# Phase 6I-1.1 — Repo-managed External Relay + Reboot Recovery — Progress note 1

Status: **IN PROGRESS, not READY.** This is a sub-phase of Phase 6I-1 (see
`2026-10-06-phase-6i-1-external-access-e2e-stabilization-progress-1.md` and `-progress-2.md`).
It integrates the already-existing, already-committed `scripts/windows/external/{start,stop,status}-external-relay.ps1`
and `RunningAI.ExternalRelay.Common.ps1` (Phase 6I-1 Part B) into the repo's canonical
start/stop/status/watchdog lifecycle, so the external relay survives a PC reboot the same way
Docker/PostgreSQL/the Garmin connector/Spring already do. Do not read this as
`PHASE_6I_1_EXTERNAL_ACCESS_E2E_READY`.

## What was recovered from an interrupted session

A prior session's working tree already contained a complete implementation across 7 files
(+339/-23) when this session resumed. Nothing was discarded or rewritten; one real test bug was
found and fixed (below), everything else was reviewed and kept as-is.

- `RunningAI.Common.ps1`: `ExternalRelay = 15` exit code; `RUNNING_AI_TEST_RUNTIME_DIR` test-only
  escape hatch so tests (including spawned child processes) never touch the real `.runtime`
  folder.
- `start-running-ai.ps1`: ensures the external relay healthy as an independent step 6, after
  Spring. A relay failure is reported and changes the exit code (15) but never rolls back an
  already-healthy Docker/PostgreSQL/connector/Spring; a Spring failure never attempts to start the
  relay.
- `stop-running-ai.ps1`: stops the external relay first (it's the public-facing component), via
  the existing tracked-PID/command-line-match machinery - a foreign/legacy process is never
  touched.
- `status-running-ai.ps1`: adds an `ExternalRelay` row (UP/DOWN, managed PID or "not started by
  RunningAI scripts") and read-only `Tailscale`/`Funnel` rows (Windows Service state,
  `tailscale funnel status` parsed for our target) - never installs, logs in, or changes Funnel
  config.
- `RunningAI.Watchdog.ps1`: the external relay is tracked as an **independent** component
  (`$script:IndependentComponents`), evaluated in its own planning pass outside the core
  docker→postgres→connector→spring cascade, with its own restart-budget/history counter. DOWN →
  `START_EXTERNAL_RELAY`; process alive but unhealthy → `RESTART_EXTERNAL_RELAY` (graceful
  pre-stop); a foreign process holding the port → `DEGRADED`/`FOREIGN_PROCESS`, never killed,
  never an action. Recovery is dispatched to the relay's own dedicated script
  (`external\start-external-relay.ps1`), never the full `start-running-ai.ps1` orchestrator, so a
  genuine Docker/PostgreSQL/connector/Spring failure can never block relay recovery and a relay
  failure can never block core-chain recovery.
- `Test-ExternalRelay.ps1` / `Test-Watchdog.ps1`: new regression covering all of the above
  (duplicate-start suppression, foreign-process non-interference for start/stop/status, the
  watchdog's independent planning, restart budget, and the dedicated-script dispatch).

## What this session fixed

One test, `status-external-relay.ps1: reports managed/PID when tracked, unmanaged when
healthy-but-untracked, DOWN when nothing listens`, was asserting false. Root cause: its "managed"
case wrote the PID file with the **test harness's own PID** (`$PID`), but
`status-external-relay.ps1` calls `Get-TrackedProcessId`, which validates the live process's
command line against `Get-ExternalRelayMarkers` (`server.js` + the relay directory) - the test
runner's own command line obviously doesn't contain those, so the PID file was correctly treated
as stale and removed, and the script fell through to "not started by RunningAI scripts" instead
of "managed". Fixed by spawning a genuine look-alike dummy process whose command line contains
both markers (a harmless leading PowerShell comment line embedding them), tracking **that**
process's PID instead. This is a test-only fix; no production script changed.

## Regression (this session)

- **PowerShell, all 6 suites: 183/183** (`Test-RunningAI.ps1` 41, `Test-CoachOperator.ps1` 25,
  `Test-Watchdog.ps1` 44, `Test-ExternalRelay.ps1` 13, `Test-CloudflaredSetup.ps1` 26,
  `Test-TailscaleSetup.ps1` 34). Up from the prior baseline of 159 (+24: the Phase 6I-1.1 checks
  added to `Test-Watchdog.ps1` and `Test-ExternalRelay.ps1`).
- **Node**: `node --test tools/external-relay/test/*.test.js` **14/14**, unchanged.
- **Server-side (Java/Kotlin)**: zero files under `server/` touched (`git status --short server/`
  empty) - not re-run.
- **Secret scan**: diff of all 7 changed files grepped for api-key/token/password/secret patterns
  - no literal credential found. `tools/external-relay/secrets/` confirmed git-ignored
    (`.gitignore` rule `secrets*`), zero files tracked under it, zero `.env` tracked anywhere in
    the repo.

## Live validation (this machine, read-only; nothing stopped/restarted for this)

Listener proof: `Get-NetTCPConnection -LocalPort 17845` shows `OwningProcess 36652`, which matches
`.runtime\external-relay.pid` (36652) and `status-running-ai.ps1`'s own report
(`ExternalRelay UP 127.0.0.1:17845 (managed, PID 36652)`) - the production listener is confirmed
to be the repo-managed process, not the legacy ad hoc instance.

| Check | Local (`127.0.0.1:17845`) | Public Funnel (`https://laptop-mmdqucic.tail4597a5.ts.net`) |
|---|---|---|
| `/health` | 200 | 200 |
| `/today-workout`, no auth | 401 | 401 |
| unknown path | 404 | 404 |
| `/today-workout`, Bearer token | 502 | 502 |

The Bearer-token 502s are **not** a transport/handoff regression - `/health`, the no-auth 401, and
the unknown-path 404 all behave correctly through the same relay instance, proving the transport
path itself is intact. The 502 is the relay's own upstream call to Intervals.icu failing because
`INTERVALS_ICU_API_KEY` is currently rejected by Intervals.icu with 401 (a credential issue,
pre-existing before this session, confirmed unchanged by this session, and explicitly out of scope
- the key was not touched). Classification: **INTERVALS_CREDENTIAL_DEGRADED**, not
`TRANSPORT_FAILURE`.

## Explicitly not done in this step

- `INTERVALS_ICU_API_KEY` was not touched, rotated, or diagnosed further (out of scope per
  instruction).
- No PC reboot was performed.
- No end-to-end Garmin 265 → Funnel hostname test was run.
- Post-reboot automatic recovery (relay + Funnel + core runtime, with no manual terminal) has not
  been exercised - only the watchdog's *logic* is regression-tested; a real reboot test is still
  pending.

## Remaining acceptance (unchanged scope, still open)

1. `INTERVALS_ICU_API_KEY` normalized.
2. Local authenticated `/today-workout` = 200 real data.
3. Public authenticated `/today-workout` = 200 real data.
4. Garmin 265 real E2E success over the Tailscale Funnel hostname.
5. PC reboot.
6. Fully automatic post-reboot recovery of runtime + relay + Funnel with no manual terminal step.
7. Post-reboot LTE/5G validation.
8. Post-reboot Garmin 265 validation.

`PHASE_6I_1_EXTERNAL_ACCESS_E2E_READY` is **not** declared.
