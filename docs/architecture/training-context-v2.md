# TrainingContext V2 (Phase 6H-7)

```text
Garmin facts (activity, detail, recovery)
       │
       ▼
RunningAI Analysis (6H-4)         Intervals.icu training model (6H-5)
       │                                      │
       └───────────────┬──────────────────────┘
                        ▼
              TrainingContext V2
              (compact evidence snapshot)
                        │
                        ▼
                     Claude
```

V2 is a parallel implementation, not a replacement: `TrainingContext` (V1) stays exactly as it was
(`TrainingDecisionContext` + athlete threshold + Garmin recovery), and both implement the shared
`CoachTrainingContext` contract (`date`, `athlete`, `constraints`) that `AiCoach` actually depends on.
`running-ai.coach.context-version` (`RUNNING_AI_TRAINING_CONTEXT_VERSION`) selects which one
`CoachTrainingContextBuilder` builds; the source default is `V1`.

## What V2 adds, and the one thing it never does

V2 turns three already-stored evidence layers into one snapshot a coach can read in one pass: Garmin
detail (laps, zones, full-resolution samples — never re-read directly, only through what 6H-4 derived
from them), RunningAI Analysis (6H-4: half-split change, decoupling, threshold exposure, lap/interval
repeatability), and Intervals.icu's training model (6H-5: CTL/ATL/derived form, per-activity training
load). It is **DB-only**: the builder makes zero Garmin calls, zero Intervals calls, and never calls
`RunningActivityAnalysisService.analyse()` — an activity without a stored analysis is reported as
missing, never recomputed on the spot.

It never decides. `candidateTrainingTypes` (V1's deterministic shortlist of plausible session types)
has no V2 counterpart — the coach reads the evidence and chooses the session itself. No coverage
count is turned into a verdict (no `historyQuality = POOR`); counts are reported as counts, and a wide
90-day window with few activities must read as exactly that, never as a dense history.

## Compaction rules (apply to every field below)

- **Null = genuinely unknown.** Never a default, never an estimate.
- **No raw payload, no identity, no GPS.** Garmin/Intervals activity ids, tokens and coordinates never
  reach the model; a dedicated test asserts their absence from the serialized snapshot.
- **No look-ahead.** For session date `D`, nothing with an athlete-local date after `D` can appear —
  activities, Intervals fitness days and recovery days are all queried with an inclusive upper bound
  at `D` (activities use the same half-open `[D-89, D+1)` instant range V1's decision context uses).
- **Bounded, not unlimited.** `recentActivities` is capped at `running-ai.coach.context-v2.max-recent-
  activities` (default 8, newest first); each activity's interval groups are capped at
  `max-interval-groups-per-activity` (default 3); individual repetitions are never included (a
  debug/read API can expose them later if needed).
- **Deterministic serialization.** `CoachContextSerializer` orders map keys, uses ISO-8601 dates, and
  never embeds `computedAt`/`now()` — the same stored data always serializes to the same bytes, which
  is what makes the persisted SHA-256 meaningful.
- **Hard size guard.** `running-ai.coach.context-v2.max-snapshot-bytes` (default 65536, 64 KiB).
  Exceeding it is `TRAINING_CONTEXT_TOO_LARGE`, raised **before** any coach call — never silently
  truncated.

## Field-by-field provenance

| Section | Provenance | Date semantics | Missing semantics |
|---|---|---|---|
| `athlete` | Athlete's own stored threshold profile (same as V1) | n/a | either value independently null |
| `dataCoverage` | Derived counts over the stored window | counts as of `D`, inclusive | a zero count, never omitted |
| `recovery` | GARMIN (Phase 6F `RecoveryContextBuilder`, reused unchanged) | per-metric `ageDays` from `D` | a metric group is null when Garmin never reported it |
| `trainingLoad` | INTERVALS (Phase 6H-5: `ctl`/`atl` = Intervals' calculated fitness/fatigue) | `sourceDate`/`ageDays` = the nearest stored day at or before `D`, never `D` itself presented as current when it is not | `sourceDate` null when no fitness day exists anywhere in the 90-day window |
| `trainingLoad.sevenDaysAgo`/`twentyEightDaysAgo` | INTERVALS | exact `D-7`/`D-28` only | null when that exact day was never stored — no nearest-day substitution |
| `trainingRhythm` | Derived from stored Garmin activities | `daysSince*` relative to `D` | a `null` date means no qualifying activity exists in the 90-day window |
| `recentActivities[].activity` | GARMIN (Activity + stored detail) | activity's athlete-local date | n/a (always present for every returned activity) |
| `recentActivities[].runningAiAnalysis` | RUNNING_AI (Phase 6H-4, read-only) | n/a | null when no analysis is stored for that activity |
| `recentActivities[].intervals` | INTERVALS (Phase 6H-5, read-only) | n/a | null when the activity has no Intervals match |
| `recentActivities[].dataQuality` | Garmin sample fidelity + RunningAI analysis status | n/a | `sampleCompleteness` null when no stream was ever collected |
| `constraints` | The athlete's own request for this call | n/a | every field independently optional |

`speedHrDecouplingPercent` and every other `runningAiAnalysis` field are **RunningAI-derived
descriptive metrics**, not Garmin's or Intervals.icu's own scores — the system prompt states this
explicitly so the coach never attributes them to the wrong source. CTL/ATL/derived form follow the
same terminology fixed in Phase 6H-5: CTL = Intervals' calculated fitness, ATL = Intervals' calculated
fatigue, `derivedForm = ctl - atl`. The subjective wellness `fatigue` field has no representation
anywhere in V2.

## Persistence and audit (V20)

Every draft — whichever context version built it — records `context_version`, `context_snapshot`
(the canonical JSON), `context_built_at` and `context_sha256` (`workout_draft`, migration V20; all
four nullable, pre-6H-7 drafts keep them null rather than a fabricated backfill). A revision computes
and stores its own snapshot; the superseded row's snapshot is never touched. The snapshot is built and
size-guarded **before** the coach is called, so an oversized context never reaches Claude and never
wastes a call.

## Read-only preview

`GET /api/v1/coach/training-context?date=&version=` builds and serializes a context exactly as a real
draft generation would, with zero Garmin calls, zero Intervals calls and zero Claude calls — useful
for inspecting what a given day's context looks like, or for comparing V1 and V2 side by side, without
spending a model call or creating a draft.

## Synthetic schema example

Illustrative only — field names and shape, not any real athlete's numbers:

```json
{
  "contextVersion": "V2",
  "date": "2026-10-03",
  "athlete": {
    "lactateThresholdHeartRateBpm": 172,
    "lactateThresholdPaceSecondsPerKm": 295
  },
  "dataCoverage": {
    "historyWindowDays": 90,
    "supportedActivityCount": 12,
    "analysedActivityCount": 12,
    "fullSampleActivityCount": 11,
    "intervalsMatchedActivityCount": 10,
    "fitnessWindowDays": 90,
    "fitnessDaysAvailable": 88,
    "recoveryWindowDays": 28,
    "recoveryDaysAvailable": 24,
    "oldestActivityDate": "2026-07-10",
    "newestActivityDate": "2026-10-03"
  },
  "trainingLoad": {
    "sourceDate": "2026-10-02",
    "ageDays": 1,
    "ctl": 42.1,
    "atl": 38.7,
    "derivedForm": 3.4,
    "sevenDaysAgo": { "date": "2026-09-26", "ctl": 40.0, "atl": 41.2, "derivedForm": -1.2 },
    "twentyEightDaysAgo": null
  },
  "trainingRhythm": {
    "consecutiveActiveDays": 2,
    "consecutiveRestDays": 0,
    "structuredIntervalDetectionAvailable": true,
    "lastStructuredIntervalDate": "2026-09-30"
  }
}
```

## Activation gate

V2 is activated on the Main PC only after the live validation in
`docs/work-orders/2026-10-03-phase-6h-7-training-context-v2-result.md` passed: no future leakage, no
raw sample/identity/GPS presence, a live preview under the size guard, and a live Claude call that
used the evidence honestly. Until then `RUNNING_AI_TRAINING_CONTEXT_VERSION` stays `V1`.
