# WO-RUNNINGAI-PHASE-6I-1.7D-1 — Supervised Watchdog Activation & Healthy-State Validation (작업 지시서 원문)

## Supervised Watchdog Activation & Healthy-State Validation

### 1. 목표

RunningAI Watchdog를 실제 Windows 운영 환경에 통제된 방식으로 활성화한다.

검증 대상:

- 신규 Watchdog의 일반 실행 경로
- 기존 Healthy 서비스에 대한 무조치 동작
- Scheduled Task의 실제 실행 환경
- State v2 및 장기 Lockout 상태 유지
- 5분 주기 실행 및 중복 실행 방지
- 운영 서비스/PID/PostgreSQL 무변경

**고의적인 서비스 장애, 강제 종료, 재부팅은 이번 범위에서 제외한다.**

### 2. 기준 환경

Canonical:
`C:\running-ai-github`

Expected Canonical HEAD:
`36e1e96cc92a7c6f1d808b243bee19bc9a316abf`

Expected origin/main:
`f6a213588c5b7855b0dc7bf99948896291240ed7`

Expected origin/main-3:
`a14481396dc12ba08e66c399d1d4ea5c2085ef3a`

서비스:
- Spring: 127.0.0.1:18080
- Connector: 127.0.0.1:8765
- Relay: 127.0.0.1:17845
- PostgreSQL: 기존 컨테이너/볼륨

State:
- watchdog-state.json v2
- .bak 정상
- 모든 장기 Lockout 해제 상태

현재 Watchdog Scheduled Task:
Disabled

### STEP A — Preflight

다음을 확인한다.

1. Canonical Working Tree Clean.
2. 기준 Git SHA 일치.
3. 서비스 5개 구성요소 전부 UP.
4. Connector Ownership = LAUNCHER_CHILD.
5. 기존 PID/CreationDate 확인.
6. PostgreSQL Container ID/Volume/Health 확인.
7. State v2 및 .bak 유효.
8. 재시작 예산과 장기 Lockout 상태 확인.
9. Watchdog Disabled.
10. RunningAI-Startup 실행 중이 아님.
11. 실행 중인 다른 Watchdog 프로세스 없음.
12. 운영 .env 정상.
13. 외부 Public HTTPS 및 인증 API 정상.

예상과 다른 상태가 있으면 즉시 STOP한다.

### STEP B — Scheduled Task 정의 확인

`RunningAI-Watchdog`의 실제 등록 정보를 읽기 전용으로 확인한다.

필수 사항:

- 작업 이름: RunningAI-Watchdog
- 실행 파일: powershell.exe
- 실행 대상: Canonical의 watch-running-ai.ps1
- 실행 방식: -File
- Logon Delay: 5분
- 반복 주기: 5분
- MultipleInstances: IgnoreNew
- 사용자: 기존 RunningAI 운영 사용자
- WorkingDirectory: Canonical
- Watchdog 상태: Disabled

특히 실제 Scheduled Task의 명령에는 SpringPort가 생략되어 있으므로, Canonical .env의 SERVER_PORT=18080을 정확하게 읽는지 확인한다.

**작업을 재등록하거나 기존 정의를 임의로 변경하지 않는다.**

### STEP C — 원본 상태 백업

다음 운영 파일의 현재 상태를 기록한다.

- watchdog-state.json
- watchdog-state.json.bak
- watchdog-status.json
- watchdog.log
- Connector/Spring/Relay PID 파일
- 운영 .env

State v2는 외부 보안 백업 디렉터리에 별도 복사하고 SHA256으로 검증한다.

기존 v1 백업도 보존한다.

로그 파일의 크기와 보존기간을 확인한다. 스케줄러 기본 로그 로테이션으로 기존 장애 증거가 삭제될 위험이 있다면 활성화하지 않는다.

### STEP D — Scheduled Task와 동일 조건의 DryRun

Canonical에서 다음 명령을 1회 실행한다.

```powershell
Set-Location "C:\running-ai-github"

powershell.exe -NoProfile -ExecutionPolicy Bypass `
  -File ".\scripts\windows\watch-running-ai.ps1" `
  -DryRun
```

이번에는 일부러 SpringPort 인자를 생략한다.

확인 사항:

- 자동으로 SpringPort=18080 적용
- 구성요소 전체 UP
- Connector Ownership 정상
- Planned Actions=0
- Blocked=0
- Exit Code 0

예상과 다르면 STOP.

### STEP E — Watchdog 일반 모드 수동 실행 1회

이 단계부터는 Watchdog의 실제 운영 상태 파일과 로그가 기록될 수 있다.

실행 직전 다시 Health를 확인한다.

모든 구성요소가 UP이고 계획된 복구가 0건일 때만 아래 명령을 1회 실행한다.

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass `
  -File ".\scripts\windows\watch-running-ai.ps1" `
  -LogMaxSizeMB 1024 `
  -LogRetentionDays 3650
```

주의: `-DryRun`과 `-NoRecovery`를 사용하지 않는 일반 모드다.

따라서 관찰 직후 상태가 변하면 실제 복구 Action이 발생할 수 있다. 이 작업은 운영자가 즉시 상태를 확인할 수 있는 감독 환경에서만 수행한다.

기대 결과:

- Exit Code 0
- Overall UP
- LastAction NONE
- Recovery Steps 0
- PID 변경 없음
- PostgreSQL 변경 없음
- State v2 정상 유지
- Restart Budget 증가 없음
- 장기 실패 횟수 증가 없음
- Lockout 없음

예상 밖 Recovery Action이 실행되면 추가 작업을 즉시 중단하고 결과를 조사한다.

### STEP F — Scheduled Task 활성화

STEP A~E가 모두 PASS이고 운영자가 즉시 상태를 확인할 수 있을 때만 진행한다.

```powershell
Enable-ScheduledTask -TaskName "RunningAI-Watchdog"
```

기존 Scheduled Task를 수정하거나 재등록하지 않는다.

활성화 직후 다음을 확인한다.

- Task State가 Disabled에서 변경되었는가?
- 작업 Action/Trigger/Principal이 보존되었는가?
- 예상치 못한 중복 Watchdog 실행이 있는가?
- 실제 서비스 PID가 변경되었는가?

활성화만으로 즉시 작업이 실행된다고 가정하지 않는다.

### STEP G — 실제 주기 실행 관찰

최초 정기 실행 2회 이상을 확인한다.

확인 사항:

1. Task Scheduler 실행 시각.
2. LastTaskResult.
3. watchdog-status.json checkedAt 갱신.
4. watchdog.log 기록.
5. 구성요소 전체 UP.
6. LastAction NONE.
7. Restart Budget 변화 없음.
8. 장기 Failure Count 변화 없음.
9. Lockout 없음.
10. Watchdog 중복 실행 없음.
11. Spring/Connector/Relay PID 및 CreationDate 불변.
12. PostgreSQL ID/Volume 불변.

실제 Scheduled Task가 실행되지 않는다면 그 원인을 조사한다.

이 단계에서 작업을 임의로 재등록하거나 트리거를 수정하지 않는다.

### STEP H — 비정상 발생 시 조치

다음 상황에서는 운영 Watchdog를 즉시 Disabled로 되돌리고 후속 테스트를 중단한다.

- 예상하지 못한 서비스 재시작
- 잘못된 컴포넌트 판정
- 소유권 UNKNOWN/FOREIGN 상태에서 복구 실행
- Restart Budget의 비정상 증가
- 장기 Lockout 오탐
- Watchdog 반복 실행 오류
- State 파일 손상
- 예상하지 못한 프로세스 종료

비활성화 명령:

```powershell
Disable-ScheduledTask -TaskName "RunningAI-Watchdog"
```

중요:

Scheduled Task를 Disabled로 변경해도 이미 실행 중인 작업이 자동 종료되는 것은 아니다.

실행 중인 프로세스를 임의로 Kill하지 말고 현재 상태를 확인하여 안전한 대응을 결정한다.

State 파일을 자동 복원하거나 서비스를 재시작하지 않는다.

### STEP I — 최종 판정

PASS:

- 정상 상태의 Watchdog 일반 실행 성공
- Scheduled Task 활성화 성공
- 정기 실행 2회 이상 확인
- 정상 서비스 무조치
- PID/CreationDate 불변
- PostgreSQL 불변
- State v2 및 장기 Lockout 정상
- 재시작 예산 비정상 증가 없음
- 서비스 Health 정상

FAIL:

- 비정상 Recovery 실행
- 서비스 중단
- State 손상
- 예산 또는 Lockout 오작동
- Scheduled Task 실행 실패
- 중복 실행으로 인한 충돌

PASS인 경우에만 Watchdog Enabled 상태를 유지한다.

FAIL 시 Disabled 상태로 두고 원인을 보고한다.

### STEP J — 최종 보고

1. Preflight PASS/FAIL
2. Scheduled Task 정의
3. 사전 백업
4. DryRun 결과
5. 일반 모드 수동 실행 결과
6. Watchdog 활성화 여부
7. 실제 Scheduled Task 실행 기록
8. 최초 2회 이상 정기 실행 결과
9. Overall/LastAction/Blocked
10. Restart Budget 및 LongTerm Lockout
11. Spring/Connector/Relay PID 불변
12. PostgreSQL 보존
13. State v2 무결성
14. 최종 Watchdog Enabled/Disabled
15. 이상 발생 및 대응 여부
16. 다음 단계 GO/NO-GO

작업 완료 후 추가적인 장애 시뮬레이션 없이 보고한다.

### 금지사항

- 고의적인 서비스 장애 유발
- Spring/Connector/Relay 수동 종료
- Ctrl+C/Forced Kill 테스트
- Docker Compose down/up
- PostgreSQL 데이터 변경
- Windows 재부팅
- 운영 .env 변경
- Garmin 동기화
- 훈련 생성·게시
- State 수동 Lockout 해제
- Git Reset/Merge/Push
- 기존 로그·백업 삭제
- Legacy C:\running-ai 접근

Phase 6I-1.7D-1 성공은 **정상 상태에서의 자동 감시 동작 성공**을 의미하며, 장애 자동 복구 자체의 E2E 검증 완료를 의미하지 않는다.
