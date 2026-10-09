# Internal helper: delivers Ctrl+C to the console of another process so that a JVM (Spring
# shutdown hooks) or uvicorn can shut down gracefully. Runs in its own short-lived process
# because attaching to another console detaches the caller from its own.
#
# GenerateConsoleCtrlEvent(0, group=0) broadcasts to EVERY process attached to the target console,
# not just $ProcessId - evaluated (Phase 6I-1.7B-1) for risk of hitting unrelated processes, given
# the connector's venv launcher re-execs the real interpreter as a CHILD process (Phase 6I-1.7A) that
# shares the launcher's console. Confirmed safe from cross-component leakage: every component here
# (connector, Spring, relay) is started via its own separate "Start-Process -WindowStyle Hidden",
# allocating each process tree its own console - so a broadcast here never reaches a different
# component or an unrelated application, only (at most) members of the SAME process tree this PID
# belongs to.
#
# It is NOT safe to assume this broadcast reaches a child by itself, though: isolated testing
# (Phase 6I-1.7B-1, a PowerShell-spawned parent/child pair reproducing the launcher/listener shape)
# found the parent reacts to the signal and the child does not, even while both share one console.
# Stop-RunningAiConnectorManaged (RunningAI.ConnectorOwnership.ps1) therefore calls this helper once
# per verified managed PID - launcher AND listener individually - rather than signaling only the
# launcher and relying on this broadcast to reach its child.
param([Parameter(Mandatory)][int]$ProcessId)

Add-Type -TypeDefinition @"
using System;
using System.Runtime.InteropServices;
public static class RunningAiConsoleCtrl {
    [DllImport("kernel32.dll", SetLastError = true)] static extern bool FreeConsole();
    [DllImport("kernel32.dll", SetLastError = true)] static extern bool AttachConsole(uint pid);
    [DllImport("kernel32.dll")] static extern bool SetConsoleCtrlHandler(IntPtr handler, bool add);
    [DllImport("kernel32.dll")] static extern bool GenerateConsoleCtrlEvent(uint ctrlEvent, uint group);
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

if ([RunningAiConsoleCtrl]::SendCtrlC([uint32]$ProcessId)) { exit 0 } else { exit 1 }
