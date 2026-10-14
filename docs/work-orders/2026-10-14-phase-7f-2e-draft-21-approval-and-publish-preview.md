# Phase 7F-2E — Draft #21 approval and publish preview

Date: 2026-10-14 (KST) · Branch `main-3` · HEAD `30113c2` · **no code change, no commit, no push**

## Outcome in one line

Draft 21 was approved through the real `WorkoutDraftApprovalService` (approval **id 5**), the
publish preview returned `publishable=true` / `expectedOutcome=PUBLISH` with the exact three-step
Workout Builder text, and the read-only preflight re-confirmed **0 events** on 2026-10-16 →
predicted operation **CREATED** — with **zero Intervals writes**, **zero production changes**, and
the dev instance stopped again. The system is now exactly one explicit command short of the first
real `CREATED`.

## A — STEP 1: dev environment

Started with the existing script, unchanged:

```
scripts\dev\start-dev-spring.ps1 -Port 18083 -Database running_ai_7f2 -IntervalsLive -DraftPublishLive
```

PID **40844**, healthy after 6 s. Verified from the live process command line, not from intent:

| property | observed | required |
|---|---|---|
| datasource | `jdbc:postgresql://127.0.0.1:5432/running_ai_7f2` (Flyway: schema version **26**) | `running_ai_7f2` OK |
| production DB | `running_ai`, a different database on the same cluster; never opened by this instance | separate OK |
| `running-ai.workout-publishing.enabled` | **false** | legacy publishing off OK |
| `running-ai.workout-publishing.scheduler.enabled` | **false** | off OK |
| `running-ai.garmin.scheduler.enabled` | **false** | off OK |
| `running-ai.garmin.profile-sync.scheduler.enabled` | **false** | off OK |
| `running-ai.coach.refresh.scheduler.enabled` | **false** | off OK |
| `running-ai.garmin.connector.base-url` | `http://127.0.0.1:9` (dead port) | Garmin traffic structurally 0 OK |
| `running-ai.draft-publishing.enabled` | **true** | needed later; approve/preview do not use it |
| `running-ai.mcp.enabled` / `read-enabled` | false / true → **7 tools registered** (read group only) | no write tool exposed OK |
| `INTERVALS_ATHLETE_ID` | unset in process, User and Machine scope → effective **`0`** (`application.yml:123`) | "0" kept OK |

Legacy XOR draft publishing guard (`PublishingModeGuard`) was satisfied — the instance started.

Production, read-only, untouched throughout: Spring 18080 PID **41436** `{"status":"UP"}`, gateway
17910 PID **48104** listening. Neither was restarted; both PIDs are identical before and after.

## B — STEP 2: draft 21 as read before approval

`GET /api/v1/workout-drafts/21` on 18083, cross-checked against the raw row in `running_ai_7f2`.

| field | expected | observed |
|---|---|---|
| id | 21 | **21** OK |
| version | 1 | **1** OK |
| status | DRAFT | **DRAFT** OK |
| date | 2026-10-16 | **2026-10-16** OK |
| title | Short Easy Recovery Jog | **Short Easy Recovery Jog** OK |
| workoutType | RECOVERY | **RECOVERY** OK |
| totalDurationMinutes | 30 | **30** OK |

| # | segment | duration | intensity | target |
|---|---|---|---|---|
| 1 | WARM_UP | 5 min | VERY_EASY | **60-68 % LTHR** OK |
| 2 | MAIN | 20 min | VERY_EASY | **65-74 % LTHR** OK |
| 3 | COOL_DOWN | 5 min | VERY_EASY | **58-65 % LTHR** OK |

Segment sum 5 + 20 + 5 = **30** = the declared total. No pace, no absolute-bpm, no treadmill
target on any segment — `primaryTargetType=HEART_RATE` with %LTHR only, on all three.

Other preconditions, all checked before touching anything:

- `draft_group_id=adcb0bcc-...` contains **exactly one** row (version 1). No revision exists, and none was created.
- Draft 21 was **not** already approved; `workout_draft_approval` held 4 rows (dates 10-03, 10-04, 10-05, 10-15) and **none for 2026-10-16**.
- `workout_draft_publication` held 3 rows (drafts 7 / 10 / 17); **nothing for draft 21**.
- Approval immutability is a real domain rule, not an assumption: `WorkoutDraftApprovalService` refuses a `SUPERSEDED` draft, returns the existing approval for an already-`APPROVED` one, and a revision of an approved draft raises `WORKOUT_DRAFT_APPROVED_IMMUTABLE`. One-approval-per-date is enforced twice — a service check *and* `uk_workout_draft_approval_athlete_date`.

## C — STEP 3: injury gate re-evaluation at approval time

Taken from the real policy, `GET /api/v1/athlete/injury-state` → `WorkoutSafetyGateService` →
`WorkoutSafetyPolicy`. The DB `RESOLVED` string alone was **not** treated as proof.

| question | answer |
|---|---|
| latest report | **id 3**, `RESOLVED`, knee (lateral) |
| `recordedAt` | 2026-10-13T22:54:24.101553Z (= 2026-10-14 **07:54:24 KST**) |
| `evaluatedAt` | 2026-10-13T23:18:40.068Z (= 08:18:40 KST) → **age about 24 min** |
| freshness window | 48 h (`running-ai.injury-safety.staleness-hours`, default 48) → valid until **2026-10-15T22:54:24Z = 2026-10-16 07:54:24 KST** |
| `INJURY_STATE_STALE` | **did not fire** |
| `nonRestTrainingAllowed` | **true** |
| reasonCode | **`INJURY_RESOLVED_CONFIRMED`** |
| RECOVERY allowed | **yes** — the policy's only question is "is this REST?"; with the gate open every non-REST type passes alike |
| other blocking condition | none; no second safety rule exists on this path |

The server's own wording is repeated here unchanged: this is **a data permission, not a medical
clearance**. Reports #1 (ACTIVE) and #2 (UNKNOWN) were read, never modified; no report was added,
no timestamp was touched, and the window was not extended by any means.

## D — STEP 4: the approval

`POST /api/v1/workout-drafts/21/approve` → **HTTP 200**.

```json
{"draftId":21,"draftGroupId":"adcb0bcc-2e6a-439e-b56e-cfd01d3fb91d","version":1,
 "date":"2026-10-16","workoutType":"RECOVERY","status":"APPROVED",
 "approvalId":5,"approvedAt":"2026-10-13T23:19:00.366827Z"}
```

Approval **id 5**, at 2026-10-13T23:19:00.366827Z = **2026-10-14 08:19:00 KST**.

Read back from `running_ai_7f2` (not from the HTTP answer):

| check | result |
|---|---|
| `workout_draft_approval` id | **5** |
| draft_id / athlete_id / workout_date | 21 / 1 / **2026-10-16** |
| draft 21 status | **APPROVED**, version still **1** |
| approvals for 2026-10-16 | **exactly 1** |
| approvals per date overall | 10-03:1, 10-04:1, 10-05:1, 10-15:1, 10-16:1 — no date has two |
| publication row for draft 21 | **none**; `workout_draft_publication` still **3 rows**, unchanged |
| external publish record | **none** |

**Duplicate-approval guard, actually exercised:** `POST .../21/approve` a second time returned the
*same* approval (`approvalId: 5`, same `approvedAt`), the row count stayed 5 and no second log line
appeared — idempotent by the service's own `existing(draft)` branch, not by luck.

Nothing was written by SQL. The only DB statements issued by this phase outside the service were
`SELECT`s. The service log line is the single authority for the write:

```
08:19:00.405 WorkoutDraftApprovalService : Workout draft approved: id=21
  group=adcb0bcc-2e6a-439e-b56e-cfd01d3fb91d version=1 date=2026-10-16 type=RECOVERY
```

### A contract note worth recording

The instruction asked for `acknowledgedVersion: 1` and `confirmedByAthlete: true`. Those fields
belong to the **Phase 7F-1 MCP adapter** (`approve_workout_draft`), where they exist precisely
because an MCP call is not the athlete's approval. The **REST** endpoint
`POST /api/v1/workout-drafts/{id}/approve` takes **no request body at all** — the athlete's
approval is the request itself, and the version echo is performed by the operator reading the
draft first. That is what happened here: version 1 was verified read-only in STEP 2 and again in
the approval response, which carries `version: 1`. No DTO was invented and no flag was written
directly. The MCP train tools were **off** on this instance (7 read tools registered, not 12).

## E — STEP 5: publish preview

`GET /api/v1/workout-drafts/21/publish-preview` → **HTTP 200**.

| field | value |
|---|---|
| `draftId` | **21** |
| `approvalId` | **5** — matches STEP 4 |
| `date` | **2026-10-16** |
| `workoutType` | **RECOVERY** |
| `restDay` | **false** |
| `publishable` | **true** |
| `externalWriteRequired` | **true** |
| `expectedOutcome` | **`PUBLISH`** |
| `structuredStepCount` | **3** |
| `unpublishableReasons` | **`[]`** |
| `publication` | **null** (never published) |

**The complete text that a publish would send**, byte for byte, nothing appended:

```
- Warm Up 5m 60-68% LTHR hr=1s
- Main 20m 65-74% LTHR hr=1s
- Cool Down 5m 58-65% LTHR hr=1s
```

3 steps, 5 + 20 + 5 = **30 minutes**, matching the approved draft exactly. This confirms the
prediction 7F-2D derived from the validator by hand.

Note on vocabulary: `expectedOutcome` is `PUBLISH | SKIPPED_REST_DAY | UNPUBLISHABLE` — it answers
*whether* a publish would happen. **CREATED / UPDATED is the Intervals operation**, which the
publisher decides from the calendar at publish time; that is what the preflight below predicts.

**The preview made zero Intervals calls.** Verified, not assumed: the preview path is
`loadApproved` → safety gate → mapper → renderer, with no client dependency (`DraftPublishController`
documents it, and `ApprovedWorkoutDraftPublishService.preview` has no publisher call), and the dev
log shows **no `Intervals` line at all** after the instance started at 08:18. There is no write path
in the preview to decline to take.

## F — STEP 6: external calendar preflight (re-run, not reused)

`scripts\dev\preflight-intervals-date.ps1 -Date 2026-10-16 -AthleteId 0`, executed **now** — the
0-event result from 7F-2D was deliberately not carried over.

```
[preflight] date   : 2026-10-16
[preflight] marker : runningai:workout:v1:0:2026-10-16
[preflight] account: i6***48 (tz Asia/Seoul) - resolved from the API key, athlete-id=0
[preflight] WORKOUT events on 2026-10-16 : 0  (owned=0 foreign=0)
[preflight] predicted publish operation: CREATED - the date is empty, one new event would be POSTed
[preflight] no write was performed by this script.
exit 0
```

| item | result |
|---|---|
| target account | **i6\*\*\*48**, timezone **Asia/Seoul** — matches the intended real account |
| athlete-id | **0** (unchanged; changing it would orphan every marker already on the calendar) |
| RunningAI marker | `runningai:workout:v1:0:2026-10-16` — **not present** |
| existing WORKOUT events | **0** (owned 0, foreign 0) |
| conflict | **none** |
| predicted operation | **CREATED** |
| risk of modifying an existing event | **none** — there is no event to modify |

Two GETs only; the script has no write path. **This reading expires.** The calendar can change at
any time, so the preflight **must be re-run immediately before the actual publish**, and its result
re-read then — a 0-event answer from today is not evidence about tomorrow.

## G — STEP 7: post-approval integrity

| check | result |
|---|---|
| draft 21 content | **unchanged** — segments JSON identical to the pre-approval dump, title/type/duration/group/`context_sha256` all identical |
| draft 21 version | **1**, still the only version in its group |
| draft 21 `updated_at` | moved 22:55:42 → 23:19:00 — the status transition's own write, the single expected change |
| approvals for draft 21 | **exactly 1** (id 5) |
| approvals for 2026-10-16 | **exactly 1**; no date carries two |
| new publication rows | **0** — still 3 (drafts 7/10/17), untouched |
| external publish status | **unchanged** |
| draft 20 / approval 4 (2026-10-15 REST) | **preserved**, `updated_at` still 2026-10-13 21:52:39 |
| injury reports #1 #2 #3 | **preserved** — ids, statuses, both timestamps and `notes` MD5 all identical |
| dev row totals | drafts 21 (unchanged), approvals 4 → **5**, publications 3 (unchanged), injury reports 3 (unchanged) |
| **production `running_ai`** | drafts **19**, approvals **3**, publications **3**, injury reports **0**, latest approval still 2026-10-04 — **identical before and after; 0 changes** |

Total dev-DB change for this phase: **1 row inserted** (`workout_draft_approval` id 5) and **1 status
column updated** (draft 21 DRAFT → APPROVED). Nothing else in any database changed.

## H — STEP 8: shutdown

`start-dev-spring.ps1 -Port 18083 -Database running_ai_7f2 -Stop`

| check | result |
|---|---|
| dev Spring 18083 (PID 40844) | **stopped** |
| scheduled task `RunningAI-Dev-Spring-18083` | **deleted** (`schtasks /Query` → not found) |
| port 18083 | **no listener**; `GET /api/v1/health` → connection refused |
| production Spring 18080 | **UP**, PID **41436** — same PID as at session start, never restarted |
| gateway 17910 | listening, PID **48104** — same PID as at session start, never restarted |
| watchface / relay | untouched — nothing in this phase addressed them, and the only processes acted on were the dev JVM and its task |

## I — Prohibited actions: none performed

No Intervals `POST`/`PUT`/`PATCH`/`DELETE`, no publish call, no auto-publish enablement. No
production DB write, no production Spring or gateway restart, no OAuth scope change, no Cloudflare
Worker deploy, no MCP write tool exposed. No revision of draft 21, no change of approval target, no
new or edited injury report, no gate bypass, no deleted approval, no SQL approval shortcut. No merge
into canonical `main`, no `origin` push, no change to `C:\running-ai`.

## J — State at hand-off

| item | value |
|---|---|
| git HEAD | `30113c2`, branch `main-3`, **no commit, no push** |
| files added | this work order + the verbatim instruction; **no source, schema, config or script change** |
| tests | not run — no code was modified |
| dev DB changes | **1 insert + 1 status update** |
| production DB changes | **0** |
| real Intervals writes | **0** (only 2 preflight GETs; the application itself made no Intervals call at all) |
| dev Spring 18083 | **stopped** |

### Completion criteria

| # | criterion | result |
|---|---|---|
| 1 | Draft 21 version 1 confirmed exactly | **PASS** |
| 2 | Injury gate current and valid | **PASS** (`INJURY_RESOLVED_CONFIRMED`, age about 24 min) |
| 3 | Approved through the existing service | **PASS** |
| 4 | Approval id confirmed | **PASS** (id 5) |
| 5 | Publish preview normal | **PASS** (`publishable=true`, 3 steps, 30 min) |
| 6 | No calendar conflict | **PASS** (0 events, CREATED) |
| 7 | Production DB unchanged | **PASS** |
| 8 | External Intervals writes = 0 | **PASS** |
| 9 | Dev server stopped | **PASS** |

**Overall: PASS. Stopped here as instructed — no publish was performed.**

## The next step, and what gates it

The next phase is **one real `CREATED` against the Intervals.icu account**, and it needs a separate,
explicit approval from the athlete. It is blocked by nothing technical; the conditions to re-check
at that moment are:

1. **Injury freshness expires 2026-10-16 07:54:24 KST** — the morning of the target date. Publish
   before then, or record a new report first; otherwise the gate answers `INJURY_STATE_STALE` at
   publish time and the publish is refused (correctly).
2. **Re-run the preflight for 2026-10-16 immediately before publishing** — today's 0-event reading
   is not valid later.
3. Restart 18083 with `-IntervalsLive -DraftPublishLive` (the instance is stopped, and
   `RUNNING_AI_DRAFT_PUBLISHING_ENABLED` must be on for the publish to be accepted at all).
4. Then, and only on explicit approval: `POST /api/v1/workout-drafts/21/publish` → expected
   Intervals operation **CREATED**, readback-verified by the publisher; afterwards confirm the event
   in the Intervals UI and the delivery on the watch.

This would be the **first `CREATED` ever performed against the real account** — the three real
publishes on record (2026-10-03/04/05, events 140389091 / 140391756 / 140511724) were all `UPDATED`
over events the legacy publisher had already placed, so `createWithoutBlindRetry` has never run for
real.
