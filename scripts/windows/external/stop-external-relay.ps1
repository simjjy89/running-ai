<#
.SYNOPSIS
  Stops the external-relay process, but only if it was started by start-external-relay.ps1
  (tracked PID file whose live command line still matches this repository). A legacy/unmanaged
  instance - including the pre-existing ad hoc legacy watchface-relay process (see
  tools/external-relay/README.md for its origin) - is left untouched and reported as
  "not running under RunningAI control".
#>
[CmdletBinding()]
param([int]$TimeoutSec = 15)

. "$PSScriptRoot\..\RunningAI.Common.ps1"
. "$PSScriptRoot\RunningAI.ExternalRelay.Common.ps1"

# Phase 6I-1.7B-2C (STEP 1): same runtime lock as start-external-relay.ps1 - see its comment.
$runtimeLock = Enter-RunningAiRuntimeLock -TimeoutSec 5
if (-not $runtimeLock) {
    Write-Step 'Another start/stop against this runtime is already in progress; skipping (no service state changed).'
    exit $ExitCode.Busy
}

try {
    $tracked = Get-TrackedProcessId 'external-relay' (Get-ExternalRelayMarkers)
    if ($null -eq $tracked) {
        Write-Step 'External relay: not running under RunningAI control (nothing to stop)'
        exit $ExitCode.Ok
    }
    $how = Stop-TrackedProcess -ProcessId $tracked -Markers (Get-ExternalRelayMarkers) -TimeoutSec $TimeoutSec
    Remove-PidFile 'external-relay'
    Write-Step "External relay: stopped ($how, PID $tracked)"
    exit $ExitCode.Ok
} catch {
    Write-Host "ERROR: $($_.Exception.Message)" -ForegroundColor Red
    exit (Get-ExitCodeFromError $_)
} finally {
    Exit-RunningAiRuntimeLock $runtimeLock
}
