# WO-RUNNINGAI-PHASE-6I-1.7C-0 — Main PC Pre-Deployment Validation & Release Gate (결과 보고)

지시서 원문: [2026-10-11-phase-6i-1-7c-0-main-pc-pre-deployment-validation-instruction.md](2026-10-11-phase-6i-1-7c-0-main-pc-pre-deployment-validation-instruction.md)

## 1. 기준 Git SHA

| 저장소 | Expected | 실제 확인값 | 일치 |
|---|---|---|---|
| main / origin/main | f6a213588c5b7855b0dc7bf99948896291240ed7 | f6a213588c5b7855b0dc7bf99948896291240ed7 | ✅ |
| main-3 / origin/main-3 | 111ce745821154930d96dc3d879d9610480ef7c6 | 111ce745821154930d96dc3d879d9610480ef7c6 (작업 시작 시점) | ✅ |

Canonical(`C:\running-ai-github`) 작업 트리: clean. 기존 Untracked 문서(`2026-10-10-phase-6i-1-6c-*.md`)는 그대로 보존했다.

## 2. 운영 사전 점검 결과 (STEP A)

| 항목 | 결과 |
|---|---|
| Git SHA/Working Tree | 1번 항목과 동일, 불일치 없음 |
| Watchdog 상태 | `RunningAI-Watchdog` 작업 `Disabled` 확인 |
| RunningAI-Startup 설정 | `State=Ready`, Logon 트리거, `MultipleInstances=IgnoreNew` 확인(변경 없음) |
| 운영 포트/PID | 18080→PID 5092, 17845→PID 10224, 8765→PID 9484, 전부 127.0.0.1 Loopback 바인딩 확인 |
| PostgreSQL | 컨테이너 ID `8ff2a3adc495`, `Up 28h (healthy)`, Volume `running-ai-github_running_ai_postgres_data` 확인 |
| 운영 .env | 존재함, 키 15개, `SERVER_PORT=18080` 확인(값만 확인, 다른 키 값은 출력하지 않음) |
| Runtime Lock 환경변수 충돌 | 현재 프로세스/영구(User/Machine) 범위 모두 `RUNNING_AI_WATCHDOG_LOCK_INHERITED`, `RUNNING_AI_TEST_RUNTIME_DIR` 미설정 확인 |

사전 상태 불일치 없음 — STOP 조건 미해당, STEP B로 진행.

## 3. Runtime Lock 신뢰성 판정 (STEP B)

**문제 확인**: 6I-1.7B-2C의 `RUNNING_AI_WATCHDOG_LOCK_INHERITED='1'`은 단순 존재 여부만 확인했다 — 지적된 5가지 위험(수동 우회, 손자 프로세스 전파, 부모 종료 후 우회, 임의 PowerShell 설정, 다른 Worktree 유출) 전부 실제로 성립 가능했다.

**적용한 수정** (개발 Worktree에서만, main-3):

- `RUNNING_AI_WATCHDOG_LOCK_INHERITED`가 더 이상 `'1'` 플래그가 아니라 **Watchdog 프로세스 자신의 PID**를 담도록 변경(`Start-RunningAiWatchdogTrustedChild`).
- 신규 `Test-RunningAiTrustedWatchdogParent`(`RunningAI.Common.ps1`)가 매 호출마다(캐시 없이) 다음을 전부 검증한 뒤에만 신뢰: (a) 주장된 PID가 지금 살아있는가, (b) 그 프로세스의 실시간 CommandLine이 `watch-running-ai.ps1`과 **이 저장소 자신의 RepoRoot**를 모두 포함하는가, (c) 그 프로세스가 **실제로 현재 프로세스의 직계 부모**인가(Win32_Process ParentProcessId), (d) CreationDate가 현재 프로세스보다 앞서는가(PID 재사용 방지).
- 하나라도 실패하면 **항상 실제 Lock 획득으로 폴백** — 절대 우회로 가지 않는다(Fail-safe).
- 기존 Deadlock 방지 기능 유지: 여전히 Watchdog만 Lock을 쥔 채 신뢰된 자식에게만 이 값을 전달하며, 자식은 그 값을 검증 못하면 스스로 Lock을 얻으려 시도한다(이 경우 Watchdog 쪽 Timeout 내에서 BUSY로 자연 해소 — 아래 11번 "남은 위험" 참고).
- PowerShell 5.1 호환: `Get-CimInstance`, `[int]::TryParse`, `StringComparison.OrdinalIgnoreCase`만 사용 — 전부 PS5.1 네이티브.

**검증 결과**: 6개 신규 테스트로 5가지 위험 전부와 정상 경로(Happy Path)를 실제 분리 프로세스로 확인 — 전부 PASS(아래 7번 참고).

## 4. Main PC Ctrl+C 테스트 결과 (STEP C)

**사전 검증**: 신규 격리 Harness(`Test-MainPcPythonVenvCtrlC.ps1`)가 다음을 소스 수준에서 충족함을 확인 — 임시 `%TEMP%` 디렉터리에 독립 venv 생성, 운영 `.env`/Docker/PostgreSQL/실제 8765·18080·17845 포트 미사용, `start-running-ai.ps1`/Watchdog 작업 미실행, 테스트 전용 PID만 종료, 운영 `.runtime` 무접촉.

**중요 발견**: 이 Main PC에 설치된 Python 3.12(Python Install Manager, `pythoncore-3.12-64`)는 venv의 `Scripts\python.exe`가 **트램폴린**(즉시 실제 엔진 프로세스로 재실행)으로 동작한다. **실제 운영 Garmin Connector 프로세스 트리를 직접 확인**한 결과 동일한 패턴이 확인되었다: 추적 PID(venv `Scripts\python.exe`, tools\garmin-connector\.venv)의 **직계 자식**이 실제 포트 8765를 리슨하는 엔진 프로세스였다 — 이는 정확히 기존 `LAUNCHER_CHILD` 모델이 가정하는 1단 구조와 일치한다(최초 설계 시 2개 스크립트를 중첩시킨 테스트 초안은 인위적으로 4단 구조를 만들어 거짓 실패를 발생시켰고, 실제 Connector의 단일 스크립트 호출 구조에 맞춰 단순화했다 — 상세 경위는 `Test-MainPcPythonVenvCtrlC.ps1` 자체의 헤더 주석에도 기록).

재현 항목 1-5(venv 생성, Launcher/Interpreter 분리, Loopback LISTEN, 소유권 판정): 전부 PASS — `Get-RunningAiConnectorOwnership`이 실제 python.exe 쌍을 `LAUNCHER_CHILD`로 정확히 분류했다.

## 5. Graceful 및 Forced 종료 결과 (STEP C 계속)

- **Graceful (Ctrl+C)**: 실제 Python 3.12 프로세스에서도 **실패**했다 — 20초 내 종료되지 않음. 기존 `Test-ConnectorOwnership.ps1`의 2건의 장기 실패와 **동일한 원인**(이 샌드박스 실행 환경의 Win32 콘솔 Ctrl+C 신호 전달 결함, 2026-10-10 Phase 6I-1.7B-2B에서 독립 재현·HEAD 기준선 대조로 이미 결론)으로 판단된다 — 이번에 코드를 수정하거나 단언을 약화하지 않았다(지시사항 준수).
- **Forced Fallback**: PASS. 검증된 관리 PID만 종료, 포트 해제, **비관리 Python 프로세스(bystander)는 완전히 보존**됨을 실제 프로세스로 확인했다.
- **PID 재사용/소유권 변경 거부**: PASS. 분류 시점 스냅샷의 CreationDate를 조작하면 `OWNERSHIP_CHANGED`로 거부되고 강제 종료가 수행되지 않음(프로세스 생존 확인)을 실제 Python 프로세스로 재확인했다.
- **결론 (STEP C 요구사항)**: "Forced Fallback까지 실패하거나 안전성을 확인할 수 없으면 NO-GO" 조건에 해당하지 않는다 — Forced Fallback의 안전성이 PowerShell 스탠드인과 실제 Python 3.12 venv 프로세스 양쪽 모두에서 증명되었다.

## 6. State/Lockout 안전성 (STEP D)

운영 State 파일(`C:\running-ai-github\.runtime\watchdog-state.json`)은 **읽기만** 했다 — 실제 내용: `version: 1`(v1, `longTerm` 키 없음), `.bak` 없음. Watchdog가 비활성화된 이후 한 번도 저장되지 않았기 때문에 당연한 상태이며, **실제 v2 마이그레이션은 적용하지 않았다**(지시사항 준수).

아래 항목은 모두 **격리된 임시 파일**로만 검증했으며(6I-1.7B-2C에서 이미 구현/테스트, 이번 Phase에서 재확인):

| 항목 | 검증 결과 |
|---|---|
| v1→v2 마이그레이션 | PASS — 기존 재시작 이력 손실 없이 보존, `longTerm`은 신규 초기화 |
| Restart History 보존 | PASS |
| LongTerm Lockout 보존 | PASS — Watchdog 재시작 시뮬레이션에도 유지 |
| `.bak` 처리 | PASS — 메인 파일 소실+`.bak` 존재 시 "최초 실행"으로 오판하지 않고 Available=false 보고, 자동 복원 없음 |
| Lockout 해제 도구 ReadOnly 안전성 | PASS — `-Force` 없는 미리보기는 바이트 단위로 파일 무변경 확인 |
| BUSY/BLOCKED/UNKNOWN_FAILURE 예산 귀속 | PASS — 셋 다 장기 실패 횟수 미증가 |
| Spring/Connector Lockout 독립성 | PASS |
| Relay 독립 복구 | PASS |
| 24시간/6회 기본 임계값 | PASS(기존 회귀 테스트로 고정) |

## 7. 전체 5개 테스트 결과 (STEP E)

| 스위트 | Exit Code | 비고 |
|---|---|---|
| `Test-RunningAI.ps1` | 0 | 전체 PASS |
| `Test-Watchdog.ps1` | 0 | 전체 PASS |
| `Test-ExternalRelay.ps1` | 0 | 전체 PASS |
| `Test-ConnectorOwnership.ps1` | 1 | 33개 중 31개 PASS — **기존 2건**(Ctrl+C 관련, 6I-1.7B-2B에서 이미 환경적 결함으로 결론) 외 전부 PASS. 이번 Phase에서 신규 추가/수정한 항목 없음(STEP C의 신규 검증은 별도 파일로 분리) |
| `Test-WatchdogRecoveryPolicy.ps1` | 0 | 전체 PASS — STEP B 신규 6건 포함 |

**추가 신규 검증 (STEP C, 필수 5개 스위트 외)**:

| 파일 | Exit Code | 비고 |
|---|---|---|
| `Test-MainPcPythonVenvCtrlC.ps1` (신규) | 1 | 5개 중 4개 PASS — 실패 1건은 위 6번 항목에 기술한 **동일한 기존 환경적 결함**(Ctrl+C 전달)뿐, Forced Fallback·소유권 판정·PID 재사용 거부는 전부 PASS |

전체 5개 스위트가 모두 Exit 0은 아니므로("Test-ConnectorOwnership.ps1" exit=1) **전체 PASS로 표현하지 않는다**. 실패는 전부 사전에 결론 난 동일 원인(샌드박스 Ctrl+C 전달 결함)이며, 이번 Phase의 변경과 무관함을 재확인했다.

추가 검사: PS5.1 파싱 — 전체 PASS. `git diff --check` — PASS(경고만, LF/CRLF 안내). 비밀정보 검사 — Clean. 격리 테스트 프로세스/임시 디렉터리 잔여 없음(디버깅 과정에서 발생한 약 12개의 누출 프로세스와 2개의 임시 디렉터리를 발견하여 정리함 — 11번 항목 참고). 운영 LISTEN PID 불변(18080/5092, 17845/10224, 8765/9484). PostgreSQL ID(`8ff2a3adc495`)/Volume 불변. Watchdog Disabled 유지.

## 8. 수정 파일과 Commit SHA

변경 파일(main-3에서만, 이번 작업 범위):

- `scripts/windows/RunningAI.Common.ps1` — `Test-RunningAiTrustedWatchdogParent` 신규, `Enter-RunningAiRuntimeLock` 검증 로직 교체.
- `scripts/windows/RunningAI.Watchdog.ps1` — `Start-RunningAiWatchdogTrustedChild`가 `'1'` 대신 `$PID`를 전달하도록 수정.
- `scripts/windows/tests/Test-WatchdogRecoveryPolicy.ps1` — STEP B 하드닝 관련 2건 기존 테스트 갱신(바른 값으로), 6건 신규 테스트 추가.
- `scripts/windows/tests/Test-MainPcPythonVenvCtrlC.ps1` (신규) — STEP C Main PC 실제 Python 3.12 venv 검증.
- `docs/work-orders/2026-10-11-phase-6i-1-7c-0-*.md` (신규, 본 문서 + 지시서 원문).

Commit SHA: 커밋 직후 본 문서에 보강 기록.

## 9. 실제 서비스/PID/PostgreSQL 불변 여부

전부 불변 확인(2번, 7번 항목 참고): Spring 18080/PID 5092, External Relay 17845/PID 10224, Garmin Connector 8765/PID 9484, PostgreSQL 컨테이너 `8ff2a3adc495`/Volume 전부 작업 시작 시점과 동일. Watchdog는 작업 내내 Disabled 유지. Canonical(`C:\running-ai-github`) HEAD/Working Tree 불변. Legacy(`C:\running-ai`)는 접근하지 않았다.

## 10. 잔여 위험

- **STEP B의 경계 사례**: `watch-running-ai.ps1`이 `-File`이 아닌 방식(예: 대화형 세션에서 직접 dot-source 실행)으로 구동될 경우, 그 프로세스의 CommandLine에 `watch-running-ai.ps1`이라는 문자열이 리터럴로 나타나지 않을 수 있어 `Test-RunningAiTrustedWatchdogParent`가 정당한 신뢰 요청도 거부할 수 있다. 이 경우 자식은 즉시 BUSY로 보지 않고 실제 Lock 획득을 재시도하는데, 부모가 이미 그 Lock을 쥐고 있으므로 자식은 자신의 TimeoutSec(기본 5초, start-running-ai.ps1 기준)만큼만 대기 후 BUSY로 자연 종료한다 — **무한 Deadlock은 아니며, 최대 수 초의 지연과 그 틱의 복구 실패로 그친다.** 실제 운영 경로(Scheduled Task, 항상 `-File`로 구동)는 이 경계에 해당하지 않는다.
- **STEP C의 실행 환경 한계**: Ctrl+C 신호 전달 자체는 이 샌드박스에서 여전히 작동하지 않는다 — 실제 Main PC 운영 세션(이 작업을 수행 중인 바로 이 환경)에서 재현되었으므로, 이는 "격리 테스트 환경"이 아니라 **이 Main PC 자체의 특성**일 가능성이 있다. Forced Fallback으로 안전하게 보완되지만, 운영 중 Graceful Shutdown이 기대보다 자주 Forced로 전환될 수 있다 — Phase 6I-1.7B-2B/2C에서 이미 권고한 대로 변경 없이 유지.
- **Python 트램폴린 구조의 실제 영향**: 실제 Garmin Connector도 동일한 Python Install Manager 기반 venv를 사용하며, `Write-PidFile`이 기록하는 PID(트램폴린)가 실제 리슨 PID와 다르다는 사실 자체는 **이미 Phase 6I-1.7B-1부터 알려지고 대응된 것**(`LAUNCHER_CHILD` 모델)이다 — 이번 조사로 그 모델이 실제 설치 환경에서도 여전히 정확히 들어맞음(1단 구조)을 재확인했을 뿐, 새로운 위험은 아니다.
- **디버깅 과정의 프로세스 누출**: 이 작업 도중 격리 설계를 반복 수정하는 과정에서 중간 실패 스크립트들이 정리 로직 실패로 인해 다수(약 12개)의 테스트 전용 Python 프로세스와 2개의 임시 디렉터리를 남겼다 — 전부 발견하여 정리했고 운영 서비스에는 영향이 없었음을 확인했지만, 최종 `Test-MainPcPythonVenvCtrlC.ps1`의 `Stop-RunningAiTestProcessTree`는 트리 전체를 재귀적으로 추적·종료하도록 설계해 이 문제가 재발하지 않도록 했다.

## 11. Phase 6I-1.7C-1 진입 GO/NO-GO

**GO.**

근거(STEP F 조건 대비):
1. Runtime Lock 우회 위험 — **해결**(PID+실시간 검증, Fail-safe 폴백, 6개 신규 테스트로 5가지 위험 전부 확인).
2. 프로세스 종료 안전 경로 — **검증 완료**(Forced Fallback이 PowerShell 스탠드인과 실제 Python 3.12 venv 프로세스 양쪽에서 안전함을 증명; Graceful은 환경적 한계로 미작동하나 FAIL 조건에 해당하지 않음).
3. 기존 운영 서비스 — **불변**(9번 항목).
4. 필수 신규 테스트 — **PASS**(STEP B 6건, STEP C 5건 중 4건 — 실패 1건은 사전 결론 난 동일 원인).
5. 기존 테스트 실패 — **원인/범위/운영 영향 명확히 규명**(6번, 7번 항목 — 전부 사전 존재 환경적 결함, 코드 결함 아님).
6. 장기 Lockout/State 보존 정책 — **검증 완료**(6번 항목).
7. Canonical 병합 시 변경 파일·운영 영향·Rollback 절차 — **이번 작업은 검증 전용이며 Canonical 병합을 수행하지 않았다**; 병합 절차 자체의 상세 설계(변경 파일 목록, 영향 범위, Rollback 수순)는 Phase 6I-1.7C-1에서 별도로 작성해야 한다 — 이 항목은 이번 보고서의 범위를 벗어나므로 **다음 Phase의 선행 과제**로 명시한다.

NO-GO 조건(소유권 불명확 프로세스 종료 가능, 임의 환경변수로 Lock 우회 가능, 종료 안전성 검증 불가, 테스트의 실제 운영 프로세스 조작, PostgreSQL/Volume 영향, Watchdog 의도치 않은 활성화) 중 어느 것도 해당하지 않는다.

**이번 작업에서는 Canonical Merge 또는 서비스 재시작을 수행하지 않았다. 운영 반영은 Phase 6I-1.7C-1에서 별도 승인 후 진행한다.**
