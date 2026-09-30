Phase 5C-1을 진행해줘.

기준 commit은 ab55d87이야.
먼저 origin/main을 pull해서 최신 상태를 확인해.

CLAUDE.md와 관련 project skill을 따르고,
작업지시서는 아래 경로에 저장해:
docs/work-orders/2026-09-30-phase-5c-1-structured-workout-domain.md

핵심은 TargetedWorkoutPrescription → StructuredWorkoutMapper
→ StructuredWorkout까지만 구현하는 거야.

Intervals renderer/publisher, GarminSafeCueFormatter,
HTTP, DB migration은 이번 Phase에서 구현하지 마.

구현 → 전체 regression → 문서화 → diff/secrets 검사
→ commit → push까지 진행하고 5C-2는 시작하지 마.
# RunningAI Phase 5C-1
## StructuredWorkout Intermediate Domain & Mapper

### 0. 기준 상태

현재 기준 commit:

```text
ab55d87
```

Phase 5C-0에서 Legacy workout publishing flow 분석 및 migration design을 완료했다.

확인된 legacy flow:

```text
intervals-structured-workout.ps1
        ↓
create-today-workout.ps1
        ↓
Intervals.icu API
        ↓
Garmin Connect
        ↓
Garmin device
```

단, Intervals.icu 이후 Garmin Connect/device 전달 과정은 repo 밖에서 이루어지며 현재 코드로 관측할 수 없다.

Phase 5C-0 권장 architecture:

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
IntervalsWorkoutPublisher
```

이번 Phase에서는 위 구조 중 다음까지만 구현한다.

```text
TargetedWorkoutPrescription
        ↓
StructuredWorkoutMapper
        ↓
StructuredWorkout
```

---

# 1. 시작 전

외부 PC에서:

```powershell
git status
git switch main
git pull --ff-only origin main
git log -5 --oneline
```

다음을 확인한다.

```text
HEAD에 ab55d87 포함
working tree clean
```

기존 변경사항이 있다면 덮어쓰지 않는다.

---

# 2. Project rules

반드시 기존 repository의:

```text
CLAUDE.md
관련 .claude/skills/*
```

를 따른다.

필요한 파일만 조사한다.

repo 전체를 다시 분석하거나 Phase 5C-0 조사를 반복하지 않는다.

Phase 5C-0 문서와 현재 Spring workout domain을 source of truth로 사용한다.

---

# 3. Work Order 저장

작업 시작 시 이 지시서를 다음 경로에 저장한다.

```text
docs/work-orders/
2026-09-30-phase-5c-1-structured-workout-domain.md
```

완료 결과도 같은 문서 또는 현재 project convention에 맞는 completion 문서에 기록한다.

---

# 4. 목표

`TargetedWorkoutPrescription`을 외부 provider 문법과 독립적인 구조화 workout representation으로 변환할 수 있게 한다.

최종 목표:

```java
TargetedWorkoutPrescription prescription = ...;

StructuredWorkout workout =
    structuredWorkoutMapper.map(prescription);
```

`StructuredWorkout`은 이후 Phase 5C-2의 Intervals renderer가 소비한다.

---

# 5. 핵심 원칙

## 5.1 Domain은 Intervals.icu를 몰라야 한다

다음과 같은 Intervals workout builder 문법이 domain에 들어가면 안 된다.

예:

```text
@4:50-5:00
hr=1s
90-95% LTHR
12.0kph Incline1pct
```

또는 provider-specific JSON/text token.

다음 클래스들은 Intervals.icu를 import하거나 참조하지 않는다.

```text
StructuredWorkout
StructuredWorkoutStep
StructuredWorkoutTarget
StructuredWorkoutMapper
```

---

## 5.2 Garmin-safe cue를 만들지 않는다

Legacy:

```text
12.0 km/h | 경사 1%
```

를

```text
12.0kph Incline1pct
```

로 만드는 것은 renderer concern이다.

따라서 이번 Phase에서는:

```java
GarminSafeCueFormatter
```

를 구현하지 않는다.

대신 필요한 원본 의미를 numeric/domain field로 보존한다.

예:

```text
speedKph = 12.0
inclinePercent = 1.0
```

정확한 구조는 기존 Spring domain을 조사해 가장 단순하게 결정한다.

---

# 6. StructuredWorkout

최소한 다음 개념을 표현할 수 있어야 한다.

```text
workout identity / source reference
activity type
ordered workout steps
```

현재 domain convention에 맞게 record/class를 선택한다.

불필요한 JPA Entity로 만들지 않는다.

DB persistence 대상이 아니다.

예시 개념:

```java
StructuredWorkout
    activityType
    steps
```

단, 실제 필드명은 기존 model과 맞춘다.

---

# 7. StructuredWorkoutStep

각 step은 최소한 다음 의미를 잃지 않아야 한다.

```text
step ordering
step purpose/type
duration/distance
target
treadmill-specific numeric instruction if present
human-readable semantic label if already available
```

예:

```text
Warmup
Work
Recovery
Cooldown
```

현재 `TargetedWorkoutPrescription`에 더 적절한 step classification이 이미 있다면 그것을 재사용한다.

새 enum을 중복 생성하지 않는다.

---

# 8. Step order

입력 prescription의 step sequence는 반드시 유지한다.

예:

```text
Warmup
Work 1
Recovery 1
Work 2
Recovery 2
Cooldown
```

가 mapper 이후에도 동일해야 한다.

Mapper가 임의로 재정렬하거나 merge하면 안 된다.

---

# 9. Duration / distance

현재 prescription에서 사용하는 duration semantics를 조사한다.

예:

```text
time
distance
```

가능한 경우 기존 value object를 재사용한다.

renderer 편의를 위해 초기에 문자열로 변환하지 않는다.

잘못된 예:

```java
"10m"
"1km"
```

가능하면 numeric + semantic type을 유지한다.

---

# 10. Target model

현재 Spring workout domain이 실제로 사용하는 target 종류만 우선 지원한다.

적어도 현재 코드에서 사용되는:

```text
PACE
HEART_RATE
```

target을 lossless하게 표현해야 한다.

Target model은 provider-neutral이어야 한다.

예시 개념:

```text
StructuredWorkoutTarget
 ├─ PaceTarget
 └─ HeartRateTarget
```

sealed interface / enum + value object / existing project style 중 현재 코드에 가장 자연스러운 방식을 선택한다.

과도한 abstraction을 만들지 않는다.

---

# 11. Pace target

Pace는 문자열이 아니라 의미 있는 numeric representation으로 유지한다.

예:

```text
lower pace
upper pace
```

또는 현재 prescription에서 사용하는 canonical unit.

단위를 mapper와 renderer 사이에서 명확히 한다.

가능하면 현재 RunningAI 내부 canonical unit을 그대로 사용한다.

Mapper에서 Intervals token으로 변환하지 않는다.

---

# 12. Heart-rate target

HR target 역시 semantic model로 유지한다.

중요:

Legacy의:

```text
absolute BPM unsupported
%LTHR + hr=1s
```

제약은 Intervals/Garmin renderer 구현의 제약이다.

그 제약을 중간 domain 자체에 억지로 넣지 않는다.

다만 현재 `TargetedWorkoutPrescription`이 실제로 표현하지 않는 target type을 이번 Phase에서 추측해 대규모로 추가할 필요도 없다.

원칙:

```text
현재 source model의 의미는 lossless하게 유지
provider limitation은 renderer phase에서 처리
```

---

# 13. Threshold semantics

Spring에는 legacy에는 없었던 threshold pace 저장/계산 구조가 이미 존재한다.

이미 `TargetedWorkoutPrescription` 생성 단계에서 concrete target으로 resolve된 값이라면:

```text
StructuredWorkoutMapper
```

가 threshold 계산을 다시 하면 안 된다.

즉:

```text
training physiology
        ↓
target calculation
        ↓
TargetedWorkoutPrescription
        ↓
structural mapping only
        ↓
StructuredWorkout
```

경계를 유지한다.

Mapper가 training recommendation engine이 되면 안 된다.

---

# 14. Treadmill semantics

현재 Spring은 treadmill speed/incline을 numeric field로 가지고 있다.

이 장점을 유지한다.

예:

```text
speedKph
inclinePercent
```

또는 현재 domain 필드.

다음처럼 미리 문자열을 생성하지 않는다.

```text
"12.0kph Incline1pct"
```

또한 Garmin cue 길이 제한/ASCII normalization/문자 제거 같은 규칙은 이번 Phase 범위 밖이다.

---

# 15. Mapper 책임

`StructuredWorkoutMapper`의 책임은:

```text
TargetedWorkoutPrescription
→ provider-neutral structured representation
```

뿐이다.

포함:

```text
step order mapping
duration mapping
target mapping
numeric treadmill information preservation
metadata preservation where needed
```

미포함:

```text
Intervals syntax rendering
HTTP
authentication
publishing
idempotency marker
Garmin cue formatting
Garmin Connect sync
DB persistence
```

---

# 16. Validation

입력 domain이 이미 validation을 보장한다면 불필요하게 중복 validation하지 않는다.

그러나 mapper가 처리할 수 없는 impossible state가 있다면 명확하게 fail한다.

silent fallback 금지.

예:

```text
target을 이해하지 못했다고 OPEN target으로 변경
pace가 잘못됐다고 target을 삭제
```

같은 동작을 하지 않는다.

---

# 17. Unsupported semantics

현재 prescription에 mapper가 지원하지 않는 새로운 semantic type이 발견되면:

1. 먼저 기존 code/design을 확인한다.
2. 의미를 손실시키는 fallback을 하지 않는다.
3. 명확한 exception 또는 project-standard validation failure를 사용한다.

단순히 renderer가 아직 지원하지 않는다는 이유만으로 StructuredWorkout mapping 자체를 실패시키지는 않는다.

---

# 18. No provider leakage test

테스트 또는 코드 검토를 통해 StructuredWorkout 계층에 다음 문자열/개념이 들어오지 않는지 확인한다.

```text
Intervals.icu
Garmin
hr=1s
kph cue token
Incline...pct cue token
Intervals workout builder syntax
HTTP endpoint
API token
```

패키지 import도 확인한다.

---

# 19. Package placement

기존 workout/domain package 구조를 먼저 확인한다.

예를 들어 현재 구조가:

```text
training/
workout/
prescription/
```

등으로 나뉘어 있다면 그 convention을 따른다.

이번 Phase 때문에 새로운 최상위 architecture tree를 만들지 않는다.

---

# 20. Unit tests

최소 다음을 검증한다.

### 20.1 Basic mapping

```text
TargetedWorkoutPrescription
→ StructuredWorkout
```

정상 변환.

---

### 20.2 Step order

입력 step 순서와 출력 step 순서 동일.

---

### 20.3 Pace target

pace min/max 또는 현재 canonical pace 값이 lossless하게 보존된다.

---

### 20.4 HR target

현재 prescription에서 지원하는 HR target semantics가 손실 없이 보존된다.

Intervals-specific token은 없어야 한다.

---

### 20.5 Treadmill

예:

```text
speed = 12.0 km/h
incline = 1.0%
```

가 numeric 의미로 보존되는지 검증한다.

출력에:

```text
12.0kph Incline1pct
```

같은 rendered cue가 생겨서는 안 된다.

---

### 20.6 Mixed workout

예:

```text
warmup
work
recovery
work
cooldown
```

같은 실제 workout 구조가 정상적으로 map되는지 검증한다.

---

### 20.7 Invalid/unsupported mapping

의미를 잃는 silent fallback이 발생하지 않는지 검증한다.

---

# 21. Regression

전체 Java regression을 실행한다.

```powershell
cd server
.\gradlew.bat clean test
```

모든 기존 test가 계속 PASS해야 한다.

이번 Phase는 Python connector를 변경하지 않는 것이 원칙이다.

따라서 Python 테스트는 Python 코드가 실제 변경된 경우에만 실행한다.

---

# 22. Database

이번 Phase에서는:

```text
migration = NO
schema change = NO
JPA entity = NO
```

가 원칙이다.

StructuredWorkout은 persistence model이 아니다.

DB 변경이 필요하다고 판단되면 구현 전에 왜 필요한지 다시 검토한다.

대부분 필요 없어야 한다.

---

# 23. Legacy files

이번 Phase에서 legacy PowerShell을 수정하지 않는다.

특히:

```text
intervals-structured-workout.ps1
create-today-workout.ps1
```

은 reference source일 뿐 production migration 대상이 아니다.

---

# 24. Pace Garmin bug

Phase 5C-0에서 확인된:

```text
Intervals server에는 pace target 존재
Garmin device에서는 "목표 없음"으로 보였던 사례
```

는 이번 Phase에서 해결하지 않는다.

이 문제는 renderer/publisher/device validation 경계에서 조사해야 한다.

StructuredWorkout domain을 이 버그에 맞춰 왜곡하지 않는다.

---

# 25. HR Garmin verification

Phase 5C-0 repository evidence 기준으로:

```text
%LTHR + hr=1s
```

이 Intervals server readback에서 보존되는 것은 확인됐지만 repo 자체만으로 Garmin device 표시까지 증명할 수 없다.

따라서 이번 Phase 문서에서는:

```text
Garmin HR target device verified
```

라고 새로 단정하지 않는다.

실기기 검증은 5C-4에서 수행한다.

---

# 26. Garmin cue ordering

Legacy에서 실제 장치 관찰로 확인된:

```text
cue
→ duration/target
```

순서 규칙은 중요한 migration knowledge다.

하지만 이 규칙 역시 renderer responsibility다.

`StructuredWorkout`에 token ordering 개념을 넣지 않는다.

5C-2에서 반드시 참고할 수 있도록 문서에 유지한다.

---

# 27. Documentation

설계 문서 또는 Phase completion report에 최소한 다음을 기록한다.

```text
StructuredWorkout responsibility
StructuredWorkoutStep responsibility
target representation
canonical units
treadmill representation
mapper responsibility
renderer와의 boundary
```

특히 다음 문장을 명시한다.

```text
StructuredWorkout is provider-neutral and contains no
Intervals.icu or Garmin rendering syntax.
```

---

# 28. Diff review

구현 후:

```powershell
git status
git diff --stat
git diff
```

를 확인한다.

다음이 생기지 않았는지 확인한다.

```text
temporary runner
generated build file
.venv
IDE metadata
test scratch file
live Garmin payload
```

---

# 29. Secrets check

최종 commit 전 전체 diff에서 확인한다.

금지:

```text
Garmin credentials
Garmin token
Intervals API key
real activity identifier
personal raw payload
GPS
.env
cookies
```

synthetic test fixture는 허용한다.

---

# 30. Definition of Done

다음이 모두 충족되어야 한다.

```text
[ ] StructuredWorkout implemented
[ ] StructuredWorkoutStep implemented
[ ] provider-neutral target representation implemented
[ ] StructuredWorkoutMapper implemented
[ ] step order preserved
[ ] pace semantics preserved
[ ] HR semantics preserved
[ ] treadmill numeric semantics preserved
[ ] no Intervals syntax in intermediate domain
[ ] no Garmin-safe cue generation
[ ] no publisher implementation
[ ] no HTTP implementation
[ ] no DB migration
[ ] mapper unit tests PASS
[ ] full Java regression PASS
[ ] documentation updated
[ ] diff reviewed
[ ] secrets checked
[ ] commit
[ ] push
```

---

# 31. Commit

권장:

```text
feat: add structured workout intermediate domain
```

commit 전에 기존 repo commit convention을 확인한다.

---

# 32. 완료 보고 형식

완료 시 아래 형식으로 보고한다.

## Domain

```text
StructuredWorkout:
StructuredWorkoutStep:
Target types:
Canonical units:
Treadmill representation:
```

## Mapper

```text
Input:
Output:
Responsibilities:
Explicit non-responsibilities:
```

## Provider boundary

```text
Intervals syntax leaked into domain: YES/NO
Garmin syntax leaked into domain: YES/NO
Cue formatting implemented: YES/NO
Publishing implemented: YES/NO
```

기대:

```text
NO
NO
NO
NO
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

## Legacy

```text
legacy production files modified: NO
```

## Git

```text
branch:
commit:
push:
```

## Remaining known issues

반드시 유지:

```text
Garmin pace target mismatch unresolved
Garmin %LTHR device validation still required from repository-evidence perspective
Garmin-safe cue ordering must be retained in renderer
Intervals→Garmin transport remains externally opaque
```

---

# 33. 다음 Phase

완료 후 추천:

```text
Phase 5C-2
IntervalsWorkoutRenderer
+
GarminSafeCueFormatter
```

여기서 처음으로 legacy renderer의:

```text
pace conversion
HR rendering
cue normalization
cue ordering
```

을 Spring/Java 쪽으로 port한다.

`IntervalsWorkoutPublisher`는 5C-3으로 분리한다.

실제 Garmin 장치 검증은 5C-4에서 수행한다.

5C-2는 자동으로 시작하지 않는다.
