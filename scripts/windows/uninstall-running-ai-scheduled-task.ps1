<#
.SYNOPSIS
  Removes only the "RunningAI-Startup" Scheduled Task created by install-running-ai-scheduled-task.ps1.
  A task with that name that does not point at start-running-ai.ps1 (legacy) is left alone.
  Running processes are not stopped; use stop-running-ai.ps1 for that.
#>
[CmdletBinding()]
param([string]$TaskName = 'RunningAI-Startup')

. "$PSScriptRoot\RunningAI.Common.ps1"

try {
    $existing = Get-ScheduledTask -TaskName $TaskName -ErrorAction SilentlyContinue
    if (-not $existing) {
        Write-Step "Scheduled task '$TaskName' does not exist; nothing to remove."
        exit $ExitCode.Ok
    }
    $targets = ($existing.Actions | ForEach-Object { "$($_.Execute) $($_.Arguments)" }) -join ' '
    if ($targets -notlike '*start-running-ai.ps1*') {
        Stop-WithError $ExitCode.Other "Task '$TaskName' does not point at start-running-ai.ps1; it is not managed by this repository and was not removed."
    }
    Unregister-ScheduledTask -TaskName $TaskName -Confirm:$false
    Write-Step "Scheduled task '$TaskName' removed."
    exit $ExitCode.Ok
} catch {
    Write-Host "ERROR: $($_.Exception.Message)" -ForegroundColor Red
    exit (Get-ExitCodeFromError $_)
}
