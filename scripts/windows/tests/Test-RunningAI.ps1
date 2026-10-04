<#
.SYNOPSIS
  Non-destructive checks of the Windows runtime scripts. Needs no Docker, Garmin, Python or
  network, and registers/changes nothing:
    - every script parses
    - Common helpers (repo root, PID files, process identity, waiting, port checks)
    - the Scheduled Task installer in -DryRun mode
    - no user/drive specific paths or secrets in the scripts
    - runtime artifacts are git-ignored

  Exit code 0 when all checks pass.
#>
$ErrorActionPreference = 'Stop'
$here = $PSScriptRoot
$scripts = Split-Path $here -Parent
$repo = (Resolve-Path (Join-Path $scripts '..\..')).Path

. (Join-Path $scripts 'RunningAI.Common.ps1')

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

Check 'all scripts parse without errors' {
    foreach ($f in Get-ChildItem $scripts -Filter *.ps1 -Recurse) {
        $errors = $null
        [void][System.Management.Automation.Language.Parser]::ParseFile($f.FullName, [ref]$null, [ref]$errors)
        if ($errors.Count) { throw "$($f.Name): $($errors[0].Message)" }
    }
}

Check 'repo root is derived from the script location' {
    (Get-RepoRoot) -eq $repo -and (Test-Path (Join-Path (Get-RepoRoot) 'docker-compose.yml'))
}

Check 'pid file round trip and cleanup' {
    $name = 'selftest-' + [guid]::NewGuid().ToString('N')
    try {
        (Read-PidFile $name) -eq $null -and $(Write-PidFile $name 4242; (Read-PidFile $name) -eq 4242)
    } finally { Remove-PidFile $name }
}

Check 'garbage pid file is treated as absent' {
    $name = 'selftest-' + [guid]::NewGuid().ToString('N')
    try {
        New-Item -ItemType Directory -Force (Split-Path (Get-PidFilePath $name)) | Out-Null
        Set-Content (Get-PidFilePath $name) 'not-a-pid'
        $null -eq (Read-PidFile $name)
    } finally { Remove-PidFile $name }
}

Check 'process identity requires every marker (own PID matches only real markers)' {
    (Test-ProcessIdentity -ProcessId $PID -Markers @('powershell')) -and
    -not (Test-ProcessIdentity -ProcessId $PID -Markers @('powershell', 'no-such-marker-xyz')) -and
    -not (Test-ProcessIdentity -ProcessId 2147483000 -Markers @('powershell'))
}

Check 'a foreign process is never treated as the connector or Spring' {
    -not (Test-ProcessIdentity -ProcessId $PID -Markers (Get-ConnectorMarkers)) -and
    -not (Test-ProcessIdentity -ProcessId $PID -Markers (Get-SpringMarkers))
}

Check 'stale pid file is removed by Get-TrackedProcessId' {
    $name = 'selftest-' + [guid]::NewGuid().ToString('N')
    Write-PidFile $name $PID          # alive, but not a connector: must be rejected and cleaned
    $tracked = Get-TrackedProcessId $name (Get-ConnectorMarkers)
    ($null -eq $tracked) -and ($null -eq (Read-PidFile $name))
}

Check 'stop of a non-tracked process does nothing' {
    (Stop-TrackedProcess -ProcessId $PID -Markers (Get-ConnectorMarkers) -TimeoutSec 1) -eq 'not-running' -and
    [bool](Get-Process -Id $PID)
}

Check 'Wait-Until returns false on timeout and honours abort' {
    $t0 = Get-Date
    $timedOut = -not (Wait-Until -TimeoutSec 1 -PollSec 1 -Test { $false })
    $aborted = -not (Wait-Until -TimeoutSec 30 -PollSec 1 -Test { $false } -Abort { 'exited' })
    $ok = Wait-Until -TimeoutSec 5 -PollSec 1 -Test { $true }
    $timedOut -and $aborted -and $ok -and (((Get-Date) - $t0).TotalSeconds -lt 10)
}

Check 'closed port yields no HTTP status and health is false' {
    $port = 1   # nothing listens here
    ($null -eq (Get-HttpStatus "http://127.0.0.1:$port/health" 1)) -and -not (Test-ConnectorHealth $port) -and -not (Test-SpringHealth $port)
}

Check 'health checks read a vendor JSON content type (actuator) and plain JSON (connector)' {
    $port = Get-Random -Minimum 20000 -Maximum 40000
    $listener = New-Object System.Net.HttpListener
    $listener.Prefixes.Add("http://127.0.0.1:$port/")
    $listener.Start()
    try {
        $job = [powershell]::Create().AddScript({
            param($l)
            for ($i = 0; $i -lt 2; $i++) {
                $ctx = $l.GetContext()
                if ($ctx.Request.Url.AbsolutePath -eq '/actuator/health') {
                    $ctx.Response.ContentType = 'application/vnd.spring-boot.actuator.v3+json'
                    $bytes = [Text.Encoding]::UTF8.GetBytes('{"status":"UP"}')
                } else {
                    $ctx.Response.ContentType = 'application/json'
                    $bytes = [Text.Encoding]::UTF8.GetBytes('{"status":"UP","service":"garmin-connector"}')
                }
                $ctx.Response.OutputStream.Write($bytes, 0, $bytes.Length)
                $ctx.Response.Close()
            }
        }).AddArgument($listener)
        $handle = $job.BeginInvoke()
        (Test-SpringHealth $port) -and (Test-ConnectorHealth $port)
    } finally { $listener.Stop(); $listener.Close() }
}

# ---- UTF-8 JSON request body (Phase 6H-7.2) ---------------------------------------------------
# Local HttpListener only: no external network, no RunningAI server required.
#
# This file is plain (no-BOM) UTF-8, like every other script here, and Windows PowerShell 5.1 reads
# a no-BOM .ps1 using the system codepage rather than UTF-8 - a literal Korean string typed directly
# into this source would silently become mojibake when the script runs (the exact failure this phase
# investigates). So every non-ASCII fixture below is built from explicit Unicode code points via
# Join-RunningAiTestChars instead of being typed as a literal, keeping this file itself pure ASCII.

# Builds a string from an array of Unicode code points (as [int], written in hex for readability).
function Join-RunningAiTestChars {
    param([Parameter(Mandatory)][int[]]$CodePoints)
    -join ($CodePoints | ForEach-Object { [char]$_ })
}

function Receive-RunningAiTestRequestOnce {
    param([Parameter(Mandatory)][int]$Port)
    $listener = New-Object System.Net.HttpListener
    $listener.Prefixes.Add("http://127.0.0.1:$Port/")
    $listener.Start()
    try {
        $job = [powershell]::Create().AddScript({
            param($l)
            $ctx = $l.GetContext()
            $reader = New-Object System.IO.StreamReader($ctx.Request.InputStream, [System.Text.Encoding]::UTF8)
            $bodyText = $reader.ReadToEnd()
            $result = [pscustomobject]@{
                BodyText    = $bodyText
                ContentType = $ctx.Request.ContentType
            }
            $bytes = [Text.Encoding]::UTF8.GetBytes('{"status":"received"}')
            $ctx.Response.ContentType = 'application/json'
            $ctx.Response.OutputStream.Write($bytes, 0, $bytes.Length)
            $ctx.Response.Close()
            return $result
        }).AddArgument($listener)
        $handle = $job.BeginInvoke()
        return @{ Job = $job; Handle = $handle; Listener = $listener }
    } catch {
        $listener.Stop(); $listener.Close()
        throw
    }
}

function Complete-RunningAiTestRequest {
    param([Parameter(Mandatory)]$Pending, [int]$TimeoutSec = 10)
    try {
        if (-not $Pending.Handle.AsyncWaitHandle.WaitOne($TimeoutSec * 1000)) {
            throw "local HttpListener did not receive a request within ${TimeoutSec}s"
        }
        return $Pending.Job.EndInvoke($Pending.Handle)
    } finally {
        $Pending.Job.Dispose()
        $Pending.Listener.Stop()
        $Pending.Listener.Close()
    }
}

Check 'Invoke-RunningAiJsonRequest sends a Korean body as UTF-8 raw bytes (round trip)' {
    $port = Get-Random -Minimum 20000 -Maximum 40000
    $pending = Receive-RunningAiTestRequestOnce -Port $port
    # "running training test" / "want to run lightly" (Korean), built from code points - see note above
    $goal = Join-RunningAiTestChars 0xB7EC, 0xB2DD, 0x20, 0xD6C8, 0xB828, 0x20, 0xD14C, 0xC2A4, 0xD2B8
    $feedback = Join-RunningAiTestChars 0xAC00, 0xBCCD, 0xAC8C, 0x20, 0xB2EC, 0xB9AC, 0xACE0, 0x20, 0xC2F6, 0xC5B4, 0xC694
    $korean = @{ requestedGoal = $goal; userFeedback = $feedback }
    Invoke-RunningAiJsonRequest -Method POST -Uri "http://127.0.0.1:$port/" -Body $korean | Out-Null
    $received = Complete-RunningAiTestRequest -Pending $pending

    $parsed = $received.BodyText | ConvertFrom-Json
    ($parsed.requestedGoal -ceq $goal) -and ($parsed.userFeedback -ceq $feedback) -and
    ($received.ContentType -match 'charset=utf-8')
}

Check 'Invoke-RunningAiJsonRequest never mis-decodes the raw request bytes (UTF-8 byte-for-byte)' {
    $port = Get-Random -Minimum 20000 -Maximum 40000
    $pending = Receive-RunningAiTestRequestOnce -Port $port
    # "running training test" (Korean), built from code points - see note above
    $text = Join-RunningAiTestChars 0xB7EC, 0xB2DD, 0x20, 0xD6C8, 0xB828, 0x20, 0xD14C, 0xC2A4, 0xD2B8
    Invoke-RunningAiJsonRequest -Method POST -Uri "http://127.0.0.1:$port/" -Body @{ text = $text } | Out-Null
    $received = Complete-RunningAiTestRequest -Pending $pending

    # Decode the raw JSON body text ourselves and compare the extracted string value directly,
    # not just "JSON.parse succeeded" - a mis-decoded body can still parse as valid (garbled) JSON.
    ($received.BodyText | ConvertFrom-Json).text -ceq $text
}

Check 'Invoke-RunningAiJsonRequest ASCII-only body still round trips (regression)' {
    $port = Get-Random -Minimum 20000 -Maximum 40000
    $pending = Receive-RunningAiTestRequestOnce -Port $port
    Invoke-RunningAiJsonRequest -Method POST -Uri "http://127.0.0.1:$port/" -Body @{ date = '2026-10-02'; availableMinutes = 45 } | Out-Null
    $received = Complete-RunningAiTestRequest -Pending $pending

    $parsed = $received.BodyText | ConvertFrom-Json
    ($parsed.date -eq '2026-10-02') -and ($parsed.availableMinutes -eq 45)
}

Check 'Invoke-RunningAiJsonRequest round trips mixed Korean/ASCII/digits/symbols' {
    $port = Get-Random -Minimum 20000 -Maximum 40000
    $pending = Receive-RunningAiTestRequestOnce -Port $port
    # "Hangul ASCII 123 !@#$%" (Hangul = Korean), built from code points - see note above
    $mixed = Join-RunningAiTestChars 0xD55C, 0xAE00, 0x20, 0x41, 0x53, 0x43, 0x49, 0x49, 0x20, 0x31, 0x32, 0x33, 0x20, 0x21, 0x40, 0x23, 0x24, 0x25
    Invoke-RunningAiJsonRequest -Method POST -Uri "http://127.0.0.1:$port/" -Body @{ text = $mixed } | Out-Null
    $received = Complete-RunningAiTestRequest -Pending $pending

    ($received.BodyText | ConvertFrom-Json).text -ceq $mixed
}

Check 'ConvertTo-RunningAiUtf8JsonBytes produces no BOM and decodes back to the same string' {
    # "Hangul test" (Korean), built from code points - see note above
    $text = Join-RunningAiTestChars 0xD55C, 0xAE00, 0x20, 0xD14C, 0xC2A4, 0xD2B8
    $bytes = ConvertTo-RunningAiUtf8JsonBytes @{ text = $text }
    $hasBom = $bytes.Length -ge 3 -and $bytes[0] -eq 0xEF -and $bytes[1] -eq 0xBB -and $bytes[2] -eq 0xBF
    $decoded = [System.Text.Encoding]::UTF8.GetString($bytes) | ConvertFrom-Json
    (-not $hasBom) -and ($decoded.text -ceq $text)
}

Check 'Invoke-RunningAiJsonRequest with no Body sends no request body (GET)' {
    $port = Get-Random -Minimum 20000 -Maximum 40000
    $pending = Receive-RunningAiTestRequestOnce -Port $port
    Invoke-RunningAiJsonRequest -Method GET -Uri "http://127.0.0.1:$port/" | Out-Null
    $received = Complete-RunningAiTestRequest -Pending $pending
    [string]::IsNullOrEmpty($received.BodyText)
}

<#
Phase 6H-7.2 live finding (Main PC, real /api/v1/workout-drafts response): Spring's JSON response
Content-Type is "application/json" with NO charset parameter, and Windows PowerShell 5.1's
Invoke-RestMethod silently falls back to a non-UTF-8 encoding for such a response, turning a real
UTF-8 multi-byte character (observed: an em dash) into double-UTF-8 mojibake - a different, wrong
character. This reproduces that exact server shape locally (no RunningAI server needed) and proves
Invoke-RunningAiJsonRequest decodes it correctly by reading the raw response bytes as UTF-8 itself,
never trusting Invoke-RestMethod's own body parsing.
#>
Check 'Invoke-RunningAiJsonRequest decodes a charset-less application/json response as UTF-8 (regression)' {
    $port = Get-Random -Minimum 20000 -Maximum 40000
    # An em dash (U+2014) plus Hangul ("test"), built from code points - see note above.
    $text = Join-RunningAiTestChars 0x2014, 0x20, 0xD14C, 0xC2A4, 0xD2B8
    $listener = New-Object System.Net.HttpListener
    $listener.Prefixes.Add("http://127.0.0.1:$port/")
    $listener.Start()
    try {
        $job = [powershell]::Create().AddScript({
            param($l, $responseText)
            $ctx = $l.GetContext()
            $json = (@{ text = $responseText } | ConvertTo-Json -Compress)
            $bytes = [System.Text.UTF8Encoding]::new($false).GetBytes($json)
            # Exactly Spring's default: no charset parameter on the Content-Type.
            $ctx.Response.ContentType = 'application/json'
            $ctx.Response.OutputStream.Write($bytes, 0, $bytes.Length)
            $ctx.Response.Close()
        }).AddArgument($listener).AddArgument($text)
        $handle = $job.BeginInvoke()
        $result = Invoke-RunningAiJsonRequest -Method GET -Uri "http://127.0.0.1:$port/"
        if (-not $handle.AsyncWaitHandle.WaitOne(10000)) { throw 'local HttpListener did not finish within 10s' }
        $job.EndInvoke($handle) | Out-Null
        $job.Dispose()
        $result.text -ceq $text
    } finally { $listener.Stop(); $listener.Close() }
}

# ---- Java 21 discovery (Phase 6H-8) ------------------------------------------------------------
# Pure candidate-building logic only; no real java.exe is invoked and no real user/machine path is
# hardcoded (every source is faked via the function's own parameters).

Check 'Get-RunningAiJava21Candidates orders env JAVA_HOME, then PATH, then machine/user JAVA_HOME, then Program Files' {
    $fakeRoot = Join-Path $env:TEMP "selftest-java-$([guid]::NewGuid().ToString('N'))"
    try {
        New-Item -ItemType Directory -Force (Join-Path $fakeRoot 'ProgramFilesJava\jdk-21.0.9') | Out-Null
        New-Item -ItemType Directory -Force (Join-Path $fakeRoot 'ProgramFilesJava\jdk-21.0.1') | Out-Null
        New-Item -ItemType Directory -Force (Join-Path $fakeRoot 'ProgramFilesJava\jdk-17.0.1') | Out-Null

        $result = Get-RunningAiJava21Candidates `
            -EnvJavaHome (Join-Path $fakeRoot 'env-home') `
            -PathJavaExe (Join-Path $fakeRoot 'path-home\bin\java.exe') `
            -MachineJavaHome (Join-Path $fakeRoot 'machine-home') `
            -UserJavaHome (Join-Path $fakeRoot 'user-home') `
            -ProgramFilesJavaDir (Join-Path $fakeRoot 'ProgramFilesJava')

        ($result.Count -eq 6) -and
        ($result[0] -eq (Join-Path $fakeRoot 'env-home')) -and
        ($result[1] -eq (Join-Path $fakeRoot 'path-home')) -and
        ($result[2] -eq (Join-Path $fakeRoot 'machine-home')) -and
        ($result[3] -eq (Join-Path $fakeRoot 'user-home')) -and
        # Program Files jdk-21* candidates only (jdk-17 excluded), newest-looking name first.
        ($result[4] -eq (Join-Path $fakeRoot 'ProgramFilesJava\jdk-21.0.9')) -and
        ($result[5] -eq (Join-Path $fakeRoot 'ProgramFilesJava\jdk-21.0.1'))
    } finally {
        Remove-Item -LiteralPath $fakeRoot -Recurse -Force -ErrorAction SilentlyContinue
    }
}

Check 'Get-RunningAiJava21Candidates de-duplicates (case-insensitive) and skips missing sources' {
    $result = Get-RunningAiJava21Candidates `
        -EnvJavaHome 'C:\Fake\JDK' `
        -PathJavaExe $null `
        -MachineJavaHome 'c:\fake\jdk\' `
        -UserJavaHome $null `
        -ProgramFilesJavaDir $null

    ($result.Count -eq 1) -and ($result[0] -eq 'C:\Fake\JDK')
}

Check 'Find-RunningAiJava21 skips a candidate with no java.exe and returns null, never throws' {
    $fakeRoot = Join-Path $env:TEMP "selftest-java-$([guid]::NewGuid().ToString('N'))"
    try {
        New-Item -ItemType Directory -Force $fakeRoot | Out-Null   # exists, but no bin\java.exe inside
        $null -eq (Find-RunningAiJava21 -Candidates @($fakeRoot, (Join-Path $fakeRoot 'does-not-exist')))
    } finally {
        Remove-Item -LiteralPath $fakeRoot -Recurse -Force -ErrorAction SilentlyContinue
    }
}

Check 'Quote-Argument quotes only when needed' {
    (Quote-Argument 'C:\a b\c.jar') -eq '"C:\a b\c.jar"' -and (Quote-Argument 'C:\ab\c.jar') -eq 'C:\ab\c.jar'
}

Check 'scheduled task installer -DryRun registers nothing and uses this repository' {
    $before = @(Get-ScheduledTask -TaskName 'RunningAI-Startup' -ErrorAction SilentlyContinue).Count
    $out = & powershell -NoProfile -ExecutionPolicy Bypass -File (Join-Path $scripts 'install-running-ai-scheduled-task.ps1') -DryRun | Out-String
    $after = @(Get-ScheduledTask -TaskName 'RunningAI-Startup' -ErrorAction SilentlyContinue).Count
    ($before -eq $after) -and ($out -match 'DRY RUN') -and ($out -match 'AtLogOn') -and
    ($out -match [regex]::Escape((Join-Path $scripts 'start-running-ai.ps1'))) -and ($out -match [regex]::Escape($repo))
}

Check 'scripts contain no hard-coded user/drive paths or credentials' {
    $pattern = 'C:\\Users\\|C:\\running-ai|GARMIN_PASSWORD\s*=\s*\S|password\s*=\s*[''"][^''"]+[''"]|refresh_token'
    $hits = Get-ChildItem $scripts -Filter *.ps1 -Recurse |
        Where-Object { $_.Name -ne 'Test-RunningAI.ps1' } |
        Select-String -Pattern $pattern
    if ($hits) { throw ($hits | Select-Object -First 1 | ForEach-Object { "$($_.Path):$($_.LineNumber)" }) }
}

Check 'runtime artifacts are git-ignored' {
    Push-Location $repo
    try {
        $ignored = git check-ignore '.runtime/logs/spring.out.log' '.runtime/spring.pid' 'x/garmin-connector.pid'
        @($ignored).Count -eq 3
    } finally { Pop-Location }
}

Check 'Import-DotEnvIntoProcess returns empty result for a missing file' {
    $r = Import-DotEnvIntoProcess -Path (Join-Path $env:TEMP "selftest-missing-$([guid]::NewGuid().ToString('N')).env")
    $r.Applied.Count -eq 0 -and $r.SkippedExisting.Count -eq 0 -and $r.MalformedLines -eq 0
}

Check 'Import-DotEnvIntoProcess ignores blank lines and comments, splits on the first = only' {
    $k1 = "SELFTEST_A_$([guid]::NewGuid().ToString('N'))"
    $k2 = "SELFTEST_B_$([guid]::NewGuid().ToString('N'))"
    $path = Join-Path $env:TEMP "selftest-$([guid]::NewGuid().ToString('N')).env"
    try {
        Set-Content -LiteralPath $path -Value @(
            '# a comment line',
            '',
            "$k1=first=second",
            "   ",
            "$k2=plain"
        ) -Encoding UTF8
        $r = Import-DotEnvIntoProcess -Path $path
        ($r.Applied -contains $k1) -and ($r.Applied -contains $k2) -and
        ((Get-Item "Env:$k1").Value -eq 'first=second') -and ((Get-Item "Env:$k2").Value -eq 'plain') -and
        $r.MalformedLines -eq 0
    } finally {
        Remove-Item "Env:$k1", "Env:$k2" -ErrorAction SilentlyContinue
        Remove-Item -LiteralPath $path -ErrorAction SilentlyContinue
    }
}

Check 'Import-DotEnvIntoProcess preserves internal whitespace in a value (cron expression)' {
    $k = "SELFTEST_CRON_$([guid]::NewGuid().ToString('N'))"
    $path = Join-Path $env:TEMP "selftest-$([guid]::NewGuid().ToString('N')).env"
    try {
        Set-Content -LiteralPath $path -Value "$k=0 30 4 * * *" -Encoding UTF8
        Import-DotEnvIntoProcess -Path $path | Out-Null
        (Get-Item "Env:$k").Value -eq '0 30 4 * * *'
    } finally {
        Remove-Item "Env:$k" -ErrorAction SilentlyContinue
        Remove-Item -LiteralPath $path -ErrorAction SilentlyContinue
    }
}

Check 'Import-DotEnvIntoProcess trims whitespace around the key and the whole value' {
    $k = "SELFTEST_TRIM_$([guid]::NewGuid().ToString('N'))"
    $path = Join-Path $env:TEMP "selftest-$([guid]::NewGuid().ToString('N')).env"
    try {
        Set-Content -LiteralPath $path -Value "  $k  =  value with inner spaces  " -Encoding UTF8
        Import-DotEnvIntoProcess -Path $path | Out-Null
        (Get-Item "Env:$k").Value -eq 'value with inner spaces'
    } finally {
        Remove-Item "Env:$k" -ErrorAction SilentlyContinue
        Remove-Item -LiteralPath $path -ErrorAction SilentlyContinue
    }
}

Check 'Import-DotEnvIntoProcess skips a malformed line (no =) without throwing' {
    $k = "SELFTEST_OK_$([guid]::NewGuid().ToString('N'))"
    $path = Join-Path $env:TEMP "selftest-$([guid]::NewGuid().ToString('N')).env"
    try {
        Set-Content -LiteralPath $path -Value @('this line has no equals sign', "$k=fine", '=also-malformed-empty-key') -Encoding UTF8
        $r = Import-DotEnvIntoProcess -Path $path
        $r.MalformedLines -eq 2 -and ($r.Applied -contains $k) -and ((Get-Item "Env:$k").Value -eq 'fine')
    } finally {
        Remove-Item "Env:$k" -ErrorAction SilentlyContinue
        Remove-Item -LiteralPath $path -ErrorAction SilentlyContinue
    }
}

Check 'Import-DotEnvIntoProcess accepts a UTF-8 file with a leading BOM' {
    $k = "SELFTEST_BOM_$([guid]::NewGuid().ToString('N'))"
    $path = Join-Path $env:TEMP "selftest-$([guid]::NewGuid().ToString('N')).env"
    try {
        $utf8Bom = New-Object System.Text.UTF8Encoding($true)
        [System.IO.File]::WriteAllText($path, "$k=bommed`n", $utf8Bom)
        $bytes = [System.IO.File]::ReadAllBytes($path)
        $hasBom = $bytes.Length -ge 3 -and $bytes[0] -eq 0xEF -and $bytes[1] -eq 0xBB -and $bytes[2] -eq 0xBF
        $r = Import-DotEnvIntoProcess -Path $path
        $hasBom -and ($r.Applied -contains $k) -and ((Get-Item "Env:$k").Value -eq 'bommed')
    } finally {
        Remove-Item "Env:$k" -ErrorAction SilentlyContinue
        Remove-Item -LiteralPath $path -ErrorAction SilentlyContinue
    }
}

Check 'Import-DotEnvIntoProcess never overwrites a variable already set in the process (existing env wins)' {
    $k = "SELFTEST_PRECEDENCE_$([guid]::NewGuid().ToString('N'))"
    $path = Join-Path $env:TEMP "selftest-$([guid]::NewGuid().ToString('N')).env"
    try {
        Set-Item "Env:$k" 'from-shell'
        Set-Content -LiteralPath $path -Value "$k=from-dotenv" -Encoding UTF8
        $r = Import-DotEnvIntoProcess -Path $path
        ($r.SkippedExisting -contains $k) -and (-not ($r.Applied -contains $k)) -and ((Get-Item "Env:$k").Value -eq 'from-shell')
    } finally {
        Remove-Item "Env:$k" -ErrorAction SilentlyContinue
        Remove-Item -LiteralPath $path -ErrorAction SilentlyContinue
    }
}

Check 'Initialize-DotEnvForThisProcess logs key names but never a value' {
    $k = "SELFTEST_SECRET_$([guid]::NewGuid().ToString('N'))"
    $secretValue = "super-secret-$([guid]::NewGuid().ToString('N'))"
    $dir = Join-Path $env:TEMP "selftest-root-$([guid]::NewGuid().ToString('N'))"
    try {
        New-Item -ItemType Directory -Force $dir | Out-Null
        Set-Content -LiteralPath (Join-Path $dir '.env') -Value "$k=$secretValue" -Encoding UTF8
        # -join (not Out-String, which wraps at the host's console width and could split a long key
        # name across two lines) so the full key name is always checked as one unbroken substring.
        $output = (Initialize-DotEnvForThisProcess -Root $dir *>&1 | ForEach-Object { $_.ToString() }) -join "`n"
        ($output -match [regex]::Escape($k)) -and (-not ($output -match [regex]::Escape($secretValue)))
    } finally {
        Remove-Item "Env:$k" -ErrorAction SilentlyContinue
        Remove-Item -LiteralPath $dir -Recurse -Force -ErrorAction SilentlyContinue
    }
}

Check 'Initialize-DotEnvForThisProcess does nothing when there is no .env file' {
    $dir = Join-Path $env:TEMP "selftest-root-noenv-$([guid]::NewGuid().ToString('N'))"
    try {
        New-Item -ItemType Directory -Force $dir | Out-Null
        $before = @(Get-ChildItem Env:).Count
        Initialize-DotEnvForThisProcess -Root $dir
        (@(Get-ChildItem Env:).Count) -eq $before
    } finally {
        Remove-Item -LiteralPath $dir -Recurse -Force -ErrorAction SilentlyContinue
    }
}

Check 'start-running-ai.ps1 loads .env before any component step and never passes credentials as a java argument' {
    $text = Get-Content (Join-Path $scripts 'start-running-ai.ps1') -Raw
    # Anchor on the first real Docker-step action (not the Test-DockerReady function definition,
    # which textually precedes everything, including the .env call) to prove the .env load really
    # happens first inside the try block, not just earlier in the file by coincidence.
    $dotenvIdx = $text.IndexOf('Initialize-DotEnvForThisProcess')
    $dockerStepIdx = $text.IndexOf("Write-Step 'Docker daemon: RUNNING'")
    $javaArgsLine = ($text -split "`r?`n" | Where-Object { $_ -match '-ArgumentList "-jar' })
    ($dotenvIdx -ge 0) -and ($dockerStepIdx -ge 0) -and ($dotenvIdx -lt $dockerStepIdx) -and
    ($javaArgsLine -notmatch 'PASSWORD|API_KEY|TOKEN')
}

Check 'stop script never removes volumes' {
    # code only: drop the comment-based help block and # comments (which mention the forbidden command)
    $text = (Get-Content (Join-Path $scripts 'stop-running-ai.ps1') -Raw) -replace '(?s)<#.*?#>', '' -replace '(?m)^\s*#.*$', ''
    ($text -notmatch 'compose[^\r\n]*\bdown\b') -and ($text -notmatch '\s-v\b') -and ($text -match 'compose[^\r\n]*\bstop\b')
}

if ($failures.Count) {
    Write-Host ("{0} check(s) failed: {1}" -f $failures.Count, ($failures -join ', ')) -ForegroundColor Red
    exit 1
}
Write-Host 'All checks passed.'
exit 0
