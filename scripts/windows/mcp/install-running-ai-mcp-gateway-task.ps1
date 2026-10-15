<#
.SYNOPSIS
  Registers the "RunningAI-McpGateway" Scheduled Task so the MCP gateway starts at logon and is
  re-started by its own repetition whenever it is not running.

.DESCRIPTION
  The ACTION is .runtime\mcp-gateway.cmd verbatim - the launcher production already uses, which
  holds the gateway's entire configuration (OAuth issuer and audience, allowed subject, the
  12-tool allowlist with publish_approved_draft deliberately absent). This script never writes,
  reads or re-expresses any of that: it only decides WHEN the launcher runs.

  What it corrects about the task that was registered by hand (Phase 9, A-2):

    * ExecutionTimeLimit was PT72H, so Windows would kill a healthy gateway every three days.
      A long-lived server process gets no time limit.
    * The only trigger was a fixed time in the past with no repetition, so after a reboot - or
      after any process death - the gateway simply stayed down until a human noticed. It is
      replaced by AtLogOn (+2 min) plus a logon-independent standing trigger, both repeating.

  The repetition is not a restart loop: MultipleInstances=IgnoreNew makes each tick a no-op while
  the gateway is alive. It is a second, independent safety net under the watchdog, which supervises
  this component too - either alone would do, and each covers a window the other does not (the
  watchdog is itself a Scheduled Task; the task engine is not).

  Idempotent. A task with this name whose action is NOT the gateway launcher is never overwritten.
  -DryRun prints the definition and registers nothing.
#>
[CmdletBinding()]
param([switch]$DryRun, [string]$TaskName = 'RunningAI-McpGateway')

. "$PSScriptRoot\..\RunningAI.Watchdog.ps1"

try {
    $launcher = Get-RunningAiMcpGatewayLauncherPath
    if (-not (Test-Path -LiteralPath $launcher)) {
        Stop-WithError $ExitCode.Other "The gateway launcher '$launcher' does not exist. It carries the gateway's OAuth and tool-allowlist configuration and is deliberately NOT generated here; create it before registering the task."
    }

    $parts = New-McpGatewayTaskParts
    $existing = Get-ScheduledTask -TaskName $TaskName -ErrorAction SilentlyContinue

    if ($DryRun) {
        Write-Host 'DRY RUN - nothing will be registered.'
        Write-Host "Task name        : $TaskName"
        Write-Host "Action           : $($parts.Execute)"
        Write-Host "Trigger 1        : AtLogOn ($($parts.User)), delay $($parts.Trigger.Delay), repeat every $($parts.Trigger.Repetition.Interval)"
        Write-Host "Trigger 2        : Once (backdated, logon-independent), repeat every $($parts.Triggers[1].Repetition.Interval)"
        Write-Host "Multiple runs    : $($parts.Settings.MultipleInstances) (a tick while the gateway is alive is a no-op)"
        Write-Host "Execution limit  : $($parts.Settings.ExecutionTimeLimit) (PT0S = none)"
        Write-Host "Gateway port     : $(Get-RunningAiMcpGatewayConfiguredPort) (read from the launcher, never chosen here)"
        Write-Host ("Existing task    : " + $(if ($existing) { 'yes (re-registered only if its action is this launcher)' } else { 'no' }))
        exit $ExitCode.Ok
    }

    if ($existing) {
        $targets = ($existing.Actions | ForEach-Object { "$($_.Execute) $($_.Arguments)" }) -join ' '
        if ($targets -notlike '*mcp-gateway*') {
            Stop-WithError $ExitCode.Other "A scheduled task named '$TaskName' already exists and does not run the MCP gateway launcher. Refusing to overwrite it; use -TaskName to choose another name."
        }
        Write-Step "Updating existing task '$TaskName'."
    }

    Register-ScheduledTask -TaskName $TaskName -Action $parts.Action -Trigger $parts.Triggers -Principal $parts.Principal `
        -Settings $parts.Settings -Description 'RunningAI MCP gateway: the loopback hop between the Cloudflare tunnel and Spring. Started at logon and kept alive by repetition.' -Force | Out-Null
    Write-Step "Scheduled task '$TaskName' registered (AtLogOn +2m and a logon-independent standing trigger, every 5 minutes, no execution time limit)."

    $info = Get-ScheduledTaskInfo -TaskName $TaskName -ErrorAction SilentlyContinue
    if ($info -and $info.NextRunTime) {
        Write-Step "Next run time: $($info.NextRunTime) - repetition is armed."
    } else {
        Write-Host "WARNING: '$TaskName' reports no NextRunTime; the repetition is not armed. State and LastTaskResult cannot detect this." -ForegroundColor Yellow
    }
    exit $ExitCode.Ok
} catch {
    Write-Host "ERROR: $($_.Exception.Message)" -ForegroundColor Red
    exit (Get-ExitCodeFromError $_)
}
