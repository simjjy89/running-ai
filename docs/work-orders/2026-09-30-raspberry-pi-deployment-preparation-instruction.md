> 원본 작업지시서 (2026-09-30, Phase 3C-4C). 구현 기록은 `2026-09-30-raspberry-pi-deployment-preparation.md` 참고.
> 추가 사용자 지시: Raspberry Pi/Linux 배포 준비만 / Windows watchdog을 Bash로 포팅하지 말고 systemd supervision 우선 / Raspberry Pi와 메인 runtime PC 접근 불가 → live validation 금지 / Windows runtime scripts 삭제·손상 금지 / 구현 → Linux artifact 정적 검증 → Java 전체 regression → README/결과 문서 → secrets 검사 → diff review → commit → push / 다음 Phase 시작 금지 / 결과 보고는 한글.

# RunningAI Phase 3C-4C
## Raspberry Pi Deployment Preparation / Linux Runtime / systemd

## 1. 현재 상태

완료: Phase 1 Spring Boot foundation, 2 PostgreSQL / Flyway, 3A Garmin ingestion, 3B Real Garmin E2E, 3C-1 Incremental sync, 3C-2 Manual sync API / status, 3C-3 Automatic scheduler, 3C-4A Windows runtime orchestration, 3C-4B Windows watchdog / recovery.
latest known commit `bd782a1`, Java regression baseline 128 / 128. Windows runtime live validation은 아직 PENDING (메인 PC 접근 가능해지면 3C-4A/4B를 한 번에 검증).

## 2. 이번 Phase 목표

실제 Raspberry Pi가 없어도 RunningAI를 Raspberry Pi / Linux로 옮길 수 있도록 배포 구조와 artifact를 준비한다. 결과물: Linux runtime design, systemd unit files, Linux setup scripts, Linux status/start/stop helper, configuration layout, deployment directory convention, migration checklist, non-destructive validation.

## 3. 중요한 원칙

Windows 구현(Task Scheduler, PowerShell PID management, custom watchdog, restart budget)을 Bash로 그대로 번역하지 않는다. Linux에서는 가능한 한 **systemd** 기본 기능을 사용한다.

## 4. 목표 Linux 구조

`PostgreSQL → running-ai-garmin-connector.service → running-ai.service`. process supervision은 systemd, Garmin activity sync 주기는 기존 Spring scheduler.

## 5. 이번 Phase에서 하지 않는 것

실제 Raspberry Pi 접속·설치, 실제 Garmin login, 실제 Garmin token 이동, 실제 production PostgreSQL migration, Windows runtime 제거, Windows PowerShell scripts 삭제, Docker image 제작, Kubernetes, Prometheus/Grafana, external API authentication, ChatGPT connector, Intervals.icu integration.

## 6–8. 프로젝트 규칙 / Git / Work Order

CLAUDE.md, running-ai-dev, running-ai-integration. DB schema 변경 없음. `git pull --ff-only origin main`, latest에 `bd782a1` 포함 확인, local 변경 덮어쓰지 않음. 지시서 원문과 결과 문서를 `docs/work-orders/`에 저장.

## 9–10. 목표 OS / 런타임

Raspberry Pi OS 64-bit 계열(특정 minor release에 의존하지 않음), arm64/aarch64. Java 21, Python 3.12+, PostgreSQL, systemd. 현재 connector의 Python 3.12 compatibility 유지.

## 11–12. Docker 정책 / 권장 방향

PostgreSQL 운영방식을 강제 확정하지 않고 두 선택지를 문서화: Option A native PostgreSQL(단순, Docker daemon 불필요, resource 절감, 부팅 의존성 단순), Option B Docker PostgreSQL(현재 compose 재사용, 환경 일치). 하나를 production으로 확정하지 않아도 된다. 개인 Pi 전제의 권장안: PostgreSQL native + Garmin Connector systemd + Spring Boot systemd, 기존 Docker Compose는 fallback으로 유지.

## 13–14. Deployment directory / source와 runtime 분리

`/opt/running-ai/{app,connector,scripts}`, `/var/lib/running-ai/{data,runtime}`, `/var/log/running-ai/`, `/etc/running-ai/{running-ai.env,garmin-connector.env}`. 실제 설치 root는 설정 가능. 개발 Git repo(`~/running-ai`)와 production runtime(`/opt/running-ai`)을 분리하고 deployment script가 build artifact를 복사하도록 설계(이번 Phase에서 실제 복사는 하지 않아도 됨).

## 15–18. Linux 사용자 / Secrets / Token

전용 계정(예 `runningai`), root로 실행하지 않음, user/group/directory ownership 문서화. Garmin token store, DB password, local environment files는 service user만 읽을 수 있어야 한다(파일 0600, directory 0700 또는 최소 권한). Token store는 repo 밖(user home 또는 전용 data directory)이며 `/opt/running-ai/app`, Git repo, committed `.env`에 넣지 않는다. 실제 token 복사는 하지 않고 결과 문서에 두 방법 기록: (A) Pi에서 새 login, (B) 기존 token store를 안전하게 수동 이전.

## 19–24. Connector systemd unit

`deploy/linux/systemd/running-ai-garmin-connector.service`. systemd가 start/stop/restart on failure/restart storm prevention/logging/dependency를 담당하고 Windows PID file/watchdog 로직은 재구현하지 않는다. ExecStart는 connector CLI의 실제 command를 source에서 확인(추측 금지): `<venv-python> ... serve`, bind 127.0.0.1만. `User=runningai`/`Group=runningai`. `Restart=on-failure`, `RestartSec=30`, `StartLimitIntervalSec=600`, `StartLimitBurst=3`(directive 위치/semantics 확인). Garmin 401/403/429는 process failure가 아니므로 HTTP server가 정상이면 process는 계속 살아 있어야 하고 systemd restart가 발생하면 안 된다(현재 connector의 exit behavior 확인).

## 25–34. Spring systemd unit

`deploy/linux/systemd/running-ai.service`. production은 `java -jar`(gradlew bootRun 금지). jar 위치 예 `/opt/running-ai/app/running-ai.jar`, artifact name(version 포함)은 deployment script가 처리. `java` 경로는 systemd PATH가 사용자 shell과 같다고 가정하지 않고 install 단계에서 `command -v java`로 확인·문서화. `EnvironmentFile=/etc/running-ai/running-ai.env`(SPRING_PROFILES_ACTIVE, DB_*, GARMIN_CONNECTOR_URL=http://127.0.0.1:8765, RUNNING_AI_GARMIN_SCHEDULER_ENABLED/FIXED_DELAY/INITIAL_DELAY). 실제 secret 값은 sample에 넣지 않는다. `deploy/linux/env/running-ai.env.example`, `garmin-connector.env.example`. Spring은 connector에 의존하나 `After=`만으로 health가 보장된다고 가정하지 않음(`After=network-online.target`, `After=running-ai-garmin-connector.service`, `Wants=network-online.target`, Requires/Wants 관계 검토). native PostgreSQL 시 `After=postgresql.service`를 고려하되 배포판 특정 이름에 지나치게 묶지 않는다. `After=`는 process start 순서이지 application healthy가 아님을 문서화하고 Spring DB retry/fail-fast 동작을 확인. 필요하면 `ExecStartPre=`로 connector `/health` 검사를 검토하되 불필요하면 도입하지 않는다(최소 설계).

## 35–38. Restart / Graceful stop

Spring: `Restart=on-failure`, `RestartSec=30`, `StartLimitIntervalSec=600`, `StartLimitBurst=3`(Windows restart budget과 같은 목적을 systemd가 담당). SIGTERM graceful stop(별도 Ctrl+C emulation 불필요), connector uvicorn도 SIGTERM. 불필요한 `KillSignal=SIGKILL` 금지, `TimeoutStopSec`(예 30) 합리적 값.

## 39–41. Logging

journald 기본. README에 `journalctl -u running-ai`, `journalctl -u running-ai-garmin-connector`. 추가 log rotation framework 금지, 기존 application logging이 파일 기반이면 조사하고 중복 저장 회피.

## 42–47. Linux scripts

`deploy/linux/scripts/`: `install-runtime.sh`(destructive package install 자동 수행 불필요; dependency check, directory creation, permission guidance, systemd template installation 중 비파괴 부분), `deploy-app.sh`(bootJar build, artifact 확인, target 복사, service restart, health 확인; 실제 Pi가 없으므로 dry-run/source 검증 가능해야 함), `status-running-ai.sh`, `validate-runtime.sh`. root 필요 작업과 일반 user 작업을 구분(전체를 root로 강제하지 않음). jar 교체는 temporary file → rename(불완전한 jar가 production filename에 노출되지 않게), 이전 jar를 `running-ai.jar.previous`로 하나 보관하는 간단한 전략은 검토 가능(release manager 금지).

## 48–52. status / validate

status: PostgreSQL, GarminConnector, Spring, Garmin Sync State — `systemctl is-active`, connector `GET /health`, Spring `GET /actuator/health`, `GET /api/v1/garmin/sync/status`(Garmin activity fetch 금지). validate: 실제 process를 시작하지 않고 Java version, Python version, systemd availability, required files, venv location, jar existence, config file existence, directory permission, service unit syntax를 검사(`systemd-analyze verify` 가능하면, 아니면 정적 검증 fallback). 외부 PC가 Linux가 아니라 실행 불가하면 `LINUX_RUNTIME_LIVE_VALIDATION_NOT_RUN`으로 기록(실패 아님).

## 53–55. Docker Compose / Windows runtime 보존, Platform separation

root `docker-compose.yml`은 Windows/local 개발용으로 유지, `scripts/windows/*`는 수정하지 않는다(공통 문서 정도만 가능). `scripts/windows/`와 `deploy/linux/{systemd,scripts,env}`로 플랫폼별 책임을 섞지 않는다.

## 56–58. Architecture / Python dependency

Spring Boot jar는 JVM bytecode이므로 arm64에서도 동일 jar 사용 가능, Python package는 Pi에서 별도 venv/install 필요 — 차이 문서화. connector directory의 기존 dependency definition을 그대로 사용(`python3.12 -m venv`, `pip install`), `garminconnect==0.3.16` 등 기존 pinning 변경 금지(dependency upgrade Phase 아님).

## 59–62. PostgreSQL migration

Pi 최초 실행 시 Flyway V1~V4가 빈 DB에 적용되는 구조 유지, 기존 migration 수정 금지. 실제 Windows DB 이전은 수행하지 않고 migration checklist에 선택지 정리: Option A fresh DB(Flyway 생성 → Garmin incremental/bootstrap), Option B pg_dump/pg_restore — 아직 결정하지 않는다. fresh start 시 `garmin_sync_state`도 초기화되어 bootstrap window부터 다시 처리되므로 historical coverage 차이를 문서화.

## 63–65. Network exposure / 외부 접근 / Firewall

Connector 127.0.0.1 only 유지. Spring도 Pi에서 LAN 노출을 자동 활성화하지 않는다 — 현재 bind 설정을 조사. 향후 ChatGPT connector/remote access를 위한 노출도 이번 Phase에서는 하지 않으며 인증 없는 `POST /api/v1/garmin/sync`는 외부 노출 금지. 방화벽 rule 자동 추가 금지, 문서에 `do not expose unauthenticated API publicly` 명시.

## 66–71. Boot behavior / systemctl 명령

부팅 시 network-online → PostgreSQL → Garmin Connector → Spring Boot, systemd enable로 자동 기동. README에 enable / restart / stop / status 명령(실제 unit name과 일치).

## 72–76. Health failure vs process failure / Linux watchdog / systemd watchdog / scheduler ownership

`Restart=on-failure`는 process exit만 복구하고 process가 살아 있으나 HTTP DOWN인 경우는 감지 못할 수 있음 — limitation으로 기록. Windows custom watchdog을 포팅하지 않고 첫 Pi 배포는 systemd restart policy + manual status까지만. `WatchdogSec` 등 notify protocol 필요한 기능은 도입하지 않는다. Spring scheduler(`RUNNING_AI_GARMIN_SCHEDULER_ENABLED=true`)가 activity synchronization, systemd가 process lifecycle을 담당(OS timer로 sync를 중복 실행하지 않는다).

## 77–80. Timezone / Clock / Storage / Backups

DB time은 계속 UTC/Instant, Pi OS timezone이 persistence semantics를 바꾸면 안 됨. Pi system clock 정확성(NTP/time synchronization enabled)을 checklist에 포함(별도 NTP 구현 금지). SD card write량/SSD 여부를 deployment note로 기록. PostgreSQL backup automation은 scope 밖이지만 Pi 운영 전 필수 후속 과제로 기록.

## 81–84. Configuration validation / hardening / WorkingDirectory / Umask

service start 전에 missing config가 명확히 실패해야 하며 credential 값은 출력하지 않는다(Environment file missing, Garmin token store missing, Java unavailable, Python venv missing). `NoNewPrivileges=true`, `PrivateTmp=true` 등은 필요한 파일 접근과 호환되는 것을 확인한 뒤에만(추측 금지). 각 service에 `WorkingDirectory` 명시. token/log/config 보호를 위해 `UMask=0077`이 기존 runtime requirements와 충돌하지 않을 때 사용.

## 85–86. README / Pi migration checklist

README 신규 섹션 "Raspberry Pi / Linux Deployment": requirements, directory layout, service install, env configuration, connector setup, Spring deployment, start/stop/status, logs, update procedure, migration checklist, security notes. Checklist: Raspberry Pi OS 64-bit, network, time sync, Java 21, Python 3.12+, PostgreSQL option, runningai user, directories, connector venv, token/login, Spring jar, env files, systemd units, Flyway startup, connector health, Spring health, manual sync, automatic scheduler, reboot validation, backup plan.

## 87–90. Validation

Windows scripts unchanged, Linux files에 secrets 없음, Windows 절대경로 없음, user-specific Linux home path 없음. 가능하면 `bash -n`(bash 없으면 다른 static method 또는 미검증 명시). scripts는 `set -euo pipefail` 검토(expected non-zero health check와 충돌 주의). shellcheck는 이미 있으면 사용, 새 global dependency 강제 설치 금지.

## 91–96. Java / Python / Database / Secrets / Live validation

`cd server; .\gradlew.bat clean test` 128/128+ PASS. connector source 변경 없음(pytest 생략 가능). migration NO, schema change NO, V1~V4 수정 금지. Linux env examples/unit files에서 password/token/email/MFA/cookie/real DB credential 실값 금지. `RASPBERRY_PI_LIVE_VALIDATION_NOT_RUN`(No Raspberry Pi runtime available in current environment). `WINDOWS_RUNTIME_LIVE_VALIDATION=PENDING`, `WATCHDOG_LIVE_VALIDATION=PENDING` 유지.

## 97. Definition of Done

Linux deployment architecture documented, native PostgreSQL vs Docker decision note, Linux directory convention, service user convention, running-ai-garmin-connector.service, running-ai.service, systemd dependency ordering, restart policy, restart storm protection, graceful SIGTERM, env example files, no credentials, install/deploy/status/validate helper scripts, systemd/journald logging documented, no Windows watchdog port, Windows scripts retained, connector 127.0.0.1 invariant, Spring unauthenticated API not publicly exposed, Flyway V1~V4 unchanged, database migration strategy documented, Raspberry Pi migration checklist, bash/static validations PASS where available, Java regression PASS, README updated, secrets scan clean, diff review, commit, push.

## 98. 권장 commit

`feat: prepare Raspberry Pi deployment`

## 99. 완료 보고 형식

Target Runtime (OS, architecture, Java, Python, PostgreSQL recommendation) / Linux Layout (application, connector, config, runtime, logs) / systemd (connector unit, Spring unit, dependency order, restart policy, restart limit, graceful shutdown) / Configuration (Spring env, connector env, Garmin token store, permissions) / Deployment (install, deploy, status, validation script) / Database (recommended Pi option, Flyway, existing Windows data migration) / Logging (system, commands, rotation) / Tests (Java total·passed·failed, Linux script validation, Python changed) / Live Validation (Raspberry Pi, Windows runtime, Windows watchdog) / Security (secrets scan, service user, connector bind, external API) / Git / Remaining limitations (Pi real hardware validation pending, DB backup automation 없음, process-alive-but-unhealthy 자동 복구 없음, external API authentication 없음, real production migration 미수행) / Next Phase(자동 시작하지 않는다; 후보 Main-PC Windows live validation, Raspberry Pi live deployment, Phase 4 training state/load model).

## 100. 핵심 invariant

```text
Raspberry Pi Boot → systemd → PostgreSQL → Garmin Connector → Spring Boot → Spring Garmin Scheduler
```

systemd = process lifecycle / restart, Spring = Garmin activity scheduling / business logic, PostgreSQL = persistent state, Garmin Connector = Garmin auth/read transport only.
**Windows watchdog을 Linux에 재구현하지 않는다. systemd가 제공하는 supervision 기능을 우선 사용한다. Garmin credential boundary를 깨지 않는다. 현재 Windows 운영 환경은 삭제하거나 변경하지 않는다.**
