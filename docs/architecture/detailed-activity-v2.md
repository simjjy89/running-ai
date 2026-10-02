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

**Live evidence (6H-1B) that this is not over-engineering:** four activities from one device on one account
produced four different layouts — `directTimestamp` sat at index 7 (outdoor run), 5 (track interval run), 9
(treadmill) and 2 (indoor cycling), and even the two outdoor runs disagreed. Two further live facts the mapper
depends on: `directTimestamp` arrives as a **JSON float** holding whole epoch milliseconds, and the descriptors'
`unit.factor` is **not** a conversion divisor (values already carry the stated unit), which is why nothing is
scaled on the way in. Details in `garmin-detailed-activity-contract-static.md` §L4.

### Sampling resolution (live)

The library default `maxchart=2000` **down-samples**: a 2783 s run returned 1399 of its 2784 native (1 Hz) samples,
with irregular spacing, while `maxChart=20000` returned all 2784 with the same first and last timestamp. Each
payload reports its native count in `totalMetricsCount`, so a down-sampled stream is always recognisable after the
fact. `running-ai.garmin.detail.samples-max-chart-size` is still unset — raising it is an ingestion-policy decision
for a later phase, not part of contract verification (§L5).

## Partial collection

Per activity, `activity_detail_collection` holds one row per part with `NORMALIZED`, `RAW_STORED`, `EMPTY`,
`FETCH_FAILED` or `MAPPING_FAILED` (+ `error_code`, `item_count`, `attempted_at`). A run reports `COMPLETE`, `PARTIAL`
or `FAILED`. A per-part failure (404, 5xx, odd body, mapping error) lets the other parts continue; an account-level
failure (auth, forbidden, rate limit, connector down) stops the run immediately and propagates, so nothing keeps
calling Garmin. A failure is never hidden behind the already stored summary activity.

**Live check (6H-1B): a normal absence is not a failure.** An activity recorded without a power meter answers the
power-zone endpoint with an empty array `[]`, which the ingestion records as `EMPTY` with `item_count = 0` — the
indoor-cycling session came back `EMPTY` while the run still reported `COMPLETE`. `ACTIVITY_DETAIL` is recorded as
`RAW_STORED` by design (see Status below).

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

## Lap semantics (live, 6H-1B)

Garmin marks work and rest on the lap itself: `lapDTOs[].intensityType` is one of `WARMUP`, `ACTIVE`, `RECOVERY`,
`COOLDOWN`, and `wktIndex` / `wktStepIndex` tie a lap back to the structured workout it was run against. All three
are preserved in `activity_lap.extra_metrics`; RunningAI never re-derives interval structure from pace or HR while
the device already states it.

**A lap is not a workout step.** On a live interval run, one step spanned nine laps, two steps alternated as a
repeat block, one step index never appeared, and the final lap carried no step at all. Any future analysis must
group laps by `wktStepIndex`, never assume a 1:1 mapping.

`lapIndex` is 1-based in Garmin payloads (the 0-based counter is `messageIndex`), so `activity_lap.lap_index`
starts at 1.

## FIT decision (6H-1B): **A — API detail is sufficient; FIT stays archival/optional**

`download_activity(ORIGINAL)` was probed read-only once: a 101 KB ZIP holding one 237 KB `.fit`. It is not needed
for the analysis this pipeline is being built for:

- sample resolution is not a reason — `maxChartSize` already returns the full native 1 Hz stream (§L5);
- running dynamics are not a reason — ground contact time, vertical oscillation, vertical ratio, stride length and
  running power all arrive as ordinary sample metrics;
- interval structure is not a reason — `intensityType` / `wktStepIndex` carry it on each lap.

What FIT would still add is per-record device-native precision, lap/session message fields Garmin does not expose
over the API, and an archival original that survives any Garmin-side edit. That is a *preservation* argument, not a
capability gap, so FIT ingestion is recorded as a **next-phase candidate** (binary storage + a decoder are a
separate decision) and nothing was implemented here.

## Status

| Piece | State |
|---|---|
| Garmin detailed contract | **LIVE_VERIFIED** (Phase 6H-1B; live section of `garmin-detailed-activity-contract-static.md`) |
| Connector endpoints | implemented; all five **live-verified** against four real activities |
| Storage (V11–V13) | implemented; H2 + PostgreSQL 17 migration validated; live ingestion + idempotent re-collection verified |
| Mappers | activity detail, laps, zones, samples: **CONFIRMED_LIVE, no key needed correcting**; fixtures now carry live shapes with synthetic values |
| `get_activity` body | shape confirmed live; deliberately still `RAW_STORED` (adds only extras the list item lacks) |
| Trigger | manual `POST /api/v1/garmin/activities/{garminActivityId}/details` (+ `/reprocess`); no scheduler, no backfill |
| Sample resolution | default is down-sampled above ~2000 native samples; `samples-max-chart-size` left unset on purpose |
| FIT / ORIGINAL download | capability live-probed (ZIP + 1 FIT); decision A — not implemented |
| Typed splits / split summaries | not needed (`intensityType` + `wktStepIndex` + embedded `splitSummaries`); not implemented |
| Feature extraction, Intervals enrichment, TrainingContext V2 | not started |

Historical backfill is still `NOT_RUN`: 6H-1B touched exactly four activities.
