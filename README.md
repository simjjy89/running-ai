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
| POST   | `/api/v1/workout-publish`  | 지정 날짜 workout을 Spring 파이프라인으로 Intervals.icu에 publish (기본 비활성 `WORKOUT_PUBLISHING_ENABLED=false` → 409 `WORKOUT_PUBLISHING_DISABLED`; 아래 Workout Publish) |
| POST   | `/mcp`                     | MCP(Streamable HTTP, stateless) `publish_workout` tool. 기본 비활성(`RUNNINGAI_MCP_ENABLED=false` → 404); 아래 MCP 참고 |
| GET    | `/api/v1/athlete/intensity-profile` | 현재 athlete의 LTHR / threshold pace 조회 (아래 Athlete Intensity Profile) |
| PUT    | `/api/v1/athlete/intensity-profile` | LTHR / threshold pace 전체 교체 |
| GET    | `/api/v1/workout-intensity-targets[?date=YYYY-MM-DD]` | Workout Prescription + pace/HR/트레드밀 target (아래 Workout Intensity Targets) |

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
| `garmin_sync_state` | Garmin incremental sync checkpoint (athlete당 1행, `athlete_id` unique) |
| `athlete_intensity_profile` | Athlete LTHR / threshold pace (athlete당 1행, `athlete_id` unique, 두 metric 모두 nullable) |
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

### Windows API 호출 / AI Coach Operator CLI

**UTF-8-safe JSON 호출.** `$body | ConvertTo-Json`을 그대로 `Invoke-RestMethod -Body`에 넘기지 않는다
(Windows PowerShell 5.1에서는 그 바이트가 콘솔/출력 encoding에 의존하고, 응답도 charset 없는
`application/json`이면 Invoke-RestMethod 자체가 비-UTF-8로 디코드할 수 있다 - 둘 다 Phase 6H-7.2에서
실제로 재현된 버그다). 대신 `RunningAI.Common.ps1`의 `Invoke-RunningAiJsonRequest`를 쓴다:

```powershell
. .\scripts\windows\RunningAI.Common.ps1
Invoke-RunningAiJsonRequest -Method POST -Uri "http://127.0.0.1:8080/api/v1/workout-drafts" `
    -Body @{ date = "2026-10-04"; requestedGoal = "easy taper run before the half marathon" }
```

또는 바로 쓸 수 있는 wrapper:

```powershell
.\scripts\windows\invoke-running-ai-api.ps1 -Method POST -Path "/api/v1/workout-drafts" -Body $body
```

자세한 원인과 수정 내용은 [docs/architecture/windows-api-encoding.md](docs/architecture/windows-api-encoding.md).

**AI Coach operator CLI (Phase 6H-8).** Draft 생성/resume → review → revise 반복 → `APPROVE` 입력 →
publish preview → `YES` 입력 → controlled publish → read-back verification → safe-mode 복귀까지
하나의 스크립트로 진행한다. AI는 draft만 만들고, approve와 publish는 항상 사람이 정확한 단어를
입력해야 실행된다 (bypass용 parameter는 존재하지 않는다).

```powershell
.\scripts\windows\running-ai-coach.ps1 -Date 2026-10-04 -AvailableMinutes 35 -Environment OUTDOOR -Goal "easy taper run before the half marathon"
.\scripts\windows\running-ai-coach.ps1 -DraftId 12          # 기존 draft resume
```

이미 approve된 draft만 다시 publish하려면:

```powershell
.\scripts\windows\publish-approved-draft-controlled.ps1 -DraftId 12
```

구조와 안전장치(TOCTOU 재검증, REST/이미-published/unpublishable short-circuit, finally cleanup)는
[docs/architecture/coach-operator-workflow.md](docs/architecture/coach-operator-workflow.md).
비파괴 self-check: `powershell -File scripts\windows\tests\Test-CoachOperator.ps1` (로컬 HttpListener만
사용, 실제 서버/Docker/Garmin/Intervals 호출 없음).

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

### Workout Recommendation

| Method | Path | 설명 |
|--------|------|------|
| GET | `/api/v1/workout-recommendation[?date=YYYY-MM-DD]` | `date`(없으면 athlete 현지 오늘)의 **workout intent 하나**와 duration 범위 |

`TrainingDecisionContext`(Training Decision Context)만을 입력으로 오늘 고려할 훈련 종류를 하나 고른다. 저장·캐시·Garmin·외부 모델 호출 없음. 응답: `recommendedIntent`, `durationMinMinutes`/`durationMaxMinutes`, `intensityClass`(`NONE, VERY_EASY, EASY, MODERATE, HARD`), `confidence`, `dataSufficiency`(`LOW, MEDIUM, HIGH`), `reasons`, `summary`(고정 템플릿 영어 문장), 그리고 근거 확인용 `decisionContext` 전체. 잘못된 `date`는 `400 INVALID_REQUEST`.

- **Recommendation chooses workout intent only. It does not generate workout steps** (정확한 시간, 반복, pace, HR target 없음. Phase 5B 이후).
- **QUALITY is not automatically selected in the initial model.** QUALITY intent는 enum/API에 있지만 quality-session/intensity context가 생기기 전까지 자동 선택하지 않으며, 후보에 있으면 `QUALITY_HISTORY_UNAVAILABLE`이 붙는다.
- **Recommendation heuristics are scheduling rules, not medical or injury-risk assessments.** 아래 숫자는 훈련 배치용 규칙이며 4B 지표(acute/chronic ratio, monotony, strain)에는 threshold를 걸지 않는다.
- 선택 순서(첫 매칭, 반드시 context 후보 안에서): ① long run 어제·오늘 + 연속 활동 2일 이상, 또는 연속 활동 4일 이상 → `REST` ② long run 어제·오늘, 또는 연속 활동 3일 이상 → `RECOVERY` ③ 28일간 활동 없음 → `EASY` ④ 마지막 long run이 6일 이상 전이고 history 충분(HIGH)·연속 활동 2일 이하·load trend가 INCREASING 아님·3일 내 러닝 있음 → `LONG` ⑤ 그 외 `EASY`(기본) ⑥ EASY가 후보에 없을 때만 `CROSS_TRAINING`. 후보 목록의 순서는 우선순위가 아니다.
- duration 범위(분): REST 0-0, RECOVERY 20-40, EASY 30-60, QUALITY 30-70, LONG 75-120, CROSS_TRAINING 30-60. intensity class는 pace/HR/LTHR/RPE 모델이 없는 정성 라벨이다.
- `confidence`는 “규칙과 데이터가 얼마나 명확한가”이고, `dataSufficiency`(최근 14일 활동일 3일 미만 LOW, 6일 이상 + trend 정의됨 HIGH)와 별개다.
- 한계: quality-session 모델 없음, pace/HR zone/LTHR 없음, 정확한 시간·workout steps 없음, race goal 인식 없음, readiness/recovery 모델 없음.

### Workout Prescription

| Method | Path | 설명 |
|--------|------|------|
| GET | `/api/v1/workout-prescription[?date=YYYY-MM-DD]` | `date`(없으면 athlete 현지 오늘)의 추천 intent를 **정확한 시간과 warm-up / main / cool-down 구조**로 변환 |

**Phase 5B-1 converts an intent recommendation into an exact-duration qualitative workout structure. It does not yet contain pace, HR, LTHR, incline, intervals, or Garmin steps.**

`WorkoutRecommendation`(Workout Recommendation)만을 source로 쓰며 intent를 바꾸지 않는다. 응답: `asOfDate`, `intent`, `totalDurationMinutes`(정수 분), `segments`(`type` = `WARM_UP|MAIN|COOL_DOWN|REST`, `durationMinutes`, `intensityClass`, `description`), `summary`(고정 템플릿 영어 문장), 근거 추적용 `recommendation` 전체. 잘못된 `date`는 `400 INVALID_REQUEST`. 저장·캐시·Garmin 호출 없음.

- **Exact duration is currently selected by a deterministic scheduling policy within the recommendation range.** intent별 기본값을 추천 범위 `[durationMinMinutes, durationMaxMinutes]`로 clamp한다(범위가 항상 우선). 생리학적 최적값이 아니다.
- 현재 기본값(모두 deterministic scheduling default): `REST` 0분(REST segment 1개), `RECOVERY` ≈30분(5/20/5), `EASY` ≈45분(10/30/5), `LONG` ≈90분(10/70/10), `CROSS_TRAINING` ≈45분(5/35/5, 종목 미지정). 범위가 달라지면 segment 시간도 함께 조정된다(EASY 35분 → 5/25/5). segment 합 = 총 시간, 모든 segment >= 0, REST 외에는 MAIN > 0이 항상 보장된다.
- intensity: warm-up/cool-down `VERY_EASY`, MAIN은 RECOVERY `VERY_EASY`, EASY·LONG·CROSS_TRAINING `EASY` (정성 라벨).
- **QUALITY는 구조를 만들지 않는다.** QUALITY recommendation이 들어오면 `422 QUALITY_PRESCRIPTION_NOT_SUPPORTED` (현재 추천 모델은 QUALITY를 선택하지 않으므로 정상 경로에서는 발생하지 않는다). 추천 범위가 잘못된 경우(min > max 등)는 조용히 보정하지 않고 내부 오류로 처리한다.
- 한계: 정확한 시간은 heuristic 기본값이며 athlete별 적응 없음, pace/HR/LTHR/incline 모델 없음, interval 구조 없음, QUALITY 미지원, cross-training 종목 미지정.

### Athlete Intensity Profile

| Method | Path | 설명 |
|--------|------|------|
| GET | `/api/v1/athlete/intensity-profile` | 현재 athlete의 LTHR / threshold pace 조회 |
| PUT | `/api/v1/athlete/intensity-profile` | LTHR / threshold pace를 **전체 교체**(partial update 아님) |

LTHR(`lactateThresholdHeartRateBpm`, bpm)와 threshold pace(`lactateThresholdPaceSecondsPerKm`, 초/km)는
workout마다 달라지는 값이 아니라 **athlete 고유 데이터**이므로 persistent model(`athlete_intensity_profile`
테이블, athlete당 1행)로 관리하며 property/설정 파일에 두지 않는다. 아직 profile이 없으면 GET은 404가 아니라
`{"initialized": false, "lactateThresholdHeartRateBpm": null, "lactateThresholdPaceSecondsPerKm": null}`을
반환한다. PUT은 두 필드 모두 optional이며(둘 다, 하나만, 혹은 `null`도 허용 — `null`은 "그 metric 미설정"을
의미), 값이 있으면 `> 0`만 검증한다(생리학적 상한/하한은 두지 않음, `<= 0`은 `400 VALIDATION_ERROR`). Garmin에서
LTHR을 자동으로 가져오지 않는다(수동/domain profile만 사용, Garmin 자동 감지는 별도 Phase).

### Workout Intensity Targets

| Method | Path | 설명 |
|--------|------|------|
| GET | `/api/v1/workout-intensity-targets[?date=YYYY-MM-DD]` | `date`(없으면 athlete 현지 오늘)의 Workout Prescription에 **pace / %LTHR 심박 / 트레드밀 speed·incline target**을 얹은 결과 |

**Phase 5B-2 layers numeric intensity targets onto the existing Workout Prescription. It does not add
interval/repeat structure, QUALITY workout generation, or Garmin/Intervals.icu rendering.** 기존
`GET /api/v1/workout-prescription` 응답/동작은 변경되지 않았다; 이 endpoint는 그 위에 target만 추가한
새 응답(`TargetedWorkoutPrescription`)을 반환하며 원본 `prescription`과, target을 계산할 때 사용한
`profile`(위 Athlete Intensity Profile 응답과 동일한 모양)을 그대로 포함한다. Target은 매 요청마다
"현재" intensity profile로 재계산될 뿐 DB에 저장되지 않으므로, profile을 PUT한 바로 다음 호출부터 새 값이
반영된다. **과거 `date`를 조회해도 그 시점이 아니라 현재 profile을 사용한다** (profile 변경 이력을 보관하지
않기 때문 — 알려진 한계).

각 segment는 `primaryTargetType`(`NONE | PACE | HEART_RATE | QUALITATIVE`)을 갖는다: threshold pace가
있으면 `PACE`, 없고 LTHR만 있으면 `HEART_RATE`, 둘 다 없거나(또는 intensity class에 정의된 band가 없으면)
`QUALITATIVE`로 fallback한다 — **profile이 전혀 없어도 prescription 자체는 실패하지 않는다.** Pace를 HR보다
우선하는 것은 향후 outdoor pace / 트레드밀 km/h / Garmin pace target으로의 변환이 더 단순하기 때문이며,
생리학적 우월성을 뜻하지 않는다. `REST` segment는 항상 `NONE`(target 전부 `null`)이고, **`CROSS_TRAINING`
intent는 profile 존재 여부와 무관하게 모든 segment가 항상 `QUALITATIVE`다** — 현재 profile은 *running*
threshold 전용이며 cycling 등 다른 modality에 적용하지 않는다(별도 모델은 향후 Phase). 상위 응답의
`targetAvailability`(`FULL | PACE_ONLY | HEART_RATE_ONLY | QUALITATIVE_ONLY`)는 이 가용성을 prescription
전체 기준으로 요약한다.

현재 숫자 target은 5B-1이 실제로 만드는 `VERY_EASY`/`EASY` running segment에만 정의되어 있다(`HARD`/QUALITY
target 없음). **아래 수치는 RunningAI의 초기 deterministic scheduling heuristic이며, 생리학적으로 검증되거나
실제 데이터로 튜닝된 값이 아니다:**

```text
Threshold pace: seconds/km 로 저장. LTHR: bpm 로 저장.

VERY_EASY pace : threshold pace의 125–145%  (숫자가 작을수록 빠른 pace이므로
EASY pace      : threshold pace의 115–130%   `fastSecondsPerKm`/`slowSecondsPerKm`로 표기, min/max 아님)

VERY_EASY heart rate : LTHR의 65–78%
EASY heart rate      : LTHR의 75–85%
```

트레드밀 speed는 `speedKph = 3600 / paceSecondsPerKm`(pace target이 있을 때만; pace 방향과 반대이므로
`minSpeedKph = 3600 / slowSecondsPerKm`, `maxSpeedKph = 3600 / fastSecondsPerKm`)로 변환하고 0.1 km/h
단위로 반올림한다(HALF_UP, 예: 10.74→10.7, 10.75→10.8). Treadmill incline은 pace/HR profile과 **무관하게**
running segment면 항상 제공되는 운영 기본값이다(야외 달리기와 동일한 부하를 의미하지 않음): warm-up/cool-down
0.0–0.5%, main 0.5–1.0%.

한계: interval/repeat/distance target 없음, QUALITY target 없음, running threshold만 지원(cycling 없음),
profile 변경 이력 없음(과거 조회도 현재 profile 사용), race pace 없음, RPE 모델 없음, Garmin/Intervals.icu
렌더링 없음, pace/HR heuristic은 실제 데이터로 튜닝되지 않음.

### Intervals Workout Publisher

내부 코드(`com.runningai.integration.intervals`)이며 scheduler, 자동 생성에는 연결되지 않았다(5C-3). 수동 HTTP trigger는 아래 Workout Publish(6A).
`RenderedIntervalsWorkout`을 Intervals.icu 캘린더에 **idempotent하게 publish하고 서버 readback으로 검증**한다.

- 인증/설정: `INTERVALS_API_KEY`(환경변수만, 저장소·로그·예외에 남기지 않음), `running-ai.intervals.athlete-id`(기본 `0` = key 소유자), `base-url`, 타임아웃(기본 3s/15s). key가 없으면 앱은 정상 기동하고 publish만 `INTERVALS_NOT_CONFIGURED`로 실패한다.
- Logical identity = RunningAI + athlete + 날짜. 소유 표시는 이벤트 `external_id` = `runningai:workout:v1:<athleteId>:<yyyy-MM-dd>` (description은 렌더 텍스트 그대로). legacy `[RunningAI-Control]` description marker 이벤트는 새 이벤트를 만들지 않고 제자리 UPDATE로 인수한다.
- 결과: `CREATED` / `UPDATED`(같은 event id) / `NO_CHANGE`(쓰기 요청 없음). 소유하지 않은 이벤트는 수정하지 않으며(`INTERVALS_UNMANAGED_WORKOUT_CONFLICT`), 소유 이벤트가 2개 이상이면 아무것도 하지 않고 `INTERVALS_DUPLICATE_OWNED_WORKOUT`.
- **쓰기 요청은 절대 자동 재시도하지 않는다.** POST가 timeout 등으로 결과를 모르면 같은 POST를 다시 보내지 않고 marker로 재조회해 실제 상태를 따른다. CREATE/UPDATE 후에는 서버에서 다시 읽어 marker·날짜·workout text를 확인하며 다르면 `INTERVALS_READBACK_MISMATCH`.
- 한계: Intervals 서버 readback까지만 검증한다. Garmin 전달(Intervals→Garmin Connect→기기)은 불투명하며 pace Garmin target은 UNRESOLVED, %LTHR Garmin은 ASSUMED, 새 treadmill cue는 기기 검증 필요(5C-4).

### Workout Publish (수동 운영 trigger, Phase 6A)

검증된 파이프라인 앞에 놓인 **명시적 수동 trigger**다. **기본 비활성**이며 scheduler/ChatGPT와는 연결되어 있지 않다.

```text
POST /api/v1/workout-publish
  → WorkoutPublishApplicationService
      WorkoutIntensityTargetService.targetedPrescribe(date)   (기존 prescription 흐름)
      → StructuredWorkoutMapper → IntervalsWorkoutRenderer → IntervalsWorkoutPublisher
```

- 요청: `{"date": "2026-10-02"}` (필수, athlete-local 날짜; "오늘" 자동 선택 없음). GET으로는 publish되지 않는다.
- 응답 200: `{"date":"2026-10-02","operation":"CREATED","verified":true,"intent":"EASY","stepCount":3}` — `operation`은 `CREATED` / `UPDATED` / `NO_CHANGE`(publisher 의미 그대로, 반복 호출 idempotency도 publisher 담당). remote event id는 응답에 노출하지 않는다.
- **안전 스위치**: `running-ai.workout-publishing.enabled` = `WORKOUT_PUBLISHING_ENABLED`, **기본 `false`**. 꺼져 있으면 409 `WORKOUT_PUBLISHING_DISABLED`. 메인 PC의 legacy workout publisher를 끄기 전에는 어떤 PC에서도 `true`로 운영하지 않는다(두 writer가 같은 캘린더에 쓰게 된다).
- **Single-flight**: 같은 날짜의 publish가 JVM 안에서 이미 실행 중이면 즉시 409 `WORKOUT_PUBLISH_ALREADY_RUNNING`(대기/큐 없음). 다른 날짜는 병렬 가능, 성공/실패와 무관하게 guard는 해제된다. 분산 lock은 없다(단일 인스턴스 전제).
- 오류 코드: 400 `VALIDATION_ERROR`/`INVALID_REQUEST`; 409 `WORKOUT_PUBLISHING_DISABLED`, `WORKOUT_PUBLISH_ALREADY_RUNNING`, `INTERVALS_DUPLICATE_OWNED_WORKOUT`, `INTERVALS_UNMANAGED_WORKOUT_CONFLICT`; 401 `INTERVALS_AUTH_FAILED`; 403 `INTERVALS_FORBIDDEN`; 429 `INTERVALS_RATE_LIMITED`; 503 `INTERVALS_NOT_CONFIGURED`/`INTERVALS_CONNECTION_FAILED`; 504 `INTERVALS_TIMEOUT`; 502 `INTERVALS_UPSTREAM_ERROR`/`INTERVALS_READBACK_MISMATCH`/`INTERVALS_CLIENT_ERROR`/`INTERVALS_INVALID_RESPONSE`; 422 `INTERVALS_EMPTY_WORKOUT`(예: REST day). 그 밖의 prescription/domain 오류는 기존 전역 handler를 따른다.
- 인증이 없으므로 이 endpoint를 공용 인터넷에 노출하지 않는다(local/private 전제; ChatGPT connector 단계에서 인증 경계를 별도로 설계).
- **메인 PC 전환 절차**: ① 메인 PC legacy Scheduled Task 조사 → ② legacy writer disable → ③ 수동 shortcut/습관 중단 → ④ legacy가 더 이상 실행되지 않음 확인 → ⑤ Spring 서버 배포/기동 확인 → ⑥ `WORKOUT_PUBLISHING_ENABLED=true` → ⑦ 수동 publish smoke test → ⑧ scheduler는 아직 OFF (Phase 6B).

#### 자동 daily trigger (Phase 6B, `WorkoutPublishingScheduler`)

매일 지정 시각에 **당일** workout publish를 1회 시도한다. **기본 비활성**이며, 오직 `WorkoutPublishApplicationService.publish(today)`만 호출한다(mapper/renderer/publisher/client 직접 호출 없음).

| 환경변수 | property | 기본값 | 의미 |
|---|---|---|---|
| `WORKOUT_PUBLISHING_ENABLED` | `running-ai.workout-publishing.enabled` | `false` | **master**: Spring이 Intervals workout을 쓸 수 있는가 (수동 POST + scheduler 공통) |
| `WORKOUT_PUBLISHING_SCHEDULER_ENABLED` | `...scheduler.enabled` | `false` | 자동 daily trigger를 실행할 것인가 (꺼져 있으면 scheduler bean 자체가 없다) |
| `WORKOUT_PUBLISHING_SCHEDULER_CRON` | `...scheduler.cron` | `0 0 5 * * *` | Spring 6-field cron (기술적 기본값이며 운영 정책이 아니다) |
| `WORKOUT_PUBLISHING_SCHEDULER_ZONE` | `...scheduler.zone` | `Asia/Seoul` | cron 실행 시간대이자 "오늘"을 정하는 시간대 (서버 OS timezone에 의존하지 않는다) |

- master=false/scheduler=false → 모든 publish 차단 · master=true/scheduler=false → 수동 POST만 · 둘 다 true → 수동 + 자동. scheduler가 master를 우회하지 않는다(scheduler=true인데 master=false면 tick마다 `WORKOUT_PUBLISHING_DISABLED`로 거부되고 로그만 남는다; 기동은 실패하지 않는다).
- 날짜 = 설정된 zone 기준 `LocalDate.now`(공용 `Clock`). 예: UTC 2026-10-01 16:30 = Seoul 2026-10-02 01:30 → `publish(2026-10-02)`.
- 결과 `CREATED` / `UPDATED` / `NO_CHANGE`는 모두 정상(수동 publish 뒤의 05:00 tick이 `NO_CHANGE`여도 오류가 아니다). 같은 날짜가 이미 실행 중이면(6A per-date single-flight 재사용, 별도 lock 없음) 그 tick은 skip 로그 후 종료.
- **retry 없음**(실패는 로그 후 해당 tick 종료), **missed-run catch-up 없음**(05:00에 서버가 꺼져 있었다면 보충하지 않는다), 실행 이력 DB 저장 없음, 알림 없음.
- REST day / 빈 workout은 현재 contract(`INTERVALS_EMPTY_WORKOUT`)대로 오류 로그로 남는다(`SKIPPED_REST_DAY` 같은 새 의미를 만들지 않았다).
- 로그에는 date / operation / verified / error code / exception 종류만 남는다(키, Authorization, workout 본문, 생리 수치 없음).
- **운영 활성화 순서**: ① legacy Scheduled Task 조회 → ② legacy writer(create-today-workout / command-channel) disable → ③ legacy 수동 실행 중단 → ④ Spring 최신 main 반영 → ⑤ 재기동 → ⑥ `WORKOUT_PUBLISHING_ENABLED=true` → ⑦ `WORKOUT_PUBLISHING_SCHEDULER_ENABLED=false` 유지 → ⑧ 수동 POST smoke test → ⑨ Intervals/Garmin 정상 확인 → ⑩ 그 뒤에만 scheduler=true. 두 스위치를 한 번에 올리지 않는다.

#### MCP `publish_workout` (Phase 6C, 기본 비활성)

ChatGPT 같은 MCP client용 adapter다. Spring AI **1.1.8**(Spring Boot 3.5 호환 1.x 라인; 2.x는 Boot 4 전용이라 사용하지 않음) + MCP Java SDK 0.18.3, **Streamable HTTP(stateless)** `POST /mcp`.

- 스위치: `RUNNINGAI_MCP_ENABLED`(`running-ai.mcp.enabled`) 기본 **false**. 이 값이 Spring AI MCP 서버 자체(`spring.ai.mcp.server.enabled`, 라이브러리 기본은 true)도 켜고 끈다. 꺼져 있으면 서버·transport·tool이 없고 `/mcp`는 404.
- tool은 **`publish_workout` 하나**뿐(resources/prompts/completions, annotation scanning 비활성). 입력 `{"date":"YYYY-MM-DD"}`만 허용(자연어 날짜, 추가 인자·credential 거부 → `INVALID_DATE`/`INVALID_ARGUMENT`).
- 동작: `WorkoutPublishApplicationService.publish(date)` 1회 호출(REST loopback 아님) → 성공 `{"success":true,"date":…,"operation":"CREATED|UPDATED|NO_CHANGE","verified":true,"intent":…,"stepCount":…}`, 실패 `isError=true` + `{"success":false,"code":…,"message":…}`(6A와 같은 코드: `WORKOUT_PUBLISHING_DISABLED`, `WORKOUT_PUBLISH_ALREADY_RUNNING`, `INTERVALS_*`). retry 없음, MCP 전용 lock 없음(6A per-date single-flight 재사용).
- master switch(`WORKOUT_PUBLISHING_ENABLED`)를 우회하지 못한다: 실제 publish에는 MCP=true **그리고** master=true가 모두 필요. scheduler와는 독립.
- `/mcp`에는 인증이 없다. 공용 인터넷에 노출하지 않는다(tunnel/port forwarding/reverse proxy 미구성). 실제 ChatGPT 연결은 아직 하지 않았고, **RunningAI MCP 준비 ≠ ChatGPT 계정/워크스페이스의 MCP write 사용 가능 여부**다.
- 메인 PC 순서: legacy writer 확인·disable → main pull → 재기동 → master=true(scheduler·MCP는 false) → REST 수동 smoke → Intervals/Garmin 확인 → (보안된) MCP transport 연결 → MCP=true → MCP tool smoke → 마지막에 필요하면 scheduler=true. 스위치는 하나씩.

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
