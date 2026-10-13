# Intervals.icu-only activity import (Phase 7E-1)

Package `com.runningai.integration.intervals` (`IntervalsActivityImportService`,
`IntervalsImportCandidateMapper`, `IntervalsStreamMapper`) + `com.runningai.enrichment`
(`IntervalsActivityImportStore`, V25 `intervals_activity_import`).

## 1. Why this path exists

Phase 7E-0 established the boundary that makes the existing backfill insufficient:

* Garmin Connect holds **nothing before 2026-09-18** — 10 activities total on the whole account.
* The 6H-6 historical backfill is driven entirely by `GARMIN_DISCOVERY`; every later phase enriches the
  `activity` rows that discovery produced.
* Therefore the **27 sessions of 2026-08-02 … 09-13 that exist only in Intervals.icu**
  (`source = OAUTH_CLIENT`, recorded by Intervals Companion and an Apple Watch) can never be reached by
  widening the backfill window. A full 90-day re-run added **0 rows**, measured.

So the import is a separate, explicitly triggered path. It is not a second backfill: it has no Garmin
dependency, no phase machine and no 90-day limit.

## 2. Boundaries

| Rule | How it is enforced |
|---|---|
| Read-only towards Intervals.icu | the only transport dependency is `IntervalsReadClient`, which declares no write method; `getActivityStreams` was added to it and to the GET-only HTTP implementation |
| Never a second row for a workout RunningAI already has | every candidate is matched against the stored activities of its own day via `IntervalsActivityMatcher` before anything is created (§4) |
| Never a synthesised Garmin id | the `activity` row's external identity is `INTERVALS_ICU` + the Intervals id verbatim (`i180579445`) |
| An activity Intervals received *from* Garmin is not this importer's | `source = GARMIN_CONNECT` → linked to the existing Garmin activity, or `SKIPPED_GARMIN_SOURCED` |
| No invented lap | all 27 report `icu_lap_count = 0`; `activity_lap` is never written, and the emptiness is recorded as a `SPLITS` part with status `EMPTY` |
| No invented zone | Intervals' own `icu_hr_zone_times` is a **seven**-zone model while every stored `activity_zone` row is Garmin's **five**-zone one; mixing them would make a zone number mean two things, so Intervals zones are not imported (they survive in the raw payload) |
| Off by default | `running-ai.intervals.activity-import.enabled` / `RUNNING_AI_INTERVALS_ACTIVITY_IMPORT_ENABLED` = `false` |
| Not while anything can publish | start refuses with `INTERVALS_ACTIVITY_IMPORT_UNSAFE_RUNTIME` while any of the two workout-publishing switches, draft publishing, or either Garmin scheduler is on. `running-ai.mcp.enabled` is deliberately **not** in that list: the MCP server's only write tool goes through `running-ai.workout-publishing.enabled`, which is, and the read-only MCP tools have to stay usable for verifying what an import produced |

## 3. Live contract (measured 2026-10-13 on the real account, all 27 activities)

Nothing below was taken from documentation.

**`GET /api/v1/athlete/{id}/activities`** — the list item carries everything the import needs; no
per-activity GET is made. Live: 71 items over 2026-07-02 … 10-11, `source` ∈ {`GARMIN_CONNECT` 10,
`OAUTH_CLIENT` 27, `STRAVA` 34}.

* `start_date` is an ISO instant with `Z`; `start_date_local` is a local date-time with no offset, and
  equals `start_date` seen from `Asia/Seoul` on **27/27**. The payload's own `timezone` field is null.
* `external_id` of an `OAUTH_CLIENT` activity is a client-generated **UUID** — never a Garmin id.
* `strava_id` is present on 26 of the 27 and is the same workout's Strava identity (kept for Phase 7E-2).
* `icu_lap_count` is **0 on all 27**.
* A `STRAVA`-sourced item is a stub: `{id, icu_athlete_id, start_date_local, source, _note}` and nothing
  else — no type, no duration, no distance.

**`GET /api/v1/activity/{id}/streams`** — an array of stream objects
`{type, name, data, data2, valueType, valueTypeIsArray, anomalies, custom, allNull}`.

* Within one activity **every** stream's `data` has the same length (27/27), so position *i* is the same
  instant in every stream. Different lengths are rejected (`INTERVALS_STREAMS_LENGTH_MISMATCH`) rather
  than truncated to the shortest.
* `time` carries **elapsed seconds, not indices**: its last value equals the activity's `elapsed_time`
  (27/27) while the array is shorter whenever the recording had gaps. A sample's instant is
  `start_date + time[i]`, never `start_date + i`.
* `icu_median_time_delta` is 1 on all 27 — the streams are native 1 Hz.
* `latlng` puts **latitude in `data` and longitude in `data2`**.
* 13 types occur, with per-activity coverage that varies: `time` 27, `heartrate` 27, `distance` 26,
  `velocity_smooth` 26, `cadence` 25, `watts`/`altitude`/`latlng`/`torque`/`fixed_altitude` 19,
  `stance_time`/`vertical_oscillation` 18, `step_length` 17.
* A type can be present and entirely null (`watts` on a run with no power meter). Null stays null.
* The endpoint takes **no size or sampling parameter** — there is nothing to ask for and nothing to tune.

## 4. Flow

```
GET activities (1 call for the whole window)
  → IntervalsImportCandidateMapper.map            (Intervals field names end here)
  → per candidate, oldest first:
       stub with no data            → SKIPPED_NO_SOURCE_DATA
       already imported, unchanged  → SKIPPED_ALREADY_IMPORTED          (no stream fetch)
       source = GARMIN_CONNECT      → LINKED_EXISTING / SKIPPED_GARMIN_SOURCED
       one stored activity matches  → LINKED_EXISTING
       2+ stored activities match   → SKIPPED_AMBIGUOUS                 (never merged)
       no RunningAI ActivityType    → SKIPPED_UNSUPPORTED_TYPE
       otherwise:
         intervals_raw_payload(ACTIVITY)          ← summary, own committed transaction
         ActivityService.upsertExternalActivity   ← INTERVALS_ICU + i<id>
         GET streams
         activity_raw_payload(ACTIVITY_DETAILS_STREAM, source INTERVALS_ICU)  ← own committed transaction
         activity_detail + activity_sample        ← pure mappers
         activity_detail_collection               ← per-part status + fidelity
         activity_intervals_metrics               ← via the existing 6H-5 mapper
         RunningActivityAnalysisService.analyseIfNeeded
       → one committed row in intervals_activity_import
```

**Identity.** The duplicate search runs `IntervalsActivityMatcher` unchanged, one stored activity at a
time, over the ±1 day window of the candidate: identity first (a payload naming an upstream id), then the
live-measured composite tolerances (type pair, |Δstart| ≤ 30 s, |Δduration| ≤ 5 s, |Δdistance| ≤ 5 m).
Several independent signals must agree, and **two agreeing candidates are reported, never resolved**.

**Duration.** `activity.duration_seconds` is `icu_recording_time` — the seconds the device actually
recorded, the analogue of Garmin's `duration`, which is what every other activity row holds and what
duration-based training load is computed from. `elapsed_time` would include standing still (live: up to
297 s on one run) and would overstate exactly those sessions' load. `elapsed_time` and `moving_time` are
both preserved in `activity_detail`.

**Validation before any row is written** (a row that lies about when or how long a session was poisons
every derived number): start instant present; `start_date_local` really is `start_date` in the athlete
timezone, else `INTERVALS_TIMEZONE_MISMATCH`; duration > 0; a reported distance not negative.

## 5. The one unit conflict, and why nothing is converted

Intervals reports running cadence as **one-leg revolutions per minute**; Garmin's `directRunCadence` —
the only thing that has ever been in `activity_sample.cadence` — is **steps per minute**. The factor is
exactly 2, verified arithmetically on the live data (`distance / duration = 2 × cadence / 60 ×
average_stride`, to 4 significant figures).

Verifying a factor is not permission to apply one. `activity_sample` keeps values exactly as the source
reported them (6H-1A rule), and putting rpm into a column every other row reads as spm would make a wrong
number indistinguishable from a right one. So:

* the cadence stream is stored under its own source key in `extra_metrics`, unconverted;
* `activity_sample.cadence` and `activity_detail.average_running_cadence_spm` stay **null**;
* every affected import row records `CADENCE_SOURCE_RPM_NOT_NORMALIZED` in `quality_flags`.

**Consequence, stated plainly: cadence metrics are unavailable for Intervals-imported activities** until a
unit-normalisation phase is decided on. The same applies to the running-dynamics streams (`stance_time` ms,
`vertical_oscillation` mm, `step_length` mm), which have no sample column at all and are likewise kept
unconverted in `extra_metrics` — and which the analysis engine reads from **laps**, of which these
activities have none.

## 6. Sample fidelity is UNKNOWN, deliberately

V14's `FULL` means "every point the source says the activity has was stored". Intervals.icu states no
native point count and its streams endpoint has no down-sampling parameter, so there is no source
statement to check against. The honest classification is `UNKNOWN`, with `source_metrics_count` = the
stored point count and `source_total_metrics_count` = null.

This is why the 6H-6 backfill's final VERIFY sweep is now scoped to `external_source = GARMIN`: that sweep
states what *Garmin* collection must achieve, and an activity another source produced was never collected
by that pipeline and cannot be repaired by it.

## 7. Idempotency, resume and retry

One row per (athlete, Intervals activity) in `intervals_activity_import` is simultaneously the idempotency
key, the resume point, the progress log, the quality record and the cross-system provenance record. It is
committed as each activity finishes (`REQUIRES_NEW`), so:

* **re-POSTing is the resume** — finished activities are skipped from their committed record and no stream
  is re-fetched; there is no separate resume endpoint and no run state machine to get stuck in;
* **re-POSTing is also the retry** — only `FAILED` and not-yet-imported candidates are attempted again,
  and `attempt_count` accumulates so a repeated failure is visible;
* an activity is re-imported only when Intervals' own `analyzed` timestamp has changed (`REFRESHED`).

There is no automatic retry anywhere. A per-activity failure is recorded and the run continues; an
account-level failure (`NOT_CONFIGURED` / `AUTH_FAILED` / `FORBIDDEN` / `RATE_LIMITED` / `TIMEOUT` /
`CONNECTION_FAILED`) stops the run where it stands and is reported as `stopReason`.

## 8. API

| Endpoint | Effect |
|---|---|
| `POST /api/v1/intervals/activity-import` `{"oldest","newest","dryRun"}` | import / resume / retry; `dryRun` reports what each candidate would become, writing nothing and fetching no stream |
| `GET /api/v1/intervals/activity-import` | every recorded decision, oldest session first; DB-only |
| `POST /api/v1/intervals/activity-import/{intervalsActivityId}/reprocess` | re-normalise one activity from its stored raw payloads; **zero** Intervals calls |

No scheduler, no startup trigger, no MCP tool.

## 9. What an imported activity can and cannot support

| | Available | Not available |
|---|---|---|
| Activity row | type, start (UTC + verified local), recorded duration, distance, avg/max HR | — |
| Detail | elapsed / moving / recording duration, distance, avg/max speed, avg/max HR, elevation, calories | running cadence (unit conflict), Garmin training effect, Garmin training load |
| Samples | 1 Hz time base, distance, speed, HR, power, elevation, lat/lng; cadence, torque, fixed altitude, stance time, vertical oscillation, step length in `extra_metrics` | temperature |
| Analysis | session metrics, half splits, speed-HR decoupling, LTHR exposure | HR-zone exposure (no zones imported), lap metrics, running dynamics, interval groups (**the source has no laps, so interval structure is structurally impossible — it is read, never inferred**) |
| Enrichment | Intervals training load, intensity, CTL (calculated fitness) and ATL (calculated fatigue) after the activity | — |
