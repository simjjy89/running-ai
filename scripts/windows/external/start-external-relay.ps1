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

try {
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
}
