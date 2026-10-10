# WO-RUNNINGAI-PHASE-6I-1.7B-2A
## Connector Recovery Fail-safe & Lifecycle Contract

### 목표

Phase 6I-1.7B-1R에서 완성한 Connector 소유권 모델을 유지하면서, Watchdog와 시작·종료 스크립트의 안전 경계를 보완한다.

이번 작업에서는 세 가지 문제를 해결한다.

1. 소유권이 확인되지 않은 Connector에 대한 재시작 시도
2. 소유권 판정과 실제 종료 사이의 PID 교체 가능성
3. 부분 종료 실패를 정상 종료로 잘못 보고하는 문제

운영 서비스에 영향을 주지 않고 개발 Worktree에서만 작업한다.

### 작업 환경

Development:
`C:\Users\simjy\orca\workspaces\running-ai-github\main-3`

Canonical:
`C:\running-ai-github` — 변경 금지

Expected:
- origin/main: `f6a213588c5b7855b0dc7bf99948896291240ed7`
- origin/main-3: `8f622cd`

Java 21 / Spring Boot 3.5.16 유지.

### STEP 1 — Watchdog PreStop Fail-safe

대상:
`scripts/windows/RunningAI.Watchdog.ps1`

Connector의 RESTART 동작에서 다음을 보장한다.

- `ManagedPids.Count = 0` → 즉시 BLOCK
- `FOREIGN_PROCESS` → BLOCK
- `UNKNOWN_OWNER` → BLOCK
- 소유권 확인 오류 → BLOCK
- 부분 종료 → BLOCK
- 포트 미해제 → BLOCK

위 상태에서 `start-running-ai.ps1`을 호출하지 않는다.

Connector가 정상적인 DOWN 상태이고 포트가 비어 있는 START 경로는 유지한다.

중단 사유는 Watchdog의 blocked/recovery 결과에 전달한다.

기존 Spring/Relay 복구 동작은 수정하지 않는다.

### STEP 2 — 소유권 스냅샷 강화

대상:
`RunningAI.ConnectorOwnership.ps1`

Ownership 판정 결과에 검증한 PID의 CreationDate 및 필요한 실행 신원 정보를 포함하도록 설계한다.

종료 함수에서 다음을 확인한다.

1. 최초 판정 당시 PID/CreationDate
2. 종료 함수 진입 당시 PID/CreationDate
3. 현재 Listener 소유권
4. Launcher/Listener 관계
5. Force 직전 동일성

최초 판정과 현재 검증 결과가 불일치하면 종료를 거부한다.

강제 종료 호출 자체의 PID 재사용 경쟁 가능성도 검토한다. 안전한 프로세스 핸들 기반 종료가 가능하면 검증 후 적용하고, 확실히 보호할 수 없다면 Fail-safe를 우선한다.

단순 PID 번호만으로 강제 종료하지 않는다.

### STEP 3 — 종료 결과 계약 통일

다음 결과를 명확히 구분한다.

- STOPPED
- ALREADY_DOWN
- REFUSED_UNKNOWN_OWNER
- OWNERSHIP_CHANGED
- PORT_STILL_OCCUPIED
- PROCESS_REMAINING
- STOP_FAILED

기존 Result 문자열과 호환성을 유지할 수 있다면 최소 변경을 우선한다.

`Test-RunningAiConnectorStopWasClean`을 단일 성공 판단 기준으로 사용한다.

다음 사항을 보장한다.

- 완전 종료 성공 시에만 PID/Sidecar 정리
- 부분 종료 실패 시 메타데이터 보존
- 수동 종료 실패 시 스크립트 Exit Code 0 금지
- Watchdog PreStop 실패 시 시작 금지
- 새로운 복구 시도 전에 반드시 포트 상태 재확인

### STEP 4 — 소유권 진단 가시성

현재 `OwnershipVerdict`는 Connector State 객체에 추가되지만, `watchdog-status.json`에는 컴포넌트별 State만 기록된다.

운영자가 소유권 불일치를 파악할 수 있도록 JSON 상태 파일에 Connector Ownership 정보를 별도 필드로 추가하는 방안을 검토한다.

기존 `components`, `blocked`, `restartBudget` 필드는 하위 호환성을 유지한다.

인증정보나 전체 프로세스 CommandLine은 기록하지 않는다.

### STEP 5 — 테스트

기존 모든 격리 테스트 유지.

필수 신규 테스트:

1. UNKNOWN_OWNER에서 Restart 차단.
2. FOREIGN_PROCESS에서 Restart 차단.
3. ManagedPids 비어 있을 때 PreStop 차단.
4. DOWN + 미점유 상태에서 정상 START.
5. 최초 Ownership과 종료 직전 PID CreationDate 불일치.
6. 최초 Listener와 현재 Listener 불일치.
7. Graceful 실패 후 포트 점유 유지.
8. Force 직전 PID 재사용 탐지.
9. 부분 종료 시 Exit Code 비정상.
10. 정상 종료 시 기존 Exit Code 유지.
11. Watchdog 상태 JSON 하위 호환성.
12. Spring/Relay 회귀 없음.

실제 Connector 8765 및 운영 프로세스에는 접근하지 않는다.

Mock 및 임시 포트 기반 격리 테스트만 사용한다.

### STEP 6 — 전체 회귀

최소 다음 테스트 수행:

- Test-RunningAI.ps1
- Test-Watchdog.ps1
- Test-ExternalRelay.ps1
- Test-ConnectorOwnership.ps1
- 관련 Windows 테스트 전체

추가 검사:

- PowerShell 5.1 호환성
- git diff --check
- 비밀정보 노출
- 프로세스/임시 파일 잔존 여부
- 실제 운영 서비스 변경 여부

### STEP 7 — Commit 및 Push

전체 PASS 후 관련 파일만 Commit.

Commit message:

`fix(windows): fail closed on unsafe connector recovery`

origin/main-3으로 Fast-forward Push 허용.

금지:
- origin/main Push
- Canonical Merge
- 운영 서비스 재시작
- Watchdog 활성화

### 완료 보고

1. 변경 파일
2. 해결된 결함
3. 소유권 스냅샷 설계
4. PreStop 차단 검증
5. 종료 결과/Exit Code 계약
6. Watchdog JSON 변경사항
7. 신규 테스트 결과
8. 전체 회귀 결과
9. Commit SHA
10. main-3/origin/main-3 일치
11. 운영환경 불변
12. 6I-1.7B-2B 진입 가능 여부

현재 운영 서비스, PostgreSQL 데이터, .env, Watchdog 설정을 절대 변경하지 않는다.

완료 후 보고하고 다음 Phase는 별도 승인 전까지 수행하지 않는다.
