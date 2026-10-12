# WO-RUNNINGAI-PHASE-6I-1.1-E2E-P2 — Canonical Watchdog DryRun & Production Invariance (작업 지시서 원문)

## 목표

Canonical Fast-forward 이후 실제 운영 Watchdog가 정상 동작하는지 읽기 전용으로 확인한다.

이번 Phase에서는 어떤 서비스도 중지하거나 재시작하지 않는다.

## 기준

* Canonical: C:\running-ai-github
* Canonical main HEAD: 54aa849b0204cea2bca1649cc69b58f5baf20a1c
* Development main-3/origin/main-3: 73cb112ba490ac84b97d0310714c85e76821167f
* origin/main: f6a213588c5b7855b0dc7bf99948896291240ed7
* REBOOT_PRECHECK=GO 유지

별도 Worktree main-3-2는 접근·수정하지 않는다.

레거시 C:\running-ai 및 워치페이스 별도 저장소도 접근·수정하지 않는다.

### STEP A — Preflight

읽기 전용으로 확인한다.

1. Canonical HEAD 및 Working Tree.
2. Docker/PostgreSQL HEALTHY.
3. Connector/Spring/Relay Health 200.
4. PID 및 CreationDate.
5. Connector Ownership=LAUNCHER_CHILD.
6. Watchdog Enabled.
7. 최근 Scheduled Task LastTaskResult=0.
8. State v2 및 Restart Budget/Lockout.
9. Tailscale Funnel 상태.
10. 기존 재부팅 백업과 기준선 보존 여부.

운영 서비스가 비정상이면 STOP.

### STEP B — DryRun 안전성 확인

Canonical 코드에서 다음 경로가 읽기 전용임을 다시 확인한다.

* Read-WatchdogState -ReadOnly
* Get-RuntimeObservation -ReadOnly
* Get-TrackedProcessId -ReadOnly
* DryRun에서 Recovery Executor 실행 불가
* DryRun에서 State/Status/로그 쓰기 불가

status-running-ai.ps1은 읽기 전용 부작용 경로가 완전히 감사되지 않았다면 실행하지 않는다.

### STEP C — Canonical Watchdog DryRun 1회

```powershell
Set-Location "C:\running-ai-github"
powershell.exe -NoProfile -ExecutionPolicy Bypass `
  -File ".\scripts\windows\watch-running-ai.ps1" `
  -DryRun `
  -ConnectorPort 8765 `
  -SpringPort 18080
$exitCode = $LASTEXITCODE
Write-Output "WATCHDOG_DRYRUN_EXIT=$exitCode"
```

기대 결과:

* Exit=0
* Docker UP
* PostgreSQL UP
* Connector UP
* Spring UP
* External Relay UP
* Planned Actions: none
* Overall: UP

주의: Watchdog Mutex 경합 때문에 "Another watchdog run is in progress; skipping"이 출력되면 Exit=0이어도 PASS가 아니다. 이 경우 INCONCLUSIVE로 기록하며 임의 재시도하지 않는다.

### STEP D — 운영 무변경 검증

DryRun 전후 다음을 비교한다.

* 모든 운영 PID/CreationDate
* PostgreSQL Container/Volume
* DB Activity/HighWater
* Watchdog State v2
* Restart Budget/Lockout
* .env 및 토큰 파일
* Canonical Git HEAD/Working Tree

Watchdog의 정상적인 5분 주기 자동 실행에 따른 State/로그 갱신은 별도로 구분한다.

### STEP E — 외부 데이터 경로 상태 확인

기존 Phase E2E-P1 결과를 재사용한다.

* Intervals.icu API 인증: HTTP 200
* 2026-10-12 WORKOUT: 0건
* Local Relay: NO_PLAN
* Public Relay: NO_PLAN
* Local/Public 응답 일치

이번 Phase에서 별도의 실제 훈련을 생성하거나 API 키를 변경하지 않는다.

FR265 실물 확인과 LTE/5G 외부망 확인은 사용자 직접 검증으로 남긴다.

### STEP F — 최종 보고

1. Preflight 결과
2. Canonical HEAD
3. DryRun Exit Code
4. 구성요소별 상태
5. Planned Actions 및 Blocked
6. 운영 PID 불변
7. PostgreSQL/DB 불변
8. Watchdog State 불변
9. 기존 E2E-P1 결과 유지 여부
10. 재부팅 준비 상태
11. 다음 단계 GO/NO-GO

결과 문서만 main-3에 기록하고, 필요 시 문서 전용 커밋을 origin/main-3에 Push한다.

## 금지사항

* 서비스 Stop/Start/Restart
* Relay Kill 또는 장애 주입
* Watchdog 일반 실행
* Docker Compose 변경
* Windows 재부팅/로그오프
* Intervals.icu 키 변경
* 훈련 생성·게시
* Canonical Merge/Reset
* origin/main Push
* 미추적 문서 삭제
* 다른 Worktree 변경

검증 후 STOP한다.

전체 External Access E2E READY는 아직 선언하지 않는다.
