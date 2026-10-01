# Phase 6B Automatic Workout Publishing Scheduler — result

Baseline `6822abb`. Implemented and tested (448 / 448); **not enabled anywhere** (no live Intervals/Garmin validation, no operational switch changed).

> **Operational prerequisite: the MAIN-PC LEGACY WRITER MUST BE DISABLED before `WORKOUT_PUBLISHING_ENABLED` or `WORKOUT_PUBLISHING_SCHEDULER_ENABLED` is ever set to true,
> and manual Spring publishing must pass an operational smoke test before the scheduler is turned on.**

## Scheduler
- Class: `com.runningai.integration.intervals.WorkoutPublishingScheduler` (+ `WorkoutPublishingSchedulingConfig` that carries `@EnableScheduling`, both only when the scheduler switch is true)
- Calls: **only** `WorkoutPublishApplicationService.publish(today)`, once per tick. Its constructor takes the service, `WorkoutPublishProperties` and `Clock` — a unit test asserts it has no
  mapper / renderer / publisher / client / target-service dependency
- Date source: `LocalDate.now(clock.withZone(configuredZone))`, with the existing shared `Clock` bean (no new time abstraction, no `systemDefault()`)
- Timezone: `scheduler.zone` (default `Asia/Seoul`), used both for the cron (`@Scheduled(zone=…)`) and for deciding "today"; the OS time zone is never consulted

## Configuration (`WorkoutPublishProperties`, extended; `application.yml`, `.env.example`)
- Master switch: `running-ai.workout-publishing.enabled` / `WORKOUT_PUBLISHING_ENABLED` — default **false**
- Scheduler switch: `running-ai.workout-publishing.scheduler.enabled` / `WORKOUT_PUBLISHING_SCHEDULER_ENABLED` — default **false**
- Cron: `…scheduler.cron` / `WORKOUT_PUBLISHING_SCHEDULER_CRON` — default `0 0 5 * * *` (technical default, not an operating policy)
- Zone: `…scheduler.zone` / `WORKOUT_PUBLISHING_SCHEDULER_ZONE` — default `Asia/Seoul` (bound as `ZoneId`: an invalid zone fails at bind; an invalid cron fails at Spring startup)
- Switch matrix: master off / scheduler off → everything blocked; master on / scheduler off → manual POST only; both on → manual + automatic.
  Scheduler on with master off is not a startup error: each tick is refused by the application service (`WORKOUT_PUBLISHING_DISABLED`) and logged, never bypassed.
- Disabled scheduler → the bean and its scheduling config do not exist (tested)

## Safety state
- Spring publishing enabled now: **NO**
- Scheduler enabled now: **NO**
- Main-PC legacy writer: **ACTIVE possible** (owner action pending since 5C-5)
- Safe to activate scheduler: **NO**

## Runtime behavior
- CREATED / UPDATED / NO_CHANGE: all normal completions, logged with date / operation / verified / intent / stepCount (NO_CHANGE after a manual publish is not an error)
- already running (same date, 6A per-date single-flight, no extra lock): tick logged as `SKIPPED_ALREADY_RUNNING` and ends; no wait, no retry
- master switch off: logged as refused (`WORKOUT_PUBLISHING_DISABLED`); not bypassed
- Intervals / other failures: logged with date + error code (or exception class) only; the tick ends; nothing escapes the scheduler thread; the next tick runs normally (tested: fail → succeed)
- REST day / empty workout: the existing contract is "empty rendered workout = error" (`WorkoutPrescriptionPolicy` gives a 0-minute REST segment, the renderer returns empty text, the
  publisher throws `INTERVALS_EMPTY_WORKOUT`, HTTP 422 in 6A). That is kept unchanged: the scheduler logs it as a warning on rest days and invents no `SKIPPED_REST_DAY`.
  Expect that warning on every planned rest day once enabled; whether it should become a quiet skip is a domain decision for a later phase.
- retry: **none** · missed run (server down at the cron time): **no catch-up** (plain `@Scheduled` semantics) · no execution history, no notifications

## Tests
- previous: 431 · new: **17** · total: **448** · passed: **448** · failed: **0** (skipped 0) — `gradlew clean test`, JDK 21
- New: `WorkoutPublishingSchedulerTest` (8: Seoul date at a UTC date boundary, zone taken from config, only-application-service dependencies, CREATED/UPDATED/NO_CHANGE complete,
  NO_CHANGE after manual publish, every failure kind ends the tick with exactly one call and no retry, fail → next tick succeeds, scheduler-on/master-off refused by the real service with
  no target-service/publisher interaction), `WorkoutPublishingSchedulerWiringTest` (2: enabled → one bean and one cron task with the configured expression and zone, verified by
  `nextExecution` in Asia/Tokyo; master stays off when only the scheduler is on), `WorkoutPublishingSchedulerDisabledTest` (2: no bean / no scheduling config; manual trigger unaffected),
  `WorkoutPublishPropertiesTest` (+5: scheduler defaults, independence of switches, cron/zone override, invalid zone, shipped yaml defaults)
- No network: the application service is mocked everywhere a scheduler runs; Spring tests pin blank Intervals key + unreachable URL and an explicit switch value

## Database
migration: **none** · schema: **none**

## Live validation
attempted: **NO** · scheduler enabled outside tests: **NO** · master switch enabled outside tests: **NO**

## Operational activation order (not executed)
1. list the main-PC legacy Scheduled Tasks · 2. disable the legacy writers (create-today-workout / command-channel) · 3. stop legacy manual runs · 4. update Spring to the latest main ·
5. restart · 6. `WORKOUT_PUBLISHING_ENABLED=true` · 7. keep `WORKOUT_PUBLISHING_SCHEDULER_ENABLED=false` · 8. manual `POST /api/v1/workout-publish` smoke test ·
9. check Intervals and Garmin · 10. only then `WORKOUT_PUBLISHING_SCHEDULER_ENABLED=true`. Never raise both switches in one step. Garmin delivery stays Intervals/Garmin's job (the scheduler never calls Garmin).

## Limitations / technical debt
- `GET /api/v1/workout-publish` returns 500 instead of 405 (pre-existing global catch-all) — untouched.
- Per-JVM guard only: two Spring instances or the legacy writer are not covered (hence the ordering above).
- A failure is only a log line: no retry, no notification, no history; a missed 05:00 is not replayed. Future candidates: missed-schedule recovery, recovery-aware generation time,
  retry/notification policy, publish audit history.
- REST-day warning noise (see above).

## Git
See the final report (branch main, commit `feat: add automatic workout publishing scheduler`).

## Next (not started)
Phase 6C — ChatGPT → RunningAI workout publish integration. Implementing it and operationally enabling it are separate decisions; 6C is not started.
