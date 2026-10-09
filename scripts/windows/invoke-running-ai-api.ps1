<#
.SYNOPSIS
  Calls a RunningAI HTTP API with a JSON body sent as explicit UTF-8 bytes (Phase 6H-7.2), so
  natural-language fields (Korean included) survive intact regardless of console encoding.

.DESCRIPTION
  A thin wrapper over Invoke-RunningAiJsonRequest (RunningAI.Common.ps1): never hands a raw
  .NET string to Invoke-RestMethod's -Body. Prints no secret: this script never loads .env into
  its process environment or reads an API key or a Garmin/Intervals credential from it - the only
  thing it ever reads from .env is the single SERVER_PORT key (file content only, via
  Get-RunningAiEnvFileValue, never Set-Item), to pick -BaseUrl's default when not explicitly
  passed. The caller is responsible for not passing a secret in -Body.

.PARAMETER Method
  HTTP method: GET, POST, PUT or DELETE.

.PARAMETER Path
  The request path, e.g. "/api/v1/workout-drafts". Joined with -BaseUrl.

.PARAMETER Body
  Optional request body as a PowerShell hashtable/object; serialized to UTF-8 JSON. Omit for a
  bodyless GET/DELETE.

.PARAMETER BaseUrl
  Overrides the default local Spring base URL.

.EXAMPLE
  .\scripts\windows\invoke-running-ai-api.ps1 -Method POST -Path "/api/v1/workout-drafts" `
      -Body @{ date = "2026-10-02"; requestedGoal = "easy training, half marathon in a week" }

.EXAMPLE
  .\scripts\windows\invoke-running-ai-api.ps1 -Method GET -Path "/api/v1/workout-drafts/42"
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory)][ValidateSet('GET', 'POST', 'PUT', 'DELETE')][string]$Method,
    [Parameter(Mandatory)][string]$Path,
    $Body = $null,
    [string]$BaseUrl = 'http://127.0.0.1:8080',
    [int]$TimeoutSec = 30
)

. "$PSScriptRoot\RunningAI.Common.ps1"

if (-not $PSBoundParameters.ContainsKey('BaseUrl')) { $BaseUrl = Get-RunningAiDefaultSpringBaseUrl }

$uri = $BaseUrl.TrimEnd('/') + '/' + $Path.TrimStart('/')
Invoke-RunningAiJsonRequest -Method $Method -Uri $uri -Body $Body -TimeoutSec $TimeoutSec
