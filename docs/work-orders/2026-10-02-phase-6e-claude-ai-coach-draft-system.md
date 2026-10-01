# Phase 6E — Kotlin-first Claude AI Coach Draft System (work order)

Repo `C:\running-ai-github`, branch `main`, HEAD `f1acc4d` at start, working tree clean
(2 commits ahead of `origin/main` from the previous task; not pushed, left as-is).
`C:\running-ai` (legacy) untouched.

## Goal

Make the AI the thing that actually analyses training and designs the workout; Spring collects and
normalises data, supplies context, validates the response, stores a draft, and enforces safety.
Spring must **not** decide EASY/QUALITY/LONG by its own rules in this path.

```
Garmin + Intervals -> TrainingContextBuilder -> AiCoach -> ClaudeAiCoach -> WorkoutDraft
  -> Validator -> DB -> Preview -> user natural-language request -> Claude redesign -> new version
```

STOP there. **No publish in this phase.**

## 0. Investigation findings (done before writing code)

### Claude CLI contract (actually probed on this PC)

`claude --version` → `2.1.248 (Claude Code)`. Verified by running it:

- `-p/--print` = non-interactive. Prompt may be passed on stdin (used here, so a long context JSON
  never lands in a process command line / `Win32_Process.CommandLine`).
- `--output-format json` → one JSON object on stdout with `is_error`, `subtype`, `result` (the
  model's text), `session_id`, `usage`, `modelUsage`, `permission_denials`, `num_turns`,
  `total_cost_usd`. Confirmed live.
- `--json-schema <schema>` → adds a validated `structured_output` object to that result JSON.
  Confirmed live (returned both `result` as a JSON string and a parsed `structured_output`).
  Phase 6E parses the **inner** coach JSON itself (from `result`) and treats `structured_output`
  as an optional fast path, so the parser is not coupled to a CLI-only feature.
- `--tools ""` → disables **all** built-in tools (no Bash, no Edit, no Read, no WebFetch). Confirmed
  live: a tools-disabled run still answers normally.
- `--strict-mcp-config` → ignores every configured MCP server (so RunningAI's own `publish_workout`
  MCP tool can never be reached by a coach call, even when `RUNNINGAI_MCP_ENABLED=true`).
- `--setting-sources ""` → ignores user/project/local settings files, so a developer's local
  permission settings cannot widen what a coach call may do.
- `--no-session-persistence` → nothing written to the session store.
- `--system-prompt` → replaces the default Claude Code system prompt (coach role, not a coding agent).
- `--model` → explicit model id/alias.
- Auth: the CLI's existing login session on this PC. **No `ANTHROPIC_API_KEY`, no Anthropic HTTP
  call from Spring.** (Note: `--bare` is explicitly *not* used — it forces API-key auth.)

Chosen command shape (prompt on stdin):

```
claude -p --output-format json --model <model> --system-prompt <coach system prompt>
       --tools "" --strict-mcp-config --setting-sources "" --no-session-persistence
```

### Existing data actually available for TrainingContext (reused, not re-implemented)

- `TrainingDecisionContextService.context(date)` → `TrainingDecisionContext`, which already
  contains `TrainingState` (acute/chronic load, ACR, 7d/previous-7d load, distance and duration
  change %, ramp, monotony, strain, active/rest days), `recentPattern` (`DailyTrainingPattern`:
  per-day classification, load minutes, running duration/distance, cycling duration),
  `lastRunningDate`/`daysSinceRunning`, `lastActiveDate`/`daysSinceActive`,
  `lastLongRunDate`/`daysSinceLongRun`, `consecutiveActiveDays`/`consecutiveRestDays`, `loadTrend`.
  All of it is computed from normalised `activity` rows in the athlete timezone. **Reused verbatim.**
- `AthleteIntensityProfileService.getDefaultProfile()` → LTHR bpm + threshold pace sec/km
  (Phase 6D keeps these in sync with Garmin). **Reused verbatim.**
- `qualityDetectionAvailable` is `false` in the existing code and `lastQualityDate`/
  `daysSinceQuality` are always null — quality-session detection does not exist. The context must
  therefore say "quality detection unavailable", not pretend a last-quality date exists.

### Recovery data gap (recorded honestly, not faked)

HRV, sleep, resting HR, Body Battery and stress are **not ingested anywhere** in this repo: the
Garmin connector exposes only `/health`, `/activities` and `/lactate-threshold`, and no table or
column stores them. Per the instruction ("아직 ingestion 되지 않은 recovery 데이터가 있다면 이번
Phase에서 무리하게 Garmin connector 전체를 확장하지 않는다"), they are modelled as **nullable** in
`TrainingContext.recovery` and emitted as explicit JSON `null`, with the prompt telling Claude that
a null metric is genuinely unknown and must not be guessed. No fabricated value is ever sent.

## 1. Kotlin setup

Kotlin JVM + Kotlin Spring plugins at the version Spring Boot 3.5.16 already manages for
`kotlin-reflect`, plus `kotlin-reflect` and `jackson-module-kotlin`. Java 21 toolchain shared with
the existing Java compilation; `-Xjsr305=strict` for Java-interop nullability. Groovy
`server/build.gradle` kept. Java↔Kotlin interop both directions must work and all existing Java
tests must stay green. Spring Boot / Spring AI / Java versions are **not** changed.

## 2. Vendor-neutral contract (`com.runningai.coach`)

```kotlin
interface AiCoach {
    fun createWorkout(context: TrainingContext): WorkoutDraft
    fun reviseWorkout(context: TrainingContext, currentDraft: WorkoutDraft, userRequest: String): WorkoutDraft
}
```

No vendor type in the signature. `CoachProvider` = `CLAUDE | CODEX | OLLAMA`; only `CLAUDE` is
implemented, selected by `running-ai.coach.provider` / `RUNNING_AI_COACH_PROVIDER` (default
`claude`); an unsupported-but-known provider fails fast at startup with a clear message.

## 3. Claude implementation (`com.runningai.coach.claude`)

`ClaudeAiCoach` → `ClaudeCoachPromptBuilder` (system + user prompt), `ClaudeCliClient` (process
execution, timeout, exit code, stdout/stderr caps, auth/CLI-unavailable detection, no retry loop,
never logs prompt content or credentials), `ClaudeCoachResponseParser` (outer CLI JSON → inner
coach JSON, strict), `WorkoutDraftValidator` (pass/reject only).

## 4. Domain

`TrainingContext` (athlete thresholds, recentTraining, recovery (nullable), weeklyContext,
constraints), `WorkoutDraft` (id, version, date, title, workoutType, totalDurationMinutes,
assessment, segments, createdAt, provider, model, status `DRAFT|SUPERSEDED`),
`WorkoutDraftSegment`, `CoachAssessment` (recoveryAssessment, loadAssessment, selectedWorkoutType,
rationale, warnings). Existing Java enums are reused where the meaning is identical
(`SegmentType`, `IntensityClass`, `CandidateTrainingType`) instead of duplicating them.

## 5. Structured response contract

JSON only, no markdown fence, no prose. Unknown/missing required field → explicit parse error, never
a silent default.

## 6. Persistence

Flyway `V6__create_workout_draft.sql`. Revision = **new version row**, previous version set
`SUPERSEDED`; drafts share a `draft_group_id` so `v1`/`v2` of "the same draft" are auditable. The raw
Claude response is not stored; only the normalised draft, assessment, provider/model and timestamps.

## 7. REST API (draft only)

- `POST /api/v1/workout-drafts` — generate
- `GET /api/v1/workout-drafts/{id}` — preview
- `POST /api/v1/workout-drafts/{id}/revisions` — natural-language revision → Claude redesigns

## 8. Absolute publish prohibition

No coach/draft class may reference `WorkoutPublishApplicationService`, `IntervalsWorkoutPublisher`,
`IntervalsWorkoutClient`, the MCP publish tool, or any Intervals/Garmin write. Enforced by an
**architecture test** that scans the compiled coach/draft sources for those references, and by
constructor-dependency assertions. Must hold even with `WORKOUT_PUBLISHING_ENABLED=true`.

## 9. Eval fixtures

≥10 provider-neutral scenarios with a deterministic fake coach for CI (no live Claude call in any
automated test) and a separately-tagged live eval that is not part of `gradlew test`.

## 10. Live validation

Only after all automated tests are green: verify the CLI is available and authenticated, build one
TrainingContext from the real RunningAI database, generate exactly **one** draft, preview it, run
**one** revision, and prove zero Intervals/Garmin writes occurred.

## Non-goals

Draft approve/publish, automatic publish, enabling the publishing scheduler, Claude→Intervals or
Claude→Garmin writes, ChatGPT MCP tunnel, OpenAI Responses API, Codex/Ollama coach implementations,
mass Java migration, Spring Boot or Spring AI upgrades.

## Result doc

`2026-10-02-phase-6e-claude-ai-coach-draft-system-result.md`.
