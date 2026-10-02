# Phase 6H-1B — Garmin detailed activity live contract verification (result)

**Outcome: `GARMIN_DETAILED_ACTIVITY_CONTRACT_LIVE_VERIFIED`.**

Instruction kept verbatim in
`docs/work-orders/2026-10-02-phase-6h-1b-garmin-detailed-live-contract-instruction.md`.

No real activity id, coordinate, personal metric value, token or credential appears in this document or in any
committed file. Real ids live only in the git-ignored local note `.runtime/live-contract/activity-ids.local.md`.

---

## 1. Repository / HEAD

| | |
|---|---|
| Repository | `C:\running-ai-github` (Main PC), branch `main` |
| Before | `668a097`, working tree clean, 18 commits behind `origin/main` |
| Pull | `git pull --ff-only origin main` → `b38604d` (fast-forward only; no reset, no clean, no rebase) |
| Baseline check | `b38604d` confirmed an ancestor of `origin/main` |
| Legacy repo `C:\running-ai` | untouched |

## 2. Publishing safety (§2)

The Main PC `.env` did **not** match the required state when the phase started:

| Flag | At start | During the phase | Restored at end |
|---|---|---|---|
| `WORKOUT_PUBLISHING_ENABLED` | `true` | `false` | `true` |
| `WORKOUT_PUBLISHING_SCHEDULER_ENABLED` | `false` | `false` | `false` |
| `RUNNING_AI_DRAFT_PUBLISHING_ENABLED` | unset (default `false`) | unset | unset |
| `RUNNINGAI_MCP_ENABLED` | `true` | `false` | `true` |

The owner was asked and chose "off for the duration of the phase, restored afterwards", because leaving both
permanently off would have disabled their ChatGPT → MCP → publish route. `.env` was backed up byte-exactly to
`.runtime/live-contract/env.backup-6h-1b` first and restored from it at the end (CRLF and the UTF-8 BOM preserved;
365 → 363 bytes back to the original). `.env` is git-ignored and was never committed.

**External workout writes: 0.** No Intervals.icu or Garmin write endpoint was called at any point; the only Spring
endpoints used were `POST /api/v1/garmin/sync`, `POST /api/v1/garmin/activities/{id}/details` and `.../reprocess`,
all read-from-Garmin / write-to-own-DB.

## 3. Runtime (§3)

`scripts/windows/start-running-ai.ps1 -Build` then `status-running-ai.ps1`:
Docker **RUNNING**, PostgreSQL **HEALTHY**, Garmin connector **UP** on 127.0.0.1:8765, Spring **UP** on 8080,
`GET /actuator/health` → `{"status":"UP"}`.

> Operational note: the first start failed with exit code 13 ("Java 21 is not available"). This PC has
> `JAVA_HOME` pointing at JDK 1.8 and JDK 17 on `PATH`; JDK 21 is installed at `C:\Users\simjy\.jdks\openjdk-21.0.2`.
> Every build/test/run command in this phase set `JAVA_HOME` to that path for the session only. Nothing was
> changed in the repo or in the machine environment — worth fixing on this PC (user-level `JAVA_HOME`, or
> `.claude/settings.local.json` `env`) so the canonical scripts work without a manual override.

## 4. Flyway (§4)

`flyway_schema_history`: 13 rows, **V1…V13, `success = t` for every one**, no failed migration.
Pre-existing data intact (`activity` 3, `activity_raw` 5 before the phase). All JSON columns are PostgreSQL
`jsonb`: `activity_raw.payload`, `activity_raw_payload.payload`, `activity_lap.extra_metrics`,
`activity_sample.extra_metrics`, `workout_draft.segments`, `workout_draft.warnings`.
No repair, no migration edit, no data deletion.

## 5. Garmin authentication (§5)

`python -m garmin_connector status` → **`tokens: VALID`** (existing Main PC token store). No login attempt, no
password or token value printed, SSL verification untouched.

## 6. Representative activities (§6)

The account holds **7 activities in total** (one page, no pagination beyond it), so selection was exhaustive
rather than sampled. Four were used:

| Role | Garmin `typeKey` | Shape of the data |
|---|---|---|
| `<OUTDOOR_RUN_ID>` = `<LONG_RUN_ID>` | `running` | ~9.9 km / ~46 min, 10 auto-laps, GPS, running power — the account's only outdoor run and also its longest, so one activity serves both required roles |
| `<INTERVAL_RUN_ID>` | `track_running` | ~6.9 km / ~45 min, 24 laps with a real warm-up / work / recovery / cool-down structure |
| `<TREADMILL_RUN_ID>` | `treadmill_running` | ~5.4 km / 30 min, 24 laps, no GPS |
| `<INDOOR_CYCLE_ID>` | `indoor_cycling` | ~13 min, 7 laps, no power meter |

The two remaining activities are `breathwork` (unsupported type — raw only, no `activity` row), which is why
`activity_raw` (7) exceeds `activity` (5).

`POST /api/v1/garmin/sync` was run once to make the interval run available to the detail endpoint
(`fetched 7, created 2, updated 3, skipped 2, failed 0, pagesFetched 1`). That is the normal incremental sync, not
a backfill.

## 7. Detail contract (`/detail`) — `CONFIRMED_LIVE`

`get_activity` returns a wrapper object with `summaryDTO` (51 keys on the outdoor run), `metadataDTO`,
`splitSummaries`, `activityTypeDTO`, `eventTypeDTO`, `timeZoneUnitDTO`. Full key list in
`docs/architecture/garmin-detailed-activity-contract-static.md` §L1.

Key finding: `summaryDTO` uses a **different vocabulary** from the activity-list item (`averagePower` vs
`avgPower`, `normalizedPower` vs `normPower`, `trainingEffect` vs `aerobicTrainingEffect`, `averageRunCadence` vs
`averageRunningCadenceInStepsPerMinute`). It is richer than the list item only *additively* (`minHR`, `minPower`,
`steps`, `recoveryHeartRate`, running dynamics, device + timezone metadata, `splitSummaries`).

**Decision: `ACTIVITY_DETAIL` stays `RAW_STORED`.** Every column `activity_detail` has is already filled correctly
from the list item (verified below), so normalising this payload would add fields, not fix any — that is a
feature decision for a later phase, not a contract correction. No temperature field exists anywhere in it.

## 8. Splits contract (`/splits`) — `CONFIRMED_LIVE`

`{"activityId", "lapDTOs": [...], "eventDTOs": [...]}` — the guessed `lapDTOs` wrapper is correct, and **all 17 lap
keys the mapper reads exist live**.

- **`lapIndex` is 1-based**; `messageIndex` is the 0-based counter. `activity_lap.lap_index` therefore starts at 1.
- `startTimeGMT` = `"2026-09-30T14:50:49.0"` — ISO local date-time with a fractional second and **no zone**, which
  is *not* the activity-list form. `parseGmt` already handled both; the lap start time stored for the outdoor run
  matched the activity's `started_at` exactly.
- Live key count per lap varies by type: 38 (outdoor), 40 (interval), 31 (treadmill), 18 (indoor cycling).
- `eventDTOs` carries only `startTimeGMT` / `startTimeGMTDoubleValue` / `sectionTypeDTO`; not mapped.

### Interval / recovery representation (§8, §30.11)

Garmin states it per lap in **`intensityType`**: live values `WARMUP`, `ACTIVE`, `RECOVERY`, `COOLDOWN`. The track
interval run's 24 laps read `WARMUP x4 → ACTIVE x10 → (RECOVERY, ACTIVE) x4 → COOLDOWN x3`.

**A Garmin lap is not a structured-workout step.** `wktStepIndex` across the same 24 laps ran
`0,0,0,0, 1,1,1,1,1,1,1,1,1, 2, 3,2,3,2,3,2,3, 5,5, —`: one step spans nine laps, two steps alternate as a repeat
block, step 4 never appears, and the last lap has no step index at all.

All three (`intensityType`, `wktIndex`, `wktStepIndex`) are preserved in `activity_lap.extra_metrics`; verified by
reading them back out of the JSONB column for all 24 laps.

## 9. Typed splits / split summaries (§9) — `NOT_RUN`, deliberately not implemented

The static study flagged `get_activity_typed_splits` and `get_activity_split_summaries` as possible gaps. Live
payloads close the gap without a new endpoint: interval/recovery is `intensityType`, lap grouping is
`wktIndex`/`wktStepIndex`, and `splitSummaries` (live `splitType` values `PACE_PRO_SPLIT`, `RWD_RUN`, `RWD_STAND`)
is **already embedded inside the `/detail` payload** that is stored raw. Adding two more Garmin requests per
activity for data already in hand was not justified, so neither endpoint was added.

## 10. HR zones (`/hr-zones`) — `CONFIRMED_LIVE`

Bare array, exactly `[{"zoneNumber", "secsInZone", "zoneLowBoundary"}, …]` — guessed keys correct.
Always **5 zones** (1…5), never partial. A zone with no time is present as `"secsInZone": 0.0`, not omitted.
**No upper-bound field exists**, so `activity_zone.max_value` stays NULL and is never derived from the next zone's
lower boundary — the 6H-1A principle is confirmed, not relaxed. Sum of zone time ≈ activity duration (outdoor run:
2783.4 s of zone time against a 2783 s activity).

## 11. Power zones (`/power-zones`) — `CONFIRMED_LIVE` + `NOT_AVAILABLE` shape confirmed

- **Running power exists** on this device: all three runs (outdoor, track, treadmill) returned five populated power
  zones with watt boundaries. This contradicts the instruction's expectation that a treadmill run would have none.
- **Cycling power does not exist** here (no power meter on the indoor bike).
- The no-power representation is an **empty array `[]`** — not `{}`, not `null`, not 404.

## 12. Samples (`/samples`) — `CONFIRMED_LIVE`

Envelope confirmed: `metricDescriptors[].metricsIndex/key/unit` + `activityDetailMetrics[].metrics`, plus
`measurementCount`, `metricsCount`, **`totalMetricsCount`**, `geoPolylineDTO`, `heartRateDTOs`, `pendingData`,
`detailsAvailable`.

**Every metric key the mapper guessed exists live**: `directTimestamp`, `sumElapsedDuration`, `sumDistance`,
`directSpeed`, `directHeartRate`, `directRunCadence`, `directPower`, `directElevation`, `directLatitude`,
`directLongitude`. `directAirTemperature` is **absent on all four activities**, so `temperature` stays NULL.

Three findings that matter:

1. **`directTimestamp` is a JSON float** (`…0000.0`) holding whole epoch milliseconds, not an integer. It is UTC:
   the first sample's time equalled the activity's stored `started_at` exactly.
2. **`unit.factor` is not a conversion divisor.** `sumDistance` has `factor: 100.0` and carries metres;
   `sumElapsedDuration` has `factor: 1000.0` and carries seconds. The outdoor run's last sample reported
   `sumDistance` identical to the activity's total distance. Storing values unscaled is correct.
3. Descriptor sets differ by activity type: 23 metrics (outdoor), 22 (track), 17 (treadmill), 7 (indoor cycling).

## 13. Descriptor mapping / outdoor vs treadmill (§13) — `CONFIRMED_LIVE`

| | Outdoor run | Treadmill run |
|---|---|---|
| GPS (`directLatitude`/`directLongitude`) | present | **absent** |
| `directElevation` | present | **absent** |
| `directVerticalSpeed`, `directGradeAdjustedSpeed`, `directPerformanceCondition` | present | **absent** |
| Speed, HR, cadence | present | present |
| Running dynamics (GCT, vertical oscillation, vertical ratio, stride length) | present | present |
| Running power | present | present |
| Descriptor count | 23 | 17 |

**Descriptor order is different in all four activities.** `directTimestamp` sat at index **7** (outdoor), **5**
(track), **9** (treadmill), **2** (indoor cycling) — and the two *outdoor* runs, same device, same account, still
disagreed. This is direct, reproduced evidence that the 6H-1A descriptor-based design is required, not defensive
over-engineering: a hard-coded index would mis-assign every metric on three of four activities.

Verified in the database after ingestion: treadmill samples have `latitude`, `longitude` and `elevation` NULL,
while outdoor samples have all three populated.

## 14. maxChart comparison (§14) — **`DOWNSAMPLED` by default, `FULL_SAMPLE` at 20000**

Long outdoor run, 2783 s recorded:

| Request | samples | `metricsCount` | `totalMetricsCount` | response size | latency |
|---|---|---|---|---|---|
| default (library `maxchart=2000`) | 1399 | 1399 | 2784 | 1.15 MB | 1025 ms |
| `maxChart=20000` | **2784** | 2784 | 2784 | 1.51 MB | 1444 ms |

- First and last timestamps identical in both.
- Default spacing is **irregular** (1,1,1,1,2,2 s); the full stream is a uniform 1 s.
- HR min/max identical (108/192); mean differs slightly (185.01 vs 185.23).
- Native sampling is **1 Hz** here — 2784 samples over 2783 s, measured on this activity, not extrapolated to other
  devices or activity types.
- `totalMetricsCount` always reports the native count, so a stored stream can be recognised as down-sampled
  afterwards without another Garmin call.
- Shorter activities are unaffected: treadmill 1801/1801, indoor cycling 788/788 (both under the 2000 cap).

> **Not changed in this phase:** `running-ai.garmin.detail.samples-max-chart-size` is still unset, so detail
> collection currently stores the down-sampled stream for activities longer than ~33 min at 1 Hz. Raising it is an
> ingestion-policy decision, outside contract verification. **Recommended as the first item of the next phase.**

## 15. FIT probe (§15) — `CONFIRMED_LIVE`, read-only, one activity

`download_activity(..., ORIGINAL)` returned `bytes`, **101,575 bytes**, magic `504b0304` → a **ZIP** containing
exactly one `.fit` entry, **237,402 bytes** uncompressed. Written to a local temp directory outside the repository
and deleted afterwards. No FIT content was parsed (out of scope).

## 16. Raw response storage (§16)

Live payloads were kept under `.runtime/live-contract/`. `git check-ignore -v` confirms `.gitignore:69 .runtime/`
covers it. Nothing from that directory is committed; this document records shapes only.

## 17. Spring detailed ingestion (§17) — live success, second run idempotent

First collection of each activity: **all four `COMPLETE`, zero mapping failures.**

| Activity | ACTIVITY_LIST | ACTIVITY_DETAIL | SPLITS | HR_ZONES | POWER_ZONES | SAMPLES |
|---|---|---|---|---|---|---|
| outdoor / long run | NORMALIZED 1 | RAW_STORED | NORMALIZED 10 | NORMALIZED 5 | NORMALIZED 5 | NORMALIZED 1399 |
| interval run | NORMALIZED 1 | RAW_STORED | NORMALIZED 24 | NORMALIZED 5 | NORMALIZED 5 | NORMALIZED 1366 |
| treadmill run | NORMALIZED 1 | RAW_STORED | NORMALIZED 24 | NORMALIZED 5 | NORMALIZED 5 | NORMALIZED 1801 |
| indoor cycling | NORMALIZED 1 | RAW_STORED | NORMALIZED 7 | NORMALIZED 5 | **EMPTY 0** | NORMALIZED 788 |

Row counts per activity: `activity_raw_payload` 5, `activity_detail` 1, `activity_detail_collection` 6 for each;
laps 10 / 24 / 24 / 7; zones 10 / 10 / 10 / 5; samples 1399 / 1366 / 1801 / 788.
Totals: raw_payload 20, detail 4, laps 65, zones 35, samples 5354, collection 24.

**Second collection of all four** (re-fetched from Garmin): every count identical, and content checksums identical
(`md5` over all laps and over all samples unchanged). Duplicate checks on every uniqueness key
(`external_source+external_activity_id+payload_type`, `activity_id+lap_index`,
`activity_id+zone_type+zone_number`, `activity_id+sample_index`, `activity_id`, `activity_id+payload_type`)
returned **0 duplicates**. Sample surrogate ids moved from 1…5354 to 5355…10708, confirming replace-per-part
semantics rather than accumulation.

## 18. Spot-check normalisation (§18) — exact matches, no conversion

Raw payload vs normalised DB for the outdoor run:

| Value | Raw | Stored |
|---|---|---|
| duration | `2783.48388671875` | `2783.48388671875` |
| distance | `9917.2099609375` | `9917.2099609375` |
| averageHR / maxHR | `185.0` / `192.0` | `185` / `192` |
| averageSpeed | `3.562999963760376` | identical |
| averageRunningCadence | `178.40625` | identical |
| calories / avgPower / maxPower / normPower | `728` / `358` / `461` / `360` | identical |
| aerobic TE / anaerobic TE / training load / label | `5.0` / `3.700000047683716` / `638.6153564453125` / `VO2MAX` | identical |
| lap 1 start (`startTimeGMT` `…T23:00:10.0`) | — | `2026-09-18 23:00:10+00` (= activity `started_at`) |
| lap 1 duration / distance / avg HR / max HR | `285.223` / `1000` / `168` / `185` | identical |
| HR zone 1…5 lower bounds / seconds | `92/110/128/146/165`, `12.001 … 2724.616` | identical, `max_value` NULL |
| sample 0 time (`directTimestamp` float) | `…0000.0` ms | `2026-09-18 23:00:10+00` |
| last sample elapsed / distance | `2783` / `9917.2099609375` | identical (= activity total distance) |

All 20 activity-list keys the detail mapper reads were present live with sensible values. No rounding or unit
conversion anywhere; no field mapping was found to be wrong.

## 19. Mapper corrections (§30.14) — **none needed**

No mapper key, shape assumption or parse rule required correcting. Production code changes in this phase are
**documentation only** (`GarminActivityDetailMappers.kt`, `GarminActivityDetailSource.kt` KDoc/comments): the
PROVISIONAL provenance notes were replaced with the live-verified facts and the `samples-max-chart-size` KDoc now
carries the measured down-sampling numbers. **No behaviour changed**, which is also why `/reprocess` was expected
to be — and was — a no-op in content.

## 20. Fixture corrections (§19, §30.15)

Fixtures were synthetic *guesses* before; they now reproduce **live shapes with synthetic values**, and are renamed
so the filename stops asserting the wrong thing:

| Before | After |
|---|---|
| `splits.SYNTHETIC_NOT_LIVE_GARMIN.json` (2 laps, guessed keys) | `splits.LIVE_SHAPE.ANONYMISED.json` — 4 laps modelled on the live interval run: 1-based `lapIndex` + 0-based `messageIndex`, `intensityType` WARMUP/ACTIVE/RECOVERY/COOLDOWN, `wktIndex`/`wktStepIndex` (last lap without one), `eventDTOs`, the full live lap key set |
| `hr-zones.SYNTHETIC_NOT_LIVE_GARMIN.json` | `hr-zones.LIVE_SHAPE.ANONYMISED.json` — content already matched the live shape exactly; renamed only |
| `samples.LIBRARY_SHAPE.SYNTHETIC_NOT_LIVE_GARMIN.json` (7 metrics, **integer** timestamps) | `samples-outdoor.LIVE_SHAPE.ANONYMISED.json` — the live 23-metric outdoor layout, `unit {key, factor}`, **float** `directTimestamp`, GPS and running dynamics, nulls in the early samples |
| — | `samples-treadmill.LIVE_SHAPE.ANONYMISED.json` (new) — the live 17-metric treadmill layout: no GPS/elevation, and **every shared key at a different index** than the outdoor fixture |

Anonymisation: activity ids `999000001` / `999000002`, the obviously synthetic point lat `1.5` / lon `2.5`, shifted
dates, invented metric values. No real id, coordinate, date or measurement was committed. The fixture README now
records the live basis and the anonymisation rule.

The most important gap these close: the old fixture used an **integer** `directTimestamp`, so no test ever
exercised the float path that live Garmin actually sends; and no fixture varied the descriptor layout, so
positional parsing would not have been caught.

Three tests were added (918 → 921):

- `an epoch-millisecond timestamp reported as a JSON float is still read exactly`
- `the same metric keys at a completely different index layout still resolve` (outdoor vs treadmill)
- `the live interval structure survives in extraMetrics, lap by lap` (`intensityType` sequence, `wktStepIndex`
  including the stepless last lap)

The ingestion test's power-zone script was changed from `{}` to the live `[]`.

## 21. Reprocess (§21) — success, zero Garmin calls

`POST .../details/reprocess` on all four activities → all **`COMPLETE`**, identical part statuses and item counts.
Row counts unchanged (raw_payload 20, detail 4, laps 65, zones 35, samples 5354, collection 24); lap and sample
content checksums unchanged; no duplicates.

Raw payloads provably untouched: `activity_raw_payload.updated_at` stayed at the collection timestamps
(11:23:13–14) while the reprocess wrote collection records at 11:23:57.

## 22. Partial collection behaviour (§22) — correct

Indoor cycling's power zones (`[]`) were recorded as **`EMPTY`, `item_count = 0`**, and the run still reported
`COMPLETE`. A normal absence of data is not treated as a failure. `ACTIVITY_DETAIL` is `RAW_STORED` by design.
Fetch/mapping failure paths were not triggered live (no error occurred); they remain covered by the automated
tests.

## 23. Errors / rate limiting (§23)

**No 401, 403 or 429 occurred at any point.** All 46 Garmin-facing calls returned 200/success.
`Garmin(retry_attempts=0)` was left in place; no retry loop was written.

**Garmin live API call count: 46**

| Purpose | Calls |
|---|---|
| connector `status` (token verification profile request) | 1 |
| activity list for selection | 1 |
| activity list for `POST /api/v1/garmin/sync` | 1 |
| contract probe, 4 activities x 5 parts | 20 |
| `maxChart=20000` comparison | 1 |
| FIT probe (session login + download) | 2 |
| Spring detail ingestion, 4 activities x 5 parts | 20 |
| Spring reprocess (by design) | 0 |

## 24. FIT decision (§24) — **A: API detail is sufficient; FIT stays archival/optional**

The three reasons that would have forced FIT are all absent:

- **resolution** — `maxChartSize` already returns the full native 1 Hz stream;
- **running dynamics** — GCT, vertical oscillation, vertical ratio, stride length and running power all arrive as
  ordinary sample metrics;
- **interval structure** — `intensityType` / `wktStepIndex` carry it on each lap.

What FIT would still add is device-native per-record precision, lap/session message fields Garmin does not expose
over the API, and an archival original that survives a Garmin-side edit. That is a preservation argument, not a
capability gap, so FIT ingestion is recorded as a **next-phase candidate** and nothing was implemented.

## 25. Scope (§25)

Historical backfill **NOT_RUN** · bulk pagination **none** (one list page, 7 items total in the account) ·
recovery backfill **NOT_RUN** · Intervals enrichment **NOT_RUN** · TrainingContext V2 **not implemented** ·
Running Analysis Engine **not implemented** · AI coach draft generation **not run** · workout publish **not run**.
Exactly four Garmin activities were touched.

## 26. Tests (§26)

| Suite | Baseline | Result |
|---|---|---|
| Spring `gradlew clean test` (JDK 21, H2) | 918 passed | **921 passed, 0 failed, 0 skipped** (93 suites; +3 new tests) |
| Python connector `pytest` | 134 passed | **134 passed** (connector unchanged) |
| PostgreSQL 17 subset (`SchemaMigrationTest`, `integration.garmin.*`, `draftpublish.*`, `coach.WorkoutDraft*`) | 394 passed | **397 passed, 0 failed** (+3 new tests) |

The PostgreSQL subset was run against a throwaway database `running_ai_6h1b` in the same container (created,
migrated V1–V13 with `json_type=JSONB`, verified, dropped) so the live data was never touched — confirmed intact
afterwards.

> First attempt at the PostgreSQL subset failed with 42 context-load errors, all
> `FATAL: sorry, too many clients already` — many Spring test contexts each opening a 10-connection Hikari pool
> against one PostgreSQL. Re-run with `maximum-pool-size: 2` for the test JVM: green. This is a test-harness
> limitation on real PostgreSQL, unrelated to the phase's changes, and worth pinning down if the subset becomes a
> routine step.

## 27. Contract status (§20)

| Capability | Status |
|---|---|
| ACTIVITY_LIST → `activity_detail` | `CONFIRMED_LIVE` |
| ACTIVITY_DETAIL (`get_activity`) | `CONFIRMED_LIVE` (shape); stored raw by decision |
| SPLITS (`lapDTOs`) | `CONFIRMED_LIVE` |
| HR zones | `CONFIRMED_LIVE` |
| Power zones | `CONFIRMED_LIVE`; `NOT_AVAILABLE` = `[]` |
| Samples / descriptor mapping | `CONFIRMED_LIVE` |
| `maxChartSize` down-sampling | `CONFIRMED_LIVE` (default `DOWNSAMPLED`, 20000 `FULL_SAMPLE`) |
| FIT / ORIGINAL download | `CONFIRMED_LIVE` (probe only) |
| Typed splits / split summaries | `NOT_RUN` — evaluated, not needed |
| Fixtures | `CORRECTED` (shape), anonymised |
| Mappers | no correction required |

All five `LIVE_VERIFIED` conditions from §20 are met: real responses inspected, fixtures corrected, Spring
normalisation succeeded, reprocess succeeded, automated tests pass.

**→ `GARMIN_DETAILED_ACTIVITY_CONTRACT_LIVE_VERIFIED`**

## 28. Files changed

Production (documentation/comments only, no behaviour change):
`server/src/main/kotlin/com/runningai/integration/garmin/GarminActivityDetailMappers.kt`,
`GarminActivityDetailSource.kt`.

Tests: `GarminActivityDetailMappersTest.kt`, `GarminActivityDetailIngestionTest.kt`.

Fixtures: `splits.LIVE_SHAPE.ANONYMISED.json` (new), `samples-outdoor.LIVE_SHAPE.ANONYMISED.json` (new),
`samples-treadmill.LIVE_SHAPE.ANONYMISED.json` (new), `hr-zones.LIVE_SHAPE.ANONYMISED.json` (renamed),
`README.md`; old `*.SYNTHETIC_NOT_LIVE_GARMIN.json` / `*.LIBRARY_SHAPE.*` removed.

Docs: `docs/architecture/garmin-detailed-activity-contract-static.md` (live section §L1–§L7 appended, static and
live facts separated), `docs/architecture/detailed-activity-v2.md` (live findings, lap semantics, FIT decision,
status), `CLAUDE.md`, `.claude/skills/running-ai-integration/SKILL.md`, this result document and the instruction.

No migration was added. No schema change.

## 29. Limitations

- Everything is verified against **one athlete, one device (Garmin 265-class), 4 activities, 7 in the account**.
  Multisport, open-water swim, strength (`exerciseSets`), trail and cycling-with-power payloads are unverified.
- `directAirTemperature` was never observed, so the temperature column is live-unexercised.
- Fetch-failure and mapping-failure paths were not triggered against live Garmin (nothing failed); only the
  automated tests cover them.
- `activity_detail` still comes from the list item; `summaryDTO`'s extra fields are stored raw but unused.
- Detail collection still stores **down-sampled** sample streams for activities over ~33 min.
- `lengthDTOs` (pool-swim lengths) was always empty here; its shape is unverified.

## 30. Suggested next steps (not started)

1. Decide `running-ai.garmin.detail.samples-max-chart-size` (e.g. 20000) so stored streams are full-resolution,
   and reprocess/refetch the affected activities.
2. Promote `intensityType` / `wktStepIndex` from `extra_metrics` to first-class lap columns if the analysis engine
   needs to query them.
3. Decide whether `summaryDTO` extras (`minHR`, `steps`, `recoveryHeartRate`, running dynamics) justify normalising
   the `get_activity` payload.
4. Historical backfill policy (still `NOT_RUN`), then the Running Analysis Engine / TrainingContext V2.
5. FIT archival storage, if preservation (not capability) is wanted.
6. Fix `JAVA_HOME` on the Main PC so `start-running-ai.ps1` finds JDK 21 without a manual override.
