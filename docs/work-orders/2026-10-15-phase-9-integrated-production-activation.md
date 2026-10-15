# Phase 9 — integrated production activation

Execution record for the staged activation described in
`2026-10-15-phase-9-production-runbook.md`. One line per thing actually done, with the evidence
that decided whether the next stage was allowed to start.

**Status: IN PROGRESS.** Stages are appended as they complete. A stage that has not run says so.

| Stage | What | Status |
|---|---|---|
| 1 | Deploy Phase 9 code, normalise both scheduled tasks | — |
| 2 | One manual Garmin sync | — |
| 3 | First 24 h observation | — |
| 4 | Enable the Garmin scheduler | — |
| 5 | Second 24 h observation | — |
| 6 | Recovery backfill (`onlyMissing`) | — |
| 7 | Final AI-coach / MCP verification | — |
| 8 | FR265 on-watch checklist (owner) | — |
| 9 | Git reconciliation | — |

---

## Baseline measured before anything was touched (2026-10-15 16:30 KST)

| Fact | Value |
|---|---|
| Production jar | `0fce192`, SHA-256 `091971eb…`, PID 29204, started 12:31 |
| Flyway head | V30, 32 tables |
| `activity` / `activity_raw` | 48 / 10 |
| `garmin_sync_attempt` | 0 rows |
| `workout_draft_publish_attempt` | 0 rows |
| `garmin_recovery_daily` / `workout_draft` / `injury_report` | 21 / 19 / 2 |
| `garmin_sync_state` | high-water `2026-10-10T22:39:40Z`, last success `2026-10-12T03:24:44Z` |
| Recovery coverage (28 d) | COMPLETE 19, PARTIAL 1, NOT_COLLECTED 8 |
| Circuit breaker | `allowed: true`, `ALLOWED` |
| Git | `main` = `origin/main` = `78306e4`; `phase-9-operations` 3 commits ahead, FF-able |
| Processes | Spring 29204, gateway 28524, relay 19828, cloudflared 18220 (started 2026-10-14 20:09) |

---

## Stage 1 — deploy Phase 9 and normalise the scheduled tasks — PASSED (16:31–16:48 KST)

### 1.1 Backup, and proof it restores

`.runtime\backup\running_ai-prod-PRE-P9-20261015-163119.dump` + the pre-release jar
(`running-ai-server-PRE-P9-0fce192.jar`). The dump was restored into a throwaway database
`running_ai_restorecheck` and reported **48 activities, Flyway 30** — the backup is not assumed to
work, it was made to work once before anything else ran.

### 1.2 Release

`main` fast-forwarded `78306e4` -> `a1ccf93` in the production checkout (merge-base equalled `main`,
so it was a true FF; nothing was rewritten and no uncommitted work existed).

**The runbook's step 1.3 was wrong in two ways and is corrected here:**

* `release-spring-jar.ps1 -Build` — there is no `-Build` switch. The script builds by default;
  `-SkipBuild` and `-VerifyOnly` are the real controls, and `-ExpectedMaxMigration 30` should be
  passed so a jar built from the wrong worktree cannot reach production.
* **A release cannot replace the jar while Spring is running.** The script's own header says a
  release "only decides what the NEXT start runs", but on Windows the live JVM holds
  `.runtime\running-ai-server.jar` open, so the final `Move-Item` fails with
  `Cannot create a file when that file already exists`. This was observed, not reasoned about.
  It failed **safely**: the old jar, its sidecar and the running process were all untouched and
  `/actuator/health` stayed `UP` throughout — the only residue was a staged `.jar.new`.

  The script's own documented remedy is the right one: **stop -> release -> start**. Executed as one
  chained command to keep the gap small, between two watchdog ticks:

  ```
  16:35:29  relay/Spring/connector stopped (graceful; PostgreSQL left running)
  16:35:37  released f32d4182... from main@a1ccf93, migrations up to V30
  16:36:02  runtime back up (Spring 18080 PID 27672, relay 17845 PID 37400)
  ```

  Total production downtime **33 s**. The watchdog was never disabled (rule 0.1).

### 1.3 Verification

| Check | Expected | Measured |
|---|---|---|
| Jar identity | `a1ccf93`, migrationCount 30 | `a1ccf93`, SHA `f32d4182...`, 30 OK |
| Health | UP | UP OK |
| Flyway head | 30 (unmoved) | 30 OK |
| `activity` / `activity_raw` | 48 / 10 | 48 / 10 OK |
| `garmin_sync_attempt` | 0 | 0 OK |
| `workout_draft_publish_attempt` | 0 | 0 OK |
| Injury gate | `nonRestTrainingAllowed: true`, `INJURY_RESOLVED_CONFIRMED` | OK |
| Injury order | latest newer than previous | `...55.183427Z` > `...54.674574Z` OK |

The injury check is the one that actually exercises the Phase 9 code change: those two rows are
**0.5 s apart** on the same `reported_at`, which is precisely the pair `recorded_at DESC` alone
could order either way. Production now resolves it deterministically.

**API regression, 24 calls:** 21 endpoints returned 200 (health, activities, intensity-profile,
training-load/state/decision-context, workout-recommendation/prescription/intensity-targets,
recovery-context, intervals fitness, data-freshness, sync status, recovery coverage, today-workout,
training-analysis-report, publish-attempts, delivery-status, coach context V1 and V2); an unknown
path returned **404**; `PUT`/`POST` on a GET-only route returned **405 with `Allow: GET`** —
Phase 8B(B) behaviour intact.

**Test suites, re-run on this commit (not from cache — the first run was `UP-TO-DATE` and proved
nothing):** Spring `cleanTest test` -> **1597 tests, 0 failures, 0 errors, 0 skipped** (7m10s).
`Test-Watchdog.ps1` and `Test-WatchdogRecoveryPolicy.ps1` both pass on the production checkout,
including every new A-1 (two-trigger) and A-2 (gateway/tunnel) case.

### 1.4 The two scheduled tasks

Both dry-run first, then registered. Measured before -> after:

| | `RunningAI-Watchdog` | `RunningAI-McpGateway` |
|---|---|---|
| Triggers | 2 -> 2 (the 8B recovery trigger is now the *canonical* shape, not a leftover) | **1 -> 2** |
| `NextRunTime` | armed -> armed | **empty -> armed** |
| `ExecutionTimeLimit` | PT30M (unchanged) | **PT72H -> PT0S** |

`PT72H` was Windows being told to kill a healthy gateway every three days. That is now removed.

**The gateway was not interrupted by its own re-registration** — PID 28524 before and after, port
17910 still listening. Nothing read or wrote `.runtime\mcp-gateway.cmd`, so the OAuth and allowlist
configuration is byte-identical and the production port is unchanged.

### 1.5 Supervision proved by observation, not by `State`

Unattended ticks (rule 0.2) — nobody started the task:

```
baseline 2026-10-15T07:34:14Z   (pre-Phase-9 shape: 5 components)
tick 1   2026-10-15T07:43:24Z   (first Phase 9 tick: 7 components)
```

The first Phase 9 tick is where the new observability appears:

```
components.mcp-gateway  UP
components.tunnel       UP
tunnel.processId        18220
tunnel.startedAtUtc     2026-10-14T11:09:16Z
tunnel.recoverable      false
```

`recoverable: false` is the whole point of the tunnel being an *observed* component: restarting a
Quick Tunnel mints a new hostname and would silently invalidate the Worker's deployed
`ORIGIN_MCP_URL`. The watchdog can therefore report it degraded but must never fix it.

Independently confirmed serving, not merely "a process exists": the gateway answered an
unauthenticated `POST /mcp` with **401 + a correct `WWW-Authenticate` Bearer challenge**, and the
relay answered `/health` with `{"status":"ok"}`.

---

## Stage 2 — one manual Garmin sync — PASSED (16:49 KST)

### 2.1 Pre-conditions

Stage 1 passed; `recoveryBusy: false`; the backup was already proven restorable (1.1). Garmin auth
was checked **before** spending the sync, with a read-only connector call:
`GET /scheduled-workouts?year=2026&month=10` -> **200 in 566 ms**. Failing a cheap probe is better
than burning the sync and writing a `FAILED` row.

### 2.2 Result — one request, one row

```
POST /api/v1/garmin/sync   -> 1 s
fetched=10 created=0 updated=8 skipped=2 failed=0 pagesFetched=1 checkpointAdvanced=false
```

The connector log shows the predicted cost exactly:

```
2026-10-15 16:49:20  GET /activities start=0 limit=50 -> 10 item(s)
```

**One** Garmin activity request. Ledger: exactly **1** row, `outcome=SUCCESS`,
`consecutive_failure_count=0`, `consecutive_partial_count=0`, no failure code.

### 2.3 The two results that look like problems and are not

**`checkpointAdvanced=false` on a SUCCESS.** The runbook lists "did not move on a SUCCESS" as a
stop condition. It is not one here, and the runbook is too strict as written. The high-water mark
is the newest fetched activity's start time, and the newest GARMIN activity in the database is
`24679734836`, `started_at = 2026-10-10 22:39:40+00` — **identical to the high-water mark**. With
`created=0`, Garmin returned nothing newer than what is already stored, so the checkpoint *cannot*
advance. The athlete has not run since 2026-10-11. The correct stop condition is "did not move on a
SUCCESS **that created new activities**".

**`skipped=2`** is not a PARTIAL. Both skipped items (`24449361556`, `24438659533`) logged
`UNSUPPORTED_GARMIN_ACTIVITY_TYPE` with the raw payload preserved. An unsupported type will never
become mappable, so holding the checkpoint back for it forever would be wrong — which is exactly
why the service records it as `skipped` and the outcome as `SUCCESS` rather than `PARTIAL`.

### 2.4 Integrity

| Check | Result |
|---|---|
| `activity` count | 48 -> 48 (never decreased; `created=0`) |
| Duplicate `(external_source, external_id)` | **0 groups** |
| `activity_raw` | 10 -> 10 (raw payloads replaced in place, rawIds 1-8 reused) |
| Rows whose `updated_at` moved | **0** — the 8 "UPDATED" items were byte-identical, so no column changed |
| `garmin_sync_state.last_successful_sync_at` | 2026-10-12T03:24:44Z -> **2026-10-15T07:49:20Z** |
| `garmin_sync_state.high_water_started_at` | unchanged, correctly (above) |

`data-freshness` now carries what 8A built it for — `GARMIN_ACTIVITY.lastAttemptOutcome: SUCCESS`,
`collectionAgeHours: 0`, `consecutiveFailureCount: 0`, `lastFailureCode: null`. Before this sync
every one of those was `null`, which is the exact ambiguity ("nothing new" vs "nothing ran" vs
"it failed") Phase 8A exists to remove. `latestDataDate` stayed 2026-10-11 — correct, since nothing
new was created.

`GARMIN_RECOVERY` still reports `lastAttemptAt: null`: no recovery collection has run since V28
shipped. That is the gap stage 6 closes.

### 2.5 The breaker's floor, observed

Immediately after the sync, `automaticSync` became
`allowed: false, reason: MINIMUM_INTERVAL` — the 15-minute hard floor on **automatic** request
frequency. This is correct and is evidence the breaker works: it governs `GarminSyncScheduler`
only, and a manual `POST /api/v1/garmin/sync` is never blocked by it.

### 2.6 V28 rollback compatibility right now

Per runbook 2.7, the single attempt row carries **`SUCCESS`**, which is in the pre-8B enum.
**A rollback to the V28 jar is therefore safe at this moment.** It stops being free the instant a
sync is in flight (`STARTED`) or was interrupted (`INTERRUPTED`) — including every scheduler tick
once stage 4 is done. `DELETE FROM garmin_sync_attempt` is not a rollback step.

---

## Stage 3 — first 24-hour observation — IN PROGRESS

**Window: 2026-10-15 16:49 KST -> 2026-10-16 16:49 KST.** Opened by the stage 2 sync. This is a
real elapsed-time requirement and is not reported as passed before it has actually elapsed.

T+0 snapshot: `.runtime\p9-observation\snapshot-T0-20261015-165110.json`

```
watchdog.overall      UP            checkedAt 2026-10-15T07:48:23Z
components            docker UP  postgres UP  connector UP  spring UP
                      external-relay UP  mcp-gateway UP  tunnel UP
tunnel.startedAtUtc   2026-10-14T11:09:16Z
lastAttempt.outcome   SUCCESS
highWaterStartedAt    2026-10-10T22:39:40Z
lastSuccessfulSyncAt  2026-10-15T07:49:20.474868Z
```

**No new collector was installed for this window, deliberately.** Everything stage 3 has to prove
is already recorded durably by components that were running anyway:

| Question at T+24 h | Answered by |
|---|---|
| Did anything else sync? | `garmin_sync_attempt` — append-only, so a second row cannot hide |
| Did anything touch the checkpoint? | `garmin_sync_state.updated_at` |
| Was supervision continuous? | `watchdog.log` (appends every 5 min) + `watchdog-status.json` |
| Did any component flap? | `restartBudget.used`, `longTermLockout`, `lastAction` |
| Did the tunnel restart? | `tunnel.startedAtUtc` — a change means `ORIGIN_MCP_URL` went stale |
| Any errors? | `spring.out.log`, `spring.err.log` |

Adding a scheduled collector would have been a new, unapproved standing task on the production
machine to record what the machine already records.

### Pass criteria (runbook 3.1)

* `garmin_sync_attempt` still exactly **1** row, still terminal
* `garmin_sync_state` unchanged since 07:49:20Z
* watchdog `overall` no worse than `UP`, `lastAction: NONE`, no restarts consumed
* `tunnel.state: UP` with `startedAtUtc` **unchanged**
* no `GARMIN_*` hint in `watchdog-status.json`
* `activity` still 48, still no duplicates

### Stop conditions

Any `GARMIN_AUTH_REQUIRED` / `GARMIN_FORBIDDEN` / `GARMIN_RATE_LIMITED`, a second unexplained
attempt row, a decrease in `activity`, a duplicate `(external_source, external_id)`, loss of
watchdog supervision (`NextRunTime` empty on either task), or a changed `tunnel.startedAtUtc`.

Stages 4-8 have **not** started and nothing about them is prejudged here.
