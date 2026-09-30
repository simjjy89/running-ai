Phase 5B-2를 진행해줘.

CLAUDE.md와 running-ai-dev / running-ai-database skill을 따라.

작업지시서:
docs/work-orders/2026-09-30-workout-intensity-target-model-instruction.md

AthleteIntensityProfile을 정식 domain으로 추가하고
LTHR / threshold pace를 persistent하게 관리해.

5B-1 WorkoutPrescription은 변경하지 말고,
그 위에 pace / %LTHR HR / treadmill speed / incline target을 얹는
TargetedWorkoutPrescription을 만들어.

실제 사용자 LTHR/pace 값을 코드, 테스트, 문서에 하드코딩하지 마.
synthetic fixture만 사용해.

running profile을 CROSS_TRAINING에 적용하지 말고,
profile이 없으면 QUALITATIVE fallback으로 정상 동작하게 해.

QUALITY prescription은 계속 unsupported로 유지하고
interval/repeat 및 Garmin/Intervals rendering은 이번 Phase에서 구현하지 마.

V5 migration → 전체 Java regression → 가능하면 PostgreSQL migration 검증 →
README/결과 문서 → secrets 검사 → diff review → commit → push까지 진행하고,
Phase 5C는 시작하지 마.
# RunningAI Phase 5B-2
## Pace / Heart Rate / LTHR / Treadmill Speed / Incline Target Model

## 1. 현재 상태

완료:

```text
Phase 1      Spring Boot foundation
Phase 2      PostgreSQL / Flyway
Phase 3      Garmin ingestion / sync / runtime
Phase 4A     Training Load
Phase 4B     Training State
Phase 4C     Training Decision Context
Phase 5A     Workout Recommendation
Phase 5B-1   Workout Prescription Structure
```

latest known commit:

```text
830b2ba
```

현재 Java regression baseline:

```text
268 tests
268 passed
```

현재 흐름:

```text
WorkoutRecommendation
        ↓
WorkoutPrescriptionService
        ↓
WorkoutPrescription
        ├─ exact duration
        ├─ warm-up
        ├─ main
        └─ cool-down
```

이번 Phase에서는:

```text
WorkoutPrescription
        +
AthleteIntensityProfile
        ↓
WorkoutIntensityTargetService
        ↓
TargetedWorkoutPrescription
```

을 구현한다.

---

# 2. 이번 Phase 목표

5B-1에서 만들어진 각 segment에 가능한 범위에서 숫자 강도 목표를 부여한다.

지원 목표:

```text
running pace
heart rate based on %LTHR
treadmill speed
treadmill incline
qualitative fallback
```

아직 다음은 하지 않는다.

```text
interval repetition
distance repetition
QUALITY workout generation
Garmin FIT/workout rendering
Intervals.icu rendering
Garmin auto LTHR fetch
race pace
VO2max model
RPE-based training model
```

---

# 3. 핵심 원칙

실제 사용자 LTHR/threshold pace 값을 코드에 하드코딩하지 않는다.

대화에 존재하는 값도 사용하지 않는다.

RunningAI domain에 저장된:

```text
AthleteIntensityProfile
```

만 사용한다.

---

# 4. AthleteIntensityProfile

새 persistent model을 만든다.

권장:

```text
AthleteIntensityProfile
```

athlete당 최대 한 행.

필드:

```text
id
athleteId

lactateThresholdHeartRateBpm nullable
lactateThresholdPaceSecondsPerKm nullable

createdAt
updatedAt
```

---

# 5. DB migration

이번 Phase는 schema 변경이 필요하다.

기존:

```text
V1
V2
V3
V4
```

는 절대 수정하지 않는다.

새 migration:

```text
V5__create_athlete_intensity_profile.sql
```

실제 naming convention에 맞춘다.

---

# 6. DB constraints

최소:

```text
FK athlete
UNIQUE athlete_id
```

를 둔다.

LTHR/pace 값은 양수여야 한다.

DB CHECK를 넣을지는 기존 migration style을 보고 결정한다.

과도한 physiological range constraint는 넣지 않는다.

---

# 7. 왜 persistent profile인가

LTHR와 threshold pace는 workout마다 달라지는 값이 아니라 athlete state다.

향후:

```text
Garmin LTHR detection
manual update
fitness test
race result
```

등에서 업데이트될 수 있다.

따라서 env property에 넣지 않는다.

---

# 8. Profile API

추가 권장:

```http
GET /api/v1/athlete/intensity-profile
PUT /api/v1/athlete/intensity-profile
```

현재 single-athlete architecture를 따른다.

athleteId를 외부 API에 노출하지 않는다.

---

# 9. GET profile — profile 없음

아직 profile이 없다면 404 대신:

```json
{
  "initialized": false,
  "lactateThresholdHeartRateBpm": null,
  "lactateThresholdPaceSecondsPerKm": null
}
```

형태를 권장한다.

---

# 10. PUT profile

예:

```json
{
  "lactateThresholdHeartRateBpm": 170,
  "lactateThresholdPaceSecondsPerKm": 300
}
```

수치는 예시일 뿐 실제 사용자 값이 아니다.

partial update를 허용할지 전체 replacement로 할지 명확히 정한다.

권장:

```text
PUT = complete profile replacement
```

null은 해당 metric 미설정을 의미한다.

---

# 11. Validation

기술적으로 의미 없는 값만 차단한다.

예:

```text
LTHR <= 0             → 400
threshold pace <= 0  → 400
```

임의의 athlete performance upper/lower range로 제한하지 않는다.

---

# 12. Garmin dependency 금지

이번 Phase에서:

```text
Garmin Connect
Garmin connector
activity_raw
```

에서 LTHR를 자동으로 가져오지 않는다.

manual/domain profile만 사용한다.

Garmin LTHR 자동 ingestion은 별도 Phase로 둔다.

---

# 13. TargetedWorkoutPrescription

5B-1의 DTO를 깨뜨리지 않는다.

새 DTO 권장:

```text
TargetedWorkoutPrescription
```

필드:

```text
asOfDate
intent
totalDurationMinutes
segments
targetAvailability
profile
recommendation
```

---

# 14. TargetedWorkoutSegment

권장:

```text
TargetedWorkoutSegment
```

필드:

```text
type
durationMinutes
intensityClass
description

primaryTargetType

paceTarget
heartRateTarget
treadmillTarget
```

---

# 15. PrimaryTargetType

enum:

```text
NONE
PACE
HEART_RATE
QUALITATIVE
```

REST:

```text
NONE
```

---

# 16. Target Selection Priority

running segment에서는:

```text
threshold pace available
→ primary = PACE

else LTHR available
→ primary = HEART_RATE

else
→ primary = QUALITATIVE
```

둘 다 있어도 HR target 정보를 secondary로 함께 제공할 수 있다.

---

# 17. 왜 Pace 우선인가

현재 RunningAI가 향후:

```text
outdoor pace
treadmill km/h
Garmin pace target
```

으로 직접 변환하기 가장 쉽다.

이것은 physiological superiority가 아니라 product/runtime 선택이다.

README에 명시한다.

---

# 18. REST target

REST segment:

```text
primaryTargetType = NONE
paceTarget = null
heartRateTarget = null
treadmillTarget = null
```

---

# 19. CROSS_TRAINING target

현재 athlete profile은 **running threshold profile**이다.

따라서 CROSS_TRAINING에 running LTHR/pace를 적용하지 않는다.

```text
primaryTargetType = QUALITATIVE
```

만 사용한다.

cycling LTHR domain은 향후 별도 모델이다.

---

# 20. Supported Running Intensities

5B-1에서 실제 running segment가 사용하는:

```text
VERY_EASY
EASY
```

만 숫자 target을 지원한다.

현재 `HARD` target은 정의하지 않는다.

QUALITY prescription이 없기 때문이다.

---

# 21. Pace Target Model

threshold pace:

```text
T = lactateThresholdPaceSecondsPerKm
```

를 기준으로 상대 범위를 계산한다.

초기 deterministic scheduling heuristic:

```text
VERY_EASY
fast pace = T × 1.25
slow pace = T × 1.45

EASY
fast pace = T × 1.15
slow pace = T × 1.30
```

pace는 seconds/km다.

---

# 22. Pace multiplier 의미

위 multiplier는 RunningAI 초기 intensity scheduling heuristic이다.

다음으로 설명하지 않는다.

```text
scientifically optimal
injury-safe
universally correct
```

README에 configurable/future-tunable heuristic임을 명시한다.

---

# 23. Pace Range Naming

pace는 숫자가 작을수록 빠르므로 `min/max`라는 이름은 혼동을 만든다.

권장:

```text
fastSecondsPerKm
slowSecondsPerKm
```

를 사용한다.

---

# 24. Pace Rounding

계산 후:

```text
nearest whole second
```

로 반올림한다.

동일 input → 동일 output.

---

# 25. Heart Rate Target Model

LTHR:

```text
L = lactateThresholdHeartRateBpm
```

초기 deterministic heuristic:

```text
VERY_EASY
65% ~ 78% LTHR

EASY
75% ~ 85% LTHR
```

---

# 26. HR target fields

권장:

```text
minBpm
maxBpm

minPercentLthr
maxPercentLthr
```

를 같이 제공한다.

예:

```json
{
  "minPercentLthr": 75,
  "maxPercentLthr": 85,
  "minBpm": 128,
  "maxBpm": 145
}
```

값은 예시다.

---

# 27. HR rounding

BPM 변환은 deterministic rounding을 사용한다.

권장:

```text
Math.round
```

또는 기존 numeric convention.

min <= max invariant를 보장한다.

---

# 28. Pace와 HR 모두 있을 때

둘 다 반환한다.

예:

```text
primary target = PACE

paceTarget       populated
heartRateTarget  populated
```

5C renderer가 플랫폼에 맞게 선택할 수 있게 한다.

---

# 29. Profile에 LTHR만 있는 경우

```text
paceTarget = null
heartRateTarget = populated
primary = HEART_RATE
```

---

# 30. Profile에 threshold pace만 있는 경우

```text
paceTarget = populated
heartRateTarget = null
primary = PACE
```

---

# 31. Profile이 없는 경우

```text
paceTarget = null
heartRateTarget = null
primary = QUALITATIVE
```

prescription 생성 자체는 실패하지 않는다.

이것이 중요한 fallback이다.

---

# 32. Treadmill Speed

pace target이 있으면 동일 target을 km/h로 변환한다.

공식:

```text
speedKph = 3600 / paceSecondsPerKm
```

---

# 33. Treadmill speed range

pace slow/fast와 speed min/max 방향은 반대다.

```text
minSpeedKph =
3600 / slowSecondsPerKm

maxSpeedKph =
3600 / fastSecondsPerKm
```

---

# 34. Speed rounding

Garmin/트레드밀 UI를 고려해:

```text
0.1 km/h
```

단위로 HALF_UP 또는 deterministic rounding.

예:

```text
10.74 → 10.7
10.75 → 10.8
```

정확한 정책을 테스트로 고정한다.

---

# 35. Treadmill Incline

running segment에는 optional treadmill incline range를 제공한다.

초기 기본 heuristic:

```text
WARM_UP
0.0% ~ 0.5%

MAIN
0.5% ~ 1.0%

COOL_DOWN
0.0% ~ 0.5%
```

---

# 36. Incline 의미

이 값은 야외 달리기와 동일한 생리학적 부하를 보장한다는 의미가 아니다.

단순 RunningAI treadmill operational default다.

README에 명시한다.

---

# 37. Incline Target DTO

예:

```text
minPercent
maxPercent
```

decimal 허용.

예:

```text
0.5
1.0
```

---

# 38. TreadmillTarget

권장:

```text
minSpeedKph
maxSpeedKph
minInclinePercent
maxInclinePercent
```

pace profile이 없으면:

```text
speed = null
incline = still available
```

로 둘지 결정해야 한다.

권장:

```text
incline은 running segment이면 pace profile과 무관하게 제공
```

한다.

---

# 39. Qualitative fallback

profile이 없어도 segment에는 기존:

```text
intensityClass
```

가 있다.

따라서:

```text
QUALITATIVE
```

target으로 정상 반환한다.

HTTP 오류로 만들지 않는다.

---

# 40. Target Availability

response에 간단한 availability를 제공한다.

예:

```text
FULL
PACE_ONLY
HEART_RATE_ONLY
QUALITATIVE_ONLY
```

또는 boolean 형태:

```text
paceAvailable
heartRateAvailable
```

과도한 enum은 만들지 않는다.

---

# 41. IntensityTargetService

구현:

```text
WorkoutIntensityTargetService
```

의존:

```text
WorkoutPrescriptionService
AthleteIntensityProfileService
```

---

# 42. Layering

```text
WorkoutIntensityTargetController
        ↓
WorkoutIntensityTargetService
        ├─ WorkoutPrescriptionService
        └─ AthleteIntensityProfileService
```

ActivityRepository 직접 접근 금지.

TrainingLoad/TrainingState 직접 접근 금지.

---

# 43. 5B-1 Prescription 변경 금지

기존:

```http
GET /api/v1/workout-prescription
```

response contract를 깨뜨리지 않는다.

새 targeted endpoint를 추가한다.

---

# 44. API

권장:

```http
GET /api/v1/workout-intensity-targets
```

optional:

```text
date=YYYY-MM-DD
```

없으면 기존 5B-1/5A path를 따라 athlete local today.

---

# 45. API Response Example

```json
{
  "asOfDate": "2026-09-30",
  "intent": "EASY",
  "totalDurationMinutes": 45,
  "targetAvailability": "FULL",
  "segments": [
    {
      "type": "WARM_UP",
      "durationMinutes": 10,
      "intensityClass": "VERY_EASY",
      "primaryTargetType": "PACE",
      "paceTarget": {
        "fastSecondsPerKm": 375,
        "slowSecondsPerKm": 435
      },
      "heartRateTarget": {
        "minPercentLthr": 65,
        "maxPercentLthr": 78,
        "minBpm": 111,
        "maxBpm": 133
      },
      "treadmillTarget": {
        "minSpeedKph": 8.3,
        "maxSpeedKph": 9.6,
        "minInclinePercent": 0.0,
        "maxInclinePercent": 0.5
      }
    }
  ],
  "prescription": {
    "..."
  }
}
```

수치는 예시일 뿐 실제 athlete 값이 아니다.

---

# 46. No exact user values in fixtures

실제 Garmin/LTHR 값을 fixture나 README에 복사하지 않는다.

테스트는 synthetic values 사용.

---

# 47. REST API

REST prescription에서도 endpoint는 정상 200.

target:

```text
NONE
```

---

# 48. QUALITY

5B-1에서 이미:

```text
QUALITY_PRESCRIPTION_NOT_SUPPORTED
```

이므로 5B-2도 해당 exception을 그대로 통과시킨다.

새로운 quality target을 만들지 않는다.

---

# 49. Profile update 후 즉시 반영

aggregate/cache가 없으므로 profile PUT 이후 다음 intensity target GET에서 새 값이 즉시 반영되어야 한다.

---

# 50. No Target Persistence

계산된 pace/HR target 자체는 DB에 저장하지 않는다.

DB에 저장하는 것은 athlete intensity profile뿐이다.

---

# 51. Why target is derived

threshold profile이 업데이트되면 과거/미래 target을 새 기준으로 재계산할 수 있게 한다.

derived target table을 만들지 않는다.

---

# 52. Historical Request Semantics

과거 날짜로:

```http
GET /api/v1/workout-intensity-targets?date=...
```

를 호출해도 **현재 intensity profile**을 사용한다.

현재 profile history를 저장하지 않기 때문이다.

README limitation에 명시한다.

---

# 53. No profile history

이번 Phase에서는:

```text
effectiveFrom
profileVersion
historical LTHR
```

를 구현하지 않는다.

---

# 54. Test — Profile absent

EASY prescription:

```text
primary = QUALITATIVE
pace = null
HR = null
treadmill incline populated
```

---

# 55. Test — LTHR only

```text
primary = HEART_RATE
pace = null
HR populated
```

VERY_EASY/EASY percentage 계산 정확성 검증.

---

# 56. Test — Pace only

```text
primary = PACE
pace populated
HR = null
speed populated
```

---

# 57. Test — Full profile

둘 다 존재:

```text
primary = PACE
pace populated
HR populated
speed populated
```

---

# 58. Test — Pace Conversion

synthetic threshold pace를 사용해:

```text
VERY_EASY
EASY
```

fast/slow seconds/km 계산을 정확히 검증.

---

# 59. Test — Speed Conversion

```text
3600 / pace
```

와 0.1 km/h rounding을 테스트한다.

범위 방향:

```text
minSpeed <= maxSpeed
```

보장.

---

# 60. Test — HR Conversion

synthetic LTHR 기준:

```text
65%
78%
75%
85%
```

각 BPM 변환 검증.

---

# 61. Test — Segment intensity

EASY prescription의:

```text
warm-up VERY_EASY
main EASY
cool-down VERY_EASY
```

가 서로 다른 target range를 받는지 검증.

---

# 62. Test — LONG

LONG도:

```text
warm-up VERY_EASY
main EASY
cool-down VERY_EASY
```

target 적용.

---

# 63. Test — RECOVERY

모든 segment:

```text
VERY_EASY
```

이므로 동일 target band.

---

# 64. Test — CROSS_TRAINING

running threshold가 있어도:

```text
primary = QUALITATIVE
pace = null
HR = null
treadmill = null
```

을 검증.

---

# 65. Test — REST

모든 numeric target null.

---

# 66. Test — Profile PUT

create:

```text
initialized false → PUT → true
```

검증.

---

# 67. Test — Profile update

같은 athlete:

```text
row count 증가 없음
same identity semantics
updatedAt 변경
```

등 현재 service convention에 맞게 검증한다.

---

# 68. Test — Profile Validation

```text
LTHR <= 0 → 400
threshold pace <= 0 → 400
```

---

# 69. Test — Profile partial availability

둘 중 한 값만 null이어도 정상 저장/계산.

---

# 70. Test — Historical date

5A/5B-1의 no-look-ahead semantics 유지.

단 intensity profile은 현재 profile을 사용함을 명시적으로 테스트하거나 문서화한다.

---

# 71. Test — Determinism

같은:

```text
prescription
profile
```

이면 target 결과 동일.

---

# 72. Test — No forbidden fields

아직 다음은 없어야 한다.

```text
repeatCount
intervalDistance
Garmin target type
Intervals.icu syntax
FIT fields
```

---

# 73. Migration Test

Flyway:

```text
V1
V2
V3
V4
V5
```

H2와 PostgreSQL-compatible migration path에서 정상 적용.

기존 V1~V4 수정 금지.

---

# 74. Existing API Regression

기존 모든 API 유지:

```text
/api/v1/health
/api/v1/activities
/api/v1/garmin/sync
/api/v1/garmin/sync/status
/api/v1/training-load
/api/v1/training-load/weekly
/api/v1/training-state
/api/v1/training-decision-context
/api/v1/workout-recommendation
/api/v1/workout-prescription
/actuator/health
```

---

# 75. No Garmin Network

이번 Phase에서 Garmin connector/network 호출은 없다.

---

# 76. No external model

LLM/AI 호출 없음.

---

# 77. Configuration

pace/HR percentage heuristic을 property로 전부 빼서 복잡하게 만들 필요는 없다.

초기에는 명확한 policy constants/class로 관리 가능하다.

하지만 magic number가 service 곳곳에 흩어지면 안 된다.

권장:

```text
RunningIntensityTargetPolicy
```

같은 단순 policy class.

---

# 78. Target Policy Responsibility

이 class는:

```text
IntensityClass
+
AthleteIntensityProfile
→ numerical targets
```

만 담당한다.

DB/API logic을 넣지 않는다.

---

# 79. README

추가:

```text
Athlete Intensity Profile
Workout Intensity Targets
```

API:

```text
GET /api/v1/athlete/intensity-profile
PUT /api/v1/athlete/intensity-profile
GET /api/v1/workout-intensity-targets
```

---

# 80. README Metric Definitions

명확히 기록:

```text
Threshold pace stored as seconds/km.
LTHR stored as bpm.

VERY_EASY pace:
125–145% of threshold pace

EASY pace:
115–130% of threshold pace

VERY_EASY HR:
65–78% LTHR

EASY HR:
75–85% LTHR
```

그리고:

```text
These are initial RunningAI scheduling heuristics,
not universal physiological thresholds.
```

명시.

---

# 81. README Treadmill

기록:

```text
speed = 3600 / paceSecondsPerKm

warm-up/cool-down incline:
0.0–0.5%

main running incline:
0.5–1.0%
```

역시 operational defaults라고 명시.

---

# 82. Work Order

저장:

```text
docs/work-orders/
2026-09-30-workout-intensity-target-model-instruction.md

docs/work-orders/
2026-09-30-workout-intensity-target-model.md
```

---

# 83. Java Regression

실행:

```powershell
cd server
.\gradlew.bat clean test
```

baseline:

```text
268 tests
```

모두 PASS.

---

# 84. Python

connector 변경 없음.

재실행 불필요.

---

# 85. PostgreSQL Validation

가능하면 migration V5를 PostgreSQL에서도 검증한다.

외부 PC에 PostgreSQL/Docker가 없다면 H2 regression만 수행하고:

```text
POSTGRESQL_LIVE_VALIDATION_NOT_RUN
```

으로 기록.

---

# 86. Secrets

Intensity profile은 credential은 아니지만 실제 athlete 값도 문서/fixture에 복사하지 않는다.

Secrets scan + diff review 수행.

---

# 87. Definition of Done

```text
[ ] AthleteIntensityProfile entity
[ ] repository
[ ] service
[ ] athlete one-to-one unique
[ ] V5 migration
[ ] LTHR nullable
[ ] threshold pace nullable
[ ] GET profile
[ ] PUT profile
[ ] profile validation
[ ] TargetedWorkoutPrescription
[ ] TargetedWorkoutSegment
[ ] primary target type
[ ] pace target
[ ] heart rate target
[ ] treadmill target
[ ] qualitative fallback
[ ] REST none target
[ ] CROSS_TRAINING does not use running thresholds
[ ] VERY_EASY pace policy
[ ] EASY pace policy
[ ] VERY_EASY LTHR policy
[ ] EASY LTHR policy
[ ] pace → speed conversion
[ ] 0.1 km/h rounding
[ ] incline defaults
[ ] profile absent fallback
[ ] pace-only fallback
[ ] HR-only fallback
[ ] full-profile behavior
[ ] QUALITY remains unsupported
[ ] existing 5B-1 contract unchanged
[ ] GET workout intensity targets
[ ] no Garmin network
[ ] no target persistence
[ ] no cache
[ ] no interval/repeat
[ ] H2/Flyway regression
[ ] full Java regression
[ ] README
[ ] result document
[ ] secrets scan
[ ] diff review
[ ] commit
[ ] push
```

---

# 88. 권장 Commit

```text
feat: add workout intensity target model
```

---

# 89. 완료 보고 형식

## Athlete Intensity Profile

```text
LTHR:
threshold pace:
persistence:
API:
```

## Target Selection

```text
PACE priority:
HR fallback:
qualitative fallback:
REST:
CROSS_TRAINING:
```

## Pace Model

```text
VERY_EASY:
EASY:
rounding:
```

## Heart Rate Model

```text
VERY_EASY:
EASY:
rounding:
```

## Treadmill

```text
speed formula:
speed rounding:
warm-up incline:
main incline:
cool-down incline:
```

## API

```text
GET profile:
PUT profile:
GET workout targets:
```

## Tests

```text
Java total:
passed:
failed:

profile:
pace:
HR:
speed:
incline:
fallback:
cross-training:
REST:
historical:
```

## Database

```text
migration:
schema:
PostgreSQL validation:
```

## Garmin

```text
network dependency:
auto LTHR ingestion:
```

## Git

```text
branch:
commit:
push:
```

## Known Limitations

```text
QUALITY target 없음
running threshold only
cycling threshold 없음
profile history 없음
race pace 없음
RPE model 없음
pace/HR heuristics not tuned from real data
treadmill incline is operational default
```

## Next Phase

자동 시작하지 않는다.

```text
Phase 5C
Structured Workout Rendering

TargetedWorkoutPrescription
        ↓
Intervals.icu workout
        ↓
Garmin compatible structured steps
```

단 실제 renderer migration 전에 기존 legacy RunningAI의
Intervals.icu/Garmin renderer를 먼저 조사한다.

---

# 90. 핵심 invariant

완료 후:

```text
WorkoutPrescription
        +
AthleteIntensityProfile
        ↓
WorkoutIntensityTargetService
        ↓
TargetedWorkoutPrescription
```

이어야 한다.

숫자 target을 만들 수 없더라도:

```text
QUALITATIVE fallback
```

으로 prescription 자체는 살아 있어야 한다.

**LTHR와 threshold pace는 athlete data이며 코드 상수가 아니다.**

**Running threshold를 cycling에 적용하지 않는다.**

**5B-1의 workout 구조를 바꾸지 않고 target만 추가한다.**
