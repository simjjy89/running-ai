<#
.SYNOPSIS
  Starts tools/external-relay/server.js (the RUNNER ARCADE watch face relay) if it is not
  already healthy on its configured port.

.DESCRIPTION
  Phase 6I-1, infrastructure-neutral stage: this manages the relay NODE PROCESS ONLY. It starts
  no tunnel and configures no public exposure - that is a separate, not-yet-decided step.

  Refuses to start a second process on an already-listening port, and does not try to tell
  whether whatever is already listening is this script's own managed instance or the pre-existing
  ad hoc legacy watchface-relay instance (see tools/external-relay/README.md for its origin;
  as of this phase it is still the one serving real watch face traffic). If the legacy instance
  is still running and healthy on this port, this script reports "already running, not
  restarted" and exits without touching it; if something unhealthy holds the port, it reports
  "in use, not managed" and exits without touching it either way.

  Exit code 0 when the relay is healthy (already running or newly started); 1 otherwise.
#>
[CmdletBinding()]
param(
    [int]$Port,
    [int]$TimeoutSec = 15
)

. "$PSScriptRoot\..\RunningAI.Common.ps1"
. "$PSScriptRoot\RunningAI.ExternalRelay.Common.ps1"

if (-not $PSBoundParameters.ContainsKey('Port')) { $Port = Get-ExternalRelayConfiguredPort }

# Phase 6I-1.7B-2C (STEP 1): the SAME runtime lock start-running-ai.ps1/stop-running-ai.ps1 use,
# so a manual run of this script is mutually exclusive with the full start/stop and with a
# Watchdog-driven relay recovery action - none of them may touch the relay process concurrently.
# A Watchdog-spawned child inherits RUNNING_AI_WATCHDOG_LOCK_INHERITED and skips acquiring (its
# parent already holds it); a manual run always acquires normally.
$runtimeLock = Enter-RunningAiRuntimeLock -TimeoutSec 5
if (-not $runtimeLock) {
    Write-Step 'Another start/stop against this runtime is already in progress; skipping (no service state changed).'
    exit $ExitCode.Busy
}

try {
    # Load the repo-root .env into THIS process before anything that spawns node: this script runs
    # standalone, in its own process (the Watchdog Scheduled Task invokes it in a process separate
    # from start-running-ai.ps1, which is the only other caller that happened to load .env already),
    # so it cannot assume a parent already did this. Start-Process below inherits this process's
    # full environment block, so INTERVALS_ICU_API_KEY (and any other .env-only key) reaches the
    # relay child exactly the same way a manually-set $env:... variable would. Existing process env
    # still wins over .env (see RunningAI.Common.ps1) - this never forces an override, only fills a
    # gap a parent process left empty. RUNNING_AI_TEST_ENV_ROOT is a test-only escape hatch (mirrors
    # RUNNING_AI_TEST_RUNTIME_DIR) so a test can supply a disposable .env without touching the real
    # repo-root one; never set outside a test.
    $envRoot = if ($env:RUNNING_AI_TEST_ENV_ROOT) { $env:RUNNING_AI_TEST_ENV_ROOT } else { Get-RepoRoot }
    Initialize-DotEnvForThisProcess -Root $envRoot

    if (Test-ExternalRelayHealth $Port) {
        Write-Step "External relay: UP on 127.0.0.1:$Port (already running, not restarted)"
        exit $ExitCode.Ok
    }

    if (Test-PortInUse $Port) {
        Write-Host "ERROR: Port $Port is in use but /health is not a healthy external relay. Refusing to start a second process on the same port - check what is already listening before retrying." -ForegroundColor Red
        exit $ExitCode.Other
    }

    $tokenFile = Join-Path $script:RelayDir 'secrets\watch-token.json'
    if (-not (Test-Path -LiteralPath $tokenFile)) {
        Write-Host "ERROR: No secrets\watch-token.json in $script:RelayDir. Run 'node generate-token.js' from that directory first." -ForegroundColor Red
        exit $ExitCode.Other
    }

    $node = Get-Command node -ErrorAction SilentlyContinue
    if (-not $node) {
        Write-Host 'ERROR: node is not on PATH.' -ForegroundColor Red
        exit $ExitCode.Other
    }

    Get-TrackedProcessId 'external-relay' (Get-ExternalRelayMarkers) | Out-Null   # cleans a stale PID file
    foreach ($n in 'external-relay.out', 'external-relay.err') { Invoke-LogRotation -LogDir $script:LogDir -Name $n -Always | Out-Null }
    Enable-CtrlCInheritance

    $serverJs = Join-Path $script:RelayDir 'server.js'
    $proc = Start-Process -FilePath $node.Source -ArgumentList (Quote-Argument $serverJs) `
        -WorkingDirectory $script:RelayDir -WindowStyle Hidden -PassThru `
        -RedirectStandardOutput (Join-Path $script:LogDir 'external-relay.out.log') `
        -RedirectStandardError (Join-Path $script:LogDir 'external-relay.err.log')
    Write-PidFile 'external-relay' $proc.Id

    $ready = Wait-Until -TimeoutSec $TimeoutSec -PollSec 1 -Test { Test-ExternalRelayHealth $Port } `
        -Abort { if ($proc.HasExited) { "exited with code $($proc.ExitCode)" } }
    if (-not $ready) {
        $why = if ($proc.HasExited) { "The process exited with code $($proc.ExitCode)." } else { "No healthy response within $TimeoutSec seconds." }
        Write-Host "ERROR: External relay did not become healthy. $why See .runtime\logs\external-relay.err.log" -ForegroundColor Red
        exit $ExitCode.Other
    }
    Write-Step "External relay: UP on 127.0.0.1:$Port (PID $($proc.Id))"
    exit $ExitCode.Ok
} catch {
    Write-Host "ERROR: $($_.Exception.Message)" -ForegroundColor Red
    exit (Get-ExitCodeFromError $_)
} finally {
    Exit-RunningAiRuntimeLock $runtimeLock
}
