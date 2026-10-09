Phase 6I-1.1 live validation 중 실제 regression 2건이 발견됐다.
현재 READY/reboot/failure-injection 진행 금지.

Repo:
C:\Users\simjy\orca\workspaces\running-ai-github\main-3

현재 main/main-3 기준 commit:
77d8ccd861971727e9bead87413d5d43fb229c73

중요:
- C:\running-ai-watchface 는 다른 작업 중이다. 절대 접근/수정하지 마라.
- C:\running-ai legacy도 수정 금지.
- RunningAI runtime은 현재 정상 동작 중이므로 불필요한 stop/restart 금지.
- secrets/.env/token 출력/수정 금지.
- PC reboot 금지.

LIVE 결과:

1) canonical runtime 정상:
Docker RUNNING
PostgreSQL HEALTHY
GarminConnector managed PID 54540
Spring managed PID 55520
ExternalRelay managed PID 27668
Tailscale Running/Automatic
Funnel configured
authenticated local/public /today-workout = HTTP 200

2) RunningAI-Watchdog task 등록 성공:
AtLogOn +5m / every 5m
Manual Start-ScheduledTask 실행:
LastTaskResult = 0
status:
Watchdog UP
LastRecovery NONE

하지만 regression 2건 발견:

BUG A — direct DryRun failure
명령:
.\scripts\windows\watch-running-ai.ps1 -DryRun

결과:
ERROR: 'Quote-Argument' 용어가 cmdlet, 함수, 스크립트 파일 또는 실행할 수 있는 프로그램 이름으로 인식되지 않습니다.

현재 RunningAI.Watchdog.ps1의 Get-DefaultProbes에서
Postgres probe가 .GetNewClosure() 내부에서:

$(Quote-Argument $compose)

를 호출한다.

중첩 dot-source / closure 실행 scope에서 helper function resolution이 깨지는 것으로 의심된다.

반드시 실제 root cause를 재현/확인하고 수정할 것.
임시 global function 주입 같은 우회 금지.

권장 검토 방향:
- Quote-Argument가 필요한 값을 closure 생성 전에 계산하고
  primitive/string value만 closure에 capture하거나,
- closure가 다른 script-scope helper resolution에 의존하지 않도록 만들 것.

BUG B — external-relay diagnostics omitted
현재 status-running-ai.ps1 출력:

RestartBudget docker=0 postgres=0 connector=0 spring=0

external-relay가 빠져 있다.

소스 확인 결과 watch-running-ai.ps1의 최종 status JSON 생성부에서:

foreach ($c in $script:Components)

를 사용하여 restartBudget.used와 components를 만들고 있다.
6I-1.1 이후에는 external-relay도 포함해야 하므로
$script:AllTrackedComponents 사용 여부를 검토/수정해야 한다.

DryRun component 출력 역시 $script:Components만 순회하므로
external-relay가 출력되지 않는다.

Acceptance:

A. .\scripts\windows\watch-running-ai.ps1 -DryRun
- exit 0
- docker/postgres/connector/spring/external-relay 모두 표시
- 모두 현재 건강하면 Planned actions: none
- Overall UP
- 어떤 파일도 쓰거나 프로세스를 재시작하지 않음

B. normal one-shot:
.\scripts\windows\watch-running-ai.ps1
- exit 0
- lastAction NONE
- watchdog-status.json components에 external-relay 포함
- restartBudget.used에 external-relay=0 포함

C. status:
.\scripts\windows\status-running-ai.ps1
- Watchdog UP
- RestartBudget에 external-relay=0 표시

D. regression tests:
- 기존 183 PowerShell 전체 + 신규 lock-in tests
- Node 14/14 유지
- server/ 변경 0
- dry-run이 실제 default probe 경로에서도 Quote-Argument scope regression을 잡는 테스트 추가
- external-relay가 status JSON components / restartBudget / DryRun output에서 빠지지 않는 테스트 추가

E. live validation:
- 현재 healthy runtime을 죽이지 않고 DryRun + normal one-shot만 수행
- Scheduled Task manual run도 다시 1회 수행
- LastTaskResult 0 확인
- ExternalRelay PID가 불필요하게 바뀌지 않았음을 확인

F. git:
검증 후 main-3 commit/push.
권장 commit:
fix(watchdog): harden live dry-run and relay diagnostics

그 다음 C:\running-ai-github main working tree clean 확인 후:
git fetch origin
git merge --ff-only origin/main-3
git push origin main

최종:
main-3 = origin/main-3 = main = origin/main
working trees clean.

보고 항목:
- BUG A 정확한 root cause
- BUG B 정확한 root cause
- 수정 파일
- PowerShell test count
- Node count
- live DryRun 결과
- live normal watchdog 결과
- Scheduled Task LastTaskResult
- status RestartBudget 전체
- relay PID before/after
- commit SHA
- four-ref SHA equality
- remaining acceptance

아직 PHASE_6I_1_EXTERNAL_ACCESS_E2E_READY 선언 금지.
아직 relay kill/failure injection 및 PC reboot 금지.
