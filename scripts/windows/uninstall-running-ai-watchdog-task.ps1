<#
.SYNOPSIS
  Removes only the "RunningAI-Watchdog" Scheduled Task. RunningAI-Startup and legacy tasks are
  untouched; running processes are not stopped.
#>
[CmdletBinding()]
param([string]$TaskName = 'RunningAI-Watchdog')

. "$PSScriptRoot\RunningAI.Common.ps1"

try {
    $existing = Get-ScheduledTask -TaskName $TaskName -ErrorAction SilentlyContinue
    if (-not $existing) { Write-Step "Scheduled task '$TaskName' does not exist; nothing to remove."; exit $ExitCode.Ok }
    $targets = ($existing.Actions | ForEach-Object { "$($_.Execute) $($_.Arguments)" }) -join ' '
    if ($targets -notlike '*watch-running-ai.ps1*') {
        Stop-WithError $ExitCode.Other "Task '$TaskName' does not point at watch-running-ai.ps1; it is not managed by this repository and was not removed."
    }
    Unregister-ScheduledTask -TaskName $TaskName -Confirm:$false
    Write-Step "Scheduled task '$TaskName' removed."
    exit $ExitCode.Ok
} catch {
    Write-Host "ERROR: $($_.Exception.Message)" -ForegroundColor Red
    exit (Get-ExitCodeFromError $_)
}
