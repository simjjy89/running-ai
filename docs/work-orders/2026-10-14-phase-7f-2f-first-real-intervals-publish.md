# Phase 7F-2F — the first real Intervals.icu publish (CREATED) and its E2E verification

Date: 2026-10-14 (KST) · Branch `main-3` · HEAD `30113c2` · **no code change, no commit, no push**

## Outcome in one line

**PASS.** Approved draft 21 was published once to the real Intervals.icu account: operation
**`CREATED`**, event **144442046**, `verified=true` — the **first time the create branch has ever
run against the real account**. External readback confirms exactly one WORKOUT on 2026-10-16 with
the approved text and a 3-step / 30-minute structure; a second publish call returned the stored
result with **zero** further external calls. Production unchanged, dev instance stopped.

**Total external writes performed: 1 POST.** Nothing was updated, deleted or taken over.

## A — STEP 1: runtime safety before anything was started

Execution began **2026-10-14 08:55:59 KST**. Injury report #3 freshness: **47.0 h remaining**
(expires 2026-10-16 07:54:24 KST).

| # | check | result |
|---|---|---|
| 1 | dev datasource | `jdbc:postgresql://127.0.0.1:5432/**running_ai_7f2**` (Flyway schema **26**) |
| 2 | not production | production is `running_ai`, a different database; never opened by this instance |
| 3 | bind address | **127.0.0.1:18083** only (`--server.address=127.0.0.1`) |
| 4 | production Spring 18080 / gateway 17910 | **UP** (PID **41436**) / listening (PID **48104**) — read-only checks |
| 5 | Garmin sync + schedulers (dev) | `garmin.scheduler`, `garmin.profile-sync.scheduler`, `coach.refresh.scheduler`, `workout-publishing.scheduler` **all false**; connector at `http://127.0.0.1:9` (dead port) |
| 6 | legacy publishing (dev) | `running-ai.workout-publishing.enabled=**false**` |
| 7 | legacy PowerShell publish tasks | `RunningAI-TodayWorkout`, `-CommandChannel`, `-TrainingCommand`, `-RemoteWakeupScheduler` — **all Disabled** (re-checked after the publish too) |
| 8 | `INTERVALS_ATHLETE_ID` | **not touched**; unset → effective `"0"` |
| 9 | account the key resolves to | **i6\*\*\*48**, tz **Asia/Seoul** — the approved account |
| 10 | competing publisher | **none** — see below |

**Check 10 in detail.** Two other dev instances were already running and were inspected rather than
assumed harmless:

| instance | database | draft-publishing | intervals base-url |
|---|---|---|---|
| 18081 (PID 29024) | `running_ai_7d_smoke` | **false** | `http://127.0.0.1:9` (dead) |
| 18082 (PID 34148) | `running_ai_realcopy` | **false** | `http://127.0.0.1:9` (dead) |

Neither holds draft 21, neither may publish, and neither can reach Intervals at all. Production
(PID 41436) likewise: `.env` has `RUNNING_AI_DRAFT_PUBLISHING_ENABLED=false`,
`WORKOUT_PUBLISHING_ENABLED=false`, `WORKOUT_PUBLISHING_SCHEDULER_ENABLED=false`, and its database
has **0 drafts and 0 approvals** for 2026-10-16 plus **0 injury reports** (so its gate is closed
regardless). No second writer could have raced this publish.

Started with the existing script, unchanged:

```
scripts\dev\start-dev-spring.ps1 -Port 18083 -Database running_ai_7f2 -IntervalsLive -DraftPublishLive
```

PID **47500**, healthy after 6 s, `running-ai.draft-publishing.enabled=true`, no
`--running-ai.intervals.base-url` override (so the real API with the live key). Production was not
restarted or rebuilt.

## B — STEP 2: draft and approval, re-read immediately before publishing

| item | required | observed |
|---|---|---|
| draft id / version / status | 21 / 1 / APPROVED | **21 / 1 / APPROVED** |
| date / type / duration | 2026-10-16 / RECOVERY / 30 min | **identical** |
| title | Short Easy Recovery Jog | **identical** |
| segments | 3, summing to 30 | **3, sum = 30** |
| WARM_UP | 5 min VERY_EASY 60–68 % LTHR | **identical** (no pace, no bpm, no treadmill) |
| MAIN | 20 min VERY_EASY 65–74 % LTHR | **identical** |
| COOL_DOWN | 5 min VERY_EASY 58–65 % LTHR | **identical** |
| approval | id 5, draft 21, 2026-10-16 | **id 5**, approved 2026-10-13T23:19:00.366827Z |
| approvals on 2026-10-16 | exactly 1 | **1** |
| publications for draft 21 | none | **0** |
| existing publications | 3, preserved | **3** |

No approval was re-created, no draft was modified, no revision was made.

## C — STEP 3: injury gate, final evaluation

Through the real `WorkoutSafetyGateService` → `WorkoutSafetyPolicy`:

| field | value |
|---|---|
| latest report | **id 3**, `RESOLVED` |
| `recordedAt` | 2026-10-13T22:54:24.101553Z |
| `evaluatedAt` | 2026-10-13T23:57:11.153Z |
| age | **1.05 h** (limit 48 h) — `INJURY_STATE_STALE` did not fire |
| `nonRestTrainingAllowed` | **true** |
| reasonCode | **`INJURY_RESOLVED_CONFIRMED`** |
| RECOVERY allowed | **yes** (policy is binary: non-REST passes once open) |
| other blocking condition | none |

No report was added, edited, re-registered or back-dated; no mock, no bypass, no policy change.
The gate was additionally re-evaluated **inside** the publish call itself, independently of this
reading — `ApprovedWorkoutDraftPublishService.publish` calls `requireAllowed` immediately before
anything is sent.

**`RESOLVED` remains the athlete's self-report that the symptom is gone — a data permission, not a
medical clearance.**

## D — STEP 4: preview re-verified

`GET /api/v1/workout-drafts/21/publish-preview`:

`publishable=true` · `externalWriteRequired=true` · `expectedOutcome=PUBLISH` · `restDay=false` ·
`structuredStepCount=3` · `unpublishableReasons=[]` · `approvalId=5` · `publication=null`.

Rendered text compared to the text approved in 7F-2E with an **ordinal, case-sensitive** comparison
(`-ceq`) → **IDENTICAL**:

```
- Warm Up 5m 60-68% LTHR hr=1s
- Main 20m 65-74% LTHR hr=1s
- Cool Down 5m 58-65% LTHR hr=1s
```

The preview performed no external write (it has no write path).

## E — STEP 5: calendar state immediately before the publish (08:57:33 KST)

`scripts\dev\preflight-intervals-date.ps1 -Date 2026-10-16 -AthleteId 0` — re-run seconds before
the publish, not reused from 7F-2E:

| item | result |
|---|---|
| account / tz / athlete-id | **i6\*\*\*48** / Asia/Seoul / **0** |
| WORKOUT events on 2026-10-16 | **0** |
| owned | **0** |
| foreign | **0** |
| marker `runningai:workout:v1:0:2026-10-16` | **absent** |
| legacy `[RunningAI-Control]` marker | **absent** |
| conflict | **none** |
| predicted operation | **CREATED** · exit 0 |

The date was empty, so no UPDATE, NO_CHANGE or legacy takeover was in scope at any point.

## F — STEP 6/7-A: the publish

One call, to the dev instance only:

```
POST http://127.0.0.1:18083/api/v1/workout-drafts/21/publish      (08:57:42 KST)
```

**HTTP 200** in ~2 s:

```json
{"draftId":21,"approvalId":5,"date":"2026-10-16","workoutType":"RECOVERY",
 "outcome":"PUBLISHED","intervalsOperation":"CREATED","remoteEventId":"144442046",
 "verified":true,"publishedAt":"2026-10-13T23:57:44.760175Z",
 "structuredStepCount":3,"alreadyPublished":false}
```

| field | value |
|---|---|
| draftId / approvalId | **21 / 5** |
| date | **2026-10-16** |
| `outcome` | **PUBLISHED** |
| `intervalsOperation` | **CREATED** |
| `remoteEventId` | **144442046** |
| `verified` | **true** (publisher's own readback) |
| `alreadyPublished` | false |
| error | none |

Server log:

```
08:57:44.754 IntervalsWorkoutPublisher        : Intervals workout publish: date=2026-10-16 operation=CREATED verified=true
08:57:44.844 WorkoutDraftPublicationStore     : Workout draft publication recorded: draftId=21 date=2026-10-16 outcome=PUBLISHED operation=CREATED
08:57:44.852 ApprovedWorkoutDraftPublishService: Workout draft publish: draftId=21 date=2026-10-16 operation=CREATED verified=true
```

No direct Intervals call was made by hand; the existing `IntervalsWorkoutPublisher` and
`HttpIntervalsWorkoutClient` did the work. The only external HTTP sequence was the publisher's own
list → POST → readback GET. No retry was issued, nothing was modified or deleted, and no SQL
recorded any outcome.

## G — STEP 7-B: external readback from Intervals (read-only GETs)

Verified against the real API, not against the application's own answer:

| # | check | observed |
|---|---|---|
| 1 | date | `start_date_local` = **2026-10-16T00:00:00** |
| 2 | account | **i6\*\*\*48**, tz Asia/Seoul |
| 3 | category / type | **WORKOUT** / **Run** |
| 4 | RunningAI marker | `external_id` = **`runningai:workout:v1:0:2026-10-16`** |
| 5 | name | **`RunningAI workout`** |
| 6 | total duration | `moving_time` = **1800 s = 30 min** (`icu_training_load` 12) |
| 7 | structure | **3 steps** — see below |
| 8 | content vs approved draft | description **byte-identical** to the approved rendered text |
| 9 | unexpected events on the date | **none** — 1 event of *any* category on 2026-10-16, and **1** WORKOUT in the whole 2026-10-13..10-19 window |
| 10 | existing events modified/deleted | **none** — the date held nothing beforehand |

Intervals parsed the text into exactly the approved session:

| step | duration | HR target | label |
|---|---|---|---|
| 1 | 300 s (5 min) | 60–68 | Warm Up |
| 2 | 1200 s (20 min) | 65–74 | Main |
| 3 | 300 s (5 min) | 58–65 | Cool Down |

Sum **1800 s = 30 min**, matching the approved total.

Note on the name: `RunningAI workout` is the publisher's fixed `EVENT_NAME` constant, **not** the
draft title "Short Easy Recovery Jog". That is existing, documented behaviour, unchanged here — the
draft title lives in RunningAI, the calendar event carries the constant plus the rendered steps.

## H — STEP 7-C: dev database record

| check | result |
|---|---|
| publications for draft 21 | **exactly 1** — row **id 4** |
| linkage | draft **21** ↔ approval **5** |
| outcome / operation | **PUBLISHED** / **CREATED** |
| external event id stored | **144442046** |
| `verified` | **t** |
| `published_at` | 2026-10-13 23:57:44.760175+00 |
| existing publications 1/2/3 (drafts 7/10/17) | **preserved, unchanged** |
| draft 21 content | **unchanged** — segments MD5 `2faf2b1d…`, identical to 7F-2E's pre-publish value |
| draft 20 / approval 4 (2026-10-15 REST) | **preserved** |
| injury reports #1 #2 #3 | **preserved** — statuses, both timestamps and `notes` MD5 all identical |
| dev totals | drafts 21, approvals 5, publications 3 → **4**, injury reports 3 |

Total dev-DB change for this phase: **1 row inserted** (`workout_draft_publication` id 4).

## I — STEP 7-D: idempotency

The guarantee was confirmed in code **before** being exercised: `publish()` looks up
`publications.find(id)` and returns the stored result **before any Intervals call**. Only then was
the call repeated:

```
POST .../21/publish  (08:58:39 KST)  → HTTP 200
{"draftId":21,"approvalId":5,...,"intervalsOperation":"CREATED","remoteEventId":"144442046",
 "verified":true,"publishedAt":"2026-10-13T23:57:44.760175Z",
 "structuredStepCount":null,"alreadyPublished":true}
```

| check | result |
|---|---|
| `alreadyPublished` | **true** |
| event id / `publishedAt` | **identical** to the first call — the stored row, not a new publish |
| server log | `draftId=21 already PUBLISHED; returning the stored result` — **no Intervals line at all** |
| additional external POST/PUT/PATCH/DELETE | **0** |
| publication rows | still **4** total, **1** for draft 21 — no duplicate |
| external event count after re-call | **1**, same id 144442046, unchanged (re-read externally) |

`structuredStepCount` is `null` on the idempotent answer because nothing was re-rendered — the
stored result is returned as-is.

## J — STEP 8: not needed

The publish returned a definite HTTP 200 with `verified=true`; there was no timeout, no lost
response and no ambiguity. No recovery path was entered, no re-POST was made. For the record, the
property that would have governed it is in the code and was read beforehand:
`createWithoutBlindRetry` never repeats a POST of unknown outcome — it re-lists the date by marker
and adopts what the server actually holds.

## K — STEP 9: Garmin delivery (reported separately from the Intervals result)

Read-only observation of the Intervals account settings:

| field | value |
|---|---|
| `icu_garmin_upload_workouts` | **true** |
| `icu_garmin_training` | true |
| `icu_garmin_last_upload` | **2026-10-13T23:57:44.436+00:00** |

That last-upload timestamp is **08:57:44.436 KST — 318 ms before** the publisher logged
`operation=CREATED verified=true` at 08:57:44.754. So Intervals pushed the workout toward Garmin at
the moment of creation: **the Intervals→Garmin upload leg demonstrably fired for this event.**

What is **not** claimed: this session did not observe the workout in Garmin Connect or on the watch.
This repository has no Garmin write path, and the dev instance's Garmin connector is deliberately
pointed at a dead port, so device-level confirmation is for the athlete to make. Nothing was
uploaded to Garmin directly, no duplicate workout was created, no Garmin sync setting was changed
and no existing Garmin workout was deleted.

**The Intervals publish is verified PASS regardless of device-level confirmation.**

## L — STEP 10: shutdown and production protection

| check | result |
|---|---|
| dev Spring 18083 (PID **47500**) | **stopped** |
| scheduled task `RunningAI-Dev-Spring-18083` | **deleted** |
| port 18083 | **no listener**; health → connection refused |
| publish-capable instance left running | **none** — 18081/18082 have `draft-publishing=false` and a dead Intervals base-url |
| production Spring 18080 | **UP**, PID **41436** — same PID throughout, never restarted |
| gateway 17910 | listening, PID **48104** — same PID throughout, never restarted |
| production DB `running_ai` | drafts **19**, approvals **3**, publications **3**, injury reports **0**, latest publication still 2026-10-05 — **0 changes** |
| legacy publish tasks | `TodayWorkout` / `CommandChannel` / `TrainingCommand` / `RemoteWakeupScheduler` **all still Disabled** |
| watchface / relay | untouched — the only processes acted on were the dev JVM and its own task |

## M — Prohibited actions: none performed

No production DB write, no production injury report, no production rebuild or restart, no gateway
restart or config change, no OAuth scope change, no Cloudflare Worker deploy, no production MCP
write tool enabled. On the external account: **one** event created on **2026-10-16** for **draft 21
only** — no other date, no other draft, no UPDATE or DELETE of any existing event, never two events,
no other athlete account, no direct Garmin publish. No domain policy was changed to make the publish
succeed, no injury gate bypass, no schema change, no deleted approval, no draft revision, no merge
into canonical `main`, no `origin` push, no change to `C:\running-ai`, no unrelated refactoring.

## N — Final report

### A. Pre-publish verification
Executed 2026-10-14 **08:55–08:57 KST**. Injury gate **valid** (report #3 RESOLVED, age 1.05 h of
48 h, `INJURY_RESOLVED_CONFIRMED`, `nonRestTrainingAllowed=true`). Draft 21 v1 APPROVED and
byte-matched; approval #5 confirmed, one per date, no prior publication. Target account
**i6\*\*\*48** / athlete-id "0" / Asia/Seoul. Calendar immediately before publish: **0 WORKOUT
events** (owned 0, foreign 0). Predicted operation **CREATED**.

### B. Publish result
Internal API **HTTP 200** · **success** · actual operation **CREATED** · external event
**144442046** · marker **`runningai:workout:v1:0:2026-10-16`** · **external writes: 1** (one POST).

### C. External readback
Date **2026-10-16**, category **WORKOUT** / type Run, **30 min** (1800 s), segments
**5 min @ 60–68 / 20 min @ 65–74 / 5 min @ 58–65 % LTHR** — matching the approved draft exactly.
**No unexpected event** on the date or in the surrounding week.

### D. Idempotency
Re-called once · `alreadyPublished=true` · **0** additional external writes · **no** duplicate
publication row · external event count still **1**.

### E. System
Dev DB: **+1 row** (`workout_draft_publication` id 4). Production DB: **0 changes**. Production
services: unaffected, never restarted. Dev server: **stopped**. Garmin delivery: **upload
triggered** (`icu_garmin_last_upload` stamped at the creation instant); device-level display not
observed from here. Git: HEAD **`30113c2`**, branch `main-3`, **no commit, no push**; files added =
this work order + the verbatim instruction, no source/schema/config change, tests not run (no code
changed).

### F. Verdict

**PASS** — injury gate valid, exactly one external `CREATED`, readback verified, no existing event
changed, one publication row in the dev DB, production untouched, no additional external writes.

## What this closes, and what it does not

**Closed:** the `CREATED` branch — including `createWithoutBlindRetry`, the first POST onto an empty
date and its readback verification — has now run for real. Before today the only live evidence was
three `UPDATED` publishes (2026-10-03/04/05, events 140389091 / 140391756 / 140511724) over events
the legacy publisher had already placed.

**Still open, each needing its own phase and its own approval:**

- Any further workout publish. This authorisation covered draft 21's first publish only.
- Production publishing: `running_ai` has **0 injury reports**, so its gate answers
  `NO_INJURY_STATE_RECORDED` and it cannot publish anything at all. Both production publishing
  switches remain false.
- Production ChatGPT MCP write tools and the Cloudflare Worker scope widening
  (`runningai:train` / `runningai:publish` tokens still cannot exist).
- Device-level confirmation that this workout reached the watch.

Architecture direction is unchanged: Modular Monolith, single-tenant, self-hosted.
