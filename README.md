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
                        PostgreSQL
```

서버 내부는 도메인별 패키지(`athlete`, `activity`, 이후 `workout`, `training`,
`integration`, `reporting`, `scheduler`)로 나누고, Controller → Service → Domain → Repository의
단순한 계층 구조를 따른다. 외부 시스템 연동은 `integration/*` 아래 어댑터로 분리해
도메인이 외부 시스템에 직접 의존하지 않도록 한다.

## 현재 상태

### 구현 완료 (`server/`)

- Spring Boot 3.5 / Java 21 / Gradle 프로젝트, `local` / `test` profile 분리
- `Athlete`, `Activity` JPA entity와 audit 필드(`createdAt`, `updatedAt`)
- 기동 시 기본 athlete 자동 생성 (이름 / timezone 설정 가능, 기본값 `default` / `Asia/Seoul`)
- Activity API: 등록, 단건 조회, 목록 조회(최신순)
- Request validation과 구조화된 오류 응답
- Global exception handler (`VALIDATION_ERROR`, `INVALID_REQUEST`, `ACTIVITY_NOT_FOUND`,
  `DUPLICATE_ACTIVITY`, `DATA_CONFLICT`, `INTERNAL_SERVER_ERROR`)
- `externalSource + externalId` 중복 방지 (service 검사 + DB unique constraint)
- Health endpoint `GET /api/v1/health`, Spring Actuator `/actuator/health`
- 통합 테스트 (H2, 외부 서비스 불필요)

### 미구현 (예정)

Garmin 로그인 / Connect 접근, Intervals.icu API 호출, 훈련 자동 생성, Garmin structured
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

## Server

- Java 21
- Spring Boot 3.5 (Web, Validation, Data JPA, Actuator)
- Gradle 8.14 (wrapper 포함)
- 로컬 / 운영: PostgreSQL, 테스트: H2 (PostgreSQL mode)

## Development

모든 명령은 `server/`에서 실행한다. Gradle wrapper는 `JAVA_HOME`이 JDK 21을 가리키거나
`PATH`에 JDK 21이 있어야 한다.

### 테스트 실행

```powershell
cd server
.\gradlew clean test
```

테스트는 `test` profile(in-memory H2)로 실행되며 DB나 네트워크 서비스가 필요 없다.

### 서버 실행

기본 profile은 `local`이며 PostgreSQL이 필요하다. 접속 정보는 환경변수로 전달한다
(repository root의 `.env.example` 참고):

```powershell
$env:DB_URL      = "jdbc:postgresql://localhost:5432/runningai"
$env:DB_USERNAME = "runningai"
$env:DB_PASSWORD = "..."
cd server
.\gradlew bootRun
```

기동 후:

```powershell
Invoke-RestMethod http://localhost:8080/api/v1/health
```

PostgreSQL 없이 확인하려면 in-memory `test` profile로 실행한다:

```powershell
.\gradlew bootRun --args='--spring.profiles.active=test'
```

### Profile

| Profile | Database                | Schema        | 용도                    |
|---------|-------------------------|---------------|-------------------------|
| `local` | PostgreSQL (환경변수)   | `update`      | 기본값, 로컬 개발       |
| `test`  | H2 in-memory, PG mode   | `create-drop` | 자동화 테스트           |

Schema 관리는 bootstrap 단계에서만 Hibernate DDL에 의존한다. Schema를 본격적으로
사용하기 전에 migration 도구(Flyway) 도입을 계획하고 있다.

### 설정 및 민감정보

민감정보는 commit하지 않는다. `.env`, credential, token, IDE / build 산출물은
git-ignore되며 빈 placeholder만 담긴 `.env.example`만 추적한다. 지원하는 환경변수:

```text
DB_URL, DB_USERNAME, DB_PASSWORD
RUNNING_AI_ATHLETE_NAME, RUNNING_AI_ATHLETE_TIMEZONE
GARMIN_USERNAME, GARMIN_PASSWORD        (예약, 미사용)
INTERVALS_API_KEY                       (예약, 미사용)
```

## Repository 구조

```text
running-ai/
├─ server/              Spring Boot 백엔드
├─ docs/work-orders/    작업지시서 및 구현 기록
├─ .env.example
└─ README.md
```
