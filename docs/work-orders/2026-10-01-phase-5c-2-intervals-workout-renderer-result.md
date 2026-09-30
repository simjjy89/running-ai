# 2026-10-01 — Phase 5C-2: IntervalsWorkoutRenderer & GarminSafeCueFormatter

| 항목 | 내용 |
|------|------|
| 날짜 | 2026-10-01 |
| 작업 | `StructuredWorkout → IntervalsWorkoutRenderer → GarminSafeCueFormatter` 구현 (render only, HTTP/publish 없음) |
| 상태 | 완료 |
| 커밋 | `feat: add Intervals workout renderer` |
| 지시서 원문 | [2026-10-01-phase-5c-2-intervals-workout-renderer.md](2026-10-01-phase-5c-2-intervals-workout-renderer.md) |
| 이전 작업 | 5c097fe `feat: add structured workout intermediate domain` (Phase 5C-1) |

## 0. 작업 전 상태

```text
git switch main / git pull --ff-only origin main: Already up to date
HEAD: 5c097fe (지시서 기준 commit과 일치)
working tree: clean
regression baseline: Java 325 passed
```

## 1. 목적과 범위

Phase 5C-0이 권장한 아키텍처의 세 번째/네 번째 단계(`StructuredWorkout → IntervalsWorkoutRenderer →
GarminSafeCueFormatter → Intervals.icu Workout Builder text`)를 구현했다. Intervals.icu HTTP
publish/인증/idempotency marker, Garmin Connect 호출, DB migration, legacy PowerShell 수정은 이번
Phase 범위 밖이며 구현하지 않았다.

## Renderer

```text
Input:  StructuredWorkout (Phase 5C-1, provider-neutral)
Output: RenderedIntervalsWorkout(String workoutText) — HTTP payload/URL/auth/이벤트 ID/publish
        status는 없음(지시서 37절 금지 목록)
Step ordering: label -> cue -> duration -> target (legacy 실기기 관찰로 확인된 순서, Get-
               GarminSafeStepCue 관련 work order 20260928-2305의 핵심 발견을 그대로 이식)
Newline: "\n"만 사용(OS locale/줄바꿈 관계없이 결정론적)
Locale handling: 모든 숫자 포맷팅에 Locale.ROOT 사용 — OS locale이 독일어(콤마 소수점)여도 출력은
               항상 "." 사용(테스트로 확인)
```

`com.runningai.integration.intervals` 패키지에 위치시켰다(기존 `integration.garmin`과 같은 레벨
convention, 지시서 47절과 일치). `IntervalsWorkoutRenderer`는 `@Component`(Spring bean, `GarminActivityMapper`와
동일 패턴), `GarminSafeCueFormatter`는 package-private 순수 static 유틸리티 클래스(`WorkoutPrescriptionPolicy`
등 기존 policy class와 동일 패턴)로 만들었다.

`CandidateTrainingType.REST`는 legacy의 "REST는 structured Run을 만들지 않는다" 규칙을 그대로 따라
빈 문자열을 반환한다(구조화 step 자체가 없음).

## Pace

```text
Canonical input:  PaceTarget(fastSecondsPerKm, slowSecondsPerKm) — 초/km, Phase 5B-2 그대로
Rendered form:    단일값 "M:SS/km Pace", 범위 "M:SS-M:SS/km Pace" (legacy Get-IntervalsPaceTarget/
                  Convert-SecondsPerKmToIntervalsPace와 동일 알고리즘 이식)
Range support:    fast<=slow는 domain이 이미 보장하므로 재검증하지 않음(지시서 16절)
Legacy equivalent: 동일 — "6:30-7:00/km Pace" 형태의 legacy 예시와 알고리즘 수준에서 일치
                  (golden test로 고정: 예 "5:45-6:30/km Pace")
Garmin device status: UNRESOLVED — Phase 5C-0에서 확인된 "Intervals 서버엔 pace target이 있는데
                  Garmin 기기엔 '목표 없음'으로 보인" 사례(event 138398536)는 이번 Phase에서
                  해결하지 않았다. Renderer가 legacy와 동일한 text를 만든다는 것이 이 버그가
                  고쳐졌다는 뜻이 아니다.
```

## Heart Rate

```text
Supported:        %LTHR range만(absolute bpm은 structured target으로 렌더링하지 않음, Phase 5C-0의
                  DECISION-HEART-RATE-STRUCTURED-TARGET-NOT-IMPLEMENTED.md 결정을 그대로 유지)
Rendered form:    단일값 "N% LTHR hr=1s", 범위 "N-M% LTHR hr=1s". HeartRateTarget에 이미 저장된
                  정수 percent(Phase 5B-2, Math.round 결과)를 그대로 표시할 뿐, bpm/LTHR에서
                  다시 계산하지 않는다(지시서 27절 "No training logic"과 일치)
hr=1s:            %LTHR target 렌더링 시 항상 함께 붙는 suffix(legacy와 동일 위치/형태)
Absolute BPM policy: HeartRateTarget에 minBpm/maxBpm이 함께 있어도 renderer는 이를 별도로
                  렌더링하지 않는다(silent %LTHR 변환도, silent bpm 노출도 하지 않음 — 지시서
                  30절 "bpm -> %LTHR 자동 변환 금지"). primaryTargetType이 HEART_RATE일 때
                  %LTHR만 렌더링하고, bpm 필드는 domain에 보존된 채 renderer가 사용하지 않는다.
Garmin device status: ASSUMED — Phase 5C-0 근거대로, %LTHR+hr=1s가 Intervals 서버 readback에서
                  텍스트가 보존되는 것은 legacy에서 확인됐지만 실제 Garmin 화면 표시는 여전히
                  미검증 상태를 유지한다(이번 Phase에서 새로 DEVICE_VERIFIED로 승격하지 않음).
```

## Garmin-safe cue

```text
Speed:    "N kph" 또는 범위 "N-Mkph", 항상 소수점 1자리 유지(legacy 예시 "12.0kph"가 "12kph"로
          trim되지 않음을 work order 20260928-2305 원문에서 확인 후 그대로 port)
Incline:  "IncineNpct" 또는 "N-Mpct" 형태(오타 방지 재확인: "Incline"+숫자+"pct"), 소수점 trailing
          zero는 제거(1.0->1, 0.5는 유지) — legacy의 "Incline0.5-1pct" 예시와 일치. 단, 이
          trim 규칙 자체는 legacy 코드가 프로그래밍적으로 계산한 것이 아니라 예시 텍스트가
          사람이 그렇게 입력한 것이었음을 Phase 5C-0에서 확인했으므로, 이번 Java 구현이 내린
          자체 결정이다(문서화된 재현 가능한 legacy 함수 규칙은 아님 — 아래 "Known issues"
          참고는 아니지만 설계 결정으로 명시해 둔다).
Range:    speed/incline 모두 min==max면 단일값, 아니면 dash 범위. legacy의 실제 fixture는 항상
          치료 고정값(min=max)이었고 진짜 speed range cue의 legacy 예시는 없었다 — 이번 포트는
          기존 incline range 관례를 speed에도 일관되게 확장한 것(지시서 17절이 요구한 "실제
          legacy decimal normalization 규칙을 확인하고 port"의 speed 부분은 값 형식(1자리
          고정)만 확인 가능했고, range 표기 자체는 확장임을 명시).
Decimal formatting: speed=String.format("%.1f", ...)(Locale.ROOT), incline=BigDecimal
          stripTrailingZeros 기반 최소 표현
ASCII normalization: '%' 기호는 어디에도 나타나지 않음(테스트로 확인), 모든 cue 텍스트는 ASCII
Ordering: cue는 항상 duration/target 토큰보다 앞(`rendersGarminCueBeforeDurationAndTarget`
          회귀 테스트로 고정)
```

## Golden tests

```text
Easy:               goldenEasyPaceRun (warm-up/main/cool-down, 전체 profile)
Intervals(5-segment): goldenFiveSegmentMixedWorkout (warmup/work/recovery/work/cooldown)
HR:                  goldenHeartRateOnlyWorkout (%LTHR+hr=1s, pace 없음)
Qualitative fallback: goldenQualitativeFallbackWorkout (profile 부재, incline만 존재)
REST:                restRendersNoStructuredContent (빈 문자열)
Mixed:               goldenMixedTargetTypesAcrossSteps (같은 workout 안에서 PACE/HEART_RATE/
                     QUALITATIVE가 step마다 다르게 존재)
```

모든 golden test는 `assertThat(actual).isEqualTo(expected)` 수준의 exact 비교다(부분 contains 아님,
지시서 38절).

## Tests

```text
Java total: 346
passed: 346
failed: 0
new tests: 21 (GarminSafeCueFormatterTest 10개, IntervalsWorkoutRendererTest 11개)
```

Baseline 325 → 346. 모든 신규 테스트가 첫 실행에서 통과했다(추가 수정 불필요). Python connector는
변경하지 않았으므로 재실행하지 않았다(지시서 51절).

## Database

```text
migration: NO
schema change: NO
```

`RenderedIntervalsWorkout`은 plain record이며 `@Entity`가 아니다.

## Provider boundary

```text
StructuredWorkout provider-neutral: YES (Phase 5C-1 그대로, 이번 Phase에서 변경 없음)
Renderer contains Intervals syntax: YES (당연히 — 이 계층이 그 지식을 처음 담는 계층)
Domain contains Intervals syntax:   NO
Domain contains Garmin syntax:      NO
```

`com.runningai.training` 패키지의 어떤 클래스도 이번 Phase에서 수정하지 않았다 — Intervals/Garmin
지식은 전부 `com.runningai.integration.intervals`에만 존재한다.

## Legacy

```text
legacy production files modified: NO
```

`C:\running-ai`는 이번 Phase에서 열람하지 않았다(Phase 5C-0에서 이미 조사 완료, 재조사하지 않음 —
지시서 4절). `intervals-structured-workout.ps1`은 reference only로 남아 있으며 Java renderer와 병행
구현 상태다(지시서 46절).

## Known issues

```text
Pace Garmin mismatch: UNRESOLVED (그대로 유지) — Intervals 서버에는 pace target이 반영되지만
                       실제 Garmin 기기에서 "목표 없음"으로 보인 사례(event 138398536)의 원인은
                       여전히 미확정. 이번 Phase의 renderer가 legacy와 동일한 text를 만든다고
                       해서 이 문제가 해결됐다고 주장하지 않는다.
%LTHR Garmin validation: ASSUMED (그대로 유지) — %LTHR+hr=1s 텍스트가 Intervals 서버 readback에서
                       보존되는 것은 legacy 근거로 확인됐지만, 실제 Garmin 기기 화면 표시는
                       여전히 검증되지 않았다. 실기기 검증은 Phase 5C-4에서 수행한다.
Intervals→Garmin transport: 여전히 repo 밖에서 이루어지며 이 코드베이스는 관측/제어할 수 없다.
```

## Git

```text
branch: main
commit: (아래 참고)
push: 완료
```

## 다음 Phase (제안, 자동 시작 안 함)

```text
Phase 5C-3
IntervalsWorkoutPublisher
  - HTTP client, 인증(환경변수 기반, legacy와 동일 원칙)
  - create/update, marker 기반 idempotency (legacy의 command_id 상태 머신을 Spring 자체
    식별자로 이식)
  - readback 검증(서버가 되돌려준 step 수/타입이 기대와 일치하는지 최소 확인)
  - renderer 로직은 재구현하지 않음(이번 Phase 산출물을 그대로 사용)

Phase 5C-4
실제 Garmin Forerunner 265 E2E 검증
  - pace target 표시 일관성(UNRESOLVED 상태 해소 시도)
  - %LTHR+hr=1s 실기기 표시 여부(ASSUMED 상태 해소 시도)
  - treadmill cue 표시 여부
```
