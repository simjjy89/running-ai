# Phase 7F-1 result — ChatGPT training MCP (draft → approve → publish)

Date: 2026-10-13
Branch: `main-3` (canonical `main` NOT merged, nothing pushed — as instructed)
Design: `docs/architecture/chatgpt-training-mcp.md`
Instruction: `2026-10-13-phase-7f-1-instruction.md`

**Real Intervals.icu publishes made by this phase: 0.** Production configuration unchanged;
production Spring and the production gateway were not restarted.

## STEP 1 — what already existed

All six endpoints and their services were read before anything was written. The finding that shaped
the phase: **the entire lifecycle already exists**, with every safety rule enforced inside the
services, so 7F-1 is an exposure-and-authorization phase, not a domain phase.

| Lifecycle step | Service | Rules it already enforces |
|---|---|---|
| `POST /api/v1/workout-drafts` | `WorkoutDraftService.generate` | injury gate; a date whose draft is approved cannot get a new one; coach failure is a failure |
| `GET .../{id}` | `WorkoutDraftService.get` | — |
| `POST .../{id}/revisions` | `WorkoutDraftService.revise` | an APPROVED draft is immutable; a SUPERSEDED version cannot be revised |
| `POST .../{id}/approve` | `WorkoutDraftApprovalService.approve` | injury gate re-checked; one approval per athlete+date; idempotent; compare-and-set so exactly one transition out of DRAFT wins |
| `GET .../{id}/publish-preview` | `ApprovedWorkoutDraftPublishService.preview` | read-only, no Intervals call, works with the switch off |
| `POST .../{id}/publish` | `ApprovedWorkoutDraftPublishService.publish` | master switch; per-draft single flight; re-publish returns the stored result; a failure is never recorded; injury gate re-checked immediately before sending |

Nothing of this was reimplemented. `PublishingModeGuard` is untouched.

## STEP 2 — the six MCP tools

`server/src/main/kotlin/com/runningai/integration/mcp/train/` — `TrainMcpTools.kt` +
`McpTrainToolConfig.kt`, behind the new switch `RUNNINGAI_MCP_TRAIN_ENABLED`
(`running-ai.mcp.train-enabled`, default **false**), a third switch independent of 6C's `enabled`
and 7C-2a's `read-enabled`.

| Tool | Calls | readOnly | openWorld |
|---|---|---|---|
| `create_workout_draft` | `WorkoutDraftService.generate` | no | no |
| `get_workout_draft` | `WorkoutDraftService.get` | **yes** | no |
| `revise_workout_draft` | `WorkoutDraftService.revise` | no | no |
| `approve_workout_draft` | `WorkoutDraftApprovalService.approve` | no | no |
| `preview_draft_publish` | `ApprovedWorkoutDraftPublishService.preview` | **yes** | no |
| `publish_approved_draft` | `ApprovedWorkoutDraftPublishService.publish` | no | **yes** |

- Deliberately placed in a **separate package** from `integration.mcp.read`, so 7C-2a's structural
  guarantee ("the read tools cannot write, by construction") keeps holding unchanged.
- Responses are the **REST DTOs themselves** (`WorkoutDraftResponse`,
  `WorkoutDraftApprovalResponse`, `DraftPublishPreviewResponse`, `DraftPublishResponse`), so the
  MCP and REST surfaces cannot drift apart.
- Errors use **the same codes the REST handlers return** — `WORKOUT_DRAFT_NOT_FOUND`,
  `WORKOUT_DRAFT_SUPERSEDED`, `WORKOUT_DRAFT_APPROVED_IMMUTABLE`, `WORKOUT_DATE_ALREADY_APPROVED`,
  `WORKOUT_DRAFT_APPROVAL_REQUIRED`, `WORKOUT_SAFETY_*`, `AI_COACH_*`, `DRAFT_PUBLISHING_DISABLED`,
  `DRAFT_PUBLISH_ALREADY_RUNNING`, `UNPUBLISHABLE_DRAFT` (with its reason list), `INTERVALS_*` —
  plus `INVALID_ARGUMENT`, `ATHLETE_CONFIRMATION_REQUIRED`, `DRAFT_VERSION_MISMATCH`,
  `APPROVAL_MISMATCH`, `INTERNAL_ERROR`.
- Every answer, success or failure, carries a **`correlationId`** that is also on each log line the
  call wrote.
- Dates default to the athlete's today via `WorkoutDraftService.today()` (athlete timezone,
  Asia/Seoul), never the server's clock.
- Unknown argument keys are rejected, so a typo never becomes "default behaviour".
- No message leaks a stack trace, an internal class name or a connection string (tested).

**The coach is never faked.** Every `AiCoachException.Reason` becomes `AI_COACH_<reason>` with an
operator-readable cause; a missing CLI says so in words and states that no workout was invented.
A separate pre-flight "is the CLI installed?" probe was deliberately *not* added: the existing
`PROVIDER_UNAVAILABLE` answers that by actually trying, which a probe can only do less reliably
and one race earlier.

## STEP 3 — OAuth scope separation

`tools/mcp-gateway/server.js`: a **hard-coded, non-configurable** per-tool scope policy.

| Scope | Tools |
|---|---|
| `runningai:read` (base) | the seven read tools; also `initialize` / `ping` / `tools/list` |
| `runningai:train` | `create_workout_draft`, `get_workout_draft`, `revise_workout_draft`, `approve_workout_draft`, `preview_draft_publish` |
| `runningai:publish` | `publish_approved_draft` |

- Hard-coded because which scope a tool demands is a security property of that tool — no
  environment variable can downgrade a write tool to the read scope.
- **Scopes are not hierarchical**: `runningai:publish` does not imply `runningai:train`, and no
  amount of `runningai:read` reaches either (tested both ways).
- `tools/list` is filtered to the allowlist **intersected with the token's granted scopes**, so a
  read-only connection cannot even see a tool it could not call.
- A scope failure is an HTTP 403 OAuth challenge naming the missing scope, raised **before** the
  backend is touched — asserted by backend hit counter in every negative case.
- `publish_workout` remains permanently un-allowlistable (startup refuses); unknown tool names and
  foreign subjects are refused regardless of scope.
- **The default allowlist is still exactly the seven read tools**, so an unconfigured gateway —
  production's — behaves precisely as before (explicitly tested).
- **The worker was deliberately not touched.** `tools/mcp-worker` still filters granted scopes
  against `['runningai:read','offline_access']`, so a token carrying train/publish cannot currently
  exist. That is the single change that would make write-scoped tokens possible and it belongs with
  the 7F-2 approval, not before it.

## STEP 4 — the enforced workflow

```
create → (get) → (revise)* → approve → preview → publish
```

| Required property | Where it is enforced |
|---|---|
| an unapproved draft cannot be published | `loadApproved` → `WORKOUT_DRAFT_APPROVAL_REQUIRED` |
| an approved draft is immutable | `WorkoutDraftService.revise` → `WORKOUT_DRAFT_APPROVED_IMMUTABLE` |
| what is published is what was approved | publish maps from the stored approved draft, which cannot have changed since approval |
| the target account / date / content | account = whoever owns `INTERVALS_API_KEY` (never hard-coded); date = the draft's; content = the renderer's text, with the publisher's readback verification |
| no duplicate publish | V10 unique per draft; a re-publish returns the stored result without calling Intervals |
| concurrent publishes | per-draft in-JVM single flight → `DRAFT_PUBLISH_ALREADY_RUNNING` |
| an uncertain outcome is not blindly re-sent | nothing retries; the tool description directs the caller to `preview_draft_publish` and its `publication` field |
| the injury gate cannot be bypassed | re-checked at generate, approve, preview and immediately before sending |

**An MCP call is not the athlete's approval.** Three mechanisms, and the docs are explicit about
what each does and does not prove:

1. `readOnlyHint: false` on the four writing tools — what makes an MCP client prompt its user. This
   is the only mechanism that involves a human, and it belongs to the client.
2. `confirmedByAthlete: true` is a **required** argument on both committing tools. Absent is
   `INVALID_ARGUMENT`; `false` is `ATHLETE_CONFIRMATION_REQUIRED`. Approval can never be an
   incidental side effect.
3. **Proof of having read**: `approve_workout_draft` requires `acknowledgedVersion` (the version
   actually shown) and `publish_approved_draft` requires `acknowledgedApprovalId`. Both are checked
   against the stored value by a read-only call *before* the committing service is entered, so a
   caller that never displayed the draft cannot supply them, and a draft revised since the athlete
   saw it cannot be approved by accident.

## STEP 5 — self-hosted architecture

No multi-tenant feature was added. No Intervals account id, athlete id or any other identity is
hard-coded anywhere in the new code: the account follows `INTERVALS_API_KEY`, the athlete comes
from the existing `AthleteService` via the lifecycle services, and the gateway's permitted subject
stays an environment variable. The new tools hold exactly three service dependencies plus Jackson
(asserted by the architecture test), so a future per-account credential provider is a change inside
those services, not here. Package layout, layering and the Athlete domain are unchanged. No
migration: **no schema change in this phase**.

## STEP 6 — tests

- Server suite: **1,414 tests, 0 failures** (`.\gradlew.bat clean test`, JDK 21, BUILD SUCCESSFUL in 6m58s).
  The baseline before this phase was 1,385, so the 29 new tests are all of the increase.
- Gateway suite: **46/46 checks pass** (`node test/gateway-test.js`), up from 33.

New server tests (`server/src/test/kotlin/com/runningai/integration/mcp/train/`):

- `McpTrainServerApiTest` — the six tools over the real `/mcp` endpoint with the three services
  mocked: registration, annotations, the full happy path, constraints actually reaching the coach,
  rest-day handling, re-publish, every domain refusal code, every `AiCoachException.Reason`,
  argument validation, and that no message leaks internals.
- `McpTrainSwitchSeparationTest` — with only `read-enabled` on, the seven read tools are listed,
  **no** training tool is, and forcing one by name fails without touching any lifecycle service.
- `TrainMcpToolsArchitectureTest` — the adapter has no route to a renderer, publisher, repository,
  the legacy publish path or the coach; it persists nothing itself; both committing tools go
  through the confirmation helper; an `AiCoachException` is never caught into a draft.

Requirement-by-requirement coverage of STEP 6's list:

| # | Requirement | Covered by |
|---|---|---|
| 1 | read tools, no regression | `McpTrainSwitchSeparationTest`, existing `McpReadServerApiTest` |
| 2 | six tools registered | `McpTrainServerApiTest` |
| 3 | scope allow/deny | gateway suite (read/train/publish matrix) |
| 4 | no token / forged / insufficient | gateway suite (11 negative cases, backend at zero hits) |
| 5 | draft create & revise | `McpTrainServerApiTest` |
| 6 | publish before approval refused | `McpTrainServerApiTest` |
| 7 | approved draft immutable | `McpTrainServerApiTest` + existing `WorkoutDraftApprovalTest` |
| 8 | injury gate blocks | `McpTrainServerApiTest` |
| 9 | no duplicate publish | `McpTrainServerApiTest` (re-publish returns the stored result) |
| 10 | concurrent requests | `McpTrainServerApiTest` (`DRAFT_PUBLISH_ALREADY_RUNNING`) |
| 11 | Intervals failure & timeout | `McpTrainServerApiTest` (`INTERVALS_TIMEOUT`) |
| 12 | coach CLI failure | `McpTrainServerApiTest` (all six reasons) |
| 13 | gateway restart & OAuth compatibility | gateway suite (fresh processes; unconfigured gateway unchanged) |

All external publishing in these tests is against mocks/fakes. No real Intervals call was made.

### Two things worth recording

- **Gradle's `--tests` filter does not work in this project.** It reports "No tests found" for
  existing, passing tests in BOTH languages - `*McpReadServerApiTest` (Kotlin) and
  `*McpServerProtocolTest` (Java) - on a freshly cleaned build, so it is neither specific to the
  new code nor a Kotlin output-directory problem. Until someone investigates, the only reliable
  way to run anything is the full `clean test`.
- **MCP structured content omits null map entries.** A DTO field that is null (for example
  `remoteEventId` on a rest-day publication) arrives as *absent*, not as JSON `null`, even though
  the DTO is `@JsonInclude(ALWAYS)` — the outer map is serialized under the application's global
  `non_null` inclusion. Harmless for a JSON consumer, but a test must not assert `isNull`.

## STEP 7 — operational policy observed

Done: main-3 code, the tools, the gateway policy, automated tests, docs, commit.
**Not done, by instruction:** no real Intervals publish, no production OAuth scope widening (the
worker is untouched), no write tool exposed on the production gateway, no production DB change, no
production Spring/gateway restart, no canonical merge, no `origin` push, no Watchface/Relay change.

## Changed files

```
server/src/main/kotlin/com/runningai/integration/mcp/train/TrainMcpTools.kt       (new)
server/src/main/kotlin/com/runningai/integration/mcp/train/McpTrainToolConfig.kt  (new)
server/src/main/java/com/runningai/integration/mcp/McpServerSwitchEnvironmentPostProcessor.java
server/src/main/resources/application.yml
server/src/test/kotlin/com/runningai/integration/mcp/train/McpTrainServerApiTest.kt        (new)
server/src/test/kotlin/com/runningai/integration/mcp/train/McpTrainSwitchSeparationTest.kt (new)
server/src/test/kotlin/com/runningai/integration/mcp/train/TrainMcpToolsArchitectureTest.kt(new)
tools/mcp-gateway/server.js
tools/mcp-gateway/test/gateway-test.js
docs/architecture/chatgpt-training-mcp.md                                          (new)
docs/work-orders/2026-10-13-phase-7f-1-instruction.md                              (new)
docs/work-orders/2026-10-13-phase-7f-1-result.md                                   (new)
.env.example
CLAUDE.md
```

## Expected impact if this were deployed as-is

Essentially none, which is the point: with `RUNNINGAI_MCP_TRAIN_ENABLED=false` the six tools are
not registered, the Spring AI server switch computes to the same value it does today, the gateway's
default allowlist and advertised scope are unchanged, and the worker cannot issue a write scope.
The only behaviour change a deployment would bring is that the gateway now *logs* its per-tool
scope policy at startup. A restart would still be needed for the code to be loaded at all, and none
was performed.

## Phase 7F-2 — execution order

Each step needs its own approval:

1. **Validate the draft→Intervals path once, by hand, against the real account.** It has never
   published for real (6G shipped with external publish `NOT_RUN`). Do it over REST on a dev
   instance holding the real Intervals key, on a date the athlete does not mind being written,
   before any of it is reachable from ChatGPT.
2. Add `runningai:train` and `runningai:publish` to the worker's `SCOPES_SUPPORTED`; deploy.
3. **Re-authorize the ChatGPT connector.** A connection keeps the scopes it was granted at creation,
   so the existing read-only connection will not silently gain the new ones.
4. Set `RUNNING_AI_ALLOWED_TOOLS` on the production gateway to include the lifecycle tools; restart
   the gateway.
5. Turn on `RUNNINGAI_MCP_TRAIN_ENABLED`, then `RUNNING_AI_DRAFT_PUBLISHING_ENABLED` (legacy
   workout publishing is already false, which the XOR guard requires); restart Spring **from the
   released jar** (`scripts\windows\release-spring-jar.ps1`, Phase 7E-5).
6. First real run end to end: create → read → approve → **preview** → publish, with a manual check
   of the Intervals calendar between the preview and the publish.
