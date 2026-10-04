# Shared functions for the Phase 6H-8 safe AI-coach operator workflow. Dot-source, do not run.
#
#   . "$PSScriptRoot\RunningAI.Common.ps1"
#   . "$PSScriptRoot\RunningAI.CoachOperator.ps1"
#
# Every human-input point (console prompts) and every runtime-restart point is taken through a
# scriptblock parameter rather than called directly (Read-Host / stop+start-running-ai.ps1), so the
# whole workflow - including the irreversible-action gates - can be driven by a PowerShell test with
# a local HttpListener standing in for the RunningAI server, with no real console input and no real
# Docker/Java process ever started. The default values of those parameters are the real behaviour;
# running-ai-coach.ps1 and publish-approved-draft-controlled.ps1 call these functions with no
# overrides at all.
#
# Safety-critical runtime text (the final YES gate, cancellation/success messages) is kept in plain
# ASCII, same reasoning as Test-RunningAI.ps1: this is a no-BOM .ps1, and Windows PowerShell 5.1
# reads a no-BOM .ps1 using the system codepage rather than UTF-8, so a literal Korean string here
# would risk becoming mojibake at exactly the moment a human is reading it to decide whether to type
# YES. The athlete-facing natural-language fields (goal, revision request) are always handled
# through the 6H-7.2 UTF-8 JSON helpers and are never typed into this file's own source.

Set-StrictMode -Version Latest

# ---- runtime restart (real implementations; tests override these) -----------------------------

# Starts the runtime if it is not already healthy (used for -StartIfNeeded). Calls the existing
# start-running-ai.ps1 only - never starts Docker/Java/connector processes itself.
function Start-RunningAiRuntimeIfNeeded {
    param([Parameter(Mandatory)][string]$BaseUrl)
    & (Join-Path $PSScriptRoot 'start-running-ai.ps1')
    if ($LASTEXITCODE -ne 0) { throw "start-running-ai.ps1 failed with exit code $LASTEXITCODE" }
    if (-not (Wait-Until -TimeoutSec 60 -PollSec 2 -Test { Test-RunningAiHealthy -BaseUrl $BaseUrl })) {
        throw "RunningAI did not report healthy at $BaseUrl after starting."
    }
}

# Stops then starts the runtime (so a changed process-environment variable - the publishing
# switches - actually takes effect in the freshly started Spring process) and waits for health.
# Calls only the existing stop-running-ai.ps1 / start-running-ai.ps1 - never manages the Docker or
# Java process itself.
function Restart-RunningAiRuntimeForPublishing {
    param([Parameter(Mandatory)][string]$BaseUrl)
    & (Join-Path $PSScriptRoot 'stop-running-ai.ps1')
    & (Join-Path $PSScriptRoot 'start-running-ai.ps1')
    if ($LASTEXITCODE -ne 0) { throw "start-running-ai.ps1 failed with exit code $LASTEXITCODE" }
    if (-not (Wait-Until -TimeoutSec 120 -PollSec 2 -Test { Test-RunningAiHealthy -BaseUrl $BaseUrl })) {
        throw "RunningAI did not report healthy at $BaseUrl after restarting."
    }
}

function Test-RunningAiHealthy {
    param([Parameter(Mandatory)][string]$BaseUrl)
    try {
        $health = Invoke-RunningAiJsonRequest -Method GET -Uri "$BaseUrl/actuator/health" -TimeoutSec 5
        return $health -and $health.status -eq 'UP'
    } catch { return $false }
}

# ---- display -------------------------------------------------------------------------------

# One segment's device target as a single readable line, or $null for a step with no numeric
# target. An absolute-bpm target is shown, never hidden, exactly flagged as not publishable - the
# renderer only understands %LTHR, so a draft carrying one would fail closed at publish time.
function Format-RunningAiSegmentTarget {
    param([Parameter(Mandatory)]$Segment)

    if ($null -ne $Segment.paceSecondsPerKmFast -and $null -ne $Segment.paceSecondsPerKmSlow) {
        $fast = [TimeSpan]::FromSeconds($Segment.paceSecondsPerKmFast).ToString('m\:ss')
        $slow = [TimeSpan]::FromSeconds($Segment.paceSecondsPerKmSlow).ToString('m\:ss')
        return "Pace $fast-$slow/km"
    }
    if ($null -ne $Segment.heartRatePercentLthrMin -and $null -ne $Segment.heartRatePercentLthrMax) {
        return "HR $($Segment.heartRatePercentLthrMin)-$($Segment.heartRatePercentLthrMax)% LTHR"
    }
    if ($null -ne $Segment.heartRateBpmMin -or $null -ne $Segment.heartRateBpmMax) {
        return "ABSOLUTE BPM $($Segment.heartRateBpmMin)-$($Segment.heartRateBpmMax) -- NOT PUBLISHABLE"
    }
    if ($null -ne $Segment.treadmillSpeedKphMin -or $null -ne $Segment.treadmillSpeedKphMax) {
        $line = "Treadmill $($Segment.treadmillSpeedKphMin)-$($Segment.treadmillSpeedKphMax) km/h"
        if ($null -ne $Segment.inclinePercentMin -or $null -ne $Segment.inclinePercentMax) {
            $line += ", incline $($Segment.inclinePercentMin)-$($Segment.inclinePercentMax)%"
        }
        return $line
    }
    return $null
}

function Write-RunningAiSegment {
    param([Parameter(Mandatory)]$Segment, [string]$Indent = '  ')
    $target = Format-RunningAiSegmentTarget $Segment
    $line = "$Indent$($Segment.type) $($Segment.durationMinutes)m ($($Segment.intensity))"
    Write-Host $line
    if ($Segment.description) { Write-Host "$Indent  $($Segment.description)" }
    if ($target) { Write-Host "$Indent  $target" }
    if ($Segment.repetitions) {
        Write-Host "$Indent  x$($Segment.repetitions)"
        if ($Segment.recovery) {
            Write-RunningAiSegment -Segment $Segment.recovery -Indent "$Indent    "
        } elseif ($Segment.recoveryDurationMinutes) {
            Write-Host "$Indent    Recovery $($Segment.recoveryDurationMinutes)m"
        }
    }
}

# Human-readable view of a draft (generate/resume/revise response). Never dumps raw JSON.
function Show-RunningAiWorkoutDraft {
    param([Parameter(Mandatory)]$Draft)

    Write-Host ''
    Write-Host "Draft #$($Draft.id) / v$($Draft.version) / $($Draft.status)"
    Write-Host "Date     : $($Draft.date)"
    Write-Host "Title    : $($Draft.title)"
    Write-Host "Type     : $($Draft.workoutType)"
    Write-Host "Duration : $($Draft.totalDurationMinutes) min"
    Write-Host ''
    Write-Host 'Recovery:'
    Write-Host "  $($Draft.assessment.recoveryAssessment)"
    Write-Host ''
    Write-Host 'Load:'
    Write-Host "  $($Draft.assessment.loadAssessment)"
    Write-Host ''
    Write-Host 'Rationale:'
    Write-Host "  $($Draft.assessment.rationale)"

    if ($Draft.segments -and $Draft.segments.Count -gt 0) {
        Write-Host ''
        Write-Host 'Workout:'
        foreach ($segment in $Draft.segments) { Write-RunningAiSegment -Segment $segment }
    }

    if ($Draft.assessment.warnings -and $Draft.assessment.warnings.Count -gt 0) {
        Write-Host ''
        Write-Host 'Warnings:'
        foreach ($w in $Draft.assessment.warnings) { Write-Host "  - $w" }
    }
    Write-Host ''
}

function Show-RunningAiApproval {
    param([Parameter(Mandatory)]$Approval)
    Write-Host ''
    Write-Host "Draft #$($Approval.draftId) status=$($Approval.status) approvalId=$($Approval.approvalId)"
    Write-Host ''
}

function Show-RunningAiPublishPreview {
    param([Parameter(Mandatory)]$Preview)
    Write-Host ''
    Write-Host '--- Publish preview ---'
    Write-Host "publishable            : $($Preview.publishable)"
    Write-Host "externalWriteRequired   : $($Preview.externalWriteRequired)"
    Write-Host "expectedOutcome         : $($Preview.expectedOutcome)"
    Write-Host "structuredStepCount     : $($Preview.structuredStepCount)"
    if ($Preview.unpublishableReasons -and $Preview.unpublishableReasons.Count -gt 0) {
        Write-Host 'unpublishableReasons    :'
        foreach ($r in $Preview.unpublishableReasons) { Write-Host "  - $r" }
    }
    if ($Preview.publication) {
        Write-Host "publication             : outcome=$($Preview.publication.outcome) verified=$($Preview.publication.verified) publishedAt=$($Preview.publication.publishedAt)"
    }
    if ($Preview.renderedWorkoutText) {
        Write-Host ''
        Write-Host $Preview.renderedWorkoutText
    }
    Write-Host ''
}

# Friendly one-line text for the known RunningAI error codes (section 59); falls back to the raw
# code/message for anything not in this list. Never dumps a response body or a secret.
function Format-RunningAiErrorMessage {
    param([Parameter(Mandatory)]$ErrorDetails)

    $known = @{
        WORKOUT_DRAFT_NOT_FOUND             = 'That draft id does not exist.'
        WORKOUT_DRAFT_SUPERSEDED            = 'This draft version has been superseded by a newer revision.'
        WORKOUT_DRAFT_APPROVED_IMMUTABLE    = 'An approved draft cannot be revised.'
        WORKOUT_DATE_ALREADY_APPROVED       = 'Another draft for this date is already approved.'
        WORKOUT_DRAFT_APPROVAL_REQUIRED     = 'This draft must be APPROVED before it can be previewed or published.'
        DRAFT_PUBLISHING_DISABLED           = 'Draft publishing is currently disabled (RUNNING_AI_DRAFT_PUBLISHING_ENABLED=false).'
        DRAFT_PUBLISH_ALREADY_RUNNING       = 'A publish of this draft is already running; try again shortly.'
        UNPUBLISHABLE_DRAFT                 = 'The approved draft cannot be published without changing it; nothing was sent.'
    }
    $code = $ErrorDetails.Code
    if ($code -and $known.ContainsKey($code)) { return "$code - $($known[$code])" }
    if ($code -and $code.StartsWith('AI_COACH_')) { return "$code - $($ErrorDetails.Message)" }
    if ($code -and $code.StartsWith('INTERVALS_')) { return "$code - $($ErrorDetails.Message)" }
    if ($code) { return "$code - $($ErrorDetails.Message)" }
    return $ErrorDetails.Message
}

# ---- preview comparison (TOCTOU protection, sections 36-38) ------------------------------------

function Get-RunningAiPreviewHash {
    param($Preview)
    $text = if ($Preview.renderedWorkoutText) { $Preview.renderedWorkoutText } else { '' }
    $bytes = [System.Text.Encoding]::UTF8.GetBytes($text)
    $sha256 = [System.Security.Cryptography.SHA256]::Create()
    try {
        $hashBytes = $sha256.ComputeHash($bytes)
        return [System.BitConverter]::ToString($hashBytes).Replace('-', '').ToLowerInvariant()
    } finally { $sha256.Dispose() }
}

# True only when every field that must not have changed between the pre-restart and post-restart
# preview is identical. Compared explicitly (not just the hash) so a mismatch report can say what
# changed, in case a hash collision concern is ever raised. See section 37.
function Test-RunningAiPreviewUnchanged {
    param([Parameter(Mandatory)]$Before, [Parameter(Mandatory)]$After)
    ($Before.draftId -eq $After.draftId) -and
    ($Before.approvalId -eq $After.approvalId) -and
    ($Before.date -eq $After.date) -and
    ($Before.workoutType -eq $After.workoutType) -and
    ($Before.structuredStepCount -eq $After.structuredStepCount) -and
    ((Get-RunningAiPreviewHash $Before) -eq (Get-RunningAiPreviewHash $After))
}

# ---- controlled publish (sections 23-45) -------------------------------------------------------

# Publishes an already-APPROVED draft, with every gate from the work order: preview first (no
# switch touched yet); short-circuit for an already-unpublishable draft, an already-published draft,
# or a REST day (none of these ever enable the publish switch or restart the runtime); the final
# human "YES" gate; the publish-enabled restart; a second preview compared byte-for-byte against the
# first (PREVIEW_CHANGED_AFTER_RESTART aborts with zero external write); exactly one publish call;
# and a `finally` that unconditionally restores all four switches to false and restarts back to safe
# mode if the runtime was ever restarted with publishing enabled.
#
# Returns a result object: { Outcome; Detail }, Outcome one of:
#   NO_EXTERNAL_WRITE_NEEDED (already published / unpublishable / REST day - all fail-closed-or-done)
#   CANCELLED (human did not type YES)
#   PREVIEW_CHANGED_AFTER_RESTART (aborted; zero external write)
#   PUBLISHED (outcome=PUBLISHED and verified=true)
#   FAILED (publish request did not come back verified, or threw)
function Invoke-RunningAiControlledPublish {
    param(
        [Parameter(Mandatory)][string]$BaseUrl,
        [Parameter(Mandatory)][long]$DraftId,
        [scriptblock]$Reader = { param($Prompt) Read-Host $Prompt },
        [scriptblock]$RuntimeRestarter = { param($Url) Restart-RunningAiRuntimeForPublishing -BaseUrl $Url }
    )

    $result = [pscustomobject]@{ Outcome = 'NOT_ATTEMPTED'; Detail = $null }

    $preview = Invoke-RunningAiJsonRequest -Method GET -Uri "$BaseUrl/api/v1/workout-drafts/$DraftId/publish-preview"
    Show-RunningAiPublishPreview $preview

    if ($preview.publication) {
        $result.Outcome = 'NO_EXTERNAL_WRITE_NEEDED'
        $result.Detail = 'already published; no new external write performed'
        Write-Host $result.Detail
        return $result
    }
    if (-not $preview.publishable) {
        $result.Outcome = 'NO_EXTERNAL_WRITE_NEEDED'
        $result.Detail = 'Draft approved but cannot be published losslessly. No external write performed.'
        Write-Host $result.Detail
        return $result
    }
    if ($preview.expectedOutcome -eq 'SKIPPED_REST_DAY') {
        $result.Outcome = 'NO_EXTERNAL_WRITE_NEEDED'
        $result.Detail = 'Approved REST day. No external workout is required.'
        Write-Host $result.Detail
        return $result
    }

    Write-Host 'This will write the approved workout to Intervals.icu.'
    $answer = & $Reader 'Type exactly YES to publish'
    if ($answer -cne 'YES') {
        $result.Outcome = 'CANCELLED'
        $result.Detail = 'Publishing cancelled.'
        Write-Host $result.Detail
        return $result
    }

    $env:RUNNING_AI_DRAFT_PUBLISHING_ENABLED = 'true'
    $env:WORKOUT_PUBLISHING_ENABLED = 'false'
    $env:WORKOUT_PUBLISHING_SCHEDULER_ENABLED = 'false'
    $env:RUNNINGAI_MCP_ENABLED = 'false'
    $restarted = $false

    try {
        & $RuntimeRestarter $BaseUrl
        $restarted = $true

        $preview2 = Invoke-RunningAiJsonRequest -Method GET -Uri "$BaseUrl/api/v1/workout-drafts/$DraftId/publish-preview"
        if (-not (Test-RunningAiPreviewUnchanged -Before $preview -After $preview2)) {
            $result.Outcome = 'PREVIEW_CHANGED_AFTER_RESTART'
            $result.Detail = 'PREVIEW_CHANGED_AFTER_RESTART: the draft changed between the first preview and the restart. Publish aborted; no external write performed.'
            Write-Host $result.Detail
            return $result
        }

        $published = Invoke-RunningAiJsonRequest -Method POST -Uri "$BaseUrl/api/v1/workout-drafts/$DraftId/publish"
        $result.Detail = $published
        if ($published.outcome -eq 'PUBLISHED' -and $published.verified -eq $true) {
            $result.Outcome = 'PUBLISHED'
            Write-Host 'PUBLISH REQUEST COMPLETED'
            Write-Host 'VERIFIED SUCCESS'
            Write-Host "Operation : $($published.intervalsOperation)"
            Write-Host "Steps     : $($published.structuredStepCount)"
        } else {
            $result.Outcome = 'FAILED'
            Write-Host 'PUBLISH REQUEST COMPLETED'
            Write-Host "NOT VERIFIED (verified=$($published.verified))"
        }
        return $result
    } catch {
        $result.Outcome = 'FAILED'
        $result.Detail = Get-RunningAiErrorDetails $_
        Write-Host "ERROR: $(Format-RunningAiErrorMessage $result.Detail)"
        return $result
    } finally {
        $env:RUNNING_AI_DRAFT_PUBLISHING_ENABLED = 'false'
        $env:WORKOUT_PUBLISHING_ENABLED = 'false'
        $env:WORKOUT_PUBLISHING_SCHEDULER_ENABLED = 'false'
        $env:RUNNINGAI_MCP_ENABLED = 'false'
        if ($restarted) {
            try {
                & $RuntimeRestarter $BaseUrl
                Write-Host 'RunningAI safe-mode restart: OK'
            } catch {
                Write-Warning "Safe-mode restart failed: $($_.Exception.Message)"
            }
        }
    }
}

# ---- main interactive session (sections 10-22) -------------------------------------------------

# One full operator session: generate-or-resume, display, revision loop, approve gate, then (only
# on a fresh or resumed approval) the controlled-publish flow above. Never automates coaching
# judgement (section 55) and has no parameter that bypasses a human gate (section 56): every
# -Reader default is a real interactive Read-Host, and the only way past "type APPROVE"/"type YES"
# is typing the exact matching word.
#
# Returns a result object: { Outcome; Draft; Publish } where Outcome is one of RUNTIME_NOT_UP,
# SUPERSEDED, DISPLAYED (NonInteractive), QUIT, or whatever Invoke-RunningAiControlledPublish
# returned after an approval.
function Invoke-CoachOperatorSession {
    param(
        [Parameter(Mandatory)][string]$BaseUrl,
        [Nullable[long]]$DraftId,
        [string]$Date,
        [Nullable[int]]$AvailableMinutes,
        [string]$Environment,
        [string]$Goal,
        [string]$UserFeedback,
        [string]$PainOrFatigueFeedback,
        [switch]$NonInteractive,
        [scriptblock]$Reader = { param($Prompt) Read-Host $Prompt },
        [scriptblock]$RuntimeRestarter = { param($Url) Restart-RunningAiRuntimeForPublishing -BaseUrl $Url }
    )

    if (-not (Test-RunningAiHealthy -BaseUrl $BaseUrl)) {
        Write-Host "RunningAI is not reachable/healthy at $BaseUrl. Start it first (start-running-ai.ps1, or pass -StartIfNeeded) and retry."
        return [pscustomobject]@{ Outcome = 'RUNTIME_NOT_UP'; Draft = $null; Publish = $null }
    }

    try {
        if ($DraftId) {
            $draft = Invoke-RunningAiJsonRequest -Method GET -Uri "$BaseUrl/api/v1/workout-drafts/$DraftId"
        } else {
            $body = [ordered]@{}
            if ($Date) { $body.date = $Date }
            if ($null -ne $AvailableMinutes) { $body.availableMinutes = $AvailableMinutes }
            if ($Environment) { $body.environment = $Environment }
            if ($Goal) { $body.requestedGoal = $Goal }
            if ($UserFeedback) { $body.userFeedback = $UserFeedback }
            if ($PainOrFatigueFeedback) { $body.painOrFatigueFeedback = $PainOrFatigueFeedback }
            $draft = Invoke-RunningAiJsonRequest -Method POST -Uri "$BaseUrl/api/v1/workout-drafts" -Body $body
        }
    } catch {
        $details = Get-RunningAiErrorDetails $_
        Write-Host "ERROR: $(Format-RunningAiErrorMessage $details)"
        return [pscustomobject]@{ Outcome = 'ERROR'; Draft = $null; Publish = $null }
    }

    while ($true) {
        Show-RunningAiWorkoutDraft $draft

        if ($draft.status -eq 'SUPERSEDED') {
            Write-Host 'This draft version is SUPERSEDED. Nothing to do here.'
            return [pscustomobject]@{ Outcome = 'SUPERSEDED'; Draft = $draft; Publish = $null }
        }

        if ($NonInteractive) {
            return [pscustomobject]@{ Outcome = 'DISPLAYED'; Draft = $draft; Publish = $null }
        }

        if ($draft.status -eq 'APPROVED') {
            # Resuming an already-approved draft: the human already made the approval decision in an
            # earlier session. Approve is idempotent (returns the existing approval unchanged), so
            # calling it again is read-only in effect and just gets us a fresh approvalId/approvedAt
            # to display - it is not a new human decision, so it does not need a fresh APPROVE gate.
            try {
                $approval = Invoke-RunningAiJsonRequest -Method POST -Uri "$BaseUrl/api/v1/workout-drafts/$($draft.id)/approve"
            } catch {
                $details = Get-RunningAiErrorDetails $_
                Write-Host "ERROR: $(Format-RunningAiErrorMessage $details)"
                return [pscustomobject]@{ Outcome = 'ERROR'; Draft = $draft; Publish = $null }
            }
            Show-RunningAiApproval $approval
            $publishResult = Invoke-RunningAiControlledPublish -BaseUrl $BaseUrl -DraftId $draft.id -Reader $Reader -RuntimeRestarter $RuntimeRestarter
            return [pscustomobject]@{ Outcome = $publishResult.Outcome; Draft = $draft; Publish = $publishResult }
        }

        # DRAFT: the only state with a menu.
        Write-Host '[R] Revise   [A] Approve   [Q] Quit'
        $choice = (& $Reader 'Choice')
        if ($null -ne $choice) { $choice = $choice.Trim() }

        switch ($choice) {
            'R' {
                $request = & $Reader 'Revision request'
                if ([string]::IsNullOrWhiteSpace($request)) {
                    Write-Host 'Empty revision request; nothing sent.'
                    break
                }
                try {
                    $draft = Invoke-RunningAiJsonRequest -Method POST -Uri "$BaseUrl/api/v1/workout-drafts/$($draft.id)/revisions" -Body @{ request = $request }
                } catch {
                    $details = Get-RunningAiErrorDetails $_
                    Write-Host "ERROR: $(Format-RunningAiErrorMessage $details)"
                }
                break
            }
            'A' {
                $confirm = & $Reader "Type APPROVE to approve Draft #$($draft.id)"
                if ($confirm -cne 'APPROVE') {
                    Write-Host 'Approval cancelled.'
                    break
                }
                try {
                    $approval = Invoke-RunningAiJsonRequest -Method POST -Uri "$BaseUrl/api/v1/workout-drafts/$($draft.id)/approve"
                } catch {
                    $details = Get-RunningAiErrorDetails $_
                    Write-Host "ERROR: $(Format-RunningAiErrorMessage $details)"
                    return [pscustomobject]@{ Outcome = 'ERROR'; Draft = $draft; Publish = $null }
                }
                Show-RunningAiApproval $approval
                $publishResult = Invoke-RunningAiControlledPublish -BaseUrl $BaseUrl -DraftId $draft.id -Reader $Reader -RuntimeRestarter $RuntimeRestarter
                return [pscustomobject]@{ Outcome = $publishResult.Outcome; Draft = $draft; Publish = $publishResult }
            }
            'Q' {
                return [pscustomobject]@{ Outcome = 'QUIT'; Draft = $draft; Publish = $null }
            }
            default {
                Write-Host "Unrecognized choice: $choice"
                break
            }
        }
    }
}
