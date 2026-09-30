# 2026-09-30 — Phase 3C-3: Automatic Garmin Incremental Sync Scheduler

| 항목 | 내용 |
|------|------|
| 날짜 | 2026-09-30 |
| 작업 | 기존 incremental sync를 주기적으로 자동 실행하는 opt-in scheduler |
| 상태 | 완료 (외부 PC, live validation 미수행) |
| 커밋 | `feat: schedule automatic Garmin incremental sync` |
| 지시서 원문 | [2026-09-30-garmin-sync-scheduler-instruction.md](2026-09-30-garmin-sync-scheduler-instruction.md) |
| 이전 작업 | [2026-09-30-garmin-sync-api.md](2026-09-30-garmin-sync-api.md) |

## 1. 구조

```text
POST /api/v1/garmin/sync ─┐
                          ├─► GarminSyncOperationService (single-flight) ─► GarminIncrementalSyncService ─► connector ─► Garmin ─► DB
GarminSyncScheduler ──────┘
```

- `GarminSyncScheduler`: `@Scheduled(fixedDelayString, initialDelayString)` 메서드 `runScheduledSync()` 하나. `GarminSyncOperationService.runSync()`만 호출한다(source/ingestion/repository 직접 호출 없음, sync 로직 복제 없음).
- `GarminSyncSchedulingConfig`: `@EnableScheduling`. scheduler와 같은 `@ConditionalOnProperty(running-ai.garmin.scheduler.enabled=true)` 조건이라 비활성 시 scheduling 인프라 자체가 뜨지 않는다.
- 새 API, migration, 실행 이력 테이블, Python 변경 없음.

## 2. 설정

```yaml
running-ai.garmin.scheduler:
  enabled: false        # 기본 OFF
  fixed-delay: 1h       # 이전 tick 종료 후 대기 (fixed delay, fixed rate 아님)
  initial-delay: 1m     # 서버 시작 직후 Garmin을 호출하지 않음
```

환경변수(Spring relaxed binding): `RUNNING_AI_GARMIN_SCHEDULER_ENABLED=true`, `RUNNING_AI_GARMIN_SCHEDULER_FIXED_DELAY=1h`, `RUNNING_AI_GARMIN_SCHEDULER_INITIAL_DELAY=1m`.
`h/m/s` 단순 duration 표기는 Spring 6 `@Scheduled` 문자열 속성에서 동작함을 테스트로 확인했다(2h / 30m).
기본 disabled 이유: 테스트·CI·token 없는 PC에서 서버를 켜기만 해도 Garmin/connector를 호출하면 안 된다.

## 3. Failure policy (한 tick = 시도 1회, tick 내 retry 없음)

| 상황 | scheduler 동작 | 로그 |
|------|----------------|------|
| 성공 | 종료 | INFO fetched/created/updated/skipped/failed/pages/checkpointAdvanced |
| 이미 실행 중(수동 sync 등) | 정상 skip, 예외 없음 | INFO `SKIPPED_ALREADY_RUNNING` |
| AUTH_REQUIRED / FORBIDDEN | 종료, 서버 유지, 다음 tick 대기 | WARN reason, http status |
| RATE_LIMITED (429) | 즉시 retry·재로그인 없음, 다음 정기 tick까지 대기 | WARN |
| connector UNAVAILABLE / UPSTREAM / CONNECTOR_ERROR / INVALID_RESPONSE | 종료, 다음 tick | WARN |
| INCREMENTAL_WINDOW_INCOMPLETE | 종료, 다음 tick (checkpoint 정책은 기존 service) | WARN |
| 예상 못한 RuntimeException | catch하여 scheduler thread 보호 | ERROR (예외 클래스명만) |

로그에는 activity 데이터, ID, 이름, GPS, credential, raw JSON을 넣지 않는다(카운트와 reason 코드만). 반대 방향은 그대로다: scheduler가 실행 중일 때 `POST /sync`는 409 `GARMIN_SYNC_ALREADY_RUNNING`.

## 4. Tests

```text
Java: clean test → 128 total, 128 passed, 0 failed   (기존 114 + 신규 14)
Python: 변경 없음, 실행 안 함
```

- `GarminSyncSchedulerTest` (12, Spring 없음, clock 대기 없음): tick당 `runSync` 정확히 1회 / already-running skip / `GarminConnectorException.Reason` 7종 전부 1회 시도·예외 없음(429 포함) / window 미완 / 예상 못한 예외 후 다음 tick 정상(각 tick 1회).
- `GarminSyncSchedulerDisabledTest` (1): 기본·test profile에서 scheduler bean, config, `ScheduledAnnotationBeanPostProcessor` 부재 → 서버 기동만으로 Garmin 호출 불가.
- `GarminSyncSchedulerWiringTest` (2): `enabled=true, fixed-delay=2h, initial-delay=30m`일 때 `FixedDelayTask` 1개가 2h / 30m로 등록됨(connector mock, 미호출 확인). 환경변수 이름 3개가 property로 binding됨.
- Phase 3C-2 동시 실행 409·lock 해제 테스트와 기존 API 테스트는 변경 없이 통과.

## 5. Live validation

```text
LIVE_VALIDATION_NOT_RUN
Reason: external development PC has no Garmin token by design
```

## 6. Database / Git

migration 없음, schema 변경 없음(V1–V4 미수정). Garmin credential 설정 추가 없음.

## 7. Limitations

- connector 프로세스 감독/자동 실행 없음: connector가 죽어 있으면 매 tick이 UNAVAILABLE로 실패만 기록한다.
- scheduler 실행 이력 없음(로그만). 마지막 성공 시각은 `GET /sync/status`의 `lastSuccessfulSyncAt`으로 확인.
- 다중 인스턴스에서 동시 scheduler 실행 방지 없음(JVM 내부 single-flight만). distributed lock 없음.
- 인증 없음(로컬/private 전제). tick 간 backoff 확대(연속 실패 시 간격 증가) 없음, 고정 간격만.
- 실제 Garmin 대상 scheduled 동작은 메인 PC에서 `RUNNING_AI_GARMIN_SCHEDULER_ENABLED=true`로 확인해야 한다.

## 8. Next Phase (자동 시작 안 함)

Phase 3C-4: 운영 안정성/배포 — connector process supervision, Windows startup/service, Spring·Docker/PostgreSQL 기동, graceful restart, 향후 Raspberry Pi 배포 구조.
