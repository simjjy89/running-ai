<#
.SYNOPSIS
  Stops the RunningAI runtime: the external relay first (it is the public-facing component),
  then Spring Boot, then the Garmin connector. PostgreSQL is left running (data is never removed)
  unless -StopDatabase is given.

.DESCRIPTION
  Only processes recorded by start-running-ai.ps1 (.runtime\*.pid) whose live command line still
  matches this repository are touched; legacy RunningAI processes and unrelated PIDs are ignored.
  Shutdown is graceful (Ctrl+C -> Spring shutdown hooks / uvicorn / the relay's own SIGINT
  handler); a forced kill is used only after the timeout. "docker compose down -v" is never run.

.PARAMETER StopDatabase
  Also runs "docker compose stop" (containers stop, the data volume is kept).
#>
[CmdletBinding()]
param(
    [switch]$StopDatabase,
    [int]$ConnectorPort = 8765,
    [int]$SpringTimeoutSec = 30,
    [int]$ConnectorTimeoutSec = 15,
    [int]$RelayTimeoutSec = 15
)

. "$PSScriptRoot\RunningAI.Common.ps1"
. "$PSScriptRoot\RunningAI.ConnectorOwnership.ps1"
. "$PSScriptRoot\external\RunningAI.ExternalRelay.Common.ps1"

function Stop-Component {
    param([string]$Label, [string]$PidName, [string[]]$Markers, [int]$TimeoutSec)
    $tracked = Get-TrackedProcessId $PidName $Markers
    if ($null -eq $tracked) {
        Write-Step "${Label}: not running under RunningAI control (nothing to stop)"
        return
    }
    $how = Stop-TrackedProcess -ProcessId $tracked -Markers $Markers -TimeoutSec $TimeoutSec
    Remove-PidFile $PidName
    Write-Step "${Label}: stopped ($how, PID $tracked)"
}

# Garmin connector-specific (Phase 6I-1.7B-1): the venv launcher Write-PidFile records may not be
# the process that actually holds the TCP port (see RunningAI.ConnectorOwnership.ps1). Stop-Component
# above (unchanged, still used for Spring/relay) only ever stops the single tracked PID, so it was
# never safe here - an unhealthy-but-tracked-gone orphan child would survive it, still holding the
# port, while Remove-PidFile made it look stopped.
# Returns $true when the connector ended up fully stopped (or was already down) - $false for any
# ownership-unverified or partial/failed outcome. Phase 6I-1.7B-2A: the caller uses this to pick a
# non-zero exit code rather than always exiting 0 regardless of what actually happened.
function Stop-GarminConnectorComponent {
    param([int]$Port, [int]$TimeoutSec)
    $tracked = Read-PidFile 'garmin-connector'
    $ownership = Get-RunningAiConnectorOwnership -Port $Port -TrackedPid $tracked -Markers (Get-ConnectorMarkers)
    if ($ownership.Verdict -eq 'DOWN') {
        Write-Step 'Garmin connector: not running under RunningAI control (nothing to stop)'
        Remove-PidFile 'garmin-connector'
        return $true
    }
    if ($ownership.ManagedPids.Count -eq 0) {
        Write-Step "Garmin connector: NOT stopped - ownership could not be verified (verdict=$($ownership.Verdict)); refusing to touch an unidentified process on port $Port."
        return $false
    }
    $result = Stop-RunningAiConnectorManaged -Ownership $ownership -Port $Port -TimeoutSec $TimeoutSec
    # Phase 6I-1.7B-1R: only a fully confirmed stop (port free AND no managed PID remaining) clears
    # the PID file/metadata - never RemainingPids.Count=0 alone, and never on 'refused' or
    # 'ownership-changed'. A partial/failed stop preserves both files and reports exactly why, so a
    # subsequent run (manual or Watchdog) sees the real state instead of a falsely "clean" one.
    $cleanStop = Test-RunningAiConnectorStopWasClean -StopResult $result
    if ($cleanStop) {
        Remove-PidFile 'garmin-connector'
        Write-Step "Garmin connector: stopped ($($result.Result), verdict $($ownership.Verdict), managed PID(s) $($ownership.ManagedPids -join ', '))"
        return $true
    }
    Write-Step "Garmin connector: NOT fully stopped (result=$($result.Result), resultCode=$($result.ResultCode), portFreed=$($result.PortFreed), remaining PID(s)=$($result.RemainingPids -join ', ')); PID file and metadata preserved."
    return $false
}

try {
    Stop-Component 'External relay' 'external-relay' (Get-ExternalRelayMarkers) $RelayTimeoutSec
    Stop-Component 'Spring Boot' 'spring' (Get-SpringMarkers) $SpringTimeoutSec
    $connectorStopped = Stop-GarminConnectorComponent -Port $ConnectorPort -TimeoutSec $ConnectorTimeoutSec

    if ($StopDatabase) {
        $compose = Join-Path (Get-RepoRoot) 'docker-compose.yml'
        if ((Invoke-NativeQuiet 'docker' "compose -f $(Quote-Argument $compose) stop") -ne 0) {
            Stop-WithError $ExitCode.Postgres 'docker compose stop failed.'
        }
        Write-Step 'PostgreSQL: stopped (data volume kept)'
    } else {
        Write-Step 'PostgreSQL: left running (use -StopDatabase to stop it; data is never deleted)'
    }
    # Phase 6I-1.7B-2A: a connector that did not fully stop (ownership unverified, partial/failed
    # stop) must never be reported via exit code 0 - relay/Spring having stopped cleanly does not
    # change that; the operator or a caller script checking $LASTEXITCODE needs to see this failed.
    if (-not $connectorStopped) { exit $ExitCode.ConnectorStopIncomplete }
    exit $ExitCode.Ok
} catch {
    Write-Host "ERROR: $($_.Exception.Message)" -ForegroundColor Red
    exit (Get-ExitCodeFromError $_)
}
