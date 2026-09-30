Phase 5C-0을 진행해줘.

CLAUDE.md와 running-ai-dev skill을 따라.

작업지시서:
docs/work-orders/2026-09-30-legacy-structured-workout-investigation-instruction.md

C:\running-ai legacy 프로젝트의 Intervals.icu / Garmin structured workout 경로를
READ ONLY로 조사해.

특히 scripts/intervals-structured-workout.ps1,
Get-GarminSafeStepCue(), pace target, %LTHR + hr=1s,
step cue, treadmill speed/incline 표현,
Intervals publish/update/idempotency,
Garmin 전달 경로와 수동 sync 필요 여부를 확인해.

C:\running-ai에는 어떤 변경도 하지 말고,
실제 Intervals/Garmin publish나 sync도 실행하지 마.

현재 Spring 5B-2 TargetedWorkoutPrescription과 legacy renderer input을
field-by-field mapping하고 capability matrix를 만들어.

각 legacy 기능을 REUSE_AS_IS / PORT_LOGIC / REWRITE / DROP으로 분류하고,
Phase 5C-1 이후 권장 architecture와 단계 분할을 제안해.

이번 Phase에서는 production Java code나 migration을 구현하지 마.

결과 문서를 작성하고 secrets 노출 여부와 diff를 확인한 뒤
문서만 commit/push해.

Phase 5C-1은 시작하지 마.
# RunningAI Phase 5C-0
## Legacy Intervals.icu / Garmin Structured Workout Pipeline Investigation

## 1. 목적

Phase 5C 구현 전에 기존 RunningAI legacy 파이프라인을 조사한다.

이번 단계의 목적은 새 renderer를 만드는 것이 아니다.

목표는 다음 질문에 답하는 것이다.

```text
1. 기존 Intervals.icu workout은 어떤 형식으로 생성되는가?
2. 어떤 코드가 실제 payload/rendering을 담당하는가?
3. PACE target은 어떤 syntax로 전달되는가?
4. HR / %LTHR target은 어떤 syntax로 전달되는가?
5. Garmin step cue는 어떤 규칙으로 생성되는가?
6. treadmill speed/incline 정보는 Garmin까지 어떻게 전달되는가?
7. Intervals.icu publish API는 어디서 호출되는가?
8. Garmin 전달은 RunningAI가 직접 하는가,
   아니면 Intervals.icu → Garmin Connect sync에 의존하는가?
9. 현재 Spring 5B-2 output과 legacy input 사이에 무엇이 부족한가?
10. 어떤 legacy 코드를 재사용/이식/폐기해야 하는가?
```

---

# 2. 현재 Spring 기준점

latest known commit:

```text
46255ff
```

현재 Java regression:

```text
314 passed
```

현재 Spring pipeline:

```text
Garmin Activity
      ↓
TrainingLoadService
      ↓
TrainingStateService
      ↓
TrainingDecisionContextService
      ↓
WorkoutRecommendationService
      ↓
WorkoutPrescriptionService
      ↓
WorkoutIntensityTargetService
      ↓
TargetedWorkoutPrescription
```

5B-2에서 이미 확보한 데이터:

```text
intent
exact duration

WARM_UP / MAIN / COOL_DOWN

qualitative intensity

pace target
%LTHR heart-rate target

treadmill speed km/h
treadmill incline

PACE → HR → QUALITATIVE fallback
```

즉 Phase 5C의 문제는:

```text
"어떤 운동을 할 것인가?"
```

가 아니라:

```text
"이 prescription을 Intervals.icu/Garmin이 이해하는
structured workout으로 어떻게 변환할 것인가?"
```

이다.

---

# 3. 조사 대상 경로

legacy project:

```text
C:\running-ai
```

new Spring project:

```text
C:\running-ai-github
```

중요:

```text
C:\running-ai
```

는 READ ONLY로 조사한다.

절대:

```text
수정
format
cleanup
rename
git reset
commit
dependency update
실제 workout publish
```

하지 않는다.

---

# 4. 우선 조사할 legacy 파일

최소 다음을 찾는다.

```text
scripts/intervals-structured-workout.ps1
```

그리고 해당 파일이 호출하거나 의존하는:

```text
Intervals.icu API client
workout builder
renderer
publisher
scheduler
Garmin sync 관련 script
config
fixture
regression test
```

를 추적한다.

파일명이 다르더라도 실제 dependency graph를 따라간다.

---

# 5. Get-GarminSafeStepCue

특히 다음 함수의 현재 구현을 조사한다.

```text
Get-GarminSafeStepCue()
```

확인할 것:

```text
input
output
escaping
길이 제한
허용 문자
speed formatting
incline formatting
null 처리
pace-only 처리
HR-only 처리
```

이미 알려진 예:

```text
12.0 km/h | 경사 0.5-1%
```

→

```text
12.0kph Incline0.5-1pct
```

이 동작이 현재 코드에서 정확히 어떻게 만들어지는지 기록한다.

---

# 6. Step Rendering Order

현재 legacy structured workout의 step 렌더링 순서를 확인한다.

특히 과거 변경된:

```text
label
→ cue
→ measure
→ target
```

순서가 실제 코드에 남아 있는지 검증한다.

과거 방식:

```text
label
→ measure
→ pace
→ note
```

가 남아 있는 다른 코드 경로가 없는지도 찾는다.

---

# 7. Intervals.icu Workout Builder Syntax

실제 생성되는 workout text/payload를 조사한다.

최소 다음 intent/segment 표현을 확인한다.

```text
warm-up
steady/easy main
cool-down
rest
```

QUALITY/interval은 이번 구현 범위가 아니더라도 legacy syntax는 참고용으로 조사 가능하다.

---

# 8. Pace Target

PACE target이 실제 Intervals.icu workout에 어떤 형식으로 들어가는지 기록한다.

예상만 하지 않는다.

실제 코드와 fixture/test를 근거로 확인한다.

다음 구분을 기록한다.

```text
pace range
single pace
duration + pace
unit
min/max direction
rounding
```

---

# 9. HR Target

기존에 성공했던 HR target 경로를 조사한다.

특히:

```text
%LTHR 기반
hr=1s
```

방식이 실제 어디서 생성되는지 확인한다.

다음 내용을 정확히 기록한다.

```text
Intervals syntax
percentage representation
range representation
hr=1s 역할
Garmin 전달 시 결과
```

---

# 10. Pace + HR 동시 존재

legacy renderer가:

```text
PACE
HEART_RATE
```

둘 중 하나만 선택하는지,

둘 다 payload에 넣을 수 있는지,

또는 workout type에 따라 하나만 선택하는지 확인한다.

이는 5B-2의:

```text
primaryTargetType
```

과 연결되어야 한다.

---

# 11. Treadmill Speed

legacy에서:

```text
km/h
```

가 실제 target으로 전달되는지,

아니면:

```text
pace target
+
cue text
```

로만 전달되는지 명확히 구분한다.

Garmin이 실제 speed field를 지원한다고 가정하지 않는다.

---

# 12. Incline

incline 또한:

```text
structured target
```

인지:

```text
step cue text
```

인지 확인한다.

기존 Garmin 265에서 실제 표시된 방식과 코드 구현을 연결해서 기록한다.

---

# 13. Garmin Display Compatibility

기존에 실제 Garmin 265에서 확인된 것과 코드상 구현을 구분한다.

결과 문서에 두 column을 둔다.

| Feature | Code supports | Garmin 265 physically verified |
|---|---:|---:|
| Pace target | YES | YES |
| HR target | YES | YES |
| Speed cue | YES | YES |
| Incline cue | YES | YES |
| Workout notes | YES | NO |
| Segment notes | YES | NO |

실제 evidence가 없는 것은 VERIFIED로 표시하지 않는다.

---

# 14. Workout-level Notes

과거 확인된:

```text
workout-level Notes
```

가 Intervals.icu에는 보이지만 Garmin 시계에는 표시되지 않았던 경로를 코드에서 확인한다.

---

# 15. segment.notes

동일하게:

```text
segment.notes
```

가 Garmin까지 전달되지 않았던 경로를 기록한다.

향후 새 renderer에서 이 방식을 다시 사용하지 않도록 문서화한다.

---

# 16. Cue mechanism

Garmin에서 표시된 실제 mechanism이:

```text
Garmin step cue
```

인지 확인한다.

Intervals.icu Workout Builder에서 어떤 field/text syntax가 Garmin step cue로 변환되는지도 기록한다.

---

# 17. Publish Path

Intervals.icu publish가 어디서 이뤄지는지 추적한다.

다음을 구분한다.

```text
renderer
publisher
HTTP client
authentication/config
response validation
retry
idempotency
update existing workout
create new workout
```

---

# 18. API Endpoint 조사

legacy 코드에 사용되는 Intervals.icu endpoint를 기록한다.

하지만:

```text
API key/token
athlete secret
Authorization header value
```

는 결과 문서에 복사하지 않는다.

secret 이름/config key 이름 정도만 기록한다.

---

# 19. Authentication

다음만 문서화:

```text
어디서 credential을 읽는가
환경변수인지
config file인지
secret store인지
```

credential actual value는 절대 출력하지 않는다.

---

# 20. Workout Identity

같은 날짜 workout을 다시 생성할 때:

```text
create
update
overwrite
duplicate
```

중 어떤 방식인지 조사한다.

특히 idempotency가 존재하는지 확인한다.

---

# 21. Date / Timezone

Intervals workout 날짜가:

```text
Asia/Seoul
UTC
local machine timezone
```

중 무엇을 기준으로 만들어지는지 확인한다.

Spring 쪽 athlete timezone semantics와 비교한다.

---

# 22. Garmin Delivery Path

매우 중요.

legacy에서 최종 Garmin 전달이:

```text
RunningAI
→ Garmin API
```

직접인지,

아니면:

```text
RunningAI
→ Intervals.icu
→ Garmin Connect
→ Garmin 265
```

인지 코드 근거로 확정한다.

현재 알려진 동작을 추측으로만 기록하지 않는다.

---

# 23. Manual Garmin Sync Requirement

과거에는 Intervals.icu에서 workout 생성/수정 후:

```text
Garmin Connect 앱 sync
```

가 필요했던 적이 있다.

현재 legacy 코드에 이를 자동화하려는 코드가 존재하는지 조사한다.

다음 상태 중 하나로 분류:

```text
AUTOMATIC
PARTIALLY_AUTOMATIC
MANUAL_REQUIRED
UNKNOWN
```

---

# 24. Existing Garmin Direct Sync Experiments

legacy에 다음과 관련된 코드가 있으면 조사한다.

```text
Garmin workout upload
Garmin Connect unofficial API
sync trigger
device queue
calendar/workout push
```

단 실행하지 않는다.

---

# 25. Existing Tests

structured workout 관련 모든 테스트/fixture를 찾는다.

분류:

```text
renderer unit test
payload regression
Intervals API test
Garmin-safe text test
native regression
smoke test
```

---

# 26. Regression IDs

기존 native regression/test ID가 있다면 기록한다.

예:

```text
structured workout regression
PACE regression
HR regression
Garmin cue regression
```

실제 이름 그대로 기록한다.

---

# 27. Sample Payload

secret 제거 후 실제 legacy input/output sample 하나 이상을 정리한다.

예:

```text
Input
- duration
- pace
- speed
- incline
```

→

```text
Rendered Intervals workout
```

→

```text
Expected Garmin representation
```

---

# 28. Spring ↔ Legacy Mapping

5B-2 DTO와 legacy renderer input을 field-by-field 비교한다.

표 작성:

| Spring TargetedWorkoutPrescription | Legacy input | Mapping |
|---|---|---|
| intent | ? | DIRECT / TRANSFORM / MISSING |
| segment.type | ? | |
| durationMinutes | ? | |
| intensityClass | ? | |
| paceTarget.fastSecondsPerKm | ? | |
| paceTarget.slowSecondsPerKm | ? | |
| HR %LTHR | ? | |
| treadmill min/max kph | ? | |
| incline min/max | ? | |

---

# 29. Missing Data

새 Spring 쪽에 추가로 필요한 필드가 있는지 확인한다.

반대로 legacy 쪽에서 이제 필요 없는 필드도 식별한다.

분류:

```text
REQUIRED
OPTIONAL
LEGACY_ONLY
DEPRECATED
```

---

# 30. Internal Structured Workout Model 필요성

다음 두 설계를 비교한다.

### Option A

```text
TargetedWorkoutPrescription
        ↓
IntervalsRenderer
```

### Option B

```text
TargetedWorkoutPrescription
        ↓
StructuredWorkout
        ↓
IntervalsRenderer
        ↓
future Garmin Renderer
```

5C-0 결과에서 어느 쪽을 추천하는지 근거를 적는다.

기본적으로 renderer-specific syntax가 domain DTO에 새어들어간다면 Option B를 권장한다.

---

# 31. Domain과 Renderer 분리

Spring domain에 이런 문자열이 들어가면 안 된다.

```text
hr=1s
Incline0.5-1pct
Intervals.icu-specific syntax
Garmin cue escaping
```

이것들은 renderer/adapter layer에 있어야 한다.

---

# 32. Renderer Candidate Architecture

조사 결과를 바탕으로 제안 구조를 작성한다.

예:

```text
TargetedWorkoutPrescription
        ↓
StructuredWorkoutMapper
        ↓
StructuredWorkout
        ↓
IntervalsWorkoutRenderer
        ↓
IntervalsWorkoutPublisher
```

필요하면:

```text
GarminSafeCueFormatter
```

별도 분리.

---

# 33. Reuse Strategy

legacy component마다 다음 중 하나를 부여한다.

```text
REUSE_AS_IS
PORT_LOGIC
REWRITE
DROP
KEEP_LEGACY_ONLY
```

---

# 34. PowerShell → Java Migration

PowerShell 구현을 그대로 Spring production runtime에서 실행하는 방식과 Java로 이식하는 방식을 비교한다.

원칙적으로 최종 Spring architecture는 Java 구현을 선호한다.

하지만 기존 regex/syntax/escaping 규칙은 regression fixture로 재사용 가능하다.

---

# 35. Golden Master 전략

legacy renderer에서 이미 Garmin에서 성공한 output이 있다면:

```text
input fixture
→ expected rendered output
```

을 golden master fixture로 확보할 수 있는지 판단한다.

secret/user-specific data는 제거한다.

---

# 36. 실제 Publish 금지

이번 Phase에서 절대:

```text
Intervals.icu workout 생성
실제 workout 수정
Garmin sync
Garmin workout 삭제
```

하지 않는다.

READ ONLY investigation이다.

---

# 37. Legacy repo 수정 금지

```text
C:\running-ai
```

에는 어떤 파일도 쓰지 않는다.

로그/테스트 실행 때문에 runtime state가 바뀔 수 있는 명령도 피한다.

static inspection 우선.

---

# 38. New Repo 변경 범위

```text
C:\running-ai-github
```

에서는 문서만 추가/수정한다.

production Java code 구현 금지.

migration 추가 금지.

---

# 39. 결과 문서

작성:

```text
docs/work-orders/
2026-09-30-legacy-structured-workout-investigation-instruction.md

docs/work-orders/
2026-09-30-legacy-structured-workout-investigation.md
```

---

# 40. 결과 문서 필수 Section

## A. Executive Summary

legacy pipeline을 10줄 이내로 설명.

---

## B. Legacy Flow

정확한 call chain:

```text
input
→ builder
→ renderer
→ publisher
→ Intervals.icu
→ Garmin
```

파일/function 명 포함.

---

## C. File Inventory

| File | Responsibility | Keep? |
|---|---|---|

---

## D. Intervals Syntax

다음 각각 실제 예시:

```text
warm-up
easy/main
cool-down
pace target
HR target
step cue
```

---

## E. Garmin Compatibility

code vs physically verified matrix.

---

## F. Cue Formatting

`Get-GarminSafeStepCue()` 상세.

---

## G. Publish Flow

endpoint/auth/idempotency/update behavior.

---

## H. Spring Mapping

5B-2 → legacy mapping table.

---

## I. Gaps

Spring에 부족한 것 / legacy에 부족한 것.

---

## J. Recommended Architecture

5C-1/5C-2/5C-3 제안.

---

## K. Migration Strategy

각 legacy 기능:

```text
REUSE
PORT
REWRITE
DROP
```

---

## L. Risks

예:

```text
Intervals undocumented syntax
Garmin cue limitations
manual sync
timezone
duplicate workout
legacy dependency
secret handling
```

---

## M. Proposed Phase 5C Breakdown

구체적인 다음 단계.

---

# 41. Capability Matrix

반드시 작성:

| Capability | Legacy | Spring | Next Action |
|---|---:|---:|---|
| Exact duration | | | |
| Warm-up/main/cooldown | | | |
| Pace target | | | |
| %LTHR | | | |
| Treadmill speed | | | |
| Incline | | | |
| Garmin-safe cue | | | |
| Intervals rendering | | | |
| Intervals publish | | | |
| Garmin delivery | | | |
| Idempotent update | | | |

---

# 42. Evidence Level

각 중요한 발견에:

```text
CODE_CONFIRMED
TEST_CONFIRMED
LIVE_PREVIOUSLY_CONFIRMED
ASSUMED
UNKNOWN
```

중 하나를 붙인다.

ASSUMED를 가능한 한 남기지 않는다.

---

# 43. Search 범위

다음 키워드를 전체 legacy repo에서 찾는다.

```text
Intervals
intervals.icu
structured
workout
Garmin
cue
notes
segment.notes
hr=1s
LTHR
pace
incline
kph
Get-GarminSafeStepCue
sync
calendar
```

---

# 44. Secrets

검색 결과에 token/key가 보이면 실제 값을 결과 문서에 기록하지 않는다.

결과:

```text
SECRET_PRESENT_IN_LEGACY_CONFIG
```

처럼 위치/종류만 기록.

---

# 45. Test Execution

READ ONLY라고 확신할 수 있는 renderer unit test/static regression만 선택적으로 실행할 수 있다.

다음 가능성이 있으면 실행하지 않는다.

```text
Intervals publish
Garmin sync
file mutation
scheduler trigger
runtime state mutation
```

---

# 46. Spring Test

production code를 변경하지 않으므로 Java regression 전체 실행은 필수가 아니다.

단 문서 외에 예상치 못한 code diff가 생기면:

```text
STOP
```

하고 원인을 조사한다.

---

# 47. Git Diff

최종 diff는 원칙적으로:

```text
docs/work-orders/...
README optional
```

만 있어야 한다.

README는 정말 필요한 경우만 수정한다.

---

# 48. Commit

조사 결과 문서를 commit/push한다.

권장 commit:

```text
docs: investigate legacy structured workout pipeline
```

---

# 49. Phase 5C-1 자동 시작 금지

결과가 나와도 구현을 시작하지 않는다.

먼저 보고한다.

---

# 50. 예상 Next Phase

기본 예상은:

```text
Phase 5C-1
Structured Workout Domain Model

Phase 5C-2
Intervals.icu Renderer

Phase 5C-3
Intervals.icu Publisher

Phase 5C-4
Garmin End-to-End Validation
```

하지만 5C-0 조사 결과에 따라 조정 가능하다.

---

# 51. 완료 보고 형식

## Legacy Flow

```text
entry:
builder:
renderer:
publisher:
Intervals:
Garmin:
```

## Key Files

```text
file:
responsibility:
```

## Pace

```text
syntax:
rounding:
Garmin verified:
```

## HR / LTHR

```text
syntax:
hr=1s:
Garmin verified:
```

## Treadmill

```text
speed representation:
incline representation:
cue:
Garmin verified:
```

## Notes / Cue

```text
workout notes:
segment.notes:
step cue:
```

## Publish

```text
endpoint:
create/update:
idempotency:
timezone:
auth source:
```

## Garmin Delivery

```text
path:
manual sync required:
direct Garmin upload:
```

## Spring Mapping

```text
direct:
transform:
missing:
legacy-only:
```

## Recommended Architecture

```text
TargetedWorkoutPrescription
→ ...
```

## Reuse Decisions

```text
REUSE_AS_IS:
PORT_LOGIC:
REWRITE:
DROP:
```

## Risks

```text
...
```

## Tests / Evidence

```text
static inspection:
tests executed:
live calls:
```

## Git

```text
branch:
commit:
push:
```

## Recommended Phase 5C-1

```text
scope:
```

---

# 52. Definition of Done

```text
[ ] legacy project inspected read-only
[ ] renderer entry point identified
[ ] dependency/call graph identified
[ ] Get-GarminSafeStepCue inspected
[ ] step rendering order confirmed
[ ] pace syntax confirmed
[ ] HR/%LTHR syntax confirmed
[ ] hr=1s behavior identified
[ ] speed representation confirmed
[ ] incline representation confirmed
[ ] notes limitation documented
[ ] step cue mechanism documented
[ ] publish endpoint/path identified
[ ] auth source identified without secret exposure
[ ] create/update/idempotency behavior identified
[ ] timezone behavior identified
[ ] Garmin delivery path confirmed
[ ] manual sync requirement classified
[ ] renderer tests/fixtures identified
[ ] Spring ↔ legacy mapping completed
[ ] capability matrix completed
[ ] missing fields identified
[ ] reuse/port/rewrite/drop decisions made
[ ] recommended 5C architecture written
[ ] no production workout published
[ ] no Garmin sync triggered
[ ] no legacy files modified
[ ] no Spring production code implemented
[ ] result document committed
[ ] pushed to origin/main
```

# 핵심 원칙

**이번 Phase에서는 코드를 새로 만들지 않는다.**

먼저 기존에 실제 Garmin까지 성공했던 규칙을 정확하게 보존한다.

특히:

```text
PACE target
%LTHR
hr=1s
Garmin step cue
treadmill speed/incline cue
```

를 추측으로 재구현하지 않는다.

**Legacy의 성공한 동작을 specification으로 만든 다음 Java로 옮긴다.**
