# Internal helper: delivers Ctrl+C to the console of another process so that a JVM (Spring
# shutdown hooks) or uvicorn can shut down gracefully. Runs in its own short-lived process
# because attaching to another console detaches the caller from its own.
#
# GenerateConsoleCtrlEvent(0, group=0) is NOT a per-PID signal - it broadcasts CTRL_C_EVENT to EVERY
# process attached to whatever console AttachConsole($ProcessId) attaches to. Calling this script
# once per PID (Stop-RunningAiConnectorManaged does exactly that) is one CALL per PID, never one
# DELIVERY per PID - every other process sharing that same console receives it too. Do not conflate
# "we invoked this once for PID X" with "only PID X was signaled" (Phase 6I-1.7B-1R).
#
# Confirmed safe from cross-component leakage (Phase 6I-1.7B-1): every component here (connector,
# Spring, relay) is started via its own separate "Start-Process -WindowStyle Hidden", allocating each
# process tree its own console - so a broadcast here never reaches a DIFFERENT component or an
# unrelated application, only (at most) other members of the SAME process tree $ProcessId belongs to
# (e.g. the connector's venv launcher and its real-interpreter child, which share one console - Phase
# 6I-1.7A). It is NOT safe to assume the broadcast reaches every such member reliably, though:
# isolated testing (Phase 6I-1.7B-1) found a parent/child pair where the parent reacted to the signal
# and the child did not, despite sharing one console - which is why Stop-RunningAiConnectorManaged
# signals every verified managed PID individually rather than relying on propagation.
#
# -AllowedProcessIds (Phase 6I-1.7B-1R, optional): before sending anything, this script enumerates
# every PID actually attached to $ProcessId's console (GetConsoleProcessList) and - only when this
# parameter is supplied - refuses to send the signal at all unless EVERY attached PID is in the
# allowed set. This is the fail-safe for the broadcast-scope risk above: if an unmanaged/unverified
# process happens to share that console, the graceful signal is skipped entirely rather than risking
# it. Omitting this parameter (the default) performs the exact legacy, unconditional send - this is
# what Stop-TrackedProcess (Spring/relay, RunningAI.Common.ps1) still calls, completely unchanged;
# only Stop-RunningAiConnectorManaged's new calls ever pass it.
#
# Exit codes: 0 = signal sent. 1 = could not attach to the target console (process gone, or no
# console). 2 = refused for safety - an unmanaged process shares the console and -AllowedProcessIds
# was supplied.
# -AllowedProcessIds is a comma-joined string (e.g. "1234,5678"), not a PowerShell array parameter:
# a [int[]] parameter bound across a "-File ... -Param a,b,c" process boundary does NOT split on
# commas the way an in-session array literal would (verified directly - Windows PowerShell 5.1
# parses "a,b,c" as ONE string token and [int[]] type-converts the whole thing as a single number
# with commas stripped as thousands separators, e.g. "123,456" -> 123456). Splitting it ourselves
# below is the only reliable way to pass more than one PID across this boundary.
param(
    [Parameter(Mandatory)][int]$ProcessId,
    [string]$AllowedProcessIds
)

Add-Type -TypeDefinition @"
using System;
using System.Runtime.InteropServices;
public static class RunningAiConsoleCtrl {
    [DllImport("kernel32.dll", SetLastError = true)] static extern bool FreeConsole();
    [DllImport("kernel32.dll", SetLastError = true)] static extern bool AttachConsole(uint pid);
    [DllImport("kernel32.dll")] static extern bool SetConsoleCtrlHandler(IntPtr handler, bool add);
    [DllImport("kernel32.dll")] static extern bool GenerateConsoleCtrlEvent(uint ctrlEvent, uint group);
    [DllImport("kernel32.dll")] static extern int GetConsoleProcessList(uint[] processList, uint processCount);

    // Attaches to $pid's console just long enough to list every PID attached to it, then detaches.
    // Returns null when attaching failed (process gone / has no console).
    public static uint[] GetAttachedProcessIds(uint pid) {
        FreeConsole();
        if (!AttachConsole(pid)) return null;
        try {
            uint[] buffer = new uint[16];
            int needed = GetConsoleProcessList(buffer, (uint)buffer.Length);
            if (needed > buffer.Length) {
                buffer = new uint[needed];
                needed = GetConsoleProcessList(buffer, (uint)buffer.Length);
            }
            if (needed <= 0) return new uint[0];
            uint[] result = new uint[needed];
            Array.Copy(buffer, result, needed);
            return result;
        } finally { FreeConsole(); }
    }

    public static bool SendCtrlC(uint pid) {
        FreeConsole();
        if (!AttachConsole(pid)) return false;
        SetConsoleCtrlHandler(IntPtr.Zero, true);          // this helper ignores the signal itself
        bool ok = GenerateConsoleCtrlEvent(0, 0);          // CTRL_C_EVENT to the attached console
        System.Threading.Thread.Sleep(500);
        FreeConsole();
        return ok;
    }
}
"@

if (-not [string]::IsNullOrWhiteSpace($AllowedProcessIds)) {
    $attached = [RunningAiConsoleCtrl]::GetAttachedProcessIds([uint32]$ProcessId)
    if ($null -eq $attached) { exit 1 }
    $allowedSet = New-Object 'System.Collections.Generic.HashSet[uint32]'
    foreach ($a in ($AllowedProcessIds -split ',')) {
        if (-not [string]::IsNullOrWhiteSpace($a)) { [void]$allowedSet.Add([uint32]$a.Trim()) }
    }
    foreach ($p in $attached) {
        if ($allowedSet.Contains($p)) { continue }
        # This helper's OWN process id is always in the list - AttachConsole($ProcessId) attaches
        # THIS process to the target's console, so GetConsoleProcessList necessarily reports this
        # helper itself as one of the attached PIDs (confirmed live, Phase 6I-1.7B-1R). It is a
        # transient side effect of the check itself, not a genuine third party, and is about to
        # FreeConsole() and exit regardless.
        if ($p -eq [uint32]$PID) { continue }
        # conhost.exe is the console's own host process, not a workload - it is always attached to
        # any console a Start-Process-launched console app owns. Never treated as "an unmanaged
        # process sharing this console".
        $sharer = Get-Process -Id $p -ErrorAction SilentlyContinue
        if ($sharer -and $sharer.ProcessName -eq 'conhost') { continue }
        exit 2   # a genuinely unmanaged process shares this console - refuse to signal
    }
}

if ([RunningAiConsoleCtrl]::SendCtrlC([uint32]$ProcessId)) { exit 0 } else { exit 1 }
