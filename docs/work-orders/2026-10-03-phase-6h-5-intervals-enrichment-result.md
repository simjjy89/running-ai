# Phase 6H-5 — Intervals.icu Analysis Enrichment (result)

Instruction: `docs/work-orders/2026-10-03-phase-6h-5-intervals-enrichment-instruction.md`.
Run on the Main PC, 2026-10-03. The phase was executed in two sessions: the read-only client,
`IntervalsHttp` extraction and client tests landed first (`22eae06`); this session rotated the key
(§3 HUMAN GATE), ran the live contract discovery (§7–§11) and built matching, storage, enrichment,
API, tests, PostgreSQL verification and the live smoke.

No API key, Authorization header, raw response body or real GPS appears in this document, in any
committed file or in any log. Key comparison used truncated SHA-256 fingerprints only.

## §49 final report

1. **Baseline SHA**: phase instruction baseline `3faa065`; this session continued from `22eae06`
   (= `3faa065` + the read-only client commit), `main = origin/main`, clean.
2. **Final SHA**: the commit of this document (recorded in the completion report); code commits in §38.
3. **API key rotation**: `INTERVALS_API_KEY_ROTATED`. Owner regenerated the key and replaced it in the
   Main-PC local `.env`. Verified by SHA-256 fingerprint comparison against the pre-rotation `.env`
   backups: old `daa7c2ac…` ≠ new `8674e4f1…` (12-hex-prefix only; values never printed). No live
   Intervals call was made before this check passed.
4. **Intervals auth validation**: Basic auth (`API_KEY:<key>`) with the rotated key → HTTP 200.
   Operational finding: the very first probe call was rejected **403 before reaching the API** because
   it carried no explicit `User-Agent` (default `Python-urllib` blocked at the edge/CDN). Per the stop
   rule the run halted; one diagnostic retry with an explicit UA (`RunningAI-contract-probe/6H-5`)
   returned 200, proving the 403 was transport-level, not an auth failure. The Spring `RestClient`
   sends its own UA and authenticated live without any workaround. No 401 occurred at any point.
5. **Activity list**: `GET /api/v1/athlete/0/activities?oldest&newest` → JSON array of
   completed-activity objects; 7 items for 2026-09-18..10-01; **184 distinct keys** inventoried.
6. **Activity detail**: `GET /api/v1/activity/{id}` → one object, **exactly the same shape as the list
   item** (0 extra keys, identical values) — so one list GET serves matching + metrics, and per-activity
   detail GETs are unnecessary for enrichment.
7. **`intervals=true`**: `GET /api/v1/activity/{id}?intervals=true` adds exactly two keys,
   `icu_intervals` (25 entries on the track session; 84 keys per entry) and `icu_groups`. Confirmed
   real, kept for reference only; NOT normalised (Garmin laps/samples remain the source of truth) and
   not stored in this phase.
8. **Wellness**: `GET /api/v1/athlete/0/wellness?oldest&newest` → JSON array of day objects
   (46 keys), `id` = ISO date.
9. **Confirmed activity fields** (live): `id` str; `source` = `GARMIN_CONNECT`; `external_id` = the
   Garmin activity id verbatim; `strava_id` present; `type` ∈ {Run, VirtualRun, VirtualRide, Workout}
   (observed); `start_date` ISO instant `…Z`; `start_date_local` (no zone); `elapsed_time` /
   `moving_time` int s; `icu_recording_time`; `distance` m float (null for indoor cycling/breathwork);
   `icu_training_load` int; `icu_training_load_data`; `icu_intensity` float (null when no load);
   `trimp`, `hr_load`+`hr_load_type` (HRSS), `pace_load`+`pace_load_type` (RUN, runs only),
   `average_heartrate`/`max_heartrate` int, `pace`/`average_speed` m/s, `analyzed`/`icu_sync_date`/
   `created` offset timestamps, `device_name`, `file_type` = fit, `lthr`, `icu_hr_zones`,
   `interval_summary`, `stream_types`. Timestamp forms differ: `start_date` ends in `Z`;
   `analyzed`/`updated` carry `+00:00` (mappers parse via OffsetDateTime).
10. **Confirmed CTL field**: activity `icu_ctl` (float) and wellness `ctl` (float) = Intervals
    **calculated fitness**; cross-checked equal for each activity day (e.g. 09-30: 13.40901 both).
11. **Confirmed ATL field**: activity `icu_atl` / wellness `atl` (float) = Intervals **calculated
    fatigue**; same cross-check held (09-30: 15.408868 both).
12. **Form**: no direct Form field exists in the live activity or wellness payloads →
    `derived_form = ctl - atl`, stored under that name and documented as **RunningAI derived from
    Intervals CTL/ATL**, set only when both inputs are present.
13. **Subjective fatigue handling**: wellness `fatigue` exists and was **null on all 14 live days**; it
    is a subjective self-report, is never read by `IntervalsWellnessMapper`, has no column anywhere, and
    a regression test feeds a synthetic non-null `fatigue` to prove ATL never picks it up.
14. **Raw payload table/types**: `intervals_raw_payload` (V18) — `ACTIVITY` (matched list item, keyed by
    the Intervals activity id, `effective_date` = athlete-local start date) and `WELLNESS_DAY` (keyed by
    the ISO-date id). JSONB on PostgreSQL; own committed transaction before any normalisation; refetch
    replaces payload + fetched_at in place (unique athlete+type+external_id).
15. **Source-link table**: `activity_source_link` (V17) — activity_id FK, external_source,
    external_activity_id, match_method (CHECK SOURCE_ID/EXTERNAL_ID/COMPOSITE), evidence deltas
    (start/duration s, distance m), matched_at; unique `(external_source, external_activity_id)` and
    `(activity_id, external_source)`. The Garmin identity on `activity` is untouched; Intervals never
    creates a second Activity row.
16. **Matching priority**: 1) SOURCE_ID (`source=GARMIN_CONNECT` ∧ `external_id` = Garmin id) →
    2) EXTERNAL_ID (same id, source not confirming Garmin) → 3) COMPOSITE, only when no explicit-id
    candidate exists; a candidate explicitly claiming a *different* Garmin id is excluded from
    composite regardless of similarity; already-linked-elsewhere → `INTERVALS_LINK_CONFLICT` (409),
    never auto re-linked.
17. **Exact composite tolerance** (from the live measurement, 5 pairs: Δstart 0 s on 5/5,
    Δduration ≤ 1 s, Δdistance ≤ 0.01 m): `|start| ≤ 30 s`, `|duration| ≤ 5 s`
    (`duration_seconds` vs `elapsed_time`), `|distance| ≤ 5 m` (skipped when either side lacks
    distance — live: Garmin indoor 0 m ↔ Intervals null). Type compatibility is live-observed pairs
    only: RUN↔Run, TREADMILL_RUN↔VirtualRun, INDOOR_CYCLING↔VirtualRide. Candidates: 0 → UNMATCHED
    (normal), 1 → MATCHED, 2+ → AMBIGUOUS (never auto-linked).
18. **Matched**: 4/4 target activities, all `SOURCE_ID` — outdoor long run (Δ 0s/0s/+0.00004m),
    track interval (0s/+1s/0m), treadmill (0s/−1s/−0.00008m), indoor cycling (0s/0s/—).
19. **Unmatched**: 0.
20. **Ambiguous**: 0.
21. **Enrichment table/fields**: `activity_intervals_metrics` (V18, unique activity_id) —
    `intervals_activity_id`, `training_load` (`icu_training_load`), `intensity` (`icu_intensity`),
    `ctl_after_activity`/`atl_after_activity` (`icu_ctl`/`icu_atl`), `source_updated_at` (`analyzed`),
    `fetched_at`. Everything else stays in raw. Never merged into `activity_analysis`.
22. **Daily fitness fields**: `intervals_fitness_daily` (V18, unique athlete+fitness_date) — `ctl`,
    `atl`, `derived_form`, `ramp_rate` (`rampRate`), `ctl_load`/`atl_load` (`ctlLoad`/`atlLoad`),
    `source_updated_at` (`updated`), `fetched_at`; nulls stay null. Garmin Recovery untouched —
    wellness HRV/sleep/restingHR live only inside the raw payload.
23. **Idempotency**: live re-enrichment of the long run and a second identical fitness run → rows stayed
    4 links / 4 metrics / 4 ACTIVITY raws / 14 WELLNESS_DAY raws / 14 fitness days (no duplicates,
    upsert in place); also covered by H2+PG tests.
24. **Reprocess network calls**: 0 (live reprocess of the long run and of the 14-day fitness window;
    the Spring log shows no additional `Intervals read completed` lines; enforced in tests with a
    call-counting fake client).
25. **Intervals GET call count**: **15 total** (< 20 budget): probe 8 (1×403 UA-blocked, then
    1 activity list, 4 activity details, 1 `intervals=true`, 1 wellness) + Spring live smoke 7
    (5 activity-list windows = 4 enrich + 1 idempotency re-run, 2 wellness = enrich + re-run).
    All sequential, no parallelisation.
26. **401/403/429 count**: 401 = 0; 429 = 0; 403 = 1 (the UA-less first probe call; run stopped,
    diagnosed with one UA-corrected call, documented in §4).
27. **Rate-limit headers**: `X-RateLimit-Limit` / `X-RateLimit-Remaining` / `Retry-After` were absent on
    every observed 200 response; the transport logs them whenever present.
28. **Garmin API calls**: 0 (the connector was restarted as part of the runtime restart but served no
    Garmin request; all activity data came from the existing DB).
29. **Intervals write calls**: 0 — structurally: enrichment can only reach `IntervalsReadClient`, which
    declares no POST/PUT/DELETE.
30. **Historical backfill**: NOT_RUN (fitness endpoint additionally hard-caps the window at 31 days).
31. **TrainingContext V2**: NOT_RUN (TrainingContext untouched; integration is Phase 6H-7).
32. **External workout writes**: 0.
33. **Publishing switches**: `WORKOUT_PUBLISHING_ENABLED=false`,
    `WORKOUT_PUBLISHING_SCHEDULER_ENABLED=false`, `RUNNING_AI_DRAFT_PUBLISHING_ENABLED=false`,
    `RUNNINGAI_MCP_ENABLED=false` — unchanged throughout; legacy writer tasks remain Disabled.
34. **Flyway migrations**: `V17__create_activity_source_link.sql`,
    `V18__create_intervals_enrichment.sql` (3 tables). Verified on H2, on a throwaway PostgreSQL 17
    (`running_ai_test`, full suite, then dropped) and applied to the live Main DB by the runtime
    restart (history rows 17/18 success; pre-existing data intact: 5 activities, 6739 samples).
35. **Spring tests**: `gradlew clean test` (H2) **1073 passed, 0 failed, 0 skipped**
    (baseline 1029 → +44).
36. **Python tests**: **134 passed** (connector untouched).
37. **PostgreSQL tests**: the full Spring suite also ran green against PostgreSQL 17
    (`--rerun-tasks`, datasource override, `json_type=JSONB`, Hikari pool capped at 2 because ~40 cached
    test contexts otherwise exhaust `max_connections` — the first PG attempt failed with
    "too many clients already"). PG also surfaced that a failed insert aborts the transaction, so the
    new schema tests hold exactly one constraint violation per @Transactional test.
38. **Commits** (this session, on top of `22eae06`):
    `07a79e9` feat: add Intervals enrichment storage model ·
    `19e3c98` feat: add read-only Intervals activity and fitness enrichment ·
    `0866a08` test: cover Intervals enrichment ·
    `c685222` docs: describe the Intervals enrichment architecture ·
    plus the docs commit containing this result document.
39. **Push**: pushed to `origin/main` after this document was committed (see the completion report).
40. **Working tree**: clean after the final commit; `main = origin/main`.
41. **Known limitations**:
    - `intervals=true` interval detail is verified but not stored/normalised (deliberate; Garmin laps
      and samples are richer). If ever stored, it belongs in `intervals_raw_payload` as a new type.
    - Composite matching covers only live-observed type pairs; an unobserved pair (e.g. outdoor ride)
      fails closed to UNMATCHED until observed and added.
    - Activity enrichment window is the athlete-local date ±1 day; an activity whose Intervals copy
      carries a start date differing by more than a day would be UNMATCHED.
    - Rate-limit visibility is logging-only (headers were absent live); counts are not persisted.
    - `activity_intervals_metrics.source_updated_at` maps `analyzed`; Intervals may re-analyze later —
      refreshing requires a manual re-enrichment (no scheduler by design).
    - The wellness fetch upserts every returned day; days outside Intervals' own data (e.g. future
      dates in range) simply do not appear in the response.
42. **Recommended exact inputs for Phase 6H-6 / 6H-7**:
    - 6H-6 (if it is the backfill phase): Garmin side 90-day detailed backfill NOT yet run; Intervals
      side use `POST /api/v1/intervals/enrichment/fitness` semantics but lift the 31-day cap behind an
      explicit new endpoint/flag, sequential, ≤ ~10 GETs (90 days = 1 wellness GET + windowed activity
      lists), stop on 429.
    - 6H-7 (TrainingContext V2): inputs now available per activity — Garmin detail/laps/zones/samples,
      `activity_analysis` (RunningAI-derived), `activity_intervals_metrics` + `intervals_fitness_daily`
      (Intervals-derived); present all three with provenance labels, CTL=fitness ATL=fatigue
      terminology fixed, never letting the coach confuse `derived_form` or subjective wellness fields
      with source values. `GET /api/v1/activities/{id}/intervals` and `GET /api/v1/intervals/fitness`
      are the stable read surfaces.

## Continuation instruction (verbatim constraints honoured)

The resumption instruction for this session fixed: Intervals read-only GET only · Intervals writes 0 ·
Garmin calls 0 · no automatic retry · stop on 401/403/429 · no key/Authorization/raw-secret logging ·
all four publishing switches false · backfill NOT_RUN · TrainingContext V2 NOT_RUN · external workout
writes 0 · no field guessed into a production mapper before the live response · CTL = calculated
fitness, ATL = calculated fatigue, wellness.fatigue never confused with ATL. All honoured as reported
above.
