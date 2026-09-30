> 원본 작업지시서 (2026-09-30, Phase 3C-2). 구현 기록은 `2026-09-30-garmin-sync-api.md` 참고.

# RunningAI Phase 3C-2
## Operational Sync Trigger + Sync Status API

## 1. 현재 상태

현재 RunningAI는 다음 단계까지 완료되어 있다.

```text
Phase 1     Spring Boot foundation                  COMPLETE
Phase 2     PostgreSQL / Flyway / ActivityRaw       COMPLETE
Phase 3A    Garmin ingestion core                   COMPLETE
Phase 3A.5  Claude Code workflow                    COMPLETE
Phase 3B    Real Garmin end-to-end integration      COMPLETE
Phase 3C-1  Incremental Garmin sync                 COMPLETE
```

현재 latest known commit: `2d37ef7`

Phase 3C-1에서 다음이 구현되었다.

```text
garmin_sync_state, high-water mark, last_successful_sync_at, 7-day overlap, pagination,
max-pages guard, bootstrap sync, incremental sync, idempotent reprocessing, checkpoint failure safety
```

Live Garmin validation도 집 PC에서 완료되었다.

---

# 2. 이번 Phase 목표

현재 `syncIncremental()`은 application/service code에서 직접 호출해야 한다.

이번 Phase의 목표는 운영자가 RunningAI 서버에 명시적으로 Garmin incremental sync를 요청하고 현재 sync 상태를 조회할 수 있도록 하는 것이다.

구현 대상:

```text
POST /api/v1/garmin/sync
GET  /api/v1/garmin/sync/status
```

추가로: 동시 sync 실행 방지, structured error response, 운영용 sync result response, DB 기반 sync status 조회.

---

# 3. 이번 Phase에서 하지 않는 것

```text
@Scheduled, 자동 주기 실행, Windows Task Scheduler, connector process 자동 실행,
connector process supervision, retry scheduler, ChatGPT connector/plugin, Intervals.icu,
workout generation, historical backfill, distributed lock, Redis lock, multi-instance coordination
```

자동화는 Phase 3C-3에서 진행한다.

---

# 4. 외부 PC 환경

Git repository 사용 가능, Java 21 사용 가능, Spring tests 실행 가능. Garmin token은 없음, 실제 Garmin login은 하지 않음, live Garmin validation은 하지 않음.
이번 Phase의 모든 regression test는 network 없이 통과해야 한다.

---

# 5. 프로젝트 규칙

작업 전에 반드시 `CLAUDE.md`, `.claude/skills/running-ai-dev/SKILL.md`, `.claude/skills/running-ai-integration/SKILL.md`를 읽고 따른다.
DB schema 변경이 필요하지 않으므로 database skill은 원칙적으로 필요 없다.

---

# 6. Git 최신화

```bash
git status
git switch main
git pull --ff-only origin main
git log --oneline -5
```

latest commit에 Phase 3C-1 `2d37ef7`이 포함되는지 확인한다. local 변경사항이 있으면 덮어쓰지 않는다.

---

# 7. Work Order

지시서 원문: `docs/work-orders/2026-09-30-garmin-sync-api-instruction.md`
결과 문서: `docs/work-orders/2026-09-30-garmin-sync-api.md`

---

# 8. API 1 — Garmin Sync Trigger

```http
POST /api/v1/garmin/sync
```

body는 필요하지 않다. 호출하면 기존 incremental sync를 정확히 한 번 수행한다.

```text
Controller → GarminSyncOperationService 또는 기존 적절한 application service
  → Garmin incremental sync → Python connector → Garmin → DB
```

Controller에 business logic을 넣지 않는다.

---

# 9. Sync Response

성공 응답은 현재 incremental result를 기반으로 구성한다.

```json
{
  "fetched": 5, "created": 1, "updated": 2, "skipped": 2, "failed": 0,
  "pagesFetched": 1, "checkpointAdvanced": true,
  "highWaterStartedAt": "2026-09-29T02:10:08Z",
  "lastSuccessfulSyncAt": "2026-09-30T00:30:12Z"
}
```

실제 existing result type을 먼저 확인하고 불필요하게 같은 데이터를 복제하지 않는다. DTO를 별도로 사용하고 Entity를 직접 반환하지 않는다.

---

# 10. HTTP status

정상: `200 OK`. 이번 sync 호출은 resource creation API가 아니라 operation execution이다.

---

# 11. API 2 — Sync Status

```http
GET /api/v1/garmin/sync/status
```

Garmin connector나 Garmin upstream에 접근하지 않는다. DB의 현재 `garmin_sync_state`만 조회한다.

---

# 12. Status Response

```json
{ "initialized": true, "highWaterStartedAt": "2026-09-29T02:10:08Z", "lastSuccessfulSyncAt": "2026-09-30T00:30:12Z" }
```

sync state가 아직 없는 경우:

```json
{ "initialized": false, "highWaterStartedAt": null, "lastSuccessfulSyncAt": null }
```

404로 처리하지 않는다.

---

# 13. Connector 상태를 status API에 넣지 않는다

`connectorStatus`, Garmin auth status, Garmin health 같은 live check를 넣지 않는다. `GET /sync/status`는 빠르고 안정적인 local DB 조회 API여야 한다.

---

# 14. 동시 Sync 방지

sync request A 실행 중 sync request B가 도착하면 B가 새로운 Garmin sync를 시작하면 안 된다.

# 15. Single-flight 방식

single JVM instance 운영 전제. in-memory single-flight guard(`AtomicBoolean`, `ReentrantLock.tryLock()` 등 단순하고 명확한 방식). Redis / DB distributed lock은 추가하지 않는다.

# 16. Lock 책임 위치

Controller에 lock 로직을 넣지 않는다. 권장: `GarminSyncOperationService`에서 single-flight 후 `GarminIncrementalSyncService` 호출.

# 17. Concurrent 요청 결과

두 번째 요청은 즉시 `409 Conflict`, code `GARMIN_SYNC_ALREADY_RUNNING`.

```json
{ "code": "GARMIN_SYNC_ALREADY_RUNNING", "message": "Garmin sync is already running", "timestamp": "..." }
```

기존 Global Exception Handler 패턴에 통합한다.

# 18. Lock 해제

sync 성공, connector failure, mapping failure, unexpected exception 모두에서 lock이 해제되어야 한다. try/finally 또는 이에 준하는 안전한 방식. lock leak 금지.

# 19. Sync 실패 정책

기존 Phase 3C-1 failure policy(AUTH_REQUIRED, FORBIDDEN, RATE_LIMITED, CONNECTOR_UNAVAILABLE, UPSTREAM_ERROR, INCREMENTAL_WINDOW_INCOMPLETE)의 domain/application exception semantics를 유지한다. Controller에서 적절한 HTTP error로 변환한다.

# 20. HTTP error mapping

```text
Garmin auth required → 401 | forbidden → 403 | rate limited → 429 | sync already running → 409
connector unavailable → 503 | Garmin upstream failure → 502
incremental incomplete → 503 또는 명확한 existing policy | unexpected → 500
```

# 21. API 보안

local/private personal server이므로 Spring Security 인증 체계(JWT, OAuth, API key, login)를 도입하지 않는다.

# 22. API 범위

`POST /api/v1/garmin/sync`, `GET /api/v1/garmin/sync/status`만 추가한다. login / logout / token / raw endpoint는 추가하지 않는다.

# 23. Garmin Credential invariant

Spring은 Garmin email / password / MFA / access token / refresh token / cookie를 계속 몰라야 한다.

# 24. Status Service

기존 sync state 조회 책임이 있으면 재사용, 없으면 최소한의 query service(`GarminSyncStatusService`). class를 불필요하게 세분화하지 않는다.

# 25. High-water 의미 확인

`checkpointAdvanced` 의미를 테스트와 문서에 명시한다. 권장: `highWaterStartedAt`가 이전 값보다 앞으로 이동했음. `lastSuccessfulSyncAt`만 갱신되고 high-water가 동일하면 `checkpointAdvanced=false`. 현재 구현을 확인한 뒤 필요한 경우 최소 보정한다.

# 26. No-new-activity 시나리오

high-water = T1, 다음 sync에서 새 activity 없이 overlap 데이터만 재조회 → `created=0`, `updated>=0`, `lastSuccessfulSyncAt` 갱신, `highWaterStartedAt=T1`, `checkpointAdvanced=false`.

# 27–34. 테스트

- 27 Sync 성공: fake GarminActivitySource로 `POST /api/v1/garmin/sync` → 200, result fields.
- 28 Status initialized: initialized=true, timestamps populated.
- 29 Status uninitialized: initialized=false, timestamps=null.
- 30 Concurrent sync: 첫 sync를 block, 두 번째 → 409 `GARMIN_SYNC_ALREADY_RUNNING`; 첫 요청 종료 후 다시 sync 가능.
- 31 Lock release after failure: connector failure 후 두 번째 요청이 409가 아니라 정상 시도.
- 32 Rate limit → 429. 33 Connector unavailable → 503.
- 34 No new activity: high-water unchanged, lastSuccessfulSyncAt updated, checkpointAdvanced=false.

# 35. Existing APIs regression

`GET /api/v1/health`, `POST /api/v1/activities`, `GET /api/v1/activities/{id}`, `GET /api/v1/activities`, `GET /actuator/health` 유지.

# 36. Java Regression

`cd server; .\gradlew.bat clean test` — 기존 baseline 98 tests에서 신규 테스트만큼 증가, 모두 PASS.

# 37. Python 변경 여부

Python connector 코드를 변경하지 않는 것이 기본. 변경이 없으면 Python regression 재실행 불필요.

# 38. Live validation

결과 문서에 `LIVE_VALIDATION_NOT_RUN — Reason: external development PC has no Garmin token by design`을 기록한다. 기능 실패가 아니다.

# 39. HTTP smoke test

가능하면 test/local profile에서 controller endpoint가 뜨는 것까지만 확인. connector가 없어 POST sync가 실패하는 것은 정상. 일반 regression test로 API contract를 검증하면 충분하다.

# 40. README

`POST /api/v1/garmin/sync`, `GET /api/v1/garmin/sync/status`를 추가하고 "POST /sync requires the local Garmin connector to be running."를 기록. Garmin credential 설정을 Spring README에 넣지 않는다.

# 41. Work Order 결과

API design, single-flight implementation, error mapping, status semantics, checkpointAdvanced semantics, tests, limitations를 포함한다.

# 42. Definition of Done

POST/GET 구현, DTO 분리, Controller에 business logic 없음, 기존 incremental sync 재사용, JVM single-flight guard, concurrent → 409, failure 후 lock release, DB-only status, initialized=false, high-water semantics 고정, no-new-activity 검증, 401/403/429/503/502 mapping, 기존 API regression PASS, Java full test PASS, migration 없음, schema 변경 없음, secrets scan PASS, README, work-order 결과 문서, git diff review, commit, push.

# 43. 권장 Commit

`feat: add Garmin sync operational API`

# 44. 최종 보고 형식

API / Single-flight (implementation, concurrent behavior, failure release) / Status semantics / Checkpoint semantics (new activity, no new activity, failed sync) / Error Mapping (already running, auth required, forbidden, rate limited, connector unavailable, upstream error) / Tests (Java total·passed·failed, Python changed·tests) / Live validation (attempted: NO, reason: external PC / no Garmin token) / Database (migration NO, schema change NO) / Git (branch, commit, push) / Remaining limitations / Next Phase (3C-3: scheduler, automatic incremental sync, retry/backoff policy, connector lifecycle / operational reliability — 자동으로 시작하지 않는다).

# 45. 핵심 invariant

```text
User / future ChatGPT → POST /api/v1/garmin/sync → single-flight guard → Incremental Garmin Sync
  → Garmin Connector → Garmin → PostgreSQL

GET /api/v1/garmin/sync/status → garmin_sync_state
```

**POST /sync는 실제 동기화를 수행한다.**
**GET /status는 Garmin을 호출하지 않는다.**
**동일 JVM에서 Garmin sync는 동시에 두 개 실행되지 않는다.**
