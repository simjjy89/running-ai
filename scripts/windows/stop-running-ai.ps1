<#
.SYNOPSIS
  Stops the RunningAI runtime: Spring Boot first, then the Garmin connector.
  PostgreSQL is left running (data is never removed) unless -StopDatabase is given.

.DESCRIPTION
  Only processes recorded by start-running-ai.ps1 (.runtime\*.pid) whose live command line still
  matches this repository are touched; legacy RunningAI processes and unrelated PIDs are ignored.
  Shutdown is graceful (Ctrl+C -> Spring shutdown hooks / uvicorn); a forced kill is used only
  after the timeout. "docker compose down -v" is never run.

.PARAMETER StopDatabase
  Also runs "docker compose stop" (containers stop, the data volume is kept).
#>
[CmdletBinding()]
param(
    [switch]$StopDatabase,
    [int]$SpringTimeoutSec = 30,
    [int]$ConnectorTimeoutSec = 15
)

. "$PSScriptRoot\RunningAI.Common.ps1"

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
