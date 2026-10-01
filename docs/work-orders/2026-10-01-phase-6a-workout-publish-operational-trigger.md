# Phase 6A Workout Publish Application Service & Manual Operational Trigger — work order

(Condensed faithful transcription of the user's instruction; rules and acceptance criteria are kept, repeated examples shortened.)

Baseline: `4e229af`. Canonical, verified pipeline: `TargetedWorkoutPrescription → StructuredWorkoutMapper → StructuredWorkout →
IntervalsWorkoutRenderer → RenderedIntervalsWorkout → IntervalsWorkoutPublisher → Intervals.icu → Garmin → Forerunner 265`
(renderer UNIT_VERIFIED, publisher/readback SERVER_VERIFIED, pace / %LTHR / treadmill cue DEVICE_VERIFIED). The main-PC legacy
writer may still be ACTIVE, so the new trigger ships **disabled by default**.

## Goal
Give the existing pipeline (no production caller today) a real entrypoint: `Manual HTTP trigger → WorkoutPublishApplicationService →
existing prescription flow → mapper → renderer → publisher`. After this phase the user can publish one explicitly dated workout through Spring by hand.

## In scope
1. `WorkoutPublishApplicationService`; 2. manual operational HTTP trigger (POST, explicit date); 3. enable/disable safety switch;
4. per-date in-JVM single-flight guard; 5. operational response DTO; 6. exception/error mapping; 7. tests; 8. docs.

## Out of scope
`@Scheduled` / automatic daily publish (Phase 6B), ChatGPT connector/plugin (Phase 6C), direct Garmin API, legacy deletion, automatic
disabling of legacy scheduled tasks, DB migration, remote-event persistence, new workout generation algorithm, renderer / publisher /
client / mapper / cue-formatter semantic changes, Spring Security, dry-run/preview endpoint, distributed locks (Redis, DB advisory, ShedLock, Zookeeper).
Do not start Phase 6B.

## Rules
- Follow `CLAUDE.md`, the project skills and Phase 5C docs; do not re-investigate the repo. `git status`, `git switch main`,
  `git pull --ff-only origin main`; `4e229af` included, clean tree; never reset/delete uncommitted user changes.
- Controller is a transport adapter only; orchestration lives in the application service, so a future scheduler (6B) and ChatGPT
  tool (6C) call the same service. No ChatGPT-specific fields.
- Service responsibilities: check enabled → per-date guard → obtain `TargetedWorkoutPrescription` from the existing canonical
  prescription/application flow (reuse, do not build a generator) → `StructuredWorkoutMapper` → `IntervalsWorkoutRenderer` →
  `IntervalsWorkoutPublisher` → operational result. Must NOT: recompute recommendation, pace / LTHR, format pace / HR / cue strings,
  call Intervals HTTP directly, create `external_id`.
- Safety switch: `running-ai.workout-publishing.enabled: ${WORKOUT_PUBLISHING_ENABLED:false}`, default **false**. Disabled →
  `WORKOUT_PUBLISHING_DISABLED`, HTTP 409 (never 401/403). `WORKOUT_PUBLISHING_ENABLED=true` is not to be used on any PC (including
  this external PC) until the main-PC legacy workout publisher is disabled; tests may set it true via test properties.
- Endpoint: follow the existing URL convention (recommended `POST /api/v1/training/workouts/publish`; convention wins), body
  `{"date": "yyyy-MM-dd"}`, date required, no new past/future limits, no implicit "today". GET must never publish.
- Response: minimal (date, operation CREATED/UPDATED/NO_CHANGE, verified, intent, stepCount); remote event id not exposed.
  Success only when the publisher returned verified=true; readback mismatch stays an error.
- Idempotency stays entirely the publisher's (first CREATED, same NO_CHANGE, changed UPDATED).
- Single-flight: in-memory, per scheduled date (single Spring instance), same date running → `WORKOUT_PUBLISH_ALREADY_RUNNING`
  (409, immediate, no queueing); different dates may run in parallel (no global lock); always released in `finally`, including after failure.
- Error propagation/mapping through the existing handler pattern: WORKOUT_PUBLISHING_DISABLED, WORKOUT_PUBLISH_ALREADY_RUNNING,
  INTERVALS_NOT_CONFIGURED / AUTH_FAILED / FORBIDDEN / RATE_LIMITED / UPSTREAM_ERROR / READBACK_MISMATCH / DUPLICATE_OWNED_WORKOUT /
  UNMANAGED_WORKOUT_CONFLICT, prescription/domain validation errors, invalid request 400.
- Security: no new framework; document that the endpoint must not be exposed to the public Internet. Logging: date, operation,
  verified only (never key, Authorization, workout text, physiological data, raw responses).
- Tests: service (disabled; CREATED / UPDATED / NO_CHANGE; publisher exception propagation; mapper→renderer→publisher orchestration;
  no recomputation), concurrency (same-date rejected, re-call after finish OK, guard released after failure, different dates), controller
  (valid POST, missing date, disabled, already-running mapping, CREATED / NO_CHANGE responses), configuration (default false, override true,
  independent of the real environment). No real network in regression; no test may write to a real calendar (keep the 5C wiring pattern).
- Live Intervals write: not required (SKIPPED). DB: no migration, no schema change.
- Docs: canonical operational flow, endpoint + examples, default-disabled policy, single-flight, error codes, main-PC cutover
  instructions, scheduler boundary, ChatGPT boundary; minimal `README` / `CLAUDE.md` update (e.g. "Workout publishing is operationally
  disabled by default; do not enable it while the legacy main-PC writer is active"). Result document with the invariant
  `WORKOUT_PUBLISHING_ENABLED must remain false until the main-PC legacy workout publisher is disabled.`
- Cutover procedure (document): 1 investigate main-PC legacy scheduled tasks; 2 disable legacy writer; 3 stop manual shortcuts/habits;
  4 confirm legacy no longer runs; 5 deploy/start Spring and confirm; 6 `WORKOUT_PUBLISHING_ENABLED=true`; 7 manual publish smoke test; 8 scheduler still OFF.
- Finish: `gradlew clean test` (baseline 396 / 396, count expected to grow), diff review, secrets check (env var names OK, values never),
  commit (`feat: add operational workout publish trigger`, repository convention first), push.

## Completion report format
Application service (class, input, prescription source, mapper, renderer, publisher, output) / Operational endpoint / Safety switch /
Concurrency (guard, key, same-date, different-date, failure release) / Idempotency / Error mapping / Tests / Database / Live validation /
Legacy safety (main-PC legacy writer: possibly ACTIVE; Spring publishing default: DISABLED; safe to enable now: NO) / Git / Next
(Phase 6B scheduler, prerequisite MAIN PC LEGACY WRITER = DISABLED; not auto-started).
