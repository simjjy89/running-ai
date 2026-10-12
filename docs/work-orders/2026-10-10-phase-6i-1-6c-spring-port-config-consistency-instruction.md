# WO-RUNNINGAI-PHASE-6I-1.6C
## Spring Port Configuration Consistency Fix

### 1. 목표

Windows TCP 제외 포트로 인해 Spring Boot 8080 바인딩이 실패하는 문제를 해결하기 위한 포트 설정 개선.

후보 포트는 18080이다.

모든 Windows 운영 스크립트가 동일한 Spring 포트를 사용하도록 수정한다.

이번 단계에서는 운영 서비스를 실행하거나 배포하지 않는다.

### 2. 개발 환경

- Canonical: `C:\running-ai-github`
- Worktree: `C:\Users\simjy\orca\workspaces\running-ai-github\main-3`
- Branch: `main-3`
- Java 21 / Spring Boot 3.5.16 / Gradle 8.14.5

운영 `.env`를 개발 Worktree로 복사하지 않는다.

### 3. STEP A — 포트 후보 확인

18080 포트에 대해 읽기 전용으로 다음을 확인한다.

1. IPv4/IPv6 TCP 제외 포트 범위에 포함되지 않는지
2. 기존 LISTEN 프로세스가 존재하는지
3. Portproxy 등 명시적 리다이렉션이 존재하는지
4. RunningAI 이외의 서비스가 해당 포트를 사용하는지

실제 Bind 테스트는 이번 작업에서 수행하지 않는다.

후보 포트의 사용 가능 여부는 최종 배포 전 별도 검증한다.

### 4. STEP B — 공통 포트 해석 개선

대상:

- `scripts/windows/RunningAI.Common.ps1`
- `scripts/windows/start-running-ai.ps1`
- `scripts/windows/watch-running-ai.ps1`
- `scripts/windows/status-running-ai.ps1`

규칙:

1. 명시적인 `-SpringPort` 파라미터가 있으면 우선 사용한다.
2. 그렇지 않으면 기존 프로세스의 `SERVER_PORT`를 사용한다.
3. 해당 값이 없으면 canonical `.env`의 `SERVER_PORT`를 사용한다.
4. 어느 곳에도 값이 없으면 기존 기본값 8080을 유지한다.
5. 잘못된 포트 값은 명확한 오류로 거부한다.
6. 실제 Spring 프로세스에 전달하는 포트와 Health Check 포트가 항상 일치해야 한다.
7. 기존 환경변수 우선순위를 변경하지 않는다.

주의: PowerShell param 바인딩은 일반 스크립트 본문에서 `.env`를 로딩하기 전에 수행될 수 있다. 따라서 포트 해석 시점을 반드시 검토한다.

기존 설정을 중복 구현하지 말고 공통 Helper 활용을 검토한다.

### 5. STEP C — 회귀 테스트

대상:

- `scripts/windows/tests/Test-RunningAI.ps1`
- `scripts/windows/tests/Test-Watchdog.ps1`
- 관련 Windows 테스트

검증 시나리오:

- 기본 설정 없으면 8080
- `.env`가 18080이면 18080
- Process 환경변수가 있으면 `.env`보다 우선
- 명시적 `-SpringPort` 우선
- 시작/상태 확인/Watchdog의 포트 일치
- Scheduled Task와 동일한 독립 프로세스 환경에서 설정 정상 로딩
- 잘못된 포트 입력 거부
- 운영 비밀정보 노출 없음
- 기존 Relay 및 Watchdog 회귀 없음

테스트에서는 가짜 `.env` 및 격리된 런타임만 사용한다.

### 6. STEP D — 배포 계획 작성

코드 검증이 끝나면 다음 운영 배포 절차를 제안한다.

1. 18080 사용 가능 여부를 IPv4/IPv6 Bind로 확인
2. 운영 `SERVER_PORT` 충돌 여부 확인
3. Canonical `.env`에 SERVER_PORT=18080 적용
4. Canonical main Fast-forward 반영
5. Watchdog Disabled 상태에서 수동 복구
6. Spring 18080 Health Check
7. Connector 및 Relay 확인
8. 인증된 Local/Public `/today-workout` HTTP 200 확인
9. PostgreSQL 기존 컨테이너와 Volume 보존 확인

이 단계에서 배포 계획은 작성만 한다. 실제 적용은 별도 승인 후 진행한다.

### 7. 금지사항

- 운영 `.env` 변경
- Docker Compose up/down
- PostgreSQL 컨테이너 또는 Volume 변경
- RunningAI 실제 시작
- Watchdog 활성화
- Windows 재부팅
- 포트 제외 범위 삭제 및 Windows 네트워크 설정 변경
- main-3에서 실제 Watchdog 실행
- Garmin 로그인/동기화/훈련 게시
- Legacy `C:\running-ai` 수정
- 무단 Commit, Merge, Push

### 8. PASS/FAIL

PASS:
- 모든 포트 설정 경로 일치
- 격리 회귀 테스트 통과
- 기존 기본값 유지
- 운영 서비스 영향 없음
- 운영 배포 절차 작성 완료

FAIL:
- 스크립트 간 포트 불일치
- 기존 회귀 테스트 실패
- 운영 서비스 변경
- 보안정보 노출

### 9. 최종 보고

1. 변경 파일
2. 포트 해석 우선순위
3. 18080 읽기 전용 사전 점검 결과
4. 테스트별 PASS/FAIL
5. 기존 동작과 변경 동작 비교
6. 운영 배포 위험
7. Commit 준비 상태
8. Phase 6I-1.6D 배포 권고

완료 후 결과를 보고하고 운영 배포는 승인 전까지 수행하지 않는다.
