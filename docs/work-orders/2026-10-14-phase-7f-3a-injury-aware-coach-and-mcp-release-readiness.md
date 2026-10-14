# Phase 7F-3A — Injury-aware AI coach, and the readiness file for enabling the ChatGPT MCP write tools

Date: 2026-10-14 (KST) · Branch `main-3` · base HEAD `30113c2`
Scope: **one minimal code change** (the coach learns the injury history), its regression tests, a
read-only re-verification of the 7F-2F publish, and the production-activation plan for 7F-3B.

**No new Intervals publish. No production DB write. No production restart. No OAuth/worker change.
No `origin` push. No merge into canonical `main`.**

---

## 1 — How the AI coach is now told about injury history

### The gap this closes

The Phase 7B-2 safety gate talks to the coach **only while it is blocking**:
`WorkoutSafetyGateService.constraintText(decision)` returns `null` the moment
`nonRestTrainingAllowed` is true. So as soon as the athlete reported `RESOLVED`, the coach designed
the session with **no signal at all** that there had been an injury — not the body site, not the
symptoms, not how recently. A knee that was `ACTIVE` with gait pain three days ago looked exactly
like an athlete who had never reported anything.

### The change

A new section, `injuryHistory`, declared on `CoachTrainingContext` itself — so it is carried by
**both V1 and V2**, because the gap exists in both (production runs V2; the source default is V1).

| file | role |
|---|---|
| `coach/InjuryHistoryContext.kt` | the model: `InjuryHistoryContext`, `InjuryGateContext`, `InjuryReportContext` |
| `coach/InjuryHistoryContextBuilder.kt` | the builder + `InjuryHistoryContextProperties` (caps) |
| `coach/CoachTrainingContext.kt` | `val injuryHistory: InjuryHistoryContext?` on the interface |
| `coach/TrainingContext.kt`, `coach/TrainingContextV2.kt` | the field, defaulted to `null` |
| `coach/TrainingContextBuilder.kt`, `coach/TrainingContextV2Builder.kt` | both populate it |
| `coach/claude/ClaudeCoachPromptBuilder.kt` | a `Reading 'injuryHistory'` block in the system prompt |
| `application.yml` | `running-ai.coach.injury-history.*` |

What it carries:

```
injuryHistory
├─ gate                        nonRestTrainingAllowed · reasonCode · message · stalenessHours
├─ totalReportCount            the whole ledger
├─ historyWindowDays           listing window (default 180)
├─ reportsInWindow
├─ omittedReportCount          what the window/cap left out — counted, never silently dropped
├─ latest                      the report the gate evaluated (null only when none ever existed)
├─ previousReports[]           older ones, newest first, capped (default 4)
└─ daysSinceLatestActiveReport computed over the WHOLE ledger, window and caps ignored
```

and per report:

```
status · reportedAt · recordedAt · ageHours
bodySite · side · painPresent · painIntensity · painOnWalking · painOnStairs
swelling · rangeOfMotionLimited
notesPresent · notesExcerpt · notesTruncated
```

### The rules it keeps (each one is a test)

| requirement | how it is met |
|---|---|
| ACTIVE / UNKNOWN / RESOLVED distinguished | `status` verbatim from the ledger, plus the gate's own `reasonCode` |
| report time and elapsed time | `reportedAt`, `recordedAt`, `ageHours` |
| body site, pain, gait pain, confirmed recovery | all structured symptom fields carried as reported |
| unreported items read as UNKNOWN | the field stays `null`; the prompt says explicitly that a null is **unreported, never "no symptom"** |
| past pain is not erased by a RESOLVED | append-only ledger is read in sequence; `previousReports` keeps the ACTIVE row, and `daysSinceLatestActiveReport` searches the **whole** ledger so a flare-up that scrolled past the cap is still reported |
| no medical-clearance judgement is produced | nothing in the data says "healed"; the prompt forbids the coach from declaring it, naming a cause, or saying the athlete is cleared |
| raw `notes` never dumped wholesale | excerpt for **`latest` only**, truncated at `max-notes-excerpt-chars` (default 200, `0` disables free text entirely); older reports get `notesPresent` only |
| prompt length bounded, structure safe | fixed caps, no unbounded list, and the existing 64 KiB `CoachContextSerializer` guard still runs **before** any coach call |

### What the prompt now says

A new `Reading 'injuryHistory'` section in `SYSTEM_PROMPT`, whose load-bearing sentences are:

> status RESOLVED means exactly one thing: as of 'reportedAt', the athlete says the symptom is gone.
> It is the athlete's latest self-report and a data permission, NOT a medical clearance and NOT a
> reason to automatically resume hard training.

> 'daysSinceLatestActiveReport' … When it is small, today's session is a RETURN TO TRAINING after a
> recent injury: treat it as one, rebuild load deliberately rather than picking up where the
> training was interrupted, and say so in the rationale.

> Nobody in this system is qualified to decide whether an injury has healed, and neither are you:
> never declare the athlete healed, never name a cause or a condition, and never tell them they are
> cleared to train.

(The existing assertion that the system prompt contains no `"` character — it is passed as a CLI
argument — still holds; the new section uses single quotes only.)

### Two design decisions worth recording

1. **One ledger read, one policy evaluation.** `InjuryHistoryContextBuilder` reads the ledger once
   and evaluates `WorkoutSafetyPolicy` against *that* read, rather than calling
   `WorkoutSafetyGateService.currentDecision()` separately. The verdict the coach is shown therefore
   describes exactly the reports listed beside it. Enforcement is unaffected: `WorkoutDraftService`
   still asks the real gate, independently, before **and** after the coach call.
2. **Hour granularity, and no `evaluatedAt`.** Every age is whole hours and no wall-clock instant is
   stored in the context, so two drafts generated in the same hour still serialize to identical
   bytes and `context_sha256` keeps meaning *the evidence changed*, not *time passed*.

---

## 2 — Is the existing injury gate unchanged?

**Yes — byte-for-byte.** No file under `com.runningai.injury` was modified in this phase:

```
$ git diff --stat -- server/src/main/kotlin/com/runningai/injury/
(no output)
```

`InjuryStateService`, `WorkoutSafetyPolicy`, `WorkoutSafetyGateService`, `InjurySafetyProperties`,
`InjuryReportEntity`, `InjuryStateController`, `V21__create_injury_report.sql` — all untouched. No
migration was added anywhere in this phase.

The direction of the change is one-way: the gate does not read `injuryHistory`, and
`injuryHistory` cannot widen the gate. It is **information for the coach, never permission** —
asserted directly by `the injury history never widens the gate`, which keeps a blocking
`INJURY_ACTIVE` verdict while the history is fully populated.

---

## 3 — State-by-state regression results

New suite `CoachInjuryHistoryContextTest` — **13/13 PASS**, against the real schema, the real
`InjuryStateService` and the real `WorkoutSafetyPolicy` (only the AI is faked; no Claude CLI call).

| # | requested state | test | result |
|---|---|---|---|
| 1 | no injury report | `no injury report has ever been made` | PASS — `latest=null`, `NO_INJURY_STATE_RECORDED`, `daysSinceLatestActiveReport=null` |
| 2 | ACTIVE | `an ACTIVE report is carried with its symptoms and blocks non-REST training` | PASS — site/side/pain/intensity/gait-pain carried, `INJURY_ACTIVE`, blocked |
| 3 | UNKNOWN | `an UNKNOWN report is carried as UNKNOWN, never as an all-clear` | PASS — `INJURY_STATE_UNKNOWN`, no fabricated return-to-training date |
| 4 | RESOLVED | `a fresh RESOLVED report still reaches the coach…` | PASS — gate permits **and** the report is still handed over |
| 5 | STALE | `a stale report is reported with its real age, not dropped or refreshed` | PASS — `INJURY_STATE_STALE`, `ageHours=72`, `stalenessHours=48` |
| 6 | RESOLVED after a recent ACTIVE | `…shows the sequence and the days since the injury` | PASS — `previousReports[0]=ACTIVE`, `daysSinceLatestActiveReport=3`, training permitted |
| 7 | unreported items null | `an item the athlete did not report stays null rather than reading as no symptom` | PASS — all eight symptom fields null |
| 8 | gate block not bypassable | `the injury history never widens the gate` (+ the four pre-existing 7B-2 tests) | PASS — `WORKOUT_SAFETY_INJURY_ACTIVE`, 0 drafts stored |
| 9 | no TrainingContext regression | `both production context builders populate the injury history`, `…survives serialization…`, plus the whole existing coach suite | PASS |
| 10 | REST decision and draft-publish flow | `WorkoutDraftSafetyGateTest` (10/10), `DraftPublishApiTest`, `DraftPublishMockE2ETest`, `WorkoutDraftApiTest`, `WorkoutDraftApprovalTest` | PASS |

Extra coverage beyond the requested list:

| test | what it proves |
|---|---|
| `a long note is capped and flagged, and the part beyond the cap never reaches the snapshot` | text past `max-notes-excerpt-chars` is absent from the serialized context — the prompt cannot leak it |
| `only the latest report carries free text at all` | older reports expose `notesPresent` but no excerpt |
| `history is capped, and what was left out is counted rather than hidden` | 6 reports → latest + 2 listed, `omittedReportCount=3` |
| `an ACTIVE report older than the listing window is still reported as the injury being returned from` | ACTIVE 60 days back, outside the 30-day window → not listed, still `daysSinceLatestActiveReport=60` |

`WorkoutDraftSafetyGateTest` grew from 7 to **10** tests (3 added end-to-end over HTTP, through the
real controller, gate and context builder).

**Real Claude CLI: not invoked.** Every test uses the existing `FakeAiCoach`. A one-off live CLI
check is listed as an optional pre-step in §9 (it is a coach-quality observation, not a correctness
gate, and it is the kind of check that belongs immediately before the production cutover).

---

## 4 — Full test results

| suite | command | result |
|---|---|---|
| Server (all) | `gradlew -p server clean test` | **BUILD SUCCESSFUL** — 147 classes, **1442 tests, 0 failures, 0 errors, 0 skipped** (2 m 54 s) |
| MCP gateway | `node test/gateway-test.js` (`tools/mcp-gateway`) | **46/46 checks passed** |

Gateway checks that matter for this phase, all PASS: read-only token sees no training tool;
read-only token calling `create_workout_draft` / `approve_workout_draft` /
`publish_approved_draft` → **403 `insufficient_scope`, not proxied**; scopes **not hierarchical**
(a train token alone cannot publish); `publish_workout` unreachable with every scope; a foreign
subject refused however many scopes it carries; JWKS outage → **503, never a pass**;
`publish_workout` on the allowlist **refuses to start**.

Migration: **none**. The schema is unchanged at V26.

---

## 5 — Intervals result frozen, and Garmin delivery re-checked (read-only)

### 5.1 The 7F-2F regression baseline

| item | value |
|---|---|
| account | **i6\*\*\*48** (Asia/Seoul) |
| date | **2026-10-16** |
| marker (`external_id`) | **`runningai:workout:v1:0:2026-10-16`** |
| Intervals event id | **144442046** |
| dev DB (`running_ai_7f2`) | draft **21** · approval **5** · publication row **4** |
| external writes | **1 POST** (`CREATED`) |
| readback | **PASS** |
| idempotency | **PASS** — re-call returned the stored row, 0 further external calls |

### 5.2 Re-verified today, read-only (GET only; the script has no write path)

| # | check | observed 2026-10-14 09:39 KST |
|---|---|---|
| 1 | event 144442046 exists | **yes** — `start_date_local` 2026-10-16T00:00:00, WORKOUT / Run |
| 2 | marker intact | `external_id` = `runningai:workout:v1:0:2026-10-16` |
| 3 | content intact | `moving_time` 1800 s, **3 steps**, description byte-identical to the approved text |
| 4 | WORKOUT events on the date | **1** (owned, no foreign event) |
| 5 | events 2026-10-13..10-20, any category | **1** — the published one, nothing else |
| 6 | account / athlete id | i6\*\*\*48 / **`0` — unchanged, `INTERVALS_ATHLETE_ID` not touched** |

Nothing was created, updated or deleted. The event is exactly as 7F-2F left it.

### 5.3 Garmin delivery

| field (Intervals athlete settings, read-only) | value |
|---|---|
| `icu_garmin_upload_workouts` | **true** |
| `icu_garmin_training` | **true** |
| `icu_garmin_last_upload` | **2026-10-13T23:57:44.436+00:00** |

That timestamp is **08:57:44.436 KST — the instant the event was created**, and it has **not moved
since**, which is consistent with exactly one upload for exactly one event.

**What that does and does not prove.** It proves the Intervals→Garmin upload leg *fired*. It is
**not** proof that the workout is visible in Garmin Connect or on the FR265, and this phase does not
claim it is. RunningAI structurally cannot check: the Garmin connector
(`tools/garmin-connector/garmin_connector/api.py`) exposes only `/health`, `/activities`,
`/activities/{id}/…`, `/lactate-threshold` and `/recovery` — **there is no planned-workout read
endpoint anywhere in this repository**, and this repo has no Garmin write path at all.

> **For the athlete to confirm, by hand:** open Garmin Connect → Training & Planning → Workouts /
> Calendar, 2026-10-16, and look for a ~30-minute run with 5 / 20 / 5-minute steps. Then check the
> FR265 after a watch sync (Garmin Connect app → sync, or wait for the watch's own sync).
> If it is missing, the thing to check first is the Garmin ↔ Intervals.icu connection in Intervals'
> own settings — not RunningAI, which has already done everything it does.

**Per the instruction: the Intervals publish stays PASS regardless of what the device shows.** The
Intervals result is verified against the Intervals API; Garmin display is a separate, downstream
system.

### 5.4 Protecting 2026-10-16 during the production cutover

Production has **no record of this publish** (dev DB only), so the risk to name is: a production
instance deciding, after the cutover, to publish 2026-10-16 again or take over event 144442046.
It cannot, and the reasons are independent of each other:

| # | control | production state, verified read-only today |
|---|---|---|
| 1 | injury gate | `GET /api/v1/athlete/injury-state` → `NO_INJURY_STATE_RECORDED`, `nonRestTrainingAllowed=false`. **0 injury reports** → every non-REST path is refused before anything else runs |
| 2 | draft publishing switch | `RUNNING_AI_DRAFT_PUBLISHING_ENABLED=false` |
| 3 | legacy publishing switch | `WORKOUT_PUBLISHING_ENABLED=false` |
| 4 | legacy scheduler | `WORKOUT_PUBLISHING_SCHEDULER_ENABLED=false` (and it has no catch-up for a missed run) |
| 5 | no target in production | `running_ai` holds **no draft and no approval for 2026-10-16**, so `POST .../{id}/publish` has nothing to aim at that date |
| 6 | MCP write tools | `RUNNINGAI_MCP_TRAIN_ENABLED` is **absent from `.env`** → false; `RUNNINGAI_MCP_ENABLED=false` |
| 7 | OAuth | the worker filters granted scopes to `['runningai:read','offline_access']`, so no train/publish token can exist |
| 8 | legacy PowerShell writers | `RunningAI-TodayWorkout` / `-CommandChannel` / `-TrainingCommand` / `-RemoteWakeupScheduler` all **Disabled** (7F-2F, unchanged) |

**No code-level date guard was added, deliberately.** Hard-coding `2026-10-16` into the publisher
would be a permanent special case for a one-off calendar fact, and it would not even be the
narrowest control available — controls 1–5 already make the publish impossible, and control 5 makes
it impossible *specifically for that date*. The protection for 7F-3B is therefore a **pre-condition**
(§9, step 0): before any production publishing switch is turned on, assert that `running_ai` holds
no approved draft for 2026-10-16. If one is ever wanted there, that becomes its own authorised phase
with its own preflight — the existing `scripts\dev\preflight-intervals-date.ps1` would then report
the date as **OWNED**, and the publisher's own marker lookup would make it an `UPDATE`/`NO_CHANGE`,
never a second event.

**Not done, and not to be done:** no dev→production data migration, no SQL that manufactures a
published state, no production injury report, no `INTERVALS_ATHLETE_ID` change.

---

## 6 — ChatGPT MCP production-activation readiness

| # | item | state | evidence |
|---|---|---|---|
| 1 | 7 read tools, no regression | **READY** | `get_recent_activities`, `get_activity_analysis`, `get_training_context`, `get_recovery_context`, `get_fitness_trend`, `get_workout_recommendation`, `get_athlete_profile` — full server suite green; gateway's read-only-compat checks PASS |
| 2 | 6 train/publish tools | **READY, OFF** | `create_workout_draft`, `get_workout_draft`, `revise_workout_draft`, `approve_workout_draft`, `preview_draft_publish`, `publish_approved_draft`; `RUNNINGAI_MCP_TRAIN_ENABLED` unset in production |
| 3 | per-tool scope control | **READY** | hard-coded `TOOL_SCOPES` in `tools/mcp-gateway/server.js`; 46/46 gateway checks, incl. non-hierarchy and 403-before-backend |
| 4 | gateway allowlist / fail-closed | **READY** | running gateway advertises `scopes_supported: ["runningai:read"]` only; unauthenticated `tools/list` → **401** with `WWW-Authenticate`; JWKS outage → 503 |
| 5 | OAuth worker scopes | **BLOCKER (by design)** | `tools/mcp-worker/src/index.js`: `SCOPES_SUPPORTED = ['runningai:read','offline_access']` — a train/publish token **cannot be issued**. Widening + deploy is 7F-3B, not this phase |
| 6 | publish refused before approval | **READY** | `ApprovedWorkoutDraftPublishService` requires an approval row; `publish_approved_draft` additionally requires `confirmedByAthlete:true` + `acknowledgedApprovalId` |
| 7 | production injury report missing ⇒ publish blocked | **CONFIRMED** | live read: `NO_INJURY_STATE_RECORDED` |
| 8 | production Claude CLI | **PRESENT, UNVERIFIED-IN-SERVICE** | `claude.exe` resolves on PATH (`…\WinGet\Packages\Anthropic.ClaudeCode_…`); production config leaves `RUNNING_AI_COACH_CLAUDE_COMMAND` at its default `claude`. 7F-2F recorded **19 drafts** in the production DB, so the CLI has worked in service before; no coach call was made against the current jar in this phase. **Re-verify after the 7F-3B restart** |
| 9 | production released JAR | **STALE for this phase** | `.runtime/running-ai-server.jar.json`: `gitCommit 799e3b5`, `gitDirty false`, `migrationCount 26`. It predates 7F-1 (`4ebfad5`) **and** 7F-3A → a new release is required |
| 10 | git consistency | **CLEAN** | canonical `main` = `799e3b5`; `main-3` is **3 ahead, 0 behind** before this phase's commits — a fast-forward merge, no rebase or conflict |
| 11 | deploy / restart / rollback order | **DOCUMENTED** | §9 |
| 12 | ChatGPT connector re-consent | **REQUIRED** | widening the worker's scopes changes the consent set, so the existing connector must be disconnected and re-authorised to obtain a token carrying `runningai:train` / `runningai:publish` |

### Two findings to carry into 7F-3B

**(a) The production gateway runs from the `main-3` worktree.** PID 48104 is
`node … \orca\workspaces\running-ai-github\main-3\tools\mcp-gateway\server.js` — the *development*
worktree, not `C:\running-ai-github`. So an edit to that file changes production behaviour at its
next restart, with no release step in between. Nothing in this phase touched it. 7F-3B should either
move the gateway to the canonical checkout or record this dependency explicitly in the runbook.

**(b) `injuryHistory` will become visible to the existing read scope.** `get_training_context`
returns the whole serialized context, so once 7F-3A is deployed a `runningai:read` token sees the
injury ledger summary — including the ≤200-character `notesExcerpt`. This is the athlete's own data
reaching the athlete's own single-subject, OAuth-gated connector, and the gate message was already
exposed through `get_workout_recommendation`'s `injurySafety` block. It is still a widened personal
surface and should be a deliberate choice, not a surprise. If free text should never leave the host,
set `RUNNING_AI_COACH_INJURY_HISTORY_MAX_NOTES_CHARS=0` — the structured symptom fields are
unaffected.

### On production's 0 injury reports

Not touched, and not to be worked around. Production's gate is closed because no self-report exists
there, and that is correct behaviour, not a defect. Recording one is **the athlete's own act**,
performed through the normal API (`POST /api/v1/athlete/injury-reports`) with their own words — §9
step 4 makes it an explicit, athlete-performed gate in the cutover, never something this system
writes on their behalf and never a copy of the dev DB's rows.

---

## 7 — Architecture

Unchanged: **Modular Monolith + single-tenant self-hosted**. One PostgreSQL per instance, one
Garmin/Intervals connection per instance, no external account id or key hard-coded anywhere (the
Intervals athlete id stays the configured `"0"`, the API key comes from `INTERVALS_API_KEY` only),
`Athlete` domain intact, existing services reused — `InjuryHistoryContextBuilder` adds no new
transport, no new table and no new external dependency. No microservice split, no installer
packaging, no multi-user feature.

---

## 8 — Changed files and commits

| file | change |
|---|---|
| `server/.../coach/InjuryHistoryContext.kt` | **new** — model |
| `server/.../coach/InjuryHistoryContextBuilder.kt` | **new** — builder + caps properties |
| `server/.../coach/CoachTrainingContext.kt` | `injuryHistory` on the interface |
| `server/.../coach/TrainingContext.kt` | field (defaulted null) |
| `server/.../coach/TrainingContextV2.kt` | field (defaulted null) |
| `server/.../coach/TrainingContextBuilder.kt` | populates it |
| `server/.../coach/TrainingContextV2Builder.kt` | populates it |
| `server/.../coach/claude/ClaudeCoachPromptBuilder.kt` | `Reading 'injuryHistory'` prompt section |
| `server/src/main/resources/application.yml` | `running-ai.coach.injury-history.*` |
| `server/.../test/.../CoachInjuryHistoryContextTest.kt` | **new** — 13 tests |
| `server/.../test/.../WorkoutDraftSafetyGateTest.kt` | +3 end-to-end tests, `report()` gains `ageHours` |
| `server/.../test/.../CoachJavaInteropTest.java` | explicit `null` (Kotlin defaults are invisible to Java) |
| `CLAUDE.md` | new invariant bullet for the 7B-2 gate + 7F-3A history; 7F-1 bullet updated with the real 7F-2F publish |
| `docs/architecture/training-context-v2.md` | provenance row + an "Injury history" section |
| `docs/work-orders/2026-10-14-phase-7f-3a-*` | this report + the verbatim instruction |

**Nothing under `com.runningai.injury` changed. No migration. No new dependency.**

Commits on `main-3` (not pushed, not merged):

- `05c32d7` docs: Phase 7F-2C..2F work orders - injury gate through the first real Intervals publish
- `HEAD` (this phase's code + docs) feat(coach): give the AI coach the injury history, not only a blocking gate

Branch `main-3` is now **5 commits ahead of canonical `main` (`799e3b5`), 0 behind**.

---

## 9 — Phase 7F-3B: production cutover order

Each step is reversible at the point it is taken; the rollback for every step is to undo it in
reverse order. **No step is authorised by this phase.**

**0 — Pre-conditions (all read-only; stop if any fails)**
   a. `running_ai` holds **no approved draft for 2026-10-16** (the §5.4 protection).
   b. The four publishing switches and both Garmin schedulers are still `false` in production.
   c. Legacy PowerShell publish tasks still **Disabled**.
   d. Intervals: `scripts\dev\preflight-intervals-date.ps1 -Date <target>` for whatever date will
      actually be published — **not** 2026-10-16 unless that is separately authorised.

**1 — Git**  Merge `main-3` into canonical `main` with `--ff-only` (verified fast-forwardable:
3 ahead / 0 behind before this phase). Push only if the athlete asks for it.

**2 — Release the jar**  Build from canonical `main`, release into `.runtime` with its
`.json` provenance, and confirm `gitCommit` matches `main` and `gitDirty=false`. Keep the previous
jar — it **is** the rollback.

**3 — Restart production Spring (18080)** with V21–V26 already applied (no new migration here).
Verify: `/api/v1/health` UP, the new `gitCommit` in the provenance file, `injury-state` still
`NO_INJURY_STATE_RECORDED`, and `GET /api/v1/coach/training-context?date=<today>&version=V2` now
returning an `injuryHistory` section. **Rollback: restore the previous jar and restart.**

**4 — The athlete records their current injury status** — by hand, in their own words, through
`POST /api/v1/athlete/injury-reports`. Nobody and nothing else does this, and no dev row is copied.
Until this exists, production cannot produce any non-REST workout, by design.

**5 — Optional, recommended: one live Claude CLI check.** `POST /api/v1/workout-drafts` for a date
on production and read the draft's rationale: does it actually mention the return-to-training
context now that `injuryHistory` is populated? This is a quality observation, not a gate — a
disappointing rationale is a prompt-tuning item, not a reason to stop. Also confirms finding 6/8
(the CLI works in service under the new jar).

**6 — Enable the MCP write surface, one switch at a time**, verifying after each:
   a. `RUNNINGAI_MCP_TRAIN_ENABLED=true` → restart → `tools/list` on the **local** MCP shows the 6
      train tools. (`RUNNING_AI_DRAFT_PUBLISHING_ENABLED` stays false for now: draft create / revise
      / approve / preview all work with it off.)
   b. Gateway: add the train tools to `RUNNING_AI_ALLOWED_TOOLS` → restart → metadata advertises
      `runningai:train`, and an existing read-only token still sees **no** train tool.
   c. Worker: widen `SCOPES_SUPPORTED` → deploy → ChatGPT connector **disconnect and re-authorise**,
      consenting to the new scopes.
   d. End-to-end through ChatGPT, publishing nothing: create → revise → approve (with
      `confirmedByAthlete` + `acknowledgedVersion`) → `preview_draft_publish`.

**7 — Only then, publishing.** `RUNNING_AI_DRAFT_PUBLISHING_ENABLED=true` (never together with
`WORKOUT_PUBLISHING_ENABLED` — `PublishingModeGuard` fails startup), gateway allowlist gains
`publish_approved_draft`, worker grants `runningai:publish`, re-consent again. Preflight the target
date, then exactly one `publish_approved_draft` with `confirmedByAthlete` +
`acknowledgedApprovalId`, then read the event back from Intervals.
**Rollback: set the switch false and restart — a published event is not auto-deleted; removing one
is a separate, explicit decision.**

**Rollback summary**

| step | undo |
|---|---|
| 7 / 6a / 6b | switch or allowlist back to false/read-only, restart that process |
| 6c | redeploy the worker with the narrow `SCOPES_SUPPORTED`; existing wide tokens stop being accepted by the gateway's scope check |
| 3 / 2 | restore the previous `.runtime` jar (`799e3b5`) and restart — schema V26 serves both |
| 1 | `main` is only fast-forwarded; `git reset --hard 799e3b5` on an unpushed merge |

---

## 10 — Verdict

**PASS.** The coach now receives the athlete's injury history in every context version, bounded and
structured, with free text capped and `RESOLVED` explicitly framed as a self-report rather than a
medical clearance. The Phase 7B-2 gate is untouched and still decides alone. 1442 server tests and
46 gateway checks are green, the 7F-2F event was re-verified read-only and is unchanged, and the
production write surface remains blocked by seven independent controls — the OAuth worker's scope
filter and production's empty injury ledger foremost among them.

**STOP here.** Nothing in §9 is authorised by this phase.
