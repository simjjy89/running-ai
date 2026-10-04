# Phase 6H-8 — Safe Coach CLI / Operator Workflow — Result

Baseline SHA: `8c68c1d82be96a31fb676a010bcf29dc4c38e3d5` (Phase 6H-7.2 result)
Final SHA: (pending commit in this worktree; not yet merged to the live checkout)

## Server production code changed

None. The CLI uses only the six existing `/api/v1/workout-drafts*` endpoints; no new server
endpoint, no `application.yml` change, no Kotlin/Java change.

## DB migration

None.

## Operator script

`scripts/windows/running-ai-coach.ps1` (new, thin entry point) + `scripts/windows/RunningAI.CoachOperator.ps1`
(new, shared testable functions: `Invoke-CoachOperatorSession`, `Invoke-RunningAiControlledPublish`,
`Show-RunningAiWorkoutDraft`/`Show-RunningAiApproval`/`Show-RunningAiPublishPreview`,
`Format-RunningAiSegmentTarget`, `Format-RunningAiErrorMessage`, `Test-RunningAiPreviewUnchanged`,
`Get-RunningAiPreviewHash`). Generate (`-Date`/`-AvailableMinutes`/`-Environment`/`-Goal`/`-GoalFile`/
`-UserFeedback`/`-PainOrFatigueFeedback`) and resume (`-DraftId`) are mutually exclusive (checked
before any HTTP call). `-StartIfNeeded` calls only `start-running-ai.ps1`. `-NonInteractive`
generates/resumes/displays and nothing else - it never reaches the approve or publish gate.

## Controlled publish script

`scripts/windows/publish-approved-draft-controlled.ps1` - now a **tracked**, ~40-line thin wrapper
over the same `Invoke-RunningAiControlledPublish` function `running-ai-coach.ps1` calls after an
approval, same invocation shape as the earlier untracked version (`-DraftId`, `-BaseUrl`). The old
Korean safety-prompt literals ("이제 실제 Intervals.icu WRITE가 발생합니다.", "실제로 publish하려면
정확히 YES 입력") are replaced by the ASCII `Type exactly YES to publish` / `Publishing cancelled.`
etc. that now live once in `Invoke-RunningAiControlledPublish`, closing the Phase 6H-7.2 Part E
leftover for this implementation's own text (see Known limitations for the untracked file itself).

## UTF-8 helper reused

Yes, unchanged from Phase 6H-7.2: every HTTP call in the new scripts goes through
`Invoke-RunningAiJsonRequest` / `ConvertTo-RunningAiUtf8JsonBytes` (`RunningAI.Common.ps1`). No
duplicate UTF-8 helper was written in `running-ai-coach.ps1`, `RunningAI.CoachOperator.ps1` or
`publish-approved-draft-controlled.ps1`.

## Request byte handling

Unchanged: `Write-Output -NoEnumerate` in `ConvertTo-RunningAiUtf8JsonBytes` (verified still in
place; Test-RunningAI.ps1's byte-unrolling regression still passes).

## Response byte handling

Unchanged: `Invoke-RunningAiJsonRequest` still decodes every response via
`Invoke-WebRequest` + `RawContentStream` + explicit `System.Text.Encoding::UTF8`, never via
`Invoke-RestMethod`'s own parsing.

## Generate

Implemented. `POST /api/v1/workout-drafts` with any combination of the listed fields; `-GoalFile`
reads a UTF-8 text file (explicit BOM strip + `Encoding.UTF8.GetString`, not PowerShell's own
encoding auto-detection).

## Resume

Implemented. `-DraftId` does `GET /api/v1/workout-drafts/{id}` instead of generating. A resumed
`SUPERSEDED` draft is reported and the session ends (no menu - nothing actionable on a superseded
version). A resumed `APPROVED` draft calls `POST /approve` again (idempotent - "returns the existing
approval unchanged", not a new human decision) purely to obtain a fresh `approvalId`/`approvedAt` to
display, then proceeds straight into the publish-preview flow.

## Revision

Implemented. `[R]` prompts for free text, sends it as `POST /revisions`, and the loop re-displays
the new version. Unlimited manual revisions; the coach is never called automatically in a loop.
Korean round-trips through this path exactly as it does through the generate path (same UTF-8
helper) - verified both by `Test-CoachOperator.ps1`'s scripted-Korean-revision check and, live, on
the already-running server (see Live smoke).

## Approve gate

Implemented as an exact-string gate: `Type APPROVE to approve Draft #<id>` accepts only the literal
`APPROVE` (case-sensitive `-cne` comparison); `Y`/`yes`/`Yes`/empty/`NO` all cancel with zero
`/approve` calls. Verified by `Test-CoachOperator.ps1`'s loop over all five rejected answers plus
the accepting case, against a local fake server that would 500 (and fail the test) on any
out-of-sequence call.

## Preview

Implemented: `GET /publish-preview` is called automatically right after every approval (fresh or
resumed), before any publish-switch change, displaying `publishable`/`externalWriteRequired`/
`expectedOutcome`/`structuredStepCount`/`unpublishableReasons`/`publication`/`renderedWorkoutText` in
full.

## Publish gate

Implemented as an exact-string gate, same pattern as approve: `Type exactly YES to publish` accepts
only the literal `YES`; `Y`/`yes`/`Yes`/empty/`NO` all cancel (`Outcome = CANCELLED`) with zero
`/publish` calls and the publish switch never touched. Verified the same way as the approve gate.

## REST behavior

Implemented as a short-circuit that **never calls `POST /publish` at all** for a REST day
(`expectedOutcome == SKIPPED_REST_DAY`): `ApprovedWorkoutDraftPublishService.publish()` gates on the
publish switch unconditionally, even for the REST/`SKIPPED_REST_DAY` path (verified by reading
`ApprovedWorkoutDraftPublishService.kt` directly - the `!properties.enabled` check runs before the
`approved.draft.isRest` check), so calling it would require the same switch-flip/restart as a real
publish for an operation that writes nothing. Per the work order (section 28) this is accepted for
this phase; a future phase can revisit recording the REST publication DB row through this CLI if
that becomes a real requirement.

## Already-published behavior

Implemented: `preview.publication != null` short-circuits before the switch is ever touched - shown,
not re-published.

## Unpublishable behavior

Implemented: `preview.publishable == false` short-circuits with "Draft approved but cannot be
published losslessly. No external write performed." - the CLI never edits the draft itself.

## Preview-before hash

Implemented: `Get-RunningAiPreviewHash` (SHA-256 of `renderedWorkoutText`) computed for both the
pre-restart and post-restart preview; reported in `Test-CoachOperator.ps1`'s TOCTOU check
(`version A` / `version B` fixtures hash to different values, correctly detected).

## Preview-after hash protection

Implemented: `Test-RunningAiPreviewUnchanged` compares `draftId`, `approvalId`, `date`,
`workoutType`, `structuredStepCount` and the hash; any mismatch aborts as
`PREVIEW_CHANGED_AFTER_RESTART` before `POST /publish` is ever called, and the `finally` block still
restores safe mode. Verified by a dedicated check with two different preview fixtures.

## `.env` modified

No. The publish-enabled restart sets the four switches as **process environment variables** only,
then calls the existing `stop-running-ai.ps1`/`start-running-ai.ps1` (which inherit the full parent
environment via `Start-Process`, same mechanism `.env` loading already relies on). `.env` itself is
never opened for writing by any script this phase added or changed.

## Process-env strategy

As above (section 32's preferred approach worked and was used, with no fallback needed): set before
the publish-enabled restart, restored to `false` x4 unconditionally in the `finally` block
regardless of outcome (success, cancellation, hash mismatch, or an exception from the publish call
itself - all four paths were exercised by `Test-CoachOperator.ps1` and all reset the same way).

## Java 21 discovery

Hardened. `Find-RunningAiJava21`/`Get-RunningAiJava21Candidates` (new, `RunningAI.Common.ps1`) add a
fallback chain beyond the previous JAVA_HOME/PATH-only check: current `JAVA_HOME` -> current `PATH`
-> machine-level `JAVA_HOME` -> user-level `JAVA_HOME` -> `Program Files\Java\jdk-21*` (newest-named
first). Each candidate is verified by actually running `java.exe -XshowSettings:properties -version`
via the existing `Invoke-NativeText` helper (PowerShell 5.1 never turns its stderr into a
`NativeCommandError`). `start-running-ai.ps1`'s own duplicate `Find-Java21` function was removed and
replaced with a call to the shared one. Live-verified on this machine: with `JAVA_HOME`/`PATH`/
machine/user `JAVA_HOME` all faked to `$null`, the Program-Files-only fallback alone still found the
real `C:\Program Files\Java\jdk-21.0.2`.

A real bug was found and fixed while building this: the candidate-building loop used `$home` as its
loop variable, which collides with PowerShell's **read-only built-in** `$HOME` automatic variable
(`VariableNotWritable` on first use) - renamed to `$candidateHome`. Caught only by actually running
the function against this machine's real JDK, not by the parse-check or the unit tests (which used
fully-faked candidates that never reached the real-JDK code path).

## Final switch values

`false` / `false` / `false` / `false` after every `Invoke-RunningAiControlledPublish` call in every
scenario tested (success, cancellation at either gate, hash-mismatch abort, unpublishable,
already-published, REST, and a simulated publish failure) - asserted directly in
`Test-CoachOperator.ps1`.

## RunningAI safe-mode health

Not live-verified this session (see Live smoke / Known limitations: no real restart was ever
triggered - `Test-CoachOperator.ps1` uses a no-op `-RuntimeRestarter` by design, and this worktree
never merged to the live checkout). The restart-and-recheck call path itself
(`Restart-RunningAiRuntimeForPublishing`) is the same `stop-running-ai.ps1` -> `start-running-ai.ps1`
-> `Wait-Until Test-RunningAiHealthy` sequence already used and live-validated by the earlier
untracked script.

## PowerShell tests

`Test-RunningAI.ps1`: **36/36 passed** (32 baseline + 1 regression for the `$HOME`-collision fix's
candidate-ordering behaviour, + 2 more Java-discovery-candidate checks, + 1 Find-RunningAiJava21
skip-missing-exe check - net +4 vs the 6H-7.2 baseline of 32).
`Test-Watchdog.ps1`: **36/36 passed** (unchanged, re-run for completeness).
`Test-CoachOperator.ps1` (new): **14/14 passed** - generate safety, revision (with a Korean
round-trip built from Unicode code points, since this is a no-BOM `.ps1`), approve-gate (5 rejected
answers + 1 accepted), publish-gate (5 rejected answers + 1 accepted, full restart+re-preview+publish
happy path), no-bypass-parameter static check, preview-before-publish ordering, TOCTOU
preview-changed abort, unpublishable/already-published/REST short-circuits, and the
finally-cleanup-on-failure path. Total PowerShell: **86/86**.

A slow-but-not-hung false alarm during development: the first full run of `Test-CoachOperator.ps1`
appeared to hang for 180s with the Bash tool's `| Select-Object -Last 60` (which buffers the entire
pipeline before emitting anything) - direct file redirection showed it was simply slow (many
sequential HttpListener startups in the approve/publish-gate loops) plus two real bugs that are now
fixed (see below), not an actual deadlock.

Two more real bugs were found and fixed while building the test harness itself:
1. `New-RunningAiScriptedReader -Responses @('A', '', 'Q')` - Windows PowerShell 5.1 refuses to bind
   an **empty-string array element** to a `[Parameter(Mandatory)]` array parameter (not just an
   empty whole argument). `[Parameter(Mandatory)]` was removed from that test-only helper's
   `-Responses` parameter, since an empty string is exactly one of the "must be rejected" answers
   these gates need to test.
2. `running-ai-coach.ps1`'s own `.DESCRIPTION` originally *named* the forbidden bypass parameters
   (to say they don't exist) - which made the static no-bypass-parameter check fail against its own
   documentation. Reworded to describe the guarantee without using the literal forbidden tokens.

## H2

`cd server; .\gradlew.bat clean test` (JDK 21) - **1192 passed, 0 failed**, identical to the Phase
6H-7.2 baseline (no server code changed this phase).

## PostgreSQL

Full suite re-run against a freshly recreated throwaway PostgreSQL 17 database (`running_ai_test`,
`spring.datasource.hikari.maximum-pool-size=2`/`minimum-idle=0`, `driver-class-name` explicit) -
**1192 passed, 0 failed**, identical to H2.

## Python

`tools/garmin-connector`: zero tracked changes (confirmed via `git status`). `python -m pytest` -
**134 passed**, matching baseline exactly.

## Automated Garmin calls

0. Nothing in this phase's code path reaches Garmin.

## Automated Intervals calls

0. `Test-CoachOperator.ps1` never starts a real RunningAI server, so `IntervalsWorkoutPublisher`/
`IntervalsWorkoutClient` were never in the loop; the real `/publish` endpoint (reached only against
a real server) was not exercised this session at all (see Live smoke).

## Automated external writes

0.

## Live smoke

**Not performed this session.** Phase 6H-7.2's live smoke worked against an already-running Main-PC
Spring instance because that phase changed zero production code and could reuse the live server
as-is for a read/write-through-existing-endpoints test. This phase's new scripts
(`running-ai-coach.ps1`, `RunningAI.CoachOperator.ps1`, `publish-approved-draft-controlled.ps1`) only
exist in this worktree (`main-3`), not in the live checkout (`C:\running-ai-github`) where the actual
operator would run them - and the work order's own section 83 describes a separate, explicit "live
checkout integration" step (handling the untracked pre-6H-8 `publish-approved-draft-controlled.ps1`
there, then a fast-forward merge) that was not authorized to run unattended in this session. See
Known limitations.

## Live controlled publish performed

No. Section 82 of the work order explicitly says this phase's developer must not run it
unprompted, and it was not run.

## README

Updated: new "Windows API 호출 / AI Coach Operator CLI" subsection under "## Windows Runtime"
documenting `Invoke-RunningAiJsonRequest`/`invoke-running-ai-api.ps1` (closing the Phase 6H-7.2
leftover) and the new `running-ai-coach.ps1`/`publish-approved-draft-controlled.ps1` usage, linking
both new architecture docs.

## Architecture docs

Both written: `docs/architecture/windows-api-encoding.md` (the Phase 6H-7.2 leftover - the full
PowerShell-object -> JSON -> UTF-8-bytes -> HTTP diagram, both real bugs found in that phase with
root cause and fix, and the script-source-encoding rationale) and
`docs/architecture/coach-operator-workflow.md` (this phase's full state-machine diagram, the
shared-function/injectable-reader-and-restarter architecture, the three fail-closed short-circuits,
TOCTOU protection, the `.env`-untouched strategy, Java discovery hardening, and error handling).

## Legacy/untracked script handling

**Deferred, not performed this session** - see Known limitations. The pre-existing untracked
`C:\running-ai-github\scripts\windows\publish-approved-draft-controlled.ps1` was read (not modified)
to recover its validated operational logic (`.env` backup/restore pattern, the Java-21
candidate-fallback list, the exact preview/gate/restart/verify sequence) as the basis for this
phase's tracked implementation. It was **not** backed up to `.runtime/backups/phase-6h-8/`, not
compared file-by-file against the new tracked script, and the live checkout was not touched at all -
all three are section 83 "live checkout integration" actions, which this session's user explicitly
asked to defer (same as the equivalent question in Phase 6H-7.2).

## Commits

Pending in this worktree (`main-3`) - not yet created at the time this result doc was written; see
the session's commit history for the actual SHAs once made. Planned split (work order section 84):
`fix: harden Windows runtime prerequisites` (Java 21 discovery), `feat: add safe AI coach operator
workflow` (the three new scripts), `test: cover coach operator safety gates` (`Test-CoachOperator.ps1`
+ the Java-discovery additions to `Test-RunningAI.ps1`), `docs: document coach operations and UTF-8
API usage` (README + both architecture docs + this work order).

## Push

Not done (pending user instruction, same as every other phase in this repository).

## Known limitations

- **Live checkout integration (work order section 83) was not performed.** This worktree's commits
  were not fast-forward-merged into `C:\running-ai-github`'s `main`, the untracked pre-6H-8
  `publish-approved-draft-controlled.ps1` there was not backed up or replaced, and none of this
  phase's new scripts exist anywhere outside this worktree yet.
- **Live smoke (sections 80-81) was not performed** as a direct consequence of the above - it
  requires the new scripts to exist against a real, already-running server, which (unlike Phase
  6H-7.2, which changed no production code and could run its live smoke against any already-running
  instance) this phase's new *scripts* specifically need to be present to exercise.
- **Live controlled publish validation (section 82) was not performed**, per the work order's own
  instruction not to run it unprompted; it additionally requires the above two steps first.
- `RunningAI safe-mode runtime health UP afterward` (a real restart's outcome) was not live-verified
  this session - only the no-op-restarter-driven unit tests exercise the surrounding logic.
- The REST-day publication DB record is intentionally not written by this CLI path, consistent with
  work order section 28; a future phase would need to decide whether and how to record it.

## Next recommended phase

Live checkout integration (back up the untracked script, compare, fast-forward merge), then live
smoke (review/resume-only, then a synthetic Korean generate+revision with zero external write), then
- only with separate, explicit user authorization at that time - one real controlled-publish
validation end to end.

PHASE_6H_8_SAFE_COACH_OPERATOR_WORKFLOW_READY
