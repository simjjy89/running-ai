# Phase 6I-1.6C — Spring Port Configuration Consistency Fix (result)

## Background

Phase 6I-1.6A/B isolated the root cause of the recurring Spring 8080 bind failure (Phase
6I-1.3/1.4/1.5): port 8080 falls inside a Windows TCP excluded port range (`8016-8115`, both
IPv4 and IPv6), and a raw socket `Bind()` test to all four candidate addresses
(`127.0.0.1`/`0.0.0.0`/`::1`/`::`) failed uniformly with `WSAEACCES` (native error `10013`) -
exactly the signature of an OS-level port exclusion, not a competing process. This phase
prepares Spring to move off port 8080 by making the port configurable consistently across every
script, without yet touching production.

## Root cause found and fixed in the scripts themselves (not the port conflict)

All three entry scripts (`start-running-ai.ps1`, `watch-running-ai.ps1`,
`status-running-ai.ps1`) declared `-SpringPort`'s default as
`$(if ($env:SERVER_PORT) { [int]$env:SERVER_PORT } else { 8080 })`. PowerShell evaluates a
parameter's default expression at **bind time**, before the script body runs - so this default
could only ever see `$env:SERVER_PORT` as it existed in the *parent* process, never a value
supplied only by the repo-root `.env` (loaded later, inside the script body). `watch-running-ai.ps1`
and `status-running-ai.ps1` additionally never called `Initialize-DotEnvForThisProcess` at all, so
a canonical `.env` `SERVER_PORT` was silently ignored by both, always. Confirmed by source
reading, not just inference (same class of bug as the Phase 6I-1.1 external-relay `.env` fix).

## Files changed

- `scripts/windows/RunningAI.Common.ps1` - new shared helper `Resolve-RunningAiSpringPort`
  (single source of truth: validates and returns `$env:SERVER_PORT`, defaulting to `8080`,
  rejecting an invalid value via `Stop-WithError`).
- `scripts/windows/start-running-ai.ps1` - `-SpringPort` default changed to the plain literal
  `8080`; `$SpringPort` is re-resolved via the helper immediately after
  `Initialize-DotEnvForThisProcess`, only when the caller did not explicitly pass `-SpringPort`.
  Added `RUNNING_AI_TEST_ENV_ROOT` test-only escape hatch for the `.env` root (mirrors
  `RUNNING_AI_TEST_RUNTIME_DIR`), without touching `$root` itself (still needed for
  `docker-compose.yml`/server paths).
- `scripts/windows/watch-running-ai.ps1` - same fix; additionally **gained its first-ever
  `.env` load** (it had none before this phase).
- `scripts/windows/status-running-ai.ps1` - same fix; also gained its first `.env` load.
- `scripts/windows/tests/Test-RunningAI.ps1` - new regression coverage (see below).

No production `.env`, secrets, or Docker/compose/PostgreSQL files touched. No service was
started.

## Port resolution priority (unchanged precedence, fixed timing)

1. Explicit `-SpringPort <n>` (detected via `$PSBoundParameters.ContainsKey('SpringPort')`,
   independent of whatever the parameter's own default happened to be).
2. Otherwise, the process's own pre-existing `$env:SERVER_PORT` (set before `.env` loads).
3. Otherwise, canonical `.env`'s `SERVER_PORT` (filled in by `Initialize-DotEnvForThisProcess`,
   which never overwrites an already-set variable).
4. Otherwise, the literal default `8080`.

Invalid `SERVER_PORT` values (non-numeric, `<1`, `>65535`) are rejected with a clear error via
`Stop-WithError`, in all three scripts.

## STEP A — port 18080 read-only pre-check

| Check | Result |
|---|---|
| IPv4 excluded port ranges | 18080 **not** in any range (highest range ends at `50059`) |
| IPv6 excluded port ranges | identical set; 18080 **not** included |
| Current LISTEN owner | none (`Get-NetTCPConnection`, `netstat -ano -p tcp` both empty) |
| `netsh interface portproxy` | no rules |

18080 looks clear by every read-only signal available. Per instruction, no actual bind test was
performed in this phase; that is deferred to pre-deployment verification (STEP D, item 1).

## Test results

- `Test-RunningAI.ps1`: **46/46** (38 pre-existing + 8 new: 5 unit tests for
  `Resolve-RunningAiSpringPort`, 6 source-order checks across the 3 scripts - collapsed to 3 PASS
  lines per script covering default-expression and ordering - and 4 isolated-standalone-process
  behavioral checks spawning the real `status-running-ai.ps1` as a genuine child process with
  `RUNNING_AI_TEST_RUNTIME_DIR`/`RUNNING_AI_TEST_ENV_ROOT`, covering: `.env`-only resolves to
  18080; existing process env wins over `.env`; explicit `-SpringPort` wins over both; no
  config anywhere falls back to 8080).
- `Test-Watchdog.ps1`: 44/44, unchanged, all pass.
- `Test-ExternalRelay.ps1`: 16/16, unchanged, all pass (confirms the Phase 6I-1.1 fix and its
  `RUNNING_AI_TEST_ENV_ROOT`/`RUNNING_AI_TEST_RELAY_DIR` escape hatches are unaffected by this
  phase's similarly-named but independently-scoped escape hatch in the three `*-running-ai.ps1`
  scripts).
- `Test-CoachOperator.ps1`, `Test-CloudflaredSetup.ps1`, `Test-TailscaleSetup.ps1`: all green,
  unchanged.

One self-inflicted test bug was found and fixed during this phase: the source-order checks'
own explanatory code comments mentioned `Resolve-RunningAiSpringPort` by name *before* the real
call site, which made a bare-name `IndexOf` search find the comment instead of the code; fixed
by searching for the literal assignment `= Resolve-RunningAiSpringPort` instead.

## Behavior comparison (existing vs. changed)

| Scenario | Before | After |
|---|---|---|
| No `-SpringPort`, no process env, no `.env` | 8080 | 8080 (unchanged) |
| No `-SpringPort`, no process env, `.env` has `SERVER_PORT=18080` | **8080 (bug: `.env` silently ignored)** | **18080** |
| No `-SpringPort`, process env `SERVER_PORT=9999`, `.env` has `18080` | 9999 | 9999 (unchanged - precedence preserved) |
| Explicit `-SpringPort 7777`, conflicting process env/`.env` | 7777 | 7777 (unchanged) |
| `watch-running-ai.ps1` / `status-running-ai.ps1` standalone (Scheduled Task style) with only `.env` configured | **never saw `.env` at all** | now loads `.env` and resolves consistently with `start-running-ai.ps1` |
| Invalid `SERVER_PORT` (e.g. `"abc"`, `"0"`, `"70000"`) | silently coerced/threw an unclear `[int]` cast error at parameter bind time | clear, explicit `Stop-WithError`: `"SERVER_PORT='...' is not a valid TCP port (1-65535)"` |

## Operational deployment risk (assessment only, nothing applied)

Low, conditional on STEP D's own pre-deployment bind check (not yet performed) confirming 18080
is still free at the moment of actual cutover. The code change itself is backward compatible:
omitting `SERVER_PORT` everywhere reproduces today's exact behavior (8080, unchanged
default/precedence). The only operational action this phase recommends preparing for - not
executing - is setting `SERVER_PORT=18080` in the canonical `.env`, which this code change is
what makes that variable actually effective for `watch-running-ai.ps1`/`status-running-ai.ps1`
for the first time.

## Commit readiness

Working tree has 5 changed files (4 production scripts/helper + 1 test file), all intended, no
secrets, full regression green. Not committed - awaiting instruction (this WO did not request a
commit, only flagged that *unauthorized* commit/merge/push is forbidden).

## Phase 6I-1.6D deployment recommendation (plan only - not executed)

1. **Bind-verify 18080 for real** (the live socket test deferred from this phase): the same
   `Bind()`-then-`Dispose()` diagnostic used in Phase 6I-1.6B, against all four addresses, run
   immediately before cutover (state can change between now and deployment).
2. Confirm no other production `SERVER_PORT` expectation conflicts (check any firewall rule,
   reverse proxy, or Funnel config that assumes 8080 - `status-running-ai.ps1`'s own Garmin
   sync-status call and Tailscale Funnel target use `$relayPort`/`$SpringPort` already, so no
   separate code change needed there, only a config-value check).
3. Apply `SERVER_PORT=18080` to the canonical `C:\running-ai-github\.env` (not done here).
4. Fast-forward canonical `main` to this phase's commit (not done here - no commit made yet).
5. With `RunningAI-Watchdog` still `Disabled` (per Phase 6I-1.4/1.5's unresolved state), perform
   one single manual recovery run (`start-running-ai.ps1`, canonical, no `-Build`, matching the
   Phase 6I-1.5 protocol) to bring Spring up on 18080.
6. Verify Spring health on 18080 (`status-running-ai.ps1`, `/actuator/health`).
7. Verify Connector and External Relay states.
8. Verify authenticated local + public Funnel `/today-workout` both HTTP 200.
9. Verify the existing PostgreSQL container ID and volume are unchanged (same protocol as Phase
   6I-1.5's preflight: compare `Id`, `StartedAt`, volume name before/after).

## Limitations

- STEP A's port-18080 check is read-only; no live bind test was performed this phase (explicitly
  deferred, per instruction).
- The deployment plan above is a proposal only; no step in it has been executed.
- Whether 18080 will remain free at actual deployment time is not guaranteed by this phase's
  findings alone - Windows port exclusion ranges can change between Docker/WSL restarts.
