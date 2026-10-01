# Phase 5C-4 Real Intervals → Garmin Forerunner 265 Device Validation — work order

(Condensed faithful transcription of the user's instruction; all rules and acceptance criteria are kept, prose and
repeated examples are shortened.)

Baseline: Phase 5C-3.5 complete (commit `138ae4e` + docs `7870e81`).

```text
Intervals renderer          UNIT_VERIFIED
Intervals publisher         SERVER_VERIFIED
Intervals server readback   SERVER_VERIFIED
Pace Garmin                 UNRESOLVED
%LTHR Garmin                ASSUMED
Treadmill cue Garmin        DEVICE validation required
```

## 1. Pre-cleanup
The synthetic "RunningAI workout" on 2026-11-16 (Phase 5C-3.5) is deleted **manually** by the user in the Intervals.icu web UI.
Start only after confirming (read-only lookup) the date is empty. No production delete feature.

## 2. Goal
Independently verify on a real Garmin Forerunner 265: **A. PACE target, B. %LTHR target, C. TREADMILL speed/incline cue.**
Final state of each: `DEVICE_VERIFIED` | `UNRESOLVED` | `FAILED_WITH_EVIDENCE`.

## 3. Nature
Not feature development. Flow: publish → Intervals readback → Garmin sync → Garmin device observation → evidence → boundary
classification. **No production code change before a real device mismatch is observed.**

## 4. Forbidden
scheduler / `@Scheduled`, ChatGPT integration, automatic publishing, DB migration, new persistence table, legacy deletion,
large renderer rewrite, Garmin API reverse engineering, automatic Garmin Connect manipulation, Controller / public endpoint.
Phase 5C-5 must not be started.

## 5–7. Git / credential / SSL
`git status`, `git switch main`, `git pull --ff-only origin main`, clean tree. `INTERVALS_API_KEY` presence only (never print);
`INTERVALS_ATHLETE_ID` unset (default `0`). Live-validation JVM only: `-Djavax.net.ssl.trustStoreType=Windows-ROOT`;
never disable certificate verification, never hard-code it in production config.

## 8–11. Separate workouts, safe dates, no production API, real pipeline
Three separate workouts (A PACE, B %LTHR, C TREADMILL), each on its own safe date (read-only lookup first: 0 WORKOUT events,
0 RunningAI-owned events; never overwrite a real plan). Temporary manual runner only (deleted before commit). Build the
workouts through `TargetedWorkoutPrescription → StructuredWorkoutMapper → StructuredWorkout → IntervalsWorkoutRenderer →
IntervalsWorkoutPublisher`, reusing existing golden-fixture values; no new formatting rules.

## 12–19. Test A — PACE
Warmup 5 min / Work 3 min with explicit pace target (existing golden value) / Cooldown 2 min. Publish; check operation,
verified, remote id, description readback equals renderer output exactly (⇒ RunningAI → Intervals PASS). Deliver to Garmin using the
user's existing workflow only (no sync automation). On the 265 check: workout exists, step count/order, Work duration, target shown,
target type, pace lower/upper. Key question: does Garmin treat it as a **Pace Target** (description/cue text alone is not success)?
Success = real target pace shown on the Work step + usable target indication when started ⇒ `Pace Garmin = DEVICE_VERIFIED`.
If "목표 없음" again ⇒ stays `UNRESOLVED`, no immediate renderer change; record Spring render PASS / Intervals PASS / Garmin FAIL
(likely Intervals → Garmin transport/conversion). Ask the user only: is target pace shown, what value, or "목표 없음".

## 20–25. Test B — %LTHR
After A. Warmup 5 / Work 3 at an existing valid %LTHR range / Cooldown 2; no new threshold computation. Readback must preserve
`% LTHR` and `hr=1s`. On the 265 check HR target exists, range shown, interpreted as LTHR-based, guidance usable when started.
Works ⇒ `%LTHR Garmin = DEVICE_VERIFIED`. No target / wrong target / description only ⇒ `FAILED_WITH_EVIDENCE` (or `UNRESOLVED` if
cause unclear). `ASSUMED` is not kept after a device observation.

## 26–33. Test C — TREADMILL
Short Warmup / Work / Cooldown; Work step with explicit numbers (speed 12.0 km/h, incline 1% or a golden value), expected cue
`12.0kph Incline1pct` (use actual formatter output). Readback order must be `label, cue, duration, target` (cue before duration
and target). On the 265: cue visible? speed? incline? truncated? shown with duration/target? Success = formatter result readable
on the real step ⇒ `Treadmill cue Garmin = DEVICE_VERIFIED`; if cue-before-duration/target ordering works on the new path ⇒
`Cue ordering migration = DEVICE_VERIFIED`. The cue is **not** a Garmin structured treadmill speed target; do not claim Garmin
controls the treadmill.

## 34–38. Independence, delivery, sync, evidence
Each feature is judged independently. If the workout never reaches Garmin, make no target/cue judgement
(`Intervals server PASS`, `Garmin delivery FAIL/UNKNOWN`, no renderer change). Record whether delivery was automatic, needed a manual
Garmin Connect sync, or a watch sync. Evidence order: renderer output → Intervals readback → Garmin Connect appearance → 265
preview → 265 active workout. Claude cannot see the watch: ask the user explicit per-test questions; **no DEVICE_VERIFIED before the
user's confirmation.**

## 39–41. Mismatch handling
Compare render output, Intervals readback and Garmin observation; classify: **A** Spring render wrong; **B** Spring correct,
Intervals readback wrong (publisher/API contract); **C** Spring + Intervals correct, Garmin wrong (Intervals→Garmin compatibility);
**D** Garmin workout absent (delivery/sync). Expected production code change = NONE; defects are recorded and fixed in a
follow-up phase.

## 42–44. Cleanup, tests, result
Synthetic events are removed manually in the Intervals UI after verification (never real workouts). Docs-only ⇒ baseline stays
396/396. Result: `2026-10-01-phase-5c-4-garmin-device-validation-result.md` (no personal identifiers).

## 45–47. Report format, DoD, next step
Report sections: Environment, Test A/B/C (Intervals publish/readback, Garmin delivery, preview/active target or cue observations,
Result), Transport, Boundary diagnosis (Spring / Intervals / Garmin per feature), Verification status, Code changes, Cleanup, Git.
Definition of Done: 2026-11-16 event cleaned; A/B/C each: safe date, publish/readback, direct device observation; sync behavior
recorded; independent verdicts; no unconfirmed DEVICE_VERIFIED; synthetic events cleaned; temp code removed; result document;
diff + secrets check; commit/push only if needed. All PASS ⇒ Phase 5C could be judged complete, next candidate 5C-5 (legacy
publishing retirement) — **not auto-started**. Partial FAIL ⇒ separate 5C-4A/4B follow-ups for the failed feature only.
