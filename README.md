# RunningAI

AI 기반 러닝 훈련 플랫폼.

RunningAI는 Windows PC에서 PowerShell / Node.js / 파일 기반으로 동작하던 개인 러닝
자동화 프로젝트에서 출발했다. 이 repository는 기존 기능을 단계적으로 옮겨 담을
**Spring Boot 백엔드 foundation**을 관리한다.

## 목표

- Garmin 활동 데이터 수집
- 훈련 상태 분석
- 적응형 훈련(workout) 생성
- Intervals.icu 연동
- Garmin 훈련 전송
- 운동 후 상세 리포트
- 주간 / 월간 리포트

## Architecture

```text
                 ┌──────────────────────┐
  Garmin ──────► │                      │ ──────► Intervals.icu
                 │   RunningAI Server   │
  Scheduler ───► │  (Spring Boot / JPA) │ ──────► Reporting
                 │                      │
                 └──────────┬───────────┘
                            │
                  PostgreSQL (Flyway 관리)
```

서버 내부는 도메인별 패키지(`athlete`, `activity`, 이후 `workout`, `training`,
`integration`, `reporting`, `scheduler`)로 나누고, Controller → Service → Domain → Repository의
단순한 계층 구조를 따른다. 외부 시스템 연동은 `integration/*` 아래 어댑터로 분리해
도메인이 외부 시스템에 직접 의존하지 않도록 한다.

## 현재 상태

### 구현 완료 (`server/`)

- Spring Boot 3.5 / Java 21 / Gradle 프로젝트, `local` / `test` profile 분리
- PostgreSQL 개발환경 (`docker-compose.yml`)과 Flyway schema migration
- `Athlete`, `Activity` JPA entity와 audit 필드(`createdAt`, `updatedAt`)
- `ActivityRaw`: 외부 시스템 원본 payload를 JSONB로 보관 (내부 service, API 미노출)
- 기동 시 기본 athlete 자동 생성 (이름 / timezone 설정 가능, 기본값 `default` / `Asia/Seoul`)
- Activity API: 등록, 단건 조회, 목록 조회(최신순)
- Request validation과 구조화된 오류 응답
- Global exception handler (`VALIDATION_ERROR`, `INVALID_REQUEST`, `ACTIVITY_NOT_FOUND`,
  `DUPLICATE_ACTIVITY`, `DATA_CONFLICT`, `INTERNAL_SERVER_ERROR`)
- `externalSource + externalId` 중복 방지 (service 검사 + DB unique constraint)
- Health endpoint `GET /api/v1/health`, Spring Actuator `/actuator/health`
- Garmin ingestion core: raw 저장 → mapping → Activity upsert → reprocess
- Garmin connector(`tools/garmin-connector`, Python): 대화형 login / status / localhost HTTP `GET /activities`
- Spring `GarminActivitySource`(HTTP) + `GarminSyncService`: 최근 N개 fetch → ingestion, 결과 집계
- 통합 테스트 (H2 + Flyway, 외부 서비스 불필요; connector는 mock)

### 미구현 (예정)

Garmin 자동 sync(scheduler / retry), Intervals.icu API 호출, 훈련 자동 생성, Garmin structured
workout 전송, 주간 / 월간 리포트, scheduler, AI / LLM 분석, 알림.
이 중 일부는 기존 PowerShell / Node.js 구현이 메인 RunningAI PC에 존재하지만,
아직 이 repository에는 **포함되어 있지 않다**.

## API

| Method | Path                       | 설명                                  |
|--------|----------------------------|---------------------------------------|
| GET    | `/api/v1/health`           | `{"status":"UP","application":"running-ai"}` |
| POST   | `/api/v1/activities`       | Activity 등록 (201 + `Location`)      |
| GET    | `/api/v1/activities/{id}`  | Activity 단건 조회                    |
| GET    | `/api/v1/activities`       | Activity 목록 조회 (최신순)           |
| POST   | `/api/v1/garmin/sync`      | Garmin incremental sync를 1회 실행 (200 + 결과). 동시 실행 시 409 `GARMIN_SYNC_ALREADY_RUNNING`; 401/403/429 Garmin 오류, 503 connector 불가, 502 upstream 오류 |
| GET    | `/api/v1/garmin/sync/status` | 현재 checkpoint(`initialized`, `highWaterStartedAt`, `lastSuccessfulSyncAt`)를 DB에서만 조회 |

`POST /api/v1/garmin/sync`는 로컬 Garmin connector가 실행 중이어야 한다. 인증은 아직 없으므로(local/private 전제)
외부에 노출하지 않는다. `checkpointAdvanced`는 high-water mark가 앞으로 이동했을 때만 true다.

등록 요청 예:

```json
{
  "externalSource": "GARMIN",
  "externalId": "188081596",
  "activityType": "RUN",
  "startedAt": "2026-09-29T06:30:00+09:00",
  "durationSeconds": 3600,
  "distanceMeters": 10000,
  "averageHeartRate": 155,
  "maxHeartRate": 172
}
```

`externalSource`: `GARMIN | INTERVALS_ICU | MANUAL`
`activityType`: `RUN | TREADMILL_RUN | INDOOR_CYCLING`

시각은 UTC로 저장하고 응답한다 (위 `startedAt`은 `2026-09-28T21:30:00Z`로 응답된다).
athlete별 timezone(`Asia/Seoul`)은 이후 표시 / 리포트용으로 athlete에 보관한다.

오류 응답:

```json
{
  "code": "ACTIVITY_NOT_FOUND",
  "message": "Activity not found: 42",
  "timestamp": "2026-09-29T01:00:00.000Z"
}
```

validation 오류에는 `{ "field", "message" }` 형태의 `errors` 배열이 추가된다.

## Database

### 테이블

| 테이블 | 역할 |
|--------|------|
| `athlete` | 사용자. `name` unique |
| `activity` | 정규화된 운동 기록. `(external_source, external_id)` unique, `athlete_id` FK |
| `activity_raw` | 외부 시스템 원본 payload (`JSONB`). `(external_source, external_id)` unique, `activity_id` nullable FK |
| `flyway_schema_history` | Flyway migration 이력 |

`activity`는 RunningAI가 실제 사용하는 정규화 데이터이고, `activity_raw`는 Garmin /
Intervals.icu 등에서 받은 원본 응답이다. 원본을 보존해 두면 파싱 로직이 바뀌거나 새
metric이 필요할 때 재수집 없이 재처리할 수 있다. 외부 activity 하나당 raw row 하나만
유지하며, 재수집 시 `payload`와 `fetched_at`을 갱신한다.

## Garmin ingestion

Garmin 데이터는 별도 Python connector(`tools/garmin-connector/`)가 읽고, Spring 서버는 localhost HTTP로
raw JSON만 받아 기존 ingestion core로 처리한다. Spring은 Garmin credential / token을 알지 못한다.

```text
Garmin Connect
      │  python-garminconnect==0.3.16 (인증 · MFA · token은 connector 호스트의 ~/.garminconnect)
      ▼
Python Garmin Connector        GET /health, GET /activities?limit=N  (127.0.0.1:8765, raw 그대로)
      │  localhost HTTP
      ▼
Spring Boot  GarminActivitySource → GarminSyncService → GarminActivityIngestionService
      │
      ├──► activity_raw (JSONB, 원본)
      └──► activity     (정규화)
```

`GarminSyncService.syncRecent(limit)`는 최근 N개를 받아 활동별로 ingest하고
`fetched / created / updated / skipped(미지원 type, raw는 보존) / failed(malformed)`를 집계한다.
connector 오류(401 / 403 / 429 / 502 / 연결 불가)는 sync를 즉시 중단시키며 자동 재시도하지 않는다.
Incremental sync(high-water mark + overlap)와 운영 API(`POST /api/v1/garmin/sync`, `GET .../sync/status`)는 구현되어 있다.

### Garmin 자동 sync (scheduler)

기존 `POST /sync`와 같은 경로(`GarminSyncOperationService`, single-flight 공유)를 주기적으로 실행한다. **기본 비활성**이라
서버를 켜기만 해서는 Garmin/connector를 호출하지 않는다.

```yaml
running-ai.garmin.scheduler.enabled: false   # 기본값
running-ai.garmin.scheduler.fixed-delay: 1h  # 이전 실행 종료 후 대기 (fixed delay)
running-ai.garmin.scheduler.initial-delay: 1m
```

운영 PC에서 환경변수로 켠다 (로컬 Garmin connector가 실행 중이어야 한다):

```text
RUNNING_AI_GARMIN_SCHEDULER_ENABLED=true
RUNNING_AI_GARMIN_SCHEDULER_FIXED_DELAY=1h
RUNNING_AI_GARMIN_SCHEDULER_INITIAL_DELAY=1m
```

실패(401/403/429/connector 불가 등)는 로그만 남기고 같은 tick에서 재시도하지 않으며 다음 정기 실행을 기다린다.
수동 sync가 실행 중이면 해당 tick은 건너뛴다.

**아직 없는 것**: connector 프로세스 감독, scheduler 실행 이력, 외부 API 인증 — Phase 3C-4 이후.

Ingestion core 자체(활동 1건 기준):

```text
Garmin raw JSON (JsonNode)
      │
      ▼
ActivityRawService.saveOrUpdate      → activity_raw (JSONB)   ─ transaction 1, commit
      │
      ▼
GarminActivityMapper                 → NormalizedActivity      ─ pure, DB 접근 없음
      │
      ▼
ActivityService.upsertExternalActivity → activity             ─ transaction 2, commit
      │
      ▼
ActivityRawService.linkToActivity    → activity_raw.activity_id ─ transaction 3
```

- **Raw-first**: mapping이 실패해도(지원하지 않는 type, 필수 필드 누락) 원본은 `activity_raw`에 남는다.
  Activity는 만들어지지 않고 `GarminActivityMappingException`(code 예: `UNSUPPORTED_GARMIN_ACTIVITY_TYPE`)이 발생한다.
- **Idempotent**: 같은 Garmin activity를 여러 번 넣어도 `activity`, `activity_raw` 각 1행만 유지된다.
  값이 바뀐 payload는 같은 row를 갱신한다 (id / createdAt 유지, updatedAt / fetchedAt 갱신).
- **Reprocess**: `GarminActivityIngestionService.reprocess(garminActivityId)`는 저장된 JSONB만으로
  mapping과 upsert를 다시 수행한다. Garmin에 재접속하지 않는다.
- 지원 activity type: `running` 계열 → `RUN`, `treadmill_running` 계열 → `TREADMILL_RUN`,
  `indoor_cycling` 계열 → `INDOOR_CYCLING`. 그 외는 실패 (임의로 RUN에 매핑하지 않음).
- 입력 contract는 Garmin Connect activity list 항목 형태다 (2026-09-29 현행 client 소스로 확인, live 검증은
  Phase 3B-2): `duration` **seconds** → `durationSeconds`(반올림), `distance` metres, `startTimeGMT`
  `"yyyy-MM-dd HH:mm:ss"`(UTC, zone 표기 없음) → UTC `Instant`, `activityType.typeKey`
  (`running`, `treadmill_running`, `indoor_cycling`, `virtual_ride` …).
- Garmin 접근 전략(ADR)과 contract 근거: `docs/work-orders/2026-09-29-garmin-live-contract-investigation.md`.
  connector 사용법: `tools/garmin-connector/README.md`.
- HTTP API로 노출하지 않는다. 서비스 + 테스트로만 검증한다.
- 사용자용 `POST /api/v1/activities`는 그대로 create 의미(중복 시 409)를 유지한다.

### Migration 정책

- Schema는 **Flyway만** 변경한다 (`server/src/main/resources/db/migration/V{n}__{설명}.sql`).
- Hibernate는 `ddl-auto: validate`로 mapping과 schema의 일치만 검증한다.
- **이미 적용된 migration 파일은 수정하지 않는다.** 변경이 필요하면 새 version을 추가한다.
- JSON 컬럼 타입은 placeholder `${json_type}`으로 두어 PostgreSQL에서는 `JSONB`,
  H2 테스트에서는 `JSON`을 사용한다.

## Server

- Java 21
- Spring Boot 3.5 (Web, Validation, Data JPA, Actuator, Flyway)
- Gradle 8.14 (wrapper 포함)
- 로컬 / 운영: PostgreSQL 17, 테스트: H2 (PostgreSQL mode)

## Development

모든 Gradle 명령은 `server/`에서 실행한다. Gradle wrapper는 `JAVA_HOME`이 JDK 21을
가리키거나 `PATH`에 JDK 21이 있어야 한다.

### 테스트 실행

```powershell
cd server
.\gradlew clean test
```

테스트는 `test` profile(in-memory H2)로 실행되며 Flyway migration을 실제로 적용한다.
DB나 네트워크 서비스가 필요 없다.

### Local Database (PostgreSQL)

Repository root에서:

```powershell
docker compose up -d
docker compose ps        # postgres 가 healthy 인지 확인
```

기본값은 database / user 모두 `running_ai`, port `5432`이다. 비밀번호를 포함한 값은
root의 `.env`(git-ignore)로 바꿀 수 있으며 `docker compose`가 자동으로 읽는다.
`.env.example`을 복사해서 시작한다.

```powershell
docker compose down      # 컨테이너만 정리 (데이터 유지)
docker compose down -v   # 데이터 volume까지 삭제
```

### 서버 실행

기본 profile은 `local`이며 PostgreSQL이 필요하다. 접속 정보는 환경변수로 전달한다:

```powershell
$env:DB_URL      = "jdbc:postgresql://localhost:5432/running_ai"
$env:DB_USERNAME = "running_ai"
$env:DB_PASSWORD = "<docker compose에 설정한 비밀번호>"
cd server
.\gradlew bootRun
```

환경변수를 매번 설정하는 대신 root `.env`에 `DB_*` 값을 적어 두면 `local` profile이
이를 fallback으로 읽는다 (실제 환경변수가 우선한다). 기동 시 Flyway가 migration을
적용하고 기본 athlete가 생성된다.

기동 후:

```powershell
Invoke-RestMethod http://localhost:8080/api/v1/health
```

PostgreSQL 없이 확인하려면 in-memory `test` profile로 실행한다:

```powershell
.\gradlew bootRun --args='--spring.profiles.active=test'
```

### Profile

| Profile | Database                | Schema               | 용도                    |
|---------|-------------------------|----------------------|-------------------------|
| `local` | PostgreSQL (환경변수)   | Flyway + `validate`  | 기본값, 로컬 개발       |
| `test`  | H2 in-memory, PG mode   | Flyway + `validate`  | 자동화 테스트           |

H2와 PostgreSQL의 알려진 차이: H2에는 `JSONB`가 없어 `JSON`을 사용하고, timestamp 소수부를
microsecond로 반올림한다. 그 외 migration SQL은 동일하게 적용된다.

### 설정 및 민감정보

민감정보는 commit하지 않는다. `.env`, credential, token, IDE / build 산출물은
git-ignore되며 빈 placeholder만 담긴 `.env.example`만 추적한다. 지원하는 환경변수:

```text
POSTGRES_DB, POSTGRES_USER, POSTGRES_PASSWORD, POSTGRES_PORT   (docker compose)
DB_URL, DB_USERNAME, DB_PASSWORD                               (server local profile)
RUNNING_AI_ATHLETE_NAME, RUNNING_AI_ATHLETE_TIMEZONE
GARMIN_USERNAME, GARMIN_PASSWORD        (예약, 미사용)
INTERVALS_API_KEY                       (예약, 미사용)
```

## Claude Code

프로젝트 규칙은 root `CLAUDE.md`에, 세부 작업 규칙은 `.claude/skills/`의 project skill 3개에 있다.

- `running-ai-dev` — 일반 개발 workflow (조사 → 최소 구현 → 테스트 → 회귀 → diff/secrets 검토 → 문서 → commit)
- `running-ai-database` — Flyway / JPA / PostgreSQL / JSONB / activity·activity_raw 규칙
- `running-ai-integration` — Garmin / Intervals.icu 등 외부 연동 규칙과 현재 구현 상태

`.claude/settings.json`의 `Stop` hook이 `scripts/dev/validate-server.ps1`을 실행해 `server/`에
commit되지 않은 변경이 있을 때 Gradle 테스트를 자동으로 돌린다 (commit / push는 하지 않는다).
같은 스크립트를 직접 실행할 수도 있다: `powershell -File scripts/dev/validate-server.ps1 -Force`.

## Windows Runtime

Windows PC에서 Docker/PostgreSQL → Garmin connector → Spring Boot를 의존 순서대로 기동/종료하는 PowerShell script다
(`scripts/windows/`). 모두 repo root를 자동 계산하며 반복 실행해도 안전하다. Garmin login은 하지 않는다.

```powershell
scripts\windows\start-running-ai.ps1                # 기동 (jar가 없으면 build, -Build로 강제 rebuild)
scripts\windows\status-running-ai.ps1               # 상태 (읽기 전용, Garmin 호출 없음)
scripts\windows\stop-running-ai.ps1                 # Spring -> connector graceful 종료, DB는 유지 (-StopDatabase로 DB도 stop)
scripts\windows\install-running-ai-scheduled-task.ps1     # 로그온 시 자동 기동 task "RunningAI-Startup" 등록 (-DryRun으로 미리보기)
scripts\windows\uninstall-running-ai-scheduled-task.ps1   # 해당 task만 제거
```

- 사전 준비: Docker Desktop, JDK 21, `tools\garmin-connector\.venv`(Python 3.12 + requirements) 및 수동 `login`으로 만든 token store,
  DB 접속 정보(OS 환경변수 또는 git-ignore된 root `.env`). 자동 sync는 `RUNNING_AI_GARMIN_SCHEDULER_ENABLED=true`일 때만 동작한다.
- 런타임 파일은 `.runtime/`(PID, `logs/`)에 생기며 git-ignore 대상이다. stop은 데이터 volume을 절대 삭제하지 않는다.
- exit code: 0 성공 / 10 Docker / 11 PostgreSQL / 12 connector / 13 Java·build / 14 Spring.
- 비파괴 self-check: `powershell -File scripts\windows\tests\Test-RunningAI.ps1`, `...\Test-Watchdog.ps1` (Docker/Garmin 불필요).
- 아직 없는 것: Windows Service(현재는 로그온 기반), 알림. 실제 Garmin 환경(메인 PC) 검증 체크리스트는
  `docs/work-orders/2026-09-30-windows-runtime-orchestration.md` 9장, watchdog은 `docs/work-orders/2026-09-30-windows-watchdog.md` 10장.

### Watchdog (자동 복구)

`RunningAI-Startup` task는 로그온 시 전체 기동만 담당하고, **`RunningAI-Watchdog` task**는 운영 중 장애를 5분마다 점검한다
(one-shot `watch-running-ai.ps1`, 로그온 5분 후 시작, `MultipleInstances=IgnoreNew`).

```powershell
scripts\windows\watch-running-ai.ps1 -DryRun                    # 관찰·분류·예정 action만 출력 (아무것도 실행/기록하지 않음)
scripts\windows\watch-running-ai.ps1 -NoRecovery                # 상태/로그만 갱신, 복구 안 함
scripts\windows\install-running-ai-watchdog-task.ps1 -DryRun    # task 정의 미리보기
scripts\windows\install-running-ai-watchdog-task.ps1            # 등록 (AtLogOn +5분, 5분 반복)
scripts\windows\uninstall-running-ai-watchdog-task.ps1          # 이 task만 제거
```

- 복구 대상: 죽은 Docker daemon, 중지된 PostgreSQL 컨테이너, 죽었거나 재확인 후에도 unhealthy인 connector / Spring (Docker → PostgreSQL → connector → Spring 순, 단계별 검증, 기존 `start-running-ai.ps1` 재사용).
- **복구하지 않는 것**: Garmin 인증/403/429/upstream 문제(재시작으로 해결되지 않음, `GarminHint`로 표시만), running-but-unhealthy PostgreSQL(진단만), 다른 프로세스가 점유한 connector/Spring port(FOREIGN_PROCESS, 절대 kill하지 않음).
- Restart budget: component당 10분에 3회까지. 초과하면 `RESTART_BUDGET_EXCEEDED`로 멈추고 window가 지나면 다시 시도한다. 상태는 `.runtime/watchdog-state.json`(손상되면 격리하고 그 tick은 복구하지 않음).
- 진단: `.runtime/watchdog-status.json`, `.runtime/logs/watchdog.log`, `status-running-ai.ps1`의 Watchdog / RestartBudget 행. Garmin은 호출하지 않으며 `POST /sync`도 하지 않는다.
- Log retention: 10 MB 초과 또는 재시작 전 로그는 `<name>.<yyyyMMdd-HHmmss>.log`로 rotate, 14일 지난 rotated 로그 삭제 (`.runtime/logs` 안의 RunningAI 로그만).

## Raspberry Pi / Linux Deployment

Raspberry Pi OS 64-bit(arm64) 같은 systemd 호스트용 배포 artifact가 `deploy/linux/`에 있다. **준비 및 정적 검증까지만 끝났고 실제 Pi/systemd에서는 실행해 본 적이 없다.**
Windows 운영(`scripts/windows/`)과는 별개이며 수정하지 않았다. 상세 절차와 checklist는 [deploy/linux/README.md](deploy/linux/README.md).

```text
Boot -> systemd -> PostgreSQL -> running-ai-garmin-connector.service -> running-ai.service -> Spring Garmin scheduler
```

- **책임 분리**: process 생명주기·재시작·restart 폭주 제한·로그는 **systemd/journald**, Garmin 동기화 주기는 **Spring scheduler**(`RUNNING_AI_GARMIN_SCHEDULER_ENABLED=true`), Garmin 인증/token은 **connector**(127.0.0.1 전용). Windows watchdog은 Linux에 포팅하지 않았다.
- **구성**: `deploy/linux/systemd/`(unit 2개: `Restart=on-failure`, `RestartSec=30`, 10분 3회 start limit, SIGTERM graceful stop, 비root `runningai` 사용자, `NoNewPrivileges`/`PrivateTmp`/`ProtectSystem=full`/`UMask=0077`), `env/`(secret 없는 `*.env.example`), `scripts/`(`install-runtime.sh`, `deploy-app.sh`, `status-running-ai.sh`, `validate-runtime.sh`).
- **배치**: `/opt/running-ai`(jar·connector·venv, root 소유), `/etc/running-ai/*.env`(0600), `/var/lib/running-ai/garmin-tokens`(0700, Git 밖), 로그는 journald.
- **PostgreSQL**: 개인 Pi에는 native PostgreSQL을 권장안으로 두되 Docker(기존 `docker-compose.yml`)도 fallback으로 유지한다. 최종 선택은 전환 시점에 한다.
- **보안**: Spring은 기본으로 모든 interface에 bind하므로 env example에 `SERVER_ADDRESS=127.0.0.1`을 두었다. 인증이 없는 `POST /api/v1/garmin/sync`를 LAN/인터넷에 노출하지 않는다.

```bash
deploy/linux/scripts/install-runtime.sh --dry-run          # 디렉터리/user/unit 설치 미리보기 (패키지 설치·서비스 시작 없음)
deploy/linux/scripts/deploy-app.sh --dry-run --connector   # bootJar 빌드 -> 원자적 교체 -> venv -> restart -> health
deploy/linux/scripts/validate-runtime.sh --static          # 저장소 artifact 정적 검증 (systemd 불필요)
sudo systemctl enable --now running-ai-garmin-connector running-ai
journalctl -u running-ai -u running-ai-garmin-connector
```

- 한계: systemd `Restart=on-failure`는 process 종료만 복구한다(살아 있지만 HTTP가 DOWN인 상태는 감지하지 않음). DB 백업 자동화, 외부 API 인증은 아직 없다.

## Training Load

저장된 **정규화 Activity**만으로 훈련량을 계산한다 (Garmin/connector/`activity_raw` 호출 없음, 저장·캐시 없음, 요청마다 계산).

| Method | Path | 설명 |
|--------|------|------|
| GET | `/api/v1/training-load[?date=YYYY-MM-DD]` | `date`(없으면 athlete 현지 오늘)를 포함한 최근 7일 / 28일 rolling: `load7Days`, `load28Days`, `runningDistance{7,28}DaysMeters`, `runningDuration{7,28}DaysSeconds`, `activityCount{7,28}Days` |
| GET | `/api/v1/training-load/weekly[?date=YYYY-MM-DD]` | `date`가 속한 ISO 주(월~일): `weekStart`, `weekEnd`, `activityCount`, `trainingLoadMinutes`, `runningDistanceMeters`, `runningDurationSeconds`, `cyclingDurationSeconds` |

- **Load 정의: 지원되는 정규화 활동 1분 = 1 load minute** (`trainingLoadMinutes` = 총 duration 초 / 60.0). 대상은 `RUN`, `TREADMILL_RUN`, `INDOOR_CYCLING`. 러닝 거리·시간은 `RUN`, `TREADMILL_RUN`만이며 cycling은 load와 cycling 시간에만 반영된다. 강도(HR, pace, RPE, TRIMP)는 아직 반영하지 않는다.
- **Timezone**: 일/주는 athlete timezone(기본 `Asia/Seoul`) 캘린더 기준이며 UTC 날짜로 묶지 않는다. 범위는 `[from, to)` 반개구간, 주는 월요일 00:00 시작.
- 거리는 항상 meters. 잘못된 `date`는 `400 INVALID_REQUEST`.
- 아직 없는 것: readiness/recovery 모델, 워크아웃 처방(훈련 종류 후보는 아래 Training Decision Context).

### Training State

| Method | Path | 설명 |
|--------|------|------|
| GET | `/api/v1/training-state[?date=YYYY-MM-DD]` | `date`(없으면 athlete 현지 오늘) 기준의 훈련 상태 **측정값** |

`TrainingLoadService`의 일별 load(최근 28일, 쿼리 1회)에서 계산한다. 저장·캐시·Garmin 호출 없음. **숫자만 제공하며 READY/FATIGUED/위험 같은 판정이나 threshold는 없다.**

```text
acuteLoad         = 최근 7일 load 합                      (= current7DayLoad)
chronicLoad       = 최근 28일 load 합 / 4                 (최근 4주의 평균 주간 load. 28일 총합이 아님)
acuteChronicRatio = acuteLoad / chronicLoad               (chronicLoad = 0이면 null)
previous7DayLoad  = 그 직전 7일(D-13..D-7) load 합
rampLoad          = current7DayLoad - previous7DayLoad     (load minutes)
weeklyLoadChangePercent = (current - previous) / previous * 100   (previous = 0이면 null, 둘 다 0이어도 null)
runningDistance / runningDuration 의 7일 값, 직전 7일 값, 변화율 (RUN + TREADMILL_RUN만, previous = 0이면 null)
monotony          = 최근 7일 일별 load의 평균 / 모집단 표준편차(÷N), 휴식일 0 포함  (SD = 0이면 null)
strain            = current7DayLoad * monotony            (monotony가 null이면 null)
activeDays7Days   = 일별 load > 0인 날 수,  restDays7Days = 7 - activeDays7Days
```

- 창은 모두 athlete timezone의 캘린더 일 기준 rolling 7일이며, `/training-load/weekly`의 월~일 주간과 다르다. 정의할 수 없는 값은 JSON에서 명시적 `null`이다.
- 한계: duration-only load(강도 미반영), readiness·recovery·부상 위험 해석 없음, SD = 0일 때 monotony 정의 불가, 직전 구간이 0이면 변화율 정의 불가.

### Training Decision Context

| Method | Path | 설명 |
|--------|------|------|
| GET | `/api/v1/training-decision-context[?date=YYYY-MM-DD]` | `date`(없으면 athlete 현지 오늘) 기준의 최근 훈련 패턴 + 후보 훈련 종류 |

Phase 4A/4B 결과(`TrainingLoadService`, `TrainingState`)를 재사용해 **의사결정 입력(context)** 만 만든다. 활동 28일치를 쿼리 1회로 읽어 메모리에서 계산하며, 저장·캐시·Garmin 호출·migration은 없다. **candidate training types는 최종 워크아웃 처방이 아니다** (pace, 목표 HR, 반복 수, 구간 거리, Garmin step 없음. 세부 처방은 이후 Phase).

- **일별 분류 (`recentPattern`, 최근 14일, 과거→현재 순)**: `LONG > QUALITY_CANDIDATE > EASY_OR_GENERAL > INDOOR_CYCLING > REST` 우선순위로 하루에 하나. 각 일에 `classificationReason`(`DURATION_THRESHOLD`, `RUNNING_ACTIVITY`, `CYCLING_ONLY`, `NO_ACTIVITY`)과 activityCount, totalLoadMinutes, 러닝 시간·거리, cycling 시간이 붙는다.
  - **LONG = 단일 RUN/TREADMILL_RUN이 90분 이상** (`running-ai.training.classification.long-run-min-duration`). 하루 합산이 아니라 활동 1건 기준.
  - **`LONG`과 `QUALITY_CANDIDATE`는 RunningAI heuristic이며 생리학적 사실이 아니다.**
  - **QUALITY_CANDIDATE는 이번 Phase에서 부여하지 않는다.** 정규화 Activity에는 duration, distance, 평균/최대 HR만 있고 lap·pace zone·개인 LTHR이 없어 확신할 수 있는 기준이 없다. 임의의 bpm threshold를 만들지 않았다. 응답의 `qualityDetectionAvailable=false`, `lastQualityDate`/`daysSinceQuality`는 null.
- **경과일**: `lastRunningDate`/`daysSinceRunning`(RUN·TREADMILL_RUN), `lastActiveDate`/`daysSinceActive`(지원 활동 전체), `lastLongRunDate`/`daysSinceLongRun`. `daysSince = asOfDate - 마지막 날짜`(캘린더 일, 당일 0). 28일 이력 안에 없으면 null.
- **연속**: `consecutiveActiveDays`(asOfDate부터 거꾸로 일별 load > 0인 날 수), `consecutiveRestDays`(load = 0인 날 수).
- **loadTrend**: 4B의 `weeklyLoadChangePercent` 재사용. null → `UNKNOWN`, `> +10%` → `INCREASING`, `< -10%` → `DECREASING`, 그 외 `STABLE` (`running-ai.training.decision.stable-band-percent`). **load trend 라벨은 설명용이며 안전 등급이 아니다.**
- **candidateTrainingTypes** (`REST, RECOVERY, EASY, QUALITY, LONG, CROSS_TRAINING` 선언 순서, 중복 없음, 단순 Java 조건문):

  | 조건 | 후보 |
  |------|------|
  | 28일간 활동 없음 (`LIMITED_HISTORY`) | REST, EASY, CROSS_TRAINING |
  | 어제·오늘 long run (`daysSinceLongRun <= 1`) 또는 연속 활동 3일 이상 | REST, RECOVERY, EASY |
  | 위에 해당하지 않고 오늘 load 0 (`consecutiveRestDays >= 1`) | EASY, QUALITY, LONG, CROSS_TRAINING |
  | 그 외 (오늘 활동 있음, 연속 1~2일) | REST, RECOVERY, EASY, CROSS_TRAINING |

  후보는 “고려할 수 있는 종류”이며 “QUALITY가 안전하다”, “LONG이 위험하다” 같은 의미가 아니다.
- **reasons** (`DecisionReason`, 선언 순서): `LONG_RUN_RECENT`, `MULTIPLE_ACTIVE_DAYS`, `REST_DAY_RECENT`, `LOAD_INCREASING`, `LOAD_DECREASING`, `LOW_RECENT_ACTIVITY`(이력은 있으나 최근 7일 활동 없음), `NO_RECENT_RUNNING`, `RECENT_CYCLING`(어제·오늘 cycling), `LIMITED_HISTORY`. 모두 context의 데이터로 설명 가능하다.
- `trainingState`에 4B `TrainingState`가 그대로 포함된다. asOfDate 이후 활동은 어떤 필드에도 반영되지 않는다(look-ahead 없음). 잘못된 `date`는 `400 INVALID_REQUEST`.
- 설정: `running-ai.training.classification.long-run-min-duration`(90m), `running-ai.training.decision.stable-band-percent`(10), `running-ai.training.decision.pattern-days`(14, 1~28). 이력 창은 4B 28일과 같은 28일 고정.
- 한계: quality 세션 판별 불가(현재 정규화 필드 한계), pace·HR zone·LTHR 모델 없음, 워크아웃 처방 없음, race goal 인식 없음, readiness/recovery 판정 없음.

## Repository 구조

```text
running-ai/
├─ server/              Spring Boot 백엔드
├─ tools/garmin-connector/  Python Garmin connector (auth · token · read transport)
├─ docs/work-orders/    작업지시서 및 구현 기록
├─ scripts/dev/         개발용 스크립트 (validate-server.ps1)
├─ scripts/windows/     Windows 운영 스크립트 (start/stop/status/watchdog, Scheduled Task)
├─ deploy/linux/        Raspberry Pi / Linux 배포 artifact (systemd unit, env example, scripts)
├─ .claude/             Claude Code project skills / hooks
├─ CLAUDE.md            Claude Code 프로젝트 규칙
├─ docker-compose.yml   로컬 PostgreSQL
├─ .env.example
└─ README.md
```
