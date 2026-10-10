<#
.SYNOPSIS
  Starts the RunningAI runtime in dependency order and waits for each layer:
  Docker -> PostgreSQL (healthy) -> Garmin connector (/health) -> Spring Boot (/actuator/health).

.DESCRIPTION
  Idempotent: components that are already up are left alone, never started twice.
  Never performs a Garmin login and never reads token contents; the connector is started with
  its existing token store (run "python -m garmin_connector login" once, by hand, on this PC).
  The Garmin scheduler is not forced on: it follows RUNNING_AI_GARMIN_SCHEDULER_ENABLED.

  Phase 6I-1.1: the external relay (tools/external-relay, 127.0.0.1:17845) is ensured healthy
  last, after Spring. It has no dependency on Spring's business API and is architecturally
  independent - a relay failure is reported (and changes the exit code) but never rolls back an
  already-healthy Docker/PostgreSQL/connector/Spring, and a Spring failure never attempts to
  start the relay (deliberately not nested inside the Spring step).

  Exit codes: 0 ok | 10 Docker | 11 PostgreSQL | 12 connector | 13 Java/build | 14 Spring |
  15 external relay | 1 other.

.PARAMETER Build
  Rebuild the Spring jar (gradlew bootJar) even when one exists. Without it the jar is built
  only if none exists.
#>
[CmdletBinding()]
param(
    [switch]$Build,
    [int]$ConnectorPort = 8765,
    [int]$SpringPort = 8080,
    [int]$DockerTimeoutSec = 120,
    [int]$PostgresTimeoutSec = 120,
    [int]$ConnectorTimeoutSec = 30,
    [int]$SpringTimeoutSec = 120
)

. "$PSScriptRoot\RunningAI.Common.ps1"
. "$PSScriptRoot\RunningAI.ConnectorOwnership.ps1"
. "$PSScriptRoot\external\RunningAI.ExternalRelay.Common.ps1"
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

function Find-SpringJar {
    $libs = Join-Path $root 'server\build\libs'
    if (-not (Test-Path $libs)) { return $null }
    Get-ChildItem $libs -Filter 'running-ai-server-*.jar' |
        Where-Object { $_.Name -notlike '*-plain.jar' } |
        Sort-Object LastWriteTime -Descending | Select-Object -First 1
}

function Stop-NewConnectorOnFailure {
    param([int]$ConnectorPort = 8765)
    if ($startedConnector) {
        $tracked = Read-PidFile 'garmin-connector'
        if ($tracked) {
            Write-Step 'Spring did not start: stopping the connector started by this run (database left running).'
            $ownership = Get-RunningAiConnectorOwnership -Port $ConnectorPort -TrackedPid $tracked -Markers (Get-ConnectorMarkers)
            $result = Stop-RunningAiConnectorManaged -Ownership $ownership -Port $ConnectorPort -TimeoutSec 15
            # Only a fully confirmed stop (port free AND no managed PID left) clears the PID file -
            # a partial/failed stop preserves it (and its .owner.json) so the next run can see what
            # actually happened instead of silently believing a clean slate. Phase 6I-1.7B-1R.
            if (Test-RunningAiConnectorStopWasClean -StopResult $result) {
                Remove-PidFile 'garmin-connector'
            } else {
                Write-Step "Garmin connector stop did not fully succeed (result=$($result.Result), portFreed=$($result.PortFreed), remaining=$($result.RemainingPids -join ',')); PID file preserved."
            }
        }
    }
}

# Phase 6I-1.7B-2B (STEP F): serializes against a concurrent stop-running-ai.ps1 (or another
# start-running-ai.ps1) targeting this SAME runtime. See RunningAI.Common.ps1's
# Enter-RunningAiRuntimeLock for why this is acquired HERE, by this script itself, and never by
# RunningAI.Watchdog.ps1 (which only ever spawns this script as a separate process). A busy runtime
# changes NOTHING - no Docker/connector/Spring call is made at all - and reports it distinctly
# (Exit code 25) rather than silently proceeding to race the other run.
$runtimeLock = Enter-RunningAiRuntimeLock -TimeoutSec 5
if (-not $runtimeLock) {
    Write-Step 'Another start-running-ai.ps1 or stop-running-ai.ps1 is already in progress for this runtime; skipping (no service state changed).'
    exit $ExitCode.Busy
}

try {
    New-Item -ItemType Directory -Force $script:LogDir | Out-Null
    Remove-ExpiredLogs -LogDir $script:LogDir | Out-Null

    # ---- 0. repo-root .env -------------------------------------------------------------
    # Loaded into this process's own environment first (existing process env wins, .env only
    # fills gaps) so every child started below -- connector and Spring alike -- inherits it the
    # same way a manually-set $env:... variable would. See RunningAI.Common.ps1 for why Spring's
    # own ".env[.properties]" config import is not relied on for this. RUNNING_AI_TEST_ENV_ROOT is
    # a test-only escape hatch (mirrors RUNNING_AI_TEST_RUNTIME_DIR) so a test can supply a
    # disposable .env without touching the real repo-root one or redirecting $root itself (which
    # still must point at the real repo for docker-compose.yml/server paths); never set outside a
    # test.
    $envRoot = if ($env:RUNNING_AI_TEST_ENV_ROOT) { $env:RUNNING_AI_TEST_ENV_ROOT } else { $root }
    Initialize-DotEnvForThisProcess -Root $envRoot
    if (-not $PSBoundParameters.ContainsKey('SpringPort')) { $SpringPort = Resolve-RunningAiSpringPort }
    Confirm-RunningAiValidPort -Port $SpringPort -Name 'SpringPort'

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
        # Best-effort ownership-metadata refresh (Phase 6I-1.7B-1): this connector was not started by
        # this run, so we only have its tracked PID file to go on. When that PID is alive and ours,
        # capture the current launcher/listener pairing so a FUTURE run (after this launcher exits)
        # can recognize an orphaned listener instead of mislabeling it DEGRADED/FOREIGN_PROCESS. Never
        # fatal, never touches the .pid file itself, never runs when the tracked PID is missing/stale.
        $existingTracked = Get-TrackedProcessId 'garmin-connector' (Get-ConnectorMarkers)
        if ($existingTracked) {
            try { Set-RunningAiConnectorOwnerMetaFromLive -LauncherPid $existingTracked -Port $ConnectorPort -Markers (Get-ConnectorMarkers) | Out-Null } catch { }
        }
    } else {
        if (Test-PortInUse $ConnectorPort) {
            Stop-WithError $ExitCode.Connector "Port $ConnectorPort is in use but is not a healthy Garmin connector. Free the port or pass -ConnectorPort."
        }
        # Phase 6I-1.7B-2B (STEP A-3): nothing is listening, but a stale tracked launcher may still be
        # alive (its listener child died or never started) - starting a brand new one here without
        # checking would race a second launcher against the still-live one. Stop the stale survivor
        # first; a failure to fully stop it aborts the start rather than risk a duplicate.
        $staleTrackedPid = Read-PidFile 'garmin-connector'
        if ($staleTrackedPid) {
            $staleOwnership = Get-RunningAiConnectorOwnership -Port $ConnectorPort -TrackedPid $staleTrackedPid -Markers (Get-ConnectorMarkers)
            if ($staleOwnership.Verdict -eq 'LAUNCHER_ALIVE_NO_LISTENER') {
                Write-Step "Garmin connector: tracked launcher (PID $staleTrackedPid) is alive but nothing is listening; stopping it before starting fresh."
                $staleStopResult = Stop-RunningAiConnectorManaged -Ownership $staleOwnership -Port $ConnectorPort -TimeoutSec 15
                if (Test-RunningAiConnectorStopWasClean -StopResult $staleStopResult) {
                    Remove-PidFile 'garmin-connector'
                } else {
                    Stop-WithError $ExitCode.Connector "A stale Garmin connector launcher (PID $staleTrackedPid) could not be fully stopped (result=$($staleStopResult.Result)); refusing to start a second one. See logs and .runtime\garmin-connector.pid."
                }
            }
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
            Stop-NewConnectorOnFailure -ConnectorPort $ConnectorPort
            Stop-WithError $ExitCode.Connector "Garmin connector health endpoint did not become ready. $why See .runtime\logs\garmin-connector.err.log"
        }
        # Capture ownership ground truth at the one moment both the launcher and the real listener
        # (which may be a different PID - Phase 6I-1.7A) are guaranteed alive and freshly created.
        # Best-effort: a failure here never fails this already-successful start.
        try { Set-RunningAiConnectorOwnerMetaFromLive -LauncherPid $proc.Id -Port $ConnectorPort -Markers (Get-ConnectorMarkers) | Out-Null } catch { }
        Write-Step "Garmin connector: UP on 127.0.0.1:$ConnectorPort (PID $($proc.Id))"
    }

    # ---- 4. Java + jar -------------------------------------------------------------------
    # Phase 6H-8: checks JAVA_HOME/PATH for the current process, then falls back to the Machine/User
    # JAVA_HOME and Program Files\Java\jdk-21* - a stale shell whose own JAVA_HOME/PATH predate a JDK
    # 21 install no longer has to be re-launched to pick it up.
    $java = Find-RunningAiJava21
    if (-not $java) { Stop-WithError $ExitCode.Java 'Java 21 is not available (checked JAVA_HOME, PATH, machine/user JAVA_HOME and Program Files\Java). Install JDK 21 and/or set JAVA_HOME.' }

    if (Test-SpringHealth $SpringPort) {
        Write-Step "Spring Boot: UP on port $SpringPort (already running, not restarted)"
    } else {
        if (Test-PortInUse $SpringPort) {
            Stop-NewConnectorOnFailure -ConnectorPort $ConnectorPort
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
                Stop-NewConnectorOnFailure -ConnectorPort $ConnectorPort
                Stop-WithError $ExitCode.Java 'Spring Boot jar build failed. See .runtime\logs\gradle-bootjar.log'
            }
        }

        # ---- 5. Spring Boot -------------------------------------------------------------
        if (-not $env:GARMIN_CONNECTOR_URL) { $env:GARMIN_CONNECTOR_URL = "http://127.0.0.1:$ConnectorPort" }
        # Always propagate the final resolved $SpringPort to the Java process, unconditionally -
        # not only when it differs from the literal 8080. The previous "if ($SpringPort -ne 8080)"
        # guard left a stale pre-existing $env:SERVER_PORT (e.g. "18080" from .env or an ancestor
        # process) in place whenever $SpringPort itself resolved to exactly 8080 (such as an
        # explicit "-SpringPort 8080"), so Java would bind the stale port while Test-SpringHealth
        # below keeps polling the real $SpringPort (8080) - a permanent health-check/actual-port
        # mismatch. $SpringPort is the single value already used for the health check above and
        # below; it must be the same value Java actually receives, with no exception.
        $env:SERVER_PORT = "$SpringPort"
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
            Stop-NewConnectorOnFailure -ConnectorPort $ConnectorPort
            Stop-WithError $ExitCode.Spring "Spring Boot health endpoint did not become ready. $why See .runtime\logs\spring.err.log and spring.out.log"
        }
        Write-Step "Spring Boot: UP on port $SpringPort (PID $($spring.Id))"
    }

    # ---- 6. External relay (independent of Spring - never blocks on it, never blocked by it) -----
    $relayPort = Get-ExternalRelayConfiguredPort
    $relayFailed = $false
    if (Test-ExternalRelayHealth $relayPort) {
        Write-Step "External relay: UP on 127.0.0.1:$relayPort (already running, not restarted)"
    } else {
        & (Join-Path $PSScriptRoot 'external\start-external-relay.ps1') | ForEach-Object { Write-Host $_ }
        if ($LASTEXITCODE -ne 0) {
            Write-Step "External relay: FAILED to start (exit $LASTEXITCODE) - Docker/PostgreSQL/connector/Spring above are unaffected and left running. See .runtime\logs\external-relay.err.log"
            $relayFailed = $true
        }
    }

    $seconds = [int]((Get-Date) - $started).TotalSeconds
    if ($relayFailed) {
        Write-Step "RunningAI core runtime is up (${seconds}s), but the external relay failed - see above."
        exit $ExitCode.ExternalRelay
    }
    Write-Step "RunningAI runtime is up (${seconds}s). Check details with scripts\windows\status-running-ai.ps1"
    exit $ExitCode.Ok
} catch {
    $code = Get-ExitCodeFromError $_
    Write-Host "ERROR: $($_.Exception.Message)" -ForegroundColor Red
    exit $code
} finally {
    Exit-RunningAiRuntimeLock $runtimeLock
}
