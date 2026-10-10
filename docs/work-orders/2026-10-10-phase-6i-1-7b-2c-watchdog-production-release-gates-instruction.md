# WO-RUNNINGAI-PHASE-6I-1.7B-2C — Watchdog Production Release Gates (작업 지시서 원문)

## Watchdog Production Release Gates

### 목적

Phase 6I-1.7B-2B 개발 결과를 기반으로 운영 배포 전 필수 안전성 문제를 해결한다.

이번 Phase는 최종 개발 안정화 단계다.

해결 대상:
1. Watchdog PreStop과 수동 start/stop의 동시성 충돌
2. 실제 실패 컴포넌트와 Restart/Lockout 예산 귀속 불일치
3. Ctrl+C 종료 경로의 환경별 불안정성
4. Lockout 해제 도구의 ReadOnly/동시성 문제

### 기준 환경

Development:
`C:\Users\simjy\orca\workspaces\running-ai-github\main-3`

Canonical:
`C:\running-ai-github`

Expected main:
`f6a213588c5b7855b0dc7bf99948896291240ed7`

Expected main-3:
`b5a84631e2f5f9233c3b211a15bae76cfb1d9449`

Spring Boot 3.5.16 / Java 21 유지.

운영환경은 수정하지 않는다.

### STEP 1 — 동시 실행 보호

현재 구조의 문제:

- start/stop은 Runtime Mutex를 획득한다.
- Watchdog 자체는 획득하지 않는다.
- 그러나 Watchdog PreStop은 자식 start 스크립트 실행 전에 프로세스를 직접 종료할 수 있다.

필수 수정:

- Watchdog PreStop과 후속 Start를 하나의 직렬화된 복구 작업으로 묶는다.
- 동일 Runtime에 대한 수동 Start/Stop과 상호 배제한다.
- 부모 Watchdog가 Lock을 보유한 상태로 동일 Lock을 기다리는 자식을 실행하는 Deadlock은 금지한다.
- Lock 획득 실패는 BUSY/SKIPPED로 처리하며 프로세스를 변경하지 않는다.
- Busy는 장기 실패 횟수에 포함하지 않는다.
- Relay 복구 역시 수동 Stop과 충돌하지 않도록 보호한다.
- 임시 Worktree 테스트와 Canonical Runtime의 Lock을 격리한다.

가능하면 복구 작업 자체를 단일 Lock 소유 프로세스가 수행하도록 구성한다.

### STEP 2 — 장애 귀속과 예산 정확성

기존 Boolean Runner 결과를 개선한다.

권장 구조:

- AttemptedComponent
- ActualFailedComponent
- ResultCode
- ActionPerformed
- Retryable
- BudgetChargeComponent

필수 규칙:

1. 실제 복구 대상 컴포넌트에만 시도 예산을 기록한다.
2. 실제 실패 컴포넌트에만 장기 실패 횟수를 기록한다.
3. Busy/Skipped/Blocked는 장기 실패 횟수에 포함하지 않는다.
4. 실패 원인을 식별할 수 없으면 임의의 컴포넌트에 귀속하지 않는다.
5. 성공하지 않은 실행을 성공으로 기록하지 않는다.
6. START와 RESTART의 실제 결과를 재관찰하여 검증한다.
7. Spring 실패가 Connector Lockout을 발생시키지 않는다.
8. Core 복구 실패와 Relay 복구 결과는 독립적으로 집계한다.

컴포넌트별 Executor 분리가 필요하다면 이번 단계에서 진행한다.

전체 start-running-ai.ps1의 수동 실행 계약은 유지한다.

### STEP 3 — Ctrl+C 환경별 종료 검증

운영 Connector에는 접근하지 않는다.

동일 Windows PC의 격리 환경에서 다음을 검증한다.

- 실제 Python 3.12 venv Launcher/Interpreter 구조
- 부모/자식 프로세스 관계
- Send-CtrlC.ps1의 신호 전달
- Graceful 종료
- 신호 실패 시 안전한 Forced 종료
- 전체 종료 후 포트 해제
- 비관리 프로세스 보존
- PID 재사용 및 소유권 변경 시 종료 거부

실패한 기존 Ctrl+C 테스트 2건은 삭제하거나 단언을 약화하지 않는다.

환경에서 Ctrl+C가 작동하지 않는다면 원인을 기록하고, Forced 종료가 소유권 검증을 통해 안전하게 동작하는지 별도로 확인한다.

필수 종료 경로가 검증되지 않으면 운영 배포 FAIL로 처리한다.

### STEP 4 — Lockout State 안전성

검토 대상:

- Read-WatchdogState
- Save-WatchdogState
- clear-watchdog-lockout.ps1
- watchdog-state.json

요구사항:

1. Lockout 미리보기는 항상 ReadOnly.
2. 명시적 -Force에서만 상태 변경.
3. Watchdog 저장과 수동 Lockout 해제의 경합 방지.
4. 실패 시 기존 Lockout 정보 보존.
5. V1/V2 정상 호환.
6. 손상된 상태 파일은 자동 복구하지 않고 Fail-safe.
7. 운영 중 사용되던 State 파일이 예기치 않게 사라져 Lockout 기록이 초기화되는 위험을 검토하고 방어한다.
8. 상태 파일 저장 실패를 조용히 무시하지 않는다.

### STEP 5 — 필수 테스트

다음 시나리오를 포함한다.

1. Watchdog PreStop과 수동 Stop 충돌
2. Watchdog PreStop과 수동 Start 충돌
3. Relay 복구와 수동 Stop 충돌
4. Runtime Lock 중첩 교착 방지
5. Busy는 재시작 실패 횟수 미증가
6. Spring 실패 시 Connector 장기 실패 횟수 미증가
7. Connector 실패 시 Connector 예산만 증가
8. UNKNOWN_FAILURE 처리
9. ActualComponent와 PlannedComponent 불일치
10. Relay 독립 복구
11. Lockout 임계값과 유지
12. Lockout 해제 중 Watchdog State 저장 경합
13. Lockout 미리보기 파일 무변경
14. Ctrl+C 성공 경로
15. Ctrl+C 실패 후 안전한 종료 경로
16. PID/포트 소유권 변경 시 종료 거부
17. PostgreSQL 무변경
18. 기존 운영 포트 비접촉

### STEP 6 — 회귀 테스트

다음 5개 스위트를 모두 실행한다.

- Test-RunningAI.ps1
- Test-Watchdog.ps1
- Test-ExternalRelay.ps1
- Test-ConnectorOwnership.ps1
- Test-WatchdogRecoveryPolicy.ps1

완료 기준:

- 전체 회귀 PASS
- PS5.1 파싱 PASS
- git diff --check PASS
- 비밀정보 스캔 PASS
- 격리 테스트 프로세스 잔여 없음
- 실제 운영 프로세스 불변
- Canonical Git 불변

테스트가 실패하면 환경적 한계와 코드 결함을 구분하여 보고하되, 필수 안전성 검증이 끝나지 않은 상태를 PASS로 보고하지 않는다.

### STEP 7 — Git

모든 필수 검증 통과 시 개발 Worktree에서만 Commit/Push.

권장 Commit:

`fix(windows): close watchdog production safety gaps`

origin/main-3 Fast-forward Push 허용.

origin/main Push와 Canonical Merge는 금지한다.

### 절대 금지

- 운영 Connector/Spring/Relay 재시작 및 종료
- Watchdog 활성화
- Startup Scheduled Task 수동 실행
- 운영 .env 수정
- 운영 PID 및 State 파일 변경
- Docker Compose 실행
- PostgreSQL 변경
- Windows 재부팅
- 실제 Garmin 동기화·훈련 생성·게시
- 기존 장애 증거 삭제
- Legacy C:\running-ai 변경

### 최종 보고

1. 변경 파일
2. Runtime Lock 최종 소유 구조
3. 실패 컴포넌트 귀속 및 예산 정책
4. Busy/Skipped/Blocked 처리
5. Relay 독립성
6. Ctrl+C 격리 검증 결과
7. Lockout 상태 파일 안전성
8. 전체 5개 회귀 스위트 결과
9. Git Commit SHA
10. Canonical 및 운영환경 불변
11. 남은 위험
12. Phase 6I-1.7C 배포 준비 PASS/FAIL

운영 적용은 별도 승인 전까지 수행하지 않는다.
