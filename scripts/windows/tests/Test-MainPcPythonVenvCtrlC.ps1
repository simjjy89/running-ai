<#
.SYNOPSIS
  Phase 6I-1.7C-0, STEP C: Main PC isolated verification of the connector ownership/graceful-stop
  model against a REAL Python 3.12 venv process - not the PowerShell stand-in processes
  Test-ConnectorOwnership.ps1 uses. Confirms the real Garmin-connector-shaped process split (the
  tracked venv "Scripts\python.exe" process, and the separate python.exe process that actually binds
  the port) is classified, signaled and (gracefully or, on Ctrl+C failure, safely forcibly) stopped
  correctly by the SAME production code (Get-RunningAiConnectorOwnership / Stop-RunningAiConnectorManaged
  / Send-CtrlC.ps1) already covered by Test-ConnectorOwnership.ps1.

.DESCRIPTION
  Never touches the real Garmin connector, port 8765, the real .runtime directory, or any operational
  file: every venv, script, port and PID here is newly created in a disposable temp directory and torn
  down at the end of this run. Does not call start-running-ai.ps1, Docker or PostgreSQL. Requires a
  Python 3.12 interpreter to be discoverable (via "py -3.12" or "python3.12") - if none is found, every
  check is skipped with a clear note rather than failing or silently falling back to a different Python
  version (STEP C explicitly asks for 3.12, matching the real Garmin connector's venv).

  IMPORTANT FINDING (recorded here, not just in the work-order report, so a future reader of this file
  understands why the test is shaped the way it is - see docs/work-orders/2026-10-11-phase-6i-1-7c-0-*
  -result.md for the full trace that led to this): this Main PC's available Python 3.12 (installed via
  the newer Python Install Manager, "...\Python\pythoncore-3.12-64\python.exe") makes its OWN venv's
  "Scripts\python.exe" a trampoline that immediately re-execs into a SECOND, real engine process for
  EVERY invocation of a .py SCRIPT FILE - confirmed live against the REAL Garmin connector itself,
  which runs on THIS SAME Main PC's venv and shows the IDENTICAL two-PID shape (a tracked
  "tools\garmin-connector\.venv\Scripts\python.exe" process whose live direct child is the real
  "pythoncore-3.12-64\python.exe" engine actually bound to port 8765). An EARLIER draft of this test
  deliberately modeled an ADDITIONAL, separate "launcher.py spawns interpreter.py as its own
  subprocess" layer on top of this - that produced a FOUR-process chain (trampoline->engine->
  trampoline->engine) that does not match how the real connector is actually invoked (one Python
  script, one Start-Process call) and caused two false test failures (an ownership-resolution mismatch,
  and a "bystander survived" check whose own bystander process was itself a trampoline that had already
  exited by the time it was checked). This file now mirrors the REAL connector's actual invocation
  shape exactly: ONE script, bound via ONE Start-Process call - which on this machine still produces
  the trampoline+engine pair needed to exercise Get-RunningAiConnectorOwnership's LAUNCHER_CHILD
  classification against 100% real python.exe processes, with no artificial extra nesting.

  If Ctrl+C delivery does not work in this execution environment (a known, previously-documented
  sandboxed Win32 console-signal-delivery limitation - see Test-ConnectorOwnership.ps1's own two
  long-standing failing checks, deliberately left untouched), the graceful-stop check here is expected
  to fail the SAME way, and the forced-fallback check that follows it is what actually matters: it must
  still stop only the verified managed PIDs, free the port, and leave an unrelated bystander process
  completely untouched.

  Exit code 0 when every runnable check passes (or every check is cleanly skipped because Python 3.12
  is unavailable); 1 if any check fails.
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

function Get-RunningAiTestPython312Exe {
    $py = Get-Command py -ErrorAction SilentlyContinue
    if ($py) {
        $probe = & $py.Source '-3.12' '-c' 'import sys; print(sys.executable)' 2>$null
        if ($LASTEXITCODE -eq 0 -and $probe) { return $probe.Trim() }
    }
    $direct = Get-Command python3.12 -ErrorAction SilentlyContinue
    if ($direct) { return $direct.Source }
    return $null
}

$python312 = Get-RunningAiTestPython312Exe
if (-not $python312) {
    Write-Host 'SKIP  No Python 3.12 interpreter found (py -3.12 / python3.12) - every check in this file is skipped. Install Python 3.12 to exercise the real venv launcher/interpreter structure.' -ForegroundColor Yellow
    exit 0
}
Write-Host "Using Python 3.12 at: $python312"

function Get-SelfTestPort { Get-Random -Minimum 20000 -Maximum 40000 }

function Wait-ForPortListening {
    param([int]$Port, [int]$TimeoutSec = 15)
    $deadline = (Get-Date).AddSeconds($TimeoutSec)
    while ((Get-Date) -lt $deadline) {
        if ([bool](Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue)) { return $true }
        Start-Sleep -Milliseconds 200
    }
    return $false
}

# Kills every live process in the subtree rooted at $RootProcessId. On this machine a single venv
# python.exe invocation is actually TWO real OS processes (trampoline + engine, see the header
# comment) - walking the tree (rather than assuming exactly one child) is what makes cleanup reliable
# regardless of exactly how many hops this Python distribution inserts.
function Stop-RunningAiTestProcessTree {
    param([int]$RootProcessId)
    $toVisit = New-Object System.Collections.Generic.Queue[int]
    $toVisit.Enqueue($RootProcessId)
    $all = New-Object System.Collections.Generic.List[int]
    while ($toVisit.Count -gt 0) {
        $current = $toVisit.Dequeue()
        if ($all.Contains($current)) { continue }
        $all.Add($current)
        Get-CimInstance Win32_Process -Filter "ParentProcessId=$current" -ErrorAction SilentlyContinue | ForEach-Object { $toVisit.Enqueue([int]$_.ProcessId) }
    }
    foreach ($p in $all) { Stop-Process -Id $p -Force -ErrorAction SilentlyContinue }
}

$testRoot = Join-Path ([IO.Path]::GetTempPath()) ('ra-pyvenv-ctrlc-' + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Force $testRoot | Out-Null
$venvDir = Join-Path $testRoot 'venv'
$venvPython = Join-Path $venvDir 'Scripts\python.exe'
$testMarkers = @($testRoot)   # the listener script lives under $testRoot, so this is a genuine substring of its own command line

try {
    Write-Host "Creating disposable Python 3.12 venv at $venvDir ..."
    & $python312 '-m' 'venv' $venvDir
    if ($LASTEXITCODE -ne 0 -or -not (Test-Path -LiteralPath $venvPython)) {
        Write-Host 'SKIP  Could not create a Python 3.12 venv - every check in this file is skipped.' -ForegroundColor Yellow
        exit 0
    }

    # listener.py: the ONLY script, mirroring the real Garmin connector's own single-script invocation
    # (no artificial extra subprocess layer) - binds the loopback port, installs a SIGINT handler that
    # closes the socket and exits 0 on a clean Ctrl+C, otherwise just sleeps.
    $listenerScript = Join-Path $testRoot 'listener.py'
    @'
import socket
import sys
import signal
import time

port = int(sys.argv[sys.argv.index("--port") + 1])
srv = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
srv.bind(("127.0.0.1", port))
srv.listen(1)

running = {"value": True}

def handler(signum, frame):
    running["value"] = False

signal.signal(signal.SIGINT, handler)

try:
    while running["value"]:
        time.sleep(0.2)
finally:
    try:
        srv.close()
    except Exception:
        pass

sys.exit(0)
'@ | Set-Content -LiteralPath $listenerScript -Encoding utf8

    # listener_ignore_sigint.py (Phase 6I-1.7D-2R, STEP 2): a SECOND, separate fixture script used only
    # by the forced-fallback check below. Identical socket/bind/listen shape to listener.py above, except
    # it installs signal.SIG_IGN for SIGINT instead of a handler that exits cleanly - so a real Ctrl+C
    # delivered to it is GUARANTEED to have no effect, deterministically forcing
    # Stop-RunningAiConnectorManaged's own graceful-wait-then-kill logic to actually reach and exercise
    # its forced path, rather than depending on this execution environment's current (and, per Phase
    # 6I-1.7D-2's own investigation, apparently now variable) Ctrl+C delivery timing. The original
    # listener.py is left completely unchanged and still used by the "graceful Ctrl+C" check above - this
    # phase does not touch that scenario.
    $ignoreSigintListenerScript = Join-Path $testRoot 'listener_ignore_sigint.py'
    @'
import socket
import sys
import signal
import time

signal.signal(signal.SIGINT, signal.SIG_IGN)

port = int(sys.argv[sys.argv.index("--port") + 1])
srv = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
srv.bind(("127.0.0.1", port))
srv.listen(1)

while True:
    time.sleep(0.2)
'@ | Set-Content -LiteralPath $ignoreSigintListenerScript -Encoding utf8

    function Start-RunningAiTestVenvPair {
        param([string]$ListenerScript = $listenerScript)
        $port = Get-SelfTestPort
        # Exactly how start-running-ai.ps1 invokes the real connector: one Start-Process call against
        # the venv's own python.exe with a script file argument. $trackedPid is what Write-PidFile would
        # record in a real deployment.
        $tracked = Start-Process -FilePath $venvPython -ArgumentList @($ListenerScript, '--port', $port) -WindowStyle Hidden -PassThru
        if (-not (Wait-ForPortListening -Port $port)) {
            Stop-RunningAiTestProcessTree -RootProcessId $tracked.Id
            throw "test listener never bound port $port"
        }
        Start-Sleep -Milliseconds 300   # let Win32_Process's parent/child relationship settle
        $conn = Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue | Select-Object -First 1
        if (-not $conn) { Stop-RunningAiTestProcessTree -RootProcessId $tracked.Id; throw "could not resolve the real LISTEN owner for port $port" }
        $listenerPid = [int]$conn.OwningProcess
        if (-not (Get-Process -Id $tracked.Id -ErrorAction SilentlyContinue)) {
            Stop-RunningAiTestProcessTree -RootProcessId $listenerPid
            throw "the tracked Start-Process PID $($tracked.Id) already exited - this machine's Python did not keep the trampoline alive as the real connector's venv does; cannot model LAUNCHER_CHILD here"
        }
        [pscustomobject]@{ Port = $port; LauncherPid = [int]$tracked.Id; ListenerPid = $listenerPid }
    }

    function Stop-RunningAiTestVenvPair {
        param($Pair)
        Stop-RunningAiTestProcessTree -RootProcessId $Pair.LauncherPid
        Stop-RunningAiTestProcessTree -RootProcessId $Pair.ListenerPid
    }

    # ---- items 1-5: real venv creation, launcher/interpreter split, loopback LISTEN, ownership -------

    Check 'real Python 3.12 venv process: the tracked Start-Process PID and the real LISTEN-owning PID are different live python.exe processes' {
        $pair = Start-RunningAiTestVenvPair
        try {
            ($pair.LauncherPid -ne $pair.ListenerPid) -and
            ([bool](Get-Process -Id $pair.LauncherPid -ErrorAction SilentlyContinue)) -and
            ([bool](Get-Process -Id $pair.ListenerPid -ErrorAction SilentlyContinue))
        } finally { Stop-RunningAiTestVenvPair $pair }
    }

    Check 'Get-RunningAiConnectorOwnership resolves the real venv launcher/interpreter pair as LAUNCHER_CHILD, both PIDs managed' {
        $pair = Start-RunningAiTestVenvPair
        try {
            $o = Get-RunningAiConnectorOwnership -Port $pair.Port -TrackedPid $pair.LauncherPid -Markers $testMarkers -Name ('pyvenv-ownership-' + [guid]::NewGuid().ToString('N'))
            ($o.Verdict -eq 'LAUNCHER_CHILD') -and
            ($o.LauncherPid -eq $pair.LauncherPid) -and ($o.ListenerPid -eq $pair.ListenerPid) -and
            (@($o.ManagedPids) -contains $pair.LauncherPid) -and (@($o.ManagedPids) -contains $pair.ListenerPid)
        } finally { Stop-RunningAiTestVenvPair $pair }
    }

    # ---- items 6-7: Ctrl+C delivery and graceful shutdown (expected to hit the known environmental --
    # limitation already documented in Test-ConnectorOwnership.ps1 - left as a real, unmodified check) --

    $gracefulResult = $null
    Check 'real Python 3.12 venv pair: Ctrl+C is sent to both launcher and interpreter and BOTH exit gracefully (port freed, no force needed)' {
        $pair = Start-RunningAiTestVenvPair
        try {
            $o = Get-RunningAiConnectorOwnership -Port $pair.Port -TrackedPid $pair.LauncherPid -Markers $testMarkers -Name ('pyvenv-stop-' + [guid]::NewGuid().ToString('N'))
            $script:gracefulResult = Stop-RunningAiConnectorManaged -Ownership $o -Port $pair.Port -TimeoutSec 20 -Name ('pyvenv-stop-' + [guid]::NewGuid().ToString('N'))
            ($script:gracefulResult.Result -eq 'graceful') -and ($script:gracefulResult.PortFreed) -and
            (-not (Get-Process -Id $pair.LauncherPid -ErrorAction SilentlyContinue)) -and
            (-not (Get-Process -Id $pair.ListenerPid -ErrorAction SilentlyContinue))
        } finally { Stop-RunningAiTestVenvPair $pair }
    }

    if ($gracefulResult -and $gracefulResult.Result -eq 'graceful') {
        Write-Host 'NOTE  Ctrl+C delivery to real Python 3.12 processes SUCCEEDED in this environment (graceful shutdown confirmed end to end).'
    } else {
        Write-Host 'NOTE  Ctrl+C delivery to real Python 3.12 processes did NOT produce a graceful shutdown in this environment - consistent with the pre-existing, already-documented sandboxed Win32 console-signal-delivery limitation (Test-ConnectorOwnership.ps1''s two long-standing failing checks). The forced-fallback checks below are what establish safety in that case.' -ForegroundColor Yellow
    }

    # ---- items 8-10: forced fallback safety, port release, bystander preservation --------------------

    # Phase 6I-1.7D-2R (STEP 1-3): the previous version of this check used the ordinary,
    # SIGINT-cooperative listener.py and TimeoutSec 0, assuming TimeoutSec 0 would "skip straight to the
    # forced path" regardless of whether Ctrl+C worked. That assumption broke once this environment's
    # Ctrl+C delivery started succeeding quickly enough that Stop-RunningAiConnectorManaged's very first
    # Wait-Until check already observed the process gone - returning a genuine, correct 'graceful' result
    # instead of 'forced' (root-caused in the Phase 6I-1.7D-2 result doc; not a production defect, and the
    # safety guarantees the check cares about - bystander untouched, managed PIDs gone, port freed - held
    # either way). Rather than loosen the assertion (explicitly forbidden - the point is to prove the
    # FORCED code path itself), this check now uses the dedicated listener_ignore_sigint.py fixture, whose
    # SIG_IGN makes "Ctrl+C has no effect" a guaranteed fact of the fixture rather than a timing accident -
    # and explicitly VERIFIES that fact (by sending a real Ctrl+C and confirming survival) before ever
    # calling the real Stop-RunningAiConnectorManaged, so a fixture regression fails loudly here instead
    # of silently producing a false pass.
    Check 'real Python 3.12 venv pair (SIGINT-ignoring fixture): Ctrl+C has no effect (verified), so forced fallback genuinely engages and stops ONLY the verified managed PIDs, frees the port, and a bystander python.exe survives untouched' {
        $pair = Start-RunningAiTestVenvPair -ListenerScript $ignoreSigintListenerScript
        $bystanderScript = Join-Path $testRoot ('bystander-' + [guid]::NewGuid().ToString('N') + '.py')
        'import time' + "`n" + 'time.sleep(120)' | Set-Content -LiteralPath $bystanderScript -Encoding utf8
        $bystanderTop = $null
        try {
            # A SCRIPT FILE (not a "-c" one-liner), same shape as the managed pair - a "-c" invocation on
            # this machine was found NOT to keep its trampoline PID alive, which would make it a poor
            # stand-in for "a live, unrelated process" (see the header comment for the full finding).
            $bystanderTop = Start-Process -FilePath $venvPython -ArgumentList @($bystanderScript) -WindowStyle Hidden -PassThru
            Start-Sleep -Milliseconds 500
            $bystanderChild = Get-CimInstance Win32_Process -Filter "ParentProcessId=$($bystanderTop.Id)" -ErrorAction SilentlyContinue | Select-Object -First 1
            $bystanderPid = if ($bystanderChild) { [int]$bystanderChild.ProcessId } else { $bystanderTop.Id }

            $o = Get-RunningAiConnectorOwnership -Port $pair.Port -TrackedPid $pair.LauncherPid -Markers $testMarkers -Name ('pyvenv-forced-' + [guid]::NewGuid().ToString('N'))
            # STEP 3 item 1-2: initial classification and snapshot, before anything is signaled.
            if ($o.Verdict -ne 'LAUNCHER_CHILD') { throw "expected initial Verdict LAUNCHER_CHILD, got $($o.Verdict)" }
            if (@($o.ManagedPidSnapshot).Count -ne 2) { throw "expected a 2-PID ManagedPidSnapshot (launcher+listener), got $(@($o.ManagedPidSnapshot).Count)" }

            # STEP 2's own precondition: prove SIGINT is genuinely ignored by THIS fixture, independently
            # of Stop-RunningAiConnectorManaged, using the same per-PID Send-CtrlC.ps1 helper that function
            # calls internally. If the listener does not survive this, the fixture itself is broken and
            # this check must fail here - never silently fall through to a false pass.
            $sendCtrlC = Join-Path $scripts 'Send-CtrlC.ps1'
            $ps = (Get-Command powershell.exe).Source
            foreach ($candidatePid in @($pair.LauncherPid, $pair.ListenerPid)) {
                & $ps -NoProfile -ExecutionPolicy Bypass -File $sendCtrlC -ProcessId $candidatePid | Out-Null
            }
            Start-Sleep -Seconds 2
            $listenerSurvivedProbe = [bool](Get-Process -Id $pair.ListenerPid -ErrorAction SilentlyContinue)
            $launcherSurvivedProbe = [bool](Get-Process -Id $pair.LauncherPid -ErrorAction SilentlyContinue)
            $portStillUsedAfterProbe = [bool](Get-NetTCPConnection -LocalPort $pair.Port -State Listen -ErrorAction SilentlyContinue)
            if (-not ($listenerSurvivedProbe -and $launcherSurvivedProbe -and $portStillUsedAfterProbe)) {
                throw 'SIGINT-ignore fixture precondition failed: a direct Ctrl+C probe stopped the listener/launcher or freed the port - the fixture no longer guarantees "Ctrl+C has no effect", so the forced-fallback code path below cannot be trusted and this check must not be reported as a pass'
            }

            # STEP 3 items 3-9: the REAL production function, given a listener proven immune to Ctrl+C -
            # a non-zero TimeoutSec so its own Wait-Until loop genuinely signals, waits, and times out
            # (never bypassed via TimeoutSec 0) before falling through to the forced kill.
            $result = Stop-RunningAiConnectorManaged -Ownership $o -Port $pair.Port -TimeoutSec 3 -Name ('pyvenv-forced-' + [guid]::NewGuid().ToString('N'))

            # STEP 3 item 12 (PID-reuse/CreationDate re-verification immediately before the forced kill) is
            # exercised end to end by the separate tamper check below, against this same real venv pair
            # shape - not duplicated here to keep this check focused on the forced-path outcome itself.
            ($result.Result -eq 'forced') -and ($result.ResultCode -eq 'STOPPED') -and ($result.PortFreed) -and
            (@($result.RemainingPids).Count -eq 0) -and
            (-not (Get-Process -Id $pair.LauncherPid -ErrorAction SilentlyContinue)) -and
            (-not (Get-Process -Id $pair.ListenerPid -ErrorAction SilentlyContinue)) -and
            ([bool](Get-Process -Id $bystanderPid -ErrorAction SilentlyContinue))
        } finally {
            Stop-RunningAiTestVenvPair $pair
            if ($bystanderTop) { Stop-RunningAiTestProcessTree -RootProcessId $bystanderTop.Id }
        }
    }

    Check 'real Python 3.12 venv pair: PID reused with a mismatched CreationDate during stop is refused as ownership-changed, never force-killed blindly' {
        $pair = Start-RunningAiTestVenvPair
        try {
            $o = Get-RunningAiConnectorOwnership -Port $pair.Port -TrackedPid $pair.LauncherPid -Markers $testMarkers -Name ('pyvenv-tamper-' + [guid]::NewGuid().ToString('N'))
            # Tamper the classification-time snapshot's CreationDate so it no longer matches the live
            # process - the same "stop-time identity must still match classification-time identity"
            # guarantee Test-ConnectorOwnership.ps1 already proves with PowerShell stand-ins, re-run here
            # against the real python.exe pair to confirm it is not shape-specific.
            $tamperedSnapshot = @($o.ManagedPidSnapshot | ForEach-Object {
                [pscustomobject]@{ ProcessId = $_.ProcessId; CreationDate = $_.CreationDate.AddDays(-1) }
            })
            $o.ManagedPidSnapshot = $tamperedSnapshot
            $result = Stop-RunningAiConnectorManaged -Ownership $o -Port $pair.Port -TimeoutSec 5 -Name ('pyvenv-tamper-' + [guid]::NewGuid().ToString('N'))
            ($result.ResultCode -eq 'OWNERSHIP_CHANGED') -and
            ([bool](Get-Process -Id $pair.LauncherPid -ErrorAction SilentlyContinue)) -and
            ([bool](Get-Process -Id $pair.ListenerPid -ErrorAction SilentlyContinue))
        } finally { Stop-RunningAiTestVenvPair $pair }
    }
} finally {
    Remove-Item -Recurse -Force $testRoot -ErrorAction SilentlyContinue
}

if ($failures.Count) {
    Write-Host ("{0} check(s) failed: {1}" -f $failures.Count, ($failures -join '; ')) -ForegroundColor Red
    exit 1
}
Write-Host 'All Main PC Python 3.12 venv Ctrl+C checks passed (or were safely skipped - see notes above).'
exit 0
