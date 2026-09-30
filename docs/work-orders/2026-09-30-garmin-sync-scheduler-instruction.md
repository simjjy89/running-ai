> 원본 작업지시서 (2026-09-30, Phase 3C-3). 구현 기록은 `2026-09-30-garmin-sync-scheduler.md` 참고.
> 추가 사용자 지시: 외부 PC라 live validation 하지 않음 / scheduler는 default disabled + fixed-delay / 실패 시 같은 tick에서 retry 금지 / Phase 3C-4 시작 금지 / 결과 보고는 한글.

# RunningAI Phase 3C-3
## Automatic Garmin Incremental Sync Scheduler

## 1. 현재 상태

완료: Phase 1 Spring Boot foundation, Phase 2 PostgreSQL / Flyway / ActivityRaw, Phase 3A Garmin ingestion core, Phase 3B Real Garmin E2E, Phase 3C-1 Incremental sync, Phase 3C-2 Operational sync API / status / single-flight.
latest known commit: `38957a6`. Java regression baseline: 114 tests / 114 passed. 현재 수동 실행: `POST /api/v1/garmin/sync`.
이번 Phase 목표는 이와 동일한 incremental sync를 Spring이 주기적으로 자동 실행하도록 만드는 것이다.

## 2. 이번 Phase 목표

구현: `GarminSyncScheduler`, scheduler configuration, automatic incremental sync, single-flight 재사용, failure-safe scheduling, structured scheduler logging, scheduler enable/disable configuration.
**새로운 sync 로직을 만들지 않는다.** 반드시 기존 `GarminSyncOperationService → incremental sync` 경로를 재사용한다.

## 3. 이번 Phase에서 하지 않는 것

Python connector 자동 실행, Windows Service 등록, systemd, Docker Compose 전체 운영 구성, Raspberry Pi 배포, distributed lock, Redis lock, DB scheduler lock, 즉시 retry loop, exponential retry framework, historical backfill, ChatGPT connector, Intervals.icu.

## 4. 프로젝트 규칙

CLAUDE.md, running-ai-dev, running-ai-integration 사용. DB schema 변경은 없어야 한다.

## 5. Git 최신화

`git status; git switch main; git pull --ff-only origin main; git log --oneline -5` — latest에 `38957a6`가 존재하는지 확인.

## 6. Work Order

`docs/work-orders/2026-09-30-garmin-sync-scheduler-instruction.md`, `docs/work-orders/2026-09-30-garmin-sync-scheduler.md`.

## 7. Scheduler 구조

```text
scheduler → GarminSyncOperationService → single-flight guard → GarminIncrementalSyncService
```

Scheduler가 직접 `GarminActivitySource`, `GarminActivityIngestionService`, Repository를 호출하지 않는다. 운영 API와 자동 scheduler가 동일한 application path를 사용해야 한다.

## 8. Scheduler 설정

기본적으로 **비활성화**.

```yaml
running-ai:
  garmin:
    scheduler:
      enabled: false
      fixed-delay: 1h
      initial-delay: 1m
```

Spring Boot Duration binding 사용. 실제 naming은 현재 config convention에 맞춘다.

## 9. 왜 기본 disabled인가

개발/테스트/CI/외부 개발 PC/token 없는 PC에서 서버를 켜는 것만으로 Garmin network 호출이 발생하면 안 된다. 운영 PC에서 명시적으로 `RUNNING_AI_GARMIN_SCHEDULER_ENABLED=true` 등으로 활성화한다.

## 10. 실행 주기

기본 1 hour (개인 러닝 데이터는 초단위 실시간 sync 불필요, Garmin rate limiting 위험 감소, 운동 후 수동 POST /sync 존재). 설정으로 변경 가능.

## 11. Fixed Rate가 아니라 Fixed Delay

sync 종료 → 1시간 대기 → 다음 sync. 장시간 sync로 실행이 겹치는 것을 줄인다. single-flight guard는 최종 안전장치로 유지.

## 12. Initial Delay

서버 시작 즉시 Garmin을 호출하지 않는다. 기본 1 minute (PostgreSQL, Python connector, network 준비 시간).

## 13. Scheduler enable 조건

`@ConditionalOnProperty` 등 Spring 표준 방식. `running-ai.garmin.scheduler.enabled=true`일 때만 활성화. 불필요한 custom framework 금지.

## 14. Scheduled 실행 성공

log: fetched, created, updated, skipped, failed, pagesFetched, checkpointAdvanced.
예: `Garmin scheduled sync completed: fetched=5 created=1 updated=2 skipped=2 failed=0 pages=1 checkpointAdvanced=true`. 개인 activity 데이터 자체는 출력하지 않는다.

## 15. 이미 실행 중인 경우

수동 `POST /sync` 실행 중 scheduler tick이 발생하면 기존 single-flight를 재사용하고 `SKIPPED_ALREADY_RUNNING`으로 처리. ERROR 아님. 다음 scheduled run에서 다시 시도.

## 16. 반대 상황

Scheduler가 sync 중일 때 `POST /sync`가 들어오면 기존 정책대로 `409 GARMIN_SYNC_ALREADY_RUNNING` 유지.

## 17. Retry 정책

scheduler tick 하나 안에서 retry하지 않는다. AUTH_REQUIRED, FORBIDDEN, RATE_LIMITED, CONNECTOR_UNAVAILABLE, UPSTREAM_ERROR, INCREMENTAL_WINDOW_INCOMPLETE 발생 시: 현재 실행 종료, 로그 기록, checkpoint 정책은 기존 service에 맡김, 다음 정기 tick까지 대기.

## 18. 429 특별 정책

즉시 retry 금지, 반복 login 금지. 기존 connector 정책 유지. Scheduler가 429를 우회하거나 짧은 간격으로 재시도하지 않는다.

## 19. Authentication failure

`GARMIN_AUTH_REQUIRED` / `GARMIN_FORBIDDEN` 시 서버를 종료시키지 않는다. 로그만 남기고 다음 실행을 기다린다. 운영자가 token 상태를 수정할 수 있어야 한다.

## 20. Connector unavailable

connector 미실행 시 로그 후 현재 run 종료. Spring 서버는 정상 동작.

## 21. Unexpected exception

예상하지 못한 exception도 scheduled executor thread를 죽이면 안 된다. catch하고 로그. 기존 sync transaction semantics 변경 금지.

## 22. Logging 보안

log 금지: Garmin email, password, token, cookie, activity raw JSON, GPS, activity ID, activityName. 운영 지표만 log.

## 23. Scheduler 상태 API 추가 금지

기존 `POST /api/v1/garmin/sync`, `GET /api/v1/garmin/sync/status`만 유지.

## 24. Database

migration = NO, schema change = NO. Scheduler 실행 이력 테이블 만들지 않음. 기존 `garmin_sync_state` 사용.

## 25. Test profile

test profile에서는 scheduler가 반드시 disabled. 전체 test 실행 시 실제 network call 없음.

## 26–32. 테스트

- 26 disabled: `enabled=false`일 때 scheduler가 sync를 호출하지 않음.
- 27 enabled: scheduler execution method를 직접 테스트, `GarminSyncOperationService` 1회 호출. 실제 clock을 기다리는 느린 테스트 금지.
- 28 success: 결과가 있어도 exception 없이 완료.
- 29 already running: `GARMIN_SYNC_ALREADY_RUNNING`이면 정상 skip, 예외를 밖으로 던지지 않음.
- 30 rate limit: retry = 0, 정상 종료.
- 31 connector unavailable: 1 attempt only, no retry.
- 32 unexpected exception: 처리 후 종료, 다음 tick 가능.

## 33. Single-flight regression

Phase 3C-2 동시 실행 테스트 계속 통과. `POST /sync concurrent → 409` semantics 불변.

## 34. Existing API regression

`GET /api/v1/health`, `POST /api/v1/activities`, `GET /api/v1/activities/{id}`, `GET /api/v1/activities`, `POST /api/v1/garmin/sync`, `GET /api/v1/garmin/sync/status`, `GET /actuator/health`.

## 35. Full regression

`cd server; .\gradlew.bat clean test` — baseline 114, 신규 scheduler tests만큼 증가 가능, 모두 PASS.

## 36. Python

Python connector 변경이 없어야 한다. 변경 없으면 Python regression 생략 가능.

## 37. 외부 PC Live Validation

`LIVE_VALIDATION_NOT_RUN`으로 기록. 실패 아님.

## 38. README

`running-ai.garmin.scheduler.enabled=false`, `fixed-delay=1h`, `initial-delay=1m`을 간단히 추가하고, 운영에서는 local Garmin connector가 실행 중이어야 함을 기록.

## 39. Environment variable

Spring relaxed binding 기준 실제 환경변수 이름을 README에 명확히 적는다: `RUNNING_AI_GARMIN_SCHEDULER_ENABLED=true`, `RUNNING_AI_GARMIN_SCHEDULER_FIXED_DELAY=1h`, `RUNNING_AI_GARMIN_SCHEDULER_INITIAL_DELAY=1m`. 실제 property binding을 검증한다.

## 40. Definition of Done

GarminSyncScheduler 구현, disabled by default, enabled property, fixed-delay/initial-delay configurable (기본 1h / 1m), 기존 GarminSyncOperationService 재사용, manual sync와 single-flight 공유, already-running은 skip, no immediate retries (429 / auth / connector unavailable), unexpected exception scheduler thread 보호, no raw/personal logging, test profile network call 없음, scheduler unit tests, existing API regression, full Java tests PASS, DB migration 없음, Python 변경 없음, README, Work Order 결과 문서, secrets scan, git diff review, commit, push.

## 41. 권장 Commit

`feat: schedule automatic Garmin incremental sync`

## 42. 완료 보고

Scheduler (enabled default, fixed delay, initial delay, operation service) / Failure policy (already running, auth, forbidden, rate limit, connector unavailable, upstream, unexpected, retry) / Tests (Java total·passed·failed, Python changed) / Database (migration, schema change) / Live validation (attempted: NO, reason: external PC / no Garmin token) / Git (branch, commit, push) / Remaining limitations (connector process supervision 없음, scheduler execution history 없음, distributed lock 없음, external API authentication 없음) / Next Phase: Phase 3C-4 operational reliability / deployment (connector supervision, Windows startup/service, Spring startup, Docker/PostgreSQL startup, graceful restart, Raspberry Pi 배포 구조) — 자동 시작하지 않는다.

## 43. 핵심 invariant

```text
          ┌── Manual POST /sync
          │
          ▼
GarminSyncOperationService
          ▲
          │
          └── GarminSyncScheduler
```

두 경로 모두 동일한 single-flight + incremental sync implementation을 사용한다. Scheduler는 sync 구현을 복제하지 않는다. Scheduler는 Garmin 실패를 빠르게 반복하지 않는다.
