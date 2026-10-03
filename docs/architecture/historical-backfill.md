# Historical Backfill (Phase 6H-6)

Manual, resumable completion of the recent training-data foundation:

```text
90-day activity window                      28-day recovery window
Garmin summary -> Garmin detail             Garmin Recovery (HRV, sleep, resting HR,
(raw, laps, zones, FULL samples)            Body Battery, stress) - deliberately NOT
-> RunningAI Analysis V1                    widened to 90 days
-> Intervals activity enrichment
-> Intervals CTL/ATL fitness history
```

Package `com.runningai.backfill`; persistent state in V19 (`historical_backfill_run`,
`historical_backfill_activity`). Triggered only by `POST /api/v1/historical-backfill`
(`{"endDate","days"}`, days 1..90), resumed only by `POST /api/v1/historical-backfill/{runId}/resume`,
inspected by `GET /api/v1/historical-backfill/{runId}`. No scheduler, no startup run, no background
executor: the pipeline runs synchronously on the request thread. In-JVM single-flight plus a DB-level
check (an existing RUNNING run refuses a new start: `HISTORICAL_BACKFILL_ALREADY_RUNNING`).

## Phases and resume semantics

```text
GARMIN_DISCOVERY -> GARMIN_DETAIL_ANALYSIS -> INTERVALS_ACTIVITIES -> INTERVALS_FITNESS
-> GARMIN_RECOVERY -> VERIFY -> COMPLETED
```

The run row stores the current phase; `PAUSED` (safe external/data stop — resumable by a human,
never automatically) vs `FAILED` (invariant/code failure — never resumable) vs `COMPLETED`
(resume refuses deterministically with `HISTORICAL_BACKFILL_ALREADY_COMPLETED`, zero network calls).
Resume keeps the run's window, days and id, and re-enters at the stored phase:

- **GARMIN_DISCOVERY** — always restarts from offset 0. Offset pagination drifts when new activities
  appear; everything discovery does (summary ingestion, item upsert) is idempotent, so re-walking is
  safe and losing an activity is not. Once a full pass completes, the target set is frozen
  (`ordinal` = started_at ASC) and never re-opened.
- **GARMIN_DETAIL_ANALYSIS** — continues at the first activity whose detail+analysis checkpoint is
  incomplete; finished activities are never re-fetched from Garmin.
- **INTERVALS_*** — idempotent window refetch (raw/link/metric/fitness upserts replace in place).
- **GARMIN_RECOVERY** — continues at the persisted `next_recovery_date` cursor (newest -> oldest);
  completed days are never re-requested.

## Discovery (Garmin)

`GarminActivitySource.fetchActivities(start, 100)` newest -> oldest from offset 0, until a page
contains an activity older than the window start, a short/empty page (history end), or a safety cap
(`max-pages` / `max-activities` -> `BACKFILL_DISCOVERY_LIMIT_EXCEEDED`, PAUSED — never a silent
truncation). In-window items go through the EXISTING `GarminActivityIngestionService` (raw-first,
idempotent create/update); unsupported types are recorded `SKIPPED_UNSUPPORTED` (raw kept, no invented
mapping). **`garmin_sync_state` is never read or advanced** — the incremental sync's high-water mark is
a different mechanism with different semantics, and backfill has no dependency that could touch it.

## Detail + analysis (oldest -> newest, one checkpoint per activity)

Skip gate (§20): stored detail passes only if all six parts are recorded, none FETCH_FAILED /
MAPPING_FAILED, and the sample stream is NORMALIZED+FULL or legitimately EMPTY. **DOWNSAMPLED and
UNKNOWN never pass — they are precisely what this run must refetch.** A refetch uses the existing
`GarminActivityDetailIngestionService.collect` (5 sequential part requests), with a configurable
`activity-delay` (default 2 s) between Garmin-touching activities and no retry anywhere.

Gates after a fetch: outcome must be COMPLETE (a normal EMPTY part such as power zones does not break
it); a NORMALIZED stream must be FULL (`SAMPLE_STREAM_DOWNSAMPLED` / `SAMPLE_STREAM_FIDELITY_UNKNOWN`
pause the run — never an automatic retry at a bigger `maxChartSize`; the operator reviews
`GARMIN_DETAIL_SAMPLES_MAX_CHART_SIZE` and resumes); an EMPTY stream is `NO_SAMPLE_STREAM`, a legitimate
state (lap/zone analysis still runs, nothing is fabricated). Account-level Garmin failures
(401/403/429/connector down) pause the whole run immediately.

Analysis: `RunningActivityAnalysisService.analyse` (no network). A fresh `RUNNING_ANALYSIS_V1` analysis
is reused only when the detail was NOT refetched in this run; refetched detail always recomputes, so no
stale derived analysis survives. An analysis failure pauses (`ANALYSIS_FAILED`) — never skipped past.

## Intervals (read-only, fixed call shape)

Lists in deterministic, non-overlapping ≤31-day windows (90 days ≈ 3 GETs); every returned item is
upserted raw-first into `intervals_raw_payload` (ACTIVITY); matching is then fully local via the 6H-5
matcher (SOURCE_ID -> EXTERNAL_ID -> COMPOSITE with the measured tolerances). **Never** a per-activity
`GET /activity/{id}`, never `intervals=true` — Garmin laps/samples remain the source of truth.
UNMATCHED / AMBIGUOUS are per-activity statuses and never stop the run (Intervals is secondary);
a source-link conflict (the Intervals activity already belongs to a different local activity) is a
data-integrity pause (`INTERVALS_LINK_CONFLICT`). Fitness: ONE wellness GET over the whole window
(this path is separate from the manual enrichment endpoint, whose 31-day guard is unchanged);
CTL = calculated fitness, ATL = calculated fatigue, `derived_form = ctl - atl`, subjective
`wellness.fatigue` is never read, missing days create no row. Any Intervals transport/auth/429 failure
pauses; stored Garmin work is untouched and resume re-enters at the Intervals phase.

## Recovery (28 days, newest first)

`GarminRecoverySyncService.syncDay(date)` per day from the run's end date down to end-27, with the
existing `running-ai.garmin.recovery-sync.backfill-delay` between days, the persisted
`next_recovery_date` cursor committed after every day, and a pause on the first connector failure.
Missing metrics on a day are a normal source absence (nullable storage, not a failure); a day with no
value at all stores no row, by the Phase 6F design.

## Verify (local only) and completion

After all network phases: every supported target has its activity row, detail passing the same gates,
`RUNNING_ANALYSIS_V1` analysis, and an explicit Intervals status (MATCHED backed by link+metrics);
across ALL window activities no DOWNSAMPLED (`VERIFY_DOWNSAMPLED_REMAINING`) and no UNKNOWN
(`VERIFY_FIDELITY_UNKNOWN`) stream remains; fitness recorded; the recovery cursor finished its window.
Only then does the run become COMPLETED.

## Safety

Start/resume refuse (`HISTORICAL_BACKFILL_UNSAFE_RUNTIME`) unless ALL of
`running-ai.workout-publishing.enabled`, `...workout-publishing.scheduler.enabled`,
`...draft-publishing.enabled`, `...mcp.enabled`, `...garmin.scheduler.enabled`,
`...garmin.profile-sync.scheduler.enabled` are false. The backfill never publishes anything: no
Intervals POST/PUT, no workout publisher, no Garmin push — external workout writes stay 0.
TrainingContext is untouched (V2 is Phase 6H-7); FIT ingestion stays not-implemented (6H-1B decision).
