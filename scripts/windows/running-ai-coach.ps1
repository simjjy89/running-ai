<#
.SYNOPSIS
  Phase 6H-8 safe AI-coach operator workflow: generate or resume a draft, review it, revise it as
  many times as you like, and - only with two separate explicit human confirmations ("APPROVE" then
  "YES") - publish it to Intervals.icu through the existing controlled-publish gates.

.DESCRIPTION
  AI designs the draft. A human approves it. A human must type the final YES before any external
  write happens. This script automates none of that judgement: it has no parameter of any kind that
  skips typing APPROVE or YES, and none will be added (section 56 of the Phase 6H-8 work order).

  Generate and resume are mutually exclusive: pass -DraftId to resume an existing draft, or any of
  -Date/-AvailableMinutes/-Environment/-Goal/-GoalFile/-UserFeedback/-PainOrFatigueFeedback to
  generate a new one. Combining -DraftId with any generate-only parameter is refused.

  -NonInteractive only generates/resumes and displays the draft; it never approves or publishes
  (section 57). -StartIfNeeded starts the runtime via the existing start-running-ai.ps1 when it is
  not already healthy; without it, a down runtime is reported as a clear error with zero HTTP calls
  attempted beyond the health check.

.PARAMETER GoalFile
  A UTF-8 text file whose content becomes -Goal (useful for a multi-line goal). Its raw bytes are
  decoded as UTF-8 explicitly - this script never relies on PowerShell's own encoding guesswork.
  Mutually exclusive with -Goal.

.EXAMPLE
  .\scripts\windows\running-ai-coach.ps1 -Date 2026-10-04 -AvailableMinutes 35 -Environment OUTDOOR -Goal "easy taper run before the half marathon"

.EXAMPLE
  .\scripts\windows\running-ai-coach.ps1 -DraftId 10
#>
[CmdletBinding()]
param(
    [string]$Date,
    [int]$AvailableMinutes,
    [ValidateSet('OUTDOOR', 'TREADMILL', 'INDOOR')][string]$Environment,
    [string]$Goal,
    [string]$GoalFile,
    [string]$UserFeedback,
    [string]$PainOrFatigueFeedback,
    [long]$DraftId,
    [string]$BaseUrl = 'http://127.0.0.1:8080',
    [switch]$StartIfNeeded,
    [switch]$NonInteractive
)

. "$PSScriptRoot\RunningAI.Common.ps1"
. "$PSScriptRoot\RunningAI.CoachOperator.ps1"

$generateOnlyParams = 'Date', 'AvailableMinutes', 'Environment', 'Goal', 'GoalFile', 'UserFeedback', 'PainOrFatigueFeedback'
$generateParamsGiven = @($PSBoundParameters.Keys | Where-Object { $_ -in $generateOnlyParams })

if ($DraftId -and $generateParamsGiven.Count -gt 0) {
    Write-Host "ERROR: -DraftId resumes an existing draft and cannot be combined with $($generateParamsGiven -join ', ')."
    exit 2
}
if ($Goal -and $GoalFile) {
    Write-Host 'ERROR: pass only one of -Goal or -GoalFile.'
    exit 2
}

if ($GoalFile) {
    if (-not (Test-Path -LiteralPath $GoalFile)) {
        Write-Host "ERROR: -GoalFile not found: $GoalFile"
        exit 2
    }
    $bytes = [System.IO.File]::ReadAllBytes($GoalFile)
    if ($bytes.Length -ge 3 -and $bytes[0] -eq 0xEF -and $bytes[1] -eq 0xBB -and $bytes[2] -eq 0xBF) {
        $bytes = $bytes[3..($bytes.Length - 1)]
    }
    $Goal = [System.Text.Encoding]::UTF8.GetString($bytes)
}

if (-not (Test-RunningAiHealthy -BaseUrl $BaseUrl)) {
    if ($StartIfNeeded) {
        Write-Step 'RunningAI is not healthy; starting it (start-running-ai.ps1).'
        try {
            Start-RunningAiRuntimeIfNeeded -BaseUrl $BaseUrl
        } catch {
            Write-Host "ERROR: $($_.Exception.Message)"
            exit 1
        }
    } else {
        Write-Host "RunningAI is not reachable/healthy at $BaseUrl."
        Write-Host 'Start it first (scripts\windows\start-running-ai.ps1), or pass -StartIfNeeded, and retry.'
        exit 1
    }
}

$sessionArgs = @{
    BaseUrl               = $BaseUrl
    DraftId               = $(if ($DraftId) { $DraftId } else { $null })
    Date                  = $Date
    AvailableMinutes       = $(if ($PSBoundParameters.ContainsKey('AvailableMinutes')) { $AvailableMinutes } else { $null })
    Environment            = $Environment
    Goal                   = $Goal
    UserFeedback           = $UserFeedback
    PainOrFatigueFeedback  = $PainOrFatigueFeedback
    NonInteractive         = $NonInteractive
}

$result = Invoke-CoachOperatorSession @sessionArgs

switch ($result.Outcome) {
    'RUNTIME_NOT_UP' { exit 1 }
    'ERROR' { exit 1 }
    'PREVIEW_CHANGED_AFTER_RESTART' { exit 1 }
    'FAILED' { exit 1 }
    default { exit 0 }
}
