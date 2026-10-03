# Phase 6H-6 — 90-Day Historical Backfill & Reprocessing (result)

Instruction: `docs/work-orders/2026-10-03-phase-6h-6-90-day-historical-backfill-instruction.md`.
Run on the Main PC, 2026-10-03.

No real Garmin/Intervals activity id, GPS coordinate, API key or token appears in this document.

## §82 final report

1. **Baseline SHA**: `50a59f0`.
2. **Final SHA**: the commit of this document (see §52 for the full commit list; recorded after push).
3. **Migrations**: `V19__create_historical_backfill.sql` (`historical_backfill_run`,
   `historical_backfill_activity`). V1-V18 untouched.
4. **Backfill run id/status**: run `1`, final status **COMPLETED**, final phase **COMPLETED**.
5. **Actual 90-day window**: `2026-07-06 .. 2026-10-03` (inclusive, 90 calendar days, athlete-local
   Asia/Seoul).
6. **Target activity count**: 5 (supported, in-window).
7. **Newly created activities**: 0 — all 5 already existed from earlier phases (6H-1B/6H-4/6H-5); this
   run reports them `UPDATED` (idempotent re-ingestion of the same summary).
8. **Existing updated activities**: 5.
9. **Unsupported skipped**: 2 (`breathwork` type, consistent with the 6H-1B finding of 2 breathwork
   items on this account; raw kept, no Activity row, no invented mapping).
10. **Discovery pages / Garmin calls**: 1 page at the production `page-size=100` (the account has 7
    activities total, all fit on one page); offset sequence `[0]`.
11. **Detail refetched count**: 2 — one activity whose sample stream was still `DOWNSAMPLED` from an
    earlier phase (upgraded to FULL), and one activity that had **never had its detail collected at
    all** before this run (discovered mid-backfill: the pre-backfill baseline had only 4
    `activity_detail_collection` rows for 5 activities).
12. **Detail skipped-as-already-complete count**: 3 (already FULL, no gate failures, no Garmin call).
13. **Detail COMPLETE count**: 5/5.
14. **Sample FULL count**: 5/5.
15. **Legitimate EMPTY sample count**: 0 (every supported activity in this window has a sensor stream).
16. **DOWNSAMPLED remaining**: 0 (verified by VERIFY; the live check below shows no activity in the
    window with a non-FULL stream).
17. **UNKNOWN remaining**: 0.
18. **Sample rows before/after**: `activity_sample` 6739 -> **9488** (+2749: the DOWNSAMPLED->FULL
    upgrade plus the first-ever collection of the fifth activity).
19. **Analysis `RUNNING_ANALYSIS_V1` count**: 5/5.
20. **Analysis COMPLETE/PARTIAL counts**: COMPLETE 5, PARTIAL 0.
21. **Interval analyses count**: unchanged at 2 groups / 14 intervals (only the track activity has
    interval structure; its detail was not refetched this run, so its analysis was reused, not
    recomputed — correctly, since its stored detail was already FULL and unaffected by this backfill).
22. **Intervals activity-list GET count**: 3 (deterministic ≤31-day windows covering the 90-day span).
23. **Intervals matched**: 5/5 — including the fifth activity, which this run matched and enriched via
    Intervals for the first time (all `SOURCE_ID`).
24. **Intervals unmatched**: 0.
25. **Intervals ambiguous**: 0.
26. **Link conflicts**: 0.
27. **CTL/ATL fitness days**: 90 requested, **90 stored** (one wellness GET covering the whole window;
    every returned day mapped cleanly).
28. **Intervals total GET count**: **4** (3 activity-list windows + 1 wellness; well under the 90-day
    budget the instruction sketched, and zero per-activity GETs as required by §29/§30).
29. **Intervals write count**: 0 (the read client has no write method).
30. **Garmin Recovery window**: 28 days, `2026-09-06 .. 2026-10-03`.
31. **Recovery completed days**: 28 days **attempted** (cursor advanced the full window, no pause);
    **17 days actually stored a row** — `garmin_recovery_daily` had 0 rows before this phase (first
    recovery collection ever on this account), and the oldest 11 days of the 28-day window
    (`2026-09-06 .. 2026-09-16`) returned no metric at all, which by the existing Phase 6F mapper
    semantics stores no row (not a failure). Stored dates run `2026-09-17 .. 2026-10-03`, none in the
    future.
32. **Recovery unavailable/missing metrics summary**: every stored day (17/17) has `resting_heart_rate`,
    `body_battery` and `stress` present; HRV and sleep are present from 2026-09-18 onward and absent on
    2026-09-17 only — consistent with normal per-metric source absence, not an error.
33. **Garmin 401/403/429**: 0 (live run hit no account-level Garmin failure).
34. **Intervals 401/403/429**: 0.
35. **Automatic retries**: 0, everywhere (structural: no retry code path exists in the discovery,
    detail, Intervals or recovery stages).
36. **Pause/resume actually exercised**: **not on the live run** (it completed in one pass — the
    account's real data posed no gate failure or rate limit). Pause/resume/offset-reset/no-refetch/
    no-re-request semantics are exercised by 17 automated integration tests with scripted faults
    (discovery rate-limit, discovery caps, detail integrity/fidelity/auth failures, analysis failure,
    Intervals rate-limit and link-conflict, recovery failure) — see §49/§68.
37. **`garmin_sync_state` before/after**: identical —
    `high_water_started_at = 2026-09-30T14:50:49Z`, `last_successful_sync_at = 2026-10-02T11:16:57.375Z`
    before and after the run. Confirms the backfill never read or advanced the incremental-sync
    checkpoint.
38. **Historical checkpoint state**: run 1, `discovery_complete=true`, `target_activity_count=5`,
    `completed_activity_count=5`, `fitness_day_count=90`, `recovery_day_count=28`,
    `next_recovery_date=2026-09-05` (one day before the window start, i.e. the cursor ran to
    completion), `current_phase=COMPLETED`, `status=COMPLETED`.
39. **DB size before/after**: 15 MB -> **18 MB** (`pg_database_size`), +3 MB for 90 days of fitness
    history, 28 days of recovery, and ~2749 additional full-resolution samples. This is the practical
    data point for future storage sizing (e.g. a Raspberry Pi target): roughly 3 MB added per
    first-time 90-day activity+recovery+fitness pass on a 5-activity account; the dominant cost will
    scale with activity *count* and sample density, not with this phase's logic.
40. **Raw payload rows before/after**: `activity_raw_payload` 20 -> 25 (+5, the newly/re-collected
    detail parts); `intervals_raw_payload` 18 -> **154** (+136: 90 wellness days + ~46 additional
    Intervals activity-list items seen across the three windows, each stored raw-first regardless of
    match outcome).
41. **Duplicate checks**: `activity` stayed at 5 rows (no duplicate inserts from re-ingesting the same
    summaries); `activity_source_link` 5 (one per activity, unique constraint intact);
    `activity_intervals_metrics` 5; `intervals_fitness_daily` 90; `historical_backfill_activity` has
    exactly one row per (run, external id) by its unique constraints. A dedicated automated test
    (`a second run over the same window is idempotent...`) additionally ran the full live-shaped
    pipeline twice and asserted zero duplicates across every table plus zero Garmin detail calls on
    the second pass.
42. **Long-run spot check**: FULL sample stream (2784 rows, matches the activity's own
    `source_total_metrics_count`), `RUNNING_ANALYSIS_V1` / COMPLETE, Intervals link + metrics present
    (`SOURCE_ID`). No raw id recorded here.
43. **Interval spot check**: FULL sample stream (2712 rows), lap `intensity_type` sequence confirmed
    intact (`WARMUP` x4 then `ACTIVE` at the start of the work block, matching the pre-existing 6H-1B/
    6H-4 structure), 2 interval groups / 14 intervals unchanged, Intervals link present.
44. **Treadmill spot check**: FULL sample stream, distance present (treadmill GPS is legitimately null —
    unaffected), analysis COMPLETE. One of the two treadmill activities is the one whose detail had
    never been collected before this phase; it now has detail+analysis+Intervals link exactly like the
    others.
45. **Publishing switches**: `WORKOUT_PUBLISHING_ENABLED=false`,
    `WORKOUT_PUBLISHING_SCHEDULER_ENABLED=false`, `RUNNING_AI_DRAFT_PUBLISHING_ENABLED=false`,
    `RUNNINGAI_MCP_ENABLED=false` throughout and at the end.
46. **Legacy task states**: `RunningAI-TodayWorkout`, `RunningAI-TrainingCommand`,
    `RunningAI-CommandChannel`, `RunningAI-RemoteWakeupScheduler` all confirmed **Disabled**
    immediately before the live run; none were modified. Other Scheduled Tasks (`RunningAI-Startup`,
    `RunningAI-ChatGPT-Sync`, `RunningAI-CommandBridge`, `RunningAI-DailyBrief`, `RunningAI-NewRunCheck`,
    `RunningAI-NtfyDecisionListener`, `RunningAI-TrainingDecision`, `RunningAI-WeeklyReport`,
    `RunningAI-CodexRequestWatchdog`) were left exactly as found.
47. **External workout writes**: 0.
48. **TrainingContext V2**: NOT_RUN (unchanged).
49. **Spring tests**: `gradlew clean test` (H2) **1101 passed, 0 failed, 0 skipped**
    (baseline 1073 -> +28; 24 new backfill tests + 4 schema tests, including the 17 fail-stop/
    resume-semantics scenarios referenced in §36).
50. **Python tests**: **134 passed** (connector untouched).
51. **PostgreSQL tests**: full Spring suite re-run against a throwaway PostgreSQL 17 database
    (`running_ai_test`, Hikari pool capped at 2 for the same reason documented in Phase 6H-5; dropped
    after) — **1101 passed, 0 failed**.
52. **Commits** (this session, on top of `50a59f0`):
    `4ae84fd` feat: add persistent historical backfill checkpoint ·
    `3cef563` feat: batch Intervals historical enrichment ·
    `e1db582` feat: add 90-day Garmin discovery, detail and analysis backfill ·
    `410f995` test: cover historical backfill resume and fail-stop ·
    `274d7c5` docs: document the 90-day historical data pipeline ·
    plus the docs commit containing this result document.
53. **Push**: to `origin/main` after this document was committed.
54. **Working tree**: clean after the final commit; `main = origin/main`.
55. **Known limitations / operational discovery**:
    - **Operational finding, corrected in-session**: the live server had
      `GARMIN_PROFILE_SYNC_ENABLED=true` (Phase 6D's automatic profile-sync scheduler), which the §46
      safety guard correctly refused to run under (`HISTORICAL_BACKFILL_UNSAFE_RUNTIME`). This was a
      pre-existing operational setting unrelated to this phase, not a 6H-6 baseline value, so it was
      backed up (`.runtime/secret-backup/`), temporarily set to `false` for the live run, and **restored
      to `true`** immediately afterward — the backfill's safety guard is not meant to permanently change
      unrelated operational toggles. `running-ai.garmin.scheduler.enabled` (activity sync) was already
      false and untouched.
    - Only one historical run exists so far; pause/resume and the various fail-stop paths are proven by
      automated tests with scripted faults, not by a live occurrence (§36) — the real account's data
      happened to pass every gate on the first attempt.
    - 11 of the 28 recovery days have no stored row (genuine absence of any Garmin metric for those
      calendar days on this account, not a bug) — see §31/§32.
    - Discovery's single page (account has 7 activities total) did not exercise the multi-page /
      cutoff-boundary path live; that path is covered by automated tests (empty page, short page,
      boundary activity, max-pages/max-activities caps).
    - DB-size delta (§39) is from one account's first 90-day pass and should not be extrapolated
      linearly to larger histories without accounting for activity count and sample density.
56. **Exact dataset available for Phase 6H-7**: for all 5 activities in the 90-day window — Garmin
    detail/laps/zones/FULL-resolution samples; `RUNNING_ANALYSIS_V1` (session/zone/threshold/lap/
    interval metrics, decoupling, interval repeatability where applicable); Intervals
    `activity_intervals_metrics` (training load, intensity, CTL/ATL after the activity) via
    `GET /api/v1/activities/{id}/intervals`; 90 days of Intervals CTL/ATL/derived_form/ramp_rate via
    `GET /api/v1/intervals/fitness`; 17 days of Garmin Recovery (HRV/sleep/resting HR/Body Battery/
    stress) via the existing recovery-context API. All three provenance layers (Garmin / RunningAI
    analysis / Intervals) are populated and never merged — exactly the shape TrainingContext V2 is
    meant to combine with explicit provenance labels, per the Phase 6H-5 §42 recommendation.

**Completion criteria (§81) all satisfied.**
