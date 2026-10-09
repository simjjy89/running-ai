<#
.SYNOPSIS
  One-shot RunningAI watchdog: detect -> classify -> recover only when safe -> limit restarts ->
  record diagnostics -> exit. Meant to be run every 5 minutes by the "RunningAI-Watchdog"
  Scheduled Task (see install-running-ai-watchdog-task.ps1).

.DESCRIPTION
  Recovers: dead Docker daemon, stopped PostgreSQL container, dead or persistently unhealthy
  Garmin connector / Spring Boot (dependency order Docker -> PostgreSQL -> connector -> Spring,
  one verified step at a time, by reusing the idempotent start-running-ai.ps1).
  Never restarts for: Garmin authentication / 403 / 429 / upstream problems (informational hint
  only), a running-but-unhealthy PostgreSQL, a port owned by a foreign process, an exhausted
  restart budget (default 3 restarts per component per 10 minutes; no permanent lockout).
  Never: calls Garmin, POSTs /sync, runs "compose down", removes volumes, kills by process name,
  or touches processes that are not proven (command line + repository path) to be RunningAI's.

  Outputs (git-ignored): .runtime\watchdog-status.json, watchdog-state.json (restart history),
  logs\watchdog.log. Old/oversized logs are rotated and rotated logs older than 14 days deleted.

  Exit codes: 0 normal (degradation is reported in the status file) | 20 watchdog error | 21 a
  recovery action was attempted and failed.

.PARAMETER DryRun
  Observe, classify and print the planned actions. Executes nothing and writes no files.
.PARAMETER NoRecovery
  Update status/log/rotation but never start, stop or restart anything.
#>
[CmdletBinding()]
param(
    [switch]$DryRun,
    [switch]$NoRecovery,
    [int]$ConnectorPort = 8765,
    [int]$SpringPort = 8080,
    [int]$RecheckDelaySec = 10,
    [int]$WindowMinutes = 10,
    [int]$MaxRestarts = 3,
    [int]$LogMaxSizeMB = 10,
    [int]$LogRetentionDays = 14
)

. "$PSScriptRoot\RunningAI.Watchdog.ps1"

$mutex = $null
try {
    # Script-level overlap guard (the Scheduled Task also uses MultipleInstances=IgnoreNew).
    $hash = [BitConverter]::ToString([Security.Cryptography.SHA1]::Create().ComputeHash([Text.Encoding]::UTF8.GetBytes((Get-RepoRoot).ToLowerInvariant()))).Replace('-', '').Substring(0, 12)
    $mutex = New-Object System.Threading.Mutex($false, "Local\RunningAI-Watchdog-$hash")
    if (-not $mutex.WaitOne(0)) { Write-Step 'Another watchdog run is in progress; skipping.'; exit $ExitCode.Ok }

    # Loaded here (same as start-running-ai.ps1's own step 0) because this script is often
    # spawned standalone by the Scheduled Task, with no parent that already loaded .env - see
    # Resolve-RunningAiSpringPort in RunningAI.Common.ps1 for why this must run before resolving
    # $SpringPort from $env:SERVER_PORT. RUNNING_AI_TEST_ENV_ROOT is a test-only escape hatch
    # (mirrors RUNNING_AI_TEST_RUNTIME_DIR); never set outside a test.
    $envRoot = if ($env:RUNNING_AI_TEST_ENV_ROOT) { $env:RUNNING_AI_TEST_ENV_ROOT } else { Get-RepoRoot }
    Initialize-DotEnvForThisProcess -Root $envRoot
    if (-not $PSBoundParameters.ContainsKey('SpringPort')) { $SpringPort = Resolve-RunningAiSpringPort }

    $now = Get-Date
    $writeFiles = -not $DryRun
    if ($writeFiles) {
        New-Item -ItemType Directory -Force $script:LogDir | Out-Null
        foreach ($n in $script:ManagedLogNames) { Invoke-LogRotation -LogDir $script:LogDir -Name $n -MaxBytes ($LogMaxSizeMB * 1MB) | Out-Null }
        Remove-ExpiredLogs -LogDir $script:LogDir -MaxAgeDays $LogRetentionDays | Out-Null
    }

    $state = Read-WatchdogState
    $history = $state.History
    Update-HistoryWindow -History $history -Now $now -WindowMinutes $WindowMinutes

    $observe = { Get-RuntimeObservation -ConnectorPort $ConnectorPort -SpringPort $SpringPort -RecheckDelaySec $RecheckDelaySec }
    $states = Get-ComponentStates (& $observe)
    $plan = Get-RecoveryPlan -States $states -History $history -Now $now -HistoryAvailable $state.Available -WindowMinutes $WindowMinutes -MaxRestarts $MaxRestarts
    $hint = Get-GarminHintFromLog -Now $now

    if ($DryRun) {
        Write-Host 'DRY RUN - nothing is executed and no files are written.'
        foreach ($c in $script:AllTrackedComponents) { Write-Host ("{0,-10} {1,-10} {2}" -f $c, $states[$c].State, $states[$c].Reason) }
        if ($hint) { Write-Host "Garmin hint : $hint (informational; never causes a restart)" }
        if (-not $state.Available) { Write-Host "State       : $($state.Note) -> no recovery this tick (fail safe)" }
        if ($plan.Actions.Count -eq 0) { Write-Host 'Planned actions: none' }
        foreach ($a in $plan.Actions) { Write-Host ("Planned action: {0} {1} ({2}){3}" -f $a.Action, $a.Component, $a.Reason, $(if ($a.PreStop) { ', graceful stop first' } else { '' })) }
        foreach ($b in $plan.Blocked) { Write-Host ("Blocked      : {0} - {1}" -f $b.Component, $b.Reason) }
        Write-Host ("Overall     : {0}" -f (Get-OverallState -States $states -Blocked $plan.Blocked -GarminHint $hint))
        exit $ExitCode.Ok
    }

    if (-not $state.Available) { Write-WatchdogLog -Component 'watchdog' -State 'DEGRADED' -Reason 'STATE_UNAVAILABLE_FAIL_SAFE' -Action 'NONE' -Result $state.Note }

    $steps = @(); $recoveryFailed = $false
    if (-not $NoRecovery -and $state.Available -and $plan.Actions.Count -gt 0) {
        $runner = { param($a) Invoke-RecoveryAction -Action $a -ConnectorPort $ConnectorPort -SpringPort $SpringPort }
        $result = Invoke-WatchdogRecovery -Observe $observe -Runner $runner -History $history -HistoryAvailable $state.Available -Now $now `
            -WindowMinutes $WindowMinutes -MaxRestarts $MaxRestarts
        $steps = @($result.Steps); $recoveryFailed = $result.Failed
        foreach ($s in $steps) { Write-WatchdogLog -Component $s.Component -State 'RECOVERING' -Reason $s.Reason -Action $s.Action -Result $s.Result }
    }

    # Final picture (after any recovery) for the status file.
    $finalStates = if ($steps.Count -gt 0) { Get-ComponentStates (& $observe) } else { $states }
    $finalPlan = if ($steps.Count -gt 0) { Get-RecoveryPlan -States $finalStates -History $history -Now $now -HistoryAvailable $state.Available -WindowMinutes $WindowMinutes -MaxRestarts $MaxRestarts } else { $plan }
    $blocked = @($finalPlan.Blocked)
    foreach ($b in $blocked) { Write-WatchdogLog -Component $b.Component -State $finalStates[$b.Component].State -Reason $b.Reason -Action 'NONE' -Result 'NOT_TOUCHED' }
    $overall = Get-OverallState -States $finalStates -Blocked $blocked -GarminHint $hint

    $budget = [ordered]@{}
    foreach ($c in $script:AllTrackedComponents) { $budget[$c] = Get-RestartCount -History $history -Component $c -Now $now -WindowMinutes $WindowMinutes }
    $componentStatus = [ordered]@{}
    foreach ($c in $script:AllTrackedComponents) { $componentStatus[$c] = $finalStates[$c].State }
    $lastAction = if ($steps.Count -gt 0) { ($steps | ForEach-Object { "$($_.Action):$($_.Result)" }) -join ',' } else { 'NONE' }

    Write-JsonAtomic -Path $script:StatusPath -Object ([ordered]@{
        checkedAt = $now.ToUniversalTime().ToString('yyyy-MM-ddTHH:mm:ssZ')
        overall = $overall
        components = $componentStatus
        blocked = @($blocked | ForEach-Object { "$($_.Component):$($_.Reason)" })
        garminHint = $hint
        lastAction = $lastAction
        restartBudget = [ordered]@{ windowMinutes = $WindowMinutes; maxRestarts = $MaxRestarts; used = $budget }
    })
    Save-WatchdogState -History $history
    Write-WatchdogLog -Component 'watchdog' -State $overall -Action $lastAction -Result $(if ($recoveryFailed) { 'RECOVERY_FAILED' } else { 'OK' })

    Write-Step "Watchdog: overall=$overall lastAction=$lastAction"
    exit $(if ($recoveryFailed) { $ExitCode.RecoveryFailed } else { $ExitCode.Ok })
} catch {
    try { Write-WatchdogLog -Component 'watchdog' -State 'ERROR' -Reason $_.Exception.GetType().Name -Result 'WATCHDOG_ERROR' } catch { }
    Write-Host "ERROR: $($_.Exception.Message)" -ForegroundColor Red
    exit $ExitCode.WatchdogError
} finally {
    if ($mutex) { try { $mutex.ReleaseMutex() } catch { }; $mutex.Dispose() }
}
