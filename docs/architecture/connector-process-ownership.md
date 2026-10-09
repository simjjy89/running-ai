# Garmin Connector Process Ownership (Phase 6I-1.7B-1)

## Problem

Phase 6I-1.7A found that on this machine the connector's virtual environment launcher
(`tools\garmin-connector\.venv\Scripts\python.exe`, the process `Start-Process` returns and the one
`Write-PidFile` records) is a re-exec stub: it spawns the real CPython interpreter (a Python Install
Manager-managed install under `AppData\Local\Python\pythoncore-*`) as a **child** process, and that
child - not the tracked launcher - is the one that actually binds the TCP port. Live-confirmed: both
processes are created in the same instant, the listener's `ParentProcessId` is the launcher, and the
launcher's only other child is a `conhost.exe` for the same console - not a duplicate connector, not
a uvicorn worker/reloader, not a stale leftover process.

The pre-existing single-PID-file mechanism (`RunningAI.Common.ps1`: `Read-PidFile`/`Write-PidFile`/
`Test-ProcessIdentity`/`Get-TrackedProcessId`) only ever knows about the tracked launcher PID. It has
no way to:
- recognize the real listener child as "ours",
- tell a genuinely orphaned child of our own launcher apart from an unrelated foreign process once
  the launcher has exited (its PID file entry can no longer pass the live parent/child check),
- avoid stopping only the launcher and leaving the real listener running, still holding the port.

## Model (`scripts\windows\RunningAI.ConnectorOwnership.ps1`)

Unchanged: `Read-PidFile`/`Write-PidFile`/`Test-ProcessIdentity`/`Get-TrackedProcessId` remain the
sole source of truth for "the tracked PID". This file adds a second, read-only cross-check on top -
given the tracked PID and the real LISTEN PID, decide which live PIDs can safely be treated as "this
connector instance" before anything is ever stopped or reported.

`Get-RunningAiConnectorOwnership -Port -TrackedPid -Markers [-Name]` returns one verdict:

| Verdict | Meaning | ManagedPids |
|---|---|---|
| `SELF_OWNED` | A single process (no separate launcher) carries every marker. | `[pid]` |
| `LAUNCHER_CHILD` | Tracked launcher alive and ours; listener is its live-verified child (`ParentProcessId` match + `CreationDate` at/after the launcher's). | `[launcherPid, listenerPid]` |
| `ORPHANED_MANAGED_PROCESS` | Tracked launcher is gone/foreign, but the sidecar metadata positively re-identifies the current listener (PID **and** creation time both match - never a bare PID guess). | `[listenerPid]` |
| `FOREIGN_PROCESS` | Port held by what looks like a connector (`garmin_connector` + `serve` in its command line) for a **different** repository/worktree. | `[]` |
| `UNKNOWN_OWNER` | None of the above applies. Ownership cannot be established. | `[]` |
| `DOWN` | Nothing is listening on the port. | `[]` |

Only the first three verdicts are ever acted on by `Stop-RunningAiConnectorManaged`; `FOREIGN_PROCESS`
and `UNKNOWN_OWNER` are always refused, matching this phase's fail-safe requirement.

## Sidecar metadata file

`.runtime\<name>.owner.json` (next to the existing `.runtime\<name>.pid`; **never** replaces it).

```json
{
  "version": 1,
  "name": "garmin-connector",
  "port": 8765,
  "repoRoot": "C:\\running-ai-github",
  "recordedAt": "2026-10-10T02:42:54Z",
  "launcher": { "processId": 30876, "creationDate": "2026-10-10T02:42:51.1234567Z" },
  "listener": { "processId": 9484,  "creationDate": "2026-10-10T02:42:51.2345678Z" }
}
```

- `launcher` is `null` for a `SELF_OWNED` capture (no separate launcher to record).
- `creationDate` is UTC, `DateTime.ToString("o")` round-trip format.
- Written through a temp file + `File.Replace` (the same atomic pattern `RunningAI.Watchdog.ps1` uses
  for its own state/status JSON) - a reader never observes a half-written file.
- Written **only** by `Set-RunningAiConnectorOwnerMetaFromLive`, called from `start-running-ai.ps1` at
  the one moment both the launcher and the real listener are guaranteed alive and freshly created
  (immediately after a fresh start is confirmed healthy), and opportunistically refreshed (best-effort,
  never fatal) when an already-running connector is found healthy on a later run.

### Backward compatibility

- **Purely additive.** Its absence (a connector started before this change, or Spring/relay, which
  never get one) degrades every ownership function to the live parent/child check only - never an
  error, never a forced interpretation.
- **Corrupt/unreadable/wrong-version file** (`version != 1`, missing `listener.processId`, invalid
  JSON) is treated **exactly** like an absent file - `Read-RunningAiOwnerMeta` never throws.
- **No automatic migration.** Nothing here converts, retrofits or deletes an existing `.pid` file, and
  nothing writes a sidecar for an already-running process except the best-effort refresh path
  described above (itself read-only towards the `.pid` file). A connector already running when this
  change deploys simply has no sidecar until its next clean start - during that gap, if its tracked
  launcher were to exit, the orphaned listener resolves to `UNKNOWN_OWNER` (refused, never
  auto-managed) rather than `ORPHANED_MANAGED_PROCESS` - safe-by-default, not silently broken.
- **PID reuse protection:** every match requires **both** the PID number **and** the recorded
  `CreationDate` (within a 2-second tolerance, normalized to UTC on both sides) to agree. A `.pid`
  file or sidecar entry whose PID has been reassigned by Windows to an unrelated process never
  matches its old creation time and is correctly rejected.

## Safe stop (`Stop-RunningAiConnectorManaged`)

1. Refuses immediately or unless the verdict is `SELF_OWNED` / `LAUNCHER_CHILD` /
   `ORPHANED_MANAGED_PROCESS` - touches nothing otherwise.
2. Re-validates every managed PID immediately before acting (closes the resolution-to-action gap).
3. Sends Ctrl+C individually to **every** verified managed PID (launcher and listener separately -
   see `Send-CtrlC.ps1`'s own comment for why console-group broadcast from parent to child is not
   relied upon here), then waits for both the port to free **and** every managed PID to exit.
4. On timeout, force-stops (`Stop-Process -Force`) only the still-alive, still-identity-verified
   managed PID(s), one call per PID - never a process-tree kill, never `taskkill /T /F`, never a PID
   outside the verified set (a sibling `conhost.exe` or any bystander process is never touched).
5. Always returns a result (`graceful` / `forced` / `refused` / `already-gone` / `orphan-remaining`)
   that reports the actual final port/PID state - a caller never has to guess.

## Integration points

- `start-running-ai.ps1`: captures metadata right after a fresh connector start is confirmed healthy
  (and opportunistically on an already-healthy connector found on a later run); its
  `Stop-NewConnectorOnFailure` (cleanup when Spring fails to start afterward) now goes through
  ownership resolution + `Stop-RunningAiConnectorManaged`.
- `stop-running-ai.ps1`: the connector gets its own `Stop-GarminConnectorComponent` (new), calling the
  ownership model. **Spring and the external relay are untouched** - they still go through the
  existing, unmodified `Stop-Component` + `Stop-TrackedProcess`.
- `RunningAI.Watchdog.ps1`: `Get-ConnectorComponentState` (new) replaces the connector's call to
  `Get-ProcessComponentState` in `Get-ComponentStates`. It is byte-for-byte identical to the legacy
  function whenever its new `-Port` parameter is `$null` (the default, and what every existing
  hand-built `Observation` in tests still gets) - only `watch-running-ai.ps1`'s real invocation passes
  it, enabling the richer classification (`ORPHANED_MANAGED_PROCESS` -> `UNHEALTHY` -> the existing
  restart-with-pre-stop path; `UNKNOWN_OWNER` -> `DEGRADED`, blocked, same as `FOREIGN_PROCESS`
  already was). `Invoke-RecoveryAction`'s connector `PreStop` branch uses the same ownership-aware
  stop. **Spring and relay's classification/recovery paths are unmodified.** The restart-budget,
  dependency-order and blocking logic in `Get-RecoveryPlan` itself is unchanged.

## What this phase deliberately does NOT do

- Does not change `Get-ProcessComponentState`, `Stop-TrackedProcess`, or any Spring/relay call site.
- Does not change the Watchdog's restart-budget policy, dependency ordering, or scheduler.
- Does not retroactively tag an already-running connector's `.pid` file or auto-create its sidecar.
- Does not touch the real Garmin connector, port 8765, or any operational `.env`/process at any point
  in its own test suite (`scripts\windows\tests\Test-ConnectorOwnership.ps1` uses only temporary
  loopback ports and short-lived PowerShell test processes it creates and tears down itself).
