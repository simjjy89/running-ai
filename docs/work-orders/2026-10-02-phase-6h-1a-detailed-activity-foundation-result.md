# Phase 6H-1A — Garmin Detailed Activity Contract & Foundation — Result

Instruction (verbatim): `2026-10-02-phase-6h-1a-detailed-activity-foundation-instruction.md`.

- **Actual Garmin API calls: 0.** No Garmin login was attempted; no `verify=False`, `--insecure`, `PYTHONHTTPSVERIFY=0`
  or any SSL workaround was used.
- **Historical backfill: NOT_RUN.**
- **External workout writes: 0.** No Intervals call, no publishing.
- **Detailed Activity contract: STATIC_SOURCE_CONFIRMED / PROVISIONAL — not LIVE_VERIFIED** (Main-PC 6H-1B).

## 1. Baseline

`main` = `origin/main` = `f5db39b`, clean. Spring/Kotlin 858, Python 88.

## 2. Installed library

`garminconnect==0.3.16` (same pin as `tools/garmin-connector/requirements.txt`), scratch venv
`%TEMP%\py\venv\Lib\site-packages\garminconnect\`. The portable Python and the scratch PostgreSQL binaries had been
partly deleted by a temp-folder cleanup since the previous phase; both were re-extracted from their original archives
(python NuGet package, zonky `embedded-postgres-binaries` jar) and the PG data directory's pruned empty sub-directories
were recreated. No new software was installed and nothing was downloaded.

## 3. Discovered detailed methods / static contract

Full inventory: `docs/architecture/garmin-detailed-activity-contract-static.md`. Summary (all STATIC_SOURCE_CONFIRMED):

| Method | Endpoint | Used |
|---|---|---|
| `get_activity(id)` | `/activity-service/activity/{id}` | yes — raw only |
| `get_activity_splits(id)` | `.../{id}/splits` | yes — laps |
| `get_activity_typed_splits(id)` / `get_activity_split_summaries(id)` | `.../typedsplits`, `.../split_summaries` | no (deferred) |
| `get_activity_hr_in_timezones(id)` / `get_activity_power_in_timezones(id)` | `.../hrTimeInZones`, `.../powerTimeInZones` | yes — zones |
| `get_activity_details(id, maxchart=2000, maxpoly=4000)` | `.../{id}/details?maxChartSize&maxPolylineSize` | yes — samples |
| `get_activity_weather` / `get_activity_exercise_sets` / `get_activity_gear` | weather / exerciseSets / gear | no (deferred) |
| `download_activity(id, ORIGINAL\|TCX\|GPX\|KML\|CSV)` | `/download-service/files/activity/{id}` (zip) and `/export/...` | no — **FIT capability exists, not implemented** |

Shapes visible in the source: activity-list item keys (`typed.Activity`), and the sample envelope
`metricDescriptors[].metricsIndex/key` + `activityDetailMetrics[].metrics` (`activity_details.py`). Nothing else.

Two transport findings: HTTP 204 is returned as `{}`; and **the library retries 5xx/network failures 3× with backoff by
default** (`retry_attempts=3`). The connector used that default, contradicting its "no automatic retry" rule; it now
creates `Garmin(retry_attempts=0)` for every endpoint (existing ones included).

## 4. Connector endpoints added

`GET /activities/{id}/detail`, `/splits`, `/hr-zones`, `/power-zones`, `/samples[?maxChart=1..100000]`. Positive
64-bit id validated (400 otherwise, no call); one library call each; body returned untouched (object / array / `{}` /
`null`); new error code `GARMIN_NOT_FOUND` (404); auth failure drops the cached gateway; connector logs never contain the
activity id. No normalisation in Python.

## 5. DB migrations (V1–V10 untouched)

- **V11 `activity_raw_payload`** — `athlete_id`, `external_source`, `external_activity_id`, `payload_type`, `payload`
  (JSONB / H2 JSON), `fetched_at`, `created_at`, `updated_at`; `UNIQUE (external_source, external_activity_id, payload_type)`.
- **V12** `activity_detail` (1:1, `UNIQUE activity_id`), `activity_lap` (`UNIQUE activity_id, lap_index`),
  `activity_zone` (`UNIQUE activity_id, zone_type, zone_number`, type HEART_RATE/POWER), `activity_sample`
  (`UNIQUE activity_id, sample_index`, `extra_metrics` JSON). All metrics nullable DOUBLE PRECISION.
- **V13 `activity_detail_collection`** — per activity + part: `status` (NORMALIZED / RAW_STORED / EMPTY / FETCH_FAILED
  / MAPPING_FAILED), `error_code`, `item_count`, `attempted_at`.

## 6. Normalised entities, raw types

Kotlin package `com.runningai.activity.detail`: `ActivityDetailData`, `LapData`, `ZoneData`, `SampleData` (provider-
neutral, no Garmin names), entities + repositories, `ActivityRawPayloadStore`, `ActivityDetailStore`.
Raw types (`DetailPayloadType`): `ACTIVITY_LIST` (stays in `activity_raw`), `ACTIVITY_DETAIL`, `SPLITS`, `HR_ZONES`,
`POWER_ZONES`, `ACTIVITY_DETAILS_STREAM`. Not implemented: `TYPED_SPLITS`, `SPLIT_SUMMARIES`, `FIT`.

- **Activity detail**: from the activity-list item already in `activity_raw`, using only `typed.Activity` keys
  (duration, elapsed/moving duration, distance, avg/max speed, avg/max HR, avg/max running cadence, elevation gain/loss,
  calories, avg/max/normalised power, aerobic/anaerobic TE, training load, TE label). No new Garmin call. Not mapped:
  min/max elevation, temperature, device metadata (no static key evidence; still in the raw).
- **`get_activity`**: stored raw only (status `RAW_STORED`); normalised after 6H-1B confirms its keys.
- **Laps** (PROVISIONAL `lapDTOs` shape): index (source `lapIndex`, else list position), start time (GMT, UTC), durations,
  distance, speeds, HR, cadence, power, elevation, calories; other fields → `extra_metrics`.
- **Zones** (PROVISIONAL): zone number, lower boundary, seconds in zone; upper bound never derived.
- **Samples**: see §7; one row per source sample, no interpolation.

## 7. Descriptor mapping strategy

`GarminSampleMapper` builds `metricsIndex → key` from the descriptors **of the payload being mapped**, then reads every
metric by key. Library skip rules applied (non-string key, non-int/negative index); a sample shorter than an index lacks
that metric; two keys on one index or one key on two indexes → `AMBIGUOUS_METRIC_DESCRIPTOR` (mapping fails, raw kept).
Column keys (PROVISIONAL): `directTimestamp` (epoch ms), `sumElapsedDuration`, `sumDistance`, `directSpeed`,
`directHeartRate`, `directRunCadence`, `directPower`, `directElevation`, `directLatitude`, `directLongitude`,
`directAirTemperature`. Every other reported metric is kept in `extra_metrics` under its own key. Tested with heart rate
at index 5 in one payload and index 0 in another.

## 8. Partial collection and idempotency

`GarminActivityDetailIngestionService.collect(id)`: requires an already ingested activity (else 404, no call);
activity-list part → each remote part: fetch → raw stored (own commit) → mapper → rows replaced (own transaction) →
status recorded. Per-part failures (404, 5xx, odd body, mapping) continue with the next part; AUTH_REQUIRED, FORBIDDEN,
RATE_LIMITED, UNAVAILABLE record the part and stop the run, then propagate (HTTP 401/403/429/503). Outcome: COMPLETE (all
attempted parts ok, none skipped) / PARTIAL / FAILED (nothing succeeded). Idempotent: raw replaced per (activity, type);
detail upserted; laps, zones-of-a-type and samples deleted and re-inserted in one transaction; a mapping failure keeps
the previous rows. `reprocess(id)` re-maps stored raw payloads without contacting Garmin. In-JVM single-flight per id.
Manual endpoints: `POST /api/v1/garmin/activities/{garminActivityId}/details` and `/details/reprocess`.

## 9. Identity decision

`activity.external_source + external_id` unchanged. Planned `activity_source` link table for Intervals enrichment
(documented in `docs/architecture/detailed-activity-v2.md`); not migrated now.

## 10. Tests

| Run | Result |
|---|---|
| Spring/Kotlin `gradlew clean test` (JDK 21, H2) | **918 passed, 0 failed, 0 skipped** (858 → 918, +60) |
| Python connector `pytest` (full) | **134 passed** (88 → 134, +46) |
| PostgreSQL 17.11 upgrade | existing V10 DB with a synthetic pre-6H activity + activity_raw row → V11, V12, V13 `success = t`; seed rows intact; `payload` / `extra_metrics` are `jsonb`; all unique/check constraints present |
| PostgreSQL 17.11 subset | `SchemaMigrationTest`, `integration.garmin.*` (existing + detail), `draftpublish.*`, `coach.WorkoutDraft*`: **394 passed, 0 failed** |
| Live Claude eval | not run — no prompt or TrainingContext change |

New Spring tests: `GarminActivityDetailMappersTest` (22), `HttpGarminActivityDetailSourceTest` (22),
`GarminActivityDetailIngestionTest` (14) = 58; the other +2 are the existing `GarminSyncSchedulerTest` and
`GarminProfileSyncSchedulerTest`, which are parameterised over `GarminConnectorException.Reason` and now also cover
the new `NOT_FOUND` reason. `SchemaMigrationTest` updated for V11–V13. Existing activity ingestion, recovery,
draft/publish suites unchanged and green.

## 11. Limitations

- Every lap/zone/sample metric key and every unit is PROVISIONAL; the `get_activity` body is not normalised.
- `maxChartSize` default 2000 may down-sample long activities (to be measured in 6H-1B); configurable via
  `running-ai.garmin.detail.samples-max-chart-size`.
- No FIT/ORIGINAL download, no typed splits / split summaries / weather / gear, no batch or scheduled collection, no
  historical backfill, no read API for the detail tables, single-flight per JVM only.
- TrainingContext unchanged (by instruction).

## MAIN_PC_6H_1B_LIVE_CHECKLIST

Run on the main PC only (real Garmin token store, no TLS interception). **Never commit real activity ids, GPS, or raw
personal payloads**; record ids only in a local, uncommitted note. Stop at the first 401/403/429.

Preconditions
- [ ] `git pull` to this phase's commit; `gradlew clean test` green; `pytest` green.
- [ ] Connector: `python -m garmin_connector status` → tokens VALID; `serve` running on 127.0.0.1.
- [ ] Spring running against the main-PC PostgreSQL; Flyway applied V11–V13 (`select version, success from flyway_schema_history`).
- [ ] `WORKOUT_PUBLISHING_ENABLED` and `RUNNING_AI_DRAFT_PUBLISHING_ENABLED` still false.

Pick 3–5 activities already in `activity` (from `/api/v1/garmin/sync`), one per kind if they exist:

| # | Kind | Activity id (local note only) |
|---|---|---|
| A | outdoor run (GPS) | `<OUTDOOR_RUN_ID>` |
| B | treadmill run | `<TREADMILL_RUN_ID>` |
| C | structured interval workout | `<INTERVAL_RUN_ID>` |
| D | long run (≥ 90 min) | `<LONG_RUN_ID>` |
| E | indoor cycling (if any) | `<INDOOR_CYCLING_ID>` |

For **each** activity X (space requests a few seconds apart):

1. Connector, raw (save each body to a local, uncommitted file, e.g. `%TEMP%\6h1b\X-<part>.json`):
   - [ ] `GET /activities/X/detail` — top-level keys? `summaryDTO`? fields and units vs the list item (duration s,
     distance m, speed m/s?). Device metadata keys? Temperature? min/max elevation?
   - [ ] `GET /activities/X/splits` — wrapper key (`lapDTOs`?), lap count vs watch laps, `lapIndex` base 0/1,
     `startTimeGMT` format, per-lap keys and units; on C: are work/recovery reps separate laps? any `intensityType`?
   - [ ] `GET /activities/X/hr-zones` — array or object? keys (`zoneNumber`, `secsInZone`, `zoneLowBoundary`?), zone
     count, does Σ secsInZone ≈ duration?
   - [ ] `GET /activities/X/power-zones` — present for runs with wrist power? `{}`/`[]` when absent?
   - [ ] `GET /activities/X/samples` — `metricDescriptors` keys + units + indexes (do they differ between A/B/E?),
     `activityDetailMetrics` count vs duration (sampling interval), `directTimestamp` unit, presence of lat/lon on A and
     absence on B/E; then `GET /activities/X/samples?maxChart=20000` on D — more samples than at 2000?
   - [ ] (FIT capability, read-only probe, optional) `download_activity(X, ORIGINAL)` from a Python shell: zip size,
     contains one `.fit`? Do not store it in the repo.
2. Spring:
   - [ ] `POST /api/v1/garmin/activities/X/details` → note `outcome` and each part's `status` / `itemCount` / `errorCode`.
   - [ ] Run it again → identical row counts in `activity_raw_payload` (5), `activity_lap`, `activity_zone`,
     `activity_sample`, `activity_detail` (1), `activity_detail_collection` (6) for X.
   - [ ] Spot-check `activity_detail` vs Garmin Connect UI (distance, durations, avg HR, cadence, TE, load).
   - [ ] Spot-check 3 samples (HR, speed, elapsed) and 2 laps against the Garmin Connect charts/laps.
3. Decide per item and record in the 6H-1B result: CONFIRMED_LIVE / CORRECTED (mapper key/unit change + a new anonymised
   fixture replacing the `SYNTHETIC_NOT_LIVE_GARMIN` one) / NOT_AVAILABLE. After any mapper correction run
   `POST /api/v1/garmin/activities/X/details/reprocess` (no Garmin call) and re-check.

Only after this checklist passes may the contract be marked `LIVE_VERIFIED` and work on feature extraction /
TrainingContext V2 start.

## 12. Git

Commits on top of `f5db39b`:
- `45cbfcf` feat: add detailed activity storage model
- `d4ab537` feat: add Garmin detailed activity connector foundation
- `ecd82e1` test: cover detailed activity ingestion
- docs commit containing this file (SHA in the final report)

Push: plain fast-forward `git push origin main` after verifying `origin/main` is still `f5db39b`; no force.
