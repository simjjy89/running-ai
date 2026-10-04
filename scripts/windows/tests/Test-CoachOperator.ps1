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

# A restarter that throws on its first call (simulating a partial restart failure - live on Main PC,
# the Java-discovery bug broke the publish-enabled restart partway through) and succeeds silently on
# every call after that (the finally block's safe-mode recovery restart).
function New-RunningAiFlakyRestarter {
    $count = [ref]0
    $block = {
        param($Url)
        $count.Value++
        if ($count.Value -eq 1) { throw 'simulated partial restart failure (Phase 6H-8.2)' }
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

# A RecoveryResponse (Phase 6H-8.1): deliberately has NO "type", "repetitions", "recovery",
# "recoveryDurationMinutes", "heartRateBpmMin" or "heartRateBpmMax" key - matching the real API DTO
# exactly, not the SegmentResponse shape. Pass $HrPercentLthrMin/$Max as $null for a qualitative
# (no numeric target) recovery block.
function New-RecoveryFixture {
    param(
        [int]$DurationMinutes = 1, [string]$Intensity = 'VERY_EASY',
        [string]$Description = 'Easy jog recovery between reps', [string]$PrimaryTargetType = 'HEART_RATE',
        $PaceFast = $null, $PaceSlow = $null, $HrPercentLthrMin = 65, $HrPercentLthrMax = 75,
        $TreadmillSpeedMin = $null, $TreadmillSpeedMax = $null, $InclineMin = $null, $InclineMax = $null
    )
    @{
        durationMinutes = $DurationMinutes; intensity = $Intensity; description = $Description
        primaryTargetType = $PrimaryTargetType
        paceSecondsPerKmFast = $PaceFast; paceSecondsPerKmSlow = $PaceSlow
        heartRatePercentLthrMin = $HrPercentLthrMin; heartRatePercentLthrMax = $HrPercentLthrMax
        treadmillSpeedKphMin = $TreadmillSpeedMin; treadmillSpeedKphMax = $TreadmillSpeedMax
        inclinePercentMin = $InclineMin; inclinePercentMax = $InclineMax
    }
}

# A SegmentResponse with a repeat block (repetitions + a nested RecoveryResponse) - the real shape
# that exposed the Phase 6H-8.1 bug, as opposed to New-DraftFixture's plain segments = @().
function New-RepeatSegmentFixture {
    param(
        [string]$Type = 'MAIN', [int]$DurationMinutes = 2, [string]$Intensity = 'HARD',
        [string]$Description = 'Controlled fast rep', [string]$PrimaryTargetType = 'PACE',
        $PaceFast = 275, $PaceSlow = 285, $Repetitions = 5, $Recovery = (New-RecoveryFixture)
    )
    @{
        type = $Type; durationMinutes = $DurationMinutes; intensity = $Intensity
        description = $Description; primaryTargetType = $PrimaryTargetType
        paceSecondsPerKmFast = $PaceFast; paceSecondsPerKmSlow = $PaceSlow
        heartRateBpmMin = $null; heartRateBpmMax = $null
        heartRatePercentLthrMin = $null; heartRatePercentLthrMax = $null
        treadmillSpeedKphMin = $null; treadmillSpeedKphMax = $null
        inclinePercentMin = $null; inclinePercentMax = $null
        repetitions = $Repetitions; recoveryDurationMinutes = $null
        recovery = $Recovery
    }
}

# A plain (no repeat, no recovery) SegmentResponse - a warm-up or cool-down with an HR target.
function New-SimpleSegmentFixture {
    param(
        [string]$Type = 'WARM_UP', [int]$DurationMinutes = 10, [string]$Intensity = 'VERY_EASY',
        [string]$Description = 'Warm up', $HrPercentLthrMin = 65, $HrPercentLthrMax = 75
    )
    @{
        type = $Type; durationMinutes = $DurationMinutes; intensity = $Intensity; description = $Description
        primaryTargetType = 'HEART_RATE'
        paceSecondsPerKmFast = $null; paceSecondsPerKmSlow = $null
        heartRateBpmMin = $null; heartRateBpmMax = $null
        heartRatePercentLthrMin = $HrPercentLthrMin; heartRatePercentLthrMax = $HrPercentLthrMax
        treadmillSpeedKphMin = $null; treadmillSpeedKphMax = $null
        inclinePercentMin = $null; inclinePercentMax = $null
        repetitions = $null; recoveryDurationMinutes = $null; recovery = $null
    }
}

# A full draft matching the real Draft #10 shape that exposed this bug on Main PC:
# WARM_UP (HR) -> MAIN x5 with a targeted recovery (PACE + HR recovery) -> COOL_DOWN (HR).
function New-RepeatDraftFixture {
    param([int]$Id = 10, [int]$Version = 1, [string]$Status = 'DRAFT')
    $draft = New-DraftFixture -Id $Id -Version $Version -Status $Status
    $draft.segments = @(
        (New-SimpleSegmentFixture -Type 'WARM_UP' -Description 'Warm up'),
        (New-RepeatSegmentFixture),
        (New-SimpleSegmentFixture -Type 'COOL_DOWN' -Description 'Cool down')
    )
    return $draft
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

# A hashtable fixture and a real parsed-JSON API response are NOT the same shape for property
# access: a hashtable's dot-access ($h.foo) works for any key whether or not Get-RunningAiOptionalProperty
# (which reads .PSObject.Properties) can see it - $h.PSObject.Properties never exposes a Hashtable's
# own keys at all. Every fixture handed directly to a display function (not through the fake HTTP
# server, which already round-trips through real JSON) must go through this first, or a check could
# pass against a hashtable while the identical bug still breaks a real PSCustomObject API response.
function ConvertTo-RunningAiFakeApiObject {
    param([Parameter(Mandatory)][hashtable]$Fixture, [int]$Depth = 10)
    return ($Fixture | ConvertTo-Json -Depth $Depth) | ConvertFrom-Json
}

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

# Phase 6H-8.2 live finding (Main PC): the publish-enabled restart itself failed partway through
# (Spring stopped, connector stopped, connector started, then Java discovery failed before Spring
# could start) - a partial failure, not a clean return. The old "$restarted = $true" (set only AFTER
# the restarter call returned) never ran, so the finally block's safe-mode recovery restart was
# skipped entirely, leaving the runtime down. This proves the fix: "was a publish-enabled restart
# ATTEMPTED" (yes) is what must gate the recovery restart, not "did it fully succeed" (no).
Check 'a restarter that fails partway through still gets a safe-mode recovery restart attempt (no publish, switches false)' {
    $port = New-Port
    $base = "http://127.0.0.1:$port"
    $routes = @(
        @{ Method = 'GET'; Path = '/api/v1/workout-drafts/12/publish-preview'; Body = (New-PreviewFixture) }
    )
    $pending = Start-RunningAiFakeServer -Port $port -Routes $routes
    $reader = New-RunningAiScriptedReader -Responses @('YES')
    $restarter = New-RunningAiFlakyRestarter
    $result = Invoke-RunningAiControlledPublish -BaseUrl $base -DraftId 12 -Reader $reader -RuntimeRestarter $restarter.Block
    $log = Complete-RunningAiFakeServer -Pending $pending

    ($result.Outcome -eq 'FAILED') -and (Confirm-RunningAiFakeServerLog -Log $log -ExpectedCount 1) -and
    ($restarter.Count.Value -eq 2) -and   # 1st: the failed publish-enabled attempt; 2nd: safe-mode recovery
    ($env:RUNNING_AI_DRAFT_PUBLISHING_ENABLED -eq 'false') -and
    ($env:WORKOUT_PUBLISHING_ENABLED -eq 'false') -and
    ($env:WORKOUT_PUBLISHING_SCHEDULER_ENABLED -eq 'false') -and
    ($env:RUNNINGAI_MCP_ENABLED -eq 'false')
}

# ---- 75: display helper sanity (pure, no HTTP) ---------------------------------------------------

Check 'Format-RunningAiSegmentTarget shows pace, %LTHR, absolute bpm as not-publishable, and treadmill' {
    $pace = Format-RunningAiSegmentTarget (ConvertTo-RunningAiFakeApiObject @{ paceSecondsPerKmFast = 330; paceSecondsPerKmSlow = 360; heartRatePercentLthrMin = $null; heartRatePercentLthrMax = $null; heartRateBpmMin = $null; heartRateBpmMax = $null; treadmillSpeedKphMin = $null; treadmillSpeedKphMax = $null; inclinePercentMin = $null; inclinePercentMax = $null })
    $lthr = Format-RunningAiSegmentTarget (ConvertTo-RunningAiFakeApiObject @{ paceSecondsPerKmFast = $null; paceSecondsPerKmSlow = $null; heartRatePercentLthrMin = 65; heartRatePercentLthrMax = 75; heartRateBpmMin = $null; heartRateBpmMax = $null; treadmillSpeedKphMin = $null; treadmillSpeedKphMax = $null; inclinePercentMin = $null; inclinePercentMax = $null })
    $bpm = Format-RunningAiSegmentTarget (ConvertTo-RunningAiFakeApiObject @{ paceSecondsPerKmFast = $null; paceSecondsPerKmSlow = $null; heartRatePercentLthrMin = $null; heartRatePercentLthrMax = $null; heartRateBpmMin = 140; heartRateBpmMax = 150; treadmillSpeedKphMin = $null; treadmillSpeedKphMax = $null; inclinePercentMin = $null; inclinePercentMax = $null })

    ($pace -eq 'Pace 5:30-6:00/km') -and ($lthr -eq 'HR 65-75% LTHR') -and ($bpm -match 'NOT PUBLISHABLE')
}

# ---- Phase 6H-8.1: recovery-block display hotfix -------------------------------------------------
# RecoveryResponse (durationMinutes/intensity/description/primaryTargetType/pace/%LTHR/treadmill/
# incline) is a DIFFERENT API shape from SegmentResponse (adds type/repetitions/recovery/
# recoveryDurationMinutes/heartRateBpmMin/Max) - rendering one as if it were the other threw
# PropertyNotFoundException live on Main PC (Draft #10: a MAIN x5 with a targeted recovery block).

Check 'A: a targeted repeat+recovery segment displays without error (MAIN x5, Recovery, HR target)' {
    $recovery = ConvertTo-RunningAiFakeApiObject (New-RecoveryFixture)
    $segment = ConvertTo-RunningAiFakeApiObject (New-RepeatSegmentFixture -Recovery (New-RecoveryFixture))
    Write-RunningAiSegment -Segment $segment | Out-Null
    $true
}

Check 'B: a recovery object with no "type" property displays without PropertyNotFoundException' {
    $recovery = ConvertTo-RunningAiFakeApiObject (New-RecoveryFixture)
    if ($null -ne $recovery.PSObject.Properties['type']) { throw 'fixture bug: the recovery fixture must not have a type property' }
    Write-RunningAiRecovery -Recovery $recovery | Out-Null
    $true
}

Check 'C: a recovery object with no "repetitions" property displays without PropertyNotFoundException' {
    $recovery = ConvertTo-RunningAiFakeApiObject (New-RecoveryFixture)
    if ($null -ne $recovery.PSObject.Properties['repetitions']) { throw 'fixture bug: the recovery fixture must not have a repetitions property' }
    Write-RunningAiRecovery -Recovery $recovery | Out-Null
    $true
}

Check 'D: a qualitative recovery (no pace / %LTHR / bpm) displays without error and shows no target line' {
    $recovery = ConvertTo-RunningAiFakeApiObject (New-RecoveryFixture -HrPercentLthrMin $null -HrPercentLthrMax $null)
    $target = Format-RunningAiSegmentTarget $recovery
    Write-RunningAiRecovery -Recovery $recovery | Out-Null
    $null -eq $target
}

Check 'E: a draft that fails to display never reaches approve/preview/publish (fail-closed)' {
    $port = New-Port
    $base = "http://127.0.0.1:$port"
    $malformed = New-DraftFixture
    # A segment missing its "type" key entirely - a real API response always has it (SegmentResponse
    # is @JsonInclude(ALWAYS)), so this simulates the display-breaking shape mismatch this phase found,
    # without relying on the specific bug already fixed above (future-proofs the fail-closed gate).
    $malformed.segments = @(
        @{ durationMinutes = 10; intensity = 'VERY_EASY'; description = $null; primaryTargetType = $null
           paceSecondsPerKmFast = $null; paceSecondsPerKmSlow = $null; heartRateBpmMin = $null; heartRateBpmMax = $null
           heartRatePercentLthrMin = $null; heartRatePercentLthrMax = $null; treadmillSpeedKphMin = $null
           treadmillSpeedKphMax = $null; inclinePercentMin = $null; inclinePercentMax = $null
           repetitions = $null; recoveryDurationMinutes = $null; recovery = $null }
    )
    $routes = @(
        @{ Method = 'GET'; Path = '/actuator/health'; Body = $HealthUp }
        @{ Method = 'POST'; Path = '/api/v1/workout-drafts'; Body = $malformed }
    )
    $pending = Start-RunningAiFakeServer -Port $port -Routes $routes
    # Interactive (not -NonInteractive): if display didn't fail closed, the menu would prompt next,
    # and a reader with no scripted responses would throw "ran out of responses" instead - a
    # different failure that would NOT prove zero approve/publish calls were made, so this uses a
    # reader that would itself be a visible test failure if ever invoked.
    $reader = { param($Prompt) throw "the approve/revise menu must never be reached: $Prompt" }
    $result = Invoke-CoachOperatorSession -BaseUrl $base -Date '2026-10-04' -Reader $reader
    $log = Complete-RunningAiFakeServer -Pending $pending

    ($result.Outcome -eq 'DISPLAY_FAILED') -and (Confirm-RunningAiFakeServerLog -Log $log -ExpectedCount 2)
}

Check 'F: a Draft #10-compatible shape (WU HR / MAIN PACE x5 / Recovery HR / CD HR) displays fully' {
    $port = New-Port
    $base = "http://127.0.0.1:$port"
    $routes = @(
        @{ Method = 'GET'; Path = '/actuator/health'; Body = $HealthUp }
        @{ Method = 'POST'; Path = '/api/v1/workout-drafts'; Body = (New-RepeatDraftFixture) }
    )
    $pending = Start-RunningAiFakeServer -Port $port -Routes $routes
    $result = Invoke-CoachOperatorSession -BaseUrl $base -Date '2026-10-04' -NonInteractive
    $log = Complete-RunningAiFakeServer -Pending $pending

    ($result.Outcome -eq 'DISPLAYED') -and (Confirm-RunningAiFakeServerLog -Log $log -ExpectedCount 2) -and
    ($result.Draft.segments.Count -eq 3) -and ($result.Draft.segments[1].recovery.durationMinutes -eq 1)
}

if ($failures.Count) {
    Write-Host ("{0} check(s) failed: {1}" -f $failures.Count, ($failures -join ', ')) -ForegroundColor Red
    exit 1
}
Write-Host 'All coach-operator checks passed.'
exit 0
