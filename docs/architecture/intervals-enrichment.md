# Intervals.icu Analysis Enrichment (Phase 6H-5)

Status: live contract LIVE_VERIFIED on the real account, 2026-10-03 (see the Phase 6H-5 work-order
result). Read-only towards Intervals.icu: this feature issues GETs only and has no code path to an
Intervals write.

## Roles — three layers, never merged

```text
Garmin          → sensor / source of truth      (activity, laps, zones, full samples, recovery)
RunningAI       → own detailed analysis         (activity_analysis*, RunningAI-derived metrics)
Intervals.icu   → training-load / fitness-model enrichment (this feature)
```

- Intervals never replaces Garmin data. `activity_sample` and the other Garmin detail tables are
  never written from Intervals payloads.
- Intervals values never enter `activity_analysis`: different provenance, kept in separate tables.
  A future TrainingContext V2 (Phase 6H-7) may present both, each labelled with its origin.
- Garmin Recovery stays the recovery source of truth. Intervals wellness also carries HRV / sleep /
  resting HR; those are kept only inside the stored raw payload and never touch `garmin_recovery_daily`.
- No layer judges another: RunningAI load and Intervals load may differ, and neither is declared wrong.

## Terminology — fixed once, enforced in schema

```text
CTL           = Intervals.icu CALCULATED fitness   (activity icu_ctl, wellness ctl)
ATL           = Intervals.icu CALCULATED fatigue   (activity icu_atl, wellness atl)
derived_form  = ctl - atl, RunningAI-derived from Intervals CTL/ATL, only when both exist
wellness.fatigue = SUBJECTIVE self-report — NEVER mapped to ATL, never first-class
```

The live API has no direct Form field, so `derived_form` is explicitly named as derived — it is not
presented as a source value. No table has an ambiguous `fatigue` column.

## Live contract (verified 2026-10-03)

| Endpoint (GET only) | Shape |
|---|---|
| `/api/v1/athlete/0/activities?oldest&newest` | JSON array of completed-activity objects (184 keys observed) |
| `/api/v1/activity/{id}` | one object, same shape as a list item (no extra keys) |
| `/api/v1/activity/{id}?intervals=true` | adds `icu_intervals` + `icu_groups` only (kept for reference, not normalised — Garmin laps/samples are richer) |
| `/api/v1/athlete/0/wellness?oldest&newest` | JSON array of day objects; `id` is the ISO date |

Fields used (names end at the mappers in `integration.intervals`):

- Activity: `id`, `source` (`GARMIN_CONNECT`), `external_id` (= the Garmin activity id), `type`
  (`Run` / `VirtualRun` / `VirtualRide` / `Workout`), `start_date` (ISO instant, `Z`),
  `elapsed_time` / `moving_time` (s), `distance` (m), `icu_training_load` (int),
  `icu_intensity` (float), `icu_ctl` / `icu_atl` (float, the Fitness-chart values after the
  activity — verified equal to that day's wellness `ctl`/`atl`), `analyzed` (offset timestamp).
- Wellness: `id` (date), `ctl`, `atl`, `ctlLoad`, `atlLoad`, `rampRate`, `updated`.
  `fatigue` existed and was null on every live day; it is subjective and is never read.
- Timestamps come in two live forms: `...Z` (`start_date`) and `...+00:00` (`analyzed`, `updated`).
- Rate-limit headers (`X-RateLimit-*`, `Retry-After`) were NOT present on the observed 200 responses;
  the transport still records them when they appear.
- Operational note: requests without an explicit `User-Agent` were rejected with 403 before reaching
  the API (edge/CDN filtering of the default `Python-urllib` agent during the probe). The Spring
  client sends its own agent and authenticated fine.

## Transport

`IntervalsReadClient` / `HttpIntervalsReadClient` (`integration.intervals`): GET only, structurally no
write method; Basic auth (user `API_KEY`, key from `INTERVALS_API_KEY` only) shared with the publisher
via `IntervalsHttp`; one request per call, never a retry; 401 → `AUTH_FAILED`, 403 → `FORBIDDEN`,
429 → `RATE_LIMITED` (stop — a `Retry-After` is recorded, never waited out), 5xx → `UPSTREAM_ERROR`,
timeouts distinct. Logs carry endpoint, status, item count and sanitized rate-limit values; never the
key, the Authorization header or a body.

## Raw-first storage

`intervals_raw_payload` (V18; unique athlete + payload_type + external_id):

- `ACTIVITY` — the matched activity item, `external_id` = the Intervals activity id
- `WELLNESS_DAY` — one wellness entry, `external_id` = its ISO-date id

The raw row is committed (own transaction) before any normalisation; a mapping failure never loses the
payload, and `reprocess` rebuilds normalised rows from here with zero Intervals API calls.

## Activity identity: linking, never duplicating

An Intervals activity never becomes a second RunningAI Activity row. `activity_source_link` (V17)
attaches the Intervals id to the existing Garmin-based activity:

- unique `(external_source, external_activity_id)` and `(activity_id, external_source)`
- `match_method` + delta columns (start/duration/distance) = technical matching evidence
- an Intervals activity already linked to a different RunningAI activity → `INTERVALS_LINK_CONFLICT`
  (409), never an automatic re-link

### Matching priority (`IntervalsActivityMatcher`)

1. **SOURCE_ID** — `source = GARMIN_CONNECT` and `external_id` = the Garmin activity id.
   Live-verified on 5/5 activities of the account; this is the expected path.
2. **EXTERNAL_ID** — the same explicit id without the Garmin source label.
3. **COMPOSITE** — only when no candidate carries the explicit id. A candidate that explicitly claims
   a DIFFERENT Garmin activity is excluded regardless of similarity. Remaining candidates must be
   type-compatible (live-observed pairs only: `RUN↔Run`, `TREADMILL_RUN↔VirtualRun`,
   `INDOOR_CYCLING↔VirtualRide`) and within the measured tolerances.

### Composite tolerances — measured, not guessed

Live measurement 2026-10-03 (5 Garmin↔Intervals pairs, same FIT file on both sides):
start-time delta 0 s on all 5; duration delta ≤ 1 s; distance delta ≤ 0.01 m. Production tolerances
add headroom only for whole-second truncation and metre-level rounding:

```text
|start delta|    <= 30 s
|duration delta| <= 5 s    (activity.duration_seconds vs Intervals elapsed_time)
|distance delta| <= 5 m    (skipped when either side has no distance; live: Garmin indoor 0 m ↔ Intervals null)
```

Candidates: 0 → `UNMATCHED` (a normal result — nothing is force-created), exactly 1 → `MATCHED`,
2+ → `AMBIGUOUS` (never auto-linked; the count is reported).

## Normalised enrichment tables (V18)

- `activity_intervals_metrics` (1:1 with activity): `intervals_activity_id`, `training_load`
  (`icu_training_load`), `intensity` (`icu_intensity`), `ctl_after_activity` / `atl_after_activity`
  (`icu_ctl` / `icu_atl`), `source_updated_at` (`analyzed`), `fetched_at`. Everything else stays raw.
- `intervals_fitness_daily` (unique athlete + fitness_date): `ctl`, `atl`, `derived_form`,
  `ramp_rate`, `ctl_load`, `atl_load`, `source_updated_at` (`updated`), `fetched_at`.
  Missing values stay NULL, never 0.

Upserts replace in place → a second enrichment of the same content is idempotent (no duplicate raw,
link, metrics or day rows); the V17/V18 unique keys are the last line of defence.

## Manual API (no scheduler, no webhook, no periodic sync — Phase 7 topic)

```text
POST /api/v1/intervals/enrichment/activities/{activityId}            1 Intervals GET (±1 day window)
POST /api/v1/intervals/enrichment/activities/{activityId}/reprocess  0 Intervals GETs
POST /api/v1/intervals/enrichment/fitness?oldest&newest              1 Intervals GET (range <= 31 days)
POST /api/v1/intervals/enrichment/fitness/reprocess?oldest&newest    0 Intervals GETs
GET  /api/v1/activities/{activityId}/intervals                       stored link + metrics
GET  /api/v1/intervals/fitness?oldest&newest                         stored daily snapshots
```

POSTs refresh RunningAI's own database only. The fitness window guard (31 days) exists because the
90-day historical backfill is a separate phase that has NOT been run. Per-target in-JVM single-flight;
errors map to 401/403/429/503/504/502 with `INTERVALS_*` codes.
