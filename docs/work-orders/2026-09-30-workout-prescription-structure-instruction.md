> 원본 작업지시서 (2026-09-30, Phase 5B-1). 구현 기록은 `2026-09-30-workout-prescription-structure.md` 참고.
> 추가 사용자 지시: CLAUDE.md, running-ai-dev 준수 / Phase 5A WorkoutRecommendation만 source로 exact duration과 warm-up/main/cool-down 구조 생성 / REST·RECOVERY·EASY·LONG·CROSS_TRAINING만 실제 prescription, QUALITY는 구조를 만들지 않고 명시적 unsupported 처리 / pace, HR, LTHR, incline, interval repeat, Garmin/Intervals rendering 금지 / Phase 5A의 QUALITY-only candidate invariant를 확인하고 candidate 밖 fallback이 생기지 않도록 테스트로 고정 / 구현 → 전체 Java regression → README/결과 문서 → secrets 검사 → diff review → commit → push / Phase 5B-2 시작 금지 / 결과 보고는 한글.

# RunningAI Phase 5B-1
## Exact Duration / Warm-up / Main / Cool-down / Qualitative Intensity

## 1. 현재 상태

완료:

```text
Phase 1     Spring Boot foundation
Phase 2     PostgreSQL / Flyway
Phase 3     Garmin ingestion / sync / runtime
Phase 4A    Training Load
Phase 4B    Training State
Phase 4C    Training Decision Context
Phase 5A    Workout Recommendation Model
```

latest known commit:

```text
af6bc26
```

현재 Java regression baseline:

```text
240 tests
240 passed
```

현재 흐름:

```text
TrainingDecisionContext
        ↓
WorkoutRecommendationService
        ↓
WorkoutRecommendation
        ├─ recommendedIntent
        ├─ duration range
        ├─ intensity class
        ├─ reasons
        └─ confidence
```

이번 Phase에서는 이를:

```text
WorkoutRecommendation
        ↓
WorkoutPrescriptionService
        ↓
WorkoutPrescription
```

으로 확장한다.

---

# 2. 이번 Phase 목표

오늘 추천된 intent를 실제 실행 가능한 구조로 변환한다.

최소 결과:

```text
intent
totalDurationMinutes
segments
summary
recommendation
```

각 segment는:

```text
WARM_UP
MAIN
COOL_DOWN
```

또는 필요한 경우:

```text
REST
```

를 표현한다.

---

# 3. 이번 Phase에서 하지 않는 것

이번 단계에서는 다음을 구현하지 않는다.

```text
pace target
speed target
HR target
HR zone
LTHR
RPE 숫자 target
incline
interval repetition
distance repetition
Garmin workout rendering
Intervals.icu rendering
QUALITY workout structure
race-goal specific workout
```

---

# 4. Phase 분리

이번 Phase:

```text
Phase 5B-1
exact duration + structure
```

다음:

```text
Phase 5B-2
pace / HR / LTHR / treadmill target
```

그 이후:

```text
Phase 5C
Garmin / Intervals.icu structured rendering
```

---

# 5. QUALITY-only invariant 확인

Phase 5B-1 시작 전에 Phase 5A invariant를 확인한다.

현재 원칙:

```text
recommendedIntent ∈ candidateTrainingTypes
```

그리고:

```text
QUALITY는 자동 선택하지 않음
```

따라서 candidate가 QUALITY 하나뿐인 context는 정상 production context가 아니다.

권장:

```text
candidateTrainingTypes = {QUALITY only}
→ explicit invariant violation
```

silent EASY fallback 금지.

candidate 밖의 intent 반환 금지.

가능하면 테스트로 고정한다.

이 보정이 필요하면 Phase 5A의 최소 수정으로 처리하고 regression을 유지한다.

---

# 6. Prescription 대상 Intent

이번 Phase에서 실제 prescription 지원:

```text
REST
RECOVERY
EASY
LONG
CROSS_TRAINING
```

QUALITY는 enum에는 남기되:

```text
QUALITY prescription unavailable
```

로 처리한다.

---

# 7. QUALITY 처리

만약 어떤 이유로:

```text
recommendedIntent = QUALITY
```

가 들어오면 억지 prescription을 만들지 않는다.

명확한 domain/application exception을 반환한다.

예:

```text
QUALITY_PRESCRIPTION_NOT_SUPPORTED
```

또는 기존 exception convention에 맞춘 코드.

---

# 8. WorkoutPrescription DTO

권장:

```text
WorkoutPrescription
```

필드:

```text
asOfDate
intent
totalDurationMinutes
segments
summary
recommendation
```

필요하다면:

```text
confidence
dataSufficiency
```

를 5A recommendation에서 재노출할 수 있다.

---

# 9. WorkoutSegment DTO

권장:

```text
WorkoutSegment
```

필드:

```text
type
durationMinutes
intensityClass
description
```

---

# 10. SegmentType

권장 enum:

```text
WARM_UP
MAIN
COOL_DOWN
REST
```

이번 Phase에는:

```text
INTERVAL
RECOVERY_REPEAT
```

등을 넣지 않는다.

---

# 11. IntensityClass 재사용

Phase 5A의:

```text
NONE
VERY_EASY
EASY
MODERATE
HARD
```

를 그대로 재사용한다.

중복 enum 금지.

---

# 12. Exact Duration Selection

5A는 범위를 제공한다.

예:

```text
EASY = 30~60
```

5B-1은 정확한 한 값을 선택한다.

선택은 deterministic해야 한다.

---

# 13. Duration Selection Principle

초기 버전은 복잡한 scoring 없이 범위 내 고정 policy를 사용한다.

권장:

```text
REST            0
RECOVERY       30
EASY           45
LONG           90
CROSS_TRAINING 45
```

단 5A의 min/max 범위를 벗어나면 안 된다.

---

# 14. Recommendation Range가 Source of Truth

정확한 duration은:

```text
WorkoutRecommendation.durationMinMinutes
WorkoutRecommendation.durationMaxMinutes
```

범위 내에서 선택한다.

즉 hard-coded 값이 range와 충돌하면 range를 우선한다.

---

# 15. Preferred Duration Policy

권장 helper:

```text
preferred duration
```

예:

```text
REST            0
RECOVERY       30
EASY           45
LONG           90
CROSS_TRAINING 45
```

그리고:

```text
selected =
clamp(preferred, min, max)
```

형태로 선택한다.

---

# 16. 왜 clamp인가

향후 recommendation range가 config나 context에 따라 바뀌어도 prescription이 invalid해지지 않는다.

---

# 17. Exact Duration 설명

README에:

```text
Exact duration is currently selected by a deterministic scheduling policy within the recommendation range.
```

라고 명시한다.

생리학적 최적값으로 설명하지 않는다.

---

# 18. REST Prescription

REST:

```text
totalDurationMinutes = 0
segments = [
  REST 0 NONE
]
```

또는 segments empty를 선택할 수 있다.

권장:

```text
REST segment 하나
```

를 두어 API 구조를 일정하게 유지한다.

---

# 19. RECOVERY 구조

권장 30분:

```text
WARM_UP   5 min  VERY_EASY
MAIN     20 min  VERY_EASY
COOL_DOWN 5 min  VERY_EASY
```

total:

```text
30
```

---

# 20. EASY 구조

권장 45분:

```text
WARM_UP   10 min VERY_EASY
MAIN      30 min EASY
COOL_DOWN  5 min VERY_EASY
```

total:

```text
45
```

---

# 21. LONG 구조

권장 90분:

```text
WARM_UP   10 min VERY_EASY
MAIN      70 min EASY
COOL_DOWN 10 min VERY_EASY
```

total:

```text
90
```

---

# 22. CROSS_TRAINING 구조

초기:

```text
WARM_UP    5 min VERY_EASY
MAIN      35 min EASY
COOL_DOWN  5 min VERY_EASY
```

total:

```text
45
```

구체 종목은 아직 결정하지 않는다.

예:

```text
bike
elliptical
stairs
```

중 무엇인지 Phase 5B-1에서 선택하지 않는다.

---

# 23. Duration이 기본값과 다를 때

range/clamp 결과가 예:

```text
EASY total = 35 min
```

이라면 segment 시간도 자동으로 맞춰야 한다.

고정:

```text
10 + 30 + 5
```

를 그대로 쓰면 안 된다.

---

# 24. Segment Allocation Policy

총 시간에 따라 warm-up/main/cool-down을 deterministic하게 분배한다.

권장 기본:

RECOVERY:

```text
warm-up  = min(5, suitable)
cool-down = min(5, suitable)
main = remainder
```

EASY:

```text
warm-up  ≈ 20%
cool-down ≈ 10%
main = remainder
```

LONG:

```text
warm-up max ~10
cool-down max ~10
main = remainder
```

과도한 수학 framework는 만들지 않는다.

---

# 25. 최소 Main Segment

non-REST prescription은:

```text
MAIN duration > 0
```

이어야 한다.

---

# 26. Segment Sum Invariant

반드시:

```text
sum(segment.durationMinutes)
==
totalDurationMinutes
```

이어야 한다.

테스트로 고정한다.

---

# 27. Non-negative Invariant

모든 segment:

```text
durationMinutes >= 0
```

---

# 28. REST Invariant

REST:

```text
total = 0
all segment duration = 0
intensity = NONE
```

---

# 29. Warm-up / Cool-down Intensity

현재:

```text
VERY_EASY
```

를 사용한다.

pace/HR target은 아직 없다.

---

# 30. MAIN Intensity

mapping:

```text
RECOVERY        VERY_EASY
EASY            EASY
LONG            EASY
CROSS_TRAINING  EASY
```

QUALITY는 미지원.

---

# 31. Description

각 segment에 짧은 deterministic description을 둘 수 있다.

예:

```text
"Easy warm-up"
"Steady easy running"
"Easy cool-down"
```

언어 국제화 framework는 이번 Phase에서 만들지 않는다.

---

# 32. Prescription Summary

WorkoutPrescription summary 예:

```text
"45-minute easy session with warm-up, steady main work, and cool-down."
```

deterministic template 사용.

LLM 호출 금지.

---

# 33. Recommendation 포함

WorkoutPrescription은 source recommendation을 nested로 포함하는 것을 권장한다.

예:

```text
recommendation
```

이유:

```text
왜 이 prescription이 만들어졌는지 추적 가능
```

---

# 34. Layering

권장:

```text
WorkoutPrescriptionController
        ↓
WorkoutPrescriptionService
        ↓
WorkoutRecommendationService
```

Repository 직접 접근 금지.

TrainingDecisionContextService 직접 접근도 하지 않는다.

---

# 35. Source of Truth

Prescription은 반드시:

```text
WorkoutRecommendation
```

만 사용한다.

다시 TrainingState나 Activity를 계산하지 않는다.

---

# 36. No independent decision

WorkoutPrescriptionService가:

```text
EASY 대신 LONG을 선택
```

하면 안 된다.

intent는 5A 결과를 그대로 따른다.

---

# 37. No recommendation override

예:

```text
recommendedIntent = RECOVERY
```

이면 prescription도 RECOVERY다.

---

# 38. Duration range invariant

선택된 total duration은:

```text
min <= total <= max
```

반드시 만족.

REST:

```text
0 <= 0 <= 0
```

---

# 39. API

추가:

```http
GET /api/v1/workout-prescription
```

optional:

```text
date=YYYY-MM-DD
```

없으면 athlete local today.

---

# 40. Response 예

```json
{
  "asOfDate": "2026-09-30",
  "intent": "EASY",
  "totalDurationMinutes": 45,
  "segments": [
    {
      "type": "WARM_UP",
      "durationMinutes": 10,
      "intensityClass": "VERY_EASY",
      "description": "Easy warm-up"
    },
    {
      "type": "MAIN",
      "durationMinutes": 30,
      "intensityClass": "EASY",
      "description": "Steady easy running"
    },
    {
      "type": "COOL_DOWN",
      "durationMinutes": 5,
      "intensityClass": "VERY_EASY",
      "description": "Easy cool-down"
    }
  ],
  "summary": "45-minute easy session with warm-up, steady main work, and cool-down.",
  "recommendation": {
    "..."
  }
}
```

---

# 41. Exact duration type

minutes는 integer 사용을 권장한다.

이번 Phase에서는:

```text
37.5 minutes
```

같은 fractional prescription을 만들지 않는다.

---

# 42. Seconds conversion 금지

Garmin rendering 전 단계이므로 내부 prescription에서 minutes로 유지해도 된다.

Phase 5C에서 seconds로 변환 가능하다.

---

# 43. Historical Date

과거 `date` 조회 시 5A recommendation이 이미 no-look-ahead를 보장한다.

5B-1에서도 이를 깨뜨리지 않는다.

---

# 44. Clock / timezone

PrescriptionService는 별도 Clock/Timezone 계산을 하지 않는다.

WorkoutRecommendation의 `asOfDate`를 그대로 사용한다.

---

# 45. QUALITY Unsupported Exception

API로 QUALITY recommendation이 들어오는 artificial/test 상황을 처리한다.

권장 HTTP mapping:

```text
422 Unprocessable Entity
```

또는 기존 application convention에 맞는 명확한 4xx.

code 예:

```text
QUALITY_PRESCRIPTION_NOT_SUPPORTED
```

500으로 흘리지 않는다.

---

# 46. Invalid Recommendation Range

예:

```text
min > max
```

같은 내부 invariant violation이 발생하면 silent correction하지 않는다.

명확한 internal/domain error로 처리한다.

다만 production 5A에서는 발생하지 않아야 한다.

---

# 47. Candidate invariant test

Phase 5A의 defensive invariant도 함께 확인한다.

특히:

```text
candidate = QUALITY only
```

상황에서 candidate 밖 fallback이 발생하지 않는지 확인한다.

필요하면 Phase 5A에 최소 수정.

---

# 48. Test — REST

기대:

```text
intent REST
total 0
REST segment
NONE
```

---

# 49. Test — RECOVERY

기본 recommendation range 20~40:

```text
total 30
segments sum 30
main VERY_EASY
```

---

# 50. Test — EASY

기본:

```text
total 45
10 / 30 / 5
```

---

# 51. Test — LONG

기본:

```text
total 90
10 / 70 / 10
```

---

# 52. Test — CROSS_TRAINING

기본:

```text
total 45
5 / 35 / 5
```

---

# 53. Test — QUALITY

명확한 unsupported error.

세부 interval을 임의 생성하지 않음.

---

# 54. Test — Custom Narrow Range

예:

```text
EASY range = 30~35
```

preferred 45가 clamp되어:

```text
35
```

가 되고 segment 합도 정확히 35인지 검증.

---

# 55. Test — Custom Wide Range

예:

```text
EASY = 30~90
```

preferred:

```text
45
```

유지.

---

# 56. Test — Segment Sum

모든 supported intent에서:

```text
sum = total
```

property-style 반복 테스트 가능.

---

# 57. Test — No Negative Segment

모든 supported intent.

---

# 58. Test — Main Positive

모든 non-REST supported intent.

---

# 59. Test — Recommendation Intent Preserved

source recommendation intent와 prescription intent가 동일.

---

# 60. Test — Recommendation Range Preserved

total이 source min/max 범위 내.

---

# 61. Test — Determinism

같은 Recommendation 두 번:

```text
same prescription
```

---

# 62. Test — API default date

5A behavior를 통해 athlete local date 사용.

---

# 63. Test — Explicit date

과거 날짜 prescription.

---

# 64. Test — Invalid date

기존:

```text
400 INVALID_REQUEST
```

유지.

---

# 65. Test — Response Structure

다음이 없는지 검증:

```text
pace
heartRate
incline
repeat
distanceTarget
Garmin fields
```

---

# 66. Existing API Regression

모두 유지:

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
/actuator/health
```

---

# 67. No Garmin Dependency

Prescription은 Garmin connector/network를 호출하지 않는다.

---

# 68. No Activity Repository

WorkoutPrescriptionService는 ActivityRepository를 알 필요가 없다.

---

# 69. No persistence

이번 Phase:

```text
migration = NO
schema change = NO
persistence = NO
```

---

# 70. No cache

추가하지 않는다.

---

# 71. README

추가:

```text
GET /api/v1/workout-prescription
```

그리고:

```text
Phase 5B-1 converts an intent recommendation into an exact-duration qualitative workout structure.
It does not yet contain pace, HR, LTHR, incline, intervals, or Garmin steps.
```

---

# 72. README Intent examples

간단히:

```text
RECOVERY  → ~30 min
EASY      → ~45 min
LONG      → ~90 min
```

현재 값은 deterministic scheduling defaults임을 명시한다.

---

# 73. Known limitation

반드시 기록:

```text
exact durations are heuristic defaults
no athlete-specific duration adaptation yet
no pace model
no HR model
no LTHR
QUALITY unsupported
cross-training modality unspecified
```

---

# 74. Work Order

저장:

```text
docs/work-orders/
2026-09-30-workout-prescription-structure-instruction.md

docs/work-orders/
2026-09-30-workout-prescription-structure.md
```

---

# 75. Java Regression

실행:

```powershell
cd server
.\gradlew.bat clean test
```

baseline:

```text
240 tests
```

신규 테스트 증가.

모두 PASS.

---

# 76. Python

변경 없음.

---

# 77. Database

기대:

```text
migration: NO
schema change: NO
```

---

# 78. Secrets

기존 workflow대로:

```text
diff review
secrets scan
```

수행.

---

# 79. Definition of Done

```text
[ ] WorkoutPrescriptionService
[ ] WorkoutPrescription DTO
[ ] WorkoutSegment DTO
[ ] SegmentType
[ ] REST prescription
[ ] RECOVERY prescription
[ ] EASY prescription
[ ] LONG prescription
[ ] CROSS_TRAINING prescription
[ ] QUALITY unsupported explicitly
[ ] exact deterministic duration
[ ] duration clamp to recommendation range
[ ] warm-up/main/cool-down allocation
[ ] segment sum invariant
[ ] no negative duration
[ ] non-rest main > 0
[ ] intent preserved from recommendation
[ ] intensity qualitative only
[ ] deterministic summary
[ ] recommendation nested
[ ] GET /api/v1/workout-prescription
[ ] historical semantics preserved
[ ] no pace
[ ] no HR target
[ ] no LTHR
[ ] no incline
[ ] no interval/repeat
[ ] no Garmin rendering
[ ] no repository dependency
[ ] no persistence
[ ] no cache
[ ] no migration
[ ] QUALITY-only candidate invariant checked
[ ] Java full regression
[ ] README
[ ] result document
[ ] secrets scan
[ ] diff review
[ ] commit
[ ] push
```

---

# 80. 권장 commit

```text
feat: add workout prescription structure
```

---

# 81. 완료 보고 형식

## Prescription Model

```text
source:
supported intents:
unsupported intents:
duration policy:
```

## Structures

```text
REST:
RECOVERY:
EASY:
LONG:
CROSS_TRAINING:
QUALITY:
```

## Duration Selection

```text
preferred:
range clamp:
segment allocation:
sum invariant:
```

## API

```text
GET /api/v1/workout-prescription:
```

## Tests

```text
Java total:
passed:
failed:

REST:
RECOVERY:
EASY:
LONG:
CROSS_TRAINING:
QUALITY:
range clamp:
segment invariant:
historical:
```

## Database

```text
migration:
schema change:
persistence:
```

## Garmin

```text
network dependency:
rendering:
```

## Git

```text
branch:
commit:
push:
```

## Known Limitations

```text
no pace
no HR/LTHR
no incline
no QUALITY structure
no interval structure
no athlete-specific exact duration model
cross-training modality unspecified
```

## Next Phase

자동 시작하지 않는다.

```text
Phase 5B-2
Intensity Target Model

- pace model
- HR model
- LTHR integration
- treadmill speed
- treadmill incline
- target selection / fallback
```

그 이후:

```text
Phase 5C
Structured Workout Rendering
→ Intervals.icu
→ Garmin
```

---

# 82. 핵심 invariant

완료 후:

```text
WorkoutRecommendation
        ↓
WorkoutPrescription
        ├─ exact duration
        ├─ warm-up
        ├─ main
        └─ cool-down
```

이어야 한다.

하지만 아직:

```text
몇 분/km
몇 km/h
몇 bpm
몇 % LTHR
경사 몇 %
인터벌 몇 회
```

는 결정하지 않는다.

**Recommendation은 intent를 결정하고, Prescription은 구조를 결정한다.**

**Intensity target은 다음 Phase로 분리한다.**