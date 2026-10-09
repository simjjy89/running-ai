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
    [int]$SpringTimeoutSec = 30,
    [int]$ConnectorTimeoutSec = 15,
    [int]$RelayTimeoutSec = 15
)

. "$PSScriptRoot\RunningAI.Common.ps1"
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

try {
    Stop-Component 'External relay' 'external-relay' (Get-ExternalRelayMarkers) $RelayTimeoutSec
    Stop-Component 'Spring Boot' 'spring' (Get-SpringMarkers) $SpringTimeoutSec
    Stop-Component 'Garmin connector' 'garmin-connector' (Get-ConnectorMarkers) $ConnectorTimeoutSec

    if ($StopDatabase) {
        $compose = Join-Path (Get-RepoRoot) 'docker-compose.yml'
        if ((Invoke-NativeQuiet 'docker' "compose -f $(Quote-Argument $compose) stop") -ne 0) {
            Stop-WithError $ExitCode.Postgres 'docker compose stop failed.'
        }
        Write-Step 'PostgreSQL: stopped (data volume kept)'
    } else {
        Write-Step 'PostgreSQL: left running (use -StopDatabase to stop it; data is never deleted)'
    }
    exit $ExitCode.Ok
} catch {
    Write-Host "ERROR: $($_.Exception.Message)" -ForegroundColor Red
    exit (Get-ExitCodeFromError $_)
}
