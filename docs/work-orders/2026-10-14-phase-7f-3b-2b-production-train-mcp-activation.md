# Phase 7F-3B-2B — Production Train MCP Activation

Date: 2026-10-14 (KST)
Branch: `main-3` (canonical `main` fast-forwarded to the same commit)
Release commit: `2385b26` (worker only; the Spring jar was **not** rebuilt)

## Goal

Make the five training-lifecycle MCP tools reachable from ChatGPT against **production**, while
`publish_approved_draft` and the legacy `publish_workout` stay unreachable and no external
Intervals.icu write becomes possible.

## Result

Server-side activation is **complete and verified**. The end-to-end ChatGPT leg is **not** verified
and cannot be from this machine: the Cloudflare Worker validates its own opaque KV-backed access
tokens, so no locally minted request can exercise the public path. The user must re-authorize the
ChatGPT connector; until then the overall phase is **PARTIAL**, not PASS.

## STEP 1 — Baseline (nothing changed)

| Fact | Value |
| --- | --- |
| Canonical `main` / `main-3` | both `e76dc6b` |
| Prod Spring | PID 3576, `127.0.0.1:18080`, jar SHA-256 `a5a0d8a2…` = `e76dc6b`, migrations 26 |
| Prod gateway | PID 48448, `127.0.0.1:17910`, task `RunningAI-McpGateway` Running |
| Gateway metadata | `scopes_supported: ["runningai:read"]`, `resource_name: "RunningAI MCP (read-only)"` |
| Spring MCP tools | exactly the 7 read tools |
| Prod DB | activity 48, activity_analysis 48, injury_report **0**, workout_draft 19, approval 3, publication 3, Flyway V26 |
| Publishing switches | `RUNNING_AI_DRAFT_PUBLISHING_ENABLED` / `WORKOUT_PUBLISHING_ENABLED` / `..._SCHEDULER_ENABLED` / `RUNNINGAI_MCP_ENABLED` all false |
| Intervals 2026-10-16 | exactly 1 event, id 144442046, marker `runningai:workout:v1:0:2026-10-16`, `moving_time` 1800 |
| Relay / tunnel | external-relay PID 45576 (from `C:\running-ai-github`), cloudflared PID 8400 → `127.0.0.1:17910` |
| Worker | version `50d3ca08-b437-495d-bb0d-abbe93a16c12`, `scopes_supported` `[runningai:read, offline_access]` |

Rollback assets confirmed present: `.runtime\backup\running-ai-server-PRE-7F3B2A-799e3b5.jar`,
`.runtime\released\running-ai-server-20261014-105352-760a2ee7.jar`, `env.PRE-7F3B2A-20261014`,
`mcp-gateway.PRE-7F3B2A.cmd`, `RunningAI-McpGateway.PRE-7F3B2A.xml`.
New for this phase: `env.PRE-7F3B2B-20261014`, `mcp-gateway.PRE-7F3B2B.cmd`, Worker version `50d3ca08`.

## STEP 2 — Production injury report

One self-report recorded through `POST /api/v1/athlete/injury-reports` (no SQL):

```
body_site=knee (lateral)  side=UNSPECIFIED  status=RESOLVED
pain_present=false  pain_on_walking=false  swelling=false
pain_intensity=null  pain_on_stairs=null  range_of_motion_limited=null   (unreported, not "normal")
reported_at = 2026-10-13T22:54:24.101553Z  (= 2026-10-14 07:54:24 KST, when the athlete actually
                                             gave the all-clear)
recorded_at = 2026-10-14T02:40:5x Z         (server time, un-backdatable)
```

Gate verdict: `nonRestTrainingAllowed=true`, `INJURY_RESOLVED_CONFIRMED`.
Freshness is computed from `recorded_at`, never `reported_at`, so the honest earlier report instant
costs nothing and extends nothing. **The permission expires 2026-10-16 ~11:41 KST** (48 h), after
which the gate answers `INJURY_STATE_STALE` and a fresh report is required.

No development row was copied. The production ledger therefore has no prior `ACTIVE` record, and
`InjuryHistoryContext.daysSinceLatestActiveReport` is consequently **null** — a real limitation of
what the coach is told, deliberately not papered over with an invented historical row.

### Defect introduced and left in place

**Two identical rows (ids 1 and 2) were inserted, 0.5 s apart, instead of one.** The POST command
carried a `|| <same curl>` fallback for pretty-printing; the printer failed, so the shell ran the
request a second time. The ledger is append-only and nothing was deleted without instruction.

Impact assessed, not assumed:
- Gate verdict unaffected — the policy reads only the latest row, which is `RESOLVED` and fresh.
- `InjuryHistoryContext` reports `totalReportCount=2` with the duplicate listed under
  `previousReports`, so the coach sees the same self-report twice. Cosmetic noise, but a safety
  ledger implying two separate reports is factually wrong.
- Open decision for the athlete: delete row id 1, or leave both and annotate.

Verified live for the first time (previously unreachable with 0 reports):
`notesPresent=true` with `notesExcerpt=null`, i.e. `RUNNING_AI_COACH_INJURY_HISTORY_MAX_NOTES_CHARS=0`
really does keep athlete free text out of the coach prompt while the structured fields still flow.

## STEP 3 — Spring train MCP

`.env` patched byte-exactly (single token, line endings preserved; verified the backup differs by
exactly that one token): `RUNNINGAI_MCP_TRAIN_ENABLED=false` → `true`. Nothing else changed.

`RunningAI-Watchdog` disabled for the restart, re-enabled afterwards. Restart via the standard
`stop-running-ai.ps1` / `start-running-ai.ps1` (no `-Build`): the **same** jar `a5a0d8a2…` from
`main@e76dc6b` was re-released, migrations up to V26. New Spring PID 20664.

Verified after restart:
- `tools/list` = **13** tools: the 7 read tools unchanged + the 6 train tools. No tool was removed.
- `publish_workout` is still **not registered** (`RUNNINGAI_MCP_ENABLED=false`).
- `POST /api/v1/workout-publish` → 409 `WORKOUT_PUBLISHING_DISABLED`.
- `POST /api/v1/workout-drafts/999999/publish` → 409 `DRAFT_PUBLISHING_DISABLED` (the switch is
  checked before any draft load, so this probe cannot publish anything).
- `get_athlete_profile` still answers; DB row counts unchanged apart from `injury_report`.

Confirmed from `McpTrainToolConfig` before touching anything: draft creation does **not** require the
publishing master switch — only `publish_approved_draft` does. No publishing switch was turned on.

## STEP 4 — Gateway allowlist

The gateway already had the hard-coded per-tool scope map; it only ever defaulted to the read-7
allowlist because `RUNNING_AI_ALLOWED_TOOLS` was unset. One line added to
`C:\running-ai-github\.runtime\mcp-gateway.cmd` (backup: `mcp-gateway.PRE-7F3B2B.cmd`) listing the
7 read + 5 train tools. `publish_approved_draft` is **absent**, so it is unreachable regardless of
scope; `publish_workout` remains refused at startup by construction.

Pre-flight on spare port 17911, running the **real** runtime binary with the **exact** production
allowlist against a stub issuer and a hit-counting stub backend — **28/28**:

- no token → 401; unknown signer → 401; wrong subject → 403; backend untouched.
- read-only token: `tools/list` = exactly the 7 read tools; all 5 train tools → 403
  `insufficient_scope` **with zero backend traffic**; a read tool still works.
- read+train token: `tools/list` = exactly 12; `publish_approved_draft` and `publish_workout`
  neither listed nor callable; `create_workout_draft` authorized.
- a token additionally claiming `runningai:publish` changes nothing.
- metadata: `scopes_supported` = `[runningai:read, runningai:train]`, no publish scope.

The existing suite also passed unchanged against the runtime copy: **46/46**.

Operational gotcha found: `Stop-ScheduledTask` left the node child alive, so the restarted task
could not bind 17910 and exited (task State `Ready`, old process still serving the old config). The
old PID must be stopped explicitly. After a clean restart (PID 46984, State Running) the startup
line printed the intended policy, and production metadata now reads
`scopes_supported: ["runningai:read","runningai:train"]`, `resource_name: "RunningAI MCP"`, with
401 for no/forged token and 404 for any non-`/mcp` path. The live cloudflared tunnel was confirmed
to serve that same new metadata, which also proves `ORIGIN_MCP_URL` in `wrangler.jsonc` is current.

## STEP 5 — OAuth Worker (`2385b26`, version `2438d9a0`)

A code change was genuinely required, and the reason was not obvious from the plan:
`mintHopToken` hard-wired the hop token's scope to the base read scope, so a train-scoped user token
would have reached the gateway as read-only and every train tool would have been refused. Enabling
the gateway tools alone would have silently achieved nothing.

Changes:
- `runningai:train` added to `SCOPES_SUPPORTED` and to `requiredScopes`. In this library
  `requiredScopes` is advertisement and challenge content only — it is never compared against a
  granted token — so widening it cannot invalidate the existing read-only grant. Verified in the
  library source before relying on it.
- The hop token now carries exactly the forwardable scopes the access token was verified to hold.
  `offline_access` is a refresh grant, not a resource permission, and never travels onward.
- `resource_name` corrected to `RunningAI MCP` (advertising "read-only" would now be false).

`runningai:publish` is **not issuable**. The gateway gives that scope to `publish_approved_draft`
alone, so while the Worker cannot mint it, no token reaching the gateway can authorize an external
write — independently of the allowlist.

Tests: chain **34/34** (6 new: read-only forwards read only; read+train forwards both; a
`runningai:publish` claim is **stripped**; `offline_access` and unknown scopes never forwarded;
fallback to the base scope when unconfigured). OAuth e2e **49/49** (4 new: asking for read grants
read only; train only when explicitly requested; asking for publish does not grant it; unknown scope
dropped). Deployed from the canonical checkout.

Live after deploy: protected-resource `["runningai:read","runningai:train"]`; AS metadata
`runningai:read, runningai:train, offline_access`; internal issuer/JWKS unchanged; `/mcp` without a
token → 401. GitHub sign-in, the single-user allowlist, issuer, audience, redirect policy, Worker
URL and token validation are all untouched.

## STEP 6/7 — ChatGPT (NOT verified — user action required)

A Worker that *can* issue `runningai:train` does not retroactively widen an existing grant: the
stored grant still carries `['runningai:read','offline_access']`. The user must remove and re-add
the RunningAI connector in ChatGPT and approve the new scope. Read must keep working before and
after.

No draft was created. `create_workout_draft` writes to the production DB and runs the real Claude
CLI, so it waits on an explicit date and an explicit go-ahead.

## STEP 8 — System state

Zero external writes. Intervals `2026-10-10..10-31` still holds exactly **one** event — 144442046,
unchanged. Prod DB changed by exactly 2 rows, both in `injury_report`; every other table identical
to baseline. Flyway still V26, no migration, no schema change. Spring, gateway, relay and tunnel all
healthy; scheduled-task states identical to baseline.

## Rollback

| Layer | Action |
| --- | --- |
| Spring | restore `.runtime\backup\env.PRE-7F3B2B-20261014`, restart (disable the watchdog first) |
| Gateway | restore `.runtime\backup\mcp-gateway.PRE-7F3B2B.cmd`, stop the node PID explicitly, restart the task |
| Worker | `wrangler rollback` / `wrangler versions deploy` to `50d3ca08-b437-495d-bb0d-abbe93a16c12` |
| Injury ledger | append-only; nothing deleted |

## Not done, deliberately

`publish_approved_draft`, the `runningai:publish` scope, any draft approval, any Intervals write, and
Phase 7F-3B-2C. No Flyway migration, no Spring rebuild, no origin push, no watchface/relay change.
