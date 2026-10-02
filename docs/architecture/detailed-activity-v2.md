# Detailed Activity v2 — architecture (Phase 6H-1A foundation)

Goal: move RunningAI from summary-only activities to a **maximum-detail, raw-first** activity pipeline, without
breaking the existing summary pipeline and without guessing Garmin's contract.

```text
Garmin activity list ──► activity_raw (V3, unchanged) ──► activity (V2, unchanged)
                                   │
                                   └──► activity_detail                 [6H-1A, list-item keys, no new call]
Garmin per-activity parts (connector, one request each, no retry)
   detail · splits · hr-zones · power-zones · samples
        ──► activity_raw_payload (V11, raw first, own commit)
        ──► pure Garmin mappers (field names end here)
        ──► activity_lap · activity_zone · activity_sample (V12, replace per part)
        ──► activity_detail_collection (V13, per-part status)
                     │
                     ▼
        feature extraction (RunningAI analysis engine)          [later]
        Intervals.icu enrichment (analysis, linked by activity) [later]
        TrainingContext V2 for the AI coach                     [later, after 6H-1B]
```

## Sources of truth

| Layer | Source of truth for | Never |
|---|---|---|
| **Garmin** | what the sensors recorded: the activity, its laps, samples, time in zone, device data | recomputed or "corrected" by RunningAI |
| **Intervals.icu** | analysis enrichment it computes itself (its own load/fitness models, interval detection) | the owner of raw sensor data; never overwrites Garmin values |
| **RunningAI** | derived features (e.g. drift, pace/HR decoupling, lap classification) and the coaching context built from them | stored back as if Garmin had reported them |

Raw payloads are the durable truth of what a source sent. Normalised rows are a re-derivable projection: after a
mapper change, `POST /api/v1/garmin/activities/{id}/details/reprocess` rebuilds them from the stored raw payloads
without contacting Garmin.

## Raw-first rules

1. Every remote payload is stored in `activity_raw_payload` (one row per activity + payload type, replaced on re-fetch)
   **in its own committed transaction before** any mapping.
2. Mappers are pure; a mapping failure records `MAPPING_FAILED` with a code and leaves both the raw payload and the
   previously normalised rows untouched.
3. Normalised rows are replaced per part in one transaction (delete + insert), so re-collection is idempotent; unique
   keys (`activity_id + lap_index`, `activity_id + zone_type + zone_number`, `activity_id + sample_index`,
   `activity_id` for detail) make a duplicate impossible.
4. No transaction is ever open across a connector call.
5. Nothing is fabricated: a metric the source did not report is NULL; samples keep Garmin's own sampling (no
   interpolation to 1 s); zone upper bounds are not derived; no unit conversion.
6. Unknown fields are kept: lap and sample fields without a column go to `extra_metrics` (JSON) under their source key.

## Samples: descriptor-based parsing

Garmin's stream is positional: each sample is an array, and the meaning of position *i* is given by that response's
`metricDescriptors[].metricsIndex/key` (and "varies by device and activity type", per python-garminconnect). The
sample mapper builds `index → key` from **the payload being mapped** and looks every metric up by key; no index is
hard-coded. Ambiguous descriptors (two keys on one index, one key on two indexes) fail the mapping instead of
picking one.

## Partial collection

Per activity, `activity_detail_collection` holds one row per part with `NORMALIZED`, `RAW_STORED`, `EMPTY`,
`FETCH_FAILED` or `MAPPING_FAILED` (+ `error_code`, `item_count`, `attempted_at`). A run reports `COMPLETE`, `PARTIAL`
or `FAILED`. A per-part failure (404, 5xx, odd body, mapping error) lets the other parts continue; an account-level
failure (auth, forbidden, rate limit, connector down) stops the run immediately and propagates, so nothing keeps
calling Garmin. A failure is never hidden behind the already stored summary activity.

## Activity identity (decision)

Today an activity is identified by `activity.external_source + external_id` (one source per row). When Intervals.icu
enrichment arrives, the same physical workout will exist in Garmin and in Intervals.icu.

**Decision for 6H-1A: keep the current identity; no migration.** Detail tables hang off `activity.id`; raw payloads
are keyed by `external_source + external_activity_id + payload_type`, so they work for any source.

**Planned (Intervals enrichment phase):** an `activity_source` link table
`(activity_id, external_source, external_id, linked_at, link_method)` with `UNIQUE (external_source, external_id)`,
backfilled with one row per existing activity from its current columns. Garmin stays the primary source of an
activity; an Intervals.icu activity is linked to it (by its Garmin id where Intervals exposes one, else by start time +
duration tolerance), never inserted as a second activity. `activity.external_source/external_id` stays as the
primary-source key for backward compatibility. Existing rows are preserved.

## Status

| Piece | State |
|---|---|
| Static contract | STATIC_SOURCE_CONFIRMED (`garmin-detailed-activity-contract-static.md`) |
| Connector endpoints | implemented, fake-tested; **not live-verified** |
| Storage (V11–V13) | implemented; H2 + PostgreSQL 17 migration validated |
| Mappers | activity detail: library-confirmed keys; samples: confirmed envelope + PROVISIONAL metric keys; laps/zones: PROVISIONAL (synthetic fixtures) |
| Trigger | manual `POST /api/v1/garmin/activities/{garminActivityId}/details` (+ `/reprocess`); no scheduler, no backfill |
| FIT / ORIGINAL download | capability confirmed in the library; not implemented |
| Feature extraction, Intervals enrichment, TrainingContext V2 | not started |

Nothing in this layer is `LIVE_VERIFIED` until Main-PC Phase 6H-1B.
