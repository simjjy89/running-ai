# Phase 7F-2D — RESOLVED injury self-report, gate re-evaluation and first-publish readiness

Date: 2026-10-14 (KST) · Branch `main-3` · HEAD `30113c2` · **no code change, no commit, no push**

## Outcome in one line

The athlete's explicit RESOLVED self-report was recorded, the Injury Gate **opened for the first
time** (`INJURY_RESOLVED_CONFIRMED`), the real Claude CLI produced a **non-REST RECOVERY draft**
for a clear date, and everything stopped one approval short of the first real Intervals write —
**zero external writes, zero production changes.**

## A — STEP 1: the new injury report (dev DB `running_ai_7f2` only)

Recorded through the normal API path, `POST /api/v1/athlete/injury-reports` on 18083 — no SQL
INSERT, no entity edit. The ledger is append-only, so this is a third row, not a change to the
first two.

| field | value | why |
|---|---|---|
| `id` | **3** | new row |
| `status` | `RESOLVED` | the athlete's explicit, authorised self-report |
| `body_site` | `knee (lateral)` | identical spelling to rows 1 and 2, so the three rows read as one history |
| `side` | `UNSPECIFIED` | side was never reported |
| `pain_present` | `false` | reported |
| `pain_on_walking` | `false` | reported |
| `swelling` | `false` | reported ("no swelling through the next day") |
| `pain_intensity` | `null` | not reported — never written as `0` |
| `pain_on_stairs` | `null` | not reported |
| `range_of_motion_limited` | `null` | not reported |
| `reported_at` | `2026-10-13T22:54:24.101553Z` | = `2026-10-14 07:54 KST`, the actual report instant |
| `recorded_at` | `2026-10-13T22:54:24.101553Z` | the actual save instant |

Neither timestamp was backdated or pushed forward: the athlete reported *now*, so reported and
recorded coincide. The 48 h freshness window was not extended or altered anywhere.

The three facts the athlete confirmed that have **no column** — no pain *during* the light run, no
pain *immediately after* it, no pain or swelling *the following day* — are recorded in `notes`,
which is the schema's own free-text field. No field was invented and nothing was force-mapped onto
an unrelated column. The exact date, distance and duration of that light run were **not** reported
and are explicitly not estimated.

### Pre-write verification

- `current_database()` = `running_ai_7f2`; the instance's Flyway line reads
  `jdbc:postgresql://127.0.0.1:5432/running_ai_7f2`, and `start-dev-spring.ps1` structurally refuses
  the name `running_ai` and the port 18080.
- Rows 1 (`ACTIVE`) and 2 (`UNKNOWN`) were read before and after: **byte-identical, untouched**.
- No `RESOLVED` row existed for this self-report, so this was not a duplicate run.

### Ledger after the write

```
3 RESOLVED 2026-10-13T22:54:24Z  pain=f walk=f swell=f  int=null stairs=null rom=null
2 UNKNOWN  2026-10-13T21:50:46Z  pain=f walk=f swell=null ...
1 ACTIVE   reported 2026-10-11T00:31:43Z recorded 2026-10-13T21:50:37Z  pain=t walk=t ...
```

Production `running_ai`: `select count(*) from injury_report` = **0**, before and after.

## B — STEP 2: Injury Gate re-evaluation

Run through the real services (`WorkoutSafetyGateService` → `WorkoutSafetyPolicy`), nothing
re-implemented or bypassed.

| question | answer | source |
|---|---|---|
| latest report | id **3**, `RESOLVED` | `findFirstByAthleteIdOrderByRecordedAtDesc` |
| freshness (48 h) | **satisfied**, age ~7 s | `INJURY_STATE_STALE` did not fire |
| `nonRestTrainingAllowed` | **true** | `GET /api/v1/athlete/injury-state` |
| reason code | `INJURY_RESOLVED_CONFIRMED` | policy rule 5 |
| RECOVERY allowed | **yes** | — |
| EASY allowed | **yes** | — |
| QUALITY | **no separate injury rule exists** | see below |

The policy is deliberately binary — `requireAllowed` only ever asks "is this REST?", so once
`nonRestTrainingAllowed` is true every non-REST type passes the *injury* gate alike. A grep of
`coach` and `draftpublish` finds no QUALITY-specific safety rule; `QUALITY` appears only as a
mapper label (`THRESHOLD`/`INTERVAL`/`TEMPO` → `CandidateTrainingType.QUALITY`). The brake on
intensity is therefore the coach's judgement and the athlete's, not a second server rule.

The server's own message says it plainly and is repeated here: this is a **data permission, not a
medical clearance**.

### A finding worth carrying forward

Injury data reaches the coach through exactly one route: `WorkoutDraftService.applySafetyConstraint`,
which prepends `WorkoutSafetyGateService.constraintText(...)` to the prompt. That method returns
`null` when training is unrestricted. So with the gate **open**, the coach is told nothing about the
injury history at all — the TrainingContext (V1 and V2) carries no injury field. Draft 21's choice
of RECOVERY therefore came from activity and recovery data, **not** from knowledge of the knee.
That is the current design, not a defect introduced today, but it means the knee history is not
visible to the coach in the state the system is now in.

## C — STEP 5: the new workout draft (real local Claude CLI)

`POST /api/v1/workout-drafts {"date":"2026-10-16","availableMinutes":30,"requestedGoal":"recovery"}`
→ HTTP 200 in **25.5 s**, provider `CLAUDE`, model `sonnet`. Not mocked, not faked, not forced: no
instruction to avoid REST was given, and a REST answer would have been accepted as correct.

- **Draft `id=21`**, `draftGroupId=adcb0bcc-2e6a-439e-b56e-cfd01d3fb91d`, **version 1**, status `DRAFT`
- Date **2026-10-16**, title **"Short Easy Recovery Jog"**, type **RECOVERY**, total **30 min**

| segment | duration | intensity | target (%LTHR) | cue |
|---|---|---|---|---|
| WARM_UP | 5 min | VERY_EASY | **60–68 %** | very relaxed jog, conversational, let HR settle low |
| MAIN | 20 min | VERY_EASY | **65–74 %** | easy recovery effort, nasal/conversational breathing, no pace pressure |
| COOL_DOWN | 5 min | VERY_EASY | **58–65 %** | slow down further, walk if needed to finish relaxed |

No pace target, no absolute-bpm target, no treadmill speed/incline on any segment — %LTHR only.

**Coach's rationale (verbatim):** "You asked for a recovery session, and that fits: your last run was
a genuinely hard effort and the most recent recovery snapshot (even though a few days old) still
pointed to significant fatigue. This is a short, easy jog to reintroduce movement without adding any
real training stress, keeping effort clearly below aerobic threshold throughout."

**Load assessment:** a very hard 112-minute long run on Oct 11 (92.8 % of time in zone 5, HR/pace
decoupling present) followed by 5 consecutive rest days; CTL ≈ 16.

**Warnings returned by the coach:**

1. Recovery metrics are 4 days old (from 2026-10-12) and may no longer reflect today — judge by feel, stop or shorten if anything feels off.
2. LTHR provenance is unconfirmed (source not tracked), so the %LTHR targets are approximate.

To which this report adds the warning the coach could not give: **the coach was not told about the
lateral knee pain or its resolution** (§B). A 30-minute very-easy jog is consistent with a first
session back, but it was not designed *around* the knee.

## D — STEP 6: preview, and why it was not obtained

`GET /api/v1/workout-drafts/21/publish-preview` was issued (a read-only GET) and answered
**409 `WORKOUT_DRAFT_APPROVAL_REQUIRED`** — "Workout draft 21 is DRAFT; only an APPROVED draft can
be previewed or published". `ApprovedWorkoutDraftPublishService.preview` calls `loadApproved(id)`
before anything else, by design.

So preview is **structurally post-approval**. It was not forced: no approval was created, no draft
status was edited, no service was bypassed. Draft 21 is still `DRAFT`/version 1 and the approval
count is still **4**. Preview stays a post-approval step for the next phase.

### Expected preview result, predicted from the validator (code reading, not a call)

Every `ApprovedWorkoutDraftPublishabilityValidator` rule was checked against draft 21 by hand:
`RECOVERY` maps to a real intent; total 30 > 0; 3 segments, each > 0 min; no bpm target; %LTHR
present as a complete, non-inverted, positive min/max pair on all three segments; no pace and no
treadmill speed, so neither "speed without incline" nor "MODERATE/HARD with no target" can fire
(all three segments are VERY_EASY in any case); mapped steps sum 5 + 20 + 5 = 30 = the approved
total. **Expected `publishable=true`, `unpublishableReasons=[]`, `expectedOutcome=CREATED`.**
This is a prediction derived from the rules, not an observed response.

## E — STEP 7: what a real publish would change

**Target account** `i6***48`, timezone `Asia/Seoul`, `INTERVALS_ATHLETE_ID` unset → effective
`"0"` (unchanged; changing it would orphan every marker already on the calendar).

Read-only preflight, `scripts\dev\preflight-intervals-date.ps1 -Date 2026-10-16 -AthleteId 0`
(one identity GET + one events GET; the script has no write path at all):

- marker: `runningai:workout:v1:0:2026-10-16`
- WORKOUT events on 2026-10-16: **0** (owned 0, foreign 0)
- predicted operation: **`CREATED`** — exit code 0

The event that would be POSTed, from `IntervalsWorkoutPublisher`:

| field | value |
|---|---|
| date | 2026-10-16 |
| name | `RunningAI workout` (fixed constant — **not** the draft title) |
| type | `Run` |
| `external_id` | `runningai:workout:v1:0:2026-10-16` |
| description | the rendered Workout Builder text, byte for byte, nothing appended |

The exact rendered text is only obtainable from the preview, i.e. after approval.

**This would be the first `CREATED` ever performed against the real account.** The three real
publishes on record (2026-10-03, 10-04, 10-05 → events 140389091 / 140391756 / 140511724) were all
`UPDATED` over events the legacy publisher had already placed. The create path — including
`createWithoutBlindRetry` — has never run for real.

### Safety properties that would apply (all already in code and covered by tests)

- **No overwrite of a foreign event.** 1 owned → compare, then UPDATE in place; >1 owned → abort
  `DUPLICATE_OWNED_WORKOUT`; a foreign event with no owned one → abort
  `UNMANAGED_WORKOUT_CONFLICT`, and the foreign event is never modified. Today: 0 of either.
- **Approval immutability**: an approved draft cannot be revised (`WORKOUT_DRAFT_APPROVED_IMMUTABLE`).
- **One approval per athlete+date**: service check *and* the `uk_workout_draft_approval_athlete_date`
  unique constraint. 2026-10-16 is free in `running_ai_7f2` (0 drafts and 0 approvals on/after 10-16).
- **No double publish**: `uk_workout_draft_approval_draft`, plus a stored publication short-circuits —
  a re-publish returns the stored result instead of calling Intervals again.
- **No concurrent publish**: per-draft in-JVM single flight (`DraftPublishAlreadyRunningException`).
- **Never a blind retry after a timeout**: an unknown-outcome POST is *never* re-sent; the publisher
  re-looks-up by marker and adopts what it finds, or fails.
- **Readback verification**: after a create/update the event is re-read and the description compared
  normalised, byte for byte; a mismatch raises instead of reporting success.
- **Recovery**: the created event is identified by its `external_id`; it can be deleted in the
  Intervals UI, or a corrected publish will UPDATE it in place.
- **Garmin delivery**: by the existing Intervals→Garmin calendar sync, confirmed on the watch. This
  repo has no Garmin write path and makes no claim about that leg.
- **Gate re-checked at publish time**, independently of the state at generation and at approval.

**Real Intervals writes performed in this phase: 0.** The only Intervals traffic was the two
preflight GETs; the application log shows no Intervals call at all.

## F — STEP 8: state at hand-off

| item | result |
|---|---|
| dev DB changes | **2 rows**: `injury_report` id 3, `workout_draft` id 21 (plus its context snapshot) |
| production DB changes | **0** (`injury_report` still 0 rows; nothing else touched) |
| production Spring 18080 / gateway 17910 | untouched, never restarted, still listening |
| external Intervals writes | **0** |
| dev Spring 18083 | **stopped**, task deleted, port verified free |
| existing reports #1 / #2 | preserved, unmodified |
| 2026-10-15 approval #4 / draft #20 | preserved, unmodified |
| files changed | this work order + the verbatim instruction. No source, schema or config change |
| tests | not run — no code was modified |
| commit / push | none; tree otherwise clean at `30113c2` |

Not done, as required: production DB write, production restart, Intervals POST/PUT/PATCH/DELETE,
injury-report edit or delete, deletion of the 10-15 approval, approval of draft 21, gate bypass,
Cloudflare Worker deploy, OAuth scope widening, MCP write-tool exposure, watchface/relay change,
canonical `main` merge, `origin` push, legacy-repo change.

## The one remaining blocker

**The athlete's explicit approval of draft 21, and then explicit approval to publish it.** Nothing
technical is in the way: the gate is open, the date is clear, and the draft passes every
publishability rule by inspection.

The sequence once approval is given:

1. restart 18083 (`start-dev-spring.ps1 -Port 18083 -Database running_ai_7f2 -IntervalsLive -DraftPublishLive`)
2. confirm the injury report is still < 48 h old — **it expires 2026-10-16 07:54 KST**, the morning
   of the target date itself. If approval comes later than that, a fresh report is required first,
   or the gate answers `INJURY_STATE_STALE` at publish time.
3. `POST /api/v1/workout-drafts/21/approve`
4. `GET /api/v1/workout-drafts/21/publish-preview` — review the real rendered text
5. re-run the preflight for 2026-10-16 (the calendar can change between now and then)
6. on explicit approval: `POST /api/v1/workout-drafts/21/publish` → expected `CREATED`, verified
7. read back in the Intervals UI, then confirm delivery on the watch
