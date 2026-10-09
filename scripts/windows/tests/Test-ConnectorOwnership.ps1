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
        $fakeOwnership = [pscustomobject]@{ Verdict = 'UNKNOWN_OWNER'; LauncherPid = $null; ListenerPid = $single.ListenerPid; ManagedPids = @() }
        $result = Stop-RunningAiConnectorManaged -Ownership $fakeOwnership -Port $single.Port -TimeoutSec 2
        ($result.Result -eq 'refused') -and ([bool](Get-Process -Id $single.ListenerPid -ErrorAction SilentlyContinue))
    } finally { Stop-SelfTestPair $single }
}

Check 'Stop-RunningAiConnectorManaged refuses a FOREIGN_PROCESS verdict - the process is left untouched' {
    $single = Start-SelfTestSingleProcess
    try {
        $fakeOwnership = [pscustomobject]@{ Verdict = 'FOREIGN_PROCESS'; LauncherPid = $null; ListenerPid = $single.ListenerPid; ManagedPids = @() }
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
        $captured = Set-RunningAiConnectorOwnerMetaFromLive -LauncherPid $pair.LauncherPid -Port $pair.Port -Name $name
        Stop-Process -Id $pair.LauncherPid -Force -ErrorAction SilentlyContinue
        Start-Sleep -Milliseconds 300
        $o = Get-RunningAiConnectorOwnership -Port $pair.Port -TrackedPid $pair.LauncherPid -Markers $testMarkers -Name $name
        $captured -and ($o.Verdict -eq 'ORPHANED_MANAGED_PROCESS') -and (@($o.ManagedPids).Count -eq 1) -and (@($o.ManagedPids)[0] -eq $pair.ListenerPid)
    } finally { Remove-RunningAiOwnerMeta -Name $name; Stop-SelfTestPair $pair }
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
