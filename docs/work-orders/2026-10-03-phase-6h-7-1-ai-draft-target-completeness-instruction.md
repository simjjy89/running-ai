# RunningAI Phase 6H-7.1 — AI Draft Target Completeness

## 0. 목적

현재 실제 Controlled Publish까지 성공했다.

검증 완료 흐름:

```text
TrainingContext V2
→ Claude Coach
→ Draft
→ Revision
→ APPROVED
→ Publish Preview
→ Intervals.icu
→ read-back verified=true
```

실제 Draft #7은:

```text
Warm Up 12m                       ← numeric target 없음
Main 5m 4:45-5:00/km Pace
Rest 2m                           ← numeric target 없음
Main 5m 4:45-5:00/km Pace
Rest 2m                           ← numeric target 없음
Main 5m 4:45-5:00/km Pace
Rest 2m                           ← numeric target 없음
Cool Down 10m                     ← numeric target 없음
```

으로 publish되었다.

이번 Phase 목표는 **AI가 생성한 Running workout의 각 스텝을 Garmin에서 가능한 한 바로 따라갈 수 있도록 device-usable target을 완성하는 것**이다.

목표 예:

```text
Warm Up 12m
→ 65-78% LTHR

Main 5m
→ 4:45-5:00/km

Recovery 2m
→ 65-75% LTHR

...

Cool Down 10m
→ 65-78% LTHR
```

실제 athlete 수치를 코드나 테스트에 하드코딩하지 않는다.

---

# 1. 작업 위치와 baseline

Repository:

```text
C:\running-ai-github
```

remote baseline:

```text
b95a4a0
```

또는 작업 시작 시 현재 `origin/main` 최신 SHA.

중요:

현재 로컬에 다음 스크립트가 수정/추가돼 있을 수 있다.

```text
scripts/windows/publish-approved-draft-controlled.ps1
```

이 파일을 reset/clean/delete/overwrite하지 않는다.

작업 시작 시:

```text
git status
git log -1 --oneline
git rev-parse origin/main
```

을 기록한다.

`git reset --hard`, `git clean -fd`, force push 금지.

---

# 2. Work order

본 지시서를:

```text
docs/work-orders/
2026-10-03-phase-6h-7-1-ai-draft-target-completeness-instruction.md
```

에 저장한다.

결과:

```text
docs/work-orders/
2026-10-03-phase-6h-7-1-ai-draft-target-completeness-result.md
```

---

# 3. 현재 확인된 코드 사실

현재 `HeartRateTarget`:

```java
record HeartRateTarget(
    int minPercentLthr,
    int maxPercentLthr,
    int minBpm,
    int maxBpm
)
```

이미 존재한다.

`IntervalsWorkoutRenderer`도 이미:

```text
65-78% LTHR hr=1s
```

형식을 지원한다.

따라서 Intervals renderer에 새로운 HR 문법을 만들지 않는다.

---

# 4. 현재 AI Draft의 문제

`WorkoutDraftSegment`는 현재:

```text
paceSecondsPerKmFast
paceSecondsPerKmSlow

heartRateBpmMin
heartRateBpmMax

treadmillSpeed...
incline...
```

만 가지고 있다.

`%LTHR` field가 없다.

---

# 5. 현재 publish 문제

`ApprovedWorkoutDraftPublishabilityValidator`는 절대 bpm target을:

```text
UNPUBLISHABLE
```

로 처리한다.

이 동작은 유지한다.

절대 bpm을 `%LTHR`로 publish 시점에 자동 변환하지 않는다.

---

# 6. 현재 mapper 문제

`WorkoutDraftStructuredWorkoutMapper`는 현재:

```text
pace → PaceTarget

heartRateTarget → 항상 null
```

이다.

따라서 renderer가 `%LTHR`를 지원해도 AI Draft에서는 해당 경로를 사용할 수 없다.

---

# 7. 현재 recovery 문제

현재 repeat block:

```text
repetitions
recoveryDurationMinutes
```

만 저장한다.

mapper의 recovery step은:

```text
SegmentType.REST
IntensityClass.NONE
PrimaryTargetType.NONE
```

으로 생성된다.

따라서 recovery에도 Garmin target이 없다.

이번 Phase에서 이것까지 해결한다.

---

# 8. 핵심 설계 원칙

Spring이 훈련을 다시 결정하지 않는다.

Spring이 다음을 바꾸면 안 된다.

```text
workout type
duration
repetition count
hard/easy decision
main pace
session purpose
```

Target completeness는:

```text
coach가 결정한 session
→ lossless device representation
```

의 문제로 다룬다.

---

# 9. 기존 Draft 호환성

이미 DB에는 기존 Draft JSON이 존재한다.

특히 Draft #7은 승인/Publish까지 완료됐다.

기존 draft를 migration하거나 다시 serialize해서 수정하지 않는다.

기존 Draft #7 publication도 절대 다시 publish하지 않는다.

---

# 10. 새 primary target

`WorkoutDraftSegment`에 명시적 primary target을 추가한다.

권장:

```kotlin
val primaryTargetType: PrimaryTargetType? = null
```

기존 persisted draft에는 field가 없으므로 default `null`.

legacy draft는 기존 규칙으로 해석 가능해야 한다.

예:

```text
pace 존재 → PACE
그 외      → 기존 qualitative behavior
```

새 V2 Coach response에서는 명시적으로 채운다.

---

# 11. %LTHR Draft fields

추가:

```kotlin
val heartRatePercentLthrMin: Int? = null
val heartRatePercentLthrMax: Int? = null
```

기존:

```text
heartRateBpmMin
heartRateBpmMax
```

은 삭제하지 않는다.

이유:

기존 persisted JSON / V1 compatibility.

하지만 새 V2 Claude response contract에서는 absolute bpm을 사용하지 않는다.

---

# 12. Absolute BPM 정책

기존 absolute BPM target은 계속:

```text
publish 불가
```

로 유지한다.

절대 다음 변환을 하지 않는다.

```text
approved bpm
→ 현재 LTHR로 %
```

승인 시점과 publish 시점의 profile이 달라질 수 있기 때문이다.

---

# 13. HeartRateTarget compatibility

현재 `HeartRateTarget`의 renderer는 `%LTHR`만 사용하지만 record는 BPM도 primitive로 요구한다.

Draft mapper가 `%LTHR`만 lossless하게 전달할 수 있도록 정리한다.

권장 최소 변경:

```java
public record HeartRateTarget(
    int minPercentLthr,
    int maxPercentLthr,
    Integer minBpm,
    Integer maxBpm
)
```

기존 deterministic target pipeline에서는 BPM을 계속 채운다.

AI Draft mapper에서는:

```text
minPercentLthr = approved Draft value
maxPercentLthr = approved Draft value

minBpm = null
maxBpm = null
```

가능하게 한다.

`IntervalsWorkoutRenderer`는 기존처럼 percent만 사용한다.

기존 workout-intensity-target API에서는 BPM 값이 이전과 동일하게 나와야 한다.

---

# 14. Recovery model 개선

기존:

```text
recoveryDurationMinutes
```

만으로는 recovery target을 표현할 수 없다.

신규 V2 Draft에서는 recovery를 명시적으로 표현할 수 있게 한다.

권장 신규 타입:

```kotlin
data class WorkoutDraftRecovery(
    val durationMinutes: Int,
    val intensity: IntensityClass,
    val description: String? = null,

    val primaryTargetType: PrimaryTargetType,

    val paceSecondsPerKmFast: Int? = null,
    val paceSecondsPerKmSlow: Int? = null,

    val heartRatePercentLthrMin: Int? = null,
    val heartRatePercentLthrMax: Int? = null,

    val treadmillSpeedKphMin: Double? = null,
    val treadmillSpeedKphMax: Double? = null,
    val inclinePercentMin: Double? = null,
    val inclinePercentMax: Double? = null,
)
```

그리고:

```kotlin
WorkoutDraftSegment.recovery: WorkoutDraftRecovery? = null
```

추가.

---

# 15. Legacy recovery 유지

기존:

```text
recoveryDurationMinutes
```

는 삭제하지 않는다.

기존 persisted draft compatibility를 위해 유지한다.

새 V2 response contract에서는 신규 `recovery` object를 사용한다.

둘이 동시에 존재하면 fail closed.

```text
recovery != null
AND
recoveryDurationMinutes != null

→ invalid response
```

---

# 16. Duration arithmetic

신규 repeat block:

```text
repetitions ×
(
    work duration
    +
    recovery.duration
)
```

으로 계산한다.

기존 legacy Draft는 계속:

```text
repetitions ×
(
    durationMinutes
    +
    recoveryDurationMinutes
)
```

를 사용한다.

기존 Draft #7:

```text
43분
```

계산이 절대 달라지면 안 된다.

---

# 17. Recovery after final repetition

현재 RunningAI contract는 마지막 repetition 뒤에도 recovery가 붙는다.

이번 Phase에서 이 semantics를 바꾸지 않는다.

즉:

```text
3 ×
Main 5m
Recovery 2m
```

은:

```text
Main
Recovery
Main
Recovery
Main
Recovery
```

이다.

Prompt와 description이 이 구조와 일치해야 한다.

---

# 18. New V2 Claude response contract

새 segment 예:

```json
{
  "type": "WARM_UP",
  "durationMinutes": 12,
  "intensity": "VERY_EASY",
  "primaryTargetType": "HEART_RATE",
  "heartRatePercentLthrMin": 65,
  "heartRatePercentLthrMax": 78
}
```

수치는 synthetic example.

---

# 19. Main quality example

```json
{
  "type": "MAIN",
  "durationMinutes": 5,
  "intensity": "HARD",
  "primaryTargetType": "PACE",
  "paceSecondsPerKmFast": 285,
  "paceSecondsPerKmSlow": 300
}
```

수치는 synthetic.

---

# 20. Recovery example

```json
{
  "repetitions": 3,
  "recovery": {
    "durationMinutes": 2,
    "intensity": "VERY_EASY",
    "primaryTargetType": "HEART_RATE",
    "heartRatePercentLthrMin": 65,
    "heartRatePercentLthrMax": 75,
    "description": "Easy jog recovery"
  }
}
```

synthetic example.

---

# 21. Cool-down example

```json
{
  "type": "COOL_DOWN",
  "durationMinutes": 10,
  "intensity": "VERY_EASY",
  "primaryTargetType": "HEART_RATE",
  "heartRatePercentLthrMin": 65,
  "heartRatePercentLthrMax": 78
}
```

---

# 22. Claude target policy

TrainingContext에 LTHR가 존재하는 경우:

```text
WARM_UP
COOL_DOWN
easy running recovery
```

에는 `%LTHR` target을 적극적으로 사용한다.

특히 easy/very-easy 구간을 absolute BPM으로 출력하지 않는다.

---

# 23. Existing RunningAI HR heuristic

기존 `RunningIntensityTargetPolicy`에는 이미:

```text
VERY_EASY
65–78% LTHR

EASY
75–85% LTHR
```

가 있다.

이는 RunningAI의 기존 deterministic scheduling heuristic이다.

새 prompt에서 easy/very-easy HR target guidance가 필요하면 이 기존 정책과 일치시킨다.

새로운 임의 zone 체계를 만들지 않는다.

---

# 24. Main quality target

THRESHOLD / INTERVAL / TEMPO의 MAIN은 coach가 session purpose에 맞춰:

```text
PACE
or
HEART_RATE
```

중 하나를 primary target으로 선택할 수 있다.

두 target을 모두 primary처럼 보내지 않는다.

---

# 25. One physiological primary target

publishable AI Draft에서는 한 step의 primary physiological target은 하나다.

예:

```text
PACE
```

또는:

```text
HEART_RATE
```

이다.

treadmill speed/incline은 Garmin-safe cue이므로 primary physiological target과 함께 존재할 수 있다.

---

# 26. Silent target dropping 금지

예를 들어:

```text
primaryTargetType=PACE

pace populated
%LTHR populated
```

인데 renderer가 HR을 버리는 형태는 허용하지 않는다.

AI Draft publish path에서 승인된 numeric target을 조용히 버리지 않는다.

validator에서 ambiguous target을 fail closed한다.

---

# 27. PrimaryTargetType validation

규칙:

```text
PACE
→ complete pace pair required

HEART_RATE
→ complete %LTHR pair required

QUALITATIVE
→ physiological numeric target 없어야 함

NONE
→ physiological numeric target 없어야 함
```

legacy `primaryTargetType=null`만 기존 compatibility inference 허용.

---

# 28. %LTHR validation

기술적 validation:

```text
both or neither
positive
min <= max
```

비현실적 malformed value를 막는 넓은 sanity ceiling은 가능하나 지나치게 좁은 physiological range를 hard-code하지 않는다.

---

# 29. BPM + %LTHR ambiguity

새 V2 Draft에서:

```text
heartRateBpm*
+
heartRatePercentLthr*
```

동시 사용 금지.

fail closed.

---

# 30. Recovery validation

`recovery`가 존재하면:

```text
durationMinutes > 0
intensity valid
primary target internally consistent
```

해야 한다.

`repetitions`가 없는데 recovery object만 존재하는 것도 거부한다.

---

# 31. Recovery type

easy-jog recovery이면 numeric target을 제공한다.

passive standing/walking rest를 coach가 의도한 경우:

```text
primaryTargetType=NONE
```

이 가능하다.

따라서 모든 recovery에 무조건 HR target을 강제로 넣지는 않는다.

다만 easy running recovery인데 target을 빼는 것은 V2 prompt contract에서 허용하지 않는다.

---

# 32. Target completeness

TrainingContext V2에 usable threshold가 있을 때 running session은 가능한 한:

```text
WARM_UP
MAIN
running recovery
COOL_DOWN
```

모두 Garmin에서 usable target을 가져야 한다.

이것은 **target completeness**다.

---

# 33. Missing threshold fallback

LTHR와 threshold pace가 모두 없다면:

```text
QUALITATIVE
```

fallback 허용.

없는 threshold를 만들어내지 않는다.

---

# 34. Pace-only athlete

threshold pace만 있고 LTHR가 없으면:

```text
PACE
```

target 사용 가능.

warm-up/cool-down도 pace target을 사용할 수 있다.

없는 LTHR target을 만들지 않는다.

---

# 35. LTHR-only athlete

LTHR만 있으면:

```text
HEART_RATE
```

primary target 사용.

pace를 만들지 않는다.

---

# 36. V2 device-usability validation

V2 context + running workout + threshold available일 때:

numeric target이 완전히 빠진 running segment를 검출한다.

Spring이 target을 자동으로 채우지는 않는다.

잘못된 Claude response는 validation 실패로 처리한다.

---

# 37. V1 compatibility

기존 TrainingContext V1 동작을 깨지 않는다.

V1 synthetic eval 20개 전부 유지.

기존 fixture를 대량 수정해서 테스트를 억지로 통과시키지 않는다.

---

# 38. WorkoutDraftValidator

현재 validator는:

```text
date
athlete
```

정보를 사용한다.

필요하다면:

```text
CoachTrainingContext
```

전체를 받아 V1/V2 target completeness 정책을 구분할 수 있게 한다.

V1에는 새 completeness requirement를 소급 적용하지 않는다.

V2에만 적용한다.

---

# 39. Mapper

신규 Draft HR target:

```text
WorkoutDraft
→ HeartRateTarget(%LTHR)
→ StructuredWorkoutStep
```

로 lossless mapping.

`HEART_RATE`이면:

```text
PrimaryTargetType.HEART_RATE
```

사용.

---

# 40. Recovery mapper

신규 `WorkoutDraftRecovery`가 있으면:

```text
StructuredWorkoutStep(
    type = REST,
    duration = recovery.durationMinutes,
    intensity = recovery.intensity,
    primary = recovery.primaryTargetType,
    ...
)
```

로 mapping.

기존 legacy `recoveryDurationMinutes`이면 현재 동작:

```text
NONE target
```

유지.

---

# 41. Renderer 변경 최소화

`IntervalsWorkoutRenderer`의 `%LTHR` syntax는 이미 구현되어 있다.

새 syntax를 만들지 않는다.

필요한 변경은 compatibility/comment/test 정도만 허용.

---

# 42. Expected renderer output

신규 Draft example:

```text
- Warm Up 12m 65-78% LTHR hr=1s
- Main 5m 4:45-5:00/km Pace
- Rest 2m 65-75% LTHR hr=1s
- Main 5m 4:45-5:00/km Pace
- Rest 2m 65-75% LTHR hr=1s
- Main 5m 4:45-5:00/km Pace
- Rest 2m 65-75% LTHR hr=1s
- Cool Down 10m 65-78% LTHR hr=1s
```

수치는 synthetic fixture다.

---

# 43. Treadmill

treadmill target은 계속 cue로 표현한다.

예:

```text
label
→ Garmin-safe treadmill cue
→ duration
→ primary target
```

기존 renderer order:

```text
label → cue → duration → target
```

절대 변경하지 않는다.

---

# 44. Treadmill + HR

다음 조합은 가능해야 한다.

```text
treadmill speed/incline cue
+
%LTHR primary target
```

즉 watch에서 speed/incline cue와 HR target 정보가 함께 손실 없이 전달되는 renderer contract를 보존한다.

---

# 45. Response DTO

`WorkoutDraftResponse.SegmentResponse`에서도 신규 target 정보를 볼 수 있어야 한다.

최소:

```text
primaryTargetType
heartRatePercentLthrMin
heartRatePercentLthrMax
recovery
```

를 노출한다.

사용자가 승인 전에 정확히 무엇이 Garmin에 전달될지 확인할 수 있어야 한다.

---

# 46. Draft persistence

`segments`는 JSON column이므로 additive nullable fields만으로 해결 가능하다면 DB migration을 만들지 않는다.

기존 JSON row deserialize를 반드시 테스트한다.

migration이 정말 필요하지 않으면:

```text
DB migration = NONE
```

으로 결과에 기록.

---

# 47. Existing Draft #7 compatibility regression

Draft #7과 동일한 legacy shape fixture:

```text
recoveryDurationMinutes=2
primaryTargetType missing
%LTHR missing
```

를 deserialize/map/render했을 때 기존 output이 변하지 않아야 한다.

즉 기존 publication semantics를 깨지 않는다.

---

# 48. Existing absolute BPM regression

absolute bpm만 가진 기존 Draft는 여전히:

```text
UNPUBLISHABLE
```

이어야 한다.

자동 변환 금지.

---

# 49. New HR publishability test

신규 `%LTHR` Draft:

```text
publishable=true
```

이어야 한다.

---

# 50. New recovery publishability test

nested recovery가 `%LTHR` target을 가진 repeat block은:

```text
work + targeted recovery
```

로 정확히 expansion되어야 한다.

---

# 51. Exact step-count test

예:

```text
warm-up
3 × (main + recovery)
cool-down
```

이면:

```text
8 steps
```

유지.

---

# 52. Exact duration test

synthetic:

```text
12
+
3 × (5 + 2)
+
10
=
43
```

정확히 유지.

---

# 53. Determinism

같은 Draft:

```text
→ same StructuredWorkout
→ same renderedWorkoutText
```

보장.

---

# 54. No target invention during publish

publish mapper가 다음을 수행하면 FAIL:

```text
athlete profile lookup
current LTHR lookup
threshold pace lookup
target recalculation
```

mapper는 승인된 Draft만 lossless하게 옮긴다.

---

# 55. No Garmin / Intervals call

unit/integration implementation 작업 중:

```text
Garmin external calls = 0
Intervals external calls = 0
```

---

# 56. No auto publish

이번 Phase 동안:

```text
RUNNING_AI_DRAFT_PUBLISHING_ENABLED=false
WORKOUT_PUBLISHING_ENABLED=false
WORKOUT_PUBLISHING_SCHEDULER_ENABLED=false
RUNNINGAI_MCP_ENABLED=false
```

유지.

실제 external publish를 자동 수행하지 않는다.

---

# 57. Current published Draft 보호

이미 성공한:

```text
Draft #7
approvalId 1
remote event
publication record
```

을 수정/삭제/re-publish하지 않는다.

이것은 historical evidence다.

---

# 58. Parser tests

Claude response parser가:

```text
primaryTargetType
%LTHR range
nested recovery
```

를 정상 parse해야 한다.

old response shape도 계속 parse 가능해야 한다.

---

# 59. Prompt tests

Claude system/response contract에:

```text
absolute bpm 대신 %LTHR
running target completeness
nested recovery
one physiological primary target
```

가 명시됐는지 테스트.

---

# 60. V2 synthetic coach eval

6H-7 known limitation이었던 V2 synthetic eval suite도 이번에 보완한다.

최소 scenarios:

```text
LTHR + pace available
LTHR only
pace only
no thresholds
interval with easy-jog recovery
passive recovery
treadmill
```

특정 workout type을 강제하지 않는다.

target contract 준수 여부를 검증한다.

---

# 61. Target coverage invariant

full-profile running scenario에서:

```text
warm-up numeric target
main numeric target
running recovery numeric target
cool-down numeric target
```

을 확인한다.

---

# 62. No hallucinated threshold

threshold null scenario에서:

```text
%LTHR generated → FAIL
pace generated → FAIL
```

---

# 63. No absolute bpm in new V2

새 V2 synthetic/live response에서:

```text
heartRateBpmMin
heartRateBpmMax
```

가 populated되면 FAIL.

---

# 64. Existing 5B target regression

기존:

```text
WorkoutIntensityTargetService
RunningIntensityTargetPolicy
```

tests 모두 그대로 PASS.

`HeartRateTarget` BPM nullable change가 기존 API JSON을 바꾸면 안 된다.

---

# 65. Renderer regression

기존 Pace:

```text
4:45-5:00/km Pace
```

유지.

기존 HR:

```text
65-78% LTHR hr=1s
```

유지.

기존 treadmill cue 유지.

---

# 66. API regression

기존:

```text
WorkoutDraft APIs
Approval APIs
Publish Preview APIs
Publish APIs
```

계약을 깨지 않는다.

신규 nullable fields 추가만 허용.

---

# 67. Persistence regression

신규 target fields 포함 Draft 저장/조회 round-trip.

legacy Draft JSON 저장/조회 round-trip.

revision 시 target fields가 version별로 정확히 보존.

---

# 68. Publish-preview integration

synthetic approved Draft를 이용해:

```text
publish-preview
```

결과가:

```text
publishable=true
externalWriteRequired=true
expectedOutcome=PUBLISH
```

이고 모든 step target이 text에 존재하는지 검증한다.

외부 Intervals 호출 0.

---

# 69. Live Claude validation

모든 automated test GREEN 후에만 수행.

실제 Main DB의 V2 context를 사용할 수 있다.

다만 외부 publish하지 않는다.

가능하면 persistence 없는 LiveClaudeCoachEval 방식으로 검증한다.

목표:

```text
warm-up target
quality main target
running recovery target
cool-down target
```

확인.

---

# 70. Live validation에서 실제 Draft 저장 시

기존 API밖에 방법이 없어 Draft가 저장된다면:

```text
status=DRAFT
```

로만 남긴다.

approve하지 않는다.

publish하지 않는다.

---

# 71. Garmin device verification

이번 Phase 자동화 범위에서는 actual Garmin device display를 성공으로 가정하지 않는다.

코드/renderer/readback이 GREEN인 후 실제 사용자가 다음 workout에서 장치 화면으로 확인한다.

---

# 72. Outdated documentation

현재 `IntervalsWorkoutRenderer` comment 등에서 `%LTHR` Garmin device status가 실제 project evidence와 다르게 적혀 있다면, repository의 확인 가능한 테스트/결과 문서를 기준으로 정확히 수정한다.

증거가 없으면 추측해서 “device verified”라고 바꾸지 않는다.

---

# 73. Tests

현재 6H-7 baseline:

```text
Spring H2          1144 passed
Spring PostgreSQL  1144 passed
Python              134 passed
```

Phase 후:

```text
Spring H2          all pass
Spring PostgreSQL  all pass
Python             134 pass
```

Python 변경 없더라도 최종 regression에서 확인한다.

---

# 74. PostgreSQL

schema migration이 없다면 기존 V20 그대로.

live Main DB schema 변경 없음.

---

# 75. Security

`.env` 출력 금지.

API key/token 출력 금지.

actual Garmin/Intervals IDs를 테스트 fixture에 복사하지 않는다.

---

# 76. Architecture document

기존 문서 업데이트 또는 신규:

```text
docs/architecture/ai-draft-targets.md
```

권장.

설명:

```text
Claude
 ↓
WorkoutDraft target
 ↓
Approval
 ↓
lossless mapper
 ↓
StructuredWorkout target
 ↓
Intervals renderer
 ↓
Garmin
```

---

# 77. Target provenance

문서에서 구분:

```text
pace target
= coach-prescribed

%LTHR target
= coach-prescribed relative target

treadmill cue
= approved Draft operational target
```

publish 시 재계산 없음.

---

# 78. Definition of Done

```text
WorkoutDraft supports explicit primary target
WorkoutDraft supports %LTHR
absolute bpm legacy compatibility retained
absolute bpm still unpublishable

new recovery model supports target
legacy recoveryDurationMinutes retained

HeartRateTarget supports percent-only draft mapping
existing deterministic BPM API unchanged

warm-up target supported
main target supported
recovery target supported
cool-down target supported

Draft → StructuredWorkout lossless
StructuredWorkout → renderer deterministic

legacy Draft regression
new %LTHR Draft regression
nested recovery regression
V1 regression
V2 target eval

publish-preview full-target PASS

Garmin calls 0
Intervals calls 0
external writes 0

Draft #7 untouched
publishing switches false

H2 GREEN
PostgreSQL GREEN
Python GREEN

docs complete
commits pushed
working tree safe
```

---

# 79. 권장 commit 분리

권장:

```text
feat: add relative heart-rate targets to AI workout drafts

feat: add targeted recovery blocks to AI workout drafts

test: cover AI draft target completeness and legacy compatibility

docs: document AI draft device targets
```

실제 repository 상태에 맞춰 조정 가능.

---

# 80. 완료 보고

최종 보고에 반드시 포함:

```text
baseline SHA
final SHA

DB migration 여부

legacy Draft compatibility
Draft #7 untouched 여부

new segment target fields
new recovery representation

HeartRateTarget change

absolute bpm policy
%LTHR policy

primary target rules

warm-up target test
main target test
recovery target test
cool-down target test

legacy recovery test
duration arithmetic test
step count test

pace renderer regression
HR renderer regression
treadmill renderer regression

V1 eval
V2 target eval

live Claude validation 여부/result

publish-preview result

Garmin API calls
Intervals API calls
external writes

publishing switch final values

H2 tests
PostgreSQL tests
Python tests

known limitations
next recommended step
```

완료 marker:

```text
PHASE_6H_7_1_AI_DRAFT_TARGET_COMPLETENESS_READY
```

여기서 멈춘다.

실제 Draft approve/publish/Garmin 전송은 자동 수행하지 않는다.