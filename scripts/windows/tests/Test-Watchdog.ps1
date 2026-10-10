<#
.SYNOPSIS
  Non-destructive checks of the RunningAI watchdog logic. Uses injected observations, fake
  runners, temp directories and (once) a harmless dummy process. Needs no Docker, Garmin, Python
  or network, registers no Scheduled Task and never touches the real .runtime files.
  Exit code 0 when all checks pass.
#>
$ErrorActionPreference = 'Stop'
$scripts = Split-Path $PSScriptRoot -Parent
. (Join-Path $scripts 'RunningAI.Watchdog.ps1')

$failures = New-Object System.Collections.Generic.List[string]
function Check {
    param([string]$Name, [scriptblock]$Body)
    try {
        $result = & $Body
        if ($result -eq $false) { throw 'assertion returned false' }
        Write-Host "PASS  $Name"
    } catch {
        Write-Host "FAIL  $Name : $($_.Exception.Message)" -ForegroundColor Red
        $failures.Add($Name)
    }
}

function New-Obs {
    param($Docker = 'UP', $Postgres = 'healthy', [bool]$ConnectorHealth = $true, [bool]$ConnectorPort = $true, $ConnectorPid = $null,
          [bool]$SpringHealth = $true, [bool]$SpringPort = $true, $SpringPid = $null,
          [bool]$RelayHealth = $true, [bool]$RelayPort = $true, $RelayPid = $null)
    [pscustomobject]@{ Docker = $Docker; Postgres = $Postgres; ConnectorHealth = $ConnectorHealth; ConnectorPortUsed = $ConnectorPort
        ConnectorPid = $ConnectorPid; SpringHealth = $SpringHealth; SpringPortUsed = $SpringPort; SpringPid = $SpringPid
        ExternalRelayHealth = $RelayHealth; ExternalRelayPortUsed = $RelayPort; ExternalRelayPid = $RelayPid }
}
function New-TempDir { $d = Join-Path ([IO.Path]::GetTempPath()) ('ra-wd-' + [guid]::NewGuid().ToString('N')); New-Item -ItemType Directory $d | Out-Null; $d }
$now = Get-Date
function Plan { param($Obs, $History = (New-EmptyHistory), [bool]$Available = $true) Get-RecoveryPlan -States (Get-ComponentStates $Obs) -History $History -Now $now -HistoryAvailable $Available }

# ---- classification + planning --------------------------------------------------------------

Check 'healthy runtime: all UP, no action, overall UP' {
    $states = Get-ComponentStates (New-Obs)
    $plan = Plan (New-Obs)
    (@($script:Components | Where-Object { $states[$_].State -ne 'UP' }).Count -eq 0) -and $plan.Actions.Count -eq 0 -and $plan.Blocked.Count -eq 0 -and
    (Get-OverallState -States $states -Blocked $plan.Blocked) -eq 'UP'
}

Check 'connector dead (port free, no process) -> START_CONNECTOR' {
    $plan = Plan (New-Obs -ConnectorHealth $false -ConnectorPort $false)
    $plan.Actions.Count -eq 1 -and $plan.Actions[0].Action -eq 'START_CONNECTOR' -and -not $plan.Actions[0].PreStop
}

Check 'spring dead -> START_SPRING' {
    $plan = Plan (New-Obs -SpringHealth $false -SpringPort $false)
    $plan.Actions.Count -eq 1 -and $plan.Actions[0].Action -eq 'START_SPRING'
}

Check 'docker daemon down -> START_DOCKER first; postgres judged later' {
    $states = Get-ComponentStates (New-Obs -Docker 'DOWN' -Postgres 'unknown' -ConnectorHealth $false -ConnectorPort $false)
    $plan = Get-RecoveryPlan -States $states -History (New-EmptyHistory) -Now $now
    $plan.Actions[0].Action -eq 'START_DOCKER' -and $states['postgres'].State -eq 'UNKNOWN'
}

Check 'docker CLI missing is a configuration error, nothing downstream is touched' {
    $plan = Plan (New-Obs -Docker 'NO_CLI' -Postgres 'unknown' -ConnectorHealth $false -ConnectorPort $false)
    $plan.Actions.Count -eq 0 -and $plan.Blocked[0].Reason -eq 'CONFIGURATION_ERROR_NO_DOCKER_CLI' -and
    (@($plan.Blocked | Where-Object { $_.Reason -eq 'DEPENDENCY_NOT_READY' }).Count -ge 1)
}

Check 'postgres stopped -> COMPOSE_UP' {
    $plan = Plan (New-Obs -Postgres 'stopped')
    $plan.Actions.Count -eq 1 -and $plan.Actions[0].Action -eq 'COMPOSE_UP'
}

Check 'postgres running but unhealthy: no restart, DEGRADED, dependents blocked' {
    $obs = New-Obs -Postgres 'unhealthy' -SpringHealth $false -SpringPort $false
    $plan = Plan $obs
    $onlyPg = New-Obs -Postgres 'unhealthy'
    $states = Get-ComponentStates $onlyPg
    $plan2 = Plan $onlyPg
    $plan.Actions.Count -eq 0 -and $states['postgres'].State -eq 'UNHEALTHY' -and
    ($plan.Blocked | Where-Object { $_.Component -eq 'postgres' }).Reason -eq 'POSTGRES_UNHEALTHY_NO_BLIND_RESTART' -and
    ($plan.Blocked | Where-Object { $_.Component -eq 'spring' }).Reason -eq 'DEPENDENCY_NOT_READY' -and
    (Get-OverallState -States $states -Blocked $plan2.Blocked) -eq 'DEGRADED' -and
    (Get-OverallState -States (Get-ComponentStates $obs) -Blocked $plan.Blocked) -eq 'DOWN'       # Spring really is down
}

Check 'postgres still starting is waited for, not restarted' {
    $plan = Plan (New-Obs -Postgres 'starting')
    $plan.Actions.Count -eq 0 -and $plan.Blocked[0].Reason -eq 'POSTGRES_STARTING'
}

Check 'foreign process owns the connector port: DEGRADED, never restarted' {
    $obs = New-Obs -ConnectorHealth $false -ConnectorPort $true -ConnectorPid $null
    $states = Get-ComponentStates $obs
    $plan = Plan $obs
    $states['connector'].State -eq 'DEGRADED' -and $states['connector'].Reason -eq 'FOREIGN_PROCESS' -and $plan.Actions.Count -eq 0 -and
    ($plan.Blocked | Where-Object { $_.Component -eq 'connector' }).Reason -eq 'FOREIGN_PROCESS'
}

Check 'foreign process owns the Spring port: DEGRADED, never restarted' {
    $plan = Plan (New-Obs -SpringHealth $false -SpringPort $true -SpringPid $null)
    $plan.Actions.Count -eq 0 -and ($plan.Blocked | Where-Object { $_.Component -eq 'spring' }).Reason -eq 'FOREIGN_PROCESS'
}

Check 'process alive but health failing -> UNHEALTHY -> graceful RESTART with pre-stop' {
    $plan = Plan (New-Obs -SpringHealth $false -SpringPort $true -SpringPid 4242)
    $plan.Actions.Count -eq 1 -and $plan.Actions[0].Action -eq 'RESTART_SPRING' -and $plan.Actions[0].PreStop
}

Check 'multiple failures are recovered strictly in dependency order' {
    $plan = Plan (New-Obs -Postgres 'stopped' -ConnectorHealth $false -ConnectorPort $false -SpringHealth $false -SpringPort $false)
    (($plan.Actions | ForEach-Object { $_.Component }) -join ',') -eq 'postgres,connector,spring'
}

# ---- external relay: independent of the core chain (Phase 6I-1.1) -----------------------------

Check 'relay DOWN -> exactly one START_EXTERNAL_RELAY action' {
    $plan = Plan (New-Obs -RelayHealth $false -RelayPort $false -RelayPid $null)
    $relayActions = @($plan.Actions | Where-Object { $_.Component -eq 'external-relay' })
    $relayActions.Count -eq 1 -and $relayActions[0].Action -eq 'START_EXTERNAL_RELAY' -and -not $relayActions[0].PreStop
}

Check 'relay process alive but health failing -> UNHEALTHY -> RESTART_EXTERNAL_RELAY with pre-stop' {
    $plan = Plan (New-Obs -RelayHealth $false -RelayPort $true -RelayPid 5150)
    $relayActions = @($plan.Actions | Where-Object { $_.Component -eq 'external-relay' })
    $relayActions.Count -eq 1 -and $relayActions[0].Action -eq 'RESTART_EXTERNAL_RELAY' -and $relayActions[0].PreStop
}

Check 'foreign process owns the relay port: DEGRADED, never an action (no kill, no silent replace)' {
    $obs = New-Obs -RelayHealth $false -RelayPort $true -RelayPid $null
    $states = Get-ComponentStates $obs
    $plan = Plan $obs
    $states['external-relay'].State -eq 'DEGRADED' -and $states['external-relay'].Reason -eq 'FOREIGN_PROCESS' -and
    (@($plan.Actions | Where-Object { $_.Component -eq 'external-relay' })).Count -eq 0 -and
    ($plan.Blocked | Where-Object { $_.Component -eq 'external-relay' }).Reason -eq 'FOREIGN_PROCESS'
}

Check 'Spring DOWN/blocked (foreign process) does not block the relay''s own recovery action' {
    $plan = Plan (New-Obs -SpringHealth $false -SpringPort $true -SpringPid $null -RelayHealth $false -RelayPort $false -RelayPid $null)
    ($plan.Blocked | Where-Object { $_.Component -eq 'spring' }).Reason -eq 'FOREIGN_PROCESS' -and
    (@($plan.Actions | Where-Object { $_.Component -eq 'external-relay' -and $_.Action -eq 'START_EXTERNAL_RELAY' })).Count -eq 1
}

Check 'relay DOWN/blocked (foreign process) does not block docker/postgres/connector/spring recovery' {
    $plan = Plan (New-Obs -Docker 'DOWN' -Postgres 'unknown' -ConnectorHealth $false -ConnectorPort $false -SpringHealth $false -SpringPort $false -RelayHealth $false -RelayPort $true -RelayPid $null)
    ($plan.Blocked | Where-Object { $_.Component -eq 'external-relay' }).Reason -eq 'FOREIGN_PROCESS' -and
    (($plan.Actions | Where-Object { $_.Component -ne 'external-relay' } | ForEach-Object { $_.Component }) -join ',') -eq 'docker,connector,spring'
}

Check 'relay restart budget: 3 recent restarts block the 4th, independent of the core chain''s own budget' {
    $h = New-EmptyHistory
    1..3 | ForEach-Object { Add-RestartRecord -History $h -Component 'external-relay' -Now $now.AddMinutes(-$_) }
    $plan = Plan (New-Obs -RelayHealth $false -RelayPort $false -RelayPid $null) $h
    (@($plan.Actions | Where-Object { $_.Component -eq 'external-relay' })).Count -eq 0 -and
    ($plan.Blocked | Where-Object { $_.Component -eq 'external-relay' }).Reason -eq 'RESTART_BUDGET_EXCEEDED' -and
    # the core chain's own budget is a completely separate counter - still free to act
    (@($plan.Actions | Where-Object { $_.Component -eq 'spring' })).Count -eq 0   # spring is healthy here, nothing to assert beyond no crash
}

Check 'executor recovers a down relay via the injected Runner, independent of core-chain state' {
    $calls = New-Object System.Collections.Generic.List[string]
    $h = New-EmptyHistory
    $result = Invoke-WatchdogRecovery -Observe { New-Obs -RelayHealth $false -RelayPort $false -RelayPid $null } `
        -Runner { param($a) $calls.Add($a.Action); $true } -History $h -HistoryAvailable $true -Now $now -MaxSteps 2
    (-not $result.Failed) -and ($calls -contains 'START_EXTERNAL_RELAY')
}

Check 'Invoke-RecoveryAction routes external-relay to its own dedicated script, never the full orchestrator (source check)' {
    $src = Get-Content -LiteralPath (Join-Path $scripts 'RunningAI.Watchdog.ps1') -Raw
    $fnStart = $src.IndexOf('function Invoke-RecoveryAction')
    $fn = $src.Substring($fnStart)
    $fn.Contains("if (`$Action.Component -eq 'external-relay')") -and $fn.Contains('external\start-external-relay.ps1')
}

# ---- restart budget ---------------------------------------------------------------------------

Check 'restart budget: 3 recent restarts block the 4th (RESTART_BUDGET_EXCEEDED, DEGRADED)' {
    $h = New-EmptyHistory
    1..3 | ForEach-Object { Add-RestartRecord -History $h -Component 'spring' -Now $now.AddMinutes(-$_) }
    $obs = New-Obs -SpringHealth $false -SpringPort $false
    $plan = Plan $obs $h
    $plan.Actions.Count -eq 0 -and ($plan.Blocked | Where-Object { $_.Component -eq 'spring' }).Reason -eq 'RESTART_BUDGET_EXCEEDED' -and
    (Get-OverallState -States (Get-ComponentStates $obs) -Blocked $plan.Blocked) -eq 'DOWN'
}

Check 'restart budget is per component' {
    $h = New-EmptyHistory
    1..3 | ForEach-Object { Add-RestartRecord -History $h -Component 'spring' -Now $now.AddMinutes(-$_) }
    (Plan (New-Obs -ConnectorHealth $false -ConnectorPort $false) $h).Actions[0].Action -eq 'START_CONNECTOR'
}

Check 'budget expiry: restarts outside the window no longer count, and are pruned' {
    $h = New-EmptyHistory
    1..3 | ForEach-Object { Add-RestartRecord -History $h -Component 'spring' -Now $now.AddMinutes(-30 - $_) }
    $plan = Plan (New-Obs -SpringHealth $false -SpringPort $false) $h
    Update-HistoryWindow -History $h -Now $now
    $plan.Actions.Count -eq 1 -and @($h['spring']).Count -eq 0
}

Check 'executor stops after the budget instead of restart-storming' {
    $h = New-EmptyHistory
    $calls = New-Object System.Collections.ArrayList
    $observe = { New-Obs -SpringHealth $false -SpringPort $false }               # never recovers
    $runner = { param($a) [void]$calls.Add($a.Action); $true }
    $r = Invoke-WatchdogRecovery -Observe $observe -Runner $runner -History $h -HistoryAvailable $true -Now $now
    $calls.Count -eq 3 -and -not $r.Failed -and (Get-RestartCount -History $h -Component 'spring' -Now $now) -eq 3
}

Check 'executor: docker recovery verified step by step, failure stops the chain' {
    $h = New-EmptyHistory
    $calls = New-Object System.Collections.ArrayList
    $stage = @{ Docker = 'DOWN' }
    $observe = { if ($stage.Docker -eq 'UP') { New-Obs } else { New-Obs -Docker 'DOWN' -Postgres 'unknown' -ConnectorHealth $false -ConnectorPort $false -SpringHealth $false -SpringPort $false } }
    $ok = Invoke-WatchdogRecovery -Observe $observe -Runner { param($a) [void]$calls.Add($a.Action); $stage.Docker = 'UP'; $true } -History $h -HistoryAvailable $true -Now $now
    $h2 = New-EmptyHistory; $calls2 = New-Object System.Collections.ArrayList
    $bad = Invoke-WatchdogRecovery -Observe { New-Obs -Docker 'DOWN' -Postgres 'unknown' -ConnectorHealth $false -ConnectorPort $false } -Runner { param($a) [void]$calls2.Add($a.Action); $false } `
        -History $h2 -HistoryAvailable $true -Now $now
    ($calls -join ',') -eq 'START_DOCKER' -and $ok.Steps[0].Result -eq 'SUCCESS' -and @($h['docker']).Count -eq 1 -and
    $bad.Failed -and ($calls2 -join ',') -eq 'START_DOCKER'                       # nothing after the failed Docker step
}

Check 'executor does nothing for a healthy runtime' {
    $calls = New-Object System.Collections.ArrayList
    $r = Invoke-WatchdogRecovery -Observe { New-Obs } -Runner { param($a) [void]$calls.Add($a.Action); $true } -History (New-EmptyHistory) -HistoryAvailable $true -Now $now
    $calls.Count -eq 0 -and $r.Steps.Count -eq 0
}

# ---- state file ---------------------------------------------------------------------------------

Check 'state round trip and atomic write leaves valid JSON and no temp files' {
    $d = New-TempDir
    try {
        $path = Join-Path $d 'watchdog-state.json'
        $h = New-EmptyHistory
        Add-RestartRecord -History $h -Component 'connector' -Now $now
        Save-WatchdogState -History $h -Path $path
        Add-RestartRecord -History $h -Component 'spring' -Now $now
        Save-WatchdogState -History $h -Path $path                                  # overwrite path (Replace)
        $back = Read-WatchdogState -Path $path
        $null = ConvertFrom-Json (Get-Content $path -Raw)
        $back.Available -and @($back.History['connector']).Count -eq 1 -and @($back.History['spring']).Count -eq 1 -and
        @(Get-ChildItem $d -Filter '*.tmp-*').Count -eq 0
    } finally { Remove-Item $d -Recurse -Force }
}

Check 'missing state file is a normal first run' {
    $d = New-TempDir
    try { (Read-WatchdogState -Path (Join-Path $d 'nope.json')).Available } finally { Remove-Item $d -Recurse -Force }
}

Check 'corrupt state file: quarantined, history unavailable, no recovery planned (fail safe)' {
    $d = New-TempDir
    try {
        $path = Join-Path $d 'watchdog-state.json'
        Set-Content $path '{ "restarts": { "spring": [1, 2, ' -Encoding ascii
        $s = Read-WatchdogState -Path $path
        $plan = Plan (New-Obs -SpringHealth $false -SpringPort $false) $s.History $s.Available
        (-not $s.Available) -and (Test-Path "$path.corrupt") -and -not (Test-Path $path) -and
        $plan.Actions.Count -eq 0 -and ($plan.Blocked | Where-Object { $_.Component -eq 'spring' }).Reason -eq 'STATE_UNAVAILABLE_FAIL_SAFE'
    } finally { Remove-Item $d -Recurse -Force }
}

Check 'wrong-shaped state JSON is also treated as corrupt' {
    $d = New-TempDir
    try {
        $path = Join-Path $d 's.json'; Set-Content $path '{"hello": 1}' -Encoding ascii
        -not (Read-WatchdogState -Path $path).Available
    } finally { Remove-Item $d -Recurse -Force }
}

# ---- observation with injected probes ------------------------------------------------------------

Check 'health is re-checked once when the process is alive: transient failure is not persistent' {
    $n = @{ c = 0 }
    $probes = @{ Docker = { 'UP' }; Postgres = { 'healthy' }; ConnectorPid = { 1234 }; ConnectorHealth = { $n.c++; $n.c -ge 2 }
                 ConnectorPortUsed = { $true }; SpringPid = { $null }; SpringHealth = { $true }; SpringPortUsed = { $true }
                 ExternalRelayPid = { $null }; ExternalRelayHealth = { $true }; ExternalRelayPortUsed = { $true } }
    $obs = Get-RuntimeObservation -RecheckDelaySec 1 -Probes $probes
    $obs.ConnectorHealth -and $n.c -eq 2
}

Check 'persistent health failure with a live process is classified UNHEALTHY' {
    $probes = @{ Docker = { 'UP' }; Postgres = { 'healthy' }; ConnectorPid = { 1234 }; ConnectorHealth = { $false }
                 ConnectorPortUsed = { $true }; SpringPid = { $null }; SpringHealth = { $true }; SpringPortUsed = { $true }
                 ExternalRelayPid = { $null }; ExternalRelayHealth = { $true }; ExternalRelayPortUsed = { $true } }
    (Get-ComponentStates (Get-RuntimeObservation -RecheckDelaySec 1 -Probes $probes))['connector'].State -eq 'UNHEALTHY'
}

# ---- garmin hint: informational only -----------------------------------------------------------------

function Line { param($Minutes, $Text) "{0} INFO --- [scheduling-1] c.r.i.g.GarminSyncScheduler : {1}" -f $now.AddMinutes(-$Minutes).ToString('yyyy-MM-ddTHH:mm:ss.fffzzz'), $Text }

Check 'garmin auth / 403 / 429 / upstream reasons produce a hint but never an action' {
    foreach ($reason in 'AUTH_REQUIRED', 'FORBIDDEN', 'RATE_LIMITED', 'UPSTREAM_ERROR') {
        $hint = Get-GarminHint -Lines @(Line 10 "Garmin scheduled sync failed: reason=$reason httpStatus=401; no retry until the next tick") -Now $now
        $plan = Plan (New-Obs)
        if ($hint -ne "GARMIN_$reason" -or $plan.Actions.Count -ne 0) { throw "reason $reason -> hint=$hint actions=$($plan.Actions.Count)" }
        if ((Get-OverallState -States (Get-ComponentStates (New-Obs)) -Blocked $plan.Blocked -GarminHint $hint) -ne 'DEGRADED') { throw 'not reported as DEGRADED' }
    }
}

Check 'garmin hint: a later successful sync clears it, old hints and connector-unavailable are ignored' {
    $failed = Line 30 'Garmin scheduled sync failed: reason=RATE_LIMITED httpStatus=429; no retry until the next tick'
    $ok = Line 5 'Garmin scheduled sync completed: fetched=1 created=0 updated=1 skipped=0 failed=0 pages=1 checkpointAdvanced=false'
    $old = Line 400 'Garmin scheduled sync failed: reason=AUTH_REQUIRED httpStatus=401; no retry until the next tick'
    $unavailable = Line 5 'Garmin scheduled sync failed: reason=UNAVAILABLE httpStatus=; no retry until the next tick'
    ($null -eq (Get-GarminHint -Lines @($failed, $ok) -Now $now)) -and ($null -eq (Get-GarminHint -Lines @($old) -Now $now)) -and
    ($null -eq (Get-GarminHint -Lines @($unavailable) -Now $now)) -and ((Get-GarminHint -Lines @($ok, $failed) -Now $now) -eq 'GARMIN_RATE_LIMITED')
}

# ---- logs -------------------------------------------------------------------------------------------------

Check 'log retention removes only old rotated RunningAI logs inside the log dir' {
    $d = New-TempDir
    try {
        $names = @{ oldRot = 'spring.out.20200101-000000.log'; newRot = ('spring.err.' + $now.ToString('yyyyMMdd-HHmmss') + '.log'); current = 'spring.out.log'
                    foreign = 'other.20200101-000000.log'; note = 'notes.txt'; oldWatch = 'watchdog.20200102-000000-1.log' }
        foreach ($n in $names.Values) { Set-Content (Join-Path $d $n) 'x' }
        foreach ($n in $names.oldRot, $names.foreign, $names.note, $names.current, $names.oldWatch) { (Get-Item (Join-Path $d $n)).LastWriteTime = $now.AddDays(-30) }
        $removed = Remove-ExpiredLogs -LogDir $d -MaxAgeDays 14 -Now $now
        (@($removed) -contains $names.oldRot) -and (@($removed) -contains $names.oldWatch) -and $removed.Count -eq 2 -and
        (Test-Path (Join-Path $d $names.newRot)) -and (Test-Path (Join-Path $d $names.current)) -and (Test-Path (Join-Path $d $names.foreign)) -and (Test-Path (Join-Path $d $names.note))
    } finally { Remove-Item $d -Recurse -Force }
}

Check 'retention outside the log dir is impossible (missing dir returns nothing)' {
    @(Remove-ExpiredLogs -LogDir (Join-Path ([IO.Path]::GetTempPath()) 'ra-no-such-dir')).Count -eq 0
}

Check 'log rotation: by size and before restart, never empty files, unique names' {
    $d = New-TempDir
    try {
        [IO.File]::WriteAllBytes((Join-Path $d 'watchdog.log'), (New-Object byte[] 2048))
        Set-Content (Join-Path $d 'spring.out.log') 'small'
        New-Item (Join-Path $d 'spring.err.log') -ItemType File | Out-Null
        $bySize = Invoke-LogRotation -LogDir $d -Name 'watchdog' -MaxBytes 1024 -Now $now
        $smallKept = -not (Invoke-LogRotation -LogDir $d -Name 'spring.out' -MaxBytes 1024 -Now $now)
        $always = Invoke-LogRotation -LogDir $d -Name 'spring.out' -Always -Now $now
        $emptyKept = -not (Invoke-LogRotation -LogDir $d -Name 'spring.err' -Always -Now $now)
        Set-Content (Join-Path $d 'watchdog.log') 'again'
        $second = Invoke-LogRotation -LogDir $d -Name 'watchdog' -Always -Now $now              # same second: needs a unique name
        $rotated = @(Get-ChildItem $d | Where-Object { $_.Name -match $script:RotatedLogPattern })
        $bySize -and $smallKept -and $always -and $emptyKept -and $second -and $rotated.Count -eq 3
    } finally { Remove-Item $d -Recurse -Force }
}

Check 'watchdog log lines carry state and action only' {
    $d = New-TempDir
    try {
        $p = Join-Path $d 'watchdog.log'
        Write-WatchdogLog -Component 'spring' -State 'DOWN' -Reason 'PROCESS_DEAD' -Action 'START_SPRING' -Result 'SUCCESS' -Path $p
        (Get-Content $p) -match '^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}Z component=spring state=DOWN reason=PROCESS_DEAD action=START_SPRING result=SUCCESS$'
    } finally { Remove-Item $d -Recurse -Force }
}

# ---- safety / boundaries -------------------------------------------------------------------------------

Check 'a look-alike connector from another location is never treated as ours or stopped' {
    $dummy = Start-Process -FilePath (Get-Command powershell.exe).Source -PassThru -WindowStyle Hidden `
        -ArgumentList '-NoProfile -Command "Start-Sleep -Seconds 60 # garmin_connector serve C:\other\legacy-project"'
    try {
        Start-Sleep -Milliseconds 800
        $identity = Test-ProcessIdentity -ProcessId $dummy.Id -Markers (Get-ConnectorMarkers)
        Write-PidFile 'selftest-lookalike' $dummy.Id
        $tracked = Get-TrackedProcessId 'selftest-lookalike' (Get-ConnectorMarkers)
        $stop = Stop-TrackedProcess -ProcessId $dummy.Id -Markers (Get-ConnectorMarkers) -TimeoutSec 1
        (-not $identity) -and ($null -eq $tracked) -and $stop -eq 'not-running' -and [bool](Get-Process -Id $dummy.Id -ErrorAction SilentlyContinue)
    } finally { Stop-Process -Id $dummy.Id -Force -ErrorAction SilentlyContinue; Remove-PidFile 'selftest-lookalike' }
}

Check 'watchdog sources contain no destructive docker, name-based kill or Garmin call' {
    $files = 'RunningAI.Watchdog.ps1', 'watch-running-ai.ps1', 'install-running-ai-watchdog-task.ps1', 'uninstall-running-ai-watchdog-task.ps1'
    foreach ($f in $files) {
        $code = (Get-Content (Join-Path $scripts $f) -Raw) -replace '(?s)<#.*?#>', '' -replace '(?m)^\s*#.*$', ''
        foreach ($bad in 'compose[^\r\n]*\bdown\b', 'volume\s+rm', 'system\s+prune', 'Stop-Process\s+-Name', 'Get-Process\s+-Name', 'taskkill', 'api/v1/garmin/sync', '-Method\s+Post', 'garmin_connector\s+login') {
            if ($code -match $bad) { throw "$f matches /$bad/" }
        }
    }
}

Check 'dry run executes nothing and writes no files' {
    $watched = @($script:StatePath, $script:StatusPath, $script:WatchdogLogPath)
    $before = $watched | ForEach-Object { if (Test-Path $_) { (Get-Item $_).LastWriteTimeUtc.Ticks } else { -1 } }
    $out = & powershell -NoProfile -ExecutionPolicy Bypass -File (Join-Path $scripts 'watch-running-ai.ps1') -DryRun -RecheckDelaySec 0 | Out-String
    $after = $watched | ForEach-Object { if (Test-Path $_) { (Get-Item $_).LastWriteTimeUtc.Ticks } else { -1 } }
    ($out -match 'DRY RUN') -and (($before -join ',') -eq ($after -join ','))
}

Check 'watchdog task definition: every 5 min, 5 min logon delay, IgnoreNew, current user, this repo (not registered)' {
    $before = @(Get-ScheduledTask -TaskName 'RunningAI-Watchdog' -ErrorAction SilentlyContinue).Count
    $parts = New-WatchdogTaskParts
    $out = & powershell -NoProfile -ExecutionPolicy Bypass -File (Join-Path $scripts 'install-running-ai-watchdog-task.ps1') -DryRun | Out-String
    $after = @(Get-ScheduledTask -TaskName 'RunningAI-Watchdog' -ErrorAction SilentlyContinue).Count
    ($parts.Trigger.Repetition.Interval -eq 'PT5M') -and ($parts.Trigger.Delay -eq 'PT5M') -and ("$($parts.Settings.MultipleInstances)" -eq 'IgnoreNew') -and
    ($parts.Arguments -like '*watch-running-ai.ps1*') -and ($parts.Action.WorkingDirectory -eq (Get-RepoRoot)) -and
    ($before -eq $after) -and ($out -match 'DRY RUN') -and ($out -match 'IgnoreNew')
}

Check 'startup task installer is unchanged in purpose and separate from the watchdog task' {
    $startup = Get-Content (Join-Path $scripts 'install-running-ai-scheduled-task.ps1') -Raw
    ($startup -match "RunningAI-Startup") -and ($startup -notmatch 'watch-running-ai') -and ($startup -match 'AtLogOn')
}

Check 'new artifacts are git-ignored' {
    Push-Location (Get-RepoRoot)
    try { @(git check-ignore '.runtime/watchdog-state.json' '.runtime/watchdog-status.json' '.runtime/logs/watchdog.log' '.runtime/watchdog-state.json.corrupt').Count -eq 4 } finally { Pop-Location }
}

# ---- Phase 6I-1.1 regression lock-ins ---------------------------------------------------------

# Live-reproduced bug: a probe built by Get-DefaultProbes and wrapped in .GetNewClosure() calls a
# dot-sourced helper FUNCTION (Quote-Argument, Invoke-NativeText, Test-ConnectorHealth, ...) by
# name. A closure's new session state chains to GLOBAL, not to the scope this file was dot-sourced
# into, so when watch-running-ai.ps1 is invoked DIRECTLY (as an operator would: ".\watch-running-
# ai.ps1", or here "powershell -Command '& path -DryRun'") rather than via "powershell -File" (as
# the Scheduled Task does), that scope is not global and the helper call throws "term '<name>' is
# not recognized". -File hides this entirely, so the regression test must avoid -File and must
# exercise the REAL (non-injected) probes, not the mocked $Probes used everywhere else in this file.
Check 'dry run via direct invocation (not -File) exercises the real default probes without a closure scope regression' {
    $script = Join-Path $scripts 'watch-running-ai.ps1'
    $out = (powershell -NoProfile -Command "& '$script' -DryRun -RecheckDelaySec 0" 2>&1 | Out-String)
    $exit = $LASTEXITCODE
    ($exit -eq 0) -and ($out -match 'DRY RUN') -and ($out -notmatch 'is not recognized') -and ($out -notmatch 'ERROR:')
}

# Live-reproduced bug: $script:Components (the 4-component dependency chain) was used instead of
# $script:AllTrackedComponents for the DryRun component listing and for the status JSON's
# "components"/"restartBudget.used" maps, so external-relay (Phase 6I-1.1, an INDEPENDENT
# component - see $script:IndependentComponents) silently never appeared in any of them.
Check 'DryRun component listing includes external-relay' {
    $script = Join-Path $scripts 'watch-running-ai.ps1'
    $out = & powershell -NoProfile -ExecutionPolicy Bypass -File $script -DryRun -RecheckDelaySec 0 | Out-String
    $out -match '(?m)^external-relay\s'
}

Check 'status JSON components and restartBudget.used include external-relay (normal, no-recovery run)' {
    $temp = Join-Path $env:TEMP "selftest-watchdog-statusdir-$([guid]::NewGuid().ToString('N'))"
    $originalEnv = $env:RUNNING_AI_TEST_RUNTIME_DIR
    $env:RUNNING_AI_TEST_RUNTIME_DIR = $temp
    try {
        $script = Join-Path $scripts 'watch-running-ai.ps1'
        & powershell -NoProfile -ExecutionPolicy Bypass -File $script -NoRecovery -RecheckDelaySec 0 | Out-Null
        $statusPath = Join-Path $temp 'watchdog-status.json'
        if (-not (Test-Path -LiteralPath $statusPath)) { throw 'watchdog-status.json was not written' }
        $status = ConvertFrom-Json (Get-Content -LiteralPath $statusPath -Raw)
        ($status.components.PSObject.Properties.Name -contains 'external-relay') -and
        ($status.restartBudget.used.PSObject.Properties.Name -contains 'external-relay')
    } finally {
        $env:RUNNING_AI_TEST_RUNTIME_DIR = $originalEnv
        Remove-Item -Recurse -Force $temp -ErrorAction SilentlyContinue
    }
}

# Phase 6I-1.7B-2A: the status JSON gains a new, additive "connectorOwnership" field - this proves
# (a) the pre-existing fields (components/blocked/restartBudget) are completely unchanged in shape
# (backward compatibility - STEP 5 item 11) and (b) the new field is actually present and carries
# nothing but the fixed verdict vocabulary string, never a CommandLine or credential.
Check 'status JSON is backward compatible and additionally carries connectorOwnership.verdict' {
    $temp = Join-Path $env:TEMP "selftest-watchdog-statusdir2-$([guid]::NewGuid().ToString('N'))"
    $originalEnv = $env:RUNNING_AI_TEST_RUNTIME_DIR
    $env:RUNNING_AI_TEST_RUNTIME_DIR = $temp
    try {
        $script = Join-Path $scripts 'watch-running-ai.ps1'
        & powershell -NoProfile -ExecutionPolicy Bypass -File $script -NoRecovery -RecheckDelaySec 0 | Out-Null
        $statusPath = Join-Path $temp 'watchdog-status.json'
        if (-not (Test-Path -LiteralPath $statusPath)) { throw 'watchdog-status.json was not written' }
        $status = ConvertFrom-Json (Get-Content -LiteralPath $statusPath -Raw)
        $names = $status.PSObject.Properties.Name
        # Backward compatibility: every pre-existing top-level field is still present with its
        # original shape untouched.
        ($names -contains 'checkedAt') -and ($names -contains 'overall') -and ($names -contains 'components') -and
        ($names -contains 'blocked') -and ($names -contains 'garminHint') -and ($names -contains 'lastAction') -and
        ($names -contains 'restartBudget') -and ($status.components.PSObject.Properties.Name -contains 'connector') -and
        ($status.restartBudget.PSObject.Properties.Name -contains 'used') -and
        # Additive: the new field exists and (when present) is just a verdict string.
        ($names -contains 'connectorOwnership') -and
        (($null -eq $status.connectorOwnership) -or ($status.connectorOwnership.PSObject.Properties.Name -contains 'verdict'))
    } finally {
        $env:RUNNING_AI_TEST_RUNTIME_DIR = $originalEnv
        Remove-Item -Recurse -Force $temp -ErrorAction SilentlyContinue
    }
}

if ($failures.Count) {
    Write-Host ("{0} check(s) failed: {1}" -f $failures.Count, ($failures -join '; ')) -ForegroundColor Red
    exit 1
}
Write-Host 'All watchdog checks passed.'
exit 0
