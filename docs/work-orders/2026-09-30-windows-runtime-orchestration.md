# 2026-09-30 — Phase 3C-4A: Windows Runtime Orchestration

| 항목 | 내용 |
|------|------|
| 날짜 | 2026-09-30 |
| 작업 | PowerShell 기반 start / stop / status + AtLogOn Scheduled Task |
| 상태 | 완료 (외부 PC, 비파괴 검증까지) |
| 커밋 | `feat: add Windows runtime orchestration` |
| 지시서 원문 | [2026-09-30-windows-runtime-orchestration-instruction.md](2026-09-30-windows-runtime-orchestration-instruction.md) |
| 이전 작업 | [2026-09-30-garmin-sync-scheduler.md](2026-09-30-garmin-sync-scheduler.md) |

## 1. 범위

Spring / connector Java·Python 코드, DB, migration 변경 없음. 추가된 것은 `scripts/windows/`와 `.gitignore` 두 줄뿐이다.
Windows Service, watchdog, 자동 재시작, log rotation, 컨테이너화는 범위 밖(3C-4B 이후).

```text
scripts/windows/
  RunningAI.Common.ps1                       공통 helper (dot-source)
  start-running-ai.ps1                       기동
  stop-running-ai.ps1                        종료
  status-running-ai.ps1                      상태 (읽기 전용)
  install-running-ai-scheduled-task.ps1      AtLogOn task 등록 (-DryRun 지원)
  uninstall-running-ai-scheduled-task.ps1    task 제거
  Send-CtrlC.ps1                             graceful 종료용 내부 helper
  tests/Test-RunningAI.ps1                   비파괴 self-check (15개)
```

## 2. Startup (`start-running-ai.ps1`)

의존 순서 고정: **Docker → PostgreSQL healthy → Garmin connector `/health` → Spring `/actuator/health`**. 앞 단계가 실패하면 뒤 단계는 시작하지 않는다.

| 단계 | 동작 | timeout |
|------|------|---------|
| Docker | `docker info`로 확인, 꺼져 있으면 `docker desktop start`(없으면 `Program Files\Docker\Docker\Docker Desktop.exe` 존재 확인 후 실행), 4초 간격 poll | 120s |
| PostgreSQL | 기존 `docker-compose.yml`로 `docker compose up -d`, compose healthcheck가 `healthy`가 될 때까지 (단순 running 아님) | 120s |
| Connector | `/health` UP이면 새로 시작하지 않음. port만 점유되고 health 실패면 오류(기존 process kill 안 함). 아니면 `.venv` python + `import garminconnect, fastapi, uvicorn` precheck 후 `python -m garmin_connector serve --port N` (127.0.0.1 고정). token store는 **존재 여부만** 검사(없으면 경고 후 기동, 내용은 읽지 않음), login은 하지 않음 | 30s, 1s poll, process 조기 종료 감지 |
| Java | `JAVA_HOME` → PATH의 java 순으로 **java.version == 21** 확인(사용자 경로 하드코딩 없음, 못 찾으면 실패) | - |
| Spring | 이미 UP이면 재시작 안 함. jar가 없거나 `-Build`면 `gradlew bootJar`(로그 `.runtime/logs/gradle-bootjar.log`), 이후 `java -jar server/build/libs/running-ai-server-*.jar`(plain jar 제외, 최신 것). 작업 디렉터리 `server/`(local profile의 `../.env` import 유지). `GARMIN_CONNECTOR_URL`이 없으면 `http://127.0.0.1:<connectorPort>`로 설정. scheduler는 강제로 켜지 않고 `RUNNING_AI_GARMIN_SCHEDULER_ENABLED` 환경을 그대로 존중 | 120s, process 조기 종료 감지 |

- repo root는 `$PSScriptRoot`에서 계산. Spring port는 `-SpringPort` 또는 `SERVER_PORT`, 기본 8080(설정 기본값). connector port는 `-ConnectorPort`, 기본 8765.
- **Partial failure**: Spring이 실패하면 이번 실행에서 새로 시작한 connector만 정리(이미 떠 있던 connector·DB는 유지). 이번 실행이 시작한 Spring도 timeout 시 정리.
- **Exit code**: 0 성공 / 10 Docker / 11 PostgreSQL / 12 connector / 13 Java·build / 14 Spring / 1 기타. Scheduled Task 결과로 실패 layer를 알 수 있다.
- 멱등: 반복 실행해도 이미 살아 있는 구성 요소는 그대로 둔다.

## 3. Process management

- `.runtime/garmin-connector.pid`, `.runtime/spring.pid` (start가 직접 띄운 process만 기록), 로그 `.runtime/logs/{garmin-connector,spring}.{out,err}.log`, `gradle-bootjar.log`. 모두 git ignore(`.runtime/`, `*.pid`).
- **PID identity**: PID 파일이 있어도 live process command line이 `garmin_connector`+` serve`+repo root (connector) / `running-ai-server`+`-jar`+repo root (Spring)를 모두 포함할 때만 "우리 것"으로 취급. 아니면 stale로 보고 PID 파일만 삭제(process는 건드리지 않음). repo root를 포함하므로 legacy `C:\running-ai` 운영 프로젝트나 재사용된 PID는 절대 stop 대상이 되지 않는다.
- 스크립트 로그에는 component 상태, port, PID, 소요 시간, 종료 사유만 남긴다(token, password, activity 데이터, ID, 이름, GPS 없음).

## 4. Shutdown (`stop-running-ai.ps1`)

순서 Spring → connector, PostgreSQL은 기본 유지(`-StopDatabase`일 때만 `docker compose stop`; `down`·`-v` 절대 없음, 테스트로 코드 검증).
**Graceful 방식**: Windows에는 SIGTERM이 없으므로 대상 console에 **Ctrl+C**를 전달한다(`Send-CtrlC.ps1`: 별도 짧은 process에서 `AttachConsole` + `GenerateConsoleCtrlEvent`, 호출자 console은 보존). JVM은 shutdown hook(Spring graceful), uvicorn은 정상 shutdown 수행. timeout(Spring 30s, connector 15s) 후에만 `Stop-Process -Force` fallback, 결과는 `graceful`/`forced`/`not-running`으로 출력.
발견 및 수정한 함정: 부모 process가 "Ctrl+C 무시" 플래그를 가지고 있으면(일부 launcher) 자식 JVM/python이 이를 상속해 Ctrl+C에 반응하지 않았다 → 자식을 띄우기 전 `Enable-CtrlCInheritance`로 해제.

## 5. Scheduled Task

| 항목 | 값 |
|------|-----|
| 이름 | `RunningAI-Startup` |
| Trigger | AtLogOn (현재 사용자) |
| 계정 | 현재 로그인 사용자, Interactive, Limited, password 저장 없음 |
| Action | `powershell.exe -NoProfile -ExecutionPolicy Bypass -WindowStyle Hidden -File <repo>\scripts\windows\start-running-ai.ps1`, working directory = repo root (VBS wrapper 없음) |
| Settings | 배터리 허용, 최대 1시간, 중복 실행 무시, 놓친 실행은 가능할 때 실행 |
| install | 멱등(`-Force` re-register). 같은 이름의 task가 `start-running-ai.ps1`을 가리키지 않으면(legacy) 덮어쓰지 않고 거부. `-DryRun`은 정의만 출력 |
| uninstall | `RunningAI-Startup`만 제거, 다른 task·legacy 무관, 실행 중 process는 건드리지 않음 |

## 6. Status (`status-running-ai.ps1`)

읽기 전용(connector `/health`, Spring `/actuator/health`, `GET /api/v1/garmin/sync/status`, `docker info/inspect`만 사용; Garmin login·fetch·token 접근 없음). 예:

```text
Docker           DOWN
PostgreSQL       UNKNOWN
GarminConnector  DOWN 127.0.0.1:8765
Spring           DOWN port 8080
Scheduler        UNKNOWN
LastSync         UNKNOWN (Spring is down)
```

전부 UP이면 exit 0, 아니면 1. Scheduler 표시는 이 shell의 `RUNNING_AI_GARMIN_SCHEDULER_ENABLED`를 기준으로 하며(스케줄러 상태 API가 없음) Spring이 죽어 있으면 UNKNOWN.

## 7. 검증 결과 (외부 PC, 비파괴)

```text
Java: clean test → 128 total, 128 passed, 0 failed (Spring 코드 변경 없음)
Python connector: 변경 없음, 테스트 재실행 안 함
PowerShell: scripts/windows/tests/Test-RunningAI.ps1 → 15/15 PASS
```

Self-check 내용: 전체 스크립트 parse, repo root, PID 파일 round trip / 깨진 PID 파일, process identity(모든 marker 필요, 타 process는 connector/Spring으로 인식 안 됨), stale PID 정리, 비추적 process stop 무동작, `Wait-Until` timeout·abort, 닫힌 port health false, **actuator vendor content-type(`application/vnd.spring-boot.actuator.v3+json`) 파싱**, `Quote-Argument`, installer `-DryRun`(등록 수 변화 없음, 이 repo 경로 사용), user/drive 경로·credential 하드코딩 없음, runtime 산출물 git ignore, stop script에 `compose down`/`-v` 없음.

추가 실험(임시 디렉터리, repo 미포함): 실제 Spring jar(H2 test profile, 임시 port)와 connector(임시 port)를 start와 동일한 방식(Hidden, 로그 redirect)으로 띄우고 `Stop-TrackedProcess`로 종료 → 두 process 모두 **graceful**(Spring `HikariPool Shutdown completed`, uvicorn `Application shutdown complete`). `Invoke-NativeQuiet`의 `python -c "import …"` 인용과 `java.version`/`java.home` 파싱도 실측 확인.
이 실험에서 발견한 버그 2건을 수정했다: (1) Windows PowerShell 5.1은 비-text content type 응답의 `.Content`를 byte[]로 돌려주어 actuator health가 항상 false가 됨 → raw stream을 직접 UTF-8 디코딩, (2) 위의 Ctrl+C 상속 문제.
이 PC에서 status는 전부 DOWN/UNKNOWN(exit 1), start는 Docker CLI가 없어 exit 10으로 즉시 실패, stop은 추적 대상 없음(exit 0)으로 기대대로 동작했다. Docker, Scheduled Task, Garmin login은 건드리지 않았다.

## 8. Live validation

```text
WINDOWS_RUNTIME_LIVE_VALIDATION_NOT_RUN
Reason: main Garmin runtime environment is on another PC
```

## 9. Main PC 검증 체크리스트

1. `git pull --ff-only origin main`
2. Docker Desktop 실행 가능 여부 확인 (로그인 후 자동 시작 설정은 Docker Desktop 설정)
3. connector 준비: `tools\garmin-connector\.venv` 생성·`pip install -r requirements.txt`, token store 확인 (`python -m garmin_connector status`, 내용은 열지 않음)
4. DB 접속 정보: OS 환경변수 또는 repo root `.env`(git ignore)의 `DB_*`/`POSTGRES_*`; 자동 sync를 원하면 `RUNNING_AI_GARMIN_SCHEDULER_ENABLED=true`
5. `scripts\windows\install-running-ai-scheduled-task.ps1 -DryRun` 확인 후 실제 install
6. `scripts\windows\start-running-ai.ps1` (exit 0 확인, 첫 실행은 jar build 포함)
7. `scripts\windows\status-running-ai.ps1` (전부 UP)
8. `POST /api/v1/garmin/sync`, `GET /api/v1/garmin/sync/status`
9. scheduler를 켠 경우 initial-delay 이후 `.runtime\logs\spring.out.log`에서 `Garmin scheduled sync completed` 확인
10. `stop-running-ai.ps1` → 로그에서 graceful 종료 확인 → 로그오프/재로그인(또는 재부팅) 후 자동기동 확인
11. 확인할 것: Task Scheduler가 script 종료 후에도 자식 process(connector, Spring)를 유지하는지, 로그인 직후 Docker Desktop 지연 시 120s 대기로 충분한지

## 10. Database / Git / 보안

migration 없음, schema 변경 없음. Garmin/DB credential을 스크립트나 repo에 저장하지 않으며 `.env`를 새로 만들도록 유도하지 않는다. `.runtime/`, `*.pid`는 ignore.

## 11. Limitations

- crash watchdog·자동 재시작 없음: connector/Spring이 죽으면 다음 로그인 또는 수동 start까지 그대로다.
- Windows Service가 아님: 사용자 로그온 및 Docker Desktop 사용자 session에 의존한다(부팅만으로는 시작되지 않음).
- log rotation 없음: `.runtime/logs`는 계속 커진다.
- Task Scheduler 하에서 start script 종료 후 자식 process 유지 여부는 이 PC에서 검증하지 못했다(9장 11번).
- `Send-CtrlC`는 대상이 자기 console을 가진 프로세스(Hidden으로 시작)일 때 동작한다. 수동으로 다른 방식으로 띄운 process는 forced fallback이 될 수 있다.
- Scheduler 활성 여부를 API로 확인할 수 없어 status는 shell 환경 기준이다. distributed lock·외부 인증 없음.

## 12. Next Phase (자동 시작 안 함)

Phase 3C-4B: connector/Spring watchdog, process failure detection, restart policy, health diagnostics, log retention, boot/restart reliability.
