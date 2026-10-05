# Phase 6H-8.2 — Controlled Publish Failure Recovery Hotfix — Result

Baseline SHA: `e10f9d047bcbd6104d769ca3930b93dd2582f368` (Phase 6H-8.1 result)
Final SHA: `a4bd34e` (main-3, 2 commits ahead of baseline; fast-forward merged into the live
checkout's `main` at `C:\running-ai-github`)

## Root cause (all three confirmed exactly as reported)

1. **Java PATH discovery corruption.** `Get-RunningAiJava21Candidates`'s `PathJavaExe` default was
   `$(($found = Get-Command java ...); if ($found) {...} else {$null})`. A parenthesized assignment
   (`($found = ...)`) emits `$found`'s value onto the pipeline **in addition to** the subsequent
   `if` statement's own result, so the subexpression produced two pipeline objects; `[string]`
   coercion space-joined them. Reproduced directly: the exact default expression returns
   `java.exe C:\Program Files\Java\jdk1.8.0_301\bin\java.exe` on this machine (not the single clean
   path `C:\Program Files\Java\jdk1.8.0_301\bin\java.exe`).
2. **Generic-exception secondary failure.** `Get-RunningAiErrorDetails` read
   `$ErrorRecord.Exception.Response` unconditionally - true only for a `WebException`. Reproduced
   directly: calling it with a `System.IO.DriveNotFoundException` (the real exception type from bug
   1's corrupted path) threw `PropertyNotFoundException` itself under `Set-StrictMode`, instead of
   returning a safe error detail.
3. **Partial restart failure skipped safe-mode recovery.** `Invoke-RunningAiControlledPublish` set
   `$restarted = $true` only *after* `& $RuntimeRestarter $BaseUrl` returned successfully. A
   restarter that fails partway through (exactly what bugs 1+2 caused, live: Spring stopped,
   connector stopped, connector started, then Java discovery failed before Spring could start) never
   reaches that line, so `$restarted` stayed `$false` and the `finally` block's safe-mode restart
   attempt was skipped entirely - the runtime was left down. Reproduced directly with a scripted
   restarter that throws on its first call: with the old code this left `$restartAttempted`-style
   state at its initial `$false`.

## Fixes

1. **`Get-RunningAiPathJavaExe`** (new, `RunningAI.Common.ps1`): a plain function whose only
   pipeline output is its explicit `return` value - no parenthesized-assignment trap. Used as
   `Get-RunningAiJava21Candidates`'s `PathJavaExe` default.
2. **`Get-RunningAiErrorDetails`**: reads `Response` via
   `$ErrorRecord.Exception.PSObject.Properties['Response']` (returns `$null` if the member genuinely
   doesn't exist, same technique as `Get-RunningAiOptionalProperty`) before falling back to
   `{HttpStatus=null; Code=null; Message=<original message>}` for any non-HTTP exception.
3. **`Invoke-RunningAiControlledPublish`**: the flag (renamed `$restartAttempted`) is now set to
   `$true` immediately before calling the restarter, not after it returns - "was a publish-enabled
   restart *attempted*" (yes, even if it then failed) is what must gate the `finally` block's
   safe-mode recovery restart, not "did it fully succeed."

## Regression

- **Java discovery**: `Get-RunningAiPathJavaExe returns a single clean path or null, never a
  space-joined concatenation` (direct, real PATH) and `Get-RunningAiJava21Candidates (real PATH, no
  override) never throws building its candidate list` (end-to-end against the real, fixed default) -
  both pass; a third check confirms the corrupted shape genuinely does break
  `Split-Path`/`Join-Path` the way it did live, so the regression guards the right thing.
- **Generic exception**: `Get-RunningAiErrorDetails handles a generic (non-HTTP) exception safely,
  never a secondary failure` - a synthetic `DriveNotFoundException` returns
  `{HttpStatus=null; Code=null; Message=<original message>}` with no exception of its own.
- **Partial restart failure**: `a restarter that fails partway through still gets a safe-mode
  recovery restart attempt (no publish, switches false)` (`Test-CoachOperator.ps1`, a
  `New-RunningAiFlakyRestarter` that throws on its first call and succeeds on its second) -
  `Outcome=FAILED`, exactly 2 restarter calls (the failed attempt + the safe-mode recovery), 0
  publish calls, all four switches `false`.
- **Full regression**: PowerShell **96/96** (39 in `Test-RunningAI.ps1`, up from 36; 21 in
  `Test-CoachOperator.ps1`, up from 20; 36 unchanged in `Test-Watchdog.ps1` - net +4, exceeding the
  ">92/92" requirement), H2 **1192/1192**, PostgreSQL **1192/1192** (freshly recreated throwaway
  database), Python **134/134** (connector untouched). All green in both `main-3` and, after the
  fast-forward merge, the live checkout.

## Live verification

Performed in full, with your explicit authorization for the real external-write step:

1. `git status --short` clean in the live checkout; fast-forward merge (`e10f9d0..a4bd34e`, 4 files
   changed, no conflicts). Both PowerShell suites re-run from the live checkout: `Test-RunningAI.ps1`
   39/39, `Test-CoachOperator.ps1` 21/21.
2. `status-running-ai.ps1` found the runtime in exactly the broken state this hotfix addresses:
   **Spring DOWN** - the live incident this work order describes had left it down because bug 3
   skipped the safe-mode recovery restart. `start-running-ai.ps1` (now using the fixed Java
   discovery) brought it up cleanly in 17s; `.env`'s four publishing switches confirmed `false`.
3. Draft #17: `APPROVED`, `publishable=true`, `publication=null` (not yet published).
4. **Real controlled publish attempted**: `echo YES | powershell.exe -File
   scripts\windows\running-ai-coach.ps1 -DraftId 17` (a genuinely new, interactive-capable
   `powershell.exe` process was required - invoking the script through this session's own
   already-non-interactive automation host made `Read-Host` refuse immediately with "Windows
   PowerShell is in NonInteractive mode"). The full flow ran exactly as designed: preview shown ->
   `YES` accepted -> publish-enabled restart (stop, start - **Java discovery worked correctly this
   time**, confirming fix 1 end to end) -> Spring UP -> second preview -> `POST /publish` called
   **exactly once**.
5. **The publish call itself failed**: `ERROR: INTERVALS_AUTH_FAILED - Intervals.icu responded 401`.
   This is an expired/invalid `INTERVALS_API_KEY` credential issue on the real account, **not a code
   bug** - out of scope for this hotfix, left for you to resolve.
6. **The fix for bug 3 worked exactly as designed despite this failure**: the `finally` block reset
   all four switches and performed a full safe-mode restart (stop, start, health re-checked) -
   printed `RunningAI safe-mode restart: OK`. Confirmed afterward: `GET /actuator/health` ->
   `{"status":"UP"}`; `GET /publish-preview` for draft 17 -> still `publication: null` (the server
   never records a publication on a failed publish attempt - "the draft stays APPROVED and can be
   retried", per `ApprovedWorkoutDraftPublishService`'s existing behaviour) - **zero external write
   occurred**; `.env`'s four switches still all `false` (never modified on disk at any point).

You chose not to retry the publish this session (the 401 is yours to investigate/resolve).

## Safety verification

- External write to Intervals.icu: **0** (the 401 means nothing was created/updated there).
- Garmin writes: **0**.
- Runtime restarts during live verification: **2** (the publish-enabled restart, then the safe-mode
  recovery restart in `finally`) - both completed successfully, both using the real
  `stop-running-ai.ps1`/`start-running-ai.ps1`, no Docker/Java process managed directly by this code.
- Final publishing-switch state: all four `false`, confirmed both in the live process environment's
  effect (`GET /actuator/health` healthy under safe-mode config) and in `.env` on disk (never
  touched).
- Draft #17: still `APPROVED`, still unpublished, retryable once the API key issue is fixed.

## Commits

Two, in `main-3` (`fc9be7c` fix: harden Java discovery and controlled-publish failure recovery,
`a4bd34e` test: cover Java PATH discovery and partial-restart-failure recovery), fast-forward merged
into the live checkout's `main` (`e10f9d0..a4bd34e`).

## Push

Not done yet (pending your instruction, same as every other phase).

## Known limitations

- **`INTERVALS_API_KEY` needs attention.** The real controlled-publish attempt failed with
  `INTERVALS_AUTH_FAILED` (401). This is a credential/account issue, not something this session
  touched, read, or can fix (per project rules, the key's value was never read, logged, or printed -
  only the `INTERVALS_AUTH_FAILED` code and generic message surfaced, exactly as the error-formatting
  code is designed to do). Draft #17 remains approved and ready to retry once resolved.
- Everything else from the Phase 6H-8 "Known limitations" (the next real controlled-publish
  end-to-end validation, now effectively attempted - see above) stands: this attempt validated the
  *mechanism* (restart, preview-compare, exactly-once publish, failure recovery) completely, but did
  not result in an actual successful Intervals.icu write, so that specific "real publish succeeds"
  milestone is still open.

PHASE_6H_8_2_CONTROLLED_PUBLISH_FAILURE_RECOVERY_READY
