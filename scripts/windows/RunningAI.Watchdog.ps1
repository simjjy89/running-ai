# Watchdog logic for the RunningAI Windows runtime. Dot-source, do not run.
#
#   . "$PSScriptRoot\RunningAI.Watchdog.ps1"
#
# Split in four pure-ish layers so each can be tested without Docker, Garmin or real processes:
#   observation  (Get-RuntimeObservation, probes injectable)
#   classification (Get-ComponentStates)      -> UP / DOWN / UNHEALTHY / UNKNOWN / DEGRADED
#   planning     (Get-RecoveryPlan)           -> ordered actions + blocked reasons, restart budget applied
#   execution    (Invoke-WatchdogRecovery)    -> one action at a time, re-observing between steps
# The watchdog never calls Garmin, never POSTs /sync, never runs destructive Docker commands and
# only touches processes whose command line proves they belong to this repository.

. "$PSScriptRoot\RunningAI.Common.ps1"
. "$PSScriptRoot\RunningAI.ConnectorOwnership.ps1"
. "$PSScriptRoot\external\RunningAI.ExternalRelay.Common.ps1"

$script:Components = @('docker', 'postgres', 'connector', 'spring')   # dependency order
# The external relay has no dependency relationship with the chain above (or with itself across
# ticks) - Phase 6I-1.1. It gets its own independent planning pass (see Get-RecoveryPlan) so a
# Spring failure never blocks relay recovery and a relay failure never blocks docker/postgres/
# connector/spring recovery. It still shares the same restart-budget/history machinery as the
# chain, via $script:AllTrackedComponents.
$script:IndependentComponents = @('external-relay')
$script:AllTrackedComponents = $script:Components + $script:IndependentComponents
$script:StatePath  = Join-Path $script:RuntimeDir 'watchdog-state.json'
$script:StatusPath = Join-Path $script:RuntimeDir 'watchdog-status.json'
$script:WatchdogLogPath = Join-Path $script:LogDir 'watchdog.log'

# ---- JSON / state --------------------------------------------------------------------------

# Writes JSON through a temp file and replaces the target, so a reader never sees partial JSON.
function Write-JsonAtomic {
    param([Parameter(Mandatory)][string]$Path, [Parameter(Mandatory)]$Object)
    $dir = Split-Path $Path -Parent
    New-Item -ItemType Directory -Force $dir | Out-Null
    $tmp = "$Path.tmp-$([guid]::NewGuid().ToString('N'))"
    $json = ConvertTo-Json -InputObject $Object -Depth 6
    [System.IO.File]::WriteAllText($tmp, $json, (New-Object System.Text.UTF8Encoding($false)))
    if (Test-Path -LiteralPath $Path) { [System.IO.File]::Replace($tmp, $Path, [NullString]::Value) } else { [System.IO.File]::Move($tmp, $Path) }
}

function ConvertTo-UnixSeconds { param([datetime]$Time) [long]([DateTimeOffset]$Time.ToUniversalTime()).ToUnixTimeSeconds() }

# ---- watchdog-state.json write serialization (Phase 6I-1.7B-2C, STEP 4) -------------------------
#
# A SEPARATE named Mutex from the runtime start/stop lock above (RunningAI.Common.ps1) - this one
# protects only READS-THEN-WRITES of watchdog-state.json itself, which a Watchdog tick and an
# operator's clear-watchdog-lockout.ps1 -Force can otherwise race: Watchdog reads the file at the
# START of a (possibly minutes-long) tick, keeps its own in-memory History/LongTermState throughout,
# and only writes at the very END. If an operator clears a component's lockout and saves WHILE that
# tick is still running, the Watchdog's later save - built from its now-stale in-memory copy, taken
# BEFORE the clear - would silently overwrite the operator's clear with the old, still-locked state.
# This lock does not fix that by making the whole tick atomic (observing/recovering can take minutes
# and must never hold a file lock that long) - it protects the one thing that actually needs it: the
# window between "read the CURRENT on-disk state fresh" and "write the merged result back", which
# both Save-WatchdogState's caller (watch-running-ai.ps1, replaying only the deltas IT itself
# produced this tick onto a freshly re-read base) and clear-watchdog-lockout.ps1 -Force now do.
function Get-RunningAiWatchdogStateLockName {
    $hash = [BitConverter]::ToString([Security.Cryptography.SHA1]::Create().ComputeHash([Text.Encoding]::UTF8.GetBytes($script:StatePath.ToLowerInvariant()))).Replace('-', '').Substring(0, 12)
    "Local\RunningAI-WatchdogState-$hash"
}

function Enter-RunningAiWatchdogStateLock {
    param([int]$TimeoutSec = 5)
    $mutex = New-Object System.Threading.Mutex($false, (Get-RunningAiWatchdogStateLockName))
    try {
        $acquired = $mutex.WaitOne([TimeSpan]::FromSeconds($TimeoutSec))
    } catch [System.Threading.AbandonedMutexException] {
        $acquired = $true   # same reasoning as Enter-RunningAiRuntimeLock: usable immediately, not stuck forever
    }
    if ($acquired) { return $mutex }
    try { $mutex.Dispose() } catch { }
    return $null
}

function Exit-RunningAiWatchdogStateLock {
    param($Mutex)
    if (-not $Mutex) { return }
    try { $Mutex.ReleaseMutex() } catch { }
    try { $Mutex.Dispose() } catch { }
}

function New-EmptyHistory { $h = @{}; foreach ($c in $script:AllTrackedComponents) { $h[$c] = @() }; return $h }

# Phase 6I-1.7B-2B (STEP C/D): long-term failure tracking and lockout, persisted alongside (never
# replacing) the existing short-term restart history. Separate from $History/Get-RestartCount, which
# remain exactly as they were (10-minute/3-restart short-term budget, unchanged). One entry per
# tracked component: { Failures = [unix seconds, FAILURES only - not every attempt]; LockedOutSince =
# unix seconds | $null }. LockedOutSince, once set, is never cleared by this file's own machinery -
# only Clear-RunningAiComponentLockout (an explicit, separately-invoked operator action) can reset it.
function New-EmptyLongTermState {
    $h = @{}
    foreach ($c in $script:AllTrackedComponents) { $h[$c] = @{ Failures = @(); LockedOutSince = $null } }
    return $h
}

function Get-LongTermFailureCount {
    param([Parameter(Mandatory)]$LongTermState, [Parameter(Mandatory)][string]$Component, [Parameter(Mandatory)][datetime]$Now, [int]$WindowHours = 24)
    $since = ConvertTo-UnixSeconds $Now.AddHours(-$WindowHours)
    @(@($LongTermState[$Component].Failures) | Where-Object { $_ -ge $since }).Count
}

# Drops failure timestamps that fell out of the long-term window - LockedOutSince is NEVER touched
# here (requirement: a lockout persists even once the failures that caused it age out of the window,
# across reboots and Watchdog process restarts - the only point of a durable lockout is to require an
# explicit operator look, not to quietly expire on its own).
function Update-LongTermFailureWindow {
    param([Parameter(Mandatory)]$LongTermState, [Parameter(Mandatory)][datetime]$Now, [int]$WindowHours = 24)
    $since = ConvertTo-UnixSeconds $Now.AddHours(-$WindowHours)
    foreach ($c in $script:AllTrackedComponents) {
        $LongTermState[$c].Failures = @(@($LongTermState[$c].Failures) | Where-Object { $_ -ge $since })
    }
}

function Add-LongTermFailure {
    param([Parameter(Mandatory)]$LongTermState, [Parameter(Mandatory)][string]$Component, [Parameter(Mandatory)][datetime]$Now)
    $LongTermState[$Component].Failures = @(@($LongTermState[$Component].Failures) + (ConvertTo-UnixSeconds $Now))
}

# Sets LockedOutSince the FIRST time the windowed long-term failure count reaches $MaxFailures - a
# no-op if already locked (never re-stamps the timestamp, never un-locks). Call this right after
# Add-LongTermFailure for the same component/tick.
function Update-RunningAiLongTermLockout {
    param([Parameter(Mandatory)]$LongTermState, [Parameter(Mandatory)][string]$Component, [Parameter(Mandatory)][datetime]$Now, [int]$WindowHours = 24, [int]$MaxFailures = 6)
    if ($LongTermState[$Component].LockedOutSince) { return }
    if ((Get-LongTermFailureCount -LongTermState $LongTermState -Component $Component -Now $Now -WindowHours $WindowHours) -ge $MaxFailures) {
        $LongTermState[$Component].LockedOutSince = (ConvertTo-UnixSeconds $Now)
    }
}

function Test-RunningAiComponentLockedOut {
    param([Parameter(Mandatory)]$LongTermState, [Parameter(Mandatory)][string]$Component)
    [bool]$LongTermState[$Component].LockedOutSince
}

# Explicit, separately-invoked operator action (Phase 6I-1.7B-2B, STEP C) - never called by any
# automatic Watchdog code path. Clears both the lockout flag and the failure history for one
# component, so a (freshly saved) state no longer blocks its recovery. Intended caller:
# scripts/windows/clear-watchdog-lockout.ps1, run by hand after investigating why the component kept
# failing - this phase does not run it against any real state.
function Clear-RunningAiComponentLockout {
    param([Parameter(Mandatory)]$LongTermState, [Parameter(Mandatory)][string]$Component)
    $LongTermState[$Component] = @{ Failures = @(); LockedOutSince = $null }
}

# Returns @{ Available; History; LongTermState; Note }. A missing file is a normal first run. A
# malformed file is quarantined (renamed *.corrupt) and reported as NOT available: for that tick the
# watchdog must not restart anything, because without restart history it cannot enforce the budget.
# A v1 file (no "longTerm" key - Phase 6I-1.7B-1 and earlier) loads its existing "restarts" history
# exactly as before and gets a freshly-initialized, never-locked LongTermState - no restart history is
# lost, and a v1 file is never mistaken for "corrupt" just because it predates long-term tracking.
#
# -ReadOnly (Phase 6I-1.7B-2B, STEP G): skips the quarantine Move-Item - a DryRun observation must
# never rename/move a file on disk. The corrupt-file outcome (Available=$false, no history) is
# reported identically either way; only the quarantine side-effect itself is suppressed.
function Read-WatchdogState {
    param([string]$Path = $script:StatePath, [switch]$ReadOnly)
    if (-not (Test-Path -LiteralPath $Path)) {
        # Phase 6I-1.7B-2C (STEP 4, requirement 7): a missing main file is normally just "first run" -
        # but Save-WatchdogState also maintains a "$Path.bak" mirror of the last successful save, and a
        # TRUE first run never has one (nothing was ever saved yet). A backup that exists while the
        # main file does not is strong evidence the main file was deleted out from under a RUNNING
        # system (disk issue, an accidental "rm", AV quarantine, ...) - silently treating that as a
        # fresh, never-locked state would quietly erase a real long-term lockout and any restart
        # history with it. Reported as unavailable (fail-safe, no recovery this tick) instead, exactly
        # like a corrupt file - the backup itself is never auto-restored (the same "never auto-recover,
        # always fail-safe" rule as a corrupt main file; an operator must look and restore it by hand
        # if that is the right call). No file is touched either way, so -ReadOnly changes nothing here.
        $bak = "$Path.bak"
        if (Test-Path -LiteralPath $bak) {
            return @{ Available = $false; History = (New-EmptyHistory); LongTermState = (New-EmptyLongTermState); Note = "state file missing but a backup ($(Split-Path $bak -Leaf)) exists - possible accidental deletion, not auto-restored; investigate before any recovery resumes" }
        }
        return @{ Available = $true; History = (New-EmptyHistory); LongTermState = (New-EmptyLongTermState); Note = 'no state file (first run)' }
    }
    try {
        $raw = Get-Content -LiteralPath $Path -Raw -ErrorAction Stop
        $obj = ConvertFrom-Json $raw -ErrorAction Stop
        if ($null -eq $obj -or $null -eq $obj.restarts) { throw 'restarts missing' }
        # Phase 6I-1.7B-2B: dot-notation on a ConvertFrom-Json PSCustomObject for a property that is
        # genuinely absent (an older state file missing a component added later, or a pre-2B v1 file
        # with no "longTerm" key at all) throws PropertyNotFoundException under this file's
        # Set-StrictMode -Version Latest (see Get-RunningAiErrorDetails above for the same gotcha) -
        # that exception would be swallowed by the catch below and misreported as a corrupt file. Read
        # every optional key via PSObject.Properties instead of dot-notation.
        $restartsObj = $obj.restarts
        $history = New-EmptyHistory
        foreach ($c in $script:AllTrackedComponents) {
            $valuesProp = $restartsObj.PSObject.Properties[$c]
            if ($valuesProp -and $null -ne $valuesProp.Value) {
                $list = @()
                foreach ($v in @($valuesProp.Value)) { $list += [long]$v }
                $history[$c] = $list
            }
        }
        # Migration: absent entirely (v1) -> a fresh, never-locked state per component. Present but
        # missing one component's entry (e.g. a component added after this file was last written) ->
        # that one component only gets a fresh entry, every other component's real data is preserved.
        $longTermState = New-EmptyLongTermState
        $longTermProp = $obj.PSObject.Properties['longTerm']
        if ($longTermProp -and $longTermProp.Value) {
            $longTermObj = $longTermProp.Value
            foreach ($c in $script:AllTrackedComponents) {
                $entryProp = $longTermObj.PSObject.Properties[$c]
                if ($entryProp -and $null -ne $entryProp.Value) {
                    $entry = $entryProp.Value
                    $failures = @()
                    foreach ($v in @($entry.failures)) { $failures += [long]$v }
                    $longTermState[$c] = @{
                        Failures       = $failures
                        LockedOutSince = if ($null -ne $entry.lockedOutSince) { [long]$entry.lockedOutSince } else { $null }
                    }
                }
            }
        }
        return @{ Available = $true; History = $history; LongTermState = $longTermState; Note = 'ok' }
    } catch {
        if ($ReadOnly) {
            return @{ Available = $false; History = (New-EmptyHistory); LongTermState = (New-EmptyLongTermState); Note = 'state file unreadable (read-only check, not quarantined)' }
        }
        $corrupt = "$Path.corrupt"
        try { Move-Item -LiteralPath $Path -Destination $corrupt -Force -ErrorAction Stop } catch { }
        $bakHint = if (Test-Path -LiteralPath "$Path.bak") { " A backup ($(Split-Path "$Path.bak" -Leaf)) from the last successful save exists if manual recovery is needed - never auto-restored." } else { '' }
        return @{ Available = $false; History = (New-EmptyHistory); LongTermState = (New-EmptyLongTermState); Note = "state file unreadable, quarantined as $(Split-Path $corrupt -Leaf).$bakHint" }
    }
}

function Get-RestartCount {
    param([Parameter(Mandatory)]$History, [Parameter(Mandatory)][string]$Component, [Parameter(Mandatory)][datetime]$Now, [int]$WindowMinutes = 10)
    $since = ConvertTo-UnixSeconds $Now.AddMinutes(-$WindowMinutes)
    @(@($History[$Component]) | Where-Object { $_ -ge $since }).Count
}

# Drops restart timestamps that fell out of the window so the state file never grows.
function Update-HistoryWindow {
    param([Parameter(Mandatory)]$History, [Parameter(Mandatory)][datetime]$Now, [int]$WindowMinutes = 10)
    $since = ConvertTo-UnixSeconds $Now.AddMinutes(-$WindowMinutes)
    foreach ($c in $script:AllTrackedComponents) { $History[$c] = @(@($History[$c]) | Where-Object { $_ -ge $since }) }
}

function Add-RestartRecord {
    param([Parameter(Mandatory)]$History, [Parameter(Mandatory)][string]$Component, [Parameter(Mandatory)][datetime]$Now)
    $History[$Component] = @(@($History[$Component]) + (ConvertTo-UnixSeconds $Now))
}

# Phase 6I-1.7B-2B: writes schema version 2 (adds "longTerm" alongside the unchanged "restarts"
# shape) - still one atomic Write-JsonAtomic call, same file. $LongTermState defaults to a fresh
# empty one so any existing caller that does not yet track it (none left in this codebase, kept for
# defensiveness) still produces a valid, parseable v2 file rather than erroring.
# Phase 6I-1.7B-2C (STEP 4, requirement 8): Write-JsonAtomic's own exceptions (disk full, permission
# denied, path gone) are deliberately NOT caught here - a save failure must reach the caller's own
# try/catch (watch-running-ai.ps1 has one, logging WATCHDOG_ERROR and exiting non-zero) rather than
# being swallowed, which would otherwise leave the operator believing a tick's restart/lockout
# accounting was durably recorded when it was not. The "$Path.bak" mirror (requirement 7, see
# Read-WatchdogState above) is written SECOND, after the real file succeeds - if the main write
# throws, no stale "successful-looking" backup is produced; if the main write succeeds but the
# backup write itself throws, that too propagates rather than being silently ignored.
function Save-WatchdogState {
    param([Parameter(Mandatory)]$History, $LongTermState = (New-EmptyLongTermState), [string]$Path = $script:StatePath)
    $restarts = [ordered]@{}
    foreach ($c in $script:AllTrackedComponents) { $restarts[$c] = @($History[$c]) }
    $longTerm = [ordered]@{}
    foreach ($c in $script:AllTrackedComponents) {
        $longTerm[$c] = [ordered]@{ failures = @($LongTermState[$c].Failures); lockedOutSince = $LongTermState[$c].LockedOutSince }
    }
    $obj = [ordered]@{ version = 2; restarts = $restarts; longTerm = $longTerm }
    Write-JsonAtomic -Path $Path -Object $obj
    Write-JsonAtomic -Path "$Path.bak" -Object $obj
}

# ---- logging -------------------------------------------------------------------------------

# One key=value line per event: timestamp, component, state, reason/action, result. Never payloads.
function Write-WatchdogLog {
    param([string]$Component, [string]$State, [string]$Reason = '', [string]$Action = '', [string]$Result = '', [string]$Path = $script:WatchdogLogPath)
    $line = "{0} component={1} state={2}" -f (Get-Date).ToUniversalTime().ToString('yyyy-MM-ddTHH:mm:ssZ'), $Component, $State
    if ($Reason) { $line += " reason=$Reason" }
    if ($Action) { $line += " action=$Action" }
    if ($Result) { $line += " result=$Result" }
    New-Item -ItemType Directory -Force (Split-Path $Path -Parent) | Out-Null
    Add-Content -LiteralPath $Path -Value $line -Encoding ASCII
}

# ---- observation ---------------------------------------------------------------------------

function Get-DefaultProbes {
    param([int]$ConnectorPort, [int]$SpringPort, [int]$RelayPort, [switch]$ReadOnly)
    $compose = Join-Path (Get-RepoRoot) 'docker-compose.yml'
    # .GetNewClosure() gives each probe its own copy of $ConnectorPort/$SpringPort/$RelayPort/
    # $compose so they keep working after Get-DefaultProbes returns - but a closure's new session
    # state chains straight up to the GLOBAL scope, NOT to the scope where this file was
    # dot-sourced. When this script is invoked directly (".\watch-running-ai.ps1", as an operator
    # or a test would) rather than via "powershell -File" (as the Scheduled Task does), that
    # dot-sourced scope is NOT global, so a closure calling a helper FUNCTION by name - Quote-
    # Argument, Invoke-NativeText, Test-ConnectorHealth, Test-PortInUse, Test-SpringHealth,
    # Test-ExternalRelayHealth - throws "term '<name>' is not recognized" (live-reproduced).
    # Closures resolve captured VARIABLES fine, so every helper function this file's closures need
    # is captured as a variable (${function:Name}) here, outside the closures, and invoked with
    # "& $var" instead of by name - sidestepping the scope-chain resolution entirely rather than
    # relying on how the script happens to be invoked.
    $quoteArgumentFn       = ${function:Quote-Argument}
    $invokeNativeTextFn    = ${function:Invoke-NativeText}
    $testConnectorHealthFn = ${function:Test-ConnectorHealth}
    $testSpringHealthFn    = ${function:Test-SpringHealth}
    $testExternalRelayHealthFn = ${function:Test-ExternalRelayHealth}
    $testPortInUseFn       = ${function:Test-PortInUse}
    # Phase 6I-1.7B-2B (STEP G): $ReadOnly must reach Get-TrackedProcessId so a DryRun observation
    # never removes a stale/foreign .pid file. These three probes now reference a captured variable
    # ($ReadOnly), so - same reason as every other probe here - they need .GetNewClosure() too, and
    # therefore the SAME function-reference-variable workaround for the functions they call by name.
    $getTrackedProcessIdFn = ${function:Get-TrackedProcessId}
    $getConnectorMarkersFn = ${function:Get-ConnectorMarkers}
    $getSpringMarkersFn    = ${function:Get-SpringMarkers}
    $getExternalRelayMarkersFn = ${function:Get-ExternalRelayMarkers}
    @{
        Docker = {
            if (-not (Get-Command docker -ErrorAction SilentlyContinue)) { return 'NO_CLI' }
            if ((Invoke-NativeQuiet 'docker' 'info') -eq 0) { 'UP' } else { 'DOWN' }
        }
        Postgres = {
            $composeArg = & $quoteArgumentFn $compose
            $cid = (& $invokeNativeTextFn 'docker' "compose -f $composeArg ps -a -q postgres").Trim()
            if (-not $cid) { return 'stopped' }
            $text = (& $invokeNativeTextFn 'docker' "inspect -f {{.State.Running}}/{{.State.Health.Status}} $cid").Trim()
            if ($text -notmatch '^(true|false)/(\w*)') { return 'unknown' }
            if ($Matches[1] -eq 'false') { return 'stopped' }
            switch ($Matches[2]) { 'healthy' { 'healthy' } 'unhealthy' { 'unhealthy' } 'starting' { 'starting' } default { 'unknown' } }
        }.GetNewClosure()
        ConnectorHealth   = { & $testConnectorHealthFn $ConnectorPort }.GetNewClosure()
        ConnectorPortUsed = { & $testPortInUseFn $ConnectorPort }.GetNewClosure()
        ConnectorPid      = { & $getTrackedProcessIdFn 'garmin-connector' (& $getConnectorMarkersFn) -ReadOnly:$ReadOnly }.GetNewClosure()
        SpringHealth      = { & $testSpringHealthFn $SpringPort }.GetNewClosure()
        SpringPortUsed    = { & $testPortInUseFn $SpringPort }.GetNewClosure()
        SpringPid         = { & $getTrackedProcessIdFn 'spring' (& $getSpringMarkersFn) -ReadOnly:$ReadOnly }.GetNewClosure()
        ExternalRelayHealth   = { & $testExternalRelayHealthFn $RelayPort }.GetNewClosure()
        ExternalRelayPortUsed = { & $testPortInUseFn $RelayPort }.GetNewClosure()
        ExternalRelayPid      = { & $getTrackedProcessIdFn 'external-relay' (& $getExternalRelayMarkersFn) -ReadOnly:$ReadOnly }.GetNewClosure()
    }
}

# Collects raw facts. When a managed process is alive but its health check fails, the check is
# repeated once after $RecheckDelaySec so a momentary hiccup is not treated as a persistent failure.
# -ReadOnly (Phase 6I-1.7B-2B, STEP G): forwarded to Get-DefaultProbes so a DryRun observation never
# removes a stale/foreign .pid file as a side effect of merely looking at it.
function Get-RuntimeObservation {
    param(
        [int]$ConnectorPort = 8765,
        [int]$SpringPort = 8080,
        [int]$RelayPort = (Get-ExternalRelayConfiguredPort),
        [int]$RecheckDelaySec = 10,
        [hashtable]$Probes,
        [switch]$ReadOnly
    )
    $p = Get-DefaultProbes -ConnectorPort $ConnectorPort -SpringPort $SpringPort -RelayPort $RelayPort -ReadOnly:$ReadOnly
    if ($Probes) { foreach ($k in $Probes.Keys) { $p[$k] = $Probes[$k] } }

    $docker = & $p.Docker
    $postgres = if ($docker -eq 'UP') { & $p.Postgres } else { 'unknown' }

    $cPid = & $p.ConnectorPid
    $cHealth = [bool](& $p.ConnectorHealth)
    if (-not $cHealth -and $cPid -and $RecheckDelaySec -gt 0) { Start-Sleep -Seconds $RecheckDelaySec; $cHealth = [bool](& $p.ConnectorHealth) }
    $cPort = if ($cHealth) { $true } else { [bool](& $p.ConnectorPortUsed) }

    $sPid = & $p.SpringPid
    $sHealth = [bool](& $p.SpringHealth)
    if (-not $sHealth -and $sPid -and $RecheckDelaySec -gt 0) { Start-Sleep -Seconds $RecheckDelaySec; $sHealth = [bool](& $p.SpringHealth) }
    $sPort = if ($sHealth) { $true } else { [bool](& $p.SpringPortUsed) }

    $rPid = & $p.ExternalRelayPid
    $rHealth = [bool](& $p.ExternalRelayHealth)
    if (-not $rHealth -and $rPid -and $RecheckDelaySec -gt 0) { Start-Sleep -Seconds $RecheckDelaySec; $rHealth = [bool](& $p.ExternalRelayHealth) }
    $rPort = if ($rHealth) { $true } else { [bool](& $p.ExternalRelayPortUsed) }

    [pscustomobject]@{
        Docker = $docker; Postgres = $postgres
        ExternalRelayHealth = $rHealth; ExternalRelayPortUsed = $rPort; ExternalRelayPid = $rPid
        ConnectorHealth = $cHealth; ConnectorPortUsed = $cPort; ConnectorPid = $cPid
        SpringHealth = $sHealth; SpringPortUsed = $sPort; SpringPid = $sPid
    }
}

# ---- classification ------------------------------------------------------------------------

function New-State { param([string]$State, [string]$Reason = '') [pscustomobject]@{ State = $State; Reason = $Reason } }

function Get-ProcessComponentState {
    param([bool]$Health, [bool]$PortUsed, $ManagedPid)
    if ($Health) { return (New-State 'UP') }
    if ($ManagedPid) { return (New-State 'UNHEALTHY' 'PROCESS_ALIVE_HEALTH_FAILING') }
    if ($PortUsed) { return (New-State 'DEGRADED' 'FOREIGN_PROCESS') }     # something else owns the port: never touch it
    return (New-State 'DOWN' 'PROCESS_DEAD')
}

# Connector-specific classifier (Phase 6I-1.7B-1). Identical to Get-ProcessComponentState for every
# case that function already handled correctly (healthy, tracked-and-alive, nothing on the port) -
# and ONLY when $Port is supplied does it resolve the one case the plain PID-file check cannot:
# health failing, tracked PID gone/foreign, but something IS on the port. Instead of always guessing
# FOREIGN_PROCESS there, it runs the ownership model to tell a genuine foreign process apart from our
# own orphaned listener (see RunningAI.ConnectorOwnership.ps1), reusing the SAME 'UNHEALTHY'/
# 'DEGRADED' state vocabulary Get-RecoveryPlan already understands - no restart-policy change, only a
# more accurate Reason feeding into it. $Port omitted (the default) reproduces the exact legacy
# behavior byte-for-byte, which is what every existing hand-built Observation in tests still gets.
function Get-ConnectorComponentState {
    param([bool]$Health, [bool]$PortUsed, $ManagedPid, $Port = $null)

    # Phase 6I-1.7B-2A: resolved once, up front, and attached to WHATEVER state is ultimately returned
    # (purely informational - .OwnershipVerdict never feeds into State/Reason/the restart decision) so
    # an operator can see a mismatch even on a healthy connector. $Port omitted (the default, what
    # every existing hand-built Observation in tests still gets) skips this entirely and reproduces
    # the exact legacy behavior byte-for-byte - no ownership call, no extra property, at all.
    $ownership = $null
    if ($null -ne $Port) {
        try { $ownership = Get-RunningAiConnectorOwnership -Port $Port -TrackedPid (Read-PidFile 'garmin-connector') -Markers (Get-ConnectorMarkers) } catch { }
    }
    $tag = { param($s) if ($ownership) { $s | Add-Member -NotePropertyName 'OwnershipVerdict' -NotePropertyValue $ownership.Verdict -Force }; $s }

    if ($Health) {
        # A passing health check proves the connector answers HTTP correctly, not that ownership is
        # confirmed - never, on its own, treated as "ownership verified". Existing behavior (no action
        # taken while healthy, restart policy untouched) is preserved exactly: State/Reason stay
        # 'UP'/'' regardless of what ownership resolves to.
        return (& $tag (New-State 'UP'))
    }
    if ($ManagedPid) { return (& $tag (New-State 'UNHEALTHY' 'PROCESS_ALIVE_HEALTH_FAILING')) }
    if (-not $PortUsed) { return (& $tag (New-State 'DOWN' 'PROCESS_DEAD')) }
    if ($null -eq $Port) { return (New-State 'DEGRADED' 'FOREIGN_PROCESS') }   # legacy fallback, unchanged

    switch ($ownership.Verdict) {
        'ORPHANED_MANAGED_PROCESS' { return (& $tag (New-State 'UNHEALTHY' 'ORPHANED_MANAGED_PROCESS')) }
        'UNKNOWN_OWNER'            { return (& $tag (New-State 'DEGRADED' 'UNKNOWN_OWNER')) }
        'DOWN'                     { return (& $tag (New-State 'DOWN' 'PROCESS_DEAD')) }
        default                    { return (& $tag (New-State 'DEGRADED' 'FOREIGN_PROCESS')) }
    }
}

function Get-ComponentStates {
    param([Parameter(Mandatory)]$Observation, $ConnectorPort = $null)
    $o = $Observation
    $states = [ordered]@{}
    $states['docker'] = switch ($o.Docker) {
        'UP'     { New-State 'UP' }
        'NO_CLI' { New-State 'DOWN' 'NO_CLI' }
        default  { New-State 'DOWN' 'DAEMON_UNREACHABLE' }
    }
    $states['postgres'] = if ($o.Docker -ne 'UP') { New-State 'UNKNOWN' 'DEPENDENCY_DOCKER' } else {
        switch ($o.Postgres) {
            'healthy'   { New-State 'UP' }
            'unhealthy' { New-State 'UNHEALTHY' 'CONTAINER_UNHEALTHY' }
            'starting'  { New-State 'UNKNOWN' 'STARTING' }
            'stopped'   { New-State 'DOWN' 'CONTAINER_NOT_RUNNING' }
            default     { New-State 'UNKNOWN' 'HEALTH_UNREADABLE' }
        }
    }
    $states['connector'] = Get-ConnectorComponentState $o.ConnectorHealth $o.ConnectorPortUsed $o.ConnectorPid $ConnectorPort
    $states['spring']    = Get-ProcessComponentState $o.SpringHealth $o.SpringPortUsed $o.SpringPid
    $states['external-relay'] = Get-ProcessComponentState $o.ExternalRelayHealth $o.ExternalRelayPortUsed $o.ExternalRelayPid
    return $states
}

# ---- garmin hint (informational only) ---------------------------------------------------------

# Reads recent Spring log lines for the scheduler's own outcome. Authentication, rate-limit and
# upstream problems are NOT process failures: the hint is shown to the operator and can never
# cause a restart. The latest event wins, and hints older than $MaxAgeMinutes are ignored.
function Get-GarminHint {
    param([string[]]$Lines, [datetime]$Now = (Get-Date), [int]$MaxAgeMinutes = 120)
    $hint = $null
    foreach ($line in $Lines) {
        if ($line -notmatch '^(?<ts>\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(\.\d+)?[+-]\d{2}:\d{2})\s') { continue }
        $ts = [DateTimeOffset]::MinValue
        if (-not [DateTimeOffset]::TryParse($Matches['ts'], [ref]$ts)) { continue }
        if ($line -match 'Garmin scheduled sync completed') { $hint = $null; continue }
        if ($line -match 'Garmin scheduled sync failed: reason=(?<r>AUTH_REQUIRED|FORBIDDEN|RATE_LIMITED|UPSTREAM_ERROR)') {
            $hint = [pscustomobject]@{ Reason = 'GARMIN_' + $Matches['r']; At = $ts }
        }
    }
    if ($hint -and ($Now.ToUniversalTime() - $hint.At.UtcDateTime).TotalMinutes -le $MaxAgeMinutes) { return $hint.Reason }
    return $null
}

function Get-GarminHintFromLog {
    param([string]$Path = (Join-Path $script:LogDir 'spring.out.log'), [datetime]$Now = (Get-Date))
    if (-not (Test-Path -LiteralPath $Path)) { return $null }
    try { return (Get-GarminHint -Lines (Get-Content -LiteralPath $Path -Tail 300 -ErrorAction Stop) -Now $Now) } catch { return $null }
}

# ---- planning ------------------------------------------------------------------------------

# Turns component states into an ordered recovery plan honouring dependency order and the restart
# budget. Nothing here executes anything.
#   Actions: @{ Component; Action; Reason; PreStop }
#   Blocked: @{ Component; Reason }   (component is not UP and will not be touched, and why)
function Get-RecoveryPlan {
    param(
        [Parameter(Mandatory)]$States,
        [Parameter(Mandatory)]$History,
        [Parameter(Mandatory)][datetime]$Now,
        [bool]$HistoryAvailable = $true,
        [int]$WindowMinutes = 10,
        [int]$MaxRestarts = 3,
        $LongTermState = $null
    )
    $actions = New-Object System.Collections.ArrayList
    $blocked = New-Object System.Collections.ArrayList
    $ready = $true     # true while every upstream component is up or has a recovery action planned

    foreach ($c in $script:Components) {
        $s = $States[$c]
        if ($s.State -eq 'UP') { continue }
        if ($s.State -eq 'UNKNOWN' -and $s.Reason -eq 'DEPENDENCY_DOCKER') { continue }   # judged after Docker is recovered

        # Phase 6I-1.7B-2B (STEP C): a persistent long-term lockout blocks this component outright,
        # before even the short-term budget is consulted, and ($LongTermState optional - omitted by
        # any existing caller that does not yet track it, reproducing the exact pre-lockout behavior).
        if ($LongTermState -and (Test-RunningAiComponentLockedOut -LongTermState $LongTermState -Component $c)) {
            [void]$blocked.Add([pscustomobject]@{ Component = $c; Reason = 'LOCKED_OUT' }); $ready = $false; continue
        }

        if (-not $ready) { [void]$blocked.Add([pscustomobject]@{ Component = $c; Reason = 'DEPENDENCY_NOT_READY' }); continue }

        $action = $null; $preStop = $false; $unrecoverable = $null
        switch ($c) {
            'docker' {
                if ($s.Reason -eq 'NO_CLI') { $unrecoverable = 'CONFIGURATION_ERROR_NO_DOCKER_CLI' } else { $action = 'START_DOCKER' }
            }
            'postgres' {
                switch ($s.State) {
                    'DOWN'      { $action = 'COMPOSE_UP' }
                    'UNHEALTHY' { $unrecoverable = 'POSTGRES_UNHEALTHY_NO_BLIND_RESTART' }   # diagnose, never restart blindly
                    default     { $unrecoverable = "POSTGRES_$($s.Reason)" }
                }
            }
            { $_ -in 'connector', 'spring' } {
                $name = $c.ToUpper()
                switch ($s.State) {
                    'DOWN'      { $action = "START_$name" }
                    'UNHEALTHY' { $action = "RESTART_$name"; $preStop = $true }
                    'DEGRADED'  { $unrecoverable = $s.Reason }                                 # FOREIGN_PROCESS
                    default     { $unrecoverable = "$name`_$($s.Reason)" }
                }
            }
        }

        if ($unrecoverable) { [void]$blocked.Add([pscustomobject]@{ Component = $c; Reason = $unrecoverable }); $ready = $false; continue }
        if (-not $HistoryAvailable) { [void]$blocked.Add([pscustomobject]@{ Component = $c; Reason = 'STATE_UNAVAILABLE_FAIL_SAFE' }); $ready = $false; continue }
        if ((Get-RestartCount -History $History -Component $c -Now $Now -WindowMinutes $WindowMinutes) -ge $MaxRestarts) {
            [void]$blocked.Add([pscustomobject]@{ Component = $c; Reason = 'RESTART_BUDGET_EXCEEDED' }); $ready = $false; continue
        }
        [void]$actions.Add([pscustomobject]@{ Component = $c; Action = $action; Reason = $s.Reason; PreStop = $preStop })
    }

    # ---- independent components (Phase 6I-1.1: external relay) --------------------------------
    # Deliberately its own pass, outside the $ready cascade above: a blocked/failed core-chain
    # component must never block this, and this must never set $ready = $false and block the
    # core chain either. Same restart-budget/history machinery, evaluated independently.
    foreach ($c in $script:IndependentComponents) {
        $s = $States[$c]
        if ($s.State -eq 'UP') { continue }

        if ($LongTermState -and (Test-RunningAiComponentLockedOut -LongTermState $LongTermState -Component $c)) {
            [void]$blocked.Add([pscustomobject]@{ Component = $c; Reason = 'LOCKED_OUT' }); continue
        }

        $action = $null; $preStop = $false; $unrecoverable = $null
        switch ($c) {
            'external-relay' {
                switch ($s.State) {
                    'DOWN'      { $action = 'START_EXTERNAL_RELAY' }
                    'UNHEALTHY' { $action = 'RESTART_EXTERNAL_RELAY'; $preStop = $true }
                    'DEGRADED'  { $unrecoverable = $s.Reason }   # FOREIGN_PROCESS - never kill, never replace
                    default     { $unrecoverable = "EXTERNAL_RELAY_$($s.Reason)" }
                }
            }
        }

        if ($unrecoverable) { [void]$blocked.Add([pscustomobject]@{ Component = $c; Reason = $unrecoverable }); continue }
        if (-not $HistoryAvailable) { [void]$blocked.Add([pscustomobject]@{ Component = $c; Reason = 'STATE_UNAVAILABLE_FAIL_SAFE' }); continue }
        if ((Get-RestartCount -History $History -Component $c -Now $Now -WindowMinutes $WindowMinutes) -ge $MaxRestarts) {
            [void]$blocked.Add([pscustomobject]@{ Component = $c; Reason = 'RESTART_BUDGET_EXCEEDED' }); continue
        }
        [void]$actions.Add([pscustomobject]@{ Component = $c; Action = $action; Reason = $s.Reason; PreStop = $preStop })
    }

    [pscustomobject]@{ Actions = @($actions); Blocked = @($blocked) }
}

# UP | DEGRADED | DOWN from the final states, blocked reasons and the optional Garmin hint.
function Get-OverallState {
    param([Parameter(Mandatory)]$States, $Blocked = @(), [string]$GarminHint)
    $values = @($script:AllTrackedComponents | ForEach-Object { $States[$_].State })
    if (@($values | Where-Object { $_ -eq 'DOWN' }).Count -gt 0) { return 'DOWN' }
    if (@($values | Where-Object { $_ -ne 'UP' }).Count -gt 0 -or @($Blocked).Count -gt 0 -or $GarminHint) { return 'DEGRADED' }
    return 'UP'
}

# ---- execution -----------------------------------------------------------------------------

# ---- recovery outcome contract (Phase 6I-1.7B-2C, STEP 2) ---------------------------------------
#
# Replaces the plain boolean $Runner result with a richer, explicit outcome so budget/lockout
# attribution can be precise: AttemptedComponent (what this action targeted) is NOT always the same
# as ActualFailedComponent (what the evidence - an exit code, a verified ownership verdict - actually
# points to), and only ActualFailedComponent may ever be charged a long-term failure (never a guess).
#   ResultCode: SUCCESS | FAILED | BUSY | BLOCKED | TIMED_OUT | UNKNOWN_FAILURE
#   ActualFailedComponent: the component the evidence identifies as actually broken, or $null when
#     the action succeeded, was never attempted (BUSY), was refused (BLOCKED), or could not be
#     identified (UNKNOWN_FAILURE).
#   BudgetChargeComponent: which component's long-term failure count this outcome charges - $null for
#     everything except FAILED/TIMED_OUT (see Get-RunningAiRecoveryBudgetChargeComponent below).
#   Retryable: informational only (never read by this file's own logic) - $false only for SUCCESS.
function New-RunningAiRecoveryOutcome {
    param(
        [Parameter(Mandatory)][string]$AttemptedComponent,
        [Parameter(Mandatory)][ValidateSet('SUCCESS', 'FAILED', 'BUSY', 'BLOCKED', 'TIMED_OUT', 'UNKNOWN_FAILURE')][string]$ResultCode,
        [string]$ActionPerformed = $null,
        [string]$ActualFailedComponent = $null
    )
    $budgetChargeComponent = if ($ResultCode -in @('FAILED', 'TIMED_OUT')) { if ($ActualFailedComponent) { $ActualFailedComponent } else { $AttemptedComponent } } else { $null }
    [pscustomobject]@{
        AttemptedComponent     = $AttemptedComponent
        ActionPerformed        = $ActionPerformed
        ResultCode             = $ResultCode
        ActualFailedComponent  = $ActualFailedComponent
        BudgetChargeComponent  = $budgetChargeComponent
        Retryable              = ($ResultCode -ne 'SUCCESS')
    }
}

# Normalizes whatever $Runner returned into the rich outcome shape above. A PLAIN BOOLEAN (every
# existing test in Test-Watchdog.ps1 and most of Test-WatchdogRecoveryPolicy.ps1 injects one) is
# mapped to the exact legacy meaning - $true = SUCCESS, charge nobody; $false = FAILED, charge
# $AttemptedComponent - so none of those tests needed to change for this phase. A caller (the real
# Invoke-RecoveryAction, or a new test exercising the new attribution rules directly) that already
# returns a New-RunningAiRecoveryOutcome-shaped object is passed through unchanged.
function ConvertTo-RunningAiRecoveryOutcome {
    param($RunResult, [Parameter(Mandatory)][string]$AttemptedComponent)
    if ($RunResult -is [bool]) {
        if ($RunResult) { return (New-RunningAiRecoveryOutcome -AttemptedComponent $AttemptedComponent -ResultCode 'SUCCESS') }
        return (New-RunningAiRecoveryOutcome -AttemptedComponent $AttemptedComponent -ResultCode 'FAILED' -ActualFailedComponent $AttemptedComponent)
    }
    if ($RunResult -and $RunResult.PSObject.Properties['ResultCode']) { return $RunResult }
    # Neither shape - fail safe rather than guess at meaning.
    return (New-RunningAiRecoveryOutcome -AttemptedComponent $AttemptedComponent -ResultCode 'UNKNOWN_FAILURE')
}

# Phase 6I-1.7B-2C (STEP 2, rule 6): re-observes the REAL runtime (never the test-injectable $Observe
# used by the generic Invoke-WatchdogRecovery loop - this is called only from the real
# Invoke-RecoveryAction helpers below, which talk to the real Docker/connector/Spring/relay) and
# confirms the attempted component is now actually UP before a successful exit code is trusted. A
# start/restart script can exit 0 while the component it targeted still never came up (for example
# it raced a transient port conflict that resolved itself one second too late) - exit code alone is
# not proof of success.
function Test-RunningAiRecoverySucceeded {
    param([Parameter(Mandatory)][string]$Component, [int]$ConnectorPort = 8765, [int]$SpringPort = 8080)
    $o = Get-RuntimeObservation -ConnectorPort $ConnectorPort -SpringPort $SpringPort -RecheckDelaySec 0
    $states = Get-ComponentStates $o -ConnectorPort $ConnectorPort
    return ($states[$Component].State -eq 'UP')
}

# Maps a start-running-ai.ps1 exit code back to the component it actually describes - $ExitCode.Java
# (JDK discovery) maps to 'spring' (Java is only ever needed to start Spring in this codebase, never
# the Python connector), $ExitCode.Ok and any unrecognized code map to $null (no specific component -
# "success" or "cannot attribute", never a guess).
function Get-RunningAiComponentFromExitCode {
    param([int]$ExitCode)
    switch ($ExitCode) {
        $script:ExitCode.Docker        { 'docker' }
        $script:ExitCode.Postgres      { 'postgres' }
        $script:ExitCode.Connector     { 'connector' }
        $script:ExitCode.Java          { 'spring' }
        $script:ExitCode.Spring        { 'spring' }
        $script:ExitCode.ExternalRelay { 'external-relay' }
        default                        { $null }
    }
}

# Dispatches to the connector/spring (core-chain) or external-relay recovery helper. Both helpers
# acquire the SAME runtime lock start-running-ai.ps1/stop-running-ai.ps1 use (Phase 6I-1.7B-2C, STEP
# 1) for the FULL duration of PreStop + the subsequent Start/Restart - one serialized unit, mutually
# exclusive with a manual start/stop touching the same runtime. A failed acquisition (another manual
# run is mid-operation) returns BUSY immediately: no PreStop, no Start, nothing touched.
function Invoke-RecoveryAction {
    param([Parameter(Mandatory)]$Action, [int]$ConnectorPort = 8765, [int]$SpringPort = 8080, [int]$LockTimeoutSec = 10)
    if ($Action.Component -eq 'external-relay') {
        return (Invoke-RunningAiRelayRecoveryAction -Action $Action -LockTimeoutSec $LockTimeoutSec)
    }
    return (Invoke-RunningAiCoreRecoveryAction -Action $Action -ConnectorPort $ConnectorPort -SpringPort $SpringPort -LockTimeoutSec $LockTimeoutSec)
}

# Spawns $FilePath/$ArgumentList as a Watchdog-trusted child: RUNNING_AI_WATCHDOG_LOCK_INHERITED is
# set to '1' on ONLY that child process's own environment block (never this process's $env:, which a
# later, unrelated probe/action in the SAME Watchdog tick could otherwise inherit) - see
# RunningAI.Common.ps1's Enter-RunningAiRuntimeLock for why this exists. Returns
# @{ Proc; TimedOut }; caller is responsible for reading .ExitCode only when -not TimedOut.
function Start-RunningAiWatchdogTrustedChild {
    param([Parameter(Mandatory)][string]$FilePath, [Parameter(Mandatory)][string]$ArgumentList, [Parameter(Mandatory)][int]$TimeoutMs)
    $psi = New-Object System.Diagnostics.ProcessStartInfo
    $psi.FileName = $FilePath
    $psi.Arguments = $ArgumentList
    $psi.UseShellExecute = $false
    $psi.CreateNoWindow = $true
    $psi.WindowStyle = [System.Diagnostics.ProcessWindowStyle]::Hidden
    $psi.EnvironmentVariables['RUNNING_AI_WATCHDOG_LOCK_INHERITED'] = '1'
    $proc = New-Object System.Diagnostics.Process
    $proc.StartInfo = $psi
    [void]$proc.Start()
    $timedOut = -not $proc.WaitForExit($TimeoutMs)
    if ($timedOut) { try { $proc.Kill() } catch { } }
    [pscustomobject]@{ Proc = $proc; TimedOut = $timedOut }
}

# Connector/Spring (core-chain) recovery for ONE action: ownership-aware PreStop (connector) or plain
# tracked-PID PreStop (Spring), then start-running-ai.ps1 as a trusted child, bundled under one lock
# hold (Phase 6I-1.7B-2C, STEP 1).
function Invoke-RunningAiCoreRecoveryAction {
    param([Parameter(Mandatory)]$Action, [int]$ConnectorPort = 8765, [int]$SpringPort = 8080, [int]$LockTimeoutSec = 10)

    $lock = Enter-RunningAiRuntimeLock -TimeoutSec $LockTimeoutSec
    if (-not $lock) {
        Write-Step "Recovery of $($Action.Component) deferred: another start/stop against this runtime is already in progress (BUSY). Nothing touched."
        return (New-RunningAiRecoveryOutcome -AttemptedComponent $Action.Component -ResultCode 'BUSY' -ActionPerformed $Action.Action)
    }
    try {
        if ($Action.PreStop) {
            if ($Action.Component -eq 'connector') {
                # Phase 6I-1.7B-1: ownership-aware stop, not the plain tracked-PID stop. This is the
                # path that reaches an ORPHANED_MANAGED_PROCESS (tracked launcher already gone, its
                # listener child still holding the port) - Get-TrackedProcessId alone would see
                # nothing to stop and silently leave the port occupied, so the subsequent restart's
                # bind would fail.
                $tracked = Read-PidFile 'garmin-connector'
                $ownership = Get-RunningAiConnectorOwnership -Port $ConnectorPort -TrackedPid $tracked -Markers (Get-ConnectorMarkers)
                switch ($ownership.Verdict) {
                    'DOWN' {
                        # Confirmed nothing is listening - safe to fall through to the start attempt below.
                    }
                    { $_ -in @('SELF_OWNED', 'LAUNCHER_CHILD', 'ORPHANED_MANAGED_PROCESS', 'LAUNCHER_ALIVE_NO_LISTENER') } {
                        if (@($ownership.ManagedPids).Count -eq 0) {
                            Write-Step "Garmin connector pre-stop blocked: verdict $($ownership.Verdict) reported no manageable PIDs; refusing to restart."
                            return (New-RunningAiRecoveryOutcome -AttemptedComponent 'connector' -ResultCode 'BLOCKED' -ActionPerformed $Action.Action)
                        }
                        $stopResult = Stop-RunningAiConnectorManaged -Ownership $ownership -Port $ConnectorPort -TimeoutSec 15
                        $cleanStop = Test-RunningAiConnectorStopWasClean -StopResult $stopResult
                        if ($cleanStop) {
                            Remove-PidFile 'garmin-connector'
                        } else {
                            # A failed/partial pre-stop must never be followed by starting a new
                            # connector anyway - the port may still be occupied by what we just failed
                            # to clear. PID file and metadata are preserved (not touched above) so the
                            # next tick sees the real state. Phase 6I-1.7B-2C: an incomplete pre-stop is
                            # a genuine failure OF THE CONNECTOR, not a mere refusal - charged exactly
                            # like any other connector recovery failure (rule 2: the actual failed
                            # component, here unambiguously the connector itself).
                            Write-Step "Garmin connector pre-stop did not fully succeed (result=$($stopResult.Result), resultCode=$($stopResult.ResultCode), portFreed=$($stopResult.PortFreed), remaining=$($stopResult.RemainingPids -join ',')); not starting a new connector this tick."
                            return (New-RunningAiRecoveryOutcome -AttemptedComponent 'connector' -ResultCode 'FAILED' -ActionPerformed $Action.Action -ActualFailedComponent 'connector')
                        }
                    }
                    default {
                        # FOREIGN_PROCESS, UNKNOWN_OWNER, or any future verdict not explicitly handled
                        # above - ownership of whatever is on the port could not be verified. A refusal
                        # to act, not a failed attempt (rule 3: never charges the long-term budget).
                        Write-Step "Garmin connector pre-stop blocked: ownership verdict is $($ownership.Verdict) ($($ownership.Detail)); refusing to restart an unverified process on port $ConnectorPort."
                        return (New-RunningAiRecoveryOutcome -AttemptedComponent 'connector' -ResultCode 'BLOCKED' -ActionPerformed $Action.Action)
                    }
                }
            } else {
                $tracked = Get-TrackedProcessId 'spring' (Get-SpringMarkers)
                if ($tracked) { Stop-TrackedProcess -ProcessId $tracked -Markers (Get-SpringMarkers) -TimeoutSec 30 | Out-Null; Remove-PidFile 'spring' }
            }
        }

        # Phase 6I-1.7B-2B (STEP B) / 2C (STEP 2): start-running-ai.ps1 is a single shared script
        # covering Docker -> PostgreSQL -> connector -> Spring, each with its OWN dedicated exit code
        # (see RunningAI.Common.ps1 $ExitCode) - it does NOT report "connector failed" vs "Spring
        # failed" as one undifferentiated failure. The actual failing layer is read from the exit code
        # and, when identifiable, is the ONLY component a long-term failure is ever charged against
        # (rule 2/7/9) - never the originally-planned $Action.Component when the two differ.
        $script = Join-Path $PSScriptRoot 'start-running-ai.ps1'
        $ps = (Get-Command powershell.exe).Source
        $argLine = "-NoProfile -ExecutionPolicy Bypass -File $(Quote-Argument $script) -ConnectorPort $ConnectorPort -SpringPort $SpringPort"
        $spawn = Start-RunningAiWatchdogTrustedChild -FilePath $ps -ArgumentList $argLine -TimeoutMs 900000
        if ($spawn.TimedOut) {
            Write-WatchdogLog -Component $Action.Component -State 'RECOVERING' -Reason $Action.Reason -Action $Action.Action -Result 'TIMED_OUT'
            return (New-RunningAiRecoveryOutcome -AttemptedComponent $Action.Component -ResultCode 'TIMED_OUT' -ActionPerformed $Action.Action)
        }
        $exitCode = $spawn.Proc.ExitCode
        if ($exitCode -ne 0) {
            $actualComponent = Get-RunningAiComponentFromExitCode -ExitCode $exitCode
            $actualLabel = if ($actualComponent) { $actualComponent.ToUpper() } else { 'UNKNOWN_FAILURE' }
            if ($actualComponent -and $actualComponent -ne $Action.Component) {
                Write-WatchdogLog -Component $Action.Component -State 'RECOVERING' -Reason $Action.Reason -Action $Action.Action -Result "FAILED_ACTUAL_COMPONENT_$($actualLabel)_EXIT_$($exitCode)"
            } else {
                Write-WatchdogLog -Component $Action.Component -State 'RECOVERING' -Reason $Action.Reason -Action $Action.Action -Result "FAILED_EXIT_$($exitCode)"
            }
            if ($actualComponent) {
                return (New-RunningAiRecoveryOutcome -AttemptedComponent $Action.Component -ResultCode 'FAILED' -ActionPerformed $Action.Action -ActualFailedComponent $actualComponent)
            }
            # Rule 4: the exit code does not map to any known component - never guess, never charge.
            return (New-RunningAiRecoveryOutcome -AttemptedComponent $Action.Component -ResultCode 'UNKNOWN_FAILURE' -ActionPerformed $Action.Action)
        }
        # Rule 5/6: exit 0 is not itself proof of success - re-observe the real runtime and require the
        # attempted component to actually be UP before reporting SUCCESS.
        if (-not (Test-RunningAiRecoverySucceeded -Component $Action.Component -ConnectorPort $ConnectorPort -SpringPort $SpringPort)) {
            Write-WatchdogLog -Component $Action.Component -State 'RECOVERING' -Reason $Action.Reason -Action $Action.Action -Result 'FAILED_VERIFICATION_STILL_NOT_UP'
            return (New-RunningAiRecoveryOutcome -AttemptedComponent $Action.Component -ResultCode 'FAILED' -ActionPerformed $Action.Action -ActualFailedComponent $Action.Component)
        }
        return (New-RunningAiRecoveryOutcome -AttemptedComponent $Action.Component -ResultCode 'SUCCESS' -ActionPerformed $Action.Action)
    } finally {
        Exit-RunningAiRuntimeLock $lock
    }
}

# External relay recovery for ONE action: plain tracked-PID PreStop, then its OWN dedicated start
# script (never the full start-running-ai.ps1 - see the module header comment on independence) as a
# trusted child, bundled under one lock hold exactly like the core-chain helper above.
function Invoke-RunningAiRelayRecoveryAction {
    param([Parameter(Mandatory)]$Action, [int]$LockTimeoutSec = 10)

    $lock = Enter-RunningAiRuntimeLock -TimeoutSec $LockTimeoutSec
    if (-not $lock) {
        Write-Step 'Recovery of external-relay deferred: another start/stop against this runtime is already in progress (BUSY). Nothing touched.'
        return (New-RunningAiRecoveryOutcome -AttemptedComponent 'external-relay' -ResultCode 'BUSY' -ActionPerformed $Action.Action)
    }
    try {
        if ($Action.PreStop) {
            $tracked = Get-TrackedProcessId 'external-relay' (Get-ExternalRelayMarkers)
            if ($tracked) { Stop-TrackedProcess -ProcessId $tracked -Markers (Get-ExternalRelayMarkers) -TimeoutSec 15 | Out-Null; Remove-PidFile 'external-relay' }
        }

        $script = Join-Path $PSScriptRoot 'external\start-external-relay.ps1'
        $ps = (Get-Command powershell.exe).Source
        $argLine = "-NoProfile -ExecutionPolicy Bypass -File $(Quote-Argument $script)"
        $spawn = Start-RunningAiWatchdogTrustedChild -FilePath $ps -ArgumentList $argLine -TimeoutMs 60000
        if ($spawn.TimedOut) {
            Write-WatchdogLog -Component 'external-relay' -State 'RECOVERING' -Reason $Action.Reason -Action $Action.Action -Result 'TIMED_OUT'
            return (New-RunningAiRecoveryOutcome -AttemptedComponent 'external-relay' -ResultCode 'TIMED_OUT' -ActionPerformed $Action.Action)
        }
        $exitCode = $spawn.Proc.ExitCode
        if ($exitCode -ne 0) {
            Write-WatchdogLog -Component 'external-relay' -State 'RECOVERING' -Reason $Action.Reason -Action $Action.Action -Result "EXIT_CODE_$exitCode"
            return (New-RunningAiRecoveryOutcome -AttemptedComponent 'external-relay' -ResultCode 'FAILED' -ActionPerformed $Action.Action -ActualFailedComponent 'external-relay')
        }
        return (New-RunningAiRecoveryOutcome -AttemptedComponent 'external-relay' -ResultCode 'SUCCESS' -ActionPerformed $Action.Action)
    } finally {
        Exit-RunningAiRuntimeLock $lock
    }
}

# Observe -> plan -> run ONLY the first planned action -> observe again, so dependency order is
# verified step by step (Docker up before PostgreSQL before connector before Spring) and a failed
# step stops the chain. Every executed action is recorded in the restart history.
# $Runner and $Observe are injectable for tests. Returns the executed steps and the final result.
function Invoke-WatchdogRecovery {
    param(
        [Parameter(Mandatory)][scriptblock]$Observe,
        [Parameter(Mandatory)][scriptblock]$Runner,
        [Parameter(Mandatory)]$History,
        [Parameter(Mandatory)][bool]$HistoryAvailable,
        [Parameter(Mandatory)][datetime]$Now,
        [int]$WindowMinutes = 10,
        [int]$MaxRestarts = 3,
        [int]$MaxSteps = 6,
        $ConnectorPort = $null,
        $LongTermState = $null,
        [int]$LongTermWindowHours = 24,
        [int]$LongTermMaxFailures = 6
    )
    # Phase 6I-1.7B-2B (STEP A-1): $ConnectorPort was previously NEVER passed to Get-ComponentStates
    # here, even though watch-running-ai.ps1's OWN direct calls (before/after this function runs) do
    # pass it - so the connector's ownership-aware classification (Get-ConnectorComponentState) used
    # the legacy FOREIGN_PROCESS-guessing fallback for every decision actually made DURING recovery,
    # while the before/after snapshots used the real ownership model. The whole cycle now shares one
    # consistent policy.
    #
    # Phase 6I-1.7B-2B (STEP E): core-chain and independent (relay) actions are executed in two
    # SEPARATE loops. The single shared loop here previously took only $plan.Actions[0] each
    # iteration and broke entirely on any failure - so a failed connector/Spring action (first in the
    # combined list) could prevent the relay's action (second) from ever being attempted in the same
    # tick, even though Get-RecoveryPlan's own design (see its comments) already computes relay
    # recovery completely independently. The core chain still stops at its first failure (dependency
    # order matters: Docker before Postgres before connector before Spring) - the relay loop's own
    # failure never affects the core chain and vice versa.
    # Phase 6I-1.7B-2C (STEP 1/2): $runResult is normalized through ConvertTo-RunningAiRecoveryOutcome,
    # so a plain boolean (every existing injected test $Runner) and the real, rich
    # Invoke-RecoveryAction outcome are both handled by the SAME logic below, with the plain-boolean
    # case reproducing the exact pre-2C attribution (charge $next.Component on $false) byte for byte.
    #
    # BUSY (the lock is held by a concurrent manual start/stop) is NOT a failure: nothing was
    # attempted, so neither the short-term restart budget (Add-RestartRecord) nor the long-term
    # failure count is charged, and $coreFailed/$relayFailed stay false - only a separate
    # $coreBusy/$relayBusy flag (surfaced as .Busy on the return value) records it. BLOCKED (an
    # explicit refusal to act, e.g. unverified connector ownership) and UNKNOWN_FAILURE (the actual
    # failed component could not be identified) both still stop this tick's loop (rule 5: never report
    # a non-SUCCESS run as if it recovered) but likewise never charge the long-term budget (rules 3/4)
    # - only FAILED/TIMED_OUT do, and only against .BudgetChargeComponent (rules 2/7/9), which is the
    # ACTUAL failed component when the evidence identifies one, never blindly $next.Component.
    #
    # Every Add-RestartRecord/Add-LongTermFailure call this invocation actually makes is ALSO recorded
    # into $deltas (Phase 6I-1.7B-2C, STEP 4): $History/$LongTermState are still mutated in place
    # exactly as before (existing callers that only look at those two objects afterward keep working
    # unchanged), but a caller that must persist against a FRESHLY re-read copy of watchdog-state.json
    # (to avoid clobbering a concurrent clear-watchdog-lockout.ps1 -Force - see watch-running-ai.ps1)
    # can replay just these deltas onto that fresh copy instead of trusting this whole stale snapshot.
    $steps = @(); $coreFailed = $false; $relayFailed = $false; $coreBusy = $false; $relayBusy = $false
    $restartDeltas = @(); $longTermFailureDeltas = @()

    for ($i = 0; $i -lt $MaxSteps; $i++) {
        $states = Get-ComponentStates (& $Observe) -ConnectorPort $ConnectorPort
        $plan = Get-RecoveryPlan -States $states -History $History -Now $Now -HistoryAvailable $HistoryAvailable -WindowMinutes $WindowMinutes -MaxRestarts $MaxRestarts -LongTermState $LongTermState
        $coreActions = @($plan.Actions | Where-Object { $_.Component -in $script:Components })
        if ($coreActions.Count -eq 0) { break }
        $next = $coreActions[0]
        $outcome = ConvertTo-RunningAiRecoveryOutcome -RunResult (& $Runner $next) -AttemptedComponent $next.Component
        $steps += [pscustomobject]@{ Component = $next.Component; Action = $next.Action; Reason = $next.Reason; Result = $outcome.ResultCode }
        if ($outcome.ResultCode -eq 'BUSY') { $coreBusy = $true; break }
        Add-RestartRecord -History $History -Component $next.Component -Now $Now
        $restartDeltas += [pscustomobject]@{ Component = $next.Component; Now = $Now }
        if ($outcome.ResultCode -eq 'SUCCESS') { continue }
        if ($LongTermState -and $outcome.BudgetChargeComponent) {
            Add-LongTermFailure -LongTermState $LongTermState -Component $outcome.BudgetChargeComponent -Now $Now
            Update-RunningAiLongTermLockout -LongTermState $LongTermState -Component $outcome.BudgetChargeComponent -Now $Now -WindowHours $LongTermWindowHours -MaxFailures $LongTermMaxFailures
            $longTermFailureDeltas += [pscustomobject]@{ Component = $outcome.BudgetChargeComponent; Now = $Now }
        }
        $coreFailed = $true; break
    }

    for ($i = 0; $i -lt $MaxSteps; $i++) {
        $states = Get-ComponentStates (& $Observe) -ConnectorPort $ConnectorPort
        $plan = Get-RecoveryPlan -States $states -History $History -Now $Now -HistoryAvailable $HistoryAvailable -WindowMinutes $WindowMinutes -MaxRestarts $MaxRestarts -LongTermState $LongTermState
        $independentActions = @($plan.Actions | Where-Object { $_.Component -in $script:IndependentComponents })
        if ($independentActions.Count -eq 0) { break }
        $next = $independentActions[0]
        $outcome = ConvertTo-RunningAiRecoveryOutcome -RunResult (& $Runner $next) -AttemptedComponent $next.Component
        $steps += [pscustomobject]@{ Component = $next.Component; Action = $next.Action; Reason = $next.Reason; Result = $outcome.ResultCode }
        if ($outcome.ResultCode -eq 'BUSY') { $relayBusy = $true; break }
        Add-RestartRecord -History $History -Component $next.Component -Now $Now
        $restartDeltas += [pscustomobject]@{ Component = $next.Component; Now = $Now }
        if ($outcome.ResultCode -eq 'SUCCESS') { continue }
        if ($LongTermState -and $outcome.BudgetChargeComponent) {
            Add-LongTermFailure -LongTermState $LongTermState -Component $outcome.BudgetChargeComponent -Now $Now
            Update-RunningAiLongTermLockout -LongTermState $LongTermState -Component $outcome.BudgetChargeComponent -Now $Now -WindowHours $LongTermWindowHours -MaxFailures $LongTermMaxFailures
            $longTermFailureDeltas += [pscustomobject]@{ Component = $outcome.BudgetChargeComponent; Now = $Now }
        }
        $relayFailed = $true; break
    }

    [pscustomobject]@{
        Steps  = $steps
        Failed = ($coreFailed -or $relayFailed)
        Busy   = ($coreBusy -or $relayBusy)
        Deltas = [pscustomobject]@{ Restarts = $restartDeltas; LongTermFailures = $longTermFailureDeltas }
    }
}

# Phase 6I-1.7B-2C (STEP 4): the ONLY safe way to persist a Watchdog tick's own restart/long-term-
# failure deltas (see Invoke-WatchdogRecovery's ".Deltas" above) without a lost-update race against a
# concurrent clear-watchdog-lockout.ps1 -Force: acquire the state-file lock, re-read the CURRENT
# on-disk state fresh (never trust the in-memory copy taken at tick start, which may already be stale
# by the time a possibly minutes-long tick finishes), re-apply the SAME window pruning the tick itself
# applied at its start (so the fresh base is consistent with $Now/$WindowMinutes/$LongTermWindowHours),
# replay only this tick's OWN deltas onto that fresh base, and save once. If the fresh read itself is
# unavailable (corrupt/missing-with-backup - Read-WatchdogState's own fail-safe cases), nothing is
# written at all rather than guessing: this tick's deltas are lost for the state file (though still
# visible in this run's own log/status output), which is the safe trade-off per requirement 6 ("손상된
# 상태 파일은 자동 복구하지 않고 Fail-safe") - never fabricate a merge target. A lock acquisition
# failure (another writer will not finish in time) is reported the same way: nothing written, loudly.
function Save-RunningAiWatchdogStateReconciled {
    param(
        [Parameter(Mandatory)]$Deltas,
        [Parameter(Mandatory)][datetime]$Now,
        [int]$WindowMinutes = 10,
        [int]$LongTermWindowHours = 24,
        [int]$LongTermMaxFailures = 6,
        [string]$Path = $script:StatePath,
        [int]$LockTimeoutSec = 10
    )
    $lock = Enter-RunningAiWatchdogStateLock -TimeoutSec $LockTimeoutSec
    if (-not $lock) {
        Write-Step 'WARNING: could not acquire the watchdog-state.json write lock within the timeout; this tick''s restart/long-term-failure accounting was NOT persisted (state file left exactly as it was).'
        return $false
    }
    try {
        $fresh = Read-WatchdogState -Path $Path
        if (-not $fresh.Available) {
            Write-Step "WARNING: watchdog-state.json could not be re-read before saving ($($fresh.Note)); this tick's restart/long-term-failure accounting was NOT persisted."
            return $false
        }
        Update-HistoryWindow -History $fresh.History -Now $Now -WindowMinutes $WindowMinutes
        Update-LongTermFailureWindow -LongTermState $fresh.LongTermState -Now $Now -WindowHours $LongTermWindowHours
        foreach ($d in @($Deltas.Restarts)) { Add-RestartRecord -History $fresh.History -Component $d.Component -Now $d.Now }
        foreach ($d in @($Deltas.LongTermFailures)) {
            Add-LongTermFailure -LongTermState $fresh.LongTermState -Component $d.Component -Now $d.Now
            Update-RunningAiLongTermLockout -LongTermState $fresh.LongTermState -Component $d.Component -Now $d.Now -WindowHours $LongTermWindowHours -MaxFailures $LongTermMaxFailures
        }
        Save-WatchdogState -History $fresh.History -LongTermState $fresh.LongTermState -Path $Path
        return $true
    } finally {
        Exit-RunningAiWatchdogStateLock $lock
    }
}

# ---- scheduled task definition (built in memory; registering is the installer's job) ------------

function New-WatchdogTaskParts {
    param([string]$User = [System.Security.Principal.WindowsIdentity]::GetCurrent().Name, [int]$IntervalMinutes = 5, [int]$InitialDelayMinutes = 5)
    $script = Join-Path $PSScriptRoot 'watch-running-ai.ps1'
    $ps = (Get-Command powershell.exe).Source
    $arguments = "-NoProfile -ExecutionPolicy Bypass -WindowStyle Hidden -File $(Quote-Argument $script)"
    $action = New-ScheduledTaskAction -Execute $ps -Argument $arguments -WorkingDirectory (Get-RepoRoot)
    $trigger = New-ScheduledTaskTrigger -AtLogOn -User $User
    $repeat = (New-ScheduledTaskTrigger -Once -At (Get-Date) -RepetitionInterval (New-TimeSpan -Minutes $IntervalMinutes)).Repetition
    $trigger.Repetition = $repeat
    $trigger.Delay = 'PT{0}M' -f $InitialDelayMinutes          # give RunningAI-Startup time to finish
    $principal = New-ScheduledTaskPrincipal -UserId $User -LogonType Interactive -RunLevel Limited
    $settings = New-ScheduledTaskSettingsSet -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries `
        -MultipleInstances IgnoreNew -ExecutionTimeLimit (New-TimeSpan -Minutes 30) -StartWhenAvailable
    [pscustomobject]@{ Action = $action; Trigger = $trigger; Principal = $principal; Settings = $settings; Script = $script; Arguments = $arguments; Execute = $ps; User = $User }
}
