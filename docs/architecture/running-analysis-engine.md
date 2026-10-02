# Running Analysis Engine (Phase 6H-4)

```text
activity · activity_detail · activity_lap · activity_zone · activity_sample
athlete_intensity_profile (LTHR) · activity_detail_collection (sample fidelity)
        │   stored rows only - no Garmin call, no Intervals call, nothing published
        ▼
RunningActivityAnalysisService
        ├── SessionMetricsCalculator       halves, decoupling, cadence
        ├── ZoneExposureCalculator         time in heart-rate zone
        ├── ThresholdExposureCalculator    time at/above a share of LTHR
        ├── LapMetricsCalculator           laps as recorded
        ├── IntervalStructureExtractor     blocks and repetition groups
        └── IntervalMetricsCalculator      repeatability, recovery HR change
        ▼
ActivityAnalysisStore → activity_analysis (V16)
                        activity_analysis_interval_group
                        activity_analysis_interval
        ▼
[later] TrainingContext V2 → Claude AI Coach
```

## The one rule

**The engine measures. It does not judge.**

Allowed: `hr_change_percent = +2.84`, `speed_change_percent = -3.00`, `speed_cv_percent = 9.81`,
`last_vs_first_speed_change_percent = -2.20`, `recovery_hr_drop_bpm = 9.4`.

Not allowed, anywhere in `com.runningai.analysis` or in the schema: a rating, a score, a threshold, a
good/bad label, a readiness or risk number, "the session failed", "endurance is poor". Interpretation
belongs to the AI coach, and keeping it out of here is what lets the coach be handed evidence rather
than a verdict it would have to take on trust.

`analysis_status` is about the **data**, not the session: `COMPLETE` (every applicable metric computed),
`PARTIAL` (something applicable could not be computed), `INSUFFICIENT_DATA` (nothing to compute from).

## Missing data

A metric the stored data cannot support is `null` — never zero, never estimated, never interpolated.
No power → null. No cadence → null. No LTHR on file → null. No interval structure → no groups. A normal
absence is not a failure, and nothing is substituted to make a row look complete.

## Session metrics

| Column | Meaning |
|---|---|
| `valid_sample_count` | samples reporting a usable heart rate **or** a usable speed |
| `analysis_duration_seconds` | last timeline point minus first |
| `analysis_distance_meters` | last reported cumulative distance minus first |

### Valid-sample policy

- heart rate: present and `> 0` (a zero means "not measured", not a stopped heart)
- speed: present and `>= 0` (a zero is kept — standing still is part of the session)
- cadence: present and `>= 0`

There is **no** moving-time filter and no speed threshold. Inventing one would quietly change every
average, and nothing in the stored data says where a pause began.

### Halves

Split at the midpoint of **elapsed time**, never at the midpoint of the sample array. Garmin's stream is
not uniformly spaced — a live default-resolution run had 1 s and 2 s gaps inside one activity — so an
index split would weight the halves differently. `elapsedSeconds` is preferred; sample timestamps are the
fallback; if neither gives a non-decreasing base that actually advances, **the half metrics stay null**.
There is deliberately no index-half fallback.

| Metric | Formula | Unit |
|---|---|---|
| `hr_change_bpm` | `secondHalfAvgHr - firstHalfAvgHr` | bpm |
| `hr_change_percent` | `(secondHalfAvgHr - firstHalfAvgHr) / firstHalfAvgHr * 100` | % |
| `speed_change_percent` | `(secondHalfAvgSpeed - firstHalfAvgSpeed) / firstHalfAvgSpeed * 100` | % |
| `cadence_change_spm` | `secondHalfAvgCadence - firstHalfAvgCadence` | spm |
| `cadence_change_percent` | `(second - first) / first * 100` | % |

A percentage is null whenever its baseline is null or zero. Speed is the stored metric, not pace; pace is
a presentation concern and is converted later, never persisted here.

### `speed_hr_decoupling_percent` — a RunningAI-derived descriptive metric

**This is not Garmin's metric and not Intervals.icu's.** It is not "aerobic decoupling" as any other tool
defines it, and it must not be presented as one.

```text
EF1 = firstHalfAverageSpeed  / firstHalfAverageHR
EF2 = secondHalfAverageSpeed / secondHalfAverageHR

speed_hr_decoupling_percent = (EF1 - EF2) / EF1 * 100
```

Positive means the second half bought less speed per heartbeat than the first. Both half-ratios are also
stored (`first_half_speed_hr_ratio`, `second_half_speed_hr_ratio`), so a reader can see where the number
came from. **There is no threshold**: no value of this metric is good or bad here.

Null whenever either half lacks a heart rate or a speed, or when EF1 is zero.

### Applicability

Cadence metrics are computed for `RUN` and `TREADMILL_RUN` only. A bike's cadence is a different
measurement that happens to share a name, so a ride reports no "cadence change" rather than a misleading
one. Everything else — halves, zones, threshold, laps, intervals — is activity-type neutral and simply
reports what the stored data supports (a ride with no speed sensor gets null speed metrics, not zeros).

## Heart-rate zone exposure

Straight from `activity_zone`, which is straight from the source.

```text
hr_zoneN_seconds  = the source's reported seconds in zone N
hr_zone_total_seconds = sum of the reported zone seconds
hr_zoneN_percent  = hr_zoneN_seconds / hr_zone_total_seconds * 100
```

The denominator is the **zone total, not the activity duration**. Live Garmin zone time and activity
duration are close but not identical, and only the former is something the source vouches for; using the
activity duration would silently invent a "no zone" remainder. A zone the source reports as `0.0` is kept
at zero; a zone it does not report at all is absent, never filled in.

## LTHR-relative exposure

```text
lthr90_seconds  = seconds at heart rate >= 0.90 * LTHR
lthr95_seconds  = seconds at heart rate >= 0.95 * LTHR
lthr100_seconds = seconds at heart rate >= LTHR
```

Time is **integrated over the real gaps between samples**, never counted as one second per sample: a
down-sampled stream has uneven gaps, so counting samples would understate a long session by roughly its
down-sampling factor. Each sample is credited with the gap to the next one, so **the final sample
contributes nothing** — there is no evidence about how long the last reading held, and adding a trailing
second would be a guess.

Without an LTHR in `athlete_intensity_profile`, all three are null. Nothing is substituted for it.

## Lap metrics

`lap_count`, `lap_speed_mean`, `lap_speed_stddev`, `lap_speed_cv_percent`, `lap_hr_mean`,
`lap_hr_progression` (last reporting lap minus first, in lap-index order), `lap_cadence_mean`.

These cover **every** lap, warm-up and cool-down included, so they are *not* a measure of interval
consistency: a session opening with a slow warm-up lap shows a wide spread here while its repetitions are
perfectly even. Interval repeatability is computed separately, over work repetitions only.

## Interval structure

Phase 6H-1B established live that **a Garmin lap is not a workout step**: one step spanned nine laps, two
steps alternated as a repeat block, one step index never appeared, and the final lap carried no step at
all. The structure is therefore read from what the device recorded, never inferred.

Inputs are the first-class lap columns promoted in V15: `intensity_type`, `workout_index`,
`workout_step_index`. `intensity_type` is a free string — live Garmin says `WARMUP` / `ACTIVE` /
`RECOVERY` / `COOLDOWN`, and an unseen value must widen the data rather than break ingestion.

**Block** — a run of consecutive laps sharing the same `intensity_type` + `workout_step_index`. The same
step index reappearing later is a *separate* block, because that recurrence is exactly how a repeat is
expressed. A block's duration and distance are the sums of its laps; its speed, heart rate, cadence and
power are **weighted by lap duration**, so a 20 s lap does not count as much as a 100 s one.

**Repetition group** — identified only when all of these hold:

1. the blocks are `ACTIVE`,
2. they share a `workout_step_index`,
3. that step occurs **at least twice**,
4. at least one `RECOVERY` block sits **between** two of those occurrences.

Anything short of that is "no interval structure identified". Back-to-back work blocks with no recovery
between them are not repetitions. **Intervals are never inferred from a speed or heart-rate pattern** — a
fartlek-looking fast/slow run with no recorded structure yields no groups, which is an absence of
structure, not a failure.

## Interval repeatability

Per group, over its **work repetitions only** (warm-up, recovery and cool-down are never mixed in):

| Metric | Formula |
|---|---|
| `work_rep_count` | number of work occurrences |
| `mean_speed` | mean of the repetition average speeds |
| `speed_stddev` | **population** standard deviation (divisor `n`) |
| `speed_cv_percent` | `speed_stddev / mean_speed * 100` |
| `first_rep_speed`, `last_rep_speed` | first and last repetition |
| `last_vs_first_speed_change_percent` | `(last - first) / first * 100` |
| `first_rep_hr`, `last_rep_hr` | first and last repetition |
| `hr_progression_bpm` | `last_rep_hr - first_rep_hr` |

**Population, not sample.** The repetitions present are the whole group, not a sample drawn from a larger
one, so the `n-1` correction would be the wrong estimator — and over two repetitions it would inflate the
spread by about 41%.

Each repetition is also stored individually in `activity_analysis_interval` (duration, distance, average
speed, average and max heart rate, average cadence, average power, and the lap range it covers).

## RunningAI interval recovery HR change

**This is not Garmin's Recovery HR metric** and must not be labelled as one.

Across the `RECOVERY` block that follows a group's **last** work repetition:

```text
recovery_start_hr         mean heart rate over the first 10 s of the block
recovery_end_hr           mean heart rate over the last 10 s of the block
recovery_hr_drop_bpm      recovery_start_hr - recovery_end_hr   (a fall is positive)
recovery_duration_seconds the block duration the laps state
```

The 10 s window is fixed in code (`IntervalMetricsCalculator.RECOVERY_WINDOW_SECONDS`): a single sample
can swing several beats and would make the number look more precise than it is. When no sample lands in a
window, the first/last usable reading inside the block is used instead of inventing an average. A block
with no usable heart rate, or one that cannot be placed on the sample timeline, reports only the duration.

## Sample fidelity dependency

`input_sample_completeness` carries `activity_detail_collection.sample_completeness` (V14) into the
analysis, so a reader can tell a full-resolution result from a down-sampled one.

| Completeness | Effect |
|---|---|
| `FULL` | normal computation |
| `DOWNSAMPLED` | computed and marked; averages over uneven gaps are coarser, and threshold exposure is only as fine as the gaps |
| `UNKNOWN` | computed and marked; the fidelity question stays open |
| `null` | the stream predates fidelity recording; nothing is assumed about it |

Lap and zone metrics are independent of sample fidelity — they come from the source's own aggregates.

Nothing is refused because of fidelity: a down-sampled stream still produces evidence, it is just labelled
as what it is.

## Versioning and persistence

`analysis_version` is stored on every row; the first is `RUNNING_ANALYSIS_V1`. A re-analysis **replaces**
the derived rows — the analysis row is updated in place and the interval group/repetition rows are deleted
and rewritten, so a session that loses its structure does not keep the old one.

The analysis never writes to `activity`, `activity_detail`, `activity_lap`, `activity_zone`,
`activity_sample` or any raw payload. Those are inputs.

## API

```text
POST /api/v1/activities/{activityId}/analysis    recompute from stored data, replace the result
GET  /api/v1/activities/{activityId}/analysis    read the last result
```

Manual only: no scheduler, no startup trigger. Neither verb reaches Garmin or Intervals.icu, and neither
can publish anything. Unknown activity → 404 `ACTIVITY_NOT_FOUND`; never analysed → 404
`ACTIVITY_ANALYSIS_NOT_FOUND`. No authentication, same convention as the other operational APIs.

## Not in this engine

HR drift as a rating, pace fade as a verdict, training-quality classification, readiness, injury risk,
and any good/bad threshold. Those are either the coach's reading or a later phase; none of them belongs
in a table of measurements.
