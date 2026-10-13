<#
.SYNOPSIS
  Builds, verifies and releases the production Spring Boot jar as three separate stages.

.DESCRIPTION
  Phase 7E-5. Production used to run the Gradle output itself
  (server\build\libs\running-ai-server-*.jar). That one fact caused three distinct problems:

    1. a rebuild overwrote the very artifact production was running, with no backup and no
       check of what had just been produced - a build from an out-of-date worktree silently
       became production (observed: a V20-era source replacing a working V26 jar);
    2. the running JVM holds its jar open on Windows, so "gradlew clean" in the production
       worktree failed, and an overwrite could in principle leave a torn file behind;
    3. "whatever jar is newest under build\libs" was the production contract, so any build
       anywhere in that worktree changed what the next start would run.

  This script replaces that with a RELEASED runtime jar at a stable path that is never a Gradle
  output and is never written by anything but this script:

      build    gradlew bootJar  ->  server\build\libs\running-ai-server-*.jar   (never executed)
      verify   the staged jar is a real, complete boot jar; its Flyway migrations are listed
      release  atomic publish   ->  .runtime\running-ai-server.jar              (what runs)

  Every release writes a provenance sidecar (.runtime\running-ai-server.jar.json) recording the
  git commit, branch, worktree cleanliness, SHA-256 and the highest Flyway migration inside the
  jar, so "which source is production actually running?" is answerable without guessing. The
  previously released jar is kept under .runtime\released\ so a bad release is one copy away
  from being undone.

  Concurrency: the publish step takes the SAME named runtime lock start-running-ai.ps1 and
  stop-running-ai.ps1 use, so it can never interleave with a Watchdog-driven restart, and the
  jar itself is published by renaming a fully written temporary file in the same directory - a
  start that happens anyway sees either the old jar or the new one, never a partial one.

  Exit codes: 0 ok | 13 Java/build/verify | 25 another start/stop/release holds the runtime lock
  | 1 other.

.PARAMETER VerifyOnly
  Build and verify, then stop. The released jar is NOT touched. Use this to prove a worktree
  builds a valid jar without changing anything production will run.

.PARAMETER SkipBuild
  Verify (and release) the jar already under server\build\libs instead of rebuilding it.

.PARAMETER AllowDirty
  Permit a release built from a worktree with uncommitted changes. Refused by default: a
  released jar whose source cannot be named by a commit cannot be reproduced or rolled back to.
  -VerifyOnly never requires this.

.PARAMETER ExpectedMaxMigration
  When given, the verify stage fails unless the highest Flyway migration inside the jar is
  exactly this number (e.g. 26). Guards against releasing a jar built from a worktree that
  predates the schema the production database is already on.

.PARAMETER RuntimeLockHeldByCaller
  The caller is already inside the runtime lock and this script must not try to take it again.
  Only start-running-ai.ps1 -Build passes this, from inside its own lock; a Mutex is owned by the
  process that acquired it and cannot be handed to a child, so without this the child would wait
  on its own parent and report the runtime as busy.

.EXAMPLE
  .\release-spring-jar.ps1 -VerifyOnly -ExpectedMaxMigration 26
  .\release-spring-jar.ps1 -ExpectedMaxMigration 26
#>
[CmdletBinding()]
param(
    [switch]$VerifyOnly,
    [switch]$SkipBuild,
    [switch]$AllowDirty,
    [int]$ExpectedMaxMigration = 0,
    [int]$KeepBackups = 5,
    [switch]$RuntimeLockHeldByCaller
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

. "$PSScriptRoot\RunningAI.Common.ps1"

$root = Get-RepoRoot

function Get-StagedSpringJar {
    $libs = Join-Path $root 'server\build\libs'
    if (-not (Test-Path $libs)) { return $null }
    Get-ChildItem $libs -Filter 'running-ai-server-*.jar' |
        Where-Object { $_.Name -notlike '*-plain.jar' } |
        Sort-Object LastWriteTime -Descending | Select-Object -First 1
}

# A JVM that was started on the Gradle output itself keeps that file open, so bootJar cannot
# replace it and "gradlew clean" cannot delete it. That is the very situation this script exists
# to end, so it is reported as an operator action rather than left to fail as a confusing Gradle
# error halfway through a build.
function Assert-BuildOutputNotInUse {
    $libs = (Join-Path $root 'server\build\libs')
    $holders = @(Get-CimInstance Win32_Process -Filter "Name='java.exe'" -ErrorAction SilentlyContinue |
        Where-Object { $_.CommandLine -and $_.CommandLine.IndexOf($libs, [StringComparison]::OrdinalIgnoreCase) -ge 0 })
    if ($holders.Count -gt 0) {
        $pids = ($holders | ForEach-Object { $_.ProcessId }) -join ', '
        Stop-WithError $ExitCode.Java (
            "A running JVM (PID $pids) is executing the Gradle output under $libs, so it cannot be rebuilt. " +
            'That process must first be moved onto the released jar: run this script with -VerifyOnly to ' +
            'confirm the build, stop Spring (stop-running-ai.ps1), release, then start again.')
    }
}

function Get-GitProvenance {
    $commit = (& git -C $root rev-parse HEAD 2>$null)
    if ($LASTEXITCODE -ne 0 -or -not $commit) { return $null }
    $branch = (& git -C $root rev-parse --abbrev-ref HEAD 2>$null)
    $status = @(& git -C $root status --porcelain 2>$null)
    [pscustomobject]@{
        Commit = $commit.Trim()
        Branch = "$branch".Trim()
        Dirty  = ($status.Count -gt 0)
        DirtyFiles = $status.Count
    }
}

# Reads the jar as a zip and reports what makes it a usable production artifact. Nothing here is
# inferred from the file name or its size: a jar that is not a complete Spring Boot application,
# or that carries a different schema than expected, must not reach the released path.
function Test-BootJar {
    param([Parameter(Mandatory)][string]$Path)

    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $zip = [System.IO.Compression.ZipFile]::OpenRead($Path)
    try {
        $names = $zip.Entries | ForEach-Object { $_.FullName }
        $hasLoader  = @($names | Where-Object { $_ -like 'org/springframework/boot/loader/*' }).Count -gt 0
        $hasClasses = @($names | Where-Object { $_ -like 'BOOT-INF/classes/*' }).Count -gt 0
        $hasLibs    = @($names | Where-Object { $_ -like 'BOOT-INF/lib/*.jar' }).Count

        $migrations = @($names | Where-Object { $_ -match '^BOOT-INF/classes/db/migration/V(\d+)__.+\.sql$' })
        $versions = @($migrations | ForEach-Object { [int]([regex]::Match($_, 'V(\d+)__')).Groups[1].Value })

        $manifestEntry = $zip.GetEntry('META-INF/MANIFEST.MF')
        $manifest = ''
        if ($manifestEntry) {
            $reader = New-Object System.IO.StreamReader($manifestEntry.Open())
            try { $manifest = $reader.ReadToEnd() } finally { $reader.Dispose() }
        }
        $startClass = ''
        if ($manifest -match 'Start-Class:\s*(\S+)') { $startClass = $Matches[1] }

        [pscustomobject]@{
            HasLoader      = $hasLoader
            HasClasses     = $hasClasses
            LibCount       = $hasLibs
            StartClass     = $startClass
            MigrationCount = $migrations.Count
            MaxMigration   = if ($versions.Count) { ($versions | Measure-Object -Maximum).Maximum } else { 0 }
        }
    } finally { $zip.Dispose() }
}

function Get-Sha256 {
    param([Parameter(Mandatory)][string]$Path)
    (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant()
}

# ---- 0. provenance -------------------------------------------------------------------------
$git = Get-GitProvenance
if ($git) {
    Write-Step "Source: $($git.Branch) @ $($git.Commit.Substring(0,7))$(if ($git.Dirty) { " (DIRTY: $($git.DirtyFiles) uncommitted path(s))" })"
} else {
    Write-Step 'Source: git provenance unavailable (not a git worktree?).'
}
if (-not $VerifyOnly -and $git -and $git.Dirty -and -not $AllowDirty) {
    Stop-WithError $ExitCode.Java (
        'Refusing to release a jar built from a worktree with uncommitted changes: the released ' +
        'artifact could not be reproduced from, or rolled back to, any commit. Commit the changes, ' +
        'or pass -AllowDirty deliberately.')
}

try {
    New-Item -ItemType Directory -Force $script:LogDir | Out-Null

    # ---- 1. build --------------------------------------------------------------------------
    if ($SkipBuild) {
        Write-Step 'Skipping the build (-SkipBuild): verifying the existing Gradle output.'
    } else {
        $java = Find-RunningAiJava21
        if (-not $java) { Stop-WithError $ExitCode.Java 'Java 21 is not available (checked JAVA_HOME, PATH, machine/user JAVA_HOME and Program Files\Java). Install JDK 21 and/or set JAVA_HOME.' }
        Assert-BuildOutputNotInUse
        Write-Step "Building the Spring Boot jar (gradlew bootJar) with $($java.Home)."
        $env:JAVA_HOME = $java.Home
        $buildLog = Join-Path $script:LogDir 'gradle-bootjar.log'
        Push-Location $script:ServerDir
        try {
            cmd /c ".\gradlew.bat bootJar --console=plain > `"$buildLog`" 2>&1"
            $buildExit = $LASTEXITCODE
        } finally { Pop-Location }
        if ($buildExit -ne 0) { Stop-WithError $ExitCode.Java "Spring Boot jar build failed (exit $buildExit). See $buildLog" }
    }

    $staged = Get-StagedSpringJar
    if (-not $staged) { Stop-WithError $ExitCode.Java "No boot jar found under $(Join-Path $root 'server\build\libs')." }

    # ---- 2. verify -------------------------------------------------------------------------
    Write-Step "Verifying $($staged.Name) ($([math]::Round($staged.Length / 1MB, 1)) MB)."
    $info = Test-BootJar -Path $staged.FullName
    if (-not $info.HasLoader)  { Stop-WithError $ExitCode.Java 'Not an executable Spring Boot jar: no org/springframework/boot/loader entries (a *-plain.jar or a corrupt archive?).' }
    if (-not $info.HasClasses) { Stop-WithError $ExitCode.Java 'Not an executable Spring Boot jar: no BOOT-INF/classes entries.' }
    if ($info.LibCount -lt 1)  { Stop-WithError $ExitCode.Java 'Not an executable Spring Boot jar: BOOT-INF/lib holds no dependency jars.' }
    if (-not $info.StartClass) { Stop-WithError $ExitCode.Java 'Not an executable Spring Boot jar: the manifest declares no Start-Class.' }
    $stagedSha = Get-Sha256 $staged.FullName
    Write-Step "  Start-Class : $($info.StartClass)"
    Write-Step "  dependencies: $($info.LibCount) jars"
    Write-Step "  migrations  : $($info.MigrationCount) files, highest V$($info.MaxMigration)"
    Write-Step "  sha256      : $stagedSha"
    if ($ExpectedMaxMigration -gt 0 -and $info.MaxMigration -ne $ExpectedMaxMigration) {
        Stop-WithError $ExitCode.Java (
            "Schema mismatch: the jar carries migrations up to V$($info.MaxMigration), but V$ExpectedMaxMigration was required. " +
            'This is what an out-of-date worktree looks like - do not release it.')
    }

    if ($VerifyOnly) {
        Write-Step 'Verify-only: the released jar was NOT touched.'
        exit $ExitCode.Ok
    }

    # ---- 3. release ------------------------------------------------------------------------
    # Serialized against start/stop/Watchdog recovery, so no start can read the released path
    # while it is being replaced.
    # A Mutex is owned by the acquiring process and cannot be passed to a child, so a caller that
    # is already inside the runtime lock (start-running-ai.ps1 -Build) says so explicitly instead
    # of this script waiting on its own parent and reporting the runtime as busy.
    $lock = $null
    if ($RuntimeLockHeldByCaller) {
        Write-Step 'Publishing under the caller-held runtime lock.'
    } else {
        $lock = Enter-RunningAiRuntimeLock -TimeoutSec 10
        if (-not $lock) {
            Write-Step 'Another start-running-ai.ps1, stop-running-ai.ps1 or release is in progress for this runtime; nothing was released.'
            exit $ExitCode.Busy
        }
    }
    try {
        $releasedPath = Get-RunningAiReleasedJarPath
        $sidecarPath  = "$releasedPath.json"
        New-Item -ItemType Directory -Force (Split-Path $releasedPath) | Out-Null

        if (Test-Path -LiteralPath $releasedPath) {
            $currentSha = Get-Sha256 $releasedPath
            if ($currentSha -eq $stagedSha) {
                Write-Step "The released jar is already these exact bytes ($($stagedSha.Substring(0,12))...); nothing to replace."
            }
            $backupDir = Join-Path (Split-Path $releasedPath) 'released'
            New-Item -ItemType Directory -Force $backupDir | Out-Null
            $backup = Join-Path $backupDir ("running-ai-server-{0}-{1}.jar" -f (Get-Date -Format 'yyyyMMdd-HHmmss'), $currentSha.Substring(0, 8))
            Copy-Item -LiteralPath $releasedPath -Destination $backup -Force
            if (Test-Path -LiteralPath $sidecarPath) { Copy-Item -LiteralPath $sidecarPath -Destination "$backup.json" -Force }
            Write-Step "Previous release kept at $backup"
        }

        # Written under a temporary name in the SAME directory and renamed into place, so a
        # concurrent start never sees a half-copied jar.
        $temp = "$releasedPath.new"
        Copy-Item -LiteralPath $staged.FullName -Destination $temp -Force
        $tempSha = Get-Sha256 $temp
        if ($tempSha -ne $stagedSha) {
            Remove-Item -LiteralPath $temp -Force -ErrorAction SilentlyContinue
            Stop-WithError $ExitCode.Java "The copy to the released path did not reproduce the verified bytes ($tempSha != $stagedSha); nothing was replaced."
        }
        Move-Item -LiteralPath $temp -Destination $releasedPath -Force

        [pscustomobject]@{
            sha256         = $stagedSha
            releasedAt     = (Get-Date).ToString('o')
            sourceJar      = $staged.Name
            gitCommit      = if ($git) { $git.Commit } else { $null }
            gitBranch      = if ($git) { $git.Branch } else { $null }
            gitDirty       = if ($git) { $git.Dirty } else { $null }
            startClass     = $info.StartClass
            migrationCount = $info.MigrationCount
            maxMigration   = $info.MaxMigration
        } | ConvertTo-Json | Set-Content -LiteralPath $sidecarPath -Encoding UTF8

        $backupDir = Join-Path (Split-Path $releasedPath) 'released'
        if ($KeepBackups -gt 0 -and (Test-Path $backupDir)) {
            Get-ChildItem $backupDir -Filter 'running-ai-server-*.jar' |
                Sort-Object LastWriteTime -Descending | Select-Object -Skip $KeepBackups |
                ForEach-Object {
                    Remove-Item -LiteralPath $_.FullName -Force -ErrorAction SilentlyContinue
                    Remove-Item -LiteralPath "$($_.FullName).json" -Force -ErrorAction SilentlyContinue
                }
        }

        Write-Step "Released -> $releasedPath"
        Write-Step 'The running Spring process is NOT affected: a release only decides what the NEXT start runs.'
    } finally { Exit-RunningAiRuntimeLock $lock }

    exit $ExitCode.Ok
} catch {
    $code = Get-ExitCodeFromError $_
    Write-Host "[release-spring-jar] $($_.Exception.Message)" -ForegroundColor Red
    exit $code
}
