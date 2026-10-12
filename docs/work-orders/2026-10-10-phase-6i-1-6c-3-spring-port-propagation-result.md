# Phase 6I-1.6C-3 — Spring Port Propagation & Operator API Client Compatibility (result)

## STEP A — Spring port propagation fix

`start-running-ai.ps1` had `if ($SpringPort -ne 8080) { $env:SERVER_PORT = "$SpringPort" }` - so
whenever the final resolved `$SpringPort` happened to equal exactly `8080` (e.g. an explicit
`-SpringPort 8080` with a stale pre-existing `$env:SERVER_PORT=18080` left over from a parent
process), the stale env value was never overwritten, and Java would bind that stale port while
`Test-SpringHealth` kept polling the real `$SpringPort` (8080) - a permanent, silent
health-check/actual-port mismatch.

Fixed by making the assignment unconditional: `$env:SERVER_PORT = "$SpringPort"`, always, with no
guard. Also added `Confirm-RunningAiValidPort` (`RunningAI.Common.ps1`), called on the FINAL
`$SpringPort` (explicit path included - `Resolve-RunningAiSpringPort` alone does not validate an
explicitly-passed port, since explicit always bypasses it), so an invalid value fails before any
component starts.

Full end-to-end verification (spawning a real `java.exe`) was explicitly out of scope; verified
instead by (1) a source check confirming the guard is gone and the assignment is unconditional,
textually before `Start-Process` for java, built on the same `$SpringPort` variable
`Test-SpringHealth` already uses; (2) the 5 named regression scenarios from the work order,
exercised against `Resolve-RunningAiSpringPort` - the actual decision logic that now
unconditionally feeds that assignment.

## STEP B — operator API client BaseUrl

`invoke-running-ai-api.ps1`, `running-ai-coach.ps1`, `publish-approved-draft-controlled.ps1` each
hardcoded `-BaseUrl = 'http://127.0.0.1:8080'`. These scripts are deliberately documented to never
load `.env` into their process environment (no API key, DB password, or Garmin/Intervals
credential should ever reach them) - so simply calling `Initialize-DotEnvForThisProcess` was not
an option.

Added two new `RunningAI.Common.ps1` helpers:
- `Get-RunningAiEnvFileValue` - reads ONE named key directly out of a `.env`-style file's raw
  content (same line-parsing rules as `Import-DotEnvIntoProcess`: BOM stripped, split on the
  first `=`, comments/blank lines skipped, key/value trimmed), never calls `Set-Item`, never
  touches the process environment, never reads any other key.
- `Get-RunningAiDefaultSpringBaseUrl` - existing process `SERVER_PORT` wins, else
  `Get-RunningAiEnvFileValue` against the canonical `.env`'s `SERVER_PORT` only, else `8080`,
  validated via `Resolve-RunningAiSpringPort`; always builds `http://127.0.0.1:<port>` - never the
  public Funnel hostname (`RUNNING_AI_EXTERNAL_BASE_URL` is never read here).

Each client script keeps `-BaseUrl`'s param default as the plain literal `8080` (same
bind-time-ordering constraint as Phase 6I-1.6C-2) and resolves the real default in the script
body, only when the caller did not explicitly pass `-BaseUrl`:
```powershell
if (-not $PSBoundParameters.ContainsKey('BaseUrl')) { $BaseUrl = Get-RunningAiDefaultSpringBaseUrl }
```
An explicit `-BaseUrl` is untouched - neither limited nor extended.

**`RunningAI.CoachOperator.ps1` reviewed, not modified** (per instruction: only change if proven
necessary). It never hardcodes a port; `Start-RunningAiRuntimeIfNeeded`/
`Restart-RunningAiRuntimeForPublishing` always take `-BaseUrl` from their caller and invoke bare
`start-running-ai.ps1` (no `-SpringPort` argument). Since both the client's `$BaseUrl` resolution
and `start-running-ai.ps1`'s own internal resolution independently read the SAME canonical
`.env`'s `SERVER_PORT` under the same precedence rules, they arrive at the same port by
construction - no coupling needed, confirmed by reading, not modified.

## STEP C — approval/publish safety

Not touched. The only change to `running-ai-coach.ps1`/`publish-approved-draft-controlled.ps1` is
one `-BaseUrl` resolution line inserted before the existing health-check/gate logic - nothing in
`RunningAI.CoachOperator.ps1` (APPROVE/YES gates, NonInteractive block, publish-preview
re-verification, safe-mode restart, default-off auto-publish) was edited. Confirmed by `git diff`
showing zero changes to that file, and by `Test-CoachOperator.ps1`'s full existing gate-test suite
passing unchanged.

## Files changed

- `scripts/windows/RunningAI.Common.ps1`
- `scripts/windows/start-running-ai.ps1`
- `scripts/windows/invoke-running-ai-api.ps1`
- `scripts/windows/running-ai-coach.ps1`
- `scripts/windows/publish-approved-draft-controlled.ps1`
- `scripts/windows/tests/Test-RunningAI.ps1`

No production `.env`, secrets, or `RunningAI.CoachOperator.ps1` touched. No service started.

## Test results

- `Test-RunningAI.ps1`: all green (38 base + 8 from 6I-1.6C-2 + ~26 new this phase: port-propagation
  source checks, the 5 named scenarios, `Get-RunningAiEnvFileValue`/`Get-RunningAiDefaultSpringBaseUrl`
  unit tests including a secret-leakage-guard test and a Funnel-hostname-never-used test, and
  3-script `BaseUrl` default/explicit-precedence/no-`.env`-load checks for each client).
- `Test-Watchdog.ps1`: all green, unchanged.
- `Test-ExternalRelay.ps1`: all green, unchanged.
- `Test-CoachOperator.ps1`: all green, unchanged - including the existing APPROVE/YES/
  NonInteractive/publish-preview-re-verification/safe-mode-restart gate tests.
- `git diff --check`: clean. Secrets scan on the diff: clean (only documentation/test-name text
  mentioning "secret", no actual values).

Two self-inflicted test bugs were found and fixed during this phase (both the same class of
mistake as in 6I-1.6C-2): a PowerShell drive-qualifier parsing error from `"$clientFile: ..."`
(fixed with `"${clientFile}: ..."`), and a source-order regex false-positive where the new fix's
own explanatory code comment quoted the removed guard literally, matching the "guard is gone"
assertion against itself (fixed by stripping comment lines before matching).

## Commit, push, and ref state

- Commit `fix(windows): propagate Spring port to operator clients`, exactly the 6 files above
  staged.
- Pushed to `origin/main-3` as a plain fast-forward (no force push).
- `main-3` local HEAD verified equal to `origin/main-3` after push.
- Canonical `C:\running-ai-github` (`main`) left untouched, not merged.

## Phase 6I-1.6D readiness

Code-side readiness for the 18080 migration is now complete: Spring's actual bound port, its
health check, and all three operator API clients' default target all derive from the same single
source of truth (`SERVER_PORT`, with the same precedence everywhere). Nothing in this phase
performs or stages an actual deployment action - the Phase 6I-1.6C deployment plan (live 18080
bind re-verification, canonical `.env` change, fast-forward merge, single manual recovery,
end-to-end health/auth verification) remains entirely unexecuted and still requires separate
approval.
