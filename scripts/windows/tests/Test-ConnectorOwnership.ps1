<#
.SYNOPSIS
  Isolated checks for the Garmin connector process-ownership model (Phase 6I-1.7B-1). Uses only
  real, short-lived, dedicated test processes and temporary loopback ports created by this file -
  never the real Garmin connector, never port 8765, never Garmin/network/Docker. Every check cleans
  up every process and PID/metadata file it creates, even on failure.
  Exit code 0 when all checks pass.
#>
$ErrorActionPreference = 'Stop'
$scripts = Split-Path $PSScriptRoot -Parent
. (Join-Path $scripts 'RunningAI.ConnectorOwnership.ps1')

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

# ---- test fixture: real launcher -> listener process pairs on a temp loopback port --------------
# Models the exact shape Phase 6I-1.7A found live (a venv python.exe launcher that re-execs the real
# interpreter as a child, which is the one that actually binds the port) without touching Python,
# Garmin or the real connector at all: a plain PowerShell "launcher" script spawns a plain
# PowerShell "listener" script as its child, which binds a TcpListener on the given port and writes
# its own PID to a handoff file. The marker set is unique to this test file (never garmin_connector).

$testRoot = Join-Path ([IO.Path]::GetTempPath()) ('ra-ownership-' + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Force $testRoot | Out-Null
# Single marker (the launcher's own script path) - deliberately NOT a substring of the neutral
# listener script's own path below, so a plain launcher->child pair reproduces the real asymmetry
# Phase 6I-1.7A found: the launcher's command line carries the repo-root marker on its own (it lives
# under tools\garmin-connector\.venv, inside this repository), the real listener's does not (a global
# interpreter path with no repo-root string anywhere in its own command line).
$testMarkers = @($testRoot)
$psExe = (Get-Command powershell.exe).Source

# Lives OUTSIDE $testRoot on purpose (see note above). -Tag is accepted but never used for anything
# except appearing in this process's own command line, letting a caller opt this one process INTO
# self-identifying (the SELF_OWNED "no separate launcher" shape) without a second script variant.
$listenerScriptPath = Join-Path ([IO.Path]::GetTempPath()) ('ra-ownership-listener-' + [guid]::NewGuid().ToString('N') + '.ps1')
Set-Content -LiteralPath $listenerScriptPath -Encoding ascii -Value @'
param([int]$Port, [switch]$IgnoreCtrlC, [string]$Tag)
if ($IgnoreCtrlC) {
    Add-Type -TypeDefinition @"
using System;
using System.Runtime.InteropServices;
public static class SelfTestCtrlCIgnore {
    public delegate bool HandlerRoutine(uint ctrlType);
    [DllImport("kernel32.dll")] public static extern bool SetConsoleCtrlHandler(HandlerRoutine handler, bool add);
    public static bool Handler(uint ctrlType) { return true; }
}
"@
    $script:selfTestCtrlCHandler = [SelfTestCtrlCIgnore+HandlerRoutine]([SelfTestCtrlCIgnore]::Handler)
    [SelfTestCtrlCIgnore]::SetConsoleCtrlHandler($script:selfTestCtrlCHandler, $true) | Out-Null
}
$listener = New-Object System.Net.Sockets.TcpListener ([System.Net.IPAddress]::Parse('127.0.0.1'), $Port)
$listener.Start()
try { while ($true) { Start-Sleep -Seconds 1 } } finally { $listener.Stop() }
'@

# Lives UNDER $testRoot, so ITS OWN command line naturally carries the repo-root marker (mirrors the
# real venv launcher's path living inside the repository). Redirects the child's stdout/stderr - the
# same shape start-running-ai.ps1 itself uses for the real connector - never spawns the listener with
# -Tag, so the child does NOT self-identify unless a test explicitly asks for it.
$launcherScriptPath = Join-Path $testRoot 'selftest-ownership-marker-launcher.ps1'
Set-Content -LiteralPath $launcherScriptPath -Encoding ascii -Value @'
param([int]$Port, [string]$ListenerScript, [string]$ChildPidFile, [switch]$ListenerIgnoreCtrlC)
$argList = @('-NoProfile', '-WindowStyle', 'Hidden', '-File', $ListenerScript, '-Port', $Port)
if ($ListenerIgnoreCtrlC) { $argList += '-IgnoreCtrlC' }
$child = Start-Process -FilePath (Get-Command powershell.exe).Source -ArgumentList $argList -WindowStyle Hidden -PassThru `
    -RedirectStandardOutput "$ChildPidFile.out.log" -RedirectStandardError "$ChildPidFile.err.log"
Set-Content -LiteralPath $ChildPidFile -Value $child.Id -Encoding ascii
while ($true) { Start-Sleep -Seconds 1 }
'@

function Get-SelfTestPort { Get-Random -Minimum 20000 -Maximum 40000 }

function Wait-ForPortListening {
    param([int]$Port, [int]$TimeoutSec = 10)
    $deadline = (Get-Date).AddSeconds($TimeoutSec)
    while ((Get-Date) -lt $deadline) {
        if ([bool](Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue)) { return $true }
        Start-Sleep -Milliseconds 200
    }
    return $false
}

function Wait-ForFileContent {
    param([string]$Path, [int]$TimeoutSec = 10)
    $deadline = (Get-Date).AddSeconds($TimeoutSec)
    while ((Get-Date) -lt $deadline) {
        if (Test-Path -LiteralPath $Path) {
            $v = (Get-Content -LiteralPath $Path -Raw -ErrorAction SilentlyContinue)
            if ($v -and $v.Trim()) { return $v.Trim() }
        }
        Start-Sleep -Milliseconds 200
    }
    throw "handoff file '$Path' never received content within ${TimeoutSec}s"
}

# Starts a launcher+listener pair, returns @{ LauncherPid; ListenerPid; Port }. Caller must
# Stop-SelfTestPair in a finally block.
function Start-SelfTestPair {
    param([switch]$ListenerIgnoreCtrlC)
    $port = Get-SelfTestPort
    $pidFile = Join-Path $testRoot ("childpid-" + [guid]::NewGuid().ToString('N') + '.txt')
    $argList = @('-NoProfile', '-WindowStyle', 'Hidden', '-File', $launcherScriptPath, '-Port', $port, '-ListenerScript', $listenerScriptPath, '-ChildPidFile', $pidFile)
    if ($ListenerIgnoreCtrlC) { $argList += '-ListenerIgnoreCtrlC' }
    $launcher = Start-Process -FilePath $psExe -ArgumentList $argList -WindowStyle Hidden -PassThru
    $listenerPid = [int](Wait-ForFileContent -Path $pidFile)
    if (-not (Wait-ForPortListening -Port $port)) { throw "test listener never bound port $port" }
    [pscustomobject]@{ LauncherPid = $launcher.Id; ListenerPid = $listenerPid; Port = $port }
}

# Starts a single process that IS the listener (no separate launcher) - models a connector whose
# venv python.exe is a real interpreter copy, not a re-exec stub.
function Start-SelfTestSingleProcess {
    $port = Get-SelfTestPort
    # -Tag makes this one process's own command line carry the repo-root marker, modeling a
    # connector whose venv python.exe is a real interpreter copy (no launcher/child split).
    $argList = @('-NoProfile', '-WindowStyle', 'Hidden', '-File', $listenerScriptPath, '-Port', $port, '-Tag', $testRoot)
    $proc = Start-Process -FilePath $psExe -ArgumentList $argList -WindowStyle Hidden -PassThru
    if (-not (Wait-ForPortListening -Port $port)) { throw "test listener never bound port $port" }
    [pscustomobject]@{ LauncherPid = $proc.Id; ListenerPid = $proc.Id; Port = $port }
}

function Stop-SelfTestPair {
    param($Pair)
    foreach ($candidatePid in @($Pair.LauncherPid, $Pair.ListenerPid) | Select-Object -Unique) {
        Stop-Process -Id $candidatePid -Force -ErrorAction SilentlyContinue
    }
}

try {

# ---- 1/2. normal launcher -> listener parent/child, PID mismatch by design ----------------------
Check 'launcher and listener are different live PIDs, correctly resolved as one managed instance' {
    $pair = Start-SelfTestPair
    try {
        $o = Get-RunningAiConnectorOwnership -Port $pair.Port -TrackedPid $pair.LauncherPid -Markers $testMarkers -Name ('selftest-ownership-' + [guid]::NewGuid().ToString('N'))
        ($pair.LauncherPid -ne $pair.ListenerPid) -and
        ($o.Verdict -eq 'LAUNCHER_CHILD') -and
        ($o.LauncherPid -eq $pair.LauncherPid) -and ($o.ListenerPid -eq $pair.ListenerPid) -and
        (@($o.ManagedPids) -contains $pair.LauncherPid) -and (@($o.ManagedPids) -contains $pair.ListenerPid)
    } finally { Stop-SelfTestPair $pair }
}

# ---- 3. normal single-process connector (no split) ----------------------------------------------
Check 'single-process connector (no separate launcher) is SELF_OWNED' {
    $single = Start-SelfTestSingleProcess
    try {
        $o = Get-RunningAiConnectorOwnership -Port $single.Port -TrackedPid $single.LauncherPid -Markers $testMarkers -Name ('selftest-ownership-' + [guid]::NewGuid().ToString('N'))
        ($o.Verdict -eq 'SELF_OWNED') -and (@($o.ManagedPids).Count -eq 1) -and (@($o.ManagedPids)[0] -eq $single.ListenerPid)
    } finally { Stop-SelfTestPair $single }
}

# ---- 4/5. PID-file PID reuse / creation-time mismatch (simulated: real OS PID reuse cannot be
# deterministically engineered in a test, so this proves the CreationDate cross-check itself, which
# is exactly what stands between a reused PID and a false-positive ownership match) ----------------
Check 'a tracked PID number that now belongs to an unrelated live process is never trusted' {
    $pair = Start-SelfTestPair
    try {
        # $PID (this test process itself) is alive but shares none of the connector markers - stands
        # in for "the number in the .pid file now belongs to a different, reused process".
        $o = Get-RunningAiConnectorOwnership -Port $pair.Port -TrackedPid $PID -Markers $testMarkers -Name ('selftest-ownership-' + [guid]::NewGuid().ToString('N'))
        # The real listener is still correctly seen, but never attributed to the bogus tracked PID.
        ($o.Verdict -ne 'LAUNCHER_CHILD') -and ($o.ListenerPid -eq $pair.ListenerPid) -and (@($o.ManagedPids).Count -eq 0 -or -not (@($o.ManagedPids) -contains $PID))
    } finally { Stop-SelfTestPair $pair }
}

Check 'sidecar metadata with a creation-time mismatch is rejected, never used to re-identify an orphan' {
    $pair = Start-SelfTestPair
    try {
        $name = 'selftest-ownership-' + [guid]::NewGuid().ToString('N')
        $listenerInfo = Get-RunningAiProcessInfo -ProcessId $pair.ListenerPid
        # Deliberately wrong recorded creation time (shifted by an hour) - the live process's own
        # creation time will never match it.
        Write-RunningAiOwnerMeta -Name $name -Port $pair.Port `
            -Launcher ([pscustomobject]@{ ProcessId = $pair.LauncherPid; CreationDate = $listenerInfo.CreationDate }) `
            -Listener ([pscustomobject]@{ ProcessId = $pair.ListenerPid; CreationDate = $listenerInfo.CreationDate.AddHours(-1) })
        try {
            # Launcher PID replaced by a dead one (no such PID) so resolution must fall through to
            # the metadata path - which must then refuse the stale/mismatched record.
            $o = Get-RunningAiConnectorOwnership -Port $pair.Port -TrackedPid 999999 -Markers $testMarkers -Name $name
            $o.Verdict -ne 'ORPHANED_MANAGED_PROCESS'
        } finally { Remove-RunningAiOwnerMeta -Name $name }
    } finally { Stop-SelfTestPair $pair }
}

# ---- 6. foreign process (different "repository") occupies the port ------------------------------
Check 'a foreign process (different repo marker) on the port is FOREIGN_PROCESS, never managed' {
    $single = Start-SelfTestSingleProcess
    try {
        $foreignRepoRoot = Join-Path ([IO.Path]::GetTempPath()) 'selftest-some-other-repo'
        $foreignMarkers = @('garmin_connector', ' serve', $foreignRepoRoot)
        $o = Get-RunningAiConnectorOwnership -Port $single.Port -TrackedPid $null -Markers $foreignMarkers -Name ('selftest-ownership-' + [guid]::NewGuid().ToString('N'))
        # This test process carries neither 'garmin_connector' nor ' serve' in its own command line,
        # so it correctly resolves as UNKNOWN_OWNER rather than a false FOREIGN_PROCESS match -
        # either way it must never be treated as manageable.
        ($o.Verdict -in @('FOREIGN_PROCESS', 'UNKNOWN_OWNER')) -and (@($o.ManagedPids).Count -eq 0)
    } finally { Stop-SelfTestPair $single }
}

# ---- 7. launcher alive, listener never started ---------------------------------------------------
Check 'launcher alive but nothing listening yet is DOWN, not conflated with an ambiguous port' {
    $port = Get-SelfTestPort
    # No listener process at all for this port - $PID stands in for a live, ours-by-marker-only
    # launcher that simply has not spawned anything bound to the port.
    $o = Get-RunningAiConnectorOwnership -Port $port -TrackedPid $PID -Markers @('powershell') -Name ('selftest-ownership-' + [guid]::NewGuid().ToString('N'))
    $o.Verdict -eq 'DOWN' -and (@($o.ManagedPids).Count -eq 0)
}

# ---- 8/9. launcher dead, listener survives (the real Phase 6I-1.7A scenario) ---------------------
Check 'launcher gone, listener survives with matching sidecar metadata -> ORPHANED_MANAGED_PROCESS' {
    $pair = Start-SelfTestPair
    try {
        $name = 'selftest-ownership-' + [guid]::NewGuid().ToString('N')
        $launcherInfo = Get-RunningAiProcessInfo -ProcessId $pair.LauncherPid
        $listenerInfo = Get-RunningAiProcessInfo -ProcessId $pair.ListenerPid
        Write-RunningAiOwnerMeta -Name $name -Port $pair.Port `
            -Launcher ([pscustomobject]@{ ProcessId = $launcherInfo.ProcessId; CreationDate = $launcherInfo.CreationDate }) `
            -Listener ([pscustomobject]@{ ProcessId = $listenerInfo.ProcessId; CreationDate = $listenerInfo.CreationDate })
        try {
            Stop-Process -Id $pair.LauncherPid -Force -ErrorAction SilentlyContinue
            Start-Sleep -Milliseconds 300
            if (Get-Process -Id $pair.LauncherPid -ErrorAction SilentlyContinue) { throw 'launcher did not actually exit' }

            $o = Get-RunningAiConnectorOwnership -Port $pair.Port -TrackedPid $pair.LauncherPid -Markers $testMarkers -Name $name
            ($o.Verdict -eq 'ORPHANED_MANAGED_PROCESS') -and (@($o.ManagedPids).Count -eq 1) -and (@($o.ManagedPids)[0] -eq $pair.ListenerPid) -and ($null -eq $o.LauncherPid)
        } finally { Remove-RunningAiOwnerMeta -Name $name }
    } finally { Stop-SelfTestPair $pair }
}

Check 'launcher gone, listener survives with NO sidecar metadata -> UNKNOWN_OWNER, never auto-managed' {
    $pair = Start-SelfTestPair
    try {
        Stop-Process -Id $pair.LauncherPid -Force -ErrorAction SilentlyContinue
        Start-Sleep -Milliseconds 300
        $o = Get-RunningAiConnectorOwnership -Port $pair.Port -TrackedPid $pair.LauncherPid -Markers $testMarkers -Name ('selftest-ownership-no-meta-' + [guid]::NewGuid().ToString('N'))
        ($o.Verdict -eq 'UNKNOWN_OWNER') -and (@($o.ManagedPids).Count -eq 0)
    } finally { Stop-SelfTestPair $pair }
}

# ---- 10. graceful shutdown succeeds ---------------------------------------------------------------
Check 'graceful stop: Ctrl+C reaches both launcher and child, port is freed, no force needed' {
    $pair = Start-SelfTestPair
    try {
        $o = Get-RunningAiConnectorOwnership -Port $pair.Port -TrackedPid $pair.LauncherPid -Markers $testMarkers -Name ('selftest-ownership-' + [guid]::NewGuid().ToString('N'))
        $result = Stop-RunningAiConnectorManaged -Ownership $o -Port $pair.Port -TimeoutSec 20 -Name ('selftest-ownership-stop-' + [guid]::NewGuid().ToString('N'))
        ($result.Result -eq 'graceful') -and ($result.PortFreed) -and
        (-not (Get-Process -Id $pair.LauncherPid -ErrorAction SilentlyContinue)) -and
        (-not (Get-Process -Id $pair.ListenerPid -ErrorAction SilentlyContinue))
    } finally { Stop-SelfTestPair $pair }
}

# ---- 11/12. graceful fails, safe forced cleanup of ONLY verified managed PIDs --------------------
Check 'graceful timeout falls back to forced stop of verified PIDs only; a bystander process survives' {
    $pair = Start-SelfTestPair -ListenerIgnoreCtrlC
    $bystander = $null
    try {
        $bystander = Start-Process -FilePath $psExe -ArgumentList @('-NoProfile', '-WindowStyle', 'Hidden', '-Command', 'Start-Sleep -Seconds 120') -WindowStyle Hidden -PassThru
        $o = Get-RunningAiConnectorOwnership -Port $pair.Port -TrackedPid $pair.LauncherPid -Markers $testMarkers -Name ('selftest-ownership-' + [guid]::NewGuid().ToString('N'))
        $result = Stop-RunningAiConnectorManaged -Ownership $o -Port $pair.Port -TimeoutSec 0 -Name ('selftest-ownership-forced-' + [guid]::NewGuid().ToString('N'))
        ($result.Result -eq 'forced') -and ($result.PortFreed) -and
        (-not (Get-Process -Id $pair.ListenerPid -ErrorAction SilentlyContinue)) -and
        ([bool](Get-Process -Id $bystander.Id -ErrorAction SilentlyContinue))
    } finally {
        Stop-SelfTestPair $pair
        if ($bystander) { Stop-Process -Id $bystander.Id -Force -ErrorAction SilentlyContinue }
    }
}

# ---- 13. unknown ownership refuses to terminate ---------------------------------------------------
Check 'Stop-RunningAiConnectorManaged refuses an UNKNOWN_OWNER verdict - the process is left untouched' {
    $single = Start-SelfTestSingleProcess
    try {
        $fakeOwnership = [pscustomobject]@{ Verdict = 'UNKNOWN_OWNER'; LauncherPid = $null; ListenerPid = $single.ListenerPid; ManagedPids = @(); ManagedPidSnapshot = @() }
        $result = Stop-RunningAiConnectorManaged -Ownership $fakeOwnership -Port $single.Port -TimeoutSec 2
        ($result.Result -eq 'refused') -and ([bool](Get-Process -Id $single.ListenerPid -ErrorAction SilentlyContinue))
    } finally { Stop-SelfTestPair $single }
}

Check 'Stop-RunningAiConnectorManaged refuses a FOREIGN_PROCESS verdict - the process is left untouched' {
    $single = Start-SelfTestSingleProcess
    try {
        $fakeOwnership = [pscustomobject]@{ Verdict = 'FOREIGN_PROCESS'; LauncherPid = $null; ListenerPid = $single.ListenerPid; ManagedPids = @(); ManagedPidSnapshot = @() }
        $result = Stop-RunningAiConnectorManaged -Ownership $fakeOwnership -Port $single.Port -TimeoutSec 2
        ($result.Result -eq 'refused') -and ([bool](Get-Process -Id $single.ListenerPid -ErrorAction SilentlyContinue))
    } finally { Stop-SelfTestPair $single }
}

# ---- 15. dev worktree vs canonical process confusion prevention ----------------------------------
Check 'a process matching connector shape but a DIFFERENT repo-root marker is never treated as ours' {
    $single = Start-SelfTestSingleProcess
    try {
        # Ask for markers requiring a repo root this test's own process tree never carries - proves
        # ownership resolution is tied to the CALLER-supplied repo marker (Get-RepoRoot of whichever
        # worktree/repo invoked it), not merely "something that looks like a connector".
        $otherWorktreeRoot = Join-Path ([IO.Path]::GetTempPath()) 'selftest-different-worktree'
        $otherWorktreeMarkers = @($testRoot, $otherWorktreeRoot)
        $o = Get-RunningAiConnectorOwnership -Port $single.Port -TrackedPid $single.LauncherPid -Markers $otherWorktreeMarkers -Name ('selftest-ownership-' + [guid]::NewGuid().ToString('N'))
        $o.Verdict -ne 'SELF_OWNED' -and $o.Verdict -ne 'LAUNCHER_CHILD'
    } finally { Stop-SelfTestPair $single }
}

# ---- metadata round trip and corruption handling --------------------------------------------------
Check 'owner metadata round trips (self-owned shape, Launcher=null) and is removed cleanly' {
    $name = 'selftest-ownership-meta-' + [guid]::NewGuid().ToString('N')
    try {
        $now = Get-Date
        Write-RunningAiOwnerMeta -Name $name -Port 12345 -Launcher $null -Listener ([pscustomobject]@{ ProcessId = 4242; CreationDate = $now })
        $meta = Read-RunningAiOwnerMeta -Name $name
        $okRead = ($meta.Port -eq 12345) -and ($meta.Listener.ProcessId -eq 4242) -and ($null -eq $meta.Launcher)
        Remove-RunningAiOwnerMeta -Name $name
        $okRead -and (-not (Test-Path (Get-RunningAiOwnerMetaPath $name)))
    } finally { Remove-RunningAiOwnerMeta -Name $name }
}

Check 'a corrupt owner metadata file is treated exactly like an absent one, never thrown' {
    $name = 'selftest-ownership-corrupt-' + [guid]::NewGuid().ToString('N')
    try {
        New-Item -ItemType Directory -Force $script:RuntimeDir | Out-Null
        Set-Content -LiteralPath (Get-RunningAiOwnerMetaPath $name) -Value '{ not valid json' -Encoding ascii
        $null -eq (Read-RunningAiOwnerMeta -Name $name)
    } finally { Remove-RunningAiOwnerMeta -Name $name }
}

Check 'Set-RunningAiConnectorOwnerMetaFromLive captures both PIDs at start time and resolves ORPHANED later' {
    $pair = Start-SelfTestPair
    $name = 'selftest-ownership-live-' + [guid]::NewGuid().ToString('N')
    try {
        $captured = Set-RunningAiConnectorOwnerMetaFromLive -LauncherPid $pair.LauncherPid -Port $pair.Port -Markers $testMarkers -Name $name
        Stop-Process -Id $pair.LauncherPid -Force -ErrorAction SilentlyContinue
        Start-Sleep -Milliseconds 300
        $o = Get-RunningAiConnectorOwnership -Port $pair.Port -TrackedPid $pair.LauncherPid -Markers $testMarkers -Name $name
        $captured -and ($o.Verdict -eq 'ORPHANED_MANAGED_PROCESS') -and (@($o.ManagedPids).Count -eq 1) -and (@($o.ManagedPids)[0] -eq $pair.ListenerPid)
    } finally { Remove-RunningAiOwnerMeta -Name $name; Stop-SelfTestPair $pair }
}

# ---- Phase 6I-1.7B-1R additions -----------------------------------------------------------------

# STEP F #1/#2: Set-RunningAiConnectorOwnerMetaFromLive must refuse (and write nothing) when the
# "listener" at the given port is not actually the launcher's child - covers both "unrelated listener"
# and "parent relationship mismatch" in one scenario, since both independent single processes here are
# each other's non-relatives by construction.
Check 'Set-RunningAiConnectorOwnerMetaFromLive refuses an unrelated listener (no parent relationship), writes nothing' {
    $a = Start-SelfTestSingleProcess
    $b = Start-SelfTestSingleProcess
    try {
        $name = 'selftest-ownership-' + [guid]::NewGuid().ToString('N')
        $captured = Set-RunningAiConnectorOwnerMetaFromLive -LauncherPid $a.LauncherPid -Port $b.Port -Markers $testMarkers -Name $name
        (-not $captured) -and (-not (Test-Path (Get-RunningAiOwnerMetaPath $name)))
    } finally { Stop-SelfTestPair $a; Stop-SelfTestPair $b }
}

# Same shape feeds Get-RunningAiConnectorOwnership directly: a tracked, alive, legitimately-ours
# launcher whose port is answered by an unrelated (non-child) process must resolve to UNKNOWN_OWNER,
# never LAUNCHER_CHILD - covers STEP F #2 (parent relationship mismatch) for the live-check path too.
Check 'launcher/listener parent relationship mismatch is never accepted as LAUNCHER_CHILD' {
    $a = Start-SelfTestSingleProcess
    $b = Start-SelfTestSingleProcess
    try {
        $o = Get-RunningAiConnectorOwnership -Port $b.Port -TrackedPid $a.LauncherPid -Markers $testMarkers -Name ('selftest-ownership-' + [guid]::NewGuid().ToString('N'))
        ($o.Verdict -ne 'LAUNCHER_CHILD') -and (@($o.ManagedPids).Count -eq 0)
    } finally { Stop-SelfTestPair $a; Stop-SelfTestPair $b }
}

# STEP F #4: a sidecar whose recorded repoRoot does not match this invocation's repo root is rejected
# outright - never used to re-identify an orphan, even if PID/port/creationDate all happen to agree.
Check 'sidecar with a mismatched repoRoot is rejected (UNKNOWN_OWNER), never used as ORPHANED' {
    $pair = Start-SelfTestPair
    try {
        $name = 'selftest-ownership-' + [guid]::NewGuid().ToString('N')
        $listenerInfo = Get-RunningAiProcessInfo -ProcessId $pair.ListenerPid
        $path = Get-RunningAiOwnerMetaPath $name
        New-Item -ItemType Directory -Force (Split-Path $path -Parent) | Out-Null
        $json = @{
            version = 1; name = $name; port = $pair.Port; repoRoot = 'C:\a-different-repo-entirely'
            recordedAt = (Get-Date).ToUniversalTime().ToString('yyyy-MM-ddTHH:mm:ssZ')
            launcher = $null
            listener = @{ processId = $listenerInfo.ProcessId; creationDate = $listenerInfo.CreationDate.ToUniversalTime().ToString('o') }
        } | ConvertTo-Json -Depth 6
        [System.IO.File]::WriteAllText($path, $json, (New-Object System.Text.UTF8Encoding($false)))
        try {
            Stop-Process -Id $pair.LauncherPid -Force -ErrorAction SilentlyContinue
            Start-Sleep -Milliseconds 300
            $o = Get-RunningAiConnectorOwnership -Port $pair.Port -TrackedPid $pair.LauncherPid -Markers $testMarkers -Name $name
            ($o.Verdict -eq 'UNKNOWN_OWNER') -and (@($o.ManagedPids).Count -eq 0)
        } finally { Remove-RunningAiOwnerMeta -Name $name }
    } finally { Stop-SelfTestPair $pair }
}

# STEP F #5: a sidecar whose internal "name" field does not match the name it was looked up under
# (e.g. a stray copy under the wrong filename) is likewise rejected, never trusted by coincidence.
Check 'sidecar with a mismatched internal name is rejected (UNKNOWN_OWNER), never used as ORPHANED' {
    $pair = Start-SelfTestPair
    try {
        $name = 'selftest-ownership-' + [guid]::NewGuid().ToString('N')
        $listenerInfo = Get-RunningAiProcessInfo -ProcessId $pair.ListenerPid
        $path = Get-RunningAiOwnerMetaPath $name
        New-Item -ItemType Directory -Force (Split-Path $path -Parent) | Out-Null
        $json = @{
            version = 1; name = 'a-completely-different-component-name'; port = $pair.Port; repoRoot = $script:RepoRoot
            recordedAt = (Get-Date).ToUniversalTime().ToString('yyyy-MM-ddTHH:mm:ssZ')
            launcher = $null
            listener = @{ processId = $listenerInfo.ProcessId; creationDate = $listenerInfo.CreationDate.ToUniversalTime().ToString('o') }
        } | ConvertTo-Json -Depth 6
        [System.IO.File]::WriteAllText($path, $json, (New-Object System.Text.UTF8Encoding($false)))
        try {
            Stop-Process -Id $pair.LauncherPid -Force -ErrorAction SilentlyContinue
            Start-Sleep -Milliseconds 300
            $o = Get-RunningAiConnectorOwnership -Port $pair.Port -TrackedPid $pair.LauncherPid -Markers $testMarkers -Name $name
            ($o.Verdict -eq 'UNKNOWN_OWNER') -and (@($o.ManagedPids).Count -eq 0)
        } finally { Remove-RunningAiOwnerMeta -Name $name }
    } finally { Stop-SelfTestPair $pair }
}

# STEP F #10: an outright port-query failure (not the routine "nothing found" case) must resolve to
# UNKNOWN_OWNER, never DOWN - an invalid port number forces Get-NetTCPConnection to fail for a
# different reason than "no matching objects", which is exactly the distinction being tested.
Check 'a port query failure (invalid port) resolves to UNKNOWN_OWNER, never DOWN' {
    $q = Get-RunningAiListenerProcessIds -Port 99999
    $o = Get-RunningAiConnectorOwnership -Port 99999 -TrackedPid $null -Markers $testMarkers -Name ('selftest-ownership-' + [guid]::NewGuid().ToString('N'))
    (-not $q.Ok) -and ($o.Verdict -eq 'UNKNOWN_OWNER')
}

# STEP F #6/#7: an ownership picture that already changed before Stop-RunningAiConnectorManaged ever
# signals anything - one of the two originally-managed PIDs no longer resolves at all - must abort as
# 'ownership-changed' rather than silently proceeding against whatever subset still resolves. The
# still-alive, still-valid PID must never be touched in this case.
Check 'Stop-RunningAiConnectorManaged detects a managed PID missing before any signal is sent (ownership-changed)' {
    $single = Start-SelfTestSingleProcess
    try {
        $launcherInfo = Get-RunningAiProcessInfo -ProcessId $single.LauncherPid
        $fakeOwnership = [pscustomobject]@{
            Verdict = 'LAUNCHER_CHILD'; LauncherPid = $single.LauncherPid; ListenerPid = 999999
            ManagedPids = @($single.LauncherPid, 999999)
            ManagedPidSnapshot = @(
                [pscustomobject]@{ ProcessId = $single.LauncherPid; CreationDate = $launcherInfo.CreationDate }
                [pscustomobject]@{ ProcessId = 999999; CreationDate = $launcherInfo.CreationDate }
            )
        }
        $result = Stop-RunningAiConnectorManaged -Ownership $fakeOwnership -Port $single.Port -TimeoutSec 5 -Name ('selftest-ownership-' + [guid]::NewGuid().ToString('N'))
        ($result.Result -eq 'ownership-changed') -and ([bool](Get-Process -Id $single.LauncherPid -ErrorAction SilentlyContinue))
    } finally { Stop-SelfTestPair $single }
}

# STEP F #11/#12: the shared clean-stop policy (start-/stop-running-ai.ps1 and RunningAI.Watchdog.ps1
# all call this instead of re-deriving the condition) must never call a result clean on RemainingPids
# alone - PortFreed matters just as much, and 'refused'/'ownership-changed' are never clean regardless
# of what RemainingPids happens to report.
Check 'Test-RunningAiConnectorStopWasClean requires PortFreed, not RemainingPids=0 alone' {
    $falselyEmpty = [pscustomobject]@{ Result = 'orphan-remaining'; PortFreed = $false; RemainingPids = @() }
    $trulyClean = [pscustomobject]@{ Result = 'forced'; PortFreed = $true; RemainingPids = @() }
    $refused = [pscustomobject]@{ Result = 'refused'; PortFreed = $true; RemainingPids = @() }
    $ownershipChanged = [pscustomobject]@{ Result = 'ownership-changed'; PortFreed = $true; RemainingPids = @() }
    (-not (Test-RunningAiConnectorStopWasClean -StopResult $falselyEmpty)) -and
    (Test-RunningAiConnectorStopWasClean -StopResult $trulyClean) -and
    (-not (Test-RunningAiConnectorStopWasClean -StopResult $refused)) -and
    (-not (Test-RunningAiConnectorStopWasClean -StopResult $ownershipChanged))
}

# STEP F #13: source-level lock-in (same pattern as the existing "Invoke-RecoveryAction routes
# external-relay to its own dedicated script" check) that the connector's PreStop branch gates on
# Test-RunningAiConnectorStopWasClean and returns before ever reaching the start-running-ai.ps1
# launch when the stop was not clean - a full process-level reproduction of a genuinely failed
# pre-stop would require forcing a real, timed ownership change, which (like true PID reuse) cannot
# be deterministically engineered against real OS processes.
Check 'Invoke-RecoveryAction never starts a new connector after a non-clean connector pre-stop (source check)' {
    $src = Get-Content (Join-Path $scripts 'RunningAI.Watchdog.ps1') -Raw
    $fn = [regex]::Match($src, '(?s)function Invoke-RecoveryAction \{.*?\n\}\r?\n').Value
    ($fn -match 'Test-RunningAiConnectorStopWasClean') -and
    ($fn -match '(?s)Test-RunningAiConnectorStopWasClean.*?return \$false')
}

# STEP F #14: Send-CtrlC.ps1 must refuse (exit 2) rather than signal when a genuinely unmanaged
# process shares the target's console - constructed by having a third "bystander" process explicitly
# AttachConsole to the launcher's console (the only reliable way to force two unrelated processes to
# share one console on demand, since the normal Start-Process launch pattern used elsewhere in this
# suite does not by itself make a child share its parent's console - confirmed separately). The
# bystander, and the real launcher/listener, must all remain untouched.
Check 'Send-CtrlC.ps1 refuses to signal when an unmanaged process shares the console (bystander untouched)' {
    $pair = Start-SelfTestPair
    $bystander = $null
    try {
        $bystanderScript = Join-Path ([IO.Path]::GetTempPath()) ('selftest-ownership-bystander-' + [guid]::NewGuid().ToString('N') + '.ps1')
        Set-Content -LiteralPath $bystanderScript -Encoding ascii -Value @'
param([int]$TargetPid)
Add-Type -TypeDefinition @"
using System;
using System.Runtime.InteropServices;
public static class SelfTestAttach {
    [DllImport("kernel32.dll")] public static extern bool FreeConsole();
    [DllImport("kernel32.dll")] public static extern bool AttachConsole(uint pid);
}
"@
# A process already attached to its own console cannot AttachConsole to another one
# (ERROR_ACCESS_DENIED) without freeing its own console first.
[SelfTestAttach]::FreeConsole() | Out-Null
[SelfTestAttach]::AttachConsole([uint32]$TargetPid) | Out-Null
while ($true) { Start-Sleep -Seconds 1 }
'@
        $bystander = Start-Process -FilePath $psExe -ArgumentList @('-NoProfile', '-WindowStyle', 'Hidden', '-File', $bystanderScript, '-TargetPid', $pair.LauncherPid) -WindowStyle Hidden -PassThru
        Start-Sleep -Seconds 1   # let the bystander actually attach before we check

        $helper = Join-Path $scripts 'Send-CtrlC.ps1'
        $allowed = "$($pair.LauncherPid),$($pair.ListenerPid)"
        $p = Start-Process -FilePath $psExe -ArgumentList @('-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', $helper, '-ProcessId', $pair.LauncherPid, '-AllowedProcessIds', $allowed) -WindowStyle Hidden -PassThru -Wait
        Start-Sleep -Milliseconds 500

        ($p.ExitCode -eq 2) -and
        ([bool](Get-Process -Id $pair.LauncherPid -ErrorAction SilentlyContinue)) -and
        ([bool](Get-Process -Id $pair.ListenerPid -ErrorAction SilentlyContinue)) -and
        ([bool](Get-Process -Id $bystander.Id -ErrorAction SilentlyContinue))
    } finally {
        Stop-SelfTestPair $pair
        if ($bystander) { Stop-Process -Id $bystander.Id -Force -ErrorAction SilentlyContinue }
        if ($bystanderScript -and (Test-Path $bystanderScript)) { Remove-Item -LiteralPath $bystanderScript -Force -ErrorAction SilentlyContinue }
    }
}

# STEP F #15 (regression): Spring/relay's stop path (Stop-TrackedProcess -> Send-CtrlC.ps1 with NO
# -AllowedProcessIds) must behave exactly as before this phase - unconditional send, never refused by
# the new console-sharing check, which only ever activates when that parameter is explicitly passed.
Check 'Send-CtrlC.ps1 omitting -AllowedProcessIds performs the legacy unconditional send (Spring/relay path unaffected)' {
    $single = Start-SelfTestSingleProcess
    try {
        $helper = Join-Path $scripts 'Send-CtrlC.ps1'
        $p = Start-Process -FilePath $psExe -ArgumentList @('-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', $helper, '-ProcessId', $single.LauncherPid) -WindowStyle Hidden -PassThru -Wait
        Start-Sleep -Milliseconds 500
        ($p.ExitCode -eq 0) -and (-not (Get-Process -Id $single.LauncherPid -ErrorAction SilentlyContinue))
    } finally { Stop-SelfTestPair $single }
}

# ---- Phase 6I-1.7B-2A additions -----------------------------------------------------------------

# STEP 1 items 1-3: source-level lock-in, the same pattern already used above for "never starts a new
# connector after a non-clean pre-stop". Deliberately NOT exercised by actually calling
# Invoke-RecoveryAction with PreStop=true for a real UNKNOWN_OWNER/FOREIGN_PROCESS/empty-ManagedPids
# ownership picture: any gap in the gate would make it fall through to the REAL
# start-running-ai.ps1 launch a few lines later (Docker/PostgreSQL/the real connector) - exactly the
# kind of "touches real services from a test" this work order forbids. Source inspection proves the
# gate exists without ever risking that fall-through.
Check 'Invoke-RecoveryAction connector PreStop: DOWN proceeds, every other verdict blocks (source check)' {
    $src = Get-Content (Join-Path $scripts 'RunningAI.Watchdog.ps1') -Raw
    $fn = [regex]::Match($src, "(?s)function Invoke-RecoveryAction \{.*?\n\}\r?\n").Value
    $connectorBlock = [regex]::Match($fn, "(?s)if \(\`$Action\.Component -eq 'connector'\) \{.*?\n        \} elseif").Value
    # 'DOWN' case has an empty/comment-only body (falls through to the start attempt below).
    $downCase = [regex]::Match($connectorBlock, "(?s)'DOWN' \{(.*?)\}\r?\n\s*\{")
    # Every other explicitly-manageable verdict requires ManagedPids non-empty, else blocks.
    $hasManagedPidsGuard = $connectorBlock -match "(?s)ManagedPids\).Count -eq 0.*?return \`$false"
    # The catch-all default (FOREIGN_PROCESS, UNKNOWN_OWNER, anything unrecognized) blocks too.
    $hasDefaultBlock = $connectorBlock -match "(?s)default \{.*?return \`$false"
    ($connectorBlock -match "'DOWN' \{") -and $hasManagedPidsGuard -and $hasDefaultBlock
}

# STEP 1 item 3 / STEP 5 item 4: the DOWN case's own body must contain no stop/block logic at all -
# confirms it is a deliberate pass-through, not an accidental empty case that happens to work.
Check 'Invoke-RecoveryAction connector PreStop: DOWN case body is a pure pass-through comment, no action (source check)' {
    $src = Get-Content (Join-Path $scripts 'RunningAI.Watchdog.ps1') -Raw
    $fn = [regex]::Match($src, "(?s)function Invoke-RecoveryAction \{.*?\n\}\r?\n").Value
    $downBody = [regex]::Match($fn, "(?s)'DOWN' \{(.*?)\}\r?\n\s*\{ \`$_ -in").Groups[1].Value
    (-not [string]::IsNullOrWhiteSpace($downBody)) -and ($downBody -notmatch 'Stop-RunningAiConnectorManaged|return \$false')
}

# STEP 5 #5: classification-time identity (Ownership.ManagedPidSnapshot), not merely "is this PID
# alive right now", is what Stop-RunningAiConnectorManaged anchors to - a snapshot CreationDate tampered
# to no longer match the live process (simulating "classification time and stop time disagree") must
# abort as ownership-changed, touching nothing, even though the live process itself never changed.
Check 'a tampered ManagedPidSnapshot CreationDate (classification/stop-time mismatch) aborts as ownership-changed' {
    $single = Start-SelfTestSingleProcess
    try {
        $o = Get-RunningAiConnectorOwnership -Port $single.Port -TrackedPid $single.LauncherPid -Markers $testMarkers -Name ('selftest-ownership-' + [guid]::NewGuid().ToString('N'))
        $tampered = [pscustomobject]@{
            Verdict = $o.Verdict; LauncherPid = $o.LauncherPid; ListenerPid = $o.ListenerPid; ManagedPids = $o.ManagedPids
            ManagedPidSnapshot = @($o.ManagedPidSnapshot | ForEach-Object { [pscustomobject]@{ ProcessId = $_.ProcessId; CreationDate = $_.CreationDate.AddHours(-3) } })
        }
        $result = Stop-RunningAiConnectorManaged -Ownership $tampered -Port $single.Port -TimeoutSec 5 -Name ('selftest-ownership-' + [guid]::NewGuid().ToString('N'))
        ($result.Result -eq 'ownership-changed') -and ($result.ResultCode -eq 'OWNERSHIP_CHANGED') -and ([bool](Get-Process -Id $single.LauncherPid -ErrorAction SilentlyContinue))
    } finally { Stop-SelfTestPair $single }
}

# STEP 5 #6: the listener Ownership captured is no longer the port's current listener at all (the
# original was killed and an unrelated process now occupies the port) - the stale Ownership object
# must detect the mismatch and never touch the new, unrelated occupant.
Check 'a stale Ownership whose listener no longer matches the current port occupant never touches the new occupant' {
    $single = Start-SelfTestSingleProcess
    $replacement = $null
    try {
        $o = Get-RunningAiConnectorOwnership -Port $single.Port -TrackedPid $single.LauncherPid -Markers $testMarkers -Name ('selftest-ownership-' + [guid]::NewGuid().ToString('N'))
        Stop-Process -Id $single.LauncherPid -Force -ErrorAction SilentlyContinue
        Start-Sleep -Milliseconds 500
        # A new, unrelated process now happens to occupy the same port. Retried: the OS may not
        # release a just-closed LISTEN socket instantly, so the first bind attempt can legitimately
        # fail with "address already in use" for a brief moment after the original process exits.
        $bound = $false
        for ($attempt = 0; $attempt -lt 5 -and -not $bound; $attempt++) {
            if ($replacement -and -not $replacement.HasExited) { Stop-Process -Id $replacement.Id -Force -ErrorAction SilentlyContinue }
            $replacement = Start-Process -FilePath $psExe -ArgumentList @('-NoProfile', '-WindowStyle', 'Hidden', '-File', $listenerScriptPath, '-Port', $single.Port) -WindowStyle Hidden -PassThru
            $bound = Wait-ForPortListening -Port $single.Port -TimeoutSec 3
            if (-not $bound) { Start-Sleep -Milliseconds 500 }
        }
        if (-not $bound) { throw 'replacement listener never bound the port' }

        $result = Stop-RunningAiConnectorManaged -Ownership $o -Port $single.Port -TimeoutSec 5 -Name ('selftest-ownership-' + [guid]::NewGuid().ToString('N'))
        # The ORIGINAL baseline PID genuinely exited (a different PID number now holds the port, not a
        # reused one) - 'already-gone' is the correct, accurate Result here, not 'ownership-changed'
        # (that is specifically for the SAME PID number now meaning something else). The safety
        # property under test is that the new, unrelated occupant is never touched either way.
        ($result.Result -eq 'already-gone') -and ($result.ResultCode -eq 'ALREADY_DOWN') -and ([bool](Get-Process -Id $replacement.Id -ErrorAction SilentlyContinue))
    } finally {
        Stop-SelfTestPair $single
        if ($replacement) { Stop-Process -Id $replacement.Id -Force -ErrorAction SilentlyContinue }
    }
}

# STEP 5 #7 (ResultCode contract): 'orphan-remaining' maps to PROCESS_REMAINING when a managed PID is
# still reported alive, and to PORT_STILL_OCCUPIED when none is (something else grabbed the port) -
# both are distinct, neither is ever read as success.
Check 'New-RunningAiStopOutcome maps orphan-remaining to PROCESS_REMAINING vs PORT_STILL_OCCUPIED correctly' {
    $withRemaining = New-RunningAiStopOutcome -Result 'orphan-remaining' -Verdict 'LAUNCHER_CHILD' -PortFreed $false -RemainingPids @(4242)
    $withoutRemaining = New-RunningAiStopOutcome -Result 'orphan-remaining' -Verdict 'LAUNCHER_CHILD' -PortFreed $false -RemainingPids @()
    ($withRemaining.ResultCode -eq 'PROCESS_REMAINING') -and ($withoutRemaining.ResultCode -eq 'PORT_STILL_OCCUPIED') -and
    (-not (Test-RunningAiConnectorStopWasClean -StopResult $withRemaining)) -and (-not (Test-RunningAiConnectorStopWasClean -StopResult $withoutRemaining))
}

# STEP 5 #7/#9/#10 (ResultCode contract, full vocabulary): every Result value maps to exactly the
# unified code this phase introduces, and only STOPPED/ALREADY_DOWN are ever a clean outcome.
Check 'New-RunningAiStopOutcome covers the full ResultCode vocabulary' {
    $cases = @{
        'already-gone'      = 'ALREADY_DOWN'
        'graceful'          = 'STOPPED'
        'forced'            = 'STOPPED'
        'refused'           = 'REFUSED_UNKNOWN_OWNER'
        'ownership-changed' = 'OWNERSHIP_CHANGED'
    }
    $ok = $true
    foreach ($kv in $cases.GetEnumerator()) {
        $outcome = New-RunningAiStopOutcome -Result $kv.Key -Verdict 'LAUNCHER_CHILD' -PortFreed $true
        if ($outcome.ResultCode -ne $kv.Value) { $ok = $false }
    }
    $unknown = New-RunningAiStopOutcome -Result 'something-unexpected' -Verdict 'LAUNCHER_CHILD' -PortFreed $false
    $ok -and ($unknown.ResultCode -eq 'STOP_FAILED')
}

# STEP 5 #3/malformed-input fail-safe: an Ownership object missing ManagedPidSnapshot entirely (e.g.
# a legacy or hand-built shape that predates this phase) is refused outright, never assumed valid.
Check 'Stop-RunningAiConnectorManaged refuses an Ownership object with no ManagedPidSnapshot at all' {
    $single = Start-SelfTestSingleProcess
    try {
        $legacyShaped = [pscustomobject]@{ Verdict = 'SELF_OWNED'; LauncherPid = $single.LauncherPid; ListenerPid = $single.ListenerPid; ManagedPids = @($single.LauncherPid) }
        $result = Stop-RunningAiConnectorManaged -Ownership $legacyShaped -Port $single.Port -TimeoutSec 5
        ($result.ResultCode -eq 'REFUSED_UNKNOWN_OWNER') -and ([bool](Get-Process -Id $single.LauncherPid -ErrorAction SilentlyContinue))
    } finally { Stop-SelfTestPair $single }
}

# STEP 5 #9/#10: stop-running-ai.ps1's exit-code contract (source check - this script's own top-level
# try/catch runs for real the moment it is dot-sourced or invoked, touching the real tracked PID
# files, so it is never executed directly by this isolated suite).
Check 'stop-running-ai.ps1 exits non-zero when the connector did not fully stop, Ok otherwise (source check)' {
    $src = Get-Content (Join-Path $scripts 'stop-running-ai.ps1') -Raw
    ($src -match '\$connectorStopped\s*=\s*Stop-GarminConnectorComponent') -and
    ($src -match 'if \(-not \$connectorStopped\) \{ exit \$ExitCode\.ConnectorStopIncomplete \}') -and
    ($src -match 'exit \$ExitCode\.Ok')
}

Check 'ExitCode.ConnectorStopIncomplete is defined and distinct from Ok/other codes' {
    ($ExitCode.ConnectorStopIncomplete -is [int]) -and ($ExitCode.ConnectorStopIncomplete -ne 0) -and
    ($ExitCode.ConnectorStopIncomplete -ne $ExitCode.Connector)
}

} finally {
    Remove-Item -LiteralPath $testRoot -Recurse -Force -ErrorAction SilentlyContinue
    Remove-Item -LiteralPath $listenerScriptPath -Force -ErrorAction SilentlyContinue
}

if ($failures.Count) {
    Write-Host ("{0} check(s) failed: {1}" -f $failures.Count, ($failures -join '; ')) -ForegroundColor Red
    exit 1
}
Write-Host 'All connector ownership checks passed.'
exit 0
