<#
  Observation of the Cloudflare Quick Tunnel that fronts the MCP gateway. Dot-source, do not run.

  Phase 9 (A-2). This file deliberately contains NO recovery. That is a decision about Quick
  Tunnels, not an omission:

    A Quick Tunnel's hostname is assigned when the cloudflared PROCESS starts and is discarded when
    it exits. The Cloudflare Worker that ChatGPT talks to reaches this machine through a hostname
    baked into its deployed ORIGIN_MCP_URL. So restarting cloudflared does not restore the path -
    it mints a DIFFERENT hostname and leaves the Worker pointing at one that no longer resolves,
    turning a diagnosable outage into a silently misconfigured one. Making the chain work again
    needs a Worker redeploy, which is an external write and an operator decision.

  So the honest contribution here is evidence, and the most valuable piece of evidence is not
  "is it up" but **when did this process start**: a cloudflared start time that is newer than the
  deployed Worker version is precisely the condition under which ORIGIN_MCP_URL is stale. The
  watchdog records it so that fact is readable afterwards instead of being reconstructed by guesswork
  (two separate sessions have already mis-attributed a dead gateway to tunnel/Worker configuration).

  Nothing here reads, logs or needs a tunnel hostname, token or credential.
#>

Set-StrictMode -Version Latest

$script:CloudflaredProcessName = 'cloudflared.exe'

<#
  The cloudflared process serving $GatewayPort, identified by its own command line (--url
  http://127.0.0.1:<port>) so a cloudflared belonging to some other service on this machine is
  never mistaken for ours. Returns:

    State        UP   - exactly one matching process
                 DOWN - none
              DEGRADED - more than one, which means two tunnels are fighting over the same origin
                         and at most one of them matches the deployed Worker. Never auto-resolved:
                         killing the wrong one is indistinguishable from killing the right one.
    ProcessId    the matching PID (UP only)
    StartedAtUtc when that process started (UP only) - the hostname-change signal described above
    Count        how many matched

  -ProcessLister is injected by tests; the default queries the real machine.
#>
function Get-RunningAiTunnelObservation {
    param(
        [Parameter(Mandatory)][int]$GatewayPort,
        [scriptblock]$ProcessLister = { Get-CimInstance Win32_Process -Filter "Name='$($script:CloudflaredProcessName)'" -ErrorAction SilentlyContinue }
    )
    $matched = @()
    try {
        foreach ($p in (& $ProcessLister)) {
            if (-not $p) { continue }
            $cmd = $p.CommandLine
            if (-not $cmd) { continue }
            # Both spellings cloudflared accepts for a loopback origin; the port must be the whole
            # token, so :17910 never matches a --url pointing at :179100 or :1791.
            if ($cmd -notmatch "(127\.0\.0\.1|localhost):$GatewayPort(\D|$)") { continue }
            $matched += $p
        }
    } catch {
        return [pscustomobject]@{ State = 'UNKNOWN'; ProcessId = $null; StartedAtUtc = $null; Count = 0 }
    }

    if (@($matched).Count -eq 0) { return [pscustomobject]@{ State = 'DOWN'; ProcessId = $null; StartedAtUtc = $null; Count = 0 } }
    if (@($matched).Count -gt 1) { return [pscustomobject]@{ State = 'DEGRADED'; ProcessId = $null; StartedAtUtc = $null; Count = @($matched).Count } }

    $one = $matched[0]
    $startedAt = $null
    try { if ($one.CreationDate) { $startedAt = ([datetime]$one.CreationDate).ToUniversalTime().ToString('yyyy-MM-ddTHH:mm:ssZ') } } catch { }
    [pscustomobject]@{ State = 'UP'; ProcessId = [int]$one.ProcessId; StartedAtUtc = $startedAt; Count = 1 }
}
