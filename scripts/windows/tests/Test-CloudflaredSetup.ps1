<#
.SYNOPSIS
  Phase 6I-1 checks for the Cloudflare Named Tunnel setup helpers (RunningAI.CloudflaredSetup.ps1)
  and the setup-running-ai-external-access.ps1 orchestrator's -DryRun safety. No real Cloudflare
  API call that creates/changes anything, no real cloudflared login, no real Windows Service
  change: every check either exercises a pure parsing/string function directly, or runs the real
  orchestrator script with -DryRun (which itself makes zero changes - that is exactly what this
  suite verifies).

  Exit code 0 when all checks pass.
#>
$ErrorActionPreference = 'Stop'
$here = $PSScriptRoot
$scripts = Split-Path $here -Parent
$repo = (Resolve-Path (Join-Path $scripts '..\..')).Path
$external = Join-Path $scripts 'external'

. (Join-Path $scripts 'RunningAI.Common.ps1')
. (Join-Path $external 'RunningAI.CloudflaredSetup.ps1')

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

# ---- ConvertTo-RunningAiHostname ----------------------------------------------------------

Check 'ConvertTo-RunningAiHostname accepts a bare hostname' {
    (ConvertTo-RunningAiHostname 'runningai.example.com') -eq 'runningai.example.com'
}

Check 'ConvertTo-RunningAiHostname accepts an https:// URL and strips the scheme/path' {
    (ConvertTo-RunningAiHostname 'https://runningai.example.com/ignored') -eq 'runningai.example.com'
}

Check 'ConvertTo-RunningAiHostname rejects an http:// URL (https required)' {
    $null -eq (ConvertTo-RunningAiHostname 'http://runningai.example.com')
}

Check 'ConvertTo-RunningAiHostname rejects empty/whitespace input' {
    ($null -eq (ConvertTo-RunningAiHostname '')) -and ($null -eq (ConvertTo-RunningAiHostname '   '))
}

Check 'ConvertTo-RunningAiHostname rejects garbage that is not a hostname' {
    $null -eq (ConvertTo-RunningAiHostname 'not a hostname!!')
}

# ---- .env round trip -----------------------------------------------------------------------

Check 'Get-RunningAiExternalHostnameFromEnvFile returns null for a missing file' {
    $null -eq (Get-RunningAiExternalHostnameFromEnvFile -EnvFilePath (Join-Path $env:TEMP "selftest-missing-$([guid]::NewGuid().ToString('N')).env"))
}

Check 'Set-/Get-RunningAiExternalHostnameInEnvFile round trips on a fresh file' {
    $f = Join-Path $env:TEMP "selftest-env-$([guid]::NewGuid().ToString('N')).env"
    try {
        Set-RunningAiExternalHostnameInEnvFile -EnvFilePath $f -Hostname 'runningai.example.com'
        (Get-RunningAiExternalHostnameFromEnvFile -EnvFilePath $f) -eq 'runningai.example.com'
    } finally { Remove-Item $f -Force -ErrorAction SilentlyContinue }
}

Check 'Set-RunningAiExternalHostnameInEnvFile preserves other lines and upserts in place' {
    $f = Join-Path $env:TEMP "selftest-env-$([guid]::NewGuid().ToString('N')).env"
    try {
        Set-Content -LiteralPath $f -Value @('# comment', 'OTHER_KEY=keep-me', 'RUNNING_AI_EXTERNAL_BASE_URL=https://old.example.com', 'ANOTHER=also-keep') -Encoding utf8
        Set-RunningAiExternalHostnameInEnvFile -EnvFilePath $f -Hostname 'new.example.com'
        $lines = Get-Content -LiteralPath $f
        ($lines.Count -eq 4) -and
        ($lines -contains 'OTHER_KEY=keep-me') -and
        ($lines -contains 'ANOTHER=also-keep') -and
        ($lines -contains 'RUNNING_AI_EXTERNAL_BASE_URL=https://new.example.com') -and
        (-not ($lines -match 'old\.example\.com'))
    } finally { Remove-Item $f -Force -ErrorAction SilentlyContinue }
}

Check 'Set-RunningAiExternalHostnameInEnvFile appends when the key is absent from an existing file' {
    $f = Join-Path $env:TEMP "selftest-env-$([guid]::NewGuid().ToString('N')).env"
    try {
        Set-Content -LiteralPath $f -Value @('SOME_OTHER_KEY=x') -Encoding utf8
        Set-RunningAiExternalHostnameInEnvFile -EnvFilePath $f -Hostname 'runningai.example.com'
        (Get-RunningAiExternalHostnameFromEnvFile -EnvFilePath $f) -eq 'runningai.example.com'
    } finally { Remove-Item $f -Force -ErrorAction SilentlyContinue }
}

# ---- cloudflared CLI output parsing ----------------------------------------------------------

Check 'Find-RunningAiNamedTunnelId parses a matching row out of "tunnel list" output' {
    $out = @"
ID                                   NAME                        CREATED              CONNECTIONS
a1b2c3d4-e5f6-7890-abcd-ef1234567890 running-ai-external-relay  2026-10-07T00:00:00Z 2xLHR
11111111-2222-3333-4444-555555555555 some-other-tunnel          2026-01-01T00:00:00Z 0
"@
    (Find-RunningAiNamedTunnelId -ListOutput $out) -eq 'a1b2c3d4-e5f6-7890-abcd-ef1234567890'
}

Check 'Find-RunningAiNamedTunnelId returns null when the name is not present' {
    $out = "11111111-2222-3333-4444-555555555555 some-other-tunnel  2026-01-01T00:00:00Z 0"
    $null -eq (Find-RunningAiNamedTunnelId -ListOutput $out)
}

Check 'Find-RunningAiNamedTunnelId never matches a different tunnel whose name merely starts the same' {
    $out = "a1b2c3d4-e5f6-7890-abcd-ef1234567890 running-ai-external-relay-staging  2026-01-01T00:00:00Z 0"
    $null -eq (Find-RunningAiNamedTunnelId -ListOutput $out)
}

Check 'Find-RunningAiCreatedTunnelId parses "tunnel create" output' {
    $out = "Tunnel credentials written to D:\example-profile\.cloudflared\a1b2c3d4-e5f6-7890-abcd-ef1234567890.json`nCreated tunnel running-ai-external-relay with id a1b2c3d4-e5f6-7890-abcd-ef1234567890`n"
    (Find-RunningAiCreatedTunnelId -CreateOutput $out) -eq 'a1b2c3d4-e5f6-7890-abcd-ef1234567890'
}

Check 'Find-RunningAiCreatedTunnelId returns null for unrelated output' {
    $null -eq (Find-RunningAiCreatedTunnelId -CreateOutput 'something failed')
}

Check 'Test-RunningAiCloudflaredAlreadyDoneMessage recognizes an "already exists" style message' {
    (Test-RunningAiCloudflaredAlreadyDoneMessage 'Failed to add route: code: 1003, reason: already exists') -and
    (Test-RunningAiCloudflaredAlreadyDoneMessage 'Hostname runningai.example.com already has a DNS record')
}

Check 'Test-RunningAiCloudflaredAlreadyDoneMessage does not misclassify an unrelated error as already-done' {
    -not (Test-RunningAiCloudflaredAlreadyDoneMessage 'failed to connect: dial tcp: connection refused')
}

# ---- config.yml generation and overwrite protection -------------------------------------------

Check 'Get-RunningAiCloudflaredConfigYaml points only at the external-relay origin, never Spring/Garmin ports' {
    $yaml = Get-RunningAiCloudflaredConfigYaml -TunnelId 'tid' -Hostname 'runningai.example.com' -CredentialsFile 'C:\creds\tid.json'
    ($yaml -match [regex]::Escape('service: http://127.0.0.1:17845')) -and
    ($yaml -notmatch '8080') -and
    ($yaml -notmatch '8765')
}

Check 'Get-RunningAiCloudflaredConfigYaml ends with a catch-all http_status:404 ingress rule' {
    $yaml = Get-RunningAiCloudflaredConfigYaml -TunnelId 'tid' -Hostname 'runningai.example.com' -CredentialsFile 'C:\creds\tid.json'
    $lastRule = ($yaml -split "`n" | Where-Object { $_.Trim() -ne '' } | Select-Object -Last 1).Trim()
    $lastRule -eq '- service: http_status:404'
}

Check 'Get-RunningAiCloudflaredConfigYaml carries the managed-by marker' {
    $yaml = Get-RunningAiCloudflaredConfigYaml -TunnelId 'tid' -Hostname 'runningai.example.com' -CredentialsFile 'C:\creds\tid.json'
    $yaml.Contains($script:RunningAiCloudflaredConfigMarker)
}

Check 'Test-RunningAiCloudflaredConfigIsOurs is true when nothing exists yet' {
    Test-RunningAiCloudflaredConfigIsOurs -Path (Join-Path $env:TEMP "selftest-cfg-$([guid]::NewGuid().ToString('N')).yml")
}

Check 'Test-RunningAiCloudflaredConfigIsOurs is true for a config this script wrote (carries the marker)' {
    $f = Join-Path $env:TEMP "selftest-cfg-$([guid]::NewGuid().ToString('N')).yml"
    try {
        Set-Content -LiteralPath $f -Value (Get-RunningAiCloudflaredConfigYaml -TunnelId 'tid' -Hostname 'h' -CredentialsFile 'c') -Encoding utf8
        Test-RunningAiCloudflaredConfigIsOurs -Path $f
    } finally { Remove-Item $f -Force -ErrorAction SilentlyContinue }
}

Check 'Test-RunningAiCloudflaredConfigIsOurs is false for a pre-existing, unrelated config (never overwritten)' {
    $f = Join-Path $env:TEMP "selftest-cfg-$([guid]::NewGuid().ToString('N')).yml"
    try {
        Set-Content -LiteralPath $f -Value "tunnel: someone-elses-tunnel`ningress:`n  - service: http://127.0.0.1:9999" -Encoding utf8
        -not (Test-RunningAiCloudflaredConfigIsOurs -Path $f)
    } finally { Remove-Item $f -Force -ErrorAction SilentlyContinue }
}

Check 'Get-RunningAiTunnelCredentialsFilePath builds <dir>\<id>.json' {
    (Get-RunningAiTunnelCredentialsFilePath -TunnelId 'abc-123' -CloudflaredDir 'C:\u\.cloudflared') -eq 'C:\u\.cloudflared\abc-123.json'
}

# ---- orchestrator -DryRun safety (real script, real machine, zero changes) --------------------

Check 'setup-running-ai-external-access.ps1 parses without errors' {
    $errors = $null
    [void][System.Management.Automation.Language.Parser]::ParseFile((Join-Path $external 'setup-running-ai-external-access.ps1'), [ref]$null, [ref]$errors)
    $errors.Count -eq 0
}

Check 'setup-running-ai-external-access.ps1 -DryRun never contains a hard-coded legacy repo path' {
    $hits = Get-ChildItem $external -Filter *.ps1 | Select-String -Pattern 'C:\\running-ai|C:\\Users\\'
    @($hits).Count -eq 0
}

Check '-DryRun makes zero filesystem/service changes and reports zero in its summary' {
    $cloudflaredPresent = [bool](Get-Command cloudflared -ErrorAction SilentlyContinue) -or (Test-Path 'C:\Program Files (x86)\cloudflared\cloudflared.exe') -or (Test-Path 'C:\Program Files\cloudflared\cloudflared.exe')
    if (-not $cloudflaredPresent) {
        Write-Host '  (skipped: cloudflared is not installed on this machine)'
        return $true
    }
    $envFile = Join-Path $env:TEMP "selftest-dryrun-$([guid]::NewGuid().ToString('N')).env"
    $cfgPath = Join-Path $env:USERPROFILE '.cloudflared\config.yml'
    $cfgExistedBefore = Test-Path -LiteralPath $cfgPath
    $cfgHashBefore = if ($cfgExistedBefore) { (Get-FileHash -LiteralPath $cfgPath -Algorithm SHA256).Hash } else { $null }
    try {
        $out = & (Join-Path $external 'setup-running-ai-external-access.ps1') -DryRun -Hostname 'dryrun-selftest.invalid' -EnvFilePath $envFile *>&1 | Out-String
        $exit = $LASTEXITCODE
        $envUnchanged = -not (Test-Path -LiteralPath $envFile)
        $cfgUnchanged =
            if (-not $cfgExistedBefore) { -not (Test-Path -LiteralPath $cfgPath) }
            else { (Get-FileHash -LiteralPath $cfgPath -Algorithm SHA256).Hash -eq $cfgHashBefore }
        ($exit -eq 0) -and $envUnchanged -and $cfgUnchanged -and
            ($out -match 'files changed: 0') -and ($out -match 'services changed: 0') -and ($out -match 'Cloudflare writes: 0')
    } finally { Remove-Item $envFile -Force -ErrorAction SilentlyContinue }
}

Write-Host ''
if ($failures.Count -gt 0) {
    Write-Host "FAILED: $($failures.Count) check(s) failed." -ForegroundColor Red
    exit 1
}
Write-Host 'All Cloudflare setup checks passed.' -ForegroundColor Green
exit 0
