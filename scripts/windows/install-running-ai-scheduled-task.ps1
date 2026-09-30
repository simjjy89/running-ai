<#
.SYNOPSIS
  Registers the "RunningAI-Startup" Scheduled Task: at logon of the current user, run
  scripts\windows\start-running-ai.ps1 (hidden window, working directory = repository root).

.DESCRIPTION
  Idempotent: an existing RunningAI-Startup task that points at a RunningAI start script is
  re-registered in place. A task with that name that does NOT point at start-running-ai.ps1 (for
  example a legacy task) is never overwritten. Runs as the current user, interactive, no stored
  password, no elevation.

.PARAMETER DryRun
  Print the task definition that would be registered and change nothing.
#>
[CmdletBinding()]
param(
    [switch]$DryRun,
    [string]$TaskName = 'RunningAI-Startup'
)

. "$PSScriptRoot\RunningAI.Common.ps1"

$root = Get-RepoRoot
$startScript = Join-Path $PSScriptRoot 'start-running-ai.ps1'
$psExe = (Get-Command powershell.exe).Source
$arguments = "-NoProfile -ExecutionPolicy Bypass -WindowStyle Hidden -File $(Quote-Argument $startScript)"
$user = [System.Security.Principal.WindowsIdentity]::GetCurrent().Name

if ($DryRun) {
    Write-Host "DRY RUN - nothing will be registered."
    Write-Host "Task name        : $TaskName"
    Write-Host "Trigger          : AtLogOn ($user)"
    Write-Host "Run as           : $user (interactive, limited, no stored password)"
    Write-Host "Action           : $psExe $arguments"
    Write-Host "Working directory: $root"
    $existing = Get-ScheduledTask -TaskName $TaskName -ErrorAction SilentlyContinue
    Write-Host ("Existing task    : " + $(if ($existing) { 'yes (would be re-registered if it targets start-running-ai.ps1)' } else { 'no' }))
    exit $ExitCode.Ok
}

try {
    $existing = Get-ScheduledTask -TaskName $TaskName -ErrorAction SilentlyContinue
    if ($existing) {
        $targets = ($existing.Actions | ForEach-Object { "$($_.Execute) $($_.Arguments)" }) -join ' '
        if ($targets -notlike '*start-running-ai.ps1*') {
            Stop-WithError $ExitCode.Other "A scheduled task named '$TaskName' already exists and is not a RunningAI startup task. Refusing to overwrite it; use -TaskName to choose another name."
        }
        Write-Step "Updating existing task '$TaskName'."
    }

    $action = New-ScheduledTaskAction -Execute $psExe -Argument $arguments -WorkingDirectory $root
    $trigger = New-ScheduledTaskTrigger -AtLogOn -User $user
    $principal = New-ScheduledTaskPrincipal -UserId $user -LogonType Interactive -RunLevel Limited
    $settings = New-ScheduledTaskSettingsSet -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries `
        -ExecutionTimeLimit (New-TimeSpan -Hours 1) -MultipleInstances IgnoreNew -StartWhenAvailable
    Register-ScheduledTask -TaskName $TaskName -Action $action -Trigger $trigger -Principal $principal `
        -Settings $settings -Description 'Starts the RunningAI runtime (Docker/PostgreSQL, Garmin connector, Spring Boot) at logon.' `
        -Force | Out-Null
    Write-Step "Scheduled task '$TaskName' registered (AtLogOn, $user)."
    exit $ExitCode.Ok
} catch {
    Write-Host "ERROR: $($_.Exception.Message)" -ForegroundColor Red
    exit (Get-ExitCodeFromError $_)
}
