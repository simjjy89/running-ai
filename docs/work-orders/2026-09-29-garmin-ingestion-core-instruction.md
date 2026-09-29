> 원본 작업지시서 (2026-09-29, Phase 3A). 구현 기록은 `2026-09-29-garmin-ingestion-core.md` 참고.

# RunningAI Phase 3A 작업지시서

## 1. 작업 목적

현재 RunningAI Spring Boot 서버는 다음 상태까지 구현되어 있다.

### 완료된 기반

- Spring Boot 3.5.16
- Java 21
- Gradle 8.14.5 wrapper
- PostgreSQL 개발환경
- Flyway migration
- Hibernate `ddl-auto=validate`
- Athlete entity
- Activity entity
- ActivityRaw entity
- PostgreSQL JSONB raw payload 저장
- Activity API
- ActivityRawService
- `externalSource + externalId` 중복 방지
- raw payload save/update
- H2 + Flyway 테스트
- 실제 PostgreSQL 17.11 검증
- 총 20개 테스트 PASS

현재 latest commit:

```text
7a48104 feat: add PostgreSQL migrations and raw activity storage
```

이번 작업의 목적은 Garmin 실제 인증/API 접속 전에 Garmin activity ingestion의 핵심 흐름을 완성하는 것이다.

구현 목표:

```text
Garmin JSON payload
        ↓
ActivityRaw 저장
        ↓
GarminActivityMapper
        ↓
Normalized Activity
        ↓
Activity upsert
```

추가로:

```text
기존 ActivityRaw
        ↓
reprocess
        ↓
Activity 재생성 / 갱신
```

기능을 구현한다.

---

# 2. 이번 단계에서 하지 않는 것

중요하다.

이번 작업에서는 Garmin Connect에 실제 접속하지 않는다.

구현 금지 범위:

```text
Garmin 로그인
Garmin username/password 인증
Garmin OAuth
Garmin token/session
Garmin Connect scraping
Garmin unofficial HTTP API
FIT 파일 다운로드
FIT parser
TCX parser
실제 Garmin activity fetch
실제 Garmin credential 저장
Intervals.icu 연동
Scheduler
AI 분석
training load 계산
workout 생성
```

실제 Garmin client는 다음 Phase 3B에서 구현한다.

이번 단계는 **fixture 기반 ingestion core**에만 집중한다.

---

# 3. 작업 전 확인

작업 시작 전에 반드시 repository 상태를 확인한다.

```bash
git status
git branch --show-current
git log --oneline -5
git remote -v
```

예상 상태:

```text
branch: main
latest commit: 7a48104
```

기존 변경사항이 있으면 덮어쓰지 않는다.

현재 구현된 다음 코드를 먼저 읽는다.

```text
Activity
ActivityService
ActivityRepository
ActivityRaw
ActivityRawService
ActivityRawRepository
ExternalSource
ActivityType
```

기존 naming convention과 package 구조를 유지한다.

---

# 4. Work Order 문서

이번 작업지시서 전체 내용을 repository에 저장한다.

경로:

```text
docs/work-orders/
```

권장 파일명:

```text
2026-09-29-garmin-ingestion-core-instruction.md
```

구현 완료 후 실제 결과 문서도 작성한다.

```text
2026-09-29-garmin-ingestion-core.md
```

결과 문서에는 다음을 포함한다.

- 작업 목적
- 구현 architecture
- Garmin fixture 구조
- mapping 정책
- Activity upsert 정책
- raw 저장 정책
- reprocessing 정책
- idempotency 정책
- 테스트 결과
- 알려진 제한사항
- 다음 Phase 3B 준비사항

---

# 5. Package 구조

현재 feature 중심 구조를 유지한다.

권장:

```text
com.runningai
├─ activity
│  ├─ Activity
│  ├─ ActivityRepository
│  ├─ ActivityService
│  ├─ ActivityRaw
│  ├─ ActivityRawRepository
│  └─ ActivityRawService
│
└─ integration
   └─ garmin
      ├─ GarminActivityIngestionService
      ├─ GarminActivityMapper
      ├─ GarminActivityPayload
      └─ GarminActivityMappingException
```

실제 현재 프로젝트 package 구조와 자연스럽게 맞춰 조정한다.

과도한 계층은 만들지 않는다.

---

# 6. GarminClient 처리

이번 단계에서는 실제 GarminClient 구현을 만들지 않는다.

다만 향후 Phase 3B를 위해 다음 중 하나를 선택할 수 있다.

### Option A — interface 미리 정의

```java
public interface GarminClient {
    List<JsonNode> fetchActivities(...);
}
```

### Option B — 이번 단계에서는 Client 자체를 만들지 않음

IngestionService가 raw payload를 직접 입력받는다.

예:

```java
ingest(JsonNode payload)
```

이번 단계에서는 **Option B를 우선 권장한다.**

이유:

- 실제 Garmin API contract가 아직 확정되지 않음
- interface를 추측해서 만들 필요 없음
- fixture 기반 ingestion core만 검증하면 충분함

불필요한 미래 abstraction을 만들지 않는다.

---

# 7. Garmin fixture

실제 Garmin activity와 유사한 JSON fixture를 테스트 resources에 추가한다.

권장 경로:

```text
server/src/test/resources/fixtures/garmin/
```

예:

```text
running-activity.json
treadmill-activity.json
indoor-cycling-activity.json
```

최소 3종을 준비한다.

```text
RUN
TREADMILL_RUN
INDOOR_CYCLING
```

fixture는 실제 Garmin 데이터를 복사한 것이 아니라 테스트 목적의 synthetic payload여도 된다.

실제 사용자 credential, private data, GPS 좌표 등 민감정보는 절대 repository에 포함하지 않는다.

---

# 8. GarminActivityPayload

Garmin payload 전체를 DTO에 모두 모델링하지 않는다.

이번 단계에서 필요한 최소 필드만 typed DTO로 표현하거나 JsonNode에서 읽는다.

추천 방향:

```java
record GarminActivityPayload(
    String activityId,
    String activityName,
    String activityType,
    Instant startTime,
    Long durationSeconds,
    Double distanceMeters,
    Integer averageHeartRate,
    Integer maxHeartRate
)
```

단 실제 fixture 구조와 자연스럽게 맞춘다.

중요:

```text
Garmin raw payload 전체
```

는 ActivityRaw JSONB에 보존하고,

```text
GarminActivityPayload
```

는 normalized Activity로 변환하기 위한 필요한 정보만 가진다.

---

# 9. Garmin Activity ID

Garmin activity ID는 RunningAI DB PK로 사용하지 않는다.

기존 정책을 유지한다.

```text
Activity.id
→ RunningAI internal PK

Activity.externalSource
→ GARMIN

Activity.externalId
→ Garmin activity ID
```

예:

```text
GARMIN + 188081596
```

---

# 10. GarminActivityMapper

다음을 구현한다.

```text
GarminActivityMapper
```

책임:

```text
Garmin raw JSON
      ↓
필요 필드 추출
      ↓
Activity normalized data 생성
```

Mapper에는 DB 접근을 넣지 않는다.

Mapper는 pure transformation에 가깝게 유지한다.

---

# 11. Activity Type Mapping

Garmin activity type을 RunningAI의 ActivityType으로 변환한다.

현재 지원:

```text
RUN
TREADMILL_RUN
INDOOR_CYCLING
```

예시 mapping:

```text
running
RUNNING
run
→ RUN

treadmill_running
treadmill
→ TREADMILL_RUN

indoor_cycling
indoor_biking
cycling_indoor
→ INDOOR_CYCLING
```

실제 fixture에서 사용하는 Garmin type name을 기준으로 구현한다.

지원하지 않는 Garmin activity type이 들어오면 임의로 RUN으로 변환하지 않는다.

다음 중 하나를 명확히 선택한다.

### 권장

mapping exception 발생

예:

```text
UNSUPPORTED_GARMIN_ACTIVITY_TYPE
```

향후 SKIP 정책은 ingestion scheduler에서 별도로 처리할 수 있다.

---

# 12. Time Mapping

Garmin activity start time을 `Instant`로 정규화한다.

주의:

Garmin 데이터가 다음 중 어떤 형태든 대응 가능하도록 mapper를 설계한다.

```text
offset 포함 ISO8601
UTC timestamp
local timestamp + timezone 정보
```

fixture에서는 offset 포함 값을 사용한다.

예:

```text
2026-09-29T06:30:00+09:00
```

Activity에는 UTC Instant로 저장한다.

기존 timestamp 정책을 깨지 않는다.

---

# 13. Duration

Garmin payload의 duration이 milliseconds / seconds 중 어떤 단위인지 fixture에서 명확히 한다.

RunningAI normalized Activity는 기존:

```text
durationSeconds
```

정책을 유지한다.

단위 변환은 mapper 책임으로 둔다.

예:

```text
Garmin duration = milliseconds

3600000
↓
Activity.durationSeconds = 3600
```

fixture / 테스트 이름으로 단위를 명확하게 드러낸다.

---

# 14. Distance

Activity normalized 필드:

```text
distanceMeters
```

정책을 유지한다.

Garmin payload가 meter라면 그대로 사용한다.

km라면 mapper에서 meter로 변환한다.

단위가 무엇인지 fixture와 Work Order에 기록한다.

---

# 15. Heart Rate

다음 normalized field를 매핑한다.

```text
averageHeartRate
maxHeartRate
```

Garmin payload에서 값이 없는 경우 nullable을 허용한다.

0을 unknown 값으로 사용하지 않는다.

---

# 16. Activity 생성 방식 변경

현재 ActivityService가 create 중심일 가능성이 높다.

Garmin ingestion에서는 단순 create가 아니라:

```text
upsert
```

가 필요하다.

다음을 추가한다.

예:

```java
Activity upsertExternalActivity(...)
```

또는 현재 architecture에 맞는 이름.

---

# 17. Activity Upsert Key

기준:

```text
externalSource + externalId
```

Garmin이면:

```text
GARMIN + Garmin Activity ID
```

동일 key가 없으면:

```text
INSERT
```

있으면:

```text
UPDATE normalized fields
```

한다.

---

# 18. Activity Upsert 정책

재수집 시 다음 값은 최신 Garmin payload 기준으로 갱신 가능해야 한다.

```text
activityType
startedAt
durationSeconds
distanceMeters
averageHeartRate
maxHeartRate
```

기존 DB PK:

```text
Activity.id
```

는 유지한다.

즉:

```text
같은 Garmin activity 재수집
→ Activity row 새로 생성 금지
→ 기존 row update
```

---

# 19. Raw Payload Upsert

기존 ActivityRawService 정책을 그대로 사용한다.

```text
GARMIN + externalId
```

가 없으면 insert.

있으면:

```text
payload update
fetchedAt update
```

한다.

row ID는 유지한다.

---

# 20. Ingestion 순서

GarminActivityIngestionService는 다음 흐름을 가진다.

```text
1. Garmin raw JSON 수신

2. external ID 추출

3. ActivityRawService.saveOrUpdateRawPayload()

4. GarminActivityMapper로 normalized data 생성

5. ActivityService.upsert()

6. ActivityRaw.activity link 설정

7. 완료 결과 반환
```

단 중요한 예외 정책이 있다.

---

# 21. Raw-first 원칙

Raw payload를 먼저 저장한다.

즉 mapper가 실패하더라도 원본은 남아야 한다.

정상 흐름:

```text
RAW SAVE
   ↓
MAPPING
   ↓
ACTIVITY UPSERT
```

mapping 실패:

```text
RAW SAVE 성공
   ↓
MAPPING 실패
   ↓
Activity 생성 안 됨
   ↓
Raw는 DB에 유지
```

이것이 이번 ingestion 구조의 핵심이다.

---

# 22. Transaction 설계

Raw-first 원칙 때문에 전체 ingestion을 하나의 transaction으로 묶어 rollback시키면 안 된다.

예:

```text
@Transactional
ingest()
```

안에서 raw save 후 mapper exception이 발생해서 raw까지 rollback되는 구조는 피한다.

Transaction boundary를 명확히 설계한다.

예:

```text
raw save transaction
↓ commit

mapping

activity upsert transaction
↓ commit

raw link update
```

혹은 동일 목적을 달성하는 더 단순하고 안전한 구조를 사용한다.

구현 이유를 Work Order에 기록한다.

---

# 23. Ingestion Result

Ingestion 결과를 표현하는 간단한 result type을 만들 수 있다.

예:

```java
record GarminIngestionResult(
    Long activityId,
    Long rawId,
    boolean created,
    boolean updated
)
```

필수는 아니다.

단 테스트와 로그에 유용하다면 과하지 않게 도입한다.

---

# 24. Logging

다음 이벤트를 적절히 로그로 남긴다.

```text
Garmin activity ingestion started
raw payload stored/updated
activity created
activity updated
mapping failed
unsupported activity type
```

로그에 raw payload 전체를 출력하지 않는다.

credential / token 등이 향후 payload에 포함될 가능성을 고려한다.

권장 로그 예:

```text
Garmin activity ingestion completed: externalId=188081596 action=UPDATED
```

---

# 25. Idempotency

이번 Phase의 핵심이다.

같은 Garmin fixture를 연속으로 여러 번 ingest해도:

```text
Activity
→ 1 row

ActivityRaw
→ 1 row
```

만 존재해야 한다.

예:

```text
ingest #1
Activity count = 1
ActivityRaw count = 1

ingest #2
Activity count = 1
ActivityRaw count = 1
```

---

# 26. 변경된 Garmin 데이터 재수집

같은 Garmin activity ID인데 값이 변경된 fixture도 준비한다.

예:

기존:

```text
durationSeconds = 3600
distanceMeters = 10000
averageHeartRate = 155
```

재수집:

```text
durationSeconds = 3612
distanceMeters = 10020
averageHeartRate = 156
```

결과:

```text
Activity row = 그대로
Activity.id = 그대로

값 = 최신 데이터로 update

ActivityRaw.id = 그대로
payload = 최신 JSON
fetchedAt = 최신 시각
```

---

# 27. Reprocessing 기능

기존 ActivityRaw 데이터를 다시 normalized Activity로 변환할 수 있어야 한다.

다음 service를 추가하거나 IngestionService 내 method로 구현한다.

예:

```java
reprocess(Long activityRawId)
```

또는:

```java
reprocessByExternalKey(
    ExternalSource source,
    String externalId
)
```

이번 단계에서는 Garmin raw만 지원한다.

---

# 28. Reprocessing 동작

흐름:

```text
ActivityRaw 조회
↓
payload 읽기
↓
GarminActivityMapper
↓
Activity upsert
↓
ActivityRaw ↔ Activity link
```

Garmin 서버에 다시 접속하지 않는다.

DB JSONB만 사용한다.

---

# 29. Reprocessing의 목적

향후 다음 상황을 지원하기 위함이다.

```text
mapper bug 수정
새 normalized field 추가
Garmin field interpretation 변경
ActivityType mapping 개선
기존 과거 데이터 재가공
```

이 기능은 API로 노출하지 않는다.

내부 service 수준으로 구현한다.

---

# 30. Mapping Failure 테스트

mapping에 필요한 필드가 없는 fixture를 만든다.

예:

```text
activityId 없음
startedAt 없음
지원하지 않는 type
```

기대 결과:

```text
ActivityRaw 저장됨
Activity 저장되지 않음
명확한 domain/application exception 발생
```

Raw-first 동작이 실제로 보장되는지 검증한다.

---

# 31. Unsupported Activity 테스트

예:

```text
SWIMMING
```

같은 지원하지 않는 Garmin 활동을 fixture로 넣는다.

기대:

```text
Raw 저장
Activity 미생성
UNSUPPORTED_GARMIN_ACTIVITY_TYPE 계열 exception
```

임의로 RUN에 매핑하지 않는다.

---

# 32. Existing ActivityRaw Auto-link 정책 검토

현재 ActivityRawService는 동일 external key의 Activity가 있으면 자동 link한다고 보고되어 있다.

새 Activity ingestion 후 raw가 아직 activity와 연결되지 않은 경우 반드시 연결되어야 한다.

예:

```text
Raw saved
activity_id = null

Activity upsert
activity_id = 123

Raw link
activity_id = 123
```

기존 service를 재사용할 수 있으면 재사용한다.

중복 책임을 만들지 않는다.

---

# 33. API

이번 단계에서는 Garmin ingestion HTTP API를 만들지 않는다.

즉:

```text
POST /api/v1/garmin/ingest
POST /api/v1/garmin/reprocess
```

같은 API를 추가하지 않는다.

이유:

실제 인증 / client / scheduler가 없는 상태에서 공개 endpoint가 필요하지 않다.

Service + tests로 검증한다.

---

# 34. Database Migration

가능하면 이번 단계에서는 새 DB migration이 필요하지 않게 구현한다.

현재 Activity / ActivityRaw schema 안에서 처리한다.

정말 schema 변경이 필요한 경우에만:

```text
V4__...
```

를 추가한다.

기존:

```text
V1
V2
V3
```

파일은 절대 수정하지 않는다.

---

# 35. 테스트 요구사항

기존 20개 테스트는 모두 계속 PASS해야 한다.

추가로 최소 다음 테스트를 작성한다.

### Mapper

```text
Garmin RUN mapping
Garmin TREADMILL_RUN mapping
Garmin INDOOR_CYCLING mapping
unsupported type mapping failure
missing required field failure
time conversion
duration unit conversion
```

### Ingestion

```text
first ingestion creates ActivityRaw
first ingestion creates Activity
raw links to Activity
```

### Idempotency

```text
same Garmin activity ingested twice
→ Activity 1 row
→ ActivityRaw 1 row
```

### Update

```text
same externalId + changed payload
→ same Activity ID
→ normalized values updated
→ same ActivityRaw ID
→ raw payload updated
```

### Failure

```text
invalid payload
→ raw persisted
→ Activity not created
```

### Reprocess

```text
ActivityRaw exists
→ reprocess
→ Activity created or updated
```

---

# 36. Fixture Loader

테스트에서 fixture를 읽는 helper를 만들어도 된다.

예:

```text
GarminFixtureLoader
```

단 테스트 편의용이며 production package에 불필요하게 넣지 않는다.

Jackson ObjectMapper를 재사용한다.

---

# 37. 테스트 Isolation

테스트 사이에서 DB 데이터가 섞이지 않게 한다.

현재 test infrastructure를 유지하되:

```text
repository.deleteAll()
```

남발보다는 적절한 transaction rollback이나 기존 테스트 pattern을 따른다.

---

# 38. PostgreSQL 검증

가능하다면 기존 Phase 2에서 사용한 PostgreSQL 검증 방식으로 전체 테스트를 다시 실행한다.

Docker가 없으면 필수는 아니다.

다만 최소:

```text
H2 + Flyway
```

기본 테스트는 모두 통과해야 한다.

PostgreSQL 테스트를 수행했다면 Work Order에 명확히 기록한다.

---

# 39. No Network Test

이번 단계의 모든 테스트는 network 없이 PASS해야 한다.

테스트 수행 중:

```text
Garmin
Intervals.icu
외부 HTTP
```

호출이 없어야 한다.

---

# 40. 기존 API 회귀

다음 API는 그대로 동작해야 한다.

```text
GET /api/v1/health

POST /api/v1/activities

GET /api/v1/activities/{id}

GET /api/v1/activities

GET /actuator/health
```

기존 contract를 불필요하게 변경하지 않는다.

---

# 41. README

README에 Garmin ingestion architecture를 간략하게 추가한다.

예:

```text
## Garmin ingestion

Garmin activity ingestion is currently implemented as an
offline fixture-driven pipeline.

Garmin raw payload
→ activity_raw
→ GarminActivityMapper
→ activity

Actual Garmin authentication/network integration is not implemented yet.
```

구현되지 않은 실제 Garmin 연결을 완료된 것처럼 작성하지 않는다.

---

# 42. Architecture 문서 표현

Work Order에 다음 데이터 흐름을 남긴다.

```text
Garmin payload
      |
      v
ActivityRawService
      |
      v
activity_raw (JSONB)
      |
      v
GarminActivityMapper
      |
      v
ActivityService.upsert
      |
      v
activity
```

실패 시:

```text
Garmin payload
      |
      v
activity_raw
      |
      v
mapping failure

raw preserved
activity not created
```

---

# 43. 향후 GarminClient 연결 지점

Phase 3B가 들어와도 ingestion core가 변경되지 않게 만든다.

향후 흐름:

```text
GarminClient
    ↓
JsonNode
    ↓
GarminActivityIngestionService
```

즉 network layer와 ingestion layer를 분리한다.

---

# 44. 향후 Batch Ingestion 고려

이번 단계에서는 단일 payload ingestion을 구현한다.

예:

```java
ingest(JsonNode payload)
```

여러 activity fetch 시:

```text
for each payload
→ ingest()
```

할 수 있는 구조면 충분하다.

지금부터 batch framework를 만들 필요는 없다.

---

# 45. Error Model

Garmin mapping 관련 exception은 구분 가능하게 한다.

예:

```text
GARMIN_ACTIVITY_ID_MISSING
GARMIN_ACTIVITY_START_TIME_MISSING
UNSUPPORTED_GARMIN_ACTIVITY_TYPE
GARMIN_ACTIVITY_MAPPING_FAILED
```

하지만 exception class를 지나치게 세분화하지 않는다.

하나의:

```text
GarminActivityMappingException
```

+ reason/code 방식도 괜찮다.

---

# 46. ActivityService 기존 API 영향 최소화

기존 manual Activity POST API는 계속 create semantics를 유지해도 된다.

즉:

```text
HTTP POST Activity
→ duplicate 409
```

를 유지하고,

Garmin ingestion 내부만:

```text
upsertExternalActivity()
```

를 사용한다.

사용자용 create API 의미를 upsert로 바꾸지 않는다.

---

# 47. Audit Field

Activity update 시:

```text
createdAt
→ 유지

updatedAt
→ 갱신
```

ActivityRaw update 시:

```text
createdAt
→ 유지

fetchedAt
→ 갱신
```

하도록 검증한다.

---

# 48. Concurrency 고려

이번 단계에서 복잡한 distributed lock은 구현하지 않는다.

DB unique constraint를 최종 방어선으로 유지한다.

동일 external activity가 동시에 ingest될 가능성은 향후 scheduler 구현 단계에서 추가 검토한다.

필요 이상의 lock / retry framework는 추가하지 않는다.

---

# 49. 코드 품질 원칙

- Lombok 추가 금지
- DTO는 record 사용 가능
- Entity를 Controller DTO로 직접 노출하지 않음
- Mapper에 DB 접근 금지
- Service에 JSON field parsing 로직을 흩뿌리지 않음
- raw payload 전체 로그 출력 금지
- unnecessary interface 금지
- network mock framework 불필요
- 미래 기능 예상만으로 과도한 abstraction 금지

---

# 50. Definition of Done

아래가 모두 만족되어야 완료다.

1. Garmin fixture 3종 이상 추가
2. Garmin raw payload parser / DTO 구현
3. GarminActivityMapper 구현
4. RUN mapping
5. TREADMILL_RUN mapping
6. INDOOR_CYCLING mapping
7. unsupported type failure
8. Activity upsert 구현
9. raw-first ingestion 구현
10. ActivityRaw save/update 재사용
11. raw ↔ Activity linking 구현
12. 동일 activity 재수집 idempotency
13. 변경 payload Activity update
14. 변경 payload ActivityRaw update
15. reprocess 구현
16. mapping 실패 시 raw 보존
17. Garmin HTTP API 미노출
18. 실제 Garmin network 호출 없음
19. 기존 20개 테스트 PASS
20. 신규 mapper 테스트 PASS
21. 신규 ingestion 테스트 PASS
22. 신규 idempotency 테스트 PASS
23. 신규 reprocess 테스트 PASS
24. README 업데이트
25. Work Order instruction 저장
26. Work Order result 저장
27. 민감정보 없음 확인
28. git diff 검토
29. 테스트 PASS
30. commit
31. origin/main push

---

# 51. 검증 명령

Windows PowerShell 기준:

```powershell
cd server

$env:JAVA_HOME="C:\Program Files\Java\jdk-21"

.\gradlew clean test
```

결과는 반드시 기록한다.

가능하면:

```powershell
.\gradlew bootRun --args='--spring.profiles.active=test'
```

기존 health API도 확인한다.

```powershell
Invoke-RestMethod http://localhost:8080/api/v1/health
```

이번 단계에서는 HTTP로 Garmin ingestion을 테스트하지 않는다.

---

# 52. Git 검증

작업 완료 후:

```bash
git status
git diff
git diff --cached
```

다음을 확인한다.

```text
credential 없음
token 없음
private Garmin data 없음
실제 사용자 활동 위치/GPS 없음
.env 없음
build 파일 없음
```

---

# 53. Commit

권장 commit message:

```text
feat: add Garmin activity ingestion core
```

테스트 성공 후:

```bash
git add .
git commit -m "feat: add Garmin activity ingestion core"
git push origin main
```

---

# 54. 최종 완료 보고

완료 후 아래 형식으로 보고한다.

## 구현 완료

```text
Garmin fixture:
Mapper:
Ingestion service:
Activity upsert:
Reprocess:
```

## Ingestion flow

```text
raw save
mapping
activity upsert
raw link
```

실패 시 raw 보존 방식도 설명한다.

## Idempotency

```text
same external activity ingestion:
Activity rows:
ActivityRaw rows:

changed payload ingestion:
Activity ID:
ActivityRaw ID:
updated fields:
```

## Mapping

```text
RUN:
TREADMILL_RUN:
INDOOR_CYCLING:
unsupported:
```

## 테스트

```text
gradlew clean test:

total:
passed:
failed:
```

기존 테스트와 신규 테스트 개수도 가능하면 분리한다.

## Database

새 migration 추가 여부:

```text
migration:
schema changed:
```

## Git

```text
branch:
commit:
push:
```

## 제한사항

현재 구현되지 않은 내용을 정확히 작성한다.

특히:

```text
Garmin 인증 미구현
Garmin network client 미구현
Garmin 실제 데이터 fetch 미구현
```

## 다음 Phase

Phase 3B 후보:

```text
1. 기존 RunningAI Garmin 수집 방식 조사
2. Garmin session / credential strategy 결정
3. GarminClient 구현
4. Garmin recent activity fetch
5. fetched payload → 현재 ingestion core 연결
6. incremental ingestion / sync cursor
7. scheduler 연결
```

다음 Phase 작업은 자동으로 시작하지 않는다.

---

# 55. 가장 중요한 원칙

이번 작업의 성공 기준은 Garmin 서버에 접속하는 것이 아니다.

성공 기준은 다음이다.

```text
어떤 Garmin JSON payload가 들어와도

원본을 먼저 안전하게 보존하고

RunningAI Activity로 정규화하며

같은 활동을 여러 번 받아도 중복 없이 갱신하고

과거 raw payload를 언제든 다시 처리할 수 있는

안정적인 ingestion core를 완성하는 것
```

실제 Garmin 인증과 네트워크 접근은 이 core 위에 다음 단계에서 얹는다.
