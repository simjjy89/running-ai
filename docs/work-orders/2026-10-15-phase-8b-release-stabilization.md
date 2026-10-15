# Phase 8B — Release stabilization and operational hand-over readiness

**Date** 2026-10-15 · **Branch** `phase-8b-release-stabilization` (off RC `940d3f6`)
**Nothing was deployed. No production schema, jar, switch, Garmin sync or Intervals write happened.**

This is the review that stood between the Phase 8B release candidate and a deployable release.
It was not feature work. It restored a broken production path, audited the Git state, and replaced
an assumed rollback step with a measured one.

---

## 0. Headline corrections to the prior record

Three things the earlier record stated are corrected here, each measured rather than reasoned:

| Prior statement | Measured reality |
|---|---|
| "local main is **28** commits ahead of origin/main" | It is **36**. The release as a whole (origin/main → RC) is **49** commits. |
| Rollback breaks `GET /api/v1/garmin/sync/status` | It breaks **two** endpoints. `GET /api/v1/data-freshness` fails identically — V28's `DataFreshnessService` also reads the attempt row. |
| Rollback pre-step is `DELETE FROM garmin_sync_attempt WHERE outcome IN (...)` | That DELETE removes an athlete's **entire** attempt record, not a log line, because the table is one row per athlete. A data-preserving equivalent now exists and is rehearsed. |

---

## 1. STEP 1 — production MCP path restored

### What was actually wrong

The reported symptoms (gateway not answering, tunnel 502, ChatGPT read tools failing) had **one**
cause, and it was not configuration:

- `RunningAI-McpGateway` is configured correctly and points at the post-7F-3B-2A launcher
  `C:\running-ai-github\.runtime\mcp-gateway.cmd`. Its port is **17910**, not 17911 —
  `mcp-gateway-17911.cmd` is the *test* launcher and was never the production one, so the
  "17911 not responding" symptom was a probe of the wrong port.
- `cloudflared` **never died**. The same process (PID 18220, started 2026-10-14 08:09 KST) has been
  serving `https://comedy-awarded-trucks-constraints.trycloudflare.com` to `127.0.0.1:17910`
  continuously. The 502s are its own origin-refused errors, first logged `2026-10-15T01:02:03Z`.
- The **node gateway process was simply gone**. Last healthy gateway log line
  `2026-10-14T23:01:30Z` (= 10-15 08:01 KST), immediately around the 08:00 watchdog Spring restart.

### The structural reason it stayed down

`watch-running-ai.ps1` supervises `docker`, `postgres`, `connector`, `spring`, `external-relay`.
**The MCP gateway and the tunnel are not in that list.** Nothing was watching them, so nothing
restarted them. This is the finding worth keeping: the outage lasted ~3.5 h not because recovery is
hard but because no component owned it.

### Recovery performed

`Start-ScheduledTask -TaskName 'RunningAI-McpGateway'` — the existing, verified launcher, unchanged.

| Check | Result |
|---|---|
| Gateway listening | PASS `127.0.0.1:17910` |
| Gateway tool policy after restart | PASS byte-identical: 7 read = `runningai:read`, 5 train = `runningai:train`, `publish_approved_draft` **not** allowlisted |
| `GET /health` (loopback) | PASS 200 `{"status":"ok","service":"running-ai-mcp-gateway"}` |
| `GET /health` (through the tunnel) | PASS 200 — tunnel-to-gateway path intact |
| Worker `GET /health` | PASS 200 |
| Worker AS metadata + JWKS | PASS 200, RS256 |
| Worker PRM `/.well-known/oauth-protected-resource/mcp` | PASS 200, scopes `runningai:read`, `runningai:train` |
| Unauthenticated `POST /mcp` (Worker) | PASS 401 + correct `WWW-Authenticate` challenge |
| Unauthenticated / bogus-token `POST /mcp` (gateway) | PASS 401, `invalid_token`, backend untouched |

### The Worker was NOT redeployed — and did not need to be

The one allowed redeploy was deliberately **not consumed**. The deployed version
`0d75fa82-eb1a-4538-9c63-06246d852968` (2026-10-14T11:10Z) was inspected with
`wrangler versions view`: its `ORIGIN_MCP_URL` is already
`https://comedy-awarded-trucks-constra...` — the live tunnel. Because the tunnel process never
restarted, the committed-vs-deployed divergence that 7F-3B-3 hit did not recur. Secrets
(`HOP_SIGNING_JWK`, `GITHUB_CLIENT_ID`, `GITHUB_CLIENT_SECRET`) and the KV binding are unchanged.

### What could not be verified, and why

**The authenticated leg (`tools/list`, `get_athlete_profile`, `get_workout_draft` carrying a real
token) was not exercised, and could not be.** The gateway validates bearer tokens against the
Worker's JWKS; the matching private key exists **only** as a Worker secret and is deliberately not
on this machine. No local process can mint an acceptable token, so this leg requires a real
GitHub-login OAuth flow from ChatGPT. That is a one-click user action, not a gap in the recovery.

What *was* proven instead is everything on both sides of that boundary: the public chain answers
correctly up to the 401, and the backend the gateway proxies to answers correctly below it —
`POST /mcp` on production Spring (loopback) lists **13** tools and `get_athlete_profile` returns the
real profile (LTHR 176 bpm, threshold pace 290 s/km). The gateway allowlist exposes **12** of those
13; `publish_approved_draft` is the excluded one.

---

## 2. STEP 2 — Git release integrity

### Topology (measured, all fast-forward, no divergence)

```
origin/main 370ee8f
   +35 --> f1451f5   <- the commit the RUNNING PRODUCTION JAR was built from
      +1 --> b1feda7 (= local main, docs-only: 1 file, +127 lines)
         +13 --> 940d3f6 (RC, phase-8b-core-completion)
```

- `origin/main` is an ancestor of `main`: **fast-forwardable, no rebase or merge needed.**
- `main` is an ancestor of the RC: **linear.**
- Working tree: **clean**, zero untracked files (`--untracked-files=all`).

### The finding that changes how the push should be judged

**`origin/main` does not contain the code that is running in production.** It is 35 commits behind
the production jar. Therefore the 36 "unpushed" commits are not unreviewed new work waiting to be
pushed — **35 of them are the live, production-validated system**, and the only commit separating
production from local `main` is `b1feda7`, which touches one documentation file.

The genuinely undeployed code is the **13 commits of Phase 8B**, not 36 and not 28.

### Phase coverage of the 36

| Phase | Commits | Already in production |
|---|---|---|
| 7B (injury gate, LTHR provenance) | 4 | yes |
| 7C (read-only MCP tools, gateway) | 6 | yes |
| 7D (Cloudflare Worker / OAuth AS) | 3 | yes |
| 7E (Intervals-only + Strava import, V24-V26, cutover) | 7 | yes |
| 7F (train MCP tools, injury history, scopes) | 5 | yes |
| 8A (attempt/collection ledgers, V27-V28) | 3 | yes |
| docs / ops / work orders | 8 | yes |

Release total `origin/main..940d3f6`: **49 commits, 324 files, +48,969 / -195**
(server 185, docs 96, tools 34, scripts 6).

### Secrets and volatile-value audit (whole 49-commit diff)

| Check | Result |
|---|---|
| Sensitive file paths added (`.env`, `*.pem`, `*.key`, `*.jwk`, dumps, jars, session/garth files) | PASS none — only `.env.example` |
| `.env.example` contents | PASS keys present, **all values empty**; every publishing/scheduler switch written `false` |
| Private keys / API keys / client secrets / tokens in diff content | PASS none. Three pattern hits, all benign: a throwaway `POSTGRES_PASSWORD=checkonly` for a restore check, a doc **listing** the patterns to scan for, and a `%RUNNINGAI_DEV_DB_PASSWORD%` variable reference |
| Real GPS coordinates | PASS none |
| Production Worker diagnostics committed | PASS yes, `e2bd1e0` — this closed the 7F-3B-3 "code only in a dirty worktree" risk |
| Volatile tunnel hostname in `wrangler.jsonc` | PASS correctly **not** tracked — committed value is the unresolvable `https://origin.invalid/mcp`, so a Worker deployed without `--var` fails visibly |
| Tunnel hostnames elsewhere | NOTE present in historical work-order docs, including the currently-live one. See below |

**The one residual exposure.** `comedy-awarded-trucks-constraints.trycloudflare.com` appears in two
work-order documents. It is a live hostname, and `/health` on it answers unauthenticated. It is not
a credential: `POST /mcp` through it returns 401 without a Worker-signed RS256 token, which is
exactly what `tools/mcp-worker/README.md` already documents ("bypassing the Worker buys nothing").
Treat it as an address disclosure, not a secret leak — and as one more reason the Quick Tunnel
should become a named tunnel.

### Safe integration path (recommended, not executed — push is out of scope)

1. **Fast-forward `origin/main` to `b1feda7` first.** This publishes only what production has been
   running and has already live-validated. It carries no deployment risk because it changes no
   running system, and it ends the state where the remote disagrees with production.
2. **Hold the 13 Phase 8B commits** until Phase 8B is actually deployed and verified, then
   fast-forward again. This keeps "what is on the remote" equal to "what has run", which is the
   property that was lost and is worth restoring.

Nothing was pushed, merged, force-pushed, reset or cleaned.

---

## 3. STEP 3 — the V30 rollback risk, resolved

### 3.1 How the new states are created

`GarminSyncAttemptOutcome` gained `STARTED` and `INTERRUPTED` in V30.

- **`STARTED`** is written by `GarminSyncAttemptService.recordStarted()`, called from exactly one
  place: `GarminSyncOperationService.runSync()` line 70, **before** the connector call. It is
  overwritten by the real outcome when the run ends. It therefore only *persists* if the JVM dies
  mid-sync.
- **`INTERRUPTED`** is written by `GarminSyncAttemptReconciler` on `ApplicationReadyEvent`, and
  only ever by converting a surviving `STARTED`. It is explicitly **not** a failure and moves
  neither streak counter.

### 3.2 Can they arise in production with the scheduler OFF? — three triggers, all manual

`runSync()` has **three** callers, one more than the earlier record names:

| Caller | Production state |
|---|---|
| `POST /api/v1/garmin/sync` | manual, operator-initiated |
| `GarminSyncScheduler` | **OFF** (`RUNNING_AI_GARMIN_SCHEDULER_ENABLED` absent, so false) |
| `CoachDataRefreshService.kt:137`, reached from `POST /api/v1/coach/data-refresh` **or** `CoachDataRefreshScheduler` | scheduler **OFF** (property absent, default false); endpoint is manual |

No other path reaches it. The legacy main-PC PowerShell automation was checked directly: **no script
under `C:\running-ai\` references the Spring sync API or port 18080 at all**, so the five Windows
tasks still firing every few minutes cannot trigger a sync.

**Conclusion: with both schedulers off, neither state can appear unless a human calls one of two
endpoints.** Production's `garmin_sync_attempt` is currently **empty (0 rows)** — no sync has been
recorded since V27 was applied. The rollback hazard does not exist today and does not begin to
exist until the first sync is run under V30.

### 3.3 How the old jar reads them — two endpoints, not one

Enumerated from the V28 tree at `f1451f5` and confirmed at runtime:

| V28 endpoint | With a `STARTED`/`INTERRUPTED` row |
|---|---|
| `GET /api/v1/garmin/sync/status` (via `GarminSyncStatusService`) | **500** |
| `GET /api/v1/data-freshness` (via `DataFreshnessService.kt`) | **500** — *missed by the prior analysis* |
| `POST /api/v1/garmin/sync` | see 3.4 |
| `/api/v1/health`, `/actuator/health`, `/api/v1/activities`, `/api/v1/training-state`, `/api/v1/garmin/recovery-sync/coverage` | 200 |

`status-running-ai.ps1` reads `/api/v1/garmin/sync/status`, so **the operator's own status tooling
breaks too** — which is how a rollback could look healthy and be measured as broken at the same time.

### 3.4 Why "the old jar started" is not evidence

Confirmed directly: the V28 jar **starts cleanly** against a V30 schema and answers
`/api/v1/health` with 200 while both endpoints above are returning 500. Flyway ignores V29/V30 under
its default `ignoreMigrationPatterns=*:future`; Hibernate `validate` accepts the extra table and
column.

The `POST /api/v1/garmin/sync` case is worse, and the prior record is **correct** — verified here by
reading V28's `runSync()`: on the success path `recordCompleted()`'s read of the bad row throws and
is *swallowed* by the `record(...)` wrapper, but the very next statement,
`statusService.getStatus()`, is **outside** that wrapper. So a manual sync on a rolled-back jar
would ingest activities and advance the checkpoint **and then answer 500**. (Not reproduced at
runtime in this session: the rehearsal connector is a black-hole port, so ingestion fails first.)

### 3.5 The recovery options, in preference order

**A. Roll forward — preferred, zero data loss, no SQL.** The Phase 8B jar reads both states
natively. Re-deploying it resolves the condition completely, and a leftover `STARTED` self-heals:
the reconciler converts it to `INTERRUPTED` at startup with both streak counters intact.
*Measured: `Garmin sync attempts left in flight by a previous process: 1 marked INTERRUPTED`,
`consecutive_failure_count` preserved at 1, both endpoints 200.*

**B. Prevention — rollback is byte-clean while no sync has run under V30.** Verified by the
pre-check `SELECT count(*) ... WHERE outcome IN ('STARTED','INTERRUPTED')`. Since Garmin activation
is already a separate approval gate (runbook step 12), the deployment can keep this count at 0 for
the whole observation window and roll back with **no data operation whatsoever**.

**C. Quarantine, only if B fails and A is not acceptable.** `scripts/ops/v30-rollback-quarantine.sql`
copies the complete row — all 18 columns, as JSONB — into `garmin_sync_attempt_v30_quarantine`
before removing it from the live table, and `scripts/ops/v30-rollback-restore.sql` puts it back on
roll-forward using `ON CONFLICT (athlete_id) DO NOTHING`, so a newer row written during the rollback
window always wins and the older one stays archived as history.

**D. The bare `DELETE` from the old runbook is struck.** It is strictly dominated by C: identical
effect on the live table, but irreversible. Because `garmin_sync_attempt` carries
`uk_garmin_sync_attempt_athlete` (**one row per athlete**), that DELETE does not drop a log entry —
it drops the athlete's whole attempt record, including `consecutive_failure_count`,
`consecutive_partial_count`, `last_failure_at` and `last_failure_code`, which the Phase 8A design
specifically built to **survive a later success**.

**Honest cost of C, to be stated at approval time:** while the old jar runs, the live row is absent,
so a sync during the rollback window starts a fresh failure streak from 0. No SQL can avoid this —
the streak lives in the same row that must disappear. The audit value is fully preserved; the live
counter is not. Nothing touches `garmin_sync_state`, so no checkpoint moves.

**Rejected:** mapping `INTERRUPTED` to `FAILED` to keep the counters (falsifies the ledger and would
wrongly push the circuit breaker), and hiding the rows behind a renamed table plus a view (mutates
production schema outside Flyway, and `validate` plus writes make it fragile).

### 3.6 Rehearsal — repeated, on a real-shaped database

Rehearsal DB `running_ai_rb`: the production `PRE-8A` dump restored (**48 activities**, Flyway V26),
then driven through the real deployment path.

| Stage | Result |
|---|---|
| V28 jar on the restored DB | PASS up, migrates V27 to V28, `sync/status` 200, `lastAttempt: null` |
| **V28 to V30 upgrade** with the RC jar | PASS V29 + V30 applied; `sync/status`, `data-freshness`, `today-workout`, `training-analysis-report` all 200 |
| Real sync attempt (black-hole connector, no Garmin contact) | PASS 503, ledger row `FAILED / GARMIN_UNAVAILABLE / streak=1` — write path works |
| V28 jar + `STARTED` row | FAIL 500 on both endpoints — `No enum constant ...GarminSyncAttemptOutcome.STARTED` |
| V28 jar + `INTERRUPTED` row | FAIL 500 on both — `...GarminSyncAttemptOutcome.INTERRUPTED` |
| Roll forward (option A) | PASS reconciled `STARTED` to `INTERRUPTED`, streak preserved, 200 |
| Quarantine (option C, **the committed script**) | PASS `must_be_zero=0, rows_preserved=1`; V28 back to 200 on both endpoints **without a restart** |
| Restore with a newer live row present | PASS `INSERT 0 0` — newer row kept, history retained |
| Restore into an empty table | PASS `INSERT 0 1`, all 18 columns byte-identical |
| **Whole cycle repeated** | PASS **3 independent cycles**, every expectation met every time |

### 3.7 Final rollback conditions

Rollback from Phase 8B to the V28 jar is **PASS** only when all of the following hold:

1. `SELECT count(*) FROM garmin_sync_attempt WHERE outcome IN ('STARTED','INTERRUPTED')` returns
   **0** — either naturally (no sync ran under V30) or after `v30-rollback-quarantine.sql`.
2. **Both** `GET /api/v1/garmin/sync/status` **and** `GET /api/v1/data-freshness` answer **200**.
   A successful start and a 200 on `/api/v1/health` are *not* sufficient evidence.
3. If the quarantine script was run, its row count is recorded, and `v30-rollback-restore.sql` is
   part of the roll-forward plan.
4. Any data removal has a fresh `pg_dump` taken first and explicit approval — it is not a routine
   step.

---

## 4. STEP 4 — integrated verification

All suites re-run on this branch (code identical to RC `940d3f6`; the two commits after `e2bd1e0`
are documentation only).

| Suite | Result |
|---|---|
| Spring, H2 — `gradlew clean test` | **1596 / 1596**, 0 failures, 0 errors, 0 skipped (159 classes) |
| Spring, PostgreSQL 17.11 — throwaway `running_ai_test`, `hikari.maximum-pool-size=2` | **1596 / 1596**, 0 failures, 0 errors, 0 skipped (see 4.1) |
| Flyway V1 to V30 | applied clean on PostgreSQL 17.11, head **V30**, 32 tables |
| Garmin connector — `pytest` | **154 / 154**, incl. the AST test that fails the build if `schedule_workout` / `upload_*_workout` / `push_workout_to_device` ever appears |
| External relay — `node --test` | **31 / 31** |
| MCP gateway — `npm test` | **46 / 46** (scopes not hierarchical, `publish_workout` refuses to start, JWKS outage fails closed) |
| MCP Worker — `npm run test:all` | **49 / 49** (`runningai:publish` never granted; no secret in any log) |
| V28 to V30 deployment rehearsal | PASS, see 3.6 |
| V30 failure + recovery rehearsal | PASS, see 3.6, 3 cycles |
| Garmin circuit breaker | covered by the Spring suite |
| Publish-attempt ledger | covered by the Spring suite |
| AI draft to relay compatibility | relay 31/31; relay is live right now against a V28 production that answers **404** on `/api/v1/today-workout`, i.e. the fallback path is working in production today |
| Injury / workout safety gate | covered by the Spring suite |
| Read / Train scopes, publish blocked | gateway + worker suites; production gateway allowlist re-checked after restart (12 of 13 tools; `publish_approved_draft` excluded) |
| Credentials / sensitive data | see section 2 |

No test was deleted, skipped or disabled.

### 4.1 PostgreSQL suite

Run against **PostgreSQL 17.11** in the project container, on a throwaway database
`running_ai_test`, with the datasource overridden through `SPRING_APPLICATION_JSON` and
`spring.datasource.hikari.maximum-pool-size=2` (the `REQUIRES_NEW` ledger writes take a second
connection while the caller's transaction is suspended, so the pool must not drop below 2).

| Metric | Value |
|---|---|
| Result | **BUILD SUCCESSFUL** in 8m 32s |
| Tests | **1596 / 1596 PASS** |
| Failures / errors / skipped | 0 / 0 / 0 |
| Test classes | 159 |
| Flyway after the run | head **V30**, 32 tables in `public` |

The post-run schema check is what proves the suite really exercised PostgreSQL rather than silently
falling back to H2: the throwaway database ends at Flyway head V30 with the full 32-table schema,
which also serves as the **Flyway V1 to V30 on PostgreSQL** verification.

---

## 5. STEP 5 — production deployment runbook

The nine-step runbook in `2026-10-15-integrated-release-preparation.md` section 6 stands. This
section records only what this review **changes** in it; everything not listed is unchanged.

### 5.1 Identity

| Item | Value |
|---|---|
| Release candidate | `940d3f6` (`phase-8b-core-completion`); this review adds commits on `phase-8b-release-stabilization` |
| RC artifact SHA-256 | `0e7712674675b18210f90dfc824f7882a7a493bb28da8c850889cf062d4d1518` |
| Current production commit | `f1451f5` (branch `main`, clean) |
| Current production artifact SHA-256 | `53d6ada77eca86694152a71da869b9f72191f5a95b804e26c1ce861a02e2fc9f` |
| Production Flyway head | **V28**; release applies **V29, V30**; next free is V31 |
| Rollback artifact | `.runtime\running-ai-server.jar` (take a copy before release) |

### 5.2 Changes to the runbook

1. **Step R (rollback) — replace the unconditional `DELETE`.** New order: (a) run the pre-check;
   (b) if it returns 0, roll back with **no data operation**; (c) if it returns more than 0, prefer
   **roll-forward**; (d) only if rollback is still required, take a `pg_dump`, get explicit
   approval, run `scripts/ops/v30-rollback-quarantine.sql`, and schedule
   `scripts/ops/v30-rollback-restore.sql` for the roll-forward.
2. **Rollback verification must check two endpoints**, `/api/v1/garmin/sync/status` **and**
   `/api/v1/data-freshness`. A clean start is not evidence.
3. **Add a gateway/tunnel liveness check to Step 0 and to the completion criteria.** This outage
   was invisible to the watchdog. Minimum: `curl http://127.0.0.1:17910/health` and
   `curl https://<tunnel>/health`.
4. **Keep the rollback window clean.** Do not run a manual Garmin sync or
   `POST /api/v1/coach/data-refresh` during the post-deploy observation window; that alone keeps the
   attempt ledger at the 0 rows that make rollback trivial. This is compatible with step 12, which
   already gates Garmin activation behind separate approval.
5. **Step 12 (Garmin manual sync / scheduler enable) is the point at which rollback stops being
   free.** State this explicitly at that approval: the first sync under V30 is what creates the
   state that makes a later rollback need section 3.5.

### 5.3 Follow-ups this review recommends (none are blockers, none were done)

- Put the MCP gateway (and ideally the tunnel) under `watch-running-ai.ps1`. This outage had no
  owner.
- Replace the Quick Tunnel with a named tunnel or the existing Tailscale Funnel hostname. It would
  make `ORIGIN_MCP_URL` a stable committable value and end the deploy-time `--var` dance that
  already caused the 7F-3B-3 divergence.
- Consider making the attempt-outcome mapping tolerant of unknown values in a future phase, so a
  V31-to-V30 rollback cannot repeat this class of failure. **Deliberately not done in this
  release** — it would change code the RC has already been verified against.

---

## 6. Status

| Area | Verdict |
|---|---|
| A — production MCP path | restored; authenticated leg needs one ChatGPT sign-in (impossible locally by design) |
| B — Git integrity | clean, linear, fast-forwardable, no secrets; count corrected 28 to 36 (49 total) |
| C — V30 rollback risk | resolved: prevention + roll-forward + a rehearsed data-preserving fallback; bare DELETE struck |
| D — tests and rehearsals | see section 4 |
| E — release approval | see the final report |
