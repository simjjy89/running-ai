Phase 5C-2를 진행해줘.

기준 commit은 5c097fe야.
origin/main을 pull하고 working tree가 clean인지 확인해.

CLAUDE.md와 관련 project skill을 따르고,
작업지시서는:

docs/work-orders/2026-10-01-phase-5c-2-intervals-workout-renderer.md

에 저장해.

이번 Phase는
StructuredWorkout
→ IntervalsWorkoutRenderer
→ GarminSafeCueFormatter
까지만 구현해.

legacy renderer의 pace/HR/cue 규칙을 필요한 만큼만 port하고,
특히 Garmin 실기기에서 확인됐던
cue → duration/target ordering을 regression test로 고정해.

Intervals HTTP/publisher/idempotency/API 호출,
DB migration, legacy production script 수정은 하지 마.

pace Garmin mismatch는 UNRESOLVED,
%LTHR Garmin device 지원은 ASSUMED 상태를 유지해.

구현 → golden/unit tests → 전체 Java regression
→ 문서화 → diff/secrets 검사 → commit → push까지 진행하고
5C-3는 시작하지 마.
# RunningAI Phase 5C-2
## IntervalsWorkoutRenderer & GarminSafeCueFormatter

## 0. 기준 상태

현재 기준 commit:

```text
5c097fe
```

Phase 5C-1 완료 상태:

```text
TargetedWorkoutPrescription
        ↓
StructuredWorkoutMapper
        ↓
StructuredWorkout
```

구현 완료.

현재 `StructuredWorkout`은 provider-neutral이며 다음 의미를 보존한다.

```text
StructuredWorkout
- asOfDate
- intent
- totalDurationMinutes
- steps

StructuredWorkoutStep
- type
- durationMinutes
- intensityClass
- description
- primaryTargetType
- paceTarget
- heartRateTarget
- treadmillTarget
```

Canonical units:

```text
pace       = seconds / km
HR         = %LTHR + bpm
speed      = km/h
incline    = percent
```

Provider-specific syntax:

```text
Intervals.icu syntax = 없음
Garmin syntax        = 없음
cue formatting       = 없음
publishing           = 없음
```

---

# 1. 이번 Phase 목표

다음 경로만 구현한다.

```text
StructuredWorkout
        ↓
IntervalsWorkoutRenderer
        ↓
GarminSafeCueFormatter
        ↓
Intervals.icu Workout Builder text
```

이번 Phase의 output은:

```text
Intervals.icu에 보낼 수 있는 rendered workout text
```

까지만이다.

아직 실제 Intervals.icu API 호출은 하지 않는다.

---

# 2. 이번 Phase에서 절대 하지 않을 것

다음은 Phase 5C-2 범위 밖이다.

```text
Intervals API HTTP client
Intervals API authentication
workout publish
update/delete
marker idempotency
Spring Controller/API
scheduler
Garmin Connect 호출
Garmin device validation
DB migration
DB entity
legacy PowerShell 수정
```

즉:

```text
render only
```

이다.

---

# 3. 시작 전 Git 확인

외부 PC에서:

```powershell
cd <running-ai repository>

git status
git switch main
git pull --ff-only origin main
git log -5 --oneline
```

확인:

```text
5c097fe 포함
working tree clean
```

기존 사용자 변경사항이 있으면 덮어쓰지 않는다.

---

# 4. Project rules

다음을 따른다.

```text
CLAUDE.md
관련 .claude/skills/*
```

특히 workout / integration 관련 skill이 있다면 읽는다.

Phase 5C-0 분석 문서를 다시 처음부터 조사하지 않는다.

다음만 source of truth로 사용한다.

```text
Phase 5C-0 legacy analysis
Phase 5C-1 StructuredWorkout implementation
현재 legacy renderer source
```

---

# 5. Work Order 저장

작업 시작 시 이 지시서를 저장한다.

```text
docs/work-orders/
2026-10-01-phase-5c-2-intervals-workout-renderer.md
```

완료 보고 역시 repository convention에 맞게 기록한다.

---

# 6. 목표 Architecture

최종적으로 다음 구조가 되어야 한다.

```text
TargetedWorkoutPrescription
        ↓
StructuredWorkoutMapper
        ↓
StructuredWorkout
        ↓
IntervalsWorkoutRenderer
        ↓
GarminSafeCueFormatter
        ↓
Rendered Intervals Workout
```

중요:

```text
StructuredWorkout
```

은 앞으로도 Intervals/Garmin을 몰라야 한다.

Provider-specific knowledge는 5C-2 renderer 계층에서 처음 등장한다.

---

# 7. Legacy reference

Legacy reference implementation:

```text
intervals-structured-workout.ps1
```

필요하면 관련 helper/function도 확인한다.

하지만 legacy PowerShell 전체 구조를 Java로 그대로 복사하지 않는다.

다음만 port한다.

```text
실제 workout semantics에 필요한 rendering logic
pace target conversion
HR target conversion
Garmin-safe cue normalization
cue ordering
```

다음은 port하지 않는다.

```text
PowerShell 구조 자체
환경변수 wiring
HTTP
retry
publish
marker state
command channel
Apple Watch mode
ntfy callback
cross-training legacy branch
```

---

# 8. 핵심 Legacy 발견사항

Phase 5C-0에서 확인됨.

## 8.1 Pace

Legacy에서는 pace가 structured target으로 렌더링된다.

Intervals server에는 반영됐으나 일부 Garmin device에서:

```text
목표 없음
```

으로 보인 사례가 있었다.

원인은 아직 확정되지 않았다.

따라서:

```text
legacy pace syntax는 동일하게 port
```

하되,

```text
Garmin에서 정상 표시됨
```

이라고 가정하지 않는다.

---

## 8.2 Heart Rate

Legacy 정책:

```text
absolute bpm target은 Garmin target으로 사용하지 않음
%LTHR + hr=1s
```

방식.

Intervals server readback에서 text preservation은 확인됐지만 Garmin device 표시까지 repository evidence로 입증되지 않았다.

따라서:

```text
renderer behavior = 구현
device support = ASSUMED
```

상태를 유지한다.

---

## 8.3 Treadmill

Legacy treadmill speed/incline:

```text
structured target이 아님
```

Garmin watch step 화면 표시용 cue text다.

예:

```text
12.0 km/h | 경사 0.5-1%
```

→

```text
12.0kph Incline0.5-1pct
```

---

## 8.4 Cue ordering

실기기 관찰에서 매우 중요.

다음 ordering:

```text
label
cue
duration / measure
target
```

이어야 Garmin에서 cue가 표시됐다.

이 ordering은 regression test로 고정한다.

---

# 9. IntervalsWorkoutRenderer

새 renderer의 책임:

```text
StructuredWorkout
→ deterministic Intervals Workout Builder text
```

예시 인터페이스:

```java
String render(StructuredWorkout workout)
```

또는 repository convention에 맞는 result object.

가능하면 처음에는 단순한 immutable result를 사용한다.

과도한 renderer abstraction hierarchy는 만들지 않는다.

---

# 10. Deterministic rendering

같은 `StructuredWorkout`은 항상 정확히 같은 output을 생성해야 한다.

다음이 실행마다 달라지면 안 된다.

```text
spacing
line order
decimal formatting
token order
newline
cue normalization
```

Golden test가 가능해야 한다.

---

# 11. Locale independent

숫자 렌더링은 OS locale의 영향을 받지 않아야 한다.

잘못된 예:

```text
12,0kph
```

정상:

```text
12.0kph
```

Decimal separator는 항상:

```text
.
```

를 사용한다.

---

# 12. Newline policy

Rendered output의 newline policy를 명확하게 정한다.

권장:

```text
\n
```

canonical newline을 사용한다.

Windows 환경이라고 `\r\n`에 의존하지 않는다.

Golden test가 OS마다 깨지지 않도록 한다.

---

# 13. Step rendering

각 step은 최소한 다음 semantic 순서를 가진다.

```text
1. step label
2. optional Garmin-safe cue
3. measure/duration
4. structured target
```

실제 Intervals syntax에 맞게 같은 logical ordering을 보장한다.

중요:

```text
cue token은 duration/target 뒤로 보내지 않는다.
```

---

# 14. Step label

기존:

```text
step type
description
intensity class
```

중 legacy renderer가 실제로 사용하는 의미를 확인한다.

불필요하게 긴 label을 새로 만들지 않는다.

사용자 description을 무조건 cue로 사용하지 않는다.

Garmin cue와 human-readable description의 책임을 분리한다.

---

# 15. GarminSafeCueFormatter

별도 formatter를 구현한다.

예:

```java
GarminSafeCueFormatter
```

책임:

```text
treadmill numeric semantics
→ Garmin-friendly step cue
```

---

# 16. Cue formatter input

가능하면 formatter는:

```text
TreadmillTarget
```

또는 필요한 numeric values를 받는다.

이미 렌더링된 자유 문자열을 받아 다시 파싱하지 않는다.

잘못된 구조:

```text
"12.0 km/h | 경사 1%"
→ parsing
→ Garmin cue
```

권장:

```text
speedKph = 12.0
inclinePercent = 1.0
→ Garmin cue
```

---

# 17. Speed cue

Legacy behavior를 기반으로:

```text
12.0 km/h
```

→

```text
12.0kph
```

와 같은 형태를 만든다.

실제 legacy의 decimal normalization 규칙을 확인하고 그대로 port한다.

추측해서 새로운 포맷을 만들지 않는다.

---

# 18. Incline cue

예:

```text
1.0%
```

→

```text
Incline1pct
```

range라면:

```text
0.5 ~ 1.0%
```

→ legacy 규칙에 따라:

```text
Incline0.5-1pct
```

같은 형태.

정확한 decimal trimming은 legacy 함수에 맞춘다.

---

# 19. Decimal normalization

예:

```text
12.00 → 12
12.50 → 12.5
1.00 → 1
0.50 → 0.5
```

또는 legacy가 `.0`을 유지한다면 그대로 따른다.

중요한 건:

```text
legacy actual behavior를 test로 고정
```

하는 것이다.

직감으로 새 규칙을 만들지 않는다.

---

# 20. ASCII-safe

Garmin-safe cue는 legacy 규칙을 따라 ASCII-friendly하게 유지한다.

예:

```text
경사
%
km/h
~
```

같은 문자를 그대로 사용하지 않는다.

렌더 결과는 예:

```text
12.0kph Incline1pct
```

같은 형태다.

---

# 21. Cue length

Legacy에 length limit/truncation logic이 존재한다면 정확히 확인한다.

있다면 port한다.

없다면 임의의 Garmin 글자 수 제한을 새로 만들지 않는다.

이번 Phase에서 확인되지 않은 Garmin device limitation을 추측하지 않는다.

---

# 22. Cue 적용 대상

Cue는 기본적으로:

```text
treadmill numeric instruction이 존재하는 step
```

에만 생성한다.

다음에는 불필요하게 cue를 생성하지 않는다.

```text
normal outdoor run
HR-only step
pace-only outdoor step
cooldown without treadmill target
```

---

# 23. Pace target rendering

기존 canonical:

```text
seconds/km
```

을 legacy Intervals target syntax로 변환한다.

예:

```text
290 sec/km
→ 4:50/km
```

정확한 Intervals syntax는 legacy source에서 확인한다.

---

# 24. Pace range

예:

```text
lower/upper target
```

이 존재하면 두 경계를 lossless하게 렌더링한다.

pace는 속도와 값 방향이 반대일 수 있으므로:

```text
faster / slower
lower / upper
```

의 의미를 잘못 뒤집지 않는다.

기존 `PaceTarget` 의미를 먼저 확인한다.

---

# 25. Pace conversion test

다음 같은 boundary를 테스트한다.

```text
300 sec/km → 5:00/km
270 sec/km → 4:30/km
```

range도 검증한다.

가능하면 기존 legacy fixture 값과 동일한 test case를 사용한다.

---

# 26. Pace target known issue

Golden output이 legacy syntax와 동일하더라도 다음 상태를 유지한다.

```text
INTERVALS_RENDERED = verified
GARMIN_DEVICE_TARGET = unresolved
```

문서에 명확히 남긴다.

5C-2 완료 보고에서:

```text
pace target Garmin device verified
```

라고 쓰지 않는다.

---

# 27. HeartRate target rendering

현재 domain:

```text
HeartRateTarget
```

의 실제 구조를 확인한다.

Legacy에서 사용하는 `%LTHR` semantics를 정확히 port한다.

---

# 28. %LTHR

예:

```text
90-95 %LTHR
```

가 legacy에서 어떤 token으로 render되는지 source로 확인한다.

그리고 필요한:

```text
hr=1s
```

directive가 어느 위치에 들어가는지도 그대로 port한다.

---

# 29. HR token ordering

Legacy가:

```text
hr=1s
```

를 step level에 넣는지 workout level에 넣는지 실제 source를 확인한다.

추측하지 않는다.

Golden test에서 exact text를 검증한다.

---

# 30. Absolute bpm

Phase 5C-0에 따라 absolute bpm Garmin target은 legacy에서 의도적으로 미지원이었다.

현재 `HeartRateTarget`이 bpm semantics도 표현할 수 있다면:

```text
renderer가 silent하게 %LTHR로 바꾸면 안 된다.
```

기존 legacy 정책과 현재 domain 의미를 검토한다.

가능한 선택:

```text
explicit unsupported exception
또는
non-Garmin structured representation
```

중 현재 architecture에 맞는 방식.

중요:

```text
bpm → %LTHR 자동 변환 금지
```

---

# 31. PrimaryTargetType

`primaryTargetType`은 renderer가 target 우선순위를 결정하는 데 사용할 수 있다.

다만 한 step에:

```text
paceTarget
heartRateTarget
treadmillTarget
```

이 여러 개 존재할 수 있으므로 현재 domain invariant를 먼저 확인한다.

임의로 하나를 삭제하지 않는다.

---

# 32. Treadmill target + pace target

트레드밀 step에:

```text
treadmill speed/incline
+
pace structured target
```

둘 다 있다면:

```text
Garmin-safe cue
+
Intervals target
```

둘 다 보존한다.

예 logical ordering:

```text
Work
12.0kph Incline1pct
10m
@5:00/km
```

정확한 실제 syntax는 legacy에 맞춘다.

---

# 33. Description

`description`이 존재한다고 해서 raw description 전체를 Garmin cue로 변환하지 않는다.

Description은 human semantic content다.

Cue formatter는 Garmin device rendering에 필요한 최소 treadmill information만 책임진다.

---

# 34. Intent / workout metadata

`StructuredWorkout.intent`, `asOfDate`, `totalDurationMinutes` 중 legacy output에 실제 필요한 값만 사용한다.

모든 domain field를 억지로 text로 출력하지 않는다.

Renderer output의 목적은:

```text
Intervals Workout Builder workout
```

이다.

---

# 35. Total duration

`totalDurationMinutes`는 renderer가 다시 계산하지 않는다.

필요하면 consistency check 정도만 할 수 있다.

하지만 renderer가:

```text
step duration 합산
→ prescription 값 overwrite
```

하지 않는다.

---

# 36. No training logic

Renderer는 다음을 계산하지 않는다.

```text
threshold pace
training zone
recommended HR
workout intensity
recovery duration
incline recommendation
```

이미 domain에서 결정된 값을 표현만 한다.

---

# 37. Output object

가능하면 renderer output을 단순 String으로 시작해도 된다.

단 future publisher가 metadata를 필요로 한다면 작은 immutable DTO는 허용한다.

예:

```java
RenderedIntervalsWorkout(
    String workoutText
)
```

하지만 다음은 금지:

```text
HTTP payload
API URL
auth header
remote event id
publish status
```

---

# 38. Golden tests

이번 Phase에서 가장 중요한 테스트 방식이다.

Legacy-equivalent input fixture를 만들고:

```text
StructuredWorkout
→ exact expected text
```

를 assert한다.

부분 contains assertion만 쓰지 않는다.

가능하면:

```java
assertThat(actual).isEqualTo(expected);
```

수준의 exact output test를 둔다.

---

# 39. Golden fixture categories

최소 다음 fixture를 만든다.

### A. Easy pace run

```text
warmup
pace work
cooldown
```

---

### B. Pace interval

```text
warmup
work
recovery
work
recovery
cooldown
```

pace target 포함.

---

### C. HR workout

```text
%LTHR
hr=1s
```

legacy rendering 확인.

---

### D. Treadmill workout

```text
speed
incline
cue
```

---

### E. Treadmill + structured target

cue와 target이 동시에 존재.

---

### F. Mixed workout

pace + HR + treadmill semantics가 서로 다른 step에 존재.

---

# 40. Cue ordering regression

반드시 별도 test를 둔다.

Test 이름이 의미를 드러내게 한다.

예:

```text
rendersGarminCueBeforeDurationAndTarget
```

검증:

```text
cue index
<
duration index
<
target index
```

또는 exact golden output.

이 regression은 향후 절대 쉽게 깨지면 안 된다.

---

# 41. GarminSafeCueFormatter unit tests

최소:

```text
speed only
incline only
speed + incline
incline range
decimal normalization
null/absent fields
```

를 테스트한다.

---

# 42. Empty cue

treadmill target이 없거나 표시 가능한 numeric information이 없으면:

```text
empty / Optional.empty
```

등 project convention에 맞는 값을 반환한다.

공백 line을 임의로 추가하지 않는다.

---

# 43. Invalid numeric value

Domain이 이미 validation한다면 중복하지 않는다.

하지만 formatter가:

```text
NaN
Infinity
negative impossible incline
```

같은 impossible state를 받을 수 있다면 silent rendering하지 않는다.

---

# 44. Target precedence

한 step에 복수 target이 존재할 때 legacy가 특정 precedence를 사용한다면 source에서 확인한다.

예:

```text
PACE
HR
```

둘 다 있을 때 하나만 render하는지, primaryTargetType을 따르는지 확인.

추측하지 않는다.

이를 test로 고정한다.

---

# 45. Legacy equivalence

가능한 범위에서 legacy renderer의 대표 input/output fixture와 Java renderer output을 비교한다.

PowerShell을 runtime dependency로 만들지는 않는다.

필요하면 legacy output을 golden fixture로 수동 고정한다.

---

# 46. Legacy production code 수정 금지

다음은 reference only:

```text
intervals-structured-workout.ps1
```

수정하지 않는다.

새 Java renderer가 기존 legacy를 대체하기 전까지 parallel implementation 상태를 유지한다.

---

# 47. Package boundary

권장 개념:

```text
workout/
  domain/
  application/
  integration/
    intervals/
```

실제 repository convention을 따른다.

`IntervalsWorkoutRenderer`와 `GarminSafeCueFormatter`는 provider/integration 계층에 위치시키는 것이 자연스럽다.

Domain package에 넣지 않는다.

---

# 48. Naming

가능하면 명확한 이름을 사용한다.

추천:

```text
IntervalsWorkoutRenderer
GarminSafeCueFormatter
RenderedIntervalsWorkout
```

기존 naming convention이 있다면 그것을 우선한다.

---

# 49. No API calls in tests

Renderer tests에서:

```text
network
Intervals API
Garmin
DB
```

를 사용하지 않는다.

순수 unit test여야 한다.

---

# 50. Regression

전체 Java test 실행:

```powershell
cd server
.\gradlew.bat clean test
```

모두 PASS해야 한다.

현재 기준:

```text
325 / 325
```

이다.

새 test 추가 후 총 개수 증가 예상.

---

# 51. Python

이번 Phase는 Python connector와 무관하다.

따라서:

```text
Python code 변경 없음
```

이면 Python regression은 실행하지 않아도 된다.

Python file을 변경해야 할 이유가 생기면 먼저 범위를 재검토한다.

---

# 52. Database

이번 Phase:

```text
migration = NO
schema = NO
JPA = NO
```

---

# 53. Documentation

Phase completion 문서에 다음을 기록한다.

```text
renderer responsibility
cue formatter responsibility
pace canonical conversion
HR rendering rule
cue ordering rule
provider boundary
known Garmin limitations
```

---

# 54. 반드시 남길 Known Issue

다음은 해결된 것으로 표시하지 않는다.

```text
1. Garmin pace target mismatch
   - Intervals render/server 반영과
     Garmin device target 표시가 일치하지 않았던 문제

2. %LTHR Garmin device validation
   - renderer/server 수준까지만 확인
   - device 실증 필요

3. Intervals → Garmin transport
   - repository 밖
   - 여전히 opaque
```

---

# 55. Phase 5C-2 결과에서 사용할 용어

다음 상태를 구분한다.

```text
IMPLEMENTED
UNIT_VERIFIED
SERVER_VERIFIED
DEVICE_VERIFIED
ASSUMED
UNRESOLVED
```

예:

```text
Pace rendering           UNIT_VERIFIED
Pace Garmin target       UNRESOLVED

%LTHR rendering          UNIT_VERIFIED
%LTHR Garmin support     ASSUMED

Treadmill cue rendering  UNIT_VERIFIED
Cue ordering             legacy DEVICE_VERIFIED
```

새 Java 구현 자체가 device verified라는 뜻은 아니다.

---

# 56. Diff review

구현 후:

```powershell
git status
git diff --stat
git diff
```

확인.

다음 불필요 파일 없어야 한다.

```text
build/
.gradle/
.venv/
temporary scripts
scratch output
IDE metadata
live response dump
```

---

# 57. Secrets check

전체 diff에서 확인.

다음 금지:

```text
Intervals API key
Garmin token
Garmin account
real workout/event IDs
real personal activity IDs
GPS data
cookies
.env
authorization headers
```

Synthetic fixture는 허용.

---

# 58. Definition of Done

```text
[ ] IntervalsWorkoutRenderer implemented
[ ] GarminSafeCueFormatter implemented
[ ] StructuredWorkout remains provider-neutral
[ ] deterministic rendering
[ ] locale-independent numeric formatting
[ ] canonical newline behavior
[ ] pace conversion ported
[ ] pace range rendering tested
[ ] HR %LTHR rendering ported
[ ] hr=1s behavior ported
[ ] no silent bpm → %LTHR conversion
[ ] treadmill speed cue ported
[ ] incline cue ported
[ ] incline range ported
[ ] cue ordering preserved
[ ] cue-before-duration/target regression test
[ ] golden renderer tests
[ ] mixed workout test
[ ] no HTTP
[ ] no publisher
[ ] no DB migration
[ ] legacy production scripts untouched
[ ] full Java regression PASS
[ ] documentation updated
[ ] known Garmin issues preserved
[ ] diff reviewed
[ ] secrets checked
[ ] commit
[ ] push
```

---

# 59. 권장 Commit

```text
feat: add Intervals workout renderer
```

repository convention을 먼저 확인한다.

---

# 60. 완료 보고 형식

## Renderer

```text
Input:
Output:
Step ordering:
Newline:
Locale handling:
```

## Pace

```text
Canonical input:
Rendered form:
Range support:
Legacy equivalent:
Garmin device status:
```

## Heart Rate

```text
Supported:
Rendered form:
hr=1s:
Absolute BPM policy:
Garmin device status:
```

## Garmin-safe cue

```text
Speed:
Incline:
Range:
Decimal formatting:
ASCII normalization:
Ordering:
```

## Golden tests

```text
Easy:
Intervals:
HR:
Treadmill:
Treadmill + target:
Mixed:
```

## Tests

```text
Java total:
passed:
failed:
new tests:
```

## Database

```text
migration: NO
schema change: NO
```

## Provider boundary

```text
StructuredWorkout provider-neutral: YES
Renderer contains Intervals syntax: YES
Domain contains Intervals syntax: NO
Domain contains Garmin syntax: NO
```

## Legacy

```text
legacy production files modified: NO
```

## Known issues

```text
Pace Garmin mismatch:
%LTHR Garmin validation:
Intervals→Garmin transport:
```

## Git

```text
branch:
commit:
push:
```

---

# 61. 다음 Phase

5C-2 완료 후:

```text
Phase 5C-3
IntervalsWorkoutPublisher
```

범위:

```text
HTTP client
authentication
create/update
marker-based idempotency migration
readback verification
```

5C-3에서는 renderer logic을 다시 구현하지 않는다.

그리고:

```text
Phase 5C-4
Real Intervals → Garmin device validation
```

에서:

```text
PACE
%LTHR
TREADMILL CUE
```

를 실제 Forerunner 265에서 검증한다.

5C-3와 5C-4는 자동으로 시작하지 않는다.
