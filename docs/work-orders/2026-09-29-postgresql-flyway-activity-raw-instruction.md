> 원본 작업지시서 (2026-09-29, 2차). 구현 기록은 `2026-09-29-postgresql-flyway-activity-raw.md` 참고.

# RunningAI 2차 작업지시서

## 1. 작업 목적

현재 RunningAI Spring Boot foundation은 다음 상태까지 구현되어 있다.

- Spring Boot 3.5.16
- Java 21
- Gradle 8.14.5 wrapper
- `server/` 프로젝트
- local / test profile 분리
- Athlete, Activity JPA entity
- Activity create / get / list API
- Health API / Actuator
- JPA Auditing
- Validation
- Global Exception Handler
- `externalSource + externalId` 중복 방지
- H2 기반 테스트
- 총 11개 테스트 PASS
- main branch commit / push 완료

현재 Git 상태:

```text
branch: main
previous commit: 77899b9
```

이번 작업의 목적은 향후 Garmin Activity ingestion을 안정적으로 구현하기 전에 데이터베이스 기반을 고정하는 것이다.

이번 작업에서 다음 3가지를 구현한다.

1. PostgreSQL Docker Compose 개발환경
2. Flyway schema migration
3. Activity Raw Payload 저장 구조

이번 단계에서는 **Garmin API 실제 연동은 구현하지 않는다.**

---

# 2. 작업 전 필수 확인

작업 시작 전에 반드시 repository 상태부터 확인한다.

```bash
git status
git log --oneline -5
git remote -v
```

현재 main branch에 예상하지 못한 변경사항이 있으면 덮어쓰지 않는다.

현재 구현된 코드를 먼저 읽고 기존 architecture와 naming convention을 유지한다.

---

# 3. Work Order 문서

이번 작업도 반드시 문서화한다.

저장 경로:

```text
docs/work-orders/
```

파일명:

```text
2026-09-29-postgresql-flyway-activity-raw.md
```

문서에는 최소 다음 내용을 남긴다.

- 작업 목적
- 기존 상태
- architecture 결정사항
- DB schema
- migration 목록
- Docker Compose 사용법
- 테스트 결과
- 실제 PostgreSQL 검증 결과
- 알려진 제한사항
- 다음 단계 제안

---

# 4. PostgreSQL Docker Compose

Repository root 또는 적절한 infrastructure 경로에 PostgreSQL 개발환경을 추가한다.

권장 위치:

```text
running-ai/
├─ docker-compose.yml
├─ server/
└─ ...
```

또는 현재 repository 구조를 검토해 더 적절한 위치가 있다면 판단하여 적용한다.

PostgreSQL은 안정적인 공식 image를 사용한다.

예:

```yaml
postgres:17
```

혹은 작업 시점 기준 안정적인 공식 버전을 선택한다.

RC / beta 버전은 사용하지 않는다.

---

# 5. PostgreSQL 기본 설정

개발용 기본값 예시는 다음 방향으로 한다.

```text
database: running_ai
username: running_ai
password: running_ai
port: 5432
```

단 실제 비밀번호를 application.yml 등에 하드코딩하지 않는다.

Docker Compose는 개발용 기본값을 사용할 수 있지만 application config는 환경변수를 우선한다.

예:

```text
DB_URL
DB_USERNAME
DB_PASSWORD
```

`.env.example`을 필요 시 갱신한다.

실제 `.env`는 commit하지 않는다.

---

# 6. Docker Compose 요구사항

최소 다음을 포함한다.

- PostgreSQL service
- named volume
- healthcheck
- restart 정책은 과도하게 강제하지 않아도 됨

예시 방향:

```yaml
services:
  postgres:
    image: postgres:17
    environment:
      POSTGRES_DB: running_ai
      POSTGRES_USER: running_ai
      POSTGRES_PASSWORD: running_ai
    ports:
      - "5432:5432"
    volumes:
      - running_ai_postgres_data:/var/lib/postgresql/data
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U running_ai -d running_ai"]
      interval: 5s
      timeout: 5s
      retries: 10
```

실제 구성은 현재 프로젝트와 호환되게 작성한다.

---

# 7. Flyway 도입

현재 local profile의 Hibernate:

```text
ddl-auto=update
```

방식은 제거한다.

향후 schema 변경은 Flyway migration만 사용한다.

Gradle dependency에 Flyway를 추가한다.

Spring Boot 3.5.x / PostgreSQL 조합에 필요한 Flyway PostgreSQL module이 있다면 함께 추가한다.

예:

```text
org.flywaydb:flyway-core
org.flywaydb:flyway-database-postgresql
```

정확한 dependency는 현재 Spring Boot dependency management와 호환되게 설정한다.

---

# 8. Hibernate Schema 정책

local 환경은 다음 방향으로 변경한다.

```text
ddl-auto=validate
```

또는 이에 준하는 안전한 설정을 적용한다.

목표는:

```text
Flyway = schema 생성/변경 책임
Hibernate = mapping 검증
```

이다.

test profile도 가능하면 migration을 실제로 태우는 방식으로 개선한다.

기존 `create-drop`에 의존하지 않는 방향을 우선 검토한다.

---

# 9. Migration 구조

Flyway migration 디렉터리:

```text
server/src/main/resources/db/migration/
```

초기 migration 예:

```text
V1__create_athlete_table.sql
V2__create_activity_table.sql
V3__create_activity_raw_table.sql
```

또는 현재 schema에 맞춰 하나의 bootstrap migration으로 구성해도 된다.

단 향후 migration history를 보기 쉽도록 적절히 분리하는 것을 권장한다.

---

# 10. 기존 JPA Entity와 DB Schema 정합성

현재 Athlete / Activity entity를 기준으로 실제 PostgreSQL schema를 만든다.

기존 기능이 깨지지 않아야 한다.

예상 schema 개념:

```text
athlete
- id
- name
- timezone
- created_at
- updated_at
```

```text
activity
- id
- athlete_id
- external_source
- external_id
- activity_type
- started_at
- duration_seconds
- distance_meters
- average_heart_rate
- max_heart_rate
- created_at
- updated_at
```

실제 column명과 datatype은 현재 entity mapping을 읽고 맞춘다.

---

# 11. Activity Raw Payload 설계

외부 데이터 소스에서 받은 원본 payload를 보존하기 위한 테이블을 추가한다.

권장 개념:

```text
activity_raw
- id
- activity_id
- external_source
- external_id
- payload
- fetched_at
- created_at
```

PostgreSQL에서는 payload를 반드시:

```text
JSONB
```

로 저장한다.

---

# 12. Activity Raw의 목적

`activity`는 RunningAI에서 실제 사용하는 정규화된 데이터다.

`activity_raw`는 Garmin / Intervals.icu 등 외부 시스템에서 받은 원본 응답을 보존한다.

다음 목적을 가진다.

- 원본 데이터 보존
- 파싱 로직 변경 시 재처리 가능
- 새 metric 추가 시 재수집 최소화
- ingestion debugging
- 외부 API 변경 대응

따라서 외부 API response를 Activity entity에 무분별하게 모두 추가하지 않는다.

---

# 13. ActivityRaw Entity

현재 naming convention에 맞춰 entity를 구현한다.

예시 개념:

```java
ActivityRaw
- id
- activity
- externalSource
- externalId
- payload
- fetchedAt
- createdAt
```

`Activity`와는 FK 관계를 가진다.

다만 향후 ingestion 과정에서 raw payload가 먼저 들어오고 normalized Activity 생성이 실패할 가능성도 고려한다.

아래 두 설계를 비교 후 결정한다.

### Option A

```text
activity_id NOT NULL
```

장점:
- 단순함
- orphan 없음

단점:
- normalize 실패 payload 저장 어려움

### Option B

```text
activity_id NULLABLE
```

장점:
- raw-first ingestion 가능
- parsing 실패 데이터 보존 가능

단점:
- orphan 관리 필요

향후 Garmin ingestion 안정성을 고려했을 때 **Option B를 우선 검토한다.**

최종 선택 이유를 Work Order에 기록한다.

---

# 14. JSONB Mapping

JSONB payload는 string으로 대충 저장하지 말고 PostgreSQL JSONB semantics를 유지한다.

Spring Boot 3 / Hibernate 6에서 지원하는 방식 중 현재 프로젝트에 가장 단순하고 안정적인 방법을 사용한다.

가능하면:

```java
@JdbcTypeCode(SqlTypes.JSON)
```

등 Hibernate 6 표준 기능을 우선 검토한다.

불필요한 외부 JSON type 라이브러리는 추가하지 않는다.

payload Java 타입은 다음 중 적절한 것을 선택한다.

```text
JsonNode
Map<String, Object>
String
```

권장은:

```text
JsonNode
```

Jackson과 자연스럽게 연결되고 raw JSON 구조를 유지하기 쉽다.

---

# 15. Raw Payload 중복 정책

동일 외부 Activity가 여러 번 fetch될 가능성이 있다.

아래 정책 중 하나를 명확히 선택한다.

권장:

```text
external_source + external_id
```

기준으로 raw 최신 snapshot 1개를 유지하는 방식.

또는 향후 version history가 중요하다면 fetch별 snapshot을 저장할 수도 있다.

현재 RunningAI 목적에는 우선:

```text
1 external activity = 1 raw row
```

방식을 추천한다.

재수집 시:

```text
payload
fetched_at
```

을 update할 수 있도록 설계한다.

단 이 정책은 Work Order에 명시한다.

---

# 16. Repository

다음을 추가한다.

```text
ActivityRawRepository
```

필요한 최소 method만 작성한다.

예:

```text
findByExternalSourceAndExternalId(...)
```

과도한 repository method는 추가하지 않는다.

---

# 17. Service

Activity Raw 저장 책임을 담당하는 service를 추가한다.

예:

```text
ActivityRawService
```

기능 예:

```text
saveOrUpdateRawPayload(...)
find...
```

하지만 이번 단계에서는 public API로 노출할 필요는 없다.

향후 Garmin ingestion adapter가 호출할 내부 application service 기반만 준비한다.

---

# 18. Raw Payload API

이번 단계에서는 일반 사용자용 Activity Raw API를 만들지 않는다.

즉 다음과 같은 endpoint는 만들지 않는다.

```text
GET /api/v1/activity-raw
POST /api/v1/activity-raw
```

Activity Raw는 ingestion 내부 구현용이다.

테스트를 위해 service/repository 레벨에서 검증한다.

---

# 19. Timestamp 정책

현재 Activity가 UTC `Instant`를 사용하는 정책을 유지한다.

ActivityRaw도:

```text
fetchedAt
createdAt
```

은 UTC 기준 `Instant`를 사용한다.

PostgreSQL datatype은 timezone 보존에 적절한 타입을 사용한다.

예:

```text
timestamp with time zone
```

---

# 20. PostgreSQL Type 선택

적절한 PostgreSQL type을 명확히 사용한다.

예:

```text
BIGINT
VARCHAR
INTEGER
TIMESTAMPTZ
JSONB
```

distance / duration 등은 현재 Java 타입에 맞춰 결정한다.

미래 가능성만으로 지나치게 큰 numeric type을 쓰지 않는다.

---

# 21. Index

최소한 다음 lookup을 고려한다.

```text
activity(external_source, external_id)
activity(started_at)
activity_raw(external_source, external_id)
```

unique constraint가 index 역할을 하는 경우 중복 index를 만들지 않는다.

실제 query pattern이 없는 필드에 index를 과도하게 추가하지 않는다.

---

# 22. 기본 Athlete 생성

현재 기동 시 기본 athlete 자동 생성 로직이 있다.

Flyway 전환 후에도 정상 동작해야 한다.

schema 생성 이전에 initializer가 실행되는 문제 등이 없는지 실제 기동 테스트한다.

---

# 23. 테스트 환경 개선

가능하면 이번 단계에서 PostgreSQL과 H2의 schema 차이 문제를 줄인다.

우선순위는 다음이다.

### 권장

Testcontainers PostgreSQL

### 차선

H2 PostgreSQL compatibility mode + Flyway

하지만 현재 외부 PC 환경에서 Docker 사용 가능 여부를 먼저 확인한다.

Docker가 있다면 Testcontainers PostgreSQL을 우선 적용한다.

Docker가 없다면 기존 H2 테스트를 유지하되 Flyway와 production PostgreSQL 차이를 문서화한다.

---

# 24. 테스트 요구사항

기존 11개 테스트는 모두 계속 통과해야 한다.

추가로 최소 다음을 검증한다.

```text
Flyway migration success
Activity schema validation
Activity duplicate constraint
ActivityRaw insert
ActivityRaw JSONB payload persistence
ActivityRaw save/update behavior
ActivityRaw fetchedAt update
```

가능하면 PostgreSQL 실제 DB에서도 검증한다.

---

# 25. PostgreSQL 실제 기동 검증

Docker가 사용 가능하면 반드시 다음 흐름을 실제 실행한다.

```bash
docker compose up -d
```

상태 확인:

```bash
docker compose ps
```

서버 local profile 실행:

```powershell
cd server
.\gradlew bootRun
```

또는 필요한 환경변수를 설정한 뒤 실행한다.

확인:

```powershell
Invoke-RestMethod http://localhost:8080/api/v1/health
```

Activity POST / GET도 실제 PostgreSQL 환경에서 검증한다.

---

# 26. DB 내부 검증

가능하면 실제 PostgreSQL에 접속하여 다음을 확인한다.

```text
flyway_schema_history
athlete
activity
activity_raw
```

또한 ActivityRaw payload column이 실제:

```text
jsonb
```

인지 확인한다.

예:

```sql
\d activity_raw
```

또는 information_schema / pg_catalog를 이용해 검증한다.

---

# 27. Flyway 최초 Migration 주의사항

현재 repository에는 아직 운영 데이터가 없으므로 기존 schema migration 부담은 없다.

따라서 현재 JPA schema를 기준으로 Flyway baseline을 깨끗하게 생성하면 된다.

하지만 앞으로는 이미 적용된 migration 파일을 수정하지 않는 원칙을 README 또는 Work Order에 명시한다.

```text
적용된 migration은 수정하지 않고 새 version migration을 추가한다.
```

---

# 28. README 업데이트

README에 PostgreSQL 개발환경 사용법을 추가한다.

예:

```text
## Local Database

docker compose up -d

DB_URL=jdbc:postgresql://localhost:5432/running_ai
DB_USERNAME=running_ai
DB_PASSWORD=running_ai
```

Windows PowerShell 환경변수 설정 예도 있으면 좋다.

```powershell
$env:DB_URL="jdbc:postgresql://localhost:5432/running_ai"
$env:DB_USERNAME="running_ai"
$env:DB_PASSWORD="running_ai"
```

실제 비밀값은 기록하지 않는다.

---

# 29. .env.example 업데이트

필요하면 다음과 같이 정리한다.

```text
DB_URL=jdbc:postgresql://localhost:5432/running_ai
DB_USERNAME=running_ai
DB_PASSWORD=
INTERVALS_API_KEY=
GARMIN_USERNAME=
GARMIN_PASSWORD=
```

실제 credential을 commit하지 않는다.

---

# 30. 기존 API 회귀 테스트

다음 endpoint는 변경 전과 동일하게 작동해야 한다.

```text
GET /api/v1/health
POST /api/v1/activities
GET /api/v1/activities/{id}
GET /api/v1/activities
GET /actuator/health
```

response contract를 불필요하게 변경하지 않는다.

---

# 31. Garmin 구현 금지 범위

이번 작업에서는 다음을 구현하지 않는다.

```text
Garmin login
Garmin Connect scraping
Garmin unofficial API
Garmin token handling
Garmin activity download
FIT file parser
TCX parser
Garmin credential storage
Intervals.icu 호출
AI 분석
training load 계산
workout 생성
scheduler migration
```

다음 단계의 ingestion 구현을 위한 저장 기반만 만든다.

---

# 32. Commit 단위

한 번에 의미 없는 대형 commit 하나로 만들기보다 현재 작업 범위가 명확하게 보이도록 정리한다.

다만 이번 작업은 하나의 기능 단위이므로 최종 squash 여부는 현재 repository 정책에 맞춰 판단한다.

최종 commit message 권장:

```text
feat: add PostgreSQL migrations and raw activity storage
```

---

# 33. 완료 조건 Definition of Done

다음 항목이 모두 만족되어야 완료다.

1. PostgreSQL Docker Compose 추가
2. PostgreSQL volume / healthcheck 설정
3. Flyway dependency 추가
4. Hibernate schema auto-create/update 제거
5. Flyway migration 파일 생성
6. Athlete schema migration
7. Activity schema migration
8. ActivityRaw schema migration
9. ActivityRaw JPA Entity 구현
10. JSONB mapping 구현
11. ActivityRaw Repository 구현
12. ActivityRaw Service 구현
13. Raw payload 중복/update 정책 구현
14. timestamp 정책 유지
15. 필요한 DB index/constraint 적용
16. 기존 API regression PASS
17. ActivityRaw persistence test PASS
18. Flyway migration test PASS
19. `.env.example` 정리
20. README 업데이트
21. Work Order 문서 저장
22. 민감정보 미포함 확인
23. Git diff 검토
24. 테스트 성공 후 commit
25. origin/main push

---

# 34. 검증 명령

최소 다음을 수행한다.

```powershell
cd server

.\gradlew clean test
```

Docker 사용 가능 시:

```powershell
cd ..

docker compose up -d
docker compose ps
```

환경변수 설정:

```powershell
$env:DB_URL="jdbc:postgresql://localhost:5432/running_ai"
$env:DB_USERNAME="running_ai"
$env:DB_PASSWORD="running_ai"
```

서버 실행:

```powershell
cd server
.\gradlew bootRun
```

health 확인:

```powershell
Invoke-RestMethod http://localhost:8080/api/v1/health
```

Activity API smoke test도 수행한다.

---

# 35. 최종 보고 형식

완료 후 반드시 아래 형식으로 보고한다.

## 구현 완료

- PostgreSQL 구성
- Flyway migration
- ActivityRaw 구조
- 주요 architecture 결정

## Database

```text
PostgreSQL version:
Flyway migrations:
Tables:
Indexes / constraints:
```

## Activity Raw

```text
payload type:
duplicate policy:
relationship to Activity:
timestamp policy:
```

## 테스트

```text
gradlew clean test:
total:
passed:
failed:
```

PostgreSQL 실제 테스트 여부:

```text
docker compose:
local boot:
health:
activity API:
activity_raw persistence:
```

## Git

```text
branch:
commit:
push:
```

## 제한사항

현재 환경 문제나 향후 고려사항을 명시한다.

## 다음 작업 후보

1. Garmin ingestion adapter
2. Garmin credential / session strategy
3. raw payload → normalized Activity mapper
4. ingestion idempotency
5. Activity pagination / filters

다음 작업을 자동으로 시작하지 않는다.

---

# 36. 가장 중요한 원칙

이번 작업은 Garmin 연동 작업이 아니다.

이번 단계의 목표는:

```text
외부 활동 데이터를 안전하게 받아서
PostgreSQL에
원본과 정규화 데이터를 안정적으로 저장할 수 있는 기반
```

을 만드는 것이다.

현재 구현된 API와 architecture를 보존하고 과도한 리팩터링을 하지 않는다.

필요 이상의 추상화, framework 추가, 외부 dependency 추가를 피한다.

실제 build / test / DB 기동 검증 후에만 완료로 처리한다.
