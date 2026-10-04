# Phase 6H-8.1 — Coach Operator Recovery Display Hotfix — Result

Baseline SHA: `4b4ece7031c74d160aead9bfeb3b3cbfdd449ade` (Phase 6H-8 final result)
Final SHA: `e654fc3a0ab77567911e86f0094312ff2f353d1b` (main-3, 2 commits ahead of baseline; fast-forward
merged into the live checkout's `main` at `C:\running-ai-github`)

## Root cause

Confirmed exactly as hypothesized: `Write-RunningAiSegment -Segment $Segment.recovery` rendered a
`RecoveryResponse` as if it were a `SegmentResponse`. The two API DTOs are genuinely different
shapes - `RecoveryResponse` has no `type`, `repetitions`, `recovery`, `recoveryDurationMinutes`,
`heartRateBpmMin` or `heartRateBpmMax` field at all (not a null one, an absent one), and
`Write-RunningAiSegment` reads `type` and `repetitions` unconditionally. Under
`Set-StrictMode -Version Latest` (set when `RunningAI.Common.ps1` is dot-sourced, in effect for
every function defined afterward in the same scope), referencing a genuinely-absent property throws
`PropertyNotFoundException` - exactly the live error (`type`, then `repetitions`).

Reproduced and verified fixed by direct comparison: calling the old pattern
(`Write-RunningAiSegment -Segment <a real recovery fixture>`) against a real-API-shaped
(JSON-round-tripped) recovery object throws `The property 'type' cannot be found on this object.`;
the new `Write-RunningAiRecovery` path does not.

## Fix

- **`Write-RunningAiRecovery`** (new): renders a `RecoveryResponse` by its own actual fields
  (`durationMinutes`, `intensity`, `description`, plus its target via `Format-RunningAiSegmentTarget`)
  and never reads `type`, `repetitions` or `recovery`.
- **`Write-RunningAiSegment`**: the repeat-block branch now calls `Write-RunningAiRecovery -Recovery
  $Segment.recovery` instead of recursing into itself. The legacy `recoveryDurationMinutes`-only
  fallback (no nested `recovery` object) is unchanged.
- **`Get-RunningAiOptionalProperty`** (new, exactly the implementation given in the work order):
  returns `$null` for a property that does not exist at all, rather than throwing. `Format-
  RunningAiSegmentTarget` (shared by both segments and recovery blocks) now reads every optional
  target field (`paceSecondsPerKmFast/Slow`, `heartRatePercentLthrMin/Max`, `heartRateBpmMin/Max`,
  `treadmillSpeedKphMin/Max`, `inclinePercentMin/Max`) through it - a `RecoveryResponse` simply has
  no bpm fields to find, and a qualitative recovery (no numeric target at all) is now handled
  safely too.
- **Fail-closed display** (`Invoke-CoachOperatorSession`): the call to `Show-RunningAiWorkoutDraft`
  is now wrapped in `try`/`catch`; any exception returns `Outcome = 'DISPLAY_FAILED'` immediately,
  before the `SUPERSEDED`/`NonInteractive`/`APPROVED` checks or the `[R]/[A]/[Q]` menu are ever
  reached - zero approve, preview, or publish calls follow a broken display, regardless of what
  broke it. `running-ai-coach.ps1`'s exit-code switch maps `DISPLAY_FAILED` to exit 1 alongside the
  other failure outcomes.

A design issue was found and fixed while building the regression tests: the work order's suggested
`Get-RunningAiOptionalProperty` implementation (`.PSObject.Properties[$Name]`) is correct for a real,
JSON-parsed API response object, but a **raw PowerShell hashtable's `.PSObject.Properties` never
exposes the hashtable's own keys at all** (confirmed empirically) - a check written against a raw
hashtable fixture would have silently passed even with the original bug still present. Every
fixture handed directly to a display function now goes through a new
`ConvertTo-RunningAiFakeApiObject` helper (`ConvertTo-Json` then `ConvertFrom-Json`) first, so tests
exercise the exact object shape production code actually receives; the one pre-existing check that
passed raw hashtables directly was updated the same way.

## Published short-circuit preserved

Confirmed unchanged and still covered by the existing Phase 6H-8 regression
("already-published draft: zero new publish, zero switch/restart") - no new test was needed for this
specifically (section 6 asked to pin the behaviour as a regression test, and it already was one); it
was additionally re-confirmed live (see Live smoke).

## New regression tests (A-F)

All six added to `Test-CoachOperator.ps1`, using new fixtures (`New-RecoveryFixture`,
`New-RepeatSegmentFixture`, `New-SimpleSegmentFixture`, `New-RepeatDraftFixture` - matching the real
API shapes field-for-field, including the fields a `RecoveryResponse` genuinely lacks):

- **A**: a targeted repeat+recovery segment (`MAIN x5`, `Recovery`, HR target) displays without error.
- **B**: a recovery object with no `type` property displays without `PropertyNotFoundException`.
- **C**: a recovery object with no `repetitions` property displays without `PropertyNotFoundException`.
- **D**: a qualitative recovery (no pace/%LTHR/bpm) displays with no error and no target line.
- **E**: a draft that fails to display (a segment missing even `type`, future-proofing the gate
  beyond the specific bug just fixed) never reaches approve/preview/publish - `DISPLAY_FAILED`,
  exactly 2 calls (health + generate), and a `-Reader` that would itself fail the test if ever
  invoked, proving the menu is never reached.
- **F**: a full Draft #10-compatible shape (`WU HR` / `MAIN PACE x5` / `Recovery HR` / `CD HR`)
  displays completely (`Outcome = DISPLAYED`, 3 segments, nested recovery duration correct).

## Server changes

None. No `application.yml` change, no Kotlin/Java change, no DB migration, no Garmin/Intervals
integration change.

## Regression

- **H2**: `.\gradlew.bat clean test` (JDK 21) - **1192 passed, 0 failed**, identical to baseline (no
  server code changed).
- **PostgreSQL**: full suite re-run against a freshly recreated throwaway database
  (`running_ai_test`, Hikari pool 2) - **1192 passed, 0 failed**, identical to H2.
- **PowerShell**: `Test-RunningAI.ps1` 36/36, `Test-Watchdog.ps1` 36/36 (both unaffected, re-run for
  completeness), `Test-CoachOperator.ps1` **20/20** (14 baseline + 6 new A-F). Total **92/92**
  (baseline was 86; +6 as required).
- **Python**: `tools/garmin-connector` untouched (confirmed via `git status`); **134 passed**,
  matching baseline.

All green in both `main-3` and, after the fast-forward merge, the live checkout.

## Live smoke

Performed, read-only, no approve of a new draft, no new publish:

1. `git status --short` in the live checkout was clean; fast-forward merge
   (`4b4ece7..e654fc3`, 3 files changed, no conflicts).
2. `Test-CoachOperator.ps1` re-run from the live checkout: **20/20 passed**.
3. `running-ai-coach.ps1 -DraftId 10` (first with `-NonInteractive`, confirming the display fix in
   isolation; then without, to exercise the full already-approved/already-published path):
   - Draft #10 v2 `APPROVED`, `INTERVAL`, 35 min, displayed **completely and without error** -
     `MAIN 2m (HARD)` / `Pace 4:35-4:45/km` / `x5` / `Recovery 1m (VERY_EASY)` /
     `Easy jog recovery between reps` / `HR 65-75% LTHR` - exactly the shape that previously threw
     `PropertyNotFoundException` on `type` then `repetitions`.
   - The idempotent `POST /approve` call succeeded (`approvalId=2`, no new human decision - draft
     was already `APPROVED`).
   - `GET /publish-preview` showed `publishable=True`, `expectedOutcome=PUBLISH`,
     `structuredStepCount=12`, and **`publication: outcome=PUBLISHED verified=True`**.
   - The script printed `already published; no new external write performed` and exited normally -
     no human input was ever requested (the already-published short-circuit returns before the
     `YES` gate), confirming this path truly needs zero interaction.
4. `GET /actuator/health` immediately after: still `UP` - no restart occurred (a real restart takes
   tens of seconds; this whole run completed in a couple of seconds).

## Safety verification

- Runtime restarts during live smoke: **0** (confirmed by elapsed time and an unchanged `UP` health
  check immediately after).
- Intervals writes: **0** (the already-published short-circuit returns before the publish switch is
  ever touched - same code path verified in commit `99ff0b1`/`1c8e39b`, re-confirmed live here).
- Garmin writes: **0** (nothing in this draft's generate/resume/approve/preview path reaches Garmin).
- Publishing switches: untouched by this entire live smoke run - the already-published short-circuit
  in `Invoke-RunningAiControlledPublish` returns immediately after the preview call, before
  `$env:RUNNING_AI_DRAFT_PUBLISHING_ENABLED` is ever set.

## Commits

Two, in `main-3` (`1c8e39b` fix: render recovery blocks with their own API shape, `e654fc3` test:
cover recovery-block display shapes), fast-forward merged into the live checkout's `main`
(`4b4ece7..e654fc3`).

## Push

Not done (pending user instruction).

## Known limitations

None identified for this hotfix's own scope. The broader Phase 6H-8 limitations (controlled-publish
validation not yet performed end to end) are unchanged and still apply.

PHASE_6H_8_1_COACH_OPERATOR_RECOVERY_DISPLAY_READY
