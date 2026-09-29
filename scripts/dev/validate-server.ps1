<#
.SYNOPSIS
  RunningAI server validation: runs the Gradle test suite when server/ has uncommitted changes.

.DESCRIPTION
  Used by the Claude Code "Stop" hook (see .claude/settings.json) and usable by hand.

  Behaviour
    - Reads the hook's stdin JSON if present. When "stop_hook_active" is true the script
      exits 0 immediately so a failing suite cannot trap Claude in an endless stop loop.
    - Exits 0 without running anything when server/ has no uncommitted changes
      (nothing to validate) or when the current change set already passed (fingerprint
      cached in .claude/.validate-cache, git-ignored). Use -Force to run anyway.
    - Requires Java 21. Candidates, in order: $env:JAVA_HOME, then the java.home of the
      java on PATH. No user-specific paths are hard-coded; if no JDK 21 is found the
      script fails with an explicit message.
    - Runs  server\gradlew.bat test  (add -Clean for  clean test).
    - Exit 0  = tests passed (or nothing to validate).
      Exit 2  = tests failed / could not run. For a Claude Code Stop hook, exit 2 blocks
                the stop and feeds stderr back to Claude so the failure is acted on.
    - Never commits, pushes, migrates a database or touches credentials.

.EXAMPLE
  powershell -NoProfile -ExecutionPolicy Bypass -File scripts/dev/validate-server.ps1 -Force -Clean
#>
[CmdletBinding()]
param(
    [switch]$Force,
    [switch]$Clean
)

# 'Continue' on purpose: under 'Stop', PowerShell 5.1 turns any stderr line of a native
# command (java -version, gradle) into a terminating error. Failures are checked explicitly.
$ErrorActionPreference = 'Continue'
$repoRoot  = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$serverDir = Join-Path $repoRoot 'server'
# Both files are git-ignored. The log must not live under server/build (gradle clean deletes it).
$cacheFile = Join-Path $repoRoot '.claude\.validate-cache'
$logFile   = Join-Path $repoRoot '.claude\.validate-server.log'

function Fail([string]$message) {
    [Console]::Error.WriteLine("[validate-server] FAIL: $message")
    exit 2
}
function Info([string]$message) {
    Write-Output "[validate-server] $message"
}

# --- 1. Hook input: avoid re-running when Claude is already continuing because of this hook.
$hookInput = ''
try {
    if ([Console]::IsInputRedirected) { $hookInput = [Console]::In.ReadToEnd() }
} catch { $hookInput = '' }
if ($hookInput -match '"stop_hook_active"\s*:\s*true') {
    Info 'stop_hook_active=true -> skipping (already continued once for this stop).'
    exit 0
}

# --- 2. Only validate when server/ actually changed.
Push-Location $repoRoot
try {
    $status    = @(git status --porcelain -- server)
    if (-not $Force -and $status.Count -eq 0) {
        Info 'no uncommitted changes under server/ -> nothing to validate.'
        exit 0
    }

    $diff      = @(git diff HEAD -- server)
    $untracked = @(git ls-files --others --exclude-standard -- server)
    $blobs     = foreach ($f in $untracked) { git hash-object -- $f }
    $material  = ($status + $diff + $untracked + $blobs) -join "`n"
    $sha       = [System.Security.Cryptography.SHA256]::Create()
    $bytes     = [System.Text.Encoding]::UTF8.GetBytes($material)
    $fingerprint = ([System.BitConverter]::ToString($sha.ComputeHash($bytes))).Replace('-', '')

    if (-not $Force -and (Test-Path $cacheFile) -and ((Get-Content $cacheFile -Raw).Trim() -eq $fingerprint)) {
        Info 'current server/ change set already passed validation -> skipping (use -Force to rerun).'
        exit 0
    }
} finally {
    Pop-Location
}

# --- 3. Locate a JDK 21 (JAVA_HOME first, then the java on PATH). No hard-coded user paths.
# Native java output goes to stderr; capture it through cmd so PowerShell does not wrap it.
function Get-NativeOutput([string]$exe, [string]$arguments) {
    return (cmd /c "`"$exe`" $arguments 2>&1" | Out-String)
}
function Get-JavaMajor([string]$javaExe) {
    if (-not (Test-Path $javaExe)) { return $null }
    $out = Get-NativeOutput $javaExe '-version'
    if ($out -match 'version "(\d+)(?:\.(\d+))?') {
        if ($Matches[1] -eq '1') { return [int]$Matches[2] }   # "1.8.0_x" -> 8
        return [int]$Matches[1]
    }
    return $null
}
$candidates = @()
if ($env:JAVA_HOME) { $candidates += $env:JAVA_HOME }
$pathJava = Get-Command java -ErrorAction SilentlyContinue
if ($pathJava) {
    $props = Get-NativeOutput $pathJava.Source '-XshowSettings:properties -version'
    if ($props -match 'java\.home\s*=\s*(.+)') { $candidates += $Matches[1].Trim() }
}
$jdk21 = $null
$seen  = @()
foreach ($candidate in $candidates) {
    if ($seen -contains $candidate) { continue }
    $seen += $candidate
    if ((Get-JavaMajor (Join-Path $candidate 'bin\java.exe')) -eq 21) { $jdk21 = $candidate; break }
}
if (-not $jdk21) {
    $tried = if ($seen.Count) { $seen -join '; ' } else { '(none)' }
    Fail "Java 21 is required but no JDK 21 was found. Tried: $tried. Set JAVA_HOME to a JDK 21 (e.g. via .claude/settings.local.json 'env') and rerun."
}
$env:JAVA_HOME = $jdk21
Info "using JDK 21 at $jdk21"

# --- 4. Run the Gradle test suite.
$gradlew = Join-Path $serverDir 'gradlew.bat'
if (-not (Test-Path $gradlew)) { Fail "Gradle wrapper not found: $gradlew" }
# [string[]] keeps a one-element list an array (PowerShell unrolls @('test') to a string otherwise).
[string[]]$tasks = if ($Clean) { @('clean', 'test') } else { @('test') }
Info "running: gradlew.bat $($tasks -join ' ')  (cwd: server)"

New-Item -ItemType Directory -Force (Split-Path $logFile) | Out-Null
# Launch through cmd with separate tokens (Start-Process mangles .bat argument spacing on
# PowerShell 5.1) and a relative, space-free log path so the redirection needs no quoting.
$relativeLog = '..\.claude\.validate-server.log'
Push-Location $serverDir
try {
    [string[]]$gradleArgs = $tasks + '--console=plain'
    cmd /c .\gradlew.bat @gradleArgs '>' $relativeLog '2>&1'
    $exit = $LASTEXITCODE
} finally {
    Pop-Location
}

if ($exit -ne 0) {
    $lines = @(Get-Content $logFile)
    $picked = New-Object System.Collections.Generic.List[string]
    for ($i = 0; $i -lt $lines.Count -and $picked.Count -lt 30; $i++) {
        $line = $lines[$i]
        if ($line -match 'FAILED|tests completed|error:|What went wrong') {
            $picked.Add($line)
            if ($line -match 'What went wrong') {
                # the actual reason follows on the next line(s)
                foreach ($j in 1..2) { if ($i + $j -lt $lines.Count -and $lines[$i + $j].Trim()) { $picked.Add($lines[$i + $j]) } }
            }
        }
    }
    [Console]::Error.WriteLine("[validate-server] gradlew test exited with $exit. Failing tests / errors:")
    foreach ($l in $picked) { [Console]::Error.WriteLine("  $l") }
    [Console]::Error.WriteLine("  full log: $logFile")
    exit 2
}

$summary = (Get-Content $logFile | Where-Object { $_ -match 'BUILD SUCCESSFUL' } | Select-Object -Last 1)
New-Item -ItemType Directory -Force (Split-Path $cacheFile) | Out-Null
Set-Content -Path $cacheFile -Value $fingerprint -Encoding ascii
Info "tests passed ($summary). Fingerprint cached; the hook will stay quiet until server/ changes again."
exit 0
