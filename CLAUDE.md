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
- **Training-lifecycle MCP tools, off by default** (Phase 7F-1; package `integration.mcp.train`, Kotlin;
  `docs/architecture/chatgpt-training-mcp.md`). A THIRD independent switch `RUNNINGAI_MCP_TRAIN_ENABLED`
  (`running-ai.mcp.train-enabled`) = false, beside 6C's `enabled` and 7C-2a's `read-enabled`; the Spring AI server turns on
  when any of the three is on. Six tools — `create_workout_draft`, `get_workout_draft`, `revise_workout_draft`,
  `approve_workout_draft`, `preview_draft_publish`, `publish_approved_draft` — each a **thin adapter over the service the REST
  API already calls** (`WorkoutDraftService`, `WorkoutDraftApprovalService`, `ApprovedWorkoutDraftPublishService`), returning
  the REST DTOs themselves so the two surfaces cannot drift. No lifecycle rule is re-implemented: approval immutability, one
  approval per athlete+date, the injury gate, the publishing master switch, per-draft single flight and "a re-publish returns
  the stored result" stay in the services (`TrainMcpToolsArchitectureTest` fails the build if the adapter grows a route to a
  renderer, publisher, repository or the coach). Failures carry **the same codes the REST API returns**, plus a
  `correlationId` on every answer and log line. **The coach is never faked**: an unavailable/timed-out/off-contract CLI fails
  as `AI_COACH_<reason>`, never a substituted workout. **An MCP call is not the athlete's approval**: `approve_workout_draft`
  and `publish_approved_draft` additionally require `confirmedByAthlete: true` AND an echo of a value only a prior read
  supplies (`acknowledgedVersion` / `acknowledgedApprovalId`), checked read-only before the committing service is entered.
  Gateway (`tools/mcp-gateway`): **hard-coded, non-configurable per-tool scope policy** — `runningai:read` (base + the seven
  read tools) / `runningai:train` (the five draft tools) / `runningai:publish` (`publish_approved_draft` only); scopes are
  **not hierarchical**, `tools/list` is filtered to allowlist ∩ granted scopes, a scope failure is a 403 challenge raised
  before any backend traffic, and `publish_workout` stays permanently un-allowlistable. Real external publish: **NOT_RUN** —
  the Cloudflare worker still filters scopes to `['runningai:read','offline_access']`, so a train/publish token cannot yet
  exist; that worker change belongs to Phase 7F-2, together with the first by-hand validation of the draft→Intervals path.
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
  **Analysis identity is three facts, not a string (Phase 7E-1, V24):** `analysis_version` = the output CONTRACT
  (`RUNNING_ANALYSIS_V1`, unchanged), `analysis_engine_revision` = the LOGIC that produced the numbers (0 = pre-tracking,
  set by V24 and never produced by code; 1 = 6H-4; 2 = V22 dynamics), `analysis_input_digest` = SHA-256 of what the engine was
  fed (activity identity metrics + every recorded `activity_detail_collection` part's status/row count/completeness + LTHR;
  **no timestamps**, so re-collecting identical data is not a data change). `AnalysisReusePolicy` is the **single** place that
  answers "is this stored analysis current?" - backfill reuse/VERIFY and the coach freshness evaluator all route through it, and
  a row with no digest is never reused. **Rule: any change that can alter a value this package writes bumps
  `RUNNING_ANALYSIS_ENGINE_REVISION` in the same commit** - every reuse decision then picks it up with no migration and no
  manual sweep list. `POST /api/v1/activities/analysis-refresh[?dryRun=true]` sweeps every stored activity with that same
  policy (DB-only; a failed activity keeps its previous analysis and the sweep continues). A re-analysis replaces in ONE
  transaction after every new value exists, so a failure never destroys the stored result.
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
- **Historical backfill** (Phase 6H-6; package `backfill`, Kotlin; `docs/architecture/historical-backfill.md`). Manual-only,
  resumable 90-day pipeline: `POST /api/v1/historical-backfill` (`{"endDate","days"}`, days <= 90) → phases GARMIN_DISCOVERY →
  GARMIN_DETAIL_ANALYSIS → INTERVALS_ACTIVITIES → INTERVALS_FITNESS → GARMIN_RECOVERY (28 days, never widened) → VERIFY →
  COMPLETED, persisted in V19 (`historical_backfill_run` + per-activity checkpoints) with one committed checkpoint per unit of
  work. Runs synchronously on the request thread; no scheduler, no auto-resume (PAUSED = safe stop, human `/resume`; FAILED =
  never resumable; COMPLETED resume = deterministic 409). Reuses the existing services only (summary ingestion, detail
  ingestion, analysis, 6H-5 matcher/stores, recovery syncDay); **never touches `garmin_sync_state`**; discovery always restarts
  from offset 0 on resume; caps pause as `BACKFILL_DISCOVERY_LIMIT_EXCEEDED` rather than truncating. Gates: detail outcome
  COMPLETE; NORMALIZED streams must be FULL (DOWNSAMPLED/UNKNOWN pause — never auto-retried at another maxChartSize), EMPTY
  stream = legitimate `NO_SAMPLE_STREAM`; refetched detail always recomputes analysis, and otherwise reuse is
  `AnalysisReusePolicy`'s answer, not a local version-string comparison (Phase 7E-1); analysis failure pauses.
  The final VERIFY fidelity sweep covers **GARMIN-sourced activities only** (Phase 7E-1): it states what Garmin collection
  must achieve, and an activity another source produced was never collected by this pipeline. Intervals: ≤31-day list windows (~3 GETs) + ONE 90-day wellness GET (the manual endpoint's
  31-day guard is untouched), never per-activity GETs, UNMATCHED/AMBIGUOUS continue, link conflict pauses. Start/resume refuse
  unless all four publishing switches AND both Garmin schedulers are false (`HISTORICAL_BACKFILL_UNSAFE_RUNTIME`); no external
  write anywhere. Config `running-ai.historical-backfill.*` (page-size 100, max-pages 20, max-activities 500, activity-delay 2s).
- **Intervals.icu-only activity import** (Phase 7E-1; `integration.intervals` + `enrichment`, Kotlin;
  `docs/architecture/intervals-activity-import.md`; live contract measured 2026-10-13 over all 27 real activities).
  Exists because Garmin Connect holds **nothing before 2026-09-18** while 27 real sessions (2026-08-02..09-13) live only in
  Intervals.icu, and 6H-6 is Garmin-discovery-driven so it can never reach them (measured: a 90-day re-run added 0 rows).
  Read-only towards Intervals (`IntervalsReadClient.getActivityStreams`, GET only). Raw-first: summary ->
  `intervals_raw_payload(ACTIVITY)`, streams -> `activity_raw_payload(INTERVALS_ICU, ACTIVITY_DETAILS_STREAM)`, each its own
  commit -> pure mappers -> `activity` (`external_source=INTERVALS_ICU`, `external_id` = the Intervals id verbatim; **no Garmin
  id is ever synthesised**) + `activity_detail` + `activity_sample` + `activity_intervals_metrics` (via the existing 6H-5 mapper)
  -> `RunningActivityAnalysisService.analyseIfNeeded`. **Never a second row for a workout RunningAI already has**: the candidate
  is matched against the stored activities of its own day by the unchanged `IntervalsActivityMatcher` - one match -> link +
  enrich, 2+ -> `SKIPPED_AMBIGUOUS` (never auto-merged), `source=GARMIN_CONNECT` -> the 6H-5 path, 0 -> only then create.
  Live facts that constrain the mappers: all streams of one activity share a length; `time` carries **elapsed seconds, not
  indices** (its last value = `elapsed_time`), so a sample instant is `start_date + time[i]`; `latlng` = latitude in `data`,
  longitude in `data2`; `icu_lap_count` is **0 on all 27**; `start_date_local` = `start_date` in the athlete zone (27/27);
  an `OAUTH_CLIENT` `external_id` is a UUID; a `STRAVA` item is a data-less stub. **Cadence is NOT converted**: Intervals
  reports one-leg rpm, the `cadence` column is Garmin's spm (factor 2, verified arithmetically) - the stream stays in
  `extra_metrics` under its source key, the column stays null, and `CADENCE_SOURCE_RPM_NOT_NORMALIZED` is recorded, so
  **cadence metrics are unavailable for imported activities**. Same for stance_time/vertical_oscillation/step_length (no column,
  and the engine reads dynamics from laps, which these have none of). **No lap is invented** (`SPLITS` part = `EMPTY`) and
  **Intervals HR zones are not imported** (7-zone model vs Garmin's 5 in `activity_zone`). Sample completeness is always
  `UNKNOWN`: Intervals states no native point count and `/streams` has no size parameter, so nothing could prove FULL - which
  is why 6H-6's final VERIFY sweep is now scoped to `external_source = GARMIN`. V25 `intervals_activity_import` is one row per
  (athlete, Intervals activity) and is simultaneously the idempotency key, resume point, progress log, quality record and
  cross-system provenance (`strava_activity_id` for Phase 7E-2); **re-POSTing is both the resume and the retry**, no automatic
  retry anywhere, account-level failures stop the run. Manual only: `POST|GET /api/v1/intervals/activity-import`,
  `POST .../{intervalsActivityId}/reprocess` (0 API calls). Off by default
  (`RUNNING_AI_INTERVALS_ACTIVITY_IMPORT_ENABLED`) and refuses to start while any publishing switch or Garmin scheduler is on.
  Copy-DB verified: 25 imported, 46,880 samples, re-run Δ=0 on 12 tables, kill-and-resume lossless, 0 failures.
- **Strava archive import** (Phase 7E-2; package `integration.strava` + `enrichment`, Kotlin;
  `docs/architecture/strava-archive-import.md`; measured over the athlete's real 94-activity export).
  Supersedes the 7E-1 preparation note `strava-archive-recovery.md`. Exists because these activities are
  reachable by **no API** (Intervals answers `422 Cannot read Strava activities via the API` for every one, Garmin has
  nothing before 2026-09-18), so the source is a **local directory** holding the unpacked export - there is no Strava HTTP
  client in this repo and none is to be added, and nothing scrapes anything. Dependency `com.garmin:fit` (official FIT SDK,
  **decode only** - FIT is profile-driven binary, so ad-hoc parsing cannot read it correctly; nothing here writes a FIT file).
  **The join is exact, never heuristic**: Strava re-encodes each Apple Health workout to FIT and stamps the developer field
  `apple_health_uuid` into the session message, and that value equals Intervals.icu's `external_id` character-for-character
  (measured 27/27, 0 contradictions; it also proved `i180579462` = Strava `19929243277`, a Stair-Stepper Intervals holds
  **twice**). Identity = `apple_health_uuid` -> Strava activity id; no match -> new activity, but a +/-120 s start-time
  collision against any non-Strava activity yields `SKIPPED_AMBIGUOUS` rather than a possible duplicate. **A stored value is
  never overwritten**: the archive and Intervals are two INDEPENDENT exports of the same workout, each missing what the other
  has (Intervals has HR on 100 % of samples where the August FITs have none; the FIT has far more running power and the laps
  Intervals reports as `icu_lap_count`=0), so enrichment fills **NULL only** and stamps `"<metric>_source":"STRAVA_FIT"` into
  `extra_metrics`; laps are written only where there are none, except that laps **this importer wrote itself** may be
  rewritten (tracked via `laps_written`, so a mapper fix can reach them) - Garmin's own laps are never touched. A FIT record
  matching no stored sample is **dropped, never appended**. **The FIT sport decides the type, not the Strava label** (the
  export says "Ride" for files that record `cycling`/`indoor_cycling`); an unmapped sport is refused, so strength/walk/stairs
  stay out rather than being approximated, and an *outdoor* ride has no `ActivityType`. **Cadence is NOT converted** (FIT =
  one-leg rpm, the column is spm) - same decision as 7E-1. **Interval GROUPS stay underivable** for Strava-sourced laps: the
  re-encode carries no `intensity` and no `wkt_step_index` (only `lap_trigger=manual`), so laps and lap metrics are restored
  but groups are correctly empty and the reason is flagged - inferring ACTIVE/RECOVERY from pace is banned. Lap average speed
  is **RunningAI-derived** distance/time, marked `average_speed_source=DERIVED_DISTANCE_OVER_TIME`. `sample_completeness` is
  **FULL** here (a FIT activity file IS the device recording and states its own count), unlike the Intervals stream's UNKNOWN.
  V26 `strava_activity_import` is one row per (athlete, Strava activity) carrying the file's SHA-256, and is at once the
  idempotency key, resume point, progress log, quality record and provenance; **re-POSTing is both the resume and the retry**,
  one unreadable file never stops the rest. Manual only: `POST|GET /api/v1/strava/archive-import`, off by default
  (`RUNNING_AI_STRAVA_ARCHIVE_IMPORT_ENABLED`, path `RUNNING_AI_STRAVA_ARCHIVE_PATH` which **must live outside the repo** -
  the export holds real GPS). Refuses to start while any publishing switch or Garmin scheduler is on. **The importer calls
  `analyse()` directly when it wrote laps or filled samples**: `analysis_input_digest` summarises a part by status and ROW
  COUNT, so a lap set replaced by the same number of richer laps looks identical to it - the component that changed the data
  states it rather than letting the digest infer it. Copy-DB verified: 15 imported, 33 enriched (116 laps, 18,541 samples),
  46 unsupported recorded, 0 ambiguous, 0 failures, re-run delta=0 on 17 tables, kill-and-resume lossless.
- **TrainingContext V2** (Phase 6H-7; package `coach`, Kotlin; `docs/architecture/training-context-v2.md`). V1
  (`TrainingContext`/`TrainingContextBuilder`, unchanged) and V2 (`TrainingContextV2`/`TrainingContextV2Builder`) both
  implement `CoachTrainingContext` (`date`/`athlete`/`constraints`), the only type `AiCoach` depends on;
  `CoachTrainingContextBuilder` routes on `running-ai.coach.context-version` (`RUNNING_AI_TRAINING_CONTEXT_VERSION`,
  default **V1**). V2Builder is **DB-only** (zero Garmin/Intervals calls, never calls `RunningActivityAnalysisService.analyse()`)
  and combines Garmin detail, RunningAI Analysis (6H-4) and Intervals.icu CTL/ATL/derived-form (6H-5, terminology unchanged:
  CTL=calculated fitness, ATL=calculated fatigue, subjective `fatigue` has no field) into `dataCoverage` (plain counts, never a
  quality verdict), `trainingLoad` (exact source date/age, D-7/D-28 exact-day only, no interpolation), `trainingRhythm`
  (factual only — **no `candidateTrainingTypes`**, the coach chooses the session), and `recentActivities` (capped by
  `running-ai.coach.context-v2.max-recent-activities` default 8, each with Garmin/RunningAI/Intervals evidence kept in
  separate sections, interval groups capped by `max-interval-groups-per-activity` default 3, no individual repetitions, no
  raw sample/GPS/external-id ever reachable). Strict no-look-ahead (nothing after date `D`); `CoachContextSerializer`
  produces deterministic canonical JSON + SHA-256, size-guarded at `max-snapshot-bytes` (default 64 KiB,
  `TRAINING_CONTEXT_TOO_LARGE` raised before any coach call, never silently truncated). Every draft (either context version)
  persists `context_version`/`context_snapshot`/`context_built_at`/`context_sha256` (V20, all nullable, pre-6H-7 drafts stay
  null); a revision stores its own snapshot and never touches the superseded row's. Read-only preview:
  `GET /api/v1/coach/training-context?date=&version=` (DB-only, zero Garmin/Intervals/Claude calls). V2 does not change the
  Claude response contract, the validator, or any V1 behavior.
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
