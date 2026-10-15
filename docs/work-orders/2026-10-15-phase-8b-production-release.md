# Phase 8B — Production release execution

**Date** 2026-10-15 (12:12–12:50 KST) · **Release commit** `0fce192` · **Flyway V28 → V30**
**Deployed, verified, pushed.** Zero Garmin writes, zero Intervals.icu writes, zero data loss.

This is the execution record for the release that
`2026-10-15-integrated-release-preparation.md` (runbook) and
`2026-10-15-phase-8b-release-stabilization.md` (corrections) prepared.

---

## 1. Release identity

| Item | Value |
|---|---|
| Release commit | `0fce1925579ad07dadfd697258fd0b798cf74428` (`phase-8b-release-stabilization`, merged FF into `main`) |
| Core RC it supersedes | `940d3f6` — `0fce192` adds **no** `server/` or `tools/` code, only docs + `scripts/ops/v30-rollback-*.sql` |
| Production jar SHA-256 | `091971eb76178d1e5b79c48660925449c3bfe463fb0419195b69b624e12c9f2a` (30 migrations, highest V30, 102 dependency jars) |
| Previous jar (rollback) | `53d6ada77eca86694152a71da869b9f72191f5a95b804e26c1ce861a02e2fc9f` (`f1451f5`, maxMigration 28) |
| Flyway | V28 → **V30** (V29 `workout_draft_publish_attempt`, V30 `garmin_sync_attempt` lifecycle) |
| Connector / Relay | same commit; relay `config.json` moved to schema **1.1** (`spring_base_url`) by the fast-forward, no hand edit |
| Worker / Gateway | **not redeployed** — deployed version `0d75fa82` still points at the live tunnel |
| Deployed at | 2026-10-15 12:31:04 KST (release) / 12:31:28 KST (Spring up) |

**Integration was trivial and was verified, not assumed:** `940d3f6` is an ancestor of `0fce192`,
and `main` is an ancestor of both, so the release is linear with no merge, no duplicate change and
no conflict.

---

## 2. Git integration — two pushes, deliberately

`origin/main` did not contain the code production was running (35 commits behind the `f1451f5`
jar). The release was therefore split so that "what is on the remote" never claims more than
"what has run":

1. **Before deployment** — `origin/main` fast-forwarded `370ee8f → b1feda7`: only the already-live,
   production-validated system (7B…8A). No running system changed.
2. **After deployment and verification** — `origin/main` fast-forwarded `b1feda7 → 0fce192`: the 14
   Phase 8B commits, pushed only once production had been verified on them.

Both pushes were fast-forward. No force push, no rebase, no history rewrite.

Final state: `main` == `origin/main` == production checkout `HEAD` == the jar's recorded
`gitCommit` == the tested commit == `0fce192`.

### Secret / volatile-value audit (release diff)

| Check | Result |
|---|---|
| Sensitive paths added (`.env`, `*.pem/key/jwk`, dumps, jars, session files) | PASS none |
| Tunnel hostnames in code or config | PASS none — `wrangler.jsonc` carries `https://origin.invalid/mcp`; only placeholder forms in README |
| Worker Git integrity | PASS the deployed diagnostics are committed (`e2bd1e0`); production worktree clean before and after |

---

## 3. Tests at the release commit

| Suite | Result |
|---|---|
| Spring, H2 — `gradlew clean test` | **1596 / 1596**, 0 failures, 0 errors, 0 skipped, 159 classes |
| Garmin connector — `pytest` | **154 / 154** |
| External relay — `node --test` | **31 / 31** |
| MCP gateway — `npm test` | **46 / 46** |
| MCP Worker — `npm run test:all` | **49 / 49** |

### One flaky test, reported rather than hidden

The **first** full run failed `1596 tests completed, 1 failed`:
`InjuryStateServiceTest > a new report never overwrites the previous one`, expecting
`["second","first"]` and getting `["first","second"]`.

Root cause, confirmed: `InjuryStateService.record()` stamps `recordedAt = Instant.now(clock)`, and
`history()` orders by `recorded_at DESC` **only**. Two `record()` calls inside one test method can
land on the same clock tick, and the ledger then has no defined order. Re-run in isolation
**4 / 4 PASS**; the full suite re-run was **1596 / 1596 PASS** with no code change.

**Production impact today: none.** The two production rows differ by 0.5 s and are both `RESOLVED`,
so `current()` is unambiguous and the gate verdict would be identical either way — verified
directly against the production table.

**Residual risk, not fixed in this release:** two reports written inside one clock tick would order
arbitrarily, and if their statuses differed the safety gate could read the wrong one. That requires
a duplicate-submission bug to produce, and fixing it means changing code the whole rehearsal chain
was verified against. **Recommended follow-up:** tie-break both repository methods on `id DESC`
(`...OrderByRecordedAtDescIdDesc`) — a one-line, behaviour-preserving change that also makes the
test deterministic.

---

## 4. Deployment, in the order executed

| # | Step | Result |
|---|---|---|
| 0 | Baseline captured | V28, 48 activities (8/25/15), 31 tables, `garmin_sync_attempt` **0 rows**, checkpoint 2026-10-12T03:24:44Z, all services UP |
| 1 | `pg_dump -Fc` + **restore proved** into `restore_check` | PASS restored copy = V28, 48 activities, 31 tables, 75 206 samples, 239 laps, 19 drafts |
| 2 | Rehearsal on that restored copy (spare port 18099, black-hole connector) | PASS V28 → V30 applied, app started in 9.44 s, Hibernate validated, every endpoint 200 |
| 3 | Watchdog paused; checkout fast-forwarded to `0fce192`; jar built and verified | PASS 30 migrations, highest V30 |
| 4 | `stop` → `release-spring-jar.ps1` → `start` | PASS connector, Spring (V30), relay all UP in 25 s |
| 5 | Watchdog re-armed | see §7 — re-enabling alone was **not** enough |

The rollback jar was additionally copied to `.runtime\backup\running-ai-server-PRE-8B-f1451f5.jar`
(SHA-256 verified identical to the sidecar) on top of the release script's own
`.runtime\released\` backup.

---

## 5. Verification

### Spring

| Check | Result |
|---|---|
| Flyway head | **30** (V29 + V30 applied 12:31:23) |
| Startup log | **0 ERROR lines**; 30 migrations validated; 13 MCP tools registered |
| `/api/v1/health`, `/actuator/health` | 200 |
| `/api/v1/garmin/sync/status` | 200, now carries `automaticSync {allowed:true, reason:ALLOWED}` |
| `/api/v1/data-freshness` | 200, same latest dates as baseline |
| `/api/v1/today-workout` (new) | 200 `NO_PLAN` for today; for 2026-10-05 → `HAS_PLAN / APPROVED_DRAFT / EASY / 40 min / published:true / stale:true / athleteToday:2026-10-15` — the approval path **and** the stale guard proven on real data |
| `/api/v1/training-analysis-report` (new) | 200 |
| `/api/v1/workout-drafts/17/delivery-status` (new) | 200, `PUBLISHED_AND_VERIFIED`, `GARMIN_NOT_FOUND` (not a failure, by design), `deviceDelivery: UNKNOWN` |
| same with `checkGarmin=false` | 200, `GARMIN_NOT_CHECKED`, zero Garmin calls |
| `/api/v1/workout-drafts/17/publish-attempts` (new) | 200, `attemptCount: 0` |
| Wrong verb → **405 + `Allow: GET`** | PASS, logged WARN with no stack |
| Missing `@RequestParam` → **400** | PASS, logged WARN with no stack |
| Unknown path → **404** | PASS unchanged |
| R1 fix on real data | PASS `metricUnavailability` no longer claims `LAP_STRUCTURE`; `HEART_RATE_ZONES` now covers 27 imported activities |

### Safety gates

| Check | Result |
|---|---|
| Draft publish `POST /api/v1/workout-drafts/17/publish` | **409 `DRAFT_PUBLISHING_DISABLED`** |
| Legacy publish `POST /api/v1/workout-publish` | **409 `WORKOUT_PUBLISHING_DISABLED`** |
| Attempt ledger after both | **0 rows** — a disabled switch is correctly not an attempt |
| Injury gate `GET /api/v1/athlete/injury-state` | `INJURY_RESOLVED_CONFIRMED`, `nonRestTrainingAllowed: true`, reading row id 2 (the later `recorded_at`) |

The publish test was safe in both directions by construction: `publish()` checks the switch as its
very first statement, and draft 17 is already published, so the `ALREADY_PUBLISHED` short-circuit
(`externalWriteAttempted = false`) precedes the safety gate and any publisher call.

### Garmin connector — `/scheduled-workouts` is now LIVE_VERIFIED

First live call ever made to this route. **HTTP 200 with a real calendar.** Every documented
assumption held:

| Assumption | Live result |
|---|---|
| `calendarItems` is the payload key | PASS present, 17 items |
| Month is 1-based at the connector, library does `month - 1` | PASS asked `month=10`, response echoes `"month": 9` |
| `itemType == "workout"` is the right filter | PASS — and necessary: the month also returned `event` ×2, `fbtAdaptiveWorkout` ×6 (Garmin Coach), `nap` ×2, `activity` ×6 |
| `title`, `sportTypeKey`, `workoutId` read as nullable | PASS all present; non-null on the one `workout` item |
| No write route exists | PASS 154/154 incl. the AST test |

The single `itemType == "workout"` item is **"RunningAI workout", 2026-10-16,
`sportTypeKey: running`, `workoutId: 1729422156`** — i.e. a RunningAI-published workout that
actually reached the Garmin calendar through Intervals.icu's own integration. Non-ASCII titles
round-trip correctly as UTF-8.

Status upgrade: **STATIC_SOURCE_CONFIRMED → LIVE_VERIFIED.** `deviceDelivery` remains permanently
`UNKNOWN`, which is a stated limit, not a gap.

### External relay

The runbook's spare-port side-by-side was **not** run as written: `start-running-ai.ps1` restarts
the relay as part of the runtime start, so the candidate went live together with Spring. The
equivalent evidence was taken as a before/after on the same port.

| Check | Result |
|---|---|
| Pre-deploy (old code) | `{"date":"2026-10-15","status":"NO_PLAN","label":null}` |
| Post-deploy (new code) | **identical** |
| Wire contract `{date,status,label}` | unchanged |
| Auth | unauthenticated → 401 |
| Config | schema 1.1, `spring_base_url: http://127.0.0.1:18080` |
| RunningAI leg reached | PASS — **no** `RUNNING_AI_UNAVAILABLE` warning in the log; `NO_PLAN` is not a decision and falls through to Intervals.icu by design |

`HAS_PLAN` / `REST` label rendering and the ≤14-glyph pixel-font constraint are covered by the
31/31 relay tests and were not re-exercised live, because no approval exists for today and creating
one is outside this release's scope. Rollback for the relay alone remains one key:
`spring_base_url: ""`.

### MCP / Worker

| Check | Result |
|---|---|
| Worker redeploy | **not performed** — deployed `ORIGIN_MCP_URL` still matches the live tunnel (`cloudflared` PID 18220 never restarted) |
| Worker `/health`, AS metadata, JWKS, PRM | 200; PRM scopes `runningai:read`, `runningai:train` |
| Unauthenticated `POST /mcp` (Worker) | 401 + correct `WWW-Authenticate` challenge |
| Tunnel → gateway `/health` | 200 |
| Gateway `/health`; unauthenticated `POST /mcp` | 200; **401**, backend untouched |
| Gateway allowlist | **12** tools; `publish_approved_draft` **not** listed; `publish_workout` permanently un-allowlistable |
| Spring `/mcp tools/list` after restart | **13** tools |
| Read tool `get_athlete_profile` (loopback) | PASS real profile (LTHR 176 bpm, threshold pace 290 s/km) |
| Train tool `get_workout_draft` (loopback, read) | PASS draft 17 returned with `correlationId` |
| Error contract | PASS `get_fitness_trend` without args → `INVALID_ARGUMENT`, `isError: true` |

### The authenticated OAuth read leg — PASS (closed 2026-10-15 13:15 KST)

Initially PENDING: the gateway validates bearer tokens against the Worker's JWKS and the signing
key exists only as a Worker secret, so no local process can mint an acceptable token. Closed by a
real ChatGPT sign-in and one `get_athlete_profile` call, cross-checked in two independent logs:

| Evidence | Line |
|---|---|
| Gateway | `2026-10-15T04:15:56.606Z INFO tools/call get_athlete_profile authorized (scope runningai:read)` |
| Spring | `2026-10-15T13:15:56.627+09:00 INFO … MCP read tool get_athlete_profile: ok` (PID 29204) |

Same request, **21 ms apart** (04:15:56Z = 13:15:56 KST), on the **post-deploy jar** — the deploy
was 12:31 KST, so this exercised `0fce192`, not the old one. The returned values
(LTHR 176 bpm, threshold pace 290 s/km, `initialized: true`) are identical to the loopback call,
so the whole chain returned the same real data. No DB write, no external publish.

**The Worker-side log could not be cross-checked from this machine**, and this is a tooling limit,
not a doubt: `observability` is enabled in `wrangler.jsonc`, but `wrangler tail` is a *live*
session only and wrangler 4.x has no historical-log query, so a request that already completed is
unreachable without the dashboard or the Observability API. It is also not needed — the gateway
accepted an **RS256 token validated against the Worker's JWKS**, and the matching private key
exists only as a Worker secret, so the token provably originated at the Worker.

> **Scope note — this call verified READ only.** It carried `runningai:read` and nothing else.
> **It is not evidence for the train scope.** The last authenticated train call through the gateway
> was `2026-10-15T03:06:55Z` (12:06:55 KST), which **predates this deploy**; post-deploy the train
> tool was exercised only over loopback, which bypasses OAuth entirely. The authenticated train leg
> therefore remains unverified on `0fce192`, and the known 7F-3B-3 state (ChatGPT's grant is
> read-only) is unchanged by this release.

---

## 6. Post-deploy data integrity — nothing moved

| Table | Baseline | After |
|---|---|---|
| `activity` | 48 (8 GARMIN / 25 INTERVALS_ICU / 15 STRAVA) | **48, identical split** |
| `activity_sample` | 75 206 | 75 206 |
| `activity_lap` | 239 | 239 |
| `activity_analysis` | 48 | 48 |
| `workout_draft` / `_approval` / `_publication` | 19 / 3 / 3 | 19 / 3 / 3 |
| `injury_report` | 2 | 2 |
| `garmin_sync_attempt` | 0 | **0** |
| `workout_draft_publish_attempt` (V29, new) | — | **0** |
| tables in `public` | 31 | **32** |
| `garmin_sync_state` | high-water 2026-10-10T22:39:40Z, `updated_at` 2026-10-12T03:24:44Z | **byte-identical** |

**Rollback is still free.** `SELECT count(*) FROM garmin_sync_attempt WHERE outcome IN
('STARTED','INTERRUPTED')` returns **0**, so the V28 jar can be restored with no data operation at
all. That stays true until the first Garmin sync runs under V30 — which is why no manual sync and
no `POST /api/v1/coach/data-refresh` were performed, and why neither should be until §8 item 1 is
approved.

---

## 7. One operational finding — the watchdog does not resume by itself

To stop a 5-minute watchdog tick from restarting Spring between `stop` and `release`, the
`RunningAI-Watchdog` scheduled task was disabled for the deploy window and re-enabled afterwards.

**Neither `Enable-ScheduledTask`, nor `Start-ScheduledTask`, nor re-running the installer brought
it back.** The task's only trigger is `MSFT_TaskLogonTrigger` with `Delay = PT5M` and
`Repetition.Interval = PT5M`. Disabling the task destroys the *running repetition instance*, and
an AtLogOn trigger cannot re-arm until the **next logon** — so:

| Attempt | Result |
|---|---|
| `Enable-ScheduledTask` (State → Ready) | no tick for ~20 min |
| `Start-ScheduledTask` | one manual run at 12:46:38, then nothing |
| `install-running-ai-watchdog-task.ps1` (re-register with `-Force`) | definition correct, still no tick for ~8 min |

`NextRunTime` was **empty** throughout — which is the actual tell, and is also why the state looked
fine: `State = Ready` and `LastTaskResult = 0` are both true of a task that will never run again.

**Remedy applied.** A supplementary `Once` trigger with a start boundary in the past and
`RepetitionInterval = 5 minutes` was added alongside the canonical logon trigger. `NextRunTime`
populated immediately and **two unattended ticks were observed five minutes apart**
(`04:04:14Z`, `04:09:14Z`, both `overall=UP`, `lastAction=NONE`).

**This leaves the live task with two triggers, which is not the canonical shape.** After the next
reboot (where the logon trigger arms normally), re-run
`scripts\windows\install-running-ai-watchdog-task.ps1` to return to the single-trigger definition.
Nothing in the repository was changed.

**How to apply:** never judge a RunningAI scheduled task by `State`. After any
`Disable-ScheduledTask` on a repetition-based task, check that `NextRunTime` is non-empty and
confirm `watchdog-status.json` advances **twice** unattended. Better still: do not disable it —
`release-spring-jar.ps1`, `start-` and `stop-running-ai.ps1` already share a named runtime lock
that a watchdog restart cannot interleave with, so pausing the watchdog was a belt-and-braces
measure that cost more than it bought.

This compounds the Phase 8B stabilization finding that the MCP gateway and the tunnel are in
nobody's supervision list. Both belong in `watch-running-ai.ps1`.

Note: `watchdog-status.json` is rewritten non-atomically enough that a concurrent read can briefly
see a missing file. Read it with a retry.

---

## 8. What still needs approval — nothing below was done

| # | Action | Why it is gated |
|---|---|---|
| 1 | One manual `POST /api/v1/garmin/sync` | This is the point where rollback stops being free: it writes the first `STARTED` row under V30. Garmin data is 4 days stale (latest activity 2026-10-11) |
| 2 | `RUNNING_AI_GARMIN_SCHEDULER_ENABLED=true` + restart | Only after item 1 has produced a clean `SUCCESS` and 24 h of quiet |
| 3 | Recovery backfill with `onlyMissing=true` | 9 days in the 28-day window are `NOT_COLLECTED` |
| 4 | Watch-face device confirmation | `HAS_PLAN`/`REST` rendering proven by tests, not on the device |
| 5 | ~~Authenticated ChatGPT MCP **read** call~~ | **DONE 2026-10-15 13:15 KST — PASS**, see §5 |
| 5b | Authenticated ChatGPT MCP **train** call | Still open. The read call above proves nothing about it, and ChatGPT's grant is still read-only (7F-3B-3). Widening it is Phase 7F-3B's remaining work, not this release's |
| 6 | Any real Intervals.icu publish; any OAuth scope change | Both publishing switches stay `false`; Worker scopes unchanged |
| 7 | The `id DESC` tie-break of §3 | Behaviour-preserving, but it is a code change after the release was verified |

**Not performed during the observation window, deliberately:** manual Garmin sync, coach data
refresh, scheduler enablement, recovery backfill, any Intervals.icu publish.

---

## 9. Rollback, if it is ever needed

Unchanged from the stabilization record, and still valid because the pre-check returns 0:

1. `SELECT count(*) FROM garmin_sync_attempt WHERE outcome IN ('STARTED','INTERRUPTED')` — **0 today**.
2. If 0: restore `.runtime\backup\running-ai-server-PRE-8B-f1451f5.jar` (or
   `.runtime\released\running-ai-server-20261015-123103-53d6ada7.jar`) and restart. No SQL.
3. If > 0: prefer **roll forward** (re-deploy this jar; the reconciler converts a leftover `STARTED`
   to `INTERRUPTED` with both streak counters intact).
4. Only if rollback is still required: fresh `pg_dump`, explicit approval, then
   `scripts/ops/v30-rollback-quarantine.sql`, with `v30-rollback-restore.sql` scheduled for the
   roll-forward. The bare `DELETE` remains struck.
5. Verify **both** `GET /api/v1/garmin/sync/status` **and** `GET /api/v1/data-freshness` answer 200.
   A clean start and a 200 on `/api/v1/health` are not evidence.

Database backup for this release:
`.runtime\backup\running_ai-prod-PRE-8B-20261015-121500.dump`
(SHA-256 `94b1614a19f0e319935c9d85e34487efbfd7ed09141a162c8a4b7f43f63cf860`, restore proved).
