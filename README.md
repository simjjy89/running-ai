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
- Garmin ingestion core (offline, fixture 기반): raw 저장 → mapping → Activity upsert → reprocess
- 통합 테스트 (H2 + Flyway, 외부 서비스 불필요)

### 미구현 (예정)

Garmin 로그인 / 인증 / 네트워크 client / 실제 데이터 fetch, Intervals.icu API 호출, 훈련 자동 생성, Garmin structured
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

Garmin activity ingestion은 현재 **offline, fixture 기반 pipeline**으로만 구현되어 있다.
실제 Garmin 인증 / 네트워크 연동은 아직 구현되지 않았다.

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
- 입력 단위(fixture 기준): `duration` milliseconds → `durationSeconds`, `distance` metres,
  `startTime` ISO-8601 offset 포함 → UTC `Instant`. 실제 Garmin payload 형태는 Phase 3B에서 확인한다.
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

## Repository 구조

```text
running-ai/
├─ server/              Spring Boot 백엔드
├─ docs/work-orders/    작업지시서 및 구현 기록
├─ scripts/dev/         개발용 스크립트 (validate-server.ps1)
├─ .claude/             Claude Code project skills / hooks
├─ CLAUDE.md            Claude Code 프로젝트 규칙
├─ docker-compose.yml   로컬 PostgreSQL
├─ .env.example
└─ README.md
```
