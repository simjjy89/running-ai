# AI draft device targets (Phase 6H-7.1)

```text
Claude Coach
     │  (chooses pace XOR %LTHR per segment, and per recovery block)
     ▼
WorkoutDraft / WorkoutDraftSegment / WorkoutDraftRecovery
     │  (approved, immutable - Phase 6G)
     ▼
WorkoutDraftStructuredWorkoutMapper          <- lossless transport mapping only
     │  (never recomputes, never looks up the athlete profile)
     ▼
StructuredWorkout / StructuredWorkoutStep
     │
     ▼
IntervalsWorkoutRenderer (unchanged, Phase 5C-3)
     │
     ▼
Intervals.icu -> Garmin device
```

## Problem this phase solves

Before this phase, only the main work interval ever carried a numeric pace target; warm-up, cool-down
and the recovery between repetitions rendered as a bare duration (`Warm Up 12m`, `Rest 2m`). This was
not a renderer limitation - `IntervalsWorkoutRenderer` already supported `%LTHR` - it was that
`WorkoutDraftSegment` had no field for it and the coach had no contract for emitting one. This phase is
a **lossless-representation problem only**: it never changes what workout the coach decides to
prescribe (type, duration, repetitions, hard/easy, main pace). It only gives the coach a way to say,
for every segment and every recovery block, which device-usable target - if any - it intends.

## The target model

Each segment (and each recovery block) carries at most one physiological primary target, declared
explicitly by `primaryTargetType`:

| `primaryTargetType` | Required | Forbidden |
|---|---|---|
| `PACE` | a complete `paceSecondsPerKmFast/Slow` pair | a complete `%LTHR` pair |
| `HEART_RATE` | a complete `heartRatePercentLthrMin/Max` pair | a complete pace pair |
| `QUALITATIVE` | neither | neither |
| `NONE` | neither | neither |
| *(absent/null)* | legacy pre-6H-7.1 inference applies (pace present -> `PACE`, else qualitative/none) | - |

A treadmill speed/incline cue is independent of this and may accompany any of the above - it is an
**operational cue**, never a substitute for the physiological target. A segment with threshold data
available but only a treadmill speed (no pace, no %LTHR) still fails V2 target completeness (see below).

**Exactly one** physiological representation is allowed per segment or recovery block: a draft that
sets both a complete bpm range and a complete %LTHR range, or claims `PACE` while also carrying a
complete %LTHR pair, fails closed (`WorkoutDraftValidationException`) rather than silently picking one
or dropping one.

## Why %LTHR, never bpm, for AI-prescribed heart-rate targets

`HeartRateTarget.minBpm/maxBpm` are nullable and are **never populated by an AI draft** - only
`RunningIntensityTargetPolicy`'s deterministic pipeline (`/api/v1/workout-intensity-targets`) ever sets
them, from the athlete's *current* profile at computation time. An AI draft's `%LTHR` is approved once
and may sit for days before publish; the athlete's LTHR can change in that window. Converting a stored
%LTHR to bpm at publish time would use a *different* LTHR than the one the coach actually reasoned
about, silently changing the prescribed effort. `ApprovedWorkoutDraftPublishabilityValidator` therefore
rejects an absolute-bpm target from ever reaching the renderer (`heartRateBpmMin/Max` stay reserved for
the deterministic pipeline only) - this is permanent, not a migration step.

The prompt's guidance band (`ClaudeCoachPromptBuilder.TARGET_GUIDANCE`) reuses the existing
`RunningIntensityTargetPolicy` %LTHR bands (VERY_EASY 65-78%, EASY 75-85%) as a reference only; it does
not introduce a new zone scheme, and nothing here computes or checks against those bands - the coach
applies its own judgement.

## Targeted recovery

`WorkoutDraftRecovery` is a nested, optional block on a repeated segment (`repetitions != null`),
carrying its own duration, intensity, `primaryTargetType` (required, not inferred - a recovery block
never falls back to legacy inference) and target fields, independent of the work interval's target.
It sits alongside - never replacing - the legacy `recoveryDurationMinutes` field, so a draft may set
exactly one of the two (both is a validation error). `primaryTargetType = NONE` is a legitimate,
deliberate choice (e.g. "stand and walk") and is never force-filled with an invented target just
because the athlete's threshold is known.

`WorkoutDraftStructuredWorkoutMapper.expand()` resolves the recovery step from `recovery` first,
falling back to the legacy `recoveryDurationMinutes` only when `recovery` is absent - this is exactly
how Draft #7 (approved and published before this phase) continues to map and render byte-for-byte
identically: it only ever populated the legacy field.

## V2-only target completeness

`WorkoutDraftValidator.validate(draft, context: CoachTrainingContext)` adds one rule on top of the
existing hard-safety checks, gated to `context is TrainingContextV2`: when the athlete has a measured
LTHR or threshold pace that `TrainingContextV2.athlete` actually reports, every non-REST segment must
carry a real pace or %LTHR target (`QUALITATIVE`/no target is rejected as `V2 target completeness`).
`TrainingContext` V1 never gets this rule - `ClaudeAiCoach.ask()` calls the two-argument
`validate(draft, date, athlete)` form for V1 and the context-aware overload for V2, so V1 behaviour
(including every existing V1 test) is unchanged. A REST segment, and a recovery block (which is
validated separately, not through this rule), are exempt - a passive recovery choice is never forced
to carry a target.

When the athlete has no threshold data at all (`TrainingContextV2.athlete` both null), the rule does
not apply regardless of context version: there is nothing for the coach to target against.

## What never changes here

- **No training decision.** Workout type, duration, repetition count, hard/easy, and the main interval's
  pace are entirely the coach's call, exactly as before this phase.
- **No recalculation at publish time.** The mapper copies the approved draft's exact percent/pace/speed
  values; it never looks up the athlete's current profile. A `%LTHR` published today is the percent the
  athlete approved, not a bpm recomputed from today's LTHR.
- **No renderer change.** `IntervalsWorkoutRenderer`/`GarminSafeCueFormatter` already rendered `%LTHR`
  (`renderHeartRate` reads only the percent fields); this phase adds no new Intervals Workout Builder
  syntax.
- **No DB migration.** `WorkoutDraft.segments` is stored as one JSONB blob; every new field is an
  additive, nullable Kotlin property, so a pre-6H-7.1 stored draft (Draft #7 included) still
  deserializes and still renders identically - `jackson-module-kotlin`'s default-parameter-value
  deserialization treats an absent JSON key exactly as a Kotlin default.
