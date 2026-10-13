<#
.SYNOPSIS
  READ-ONLY pre-publish check of one date on the real Intervals.icu calendar (Phase 7F-2B).

.DESCRIPTION
  Answers, immediately before an approved-draft publish, the one question the publisher will ask
  itself a moment later: what does that date already hold, and would RunningAI own it?

  It issues exactly ONE request - the same GET the publisher issues first:
      GET /api/v1/athlete/{athleteId}/events?oldest=<date>&newest=<date>&category=WORKOUT
  and then classifies every returned event the way IntervalsWorkoutPublisher does:
      marker match (external_id = runningai:workout:v1:<athleteId>:<date>)  -> OWNED
      no external_id but '[RunningAI-Control]' in the description           -> OWNED (legacy takeover)
      anything else                                                        -> FOREIGN

  It predicts the operation the publish would perform. It is a prediction, not a promise: the
  publisher re-lists the date itself and decides from what it sees then, which is the authority.

  This script performs NO write of any kind. It has no create, update or delete path, and it is
  deliberately separate from the application so a pre-flight can be run without the publish switch
  being on anywhere.

  The API key is read from the canonical .env into memory only. It is never printed, never written
  to disk and never logged; the Intervals athlete id is printed masked.

.PARAMETER Date
  The calendar date to inspect, yyyy-MM-dd.

.PARAMETER AthleteId
  The Intervals athlete id as RunningAI is configured to use it. MUST stay '0' - that is the value
  baked into every marker already on the calendar, and changing it would orphan all of them
  (Phase 7F-2A risk R1). Exposed only so the script states the assumption it is making.

.EXAMPLE
  scripts\dev\preflight-intervals-date.ps1 -Date 2026-10-15
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory)][ValidatePattern('^\d{4}-\d{2}-\d{2}$')][string]$Date,
    [ValidateSet('0')][string]$AthleteId = '0',
    [string]$CanonicalEnv = 'C:\running-ai-github\.env'
)

$ErrorActionPreference = 'Stop'

if (-not (Test-Path -LiteralPath $CanonicalEnv)) { throw "canonical .env not found at $CanonicalEnv" }
$key = $null
foreach ($line in Get-Content -LiteralPath $CanonicalEnv) {
    if ($line -match '^\s*INTERVALS_API_KEY\s*=\s*(.+?)\s*$') { $key = $Matches[1].Trim('"').Trim("'") }
}
if (-not $key) { throw "INTERVALS_API_KEY not present in $CanonicalEnv" }

$headers = @{
    Authorization = 'Basic ' + [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes("API_KEY:$key"))
    'User-Agent'  = 'RunningAI-preflight'
}
$key = $null      # not needed again

$marker = "runningai:workout:v1:${AthleteId}:${Date}"
$uri    = "https://intervals.icu/api/v1/athlete/$AthleteId/events?oldest=$Date&newest=$Date&category=WORKOUT"

Write-Output "[preflight] date   : $Date"
Write-Output "[preflight] marker : $marker"

try {
    $identity = Invoke-RestMethod -Uri "https://intervals.icu/api/v1/athlete/$AthleteId" -Headers $headers -TimeoutSec 20
    $id = [string]$identity.id
    $masked = if ($id.Length -ge 4) { "$($id.Substring(0,2))***$($id.Substring($id.Length-2))" } else { '***' }
    Write-Output "[preflight] account: $masked (tz $($identity.timezone)) - resolved from the API key, athlete-id=$AthleteId"
} catch {
    Write-Output "[preflight] account: could not be read ($($_.Exception.Message))"
}

# An empty JSON array comes back from Invoke-RestMethod as $null, and @($null) is a one-element
# array holding nothing - which would be miscounted as "one event on the date". Drop empties.
$events = @(Invoke-RestMethod -Uri $uri -Headers $headers -TimeoutSec 20) |
          Where-Object { $null -ne $_ -and $null -ne $_.id }
$events = @($events)

$owned   = @()
$foreign = @()
foreach ($e in $events) {
    # Defensive, exactly as the publisher is: only the requested day counts.
    if (([string]$e.start_date_local) -notlike "$Date*") { continue }
    $ext = [string]$e.external_id
    $isLegacy = [string]::IsNullOrWhiteSpace($ext) -and ([string]$e.description).Contains('[RunningAI-Control]')
    if ($ext -eq $marker -or $isLegacy) { $owned += $e } else { $foreign += $e }
}

Write-Output "[preflight] WORKOUT events on $Date : $($events.Count)  (owned=$($owned.Count) foreign=$($foreign.Count))"
foreach ($e in $events) {
    $ext = [string]$e.external_id
    $kind = if (([string]$e.start_date_local) -notlike "$Date*") { 'OTHER-DATE' }
            elseif ($ext -eq $marker) { 'OWNED(marker)' }
            elseif ([string]::IsNullOrWhiteSpace($ext) -and ([string]$e.description).Contains('[RunningAI-Control]')) { 'OWNED(legacy)' }
            else { 'FOREIGN' }
    Write-Output ("            {0,-14} start={1} id={2} type={3} name='{4}' external_id='{5}'" -f $kind, $e.start_date_local, $e.id, $e.type, $e.name, $ext)
}

$prediction = if ($owned.Count -gt 1)        { 'DUPLICATE_OWNED_WORKOUT - the publish would ABORT without touching anything' }
              elseif ($owned.Count -eq 1)    { 'NO_CHANGE or UPDATED - an owned event exists and would be compared, then overwritten in place if it differs' }
              elseif ($foreign.Count -gt 0)  { 'UNMANAGED_WORKOUT_CONFLICT - the publish would ABORT; the foreign event is never modified' }
              else                           { 'CREATED - the date is empty, one new event would be POSTed' }

Write-Output "[preflight] predicted publish operation: $prediction"
Write-Output "[preflight] no write was performed by this script."

# Exit code says whether the date itself is clear for a first-time CREATE: 0 = empty, 1 = something is on it.
if (($owned.Count + $foreign.Count) -eq 0) { exit 0 } else { exit 1 }
