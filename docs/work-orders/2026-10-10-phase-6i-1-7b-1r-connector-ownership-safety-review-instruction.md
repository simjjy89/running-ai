# WO-RUNNINGAI-PHASE-6I-1.7B-1R
## Connector Ownership Safety Review & Corrections

### 목표

Phase 6I-1.7B-1 구현에서 발견된 프로세스 소유권 및 종료 안전성 결함을 수정한다.

기존 프로세스 관계 모델과 격리 테스트를 유지하고, 필요한 부분만 보완한다.

운영환경 변경 및 실제 Connector 재시작은 금지한다.

### 개발 환경

- Development Worktree: `C:\Users\simjy\orca\workspaces\running-ai-github\main-3`
- Canonical: `C:\running-ai-github` — 수정 금지
- Expected origin/main: `f6a213588c5b7855b0dc7bf99948896291240ed7`
- Expected origin/main-3: `a7c29670ca33b3a26cb7e6564992e3b4a0d63911`
- Spring Boot 3.5.16 유지

### STEP A — 소유권 메타데이터 생성 검증

대상:
`RunningAI.ConnectorOwnership.ps1`

`Set-RunningAiConnectorOwnerMetaFromLive`가 메타데이터를 기록하기 전에 다음을 검증하도록 수정한다.

1. Launcher의 실행 경로와 Repository Identity 확인.
2. Launcher 명령행의 Connector 마커 확인.
3. Listener가 Launcher와 동일 PID이거나 검증된 직접 자식인지 확인.
4. Listener CreationDate가 Launcher CreationDate 이후인지 확인.
5. Listener의 실제 LISTEN 포트 확인.
6. 검증 도중 PID 또는 포트 소유자가 변경되면 기록 거부.
7. 기존 메타데이터를 신뢰할 수 없는 상태에서는 덮어쓰지 않음.

검증 실패 시 `.owner.json` 신규 생성·교체를 하지 않는다.

추가로 메타데이터 조회 시 Name, RepoRoot, Port 및 Process CreationDate를 모두 검증한다.

### STEP B — 종료 직전 PID 재검증

`Stop-RunningAiConnectorManaged`에서 기존 Ownership 객체의 ManagedPids만으로 프로세스를 종료하지 않는다.

다음 단계마다 최신 상태를 검증한다.

- Graceful 신호 전달 전
- Graceful 종료 대기 이후
- Force Kill 직전
- 최종 종료 판정 전

검증 대상:

- PID
- CreationDate
- 실행 파일 및 명령행
- Launcher/Listener 관계 또는 검증된 메타데이터
- 실제 LISTEN 소유 PID

특히 Force Kill 직전에는 처음 확인한 Process CreationDate와 현재 값이 일치해야 한다.

PID만 같고 CreationDate가 다르면 중단한다.

소유권 검증에 실패하면 다른 프로세스를 종료하지 말고 `OWNERSHIP_CHANGED` 또는 동등한 실패 결과를 반환한다.

### STEP C — Ctrl+C 신호 범위 확인

현재 Send-CtrlC.ps1은 `GenerateConsoleCtrlEvent(0, 0)`을 사용한다.

이 호출이 특정 PID만 대상으로 하는 것이 아니라 연결된 콘솔에 신호를 전달한다는 점을 명확히 반영한다.

필수 요구사항:

1. PID별 호출과 PID별 신호 전달을 혼동하지 않는다.
2. 신호 전송 전 대상 프로세스와 콘솔의 관계를 확인한다.
3. 해당 콘솔에 비관리 프로세스가 함께 연결된 경우 신호를 보내지 않는다.
4. 안전성을 확인할 수 없으면 Graceful 신호를 생략하고 명확한 Fail-safe 정책을 적용한다.
5. 기존 Spring/Relay 종료 방식은 이번 수정으로 변경하지 않는다.
6. Windows PowerShell 5.1 호환성을 유지한다.

새로운 종료 방식을 도입한다면 격리된 프로세스 테스트로 먼저 검증한다.

### STEP D — 실패 시 PID 파일 및 메타데이터 보존

대상:

- stop-running-ai.ps1
- start-running-ai.ps1
- RunningAI.Watchdog.ps1

다음 정책을 적용한다.

**완전 종료 성공:**
- LISTEN 포트 해제 확인
- 관리 대상 프로세스 종료 확인
- PID 파일과 메타데이터 정리 허용

**부분 종료 또는 실패:**
- PID 파일과 메타데이터 보존
- 실패 사유 기록
- 추가 Start 금지
- 재시도 예산을 소비하지 않는 안전한 정지 상태로 분류할 수 있도록 정보 제공

실패한 사전 종료 이후 무조건 `start-running-ai.ps1`을 실행해서는 안 된다.

`RemainingPids.Count=0`만으로 성공을 판단하지 않는다. 실제 `PortFreed` 상태도 확인한다.

### STEP E — 상태 판정 개선

다음 상태를 명확히 구분한다.

- 실제 포트 미점유 → DOWN
- 소유 PID 여러 개 → UNKNOWN_OWNER
- 포트 조회 오류 → UNKNOWN_OWNER
- Launcher/Listener 관계 검증 성공 → MANAGED
- Sidecar 기반 고아 프로세스 검증 성공 → ORPHANED_MANAGED_PROCESS
- 외부 프로세스 확인 → FOREIGN_PROCESS
- 메타데이터 충돌 → UNKNOWN_OWNER

Connector Health만 정상이라고 해서 소유권까지 확인된 것으로 판단하지 않는다.

Watchdog가 HEALTHY 상태에서 불필요하게 프로세스를 종료하지 않는 기존 동작은 유지하되, 소유권 불일치는 진단 가능한 상태로 보고한다.

### STEP F — 보강 테스트

기존 17개 격리 테스트를 유지한다.

추가 테스트:

1. 무관한 Listener에 대한 Sidecar 기록 거부.
2. Launcher와 Listener의 부모 관계 불일치.
3. Listener CreationDate 불일치.
4. Sidecar RepoRoot 불일치.
5. Sidecar Name 불일치.
6. Listener PID가 검사 도중 재사용된 상황.
7. Graceful 이후 Force 직전 소유권 변경.
8. Force Kill 전에 생성 시각 불일치.
9. 두 개 이상의 LISTEN PID.
10. 포트 조회 실패.
11. 종료 실패 후 PID 파일 보존.
12. 포트 점유 상태에서 종료 성공 오판 방지.
13. Watchdog PreStop 실패 후 Start 차단.
14. 다른 프로세스가 콘솔을 공유하는 경우 신호 안전성.
15. 기존 Spring/Relay 종료 회귀 방지.

실제 운영 PID, Connector 8765, Spring 18080, Relay 17845는 테스트에 사용하지 않는다.

### STEP G — 전체 회귀

최소 실행:

- Test-RunningAI.ps1
- Test-Watchdog.ps1
- Test-ExternalRelay.ps1
- Test-ConnectorOwnership.ps1
- 관련 Spring/Relay 테스트

추가 검증:

- PowerShell 5.1 파싱
- git diff --check
- 비밀정보 노출 검사
- Canonical 서비스 상태 불변

전체 PASS 후 관련 파일만 Commit 및 origin/main-3 Fast-forward Push 허용.

권장 Commit:

`fix(windows): enforce connector ownership before termination`

origin/main Push 및 Canonical 병합은 금지한다.

### 금지사항

- 운영 Connector/Spring/Relay 종료 또는 재시작
- Watchdog 활성화 및 실제 복구
- Startup Scheduled Task 실행
- 운영 .env 및 PID 파일 수정
- Docker Compose 실행
- PostgreSQL 변경
- Windows 재부팅
- Garmin 로그인·동기화
- 훈련 생성·게시
- Canonical main 변경

### 최종 보고

1. 확인된 결함별 수정 결과
2. 소유권 판정 절차
3. Sidecar 기록 안전성
4. Graceful/Force Kill 안전성
5. PID 파일 보존 정책
6. Watchdog PreStop 실패 처리
7. 추가 테스트 결과
8. 전체 회귀 결과
9. Commit SHA 및 원격 브랜치 상태
10. 운영환경 불변 확인
11. 6I-1.7B-2 진행 가능 여부

운영 배포는 별도 승인 전까지 진행하지 않는다.
