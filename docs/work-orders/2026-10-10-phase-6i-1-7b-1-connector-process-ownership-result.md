# Phase 6I-1.7B-1 — Garmin Connector Process Lifecycle Hardening — Result

## 1. 변경 파일

New:
- `scripts/windows/RunningAI.ConnectorOwnership.ps1` — ownership model + safe stop (core deliverable).
- `scripts/windows/tests/Test-ConnectorOwnership.ps1` — isolated integration tests (17 checks).
- `docs/architecture/connector-process-ownership.md` — model/metadata-format documentation.

Modified (connector-only call sites; Spring/relay paths untouched):
- `scripts/windows/start-running-ai.ps1` — ownership-aware `Stop-NewConnectorOnFailure`; captures
  ownership metadata right after a fresh/already-healthy connector is confirmed.
- `scripts/windows/stop-running-ai.ps1` — new `Stop-GarminConnectorComponent`, replacing the
  connector's use of the generic `Stop-Component` (Spring/relay still use it unchanged).
- `scripts/windows/RunningAI.Watchdog.ps1` — new `Get-ConnectorComponentState` (opt-in via a new
  `-ConnectorPort` parameter on `Get-ComponentStates`, `$null` default reproduces legacy behavior
  byte-for-byte); `Invoke-RecoveryAction`'s connector `PreStop` branch now ownership-aware.
- `scripts/windows/watch-running-ai.ps1` — passes `-ConnectorPort` into the two real
  `Get-ComponentStates` calls (the only change needed to activate the richer classification).
- `scripts/windows/Send-CtrlC.ps1` — comment only: documents the console-broadcast finding below.

## 2. 기존 문제의 원인

`start-running-ai.ps1` records `$proc.Id` from `Start-Process` into `.runtime\garmin-connector.pid` -
the PID of whatever `Start-Process` directly launched. On this machine, the connector's venv
`python.exe` (under `tools\garmin-connector\.venv`, a Python Install Manager-style launcher stub) does
not become the running interpreter in place; it spawns the real interpreter (a separate, globally
installed Python) as a **child** process, and that child is the one that actually binds port 8765.
`Get-TrackedProcessId`/`Test-ProcessIdentity` only ever examine the tracked PID's own command line, so
they had no way to recognize the child as "ours", and `RunningAI.Watchdog.ps1`'s
`Get-ProcessComponentState` would classify "tracked PID gone, something on the port" as
`DEGRADED`/`FOREIGN_PROCESS` even when the "something" was our own orphaned listener - permanently
blocking automatic recovery instead of restarting it.

## 3. 새로운 소유권 모델

See `docs/architecture/connector-process-ownership.md` for the full verdict table and metadata
format. Summary: `Get-RunningAiConnectorOwnership` resolves one of `SELF_OWNED` / `LAUNCHER_CHILD` /
`ORPHANED_MANAGED_PROCESS` / `FOREIGN_PROCESS` / `UNKNOWN_OWNER` / `DOWN`, backed by:
- a live parent/child + `CreationDate` check when both the tracked launcher and the listener are
  currently alive (no stored state needed), and
- an optional, purely additive `.runtime\<name>.owner.json` sidecar (captured once, at the moment a
  fresh connector start is confirmed healthy) that lets a **later** run recognize an orphaned listener
  after its launcher has since exited - PID-reuse-protected by requiring both the PID number **and**
  its recorded `CreationDate` to match (both sides normalized to UTC; a real bug here - comparing a
  UTC-stored expected time against CIM's local-time `CreationDate` without normalizing - was caught
  and fixed during isolated testing, see §8).

Only `SELF_OWNED`/`LAUNCHER_CHILD`/`ORPHANED_MANAGED_PROCESS` are ever acted on;
`FOREIGN_PROCESS`/`UNKNOWN_OWNER` are always refused, per the "UNKNOWN_OWNER는 절대 자동 종료하지
않는다" requirement.

## 4. 프로세스 관계 검증 방식

`Get-RunningAiProcessInfo` (`Get-CimInstance Win32_Process`) supplies `ParentProcessId`,
`ExecutablePath`, `CreationDate`, and (used only internally for marker matching, never printed
wholesale) `CommandLine`. Ownership requires **all** of: the tracked PID's own command line carrying
every repository marker (`Test-ProcessIdentity`, unchanged), the listener's `ParentProcessId` equaling
the tracked PID, and the listener's `CreationDate` at/after the tracked PID's - never port-ownership
alone, never a bare PID number.

## 5. 정상 종료/강제 종료 설계

`Stop-RunningAiConnectorManaged`: refuses outright for any non-manageable verdict; re-validates every
managed PID immediately before acting; sends Ctrl+C **individually to every verified managed PID**
(see §8 - relying on console-group broadcast from parent to child was found unreliable in isolated
testing, so each PID is signaled directly rather than hoping it cascades); waits for **both** the port
to free **and** every managed PID to exit (parent exiting alone is never treated as success); on
timeout, force-stops (`Stop-Process -Force`, one call per PID) only the still-alive, still-verified
managed PID(s) - never a process tree, never `taskkill /T /F`, never anything outside the verified
set (a sibling `conhost.exe` and an unrelated bystander process are both confirmed untouched in
testing). Every path returns a result that reports the actual final port/PID state.

## 6. 고아 프로세스 처리 정책

A tracked launcher that has exited, whose listener child still holds the port, resolves to
`ORPHANED_MANAGED_PROCESS` **only** when the sidecar metadata positively re-identifies that exact
listener (PID + creation time). Absent that metadata (e.g. a connector that was already running
before this change deploys, so has no sidecar yet), the same scenario resolves to `UNKNOWN_OWNER` -
safe-by-default, refused, never auto-managed, rather than silently broken or wrongly aggressive.

## 7. Watchdog 연동 영향

`Get-ComponentStates` gained one new optional parameter (`-ConnectorPort`, default `$null`); every
existing hand-built `Observation` in `Test-Watchdog.ps1` (which never knows about it) gets the exact
legacy `Get-ProcessComponentState` behavior, confirmed by the full existing watchdog suite passing
unchanged (see §9). Only `watch-running-ai.ps1`'s two real `Get-ComponentStates` calls pass it,
activating `Get-ConnectorComponentState`'s richer classification for live runs. `Get-RecoveryPlan`
itself - restart budget, dependency order, blocking - is **unmodified**; the connector's new
`ORPHANED_MANAGED_PROCESS` reason maps to the existing `UNHEALTHY` → restart-with-pre-stop path, and
`UNKNOWN_OWNER` maps to the existing `DEGRADED` → blocked path, reusing machinery `Get-RecoveryPlan`
already had for `PROCESS_ALIVE_HEALTH_FAILING`/`FOREIGN_PROCESS`.

## 8. 격리 테스트 결과

`scripts\windows\tests\Test-ConnectorOwnership.ps1` — **17/17 PASS**, stable across 5 repeated runs.
Uses only real, short-lived PowerShell test processes on temporary loopback ports (20000-40000) it
creates and tears down itself - never port 8765, never Python, never Garmin, never Docker. Covers:
normal launcher→child pair and PID mismatch; single-process (no split) `SELF_OWNED`; a reused/foreign
tracked PID and a creation-time-mismatched sidecar both correctly rejected; a foreign-repo marker
on the port; launcher-alive-no-listener (`DOWN`, not conflated with `FOREIGN_PROCESS`);
launcher-gone-with/without-matching-metadata (`ORPHANED_MANAGED_PROCESS` vs `UNKNOWN_OWNER`); graceful
stop (both PIDs gone, port freed); graceful-timeout → forced stop of only the verified PIDs (a
bystander process confirmed untouched); refusal to act on `UNKNOWN_OWNER`/`FOREIGN_PROCESS`;
cross-worktree marker isolation; metadata round-trip, corruption-is-treated-as-absent, and the live
start-time capture → later orphan resolution end-to-end.

Two real bugs were found and fixed **during** this testing, not assumed away:
1. **`Send-CtrlC.ps1`'s console-group broadcast does not reliably reach a child process** spawned the
   way this codebase's own `Start-Process` calls spawn one (redirected stdout/stderr, `WindowStyle
   Hidden`) - verified directly: Ctrl+C to the parent alone left the child alive in repeated trials.
   Fixed by signaling every managed PID individually (§5) rather than relying on propagation - this
   corrects an assumption asserted with unwarranted confidence during the Phase 6I-1.7A investigation.
2. **`CreationDate` UTC mismatch**: comparing a sidecar's UTC-stored time against a live
   `Win32_Process.CreationDate` (which CIM returns in local time) without normalizing both sides to
   UTC made every orphan-metadata match silently fail (off by the machine's UTC offset). Fixed in
   `Test-RunningAiCreationDateMatches`.

5 untestable-by-design scenarios from the work order's 15-item list: true OS-level PID reuse and
`ParentProcessId` reuse cannot be deterministically engineered in an automated test (Windows doesn't
expose a way to force it) - both are instead proven via the `CreationDate` cross-check itself (a
reused-but-wrong-creation-time PID is rejected, which is the actual mechanism standing between a
reused PID and a false match), documented inline in the test file as simulated rather than literal.

## 9. 전체 회귀 테스트 결과

All run to completion, zero failures, after this work:
- `Test-RunningAI.ps1` — PASS (includes the pre-existing "no hard-coded user/drive paths or
  credentials" hygiene check, which caught and required fixing two hardcoded `C:\running-ai...`
  literals in the new test file's fixtures - replaced with dynamically-built temp paths).
- `Test-Watchdog.ps1` — PASS, including the `FOREIGN_PROCESS`/restart-budget/dependency-order checks
  that exercise `Get-ComponentStates`/`Get-RecoveryPlan` directly - unaffected.
- `Test-ExternalRelay.ps1` — PASS (confirms no cross-impact from the shared `RunningAI.Watchdog.ps1`
  edits).
- `Test-ConnectorOwnership.ps1` — PASS (new, see §8).
- `git diff --check` — no whitespace errors.
- All touched/new `.ps1` files parse cleanly under Windows PowerShell 5.1 (`Parser]::ParseFile`) and
  were actually executed via `powershell.exe` (not pwsh) throughout, confirming 5.1 compatibility.
- Secret scan of the diff and new files — clean.
- `.runtime\*.owner.json` confirmed git-ignored (covered by the existing blanket `.runtime/` rule).
- No real `.pid` file, operational process, or PostgreSQL state was touched at any point - every test
  used synthetic PowerShell processes and temporary ports this session created and removed itself.
- One accidental regression caught and fixed during review: an earlier sed-style edit had introduced
  a UTF-8 BOM and one mis-indented line into `start-running-ai.ps1`; both were corrected before commit.

## 10. Commit SHA

(recorded after commit - see below)

## 11. main-3 / origin/main-3 상태

(recorded after push - see below)

## 12. 운영환경 불변 여부

**불변.** This entire phase was conducted in the development worktree
(`C:\Users\simjy\orca\workspaces\running-ai-github\main-3`), never Canonical
(`C:\running-ai-github`). No `start-/stop-/watch-running-ai.ps1` was ever run for real against any
live service; no real `.pid`/`.owner.json` file was created, read, or modified outside this worktree's
own test-created temp paths; Docker/PostgreSQL were never touched; `.env` was never touched; Watchdog
was never enabled or actually run for recovery. Only read-only `git`/parse/test invocations and the
isolated test suite's own synthetic processes ran.

## 13. Phase 6I-1.7B-2 권고 사항

1. **Live validation on the real connector** (separate, approved phase): with the real connector next
   restarted via `start-running-ai.ps1`, confirm the `.owner.json` sidecar is written, then verify a
   real orphan scenario resolves to `ORPHANED_MANAGED_PROCESS` and is recovered cleanly by the
   Watchdog - this phase deliberately could not and did not touch the live connector.
2. Consider extending the same ownership model to Spring if any future JVM-launcher-via-wrapper
   pattern is ever introduced (not needed today - Spring's `java -jar` is a direct, unsplit process).
3. Revisit the remaining Phase 6I-1.4 items noted in the 6I-1.7A investigation (restart-budget
   infinite-loop risk, Startup/Watchdog/manual-run concurrency, dev-worktree Compose project
   identification) - out of scope here, not re-verified in this phase.
4. The best-effort metadata-refresh path added to `start-running-ai.ps1`'s "already running" branch
   is shipped but has not been live-exercised (would require running the real start script); confirm
   it behaves as designed during the Phase 6I-1.7B-2 live validation above.
