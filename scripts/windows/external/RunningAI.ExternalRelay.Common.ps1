<#
  Shared helpers for the external-relay (tools/external-relay) lifecycle scripts. Dot-source,
  do not run. Mirrors the PID-file / process-identity / health-check pattern RunningAI.Common.ps1
  already uses for the Garmin connector and Spring Boot.

  Phase 6I-1: this manages the relay PROCESS ONLY (node server.js). It does not start, stop, or
  know about any tunnel (cloudflared, WireGuard, or otherwise) - transport is a separate,
  not-yet-decided concern. It is not yet called from start-running-ai.ps1, stop-running-ai.ps1,
  status-running-ai.ps1, or RunningAI.Watchdog.ps1 - wiring it in is a later step in this phase,
  once the external-transport decision is made.
#>

Set-StrictMode -Version Latest

$script:RelayDir = Join-Path $script:RepoRoot 'tools\external-relay'

function Get-ExternalRelayMarkers { @('server.js', $script:RelayDir) }

function Get-ExternalRelayConfiguredPort {
    $cfgPath = Join-Path $script:RelayDir 'config.json'
    if (Test-Path -LiteralPath $cfgPath) {
        try {
            $port = [int]((Get-Content -LiteralPath $cfgPath -Raw | ConvertFrom-Json).port)
            if ($port -gt 0) { return $port }
        } catch { }
    }
    return 17845
}

function Test-ExternalRelayHealth {
    param([int]$Port)
    $body = Get-HttpBody "http://127.0.0.1:$Port/health"
    if (-not $body) { return $false }
    try { return ((ConvertFrom-Json $body).status -eq 'ok') } catch { return $false }
}
