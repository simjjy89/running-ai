# Intervals.icu fixtures — LIVE_SHAPE.ANONYMISED

Shape and field names verified against real Intervals.icu responses on 2026-10-03 (Phase 6H-5 live
contract discovery, `docs/work-orders/2026-10-03-phase-6h-5-intervals-enrichment-result.md`):

- `activities-list.LIVE_SHAPE.ANONYMISED.json` — items of
  `GET /api/v1/athlete/0/activities?oldest&newest` (a JSON array; the single-activity
  `GET /api/v1/activity/{id}` returns the same object shape with no extra keys). A representative
  subset of the 184 live keys is kept; extra/unknown keys stay legal and are ignored by the mappers.
- `wellness.LIVE_SHAPE.ANONYMISED.json` — entries of
  `GET /api/v1/athlete/0/wellness?oldest&newest` (a JSON array; `id` is the ISO date).
  `ctl`/`atl` are Intervals' CALCULATED fitness/fatigue. `fatigue` is the SUBJECTIVE wellness field
  (always null on the live account; the synthetic non-null value in the third entry exists to prove the
  mapper never reads it).

Every value is synthetic or anonymised: activity/athlete/external/strava ids, dates, names, device
names are fake; no GPS, no notes, no real personal metric series. Field names and JSON types are the
live contract; values are not.
