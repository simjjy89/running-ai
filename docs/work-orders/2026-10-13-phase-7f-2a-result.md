# Phase 7F-2A — Intervals.icu real-publish path verification (preparation + Mock E2E)

Date: 2026-10-13 (plan updated 2026-10-14) · Branch `main-3` (base `4ebfad5`) · Instruction: `2026-10-13-phase-7f-2a-instruction.md`

**Status: STEP 1–4 complete, STOPPED at STEP 5 awaiting approval. No Intervals.icu write was performed.**
Every Intervals call made during this work order was a **GET** (athlete identity and calendar listing),
used to answer STEP 4's "해당 날짜 기존 일정 존재 여부". Nothing was created, updated or deleted.

---

## 0. Correction to the stated baseline

The instruction's "현재 기준" says **운영 Intervals.icu 실게시 0건**. That is not what the account holds.

Read-only `GET /api/v1/athlete/0/events?oldest=2026-08-01&newest=2026-10-27&category=WORKOUT`
returned 30 WORKOUT events, four of which carry **this repository's own ownership marker**:

| date | event id | `external_id` | in `workout_draft_publication`? |
|---|---|---|---|
| 2026-10-02 | 140387371 | `runningai:workout:v1:0:2026-10-02` | no |
| 2026-10-03 | 140389091 | `runningai:workout:v1:0:2026-10-03` | yes — publication 1, draft 7, `UPDATED`, verified |
| 2026-10-04 | 140391756 | `runningai:workout:v1:0:2026-10-04` | yes — publication 2, draft 10, `UPDATED`, verified |
| 2026-10-05 | 140511724 | `runningai:workout:v1:0:2026-10-05` | yes — publication 3, draft 17, `UPDATED`, verified |

So the **approved-draft → Intervals.icu path has already written to the real account three times**, all
server-verified, and the descriptions are unmistakably this renderer's output (`- Main 2m 4:35-4:45/km Pace`,
`- Rest 1m 65-75% LTHR hr=1s`). The 2026-10-02 event carries the marker but has no publication row, so it
came from the legacy date-based path or from a legacy event the publisher adopted.

**What is genuinely unproven is narrower and more interesting:** all three draft publications are `UPDATED`.
An owned (or legacy-marked) event already existed on each of those dates, so the publisher took it over in
place. **The `CREATED` branch — the first POST onto a date that holds nothing — has never run against the
real account from the draft path.** That is exactly what the proposed publish in §4 would exercise, because
the calendar is empty from 2026-10-13 onward.

---

## 1. STEP 1 — review of the real publishing path

Flow as implemented (no step inferred; each read in source):

```
POST /api/v1/workout-drafts            WorkoutDraftController.generate  -> WorkoutDraftService -> ClaudeAiCoach (CLI)
POST .../{id}/revisions                             .revise             -> new version, previous SUPERSEDED
POST .../{id}/approve                  WorkoutDraftApprovalService      -> APPROVED + workout_draft_approval (DB only)
GET  .../{id}/publish-preview          ApprovedWorkoutDraftPublishService.preview   (zero external calls)
POST .../{id}/publish                  ApprovedWorkoutDraftPublishService.publish
   -> ApprovedWorkoutDraftPublishabilityValidator   (fail closed)
   -> WorkoutDraftStructuredWorkoutMapper           (exact transport mapping, repeats expanded)
   -> IntervalsWorkoutRenderer                      (Workout Builder text)
   -> IntervalsWorkoutPublisher                     (ownership, state machine, readback)
   -> HttpIntervalsWorkoutClient                    (the only code in the repo that POSTs/PUTs Intervals)
   -> workout_draft_publication                     (success only)
```

### 1.1 Target Intervals athlete id

`running-ai.intervals.athlete-id` = `${INTERVALS_ATHLETE_ID:0}`. The canonical production `.env` does **not**
set `INTERVALS_ATHLETE_ID`, so the value is the literal string `"0"`, which Intervals.icu resolves to *the
owner of the API key*. **The target is therefore chosen by the credential, not by configuration.** Verified
live: `GET /api/v1/athlete/0` returns athlete `i6***48` (7 characters), `timezone = Asia/Seoul`.

The ownership marker embeds the **configured string, not the resolved id**:
`IntervalsWorkoutPublisher.externalId()` = `"runningai:workout:v1:" + properties.athleteId() + ":" + date`,
which matches the four live markers above (`...:v1:0:...`). See risk **R1**.

### 1.2 API key loading and exposure

- `IntervalsProperties.apiKey` comes from `INTERVALS_API_KEY` only; it may be blank so the application still
  starts, and `IntervalsHttp.authorization()` then throws `NOT_CONFIGURED` **before building a request**, so an
  unauthenticated call is structurally impossible.
- `IntervalsProperties.toString()` prints `<set>` / `<not set>`; `IntervalsEvent.toString()` and
  `IntervalsEventDraft.toString()` never print the description (the workout text).
- `IntervalsHttp.toException()` keeps **only the HTTP status code** — never the response body.
- Verified at the wire level (new test, §2): a 401 whose body literally contains the API key produces an API
  error response containing neither the key nor the `Authorization` header.
- This work order itself never printed the key: it was read from the canonical `.env` into a local variable and
  used only to build the header.

### 1.3 Date and time zone

The event is written as `start_date_local = <draft date>T00:00:00`. That field is a **local** date-time, so no
zone conversion happens on either side; the Intervals athlete's zone is `Asia/Seoul`, the same zone the rest of
RunningAI normalises in. Two independent defences: the publisher ignores any listed event whose
`start_date_local` date is not the requested date, and the readback re-checks the date.

### 1.4 Training-structure conversion

`WorkoutDraftStructuredWorkoutMapper` copies every duration, pace, %LTHR, treadmill speed and incline exactly
and expands `repetitions` into real steps; it never consults the athlete profile, so nothing is re-derived from
whatever LTHR happens to be on file at publish time. `ApprovedWorkoutDraftPublishabilityValidator` **fails
closed before the publisher is reached** on: bpm heart-rate targets, half-specified ranges, treadmill speed
without incline, negative incline, MODERATE/HARD with no numeric target, `CROSS_TRAINING`/unknown types; and
after mapping it re-checks that the mapped steps sum to exactly the approved total duration.

### 1.5 Overwriting an existing calendar entry

`IntervalsWorkoutPublisher` lists the date's WORKOUT events and classifies each as owned (marker match, **or**
legacy `[RunningAI-Control]` in the description with no `external_id`) or foreign:

| situation | action |
|---|---|
| 2+ owned | `DUPLICATE_OWNED_WORKOUT` — nothing created, changed or deleted |
| 1 owned, same text | `NO_CHANGE` — no write request at all |
| 1 owned, different | `PUT` on that same event id → `UPDATED` |
| 0 owned, ≥1 foreign | `UNMANAGED_WORKOUT_CONFLICT` — nothing created or changed |
| nothing on the date | `POST` → `CREATED` |

**A foreign event is never modified, and no code path in this repository deletes an Intervals event** — the
`IntervalsWorkoutClient` interface has only `listWorkouts`/`get`/`create`/`update`. The one overwrite that *can*
happen is the deliberate legacy takeover: an unmarked event whose description contains `[RunningAI-Control]` is
updated in place (and acquires the new marker and the name `RunningAI workout`) rather than duplicated. That is
almost certainly what produced the three `UPDATED` publications in §0.

### 1.6 Success response and stored result

After every write the event is re-read and compared on id, marker, date and (newline-normalised) description;
any difference is `READBACK_MISMATCH`, never a success — so `verified = true` means the server really holds what
was approved. `workout_draft_publication` keeps one row per draft (unique index), with outcome, Intervals
operation, remote event id, `verified` and timestamp. **Failures store nothing**: the draft stays `APPROVED` and
can be published again.

### 1.7 Timeout and duplicate-publish safety

Four independent layers, no retry anywhere in the stack:

1. `HttpIntervalsWorkoutClient` — exactly one HTTP request per method, never repeated.
2. `IntervalsWorkoutPublisher.createWithoutBlindRetry` — on TIMEOUT / CONNECTION_FAILED / 5xx
   (`IntervalsException.isOutcomeUnknown()`) it **lists the date again by marker** and adopts an event that did
   land; if nothing landed it surfaces the original failure instead of POSTing again.
3. `ApprovedWorkoutDraftPublishService` — per-draft in-JVM single flight (`DRAFT_PUBLISH_ALREADY_RUNNING`), and
   a stored publication short-circuits before the publisher is touched.
4. The publisher's own marker lookup makes a duplicate remote event impossible even across restarts.

Bounded timeouts: connect 3 s, read 15 s (never infinite).

### 1.8 Conflict with the legacy date-based publishing path

Both paths write **the same per-athlete-per-date marker**, so `PublishingModeGuard` fails application startup if
`WORKOUT_PUBLISHING_ENABLED` and `RUNNING_AI_DRAFT_PUBLISHING_ENABLED` are both true. Production currently has
*both* off (verified in the canonical `.env`), together with the publishing scheduler, the MCP master switch and
the train-MCP switch (the latter simply absent → false). The remaining writer is outside this repository — see
risk **R2**.

---

## 2. STEP 3 — Mock E2E result

### 2.1 What already existed

`DraftPublishApiTest` (28 cases) covers the lifecycle over HTTP but **mocks `IntervalsWorkoutPublisher` and the
transport**; `IntervalsWorkoutPublisherTest` (28 cases) covers the ownership state machine against a fake
client; `HttpIntervalsWorkoutClientTest` covers the HTTP contract in isolation. Nothing exercised the three
together — i.e. **no test ever asserted the actual bytes the draft path would put on the athlete's calendar.**

### 2.2 What was added

`server/src/test/kotlin/com/runningai/draftpublish/DraftPublishIntervalsWireE2ETest.kt` — a full-stack
`@SpringBootTest` with the real publisher, the real `HttpIntervalsWorkoutClient`, the real renderer and the real
draft lifecycle over MockMvc. **Only the socket is replaced**: the `intervalsRestClient` bean is bound to a
`MockRestServiceServer` (by bean name, deliberately not `@Primary`, so the Garmin `RestClient` keeps resolving to
its own bean). The API key is a synthetic inlined test property, which outranks any real `INTERVALS_API_KEY` in
the developer's environment, and no request can leave the JVM.

12 cases, **all passing**, mapped to the STEP 3 checklist:

| # | STEP 3 item | where it is proven |
|---|---|---|
| 1 | Draft 생성 성공 | `WorkoutDraftApiTest` (existing) |
| 2 | Revision 정상 반영 | `WorkoutDraftApiTest`, `DraftPublishApiTest` (approved draft refuses revision) |
| 3 | Approval 및 버전 검증 | `DraftPublishApiTest` — idempotent approval, one per athlete+date, SUPERSEDED refused |
| 4 | Preview와 승인 내용 일치 | **new**: `the preview text is byte-identical to the description that is actually sent` |
| 5 | 외부 전송 payload 검증 | **new**: `a preview sends nothing and a publish sends exactly list, create and readback` — asserts category/type/name/`start_date_local`/`external_id`/description on the real POST body |
| 6 | 기존 일정 충돌 감지 | **new**: `a workout RunningAI does not own blocks the publish before any write`; `an identical workout already on the calendar is NO_CHANGE with no write at all` |
| 7 | 동일 Draft 중복 게시 방지 | **new**: `a second publish of the same draft reaches Intervals zero times` |
| 8 | 타임아웃·응답 유실 시 처리 | **new**: `a create whose answer is lost is resolved by a second lookup, never by a second POST`; `a lost create that did not apply stores nothing and the retry publishes once` |
| 9 | 게시 대상 Athlete ID 검증 | **new**: `the request is addressed to the configured athlete and the marker names that athlete and date only` |
| 10 | Injury Gate 우회 불가 | **new**: `the injury gate blocks a non-REST publish before the first byte is sent` |
| — | readback integrity | **new**: `a server that stores something else than what was sent is a readback mismatch, not a success` |
| — | credential never leaks | **new**: `an Intervals rejection carries the status code and never the credential` |

The exact request sequence a `CREATED` publish emits, now pinned by test:

```
GET  /api/v1/athlete/0/events?oldest=<date>&newest=<date>&category=WORKOUT    (Basic auth)
POST /api/v1/athlete/0/events                                                 (one request, never retried)
GET  /api/v1/athlete/0/events/<new event id>                                  (readback verification)
```

and a preview emits **none of them**.

### 2.3 Full regression

`.\gradlew.bat clean test` from `server/` — see §7 for the recorded result.

---

## 3. STEP 2 — safe development environment

Current machine state (verified): production Spring on `127.0.0.1:18080` against database `running_ai`;
dev instances already listening on 18081 and 18082; PostgreSQL 17 in the `running-ai-postgres` container.

Prepared, minimally: `scripts/dev/start-dev-spring.ps1` hard-coded `--running-ai.draft-publishing.enabled=false`,
so **there was no supported way to run the real path at all** without touching production. Added a
`-DraftPublishLive` switch that flips only that one property, requires `-IntervalsLive` (no credential, no
possible write), prints an explicit warning, and leaves every existing guard untouched: production database and
port 18080 still refused outright, loopback-only bind, legacy publishing and both Garmin schedulers still forced
off, Garmin connector still pointed at a dead port, `DB_PASSWORD`/`INTERVALS_API_KEY` still read from the
canonical `.env` at launch into a per-user environment variable and never written to disk. `PublishingModeGuard`
remains satisfied because legacy publishing stays false.

Nothing was started with this switch. Production Spring was not restarted, and no production row was changed.

---

## 4. STEP 4 — execution plan for the real publish

### 4.1 Blocking precondition — the injury gate (not bypassable, and not mine to satisfy)

`injury_report` in the production database is **empty (0 rows)**. `WorkoutSafetyPolicy` therefore returns
`NO_INJURY_STATE_RECORDED` → REST only, which is why the two most recent production drafts (2026-10-06 and
2026-10-12) are both `REST`. With the database empty, `approve` and `publish` of any non-REST draft fail with
`WORKOUT_SAFETY_NO_INJURY_STATE_RECORDED` (422).

**The athlete must record a current status themselves** (`POST /api/v1/athlete/injury-reports` with
`status: "RESOLVED"`) on whichever instance runs the test, and it must be within the 48 h freshness window at
both approve time and publish time. Per the instruction, the gate is not bypassed, not stubbed and not
pre-seeded by me; if the athlete's real status is `ACTIVE`, this publish does not happen and the coach will
correctly produce a REST day.

### 4.2 The plan

| item | value |
|---|---|
| **게시 예정 날짜** | **2026-10-15** (tomorrow; 2026-10-14, today, works equally well) |
| **Intervals athlete (masked)** | `i6***48` (7 chars), timezone `Asia/Seoul`, selected implicitly by `athlete-id=0` = the API key's owner |
| **해당 날짜 기존 일정** | **none.** `category=WORKOUT` over 2026-10-13..2026-10-27 returned 0 events; the last RunningAI-marked event is 2026-10-05 |
| **생성될 이벤트** | one Intervals.icu calendar **event**, `category=WORKOUT`, `type=Run`, `name="RunningAI workout"`, `start_date_local=2026-10-15T00:00:00`, `external_id=runningai:workout:v1:0:2026-10-15`, description = the rendered Workout Builder text |
| **expected operation** | **`CREATED`** — the branch never yet exercised live from the draft path |
| **훈련 내용** | a short recovery session **designed by the coach, not dictated here**: the draft is requested with `availableMinutes: 30`, `requestedGoal: "recovery"` and the real V2 training context, then reviewed in the preview before approval. Shape to expect (≈25–30 min, warm-up / easy main / cool-down, at most a `%LTHR` target), e.g. `- Warm Up 10m` / `- Main 15m 70-80% LTHR hr=1s` / `- Cool Down 5m`. If the coach returns REST, that is the correct answer and the publish is cancelled. |
| **외부 요청의 정확한 영향** | exactly 3 HTTP requests: one GET listing that date, one POST creating the event, one GET reading it back. One new calendar row on the athlete's account. No existing event is read for modification, none is updated, none is deleted; no activity, wellness or athlete record is touched. |

### 4.3 Where it runs

Recommended: a **new dev instance on 18083 against a fresh copy of the production database** (`running_ai_7f2`),
so the coach sees the real training history (V2 context) while production rows stay untouched:

- production Spring (18080) keeps running, unchanged and unrestarted;
- the production database is only read (`pg_dump` → restore into a new database);
- the **Intervals.icu calendar is genuinely shared** — this is the one real side effect, and it is the point of
  the exercise.

Alternative, if the publication must be recorded in the production database instead: enable
`RUNNING_AI_DRAFT_PUBLISHING_ENABLED=true` in the canonical `.env` and restart production — **STEP 5 forbids a
production restart without explicit approval**, so this is not the default.

### 4.4 Failure and recovery

| failure | what it means | recovery |
|---|---|---|
| `WORKOUT_SAFETY_*` (422) | the injury gate refused | correct; record a current status or accept REST. Nothing was sent |
| `UNPUBLISHABLE_DRAFT` (422) | the renderer cannot express the approved draft | nothing was sent; revise and approve a new draft |
| `INTERVALS_UNMANAGED_WORKOUT_CONFLICT` (409) | a foreign workout appeared on that date | nothing was sent; inspect the date by hand (likely the legacy PowerShell writer — risk R2) |
| `INTERVALS_TIMEOUT` / `CONNECTION_FAILED` / `UPSTREAM_ERROR` (504/503/502) | outcome unknown; the publisher already re-listed by marker and found nothing | nothing stored, draft still APPROVED. **Re-POST the same publish** — it lists first and adopts rather than duplicating. Never POST by hand |
| `INTERVALS_READBACK_MISMATCH` (502) | a write happened but the server holds something else | **an event may exist.** Nothing was stored. Verify with `GET /api/v1/athlete/0/events/<id>`; the event id is in the server log |
| wrong workout published | | re-publish is not the fix (the stored publication short-circuits). Delete the event in the Intervals.icu UI by hand, delete the `workout_draft_publication` row, then publish the corrected draft |

Rollback of the test itself is a single manual deletion of one calendar event in the Intervals.icu UI — the date
holds nothing else.

### 4.5 Expected Garmin calendar impact

Intervals.icu pushes planned workouts to Garmin Connect **only if the athlete has enabled that in their
Intervals.icu Garmin settings**; this repository has no code path to Garmin's calendar and cannot determine that
setting from the API. If it is on, the event is expected to appear as a planned workout in Garmin Connect and on
the Forerunner 265 after a sync — which is the behaviour Phase 5C-4 already DEVICE_VERIFIED for pace, %LTHR and
treadmill cues on the publisher path. **This should be confirmed by looking at Garmin Connect after the publish,
not assumed.** Nothing is written back to Garmin by RunningAI.

---

## 5. Risks found before any external publish

| id | severity | risk |
|---|---|---|
| **R1** | medium (latent) | The ownership marker embeds the **configured** `athlete-id` string, not the resolved athlete. Setting `INTERVALS_ATHLETE_ID` to the real id (`i6…48`) would make all four existing marked events unrecognised, so the next publish on those dates would see a *foreign* event and fail `UNMANAGED_WORKOUT_CONFLICT`. **Do not change `INTERVALS_ATHLETE_ID`.** Not fixed here (minimal-change scope); worth a follow-up decision. |
| **R2** | ~~medium~~ → **CLEARED 2026-10-14** | The deprecated legacy PowerShell publisher was the only other writer of this marker. Verified read-only (Phase 7F-2B, see that section): only `create-today-workout.ps1` and `command-channel-worker.ps1` write Intervals events, and all four tasks that can reach them (`RunningAI-TodayWorkout`, `RunningAI-CommandChannel`, `RunningAI-TrainingCommand`, `RunningAI-RemoteWakeupScheduler`) are **Disabled**, with no such process running. The enabled legacy tasks read Intervals or do not touch it at all. |
| **R3** | low | The baseline "실게시 0건" was wrong (§0); any decision that assumed an untouched calendar should be revisited. The real gap is the `CREATED` branch, not the path as a whole. |
| **R4** | low | `POST /api/v1/workout-drafts/{id}/publish` has **no authentication** (documented convention: private network only). On 18083 it binds 127.0.0.1 only, so the exposure is local processes. Unchanged by this work order. |
| **R5** | low | The injury gate is re-checked at approve and at publish, but `workout_draft_approval` is not re-validated against the *content* at publish time — it does not need to be, because approved drafts are immutable. No action. |
| **R6** | informational | A dev instance publishing to the shared real calendar means **dev data can produce a production-visible side effect**. That is inherent to validating the real path and is why the publish is gated on explicit approval. |

---

## 6. Changed files

| file | change |
|---|---|
| `server/src/test/kotlin/com/runningai/draftpublish/DraftPublishIntervalsWireE2ETest.kt` | **new** — 12-case wire-level Mock E2E (real publisher + real HTTP client + real renderer, mock socket) |
| `scripts/dev/start-dev-spring.ps1` | `-DraftPublishLive` switch (requires `-IntervalsLive`), explicit warning; all existing safety refusals unchanged |
| `docs/work-orders/2026-10-13-phase-7f-2a-instruction.md` | **new** — the instruction, verbatim |
| `docs/work-orders/2026-10-13-phase-7f-2a-result.md` | **new** — this report |

No production code was changed. No migration. Not committed, not pushed, not merged (STEP 5).

---

## 7. Test results

`.\gradlew.bat clean test` from `server/` (Java 21):

```
BUILD SUCCESSFUL in 6m 48s
146 test classes · 1426 tests · 0 failures · 0 errors · 0 skipped
```

`DraftPublishIntervalsWireE2ETest` passes 12/12, both in isolation and in the full run. No existing test was
changed, disabled or deleted.

---

## 8. Commands to run after approval

All of this is **blocked pending explicit approval**; nothing below has been executed.

```powershell
# 0. PRE-FLIGHT: confirm the legacy PowerShell publisher's scheduled tasks are disabled (risk R2)
schtasks /Query /FO LIST | Select-String -Pattern 'RunningAI'

# 1. A dev copy of the production database (production is only read)
docker exec running-ai-postgres psql -U running_ai -d postgres -c "CREATE DATABASE running_ai_7f2 TEMPLATE running_ai;"

# 2. A dev instance that may publish. Loopback-only; legacy publishing and both Garmin schedulers stay off.
scripts\dev\start-dev-spring.ps1 -Port 18083 -Database running_ai_7f2 -IntervalsLive -DraftPublishLive

# 3. THE ATHLETE records their own current status (the injury gate is not bypassed)
Invoke-RestMethod -Method Post -Uri http://127.0.0.1:18083/api/v1/athlete/injury-reports `
  -ContentType 'application/json' -Body '{"status":"RESOLVED","painPresent":false,"notes":"7F-2 validation"}'
Invoke-RestMethod -Uri http://127.0.0.1:18083/api/v1/athlete/injury-state

# 4. Draft the session (the coach decides; CLI call, ~1-2 min)
$d = Invoke-RestMethod -Method Post -Uri http://127.0.0.1:18083/api/v1/workout-drafts `
  -ContentType 'application/json' `
  -Body '{"date":"2026-10-15","availableMinutes":30,"requestedGoal":"short recovery run to validate the publishing path"}'
$d | ConvertTo-Json -Depth 8      # STOP here if workoutType is REST: that answer is correct, cancel the publish

# 5. Approve, then preview EXACTLY what would be sent (preview makes zero external calls)
Invoke-RestMethod -Method Post -Uri "http://127.0.0.1:18083/api/v1/workout-drafts/$($d.id)/approve"
$p = Invoke-RestMethod -Uri "http://127.0.0.1:18083/api/v1/workout-drafts/$($d.id)/publish-preview"
$p | ConvertTo-Json -Depth 6      # check: publishable=true, externalWriteRequired=true, renderedWorkoutText

# 6. >>> THE ONLY EXTERNAL WRITE <<<  3 requests: list, create, readback
Invoke-RestMethod -Method Post -Uri "http://127.0.0.1:18083/api/v1/workout-drafts/$($d.id)/publish"

# 7. Stop the publish-capable instance immediately afterwards
scripts\dev\start-dev-spring.ps1 -Port 18083 -Database running_ai_7f2 -Stop
```

### Verification after the publish

1. **Response**: `outcome=PUBLISHED`, `intervalsOperation=CREATED`, `verified=true`, a `remoteEventId`.
2. **Server-side, independently** (read-only GET, the publisher already did its own readback):
   `GET https://intervals.icu/api/v1/athlete/0/events?oldest=2026-10-15&newest=2026-10-15&category=WORKOUT`
   — exactly one event, `external_id = runningai:workout:v1:0:2026-10-15`, description identical to
   `renderedWorkoutText` from step 5.
3. **Idempotency**: repeat step 6 — expect `alreadyPublished=true` and **zero** further Intervals requests.
4. **Intervals.icu UI**: the workout appears on 2026-10-15 with the structure visible in the Workout Builder.
5. **Garmin Connect**: check whether the planned workout propagated (depends on the athlete's Intervals→Garmin
   setting; see §4.5) and, if so, whether it reaches the Forerunner 265 after a sync.
6. **Database**: one new `workout_draft_publication` row in `running_ai_7f2` — and **none** in `running_ai`.

### Explicitly still forbidden without separate approval

Production Spring restart · production `.env` changes · OAuth scope widening · Cloudflare Worker deploy ·
exposing write tools on the production gateway · merge to canonical `main` · `origin` push.
