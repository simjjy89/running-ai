<#
  Shared helpers for the Tailscale Funnel external-access setup (Phase 6I-1, transport pivoted
  2026-10-09 from Cloudflare Named Tunnel). Dot-source, do not run. Pure/parsing functions are
  kept separate from the orchestrator (setup-running-ai-tailscale-funnel.ps1) so they can be unit
  tested without touching the real tailscale binary, the real tailscaled service, or a real
  tailnet.

  Decisions this encodes (see the 2026-10-09 transport-pivot instruction):
    - Transport: Tailscale Funnel (not Cloudflare, not ipTIME WireGuard/port-forwarding, no
      purchased domain) - a stable *.ts.net HTTPS hostname, zero ongoing cost.
    - Funnel target is ALWAYS http://127.0.0.1:<the external-relay's configured port> - Spring
      (8080) and the Garmin connector (8765) are never referenced.
    - Funnel is a PUBLIC endpoint with no equivalent of Cloudflare Access in front of it: the
      relay's own Bearer-token check on /today-workout is the only thing gating real data, and
      /health is reachable by anyone who learns the hostname. This is documented, not hidden.
    - Two conditions genuinely require the operator's own action outside this script and cannot
      be automated: the tailnet-level MagicDNS toggle and the Funnel node-attribute grant, both
      set in the Tailscale admin console. Rather than guess at exact error text, this library
      only checks what is reliably knowable from the CLI (version, login state, MagicDNS flag)
      and lets the live `tailscale funnel` command's own diagnostic surface everything else.
#>

Set-StrictMode -Version Latest

$script:RunningAiTailscaleMinVersion = [Version]'1.38.3'

function Get-RunningAiTailscaleExe {
    $cmd = Get-Command tailscale -ErrorAction SilentlyContinue
    if ($cmd) { return $cmd.Source }
    foreach ($p in @("$env:ProgramFiles\Tailscale\tailscale.exe", "$env:ProgramFiles (x86)\Tailscale\tailscale.exe")) {
        if ($p -and (Test-Path -LiteralPath $p)) { return $p }
    }
    return $null
}

# Parses the first line of `tailscale version` output (e.g. "1.104.1" or "1.104.1-t1234abcde")
# into a comparable [Version], dropping any non-numeric suffix. Returns $null if unparseable.
function ConvertTo-RunningAiTailscaleVersion {
    param([Parameter(Mandatory)][AllowEmptyString()][string]$VersionOutput)
    $firstLine = ($VersionOutput -split "`r?`n")[0].Trim()
    if ($firstLine -match '^(\d+\.\d+(\.\d+)?)') {
        try { return [Version]$Matches[1] } catch { return $null }
    }
    return $null
}

function Test-RunningAiTailscaleVersionSupported {
    param([Parameter(Mandatory)][AllowEmptyString()][string]$VersionOutput)
    $parsed = ConvertTo-RunningAiTailscaleVersion $VersionOutput
    if (-not $parsed) { return $false }
    return $parsed -ge $script:RunningAiTailscaleMinVersion
}

# `tailscale status --json`'s "BackendState" field: "Running" means logged in and connected;
# "NeedsLogin"/"Stopped"/anything else means `tailscale up` is still required.
function Test-RunningAiTailscaleLoggedIn {
    param([Parameter(Mandatory)][AllowEmptyString()][string]$StatusJson)
    try {
        $parsed = $StatusJson | ConvertFrom-Json
        return $parsed.BackendState -eq 'Running'
    } catch { return $false }
}

# `tailscale status --json`'s "CurrentTailnet.MagicDNSEnabled" field. Returns $null (not $false)
# when the field cannot be found at all, so the caller can tell "disabled" apart from "unknown".
function Test-RunningAiTailscaleMagicDnsEnabled {
    param([Parameter(Mandatory)][AllowEmptyString()][string]$StatusJson)
    try {
        $parsed = $StatusJson | ConvertFrom-Json
        $tailnet = $parsed.PSObject.Properties['CurrentTailnet']
        if (-not $tailnet -or -not $tailnet.Value) { return $null }
        $flag = $tailnet.Value.PSObject.Properties['MagicDNSEnabled']
        if (-not $flag) { return $null }
        return [bool]$flag.Value
    } catch { return $null }
}

# `tailscale status --json`'s "Self.DNSName" field (the device's own MagicDNS name), trailing dot
# stripped. This is the fallback hostname source when parsing the funnel-enable output fails.
function Find-RunningAiTailscaleSelfDnsName {
    param([Parameter(Mandatory)][AllowEmptyString()][string]$StatusJson)
    try {
        $parsed = $StatusJson | ConvertFrom-Json
        $self = $parsed.PSObject.Properties['Self']
        if (-not $self -or -not $self.Value) { return $null }
        $dnsName = $self.Value.PSObject.Properties['DNSName']
        if (-not $dnsName -or -not $dnsName.Value) { return $null }
        return ([string]$dnsName.Value).TrimEnd('.')
    } catch { return $null }
}

# Parses `tailscale funnel status` (or the enable command's own stdout) for the public
# "https://<name>.ts.net" URL Tailscale prints, and returns just the hostname.
function Find-RunningAiFunnelHostname {
    param([Parameter(Mandatory)][AllowEmptyString()][string]$Output)
    if ($Output -match 'https://([a-zA-Z0-9.-]+\.ts\.net)') { return $Matches[1] }
    return $null
}

# True when the already-running Funnel/serve output shows our exact target already mapped -
# an idempotent re-run should not try to re-enable it.
function Test-RunningAiFunnelAlreadyServingTarget {
    param([Parameter(Mandatory)][AllowEmptyString()][string]$StatusOutput, [Parameter(Mandatory)][string]$Target)
    $StatusOutput.Contains($Target)
}
