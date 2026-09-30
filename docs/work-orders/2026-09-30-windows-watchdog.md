# 2026-09-30 — Phase 3C-4B: Windows Watchdog / Recovery / Diagnostics / Log Retention

| 항목 | 내용 |
|------|------|
| 날짜 | 2026-09-30 |
| 작업 | one-shot watchdog + 5분 Scheduled Task, restart budget, 진단 파일, log rotation/retention |
| 상태 | 완료 (외부 PC, 비파괴 검증까지) |
| 커밋 | `feat: add Windows runtime watchdog` |
| 지시서 원문 | [2026-09-30-windows-watchdog-instruction.md](2026-09-30-windows-watchdog-instruction.md) |
| 이전 작업 | [2026-09-30-windows-runtime-orchestration.md](2026-09-30-windows-runtime-orchestration.md) |

## 1. 범위와 구조

Spring/connector/Java/Python/DB 변경 없음. 추가·변경은 `scripts/windows/`뿐이다(Spring API 추가 없음, Garmin 호출 없음).

```text
RunningAI.Watchdog.ps1   로직 라이브러리: 관찰 → 분류 → 계획 → 실행 (각 층이 주입 가능해 단독 테스트)
watch-running-ai.ps1     one-shot 진입점 (-DryRun, -NoRecovery)
install/uninstall-running-ai-watchdog-task.ps1   RunningAI-Watchdog task
RunningAI.Common.ps1     (확장) log rotation·retention, exit code 20/21
start-running-ai.ps1     (변경) 시작 전 이전 로그를 rotate, 오래된 rotated log 정리
status-running-ai.ps1    (변경) watchdog 행 추가, Scheduler 행 relabel
tests/Test-Watchdog.ps1  비파괴 self-check 32개
```

Process 식별·health·PID·graceful stop은 4A의 `RunningAI.Common.ps1`을 그대로 재사용한다(복붙 없음). 복구 실행은 멱등한 `start-running-ai.ps1`을 그대로 호출한다(이미 정상인 것은 건드리지 않음).

## 2. Watchdog 동작 모델

| 항목 | 값 |
|------|-----|
| 실행 주기 | 5분 (Scheduled Task 반복) |
| 첫 실행 | 로그온 후 5분 지연 (`RunningAI-Startup` 완료 시간 확보) |
| 실행 모델 | one-shot: 관찰 → 분류 → 복구(가능할 때만) → budget 확인 → 기록 → 종료. daemon 아님 |
| 겹침 방지 | Task `MultipleInstances=IgnoreNew` + script 내부 named mutex(`Local\RunningAI-Watchdog-<repo hash>`) |
| 종료 코드 | 0 정상(저하 상태는 status 파일에 기록) / 20 watchdog 자체 오류 / 21 복구 시도 실패 |

Task 설정: 이름 `RunningAI-Watchdog`, AtLogOn(+5분 delay) + 5분 repetition, 현재 사용자·Interactive·Limited, 최대 30분, 배터리 허용. 같은 이름이지만 `watch-running-ai.ps1`을 가리키지 않는 task는 덮어쓰지 않는다. uninstall은 이 task만. `RunningAI-Startup`은 변경 없음(별도 task, 자체 검사로 분리 확인).

## 3. Detection (상태: UP / DOWN / UNHEALTHY / UNKNOWN / DEGRADED)

| Component | 판정 |
|-----------|------|
| Docker | `docker info` 성공 UP / CLI 없음 DOWN(`NO_CLI`, 설정 오류) / daemon 불가 DOWN(`DAEMON_UNREACHABLE`) |
| PostgreSQL | compose healthcheck: healthy UP / 실행 중 unhealthy UNHEALTHY / 컨테이너 없음·중지 DOWN / starting UNKNOWN / Docker 불가면 UNKNOWN |
| Connector | `/health` UP이면 UP. 아니고 PID 파일의 process가 살아 있고 command line이 이 repo의 connector면 UNHEALTHY. 그런 process가 없는데 port가 점유되면 **DEGRADED / FOREIGN_PROCESS**. 아니면 DOWN(`PROCESS_DEAD`) |
| Spring | `/actuator/health`로 위와 동일 |

process가 살아 있는데 health가 실패하면 `RecheckDelaySec`(기본 10초) 후 한 번 더 확인하고, 그래도 실패해야 UNHEALTHY로 본다(일시 장애 배제).

## 4. Recovery 규칙

계획은 Docker → PostgreSQL → connector → Spring 순으로 만들고, 실행은 **첫 action 하나만 실행 후 다시 관찰**하는 루프다(단계별 검증, 최대 6단계). 앞 단계 복구가 실패하거나 정상화되지 않으면 뒤 단계는 시도하지 않는다.

| 상황 | 동작 |
|------|------|
| Docker daemon DOWN | `START_DOCKER` (4A의 `docker desktop start`/실행 파일 fallback, 대기 포함) |
| Docker CLI 없음 | 복구 없음, `CONFIGURATION_ERROR_NO_DOCKER_CLI`, 하위 전부 `DEPENDENCY_NOT_READY` |
| PostgreSQL 중지 | `COMPOSE_UP` (`docker compose up -d`) |
| PostgreSQL running + unhealthy | **복구 안 함**(blind restart 금지), DEGRADED `POSTGRES_UNHEALTHY_NO_BLIND_RESTART`, 진단만 기록, 하위 component 차단 |
| PostgreSQL starting | 대기(다음 tick), 복구 안 함 |
| connector DOWN | `START_CONNECTOR` |
| connector UNHEALTHY(살아 있고 재확인 후에도 실패) | 이 repo의 PID임이 증명된 경우에만 graceful stop 후 `RESTART_CONNECTOR` |
| connector FOREIGN_PROCESS | **kill·restart 절대 없음**, DEGRADED만 기록 |
| Spring DOWN / UNHEALTHY / FOREIGN | connector와 동일 (`START_SPRING` / `RESTART_SPRING` / 기록만). connector까지 정상이어야 시도 |

**Garmin 문제는 process 장애가 아니다.** `GARMIN_AUTH_REQUIRED`, `FORBIDDEN`, `RATE_LIMITED`, `UPSTREAM_ERROR`는 process health가 UP이면 어떤 action도 만들지 않는다. watchdog은 Garmin을 호출하지 않으며(`POST /sync` 포함, 소스 검사로 확인) 최근 `spring.out.log`의 scheduler 결과 줄(`Garmin scheduled sync failed: reason=…`)만 읽어 `garminHint`로 status에 표시한다(2시간 이내, 이후 성공 줄이 있으면 해제, connector-unavailable은 process 문제이므로 제외). 이 hint가 있으면 overall은 DEGRADED로 보고되지만 restart는 하지 않는다.
금지 동작은 코드에 없다: `compose down`, `volume rm`, `system prune`, 이름 기반 kill(`Stop-Process -Name` 등), `taskkill`, connector login (self-check가 소스 검사로 강제).

## 5. Restart protection

| 항목 | 값 |
|------|-----|
| window / 최대 | 10분 / component당 3회 (`-WindowMinutes`, `-MaxRestarts`) |
| 대상 | docker, postgres, connector, spring 각각 |
| 상태 파일 | `.runtime/watchdog-state.json` — `{"version":1,"restarts":{"docker":[unix초…],…}}` |
| 기록 시점 | action을 실행하려는 순간(실패해도 기록) |
| 초과 시 | restart 안 함, `RESTART_BUDGET_EXCEEDED`, DEGRADED/DOWN 상태 보고, 하위 차단 |
| cooldown | window 밖으로 나간 기록은 매 tick 제거 → 자동으로 다시 시도 가능(영구 lockout 없음), 파일 무한 증가 없음 |
| atomic write | temp 파일에 쓰고 `File.Replace`/`Move`로 교체(부분 JSON 없음) |
| 손상 파일 | 파싱 실패·모양 불일치 → `watchdog-state.json.corrupt`로 격리, **그 tick은 복구를 하지 않고**(history 없이는 budget을 지킬 수 없음, fail safe) DEGRADED `STATE_UNAVAILABLE_FAIL_SAFE`; 다음 tick부터 새 빈 상태로 정상 동작 |

## 6. Diagnostics

- `.runtime/watchdog-status.json`: `checkedAt, overall, components{docker,postgres,connector,spring}, blocked[], garminHint, lastAction, restartBudget{windowMinutes,maxRestarts,used{…}}` (atomic).
- `.runtime/logs/watchdog.log`: `2026-09-30T03:12:25Z component=docker state=DOWN reason=… action=NONE result=NOT_TOUCHED` 형식(ASCII, key=value). payload·token·email·ID 없음.
- `status-running-ai.ps1`: `Watchdog`(overall, 15분 넘게 갱신 없으면 STALE 경고), `LastWatchdogCheck`, `LastRecovery`, `RestartBudget`, `WatchdogBlocked`, `GarminHint`. 파일만 읽고 아무것도 probe/restart하지 않는다.
  Scheduler 행은 `SchedulerConfig enabled=… (source: current shell environment; Spring not queried)`로 바꿔 Spring 실제 설정이라는 오해를 없앴다(API 추가 없음).
- `-DryRun`: 관찰·분류·예정 action·blocked·overall만 출력, 어떤 파일도 쓰지 않음. `-NoRecovery`: status/log/rotation은 갱신하되 start/stop/restart 없음.

## 7. Log retention

| 항목 | 값 |
|------|-----|
| 크기 rotation | 파일 > 10 MB → `<name>.<yyyyMMdd-HHmmss>.log`로 rename (watchdog 매 tick) |
| 재시작 전 rotation | `start-running-ai.ps1`이 connector/Spring 시작 직전에 비어 있지 않은 이전 로그를 rotate (Start-Process 리다이렉션은 파일을 truncate하므로 4A에서는 재시작마다 이전 로그가 사라졌다) |
| retention | rotated 로그 중 14일 초과 삭제 (`-LogRetentionDays`) |
| 범위 | `.runtime/logs` 안, 이름이 `(spring.out|spring.err|garmin-connector.out|garmin-connector.err|watchdog).<날짜>.log` 패턴인 파일만. 그 외 파일·디렉터리·현재 로그는 삭제하지 않음 |
| 한계 | 실행 중인 process가 연 로그(spring.out.log 등)는 Windows에서 rename이 실패할 수 있어 그 tick은 건너뛰고(조용히 false) 다음 재시작 시 rotate된다. 크기 rotation이 확실한 것은 watchdog 자신의 로그 |

## 8. 검증 (외부 PC, 비파괴)

```text
Java:  clean test → 128 total, 128 passed, 0 failed (Spring 코드 변경 없음)
PowerShell: Test-RunningAI.ps1 (4A) 통과 + Test-Watchdog.ps1 32/32 통과
Python: 변경 없음, 실행 안 함
```

`Test-Watchdog.ps1`: 정상(action NONE·overall UP) / connector dead→START_CONNECTOR / Spring dead→START_SPRING / Docker down→START_DOCKER / Docker CLI 없음 / PostgreSQL stopped→COMPOSE_UP / PostgreSQL unhealthy→복구 없음·DEGRADED·하위 차단 / starting 대기 / connector·Spring foreign port→복구 없음 / alive+unhealthy→pre-stop RESTART / 다중 장애 의존 순서 /
budget 3회 차단·component별 독립·window 만료·prune·restart storm 방지(실행기가 3회에서 멈춤) / 실행기 단계별 검증과 실패 시 중단 / 정상이면 무동작 /
state round trip·atomic(임시 파일 잔재 없음)·첫 실행·손상 파일 격리와 fail safe·모양 불일치 / probe 주입 관찰(재확인으로 일시 실패 흡수, 지속 실패=UNHEALTHY) /
Garmin 4종 reason은 hint만·action 없음, 성공 줄이 hint 해제, 오래된 hint·UNAVAILABLE 무시 / log retention(오래된 rotated만 삭제, 현재 로그·타 이름·txt 보존, 없는 디렉터리) / rotation(크기·-Always·빈 파일 제외·같은 초 충돌 회피) / log line 형식 /
**타 위치의 look-alike connector 프로세스**를 실제로 띄워 identity 거부·stop 거부·process 생존 확인 / 소스에 destructive docker·이름 기반 kill·Garmin 호출 없음 / `-DryRun`이 파일을 쓰지 않음 / task 정의(PT5M 반복, PT5M 지연, IgnoreNew, 이 repo 경로, 미등록) / startup task 분리 / 신규 산출물 gitignore.

개발 중 발견·수정: `[System.IO.File]::Replace(…, $null)`이 PowerShell에서 `$null`이 빈 문자열로 변환되어 실패 → `[NullString]::Value`. watchdog log는 BOM 없는 ASCII로 기록.
이 PC 실동작(Docker 없음): `-DryRun`은 docker `NO_CLI` 설정 오류로 action 없이 하위를 `DEPENDENCY_NOT_READY`로 차단(overall DOWN), `-NoRecovery`와 일반 실행 모두 status·state·log만 쓰고 프로세스/컨테이너는 건드리지 않았다(테스트 후 `.runtime` 삭제). Task 등록은 하지 않았다.

## 9. Live validation

```text
WATCHDOG_LIVE_VALIDATION_NOT_RUN
Reason: main runtime PC unavailable
```

(`WINDOWS_RUNTIME_LIVE_VALIDATION`은 4A와 함께 계속 PENDING)

## 10. 메인 PC 후속 검증 체크리스트

1. `git pull --ff-only origin main`
2. 3C-4A startup 검증 (`start-running-ai.ps1` → `status-running-ai.ps1` 전부 UP)
3. startup task 등록 (`install-running-ai-scheduled-task.ps1 -DryRun` 후 실제)
4. watchdog task 등록 (`install-running-ai-watchdog-task.ps1 -DryRun` 후 실제, Task Scheduler에서 5분 반복·IgnoreNew 확인)
5. runtime healthy 확인, `watch-running-ai.ps1 -DryRun`이 `Planned actions: none`
6. connector process를 수동 종료 (PID는 `.runtime\garmin-connector.pid`)
7. 다음 watchdog tick(최대 5분) 후 자동 복구와 `watchdog.log`의 `START_CONNECTOR … SUCCESS` 확인
8. Spring process 수동 종료
9. 자동 복구 확인 (`START_SPRING`)
10. restart budget: 10분 안에 같은 process를 4번 종료 → 3회까지만 복구, 이후 `RESTART_BUDGET_EXCEEDED`, 10분 후 재개 확인
11. DB/Docker 장애 시 안전 동작: PostgreSQL 컨테이너를 `docker stop`(volume 삭제 금지) → `COMPOSE_UP` 복구; Docker 강제 kill 테스트는 필요하지 않으면 하지 않음; unhealthy 상태에서 blind restart가 없는지 확인
12. `RUNNING_AI_GARMIN_SCHEDULER_ENABLED=true`로 scheduler Garmin sync 확인; connector token을 일부러 무효화하면 watchdog이 restart하지 않고 `GarminHint`만 표시하는지 확인
13. 로그 rotation 확인 (재시작 후 `*.yyyyMMdd-HHmmss.log` 생성, 14일 retention)
14. 로그오프/로그인(또는 재부팅) 후 startup task와 watchdog task가 연달아 동작하는지 확인
15. 추가로 확인할 것: watchdog이 Task Scheduler 하에서 `start-running-ai.ps1`로 띄운 자식 process를 유지하는지(4A 미검증 항목과 동일), 30분 Task 제한이 충분한지

## 11. Database / Git / 보안

migration 없음, schema 변경 없음. credential·token·payload를 읽거나 기록하지 않는다. `.runtime/`, `*.pid`, `watchdog-state.json`, `watchdog-status.json`, `*.corrupt`, 로그는 git ignore. legacy 보호: 모든 stop/kill은 command line에 component marker와 이 repo 경로가 있는 PID 파일 대상으로 한정, 이름 기반 kill 없음.

## 12. Limitations

- Windows Service가 아님: 사용자 로그온·Docker Desktop 사용자 session 의존, 로그오프 시 watchdog도 멈춘다.
- 실제 Docker/Task Scheduler/부팅 시나리오 미검증(외부 PC). 특히 복구 시 `start-running-ai.ps1`을 hidden child로 실행하는 경로와 Docker Desktop 시작 지연.
- 실행 중 process가 연 로그는 크기 rotation이 즉시 되지 않을 수 있음(재시작 시 rotate).
- Garmin hint는 Spring 로그 형식에 의존한다(scheduler가 켜져 있고 로그가 있어야 함). scheduler 실제 활성 여부는 API가 없어 확인 불가.
- restart는 crash loop를 막기만 하고 원인을 고치지 않는다: budget 초과 후에는 사람이 개입해야 하며 알림(메일/Slack)은 없다.
- distributed lock, 외부 API 인증 없음.

## 13. Next Phase (자동 시작 안 함)

후보: Phase 3C-4C Raspberry Pi 배포 준비, 또는 메인 PC live validation / hardening.
