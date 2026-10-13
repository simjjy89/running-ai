# Phase 7F-2B — preparation for the first real Intervals.icu publish

Date: 2026-10-14 · Branch `main-3` · Follows `2026-10-13-phase-7f-2a-result.md`

**No training was published. No OAuth scope, Cloudflare Worker or production service was touched.**
Every Intervals.icu call in this phase was a **GET**.

---

## 1. Commit of the 7F-2A work

`5beb23d test(draftpublish): wire-level Mock E2E for the approved-draft publish path` on `main-3`
(not pushed, not merged). Four files: the new wire-level E2E test, the `-DraftPublishLive` dev switch,
and the two 7F-2A work-order documents. Diff reviewed for credentials, tokens, `.env`, private activity
data, build output and IDE files — clean. No production code, no migration.

## 2. `INTERVALS_ATHLETE_ID`

Unchanged and untouched: still unset in the canonical `.env`, so `running-ai.intervals.athlete-id`
resolves to the literal `"0"`. The new preflight script hard-constrains it with
`[ValidateSet('0')]` so it cannot even be *inspected* under another value by accident (risk R1).

## 3. Legacy PowerShell publishing — read-only verification → **CLEARED**

Only two legacy scripts write Intervals calendar events (`Select-String` over `C:\running-ai\scripts\*.ps1`
for `athlete/.../events`): `create-today-workout.ps1` and `command-channel-worker.ps1`. Every scheduled
task that can reach either of them is disabled:

| task | state |
|---|---|
| `RunningAI-TodayWorkout` (→ `create-today-workout.ps1`) | **Disabled** |
| `RunningAI-CommandChannel` (→ `command-channel-worker.ps1`) | **Disabled** |
| `RunningAI-TrainingCommand` | **Disabled** |
| `RunningAI-RemoteWakeupScheduler` | **Disabled** |

The legacy tasks that *are* enabled were each traced to their script and none can publish:
`process-training-decisions` (no Intervals reference; dispatches only `approve-/reject-training-command.ps1`,
both of which are 5–6 line stubs with no write verb), `check-new-run` (8 Intervals references, **zero**
write verbs — it reads activities), `create-daily-brief`, `run-chatgpt-pipeline`,
`process-ntfy-decision-actions`, `create-weekly-report` (no event writes). `RunningAI-CommandBridge`
only `spawnSync`s `validate-training-command.ps1` and enqueues; the worker that would execute the queue
is one of the disabled tasks above. No such process is running.

**Conclusion: no enabled legacy path can write the Intervals calendar, so 7F-2A risk R2 is closed and
this phase did not have to halt.** Nothing was disabled or modified by this work order — the check was
read-only, as instructed.

## 4. Isolated dev instance — ready

| | production | 7F-2B dev instance |
|---|---|---|
| port | 18080 | **18083** (loopback only, `127.0.0.1`) |
| database | `running_ai` | **`running_ai_7f2`** (fresh `pg_dump` copy; production was only read) |
| draft publishing | `false` | **`true`** |
| legacy date publishing | `false` | `false` (so `PublishingModeGuard` is satisfied) |
| publishing scheduler | `false` | `false` |
| Garmin schedulers | — | `false`, connector pointed at a dead port |
| Intervals | live key | live key (`-IntervalsLive`, required by `-DraftPublishLive`) |

Copy fidelity: both databases report `48 activities / 19 drafts / 3 publications / 0 injury reports`,
Flyway `26`. Production verified untouched afterwards (`running_ai` still 3 publications, 18080 still UP).

Switch states verified **behaviourally**, not from config:

- `POST /api/v1/workout-drafts/999999/publish` → `404 WORKOUT_DRAFT_NOT_FOUND`, i.e. the master switch is
  on (it would otherwise return `409 DRAFT_PUBLISHING_DISABLED` first). No external call, no state change.
- `POST /api/v1/workout-publish` → `409 WORKOUT_PUBLISHING_DISABLED`: the legacy date-based path stays off.

## 5. Injury gate — untouched, and still the blocker

`GET /api/v1/athlete/injury-state` on the dev instance returns
`nonRestTrainingAllowed = false`, `NO_INJURY_STATE_RECORDED`. The dev database carries production's
`injury_report` table verbatim: **0 rows**. Nothing was seeded, stubbed, assumed or bypassed.

Until the athlete records their own current status, every non-REST approval and publish returns `422`,
and the coach will correctly produce REST. **If the athlete's real status is not RESOLVED, the publish
does not happen** — that is the correct outcome, not a failure.

## 6. Pre-publish calendar re-check — ready

`scripts/dev/preflight-intervals-date.ps1` (new). Read-only by construction: it has no create, update or
delete path at all. It issues the *same* one-day `category=WORKOUT` GET the publisher issues first, then
classifies each event exactly as `IntervalsWorkoutPublisher` does (marker match → OWNED, blank
`external_id` + `[RunningAI-Control]` → OWNED-legacy, otherwise FOREIGN) and predicts the operation a
publish would perform. The prediction is explicitly not a promise: the publisher re-lists the date itself
and that is the authority. The API key is read into memory only and never printed; the athlete id prints
masked.

Verified both ways:

```
-Date 2026-10-15 -> 0 events                      -> predicts CREATED            (exit 0)
-Date 2026-10-05 -> 1 event, OWNED(marker)        -> predicts NO_CHANGE/UPDATED  (exit 1)
```

Two bugs were found and fixed while verifying it, both of which would have *misreported a date as
occupied or empty*: `-like '*[RunningAI-Control]*'` treats `[...]` as a PowerShell wildcard class (now a
literal `.Contains`), and an empty JSON array arrives from `Invoke-RestMethod` as `$null`, which `@(...)`
turns into a one-element array (now filtered). This is why the script was tested against a known-occupied
date rather than only an empty one.

**2026-10-15 is confirmed empty** (`category=WORKOUT`), so the planned publish still exercises the
never-yet-live `CREATED` branch.

## 7. Remaining blockers

| # | blocker | who can clear it |
|---|---|---|
| **B1** | **No injury/pain status on file** (0 rows). All non-REST approve/publish is refused `422`. | **The athlete only** — a genuine self-report via `POST /api/v1/athlete/injury-reports`. Must be within the 48 h freshness window at *both* approve and publish time. |
| **B2** | The coach must actually return a non-REST session for 2026-10-15. | The coach. If it returns REST, the publish is held, per instruction 6. |
| **B3** | Explicit approval for the single external write. | The owner. |

Not blockers, but unexecuted by instruction 8: real publish, OAuth scope change, Worker deploy,
production restart, merge to canonical `main`, `origin` push.

## 8. Remaining sequence once B1–B3 clear

```powershell
# 1. the athlete records their own current status on 18083 (not seeded by anyone else)
Invoke-RestMethod -Method Post -Uri http://127.0.0.1:18083/api/v1/athlete/injury-reports `
  -ContentType 'application/json' -Body '{"status":"RESOLVED","painPresent":false,"notes":"7F-2B validation"}'

# 2. draft (the coach decides; hold if it returns REST)
$d = Invoke-RestMethod -Method Post -Uri http://127.0.0.1:18083/api/v1/workout-drafts `
  -ContentType 'application/json' `
  -Body '{"date":"2026-10-15","availableMinutes":30,"requestedGoal":"short recovery run to validate the publishing path"}'

# 3. approve + preview exactly what would be sent (zero external calls)
Invoke-RestMethod -Method Post -Uri "http://127.0.0.1:18083/api/v1/workout-drafts/$($d.id)/approve"
Invoke-RestMethod -Uri "http://127.0.0.1:18083/api/v1/workout-drafts/$($d.id)/publish-preview"

# 4. re-check the calendar IMMEDIATELY before the write
scripts\dev\preflight-intervals-date.ps1 -Date 2026-10-15     # must predict CREATED

# 5. >>> the only external write <<<   (awaiting approval)
# Invoke-RestMethod -Method Post -Uri "http://127.0.0.1:18083/api/v1/workout-drafts/$($d.id)/publish"

# 6. stop the publish-capable instance afterwards
scripts\dev\start-dev-spring.ps1 -Port 18083 -Database running_ai_7f2 -Stop
```

Architecture unchanged: Modular Monolith, single-tenant self-hosted. No new module, no new external
dependency, no refactoring.
