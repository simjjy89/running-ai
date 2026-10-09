# WO-RUNNINGAI-PHASE-6I-1.7B-1
## Garmin Connector Process Lifecycle Hardening

### 1. 목표

Windows Python 가상환경 런처와 실제 인터프리터가 서로 다른 PID로 실행되는 구조를 안전하게 관리한다.

해결 대상:

- PID 파일과 실제 LISTEN PID 불일치
- 자식 Python 프로세스가 고아로 남을 위험
- 실제 RunningAI 프로세스를 FOREIGN_PROCESS로 오인할 위험
- PID 재사용 및 잘못된 프로세스 종료 위험

**현재 정상 운영 중인 Connector/Spring/Relay는 절대 중단하지 않는다.**

### 2. 환경

Canonical:
`C:\running-ai-github`

Development Worktree:
`C:\Users\simjy\orca\workspaces\running-ai-github\main-3`

Expected Base:
`f6a213588c5b7855b0dc7bf99948896291240ed7`

Java 21 / Spring Boot 3.5.16 유지.

Legacy:
`C:\running-ai` — 접근 및 변경 금지.

### 3. STEP A — 현재 구현 분석

다음 파일을 검토한다.

- `scripts/windows/RunningAI.Common.ps1`
- `scripts/windows/start-running-ai.ps1`
- `scripts/windows/stop-running-ai.ps1`
- `scripts/windows/Send-CtrlC.ps1`
- `scripts/windows/RunningAI.Watchdog.ps1`
- `scripts/windows/watch-running-ai.ps1`
- 관련 Windows 테스트

특히 PID 파일 생성, 프로세스 신원 검증, 종료 신호 전달, 강제 종료 및 Watchdog 상태 판정 경로를 추적한다.

기존 운영 PID 파일이나 프로세스는 변경하지 않는다.

### 4. STEP B — Connector 프로세스 소유권 모델

다음 값을 구별하여 관리할 수 있도록 설계한다.

- Launcher PID
- 실제 TCP LISTEN PID
- 각 프로세스의 CreationDate
- 실행 파일 경로
- ParentProcessId
- Connector 명령행 마커
- Canonical Repository identity
- 연결 포트 8765

소유권 판정은 다음 원칙을 따른다.

1. LISTEN PID만으로 소유권을 인정하지 않는다.
2. Launcher와 실제 Listener의 관계를 검증한다.
3. CreationDate로 PID 재사용 위험을 확인한다.
4. 프로세스 실행 경로와 명령행을 추가 검증한다.
5. 서로 다른 Repository/Worktree의 프로세스를 혼동하지 않는다.
6. 소유권이 불분명하면 UNKNOWN_OWNER로 분류한다.
7. UNKNOWN_OWNER는 절대 자동 종료하지 않는다.

기존 단일 PID 파일과의 하위 호환성을 유지한다.

새로운 메타데이터 저장 방식이 필요하면 파일 형식, 저장 시점, 원자적 쓰기, 복구 방식 및 구버전 처리 정책을 문서화한다.

현재 운영 PID 파일을 자동 변환하지 않는다.

### 5. STEP C — 안전한 Connector 종료

Connector 전용 종료 로직을 우선 검토한다.

공통 Stop-TrackedProcess를 무조건 변경하여 Spring 및 Relay 종료 동작에 회귀를 일으키지 않는다.

요구사항:

1. 실제 소유권을 확인한 프로세스만 종료 대상으로 삼는다.
2. 가능한 경우 Graceful shutdown을 우선한다.
3. 종료 후 실제 LISTEN 포트가 해제됐는지 확인한다.
4. 부모 PID 종료만으로 성공을 판정하지 않는다.
5. 타임아웃 발생 시 자식 프로세스가 남았는지 확인한다.
6. 강제 종료는 검증된 Connector 프로세스 집합에만 적용한다.
7. PID 재사용 또는 생성 시각 불일치가 있으면 종료하지 않는다.
8. 소유권 불명확 시 Fail-safe로 중단한다.
9. 종료 대상이 아닌 conhost.exe 및 다른 애플리케이션에 영향을 주지 않는다.
10. 오류 시 고아 프로세스와 포트 점유 상태를 명확히 보고한다.

`taskkill /T /F` 등 무차별적인 프로세스 트리 종료는 금지한다.

현재 Send-CtrlC.ps1의 콘솔 전체 신호 방식이 다른 프로세스에 영향을 줄 수 있는지도 평가한다.

### 6. STEP D — Watchdog 상태 판정 호환성

다음 상태를 구분할 수 있도록 필요한 최소 인터페이스를 검토한다.

- HEALTHY_MANAGED
- HEALTHY_UNMANAGED
- UNHEALTHY_MANAGED
- ORPHANED_MANAGED_PROCESS
- FOREIGN_PROCESS
- UNKNOWN_OWNER
- DOWN

기존 UP/DOWN/DEGRADED 상태 모델과의 호환성을 유지하고, 필요한 상세 사유를 추가하는 방식을 우선 고려한다.

소유권이 불분명한 포트 점유 프로세스를 종료하거나 대체해서는 안 된다.

이번 단계에서 Watchdog의 재시작 정책 전체를 변경하지 않는다.

### 7. STEP E — 격리 테스트

운영 프로세스를 사용하는 테스트는 금지한다.

테스트 대상:

1. 정상 Launcher → Listener 부모·자식 관계.
2. Launcher와 Listener PID 불일치.
3. 정상적인 단일 프로세스 Connector.
4. PID 파일의 PID 재사용.
5. ParentProcessId 재사용 및 생성 시각 불일치.
6. 외부 Python 서버가 같은 포트를 점유한 상황.
7. Connector Launcher만 종료된 상황.
8. 실제 Listener만 종료된 상황.
9. 부모 종료 후 자식이 남는 상황.
10. Graceful shutdown 성공.
11. Graceful shutdown 실패 후 안전한 정리.
12. 강제 종료 시 검증된 자식만 종료.
13. 소유권 불명확 시 종료 거부.
14. 기존 Spring/Relay 종료 회귀 없음.
15. Developer Worktree와 Canonical 프로세스 혼동 방지.

가능하면 테스트 전용 부모·자식 프로세스와 임시 Loopback 포트를 이용한 통합 테스트를 추가한다.

실제 Python venv 구조를 재현하는 테스트는 운영 Connector와 완전히 격리된 환경에서만 수행한다.

외부 Garmin API 및 실제 Connector 8765를 사용하지 않는다.

### 8. STEP F — 회귀 테스트

기존 테스트:

- Test-RunningAI.ps1
- Test-Watchdog.ps1
- Test-ExternalRelay.ps1
- 기타 관련 Windows 테스트

추가 확인:

- PowerShell 5.1 호환성
- git diff --check
- 비밀정보 노출 없음
- PID 파일 오염 없음
- 운영 프로세스 영향 없음
- PostgreSQL 영향 없음

### 9. STEP G — 코드 리뷰 및 Git

먼저 설계 및 테스트 결과를 검토한다.

모든 필수 테스트가 PASS인 경우에만 관련 코드와 테스트를 Commit한다.

Commit message:

`fix(windows): track Garmin connector process ownership safely`

Commit 후 origin/main-3으로 일반 Fast-forward Push를 허용한다.

Canonical main Merge 및 Push는 금지한다.

기존 RunningAI 운영 프로세스는 변경하지 않는다.

### 10. 절대 금지사항

- 운영 Connector Kill/Stop/Restart
- Spring/Relay 재시작
- Watchdog 활성화 또는 실제 복구 실행
- RunningAI-Startup 실행
- Docker Compose up/down
- PostgreSQL 데이터 변경
- 운영 .env 변경
- Windows 재부팅
- Canonical PID 파일 변경
- 실제 8765 포트 사용 테스트
- Garmin 로그인/동기화
- 훈련 생성·승인·게시
- Origin main Push
- Legacy 경로 변경

### 11. 완료 기준

PASS:

- Launcher/Listener 소유권 모델 구현
- PID 재사용 방어
- Graceful/Forced 종료의 안전한 처리
- 외부 프로세스 보호
- 격리 통합 테스트 통과
- 전체 회귀 테스트 통과
- 운영환경 변경 없음

FAIL:

- 실제 운영 프로세스 변경
- 소유권 불명확한 프로세스 종료 가능
- 자식 프로세스 잔존을 성공으로 오판
- 잘못된 Repository 프로세스 종료
- 테스트 실패
- 비밀정보 노출

### 12. 최종 보고

1. 변경 파일
2. 기존 문제의 원인
3. 새로운 소유권 모델
4. 프로세스 관계 검증 방식
5. 정상 종료/강제 종료 설계
6. 고아 프로세스 처리 정책
7. Watchdog 연동 영향
8. 격리 테스트 결과
9. 전체 회귀 테스트 결과
10. Commit SHA
11. main-3/origin/main-3 상태
12. 운영환경 불변 여부
13. Phase 6I-1.7B-2 권고 사항

운영 배포는 별도 승인 전까지 수행하지 않는다.
