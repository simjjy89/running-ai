<#
.SYNOPSIS
  Starts the Cloudflare Quick Tunnel in front of the MCP gateway, as a detached, tracked process
  that logs into .runtime\logs, and prints the hostname it was assigned.

.DESCRIPTION
  Phase 9 (A-2). The production tunnel was never launched by anything: it was started ad hoc from a
  terminal session, so its only log lives in that session's temporary directory and its PID is
  recorded nowhere. It currently survives only because its parent exited without taking it down.
  This script makes starting one a defined operation - it does not change what is started.

  Deliberately NOT part of watchdog recovery, and this is the whole reason the watchdog observes the
  tunnel without ever restarting it: a Quick Tunnel's hostname is minted when the PROCESS starts and
  is gone when it exits. The Cloudflare Worker that ChatGPT talks to reaches this machine through a
  hostname baked into its deployed ORIGIN_MCP_URL, so starting a tunnel is only half of restoring the
  path - the other half is redeploying the Worker with the new hostname, which is an external write
  and an operator decision. That is why this script ENDS by printing the hostname and the exact
  follow-up, rather than pretending the job is done.

  Refuses to start a second tunnel for the same gateway port. Two cloudflared processes on one
  origin is the one case nothing on this machine can resolve: at most one of them matches the
  deployed Worker and there is no way to tell which, so the right move is to stop the one you know
  about rather than to add another.

  Starts no gateway, writes no gateway configuration, needs no Cloudflare credential (a Quick Tunnel
  is anonymous), and introduces no paid infrastructure.
#>
[CmdletBinding()]
param(
    [int]$Port,
    [int]$HostnameTimeoutSec = 30,
    [switch]$DryRun
)

. "$PSScriptRoot\..\RunningAI.Watchdog.ps1"

try {
    if (-not $PSBoundParameters.ContainsKey('Port')) { $Port = Get-RunningAiMcpGatewayConfiguredPort }

    $cloudflared = (Get-Command cloudflared -ErrorAction SilentlyContinue)
    if (-not $cloudflared) {
        $fallback = 'C:\Program Files (x86)\cloudflared\cloudflared.exe'
        if (Test-Path -LiteralPath $fallback) { $exe = $fallback } else {
            Stop-WithError $ExitCode.Cloudflared 'cloudflared was not found on PATH or at the default install location. Install it before starting a tunnel; this script never downloads anything.'
        }
    } else { $exe = $cloudflared.Source }

    $existing = Get-RunningAiTunnelObservation -GatewayPort $Port
    if ($existing.State -eq 'UP') {
        Write-Step "A cloudflared tunnel for port $Port is already running (PID $($existing.ProcessId), started $($existing.StartedAtUtc)). Not starting a second one: the running process owns the hostname the Worker is deployed against."
        exit $ExitCode.Ok
    }
    if ($existing.State -eq 'DEGRADED') {
        Stop-WithError $ExitCode.Cloudflared "$($existing.Count) cloudflared processes already target port $Port. At most one matches the hostname the Worker has deployed and nothing here can tell which; stop them deliberately before starting a new one."
    }

    $logPath = Join-Path $script:LogDir 'cloudflared.log'
    if ($DryRun) {
        Write-Host 'DRY RUN - nothing will be started.'
        Write-Host "Executable : $exe"
        Write-Host "Command    : tunnel --url http://127.0.0.1:$Port --logfile $logPath"
        Write-Host "Log file   : $logPath"
        Write-Host "PID file   : $(Get-PidFilePath 'cloudflared')"
        Write-Host 'After starting, the printed hostname must be deployed to the Worker as ORIGIN_MCP_URL, or ChatGPT keeps reaching the previous, now-dead hostname.'
        exit $ExitCode.Ok
    }

    New-Item -ItemType Directory -Force $script:LogDir | Out-Null
    # The log is truncated per start on purpose: a Quick Tunnel log is only ever about the tunnel
    # currently running, and keeping the previous hostname's lines in the same file is precisely how
    # the wrong hostname gets read back out later.
    Set-Content -LiteralPath $logPath -Value '' -Encoding ascii

    $argList = "tunnel --url http://127.0.0.1:$Port --logfile $(Quote-Argument $logPath)"
    $proc = Start-Process -FilePath $exe -ArgumentList $argList -WindowStyle Hidden -PassThru
    Write-PidFile 'cloudflared' $proc.Id
    Write-Step "cloudflared started (PID $($proc.Id)) for http://127.0.0.1:$Port; log: $logPath"

    # cloudflared prints the assigned hostname into its log a second or two after start. Reading it
    # back from the log is the only way to learn it: it is not an argument and not a return value.
    $hostname = $null
    $found = Wait-Until -TimeoutSec $HostnameTimeoutSec -PollSec 1 -Test {
        if (-not (Test-Path -LiteralPath $logPath)) { return $false }
        $text = Get-Content -LiteralPath $logPath -Raw -ErrorAction SilentlyContinue
        if (-not $text) { return $false }
        $m = [regex]::Match($text, 'https://[a-z0-9-]+\.trycloudflare\.com')
        if (-not $m.Success) { return $false }
        $script:ResolvedTunnelHostname = $m.Value
        return $true
    }
    if ($found) { $hostname = $script:ResolvedTunnelHostname }

    if (-not $hostname) {
        Write-Host "WARNING: the tunnel process started but no hostname appeared in $logPath within ${HostnameTimeoutSec}s. The process was NOT stopped; read the log and confirm before deploying anything." -ForegroundColor Yellow
        exit $ExitCode.Ok
    }

    Write-Step "Tunnel hostname: $hostname"
    Write-Host ''
    Write-Host 'This hostname is new. Until the Worker is redeployed against it, ChatGPT still points at the previous one:' -ForegroundColor Yellow
    Write-Host "  cd tools\mcp-worker" -ForegroundColor Yellow
    Write-Host "  npx wrangler deploy --var ORIGIN_MCP_URL:$hostname/mcp" -ForegroundColor Yellow
    Write-Host 'Verify with: npx wrangler deployments status, then npx wrangler versions view <id>' -ForegroundColor Yellow
    exit $ExitCode.Ok
} catch {
    Write-Host "ERROR: $($_.Exception.Message)" -ForegroundColor Red
    exit (Get-ExitCodeFromError $_)
}
