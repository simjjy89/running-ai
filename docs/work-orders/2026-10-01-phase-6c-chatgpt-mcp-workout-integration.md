# Phase 6C ChatGPT-ready MCP Workout Command Integration — work order

(Condensed faithful transcription of the user's instruction; rules and acceptance criteria are kept, repeated examples shortened.)

Baseline: `7751111` (5C Spring → Intervals → Garmin 265 complete; 6A manual publish; 6B scheduler; 448 / 448). Operating state:
`WORKOUT_PUBLISHING_ENABLED=false`, `WORKOUT_PUBLISHING_SCHEDULER_ENABLED=false`, main-PC legacy writer possibly ACTIVE — no real publish is enabled in this phase.

## Goal
An MCP client (e.g. ChatGPT) can call RunningAI workout publishing as a standard MCP tool:
`MCP client → publish_workout → RunningAI MCP adapter → WorkoutPublishApplicationService → canonical pipeline → Intervals.icu → Garmin`.
Callers of the application service after this phase: manual REST POST, daily scheduler, MCP `publish_workout`. The MCP layer holds no business logic.

## Version constraints
Keep Spring Boot **3.5.16**, Java 21, Gradle 8.14.5. Do not blindly use Spring AI 2.x; Boot 3.5.x needs the Spring AI **1.x** line — check Maven Central /
official docs and pick the latest stable 1.x compatible with Boot 3.5.16. **No Boot 4 migration.** Preferred: Spring AI MCP server inside the RunningAI app,
WebMVC, **Streamable HTTP** (not SSE as the new canonical transport), verified against the chosen version. If that is not safely possible on Boot 3.5.16:
stop, record `MCP_DEPENDENCY_COMPATIBILITY_BLOCKED`, propose a separate adapter process — never upgrade Boot for this.

## Endpoint / tool
- Endpoint `/mcp` (or the library default); keep `POST /api/v1/workout-publish`; the MCP tool calls the service in-process, never the REST API over loopback.
- Exactly one write tool: `publish_workout`, description in the sense of "Publish the RunningAI workout for an explicit calendar date through the canonical
  Spring publishing pipeline", stating: explicit date required, external side effect, publishes to the configured Intervals account, idempotent for the same
  effective workout. Tool name is a stable API.
- Input `{"date":"YYYY-MM-DD"}` only, required; no natural-language dates (client resolves them), no free-text prompt, **no credentials in arguments**.
- Output reuses 6A semantics (`date, operation CREATED/UPDATED/NO_CHANGE, verified, intent, stepCount`); no remote event id.
- Adapter responsibilities: input validation → `WorkoutPublishApplicationService.publish(date)` → result serialization. Not: recommendation, pace/LTHR,
  cue formatting, mapping, rendering, Intervals HTTP, `external_id`, retry. No direct dependency on mapper / renderer / publisher / client.
- Optional read-only `runningai_status` only if really needed (not needed ⇒ not added).

## Safety
- Must pass the existing master switch `running-ai.workout-publishing.enabled` (no bypass). New MCP switch `running-ai.mcp.enabled` /
  `RUNNINGAI_MCP_ENABLED`, default **false**; a real write needs both MCP=true AND master=true. Finish with MCP, master and scheduler all false; never true outside tests.
- Expose only `publish_workout`; disable resources / prompts / completion where feasible (least privilege); nothing auto-exposed.
- No public Internet exposure; no OAuth/security infrastructure forced in this phase; no tunnel / port forwarding / Cloudflare / ngrok / reverse proxy;
  Secure MCP Tunnel only evaluated later. No Command Channel revival (no Git/file polling, PowerShell queues). No direct ChatGPT → Intervals.
- Concurrency and idempotency: reuse 6A per-date single-flight (`WORKOUT_PUBLISH_ALREADY_RUNNING`) and publisher semantics; no MCP lock, no MCP idempotency store,
  **no retry** (trust the publisher's unknown-outcome reconciliation). REST day keeps `INTERVALS_EMPTY_WORKOUT` (no `SKIPPED_REST_DAY`).
- Error contract: machine-readable codes preserved (WORKOUT_PUBLISHING_DISABLED, WORKOUT_PUBLISH_ALREADY_RUNNING, INTERVALS_NOT_CONFIGURED / AUTH_FAILED / FORBIDDEN /
  RATE_LIMITED / UPSTREAM_ERROR / READBACK_MISMATCH / DUPLICATE_OWNED_WORKOUT / UNMANAGED_WORKOUT_CONFLICT / EMPTY_WORKOUT), e.g. `{"success":false,"code":…,"message":…}`
  following the SDK's error model; no stack traces. No interactive confirmation inside the tool (platform confirmation applies).
- Logging: tool name, date, operation, verified only (no key, Authorization, credentials, workout body, raw headers, physiological data). No DB audit table.

## Tests
Registration (MCP off → nothing exposed; on → `publish_workout` exists), successful invocation (exactly one `publish(2026-10-02)`), result preservation
(CREATED, verified; NO_CHANGE is a success), master off with MCP on → disabled error, no network write, invalid input (missing, invalid date) rejected before the
service, already-running preserved (integration if possible), dependency/architecture test (no mapper / renderer / publisher / client / target service).
No external network: service mocked/stubbed; a real credential in the environment must not allow a calendar write. Full regression (baseline 448) with
`gradlew clean test`. Dependency inspection (`dependencies` / `dependencyInsight`: Spring Framework, Jackson, Reactor, MCP SDK, Spring Boot) — Boot must stay 3.5.16.

## Unchanged / forbidden
`/api/v1/*` collisions none; existing GET 500 technical debt untouched; 6B scheduler unchanged (cron, zone, retry, missed run); no Flyway migration / schema
change; no legacy deletion; `.env.example` may gain `RUNNINGAI_MCP_ENABLED=false` only (no auth secret placeholders); no live Intervals write, no real
ChatGPT connection (protocol-level local test with a mocked service at most). Do not start any following phase.

## Docs
README / integration skill / CLAUDE.md: MCP adapter exists, default disabled, tool name, input/output, master-switch relationship, scheduler independence,
no public exposure, main-PC legacy prerequisite, remote ChatGPT transport not activated; "RunningAI MCP server readiness ≠ ChatGPT account/workspace MCP write
availability". Main-PC sequence: 1 check legacy writer · 2 disable it · 3 pull main · 4 restart Spring · 5 `WORKOUT_PUBLISHING_ENABLED=true` · 6 scheduler=false ·
7 MCP=false · 8 REST manual smoke · 9 check Intervals + Garmin · 10 connect MCP transport · 11 MCP=true · 12 MCP tool smoke · 13 scheduler=true last if wanted;
one switch at a time. ChatGPT connection options (custom MCP app, Secure MCP Tunnel, Desktop local MCP) are decided later; no transport is pinned as canonical.

## Finish
Implementation → tests → dependency verification → docs → diff / secrets check → commit `feat: add MCP workout publish integration` → push. Report in the
requested format (MCP implementation, Tools, Safety, Error semantics, Tests, Dependency verification, Database, Live validation, Git, Operational state, Next:
MAIN-PC OPERATIONAL CUTOVER first, then the ChatGPT ↔ RunningAI MCP transport and an end-to-end smoke test; not auto-started).
