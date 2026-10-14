# Phase 7F-2C — Injury Gate resolution attempt and pre-publish validation

Date: 2026-10-14 (KST) · Branch `main-3` · HEAD `30113c2` · **no code change, no commit, no push**

## Outcome in one line

The Injury Gate was **exercised, not cleared**: the athlete's actual self-report maps honestly to
`UNKNOWN`, so non-REST training stays blocked, the coach correctly produced a REST day, and
**zero external writes** were made.

## STEP 1 — Injury reports recorded (dev DB `running_ai_7f2` only)

Two rows in the append-only `injury_report` ledger, in this order:

| id | status | reported_at | recorded_at | body_site | side | pain_present | pain_on_walking | other symptom fields |
|----|--------|-------------|-------------|-----------|------|--------------|-----------------|----------------------|
| 1 | `ACTIVE` | `2026-10-11T00:31:43Z` | `2026-10-13T21:50:37.858901Z` | `knee (lateral)` | `UNSPECIFIED` | `true` | `true` | all `null` |
| 2 | `UNKNOWN` | `2026-10-13T21:50:46.705705Z` | `2026-10-13T21:50:46.705705Z` | `knee (lateral)` | `UNSPECIFIED` | `false` | `false` | all `null` |

`pain_intensity`, `pain_on_stairs`, `swelling`, `range_of_motion_limited` are `null` on both rows —
the athlete reported none of them, and an unconfirmed symptom is never written as `0`/`false`.

### Why row 1 exists
The athlete reported a past fact (post-half-marathon lateral knee pain on 2026-10-11, including on
walking). Recording it preserves the history rather than discarding it. `reported_at` is pinned to
the **real** end instant of the stored half marathon (Garmin activity `24679734836`, 21.24 km,
ended `2026-10-11T00:31:43Z`) because the exact onset time was not reported — no clock time was
invented. `recorded_at` is server-now, demonstrating the reported-vs-recorded distinction.

### Why row 2 is `UNKNOWN` and not `RESOLVED` (the decisive call)
The athlete confirmed: no knee pain now, no knee pain when walking. The athlete explicitly left
**unconfirmed**: whether pain occurs *while running*, and whether pain *recurs after* running.

`InjuryStatus.RESOLVED` is documented as reachable *only* by an explicit athlete statement that the
symptom is gone. No such statement was made. Writing `RESOLVED` would have been a fabricated
recovery judgement and would have lifted the running restriction on unconfirmed data — the two
things the work order forbids. The schema expresses the real state precisely
(`UNKNOWN` + confirmed `false` booleans + nulls + notes), so no STOP was warranted.

## STEP 2 — Injury Gate verification

- `GET /api/v1/athlete/injury-state` → `nonRestTrainingAllowed = false`, `INJURY_STATE_UNKNOWN`
- Latest row by `recorded_at DESC` is id=2 (ordering unambiguous: the two rows are ~9 s apart)
- **48 h freshness: satisfied.** `staleness-hours = 48` (application.yml, not overridden by the dev
  launch args); latest report age ≈ 12 s. `INJURY_STATE_STALE` did **not** fire — the block comes
  from the `UNKNOWN` status itself, which is rule 2 of `WorkoutSafetyPolicy`'s precedence.
- **RECOVERY training: not allowed.** The policy is deliberately binary. `requireAllowed` passes
  only when `isRest` is true, and `isRest` is `workoutType == "REST"` exactly. RECOVERY is a
  non-REST type, so it is blocked like any run.
- **REST required: yes.** REST is the only workout type that can be generated, approved or published
  in this state.

No domain logic was bypassed, patched or re-implemented; every verdict came from
`WorkoutSafetyGateService` / `WorkoutSafetyPolicy`.

## STEP 3 — AI draft (real local Claude CLI)

`POST /api/v1/workout-drafts {"date":"2026-10-15","availableMinutes":30,"requestedGoal":"recovery"}`
→ HTTP 200 in **42.1 s**, provider `CLAUDE`, model `sonnet`. Not faked, not forced.

The gate's `constraintText` was prepended to the prompt ahead of the athlete's own words, and the
coach complied: `workoutType = REST`, `totalDurationMinutes = 0`, `segments = []`.

Per the work order a REST answer is a normal result, so real-publish validation is deferred. The
`STOP` condition (non-REST generated *and* gate-blocked) did not occur.

## STEP 4 — Draft → Approval → Preview

Reviewed before approving: date `2026-10-15` (intended target), type `REST`, no intensity, no
segments, version 1 is the latest. A REST draft reaches no Intervals account at all. **No revision**
was requested: REST is the correct content under the gate, and a non-REST revision would only have
been rejected by the same gate.

- Draft `id=20`, `draftGroupId=ce5ce1cb-b30a-4e95-9381-4fa22b9a5fb1`, version `1` → `APPROVED`
- Approval `id=4`, `approvedAt=2026-10-13T21:52:40.164678Z`
- Preview: `restDay=true`, `publishable=true`, `externalWriteRequired=false`,
  `expectedOutcome=SKIPPED_REST_DAY`, `structuredStepCount=0`, `unpublishableReasons=[]`,
  `publication=null`

Approval was an internal dev-DB lifecycle change only — it is architecturally incapable of an
external write (`CoachArchitectureTest`).

### Consequence to carry forward
`workout_draft_approval` enforces **at most one approved draft per athlete+date** (service check and
DB unique constraint). Approving the REST draft therefore **permanently consumes 2026-10-15** in
`running_ai_7f2`: a later attempt to approve a real workout for that date in this DB returns
`WORKOUT_DATE_ALREADY_APPROVED`. The first real publish on this dev DB must target a different date,
or the approval row must be removed by hand.

## STEP 5 — Intervals calendar preflight (read-only)

`scripts\dev\preflight-intervals-date.ps1 -Date 2026-10-15 -AthleteId 0` — one GET, no write path.

- Account: `i6***48`, tz `Asia/Seoul`, resolved from the API key with `athlete-id=0` (unchanged)
- Marker: `runningai:workout:v1:0:2026-10-15`
- WORKOUT events on 2026-10-15: **0** (owned 0, foreign 0)
- Predicted operation: `CREATED`

No existing event, no overwrite, no conflict → no STOP condition. Note this prediction describes
what the *publisher* would do; the approved draft is REST, so the draft-publish path short-circuits
to `SKIPPED_REST_DAY` before the publisher is ever reached. The real expected external change for
draft 20 is **none**.

## STEP 6 — External write count: 0

No Intervals `POST`/`PUT`/`PATCH`/`DELETE` was issued. The only Intervals traffic was the single
preflight `GET`. Not done, as required: production DB change, production restart, OAuth scope
change, Cloudflare Worker deploy, MCP write-tool exposure, gate bypass, auto-publish, canonical
`main` merge, `origin` push.

The publish-capable dev instance (18083, started with `-DraftPublishLive`) was **shut down** after
validation: `scripts\dev\start-dev-spring.ps1 -Port 18083 -Database running_ai_7f2 -Stop`. Verified
no listener remains. Production 18080 verified `UP` and untouched (still 0 injury reports).

## Blocker for the first real publish

Only one thing stands between here and a real workout publish: the athlete must explicitly report
`status=RESOLVED` — specifically after confirming the two items they left open (pain during running,
pain recurrence after running). That is the athlete's statement to make, not RunningAI's to infer.

Once a fresh `RESOLVED` report exists (<48 h old), the sequence is: restart the dev instance,
generate a draft for a date **other than 2026-10-15**, review, approve, preflight that date, then
publish on explicit approval.

## State

- Files changed: this work order + the verbatim instruction. No source, schema or config change.
- Commit/push: none. Tree otherwise clean at `30113c2`.
- Tests: not run — no code was modified.
