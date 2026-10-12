# WO-RUNNINGAI-PHASE-6I-1.6C-3
## Spring Port Propagation & Operator API Client Compatibility

### 1. 목표

Phase 6I-1.6C-2의 후속 보완 작업.

Spring 포트 명시적 재정의가 Java 프로세스에 정확히 전달되도록 수정하고, Windows 운영 API 클라이언트가 현재 설정된 Spring 포트를 자동으로 사용하게 한다.

기존 훈련 승인·게시 안전장치 및 운영환경은 변경하지 않는다.

### 2. 작업 환경

- Worktree: `C:\Users\simjy\orca\workspaces\running-ai-github\main-3`
- Canonical: `C:\running-ai-github` — 변경 금지
- Legacy: `C:\running-ai` — 접근 및 변경 금지
- Base commit: `38ae9983774912cb7c1abb46e4bcf1aad0fd94b2`

### 3. STEP A — Spring 실제 포트 전달 결함 수정

대상: `scripts/windows/start-running-ai.ps1`

현재 코드:

```powershell
if ($SpringPort -ne 8080) {
    $env:SERVER_PORT = "$SpringPort"
}
```

문제: 명시적으로 `-SpringPort 8080`을 지정했는데 기존 `SERVER_PORT=18080`이 존재하면 Java 실행 포트와 Health Check 포트가 불일치한다.

수정 요구사항:

1. 최종 결정된 SpringPort를 Java 실행 환경변수에 항상 전달한다.
2. 명시적 파라미터 우선순위 유지.
3. `.env`와 기존 프로세스 환경변수 우선순위 유지.
4. 이미 정상 동작 중인 서버를 불필요하게 재시작하지 않는다.
5. SpringPort가 유효하지 않으면 서버 시작 전 실패한다.
6. 최소 변경으로 수정한다.

회귀 테스트:

- Explicit 8080 + Process 18080 → Java 8080
- Explicit 8080 + .env 18080 → Java 8080
- Explicit 18080 + Process 8080 → Java 18080
- .env 18080 only → Java 18080
- No configuration → Java 8080
- Health Check 포트와 Java 실행 포트 동일

실제 Java 프로세스는 실행하지 말고 격리된 테스트 또는 프로세스 실행 Mock으로 검증한다.

### 4. STEP B — 운영 API 클라이언트 포트 설정 통합

대상:

- `scripts/windows/invoke-running-ai-api.ps1`
- `scripts/windows/publish-approved-draft-controlled.ps1`
- `scripts/windows/running-ai-coach.ps1`

현재 세 스크립트는 기본 BaseUrl이 `http://127.0.0.1:8080`으로 고정되어 있다.

요구사항:

1. 명시적 `-BaseUrl`이 있으면 기존 값을 그대로 사용한다.
2. 없으면 프로세스 SERVER_PORT → canonical .env SERVER_PORT → 8080 순으로 포트를 결정한다.
3. 기본 BaseUrl은 `http://127.0.0.1:<resolved-port>`로 구성한다.
4. 공통 Helper를 재사용한다.
5. 운영 API 클라이언트가 포트 확인을 위해 불필요하게 `.env`의 다른 인증키를 프로세스 환경변수로 로딩하지 않도록 한다.
6. `.env`에서 SERVER_PORT만 읽는 경우 기존 파싱 규칙과의 일관성을 유지한다.
7. 비밀정보를 출력하거나 로그에 포함하지 않는다.
8. 기존 명시적 BaseUrl 기능을 제한하거나 확장하지 않는다.
9. Public Funnel 주소를 기본 BaseUrl로 사용하지 않는다.

추가로 `RunningAI.CoachOperator.ps1`의 StartIfNeeded 및 RuntimeRestarter가 변경된 기본 포트와 일치하는지 검토한다.

이 파일은 필요성이 입증되지 않으면 수정하지 않는다.

### 5. STEP C — 승인 및 게시 안전성 유지

다음 동작은 변경하지 않는다.

- Draft 생성·수정·승인 분리
- 사용자 APPROVE 확인
- 최종 YES 확인
- NonInteractive에서는 승인·게시 금지
- Publish Preview 재검증
- 게시 후 확인
- Safe Mode 복구
- 기본 자동 게시 비활성화

포트 설정 변경으로 인해 자동으로 게시되거나 외부 API가 호출되는 부작용이 없어야 한다.

### 6. STEP D — 테스트

기존 테스트:

- Test-RunningAI.ps1
- Test-Watchdog.ps1
- Test-ExternalRelay.ps1
- Test-CoachOperator.ps1

신규 테스트:

1. 기본값 8080
2. SERVER_PORT 18080
3. 명시적 BaseUrl 우선
4. 잘못된 포트 거부
5. API 클라이언트 3개 모두 동일 포트 해석
6. Spring 프로세스 환경변수와 Health Check 일치
7. NonInteractive 게시 차단 유지
8. 기존 승인/게시 게이트 유지
9. 비밀정보 미노출

실제 RunningAI 서비스 및 외부 게시 API에 요청하지 않는다. 필요한 경우 격리된 Fake Server만 사용한다.

### 7. STEP E — 최종 검토 및 Commit

전체 테스트 PASS 후 의도한 파일만 Stage한다.

Commit message:

`fix(windows): propagate Spring port to operator clients`

Commit 후 `origin/main-3`에 일반 Fast-forward Push를 허용한다.

Force Push 금지.

Canonical main Merge 및 Push는 별도 승인 전까지 금지한다.

### 8. 금지사항

- 운영 .env 변경
- 실제 RunningAI 실행·재시작
- Docker Compose 실행
- PostgreSQL 변경
- Watchdog 활성화
- 재부팅
- Garmin 로그인 및 동기화
- 훈련 자동 게시
- Canonical main 변경
- Legacy 변경
- 운영 인증정보 출력

### 9. PASS/FAIL

PASS:
- Java 실행 포트/Health Check 일치
- 세 운영 클라이언트의 기본 주소 일치
- 명시적 파라미터 우선순위 유지
- 기존 승인·게시 안전성 유지
- 전체 회귀 테스트 PASS
- main-3 Commit/Push 완료

FAIL:
- 포트 설정 불일치
- 운영 서비스 영향
- 비밀정보 노출
- 승인 없는 게시
- 회귀 테스트 실패

### 10. 최종 보고

1. 변경 파일 및 핵심 Diff
2. SpringPort → Java 프로세스 전달 검증
3. 3개 운영 API 클라이언트 BaseUrl 검증
4. 테스트 PASS/FAIL
5. 승인·게시 안전성 검증
6. Commit SHA
7. main-3/origin/main-3 일치
8. Canonical main과의 차이
9. 6I-1.6D 운영 배포 준비 여부

완료 후 결과를 보고하고 실제 운영 배포는 진행하지 않는다.
