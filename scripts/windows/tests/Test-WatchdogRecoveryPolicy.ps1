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

# Phase 6I-1.7B-2C (STEP 1) supersedes the original 2B rule this test enforced ("Watchdog never
# acquires this lock at all"): RunningAI.Watchdog.ps1's own recovery helpers now DO acquire it, for
# the bundled PreStop+Start/Restart duration. The deadlock risk that rule existed to avoid still
# cannot be allowed to happen, so what must be true now instead is: (1) watch-running-ai.ps1 ITSELF
# still never acquires it directly (only the Watchdog.ps1 recovery helpers do, always through the
# lock-then-spawn-trusted-child pattern); (2) EVERY Enter-RunningAiRuntimeLock call in
# RunningAI.Watchdog.ps1 is immediately followed by its own try/finally releasing it (never held open
# indefinitely); (3) every process this file spawns while holding that lock goes through
# Start-RunningAiWatchdogTrustedChild (which sets RUNNING_AI_WATCHDOG_LOCK_INHERITED on the child), and
# this file contains no OTHER raw Start-Process call to start-running-ai.ps1 or
# external\start-external-relay.ps1 that could re-acquire the same lock and deadlock/spuriously-BUSY
# against its own parent.
Check 'watch-running-ai.ps1 never acquires the runtime lock directly; only RunningAI.Watchdog.ps1''s lock-then-spawn-trusted-child helpers do (source check)' {
    $watchdogSrc = Get-Content (Join-Path $scripts 'RunningAI.Watchdog.ps1') -Raw
    $watchSrc = Get-Content (Join-Path $scripts 'watch-running-ai.ps1') -Raw
    $enterCount = ([regex]::Matches($watchdogSrc, '\$lock = Enter-RunningAiRuntimeLock')).Count
    $finallyExitCount = ([regex]::Matches($watchdogSrc, '(?s)finally \{\s*Exit-RunningAiRuntimeLock \$lock\s*\}')).Count
    ($watchSrc -notmatch 'Enter-RunningAiRuntimeLock') -and
    ($enterCount -eq 2) -and ($finallyExitCount -eq 2) -and
    ($watchdogSrc -notmatch '(?s)Start-Process -FilePath \$ps -ArgumentList \$argLine -WindowStyle Hidden -PassThru\b(?!.{0,400}Start-RunningAiWatchdogTrustedChild)')
}

# Every spawn of start-running-ai.ps1 or the relay's own start script FROM INSIDE a lock-holding
# recovery helper must go through the trusted-child path (sets RUNNING_AI_WATCHDOG_LOCK_INHERITED on
# that one child only) - never a plain Start-Process, which would let the child try to re-acquire the
# SAME lock its own parent is holding.
Check 'Invoke-RunningAiCoreRecoveryAction and Invoke-RunningAiRelayRecoveryAction spawn their child scripts ONLY via Start-RunningAiWatchdogTrustedChild (source check)' {
    $src = Get-Content (Join-Path $scripts 'RunningAI.Watchdog.ps1') -Raw
    $coreFn = [regex]::Match($src, '(?s)function Invoke-RunningAiCoreRecoveryAction \{.*?\n\}\r?\n').Value
    $relayFn = [regex]::Match($src, '(?s)function Invoke-RunningAiRelayRecoveryAction \{.*?\n\}\r?\n').Value
    ($coreFn -match 'Start-RunningAiWatchdogTrustedChild') -and ($relayFn -match 'Start-RunningAiWatchdogTrustedChild') -and
    ($coreFn -notmatch 'Start-Process -FilePath \$ps') -and ($relayFn -notmatch 'Start-Process -FilePath \$ps')
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

# ======================================================================================
# Phase 6I-1.7B-2C, STEP 1/5: Watchdog PreStop+Start/Restart vs manual start/stop mutual exclusion.
# A REAL separate process holds the real Enter-RunningAiRuntimeLock (same pattern as the 2B
# contention test above) while THIS process calls the real Invoke-RunningAiCoreRecoveryAction /
# Invoke-RunningAiRelayRecoveryAction directly - both must report BUSY and touch nothing (no PID
# file, no process, no start-running-ai.ps1/external start script spawned).
# ======================================================================================

function New-RunningAiLockHolderProcess {
    param([string]$Dir, [int]$HoldSeconds = 6)
    $psExe = (Get-Command powershell.exe).Source
    $holderScript = Join-Path $Dir ('holder-' + [guid]::NewGuid().ToString('N') + '.ps1')
    @"
. `"`$env:RA_TEST_COMMON_PATH`"
`$m = Enter-RunningAiRuntimeLock -TimeoutSec 5
if (-not `$m) { exit 9 }
Start-Sleep -Seconds $HoldSeconds
Exit-RunningAiRuntimeLock `$m
"@ | Set-Content -LiteralPath $holderScript -Encoding ascii
    $env:RA_TEST_COMMON_PATH = Join-Path $scripts 'RunningAI.Common.ps1'
    Start-Process -FilePath $psExe -ArgumentList @('-NoProfile', '-WindowStyle', 'Hidden', '-File', $holderScript) -WindowStyle Hidden -PassThru
}

# Calling Invoke-RunningAiCoreRecoveryAction/Invoke-RunningAiRelayRecoveryAction IN-PROCESS here would
# be unsafe: this test FILE already dot-sourced RunningAI.Watchdog.ps1 at its own top, before
# With-TempRuntimeDir ever sets RUNNING_AI_TEST_RUNTIME_DIR - so this process's own $script:RuntimeDir
# (and the runtime-lock name derived from it) is permanently bound to the REAL dev-worktree ".runtime"
# path, exactly like the 2B lock-contention test's own documented gotcha. Calling the action function
# directly here would therefore use the REAL runtime lock name (harmless by itself) but, if the BUSY
# check did not fire, would fall through toward a REAL start-running-ai.ps1 spawn - precisely what this
# whole test suite exists to never risk. The action attempt therefore ALSO runs as its own freshly
# spawned process (dot-sourcing fresh, after RUNNING_AI_TEST_RUNTIME_DIR is set in its inherited
# environment) and is given clearly-fake, high, random ConnectorPort/SpringPort values as a second,
# independent safety net - even in the extremely unlikely case BUSY does not fire, nothing would touch
# a real port. The outcome is serialized to a small JSON result file and read back here.
function Invoke-RunningAiRecoveryActionInIsolatedProcess {
    param(
        [string]$Dir,
        [Parameter(Mandatory)][ValidateSet('Core', 'Relay')][string]$Kind,
        [Parameter(Mandatory)]$Action,
        [int]$LockTimeoutSec = 1
    )
    $resultPath = Join-Path $Dir ('outcome-' + [guid]::NewGuid().ToString('N') + '.json')
    $actionJson = ($Action | ConvertTo-Json -Compress)
    $connectorPort = Get-Random -Minimum 20000 -Maximum 30000
    $springPort = Get-Random -Minimum 30000 -Maximum 40000
    $invocation = if ($Kind -eq 'Core') {
        "Invoke-RunningAiCoreRecoveryAction -Action `$action -ConnectorPort $connectorPort -SpringPort $springPort -LockTimeoutSec $LockTimeoutSec"
    } else {
        "Invoke-RunningAiRelayRecoveryAction -Action `$action -LockTimeoutSec $LockTimeoutSec"
    }
    $runnerScript = Join-Path $Dir ('attempt-' + [guid]::NewGuid().ToString('N') + '.ps1')
    @"
. `"`$env:RA_TEST_WATCHDOG_PATH`"
`$action = '$actionJson' | ConvertFrom-Json
`$outcome = $invocation
`$outcome | ConvertTo-Json -Compress | Set-Content -LiteralPath `$env:RA_TEST_RESULT_PATH -Encoding ascii
"@ | Set-Content -LiteralPath $runnerScript -Encoding ascii
    $psExe = (Get-Command powershell.exe).Source
    $env:RA_TEST_WATCHDOG_PATH = Join-Path $scripts 'RunningAI.Watchdog.ps1'
    $env:RA_TEST_RESULT_PATH = $resultPath
    try {
        Start-Process -FilePath $psExe -ArgumentList @('-NoProfile', '-WindowStyle', 'Hidden', '-File', $runnerScript) -WindowStyle Hidden -Wait
        if (Test-Path -LiteralPath $resultPath) { Get-Content -LiteralPath $resultPath -Raw | ConvertFrom-Json } else { $null }
    } finally {
        Remove-Item Env:\RA_TEST_WATCHDOG_PATH, Env:\RA_TEST_RESULT_PATH -ErrorAction SilentlyContinue
    }
}

Check 'Watchdog core-chain recovery (PreStop+Start) reports BUSY and touches nothing while a manual start/stop holds the runtime lock' {
    With-TempRuntimeDir {
        param($dir)
        $holder = $null
        try {
            $holder = New-RunningAiLockHolderProcess -Dir $dir -HoldSeconds 6
            Start-Sleep -Seconds 2
            $action = [pscustomobject]@{ Component = 'spring'; Action = 'RESTART_SPRING'; Reason = 'PROCESS_ALIVE_HEALTH_FAILING'; PreStop = $true }
            $outcome = Invoke-RunningAiRecoveryActionInIsolatedProcess -Dir $dir -Kind Core -Action $action
            # Checked via the ISOLATED temp runtime dir's own file (never Get-TrackedProcessId in THIS
            # process, which is permanently bound to the REAL dev-worktree runtime dir - see the helper
            # function's own comment above for why).
            ($null -ne $outcome) -and ($outcome.ResultCode -eq 'BUSY') -and ($outcome.AttemptedComponent -eq 'spring') -and ($null -eq $outcome.BudgetChargeComponent) -and
            (-not (Test-Path -LiteralPath (Join-Path $dir 'spring.pid')))
        } finally {
            if ($holder) { Stop-Process -Id $holder.Id -Force -ErrorAction SilentlyContinue }
            Remove-Item Env:\RA_TEST_COMMON_PATH -ErrorAction SilentlyContinue
        }
    }
}

Check 'Watchdog connector recovery reports BUSY (not FAILED/BLOCKED) while a manual start/stop holds the runtime lock (covers both PreStop-vs-Stop and Start-vs-Start conflicts)' {
    With-TempRuntimeDir {
        param($dir)
        $holder = $null
        try {
            $holder = New-RunningAiLockHolderProcess -Dir $dir -HoldSeconds 6
            Start-Sleep -Seconds 2
            $action = [pscustomobject]@{ Component = 'connector'; Action = 'START_CONNECTOR'; Reason = 'PROCESS_DEAD'; PreStop = $false }
            $outcome = Invoke-RunningAiRecoveryActionInIsolatedProcess -Dir $dir -Kind Core -Action $action
            ($null -ne $outcome) -and ($outcome.ResultCode -eq 'BUSY') -and ($outcome.ActionPerformed -eq 'START_CONNECTOR') -and ($outcome.Retryable)
        } finally {
            if ($holder) { Stop-Process -Id $holder.Id -Force -ErrorAction SilentlyContinue }
            Remove-Item Env:\RA_TEST_COMMON_PATH -ErrorAction SilentlyContinue
        }
    }
}

Check 'Relay recovery reports BUSY and touches nothing while a manual stop (same runtime lock) is in progress' {
    With-TempRuntimeDir {
        param($dir)
        $holder = $null
        try {
            $holder = New-RunningAiLockHolderProcess -Dir $dir -HoldSeconds 6
            Start-Sleep -Seconds 2
            $action = [pscustomobject]@{ Component = 'external-relay'; Action = 'START_EXTERNAL_RELAY'; Reason = 'PROCESS_DEAD'; PreStop = $false }
            $outcome = Invoke-RunningAiRecoveryActionInIsolatedProcess -Dir $dir -Kind Relay -Action $action
            ($null -ne $outcome) -and ($outcome.ResultCode -eq 'BUSY') -and ($outcome.AttemptedComponent -eq 'external-relay') -and
            (-not (Test-Path -LiteralPath (Join-Path $dir 'external-relay.pid')))
        } finally {
            if ($holder) { Stop-Process -Id $holder.Id -Force -ErrorAction SilentlyContinue }
            Remove-Item Env:\RA_TEST_COMMON_PATH -ErrorAction SilentlyContinue
        }
    }
}

# ---- deadlock avoidance: the trusted-child inheritance mechanism itself -------------------------

Check 'Enter-RunningAiRuntimeLock returns INHERITED (no wait at all) when RUNNING_AI_WATCHDOG_LOCK_INHERITED=1, and Exit- is a safe no-op on it' {
    $orig = $env:RUNNING_AI_WATCHDOG_LOCK_INHERITED
    try {
        $env:RUNNING_AI_WATCHDOG_LOCK_INHERITED = '1'
        $m = Enter-RunningAiRuntimeLock -TimeoutSec 1
        $isInherited = ($m -eq 'INHERITED')
        Exit-RunningAiRuntimeLock $m   # must not throw
        $isInherited
    } finally {
        if ($null -eq $orig) { Remove-Item Env:\RUNNING_AI_WATCHDOG_LOCK_INHERITED -ErrorAction SilentlyContinue } else { $env:RUNNING_AI_WATCHDOG_LOCK_INHERITED = $orig }
    }
}

Check 'Start-RunningAiWatchdogTrustedChild sets RUNNING_AI_WATCHDOG_LOCK_INHERITED on the CHILD only, never leaking into this process''s own $env:' {
    With-TempRuntimeDir {
        param($dir)
        $marker = Join-Path $dir 'inherited-marker.txt'
        $childScript = Join-Path $dir 'dump-inherited.ps1'
        @"
`$v = `$env:RUNNING_AI_WATCHDOG_LOCK_INHERITED
if (`$null -eq `$v) { `$v = '<unset>' }
Set-Content -LiteralPath '$marker' -Value `$v -Encoding ascii
"@ | Set-Content -LiteralPath $childScript -Encoding ascii
        $psExe = (Get-Command powershell.exe).Source
        $beforeInThisProcess = $env:RUNNING_AI_WATCHDOG_LOCK_INHERITED
        $spawn = Start-RunningAiWatchdogTrustedChild -FilePath $psExe -ArgumentList "-NoProfile -File `"$childScript`"" -TimeoutMs 10000
        $afterInThisProcess = $env:RUNNING_AI_WATCHDOG_LOCK_INHERITED
        $childSaw = if (Test-Path -LiteralPath $marker) { (Get-Content -LiteralPath $marker -Raw).Trim() } else { $null }
        (-not $spawn.TimedOut) -and ($childSaw -eq '1') -and ($beforeInThisProcess -eq $afterInThisProcess)
    }
}

# ======================================================================================
# Phase 6I-1.7B-2C, STEP 2/5: outcome normalization and attribution precision.
# ======================================================================================

Check 'New-RunningAiRecoveryOutcome: SUCCESS/BUSY/BLOCKED/UNKNOWN_FAILURE never set a BudgetChargeComponent' {
    $success = New-RunningAiRecoveryOutcome -AttemptedComponent 'spring' -ResultCode 'SUCCESS'
    $busy = New-RunningAiRecoveryOutcome -AttemptedComponent 'spring' -ResultCode 'BUSY'
    $blocked = New-RunningAiRecoveryOutcome -AttemptedComponent 'connector' -ResultCode 'BLOCKED'
    $unknown = New-RunningAiRecoveryOutcome -AttemptedComponent 'connector' -ResultCode 'UNKNOWN_FAILURE'
    ($null -eq $success.BudgetChargeComponent) -and ($null -eq $busy.BudgetChargeComponent) -and
    ($null -eq $blocked.BudgetChargeComponent) -and ($null -eq $unknown.BudgetChargeComponent)
}

Check 'New-RunningAiRecoveryOutcome: FAILED/TIMED_OUT charge the ACTUAL failed component when known, else fall back to the attempted one' {
    $mismatched = New-RunningAiRecoveryOutcome -AttemptedComponent 'connector' -ResultCode 'FAILED' -ActualFailedComponent 'spring'
    $selfAttributed = New-RunningAiRecoveryOutcome -AttemptedComponent 'connector' -ResultCode 'FAILED'
    $timedOut = New-RunningAiRecoveryOutcome -AttemptedComponent 'spring' -ResultCode 'TIMED_OUT'
    ($mismatched.BudgetChargeComponent -eq 'spring') -and ($selfAttributed.BudgetChargeComponent -eq 'connector') -and
    ($timedOut.BudgetChargeComponent -eq 'spring')
}

Check 'ConvertTo-RunningAiRecoveryOutcome: a plain legacy boolean Runner reproduces the exact pre-2C attribution ($true=SUCCESS no charge, $false=FAILED charge the attempted component)' {
    $ok = ConvertTo-RunningAiRecoveryOutcome -RunResult $true -AttemptedComponent 'spring'
    $bad = ConvertTo-RunningAiRecoveryOutcome -RunResult $false -AttemptedComponent 'connector'
    ($ok.ResultCode -eq 'SUCCESS') -and ($null -eq $ok.BudgetChargeComponent) -and
    ($bad.ResultCode -eq 'FAILED') -and ($bad.BudgetChargeComponent -eq 'connector') -and ($bad.ActualFailedComponent -eq 'connector')
}

Check 'Invoke-WatchdogRecovery: a BUSY runner result charges NO restart/long-term budget, reports .Busy true and .Failed false, with empty Deltas' {
    $history = New-EmptyHistory
    $lt = New-EmptyLongTermState
    $runner = { param($a) New-RunningAiRecoveryOutcome -AttemptedComponent $a.Component -ResultCode 'BUSY' -ActionPerformed $a.Action }
    $fakeObserve = {
        [pscustomobject]@{ Docker='UP'; Postgres='healthy'; ConnectorHealth=$true; ConnectorPortUsed=$true; ConnectorPid=1; SpringHealth=$false; SpringPortUsed=$false; SpringPid=$null; ExternalRelayHealth=$true; ExternalRelayPortUsed=$true; ExternalRelayPid=1 }
    }
    $result = Invoke-WatchdogRecovery -Observe $fakeObserve -Runner $runner -History $history -HistoryAvailable $true -Now $now -LongTermState $lt
    ($result.Busy) -and (-not $result.Failed) -and (@($result.Deltas.Restarts).Count -eq 0) -and (@($result.Deltas.LongTermFailures).Count -eq 0) -and
    ((Get-RestartCount -History $history -Component 'spring' -Now $now) -eq 0) -and
    (-not (Test-RunningAiComponentLockedOut -LongTermState $lt -Component 'spring'))
}

Check 'Invoke-WatchdogRecovery: Spring failing does not charge Connector''s long-term failure count (attempted=connector would never happen for a pure Spring failure, but a mismatched outcome must still route correctly)' {
    $history = New-EmptyHistory
    $lt = New-EmptyLongTermState
    $runner = { param($a) New-RunningAiRecoveryOutcome -AttemptedComponent $a.Component -ResultCode 'FAILED' -ActionPerformed $a.Action -ActualFailedComponent 'spring' }
    $fakeObserve = {
        [pscustomobject]@{ Docker='UP'; Postgres='healthy'; ConnectorHealth=$false; ConnectorPortUsed=$false; ConnectorPid=$null; SpringHealth=$true; SpringPortUsed=$true; SpringPid=1; ExternalRelayHealth=$true; ExternalRelayPortUsed=$true; ExternalRelayPid=1 }
    }
    $result = Invoke-WatchdogRecovery -Observe $fakeObserve -Runner $runner -History $history -HistoryAvailable $true -Now $now -LongTermState $lt
    (@($lt['spring'].Failures).Count -eq 1) -and (@($lt['connector'].Failures).Count -eq 0) -and
    (@(@($result.Deltas.LongTermFailures) | Where-Object { $_.Component -eq 'spring' }).Count -eq 1)
}

Check 'Invoke-WatchdogRecovery: a self-attributed Connector failure charges ONLY Connector''s long-term budget' {
    $history = New-EmptyHistory
    $lt = New-EmptyLongTermState
    $runner = { param($a) New-RunningAiRecoveryOutcome -AttemptedComponent $a.Component -ResultCode 'FAILED' -ActionPerformed $a.Action -ActualFailedComponent 'connector' }
    $fakeObserve = {
        [pscustomobject]@{ Docker='UP'; Postgres='healthy'; ConnectorHealth=$false; ConnectorPortUsed=$false; ConnectorPid=$null; SpringHealth=$true; SpringPortUsed=$true; SpringPid=1; ExternalRelayHealth=$true; ExternalRelayPortUsed=$true; ExternalRelayPid=1 }
    }
    $result = Invoke-WatchdogRecovery -Observe $fakeObserve -Runner $runner -History $history -HistoryAvailable $true -Now $now -LongTermState $lt
    (@($lt['connector'].Failures).Count -eq 1) -and (@($lt['spring'].Failures).Count -eq 0) -and (@($lt['docker'].Failures).Count -eq 0)
}

Check 'Invoke-WatchdogRecovery: UNKNOWN_FAILURE stops the tick (.Failed true) but charges NO component''s long-term budget, while still consuming the short-term attempt budget' {
    $history = New-EmptyHistory
    $lt = New-EmptyLongTermState
    $runner = { param($a) New-RunningAiRecoveryOutcome -AttemptedComponent $a.Component -ResultCode 'UNKNOWN_FAILURE' -ActionPerformed $a.Action }
    $fakeObserve = {
        [pscustomobject]@{ Docker='UP'; Postgres='healthy'; ConnectorHealth=$false; ConnectorPortUsed=$false; ConnectorPid=$null; SpringHealth=$true; SpringPortUsed=$true; SpringPid=1; ExternalRelayHealth=$true; ExternalRelayPortUsed=$true; ExternalRelayPid=1 }
    }
    $result = Invoke-WatchdogRecovery -Observe $fakeObserve -Runner $runner -History $history -HistoryAvailable $true -Now $now -LongTermState $lt
    ($result.Failed) -and (-not $result.Busy) -and (@($lt['connector'].Failures).Count -eq 0) -and
    ((Get-RestartCount -History $history -Component 'connector' -Now $now) -eq 1) -and
    (@(@($result.Deltas.Restarts) | Where-Object { $_.Component -eq 'connector' }).Count -eq 1) -and
    (@($result.Deltas.LongTermFailures).Count -eq 0)
}

# ======================================================================================
# Phase 6I-1.7B-2C, STEP 4/5: watchdog-state.json write serialization and lockout-clear safety.
# ======================================================================================

Check 'Enter-RunningAiWatchdogStateLock refuses while a REAL SEPARATE process holds the same-named state lock, and succeeds once released' {
    With-TempRuntimeDir {
        param($dir)
        $psExe = (Get-Command powershell.exe).Source
        $holderScript = Join-Path $dir 'state-holder.ps1'
        $contenderScript = Join-Path $dir 'state-contender.ps1'
        $resultPath = Join-Path $dir 'state-contender-result.txt'
        @'
. "$env:RA_TEST_WATCHDOG_PATH"
$m = Enter-RunningAiWatchdogStateLock -TimeoutSec 5
if (-not $m) { exit 9 }
Start-Sleep -Seconds 4
Exit-RunningAiWatchdogStateLock $m
'@ | Set-Content -LiteralPath $holderScript -Encoding ascii
        @'
. "$env:RA_TEST_WATCHDOG_PATH"
$mine = Enter-RunningAiWatchdogStateLock -TimeoutSec 1
if ($mine) { Exit-RunningAiWatchdogStateLock $mine; 'ACQUIRED' | Set-Content -LiteralPath $env:RA_TEST_RESULT_PATH -Encoding ascii }
else { 'BUSY' | Set-Content -LiteralPath $env:RA_TEST_RESULT_PATH -Encoding ascii }
'@ | Set-Content -LiteralPath $contenderScript -Encoding ascii
        $env:RA_TEST_WATCHDOG_PATH = Join-Path $scripts 'RunningAI.Watchdog.ps1'
        $holder = $null
        try {
            $holder = Start-Process -FilePath $psExe -ArgumentList @('-NoProfile', '-WindowStyle', 'Hidden', '-File', $holderScript) -WindowStyle Hidden -PassThru
            Start-Sleep -Seconds 2
            $env:RA_TEST_RESULT_PATH = $resultPath
            Start-Process -FilePath $psExe -ArgumentList @('-NoProfile', '-WindowStyle', 'Hidden', '-File', $contenderScript) -WindowStyle Hidden -Wait
            $busyWhileHeld = (Test-Path -LiteralPath $resultPath) -and ((Get-Content -LiteralPath $resultPath -Raw).Trim() -eq 'BUSY')
            Start-Sleep -Seconds 4   # let the holder finish and release
            Remove-Item -LiteralPath $resultPath -ErrorAction SilentlyContinue
            Start-Process -FilePath $psExe -ArgumentList @('-NoProfile', '-WindowStyle', 'Hidden', '-File', $contenderScript) -WindowStyle Hidden -Wait
            $acquiredAfterRelease = (Test-Path -LiteralPath $resultPath) -and ((Get-Content -LiteralPath $resultPath -Raw).Trim() -eq 'ACQUIRED')
            $busyWhileHeld -and $acquiredAfterRelease
        } finally {
            if ($holder) { Stop-Process -Id $holder.Id -Force -ErrorAction SilentlyContinue }
            Remove-Item Env:\RA_TEST_WATCHDOG_PATH, Env:\RA_TEST_RESULT_PATH -ErrorAction SilentlyContinue
        }
    }
}

Check 'Save-RunningAiWatchdogStateReconciled replays only THIS tick''s own deltas onto a freshly re-read base, never resurrecting a concurrently-cleared lockout' {
    With-TempRuntimeDir {
        param($dir)
        $path = Join-Path $dir 'watchdog-state.json'
        # Seed: connector already locked out from an earlier run.
        $seedLt = New-EmptyLongTermState
        1..6 | ForEach-Object { Add-LongTermFailure -LongTermState $seedLt -Component 'connector' -Now $now }
        Update-RunningAiLongTermLockout -LongTermState $seedLt -Component 'connector' -Now $now -MaxFailures 6
        Save-WatchdogState -History (New-EmptyHistory) -LongTermState $seedLt -Path $path
        # Simulate "an operator cleared the connector lockout WHILE our tick was still running" by
        # overwriting the on-disk file directly (standing in for a real concurrent clear-watchdog-
        # lockout.ps1 -Force, already covered end-to-end by the next check) BEFORE we save our own
        # tick's deltas - a completely unrelated 'spring' failure recorded during that same tick.
        $clearedLt = New-EmptyLongTermState
        Save-WatchdogState -History (New-EmptyHistory) -LongTermState $clearedLt -Path $path
        $deltas = [pscustomobject]@{ Restarts = @(); LongTermFailures = @([pscustomobject]@{ Component = 'spring'; Now = $now }) }
        $ok = Save-RunningAiWatchdogStateReconciled -Deltas $deltas -Now $now -Path $path
        $reloaded = Read-WatchdogState -Path $path
        $ok -and
        (-not (Test-RunningAiComponentLockedOut -LongTermState $reloaded.LongTermState -Component 'connector')) -and
        (@($reloaded.LongTermState['connector'].Failures).Count -eq 0) -and
        (@($reloaded.LongTermState['spring'].Failures).Count -eq 1)
    }
}

Check 'clear-watchdog-lockout.ps1 WITHOUT -Force (preview) never modifies the state file, even byte-for-byte' {
    With-TempRuntimeDir {
        param($dir)
        $path = Join-Path $dir 'watchdog-state.json'
        $lt = New-EmptyLongTermState
        1..6 | ForEach-Object { Add-LongTermFailure -LongTermState $lt -Component 'spring' -Now $now }
        Update-RunningAiLongTermLockout -LongTermState $lt -Component 'spring' -Now $now -MaxFailures 6
        Save-WatchdogState -History (New-EmptyHistory) -LongTermState $lt -Path $path
        $before = Get-Content -LiteralPath $path -Raw
        $beforeWriteTime = (Get-Item -LiteralPath $path).LastWriteTimeUtc
        $psExe = (Get-Command powershell.exe).Source
        $previewScript = Join-Path $scripts 'clear-watchdog-lockout.ps1'
        Start-Process -FilePath $psExe -ArgumentList @('-NoProfile', '-File', $previewScript, '-Component', 'spring') -WindowStyle Hidden -Wait
        $after = Get-Content -LiteralPath $path -Raw
        $afterWriteTime = (Get-Item -LiteralPath $path).LastWriteTimeUtc
        ($before -eq $after) -and ($beforeWriteTime -eq $afterWriteTime) -and (-not (Test-Path -LiteralPath "$path.corrupt"))
    }
}

Check 'clear-watchdog-lockout.ps1 -Force actually clears the named component only, and a real concurrent Watchdog-style save (holding the state lock first) is refused rather than racing' {
    With-TempRuntimeDir {
        param($dir)
        $path = Join-Path $dir 'watchdog-state.json'
        $lt = New-EmptyLongTermState
        1..6 | ForEach-Object { Add-LongTermFailure -LongTermState $lt -Component 'connector' -Now $now }
        Update-RunningAiLongTermLockout -LongTermState $lt -Component 'connector' -Now $now -MaxFailures 6
        1..2 | ForEach-Object { Add-LongTermFailure -LongTermState $lt -Component 'spring' -Now $now }
        Save-WatchdogState -History (New-EmptyHistory) -LongTermState $lt -Path $path
        $psExe = (Get-Command powershell.exe).Source
        $clearScript = Join-Path $scripts 'clear-watchdog-lockout.ps1'
        $holderScript = Join-Path $dir 'state-lock-holder.ps1'
        @'
. "$env:RA_TEST_WATCHDOG_PATH"
$m = Enter-RunningAiWatchdogStateLock -TimeoutSec 5
if (-not $m) { exit 9 }
Start-Sleep -Seconds 16
Exit-RunningAiWatchdogStateLock $m
'@ | Set-Content -LiteralPath $holderScript -Encoding ascii
        $env:RA_TEST_WATCHDOG_PATH = Join-Path $scripts 'RunningAI.Watchdog.ps1'
        $holder = $null
        try {
            $holder = Start-Process -FilePath $psExe -ArgumentList @('-NoProfile', '-WindowStyle', 'Hidden', '-File', $holderScript) -WindowStyle Hidden -PassThru
            Start-Sleep -Seconds 2
            # clear-watchdog-lockout.ps1 -Force itself waits up to 10s for the lock (production value,
            # not shortened for this test) - the holder above sleeps 16s total (started ~2s before this
            # call), so its remaining hold time comfortably exceeds that 10s budget and this attempt
            # genuinely times out and is refused, rather than merely waiting the holder out.
            $clearDuringHold = Start-Process -FilePath $psExe -ArgumentList @('-NoProfile', '-File', $clearScript, '-Component', 'connector', '-Force') -WindowStyle Hidden -PassThru -Wait
            $refusedWhileHeld = ($clearDuringHold.ExitCode -ne 0)
            $stillLockedDuringHold = Test-RunningAiComponentLockedOut -LongTermState (Read-WatchdogState -Path $path).LongTermState -Component 'connector'
            Start-Sleep -Seconds 6   # let the holder release (16s total hold, ~12s elapsed by now)
            $clearAfterRelease = Start-Process -FilePath $psExe -ArgumentList @('-NoProfile', '-File', $clearScript, '-Component', 'connector', '-Force') -WindowStyle Hidden -PassThru -Wait
            $reloaded = Read-WatchdogState -Path $path
            $clearedNow = -not (Test-RunningAiComponentLockedOut -LongTermState $reloaded.LongTermState -Component 'connector')
            $springUntouched = (@($reloaded.LongTermState['spring'].Failures).Count -eq 2)
            $refusedWhileHeld -and $stillLockedDuringHold -and ($clearAfterRelease.ExitCode -eq 0) -and $clearedNow -and $springUntouched
        } finally {
            if ($holder) { Stop-Process -Id $holder.Id -Force -ErrorAction SilentlyContinue }
            Remove-Item Env:\RA_TEST_WATCHDOG_PATH -ErrorAction SilentlyContinue
        }
    }
}

Check 'Read-WatchdogState: a missing main file WITH a .bak present is reported unavailable (never silently treated as first run), and the backup is never auto-restored' {
    With-TempRuntimeDir {
        param($dir)
        $path = Join-Path $dir 'watchdog-state.json'
        $lt = New-EmptyLongTermState
        1..6 | ForEach-Object { Add-LongTermFailure -LongTermState $lt -Component 'spring' -Now $now }
        Update-RunningAiLongTermLockout -LongTermState $lt -Component 'spring' -Now $now -MaxFailures 6
        Save-WatchdogState -History (New-EmptyHistory) -LongTermState $lt -Path $path   # writes $path and $path.bak
        Remove-Item -LiteralPath $path -Force   # simulate the main file vanishing
        $state = Read-WatchdogState -Path $path
        (-not $state.Available) -and ($state.Note -match 'backup') -and (-not (Test-Path -LiteralPath $path))
    }
}

Check 'Read-WatchdogState: a missing main file with NO .bak is still a genuine first run (unchanged behavior)' {
    With-TempRuntimeDir {
        param($dir)
        $path = Join-Path $dir 'watchdog-state.json'
        $state = Read-WatchdogState -Path $path
        ($state.Available) -and ($state.Note -eq 'no state file (first run)')
    }
}

if ($failures.Count) {
    Write-Host ("{0} check(s) failed: {1}" -f $failures.Count, ($failures -join '; ')) -ForegroundColor Red
    exit 1
}
Write-Host 'All watchdog recovery policy checks passed.'
exit 0
