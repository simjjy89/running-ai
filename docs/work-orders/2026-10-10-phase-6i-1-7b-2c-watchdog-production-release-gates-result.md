# WO-RUNNINGAI-PHASE-6I-1.7B-2C — Watchdog Production Release Gates (결과 보고)

지시서 원문: [2026-10-10-phase-6i-1-7b-2c-watchdog-production-release-gates-instruction.md](2026-10-10-phase-6i-1-7b-2c-watchdog-production-release-gates-instruction.md)

## 1. 변경 파일

- `scripts/windows/RunningAI.Common.ps1` — `Enter-RunningAiRuntimeLock`/`Exit-RunningAiRuntimeLock`에
  `RUNNING_AI_WATCHDOG_LOCK_INHERITED` 신뢰 메커니즘 추가(STEP 1).
- `scripts/windows/RunningAI.Watchdog.ps1` — 신규: watchdog-state.json 전용 Lock
  (`Enter/Exit-RunningAiWatchdogStateLock`, STEP 4), `.bak` 백업 작성/누락-감지(`Read-WatchdogState`/
  `Save-WatchdogState`, STEP 4), 복구 결과 계약(`New-RunningAiRecoveryOutcome`,
  `ConvertTo-RunningAiRecoveryOutcome`, `Test-RunningAiRecoverySucceeded`, STEP 2),
  `Invoke-RecoveryAction`를 얇은 디스패처로 전환하고 실제 로직을 `Invoke-RunningAiCoreRecoveryAction`/
  `Invoke-RunningAiRelayRecoveryAction`(둘 다 Lock으로 PreStop+Start/Restart를 하나로 묶음, STEP 1)로
  분리, `Start-RunningAiWatchdogTrustedChild`(자식 프로세스 환경변수에만 신뢰 플래그 설정, STEP 1),
  `Invoke-WatchdogRecovery`의 BUSY/BLOCKED/UNKNOWN_FAILURE 분기 및 Deltas 추적(STEP 2/4).
- `scripts/windows/watch-running-ai.ps1` — `Save-RunningAiWatchdogStateReconciled` 사용으로 전환
  (STEP 4), Busy 상태 로그/status JSON 반영(STEP 1).
- `scripts/windows/clear-watchdog-lockout.ps1` — 미리보기는 항상 `-ReadOnly`, `-Force` 경로는
  Watchdog State Lock 획득 후 재읽기(STEP 4).
- `scripts/windows/external/start-external-relay.ps1`, `stop-external-relay.ps1` — Runtime Lock
  획득/해제 추가(STEP 1, Relay 수동 Stop과의 충돌 방지).
- `scripts/windows/tests/Test-WatchdogRecoveryPolicy.ps1` — STEP 5 신규 시나리오 다수 추가(아래 9번).
- `scripts/windows/tests/Test-ConnectorOwnership.ps1` — 함수명/결과 형태 변경에 맞춘 소스 검사 테스트
  3건 갱신(`Invoke-RecoveryAction` → `Invoke-RunningAiCoreRecoveryAction`, `return $false` →
  `ResultCode 'BLOCKED'/'FAILED'`).
- `docs/work-orders/2026-10-10-phase-6i-1-7b-2c-*.md` (신규, 본 문서 + 지시서 원문).

## 2. Runtime Lock 최종 소유 구조

기존 6I-1.7B-2B 규칙("Watchdog는 Lock을 절대 획득하지 않는다")을 폐기하지 않고 **대체**했다:

- `Invoke-RunningAiCoreRecoveryAction`/`Invoke-RunningAiRelayRecoveryAction`이 각각 하나의 복구
  작업(PreStop + Start/Restart) 전체를 감싸는 단일 `Enter-RunningAiRuntimeLock` 획득/해제 블록을
  가진다 — 수동 start/stop과 완전히 상호 배제된다.
- 교착 상태 방지: 이 Lock을 쥔 채로 `start-running-ai.ps1`/`external\start-external-relay.ps1`을
  자식 프로세스로 실행(`Start-RunningAiWatchdogTrustedChild`)할 때, `RUNNING_AI_WATCHDOG_LOCK_INHERITED
  = '1'`을 **그 자식 프로세스의 `ProcessStartInfo.EnvironmentVariables`에만** 설정한다(부모 자신의
  `$env:`는 절대 건드리지 않음 — 별도 테스트로 누수 없음을 확인, 9번 참고). 자식은 이 플래그를 보고
  Lock을 아예 재획득하려 시도하지 않으므로, "부모가 Lock을 쥔 채 동일 Lock을 기다리는 자식을 기다리는"
  교착 구조 자체가 성립하지 않는다.
- Lock 획득 실패(수동 작업 진행 중) → 즉시 `BUSY` 결과 반환, PreStop/Start 둘 다 시도하지 않음.
- 소스 검사 테스트로 고정: `Enter-RunningAiRuntimeLock` 호출이 정확히 2곳(Core/Relay 헬퍼)에만
  있고, 각각 `try/finally`로 해제되며, 두 헬퍼 모두 `Start-RunningAiWatchdogTrustedChild`를 통해서만
  자식을 실행함(원시 `Start-Process` 없음)을 확인.

## 3. 실패 컴포넌트 귀속 및 예산 정책

`New-RunningAiRecoveryOutcome` / `ConvertTo-RunningAiRecoveryOutcome`로 기존 Boolean 계약을 확장했다.

- `ResultCode`: `SUCCESS | FAILED | BUSY | BLOCKED | TIMED_OUT | UNKNOWN_FAILURE`.
- `BudgetChargeComponent`는 **`FAILED`/`TIMED_OUT`에서만** 계산되며, `ActualFailedComponent`가 있으면
  그것을, 없으면 시도 대상(`AttemptedComponent`) 자신을 사용한다 — 절대 임의 추측 없음(규칙 4).
- **하위 호환**: `$Runner`가 기존처럼 단순 `$true`/`$false`를 반환하면 `ConvertTo-RunningAiRecoveryOutcome`이
  이를 정확히 기존 동작(`$true`=SUCCESS/무과금, `$false`=FAILED/시도 대상 과금)으로 변환한다 — 기존
  `Test-Watchdog.ps1`과 `Test-WatchdogRecoveryPolicy.ps1`의 수십 개 테스트가 **단 하나도 수정 없이**
  그대로 통과했다.
- `Invoke-RunningAiCoreRecoveryAction`은 `start-running-ai.ps1`의 실제 Exit Code로
  `ActualFailedComponent`를 식별하고(6I-1.7B-2B의 매핑 재사용), Exit 0이어도
  `Test-RunningAiRecoverySucceeded`로 **실제 재관찰**하여 대상 컴포넌트가 진짜 UP인지 확인한 뒤에만
  SUCCESS를 보고한다(규칙 5/6) — 재관찰에서 여전히 UP이 아니면 FAILED로 강등하고 시도 대상 자신에게
  귀속한다.

## 4. Busy/Skipped/Blocked 처리

- **BUSY**(Lock 경합): 아무것도 시도되지 않음 — 단기 재시작 예산, 장기 실패 횟수 모두 미증가.
  `Invoke-WatchdogRecovery`의 `.Failed`에도 포함되지 않고 별도 `.Busy` 플래그로만 보고된다.
- **BLOCKED**(소유권 미검증 등으로 의도적 거부): 시도한 것이 아니라 "거부"이므로 장기 실패 횟수는
  증가하지 않는다(규칙 3) — 단기 재시작 예산은 여전히 증가한다(실제 컴포넌트를 건드리려는 시도 자체는
  있었기 때문).
- **UNKNOWN_FAILURE**(Exit Code를 알려진 컴포넌트에 매핑할 수 없음): 어떤 컴포넌트에도 장기 실패를
  귀속하지 않는다(규칙 4) — 단기 예산은 시도 대상 컴포넌트에 증가(해당 틱은 `.Failed = $true`로 중단).
- 오직 `FAILED`/`TIMED_OUT`만 장기 실패 횟수를 증가시키며, 그 대상은 `BudgetChargeComponent`
  (실제 식별된 컴포넌트 우선)이다.

## 5. Relay 독립성

6I-1.7B-2A/2B에서 확립된 아키텍처(별도 복구 루프, 별도 예산, Core 체인과 교차 영향 없음)는 변경하지
않았다. 이번 단계에서 추가된 것은 **동시성 보호**뿐이다: `Invoke-RunningAiRelayRecoveryAction`과
`external\start-external-relay.ps1`/`stop-external-relay.ps1`(수동 실행 경로) 모두 동일한 Runtime
Lock을 사용하므로, Watchdog의 Relay 복구와 수동 Relay Stop/Start가 서로 경합할 수 없다.

## 6. Ctrl+C 격리 검증 결과

Phase 6I-1.7B-2B의 결론을 그대로 재확인했다 — **변경하지 않고 재검증만 수행**:

- `Test-ConnectorOwnership.ps1`의 기존 2건("graceful stop: Ctrl+C reaches both launcher and
  child...", "Send-CtrlC.ps1 omitting -AllowedProcessIds performs the legacy unconditional
  send...")이 이번 Phase의 전체 회귀에서도 **동일하게** 실패했다. 지시서의 명시적 지침("삭제하거나
  단언을 약화하지 않는다")에 따라 **그대로 유지**했다 — 수정하지 않았다.
- 이는 2B에서 독립 재현 스크립트 및 수정 전 HEAD 기준선 대조로 이미 "이번 저장소 코드와 무관한, 현재
  샌드박스 실행 환경의 Win32 콘솔 Ctrl+C 신호 전달 결함"으로 결론 내린 사안이며, 이번 Phase의 변경
  (`Send-CtrlC.ps1` 자체는 전혀 수정하지 않음, `RunningAI.ConnectorOwnership.ps1`도 이번 Phase에서
  수정하지 않음)과 무관하다.
- **STEP 3가 요구하는 "Ctrl+C가 안 되면 Forced 종료가 소유권 검증을 통해 안전하게 동작하는지 확인"**은
  이미 충족되어 있다 — "graceful timeout falls back to forced stop of verified PIDs only; a bystander
  process survives" 테스트가 매 회귀마다 PASS하며, Forced 종료가 (a) 검증된 관리 PID만 대상으로 하고
  (b) 비관리 프로세스(bystander)는 절대 건드리지 않음을 실제 분리 프로세스로 증명한다. 따라서 **필수
  종료 경로(Forced fallback)는 검증되었다** — STEP 3의 FAIL 조건("필수 종료 경로가 검증되지 않으면
  FAIL")에 해당하지 않는다.

## 7. Lockout 상태 파일 안전성

- **`.bak` 백업**: `Save-WatchdogState`가 매번 메인 파일 저장 직후 동일 내용을 `.bak`에도 기록한다.
  `Read-WatchdogState`는 메인 파일이 없는데 `.bak`만 있으면 "최초 실행"이 아니라 **"파일이 예기치 않게
  사라졌을 가능성"**으로 판단하여 `Available=false`(자동 복원 없음, Fail-safe)를 보고한다 — 둘 다
  없으면 진짜 최초 실행으로 처리(변경 없음).
- **전용 State Lock**(`Enter/Exit-RunningAiWatchdogStateLock`, Runtime Lock과는 별개의 Named Mutex):
  Watchdog의 최종 저장(`Save-RunningAiWatchdogStateReconciled`)과 `clear-watchdog-lockout.ps1 -Force`가
  이 Lock으로 상호 배제된다.
- **Fresh-read 후 Delta 재적용 방식**: Watchdog 한 틱이 길어질 수 있으므로(최대 15분 타임아웃) 틱
  시작 시점의 State를 끝까지 들고 있다가 그대로 덮어쓰지 않는다. 대신 틱이 끝날 때 Lock을 획득하고
  **디스크의 최신 State를 다시 읽은 뒤**, 이번 틱에서 실제로 발생한 재시작/장기실패 Delta만 그 위에
  재적용하여 저장한다 — 동시에 운영자가 `clear-watchdog-lockout.ps1 -Force`로 Lockout을 해제했다면
  그 해제가 Watchdog의 저장으로 되살아나지 않는다(실 테스트로 확인).
- **`clear-watchdog-lockout.ps1`**: `-Force` 없는 미리보기는 `-ReadOnly`로만 읽어 파일을 전혀
  건드리지 않음(바이트 단위로 확인). `-Force` 경로는 State Lock을 먼저 획득한 뒤 재읽기 → 수정 →
  저장 → 해제.
- **저장 실패를 조용히 무시하지 않음**: `Save-WatchdogState`/`Save-RunningAiWatchdogStateReconciled`
  모두 예외를 삼키지 않는다 — `watch-running-ai.ps1`의 기존 최상위 `try/catch`가 그대로 받아
  `WATCHDOG_ERROR`로 로그·종료 코드 처리한다. Lock 획득 실패나 재읽기 실패는 저장을 **생략**하고
  명시적으로 경고 로그를 남긴다(값을 조용히 포기하지 않고, 추측으로 덮어쓰지도 않음).

## 8. 전체 5개 회귀 스위트 결과

| 파일 | 결과 |
|---|---|
| `Test-RunningAI.ps1` | PASS (exit 0) |
| `Test-Watchdog.ps1` | PASS (exit 0) |
| `Test-ExternalRelay.ps1` | PASS (exit 0) |
| `Test-WatchdogRecoveryPolicy.ps1` | PASS, 2회 연속 확인 (exit 0, 40개 이상 체크) |
| `Test-ConnectorOwnership.ps1` | 33개 중 31개 PASS — 6번 항목에 기술한 **이번 Phase와 무관한
  기존(2B) 환경적 결함 2건**만 실패, 그 외 전부 PASS(신규 소스 검사 3건 포함) |

`Test-WatchdogRecoveryPolicy.ps1`에 STEP 5 시나리오 전체(Busy/Blocked/Unknown-failure 귀속, Lock
경합 3종, Lockout 경합/미리보기, 상태 파일 백업 감지 등)를 다루는 신규 Check 약 20여 개를 추가했다.

## 9. Git Commit SHA

커밋 직후 본 문서에 보강 기록.

## 10. Canonical 및 운영환경 불변 확인

- `C:\running-ai-github`: `git status --short` 비어 있음, HEAD `f6a213588c5b7855b0dc7bf99948896291240ed7`
  (작업 지시서의 Expected main과 정확히 일치, 변경 없음).
- 실 서비스: Spring `18080`(PID 5092), External Relay `17845`(PID 10224), Garmin Connector
  `8765`(PID 9484) 모두 세션 시작 시점과 동일한 PID로 LISTEN 중. Postgres 컨테이너
  `running-ai-postgres` "Up 22 hours (healthy)" 그대로.
- 아래 11번에 기술한 "사고 직전까지 간" 테스트 설계 결함이 실제로는 이 환경에 아무 영향도 주지
  않았음을 `docker ps`, 포트/PID 조회, Canonical git status로 재확인했다.

## 11. 남은 위험

- **STEP 1 Lock의 한계**: Watchdog 복구 동작과 수동 작업이 **정확히 같은 순간**에 경합하는 극단적
  타이밍에서는, 각자의 Lock 획득 순서에 따라 한쪽이 BUSY로 양보하는 것으로 충분히 방어되지만, Lock
  획득 자체의 OS 스케줄링 레벨 공정성까지 이 설계가 보장하지는 않는다. 실무적으로는 안전하지만
  수학적으로 완벽한 상호배제 증명은 아니다.
- **STEP 2 귀속의 전제**: `start-running-ai.ps1`의 Exit Code가 실제로 정확하다는 것을 전제로
  귀속한다 — Exit Code 매핑 자체의 정확성은 이번 Phase에서 별도로 재검증하지 않았다(6I-1.7B-2B에서
  설계된 기존 매핑을 그대로 재사용).
- **테스트 개발 중 발견한 중요한 설계 함정(기록 필요)**: 이번 Phase의 테스트를 작성하던 중,
  `Invoke-RunningAiCoreRecoveryAction`을 테스트 파일 **안에서 직접(in-process)** 호출하는 최초
  버전이 실제로 **진짜 `start-running-ai.ps1`을 개발 Worktree의 실제 Docker/PostgreSQL을 대상으로
  스폰**했던 것을 발견했다. 원인: 이 테스트 파일은 최상단에서 `RunningAI.Watchdog.ps1`을
  dot-source하는데, 그 시점에 `$script:RuntimeDir`가 (아직 `RUNNING_AI_TEST_RUNTIME_DIR`가 설정되기
  전이므로) **실제 저장소 경로로 고정**된다 — 이후 `With-TempRuntimeDir`로 격리 디렉터리를 아무리
  설정해도, 테스트 프로세스 **자기 자신의** `Enter-RunningAiRuntimeLock` 호출은 여전히 실제 경로
  기반의 Lock 이름을 사용하므로 격리되지 않는다. 다행히 이 스폰은 postgres 단계에서 실패하며
  종료되었고, `docker ps`/Canonical git status/실 서비스 PID 확인으로 **실제 영향이 전혀 없었음**을
  확인했지만, 운이 좋았을 뿐 설계 자체는 위험했다. 즉시 모든 관련 테스트를 "Lock을 보유하는 프로세스와
  Lock을 시도하는 프로세스 양쪽 모두 별도로 스폰"하는 방식으로 재작성하여 수정했다(안전한 무작위 가짜
  포트까지 이중 안전장치로 추가). **향후 이 코드베이스에서 Lock을 획득하는 프로덕션 함수를 테스트할
  때는 반드시 완전히 분리된 자식 프로세스에서 실행해야 하며, 테스트 파일 자신의 in-process 호출에
  의존해서는 안 된다**는 것을 이번 Phase의 가장 중요한 발견으로 기록한다.
- Phase 6I-1.7B-2B에서 권고한 "Main PC에서 실제 Graceful Stop 수동 검증"은 여전히 미수행 상태로
  남아있다 — 이번 Phase도 동일하게 권고한다.

## 12. Phase 6I-1.7C 배포 준비 PASS/FAIL

**PASS.** STEP 1~7의 모든 필수 안전성 요구사항이 구현·테스트·회귀 완료되었다. 11번 항목의 잔여
위험은 모두 식별되었고 차단 사유가 아닌, 운영 전환 시 참고할 문서화된 한계다. 유일한 회귀 실패 2건은
이번 Phase의 변경과 무관한 사전 존재 환경 제약(6번 항목)으로, Forced 종료 Fallback이 안전하게
동작함이 증명되어 있어 STEP 3의 FAIL 조건에 해당하지 않는다.
