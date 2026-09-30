> 원본 작업지시서 (2026-09-30, Phase 3C-4B). 구현 기록은 `2026-09-30-windows-watchdog.md` 참고.
> 추가 사용자 지시: 메인 runtime PC 접근 불가 → 실제 Docker/Garmin/Windows runtime live validation 금지, 외부 PC에서는 비파괴 PowerShell self-check + Java 전체 regression까지만 / 3C-4C 시작 금지 / 결과 보고는 한글.

# RunningAI Phase 3C-4B
## Watchdog / Recovery / Diagnostics / Log Retention

## 1. 현재 상태

완료: Phase 1 Spring Boot foundation, 2 PostgreSQL / Flyway, 3A Garmin ingestion, 3B Real Garmin E2E, 3C-1 Incremental sync, 3C-2 Manual sync API / status / single-flight, 3C-3 Automatic scheduler, 3C-4A Windows runtime orchestration.
latest known commit `8959f6b`, Java regression baseline 128 / 128.
4A scripts: start / stop / status / install·uninstall scheduled task. 의존: Docker Desktop → PostgreSQL → Garmin Connector → Spring Boot.

## 2. 중요한 현재 제약

메인 Garmin PC에 접근할 수 없다. `WINDOWS_RUNTIME_LIVE_VALIDATION = PENDING` 유지(실패 아님). 외부 PC에서 실제 Docker Desktop, Garmin login/token, real PostgreSQL runtime, real Spring runtime recovery를 강제로 실행하지 않는다.

## 3. 이번 Phase 목표

장애를 주기적으로 감지하고 복구 가능한 장애에 한해 안전하게 복구하는 watchdog. `Detect → Classify → Recover only when safe → Limit retries → Record diagnostics`.
구현: `watch-running-ai.ps1`, watchdog scheduled task, restart budget / cooldown, health diagnostics, runtime state file, log retention.

## 4. 가장 중요한 원칙

**모든 장애를 restart로 해결하려고 하지 않는다.** process dead / service unhealthy / Docker daemon down / PostgreSQL unhealthy / Garmin authentication failure / Garmin rate limit / Garmin upstream failure는 서로 다른 문제이며 원인을 구분해야 한다.

## 5. 이번 Phase에서 하지 않는 것

Spring code 기반 watchdog, Redis / distributed lock, external monitoring SaaS, Prometheus/Grafana, email/SMS/Slack notification, Windows Service 전환, NSSM, WinSW, systemd, Raspberry Pi 배포, Garmin automatic re-login, credential refresh automation, historical backfill.

## 6–8. 프로젝트 규칙 / Git / Work Order

CLAUDE.md, running-ai-dev, running-ai-integration. DB migration 없음. `git pull --ff-only origin main`, latest에 `8959f6b` 포함 확인, local 변경 덮어쓰지 않음. 지시서 원문과 결과 문서를 `docs/work-orders/`에 저장.

## 9. 새로운 script

`scripts/windows/watch-running-ai.ps1`. 기존 4A helper / repo-root / runtime logic 재사용, process detection과 health logic을 복붙으로 이중 구현하지 않는다.

## 10–12. One-shot watchdog

한 번 실행: 상태 확인 → 장애 분류 → 복구 가능한 경우에만 복구 → restart budget 확인 → 결과 기록 → 종료. 장시간 daemon 금지. Scheduled Task가 주기적으로 실행(watchdog crash 영향 최소, 업데이트 쉬움, 재부팅/로그인 구조 단순). 기본 5분, 1분 이하 과도한 감시 금지.

## 13–16. Watchdog Scheduled Task

`install-running-ai-watchdog-task.ps1`, `uninstall-running-ai-watchdog-task.ps1`, task name `RunningAI-Watchdog`. Trigger `AtLogOn` + 5분 반복, 현재 사용자 session, SYSTEM 전환 금지. 기존 `RunningAI-Startup`(전체 bootstrap)과 별도 task로 유지(watchdog은 정상 운영 중 장애 감지/복구). watchdog 자체 겹침 방지: `MultipleInstances = IgnoreNew` 우선, 필요하면 script level guard 최소 구현.

## 17. Component 상태 분류

UP, DOWN, UNHEALTHY, UNKNOWN, DEGRADED.

## 18–20. Docker

`docker info` 성공 → UP, CLI 없음 → DOWN / configuration error, daemon unreachable → DOWN. Docker DOWN이면 `docker desktop start` 또는 4A의 안전한 fallback 재사용 후 제한된 시간 ready 대기. 매 tick 재시작 금지 — restart budget 적용.

## 21–23. PostgreSQL

기존 compose healthcheck 사용: healthy → UP, running + unhealthy → UNHEALTHY, not running → DOWN. 단순 stopped면 `docker compose up -d`로 복구 가능. **running + unhealthy는 무조건 restart하지 않고 먼저 진단을 남긴다.** 절대 금지: `docker compose down -v`, `docker volume rm`, `docker system prune` — 데이터에 destructive action 금지.

## 24–27. Garmin Connector

PID + process identity + port + `GET /health` 조합. 정상 = process valid + health success. 4A가 시작한 connector PID가 없거나 dead이고 `/health`도 실패 → recoverable(budget 허용 시 재시작). process는 살아 있으나 `/health` 실패 → 즉시 kill하지 않고 짧은 재확인 후에도 실패할 때만 제한적 restart 후보(foreign process 가능성 반드시 확인). 8765 port가 점유됐지만 process identity가 이 repository의 connector가 아니면 `FOREIGN_PROCESS`, 절대 kill/restart 금지, DEGRADED만 기록.

## 28–31. Spring

PID + process identity + `GET /actuator/health`. dead + health 실패 → recoverable, 단 Docker UP / PostgreSQL HEALTHY / Connector UP이 먼저 충족되어야 한다. process alive + health 실패 → 즉시 restart 금지(첫 실패 → 재확인, persistent → restart budget 확인 → graceful restart). 항상 Docker → PostgreSQL → Connector → Spring 순으로 확인(dependency-aware).

## 32. 기존 startup script 재사용

복구 시 idempotent한 `start-running-ai.ps1` 재사용을 우선 검토(정상 process를 건드리지 않는지 확인).

## 33–38. Restart storm 방지

Restart budget 필수. 기본 window 10분, component당 최대 3회. 초과 시 자동 restart 중단, DEGRADED, reason `RESTART_BUDGET_EXCEEDED`. 상태 저장 `.runtime/watchdog-state.json`(docker / connector / spring 별 restartTimestamps, gitignore). 쓰기는 temp file → replace(atomic). 매 tick window보다 오래된 timestamp 제거(무한 증가 금지). window 밖으로 나가면 다시 restart 가능(영구 lockout 금지).

## 39–43. Garmin 오류는 process 장애가 아니다

`GARMIN_AUTH_REQUIRED`, `GARMIN_FORBIDDEN`, `GARMIN_RATE_LIMITED`, `GARMIN_UPSTREAM_ERROR`는 connector restart 대상이 아니다(process health가 정상인 경우). watchdog 자체는 Garmin activity fetch를 하지 않으며, Spring sync status나 최근 runtime log에 명백한 상태가 있을 때만 참고한다. **`POST /api/v1/garmin/sync`를 호출하지 않는다.** watchdog은 infrastructure health만 관리한다.

## 44–47. Diagnostics / log

`.runtime/watchdog-status.json`(checkedAt, overall, docker, postgres, garminConnector, spring, lastAction). `status-running-ai.ps1`이 이를 읽어 Watchdog / LastCheck / LastAction 표시 가능(Garmin fetch는 여전히 금지). `.runtime/logs/watchdog.log`: timestamp, component, state, reason, action, result. 예: `2026-09-30T01:00:00Z component=spring state=DOWN action=RESTART result=SUCCESS`. 금지: activity payload, token, email, activity ID.

## 48–52. Log retention

4A에는 rotation이 없었다. 복잡한 framework 없이 최소 retention: 파일 크기 > 10 MB → timestamp suffix로 rename 후 새 file, 14일 초과 rotated log 삭제. 삭제 범위는 오직 `.runtime/logs` 내 RunningAI 로그(spring.out/err, garmin-connector.out/err, watchdog).

## 53–55. Watchdog failure / DryRun / NoRecovery

unexpected exception → non-zero exit + 가능한 diagnostic log, 다른 component를 무조건 kill하지 않는다. `-DryRun`: health 검사, 장애 분류, 예정 action 표시까지만(실제 restart/start/stop 금지). 선택적으로 `-NoRecovery`.

## 56–70. Self-check (실제 Docker/프로세스를 destructive하게 조작하지 않음)

Healthy(action NONE, overall UP) / Connector dead(RESTART_CONNECTOR) / Spring dead(RESTART_SPRING) / Docker down(startup 시도, DryRun에서는 미실행) / PostgreSQL stopped(`docker compose up -d`) / PostgreSQL unhealthy(blind restart 금지, DEGRADED) / Foreign port owner(no kill, no restart, DEGRADED) / Restart budget(3 recent → no restart, RESTART_BUDGET_EXCEEDED) / Budget expiry / State file corruption(unsafe restart·foreign kill 금지, fail safe 또는 명확한 backup/reset 정책) / Atomic state(valid JSON) / Log retention(오래된 mock log만 삭제) / Repository boundary(legacy `C:\running-ai`의 PID/process/log/task 미접촉) / Garmin errors do not trigger restart(process health UP이면 action 없음).

## 71–75. Scheduled Task 세부

install script `-DryRun` 지원, 같은 이름이지만 다른 script를 가리키면 덮어쓰지 않음. Task name `RunningAI-Watchdog`, AtLogOn + 5분 반복, MultipleInstances IgnoreNew, 현재 사용자. user logoff 시 중지되어도 허용. uninstall은 자기 task만 삭제. 로그인 직후 `RunningAI-Startup`과 첫 tick이 겹칠 수 있으므로 watchdog initial delay 5분.

## 76–78. Recovery 우선순위 / fail-fast

여러 component가 down이면 Docker → PostgreSQL → Connector → Spring 순. 한 번에 무리하게 모두 restart하지 않고, 각 health가 실제 정상인지 확인 후 다음으로 진행. Docker 복구 실패 시 DB/Connector/Spring 복구 시도 안 함, PostgreSQL 복구 실패 시 Connector/Spring 복구 시도 안 함.

## 79–82. 책임 분리 / status 개선

Garmin scheduler = activity sync, Windows watchdog = runtime process health (섞지 않는다). `status-running-ai.ps1`에 Watchdog / LastWatchdogCheck / LastRecoveryAction / RestartBudget 추가(출력 복잡도 주의). Scheduler 표시는 현재 shell 환경변수 기준임을 명확히(`SchedulerConfig(source=current environment)` 또는 UNKNOWN), 거짓 정밀도 금지.

## 83–85. Spring API 추가 금지 / Java / Python

scheduler 상태 확인을 위한 Spring API 추가 금지. Spring 코드 변경 없으면 `cd server; .\gradlew.bat clean test` 128 전부 PASS. connector code 변경 금지. 외부 PC에서 비파괴 PowerShell self-check 충분히 실행.

## 86–88. Live validation / 메인 PC 체크리스트

외부 PC: `WATCHDOG_LIVE_VALIDATION_NOT_RUN` (이유: main runtime PC unavailable). 결과 문서에 메인 PC 후속 검증 체크리스트 포함: 1 latest main pull 2 3C-4A startup validation 3 startup task install 4 watchdog task install 5 runtime healthy 확인 6 connector process 수동 종료 7 watchdog 자동 복구 확인 8 Spring process 수동 종료 9 watchdog 자동 복구 확인 10 restart budget 확인 11 DB/Docker 장애 시 안전 동작 확인 12 scheduler Garmin sync 확인 13 로그 rotation 확인 14 logout/login 후 startup + watchdog 확인. 메인 PC 검증에서도 PostgreSQL volume 삭제 금지, 필요하지 않으면 Docker 강제 kill 테스트 하지 않는다.

## 89–92. Security / gitignore / README

commit 금지: Garmin token, email, password, MFA, cookie, real DB password, raw activity JSON, GPS, real IDs, runtime PID / logs / watchdog state. `.runtime/`, `*.pid`, `watchdog-state.json`, `watchdog-status.json`, runtime logs 미추적 확인. README Windows operations에 Startup task / Watchdog task / Manual status / Dry-run watchdog / Restart budget / Log retention. 예: `.\scripts\windows\watch-running-ai.ps1 -DryRun`, `install-running-ai-watchdog-task.ps1 -DryRun`, `install-running-ai-watchdog-task.ps1`, `uninstall-running-ai-watchdog-task.ps1`.

## 93. Definition of Done

watch-running-ai.ps1, one-shot, dependency-aware detection, Docker recovery, PostgreSQL stopped recovery, PostgreSQL unhealthy safe handling, connector dead recovery, connector foreign process protection, Spring dead recovery, process-alive health failure handling, restart budget·window, restart state persistence, atomic state write, watchdog status file, watchdog log, log retention, DryRun, no Garmin fetch, no POST /sync, auth/rate-limit not treated as process failure, watchdog scheduled task (IgnoreNew, initial delay, uninstall), startup task unchanged, legacy environment untouched, PowerShell self-check PASS, Java 128+ PASS, no DB migration, no Python change, README, main-PC validation checklist, secrets scan, diff review, commit, push.

## 94. 권장 commit

`feat: add Windows runtime watchdog`

## 95. 완료 보고 형식

Watchdog (interval, initial delay, execution model, overlap policy) / Detection (Docker, PostgreSQL, Garmin connector, Spring, foreign process) / Recovery (Docker, PostgreSQL stopped, PostgreSQL unhealthy, connector, Spring) / Restart protection (window, max restarts, state file, budget exceeded behavior) / Diagnostics (watchdog status, watchdog log, status script changes) / Log retention (rotation, max size, retention, scope) / Scheduled Task (name, trigger, repeat, multiple instances, install, uninstall) / Tests (Java total·passed·failed, PowerShell self-check, Python changed) / Database (migration NO, schema change NO) / Live validation (attempted: NO, reason: main runtime PC unavailable) / Security (secrets scan, runtime artifacts ignored, legacy protection) / Git / Remaining limitations / Next Phase (자동 시작 안 함; 후보 Phase 3C-4C Raspberry Pi deployment preparation 또는 Main-PC live validation / hardening).

## 96. 핵심 invariant

```text
RunningAI-Startup  → runtime bootstrap
RunningAI-Watchdog → health detection → safe recovery
```

watchdog은 프로세스가 죽었을 때는 복구, Garmin 인증 문제는 재시작하지 않음, 429는 재시작하지 않음, foreign process는 건드리지 않음, restart storm은 차단한다.
**Watchdog은 서비스 장애를 숨기는 도구가 아니라, 안전하게 복구 가능한 장애만 처리하는 도구다.**
