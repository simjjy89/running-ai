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
        return @{ Available = $false; History = (New-EmptyHistory); LongTermState = (New-EmptyLongTermState); Note = "state file unreadable, quarantined as $(Split-Path $corrupt -Leaf)" }
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
function Save-WatchdogState {
    param([Parameter(Mandatory)]$History, $LongTermState = (New-EmptyLongTermState), [string]$Path = $script:StatePath)
    $restarts = [ordered]@{}
    foreach ($c in $script:AllTrackedComponents) { $restarts[$c] = @($History[$c]) }
    $longTerm = [ordered]@{}
    foreach ($c in $script:AllTrackedComponents) {
        $longTerm[$c] = [ordered]@{ failures = @($LongTermState[$c].Failures); lockedOutSince = $LongTermState[$c].LockedOutSince }
    }
    Write-JsonAtomic -Path $Path -Object ([ordered]@{ version = 2; restarts = $restarts; longTerm = $longTerm })
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

# Default action runner: reuses the idempotent start script (it only starts what is down). Unhealthy
# managed processes are stopped gracefully first, and only when the PID file proves they are ours.
# The external relay is a deliberate exception: it is started via its OWN dedicated script
# (external\start-external-relay.ps1), never the full start-running-ai.ps1 - calling the full
# orchestrator to recover just the relay would make a genuine Docker/PostgreSQL/connector/Spring
# failure incorrectly block/fail relay recovery, which is exactly the coupling Phase 6I-1.1
# requires NOT exist.
function Invoke-RecoveryAction {
    param([Parameter(Mandatory)]$Action, [int]$ConnectorPort = 8765, [int]$SpringPort = 8080)
    if ($Action.PreStop) {
        if ($Action.Component -eq 'connector') {
            # Phase 6I-1.7B-1: ownership-aware stop, not the plain tracked-PID stop. This is the path
            # that reaches an ORPHANED_MANAGED_PROCESS (tracked launcher already gone, its listener
            # child still holding the port) - Get-TrackedProcessId alone would see nothing to stop and
            # silently leave the port occupied, so the subsequent restart's bind would fail.
            $tracked = Read-PidFile 'garmin-connector'
            $ownership = Get-RunningAiConnectorOwnership -Port $ConnectorPort -TrackedPid $tracked -Markers (Get-ConnectorMarkers)
            # Phase 6I-1.7B-2A: explicit per-verdict gate, not just "ManagedPids.Count -gt 0" - that
            # condition is ALSO true for... nothing, actually: ManagedPids is empty for every
            # non-manageable verdict by construction. The real 1.7B-1R gap was that when it WAS empty
            # (DOWN, FOREIGN_PROCESS, UNKNOWN_OWNER, or a query error folded into UNKNOWN_OWNER), the
            # old code simply skipped this whole block with no action and fell straight through to the
            # start-running-ai.ps1 launch below - correct for DOWN (nothing to stop, safe to start),
            # silently wrong for every other case (restarting a connector whose port ownership was
            # never verified). Each case is now explicit.
            switch ($ownership.Verdict) {
                'DOWN' {
                    # Confirmed nothing is listening - safe to fall through to the start attempt below.
                }
                { $_ -in @('SELF_OWNED', 'LAUNCHER_CHILD', 'ORPHANED_MANAGED_PROCESS', 'LAUNCHER_ALIVE_NO_LISTENER') } {
                    if (@($ownership.ManagedPids).Count -eq 0) {
                        # Should not happen given the verdict, but fail-safe regardless of why.
                        Write-Step "Garmin connector pre-stop blocked: verdict $($ownership.Verdict) reported no manageable PIDs; refusing to restart."
                        return $false
                    }
                    $stopResult = Stop-RunningAiConnectorManaged -Ownership $ownership -Port $ConnectorPort -TimeoutSec 15
                    $cleanStop = Test-RunningAiConnectorStopWasClean -StopResult $stopResult
                    if ($cleanStop) {
                        Remove-PidFile 'garmin-connector'
                    } else {
                        # A failed/partial pre-stop must never be followed by starting a new connector
                        # anyway - the port may still be occupied by what we just failed to clear. PID
                        # file and metadata are preserved (not touched above) so the next tick sees the
                        # real state; this tick's recovery step is reported as failed, same as any
                        # other failed action, rather than silently launching start-running-ai.ps1
                        # straight into a bind-in-use failure.
                        Write-Step "Garmin connector pre-stop did not fully succeed (result=$($stopResult.Result), resultCode=$($stopResult.ResultCode), portFreed=$($stopResult.PortFreed), remaining=$($stopResult.RemainingPids -join ',')); not starting a new connector this tick."
                        return $false
                    }
                }
                default {
                    # FOREIGN_PROCESS, UNKNOWN_OWNER, or any future verdict not explicitly handled
                    # above - ownership of whatever is on the port could not be verified. Block the
                    # restart entirely rather than risk starting a second connector alongside (or
                    # instead of stopping) a process we cannot positively identify.
                    Write-Step "Garmin connector pre-stop blocked: ownership verdict is $($ownership.Verdict) ($($ownership.Detail)); refusing to restart an unverified process on port $ConnectorPort."
                    return $false
                }
            }
        } elseif ($Action.Component -eq 'external-relay') {
            $tracked = Get-TrackedProcessId 'external-relay' (Get-ExternalRelayMarkers)
            if ($tracked) { Stop-TrackedProcess -ProcessId $tracked -Markers (Get-ExternalRelayMarkers) -TimeoutSec 15 | Out-Null; Remove-PidFile 'external-relay' }
        } else {
            $tracked = Get-TrackedProcessId 'spring' (Get-SpringMarkers)
            if ($tracked) { Stop-TrackedProcess -ProcessId $tracked -Markers (Get-SpringMarkers) -TimeoutSec 30 | Out-Null; Remove-PidFile 'spring' }
        }
    }

    if ($Action.Component -eq 'external-relay') {
        $script = Join-Path $PSScriptRoot 'external\start-external-relay.ps1'
        $ps = (Get-Command powershell.exe).Source
        $argLine = "-NoProfile -ExecutionPolicy Bypass -File $(Quote-Argument $script)"
        $proc = Start-Process -FilePath $ps -ArgumentList $argLine -WindowStyle Hidden -PassThru
        if (-not $proc.WaitForExit(60000)) { Stop-Process -Id $proc.Id -Force -ErrorAction SilentlyContinue; return $false }
        if ($proc.ExitCode -ne 0) { Write-WatchdogLog -Component $Action.Component -State 'RECOVERING' -Reason $Action.Reason -Action $Action.Action -Result "EXIT_CODE_$($proc.ExitCode)" }
        return ($proc.ExitCode -eq 0)
    }

    # Phase 6I-1.7B-2B (STEP B): start-running-ai.ps1 is a single shared script covering Docker ->
    # PostgreSQL -> connector -> Spring, each with its OWN dedicated exit code (see RunningAI.Common.ps1
    # $ExitCode) - it does NOT report "connector failed" vs "Spring failed" as one undifferentiated
    # failure. Previously only ($proc.ExitCode -eq 0) was ever read, so a RESTART_CONNECTOR action that
    # failed because of an unrelated Spring problem (a real-world possibility: the idempotent script
    # tries every unhealthy layer, not just the one this action targeted) would get silently recorded
    # as a connector failure - the real cause mislabeled. The actual failing layer is now logged
    # explicitly via the exit code, distinct from which component the ATTEMPT (and its restart-budget
    # consumption, unchanged - see Invoke-WatchdogRecovery) was charged to. A code this mapping does not
    # recognize is logged as UNKNOWN_FAILURE rather than guessed.
    $script = Join-Path $PSScriptRoot 'start-running-ai.ps1'
    $ps = (Get-Command powershell.exe).Source
    $argLine = "-NoProfile -ExecutionPolicy Bypass -File $(Quote-Argument $script) -ConnectorPort $ConnectorPort -SpringPort $SpringPort"
    $proc = Start-Process -FilePath $ps -ArgumentList $argLine -WindowStyle Hidden -PassThru
    if (-not $proc.WaitForExit(900000)) {
        Stop-Process -Id $proc.Id -Force -ErrorAction SilentlyContinue
        Write-WatchdogLog -Component $Action.Component -State 'RECOVERING' -Reason $Action.Reason -Action $Action.Action -Result 'TIMED_OUT'
        return $false
    }
    if ($proc.ExitCode -ne 0) {
        $actualComponent = Get-RunningAiComponentFromExitCode -ExitCode $proc.ExitCode
        $actualLabel = if ($actualComponent) { $actualComponent.ToUpper() } else { 'UNKNOWN_FAILURE' }
        if ($actualComponent -and $actualComponent -ne $Action.Component) {
            Write-WatchdogLog -Component $Action.Component -State 'RECOVERING' -Reason $Action.Reason -Action $Action.Action -Result "FAILED_ACTUAL_COMPONENT_$($actualLabel)_EXIT_$($proc.ExitCode)"
        } else {
            Write-WatchdogLog -Component $Action.Component -State 'RECOVERING' -Reason $Action.Reason -Action $Action.Action -Result "FAILED_EXIT_$($proc.ExitCode)"
        }
    }
    return ($proc.ExitCode -eq 0)
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
    $steps = @(); $coreFailed = $false; $relayFailed = $false

    for ($i = 0; $i -lt $MaxSteps; $i++) {
        $states = Get-ComponentStates (& $Observe) -ConnectorPort $ConnectorPort
        $plan = Get-RecoveryPlan -States $states -History $History -Now $Now -HistoryAvailable $HistoryAvailable -WindowMinutes $WindowMinutes -MaxRestarts $MaxRestarts -LongTermState $LongTermState
        $coreActions = @($plan.Actions | Where-Object { $_.Component -in $script:Components })
        if ($coreActions.Count -eq 0) { break }
        $next = $coreActions[0]
        Add-RestartRecord -History $History -Component $next.Component -Now $Now
        $runResult = & $Runner $next
        $ok = [bool]$runResult
        $steps += [pscustomobject]@{ Component = $next.Component; Action = $next.Action; Reason = $next.Reason; Result = $(if ($ok) { 'SUCCESS' } else { 'FAILED' }) }
        if (-not $ok) {
            # Phase 6I-1.7B-2B (STEP C): a FAILURE (not merely an attempt) counts toward the long-term
            # lockout - recorded regardless of whether $LongTermState was supplied (a $null one is
            # simply discarded by these functions' own no-op-on-null guards... actually both REQUIRE a
            # non-null state, so only record when one was actually given, preserving every existing
            # caller that does not pass this parameter at all).
            if ($LongTermState) {
                Add-LongTermFailure -LongTermState $LongTermState -Component $next.Component -Now $Now
                Update-RunningAiLongTermLockout -LongTermState $LongTermState -Component $next.Component -Now $Now -WindowHours $LongTermWindowHours -MaxFailures $LongTermMaxFailures
            }
            $coreFailed = $true; break
        }
    }

    for ($i = 0; $i -lt $MaxSteps; $i++) {
        $states = Get-ComponentStates (& $Observe) -ConnectorPort $ConnectorPort
        $plan = Get-RecoveryPlan -States $states -History $History -Now $Now -HistoryAvailable $HistoryAvailable -WindowMinutes $WindowMinutes -MaxRestarts $MaxRestarts -LongTermState $LongTermState
        $independentActions = @($plan.Actions | Where-Object { $_.Component -in $script:IndependentComponents })
        if ($independentActions.Count -eq 0) { break }
        $next = $independentActions[0]
        Add-RestartRecord -History $History -Component $next.Component -Now $Now
        $ok = [bool](& $Runner $next)
        $steps += [pscustomobject]@{ Component = $next.Component; Action = $next.Action; Reason = $next.Reason; Result = $(if ($ok) { 'SUCCESS' } else { 'FAILED' }) }
        if (-not $ok) {
            if ($LongTermState) {
                Add-LongTermFailure -LongTermState $LongTermState -Component $next.Component -Now $Now
                Update-RunningAiLongTermLockout -LongTermState $LongTermState -Component $next.Component -Now $Now -WindowHours $LongTermWindowHours -MaxFailures $LongTermMaxFailures
            }
            $relayFailed = $true; break
        }
    }

    [pscustomobject]@{ Steps = $steps; Failed = ($coreFailed -or $relayFailed) }
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
