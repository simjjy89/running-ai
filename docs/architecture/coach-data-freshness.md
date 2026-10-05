# Coach data freshness pipeline (Phase 6H-9)

```text
Garmin summaries ──┐
Garmin details ─────┤
Running analysis ───┤
Intervals metrics ──├─> RunningAI DB
Intervals fitness ──┤
Garmin recovery ────┘
                           │
                  CoachDataFreshnessEvaluator   (DB-only, zero network calls)
                           │
                    TrainingContextV2Builder    (DB-only, unchanged invariant)
                           │
                        Claude
```

## The gap this closes

Before this phase, keeping the RunningAI DB current meant running five separate manual endpoints
by hand (Garmin summary sync, Garmin detail collection, running analysis, Intervals activity
enrichment, Intervals fitness enrichment, Garmin recovery sync) before every coach session, or
trusting a database that might be hours or days stale. `POST /api/v1/coach/data-refresh`
(`com.runningai.coachrefresh`) is a single orchestration call that runs all of them in the right
order, then judges whether the result is fresh and complete enough for a new coach decision.

## The invariant this must never break

`TrainingContextV2Builder.build()` stays exactly what it was: DB-only, zero Garmin calls, zero
Intervals calls, zero `RunningActivityAnalysisService.analyse()` recomputation. `WorkoutDraftService`
keeps its existing, architecture-test-enforced contract of no dependency on any Garmin/Intervals/
publishing type. `CoachDataRefreshService` is therefore a **separate, sibling top-level package**
(`com.runningai.coachrefresh`, not `com.runningai.coach.refresh`) - `CoachArchitectureTest` forbids
any file under `com.runningai.coach` from importing `com.runningai.integration.garmin`/`intervals`
at all, and this service's entire purpose is to call both. Refreshing the DB and reading it for a
coach decision are two separate, explicit steps; nothing in `WorkoutDraftController`'s existing
generate/revise endpoints changed.

## Pipeline order

```text
1. Garmin incremental activity summary sync       (GarminSyncOperationService, reused as-is)
      │  failure -> stop here, readyForCoach=false, nothing else attempted
      ▼
2. Recent-activity completion, D-6..D (recent-activity-days)
      for each activity in the window:
        a. detail completion (GarminActivityDetailIngestionService) - only if not already
           complete; a DOWNSAMPLED/UNKNOWN sample stream is never re-requested (existing policy)
        b. running analysis (RunningActivityAnalysisService) - RUN/TREADMILL_RUN only, only if
           missing, version-outdated, or this refresh actually (re)collected the detail
        c. Intervals activity enrichment (IntervalsEnrichmentService.enrichActivity) - every
           activity, every refresh; UNMATCHED/AMBIGUOUS are normal outcomes, not failures; an
           account-level Intervals failure (401/403/429/timeout/connection) stops the loop, no retry
      ▼
3. Intervals fitness refresh, D-6..D (fitness-refresh-days)       (IntervalsEnrichmentService.enrichFitness)
      │  failure -> readyForCoach=false
      ▼
4. Garmin recovery refresh, D and D-1 (recovery-refresh-days)     (GarminRecoverySyncService.backfill)
      │  failure -> readyForCoach=false
      ▼
5. CoachDataFreshnessEvaluator.evaluate(...)        DB-only, zero network calls
      ▼
6. CoachDataRefreshResult: date, readyForCoach, reasons, per-stage summaries, freshness facts
```

Single-flight in this JVM (`CoachDataRefreshService`'s own `ReentrantLock`, independent of every
sub-service's own single-flight guard, which stays in place unmodified). A concurrent request gets
`409 COACH_DATA_REFRESH_ALREADY_RUNNING` immediately - never queued. No stage is retried anywhere in
this pipeline; an account-level failure (401/403/429/timeout/connection-refused) is recorded and the
pipeline moves on to report `readyForCoach=false`, never hammering the same failing call again.

## Role separation (section 4)

| Component | Responsibility |
|---|---|
| `CoachDataRefreshService` | External source → RunningAI DB refresh. Orchestration only - every external call goes through an already-safe existing service. |
| `CoachDataFreshnessEvaluator` | DB-only readiness judgement. Zero network calls. |
| `TrainingContextV2Builder` | DB-only evidence serialization for the coach. Unchanged. |
| `WorkoutDraftService` | `TrainingContext` → Claude. Unchanged, still has zero Garmin/Intervals/publishing dependency. |

`CoachDataFreshnessEvaluator` is deliberately a different question from the orchestrator's own
network-stage outcomes: "did the Garmin summary sync / Intervals fitness / Garmin recovery call
itself fail *this run*" is a fact the orchestrator observes directly from its own calls, while the
evaluator asks "given what is now in the database (regardless of why), is it fresh and complete
enough for a coach decision?" - detail completeness, analysis currency, and fitness/recovery age are
all answerable from stored rows alone.

## Section 37 (critical): absence of activity is not evidence of staleness

```text
last run = 5 days ago        <- a fact about the athlete, not about data quality
Garmin sync just succeeded   <- a fact about the data pipeline
```

These are independent. `CoachDataFreshness.newestActivityDate` being old or null is reported as a
plain fact and is **never** itself turned into a blocker or a warning by the evaluator - an athlete
can legitimately not have trained in days, and that absence is only trustworthy evidence *because*
the Garmin summary-sync stage that precedes the evaluator already ran and succeeded this refresh.
If the summary sync had instead failed, the whole pipeline already stopped at stage 1 and the
evaluator is never reached at all - so by the time the evaluator runs, "no recent activity" always
means "the sync looked and found none," never "the sync was never attempted."

This is also why `TrainingContextV2`'s additive `sourceFreshness` section deliberately has **no**
Garmin sync timestamp (see `TrainingContextV2.kt`'s `SourceFreshnessV2` doc comment) - adding one
would require `TrainingContextV2Builder` to import a Garmin package, which the architecture test
forbids. Whether the Garmin source itself was just synced is answered by the refresh step that runs
immediately before generate, not by a field inside the context Claude reads afterward. The prompt's
own freshness guidance (`ClaudeCoachPromptBuilder`) tells Claude exactly this: ordinarily it may
read an activity gap as a genuine rest streak (normal operation always refreshes first), but if it
is ever told the refresh was skipped or failed, it should not draw a strong conclusion from the gap.

## CLI integration

```text
running-ai-coach.ps1 generate path:
  health -> coach data refresh -> freshness report -> generate

running-ai-coach.ps1 -DraftId <id> (resume):
  health -> GET draft                      (no refresh: an existing draft's context snapshot
                                             is immutable - see docs/architecture/
                                             training-context-v2.md - refreshing the DB now
                                             would not change what that draft already shows)

revision:
  no implicit external refresh either - a revision keeps the same immutable evidence the
  original draft was built from; generate a new draft if you want fresh evidence
```

A `NOT_READY` result stops the CLI before any draft is generated - zero `POST /workout-drafts`
calls, so zero Claude calls. `-SkipRefresh` is a debug-only escape hatch that generates from stored
data anyway, with a loud warning; it does not touch the approve/publish human gates in any way, and
adding it did not require touching `Invoke-RunningAiControlledPublish` at all.

## What this phase does not add

No new database migration (freshness is computed from timestamps/state that already exist:
`activity`, `activity_detail_collection`, `activity_analysis`, `intervals_fitness_daily`,
`garmin_recovery_daily`). No scheduler by default (`running-ai.coach-refresh.scheduler.enabled`
defaults `false`, same pattern as `GarminSyncScheduler`). No change to historical backfill's role
(`docs/architecture/historical-backfill.md` - that pipeline is 90-day reconstruction and
completeness verification; this one is "is the last few days current," a much narrower and far
more frequent operation). No cycling-specific running analysis: `RunningActivityAnalysisService`
still only accepts RUN/TREADMILL_RUN, and this pipeline never forces any other type through it.
