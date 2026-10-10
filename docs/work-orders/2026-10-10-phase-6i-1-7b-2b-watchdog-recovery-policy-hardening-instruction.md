# WO-RUNNINGAI-PHASE-6I-1.7B-2B — Windows Watchdog Recovery Policy Hardening (작업 지시서 원문)

# WO-RUNNINGAI-PHASE-6I-1.7B-2B
## Windows Watchdog Recovery Policy Hardening

### 1. 목표

RunningAI Windows Watchdog의 자동 복구 정책을 개선한다.

이번 Phase에서 해결할 문제:

1. Connector 소유권 판정 일관성
2. 실제 장애 컴포넌트 귀속
3. 반복 장애에 대한 영구적인 자동 재시작 차단
4. Relay 독립 복구
5. Startup / Watchdog / 수동 시작의 동시 실행 충돌
6. DryRun의 완전한 읽기 전용 보장
7. 이전 Connector 소유권 코드의 잔여 안전성 문제

### 2. 기준 환경

Repository:
`simjjy89/running-ai`

Development Worktree:
`C:\Users\simjy\orca\workspaces\running-ai-github\main-3`

Canonical:
`C:\running-ai-github`

Expected:
- origin/main: `f6a213588c5b7855b0dc7bf99948896291240ed7`
- origin/main-3: `0ff78266cb2fe9066d8c5b23060c0be73522e30e`

기술 스택:
- Java 21
- Spring Boot 3.5.16
- PostgreSQL 17
- PowerShell 5.1

기존 정상 운영 서비스는 변경하지 않는다.

### 3. STEP A — 현재 코드 정합성 및 잔여 결함 보완

#### A-1. Connector 재관찰 일관성

현재 최초 관찰은 ConnectorPort를 전달하지만, Invoke-WatchdogRecovery 내부에서는 Get-ComponentStates 호출 시 ConnectorPort가 누락된다.

전체 복구 사이클에서 동일한 Connector 소유권 판정 정책을 사용하도록 수정한다.

- 최초 Observe
- 복구 계획 수립
- Action 실행 후 Observe
- 최종 상태 평가

모두 동일한 ConnectorPort와 Ownership 정책을 사용해야 한다.

#### A-2. already-gone 메타데이터 정리

Connector Stop에서 다음을 보장한다.

- 포트 해제 확인
- 관리 프로세스 부재 확인
- 소유권 상태 재검증

세 조건을 충족하기 전에는 PID 파일 및 Sidecar를 제거하지 않는다.

`already-gone`이라도 포트가 여전히 점유된 경우에는 메타데이터를 보존한다.

#### A-3. DOWN 판정과 살아 있는 Launcher 구분

LISTEN 포트가 비어 있어도 추적 중인 Launcher가 살아 있다면 단순 DOWN으로 분류해 새로운 Connector를 중복 실행하지 않는다.

이 상황은 START 전 차단 또는 안전한 기존 프로세스 정리 대상으로 구분한다.

소유권 확인이 실패하면 Fail-safe로 중단한다.

### 4. STEP B — 실제 장애 컴포넌트 귀속

현재 Invoke-RecoveryAction은 Connector/Spring 등의 복구를 위해 전체 start-running-ai.ps1을 호출한다.

그 결과 실제로 Spring이 실패했는데 Connector 복구 시도 실패로 기록될 수 있다.

이를 수정한다.

필수 요구사항:

1. Docker 장애는 Docker로 기록.
2. PostgreSQL 장애는 PostgreSQL로 기록.
3. Connector 장애는 Connector로 기록.
4. Spring 장애는 Spring으로 기록.
5. Relay 장애는 Relay로 기록.
6. 하위 컴포넌트 실패를 상위 컴포넌트의 복구 실패로 잘못 기록하지 않는다.
7. 실제로 시작하거나 재시작한 컴포넌트와 복구 예산을 소비한 컴포넌트가 일치해야 한다.

가능하면 컴포넌트별 Recovery Executor를 분리한다.

단, 기존 start-running-ai.ps1의 정상 수동 시작 동작은 유지한다.

기존 전체 시작 스크립트를 그대로 사용해야 하는 경우에는 실제 Exit Code와 실행 후 관찰 결과를 근거로 실패 컴포넌트를 귀속한다.

정확한 귀속이 불가능하면 UNKNOWN_FAILURE로 기록하고 다른 컴포넌트의 예산을 소비하지 않는다.

### 5. STEP C — 지속적 Restart Lockout

현재 정책:

- 10분 동안 최대 3회
- 이동 창 만료 후 다시 시도 가능

문제:

지속적인 장애가 발생하면 5분마다 실행되는 Watchdog가 장기적으로 무한 재시도할 수 있다.

신규 정책은 다음을 만족해야 한다.

1. 단기 재시작 제한 유지.
2. 장기 누적 실패 횟수 제한 추가.
3. 장기 한도 초과 시 지속적 LOCKED_OUT 전환.
4. Lockout은 재부팅 및 Watchdog 프로세스 재실행 후에도 유지.
5. Lockout 중 자동 재시작 없음.
6. Lockout 상태는 로그와 status JSON에 표시.
7. 수동 개입 없이 자동으로 무한 재시도를 재개하지 않음.
8. 다른 컴포넌트의 정상 복구까지 차단하지 않음.

권장 초기 정책:

- 단기: 10분당 3회
- 장기: 24시간 이내 실패 6회
- 장기 한도 초과 시 운영자 확인 전 자동 복구 차단

재시도 횟수와 차단 정책은 설정 가능하도록 설계한다.

정확한 한도 정책을 문서화하고 테스트한다.

Lockout 해제 기능이 필요하면 별도 명시적 운영자 작업으로 설계한다. 이번 단계에서 실제 운영 Lockout을 해제하지 않는다.

### 6. STEP D — Watchdog State 호환성 및 무결성

기존 watchdog-state.json의 재시작 기록을 보존한다.

새 구조가 필요하면 Version Migration을 구현한다.

요구사항:

- 기존 Version 1 정상 로드
- 기존 기록 손실 없음
- 새 버전 저장 시 Atomic Write
- 손상된 파일은 복구 실행 없이 Fail-safe
- 파일 손상 또는 누락으로 재시작 한도가 무조건 초기화되지 않도록 검토
- 이미 Lockout된 상태는 재부팅 후에도 유지
- 시간대와 시스템 시각 변경에도 안전한 판단
- 로그와 JSON에 비밀정보 기록 금지

실제 운영의 watchdog-state.json은 수정하지 않는다.

### 7. STEP E — External Relay 독립 복구

현재 Get-RecoveryPlan은 Relay를 별도 계획하지만, 실제 Recovery Executor는 첫 실패 시 전체 루프를 종료한다.

따라서 Spring 또는 Connector 복구가 실패하면 같은 실행에서 Relay 복구가 진행되지 않을 수 있다.

이를 개선한다.

필수 조건:

1. Spring 복구 실패와 Relay 복구는 독립적.
2. Connector 실패와 Relay 복구는 독립적.
3. Docker/PostgreSQL 장애도 Relay 복구를 불필요하게 막지 않음.
4. Relay 실패는 Spring/Connector 복구를 막지 않음.
5. Relay는 기존 전용 start-external-relay.ps1 경로 유지.
6. Relay와 Spring의 재시작 예산은 별도로 계산.
7. 한 컴포넌트의 Lockout이 다른 독립 컴포넌트를 Lockout하지 않음.
8. Relay 자체의 포트 소유권이 불분명할 때 무리하게 종료하지 않음.

외부 Funnel 설정은 변경하지 않는다.

### 8. STEP F — 동시 실행 방지

다음 진입점을 확인한다.

- RunningAI-Startup Scheduled Task
- RunningAI-Watchdog Scheduled Task
- 수동 start-running-ai.ps1
- 수동 stop-running-ai.ps1
- Watchdog Recovery Executor

동일한 운영 서비스에 대한 시작과 종료가 동시에 실행되지 않도록 설계한다.

주의:

Watchdog가 Mutex를 소유한 채 자식 PowerShell의 시작 스크립트를 실행하는 경우, 자식이 동일 Mutex를 다시 획득하려 하면 Deadlock이 발생할 수 있다.

다음을 만족하는 구조를 설계한다.

1. 동일 Canonical Runtime에 대한 공통 실행 직렬화.
2. 개발 Worktree와 Canonical 경로 구분.
3. 중복 실행 시 무조건 강제 종료하지 않고 Skip/Busy 처리.
4. Lock 소유 프로세스의 비정상 종료 대응.
5. Lock 획득 실패 시 서비스 상태 변경 없음.
6. Watchdog → 시작 스크립트 경로에서 자기 교착 없음.
7. Startup과 수동 시작 충돌 방지.
8. Stop과 자동 Restart 충돌 방지.
9. 오래된 Lock 파일만으로 정상 시작을 영구 차단하지 않도록 설계.

공통 Lock 구조가 명확히 검증되지 않으면 기능을 임의로 활성화하지 않는다.

### 9. STEP G — DryRun 완전 읽기 전용

다음 호출의 부작용을 검사한다.

- Read-WatchdogState
- Get-TrackedProcessId
- Get-RuntimeObservation
- Get-ComponentStates
- Get-RecoveryPlan
- 기타 DryRun 호출부

현재 Read-WatchdogState는 손상된 상태 파일을 .corrupt로 이동할 수 있고, Get-TrackedProcessId는 잘못된 PID 파일을 삭제할 수 있다.

DryRun 경로에서는 이런 쓰기 동작이 절대 발생하지 않도록 수정한다.

DryRun 금지 동작:

- 파일 생성·수정·삭제
- 로그 로테이션
- 상태 파일 Quarantine
- PID 파일 정리
- Docker Compose 실행
- 서비스 시작/종료
- Garmin API 동기화

읽기 전용 검사 결과만 출력한다.

### 10. STEP H — 테스트

운영 서비스를 사용하는 실험은 금지한다.

기존 Windows 테스트 전체를 유지한다.

필수 신규 시나리오:

1. Spring 실패가 Connector 예산을 소비하지 않음.
2. Connector 실패만 Connector 예산 소비.
3. 실제 동작하지 않은 컴포넌트는 예산 미소비.
4. 10분 내 3회 제한.
5. 24시간 누적 실패 한도.
6. Lockout 후 5분 주기 재실행.
7. Lockout 후 Watchdog 프로세스 재시작.
8. Lockout 후 Windows 재부팅을 모사한 상태 복원.
9. 한 컴포넌트 Lockout 시 Relay 독립 복구.
10. Spring 실패 중 Relay 복구 성공.
11. Connector 실패 중 Relay 복구 성공.
12. Relay 실패 중 Spring 정상 유지.
13. Startup과 Watchdog 동시 시작 시뮬레이션.
14. 수동 시작과 자동 시작 충돌.
15. Stop과 자동 Restart 충돌.
16. 중첩 Mutex Deadlock 방지.
17. DryRun 중 손상된 State 파일.
18. DryRun 중 오래된 PID 파일.
19. DryRun 파일 해시 변경 없음.
20. 기존 Watchdog Version 1 State 마이그레이션.
21. Connector DOWN이지만 Launcher 생존.
22. already-gone 결과에서 포트가 점유된 경우 메타데이터 보존.
23. Connector 최초/재관찰 소유권 분류 일치.
24. PostgreSQL 데이터/Volume을 수정하지 않는 경로.
25. 기존 Spring Boot 및 Relay 동작 회귀 없음.

테스트는 Mock 및 격리된 임시 프로세스·포트·파일만 사용한다.

실제 운영 포트 8765, 18080, 17845를 통한 재시작·종료 테스트는 금지한다.

### 11. STEP I — 회귀 및 품질 검사

최소 실행:

- Test-RunningAI.ps1
- Test-Watchdog.ps1
- Test-ExternalRelay.ps1
- Test-ConnectorOwnership.ps1
- 기타 관련 Windows 테스트

추가:

- Windows PowerShell 5.1 Parsing
- git diff --check
- 비밀정보 스캔
- 테스트 잔여 프로세스 확인
- 테스트 임시 파일 확인
- Canonical 및 운영 프로세스 불변 확인

테스트 실패가 있으면 수정 후 재실행한다.

### 12. Git

모든 필수 테스트가 PASS이고 예상하지 못한 변경이 없다면 개발 브랜치에서만 Commit한다.

권장 Commit:

`fix(windows): harden watchdog recovery lifecycle and budgets`

origin/main-3 Fast-forward Push 허용.

다음은 금지한다.

- origin/main Push
- Canonical Merge
- Canonical Worktree 수정
- 운영 서비스 시작/종료
- Watchdog 활성화
- Windows 재부팅

### 13. 완료 보고

1. 변경 파일
2. 장애 귀속 정책
3. 장기 Lockout 상태 설계
4. 상태 JSON 버전 및 마이그레이션
5. Relay 독립 복구 검증
6. 동시 실행 Lock 구조
7. DryRun 읽기 전용 검증
8. Connector 잔여 결함 보완
9. 신규 테스트 및 전체 회귀 결과
10. Commit SHA
11. origin/main-3 상태
12. Canonical 및 운영환경 불변 확인
13. 배포 이전 남은 위험
14. Phase 6I-1.7C 진입 가능 여부

운영환경을 변경하지 않고 모든 결과를 보고한다.
