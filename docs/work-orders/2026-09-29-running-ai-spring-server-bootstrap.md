# 2026-09-29 — RunningAI Spring Boot 서버 초기 구축 (Bootstrap)

| 항목 | 내용 |
|------|------|
| 날짜 | 2026-09-29 |
| 작업 | `server/` Spring Boot backend foundation 신규 구축 |
| 상태 | 완료 |
| 커밋 | `feat: bootstrap RunningAI Spring Boot server` |
| 지시서 원문 | [2026-09-29-running-ai-spring-server-bootstrap-instruction.md](2026-09-29-running-ai-spring-server-bootstrap-instruction.md) |

---

## 1. 작업 목적

RunningAI는 현재 메인 Windows PC에서 PowerShell / Node.js / 파일 기반으로 동작하는
개인 러닝 자동화 프로젝트다. 다음 기능들을 안정적인 서버 애플리케이션으로 단계적으로
이전하기 위한 **Spring Boot 기반 backend foundation**을 새로 만든다.

- Garmin 활동 데이터 수집 및 저장
- 러닝 / 실내 러닝 / 실내 자전거 데이터 관리
- 훈련 상태 분석, 훈련 생성
- Intervals.icu 연동, Garmin 훈련 전송 파이프라인
- 운동 후 상세 리포트, 주간 / 월간 리포트
- Scheduler 기반 자동화

이번 작업 범위는 **안정적인 기반 구조만** 만드는 것이다. 기존 PowerShell / Node.js
기능은 이전하지 않으며, 외부 PC에는 해당 코드가 없으므로 추측해서 재현하지 않는다.

## 2. 작업 전 상태

- GitHub `simjjy89/running-ai` repository 존재, **remote / local 모두 commit 0개** (빈 repo)
- 작업 PC: 외부 Windows 11 PC. JDK 21 (`C:\Program Files\Java\jdk-21`) 설치되어 있으나
  `JAVA_HOME`은 JDK 8을 가리킴. 로컬 Gradle 6.8.3 설치. Docker / PostgreSQL 없음.
- 회사 프록시(KONA-PROXY)가 `github.com` TLS를 가로채므로 Java에서 GitHub 직접 다운로드 불가.
  Maven Central / Gradle plugin portal은 Java에서 정상 접근 가능.

## 3. 결정사항

| 주제 | 결정 | 이유 |
|------|------|------|
| Spring Boot | **3.5.16** | 작업 시점 Maven Central 기준 최신 안정 3.x. RC/M/SNAPSHOT 제외 |
| Gradle | **8.14.5** (wrapper) | Boot 3.5 공식 지원 범위 내 최신 8.x. `distributionSha256Sum` 고정 |
| Java | 21 (Gradle toolchain) | 작업지시서 요구. 회사 Java 8 프로젝트와 분리 |
| Package | `com.runningai` + feature 패키지 (`common`, `athlete`, `activity`) | 향후 `workout`, `training`, `integration`, `reporting`, `scheduler` 추가 용이. 빈 패키지는 만들지 않음 |
| Architecture | Controller → Service → Domain(Entity) → Repository | Hexagonal 강제하지 않음. 과도한 추상화 회피 |
| Lombok | 사용 안 함 | 지시서 원칙. DTO는 Java record, Entity는 명시적 getter + 생성자 |
| Athlete 소유 | 요청에 athleteId 없음 → 설정된 **기본 athlete**에 귀속 | single-user 서비스. 기동 시 `ApplicationRunner`가 기본 athlete 자동 생성 (`default` / `Asia/Seoul`, 환경변수로 변경 가능) |
| 시간 저장 | 요청은 `OffsetDateTime`(예: `+09:00`), 저장/응답은 UTC `Instant` | DB 저장값 일관성. 표시용 timezone은 Athlete가 보유 |
| distanceMeters 타입 | `Double` (nullable) | Garmin 거리는 소수 m 단위. 실내 자전거 등 거리 없는 경우 허용 |
| 중복 방지 | Service 사전 검사(`existsBy…`) + DB unique constraint `(external_source, external_id)` | 반복 수집 시 중복 저장 방지. race 시 `DataIntegrityViolationException` → 409 |
| 오류 응답 | `{code, message, timestamp, errors?}` | validation 시에만 `errors` 배열 추가. 공통 success wrapper 없음 |
| 목록 조회 | 단순 list, `startedAt DESC` | pagination은 추후 |
| Schema | Hibernate `ddl-auto` (local: `update`, test: `create-drop`) | bootstrap 단계 한정. Flyway 도입 예정 |
| Test DB | H2 in-memory, `MODE=PostgreSQL` | Docker 없이 테스트 가능. Testcontainers는 추후 선택 |
| Profile | `local`(기본, PostgreSQL), `test`(H2) | `spring.profiles.default: local` |

## 4. 구현 내역

### 4.1 Repository 구조

```text
running-ai/
├─ server/
│  ├─ build.gradle, settings.gradle
│  ├─ gradlew, gradlew.bat, gradle/wrapper/
│  └─ src/
│     ├─ main/java/com/runningai/
│     │  ├─ RunningAiApplication.java
│     │  ├─ common/config/     JpaAuditingConfig, RunningAiProperties
│     │  ├─ common/exception/  GlobalExceptionHandler, ErrorResponse,
│     │  │                     ResourceNotFoundException, DuplicateResourceException
│     │  ├─ common/health/     HealthController
│     │  ├─ athlete/           Athlete, AthleteRepository, AthleteService
│     │  └─ activity/          Activity, ActivityType, ExternalSource, ActivityRepository,
│     │                        ActivityService, ActivityController,
│     │                        ActivityCreateRequest, ActivityResponse
│     ├─ main/resources/       application.yml, application-local.yml, application-test.yml
│     └─ test/java/com/runningai/
│        ├─ RunningAiApplicationTests
│        ├─ common/health/HealthApiTest
│        └─ activity/ActivityApiTest
├─ docs/work-orders/2026-09-29-running-ai-spring-server-bootstrap.md  (이 문서)
├─ .env.example
├─ .gitattributes
├─ .gitignore
└─ README.md
```

### 4.2 Domain

**Athlete** (`athlete` 테이블): `id`, `name`(unique), `timezone`(ZoneId 검증), `createdAt`, `updatedAt`

**Activity** (`activity` 테이블): `id`, `athleteId`, `externalSource`(GARMIN | INTERVALS_ICU | MANUAL),
`externalId`, `activityType`(RUN | TREADMILL_RUN | INDOOR_CYCLING), `startedAt`(UTC),
`durationSeconds`, `distanceMeters`, `averageHeartRate`, `maxHeartRate`, `createdAt`, `updatedAt`

- Unique constraint `uk_activity_external (external_source, external_id)`
- Index `ix_activity_athlete_started (athlete_id, started_at)`
- Audit: Spring Data JPA Auditing (`@CreatedDate` / `@LastModifiedDate`)
- DB PK와 외부 ID 분리 (외부 ID를 PK로 사용하지 않음)

### 4.3 API

| Method | Path | 응답 |
|--------|------|------|
| GET | `/api/v1/health` | `200 {"status":"UP","application":"running-ai"}` |
| POST | `/api/v1/activities` | `201` + `Location` + ActivityResponse / `400` VALIDATION_ERROR·INVALID_REQUEST / `409` DUPLICATE_ACTIVITY |
| GET | `/api/v1/activities/{id}` | `200` ActivityResponse / `404` ACTIVITY_NOT_FOUND |
| GET | `/api/v1/activities` | `200` ActivityResponse[] (startedAt DESC) |
| GET | `/actuator/health` | Spring Actuator (health, info만 노출) |

### 4.4 Validation (ActivityCreateRequest)

`externalSource` NotNull · `externalId` NotBlank, max 100 · `activityType` NotNull ·
`startedAt` NotNull · `durationSeconds` NotNull, ≥ 0 · `distanceMeters` ≥ 0 ·
`averageHeartRate` ≥ 0 · `maxHeartRate` ≥ 0

### 4.5 Global Exception Handler (`@RestControllerAdvice`)

| 예외 | HTTP | code |
|------|------|------|
| `MethodArgumentNotValidException` | 400 | `VALIDATION_ERROR` (+ `errors[]`) |
| `HttpMessageNotReadableException`, `MethodArgumentTypeMismatchException` | 400 | `INVALID_REQUEST` |
| `ResourceNotFoundException` | 404 | 예외가 가진 code (예: `ACTIVITY_NOT_FOUND`) |
| `NoResourceFoundException` | 404 | `NOT_FOUND` |
| `DuplicateResourceException` | 409 | 예외가 가진 code (예: `DUPLICATE_ACTIVITY`) |
| `DataIntegrityViolationException` | 409 | `DATA_CONFLICT` |
| `Exception` | 500 | `INTERNAL_SERVER_ERROR` |

### 4.6 Configuration

- `application.yml`: 앱 이름, 기본 profile `local`, `open-in-view=false`, Hibernate JDBC timezone UTC,
  Jackson non-null, `running-ai.default-athlete.{name,timezone}`, actuator health/info 노출
- `application-local.yml`: PostgreSQL. `DB_URL` / `DB_USERNAME` / `DB_PASSWORD` 환경변수
  (기본값 `jdbc:postgresql://localhost:5432/runningai`, `runningai`, 빈 비밀번호)
- `application-test.yml`: H2 in-memory PostgreSQL mode, `create-drop`
- `.env.example`: `DB_*`, `RUNNING_AI_ATHLETE_*`, `GARMIN_*`(미사용, 예약), `INTERVALS_API_KEY`(미사용, 예약).
  실제 값 없음
- Garmin credential은 저장/구현하지 않음

### 4.7 Secrets / .gitignore

`.env`, `.env.*`(단 `.env.example` 허용), 인증서/키, `credentials*`, `secrets*`, `tokens*`,
build 산출물, `.gradle/`, IDE 설정, 로그, 로컬 DB 파일 제외. `gradle-wrapper.jar`는 예외 허용.
`git check-ignore`로 확인 완료.

## 5. 테스트 결과

```text
cd server
.\gradlew clean test
→ BUILD SUCCESSFUL, 11 tests, 11 passed, 0 failed
```

| 테스트 클래스 | 케이스 |
|---------------|--------|
| `RunningAiApplicationTests` | context load + 기본 athlete 자동 생성 |
| `HealthApiTest` | health UP, 미존재 경로 404 error body |
| `ActivityApiTest` | create(201, Location, UTC 변환), get, not found 404, list 최신순, 중복 409, 다른 source 동일 externalId 허용, validation 400 (7개 필드), 알 수 없는 enum 400 |

테스트 전략: MockMvc + 실제 Service/Repository/H2 통합 테스트 1계층으로 통일
(Controller / Service / Repository 개별 중복 테스트 없음). `@Transactional` rollback으로 테스트 간 격리.

## 6. 실행 검증 (smoke test)

이 PC에는 PostgreSQL이 없으므로 `local` profile 실행은 검증하지 못했다.
대신 H2 `test` profile로 서버를 기동해 실제 HTTP 호출을 확인했다.

```text
.\gradlew bootRun --args='--spring.profiles.active=test'

GET  /api/v1/health          → 200 {"status":"UP","application":"running-ai"}
GET  /actuator/health        → 200 {"status":"UP"}
POST /api/v1/activities      → 201, Location: http://localhost:8080/api/v1/activities/1
POST (동일 GARMIN/188081596) → 409 DUPLICATE_ACTIVITY
POST (빈 externalId, -1 초)  → 400 VALIDATION_ERROR, errors 5건
GET  /api/v1/activities/1    → 200
GET  /api/v1/activities/999  → 404 ACTIVITY_NOT_FOUND
GET  /api/v1/activities      → 200, 1건
```

참고: validation 메시지는 JVM 기본 locale(ko_KR)로 출력됨 ("널이어서는 안됩니다" 등).
영문 고정이 필요하면 `spring.web.locale` 또는 `LocaleResolver` 설정을 추가한다.

## 7. 빌드 환경 메모 (외부 PC)

- `gradlew` 실행 시 `JAVA_HOME`이 JDK 8을 가리키므로 **JDK 21로 지정 필요**:
  `$env:JAVA_HOME = "C:\Program Files\Java\jdk-21"`
- Gradle 배포판 다운로드는 `services.gradle.org → github.com`으로 redirect되는데,
  회사 프록시의 TLS 가로채기 CA를 JDK가 신뢰하지 않아 wrapper 자동 다운로드가 PKIX 오류로 실패.
  curl(Windows schannel)로 zip을 받아 sha256 검증 후 `~/.gradle/wrapper/dists/gradle-8.14.5-bin/<hash>/`에
  수동 배치해 해결. 프록시가 없는 메인 PC에서는 wrapper가 자동으로 받는다.
- Maven Central / plugins.gradle.org 의존성 해석은 정상.

## 8. 이번 작업에서 하지 않은 것

Garmin 실제 로그인·scraping, Intervals.icu API 호출, 훈련 자동 생성, Garmin structured workout / cue 생성,
PowerShell scheduler 이전, 주간/월간 리포트, AI/LLM 연동, ntfy 알림, Docker Compose, Flyway, pagination,
기존 PowerShell / Node.js 코드 병합.

## 9. 다음 작업 후보

1. PostgreSQL Docker Compose (`docker-compose.yml` + `.env` 연동) 및 `local` profile 실기동 검증
2. Flyway 도입 (Hibernate `ddl-auto` 제거, 초기 스키마 V1)
3. Garmin Activity ingestion adapter (`integration/garmin`, credential은 환경변수)
4. Activity raw data schema (Garmin 원본 JSON / lap / split 보관)
5. Activity 목록 pagination + 기간/타입 필터
6. Training load domain
7. Workout domain
8. Intervals.icu adapter (`integration/intervals`)
9. 메인 PC에서 기존 PowerShell / Node.js / docs 병합
