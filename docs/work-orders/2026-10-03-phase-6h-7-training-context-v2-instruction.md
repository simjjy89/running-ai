# RunningAI Phase 6H-7 — TrainingContext V2 & Claude Evidence Integration

## 0. 작업 환경

이번 작업은 **Main PC**에서 수행한다.

Repository:

`C:\running-ai-github`

Baseline:

`f70d223`

현재 완료 상태:

- Phase 6H-1A Detailed Activity Foundation ✅
- Phase 6H-1B Garmin Detailed Contract LIVE_VERIFIED ✅
- Phase 6H-1C Sample Fidelity ✅
- Phase 6H-4 Running Analysis Engine ✅
- Phase 6H-5 Intervals.icu Enrichment ✅
- Phase 6H-6 90-Day Historical Backfill ✅
- Java 21 System Normalization ✅
- Intervals API key rotation ✅

현재 데이터:

```text
90-day supported activities = 5

Garmin detail:
5/5 COMPLETE
5/5 FULL samples

RunningAI Analysis:
5/5 RUNNING_ANALYSIS_V1
5/5 COMPLETE

Intervals:
5/5 SOURCE_ID matched
90 days CTL/ATL/derived form

Garmin Recovery:
28 days attempted
17 days with actual recovery data
```

Test baseline:

```text
Spring H2          1101 passed
Spring PostgreSQL  1101 passed
Python              134 passed
```

Runtime safety:

```text
WORKOUT_PUBLISHING_ENABLED=false
WORKOUT_PUBLISHING_SCHEDULER_ENABLED=false
RUNNING_AI_DRAFT_PUBLISHING_ENABLED=false
RUNNINGAI_MCP_ENABLED=false
```

Historical backfill 완료.

이번 Phase에서는 **더 이상 Garmin/Intervals 데이터를 수집하는 것이 아니라, 저장된 evidence를 Claude Coach에 정확하고 압축된 형태로 전달한다.**

---

# 1. 핵심 목표

현재 AI Coach 흐름:

```text
TrainingDecisionContext
+ Threshold
+ Recovery
        ↓
TrainingContext V1
        ↓
Claude
```

를 보존하면서 새로운 경로를 추가한다.

```text
Garmin detail
        +
RunningAI Analysis
        +
Intervals CTL/ATL/load
        +
Garmin Recovery
        +
Athlete Threshold
        +
User constraints
        ↓
TrainingContext V2
        ↓
Compact Evidence Snapshot
        ↓
Claude Coach
```

V1은 삭제하지 않는다.

V2를 병렬 구현한다.

---

# 2. 가장 중요한 원칙

TrainingContext V2는 **raw data dump가 아니다.**

Claude에게 절대 전달하지 않는다:

```text
1 Hz sample arrays
raw Garmin JSON
raw Intervals JSON
GPS coordinates
activity external IDs
Garmin IDs
Intervals IDs
tokens
API keys
credentials
```

Claude에게 전달하는 것은:

```text
source facts
+
RunningAI derived evidence
+
Intervals training-model metrics
+
recovery evidence
+
data coverage
```

뿐이다.

---

# 3. Work order 기록

이 지시서를 저장:

`docs/work-orders/2026-10-03-phase-6h-7-training-context-v2-instruction.md`

완료 결과:

`docs/work-orders/2026-10-03-phase-6h-7-training-context-v2-result.md`

---

# 4. Git preflight

확인:

```text
repo = C:\running-ai-github
branch = main
working tree = clean
main = origin/main
f70d223 ancestor
```

필요할 경우:

`git pull --ff-only origin main`

만 사용.

금지:

- reset --hard
- clean -fd
- force push
- destructive rebase

---

# 5. V1 삭제 금지

현재:

`TrainingContext`

`TrainingContextBuilder`

`TrainingDecisionContextService`

기반 V1은 유지한다.

기존 V1 테스트도 계속 통과해야 한다.

이번 Phase에서는:

```text
V1 = compatibility/reference implementation
V2 = new evidence-rich implementation
```

으로 운영한다.

---

# 6. 공통 Coach Context contract

현재 `AiCoach`는 구체 타입:

`TrainingContext`

에 의존한다.

V1/V2 병렬 지원을 위해 provider-neutral 공통 contract를 도입한다.

권장:

```kotlin
interface CoachTrainingContext {
    val date: LocalDate
    val athlete: AthleteThresholds
    val constraints: SessionConstraints
}
```

또는 sealed interface가 repository style에 더 적합하면 사용 가능.

기존:

`TrainingContext`

가 이를 구현하도록 한다.

신규:

`TrainingContextV2`

도 동일 contract를 구현한다.

---

# 7. Context version

명시적 enum:

```text
V1
V2
```

또는:

```text
TRAINING_CONTEXT_V1
TRAINING_CONTEXT_V2
```

를 도입한다.

Prompt와 persisted draft에서 어떤 context를 사용했는지 반드시 알 수 있어야 한다.

---

# 8. Context selection feature gate

configuration:

```yaml
running-ai:
  coach:
    context-version: ${RUNNING_AI_TRAINING_CONTEXT_VERSION:V1}
```

를 추가한다.

Source default:

`V1`

을 유지한다.

이유:

- 기존 deployment backward compatibility
- V2 validation 전 자동 전환 방지

Main PC에서는 Phase 끝에 live validation을 통과한 경우에만:

```text
RUNNING_AI_TRAINING_CONTEXT_VERSION=V2
```

로 설정한다.

---

# 9. Builder router

권장:

```text
CoachTrainingContextBuilder
    ├─ TrainingContextBuilder       (V1)
    └─ TrainingContextV2Builder     (V2)
```

선택은 configuration에 의해 deterministic해야 한다.

WorkoutDraftService가 V1/V2 implementation을 직접 알지 않게 한다.

예:

```text
WorkoutDraftService
        ↓
CoachTrainingContextBuilder
        ↓
V1 or V2
```

---

# 10. V2는 DB-only

`TrainingContextV2Builder`는 **저장된 DB data만 읽는다.**

금지:

- Garmin API call
- Intervals API call
- recovery live sync
- detail refresh
- analysis recompute
- backfill trigger

Context 생성은 pure read operation이어야 한다.

---

# 11. No look-ahead leakage

매우 중요.

session date가 `D`라면 TrainingContext V2에 포함될 수 있는 데이터는:

```text
activity started <= D
fitness date <= D
recovery date <= D
```

뿐이다.

과거 날짜의 workout을 생성할 때 미래 데이터를 context에 섞으면 안 된다.

activity의 athlete-local date/time을 사용한다.

동일 날짜에 이미 완료된 activity가 저장되어 있다면 포함 가능하다.

`D+1` 이후 data는 절대 포함하지 않는다.

자동 테스트 필수.

---

# 12. TrainingContext V2 top-level

권장 구조:

```text
TrainingContextV2
├─ contextVersion
├─ date
├─ athlete
├─ dataCoverage
├─ recovery
├─ trainingLoad
├─ trainingRhythm
├─ recentActivities
├─ recentStructuredQuality
└─ constraints
```

이름은 repository convention에 맞춰 조정 가능.

---

# 13. athlete

기존 threshold data 재사용:

```text
lactateThresholdHeartRateBpm
lactateThresholdPaceSecondsPerKm
```

추정 금지.

null이면 null.

---

# 14. dataCoverage — 필수

현재 데이터가 적다는 사실을 Claude가 반드시 알 수 있어야 한다.

최소:

```text
historyWindowDays
supportedActivityCount
analysedActivityCount
fullSampleActivityCount

intervalsMatchedActivityCount

fitnessWindowDays
fitnessDaysAvailable

recoveryWindowDays
recoveryDaysAvailable

oldestActivityDate
newestActivityDate
```

현재 live dataset에서는 대략:

```text
historyWindowDays = 90
supportedActivityCount = 5
analysedActivityCount = 5
fullSampleActivityCount = 5
intervalsMatchedActivityCount = 5
fitnessDaysAvailable = 90
recoveryDaysAvailable = 17
```

단 production code는 실제 DB에서 계산한다.

5 activity밖에 없는데 90일 history라는 이유로 dense history처럼 표현하면 안 된다.

---

# 15. Coverage 자체를 평가하지 않는다

금지:

```text
historyQuality = POOR
recoveryCoverage = GOOD
```

대신 숫자로만 제공.

Claude가 context의 한계를 해석한다.

---

# 16. Recovery

기존:

`RecoveryContextBuilder`

를 재사용한다.

새 recovery baseline 계산을 중복 구현하지 않는다.

V2에도 기존:

- latest value
- ageDays
- personal baseline
- difference
- differencePercent
- sampleCount
- baseline status

를 그대로 전달한다.

---

# 17. Recovery provenance

V2 JSON 구조 또는 documentation에서:

```text
source = GARMIN
```

이라는 provenance가 명확해야 한다.

단 모든 nested metric마다 동일 문자열을 반복해서 JSON을 비대하게 만들 필요는 없다.

section-level provenance 권장.

---

# 18. Training load — Intervals first-class

V2에는 Intervals fitness model을 first-class로 제공한다.

새 모델 예:

```text
TrainingLoadContextV2
```

최소:

```text
sourceDate
ageDays

ctl
atl
derivedForm
rampRate

ctlLoad
atlLoad
```

의미:

```text
CTL = Intervals calculated fitness
ATL = Intervals calculated fatigue
derivedForm = CTL - ATL
```

`wellness.fatigue`는 사용 금지.

---

# 19. Fitness latest lookup

session date `D`에 대해:

`D` 또는 그 이전의 가장 최근 stored Intervals fitness day를 사용한다.

해당 날짜도 함께 전달한다.

예:

```text
sourceDate
ageDays
```

오늘 값이 없다고 어제 값을 오늘 값처럼 표시하지 않는다.

---

# 20. Fitness trend

Claude가 단일 CTL/ATL snapshot만 보는 것보다 변화 방향을 알 수 있게 compact trend를 제공한다.

권장:

```text
current
sevenDaysAgo
twentyEightDaysAgo
```

각 snapshot:

```text
date
ctl
atl
derivedForm
```

정확한 D-7/D-28 row가 없으면 null.

nearest-day interpolation을 하지 않는다.

---

# 21. Legacy load metrics

기존 `WeeklyContext`는 V1 compatibility 때문에 유지한다.

V2에서는 Intervals CTL/ATL과 기존 RunningAI load-minutes를 같은 이름으로 섞지 않는다.

필요하면 별도 section:

```text
localLoadSummary
```

로 보존할 수 있다.

하지만 Claude prompt를 불필요하게 중복시키지 않도록 실제 유용성을 평가한다.

권장:

V2에서는 Intervals load model을 주 load context로 사용하고,

기존 TrainingDecisionContext에서 필요한 것은:

- active/rest streak
- recent calendar pattern
- last run/long run

정도로 제한한다.

---

# 22. CandidateTrainingTypes 제거

V1에는:

`candidateTrainingTypes`

가 있다.

이 값은 deterministic heuristic가 사실상 workout 후보를 미리 좁힌다.

V2에는 **넣지 않는다.**

Claude가 rich evidence를 보고 session type을 선택한다.

기존 system prompt 원칙:

> software collects data; coach chooses session

과 일치하도록 한다.

V1은 변경하지 않는다.

---

# 23. Training rhythm

V2에는 decision이 아니라 factual rhythm만 제공한다.

권장:

```text
consecutiveActiveDays
consecutiveRestDays

lastRunDate
daysSinceLastRun

lastLongRunDate
daysSinceLastLongRun

lastStructuredIntervalDate
daysSinceLastStructuredInterval
```

---

# 24. qualityDetectionAvailable 개선

V1:

```text
qualityDetectionAvailable = false
```

였던 이유는 normalised summary만으로 quality를 알 수 없었기 때문이다.

이제 Analysis Engine이 structured interval을 실제 Garmin workout structure로 확인할 수 있다.

V2에서는:

```text
structuredIntervalDetectionAvailable = true
```

로 구현 가능.

단 **speed 모양을 보고 quality를 추측하지 않는다.**

structured interval 조건:

Running Analysis의 실제 interval group 존재.

---

# 25. Threshold-like session 자동 판정 금지

다음은 이번 Phase에서 하지 않는다:

```text
"이 활동은 threshold workout이다"
```

LTHR exposure가 높다는 이유만으로 threshold session으로 이름 붙이지 않는다.

Claude에게 evidence만 전달한다.

예:

```text
lthr90Seconds
lthr95Seconds
lthr100Seconds
```

---

# 26. recentActivities

Claude에게 모든 90일 activity를 무제한 제공하지 않는다.

configuration 추가 권장:

```text
running-ai.coach.context-v2.max-recent-activities = 8
```

default:

`8`

현재 live dataset은 5개라 5개 모두 들어간다.

향후 activity가 많아져도 prompt size를 제한한다.

---

# 27. Activity ordering

최근 activity부터:

```text
startedAt DESC
```

순서.

session date 이후 activity 제외.

---

# 28. Compact recent activity model

각 activity는 최소:

```text
date
activityType

durationSeconds
distanceMeters

averageHeartRateBpm
maxHeartRateBpm
averageCadence
averagePower
```

가능한 값만.

null은 null.

---

# 29. Per-activity provenance structure

권장 구조:

```text
RecentActivityEvidence
├─ activity
├─ runningAiAnalysis
├─ intervals
└─ dataQuality
```

### activity

Garmin normalized facts.

### runningAiAnalysis

RunningAI-derived metrics.

### intervals

Intervals enrichment.

### dataQuality

FULL/DOWNSAMPLED/UNKNOWN + analysis status/version.

---

# 30. RunningAI analysis evidence

최근 activity에 compact하게 넣을 최소 evidence:

```text
hrChangePercent
speedChangePercent
speedHrDecouplingPercent

cadenceChangePercent

lthr90Seconds
lthr95Seconds
lthr100Seconds

lapSpeedCvPercent
```

없는 값 null.

---

# 31. HR zone evidence

5개 zone을 전부 넣을 수 있다.

단 seconds와 percent를 모두 반복해서 context를 비대하게 만들 필요는 없다.

권장:

```text
heartRateZonePercent:
{
  1: ...,
  2: ...,
  3: ...,
  4: ...,
  5: ...
}
```

그리고:

`totalZoneSeconds`

정도.

---

# 32. Interval activity evidence

interval group이 있을 경우 compact group summary 제공.

최대 group 수 configuration 권장:

```text
maxIntervalGroupsPerActivity = 3
```

각 group:

```text
workRepCount
meanSpeed
speedCvPercent
lastVsFirstSpeedChangePercent
hrProgressionBpm

recoveryHrDropBpm
recoveryDurationSeconds
```

개별 1Hz samples 금지.

---

# 33. Individual repetition 전달 제한

Claude에게 모든 repetition을 반드시 전달할 필요는 없다.

기본 V2 context에서는:

**group summary만** 전달한다.

현재 4 rep/10 rep 데이터를 모두 나열하면 prompt가 빠르게 커질 수 있다.

향후 필요하면 debug/read API에서 repetitions를 조회 가능.

---

# 34. Intervals per-activity enrichment

각 recent activity에:

```text
trainingLoad
intensity
ctlAfterActivity
atlAfterActivity
```

를 제공.

source:

`INTERVALS`

임을 명확히 한다.

---

# 35. Garmin vs Intervals 중복값

예:

Garmin training load와 Intervals training load가 둘 다 존재할 수 있다.

같은 metric으로 합치지 않는다.

V2 context에서 이름/section으로 provenance를 분리한다.

예:

```text
garminTrainingLoad
intervalsTrainingLoad
```

또는 Intervals section 안에 위치.

어느 쪽이 “정답”이라고 미리 판정하지 않는다.

---

# 36. Sample fidelity

각 activity의:

```text
sampleCompleteness
analysisStatus
analysisVersion
```

전달.

특히 DOWNSAMPLED/UNKNOWN인 미래 activity가 생기면 Claude가 evidence quality를 알 수 있어야 한다.

현재 live dataset은 5/5 FULL.

---

# 37. recentStructuredQuality

최근 activity list와 별도로 Claude가 빠르게 찾을 수 있게 structured interval summary를 제공해도 된다.

최대:

`3`

세션.

단 중복 JSON이 너무 커지지 않도록 recentActivities 전체 object를 복제하지 않는다.

권장 reference-style compact summary:

```text
date
activityType
intervalGroupCount
totalWorkRepCount
meanSpeedCvPercent
```

또는 생략하고 recentActivities의 intervalGroups만 사용해도 된다.

구현 시 snapshot size가 더 작고 명확한 쪽을 선택한다.

---

# 38. Long run evidence

기존 설정:

`long-run-min-duration = 90m`

를 그대로 재사용한다.

최근 LONG factual detection:

single run duration >= configured threshold.

새 임계값 만들지 않는다.

V2에는:

```text
lastLongRunDate
daysSinceLastLongRun
```

를 유지한다.

---

# 39. Context time window

활동 history:

`90 days`

Intervals fitness:

최대 `90 days`

Recovery baseline:

기존 `28 days`

Context 자체는 90일 row 전체를 Claude에게 전달하지 않는다.

90일 데이터는 builder가 **선택/요약**하는 input이다.

---

# 40. Prompt size budget

TrainingContext V2 snapshot에는 hard size guard를 둔다.

권장:

```text
max serialized context bytes = 65536
```

64 KiB.

일반적인 context는 이보다 훨씬 작아야 한다.

초과하면 조용히 truncate하지 않는다.

명확한:

`TRAINING_CONTEXT_TOO_LARGE`

오류를 발생시킨다.

---

# 41. Deterministic ordering

Snapshot reproducibility를 위해:

- recent activities deterministic order
- interval groups deterministic order
- map key order deterministic
- dates ISO
- nullable explicit

유지.

같은 DB snapshot → 같은 context JSON이어야 한다.

`computedAt=now` 같은 non-deterministic field를 V2 payload 안에 넣지 않는다.

---

# 42. Prompt에는 raw identity 없음

TrainingContext V2 serialization 테스트에서 다음 문자열/field가 존재하지 않는지 검증한다.

- externalActivityId
- garminActivityId
- intervalsActivityId
- latitude
- longitude
- API_KEY
- Authorization

---

# 43. Context persistence — 중요

현재 WorkoutDraft에는 어떤 context로 생성됐는지 snapshot이 저장되지 않는다.

V2부터 audit/reproducibility를 위해 draft별 context snapshot을 저장한다.

새 migration:

`V20__add_workout_draft_context_snapshot.sql`

권장 columns:

```text
context_version VARCHAR(32)
context_snapshot JSONB
context_built_at TIMESTAMP WITH TIME ZONE
context_sha256 VARCHAR(64)
```

기존 draft row는 nullable.

새 draft부터는 반드시 기록.

---

# 44. Snapshot hash

context JSON canonical serialization 후:

SHA-256

저장 권장.

목적:

- context diff
- audit
- duplicate/reproducibility 확인

hash만 log 가능.

context 본문을 log하지 않는다.

---

# 45. Context snapshot 저장 시점

흐름:

```text
build context
→ serialize canonical snapshot
→ Claude call
→ validate draft
→ save draft + same context snapshot
```

AI call 후 DB data가 변해도 해당 draft가 어떤 context에서 만들어졌는지 보존되어야 한다.

---

# 46. Revision semantics

기존 revision은 새 context를 다시 build한다.

이를 유지한다.

즉:

draft v1:

```text
context snapshot A
```

revision v2:

```text
context snapshot B
```

가 가능.

각 draft version은 자신이 실제 사용한 context snapshot을 저장한다.

이전 draft의 context를 덮어쓰지 않는다.

---

# 47. Approved draft immutable

기존 approved draft immutable contract 유지.

context snapshot도 write-once.

Approval 후 수정 금지.

---

# 48. Context preview API

Claude를 호출하지 않고 V1/V2 context를 확인할 read-only API 추가 권장.

예:

```text
GET /api/v1/coach/training-context
    ?date=2026-10-03
    &version=V2
```

default date:

athlete-local today.

이 endpoint는:

- DB read only
- Claude call 0
- Garmin call 0
- Intervals call 0

이어야 한다.

---

# 49. Preview API safety

Context 자체가 이미 sanitized해야 한다.

응답에:

- raw payload
- IDs
- GPS
- token

없어야 한다.

---

# 50. V1/V2 live comparison

Main PC에서 동일 날짜/동일 constraints로:

```text
V1 snapshot
V2 snapshot
```

을 생성.

비교:

```text
serialized size
top-level sections
recovery
thresholds
activity knowledge
quality-session knowledge
Intervals load context
coverage information
```

V2가 단순히 V1보다 크다는 이유로 성공으로 보지 않는다.

**더 많은 raw data가 아니라 더 많은 useful evidence**가 들어갔는지 확인한다.

---

# 51. Current live data expected in V2

2026-10-03 context 기준으로 대략 확인:

```text
supported activities = 5
analysis complete = 5
full samples = 5
Intervals matched = 5
fitness days = 90
recovery actual days = 17
```

실제 builder 결과를 DB 기준으로 검증한다.

hard-code 금지.

---

# 52. Sparse history warning via coverage

Claude prompt에 별도 natural-language warning을 hard-code할 필요는 없다.

`dataCoverage`가 숫자로 정확히 보여야 한다.

system prompt에는:

> Coverage counts describe how much evidence exists. Do not assume a 90-day window contains dense activity history.

정도의 일반 원칙 추가 가능.

---

# 53. Claude system prompt V2 update

기존 system prompt의 training principles와 safety contract는 유지한다.

추가할 핵심:

### Provenance

```text
GARMIN
= device/source measurement

RUNNING_AI
= deterministic derived evidence

INTERVALS
= external training-model enrichment
```

한 source를 무조건 다른 source보다 우위로 두지 않는다.

---

# 54. Missing data rule 강화

Claude에게:

```text
null = unknown/unavailable
```

기존 원칙 유지.

추가:

```text
absence of an activity or recovery reading is not proof that nothing happened or that recovery was poor.
```

---

# 55. Data fidelity guidance

Prompt에:

```text
sampleCompleteness = FULL
```

이면 full stored stream 기반.

DOWNSAMPLED/UNKNOWN이면 해당 sample-derived metric을 더 불확실하게 취급하도록 안내한다.

단 Spring이 metric을 수정하거나 score하지 않는다.

---

# 56. RunningAI-derived metric guidance

특히:

`speedHrDecouplingPercent`

는 RunningAI descriptive metric.

Garmin/Intervals official score가 아님을 prompt/documentation에서 명시.

---

# 57. Intervals terminology guidance

Prompt에서:

```text
CTL = calculated fitness
ATL = calculated fatigue
derivedForm = CTL - ATL
```

명확히 한다.

subjective `fatigue` field는 V2에 존재하지 않는다.

---

# 58. User feedback priority

기존 원칙 유지.

사용자의:

`painOrFatigueFeedback`

은 wearable/fitness model이 정상이어도 무시하면 안 된다.

예:

```text
wearable looks normal
but user reports heavy pain/fatigue
```

이면 Claude가 사용자 feedback을 반드시 고려해야 한다.

Spring이 workout type을 대신 고르지는 않는다.

---

# 59. No deterministic coaching policy

V2 Builder에서 금지:

```text
if ATL high → REST
if decoupling > 5 → EASY
if HRV down → no intervals
```

이런 rule을 추가하지 않는다.

Builder는 evidence 전달자다.

---

# 60. No candidate training types in V2

다시 강조:

V2에는:

`candidateTrainingTypes`

를 전달하지 않는다.

AI Coach가 workout type을 선택한다.

---

# 61. Existing V1 eval 유지

현재 Coach eval scenarios 전체 유지.

V1 regression 깨지면 안 된다.

---

# 62. V2 synthetic eval scenarios

V2 전용 synthetic scenarios 추가.

최소:

### A. Recent structured interval

최근 interval session:

- reps 존재
- HR progression
- speed CV

Claude가 최근 quality session을 인식하는지.

특정 다음 workout type을 강제하지 않는다.

---

# 63. V2 long run scenario

최근 long run evidence 존재.

Claude rationale가 해당 history를 인식할 수 있어야 한다.

---

# 64. V2 sparse-history scenario

```text
90-day window
activity count = 2
```

같은 context.

Claude가 이를 dense training history처럼 말하지 않는지 확인.

---

# 65. V2 low recovery coverage scenario

```text
recoveryWindowDays=28
recoveryDaysAvailable=3
```

Claude가 missing recovery를 invent하지 않는지.

---

# 66. V2 full evidence scenario

- threshold known
- recovery available
- recent intervals
- CTL/ATL available
- full samples

Claude가 각 source를 적어도 합리적으로 사용할 수 있는지.

단 특정 workout 결과를 강제하지 않는다.

---

# 67. V2 provenance conflict scenario

예:

```text
Garmin-derived / RunningAI evidence indicates HR rose
Intervals load is moderate
```

둘을 하나의 fake metric으로 합치거나 source를 혼동하지 않는지 확인.

---

# 68. V2 user-fatigue-overrides-data scenario

wearable/load evidence는 평범하지만:

```text
painOrFatigueFeedback = exhausted / pain
```

Claude가 이를 무시하지 않는 기존 invariant 유지.

---

# 69. Context builder tests

최소:

- 90-day cutoff
- future activity excluded
- future fitness excluded
- future recovery excluded
- same-day prior activity included
- activity order newest first
- max activity count
- FULL fidelity carried through
- interval group summary
- no reps/raw samples in context
- missing Intervals metrics
- missing analysis
- missing recovery
- exact CTL/ATL dates
- exact D-7/D-28 snapshot
- sparse coverage
- no candidateTrainingTypes

---

# 70. Snapshot serialization tests

확인:

same input → byte-identical JSON.

그리고:

```text
< 64 KiB
```

expected fixture.

아래 금지 field가 없는지 확인.

```text
latitude
longitude
externalActivityId
garminActivityId
intervalsActivityId
Authorization
API_KEY
```

---

# 71. Draft persistence tests

신규 draft:

```text
context_version = V2
context_snapshot != null
context_sha256 != null
```

확인.

hash:

stored snapshot과 다시 계산한 SHA-256 일치.

---

# 72. Revision persistence

v1 draft context hash A.

revision 후:

v2 draft context hash B.

각 row가 자신의 context를 보존.

이전 row 불변.

---

# 73. V1 compatibility persistence

Context version selector = V1일 때도 가능하면 새 draft에는:

```text
context_version = V1
context_snapshot = V1 snapshot
```

저장.

그래야 앞으로 모든 신규 draft가 audit 가능하다.

---

# 74. Existing drafts

V20 이전 existing draft:

```text
context_version = NULL
context_snapshot = NULL
```

허용.

억지 backfill 금지.

어떤 context였는지 증명할 수 없기 때문이다.

---

# 75. Architecture boundary

`WorkoutDraftService`는 여전히 다음에 의존하면 안 된다:

- Garmin client
- Intervals client
- publisher

Context builder만 사용.

기존 architecture test 업데이트.

Draft generation으로 외부 data sync가 발생하면 FAIL.

---

# 76. Live V2 context preview

자동 tests GREEN 후 Main PC 실제 DB로:

```text
GET training context V2
```

실행.

검증:

- Garmin network calls 0
- Intervals network calls 0
- Claude call 0

DB-only.

---

# 77. Live snapshot manual inspection

실제 JSON을 확인하되 개인 ID/GPS는 원래 없어야 한다.

확인:

```text
coverage correct
recent activities <= 5 current data
analysis evidence present
Intervals metrics present
fitness context present
Recovery context present
```

null도 사실대로 유지.

---

# 78. Snapshot size

실제 Main PC V2 context의:

```text
UTF-8 bytes
```

보고.

권장 목표:

`< 32 KiB`

hard guard:

`64 KiB`.

현재 activity 5개 기준이면 충분히 작아야 한다.

---

# 79. V1 vs V2 prompt diff

Prompt 자체에 secret/raw data가 없는 상태에서 structural diff 수행.

확인:

V2에서 새롭게 Claude가 아는 것:

```text
data coverage
actual structured intervals
analysis evidence
Intervals CTL/ATL/load
actual recent activity evidence
```

V1의 useful safety/recovery/constraints 정보는 사라지지 않아야 한다.

---

# 80. HUMAN GATE 전환 전

V2 구현 및 tests 완료 후에도 Main PC:

`RUNNING_AI_TRAINING_CONTEXT_VERSION`

은 아직 V1 유지.

먼저:

- V2 preview
- synthetic eval
- live Claude eval

을 수행한다.

---

# 81. Live Claude eval — publish 없음

Main PC의 실제 V2 context를 이용해 Claude를 한 번 호출한다.

목적:

**workout을 publish하는 게 아니라 context 사용 여부 검증.**

가능하면 draft generation을 실제 endpoint/service를 통해 수행.

외부 writes:

```text
Garmin 0
Intervals 0
Workout publish 0
```

local workout_draft INSERT는 허용.

---

# 82. Live V2 draft validation

생성 draft에 대해 확인:

- structured interval history를 필요 시 인식
- current CTL/ATL/form을 근거로 사용 가능
- recovery evidence를 무시하지 않음
- sparse activity count를 90일 고빈도 훈련으로 착각하지 않음
- null metric invent하지 않음
- user constraint 준수
- context provenance 혼동 없음

특정 workout type을 “정답”으로 강제하지 않는다.

---

# 83. V1 vs V2 live comparison

가능하면 동일 constraints로 V1과 V2 draft 각각 1개씩 생성한다.

둘 다 publish하지 않는다.

비교 항목:

```text
context evidence actually referenced
missing-data honesty
recent workout awareness
training load awareness
recovery awareness
rationale specificity
```

기계적인 winner score를 만들 필요는 없다.

V2가 context 정보를 실제로 사용하고 hallucination이 없는지 확인하는 것이 목적.

---

# 84. Live generated drafts 상태

비교용 draft는:

`DRAFT`

상태로 남겨도 된다.

승인하지 않는다.

publish하지 않는다.

필요하면 명확히 test/eval generated라고 result doc에 ID 없이 기록.

---

# 85. V2 activation gate

다음이 모두 GREEN이면 Main PC `.env`:

```text
RUNNING_AI_TRAINING_CONTEXT_VERSION=V2
```

설정.

서버 restart.

새 process에서 V2 선택 확인.

---

# 86. V2 activation 실패

다음 중 하나면 Main PC는 V1 유지:

- context missing critical evidence
- snapshot overflow
- eval regression
- Claude malformed behaviour clearly caused by V2 structure
- persistence bug
- future leakage
- provenance confusion

문제를 고치고 다시 검증.

V2를 억지로 활성화하지 않는다.

---

# 87. V2 activation 후 smoke

V2 활성화 후:

context preview.

그다음 필요하면 실제 draft 한 건 생성.

확인:

```text
context_version = V2
context snapshot stored
hash valid
Claude draft valid
```

publish 없음.

---

# 88. Publishing safety

Phase 전 과정:

```text
WORKOUT_PUBLISHING_ENABLED=false
WORKOUT_PUBLISHING_SCHEDULER_ENABLED=false
RUNNING_AI_DRAFT_PUBLISHING_ENABLED=false
RUNNINGAI_MCP_ENABLED=false
```

유지.

Legacy writer Disabled 유지.

---

# 89. Network budget

TrainingContext V2:

```text
Garmin calls = 0
Intervals calls = 0
```

Claude live eval만 local Claude CLI 호출.

Data source API는 호출하지 않는다.

---

# 90. No new backfill

Phase 6H-6 data 그대로 사용.

이번 Phase에서는:

- Garmin backfill 없음
- Recovery backfill 없음
- Intervals enrichment refresh 없음

---

# 91. No Analysis recalculation

TrainingContext build 중:

`RunningActivityAnalysisService.analyse()`

를 호출하지 않는다.

저장된 analysis만 사용.

분석이 없는 activity는 missing으로 표현.

---

# 92. No hidden filtering

V2 recentActivities에서 특정 결과가 “나빠 보여서” 제외하지 않는다.

선택 규칙:

```text
date range
max count
activity type support
```

같은 structural rule만 사용.

---

# 93. Data query efficiency

N+1 query를 피한다.

최근 activity 최대 8개에 대해:

각각 analysis/Intervals를 개별 query해도 작은 규모에서는 동작하지만 architecture상 batch read를 우선 고려한다.

단 과도한 repository refactor를 하지 않는다.

실제 query count를 test/profiling해 reasonable하게 유지.

---

# 94. No prompt injection from external text

Intervals activity name, Garmin activity name 등 external free text는 V2 context에 기본적으로 넣지 않는다.

필요한 training evidence는 typed metrics로 충분하다.

사용자가 직접 넣은:

`userFeedback`

`requestedGoal`

만 verbatim user text로 prompt에 전달.

---

# 95. Constraints 유지

기존:

```text
availableMinutes
environment
userFeedback
requestedGoal
painOrFatigueFeedback
```

전부 V2에서도 유지.

---

# 96. Workout validator compatibility

현재 validator가:

```text
context.date
context.athlete
```

를 사용한다.

공통 `CoachTrainingContext` contract로 V1/V2 모두 정상 동작하도록 수정.

validation policy 자체는 변경하지 않는다.

---

# 97. Claude response contract

이번 Phase는 **Claude output schema를 바꾸는 Phase가 아니다.**

기존:

- workout
- assessment
- rationale
- warnings
- segments

contract 유지.

변경 대상은 input context다.

---

# 98. Architecture document

작성:

`docs/architecture/training-context-v2.md`

포함:

```text
Garmin facts
       ↓
RunningAI Analysis
       +
Intervals Training Model
       +
Garmin Recovery
       ↓
TrainingContext V2
       ↓
Claude
```

각 field에:

- provenance
- date semantics
- missing semantics
- lookback
- compaction rule

기록.

---

# 99. Context schema example

실제 personal value가 아닌 synthetic example 추가.

예:

```json
{
  "contextVersion": "V2",
  "date": "2026-10-03",
  "dataCoverage": {
    "historyWindowDays": 90,
    "supportedActivityCount": 12,
    "fitnessDaysAvailable": 90,
    "recoveryDaysAvailable": 24
  }
}
```

실제 사용자 수치 그대로 architecture doc에 복사하지 않는다.

---

# 100. Migration

현재 V19까지 적용.

Context snapshot persistence 때문에:

`V20__add_workout_draft_context_snapshot.sql`

추가 예상.

기존 V1~V19 수정 금지.

H2 / PostgreSQL 17 / live Main DB 검증.

기존 draft rows 보존.

---

# 101. Full tests

현재 Spring baseline:

```text
1101
```

실행:

`gradlew clean test`

기대:

- count 증가
- failed 0
- skipped 0

PostgreSQL 17 full suite도 GREEN.

Python connector는 변경 없음:

`134 passed`

유지 확인.

---

# 102. Coach eval regression

기존 V1 Coach eval scenarios 유지.

V2 synthetic scenarios 추가.

Live Claude eval은 unit/full regression과 별도 보고.

---

# 103. Context version property tests

확인:

```text
unset → V1
V1 → V1
V2 → V2
invalid → startup fail
```

---

# 104. DB snapshot privacy test

stored `context_snapshot`에도 다음 없음:

```text
GPS
external IDs
API key
Authorization
raw payload
```

---

# 105. Context SHA test

같은 deterministic snapshot:

같은 SHA-256.

metric 하나 변경:

hash 달라짐.

---

# 106. Live DB migration

자동 테스트 GREEN 이후 V20 live DB 적용.

기존 workout drafts row count/state 불변.

승인된 draft가 있다면 status/content 변경 없음.

---

# 107. Final Main PC state

성공 시:

```text
RUNNING_AI_TRAINING_CONTEXT_VERSION=V2
```

Main PC local `.env`.

Source default는 V1 유지 가능.

Publishing switches 4개는 false.

---

# 108. Phase 완료 조건

모두 만족해야 한다.

```text
TrainingContext V1 preserved ✅
TrainingContext V2 implemented ✅

V2 DB-only ✅
No future leakage ✅
No raw samples ✅
No GPS / external IDs ✅

90-day coverage represented ✅
Sparse history visible ✅

Garmin recovery included ✅
Intervals CTL/ATL/form included ✅
RunningAI Analysis included ✅
Structured interval history included ✅

candidateTrainingTypes absent from V2 ✅

Context deterministic ✅
Context < 64 KiB ✅

Draft context snapshot persisted ✅
Context version persisted ✅
Context SHA persisted ✅

Revision keeps per-version context ✅

V1 synthetic eval GREEN ✅
V2 synthetic eval GREEN ✅
Live V2 preview GREEN ✅
Live Claude V2 eval GREEN ✅

Main PC context version = V2 ✅

Garmin API calls = 0
Intervals API calls = 0
External workout writes = 0

Historical backfill NOT_RUN
Publishing disabled

Spring GREEN
PostgreSQL GREEN
Python GREEN
working tree clean
origin/main in sync
```

완료 후:

`PHASE_6H_7_TRAINING_CONTEXT_V2_READY`

보고하고 멈춘다.

---

# 109. 절대 자동으로 넘어가지 않을 것

Phase 완료 후 다음을 자동 실행하지 않는다:

- draft approval
- publish-preview
- Intervals publish
- Garmin sync/push
- scheduler enable
- MCP enable

다음 단계는 별도 승인 후 진행한다.

---

# 110. 최종 보고 (§110)

1. baseline SHA
2. final SHA
3. migration
4. V1 preserved 여부
5. common context contract
6. context selector
7. source default context version
8. Main PC active context version
9. history lookback
10. max recent activities
11. actual live recent activity count
12. dataCoverage live values
13. recovery coverage
14. fitness coverage
15. current CTL source date/age
16. current ATL source date/age
17. D-7 fitness availability
18. D-28 fitness availability
19. structured interval detection
20. last structured interval date availability
21. long-run detection
22. RunningAI analysis fields included
23. interval summary fields included
24. Intervals fields included
25. candidateTrainingTypes in V2 여부
26. raw sample presence check
27. GPS presence check
28. external ID presence check
29. future-leak tests
30. live V2 serialized byte size
31. hard byte limit
32. deterministic serialization test
33. workout_draft context columns
34. existing draft migration result
35. new draft snapshot persistence
36. context SHA validation
37. revision per-version context test
38. context preview API
39. preview Garmin calls
40. preview Intervals calls
41. V1 eval result
42. V2 synthetic eval result
43. live Claude eval result
44. V1/V2 live structural comparison
45. live V2 draft result
46. draft approval status
47. workout publish calls
48. Garmin API calls
49. Intervals API calls
50. external workout writes
51. publishing switches
52. historical backfill status
53. Spring H2 tests
54. Spring PostgreSQL tests
55. Python tests
56. commits
57. push
58. working tree
59. known limitations
60. recommended next phase
