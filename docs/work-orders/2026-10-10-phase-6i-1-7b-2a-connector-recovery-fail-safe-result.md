# Phase 6I-1.7B-2A — Connector Recovery Fail-safe & Lifecycle Contract — Result

## 1. 변경 파일

- `scripts/windows/RunningAI.Common.ps1` — `ExitCode.ConnectorStopIncomplete = 24` 추가.
- `scripts/windows/RunningAI.ConnectorOwnership.ps1` — `ManagedPidSnapshot`(분류 시점 PID+CreationDate),
  `New-RunningAiOwnershipResult`/`New-RunningAiStopOutcome` 헬퍼, 핸들 기반 강제 종료, `ResultCode`.
- `scripts/windows/RunningAI.Watchdog.ps1` — `Invoke-RecoveryAction` connector PreStop 검증별 분기,
  `Get-ConnectorComponentState`의 `OwnershipVerdict` 전 분기 부착.
- `scripts/windows/watch-running-ai.ps1` — 상태 JSON에 `connectorOwnership` 필드 추가.
- `scripts/windows/stop-running-ai.ps1` — Exit Code 계약(`ConnectorStopIncomplete`).
- `scripts/windows/tests/Test-ConnectorOwnership.ps1` — 기존 33개 유지 + 신규 다수(총 33개 PASS).
- `scripts/windows/tests/Test-Watchdog.ps1` — 상태 JSON 하위 호환성 테스트 1건 추가.

## 2. 해결된 결함

| 문제 | 수정 |
|---|---|
| 1. ManagedPids=0/FOREIGN_PROCESS/UNKNOWN_OWNER일 때 Watchdog이 아무 조치 없이 `start-running-ai.ps1`로 진행 | `Invoke-RecoveryAction` connector PreStop이 verdict별 명시적 switch: `DOWN`만 통과, 나머지는 전부 `return $false` |
| 2. 소유권 판정~실제 종료 사이 PID 교체 가능성 | `ManagedPidSnapshot`(분류 시점 CreationDate)을 Stop 함수의 베이스라인으로 사용 — 함수 진입 시점이 아니라 **분류 시점**에 앵커링 |
| 3. 부분 종료를 성공으로 오판 | `Test-RunningAiConnectorStopWasClean`을 start/stop/Watchdog 3곳 모두 유일한 성공 판정 기준으로 통일, `stop-running-ai.ps1`은 실패 시 Exit Code 24 |

테스트 작성 중 추가로 발견·수정한 실제 버그 2건:
- **단일 관리 PID가 "존재하되 CreationDate 불일치"인 경우 `already-gone`으로 오분류되던 버그** — liveness(생존 여부)와 identity(신원 일치) 검사를 분리해, "전부 죽음=ALREADY_DOWN"과 "살아있지만 신원 불일치=OWNERSHIP_CHANGED"를 명확히 구분하도록 수정.
- **`New-RunningAiStopOutcome -Result (if (...) {...} else {...})`** — PowerShell에서 `if`는 문(statement)이지 식(expression)이 아니어서 괄호 안 인라인 사용 시 "'if' is not recognized as a cmdlet" 오류 발생 — 변수에 먼저 대입하도록 수정.

## 3. 소유권 스냅샷 설계

`Get-RunningAiConnectorOwnership`의 모든 반환 경로가 `New-RunningAiOwnershipResult`를 통과하며, `ManagedPids`의 각 PID에 대해 그 순간 관찰한 `CreationDate`를 `ManagedPidSnapshot`에 함께 기록한다. `Stop-RunningAiConnectorManaged`는 이 스냅샷을 그대로 베이스라인으로 사용(함수 진입 시 재조회하지 않음) — 분류 시점과 종료 호출 시점 사이의 간극에서 발생하는 PID 재사용도 탐지 가능. 스냅샷이 없거나 `ManagedPids`와 개수가 다른 Ownership 객체는 무조건 거부(`REFUSED_UNKNOWN_OWNER`).

## 4. PreStop 차단 검증

`Invoke-RecoveryAction`의 connector 분기:
```
switch ($ownership.Verdict) {
    'DOWN' { }  # 유일하게 통과 — 아래 Start 로직으로 진행
    { SELF_OWNED/LAUNCHER_CHILD/ORPHANED_MANAGED_PROCESS } {
        ManagedPids=0 이면 return $false
        Stop 시도 → Test-RunningAiConnectorStopWasClean 아니면 return $false
    }
    default { return $false }  # FOREIGN_PROCESS, UNKNOWN_OWNER, 기타 전부
}
```
실제 Invoke-RecoveryAction 실행(특히 BLOCK 되지 않는 경로)은 실제 `start-running-ai.ps1`을 기동시켜 Docker/PostgreSQL을 건드릴 위험이 있어, 이 로직은 **소스 레벨 검증**으로만 테스트했다(기존 코드베이스에 이미 있는 "source check" 패턴과 동일 — `Invoke-RecoveryAction routes external-relay...(source check)`).

## 5. 종료 결과/Exit Code 계약

`New-RunningAiStopOutcome`이 기존 `Result`(`graceful`/`forced`/`already-gone`/`refused`/`ownership-changed`/`orphan-remaining`)는 그대로 유지하면서 `ResultCode` 필드를 추가:

| Result | ResultCode |
|---|---|
| already-gone | ALREADY_DOWN |
| graceful / forced | STOPPED |
| refused | REFUSED_UNKNOWN_OWNER |
| ownership-changed | OWNERSHIP_CHANGED |
| orphan-remaining (RemainingPids 있음) | PROCESS_REMAINING |
| orphan-remaining (RemainingPids 없음) | PORT_STILL_OCCUPIED |
| 그 외/예상치 못한 값 | STOP_FAILED |

`stop-running-ai.ps1`: `Stop-GarminConnectorComponent`가 `$true`/`$false`를 반환하도록 변경, 메인 스크립트는 `$connectorStopped`가 거짓이면 `exit $ExitCode.ConnectorStopIncomplete`(24) — 기존 `exit $ExitCode.Ok`(0) 경로는 유지.

## 6. Watchdog JSON 변경사항

`watch-running-ai.ps1`의 상태 JSON에 `connectorOwnership: { verdict }` 필드 추가(없으면 `null`). 기존 `components`/`blocked`/`restartBudget`/`checkedAt`/`overall`/`garminHint`/`lastAction` 필드는 형태 변경 없음 — Test-Watchdog.ps1에 하위 호환성 테스트로 고정. CommandLine·인증정보·전체 PID 목록은 기록하지 않는다.

## 7. 신규 테스트 결과

`Test-ConnectorOwnership.ps1`: 기존 27 + 신규 다수 = **33/33 PASS**(안정적으로 재확인). 신규 테스트 요지:
- Invoke-RecoveryAction connector PreStop 게이트 구조(소스 검증) 2건
- 분류 시점 CreationDate 변조 시 OWNERSHIP_CHANGED(실제 버그 수정을 직접 검증)
- 원래 Listener가 사라지고 무관한 새 프로세스가 같은 포트를 점유 — 새 프로세스는 절대 건드리지 않음(ALREADY_DOWN으로 정확히 분류됨을 확인하며 당초 테스트 기대값의 오류도 함께 수정)
- ResultCode 매핑 전체 벡터 검증(2건)
- ManagedPidSnapshot 누락 시 무조건 거부
- stop-running-ai.ps1 Exit Code 계약(소스 검증)

`Test-Watchdog.ps1`: 신규 1건 — 상태 JSON 하위 호환 + `connectorOwnership.verdict` 추가 확인.

STEP 5의 8번(Force 직전 PID 재사용)은 5/6번과 동일한 `Test-RunningAiManagedPidStillValid` 메커니즘을 공유하며, 실제 OS 수준 PID 재사용을 정확한 타이밍에 강제로 재현하는 것은 6I-1.7B-1R에서와 동일한 구조적 한계로 불가능 — 코드 검토 및 메커니즘 공유로 커버리지를 대체함을 문서화.

## 8. 전체 회귀 결과

`Test-RunningAI.ps1` / `Test-Watchdog.ps1` / `Test-ExternalRelay.ps1` / `Test-ConnectorOwnership.ps1` 전부 PASS. `git diff --check` 클린. 비밀정보 검색 클린. 31개 스크립트 전체 PowerShell 5.1 파싱 확인. 테스트 종료 후 잔여 프로세스/임시 디렉터리 없음 확인.

## 9. Commit SHA

`4f7beb0` (`fix(windows): fail closed on unsafe connector recovery`, `8f622cd` 위)

## 10. main-3/origin/main-3 일치

Fast-forward only로 `origin/main-3`에 Push 완료, Merge Commit 없음, `origin/main`은 미변경.

## 11. 운영환경 불변

전 과정 개발 Worktree에서만 수행. Canonical(`C:\running-ai-github`) HEAD `f6a21358...` 불변, Working Tree clean, PostgreSQL 컨테이너 ID/Health 불변 확인. 실제 Connector(8765)/Spring(18080)/Relay(17845)/`start-running-ai.ps1`/Docker Compose/Watchdog 활성화 전부 미수행. `Test-Watchdog.ps1`의 기존 선례 테스트(및 이번에 추가한 동형 테스트)만 `-NoRecovery`로 실제 포트 8765를 읽기 전용 조회하며, 이는 이번 작업 이전부터 승인되어 있던 동일 패턴이다.

## 12. 6I-1.7B-2B 진입 가능 여부

**가능.** 실제 python.exe 런처/인터프리터 쌍 및 실제 `watchdog-status.json`에 대한 라이브 검증(별도 승인 필요)과, 6I-1.7A에서 식별된 나머지 결함(재시작 예산 무한 반복 위험, Startup/Watchdog/수동 실행 동시성, 개발 Worktree Compose 프로젝트 식별 오류)은 여전히 범위 밖으로 남아있다.
