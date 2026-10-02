# Garmin detailed activity contract — static source inventory (Phase 6H-1A)

> **Status of this section: STATIC_SOURCE_CONFIRMED.** Everything down to "Capability classification" comes from
> reading the installed `garminconnect==0.3.16` source on the external PC. No Garmin request was made there (the
> external PC's corporate TLS interception blocks Garmin; no SSL workaround was used). Response *shapes* in this
> part are only what the source itself shows.
>
> **Live facts are kept separate**, in "Live verification (Phase 6H-1B)" at the end of this file: every field name,
> shape and unit there was read off real payloads from the owner's Garmin account on the Main PC. Where the two
> sections disagree, the live section wins.

Installed library: `garminconnect` **0.3.16**, at `%TEMP%\py\venv\Lib\site-packages\garminconnect\` (scratch venv on
the external PC; the connector pins the same version in `tools/garmin-connector/requirements.txt`). Files read:
`__init__.py` (4437 lines), `client.py`, `activity_details.py`, `typed.py`, `exceptions.py`, `fit.py`.

## Transport behaviour common to every method (STATIC_SOURCE_CONFIRMED)

- `Garmin.connectapi(path, **kwargs)` → `client.connectapi` → `_run_request("GET", ...)` → `resp.json()`. One HTTP GET to
  `connectapi.garmin.com` + `path`, default timeout 15 s.
- **HTTP 204 → `{}`** (an `EmptyJSONResp` whose `.json()` is `{}`). A part with no data can therefore arrive as `{}`.
- 401 on the first attempt: the session is refreshed and the same request is sent **once** more (token refresh,
  not a business retry); a second 401 → `GarminConnectAuthenticationError`.
- `@_handle_api_errors` decorator: 401 → `GarminConnectAuthenticationError`; 429 → `GarminConnectTooManyRequestsError`;
  404 → `GarminConnectNotFoundError` (subclass of `GarminConnectConnectionError`); other 4xx →
  `GarminConnectConnectionError` with `.response`; 5xx / network → **retried `retry_attempts` times with exponential
  backoff, default `retry_attempts=3`**, then `GarminConnectConnectionError`.
  → **Finding (fixed in 6H-1A):** the connector built `Garmin()` with that default, so it silently retried 5xx up to 3×,
  contradicting the connector's "no automatic retry" rule. The connector now creates `Garmin(retry_attempts=0)`
  (`garmin_connector.client.no_retry_garmin`), for every endpoint.
- Every activity-id method validates `int(activity_id) > 0` (`_validate_positive_integer`) before any request and
  sends it as a path segment; `_run_request` rejects `..`, `?`, `#`, `\` in paths.

## Activity-level methods

All are `STATIC_SOURCE_CONFIRMED`. "Requests" = Garmin HTTP requests per call (excluding the 401 refresh).

| Method (0.3.16) | Arguments | Endpoint (from source) | Return (source annotation) | Paging | activityId | Role | Requests | Exposed by connector |
|---|---|---|---|---|---|---|---|---|
| `get_activities` | `start=0, limit=20, activitytype=None, activitysubtype=None` (limit ≤ 1000) | `/activitylist-service/activities/search/activities?start&limit` | `dict \| list` (`None` → `[]`) | yes, `start`/`limit` | no | ACTIVITY_LIST | 1 | `GET /activities` (existing, CONFIRMED_LIVE in 3B-3) |
| `get_activities_by_date` | `startdate, enddate=None, activitytype=None, sortorder=None` | same search endpoint with `startDate`/`endDate` | list | yes, internal loop of 20 until empty | no | ACTIVITY_LIST | ≥1 (loops) | no |
| `get_activity` | `activity_id` | `/activity-service/activity/{id}` | `dict` — docstring: "activity summary, including basic splits" | no | yes | ACTIVITY_DETAIL | 1 | `GET /activities/{id}/detail` |
| `get_activity_splits` | `activity_id` | `/activity-service/activity/{id}/splits` | `dict` | no | yes | LAPS/SPLITS | 1 | `GET /activities/{id}/splits` |
| `get_activity_typed_splits` | `activity_id` | `/activity-service/activity/{id}/typedsplits` | `dict` — "more detail for certain activity types (e.g. Bouldering)" | no | yes | SPLITS (variant) | 1 | no (deferred) |
| `get_activity_split_summaries` | `activity_id` | `/activity-service/activity/{id}/split_summaries` | `dict` | no | yes | SPLITS (summary) | 1 | no (deferred) |
| `get_activity_hr_in_timezones` | `activity_id` | `/activity-service/activity/{id}/hrTimeInZones` | `dict` (annotation) | no | yes | ZONES (HR) | 1 | `GET /activities/{id}/hr-zones` |
| `get_activity_power_in_timezones` | `activity_id` | `/activity-service/activity/{id}/powerTimeInZones` | `dict` (annotation) | no | yes | ZONES (power) | 1 | `GET /activities/{id}/power-zones` |
| `get_activity_details` | `activity_id, maxchart=2000, maxpoly=4000` | `/activity-service/activity/{id}/details?maxChartSize&maxPolylineSize` | `dict` | no (size-capped by `maxChartSize`) | yes | SAMPLES/STREAMS | 1 | `GET /activities/{id}/samples[?maxChart=]` |
| `get_activity_weather` | `activity_id` | `/activity-service/activity/{id}/weather` | `dict` | no | yes | context | 1 | no (deferred) |
| `get_activity_exercise_sets` | `activity_id` | `/activity-service/activity/{id}/exerciseSets` | `dict` | no | yes | strength sets | 1 | no (deferred) |
| `get_activity_gear` | `activity_id` | `/gear-service/gear/filterGear?activityId` | `dict` | no | yes | gear | 1 | no (deferred) |
| `download_activity` | `activity_id, dl_fmt=TCX` (`ORIGINAL`, `TCX`, `GPX`, `KML`, `CSV`) | `/download-service/files/activity/{id}` (ORIGINAL = zip, "up to user to extract"), `/download-service/export/{tcx,gpx,kml,csv}/activity/{id}` | `bytes` | no | yes | RAW FILE | 1 | no (deferred) |
| `get_heart_rate_zones` | — | `/biometric-service/heartRateZones` | `list[dict]` | no | no | athlete zone *config* | 1 | no |
| `get_power_zones` / `get_power_zones_for_sport` | — / `sport` | `/biometric-service/powerZones/...` | list / dict | no | no | athlete zone *config* | 1 | no |

Not found in 0.3.16: any method returning parsed FIT records, any streams endpoint other than
`get_activity_details`, any method for laps other than the split family. `fit.py` contains only a FIT **encoder**
(weight / blood pressure upload), not a decoder.

## Response shapes the source actually shows

### ACTIVITY_LIST item — `typed.Activity` (pydantic aliases, all optional)

`activityId`, `activityName`, `startTimeLocal`, `startTimeGMT`, `activityType{typeId,typeKey,parentTypeId,isHidden}`,
`duration`, `movingDuration`, `elapsedDuration`, `distance`, `elevationGain`, `elevationLoss`, `averageSpeed`,
`maxSpeed`, `averageHR`, `maxHR`, `calories`, `bmrCalories`, `avgPower`, `maxPower`, `normPower`,
`aerobicTrainingEffect`, `anaerobicTrainingEffect`, `activityTrainingLoad`, `trainingEffectLabel`,
`averageRunningCadenceInStepsPerMinute`, `maxRunningCadenceInStepsPerMinute`, `totalSets`, `activeSets`, `totalReps`,
`totalVolume`. (`activityId`, `activityType.typeKey`, `startTimeGMT`, `duration` s, `distance` m, `averageHR`/`maxHR`
were also CONFIRMED_LIVE in Phase 3B-3.) **These are the keys `GarminActivityDetailMapper` uses**, so the normalised
`activity_detail` comes from the list item already stored in `activity_raw` — no new Garmin call and no guessed key.

### SAMPLES — `get_activity_details` (envelope from `activity_details.py::parse_activity_detail_metrics`)

```text
{
  "metricDescriptors":     [ {"metricsIndex": <int>, "key": <str>, ...}, ... ],
  "activityDetailMetrics": [ {"metrics": [<value at index 0>, <value at index 1>, ...]}, ... ]
}
```

Library rules (STATIC_SOURCE_CONFIRMED): the position→name mapping "varies by device and activity type"; descriptors
with a non-string `key` or a missing / non-int / negative `metricsIndex` are skipped; a sample shorter than an index
simply lacks that metric. Duration keys named in the source: `sumDuration`, `sumElapsedDuration`, `sumMovingDuration`
("not equivalent … callers must pick the one they mean"). Every other metric key (`directHeartRate`, `directSpeed`,
`directTimestamp`, `sumDistance`, `directLatitude`, …) is **not** in the source: PROVISIONAL.

### Everything else

`get_activity`, `get_activity_splits`, `*_in_timezones`, typed splits, split summaries, weather, gear: the source shows
only the endpoint and `dict` annotation; **no field names**. The lap (`lapDTOs`, …) and zone (`zoneNumber`,
`secsInZone`, `zoneLowBoundary`) keys the 6H-1A mappers were written against could therefore only be guessed here —
they are confirmed, unchanged, in the live section below.

## Capability classification

| Layer | 6H-1A capability | Evidence | Stored as | Normalised into |
|---|---|---|---|---|
| ACTIVITY_LIST | list item | typed.Activity + CONFIRMED_LIVE | `activity_raw` (unchanged) | `activity` (unchanged) + **`activity_detail`** |
| ACTIVITY_DETAIL | `get_activity` | endpoint only | `activity_raw_payload` `ACTIVITY_DETAIL` | **raw only** until 6H-1B confirms keys |
| LAPS / SPLITS | `get_activity_splits` | endpoint only | `SPLITS` | `activity_lap` (PROVISIONAL keys) |
| ZONES | `get_activity_hr_in_timezones`, `get_activity_power_in_timezones` | endpoint only | `HR_ZONES`, `POWER_ZONES` | `activity_zone` (PROVISIONAL keys) |
| SAMPLES / STREAMS | `get_activity_details` | envelope confirmed, metric keys provisional | `ACTIVITY_DETAILS_STREAM` | `activity_sample` (+ `extra_metrics`) |
| RAW FILE | `download_activity(ORIGINAL)` (zip with the FIT) | method + URL confirmed | not stored | not implemented (binary storage + FIT parser is a separate decision) |

## Open questions for 6H-1B (live) — all answered

Answered in the live section below: (1) §L1, (2) §L2, (3) §L3, (4) §L4/§L5, (5) §L6.

1. `get_activity` body: top-level keys, `summaryDTO` presence/keys, units; is it richer than the list item?
2. `get_activity_splits`: wrapper key (`lapDTOs`?), lap index base (0/1), `startTimeGMT` format, units, intervals vs
   auto-laps on a structured workout.
3. `*_in_timezones`: list vs object, zone numbering, boundary keys, whether power zones exist for a run without a
   power meter (expect `{}`/`[]`).
4. `get_activity_details`: real metric keys and units (`directHeartRate`? `directSpeed` m/s? `directTimestamp` epoch
   ms?), number of samples vs duration at `maxChartSize` 2000 vs a larger value (is Garmin down-sampling?), whether
   `maxPolylineSize` adds a GPS polyline that should be dropped or kept.
5. `download_activity(ORIGINAL)`: zip size per activity; decide FIT storage and parser separately.

---

# Live verification (Phase 6H-1B) — LIVE_VERIFIED

> **Status: LIVE_VERIFIED.** Read off real payloads from the owner's Garmin account on the Main PC on 2026-10-02
> through the localhost connector. Four activities: an outdoor run (also the account's longest), a track interval
> run, a treadmill run and an indoor cycling session. No activity id, coordinate or personal value is reproduced
> here. Full run record: `docs/work-orders/2026-10-02-phase-6h-1b-garmin-detailed-live-contract-result.md`.
>
> **No mapper key needed correcting.** Every field name the 6H-1A mappers guessed exists live; all four activities
> normalised COMPLETE on the first attempt.

## L1. `get_activity` — ACTIVITY_DETAIL (`/activities/{id}/detail`)

Top level: `activityId`, `activityUUID{uuid}`, `activityName`, `userProfileId`, `isMultiSportParent`,
`activityTypeDTO{typeId,typeKey,parentTypeId,isHidden,restricted,trimmable}`, `eventTypeDTO{typeId,typeKey,sortOrder}`,
`accessControlRuleDTO`, `timeZoneUnitDTO{unitId,unitKey,factor,timeZone}`, `metadataDTO`, **`summaryDTO`**,
**`splitSummaries`**.

`summaryDTO` (51 keys on the outdoor run) uses a *different vocabulary* from the activity-list item — e.g.
`averagePower` / `normalizedPower` / `trainingEffect` / `averageRunCadence` where the list item says `avgPower` /
`normPower` / `aerobicTrainingEffect` / `averageRunningCadenceInStepsPerMinute`. Keys seen:
`startTimeLocal`, `startTimeGMT`, `startLatitude`, `startLongitude`, `endLatitude`, `endLongitude`, `distance`,
`duration`, `movingDuration`, `elapsedDuration`, `elevationGain`, `elevationLoss`, `maxElevation`, `minElevation`,
`avgElevation`, `averageSpeed`, `averageMovingSpeed`, `maxSpeed`, `maxVerticalSpeed`, `avgGradeAdjustedSpeed`,
`calories`, `bmrCalories`, `waterEstimated`, `averageHR`, `maxHR`, `minHR`, `recoveryHeartRate`,
`averageRunCadence`, `maxRunCadence`, `averagePower`, `maxPower`, `minPower`, `normalizedPower`, `totalWork`,
`groundContactTime`, `strideLength`, `verticalOscillation`, `verticalRatio`, `steps`, `trainingEffect`,
`anaerobicTrainingEffect`, `aerobicTrainingEffectMessage`, `anaerobicTrainingEffectMessage`, `trainingEffectLabel`,
`activityTrainingLoad`, `minActivityLapDuration`, `directWorkoutFeel`, `directWorkoutRpe`,
`moderateIntensityMinutes`, `vigorousIntensityMinutes`, `differenceBodyBattery`. **No temperature key.**

`metadataDTO` carries `manufacturer`, `lapCount`, `fileFormat{formatKey:"fit"}`, `deviceMetaDataDTO{deviceId,
deviceTypePk, deviceVersionPk}` and the capability flags `hasSplits`, `hasChartData`, `hasHrTimeInZones`,
`hasPowerTimeInZones`, `hasPolyline`, `hasIntensityIntervals`.

**Is it richer than the list item? Yes**, but only additively: `minHR`, `minPower`, `steps`, `recoveryHeartRate`,
running dynamics, `splitSummaries`, device and timezone metadata. Everything `activity_detail` normalises today is
already present in the list item, so **ACTIVITY_DETAIL stays `RAW_STORED`**; promoting these extras is a later
decision, not a correction.

`splitSummaries` is embedded here (not a separate call): one entry per `splitType`, live values `PACE_PRO_SPLIT`,
`RWD_RUN`, `RWD_STAND`, each with `noOfSplits` and aggregate metrics.

## L2. `get_activity_splits` — SPLITS (`/activities/{id}/splits`)

Shape: `{"activityId": <int>, "lapDTOs": [...], "eventDTOs": [...]}` — the guessed `lapDTOs` wrapper is correct.

- **`lapIndex` is 1-based** (1…N) and sits next to a **0-based `messageIndex`**. The mapper keeps the source's
  `lapIndex`, so `activity_lap.lap_index` starts at 1.
- `startTimeGMT` is `"2026-09-30T14:50:49.0"` — ISO local date-time, fractional second, **no zone designator**.
  This is *not* the activity-list form (`yyyy-MM-dd HH:mm:ss`); `parseGmt` already accepted both.
- Every lap key the mapper reads exists live: `duration`, `elapsedDuration`, `movingDuration`, `distance`,
  `averageSpeed`, `maxSpeed`, `averageHR`, `maxHR`, `averageRunCadence`, `maxRunCadence`, `averagePower`,
  `maxPower`, `elevationGain`, `elevationLoss`, `calories`.
- Further live lap keys (all preserved in `extra_metrics`): `messageIndex`, **`intensityType`**, **`wktIndex`**,
  **`wktStepIndex`**, `averageMovingSpeed`, `normalizedPower`, `minPower`, `maxElevation`, `minElevation`,
  `maxVerticalSpeed`, `avgGradeAdjustedSpeed`, `bmrCalories`, `totalWork`, `groundContactTime`, `strideLength`,
  `verticalOscillation`, `verticalRatio`, `lengthDTOs`, `connectIQMeasurement`, `directWorkoutComplianceScore`,
  `startLatitude`, `startLongitude`, `endLatitude`, `endLongitude`.
- Key count varies by activity: 38 (outdoor run), 40 (interval run), 31 (treadmill — no elevation/GPS keys),
  18 (indoor cycling — no running keys).
- `eventDTOs` entries carry only `startTimeGMT`, `startTimeGMTDoubleValue`, `sectionTypeDTO`. Not mapped.

### Interval / recovery representation (live)

`intensityType` is the answer, per lap: live values **`WARMUP`, `ACTIVE`, `RECOVERY`, `COOLDOWN`**. On the track
interval run the 24 laps read `WARMUP x4 → ACTIVE x10 → (RECOVERY, ACTIVE) x4 → COOLDOWN x3`.

**A Garmin lap is not a structured-workout step.** `wktStepIndex` on the same activity ran
`0,0,0,0, 1,1,1,1,1,1,1,1,1, 2, 3,2,3,2,3,2,3, 5,5, —`: one step spans nine laps, steps 2 and 3 alternate as a
repeat block, step 4 never appears, and the last lap carries no step at all. Never assume lap ↔ step is 1:1.

## L3. `*_in_timezones` — ZONES (`/activities/{id}/hr-zones`, `/power-zones`)

Shape: a **bare JSON array**, exactly `[{"zoneNumber": <int>, "secsInZone": <float>, "zoneLowBoundary": <int>}, …]`
— the guessed keys are correct.

- Always **5 zones**, numbered 1…5, never partial.
- A zone with no time is present with `"secsInZone": 0.0`, not omitted.
- **There is no upper-bound field.** `activity_zone.max_value` therefore stays NULL; it is never derived from the
  next zone's lower boundary.
- Total zone time ≈ activity duration (outdoor run: 2783.4 s of zone time against a 2783 s activity).
- `zoneLowBoundary` is bpm for HR zones, watts for power zones.
- **Running power exists** on this device: all three runs returned five populated power zones.
- **Power zones for an activity without a power meter come back as `[]`** — an empty array, not `{}`, not `null`,
  not 404. The indoor-cycling session returned `[]`, which the ingestion records as part status `EMPTY`.

## L4. `get_activity_details` — SAMPLES (`/activities/{id}/samples`)

Top level: `activityId`, `measurementCount`, `metricsCount`, **`totalMetricsCount`**, `metricDescriptors`,
`activityDetailMetrics`, `geoPolylineDTO`, `heartRateDTOs`, `pendingData`, `detailsAvailable`.

Descriptor: `{"metricsIndex": <int>, "key": <str>, "unit": {"key": <str>, "factor": <float>}}`.
Sample row: `{"metrics": [ … ]}`, one positional array per sample.

**Every metric key the mapper guessed exists live**: `directTimestamp`, `sumElapsedDuration`, `sumDistance`,
`directSpeed`, `directHeartRate`, `directRunCadence`, `directPower`, `directElevation`, `directLatitude`,
`directLongitude`. Other live keys (kept in `extra_metrics`): `sumDuration`, `sumMovingDuration`,
`directGradeAdjustedSpeed`, `directFractionalCadence`, `directDoubleCadence`, `directBodyBattery`,
`directVerticalSpeed`, `directVerticalOscillation`, `directVerticalRatio`, `directStrideLength`,
`directGroundContactTime`, `sumAccumulatedPower`, `directPerformanceCondition`.
**`directAirTemperature` was not present on any of the four activities**, so `activity_sample.temperature` stays
NULL — correctly, not fabricated.

Three live facts the mapping depends on:

1. **`metricsIndex` is per payload, and genuinely unstable.** The four activities — one device, one account —
   produced four different layouts; `directTimestamp` sat at index **7, 5, 9 and 2**. Even the two outdoor runs
   disagreed. Positional parsing would silently mis-assign every metric.
2. **`directTimestamp` is a JSON float** holding whole epoch milliseconds (`1789772410000.0`), not an integer.
   It is UTC: the first sample's timestamp equalled the activity's stored `started_at` exactly.
3. **`unit.factor` is not a conversion divisor.** `sumDistance` carries `factor: 100.0` and metres;
   `sumElapsedDuration` carries `factor: 1000.0` and seconds; `directElevation` carries `factor: 100.0` and metres.
   The last sample of the outdoor run reported `sumDistance` equal, to the bit, to the activity total distance.
   Values are stored exactly as reported.

Descriptor sets by activity type: outdoor run 23 metrics (GPS, elevation, grade-adjusted speed, running dynamics);
track interval run 22 (as outdoor, without `directGradeAdjustedSpeed`); treadmill 17 (**no `directLatitude`,
`directLongitude`, `directElevation`, `directVerticalSpeed`**, but speed, cadence, HR, power and full running
dynamics); indoor cycling 7 (`sumElapsedDuration`, `sumDuration`, `directTimestamp`, `sumMovingDuration`,
`sumDistance`, `directHeartRate`, `directBodyBattery` — no running dynamics, no power, no GPS).

`maxPolylineSize` is not sent by the connector. The payload does carry `geoPolylineDTO`; it stays in the stored raw
payload and is not normalised.

## L5. Down-sampling — `maxChartSize` (`/samples?maxChart=`)

Measured on the long outdoor run (2783 s recorded):

| Request | samples returned | `metricsCount` | `totalMetricsCount` | response | latency |
|---|---|---|---|---|---|
| default (library `maxchart=2000`) | 1399 | 1399 | 2784 | 1.15 MB | 1025 ms |
| `maxChart=20000` | **2784** | 2784 | 2784 | 1.51 MB | 1444 ms |

**Verdict: the default is `DOWNSAMPLED`; `maxChart=20000` is `FULL_SAMPLE`.** First and last timestamps are
identical in both; the default spacing is irregular (1,1,1,1,2,2 s) while the full stream is a uniform 1 s;
HR min/max are identical (108/192) and the mean differs slightly (185.01 vs 185.23).

`totalMetricsCount` always reports the native count, so a stored stream can always be told apart from a complete
one without asking Garmin again. Native sampling here is 1 Hz (2784 samples over 2783 s) — measured, not assumed;
no cadence is extrapolated to other devices or activity types.

Shorter activities are unaffected: the treadmill run (1801 s) returned 1801 of 1801 and the indoor cycle 788 of 788.

> **Operational consequence, acted on in Phase 6H-1C:** `running-ai.garmin.detail.samples-max-chart-size` now
> defaults to **20000** and is always sent, so a stream is collected at full resolution unless the operator lowers
> it. Because asking for 20000 is not a guarantee, every collection also records what the response itself said —
> `requested_max_chart_size`, `source_metrics_count`, `source_total_metrics_count` and
> `sample_completeness` (FULL / DOWNSAMPLED / UNKNOWN) in `activity_detail_collection` (V14). The rule and its
> UNKNOWN cases are in `detailed-activity-v2.md`, "Sample fidelity policy". Re-collecting the long outdoor run
> under that policy turned 1399 of 2784 (DOWNSAMPLED) into 2784 of 2784 (FULL).

## L6. `download_activity(ORIGINAL)` — RAW FILE

Probed read-only on one activity: returns `bytes`, 101,575 bytes, magic `504b0304` — a **ZIP** containing exactly
one `.fit` entry of 237,402 bytes uncompressed. Nothing was stored in the repository and no FIT content was parsed.

## L7. Capability classification after live verification

| Layer | Capability | Live status | Normalised into |
|---|---|---|---|
| ACTIVITY_LIST | list item | `CONFIRMED_LIVE` (all 20 mapper keys present) | `activity_detail` |
| ACTIVITY_DETAIL | `get_activity` | `CONFIRMED_LIVE` (shape in §L1) | raw only, by decision |
| LAPS / SPLITS | `get_activity_splits` | `CONFIRMED_LIVE` | `activity_lap` (+ `extra_metrics`) |
| ZONES (HR) | `get_activity_hr_in_timezones` | `CONFIRMED_LIVE` | `activity_zone` |
| ZONES (power) | `get_activity_power_in_timezones` | `CONFIRMED_LIVE`; `NOT_AVAILABLE` = `[]` | `activity_zone` |
| SAMPLES | `get_activity_details` | `CONFIRMED_LIVE` | `activity_sample` (+ `extra_metrics`) |
| RAW FILE | `download_activity(ORIGINAL)` | `CONFIRMED_LIVE` (zip + 1 FIT) | not stored |
| TYPED SPLITS / SPLIT SUMMARIES | `get_activity_typed_splits`, `get_activity_split_summaries` | `NOT_RUN` — not needed | — |

### Why typed splits and split summaries were not added

The 6H-1A static study flagged both as possible gaps. Live payloads close that gap without a new endpoint:

- interval vs recovery: `lapDTOs[].intensityType` (§L2);
- lap grouping to workout steps: `lapDTOs[].wktIndex` / `wktStepIndex` (§L2);
- running/cycling split metadata: `splitSummaries`, already inside the `get_activity` payload, which is stored
  raw (§L1).

Adding `/typed-splits` or `/split-summaries` now would mean two more Garmin requests per activity for data already
in hand, so neither was implemented.
