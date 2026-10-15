# Integrated Release — RunningAI Core Feature Completion (Phase 8B)

- Date: 2026-10-15 (measurements 10:51–11:17 KST)
- Branch: `phase-8b-core-completion`, base `b1feda7`, **release candidate `e2bd1e0`** (+ this record)
- Production checkout: `C:\running-ai-github`, Spring `127.0.0.1:18080`, Flyway head **V28**, jar `f1451f5`
- Status: **development verification complete / STOP before production**

**Nothing was changed in production and nothing was written outside this repository.**
Zero Garmin writes, zero Intervals.icu writes, zero production DB writes, zero deploys,
zero pushes, zero OAuth changes. The only production contact was read-only: `SELECT` on the
production database, and `GET` against loopback `18080`. Every rehearsal ran on throwaway
databases (`running_ai_test`, `ra_rel_v30`), both dropped afterwards.

---

## 1. What this release contains

Eleven commits on top of `b1feda7`: the eight that were already there, plus three from this
review. Six independent workstreams, none of which changes an existing contract.

| # | Workstream | Commit | What it does |
|---|---|---|---|
| A | Garmin sync circuit breaker | `2f9d65d` | Turns 8A's unread failure ledger into a decision. Governs the **scheduler only**. V30 adds `STARTED`/`INTERRUPTED` and `consecutive_partial_count`. |
| B | HTTP status correctness | `2fb0e06` | Wrong verb → **405 + `Allow`**, missing `@RequestParam` → **400**, both WARN with no stack. Used to reach the catch-all as 500s. |
| C | Publish attempt ledger | `a62582c` | V29 `workout_draft_publish_attempt`, append-only. `workout_draft_publication` stays success-only. Drives no retry. |
| D | Approved plan reaches the watch | `02698f2` | `GET /api/v1/today-workout` reports the **approval**, so an approved plan — and a REST day — reaches the watch before any publish. Relay asks it first, keeps both old sources as fallbacks. |
| E | Delivery readback | `77d712b` | Connector `GET /scheduled-workouts` + `GET /api/v1/workout-drafts/{id}/delivery-status`. Read-only; no Garmin write path exists or may be added. |
| F | What was never measured | `35029ce` | `MetricAvailability` + `GET /api/v1/training-analysis-report`. Measures, never judges. |
| — | Lifecycle test | `c08b574` | Pins the whole chain and what it does at each handover when it fails. |
| — | Record | `3f28073` | Phase 8B work order. |
| **R1** | **Review fix** | **`d8abd08`** | **Lap availability is asked of the data, not the source.** |
| **R2** | **Review fix** | **`f7e4876`** | **Publish single-flight is released on every exit.** |
| **R3** | **Review fix** | **`e2bd1e0`** | **Deployed Worker diagnostics committed; volatile tunnel hostname untracked.** |

---

## 2. Defects found and fixed

Three. Two are behavioural defects in the Phase 8B code, one is a Git-integrity defect.

### R1 — the coach was told 25 of 48 activities had no lap data, and they all do

`MetricAvailability` decided every unavailable metric from `external_source` alone, on the
stated grounds that these absences are properties of the import.

That reasoning holds for four of the five metrics. It fails for `LAP_STRUCTURE`, because a
**second importer changes the answer after the fact**: the Strava archive import (7E-2) writes
laps *into* activities imported from Intervals.icu, so one row goes from having no lap
breakdown to having one without its `external_source` ever changing.

Measured on the production database:

```
 external_source | activities | with_laps | strava_marked_laps | laps_with_step_index
 GARMIN          |          8 |         8 |                  0 |                   87
 INTERVALS_ICU   |         25 |        25 |                 67 |                    0
 STRAVA          |         15 |        15 |                  4 |                    0
```

Every one of the 25 Intervals-sourced activities carries laps, 67 of them written by the Strava
importer — and every one was being reported to the coach as having no per-lap data at all. A
feature built so an absent metric would not read as a low one was itself asserting an absence
that was not there.

Second, smaller miss in the same table: **neither** importer writes `activity_zone` — Intervals
because its 7-zone model is not mapped onto Garmin's 5, Strava because the archive has no zone
table — but `HEART_RATE_ZONES` was listed for Intervals only. All 15 Strava activities have no
stored zones (verified) and the coach was not told why.

Fixed: lap presence is asked of the rows in one batched query per window
(`ActivityLapRepository.findActivityIdsWithLaps`, exposed through `ActivityDetailStore`), and
`HEART_RATE_ZONES` was added to the Strava set. The correction also works in the other
direction — a Garmin activity whose per-activity detail has never been collected has no laps
either, and now says so instead of staying silent.

Net effect on production data: the false `LAP_STRUCTURE` claim disappears; the Strava zone
absence appears. Nothing else changes, because all 48 activities have laps.

### R2 — a draft could become permanently unpublishable

Adding the attempt ledger split `publish()` into two `try` blocks so the approval id would be in
scope for the recorder. The first one caught `RuntimeException` and removed the in-flight entry
by hand; only the second kept a `finally`.

A `finally` releases on everything thrown. A `catch (RuntimeException)` does not. An `Error` out
of `loadApproved` left the draft id in the in-flight set permanently, so every later publish of
that draft answered `DRAFT_PUBLISH_ALREADY_RUNNING` until the JVM restarted — a draft that can
never be published again, from a failure that had nothing to do with publishing.

Fixed by carrying the approval id in a nullable variable and restoring the single `finally`. The
recording rule is unchanged: nothing is recorded until an approved draft has actually been
loaded.

### R3 — production Worker code existed only in a dirty worktree

Two uncommitted changes had been live on the production Cloudflare Worker since 2026-10-14:

- `src/index.js` — the two log lines that settled Phase 7F-3B-3 (nothing recorded what the
  *client* asked for at `/authorize`, so "ChatGPT requested read-only" and "ChatGPT sent no scope
  parameter" were indistinguishable afterwards). **Committed as-is**; it matches the deployed
  Worker and the no-secret-in-logs checks already cover the new lines.
- `wrangler.jsonc` — a cloudflared Quick Tunnel hostname. That is the part that does not belong
  in version control: the hostname is reissued on every cloudflared restart, so a committed one
  is false by the next restart. Git has carried a dead hostname since Phase 7D.

**Committed value is now `https://origin.invalid/mcp`** (RFC 2606, cannot resolve — an
unconfigured Worker answers 502 rather than reaching something unexpected). The live address is
supplied per deploy:

```
npx wrangler deploy --var ORIGIN_MCP_URL:"https://<current-tunnel>/mcp"
```

⚠ **This commit is not deployed.** Deploying it *without* `--var` points the Worker at
`origin.invalid` and breaks the ChatGPT connector. See §7, approval item 3.

The permanent fix is a named tunnel or the Tailscale Funnel hostname this machine already has,
which would make the value stable enough to commit. That is its own task, not this one.

---

## 3. Review findings that are correct as built (no change made)

| Area | Verdict |
|---|---|
| **Breaker vs manual sync** | Correct. `GarminSyncCircuitBreaker` is injected into `GarminSyncScheduler` and `GarminSyncStatusService` only; `GarminSyncOperationService.runSync()` never consults it. A manual sync IS the intervention being waited for, and a completed attempt is what clears an AUTH block, so there is no flag to reset by hand. |
| **Breaker during a STARTED run** | Safe. A `STARTED` row bypasses the failure block (it is not `FAILED`), but `GarminSyncOperationService`'s `ReentrantLock` rejects the concurrent tick, and the real outcome overwrites `STARTED` immediately after. |
| **`INTERRUPTED` never moves a streak** | Verified in `GarminSyncAttempt.recordInterrupted`: it touches neither `consecutiveFailureCount` nor `lastFailureAt`/`lastFailureCode`, so a watchdog restart is never a step towards stopping collection. |
| **Garmin month indexing** | Verified against the installed library source: `get_scheduled_workouts(year, month)` takes a **1-based** month and does the `month - 1` itself. The connector passes 1-based. Correct. |
| **`calendarItems` / `itemType == "workout"`** | Confirmed to be the library's own usage (`get_next_scheduled_workout`). `title`, `sportTypeKey`, `workoutId` are read defensively as nullable and are **not** confirmed. STATIC_SOURCE_CONFIRMED is an accurate label. |
| **No Garmin write path** | Connector AST test fails the build if `schedule_workout` / `upload_*_workout` / `push_workout_to_device` appears, and asserts every route is a GET. 154/154 pass. |
| **Ledger transaction safety** | Every write is `REQUIRES_NEW` and every recorder swallows its own failure, so a full disk cannot turn a successful publish into an error, and a rolled-back caller cannot erase the record of its own failure. `ck_..._failure` keeps a failure from being stored without saying what failed. |
| **Relay wire contract** | `{date, status, label}` unchanged — the Phase 8B change is entirely inside `resolveToday()`. RunningAI is tried first, failures are caught and stepped over, and `config.spring_base_url: ""` restores pre-8B behaviour exactly. 31/31 pass. |
| **Coach free text to the watch** | `plan.workoutType` is a `String` the validator only checks for non-blankness, so the server side is weaker than "structure only" implies. It does not reach the watch: the relay maps it through the `PLAN_TYPE_LABELS` allowlist (unknown → `RUN`) and `sanitizeLabel` clamps to the pixel font's charset and 14 glyphs. Noted, not changed. |
| **Auth posture of the new endpoints** | `/api/v1/today-workout` and `/delivery-status` have no auth, matching every other operational API. Verified that production Spring binds `127.0.0.1:18080` only (`SERVER_ADDRESS=127.0.0.1`), so no new exposure. |
| **Injury gate** | Untouched by Phase 8B. `WorkoutSafetyGateService.requireAllowed` still runs immediately before the Intervals call, inside the publish path. Regression covered by `WorkoutSafetyPolicyTest`, `WorkoutDraftSafetyGateTest` and `LifecycleIntegrationTest`'s "with no injury report at all nothing non-REST can be approved or published". |
| **New defaults** | `circuit-breaker.enabled=true` is the only new switch. It governs the scheduler, which is **off** in production, so enabling it changes no production behaviour. Thresholds are deliberately generous (AUTH 3 / RATE_LIMIT 1 / TRANSIENT 8 / PARTIAL 6). `false` restores pre-8B behaviour exactly. |

### Residual risks accepted, not fixed

1. `GET .../delivery-status` defaults to `checkGarmin=true`, so each call spends one Garmin
   request, and it neither registers with `GarminActivityRegistry` nor consults the breaker. It is
   a manual, loopback-only endpoint; `?checkGarmin=false` answers from the database alone.
2. `REQUIRES_NEW` on the ledger writes takes a **second** connection while the caller's
   transaction is suspended. Harmless at the production pool size; do not drop
   `hikari.maximum-pool-size` below 2.
3. `GarminActivityRegistry` is in-memory and advisory. A wrong answer costs one skipped automatic
   tick. By design.

---

## 4. Test results

Everything below was run on this branch at `e2bd1e0`. No Garmin or Intervals.icu write occurred.

| Suite | Result |
|---|---|
| Spring, H2 (`gradlew clean test`) | **1596 / 1596 PASS**, 0 failures, 0 errors, 0 skipped, 159 classes |
| Spring, **PostgreSQL 17.11** (throwaway `running_ai_test`, `hikari.maximum-pool-size=2`) | **1596 / 1596 PASS**, 0 failures, 0 errors, 0 skipped |
| Flyway **V1 → V30** on empty PostgreSQL 17 | **30 / 30 applied, 0 failures**; `workout_draft_publish_attempt` created, `consecutive_partial_count NOT NULL DEFAULT 0` present |
| Garmin connector (pytest) | **154 / 154 PASS** |
| External relay (`node --test`) | **31 / 31 PASS** |
| MCP Worker (`npm run test:all`) | **49 / 49 PASS** |
| MCP Gateway (`npm test`) | **46 / 46 PASS** |
| Production jar rehearsal (`release-spring-jar.ps1 -VerifyOnly`) | **PASS** — valid boot jar, 102 dependency jars, **30 migrations, highest V30**. Released jar NOT touched. |

Baseline before the review fixes was 1589; the three fixes added 7 tests.

### Scope and gate regressions specifically requested

- **Read / Train / publish blocking** — gateway 46/46 includes: scopes are not hierarchical
  (train alone cannot publish → 403, not proxied); a train-only token cannot even list; a foreign
  subject is refused however many scopes it carries; `publish_workout` stays unreachable with
  every scope; an allowlisted `publish_workout` **refuses to start**; JWKS outage → 503, never a
  pass, and the backend saw nothing. Worker 49/49 includes: asking for read grants read only;
  train only when explicitly requested; `runningai:publish` is **not** granted; unknown scopes
  dropped.
- **Injury safety gate** — covered as described above; `LifecycleIntegrationTest` proves it
  across the whole chain, not only at one entry point.
- **Integrated create → approve → relay simulation** — `LifecycleIntegrationTest` (8 tests, all
  PASS): "collection through approval to publish, and the watch and delivery check agree with
  it"; "an approved rest day reaches the watch as REST and is never sent anywhere"; "a failed
  publish leaves no publication, a recorded attempt, and a retryable draft"; "a Garmin
  authentication failure is recorded and leaves the checkpoint untouched"; "an AI coach failure
  produces no draft at all"; "the collection and publish paths are both single-flight across the
  whole flow".

---

## 5. Rollback compatibility — measured, not assumed

The instruction asks whether the previous JAR can run after V29/V30, rather than rolling the
database back. This was tested directly: the V30 schema was cloned into a throwaway database and
the **current production jar** (`f1451f5`, maxMigration 28) was started against it.

| Test | Result |
|---|---|
| V28 jar starts against a V30 schema | ✅ `Started RunningAiApplication in 9.663 seconds` — Flyway does not reject the two future migrations, Hibernate `validate` accepts the extra table and the extra column |
| V28 jar reads `SUCCESS` / `PARTIAL` / `FAILED` attempt rows | ✅ `GET /api/v1/garmin/sync/status` → 200 with the full attempt body |
| V28 jar reads an **`INTERRUPTED`** attempt row | ❌ **HTTP 500** — `IllegalArgumentException: No enum constant GarminSyncAttemptOutcome.INTERRUPTED` |
| V28 jar reads a **`STARTED`** attempt row | ❌ **HTTP 500**, same cause |
| After `DELETE FROM garmin_sync_attempt WHERE outcome IN ('STARTED','INTERRUPTED')` | ✅ back to 200 immediately |

> **CORRECTED 2026-10-15** — see `2026-10-15-phase-8b-release-stabilization.md` section 3, which
> re-measured this on a restored production copy and found two things this table gets wrong:
> (1) **`GET /api/v1/data-freshness` fails identically**, because V28's `DataFreshnessService` also
> reads the attempt row — so a rollback that only checks `/garmin/sync/status` can still be broken;
> (2) the pre-step below is **not** mandatory and **not** free. It is unnecessary whenever no sync
> has run under V30 (production's `garmin_sync_attempt` currently holds **0 rows**), roll-forward
> fixes the same condition with nothing removed, and the DELETE itself destroys the athlete's
> whole attempt record rather than a log line. The replacement procedure is Step R below.

**This makes one pre-step mandatory on rollback, and it is not optional.** `STARTED` is written
before every sync — manual included — and `INTERRUPTED` survives until the next attempt. Worse
than the status endpoint: in the V28 code `runSync()` builds its response from
`statusService.getStatus()` *outside* the swallowing `record(...)` wrapper, so a manual sync on a
rolled-back jar would ingest activities and then answer 500, with the ledger write swallowed.

The pre-step loses nothing that matters: `garmin_sync_attempt` is pure observability, one row per
athlete, and "no row" is a state the V28 code already handles (it is the pre-8A state). It never
touches `garmin_sync_state`, so no checkpoint moves.

`workout_draft_publish_attempt` (V29) poses no rollback problem: the old jar does not map it, and
Hibernate `validate` ignores unmapped tables.

---

## 6. Production deployment runbook

Each step proceeds **only** if the previous one passed. The database is never rolled back; the
jar is.

### Step 0 — baseline (read-only)

```
git -C C:\running-ai-github rev-parse HEAD
Get-Content C:\running-ai-github\.runtime\running-ai-server.jar.json
powershell -File scripts\windows\status-running-ai.ps1
curl http://127.0.0.1:18080/api/v1/health
curl http://127.0.0.1:18080/api/v1/garmin/sync/status
curl http://127.0.0.1:18080/api/v1/data-freshness
docker exec running-ai-postgres psql -U running_ai -d running_ai -c "SELECT max(version::int) FROM flyway_schema_history WHERE success;"
docker exec running-ai-postgres psql -U running_ai -d running_ai -c "SELECT external_source, count(*) FROM activity GROUP BY 1;"
```

Record all of it. **Expected at the time of writing:** head `b1feda7`, jar `f1451f5`
(maxMigration 28), Flyway V28, 48 activities (8 / 25 / 15), `lastSuccessfulSyncAt`
2026-10-12T03:24Z, `lastAttempt` null.
**PASS:** every service UP, Flyway head is exactly 28, no attempt row.

### Step 1 — backup and prove the restore

```
docker exec running-ai-postgres pg_dump -U running_ai -d running_ai -Fc > .runtime\backup\running_ai-prod-PRE-8B-<ts>.dump
docker exec running-ai-postgres psql -U running_ai -d postgres -c "CREATE DATABASE restore_check;"
docker exec -i running-ai-postgres pg_restore -U running_ai -d restore_check < <the dump>
docker exec running-ai-postgres psql -U running_ai -d restore_check -c "SELECT max(version::int) FROM flyway_schema_history; SELECT count(*) FROM activity;"
docker exec running-ai-postgres psql -U running_ai -d postgres -c "DROP DATABASE restore_check;"
```

**PASS:** the restored copy reports V28 and the same activity count as production. A backup that
has not been restored once is not a backup.

### Step 2 — rehearse V29/V30 on a copy, never first on production

Restore the Step-1 dump into `running_ai_v30_rehearsal`, point a **temporary** Spring instance at
it on a spare port with the new jar, and confirm Flyway reaches V30 and the app starts. Then,
while that rehearsal DB still exists, confirm the rollback path of §5 against it. Drop it.

**PASS:** V29 and V30 apply on a copy of real production data, the new jar starts, Hibernate
validates. **Stop here if either migration fails** — nothing has touched production yet.

### Step 3 — deploy Spring and regress

```
powershell -File scripts\windows\stop-running-ai.ps1
# from the release worktree:
powershell -File scripts\windows\release-spring-jar.ps1          # builds, verifies, publishes, keeps the old jar
powershell -File scripts\windows\start-running-ai.ps1
```

Then: `/api/v1/health` → UP; Flyway head = **30**; `/api/v1/garmin/sync/status` → 200 and now
carries `automaticSync`; `/api/v1/data-freshness` → same latest dates as Step 0;
`/api/v1/training-analysis-report` → 200; activity counts unchanged from Step 0.

**PASS:** head 30, no data changed, every read-only endpoint answers.
**FAIL → Step R.**

### Step 4 — connector deploy and read verification

Restart the connector from the new source, then confirm the **new read route only**:

```
curl "http://127.0.0.1:8765/health"
curl "http://127.0.0.1:8765/scheduled-workouts?year=2026&month=10"
```

**PASS:** `/health` ok, and `/scheduled-workouts` returns either a calendar payload or a clean
error code — not a 500 and not a stack. Note: this is the **first live call** to this Garmin
route (§8). A `GARMIN_NOT_FOUND`-shaped empty calendar is a valid answer, not a failure.

### Step 5 — relay side-by-side

Start the new relay on a **spare port** with the old one still serving the watch, and compare:

```
curl -H "Authorization: Bearer <token>" http://127.0.0.1:17845/today-workout   # current
curl -H "Authorization: Bearer <token>" http://127.0.0.1:<spare>/today-workout # candidate
```

**PASS:** both answer `{date, status, label}` with the same `date`; any difference in
`status`/`label` is explained by RunningAI now holding an approval the old sources could not see.
Also confirm the candidate's log shows either `TODAY_WORKOUT_SOURCE source=RUNNING_AI` or a
`RUNNING_AI_UNAVAILABLE ... falling back` line — never an error to the caller.

### Step 6 — watch-face contract

With the candidate relay: the three states (`HAS_PLAN`, `REST`, `NO_PLAN`) must each produce a
label within the pixel font's charset and ≤ 14 glyphs. Verified in tests
(`sanitizeLabel`, `todayLabelNeverOverflowsSlot`); confirm once on the device or against the
real response.
**PASS:** no label exceeds 14 glyphs and no glyph is outside `0-9 A-Z` and `: + - / . % !`.

### Step 7 — cut over

Stop the old relay, move the candidate onto 17845, confirm the watch face updates.
**PASS:** the watch shows today's row.

### Step 8 — reboot, recovery and rollback drill

Reboot the machine. Confirm every service returns by itself, Spring still reports Flyway 30, and
`GarminSyncAttemptReconciler` either logs nothing or logs an `INTERRUPTED` conversion — which is
**not** a failure and must not have moved any streak.

Then rehearse the rollback **without executing it on production**: confirm
`.runtime\released\` holds the pre-8B jar and that §5's pre-step SQL is to hand.

### Step R — rollback (only if a step fails)

> **SUPERSEDED 2026-10-15** by `2026-10-15-phase-8b-release-stabilization.md` section 3.
> The unconditional `DELETE FROM garmin_sync_attempt ...` that stood here is **withdrawn**: that
> table holds one row per athlete, so the DELETE destroys the athlete's whole attempt record
> (including the failure streak that is designed to survive a later success), and it is
> unnecessary in the normal case. Use the order below.

The database is **not** rolled back. V29 and V30 are forward-compatible with the V28 jar, except
for two outcome values the old enum does not have.

```
-- R0. PRE-CHECK - always run this first. It decides everything that follows.
SELECT count(*) FROM garmin_sync_attempt WHERE outcome IN ('STARTED','INTERRUPTED');
```

- **Returns 0 (the expected case)** - roll back with **no data operation at all**. Go to R2.
  This count stays 0 as long as no Garmin sync has run under V30, which is why the post-deploy
  observation window must not include a manual sync or `POST /api/v1/coach/data-refresh`.
- **Returns > 0** - prefer **R1a (roll forward)**. Only use **R1b** if rollback is still required.

```
# R1a. ROLL FORWARD - preferred, loses nothing, needs no SQL.
#      The 8B jar reads both values natively and the startup reconciler converts a leftover
#      STARTED into INTERRUPTED with both streak counters intact.
```

```
# R1b. QUARANTINE - last resort. Requires a fresh pg_dump and explicit approval first,
#      because it empties the athlete's live attempt record (the row is preserved, the live
#      failure streak restarts from 0).
psql -U running_ai -d running_ai -f scripts\ops\v30-rollback-quarantine.sql
#      On a later roll-forward, put it back (never overwrites a newer row):
psql -U running_ai -d running_ai -f scripts\ops\v30-rollback-restore.sql
```

```
# R2. restore the previous jar
powershell -File scripts\windows\stop-running-ai.ps1
Copy-Item .runtime\released\<previous>.jar .runtime\running-ai-server.jar -Force
powershell -File scripts\windows\start-running-ai.ps1
# R3. verify - BOTH endpoints. A clean start and a 200 on /api/v1/health are NOT evidence:
#      the V28 jar starts perfectly while these two return 500.
curl http://127.0.0.1:18080/api/v1/health
curl http://127.0.0.1:18080/api/v1/garmin/sync/status     # must be 200, not 500
curl http://127.0.0.1:18080/api/v1/data-freshness         # must be 200, not 500
```

`workout_draft_publish_attempt` is left in place: the old jar does not map it and Hibernate
ignores it. Restoring the Step-1 dump is the last resort only, and only if data was actually
corrupted — which no step above can do, since every migration is additive.

---

## 7. Production activation plan (separate approval, after the deployment above)

Garmin data is currently **3 days stale** (`lastSuccessfulSyncAt` 2026-10-12T03:24Z) and no
attempt has been recorded since the 8A deploy, so the first manual sync is also the first real
exercise of the 8A/8B ledger.

**Never run a backfill and an activity sync at the same time.** The two hold separate locks; the
registry makes the scheduler wait, but a manual request is never blocked, so this is a human
rule.

| # | Action | Proceed only when | Abort if |
|---|---|---|---|
| 1 | One manual `POST /api/v1/garmin/sync` | Step 8 passed | — |
| 2 | Inspect result and DB impact: `/garmin/sync/status` (outcome, counts, `consecutive*Count`), `/data-freshness`, `activity` count delta, `garmin_sync_state` moved | outcome `SUCCESS`, counts explainable, checkpoint advanced | outcome `FAILED` with `GARMIN_AUTH_REQUIRED`/`GARMIN_FORBIDDEN` (**401/403 → stop, renew the connector session, do not retry automatically**); `GARMIN_RATE_LIMITED` (**429 → stop for ≥ 6 h**); a duplicate activity appears (same `external_source`+`external_id` twice, or an activity count that grew by more than `created`); outcome `PARTIAL` (checkpoint deliberately held back — inspect the unmappable item before anything else) |
| 3 | Observe 24 h with the scheduler still **off** | nothing degraded, no new failure rows | any `FAILED` row appears |
| 4 | Enable `RUNNING_AI_GARMIN_SCHEDULER_ENABLED=true`, restart Spring | step 3 clean | — |
| 5 | Confirm the first scheduled run: a `SUCCESS` attempt whose `attemptedAt` matches the tick, and `automaticSync.allowed=true` in the status body | exactly one tick ran | two ticks inside `minimum-interval` (15 m); any `SKIPPED_*` that is not explainable |
| 6 | Observe ≥ 24 h | `consecutiveFailureCount` and `consecutivePartialCount` both 0 | `consecutiveFailureCount` ≥ 3 with an AUTH code — the breaker has stopped automatic sync and **only a human + one completed manual sync clears it** |
| 7 | Recovery backfill with `onlyMissing=true` (≤ 28 days) — **after confirming no activity sync is running** (`/garmin/sync/status` outcome ≠ `STARTED`) | step 6 clean | any connector failure — the backfill stops at the first one by design; do not restart it in a loop |
| 8 | Verify the latest `TrainingContextV2` via `GET /api/v1/coach/training-context?version=V2` | step 7 clean | `dataCoverage.metricUnavailability` still lists `LAP_STRUCTURE` for activities that have laps (would mean R1 did not reach production); snapshot > 64 KiB (`TRAINING_CONTEXT_TOO_LARGE`) |

**Checkpoint-mismatch rule:** if `garmin_sync_state.highWaterStartedAt` moves while the attempt
ledger reports `PARTIAL`, or moves without a `SUCCESS` attempt, **stop and investigate before any
further sync** — those two must agree, and 8A/8B never write `garmin_sync_state`.

---

## 8. Unverified external dependencies

1. **Garmin `/scheduled-workouts`** — STATIC_SOURCE_CONFIRMED only. `calendarItems` and
   `itemType == "workout"` are confirmed from the library's own `get_next_scheduled_workout`;
   `title`, `sportTypeKey` and `workoutId` are **not** and are read as nullable. **Never
   live-called.** Step 4 is the first.
2. **`deviceDelivery`** — permanently `UNKNOWN`. No available API reports what the watch has
   synced. This is a stated limit, not a gap to close.
3. **`GARMIN_NOT_FOUND` is not a failure** — Garmin's calendar returns only committed workouts,
   and the Intervals→Garmin push is Intervals.icu's own integration. Read it with
   `minutesSincePublish`.
4. **Real external publish through MCP** — still **NOT_RUN**. The Worker filters to
   `['runningai:read','offline_access']`, so a train/publish token cannot exist yet. Unchanged by
   this release.
5. **ChatGPT connector is currently down** — the MCP gateway (17911) is not listening and the
   Quick Tunnel answers 502. Unrelated to this release, but relevant to approval item 3.

---

## 9. What needs your approval

Everything below was deliberately **not** done.

1. **`git push` and merge into canonical `main`.** The branch is 11 commits ahead of local `main`
   and local `main` is **28 commits ahead of `origin/main`** (last fetched 2026-10-12). The push
   therefore carries far more than Phase 8B — 7D, 7E and 7F included. Confirm that is intended.
2. **Production deployment** — the jar, the connector, the relay, and the V29/V30 migration
   (§6).
3. **Worker redeploy.** `wrangler.jsonc` in Git no longer carries a tunnel hostname. The next
   deploy **must** pass `--var ORIGIN_MCP_URL:"https://<current-tunnel>/mcp"` or the ChatGPT
   connector breaks. Separately worth deciding: move to a named tunnel or the Tailscale Funnel
   hostname so the value becomes stable.
4. **Garmin manual sync, then the scheduler** (§7, steps 1 and 4).
5. **The recovery `onlyMissing` backfill** (§7, step 7).
6. **Any real Intervals.icu publish**, and any OAuth scope change.

---

## 10. Release candidate

```
branch  phase-8b-core-completion
head    e2bd1e0  (+ this record)
base    b1feda7
jar     30 migrations, highest V30, verified but NOT released
tests   Spring 1596/1596 (H2 and PostgreSQL 17) | connector 154 | relay 31 | worker 49 | gateway 46
```
