# Phase 6I-1.7B-2B — Windows Watchdog Recovery Policy Hardening — 완료 보고

작업 지시서 원문: `2026-10-10-phase-6i-1-7b-2b-watchdog-recovery-policy-hardening-instruction.md`

## 1. 변경 파일

| 파일 | 변경 내용 |
|---|---|
| `scripts/windows/RunningAI.Common.ps1` | STEP F: `Get-RunningAiRuntimeLockName`, `Enter-RunningAiRuntimeLock`, `Exit-RunningAiRuntimeLock` 추가. `$ExitCode`에 `Busy = 25` 추가. `Get-TrackedProcessId`에 `-ReadOnly` 스위치 추가(STEP G). |
| `scripts/windows/RunningAI.ConnectorOwnership.ps1` | STEP A-3: `LAUNCHER_ALIVE_NO_LISTENER` 신규 판정 추가. STEP A-2: `already-gone` 시 포트가 재점유된 경우 메타데이터 보존(`PortFreed` 조건부 삭제). `Stop-RunningAiConnectorManaged`의 `$manageable` 목록에 `LAUNCHER_ALIVE_NO_LISTENER` 포함. |
| `scripts/windows/RunningAI.Watchdog.ps1` | STEP C/D: `New-EmptyLongTermState`, `Get-LongTermFailureCount`, `Update-LongTermFailureWindow`, `Add-LongTermFailure`, `Update-RunningAiLongTermLockout`, `Test-RunningAiComponentLockedOut`, `Clear-RunningAiComponentLockout` 신규. `Read-WatchdogState`/`Save-WatchdogState`를 상태 버전 2(`longTerm` 키)로 확장, `-ReadOnly` 지원. STEP A-1: `Get-ConnectorComponentState`/`Get-ComponentStates`/`Invoke-WatchdogRecovery`의 모든 내부 호출에 `ConnectorPort` 일관 전달. STEP B: `Get-RunningAiComponentFromExitCode` 신규, `Invoke-RecoveryAction`에서 실제 Exit Code 기반 컴포넌트와 계획된 컴포넌트가 다르면 `FAILED_ACTUAL_COMPONENT_<X>_EXIT_<code>` 로그. STEP E: `Invoke-WatchdogRecovery`를 core-chain 루프와 independent(relay) 루프로 완전히 분리. STEP G: `Get-DefaultProbes`/`Get-RuntimeObservation`에 `-ReadOnly` 스위치 추가(클로저로 `${function:...}` 캡처 방식 재사용). |
| `scripts/windows/start-running-ai.ps1` | STEP F: 시작 전 `Enter-RunningAiRuntimeLock` 획득, 실패 시 `exit $ExitCode.Busy`, `finally`에서 해제. STEP A-3: 신규 Connector 시작 전 `LAUNCHER_ALIVE_NO_LISTENER` 상태의 기존 Launcher를 먼저 정리. |
| `scripts/windows/stop-running-ai.ps1` | STEP F: 동일한 Runtime Lock 적용(시작 스크립트와 동일 패턴). |
| `scripts/windows/watch-running-ai.ps1` | `-LongTermWindowHours`/`-LongTermMaxFailures` 파라미터 추가, `-ReadOnly:$DryRun`을 관찰/상태읽기 경로에 전달, status JSON에 `longTermLockout` 섹션 추가. |
| `scripts/windows/clear-watchdog-lockout.ps1` | (신규) 장기 Lockout 해제 전용 운영자 스크립트. `-Force` 없이는 Dry-run 미리보기만 수행하며, 어떤 자동화 코드에서도 호출되지 않음. |
| `scripts/windows/tests/Test-ConnectorOwnership.ps1` | `LAUNCHER_ALIVE_NO_LISTENER` 신규 판정에 맞춰 기존 테스트 이름/단언 갱신, "진짜 죽은 추적 PID는 DOWN" 보강 테스트 추가. |
| `scripts/windows/tests/Test-WatchdogRecoveryPolicy.ps1` | (신규) 26개 Check 블록 — 장기 Lockout, 상태 버전 마이그레이션, Relay 독립 복구, ConnectorPort 전파, Runtime Lock(실제 분리 프로세스 포함), DryRun 읽기 전용 등. |
| `docs/architecture/connector-process-ownership.md` | "Phase 6I-1.7B-2B hardening" 섹션 추가 (기존 2A 섹션과 동일한 형식). |

## 2. 장애 귀속 정책 (STEP B)

요구사항 전체(컴포넌트별 Executor 완전 분리)를 이번 단계에서 전부 구현하지는 않았다. 대신:

- `Invoke-RecoveryAction`이 하위 프로세스의 **실제 Exit Code**를 `Get-RunningAiComponentFromExitCode`로
  역매핑하여, 계획된 컴포넌트와 실제로 실패를 가리키는 컴포넌트가 다를 경우
  `FAILED_ACTUAL_COMPONENT_<X>_EXIT_<code>` 형태로 로그에 명시적으로 남긴다.
- 단, 이 개선은 **로그 레벨**에 한정된다. 기존 `Runner`의 boolean 성공/실패 계약과 복구 예산 소비 주체는
  변경하지 않았다. `Test-Watchdog.ps1`을 포함한 기존 테스트 전체가 이 계약에 의존하고 있어, 이를
  재구조화하면 이번 Phase의 범위를 벗어나는 회귀 위험이 커진다고 판단했다.
- Relay는 이미 전용 `Invoke-RecoveryAction` 분기(별도 스크립트 경로)를 사용하므로 Relay 실패는 Spring/
  Connector 예산을 절대 소비하지 않는다(기존 설계, 이번 단계에서 유지·검증만 추가).
- 완전한 컴포넌트별 Executor 분리는 다음 단계(6I-1.7C 또는 그 이후)로 명시적으로 이월한다.

## 3. 장기 Lockout 상태 설계 (STEP C)

- 단기 정책: 변경 없음 (10분 이동 창, 최대 3회).
- 장기 정책(신규): 24시간 이내 실패 6회 이상 시 해당 컴포넌트만 `LockedOutSince` 타임스탬프를 기록하고
  영구 Lockout 상태로 전환한다. 이 값은 **자동으로 해제되지 않는다** — 실패 기록이 24시간 창 밖으로
  밀려나도 `LockedOutSince` 자체는 그대로 유지된다.
- `Get-RecoveryPlan`은 단기 예산을 확인하기 전에 먼저 Lockout 여부를 검사하여, Lockout된 컴포넌트는
  `LOCKED_OUT` 사유로 차단하고 다른 컴포넌트의 정상 복구에는 영향을 주지 않는다(코어 체인/Relay 양쪽
  모두 검증).
- 해제는 신규 `clear-watchdog-lockout.ps1`(운영자 명시 실행 전용, `-Force` 없이는 Dry-run)로만 가능하며,
  어떤 자동화 코드도 이를 호출하지 않는다. 이번 단계에서 실제 운영 Lockout을 해제한 적은 없다(실제
  운영 `watchdog-state.json`에 접근하지 않았다).
- 재시도 한도는 `watch-running-ai.ps1 -LongTermWindowHours`/`-LongTermMaxFailures`로 설정 가능.

## 4. 상태 JSON 버전 및 마이그레이션 (STEP D)

- `watchdog-state.json`은 이제 `version: 2`로 저장되며, 기존 `restarts` 섹션 옆에 `longTerm`
  섹션(`{failures: [...], lockedOutSince}`, 컴포넌트별)이 추가된다.
- Version 1 파일(또는 일부 컴포넌트만 `longTerm` 항목이 없는 파일)을 로드하면: 기존 재시작 기록은
  **전혀 손실 없이** 그대로 보존되고, `longTerm` 정보가 없는 컴포넌트만 새롭고 잠기지 않은 상태로
  초기화된다.
- 구현 중 발견한 버그: `$obj.restarts.$c` / `$obj.longTerm` / `$obj.longTerm.$c`처럼 점(dot) 표기법으로
  존재하지 않는 속성에 접근하면, 이 파일의 `Set-StrictMode -Version Latest` 하에서
  `PropertyNotFoundException`이 발생하여 바깥쪽 `catch`가 이를 "손상된 파일"로 오인/Quarantine하는
  문제가 있었다. `$obj.PSObject.Properties[...]` 패턴(이 코드베이스에서 `Get-RunningAiErrorDetails`가
  이미 쓰는 것과 동일한 안전한 읽기 패턴)으로 수정하여 해결했다 — 수정 전까지는 신규 테스트 1건이
  바로 이 버그로 실패했다(아래 9번 항목 참고).
- 손상된 파일: 기존과 동일하게 `.corrupt`로 Quarantine하고 `Available=false`를 반환한다(단,
  `-ReadOnly`일 때는 Quarantine을 건너뛴다 — STEP G).
- 파일 손상/누락이 재시작 한도를 무조건 초기화하지 않는다는 점: "파일 없음(최초 실행)"과 "파일
  손상"을 분리해서 처리하며, 손상 시에는 `Available=false`로 복구 자체를 보류하므로 한도가 암묵적으로
  리셋되는 효과가 생기지 않는다(기존 동작 유지).
- 실제 운영 `watchdog-state.json`은 이번 단계에서 전혀 읽거나 쓰지 않았다.

## 5. Relay 독립 복구 검증 (STEP E)

`Invoke-WatchdogRecovery`를 core-chain 루프와 independent(Relay) 루프로 완전히 분리했다. 신규 테스트로
다음을 모두 확인함(Mock Observe/Runner 주입, 실제 서비스 미사용):

- Spring 실패 중 같은 호출에서 Relay 복구 성공
- Connector 실패 중 같은 호출에서 Relay 복구 성공(대칭 케이스)
- Relay 실패가 core chain(Spring 등) 복구를 막지 않음
- core-chain 실패가 해당 컴포넌트 자신의 예산만 소비하고 다른 컴포넌트 예산에 영향 없음
- `-ConnectorPort`가 두 루프의 모든 내부 `Get-ComponentStates` 호출에 일관되게 전달됨(회귀 테스트,
  STEP A-1)

## 6. 동시 실행 Lock 구조 (STEP F)

- `RunningAI.Common.ps1`의 `Enter-RunningAiRuntimeLock`/`Exit-RunningAiRuntimeLock`이 Runtime
  디렉터리 경로를 해시한 이름의 Named Mutex를 래핑한다(테스트용 `RUNNING_AI_TEST_RUNTIME_DIR`과 실제
  운영 경로가 절대 충돌하지 않음).
- `start-running-ai.ps1`과 `stop-running-ai.ps1`만 이 Lock을 획득한다(실행 시작 직후 획득, `finally`에서
  해제). 획득 실패 시 서비스 상태를 전혀 바꾸지 않고 `ExitCode.Busy`(25)로 즉시 종료한다.
- **Watchdog는 이 Lock을 절대 획득하지 않는다.** Watchdog는 시작/종료 로직을 자신의 in-process 함수로
  직접 수행하며 `start-running-ai.ps1`/`stop-running-ai.ps1`을 자식 프로세스로 호출하지 않으므로, 작업
  지시서가 경고한 "Watchdog가 Lock을 쥔 채 동일 Lock을 다시 요구하는 자식을 기다리는" Deadlock 경로
  자체가 존재하지 않는다. 이는 소스 검사 테스트로 고정되어 있다(Watchdog/watch-running-ai.ps1 소스에
  `Enter-RunningAiRuntimeLock` 호출이 없음을 확인).
- 검증: 실제로 분리된 PowerShell 프로세스 2개(Holder/Contender)를 스폰하여 (a) Holder가 Lock을 쥔 동안
  Contender가 거부됨, (b) Holder가 해제하면 즉시 획득 가능(영구 Deadlock 아님), (c) Holder가 비정상
  종료(Abandoned Mutex)해도 이후 획득 가능함을 확인했다.
- 테스트 설계상 주의점(디버깅 중 발견): 테스트 파일 자신은 `RunningAI.Watchdog.ps1`/
  `RunningAI.Common.ps1`을 파일 최상단에서 **한 번만** dot-source하므로, 그 시점 이후에
  `RUNNING_AI_TEST_RUNTIME_DIR`을 설정해도 테스트 프로세스 자신의 `$script:RuntimeDir`(및 그로부터
  계산되는 Lock 이름)은 이미 고정되어 있다. 따라서 "실제 분리 프로세스와의 경합" 테스트는 Contender도
  반드시 별도 스폰 프로세스로 만들어야 하며(같은 환경변수를 상속해 새로 dot-source), 테스트 프로세스
  자신이 in-process로 `Enter-RunningAiRuntimeLock`을 호출해 비교하면 서로 다른 Lock 이름을 계산해
  영원히 경합하지 않는다 — 최초 구현에서 바로 이 실수로 테스트가 실패했고, Contender를 별도 프로세스로
  교체하여 수정했다(9번 항목 참고).

## 7. DryRun 읽기 전용 검증 (STEP G)

- `Get-TrackedProcessId -ReadOnly`: 유효하지 않은(다른 프로세스가 재사용했거나 죽은) PID 파일을
  "없음"으로 보고하되 파일 자체는 삭제하지 않는다. `-ReadOnly` 없이 호출하면 기존과 동일하게 삭제한다
  (회귀 테스트로 두 경로 모두 확인).
- `Read-WatchdogState -ReadOnly`: 손상된 상태 파일을 "사용 불가"로 보고하되 `.corrupt`로 이동(Quarantine)
  하지 않는다. `-ReadOnly` 없이는 기존과 동일하게 Quarantine한다.
- `Get-DefaultProbes -ReadOnly` / `Get-RuntimeObservation -ReadOnly`가 위 두 스위치를 `watch-running-ai.ps1
  -DryRun`이 쓰는 모든 프로세스 존재 확인 클로저까지 전달한다.
- 확인된 DryRun 금지 동작: 파일 생성/수정/삭제, PID 파일 정리, 상태 파일 Quarantine. Docker Compose
  실행, 서비스 시작/종료, Garmin API 동기화는 애초에 DryRun 관찰 경로에서 호출되지 않는 구조이며 이번
  단계에서도 호출 경로를 추가하지 않았다(코드 검토로 확인, 별도 신규 테스트는 추가하지 않음 — 기존
  `Test-Watchdog.ps1`의 "dry run executes nothing and writes no files" 테스트가 이미 이를 포괄).

## 8. Connector 잔여 결함 보완 (STEP A)

- A-1(ConnectorPort 전파 불일치): 수정 완료, 회귀 테스트 추가.
- A-2(already-gone 메타데이터 조기 삭제): 수정 완료 — 포트가 재점유된 경우 메타데이터 보존, 회귀
  테스트 추가(실제 스폰 프로세스로 포트 점유 재현).
- A-3(DOWN과 생존 Launcher 혼동): `LAUNCHER_ALIVE_NO_LISTENER` 신규 판정으로 분리, Watchdog PreStop과
  `start-running-ai.ps1`의 수동 시작 경로 양쪽에 반영. 기존 "Launcher 생존 시 DOWN" 테스트는 새 판정에
  맞게 이름과 단언을 갱신했고, "진짜 죽은 PID는 여전히 DOWN"을 확인하는 신규 테스트를 추가했다.

## 9. 신규 테스트 및 전체 회귀 결과 (STEP H/I)

신규 파일 `Test-WatchdogRecoveryPolicy.ps1` (26개 Check): 장기 Lockout 임계값/컴포넌트별 격리/창 만료
후 지속성, `Get-RecoveryPlan`의 LOCKED_OUT 차단(`-LongTermState` 유무 양쪽), `Clear-RunningAiComponentLockout`,
상태 v1→v2 마이그레이션, v2 라운드트립, Watchdog 재시작을 모사한 Lockout 지속성, 손상 상태 파일
ReadOnly/비ReadOnly, 오래된 PID 파일 ReadOnly/비ReadOnly, Relay-독립성 3종(대칭 케이스 포함),
core-chain 예산 격리, ConnectorPort 전파 회귀, `LAUNCHER_ALIVE_NO_LISTENER`/plain DOWN 실프로세스
테스트, already-gone 포트 재점유 메타데이터 보존(실프로세스), Runtime Lock 3종(실분리 프로세스 포함),
Watchdog가 Lock을 절대 획득하지 않는다는 소스 검사, start/stop 스크립트가 Lock을 획득/해제한다는 소스
검사, Exit Code → 컴포넌트 매핑.

**디버깅 과정에서 발견·수정한 버그 2건 (최초 구현 시점에는 실패했으나 현재는 모두 PASS):**

1. v1 상태 마이그레이션 테스트가 `assertion returned false`로 실패 — 원인은 위 4번 항목에 기술한
   `Set-StrictMode` + dot-notation 속성 접근 버그(`RunningAI.Watchdog.ps1`의 `Read-WatchdogState`).
   `PSObject.Properties[...]` 패턴으로 수정.
2. "Enter-RunningAiRuntimeLock refuses while a REAL SEPARATE process holds the same-named lock" 테스트가
   실패 — 원인은 위 6번 항목에 기술한 테스트 설계 문제(테스트 프로세스 자신의 Lock 이름이 임시
   RuntimeDir을 반영하지 못함). Contender도 별도 스폰 프로세스로 교체하여 수정(제품 코드는 변경 없음,
   테스트 파일만 수정).

두 버그 모두 수정 후 `Test-WatchdogRecoveryPolicy.ps1`을 2회 연속 단독 실행하여 26/26 PASS, Exit 0을
확인했다.

**전체 회귀 실행 결과 (`Test-RunningAI.ps1`, `Test-Watchdog.ps1`, `Test-ExternalRelay.ps1`,
`Test-ConnectorOwnership.ps1`, `Test-WatchdogRecoveryPolicy.ps1`):**

4개 파일은 Exit 0으로 전부 PASS. `Test-ConnectorOwnership.ps1`에서 **이번 Phase와 무관한 2건의
실패**를 발견했다 — "graceful stop: Ctrl+C reaches both launcher and child..."와 "Send-CtrlC.ps1
omitting -AllowedProcessIds performs the legacy unconditional send...". 정직하게 보고한다:

- 두 테스트 모두 실제 Win32 `GenerateConsoleCtrlEvent` 기반 Ctrl+C 신호 전달에 의존한다.
- `RunningAI.ConnectorOwnership.ps1`의 이번 Phase 변경분은 신호 전송 코드 자체(`Stop-RunningAiConnectorManaged`의
  `Send-CtrlC.ps1` 호출부)를 전혀 건드리지 않았으며, `Send-CtrlC.ps1`은 이번 Phase에서 전혀 수정하지
  않았다(git diff로 확인).
- 재현성 검증: (1) 수정된 2B 버전에서 단독 재실행(격리) 시에도 동일하게 재현됨. (2) `git checkout --`로
  두 파일을 **수정 전 HEAD(Phase 6I-1.7B-2A 시점) 상태로 완전히 되돌린 뒤** 동일 테스트를 재실행한
  결과, **동일한 2건이 동일하게 실패**했다. (3) 최소 재현 스크립트(`Send-CtrlC.ps1`을 단순 PowerShell
  대상 프로세스에 직접 호출)로도 `GenerateConsoleCtrlEvent`가 성공(Exit 0)을 반환함에도 대상 프로세스가
  실제로 신호를 받지 못하고 종료되지 않음을 확인했다.
- 결론: 이는 **이번 Phase의 회귀가 아니라, 현재 실행 샌드박스 환경에서의 Win32 콘솔 Ctrl+C 신호
  전달 자체가 불안정한, 사전에 존재하던 환경적 한계**다. 코드를 임의로 수정하거나 테스트를
  약화/삭제하지 않았다. 재현 과정에서 생성한 임시 프로세스/스크립트는 모두 정리했다.
- `Test-WatchdogRecoveryPolicy.ps1`을 포함한 2B의 신규/수정 테스트는 이 메커니즘에 의존하지 않으므로
  영향이 없다.

## 10. Commit SHA

커밋은 이 보고서 작성 직후 생성된다 — 실제 SHA는 아래 "origin/main-3 상태" 항목 및 후속 커밋 메시지로
확정한다. (커밋 메시지: `fix(windows): harden watchdog recovery lifecycle and budgets`)

## 11. origin/main-3 상태

Push는 fast-forward 전용으로 수행하며, `origin/main`과 Canonical(`C:\running-ai-github`)에는 어떤
형태로도 반영하지 않는다. Push 전 `git merge-base --is-ancestor`로 fast-forward 안전성을 확인한다.

## 12. Canonical 및 운영환경 불변 확인

- Canonical(`C:\running-ai-github`) 작업 트리: `git status --short` 결과 비어 있음(변경 없음), HEAD는
  `f6a2135`(작업 시작 시점과 동일)로 유지.
- 실제 운영 포트 확인: 18080(Spring), 17845(External Relay), 8765(Garmin Connector) 모두 이번 세션 시작
  전과 동일한 PID로 LISTEN 중 — 단 한 번도 중지/재시작하지 않았다.
- 실제 운영 `watchdog-state.json`, `.env`, PID/Sidecar 파일은 이번 Phase 동안 전혀 읽거나 쓰지 않았다
  (모든 테스트는 `RUNNING_AI_TEST_RUNTIME_DIR` 임시 디렉터리 또는 자체 격리 포트만 사용).
- Watchdog 스케줄 작업은 활성화하지 않았다. Windows 재부팅은 수행하지 않았다.

## 13. 배포 이전 남은 위험

- STEP B 컴포넌트별 Executor 완전 분리는 로그 레벨 귀속으로 범위를 축소했다 — 실제 운영 예산 회계
  정확도는 "이 Phase 이전과 동일" 수준이며, 장애 조사 시 로그를 봐야 정확한 귀속을 알 수 있다.
  완전한 구조적 분리는 후속 Phase 과제로 남는다.
- `Test-ConnectorOwnership.ps1`의 Ctrl+C 2건은 "이번 Phase와 무관한 환경적 플레이크"로 결론지었지만,
  이는 어디까지나 현재 개발 샌드박스에서의 관찰이다. **실제 운영 PC(main PC)에서 Graceful Stop이
  실제로 동작하는지는 이번 세션에서 재검증하지 않았다** — Phase 6I-1.7B-1에서 Main PC 실환경 검증이
  이미 있었다는 기록이 있으나, 2B 배포 전에는 Main PC에서 최소 1회 수동 Graceful Stop 검증을 권장한다.
- 장기 Lockout 정책(24h/6회)은 설정값이며 실제 운영 트래픽 패턴에서 검증된 바 없다 — 배포 초기에는
  status JSON의 `longTermLockout`을 주기적으로 관찰해 오탐(false lockout)이 없는지 확인할 필요가 있다.
- Runtime Lock(STEP F)은 Watchdog 자체가 참여하지 않는 구조이므로, Watchdog의 PreStop/복구 로직이
  start/stop 스크립트와 **동시에** 같은 프로세스를 건드리는 레이스는 Lock으로 막히지 않는다(의도된
  설계지만, 운영 중 수동 start/stop과 Watchdog 자동 복구가 정확히 겹치는 극단적 타이밍에서는 여전히
  두 경로가 동시에 같은 프로세스를 관찰/조작할 수 있다 — 각 경로 내부의 소유권/CreationDate 재검증이
  최종 안전망이다).

## 14. Phase 6I-1.7C 진입 가능 여부

가능하다고 판단한다. 단, 위 13번의 세 가지 잔여 위험(STEP B 완전 분리 이월, Main PC Graceful Stop
재검증 권장, 장기 Lockout 임계값의 실운영 미검증)을 다음 단계 또는 운영 전환 전 체크리스트에 명시적으로
포함할 것을 권장한다.
