# WO-RUNNINGAI-PHASE-6I-1.7C-2 — Production Watchdog Read-Only Validation (작업 지시서 원문)

## Production Watchdog Read-Only Validation

### 1. 작업 목표

Canonical에 반영된 신규 Watchdog 코드가 실제 Main PC 환경에서 올바르게 동작하는지 검증한다.

이번 Phase에서 허용하는 실행은 **읽기 전용 관찰과 `watch-running-ai.ps1 -DryRun` 1회**로 제한한다.

운영 Watchdog 활성화, 상태 파일 v2 전환, 서비스 재시작은 수행하지 않는다.

### 2. Git 기준

Canonical:
`C:\running-ai-github`

Development:
`C:\Users\simjy\orca\workspaces\running-ai-github\main-3`

Expected:

- Canonical main: `36e1e96cc92a7c6f1d808b243bee19bc9a316abf`
- origin/main: `f6a213588c5b7855b0dc7bf99948896291240ed7`
- main-3/origin/main-3: `e031e4fb4362c91b033e415130309c30d4cbaf0c`

Development의 최신 커밋은 6I-1.7C-1 결과 문서만 추가했으므로 Canonical과 실행 코드가 동일해야 한다.

Git SHA와 실행 스크립트 해시를 확인한다.

불일치 시 STOP.

### 3. STEP A — 운영 Preflight

다음 항목을 읽기 전용으로 수집한다.

1. RunningAI-Watchdog Disabled.
2. RunningAI-Startup 실행 중이 아님.
3. Spring 127.0.0.1:18080 LISTEN PID.
4. Connector 127.0.0.1:8765 LISTEN PID 및 Launcher PID.
5. Relay 127.0.0.1:17845 LISTEN PID.
6. PostgreSQL 컨테이너 ID/Volume/Health.
7. Local Health 응답.
8. Canonical .env의 SERVER_PORT=18080 및 SERVER_ADDRESS=127.0.0.1.
9. Runtime Lock 관련 환경변수 충돌 없음.
10. 기존 Watchdog State 버전 v1.

기존 PID 5092/9484/10224/30876은 비교 기준으로 사용하되, 조사 시점의 실제 PID와 CreationDate도 확인한다.

예상하지 못한 재시작이 있었다면 STOP하고 원인부터 보고한다.

### 4. STEP B — 운영 파일 불변 기준 확보

DryRun 전 다음 파일의 존재 여부, LastWriteTimeUtc, Length, SHA256을 기록한다.

- `.env`
- `.runtime\garmin-connector.pid`
- `.runtime\spring.pid`
- `.runtime\external-relay.pid`
- `.runtime\garmin-connector.owner.json` (존재하는 경우)
- `.runtime\watchdog-state.json`
- `.runtime\watchdog-state.json.bak` (존재하는 경우)
- `.runtime\watchdog-status.json` (존재하는 경우)

비밀정보는 출력하지 않고 해시와 메타데이터만 기록한다.

Watchdog 로그 파일의 존재 여부와 크기도 기록한다.

다른 정상 운영 프로세스가 자체적으로 작성하는 로그의 변경은 DryRun 위반으로 단정하지 않는다.

### 5. STEP C — DryRun 부작용 경로 검토

실행 전에 다음을 소스 수준에서 확인한다.

- `-DryRun`에서 `Read-WatchdogState -ReadOnly` 적용
- `Get-RuntimeObservation -ReadOnly` 전파
- `Get-TrackedProcessId -ReadOnly` 전파
- PID 파일 삭제 없음
- State Quarantine 없음
- JSON 저장 없음
- 로그 로테이션 없음
- 서비스 복구 Executor 호출 없음
- Docker Compose 변경 없음
- PostgreSQL 변경 없음
- Garmin 동기화 없음

주의:

`status-running-ai.ps1`은 현재 내부에서 `Get-TrackedProcessId`를 `-ReadOnly` 없이 호출하므로, 이번 무변경 검증에서는 실행하지 않는다.

ReadOnly 호출 경로에 의심스러운 쓰기 부작용이 있으면 DryRun도 실행하지 않고 STOP한다.

### 6. STEP D — Watchdog DryRun 1회

STEP A~C가 PASS일 때만 Canonical에서 실행한다.

```powershell
Set-Location "C:\running-ai-github"

powershell.exe -NoProfile -ExecutionPolicy Bypass `
  -File ".\scripts\windows\watch-running-ai.ps1" `
  -DryRun `
  -ConnectorPort 8765 `
  -SpringPort 18080

$dryRunExitCode = $LASTEXITCODE
Write-Output "WATCHDOG_DRYRUN_EXIT=$dryRunExitCode"
```

반드시 `-DryRun`을 사용한다.

`-NoRecovery` 단독 실행은 금지한다. 해당 옵션은 서비스 재시작을 막지만 상태 JSON과 로그를 기록할 수 있으므로 읽기 전용이 아니다.

추가로 다음을 금지한다.

- `Invoke-RecoveryAction` 직접 호출
- `Invoke-WatchdogRecovery` 실제 Runner 호출
- `start-running-ai.ps1` 호출
- `stop-running-ai.ps1` 호출

DryRun이 실패하면 원인만 수집하고 무조건 재시도하지 않는다.

### 7. STEP E — DryRun 결과 검증

다음을 확인한다.

1. Exit Code 0.
2. Docker UP.
3. PostgreSQL UP.
4. Connector UP.
5. Spring UP.
6. External Relay UP.
7. 계획된 복구 Action 없음.
8. 비정상적인 Blocked 상태 없음.
9. `SERVER_PORT=18080` 적용.
10. 실제 외부 Relay와 Spring 포트를 혼동하지 않음.

오래된 Restart History 또는 Garmin Hint가 출력된다면 장애와 구분하여 해석한다.

상태가 모두 UP이어도 실제 소유권 판정은 별도로 확인한다.

### 8. STEP F — Connector 소유권 읽기 전용 검사

신규 소유권 모델을 사용해 실제 Connector 프로세스를 분석한다.

기대:

- Launcher PID가 기존 추적 PID와 동일
- Listener PID가 실제 8765 LISTEN 소유 PID와 동일
- Verdict = LAUNCHER_CHILD
- ManagedPidSnapshot에 두 PID의 CreationDate 반영
- 운영 `.owner.json`은 신규 생성하지 않음

소유권 조회 함수만 사용한다.

Stop 함수 및 Sidecar 기록 함수는 호출하지 않는다.

UNKNOWN_OWNER, FOREIGN_PROCESS 또는 예상과 다른 결과면 STOP하고 원인을 보고한다.

### 9. STEP G — 불변성 검증

DryRun 후 다음을 재확인한다.

- Spring/Connector/Relay PID 및 CreationDate 불변
- 운영 서비스 Health 정상
- PostgreSQL 컨테이너/Volume 불변
- Watchdog Disabled 유지
- `.env` 해시 불변
- PID/Sidecar 파일 해시 및 존재 여부 불변
- watchdog-state.json v1 유지
- watchdog-status.json 불변
- DryRun으로 신규 복구/로그 파일 생성 없음
- Canonical Git Working Tree Clean

특히 v1 상태 파일을 v2로 자동 변경하지 않았는지 확인한다.

### 10. STEP H — 다음 운영 단계 계획

위 검증이 PASS이면 실제 서비스가 아닌 Watchdog 운영 상태 파일만 안전하게 전환하는 후속 작업 절차를 작성한다.

후속 단계 후보:

**Phase 6I-1.7C-3 — Controlled Watchdog State v2 Transition**

이 단계의 설계에는 다음이 반드시 포함되어야 한다.

1. 기존 v1 상태 백업.
2. 기존 Restart History 보존.
3. v2/longTerm 구조 적용.
4. 상태 파일 및 백업 무결성 검증.
5. Watchdog는 계속 Disabled.
6. 서비스 재시작 없이 NoRecovery 검증.
7. 실패 시 State 복구 정책.
8. Watchdog 활성화는 별도 승인.

이번 6I-1.7C-2에서는 위 계획만 작성하며 실행하지 않는다.

### 11. 금지사항

- Watchdog 활성화
- Watchdog 실제 자동 복구 실행
- RunningAI-Startup 실행
- 서비스 Stop/Start/Restart
- Ctrl+C 또는 Forced Kill
- 운영 `.env` 변경
- State v2 Migration
- PID/Sidecar 기록
- Lockout 해제
- Docker Compose up/down
- PostgreSQL 변경
- Windows 재부팅
- Garmin 동기화
- 훈련 생성·게시
- Canonical 코드 수정
- origin/main Push
- Legacy 경로 접근

### 12. 최종 보고

1. Git SHA
2. 운영 Preflight
3. DryRun 실행 명령 및 Exit Code
4. Component 상태별 결과
5. Connector Ownership Verdict
6. 복구 Action 계획
7. DryRun 전후 파일 해시 비교
8. PID/CreationDate 불변
9. PostgreSQL 보존
10. Watchdog Disabled 유지
11. Ctrl+C 관련 잔여 위험
12. State v2 전환 절차 및 Rollback 설계
13. Phase 6I-1.7C-3 GO/NO-GO

완료 후 결과를 보고하고 STOP한다.

신규 운영 기능을 자동으로 활성화하지 않는다.
