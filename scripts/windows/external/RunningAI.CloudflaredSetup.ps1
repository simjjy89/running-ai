<#
  Shared helpers for the Cloudflare Named Tunnel external-access setup (Phase 6I-1). Dot-source,
  do not run. Pure/parsing functions are kept separate from the orchestrator
  (setup-running-ai-external-access.ps1) so they can be unit tested without touching the real
  cloudflared binary, the real Windows Service, or the real %USERPROFILE%\.cloudflared directory.

  Decisions this encodes (see docs/work-orders/2026-10-06-phase-6i-1-...-instruction.md and its
  2026-10-06 addendum, plus the 2026-10-07 Named Tunnel confirmation):
    - Transport: Cloudflare Named Tunnel (not ipTIME WireGuard, not a quick tunnel in production).
    - Origin is ALWAYS http://127.0.0.1:17845 (external-relay) - Spring (8080) and the Garmin
      connector (8765) are never referenced by any ingress rule this generates.
    - A catch-all `http_status:404` ingress rule is always the last rule - no hostname outside
      the one configured one is ever forwarded anywhere.
    - cloudflared's own credential files (cert.pem, <tunnel-id>.json) live under
      %USERPROFILE%\.cloudflared, outside this repository, and are never read/printed/logged by
      anything here - only their presence (Test-Path) is ever checked.
#>

Set-StrictMode -Version Latest

$script:RunningAiTunnelName = 'running-ai-external-relay'
$script:RunningAiCloudflaredConfigMarker = '# managed-by: running-ai-github scripts/windows/external/setup-running-ai-external-access.ps1 - do not hand-edit'
$script:RunningAiCloudflaredDir = Join-Path $env:USERPROFILE '.cloudflared'
$script:RunningAiCloudflaredConfigPath = Join-Path $script:RunningAiCloudflaredDir 'config.yml'
$script:RunningAiCloudflaredCertPath = Join-Path $script:RunningAiCloudflaredDir 'cert.pem'
$script:RunningAiRelayOrigin = 'http://127.0.0.1:17845'

function Test-RunningAiCloudflaredLoggedIn {
    param([string]$CertPath = $script:RunningAiCloudflaredCertPath)
    Test-Path -LiteralPath $CertPath
}

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

# Parses `cloudflared tunnel list` text output for a tunnel named $script:RunningAiTunnelName.
# Returns its UUID, or $null if no tunnel with that exact name is listed.
function Find-RunningAiNamedTunnelId {
    param([Parameter(Mandatory)][AllowEmptyString()][string]$ListOutput, [string]$TunnelName = $script:RunningAiTunnelName)
    foreach ($line in ($ListOutput -split "`r?`n")) {
        if ($line -match "^\s*([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})\s+$([regex]::Escape($TunnelName))(\s|$)") {
            return $Matches[1]
        }
    }
    return $null
}

# Parses `cloudflared tunnel create <name>` text output for the newly created tunnel's UUID.
function Find-RunningAiCreatedTunnelId {
    param([Parameter(Mandatory)][AllowEmptyString()][string]$CreateOutput)
    if ($CreateOutput -match '\bwith id ([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})\b') {
        return $Matches[1]
    }
    return $null
}

# True when cloudflared's own "already exists / already routed" style message is present - these
# are success-equivalent outcomes for an idempotent re-run, not real failures.
function Test-RunningAiCloudflaredAlreadyDoneMessage {
    param([Parameter(Mandatory)][AllowEmptyString()][string]$Output)
    $Output -match '(?i)already\s+(exists|has a DNS record|routed)'
}

# The exact config.yml content this setup writes: fixed origin, catch-all 404 last. Pure string
# builder - no file I/O, no secrets (the credentials file PATH is not a secret; its contents are
# never read by anything in this repo).
function Get-RunningAiCloudflaredConfigYaml {
    param(
        [Parameter(Mandatory)][string]$TunnelId,
        [Parameter(Mandatory)][string]$Hostname,
        [Parameter(Mandatory)][string]$CredentialsFile
    )
    @"
$script:RunningAiCloudflaredConfigMarker
tunnel: $TunnelId
credentials-file: $CredentialsFile

ingress:
  - hostname: $Hostname
    service: $script:RunningAiRelayOrigin
  - service: http_status:404
"@ -replace "`r`n", "`n"
}

# True when it is safe to (over)write $Path: either nothing is there yet, or what is there was
# written by this same setup script (carries the marker comment). False means a pre-existing,
# unrelated cloudflared config - never touched.
function Test-RunningAiCloudflaredConfigIsOurs {
    param([Parameter(Mandatory)][string]$Path)
    if (-not (Test-Path -LiteralPath $Path)) { return $true }
    $content = Get-Content -LiteralPath $Path -Raw -ErrorAction SilentlyContinue
    return [bool]($content -and $content.Contains($script:RunningAiCloudflaredConfigMarker))
}

function Get-RunningAiTunnelCredentialsFilePath {
    param([Parameter(Mandatory)][string]$TunnelId, [string]$CloudflaredDir = $script:RunningAiCloudflaredDir)
    Join-Path $CloudflaredDir "$TunnelId.json"
}
