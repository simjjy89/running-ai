# Shared helpers for the RunningAI Windows runtime scripts. Dot-source, do not run.
#
#   . "$PSScriptRoot\RunningAI.Common.ps1"
#
# Nothing here hard-codes a user, drive or install path: the repository root is derived from
# this file's location. No function touches credentials, tokens or Garmin data.

Set-StrictMode -Version Latest

$script:RepoRoot     = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$script:RuntimeDir   = Join-Path $script:RepoRoot '.runtime'
$script:LogDir       = Join-Path $script:RuntimeDir 'logs'
$script:ConnectorDir = Join-Path $script:RepoRoot 'tools\garmin-connector'
$script:ServerDir    = Join-Path $script:RepoRoot 'server'

# Exit codes shared by start/stop/status so a Scheduled Task result identifies the failing layer.
$script:ExitCode = @{ Ok = 0; Docker = 10; Postgres = 11; Connector = 12; Java = 13; Spring = 14; Usage = 2; Other = 1; WatchdogError = 20; RecoveryFailed = 21 }

function Get-RepoRoot { $script:RepoRoot }

function Stop-WithError {
    param([Parameter(Mandatory)][int]$Code, [Parameter(Mandatory)][string]$Message)
    $ex = New-Object System.Exception $Message
    $ex.Data['ExitCode'] = $Code
    throw $ex
}

function Get-ExitCodeFromError {
    param($ErrorRecord)
    $ex = $ErrorRecord.Exception
    if ($ex -and $ex.Data -and $ex.Data.Contains('ExitCode')) { return [int]$ex.Data['ExitCode'] }
    return $script:ExitCode.Other
}

function Write-Step { param([string]$Message) Write-Host ("[{0}] {1}" -f (Get-Date -Format 'HH:mm:ss'), $Message) }

# Runs a native command, returns its exit code and discards output (avoids PowerShell 5.1
# turning stderr lines into errors).
function Invoke-NativeQuiet {
    param([Parameter(Mandatory)][string]$Exe, [string]$Arguments = '')
    cmd /c "`"$Exe`" $Arguments >nul 2>&1"
    return $LASTEXITCODE
}

# Runs a native command and returns stdout+stderr as one string.
function Invoke-NativeText {
    param([Parameter(Mandatory)][string]$Exe, [string]$Arguments = '')
    return (cmd /c "`"$Exe`" $Arguments 2>&1" | Out-String)
}

function Quote-Argument { param([string]$Value) if ($Value -match '[\s"]') { '"' + ($Value -replace '"', '\"') + '"' } else { $Value } }

# ---- Java 21 discovery (Phase 6H-8) -----------------------------------------------------------
# start-running-ai.ps1 previously only checked the current process's JAVA_HOME and PATH, so a stale
# PowerShell session (one whose JAVA_HOME points at an older JDK, or has none at all) reported
# "Java 21 is not available" even though a JDK 21 was installed and discoverable elsewhere on the
# machine. These two functions are split so the candidate list - pure, no process started - can be
# tested without a real JDK; only Find-RunningAiJava21 itself runs java.exe.

# Returns java.exe's path from PATH, or $null. Split out of Get-RunningAiJava21Candidates's own
# parameter default (Phase 6H-8.2): a parenthesized assignment like "($found = Get-Command ...)"
# itself emits $found's value onto the pipeline, IN ADDITION to the subsequent "if" statement's
# result - so the old inline "$(($found = Get-Command java ...); if ($found) {...} else {$null})"
# default produced TWO pipeline objects, which [string] coercion joined with a space into something
# like "java.exe C:\Program Files\Java\jdk-21.0.2\bin\java.exe" (CommandInfo's own ToString(), then
# its real .Source, concatenated) - live on Main PC, this corrupted candidate then failed
# Split-Path/Join-Path with "DriveNotFoundException: drive 'java.exe C' not found". A plain
# function body has no such pipeline-emission trap: only the explicit "return" value escapes it.
function Get-RunningAiPathJavaExe {
    $found = Get-Command java -ErrorAction SilentlyContinue
    if ($found) { return $found.Source }
    return $null
}

# Builds an ordered, de-duplicated list of candidate JDK home directories to check, cheapest/most
# specific first. Every source is an optional parameter so tests can fake each one independently
# without touching the real machine/user environment or current process state.
function Get-RunningAiJava21Candidates {
    param(
        [string]$EnvJavaHome = $env:JAVA_HOME,
        [string]$PathJavaExe = (Get-RunningAiPathJavaExe),
        [string]$MachineJavaHome = ([Environment]::GetEnvironmentVariable('JAVA_HOME', 'Machine')),
        [string]$UserJavaHome = ([Environment]::GetEnvironmentVariable('JAVA_HOME', 'User')),
        [string]$ProgramFilesJavaDir = $(if ($env:ProgramFiles) { Join-Path $env:ProgramFiles 'Java' } else { $null })
    )

    $candidates = New-Object System.Collections.Generic.List[string]
    if ($EnvJavaHome) { $candidates.Add($EnvJavaHome) }
    if ($PathJavaExe) {
        # .../bin/java.exe -> home is two levels up.
        $candidates.Add((Split-Path (Split-Path $PathJavaExe -Parent) -Parent))
    }
    if ($MachineJavaHome) { $candidates.Add($MachineJavaHome) }
    if ($UserJavaHome) { $candidates.Add($UserJavaHome) }
    if ($ProgramFilesJavaDir -and (Test-Path -LiteralPath $ProgramFilesJavaDir)) {
        Get-ChildItem -LiteralPath $ProgramFilesJavaDir -Directory -Filter 'jdk-21*' -ErrorAction SilentlyContinue |
            Sort-Object Name -Descending |
            ForEach-Object { $candidates.Add($_.FullName) }
    }

    $seen = New-Object System.Collections.Generic.HashSet[string]([StringComparer]::OrdinalIgnoreCase)
    $ordered = New-Object System.Collections.Generic.List[string]
    foreach ($c in $candidates) {
        if ([string]::IsNullOrWhiteSpace($c)) { continue }
        $normalized = $c.TrimEnd('\')
        if ($seen.Add($normalized)) { $ordered.Add($normalized) }
    }
    return ,$ordered.ToArray()
}

# Runs java.exe at each candidate home (via Invoke-NativeText, so PowerShell 5.1 never turns its
# normal -XshowSettings/-version stderr output into a terminating NativeCommandError) until one
# reports "java.version = 21". Returns $null if none do. A missing java.exe at a candidate is
# skipped, never an error - most candidates will not exist on any given machine.
function Find-RunningAiJava21 {
    param([string[]]$Candidates = (Get-RunningAiJava21Candidates))

    foreach ($candidateHome in $Candidates) {
        $exe = Join-Path $candidateHome 'bin\java.exe'
        if (-not (Test-Path -LiteralPath $exe)) { continue }
        $text = Invoke-NativeText $exe '-XshowSettings:properties -version'
        if ($text -match 'java\.version\s*=\s*21(\.|\s|$)') {
            return [pscustomobject]@{ Exe = $exe; Home = $candidateHome }
        }
    }
    return $null
}

# ---- .env loading -----------------------------------------------------------------------------
# Parses KEY=VALUE lines from a repo-root .env into the CURRENT process's environment, so every
# child process started afterward (Start-Process inherits the full environment block by default)
# sees it exactly as if the operator had set $env:KEY themselves. This exists because Spring's own
# "optional:file:../.env[.properties]" config import is NOT a reliable substitute: Spring Boot's
# underscore/SCREAMING_SNAKE_CASE relaxed-binding mapper (the thing that lets "SERVER_ADDRESS" bind
# to the built-in "server.address" property) is restricted to real SystemEnvironmentPropertySource
# entries and does not apply to a plain file-backed property source, so a built-in Spring property
# configured only via its env-style name in .env can silently fail to apply even though a literal
# "${SOME_KEY:default}" placeholder elsewhere in application.yml still resolves fine from the same
# file. Routing every .env entry through a real process environment variable here removes that
# distinction entirely, for every current and future key.
#
# Precedence: existing process env > .env > whatever default the consumer (Spring, Docker Compose,
# ...) falls back to on its own. A key already present in the process environment is left alone —
# .env only fills in what is not already set. Values are never logged, only key names and counts.
#
# Format: one KEY=VALUE per line, split on the FIRST '=' only (so a value may itself contain '=');
# a blank line or one whose first non-whitespace character is '#' is ignored; a line with no '='
# (or an empty key) is skipped as malformed and counted, never thrown. Leading/trailing whitespace
# is trimmed from the key and from the whole value, but whitespace INSIDE the value is preserved
# verbatim (a cron expression like "0 30 4 * * *" must survive with its internal spaces intact).
# UTF-8 is read and decoded explicitly (bytes, not Get-Content's encoding auto-detection), so a
# leading byte-order mark is stripped instead of leaking into the first key's name.
#
# Returns @{ Applied = [string[]]; SkippedExisting = [string[]]; MalformedLines = [int] }.
function Import-DotEnvIntoProcess {
    param([Parameter(Mandatory)][string]$Path)

    $result = [ordered]@{ Applied = @(); SkippedExisting = @(); MalformedLines = 0 }
    if (-not (Test-Path -LiteralPath $Path)) { return $result }

    $bytes = [System.IO.File]::ReadAllBytes($Path)
    if ($bytes.Length -ge 3 -and $bytes[0] -eq 0xEF -and $bytes[1] -eq 0xBB -and $bytes[2] -eq 0xBF) {
        $bytes = $bytes[3..($bytes.Length - 1)]
    }
    $text = [System.Text.Encoding]::UTF8.GetString($bytes)

    foreach ($rawLine in ($text -split "`n")) {
        $line = $rawLine.TrimEnd("`r")
        $trimmed = $line.Trim()
        if ($trimmed.Length -eq 0 -or $trimmed.StartsWith('#')) { continue }

        $idx = $line.IndexOf('=')
        if ($idx -lt 1) { $result.MalformedLines++; continue }   # no '=', or '=' is the first character (empty key)
        $key = $line.Substring(0, $idx).Trim()
        if ($key.Length -eq 0) { $result.MalformedLines++; continue }
        $value = $line.Substring($idx + 1).Trim()

        if (Test-Path "Env:$key") {
            $result.SkippedExisting += $key
            continue
        }
        Set-Item "Env:$key" $value
        $result.Applied += $key
    }
    return $result
}

# Calls Import-DotEnvIntoProcess against the repo-root .env and reports the outcome via Write-Step
# (key names and counts only, never values). Safe to call when the file does not exist.
function Initialize-DotEnvForThisProcess {
    param([string]$Root = (Get-RepoRoot))
    $path = Join-Path $Root '.env'
    if (-not (Test-Path -LiteralPath $path)) { return }
    $r = Import-DotEnvIntoProcess -Path $path
    if ($r.Applied.Count -gt 0) {
        Write-Step ".env: applied $($r.Applied.Count) variable(s) not already set in the process environment ($($r.Applied -join ', '))"
    }
    if ($r.SkippedExisting.Count -gt 0) {
        Write-Step ".env: $($r.SkippedExisting.Count) variable(s) left unchanged (already set in the process environment: $($r.SkippedExisting -join ', '))"
    }
    if ($r.MalformedLines -gt 0) {
        Write-Warning ".env contained $($r.MalformedLines) malformed line(s) (no '='); they were skipped."
    }
}

# ---- UTF-8-safe JSON HTTP requests (Phase 6H-7.2) --------------------------------------------
# Windows PowerShell 5.1's Invoke-RestMethod can mangle non-ASCII text on BOTH sides of a request:
#  - REQUEST: handing a .NET string to -Body directly sends bytes that depend on the console/output
#    encoding rather than always being UTF-8 (fixed below by always converting to UTF-8 bytes first).
#  - RESPONSE: Invoke-RestMethod's own JSON body parsing falls back to a non-UTF-8 encoding whenever
#    the server's Content-Type omits an explicit charset parameter - which is exactly what Spring's
#    default JSON response looks like ("application/json", no ";charset=..."). Live-observed
#    (Phase 6H-7.2, Main PC): an em dash (U+2014, UTF-8 bytes E2 80 94) round-tripped through
#    Invoke-RestMethod from a real /api/v1/workout-drafts response came back as a different, wrong
#    single character (double-UTF-8 mojibake: the response's real UTF-8 bytes were decoded as
#    Latin-1 first, and that wrong text is what the caller received) - while decoding the identical
#    raw response bytes as UTF-8 by hand gave back the correct em dash.
#    This is fixed below by never trusting Invoke-RestMethod's own body decoding: every call reads
#    the raw response bytes and decodes them as UTF-8 itself, the same technique Get-HttpBody below
#    already uses for actuator/connector health checks.

# Converts any JSON-serializable PowerShell value to UTF-8 bytes (no BOM), via ConvertTo-Json.
function ConvertTo-RunningAiUtf8JsonBytes {
    param(
        [Parameter(Mandatory)]
        $Value,

        [int]$Depth = 30
    )

    $json = $Value | ConvertTo-Json -Depth $Depth
    # -NoEnumerate: a plain "return $bytes" unrolls the byte[] into individual byte objects across
    # the function-return pipeline boundary, so the caller receives Object[] instead of Byte[] - and
    # Invoke-RestMethod then falls back to stringifying that Object[] (space-joined decimal values)
    # instead of sending it as a raw body. This one call is what keeps it as an actual byte[].
    Write-Output -NoEnumerate ([System.Text.UTF8Encoding]::new($false).GetBytes($json))
}

# Calls a RunningAI JSON API: sends a request body (when present) as explicit UTF-8 bytes with
# Content-Type: application/json; charset=utf-8 (never a raw .NET string via -Body), and decodes
# the response body as UTF-8 itself rather than trusting Invoke-RestMethod's own JSON parsing (see
# the note above this file's UTF-8 helpers for why). Returns $null for an empty response body.
function Invoke-RunningAiJsonRequest {
    param(
        [Parameter(Mandatory)][string]$Method,
        [Parameter(Mandatory)][string]$Uri,
        $Body = $null,
        [int]$TimeoutSec = 30
    )

    if ($null -eq $Body) {
        $response = Invoke-WebRequest -Method $Method -Uri $Uri -TimeoutSec $TimeoutSec -UseBasicParsing
    } else {
        $bytes = ConvertTo-RunningAiUtf8JsonBytes $Body
        $response = Invoke-WebRequest `
            -Method $Method `
            -Uri $Uri `
            -ContentType 'application/json; charset=utf-8' `
            -Body $bytes `
            -TimeoutSec $TimeoutSec `
            -UseBasicParsing
    }

    $text = [System.Text.Encoding]::UTF8.GetString($response.RawContentStream.ToArray())
    if ([string]::IsNullOrWhiteSpace($text)) { return $null }
    return $text | ConvertFrom-Json
}

# Extracts a safe, displayable {HttpStatus; Code; Message} from the exception an Invoke-WebRequest
# call raises on a non-2xx RunningAI response (ErrorResponse: code/message/timestamp/errors - never
# a credential, a raw prompt or a raw AI response, so this is always safe to print). Falls back to
# the raw exception message when the response isn't the expected JSON shape (for example the server
# is entirely unreachable, in which case $ErrorRecord.Exception.Response is $null).
function Get-RunningAiErrorDetails {
    param([Parameter(Mandatory)]$ErrorRecord)

    # Phase 6H-8.2: "$ErrorRecord.Exception.Response" assumed every exception has a Response member
    # (true only for a WebException). A generic .NET/PowerShell exception - DriveNotFoundException,
    # ArgumentException, FileNotFoundException, anything that is not a failed HTTP call - has no
    # such member at all, and under Set-StrictMode referencing a genuinely-absent property throws
    # PropertyNotFoundException itself: the error formatter caused a second failure while reporting
    # the first one (live on Main PC, following the Java-discovery bug above). Read it as an
    # optional property instead, exactly like Get-RunningAiOptionalProperty (CoachOperator.ps1) does
    # for an API response shape - this function cannot depend on that one without a load-order
    # requirement, so the same safe-read logic is duplicated here, deliberately, as a two-line
    # primitive rather than a cross-file dependency.
    $responseProperty = $ErrorRecord.Exception.PSObject.Properties['Response']
    $response = if ($responseProperty) { $responseProperty.Value } else { $null }
    if (-not $response) {
        return [pscustomobject]@{ HttpStatus = $null; Code = $null; Message = $ErrorRecord.Exception.Message }
    }
    try {
        $status = [int]$response.StatusCode
        $stream = $response.GetResponseStream()
        $reader = New-Object System.IO.StreamReader($stream, [System.Text.Encoding]::UTF8)
        $body = $reader.ReadToEnd() | ConvertFrom-Json
        return [pscustomobject]@{ HttpStatus = $status; Code = $body.code; Message = $body.message }
    } catch {
        return [pscustomobject]@{ HttpStatus = $status; Code = $null; Message = $ErrorRecord.Exception.Message }
    }
}

# ---- HTTP -----------------------------------------------------------------------------

# Returns the HTTP status code, or $null when nothing answered.
function Get-HttpStatus {
    param([Parameter(Mandatory)][string]$Url, [int]$TimeoutSec = 3)
    try {
        return [int](Invoke-WebRequest -Uri $Url -UseBasicParsing -TimeoutSec $TimeoutSec).StatusCode
    } catch {
        $response = $_.Exception.Response
        if ($response) { return [int]$response.StatusCode }
        return $null
    }
}

function Get-HttpBody {
    param([Parameter(Mandatory)][string]$Url, [int]$TimeoutSec = 3)
    # Decode the raw bytes ourselves: for non-text content types (Spring actuator's
    # application/vnd.spring-boot.actuator.v3+json) Windows PowerShell 5.1 returns .Content as byte[].
    try {
        $response = Invoke-WebRequest -Uri $Url -UseBasicParsing -TimeoutSec $TimeoutSec
        return [System.Text.Encoding]::UTF8.GetString($response.RawContentStream.ToArray())
    } catch { return $null }
}

# Polls $Test (scriptblock returning $true when ready) until the deadline. $Abort, when given,
# returns a non-empty string to stop early (for example "process exited").
function Wait-Until {
    param(
        [Parameter(Mandatory)][scriptblock]$Test,
        [Parameter(Mandatory)][int]$TimeoutSec,
        [int]$PollSec = 2,
        [scriptblock]$Abort
    )
    $deadline = (Get-Date).AddSeconds($TimeoutSec)
    while ($true) {
        if (& $Test) { return $true }
        if ($Abort) { $reason = & $Abort; if ($reason) { return $false } }
        if ((Get-Date) -ge $deadline) { return $false }
        Start-Sleep -Seconds $PollSec
    }
}

function Test-ConnectorHealth {
    param([int]$Port)
    $body = Get-HttpBody "http://127.0.0.1:$Port/health"
    if (-not $body) { return $false }
    try { return ((ConvertFrom-Json $body).status -eq 'UP') } catch { return $false }
}

function Test-SpringHealth {
    param([int]$Port)
    $body = Get-HttpBody "http://127.0.0.1:$Port/actuator/health"
    if (-not $body) { return $false }
    try { return ((ConvertFrom-Json $body).status -eq 'UP') } catch { return $false }
}

# True when something already listens on the TCP port (so we never start a second process on it).
function Test-PortInUse {
    param([int]$Port)
    return [bool](Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue)
}

# ---- PID files and process identity ------------------------------------------------------

function Get-PidFilePath { param([Parameter(Mandatory)][string]$Name) Join-Path $script:RuntimeDir "$Name.pid" }

function Read-PidFile {
    param([Parameter(Mandatory)][string]$Name)
    $path = Get-PidFilePath $Name
    if (-not (Test-Path $path)) { return $null }
    $text = (Get-Content $path -Raw -ErrorAction SilentlyContinue)
    $value = 0
    if ($text -and [int]::TryParse($text.Trim(), [ref]$value) -and $value -gt 0) { return $value }
    return $null
}

function Write-PidFile {
    param([Parameter(Mandatory)][string]$Name, [Parameter(Mandatory)][int]$ProcessId)
    New-Item -ItemType Directory -Force $script:RuntimeDir | Out-Null
    Set-Content -Path (Get-PidFilePath $Name) -Value $ProcessId -Encoding ascii
}

function Remove-PidFile { param([Parameter(Mandatory)][string]$Name) Remove-Item (Get-PidFilePath $Name) -Force -ErrorAction SilentlyContinue }

# A PID counts as "ours" only if the live process command line contains every marker
# (component marker AND this repository root). A recycled PID or a legacy/other process fails.
function Test-ProcessIdentity {
    param([Parameter(Mandatory)][int]$ProcessId, [Parameter(Mandatory)][string[]]$Markers)
    $p = Get-CimInstance Win32_Process -Filter "ProcessId=$ProcessId" -ErrorAction SilentlyContinue
    if (-not $p -or -not $p.CommandLine) { return $false }
    foreach ($m in $Markers) { if ($p.CommandLine.IndexOf($m, [StringComparison]::OrdinalIgnoreCase) -lt 0) { return $false } }
    return $true
}

function Get-ConnectorMarkers { @('garmin_connector', ' serve', $script:RepoRoot) }
function Get-SpringMarkers    { @('running-ai-server', '-jar', $script:RepoRoot) }

# Returns the tracked PID when it is alive AND still our process; cleans a stale/foreign PID file.
function Get-TrackedProcessId {
    param([Parameter(Mandatory)][string]$Name, [Parameter(Mandatory)][string[]]$Markers)
    $tracked = Read-PidFile $Name
    if ($null -eq $tracked) { return $null }
    if (Test-ProcessIdentity -ProcessId $tracked -Markers $Markers) { return $tracked }
    Write-Step "Removing stale $Name.pid (PID $tracked is not this component)."
    Remove-PidFile $Name
    return $null
}

# ---- graceful stop -----------------------------------------------------------------------

# Sends Ctrl+C to a console process from a short-lived helper process (so this script keeps its
# own console), waits up to $TimeoutSec, and only then falls back to a forced stop.
# Returns 'graceful', 'forced' or 'not-running'.
function Stop-TrackedProcess {
    param(
        [Parameter(Mandatory)][int]$ProcessId,
        [Parameter(Mandatory)][string[]]$Markers,
        [int]$TimeoutSec = 30
    )
    if (-not (Test-ProcessIdentity -ProcessId $ProcessId -Markers $Markers)) { return 'not-running' }

    $helper = Join-Path $PSScriptRoot 'Send-CtrlC.ps1'
    $ps = (Get-Command powershell.exe).Source
    $argLine = "-NoProfile -ExecutionPolicy Bypass -File $(Quote-Argument $helper) -ProcessId $ProcessId"
    Start-Process -FilePath $ps -ArgumentList $argLine -WindowStyle Hidden -Wait | Out-Null

    $stopped = Wait-Until -TimeoutSec $TimeoutSec -PollSec 1 -Test { -not (Get-Process -Id $ProcessId -ErrorAction SilentlyContinue) }
    if ($stopped) { return 'graceful' }

    Write-Step "PID $ProcessId did not exit within ${TimeoutSec}s; forcing stop."
    Stop-Process -Id $ProcessId -Force -ErrorAction SilentlyContinue
    return 'forced'
}

# A process inherits an "ignore Ctrl+C" flag when it was started by a parent that had Ctrl+C
# disabled (some launchers do). Children of this script would then never react to the graceful
# Ctrl+C in Stop-TrackedProcess, so normal Ctrl+C handling is re-enabled here before spawning them.
function Enable-CtrlCInheritance {
    if (-not ('RunningAiCtrlCFlag' -as [type])) {
        Add-Type -TypeDefinition @"
using System;
using System.Runtime.InteropServices;
public static class RunningAiCtrlCFlag {
    [DllImport("kernel32.dll")] static extern bool SetConsoleCtrlHandler(IntPtr handler, bool add);
    public static void Enable() { SetConsoleCtrlHandler(IntPtr.Zero, false); }
}
"@
    }
    [RunningAiCtrlCFlag]::Enable()
}

# ---- log rotation / retention ------------------------------------------------------------
# Scope is strictly the RunningAI log directory passed in; only files whose names match the
# rotated-name pattern below are ever deleted.

$script:ManagedLogNames = @('spring.out', 'spring.err', 'garmin-connector.out', 'garmin-connector.err', 'watchdog')
$script:RotatedLogPattern = '^(spring\.out|spring\.err|garmin-connector\.out|garmin-connector\.err|watchdog)\.\d{8}-\d{6}(-\d+)?\.log$'

# Renames <name>.log to <name>.<yyyyMMdd-HHmmss>.log when it is non-empty and either -Always is
# given (before a component (re)starts and re-creates the file) or it exceeds -MaxBytes.
# A file that is still held open by a running process cannot be renamed; that is reported as
# $false and simply retried on a later call.
function Invoke-LogRotation {
    param(
        [Parameter(Mandatory)][string]$LogDir,
        [Parameter(Mandatory)][string]$Name,
        [long]$MaxBytes = 10MB,
        [switch]$Always,
        [datetime]$Now = (Get-Date)
    )
    $path = Join-Path $LogDir "$Name.log"
    if (-not (Test-Path -LiteralPath $path)) { return $false }
    $length = (Get-Item -LiteralPath $path).Length
    if ($length -eq 0) { return $false }
    if (-not $Always -and $length -le $MaxBytes) { return $false }
    $dest = Join-Path $LogDir ("{0}.{1}.log" -f $Name, $Now.ToString('yyyyMMdd-HHmmss'))
    $n = 1
    while (Test-Path -LiteralPath $dest) { $dest = Join-Path $LogDir ("{0}.{1}-{2}.log" -f $Name, $Now.ToString('yyyyMMdd-HHmmss'), $n); $n++ }
    try { Move-Item -LiteralPath $path -Destination $dest -ErrorAction Stop; return $true } catch { return $false }
}

# Deletes rotated logs older than $MaxAgeDays. Returns the names removed.
function Remove-ExpiredLogs {
    param([Parameter(Mandatory)][string]$LogDir, [int]$MaxAgeDays = 14, [datetime]$Now = (Get-Date))
    if (-not (Test-Path -LiteralPath $LogDir)) { return @() }
    $cutoff = $Now.AddDays(-$MaxAgeDays)
    $removed = @()
    foreach ($f in (Get-ChildItem -LiteralPath $LogDir -File -ErrorAction SilentlyContinue)) {
        if ($f.Name -match $script:RotatedLogPattern -and $f.LastWriteTime -lt $cutoff) {
            try { Remove-Item -LiteralPath $f.FullName -Force -ErrorAction Stop; $removed += $f.Name } catch { }
        }
    }
    return $removed
}
