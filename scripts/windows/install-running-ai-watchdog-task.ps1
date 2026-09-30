<#
.SYNOPSIS
  Registers the "RunningAI-Watchdog" Scheduled Task: at logon (after a 5 minute delay, so
  RunningAI-Startup can finish) and then every 5 minutes, run scripts\windows\watch-running-ai.ps1
  as the current user (hidden, MultipleInstances=IgnoreNew, no stored password, no elevation).

  Separate from RunningAI-Startup, which is left untouched. Idempotent; a task with this name that
  does not point at watch-running-ai.ps1 is never overwritten. -DryRun prints the definition only.
#>
[CmdletBinding()]
param([switch]$DryRun, [string]$TaskName = 'RunningAI-Watchdog')

. "$PSScriptRoot\RunningAI.Watchdog.ps1"

try {
    $parts = New-WatchdogTaskParts
    $existing = Get-ScheduledTask -TaskName $TaskName -ErrorAction SilentlyContinue

    if ($DryRun) {
        Write-Host 'DRY RUN - nothing will be registered.'
        Write-Host "Task name        : $TaskName"
        Write-Host "Trigger          : AtLogOn ($($parts.User)), delay $($parts.Trigger.Delay), repeat every $($parts.Trigger.Repetition.Interval)"
        Write-Host "Run as           : $($parts.User) (interactive, limited, no stored password)"
        Write-Host "Multiple runs    : $($parts.Settings.MultipleInstances)"
        Write-Host "Action           : $($parts.Execute) $($parts.Arguments)"
        Write-Host "Working directory: $(Get-RepoRoot)"
        Write-Host ("Existing task    : " + $(if ($existing) { 'yes (re-registered only if it targets watch-running-ai.ps1)' } else { 'no' }))
        exit $ExitCode.Ok
    }

    if ($existing) {
        $targets = ($existing.Actions | ForEach-Object { "$($_.Execute) $($_.Arguments)" }) -join ' '
        if ($targets -notlike '*watch-running-ai.ps1*') {
            Stop-WithError $ExitCode.Other "A scheduled task named '$TaskName' already exists and is not a RunningAI watchdog task. Refusing to overwrite it; use -TaskName to choose another name."
        }
        Write-Step "Updating existing task '$TaskName'."
    }
    Register-ScheduledTask -TaskName $TaskName -Action $parts.Action -Trigger $parts.Trigger -Principal $parts.Principal `
        -Settings $parts.Settings -Description 'RunningAI watchdog: detects failures and safely recovers the runtime every 5 minutes.' -Force | Out-Null
    Write-Step "Scheduled task '$TaskName' registered (AtLogOn +5m, every 5 minutes, $($parts.User))."
    exit $ExitCode.Ok
} catch {
    Write-Host "ERROR: $($_.Exception.Message)" -ForegroundColor Red
    exit (Get-ExitCodeFromError $_)
}
