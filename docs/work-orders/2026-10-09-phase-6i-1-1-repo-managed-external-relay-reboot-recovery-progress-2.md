# Phase 6I-1.1 — watchdog live-validation regression fix (progress 2)

## Context

Continuation of `2026-10-09-phase-6i-1-1-repo-managed-external-relay-reboot-recovery-progress-1.md`.
Live validation of the `RunningAI-Watchdog` Scheduled Task on the main PC found the canonical
runtime healthy (Docker RUNNING, PostgreSQL HEALTHY, GarminConnector PID 54540, Spring PID 55520,
ExternalRelay PID 27668, Tailscale Funnel configured, authenticated `/today-workout` = 200) and the
Scheduled Task itself registered and running (`LastTaskResult = 0`), but surfaced two real
regressions in the watchdog scripts themselves. `PHASE_6I_1_EXTERNAL_ACCESS_E2E_READY` was
explicitly not yet declared, and no reboot/failure-injection was authorized for this step.

## BUG A — direct `-DryRun` invocation threw `'Quote-Argument' is not recognized`

### Root cause

`Get-DefaultProbes` (in `RunningAI.Watchdog.ps1`) builds several probe scriptblocks with
`.GetNewClosure()` so each one keeps its own copy of `$ConnectorPort` / `$SpringPort` /
`$RelayPort` / `$compose` after `Get-DefaultProbes` itself returns. `.GetNewClosure()` creates a
brand-new session state whose scope chain goes straight to the runspace's **GLOBAL** scope - not
to the scope this file was dot-sourced into. When `watch-running-ai.ps1` is invoked via
`powershell -File` (what the Scheduled Task does, and what `install-running-ai-watchdog-task.ps1`
registers), that dot-sourced scope ends up effectively global, so the closures can resolve helper
functions fine. When it is invoked directly - `.\watch-running-ai.ps1 -DryRun`, or
`powershell -Command "& 'path' -DryRun"` - the dot-sourced scope is a child of the caller's own
global scope, not global itself, and a closure calling a dot-sourced helper **function** by name
(`Quote-Argument`, `Invoke-NativeText`, `Test-ConnectorHealth`, `Test-PortInUse`,
`Test-SpringHealth`, `Test-ExternalRelayHealth`) cannot find it there and throws
`"The term '<name>' is not recognized..."`. Closures resolve captured **variables** correctly in
both invocation styles - only function-by-name resolution inside the closure is affected.

Confirmed empirically (isolated repro files, then the real scripts) that:
- `powershell -File watch-running-ai.ps1 -DryRun` worked even on the unfixed code.
- `.\watch-running-ai.ps1 -DryRun` (exactly the instruction's reproduction command) and
  `powershell -Command "& 'watch-running-ai.ps1' -DryRun"` both threw on the unfixed code, first on
  `Quote-Argument`, then (once that one call was fixed in isolation) on `Invoke-NativeText` -
  proving the defect is the closure/scope mechanism itself, not one specific helper call.

### Fix

In `Get-DefaultProbes`, every helper function a closure needs is captured once as a variable via
`${function:Name}` *before* the closures are built, and invoked inside each closure with
`& $theVariable ...` instead of calling the function by name. This is captured as a variable (which
closures resolve correctly) rather than relying on name resolution through a scope chain that
depends on how the script happens to be invoked. Applied to the `Postgres`, `ConnectorHealth`,
`ConnectorPortUsed`, `SpringHealth`, `SpringPortUsed`, `ExternalRelayHealth`,
`ExternalRelayPortUsed` closures. `Docker`, `ConnectorPid`, `SpringPid`, `ExternalRelayPid` were not
affected (no `.GetNewClosure()`, no variable capture needed) and are unchanged.

## BUG B — external-relay missing from DryRun output / status JSON / restart budget

### Root cause

`watch-running-ai.ps1` built the `-DryRun` component listing, the `watchdog-status.json`
`components` map, and its `restartBudget.used` map by iterating `$script:Components` - the
4-component core dependency chain (`docker, postgres, connector, spring`) defined in
`RunningAI.Watchdog.ps1`. Phase 6I-1.1 added `external-relay` as a fifth, *independent* tracked
component (`$script:IndependentComponents`, combined into `$script:AllTrackedComponents`) with its
own classification, planning pass and restart-budget history, but these three loops were never
updated to iterate the combined list, so `external-relay` silently never appeared in the operator
diagnostics even though it was fully classified, planned and budgeted internally.

### Fix

Changed all three `foreach ($c in $script:Components)` loops in `watch-running-ai.ps1` (the DryRun
listing, the `restartBudget.used` builder, the `components` status builder) to
`foreach ($c in $script:AllTrackedComponents)`. No other file needed a change:
`status-running-ai.ps1`'s `RestartBudget` line already renders whatever keys are present in the
JSON (`$w.restartBudget.used.PSObject.Properties`), so it picked up `external-relay` automatically
once the JSON contained it.

## Files changed

- `scripts/windows/RunningAI.Watchdog.ps1` — `Get-DefaultProbes` helper-function capture (BUG A).
- `scripts/windows/watch-running-ai.ps1` — three loops switched to `$script:AllTrackedComponents`
  (BUG B).
- `scripts/windows/tests/Test-Watchdog.ps1` — three new regression tests (below).

`server/` was not touched (0 files).

## New regression tests (`Test-Watchdog.ps1`)

1. **"dry run via direct invocation (not -File) exercises the real default probes without a
   closure scope regression"** — spawns `powershell -Command "& 'watch-running-ai.ps1' -DryRun
   -RecheckDelaySec 0"` (the non-`-File` invocation style that reproduced BUG A) against the real,
   non-injected `Get-DefaultProbes` path, and asserts exit 0 with no `"is not recognized"` / no
   `"ERROR:"` in the output. Deliberately avoids `-File` (which hid the bug) and avoids injected
   mock probes (which never exercise `Get-DefaultProbes` at all).
2. **"DryRun component listing includes external-relay"** — asserts the `-DryRun` text output
   contains an `external-relay` row.
3. **"status JSON components and restartBudget.used include external-relay (normal, no-recovery
   run)"** — runs `watch-running-ai.ps1 -NoRecovery` against an isolated
   `RUNNING_AI_TEST_RUNTIME_DIR` and asserts both `components` and `restartBudget.used` in the
   written `watchdog-status.json` contain an `external-relay` key.

## Test results

- PowerShell (`scripts/windows/tests/*.ps1`, each run standalone): **186 passed, 0 failed**
  (183 pre-existing + 3 new). One run of `Test-CoachOperator.ps1` showed a single transient failure
  when all six suites were run back-to-back rapidly (likely port/resource contention from the
  earlier suites); re-run in isolation and in a second full back-to-back pass it was 25/25 both
  times. Not touched by, or related to, this change (no file this change modifies is used by that
  suite).
- Node (`node --test tools/external-relay/test/*.test.js`): **14 passed, 0 failed** (unchanged).
- `server/`: 0 files changed, Gradle suite not re-run (no server code touched).

## Live validation (main PC, canonical runtime, nothing stopped/restarted/rebooted)

Baseline before any watchdog invocation (`status-running-ai.ps1`): Docker RUNNING, PostgreSQL
HEALTHY, GarminConnector PID 54540, Spring PID 55520, ExternalRelay PID 27668.

- `.\scripts\windows\watch-running-ai.ps1 -DryRun` (direct invocation, the exact command from the
  bug report): exit 0, printed all five components (`docker UP`, `postgres UP`, `connector UP`,
  `spring UP`, `external-relay UP`), `Planned actions: none`, `Overall: UP`. No error, no file
  written.
- `.\scripts\windows\watch-running-ai.ps1` (normal one-shot): exit 0, `lastAction=NONE`.
  `watchdog-status.json` written with `components` and `restartBudget.used` both containing
  `external-relay` (value `UP` / `0`).
- `status-running-ai.ps1` after that run: `RestartBudget docker=0 postgres=0 connector=0 spring=0
  external-relay=0 (max 3 per 10 min)`.
- `Start-ScheduledTask -TaskName 'RunningAI-Watchdog'` (manual run): `LastTaskResult = 0`;
  `watchdog-status.json` updated again, `external-relay` still present and `UP`.
- ExternalRelay PID before and after every one of the above: **27668, unchanged throughout**
  (same for GarminConnector PID 54540 and Spring PID 55520).

## Git

- `main-3`: commit `4e7f8c1` ("fix(watchdog): harden live dry-run and relay diagnostics"), pushed
  to `origin/main-3`.
- `C:\running-ai-github` (`main`): fast-forwarded to `4e7f8c1` via `git fetch origin && git merge
  --ff-only origin/main-3`, then live-validated (above), then pushed to `origin/main`.
- Four-ref check after push: `main-3`, `origin/main-3`, `main`, `origin/main` all at `4e7f8c1`.
- Both working trees clean after push.

## Limitations / explicitly not done

- `PHASE_6I_1_EXTERNAL_ACCESS_E2E_READY` is **not** declared.
- No relay kill / failure injection was performed.
- No PC reboot was performed.
- The transient `Test-CoachOperator.ps1` failure under rapid back-to-back suite execution was
  observed but not root-caused (it did not reproduce on either isolated re-run); worth a look if it
  recurs, but it is unrelated to the files this change touches.

## Remaining acceptance (unchanged from progress-1, still open)

Unchanged — see `progress-1.md` items 1-8 (Intervals API key, authenticated live data, Garmin 265
E2E, reboot, post-reboot automatic recovery, post-reboot LTE/5G and Garmin 265 validation).
