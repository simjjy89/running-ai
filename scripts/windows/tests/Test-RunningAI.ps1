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
