# Phase 6I-1.1 — External Relay Environment Isolation Fix (result)

## Root cause

`scripts/windows/external/start-external-relay.ps1` dot-sources `RunningAI.Common.ps1` (which
defines `Initialize-DotEnvForThisProcess`) but never called it. The script only ever worked
because, in every previously-observed invocation, some parent process (`start-running-ai.ps1`)
had already loaded the repo-root `.env` into the process environment tree it was launched from.
When the `RunningAI-Watchdog` Scheduled Task's recovery branch spawns
`external\start-external-relay.ps1` as its own standalone process (`Invoke-RecoveryAction`'s
`external-relay` branch, `RunningAI.Watchdog.ps1`), there is no such parent, so
`INTERVALS_ICU_API_KEY` (and any other `.env`-only key) was never applied. Combined with a stale
User-scoped `INTERVALS_ICU_API_KEY` override (environment issue, resolved separately by the
operator), the relay authenticated against Intervals.icu with the wrong/absent key, producing
`TODAY_WORKOUT_FAILED error=intervals.icu HTTP 401`.

## Fix

- `start-external-relay.ps1`: calls `Initialize-DotEnvForThisProcess` (repo-root `.env`, existing
  process env still wins) before starting `node`, so `Start-Process`'s inherited environment block
  carries the key to the relay child regardless of what process started this script.
  `RUNNING_AI_TEST_ENV_ROOT` is a test-only escape hatch mirroring `RUNNING_AI_TEST_RUNTIME_DIR`.
- `RunningAI.ExternalRelay.Common.ps1`: added a parallel `RUNNING_AI_TEST_RELAY_DIR` escape hatch
  so a test can point at a disposable fake relay directory instead of the real
  `tools/external-relay`.
- `Test-ExternalRelay.ps1`: three new tests spawn the real `start-external-relay.ps1` as a
  standalone child process against a disposable fake Node relay, proving (a) a key missing from
  the parent process is picked up from a test `.env`, (b) existing process env still wins over
  `.env` (precedence lock-in), (c) idempotency is preserved (no restart when already healthy).
  Along the way, a real `Start-Process -Wait` + redirected-output deadlock was found and fixed in
  the test harness itself (not production code): a spawned grandchild `node` process inherits the
  parent script's own redirected stdout/stderr file handles and, being left running detached,
  keeps them open forever, which blocks `-Wait`'s `WaitForExit()` indefinitely. Fixed by polling
  `$proc.HasExited` instead of using `-Wait`.

## Files changed

- `scripts/windows/external/start-external-relay.ps1`
- `scripts/windows/external/RunningAI.ExternalRelay.Common.ps1`
- `scripts/windows/tests/Test-ExternalRelay.ps1`

No production `.env`, secrets, or migration files touched.

## Test results (main-3, `C:\Users\simjy\orca\workspaces\running-ai-github\main-3`)

- `Test-ExternalRelay.ps1`: 16/16 (13 existing + 3 new)
- `Test-Watchdog.ps1`: 44/44
- `Test-RunningAI.ps1`: 38/38
- `Test-CoachOperator.ps1`, `Test-CloudflaredSetup.ps1`, `Test-TailscaleSetup.ps1`: all green
- `tools/external-relay/test/*.test.js` (Node): 14/14

## Live validation — main-3 (ENVIRONMENT-INAPPLICABLE, not a code failure)

`main-3` is a secondary git worktree and has no repo-root `.env` (untracked, not shared between
worktrees). Stop/start of the real external relay via the fixed scripts worked correctly
end-to-end: stale process-env key removed and confirmed absent; relay stopped (PID 55444 ->
freed) and restarted (new managed PID 24932) with Connector (50416), Spring (55520) and
PostgreSQL (`healthy`, unchanged `StartedAt`) all untouched. The authenticated check then failed
as expected given the missing `.env`: `relay.log` showed
`INTERVALS_ICU_API_KEY not set in this process environment` (not the original stale-key 401) -
exactly the behavior `Initialize-DotEnvForThisProcess` is documented to produce when no `.env`
file exists. This confirms the fix's logic is correct; it is not evidence of a defect.

Production was restored to a healthy, authenticated state (local and public Funnel both HTTP 200)
using a temporary, in-memory-only process environment variable read once from the canonical
`C:\running-ai-github\.env` - never written to disk, never logged, never printed. No production
`.env`/secrets file was created, copied, or modified in `main-3`, per explicit instruction.

Authoritative end-to-end validation (full watchdog recovery against the real `.env`, real Docker
Compose project, and the real Scheduled Task) is performed from the canonical checkout,
`C:\running-ai-github`, after this fix is merged there - see the live-validation record for that
step.

## Known, separate hardening issue (not fixed here)

Watchdog/Compose recovery invoked from a secondary git worktree can classify the canonical
PostgreSQL container as DOWN, because Docker Compose project identity depends on checkout
context: `docker compose -f <worktree>/docker-compose.yml ps -a -q postgres` returns empty even
though `docker ps -a --filter name=running-ai-postgres` shows the real container healthy, because
the container was created under a different checkout's compose project. A recovery run from that
worktree would plan `COMPOSE_UP` against the fixed `container_name: running-ai-postgres` and fail
with a name conflict - confirmed via a read-only `-DryRun` from `main-3` (`Planned action:
COMPOSE_UP postgres (CONTAINER_NOT_RUNNING)`, `Overall: DOWN`), without ever executing it. This is
a separate runtime-safety hardening item for a future phase; not solved by changing compose
project name, `container_name`, volumes, or production Docker state as part of this fix.

## Limitations

- Live end-to-end validation (real `.env`, full watchdog recovery) could not be fully completed
  from `main-3` due to the worktree's missing `.env` - it is the canonical checkout's
  responsibility, by design.
- The pre-existing Compose project-identity mismatch (hardening issue above) remains unaddressed.

## Next steps (not started)

- Canonical-checkout live validation (`C:\running-ai-github`), see separate live-validation record
  for that run's results.
- Track the secondary-worktree Compose project-identity hardening issue as its own future phase.
