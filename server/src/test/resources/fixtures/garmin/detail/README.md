# Garmin detail fixtures (Phase 6H-1A)

**None of these files is a live Garmin payload.** All values and ids are synthetic; there is no real GPS,
no real activity id, no personal data.

| File | Kind | Basis |
|---|---|---|
| `samples.LIBRARY_SHAPE.SYNTHETIC_NOT_LIVE_GARMIN.json` | library-shape envelope, synthetic values | envelope `metricDescriptors[].metricsIndex/key` + `activityDetailMetrics[].metrics` is STATIC_SOURCE_CONFIRMED by python-garminconnect 0.3.16 `activity_details.py`; the `direct*`/`sumDistance` keys and units are PROVISIONAL |
| `splits.SYNTHETIC_NOT_LIVE_GARMIN.json` | synthetic | `lapDTOs` and lap keys are PROVISIONAL (the library only shows the endpoint, not the body) |
| `hr-zones.SYNTHETIC_NOT_LIVE_GARMIN.json` | synthetic | `zoneNumber` / `secsInZone` / `zoneLowBoundary` are PROVISIONAL |

Replace or confirm each against a real payload in Main-PC Phase 6H-1B (see the 6H-1A result document,
`MAIN_PC_6H_1B_LIVE_CHECKLIST`). Real payloads must be anonymised before they are committed (no activity
ids, no GPS).
