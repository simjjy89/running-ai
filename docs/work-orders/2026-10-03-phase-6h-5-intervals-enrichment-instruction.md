# RunningAI Phase 6H-5 — Intervals.icu Analysis Enrichment

## 0. 작업 환경

이번 작업은 **Main PC**에서 수행한다.

Repository:

`C:\running-ai-github`

Baseline:

`3faa065`

현재 상태:

- Phase 6H-1A Detailed Activity Foundation ✅
- Phase 6H-1B Garmin Live Contract ✅
- Phase 6H-1C Sample Fidelity ✅
- Phase 6H-4 Running Analysis Engine ✅
- Java 21 System Normalization ✅
- Spring regression baseline: 1011 passed
- Python baseline: 134 passed
- Garmin detailed activity = LIVE_VERIFIED
- Running Analysis Engine = RUNNING_ANALYSIS_V1
- Historical backfill = NOT_RUN
- TrainingContext V2 = NOT_RUN
- External workout writes = 0

Runtime safety:

- `WORKOUT_PUBLISHING_ENABLED=false`
- `WORKOUT_PUBLISHING_SCHEDULER_ENABLED=false`
- `RUNNING_AI_DRAFT_PUBLISHING_ENABLED=false`
- `RUNNINGAI_MCP_ENABLED=false`
- legacy writer tasks Disabled

이번 Phase는 **Intervals.icu를 분석 enrichment source로 추가**한다.

역할:

```text
Garmin
→ sensor/source-of-truth

RunningAI
→ 자체 상세 분석

Intervals.icu
→ training-load / fitness-model enrichment
```

Intervals가 Garmin 원본을 대체하지 않는다.

---

# 1. 작업 기록

지시서:

`docs/work-orders/2026-10-03-phase-6h-5-intervals-enrichment-instruction.md`

결과:

`docs/work-orders/2026-10-03-phase-6h-5-intervals-enrichment-result.md`

실제 API key는:

- 문서
- 로그
- terminal echo
- git
- diff

어디에도 출력하지 않는다.

---

# 2. Git preflight

확인:

- repo = `C:\running-ai-github`
- branch = main
- working tree clean
- origin/main 확인
- `3faa065` ancestor 확인

필요 시:

`git pull --ff-only origin main`

만 사용.

destructive git command 금지.

---

# 3. INTERVALS_API_KEY rotation — 선행 HUMAN GATE

기존 API key는 과거 terminal/chat transcript에 한 번 노출됐다.

따라서 Phase 시작 전에 **반드시 교체한다.**

Claude가 새 API key를 만들거나 추측하지 않는다.

사용자가 Intervals.icu Developer Settings에서 직접:

1. 기존 API key regenerate/clear
2. 새 API key 생성
3. Main PC local `.env`의 `INTERVALS_API_KEY` 변경

을 수행한다.

새 key 값은 출력하지 않는다.

검증은 값이 아닌 상태만 사용:

```text
INTERVALS_API_KEY=<SET>
```

가능하면 old/new key의 안전한 fingerprint만 비교한다.

새 key가 기존 key와 다름을 확인하면:

`INTERVALS_API_KEY_ROTATED`

기록.

교체 전에는 live Intervals call을 시작하지 않는다.

---

# 4. Read-only 전용 client 분리

기존:

`HttpIntervalsWorkoutClient`

는 create/update 기능을 포함한다.

Enrichment가 이 client를 사용하지 않게 한다.

새 read-only boundary를 만든다.

예:

```text
IntervalsReadClient
HttpIntervalsReadClient
```

허용 메서드:

```text
listActivities(...)
getActivity(...)
getWellness(...)
```

필요 시:

`getActivity(..., intervals=true)`

도 read-only capability로 추가 가능.

이 client에는:

- POST 없음
- PUT 없음
- DELETE 없음

을 구조적으로 보장한다.

---

# 5. Authentication

기존 `IntervalsProperties`와 동일한 API key를 사용한다.

Personal API key auth:

```text
username = API_KEY
password = INTERVALS_API_KEY
```

기존 auth generation 코드가 안전하면 공유 helper로 추출 가능.

API key를 exception/log/toString에 넣지 않는다.

---

# 6. Error policy

Garmin connector와 같은 fail-closed 원칙.

- no automatic retry
- retry loop 금지
- 401 → AUTH_FAILED
- 403 → FORBIDDEN
- 429 → RATE_LIMITED
- timeout 명확히 구분
- 5xx → UPSTREAM_ERROR

429 발생 시 자동으로 기다렸다 재시도하지 않는다.

`Retry-After`가 응답에 있어도 이번 Phase에서는 기록만 하고 STOP.

---

# 7. Phase 6H-5A — Live read-only contract discovery

production mapping을 추측해서 먼저 만들지 않는다.

API key rotation 후 실제 Intervals 데이터를 소량 조회한다.

## Activity list

대표 activity들이 포함되는 좁은 날짜 범위:

```text
GET /api/v1/athlete/0/activities?oldest=...&newest=...
```

Intervals 공식 API의 completed activities endpoint를 사용한다.

응답 RAW는 local-only:

`.runtime/intervals-live-contract/`

에 저장 가능.

gitignore 확인 필수.

---

# 8. Activity list에서 확인할 항목

실제 response key를 inventory한다.

특히 찾아볼 것:

```text
id
start date/time
local start date/time
type
name
distance
moving time
elapsed time

source
source id
external id

icu_training_load
icu_intensity

icu_ctl
icu_atl

pace / HR / cadence related summary
training effect equivalents if present
```

위 이름을 실제 필드명이라고 가정하지 않는다.

**live response에 실제 존재하는 key만 확정한다.**

---

# 9. Activity detail probe

대표 Intervals activity 3~4건에 대해 read-only:

```text
GET /api/v1/activity/{activityId}
```

를 조사한다.

가능하면 동일 activity에:

```text
GET /api/v1/activity/{activityId}?intervals=true
```

도 한 번 비교한다.

2026년 Intervals 사용자 사례에서는 `intervals=true`로 상세 interval/lap 데이터를 가져올 수 있다고 보고되어 있지만, production 구현은 반드시 현재 실제 계정 응답으로 다시 확인한다.

Garmin에 이미 더 상세한 lap/sample이 있으므로 Intervals interval detail을 중복 normalise할 필요는 없다.

하지만 future reprocessing을 위해 raw 보존 가치가 있다면 저장할 수 있다.

---

# 10. Wellness / fitness-model probe

좁은 날짜 범위로:

```text
GET /api/v1/athlete/0/wellness?oldest=...&newest=...
```

호출.

Intervals는 이 endpoint에 계산된 fitness/fatigue 데이터를 제공한다.

확인할 실제 field 후보:

```text
id / date

ctl
atl
ctlLoad
atlLoad
rampRate
```

live response로 실제 이름/타입을 확정한다.

### 매우 중요

wellness의:

`fatigue`

필드는 **subjective fatigue**일 수 있다.

계산된 Fitness chart fatigue와 동일하게 취급하지 않는다.

RunningAI mapping:

```text
CTL → Intervals calculated fitness
ATL → Intervals calculated fatigue
```

`fatigue`라는 subjective wellness field를 ATL에 매핑 금지.

---

# 11. Form

Intervals 계산 모델에서 Form에 해당하는 값이 live API field로 직접 존재하는지 먼저 확인한다.

직접 field가 있으면 source value를 보존한다.

없다면 RunningAI derived value로:

```text
form = ctl - atl
```

계산 가능.

이 경우 명확히:

`RunningAI derived from Intervals CTL/ATL`

로 표시한다.

source field인 것처럼 위장하지 않는다.

---

# 12. Raw-first Intervals storage

Garmin과 마찬가지로 Intervals에서도 중요한 live response를 먼저 raw로 보존한다.

권장 신규 구조:

`intervals_raw_payload`

또는 기존 architecture에 더 자연스러운 generic external raw table.

최소:

```text
id
athlete_id
payload_type
external_id nullable
effective_date nullable
payload JSONB
fetched_at
created_at
updated_at
```

payload type 예:

```text
ACTIVITY
ACTIVITY_DETAIL
ACTIVITY_INTERVALS
WELLNESS_DAY
```

실제 구현된 capability만 enum에 넣는다.

---

# 13. Activity identity linking

현재 RunningAI `activity`는 Garmin external identity를 기반으로 만들어져 있다.

이번 Phase에서 **Intervals activity를 별도 RunningAI Activity row로 중복 생성하지 않는다.**

동일 physical workout을 연결한다.

새 mapping table 권장:

`activity_source_link`

예:

```text
id
activity_id
external_source
external_activity_id
match_method
matched_at
```

unique:

```text
external_source + external_activity_id
activity_id + external_source
```

한 RunningAI activity에:

```text
GARMIN    <garmin activity id>
INTERVALS <intervals activity id>
```

를 연결할 수 있게 한다.

기존 Garmin identity를 파괴적으로 migrate하지 않는다.

---

# 14. Matching strategy — 우선순위

Intervals activity와 Garmin activity의 연결은 다음 순서를 사용한다.

## Priority 1 — explicit source identifier

Intervals payload 안에 Garmin activity id 또는 동일한 stable upstream identifier가 실제로 존재하고 검증 가능할 경우 사용.

match method:

`SOURCE_ID`

## Priority 2 — external ID

확실하게 동일 activity를 지칭하는 `external_id`가 존재할 경우.

`EXTERNAL_ID`

단 이름이 같다는 이유만으로 사용하지 않는다.

## Priority 3 — deterministic composite matching

explicit ID가 없을 때만:

```text
activity type
start timestamp
duration
distance
```

등 live-confirmed common fields를 이용한다.

시간대는 athlete timezone을 정확히 처리한다.

---

# 15. Fuzzy match 안전 규칙

Composite matching은 자동으로 가장 가까운 한 건을 선택하지 않는다.

후보가 정확히 1개일 때만 연결한다.

예:

```text
start time difference <= tolerance
duration difference <= tolerance
distance difference <= tolerance
compatible activity type
```

tolerance 값은 실제 4건 비교 결과를 보고 정한다.

근거 없이 예:

`5분`
`1km`

같은 넓은 tolerance를 만들지 않는다.

실제 Garmin↔Intervals 차이를 측정한 후 최소 범위로 확정한다.

후보:

- 0건 → UNMATCHED
- 1건 → MATCHED
- 2건 이상 → AMBIGUOUS

AMBIGUOUS는 자동 연결 금지.

---

# 16. Match evidence 저장

향후 디버깅 가능하도록 source link에 technical evidence를 남긴다.

예:

```text
match_method
start_time_delta_seconds
duration_delta_seconds
distance_delta_meters
```

이 값들은 technical matching evidence이지 training metric이 아니다.

confidence 점수를 임의로 만들 필요는 없다.

---

# 17. Intervals activity enrichment table

새 table 권장:

`activity_intervals_metrics`

1:1 with RunningAI activity.

실제 LIVE_VERIFIED fields 중 coaching/analysis에 유용한 것만 first-class column으로 올린다.

최소 후보:

```text
activity_id
intervals_activity_id

training_load
intensity

ctl_after_activity
atl_after_activity

source_updated_at
fetched_at
```

정확한 column 이름과 의미는 live contract 후 확정한다.

존재하지 않는 field는 만들지 않는다.

추가 Intervals 값은 raw JSON에 보존한다.

---

# 18. Daily fitness enrichment

별도 table:

`intervals_fitness_daily`

권장.

identity:

```text
athlete_id
date UNIQUE
```

LIVE_VERIFIED 가능한 경우:

```text
ctl
atl
form
ramp_rate
ctl_load
atl_load
source_updated_at
fetched_at
```

`form`이 source에 없다면:

```text
derived_form = ctl - atl
```

처럼 provenance를 명확히 한다.

---

# 19. Garmin Recovery와 Wellness 중복 원칙

RunningAI에는 이미 Garmin Recovery가 있다.

따라서 Intervals wellness에서:

- HRV
- sleep
- resting HR
- subjective wellness

등이 존재해도 이번 Phase에서 Garmin Recovery 값을 덮어쓰지 않는다.

역할 분리:

```text
Garmin Recovery
→ recovery source-of-truth

Intervals Wellness
→ fitness model / load enrichment
```

다른 recovery fields는 raw에 보존만 할 수 있다.

---

# 20. RunningAI Analysis와 Intervals 값도 합치지 않는다

예:

```text
RunningAI:
speed_hr_decoupling_percent

Intervals:
icu_training_load / ctl / atl
```

둘은 다른 provenance다.

`activity_analysis`에 Intervals 값을 밀어 넣지 않는다.

별도 enrichment table에서 유지한다.

향후 TrainingContext V2 builder가 둘을 조합한다.

---

# 21. Read client tests

최소:

- activity list URL
- oldest/newest parameter
- athlete id = 0
- activity detail GET
- wellness range GET
- Basic authorization
- no API key → no HTTP request
- 401
- 403
- 429
- timeout
- malformed JSON
- no retry

그리고 read client type에는 write method가 없어야 한다.

---

# 22. Live matching smoke

기존 Main PC 4 activity를 기준으로 Intervals에서 같은 activity가 존재하는지 확인한다.

대상:

- outdoor/long run
- track interval
- treadmill
- indoor cycling

각각:

```text
Garmin RunningAI activity
↔
Intervals activity
```

match 결과를 기록.

가능하면 4/4를 목표로 하지만 Intervals에 실제 activity가 없다면 억지 생성하지 않는다.

`UNMATCHED`는 정상 결과다.

---

# 23. Matching live contract

각 matched pair에 대해 비교:

```text
start time delta
duration delta
distance delta
sport/type
```

이를 기반으로 production matching tolerance를 결정한다.

문서에 exact rule을 기록한다.

특정 Garmin/Intervals pair를 맞추기 위해 magic value를 만들지 않는다.

---

# 24. Activity enrichment smoke

matched activity에 대해:

```text
Intervals raw fetch
→ raw stored
→ source linked
→ metrics normalized
```

확인.

같은 activity를 두 번 enrichment한다.

기대:

- raw duplicate 없음
- source link duplicate 없음
- metrics duplicate 없음
- update/upsert deterministic
- content 동일하면 결과 동일

---

# 25. Daily fitness smoke

90일을 받지 않는다.

이번 Phase에서는 최근:

`7~14일`

정도의 좁은 범위만 허용.

목적:

- CTL contract
- ATL contract
- daily load fields
- date semantics
- null semantics

확인.

historical backfill이 아니다.

---

# 26. Rate-limit visibility

Intervals 응답 header에서 가능하면:

```text
X-RateLimit-Limit
X-RateLimit-Remaining
Retry-After
```

를 transport/debug 수준에서 관찰할 수 있게 한다.

API key 호출은 현재 공식 안내상:

- 5000/day
- 2500/rolling 15m
- additional 10 requests/sec/IP

제한이 있다.

이번 Phase 호출은 sequential.

bulk parallelisation 금지.

---

# 27. Sensitive logs

절대 로그하지 않는다:

- Authorization header
- API key
- response raw body 전체
- secret fingerprint 전체

허용:

```text
Intervals request completed:
endpoint=activities
status=200
items=4
remaining-rate-limit=<sanitized>
```

---

# 28. Manual API

read-only/manual trigger API를 추가할 수 있다.

예:

```text
POST /api/v1/intervals/enrichment/activity/{activityId}
POST /api/v1/intervals/enrichment/fitness?oldest=...&newest=...
GET  /api/v1/activities/{activityId}/intervals
GET  /api/v1/intervals/fitness?date=...
```

POST라고 해도 **RunningAI local DB refresh trigger**일 뿐 Intervals에는 GET만 해야 한다.

Controller/API naming은 기존 repository convention을 따른다.

---

# 29. No automatic scheduler

이번 Phase에서:

- Intervals enrichment scheduler
- webhook
- periodic sync

추가 금지.

먼저 manual live behaviour를 검증한다.

Phase 7에서 자동화를 논의한다.

---

# 30. Database migrations

현재 V16까지 존재한다.

기존 migration 수정 금지.

예:

```text
V17__create_activity_source_link.sql
V18__create_intervals_enrichment.sql
```

또는 논리적 분리.

검증:

- H2
- PostgreSQL 17 throwaway
- Main live DB
- 기존 Garmin/activity/analysis 데이터 보존

repair/drop/truncate 금지.

---

# 31. Source link future-proofing

`ExternalSource`에 INTERVALS가 이미 없다면 추가할 수 있다.

하지만 현재 Garmin Activity row의 identity semantics를 한 번에 전면 개편하지 않는다.

이번 목적:

```text
existing RunningAI activity
+
additional Intervals reference
```

이다.

---

# 32. Raw retention

Intervals activity detail은 Garmin보다 덜 원천적이지만 분석 결과가 나중에 바뀔 수 있다.

따라서 live response를 raw-first 저장하는 것이 좋다.

이후 mapper가 바뀌면:

```text
reprocess
```

만으로 normalized enrichment를 재생성 가능하게 한다.

reprocess는 Intervals API call = 0.

---

# 33. Reprocess

지원 권장:

```text
POST .../reprocess
```

stored Intervals raw:

→ metrics normalization

→ source link validation

Garmin/Intervals network call 없음.

테스트:

- raw unchanged
- metrics same
- duplicates 0

---

# 34. Fitness terminology

코드/DB/documentation에서 다음을 명확히 한다.

```text
CTL = Intervals calculated fitness
ATL = Intervals calculated fatigue
Form = CTL - ATL, when derived
```

다음과 혼동 금지:

```text
wellness.fatigue
```

이는 subjective fatigue field일 수 있다.

따라서 DB에 단순:

`fatigue`

라는 ambiguous column을 만들지 않는다.

권장:

```text
ctl
atl
derived_form
```

---

# 35. Activity load terminology

Intervals activity 응답에서 실제 확인된 training-load field의 정확한 이름과 의미를 사용한다.

예상 이름을 production mapper에 먼저 박지 않는다.

Live contract 결과로:

- exact field
- numeric type
- nullability

확정 후 first-class mapping.

---

# 36. No duplicate detailed sample ingestion

Intervals API가 second-by-second stream/interval 상세를 제공하더라도 이번 Phase에서:

`activity_sample`

을 Intervals 데이터로 덮어쓰지 않는다.

Garmin sample은 source-of-truth.

Intervals detailed response는:

- raw 보존
- analysis enrichment

용도다.

---

# 37. Analysis comparison은 아직 판단하지 않는다

RunningAI load와 Intervals load가 다르더라도:

```text
Intervals is correct
RunningAI is wrong
```

같은 판단 금지.

둘을 provenance별로 보존한다.

향후 TrainingContext V2에서는 둘 다 Claude에게 의미와 함께 제공할 수 있다.

---

# 38. Phase 6H-5에서 확보할 핵심 결과

최종적으로 activity 하나는:

```text
RunningAI Activity
│
├─ Garmin
│   ├─ detail
│   ├─ laps
│   ├─ zones
│   └─ full samples
│
├─ RunningAI Analysis
│   ├─ decoupling
│   ├─ interval repeatability
│   ├─ HR progression
│   └─ recovery HR
│
└─ Intervals
    ├─ training load
    ├─ intensity
    ├─ CTL
    └─ ATL
```

형태가 되어야 한다.

---

# 39. Tests

최소:

### Contract mapper

- actual live-shaped activity fixture
- wellness fixture
- null fields
- unknown additional fields
- numeric values

### Activity matching

- explicit id exact match
- unique composite match
- no candidate
- two candidates → AMBIGUOUS
- time-zone handling
- duration difference
- distance difference

### Persistence

- raw first
- source link insert
- duplicate prevention
- enrichment upsert
- second run idempotent
- reprocess no network

### Fitness

- CTL
- ATL
- both
- missing CTL
- missing ATL
- derived form only when both available
- subjective fatigue never mapped to ATL

### API

- manual enrichment
- query stored result
- activity not found
- Intervals key missing
- auth error
- rate limit error

---

# 40. Live contract fixtures

Live response를 그대로 commit하지 않는다.

실제 response 확인 후:

`LIVE_SHAPE.ANONYMISED`

fixture 생성.

익명화:

- activity id
- athlete id
- dates
- notes
- GPS/location
- personal names
- source ids

shape와 field name만 보존.

---

# 41. Regression

코드 변경 후:

```text
gradlew clean test
```

현재 Spring baseline:

`1011 passed`

→ 증가해야 함.

Python connector는 변경하지 않는다.

baseline:

`134 passed`

PostgreSQL 관련 tests도 V17+ migration과 enrichment persistence를 포함해 실행한다.

---

# 42. Live call budget

이번 Phase에서는 Intervals 호출을 최소화한다.

대략:

```text
1 activity list
3~4 activity details
필요 시 1~2 intervals=true detail
1 wellness range
```

정도로 시작.

실제 필요할 때만 추가.

목표:

`< 20 Intervals live GET calls`

429 발생 시 즉시 중단.

---

# 43. Garmin call budget

이번 Phase에는 Garmin을 호출할 이유가 없다.

기존 DB 데이터를 사용한다.

목표:

`Garmin API calls = 0`

---

# 44. Historical backfill 금지

아직:

```text
90-day Garmin backfill
90-day Intervals enrichment
28-day Recovery backfill
```

모두 하지 않는다.

이번 Phase는 contract + 구조 + 소수 smoke만 한다.

---

# 45. TrainingContext V2 금지

이번 Phase에서 Claude context를 변경하지 않는다.

먼저:

- Garmin detailed
- RunningAI Analysis
- Intervals enrichment

세 계층이 안정적으로 저장되는 것까지 확인한다.

TrainingContext 통합은 6H-7.

---

# 46. Runtime final state

종료 시:

```text
WORKOUT_PUBLISHING_ENABLED=false
WORKOUT_PUBLISHING_SCHEDULER_ENABLED=false
RUNNING_AI_DRAFT_PUBLISHING_ENABLED=false
RUNNINGAI_MCP_ENABLED=false
```

legacy writer Disabled 유지.

External workout writes:

`0`

---

# 47. Architecture docs

작성:

`docs/architecture/intervals-enrichment.md`

반드시 포함:

```text
Garmin = source/sensor truth

RunningAI Analysis =
RunningAI-derived objective metrics

Intervals =
training-model enrichment
```

그리고:

```text
CTL ≠ subjective fatigue
ATL = calculated fatigue
wellness.fatigue = subjective field
```

를 명시한다.

또 activity matching algorithm과 ambiguity behaviour를 문서화한다.

---

# 48. Completion criteria

다음 모두 만족 시 Phase 완료:

```text
INTERVALS_API_KEY rotated ✅

Intervals activity list LIVE_VERIFIED ✅
Intervals activity detail LIVE_VERIFIED ✅
Intervals wellness fitness fields LIVE_VERIFIED ✅

Read-only client ✅
No write methods ✅
No retries ✅

Garmin↔Intervals matching ✅
Ambiguous match fail-closed ✅
activity_source_link ✅

Intervals raw-first storage ✅
activity enrichment ✅
daily CTL/ATL enrichment ✅

subjective fatigue != ATL ✅
provenance preserved ✅

reprocess network calls = 0 ✅
idempotency ✅

existing activity smoke ✅
daily fitness smoke ✅

Garmin API calls = 0
Intervals write calls = 0
Historical backfill = NOT_RUN
TrainingContext V2 = NOT_RUN
External workout writes = 0

Spring tests GREEN
PostgreSQL tests GREEN
working tree clean
origin/main in sync
```

완료 후:

`PHASE_6H_5_INTERVALS_ENRICHMENT_READY`

보고하고 멈춘다.

---

# 49. 최종 보고

1. baseline SHA
2. final SHA
3. API key rotation status
4. Intervals auth validation
5. activity-list endpoint/shape
6. activity-detail endpoint/shape
7. `intervals=true` result
8. wellness endpoint/shape
9. confirmed activity fields
10. confirmed CTL field
11. confirmed ATL field
12. Form source/derived 여부
13. subjective fatigue handling
14. raw payload tables/types
15. source-link table
16. activity-matching priority
17. exact composite tolerance
18. matched activity count
19. unmatched count
20. ambiguous count
21. enrichment table/fields
22. daily fitness fields
23. idempotency
24. reprocess network calls
25. Intervals GET call count
26. 401/403/429 count
27. rate-limit remaining if available
28. Garmin calls
29. Intervals writes
30. historical backfill status
31. TrainingContext V2 status
32. external workout writes
33. publishing switches
34. Flyway migrations
35. Spring tests
36. Python tests
37. PostgreSQL tests
38. commits
39. push
40. working tree
41. known limitations
42. exact inputs recommended for Phase 6H-6/6H-7
