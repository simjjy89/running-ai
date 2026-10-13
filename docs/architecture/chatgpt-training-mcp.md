# ChatGPT training MCP — draft → approve → publish (Phase 7F-1)

Status: implemented and verified against mocks/fakes. **Zero real Intervals.icu publishes have been
made through this path**, and it is switched off in production. Phase 7F-2 is the real-publish
validation; its prerequisites are listed at the end.

## 1. What this phase added, and what it deliberately did not

Phase 7C-2 put RunningAI's *read* data in ChatGPT's hands. 7F-1 puts the existing *training
lifecycle* there as well — designing a session, revising it, the athlete approving it, and sending
it to Intervals.icu.

Almost none of that is new behaviour. The whole lifecycle already existed as REST endpoints over
three services, with every safety rule enforced inside them:

| Step | Service | REST |
|---|---|---|
| design a session | `WorkoutDraftService.generate` | `POST /api/v1/workout-drafts` |
| read it | `WorkoutDraftService.get` | `GET /api/v1/workout-drafts/{id}` |
| redesign after feedback | `WorkoutDraftService.revise` | `POST .../{id}/revisions` |
| the athlete approves | `WorkoutDraftApprovalService.approve` | `POST .../{id}/approve` |
| see what would be sent | `ApprovedWorkoutDraftPublishService.preview` | `GET .../{id}/publish-preview` |
| send it | `ApprovedWorkoutDraftPublishService.publish` | `POST .../{id}/publish` |

So 7F-1 is **an exposure and authorization phase, not a domain phase**. The six new MCP tools are
thin adapters over those exact services, and the response bodies are the REST DTOs themselves
(`WorkoutDraftResponse`, `DraftPublishPreviewResponse`, …) so the two surfaces cannot drift apart.
No lifecycle rule is re-implemented in the adapter, and `TrainMcpToolsArchitectureTest` fails the
build if the adapter ever grows a route to a renderer, a publisher, a repository or the coach that
would let it bypass one.

## 2. The six tools

`com.runningai.integration.mcp.train.TrainMcpTools`, registered only when
`running-ai.mcp.train-enabled=true` (`RUNNINGAI_MCP_TRAIN_ENABLED`, default **false**).

```
create_workout_draft → get_workout_draft → (revise_workout_draft)* →
approve_workout_draft → preview_draft_publish → publish_approved_draft
```

Annotations are honest, because that is what makes an MCP client ask its user before acting:
`get_workout_draft` and `preview_draft_publish` are `readOnlyHint: true`; the other four are not;
`publish_approved_draft` alone is `openWorldHint: true`; nothing is `destructiveHint`.

Errors use **the same codes the REST API returns for the same failure** —
`WORKOUT_DRAFT_APPROVED_IMMUTABLE`, `WORKOUT_SAFETY_INJURY_ACTIVE`, `DRAFT_PUBLISHING_DISABLED`,
`UNPUBLISHABLE_DRAFT`, `INTERVALS_TIMEOUT`, … — so a failure reads identically whichever surface
produced it. Every answer, success or failure, carries a `correlationId` that is also on every log
line the call wrote.

### The coach is never faked

`create_workout_draft` and `revise_workout_draft` run the local AI coach CLI on the RunningAI host.
When it is missing, not logged in, slow, off-contract or produces something that fails hard-safety
validation, the tool **fails** with `AI_COACH_<reason>` and an operator-readable cause. It never
substitutes a plausible session. There is no pre-flight "is the CLI there?" probe: the existing
`AiCoachException.PROVIDER_UNAVAILABLE` already answers that question by actually trying, which a
probe could only answer less reliably and one race earlier.

## 3. An MCP call is not the athlete's approval

This is the requirement that shaped the design. A naive `approve(draftId)` tool makes the tool call
itself the approval, which would let a model approve a workout because it liked its own suggestion.

Three independent mechanisms, and it is worth being precise about what each does and does not prove:

1. **Client confirmation** — `readOnlyHint: false` is what makes an MCP client prompt its user
   before calling. This is the only mechanism that involves a human, and it belongs to the client,
   not to us.
2. **Explicit intent** — `confirmedByAthlete: true` is a *required* argument on both committing
   tools. Omitting it is a schema violation; passing `false` is a refusal. An approval can never
   happen as an incidental side effect of some other action.
3. **Proof of having read** — `approve_workout_draft` requires `acknowledgedVersion` (the draft
   version actually shown) and `publish_approved_draft` requires `acknowledgedApprovalId`. Both are
   checked against the stored value first, with a read-only call, before the committing service is
   entered. A caller that never displayed the draft cannot supply them, and a draft revised since
   the athlete saw it cannot be approved by accident.

Together these make an accidental, blind or implicit approval impossible. They do **not** by
themselves prove a human agreed — only (1) can do that — and the code says so rather than implying
more assurance than it has.

## 4. Authorization: three scopes, server-side policy

The gateway (`tools/mcp-gateway/server.js`) had one required scope and one allowlist. It now also
carries a **hard-coded** per-tool scope policy — which scope a tool demands is a security property
of that tool, so no environment variable can downgrade a write tool to the read scope:

| Scope | Tools |
|---|---|
| `runningai:read` (base) | the seven 7C-2 read tools; also `initialize` / `ping` / `tools/list` |
| `runningai:train` | `create_workout_draft`, `get_workout_draft`, `revise_workout_draft`, `approve_workout_draft`, `preview_draft_publish` |
| `runningai:publish` | `publish_approved_draft` |

- **Scopes are not hierarchical.** Holding `runningai:publish` does not grant `runningai:train`, and
  no amount of `runningai:read` reaches either. "Can design and approve training" and "can send it
  to my calendar" are separately grantable and separately revocable.
- `tools/list` is filtered to the allowlist **intersected with what the token's scopes permit**, so
  a read-only connection is not even shown a tool it could not call.
- A scope failure is an HTTP 403 OAuth challenge naming the missing scope, raised **before** the
  backend is touched — an unauthorized request produces zero backend traffic, which the test suite
  asserts by hit counter.
- `publish_workout` (the legacy 6C deterministic tool) remains permanently banned: the gateway
  refuses to start if it appears on the allowlist, and that is unchanged.
- The default allowlist is still exactly the seven read tools, so a gateway started with no new
  configuration behaves precisely as it did before this phase.

## 5. Why nothing can publish today

Four independent gates, any one of which stops a real publish:

1. `RUNNINGAI_MCP_TRAIN_ENABLED=false` — the six tools are not registered at all. Not hidden:
   absent, so a forced `tools/call` fails as an unknown tool.
2. The gateway's allowlist does not contain them, and its scope policy would still demand
   `runningai:train` / `runningai:publish`.
3. **The Cloudflare worker cannot issue those scopes.** `tools/mcp-worker/src/index.js` filters the
   requested scopes against `SCOPES_SUPPORTED = ['runningai:read', 'offline_access']`, so a token
   carrying `runningai:train` or `runningai:publish` cannot currently exist. This phase deliberately
   left the worker untouched: it is the one change that would make write-scoped tokens possible, and
   it belongs with the 7F-2 approval, not before it.
4. `RUNNING_AI_DRAFT_PUBLISHING_ENABLED=false` — `ApprovedWorkoutDraftPublishService.publish`
   refuses regardless of how it was reached, and `PublishingModeGuard` keeps that flag mutually
   exclusive with legacy workout publishing.

## 6. Phase 7F-2 (real publish) — prerequisites

In order, each needing its own approval:

1. **Validate the draft→Intervals path once, by hand, against the real account.** This path has
   never actually published (Phase 6G shipped with real external publish `NOT_RUN`). Do it over
   REST on a dev instance pointed at the real Intervals key, with a date the athlete does not mind
   being written, before any of it is reachable from ChatGPT.
2. Add `runningai:train` and `runningai:publish` to the worker's `SCOPES_SUPPORTED` and deploy it.
3. Re-authorize the ChatGPT connector: a connection holds the scopes granted when it was created,
   so an existing read-only connection will not silently gain the new ones — it must be re-consented.
4. Set `RUNNING_AI_ALLOWED_TOOLS` on the production gateway to include the lifecycle tools, and
   restart it.
5. Turn on `RUNNINGAI_MCP_TRAIN_ENABLED`, then `RUNNING_AI_DRAFT_PUBLISHING_ENABLED` (legacy
   workout publishing must already be false, which it is), and restart Spring from the released jar.
6. First real run: create → read → approve → **preview** → publish, checking the Intervals calendar
   by hand between the preview and the publish.

## 7. Known limitations

- The coach runs a local CLI (`RUNNING_AI_COACH_CLAUDE_COMMAND`), so `create_workout_draft` and
  `revise_workout_draft` only work on the host that has it installed and logged in.
- A publish whose outcome is genuinely unknown (timeout, dropped connection) is **not** retried by
  anything here. The tool says so, and the correct move is `preview_draft_publish` and reading its
  `publication` field — `IntervalsException.isOutcomeUnknown()` exists precisely because re-sending
  is the wrong answer.
- The base scope for `tools/list` is still `runningai:read`, so a hypothetical train-only token
  could not enumerate tools. That is intentional for now; every real connection will hold read.
