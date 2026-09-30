# Internal helper: delivers Ctrl+C to the console of another process so that a JVM (Spring
# shutdown hooks) or uvicorn can shut down gracefully. Runs in its own short-lived process
# because attaching to another console detaches the caller from its own.
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
