<#
.SYNOPSIS
  Starts the RunningAI runtime in dependency order and waits for each layer:
  Docker -> PostgreSQL (healthy) -> Garmin connector (/health) -> Spring Boot (/actuator/health).

.DESCRIPTION
  Idempotent: components that are already up are left alone, never started twice.
  Never performs a Garmin login and never reads token contents; the connector is started with
  its existing token store (run "python -m garmin_connector login" once, by hand, on this PC).
  The Garmin scheduler is not forced on: it follows RUNNING_AI_GARMIN_SCHEDULER_ENABLED.

  Exit codes: 0 ok | 10 Docker | 11 PostgreSQL | 12 connector | 13 Java/build | 14 Spring | 1 other.

.PARAMETER Build
  Rebuild the Spring jar (gradlew bootJar) even when one exists. Without it the jar is built
  only if none exists.
#>
[CmdletBinding()]
param(
    [switch]$Build,
    [int]$ConnectorPort = 8765,
    [int]$SpringPort = $(if ($env:SERVER_PORT) { [int]$env:SERVER_PORT } else { 8080 }),
    [int]$DockerTimeoutSec = 120,
    [int]$PostgresTimeoutSec = 120,
    [int]$ConnectorTimeoutSec = 30,
    [int]$SpringTimeoutSec = 120
)

. "$PSScriptRoot\RunningAI.Common.ps1"
$root = Get-RepoRoot
$started = Get-Date
$startedConnector = $false

function Test-DockerReady { (Invoke-NativeQuiet 'docker' 'info') -eq 0 }

function Start-DockerDesktop {
    # Prefer the official CLI ("docker desktop start"); fall back to the installed executable.
    if ((Invoke-NativeQuiet 'docker' 'desktop version') -eq 0) {
        Write-Step 'Starting Docker Desktop (docker desktop start).'
        Invoke-NativeQuiet 'docker' 'desktop start' | Out-Null
        return
    }
    $exe = Join-Path $env:ProgramFiles 'Docker\Docker\Docker Desktop.exe'
    if (Test-Path $exe) {
        Write-Step 'Starting Docker Desktop (executable).'
        Start-Process -FilePath $exe | Out-Null
        return
    }
    Stop-WithError $ExitCode.Docker 'Docker daemon is not running and Docker Desktop could not be located. Start Docker Desktop manually and retry.'
}

function Find-Java21 {
    $candidates = @()
    if ($env:JAVA_HOME) { $candidates += (Join-Path $env:JAVA_HOME 'bin\java.exe') }
    $onPath = Get-Command java -ErrorAction SilentlyContinue
    if ($onPath) { $candidates += $onPath.Source }
    foreach ($exe in ($candidates | Select-Object -Unique)) {
        if (-not (Test-Path $exe)) { continue }
        $text = Invoke-NativeText $exe '-XshowSettings:properties -version'
        if ($text -match 'java\.version\s*=\s*(\d+)' -and [int]$Matches[1] -eq 21 -and $text -match 'java\.home\s*=\s*(.+)') {
            $javaHome = $Matches[1].Trim()
            return [pscustomobject]@{ Exe = (Join-Path $javaHome 'bin\java.exe'); Home = $javaHome }
        }
    }
    return $null
}

function Find-SpringJar {
    $libs = Join-Path $root 'server\build\libs'
    if (-not (Test-Path $libs)) { return $null }
    Get-ChildItem $libs -Filter 'running-ai-server-*.jar' |
        Where-Object { $_.Name -notlike '*-plain.jar' } |
        Sort-Object LastWriteTime -Descending | Select-Object -First 1
}

function Stop-NewConnectorOnFailure {
    if ($startedConnector) {
        $tracked = Get-TrackedProcessId 'garmin-connector' (Get-ConnectorMarkers)
        if ($tracked) {
            Write-Step 'Spring did not start: stopping the connector started by this run (database left running).'
            Stop-TrackedProcess -ProcessId $tracked -Markers (Get-ConnectorMarkers) -TimeoutSec 15 | Out-Null
            Remove-PidFile 'garmin-connector'
        }
    }
}

try {
    New-Item -ItemType Directory -Force $script:LogDir | Out-Null
    Remove-ExpiredLogs -LogDir $script:LogDir | Out-Null

    # ---- 1. Docker -------------------------------------------------------------------
    if (-not (Get-Command docker -ErrorAction SilentlyContinue)) {
        Stop-WithError $ExitCode.Docker 'Docker CLI was not found on PATH. Install Docker Desktop.'
    }
    if (Test-DockerReady) {
        Write-Step 'Docker daemon: RUNNING'
    } else {
        Start-DockerDesktop
        if (-not (Wait-Until -TimeoutSec $DockerTimeoutSec -PollSec 4 -Test { Test-DockerReady })) {
            Stop-WithError $ExitCode.Docker "Docker daemon did not become ready within $DockerTimeoutSec seconds."
        }
        Write-Step 'Docker daemon: RUNNING (started)'
    }

    # ---- 2. PostgreSQL ---------------------------------------------------------------
    $compose = Join-Path $root 'docker-compose.yml'
    if ((Invoke-NativeQuiet 'docker' "compose -f $(Quote-Argument $compose) up -d") -ne 0) {
        $detail = Invoke-NativeText 'docker' "compose -f $(Quote-Argument $compose) up -d"
        Stop-WithError $ExitCode.Postgres "docker compose up -d failed: $($detail.Trim())"
    }
    $pgHealthy = {
        $cid = (Invoke-NativeText 'docker' "compose -f $(Quote-Argument $compose) ps -q postgres").Trim()
        if (-not $cid) { return $false }
        (Invoke-NativeText 'docker' "inspect -f {{.State.Health.Status}} $cid").Trim() -eq 'healthy'
    }
    if (-not (Wait-Until -TimeoutSec $PostgresTimeoutSec -PollSec 3 -Test $pgHealthy)) {
        Stop-WithError $ExitCode.Postgres "PostgreSQL container did not become healthy within $PostgresTimeoutSec seconds."
    }
    Write-Step 'PostgreSQL: HEALTHY'

    # ---- 3. Garmin connector -----------------------------------------------------------
    if (Test-ConnectorHealth $ConnectorPort) {
        Write-Step "Garmin connector: UP on 127.0.0.1:$ConnectorPort (already running, not restarted)"
    } else {
        if (Test-PortInUse $ConnectorPort) {
            Stop-WithError $ExitCode.Connector "Port $ConnectorPort is in use but is not a healthy Garmin connector. Free the port or pass -ConnectorPort."
        }
        $py = Join-Path $script:ConnectorDir '.venv\Scripts\python.exe'
        if (-not (Test-Path $py)) {
            Stop-WithError $ExitCode.Connector "Garmin connector virtual environment was not found at tools\garmin-connector\.venv. See tools\garmin-connector\README.md (py -3.12 -m venv .venv; pip install -r requirements.txt)."
        }
        if ((Invoke-NativeQuiet $py '-c "import garminconnect, fastapi, uvicorn"') -ne 0) {
            Stop-WithError $ExitCode.Connector 'Garmin connector dependencies are missing in its virtual environment. Run pip install -r requirements.txt there.'
        }
        $tokenFile = Join-Path $HOME '.garminconnect\garmin_tokens.json'
        if (-not (Test-Path $tokenFile)) {
            Write-Warning 'No Garmin token store found (~/.garminconnect). The connector will start but Garmin calls will return GARMIN_AUTH_REQUIRED until you run "python -m garmin_connector login" manually.'
        }

        $stale = Get-TrackedProcessId 'garmin-connector' (Get-ConnectorMarkers)   # cleans stale PID files
        # Start-Process truncates the redirected log files, so keep the previous run's logs first.
        foreach ($n in 'garmin-connector.out', 'garmin-connector.err') { Invoke-LogRotation -LogDir $script:LogDir -Name $n -Always | Out-Null }
        Enable-CtrlCInheritance
        $proc = Start-Process -FilePath $py `
            -ArgumentList "-m garmin_connector serve --port $ConnectorPort" `
            -WorkingDirectory $script:ConnectorDir -WindowStyle Hidden -PassThru `
            -RedirectStandardOutput (Join-Path $script:LogDir 'garmin-connector.out.log') `
            -RedirectStandardError (Join-Path $script:LogDir 'garmin-connector.err.log')
        Write-PidFile 'garmin-connector' $proc.Id
        $startedConnector = $true
        $ready = Wait-Until -TimeoutSec $ConnectorTimeoutSec -PollSec 1 -Test { Test-ConnectorHealth $ConnectorPort } `
            -Abort { if ($proc.HasExited) { "exited with code $($proc.ExitCode)" } }
        if (-not $ready) {
            $why = if ($proc.HasExited) { "The process exited with code $($proc.ExitCode)." } else { "No healthy response within $ConnectorTimeoutSec seconds." }
            Stop-NewConnectorOnFailure
            Stop-WithError $ExitCode.Connector "Garmin connector health endpoint did not become ready. $why See .runtime\logs\garmin-connector.err.log"
        }
        Write-Step "Garmin connector: UP on 127.0.0.1:$ConnectorPort (PID $($proc.Id))"
    }

    # ---- 4. Java + jar -------------------------------------------------------------------
    $java = Find-Java21
    if (-not $java) { Stop-WithError $ExitCode.Java 'Java 21 is not available (checked JAVA_HOME and PATH). Install JDK 21 and/or set JAVA_HOME.' }

    if (Test-SpringHealth $SpringPort) {
        Write-Step "Spring Boot: UP on port $SpringPort (already running, not restarted)"
    } else {
        if (Test-PortInUse $SpringPort) {
            Stop-NewConnectorOnFailure
            Stop-WithError $ExitCode.Spring "Port $SpringPort is in use but /actuator/health is not UP. Free the port or set SERVER_PORT."
        }
        $jar = Find-SpringJar
        if ($Build -or -not $jar) {
            Write-Step 'Building Spring Boot jar (gradlew bootJar).'
            $env:JAVA_HOME = $java.Home
            Push-Location $script:ServerDir
            try {
                cmd /c ".\gradlew.bat bootJar --console=plain > `"$(Join-Path $script:LogDir 'gradle-bootjar.log')`" 2>&1"
                $buildExit = $LASTEXITCODE
            } finally { Pop-Location }
            $jar = Find-SpringJar
            if ($buildExit -ne 0 -or -not $jar) {
                Stop-NewConnectorOnFailure
                Stop-WithError $ExitCode.Java 'Spring Boot jar build failed. See .runtime\logs\gradle-bootjar.log'
            }
        }

        # ---- 5. Spring Boot -------------------------------------------------------------
        if (-not $env:GARMIN_CONNECTOR_URL) { $env:GARMIN_CONNECTOR_URL = "http://127.0.0.1:$ConnectorPort" }
        if ($SpringPort -ne 8080) { $env:SERVER_PORT = "$SpringPort" }
        if (-not $env:DB_PASSWORD -and -not (Test-Path (Join-Path $root '.env'))) {
            Write-Warning 'Neither DB_PASSWORD nor a repository-root .env is set; the local profile will try an empty database password.'
        }
        $stale = Get-TrackedProcessId 'spring' (Get-SpringMarkers)
        foreach ($n in 'spring.out', 'spring.err') { Invoke-LogRotation -LogDir $script:LogDir -Name $n -Always | Out-Null }
        Enable-CtrlCInheritance
        $spring = Start-Process -FilePath $java.Exe `
            -ArgumentList "-jar $(Quote-Argument $jar.FullName)" `
            -WorkingDirectory $script:ServerDir -WindowStyle Hidden -PassThru `
            -RedirectStandardOutput (Join-Path $script:LogDir 'spring.out.log') `
            -RedirectStandardError (Join-Path $script:LogDir 'spring.err.log')
        Write-PidFile 'spring' $spring.Id
        $up = Wait-Until -TimeoutSec $SpringTimeoutSec -PollSec 2 -Test { Test-SpringHealth $SpringPort } `
            -Abort { if ($spring.HasExited) { "exited with code $($spring.ExitCode)" } }
        if (-not $up) {
            $why = if ($spring.HasExited) { "The process exited with code $($spring.ExitCode)." } else { "No UP response within $SpringTimeoutSec seconds." }
            if (-not $spring.HasExited) { Stop-TrackedProcess -ProcessId $spring.Id -Markers (Get-SpringMarkers) -TimeoutSec 20 | Out-Null }
            Remove-PidFile 'spring'
            Stop-NewConnectorOnFailure
            Stop-WithError $ExitCode.Spring "Spring Boot health endpoint did not become ready. $why See .runtime\logs\spring.err.log and spring.out.log"
        }
        Write-Step "Spring Boot: UP on port $SpringPort (PID $($spring.Id))"
    }

    $seconds = [int]((Get-Date) - $started).TotalSeconds
    Write-Step "RunningAI runtime is up (${seconds}s). Check details with scripts\windows\status-running-ai.ps1"
    exit $ExitCode.Ok
} catch {
    $code = Get-ExitCodeFromError $_
    Write-Host "ERROR: $($_.Exception.Message)" -ForegroundColor Red
    exit $code
}
