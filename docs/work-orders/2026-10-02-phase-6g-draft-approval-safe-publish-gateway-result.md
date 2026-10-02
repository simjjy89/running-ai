# Phase 6G — Draft Approval & Safe Publish Gateway — Result

Instruction (verbatim): `2026-10-02-phase-6g-draft-approval-safe-publish-gateway-instruction.md`.

**External workout writes during Phase 6G implementation: 0**

- Real Intervals.icu publish: **NOT_RUN** (every publish path was exercised with a mocked `IntervalsWorkoutPublisher` /
  `IntervalsWorkoutClient`; the Intervals base URL in tests points at a closed local port and the API key is blank).
- Real Garmin write: **NOT_RUN** (no Garmin write path exists or was added).
- Phase 6F live validation: external PC `BLOCKED_BY_CORPORATE_TLS`, main PC **still PENDING**.

## 1. Preflight / baseline

- Repository root `C:\running-ai`, branch `main`, working tree clean.
- `83ca3fe`, `488f108`, `049cb36` all ancestors of HEAD; `origin/main` = `049cb36` before the work.
- Baseline: Spring/Kotlin 782 passed, Python 88 passed, live coach eval 20/20.

## 2. Changed files

Main:

| File | Change |
|---|---|
| `db/migration/V9__create_workout_draft_approval.sql` | new: approval audit table |
| `db/migration/V10__create_workout_draft_publication.sql` | new: publication audit table |
| `coach/WorkoutDraft.kt` | `WorkoutDraftStatus.APPROVED` |
| `coach/WorkoutDraftEntity.kt` | `transitionStatus` compare-and-set update |
| `coach/WorkoutDraftService.kt` | CAS supersede, APPROVED revision refused, `WorkoutDraftApprovedImmutableException` |
| `coach/WorkoutDraftApproval.kt` | new: approval entity/repository/domain, `WorkoutDraftApprovalService` |
| `coach/WorkoutDraftController.kt` | `POST /{id}/approve`, `WorkoutDraftApprovalResponse`, two new error codes |
| `draftpublish/DraftPublishingProperties.kt` | new switch `running-ai.draft-publishing.enabled` |
| `draftpublish/WorkoutDraftStructuredWorkoutMapper.kt` | new: WorkoutDraft → StructuredWorkout bridge |
| `draftpublish/ApprovedWorkoutDraftPublishabilityValidator.kt` | new: fail-closed publishability checks |
| `draftpublish/WorkoutDraftPublication.kt` | new: publication entity/repository/store |
| `draftpublish/ApprovedWorkoutDraftPublishService.kt` | new: the gateway (preview + publish) |
| `draftpublish/DraftPublishController.kt` | new: `GET /{id}/publish-preview`, `POST /{id}/publish`, DTOs, error mapping |
| `application.yml`, `.env.example` | `RUNNING_AI_DRAFT_PUBLISHING_ENABLED=false` |
| `CLAUDE.md` | Phase 6G lifecycle, 6F live-validation status |

Tests: new `WorkoutDraftApprovalTest`, `WorkoutDraftStructuredWorkoutMapperTest`, `DraftPublishApiTest`,
`DraftPublishSwitchOffApiTest`, `DraftPublishArchitectureTest`; updated `SchemaMigrationTest` (V1–V10, two new tables),
`CoachArchitectureTest` (approval service in the scanned beans, `draftpublish` import/dependency forbidden),
`WorkoutDraftApiTest`.

`WorkoutDraftApiTest` note: the Phase 6E test "there is no approve or publish endpoint on the draft API" asserted the
old contract that this phase explicitly changes. It was replaced in place by "approving never publishes and publishing
stays behind its own switch" (legacy publish switch ON, approve → 200 APPROVED, publish → 409
`DRAFT_PUBLISHING_DISABLED`, publisher/legacy service/client still verified untouched).

## 3. New DB migrations

- **V9 `workout_draft_approval`**: `id`, `draft_id` (FK, `UNIQUE`), `athlete_id` (FK), `workout_date`, `approved_at`;
  `UNIQUE (athlete_id, workout_date)`.
- **V10 `workout_draft_publication`**: `id`, `draft_id` (FK, `UNIQUE`), `approval_id` (FK), `outcome`,
  `intervals_operation`, `remote_event_id`, `verified`, `published_at`; check constraint: `SKIPPED_REST_DAY` ⇒ operation,
  remote id and verified all NULL; `PUBLISHED` ⇒ operation ∈ {CREATED, UPDATED, NO_CHANGE}, remote id and verified NOT NULL.
- No applied migration was edited. `workout_draft.status` has no check constraint, so `APPROVED` needed no schema change.

## 4. Approval lifecycle

`DRAFT → SUPERSEDED` (revision) or `DRAFT → APPROVED` (explicit approval). Publish state is **not** a draft status.

- Only a current `DRAFT` can be approved. `SUPERSEDED` → 409 `WORKOUT_DRAFT_SUPERSEDED`; unknown → 404.
- Another approved draft for the same athlete+date → 409 `WORKOUT_DATE_ALREADY_APPROVED` (service check, plus the unique
  constraint as the last line; a lost race rolls the whole transaction back).
- Re-approving the same draft returns the existing approval (same `approvalId`/`approvedAt`, no new row).
- Status change and approval row are one transaction. The status change is a compare-and-set
  (`update ... where id = ? and status = 'DRAFT'`), so a concurrent approve/revise of one draft has exactly one winner.
- `WorkoutDraftApprovalService` depends only on coach repositories/store (architecture-tested): approval can never write externally.

## 5. Approval API example

`POST /api/v1/workout-drafts/7/approve` → 200

```json
{"draftId":7,"draftGroupId":"3f1c…","version":1,"date":"2026-10-02","workoutType":"EASY",
 "status":"APPROVED","approvalId":4,"approvedAt":"2026-10-02T03:12:45.123456Z"}
```

## 6. Publish preview example

`GET /api/v1/workout-drafts/7/publish-preview` (works with the switch OFF; APPROVED only, else 409
`WORKOUT_DRAFT_APPROVAL_REQUIRED` / `WORKOUT_DRAFT_SUPERSEDED`) → 200

```json
{"draftId":7,"approvalId":4,"date":"2026-10-02","workoutType":"EASY","restDay":false,"publishable":true,
 "externalWriteRequired":true,"expectedOutcome":"PUBLISH",
 "renderedWorkoutText":"- Warm Up 10m\n- Main 25m\n- Cool Down 5m","structuredStepCount":3,
 "unpublishableReasons":[],"publication":null}
```

An unpublishable approved draft previews with `publishable:false`, `expectedOutcome:"UNPUBLISHABLE"`,
`renderedWorkoutText:null` and the reasons.

## 7. REST approval / publish example

Approve as any draft. Preview:

```json
{"restDay":true,"publishable":true,"externalWriteRequired":false,"expectedOutcome":"SKIPPED_REST_DAY",
 "renderedWorkoutText":"REST DAY - approved rest; no workout is sent to Intervals.icu or Garmin","structuredStepCount":0}
```

`POST /{id}/publish` (switch ON) → `{"outcome":"SKIPPED_REST_DAY","intervalsOperation":null,"remoteEventId":null,
"verified":null,"alreadyPublished":false,…}`. The REST branch returns before the renderer: renderer, publisher and
client interactions are verified to be zero. A second request returns the stored row with `alreadyPublished:true`.
With the switch OFF even a REST publish is refused (409 `DRAFT_PUBLISHING_DISABLED`) and nothing is recorded.

## 8. Approved workout publish flow

`ApprovedWorkoutDraftPublishService.publish(id)`: switch check → per-draft single-flight → load APPROVED draft + approval
→ stored publication? return it → REST? record `SKIPPED_REST_DAY` → publishability check → mapper → mapped-duration check →
existing `IntervalsWorkoutRenderer` → non-empty check → existing `IntervalsWorkoutPublisher.publish(draft.date, rendered)`
→ record `PUBLISHED` with the `IntervalsPublishResult`. The service is not transactional; reads/writes go through
`WorkoutDraftApprovalService` / `WorkoutDraftPublicationStore` (separate beans, so no self-invocation and no transaction
open during the HTTP calls). It never references `WorkoutPublishApplicationService`, `WorkoutIntensityTargetService`,
`StructuredWorkoutMapper` or `IntervalsWorkoutClient` (source scan + constructor check; in the HTTP test those legacy
beans are mocks verified untouched after every test).

## 9. WorkoutDraft → StructuredWorkout mapping policy

Transport only: every duration, pace, speed and incline copied exactly, segments in order, nothing derived from the
athlete profile. `intent` is a label (EASY/RECOVERY/LONG → same; THRESHOLD/INTERVAL/TEMPO → QUALITY); the renderer
uses it only to detect REST. Primary target: PACE when the segment has a pace range, NONE for a `REST` segment,
otherwise QUALITATIVE. Heart-rate target on a step is always null (see 11).

## 10. Repetitions

`repetitions = r, durationMinutes = d, recoveryDurationMinutes = c` → r × (work step `d` + recovery step `c`), the
recovery after the last repetition included (exactly `effectiveDurationMinutes`); `c` = 0/null → work steps only.
Recovery steps: `SegmentType.REST` (rendered "Rest"), no target, no intensity invented. After mapping, the step sum must
equal `totalDurationMinutes` or the publish fails closed before any external call. Example 10 + 5×(3+2) + 10 = 45 → 12 steps:
`- Main 3m 4:30-4:45/km Pace` / `- Rest 2m` ×5.

## 11. Pace / HR / treadmill target mapping

- **Pace**: `paceSecondsPerKmFast/Slow` → `PaceTarget(fast, slow)` unchanged; rendered `M:SS-M:SS/km Pace` (single value
  when equal). Only one bound present → unpublishable.
- **Treadmill**: speed min/max + incline min/max → `TreadmillTarget` unchanged, rendered as the existing Garmin-safe cue
  before the duration (`- Main 10.6kph Incline0.5-1.5pct 30m 5:40/km Pace`). Incline-only is fine (`Incline2pct`).
  Speed without incline → unpublishable (the cue type requires an incline; none is invented). Negative incline →
  unpublishable (the formatter rejects it). Half ranges → unpublishable.
- **Heart rate — option B (fail closed)**: the draft holds absolute bpm; the renderer's only HR form is whole-percent
  `%LTHR` (an absolute-bpm Intervals token has no verified evidence). Converting would round the approved values and
  depend on today's LTHR instead of what was approved, and with a pace present only the pace would be rendered. So any
  segment with a bpm target is `UNPUBLISHABLE_DRAFT`; the target is never dropped, converted or re-chosen. No domain
  change was needed.

## 12. Unpublishable draft policy

`UNPUBLISHABLE_DRAFT` (422, reasons in `errors[]`), checked before the publisher: bpm HR target; CROSS_TRAINING (the
publisher always creates a *Run* event) or unknown workout type; half pace/speed/incline range; speed without incline;
negative incline; MODERATE/HARD segment with neither pace nor treadmill speed (the renderer prints no intensity label,
so it would reach the watch as an unlabelled duration); non-positive durations; mapped-sum ≠ total; empty rendered text.
The draft stays APPROVED (approval and publishability are separate).

## 13. Same-date double approval

Prevented at approval time (409 `WORKOUT_DATE_ALREADY_APPROVED`) and by `uk_workout_draft_approval_athlete_date`.
Tested via service, HTTP and a direct JDBC insert.

## 14. Revision after approval

`POST /{id}/revisions` on an APPROVED draft → 409 `WORKOUT_DRAFT_APPROVED_IMMUTABLE` before the coach is called. A
revision that loaded the draft *before* it was approved is also refused when it tries to supersede it (CAS), leaving
the approved row unchanged and no v2 inserted. Revoke/cancel of an approval is not implemented (out of scope).

## 15. Publish idempotency

A stored publication is returned with `alreadyPublished:true` and no publisher call (tested: publisher called once
across two requests; REST likewise). `uk_workout_draft_publication_draft` is the DB backstop (tested via JDBC).

## 16. Concurrent publish

In-JVM single-flight keyed by draft id (single-instance deployment). Tested with a blocking mocked publisher: a second
publish of the same draft (service and HTTP) → `DRAFT_PUBLISH_ALREADY_RUNNING` (409) while a different draft publishes
normally; the publisher ran exactly once; the guard is released afterwards.

## 17. Publisher failure / retry

Publisher exception (e.g. TIMEOUT → 504 `INTERVALS_TIMEOUT`, UNMANAGED_WORKOUT_CONFLICT → 409) → no publication row,
draft stays APPROVED, draft content untouched. An explicit retry calls the publisher again, whose own marker lookup
decides CREATED / NO_CHANGE remotely (unknown-outcome recovery unchanged). Tested.

## 18. Publication audit

One row per successfully handled draft: outcome, Intervals operation (CREATED / UPDATED / NO_CHANGE stored exactly as
reported — each tested), remote event id, verified, approval id, timestamp. REST rows carry no remote data (check
constraint, tested).

## 19. Switches

- `RUNNING_AI_DRAFT_PUBLISHING_ENABLED` / `running-ai.draft-publishing.enabled` = **false** (tested). Off: generate,
  revise, approve, preview work; publish → 409 `DRAFT_PUBLISHING_DISABLED`.
- `WORKOUT_PUBLISHING_ENABLED` (legacy) unchanged, false; neither switch implies the other.
- Both paths share the Intervals per-date marker (`runningai:workout:v1:<athlete>:<date>`), so they must never both be
  enabled for the same calendar.

## 20. Scheduler / MCP

`WorkoutPublishingScheduler` untouched, default off (tested: no bean). No new scheduler, startup hook or MCP tool;
`draftpublish` contains no `@Scheduled`/runner/`@Tool` (architecture test). MCP sources do not reference drafts,
approval or the gateway. The Claude CLI still runs with `--tools ""`, `--strict-mcp-config`, `--setting-sources ""`
(asserted). The coach/draft/approval code has no route to `draftpublish` or the Intervals renderer/publisher.

## 21–23. Test results

| Run | Result |
|---|---|
| Spring/Kotlin `gradlew clean test` (JDK 21, H2) | **851 passed, 0 failed, 0 skipped** (782 → 851, +69) |
| Python connector `pytest` (code untouched) | **88 passed** |
| Real PostgreSQL 17.11 — upgrade | existing V1–V8 DB with a pre-existing synthetic DRAFT row upgraded to V10: `success = t` for 9 and 10, row intact, constraints as in §3 |
| Real PostgreSQL 17.11 — subset | `SchemaMigrationTest`, `draftpublish.*`, `WorkoutDraftApprovalTest`, `WorkoutDraftPersistenceTest`, `WorkoutDraftApiTest`, `integration.intervals.*` (legacy publish regression): **235 passed, 0 failed** |
| Live Claude eval | **not re-run**: the Claude prompt, response contract and WorkoutDraft shape were not changed |

The first PG subset run hit "too many connections" (several cached Spring contexts × default Hikari pool of 10 against
the scratch server's `max_connections`); rerun with `spring.datasource.hikari.maximum-pool-size=3` — a test-environment
limit, no code change.

## 24–27. External effects

- Actual external workout writes: **0**. Real Intervals publish: **NOT_RUN**. Real Garmin write: **NOT_RUN**.
- Phase 6F live validation: still **PENDING** on the main PC.

## 28–30. Git

Commits on `main` (on top of `049cb36`):

- `67652ab` feat: add workout draft approval lifecycle
- `2a10da9` feat: add approved draft safe publish gateway
- `a62ce2b` test: verify approved draft publishing safety
- docs commit containing this file (SHA in the final report)

Push: `origin/main` was still `049cb36` (fast-forward) before pushing; pushed with a plain `git push origin main` (no force).

## Limitations / next steps (not started)

- HR-targeted AI drafts cannot be published until a bpm Intervals token is device-verified or the coach contract gains
  an explicit %LTHR target.
- No approval revoke/cancel; a changed plan for an approved date currently has no path (needs a revoke phase).
- Single-flight is per JVM only (single-instance deployment assumed).
- Main-PC steps before any real publish: disable the legacy writer, keep `WORKOUT_PUBLISHING_ENABLED=false`, run a
  manual approve → preview → publish smoke test on one date, check the Garmin 265 display.
