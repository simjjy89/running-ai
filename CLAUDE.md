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
  targets), `coach` (Kotlin AI coach, drafts, approval), `recovery`, `draftpublish` (Kotlin, Phase 6G approved-draft publish gateway),
  `analysis` (Kotlin, Phase 6H-4 running analysis engine: derived evidence only, no coaching judgment). Layering: Controller → Service → Entity → Repository.
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
- **Detailed activity foundation** (Phase 6H-1A; `activity.detail` + `integration.garmin`, Kotlin; architecture in
  `docs/architecture/detailed-activity-v2.md`, static contract in `docs/architecture/garmin-detailed-activity-contract-static.md`).
  Connector `GET /activities/{id}/detail|splits|hr-zones|power-zones|samples` (raw, one Garmin request, library retries disabled:
  `Garmin(retry_attempts=0)`; 404 → `GARMIN_NOT_FOUND`). Raw-first: `activity_raw_payload` (V11, per activity+type, own commit) →
  pure mappers (Garmin field names end there) → `activity_detail` / `activity_lap` / `activity_zone` / `activity_sample` (V12,
  replaced per part, `extra_metrics` keeps unknown keys) → per-part status `activity_detail_collection` (V13; COMPLETE/PARTIAL/FAILED,
  account-level failures stop the run). `activity_detail` comes from the list item already in `activity_raw`; `get_activity` is raw-only.
  Samples are parsed by each payload's `metricDescriptors` (never a fixed index), native sampling kept, nothing fabricated.
  Manual only: `POST /api/v1/garmin/activities/{garminActivityId}/details` and `/reprocess` (no Garmin call).
  **Contract is LIVE_VERIFIED (Phase 6H-1B, Main PC, 4 real activities): no mapper key needed correcting.** Live facts that
  constrain future work: `metricsIndex` differs per payload (one device gave 4 layouts — never hard-code an index);
  `directTimestamp` is a JSON float of epoch ms; descriptor `unit.factor` is **not** a conversion divisor; lap `lapIndex` is
  1-based and carries `intensityType` (WARMUP/ACTIVE/RECOVERY/COOLDOWN) + `wktStepIndex` in `extra_metrics`, and a lap is
  **not** 1:1 with a workout step; zones are always 5 with no upper bound; power zones of a no-power activity are `[]` → `EMPTY`,
  not a failure; `/samples` default **down-samples** above ~2000 native samples (`totalMetricsCount` reports the native count) and
  FIT decision: **A — API detail suffices**, FIT archival-only, not implemented.
  **Sample fidelity (Phase 6H-1C):** `running-ai.garmin.detail.samples-max-chart-size` (`GARMIN_DETAIL_SAMPLES_MAX_CHART_SIZE`)
  defaults to **20000** and is always sent for `/samples` only (range 1..100000 = the connector limit; outside it startup fails).
  Asking for it never implies completeness: every collection records `requested_max_chart_size`, `source_metrics_count`,
  `source_total_metrics_count` and `sample_completeness` (FULL / DOWNSAMPLED / UNKNOWN) in `activity_detail_collection` (V14),
  judged from the response (`totalMetricsCount` is the authority), with UNKNOWN for a missing count or a self-contradicting
  payload. A DOWNSAMPLED answer is never re-requested at another size; `reprocess` recomputes completeness from stored raw
  with no Garmin call. Live-verified: the long run went 1399/2784 DOWNSAMPLED -> 2784/2784 FULL.
  No scheduler, no historical backfill, no FIT storage, TrainingContext unchanged. `activity` identity unchanged (an
  `activity_source` link table is planned for Intervals enrichment).
- **Running Analysis Engine** (Phase 6H-4; package `analysis`, Kotlin; `docs/architecture/running-analysis-engine.md`).
  Derives objective evidence from **stored rows only** (no Garmin call, no Intervals call, nothing published):
  `RunningActivityAnalysisService` -> `SessionMetricsCalculator` / `ZoneExposureCalculator` / `ThresholdExposureCalculator` /
  `LapMetricsCalculator` / `IntervalStructureExtractor` / `IntervalMetricsCalculator` -> `ActivityAnalysisStore` ->
  `activity_analysis` + `activity_analysis_interval_group` + `activity_analysis_interval` (V16). Manual only:
  `POST|GET /api/v1/activities/{activityId}/analysis`; version `RUNNING_ANALYSIS_V1`, re-analysis replaces derived rows.
  **It measures, it never judges**: no rating, score, threshold, readiness/risk number or good/bad label may enter this
  package or its schema -- interpretation is the AI coach's. `analysis_status` (COMPLETE/PARTIAL/INSUFFICIENT_DATA) describes
  the *data*, not the session. Rules: missing data is null, never estimated; halves split at the **elapsed-time midpoint**
  (never by sample index, and no index fallback); no moving-time filter or speed threshold; zone percentages use the **zone
  total**, not activity duration; LTHR exposure **integrates real sample gaps** (the last sample contributes nothing) and is
  null without an LTHR; `speed_hr_decoupling_percent` = `(EF1-EF2)/EF1*100` where `EF = avgSpeed/avgHR` -- a
  **RunningAI-derived descriptive metric, not Garmin's or Intervals.icu's**, with no threshold; every CV is a **population**
  SD (divisor n); cadence metrics only for RUN/TREADMILL_RUN. Interval structure is **read, never inferred**: blocks are
  consecutive laps sharing `intensity_type`+`workout_step_index` (V15 first-class lap columns), a group needs ACTIVE, the same
  step >=2 times, and a RECOVERY block between occurrences -- never a speed/HR pattern. `recovery_hr_drop_bpm` is the
  **RunningAI interval recovery HR change** (fixed 10 s windows), not Garmin's Recovery HR.
- **Intervals.icu analysis enrichment** (Phase 6H-5; `integration.intervals` + package `enrichment`, Kotlin;
  `docs/architecture/intervals-enrichment.md`; live contract LIVE_VERIFIED 2026-10-03 on the real account). Roles never merged:
  Garmin = sensor truth, RunningAI analysis = own derived metrics, Intervals = training-load/fitness-model enrichment. Terminology:
  **CTL = Intervals calculated fitness (`icu_ctl`/wellness `ctl`), ATL = Intervals calculated fatigue (`icu_atl`/`atl`);
  wellness `fatigue` is a SUBJECTIVE field and is never mapped to ATL**; Form has no live API field → `derived_form = ctl - atl`
  (explicitly RunningAI-derived, only when both exist). Read-only towards Intervals: `IntervalsReadClient`/`HttpIntervalsReadClient`
  (GET only, structurally no write method, shared auth/error mapping via `IntervalsHttp`, one request per call, no retry; 429 stops).
  Raw-first: `intervals_raw_payload` (V18; ACTIVITY / WELLNESS_DAY, own commit) → pure mappers (Intervals field names end there) →
  `activity_intervals_metrics` (V18, 1:1: training_load, intensity, ctl/atl_after_activity) and `intervals_fitness_daily` (V18,
  athlete+date: ctl, atl, derived_form, ramp_rate, ctl_load, atl_load). An Intervals activity never becomes a second Activity row:
  `activity_source_link` (V17, unique external identity + activity+source, match evidence deltas) attaches the Intervals id.
  Matching priority SOURCE_ID (`source=GARMIN_CONNECT` + `external_id`=Garmin id; live 5/5) → EXTERNAL_ID → COMPOSITE (only
  live-observed type pairs; measured-based tolerances |start|<=30s, |duration|<=5s, |distance|<=5m; a candidate explicitly claiming a
  different Garmin id never matches; 0→UNMATCHED normal, 2+→AMBIGUOUS never auto-linked; already-linked-elsewhere → 409
  `INTERVALS_LINK_CONFLICT`). Manual only: `POST /api/v1/intervals/enrichment/activities/{id}` (+`/reprocess`, 0 Intervals calls),
  `POST .../fitness?oldest&newest` (range <= 31 days; backfill is NOT this endpoint; +`/reprocess`), read-only
  `GET /api/v1/activities/{id}/intervals`, `GET /api/v1/intervals/fitness`. No scheduler/webhook/periodic sync (Phase 7). Intervals
  values never enter `activity_analysis`; Intervals wellness never overwrites `garmin_recovery_daily`; `activity_sample` stays
  Garmin-only. TrainingContext unchanged (V2 = 6H-7).
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
