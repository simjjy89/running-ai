<#
  Shared helpers for the external-relay (tools/external-relay) lifecycle scripts. Dot-source,
  do not run. Mirrors the PID-file / process-identity / health-check pattern RunningAI.Common.ps1
  already uses for the Garmin connector and Spring Boot.

  Phase 6I-1: this manages the relay PROCESS ONLY (node server.js). It does not start, stop, or
  know about any tunnel (cloudflared, Tailscale, or otherwise) - transport-specific setup lives in
  its own file (RunningAI.CloudflaredSetup.ps1 - deferred 2026-10-09, kept only as historical
  reference - or RunningAI.TailscaleSetup.ps1, the current transport). It is not yet called from
  start-running-ai.ps1, stop-running-ai.ps1, status-running-ai.ps1, or RunningAI.Watchdog.ps1 -
  wiring it in is a later step in this phase.

  Also holds the transport-NEUTRAL helpers both setup scripts share: the RUNNING_AI_EXTERNAL_BASE_URL
  .env round trip (the concept of "the fixed external hostname" does not belong to either
  transport) and the external-access verification (health/auth/unsafe-path over HTTPS) that must
  behave identically regardless of what sits in front of the relay.
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

# ---- hostname / .env (transport-neutral) -----------------------------------------------------

# Accepts a bare hostname ("runningai.example.com") or a full URL typed by the user and returns
# just the hostname, or $null if it is not a plausible hostname. Never accepts a non-https scheme
# when a scheme is present (so a pasted http:// URL is rejected rather than silently downgraded).
function ConvertTo-RunningAiHostname {
    param([Parameter(Mandatory)][AllowEmptyString()][string]$InputText)
    $trimmed = $InputText.Trim()
    if (-not $trimmed) { return $null }
    $candidate = if ($trimmed -match '^[a-zA-Z][a-zA-Z0-9+.-]*://') { $trimmed } else { "https://$trimmed" }
    try {
        $uri = [Uri]$candidate
    } catch { return $null }
    if ($uri.Scheme -ne 'https') { return $null }
    if (-not $uri.Host) { return $null }
    if ($uri.Host -notmatch '^[a-zA-Z0-9]([a-zA-Z0-9-]*[a-zA-Z0-9])?(\.[a-zA-Z0-9]([a-zA-Z0-9-]*[a-zA-Z0-9])?)+$') { return $null }
    return $uri.Host
}

# Reads RUNNING_AI_EXTERNAL_BASE_URL out of a .env-style file. Returns $null if the file is
# missing, the key is absent, or the value is not a valid https hostname.
function Get-RunningAiExternalHostnameFromEnvFile {
    param([Parameter(Mandatory)][string]$EnvFilePath)
    if (-not (Test-Path -LiteralPath $EnvFilePath)) { return $null }
    $content = Get-Content -LiteralPath $EnvFilePath -Raw -ErrorAction SilentlyContinue
    if (-not $content) { return $null }
    foreach ($line in ($content -replace "`r`n", "`n").Split("`n")) {
        $trimmed = $line.Trim()
        if (-not $trimmed -or $trimmed.StartsWith('#')) { continue }
        $eq = $trimmed.IndexOf('=')
        if ($eq -lt 0) { continue }
        $key = $trimmed.Substring(0, $eq).Trim()
        if ($key -ne 'RUNNING_AI_EXTERNAL_BASE_URL') { continue }
        $value = $trimmed.Substring($eq + 1).Trim().Trim('"').Trim("'")
        return ConvertTo-RunningAiHostname $value
    }
    return $null
}

# Upserts RUNNING_AI_EXTERNAL_BASE_URL=https://<hostname> into a .env-style file, preserving every
# other line untouched. Creates the file if it does not exist.
function Set-RunningAiExternalHostnameInEnvFile {
    param([Parameter(Mandatory)][string]$EnvFilePath, [Parameter(Mandatory)][string]$Hostname)
    $line = "RUNNING_AI_EXTERNAL_BASE_URL=https://$Hostname"
    if (-not (Test-Path -LiteralPath $EnvFilePath)) {
        Set-Content -LiteralPath $EnvFilePath -Value $line -Encoding utf8
        return
    }
    $content = Get-Content -LiteralPath $EnvFilePath -Raw
    $lines = [System.Collections.Generic.List[string]]::new()
    $found = $false
    foreach ($l in ($content -replace "`r`n", "`n").TrimEnd("`n").Split("`n")) {
        if ($l.Trim().StartsWith('RUNNING_AI_EXTERNAL_BASE_URL=')) { $lines.Add($line); $found = $true } else { $lines.Add($l) }
    }
    if (-not $found) { $lines.Add($line) }
    Set-Content -LiteralPath $EnvFilePath -Value ($lines -join "`n") -Encoding utf8
}

# ---- external-access verification (transport-neutral) ----------------------------------------

# GET https://<hostname>/health, /today-workout (no credential), and an unlisted path, through
# whatever sits in front of the relay (Cloudflare Named Tunnel, Tailscale Funnel, ...). Returns a
# plain object the caller prints - never throws, never requires a secret. $TunnelDownStatuses lets
# a caller pass transport-specific "edge down" status codes (e.g. Cloudflare's 502/521/523/530);
# omit it for a transport with no such well-known codes.
function Test-RunningAiExternalAccess {
    param(
        [Parameter(Mandatory)][string]$Hostname,
        [int]$TimeoutSec = 15,
        [int[]]$TunnelDownStatuses = @()
    )
    $base = "https://$Hostname"

    $healthStatus = Get-HttpStatus -Url "$base/health" -TimeoutSec $TimeoutSec
    $healthState =
        if ($null -eq $healthStatus) { 'TUNNEL_DOWN' }
        elseif ($TunnelDownStatuses -contains $healthStatus) { 'TUNNEL_DOWN' }
        elseif ($healthStatus -eq 200) {
            $body = Get-HttpBody -Url "$base/health" -TimeoutSec $TimeoutSec
            $parsed = $null
            try { $parsed = $body | ConvertFrom-Json } catch { }
            if ($parsed -and $parsed.status -eq 'ok') { 'UP' } else { 'RELAY_DOWN' }
        } else { 'UNKNOWN' }

    $authStatus = Get-HttpStatus -Url "$base/today-workout" -TimeoutSec $TimeoutSec
    $authState = if ($authStatus -eq 401) { 'AUTH_ENFORCED' } elseif ($null -eq $authStatus) { 'UNKNOWN' } else { "UNEXPECTED_STATUS_$authStatus" }

    $unsafeStatus = Get-HttpStatus -Url "$base/does-not-exist" -TimeoutSec $TimeoutSec
    $unsafeState = if ($unsafeStatus -eq 404) { 'CATCH_ALL_ENFORCED' } elseif ($null -eq $unsafeStatus) { 'UNKNOWN' } else { "UNEXPECTED_STATUS_$unsafeStatus" }

    [pscustomobject]@{
        HealthState  = $healthState
        HealthStatus = $healthStatus
        AuthState    = $authState
        UnsafeState  = $unsafeState
    }
}
