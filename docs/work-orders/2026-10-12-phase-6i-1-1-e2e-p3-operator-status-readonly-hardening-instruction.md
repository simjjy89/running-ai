# WO-RUNNINGAI-PHASE-6I-1.1-E2E-P3 — Operator Status Read-Only Hardening (작업 지시서 원문)

## 목적

status-running-ai.ps1이 어떤 PID 상태에서도 운영 파일을 변경하지 않는 읽기 전용 진단 도구가 되도록 수정한다.

이번 작업은 개발 Worktree에서만 수행하며, 실제 운영 프로세스에는 영향을 주지 않는다.

## 작업 기준

* Canonical: C:\running-ai-github, HEAD 54aa849
* Development: main-3, HEAD 27bfd6e
* origin/main: 변경 금지
* REBOOT_PRECHECK=GO 유지
* 운영 Watchdog Enabled 유지

main-3-2, C:\running-ai, C:\running-ai-watchface는 접근하거나 변경하지 않는다.

### STEP A — 원인 확인

scripts/windows/status-running-ai.ps1에서 다음 3개 호출을 확인한다.

* Garmin Connector의 Get-TrackedProcessId
* Spring의 Get-TrackedProcessId
* External Relay의 Get-TrackedProcessId

현재 -ReadOnly가 누락되어 있는지 확인한다.

RunningAI.Common.ps1의 해당 함수가 오래된 PID를 감지하면 Remove-PidFile을 호출할 수 있다는 점을 재검증한다.

### STEP B — 최소 코드 수정

위 세 호출에 -ReadOnly를 적용한다.

그 외 구성요소의 실행·복구 동작은 변경하지 않는다.

status-running-ai.ps1의 기존 출력 형식, Exit Code, Health 조회 계약을 유지한다.

스크립트에 남아 있는 다른 파일 쓰기 경로도 정적 감사한다. 새로운 문제가 발견되면 임의로 범위를 확장하지 말고 먼저 보고한다.

### STEP C — 격리 회귀 테스트

RUNNING_AI_TEST_RUNTIME_DIR과 테스트 전용 환경을 사용한다.

다음 시나리오를 확인한다.

1. 정상 Managed PID 조회.
2. 이미 종료된 Stale PID 조회 후 PID 파일 보존.
3. 다른 프로세스의 PID가 기록된 경우 파일 보존.
4. Connector/Spring/Relay 세 컴포넌트 모두 동일 정책.
5. 정상 Health 및 기존 출력 형식 유지.
6. 테스트가 운영 .runtime에 쓰기를 수행하지 않음.

PID 파일 내용과 SHA256을 조회 전후 비교한다.

테스트를 위해 실제 운영 PID 파일을 변조하거나 운영 서비스를 종료하지 않는다.

### STEP D — 회귀 검증

기존 관련 PowerShell 테스트를 재실행한다.

필요하면 전체 Windows 테스트 스위트도 실행한다.

git diff --check, PowerShell 5.1 구문 검사, 비밀정보 노출 검사도 수행한다.

실패 테스트를 삭제하거나 PASS로 간주하지 않는다.

### STEP E — 운영 무변경 검증

테스트 전후 확인한다.

* Spring/Connector/Relay PID 및 CreationDate
* PostgreSQL Container/Volume/Health
* Watchdog LastTaskResult
* Restart Budget/LongTerm Lockout
* DB Activity/HighWater
* Canonical Git HEAD 및 Working Tree

운영 서비스 재시작은 0건이어야 한다.

### STEP F — Git 및 최종 보고

수정은 main-3에서만 수행한다.

회귀 PASS 후 커밋하고 origin/main-3로 Push한다.

Canonical Merge와 origin/main Push는 수행하지 않는다.

보고 내용:

* 원인 및 수정 위치
* ReadOnly 전파 확인
* Stale/Foreign PID 보존 테스트
* 전체 회귀 결과
* 운영 불변 검증
* 변경 파일 및 Commit SHA
* 다음 단계 GO/NO-GO

## 금지

* Windows 재부팅·로그아웃
* 실제 Relay 중지·복구
* Watchdog 일반 실행·재등록
* PostgreSQL 변경
* 운영 .env 및 토큰 변경
* 실제 Garmin 동기화·훈련 게시
* 다른 Worktree 또는 레거시 저장소 변경

완료 후 STOP한다.
