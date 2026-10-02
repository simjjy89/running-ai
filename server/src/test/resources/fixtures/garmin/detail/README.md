# Garmin detail fixtures (shape LIVE_VERIFIED in Phase 6H-1B)

**None of these files is a live Garmin payload.** The *shape* (keys, nesting, value types, descriptor
envelope) was verified against real payloads from the owner's Garmin account on the Main PC in Phase 6H-1B
(`docs/work-orders/2026-10-02-phase-6h-1b-garmin-detailed-live-contract-result.md`); every *value* here is
synthetic. There is no real activity id (`999000001` / `999000002`), no real GPS (the sample fixtures use
the obviously synthetic point lat `1.5` / lon `2.5`), no real date and no personal data.

| File | Live basis |
|---|---|
| `splits.LIVE_SHAPE.ANONYMISED.json` | `{activityId, lapDTOs, eventDTOs}`; lap keys, **1-based `lapIndex`** next to 0-based `messageIndex`, `startTimeGMT` as `yyyy-MM-dd'T'HH:mm:ss.S` without a zone, and `intensityType` / `wktIndex` / `wktStepIndex` — modelled on a live interval run (WARMUP → ACTIVE/RECOVERY → COOLDOWN) |
| `hr-zones.LIVE_SHAPE.ANONYMISED.json` | bare array of `{zoneNumber, secsInZone, zoneLowBoundary}`, always 5 zones, zero-time zones present as `0.0`, **no upper bound field** |
| `samples-outdoor.LIVE_SHAPE.ANONYMISED.json` | full outdoor-run descriptor set (23 metrics) in the live `metricsIndex` layout, `unit` = `{key, factor}`, **`directTimestamp` as a JSON float** (epoch ms), running dynamics null in the first samples |
| `samples-treadmill.LIVE_SHAPE.ANONYMISED.json` | treadmill descriptor set (17 metrics): no `directLatitude` / `directLongitude` / `directElevation`, and **every shared key at a different `metricsIndex` than in the outdoor payload** |

Live facts these fixtures exist to pin down:

- **`metricsIndex` is per payload.** Four live activities from one device produced four different layouts
  (`directTimestamp` sat at index 7, 5, 9 and 2). The two sample fixtures reproduce that, so any regression
  to positional parsing fails the tests.
- **`directTimestamp` arrives as a float** (`1790000000000.0`), not an integer.
- **`unit.factor` is not a divisor.** Live `sumDistance` with `factor: 100.0` already carries metres and
  `sumElapsedDuration` with `factor: 1000.0` already carries seconds; values are stored exactly as reported.
- **Absent is absent.** Live outdoor payloads carry no `directAirTemperature` descriptor, so `temperature`
  stays null; power zones for an activity without a power meter come back as `[]`, which is EMPTY, not a
  failure.

Anonymisation rule for any future update: replace the activity id, drop or replace every coordinate, and
shift dates. These fixtures exist to verify shape and field mapping, never to preserve a real workout.
