# Phase 6D instruction (verbatim)

RunningAI Phase 6D를 구현해줘.

작업 repo:
C:\running-ai-github

중요:
- C:\running-ai 는 legacy 보존 영역이다. 수정하지 않는다.
- 작업 시작 전에 현재 branch/status/head를 확인한다.
- 기존 Phase 5C/6A/6B/6C 동작을 깨지 않는다.
- 구현 전 전체 작업지시서를 아래 경로에 먼저 저장한다.

C:\running-ai-github\docs\work-orders\2026-10-02-phase-6d-garmin-athlete-profile-auto-sync.md

## 배경

현재 Spring publisher 운영 cutover는 완료됐다.

검증 완료:
- Spring Boot 3.5.16 / Java 21
- PostgreSQL 17
- POST /api/v1/workout-publish 실제 Intervals publish 성공
- CREATED → readback verified=true
- 동일 날짜 재호출 NO_CHANGE
- Garmin Forerunner 265까지 workout 전달 확인
- Garmin에서 Pace target + treadmill speed/incline cue 표시 확인

하지만 신규 PostgreSQL의 athlete_intensity_profile이 비어 있었기 때문에 최초 EASY workout은 Garmin에서 "목표 없음"으로 생성됐다.

현재는 아래 값을 수동 입력했다.

- LTHR: 180 bpm
- LT pace: 4:50/km = 290 sec/km

수동 입력 후:
- intensity profile initialized=true
- targetAvailability=FULL
- EASY primaryTargetType=PACE
- 기존 workout UPDATED
- Garmin에서 Pace target 정상 표시 확인

목표는 앞으로 Garmin에서 갱신된 젖산역치를 자동 수집하여 athlete_intensity_profile에 반영하는 것이다.

현재 dependency:
python-garminconnect==0.3.16

이 버전에는 Garmin.get_lactate_threshold()가 존재한다.

---

# Phase 6D 목표

Garmin Connect의 최신 running lactate threshold를 안전하게 읽어서:

Garmin Connect
→ garmin-connector
→ Spring Garmin profile sync
→ athlete_intensity_profile
→ WorkoutIntensityTargetService

경로로 자동 반영한다.

사용자가 LTHR / LT pace를 다시 수동 입력할 필요가 없어야 한다.

단, 기존 수동 입력 API는 fallback 용도로 유지한다.

---

# 6D-0 — Live contract probe

가장 먼저 실제 사용자 Garmin 계정의

Garmin.get_lactate_threshold()

응답 계약을 확인한다.

중요:
- 응답 field명을 추측해서 구현하지 않는다.
- 현재 설치된 garminconnect 0.3.16을 사용한다.
- 기존 token store 방식만 사용한다.
- 이메일/패스워드를 코드에 추가하지 않는다.
- token 값을 출력하지 않는다.
- 개인 식별자는 fixture에 남기지 않는다.

실제 live response에서 다음을 확인한다.

1. LTHR bpm field
2. LT speed 또는 LT pace field
3. 단위
4. timestamp/date field 존재 여부
5. null/empty 상태
6. nested structure 여부

특히 speed 값의 단위를 반드시 실제 응답과 upstream 구현을 기준으로 검증한다.

speed를 pace로 변환해야 한다면:
- conversion을 별도 pure function으로 구현
- 단위를 명확히 문서화
- divide-by-zero / non-positive / NaN 방어
- 결과는 secondsPerKm 정수

현재 실제 Garmin 값인
180 bpm / 약 4:50/km
와 합리적으로 일치하는지도 live probe에서 확인한다.

live payload를 test fixture로 저장할 경우 개인정보/식별자는 제거한 sanitized fixture만 저장한다.

---

# 6D-1 — Python garmin-connector

현재 connector 원칙을 유지한다.

기존:
/health
/activities

추가:
GET /lactate-threshold

GarminGateway에 최소 wrapper를 추가한다.

예시 방향:

GarminGateway.lactate_threshold()
→ self._garmin.get_lactate_threshold()

CachedGatewayProvider에도 동일한 forwarding method를 추가한다.

FastAPI endpoint는 connector의 기존 error translation 정책을 그대로 사용한다.

가능하면 Garmin 원본 응답을 과도하게 가공하지 않는다.
정규화 책임은 Spring에 둔다.

단, JSON serialization 불가능한 타입이 실제 응답에 있을 경우 필요한 최소 변환만 수행한다.

테스트:
- successful lactate threshold response
- null/empty response
- auth failure
- upstream failure
- endpoint validation/regression
- 기존 /activities 동작 불변

---

# 6D-2 — Spring Garmin profile sync

integration.garmin 영역에 profile sync 경계를 추가한다.

권장 구조:

GarminLactateThresholdSource
HttpGarminLactateThresholdSource
GarminLactateThresholdMapper
GarminLactateThresholdSnapshot
GarminAthleteProfileSyncService

이름은 기존 프로젝트 naming convention을 우선한다.

Spring은 connector의 raw JSON을 받아 domain snapshot으로 정규화한다.

최소 domain 값:

- lactateThresholdHeartRateBpm
- lactateThresholdPaceSecondsPerKm

필요하면:
- measuredAt / observedAt
- raw provenance

를 추가할 수 있지만 이번 Phase에서 불필요한 schema 확장은 피한다.

## 저장 정책

기존 AthleteIntensityProfileService의 PUT semantics는 변경하지 않는다.

현재 PUT:
null = metric clear

이 의미를 깨지 말 것.

Garmin sync용 별도 merge/upsert operation을 추가한다.

Garmin sync 규칙:

1. Garmin에서 유효한 LTHR가 오면 해당 metric 갱신
2. Garmin에서 유효한 LT pace가 오면 해당 metric 갱신
3. Garmin 응답에서 한 metric이 누락되었다고 기존 값을 null로 지우지 않는다
4. 둘 다 누락되면 DB 변경 없음
5. Garmin 호출 실패 시 DB 변경 없음
6. malformed 값이면 DB 변경 없음 또는 해당 field만 무시
7. 기존 값과 동일하면 unnecessary UPDATE 하지 않음
8. 변경된 값이 있을 때만 저장
9. manual profile은 fallback으로 유지
10. Garmin valid value가 들어오면 해당 metric에 대해서는 Garmin 최신값을 반영

기존 WorkoutIntensityTargetService는 수정하지 않거나 최소 수정만 한다.

이미 이 서비스는 매 호출마다 current intensity profile을 읽으므로 profile sync 후 다음 workout 생성부터 자동 반영되어야 한다.

---

# 값 검증

비현실적 데이터를 DB에 저장하지 않도록 최소 validation을 둔다.

단, 임의의 스포츠 과학 기준을 과도하게 hard-code하지 않는다.

필수:
- bpm > 0
- pace seconds/km > 0
- finite numeric input
- conversion overflow 방지

보다 좁은 physiological bounds를 넣고 싶다면 근거와 이유를 work-order result에 기록한다.

---

# 자동 실행

자동 sync는 기존 Garmin sync scheduling 구조를 먼저 분석한 후 최소 변경으로 통합한다.

우선순위:

1. 기존 Garmin sync scheduler 흐름에 자연스럽게 profile sync를 결합할 수 있으면 재사용
2. 결합이 부적절하면 독립 Garmin profile scheduler

새 scheduler가 필요할 경우:

GARMIN_PROFILE_SYNC_ENABLED=false

를 default로 한다.

운영 activation 전까지 자동으로 Garmin API를 호출해서는 안 된다.

timezone은 Asia/Seoul 또는 기존 Garmin scheduling timezone 설정을 재사용한다.

중요:
- workout publishing scheduler와 결합하지 않는다.
- workout publish 자체가 Garmin profile API availability에 의존하게 만들지 않는다.
- Garmin profile fetch 실패 때문에 workout publish가 실패하면 안 된다.
- 마지막으로 저장된 profile을 stale fallback으로 사용할 수 있어야 한다.

권장 운영 순서는:

Garmin profile sync
→ DB에 latest threshold 저장
→ 이후 workout publishing

이다.

---

# API / manual trigger

운영 검증을 위한 수동 profile sync trigger를 제공한다.

기존 API convention을 확인해서 결정한다.

예:
POST /api/v1/garmin/profile-sync

응답 최소 예:

{
  "updated": true,
  "lactateThresholdHeartRateBpm": 180,
  "lactateThresholdPaceSecondsPerKm": 290
}

하지만 실제 response contract는 기존 Garmin sync API style에 맞춰라.

민감한 Garmin raw payload는 API 응답에 노출하지 않는다.

---

# observability

INFO:
- profile sync started/completed
- updated / unchanged
- 어떤 metric이 변경되었는지

WARN:
- Garmin data missing
- malformed field
- connector failure

절대 로그 금지:
- Garmin token
- Authorization header
- credential
- 전체 민감 raw profile payload

---

# 테스트

최소 다음 테스트를 추가한다.

Python:
1. lactate threshold success
2. empty result
3. Garmin authentication error
4. Garmin upstream error

Spring:
1. full threshold first insert
2. both metrics updated
3. unchanged → no update
4. HR only → pace preserved
5. pace only → HR preserved
6. both missing → no DB change
7. malformed HR ignored
8. malformed pace/speed ignored
9. connector failure → existing profile preserved
10. speed → seconds/km conversion
11. scheduler default disabled
12. timezone behavior if scheduler added
13. existing manual PUT semantics unchanged
14. WorkoutIntensityTargetService uses updated profile immediately

Regression:
- 기존 전체 server test
- Python connector tests
- Phase 5C publisher tests
- Phase 6A REST publish
- Phase 6B scheduler
- Phase 6C MCP tests

외부 Garmin/Intervals API는 automated test에서 호출하지 않는다.

---

# Live validation

자동화 테스트 완료 후 실제 계정으로 수동 검증한다.

순서:

1. garmin-connector 실행
2. GET /lactate-threshold 확인
3. Spring profile sync trigger
4. GET /api/v1/athlete/intensity-profile
5. 현재 Garmin 값과 비교
6. workout-intensity-targets preview
7. 기존 profile과 동일하면 unchanged 확인

현재 baseline:

LTHR = 180
LT pace = 290 sec/km (4:50/km)

실제 Garmin 값이 변경돼 있다면 Garmin 최신값을 정상 반영하는 것이 기대 동작이다.

이 Phase에서는 테스트 목적으로 새로운 Intervals workout을 publish하지 않는다.

---

# Non-goals

이번 Phase에서는 하지 않는다.

- HRV 자동 저장
- Body Battery
- Training Readiness
- Training Status
- VO2Max
- Race prediction
- Garmin workout 직접 upload
- MCP 변경
- ChatGPT MCP 연결
- workout publishing scheduler activation
- 기존 Garmin activity ingestion 대규모 refactor
- Spring Boot 4 migration

이 항목들은 후속 Phase로 남긴다.

---

# 완료 조건

다음이 모두 만족돼야 Phase 6D 완료다.

- garminconnect.get_lactate_threshold() live contract 확인
- connector /lactate-threshold 구현
- Spring profile sync 구현
- partial/missing/failure에서 기존 profile 보존
- 동일 데이터 idempotent
- manual intensity profile PUT semantics 유지
- 전체 regression GREEN
- 실제 Garmin 값으로 live sync 성공
- athlete_intensity_profile 자동 반영 확인
- workout target preview가 최신 threshold를 즉시 사용
- scheduler가 추가됐다면 default OFF
- MCP는 여전히 OFF

작업 완료 후 결과 문서를:

C:\running-ai-github\docs\work-orders\2026-10-02-phase-6d-garmin-athlete-profile-auto-sync-result.md

에 저장한다.

마지막에 다음을 보고해줘.

1. 변경 파일 목록
2. 실제 Garmin contract
3. LT speed → pace 변환 규칙
4. 저장/merge 정책
5. 테스트 개수 및 결과
6. live validation 결과
7. 설정 환경변수
8. commit SHA
9. 다음 Phase 추천

구현하고 테스트까지 완료한 뒤 commit해줘.
