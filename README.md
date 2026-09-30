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

## Repository 구조

```text
running-ai/
├─ server/              Spring Boot 백엔드
├─ tools/garmin-connector/  Python Garmin connector (auth · token · read transport)
├─ docs/work-orders/    작업지시서 및 구현 기록
├─ scripts/dev/         개발용 스크립트 (validate-server.ps1)
├─ scripts/windows/     Windows 운영 스크립트 (start/stop/status/watchdog, Scheduled Task)
├─ .claude/             Claude Code project skills / hooks
├─ CLAUDE.md            Claude Code 프로젝트 규칙
├─ docker-compose.yml   로컬 PostgreSQL
├─ .env.example
└─ README.md
```
