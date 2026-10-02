# RunningAI Phase 6H-1B — Main PC Garmin Detailed Activity Live Contract Verification

이번 작업은 Main PC에서 수행한다.

신규 RunningAI repository:

`C:\running-ai-github`

기준 remote main latest known:

`b38604d`

Phase 6H-1A 상태:

- Detailed Activity foundation 구현 완료
- Spring tests: 918 passed
- Python connector tests: 134 passed
- PostgreSQL subset: 394 passed
- Garmin live API calls: 0
- Historical backfill: NOT_RUN
- External workout writes: 0
- detailed contract 상태:
  - `STATIC_SOURCE_CONFIRMED`
  - 아직 `LIVE_VERIFIED` 아님

이번 Phase의 목표는:

실제 Garmin 계정의 대표 activity 몇 개를 이용해

- detail
- splits
- HR zones
- power zones
- samples
- optional FIT/download

응답 구조를 확인하고,

6H-1A에서 만든 provisional mapper/fixture/schema가 실제 Garmin 응답과 맞는지 검증 및 최소 보정하는 것이다.

이번 Phase에서는 아직:

- 90일 historical backfill 금지
- TrainingContext V2 구현 금지
- Running Analysis Engine 구현 금지
- Intervals enrichment 구현 금지
- 실제 workout publish 금지

한다.

---

# 0. 작업 기록

작업 시작 전에 이 지시서를 원문 그대로:

`docs/work-orders/2026-10-02-phase-6h-1b-garmin-detailed-live-contract-instruction.md`

에 저장한다.

완료 후:

`docs/work-orders/2026-10-02-phase-6h-1b-garmin-detailed-live-contract-result.md`

를 작성한다.

실제 activity id, 개인 운동 세부 값, Garmin token/credential은 repository 문서에 commit하지 않는다.

실제 activity id는 local uncommitted note에서만 관리한다.

---

# 1. Repository preflight

작업 repository:

`C:\running-ai-github`

확인:

- branch = main
- working tree clean
- origin/main fetch
- `b38604d`가 HEAD ancestor
- safe fast-forward 가능 여부

가능하면:

`git pull --ff-only origin main`

만 사용한다.

금지:

- hard reset
- clean -fd
- force checkout
- destructive rebase

Main PC의 legacy repository:

`C:\running-ai`

는 수정하지 않는다.

---

# 2. Publishing safety

이번 Phase는 Garmin read-only 검증이다.

작업 시작 시 다음을 모두 확인한다.

- `WORKOUT_PUBLISHING_ENABLED=false`
- `WORKOUT_PUBLISHING_SCHEDULER_ENABLED=false`
- `RUNNING_AI_DRAFT_PUBLISHING_ENABLED=false`
- `RUNNINGAI_MCP_ENABLED=false`

실제 Intervals/Garmin workout write는 절대 수행하지 않는다.

Phase 종료 시에도 모두 OFF여야 한다.

---

# 3. Runtime start

Main PC 신규 repository에서 canonical scripts 사용:

`scripts/windows/stop-running-ai.ps1`

필요 시:

`scripts/windows/start-running-ai.ps1 -Build`

그리고:

`scripts/windows/status-running-ai.ps1`

확인:

- Docker RUNNING
- PostgreSQL HEALTHY
- Garmin connector UP
- Spring UP
- actuator health UP

---

# 4. Flyway validation

Main PC DB에서 최소 V1~V13이 정상 적용됐는지 확인한다.

특히:

- V11 activity_raw_payload
- V12 activity_detail / activity_lap / activity_zone / activity_sample
- V13 activity_detail_collection

확인:

- failed migration 없음
- 기존 activity row 유지
- 기존 activity_raw 유지
- JSON columns PostgreSQL jsonb

migration 실패 시:

- repair 금지
- migration 수정 금지
- 데이터 삭제 금지

STOP 후 보고한다.

---

# 5. Garmin authentication

기존 Main PC Garmin token store 사용.

먼저 connector CLI status 확인.

기대:

`VALID`

INVALID/MISSING이면 자동 login을 반복하지 않는다.

필요하면 사용자가 직접 interactive login을 수행한다.

절대:

- password log
- token output
- SSL verification disable

하지 않는다.

401 / 403 / 429 발생 시 즉시 중단한다.

---

# 6. 대표 activity 선정

실제 Garmin 계정에서 다음 activity를 가능한 범위에서 선정한다.

필수:

1. outdoor run
2. treadmill run
3. interval run
4. long run

선택:

5. indoor cycling

가능하면 최근 기록 중 데이터가 풍부한 activity를 선택한다.

각 activity id는 local uncommitted note에만 저장한다.

repository 문서에는:

`<OUTDOOR_RUN_ID>`

형태 placeholder만 기록한다.

---

# 7. Connector live contract probe

각 activity에 대해 아래 localhost connector endpoint를 호출한다.

## Detail

`GET /activities/{id}/detail`

확인:

- top-level shape
- wrapper 존재 여부
- summaryDTO 또는 동등 필드
- duration fields
- distance
- speed / pace
- HR
- cadence
- elevation
- calories
- power
- Training Effect
- Training Load
- device metadata
- temperature
- 기타 Garmin-specific field

실제 존재하는 field만 기록한다.

없는 field를 예상으로 추가하지 않는다.

---

# 8. Splits live probe

호출:

`GET /activities/{id}/splits`

확인:

- top-level list/object
- wrapper key
- lap/split numbering
- 0-based / 1-based 여부
- start time
- duration
- distance
- pace/speed
- avg HR
- max HR
- cadence
- power
- ascent/descent
- interval/recovery representation

특히 interval run에서는:

- work interval
- recovery interval
- warmup
- cooldown

이 각각 어떻게 표현되는지 확인한다.

Garmin lap과 structured workout step이 동일하다고 가정하지 않는다.

---

# 9. Typed splits / split summaries

6H-1A static source에서 발견된:

- `get_activity_typed_splits`
- `get_activity_split_summaries`

가 현재 connector endpoint로 아직 노출되지 않았다면 먼저 실제 필요성을 평가한다.

기존 `/splits`만으로:

- interval/recovery 구분
- lap grouping
- running/cycling split metadata

가 충분하지 않다면 최소 read-only connector endpoint를 추가한다.

불필요하다면 구현하지 않고 이유를 문서화한다.

scope creep을 피한다.

---

# 10. HR zones probe

호출:

`GET /activities/{id}/hr-zones`

확인:

- response list/object shape
- zone 번호
- lower bound
- upper bound
- duration/time
- percentage field
- total zone time vs activity duration
- missing zone 표현
- zero-duration zone 표현

중요:

upper bound가 response에 없으면 RunningAI가 임의 계산하지 않는다.

6H-1A의 기존 원칙을 유지한다.

---

# 11. Power zones probe

호출:

`GET /activities/{id}/power-zones`

power가 존재하는 activity에 대해 확인한다.

특히:

- running power 존재 여부
- cycling power 존재 여부

power가 없는 activity에서는 Garmin response가:

- empty list
- null
- 404
- other shape

중 무엇인지 기록한다.

`NOT_AVAILABLE`은 정상 contract일 수 있다.

---

# 12. Samples / Activity Details Stream probe

호출:

`GET /activities/{id}/samples`

확인:

- top-level structure
- `metricDescriptors`
- `activityDetailMetrics`
- descriptor key
- descriptor unit
- descriptor index/position
- sample timestamp
- timestamp unit
- elapsed time
- distance
- heart rate
- speed
- cadence
- elevation
- GPS latitude/longitude
- temperature
- power
- running dynamics

중요:

metric index를 절대 하드코딩하지 않는다.

실제 response의 descriptor:

`key/name → metric index`

mapping을 기반으로 읽는다.

unknown metric은 `extra_metrics`에 보존할 수 있어야 한다.

---

# 13. Outdoor vs treadmill comparison

Outdoor run과 treadmill run sample descriptor를 비교한다.

확인:

Outdoor:
- GPS 존재?
- elevation 존재?
- speed/pace?
- HR?
- cadence?
- running dynamics?

Treadmill:
- GPS 없음?
- elevation 없음/고정?
- treadmill speed?
- cadence?
- HR?
- stride/running dynamics?

두 activity에서 metricDescriptors 순서가 같은지 비교한다.

순서가 다르면 현재 descriptor-based parsing 설계가 실제로 필요한 근거로 문서화한다.

---

# 14. maxChart comparison

Long run activity에 대해:

default:

`/samples`

그리고:

`/samples?maxChart=20000`

을 비교한다.

확인:

- sample count
- timestamp distribution
- 동일 시작/종료
- downsampling 여부
- HR/speed/cadence 변화
- endpoint latency
- response size

특히:

default 2000이 실제 full sample인지 downsample인지 판단한다.

결과 분류:

- FULL_SAMPLE
- DOWNSAMPLED
- UNKNOWN

가능하면 실제 기록 duration과 sample count를 비교하되 Garmin native sampling cadence를 임의 추정하지 않는다.

---

# 15. Optional FIT probe

installed garminconnect 0.3.16의:

`download_activity(... ORIGINAL ...)`

capability를 Main PC에서 activity 한 건에 한해 read-only probe해도 된다.

단 조건:

- 로그인/token 정상
- rate limit 문제 없음
- 실제 workout write와 무관
- local temp directory에만 저장
- repository에 commit 금지

확인:

- 반환 파일 type
- ZIP 여부
- 내부 FIT 존재 여부
- 파일 크기

FIT 내용을 분석하는 기능 구현은 이번 Phase 범위 밖이다.

probe가 위험하거나 필요 없으면:

`NOT_RUN`

으로 남겨도 된다.

---

# 16. Raw response storage policy

Live probe response는 필요한 경우 local temp file로 보관한다.

예:

`.runtime/live-contract/`

단 이 디렉터리가 gitignore 대상인지 확인한다.

개인 데이터/raw payload를 repository에 commit하지 않는다.

결과 문서에는 structure/schema만 sanitized하게 기록한다.

---

# 17. Spring Detailed Ingestion Live Validation

각 대표 activity에 대해 Spring endpoint:

`POST /api/v1/garmin/activities/{id}/details`

를 실행한다.

첫 호출 후 확인:

- base activity link
- activity_raw_payload
- activity_detail
- activity_lap
- activity_zone
- activity_sample
- activity_detail_collection

각 row count 확인.

그리고 동일 activity에 대해 같은 endpoint를 한 번 더 호출한다.

기대:

- duplicate raw row 없음
- duplicate detail 없음
- duplicate lap 없음
- duplicate zone 없음
- duplicate sample 없음
- normalized counts 동일
- idempotent refresh

---

# 18. Spot-check normalization

각 activity에서 최소 몇 개 값을 raw와 normalized DB에서 비교한다.

예:

- activity duration
- distance
- avg HR
- max HR
- first lap duration
- first lap distance
- first lap avg HR
- sample HR
- sample speed
- sample cadence

exact 또는 정의된 rounding/unit conversion이 일치해야 한다.

잘못된 field mapping이 있으면 fixture와 mapper를 수정한다.

---

# 19. Fixture correction

6H-1A에서 synthetic fixture로 만든 부분을 실제 live contract에 맞춰 보정한다.

단 실제 개인 값은 anonymize한다.

예:

실제:

`activityId = 123456789`

이면 fixture에서는:

`activityId = 999000001`

처럼 대체한다.

개인 날짜, 위치, GPS 좌표도 anonymize 또는 제거한다.

fixture 목적은 shape/field mapping 검증이다.

개인 기록 보존이 아니다.

---

# 20. Contract status

각 capability를 아래 중 하나로 분류한다.

- `CONFIRMED_LIVE`
- `CORRECTED`
- `NOT_AVAILABLE`
- `BLOCKED`
- `NOT_RUN`

다음 조건이 만족된 경우에만:

`LIVE_VERIFIED`

표시 가능:

- 실제 Garmin response 확인
- mapper fixture 보정
- Spring normalize 성공
- reprocess 성공
- automated test PASS

static source만 본 것은 LIVE_VERIFIED가 아니다.

---

# 21. Reprocess validation

live contract에 맞게 mapper를 수정했다면:

기존 stored raw payload를 이용해:

`/details/reprocess`

또는 현재 구현된 equivalent reprocess path를 실행한다.

중요:

reprocess는 Garmin API call 없이 수행되어야 한다.

검증:

- normalized detail rebuild
- lap rebuild
- zone rebuild
- sample rebuild
- raw payload unchanged
- duplicate 없음

---

# 22. Partial collection behaviour

일부 activity에서 특정 endpoint가 empty/not available인 경우 확인한다.

예:

Treadmill:
- power zones 없음

Indoor cycling:
- running dynamics 없음

그 경우 collection status가 적절히:

- NORMALIZED
- EMPTY
- RAW_STORED
- FETCH_FAILED
- MAPPING_FAILED

중 하나인지 확인한다.

정상적인 데이터 부재를 FAILED로 잘못 처리하지 않는다.

---

# 23. Error / rate-limit policy

실제 live validation 중:

401
403
429

가 발생하면 그 즉시 Garmin live probe 전체를 중단한다.

`retry_attempts=0`이 유지되어야 한다.

별도 retry loop를 만들지 않는다.

429 발생 후 다른 activity를 이어서 호출하지 않는다.

결과:

`BLOCKED_BY_GARMIN_RATE_LIMIT`

로 기록한다.

---

# 24. FIT decision

Main PC live response를 본 뒤 architecture 문서에 다음 중 하나를 결정한다.

### A. API detail sufficient

Garmin detail/splits/samples가 분석에 충분하여 FIT는 archival/optional.

또는

### B. FIT recommended

API stream이 downsample되거나 running dynamics/event 정보가 부족하여 FIT raw preservation이 필요.

단 이번 Phase에서 FIT ingestion implementation까지 확대하지 않는다.

다음 Phase 후보로 기록한다.

---

# 25. No historical backfill yet

아직 90일 수집하지 않는다.

이번 Phase에서 허용되는 Garmin activity는 선정한 대표 activity 3~5개뿐이다.

다음 금지:

- bulk pagination
- 90-day backfill
- recovery 28-day backfill
- Intervals enrichment
- TrainingContext V2
- AI Coach draft generation
- workout publish

---

# 26. Tests

mapper/fixture/code 수정이 생겼다면 전체 regression.

Spring:

baseline:

918 passed

실행:

`gradlew clean test`

기대:

0 failed
0 skipped unexpected

Python connector 변경이 생겼다면:

baseline:

134 passed

full pytest 실행.

PostgreSQL 관련 schema/mapper를 변경했다면 가능하면 PostgreSQL subset도 재실행한다.

---

# 27. Architecture documents update

다음 문서를 실제 결과에 맞게 갱신한다.

`docs/architecture/garmin-detailed-activity-contract-static.md`

static facts와 live facts를 구분한다.

그리고:

`docs/architecture/detailed-activity-v2.md`

에 실제 live findings를 반영한다.

특히:

- actual detail fields
- lap shape
- zone shape
- sample descriptor
- downsampling
- FIT decision

을 기록한다.

---

# 28. Completion criteria

Phase 6H-1B 완료 조건:

- Main PC Garmin token valid
- representative activities selected
- detail live contract 확인
- splits live contract 확인
- HR zones live contract 확인
- power zones behaviour 확인
- samples live contract 확인
- descriptor mapping 확인
- outdoor/treadmill differences 확인
- maxChart comparison 확인
- Spring detailed ingestion live success
- second ingestion idempotent
- mapper corrected if necessary
- anonymized fixtures updated
- reprocess success if changed
- tests green
- no bulk backfill
- no external workout writes

---

# 29. STOP point

Phase 6H-1B가 완료되면 다음을 보고하고 멈춘다.

`GARMIN_DETAILED_ACTIVITY_CONTRACT_LIVE_VERIFIED`

또는 실패 시 정확한 BLOCKED reason.

이후 자동으로:

- 90일 historical backfill
- Analysis Engine
- Intervals enrichment
- TrainingContext V2

로 넘어가지 않는다.

다음 Phase 지시를 기다린다.

---

# 30. Final report

최종 보고 순서:

1. repo/head SHA
2. Garmin token status
3. Flyway V1~V13 status
4. selected activity categories
5. detail contract result
6. splits contract result
7. HR zone contract result
8. power zone result
9. sample descriptor result
10. outdoor/treadmill differences
11. interval/recovery representation
12. maxChart default vs 20000
13. FIT probe result
14. mapper corrections
15. fixture corrections
16. Spring ingestion row counts
17. idempotent second fetch result
18. reprocess result
19. collection status behaviour
20. Spring test count
21. Python test count
22. PostgreSQL test result
23. Garmin live API call count
24. 401/403/429 occurrence
25. historical backfill = NOT_RUN
26. Intervals enrichment = NOT_RUN
27. external workout writes = 0
28. contract status
29. commits
30. push result
31. working tree clean 여부

개인 activity id, GPS 위치, Garmin token, credential은 보고서와 commit에 남기지 않는다.
