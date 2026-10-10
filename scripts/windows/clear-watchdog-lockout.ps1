<#
.SYNOPSIS
  Explicit, manual operator action (Phase 6I-1.7B-2B): clears a component's long-term Watchdog
  lockout. Never called automatically by watch-running-ai.ps1, RunningAI.Watchdog.ps1, or any
  Scheduled Task - the whole point of a long-term lockout (STEP C) is that it requires a human to
  look at WHY a component kept failing before recovery resumes.

.DESCRIPTION
  Reads the real watchdog-state.json, clears the named component's lockout flag AND its long-term
  failure history (a fresh start, not just unlocking on top of an already-near-threshold count), and
  writes it back atomically. The short-term restart history (10-minute/3-restart budget) and every
  OTHER component's long-term state are left completely untouched.

  Requires -Confirm (or -Force) to actually write - run once without either to preview what would
  change.

.PARAMETER Component
  One of: docker, postgres, connector, spring, external-relay.

.PARAMETER Force
  Actually writes the change. Without it, this is a dry-run preview only.
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory)][ValidateSet('docker', 'postgres', 'connector', 'spring', 'external-relay')][string]$Component,
    [switch]$Force
)

. "$PSScriptRoot\RunningAI.Watchdog.ps1"

try {
    $state = Read-WatchdogState
    if (-not $state.Available) {
        Stop-WithError $ExitCode.WatchdogError "watchdog-state.json is not readable ($($state.Note)); refusing to clear a lockout against an unreadable state. Investigate and fix the file first."
    }

    $longTermState = $state.LongTermState
    $before = $longTermState[$Component]
    $wasLocked = [bool]$before.LockedOutSince
    $failureCount = @($before.Failures).Count

    if (-not $wasLocked) {
        Write-Step "Component '$Component' is not currently locked out (failure history: $failureCount recorded entries). Nothing to clear."
        if (-not $Force) { exit $ExitCode.Ok }
    }

    Write-Step "Component '$Component': currently locked out since $(if ($before.LockedOutSince) { [DateTimeOffset]::FromUnixTimeSeconds($before.LockedOutSince).UtcDateTime.ToString('o') } else { 'n/a' }), $failureCount failure(s) recorded."

    if (-not $Force) {
        Write-Step "DRY RUN - pass -Force to actually clear this lockout and its failure history. No file was changed."
        exit $ExitCode.Ok
    }

    Clear-RunningAiComponentLockout -LongTermState $longTermState -Component $Component
    Save-WatchdogState -History $state.History -LongTermState $longTermState
    Write-Step "Component '$Component': lockout and long-term failure history cleared. Short-term restart budget and every other component are unchanged."
    exit $ExitCode.Ok
} catch {
    Write-Host "ERROR: $($_.Exception.Message)" -ForegroundColor Red
    exit (Get-ExitCodeFromError $_)
}
