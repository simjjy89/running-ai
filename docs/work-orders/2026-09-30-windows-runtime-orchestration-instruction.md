> 원본 작업지시서 (2026-09-30, Phase 3C-4A). 구현 기록은 `2026-09-30-windows-runtime-orchestration.md` 참고.
> 추가 사용자 지시: Windows runtime orchestration만 구현 / 외부 PC에서 Docker, Garmin login, Windows Scheduled Task를 강제로 변경하지 않음 / Phase 3C-4B 시작 금지 / 결과 보고는 한글.

# RunningAI Phase 3C-4A
## Windows Runtime Orchestration / Automatic Startup / Graceful Shutdown

## 1. 현재 상태

완료: Phase 1 Spring Boot foundation, 2 PostgreSQL / Flyway, 3A Garmin ingestion, 3B Real Garmin E2E, 3C-1 Incremental sync, 3C-2 Manual sync API / status / single-flight, 3C-3 Automatic scheduler.
latest known commit `011aec7`, Java regression baseline 128 / 128 passed.

운영 구성 요소: Docker Desktop → PostgreSQL container / Python 3.12 → Garmin Connector → 127.0.0.1:8765 / Java 21 → Spring Boot RunningAI.
Garmin scheduler는 기본 비활성화이며 운영 환경에서 명시적으로 활성화한다.

## 2. 이번 Phase 목표

Windows PC에서 RunningAI runtime을 한 번에 기동/종료한다.

```text
Windows 로그인/부팅 → RunningAI startup → Docker/PostgreSQL 확인 → Garmin Connector 시작 → Spring Boot 시작 → health 확인 → 운영 가능 상태
RunningAI stop → Spring Boot graceful stop → Garmin Connector stop → PostgreSQL은 기본적으로 유지
```

## 3. 이번 Phase에서 하지 않는 것

Windows Service 구현, NSSM, WinSW, systemd, Raspberry Pi 배포, Spring Boot / Garmin Connector 컨테이너화, connector watchdog, process crash 자동 재시작, distributed lock, external API authentication, scheduler history DB, monitoring dashboard. (Windows Service화와 watchdog은 Phase 3C-4B 이후 후보)

## 4. 핵심 설계 원칙

PowerShell orchestration + Windows Scheduled Task. 복잡한 서비스 매니저를 추가하지 않는다. 기존 RunningAI legacy 운영 경험은 참고할 수 있지만 새 Spring 프로젝트 구조에 맞게 단순하게 구현한다.

## 5–7. 프로젝트 규칙 / Git / Work Order

CLAUDE.md, running-ai-dev, running-ai-integration 사용 (DB schema 변경 없음). `git pull --ff-only origin main` 후 latest에 `011aec7` 확인. 지시서 원문과 결과 문서를 `docs/work-orders/`에 저장.

## 8. 새 scripts 구조

`scripts/windows/`: start-running-ai.ps1, stop-running-ai.ps1, status-running-ai.ps1, install-running-ai-scheduled-task.ps1, uninstall-running-ai-scheduled-task.ps1. 필요하면 내부 helper 추가 가능하나 과도하게 분리하지 않는다.

## 9. start-running-ai.ps1

환경 검증 → Docker Desktop 확인 → PostgreSQL compose 확인 → Garmin Connector 시작 → Spring Boot 시작 → health 확인 → 최종 상태 출력. 멱등: 이미 실행 중인 구성 요소를 중복 실행하지 않는다.

## 10. Repository 위치

`$PSScriptRoot` 기반으로 repo root 자동 계산. `C:\Users\simjy\...` 같은 하드코딩 금지.

## 11–12. Docker Desktop

`docker info`로 daemon 상태 확인. 정상이면 진행, 비정상이면 시작 (`docker desktop start` 우선 검토, fallback `Start-Process "C:\Program Files\Docker\Docker\Docker Desktop.exe"` — 설치 경로 존재 확인).
시작 직후 즉시 compose 호출 금지. timeout 120초, poll 3~5초, `docker info` 성공 시 ready, timeout이면 명확히 실패. 무한 대기 금지.

## 13–15. PostgreSQL

repository root의 기존 `docker-compose.yml` 사용, `docker compose up -d`. compose 구조를 불필요하게 확장하지 않는다. 기존 healthcheck 재사용, `running`이 아니라 `healthy`까지 대기(timeout 120초), timeout이면 connector/Spring을 시작하지 않는다.
stop 기본은 `docker compose down -v` 금지, volume 제거 금지, PostgreSQL은 계속 실행 상태로 둠. 전체 종료가 필요하면 명시적 switch(`-StopDatabase`).

## 16–19. Garmin Connector 환경 / precheck / 시작

`tools/garmin-connector/.venv` (Python 3.12) 기대. `.venv`는 Git 미포함. precheck: .venv 존재, python 존재, required package import, token store 존재 여부(내용은 읽거나 출력하지 않음). token store가 없어도 기동 가능하면 허용 여부를 현재 구현 기준으로 판단. startup script는 Garmin credential/login을 수행하지 않는다.
기존 CLI `serve` 사용 (connector README/source 확인, 추측 금지). 계속 127.0.0.1만, 기본 port 8765.

## 20–24. Connector 중복 방지 / PID / 로그 / health wait

시작 전 `GET http://127.0.0.1:8765/health` 확인, 정상이면 새 process를 시작하지 않는다. port만 점유되고 health가 실패하면 명확한 오류(기존 process를 무조건 kill하지 않음).
PID는 `.runtime/garmin-connector.pid`, `.runtime/`은 git ignore. PID 파일이 있어도 process identity(PID 존재 + command/path가 connector)를 확인, stale PID는 안전하게 정리.
로그 `.runtime/logs/` (garmin-connector.out/err.log, spring.out/err.log), Git ignore, credential/raw payload 기록 금지.
시작 후 `/health` 성공까지 대기(timeout 30초, poll 1초), 실패하면 Spring을 시작하지 않는다. process가 즉시 종료됐다면 exit 상태를 진단 메시지에 포함.

## 25–34. Spring Boot

Gradle wrapper 또는 built jar 중 현재 흐름에 적절한 방식. 권장 `gradlew bootJar` + `java -jar` (artifact naming 확인 후). 매 startup마다 전체 build 금지: jar가 없으면 build, 있으면 기존 jar 실행, `-Build` 옵션으로 rebuild.
시작 전 Java 21 확인 (JDK 경로 하드코딩 금지). Spring에는 실제 프로젝트 설정 기반으로 DB connection, `GARMIN_CONNECTOR_URL`, scheduler enabled가 전달되어야 하며 credential은 스크립트에 작성하지 않는다.
scheduler를 강제로 켜지 않는다 — `RUNNING_AI_GARMIN_SCHEDULER_ENABLED=true`가 설정돼 있으면 활성화, 없으면 기본 false.
`.env` 관련: repository 내부 `.env`에 새 secret 저장을 유도하지 않는다. OS 환경변수 또는 ignored local config만 사용.
`.runtime/spring.pid`, process identity 검증 후 stop. application port는 하드코딩하지 않고 config/README 기준. 기동 판단은 `/actuator/health`(권장) 또는 `/api/v1/health`, timeout 120초 polling. 실패 시 process 상태와 최근 stderr log 경로를 안내.

## 35–37. 의존 순서 / partial failure / exit code

Docker → PostgreSQL healthy → Garmin Connector healthy → Spring Boot healthy. Spring을 먼저 띄우지 않는다.
DB OK / Connector OK / Spring FAIL이면 start 전체는 실패 exit code, 기존 DB는 자동 종료하지 않는다. 이번 startup에서 새로 시작한 connector는 정리(권장), 이미 실행 중이던 connector는 종료하지 않는다.
성공 0, 실패 non-zero (Scheduled Task에서 판단 가능).

## 38–40. status-running-ai.ps1

Docker daemon / PostgreSQL / Garmin Connector / Spring Boot / Garmin Sync State를 한 번에 표시 (Docker, PostgreSQL, GarminConnector, Spring, Scheduler ENABLED/DISABLED/UNKNOWN, LastSync). Garmin login이나 activity fetch를 수행하지 않는다. Connector `/health`와 Spring `/status`(`GET /api/v1/garmin/sync/status`: initialized, highWaterStartedAt, lastSuccessfulSyncAt)만 사용.

## 41–44. stop-running-ai.ps1

기본 종료 순서 Spring Boot → Garmin Connector, DB 유지. Spring/Connector 모두 graceful 종료 우선(Windows에서 실제 가능한 방식 조사), 단순 `Stop-Process -Force`를 기본으로 사용하지 않고 timeout 또는 불가능한 경우에만 강제 종료 fallback. 각 process timeout 10~30초, 무한 대기 금지.

## 45–52. Scheduled Task

`install-running-ai-scheduled-task.ps1`: Windows user logon → start-running-ai.ps1. Trigger `AtLogOn` (AtStartup SYSTEM service 구조는 사용하지 않음: Docker Desktop 사용자 session 의존, 개인 PC, 권한/desktop 단순화). 이름 예 `RunningAI-Startup`, 기존 legacy task 이름과 충돌하면 새 프로젝트임이 드러나는 이름. 현재 로그인 사용자 계정, password 저장 금지, working directory는 repository root. 창이 크게 뜨지 않도록 Scheduled Task PowerShell 옵션으로 해결(VBS wrapper 도입 금지). 이미 존재하면 update/re-register(멱등, 중복 task 금지). `uninstall-running-ai-scheduled-task.ps1`은 RunningAI task만 제거하고 다른 legacy task는 건드리지 않는다.

## 53–57. Legacy 보호 / 로그 / gitignore / secrets

`C:\running-ai` legacy operational project가 별도로 존재할 수 있음 — 이번 script는 legacy directory/task/process를 수정하지 않는다(대상은 새 Git repo runtime뿐). 완전한 log rotation은 만들지 않고 limitation으로 기록. `.runtime/`, `*.pid`, runtime logs는 commit되지 않아야 한다. 저장 금지: Garmin email/password/MFA/token JSON/cookies/PostgreSQL 실제 password/개인 activity JSON/GPS. script log에는 component state, port, PID, health status, startup duration, exit reason만 허용 (token, password, raw activity, activity ID, activityName, GPS 금지).

## 58–62. Tests

가능한 범위에서 script logic을 testable하게 작성(helper function, mock 가능 구조). 실제 Docker Desktop을 켜고 끄는 destructive test 금지. Java 전체 regression `cd server; .\gradlew.bat clean test` 128 전부 PASS (Spring 코드 변경 없음). Python connector 변경 없음(생략 가능). 외부 PC에서 실제 task 등록 강제 금지 — script syntax, command generation, existing task detection 정도만 비파괴 검증. 결과 문서에 `WINDOWS_RUNTIME_LIVE_VALIDATION_NOT_RUN / Reason: main Garmin runtime environment is on another PC` 기록(실패 아님).

## 63. Main PC 최종 validation checklist (문서화)

1. git pull 2. Docker Desktop 실행 3. Garmin connector token 확인 4. install scheduled task 5. start-running-ai.ps1 6. status-running-ai.ps1 7. POST /api/v1/garmin/sync 8. GET /api/v1/garmin/sync/status 9. scheduler live tick 확인 10. Windows 재로그인/재부팅 후 자동기동 확인. 실제 Garmin identifier는 문서에 넣지 않는다.

## 64–65. README / Failure messages

README에 Windows 운영 섹션(Start / Status / Stop / Install startup task / Remove startup task). 실패 메시지는 이해하기 쉽게(예: `Docker daemon did not become ready within 120 seconds.`, `PostgreSQL container did not become healthy.`, `Garmin connector virtual environment was not found.`, `Garmin connector health endpoint did not become ready.`, `Java 21 is not available.`, `Spring Boot health endpoint did not become ready.`), stack trace를 무조건 노출하지 않는다.

## 66. Definition of Done

start / stop / status / Scheduled Task install·uninstall script, repo root 자동 탐색, user-specific path hardcode 없음, Docker ready check·startup, PostgreSQL compose startup·health wait, connector precheck·duplicate prevention·PID tracking·health wait, Java 21 check, Spring PID tracking·health wait, ordered dependency startup, partial failure handling, graceful shutdown strategy, status summary, runtime logs, runtime files gitignored, AtLogOn Scheduled Task, install 멱등, legacy RunningAI untouched, no secrets committed, Java 128+ PASS, README, main-PC validation checklist, full diff review, secrets scan, commit, push.

## 67. 권장 commit

`feat: add Windows runtime orchestration`

## 68. 완료 보고 형식

Startup (Docker, PostgreSQL, Garmin connector, Spring, dependency order, startup timeout) / Process management (connector PID, Spring PID, runtime directory, logs) / Shutdown (Spring, connector, PostgreSQL default, force fallback) / Scheduled Task (name, trigger, account, install, uninstall) / Status command / Tests (Java, PowerShell validation, Python changed) / Database / Live validation (attempted: NO, reason: external development PC) / Security (secrets scan, runtime files ignored, credential handling) / Git / Remaining limitations (process crash watchdog 없음, automatic restart 없음, log rotation 없음, Windows Service 아님, Docker Desktop 사용자 session 의존, distributed lock 없음) / Next Phase 3C-4B (watchdog, process failure detection, restart policy, health diagnostics, log retention, boot/restart reliability) — 자동 시작하지 않는다.

## 69. 핵심 invariant

```text
Windows Logon → RunningAI Scheduled Task → start-running-ai.ps1 → Docker Desktop → PostgreSQL HEALTHY → Garmin Connector UP → Spring Boot UP → Garmin Scheduler
```

dependency order를 건너뛰지 않는다. credential을 orchestration layer에 넣지 않는다. legacy RunningAI 환경을 수정하지 않는다. startup script는 반복 실행해도 안전해야 한다.
