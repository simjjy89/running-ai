<#
  DEFERRED 2026-10-09: the external transport decision was pivoted to Tailscale Funnel (see
  setup-running-ai-tailscale-funnel.ps1 - the current transport). This script is kept, not
  deleted, as a historical/fallback reference - it was live-tested (-DryRun, zero real changes)
  on this machine and may be revisited if Tailscale Funnel does not work out. Do not run this for
  real production setup without first re-confirming the transport decision.

.SYNOPSIS
  One-command setup for RunningAI's external read-only access (Phase 6I-1): a Cloudflare Named
  Tunnel in front of tools/external-relay (127.0.0.1:17845) only - Spring (8080) and the Garmin
  connector (8765) are never exposed.

.DESCRIPTION
  Idempotent and resumable: every step checks what already exists before acting, and a prior
  partial failure can be fixed by simply re-running this script. Never overwrites a cloudflared
  tunnel/config/service that this script did not itself create (detected by name and by a marker
  comment in config.yml). Never prints or logs a secret, cert, tunnel credential, API key, or
  token value - only file paths and existence checks.

  Steps:
    1. Check cloudflared is installed.
    2. Check Cloudflare login (%USERPROFILE%\.cloudflared\cert.pem). If missing and not -DryRun,
       runs "cloudflared tunnel login" in the foreground - this blocks on a real browser OAuth
       flow the operator must complete themselves.
    3. Resolve the fixed hostname: reuses RUNNING_AI_EXTERNAL_BASE_URL from .env if already set,
       otherwise prompts once and saves it there.
    4. Create or reuse the Named Tunnel "running-ai-external-relay".
    5. Create or reuse the DNS route for the hostname.
    6. Write %USERPROFILE%\.cloudflared\config.yml (origin = 127.0.0.1:17845, catch-all 404) -
       refuses to touch a pre-existing config.yml that is not this script's own.
    7. Install/ensure the cloudflared Windows Service (requires an elevated/Administrator shell).
    8. Ensure the external relay is running (reuses start-external-relay.ps1 - a no-op if the
       legacy instance is already healthy; never copies or generates a token here).
    9. Verify external HTTPS reachability, the auth gate, and the unsafe-path/method catch-all,
       through the real public hostname.

  Does NOT configure Cloudflare Access (Service Token) - that is a separate, not-yet-made
  decision. Until it is added, the public hostname's /health is reachable without authentication
  (matching the relay's existing contract - the same exposure level as today's quick tunnel) and
  /today-workout is still gated by the relay's own Bearer token, now proven end-to-end through
  Cloudflare rather than only locally.

.PARAMETER DryRun
  Makes zero changes: no files written, no service installed/changed, no Cloudflare API calls
  that create/modify anything (list-only calls are still made where needed to report accurate
  "already exists" status). Reports what each step would do.

.PARAMETER Hostname
  Skips the interactive prompt and uses this hostname (still validated, still saved to .env).

.PARAMETER EnvFilePath
  Where RUNNING_AI_EXTERNAL_BASE_URL is read from and written to. Defaults to the repo root
  .env file (gitignored).
#>
[CmdletBinding()]
param(
    [switch]$DryRun,
    [string]$Hostname,
    [string]$EnvFilePath
)

. "$PSScriptRoot\..\RunningAI.Common.ps1"
. "$PSScriptRoot\RunningAI.ExternalRelay.Common.ps1"
. "$PSScriptRoot\RunningAI.CloudflaredSetup.ps1"

if (-not $EnvFilePath) { $EnvFilePath = Join-Path (Get-RepoRoot) '.env' }

$script:FilesChanged = 0
$script:ServicesChanged = 0
$script:CloudflareWrites = 0

function Step { param([string]$Message) Write-Step "[step] $Message" }

function Get-RunningAiCloudflaredExe {
    $cmd = Get-Command cloudflared -ErrorAction SilentlyContinue
    if ($cmd) { return $cmd.Source }
    foreach ($p in @("$env:ProgramFiles (x86)\cloudflared\cloudflared.exe", "$env:ProgramFiles\cloudflared\cloudflared.exe")) {
        if ($p -and (Test-Path -LiteralPath $p)) { return $p }
    }
    return $null
}

function Invoke-RunningAiCloudflared {
    param([Parameter(Mandatory)][string]$Exe, [Parameter(Mandatory)][string[]]$Arguments)
    $output = & $Exe @Arguments 2>&1 | Out-String
    return @{ ExitCode = $LASTEXITCODE; Output = $output }
}

function Test-RunningAiIsAdministrator {
    $id = [Security.Principal.WindowsIdentity]::GetCurrent()
    (New-Object Security.Principal.WindowsPrincipal($id)).IsInRole([Security.Principal.WindowsBuiltinRole]::Administrator)
}

try {
    # ---- 1. cloudflared installed? -----------------------------------------------------------
    Step 'Checking cloudflared is installed'
    $cloudflared = Get-RunningAiCloudflaredExe
    if (-not $cloudflared) {
        Write-Host @'
ERROR: cloudflared was not found on PATH or in Program Files.
Install it first, then re-run this script:
  winget install --id Cloudflare.cloudflared -e
or download it from https://github.com/cloudflare/cloudflared/releases
'@ -ForegroundColor Red
        exit $ExitCode.Cloudflared
    }
    Write-Step "cloudflared found: $cloudflared"

    # ---- 2. Cloudflare login (cert.pem) -------------------------------------------------------
    Step 'Checking Cloudflare login'
    if (-not (Test-RunningAiCloudflaredLoggedIn)) {
        if ($DryRun) {
            Write-Step "DRY RUN: would run `"$cloudflared tunnel login`" (opens a browser; you log in and authorize a zone)."
        } else {
            Write-Host ''
            Write-Host 'Not logged in to Cloudflare yet. About to run:' -ForegroundColor Yellow
            Write-Host "  & `"$cloudflared`" tunnel login" -ForegroundColor Yellow
            Write-Host 'A browser window will open. Log in to your Cloudflare account and authorize the' -ForegroundColor Yellow
            Write-Host 'zone/domain you want to use. The terminal will print something like' -ForegroundColor Yellow
            Write-Host '"You have successfully logged in" once it is done; this script continues automatically.' -ForegroundColor Yellow
            Write-Host ''
            & $cloudflared tunnel login
            if (-not (Test-RunningAiCloudflaredLoggedIn)) {
                Write-Host 'ERROR: cert.pem still not present after "tunnel login" - login did not complete. Re-run this script to try again.' -ForegroundColor Red
                exit $ExitCode.Cloudflared
            }
        }
    } else {
        Write-Step "Already logged in ($script:RunningAiCloudflaredCertPath exists)"
    }

    # ---- 3. hostname ---------------------------------------------------------------------------
    Step 'Resolving external hostname'
    $resolvedHostname = $null
    if ($Hostname) {
        $resolvedHostname = ConvertTo-RunningAiHostname $Hostname
        if (-not $resolvedHostname) {
            Write-Host "ERROR: '$Hostname' is not a valid https hostname." -ForegroundColor Red
            exit $ExitCode.Usage
        }
    } else {
        $resolvedHostname = Get-RunningAiExternalHostnameFromEnvFile $EnvFilePath
    }
    if (-not $resolvedHostname) {
        if ($DryRun) {
            Write-Step 'DRY RUN: would prompt once for the fixed hostname (e.g. runningai.yourdomain.com) and save it to .env'
            $resolvedHostname = 'DRYRUN-PLACEHOLDER.invalid'
        } else {
            do {
                $typed = Read-Host 'Enter the fixed hostname for RunningAI external access (e.g. runningai.yourdomain.com)'
                $resolvedHostname = ConvertTo-RunningAiHostname $typed
                if (-not $resolvedHostname) { Write-Host 'Not a valid hostname - try again (or Ctrl+C to abort).' -ForegroundColor Yellow }
            } while (-not $resolvedHostname)
        }
    }
    Write-Step "Hostname: $resolvedHostname"
    if (-not $DryRun -and ($Hostname -or -not (Get-RunningAiExternalHostnameFromEnvFile $EnvFilePath))) {
        Set-RunningAiExternalHostnameInEnvFile -EnvFilePath $EnvFilePath -Hostname $resolvedHostname
        $script:FilesChanged++
        Write-Step "Saved RUNNING_AI_EXTERNAL_BASE_URL=https://$resolvedHostname to $EnvFilePath"
    }

    # ---- 4. named tunnel -------------------------------------------------------------------------
    Step "Checking for an existing Named Tunnel '$script:RunningAiTunnelName'"
    $tunnelId = $null
    if (Test-RunningAiCloudflaredLoggedIn -or $DryRun) {
        $list = if ($DryRun -and -not (Test-RunningAiCloudflaredLoggedIn)) { @{ ExitCode = 0; Output = '' } } else { Invoke-RunningAiCloudflared -Exe $cloudflared -Arguments @('tunnel', 'list') }
        if ($list.ExitCode -ne 0 -and -not $DryRun) {
            Write-Host "ERROR: 'cloudflared tunnel list' failed:`n$($list.Output)" -ForegroundColor Red
            exit $ExitCode.Cloudflared
        }
        $tunnelId = Find-RunningAiNamedTunnelId -ListOutput $list.Output
    }
    if ($tunnelId) {
        Write-Step "Reusing existing tunnel (id $tunnelId)"
    } elseif ($DryRun) {
        Write-Step "DRY RUN: would run `"$cloudflared tunnel create $script:RunningAiTunnelName`""
        $tunnelId = '00000000-0000-0000-0000-000000000000'
    } else {
        $created = Invoke-RunningAiCloudflared -Exe $cloudflared -Arguments @('tunnel', 'create', $script:RunningAiTunnelName)
        if ($created.ExitCode -ne 0) {
            Write-Host "ERROR: 'cloudflared tunnel create $script:RunningAiTunnelName' failed:`n$($created.Output)" -ForegroundColor Red
            exit $ExitCode.Cloudflared
        }
        $tunnelId = Find-RunningAiCreatedTunnelId -CreateOutput $created.Output
        if (-not $tunnelId) {
            Write-Host "ERROR: tunnel create succeeded but its id could not be parsed from cloudflared's own output." -ForegroundColor Red
            exit $ExitCode.Cloudflared
        }
        $script:CloudflareWrites++
        Write-Step "Created tunnel (id $tunnelId)"
    }

    # ---- 5. DNS route ------------------------------------------------------------------------
    Step "Ensuring DNS route for $resolvedHostname"
    if ($DryRun) {
        Write-Step "DRY RUN: would run `"$cloudflared tunnel route dns $script:RunningAiTunnelName $resolvedHostname`""
    } else {
        $route = Invoke-RunningAiCloudflared -Exe $cloudflared -Arguments @('tunnel', 'route', 'dns', $script:RunningAiTunnelName, $resolvedHostname)
        if ($route.ExitCode -ne 0 -and -not (Test-RunningAiCloudflaredAlreadyDoneMessage $route.Output)) {
            Write-Host "ERROR: 'cloudflared tunnel route dns' failed:`n$($route.Output)" -ForegroundColor Red
            exit $ExitCode.Cloudflared
        }
        if ($route.ExitCode -ne 0) { Write-Step 'DNS route already exists - reusing.' } else { $script:CloudflareWrites++; Write-Step 'DNS route created.' }
    }

    # ---- 6. config.yml -------------------------------------------------------------------------
    Step 'Writing cloudflared config.yml'
    $credentialsFile = Get-RunningAiTunnelCredentialsFilePath -TunnelId $tunnelId
    $configYaml = Get-RunningAiCloudflaredConfigYaml -TunnelId $tunnelId -Hostname $resolvedHostname -CredentialsFile $credentialsFile
    if (-not (Test-RunningAiCloudflaredConfigIsOurs -Path $script:RunningAiCloudflaredConfigPath)) {
        Write-Host "ERROR: $script:RunningAiCloudflaredConfigPath already exists and was not created by this script. Refusing to overwrite a config this script does not own. Back it up/remove it, or point cloudflared at a different config, then re-run." -ForegroundColor Red
        exit $ExitCode.Cloudflared
    }
    $existingConfig = if (Test-Path -LiteralPath $script:RunningAiCloudflaredConfigPath) { Get-Content -LiteralPath $script:RunningAiCloudflaredConfigPath -Raw } else { $null }
    $configChanged = ($existingConfig -ne $configYaml)
    if ($DryRun) {
        Write-Step "DRY RUN: would write $script:RunningAiCloudflaredConfigPath (changed: $configChanged)"
    } elseif ($configChanged) {
        New-Item -ItemType Directory -Force $script:RunningAiCloudflaredDir | Out-Null
        Set-Content -LiteralPath $script:RunningAiCloudflaredConfigPath -Value $configYaml -Encoding utf8
        $script:FilesChanged++
        Write-Step "Wrote $script:RunningAiCloudflaredConfigPath"
    } else {
        Write-Step "$script:RunningAiCloudflaredConfigPath already up to date"
    }

    # ---- 7. Windows Service --------------------------------------------------------------------
    Step 'Checking the cloudflared Windows Service'
    $existingService = Get-Service -Name 'cloudflared' -ErrorAction SilentlyContinue
    if (-not $existingService) {
        if ($DryRun) {
            Write-Step "DRY RUN: would run `"$cloudflared service install`" (requires an elevated shell)"
        } elseif (-not (Test-RunningAiIsAdministrator)) {
            Write-Host 'ERROR: Installing the cloudflared Windows Service requires an elevated (Run as Administrator) PowerShell window. Re-run this script from one - every step above is already done and will be skipped automatically.' -ForegroundColor Red
            exit $ExitCode.Cloudflared
        } else {
            $install = Invoke-RunningAiCloudflared -Exe $cloudflared -Arguments @('service', 'install')
            if ($install.ExitCode -ne 0) {
                Write-Host "ERROR: 'cloudflared service install' failed:`n$($install.Output)" -ForegroundColor Red
                exit $ExitCode.Cloudflared
            }
            $script:ServicesChanged++
            Write-Step 'cloudflared Windows Service installed.'
        }
    } else {
        Write-Step "cloudflared Windows Service already installed (status: $($existingService.Status))"
        if (-not $DryRun) {
            if ($existingService.StartType -ne 'Automatic') { Set-Service -Name 'cloudflared' -StartupType Automatic; $script:ServicesChanged++ }
            if ($existingService.Status -ne 'Running') { Start-Service -Name 'cloudflared'; $script:ServicesChanged++ }
            elseif ($configChanged) { Restart-Service -Name 'cloudflared'; $script:ServicesChanged++; Write-Step 'Restarted cloudflared service to pick up the updated config.' }
        }
    }

    # ---- 8. external relay ----------------------------------------------------------------------
    Step 'Ensuring the external relay is running on 127.0.0.1:17845'
    if ($DryRun) {
        Write-Step 'DRY RUN: would run start-external-relay.ps1 (no-op if a healthy relay is already on this port)'
    } else {
        & (Join-Path $PSScriptRoot 'start-external-relay.ps1') | ForEach-Object { Write-Host $_ }
        if ($LASTEXITCODE -ne 0) {
            Write-Host 'ERROR: the external relay is not healthy - the tunnel would have nothing to serve. See the message above.' -ForegroundColor Red
            exit $ExitCode.ExternalAccess
        }
    }

    # ---- 9. verification ------------------------------------------------------------------------
    Step 'Verifying external HTTPS access'
    if ($DryRun) {
        Write-Step "DRY RUN: would GET https://$resolvedHostname/health, confirm /today-workout requires auth, and confirm an unlisted path/method is rejected"
    } else {
        $result = Test-RunningAiExternalAccess -Hostname $resolvedHostname -TimeoutSec 15 -TunnelDownStatuses @(502, 521, 523, 530)
        Write-Step "ExternalEndpoint: $($result.HealthState) (HTTP $($result.HealthStatus))"
        Write-Step "Auth gate (no credential) -> $($result.AuthState)"
        Write-Step "Unsafe path -> $($result.UnsafeState)"

        if ($result.HealthState -ne 'UP') {
            Write-Host "ERROR: external health check did not return UP (got $($result.HealthState)). See above for the tunnel/service/relay states." -ForegroundColor Red
            exit $ExitCode.ExternalAccess
        }
    }

    Write-Host ''
    Write-Step "files changed: $script:FilesChanged"
    Write-Step "services changed: $script:ServicesChanged"
    Write-Step 'tasks changed: 0 (this script never creates/modifies a Scheduled Task)'
    Write-Step "Cloudflare writes: $script:CloudflareWrites"
    Write-Host ''
    Write-Host 'Cloudflare Access (Service Token) is NOT configured by this script - /health is reachable' -ForegroundColor Yellow
    Write-Host 'by anyone who knows the hostname (same exposure as the old quick tunnel); /today-workout' -ForegroundColor Yellow
    Write-Host 'is still gated by the relay''s own Bearer token, now proven end-to-end through Cloudflare.' -ForegroundColor Yellow
    Write-Host 'This is a deliberate scope decision for this run, not an oversight.' -ForegroundColor Yellow
    Write-Host ''
    Write-Host 'Phase 6I-1 is still NOT READY: this only proves the Named Tunnel E2E path locally.' -ForegroundColor Yellow
    Write-Host 'Real LTE/5G + Garmin 265 + PC reboot validation remain.' -ForegroundColor Yellow

    exit $ExitCode.Ok
} catch {
    Write-Host "ERROR: $($_.Exception.Message)" -ForegroundColor Red
    exit (Get-ExitCodeFromError $_)
}
