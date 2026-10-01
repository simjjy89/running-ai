# Phase 6B Automatic Workout Publishing Scheduler — work order

(Condensed faithful transcription of the user's instruction; rules and acceptance criteria are kept, repeated examples shortened.)

Baseline: `6822abb` (Phase 6A: `WorkoutPublishApplicationService`, `POST /api/v1/workout-publish`, per-date single-flight, master switch
default off; 431 / 431). Operating state: main-PC legacy writer possibly ACTIVE; Spring workout publishing DISABLED; no scheduler yet.
**This phase must not enable any automatic publishing.**

## Goal
`WorkoutPublishingScheduler → WorkoutPublishApplicationService.publish(today) → existing 6A / 5C pipeline → Intervals.icu → Garmin`.
The scheduler is an orchestration trigger only; it may call **only** `WorkoutPublishApplicationService` (never the target service, mapper,
renderer, publisher or client). Manual POST, scheduler and a future ChatGPT tool all converge on that one service.

## In scope
Scheduler class; scheduler-specific enable switch; configurable cron; configurable timezone; clock/date handling; success/failure logging;
overlap behaviour; tests; operational docs.

## Out of scope
ChatGPT integration, Garmin direct API, DB migration, scheduler execution-history storage, distributed lock / ShedLock / Redis, legacy deletion
or automatic disabling of legacy tasks, recommendation algorithm / publisher / renderer semantic changes, notifications, **retry loop**,
**missed-run catch-up**, the pre-existing `GET /api/v1/workout-publish → 500` (technical debt, untouched). Do not start Phase 6C.

## Rules
- `git status`, `git switch main`, `git pull --ff-only origin main`; `6822abb` included; clean tree; never reset user changes.
- Two independent switches. Master `running-ai.workout-publishing.enabled` (`WORKOUT_PUBLISHING_ENABLED`) = may Spring write Intervals workouts at
  all. Scheduler `running-ai.workout-publishing.scheduler.enabled` (`WORKOUT_PUBLISHING_SCHEDULER_ENABLED`) = run the automatic daily trigger.
  Both default **false**. master=false/scheduler=false → everything blocked; master=true/scheduler=false → manual POST only; both true → manual + automatic.
  The scheduler never bypasses the master switch (scheduler on + master off → the service refuses with `WORKOUT_PUBLISHING_DISABLED`, logged; startup does
  not fail — runtime safety preferred).
- Config (extend the existing properties class, no scattered classes): `scheduler.enabled`, `scheduler.cron` (`WORKOUT_PUBLISHING_SCHEDULER_CRON`,
  default `0 0 5 * * *`), `scheduler.zone` (`WORKOUT_PUBLISHING_SCHEDULER_ZONE`, default `Asia/Seoul`). 05:00 is a technical default, not an operating policy.
  Invalid cron fails at Spring startup (framework behaviour, no custom parser); an invalid zone fails at config bind. Never depend on the OS default zone.
- Bean exists only when the scheduler switch is true (`@ConditionalOnProperty`); scheduling infrastructure only then too.
- Date = `LocalDate.now` in the configured zone, via the existing shared `Clock` bean (testable; no new TimeProvider, no `systemDefault()`).
- One `publish(today)` call per tick; reuse the 6A per-date single-flight (no extra lock): a tick overlapping a manual POST is rejected by the service
  → logged as skipped, no wait, no retry. CREATED / UPDATED / NO_CHANGE are all normal successes (NO_CHANGE after a manual publish is not an error).
- REST day: first inspect the existing prescription / publisher contract; if "empty workout = error" is the current contract (`INTERVALS_EMPTY_WORKOUT`)
  keep it and record it; do not invent `SKIPPED_REST_DAY` semantics.
- Exception handling: nothing escapes the scheduler thread; do not swallow silently or report failure as success; log date, error code, exception class
  only (never key, Authorization, HTTP body, workout text, physiological values). Startup log of enabled / cron / zone is allowed.
- Tests (no network, application service mocked/faked): bean present when enabled / absent when disabled; fixed clock + zone date test incl. a UTC date boundary
  (UTC 2026-10-01 16:30 → Asia/Seoul 2026-10-02 → publish(2026-10-02)); exactly one invocation and no direct mapper/renderer/publisher use; CREATED and NO_CHANGE
  (UPDATED too) complete; WORKOUT_PUBLISHING_DISABLED, WORKOUT_PUBLISH_ALREADY_RUNNING and other exceptions end the tick safely; first call fails → second succeeds.
  Existing 431 tests must stay green; run the full `gradlew clean test`.
- Never enable the scheduler (or the master switch) outside tests; no live Intervals/Garmin validation. No DB migration.
- Docs: purpose, default OFF, cron + zone properties, master vs scheduler switch, manual-first cutover, legacy prerequisite, no retry, no catch-up,
  single-flight reuse; `CLAUDE.md` invariant: "Automatic workout publishing is disabled by default. Do not enable the scheduler until the main-PC legacy
  workout writer is disabled and manual Spring publishing has passed an operational smoke test."
- Cutover order (document): 1 list legacy Scheduled Tasks; 2 disable legacy writers (create-today-workout / command-channel); 3 stop manual legacy runs;
  4 update Spring to latest main; 5 restart; 6 `WORKOUT_PUBLISHING_ENABLED=true`; 7 `WORKOUT_PUBLISHING_SCHEDULER_ENABLED=false`; 8 manual POST smoke test;
  9 check Intervals/Garmin; 10 only then scheduler=true — never both switches in one step.
- Garmin delivery stays Intervals/Garmin's job (the scheduler never calls Garmin sync). Notifications, publish-audit history, missed-schedule recovery,
  recovery-aware generation time, retry policy: future-phase candidates only.
- Finish: full regression, diff review, secrets check (env var names OK, no values), commit `feat: add automatic workout publishing scheduler`, push.

## Completion report format
Scheduler (class, calls, date source, timezone) / Configuration (master, scheduler, cron, zone, defaults) / Safety state (Spring publishing enabled now: NO,
scheduler enabled now: NO, main-PC legacy writer: ACTIVE possible, safe to activate scheduler: NO) / Runtime behavior (CREATED, UPDATED, NO_CHANGE, already
running, failure, retry, missed run) / Tests (previous, new, total, passed, failed) / Database / Live validation (attempted: NO; scheduler enabled outside
tests: NO) / Git / Operational prerequisite (MAIN PC LEGACY WRITER MUST BE DISABLED) / Next (Phase 6C ChatGPT integration; not auto-started).
