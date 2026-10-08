<#
  DEFERRED 2026-10-09: the external transport decision was pivoted from Cloudflare Named Tunnel to
  Tailscale Funnel (no domain purchase, zero ongoing cost via Tailscale's stable *.ts.net HTTPS
  hostname - see RunningAI.TailscaleSetup.ps1 and setup-running-ai-tailscale-funnel.ps1, the
  current transport). This file is kept, not deleted, as a historical/fallback reference: it was
  live-tested (-DryRun, zero real changes) and may be revisited if Tailscale Funnel turns out not
  to work for the real external-access requirement. Do not build further on this file without
  first re-confirming the transport decision - see the Phase 6I-1 work-order docs.

  Shared helpers for the Cloudflare Named Tunnel external-access setup (Phase 6I-1). Dot-source,
  do not run. Pure/parsing functions are kept separate from the orchestrator
  (setup-running-ai-external-access.ps1) so they can be unit tested without touching the real
  cloudflared binary, the real Windows Service, or the real %USERPROFILE%\.cloudflared directory.

  Decisions this encoded (see docs/work-orders/2026-10-06-phase-6i-1-...-instruction.md and its
  2026-10-06 addendum, plus the 2026-10-07 Named Tunnel confirmation):
    - Transport: Cloudflare Named Tunnel (not ipTIME WireGuard, not a quick tunnel in production).
    - Origin is ALWAYS http://127.0.0.1:17845 (external-relay) - Spring (8080) and the Garmin
      connector (8765) are never referenced by any ingress rule this generates.
    - A catch-all `http_status:404` ingress rule is always the last rule - no hostname outside
      the one configured one is ever forwarded anywhere.
    - cloudflared's own credential files (cert.pem, <tunnel-id>.json) live under
      %USERPROFILE%\.cloudflared, outside this repository, and are never read/printed/logged by
      anything here - only their presence (Test-Path) is ever checked.

  RUNNING_AI_EXTERNAL_BASE_URL .env helpers (ConvertTo-RunningAiHostname,
  Get-/Set-RunningAiExternalHostnameFromEnvFile) moved to RunningAI.ExternalRelay.Common.ps1 on
  2026-10-09 - they were never Cloudflare-specific, and Tailscale's setup needs the exact same
  logic. Both this file's orchestrator and the Tailscale one dot-source that file first.
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
