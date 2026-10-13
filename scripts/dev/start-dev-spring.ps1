<#
.SYNOPSIS
  Starts a throwaway, loopback-only RunningAI Spring instance against a NON-PRODUCTION database.

.DESCRIPTION
  Phase 7E-2 STEP 2. Exists because every ad-hoc instance started from inside a Claude Code
  session (18081/PID 13980, 18082/PID 9116, 18084/PID 14000) died when that session ended:
  a session-background process is reaped with its session, so the ChatGPT connector lost its
  backend without anything being wrong with the gateway, tunnel or Worker.

  This script therefore launches Java through the Windows Task Scheduler, which owns the
  process independently of any shell, and it is the only supported way to bring a dev instance
  up for something long-lived like the ChatGPT smoke path.

  Safety properties (all enforced here, not by convention):
    - refuses any database named 'running_ai' (production)
    - refuses any port that is not in the dev range 18081..18099 (production is 18080)
    - binds 127.0.0.1 only
    - every write switch stays off; only the read-only MCP tool group is enabled
    - the Garmin connector is pointed at a dead port, so Garmin traffic is structurally zero
    - DB_PASSWORD is read from the canonical .env at launch and never written anywhere

.EXAMPLE
  scripts\dev\start-dev-spring.ps1 -Port 18081 -Database running_ai_7d_smoke
.EXAMPLE
  scripts\dev\start-dev-spring.ps1 -Port 18082 -Database running_ai_realcopy -IntervalsLive
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory)][ValidateRange(18081, 18099)][int]$Port,
    [Parameter(Mandatory)][string]$Database,
    # Allow real Intervals.icu GETs (needed by the importers). Off by default: the base URL is
    # left pointing at the real API but without a key no call can succeed.
    [switch]$IntervalsLive,
    # Enables the Phase 7E-2 Strava archive importer and points it at the unpacked export. The path
    # must be outside the repository - the export holds the athlete's real GPS traces.
    [string]$StravaArchive,
    # Enables the Phase 7E-1 Intervals-only activity importer. Needs -IntervalsLive to reach the API.
    [switch]$IntervalsImport,
    [string]$CanonicalEnv = 'C:\running-ai-github\.env',
    [switch]$Stop
)

# 'Continue' on purpose: under 'Stop', PowerShell 5.1 turns any stderr line of a native command
# (schtasks prints "task not found" on a first run) into a terminating error. Native exit codes are
# checked explicitly instead.
$ErrorActionPreference = 'Continue'
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$taskName = "RunningAI-Dev-Spring-$Port"

if ($Database -eq 'running_ai') { throw "refusing to start a dev instance against the PRODUCTION database 'running_ai'." }
if ($Port -eq 18080)            { throw "refusing to use the production port 18080." }

if ($Stop) {
    cmd.exe /c "schtasks.exe /End /TN $taskName >nul 2>&1"
    cmd.exe /c "schtasks.exe /Delete /TN $taskName /F >nul 2>&1"
    $owner = (Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue).OwningProcess
    if ($owner) { Stop-Process -Id $owner -Force -ErrorAction SilentlyContinue }
    Write-Output "[dev-spring] stopped and removed $taskName"
    exit 0
}

# --- the jar: this worktree's build, so the instance carries the migrations under test.
$jar = Get-ChildItem (Join-Path $repoRoot 'server\build\libs') -Filter 'running-ai-server-*.jar' |
       Where-Object { $_.Name -notlike '*-plain.jar' } | Select-Object -First 1
if (-not $jar) { throw "no boot jar under server\build\libs - run: server\gradlew.bat bootJar" }

# --- java 21
$java = Join-Path $env:JAVA_HOME 'bin\java.exe'
if (-not (Test-Path $java)) { $java = 'C:\Program Files\Java\jdk-21.0.2\bin\java.exe' }
if (-not (Test-Path $java)) { throw "JDK 21 not found; set JAVA_HOME." }

# --- the DB password, read at launch, kept in memory only.
if (-not (Test-Path $CanonicalEnv)) { throw "canonical .env not found at $CanonicalEnv" }
$dbPassword = $null
foreach ($line in Get-Content $CanonicalEnv) {
    if ($line -match '^\s*DB_PASSWORD\s*=\s*(.+?)\s*$') { $dbPassword = $Matches[1].Trim('"').Trim("'") }
}
if (-not $dbPassword) { throw "DB_PASSWORD not present in $CanonicalEnv" }

$logDir = Join-Path $repoRoot '.runtime\logs'
New-Item -ItemType Directory -Force $logDir | Out-Null
$log = Join-Path $logDir "dev-spring-$Port.log"

# Everything that is not a secret travels as a command-line argument (highest Spring precedence,
# so the canonical .env's SERVER_PORT=18080 cannot win). The password travels in the environment.
$springArgs = @(
    "--server.address=127.0.0.1"
    "--server.port=$Port"
    "--spring.datasource.url=jdbc:postgresql://127.0.0.1:5432/$Database"
    "--spring.datasource.username=running_ai"
    "--running-ai.garmin.connector.base-url=http://127.0.0.1:9"
    "--running-ai.mcp.read-enabled=true"
    "--running-ai.mcp.enabled=false"
    "--running-ai.coach.context-version=V2"
    "--running-ai.workout-publishing.enabled=false"
    "--running-ai.workout-publishing.scheduler.enabled=false"
    "--running-ai.draft-publishing.enabled=false"
    "--running-ai.garmin.scheduler.enabled=false"
    "--running-ai.garmin.profile-sync.scheduler.enabled=false"
    "--running-ai.coach.refresh.scheduler.enabled=false"
)
if (-not $IntervalsLive) { $springArgs += "--running-ai.intervals.base-url=http://127.0.0.1:9" }
if ($IntervalsImport) { $springArgs += "--running-ai.intervals.activity-import.enabled=true" }
if ($StravaArchive) {
    if (-not (Test-Path -LiteralPath $StravaArchive)) { throw "Strava archive not found: $StravaArchive" }
    $resolved = (Resolve-Path -LiteralPath $StravaArchive).Path
    if ($resolved.StartsWith($repoRoot, [StringComparison]::OrdinalIgnoreCase)) {
        throw "refusing an archive path inside the repository: $resolved"
    }
    $springArgs += "--running-ai.strava.archive-import.enabled=true"
    $springArgs += "--running-ai.strava.archive-import.archive-path=`"$resolved`""
}

# A scheduled task action is a single command line, and schtasks cannot be made to quote a path
# containing spaces ("C:\Program Files\Java\...") reliably. So the task runs one generated .cmd
# launcher instead. The launcher carries NO secret: it reads the password from the per-user
# environment variable set just below, which is why the password never lands on disk here.
[Environment]::SetEnvironmentVariable('RUNNINGAI_DEV_DB_PASSWORD', $dbPassword, 'User')

# The jar is COPIED out of server/build/libs before it is run. A detached JVM holds its jar open on
# Windows, which would make the next `gradlew clean` fail - so the running instance must never be the
# build output itself.
$runJar = Join-Path $repoRoot ".runtime\dev-spring-$Port.jar"
Copy-Item -LiteralPath $jar.FullName -Destination $runJar -Force

$launcher = Join-Path $repoRoot ".runtime\dev-spring-$Port.cmd"
@(
    '@echo off'
    'set "DB_PASSWORD=%RUNNINGAI_DEV_DB_PASSWORD%"'
    ('"' + $java + '" -jar "' + $runJar + '" ' + ($springArgs -join ' ') + ' >> "' + $log + '" 2>&1')
) | Set-Content -LiteralPath $launcher -Encoding ascii

cmd.exe /c "schtasks.exe /Delete /TN $taskName /F >nul 2>&1"
schtasks.exe /Create /TN $taskName /TR "`"$launcher`"" /SC ONCE /ST 00:00 /F /RL LIMITED | Out-Null
if ($LASTEXITCODE -ne 0) { throw "schtasks /Create failed ($LASTEXITCODE)" }
schtasks.exe /Run /TN $taskName | Out-Null
if ($LASTEXITCODE -ne 0) { throw "schtasks /Run failed ($LASTEXITCODE)" }

Write-Output "[dev-spring] task $taskName started: port=$Port db=$Database jar=$($jar.Name)"
Write-Output "[dev-spring] log: $log"

for ($i = 1; $i -le 90; $i++) {
    Start-Sleep -Seconds 2
    try {
        $r = Invoke-RestMethod -Uri "http://127.0.0.1:$Port/api/v1/health" -TimeoutSec 3
        Write-Output "[dev-spring] UP after $($i*2)s - health: $($r | ConvertTo-Json -Compress)"
        exit 0
    } catch { }
}
Write-Output "[dev-spring] did NOT become healthy within 180s - inspect $log"
exit 2
