 > 원본 작업지시서 (2026-09-29). 구현 기록은 `2026-09-29-running-ai-spring-server-bootstrap.md` 참고.

# RunningAI Spring Boot 초기 구축 작업지시서

## 1. 작업 목적

현재 RunningAI는 Windows PC에서 PowerShell / Node.js / 파일 기반으로 동작하는 개인 러닝 자동화 프로젝트다.

향후 다음 기능을 안정적인 서버 애플리케이션으로 이전하기 위해 Spring Boot 기반 백엔드 프로젝트를 새로 구축한다.

주요 목표는 다음과 같다.

- Garmin 활동 데이터 수집 및 저장
- 러닝 / 실내 러닝 / 실내 자전거 데이터 관리
- 훈련 상태 분석
- 훈련 생성
- Intervals.icu 연동
- Garmin 훈련 전송 파이프라인 관리
- 운동 후 상세 리포트 생성
- 주간 / 월간 리포트 생성
- Scheduler 기반 자동화
- 기존 PowerShell / Node.js 기능의 단계적 서버 이전

이번 작업에서는 기존 RunningAI 전체 기능을 옮기지 않는다.

**Spring Boot 서버의 안정적인 기반 구조만 만든다.**

---

# 2. Git Repository

현재 GitHub에 다음 repository가 이미 생성되어 있다.

```text
running-ai
```

현재 작업 PC는 기존 RunningAI가 설치된 메인 Windows PC가 아닌 외부 PC다.

따라서 기존 프로젝트 파일을 임의로 재현하거나 복사하려 하지 말고, GitHub repository를 clone하여 신규 Spring Boot 서버 부분만 구현한다.

Repository 구조는 다음 방향을 기본으로 한다.

```text
running-ai/
├─ server/
│  ├─ src/
│  ├─ build.gradle
│  ├─ settings.gradle
│  └─ ...
│
├─ docs/
│  └─ work-orders/
│
├─ .gitignore
└─ README.md
```

기존 PowerShell / Node.js / scheduler / reporting 소스는 추후 메인 PC에서 병합할 예정이다.

따라서 이번 작업에서 존재하지 않는 기존 코드를 임의로 생성하지 않는다.

---

# 3. Work Order 문서 저장

이번 작업 내용을 반드시 Markdown 문서로 저장한다.

경로:

```text
docs/work-orders/
```

파일명 예:

```text
2026-09-29-running-ai-spring-server-bootstrap.md
```

현재 이 작업지시서의 전체 내용 또는 동일한 수준의 상세 작업 내역을 기록한다.

향후 변경 이력을 추적할 수 있도록 작업 목적, 결정사항, 구현사항, 테스트 결과를 모두 남긴다.

---

# 4. 기술 스택

신규 서버는 기존 회사 프로젝트의 Java 8 / Spring Boot 2.x 환경과 분리한다.

다음 기술 스택을 사용한다.

## Backend

```text
Java 21
Spring Boot 3.x
Gradle
Spring Web
Spring Validation
Spring Data JPA
Spring Actuator
```

가능하면 현재 안정적인 Spring Boot 3.x 최신 버전을 사용한다.

단, RC / M / SNAPSHOT 버전은 사용하지 않는다.

---

# 5. Database

향후 운영 DB는 PostgreSQL을 기본 방향으로 한다.

로컬 개발 및 테스트 환경에서는 다음 구성을 사용한다.

```text
runtime/development:
PostgreSQL

test:
H2 또는 Testcontainers PostgreSQL
```

이번 단계에서 Docker가 반드시 필요한 구조로 만들지는 않는다.

하지만 향후 Docker Compose로 PostgreSQL을 실행할 수 있도록 확장 가능한 형태로 구성한다.

---

# 6. package 구조

초기 package는 다음 형태를 기준으로 한다.

예:

```text
com.runningai
```

또는

```text
com.runningai.server
```

아래와 같은 feature 중심 구조를 권장한다.

```text
com.runningai
├─ common
│  ├─ config
│  ├─ exception
│  ├─ response
│  └─ util
│
├─ athlete
│
├─ activity
│
├─ workout
│
├─ training
│
├─ integration
│  ├─ garmin
│  └─ intervals
│
├─ reporting
│
└─ scheduler
```

아직 기능 구현이 없는 package를 불필요하게 빈 디렉터리로 대량 생성하지 않아도 된다.

현재 필요한 최소 구조만 생성하되 향후 위 구조로 확장하기 쉽게 설계한다.

---

# 7. Architecture 원칙

초기부터 Controller에 비즈니스 로직을 넣지 않는다.

기본 구조는 다음 패턴을 사용한다.

```text
Controller
    ↓
Application / Service
    ↓
Domain
    ↓
Repository
```

외부 시스템 연동은 별도로 분리한다.

```text
application
domain
infrastructure
```

형태의 엄격한 Hexagonal Architecture까지 이번 단계에서 강제하지 않는다.

과도한 추상화는 피한다.

RunningAI는 현재 개인 프로젝트이므로:

- 유지보수성
- 테스트 가능성
- 단순성
- 추후 기능 확장

사이의 균형을 맞춘다.

---

# 8. 최초 Domain

이번 단계에서는 시스템이 정상 동작함을 검증할 수 있도록 최소 Domain만 만든다.

## Athlete

RunningAI 사용자를 나타낸다.

현재 single-user 서비스이지만 향후 확장 가능하게 만든다.

예:

```text
Athlete
- id
- name
- timezone
- createdAt
- updatedAt
```

timezone 기본 방향:

```text
Asia/Seoul
```

---

## Activity

운동 기록을 나타낸다.

향후 다음 운동 유형을 지원한다.

```text
RUN
TREADMILL_RUN
INDOOR_CYCLING
```

초기 Entity 예:

```text
Activity
- id
- athleteId
- externalSource
- externalId
- activityType
- startedAt
- durationSeconds
- distanceMeters
- averageHeartRate
- maxHeartRate
- createdAt
- updatedAt
```

externalSource는 향후 다음 값이 들어갈 수 있다.

```text
GARMIN
INTERVALS_ICU
MANUAL
```

이번 단계에서 Garmin API를 실제 구현할 필요는 없다.

Domain과 저장 구조만 준비한다.

---

# 9. ID 정책

DB 내부 Primary Key와 외부 서비스 ID를 분리한다.

예:

```text
id
externalId
externalSource
```

Garmin activity ID나 Intervals.icu ID를 DB Primary Key로 직접 사용하지 않는다.

---

# 10. Audit Field

Entity에 다음 값을 기록할 수 있도록 한다.

```text
createdAt
updatedAt
```

Spring Data JPA Auditing을 사용해도 된다.

---

# 11. API

서버 기동 여부를 확인할 수 있는 최소 API를 구현한다.

## Health API

```http
GET /api/v1/health
```

응답 예:

```json
{
  "status": "UP",
  "application": "running-ai"
}
```

Spring Actuator health endpoint와 별도로 사용자용 API를 둬도 된다.

---

## Activity API

최소 기능으로 다음 endpoint를 만든다.

### Activity 등록

```http
POST /api/v1/activities
```

예시 요청:

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

### Activity 조회

```http
GET /api/v1/activities/{id}
```

### Activity 목록 조회

```http
GET /api/v1/activities
```

처음에는 단순 조회로 구현해도 되지만 향후 pagination 적용을 고려한다.

---

# 12. Validation

Request DTO에 validation을 적용한다.

예:

```text
durationSeconds >= 0
distanceMeters >= 0
heartRate >= 0
externalSource required
activityType required
startedAt required
```

Entity를 Controller request/response로 직접 사용하지 않는다.

DTO를 별도로 만든다.

---

# 13. 예외 처리

Global Exception Handler를 구현한다.

예:

```text
@RestControllerAdvice
```

최소한 다음 상황을 처리한다.

```text
Validation error
Resource not found
Unexpected server error
```

공통 오류 응답 예:

```json
{
  "code": "ACTIVITY_NOT_FOUND",
  "message": "Activity not found",
  "timestamp": "..."
}
```

과도한 공통 Response Wrapper는 만들지 않는다.

---

# 14. 중복 Activity 대비

향후 Garmin 데이터를 반복 수집할 가능성이 높다.

따라서 동일한:

```text
externalSource + externalId
```

조합의 Activity가 중복 저장되지 않도록 DB unique constraint 또는 이에 준하는 구조를 준비한다.

이 부분은 RunningAI 데이터 ingestion 안정성에서 중요하다.

---

# 15. Configuration

다음 profile을 준비한다.

```text
local
test
```

예:

```text
application.yml
application-local.yml
application-test.yml
```

민감정보를 코드에 직접 넣지 않는다.

향후 다음 환경 변수를 사용할 수 있도록 한다.

```text
DB_URL
DB_USERNAME
DB_PASSWORD

GARMIN_USERNAME
GARMIN_PASSWORD

INTERVALS_API_KEY
```

Garmin credential은 실제로 현재 저장하거나 구현하지 않는다.

---

# 16. Security / Secrets

다음 파일 또는 정보는 Git에 올라가면 안 된다.

```text
.env
.env.*
credentials
API keys
tokens
Garmin credentials
Intervals.icu API key
IDE 설정
build 결과물
logs
```

`.gitignore`를 작성하고 확인한다.

단:

```text
.env.example
```

은 필요하다면 commit할 수 있다.

예:

```text
DB_URL=
DB_USERNAME=
DB_PASSWORD=
INTERVALS_API_KEY=
```

실제 값은 절대 작성하지 않는다.

---

# 17. Testing

최소한 다음 테스트를 만든다.

## Unit / Integration

```text
Application context load test
Activity create test
Activity get test
Duplicate external activity test
Validation failure test
```

가능하면 Controller / Service / Repository 테스트가 지나치게 중복되지 않도록 한다.

테스트의 목적은 architecture demonstration이 아니라 실제 regression 방지다.

---

# 18. 코드 품질

다음 원칙을 따른다.

- Lombok은 반드시 필요하지 않으면 사용하지 않는다.
- Java record는 DTO에서 적극적으로 사용 가능하다.
- Entity에서 무분별한 setter 사용을 피한다.
- 생성 책임을 명확히 한다.
- 불필요한 interface / abstract class를 만들지 않는다.
- 미래 기능을 예상한 과도한 abstraction을 피한다.
- Clean Code보다 실제 유지보수성을 우선한다.

---

# 19. README

Repository root의 README.md를 작성하거나 업데이트한다.

최소 다음 내용을 포함한다.

```text
# RunningAI

AI-powered running training platform.

## Goals

Garmin activity ingestion
Training analysis
Adaptive workout generation
Intervals.icu integration
Garmin workout delivery
Activity reports
Weekly / monthly reports

## Architecture

RunningAI Server
Garmin
Intervals.icu
Scheduler
Reporting

## Server

Java 21
Spring Boot 3
PostgreSQL

## Development

server 실행 방법
test 실행 방법
profile 사용 방법
```

현재 구현된 것과 향후 계획을 명확히 구분한다.

아직 구현되지 않은 기능을 구현된 것처럼 README에 작성하지 않는다.

---

# 20. 현재 기존 RunningAI와의 관계

기존 RunningAI에는 이미 다음과 같은 기능이 존재한다.

```text
PowerShell scheduler
Command Channel
Intervals.icu structured workout creation
Garmin workout synchronization pipeline
activity ingestion
activity detail report
weekly report
monthly report
regression tests
```

하지만 현재 외부 PC에는 해당 코드가 없을 수 있다.

절대 기존 기능을 추측해서 다시 만들지 않는다.

이번 Spring Boot 프로젝트는 향후 기존 코드를 단계적으로 흡수할 **새 Backend foundation**이다.

---

# 21. 향후 서버 이전 예정 기능

아래는 이번 작업에서 구현하지 않는다.

```text
Garmin 실제 로그인
Garmin Connect scraping
Intervals.icu 실제 API 호출
훈련 자동 생성 알고리즘
Garmin structured workout 생성
Garmin workout cue 생성
PowerShell scheduler 이전
Monthly Report 생성
Weekly Report 생성
AI 분석
LLM 연동
ntfy 알림
```

단, 향후 해당 기능을 추가하기 쉬운 구조는 고려한다.

---

# 22. 이번 작업의 Definition of Done

아래 항목이 모두 만족되어야 완료로 판단한다.

1. `server/` Spring Boot 프로젝트 생성
2. Java 21 사용
3. Gradle build 성공
4. Spring Boot application 정상 실행
5. `/api/v1/health` 정상 응답
6. Activity entity 구현
7. Activity create API 구현
8. Activity get API 구현
9. Activity list API 구현
10. Validation 구현
11. Global Exception Handler 구현
12. `externalSource + externalId` 중복 방지
13. JPA repository 구현
14. local/test profile 분리
15. 테스트 성공
16. `.gitignore` 작성
17. README 업데이트
18. Work Order 문서 저장
19. Git diff 검토
20. 민감정보가 repository에 포함되지 않았는지 확인

---

# 23. 검증 명령

작업 완료 후 실제로 다음 명령을 실행한다.

Windows PowerShell 기준:

```powershell
cd server

.\gradlew clean test
```

그리고:

```powershell
.\gradlew bootRun
```

서버가 실행되면:

```powershell
Invoke-RestMethod http://localhost:8080/api/v1/health
```

정상 응답을 확인한다.

가능하면 Activity POST / GET도 실제로 검증한다.

---

# 24. Git 작업

작업 전:

```bash
git status
```

작업 완료 후:

```bash
git status
git diff
```

민감정보가 없는지 반드시 확인한다.

Commit message는 다음과 같이 사용한다.

```text
feat: bootstrap RunningAI Spring Boot server
```

remote 설정이 정상이고 push 권한이 있다면 commit 후 push한다.

```bash
git add .
git commit -m "feat: bootstrap RunningAI Spring Boot server"
git push
```

단, push 전에 반드시 테스트가 통과해야 한다.

---

# 25. 작업 완료 보고

최종 보고는 다음 형식으로 작성한다.

## 구현 완료

- 구현한 기능
- 추가한 파일
- 주요 architecture 결정

## 테스트

```text
gradlew clean test
PASS / FAIL
```

테스트 개수도 가능하면 표시한다.

## API

구현한 endpoint 목록

## Git

```text
branch:
commit:
push:
```

## 남은 작업

Spring Boot 서버 기준 다음 작업 후보를 제안한다.

예:

```text
1. PostgreSQL Docker Compose
2. Garmin Activity ingestion adapter
3. Activity raw data schema
4. Training load domain
5. Workout domain
6. Intervals.icu adapter
```

단 다음 작업을 자동으로 시작하지 않는다.

---

# 26. 중요한 작업 원칙

가장 중요하다.

현재 목적은 RunningAI 전체 재개발이 아니다.

**Spring Boot 기반 Backend foundation을 깨끗하고 테스트 가능한 상태로 만드는 것이 이번 작업의 전부다.**

기존 RunningAI 기능을 임의로 추측해 구현하지 않는다.

과도하게 많은 기능을 한 번에 추가하지 않는다.

현재 repository 상태를 먼저 확인한 뒤 기존 파일을 보존하면서 작업한다.

모든 구현 후 실제 build / test를 수행하여 성공 여부를 확인한다.
