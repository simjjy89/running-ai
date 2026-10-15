<#
.SYNOPSIS
  Registers the "RunningAI-Watchdog" Scheduled Task: at logon (after a 5 minute delay, so
  RunningAI-Startup can finish) and then every 5 minutes, run scripts\windows\watch-running-ai.ps1
  as the current user (hidden, MultipleInstances=IgnoreNew, no stored password, no elevation).

  TWO triggers are registered (Phase 9, A-1): the AtLogOn one above plus a standing, backdated
  Once+repetition trigger that is not tied to a logon. See New-WatchdogTaskParts for the measured
  failure that makes the second one necessary - a logon trigger's repetition, once interrupted,
  cannot be restored by Enable/Start/re-register and leaves the task looking healthy while it never
  fires again. Supervision that reports Ready but has stopped running is worse than none.

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
        Write-Host "Trigger 1        : AtLogOn ($($parts.User)), delay $($parts.Trigger.Delay), repeat every $($parts.Trigger.Repetition.Interval)"
        Write-Host "Trigger 2        : Once (backdated, logon-independent), repeat every $($parts.Triggers[1].Repetition.Interval)"
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
    Register-ScheduledTask -TaskName $TaskName -Action $parts.Action -Trigger $parts.Triggers -Principal $parts.Principal `
        -Settings $parts.Settings -Description 'RunningAI watchdog: detects failures and safely recovers the runtime every 5 minutes.' -Force | Out-Null
    Write-Step "Scheduled task '$TaskName' registered (AtLogOn +5m and a logon-independent standing trigger, every 5 minutes, $($parts.User))."

    # The only honest proof that the repetition is actually armed. State = Ready and
    # LastTaskResult = 0 were both true throughout the 2026-10-15 outage in which the task never ran
    # once; an empty NextRunTime was the sole signal. Reported, not thrown: registration did succeed,
    # and the scheduler can take a moment to compute the next occurrence.
    $info = Get-ScheduledTaskInfo -TaskName $TaskName -ErrorAction SilentlyContinue
    if ($info -and $info.NextRunTime) {
        Write-Step "Next run time: $($info.NextRunTime) - repetition is armed."
    } else {
        Write-Host "WARNING: '$TaskName' reports no NextRunTime. State/LastTaskResult cannot detect this; confirm with two unattended ticks of .runtime\watchdog-status.json before trusting supervision." -ForegroundColor Yellow
    }
    exit $ExitCode.Ok
} catch {
    Write-Host "ERROR: $($_.Exception.Message)" -ForegroundColor Red
    exit (Get-ExitCodeFromError $_)
}
