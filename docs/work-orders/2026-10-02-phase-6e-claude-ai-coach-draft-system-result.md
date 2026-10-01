# Phase 6E — Kotlin-first Claude AI Coach Draft System (result)

Baseline `f1acc4d`, branch `main`. Work order:
`2026-10-02-phase-6e-claude-ai-coach-draft-system.md`.

## 1. Kotlin / Gradle changes

`server/build.gradle`:
- plugins `org.jetbrains.kotlin.jvm`, `org.jetbrains.kotlin.plugin.spring`,
  `org.jetbrains.kotlin.plugin.jpa`, all **2.1.21** (the version Spring Boot 3.5.16 already manages
  for `kotlin-stdlib`/`kotlin-reflect`, so plugin and runtime cannot drift).
- dependencies `org.jetbrains.kotlin:kotlin-reflect`, `com.fasterxml.jackson.module:jackson-module-kotlin`
  (versions from the Boot BOM, not pinned).
- `kotlin { compilerOptions { freeCompilerArgs '-Xjsr305=strict'; jvmTarget JVM_21 }; jvmToolchain(21) }`.
- `test { useJUnitPlatform { if (!hasProperty('liveCoachEval')) excludeTags 'live-coach' } }`.

Spring Boot 3.5.16, Spring AI 1.1.8, Java 21 and Gradle are **unchanged**. Existing Java code was not
migrated. Sources live side by side in `src/main/java` and `src/main/kotlin`.

## 2. New packages and classes

`com.runningai.coach` (Kotlin): `AiCoach` + `AiCoachException`, `CoachProvider`, `CoachProperties`,
`CoachConfig`, `TrainingContext` (+ `AthleteThresholds`, `RecentTraining`, `TrainingDay`,
`RecoveryContext`, `WeeklyContext`, `SessionConstraints`, `TrainingEnvironment`),
`TrainingContextBuilder`, `WorkoutDraft` (+ `WorkoutDraftSegment`, `CoachAssessment`,
`WorkoutDraftStatus`), `WorkoutDraftValidator` (+ `WorkoutDraftValidationException`),
`WorkoutDraftEntity` + `WorkoutDraftRepository`, `WorkoutDraftStore`, `WorkoutDraftService`,
`WorkoutDraftController` + request/response DTOs + `WorkoutDraftExceptionHandler`.

`com.runningai.coach.claude`: `ClaudeAiCoach`, `ClaudeCoachPromptBuilder`, `ClaudeCliClient`
(+ `ClaudeCliResult`), `ClaudeCoachResponseParser`.

Existing Java enums are **reused, not duplicated**: `SegmentType`, `IntensityClass`,
`CandidateTrainingType`.

## 3. `AiCoach` contract

```kotlin
interface AiCoach {
    fun createWorkout(context: TrainingContext): WorkoutDraft
    fun reviseWorkout(context: TrainingContext, currentDraft: WorkoutDraft, userRequest: String): WorkoutDraft
}
```

No vendor type appears in the signature (asserted by an architecture test). Provider selection:
`running-ai.coach.provider` / `RUNNING_AI_COACH_PROVIDER`, default `CLAUDE`;
`CODEX`/`OLLAMA` fail fast at startup with an explicit "not implemented" message rather than
silently falling back.

## 4. Claude CLI command contract (probed live, not assumed)

`claude --version` → `2.1.248 (Claude Code)`. Actual invocation:

```
claude -p --output-format json --model <model> --system-prompt <coach system prompt>
       --tools "" --strict-mcp-config --setting-sources "" --no-session-persistence
```

with the **prompt on stdin** (never as an argument, so a training context never appears in the
process table).

- Auth = the CLI's own login session on the host. No `ANTHROPIC_API_KEY` is read, set, stored or
  logged; `--bare` is deliberately avoided because it forces API-key auth.
- `--tools ""` removes every built-in tool (no Bash, no file access, no WebFetch).
- `--strict-mcp-config` makes RunningAI's own `publish_workout` MCP tool unreachable from a coach
  call even when `RUNNINGAI_MCP_ENABLED=true`.
- `--setting-sources ""` ignores user/project/local settings so local permissions cannot widen it.
- Response envelope: `{is_error, subtype, result, structured_output?, modelUsage, ...}`. The parser
  reads `structured_output` when present, otherwise parses `result`.
- One attempt, no retry. Timeout (default 120s) destroys the process. stdout/stderr are capped
  (1 MiB / 64 KiB) and drained on separate threads so a full pipe cannot deadlock the child.
  Logs carry exit code and byte counts only — never the prompt, response or any credential.

## 5. Data actually in `TrainingContext`

From `TrainingDecisionContextService` (reused, nothing recomputed): per-day `recentPattern`
(date, classification, activity count, load minutes, running duration/distance, cycling duration),
`lastRunDate`/`daysSinceLastRun`, `lastLongRunDate`/`daysSinceLastLongRun`,
`consecutiveActiveDays`/`consecutiveRestDays`, `candidateTrainingTypes`, and the full
`TrainingState`: acute/chronic load, acute:chronic ratio, current and previous 7-day load, weekly
load change %, 7-day running distance and duration, ramp load, monotony, strain, active/rest days,
load trend.

From `AthleteIntensityProfileService` (Phase 6D keeps it Garmin-synced): `lactateThresholdHeartRateBpm`,
`lactateThresholdPaceSecondsPerKm`.

From the request: `availableMinutes`, `environment`, `userFeedback`, `requestedGoal`,
`painOrFatigueFeedback`.

## 6. Data that is nullable because it is not collected (honest gap)

`RecoveryContext` — **every field is null in this build**: `hrvMs`, `restingHeartRateBpm`,
`sleepHours`, `bodyBattery`, `stressLevel`. None of these is ingested anywhere in RunningAI; the
Garmin connector exposes only `/health`, `/activities` and `/lactate-threshold`, and no table stores
them. Per the work order they were modelled as nullable rather than extending the connector in this
phase.

Also genuinely unavailable: `qualityDetectionAvailable` is `false` and `lastQualityDate`/
`daysSinceLastQuality` are always null, because the pipeline cannot detect a quality session from
the normalised activity fields. The prompt tells the coach that a null means unknown and that it may
not infer "no quality done" from the absence of a date.

**No fabricated value is ever sent.** Confirmed live: Claude's own assessment said *"No recovery
data available (HRV, RHR, sleep, body battery all null) — treat as unknown and train conservatively"*.

## 7. Live draft example (real DB context, real Claude call)

```json
{
  "id": 1, "draftGroupId": "c01d7153-…", "version": 1, "status": "DRAFT",
  "date": "2026-10-03", "title": "Easy Base-Building Run",
  "workoutType": "EASY", "totalDurationMinutes": 35,
  "assessment": {
    "recoveryAssessment": "No recovery data available (HRV, RHR, sleep, body battery all null) - treat as unknown and train conservatively",
    "loadAssessment": "Very low recent volume with 4 rest days in a row and only one run (30 min) in the last 7 days; acute:chronic ratio is elevated (1.34) purely because the base is so thin, not because of heavy load",
    "selectedWorkoutType": "EASY",
    "rationale": "You've only logged one short run in the past week after several rest days, so this session is about re-establishing running rhythm rather than adding stress. …",
    "warnings": ["No recovery metrics available - session designed conservatively", "…"]
  },
  "segments": [
    {"type":"WARM_UP","durationMinutes":5,"intensity":"VERY_EASY","heartRateBpmMin":110,"heartRateBpmMax":130,
     "treadmillSpeedKphMin":5.5,"treadmillSpeedKphMax":7.0,"inclinePercentMin":0.5,"inclinePercentMax":1.0},
    {"type":"MAIN","durationMinutes":25,"intensity":"EASY","paceSecondsPerKmFast":350,"paceSecondsPerKmSlow":380,
     "heartRateBpmMin":140,"heartRateBpmMax":155,"treadmillSpeedKphMin":9.5,"treadmillSpeedKphMax":10.3},
    {"type":"COOL_DOWN","durationMinutes":5,"intensity":"VERY_EASY","heartRateBpmMin":100,"heartRateBpmMax":120,
     "treadmillSpeedKphMin":4.5,"treadmillSpeedKphMax":5.5}
  ],
  "provider": "CLAUDE", "model": "sonnet"
}
```

## 8. Schema and versioning

Flyway `V6__create_workout_draft.sql` → table `workout_draft`: `id`, `athlete_id`,
`draft_group_id`, `version`, `workout_date`, `status`, `title`, `workout_type`,
`total_duration_minutes`, `recovery_assessment`, `load_assessment`, `selected_workout_type`,
`rationale`, `warnings` (JSON), `segments` (JSON), `provider`, `model`, `created_at`, `updated_at`;
unique `(draft_group_id, version)`, checks on positive version/duration, indexes on
`(athlete_id, workout_date)` and `(draft_group_id, version)`.

Revision inserts a **new row** at `version + 1` and flips the previous row to `SUPERSEDED` in the
same transaction; all versions share `draft_group_id`, so the history is a full audit trail.
Revising an already-superseded version is refused (409) rather than forking. The raw AI response is
**not** stored — only the normalised draft, the assessment and provider/model metadata.

## 9. REST API

| Method | Path | Purpose |
|---|---|---|
| POST | `/api/v1/workout-drafts` | design and store version 1 |
| GET | `/api/v1/workout-drafts/{id}` | preview |
| POST | `/api/v1/workout-drafts/{id}/revisions` | natural-language revision → new version |

There is deliberately **no approve and no publish endpoint**. Errors: 404 `WORKOUT_DRAFT_NOT_FOUND`,
409 `WORKOUT_DRAFT_SUPERSEDED`, 503 `AI_COACH_PROVIDER_UNAVAILABLE`/`AI_COACH_AUTH_REQUIRED`,
504 `AI_COACH_TIMEOUT`, 502 `AI_COACH_PROVIDER_ERROR`/`AI_COACH_INVALID_RESPONSE`,
422 `AI_COACH_VALIDATION_FAILED`.

## 10. Validator rules

Accept-or-reject only; it never rewrites a workout. **Hard safety** (enforced): date must equal the
requested date; non-blank title / workoutType / rationale / selectedWorkoutType; at least one
segment; total duration > 0, within `[5, 300]` minutes; every segment duration > 0; repetitions > 0;
recovery ≥ 0; pace/HR/speed strictly positive and finite; no NaN/Infinity; non-inverted ranges
(pace is seconds/km, so "fast" must be the smaller number); incline finite and |incline| ≤ 40 %;
segment durations must sum exactly to the declared total (a repeated block contributes
`reps × (work + recovery)`); and pace/HR must be within a wide plausibility band of the athlete's
**own** measured threshold (±50 %), which catches unit errors, not coaching choices.

**Coaching policy is explicitly NOT enforced** — whether today should be easy or hard, how long the
long run is, whether a threshold session is wise. A faster-than-threshold, above-LTHR interval
session passes validation; that is covered by a dedicated test.

## 11. Eval scenarios (10, provider-neutral)

`01` quality yesterday + poor recovery · `02` two easy days, good recovery, no quality ·
`03` long run yesterday · `04` 30 min treadmill only · `05` high recent load · `06` low recent load ·
`07` heavy legs reported · `08` asks for a hard session despite poor recovery ·
`09` threshold session already done · `10` no recovery data and no thresholds.

Each carries *invariants*, not a single "correct" workout: structurally valid, durations add up,
athlete is told why, hard time limit respected, treadmill session actually dial-able,
missing-recovery acknowledged rather than invented, reported pain/fatigue not ignored. The CI run
drives them with a deterministic fake coach **and** proves the invariants discriminate (a
deliberately overconfident / dismissive / overrunning answer is caught). The live Claude run of the
same scenarios is `LiveClaudeCoachEvalTest`, tagged `live-coach` and excluded from `gradlew test`
(`gradlew test -PliveCoachEval` to run it).

## 12. Tests

**120 new coach tests. Full suite: 680 tests, 0 failures, 0 errors, 0 skipped** (`gradlew clean test`,
JDK 21) — up from 560 before this phase.

Coverage: Kotlin compile + Java↔Kotlin interop both directions (a Java test constructs Kotlin types
and implements `AiCoach`); provider selection (claude selected, unimplemented provider fails fast,
unknown name does not bind, no credential field exists); CLI (success, missing CLI, unauthenticated,
non-zero exit, timeout actually kills, empty output, output cap, prompt-on-stdin); parser (well
formed, `structured_output` path, fence stripping, `is_error`, malformed outer/inner JSON, missing
assessment/workout/segments/required field, unknown enum, non-numeric, explicit nulls, version,
model fallback); context builder against **real repositories** (thresholds from the stored profile,
nulls when absent, real activities drive load and pattern, empty history, long run, quality
unavailable, all recovery null, constraints carried through, no look-ahead); persistence
(round-trip incl. every optional target, supersede, shared group id, refuse superseded, unique
constraint); API (generate/get/revise, constraints reach the coach, default date, 404/409/400,
every coach failure mapping, no approve/publish endpoint); validator (20 cases); architecture
(6 checks); eval (10 scenarios + 6 discrimination/meta checks).

Two real defects were found and fixed by these tests: a computed `effectiveDurationMinutes`
property that serialized but could not deserialize (would have broken the DB JSON round-trip in
production), and a `@Transactional` method that was self-invoked and therefore never proxied.

## 13. Live validation

Claude CLI verified available (`2.1.248`) and authenticated before starting. Runtime rebuilt and
started normally; Flyway applied **V6 to the real PostgreSQL 17** (versions 1–6 all `success = t`).

1. `POST /api/v1/workout-drafts {"date":"2026-10-03","availableMinutes":45,"environment":"TREADMILL"}`
   → 200 in 23.7 s. Real training context from the live database; Claude designed a 35-minute easy
   treadmill session (section 7), correctly stated that recovery data is unavailable, and gave
   treadmill speeds and inclines because the environment said treadmill.
2. `GET /api/v1/workout-drafts/1` → 200, version 1, `DRAFT`.
3. `POST /api/v1/workout-drafts/1/revisions` with the Korean request
   *"오늘 시간이 30분밖에 없으니 전체 시간을 줄여줘. 훈련 목적은 가능하면 유지해줘."* → 200 in 9.5 s,
   **version 2**, same `draftGroupId`, exactly **30 minutes**, rationale: *"With only 30 minutes
   available, the warm-up and cool-down are trimmed slightly so the easy running block stays as the
   priority, preserving the base-building purpose of the session."* — i.e. Claude redesigned the
   session and explained the trade-off; Spring did not mechanically trim numbers.
4. Version 1 is now `SUPERSEDED` in PostgreSQL; both rows present; re-revising v1 → 409
   `WORKOUT_DRAFT_SUPERSEDED`.

### How "zero Intervals/Garmin writes" was verified

- The Intervals calendar (`GET /athlete/0/events`, 2026-09-25 → 2026-10-10) was captured **before**
  and **after** the live run and diffed by event id and full content: 9 events before, 9 after,
  **0 added, 0 removed, 0 modified**.
- The Spring log contains **no** publisher/Intervals/publish line at all during the session; only
  `AI coach create/revise`, `Claude CLI call finished (exit code + byte counts)` and
  `Workout draft saved`.
- `WorkoutPublishApplicationService`, `IntervalsWorkoutPublisher` and `IntervalsWorkoutClient` are
  mocked in `WorkoutDraftApiTest` and asserted with `verifyNoInteractions` after every test — with
  `running-ai.workout-publishing.enabled=true`, so the separation is proven structural rather than a
  side effect of the switch being off.
- `CoachArchitectureTest` fails the build if any coach/draft source references a publishing,
  Intervals or Garmin-write type, imports those packages, performs an outbound HTTP call, or if a
  coach/draft bean declares such a constructor dependency. (Comments are stripped before scanning,
  so documentation explaining *why* something is unreachable is allowed; code is not.)

MCP remained at its configured state and the workout-publishing scheduler was not enabled or changed.

## 14. Known limitations

- **No recovery data at all.** HRV, sleep, resting HR, Body Battery and stress are not ingested, so
  every coach decision is made without them. This is the single biggest quality gap.
- **Quality-session detection does not exist**, so the coach cannot know whether the athlete's last
  hard session was two days or two weeks ago except from free-text feedback.
- The live smoke covered one athlete, one date, one create + one revise. The eval scenarios were run
  live only as an available opt-in (`-PliveCoachEval`), not as part of this validation.
- Draft approve/publish is intentionally absent, so a draft currently cannot become a real workout;
  that is Phase 6F.
- One draft group has exactly one current version; concurrent revisions of the same draft are not
  locked (last writer would hit the unique constraint rather than corrupting state).
- `ClaudeCliClientTest` is Windows-only (its stand-ins are `.cmd` scripts); the production code is
  not platform specific.
- Cost/latency: a create is ~24 s and a revise ~9 s of real model time; there is no caching.

## 15. Phase 6F recommendation

**Draft → approved → published, with the human in the middle.** Specifically: add an explicit
approve transition (`DRAFT → APPROVED`) and a separate, explicitly-triggered publish that routes an
*approved* draft through the existing, already-verified `WorkoutPublishApplicationService` →
`IntervalsWorkoutPublisher` path — reusing `StructuredWorkoutMapper`/`IntervalsWorkoutRenderer`
rather than letting the coach near Intervals. The master publish switch, the per-date single-flight
guard and the marker-based idempotency all stay exactly as they are, and the architecture test
stays, with the publish path living outside the coach package.

Strong second candidate, and arguably the higher-value one: **ingest Garmin recovery metrics**
(HRV, sleep, resting HR, Body Battery, stress) through the existing connector pattern and fill in
`RecoveryContext`. Every limitation above that starts with "the coach cannot know…" is downstream of
that gap.
