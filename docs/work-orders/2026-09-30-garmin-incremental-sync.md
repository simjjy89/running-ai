# 2026-09-30 — Phase 3C-1: Garmin Incremental Sync / High-Water Mark / Overlap Window

| 항목 | 내용 |
|------|------|
| 날짜 | 2026-09-30 |
| 작업 | Garmin "최근 N개 전체 재조회" 방식을 high-water mark + 7일 overlap window + idempotent ingestion 기반 incremental sync로 전환 |
| 상태 | 완료 |
| 커밋 | `feat: add incremental Garmin activity sync` |
| 지시서 원문 | [2026-09-30-garmin-incremental-sync-instruction.md](2026-09-30-garmin-incremental-sync-instruction.md) |
| 이전 작업 | [2026-09-29-garmin-live-e2e-validation.md](2026-09-29-garmin-live-e2e-validation.md) |

---

## 1. 목적과 범위

Phase 3B-3까지 Garmin activity 1건에 대한 Connector → Spring → PostgreSQL E2E와 idempotency가 실제 계정으로
검증됐다. 이번 Phase는 기존 `GarminSyncService.syncRecent(limit)`(항상 최근 limit개 전체 재조회, cursor 없음)를
건드리지 않고, 그 위에 `GarminIncrementalSyncService`를 새로 추가해 high-water mark + overlap window +
idempotent ingestion 기반 incremental sync를 구현했다. Scheduler(`@Scheduled`), 운영 sync trigger(`POST
/api/v1/garmin/sync`), connector 프로세스 감독은 이번 범위 밖이며 구현하지 않았다(Phase 3C-2 후보).

## 2. 작업 전 상태

```text
branch: main (origin/main과 동일, working tree clean)
latest commit: cf4c04f test: validate live Garmin end-to-end ingestion
regression baseline: Java 83 passed / Python 31 passed
```

## 3. 핵심 설계

### garmin_sync_state (신규 테이블, athlete당 1행)

```text
id
athlete_id                (FK -> athlete, unique)
high_water_started_at     가장 최신으로 성공 처리한 activity의 startTime; 뒤로 이동하지 않음
last_successful_sync_at   incremental sync가 마지막으로 성공 완료한 시각
created_at / updated_at
```

`GarminSyncState`(entity, `athleteId`는 Activity와 동일하게 plain `Long`, JPA 연관관계 아님) /
`GarminSyncStateRepository` / `GarminSyncStateService`(조회 + `advance(athleteId, candidateHighWater,
syncedAt)`). Migration `V4__create_garmin_sync_state.sql`(V1~V3는 수정하지 않음).

### Connector / Spring pagination

- python-garminconnect의 실제 stable 함수 시그니처를 소스에서 직접 확인:
  `get_activities(start: int = 0, limit: int = 20, ...)`, `start=0`이 "가장 최신 activity"를 의미함
  (추측하지 않고 `garminconnect/__init__.py`에서 확인).
- Python connector `GET /activities?start=S&limit=N` (`start` 기본값 0, 기존 호출과 완전히 호환).
  `GarminGateway.recent_activities(limit, start=0)`, `CachedGatewayProvider.recent_activities(limit,
  start=0)`로 확장. Garmin dict → JSON 구조는 그대로 유지(필드 rename/normalization 없음).
- Spring: 기존 `GarminActivitySource.fetchRecentActivities(int limit)`를
  `fetchActivities(int start, int limit)`로 교체(불필요한 하위 호환 레이어를 추가하지 않음).
  `HttpGarminActivitySource`가 `start`/`limit` 둘 다 query parameter로 전송하고 validate(`start >= 0`,
  `1 <= limit <= 100`). 기존 `GarminSyncService.syncRecent`는 `fetchActivities(0, limit)`으로 호출부만
  최소 변경.

### GarminIncrementalSyncService.syncIncremental()

```text
checkpoint:  high-water mark (GarminSyncState.high_water_started_at)
overlap:     7일 (기본값, running-ai.garmin.sync.overlap, Duration)
page size:   50 (기본값, running-ai.garmin.sync.page-size)
max pages:   10 (기본값, running-ai.garmin.sync.max-pages)
```

- `GarminSyncState` 없음 → bootstrap: 정확히 1 page(page-size개)만 가져오고 max-pages는 적용하지 않음.
- `GarminSyncState` 있음 → cutoff = `high_water_started_at - overlap`. Garmin newest→oldest 순으로
  page(`start=0,page-size`; `start=page-size,page-size`; ...)를 가져오며, 어떤 page의 (parseable한 것 중)
  가장 오래된 startTime이 cutoff 이하로 내려가거나, page 크기가 page-size보다 작게 오거나(Garmin 이력 종료),
  max-pages를 다 썼을 때(→ `GarminIncrementalSyncException`, checkpoint 미갱신) 중단한다.
- Page 안의 모든 item은 기존 `GarminActivityIngestionService`로 그대로 ingest(동일한 raw-first, idempotent
  insert/update)된다 — overlap 안에 이미 있는 activity도 매번 다시 ingest해 Garmin 쪽 수정(거리/HR/제목/시간대
  보정)을 반영한다.
- Checkpoint는 **다음 조건을 모두 만족할 때만** advance된다: connector-level 실패 없음(있으면 즉시 propagate,
  checkpoint 코드에 도달하기 전에 method가 종료됨 — 이미 성공적으로 ingest된 이전 page는 rollback되지 않음),
  malformed item 없음(`failed == 0`), incremental(비bootstrap) 실행이라면 cutoff에 실제로 도달함(max-pages
  소진 전). High-water mark 자체는 이번 run에서 발견한 parseable startTime의 최댓값과 기존 값 중 더 최신인
  쪽으로만 이동한다(뒤로 이동 없음). Cursor는 `activityId`도 아니고 exclusive timestamp도 아니다(동일
  timestamp를 가진 서로 다른 activity 2건 모두 정상 처리됨은 테스트로 검증).

## 4. Database

```text
migration: YES (V4__create_garmin_sync_state.sql)
table: garmin_sync_state
high-water: high_water_started_at 컬럼, athlete당 1행, 뒤로 이동하지 않음
last successful sync: last_successful_sync_at 컬럼, 성공한 모든 run마다 갱신
```

V1~V3는 수정하지 않았다. H2(테스트)와 실제 PostgreSQL(local Docker Compose) 양쪽에서 migration이 정상
적용됨을 확인했다(아래 12절).

## 5. Bootstrap (실제 계정, live)

```text
pages: 1
fetched: 5   (page-size를 live 검증용으로 5로 축소해 실제 Garmin 요청을 최소화함)
created: 2
updated: 1   (Phase 3B-3에서 이미 저장돼 있던 activity가 재확인됨)
skipped: 2   (지원하지 않는 activity type; raw는 저장됨)
failed: 0
checkpoint: 생성됨 (high-water/lastSuccessfulSyncAt 모두 존재)
```

## 6. Incremental second run (실제 계정, live)

```text
pages: 1
fetched: 5
created: 0
updated: 3
skipped: 2
failed: 0
checkpoint advanced: true (high-water는 동일 값 유지, lastSuccessfulSyncAt만 갱신 — 새 activity가
                           업로드되지 않았으므로 정상 동작)
```

Live 검증에서는 실제 Garmin 요청 횟수를 최소화하기 위해 `overlap`을 `1s`로 일시적으로 줄여(운영 기본값
`7d`는 변경하지 않음) 두 번째 sync가 곧바로 cutoff에 도달하도록 했다. 두 실행 모두 임시 `CommandLineRunner`
(`running-ai.garmin.manual-live-sync=true`로만 활성화)로 `syncIncremental()`을 1회씩 호출했고, 검증 후 즉시
삭제해 최종 diff에는 포함되지 않는다.

## 7. Real-data idempotency (live)

```text
Activity row count       변화 없음 (3 -> 3)
ActivityRaw row count    변화 없음 (5 -> 5)
garmin_sync_state row    변화 없음 (id 1 유지)
high_water_started_at    변화 없음 (동일 시각)
last_successful_sync_at  갱신됨
```

## 8. Failure safety (테스트로 검증, H2)

```text
malformed:          failed > 0 이면 checkpointAdvanced=false, GarminSyncState는 생성/갱신되지 않음
                     (이미 성공 ingest된 activity는 유지)
connector failure:   page 0(50건, cutoff보다 모두 최신) 성공 커밋 후 page 1 fetch에서 connector 예외 발생
                     -> 예외가 그대로 전파되고 checkpoint는 갱신되지 않음; page 0의 Activity/ActivityRaw는
                     그대로 남음
max pages:           page-size=1, max-pages=2로 축소한 별도 테스트: cutoff 도달 전 2 page 모두 소진 시
                     GarminIncrementalSyncException 발생, checkpoint 미갱신, 3번째 page는 호출되지 않음
```

## 9. Tests

```text
Java total: 98
passed: 98
failed: 0

Python total: 36
passed: 36
failed: 0
```

신규 Java 테스트 15개(baseline 83 → 98): `GarminIncrementalSyncServiceTest`(8),
`GarminIncrementalSyncMaxPagesTest`(1), `GarminSyncStateServiceTest`(3),
`HttpGarminActivitySourceTest`에 pagination 테스트 2개 추가, `SchemaMigrationTest`에 V4 unique
constraint 테스트 1개 추가. 신규 Python 테스트 5개(baseline 31 → 36): connector/gateway의 `start`
offset 전달·검증 테스트. 두 suite 모두 Garmin live network 없이(H2/Flyway, mock connector, fixture)
통과한다 — live 검증은 별도의 manual 실행(6~7절)이며 일반 regression에 포함되지 않는다.

## 10. Live validation

```text
attempted: yes (Docker Desktop 기동 상태의 local PostgreSQL + 실제 Garmin 계정, token store 재사용)
bootstrap: PASS (5절)
second sync: PASS (6절)
duplicate rows: 없음 (7절)
```

실제 Garmin activity ID, activityName, GPS, token은 기록하지 않았다.

## 11. Encoding / secrets 관련 유지 사항

이번 Phase는 activityName encoding을 다루지 않는다(Phase 3B-3에서 이미 진단·문서화됨,
`GARMIN_ACTIVITY_NAME_ENCODING` known issue는 `running-ai-integration` skill에 그대로 유지). `activityName`은
여전히 `NormalizedActivity`에 포함되지 않으며 incremental sync의 cutoff 판단에는 `startTimeGMT`만 사용한다.

## 12. Database migration 검증

```text
H2 (테스트): SchemaMigrationTest에서 V1~V4 모두 SUCCESS, garmin_sync_state 테이블 존재, unique
             constraint(uk_garmin_sync_state_athlete) 강제됨을 확인.
PostgreSQL:  live 검증 시 로컬 Docker Compose PostgreSQL에 V4가 정상 적용됨을 실제로 확인
             ("Migrating schema \"public\" to version \"4 - create garmin sync state\"" 로그).
```

## 13. Documentation 갱신

`.claude/skills/running-ai-integration/SKILL.md`: incremental sync 섹션 추가(설계, 설정값, 알고리즘,
checkpoint 정책), 기존 `syncRecent` vs 신규 `syncIncremental` 구분, pagination된 connector/Spring 계약
갱신. `.claude/skills/running-ai-database/SKILL.md`: `garmin_sync_state` 테이블 설명과 V4 migration을
불변 목록에 추가.

## 14. Secrets / 로그 점검

- Garmin email/password/MFA/token/cookie/session, 실제 activity ID, activityName, GPS: 코드·문서·로그
  어디에도 없음.
- 임시 `ManualLiveIncrementalSyncRunner`는 결과 카운트만 로깅했고 검증 후 삭제해 최종 diff에 없음.
- `git diff`/`git status`로 최종 변경 파일을 확인해 raw payload, 빌드 산출물, IDE 파일, `.env`가 없음을
  확인했다.

## 15. Git

```text
branch: main
commit: (아래 커밋 참고)
push: 완료
```

## 16. Remaining limitation

- Historical backfill(수년 치 과거 activity 전체 동기화)은 이번 범위 밖이며, bootstrap은 첫 page(기본
  50건)만 처리한다.
- Scheduler(`@Scheduled`, OS scheduler)와 운영 sync trigger(API/CLI)는 아직 없다 — `syncIncremental()`은
  현재 애플리케이션 코드에서 직접 호출해야 한다.
- `max-pages` 초과(`INCREMENTAL_WINDOW_INCOMPLETE`) 상황을 실제 Garmin 계정으로는 재현하지 않았다(계정에
  overlap 안에서 그 정도로 많은 activity가 없음); H2 테스트로만 검증됨.
- 두 sync entry point(`syncRecent`, `syncIncremental`)가 동시에 존재하므로, 향후 둘 중 하나만 계속 쓸지
  결정이 필요하다.

## 17. Next Phase (제안, 자동 시작 안 함)

Phase 3C-2 후보:

```text
operational sync trigger (manual command 또는 API)
sync status/result 조회
```
