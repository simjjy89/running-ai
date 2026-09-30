> 원본 작업지시서 (2026-09-30, Phase 5A). 구현 기록은 `2026-09-30-workout-recommendation-model.md` 참고.
> 추가 사용자 지시: CLAUDE.md, running-ai-dev 준수 / Phase 4C TrainingDecisionContext를 source로 오늘의 workout intent 하나 선택, duration range·intensity class·confidence·data sufficiency·reason·deterministic summary 반환 / quality-session model이 없으므로 QUALITY 자동 선택 금지, candidate list 첫 항목을 그대로 선택하는 구현 금지 / exact duration, workout steps, interval 구조, pace/HR target, Garmin/Intervals rendering 금지 / 구현 → 전체 Java regression → README/결과 문서 → secrets 검사 → diff review → commit → push / Phase 5B 시작 금지 / 결과 보고는 한글.

# RunningAI Phase 5A
## Workout Intent Selection / Duration Range / Intensity Class / Rationale

## 1. 현재 상태

완료:

```text
Phase 1     Spring Boot foundation
Phase 2     PostgreSQL / Flyway
Phase 3A    Garmin ingestion
Phase 3B    Real Garmin E2E
Phase 3C    Sync / Scheduler / Runtime
Phase 4A    Training Load
Phase 4B    Training State
Phase 4C    Training Decision Context
```

latest known commit:

```text
94d6f69
```

현재 Java regression baseline:

```text
207 tests
207 passed
```

현재 의사결정 흐름:

```text
Normalized Activity
        ↓
TrainingLoadService
        ↓
TrainingStateService
        ↓
TrainingDecisionContextService
        ↓
TrainingDecisionContext
```

이번 Phase에서는 처음으로:

```text
TrainingDecisionContext
        ↓
WorkoutRecommendationService
        ↓
WorkoutRecommendation
```

을 구현한다.

---

# 2. 이번 Phase 목표

오늘 실행할 훈련의 **intent 하나**를 결정한다.

결과에는 최소 다음이 포함되어야 한다.

```text
recommendedIntent
durationMinMinutes
durationMaxMinutes
intensityClass
confidence
reasons
summary
```

하지만 아직:

```text
interval repetition
pace target
HR target
incline
warm-up steps
Garmin structured workout
Intervals.icu payload
```

는 만들지 않는다.

---

# 3. 핵심 원칙

Phase 5A는:

```text
"What kind of workout should be considered today?"
```

를 결정한다.

Phase 5B는:

```text
"What exactly should that workout look like?"
```

를 결정한다.

둘을 섞지 않는다.

---

# 4. WorkoutIntent

기존 4C candidate type과 동일한 의미를 최대한 재사용한다.

권장:

```text
REST
RECOVERY
EASY
QUALITY
LONG
CROSS_TRAINING
```

가능하면 중복 enum을 새로 만들지 않는다.

---

# 5. Recommendation은 하나

Phase 4C:

```text
candidateTrainingTypes = 여러 개
```

Phase 5A:

```text
recommendedIntent = 정확히 하나
```

를 반환한다.

---

# 6. Candidate membership

원칙적으로 추천 intent는:

```text
TrainingDecisionContext.candidateTrainingTypes
```

안에서 선택한다.

후보에 없는 intent를 새로 만들어 추천하지 않는다.

예외가 정말 필요하면 이유를 문서화한다.

---

# 7. Candidate 순서 그대로 선택 금지

다음과 같이 구현하면 안 된다.

```java
return candidates.get(0);
```

4C candidate order는 deterministic output을 위한 순서이지 추천 우선순위가 아니다.

반드시 context를 보고 선택한다.

---

# 8. Recommendation Rule Philosophy

초기 모델은:

```text
단순
결정적
설명 가능
테스트 가능
```

해야 한다.

ML, scoring framework, external AI call은 사용하지 않는다.

---

# 9. Recommendation inputs

반드시 기존 context를 사용한다.

주요 입력:

```text
TrainingState
candidateTrainingTypes
decision reasons
daysSinceRunning
daysSinceLongRun
daysSinceQuality
consecutiveActiveDays
consecutiveRestDays
loadTrend
recentPattern
```

---

# 10. QUALITY 제한

현재 Phase 4C에서는:

```text
QUALITY_CANDIDATE
```

를 실제 분류하지 않는다.

따라서 quality session history가 충분하지 않다.

이번 Phase에서도 QUALITY를 적극 추천하면 안 된다.

---

# 11. QUALITY policy

권장:

```text
QUALITY candidate가 존재하더라도
현재 data sufficiency가 낮으면
QUALITY를 기본 선택하지 않는다.
```

즉:

```text
QUALITY
```

는 enum/API에는 유지하되 초기 recommendation engine에서 매우 제한적으로만 선택하거나 아예 선택하지 않아도 된다.

---

# 12. QUALITY unavailable reason

추천에서 QUALITY를 보수적으로 제외하는 경우 결과에:

```text
QUALITY_HISTORY_UNAVAILABLE
```

또는 유사한 reason을 제공할 수 있다.

과도하게 많은 reason enum은 만들지 않는다.

---

# 13. LONG recommendation

LONG은 명확한 recent long history가 있으므로 일부 추천 가능하다.

단:

```text
daysSinceLongRun <= 1
```

이면 LONG을 다시 선택하지 않는다.

---

# 14. Recovery Priority

다음 상황에서는:

```text
RECOVERY
```

를 우선 검토한다.

예:

```text
LONG_RUN_RECENT
MULTIPLE_ACTIVE_DAYS
```

가 존재하면서 activity를 완전히 쉬어야 할 명확한 근거는 없는 경우.

---

# 15. REST Priority

다음 상황에서는:

```text
REST
```

를 우선 검토할 수 있다.

초기 heuristic 예:

```text
LONG_RUN_RECENT
AND
consecutiveActiveDays >= 2
```

또는:

```text
consecutiveActiveDays >= 4
```

단 이 값은 medical/safety threshold가 아니라 단순 훈련 배치 heuristic이다.

---

# 16. REST threshold semantics

REST 선택 규칙을:

```text
injury prevention
overtraining diagnosis
unsafe to run
```

으로 설명하면 안 된다.

단지 현재 훈련 배치를 위한 보수적 스케줄링 rule이다.

---

# 17. EASY default

특별한 recovery/rest/long 조건이 없으면:

```text
EASY
```

를 기본 추천 intent로 사용하는 것이 적절하다.

초기 RunningAI 추천의 안정적인 fallback이다.

---

# 18. CROSS_TRAINING

다음 경우 후보:

```text
최근 러닝 없음
recent cycling 있음
running candidate context 제한적
```

등.

하지만 EASY가 충분히 가능한 상황에서 무조건 CROSS_TRAINING을 우선하지 않는다.

---

# 19. LONG eligibility

초기 LONG 선택 heuristic을 명확히 정의한다.

예:

```text
LONG is candidate
AND
daysSinceLongRun is null or >= 6
AND
consecutiveActiveDays <= 2
AND
loadTrend != strongly constrained case
```

숫자는 physiological truth가 아니라 scheduling heuristic이다.

---

# 20. Long frequency

초기 추천에서는 같은 7일 rolling period에 long run을 여러 번 넣지 않는 방향이 적절하다.

`daysSinceLongRun`을 재사용한다.

---

# 21. Load Trend 사용

`loadTrend`는 descriptive label이다.

다음처럼 활용 가능:

```text
INCREASING
→ LONG/QUALITY보다 EASY/RECOVERY 쪽에 가중

DECREASING
→ activity history가 충분하면 EASY/LONG 후보 유지

STABLE
→ normal recommendation

UNKNOWN
→ conservative recommendation
```

---

# 22. 숫자 기반 위험 판정 금지

다음 금지:

```java
if (acuteChronicRatio > 1.5) REST
```

Phase 4B metric에 arbitrary safety threshold를 붙이지 않는다.

---

# 23. Data Sufficiency

새 enum 권장:

```text
DataSufficiency
```

값:

```text
LOW
MEDIUM
HIGH
```

---

# 24. Data Sufficiency 목적

Recommendation이 얼마나 많은 history를 바탕으로 했는지 표현한다.

confidence와 의미를 섞지 않는다.

---

# 25. Data Sufficiency heuristic

초기 규칙 예:

```text
LOW
- LIMITED_HISTORY reason 존재
- recent 28d history가 매우 적음

MEDIUM
- 최근 7~28일 일부 data 존재

HIGH
- 충분한 28d history
- recentPattern이 안정적으로 구성됨
```

정확한 activity count 기준은 현재 데이터를 보고 단순하게 정한다.

---

# 26. Confidence

권장 enum:

```text
LOW
MEDIUM
HIGH
```

---

# 27. Confidence 의미

Confidence는:

```text
"이 추천이 얼마나 확실히 옳은가"
```

가 아니라:

```text
"현재 규칙과 데이터가 얼마나 명확한가"
```

를 의미한다.

---

# 28. Confidence 예

```text
LONG_RUN_RECENT + MULTIPLE_ACTIVE_DAYS
→ RECOVERY 추천
→ HIGH confidence
```

반면:

```text
LIMITED_HISTORY
→ EASY 추천
→ LOW confidence
```

처럼 사용할 수 있다.

---

# 29. Recommendation Reason

별도 enum 권장:

```text
WorkoutRecommendationReason
```

예:

```text
RECENT_LONG_RUN
MULTIPLE_ACTIVE_DAYS
RECENT_REST
LOAD_TREND_INCREASING
LOAD_TREND_STABLE
LOAD_TREND_DECREASING
LOW_RECENT_ACTIVITY
NO_RECENT_RUNNING
LONG_RUN_DUE
LIMITED_HISTORY
QUALITY_HISTORY_UNAVAILABLE
DEFAULT_EASY
```

기존 DecisionReason을 그대로 재사용할 수 있으면 재사용을 우선한다.

---

# 30. Summary

결과에 사람이 읽기 쉬운 짧은 설명을 넣는다.

예:

```text
"최근 장거리 세션 이후 회복일이 짧아 오늘은 회복 러닝을 우선 추천합니다."
```

다만 summary 생성은 deterministic template 기반이어야 한다.

LLM 호출 금지.

---

# 31. Summary language

현재 프로젝트/사용자 UI가 한국어 중심이라도 domain/service layer에서 언어별 문자열을 과도하게 박아 넣지 않는다.

권장:

```text
machine-readable reasons
+
optional simple summary
```

summary locale 전략이 아직 없다면 영어 deterministic summary도 허용한다.

---

# 32. Duration range

각 intent별로 **범위만** 제공한다.

예:

```text
REST           0 - 0
RECOVERY      20 - 40
EASY          30 - 60
QUALITY       30 - 70
LONG          75 - 120
CROSS_TRAINING 30 - 60
```

---

# 33. Duration은 exact prescription 아님

예:

```text
EASY = 30~60 min
```

은 범위다.

Phase 5B가 실제:

```text
40 min
```

을 선택한다.

---

# 34. Duration configuration

기본값을 configuration으로 빼도 된다.

하지만 Phase 5A에서 property가 지나치게 많아지면 안 된다.

권장:

```text
running-ai.training.recommendation.easy-min-duration
...
```

보다 우선 Java policy object/static defaults를 검토한다.

---

# 35. IntensityClass

권장 enum:

```text
NONE
VERY_EASY
EASY
MODERATE
HARD
```

---

# 36. Initial mapping

예:

```text
REST            → NONE
RECOVERY        → VERY_EASY
EASY            → EASY
QUALITY         → HARD
LONG            → EASY
CROSS_TRAINING  → EASY or MODERATE
```

LONG은 duration이 길다고 intensity가 HARD인 것은 아니다.

---

# 37. Intensity limitations

아직:

```text
pace
HR zone
LTHR
RPE
```

가 없으므로 intensity class는 qualitative label일 뿐이다.

---

# 38. WorkoutRecommendation DTO

권장:

```text
WorkoutRecommendation
```

필드:

```text
asOfDate
recommendedIntent

durationMinMinutes
durationMaxMinutes
intensityClass

confidence
dataSufficiency

reasons
summary

decisionContext
```

---

# 39. decisionContext 포함 여부

API에서 nested `TrainingDecisionContext`를 통째로 포함할지 검토한다.

장점:

```text
추천 근거 확인 용이
```

단점:

```text
응답이 커짐
```

추천:

```text
Phase 5A에서는 포함
```

하여 explainability를 우선한다.

---

# 40. WorkoutRecommendationService

구현:

```text
WorkoutRecommendationService
```

의존:

```text
TrainingDecisionContextService
```

만 우선 사용한다.

ActivityRepository 직접 접근 금지.

TrainingLoadService 직접 접근도 가능하면 피한다.

---

# 41. Layering

권장:

```text
WorkoutRecommendationController
        ↓
WorkoutRecommendationService
        ↓
TrainingDecisionContextService
```

Controller에 recommendation logic 금지.

---

# 42. Recommendation policy 분리

조건문이 길어지면:

```text
WorkoutRecommendationPolicy
```

같은 package-private/simple class로 분리 가능.

하지만 rule-engine abstraction은 만들지 않는다.

---

# 43. Initial selection precedence

초기 deterministic precedence 권장:

```text
1. Strong recovery/rest context
2. Recent long context
3. Multiple active days
4. Limited history
5. Long-run due
6. Normal easy
7. Cross-training fallback
8. Quality only if explicitly supported
```

실제 구현에서 후보 membership을 항상 확인한다.

---

# 44. Example — recent long

Context:

```text
daysSinceLongRun = 1
candidate = REST, RECOVERY, EASY
```

권장:

```text
RECOVERY
```

단 consecutive active days가 높으면 REST를 선택할 수 있다.

---

# 45. Example — many active days

Context:

```text
consecutiveActiveDays = 4
candidate includes REST
```

권장:

```text
REST
```

---

# 46. Example — fresh normal day

Context:

```text
yesterday rest
no recent long
load trend STABLE
history sufficient
```

후보:

```text
EASY, QUALITY, LONG, CROSS_TRAINING
```

현재 quality intelligence가 부족하므로 기본:

```text
EASY
```

를 추천한다.

---

# 47. Example — long due

Context:

```text
LONG candidate
lastLongRun > 6 days ago
recent pattern reasonable
history sufficient
```

이면:

```text
LONG
```

추천 가능.

---

# 48. Long due는 calendar scheduling heuristic

`6 days` 등은 훈련 배치 규칙이다.

생리학적 회복 보장으로 설명하지 않는다.

---

# 49. Example — limited history

Context:

```text
LIMITED_HISTORY
```

추천:

```text
EASY
```

또는 후보에 따라 REST.

Confidence/DataSufficiency는 LOW.

---

# 50. Example — no recent running

최근 cycling만 있음:

```text
NO_RECENT_RUNNING
RECENT_CYCLING
```

후보에 EASY/CROSS_TRAINING이 있으면 정책에 따라 EASY 또는 CROSS_TRAINING.

초기 default는 EASY 쪽을 권장.

---

# 51. QUALITY recommendation

Phase 5A 초기 버전에서는:

```text
recommendedIntent = QUALITY
```

를 발생시키지 않는 것도 허용한다.

README에:

```text
QUALITY intent exists but is not automatically selected until quality-session/intensity context is available.
```

라고 명시한다.

이 방향을 권장한다.

---

# 52. API

추가:

```http
GET /api/v1/workout-recommendation
```

optional:

```text
date=YYYY-MM-DD
```

없으면 athlete local today.

---

# 53. Response example

```json
{
  "asOfDate": "2026-09-30",
  "recommendedIntent": "EASY",
  "durationMinMinutes": 30,
  "durationMaxMinutes": 60,
  "intensityClass": "EASY",
  "confidence": "MEDIUM",
  "dataSufficiency": "HIGH",
  "reasons": [
    "RECENT_REST",
    "LOAD_TREND_STABLE",
    "DEFAULT_EASY"
  ],
  "summary": "Recent training context supports a normal easy session.",
  "decisionContext": {
    "..."
  }
}
```

---

# 54. REST response

```text
durationMinMinutes = 0
durationMaxMinutes = 0
intensityClass = NONE
```

---

# 55. Null policy

추천 intent는 반드시 존재해야 한다.

즉:

```text
recommendedIntent = null
```

은 허용하지 않는다.

어떤 상황에서도 최소 REST 또는 EASY 중 하나를 선택할 수 있어야 한다.

---

# 56. Historical date

과거 `date` 조회 시 그 날짜 이후 데이터가 recommendation에 들어가면 안 된다.

4C의 no-look-ahead semantics를 그대로 보존한다.

---

# 57. Determinism

동일:

```text
activities
asOfDate
timezone
configuration
```

이면 동일 Recommendation이 나와야 한다.

---

# 58. No randomization

랜덤 workout selection 금지.

---

# 59. No LLM dependency

추천을 위해 OpenAI/Claude/외부 모델을 호출하지 않는다.

Spring deterministic engine으로 구현한다.

---

# 60. No Garmin dependency

Garmin connector/network 호출 없음.

---

# 61. No persistence

추천 결과를 DB에 저장하지 않는다.

이번 Phase:

```text
migration = NO
schema change = NO
```

---

# 62. No cache

Redis/cache 추가 금지.

---

# 63. Test — recent long

어제 long:

```text
recommendedIntent = RECOVERY
```

또는 policy가 명확히 정의한 conservative result.

reason 포함.

---

# 64. Test — recent long + many active days

예:

```text
daysSinceLongRun=1
consecutiveActiveDays=4
```

추천:

```text
REST
```

---

# 65. Test — normal rested day

```text
consecutiveRestDays >= 1
no recent long
stable load
sufficient history
```

추천:

```text
EASY
```

---

# 66. Test — long due

```text
LONG candidate
daysSinceLongRun >= configured minimum
history sufficient
```

추천:

```text
LONG
```

---

# 67. Test — limited history

추천이:

```text
EASY or REST
```

같은 보수적 intent인지 검증.

confidence:

```text
LOW
```

---

# 68. Test — quality not auto-selected

후보에 QUALITY가 있어도 현재 데이터 모델에서는 기본 recommendation이 QUALITY가 되지 않는 것을 명시적으로 테스트한다.

---

# 69. Test — candidate membership

모든 추천 결과가 입력 candidate set 안에 있는지 검증한다.

---

# 70. Test — deterministic

같은 context 여러 번 → 완전히 동일 결과.

---

# 71. Test — duration mapping

각 intent별 min/max/intensity mapping 검증.

---

# 72. Test — REST duration

0/0/NONE 검증.

---

# 73. Test — historical date

미래 activity leakage 없음.

---

# 74. Test — timezone

athlete local today 기준.

---

# 75. Test — invalid date

기존:

```text
400 INVALID_REQUEST
```

유지.

---

# 76. Test — API response

nested context 포함 여부와 enum serialization 검증.

---

# 77. Existing API regression

유지:

```text
/api/v1/health
/api/v1/activities
/api/v1/garmin/sync
/api/v1/garmin/sync/status
/api/v1/training-load
/api/v1/training-load/weekly
/api/v1/training-state
/api/v1/training-decision-context
/actuator/health
```

---

# 78. No workout structure

다음 필드 금지:

```text
steps
warmup
cooldown
repeatCount
paceMin
paceMax
heartRateTarget
incline
intervalDistance
```

Phase 5B 이후.

---

# 79. No race goal

현재 DB/domain에 race goal이 없으므로:

```text
10K goal
half marathon goal
race date
```

를 recommendation engine에 넣지 않는다.

대화 memory의 사용자 목표를 코드에 하드코딩하지 않는다.

---

# 80. No medical/readiness interpretation

Recommendation reason으로:

```text
injury risk
overtraining
medically unsafe
recovered
fully recovered
```

같은 표현을 사용하지 않는다.

---

# 81. README

추가:

```text
GET /api/v1/workout-recommendation
```

다음 명시:

```text
Recommendation chooses workout intent only.
It does not generate workout steps.
QUALITY is not automatically selected in the initial model.
Recommendation heuristics are scheduling rules, not medical or injury-risk assessments.
```

---

# 82. Work Order

저장:

```text
docs/work-orders/
2026-09-30-workout-recommendation-model-instruction.md

docs/work-orders/
2026-09-30-workout-recommendation-model.md
```

---

# 83. Java regression

실행:

```powershell
cd server
.\gradlew.bat clean test
```

baseline:

```text
207
```

신규 테스트 증가.

전부 PASS.

---

# 84. Python

변경 없음.

---

# 85. Database

기대:

```text
migration: NO
schema change: NO
persistence: NO
```

---

# 86. Secrets

기존 workflow:

```text
diff review
secrets scan
```

실행.

---

# 87. Definition of Done

```text
[ ] WorkoutRecommendationService
[ ] WorkoutRecommendation DTO
[ ] one recommended intent
[ ] candidate membership enforcement
[ ] deterministic selection
[ ] REST recommendation
[ ] RECOVERY recommendation
[ ] EASY recommendation
[ ] LONG recommendation
[ ] CROSS_TRAINING supported
[ ] QUALITY enum retained
[ ] QUALITY not auto-selected without sufficient model
[ ] duration min/max
[ ] intensity class
[ ] confidence
[ ] data sufficiency
[ ] recommendation reasons
[ ] deterministic summary
[ ] decision context reused
[ ] historical no-look-ahead
[ ] athlete timezone
[ ] GET /api/v1/workout-recommendation
[ ] no Garmin network
[ ] no repository direct dependency where avoidable
[ ] no persistence
[ ] no cache
[ ] no migration
[ ] no workout steps
[ ] no pace/HR targets
[ ] no readiness/injury-risk classification
[ ] Java full regression
[ ] README
[ ] result document
[ ] secrets scan
[ ] diff review
[ ] commit
[ ] push
```

---

# 88. 권장 commit

```text
feat: add workout recommendation model
```

---

# 89. 완료 보고 형식

## Recommendation Model

```text
inputs:
selection precedence:
default intent:
quality policy:
```

## Intent Policies

```text
REST:
RECOVERY:
EASY:
QUALITY:
LONG:
CROSS_TRAINING:
```

## Duration / Intensity

```text
REST:
RECOVERY:
EASY:
QUALITY:
LONG:
CROSS_TRAINING:
```

## Confidence

```text
LOW:
MEDIUM:
HIGH:
data sufficiency:
```

## API

```text
GET /api/v1/workout-recommendation:
```

## Tests

```text
Java total:
passed:
failed:

recent long:
many active days:
normal rested:
long due:
limited history:
quality suppression:
historical leakage:
timezone:
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
```

## Git

```text
branch:
commit:
push:
```

## Known Limitations

```text
no quality-session model
no pace targets
no HR-zone/LTHR model
no exact workout duration selection
no workout steps
no race-goal awareness
no readiness/recovery model
```

## Next Phase

자동 시작하지 않는다.

```text
Phase 5B
Workout Prescription

WorkoutRecommendation
        ↓
exact duration
        ↓
workout structure
        ↓
warm-up / main / cooldown
        ↓
pace/HR/intensity target model

Garmin / Intervals.icu rendering은 그 이후 Phase
```

---

# 90. 핵심 invariant

완료 후:

```text
TrainingDecisionContext
        ↓
WorkoutRecommendationService
        ↓
WorkoutRecommendation
```

이어야 한다.

Recommendation은:

```text
오늘 어떤 종류의 훈련을 할지
얼마나 긴 범위인지
어떤 강도 클래스인지
왜 그렇게 선택했는지
```

를 설명한다.

하지만 아직:

```text
정확히 몇 분
몇 km
몇 회 반복
몇 분/km
몇 bpm
```

을 결정하지 않는다.

**Intent selection과 prescription을 분리한다.**

**현재 데이터로 판별할 수 없는 QUALITY를 억지로 자동 추천하지 않는다.**