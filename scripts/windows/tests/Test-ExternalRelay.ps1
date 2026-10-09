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

# ---- Phase 6I-1.1: standalone .env loading -----------------------------------------------------
# These exercise the REAL start-external-relay.ps1 as a spawned child process, with a disposable
# fake relay directory (RUNNING_AI_TEST_RELAY_DIR) standing in for tools\external-relay and a
# disposable .env root (RUNNING_AI_TEST_ENV_ROOT) standing in for the repo root - never the real
# tools\external-relay or the real repo-root .env. The fake server.js is NOT the real relay: it
# only answers /health and a test-only /env-check that reports whether INTERVALS_ICU_API_KEY
# reached ITS process environment, and its length - never the value itself, and no network call.

function New-FakeRelayDir {
    param([Parameter(Mandatory)][int]$Port)
    $dir = Join-Path $env:TEMP "selftest-relaydir-$([guid]::NewGuid().ToString('N'))"
    New-Item -ItemType Directory -Force $dir | Out-Null
    New-Item -ItemType Directory -Force (Join-Path $dir 'secrets') | Out-Null
    Set-Content -LiteralPath (Join-Path $dir 'config.json') -Value (@{ port = $Port } | ConvertTo-Json) -Encoding ascii
    Set-Content -LiteralPath (Join-Path $dir 'secrets\watch-token.json') -Value '{"token":"selftest-token-not-a-real-credential"}' -Encoding ascii
    $serverJsLines = @(
        "const http = require('http');",
        "const fs = require('fs');",
        "const path = require('path');",
        "const BOM = String.fromCharCode(0xFEFF);",
        "let cfgRaw = fs.readFileSync(path.join(__dirname, 'config.json'), 'utf8');",
        "if (cfgRaw.charAt(0) === BOM) { cfgRaw = cfgRaw.slice(1); }",
        "const cfg = JSON.parse(cfgRaw);",
        "http.createServer((req, res) => {",
        "  if (req.url === '/health') {",
        "    res.writeHead(200, { 'Content-Type': 'application/json' });",
        "    return res.end(JSON.stringify({ status: 'ok' }));",
        "  }",
        "  if (req.url === '/env-check') {",
        "    const v = process.env.INTERVALS_ICU_API_KEY || '';",
        "    res.writeHead(200, { 'Content-Type': 'application/json' });",
        "    return res.end(JSON.stringify({ hasKey: v.length > 0, length: v.length }));",
        "  }",
        "  res.writeHead(404);",
        "  res.end();",
        "}).listen(cfg.port, '127.0.0.1');"
    )
    Set-Content -LiteralPath (Join-Path $dir 'server.js') -Value $serverJsLines -Encoding ascii
    return $dir
}

# Runs $Body with RUNNING_AI_TEST_RUNTIME_DIR and RUNNING_AI_TEST_RELAY_DIR both pointed at fresh
# throwaway directories (so a spawned child start-external-relay.ps1 never touches the real
# .runtime or tools\external-relay), stops whatever PID it tracked under 'external-relay', and
# restores every environment variable / $script: var it touched - INTERVALS_ICU_API_KEY included,
# since several checks below deliberately set or clear it on THIS process to prove what a spawned
# child does or does not inherit.
function Invoke-WithIsolatedRelay {
    param([Parameter(Mandatory)][int]$Port, [Parameter(Mandatory)][scriptblock]$Body)
    $originalRuntimeEnv = $env:RUNNING_AI_TEST_RUNTIME_DIR
    $originalRuntimeVar = $script:RuntimeDir
    $originalRelayEnv = $env:RUNNING_AI_TEST_RELAY_DIR
    $originalRelayVar = $script:RelayDir
    $originalApiKey = $env:INTERVALS_ICU_API_KEY
    $runtimeTemp = Join-Path $env:TEMP "selftest-relay-runtimedir-$([guid]::NewGuid().ToString('N'))"
    New-Item -ItemType Directory -Force (Join-Path $runtimeTemp 'logs') | Out-Null   # start-*.ps1 never creates this itself; a real run always already has it
    $relayDir = New-FakeRelayDir -Port $Port
    $env:RUNNING_AI_TEST_RUNTIME_DIR = $runtimeTemp
    $script:RuntimeDir = $runtimeTemp
    $env:RUNNING_AI_TEST_RELAY_DIR = $relayDir
    $script:RelayDir = $relayDir
    try { & $Body }
    finally {
        $tracked = Get-TrackedProcessId 'external-relay' (Get-ExternalRelayMarkers)
        if ($tracked) { Stop-Process -Id $tracked -Force -ErrorAction SilentlyContinue }
        $env:RUNNING_AI_TEST_RUNTIME_DIR = $originalRuntimeEnv
        $script:RuntimeDir = $originalRuntimeVar
        $env:RUNNING_AI_TEST_RELAY_DIR = $originalRelayEnv
        $script:RelayDir = $originalRelayVar
        if ($null -eq $originalApiKey) { Remove-Item Env:INTERVALS_ICU_API_KEY -ErrorAction SilentlyContinue }
        else { $env:INTERVALS_ICU_API_KEY = $originalApiKey }
        Remove-Item -Recurse -Force $runtimeTemp -ErrorAction SilentlyContinue
        Remove-Item -Recurse -Force $relayDir -ErrorAction SilentlyContinue
    }
}

function Invoke-StartExternalRelayChild {
    param([Parameter(Mandatory)][int]$Port, [Parameter(Mandatory)][string]$EnvRoot)
    $psExe = (Get-Command powershell.exe).Source
    $scriptPath = Join-Path $external 'start-external-relay.ps1'
    $out = [System.IO.Path]::GetTempFileName()
    $err = [System.IO.Path]::GetTempFileName()
    try {
        $saved = $env:RUNNING_AI_TEST_ENV_ROOT
        $env:RUNNING_AI_TEST_ENV_ROOT = $EnvRoot
        try {
            # Deliberately NOT "-Wait": when this child actually spawns node (the whole point of
            # these checks), node inherits this process's own redirected stdout/stderr FILE handles
            # (Start-Process/CreateProcess marks them inheritable), and because node is left running
            # detached, it keeps those handles open forever - which makes Start-Process -Wait's
            # WaitForExit() block indefinitely (live-reproduced: every other check here is safe only
            # because its fake listener answers /health immediately, so start-external-relay.ps1
            # returns BEFORE ever spawning node). Polling HasExited instead depends only on the
            # process's own exit, never on who else holds a handle to its output files.
            $proc = Start-Process -FilePath $psExe -ArgumentList @(
                '-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', "`"$scriptPath`"", '-Port', "$Port"
            ) -WindowStyle Hidden -PassThru -RedirectStandardOutput $out -RedirectStandardError $err
            $exited = Wait-Until -TimeoutSec 30 -PollSec 1 -Test { $proc.HasExited }
            if (-not $exited) { Stop-Process -Id $proc.Id -Force -ErrorAction SilentlyContinue; throw "start-external-relay.ps1 child (PID $($proc.Id)) did not exit within 30s" }
        } finally { $env:RUNNING_AI_TEST_ENV_ROOT = $saved }
        # $proc.ExitCode is unreliable here: a Process object from Start-Process -PassThru without
        # -Wait does not reliably expose it even after HasExited is true. Callers assert on StdOut
        # content instead (every code path below prints an unambiguous marker line).
        [pscustomobject]@{
            StdOut   = Get-Content -LiteralPath $out -Raw -ErrorAction SilentlyContinue
            StdErr   = Get-Content -LiteralPath $err -Raw -ErrorAction SilentlyContinue
        }
    } finally {
        Remove-Item -LiteralPath $out -ErrorAction SilentlyContinue
        Remove-Item -LiteralPath $err -ErrorAction SilentlyContinue
    }
}

function Get-EnvCheck {
    param([Parameter(Mandatory)][int]$Port)
    $body = Get-HttpBody "http://127.0.0.1:$Port/env-check"
    if (-not $body) { return $null }
    try { return ($body | ConvertFrom-Json) } catch { return $null }
}

Check 'start-external-relay.ps1: a standalone child with no inherited key picks up INTERVALS_ICU_API_KEY from a repo/test .env' {
    Invoke-WithIsolatedRelay -Port 18911 -Body {
        Remove-Item Env:INTERVALS_ICU_API_KEY -ErrorAction SilentlyContinue
        $envDir = Join-Path $env:TEMP "selftest-envroot-$([guid]::NewGuid().ToString('N'))"
        New-Item -ItemType Directory -Force $envDir | Out-Null
        try {
            $secret = "dotenv-$([guid]::NewGuid().ToString('N'))"   # 9 + 32 chars, never asserted on by value elsewhere
            Set-Content -LiteralPath (Join-Path $envDir '.env') -Value "INTERVALS_ICU_API_KEY=$secret" -Encoding utf8
            $result = Invoke-StartExternalRelayChild -Port 18911 -EnvRoot $envDir
            $check = Get-EnvCheck -Port 18911
            ($result.StdOut -match 'External relay: UP') -and $check -and $check.hasKey -and ($check.length -eq $secret.Length) -and
                (-not ($result.StdOut -match [regex]::Escape($secret))) -and (-not ($result.StdErr -match [regex]::Escape($secret)))
        } finally { Remove-Item -Recurse -Force $envDir -ErrorAction SilentlyContinue }
    }
}

Check 'start-external-relay.ps1: an existing process-env INTERVALS_ICU_API_KEY is never overridden by .env (precedence lock-in)' {
    Invoke-WithIsolatedRelay -Port 18912 -Body {
        $fromProcess = "procenv-$([guid]::NewGuid().ToString('N'))"
        $env:INTERVALS_ICU_API_KEY = $fromProcess
        $envDir = Join-Path $env:TEMP "selftest-envroot-$([guid]::NewGuid().ToString('N'))"
        New-Item -ItemType Directory -Force $envDir | Out-Null
        try {
            Set-Content -LiteralPath (Join-Path $envDir '.env') -Value 'INTERVALS_ICU_API_KEY=from-dotenv-should-be-ignored' -Encoding utf8
            $result = Invoke-StartExternalRelayChild -Port 18912 -EnvRoot $envDir
            $check = Get-EnvCheck -Port 18912
            ($result.StdOut -match 'External relay: UP') -and $check -and $check.hasKey -and ($check.length -eq $fromProcess.Length)
        } finally { Remove-Item -Recurse -Force $envDir -ErrorAction SilentlyContinue }
    }
}

Check 'start-external-relay.ps1: an already-healthy relay is still not restarted now that .env loading runs first (idempotency regression)' {
    Invoke-WithIsolatedRelay -Port 18913 -Body {
        $pending = Start-FakeJsonListener -Port 18913 -Json '{"status":"ok"}'
        try {
            Write-PidFile 'external-relay' $PID
            $envDir = Join-Path $env:TEMP "selftest-envroot-$([guid]::NewGuid().ToString('N'))"
            New-Item -ItemType Directory -Force $envDir | Out-Null
            try {
                $result = Invoke-StartExternalRelayChild -Port 18913 -EnvRoot $envDir
                ($result.StdOut -match 'already running, not restarted')
            } finally { Remove-Item -Recurse -Force $envDir -ErrorAction SilentlyContinue }
        } finally { Stop-FakeJsonListener $pending }
    }
}

Write-Host ''
if ($failures.Count -gt 0) {
    Write-Host "FAILED: $($failures.Count) check(s) failed." -ForegroundColor Red
    exit 1
}
Write-Host 'All external-relay checks passed.' -ForegroundColor Green
exit 0
