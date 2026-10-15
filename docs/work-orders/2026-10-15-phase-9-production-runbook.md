# Phase 9 — production runbook

Companion to `2026-10-15-phase-9-operations.md` (what was built and measured). This file is the
execution procedure only: every step, its pre-condition, the exact command, what proves it worked,
and what to do when it did not.

**Nothing in this file has been executed.** Writing a step here does not authorise it. Each
numbered stage needs its own approval, and a stage runs only if the previous one passed.

Baseline this runbook was written against (measured 2026-10-15, 14:30–15:20 KST):

| Fact | Value |
|---|---|
| Production commit / jar | `0fce192`, jar SHA-256 `091971eb…`, PID 29204 |
| Flyway head | **V30**, 32 tables |
| Activities | 48 |
| `garmin_sync_attempt` | **0 rows** |
| `workout_draft_publish_attempt` | **0 rows** |
| `garmin_sync_state` | high-water `2026-10-10T22:39:40Z`, last success `2026-10-12T03:24:44Z` |
| Newest activity | 2026-10-11 (4 days old) |
| Recovery coverage (28 d) | COMPLETE 19, PARTIAL 1, NOT_COLLECTED 8 → **9 days worth fetching** |
| Circuit breaker | `allowed: true`, reason `ALLOWED` |
| Garmin auth | **valid** — `GET /scheduled-workouts` answered 200 in 1.1 s |
| Switches | both publishing OFF, both Garmin schedulers OFF, coach-refresh OFF |

---

## 0. The two rules that govern every stage

**0.1 Never disable the watchdog for a deploy.** `release-spring-jar.ps1`, `start-running-ai.ps1`
and `stop-running-ai.ps1` already take the same named runtime lock, so a watchdog-driven restart
provably cannot interleave with a release. Disabling `RunningAI-Watchdog` destroys its running
repetition instance, and on the single-trigger task shape neither `Enable-ScheduledTask`,
`Start-ScheduledTask` nor a full `Register-ScheduledTask -Force` brings it back before the next
logon — measured 2026-10-15, production ran unsupervised for ~20 minutes while `State` read `Ready`
and `LastTaskResult` read `0` the whole time.

Stage 1 of this runbook replaces that task shape with a two-trigger one, after which a disable is
recoverable. Until it has run, the rule above is absolute.

**0.2 Check supervision with `NextRunTime`, never with `State`.** An empty `NextRunTime` is the
only honest signal that a repetition is not armed. Confirm with **two unattended ticks** of
`.runtime\watchdog-status.json` (`checkedAt` must advance twice without anyone starting the task),
reading the file with a retry — it is rewritten non-atomically and a concurrent read can see it
missing.

---

## 1. Deploy the Phase 9 code — approval required

Code-only. No schema change (**Flyway stays V30**; next free is V31), no new switch, no behaviour
change to any existing endpoint.

### 1.1 Pre-conditions

```powershell
# Production is healthy and is the jar we think it is
Invoke-RestMethod http://127.0.0.1:18080/actuator/health
Get-Content C:\running-ai-github\.runtime\running-ai-server.jar.json

# Nothing is mid-flight
Invoke-RestMethod http://127.0.0.1:18080/api/v1/garmin/sync/status
```

### 1.2 Backup

```powershell
$stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
docker exec running-ai-postgres pg_dump -U running_ai -d running_ai -Fc -f /tmp/p9.dump
docker cp running-ai-postgres:/tmp/p9.dump "C:\running-ai-github\.runtime\backup\running_ai-prod-PRE-P9-$stamp.dump"
Copy-Item C:\running-ai-github\.runtime\running-ai-server.jar `
          "C:\running-ai-github\.runtime\backup\running-ai-server-PRE-P9-0fce192.jar"
```

A database backup is taken even though this release changes no schema: stage 2 is the first write
this runbook causes, and the backup must predate it.

### 1.3 Release

The Phase 9 code is on the local branch **`phase-9-operations`**, not on `main` and not pushed.
Getting it onto the production checkout is itself an approval item, and it comes first:

```powershell
cd C:\running-ai-github            # the production checkout, on main
git merge --ff-only phase-9-operations   # must fast-forward; it was branched from origin/main
git log --oneline -3                     # confirm the Phase 9 commits are now on main
.\scripts\windows\release-spring-jar.ps1 -Build
```

Pushing `main` to `origin` is a **separate** decision and is not required to deploy. If it is
approved, push after the release has been verified, so the remote never advertises a commit that
production rejected.

Leave the watchdog running throughout (rule 0.1).

### 1.4 Verification

| Check | Command | Expected |
|---|---|---|
| Jar identity | `Get-Content .runtime\running-ai-server.jar.json` | the Phase 9 commit, `migrationCount` **30** |
| Health | `Invoke-RestMethod .../actuator/health` | `UP` |
| Flyway unmoved | `docker exec running-ai-postgres psql -U running_ai -d running_ai -At -c "select version from flyway_schema_history order by installed_rank desc limit 1"` | `30` |
| Data unmoved | `select count(*) from activity` | **48** |
| Ledgers still empty | `select count(*) from garmin_sync_attempt` | **0** |
| Injury gate intact | `GET /api/v1/coach/training-context?date=<today>&version=V2` | `injuryHistory.gate.nonRestTrainingAllowed = true`, reason `INJURY_RESOLVED_CONFIRMED` |
| Injury order deterministic | same response | `latest.recordedAt` (…`55.183427Z`) newer than `previousReports[0]` (…`54.674574Z`) |

### 1.5 Register the two scheduled tasks — separate approval

These change the Windows task configuration and are listed separately on purpose.

```powershell
cd C:\running-ai-github
.\scripts\windows\install-running-ai-watchdog-task.ps1 -DryRun          # read first
.\scripts\windows\install-running-ai-watchdog-task.ps1

.\scripts\windows\mcp\install-running-ai-mcp-gateway-task.ps1 -DryRun
.\scripts\windows\mcp\install-running-ai-mcp-gateway-task.ps1
```

What each one changes:

* **`RunningAI-Watchdog`** gains a second, logon-independent standing trigger alongside the existing
  AtLogOn one. This also makes the temporary trigger added during the 8B deploy recovery the
  *canonical* shape instead of an undocumented leftover — the task is normalised by adopting it,
  not by removing it, so supervision is never interrupted.
* **`RunningAI-McpGateway`** gets the same two-trigger shape and, more importantly,
  **`ExecutionTimeLimit = PT0S` (none)** instead of `PT72H` — the current task tells Windows to kill
  a perfectly healthy gateway every three days. Its action is `.runtime\mcp-gateway.cmd` verbatim;
  no gateway configuration is read or written.

Verification (both tasks):

```powershell
Get-ScheduledTask -TaskName 'RunningAI-Watchdog','RunningAI-McpGateway' |
  ForEach-Object { $i = $_ | Get-ScheduledTaskInfo
    "{0}: triggers={1} next={2}" -f $_.TaskName, @($_.Triggers).Count, $i.NextRunTime }
```

Expect `triggers=2` and a **non-empty** `NextRunTime` on both. Then wait ~12 minutes and confirm
`checkedAt` in `.runtime\watchdog-status.json` advanced **twice** on its own (rule 0.2).

New fields to expect in `watchdog-status.json` after the first Phase 9 tick:

```
components.mcp-gateway   UP
components.tunnel        UP
tunnel.processId         <pid>
tunnel.startedAtUtc      <when cloudflared started>
tunnel.recoverable       false
```

`tunnel.startedAtUtc` is the field that matters operationally: it is newer than the deployed Worker
version exactly when `ORIGIN_MCP_URL` has gone stale.

### 1.6 Rollback for stage 1

Pure code, no schema change, both ledgers empty → rollback is the jar swap and nothing else:

```powershell
Copy-Item C:\running-ai-github\.runtime\backup\running-ai-server-PRE-P9-0fce192.jar `
          C:\running-ai-github\.runtime\running-ai-server.jar -Force
.\scripts\windows\start-running-ai.ps1
```

To undo 1.5, re-register the tasks from the previous commit's installers. Do **not**
`Disable-ScheduledTask` (rule 0.1).

---

## 2. One manual Garmin sync — approval required

### 2.1 What it costs and what it writes

| | |
|---|---|
| Garmin requests | **1** — high-water `2026-10-10T22:39:40Z` minus the 7-day overlap starts the window at 2026-10-03; `page-size` is 50 and the account has far fewer than 50 activities since then, so page 0 is short and the loop ends |
| Writes to Garmin | **none** — incremental sync is read-only against Garmin |
| Writes to RunningAI | `activity` + `activity_raw` upserts, `garmin_sync_state` checkpoint, **one** `garmin_sync_attempt` row |
| Not written | `activity_detail`, `activity_sample`, `activity_analysis` — incremental sync stores summaries only |
| Circuit breaker | **not consulted** — `GarminSyncCircuitBreaker.evaluate()` is called only by `GarminSyncScheduler` and by the read-only status endpoint. A manual sync can never be blocked by it, which is deliberate: this request *is* the intervention a block is waiting for |

A new activity legitimately raises `data-freshness.analysis.analysisMissingOrOutdated`. That is not
a defect — detail/analysis collection is a separate, manual step.

### 2.2 Pre-conditions

* Stage 1 passed.
* No recovery backfill running (`GarminActivityRegistry` only makes the *scheduler* defer; it does
  **not** block a manual sync — see 3.3).
* Backup from 1.2 exists and restores. Prove the restore rather than assuming it:

```powershell
docker exec running-ai-postgres psql -U running_ai -d postgres -c "DROP DATABASE IF EXISTS running_ai_restorecheck;"
docker exec running-ai-postgres psql -U running_ai -d postgres -c "CREATE DATABASE running_ai_restorecheck;"
docker cp "C:\running-ai-github\.runtime\backup\running_ai-prod-PRE-P9-<stamp>.dump" running-ai-postgres:/tmp/rc.dump
docker exec running-ai-postgres pg_restore -U running_ai -d running_ai_restorecheck /tmp/rc.dump
docker exec running-ai-postgres psql -U running_ai -d running_ai_restorecheck -At -c "select count(*) from activity;"   # expect 48
```

### 2.3 Record the baseline first

```powershell
docker exec running-ai-postgres psql -U running_ai -d running_ai -c "
 select 'activity', count(*) from activity
 union all select 'activity_raw', count(*) from activity_raw
 union all select 'attempt', count(*) from garmin_sync_attempt;
 select * from garmin_sync_state;"
Invoke-RestMethod http://127.0.0.1:18080/api/v1/data-freshness | ConvertTo-Json -Depth 4 > p9-freshness-before.json
```

### 2.4 Execute

```powershell
Invoke-RestMethod -Method Post http://127.0.0.1:18080/api/v1/garmin/sync
```

Exactly once. **Do not retry on a non-answer**: re-issuing turns one Garmin request into two and
the in-JVM single-flight will reject the second anyway (a rejected request is deliberately not
recorded as an attempt, because it never reached Garmin).

### 2.5 Verification

| Check | Expected |
|---|---|
| `GET /api/v1/garmin/sync/status` → `lastAttempt.outcome` | `SUCCESS` (or `PARTIAL`, see below) |
| `garmin_sync_attempt` row count | exactly **1** |
| `consecutive_failure_count` / `consecutive_partial_count` | `0` / `0` |
| `garmin_sync_state.last_successful_sync_at` | advanced |
| `garmin_sync_state.high_water_started_at` | advanced **only** on SUCCESS; a PARTIAL deliberately holds it back |
| `activity` count | 48 + the genuinely new sessions, never fewer |
| Duplicates | `select external_source, external_id, count(*) from activity group by 1,2 having count(*) > 1` → **0 rows** |
| `GET /api/v1/data-freshness` | `GARMIN_ACTIVITY.lastAttemptOutcome` populated for the first time; `latestDataDate` moved forward |
| `GET /api/v1/coach/training-context?version=V2` | `dataCoverage.newestActivityDate` and `trainingRhythm.daysSinceLastRun` reflect the new data |

### 2.6 Stop conditions

Stop and do not proceed to stage 3 if any of these occur:

* outcome `FAILED` with `GARMIN_AUTH_REQUIRED` or `GARMIN_FORBIDDEN` → the connector session needs
  renewing; a scheduler would repeat this 24×/day.
* outcome `FAILED` with `GARMIN_RATE_LIMITED` → wait out the cooldown; never retry immediately.
* `activity` count **decreased**, or any duplicate `(external_source, external_id)`.
* `high_water_started_at` moved on a PARTIAL, or did not move on a SUCCESS.

### 2.7 Rollback after stage 2 — measured, not assumed

The pre-8B jar (`f1451f5`, 28 migrations) was run against a **restored copy of production at V30**
on 2026-10-15 with the attempt row set to each possible outcome in turn:

| `garmin_sync_attempt.outcome` | V28 jar starts? | `/api/v1/data-freshness` | `/api/v1/garmin/sync/status` |
|---|---|---|---|
| `SUCCESS` | yes | **200** | **200** |
| `INTERRUPTED` | yes | **500** | **500** |
| `STARTED` | yes | **500** | **500** |

Two things this settles:

* **Flyway is not the problem.** The 28-migration jar logged `Successfully validated 30 migrations`
  and `Current version of schema "public": 30`, and started normally. The extra
  `consecutive_partial_count` column is also harmless — Hibernate's `validate` ignores unmapped
  columns.
* **The enum is.** `STARTED` and `INTERRUPTED` do not exist in the pre-8B
  `GarminSyncAttemptOutcome` (`SUCCESS`, `PARTIAL`, `FAILED` only), so the two endpoints that read
  the ledger fail with `No enum constant …GarminSyncAttemptOutcome.INTERRUPTED`. The application is
  otherwise fine; two read endpoints are not.

**Therefore:** after stage 2, swapping back to the V28 jar is safe **while and only while** the
single attempt row carries `SUCCESS`, `PARTIAL` or `FAILED`. A completed sync overwrites `STARTED`
within seconds, so the exposed window is a sync that is in flight or was interrupted.

If a rollback is ever needed while the row is `STARTED`/`INTERRUPTED`, in order of preference:

1. **Roll forward** — keep the V30 jar. Nothing about the data is wrong; only the old reader is.
2. If the V28 jar is genuinely required, **re-map the row, never delete it**:
   ```sql
   UPDATE garmin_sync_attempt
      SET outcome = 'FAILED',
          failure_code = 'ROLLBACK_V28_INTERRUPTED_REMAP'
    WHERE outcome IN ('STARTED', 'INTERRUPTED');
   ```
   `FAILED` is the closest honest word in the old vocabulary for a run that did not complete, and
   the failure code says exactly why the value was changed and by whom. This needs its own approval.

**`DELETE FROM garmin_sync_attempt` is not a rollback step.** It is an audit ledger; destroying it
to satisfy an old reader loses the one record of what happened.

---

## 3. Observation window, then the scheduler — approval required

### 3.1 The 24-hour observation after stage 2

Not a waiting ritual — there is something specific to see. Record at the start, at +4 h and at
+24 h:

```powershell
Invoke-RestMethod http://127.0.0.1:18080/api/v1/garmin/sync/status
Invoke-RestMethod http://127.0.0.1:18080/api/v1/data-freshness | ConvertTo-Json -Depth 4
Get-Content C:\running-ai-github\.runtime\watchdog-status.json -Raw
```

Pass criteria: attempt row still exactly 1 and still terminal; `garmin_sync_state` unchanged since
the sync (nothing else is touching it); watchdog `overall` not worse than it was; `tunnel.state`
`UP` with an unchanged `startedAtUtc`; no `GARMIN_*` hint in `watchdog-status.json`.

### 3.2 Enabling the scheduler

```powershell
# .env on the production machine
RUNNING_AI_GARMIN_SCHEDULER_ENABLED=true
```

Then restart Spring through the normal path (`release-spring-jar.ps1` is not needed; a restart is).

`running-ai.garmin.scheduler.enabled` is the only switch in `application.yml` with a literal
`false` and no `${ENV:default}` placeholder. The environment variable still wins (systemEnvironment
outranks YAML, with relaxed `_`↔`.`/`-` binding) and `GarminSyncSchedulerWiringTest` asserts exactly
that — but **in production the only proof it bound is a `Garmin scheduled sync` line in
`spring.out.log` within ~1 minute of startup**. No line within 3 minutes ⇒ revert the `.env` line
and investigate; do not assume it is on.

Cost once on: `fixed-delay: 1h`, one Garmin request per tick ⇒ **≤ 24 reads/day**, plus the 1/day
profile sync that is already enabled.

### 3.3 What now protects it

| Failure | Threshold | Effect |
|---|---|---|
| AUTH (401/403) | 3 | automatic sync stops, **no expiry** — only a completed attempt clears it |
| RATE_LIMIT (429) | 1 | 6 h cooldown |
| TRANSIENT | 8 | 2 h cooldown |
| PARTIAL | 6 | 12 h cooldown (a PARTIAL is not a failure, but it holds the checkpoint back forever) |
| Any tick | — | `minimum-interval` 15 m is a hard floor on automatic request frequency |

There is no flag to reset by hand: a manual `POST /api/v1/garmin/sync` is never blocked, and a
completed attempt is what lifts an auth block.

`GarminActivityRegistry` makes the **scheduler** defer while a recovery sync is in flight. It is
consulted by `GarminSyncCircuitBreaker` alone, so it does **not** stop a *manual* activity sync from
running alongside a recovery backfill — that remains an operator rule, not a guarantee.

### 3.4 Second 24-hour observation

Expect ~24 ticks. Pass criteria: `consecutive_failure_count` and `consecutive_partial_count` both
`0`; `lastAttempt.outcome` terminal; `automaticSync.allowed` still `true`; `data-freshness`
`collectionAgeHours` staying small; no duplicate activities.

Stop immediately on one `GARMIN_AUTH_REQUIRED`/`GARMIN_FORBIDDEN`/`GARMIN_RATE_LIMITED`, or on
`consecutive_failure_count >= 3`: set `RUNNING_AI_GARMIN_SCHEDULER_ENABLED=false` and restart.

Throughout both windows, 2.7 continues to apply — with the scheduler on, a `STARTED` row can exist
for the duration of any tick, so the V28 rollback is only free between ticks.

---

## 4. Recovery backfill — approval required

### 4.1 What is actually missing

Nine days in the 28-day window are worth fetching, from the live coverage report:

```
2026-10-04, 2026-10-06, 2026-10-07, 2026-10-08, 2026-10-09,
2026-10-10, 2026-10-13, 2026-10-14, 2026-10-15
```

Eight are `NOT_COLLECTED` — **no connector call was ever made for them**, which is not the same as
"Garmin has nothing". One (2026-10-06) is `PARTIAL`: resting HR, Body Battery and stress are stored,
HRV and sleep are not, and it is re-fetched because Garmin can fill a metric in later.

### 4.2 Cost and behaviour

| | |
|---|---|
| Connector calls | **9** (one per target day) |
| Garmin requests | ~45 (the connector fetches five metric groups per day) |
| Pauses | 8 × 2 s — the pause follows a *fetch*, never a skip, so the 19 covered days cost nothing |
| Order | **newest → oldest**, so the most decision-relevant days land first if the run is cut short |
| Overwrites | none — the upsert never replaces a stored value with a missing one |
| On failure | first-day failure throws; a later failure returns `completed=false` with `stoppedAt` and `stoppedReason`, keeping everything already fetched |

### 4.3 Execute

```powershell
Invoke-RestMethod -Method Post -ContentType 'application/json' `
  -Uri http://127.0.0.1:18080/api/v1/garmin/recovery-sync/backfill `
  -Body '{"days":28,"onlyMissing":true}'
```

`onlyMissing: true` is required. Its default is `false`, which would re-fetch all 28 days — 28
connector calls and ~140 Garmin requests for no new data.

Do not run this while an activity sync is in flight (3.3).

### 4.4 Verification — and the mistake to avoid

```powershell
Invoke-RestMethod http://127.0.0.1:18080/api/v1/garmin/recovery-sync/coverage | ConvertTo-Json -Depth 4
```

Success is **`NOT_COLLECTED` reaching 0**, not every day reaching `COMPLETE`.

After the run each of the nine days must be one of:

* `COMPLETE` — all five metrics stored;
* `PARTIAL` — some stored, and the rest genuinely absent at Garmin;
* `CONFIRMED_NO_DATA` — Garmin was asked and had nothing (the watch was off, or not worn);
* `COLLECTION_FAILED` — the call failed and was recorded as such.

`PARTIAL` and `CONFIRMED_NO_DATA` are **correct outcomes**. A day the athlete did not wear the watch
has no HRV, and no amount of re-fetching will create one. Judging this run by "all 28 days COMPLETE"
would mean treating a real absence as a defect — and the one thing that must never happen is filling
an absent measurement with `0` or any other invented value.

Then re-check that the coach actually sees it:

```powershell
Invoke-RestMethod "http://127.0.0.1:18080/api/v1/recovery-context"
Invoke-RestMethod "http://127.0.0.1:18080/api/v1/coach/training-context?version=V2"
```

Expect `dataCoverage.recoveryDaysAvailable` above its current 20, and the `recovery.*.ageDays`
values (currently 3) to drop.

---

## 5. On-watch verification — the owner, once

Everything below needs the physical FR265 and cannot be done from this machine. It is deliberately
the last stage and is requested once.

Before asking, make sure there is something to see: a plan only reaches the watch when a draft for
**today** is approved. `GET /api/v1/today-workout` currently answers `NO_PLAN` for 2026-10-15.

| # | Check | Expected | Why it is not already proven |
|---|---|---|---|
| 1 | Watch face TODAY row with an approved non-REST draft | type + metric, e.g. `EASY 40M` | the relay builds the label from structure; only the device proves the pixel font renders it |
| 2 | TODAY row on an approved REST day | `REST` | REST is a decision that is never published to Intervals, so only the approval path can carry it |
| 3 | TODAY row with no approved draft | the face's existing no-plan rendering | `NO_PLAN` must not look like an error |
| 4 | Date agreement | the row matches the watch's own date | the relay discards a `stale` or wrong-date answer; this confirms the watch agrees |
| 5 | Relay unreachable (stop the relay briefly) | the face degrades as it always did | a backend outage must never blank the face |
| 6 | Workout actually on the watch | the scheduled workout appears in the watch's workout list after a Garmin sync | **this is the only step that can prove device delivery at all** |

On #6 — keep the four levels apart, they are not the same claim:

1. **Intervals publish** — measured. Draft 17 reports `intervalsDelivery: PUBLISHED_AND_VERIFIED`,
   event 140511724.
2. **Garmin Calendar** — measured. `GET /scheduled-workouts?year=2026&month=10` returned 17
   calendar items including one `workout`: *"RunningAI workout"*, `running`, 2026-10-16,
   `workoutId` 1729422156. So Intervals.icu's own Garmin integration demonstrably delivers.
3. **Device sync** — **not observable from here, ever.** `deviceDelivery` is permanently `UNKNOWN`
   and there is no Garmin write path in this repo (a connector test parses the AST and fails the
   build if `schedule_workout` / `upload_*_workout` / `push_workout_to_device` ever appears).
4. **Seen on the watch** — only the owner can confirm.

A `GARMIN_NOT_FOUND` from `delivery-status` is **not a failure**: Garmin's calendar returns only
committed workouts, so an older or unpublished date legitimately has none. Draft 17 (2026-10-05)
returns exactly that, with `garminScheduledWorkoutCount: 0`, and nothing is wrong.

---

## 6. If something goes wrong

1. Stop. Do not start the next stage.
2. Preserve state — do **not** restart Spring, clear a lock, or truncate a table to "reset".
3. Capture: `.runtime\logs\spring.out.log`, `watchdog.log`, `watchdog-status.json`,
   `GET /api/v1/garmin/sync/status`, `GET /api/v1/data-freshness`, and
   `select * from garmin_sync_attempt`.
4. Prefer rolling **forward**. The V30 jar reads every state the V28 one does; the reverse is not
   true (2.7).
5. Only then consider the jar swap, against 2.7's table.
6. Restoring the database needs its own approval and loses every write since the backup.

Never, as a recovery step: `DELETE` from an audit ledger, `flyway repair` on production, or
`Disable-ScheduledTask` on the watchdog.
