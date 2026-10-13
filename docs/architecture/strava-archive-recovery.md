# Strava / Apple Health archive recovery — preparation (Phase 7E-1 STEP 5)

**Status: design and evidence only. No importer is implemented, and none will be designed in detail until
a real archive has been inspected.** This document records what is already known for certain, so that when
the archive arrives the only open question is its file layout.

## 1. What is missing, exactly

| Group | Count | Range | Reachable today |
|---|---:|---|---|
| Garmin-sourced | 10 | 2026-09-18 … 10-11 | yes — already stored |
| Intervals-only (`OAUTH_CLIENT`) | 27 | 2026-08-02 … 09-13 | yes — Phase 7E-1 importer |
| **Strava-only (`STRAVA`)** | **34** | **2026-07-02 … 08-26** | **no** |
| Anything before 2026-07-02 | 0 | — | does not exist in any source |

The 34 sessions, by athlete-local date:

```
2026-07-02 ×2, 07-04, 07-05, 07-06 ×3, 07-08, 07-09 ×3, 07-10 ×3, 07-12 ×3,
2026-07-15, 07-17 ×3, 07-26, 07-28 ×2, 07-30 ×2, 07-31,
2026-08-06, 08-10, 08-11, 08-19, 08-25, 08-26 ×2
```

## 2. Why Intervals.icu cannot supply them — measured, not assumed

Probed read-only on 2026-10-13 against the real account:

```
GET /api/v1/activity/19929242715           200  {"id","icu_athlete_id","start_date_local","source",
                                                 "_note":"STRAVA activities are not available via the API"}
GET /api/v1/activity/19929242715/streams   422  {"error":"Cannot read Strava activities via the API"}
GET /api/v1/activity/19929242715/file      422  {"error":"Activity has no original file to download"}
```

The stub carries **no type, no duration, no distance, no heart rate** — only an id, the athlete, a local
start time and the note. There is nothing to map, which is why the importer records them as
`SKIPPED_NO_SOURCE_DATA` rather than as failures: the data is not missing from RunningAI, it is absent at
the source.

Intervals' own fitness model agrees that these sessions never reached it — `ctl > 0` only from 2026-08-02,
so the July training carries no load anywhere in the system today.

**Only the athlete's own Strava account archive can recover them.** No scraping, no unofficial endpoint and
no policy workaround will be used; the archive is requested by the account owner from Strava and arrives as
a file.

## 3. The identifier that makes matching easy

For a `STRAVA`-sourced activity, Intervals uses **the Strava activity id itself as its activity id**: all
34 ids are bare numerics (`19929242715`, `19929243277`, …), unlike the `i…`-prefixed ids of every other
source. A Strava export's `activities.csv` has an `Activity ID` column carrying the same number.

So the primary join is an **exact id match**, not a heuristic:

```
strava archive activities.csv "Activity ID"  ==  intervals_activity_import.intervals_activity_id
                                              (rows with outcome SKIPPED_NO_SOURCE_DATA)
```

Those 34 rows are already stored by the Phase 7E-1 importer, each with its `start_date_local`, so the
archive can be reconciled against a known target set instead of being imported blind. Anything in the
archive that is *not* in that set is a session Intervals never saw at all, and is a separate decision.

The composite fallback (type + start time + duration + distance within the 6H-5 tolerances) stays available
for an archive row whose id does not match, and the existing rule holds unchanged: **two candidates are
never merged automatically.**

## 4. Overlap between the 34 and the 27 — measured

* The 34 Strava ids and the `strava_id` values of the 27 Intervals-only activities (26 have one) and of the
  10 Garmin activities **do not intersect at all**. They are different Strava uploads, not the same records.
* They do overlap in time: 7 of the 34 fall in August, inside the Intervals-only range. Three calendar days
  carry both kinds.
* **Exactly one pair is an almost certain duplicate session**: Strava `19929243277` and Intervals
  `i180579462` both start at `2026-08-06T18:21:47` local — the same second. `i180579462` is the Apple Watch
  (`Watch7,9`) `Workout`, 57 536 s elapsed, and carries **no `strava_id`**, so the same physical session was
  uploaded to Strava independently and the two systems never learned of each other.
  * That activity is currently `SKIPPED_UNSUPPORTED_TYPE` in RunningAI (`Workout` maps to no
    `ActivityType`), so **no RunningAI row exists for it**. If the archive import ever creates one, the
    duplicate search will find nothing to match against. This pair must be handled explicitly rather than
    left to the matcher.
* The other two same-day pairs are 3 h 39 m and 10 h 38 m apart — different sessions.

## 5. File-format support today

**None.** `server/` contains no FIT, TCX or GPX parser and no dependency that could become one; the only
matches in the repository are unrelated strings inside `tools/mcp-worker/node_modules`.

This is consistent with the Phase 6H-1B decision (**A — the API detail suffices; FIT is archival-only, not
implemented**). A Strava archive would be the first thing to change that, and the format question is open:

| Format | Typically in a Strava bulk export | What it carries |
|---|---|---|
| `.fit.gz` | activities uploaded from a device | full per-second streams, including running dynamics |
| `.gpx.gz` | some app-recorded activities | time + position + elevation; heart rate only as an extension, cadence often absent |
| `.tcx.gz` | some imports | time, distance, HR, cadence; no running dynamics |
| `activities.csv` | always | one row per activity: id, date, type, elapsed/moving time, distance, elevation, average/max HR, the relative path of its file |

Which of these the archive actually contains is **not predictable from here**, and deciding a parser before
seeing one would be guessing at an external contract. The sequence is fixed instead:

1. the archive arrives;
2. `activities.csv` is read first and reconciled against the 34 known ids (§3) — this alone already yields
   type, duration, distance and average/max HR for every session, with no parser at all;
3. **one** sample file of each extension present is inspected to establish the real contract;
4. only then is the importer's scope fixed, and only for formats actually present.

Step 2 is worth stating separately: a CSV-only import would already restore 34 activity rows with enough
to compute training load and the daily pattern, even if no stream is ever parsed.

## 6. Apple Health `export.xml` — feasibility

The Apple Watch is already a confirmed recording device on this account (`Watch7,9`, 8 of the 27
Intervals-only activities), so an Apple Health export plausibly contains the July sessions too.

Known characteristics, to be confirmed against a real export before anything is built:

* `export.xml` is a single large XML document (hundreds of MB is normal) and must be streamed, never
  loaded whole;
* workouts appear as `<Workout>` elements with a type, start/end, duration, distance and energy; per-second
  series live in separate `<Record>` elements, keyed by type and time range, not nested inside the workout;
* heart rate is usually present at a few-seconds cadence rather than a true 1 Hz;
* running dynamics (ground contact time, vertical oscillation, stride length) are **not** generally present;
* there is no Strava or Intervals id anywhere in it, so matching would have to be composite
  (start time + duration + distance) — exactly the case where the existing rule says an ambiguous match is
  never merged automatically.

Assessment: **viable as a last-resort source, clearly inferior to a Strava archive, and worth implementing
only for sessions the Strava archive does not cover.** It is not a parallel track.

## 7. Source fidelity, ranked

Against what RunningAI can actually use (per-second streams, laps, dynamics):

| Source | Streams | Laps | Running dynamics | HR | Verdict |
|---|---|---|---|---|---|
| Garmin detail API (today) | 1 Hz, fidelity provable (`FULL`) | yes, with workout step structure | yes | yes | reference quality |
| Intervals `/streams` (Phase 7E-1) | 1 Hz, fidelity `UNKNOWN` (no source count to check) | **none** (`icu_lap_count` = 0) | in `extra_metrics`, unconverted, unanalysed | yes | good for session metrics and decoupling; no interval structure, ever |
| Strava archive `.fit` | expected 1 Hz | expected | possibly | yes | best available for the 34 |
| Strava archive `.tcx` | per-second | basic | no | yes | adequate |
| Strava archive `.gpx` | per-second | no | no | extension only | weak |
| `activities.csv` alone | none | none | no | averages only | enough for an activity row and training load |
| Apple Health `export.xml` | few-second | no | no | yes | last resort |

A note that applies to the 27 already imported: Intervals **does** serve the original uploaded file
(`GET /api/v1/activity/{id}/file` returned 402 KB, `…/fit-file` 260 KB for a sample activity), so a higher
fidelity path exists for them if one is ever needed. It is deliberately not used — the 6H-1B decision stands,
and the streams endpoint already gives 1 Hz data without introducing a FIT parser.

## 8. Explicitly out of scope

* Unofficial Strava endpoints, scraping, or anything that works around a service's terms. The archive is
  requested by the account owner through Strava's own export.
* Guessing the archive's file layout before a real one has been inspected.
* Inventing any value — a session the archive describes only in CSV gets an activity row and nothing more;
  no stream is synthesised from averages.
