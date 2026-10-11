# WO-RUNNINGAI-PHASE-6I-1.7C-3 — Controlled Watchdog State v2 Transition (작업 지시서 원문)

## Controlled Watchdog State v2 Transition

### 1. 작업 목표

실제 운영 환경의 Watchdog State 파일을 v1에서 v2로 안전하게 전환한다.

이번 Phase에서 허용되는 변경은 Watchdog State 파일, 해당 백업, 그리고 통제된 NoRecovery 실행에 필요한 상태·로그 기록뿐이다.

**운영 서비스는 재시작하지 않으며 Watchdog Scheduled Task는 Disabled 상태로 유지한다.**

### 2. 기준 환경

Canonical: `C:\running-ai-github`

Development: `C:\Users\simjy\orca\workspaces\running-ai-github\main-3`

Legacy: `C:\running-ai` — 접근 및 변경 금지

Expected Git:
- Canonical main: `36e1e96cc92a7c6f1d808b243bee19bc9a316abf`
- origin/main: `f6a213588c5b7855b0dc7bf99948896291240ed7`
- origin/main-3: `a83e58a4ee684833cbf0fdf6a76ef0607042884e`

운영 기준:
- Spring 127.0.0.1:18080
- Connector 127.0.0.1:8765
- Relay 127.0.0.1:17845
- PostgreSQL 기존 Container ID/Volume 유지
- Watchdog Disabled
- 현재 watchdog-state.json version 1

### STEP A — 사전 검증

읽기 전용으로 확인한다.

1. Canonical HEAD와 Working Tree.
2. Git 기준 SHA 일치 여부.
3. Watchdog Disabled 및 실행 중인 Watchdog 프로세스 없음.
4. RunningAI-Startup 작업 실행 중 아님.
5. Docker/PostgreSQL/Connector/Spring/Relay 모두 UP.
6. 기존 LISTEN PID와 CreationDate 일치.
7. Connector Ownership = LAUNCHER_CHILD.
8. 운영 .env 및 PID 파일 상태 정상.
9. watchdog-state.json 존재 및 version=1.
10. 기존 watchdog-state.json.bak 존재 여부.
11. State 파일의 현재 SHA256/Length/LastWriteTimeUtc.
12. Runtime Lock 및 Watchdog State Lock 경합 없음.

한 항목이라도 예상과 다르면 STOP.

### STEP B — v1 원본 백업

State를 수정하기 전에 전용 백업을 생성한다.

권장 위치:
`C:\Users\simjy\.runningai-state-backups\`

요구사항:

- 타임스탬프가 포함된 고유 파일명.
- SYSTEM 및 현재 운영 사용자만 접근 가능한 ACL.
- Canonical Git 저장소 외부에 보관.
- 원본과 백업의 SHA256 완전 일치.
- 백업 파일 version=1 확인.
- 기존 `.bak` 파일과 혼동하지 않음.
- 백업을 덮어쓰지 않음.

복원 가능한 백업이 확인되지 않으면 전환하지 않는다.

백업 경로와 SHA256만 보고하며 State 전체 내용은 로그에 출력하지 않는다.

### STEP C — 기존 Restart History 검증

실제 변경 전에 `Read-WatchdogState -ReadOnly`로 v1을 읽는다.

확인 사항:

- Available=True
- version=1
- `restarts` 구조 정상
- 모든 컴포넌트별 History 파싱 가능
- 타임스탬프 타입과 범위 유효
- 기존 값과 파싱 결과 일치
- 신규 LongTermState가 빈 상태로 초기화 가능한지

기존 Restart History를 컴포넌트별 개수와 값의 동일성으로 검증한다.

주의:

`Update-HistoryWindow`는 이 단계에서 호출하지 않는다. 과거 Restart History를 전환 과정에서 임의로 제거하지 않아야 한다.

`Read-WatchdogState`의 비-ReadOnly 경로는 손상된 파일을 격리 이동할 수 있으므로 사전 검증에 사용하지 않는다.

### STEP D — State v2 전환

전환 전 소스 코드를 확인한다.

- `Enter-RunningAiWatchdogStateLock`
- `Exit-RunningAiWatchdogStateLock`
- `Read-WatchdogState`
- `New-EmptyLongTermState`
- `Save-WatchdogState`
- `Write-JsonAtomic`

다음 순서로 수행한다.

1. Watchdog Disabled 재확인.
2. Watchdog State Lock 획득.
3. Lock을 보유한 상태에서 원본 State 재읽기.
4. 원본 백업과 내용이 바뀌지 않았는지 재확인.
5. 기존 History를 변경하지 않고 메모리에 보존.
6. 신규 LongTermState 초기화.
7. `Save-WatchdogState`를 사용해 version 2 저장.
8. 저장 후 State 및 `.bak` 무결성 확인.
9. `finally`에서 Lock 해제.

새 v2 구조:

- version = 2
- restarts = 기존 기록 그대로 유지
- longTerm = 컴포넌트별 빈 failures 및 null lockedOutSince

v1은 장기 실패 기록을 갖고 있지 않으므로 과거 실패를 추측해 신규 Lockout을 만들지 않는다.

저장은 단 한 번 수행한다.

다음 상태면 즉시 STOP한다.

- State Lock 획득 실패
- 원본 백업 불일치
- JSON 파싱 실패
- 저장 중 예외
- `.bak` 저장 실패
- Restart History 불일치
- version 불일치

### STEP E — 전환 직후 무결성 검사

다음을 검증한다.

1. State version=2.
2. `.bak` 존재 및 version=2.
3. State와 `.bak` 내용 동일.
4. 원본 v1 Restart History의 모든 값 보존.
5. 컴포넌트별 LongTermState 구조 정상.
6. 모든 LockedOutSince = null.
7. 모든 초기 FailureCount = 0.
8. 운영 PID/Sidecar/.env 불변.
9. Watchdog Disabled.
10. 서비스 Health 정상.

여기까지 성공했을 때만 다음 단계로 진행한다.

### STEP F — NoRecovery 사전 안전 점검

`watch-running-ai.ps1 -NoRecovery`는 ReadOnly가 아니다.

다음 변경이 예상된다.

- watchdog-state.json 재저장
- watchdog-state.json.bak 갱신
- watchdog-status.json 갱신
- watchdog.log 추가 기록
- Restart History의 오래된 타임스탬프 정리
- 기존 로그 로테이션/보존기간 처리

따라서 실행 전에 다음을 확인한다.

1. 현재 State v2가 유효하다.
2. State 원본 v1 백업이 보존되어 있다.
3. Watchdog Disabled.
4. 모든 서비스 UP.
5. 모든 운영 PID 파일의 소유권 검사 정상.
6. NoRecovery에서 복구 Executor가 호출되지 않음.
7. 로그 로테이션으로 기존 장애 증거가 삭제되지 않도록 보존기간/크기를 검토한다.
8. 신규 Sidecar 생성이나 PID 정리가 발생하지 않는 호출 경로인지 확인한다.

안전을 입증할 수 없으면 NoRecovery를 실행하지 않고 STOP한다.

### STEP G — NoRecovery 1회 실행

STEP F가 PASS일 때만 다음 명령을 실행한다.

```powershell
Set-Location "C:\running-ai-github"

powershell.exe -NoProfile -ExecutionPolicy Bypass `
  -File ".\scripts\windows\watch-running-ai.ps1" `
  -NoRecovery `
  -ConnectorPort 8765 `
  -SpringPort 18080 `
  -LogMaxSizeMB 1024 `
  -LogRetentionDays 3650

$exitCode = $LASTEXITCODE
Write-Output "WATCHDOG_NORECOVERY_EXIT=$exitCode"
```

로그 크기와 연령을 먼저 확인하여 위 보존 설정이 기존 로그를 삭제하지 않는다는 것을 검증한다. 보장할 수 없으면 STOP.

1회만 실행하며 재시도하지 않는다.

예상 결과:

- Exit Code 0
- Overall UP
- Recovery Action 0
- LastAction NONE
- 모든 Component UP
- State version=2 유지
- Watchdog Task Disabled 유지

기존 Restart History 중 10분 창 밖의 기록이 이 단계에서 만료 정리되는 것은 코드상 의도된 동작이다.

단, STEP E에서는 원본 이력을 완전히 보존했음을 먼저 증명해야 한다.

STEP G 이후에는 정상적인 창 만료 정리 외의 예상치 못한 이력 삭제가 없어야 한다.

### STEP H — 최종 운영 검증

다음을 확인한다.

- State 및 .bak 모두 유효한 v2
- watchdog-status.json 정상 생성/갱신
- Restart History 정리 결과가 정책과 일치
- 장기 Lockout 값 정상
- State 원본 v1 백업 보존
- Connector Ownership 상태 정상
- Spring/Connector/Relay PID 및 CreationDate 불변
- PostgreSQL 기존 Container ID/Volume/Health 불변
- Local/Public API 정상
- 운영 .env/PID/Sidecar 불변
- Git Working Tree Clean
- Watchdog Disabled
- RunningAI-Startup 별도 실행 없음

상태 JSON이나 로그에 비밀정보가 기록되지 않았는지 확인한다.

### STEP I — 실패 및 복구 정책

State 마이그레이션 실패 시:

1. 즉시 후속 단계 중단.
2. Watchdog Disabled 확인.
3. 손상된 State 및 현재 .bak를 증거로 보존.
4. 기존 v1 원본 백업의 무결성 재확인.
5. 실제 서비스를 재시작하지 않음.
6. 원인 분석 후 수동 복원 절차 보고.

자동적인 Git Reset, State 덮어쓰기 또는 서비스 재시작으로 문제를 감추지 않는다.

수동 복원이 필요하면 Watchdog State Lock을 확보하고, 다른 Writer가 없음을 확인한 뒤, 승인된 원본 스냅샷으로 복원하는 절차를 사용한다.

이번 작업은 실패했다고 무조건 재실행하지 않는다.

### STEP J — 최종 보고

다음 항목을 모두 보고한다.

1. Preflight PASS/FAIL
2. 원본 State v1 백업 경로 및 SHA256
3. 기존 Restart History 개수
4. v2 저장 결과
5. State/.bak 무결성
6. 기존 History 보존 결과
7. NoRecovery 실행 여부 및 Exit Code
8. NoRecovery 이후 정상적인 History 만료 정리 내역
9. watchdog-status.json 결과
10. Connector Ownership Verdict
11. Spring/Connector/Relay PID 불변
12. PostgreSQL 보존 여부
13. Watchdog Disabled 상태
14. Git 브랜치 및 Working Tree
15. 운영 변경 파일 목록
16. 잔여 위험 및 Rollback 준비 상태
17. Phase 6I-1.7D 진입 GO/NO-GO

완료 후 STOP한다.

### 절대 금지사항

- Watchdog Scheduled Task 활성화
- Watchdog 일반 복구 모드 실행
- 운영 서비스 Start/Stop/Restart
- Ctrl+C 또는 Forced Kill
- Windows 재부팅
- Docker Compose up/down
- PostgreSQL 데이터·볼륨 변경
- 운영 .env 수정
- Connector PID/Sidecar 수정
- Lockout 자동 해제
- Garmin 동기화
- 훈련 생성·승인·게시
- Canonical Git 변경/Reset
- origin/main Push
- Legacy 경로 접근

이번 Phase의 성공은 **State v2 전환 성공**만을 의미한다. Watchdog 자동 복구 기능이 실제 운영에서 안전하게 작동한다는 최종 판정은 아니다.
