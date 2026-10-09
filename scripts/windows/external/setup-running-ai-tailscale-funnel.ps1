<#
.SYNOPSIS
  One-command setup for RunningAI's external read-only access (Phase 6I-1, current transport as of
  2026-10-09): Tailscale Funnel in front of tools/external-relay (127.0.0.1:<configured port>,
  default 17845) only - Spring (8080) and the Garmin connector (8765) are never exposed. No domain
  purchase, no Cloudflare, no ipTIME WireGuard/port-forwarding, zero ongoing cost.

.DESCRIPTION
  Idempotent and resumable: every step checks what already exists before acting, and a prior
  partial failure can be fixed by simply re-running this script. Never prints or logs a secret,
  auth key, or token value - only file paths, version strings, and public hostnames.

  Steps:
    1. Check Tailscale is installed; attempt a winget install if not (reports and stops if winget
       is unavailable or the install fails - never silently retries).
    2. Check the installed version is >= 1.38.3 (Funnel's minimum).
    3. Check login state (`tailscale status --json` -> BackendState). If not logged in and not
       -DryRun, runs "tailscale up" in the foreground - this blocks on a real browser auth flow
       the operator must complete themselves.
    4. Check the tailnet has MagicDNS enabled (a tailnet-wide admin-console toggle this script
       cannot set) - stops with the exact console link if it is confirmed off.
    5. Ensure the external relay is healthy on its configured port (reuses start-external-relay.ps1
       - a no-op if already healthy; never copies or generates a token here).
    6. Check whether Funnel is already serving our target; if not, runs
       "tailscale funnel --bg http://127.0.0.1:<port>". HTTPS-enablement and the Funnel
       node-attribute grant (both also admin-console/tailnet-policy concerns) are not
       independently pre-checked - if either is missing, Tailscale's own command fails with its
       own diagnostic, which this script surfaces verbatim rather than guessing at a workaround.
    7. Resolves and saves the assigned *.ts.net hostname to .env
       (RUNNING_AI_EXTERNAL_BASE_URL - the same key the Cloudflare setup used).
    8. Reports (and, for a minimal fix, corrects) the Tailscale Windows Service's startup type so
       the tailnet connection and Funnel config survive a reboot - per Tailscale's own documented
       behavior, a "-bg" Funnel configuration resumes automatically once tailscaled is running
       again; this script does not duplicate that persistence itself.
    9. Verifies external HTTPS reachability, the auth gate, and the unsafe-path/method catch-all,
       through the real public hostname.

  Funnel has no equivalent of Cloudflare Access: once enabled, the hostname is a genuinely public
  HTTPS endpoint. /health is reachable by anyone who learns it (same exposure as the relay has
  always had); /today-workout remains gated by the relay's own Bearer token - this is the ONLY
  auth boundary now, which makes that token mandatory in a way it was not before Funnel.

.PARAMETER DryRun
  Makes zero changes: no install, no `tailscale up`, no Funnel enable/service changes, no .env
  write. Reports what each step would do.

.PARAMETER Hostname is intentionally not a parameter here - the *.ts.net hostname is assigned by
  Tailscale (device name + tailnet name), not chosen by the operator.

.PARAMETER EnvFilePath
  Where RUNNING_AI_EXTERNAL_BASE_URL is read from and written to. Defaults to the repo root .env
  file (gitignored).
#>
[CmdletBinding()]
param(
    [switch]$DryRun,
    [string]$EnvFilePath
)

. "$PSScriptRoot\..\RunningAI.Common.ps1"
. "$PSScriptRoot\RunningAI.ExternalRelay.Common.ps1"
. "$PSScriptRoot\RunningAI.TailscaleSetup.ps1"

if (-not $EnvFilePath) { $EnvFilePath = Join-Path (Get-RepoRoot) '.env' }

$script:FilesChanged = 0
$script:ServicesChanged = 0
$script:TailscaleWrites = 0

function Step { param([string]$Message) Write-Step "[step] $Message" }

# Null-coalesces Get-RunningAiTailscaleStatusJson's failure case to '' so every downstream parser
# (which all accept an empty string safely) never has to separately handle $null.
function Get-RunningAiTailscaleStatusJsonOrEmpty {
    param([Parameter(Mandatory)][string]$Exe)
    $json = Get-RunningAiTailscaleStatusJson -Exe $Exe
    if ($null -eq $json) { return '' }
    return $json
}

try {
    # ---- 1. tailscale installed? ---------------------------------------------------------------
    Step 'Checking Tailscale is installed'
    $tailscale = Get-RunningAiTailscaleExe
    if (-not $tailscale) {
        $winget = Get-Command winget -ErrorAction SilentlyContinue
        if ($DryRun) {
            if ($winget) { Write-Step 'DRY RUN: would run "winget install --id Tailscale.Tailscale -e"' }
            else { Write-Step 'DRY RUN: winget not found - would stop with manual install instructions' }
        } elseif ($winget) {
            Write-Host 'Tailscale not found - installing via winget (this may show its own UAC/installer prompt).' -ForegroundColor Yellow
            $install = & winget install --id Tailscale.Tailscale -e --accept-package-agreements --accept-source-agreements 2>&1 | Out-String
            Write-Host $install
            $tailscale = Get-RunningAiTailscaleExe
            if (-not $tailscale) {
                Write-Host @'
ERROR: winget install did not leave a usable tailscale.exe on PATH or in Program Files.
Install it manually from https://tailscale.com/download/windows, then re-run this script.
'@ -ForegroundColor Red
                exit $ExitCode.Other
            }
            $script:ServicesChanged++
        } else {
            Write-Host @'
ERROR: Tailscale was not found and winget is not available to install it automatically.
Install it manually from https://tailscale.com/download/windows, then re-run this script.
'@ -ForegroundColor Red
            exit $ExitCode.Other
        }
    }
    if ($tailscale) { Write-Step "Tailscale found: $tailscale" }

    # ---- 2. version ----------------------------------------------------------------------------
    Step 'Checking the installed Tailscale version'
    if ($tailscale) {
        $versionResult = Invoke-RunningAiTailscaleCommand -Exe $tailscale -Arguments @('version')
        if (-not (Test-RunningAiTailscaleVersionSupported $versionResult.StdOut)) {
            Write-Host "ERROR: Tailscale version is too old for Funnel (needs >= 1.38.3). Got:`n$($versionResult.StdOut)" -ForegroundColor Red
            Write-Host 'Upgrade with: winget upgrade --id Tailscale.Tailscale -e' -ForegroundColor Red
            exit $ExitCode.Other
        }
        Write-Step "Version OK: $(($versionResult.StdOut -split "`r?`n")[0].Trim())"
    } elseif ($DryRun) {
        Write-Step 'DRY RUN: would check the installed version (>= 1.38.3)'
    }

    # ---- 3. login state --------------------------------------------------------------------------
    Step 'Checking Tailscale login state'
    $loggedIn = $false
    if ($tailscale) {
        $loggedIn = Test-RunningAiTailscaleLoggedIn (Get-RunningAiTailscaleStatusJsonOrEmpty -Exe $tailscale)
    }
    if (-not $loggedIn) {
        if ($DryRun) {
            Write-Step 'DRY RUN: would run "tailscale up" (opens a browser auth flow; you log in and approve the device)'
        } else {
            Write-Host ''
            Write-Host 'Not logged in to Tailscale yet. About to run:' -ForegroundColor Yellow
            Write-Host "  & `"$tailscale`" up" -ForegroundColor Yellow
            Write-Host 'This prints a URL like "To authenticate, visit: https://login.tailscale.com/a/xxxxxxxx".' -ForegroundColor Yellow
            Write-Host 'Open it, log in, and approve this device. The command returns automatically once' -ForegroundColor Yellow
            Write-Host 'authentication completes; this script continues automatically after that.' -ForegroundColor Yellow
            Write-Host ''
            # Deliberately NOT Invoke-RunningAiTailscaleCommand: this one must inherit the real
            # console (see the file-header note) so the operator sees the auth URL in real time.
            & $tailscale up
            if (-not (Test-RunningAiTailscaleLoggedIn (Get-RunningAiTailscaleStatusJsonOrEmpty -Exe $tailscale))) {
                Write-Host 'ERROR: still not logged in after "tailscale up" - it did not complete. Re-run this script to try again.' -ForegroundColor Red
                exit $ExitCode.Other
            }
            $script:TailscaleWrites++
        }
    } else {
        Write-Step 'Already logged in and connected (BackendState=Running, Self.Online=true)'
    }

    # ---- 4. MagicDNS -----------------------------------------------------------------------------
    Step 'Checking MagicDNS is enabled for this tailnet'
    if ($tailscale -and -not $DryRun) {
        $magicDns = Test-RunningAiTailscaleMagicDnsEnabled (Get-RunningAiTailscaleStatusJsonOrEmpty -Exe $tailscale)
        if ($magicDns -eq $false) {
            Write-Host @'
ERROR: MagicDNS is not enabled for this tailnet. This is a tailnet-wide setting only an admin can
change, at:
  https://login.tailscale.com/admin/dns
Enable "MagicDNS", then re-run this script.
'@ -ForegroundColor Red
            exit $ExitCode.Other
        } elseif ($null -eq $magicDns) {
            Write-Step 'Could not confirm MagicDNS state from `tailscale status --json` - continuing; the live Funnel-enable step below will surface a clear error if it is actually off.'
        } else {
            Write-Step 'MagicDNS enabled'
        }
    } elseif ($DryRun) {
        Write-Step 'DRY RUN: would check CurrentTailnet.MagicDNSEnabled'
    }

    # ---- 5. external relay ----------------------------------------------------------------------
    $port = Get-ExternalRelayConfiguredPort
    Step "Ensuring the external relay is running on 127.0.0.1:$port"
    if ($DryRun) {
        Write-Step 'DRY RUN: would run start-external-relay.ps1 (no-op if a healthy relay is already on this port)'
    } else {
        & (Join-Path $PSScriptRoot 'start-external-relay.ps1') | ForEach-Object { Write-Host $_ }
        if ($LASTEXITCODE -ne 0) {
            Write-Host 'ERROR: the external relay is not healthy - Funnel would have nothing to serve. See the message above.' -ForegroundColor Red
            exit $ExitCode.ExternalAccess
        }
    }

    # ---- 6. Funnel enable ------------------------------------------------------------------------
    $target = "http://127.0.0.1:$port"
    Step "Checking whether Funnel already serves $target"
    $resolvedHostname = $null
    if ($tailscale -and -not $DryRun) {
        $funnelStatus = (Invoke-RunningAiTailscaleCommand -Exe $tailscale -Arguments @('funnel', 'status')).StdOut
        if (Test-RunningAiFunnelAlreadyServingTarget -StatusOutput $funnelStatus -Target $target) {
            Write-Step 'Funnel already serves this target - reusing.'
            $resolvedHostname = Find-RunningAiFunnelHostname $funnelStatus
        } else {
            $enable = Invoke-RunningAiTailscaleCommand -Exe $tailscale -Arguments @('funnel', '--bg', $target)
            if ($enable.ExitCode -ne 0) {
                Write-Host "ERROR: `"tailscale funnel --bg $target`" failed:`n$($enable.StdOut)$($enable.StdErr)" -ForegroundColor Red
                Write-Host 'This is Tailscale''s own diagnostic - it usually names exactly what is missing (HTTPS, the' -ForegroundColor Red
                Write-Host 'Funnel node attribute in your tailnet policy, etc.) and often an admin-console link to fix' -ForegroundColor Red
                Write-Host 'it. Address what it says, then re-run this script.' -ForegroundColor Red
                exit $ExitCode.ExternalAccess
            }
            $script:TailscaleWrites++
            $resolvedHostname = Find-RunningAiFunnelHostname ($enable.StdOut + $enable.StdErr)
            if (-not $resolvedHostname) {
                $funnelStatus = (Invoke-RunningAiTailscaleCommand -Exe $tailscale -Arguments @('funnel', 'status')).StdOut
                $resolvedHostname = Find-RunningAiFunnelHostname $funnelStatus
            }
            if (-not $resolvedHostname) {
                $resolvedHostname = Find-RunningAiTailscaleSelfDnsName (Get-RunningAiTailscaleStatusJsonOrEmpty -Exe $tailscale)
            }
            if (-not $resolvedHostname) {
                Write-Host "ERROR: Funnel was enabled but its public hostname could not be determined from Tailscale's own output." -ForegroundColor Red
                exit $ExitCode.ExternalAccess
            }
            Write-Step "Funnel enabled -> https://$resolvedHostname"
        }
    } elseif ($DryRun) {
        Write-Step "DRY RUN: would run `"tailscale funnel --bg $target`" if not already serving it"
        $resolvedHostname = 'DRYRUN-PLACEHOLDER.ts.net'
    }

    # ---- 7. save hostname ------------------------------------------------------------------------
    Step 'Saving the resolved hostname'
    if (-not $DryRun -and $resolvedHostname) {
        $existing = Get-RunningAiExternalHostnameFromEnvFile $EnvFilePath
        if ($existing -ne $resolvedHostname) {
            Set-RunningAiExternalHostnameInEnvFile -EnvFilePath $EnvFilePath -Hostname $resolvedHostname
            $script:FilesChanged++
            Write-Step "Saved RUNNING_AI_EXTERNAL_BASE_URL=https://$resolvedHostname to $EnvFilePath"
        } else {
            Write-Step "$EnvFilePath already up to date"
        }
    } elseif ($DryRun) {
        Write-Step "DRY RUN: would save RUNNING_AI_EXTERNAL_BASE_URL=https://$resolvedHostname to $EnvFilePath"
    }

    # ---- 8. reboot persistence -------------------------------------------------------------------
    Step 'Checking the Tailscale Windows Service start type (reboot persistence)'
    $tsService = Get-Service -ErrorAction SilentlyContinue | Where-Object { $_.Name -like '*Tailscale*' -or $_.DisplayName -like '*Tailscale*' } | Select-Object -First 1
    if (-not $tsService) {
        Write-Step 'Could not find a Tailscale Windows Service - verify manually that Tailscale is set to start on boot (its installer normally registers one).'
    } else {
        Write-Step "Found service '$($tsService.Name)' (status: $($tsService.Status), start type: $($tsService.StartType))"
        if (-not $DryRun -and $tsService.StartType -ne 'Automatic') {
            Set-Service -Name $tsService.Name -StartupType Automatic
            $script:ServicesChanged++
            Write-Step "Set '$($tsService.Name)' to start automatically."
        } elseif ($DryRun -and $tsService.StartType -ne 'Automatic') {
            Write-Step "DRY RUN: would set '$($tsService.Name)' to start automatically."
        }
    }

    # ---- 9. verification ------------------------------------------------------------------------
    Step 'Verifying external HTTPS access'
    if ($DryRun) {
        Write-Step "DRY RUN: would GET https://$resolvedHostname/health, confirm /today-workout requires auth, and confirm an unlisted path/method is rejected"
    } else {
        $result = Test-RunningAiExternalAccess -Hostname $resolvedHostname -TimeoutSec 15
        Write-Step "ExternalEndpoint: $($result.HealthState) (HTTP $($result.HealthStatus))"
        Write-Step "Auth gate (no credential) -> $($result.AuthState)"
        Write-Step "Unsafe path -> $($result.UnsafeState)"

        if ($result.HealthState -ne 'UP') {
            Write-Host "ERROR: external health check did not return UP (got $($result.HealthState)). See above for the Funnel/relay states." -ForegroundColor Red
            exit $ExitCode.ExternalAccess
        }
    }

    Write-Host ''
    Write-Step "files changed: $script:FilesChanged"
    Write-Step "services changed: $script:ServicesChanged"
    Write-Step 'tasks changed: 0 (this script never creates/modifies a Scheduled Task)'
    Write-Step "Tailscale writes: $script:TailscaleWrites"
    Write-Host ''
    Write-Host 'Tailscale Funnel has NO equivalent of Cloudflare Access: the hostname above is a genuinely' -ForegroundColor Yellow
    Write-Host 'public HTTPS endpoint once enabled. /health is reachable by anyone who learns it;' -ForegroundColor Yellow
    Write-Host '/today-workout is gated ONLY by the relay''s own Bearer token - that token is now the sole' -ForegroundColor Yellow
    Write-Host 'auth boundary for this endpoint, not merely one layer among several.' -ForegroundColor Yellow
    Write-Host ''
    Write-Host 'The legacy Cloudflare quick tunnel is untouched by this script and is not stopped until a' -ForegroundColor Yellow
    Write-Host 'real Tailscale Funnel end-to-end run succeeds.' -ForegroundColor Yellow
    Write-Host ''
    Write-Host 'Phase 6I-1 is still NOT READY: this only proves the Tailscale Funnel E2E path locally.' -ForegroundColor Yellow
    Write-Host 'Real LTE/5G + Garmin 265 + PC reboot validation remain.' -ForegroundColor Yellow

    exit $ExitCode.Ok
} catch {
    Write-Host "ERROR: $($_.Exception.Message)" -ForegroundColor Red
    exit (Get-ExitCodeFromError $_)
}
