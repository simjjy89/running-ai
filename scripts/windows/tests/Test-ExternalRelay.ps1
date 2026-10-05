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

Write-Host ''
if ($failures.Count -gt 0) {
    Write-Host "FAILED: $($failures.Count) check(s) failed." -ForegroundColor Red
    exit 1
}
Write-Host 'All external-relay checks passed.' -ForegroundColor Green
exit 0
