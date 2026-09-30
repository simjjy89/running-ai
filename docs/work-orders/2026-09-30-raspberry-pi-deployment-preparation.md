# 2026-09-30 — Phase 3C-4C: Raspberry Pi / Linux Deployment Preparation

| 항목 | 내용 |
|------|------|
| 날짜 | 2026-09-30 |
| 작업 | systemd unit 2개, env example, Linux helper script 4개, 배포 문서. 실행은 하지 않음(준비 + 정적 검증) |
| 상태 | 완료 (Pi/Linux 실행 검증 없음) |
| 커밋 | `feat: prepare Raspberry Pi deployment` |
| 지시서 원문 | [2026-09-30-raspberry-pi-deployment-preparation-instruction.md](2026-09-30-raspberry-pi-deployment-preparation-instruction.md) |
| 이전 작업 | [2026-09-30-windows-watchdog.md](2026-09-30-windows-watchdog.md) |

## 1. 범위

`deploy/linux/`만 추가했다(`systemd/`, `env/`, `scripts/`, `README.md`) + root README 절. Spring/Python/DB/`scripts/windows`/`docker-compose.yml` 변경 없음(`validate-runtime.sh --static`이 `scripts/windows` 무변경을 확인).
Windows watchdog/PID/restart budget은 **포팅하지 않았다**: supervision은 systemd 기능(`Restart=`, `StartLimit*`, SIGTERM, journald)으로 대체한다.

## 2. Target runtime

| 항목 | 값 |
|------|-----|
| OS / arch | Raspberry Pi OS 64-bit(systemd 배포판이면 동일), arm64 |
| Java | 21 (`/usr/bin/java`, apt alternatives). Spring jar는 JVM bytecode라 Windows에서 빌드한 jar가 arm64에서도 그대로 동작 |
| Python | 3.12+ , connector venv는 **Pi에서** `requirements.txt`(핀 변경 없음, `garminconnect==0.3.16`)로 생성 (`curl_cffi` 등이 플랫폼별 wheel) |
| PostgreSQL | **권장: native**, fallback: 기존 Docker compose. 최종 확정 안 함(전환 시점에 선택) |

Native vs Docker 비교: native는 부팅 체인이 단순하고 Docker daemon이 필요 없어 RAM/CPU가 덜 들며 개인 Pi에 적합하다. Docker는 개발 환경과 동일하고 교체/테스트가 쉽지만 daemon 의존과 자원이 추가된다. unit은 `postgresql.service`에 **순서만**(`After=`) 걸고 `Wants=`는 걸지 않아 두 옵션 모두에서 동작한다(Docker 옵션은 `systemctl edit`로 `After=docker.service` 추가, README 기재).

## 3. Linux layout

```text
application: /opt/running-ai/app/running-ai.jar (+ .previous 1세대)     root 소유, service user는 읽기만
connector:   /opt/running-ai/connector/{garmin_connector, .venv}         root 소유
config:      /etc/running-ai/{running-ai.env, garmin-connector.env}      0600 root (systemd가 root로 읽음)
runtime:     /var/lib/running-ai/{garmin-tokens(0700, runningai), runtime}
logs:        journald (/var/log/running-ai는 만들지 않음 — 중복 저장·별도 rotation 방지)
source:      ~/running-ai (개발 checkout, 위치 무관) — deploy-app.sh가 build 산출물만 runtime으로 복사
```

root/etc/state/user는 `RUNNINGAI_ROOT|ETC|STATE|USER|GROUP`으로 바꿀 수 있고 `install-runtime.sh`가 unit의 기본 경로를 치환해 설치한다(unit 파일 자체는 기본값 그대로 검증 가능). 서비스 사용자는 `runningai`(system, nologin), root 실행 없음. Token store는 Git·app 디렉터리·env 파일 밖 `/var/lib/running-ai/garmin-tokens`.

## 4. systemd

| 항목 | connector unit | Spring unit |
|------|----------------|-------------|
| 파일 | `running-ai-garmin-connector.service` | `running-ai.service` |
| ExecStart | `.venv/bin/python -m garmin_connector --tokenstore /var/lib/running-ai/garmin-tokens serve --port ${GARMIN_CONNECTOR_PORT}` (CLI는 source에서 확인, host 옵션 없음 → 127.0.0.1 고정) | `/usr/bin/java $JAVA_OPTS -jar /opt/running-ai/app/running-ai.jar` (bootRun 아님) |
| 사용자 | `runningai` | `runningai` |
| 의존 | `After/Wants=network-online.target` | `After=network-online.target postgresql.service running-ai-garmin-connector.service`, `Wants=` network-online + connector |
| 재시작 | `Restart=on-failure`, `RestartSec=30` | 동일 |
| storm 제한 | `[Unit]`에 `StartLimitIntervalSec=600`, `StartLimitBurst=3` | 동일 |
| 종료 | 기본 SIGTERM, `TimeoutStopSec=20` (uvicorn graceful) | 기본 SIGTERM, `TimeoutStopSec=30`, `SuccessExitStatus=143` (JVM SIGTERM 정상 종료 코드) |
| env | `EnvironmentFile=/etc/running-ai/garmin-connector.env` (필수: 없으면 systemd가 명확히 실패) | `EnvironmentFile=/etc/running-ai/running-ai.env` |
| hardening | `NoNewPrivileges`, `PrivateTmp`, `ProtectSystem=full`, `ProtectHome=true`, `UMask=0077`, `StateDirectory=running-ai/garmin-tokens` + `StateDirectoryMode=0700`(유일한 쓰기 경로) | 동일(쓰기 경로 없음, JVM temp는 PrivateTmp) |
| WorkingDirectory | `/opt/running-ai/connector` | `/opt/running-ai/app` |

설계 판단:
- **Spring은 connector를 `Requires=`가 아니라 `Wants=`+`After=`**: `Requires=`면 connector 재시작이 Spring까지 재시작/중지시킨다. connector가 잠시 죽어도 Spring은 살아 있고 sync는 다음 tick에 재시도된다. 종료 순서는 `After=` 역순이라 Spring → connector.
- **`After=`는 readiness가 아님**: PostgreSQL이 아직 준비되지 않았으면 Spring은 fail-fast로 종료하고 `Restart=on-failure`가 30초 뒤 재시도한다. 단 3회/10분 제한에 걸리면 unit이 failed로 남으므로(`systemctl reset-failed`) 느린 첫 부팅에서 발생할 수 있다 — 문서에 기재. `ExecStartPre` health probe는 curl 의존·복잡도 대비 이득이 작아 도입하지 않았다.
- **Garmin 401/403/429는 재시작을 일으키지 않는다**: connector source 확인 결과 인증/rate-limit/upstream 오류는 모두 요청 단위 HTTP 응답(`GARMIN_AUTH_REQUIRED` 등)이고 token 없이도 `serve`가 기동하며, `serve`는 정상 종료 시 0을 반환한다. process가 죽을 때만 `on-failure`가 작동한다.
- **Spring 노출 발견**: `server.address`가 설정돼 있지 않아 Spring(Tomcat)은 **모든 interface에 bind**한다. Java 코드를 바꾸지 않고 `running-ai.env.example`에 `SERVER_ADDRESS=127.0.0.1`을 넣었고, host 검사(`validate-runtime.sh`)가 이를 확인하며 아니면 WARN한다. 인증 없는 `POST /api/v1/garmin/sync`는 외부 노출 금지.
- `WatchdogSec`/notify 미사용(애플리케이션 지원 필요). `KillSignal` 미지정. `ProtectHome=true`이므로 token store/venv를 `/home` 아래에 두면 동작하지 않는다(기본 경로는 `/var/lib`).
- Java는 `/usr/bin/java` 가정. 다른 경로면 `install-runtime.sh`가 `command -v java` 결과로 `systemctl edit running-ai` override 명령을 출력한다.

## 5. Configuration

- `deploy/linux/env/running-ai.env.example`: `SPRING_PROFILES_ACTIVE=local`, `SERVER_ADDRESS=127.0.0.1`, `SERVER_PORT`, `DB_URL/DB_USERNAME/DB_PASSWORD(빈 값)`, `GARMIN_CONNECTOR_URL=http://127.0.0.1:8765`, `RUNNING_AI_GARMIN_SCHEDULER_ENABLED=true`, `..._FIXED_DELAY=1h`, `..._INITIAL_DELAY=1m`, `JAVA_OPTS=`.
- `deploy/linux/env/garmin-connector.env.example`: `GARMIN_CONNECTOR_PORT=8765`만(Garmin credential/token 없음).
- Garmin token store 권한: 디렉터리 0700, `garmin_tokens.json` 0600(라이브러리가 기록). Garmin login은 서비스 사용자로 대화형 1회(README 명령), Spring은 계속 Garmin credential을 모른다.
- Token 이전: (A) Pi에서 새 login, (B) 기존 `garmin_tokens.json`을 수동 이전(소유자 `runningai`, 0600) — 실제 사용 시점에 결정, 이번에는 복사하지 않음.
- Scheduler 소유권: Spring scheduler = sync, systemd = lifecycle (cron/timer로 sync 중복 금지).

## 6. Deployment scripts (`deploy/linux/scripts/`)

| script | 역할 |
|--------|------|
| `lib.sh` | 경로/사용자 기본값(override 가능), `priv`(root면 직접, 아니면 sudo), `run`(dry-run 시 출력만), Java/Python 탐지, `render_unit`, 짧은 HTTP helper |
| `install-runtime.sh [--dry-run] [--create-user] [--enable]` | 의존성 **점검만**(패키지 설치 안 함, 서비스 시작 안 함), 사용자/디렉터리/권한 생성, env example 복사(기존 파일 덮어쓰지 않음, 0600), unit 렌더링·설치·`daemon-reload`, 선택적 `enable`. root 필요 단계만 sudo, `--dry-run`은 권한 불필요 |
| `deploy-app.sh [--dry-run] [--jar P] [--skip-build] [--connector] [--no-restart]` | 일반 사용자로 `gradlew bootJar` → `*-plain.jar` 제외 newest jar 선택 → zip 헤더 검증 → runtime 디렉터리에 **임시 이름으로 복사 후 rename**(반쯤 복사된 jar가 운영 이름으로 노출되지 않음), 이전 jar 1세대 `.previous` 보관 → (`--connector`) connector 소스 복사 + Pi에서 venv 생성·`pip install -r requirements.txt` → restart → `/actuator/health` UP 대기(120s) |
| `status-running-ai.sh` | `systemctl is-active`, connector `/health`, Spring `/actuator/health`, `/api/v1/garmin/sync/status`. 읽기 전용, Garmin 호출 없음, 전부 UP일 때만 exit 0 |
| `validate-runtime.sh [--static]` | `--static`: 저장소 artifact 검증(아래). 기본(host): Java 21(`/usr/bin/java`)·Python 3.12·systemd·서비스 사용자(비root)·디렉터리·token store 0700/파일 0600·env 파일 존재/권한/`DB_PASSWORD` 비어 있지 않음(값은 출력 안 함)/`SERVER_ADDRESS`·jar·venv import·unit 설치와 `systemd-analyze verify`·NTP 동기화. PASS/WARN/FAIL, FAIL이 있으면 exit 1 |

모든 mutation script는 `set -euo pipefail`(status는 예상되는 non-zero 검사 때문에 `-uo pipefail`).

## 7. Database

- 추천 Pi 옵션: native PostgreSQL(미확정, Docker fallback 유지). 빈 DB에서 Flyway V1–V4 자동 적용, 기존 migration 무변경(migration 없음, schema 변경 없음).
- 기존 Windows 데이터 이전은 수행하지 않았다. 선택지: **A fresh DB** — Flyway 생성 후 첫 sync는 bootstrap(첫 page)이고 이후 overlap window로 진행, 과거 이력은 재수집되지 않으며 `garmin_sync_state`도 비어서 시작. **B `pg_dump`/`pg_restore`** — activity, raw payload, sync checkpoint 보존(Windows 스택을 먼저 멈춰 checkpoint 일관성 확보). 결정은 보류.
- DB 시간은 계속 UTC `Instant`, Pi timezone은 로그 표시에만 영향. NTP 동기화 필수(checklist). SD card는 DB write에 불리 → SSD 권장. 백업 자동화는 없으며 Pi 운영 전 후속 과제.

## 8. Logging

journald: `journalctl -u running-ai -u running-ai-garmin-connector`. Spring/connector 모두 stdout·stderr 콘솔 로깅이라 journald가 받고(파일 appender 없음을 확인) 자체 rotation/파일 로그를 만들지 않는다. 보존은 journald 설정(`SystemMaxUse` 등)에 맡긴다.

## 9. 검증 결과 (이 PC: Windows, Git Bash 있음, systemd/Linux 없음)

```text
Java: clean test → 128 total, 128 passed, 0 failed (Spring 코드 무변경)
Python: 변경 없음, 실행 안 함
Linux script validation: bash -n 5/5 OK, validate-runtime.sh --static → 0 failure(s), 1 warning(systemd-analyze 없음)
```

`--static`(43 PASS)이 확인하는 것: unit — 비root `User`, `Restart=on-failure`+`RestartSec`, `StartLimit*`가 `[Unit]`에 위치, 필수 `EnvironmentFile`(선행 `-` 없음), `WorkingDirectory`, `TimeoutStopSec`+`KillSignal` 없음, watchdog/notify 미사용, hardening 3종, bootRun/Windows 경로/`/home/`/`0.0.0.0` 없음, connector는 `--host` 없음+`StateDirectoryMode=0700`, Spring은 `java ... -jar`+`SuccessExitStatus=143`, Spring이 connector/PostgreSQL 뒤에 정렬되고 `Requires`/`BindsTo` 없음 / env example — 비밀 후보 key(PASSWORD/TOKEN/SECRET/KEY/EMAIL)는 빈 값, `export`·shell 치환 없음, loopback bind·connector URL·scheduler 활성, connector env는 port만 / scripts — `bash -n`, `set -euo pipefail`, Windows 경로·사용자 홈·Garmin credential 변수 없음, Garmin/POST/`compose down`/volume 삭제 호출 없음 / `scripts/windows` 무변경.
`systemd-analyze verify`는 systemd 호스트에서만 실행되며(스크립트가 자동으로 사용), 여기서는 구조 검사로 대체했다.
추가로 Git Bash에서 `install-runtime.sh --dry-run --create-user`와 `deploy-app.sh --dry-run`(jar 경로 지정/`--connector`)을 실행해 모든 동작이 `+ ...`로만 출력되고 아무 것도 변경되지 않음을, 잘못된 jar는 거부됨을, host 모드 `validate-runtime.sh`/`status-running-ai.sh`가 이 PC에서 의도대로 FAIL/DOWN을 보고함을 확인했다. 그 과정에서 `--skip-build`+jar 없음 시 `set -e`로 조용히 종료하던 버그(`find` 실패)와 지나치게 넓은 정적 검사 2건을 고쳤다.
shellcheck는 없어 실행하지 않았다.

## 10. Live validation

```text
RASPBERRY_PI_LIVE_VALIDATION_NOT_RUN   (No Raspberry Pi runtime available in current environment)
LINUX_RUNTIME_LIVE_VALIDATION_NOT_RUN  (systemd 호스트 아님: 실제 systemctl/journald/사용자/권한 동작 미검증)
WINDOWS_RUNTIME_LIVE_VALIDATION=PENDING
WATCHDOG_LIVE_VALIDATION=PENDING
```

## 11. Pi 첫 배포 시 확인할 것

`deploy/linux/README.md`의 Pi migration checklist 전체 + 특히: `systemd-analyze verify` 결과, `ProtectSystem/ProtectHome/StateDirectory`와 실제 파일 접근 호환(연결 실패 시 hardening 항목을 하나씩 완화), `kill -9` 후 30초 뒤 재시작, 재부팅 후 자동 기동, start limit에 걸릴 때의 `reset-failed` 절차, 첫 부팅에서 PostgreSQL 지연 여부, `curl_cffi` arm64 wheel 설치 성공, Spring이 실제로 127.0.0.1에만 listen(`ss -ltnp`).

## 12. Security

secrets scan 정상(실값 없음), env example의 비밀 key는 전부 빈 값, Garmin credential/token은 어디에도 없음. 서비스 사용자 비root, connector 127.0.0.1, Spring도 env로 loopback 고정 권장, 방화벽 규칙 미추가, 인증 없는 API 외부 노출 금지 명시.

## 13. Remaining limitations

- 실제 Raspberry Pi/systemd 미검증.
- `Restart=on-failure`는 process 종료만 복구: 살아 있으나 HTTP가 DOWN인 상태는 감지·복구하지 않는다(필요 시 health 기반 watchdog을 나중에 판단).
- start limit(10분 3회)로 느린 부팅 시 unit이 failed로 남을 수 있음(`reset-failed`).
- DB 백업 자동화 없음, 실제 운영 데이터 이전 미수행, 외부 API 인증 없음, Spring 코드는 여전히 기본이 전체 interface bind(env로 제한).
- `deploy-app.sh`의 rollback은 이전 jar 1세대 수동 복원뿐.

## 14. Next Phase (자동 시작 안 함)

후보: 메인 PC Windows live validation(3C-4A/4B), Raspberry Pi live deployment, Phase 4 training state/load model.
