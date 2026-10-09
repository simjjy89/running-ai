<#
.SYNOPSIS
  Phase 6I-1 checks for the external-relay lifecycle scripts (scripts/windows/external).
  No node process, no real port 17845, no network beyond a local HttpListener: every "relay"
  response comes from a disposable fake HttpListener on an unused test port.

  Exit code 0 when all checks pass.
#>
$ErrorActionPreference = 'Stop'
$here = $PSScriptRoot
$scripts = Split-Path $here -Parent
$repo = (Resolve-Path (Join-Path $scripts '..\..')).Path
$external = Join-Path $scripts 'external'

. (Join-Path $scripts 'RunningAI.Common.ps1')
. (Join-Path $external 'RunningAI.ExternalRelay.Common.ps1')

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

function Start-FakeJsonListener {
    param([Parameter(Mandatory)][int]$Port, [Parameter(Mandatory)][string]$Json, [int]$Status = 200)
    $listener = New-Object System.Net.HttpListener
    $listener.Prefixes.Add("http://127.0.0.1:$Port/")
    $listener.Start()
    $job = [powershell]::Create().AddScript({
        param($l, $json, $status)
        $ctx = $l.GetContext()
        $bytes = [System.Text.UTF8Encoding]::new($false).GetBytes($json)
        $ctx.Response.StatusCode = $status
        $ctx.Response.ContentType = 'application/json'
        $ctx.Response.OutputStream.Write($bytes, 0, $bytes.Length)
        $ctx.Response.Close()
    }).AddArgument($listener).AddArgument($Json).AddArgument($Status)
    $handle = $job.BeginInvoke()
    return @{ Job = $job; Handle = $handle; Listener = $listener }
}

function Stop-FakeJsonListener {
    param([Parameter(Mandatory)]$Pending, [int]$TimeoutSec = 10)
    try { $Pending.Handle.AsyncWaitHandle.WaitOne($TimeoutSec * 1000) | Out-Null }
    finally { $Pending.Job.Dispose(); $Pending.Listener.Stop(); $Pending.Listener.Close() }
}

Check 'all external-relay scripts parse without errors' {
    foreach ($f in Get-ChildItem $external -Filter *.ps1) {
        $errors = $null
        [void][System.Management.Automation.Language.Parser]::ParseFile($f.FullName, [ref]$null, [ref]$errors)
        if ($errors.Count) { throw "$($f.Name): $($errors[0].Message)" }
    }
}

Check 'Get-ExternalRelayConfiguredPort reads the committed tools/external-relay/config.json port' {
    (Get-ExternalRelayConfiguredPort) -eq 17845
}

Check 'Get-ExternalRelayMarkers identifies server.js under this repository, nothing else' {
    $markers = Get-ExternalRelayMarkers
    ($markers -contains 'server.js') -and ($markers | Where-Object { $_ -like "*tools\external-relay*" })
}

Check 'Test-ExternalRelayHealth is true for a {"status":"ok"} 200 response' {
    $pending = Start-FakeJsonListener -Port 18901 -Json '{"status":"ok"}'
    try { Test-ExternalRelayHealth 18901 } finally { Stop-FakeJsonListener $pending }
}

Check 'Test-ExternalRelayHealth is false for a different status value' {
    $pending = Start-FakeJsonListener -Port 18902 -Json '{"status":"degraded"}'
    try { -not (Test-ExternalRelayHealth 18902) } finally { Stop-FakeJsonListener $pending }
}

Check 'Test-ExternalRelayHealth is false for a non-2xx response' {
    $pending = Start-FakeJsonListener -Port 18903 -Json '{"status":"ok"}' -Status 500
    try { -not (Test-ExternalRelayHealth 18903) } finally { Stop-FakeJsonListener $pending }
}

Check 'Test-ExternalRelayHealth is false when nothing is listening' {
    -not (Test-ExternalRelayHealth 18904)
}

Check 'start-external-relay.ps1 never contains a hard-coded legacy repo path' {
    $hits = Get-ChildItem $external -Filter *.ps1 | Select-String -Pattern 'C:\\running-ai|C:\\Users\\'
    @($hits).Count -eq 0
}

Check 'start-external-relay.ps1 refuses to start a second process on an unhealthy occupied port' {
    # A plain TCP listener (no HTTP) on the configured port: Test-PortInUse is true, but
    # Test-ExternalRelayHealth is false - the script must exit non-zero without starting node.
    $listener = New-Object System.Net.Sockets.TcpListener([System.Net.IPAddress]::Loopback, 18905)
    $listener.Start()
    try {
        $psExe = (Get-Command powershell.exe).Source
        $scriptPath = Join-Path $external 'start-external-relay.ps1'
        $proc = Start-Process -FilePath $psExe -ArgumentList @(
            '-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', "`"$scriptPath`"", '-Port', '18905'
        ) -WindowStyle Hidden -PassThru -Wait -RedirectStandardOutput ([System.IO.Path]::GetTempFileName()) -RedirectStandardError ([System.IO.Path]::GetTempFileName())
        $proc.ExitCode -ne 0
    } finally { $listener.Stop() }
}

# ---- Phase 6I-1.1: isolated PID-file tests ----------------------------------------------------
# RUNNING_AI_TEST_RUNTIME_DIR redirects .runtime (PID files, logs) to a throwaway temp directory
# for the duration of each test below - set as an ENVIRONMENT VARIABLE (not just $script:RuntimeDir)
# specifically so a SPAWNED CHILD PROCESS (these tests invoke the real start-/stop-/
# status-external-relay.ps1 via Start-Process, which dot-source RunningAI.Common.ps1 fresh in
# their own process) also sees the redirect - without this, a child process would read/write the
# REAL .runtime\external-relay.pid, which may be tracking a real, currently-running production
# relay. Always restored in `finally`, both the env var and this process's own $script:RuntimeDir.

function Invoke-WithIsolatedRuntimeDir {
    param([Parameter(Mandatory)][scriptblock]$Body)
    $originalEnv = $env:RUNNING_AI_TEST_RUNTIME_DIR
    $originalScriptVar = $script:RuntimeDir
    $temp = Join-Path $env:TEMP "selftest-relay-runtimedir-$([guid]::NewGuid().ToString('N'))"
    $env:RUNNING_AI_TEST_RUNTIME_DIR = $temp
    $script:RuntimeDir = $temp
    try { & $Body } finally {
        $env:RUNNING_AI_TEST_RUNTIME_DIR = $originalEnv
        $script:RuntimeDir = $originalScriptVar
        Remove-Item -Recurse -Force $temp -ErrorAction SilentlyContinue
    }
}

Check 'start-external-relay.ps1: an already-healthy MANAGED target is never restarted (duplicate start 0)' {
    Invoke-WithIsolatedRuntimeDir {
        $pending = Start-FakeJsonListener -Port 18906 -Json '{"status":"ok"}'
        try {
            Write-PidFile 'external-relay' $PID   # any real, currently-running PID is enough to exist
            $psExe = (Get-Command powershell.exe).Source
            $scriptPath = Join-Path $external 'start-external-relay.ps1'
            $out = [System.IO.Path]::GetTempFileName()
            $proc = Start-Process -FilePath $psExe -ArgumentList @(
                '-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', "`"$scriptPath`"", '-Port', '18906'
            ) -WindowStyle Hidden -PassThru -Wait -RedirectStandardOutput $out -RedirectStandardError ([System.IO.Path]::GetTempFileName())
            $text = Get-Content -LiteralPath $out -Raw
            ($proc.ExitCode -eq 0) -and ($text -match 'already running, not restarted')
        } finally { Stop-FakeJsonListener $pending }
    }
}

Check 'start-external-relay.ps1: an already-healthy FOREIGN (untracked) target is never killed or replaced' {
    Invoke-WithIsolatedRuntimeDir {
        $pending = Start-FakeJsonListener -Port 18907 -Json '{"status":"ok"}'
        try {
            # Deliberately no PID file at all - this simulates a healthy relay RunningAI never started.
            $psExe = (Get-Command powershell.exe).Source
            $scriptPath = Join-Path $external 'start-external-relay.ps1'
            $out = [System.IO.Path]::GetTempFileName()
            $proc = Start-Process -FilePath $psExe -ArgumentList @(
                '-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', "`"$scriptPath`"", '-Port', '18907'
            ) -WindowStyle Hidden -PassThru -Wait -RedirectStandardOutput $out -RedirectStandardError ([System.IO.Path]::GetTempFileName())
            $text = Get-Content -LiteralPath $out -Raw
            ($proc.ExitCode -eq 0) -and ($text -match 'already running, not restarted') -and
                (-not (Test-Path -LiteralPath (Get-PidFilePath 'external-relay')))   # never claimed it as managed
        } finally { Stop-FakeJsonListener $pending }
    }
}

Check 'stop-external-relay.ps1: only kills a process whose live command line actually matches ours - a look-alike is left running' {
    Invoke-WithIsolatedRuntimeDir {
        # A real, harmless dummy process whose command line does NOT contain 'server.js' or this
        # repository's tools\external-relay path - Get-ExternalRelayMarkers must reject it.
        $dummy = Start-Process -FilePath (Get-Command powershell.exe).Source -ArgumentList '-NoProfile -Command "Start-Sleep -Seconds 60"' -WindowStyle Hidden -PassThru
        try {
            Write-PidFile 'external-relay' $dummy.Id
            $psExe = (Get-Command powershell.exe).Source
            $scriptPath = Join-Path $external 'stop-external-relay.ps1'
            $out = [System.IO.Path]::GetTempFileName()
            $proc = Start-Process -FilePath $psExe -ArgumentList @('-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', "`"$scriptPath`"") `
                -WindowStyle Hidden -PassThru -Wait -RedirectStandardOutput $out -RedirectStandardError ([System.IO.Path]::GetTempFileName())
            $text = Get-Content -LiteralPath $out -Raw
            $stillRunning = [bool](Get-Process -Id $dummy.Id -ErrorAction SilentlyContinue)
            ($proc.ExitCode -eq 0) -and ($text -match 'not running under RunningAI control') -and $stillRunning
        } finally {
            Stop-Process -Id $dummy.Id -Force -ErrorAction SilentlyContinue
        }
    }
}

Check 'status-external-relay.ps1: reports managed/PID when tracked, unmanaged when healthy-but-untracked, DOWN when nothing listens' {
    $results = Invoke-WithIsolatedRuntimeDir {
        $r = [ordered]@{}

        $pending = Start-FakeJsonListener -Port 18908 -Json '{"status":"ok"}'
        # A dummy process whose COMMAND LINE actually contains both markers ('server.js' and the
        # relay directory) - Get-TrackedProcessId validates the live command line, not just PID
        # existence, so a PID file pointing at a process that does not genuinely match (e.g. this
        # test's own $PID) is correctly treated as stale and removed. The markers are embedded as a
        # harmless leading comment line so they appear verbatim in the process's command line.
        $cmdText = "# $((Get-ExternalRelayMarkers) -join ' ')`nStart-Sleep -Seconds 60"
        $lookalike = Start-Process -FilePath (Get-Command powershell.exe).Source -ArgumentList @('-NoProfile', '-Command', $cmdText) -WindowStyle Hidden -PassThru
        try {
            Write-PidFile 'external-relay' $lookalike.Id
            $out1 = [System.IO.Path]::GetTempFileName()
            Start-Process -FilePath (Get-Command powershell.exe).Source -ArgumentList @(
                '-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', "`"$(Join-Path $external 'status-external-relay.ps1')`"", '-Port', '18908'
            ) -WindowStyle Hidden -Wait -RedirectStandardOutput $out1 -RedirectStandardError ([System.IO.Path]::GetTempFileName())
            $r.Managed = Get-Content -LiteralPath $out1 -Raw
        } finally {
            Stop-FakeJsonListener $pending
            Stop-Process -Id $lookalike.Id -Force -ErrorAction SilentlyContinue
        }

        Remove-PidFile 'external-relay'
        $pending2 = Start-FakeJsonListener -Port 18909 -Json '{"status":"ok"}'
        try {
            $out2 = [System.IO.Path]::GetTempFileName()
            Start-Process -FilePath (Get-Command powershell.exe).Source -ArgumentList @(
                '-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', "`"$(Join-Path $external 'status-external-relay.ps1')`"", '-Port', '18909'
            ) -WindowStyle Hidden -Wait -RedirectStandardOutput $out2 -RedirectStandardError ([System.IO.Path]::GetTempFileName())
            $r.Unmanaged = Get-Content -LiteralPath $out2 -Raw
        } finally { Stop-FakeJsonListener $pending2 }

        $out3 = [System.IO.Path]::GetTempFileName()
        Start-Process -FilePath (Get-Command powershell.exe).Source -ArgumentList @(
            '-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', "`"$(Join-Path $external 'status-external-relay.ps1')`"", '-Port', '18910'
        ) -WindowStyle Hidden -Wait -RedirectStandardOutput $out3 -RedirectStandardError ([System.IO.Path]::GetTempFileName())
        $r.Down = Get-Content -LiteralPath $out3 -Raw

        $r
    }
    ($results.Managed -match 'UP' -and $results.Managed -match 'managed') -and
        ($results.Unmanaged -match 'UP' -and $results.Unmanaged -match 'not started by RunningAI') -and
        ($results.Down -match 'DOWN')
}

Write-Host ''
if ($failures.Count -gt 0) {
    Write-Host "FAILED: $($failures.Count) check(s) failed." -ForegroundColor Red
    exit 1
}
Write-Host 'All external-relay checks passed.' -ForegroundColor Green
exit 0
