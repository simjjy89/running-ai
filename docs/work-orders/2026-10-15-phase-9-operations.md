# Phase 9 — production operations and end-to-end completion

Date: 2026-10-15 · Branch: `phase-9-operations` (from `origin/main` `78306e4`) · Instruction kept
verbatim as `2026-10-15-phase-9-operations-instruction.md` · Execution procedure in
`2026-10-15-phase-9-production-runbook.md`.

**Nothing in production was changed by this phase.** No jar was deployed, no scheduled task was
re-registered, no switch was flipped, no Garmin sync or recovery backfill was run, no draft was
created or approved in production, and nothing was published anywhere. Production reads, three
Garmin read requests, and work on throwaway database copies — that is the whole footprint.

---

## 1. Measured baseline (2026-10-15, 14:30–15:20 KST)

| | |
|---|---|
| Production commit / jar | `0fce192`, SHA-256 `091971eb…`, PID 29204, started 12:31 KST |
| Flyway | head **V30**, 32 tables. Next free migration: **V31** |
| `origin/main` | `78306e4` (two doc commits ahead of the deployed `0fce192`; code identical) |
| Activities | 48 |
| `garmin_sync_attempt` | **0 rows** |
| `workout_draft_publish_attempt` | **0 rows** |
| `garmin_sync_state` | high-water `2026-10-10T22:39:40Z`; last success `2026-10-12T03:24:44Z` |
| Newest activity | 2026-10-11 → **4 days old** |
| Recovery, 28-day window | COMPLETE 19 · PARTIAL 1 · NOT_COLLECTED 8 → **9 days worth fetching** |
| Circuit breaker | `allowed: true`, `ALLOWED` |
| Garmin authentication | **valid** — `GET /scheduled-workouts` answered 200 in 1.1 s |
| Switches | both publishing OFF · both Garmin schedulers OFF · coach-refresh OFF · MCP read+train ON · context V2 |
| Runtime | docker/postgres/connector/spring/external-relay all UP; gateway UP on 17910 (PID 28524); cloudflared UP (PID 18220) |

Both V29/V30 ledgers are still empty, so **rollback to the pre-8B jar is still free today**. Section
5.3 says exactly when it stops being free, measured rather than assumed.

---

## 2. Workstream A — operational process

### 2.1 A-1: the watchdog task could stop ticking and still look healthy

The task carried a **single** `AtLogOn` trigger whose repetition is a running instance that exists
only between one logon and the next. Anything that tears that instance down leaves the task
reporting `State = Ready` and `LastTaskResult = 0` while never firing again. Measured on 2026-10-15:
production ran unsupervised for ~20 minutes and neither `Enable-ScheduledTask`,
`Start-ScheduledTask` nor a full `Register-ScheduledTask -Force` restored the repetition. The only
honest signal was an empty `NextRunTime`.

The deploy recovery had bolted on an ad-hoc second trigger. **It is the fix, not a leftover** — so
rather than removing it and restoring the fragile single-trigger shape, `New-WatchdogTaskParts` now
*defines* two triggers:

1. `AtLogOn` + 5 min delay + 5 min repetition (unchanged — the normal boot path);
2. a `Once` trigger backdated 5 minutes, same repetition, plus `StartWhenAvailable` — **not tied to
   a logon**, so it re-arms by itself after any interruption and after a task-engine restart.

Two triggers over one task cost nothing: `MultipleInstances = IgnoreNew` plus
`watch-running-ai.ps1`'s own named mutex mean two triggers that coincide produce at most one run.
The installer now registers `$parts.Triggers` (all of them) and prints `NextRunTime`, warning
explicitly when it is empty, because that is the only state in which the task is broken but looks
fine.

Against the instruction's checklist:

| Requirement | Where it is met |
|---|---|
| Starts after logon | `AtLogOn` trigger, delay 5 min (lets `RunningAI-Startup` finish) |
| Runs periodically | both triggers repeat every 5 min |
| Recovers after reboot | the standing trigger no longer depends on a logon; `StartWhenAvailable` runs a missed occurrence |
| No duplicate watchdog | `MultipleInstances = IgnoreNew` **and** the per-repo named mutex in `watch-running-ai.ps1` |
| No clash during a deploy | `release-spring-jar.ps1` / `start-` / `stop-running-ai.ps1` and every recovery helper take the **same** named runtime lock; a lock failure returns `BUSY` and touches nothing |
| Detects abnormal exits | unchanged observation layer: health + tracked PID + port ownership |
| No restart storms | unchanged 3-per-10-min budget plus the 24 h / 6-failure lockout that only an operator clears |

### 2.2 A-2: the gateway and the tunnel were in no supervision list at all

`watch-running-ai.ps1` supervised exactly `docker`, `postgres`, `connector`, `spring`,
`external-relay`. The MCP gateway — the single hop between the Cloudflare tunnel and Spring — was
in neither that list nor `watchdog-status.json`, so when its node process died it stayed dead
(diagnosed 2026-10-15: ~3.5 hours).

Two new components, in **two different categories**, because they are not the same kind of thing.

**`mcp-gateway` — supervised and recovered**, alongside `external-relay` in
`$script:IndependentComponents`: a failing core chain must never block the one hop ChatGPT reaches
this machine through, and vice versa. It inherits the existing restart budget, long-term lockout,
`BUSY` deferral and `FOREIGN_PROCESS` refusal unchanged. Two things about it differ from every other
component, and both follow from what it is rather than from preference:

* **No `.pid` file.** The gateway is launched by `.runtime\mcp-gateway.cmd`, which holds its entire
  configuration and writes no PID. Rather than change the one launcher already verified in
  production, the process is found by the **listening socket** on its configured port and then
  proved ours by command-line markers. That is also the only identification that can tell the
  production gateway (17910) from the test one (17911) — both run the same `server.js` from the same
  directory, so markers alone cannot; the port is the discriminator.
* **Recovery is `Start-ScheduledTask`, not a spawned child.** The launcher runs node in the
  foreground, so spawning it as a watchdog child would block for the whole child timeout and then
  **kill** the gateway that had just come up. The Scheduled Task is detached and `IgnoreNew` makes a
  redundant start a no-op. Because the start is asynchronous, success is defined only as `/health`
  answering within 30 s — never an exit code.

When the launcher or its task is missing, recovery is **refused** with
`MCP_GATEWAY_LAUNCHER_MISSING` / `MCP_GATEWAY_TASK_NOT_REGISTERED` rather than improvised: inventing
a start command would mean inventing OAuth issuer, audience and tool-allowlist values, i.e. a second
source of truth for the security policy. A build-failing source check asserts the recovery helper
contains no `Start-Process`, no `node.exe`, and no `RUNNING_AI_GATEWAY_*` / `RUNNING_AI_ALLOWED_TOOLS`.

**`tunnel` — observed, never recovered**, in a new third category `$script:ObservedComponents`. This
is a decision about Quick Tunnels, not an omission:

> A Quick Tunnel's hostname is minted when the **process** starts and is gone when it exits. The
> Worker that ChatGPT talks to reaches this machine through a hostname baked into its deployed
> `ORIGIN_MCP_URL`. Restarting cloudflared therefore does not restore the path — it mints a
> *different* hostname and leaves the Worker pointing at one that no longer resolves, turning a
> diagnosable outage into a silently misconfigured one.

So the watchdog reports the tunnel and refuses to touch it. Observed-only components carry no
restart history, no budget and no lockout, because none of those concepts means anything for
something that is never restarted; a stopped tunnel adds a `Blocked` entry (which makes the overall
state `DEGRADED`) but never `DOWN`, since `DOWN` in this file means "something the watchdog can and
will fix is dead". Two cloudflared processes on one origin is `DEGRADED`/`MULTIPLE_TUNNEL_PROCESSES`
and is likewise never auto-resolved: at most one matches the deployed Worker and nothing here can
tell which.

The most useful thing recorded is not up/down but **`tunnel.startedAtUtc`**. A cloudflared start time
newer than the deployed Worker version is *precisely* the condition under which `ORIGIN_MCP_URL` is
stale — the failure two separate sessions already mis-attributed to Worker or gateway configuration.
`watchdog-status.json` gains:

```
components.mcp-gateway   UP | UNHEALTHY | DOWN | DEGRADED
components.tunnel        UP | DEGRADED | DOWN
tunnel { state, reason, processId, startedAtUtc, recoverable: false, note }
```

**One honest limitation, found by dry-running the new code from this worktree.** Unlike the
connector and Spring — whose markers include the repository root, so a watchdog in a dev worktree
cannot touch production's — the gateway is a **machine-global singleton**: one port, one
`RunningAI-McpGateway` task. A watchdog run from any checkout whose launcher names the same port
therefore observes, and could recover, the same gateway. That is benign (recovery is
`Start-ScheduledTask` on the one correct task) and in practice there is only one registered
watchdog, but it is a real difference from the repo-scoped components and is stated rather than
left to be discovered. The dry run also shows the components correctly:

```
mcp-gateway    UP
tunnel         UP          (observed only; never recovered)
```

**Backward compatibility.** An observation that predates these components (every hand-built one in
the test suites) classifies as `UNKNOWN`/`NOT_OBSERVED`, which plans nothing, blocks nothing and
does not degrade the overall state — "the watchdog did not look" is never reported as a failure. A
test asserts the real `Get-RuntimeObservation` always populates both, so `NOT_OBSERVED` cannot occur
on a live tick.

**Restart limiting and backoff** were not reinvented: the existing 3-per-10-minute budget, the
24 h / 6-failure operator-cleared lockout, and the 5-minute tick already are the limit, the backoff
and the repeated-failure record. The gateway joins them; the tunnel has none by design.

### 2.3 The tunnel is currently an orphan, and that is worth stating plainly

Measured, read-only:

* `cloudflared.exe` PID 18220, started **2026-10-14T11:09:16Z**, command line
  `tunnel --url http://127.0.0.1:17910 --logfile <a Claude session scratchpad under %LOCALAPPDATA%\Temp>`.
* Its parent (PID 38812) **no longer exists** — the process survives only because nothing took it
  down with its parent.
* The log carries the live Quick Tunnel hostname. It is **not reproduced here**: it is a currently
  live `*.trycloudflare.com` address fronting the gateway, and commit `e2bd1e0` deliberately
  untracked it from `wrangler.jsonc` for that reason. What matters is the comparison below, which
  needs no hostname in version control.
* Deployed Worker version `0d75fa82…`, created **2026-10-14T11:10:10Z** — one minute *after* the
  tunnel started — and its `ORIGIN_MCP_URL` **matches** the hostname that running cloudflared
  process minted (compared directly; neither value is recorded here).

So the chain is consistent **today**, and the ordering is exactly the relationship the new
`startedAtUtc` field exposes. But the production tunnel's only log lives in a temp directory a
cleanup could remove, and nothing owns the process.

`scripts/windows/mcp/start-cloudflare-quick-tunnel.ps1` makes starting one a defined operation:
tracked PID file, log under `.runtime\logs`, refusal to start a second tunnel for a port that
already has one, and — the point — it prints the assigned hostname **and the Worker redeploy command
it now requires**, without performing it. Deploying is an external write and an operator decision.
Adopting this launcher means restarting cloudflared, which changes the hostname, so it is an
approval item rather than something done here (see §7). No Named Tunnel, no paid infrastructure.

---

## 3. Workstream E — remaining defects and MCP verification

### 3.1 E-1: the injury ledger had no defined "latest"

`InjuryReportEntity` repository methods ordered on `recorded_at DESC` alone. `recordedAt` is
`Instant.now(clock)`, so two reports appended inside one clock tick share it exactly and the
database is free to return either as "latest" — which, for the ledger the safety gate reads, decides
whether training is permitted. It is not hypothetical: production holds two `RESOLVED` rows 0.5 s
apart (a retried shell pipeline), and the full suite flaked on this once.

Fixed by tie-breaking on insertion order, the same precedent `AthleteIntensityProfileHistoryRepository`
already uses:

```
findFirstByAthleteIdOrderByRecordedAtDescIdDesc
findByAthleteIdOrderByRecordedAtDescIdDesc
```

One change covers both consumers: the gate reads `current()`, the coach context reads `history()`,
and both go through these two methods.

Regression test `reports sharing one recordedAt are ordered by insertion, newest first` saves two
rows with a byte-identical `recordedAt` (the service stamps the real clock and cannot be made to
produce a genuine tie) and asserts the order **five times**, so an ordering that merely happens to
come out right once is not mistaken for a defined one.

No production row was read, modified or deleted. `V21__create_injury_report.sql` carries a comment
naming the old method; it is an applied migration and was deliberately **not** edited — changing a
comment changes the checksum and would fail Flyway validation in production.

### 3.2 E-2: the train-scope OAuth leg is PASS, on two-sided evidence

| Side | Evidence |
|---|---|
| Gateway | `.runtime\logs\mcp-gateway.log` — `2026-10-15T05:03:05.192Z INFO tools/call get_workout_draft authorized (scope runningai:train)` |
| Spring | `.runtime\logs\spring.out.log` — `2026-10-15T14:03:05.204+09:00 INFO 29204 … TrainMcpTools : MCP train tool get_workout_draft: ok cid=361a38ff-07f9-4b7f-8667-24e3f96dd222` |

Same instant (05:03:05.192Z = 14:03:05.192 KST), 12 ms apart, matching the correlation ID the
instruction supplied. PID 29204 is the post-deploy jar. The call is **after** the 03:31Z deploy,
which is what the previous session could not yet show.

**Why this proves the token carried `runningai:train`, not merely that a read succeeded.**
`tools/mcp-gateway/server.js` checks `granted.includes(scopeFor(name))` **before** any backend
traffic, and `get_workout_draft` requires `runningai:train`. A read-only token produces
`tool refused: … needs a scope this token does not carry` plus a 403 challenge and **zero** backend
requests. The Spring line proves backend traffic happened; therefore the check passed; therefore the
token carried the train scope. The gateway's own log line is not relied on for this — a known gotcha
is that it prints the scope the *tool* requires, not the token's.

And the gateway only accepts tokens it can validate against the Worker's JWKS, whose private key
exists solely as a Worker secret, so no local process can mint one. An `authorized` line is
necessarily a real Worker-signed token.

| Instruction item | Result |
|---|---|
| A real `tools/call` is recorded | **yes**, both sides |
| OAuth request and granted scope | the grant could **not** be re-read this session (`wrangler kv key list --remote` fails with `Authentication error [code: 10000]`; without `--remote` it reads the empty local namespace). Recorded as not re-measured — the log pair above is independent of it |
| `runningai:train` check passed | **yes**, by the ordering argument above |
| Reached production Spring | **yes**, PID 29204 |
| Read / train separation intact | **yes** — the same log shows `get_athlete_profile authorized (scope runningai:read)` |
| `publish_approved_draft` still unreachable | **yes** — absent from `RUNNING_AI_ALLOWED_TOOLS` in `.runtime\mcp-gateway.cmd` (12 of 13 tools), and permanently un-allowlistable in `server.js` |

No re-authentication was performed and no OAuth scope was changed.

---

## 4. Workstream D — AI coach, Garmin reach, watch face

### 4.1 What the coach is actually given today (production, read-only)

`GET /api/v1/coach/training-context?date=2026-10-15&version=V2` — DB-only, zero external calls,
17 KB, SHA-256 `14555893…`:

* `injuryHistory.gate` → `nonRestTrainingAllowed: true`, `INJURY_RESOLVED_CONFIRMED`, with the
  message stating in full that this is *"a data permission, not a medical clearance"*. The gate is
  intact and correctly worded.
* The E-1 pair is visible in it: `latest.recordedAt` …`55.183427Z` over `previousReports[0]`
  …`54.674574Z`.
* `dataCoverage` — 35 supported activities, 35 analysed, 20 of 28 recovery days,
  `newestActivityDate 2026-10-11`, plus the Phase 8B `metricUnavailability` block naming all four
  import-level absences.
* `trainingLoad` — source date 2026-10-12, **ageDays 3**; `trainingRhythm.daysSinceLastRun` **4**.
* `recovery` — every metric dated 2026-10-12, ageDays 3.

So the context honestly reports that its newest inputs are 3–4 days old instead of presenting them
as current. That staleness is exactly what Workstreams B and C exist to remove; **the coach is
already being told about it, correctly.**

### 4.2 Draft lifecycle end-to-end — on an isolated instance

Run on a dev Spring (port 18088) against `running_ai_p9dev`, a fresh copy of the production
database, with every publishing switch and both Garmin schedulers off and no Intervals key present.
Production was not contacted and nothing was published.

Full path exercised: real production data → `TrainingContextV2` → Claude CLI → draft → revision →
approval → `GET /api/v1/today-workout`, then every refusal that should stand in the way.

| Step | Request | Result |
|---|---|---|
| 0 | `today-workout?date=2026-10-15` | `NO_PLAN` |
| 2 | `POST /workout-drafts` (50 min) | **200** — draft **21** v1, *"Easy Return-to-Running Shakeout"*, `EASY`, 45 min |
| 4 | `today-workout` while unapproved | still **`NO_PLAN`** — a draft is not a plan |
| 5 | `POST /{21}/revisions` ("only 30 minutes") | **200** — draft **22** v2, `EASY`, **30 min**, same `draftGroupId` |
| 6 | `GET /{21}` | now **`SUPERSEDED`** |
| 7 | `POST /{22}/approve` | **200** — `APPROVED`, approval 4 |
| 8 | `today-workout?date=2026-10-15` | **`HAS_PLAN` / `APPROVED_DRAFT` / `EASY` / 30 min / `stale: false`** |
| 9 | `today-workout?date=2026-10-05` | `HAS_PLAN` with **`stale: true`**, `athleteToday 2026-10-15` |
| 10 | `POST /{22}/revisions` again | **409 `WORKOUT_DRAFT_APPROVED_IMMUTABLE`** |
| 11 | `GET /{22}/publish-preview` | 200 — `publishable: true`, `expectedOutcome: PUBLISH`, 3 rendered steps |
| 11 | `POST /{22}/publish` | **409 `DRAFT_PUBLISHING_DISABLED`** — nothing published |
| 12 | `GET /{22}/publish-attempts` | `attemptCount: 0` — correct: a publish refused by the master switch never reached an external system, so it is not an attempt |

**The coach reported its own data staleness, unprompted.** Draft 21's assessment opens:
*"Stale (3-day-old) data from the day after the Oct 11 long run shows HRV, sleep and Body Battery
well below baseline — likely reflecting that hard session rather than today's state, **but no
fresher reading exists to override it**."* It also names the numbers (HRV −30 %, sleep score −30 %,
Body Battery −49 %) and the four consecutive rest days. That is the "insufficient data must be
stated" requirement, demonstrated on real production data rather than asserted.

**It read the injury gate correctly, in the Phase 7F-3A sense.** Draft 22: *"the injury gate permits
training following yesterday's resolved lateral-knee report — treat today as a cautious return, not
a fitness read."* A permitted gate was not taken as licence to train hard.

Then the gate itself, on the same instance:

| Check | Result |
|---|---|
| `POST /athlete/injury-reports` `status: ACTIVE` | `nonRestTrainingAllowed: false`, `INJURY_ACTIVE` |
| `POST /workout-drafts` for 2026-10-16 with that on file | draft **23**, `workoutType: REST`, **0 min, zero segments**, rationale naming the gate; warnings include *"do not assume today's status from"* 4-day-old recovery metrics |
| Approve 23, then `today-workout?date=2026-10-16` | **`REST`** / `APPROVED_DRAFT` — a REST decision reaches the watch, which was the whole point of the 8B watch link |
| `GET /{23}/publish-preview` | `restDay: true`, `externalWriteRequired: **false**`, `expectedOutcome: SKIPPED_REST_DAY` |
| `GET /{22}/publish-preview` — the **already-approved EASY** draft, now that an ACTIVE report exists | **422 `WORKOUT_SAFETY_INJURY_ACTIVE`** |

That last row is the one worth keeping: a draft approved while healthy **cannot** be published once
an injury is reported. The gate is re-evaluated at every workout-producing path, not only at
generation, and it is never silently rewritten into a REST day — it raises.

Teardown: the dev instance was stopped and `running_ai_p9dev`, `running_ai_p9rb`, `running_ai_test`
and `running_ai_restorecheck` dropped. Production re-checked afterwards: **48 activities, 19 drafts,
2 injury reports, 0 sync attempts** — unchanged.

### 4.3 D-2: how far a workout provably gets, in four separate claims

These are different statements and are deliberately not merged:

| # | Claim | Status | Evidence |
|---|---|---|---|
| 1 | Published to Intervals.icu | **PROVEN** | draft 17 → `intervalsDelivery: PUBLISHED_AND_VERIFIED`, event `140511724` |
| 2 | Present on the Garmin calendar | **PROVEN** | `GET /scheduled-workouts?year=2026&month=10` → 17 items; one `workout`: *"RunningAI workout"*, `running`, **2026-10-16**, `workoutId 1729422156` |
| 3 | Downloaded to the FR265 | **NOT OBSERVABLE** | `deviceDelivery` is permanently `UNKNOWN`; no Garmin write path exists or may be added (a connector AST test fails the build if `schedule_workout` / `upload_*_workout` / `push_workout_to_device` appears) |
| 4 | Seen on the watch | **NOT VERIFIED** | needs the owner (§7) |

Claim 2 also re-confirms the Phase 8B live findings on the first call of this session: five
`itemType` values (`workout` 1, `fbtAdaptiveWorkout` 6, `event` 2, `nap` 2, `activity` 6), so the
`itemType == "workout"` filter is load-bearing; and a request for `month=10` echoes `"month": 9`,
confirming the library's 0-indexing.

Claim 1's companion: `delivery-status` for draft 17 (2026-10-05) returns
`garminDelivery: GARMIN_NOT_FOUND`, `garminScheduledWorkoutCount: 0`. **That is not a failure** —
Garmin's calendar returns only committed workouts, so a 10-day-old date legitimately has none.

Saying "the Garmin calendar read succeeded" is therefore **not** the same as "the workout is on the
FR265", and this report does not say so anywhere.

### 4.4 D-3: watch-face path

Contract unchanged: the wire format to the watch stays `{date, status, label}` and no coach free
text crosses — `GET /api/v1/today-workout` carries structure only (`workoutType`,
`totalDurationMinutes`, `repetitions`) and the relay builds the label itself.

Verified read-only against production:

* relay `/health` → ok; `spring_base_url` = `http://127.0.0.1:18080`; cache TTL 240 s.
* Relay source order: RunningAI approved draft **first**, then the legacy `today_plan.json`
  (read-only, never written), then Intervals.icu events.
* The relay discards an answer whose `date` disagrees or whose `stale` is true, and treats
  `NO_PLAN` as "RunningAI has decided nothing" rather than a decision — so it falls through.
* A RunningAI failure is caught, logged `RUNNING_AI_UNAVAILABLE` and stepped over: a backend outage
  must never blank the face.
* Live relay log confirms `TODAY_WORKOUT_OK status=NO_PLAN` post-deploy, which is correct — there is
  no approved draft for 2026-10-15 in production.
* Production `today-workout?date=2026-10-05` → `HAS_PLAN / EASY / 40 min / published true / **stale
  true** / athleteToday 2026-10-15`: the stale flag works on real data.

Relay suite: 31/31. The on-device checklist is §5 of the runbook. No watch-face design, font or
protocol change was made.

---

## 5. Workstream B — Garmin collection safety (B-1 only; nothing was run)

### 5.1 Cost and effect of one manual sync

| | |
|---|---|
| Garmin requests | **1** — high-water 2026-10-10 minus the 7-day overlap starts at 2026-10-03; `page-size` 50 ends the loop on a short page 0 |
| Writes to Garmin | none (read-only) |
| Writes to RunningAI | `activity`/`activity_raw` upserts, the `garmin_sync_state` checkpoint, **one** `garmin_sync_attempt` row |
| Not written | `activity_detail`, `activity_sample`, `activity_analysis` — so a new activity legitimately raises `analysisMissingOrOutdated` |

**The circuit breaker cannot block it.** `GarminSyncCircuitBreaker.evaluate()` has exactly two
callers — `GarminSyncScheduler` and the read-only `GarminSyncStatusService`. A manual sync never
consults it, which is the design: that request *is* the intervention a block is waiting for.

### 5.2 Scheduler, once enabled

`fixed-delay 1h` → ≤ 24 Garmin reads/day, plus the 1/day profile sync already enabled. Thresholds:
AUTH 3 (**no expiry**), RATE_LIMIT 1 (6 h), TRANSIENT 8 (2 h), PARTIAL 6 (12 h),
`minimum-interval` 15 m as a hard floor.

`running-ai.garmin.scheduler.enabled` is the only switch in `application.yml` with a literal `false`
and no `${ENV:default}`. The environment variable still wins and a wiring test asserts it, but in
production the **only** proof it bound is a `Garmin scheduled sync` line in `spring.out.log` within
~1 minute of startup.

`GarminActivityRegistry` is consulted by the circuit breaker alone, so it makes the **scheduler**
defer during a recovery sync but does **not** stop a *manual* activity sync from running alongside
one. That stays an operator rule.

### 5.3 The V28 rollback question — measured, not assumed

The pre-8B jar (`f1451f5`, 28 migrations, `GarminSyncAttemptOutcome` = SUCCESS/PARTIAL/FAILED) was
started against a **restored copy of production at V30** with the single attempt row set to each
outcome in turn:

| `outcome` | V28 jar starts? | `/api/v1/data-freshness` | `/api/v1/garmin/sync/status` |
|---|---|---|---|
| `SUCCESS` | yes | **200** | **200** |
| `INTERRUPTED` | yes | **500** | **500** |
| `STARTED` | yes | **500** | **500** |

* **Flyway is not the obstacle.** The 28-migration jar logged `Successfully validated 30 migrations`
  and `Current version of schema "public": 30`. The extra `consecutive_partial_count` column is
  harmless too — Hibernate's `validate` ignores unmapped columns.
* **The enum is.** `No enum constant …GarminSyncAttemptOutcome.INTERRUPTED`, thrown by both
  endpoints that read the ledger. The application is otherwise fine; two read endpoints are not.

**Rule:** after the first sync under V30, the V28 jar is a safe swap **while and only while** the
attempt row carries `SUCCESS`, `PARTIAL` or `FAILED`. A completed sync overwrites `STARTED` within
seconds, so the exposed window is an in-flight or interrupted sync. If a rollback is ever needed
inside that window: roll **forward** first; if the old jar is genuinely required, `UPDATE` the row's
outcome to `FAILED` with `failure_code = 'ROLLBACK_V28_INTERRUPTED_REMAP'` — self-describing, and
not a `DELETE`. Destroying an audit ledger to satisfy an old reader is not a rollback step.

---

## 6. Workstream C — recovery backfill plan (validated; nothing was run)

Nine days in the 28-day window are worth fetching — **2026-10-04, 10-06, 10-07, 10-08, 10-09,
10-10, 10-13, 10-14, 10-15**. Eight are `NOT_COLLECTED`: no connector call was ever made, which is
not the same as "Garmin has nothing". One (10-06) is `PARTIAL` — resting HR, Body Battery and stress
stored; HRV and sleep not — and is re-fetched because Garmin can fill a metric in later.

Verified from the implementation, not from the documentation:

| Requirement | Finding |
|---|---|
| `onlyMissing=true` behaviour | `targetDates()` returns exactly `coverage(...).datesWorthFetching()` — the same 9 dates the live endpoint reports |
| Request volume | 9 connector calls ≈ 45 Garmin requests (five metric groups per day); `onlyMissing=false` would be 28 calls ≈ 140 for no new data |
| Pauses | 2 s **after a fetch, never after a skip**, so the 19 covered days cost nothing |
| Order | newest → oldest, so the most decision-relevant days land first if the run is cut short |
| Overwrites | none — the upsert never replaces a stored value with a missing one |
| Failure | first-day failure throws; a later one returns `completed=false` with `stoppedAt`/`stoppedReason`, keeping what was already fetched; no retry anywhere |
| Concurrency with activity sync | the registry defers the **scheduler** only; a manual activity sync is an operator rule (§5.2) |

**Success is `NOT_COLLECTED` reaching 0 — not every day reaching `COMPLETE`.** After the run each
day must be `COMPLETE`, `PARTIAL`, `CONFIRMED_NO_DATA` or `COLLECTION_FAILED`; the middle two are
correct outcomes. A day the athlete did not wear the watch has no HRV and never will. Treating that
as a defect would invite filling an absent measurement with `0`, which must never happen.

---

## 7. Tests

| Suite | Result |
|---|---|
| Spring, H2 — `gradlew test` | **1597 / 1597**, 0 failures, 0 errors, 0 skipped (159 classes) |
| Spring, PostgreSQL 17 — throwaway `running_ai_test`, Hikari pool 2 | **1597 / 1597**, 0 / 0 / 0 |
| Flyway V1→V30 on PostgreSQL | head **V30**, **32 tables** — the proof the suite really used PostgreSQL |
| PowerShell `Test-Watchdog.ps1` | **71 / 71** (was 48; +23 for A-1/A-2) |
| PowerShell `Test-WatchdogRecoveryPolicy.ps1` | **56 / 56** (was 55; +1) |
| PowerShell `Test-ExternalRelay.ps1` | 16 / 16 |
| PowerShell `Test-ConnectorOwnership.ps1` | 36 / 36 |
| Garmin connector — `pytest` | **154 / 154** |
| External relay — `node --test` | **31 / 31** |
| MCP gateway — `npm test` | **46 / 46** |
| MCP Worker — `npm run test:all` | **49 / 49** |
| V30 → V28 rollback rehearsal | PASS — 3 outcomes measured, §5.3 |

The Spring count moves 1596 → 1597: exactly the new injury ordering regression test. **No test was
deleted, skipped or disabled.**

Two pre-existing checks in `Test-WatchdogRecoveryPolicy.ps1` were updated rather than worked around:

* the lock-pairing check asserted `$enterCount -eq 2` — a magic number that must be bumped for every
  new recovery helper. It now asserts the actual invariant: `>= 2` acquisitions and
  `finallyExitCount -eq $enterCount`.
* a new sibling check pins the gateway helper's distinguishing property — it starts the gateway only
  via its Scheduled Task, never a spawned child and never a re-expressed launcher.

---

## 8. Production impact of this phase

| | |
|---|---|
| Deployed | **nothing** |
| Schema | unchanged, **V30**; next free is V31 |
| Switches | unchanged |
| Scheduled tasks | unchanged |
| Production DB writes | **none** |
| Garmin requests made | **3**, all reads: one `/scheduled-workouts` probe, one via `delivery-status`, and the connector `/health` |
| Intervals.icu | not contacted |
| Drafts created/approved in production | **none** |
| Published anywhere | **nothing** |
| Throwaway databases created | `running_ai_test` (PG suite), `running_ai_p9rb` (rollback rehearsal), `running_ai_p9dev` (coach E2E) — all copies, all disposable |
| Cost | none — no new infrastructure, no Named Tunnel, no paid service |

---

## 9. Awaiting approval, in the order they must run

Each stage runs only if the previous one passed. Full procedure in the runbook.

| # | Action | Why it needs approval | Reversible? |
|---|---|---|---|
| 1 | Deploy the Phase 9 code | production jar change | yes — jar swap, no schema change, both ledgers empty |
| 2 | Re-register `RunningAI-Watchdog` and `RunningAI-McpGateway` | scheduled-task configuration | yes — re-register the previous definitions |
| 3 | One manual `POST /api/v1/garmin/sync` | first Garmin-driven write to the production DB | data is additive; see §5.3 for the rollback rule it starts |
| 4 | 24 h observation | — | — |
| 5 | `RUNNING_AI_GARMIN_SCHEDULER_ENABLED=true` + restart | enables automatic Garmin traffic | yes — set false and restart |
| 6 | 24 h observation | — | — |
| 7 | Recovery backfill, `onlyMissing: true`, 28 days | ~45 Garmin requests, writes recovery rows | additive only; never overwrites |
| 8 | Final freshness and analysis check | — | — |
| 9 | FR265 on-device checklist (owner, once) | physical device | — |

Separate, not part of the chain:

* **Adopt the tunnel launcher.** Restarting cloudflared mints a new hostname and requires a Worker
  redeploy with the new `ORIGIN_MCP_URL`. Worth doing — the production tunnel is currently an orphan
  logging into a temp directory (§2.3) — but it is an external write and a deliberate outage window.
* **Push `phase-9-operations` / merge to `main`.** Not done; the branch is local.

Explicitly **not** requested and **not** done: any Intervals.icu publish, any OAuth scope change,
any re-authentication, any production data edit, any reboot.
