> 원본 작업지시서 (2026-09-30, Phase 4C). 구현 기록은 `2026-09-30-training-decision-context.md` 참고.
> 추가 사용자 지시: CLAUDE.md, running-ai-dev 준수 / Phase 4A·4B 결과 재사용 / quality 분류는 현재 normalized data로 확신할 수 있는 범위까지만, 임의의 HR/LTHR threshold 금지 / workout 세부 prescription, readiness·risk 등급, Garmin network 호출, migration·persistence·cache 금지 / 구현 → 전체 Java regression → README/결과 문서 → secrets 검사 → diff review → commit → push / Phase 5 시작 금지 / 결과 보고는 한글.

# RunningAI Phase 4C
## Recent Training Pattern / Session Classification / Recommendation Context

## 1. 현재 상태

완료:

```text
Phase 1     Spring Boot foundation
Phase 2     PostgreSQL / Flyway
Phase 3A    Garmin ingestion
Phase 3B    Real Garmin E2E
Phase 3C    Sync / Scheduler / Runtime preparation
Phase 4A    Training Load Foundation
Phase 4B    Training State
```

latest known commit:

```text
2008227
```

현재 Java regression baseline:

```text
172 tests
172 passed
```

Phase 4B 현재 흐름:

```text
Normalized Activity
      ↓
TrainingLoadService
      ↓
TrainingStateService
      ↓
TrainingState
```

이번 Phase에서는 여기에:

```text
recent session history
last quality / long session
days since session
consecutive activity/rest pattern
candidate training types
decision reasons
```

를 추가한다.

---

# 2. 이번 Phase 목표

오늘의 workout을 생성하기 전에 필요한 **decision context**를 만든다.

목표 흐름:

```text
Activities
   +
TrainingState
   ↓
TrainingDecisionContextService
   ↓
TrainingDecisionContext
```

결과는 다음 질문에 답할 수 있어야 한다.

```text
최근 며칠 어떻게 훈련했는가?
마지막 quality 후보는 언제였는가?
마지막 long run은 언제였는가?
연속 며칠 운동했는가?
최근 휴식일은 얼마나 있었는가?
현재 load trend는 어떤가?
오늘 고려할 수 있는 훈련 종류는 무엇인가?
그 후보가 나온 이유는 무엇인가?
```

---

# 3. 중요한 경계

이번 Phase는:

```text
context + candidate generation
```

까지만 담당한다.

아직:

```text
5 x 1km @ 4:30/km
30 min @ HR Zone 2
20km long run
```

같은 세부 workout prescription은 만들지 않는다.

그것은 Phase 5 scope다.

---

# 4. Recommendation과 Decision 분리

Phase 4C가 반환하는 것은 최종 workout이 아니다.

예:

```text
candidateTypes:
- EASY
- RECOVERY
- REST
```

와:

```text
reasons:
- LONG_RUN_RECENT
- QUALITY_WITHIN_48_HOURS
```

정도다.

Phase 5가 이 context를 받아 실제 workout을 구성한다.

---

# 5. Session Classification

최근 normalized activity를 다음과 같은 설명 가능한 분류로 분류한다.

권장 enum:

```text
REST
EASY_OR_GENERAL
QUALITY_CANDIDATE
LONG
INDOOR_CYCLING
```

이름은 현재 프로젝트 convention에 맞게 조정 가능하다.

---

# 6. REST의 의미

REST는 Activity row가 아니다.

해당 athlete local date에 supported normalized activity가 없거나 training load가 0인 날을 의미한다.

즉 daily pattern 생성 시 synthetic day classification이다.

---

# 7. EASY_OR_GENERAL

현재 Phase에서는 intensity-aware load가 없으므로 일반 러닝을 과도하게 세분화하지 않는다.

다음에 해당하는 RUN / TREADMILL_RUN은 기본적으로:

```text
EASY_OR_GENERAL
```

후보다.

단 LONG 또는 QUALITY_CANDIDATE 조건을 만족하면 해당 분류가 우선한다.

---

# 8. LONG 분류

Long run은 현재 설명 가능한 heuristic으로 정의한다.

권장 초기 규칙:

```text
RUN or TREADMILL_RUN
AND
duration >= 90 minutes
```

또는 현재 사용자의 훈련 규모에 맞춘 더 낮은 값이 필요하다면 configuration으로 둔다.

중요:

숫자를 코드 여러 곳에 hard-code하지 않는다.

예:

```text
running-ai.training.classification.long-run-min-duration=90m
```

기본값을 제공한다.

---

# 9. Long run heuristic 의미

`LONG`은:

```text
RunningAI heuristic classification
```

일 뿐 Garmin 또는 공식 훈련 유형을 의미하지 않는다.

README에 명확히 적는다.

---

# 10. QUALITY_CANDIDATE

현재는 정확한 interval/tempo 구조가 Activity entity에 없으므로 quality를 확정 판정하면 안 된다.

따라서 이름도:

```text
QUALITY_CANDIDATE
```

처럼 불확실성을 드러낸다.

---

# 11. Quality heuristic

현재 사용할 수 있는 normalized field를 먼저 조사한다.

예:

```text
duration
distance
averageHeartRate
maxHeartRate
activity type
```

실제 entity를 확인한 뒤 가장 단순하고 설명 가능한 heuristic만 사용한다.

---

# 12. Quality heuristic 기본 방향

HR 정보가 충분한 경우에만:

```text
averageHeartRate
```

를 활용할 수 있다.

하지만 threshold를 개인 역치 대비로 계산할 기반이 아직 domain에 없으면:

```text
absolute bpm threshold
```

를 임의로 넣지 않는다.

---

# 13. LTHR integration 금지

사용자의 Garmin LTHR가 별도 대화에 존재하더라도 현재 Spring domain에 정식 저장되지 않았다면 코드에 넣지 않는다.

Phase 4C는 repository에 존재하는 데이터만 사용한다.

---

# 14. Quality fallback

현 domain으로 신뢰도 높은 quality 분류가 어렵다면:

```text
QUALITY_CANDIDATE = unavailable
```

또는 보수적인 heuristic만 지원한다.

억지로 모든 러닝을 easy/quality로 나누지 않는다.

---

# 15. Prefer explicit future extensibility

분류 결과가 왜 그렇게 나왔는지 reason을 함께 제공한다.

예:

```text
classification = LONG
classificationReason = DURATION_THRESHOLD
```

향후 structured workout metadata가 생기면 분류 근거를 확장할 수 있게 한다.

---

# 16. Same-day multiple activities

같은 날짜에 여러 activity가 존재할 수 있다.

daily pattern에서는 하루를 하나의 대표 classification으로 만든다.

우선순위 권장:

```text
LONG
QUALITY_CANDIDATE
EASY_OR_GENERAL
INDOOR_CYCLING
REST
```

---

# 17. 왜 우선순위가 필요한가

예:

```text
AM easy 30min
PM long 100min
```

이면 해당 날짜는 recovery 관점에서:

```text
LONG
```

이 더 의미 있다.

---

# 18. DailyTrainingPattern

권장 DTO:

```text
DailyTrainingPattern
```

필드:

```text
date
classification
activityCount
totalLoadMinutes
runningDurationSeconds
runningDistanceMeters
cyclingDurationSeconds
```

필요하면:

```text
classificationReason
```

추가.

---

# 19. Pattern window

기본 최근:

```text
14 calendar days
```

를 사용한다.

이유:

```text
7일만 보면 quality/long 이후 경과일 계산이 제한적
28일은 decision context로는 과함
14일이면 최근 훈련 패턴 파악에 충분
```

설정 가능하게 만들어도 된다.

---

# 20. lastLongRunDate

최근 pattern/history에서 마지막 LONG 날짜.

없으면:

```text
null
```

---

# 21. daysSinceLongRun

정의:

```text
asOfDate - lastLongRunDate
```

calendar day 차이.

오늘 long run이었다면:

```text
0
```

어제였다면:

```text
1
```

없으면:

```text
null
```

---

# 22. lastQualityDate

최근 QUALITY_CANDIDATE 날짜.

없으면 null.

---

# 23. daysSinceQuality

동일한 calendar day 차이.

---

# 24. Last Running Day

추가로:

```text
lastRunningDate
daysSinceRunning
```

도 useful하다.

RUN/TREADMILL_RUN이 있던 마지막 날짜 기준.

---

# 25. Last Active Day

cycling 포함 supported activity가 있었던 마지막 날짜.

필드:

```text
lastActiveDate
daysSinceActive
```

---

# 26. Consecutive Active Days

`asOfDate`부터 과거로 역순 확인하여:

```text
dailyLoad > 0
```

가 연속된 날짜 수.

예:

```text
Mon active
Tue active
Wed active
Thu asOf active
```

이면:

```text
consecutiveActiveDays = 4
```

---

# 27. Consecutive Rest Days

반대로 asOfDate부터 뒤로:

```text
dailyLoad == 0
```

연속 일수.

---

# 28. 최근 7일 pattern

API/context에 사람이 읽기 쉬운 최근 7일 classification sequence를 포함할 수 있다.

예:

```text
[
  REST,
  EASY_OR_GENERAL,
  QUALITY_CANDIDATE,
  REST,
  EASY_OR_GENERAL,
  LONG,
  REST
]
```

날짜를 함께 제공한다.

---

# 29. Load Trend

Phase 4B의:

```text
current7DayLoad
previous7DayLoad
weeklyLoadChangePercent
rampLoad
```

를 재사용한다.

새 계산을 하지 않는다.

---

# 30. LoadTrend enum

단순 설명용으로:

```text
INCREASING
STABLE
DECREASING
UNKNOWN
```

을 둘 수 있다.

하지만 threshold 설정은 명확해야 한다.

---

# 31. LoadTrend 초기 규칙

권장:

```text
weeklyLoadChangePercent == null
→ UNKNOWN
```

나머지는 소폭 변동을 STABLE로 둘 수 있다.

예:

```text
> +10%  → INCREASING
< -10%  → DECREASING
else    → STABLE
```

단 이는 physiological safety threshold가 아니다.

단지 trend labeling threshold임을 명시한다.

---

# 32. Trend threshold configuration

가능하면:

```text
running-ai.training.decision.stable-band-percent=10
```

처럼 설정 가능하게 한다.

이 숫자를 위험 판단에 사용하지 않는다.

---

# 33. Candidate Training Types

권장 enum:

```text
REST
RECOVERY
EASY
QUALITY
LONG
CROSS_TRAINING
```

이것은 최종 추천이 아니라 후보군이다.

---

# 34. Candidate generation 목적

candidate list는:

```text
오늘 가능한 선택지 범위를 좁히는 것
```

이다.

하나의 winner를 강제로 고르지 않아도 된다.

---

# 35. Decision Reason enum

권장 예:

```text
LONG_RUN_RECENT
QUALITY_RECENT
MULTIPLE_ACTIVE_DAYS
REST_DAY_RECENT
LOAD_INCREASING
LOAD_DECREASING
LOW_RECENT_ACTIVITY
NO_RECENT_RUNNING
RECENT_CYCLING
LIMITED_HISTORY
```

필요 최소 수준만 구현한다.

---

# 36. 중요한 원칙

Reason은 데이터로 직접 설명 가능해야 한다.

예:

```text
LONG_RUN_RECENT
```

이면 실제:

```text
daysSinceLongRun
```

가 context에 있어야 한다.

---

# 37. Candidate Rule — Long recent

예:

```text
daysSinceLongRun <= 1
```

이면:

```text
REST
RECOVERY
EASY
```

후보를 포함할 수 있다.

그리고:

```text
LONG_RUN_RECENT
```

reason 추가.

---

# 38. Candidate Rule — Quality recent

quality classification이 가능한 경우:

```text
daysSinceQuality <= 1
```

이면:

```text
REST
RECOVERY
EASY
```

중심 후보.

QUALITY 재후보는 제외하는 방향을 검토한다.

---

# 39. Candidate Rule — Consecutive activity

예:

```text
consecutiveActiveDays >= 3
```

이면:

```text
REST
RECOVERY
EASY
```

후보를 포함하고:

```text
MULTIPLE_ACTIVE_DAYS
```

reason을 제공한다.

---

# 40. Candidate Rule — Rested

예:

```text
consecutiveRestDays >= 1
```

이고 recent long/quality가 없으면:

```text
EASY
QUALITY
LONG
CROSS_TRAINING
```

등을 후보로 둘 수 있다.

---

# 41. Candidate Rule — Low history

최근 28일 activity가 거의 없거나 chronic baseline이 매우 적으면:

```text
LIMITED_HISTORY
```

reason을 추가한다.

이 경우:

```text
EASY
REST
CROSS_TRAINING
```

처럼 보수적인 후보를 우선 검토한다.

---

# 42. 중요한 제한

Phase 4C에서도 다음 표현을 만들어서는 안 된다.

```text
QUALITY is safe
LONG is dangerous
You should run intervals
```

candidate는 판단 context일 뿐이다.

---

# 43. Deterministic ordering

Candidate list는 매번 같은 순서로 반환한다.

예:

```text
REST
RECOVERY
EASY
QUALITY
LONG
CROSS_TRAINING
```

enum order에 의존하기보다 explicit ordering을 검토한다.

---

# 44. Candidate deduplication

여러 rule이 EASY를 추가해도 결과에는 한 번만 나타난다.

---

# 45. Candidate evidence

가능하면 candidate마다 reason mapping을 만들지 않고 context-level reason list를 유지한다.

과도한 설명 구조를 만들지 않는다.

Phase 5가 context 전체를 이용하면 충분하다.

---

# 46. TrainingDecisionContext DTO

권장 필드:

```text
asOfDate

trainingState

recentPattern

lastRunningDate
daysSinceRunning

lastActiveDate
daysSinceActive

lastLongRunDate
daysSinceLongRun

lastQualityDate
daysSinceQuality

consecutiveActiveDays
consecutiveRestDays

loadTrend

candidateTrainingTypes
reasons
```

---

# 47. TrainingState 중복

TrainingState를 통째로 nested DTO로 넣을지 필요한 핵심 값만 복사할지 현재 API style을 보고 결정한다.

중복 DTO 필드 복사를 과도하게 하지 않는다.

---

# 48. Service

구현:

```text
TrainingDecisionContextService
```

책임:

```text
TrainingState 조회
recent daily pattern 생성
session classification
days-since 계산
consecutive days 계산
trend 분류
candidate 생성
reason 생성
```

---

# 49. Repository dependency

가능하면 직접 ActivityRepository를 호출하지 않고:

```text
TrainingLoadService.daily(...)
```

와 필요한 activity history query를 최소화한다.

하지만 session classification에 개별 Activity가 필요하다면 repository range query 재사용은 허용한다.

---

# 50. Query efficiency

최근 28일 또는 14일 activities를 한 번 조회하여:

```text
daily pattern
last long
last quality
last running
```

을 함께 계산한다.

각 metric마다 repository를 반복 호출하지 않는다.

---

# 51. Long lookback

lastLongRun 또는 lastQuality가 14일 안에 없으면 null로 둘 것인지 더 긴 history를 조회할지 결정해야 한다.

권장:

```text
pattern = 14 days
history lookup = 28 days
```

즉 최근 pattern은 14일만 반환하되 session history 탐색은 28일 범위 사용.

---

# 52. History outside 28 days

28일에도 없다면:

```text
null
```

로 반환한다.

전체 DB를 무제한 search하지 않는다.

---

# 53. Same-day classification

여러 activity가 같은 day에 있을 때:

```text
LONG > QUALITY_CANDIDATE > EASY_OR_GENERAL > INDOOR_CYCLING
```

우선순위 적용.

---

# 54. Cycling + running

같은 날 cycling + easy running:

대표 classification:

```text
EASY_OR_GENERAL
```

running이 없고 cycling만 있으면:

```text
INDOOR_CYCLING
```

---

# 55. Candidate rules는 최소화

Phase 4C에서 rule engine을 만들지 않는다.

단순한 Java conditional logic이면 충분하다.

Drools 등의 dependency 금지.

---

# 56. Configuration

허용 가능한 config:

```text
long-run-min-duration
stable-band-percent
decision-history-days
pattern-days
```

정도.

threshold를 과도하게 외부화하지 않는다.

---

# 57. API

추가:

```http
GET /api/v1/training-decision-context
```

optional query:

```text
date=YYYY-MM-DD
```

없으면 athlete local today.

---

# 58. API response 예시

```json
{
  "asOfDate": "2026-09-30",

  "loadTrend": "INCREASING",

  "lastRunningDate": "2026-09-29",
  "daysSinceRunning": 1,

  "lastLongRunDate": "2026-09-28",
  "daysSinceLongRun": 2,

  "lastQualityDate": "2026-09-25",
  "daysSinceQuality": 5,

  "consecutiveActiveDays": 0,
  "consecutiveRestDays": 1,

  "candidateTrainingTypes": [
    "RECOVERY",
    "EASY",
    "QUALITY"
  ],

  "reasons": [
    "REST_DAY_RECENT",
    "LOAD_INCREASING"
  ],

  "recentPattern": [
    {
      "date": "2026-09-24",
      "classification": "EASY_OR_GENERAL"
    }
  ]
}
```

수치는 예시일 뿐이다.

---

# 59. TrainingState API 유지

기존:

```text
GET /api/v1/training-state
```

는 변경하지 않는다.

---

# 60. No workout generation

다음과 같은 field는 추가하지 않는다.

```text
pace
target HR
repetitions
interval distance
workout duration recommendation
Garmin workout steps
```

Phase 5 영역이다.

---

# 61. No user goal integration yet

10K 목표, 하프 목표, 대회 날짜 등은 아직 DB/domain에 정식 모델이 없다.

메모리나 외부 대화 내용을 코드에 넣지 않는다.

Athlete goal domain은 별도 Phase에서 필요 시 추가한다.

---

# 62. No personal health inference

부상, 질환, readiness 등의 개인 건강 상태를 추론하지 않는다.

현재 activity/load 데이터만 사용한다.

---

# 63. Test — Empty history

기대 예:

```text
lastRunning = null
lastLong = null
lastQuality = null

consecutiveActive = 0
consecutiveRest = patternDays 또는 정의한 범위

loadTrend = UNKNOWN

reasons include LIMITED_HISTORY
```

Candidate는 최소:

```text
REST
EASY
```

등 설명 가능한 값만 제공.

---

# 64. Test — Recent long run

어제 LONG:

```text
daysSinceLongRun = 1
reason = LONG_RUN_RECENT
```

candidate에:

```text
REST
RECOVERY
EASY
```

가 포함되는지 검증.

---

# 65. Test — Recent quality

quality heuristic을 구현한 경우:

```text
daysSinceQuality = 1
QUALITY_RECENT
```

검증.

quality 분류를 보류했다면 테스트도 해당 기능을 강요하지 않는다.

---

# 66. Test — Consecutive active days

최근 3일 연속 active:

```text
consecutiveActiveDays = 3
MULTIPLE_ACTIVE_DAYS
```

검증.

---

# 67. Test — Rest yesterday

asOfDate에는 activity 없음, 어제도 없음 등의 시나리오에서 consecutiveRestDays를 검증.

---

# 68. Test — Same-day multiple activities

run + cycle:

daily load 합산과 대표 classification 검증.

---

# 69. Test — Long priority

same day:

```text
easy run
long run
```

대표 classification LONG.

---

# 70. Test — Trend

Phase 4B TrainingState fixture를 통해:

```text
+15% → INCREASING
+5%  → STABLE
-20% → DECREASING
null → UNKNOWN
```

등을 검증한다.

이 threshold는 trend label일 뿐 위험 threshold가 아니다.

---

# 71. Test — Candidate deduplication

여러 reason이 동일 candidate를 추가해도 중복 없음.

---

# 72. Test — Deterministic order

같은 context에서 candidate 순서 항상 동일.

---

# 73. Test — Timezone

daily pattern/date calculations 모두 athlete timezone 기준인지 검증.

---

# 74. Test — Explicit historical date

과거 date query로 context를 조회했을 때 그 날짜 기준 미래 activity가 포함되지 않아야 한다.

---

# 75. Critical historical-date rule

`asOfDate` 이후 activity는:

```text
recentPattern
last*
candidate context
```

어디에도 들어가면 안 된다.

look-ahead leakage 금지.

---

# 76. Test — API default date

Clock 고정 + athlete timezone 기준.

---

# 77. Test — invalid date

기존:

```text
400 INVALID_REQUEST
```

유지.

---

# 78. Existing API regression

기존 API 모두 유지:

```text
/api/v1/health
/api/v1/activities
/api/v1/garmin/sync
/api/v1/garmin/sync/status
/api/v1/training-load
/api/v1/training-load/weekly
/api/v1/training-state
/actuator/health
```

---

# 79. No Garmin dependency

Decision Context는 Garmin connector/network를 호출하지 않는다.

---

# 80. Persistence

새 table 없음.

```text
migration = NO
schema change = NO
```

---

# 81. Cache

추가하지 않는다.

---

# 82. README

추가:

```text
GET /api/v1/training-decision-context
```

다음도 명시:

```text
candidate types are not final workout prescriptions
LONG and QUALITY_CANDIDATE are RunningAI heuristics
load trend labels are descriptive, not safety classifications
```

---

# 83. Work Order 문서

저장:

```text
docs/work-orders/
2026-09-30-training-decision-context-instruction.md

docs/work-orders/
2026-09-30-training-decision-context.md
```

---

# 84. Java regression

실행:

```powershell
cd server
.\gradlew.bat clean test
```

baseline:

```text
172
```

모두 PASS.

---

# 85. Python

변경 없음.

---

# 86. Secrets

새 secret 없음.

기존 workflow:

```text
diff review
secrets scan
```

실행.

---

# 87. Definition of Done

```text
[ ] TrainingDecisionContextService
[ ] TrainingDecisionContext DTO
[ ] DailyTrainingPattern
[ ] session classification enum
[ ] REST synthetic day
[ ] EASY_OR_GENERAL
[ ] LONG heuristic
[ ] quality classification conservative or unavailable
[ ] same-day priority
[ ] recent 14-day pattern
[ ] 28-day session history lookup
[ ] last running date
[ ] days since running
[ ] last active date
[ ] days since active
[ ] last long date
[ ] days since long
[ ] last quality date where supported
[ ] days since quality
[ ] consecutive active days
[ ] consecutive rest days
[ ] load trend
[ ] candidate training type enum
[ ] decision reasons
[ ] candidate deduplication
[ ] deterministic candidate order
[ ] no future-data leakage
[ ] GET /api/v1/training-decision-context
[ ] athlete timezone
[ ] Clock reuse
[ ] no Garmin dependency
[ ] no persistence
[ ] no migration
[ ] no workout prescription
[ ] no readiness / injury-risk classification
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
feat: add training decision context
```

---

# 89. 완료 보고 형식

## Session Classification

```text
REST:
EASY_OR_GENERAL:
LONG:
QUALITY_CANDIDATE:
INDOOR_CYCLING:
same-day priority:
```

## History

```text
pattern window:
history window:
last running:
last active:
last long:
last quality:
```

## Consecutive Pattern

```text
active days:
rest days:
timezone:
```

## Load Trend

```text
source:
stable band:
INCREASING:
STABLE:
DECREASING:
UNKNOWN:
```

## Candidates

```text
types:
rules:
ordering:
deduplication:
```

## Reasons

```text
implemented:
```

## API

```text
GET /api/v1/training-decision-context:
```

## Tests

```text
Java total:
passed:
failed:

timezone:
future leakage:
classification:
candidate:
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
quality classification limited by current normalized activity fields
no pace/HR-zone/LTHR model
no workout prescription
no race-goal awareness
no readiness/recovery classification
```

## Next Phase

자동 시작하지 않는다.

```text
Phase 5A
Workout Recommendation Model

TrainingDecisionContext
        ↓
select one workout intent
        ↓
recommended duration / intensity class
        ↓
human-readable rationale

아직 Garmin structured workout rendering은 이후 Phase
```

---

# 90. 핵심 invariant

Phase 4C 완료 후:

```text
Activities
    ↓
Training Load
    ↓
Training State
    ↓
Training Decision Context
```

이어야 한다.

Decision Context는:

```text
어떤 훈련이 후보인지
왜 그런 후보가 나왔는지
```

설명해야 한다.

하지만:

```text
정확히 어떤 세트를 몇 분/몇 km로 할 것인지
```

는 결정하지 않는다.

**최근 데이터를 설명하고 후보를 좁히되, 실제 workout prescription은 Phase 5로 넘긴다.**