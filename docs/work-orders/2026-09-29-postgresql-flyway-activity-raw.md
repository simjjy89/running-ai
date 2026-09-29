# 2026-09-29 — PostgreSQL Docker Compose / Flyway / Activity Raw Payload

| 항목 | 내용 |
|------|------|
| 날짜 | 2026-09-29 |
| 작업 | 2차. DB 기반 고정: PostgreSQL 개발환경, Flyway migration, `activity_raw` 저장 구조 |
| 상태 | 완료 |
| 커밋 | `feat: add PostgreSQL migrations and raw activity storage` |
| 지시서 원문 | [2026-09-29-postgresql-flyway-activity-raw-instruction.md](2026-09-29-postgresql-flyway-activity-raw-instruction.md) |
| 이전 작업 | [2026-09-29-running-ai-spring-server-bootstrap.md](2026-09-29-running-ai-spring-server-bootstrap.md) |

---

## 1. 작업 목적

향후 Garmin Activity ingestion을 안정적으로 구현하기 전에 데이터베이스 기반을 고정한다.

1. PostgreSQL Docker Compose 개발환경
2. Flyway schema migration (Hibernate `ddl-auto: update` 제거)
3. 외부 원본 payload 보존용 `activity_raw` 저장 구조

Garmin / Intervals.icu 실제 연동은 하지 않는다.

## 2. 기존 상태

- `main` = `bb7f2b7` (1차 bootstrap `77899b9` + README 한글화 + 지시서 원문 저장). 작업 트리 clean.
- Spring Boot 3.5.16 / Java 21 / Gradle 8.14.5, `local`(PostgreSQL, `ddl-auto: update`) / `test`(H2, `create-drop`) profile.
- Athlete / Activity entity, Activity API, 테스트 11개.
- 작업 PC: 외부 Windows PC. **Docker / podman 없음**, psql 없음. Maven Central 접근 가능.

## 3. Architecture 결정사항

| 주제 | 결정 | 이유 |
|------|------|------|
| PostgreSQL image | `postgres:17` | 작업지시서 예시와 동일한 안정 major. RC/beta 제외. 이 PC에서는 image pull을 못 하므로 compose 파일 자체는 실행 검증하지 못함 (7장) |
| Compose 위치 | repository root `docker-compose.yml` | `server/`와 독립된 인프라. root `.env`를 compose와 서버가 함께 사용 |
| Flyway | `flyway-core` + `flyway-database-postgresql` (Boot BOM 11.7.2) | Flyway 10+는 DB별 모듈 필요 |
| Hibernate | 전 profile `ddl-auto: validate` | Flyway = schema 생성/변경, Hibernate = mapping 검증 |
| test profile | H2(PostgreSQL mode) + **Flyway 실제 적용** + `validate` | Docker 없음 → Testcontainers 불가. `create-drop` 제거. `DB_CLOSE_DELAY=-1`로 context 간 DB 유지 |
| JSON 타입 차이 | Flyway placeholder `${json_type}`: PostgreSQL `JSONB`, H2 `JSON` | migration SQL을 한 벌로 유지. H2에는 JSONB가 없음 |
| Migration 분리 | `V1 athlete`, `V2 activity`, `V3 activity_raw` | 이력 가독성. 운영 데이터가 없으므로 baseline 부담 없음 |
| `activity_raw.activity_id` | **Option B: NULLABLE FK** | raw-first ingestion. 정규화 실패 시에도 원본 보존. orphan은 `(external_source, external_id)`로 언제든 재연결 가능 |
| Raw 중복 정책 | `1 external activity = 1 raw row`. `(external_source, external_id)` unique. 재수집 시 `payload`, `fetched_at` update | 현재 목적(재처리, 디버깅)에 최신 snapshot 하나면 충분. version history는 추후 필요 시 별도 테이블 |
| payload Java 타입 | `JsonNode` + `@JdbcTypeCode(SqlTypes.JSON)` | Hibernate 6 표준 기능. 외부 JSON type 라이브러리 없음. Jackson과 자연스럽게 연결 |
| `activity_raw` 컬럼 | id, activity_id, external_source, external_id, payload, fetched_at, created_at | 지시서 그대로. `updated_at`은 `fetched_at`이 갱신 시점을 나타내므로 두지 않음 |
| Raw 자동 연결 | `saveOrUpdate` 시 같은 `(source, externalId)`의 Activity가 있으면 link | 별도 호출 없이 정합성 유지. 반대 방향(Activity 생성 시 raw 연결)은 ingestion adapter 책임으로 남김 |
| FK 추가 | `activity.athlete_id → athlete.id`, `activity_raw.activity_id → activity.id` | 1차에서는 Hibernate DDL이라 FK가 없었음. Entity는 그대로(`Long athleteId`) |
| Index | `activity(athlete_id, started_at)`, `activity(started_at)` | 목록 endpoint(`started_at desc`)와 향후 athlete별 기간 조회. unique constraint가 index 역할을 하는 컬럼에는 중복 index 없음 |
| Timestamp | `Instant`(UTC) ↔ `TIMESTAMP WITH TIME ZONE` | 1차 정책 유지 |
| `.env` import | `local` profile에서 `optional:file:../.env[.properties]` import | PowerShell은 `.env`를 자동으로 읽지 않으므로 fallback 제공. 실제 환경변수가 우선 |
| API | Activity Raw API 없음 | ingestion 내부용. service/repository 테스트로 검증 |

## 4. DB Schema

```text
athlete
  id                 BIGINT IDENTITY PK
  name               VARCHAR(100) NOT NULL   uk_athlete_name
  timezone           VARCHAR(64)  NOT NULL
  created_at         TIMESTAMPTZ  NOT NULL
  updated_at         TIMESTAMPTZ  NOT NULL

activity
  id                 BIGINT IDENTITY PK
  athlete_id         BIGINT NOT NULL         fk_activity_athlete → athlete(id)
  external_source    VARCHAR(32)  NOT NULL   ┐ uk_activity_external
  external_id        VARCHAR(100) NOT NULL   ┘
  activity_type      VARCHAR(32)  NOT NULL
  started_at         TIMESTAMPTZ  NOT NULL   ix_activity_started_at, ix_activity_athlete_started(athlete_id, started_at)
  duration_seconds   INTEGER      NOT NULL
  distance_meters    DOUBLE PRECISION
  average_heart_rate INTEGER
  max_heart_rate     INTEGER
  created_at         TIMESTAMPTZ  NOT NULL
  updated_at         TIMESTAMPTZ  NOT NULL

activity_raw
  id                 BIGINT IDENTITY PK
  activity_id        BIGINT NULL             fk_activity_raw_activity → activity(id)
  external_source    VARCHAR(32)  NOT NULL   ┐ uk_activity_raw_external
  external_id        VARCHAR(100) NOT NULL   ┘
  payload            JSONB        NOT NULL   (H2: JSON)
  fetched_at         TIMESTAMPTZ  NOT NULL
  created_at         TIMESTAMPTZ  NOT NULL
```

## 5. Migration 목록

| Version | 파일 | 내용 |
|---------|------|------|
| 1 | `V1__create_athlete.sql` | athlete 테이블, `uk_athlete_name` |
| 2 | `V2__create_activity.sql` | activity 테이블, FK, `uk_activity_external`, index 2개 |
| 3 | `V3__create_activity_raw.sql` | activity_raw 테이블, nullable FK, `uk_activity_raw_external`, `${json_type}` placeholder |

원칙: **이미 적용된 migration 파일은 수정하지 않는다. 변경은 새 version으로 추가한다.** (README에도 명시)

## 6. 구현 내역

```text
docker-compose.yml                                   (신규) postgres:17, named volume, healthcheck
.env.example                                         (갱신) POSTGRES_* + DB_* 정리
README.md                                            (갱신) Database / Local Database / migration 정책
server/build.gradle                                  (갱신) flyway-core, flyway-database-postgresql
server/src/main/resources/application.yml            (갱신) ddl-auto validate, flyway placeholder JSONB
server/src/main/resources/application-local.yml      (갱신) ddl-auto update 제거, .env import, 기본 DB running_ai
server/src/main/resources/application-test.yml       (갱신) create-drop 제거, Flyway 적용, placeholder JSON
server/src/main/resources/db/migration/V1..V3        (신규)
server/src/main/java/com/runningai/activity/ActivityRaw.java             (신규) entity, JsonNode payload
server/src/main/java/com/runningai/activity/ActivityRawRepository.java   (신규) findByExternalSourceAndExternalId
server/src/main/java/com/runningai/activity/ActivityRawService.java      (신규) saveOrUpdate, find
server/src/main/java/com/runningai/activity/ActivityRepository.java      (갱신) findByExternalSourceAndExternalId 추가
server/src/test/java/com/runningai/SchemaMigrationTest.java              (신규)
server/src/test/java/com/runningai/activity/ActivityRawServiceTest.java  (신규)
```

기존 Controller / DTO / 오류 응답은 변경하지 않았다 (API contract 동일).

## 7. Docker Compose 사용법

```powershell
docker compose up -d
docker compose ps            # running-ai-postgres 가 healthy 인지 확인
docker compose down          # 데이터 유지
docker compose down -v       # volume 삭제
```

기본값: DB / user `running_ai`, port 5432. 비밀번호 등은 root `.env`(`POSTGRES_PASSWORD`, `DB_PASSWORD`)로 설정.
서버 실행: `$env:DB_URL / DB_USERNAME / DB_PASSWORD` 설정 후 `server`에서 `.\gradlew bootRun`.
`.env`에 `DB_*`를 적어 두면 `local` profile이 fallback으로 읽는다.

**이 PC에는 Docker가 없어 `docker compose up`은 실행하지 못했다.** compose 파일은 공식 image 옵션만 사용했고,
실제 PostgreSQL 검증은 8장의 방식으로 대체했다.

## 8. 테스트 결과

### H2 (기본 `test` profile)

```text
cd server
.\gradlew clean test
→ BUILD SUCCESSFUL, 20 tests, 20 passed, 0 failed
```

| 테스트 클래스 | 케이스 |
|---------------|--------|
| `RunningAiApplicationTests` (1) | context load + 기본 athlete 생성 (Flyway 이후 initializer 정상 동작) |
| `HealthApiTest` (2) | 기존 |
| `ActivityApiTest` (8) | 기존 회귀 (create / get / 404 / list / 409 중복 / 다른 source 허용 / validation / enum) |
| `SchemaMigrationTest` (4) | migration 1,2,3 SUCCESS + pending 없음, 테이블 존재, payload 컬럼 JSON 타입, DB unique constraint 강제 |
| `ActivityRawServiceTest` (5) | JSON payload 저장·재조회 동일, 재수집 시 payload/fetchedAt update(행 1개 유지, createdAt 유지), 기존 Activity 자동 link, 다른 source 동일 externalId 분리, DB unique constraint 강제 |

Hibernate `validate`가 context 기동 시 실행되므로 entity ↔ migration 불일치는 모든 테스트를 실패시킨다.

### 실제 PostgreSQL 17.11 검증

Docker 대신 Maven Central의 `io.zonky.test.postgres:embedded-postgres-binaries-windows-amd64:17.11.0`에서
PostgreSQL 17.11 Windows 바이너리를 받아 scratchpad에 `initdb` / `pg_ctl start`로 로컬 인스턴스를 띄웠다
(repository에는 아무 것도 추가하지 않음). `running_ai`(서버용), `running_ai_test`(테스트용) DB 생성.

**전체 테스트를 PostgreSQL에서 재실행**
(`SPRING_APPLICATION_JSON`으로 datasource와 `json_type=JSONB` override):

```text
.\gradlew test --rerun-tasks  (datasource = jdbc:postgresql://localhost:5432/running_ai_test)
→ BUILD SUCCESSFUL, 20 tests, 20 passed, 0 failed
```

**local profile 실기동** (`DB_URL / DB_USERNAME / DB_PASSWORD` 환경변수):

```text
Flyway: Database: jdbc:postgresql://localhost:5432/running_ai (PostgreSQL 17.11)
        Successfully validated 3 migrations
        Creating Schema History table "public"."flyway_schema_history"
        Successfully applied 3 migrations to schema "public", now at version v3
Started RunningAiApplication in 11.4 seconds
Default athlete ready: id=1, name=default, timezone=Asia/Seoul

GET  /api/v1/health                 → 200 {"status":"UP","application":"running-ai"}
GET  /actuator/health               → 200 {"status":"UP"}
POST /api/v1/activities             → 201, Location: /api/v1/activities/1
POST 동일 GARMIN/188081596          → 409 DUPLICATE_ACTIVITY
GET  /api/v1/activities/1           → 200
GET  /api/v1/activities/999         → 404 ACTIVITY_NOT_FOUND
GET  /api/v1/activities             → 200, 1건
```

**DB 내부 확인** (JDBC로 `information_schema` / `pg_catalog` 조회):

```text
flyway_schema_history: 1 create athlete | 2 create activity | 3 create activity raw  (type SQL, success t)
tables: activity, activity_raw, athlete, flyway_schema_history

activity_raw.payload      jsonb (udt_name jsonb) NOT NULL
activity_raw.activity_id  bigint NULL
activity_raw.fetched_at   timestamp with time zone
activity.started_at       timestamp with time zone
activity.distance_meters  double precision
activity.duration_seconds integer

constraints: athlete_pkey, uk_athlete_name,
             activity_pkey, fk_activity_athlete, uk_activity_external,
             activity_raw_pkey, fk_activity_raw_activity, uk_activity_raw_external
indexes:     ix_activity_athlete_started, ix_activity_started_at (+ PK / unique index)

athlete:  1 | default | Asia/Seoul
activity: 1 | GARMIN | 188081596 | 2026-09-29 06:30:00+09
```

## 9. 알려진 제한사항

- **Docker Compose 미검증**: 이 PC에 Docker가 없다. 메인 PC에서 `docker compose up -d` → `docker compose ps` healthy 확인 필요.
- **테스트 기본 DB는 H2**: Testcontainers는 Docker 필요로 보류. H2 차이: `JSONB` 대신 `JSON`(placeholder로 흡수), timestamp 소수부 microsecond 반올림. 그 외 migration SQL은 동일하게 적용되며 실제 PostgreSQL에서도 전체 테스트가 통과함을 확인했다.
- **local profile 기본 비밀번호 없음**: `DB_PASSWORD` 미설정 시 인증 실패. 의도된 동작 (yml 하드코딩 금지).
- **Entity의 `@UniqueConstraint` / `@Index` 어노테이션**은 이제 문서 역할만 한다 (schema는 Flyway). migration과 이름을 맞춰 두었다.
- `activity_raw.activity_id`에 별도 index 없음. raw → activity 조회 패턴이 생기면 새 migration으로 추가.
- Raw payload version history 없음 (최신 snapshot 1개 정책).
- validation 메시지 locale은 여전히 JVM 기본(ko_KR).
- JDK 21로 `JAVA_HOME` 지정 필요, GitHub redirect PKIX 문제 등 환경 메모는 1차 문서 7장 참고.

## 10. 다음 단계 제안

1. Garmin ingestion adapter (`integration/garmin`): fetch → `ActivityRawService.saveOrUpdate` → mapper → `Activity`
2. Garmin credential / session 전략 (환경변수, token 저장 위치)
3. raw payload → normalized Activity mapper (재처리 command 포함)
4. ingestion idempotency (raw 갱신 시 Activity 갱신 정책, `updated_at`)
5. Activity pagination / 기간·타입 filter
6. 메인 PC에서 `docker compose` 실검증 및 Testcontainers 전환 검토
