# Garmin connector process-ownership model. Dot-source, do not run.
#
#   . "$PSScriptRoot\RunningAI.ConnectorOwnership.ps1"
#
# Phase 6I-1.7A found that this machine's connector virtual environment launcher (a venv python.exe
# under tools\garmin-connector\.venv, the process Start-Process actually returns and the one
# Write-PidFile records) is only a stub: it re-execs the real CPython interpreter (here, a Python
# Install Manager-managed install under AppData\Local\Python\pythoncore-*) as a CHILD process, and
# that child - not the tracked launcher - is the one that actually binds the TCP port. The tracked
# .pid file and the real LISTEN socket can therefore legitimately belong to two different, live PIDs
# at once. Live-confirmed (6I-1.7A): both processes are created in the same instant, the listener's
# ParentProcessId is the launcher, and the launcher's only other child is a conhost.exe for the same
# console - not a duplicate connector, not a uvicorn worker/reloader, not a stale leftover process.
#
# This file adds a PID-and-port ownership MODEL on top of the existing single-PID-file mechanism
# (RunningAI.Common.ps1's Read-PidFile/Write-PidFile/Test-ProcessIdentity/Get-TrackedProcessId are
# all unchanged and still the source of truth for "the tracked PID"). It never replaces that
# mechanism, only adds a second, read-only cross-check: given the tracked PID and the real LISTEN
# PID, decide which live PIDs can safely be treated as "this connector instance" before anything is
# ever stopped or reported.
#
# ---- Metadata sidecar (optional, additive, backward compatible) -------------------------------
# File: .runtime\<name>.owner.json (next to the existing .runtime\<name>.pid; never replaces it).
# Written ONLY at the moment start-running-ai.ps1 itself observes a freshly started connector to be
# healthy - the one instant both the launcher and the real listener are guaranteed alive, freshly
# created, and provably related (live parent/child check, no stored state needed yet). Content:
#   { version, name, port, repoRoot, recordedAt,
#     launcher: { processId, creationDate } | null,
#     listener: { processId, creationDate } }
# creationDate is ISO-8601 UTC ("o" round-trip format). Written through a temp file + File.Replace
# (same atomic pattern RunningAI.Watchdog.ps1 uses for its own state/status JSON), so a reader never
# observes a half-written file.
#
# Why it exists: once the launcher has exited, its PID can be reassigned by Windows to a totally
# unrelated process, so a live parent/child re-check is no longer possible or trustworthy. The
# sidecar lets a LATER run recognize "the listener now has no parent, but it is exactly the PID and
# creation time we ourselves recorded as this connector's listener" - i.e. a genuine orphan of our
# own process tree - without ever trusting a bare, possibly-recycled PID number alone.
#
# Backward compatibility: the sidecar is purely additive. Its absence (a connector started before
# this change, or a Spring/relay PID that never gets one) degrades every function below to the live
# parent/child check only - never an error, never a forced interpretation. A corrupt/unreadable
# sidecar is treated exactly like an absent one. Nothing here ever converts, migrates or deletes an
# existing .pid file, and nothing here is called automatically against an already-running process -
# every entry point is invoked explicitly by start-/stop-/watch-running-ai.ps1.

. "$PSScriptRoot\RunningAI.Common.ps1"

# ---- live process inspection -------------------------------------------------------------------

# Returns $null when the PID is not a live process. CommandLine is kept on the object for this
# file's own marker checks only; callers must never print it wholesale (it may contain a Garmin
# token PATH argument in some future invocation shape, even though today's connector/Spring command
# lines do not take one).
function Get-RunningAiProcessInfo {
    param([Parameter(Mandatory)][int]$ProcessId)
    $p = Get-CimInstance Win32_Process -Filter "ProcessId=$ProcessId" -ErrorAction SilentlyContinue
    if (-not $p) { return $null }
    [pscustomobject]@{
        ProcessId       = [int]$p.ProcessId
        ParentProcessId = [int]$p.ParentProcessId
        Name            = $p.Name
        ExecutablePath  = $p.ExecutablePath
        CreationDate    = $p.CreationDate
        CommandLine     = $p.CommandLine
    }
}

# The single PID currently LISTENing on $Port, or $null when nothing is, or when (very rare -
# IPv4/IPv6 dual rows briefly during bind/unbind) more than one distinct PID is reported, since a
# genuinely ambiguous owner must never be auto-trusted.
function Get-RunningAiListenerProcessId {
    param([Parameter(Mandatory)][int]$Port)
    $conns = @(Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue)
    if ($conns.Count -eq 0) { return $null }
    $pids = @($conns | Select-Object -ExpandProperty OwningProcess -Unique)
    if ($pids.Count -eq 1) { return [int]$pids[0] }
    return $null
}

# True when a live process's command line looks like *some* garmin_connector serve process,
# regardless of repository - used only to distinguish "a connector for a different RunningAI
# worktree/repo" (FOREIGN_PROCESS) from "a genuinely unrelated process" (UNKNOWN_OWNER). Never on
# its own grounds for treating a process as ours - Test-ProcessIdentity (RunningAI.Common.ps1),
# which also requires this repository's own root path, remains the only trust check for that.
function Test-RunningAiConnectorShape {
    param($ProcessInfo)
    if (-not $ProcessInfo -or -not $ProcessInfo.CommandLine) { return $false }
    foreach ($m in @('garmin_connector', ' serve')) {
        if ($ProcessInfo.CommandLine.IndexOf($m, [StringComparison]::OrdinalIgnoreCase) -lt 0) { return $false }
    }
    return $true
}

# Tolerant datetime comparison for the sidecar's recorded creation time against a live process's own
# CreationDate - small slack for JSON round-trip/WMI rounding, not for telling two different process
# creations apart (new processes are never created within a couple of seconds of each other in this
# codebase's own start sequence by accident).
function Test-RunningAiCreationDateMatches {
    param($Expected, $Actual, [int]$ToleranceSeconds = 2)
    if (-not $Expected -or -not $Actual) { return $false }
    try {
        # Both sides are normalized to UTC before comparing - $Expected (from the JSON sidecar) is
        # always already UTC ("o" format, Kind=Utc after RoundtripKind parsing), but $Actual, when
        # passed as a live Win32_Process.CreationDate [datetime], comes back from CIM as local time
        # (Kind=Local) - comparing it directly against a UTC value would be off by the machine's UTC
        # offset (e.g. 9 hours in KST), silently failing every match. ToUniversalTime() is a no-op for
        # a value already Kind=Utc, so a parsed $Expected is unaffected by the same call.
        $e = if ($Expected -is [datetime]) { $Expected.ToUniversalTime() } else { [datetime]::Parse($Expected, [System.Globalization.CultureInfo]::InvariantCulture, [System.Globalization.DateTimeStyles]::RoundtripKind).ToUniversalTime() }
        $a = if ($Actual -is [datetime]) { $Actual.ToUniversalTime() } else { [datetime]::Parse($Actual, [System.Globalization.CultureInfo]::InvariantCulture, [System.Globalization.DateTimeStyles]::RoundtripKind).ToUniversalTime() }
        return [math]::Abs(($a - $e).TotalSeconds) -le $ToleranceSeconds
    } catch { return $false }
}

# ---- metadata sidecar -------------------------------------------------------------------------

function Get-RunningAiOwnerMetaPath { param([Parameter(Mandatory)][string]$Name) Join-Path $script:RuntimeDir "$Name.owner.json" }

function Write-RunningAiOwnerMeta {
    param(
        [Parameter(Mandatory)][string]$Name,
        [Parameter(Mandatory)][int]$Port,
        $Launcher,                               # {ProcessId; CreationDate} or $null (self-owned case)
        [Parameter(Mandatory)]$Listener           # {ProcessId; CreationDate}
    )
    New-Item -ItemType Directory -Force $script:RuntimeDir | Out-Null
    $obj = [ordered]@{
        version    = 1
        name       = $Name
        port       = $Port
        repoRoot   = $script:RepoRoot
        recordedAt = (Get-Date).ToUniversalTime().ToString('yyyy-MM-ddTHH:mm:ssZ')
        launcher   = if ($Launcher) { [ordered]@{ processId = $Launcher.ProcessId; creationDate = $Launcher.CreationDate.ToUniversalTime().ToString('o') } } else { $null }
        listener   = [ordered]@{ processId = $Listener.ProcessId; creationDate = $Listener.CreationDate.ToUniversalTime().ToString('o') }
    }
    $path = Get-RunningAiOwnerMetaPath $Name
    $tmp = "$path.tmp-$([guid]::NewGuid().ToString('N'))"
    $json = ConvertTo-Json -InputObject $obj -Depth 6
    [System.IO.File]::WriteAllText($tmp, $json, (New-Object System.Text.UTF8Encoding($false)))
    if (Test-Path -LiteralPath $path) { [System.IO.File]::Replace($tmp, $path, [NullString]::Value) } else { [System.IO.File]::Move($tmp, $path) }
}

# Never throws: a missing file is normal (older connector, or Spring/relay), a corrupt or
# unrecognized-version file is treated exactly like a missing one.
function Read-RunningAiOwnerMeta {
    param([Parameter(Mandatory)][string]$Name)
    $path = Get-RunningAiOwnerMetaPath $Name
    if (-not (Test-Path -LiteralPath $path)) { return $null }
    try {
        $raw = Get-Content -LiteralPath $path -Raw -ErrorAction Stop
        $obj = ConvertFrom-Json $raw -ErrorAction Stop
        if ($obj.version -ne 1 -or -not $obj.listener -or -not $obj.listener.processId) { return $null }
        [pscustomobject]@{
            Port     = [int]$obj.port
            RepoRoot = $obj.repoRoot
            Launcher = if ($obj.launcher) { [pscustomobject]@{ ProcessId = [int]$obj.launcher.processId; CreationDate = $obj.launcher.creationDate } } else { $null }
            Listener = [pscustomobject]@{ ProcessId = [int]$obj.listener.processId; CreationDate = $obj.listener.creationDate }
        }
    } catch { return $null }
}

function Remove-RunningAiOwnerMeta { param([Parameter(Mandatory)][string]$Name) Remove-Item (Get-RunningAiOwnerMetaPath $Name) -Force -ErrorAction SilentlyContinue }

# Captures ground truth at the one moment it is cheapest and most certain to get right: immediately
# after start-running-ai.ps1 has confirmed a freshly started connector is healthy, both the launcher
# ($LauncherPid, from Start-Process) and the real listener are guaranteed alive. Returns $false
# (never throws) when the listener cannot be found or inspected - metadata capture is best-effort and
# must never fail the caller's own startup success.
function Set-RunningAiConnectorOwnerMetaFromLive {
    param([Parameter(Mandatory)][int]$LauncherPid, [Parameter(Mandatory)][int]$Port, [string]$Name = 'garmin-connector')
    $launcherInfo = Get-RunningAiProcessInfo -ProcessId $LauncherPid
    $listenerPid = Get-RunningAiListenerProcessId -Port $Port
    if (-not $launcherInfo -or -not $listenerPid) { return $false }
    $listenerInfo = Get-RunningAiProcessInfo -ProcessId $listenerPid
    if (-not $listenerInfo) { return $false }
    $launcher = if ($listenerPid -eq $LauncherPid) { $null } else { [pscustomobject]@{ ProcessId = $launcherInfo.ProcessId; CreationDate = $launcherInfo.CreationDate } }
    Write-RunningAiOwnerMeta -Name $Name -Port $Port -Launcher $launcher `
        -Listener ([pscustomobject]@{ ProcessId = $listenerInfo.ProcessId; CreationDate = $listenerInfo.CreationDate })
    return $true
}

# ---- ownership resolution -----------------------------------------------------------------------

# Verdicts:
#   SELF_OWNED               - a single process (no separate launcher) carries every marker.
#   LAUNCHER_CHILD            - tracked launcher alive and ours; listener is its live-verified child.
#   ORPHANED_MANAGED_PROCESS  - tracked launcher is gone, but the sidecar positively re-identifies
#                               the current listener as the one we recorded for it (PID + creation
#                               time both match - never a bare PID guess).
#   FOREIGN_PROCESS           - the port is held by what looks like a connector for a DIFFERENT
#                               repository/worktree (connector shape present, repo marker absent).
#   UNKNOWN_OWNER             - nothing above applies; ownership cannot be established. Never
#                               auto-managed by any caller.
#   DOWN                      - nothing is listening on the port at all.
#
# ManagedPids is the exact, order-independent set of live PIDs a caller may safely act on (stop,
# restart) for verdicts SELF_OWNED / LAUNCHER_CHILD / ORPHANED_MANAGED_PROCESS, and is always empty
# otherwise - callers must never derive a "safe to touch" set any other way.
function Get-RunningAiConnectorOwnership {
    param(
        [Parameter(Mandatory)][int]$Port,
        $TrackedPid,
        [Parameter(Mandatory)][string[]]$Markers,
        [string]$Name = 'garmin-connector'
    )

    $listenerPid = Get-RunningAiListenerProcessId -Port $Port
    if (-not $listenerPid) {
        return [pscustomobject]@{ Verdict = 'DOWN'; LauncherPid = $null; ListenerPid = $null; ManagedPids = @(); Detail = 'Nothing is listening on the port.' }
    }

    $listenerInfo = Get-RunningAiProcessInfo -ProcessId $listenerPid
    if (-not $listenerInfo) {
        return [pscustomobject]@{ Verdict = 'DOWN'; LauncherPid = $null; ListenerPid = $null; ManagedPids = @(); Detail = 'Listener PID vanished before it could be inspected.' }
    }
    $listenerIsOurs = Test-ProcessIdentity -ProcessId $listenerPid -Markers $Markers

    if (-not $TrackedPid) {
        if ($listenerIsOurs) {
            return [pscustomobject]@{ Verdict = 'SELF_OWNED'; LauncherPid = $listenerPid; ListenerPid = $listenerPid; ManagedPids = @($listenerPid); Detail = 'No tracked PID; listener itself carries every expected marker.' }
        }
        if (Test-RunningAiConnectorShape -ProcessInfo $listenerInfo) {
            return [pscustomobject]@{ Verdict = 'FOREIGN_PROCESS'; LauncherPid = $null; ListenerPid = $listenerPid; ManagedPids = @(); Detail = 'Port is held by a garmin_connector process for a different repository/worktree.' }
        }
        return [pscustomobject]@{ Verdict = 'UNKNOWN_OWNER'; LauncherPid = $null; ListenerPid = $listenerPid; ManagedPids = @(); Detail = 'No tracked PID and the current listener has no recognizable connector markers.' }
    }

    $trackedInfo = Get-RunningAiProcessInfo -ProcessId $TrackedPid
    $trackedIsOurs = [bool]$trackedInfo -and (Test-ProcessIdentity -ProcessId $TrackedPid -Markers $Markers)

    if ($listenerPid -eq $TrackedPid) {
        if ($trackedIsOurs) {
            return [pscustomobject]@{ Verdict = 'SELF_OWNED'; LauncherPid = $TrackedPid; ListenerPid = $listenerPid; ManagedPids = @($listenerPid); Detail = 'Tracked PID is itself the listener.' }
        }
        return [pscustomobject]@{ Verdict = 'UNKNOWN_OWNER'; LauncherPid = $null; ListenerPid = $listenerPid; ManagedPids = @(); Detail = 'Tracked PID is the listener but no longer matches the expected markers (likely a recycled PID).' }
    }

    if ($trackedIsOurs) {
        if ($listenerInfo.ParentProcessId -eq $TrackedPid -and $listenerInfo.CreationDate -ge $trackedInfo.CreationDate) {
            return [pscustomobject]@{ Verdict = 'LAUNCHER_CHILD'; LauncherPid = $TrackedPid; ListenerPid = $listenerPid; ManagedPids = @($TrackedPid, $listenerPid); Detail = 'Listener is a live-verified direct child of the tracked launcher, created at/after it.' }
        }
        return [pscustomobject]@{ Verdict = 'UNKNOWN_OWNER'; LauncherPid = $TrackedPid; ListenerPid = $listenerPid; ManagedPids = @(); Detail = 'Tracked launcher is alive and ours, but the port listener is not its child.' }
    }

    # Tracked PID is gone or no longer matches: only recorded metadata (never a bare PID guess) can
    # re-identify an orphaned child of a launcher that has since exited.
    $meta = Read-RunningAiOwnerMeta -Name $Name
    if ($meta -and $meta.Port -eq $Port -and $meta.Listener.ProcessId -eq $listenerPid -and
        (Test-RunningAiCreationDateMatches -Expected $meta.Listener.CreationDate -Actual $listenerInfo.CreationDate)) {
        return [pscustomobject]@{ Verdict = 'ORPHANED_MANAGED_PROCESS'; LauncherPid = $null; ListenerPid = $listenerPid; ManagedPids = @($listenerPid); Detail = 'Tracked launcher is gone; the listener matches a previously recorded managed process (PID and creation time both confirmed).' }
    }

    if ($listenerIsOurs) {
        return [pscustomobject]@{ Verdict = 'SELF_OWNED'; LauncherPid = $listenerPid; ListenerPid = $listenerPid; ManagedPids = @($listenerPid); Detail = 'Tracked launcher is gone/foreign, but the listener independently carries every expected marker.' }
    }
    if (Test-RunningAiConnectorShape -ProcessInfo $listenerInfo) {
        return [pscustomobject]@{ Verdict = 'FOREIGN_PROCESS'; LauncherPid = $null; ListenerPid = $listenerPid; ManagedPids = @(); Detail = 'Port is held by a garmin_connector process for a different repository/worktree, and no metadata links it to this one.' }
    }
    return [pscustomobject]@{ Verdict = 'UNKNOWN_OWNER'; LauncherPid = $null; ListenerPid = $listenerPid; ManagedPids = @(); Detail = 'Tracked PID is gone or no longer matches, and the current listener cannot be confirmed as ours by any available evidence.' }
}

# ---- safe stop ------------------------------------------------------------------------------

# Stops ONLY a positively-verified connector process set. Refuses (returns 'refused', touches
# nothing) for SELF_OWNED/LAUNCHER_CHILD/ORPHANED_MANAGED_PROCESS, re-validates every managed PID
# immediately before acting, prefers graceful shutdown (Ctrl+C to the launcher when it survives -
# its console group reaches the child too; straight to the listener otherwise), confirms the actual
# port AND every managed PID are gone before declaring success, and - only on a graceful timeout -
# force-stops exactly the still-verified managed PID(s), one Stop-Process -Id call per PID, never a
# process-tree or taskkill /T /F. Never touches conhost.exe or anything outside ManagedPids.
function Stop-RunningAiConnectorManaged {
    param(
        [Parameter(Mandatory)]$Ownership,
        [Parameter(Mandatory)][int]$Port,
        [int]$TimeoutSec = 15,
        [string]$Name = 'garmin-connector'
    )
    $manageable = @('SELF_OWNED', 'LAUNCHER_CHILD', 'ORPHANED_MANAGED_PROCESS')
    if (($Ownership.Verdict -notin $manageable) -or (@($Ownership.ManagedPids).Count -eq 0)) {
        return [pscustomobject]@{ Result = 'refused'; Verdict = $Ownership.Verdict; PortFreed = (-not (Test-PortInUse $Port)); RemainingPids = @() }
    }

    $verifiedPids = @(@($Ownership.ManagedPids) | Where-Object { Get-RunningAiProcessInfo -ProcessId $_ })
    if ($verifiedPids.Count -eq 0) {
        Remove-RunningAiOwnerMeta -Name $Name
        return [pscustomobject]@{ Result = 'already-gone'; Verdict = $Ownership.Verdict; PortFreed = (-not (Test-PortInUse $Port)); RemainingPids = @() }
    }

    # Signal every verified managed PID individually - never only the launcher. Live testing
    # (Phase 6I-1.7B-1) found GenerateConsoleCtrlEvent's console-group broadcast does NOT reliably
    # reach a child spawned the way this codebase's own Start-Process calls spawn one (redirected
    # stdout/stderr, WindowStyle Hidden): the parent receives and reacts to the signal, the child does
    # not, even though both are attached to the same console. Explicitly targeting each managed PID
    # removes the dependency on that unverified propagation entirely.
    $helper = Join-Path $PSScriptRoot 'Send-CtrlC.ps1'
    $ps = (Get-Command powershell.exe).Source
    foreach ($candidatePid in $verifiedPids) {
        $argLine = "-NoProfile -ExecutionPolicy Bypass -File $(Quote-Argument $helper) -ProcessId $candidatePid"
        Start-Process -FilePath $ps -ArgumentList $argLine -WindowStyle Hidden -Wait | Out-Null
    }

    $graceful = Wait-Until -TimeoutSec $TimeoutSec -PollSec 1 -Test {
        (-not (Test-PortInUse $Port)) -and (@($verifiedPids | Where-Object { Get-Process -Id $_ -ErrorAction SilentlyContinue }).Count -eq 0)
    }
    if ($graceful) {
        Remove-RunningAiOwnerMeta -Name $Name
        return [pscustomobject]@{ Result = 'graceful'; Verdict = $Ownership.Verdict; PortFreed = $true; RemainingPids = @() }
    }

    Write-Step "Connector managed PID(s) $($verifiedPids -join ', ') did not exit within ${TimeoutSec}s; forcing stop of verified process(es) only."
    foreach ($candidatePid in @($verifiedPids | Where-Object { Get-Process -Id $_ -ErrorAction SilentlyContinue })) {
        # Re-check identity immediately before the forced kill, never on a result that may now be
        # several seconds stale. A PID that no longer resolves is simply skipped, not retried.
        $fresh = Get-RunningAiProcessInfo -ProcessId $candidatePid
        if ($fresh -and (($candidatePid -eq $Ownership.ListenerPid) -or ($candidatePid -eq $Ownership.LauncherPid))) {
            Stop-Process -Id $candidatePid -Force -ErrorAction SilentlyContinue
        }
    }

    $portFreed = Wait-Until -TimeoutSec 10 -PollSec 1 -Test { -not (Test-PortInUse $Port) }
    $remaining = @($verifiedPids | Where-Object { Get-Process -Id $_ -ErrorAction SilentlyContinue })
    if ($portFreed -and $remaining.Count -eq 0) { Remove-RunningAiOwnerMeta -Name $Name }

    [pscustomobject]@{
        Result        = if ($remaining.Count -gt 0) { 'orphan-remaining' } else { 'forced' }
        Verdict       = $Ownership.Verdict
        PortFreed     = $portFreed
        RemainingPids = $remaining
    }
}
