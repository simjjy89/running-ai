<#
.SYNOPSIS
  One-screen status of the RunningAI runtime. Read-only and safe: it calls only the connector
  /health, Spring /actuator/health, GET /api/v1/garmin/sync/status, the external relay's own
  /health, and (read-only) the Tailscale Windows Service state and `tailscale funnel status`.
  No Garmin login, no Garmin activity fetch, no token access, and this command NEVER changes the
  Funnel configuration - "status" means read, nothing more, even for the Tailscale/Funnel rows.

  Exit code 0 when Docker, PostgreSQL, the connector, Spring and the external relay are all up;
  1 otherwise.
#>
[CmdletBinding()]
param(
    [int]$ConnectorPort = 8765,
    [int]$SpringPort = 8080
)

. "$PSScriptRoot\RunningAI.Common.ps1"
. "$PSScriptRoot\external\RunningAI.ExternalRelay.Common.ps1"
. "$PSScriptRoot\external\RunningAI.TailscaleSetup.ps1"

# Loaded here (same reasoning as start-/watch-running-ai.ps1) so a canonical .env SERVER_PORT is
# honoured even when this script is run standalone, with no parent that already loaded .env - see
# Resolve-RunningAiSpringPort in RunningAI.Common.ps1 for why this must precede the port read.
# RUNNING_AI_TEST_ENV_ROOT is a test-only escape hatch (mirrors RUNNING_AI_TEST_RUNTIME_DIR);
# never set outside a test.
$envRoot = if ($env:RUNNING_AI_TEST_ENV_ROOT) { $env:RUNNING_AI_TEST_ENV_ROOT } else { Get-RepoRoot }
Initialize-DotEnvForThisProcess -Root $envRoot
if (-not $PSBoundParameters.ContainsKey('SpringPort')) { $SpringPort = Resolve-RunningAiSpringPort }

function Format-Row { param([string]$Name, [string]$Value) Write-Host ("{0,-16} {1}" -f $Name, $Value) }

$allUp = $true

# Docker
$dockerUp = (Get-Command docker -ErrorAction SilentlyContinue) -and ((Invoke-NativeQuiet 'docker' 'info') -eq 0)
Format-Row 'Docker' $(if ($dockerUp) { 'RUNNING' } else { 'DOWN' })
if (-not $dockerUp) { $allUp = $false }

# PostgreSQL
$pgState = 'UNKNOWN'
if ($dockerUp) {
    $compose = Join-Path (Get-RepoRoot) 'docker-compose.yml'
    $cid = (Invoke-NativeText 'docker' "compose -f $(Quote-Argument $compose) ps -q postgres").Trim()
    if (-not $cid) { $pgState = 'NOT CREATED' }
    else {
        $health = (Invoke-NativeText 'docker' "inspect -f {{.State.Health.Status}} $cid").Trim()
        $pgState = $health.ToUpper()
    }
}
Format-Row 'PostgreSQL' $pgState
if ($pgState -ne 'HEALTHY') { $allUp = $false }

# Connector
$connectorUp = Test-ConnectorHealth $ConnectorPort
$connectorPid = Get-TrackedProcessId 'garmin-connector' (Get-ConnectorMarkers)
$detail = if ($connectorPid) { " (managed, PID $connectorPid)" } elseif ($connectorUp) { ' (not started by RunningAI scripts)' } else { '' }
Format-Row 'GarminConnector' ($(if ($connectorUp) { 'UP' } else { 'DOWN' }) + " 127.0.0.1:$ConnectorPort$detail")
if (-not $connectorUp) { $allUp = $false }

# Spring
$springUp = Test-SpringHealth $SpringPort
$springPid = Get-TrackedProcessId 'spring' (Get-SpringMarkers)
$detail = if ($springPid) { " (managed, PID $springPid)" } elseif ($springUp) { ' (not started by RunningAI scripts)' } else { '' }
Format-Row 'Spring' ($(if ($springUp) { 'UP' } else { 'DOWN' }) + " port $SpringPort$detail")
if (-not $springUp) { $allUp = $false }

# External relay (independent of Spring - reported here for a complete picture, but
# start/stop/watchdog never couple its state to Spring's)
$relayPort = Get-ExternalRelayConfiguredPort
$relayUp = Test-ExternalRelayHealth $relayPort
$relayPid = Get-TrackedProcessId 'external-relay' (Get-ExternalRelayMarkers)
$detail = if ($relayPid) { " (managed, PID $relayPid)" } elseif ($relayUp) { ' (not started by RunningAI scripts)' } else { '' }
Format-Row 'ExternalRelay' ($(if ($relayUp) { 'UP' } else { 'DOWN' }) + " 127.0.0.1:$relayPort$detail")
if (-not $relayUp) { $allUp = $false }

# Tailscale / Funnel - read-only. Never installs, logs in, enables Funnel, or changes any config.
$tailscaleExe = Get-RunningAiTailscaleExe
if (-not $tailscaleExe) {
    Format-Row 'Tailscale' 'NOT INSTALLED'
} else {
    $tsService = Get-Service -ErrorAction SilentlyContinue | Where-Object { $_.Name -like '*Tailscale*' -or $_.DisplayName -like '*Tailscale*' } | Select-Object -First 1
    if ($tsService) { Format-Row 'Tailscale' "$($tsService.Status) (service '$($tsService.Name)', start type $($tsService.StartType))" }
    else { Format-Row 'Tailscale' 'service not found' }

    $funnelStatusOut = (Invoke-RunningAiTailscaleCommand -Exe $tailscaleExe -Arguments @('funnel', 'status')).StdOut
    $funnelTarget = "http://127.0.0.1:$relayPort"
    $funnelConfigured = Test-RunningAiFunnelAlreadyServingTarget -StatusOutput $funnelStatusOut -Target $funnelTarget
    $funnelHostname = if ($funnelConfigured) { Find-RunningAiFunnelHostname $funnelStatusOut } else { $null }
    Format-Row 'Funnel' $(if ($funnelConfigured) { "configured -> https://$funnelHostname" } else { "not configured for $funnelTarget" })
}

# SchedulerConfig: Spring has no scheduler status API, so this only reflects the environment of THIS shell.
# It does not prove what the running Spring process was started with.
$schedulerConfig = if ($env:RUNNING_AI_GARMIN_SCHEDULER_ENABLED -eq 'true') { 'enabled=true' } else { 'enabled=false (default)' }
Format-Row 'SchedulerConfig' "$schedulerConfig (source: current shell environment; Spring not queried)"

# Garmin sync state (database only, no Garmin call)
if ($springUp) {
    $body = Get-HttpBody "http://127.0.0.1:$SpringPort/api/v1/garmin/sync/status" 5
    if ($body) {
        try {
            $s = ConvertFrom-Json $body
            if ($s.initialized) {
                Format-Row 'HighWater' "$($s.highWaterStartedAt)"
                Format-Row 'LastSync' "$($s.lastSuccessfulSyncAt)"
            } else {
                Format-Row 'LastSync' 'never (sync state not initialized)'
            }
        } catch { Format-Row 'LastSync' 'UNKNOWN (unreadable status response)' }
    } else { Format-Row 'LastSync' 'UNKNOWN (status endpoint unreachable)' }
} else {
    Format-Row 'LastSync' 'UNKNOWN (Spring is down)'
}

# Watchdog (reads the file the watchdog wrote; nothing is probed or restarted here)
$watchdogFile = Join-Path $script:RuntimeDir 'watchdog-status.json'
if (Test-Path -LiteralPath $watchdogFile) {
    try {
        $w = ConvertFrom-Json (Get-Content -LiteralPath $watchdogFile -Raw)
        $checked = [DateTimeOffset]::Parse([string]$w.checkedAt)
        $ageMin = [int]([DateTimeOffset]::UtcNow - $checked.ToUniversalTime()).TotalMinutes
        $stale = if ($ageMin -gt 15) { ' - STALE, is the RunningAI-Watchdog task running?' } else { '' }
        Format-Row 'Watchdog' "$($w.overall)$stale"
        Format-Row 'LastWatchdogCheck' "$($w.checkedAt) (${ageMin} min ago)"
        Format-Row 'LastRecovery' "$($w.lastAction)"
        $used = ($w.restartBudget.used.PSObject.Properties | ForEach-Object { "$($_.Name)=$($_.Value)" }) -join ' '
        Format-Row 'RestartBudget' "$used (max $($w.restartBudget.maxRestarts) per $($w.restartBudget.windowMinutes) min)"
        if (@($w.blocked).Count -gt 0) { Format-Row 'WatchdogBlocked' ((@($w.blocked)) -join '; ') }
        if ($w.garminHint) { Format-Row 'GarminHint' "$($w.garminHint) (informational; needs operator action, not a restart)" }
    } catch { Format-Row 'Watchdog' 'UNKNOWN (status file unreadable)' }
} else {
    Format-Row 'Watchdog' 'NOT RUN (no watchdog-status.json yet)'
}

exit $(if ($allUp) { $ExitCode.Ok } else { $ExitCode.Other })
