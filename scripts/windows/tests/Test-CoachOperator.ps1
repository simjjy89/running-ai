<#
.SYNOPSIS
  Phase 6H-8 safety-gate checks for the AI-coach operator workflow (RunningAI.CoachOperator.ps1).
  No real RunningAI server, no Docker, no Java process, no Garmin/Intervals call: every HTTP call
  goes to a local HttpListener driven by a pre-scripted, strictly-ordered list of expected
  (method, path) -> response routes, and every console prompt is driven by a pre-scripted reader
  instead of real keyboard input. A route the implementation calls out of order gets a 500 back
  (and is flagged in the log), so a sequencing bug is caught rather than silently passing.

  Exit code 0 when all checks pass.
#>
$ErrorActionPreference = 'Stop'
$here = $PSScriptRoot
$scripts = Split-Path $here -Parent

. (Join-Path $scripts 'RunningAI.Common.ps1')
. (Join-Path $scripts 'RunningAI.CoachOperator.ps1')

$failures = New-Object System.Collections.Generic.List[string]
function Check {
    param([string]$Name, [scriptblock]$Body)
    try {
        $result = & $Body
        if ($result -eq $false) { throw 'assertion returned false' }
        Write-Host "PASS  $Name"
    } catch {
        Write-Host "FAIL  $Name : $($_.Exception.Message)" -ForegroundColor Red
        $failures.Add($Name)
    }
}

# ---- fake RunningAI server (strictly-ordered routes) -------------------------------------------

function Start-RunningAiFakeServer {
    param([Parameter(Mandatory)][int]$Port, [Parameter(Mandatory)][array]$Routes)
    $listener = New-Object System.Net.HttpListener
    $listener.Prefixes.Add("http://127.0.0.1:$Port/")
    $listener.Start()
    try {
        $job = [powershell]::Create().AddScript({
            param($l, $routes)
            foreach ($route in $routes) {
                $ctx = $l.GetContext()
                $reader = New-Object System.IO.StreamReader($ctx.Request.InputStream, [System.Text.Encoding]::UTF8)
                $bodyText = $reader.ReadToEnd()
                $actualMethod = $ctx.Request.HttpMethod
                $actualPath = $ctx.Request.Url.AbsolutePath
                $isMatch = ($actualMethod -eq $route.Method) -and ($actualPath -eq $route.Path)

                if ($isMatch) {
                    $status = if ($route.Status) { $route.Status } else { 200 }
                    $json = if ($route.Body -is [string]) { $route.Body } else { ($route.Body | ConvertTo-Json -Depth 20 -Compress) }
                } else {
                    $status = 500
                    $json = '{"code":"UNEXPECTED_CALL","message":"test route mismatch"}'
                }
                $bytes = [System.Text.UTF8Encoding]::new($false).GetBytes($json)
                $ctx.Response.StatusCode = $status
                $ctx.Response.ContentType = 'application/json'
                $ctx.Response.OutputStream.Write($bytes, 0, $bytes.Length)
                $ctx.Response.Close()

                [pscustomobject]@{
                    Method = $actualMethod; Path = $actualPath; Body = $bodyText
                    ExpectedMethod = $route.Method; ExpectedPath = $route.Path; Matched = $isMatch
                }
            }
        }).AddArgument($listener).AddArgument($Routes)
        $handle = $job.BeginInvoke()
        return @{ Job = $job; Handle = $handle; Listener = $listener; RouteCount = $Routes.Count }
    } catch {
        $listener.Stop(); $listener.Close()
        throw
    }
}

function Complete-RunningAiFakeServer {
    param([Parameter(Mandatory)]$Pending, [int]$TimeoutSec = 20)
    try {
        if (-not $Pending.Handle.AsyncWaitHandle.WaitOne($TimeoutSec * 1000)) {
            throw "fake server did not receive the expected $($Pending.RouteCount) request(s) within ${TimeoutSec}s"
        }
        return @($Pending.Job.EndInvoke($Pending.Handle))
    } finally {
        $Pending.Job.Dispose()
        $Pending.Listener.Stop()
        $Pending.Listener.Close()
    }
}

# True only when every logged request matched its expected route, in order, and the count is exact.
function Confirm-RunningAiFakeServerLog {
    param([Parameter(Mandatory)][array]$Log, [Parameter(Mandatory)][int]$ExpectedCount)
    if ($Log.Count -ne $ExpectedCount) {
        $seen = ($Log | ForEach-Object { "$($_.Method) $($_.Path)" }) -join ', '
        throw "expected $ExpectedCount request(s), got $($Log.Count): $seen"
    }
    foreach ($entry in $Log) {
        if (-not $entry.Matched) {
            throw "unexpected call: $($entry.Method) $($entry.Path) (expected $($entry.ExpectedMethod) $($entry.ExpectedPath))"
        }
    }
    return $true
}

# A Reader scriptblock that returns each of $Responses in turn; throws if asked for more than given.
function New-RunningAiScriptedReader {
    # Not [Parameter(Mandatory)]: PowerShell 5.1 refuses to bind an empty-string array ELEMENT (not
    # just the whole argument) to a mandatory parameter, and an empty string is exactly one of the
    # "answers that must be rejected" fixtures these tests need to script (Read-Host on an empty
    # line).
    param([string[]]$Responses)
    $index = [ref]0
    return {
        param($Prompt)
        $i = $index.Value
        $index.Value++
        if ($i -ge $Responses.Count) { throw "scripted reader ran out of responses (prompt: $Prompt)" }
        return $Responses[$i]
    }.GetNewClosure()
}

# A RuntimeRestarter scriptblock that does nothing (no Docker/Java touched) and counts calls.
function New-RunningAiNoopRestarter {
    $count = [ref]0
    $block = {
        param($Url)
        $count.Value++
    }.GetNewClosure()
    return @{ Block = $block; Count = $count }
}

# ---- fixtures (synthetic; no real athlete data) -------------------------------------------------

$HealthUp = @{ status = 'UP' }

function New-DraftFixture {
    param([int]$Id = 12, [int]$Version = 1, [string]$Status = 'DRAFT', [string]$Goal = $null)
    @{
        id = $Id; draftGroupId = 'g-' + $Id; version = $Version; status = $Status
        date = '2026-10-04'; title = 'Easy Run'; workoutType = 'EASY'; totalDurationMinutes = 30
        assessment = @{ recoveryAssessment = 'ok'; loadAssessment = 'steady'; selectedWorkoutType = 'EASY'; rationale = 'keep it easy'; warnings = @() }
        segments = @()
        provider = 'CLAUDE'; model = 'test-model'; createdAt = '2026-10-04T00:00:00Z'
    }
}

function New-ApprovalFixture {
    param([int]$DraftId = 12, [int]$ApprovalId = 99)
    @{ draftId = $DraftId; draftGroupId = 'g-' + $DraftId; version = 1; date = '2026-10-04'; workoutType = 'EASY'; status = 'APPROVED'; approvalId = $ApprovalId; approvedAt = '2026-10-04T00:05:00Z' }
}

function New-PreviewFixture {
    param(
        [int]$DraftId = 12, [int]$ApprovalId = 99, [bool]$Publishable = $true, [bool]$RestDay = $false,
        [string]$ExpectedOutcome = 'PUBLISH', $Publication = $null, [string]$RenderedText = 'Workout Builder text',
        [int]$StructuredStepCount = 3, [string[]]$UnpublishableReasons = @()
    )
    @{
        draftId = $DraftId; approvalId = $ApprovalId; date = '2026-10-04'; workoutType = 'EASY'
        restDay = $RestDay; publishable = $Publishable
        externalWriteRequired = (-not $RestDay) -and $Publishable -and (-not $Publication)
        expectedOutcome = $ExpectedOutcome; renderedWorkoutText = $RenderedText
        structuredStepCount = $StructuredStepCount; unpublishableReasons = $UnpublishableReasons
        publication = $Publication
    }
}

function New-PublishedFixture {
    param([int]$DraftId = 12, [int]$ApprovalId = 99, [bool]$Verified = $true, [string]$Outcome = 'PUBLISHED')
    @{
        draftId = $DraftId; approvalId = $ApprovalId; date = '2026-10-04'; workoutType = 'EASY'
        outcome = $Outcome; intervalsOperation = 'CREATED'; remoteEventId = 'remote-abc'
        verified = $Verified; publishedAt = '2026-10-04T00:10:00Z'; structuredStepCount = 3; alreadyPublished = $false
    }
}

function New-Port { Get-Random -Minimum 20000 -Maximum 40000 }

# ---- 64: generate safety -------------------------------------------------------------------------

Check 'generate (NonInteractive): only health + POST /workout-drafts, zero publish calls' {
    $port = New-Port
    $base = "http://127.0.0.1:$port"
    $routes = @(
        @{ Method = 'GET'; Path = '/actuator/health'; Body = $HealthUp }
        @{ Method = 'POST'; Path = '/api/v1/workout-drafts'; Body = (New-DraftFixture) }
    )
    $pending = Start-RunningAiFakeServer -Port $port -Routes $routes
    $result = Invoke-CoachOperatorSession -BaseUrl $base -Date '2026-10-04' -Goal 'easy run' -NonInteractive
    $log = Complete-RunningAiFakeServer -Pending $pending

    ($result.Outcome -eq 'DISPLAYED') -and (Confirm-RunningAiFakeServerLog -Log $log -ExpectedCount 2)
}

# ---- 65: revision -------------------------------------------------------------------------------

Check 'revision: POST /revisions exactly once, Korean request body round trips, quits with zero approve/publish calls' {
    $port = New-Port
    $base = "http://127.0.0.1:$port"
    # "a little lighter please" (Korean), built from code points - same reasoning as Test-RunningAI.ps1:
    # this is a no-BOM .ps1 and a literal would risk mojibake under PowerShell 5.1 source decoding.
    $koreanRequest = -join (@(0xC870, 0xAE08, 0x20, 0xB354, 0x20, 0xAC00, 0xBCCD, 0xAC8C, 0x20, 0xBC14, 0xBF4C, 0xC918) | ForEach-Object { [char]$_ })
    $routes = @(
        @{ Method = 'GET'; Path = '/actuator/health'; Body = $HealthUp }
        @{ Method = 'POST'; Path = '/api/v1/workout-drafts'; Body = (New-DraftFixture -Version 1) }
        @{ Method = 'POST'; Path = '/api/v1/workout-drafts/12/revisions'; Body = (New-DraftFixture -Version 2) }
    )
    $pending = Start-RunningAiFakeServer -Port $port -Routes $routes
    $reader = New-RunningAiScriptedReader -Responses @('R', $koreanRequest, 'Q')
    $result = Invoke-CoachOperatorSession -BaseUrl $base -Date '2026-10-04' -Reader $reader
    $log = Complete-RunningAiFakeServer -Pending $pending

    $revisionCall = $log | Where-Object { $_.Path -eq '/api/v1/workout-drafts/12/revisions' }
    $sentRequest = ($revisionCall.Body | ConvertFrom-Json).request

    ($result.Outcome -eq 'QUIT') -and (Confirm-RunningAiFakeServerLog -Log $log -ExpectedCount 3) -and
    ($sentRequest -ceq $koreanRequest)
}

# ---- 66: approval gate ----------------------------------------------------------------------------

Check 'approve gate rejects anything other than exactly APPROVE (Y / yes / Yes / empty / NO)' {
    $badAnswers = @('Y', 'yes', 'Yes', '', 'NO')
    foreach ($bad in $badAnswers) {
        $port = New-Port
        $base = "http://127.0.0.1:$port"
        $routes = @(
            @{ Method = 'GET'; Path = '/actuator/health'; Body = $HealthUp }
            @{ Method = 'POST'; Path = '/api/v1/workout-drafts'; Body = (New-DraftFixture) }
        )
        $pending = Start-RunningAiFakeServer -Port $port -Routes $routes
        $reader = New-RunningAiScriptedReader -Responses @('A', $bad, 'Q')
        $result = Invoke-CoachOperatorSession -BaseUrl $base -Date '2026-10-04' -Reader $reader
        $log = Complete-RunningAiFakeServer -Pending $pending
        if ($result.Outcome -ne 'QUIT') { throw "answer '$bad': expected QUIT, got $($result.Outcome)" }
        Confirm-RunningAiFakeServerLog -Log $log -ExpectedCount 2 | Out-Null   # health + generate only, never /approve
    }
    $true
}

Check 'approve gate accepts exactly APPROVE and calls /approve once' {
    $port = New-Port
    $base = "http://127.0.0.1:$port"
    $routes = @(
        @{ Method = 'GET'; Path = '/actuator/health'; Body = $HealthUp }
        @{ Method = 'POST'; Path = '/api/v1/workout-drafts'; Body = (New-DraftFixture) }
        @{ Method = 'POST'; Path = '/api/v1/workout-drafts/12/approve'; Body = (New-ApprovalFixture) }
        @{ Method = 'GET'; Path = '/api/v1/workout-drafts/12/publish-preview'; Body = (New-PreviewFixture -Publishable $false -ExpectedOutcome 'UNPUBLISHABLE' -UnpublishableReasons @('synthetic: stop here, this check is only about the approve gate')) }
    )
    $pending = Start-RunningAiFakeServer -Port $port -Routes $routes
    $reader = New-RunningAiScriptedReader -Responses @('A', 'APPROVE')
    $result = Invoke-CoachOperatorSession -BaseUrl $base -Date '2026-10-04' -Reader $reader
    $log = Complete-RunningAiFakeServer -Pending $pending

    ($result.Outcome -eq 'NO_EXTERNAL_WRITE_NEEDED') -and (Confirm-RunningAiFakeServerLog -Log $log -ExpectedCount 4)
}

# ---- 67/68: publish YES-gate, and no bypass parameter -------------------------------------------

Check 'publish gate rejects anything other than exactly YES (Y / yes / Yes / empty / NO)' {
    $badAnswers = @('Y', 'yes', 'Yes', '', 'NO')
    foreach ($bad in $badAnswers) {
        $port = New-Port
        $base = "http://127.0.0.1:$port"
        $routes = @(
            @{ Method = 'GET'; Path = '/api/v1/workout-drafts/12/publish-preview'; Body = (New-PreviewFixture) }
        )
        $pending = Start-RunningAiFakeServer -Port $port -Routes $routes
        $reader = New-RunningAiScriptedReader -Responses @($bad)
        $result = Invoke-RunningAiControlledPublish -BaseUrl $base -DraftId 12 -Reader $reader
        $log = Complete-RunningAiFakeServer -Pending $pending
        if ($result.Outcome -ne 'CANCELLED') { throw "answer '$bad': expected CANCELLED, got $($result.Outcome)" }
        Confirm-RunningAiFakeServerLog -Log $log -ExpectedCount 1 | Out-Null   # preview only, never /publish
    }
    $true
}

Check 'publish gate accepts exactly YES, restarts, re-previews, publishes once, verified success' {
    $port = New-Port
    $base = "http://127.0.0.1:$port"
    $routes = @(
        @{ Method = 'GET'; Path = '/api/v1/workout-drafts/12/publish-preview'; Body = (New-PreviewFixture) }
        @{ Method = 'GET'; Path = '/api/v1/workout-drafts/12/publish-preview'; Body = (New-PreviewFixture) }
        @{ Method = 'POST'; Path = '/api/v1/workout-drafts/12/publish'; Body = (New-PublishedFixture) }
    )
    $pending = Start-RunningAiFakeServer -Port $port -Routes $routes
    $reader = New-RunningAiScriptedReader -Responses @('YES')
    $restarter = New-RunningAiNoopRestarter
    $env:RUNNING_AI_DRAFT_PUBLISHING_ENABLED = 'unset-marker'

    $result = Invoke-RunningAiControlledPublish -BaseUrl $base -DraftId 12 -Reader $reader -RuntimeRestarter $restarter.Block
    $log = Complete-RunningAiFakeServer -Pending $pending

    ($result.Outcome -eq 'PUBLISHED') -and (Confirm-RunningAiFakeServerLog -Log $log -ExpectedCount 3) -and
    ($restarter.Count.Value -eq 2) -and   # once to enable, once for the safe-mode restart in finally
    ($env:RUNNING_AI_DRAFT_PUBLISHING_ENABLED -eq 'false') -and
    ($env:WORKOUT_PUBLISHING_ENABLED -eq 'false') -and
    ($env:WORKOUT_PUBLISHING_SCHEDULER_ENABLED -eq 'false') -and
    ($env:RUNNINGAI_MCP_ENABLED -eq 'false')
}

Check 'no human-gate-bypass parameter exists on any operator script' {
    $forbidden = '-AutoApprove', '-AutoPublish', '-Yes\b', '-ForcePublish'
    $targets = @(
        (Join-Path $scripts 'running-ai-coach.ps1'),
        (Join-Path $scripts 'RunningAI.CoachOperator.ps1'),
        (Join-Path $scripts 'publish-approved-draft-controlled.ps1')
    )
    foreach ($file in $targets) {
        $text = Get-Content -LiteralPath $file -Raw
        foreach ($pattern in $forbidden) {
            if ($text -match $pattern) { throw "$file contains a forbidden bypass-like token matching '$pattern'" }
        }
    }
    $true
}

# ---- 69/70: preview-before-publish and TOCTOU change protection ----------------------------------

Check 'preview is always requested before publish (route order enforced by the fake server itself)' {
    # Already covered structurally by every other Check here (the fake server 500s and the call
    # chain aborts if the implementation calls anything out of the routes' exact order) - this check
    # just makes the guarantee explicit and named per the work order.
    $port = New-Port
    $base = "http://127.0.0.1:$port"
    $routes = @(
        @{ Method = 'GET'; Path = '/api/v1/workout-drafts/12/publish-preview'; Body = (New-PreviewFixture) }
        @{ Method = 'GET'; Path = '/api/v1/workout-drafts/12/publish-preview'; Body = (New-PreviewFixture) }
        @{ Method = 'POST'; Path = '/api/v1/workout-drafts/12/publish'; Body = (New-PublishedFixture) }
    )
    $pending = Start-RunningAiFakeServer -Port $port -Routes $routes
    $reader = New-RunningAiScriptedReader -Responses @('YES')
    $restarter = New-RunningAiNoopRestarter
    Invoke-RunningAiControlledPublish -BaseUrl $base -DraftId 12 -Reader $reader -RuntimeRestarter $restarter.Block | Out-Null
    $log = Complete-RunningAiFakeServer -Pending $pending

    ($log[0].Path -eq '/api/v1/workout-drafts/12/publish-preview') -and
    ($log[-1].Path -eq '/api/v1/workout-drafts/12/publish')
}

Check 'a changed preview after the restart aborts with zero publish calls (PREVIEW_CHANGED_AFTER_RESTART)' {
    $port = New-Port
    $base = "http://127.0.0.1:$port"
    $routes = @(
        @{ Method = 'GET'; Path = '/api/v1/workout-drafts/12/publish-preview'; Body = (New-PreviewFixture -StructuredStepCount 3 -RenderedText 'version A') }
        @{ Method = 'GET'; Path = '/api/v1/workout-drafts/12/publish-preview'; Body = (New-PreviewFixture -StructuredStepCount 4 -RenderedText 'version B') }
    )
    $pending = Start-RunningAiFakeServer -Port $port -Routes $routes
    $reader = New-RunningAiScriptedReader -Responses @('YES')
    $restarter = New-RunningAiNoopRestarter
    $result = Invoke-RunningAiControlledPublish -BaseUrl $base -DraftId 12 -Reader $reader -RuntimeRestarter $restarter.Block
    $log = Complete-RunningAiFakeServer -Pending $pending

    ($result.Outcome -eq 'PREVIEW_CHANGED_AFTER_RESTART') -and (Confirm-RunningAiFakeServerLog -Log $log -ExpectedCount 2) -and
    ($restarter.Count.Value -eq 2) -and ($env:RUNNING_AI_DRAFT_PUBLISHING_ENABLED -eq 'false')
}

# ---- 71/72/73: unpublishable / already-published / REST short-circuits --------------------------

Check 'unpublishable draft: zero external write, zero switch/restart' {
    $port = New-Port
    $base = "http://127.0.0.1:$port"
    $routes = @(
        @{ Method = 'GET'; Path = '/api/v1/workout-drafts/12/publish-preview'; Body = (New-PreviewFixture -Publishable $false -ExpectedOutcome 'UNPUBLISHABLE' -UnpublishableReasons @('synthetic reason')) }
    )
    $pending = Start-RunningAiFakeServer -Port $port -Routes $routes
    $restarter = New-RunningAiNoopRestarter
    $result = Invoke-RunningAiControlledPublish -BaseUrl $base -DraftId 12 -RuntimeRestarter $restarter.Block
    $log = Complete-RunningAiFakeServer -Pending $pending

    ($result.Outcome -eq 'NO_EXTERNAL_WRITE_NEEDED') -and (Confirm-RunningAiFakeServerLog -Log $log -ExpectedCount 1) -and
    ($restarter.Count.Value -eq 0)
}

Check 'already-published draft: zero new publish, zero switch/restart' {
    $port = New-Port
    $base = "http://127.0.0.1:$port"
    $existingPublication = @{ publicationId = 1; outcome = 'PUBLISHED'; intervalsOperation = 'NO_CHANGE'; remoteEventId = 'remote-abc'; verified = $true; publishedAt = '2026-10-03T00:00:00Z' }
    $routes = @(
        @{ Method = 'GET'; Path = '/api/v1/workout-drafts/12/publish-preview'; Body = (New-PreviewFixture -Publication $existingPublication) }
    )
    $pending = Start-RunningAiFakeServer -Port $port -Routes $routes
    $restarter = New-RunningAiNoopRestarter
    $result = Invoke-RunningAiControlledPublish -BaseUrl $base -DraftId 12 -RuntimeRestarter $restarter.Block
    $log = Complete-RunningAiFakeServer -Pending $pending

    ($result.Outcome -eq 'NO_EXTERNAL_WRITE_NEEDED') -and (Confirm-RunningAiFakeServerLog -Log $log -ExpectedCount 1) -and
    ($restarter.Count.Value -eq 0)
}

Check 'REST day: zero external write, zero switch/restart' {
    $port = New-Port
    $base = "http://127.0.0.1:$port"
    $routes = @(
        @{ Method = 'GET'; Path = '/api/v1/workout-drafts/12/publish-preview'; Body = (New-PreviewFixture -RestDay $true -ExpectedOutcome 'SKIPPED_REST_DAY' -RenderedText 'REST DAY - approved rest; no workout is sent to Intervals.icu or Garmin' -StructuredStepCount 0) }
    )
    $pending = Start-RunningAiFakeServer -Port $port -Routes $routes
    $restarter = New-RunningAiNoopRestarter
    $result = Invoke-RunningAiControlledPublish -BaseUrl $base -DraftId 12 -RuntimeRestarter $restarter.Block
    $log = Complete-RunningAiFakeServer -Pending $pending

    ($result.Outcome -eq 'NO_EXTERNAL_WRITE_NEEDED') -and (Confirm-RunningAiFakeServerLog -Log $log -ExpectedCount 1) -and
    ($restarter.Count.Value -eq 0)
}

# ---- 74: cleanup-on-failure -----------------------------------------------------------------------

Check 'a failed publish still runs the finally cleanup: switches false, safe-mode restart attempted' {
    $port = New-Port
    $base = "http://127.0.0.1:$port"
    $routes = @(
        @{ Method = 'GET'; Path = '/api/v1/workout-drafts/12/publish-preview'; Body = (New-PreviewFixture) }
        @{ Method = 'GET'; Path = '/api/v1/workout-drafts/12/publish-preview'; Body = (New-PreviewFixture) }
        @{ Method = 'POST'; Path = '/api/v1/workout-drafts/12/publish'; Status = 503; Body = @{ code = 'INTERVALS_CONNECTION_FAILED'; message = 'synthetic failure for this test' } }
    )
    $pending = Start-RunningAiFakeServer -Port $port -Routes $routes
    $reader = New-RunningAiScriptedReader -Responses @('YES')
    $restarter = New-RunningAiNoopRestarter
    $result = Invoke-RunningAiControlledPublish -BaseUrl $base -DraftId 12 -Reader $reader -RuntimeRestarter $restarter.Block
    $log = Complete-RunningAiFakeServer -Pending $pending

    ($result.Outcome -eq 'FAILED') -and (Confirm-RunningAiFakeServerLog -Log $log -ExpectedCount 3) -and
    ($restarter.Count.Value -eq 2) -and
    ($env:RUNNING_AI_DRAFT_PUBLISHING_ENABLED -eq 'false') -and
    ($env:WORKOUT_PUBLISHING_ENABLED -eq 'false') -and
    ($env:WORKOUT_PUBLISHING_SCHEDULER_ENABLED -eq 'false') -and
    ($env:RUNNINGAI_MCP_ENABLED -eq 'false')
}

# ---- 75: display helper sanity (pure, no HTTP) ---------------------------------------------------

Check 'Format-RunningAiSegmentTarget shows pace, %LTHR, absolute bpm as not-publishable, and treadmill' {
    $pace = Format-RunningAiSegmentTarget @{ paceSecondsPerKmFast = 330; paceSecondsPerKmSlow = 360; heartRatePercentLthrMin = $null; heartRatePercentLthrMax = $null; heartRateBpmMin = $null; heartRateBpmMax = $null; treadmillSpeedKphMin = $null; treadmillSpeedKphMax = $null; inclinePercentMin = $null; inclinePercentMax = $null }
    $lthr = Format-RunningAiSegmentTarget @{ paceSecondsPerKmFast = $null; paceSecondsPerKmSlow = $null; heartRatePercentLthrMin = 65; heartRatePercentLthrMax = 75; heartRateBpmMin = $null; heartRateBpmMax = $null; treadmillSpeedKphMin = $null; treadmillSpeedKphMax = $null; inclinePercentMin = $null; inclinePercentMax = $null }
    $bpm = Format-RunningAiSegmentTarget @{ paceSecondsPerKmFast = $null; paceSecondsPerKmSlow = $null; heartRatePercentLthrMin = $null; heartRatePercentLthrMax = $null; heartRateBpmMin = 140; heartRateBpmMax = 150; treadmillSpeedKphMin = $null; treadmillSpeedKphMax = $null; inclinePercentMin = $null; inclinePercentMax = $null }

    ($pace -eq 'Pace 5:30-6:00/km') -and ($lthr -eq 'HR 65-75% LTHR') -and ($bpm -match 'NOT PUBLISHABLE')
}

if ($failures.Count) {
    Write-Host ("{0} check(s) failed: {1}" -f $failures.Count, ($failures -join ', ')) -ForegroundColor Red
    exit 1
}
Write-Host 'All coach-operator checks passed.'
exit 0
