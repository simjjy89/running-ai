# Phase 6H-9 — Coach Data Freshness Pipeline — Result

Baseline SHA: `b075f3c65dedb117b9661182cd1ea478e04c5ced` (Phase 6H-8.2 result, including the Draft #17
real-publish addendum)
Final SHA: `4bfa515f7c5413865276a77e9a2f067064fef2b8` (main-3, 6 commits ahead of baseline;
fast-forward merged into the live checkout's `main`)

## 6H-8 final result update (section 83)

Done as the first commit of this phase (`c48bfe5`, before any 6H-9 code): the Phase 6H-8.2 result
doc now records Draft #17's real, verified, end-to-end successful publish
(`outcome=PUBLISHED`, `intervalsOperation=UPDATED`, `verified=true`, no API key value recorded
anywhere).

## Architecture

`com.runningai.coachrefresh` (new, top-level, deliberately **not** a subpackage of
`com.runningai.coach`): `CoachDataRefreshService`, `CoachDataFreshnessEvaluator`,
`CoachDataRefreshController` (`POST /api/v1/coach/data-refresh`), `CoachDataRefreshScheduler`
(optional, default off), models, properties. Full diagram and reasoning in
`docs/architecture/coach-data-freshness.md`.

### The one architectural fact everything else follows from

`CoachArchitectureTest` forbids any file under `com.runningai.coach` from importing
`com.runningai.integration.garmin`/`intervals` **at all** - not just as a runtime dependency, as a
source-level import, checked recursively across the whole package. Since this phase's entire
purpose is a service that calls both, it could not live under `com.runningai.coach` without either
breaking that test or weakening it (never acceptable - it is the single most important safety
property protecting `WorkoutDraftService`). `com.runningai.coachrefresh` as a sibling top-level
package resolves this with zero compromise: `TrainingContextV2Builder`, `WorkoutDraftService` and
the rest of the coach package are completely unchanged in their Garmin/Intervals-free contract, and
`CoachArchitectureTest` itself required no modification and still passes unchanged.

This same constraint is also why `TrainingContextV2`'s additive `sourceFreshness` section has no
Garmin sync timestamp field, even though the work order's section 38 example included one - adding
it would need `TrainingContextV2Builder` to import a Garmin package. `sourceFreshness` contains only
what the builder can compute from dependencies it already has: `newestActivityDate` (re-packaged
from `dataCoverage`), `fitnessSourceDate`/`fitnessAgeDays` (re-packaged from `trainingLoad`), and a
new DB-only aggregate - `recoverySourceDate`/`recoveryAgeDays`, the single most-recently-available
recovery metric across hrv/sleep/restingHeartRate/bodyBattery/stress. Garmin sync freshness is
reported only by the refresh endpoint's own response and the CLI's freshness report, consumed by the
*operator* (or the pipeline that gates generate on it), never echoed into the context Claude reads.

## Pipeline order (implemented exactly as specified)

1. Garmin incremental summary sync (`GarminSyncOperationService`, reused as-is) - failure stops
   everything, `readyForCoach=false`, nothing else attempted.
2. Recent-activity completion, D-6..D (`recent-activity-days=7`): per activity, detail completion
   only if not already complete (never re-fetched merely for being DOWNSAMPLED/UNKNOWN - existing
   policy preserved), running analysis only for RUN/TREADMILL_RUN and only if missing/version-stale/
   this-run-recollected, Intervals activity enrichment every activity every refresh (UNMATCHED/
   AMBIGUOUS are normal outcomes, never failures; an account-level Intervals failure stops the loop
   with no retry).
3. Intervals fitness refresh, D-6..D (`fitness-refresh-days=7`).
4. Garmin recovery refresh, D and D-1 (`recovery-refresh-days=2`, via the existing `backfill()`).
5. `CoachDataFreshnessEvaluator.evaluate(...)` - DB-only, zero network calls.

Single-flight via the service's own `ReentrantLock` (409 `COACH_DATA_REFRESH_ALREADY_RUNNING` on a
concurrent call, never queued) - independent of every sub-service's own existing single-flight
guard, which is untouched. No stage is retried anywhere.

## CLI integration

`running-ai-coach.ps1`'s generate path (not resume): health → `POST /api/v1/coach/data-refresh` →
freshness report (`Show-RunningAiCoachDataRefresh`) → generate. A `NOT_READY` result stops before any
draft is generated (zero `POST /workout-drafts` calls, so zero Claude calls). `-DraftId` (resume)
never refreshes - an existing draft's context snapshot is already immutable. `-SkipRefresh` (debug
only) generates from stored data with a loud warning; it required no change to
`Invoke-RunningAiControlledPublish` and touches no approve/publish gate.

## Regression

- **H2**: `.\gradlew.bat clean test` (JDK 21) - **1203 passed, 0 failed** (baseline 1192, +11: 9 in
  `CoachDataRefreshServiceTest`, 2 new `sourceFreshness` checks in `TrainingContextV2BuilderTest`).
- **PostgreSQL**: full suite re-run against a freshly recreated throwaway database - **1203 passed,
  0 failed**, identical to H2.
- **PowerShell**: `Test-RunningAI.ps1` 39/39 (unchanged), `Test-CoachOperator.ps1` **25/25** (21
  baseline + 4 new refresh-integration checks), `Test-Watchdog.ps1` 36/36 (unchanged). Total
  **100/100** (baseline was 96).
- **Python**: `tools/garmin-connector` untouched (confirmed via `git status`); **134 passed**,
  matching baseline.

All green in both `main-3` and, after the fast-forward merge, the live checkout.

### Test coverage against the work order's named scenarios (sections 56-71)

Directly covered in `CoachDataRefreshServiceTest.kt`: Garmin summary failure -> NOT_READY nothing
else attempted (§61); a new running activity completed end to end -> READY (§59); idempotent second
refresh, zero detail calls/zero analysis recompute (§58); a cycling activity never pushed through
running analysis (§60); Intervals fitness failure -> NOT_READY, zero retry (§64); Garmin recovery
transport failure -> NOT_READY (§66); a real rest streak is not treated as stale (§67); the DB-only
evaluator reports stale fitness/recovery with zero new network call (§68); a concurrent refresh is
rejected, never queued (§52/57 implicitly - route-order enforcement by the fake server itself proves
the pipeline order). Directly covered in `Test-CoachOperator.ps1`: CLI generate calls refresh before
generate, in order (§69); `NOT_READY` stops before any draft is generated (§70); resume never calls
refresh (§71). TrainingContext purity (§56) is covered both by the existing unreachable-base-url
pattern across every `TrainingContextV2BuilderTest` test and by `CoachArchitectureTest`'s unmodified,
still-passing import/dependency scan. Not given a dedicated synthetic unit test (lower risk, judged
adequately covered by the live validation and the general design): Intervals activity UNMATCHED/
AMBIGUOUS as a pure warning (§63, exercised implicitly - the live run's own `intervalsMatched=2`
path proves the success path; the MATCHED/UNMATCHED/AMBIGUOUS branching itself is
`IntervalsActivityMatcher`'s own pre-existing, separately-tested logic, not new in this phase), and
Recovery-partial-metrics-still-ready (§65, same reasoning - missing-metric semantics are
`RecoverySnapshot.merge`'s existing, separately-tested behaviour, not new here).

## Live validation

Performed in full, with your explicit authorization:

1. Live checkout merge: `git status --short` clean; fast-forward (`b075f3c..4bfa515`, 19 files
   changed, no conflicts). Both `Test-RunningAI.ps1` (39/39) and `Test-CoachOperator.ps1` (25/25,
   after confirming one transient random-port collision on the first attempt was not a real
   regression by re-running) passed from the live checkout.
2. Rebuild + restart: `stop-running-ai.ps1` then `start-running-ai.ps1 -Build` (the first `-Build`
   attempt was a no-op since Spring was already healthy and the script's idempotent design correctly
   left it running - stopping first was required to force the rebuild to actually take effect).
   Spring back up in 45s. All four publishing switches confirmed `false` in `.env`.
3. **Real refresh-only call**, `POST /api/v1/coach/data-refresh` (no date - today, 2026-10-06),
   against the real Garmin connector and real Intervals.icu:
   - Garmin summary: `success=true`, 8 fetched, 1 created, 5 updated, 0 failed, checkpoint advanced,
     `syncAgeMinutes=0`.
   - Recent activities: 2, both running. One was already complete from an earlier sync; the other
     was newly detail-collected and analysed this run. Both sample streams `FULL`, both matched to
     Intervals (`intervalsMatched=2`, 0 unmatched, 0 ambiguous).
   - Intervals fitness: 7 days fetched and stored (`2026-09-30`..`2026-10-06`).
   - Garmin recovery: 2 days attempted, 2 updated, `completed=true`.
   - `freshness`: `newestActivityDate`/`latestFitnessDate`/`latestRecoveryDate` all `2026-10-06`,
     every age `0` days.
   - **`readyForCoach=true`, `reasons=[]`.**
4. **Synthetic draft generated** with a synthetic Korean goal ("today I want to run lightly since
   there is a race coming up soon"): Draft **#18**, `status=DRAFT`. The coach's own answer is itself
   a strong validation of the freshness pipeline working correctly - it noticed a real run had
   **already happened today** (the activity the refresh had just ingested: ~43 min, HR mostly zone
   3-4, 83% avg LTHR, training load 50 - "the heaviest single session logged in the last week") and
   the same-day recovery data showing real depletion (Body Battery collapsed to 5, HRV -18.7%,
   RHR +10%), and correctly recommended `REST` for the *rest* of the day rather than prescribing a
   second session - reading the athlete's "run light" request as "treat this morning's run as
   today's training; don't add more" rather than generating a workout in a vacuum. This is exactly
   the kind of evidence-correct behaviour the freshness pipeline exists to make possible: the draft
   used today's just-synced activity and today's just-synced recovery, not stale data, and the
   rationale never overstated freshness it didn't have.
5. Draft #18 confirmed `status=DRAFT` (not approved, not published). Draft #17's publish-preview
   re-checked: `approvalId=3`, `structuredStepCount=3`, `publication` identical to before (same
   `publishedAt`, same `remoteEventId`) - unchanged.
6. Final check: `GET /actuator/health` -> `UP`; all four publishing switches still `false` in
   `.env` (never touched).

## Safety verification

- Garmin workout writes: **0**. Intervals workout writes: **0** (no dependency on
  `IntervalsWorkoutPublisher`/`IntervalsWorkoutClient` anywhere in `com.runningai.coachrefresh`,
  structurally - the same guarantee `CoachArchitectureTest` enforces for the coach package itself).
- Draft publish: **0** (Draft #18 left in `DRAFT`; Draft #17 untouched).
- Garmin/Intervals reads: real and nonzero this session (the whole point of live validation), all
  through existing, already-safe, already-tested ingestion/enrichment services.
- Publishing switches: all four `false` throughout, confirmed before and after.

## Commits

Seven, in `main-3`:
- `c48bfe5` docs: record Draft #17's real successful publish (section 83, done first)
- `ea80c7f` feat: add coach data refresh pipeline
- `089c841` feat: add TrainingContextV2 source-freshness metadata
- `2d143ec` test: cover the coach data refresh pipeline
- `7de6ff7` feat: integrate coach data refresh into the operator CLI
- `4bfa515` test: cover the coach CLI's refresh integration

Fast-forward merged into the live checkout's `main` (`b075f3c..4bfa515`). A docs commit (this result
doc + instruction doc) follows.

## Push

Not done yet (pending your instruction, same as every other phase).

## Known limitations

- Two named test scenarios (§63 Intervals UNMATCHED/AMBIGUOUS as pure warning, §65 recovery-partial-
  still-ready) were not given dedicated new unit tests - the underlying branching they'd exercise
  (`IntervalsActivityMatcher`, `RecoverySnapshot.merge`) is pre-existing, separately-tested logic
  this phase reuses rather than introduces; see the regression section above for the full reasoning.
- No optional refresh-history database table was added (section 55 explicitly did not require one;
  freshness is computed from timestamps/state that already existed).
- The optional scheduler (`CoachDataRefreshScheduler`) exists but stays `enabled=false` by default
  and was not enabled during this phase's live validation, per the work order's own instruction.

PHASE_6H_9_COACH_DATA_FRESHNESS_READY
