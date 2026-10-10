# WO-RUNNINGAI-PHASE-6I-1.7C-0 — Main PC Pre-Deployment Validation & Release Gate (작업 지시서 원문)

## Main PC Pre-Deployment Validation & Release Gate

### 0. 작업 선언

Phase 6I-1.7B-2C는 이미 완료되었다.

확인된 커밋:
- 07e999aaaa46eb4b37a32bb952328df53fd6a0f7
- 111ce745821154930d96dc3d879d9610480ef7c6

기존 작업을 다시 구현하거나 반복하지 않는다.

이번 작업의 목표는 **Phase 6I-1.7C 운영 반영 전에 남아 있는 위험을 검증하고 Release GO/NO-GO를 결정하는 것**이다.

이번 작업은 운영 배포가 아니다.

### 1. 기준 환경

Canonical:
`C:\running-ai-github`

Development:
`C:\Users\simjy\orca\workspaces\running-ai-github\main-3`

Legacy:
`C:\running-ai` — 접근 및 변경 금지

Expected:
- main / origin/main: f6a213588c5b7855b0dc7bf99948896291240ed7
- main-3 / origin/main-3: 111ce745821154930d96dc3d879d9610480ef7c6

기존 Untracked 문서는 보존한다.

운영 서비스:
- Spring: 127.0.0.1:18080
- Garmin Connector: 127.0.0.1:8765
- External Relay: 127.0.0.1:17845
- PostgreSQL 17
- Watchdog: Disabled

### 2. STEP A — 사전 점검

읽기 전용으로 확인한다.

1. Git 브랜치 SHA 및 Working Tree
2. 기존 Untracked 파일 목록
3. Watchdog Disabled
4. RunningAI-Startup 작업의 설정
5. 기존 운영 포트와 실제 LISTEN PID
6. PostgreSQL 컨테이너 ID, Health, Volume
7. 운영 .env 존재 여부 및 18080/Loopback 설정
8. 현재 Runtime Lock 관련 환경변수 충돌 여부

비밀번호, 토큰, 전체 환경변수 값은 출력하지 않는다.

사전 상태 불일치 시 STOP.

### 3. STEP B — Runtime Lock Trusted Child 안전성

현재 `Enter-RunningAiRuntimeLock`은 다음 환경변수만으로 Lock 상속 여부를 판단한다.

`RUNNING_AI_WATCHDOG_LOCK_INHERITED=1`

이 환경변수의 존재만으로 실제 Watchdog가 Lock을 소유한 자식이라고 단정할 수 없다.

다음 위험을 검증한다.

1. 수동 실행 프로세스에서 같은 환경변수가 설정된 경우 Lock 우회 가능 여부
2. 환경변수가 자식·손자 프로세스에 전파되는 경우
3. Watchdog 부모가 이미 종료됐는데 자식이 Lock을 우회하는 경우
4. 임의의 별도 PowerShell에서 상속 플래그를 설정한 경우
5. 동일 Repository가 아닌 Worktree 실행에서 플래그가 남아 있는 경우

필수 조건:

- 실제 Watchdog가 관리하는 실행만 신뢰할 것
- 단순 환경변수 설정만으로 Runtime Lock을 우회할 수 없을 것
- 부모가 Lock을 보유하고 있다는 전제가 깨지면 Fail-safe
- 기존 Deadlock 방지 기능 유지
- PowerShell 5.1 호환

이 조건을 만족하지 못한다면 개발 Worktree에서만 최소 수정한다.

필요한 검증이 불가능하면 NO-GO로 보고한다. 임시로 Lock을 비활성화하거나 우회하지 않는다.

### 4. STEP C — Main PC 격리 Ctrl+C 검증

실제 운영 Connector는 중단하지 않는다.

먼저 격리 테스트 Harness가 운영환경에 영향을 주지 않음을 소스 수준에서 검증한다.

과거 테스트에서 실제 start-running-ai.ps1이 의도치 않게 실행된 이력이 있으므로 다음 사항을 반드시 확인한다.

- 임시 RuntimeDir을 dot-source 이전에 구성
- 운영 .env를 테스트에 로드하지 않음
- 실제 Docker/PostgreSQL 명령 실행 경로 없음
- RunningAI-Startup 및 Watchdog 작업 실행 없음
- 실제 8765/18080/17845 포트 미사용
- 테스트 전용 프로세스만 종료
- 실제 Canonical PID 파일과 Watchdog State 파일 무접촉

검증 가능한 경우 Main PC에서 다음 구조를 재현한다.

1. Python 3.12 테스트 전용 venv 생성
2. 테스트 Launcher 실행
3. 자식 Python 인터프리터 생성 확인
4. 임시 Loopback 포트 LISTEN
5. Launcher/Listener 소유권 판정
6. Ctrl+C 전달
7. 정상 Graceful 종료 확인
8. Ctrl+C 전달 실패 시 안전한 Forced Fallback
9. 포트 해제와 전체 관리 PID 종료 확인
10. 비관리 프로세스 보존 확인

Ctrl+C가 작동하지 않는 환경이라면 원인과 OS/콘솔 조건을 기록한다.

Graceful 실패만으로 임의의 운영 프로세스를 종료하지 않는다.

Forced Fallback까지 실패하거나 안전성을 확인할 수 없으면 NO-GO로 판정한다.

기존 실패 테스트 2건을 삭제하거나 단언을 약화하지 않는다.

### 5. STEP D — 상태 파일 및 자동 복구 정책 검증

운영 State 파일을 수정하지 않고 확인한다.

1. watchdog-state.json의 현재 버전
2. v1 → v2 마이그레이션 호환성
3. 기존 Restart History 보존
4. LongTerm Lockout 보존
5. .bak 파일 처리
6. Lockout 해제 도구의 ReadOnly 안전성
7. BUSY/BLOCKED/UNKNOWN_FAILURE 예산 귀속
8. Spring 장애와 Connector Lockout의 독립성
9. Relay 독립 복구
10. 24시간/6회 기본 임계값

상태 파일 변환 및 저장 테스트는 격리된 임시 파일로만 수행한다.

운영 상태 파일에 실제 v2 마이그레이션을 적용하지 않는다.

### 6. STEP E — 최종 회귀 테스트

필수 스위트:

- Test-RunningAI.ps1
- Test-Watchdog.ps1
- Test-ExternalRelay.ps1
- Test-ConnectorOwnership.ps1
- Test-WatchdogRecoveryPolicy.ps1

각 스위트의 Exit Code와 통과/실패 건수를 분리해 보고한다.

기존 Ctrl+C 실패 2건은 실제 Main PC 검증 결과와 연결해서 설명한다.

전체 5개 스위트가 모두 Exit 0이 아닌 경우 이를 전체 PASS로 표현하지 않는다.

추가 검사:

- PowerShell 5.1 파싱
- git diff --check
- 비밀정보 검사
- 테스트 파일·프로세스 정리 확인
- 운영 LISTEN PID 불변
- PostgreSQL ID/Volume 불변
- Watchdog Disabled 유지

### 7. STEP F — Release GO/NO-GO

GO 조건:

1. Runtime Lock 우회 위험 해결
2. 프로세스 종료의 안전 경로 검증
3. 기존 운영 서비스 불변
4. 필수 신규 테스트 PASS
5. 기존 테스트 실패가 있다면 원인·범위·운영 영향 명확히 규명
6. 장기 Lockout 및 State 보존 정책 검증
7. Canonical 병합 시 변경 파일·운영 영향·Rollback 절차 작성 완료

NO-GO 조건:

- 소유권 불명확한 프로세스 종료 가능
- 임의 환경변수로 Runtime Lock 우회 가능
- Graceful/Forced 종료 안전성 검증 불가
- 테스트가 실제 운영 프로세스를 조작
- PostgreSQL/Volume 영향
- Watchdog가 의도치 않게 활성화됨

### 8. Git 정책

검증만 수행했다면 새 커밋은 필요 없다.

필수 수정이 발생한 경우:

- main-3에서만 수정
- 격리 테스트 통과
- 관련 파일만 Commit
- origin/main-3 Fast-forward Push

금지:

- Canonical main Merge
- origin/main Push
- 실제 서비스 재시작
- Watchdog 활성화

### 9. 최종 보고

1. 기준 Git SHA
2. 운영 사전 점검 결과
3. Runtime Lock 신뢰성 판정
4. Main PC Ctrl+C 테스트 결과
5. Graceful 및 Forced 종료 결과
6. State/Lockout 안전성
7. 전체 5개 테스트 결과
8. 수정 파일과 Commit SHA(있을 경우)
9. 실제 서비스/PID/PostgreSQL 불변 여부
10. 잔여 위험
11. Phase 6I-1.7C-1 진입 GO/NO-GO

GO 판정이더라도 이번 작업에서 Canonical Merge 또는 서비스 재시작을 수행하지 않는다.

후속 Phase 6I-1.7C-1에서 운영 반영을 별도 승인받는다.
