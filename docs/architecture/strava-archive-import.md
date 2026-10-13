# Strava archive import (Phase 7E-2)

Package `com.runningai.integration.strava` + `com.runningai.enrichment` (Kotlin), migration **V26**.
Supersedes the preparation note [`strava-archive-recovery.md`](strava-archive-recovery.md), whose
open questions are answered here by measurement.

## 1. Why a file importer exists at all

The athlete's data is split across three systems and **no single one holds the history**:

| Source | Holds | Earliest |
|---|---|---|
| Garmin Connect | 10 activities | 2026-09-18 |
| Intervals.icu | 71 (10 Garmin dupes + 27 own + 34 data-less Strava stubs) | 2026-08-02 |
| Strava export | **94 activities** | **2026-06-02** |

Phase 7E-1 recovered the 27 Intervals-only sessions. The remainder is reachable by **no API**:
Intervals.icu answers `422 Cannot read Strava activities via the API` for every Strava-sourced
activity, and `/file` likewise. The athlete's own data export is the only surviving copy, so the
source here is a **local directory**. There is no Strava HTTP client in this repository and none is
added; nothing scrapes anything.

## 2. The join is exact, not heuristic

Strava re-encodes each Apple Health workout into FIT and stamps a developer field
**`apple_health_uuid`** into the session message. That value is character-for-character the
`external_id` Intervals.icu holds for the same workout.

Measured over the whole real dataset before a line was written:

| Join | Result |
|---|---|
| 27 Intervals `OAUTH_CLIENT` ↔ archive, by `apple_health_uuid` | **27/27**, 0 contradictions |
| 34 Intervals `STRAVA` stubs ↔ archive, by Strava activity id | **34/34** |
| 10 Intervals `GARMIN_CONNECT` ↔ archive, by Strava activity id | **10/10** |

All 71 Intervals rows land inside the 94 archive files, so the archive is a strict superset of every
other source. The join also settled a question Phase 7E-1 could only flag as "almost certainly":
Strava `19929243277` and Intervals `i180579462` share UUID `C73CBC61-…C5540` — the same workout, a
**Stair-Stepper**, which Intervals holds **twice** (once as a STRAVA stub, once as an OAUTH_CLIENT
upload). Intervals records no `strava_id` for that one, which is exactly why the id-intersection test
in Phase 7E-0 reported no overlap.

Identity therefore uses **identifiers only**: `apple_health_uuid`, then the Strava activity id. A
candidate that matches neither is only then a new activity, and even then a start-time collision
guard (±120 s against any non-Strava activity) refuses to create a row rather than risk a duplicate.

## 3. What the archive actually adds

| Disposition of the 94 | Count |
|---|---|
| Already in RunningAI (25 Intervals + 8 Garmin) → **enriched** | 33 |
| Not modellable by `ActivityType` → recorded and skipped | 46 |
| **New and importable** | **15** (11 indoor ride, 4 treadmill) |

Two facts worth stating plainly, because they shaped every rule below:

* **Every outdoor run in the archive is already stored.** The 15 new activities contain no outdoor
  run and **no heart rate at all**.
* The archive and Intervals are two **independent exports of the same Apple Health workout**, each
  missing what the other has. Over the 25: Intervals carries heart rate on **100 %** of samples while
  the August FITs carry **none**; the FIT carries far more running power (2373 → 6549 points on the
  2026-08-18 long run) and the laps Intervals reports as `icu_lap_count` = 0 — **all 25 have zero
  laps stored**, 5 of them with a genuine repetition structure.

A source-level overwrite in either direction would therefore destroy real data.

## 4. Rules

* **A stored value is never overwritten.** Enrichment fills a sample metric only where the stored
  value is `null`, and stamps `"<metric>_source": "STRAVA_FIT"` into `extra_metrics` so a reader can
  always tell which export produced which number. Laps are written only when the activity has none —
  Garmin's laps, which carry real intensities and step indices, are strictly better and are left
  untouched (`LAPS_PRESENT_NOT_REPLACED`).
* **A FIT record matching no stored sample is dropped, never appended.** Inserting points into a
  stream another source defined would change what the sample index means.
* **The FIT sport decides the type, the Strava label does not.** The export labels sessions "Ride"
  that the file itself records as `CYCLING`/`INDOOR_CYCLING`. An unmapped sport is refused, never
  approximated: strength training, walking and stair climbing have no `ActivityType` and stay out.
  An *outdoor* ride is also refused — only an explicit `INDOOR_CYCLING` sub-sport maps.
* **Cycling never reaches running logic.** `RunningActivityAnalysisService` already restricts cadence
  and running dynamics to `RUNNING_TYPES = {RUN, TREADMILL_RUN}`; an imported `INDOOR_CYCLING`
  activity gets session/zone/threshold measurement only, and its cadence and dynamics stay null.
* **Nothing is estimated.** A channel the file lacks stays `null`, heart rate included
  (`NO_HEART_RATE_IN_SOURCE`).
* **Cadence is not converted.** FIT running cadence is one-leg **rpm**; `activity_sample.cadence` is
  Garmin's **spm** (factor 2). The column stays null, the source value is kept in `extra_metrics`
  under `cadence_rpm`, and `CADENCE_SOURCE_RPM_NOT_NORMALIZED` is recorded — the same decision
  Phase 7E-1 took for Intervals, so the two importers cannot disagree about what the column means.
* **Interval structure is read, never inferred.** A Strava re-encode's laps carry **no `intensity`
  and no `wkt_step_index`** (verified: only `lap_trigger = manual`). The laps are restored exactly,
  so lap metrics become available — but `IntervalStructureExtractor` needs intensity + step index to
  form a group, so **interval groups stay empty for those activities**, and the reason is recorded as
  `LAP_INTENSITY_NOT_RECORDED_BY_SOURCE` / `LAP_WORKOUT_STEP_NOT_RECORDED_BY_SOURCE` rather than
  manufactured from each lap's pace.
* **No Garmin id is ever synthesised.** `external_source = STRAVA`, `external_id` = the Strava
  activity id verbatim. The archive **file** is named after the *upload* id, a different number
  entirely (0 of 94 filenames equal the activity id), and the two are never conflated.

## 5. Sample completeness

Unlike the Intervals stream (Phase 7E-1), a FIT activity file **is** the device's own recording and
states its own record count, so `sample_completeness` is `FULL` here as a fact rather than an
assumption. That is also why Phase 7E-1 had to scope the 6H-6 VERIFY sweep to Garmin-sourced
activities: `UNKNOWN` was the honest answer there and remains so.

## 6. Flow

```
archive dir (outside the repo, read-only)
  activities.csv ──► StravaArchive ──► entries (Activity ID, date, file)
  activities/*.fit.gz ──► FitActivityDecoder (Garmin FIT SDK, decode only) ──► FitActivity
                                   │
          ┌────────────────────────┴─────────────────────────┐
   identity match?                                    no match
   (apple_health_uuid → Strava id)                           │
          │                                          type supported?
   ENRICHED_EXISTING                                  │          │
   ├─ laps, only if none stored                      yes         no
   ├─ sample metrics, only where null                 │          │
   └─ analyseIfNeeded (digest changed)        start collision?  SKIPPED_UNSUPPORTED_TYPE
                                               │        │
                                        SKIPPED_      IMPORTED
                                        AMBIGUOUS     ├─ activity + detail + laps + samples
                                                      └─ analyseIfNeeded
```

Every path writes one committed row to `strava_activity_import`, carrying the file's SHA-256. That
row is the idempotency key, the resume point, the progress log, the quality record and the
provenance all at once.

## 7. Interaction with analysis versioning (Phase 7E-1)

Adding laps or filling samples changes what the engine reads, which changes
`analysis_input_digest` — so `AnalysisReusePolicy` marks the activity stale **on its own** and the
recomputation is the policy's decision, not a forced re-run. **`RUNNING_ANALYSIS_ENGINE_REVISION` is
deliberately NOT bumped by this phase**: no calculator changed, only the data fed to them, and the
digest is the mechanism built for exactly that case.

## 8. Operation

Manual only, off by default, no scheduler, no startup run, no MCP tool:

```
POST /api/v1/strava/archive-import           {"dryRun": true|false}
GET  /api/v1/strava/archive-import           every recorded outcome
```

| Setting | Meaning |
|---|---|
| `RUNNING_AI_STRAVA_ARCHIVE_IMPORT_ENABLED` | master switch, default `false` |
| `RUNNING_AI_STRAVA_ARCHIVE_PATH` | unpacked export directory, **must be outside the repository** |

Start is refused while any of the four publishing switches or either Garmin scheduler is on
(`STRAVA_ARCHIVE_IMPORT_UNSAFE_RUNTIME`). Re-POSTing **is** the resume and the retry: a file whose
SHA-256 already has a stored outcome is skipped, one unreadable file is recorded and the run
continues. There is no automatic retry anywhere.

## 9. Privacy

The export contains the athlete's real GPS traces. It is kept outside the Git repository, never
committed, and never logged. Every test fixture is a **synthetic** FIT file generated in-test by the
SDK's encoder; no byte of the real archive is in this repository.
