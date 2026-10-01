# Phase 6C ChatGPT-ready MCP Workout Command Integration — result

Baseline `7751111`. Implemented and tested (499 / 499). **Not enabled anywhere**: MCP, the publishing master switch and the scheduler are all off;
no Intervals write, no ChatGPT connection. Compatibility check passed — `MCP_DEPENDENCY_COMPATIBILITY_BLOCKED` was **not** needed.

## MCP implementation
- Library / version: **Spring AI 1.1.8** (`spring-ai-bom:1.1.8`, `spring-ai-starter-mcp-server-webmvc`) → MCP Java SDK **0.18.3** (`mcp-core`, `mcp-json-jackson2`,
  `mcp-spring-webmvc`). Why 1.1.8: newest stable 1.x on Maven Central (1.0.0 … 1.1.8, then 2.0.x); the 1.1.x line is built against Spring Boot 3.5 (its starter
  POM declares `spring-boot-starter:3.5.15`), while Spring AI 2.x targets Boot 4 — not used.
- Spring Boot version: **3.5.16** (plugin unchanged; the 3.5.15 references resolve to 3.5.16 through Boot's dependency management). Java 21.
- Transport: **Streamable HTTP, stateless mode** (`spring.ai.mcp.server.protocol=STATELESS`, WebMVC `WebMvcStatelessServerTransport`). Chosen over the stateful
  `STREAMABLE` mode because the only tool is a stateless request/response write: no session, no server push, plain JSON responses. SSE (the library default) is not used.
- Endpoint: `POST /mcp` (JSON-RPC). No collision with `/api/v1/*` (all 448 earlier tests pass; `GET /api/v1/health` checked with MCP on).
- MCP enabled by default: **NO**. Important finding: Spring AI's own `spring.ai.mcp.server.enabled` defaults to **true**, so the dependency alone would start an
  (SSE) MCP server on every boot. `application.yml` therefore binds `spring.ai.mcp.server.enabled: ${running-ai.mcp.enabled}` and
  `running-ai.mcp.enabled: ${RUNNINGAI_MCP_ENABLED:false}` — one switch, default off (tested: no server, transport or tool bean, `/mcp` → 404).

## Tools
- Tool: `publish_workout` (the only one; registered explicitly as a `List<McpStatelessServerFeatures.SyncToolSpecification>` bean by `McpToolConfig`, which itself
  exists only when `running-ai.mcp.enabled=true`). Annotation scanning off; resources / prompts / completions capabilities off (tested: `initialize` advertises
  `tools` only, `tools/list` returns exactly one tool). Tool annotations: readOnly=false, destructive=true (may overwrite RunningAI's own workout), idempotent=true, openWorld=true.
- Input: `{"date":"YYYY-MM-DD"}` — JSON schema `required:[date]`, `additionalProperties:false`; server-side validation additionally rejects missing/null/non-string
  dates, anything not matching `\d{4}-\d{2}-\d{2}`, impossible dates (`2026-02-30`), natural language (`today`, `tomorrow`, `next Monday`) and any extra argument
  (e.g. an `apiKey`) — before the service is called (`INVALID_ARGUMENT` / `INVALID_DATE`).
- Output: `structuredContent` and an identical JSON text content: `{"success":true,"date":"2026-10-02","operation":"CREATED","verified":true,"intent":"EASY","stepCount":3}`;
  no remote event id.
- Application service called: `WorkoutPublishApplicationService.publish(date)`, exactly once per call, in-process (no REST loopback).
- Other direct business dependencies: **none** — `PublishWorkoutMcpTool`'s constructor takes only the service and Jackson's `ObjectMapper`; a test also scans the
  `integration.mcp` sources for mapper / renderer / publisher / client / target-service / `RestClient` references.

## Safety
- MCP switch: `running-ai.mcp.enabled` / `RUNNINGAI_MCP_ENABLED` = **false** (default, `.env.example`)
- Workout publishing master: `WORKOUT_PUBLISHING_ENABLED` = **false**
- Scheduler switch: `WORKOUT_PUBLISHING_SCHEDULER_ENABLED` = **false** (6B unchanged)
- MCP can bypass master: **NO** (tested with MCP on, master off and the real service: `WORKOUT_PUBLISHING_DISABLED`, no target-service or publisher interaction).
  A real write needs MCP=true **and** master=true.
- Public Internet exposure: **NO** — no tunnel, port forwarding or reverse proxy configured; `/mcp` has no authentication and must stay on localhost / private network.
  Auth is the transport/security layer's job in a later step; credentials are never accepted as tool arguments.

## Error semantics (`isError=true`, `{"success":false,"code":…,"message":…,"date":…}`, no stack trace)
- disabled: `WORKOUT_PUBLISHING_DISABLED`
- already-running: `WORKOUT_PUBLISH_ALREADY_RUNNING` (tested end to end with the real service: a concurrent same-date publish rejects the MCP call; no MCP lock)
- Intervals errors: `INTERVALS_<REASON>` for every `IntervalsException.Reason` (NOT_CONFIGURED, AUTH_FAILED, FORBIDDEN, RATE_LIMITED, CLIENT_ERROR, UPSTREAM_ERROR,
  TIMEOUT, CONNECTION_FAILED, INVALID_RESPONSE, DUPLICATE_OWNED_WORKOUT, UNMANAGED_WORKOUT_CONFLICT, READBACK_MISMATCH, EMPTY_WORKOUT); exactly one service call, no retry
- domain errors: the code of `UnprocessableRequestException` / `ResourceNotFoundException`; anything else → `INTERNAL_ERROR` with a generic message
- invalid date: `INVALID_DATE` (bad format / impossible / natural language / non-string) or `INVALID_ARGUMENT` (missing, extra arguments)
- REST day: unchanged contract → `INTERVALS_EMPTY_WORKOUT`

## Tests
- previous: 448 · new: **51** · total: **499** · passed: **499** · failed: **0** (skipped 0) — `gradlew clean test`, JDK 21
- New: `PublishWorkoutMcpToolTest` (39: success + exactly-once call, CREATED/UPDATED/NO_CHANGE, 11 invalid date strings, missing / null / non-string date, extra
  credential-like argument, disabled, already running, all 13 Intervals reasons, domain code, no internal leak, tool contract, handler delegation, constructor
  dependencies, source scan), `McpServerProtocolTest` (8, JSON-RPC over `/mcp` with the service mocked: initialize capabilities, one listed tool, tools/call result,
  NO_CHANGE, invalid / missing date, already-running code, REST API unaffected), `McpServerDisabledTest` (2), `McpMasterSwitchTest` (1), `McpSingleFlightTest` (1)
- No external network: service or publisher + prescription source mocked; Spring tests pin a blank Intervals key and an unreachable URL and an explicit MCP switch value

## Dependency verification (`gradlew dependencies` / `dependencyInsight`, runtime and test runtime classpaths, before vs after)
- Spring Boot: **3.5.16** · Spring Framework: **6.2.19** · Jackson: **2.21.4** (already Boot 3.5.16's managed version before this change) · Reactor: 3.7.19 (new, pulled by the MCP SDK)
- Spring AI / MCP: 1.1.8 / 0.18.3
- conflicts: **none — 0 pre-existing artifacts changed version**; only new artifacts were added (MCP SDK, Spring AI mcp/commons/model/template-st, reactor-core,
  reactive-streams, context-propagation, spring-messaging, json-schema tooling (victools, networknt, jackson-module-jsonSchema, jackson-dataformat-yaml),
  jtokkit, ST4/antlr, swagger-annotations, `javax.validation:validation-api:1.1.0.Final`, mcp-annotations). No webflux. The legacy `javax.validation` API jar sits
  next to Jakarta Validation (different package; existing validation tests pass); noted as a classpath footprint, not a conflict.

## Database
migration: **NO** · schema: **NO**

## Live validation
actual Intervals write: **NO** · actual ChatGPT connection: **NO** · MCP enabled outside tests: **NO** (protocol-level test only, local MockMvc, mocked service)

## Product boundary
RunningAI MCP server readiness ≠ ChatGPT account/workspace MCP write availability. Connecting ChatGPT needs a separate, supported client setup (custom MCP
connector/app, Secure MCP Tunnel or a desktop local MCP option, depending on the plan/client at that time) plus an authenticated transport; none is chosen or configured here.

## Operational state
- main-PC legacy writer: **ACTIVE possible** · Spring master: **OFF** · scheduler: **OFF** · MCP: **OFF** · safe for remote write: **NO**

### Main-PC sequence (one switch at a time; not executed)
1 check the legacy writer · 2 disable it · 3 pull main · 4 restart Spring · 5 `WORKOUT_PUBLISHING_ENABLED=true` · 6 scheduler stays false · 7 MCP stays false ·
8 REST manual smoke (`POST /api/v1/workout-publish`) · 9 check Intervals + Garmin · 10 set up the MCP transport (private, authenticated) · 11 `RUNNINGAI_MCP_ENABLED=true` ·
12 MCP `publish_workout` smoke · 13 only then, if wanted, scheduler=true.

## Limitations
- No authentication on `/mcp` (like the REST API); remote exposure requires a separate secured transport.
- Stateless mode: no server-initiated notifications (`tool-change-notification` is irrelevant with a fixed tool set).
- The pre-existing `GET /api/v1/workout-publish → 500` technical debt is untouched.

## Next (not started)
**MAIN-PC OPERATIONAL CUTOVER** first; then connect a ChatGPT ↔ RunningAI MCP transport and run an end-to-end smoke test.
