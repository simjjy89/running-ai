# WO-RUNNINGAI-PHASE-6I-1.7D-2 — Isolated Watchdog Recovery & Failure Simulation (작업 지시서 원문)

## Isolated Watchdog Recovery & Failure Simulation

### 1. 목표

실제 운영 Watchdog의 정상 상태 감시가 완료되었다.

이번 Phase에서는 격리 환경에서 장애를 시뮬레이션해 자동 복구 정책을 검증한다.

검증 대상:
- 장애 탐지 및 분류
- 안전한 복구 계획
- Connector 소유권 보호
- Relay 독립 복구
- 재시작 예산과 장기 Lockout
- Runtime Lock 경합
- 상태 파일 영속성과 장애 복원

운영 서비스는 중지하거나 재시작하지 않는다.

### 2. 현재 기준

Canonical:
`C:\running-ai-github`

Development:
`C:\Users\simjy\orca\workspaces\running-ai-github\main-3`

Legacy:
`C:\running-ai` — 절대 변경 금지

Expected:
- Canonical main: `36e1e96cc92a7c6f1d808b243bee19bc9a316abf`
- origin/main: `f6a213588c5b7855b0dc7bf99948896291240ed7`
- origin/main-3: `39917fb42890a8cd94ede2ce3151a12bf553e32c`

운영 Watchdog는 Enabled이며 5분 주기로 정상 실행 중이라는 최신 보고가 있다.

새 PID 기준:
- Spring 29984
- Connector Launcher 25372
- Connector Listener 28684
- Relay 13588

PID는 작업 시작 시 실제 상태와 CreationDate를 재확인한다. 변경돼 있다면 그 이유를 먼저 조사한다.

### STEP A — 운영 기준선 확보

읽기 전용으로 확인한다.

1. 5개 구성요소 Health.
2. PID 및 CreationDate.
3. PostgreSQL Container ID, Health, Volume.
4. PostgreSQL 데이터 영속성 기준선(기존 애플리케이션 데이터 개수, Garmin 동기화 HighWater 등 조회 가능한 읽기 전용 지표).
5. Watchdog Enabled.
6. 가장 최근 5분 간격 자동 실행 기록.
7. watchdog-state.json v2.
8. LongTerm Lockout 전부 해제 상태.
9. Restart Budget 정상.
10. Git Working Tree와 브랜치 SHA.

운영환경에 문제가 있으면 격리 테스트를 진행하기 전에 원인부터 보고한다.

### STEP B — 테스트 격리 검증

과거 테스트에서 격리 RuntimeDir이 의도한 시점에 적용되지 않아 실제 시작 스크립트가 실행된 사례가 있었다.

이번에는 아래 안전 조건을 반드시 충족해야 한다.

- 환경변수를 dot-source보다 먼저 설정.
- 테스트별 고유 RuntimeDir.
- 테스트별 고유 State 파일.
- 운영 .env를 테스트에 로드하지 않음.
- 운영 PID/Sidecar 접근 금지.
- 실제 Docker/PostgreSQL 명령 금지.
- 운영 포트 8765/18080/17845 미사용.
- 실제 RunningAI Scheduled Task 변경 금지.
- Runtime Lock 이름이 Canonical과 다름을 검증.
- 별도 프로세스에서 테스트하고 종료 시 자식 프로세스까지 정리.

특히 실제 `Invoke-RunningAiCoreRecoveryAction`은 Canonical의 start-running-ai.ps1을 실행할 수 있으므로, 단순한 RuntimeDir 변경만으로 안전한 테스트라고 간주하지 않는다.

실제 실행 경계는 반드시 주입형 Fake Executor 또는 완전히 독립된 테스트 Harness로 대체한다.

안전성을 입증하지 못하면 해당 테스트는 실행하지 않고 NO-GO로 보고한다.

### STEP C — 장애 분류 검증

격리된 Observation/Runner를 사용해 다음을 검증한다.

1. Connector DOWN → 정상 복구 계획.
2. Connector HEALTH 실패 + 관리 PID 생존.
3. Connector ORPHANED_MANAGED_PROCESS.
4. Connector FOREIGN_PROCESS → 종료 거부.
5. Connector UNKNOWN_OWNER → 종료 거부.
6. Launcher 생존 + Listener 부재.
7. PID 재사용 → 소유권 거부.
8. Spring DOWN → Spring 복구 계획.
9. PostgreSQL stopped → 의존성 순서 보존.
10. PostgreSQL unhealthy → 무리한 복구 금지.
11. 외부 Relay DOWN → 독립 복구 계획.

### STEP D — 자동 복구 실행 정책

실제 서비스 대신 테스트 전용 프로세스와 Fake Executor를 사용한다.

검증 항목:

- Connector 복구 성공/실패
- Spring 복구 성공/실패
- Relay 독립 복구
- 실제 실패 컴포넌트로 예산 귀속
- BUSY는 예산 미소비
- BLOCKED는 장기 실패 미증가
- UNKNOWN_FAILURE에 대한 잘못된 Lockout 방지
- 종료 실패 이후 신규 Start 차단
- 복구 성공 후 재관찰
- 예기치 않은 상태 변경 시 Fail-safe

복구 성공 판정은 Runner 반환값만이 아니라 최종 관찰 상태를 기준으로 한다.

### STEP E — Lockout 및 State 복원

격리 State 파일로 검증한다.

- 10분/3회 제한
- 24시간/6회 장기 실패 제한
- Lockout 이후 자동 복구 금지
- 다른 컴포넌트 복구 영향 없음
- 프로세스 재시작 이후 Lockout 유지
- v2/.bak 일관성
- State 파일 손상 시 Fail-safe
- 운영자 Lockout 해제 시뮬레이션
- State 저장 경합
- Lockout 해제와 Watchdog 저장 충돌 방지

운영 Lockout 상태를 실제로 변경하지 않는다.

### STEP F — 기존 회귀

필수 테스트:

- Test-RunningAI.ps1
- Test-Watchdog.ps1
- Test-ExternalRelay.ps1
- Test-ConnectorOwnership.ps1
- Test-WatchdogRecoveryPolicy.ps1
- Test-MainPcPythonVenvCtrlC.ps1

기존 Ctrl+C 환경 실패는 별도로 기록한다.

실패한 테스트를 삭제하거나 결과를 PASS로 바꾸지 않는다.

이번 Phase에서 새로 구현한 테스트는 모두 PASS해야 한다.

### STEP G — 운영 상태 최종 확인

격리 테스트 완료 후:

1. 운영 Watchdog가 계속 정상적으로 5분 주기 실행되는지 확인.
2. Spring/Connector/Relay PID 및 CreationDate 불변.
3. PostgreSQL ID/Volume/Health 불변.
4. 애플리케이션 DB 기준선 유지.
5. 운영 Watchdog State가 테스트에 의해 변경되지 않았는지 확인.
6. 실제 Recovery Action이 발생하지 않았는지 확인.
7. 테스트 프로세스 잔여 없음.
8. Canonical Working Tree Clean.
9. 외부 Relay 및 Public API 정상.

### STEP H — Git

검증만 수행했다면 코드 커밋은 필요 없다.

테스트 보완이 필요하면 개발 Worktree에서만 수정한다.

전체 검증 성공 시 origin/main-3 Fast-forward Push 허용.

Canonical main, origin/main 수정 금지.

### STEP I — 최종 보고

1. 운영 Preflight
2. 테스트 격리 보장 방법
3. 장애 분류 결과
4. Core 복구 검증
5. Relay 독립 복구 검증
6. 재시작 예산 및 Lockout 결과
7. Runtime Lock 안전성
8. State 영속성 검증
9. 전체 회귀 결과
10. 운영 PID 및 PostgreSQL 불변
11. Watchdog 주기 실행 유지
12. 변경 파일 및 Commit SHA
13. 남은 운영 위험
14. Phase 6I-1.7D-3 진입 GO/NO-GO

작업 완료 후 STOP한다.

### 절대 금지

- 실제 Spring/Connector/Relay 종료
- 실제 PostgreSQL 종료·재시작
- 실제 Watchdog 비활성화·재등록
- Windows 로그아웃·재부팅
- 운영 PID/Sidecar/State 수정
- 운영 .env 변경
- 실제 Garmin 동기화
- 훈련 생성·게시
- Canonical Git Merge/Reset
- origin/main Push
- Legacy 저장소 접근

이번 Phase는 격리 환경에서의 장애 자동 복구 검증이다. 운영 재부팅 검증은 별도 승인 없이는 진행하지 않는다.
