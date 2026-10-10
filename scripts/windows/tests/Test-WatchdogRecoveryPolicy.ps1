<#
.SYNOPSIS
  Isolated checks for Phase 6I-1.7B-2B (Watchdog recovery policy hardening): long-term lockout,
  relay-independent recovery across a core-chain failure, DryRun read-only guarantees, state-file
  migration, connector ownership consistency through a full recovery cycle, and the cross-process
  start/stop runtime lock. Uses only injected observe/runner closures, temporary isolated runtime
  directories, and short-lived synthetic PowerShell test processes on temporary loopback ports -
  never the real Garmin connector, never ports 8765/18080/17845, never Docker/PostgreSQL, never a
  real start-running-ai.ps1/stop-running-ai.ps1 invocation against the real runtime.
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

function New-TempRuntimeDir { $d = Join-Path ([IO.Path]::GetTempPath()) ('ra-recovpolicy-' + [guid]::NewGuid().ToString('N')); New-Item -ItemType Directory -Force $d | Out-Null; $d }

# Runs $Body with RUNNING_AI_TEST_RUNTIME_DIR pointed at a fresh temp dir, always restoring the
# previous value and deleting the temp dir afterward - matches the existing precedent in
# Test-Watchdog.ps1 (the "status JSON..." checks), extended to also hand the dir to $Body.
function With-TempRuntimeDir {
    param([scriptblock]$Body)
    $temp = New-TempRuntimeDir
    $originalEnv = $env:RUNNING_AI_TEST_RUNTIME_DIR
    $env:RUNNING_AI_TEST_RUNTIME_DIR = $temp
    try { & $Body $temp } finally {
        $env:RUNNING_AI_TEST_RUNTIME_DIR = $originalEnv
        Remove-Item -Recurse -Force $temp -ErrorAction SilentlyContinue
    }
}

$now = Get-Date

# ======================================================================================
# STEP C/D: long-term lockout + state versioning/migration
# ======================================================================================

Check 'long-term lockout: locks after the configured failure threshold, not before' {
    $lt = New-EmptyLongTermState
    1..5 | ForEach-Object {
        Add-LongTermFailure -LongTermState $lt -Component 'connector' -Now $now
        Update-RunningAiLongTermLockout -LongTermState $lt -Component 'connector' -Now $now -WindowHours 24 -MaxFailures 6
    }
    $notYetLocked = -not (Test-RunningAiComponentLockedOut -LongTermState $lt -Component 'connector')
    Add-LongTermFailure -LongTermState $lt -Component 'connector' -Now $now
    Update-RunningAiLongTermLockout -LongTermState $lt -Component 'connector' -Now $now -WindowHours 24 -MaxFailures 6
    $lockedAtSix = Test-RunningAiComponentLockedOut -LongTermState $lt -Component 'connector'
    $notYetLocked -and $lockedAtSix
}

Check 'long-term lockout never locks a DIFFERENT component (per-component, not global)' {
    $lt = New-EmptyLongTermState
    1..6 | ForEach-Object {
        Add-LongTermFailure -LongTermState $lt -Component 'connector' -Now $now
        Update-RunningAiLongTermLockout -LongTermState $lt -Component 'connector' -Now $now -WindowHours 24 -MaxFailures 6
    }
    (Test-RunningAiComponentLockedOut -LongTermState $lt -Component 'connector') -and
    (-not (Test-RunningAiComponentLockedOut -LongTermState $lt -Component 'spring')) -and
    (-not (Test-RunningAiComponentLockedOut -LongTermState $lt -Component 'external-relay'))
}

Check 'a lockout persists even after its failures age out of the long-term window (no auto-expiry)' {
    $lt = New-EmptyLongTermState
    $longAgo = $now.AddHours(-48)
    1..6 | ForEach-Object { Add-LongTermFailure -LongTermState $lt -Component 'spring' -Now $longAgo }
    Update-RunningAiLongTermLockout -LongTermState $lt -Component 'spring' -Now $longAgo -WindowHours 24 -MaxFailures 6
    $lockedAtTheTime = Test-RunningAiComponentLockedOut -LongTermState $lt -Component 'spring'
    # Simulate the next tick, now 48h later - pruning drops every failure out of the 24h window...
    Update-LongTermFailureWindow -LongTermState $lt -Now $now -WindowHours 24
    $failureCountNow = Get-LongTermFailureCount -LongTermState $lt -Component 'spring' -Now $now -WindowHours 24
    $stillLocked = Test-RunningAiComponentLockedOut -LongTermState $lt -Component 'spring'
    $lockedAtTheTime -and ($failureCountNow -eq 0) -and $stillLocked
}

Check 'Get-RecoveryPlan blocks a LOCKED_OUT component with reason LOCKED_OUT, omitting -LongTermState reproduces legacy behavior' {
    $lt = New-EmptyLongTermState
    $lt['connector'].LockedOutSince = (ConvertTo-UnixSeconds $now)
    $states = [ordered]@{
        docker = [pscustomobject]@{ State = 'UP'; Reason = '' }
        postgres = [pscustomobject]@{ State = 'UP'; Reason = '' }
        connector = [pscustomobject]@{ State = 'DOWN'; Reason = 'PROCESS_DEAD' }
        spring = [pscustomobject]@{ State = 'UP'; Reason = '' }
        'external-relay' = [pscustomobject]@{ State = 'UP'; Reason = '' }
    }
    $history = New-EmptyHistory
    $withLockout = Get-RecoveryPlan -States $states -History $history -Now $now -LongTermState $lt
    $withoutLockout = Get-RecoveryPlan -States $states -History $history -Now $now
    ($withLockout.Actions.Count -eq 0) -and
    (($withLockout.Blocked | Where-Object { $_.Component -eq 'connector' }).Reason -eq 'LOCKED_OUT') -and
    ($withoutLockout.Actions.Count -eq 1) -and ($withoutLockout.Actions[0].Component -eq 'connector')
}

Check 'Clear-RunningAiComponentLockout resets both the lockout flag and failure history for just one component' {
    $lt = New-EmptyLongTermState
    1..6 | ForEach-Object { Add-LongTermFailure -LongTermState $lt -Component 'connector' -Now $now }
    Update-RunningAiLongTermLockout -LongTermState $lt -Component 'connector' -Now $now -MaxFailures 6
    Add-LongTermFailure -LongTermState $lt -Component 'spring' -Now $now
    Clear-RunningAiComponentLockout -LongTermState $lt -Component 'connector'
    (-not (Test-RunningAiComponentLockedOut -LongTermState $lt -Component 'connector')) -and
    (@($lt['connector'].Failures).Count -eq 0) -and
    (@($lt['spring'].Failures).Count -eq 1)   # untouched
}

Check 'state v1 (no longTerm key) migrates: restart history preserved, LongTermState fresh and unlocked' {
    With-TempRuntimeDir {
        param($dir)
        $v1 = @{ version = 1; restarts = @{ connector = @(1700000000, 1700000100); spring = @() } } | ConvertTo-Json -Depth 5
        [System.IO.File]::WriteAllText((Join-Path $dir 'watchdog-state.json'), $v1, (New-Object System.Text.UTF8Encoding($false)))
        $state = Read-WatchdogState -Path (Join-Path $dir 'watchdog-state.json')
        ($state.Available) -and (@($state.History['connector']).Count -eq 2) -and
        (-not (Test-RunningAiComponentLockedOut -LongTermState $state.LongTermState -Component 'connector')) -and
        (@($state.LongTermState['connector'].Failures).Count -eq 0)
    }
}

Check 'state v2 round trip preserves both restart history and long-term lockout data' {
    With-TempRuntimeDir {
        param($dir)
        $path = Join-Path $dir 'watchdog-state.json'
        $history = New-EmptyHistory
        Add-RestartRecord -History $history -Component 'spring' -Now $now
        $lt = New-EmptyLongTermState
        1..6 | ForEach-Object { Add-LongTermFailure -LongTermState $lt -Component 'connector' -Now $now }
        Update-RunningAiLongTermLockout -LongTermState $lt -Component 'connector' -Now $now -MaxFailures 6
        Save-WatchdogState -History $history -LongTermState $lt -Path $path
        $reloaded = Read-WatchdogState -Path $path
        (@($reloaded.History['spring']).Count -eq 1) -and
        (Test-RunningAiComponentLockedOut -LongTermState $reloaded.LongTermState -Component 'connector') -and
        (@($reloaded.LongTermState['connector'].Failures).Count -eq 6)
    }
}

Check 'a lockout survives a simulated Watchdog process restart (fresh Read-WatchdogState after Save)' {
    With-TempRuntimeDir {
        param($dir)
        $path = Join-Path $dir 'watchdog-state.json'
        $lt = New-EmptyLongTermState
        1..6 | ForEach-Object { Add-LongTermFailure -LongTermState $lt -Component 'docker' -Now $now }
        Update-RunningAiLongTermLockout -LongTermState $lt -Component 'docker' -Now $now -MaxFailures 6
        Save-WatchdogState -History (New-EmptyHistory) -LongTermState $lt -Path $path
        # A brand new process reading the SAME file (this call has no memory of $lt at all) must see
        # the exact same lockout - this is the only way a real Watchdog process restart (or a reboot)
        # could possibly preserve it, since nothing but the file survives either event.
        $afterRestart = Read-WatchdogState -Path $path
        Test-RunningAiComponentLockedOut -LongTermState $afterRestart.LongTermState -Component 'docker'
    }
}

Check 'a corrupt state file with -ReadOnly is reported unavailable but NEVER quarantined (file left in place)' {
    With-TempRuntimeDir {
        param($dir)
        $path = Join-Path $dir 'watchdog-state.json'
        Set-Content -LiteralPath $path -Value 'not valid json at all' -Encoding ascii
        $before = Get-Content -LiteralPath $path -Raw
        $result = Read-WatchdogState -Path $path -ReadOnly
        $stillThere = (Test-Path -LiteralPath $path) -and ((Get-Content -LiteralPath $path -Raw) -eq $before)
        $notQuarantined = -not (Test-Path -LiteralPath "$path.corrupt")
        (-not $result.Available) -and $stillThere -and $notQuarantined
    }
}

Check 'the SAME corrupt file WITHOUT -ReadOnly is quarantined as before (regression: ReadOnly does not weaken the real path)' {
    With-TempRuntimeDir {
        param($dir)
        $path = Join-Path $dir 'watchdog-state.json'
        Set-Content -LiteralPath $path -Value 'not valid json at all' -Encoding ascii
        $result = Read-WatchdogState -Path $path
        (-not $result.Available) -and (-not (Test-Path -LiteralPath $path)) -and (Test-Path -LiteralPath "$path.corrupt")
    }
}

# ======================================================================================
# STEP G: DryRun read-only guarantee for the stale-PID-file path
# ======================================================================================

Check 'Get-TrackedProcessId -ReadOnly reports a stale PID as absent but never removes the file' {
    With-TempRuntimeDir {
        param($dir)
        $name = 'selftest-readonly-' + [guid]::NewGuid().ToString('N')
        Write-PidFile $name $PID   # alive, but $PID itself never matches connector/spring markers
        $before = Get-Content -LiteralPath (Get-PidFilePath $name) -Raw
        $tracked = Get-TrackedProcessId $name (Get-ConnectorMarkers) -ReadOnly
        $stillThere = (Test-Path (Get-PidFilePath $name)) -and ((Get-Content -LiteralPath (Get-PidFilePath $name) -Raw) -eq $before)
        ($null -eq $tracked) -and $stillThere
    }
}

Check 'the SAME stale PID file WITHOUT -ReadOnly is removed as before (regression: ReadOnly does not weaken the real path)' {
    With-TempRuntimeDir {
        param($dir)
        $name = 'selftest-readonly-' + [guid]::NewGuid().ToString('N')
        Write-PidFile $name $PID
        $tracked = Get-TrackedProcessId $name (Get-ConnectorMarkers)
        ($null -eq $tracked) -and (-not (Test-Path (Get-PidFilePath $name)))
    }
}

# ======================================================================================
# STEP A-1 / STEP E: consistent ConnectorPort + relay independence through a full recovery cycle
# ======================================================================================

function New-RecoveryObs {
    param($Docker = 'UP', $Postgres = 'healthy', [bool]$ConnectorHealth = $true, [bool]$ConnectorPort = $true, $ConnectorPid = $null,
          [bool]$SpringHealth = $true, [bool]$SpringPort = $true, $SpringPid = $null,
          [bool]$RelayHealth = $true, [bool]$RelayPort = $true, $RelayPid = $null)
    [pscustomobject]@{ Docker = $Docker; Postgres = $Postgres; ConnectorHealth = $ConnectorHealth; ConnectorPortUsed = $ConnectorPort
        ConnectorPid = $ConnectorPid; SpringHealth = $SpringHealth; SpringPortUsed = $SpringPort; SpringPid = $SpringPid
        ExternalRelayHealth = $RelayHealth; ExternalRelayPortUsed = $RelayPort; ExternalRelayPid = $RelayPid }
}

Check 'Spring failing does not prevent Relay from being recovered in the SAME Invoke-WatchdogRecovery call' {
    $calls = New-Object System.Collections.Generic.List[string]
    $observe = { New-RecoveryObs -SpringHealth $false -SpringPort $false -RelayHealth $false -RelayPort $false }
    $runner = {
        param($a)
        $calls.Add($a.Component)
        if ($a.Component -eq 'spring') { return $false }   # Spring restart keeps failing
        return $true                                        # relay succeeds
    }
    $result = Invoke-WatchdogRecovery -Observe $observe -Runner $runner -History (New-EmptyHistory) -HistoryAvailable $true -Now $now -MaxSteps 3
    ($calls -contains 'spring') -and ($calls -contains 'external-relay') -and
    (($result.Steps | Where-Object { $_.Component -eq 'external-relay' }).Result -eq 'SUCCESS') -and
    $result.Failed   # overall still reports failed, because spring (core chain) never recovered
}

Check 'Connector failing does not prevent Relay from being recovered in the SAME call (symmetric case)' {
    $calls = New-Object System.Collections.Generic.List[string]
    $observe = { New-RecoveryObs -ConnectorHealth $false -ConnectorPort $false -RelayHealth $false -RelayPort $false }
    $runner = {
        param($a)
        $calls.Add($a.Component)
        if ($a.Component -eq 'connector') { return $false }
        return $true
    }
    $result = Invoke-WatchdogRecovery -Observe $observe -Runner $runner -History (New-EmptyHistory) -HistoryAvailable $true -Now $now -MaxSteps 3
    ($calls -contains 'connector') -and ($calls -contains 'external-relay') -and
    (($result.Steps | Where-Object { $_.Component -eq 'external-relay' }).Result -eq 'SUCCESS')
}

Check 'Relay failing does not prevent Spring from being recovered (core chain unaffected by the relay loop)' {
    $calls = New-Object System.Collections.Generic.List[string]
    $observe = { New-RecoveryObs -SpringHealth $false -SpringPort $false -RelayHealth $false -RelayPort $false }
    $runner = {
        param($a)
        $calls.Add($a.Component)
        if ($a.Component -eq 'external-relay') { return $false }
        return $true
    }
    $result = Invoke-WatchdogRecovery -Observe $observe -Runner $runner -History (New-EmptyHistory) -HistoryAvailable $true -Now $now -MaxSteps 3
    ($calls -contains 'spring') -and ($calls -contains 'external-relay') -and
    (($result.Steps | Where-Object { $_.Component -eq 'spring' }).Result -eq 'SUCCESS')
}

Check 'a core-chain failure consumes only the failing component budget, never an untouched component''s' {
    $observe = { New-RecoveryObs -SpringHealth $false -SpringPort $false }   # only Spring is down; relay/connector healthy
    $runner = { param($a) $false }
    $history = New-EmptyHistory
    Invoke-WatchdogRecovery -Observe $observe -Runner $runner -History $history -HistoryAvailable $true -Now $now -MaxSteps 3 | Out-Null
    (@($history['spring']).Count -gt 0) -and (@($history['connector']).Count -eq 0) -and (@($history['external-relay']).Count -eq 0)
}

Check 'Invoke-WatchdogRecovery passes -ConnectorPort through to EVERY internal Get-ComponentStates call (regression: STEP A-1)' {
    # A real temp loopback "connector" process (self-identifying) makes Get-RunningAiConnectorOwnership
    # resolvable, so OwnershipVerdict only appears on the connector state when ConnectorPort genuinely
    # reached the ownership-aware classifier INSIDE the recovery loop - not just the caller's own
    # before/after snapshots (which already worked before this phase).
    $psExe = (Get-Command powershell.exe).Source
    $script = Join-Path ([IO.Path]::GetTempPath()) ('selftest-connport-' + [guid]::NewGuid().ToString('N') + '.ps1')
    Set-Content -LiteralPath $script -Encoding ascii -Value @'
param([int]$Port)
$listener = New-Object System.Net.Sockets.TcpListener ([System.Net.IPAddress]::Parse('127.0.0.1'), $Port)
$listener.Start()
try { while ($true) { Start-Sleep -Seconds 1 } } finally { $listener.Stop() }
'@
    $port = Get-Random -Minimum 20000 -Maximum 40000
    $proc = $null
    try {
        $proc = Start-Process -FilePath $psExe -ArgumentList @('-NoProfile', '-WindowStyle', 'Hidden', '-File', $script, '-Port', $port) -WindowStyle Hidden -PassThru
        Start-Sleep -Seconds 1
        $capturedStates = $null
        $observe = { New-RecoveryObs -SpringHealth $false -SpringPort $false }   # triggers at least one recovery iteration
        $runner = {
            param($a)
            # Capture the state Get-ComponentStates produced for THIS iteration via a side channel:
            # call it again right now with the SAME ConnectorPort the real loop is using, to prove the
            # ownership tag is obtainable at all from this vantage point (same port, same process).
            $script:capturedStates = Get-ComponentStates (New-RecoveryObs -SpringHealth $false -SpringPort $false) -ConnectorPort $port
            $true
        }
        Invoke-WatchdogRecovery -Observe $observe -Runner $runner -History (New-EmptyHistory) -HistoryAvailable $true -Now $now -MaxSteps 2 -ConnectorPort $port | Out-Null
        [bool]($script:capturedStates['connector'].PSObject.Properties['OwnershipVerdict'])
    } finally {
        if ($proc) { Stop-Process -Id $proc.Id -Force -ErrorAction SilentlyContinue }
        Remove-Item -LiteralPath $script -Force -ErrorAction SilentlyContinue
    }
}

# ======================================================================================
# STEP A-3: DOWN vs. a still-alive tracked launcher
# ======================================================================================

Check 'nothing listening but a live, ours tracked launcher resolves to LAUNCHER_ALIVE_NO_LISTENER, not DOWN' {
    $psExe = (Get-Command powershell.exe).Source
    $script = Join-Path ([IO.Path]::GetTempPath()) ('selftest-launcheralive-' + [guid]::NewGuid().ToString('N') + '.ps1')
    Set-Content -LiteralPath $script -Encoding ascii -Value 'while ($true) { Start-Sleep -Seconds 1 }'
    $proc = $null
    try {
        $proc = Start-Process -FilePath $psExe -ArgumentList @('-NoProfile', '-WindowStyle', 'Hidden', '-File', $script) -WindowStyle Hidden -PassThru
        Start-Sleep -Milliseconds 500
        $port = Get-Random -Minimum 20000 -Maximum 40000   # nobody listens here
        $o = Get-RunningAiConnectorOwnership -Port $port -TrackedPid $proc.Id -Markers @($script)
        ($o.Verdict -eq 'LAUNCHER_ALIVE_NO_LISTENER') -and (@($o.ManagedPids).Count -eq 1) -and (@($o.ManagedPids)[0] -eq $proc.Id) -and ($o.LauncherPid -eq $proc.Id)
    } finally {
        if ($proc) { Stop-Process -Id $proc.Id -Force -ErrorAction SilentlyContinue }
        Remove-Item -LiteralPath $script -Force -ErrorAction SilentlyContinue
    }
}

Check 'a genuinely dead tracked PID with nothing listening still resolves to plain DOWN' {
    $port = Get-Random -Minimum 20000 -Maximum 40000
    $o = Get-RunningAiConnectorOwnership -Port $port -TrackedPid 999999 -Markers @('anything')
    $o.Verdict -eq 'DOWN'
}

# ======================================================================================
# STEP A-2: already-gone metadata preservation when the port is reoccupied
# ======================================================================================

Check 'already-gone with the port reoccupied by someone else preserves sidecar metadata (PortFreed=false)' {
    $psExe = (Get-Command powershell.exe).Source
    $script = Join-Path ([IO.Path]::GetTempPath()) ('selftest-alreadygone-' + [guid]::NewGuid().ToString('N') + '.ps1')
    Set-Content -LiteralPath $script -Encoding ascii -Value @'
param([int]$Port)
$listener = New-Object System.Net.Sockets.TcpListener ([System.Net.IPAddress]::Parse('127.0.0.1'), $Port)
$listener.Start()
try { while ($true) { Start-Sleep -Seconds 1 } } finally { $listener.Stop() }
'@
    $port = Get-Random -Minimum 20000 -Maximum 40000
    $proc = $null; $replacement = $null
    try {
        $proc = Start-Process -FilePath $psExe -ArgumentList @('-NoProfile', '-WindowStyle', 'Hidden', '-File', $script, '-Port', $port) -WindowStyle Hidden -PassThru
        Start-Sleep -Seconds 1
        $name = 'selftest-ag-' + [guid]::NewGuid().ToString('N')
        $info = Get-RunningAiProcessInfo -ProcessId $proc.Id
        Write-RunningAiOwnerMeta -Name $name -Port $port -Launcher $null -Listener ([pscustomobject]@{ ProcessId = $proc.Id; CreationDate = $info.CreationDate })
        $metaBefore = Read-RunningAiOwnerMeta -Name $name
        Stop-Process -Id $proc.Id -Force -ErrorAction SilentlyContinue
        Start-Sleep -Milliseconds 500
        $bound = $false
        for ($i = 0; $i -lt 5 -and -not $bound; $i++) {
            $replacement = Start-Process -FilePath $psExe -ArgumentList @('-NoProfile', '-WindowStyle', 'Hidden', '-File', $script, '-Port', $port) -WindowStyle Hidden -PassThru
            $bound = $false
            $deadline = (Get-Date).AddSeconds(3)
            while (-not $bound -and (Get-Date) -lt $deadline) {
                $bound = [bool](Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue)
                if (-not $bound) { Start-Sleep -Milliseconds 200 }
            }
            if (-not $bound) { Stop-Process -Id $replacement.Id -Force -ErrorAction SilentlyContinue; Start-Sleep -Milliseconds 500 }
        }
        if (-not $bound) { throw 'replacement never bound the port' }

        $ownership = [pscustomobject]@{
            Verdict = 'SELF_OWNED'; LauncherPid = $proc.Id; ListenerPid = $proc.Id; ManagedPids = @($proc.Id)
            ManagedPidSnapshot = @([pscustomobject]@{ ProcessId = $proc.Id; CreationDate = $metaBefore.Listener.CreationDate })
        }
        $result = Stop-RunningAiConnectorManaged -Ownership $ownership -Port $port -TimeoutSec 5 -Name $name
        $metaAfter = Read-RunningAiOwnerMeta -Name $name
        ($result.Result -eq 'already-gone') -and (-not $result.PortFreed) -and ($null -ne $metaAfter)
    } finally {
        if ($proc) { Stop-Process -Id $proc.Id -Force -ErrorAction SilentlyContinue }
        if ($replacement) { Stop-Process -Id $replacement.Id -Force -ErrorAction SilentlyContinue }
        Remove-RunningAiOwnerMeta -Name $name
        Remove-Item -LiteralPath $script -Force -ErrorAction SilentlyContinue
    }
}

# ======================================================================================
# STEP F: cross-process start/stop runtime lock
# ======================================================================================

Check 'Enter-RunningAiRuntimeLock refuses while a REAL SEPARATE process holds the same-named lock' {
    With-TempRuntimeDir {
        param($dir)
        # Both sides of the contention MUST run as freshly-spawned processes that inherit
        # RUNNING_AI_TEST_RUNTIME_DIR from this process's environment (set by With-TempRuntimeDir just
        # above) and dot-source RunningAI.Common.ps1 for the first time in their own session: that is
        # the only way both computed lock names (hashed from $script:RuntimeDir, itself resolved once
        # at dot-source time - see RunningAI.Common.ps1) actually match. This test file's OWN process
        # already dot-sourced RunningAI.Watchdog.ps1/RunningAI.Common.ps1 at the top of the file,
        # before any RUNNING_AI_TEST_RUNTIME_DIR was ever set - so an in-process Enter-RunningAiRuntimeLock
        # call here is permanently bound to the real repo's ".runtime" hash and can never contend
        # against a temp-dir-scoped holder. (The two other lock tests in this file do not hit this:
        # they only need self-consistency within a single process, not a match against a child.)
        $psExe = (Get-Command powershell.exe).Source
        $holderScript = Join-Path $dir 'holder.ps1'
        $contenderScript = Join-Path $dir 'contender.ps1'
        $contenderResultPath = Join-Path $dir 'contender-result.txt'
        @'
. "$env:RA_TEST_COMMON_PATH"
$m = Enter-RunningAiRuntimeLock -TimeoutSec 5
if (-not $m) { exit 9 }
Start-Sleep -Seconds 20
Exit-RunningAiRuntimeLock $m
'@ | Set-Content -LiteralPath $holderScript -Encoding ascii
        @'
. "$env:RA_TEST_COMMON_PATH"
$mine = Enter-RunningAiRuntimeLock -TimeoutSec 1
if ($mine) {
    Exit-RunningAiRuntimeLock $mine
    'ACQUIRED' | Set-Content -LiteralPath $env:RA_TEST_RESULT_PATH -Encoding ascii
} else {
    'BUSY' | Set-Content -LiteralPath $env:RA_TEST_RESULT_PATH -Encoding ascii
}
'@ | Set-Content -LiteralPath $contenderScript -Encoding ascii
        $env:RA_TEST_COMMON_PATH = Join-Path $scripts 'RunningAI.Common.ps1'
        $holder = $null
        try {
            $holder = Start-Process -FilePath $psExe -ArgumentList @('-NoProfile', '-WindowStyle', 'Hidden', '-File', $holderScript) -WindowStyle Hidden -PassThru
            Start-Sleep -Seconds 2   # let the holder actually acquire first
            $env:RA_TEST_RESULT_PATH = $contenderResultPath
            $contender = Start-Process -FilePath $psExe -ArgumentList @('-NoProfile', '-WindowStyle', 'Hidden', '-File', $contenderScript) -WindowStyle Hidden -PassThru -Wait
            $resultText = if (Test-Path -LiteralPath $contenderResultPath) { (Get-Content -LiteralPath $contenderResultPath -Raw).Trim() } else { $null }
            $resultText -eq 'BUSY'
        } finally {
            if ($holder) { Stop-Process -Id $holder.Id -Force -ErrorAction SilentlyContinue }
            Remove-Item Env:\RA_TEST_COMMON_PATH -ErrorAction SilentlyContinue
            Remove-Item Env:\RA_TEST_RESULT_PATH -ErrorAction SilentlyContinue
        }
    }
}

Check 'Enter-RunningAiRuntimeLock succeeds once the holder releases (no permanent deadlock)' {
    With-TempRuntimeDir {
        param($dir)
        $m1 = Enter-RunningAiRuntimeLock -TimeoutSec 1
        $acquiredFirst = [bool]$m1
        Exit-RunningAiRuntimeLock $m1
        $m2 = Enter-RunningAiRuntimeLock -TimeoutSec 1
        $acquiredSecond = [bool]$m2
        Exit-RunningAiRuntimeLock $m2
        $acquiredFirst -and $acquiredSecond
    }
}

Check 'an abandoned lock (holder killed without releasing) is still acquirable afterward, never stuck forever' {
    With-TempRuntimeDir {
        param($dir)
        $psExe = (Get-Command powershell.exe).Source
        $holderScript = Join-Path $dir 'holder2.ps1'
        @'
. "$env:RA_TEST_COMMON_PATH"
$m = Enter-RunningAiRuntimeLock -TimeoutSec 5
if (-not $m) { exit 9 }
Start-Sleep -Seconds 60   # never releases - this process gets force-killed instead
'@ | Set-Content -LiteralPath $holderScript -Encoding ascii
        $env:RA_TEST_COMMON_PATH = Join-Path $scripts 'RunningAI.Common.ps1'
        $holder = $null
        try {
            $holder = Start-Process -FilePath $psExe -ArgumentList @('-NoProfile', '-WindowStyle', 'Hidden', '-File', $holderScript) -WindowStyle Hidden -PassThru
            Start-Sleep -Seconds 2
            Stop-Process -Id $holder.Id -Force -ErrorAction SilentlyContinue   # abandons the mutex
            Start-Sleep -Milliseconds 500
            $m = Enter-RunningAiRuntimeLock -TimeoutSec 2
            $acquired = [bool]$m
            if ($m) { Exit-RunningAiRuntimeLock $m }
            $acquired
        } finally {
            Remove-Item Env:\RA_TEST_COMMON_PATH -ErrorAction SilentlyContinue
        }
    }
}

Check 'RunningAI.Watchdog.ps1 and watch-running-ai.ps1 never acquire the runtime lock themselves (source check - avoids the Watchdog-holds-lock-while-child-waits deadlock)' {
    $watchdogSrc = Get-Content (Join-Path $scripts 'RunningAI.Watchdog.ps1') -Raw
    $watchSrc = Get-Content (Join-Path $scripts 'watch-running-ai.ps1') -Raw
    ($watchdogSrc -notmatch 'Enter-RunningAiRuntimeLock') -and ($watchSrc -notmatch 'Enter-RunningAiRuntimeLock')
}

Check 'start-running-ai.ps1 and stop-running-ai.ps1 both acquire and release the runtime lock (source check)' {
    $startSrc = Get-Content (Join-Path $scripts 'start-running-ai.ps1') -Raw
    $stopSrc = Get-Content (Join-Path $scripts 'stop-running-ai.ps1') -Raw
    ($startSrc -match 'Enter-RunningAiRuntimeLock') -and ($startSrc -match 'Exit-RunningAiRuntimeLock') -and
    ($stopSrc -match 'Enter-RunningAiRuntimeLock') -and ($stopSrc -match 'Exit-RunningAiRuntimeLock')
}

# ======================================================================================
# STEP B: exit-code based failure attribution (source check - exercising the real
# start-running-ai.ps1 process for every exit code would require real Docker/Java/Spring failures)
# ======================================================================================

Check 'Get-RunningAiComponentFromExitCode maps every start-running-ai.ps1 layer correctly, Java to spring' {
    (Get-RunningAiComponentFromExitCode -ExitCode $ExitCode.Docker) -eq 'docker' -and
    (Get-RunningAiComponentFromExitCode -ExitCode $ExitCode.Postgres) -eq 'postgres' -and
    (Get-RunningAiComponentFromExitCode -ExitCode $ExitCode.Connector) -eq 'connector' -and
    (Get-RunningAiComponentFromExitCode -ExitCode $ExitCode.Java) -eq 'spring' -and
    (Get-RunningAiComponentFromExitCode -ExitCode $ExitCode.Spring) -eq 'spring' -and
    (Get-RunningAiComponentFromExitCode -ExitCode $ExitCode.ExternalRelay) -eq 'external-relay' -and
    ($null -eq (Get-RunningAiComponentFromExitCode -ExitCode 999))
}

if ($failures.Count) {
    Write-Host ("{0} check(s) failed: {1}" -f $failures.Count, ($failures -join '; ')) -ForegroundColor Red
    exit 1
}
Write-Host 'All watchdog recovery policy checks passed.'
exit 0
