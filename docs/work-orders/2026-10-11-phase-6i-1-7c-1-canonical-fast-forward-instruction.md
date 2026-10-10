# WO-RUNNINGAI-PHASE-6I-1.7C-1 — Controlled Canonical Fast-Forward & Non-Disruptive Validation (작업 지시서 원문)

## Controlled Canonical Fast-Forward & Non-Disruptive Validation

### 1. 목표

Phase 6I-1.7C-0에서 조건부 GO 판정을 받은 RunningAI Windows 운영 안정화 코드를 Canonical main에 반영한다.

이번 단계에서는 Git 작업 트리의 코드만 갱신한다.

**기존 실행 중인 운영 서비스는 유지하며, 신규 코드를 실제로 실행하거나 Watchdog를 활성화하지 않는다.**

### 2. 기준 상태

Repository: `simjjy89/running-ai`

Canonical:
`C:\running-ai-github`

Development:
`C:\Users\simjy\orca\workspaces\running-ai-github\main-3`

Legacy:
`C:\running-ai` — 접근 및 변경 금지

Expected SHA:

- main / origin/main: `f6a213588c5b7855b0dc7bf99948896291240ed7`
- main-3 / origin/main-3: `36e1e96cc92a7c6f1d808b243bee19bc9a316abf`

Target Canonical HEAD:
`36e1e96cc92a7c6f1d808b243bee19bc9a316abf`

운영 기준:

- Spring: 127.0.0.1:18080
- Connector: 127.0.0.1:8765
- Relay: 127.0.0.1:17845
- PostgreSQL: 기존 컨테이너 및 Volume 유지
- Watchdog: Disabled

### STEP A — 운영 사전 점검

반드시 읽기 전용으로 다음을 확인한다.

1. Canonical HEAD 및 현재 브랜치.
2. origin/main, main-3, origin/main-3 SHA.
3. Canonical 작업 트리 상태.
4. Development Worktree의 기존 Untracked 문서 목록.
5. 포트 18080/8765/17845 LISTEN 및 소유 PID.
6. Local Health 응답.
7. PostgreSQL Container ID, Volume, Health.
8. Watchdog Disabled.
9. RunningAI-Startup 작업의 설정 및 실행 여부.
10. SERVER_PORT=18080, SERVER_ADDRESS=127.0.0.1.
11. 현재 운영 프로세스 및 중복 실행 여부.

민감한 .env 값과 API 토큰을 출력하지 않는다.

기준 상태가 다르면 STOP한다.

### STEP B — 변경 영향 및 Rollback 설계

**Canonical을 변경하기 전에 반드시 완료한다.**

다음을 준비한다.

1. 변경 파일 전체 목록.
2. 기존 운영 코드 대비 변경된 실행 스크립트.
3. Git Hook 및 자동 배포 트리거 영향.
4. RunningAI-Startup/Watchdog의 스크립트 참조 경로.
5. 실행 중인 프로세스와 디스크 스크립트 버전의 차이.
6. Runtime State v1을 변경 없이 보존할 수 있는지.
7. 기존 PID 파일과 Sidecar 처리 방식.
8. Git 롤백 기준점과 복구 절차.
9. 코드 롤백이 실제 실행 프로세스를 자동 롤백하지 않는다는 점.

Rollback 기준:

- Old SHA: `f6a213588c5b7855b0dc7bf99948896291240ed7`
- New SHA: `36e1e96cc92a7c6f1d808b243bee19bc9a316abf`

코드만 반영한 뒤 문제가 발견되면 즉시 작업을 중단하고 상태를 보고한다.

이번 Phase에서 자동 Git Reset 또는 운영 Rollback은 수행하지 않는다.

별도 승인 후 다음을 검토하도록 문서화한다.

- Git 복귀 필요 여부
- 실행 중인 서비스 버전
- 변경 이후 생성된 Runtime State
- 롤백 시 서비스 중단 필요 여부
- PostgreSQL 데이터 및 Volume 보존

`git reset --hard`, `git clean`, Force Push를 자동 롤백 수단으로 사용하지 않는다.

Git Hook이나 자동화가 서비스 재시작을 유발할 가능성이 확인되면 STEP C 전에 STOP한다.

### STEP C — Git Fast-forward 사전 검증

Canonical 디렉터리에서 실행한다.

```powershell
Set-Location "C:\running-ai-github"

git fetch origin

git status --porcelain=v1 --untracked-files=all
git branch --show-current

git rev-parse HEAD
git rev-parse origin/main
git rev-parse origin/main-3

git merge-base --is-ancestor HEAD origin/main-3

if ($LASTEXITCODE -ne 0) {
    throw "STOP: Fast-forward is not possible."
}

git diff --check HEAD origin/main-3

if ($LASTEXITCODE -ne 0) {
    throw "STOP: diff check failed."
}

git diff --name-status HEAD origin/main-3
```

다음 조건을 추가 검증한다.

- Canonical은 main 브랜치.
- 기존 HEAD가 정확히 f6a2135.
- origin/main이 예상 SHA와 일치.
- origin/main-3이 정확히 36e1e96.
- 변경 경로가 사전 영향 분석과 일치.
- 추적 파일 변경 없음.
- Untracked 파일 덮어쓰기 위험 없음.
- Merge Hook 등의 자동 실행 위험 없음.

불일치 시 STOP한다.

### STEP D — Canonical Fast-forward

STEP A~C가 전부 PASS일 때만 수행한다.

```powershell
Set-Location "C:\running-ai-github"

git merge --ff-only origin/main-3

if ($LASTEXITCODE -ne 0) {
    throw "STOP: Canonical fast-forward failed."
}
```

조건:

- 1회 실행
- Fast-forward only
- Merge Commit 생성 금지
- Rebase 금지
- Force 금지
- 새 코드 Commit 생성 금지
- origin/main Push 금지

기존 Development Worktree 및 Untracked 문서는 수정하지 않는다.

### STEP E — Canonical Git 검증

다음을 확인한다.

- main HEAD = 36e1e96
- origin/main = f6a2135 (변경 없음)
- main-3/origin/main-3 = 36e1e96
- Canonical Tracked Working Tree Clean
- 기존 Untracked 파일 보존
- 예상하지 못한 파일 변경 없음
- Git Hook 후속 작업이나 자동 배포가 발생하지 않음

main과 origin/main이 달라진 것은 이번 Phase에서는 정상이다.

원격 main Push는 별도 Phase에서 수행한다.

### STEP F — 운영 불변 검증

Git Fast-forward 이후 읽기 전용으로 확인한다.

1. Spring 기존 PID 불변.
2. Spring 127.0.0.1:18080만 LISTEN.
3. Spring /actuator/health HTTP 200 UP.
4. Connector 기존 Launcher/Listener PID 불변.
5. Connector /health UP.
6. Relay 기존 PID 불변.
7. Relay Local /health HTTP 200.
8. Public HTTPS /health 정상.
9. 인증된 Local/Public /today-workout HTTP 200 및 응답 일치.
10. 무인증 Public 요청 HTTP 401.
11. PostgreSQL Container ID/Volume/Health 불변.
12. .env 및 PID/Sidecar 파일 불변.
13. 기존 watchdog-state.json v1 불변.
14. Watchdog Disabled 유지.
15. RunningAI-Startup 및 서비스 재실행 없음.

`NO_PLAN`은 API 정상 응답이며 실패로 분류하지 않는다.

현재 날짜의 훈련 상태가 달라져도 서비스 무결성 기준으로 판단한다.

실행 중인 서비스는 기존 기동 시점의 코드·설정을 계속 사용할 수 있다. Git Fast-forward만으로 신규 Watchdog 동작이 검증됐다고 보고하지 않는다.

### STEP G — 금지사항

이번 단계에서는 다음을 수행하지 않는다.

- start-running-ai.ps1 실행
- stop-running-ai.ps1 실행
- watch-running-ai.ps1 실행
- RunningAI-Startup 수동 실행
- Watchdog 활성화
- Connector/Spring/Relay 재시작
- 운영 포트를 사용하는 테스트 실행
- Windows 재부팅
- Docker Compose up/down
- PostgreSQL 변경
- 운영 .env 수정
- 운영 Watchdog State 마이그레이션
- 운영 PID/Sidecar 파일 변경
- 실제 Garmin 로그인·동기화
- 훈련 생성·게시
- Git reset/clean/stash
- origin/main Push
- Legacy 경로 변경

테스트를 다시 실행할 필요가 있다면 개발 Worktree의 격리 환경에서만 수행한다.

### STEP H — 최종 보고

다음 항목을 보고한다.

1. Preflight PASS/FAIL.
2. Git Hook/자동 배포 위험 검사.
3. 변경 파일 및 영향 분석.
4. Rollback 계획.
5. Fast-forward 실행 여부 및 결과.
6. main / origin/main SHA.
7. main-3 / origin/main-3 SHA.
8. Canonical Working Tree 상태.
9. Spring/Connector/Relay PID 불변 여부.
10. Local/Public API 상태.
11. PostgreSQL 컨테이너/Volume 보존.
12. Watchdog Disabled 유지.
13. .env/PID/Sidecar/State 보존.
14. 남은 Ctrl+C 및 Forced Shutdown 위험.
15. Phase 6I-1.7C-2 진입 GO/NO-GO.

완료 후 STOP하고 보고한다.

운영 Watchdog 검증과 재활성화는 별도 Phase에서 승인받는다.
