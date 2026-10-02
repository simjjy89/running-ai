# RunningAI — project rules for Claude Code

AI-powered personal running training platform. This repository is the new Spring Boot
backend that will absorb, phase by phase, the owner's existing PowerShell / Node.js
RunningAI automation (which lives only on the main PC and is NOT in this repo).

## Architecture (current)

- `server/` — Spring Boot 3.5.x, Java 21, Gradle wrapper, PostgreSQL 17, Flyway,
  Spring Data JPA / Hibernate 6, Jackson, JUnit 5 + Spring Test (H2 in tests).
- Packages under `com.runningai`: `common` (config, exception, health), `athlete` (Athlete,
  AthleteIntensityProfile), `activity` (Activity, ActivityRaw, services, HTTP API),
  `integration/garmin` (ingestion core, connector HTTP source, incremental sync, sync API,
  scheduler), `integration/intervals` (`IntervalsWorkoutRenderer` + `GarminSafeCueFormatter`:
  renders a `StructuredWorkout` into Intervals.icu Workout Builder text; `IntervalsWorkoutPublisher` +
  `IntervalsWorkoutClient` publish it idempotently with readback verification (Phase 5C-3); no
  scheduler yet), `integration/mcp` (`publish_workout` MCP tool, off by default), `training` (derived training load, workout recommendation/prescription/intensity
  targets), `coach` (Kotlin AI coach, drafts, approval), `recovery`, `draftpublish` (Kotlin, Phase 6G approved-draft publish gateway). Layering: Controller → Service → Entity → Repository.
- Implemented: Activity API, ActivityRaw JSONB storage, Garmin ingestion + incremental sync via the
  localhost Python connector (`tools/garmin-connector`; Spring never holds Garmin credentials),
  sync API and opt-in scheduler, Windows (`scripts/windows`) and Linux (`deploy/linux`) runtime
  artifacts, and duration-based training load (`/api/v1/training-load`, `/api/v1/training-state` (acute/chronic, progression, ramp, monotony, strain; measurement only, no ratings), `/api/v1/training-decision-context` (recent daily pattern, days since run/long run, consecutive counters, load trend label, candidate training types; context only, no prescription), `/api/v1/workout-recommendation` (one workout intent + duration range + intensity class from the decision context; deterministic rules, QUALITY never auto-selected, no workout steps), `/api/v1/workout-prescription` (exact duration + warm-up/main/cool-down segments for the recommended intent; qualitative intensity only, QUALITY unsupported), computed from normalised
  activities in the athlete timezone, nothing persisted), `/api/v1/athlete/intensity-profile` (GET/PUT
  persistent athlete LTHR + threshold pace, both optional, no Garmin auto-fetch), and
  `/api/v1/workout-intensity-targets` (Workout Prescription + pace / %LTHR heart-rate / treadmill
  speed+incline targets per segment; pace preferred over HR when both available, QUALITATIVE
  fallback when the profile is absent or incomplete, CROSS_TRAINING never uses the running
  threshold profile; targets are derived every call, never persisted), `StructuredWorkout`/
  `StructuredWorkoutMapper` (`training` package): a provider-neutral, ordered re-shaping of
  `TargetedWorkoutPrescription` with no Intervals.icu/Garmin syntax, HTTP, publishing or
  persistence, and `IntervalsWorkoutRenderer`/`GarminSafeCueFormatter` (render-only:
  `StructuredWorkout` → deterministic Intervals.icu Workout Builder text), and
  `IntervalsWorkoutPublisher`/`HttpIntervalsWorkoutClient` (Phase 5C-3: Basic-auth HTTP, marker =
  `external_id`, CREATE/UPDATE/NO_CHANGE, no blind POST retry, readback verification; API key from
  `INTERVALS_API_KEY` only; reached only via `WorkoutPublishApplicationService` (Phase 6A, `POST /api/v1/workout-publish`, no scheduler); live-validated SERVER_VERIFIED in Phase 5C-3.5; Pace, %LTHR and treadmill-cue Garmin 265 DEVICE_VERIFIED in Phase 5C-4).
- **Canonical publishing path (Phase 5C-5): `IntervalsWorkoutPublisher` is the canonical workout publishing path.** No other
  code in this repo may POST/PUT Intervals events (only `HttpIntervalsWorkoutClient`, called by the publisher). The legacy
  PowerShell publisher/renderer (`create-today-workout.ps1`, `intervals-structured-workout.ps1`, Command Channel; main PC only,
  not in this repo) is DEPRECATED: not authoritative, never an automatic fallback, kept only as reference/manual rollback; its
  scheduled tasks must be disabled by the owner on the main PC (machine operation, never committed; status: pending owner action as of Phase 5C-5). Legacy marker compatibility in
  the publisher stays until a separate cleanup phase.
- **Workout publishing is operationally disabled by default** (Phase 6A): `running-ai.workout-publishing.enabled` /
  `WORKOUT_PUBLISHING_ENABLED` = false. Do not enable it on any PC while the legacy main-PC workout writer is active.
  The only trigger is `POST /api/v1/workout-publish` (explicit date) → `WorkoutPublishApplicationService` (per-date
  in-JVM single-flight); the scheduler (6B) and a future ChatGPT tool must call that service, never the publisher or Intervals directly.
- **Automatic workout publishing is disabled by default** (Phase 6B): `WorkoutPublishingScheduler`
  (`running-ai.workout-publishing.scheduler.enabled` / `WORKOUT_PUBLISHING_SCHEDULER_ENABLED` = false, cron 05:00 Asia/Seoul) only calls
  `WorkoutPublishApplicationService`, never bypasses the master switch, and has no retry or missed-run catch-up. Do not enable the scheduler
  until the main-PC legacy workout writer is disabled and manual Spring publishing has passed an operational smoke test.
- **MCP adapter is disabled by default** (Phase 6C, package `integration.mcp`): Spring AI 1.1.8 MCP server (Boot 3.5-compatible 1.x line; never
  Spring AI 2.x / Boot 4 for this), Streamable HTTP stateless on `POST /mcp`, exactly one tool `publish_workout({"date":"YYYY-MM-DD"})` that only
  calls `WorkoutPublishApplicationService` (master switch still applies). `RUNNINGAI_MCP_ENABLED` (`running-ai.mcp.enabled`) = false also switches
  the Spring AI server off (its own default is on). `/mcp` has no auth: never expose it publicly; remote ChatGPT transport is not set up.
- **Garmin recovery intelligence** (Phase 6F; package `recovery` + `integration/garmin` + `coach`): connector `GET /recovery?date=`
  (HRV, sleep, resting HR, Body Battery, stress; documented fields only) → `GarminRecoveryClient` → `GarminRecoveryMapper` →
  `garmin_recovery_daily` (V7, unique athlete+date, idempotent merge: a missing metric never clears a stored one) →
  `RecoveryBaselineService` (28-day personal baseline, ≥7 valid days else INSUFFICIENT_DATA; measurement only, no ratings) →
  `RecoveryContextBuilder` → `TrainingContext.recovery` for the Kotlin AI coach (Phase 6E, drafts only). Manual triggers only:
  `POST /api/v1/garmin/recovery-sync` and `/backfill` (≤28 days, sequential, no retry, stops at the first connector failure);
  read-only `GET /api/v1/recovery-context`. Spring never turns a recovery value into a training decision; the coach does.
  Live validation: external PC `BLOCKED_BY_CORPORATE_TLS`; main PC `PENDING`.
- **Draft approval & safe publish gateway** (Phase 6G; `coach` approval + package `draftpublish`). Lifecycle:
  TrainingContext → ClaudeAiCoach → WorkoutDraft `DRAFT` → optional revision(s) → explicit `POST /api/v1/workout-drafts/{id}/approve`
  (`APPROVED`, V9 `workout_draft_approval`; DB-only, at most one approved draft per athlete+date, approved drafts are immutable:
  revision → `WORKOUT_DRAFT_APPROVED_IMMUTABLE`) → read-only `GET .../{id}/publish-preview` → explicit `POST .../{id}/publish`.
  REST → `SKIPPED_REST_DAY`, zero renderer/publisher/client calls. Other workouts → `WorkoutDraftStructuredWorkoutMapper` (exact
  transport mapping, repeats expanded, total re-checked) → existing `IntervalsWorkoutRenderer` → existing `IntervalsWorkoutPublisher` →
  `PUBLISHED` (V10 `workout_draft_publication`, unique per draft, failures never stored, re-publish returns the stored result).
  Never uses `WorkoutPublishApplicationService`/`WorkoutIntensityTargetService` (that would re-prescribe). Lossy drafts fail closed
  (`UNPUBLISHABLE_DRAFT`): bpm heart-rate targets (renderer only has %LTHR), half ranges, treadmill speed without incline, negative
  incline, MODERATE/HARD without pace or speed, CROSS_TRAINING/unknown types. Separate switch `RUNNING_AI_DRAFT_PUBLISHING_ENABLED`
  (`running-ai.draft-publishing.enabled`) = false; approve/preview work while off. No scheduler, no startup trigger, no MCP tool;
  per-draft in-JVM single-flight. `POST /api/v1/workout-publish` stays the separate legacy deterministic date-based path (both use
  the same per-date Intervals marker). Real external publish: NOT_RUN (fake/mock only so far).
- **Legacy publishing XOR AI Draft publishing** (Phase 6G.1, `draftpublish.PublishingModeGuard`): `WORKOUT_PUBLISHING_ENABLED` and
  `RUNNING_AI_DRAFT_PUBLISHING_ENABLED` both true → application startup fails ("Legacy workout publishing and AI draft publishing
  cannot be enabled at the same time"). Both off or exactly one on starts normally. Never weaken or bypass this guard.
- Planned, not started: interval/repeat workout structure, QUALITY workout structure,
  cycling threshold profile, race pace, RPE model, reporting, remote ChatGPT ↔ MCP transport + authentication, missed-run catch-up / retry / notifications for publishing.
- Skills with the detailed rules: `running-ai-dev` (workflow), `running-ai-database`
  (schema/persistence), `running-ai-integration` (Garmin / external systems).

## Before any change

Run `git status`, `git branch --show-current`, `git log --oneline -5`, then read the code
you are about to touch. Existing implementation first, guessing second. Never re-create the
legacy PowerShell / Node.js features from memory; never invent external API contracts.

## Invariants

- Keep the existing architecture and package layout; no speculative refactoring or
  future-proof abstractions; no unnecessary interfaces; no Lombok; new dependencies only
  with a clear, stated need.
- Entities are never exposed as API DTOs (DTOs are Java records). No business logic in
  controllers. External-system code stays out of domain/application logic.
- Existing contracts stay unchanged unless the task explicitly says otherwise:
  `GET /api/v1/health`, `POST /api/v1/activities` (create, duplicate → 409),
  `GET /api/v1/activities/{id}`, `GET /api/v1/activities`, `GET /actuator/health`.
- Flyway owns schema changes; Hibernate only validates (`ddl-auto: validate`).
  Applied migrations are never edited; a schema change is a new `V<N>__*.sql`.
- Time is stored as UTC `Instant` / `TIMESTAMPTZ`. Internal PKs are never external IDs;
  external identity is `external_source + external_id`.

## Secrets

Never commit Garmin credentials/tokens/session data, the Intervals.icu API key, a real
`.env`, private keys, real user GPS coordinates, or raw private user payloads (fixtures are
synthetic). Never log credentials, tokens, or full raw payloads. `.env.example` holds
placeholders only.

## Testing and completion

- After code changes run the full suite from `server/`: `.\gradlew.bat clean test`
  (Java 21 required; see the `running-ai-dev` skill). Do not report done while tests fail,
  and never delete/disable a failing regression test to get green.
- Before committing check the diff for credentials, tokens, `.env`, private activity data,
  build output and IDE files. Commit/push only when the task asks for it, after tests pass.
- Every meaningful task gets a record in `docs/work-orders/YYYY-MM-DD-<name>.md`; when the
  user supplies a detailed instruction, also keep it verbatim as `*-instruction.md`.
- Completion report: what was implemented, architecture decisions, test results,
  migration yes/no, branch/commit/push, limitations, next steps. Never describe
  unimplemented work as implemented.
