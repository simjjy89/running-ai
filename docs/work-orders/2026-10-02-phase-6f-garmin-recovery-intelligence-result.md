# Phase 6F — Garmin Recovery Intelligence (result)

Baseline `668a097 docs: record Phase 6E result`, branch `main`, clean working tree at start (Phase 6E
commits `3f6a79d`, `509dfc9`, `57e1b68`, `668a097` present). Instruction (verbatim):
`2026-10-02-phase-6f-garmin-recovery-intelligence-instruction.md`. Worked on the external PC.

## 1. What was implemented

```
Garmin ──▶ garmin-connector GET /recovery?date=        (Python, projects documented fields only)
       ──▶ GarminRecoveryClient / HttpGarminRecoveryClient   (Spring transport, 1 request, no retry)
       ──▶ GarminRecoveryMapper                              (pure normalisation → RecoveryDailyValues)
       ──▶ RecoverySnapshotService.upsert ─▶ RecoveryRepository ─▶ garmin_recovery_daily (V7)
       ──▶ RecoveryBaselineService                           (28-day personal baseline, measurement only)
       ──▶ RecoveryContextBuilder (Kotlin)                   (re-shape, sleep s → h)
       ──▶ TrainingContextBuilder ─▶ TrainingContext.recovery
       ──▶ ClaudeAiCoach (prompt explains the fields) ─▶ WorkoutDraft
```

Spring collects, normalises, stores, computes baseline/deviation and hands it over. **No Spring code turns a
recovery value into a training decision**: there is no rule like "HRV low → easy", no rating field
(asserted by a test that scans the context classes for rating/label/readiness-style names), and the system
prompt tells Claude explicitly that the numbers are unrated and that interpreting them is its job.

Out of scope and untouched, as instructed: Intervals publish, Garmin workout write, approve workflow,
publishing scheduler. The coach architecture test was extended so coach code also cannot reference the new
Garmin transport (`GarminRecoveryClient`, `GarminRecoverySyncService`).

## 2. Garmin library investigation (python-garminconnect 0.3.16)

Source read directly from the 0.3.16 wheel/sdist on PyPI, plus the library's own tests at tag `0.3.16`
(`tests/test_garmin.py`, `tests/test_typed.py`, `tests/test_garmin_unit.py`). No field name was guessed.

| Metric | Method used | Fields read | Evidence |
|---|---|---|---|
| HRV | `get_hrv_data(date)` | `hrvSummary.{calendarDate,lastNightAvg,weeklyAvg,status}` | `typed.HrvSummary`; `test_hrv_data` asserts `hrvSummary.weeklyAvg`; returns `None` on 204 (`test_get_hrv_data_returns_none_on_204`) |
| Sleep | `get_sleep_data(date)` | `dailySleepDTO.{calendarDate,sleepTimeSeconds}`, `dailySleepDTO.sleepScores.overall.value` | `typed.DailySleepDTO`, `typed.SleepScores` |
| Resting HR | `get_rhr_daily(date, date)` | `[{calendarDate, value}]` | the library itself flattens the wellness-stats response into this shape (`Garmin.get_rhr_daily`) |
| Body Battery | `get_body_battery(date)` | `date, charged, drained, bodyBatteryValuesArray[[ts, level]]` | `typed.BodyBatteryEntry` ("list of `[timestamp, level]` pairs"); `test_body_battery` |
| Stress | `get_all_day_stress(date)` | `calendarDate, avgStressLevel, maxStressLevel` | `test_all_day_stress` asserts exactly these (no typed model exists for stress) |

Not used, deliberately:
- `get_rhr_day` — returns the raw wellness-stats structure; `get_rhr_daily` gives the library-normalised rows.
- `get_stress_data` — same URL as `get_all_day_stress`, but only the latter has field assertions in the library.
- Range methods `get_hrv_data_range` / `get_sleep_daily` — their **row** schemas are not evidenced in the
  library (the unit tests mock only `{"hrvSummaries": []}` / `overallSleepScore`). Using them would have meant
  guessing, so the backfill uses the per-day methods instead. Candidate optimisation once live-probed.
- **`typed.py` is explicitly *Experimental*** ("model shapes and the `g.typed` surface may change between minor
  releases"). It was used only as documentation of field names; the connector never imports it (it needs
  pydantic, which the connector does not depend on).

Retry behaviour (from `_handle_api_errors` / `_is_retryable`): the library retries only 5xx and network
failures (default 3 attempts with backoff) and **never retries 401 or 429**. The connector adds no retry.

## 3. Python connector (`tools/garmin-connector`)

- New `garmin_connector/recovery.py`: per-metric parsers + `fetch_recovery(garmin, date)`; five strictly
  sequential calls per day.
- `GarminGateway.recovery(day)`, `CachedGatewayProvider.recovery(day)` (drops the cached session on auth failure,
  like the existing endpoints); `create_app(...)` takes a third fetcher; `GET /recovery?date=YYYY-MM-DD`.
- Contract per metric: `{"status": OK|NO_DATA|MALFORMED|ERROR, "data": {...}|null}`. Garmin units kept; no
  time series, no `userProfilePK`, no raw body leaves the connector. Body Battery `highest`/`lowest` are the
  max/min of the sampled levels of that day. A body for a different `calendarDate` is `MALFORMED` (never stored
  under the wrong day). Negative stress values (Garmin's "no reading" markers) are dropped.
- Error handling: authentication failure or rate limit (429, either exception type or upstream status) aborts
  the whole day immediately — the remaining calls are not made; any other single-metric failure only marks that
  metric `ERROR`, without forwarding the library message.
- README documents `/recovery` (and the previously undocumented `/lactate-threshold`).

## 4. Database — Flyway `V7__create_garmin_recovery_daily.sql`

`garmin_recovery_daily`: `athlete_id` (FK), `recovery_date` (Garmin calendar date, athlete-local),
`hrv_last_night_avg_ms`, `hrv_weekly_avg_ms`, `hrv_status` (Garmin label, verbatim), `sleep_duration_seconds`,
`sleep_score`, `resting_heart_rate_bpm`, `body_battery_highest/lowest/charged/drained`, `stress_average`,
`stress_max`, `created_at`, `updated_at`. **Unique `(athlete_id, recovery_date)`**; non-negative checks
(RHR > 0). Every metric nullable; null never means zero.

Idempotency (`RecoverySnapshot.merge` / `RecoverySnapshotService.upsert`): re-sync updates the same row; a
non-null value overwrites, a null keeps the stored value (the connector cannot always distinguish "Garmin has
none" from "that call failed"); unchanged data writes nothing (`updated_at` untouched); a day with no value
at all creates no row.

Verified on **real PostgreSQL 17.11** (throwaway scratch cluster): V1–V7 all `success = t`, column types as
designed, and `SchemaMigrationTest`, `recovery.*`, `GarminRecoverySyncApiTest`, `TrainingContextBuilderTest`,
`WorkoutDraftApiTest` (72 tests) pass against it.

## 5. Baseline (`RecoveryBaselineService`, calculation only)

Per metric (`HRV_LAST_NIGHT_AVG_MS`, `SLEEP_DURATION_SECONDS`, `SLEEP_SCORE`, `RESTING_HEART_RATE_BPM`,
`BODY_BATTERY_HIGHEST`, `STRESS_AVERAGE`):
- **current** = most recent day with a value within the 28 days ending on the target date (each metric has its
  own date; `ageDays` = target − that date);
- **baseline** = arithmetic mean of the valid values in the **28 days before** the current day (current day
  excluded); days without a value are skipped, never zero;
- **difference**, **differencePercent** (null when baseline is 0), **sampleCount**;
- fewer than **7 valid days** → `INSUFFICIENT_DATA` (baseline/difference/percent null; current still real);
- values after the target date are never read (no look-ahead). Rounded to 0.1.

## 6. RecoveryContext (Phase 6E `TrainingContext.recovery`, reshaped)

```
recovery: {
  hrv:              { lastNightAvgMs: M, garminWeeklyAvgMs, garminHrvStatus } | null
  sleep:            { durationHours: M|null, sleepScore: M|null } | null
  restingHeartRate: { bpm: M } | null
  bodyBattery:      { highest: M, lowest, charged, drained } | null
  stress:           { average: M, max } | null }
M = { date, ageDays, current, baseline, difference, differencePercent, sampleCount,
      baselineWindowDays: 28, minimumSamples: 7, baselineStatus: AVAILABLE|INSUFFICIENT_DATA }
```

Missing groups are explicit JSON `null` in the prompt (and in the API), never omitted or defaulted. The old
flat placeholder fields (`hrvMs`, `sleepHours`, …, all always null in 6E) were replaced; the one Java interop
test that referenced them was updated.

## 7. API

| Method | Path | Notes |
|---|---|---|
| POST | `/api/v1/garmin/recovery-sync` | body `{"date":"YYYY-MM-DD"}` optional (default athlete today) → `{"date","updated","availableMetrics":[...],"unavailableMetrics":{metric: NO_DATA\|MALFORMED\|ERROR}}`. Future date → 422 `RECOVERY_DATE_IN_FUTURE`. Connector errors → 401/403/429/503/502 (same codes as other Garmin triggers). |
| POST | `/api/v1/garmin/recovery-sync/backfill` | `{"endDate","days"}` both optional (today, 28; 1–28 else 422). Newest day first, **sequential**, `backfill-delay` (default 2 s, `GARMIN_RECOVERY_BACKFILL_DELAY`) between days, **no retry**, stops at the first connector failure (e.g. `RATE_LIMITED`) and returns the partial result (`completed:false, stoppedAt, stoppedReason`); if the very first day fails the error is returned as HTTP error. |
| GET | `/api/v1/recovery-context?date=` | read-only: exactly the RecoveryContext the coach would receive. |

One in-JVM lock covers sync and backfill (concurrent request → 409 `GARMIN_RECOVERY_SYNC_ALREADY_RUNNING`). No
scheduler was added; starting the server never calls Garmin for recovery. Responses never contain metric values
or raw payloads (the sync response lists metric names only).

## 8. Claude prompt change

System prompt gains a "How to read the recovery data" section: what current/baseline/difference mean, that the
software has **not** rated or acted on them, `ageDays` (stale readings must be weighed and named),
`INSUFFICIENT_DATA` (do not invent a baseline), Garmin's own HRV status, partial-day Body Battery/stress, and
that signals can conflict and normal wearable numbers never override reported pain/illness/fatigue. The
missing-data rules now add "only cite recovery numbers that appear in the context" and "name what you based the
assessment on and what was unavailable". No athlete value is hard-coded; workout type, duration, intensity and
rationale remain Claude's decision.

Found during live validation: the system prompt is passed as a `--system-prompt` CLI argument, and on Windows
`ProcessBuilder` **silently drops embedded double quotes** from an argument (verified with a probe program). The
new section originally quoted field names with `"`; they are now single quotes, and a regression test asserts
the system prompt contains no `"`. (The training context travels on stdin and is unaffected.)

## 9. Evaluation scenarios (11–20, provider-neutral, synthetic)

`11-hrv-drop` · `12-resting-hr-up` (HRV + Body Battery null) · `13-short-sleep` · `14-low-body-battery` ·
`15-stress-up` · `16-recovery-normal` · `17-mixed-signals` · `18-no-recovery-data` · `19-stale-recovery`
(all readings 6 days old) · `20-fatigue-reported-wearable-normal`.

New invariants: *recovery context is reflected* (assessment names a recovery metric), *does not invent missing
metrics* (a sentence naming a null metric must say it is unavailable), *acknowledges stale data*; plus the
existing structure/arithmetic/rationale/duration/pain invariants, and every CI answer must pass the real
`WorkoutDraftValidator`. The deterministic CI coach now describes exactly the recovery data it received.
Discrimination tests prove the new invariants catch an invented HRV, an ignored HRV drop, stale data treated as
current, and invented sleep data — and accept an honest "HRV not recorded".

As in 6E, scenarios assert invariants, not one "correct" workout (no "HRV low ⇒ must be easy" assertion, which
would re-introduce the forbidden rule engine through the back door).

## 10. Tests

| Suite | Before | After | Result |
|---|---|---|---|
| Python connector (`pytest`) | 48 | **88** (+40) | 88 passed |
| Spring/Kotlin (`gradlew clean test`, JDK 21, H2) | 680 | **758** (+78) | 758 passed, 0 failed, 0 skipped |
| Subset on real PostgreSQL 17.11 | — | 72 | all passed |

(The 6D result doc reports 50 connector tests; on this PC the pre-change suite collected 48.)

Python: parsing of each metric, missing fields → null, 204/None/empty → `NO_DATA`, malformed shapes (8 cases),
wrong date, bool/NaN/negative/unsafe-status rejection, per-metric upstream error, unexpected exception without
leaking its message, 429 and auth abort with no further calls, normalized contract, no time-series/ID leakage,
API date validation and error mapping, provider session drop on auth failure.

Spring/Kotlin: migration (V7 applied, table, unique + check constraints), persistence + idempotency (insert, no-op
re-sync, in-place update, null never clears, no empty row, ordering), baseline (11 cases: mean, 6 vs 7 samples,
current excluded, gaps skipped, 28-day boundary, stale age, absent metric, no look-ahead, zero baseline, rounding,
per-metric dates), mapper (6), HTTP client (8), sync/backfill API (14 incl. 429 stop without retry, newest-first
order, single-flight 409), RecoveryContext mapping + prompt nulls + no rating fields, TrainingContext from real
repositories, draft API passes the stored recovery to the coach, `/recovery-context`, eval (10 new scenarios + 6
discrimination checks), architecture (new Garmin transport forbidden in coach code).

Two test-side fixes on existing code: `LiveClaudeCoachEvalTest` used the fixture model `"test-model"`, which the
real CLI rejects (`unrecognized_model`, exit 1) — so the 6E live eval could never have run; it now uses
`RUNNING_AI_COACH_CLAUDE_MODEL` or `sonnet`, and records each draft summary in the test report.

## 11. Live validation

| Item | Status |
|---|---|
| Garmin real read (`/recovery`) | **NOT_RUN** — this external PC has no Garmin token store (`~/.garminconnect` absent) and logging in needs the owner's credentials/MFA |
| Recovery sync against real Garmin | **NOT_RUN** (same reason) |
| 28-day backfill against real Garmin | **NOT_RUN** (same reason) |
| Baseline on real recovery data | **NOT_RUN** (no real data); baseline verified by tests and on real PostgreSQL with synthetic rows |
| Recovery-based Claude draft (real Claude CLI 2.1.287, `sonnet`, synthetic contexts) | **RUN — 18/20 scenarios passed** |

Live eval details (`gradlew test -PliveCoachEval --tests "...LiveClaudeCoachEvalTest"`, nothing published):
- All recovery scenarios 11–19 passed. Examples of Claude's own recovery assessments:
  - 11: *"HRV is well below baseline (38 ms vs 52 ms, -26.9%, Garmin status UNBALANCED), while sleep, resting
    heart rate, Body Battery and stress are all at baseline"* → EASY 40 min.
  - 12: *"resting heart rate is 8 bpm (16%) above your norm. HRV and Body Battery are unavailable, so readiness
    is uncertain."* → EASY 35 min (named the missing metrics, invented nothing).
  - 13: short/poor sleep cited with exact numbers → EASY 30 min, "no quality work today".
  - 18: *"Unknown: no HRV, sleep, resting heart rate, Body Battery or stress data is available"*.
  - 19: *"the only recovery readings are 6 days old … so they say little about how you feel now"*.
- **FAILED: 08 and 20** — in both (athlete reports exhaustion) Claude chose a **rest day** with no segments and 0
  minutes, which the 6E `WorkoutDraftValidator` rejects (≥1 segment, ≥5 min) and `workout_draft` cannot store
  (`CHECK total_duration_minutes > 0`). The coaching choice is reasonable; the gap is a pre-existing 6E contract
  conflict (the prompt offers `REST` as a type, validation/schema cannot represent a rest day). Not changed in
  this phase because it alters a 6E safety rule and needs a migration — see next steps.
- Claude also flagged in several answers that the 6E synthetic fixture is internally inconsistent (daily pattern
  all REST while `lastRunDate` is yesterday). That is a test-fixture issue in `CoachTestFixtures.recentTraining`,
  noted here, not changed.

## 12. Configuration

- `running-ai.garmin.recovery-sync.backfill-delay` (`GARMIN_RECOVERY_BACKFILL_DELAY`, default `2s`)
- `running-ai.garmin.recovery-sync.max-backfill-days` (default 28)
- No new secret, no new dependency (connector and server dependency lists unchanged).

## 13. Limitations

- No live Garmin data has gone through the pipeline yet; the projection is source-evidenced but not live-probed
  for this account (e.g. whether `avgStressLevel` is ever negative, Body Battery sampling).
- Today's Body Battery and stress are partial-day when synced in the morning; re-syncing later updates them.
- A backfill costs 5 Garmin calls per day (140 for 28 days); range methods were not used (schemas unevidenced).
- No recovery sync scheduler: data is only as fresh as the last manual sync (the coach sees `ageDays`).
- The single-flight lock is per JVM.
- Rest-day drafts are rejected (see §11).

## 14. Next steps (not started)

1. On the main PC: run the connector with the real token store, `POST /api/v1/garmin/recovery-sync`, then
   `/backfill` (28 days), check `GET /api/v1/recovery-context`, and generate a real draft — turning the NOT_RUN rows
   into results and live-probing the projected fields.
2. Decide how a REST day is represented in a draft (validator + schema + prompt contract).
3. Optional opt-in recovery sync scheduler (before the coach/publish runs), mirroring the 6D profile-sync scheduler.
4. Fix the inconsistent 6E synthetic training fixture.
5. Then the 6E recommendation: draft → approve → publish via the existing publisher.

## 15. Git

Commits on `main`: `90dfd5a` feat: add Garmin recovery intelligence · `8fd81d6` feat: integrate recovery context into AI coach · `2f6d459` test: add recovery aware coach evaluation · `44309a9` docs: record Phase 6F result, plus a follow-up docs commit recording the push. **Pushed to
`origin/main`** on the owner's request after the phase was completed.
