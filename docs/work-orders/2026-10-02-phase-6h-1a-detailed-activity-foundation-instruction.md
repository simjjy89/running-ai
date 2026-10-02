# RunningAI Phase 6H-1A — Garmin Detailed Activity Contract & Foundation

작업 PC는 외부 PC이며 repository root는:

`C:\running-ai`

이다.

현재 기준:

- branch: main
- origin/main latest known: `f5db39b`
- Phase 6G.1 완료
- Spring/Kotlin baseline: 858 passed
- Python connector baseline: 88 passed
- actual external workout writes: 0
- 이 PC에서는 Garmin login/live API가 corporate TLS interception 때문에 차단되어 있다.

이번 작업에서는 Garmin login을 다시 시도하지 않는다.

`verify=False`, `--insecure`, `PYTHONHTTPSVERIFY=0` 또는 SSL verification disable을 절대로 사용하지 않는다.

---

## 목적

RunningAI의 Activity 수집을 기존 summary-only 구조에서:

`Maximum-detail Raw-first Activity Pipeline`

으로 확장하기 위한 첫 단계다.

최종 방향:

Garmin Activity List
→ Activity Summary
→ Activity Detail
→ Laps / Splits
→ HR Zones
→ Streams / Samples
→ FIT/raw when available
→ RunningAI Raw Layer
→ Detailed Normalization
→ Analysis Features
→ TrainingContext V2

이번 Phase에서는 실제 Garmin live response를 추측해서 확정하지 않는다.

외부 PC에서는:

1. 설치된 python-garminconnect 0.3.16 소스 조사
2. 상세 API capability inventory
3. provider-neutral detailed data model 설계
4. connector abstraction/fake-based contract
5. DB migration 초안/구현
6. 상세 ingestion foundation
7. 테스트

까지 수행한다.

실제 Garmin live contract verification은 Main PC Phase 6H-1B로 남긴다.

---

## 1. Preflight

작업 시작 전에:

- `C:\running-ai`
- main
- working tree clean
- `f5db39b` ancestor 확인
- origin/main과 관계 확인

dirty tree라면 reset/clean하지 말고 STOP한다.

instruction을 먼저:

`docs/work-orders/2026-10-02-phase-6h-1a-detailed-activity-foundation-instruction.md`

에 원문 그대로 저장한다.

---

## 2. Installed garminconnect source를 우선 조사

외부 웹 문서를 구현 근거로 삼기 전에 이 PC에 실제 설치된:

`garminconnect==0.3.16`

소스를 직접 조사한다.

Python executable/venv는 repository 환경을 먼저 사용하고, 없으면 현재 외부 PC에서 실제 사용 가능한 venv를 확인한다.

다음과 관련된 Garmin class method를 찾아라.

예:

- activities
- activity details
- splits
- typed splits
- split summaries
- HR zones
- power zones
- exercise sets
- weather
- gear
- FIT/download/export
- activity details / metrics / samples

정확한 함수 이름/signature는 추측하지 말고 설치된 source에서 찾는다.

각 method별로 문서화:

- method name
- arguments
- underlying endpoint if source에 드러나는 경우
- return type/shape
- pagination 여부
- activityId 요구 여부
- raw/detail/split/stream 중 역할
- 호출 한 번당 Garmin request 수
- known exception path

결과를:

`docs/architecture/garmin-detailed-activity-contract-static.md`

에 작성한다.

중요:

이 문서는 LIVE VERIFIED가 아니다.

각 항목을:

`STATIC_SOURCE_CONFIRMED`

로 표시한다.

---

## 3. Capability classification

찾은 Garmin 데이터를 다음 계층으로 분류한다.

### ACTIVITY_LIST

현재 `/activities`가 사용 중인 목록/summary.

### ACTIVITY_DETAIL

한 activity의 전체 summary/detail.

예상 가능한 종류:

- elapsed/moving/timer duration
- distance
- speed/pace
- HR
- cadence
- elevation
- calories
- power
- training effect/load
- device metadata

실제 필드는 source/fixture로 확인된 것만 명시한다.

### LAPS / SPLITS

lap/repetition 단위:

- index
- start
- duration
- distance
- speed/pace
- HR
- cadence
- power
- elevation

실제 반환 여부는 live verification 전에는 확정하지 않는다.

### ZONES

- HR zones
- power zones

### SAMPLES / STREAMS

시간 순서 sample.

특히 metric descriptor + positional values 구조가 존재한다면 descriptor 기반 parsing을 설계한다.

metric array index를 하드코딩하지 않는다.

### RAW FILE

FIT 또는 다운로드 가능한 activity file 관련 capability가 설치된 library에 존재하는지 조사한다.

없으면 없는 것으로 문서화한다.

---

## 4. Raw-first architecture

기존 `activity_raw`를 함부로 변경하거나 제거하지 않는다.

Detailed Activity용 raw storage가 여러 payload type을 보존할 수 있도록 설계한다.

권장 개념:

`activity_raw_payload`

또는 repository convention에 맞는 이름.

최소 identity:

- id
- athlete_id
- source
- external_activity_id
- payload_type
- payload JSONB 또는 binary reference
- fetched_at
- created_at
- updated_at

payload type 예:

- ACTIVITY_LIST
- ACTIVITY_DETAIL
- SPLITS
- TYPED_SPLITS
- SPLIT_SUMMARIES
- HR_ZONES
- POWER_ZONES
- ACTIVITY_DETAILS_STREAM
- FIT

실제 source에서 제공되지 않는 type은 구현할 필요 없다.

중요:

Raw 데이터를 normalize 성공 여부와 관계없이 먼저 저장한다.

mapper 실패가 raw rollback을 유발하지 않도록 기존 raw-first transaction 원칙을 유지한다.

---

## 5. Existing activity identity 재설계 검토

현재:

`activity.external_source + external_id`

가 identity다.

향후 Garmin과 Intervals가 동일한 physical workout을 가리킬 수 있으므로 장기적으로:

`activity_source`

mapping table이 필요한지 설계한다.

단 이번 Phase에서 무리하게 기존 identity 구조를 완전히 migrate하지 않아도 된다.

목표는:

- backward compatibility
- future Garmin + Intervals linking 가능
- 기존 activity row 보존

이다.

필요하면 architecture decision만 먼저 문서화하고 실제 source-link migration은 Intervals enrichment Phase로 미뤄도 된다.

---

## 6. Detailed normalized schema

최대한 상세히 저장하되 모든 Garmin JSON key를 DB column으로 만들지 않는다.

provider-neutral normalized tables를 설계한다.

권장:

### activity_detail

1:1 with activity.

가능 필드:

- elapsed duration
- moving duration
- timer duration
- distance
- average/max speed
- average/best pace
- average/max HR
- average/max cadence
- ascent/descent
- min/max elevation
- calories
- avg/max power
- aerobic training effect
- anaerobic training effect
- Garmin training load
- temperature
- device metadata

각 필드는 실제 contract에서 확인 가능할 때만 map.

모든 optional metric은 nullable.

값을 추정하지 않는다.

### activity_lap

1:N.

최소:

- activity_id
- lap_index
- start_time
- duration
- distance

지원 가능한 경우:

- avg/max HR
- avg/max speed
- pace
- cadence
- power
- ascent/descent
- calories

unique:

`activity_id + lap_index`

### activity_zone

1:N.

예:

- activity_id
- zone_type
- zone_number
- min_value
- max_value
- duration_seconds

zone_type:

- HEART_RATE
- POWER

### activity_sample

1:N.

처음부터 모든 metric을 column으로 강제하지 않는다.

권장:

- activity_id
- sample_index
- timestamp nullable
- elapsed_seconds nullable
- distance
- speed
- heart_rate
- cadence
- power
- elevation
- latitude
- longitude
- temperature
- extra_metrics JSONB

중요:

Garmin native sampling interval을 유지한다.

없는 seconds를 보간하여 fake 1-second data를 만들지 않는다.

unique:

`activity_id + sample_index`

---

## 7. Database migration

현재 Flyway V10까지 존재한다.

다음 migration부터 사용한다.

적용된 V1~V10은 수정하지 않는다.

migration은 H2 / PostgreSQL 둘 다 고려한다.

테이블을 너무 큰 하나의 migration으로 몰아야 할 이유는 없다.

logical migrations로 분리 가능.

기존 activity 데이터가 그대로 유지되어야 한다.

---

## 8. Python connector detailed abstraction

현재 connector는:

`GET /activities`

만 activity 쪽에서 제공한다.

이번 Phase에서는 GarminGateway에 detailed read abstraction을 추가할 수 있다.

단 실제 live call을 수행하지 않는다.

설치된 garminconnect source에서 실제 method signature를 확인한 후 작성한다.

권장 localhost endpoints는 capability에 따라 예:

`GET /activities/{activityId}/detail`

`GET /activities/{activityId}/splits`

`GET /activities/{activityId}/hr-zones`

`GET /activities/{activityId}/samples`

형태로 만들 수 있다.

정확한 endpoint set은 static source inventory 결과로 결정한다.

connector 역할은 최대한 thin하게 유지한다.

- authentication
- Garmin call-through
- upstream error translation
- raw response 반환

비즈니스 normalization을 Python에서 하지 않는다.

Spring에서 normalize한다.

---

## 9. Error policy

기존 connector 원칙 유지:

- no automatic retry
- 401/403/429 → caller에게 명확하게 전달
- auth failure 시 cached gateway invalidation
- credentials/password/token 로그 금지

한 activity의 detail endpoint 하나가 실패했을 때 전체 activity를 어떻게 처리할지는 Spring ingestion policy에서 결정한다.

이번 Phase의 기본 원칙:

summary activity는 이미 저장되어 있더라도 detail collection 실패를 성공으로 숨기지 않는다.

부분 수집 상태를 추적할 수 있는 구조를 설계한다.

예:

- COMPLETE
- PARTIAL
- FAILED

단 enum/name은 repository style에 맞춘다.

---

## 10. Spring detailed source clients

Python localhost endpoints를 호출하는 Spring client를 만든다.

기존 `HttpGarminActivitySource`와 동일한 스타일을 따른다.

예:

`GarminActivityDetailSource`

또는 capability별 source.

역할:

- activity id 전달
- JSON 응답
- connector error mapping
- no retry

Spring client가 metric 의미를 해석하지 않는다.

mapping layer를 별도로 둔다.

---

## 11. Detailed mapper

provider-neutral mapper를 만든다.

예:

- GarminActivityDetailMapper
- GarminLapMapper
- GarminZoneMapper
- GarminSampleMapper

중요:

Garmin-specific field names은 mapper 안에서 끝난다.

domain은 Garmin field name을 노출하지 않는다.

### Sample parsing

만약 response가:

descriptor[]
+
metricValues[]

형태라면:

descriptor.key
→ index

mapping을 먼저 생성한다.

예:

heartRate descriptor가 index 7이라고 응답하면 해당 response에서만 7을 사용한다.

절대로:

`metrics[7] == heart rate`

같이 하드코딩하지 않는다.

unknown descriptor는 `extraMetrics`로 보존할 수 있다.

---

## 12. Detailed ingestion

한 activity의 전체 collection pipeline은 궁극적으로:

Activity list item
→ list RAW 저장
→ base Activity upsert
→ detailed fetch
→ detail RAW
→ detail normalization
→ splits RAW
→ lap normalization
→ zone RAW
→ zone normalization
→ stream RAW
→ sample normalization

형태가 되어야 한다.

각 remote payload는 먼저 raw commit.

그 다음 normalized transaction.

재실행 가능하고 idempotent해야 한다.

동일 Garmin activity id 재수집 시 duplicate detail/lap/sample을 만들지 않는다.

가능하면 replace/upsert semantics를 명시적으로 설계한다.

---

## 13. 아직 Historical Backfill은 하지 않는다

이번 외부 PC에서는 과거 Garmin activity를 실제로 호출하지 않는다.

다음은 금지:

- 90일 Garmin backfill
- live activity detail call
- Garmin login retry
- SSL workaround
- real Intervals call
- real workout publishing

`Historical Backfill = NOT_RUN`

이다.

---

## 14. Fixtures

Live Garmin contract가 없으므로 fixture를 상상해서 production mapper contract로 확정하면 안 된다.

두 종류 fixture를 구분한다.

### Library-shape fixture

설치된 library tests/source에서 직접 확인 가능한 구조.

### Synthetic fixture

우리 domain/test를 위한 synthetic 구조.

synthetic fixture는 파일명/주석에:

`SYNTHETIC_NOT_LIVE_GARMIN`

을 명시한다.

Main PC 6H-1B에서 실제 payload로 교체/확정할 수 있게 한다.

---

## 15. Tests

최소 검증:

- connector detailed methods call correct library method
- endpoint activityId validation
- no retry
- upstream auth/rate limit translation
- raw saved before mapper
- detail idempotency
- lap idempotency
- zone idempotency
- sample idempotency
- unknown metric preserved
- missing optional metric = null
- no fabricated metric
- descriptor-based sample lookup
- existing Activity ingestion regression
- existing Recovery regression
- existing Draft/Publish regression
- Flyway migration H2
- PostgreSQL 가능하면 migration validation

전체:

Spring/Kotlin baseline 858에서 증가해야 한다.

`gradlew clean test`

0 failed.

Python:

현재 baseline 88.

connector 변경이 있으므로 full pytest 수행.

0 failed.

---

## 16. No TrainingContext change yet

이번 Phase에서는 Claude TrainingContext를 변경하지 않는다.

이유:

실제 Garmin detailed contract가 아직 Main PC에서 검증되지 않았다.

먼저 데이터를 losslessly 저장할 기반을 만든다.

TrainingContext V2는:

6H-1B live contract
+
Detailed ingestion live validation
+
RunningAI Analysis Engine

후에 구현한다.

---

## 17. Main-PC handoff artifact

작업 결과 문서에:

`MAIN_PC_6H_1B_LIVE_CHECKLIST`

섹션을 반드시 작성한다.

여기에 Main PC에서 실제 Garmin activity 3~5개를 대상으로 확인해야 할 capability를 자동 생성한다.

권장 sample:

- outdoor run
- treadmill run
- interval workout
- long run
- indoor cycling이 존재하면 cycling

각 activity별로:

- activity id
- detail
- splits
- zones
- samples
- file/FIT capability

를 확인하도록 한다.

개인 activity id는 repository 문서에 commit하지 않는다.

placeholder만 기록한다.

---

## 18. Documentation

작성:

`docs/architecture/detailed-activity-v2.md`

내용:

Garmin raw
→ normalized
→ feature extraction
→ Intervals enrichment
→ TrainingContext V2

그리고 각 계층의 Source of Truth를 명확히 한다.

### Garmin

sensor/source-of-truth

### Intervals

analysis enrichment

### RunningAI

derived features and coaching context

---

## 19. Git

논리적 commit.

예:

- `feat: add detailed activity storage model`
- `feat: add Garmin detailed activity connector foundation`
- `test: cover detailed activity ingestion`
- `docs: define detailed activity v2 architecture`

full tests GREEN + working tree clean이면 safe fast-forward push.

force push 금지.

---

## 20. Final report

최종 보고:

- baseline SHA
- installed garminconnect version/path
- discovered detailed Garmin methods
- static source contract
- connector endpoints added
- DB migrations
- normalized entities
- raw types
- lap support
- zone support
- sample support
- FIT/download capability result
- descriptor mapping strategy
- partial collection strategy
- idempotency strategy
- Spring test count
- Python test count
- PostgreSQL validation
- actual Garmin API calls = 0
- actual historical backfill = NOT_RUN
- external workout writes = 0
- commit SHA
- push result
- working tree state
- exact Main-PC 6H-1B checklist

작업 완료 후 Main PC live verification 전에는 Detailed Activity contract를 `LIVE_VERIFIED`라고 표시하지 않는다.
