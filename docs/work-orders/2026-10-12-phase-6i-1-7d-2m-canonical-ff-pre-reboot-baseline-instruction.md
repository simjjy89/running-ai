# WO-RUNNINGAI-PHASE-6I-1.7D-2M — Canonical Fast-Forward & Pre-Reboot Baseline Freeze (작업 지시서 원문)

## 1. 목표

Phase 6I-1.7D-2/2R 검증 결과를 Canonical main에 반영하고, 재부팅 최종 검증을 위한 운영 기준선을 확정한다.

이번 작업에서 실제 재부팅이나 장애 주입은 수행하지 않는다.

## 2. Git 기준

* Canonical: C:\running-ai-github
* Development: C:\Users\simjy\orca\workspaces\running-ai-github\main-3
* Legacy: C:\running-ai — 변경 및 접근 금지

Expected SHA:

* main: 36e1e96cc92a7c6f1d808b243bee19bc9a316abf
* origin/main: f6a213588c5b7855b0dc7bf99948896291240ed7
* main-3 / origin/main-3: 43ed6af2049e1564fb35b76dbd8e22d99774ec19

### STEP A — Preflight

1. Canonical main 및 개발 Worktree HEAD 확인.
2. Canonical Tracked Working Tree Clean 확인.
3. 기존 Untracked 파일 보존.
4. Git Fast-forward 가능 여부 확인.
5. 변경 경로가 docs/work-orders/ 및 scripts/windows/tests/로만 제한되는지 확인.
6. Watchdog Enabled 및 최근 자동 실행 정상 확인.
7. Spring/Connector/Relay Health 및 PID·CreationDate 기록.
8. PostgreSQL Container ID/Volume/Health 기록.
9. watchdog-state.json v2, Restart Budget, Lockout 확인.
10. Local/Public 인증 API 정상 확인.

예상과 다른 변경이나 서비스 장애가 있으면 STOP.

### STEP B — Canonical Fast-forward

조건 충족 후 다음만 수행한다.

```powershell
Set-Location "C:\running-ai-github"
git fetch origin
git merge-base --is-ancestor HEAD origin/main-3
if ($LASTEXITCODE -ne 0) {
    throw "STOP: not fast-forwardable"
}
$unexpected = @(git diff --name-only HEAD origin/main-3 |
    Where-Object {
        $_ -notmatch '^(docs/work-orders/|scripts/windows/tests/)'
    })
if ($unexpected.Count -gt 0) {
    throw "STOP: unexpected production code changes"
}
git diff --check HEAD origin/main-3
if ($LASTEXITCODE -ne 0) {
    throw "STOP: diff check failed"
}
git merge --ff-only origin/main-3
if ($LASTEXITCODE -ne 0) {
    throw "STOP: fast-forward failed"
}
```

목표 main HEAD: 43ed6af2049e1564fb35b76dbd8e22d99774ec19

origin/main Push는 수행하지 않는다.

### STEP C — 운영 무변경 검증

Fast-forward 후 확인한다.

* Canonical HEAD가 목표 SHA와 일치.
* Git Working Tree Clean.
* Watchdog Enabled 유지.
* 자동 실행 LastTaskResult=0.
* 최근 Watchdog Action=NONE.
* 5개 구성요소 UP.
* Restart Budget/Lockout 비정상 증가 없음.
* Spring/Connector/Relay PID 및 CreationDate 불변.
* PostgreSQL 컨테이너 및 Volume 불변.
* 애플리케이션 activity/HighWater 기준선 유지.
* Local/Public API 정상.

주의: Watchdog가 5분마다 기록하는 status/state/log 갱신은 정상 운영 동작이다. 파일 수정 시각 변경만으로 실패 처리하지 않는다. 실제 Recovery Action 및 예산 증가 여부를 기준으로 판정한다.

### STEP D — 재부팅 검증 준비

다음 Phase를 위한 계획만 작성한다.

1. 서비스 및 데이터 백업 기준.
2. 재부팅 직전 건강 상태 기록.
3. RunningAI-Startup Logon 자동 기동 순서.
4. Docker Desktop 및 PostgreSQL 자동 복구 조건.
5. Garmin Connector Launcher/Listener 소유권 판정.
6. Spring 18080 및 Relay 17845 Health.
7. Tailscale Funnel 외부 HTTPS.
8. Watchdog 로그인 후 5분 지연 및 반복 실행.
9. PostgreSQL DB 영속성.
10. 재부팅 후 Public/LTE/5G 및 FR265 검증.
11. 자동 복구 실패 시 수동 대응 및 Rollback.
12. 비밀정보·환경변수 보존 확인.

실제 재부팅은 별도 승인받는다.

### STEP E — 최종 보고

* 사전 SHA
* 변경 파일 및 영향 범위
* Fast-forward 실행 결과
* 사후 Git SHA
* Watchdog 정상 실행 여부
* PID/DB/PostgreSQL 불변 여부
* 인증 Local/Public API 상태
* 재부팅 전 체크리스트
* 잔여 위험
* Phase 6I-1.7D-3 GO/NO-GO

완료 후 STOP.

### 금지사항

* 운영 서비스 재시작
* Watchdog 활성화 상태 변경·재등록
* 장애 주입
* PC 재부팅/로그아웃
* 운영 State/Sidecar 임의 수정
* Docker Compose down/up
* PostgreSQL 변경
* Garmin 동기화/훈련 게시
* Git Reset/Clean/Force Push
* origin/main Push
* Legacy 저장소 접근
