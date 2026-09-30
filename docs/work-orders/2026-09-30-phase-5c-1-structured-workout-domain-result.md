# 2026-09-30 — Phase 5C-1: StructuredWorkout Intermediate Domain & Mapper

| 항목 | 내용 |
|------|------|
| 날짜 | 2026-09-30 |
| 작업 | `TargetedWorkoutPrescription → StructuredWorkoutMapper → StructuredWorkout`까지만 구현 (provider-neutral 중간 도메인) |
| 상태 | 완료 |
| 커밋 | `feat: add structured workout intermediate domain` |
| 지시서 원문 | [2026-09-30-phase-5c-1-structured-workout-domain.md](2026-09-30-phase-5c-1-structured-workout-domain.md) |
| 이전 작업 | ab55d87 `docs: investigate legacy structured workout pipeline` (Phase 5C-0) |

## 0. 작업 전 상태

```text
git switch main / git pull --ff-only origin main: Already up to date
HEAD: ab55d87 (지시서 기준 commit과 일치)
working tree: clean
regression baseline: Java 314 passed
```

## 1. 목적과 범위

Phase 5C-0이 권장한 아키텍처(`TargetedWorkoutPrescription → StructuredWorkoutMapper →
StructuredWorkout → IntervalsWorkoutRenderer → GarminSafeCueFormatter → IntervalsWorkoutPublisher`)
중 처음 두 화살표만 구현했다. Intervals.icu/Garmin renderer, cue formatter, HTTP publisher, DB
migration은 이번 Phase에서 구현하지 않았다(지시서 명시 범위 밖).

## Domain

```text
StructuredWorkout:          asOfDate, intent(CandidateTrainingType), totalDurationMinutes,
                             steps(List<StructuredWorkoutStep>) — TargetedWorkoutPrescription의
                             profile/prescription 트레이스백 필드는 의도적으로 제외(디커플링이
                             목적이므로 renderer가 상위 도메인을 몰라도 되게 함)
StructuredWorkoutStep:       type(SegmentType), durationMinutes, intensityClass, description,
                             primaryTargetType(PrimaryTargetType), paceTarget(PaceTarget),
                             heartRateTarget(HeartRateTarget), treadmillTarget(TreadmillTarget)
                             — TargetedWorkoutSegment와 정확히 같은 필드 shape (아래 "Mapper" 절 참고)
Target types:                기존 PaceTarget/HeartRateTarget/TreadmillTarget/PrimaryTargetType을
                             그대로 재사용(새 타입을 만들지 않음 — 이미 provider-neutral하고
                             lossless한 canonical-unit value object였으므로 중복 abstraction을
                             피함, 지시서 10절 "과도한 abstraction을 만들지 않는다"에 부합)
Canonical units:              pace=seconds/km, HR=%LTHR와 bpm 둘 다, treadmill speed=km/h,
                             incline=percent — 5B-2에서 이미 확정된 단위 그대로 유지
Treadmill representation:     TreadmillTarget(minSpeedKph, maxSpeedKph, minInclinePercent,
                             maxInclinePercent) 숫자 필드 그대로. "12.0kph Incline1pct" 같은
                             문자열은 어디에도 생성하지 않음(테스트로 확인, 아래 참고)
```

## Mapper

```text
Input:  TargetedWorkoutPrescription (5B-2 산출물)
Output: StructuredWorkout
Responsibilities: 순수 구조적 재형성만. segments 리스트를 순서 그대로 순회하며 각
                  TargetedWorkoutSegment의 8개 필드를 StructuredWorkoutStep으로 1:1 복사한다.
                  threshold 계산이나 target 우선순위 판단을 다시 하지 않는다(이미
                  WorkoutIntensityTargetService/WorkoutIntensityTargetPolicy가 끝낸 일).
Explicit non-responsibilities: Intervals.icu 문법 렌더링, HTTP, 인증, publish, idempotency
                  marker, Garmin cue 포맷팅, Garmin Connect sync, DB 영속화 — 전부 미구현,
                  다음 Phase(5C-2/5C-3) 대상.
```

`StructuredWorkoutMapper`는 `@Component`(Spring bean)로 만들었다 — `GarminActivityMapper`와 같은
기존 프로젝트 관례(순수 변환이지만 향후 다른 서비스가 주입해서 쓸 수 있도록 인스턴스 메서드로 노출)를
따랐다. 상태가 없고 DB/HTTP 의존성이 전혀 없다.

오늘 실제로 mapping이 "구조적으로는" `TargetedWorkoutSegment`를 거의 그대로 복사하는 것처럼 보이지만,
이것이 이번 Phase의 의도다: renderer(5C-2)가 `TargetedWorkoutPrescription`이 아니라
`StructuredWorkout`에만 의존하게 해서, training/recommendation 도메인의 향후 변경(예: interval/repeat
구조 추가)이 renderer를 건드리지 않게 하는 architectural boundary를 만드는 것이 목적이지, 오늘 당장
값을 가공하는 것이 목적이 아니다(Phase 5C-0의 Option B 권장 근거).

## Provider boundary

```text
Intervals syntax leaked into domain: NO
Garmin syntax leaked into domain:    NO
Cue formatting implemented:          NO
Publishing implemented:              NO
```

`StructuredWorkoutMapperTest.noProviderSyntaxLeaksIntoTheStructuredWorkout()`이 실제로 `toString()`
결과에 `"Intervals"`, `"Garmin"`, `"hr=1s"`, `"kph"`, `"pct"`, `"@4:50"` 같은 provider 토큰이 전혀
없음을 검증한다. `TreadmillTarget`의 필드명(`minInclinePercent` 등)이 우연히 "Incline"이라는 부분
문자열을 포함하지만 이는 RunningAI 자체 canonical 필드 이름일 뿐 렌더링된 cue 텍스트가 아니므로
금지어 목록에서 제외했다(구분 근거를 테스트 주석에 명시).

## Tests

```text
Java total: 325
passed: 325
failed: 0
new tests: 11 (StructuredWorkoutMapperTest)
```

Baseline 314 → 325 (+11). 신규 테스트: basic mapping, step order 보존, 5-segment
warmup/work/recovery/work/cooldown 혼합 구조, pace target 무손실 보존, HR-only target 무손실 보존,
treadmill 숫자 의미 보존 + cue 문자열 미생성, REST(target 전부 null, 예외 없음), CROSS_TRAINING(profile이
있어도 항상 QUALITATIVE, 예외 없음), profile 부재(QUALITATIVE fallback, 예외 없음), provider 문법
미유출, 결정론(같은 입력 → 같은 결과). 첫 실행 시 2개 테스트가 `TreadmillTarget`의 필드명 자체에 포함된
"Incline" 부분 문자열을 오탐한 assertion 버그였고, 실제 cue 토큰(`kph`/`pct`/`0.5-1`)만 검사하도록
수정해 통과시켰다(위 "Provider boundary" 절 참고) — 구현 로직 자체의 결함은 아니었다.

Python connector는 이번 Phase에서 변경하지 않았으므로 재실행하지 않았다(지시서 21절).

## Database

```text
migration: NO
schema change: NO
```

`StructuredWorkout`/`StructuredWorkoutStep`은 plain Java record이며 `@Entity`가 아니다. 영속화 대상이
아니다(지시서 22절과 일치).

## Legacy

```text
legacy production files modified: NO
```

`C:\running-ai`의 어떤 파일도 이번 Phase에서 열람하지 않았다(Phase 5C-0에서 이미 조사 완료, 재조사하지
않음 — 지시서 2절).

## Git

```text
branch: main
commit: (아래 참고)
push: 완료
```

## Remaining known issues

```text
Garmin pace target mismatch unresolved: Phase 5C-0에서 확인된 "Intervals 서버엔 pace target이
  있는데 Garmin 기기엔 '목표 없음'으로 보인" 사례는 이번 Phase에서 다루지 않았다. StructuredWorkout은
  이 버그에 맞춰 값을 왜곡하지 않았다(입력을 그대로 보존).
Garmin %LTHR device validation still required from repository-evidence perspective: %LTHR+hr=1s가
  실제 Garmin 화면에 표시되는지는 여전히 미검증(ASSUMED, Phase 5C-0 근거 그대로 유지). 이번 Phase의
  StructuredWorkout에는 hr=1s 같은 provider 토큰이 전혀 없으므로 이 이슈와 무관하다.
Garmin-safe cue ordering must be retained in renderer: legacy의 "cue는 duration/target 토큰보다
  앞에 와야 Garmin에 보인다" 규칙은 StructuredWorkout에 넣지 않았다(renderer 책임). Phase 5C-2
  구현 시 반드시 이 순서 규칙을 지켜야 한다(Phase 5C-0 문서에 상세 기록됨).
Intervals→Garmin transport remains externally opaque: 이 repo는 Intervals.icu API 이후
  Garmin Connect/기기로의 전달을 관측하거나 제어할 수 없다는 사실은 여전히 유효하다.
```

## 다음 Phase (제안, 자동 시작 안 함)

```text
Phase 5C-2
IntervalsWorkoutRenderer + GarminSafeCueFormatter
  - StructuredWorkout -> Intervals.icu Workout Builder text
  - legacy pace/HR 변환 공식과 cue 정규화 규칙(Get-GarminSafeStepCue 대응) 이식
  - cue-before-target 순서 규칙 반드시 보존

Phase 5C-3
IntervalsWorkoutPublisher (HTTP client, marker 기반 idempotency, readback 검증)

Phase 5C-4
실제 Garmin Forerunner 265 E2E 검증 (pace target 표시 일관성, %LTHR+hr=1s 표시 여부 우선 확인)
```
