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

$script:Components = @('docker', 'postgres', 'connector', 'spring')   # dependency order
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

function New-EmptyHistory { $h = @{}; foreach ($c in $script:Components) { $h[$c] = @() }; return $h }

# Returns @{ Available; History; Note }. A missing file is a normal first run. A malformed file is
# quarantined (renamed *.corrupt) and reported as NOT available: for that tick the watchdog must
# not restart anything, because without restart history it cannot enforce the budget.
function Read-WatchdogState {
    param([string]$Path = $script:StatePath)
    if (-not (Test-Path -LiteralPath $Path)) { return @{ Available = $true; History = (New-EmptyHistory); Note = 'no state file (first run)' } }
    try {
        $raw = Get-Content -LiteralPath $Path -Raw -ErrorAction Stop
        $obj = ConvertFrom-Json $raw -ErrorAction Stop
        if ($null -eq $obj -or $null -eq $obj.restarts) { throw 'restarts missing' }
        $history = New-EmptyHistory
        foreach ($c in $script:Components) {
            $values = $obj.restarts.$c
            if ($null -ne $values) {
                $list = @()
                foreach ($v in @($values)) { $list += [long]$v }
                $history[$c] = $list
            }
        }
        return @{ Available = $true; History = $history; Note = 'ok' }
    } catch {
        $corrupt = "$Path.corrupt"
        try { Move-Item -LiteralPath $Path -Destination $corrupt -Force -ErrorAction Stop } catch { }
        return @{ Available = $false; History = (New-EmptyHistory); Note = "state file unreadable, quarantined as $(Split-Path $corrupt -Leaf)" }
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
    foreach ($c in $script:Components) { $History[$c] = @(@($History[$c]) | Where-Object { $_ -ge $since }) }
}

function Add-RestartRecord {
    param([Parameter(Mandatory)]$History, [Parameter(Mandatory)][string]$Component, [Parameter(Mandatory)][datetime]$Now)
    $History[$Component] = @(@($History[$Component]) + (ConvertTo-UnixSeconds $Now))
}

function Save-WatchdogState {
    param([Parameter(Mandatory)]$History, [string]$Path = $script:StatePath)
    $restarts = [ordered]@{}
    foreach ($c in $script:Components) { $restarts[$c] = @($History[$c]) }
    Write-JsonAtomic -Path $Path -Object ([ordered]@{ version = 1; restarts = $restarts })
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
    param([int]$ConnectorPort, [int]$SpringPort)
    $compose = Join-Path (Get-RepoRoot) 'docker-compose.yml'
    @{
        Docker = {
            if (-not (Get-Command docker -ErrorAction SilentlyContinue)) { return 'NO_CLI' }
            if ((Invoke-NativeQuiet 'docker' 'info') -eq 0) { 'UP' } else { 'DOWN' }
        }
        Postgres = {
            $cid = (Invoke-NativeText 'docker' "compose -f $(Quote-Argument $compose) ps -a -q postgres").Trim()
            if (-not $cid) { return 'stopped' }
            $text = (Invoke-NativeText 'docker' "inspect -f {{.State.Running}}/{{.State.Health.Status}} $cid").Trim()
            if ($text -notmatch '^(true|false)/(\w*)') { return 'unknown' }
            if ($Matches[1] -eq 'false') { return 'stopped' }
            switch ($Matches[2]) { 'healthy' { 'healthy' } 'unhealthy' { 'unhealthy' } 'starting' { 'starting' } default { 'unknown' } }
        }.GetNewClosure()
        ConnectorHealth   = { Test-ConnectorHealth $ConnectorPort }.GetNewClosure()
        ConnectorPortUsed = { Test-PortInUse $ConnectorPort }.GetNewClosure()
        ConnectorPid      = { Get-TrackedProcessId 'garmin-connector' (Get-ConnectorMarkers) }
        SpringHealth      = { Test-SpringHealth $SpringPort }.GetNewClosure()
        SpringPortUsed    = { Test-PortInUse $SpringPort }.GetNewClosure()
        SpringPid         = { Get-TrackedProcessId 'spring' (Get-SpringMarkers) }
    }
}

# Collects raw facts. When a managed process is alive but its health check fails, the check is
# repeated once after $RecheckDelaySec so a momentary hiccup is not treated as a persistent failure.
function Get-RuntimeObservation {
    param([int]$ConnectorPort = 8765, [int]$SpringPort = 8080, [int]$RecheckDelaySec = 10, [hashtable]$Probes)
    $p = Get-DefaultProbes -ConnectorPort $ConnectorPort -SpringPort $SpringPort
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

    [pscustomobject]@{
        Docker = $docker; Postgres = $postgres
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

function Get-ComponentStates {
    param([Parameter(Mandatory)]$Observation)
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
    $states['connector'] = Get-ProcessComponentState $o.ConnectorHealth $o.ConnectorPortUsed $o.ConnectorPid
    $states['spring']    = Get-ProcessComponentState $o.SpringHealth $o.SpringPortUsed $o.SpringPid
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
        [int]$MaxRestarts = 3
    )
    $actions = New-Object System.Collections.ArrayList
    $blocked = New-Object System.Collections.ArrayList
    $ready = $true     # true while every upstream component is up or has a recovery action planned

    foreach ($c in $script:Components) {
        $s = $States[$c]
        if ($s.State -eq 'UP') { continue }
        if ($s.State -eq 'UNKNOWN' -and $s.Reason -eq 'DEPENDENCY_DOCKER') { continue }   # judged after Docker is recovered

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
    [pscustomobject]@{ Actions = @($actions); Blocked = @($blocked) }
}

# UP | DEGRADED | DOWN from the final states, blocked reasons and the optional Garmin hint.
function Get-OverallState {
    param([Parameter(Mandatory)]$States, $Blocked = @(), [string]$GarminHint)
    $values = @($script:Components | ForEach-Object { $States[$_].State })
    if (@($values | Where-Object { $_ -eq 'DOWN' }).Count -gt 0) { return 'DOWN' }
    if (@($values | Where-Object { $_ -ne 'UP' }).Count -gt 0 -or @($Blocked).Count -gt 0 -or $GarminHint) { return 'DEGRADED' }
    return 'UP'
}

# ---- execution -----------------------------------------------------------------------------

# Default action runner: reuses the idempotent start script (it only starts what is down). Unhealthy
# managed processes are stopped gracefully first, and only when the PID file proves they are ours.
function Invoke-RecoveryAction {
    param([Parameter(Mandatory)]$Action, [int]$ConnectorPort = 8765, [int]$SpringPort = 8080)
    if ($Action.PreStop) {
        if ($Action.Component -eq 'connector') {
            $tracked = Get-TrackedProcessId 'garmin-connector' (Get-ConnectorMarkers)
            if ($tracked) { Stop-TrackedProcess -ProcessId $tracked -Markers (Get-ConnectorMarkers) -TimeoutSec 15 | Out-Null; Remove-PidFile 'garmin-connector' }
        } else {
            $tracked = Get-TrackedProcessId 'spring' (Get-SpringMarkers)
            if ($tracked) { Stop-TrackedProcess -ProcessId $tracked -Markers (Get-SpringMarkers) -TimeoutSec 30 | Out-Null; Remove-PidFile 'spring' }
        }
    }
    $script = Join-Path $PSScriptRoot 'start-running-ai.ps1'
    $ps = (Get-Command powershell.exe).Source
    $argLine = "-NoProfile -ExecutionPolicy Bypass -File $(Quote-Argument $script) -ConnectorPort $ConnectorPort -SpringPort $SpringPort"
    $proc = Start-Process -FilePath $ps -ArgumentList $argLine -WindowStyle Hidden -PassThru
    if (-not $proc.WaitForExit(900000)) { Stop-Process -Id $proc.Id -Force -ErrorAction SilentlyContinue; return $false }
    return ($proc.ExitCode -eq 0)
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
        [int]$MaxSteps = 6
    )
    $steps = @(); $failed = $false
    for ($i = 0; $i -lt $MaxSteps; $i++) {
        $states = Get-ComponentStates (& $Observe)
        $plan = Get-RecoveryPlan -States $states -History $History -Now $Now -HistoryAvailable $HistoryAvailable -WindowMinutes $WindowMinutes -MaxRestarts $MaxRestarts
        if ($plan.Actions.Count -eq 0) { break }
        $next = $plan.Actions[0]
        Add-RestartRecord -History $History -Component $next.Component -Now $Now
        $ok = [bool](& $Runner $next)
        $steps += [pscustomobject]@{ Component = $next.Component; Action = $next.Action; Reason = $next.Reason; Result = $(if ($ok) { 'SUCCESS' } else { 'FAILED' }) }
        if (-not $ok) { $failed = $true; break }
    }
    [pscustomobject]@{ Steps = $steps; Failed = $failed }
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
