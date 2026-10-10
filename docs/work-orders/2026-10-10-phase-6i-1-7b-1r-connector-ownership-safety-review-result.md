# Phase 6I-1.7B-1R — Connector Ownership Safety Review & Corrections — Result

## 1. 확인된 결함별 수정 결과

| # | 결함 | 수정 |
|---|------|------|
| A | `Set-RunningAiConnectorOwnerMetaFromLive`가 Markers/관계/CreationDate 순서/재검증 없이 기록 | `-Markers` 필수화, launcher identity + self-or-verified-child + CreationDate 순서 + 기록 직전 3중 재확인(launcher CreationDate, listener PID+CreationDate) 모두 통과해야 기록 |
| B-1 | 종료 중 PID 재검증 부재(baseline 없이 Ownership.ManagedPids를 그대로 신뢰) | PID+CreationDate 베이스라인을 스냅샷하고 신호 전·대기 후·강제종료 직전·최종판정 전 4곳에서 전부 재검증 |
| B-2 | **실제 버그**: pre-signal 체크가 `baseline.Count`(이미 축소된 집합)와 비교해 원래 요청된 PID 일부가 처음부터 사라진 경우를 절대 탐지 못함 | 원래 `Ownership.ManagedPids.Count`와 비교하도록 수정 — 신규 테스트로 고정 |
| C | Send-CtrlC.ps1이 "PID별 호출 = PID별 전달"로 오인될 여지, 비관리 프로세스 보호 없음 | 주석으로 브로드캐스트 범위를 명시하고, `-AllowedProcessIds`(opt-in, 콤마 구분 문자열) 지정 시 `GetConsoleProcessList`로 대상 콘솔의 실제 부착 PID를 확인해 비관리 프로세스가 하나라도 있으면 신호를 보내지 않고 거부(종료 코드 2). 파라미터 생략 시(Spring/Relay) 기존 동작 100% 그대로 |
| D-1 | `Remove-PidFile`이 결과와 무관하게 무조건 호출(start/stop 스크립트 모두) | `Test-RunningAiConnectorStopWasClean` 공용 함수 — `graceful`/`forced`/`already-gone` **and** `PortFreed` **and** `RemainingPids.Count=0`일 때만 제거 |
| D-2 | Watchdog `Invoke-RecoveryAction`이 PreStop 실패와 무관하게 항상 `start-running-ai.ps1` 실행 | 미완료 시 `return $false`로 즉시 중단, PID 파일/메타데이터 보존 |
| E | Health=true일 때 소유권 정보가 전혀 노출되지 않음; 여러 LISTEN PID/포트 조회 오류가 DOWN으로 뭉뚱그려짐 | `Get-ConnectorComponentState`가 Health=true에서도 `.OwnershipVerdict`를 부가 정보로 첨부(State/Reason/재시작 정책 불변); `Get-RunningAiListenerProcessIds`가 Ok/Pids를 분리 반환해 "없음"·"모호함(2+)"·"조회 실패"를 구분 → DOWN/UNKNOWN_OWNER/UNKNOWN_OWNER로 각각 매핑 |

테스트 작성 중 추가로 발견·수정한 실제 버그 2건(둘 다 운영 영향 없음, 이번 작업 범위 내 발견):
- `[ordered]@{}`의 `[int]` 키 인덱서가 위치 인덱스로 해석되어 `ArgumentOutOfRangeException` 발생 → 일반 `Hashtable`로 교체.
- "리스너 없음" 판정을 로캘 의존적인 예외 메시지(`'No matching'`, 영어)로 매칭 → 이 머신은 한국어 로캘이라 전혀 매칭되지 않아 모든 "포트 비어있음" 케이스가 오탐으로 UNKNOWN_OWNER 처리됨 → 로캘 무관 `FullyQualifiedErrorId`(`CmdletizationQuery_NotFound`)로 교체.

## 2. 소유권 판정 절차

`Get-RunningAiConnectorOwnership`: 포트 질의(Ok/개수 0·1·2+ 구분) → DOWN/UNKNOWN_OWNER 조기 반환 → 리스너 신원 확인 → TrackedPid 없음/있음 분기 → (있음) 살아있고 우리 것이면 실시간 부모-자식+CreationDate 순서 검증 → 실패 시 사이드카(name+repoRoot+port+PID+CreationDate 전부 일치해야 함) → 모두 실패 시 listener 자체 마커 확인 → FOREIGN_PROCESS/UNKNOWN_OWNER. 상세 표는 `docs/architecture/connector-process-ownership.md` 참조.

## 3. Sidecar 기록 안전성

`Set-RunningAiConnectorOwnerMetaFromLive`는 (1) launcher identity, (2) listener가 self 또는 실시간 검증된 자식, (3) CreationDate 순서, (4) 기록 직전 3중 재확인을 **전부** 통과해야 `Write-RunningAiOwnerMeta`에 도달 — 실패 시 기존 파일은 전혀 건드리지 않는다(새 테스트로 "무관한 리스너 → 기록 거부, 파일 생성 안 됨" 확인). 조회 측에서도 `name`/`repoRoot`/`port`/PID/CreationDate 5개를 모두 검증하도록 강화(새 테스트 2건: repoRoot 불일치, name 불일치 각각 UNKNOWN_OWNER로 거부).

## 4. Graceful/Force Kill 안전성

- Graceful: 베이스라인 스냅샷 → pre-signal 재검증(원본 개수와 비교, 버그 수정됨) → 각 관리 PID에 개별 Ctrl+C(+콘솔 공유 안전성 검사) → port-free AND all-gone 조건으로 대기.
- Force: 타임아웃 시 pre-force 재검증 — CreationDate 불일치(PID 재사용) 발견 시 **아무것도 죽이지 않고** `ownership-changed` 반환. 검증 통과분만 개별 `Stop-Process -Force`.
- 최종판정: port-free와 전원-소멸을 재확인 후에만 성공 분류, `RemainingPids.Count=0` 단독 판단 금지.

## 5. PID 파일 보존 정책

`Test-RunningAiConnectorStopWasClean`(공용, 3곳에서 동일 호출)이 `graceful`/`forced`/`already-gone` **and** `PortFreed` **and** `RemainingPids.Count=0`일 때만 `true`. `refused`/`ownership-changed`/`orphan-remaining`은 전부 `false` → PID 파일·`.owner.json` 보존, 사유를 `Write-Step`으로 기록.

## 6. Watchdog PreStop 실패 처리

`Invoke-RecoveryAction`의 connector PreStop이 클린하지 않으면 `Remove-PidFile`을 호출하지 않고 **즉시 `return $false`** — 동일 틱에서 `start-running-ai.ps1`을 호출하지 않는다(소스 검사 테스트로 고정). 재시작 예산 자체(윈도/카운트 로직)는 이번 단계에서 변경하지 않음 — 6I-1.7B-1에서 확정한 범위를 유지.

## 7. 추가 테스트 결과

`Test-ConnectorOwnership.ps1`: 기존 17개 + 신규 10개 = **27/27 PASS**, 5회 연속 안정. 신규 테스트:
1. 무관한 Listener → Sidecar 기록 거부(파일 미생성)
2. Launcher/Listener 부모 관계 불일치 → LAUNCHER_CHILD 아님
3. Sidecar repoRoot 불일치 → UNKNOWN_OWNER
4. Sidecar name 불일치 → UNKNOWN_OWNER
5. 포트 조회 실패(잘못된 포트 번호) → UNKNOWN_OWNER, DOWN 아님
6. 신호 전 이미 일부 PID 소실 → `ownership-changed`, 생존 PID 미접촉(버그 수정 직접 검증)
7. `Test-RunningAiConnectorStopWasClean`이 `PortFreed`를 `RemainingPids=0`과 독립적으로 요구
8. Watchdog `Invoke-RecoveryAction` 소스 검사(클린하지 않으면 Start 전 `return $false`)
9. Send-CtrlC.ps1이 비관리 프로세스가 콘솔을 공유할 때 거부(종료 코드 2) — bystander/launcher/listener 전원 생존 확인
10. `-AllowedProcessIds` 생략 시 기존 무조건 전송 동작 유지(Spring/Relay 회귀 방지)

STEP F 15개 항목 중 "두 개 이상의 LISTEN PID"(#9)는 Windows가 한 포트에 서로 다른 프로세스의 동시 바인딩을 애초에 허용하지 않아 실제 프로세스로 재현 불가능 — `Get-RunningAiListenerProcessIds`의 분기 로직은 코드 검토로 확인했으나 실행 테스트는 작성하지 않음(6I-1.7B-1의 "실제 PID 재사용" 항목과 동일한 구조적 한계로 문서화).

## 8. 전체 회귀 결과

`Test-RunningAI.ps1` / `Test-Watchdog.ps1` / `Test-ExternalRelay.ps1` / `Test-ConnectorOwnership.ps1` / `Test-CoachOperator.ps1` / `Test-CloudflaredSetup.ps1` / `Test-TailscaleSetup.ps1` 전부 PASS. `git diff --check` 클린. 변경/신규 파일 비밀정보 검색 클린. 수정된 6개 스크립트 전부 Windows PowerShell 5.1(`Parser]::ParseFile` + 실제 `powershell.exe` 실행)로 파싱/실행 확인. 테스트 종료 후 잔여 프로세스/임시 디렉터리 없음을 확인(디버깅 중 생성된 임시 폴더 일부 수동 정리).

## 9. Commit SHA 및 원격 브랜치 상태

(커밋 후 기록)

## 10. 운영환경 불변 확인

전 과정 개발 Worktree에서만 수행. Canonical(`C:\running-ai-github`) HEAD `f6a21358...` 불변, Working Tree clean, PostgreSQL 컨테이너 ID/Health 불변(`8ff2a3adc495...`, healthy) 확인. 실제 Connector(8765)/Spring(18080)/Relay(17845)에는 어떤 호출도 발생하지 않음 — 모든 테스트는 자체 생성한 임시 루프백 포트와 PowerShell 테스트 프로세스만 사용.

## 11. 6I-1.7B-2 진행 가능 여부

**가능.** 다만 다음은 실제 Connector 재시작이 포함되는 별도 승인 하에 라이브 검증 필요:
- 새 메타데이터 검증 체인이 실제 venv 런처/인터프리터 쌍에서도 기록에 성공하는지
- 강화된 Send-CtrlC 콘솔 안전성 검사가 실제 python.exe 프로세스 쌍에서도 올바르게 동작하는지(지금까지는 PowerShell 프로세스 쌍으로만 검증)
- Watchdog의 `.OwnershipVerdict` 진단 필드가 실제 `watchdog-status.json`에 올바르게 나타나는지
