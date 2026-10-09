Phase 6I-1.1 — External Relay Environment Isolation Fix

Repo:
C:\Users\simjy\orca\workspaces\running-ai-github\main-3

Current HEAD:
43799f76339b61532b4107c4083f5224383afe2f

IMPORTANT:
- C:\running-ai-watchface 절대 접근/수정 금지
- C:\running-ai legacy 수정 금지
- secrets/.env 값 출력 금지
- PC reboot 금지
- Garmin 265 작업 금지
- 현재 runtime 불필요한 restart 금지

LIVE REGRESSION BUG C:

Watchdog real recovery itself는 성공:
ExternalRelay 46972 stop
→ watch-running-ai.ps1
→ START_EXTERNAL_RELAY:SUCCESS
→ new PID 53700
→ Watchdog exit 0
→ external-relay restart budget=1

그러나 새 relay의 authenticated /today-workout은
local/public 모두 UPSTREAM_ERROR.

relay.log:
TODAY_WORKOUT_FAILED error=intervals.icu HTTP 401

credential identity를 secret 출력 없이 확인한 결과:

.env:
INTERVALS_API_KEY length=25
INTERVALS_ICU_API_KEY length=25
File_VALUES_EQUAL=True

current process:
INTERVALS_API_KEY absent
INTERVALS_ICU_API_KEY length=25
ProcessICU_Equals_FileAPI=False
ProcessICU_Equals_FileICU=False

User environment:
INTERVALS_ICU_API_KEY exists
Machine environment:
none

즉 stale User-scoped INTERVALS_ICU_API_KEY가 scheduled task/new shell에
상속되고 repo .env의 정상 값을 shadow한 것이 401의 직접 원인.

운영자가 stale User env override는 별도로 제거할 예정이다.

그러나 코드에도 독립 결함이 있다:

scripts/windows/external/start-external-relay.ps1 는
RunningAI.Common.ps1을 dot-source하지만
Initialize-DotEnvForThisProcess 를 호출하지 않는다.

따라서 stale user env를 제거한 후에는
RunningAI-Watchdog Scheduled Task가 별도 process에서
start-external-relay.ps1을 실행할 때
INTERVALS_ICU_API_KEY 자체를 받지 못할 수 있다.

Canonical start에서 우연히 부모 process가 .env를 로드한 경우에만 동작하는 구조는 금지.
External relay lifecycle은 독립적으로 안전하게 시작 가능해야 한다.

REQUIRED FIX:

1. start-external-relay.ps1이 Node 시작 전에
   repo-root .env를 기존 canonical helper
   Initialize-DotEnvForThisProcess
   로 로드하도록 한다.

2. 기존 precedence는 변경 금지:
   existing process env > .env
   이 정책 자체는 RunningAI 전체 계약이다.
   외부 relay만 .env로 강제 override하는 별도 로직 금지.

3. secret value 출력 금지.
   기존 helper처럼 적용 key 이름/count만 허용.

4. 이미 healthy relay이면 불필요하게 restart하지 않는 기존 idempotency 유지.

5. tests:
   - isolated child process에서 parent에 INTERVALS_ICU_API_KEY가 없어도
     repo/test .env에서 값을 받아 relay child에 전달되는 것을 검증
   - existing process env가 있으면 .env가 덮어쓰지 않는 기존 precedence lock-in
   - secret value가 stdout/stderr에 나오지 않음
   - existing ExternalRelay tests 모두 유지
   - Watchdog tests 모두 유지

6. production .env 또는 secrets 파일 수정 금지.
   test fixture/temp env file만 사용.

7. PowerShell full regression + Node full regression 실행.

LIVE VALIDATION AFTER MERGE:
- operator가 stale User-scoped INTERVALS_ICU_API_KEY를 제거한 상태에서 수행
- current shell에서도 해당 key 제거
- relay를 공식 stop script로 stop
- watch-running-ai.ps1 직접 실행
- relay 새 PID 확인
- local authenticated /today-workout HTTP 200
- public Funnel authenticated /today-workout HTTP 200
- Watchdog lastAction START_EXTERNAL_RELAY:SUCCESS
- no other component PID changes

Commit suggestion:
fix(external-relay): load canonical environment on standalone start

그 후 main-3 push,
C:\running-ai-github main clean 확인,
fetch + ff-only merge + push.

최종 보고:
- exact root cause
- files changed
- tests counts
- live validation
- before/after PIDs
- local/public HTTP result
- commit SHA
- four-ref equality

PHASE_6I_1_EXTERNAL_ACCESS_E2E_READY 선언 금지.
PC reboot 금지.
