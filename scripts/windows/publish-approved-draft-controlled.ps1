<#
.SYNOPSIS
  Controlled publish of an already-APPROVED AI-coach workout draft: preview, the final human "YES"
  gate, the publish-enabled runtime restart, a second preview re-verified against the first,
  exactly one publish call, read-back verification, and an unconditional safe-mode restart
  afterward (Phase 6H-8).

.DESCRIPTION
  Thin wrapper over Invoke-RunningAiControlledPublish (RunningAI.CoachOperator.ps1) - the same
  function scripts\windows\running-ai-coach.ps1 calls right after an approval. Kept as its own
  script so an operator who already approved a draft elsewhere (or via the API directly) can run
  just the publish step by draft id, same invocation shape as the earlier untracked version of this
  script.

  The draft must already be APPROVED (GET /publish-preview / POST /publish both refuse otherwise).
  This script never calls the approve endpoint itself.

.PARAMETER DraftId
  The approved draft's id.

.PARAMETER BaseUrl
  Overrides the default local Spring base URL.

.EXAMPLE
  .\scripts\windows\publish-approved-draft-controlled.ps1 -DraftId 12
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory)][long]$DraftId,
    [string]$BaseUrl = 'http://127.0.0.1:8080'
)

. "$PSScriptRoot\RunningAI.Common.ps1"
. "$PSScriptRoot\RunningAI.CoachOperator.ps1"

if (-not $PSBoundParameters.ContainsKey('BaseUrl')) { $BaseUrl = Get-RunningAiDefaultSpringBaseUrl }

if (-not (Test-RunningAiHealthy -BaseUrl $BaseUrl)) {
    Write-Host "RunningAI is not reachable/healthy at $BaseUrl. Start it first (start-running-ai.ps1) and retry."
    exit 1
}

$result = Invoke-RunningAiControlledPublish -BaseUrl $BaseUrl -DraftId $DraftId

switch ($result.Outcome) {
    'PUBLISHED' { exit 0 }
    'NO_EXTERNAL_WRITE_NEEDED' { exit 0 }
    'CANCELLED' { exit 0 }
    default { exit 1 }
}
