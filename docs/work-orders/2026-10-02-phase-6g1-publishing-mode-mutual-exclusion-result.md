# Phase 6G.1 — Publishing Mode Mutual Exclusion Guard — Result

Instruction (verbatim): `2026-10-02-phase-6g1-publishing-mode-mutual-exclusion-instruction.md`.

**External workout writes during Phase 6G.1: 0.** Real Intervals publish: NOT_RUN. Real Garmin write: NOT_RUN.

## Baseline

`main` = `origin/main` = `61260f0` (Phase 6G), working tree clean, 851 tests.

## Implementation

`server/src/main/kotlin/com/runningai/draftpublish/PublishingModeGuard.kt` — a standalone `@Component` that depends only
on the two bound property beans (`WorkoutPublishProperties`, `DraftPublishingProperties`) and checks them in its
constructor (`init`). Rule: **legacy publishing XOR AI draft publishing** (at most one on).

Fail-fast: the guard is an eager singleton, so with both switches true its creation throws
`IllegalStateException("Legacy workout publishing and AI draft publishing cannot be enabled at the same time
(WORKOUT_PUBLISHING_ENABLED and RUNNING_AI_DRAFT_PUBLISHING_ENABLED are both true); enable at most one")` and the
application context does not start, before any request can be served. The decision itself is a pure
`PublishingModeGuard.check(legacyEnabled, draftEnabled)`; unit tests that build no Spring context are unaffected.

Placed in `draftpublish` (the package that introduced the second path; it may already import `integration.intervals`).
Nothing else changed: no publisher, service, scheduler, MCP, Claude, Garmin or draft-lifecycle code; no default changed.
Docs: `CLAUDE.md` (rule added), `application.yml` comment, `.env.example` comment.

## Switch combinations

| `WORKOUT_PUBLISHING_ENABLED` | `RUNNING_AI_DRAFT_PUBLISHING_ENABLED` | Result |
|---|---|---|
| false | false | starts (default) |
| true | false | starts |
| false | true | starts |
| true | true | **startup fails** with the message above |

## Tests

New `PublishingModeGuardTest` (7): pure check of all four combinations; `ApplicationContextRunner` with only the two
property classes + guard for all four combinations (both-on: context failed, root cause is the exact message); defaults
with nothing configured (draft off, legacy off, legacy scheduler off); and the real `RunningAiApplication` (test profile,
no web server) started with the real env-variable names `WORKOUT_PUBLISHING_ENABLED=true` and
`RUNNING_AI_DRAFT_PUBLISHING_ENABLED=true` → refuses to start with the message.

Existing suites keep covering the single-switch contexts end to end: `WorkoutPublishApiTest` / `WorkoutDraftApiTest`
(legacy on), `DraftPublishApiTest` (draft on), `DraftPublishSwitchOffApiTest` / `WorkoutPublishingSchedulerDisabledTest`
(defaults off). No existing test enabled both switches.

| Run | Result |
|---|---|
| `gradlew clean test` (JDK 21, H2) | **858 passed, 0 failed, 0 skipped** (851 → 858, +7) |
| Python connector | not run — unchanged (88 at Phase 6G) |
| Migration | none |

## Defaults

`RUNNING_AI_DRAFT_PUBLISHING_ENABLED` = false, `WORKOUT_PUBLISHING_ENABLED` = false,
`WORKOUT_PUBLISHING_SCHEDULER_ENABLED` = false, `RUNNINGAI_MCP_ENABLED` = false.

## Git

Commits on top of `61260f0`: `feat: guard publishing modes against simultaneous enablement`, then
`docs: record Phase 6G.1 result` (SHAs in the final report). Plain fast-forward `git push origin main`, no force.

## Limitations

The guard covers the two switches inside one application instance. It cannot see the legacy PowerShell writer on the
main PC (outside this repo); disabling that remains an owner action.
