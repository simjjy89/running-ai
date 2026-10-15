<#
  Shared helpers for supervising the MCP gateway process (.runtime\mcp-gateway\server.js).
  Dot-source, do not run.

  Phase 9 (A-2). The gateway was in NO supervision list at all: nothing observed it, nothing
  restarted it, and it is the single hop between the Cloudflare tunnel and Spring, so when its
  node process died ChatGPT simply stopped working until a human noticed (diagnosed 2026-10-15,
  dead ~3.5 h). This file gives the watchdog the three facts it needs - is it healthy, which
  process is it, and how is it started again - and nothing else.

  Two deliberate differences from the connector/Spring/relay pattern:

  1. There is NO .pid file. The gateway is launched by .runtime\mcp-gateway.cmd (a Scheduled Task
     action), which holds the whole gateway configuration and writes no PID anywhere. Rather than
     change the one launcher that is already verified in production, the process is identified by
     the LISTENING SOCKET on its configured port and then proved ours by command-line markers.
     That is also the only identification that can tell the production gateway (17910) apart from
     the test one (17911): both run the exact same server.js from the exact same directory, so
     markers alone cannot distinguish them - the port is the discriminator.

  2. Recovery is Start-ScheduledTask, not a spawned child. .runtime\mcp-gateway.cmd runs node in
     the FOREGROUND, so starting it as a watchdog child would block until the child timeout and
     then kill the gateway that had just come up. The Scheduled Task is detached by nature and its
     MultipleInstances=IgnoreNew makes a redundant start a no-op.
#>

Set-StrictMode -Version Latest

$script:McpGatewayTaskName    = 'RunningAI-McpGateway'
$script:McpGatewayDefaultPort = 17910

function Get-RunningAiMcpGatewayTaskName { $script:McpGatewayTaskName }

# .runtime is git-ignored, so on a fresh checkout none of these exist. Every function below reports
# that as a fact ($null / $false / 'MISSING') rather than throwing - a machine that has never run a
# gateway is a legitimate state, not an error.
function Get-RunningAiMcpGatewayLauncherPath { Join-Path $script:RuntimeDir 'mcp-gateway.cmd' }
function Get-RunningAiMcpGatewayDirectory    { Join-Path $script:RuntimeDir 'mcp-gateway' }
function Get-RunningAiMcpGatewayMarkers      { @('server.js', (Get-RunningAiMcpGatewayDirectory)) }

<#
  The gateway's port, read from the launcher that actually sets it, so this never drifts from what
  production runs. A launcher that is missing or does not set the variable yields the documented
  default - never a guess at another port.
#>
function Get-RunningAiMcpGatewayConfiguredPort {
    param([string]$LauncherPath = (Get-RunningAiMcpGatewayLauncherPath))
    if (-not (Test-Path -LiteralPath $LauncherPath)) { return $script:McpGatewayDefaultPort }
    $text = Get-Content -LiteralPath $LauncherPath -Raw -ErrorAction SilentlyContinue
    if (-not $text) { return $script:McpGatewayDefaultPort }
    foreach ($line in ($text -replace "`r`n", "`n").Split("`n")) {
        if ($line -match '^\s*set\s+"?\s*RUNNING_AI_GATEWAY_PORT\s*=\s*(?<p>\d+)\s*"?\s*$') {
            $port = [int]$Matches['p']
            if ($port -gt 0 -and $port -le 65535) { return $port }
        }
    }
    return $script:McpGatewayDefaultPort
}

<#
  GET /health on the loopback gateway. This is an UNAUTHENTICATED endpoint that returns identities
  only ({status, service}) - the watchdog can therefore never need, hold or log a token, and a
  passing check says exactly one thing: the node process is up and serving HTTP. It deliberately
  says nothing about OAuth, the tunnel or Spring.
#>
function Test-RunningAiMcpGatewayHealth {
    param([int]$Port)
    $body = Get-HttpBody "http://127.0.0.1:$Port/health"
    if (-not $body) { return $false }
    try { return ((ConvertFrom-Json $body).status -eq 'ok') } catch { return $false }
}

<#
  The PID listening on $Port, but ONLY when its live command line proves it is this repository's
  gateway (same Test-ProcessIdentity contract the connector/Spring/relay use). A foreign process
  squatting the port returns $null, which the watchdog classifies as DEGRADED/FOREIGN_PROCESS and
  never touches - the gateway is not special here.

  -ProcessLister / -ListenerLookup are injected by tests only; the defaults talk to the real machine.
#>
function Get-RunningAiMcpGatewayProcessId {
    param(
        [Parameter(Mandatory)][int]$Port,
        # No listener at all is the normal "gateway is down" case, so the lookup must return $null
        # rather than dereference an empty result - Set-StrictMode turns that into an exception, and
        # an exception on the one path that detects a dead gateway would hide exactly what it exists
        # to find.
        [scriptblock]$ListenerLookup = {
            param($p)
            $conn = @(Get-NetTCPConnection -LocalPort $p -State Listen -ErrorAction SilentlyContinue)
            if ($conn.Count -eq 0) { return $null }
            $conn[0].OwningProcess
        },
        [scriptblock]$IdentityCheck  = { param($processId, $markers) Test-ProcessIdentity -ProcessId $processId -Markers $markers }
    )
    $owner = & $ListenerLookup $Port
    if (-not $owner) { return $null }
    $owningPid = 0
    if (-not [int]::TryParse("$owner", [ref]$owningPid) -or $owningPid -le 0) { return $null }
    if (& $IdentityCheck $owningPid (Get-RunningAiMcpGatewayMarkers)) { return $owningPid }
    return $null
}

<#
  Why recovery may not be attempted, or $null when it may. Kept separate from the act of recovering
  so the watchdog's PLANNING stage can report an honest blocked reason without side effects.
#>
function Get-RunningAiMcpGatewayRecoveryBlocker {
    param([scriptblock]$TaskLookup = { param($name) Get-ScheduledTask -TaskName $name -ErrorAction SilentlyContinue })
    if (-not (Test-Path -LiteralPath (Get-RunningAiMcpGatewayLauncherPath))) { return 'MCP_GATEWAY_LAUNCHER_MISSING' }
    if (-not (& $TaskLookup $script:McpGatewayTaskName)) { return 'MCP_GATEWAY_TASK_NOT_REGISTERED' }
    return $null
}
