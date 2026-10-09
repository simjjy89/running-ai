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

  2026-10-09 fix: every `tailscale ...` invocation here goes through Invoke-RunningAiTailscaleCommand
  (RunningAI.Common.ps1's Invoke-RunningAiUtf8Process), never a plain `& tailscale ... 2>&1`
  pipeline capture. Live-reproduced bug: on a Windows PowerShell 5.1 console whose codepage is not
  UTF-8, capturing tailscale.exe's stdout through the pipeline decodes it using
  [Console]::OutputEncoding (the system codepage), which corrupts the Korean Self.DisplayName
  field inside `tailscale status --json` into mojibake - turning the JSON itself invalid and
  making ConvertFrom-Json fail, which this setup script then misread as "not logged in." The one
  deliberate exception is `tailscale up`: it must stay attached to the real console (inherited,
  not redirected) because its interactive browser-auth flow needs the operator to see the
  auth URL and the command to block in real time - redirecting it would buffer all output until
  the process exits, which is exactly backwards for an interactive login.
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

# The one place every non-interactive `tailscale ...` call goes through: UTF-8-safe stdout/stderr,
# so a non-ASCII field (a Korean DisplayName, for example) never corrupts the output. Never logs
# the raw StdOut itself (status --json can carry personal profile fields) - callers extract and
# log only specific derived values.
function Invoke-RunningAiTailscaleCommand {
    param([Parameter(Mandatory)][string]$Exe, [Parameter(Mandatory)][string[]]$Arguments)
    Invoke-RunningAiUtf8Process -FilePath $Exe -ArgumentList $Arguments
}

# Runs `tailscale status --json` through the UTF-8-safe invoker and returns the raw JSON text on
# success, or $null on a non-zero exit (the caller decides whether/how to surface $StdErr - never
# automatically dumped, since it is printed straight to the console by the caller only when it
# actually needs diagnosing).
function Get-RunningAiTailscaleStatusJson {
    param([Parameter(Mandatory)][string]$Exe)
    $result = Invoke-RunningAiTailscaleCommand -Exe $Exe -Arguments @('status', '--json')
    if ($result.ExitCode -ne 0) { return $null }
    return $result.StdOut
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

# Logged in AND connected: `tailscale status --json`'s "BackendState" must be "Running" (anything
# else - "NeedsLogin", "Stopped", ... - means `tailscale up` is still required) AND "Self.Online"
# must be true (BackendState can read "Running" for a moment before the daemon has actually
# established connectivity - Online is the stronger, more specific signal). Self.Online missing
# entirely is treated as not-yet-connected, never assumed true.
function Test-RunningAiTailscaleLoggedIn {
    param([Parameter(Mandatory)][AllowEmptyString()][string]$StatusJson)
    try {
        $parsed = $StatusJson | ConvertFrom-Json
        if ($parsed.BackendState -ne 'Running') { return $false }
        $self = $parsed.PSObject.Properties['Self']
        if (-not $self -or -not $self.Value) { return $false }
        $online = $self.Value.PSObject.Properties['Online']
        if (-not $online) { return $false }
        return [bool]$online.Value
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

# Ensures Funnel serves $Target, fail-closed, without ever trying to read or auto-approve a
# first-time web-approval prompt:
#   1. `tailscale funnel status` (UTF-8-safe, non-interactive) - if $Target is already being
#      served, returns immediately. The interactive enable path below is never reached on an
#      already-configured target (a re-run must not re-trigger it).
#   2. Otherwise runs $EnableInteractive exactly once - the caller's scriptblock MUST inherit the
#      real console (never Invoke-RunningAiTailscaleCommand/redirected), because the very first
#      Funnel enable for a tailnet/device can require the operator to open a web-approval URL
#      Tailscale prints live; redirecting that output would buffer it until the process exits,
#      which can never happen if Tailscale itself is waiting on that approval. $EnableInteractive
#      must return the enable command's own exit code (typically via $LASTEXITCODE right after
#      `& $tailscale funnel --bg $target`).
#   3. A non-zero exit from $EnableInteractive is fail-closed immediately - never re-verified,
#      never retried automatically.
#   4. On a zero exit, re-queries `tailscale funnel status` (UTF-8-safe) again and only trusts
#      the enable as real if THAT confirms the target is being served - the enable command's own
#      exit code is never trusted alone.
# Returns @{ AlreadyServing; Enabled; ExitCode; Hostname }. Exactly one of AlreadyServing/Enabled
# is true on success; both are false on any failure (ExitCode carries the non-zero code, or 1 for
# a "reported success but status disagrees" inconsistency).
function Invoke-RunningAiFunnelEnsureEnabled {
    param(
        [Parameter(Mandatory)][string]$Exe,
        [Parameter(Mandatory)][string]$Target,
        [Parameter(Mandatory)][scriptblock]$EnableInteractive
    )
    $statusOut = (Invoke-RunningAiTailscaleCommand -Exe $Exe -Arguments @('funnel', 'status')).StdOut
    if (Test-RunningAiFunnelAlreadyServingTarget -StatusOutput $statusOut -Target $Target) {
        return [pscustomobject]@{
            AlreadyServing = $true
            Enabled        = $false
            ExitCode       = 0
            Hostname       = (Find-RunningAiFunnelHostname $statusOut)
        }
    }

    $enableExitCode = & $EnableInteractive
    if ($enableExitCode -ne 0) {
        return [pscustomobject]@{ AlreadyServing = $false; Enabled = $false; ExitCode = $enableExitCode; Hostname = $null }
    }

    $recheck = (Invoke-RunningAiTailscaleCommand -Exe $Exe -Arguments @('funnel', 'status')).StdOut
    if (-not (Test-RunningAiFunnelAlreadyServingTarget -StatusOutput $recheck -Target $Target)) {
        return [pscustomobject]@{ AlreadyServing = $false; Enabled = $false; ExitCode = 1; Hostname = $null }
    }
    return [pscustomobject]@{ AlreadyServing = $false; Enabled = $true; ExitCode = 0; Hostname = (Find-RunningAiFunnelHostname $recheck) }
}
