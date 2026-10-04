# Safe AI-coach operator workflow (Phase 6H-8)

```text
TrainingContext V1/V2
       |
       v
Claude Coach  (running-ai-coach.ps1 -Date/-Goal/..., or -DraftId to resume)
       |
       v
     DRAFT  <------------------------------------------.
       |                                                |
       |-- [R] Revise -----> POST /revisions ---> new DRAFT version
       |                                                |
       `-- [A] Approve                                  |
              |  "Type APPROVE to approve Draft #N:"    |
              |  (anything but exact APPROVE -> cancelled, loop above)
              v
          POST /approve  ->  APPROVED
              |
              v
       GET /publish-preview  (publish switch still false; zero Intervals calls)
              |
   .----------+-----------+--------------------.
   |          |           |                    |
already    unpublishable  REST day         publishable, no
published  (fail closed)  (no switch,      publication yet
   |          |           no restart)          |
   `----------+-----------+              "This will write the
              |                           approved workout to
              v                           Intervals.icu.
         STOP, zero external write        Type exactly YES to publish:"
                                                |
                                 (anything but exact YES -> cancelled, STOP)
                                                v
                                 env: RUNNING_AI_DRAFT_PUBLISHING_ENABLED=true
                                      WORKOUT_PUBLISHING_ENABLED=false
                                      WORKOUT_PUBLISHING_SCHEDULER_ENABLED=false
                                      RUNNINGAI_MCP_ENABLED=false
                                                |
                                 stop-running-ai.ps1 -> start-running-ai.ps1
                                      (process env wins; .env is not touched)
                                                |
                                                v
                                 GET /publish-preview AGAIN
                                                |
                                 compare to the pre-restart preview:
                                 draftId, approvalId, date, workoutType,
                                 structuredStepCount, SHA-256(renderedWorkoutText)
                                                |
                                 changed?  -> PREVIEW_CHANGED_AFTER_RESTART, STOP
                                 unchanged? -> POST /publish  (exactly once)
                                                |
                                                v
                                   outcome == PUBLISHED
                                   and verified == true ?
                                                |
                                  no -> FAILED          yes -> VERIFIED SUCCESS
                                                |                   |
                                                `---------+---------'
                                                          v
                                           finally: all four switches -> false
                                           stop-running-ai.ps1 -> start-running-ai.ps1
                                           (safe-mode health re-checked)
```

## Core principle

**AI makes the draft. A human approves it. A human must type the exact word `YES` before any
external write happens.** `running-ai-coach.ps1` has no parameter that skips either gate - no
`-AutoApprove`, `-AutoPublish`, `-Yes`, `-ForcePublish`, and none will be added
(`Test-CoachOperator.ps1` has a static check that the source contains no such token). `-Revise` and
`-Approve` loop as many times as the human chooses; the coach is never called automatically in a
loop.

## Why the logic lives in `RunningAI.CoachOperator.ps1`, not in the two entry scripts

`scripts/windows/running-ai-coach.ps1` (full session: generate/resume, review, revise, approve,
then publish) and `scripts/windows/publish-approved-draft-controlled.ps1` (publish only, given an
id already APPROVED - the tracked replacement for what was previously an untracked, unversioned
script on the main PC) are both thin wrappers around the same shared functions in
`RunningAI.CoachOperator.ps1`, principally `Invoke-CoachOperatorSession` and
`Invoke-RunningAiControlledPublish`. Neither script repeats the gate, restart or TOCTOU logic -
there is exactly one implementation of each safety property, reused by both entry points.

Every human-input point (`Read-Host`-equivalent console prompts) and every runtime-restart point
(`stop-running-ai.ps1` / `start-running-ai.ps1`) is taken through a `-Reader` / `-RuntimeRestarter`
scriptblock parameter instead of being called directly. The real entry scripts never override these
defaults - a human really does type at a real console, and the real scripts really do restart - but
`scripts/windows/tests/Test-CoachOperator.ps1` injects a scripted reader and a no-op restarter, so
the entire gate/TOCTOU/cleanup logic can be exercised against a local `HttpListener` with zero
Docker, zero Java process, and zero real keyboard input.

## Fail-closed short-circuits (never touch the publish switch or restart)

Three preview outcomes complete the workflow **without ever setting
`RUNNING_AI_DRAFT_PUBLISHING_ENABLED=true` or restarting the runtime**:

- **Already published** (`preview.publication != null`): the stored result is shown; no new publish
  is attempted.
- **Unpublishable** (`preview.publishable == false`): "Draft approved but cannot be published
  losslessly. No external write performed." - the CLI never edits the draft to make it publishable;
  that stays the coach's and the human's job.
- **REST day** (`preview.expectedOutcome == SKIPPED_REST_DAY`): a REST day never needs an Intervals
  write, so the workflow completes as "Approved REST day. No external workout is required." without
  ever calling `POST /publish` at all. (`ApprovedWorkoutDraftPublishService.publish()` still gates
  on the publish switch even for a REST day's `SKIPPED_REST_DAY` recording - calling it would need
  the same switch-flip/restart dance as a real publish for a result with no external effect. If a
  later requirement needs the REST-day publication *row* recorded by this CLI, that is a separate,
  explicit phase - not assumed here.)

## TOCTOU protection (sections 36-38 of the work order)

A restart happens *between* the first preview (switch still false, before the human's `YES`) and
the actual publish (switch now true, after a fresh JVM start). In principle the approved draft could
have changed in that window (a concurrent approval of a revision is not possible by this draft's own
immutability rules once APPROVED, but the protection is cheap and removes the assumption entirely).
`Test-RunningAiPreviewUnchanged` compares `draftId`, `approvalId`, `date`, `workoutType`,
`structuredStepCount` and a SHA-256 of `renderedWorkoutText` between the two previews; any
difference aborts as `PREVIEW_CHANGED_AFTER_RESTART` with zero external write, before `POST
/publish` is ever called.

## `.env` is never modified

The publish-enabled restart sets the four switches as **process environment variables** on the
PowerShell process running the CLI, then calls `stop-running-ai.ps1` / `start-running-ai.ps1`.
`start-running-ai.ps1` loads `.env` into its own process environment only for keys **not already
set** (`Initialize-DotEnvForThisProcess`, existing behaviour since the Windows runtime orchestration
phase) and `Start-Process` inherits the full parent environment by default - so the child Spring
process sees `RUNNING_AI_DRAFT_PUBLISHING_ENABLED=true` for exactly this one restart, without the
repository's `.env` file ever being opened for writing. This replaces the previous (untracked,
unversioned) controlled-publish script's approach of editing `.env` in place with a backup/restore
dance; the new approach has nothing to restore because nothing on disk was ever changed. When the
CLI process exits, its own environment variables disappear with it - there is no persistence to
clean up.

## Java 21 discovery hardening

A stale PowerShell session's own `JAVA_HOME`/`PATH` previously being the *only* thing
`start-running-ai.ps1` checked meant a shell that predated a JDK 21 install reported "Java 21 is not
available" even with one present on the machine. `Find-RunningAiJava21` /
`Get-RunningAiJava21Candidates` (`RunningAI.Common.ps1`) add a fallback chain: current `JAVA_HOME` ->
current `PATH` -> machine-level `JAVA_HOME` -> user-level `JAVA_HOME` -> `Program Files\Java\jdk-21*`.
Each candidate is verified by actually running `java.exe -XshowSettings:properties -version` via the
existing `Invoke-NativeText` helper (so PowerShell 5.1 never turns that command's normal stderr
output into a terminating `NativeCommandError`) and checking for `java.version = 21`. The candidate
list itself is a pure function, independently testable without a real JDK or any hard-coded
user-specific path (`Test-RunningAI.ps1`); only the final lookup runs a real process.

## Error handling

`Get-RunningAiErrorDetails` (`RunningAI.Common.ps1`) extracts `{HttpStatus; Code; Message}` from a
non-2xx RunningAI response - this is always the existing `ErrorResponse` shape
(`code`/`message`/`timestamp`/`errors?`), never a raw response body, a credential, a prompt, or a raw
AI response. `Format-RunningAiErrorMessage` (`RunningAI.CoachOperator.ps1`) turns the known codes
(`WORKOUT_DRAFT_NOT_FOUND`, `WORKOUT_DRAFT_SUPERSEDED`, `DRAFT_PUBLISHING_DISABLED`,
`UNPUBLISHABLE_DRAFT`, every `AI_COACH_*` / `INTERVALS_*` code, etc.) into a short, readable line.

## What this phase does not add

No new server endpoint - the CLI uses only the six existing `/api/v1/workout-drafts*` endpoints
that already existed before this phase. No scheduler, no startup trigger, no automatic revision, no
automatic approval, no non-interactive publish path (`-NonInteractive` only generates/resumes and
displays; it never reaches the approve or publish gate).
