# Phase 6A Workout Publish Application Service & Manual Operational Trigger — result

Baseline `4e229af`. Implemented, tested (431 / 431), **not enabled anywhere**.

> **Operational invariant: `WORKOUT_PUBLISHING_ENABLED` must remain `false` until the main-PC legacy workout publisher is disabled.**

## Application service
- Class: `com.runningai.integration.intervals.WorkoutPublishApplicationService` (`@Service`, not `@Transactional`: no DB transaction is held across Intervals HTTP calls)
- Input: `LocalDate scheduledDate` (athlete-local, explicit)
- Prescription source: existing `WorkoutIntensityTargetService.targetedPrescribe(date)` (→ `WorkoutPrescriptionService` + athlete intensity profile) — reused, no new generator
- Mapper: `StructuredWorkoutMapper` · Renderer: `IntervalsWorkoutRenderer` · Publisher: `IntervalsWorkoutPublisher` (all existing beans, unchanged)
- Output: `WorkoutPublishResponse(date, operation, verified, intent, stepCount)`; remote event id deliberately not exposed
- It recomputes/formats nothing, builds no `external_id`, makes no Intervals HTTP call (the unit tests assert the stored description equals exactly what the existing mapper + renderer produce)

## Operational endpoint
- Method / path: `POST /api/v1/workout-publish` (repository convention is flat `/api/v1/<name>`, so the suggested `/training/workouts/publish` was not used)
- Request: `{"date":"2026-10-02"}` (required; invalid → 400 `VALIDATION_ERROR` / `INVALID_REQUEST`)
- Response 200: `{"date":"2026-10-02","operation":"CREATED","verified":true,"intent":"EASY","stepCount":3}`
- Controller `WorkoutPublishController` is a transport adapter only; GET never publishes (it is not mapped; see limitation below)
- Security: no authentication exists — private network only, never expose to the public Internet

## Safety switch
- Property: `running-ai.workout-publishing.enabled` (`WorkoutPublishProperties`)
- Default: **false** (also pinned in `application.yml` as `${WORKOUT_PUBLISHING_ENABLED:false}`, and `.env.example`)
- Environment override: `WORKOUT_PUBLISHING_ENABLED=true`
- Disabled behavior: nothing is fetched or written; 409 `WORKOUT_PUBLISHING_DISABLED` (not 401/403); the guard is not taken

## Concurrency
- Guard: in-memory `ConcurrentHashMap` key set in the application service (single Spring instance; no Redis / DB / ShedLock)
- Key: scheduled date
- Same-date behavior: second request fails immediately with 409 `WORKOUT_PUBLISH_ALREADY_RUNNING` (no queueing, nothing written by the rejected request)
- Different-date behavior: not blocked (verified: while date A is held, date B publishes)
- Failure release: `finally` removes the key after success, prescription failure and publisher failure (all three tested)

## Idempotency
Entirely the existing publisher's (tested through the service with the stateful fake):
first publish `CREATED`; identical re-publish `NO_CHANGE` (no write); changed prescription `UPDATED` on the same remote event, one event total.

## Error mapping (`WorkoutPublishExceptionHandler`, ordered before the global handler)
- disabled / already running: 409 `WORKOUT_PUBLISHING_DISABLED` / `WORKOUT_PUBLISH_ALREADY_RUNNING`
- Intervals (code = `INTERVALS_<REASON>`): AUTH_FAILED 401, FORBIDDEN 403, RATE_LIMITED 429, NOT_CONFIGURED / CONNECTION_FAILED 503, TIMEOUT 504,
  UPSTREAM_ERROR / READBACK_MISMATCH / CLIENT_ERROR / INVALID_RESPONSE 502, DUPLICATE_OWNED_WORKOUT / UNMANAGED_WORKOUT_CONFLICT 409,
  EMPTY_WORKOUT 422 (e.g. a REST day: nothing to publish)
- domain errors from the prescription flow: unchanged global handler mapping
- Success is only ever returned when the publisher returned `verified=true`; a readback mismatch stays a 502 error

## Tests
- Java total: **431** · passed: **431** · failed: **0** (skipped 0) — `gradlew clean test`, JDK 21 (baseline 396, **35 new**)
- New: `WorkoutPublishApplicationServiceTest` (12: disabled, null date, CREATED→NO_CHANGE→UPDATED same event, exact mapper/renderer output, bean orchestration,
  readback-mismatch and prescription failure propagation, same-date rejection + re-call, different dates, guard release after prescription and publisher failure, disabled keeps guard free),
  `WorkoutPublishApiTest` (17: CREATED / UPDATED / NO_CHANGE responses without remote id, missing and unparsable date, GET never publishes, 11 Intervals reason mappings),
  `WorkoutPublishGuardMappingTest` (2), `WorkoutPublishSwitchOffApiTest` (1: real service refuses end to end), `WorkoutPublishPropertiesTest` (3: default false, override, shipped yaml default; environment-independent)
- No real network: publisher/prescription source mocked or fake client; Spring tests pin blank key + unreachable URL + explicit enabled value

## Database
migration: **none** · schema: **none** (no trigger state is persisted)

## Live validation
attempted: **no** — not required; publisher is SERVER_VERIFIED and the Garmin side DEVICE_VERIFIED (5C). No real calendar write, `WORKOUT_PUBLISHING_ENABLED` was never set to true outside test properties.

## Legacy safety
- main-PC legacy writer: **possibly ACTIVE** (owner action pending since 5C-5)
- Spring publishing default: **DISABLED**
- safe to enable now: **NO**

### Main-PC cutover procedure (not executed)
1. Investigate the main-PC legacy Scheduled Tasks (read-only `Get-ScheduledTask` + `.Actions`, see the 5C-5 result)
2. Disable the legacy writer (`Disable-ScheduledTask`, reversible)
3. Stop manual shortcuts / habits that run `create-today-workout.ps1`
4. Confirm the legacy writer no longer runs
5. Deploy / start the Spring server and confirm health
6. Set `WORKOUT_PUBLISHING_ENABLED=true`
7. Manual `POST /api/v1/workout-publish` smoke test with an explicit empty date
8. Scheduler stays OFF (none exists)

## Boundaries
- Scheduler (Phase 6B): will call `WorkoutPublishApplicationService`, not the controller, publisher or client; not started
- ChatGPT (Phase 6C): will also call the service via its own authenticated boundary; no ChatGPT-specific fields exist here; not started

## Limitations / notes
- `GET /api/v1/workout-publish` does not publish but currently returns 500 instead of 405: the pre-existing `GlobalExceptionHandler` catch-all turns
  `HttpRequestMethodNotSupportedException` into 500 for every endpoint. Not changed here (out of scope); the test only asserts "no publish, not 2xx".
- Guard is per JVM; two Spring instances or the legacy writer on the main PC are not covered (hence the disabled default and cutover order).
- No past/future date limit and no dry-run/preview endpoint (the existing prescription / targets GET APIs serve as preview).
- No authentication on the endpoint.

## Git
See the final report (branch main, commit message `feat: add operational workout publish trigger`).

## Next (not started)
Phase 6B Workout Publishing Scheduler — prerequisite: **main-PC legacy writer disabled**; do not operationally enable it before that.
