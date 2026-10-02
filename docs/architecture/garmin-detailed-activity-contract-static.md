# Garmin detailed activity contract — static source inventory (Phase 6H-1A)

> **Status: STATIC_SOURCE_CONFIRMED — NOT LIVE VERIFIED.** Everything here comes from reading the installed
> `garminconnect==0.3.16` source on the external PC. No Garmin request was made (the external PC's corporate TLS
> interception blocks Garmin; no SSL workaround was used). Response *shapes* below are only what the source itself
> shows. Live confirmation is Main-PC Phase 6H-1B.

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
`secsInZone`, `zoneLowBoundary`) keys used by the 6H-1A mappers are PROVISIONAL and exercised only with
`SYNTHETIC_NOT_LIVE_GARMIN` fixtures.

## Capability classification

| Layer | 6H-1A capability | Evidence | Stored as | Normalised into |
|---|---|---|---|---|
| ACTIVITY_LIST | list item | typed.Activity + CONFIRMED_LIVE | `activity_raw` (unchanged) | `activity` (unchanged) + **`activity_detail`** |
| ACTIVITY_DETAIL | `get_activity` | endpoint only | `activity_raw_payload` `ACTIVITY_DETAIL` | **raw only** until 6H-1B confirms keys |
| LAPS / SPLITS | `get_activity_splits` | endpoint only | `SPLITS` | `activity_lap` (PROVISIONAL keys) |
| ZONES | `get_activity_hr_in_timezones`, `get_activity_power_in_timezones` | endpoint only | `HR_ZONES`, `POWER_ZONES` | `activity_zone` (PROVISIONAL keys) |
| SAMPLES / STREAMS | `get_activity_details` | envelope confirmed, metric keys provisional | `ACTIVITY_DETAILS_STREAM` | `activity_sample` (+ `extra_metrics`) |
| RAW FILE | `download_activity(ORIGINAL)` (zip with the FIT) | method + URL confirmed | not stored | not implemented (binary storage + FIT parser is a separate decision) |

## Open questions for 6H-1B (live)

1. `get_activity` body: top-level keys, `summaryDTO` presence/keys, units; is it richer than the list item?
2. `get_activity_splits`: wrapper key (`lapDTOs`?), lap index base (0/1), `startTimeGMT` format, units, intervals vs
   auto-laps on a structured workout.
3. `*_in_timezones`: list vs object, zone numbering, boundary keys, whether power zones exist for a run without a
   power meter (expect `{}`/`[]`).
4. `get_activity_details`: real metric keys and units (`directHeartRate`? `directSpeed` m/s? `directTimestamp` epoch
   ms?), number of samples vs duration at `maxChartSize` 2000 vs a larger value (is Garmin down-sampling?), whether
   `maxPolylineSize` adds a GPS polyline that should be dropped or kept.
5. `download_activity(ORIGINAL)`: zip size per activity; decide FIT storage and parser separately.
