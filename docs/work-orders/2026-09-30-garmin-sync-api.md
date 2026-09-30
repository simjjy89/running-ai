# 2026-09-30 — Phase 3C-2: Operational Sync Trigger + Sync Status API

| 항목 | 내용 |
|------|------|
| 날짜 | 2026-09-30 |
| 작업 | `POST /api/v1/garmin/sync`, `GET /api/v1/garmin/sync/status`, single-flight guard |
| 상태 | 완료 (외부 PC, live validation 미수행) |
| 커밋 | `feat: add Garmin sync operational API` |
| 지시서 원문 | [2026-09-30-garmin-sync-api-instruction.md](2026-09-30-garmin-sync-api-instruction.md) |
| 이전 작업 | [2026-09-30-garmin-incremental-sync.md](2026-09-30-garmin-incremental-sync.md) |

## 1. 범위

3C-1의 `GarminIncrementalSyncService.syncIncremental()`을 운영자가 HTTP로 호출하고 checkpoint 상태를 조회할 수 있게 한다.
scheduler, connector 감독, 인증, distributed lock, migration, Python 변경은 하지 않았다.

## 2. API design

| Endpoint | 동작 |
|----------|------|
| `POST /api/v1/garmin/sync` | body 없음. incremental sync를 정확히 1회 수행, 200 + `GarminSyncResponse` |
| `GET /api/v1/garmin/sync/status` | `garmin_sync_state`만 조회(connector/Garmin 호출 없음), 200 + `GarminSyncStatusResponse` |

```text
Controller(GarminSyncController, 로직 없음)
  → GarminSyncOperationService (single-flight) → GarminIncrementalSyncService → connector → Garmin → DB
  → GarminSyncStatusService → GarminSyncStateService → garmin_sync_state
```

- `GarminSyncResponse`: `fetched, created, updated, skipped, failed, pagesFetched, checkpointAdvanced, highWaterStartedAt, lastSuccessfulSyncAt`.
  기존 `GarminIncrementalSyncResult`를 재사용하고 `lastSuccessfulSyncAt`만 sync 종료 후 status service에서 읽어 붙인다(결과 record 복제·변경 없음). Entity는 노출하지 않는다.
- `GarminSyncStatusResponse`: `initialized, highWaterStartedAt, lastSuccessfulSyncAt`. state가 없으면 `false, null, null` (404 아님, null은 명시적으로 직렬화).
- `connectorStatus`/Garmin auth 같은 live check는 status에 없다.

## 3. Single-flight

- `GarminSyncOperationService`가 `ReentrantLock.tryLock()`으로 JVM 내 동시 실행을 1개로 제한한다(in-memory, 단일 인스턴스 전제).
- 실행 중 두 번째 요청은 대기·큐잉 없이 즉시 `GarminSyncAlreadyRunningException` → **409 `GARMIN_SYNC_ALREADY_RUNNING`**. 새 Garmin sync는 시작되지 않는다.
- `try/finally`로 unlock: 성공, connector 실패, `INCREMENTAL_WINDOW_INCOMPLETE`, 예상 못한 예외(500) 모두에서 해제된다(테스트로 검증). distributed lock은 없다.

## 4. Error mapping

`GarminSyncExceptionHandler`(`@RestControllerAdvice(assignableTypes = GarminSyncController)`, `HIGHEST_PRECEDENCE`)가 기존 `ErrorResponse{code,message,timestamp}`로 변환한다.
`GlobalExceptionHandler`의 catch-all보다 먼저 적용되도록 order를 둔다. 메시지에는 credential/payload가 없다.

| 상황 | HTTP | code |
|------|------|------|
| 이미 실행 중 | 409 | `GARMIN_SYNC_ALREADY_RUNNING` |
| connector 401 (`AUTH_REQUIRED`) | 401 | `GARMIN_AUTH_REQUIRED` |
| `FORBIDDEN` | 403 | `GARMIN_FORBIDDEN` |
| `RATE_LIMITED` | 429 | `GARMIN_RATE_LIMITED` |
| `UNAVAILABLE` (connector 불가/timeout) | 503 | `GARMIN_CONNECTOR_UNAVAILABLE` |
| `UPSTREAM_ERROR`, `CONNECTOR_ERROR`, `INVALID_RESPONSE` | 502 | `GARMIN_UPSTREAM_ERROR` |
| `GarminIncrementalSyncException` (max-pages 소진) | 503 | `INCREMENTAL_WINDOW_INCOMPLETE` |
| 그 외 | 500 | `INTERNAL_SERVER_ERROR` (기존 handler) |

3C-1의 예외 semantics(checkpoint 미전진, 이미 ingest된 activity 유지)는 변경하지 않았다.

## 5. Status / checkpoint semantics

- `initialized`: `garmin_sync_state` 행 존재 여부. `highWaterStartedAt`: 처리된 가장 최신 activity 시작 시각(뒤로 가지 않음). `lastSuccessfulSyncAt`: 마지막 성공 sync 종료 시각.
- **`checkpointAdvanced` 의미 고정(최소 보정 1건)**: 3C-1은 `failed==0`이면 항상 true였다. 이제 **high-water mark가 앞으로 이동했거나 checkpoint가 새로 생성됐을 때만 true**.

| 시나리오 | created | high-water | lastSuccessfulSyncAt | checkpointAdvanced |
|----------|---------|-----------|----------------------|--------------------|
| 첫 sync / 새 activity | ≥ 1 | 앞으로 이동(또는 생성) | 갱신 | true |
| 새 activity 없음(overlap만) | 0 | 그대로 | 갱신 | **false** |
| 실패 sync (connector 오류, malformed, window 미완) | - | 그대로 | 그대로 | false (connector 오류/미완은 예외로 200 없음) |

기존 3C-1 테스트(advanced=true인 케이스는 모두 high-water가 실제로 이동)는 수정 없이 통과한다.

## 6. Tests

```text
Java: clean test → 114 total, 114 passed, 0 failed   (기존 98 + GarminSyncApiTest 16)
Python: 변경 없음 → 재실행 안 함
```

`GarminSyncApiTest` (`@SpringBootTest` + MockMvc, `GarminActivitySource`만 mock, network 없음):
status 미초기화 / status 조회(connector 미호출 검증) / sync 성공 + checkpoint 생성 / **no-new-activity(high-water 유지, lastSuccessfulSyncAt 갱신, advanced=false)** /
실패 시 checkpoint 미생성 / connector Reason 7종 → HTTP·code 매핑(401/403/429/503/502×3) / max-pages 소진 → 503 /
**동시 요청(첫 sync를 latch로 block) → 409, 종료 후 정상** / 실패(429) 후 lock 해제 / 예상 못한 예외(500) 후 lock 해제.
기존 API(`health`, `activities`, actuator) 테스트는 변경 없이 통과.

## 7. Live validation

```text
LIVE_VALIDATION_NOT_RUN
Reason: external development PC has no Garmin token by design
```

기능 실패가 아니다. `POST /sync`의 실제 동작은 메인 PC에서 connector를 띄우고 확인해야 한다.

## 8. Database / Git

migration 없음, schema 변경 없음(V1–V4 미수정). Python connector 변경 없음. Spring에 Garmin credential/token 설정 추가 없음.

## 9. Limitations

- 인증 없음(local/private 전제). API를 외부에 노출하면 누구나 sync를 트리거할 수 있다.
- single-flight는 JVM 내부뿐. 다중 인스턴스 조정 없음.
- 요청은 동기식으로 sync 완료까지 응답을 잡고 있다(최대 max-pages × read-timeout 30s). 비동기/job 모델 없음.
- scheduler, retry/backoff, connector lifecycle supervision 없음.
- `lastSuccessfulSyncAt`은 sync 후 별도 조회로 붙인다(동시 다른 경로가 없어 실질적으로 동일).

## 10. Next Phase (자동 시작 안 함)

Phase 3C-3: scheduler, 자동 incremental sync, retry/backoff 정책, connector lifecycle / 운영 안정성.
