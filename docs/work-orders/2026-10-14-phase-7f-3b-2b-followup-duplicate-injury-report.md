# Phase 7F-3B-2B follow-up — duplicate injury report: cause, write-path audit

Date: 2026-10-14 (KST)
Branch: `main-3`
Baseline commit: `d86d966` (unchanged — this follow-up adds documentation only)
Parent: [`2026-10-14-phase-7f-3b-2b-production-train-mcp-activation.md`](2026-10-14-phase-7f-3b-2b-production-train-mcp-activation.md)

## Scope

Five things only: record why one POST executed twice, audit every production write path for the
same pattern, fix it where this phase's own scripts carry it, leave the duplicate rows untouched,
and leave the data correction as a separate approval item.

Explicitly **not** done (and nothing in this document changes them): no `publish_approved_draft`,
no `runningai:publish` scope, no Intervals write, no production DB change, no OAuth scope change,
no Spring/gateway/worker restart, no `origin` push.

## STEP 0 — ChatGPT read path, verified live after the 2B changes

The user exercised the real OAuth-protected read tools from ChatGPT against production:

| Check | Result |
| --- | --- |
| `get_training_context` against production | works |
| Injury gate as the coach sees it | `RESOLVED` |
| `notesExcerpt` in the coach-facing payload | not exposed |

This closes a question 2B could only answer from library source rather than from behaviour.
2B widened the Worker's `SCOPES_SUPPORTED` / `requiredScopes` and changed `resource_name`, and the
prediction was that this **cannot** invalidate the already-stored read-only grant, because
`workers-oauth-provider` treats `requiredScopes` as advertisement and challenge content and never
compares it against a granted token. The read tools still answering over the public path with the
pre-existing grant is that prediction confirmed in production.

It also re-confirms, now through the public path rather than a loopback call, that
`RUNNING_AI_COACH_INJURY_HISTORY_MAX_NOTES_CHARS=0` keeps athlete free text out of the coach prompt
while the structured injury fields still flow.

The **train** tools remain **UNVERIFIED** end-to-end: the stored ChatGPT grant still carries
`['runningai:read','offline_access']`, so the user must remove and re-add the connector and approve
`runningai:train`. That is the only outstanding item from 2B itself.

## STEP 1 — Why the same POST ran twice

Two independent facts combined. Neither alone would have produced a duplicate.

**1. The retry unit was the whole command, not the failing part.** The report was submitted as an
ad-hoc interactive shell one-liner shaped like:

```
<curl POST /api/v1/athlete/injury-reports ...> | <pretty-printer>  ||  <the same curl POST ...>
```

In POSIX shell, `||` tests the exit status of the **entire pipeline**, and a pipeline's exit status
is that of its **last** command — here the pretty-printer, not `curl`. The printer failed, the
pipeline therefore reported failure, and the shell ran the right-hand side. That right-hand side was
a full re-issue of the request, not a re-render of a response already in hand. The first POST had
already succeeded and committed row id 1; the fallback committed row id 2 about 0.5 s later.

The operator's intent was "if the output is unreadable, print it again plainly". What was actually
written was "if the output is unreadable, perform the write again". For a GET those two readings are
indistinguishable, which is why the habit survives until it meets a write.

**2. The endpoint is deliberately append-only and has no idempotency key.** `InjuryStateService`
(`server/src/main/kotlin/com/runningai/injury/InjuryStateService.kt:38`) never edits a row, only
appends one, and `POST /api/v1/athlete/injury-reports` carries no request id, no dedupe window and
no "same payload within N seconds" guard. **This is correct and is not the defect.** A safety ledger
that silently collapsed two submissions into one could hide a genuine second report; a status change
is always a new row. The server did exactly what it is specified to do with the two requests it
received.

So the fault is entirely in how the request was issued, and entirely outside the server.

### Impact, assessed rather than assumed

- **Gate verdict unaffected.** `WorkoutSafetyPolicy` reads only the latest row, which is `RESOLVED`
  and fresh (`INJURY_RESOLVED_CONFIRMED`). The duplicate cannot change a permission decision.
- **Coach-facing context is factually wrong in one respect.** `InjuryHistoryContext` reports
  `totalReportCount=2` with the duplicate under `previousReports`, so the coach is shown the same
  self-report twice and could read it as two separate reports. Cosmetic in effect, incorrect in
  substance.
- **`daysSinceLatestActiveReport` is `null`** — unrelated to the duplicate. No `ACTIVE` row was ever
  written to production (dev rows were deliberately not copied). A real limitation of what the coach
  is told, not papered over with an invented historical row.
- Permission freshness is computed from `recorded_at`, never `reported_at`. The duplicate did not
  extend it: both rows carry effectively the same `recorded_at`, so the window still expires
  **2026-10-16 ~11:41 KST**.

## STEP 2 — Write-path audit

Every production write path was checked for the specific pattern: **a failure in output handling,
formatting or response parsing causing the request itself to be issued again.**

| Artifact | Verdict | Evidence |
| --- | --- | --- |
| `scripts/windows/RunningAI.Common.ps1` → `Invoke-RunningAiJsonRequest` | **CLEAN** | Exactly one `Invoke-WebRequest`. Body encoding happens before it; UTF-8 decode and `ConvertFrom-Json` happen after it. A parse failure propagates as a terminating error — there is no code path back to the request. |
| `scripts/windows/invoke-running-ai-api.ps1` (the generic write tool) | **CLEAN** | Two effective lines: build URI, call the helper once, return the object. No formatting step, so no formatting fallback. |
| `scripts/windows/RunningAI.CoachOperator.ps1` (draft create / revise / approve / publish, 5 POSTs) | **CLEAN** | Each write is a single `Invoke-RunningAiJsonRequest`. Rendering is done by separate functions on the returned object; no renderer is wrapped around a request. |
| `scripts/windows/publish-approved-draft-controlled.ps1` | **CLEAN** | Health check gates the run and reports "retry" as human advice; no automatic re-issue. |
| `scripts/dev/preflight-intervals-date.ps1` (the only ops script added in Phase 7F) | **CLEAN — read-only** | GET only, against Intervals; prints `no write was performed by this script`. Cannot duplicate anything. |
| `tools/mcp-gateway/server.js` → `proxy()` — the live production MCP write path | **CLEAN, and explicitly so** | One `fetch` to the backend. The `tools/list` filter's `JSON.parse` is wrapped in `catch (e) { /* fall through: pass the original text */ }` — a parse failure forwards the bytes already received and never re-fetches. This is the exact correct shape of what the shell one-liner got wrong. |
| `tools/mcp-worker/src/mcp-proxy.js` | **CLEAN** | One `fetchImpl` to the origin; the upstream body is streamed straight through without parsing. Transport failure returns 502 — it does not re-send. |
| `.runtime\mcp-gateway.cmd` (production, inspected read-only) | **CLEAN** | Sets env and launches node once. Also re-confirms the intended tool policy is still in place: read 7 + train 5, `publish_approved_draft` absent. |
| Server-side write clients (`IntervalsWorkoutPublisher`, `HttpIntervalsWorkoutClient`, `HttpIntervalsReadClient`, Garmin detail source, import services, schedulers) | **CLEAN by design, documented** | "Exactly one request per call, no retry" throughout. `createWithoutBlindRetry` goes further: a POST with an unknown outcome is resolved by **reading back the marker**, never by re-POSTing. |
| Whole repository, grep for a `curl`-with-`||`-fallback shape | **No hit** | Single match is `deploy/linux/scripts/deploy-app.sh:98` → `have curl || die` — a precondition check, not a retry. |
| Runbook / work-order documents containing copy-pasteable `curl` | **No hit** | No committed document hands an operator a write command with an `||` fallback, so the pattern cannot propagate by copy-paste. |

**Result: the pattern exists in no committed artifact.** It existed only in an interactive shell
command that was never part of any script, which is precisely why the repository's test suites could
not have caught it.

## STEP 3 — No code change, and why

The instruction's fix step is conditional on the pattern being present in a script written during
this work. It is not: the only ops script this phase produced is `preflight-intervals-date.ps1`, and
it is read-only. Writing a change anyway would mean modifying correct code to look responsive.

No code was changed, therefore no regression run was warranted and none is claimed. The verification
already on hand from 2B stands unchanged: server 1442/1442, gateway 46/46, the 28/28 allowlist
pre-flight on port 17911, worker chain 34/34, oauth e2e 49/49.

The one durable countermeasure is a rule rather than a patch, recorded here because it binds future
operators including future sessions:

> **A production write is issued exactly once per intent, as its own command. Output handling —
> formatting, parsing, pretty-printing, piping — never shares a failure path with the request. If a
> response is unreadable, re-read the state with a GET; never re-issue the write to get a better
> printout.**

This is the same rule the Java publisher already follows (`createWithoutBlindRetry` reads back the
marker instead of re-POSTing) and the same rule `proxy()` follows for `tools/list`. It was simply
never written down for operators typing commands by hand.

## STEP 4 — Open items, held for separate approval

| Item | Status |
| --- | --- |
| Delete `injury_report` id 1, or keep both rows and annotate | **Athlete's decision. Not actioned.** The ledger is append-only; nothing was deleted or edited. |
| ChatGPT connector re-authorization to grant `runningai:train` | Pending user action; train tools stay UNVERIFIED until then |
| Injury permission expiry **2026-10-16 ~11:41 KST** | A fresh report is required after it, or the gate answers `INJURY_STATE_STALE` |
| `publish_approved_draft`, `runningai:publish`, Phase 7F-3B-2C | Not started, deliberately |

Should the duplicate ever be corrected, the honest options are to delete row id 1 (removing a row
that records a submission that genuinely happened) or to leave both. Either is a data-correction
decision with its own approval, outside this follow-up.

## STEP 5 — System state

Unchanged from the end of 7F-3B-2B. No production write of any kind was performed by this
follow-up: no DB row, no Intervals event, no `.env` edit, no gateway or worker deploy, no restart.
Inspection of `.runtime\mcp-gateway.cmd` was read-only. Production still exposes read 7 + train 5
with `publish_approved_draft` unreachable and `runningai:publish` un-issuable.
