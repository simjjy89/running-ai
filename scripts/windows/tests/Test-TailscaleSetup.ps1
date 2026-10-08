<#
.SYNOPSIS
  Phase 6I-1 checks for the Tailscale Funnel setup helpers (RunningAI.TailscaleSetup.ps1) and the
  setup-running-ai-tailscale-funnel.ps1 orchestrator's -DryRun safety. No real Tailscale login, no
  real Funnel enable, no real Windows Service change: every check either exercises a pure
  parsing/string function directly with a fixture, or runs the real orchestrator script with
  -DryRun (which itself makes zero changes - that is exactly what this suite verifies).

  Exit code 0 when all checks pass.
#>
$ErrorActionPreference = 'Stop'
$here = $PSScriptRoot
$scripts = Split-Path $here -Parent
$repo = (Resolve-Path (Join-Path $scripts '..\..')).Path
$external = Join-Path $scripts 'external'

. (Join-Path $scripts 'RunningAI.Common.ps1')
. (Join-Path $external 'RunningAI.ExternalRelay.Common.ps1')
. (Join-Path $external 'RunningAI.TailscaleSetup.ps1')

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

# ---- version parsing -------------------------------------------------------------------------

Check 'ConvertTo-RunningAiTailscaleVersion parses a plain version string' {
    (ConvertTo-RunningAiTailscaleVersion "1.104.1`ntailscale commit: abc123`n") -eq [Version]'1.104.1'
}

Check 'ConvertTo-RunningAiTailscaleVersion strips a commit-hash suffix' {
    (ConvertTo-RunningAiTailscaleVersion '1.38.3-t1234abcde') -eq [Version]'1.38.3'
}

Check 'ConvertTo-RunningAiTailscaleVersion returns null for unparseable output' {
    $null -eq (ConvertTo-RunningAiTailscaleVersion 'not a version')
}

Check 'Test-RunningAiTailscaleVersionSupported accepts the exact minimum version' {
    Test-RunningAiTailscaleVersionSupported '1.38.3'
}

Check 'Test-RunningAiTailscaleVersionSupported accepts a newer version' {
    Test-RunningAiTailscaleVersionSupported '1.104.1'
}

Check 'Test-RunningAiTailscaleVersionSupported rejects an older version' {
    -not (Test-RunningAiTailscaleVersionSupported '1.30.0')
}

Check 'Test-RunningAiTailscaleVersionSupported rejects unparseable output' {
    -not (Test-RunningAiTailscaleVersionSupported 'garbage')
}

# ---- status --json parsing -------------------------------------------------------------------

$runningJson = '{"BackendState":"Running","Self":{"DNSName":"my-pc.tailnet-name.ts.net."},"CurrentTailnet":{"MagicDNSEnabled":true}}'
$needsLoginJson = '{"BackendState":"NeedsLogin"}'

Check 'Test-RunningAiTailscaleLoggedIn is true for BackendState=Running' {
    Test-RunningAiTailscaleLoggedIn $runningJson
}

Check 'Test-RunningAiTailscaleLoggedIn is false for BackendState=NeedsLogin' {
    -not (Test-RunningAiTailscaleLoggedIn $needsLoginJson)
}

Check 'Test-RunningAiTailscaleLoggedIn is false for unparseable/empty input, never throws' {
    (-not (Test-RunningAiTailscaleLoggedIn '')) -and (-not (Test-RunningAiTailscaleLoggedIn 'not json'))
}

Check 'Test-RunningAiTailscaleMagicDnsEnabled reads true from CurrentTailnet.MagicDNSEnabled' {
    (Test-RunningAiTailscaleMagicDnsEnabled $runningJson) -eq $true
}

Check 'Test-RunningAiTailscaleMagicDnsEnabled reads false explicitly' {
    (Test-RunningAiTailscaleMagicDnsEnabled '{"CurrentTailnet":{"MagicDNSEnabled":false}}') -eq $false
}

Check 'Test-RunningAiTailscaleMagicDnsEnabled returns null (unknown) when the field is absent, never a false negative' {
    $null -eq (Test-RunningAiTailscaleMagicDnsEnabled '{"BackendState":"Running"}')
}

Check 'Find-RunningAiTailscaleSelfDnsName strips the trailing dot' {
    (Find-RunningAiTailscaleSelfDnsName $runningJson) -eq 'my-pc.tailnet-name.ts.net'
}

Check 'Find-RunningAiTailscaleSelfDnsName returns null when Self/DNSName is absent' {
    $null -eq (Find-RunningAiTailscaleSelfDnsName '{"BackendState":"Running"}')
}

# ---- funnel output parsing ---------------------------------------------------------------------

Check 'Find-RunningAiFunnelHostname parses the public URL Tailscale prints' {
    (Find-RunningAiFunnelHostname "Available on the internet:`nhttps://my-pc.tailnet-name.ts.net/`n") -eq 'my-pc.tailnet-name.ts.net'
}

Check 'Find-RunningAiFunnelHostname returns null when no ts.net URL is present' {
    $null -eq (Find-RunningAiFunnelHostname 'no url here')
}

Check 'Test-RunningAiFunnelAlreadyServingTarget true when the exact target string is present' {
    Test-RunningAiFunnelAlreadyServingTarget -StatusOutput 'https://my-pc.tailnet-name.ts.net (Funnel on)`n|-- / proxy http://127.0.0.1:17845' -Target 'http://127.0.0.1:17845'
}

Check 'Test-RunningAiFunnelAlreadyServingTarget false when a different target is being served' {
    -not (Test-RunningAiFunnelAlreadyServingTarget -StatusOutput 'proxy http://127.0.0.1:9999' -Target 'http://127.0.0.1:17845')
}

Check 'Test-RunningAiFunnelAlreadyServingTarget false for empty status (nothing configured yet)' {
    -not (Test-RunningAiFunnelAlreadyServingTarget -StatusOutput '' -Target 'http://127.0.0.1:17845')
}

# ---- orchestrator -DryRun safety (real script, real machine, zero changes) --------------------

Check 'setup-running-ai-tailscale-funnel.ps1 parses without errors' {
    $errorsOut = $null
    [void][System.Management.Automation.Language.Parser]::ParseFile((Join-Path $external 'setup-running-ai-tailscale-funnel.ps1'), [ref]$null, [ref]$errorsOut)
    $errorsOut.Count -eq 0
}

Check 'setup-running-ai-tailscale-funnel.ps1 never contains a hard-coded legacy repo path' {
    $hits = Get-ChildItem $external -Filter *.ps1 | Select-String -Pattern 'C:\\running-ai|C:\\Users\\'
    @($hits).Count -eq 0
}

Check 'setup-running-ai-tailscale-funnel.ps1 never references Spring (8080) or the Garmin connector (8765) as a Funnel target' {
    $content = Get-Content -LiteralPath (Join-Path $external 'setup-running-ai-tailscale-funnel.ps1') -Raw
    ($content -notmatch '127\.0\.0\.1:8080') -and ($content -notmatch '127\.0\.0\.1:8765')
}

Check '-DryRun makes zero filesystem/service changes and reports zero in its summary' {
    $envFile = Join-Path $env:TEMP "selftest-ts-dryrun-$([guid]::NewGuid().ToString('N')).env"
    $servicesBefore = @(Get-Service -ErrorAction SilentlyContinue | Where-Object { $_.Name -like '*Tailscale*' } | ForEach-Object { "$($_.Name):$($_.StartType)" }) -join ','
    try {
        $out = & (Join-Path $external 'setup-running-ai-tailscale-funnel.ps1') -DryRun -EnvFilePath $envFile *>&1 | Out-String
        $exit = $LASTEXITCODE
        $envUnchanged = -not (Test-Path -LiteralPath $envFile)
        $servicesAfter = @(Get-Service -ErrorAction SilentlyContinue | Where-Object { $_.Name -like '*Tailscale*' } | ForEach-Object { "$($_.Name):$($_.StartType)" }) -join ','
        ($exit -eq 0) -and $envUnchanged -and ($servicesBefore -eq $servicesAfter) -and
            ($out -match 'files changed: 0') -and ($out -match 'services changed: 0') -and ($out -match 'Tailscale writes: 0')
    } finally { Remove-Item $envFile -Force -ErrorAction SilentlyContinue }
}

Write-Host ''
if ($failures.Count -gt 0) {
    Write-Host "FAILED: $($failures.Count) check(s) failed." -ForegroundColor Red
    exit 1
}
Write-Host 'All Tailscale setup checks passed.' -ForegroundColor Green
exit 0
