# WO-RUNNINGAI-PHASE-6I-1.7D-2R — Forced Fallback Regression Test Stabilization (작업 지시서 원문)

## Forced Fallback Regression Test Stabilization

### 목적

Phase 6I-1.7D-2는 이미 개발과 격리 복구 검증이 완료되었다.

이번 작업에서는 남아 있는 테스트 1건만 수정하여 회귀 결과를 안정화한다.

기존 운영 로직은 변경하지 않는다.

### 현재 기준

Canonical:
C:\running-ai-github

Development:
C:\Users\simjy\orca\workspaces\running-ai-github\main-3

Expected:

* main: 36e1e96cc92a7c6f1d808b243bee19bc9a316abf
* origin/main: f6a213588c5b7855b0dc7bf99948896291240ed7
* main-3/origin/main-3: 91d543d353f19bb0f077565d870a65a0c1606517

운영 Watchdog는 Enabled 상태이며, 정상적인 5분 주기 자동 감시를 유지해야 한다.

### STEP 1 — 문제 재현

대상 파일:

scripts/windows/tests/Test-MainPcPythonVenvCtrlC.ps1

현재 실패 테스트:

forced fallback stops ONLY the verified managed PIDs...

원인:

* 테스트가 TimeoutSec=0이면 Forced 경로에 바로 진입한다고 가정함.
* 실제 Stop-RunningAiConnectorManaged는 먼저 Ctrl+C를 전송함.
* 현재 환경에서는 Ctrl+C가 빠르게 성공해 Result=graceful이 반환될 수 있음.
* 따라서 테스트의 실행 경로 가정이 잘못됨.

Production 함수 자체는 수정하지 않는다.

### STEP 2 — 테스트 전용 Forced 조건 구성

기존 정상 종료 테스트는 그대로 유지한다.

Forced 전용 시나리오에는 별도 Python 3.12 테스트 Fixture를 생성한다.

* 테스트 전용 Python venv 사용
* 독립적인 Loopback Port 사용
* Listener 프로세스가 SIGINT(Ctrl+C)를 무시하도록 설정
* Launcher/Listener 소유권 검증
* 비관리 Bystander 프로세스 별도 실행

Python에서는 테스트 전용 Fixture에 다음과 같은 방식의 신호 무시를 사용할 수 있다.

```python
import signal
signal.signal(signal.SIGINT, signal.SIG_IGN)
```

단, Windows 환경에서 실제로 신호를 무시하고 Listener가 살아 있는지를 검증한 뒤 Forced 테스트를 진행한다.

신호 무시가 검증되지 않으면 해당 테스트를 PASS 처리하지 않는다.

### STEP 3 — Forced 종료 검증

반드시 실제 Stop-RunningAiConnectorManaged를 통과해야 한다.

검증 항목:

1. 초기 Verdict = LAUNCHER_CHILD
2. ManagedPidSnapshot 생성
3. Graceful 신호 전송 후 Listener 생존
4. Graceful 제한시간 경과
5. Forced Fallback 진입
6. Result = forced
7. ResultCode = STOPPED
8. PortFreed = true
9. RemainingPids = 0
10. Launcher/Listener 전부 종료
11. Bystander 프로세스 생존
12. PID CreationDate 재검증 유지
13. 비관리 프로세스 종료 없음

Result 단언을 단순히 graceful 또는 forced로 완화하는 것은 금지한다.

목적은 실제 Forced 코드 경로 검증이다.

### STEP 4 — 운영 격리

* 테스트 프로세스는 임시 디렉터리에서만 생성
* RUNNING_AI_TEST_RUNTIME_DIR 격리
* 운영 Python Connector 접근 금지
* 실제 포트 8765/18080/17845 접근 금지
* 운영 .env 로드 금지
* 실제 Docker/PostgreSQL 조작 금지
* 테스트 종료 후 모든 임시 프로세스 정리
* Watchdog Scheduled Task 변경 금지

테스트의 cleanup 단계에서도 프로세스 소유권을 확인하고, 운영 PID를 종료하지 않는다.

### STEP 5 — 전체 테스트

다음 6개 스위트를 실행한다.

1. Test-RunningAI.ps1
2. Test-Watchdog.ps1
3. Test-ExternalRelay.ps1
4. Test-ConnectorOwnership.ps1
5. Test-WatchdogRecoveryPolicy.ps1
6. Test-MainPcPythonVenvCtrlC.ps1

추가로 Node External Relay 테스트도 실행한다.

목표:

* 기존 성공 테스트 유지
* 신규 Forced 전용 시나리오 PASS
* 모든 실행 가능한 PowerShell 테스트 PASS
* Node 테스트 PASS
* PowerShell 5.1 호환
* git diff –check PASS
* 비밀정보 검사 PASS

환경상 실행 불가 항목은 SKIP으로 별도 보고하고, PASS에 포함하지 않는다.

### STEP 6 — 운영 불변 검증

테스트 전후 다음을 비교한다.

* Spring PID / CreationDate
* Connector Launcher/Listener PID
* Relay PID
* PostgreSQL Container ID / Volume / Health
* RunningAI-Watchdog Enabled
* 자동 실행 LastTaskResult
* Watchdog Recovery Action
* Restart Budget / LongTerm Lockout
* DB activity 데이터 수 및 동기화 HighWater
* Local/Public Health

테스트로 인한 서비스 재시작은 0건이어야 한다.

### STEP 7 — Git

개발 Worktree에서만 수정한다.

예상 변경 파일:

scripts/windows/tests/Test-MainPcPythonVenvCtrlC.ps1

필요한 경우 해당 테스트 전용 Fixture만 추가한다.

금지:

* Production 코드 수정
* Canonical main 변경
* origin/main Push
* Watchdog 재등록
* 서비스 재시작
* 실제 장애 주입
* Windows 재부팅

전체 테스트가 완료되면 다음 커밋을 생성한다.

test(windows): make connector forced-fallback regression deterministic

origin/main-3로 Fast-forward Push한다.

### STEP 8 — 최종 보고

1. 원래 실패 원인
2. 수정 방식
3. Ctrl+C 무시 조건 검증
4. 실제 Forced 코드 경로 진입 증거
5. Bystander 보존 결과
6. 전체 6개 테스트 결과
7. Node 테스트 결과
8. 운영 PID/DB/Watchdog 불변
9. 변경 파일
10. Commit SHA
11. main/main-3 상태
12. Canonical FF 가능 여부
13. 남은 위험

작업 완료 후 STOP한다.

Canonical 병합과 재부팅은 이번 작업에서 수행하지 않는다.
