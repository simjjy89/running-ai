# 2026-09-30 — Phase 5B-2: Pace / Heart Rate / LTHR / Treadmill Speed / Incline Target Model

| 항목 | 내용 |
|------|------|
| 날짜 | 2026-09-30 |
| 작업 | `AthleteIntensityProfile` persistent domain 추가, 5B-1 `WorkoutPrescription` 위에 pace/%LTHR HR/트레드밀 speed·incline target을 얹는 `TargetedWorkoutPrescription` 구현 |
| 상태 | 완료 |
| 커밋 | `feat: add workout intensity target model` |
| 지시서 원문 | [2026-09-30-workout-intensity-target-model-instruction.md](2026-09-30-workout-intensity-target-model-instruction.md) |
| 이전 작업 | 830b2ba `feat: add workout prescription structure` |

---

## 1. 목적과 범위

Phase 5B-1까지 `WorkoutPrescription`은 정확한 시간의 warm-up/main/cool-down 구조를 만들지만 숫자 강도
target(pace, 심박, 트레드밀 speed/incline)은 없었다. 이번 Phase는 5B-1 `WorkoutPrescription`을 전혀
변경하지 않고 그 위에 `TargetedWorkoutPrescription`을 얹었다. 새로 저장되는 것은 athlete의 LTHR/threshold
pace(`AthleteIntensityProfile`, 최대 1행/athlete)뿐이며, 계산된 target 자체는 저장하지 않는다(매 요청마다
현재 profile 기준으로 재계산). interval/repeat 구조, QUALITY workout, Garmin/Intervals.icu rendering,
Garmin LTHR 자동 수집은 이번 범위 밖이다.

## 2. 작업 전 상태

```text
branch: main (origin/main과 동일, working tree clean)
latest commit: 830b2ba feat: add workout prescription structure
regression baseline: Java 268 passed
```

## 3. Athlete Intensity Profile

```text
LTHR: lactateThresholdHeartRateBpm, bpm, nullable
threshold pace: lactateThresholdPaceSecondsPerKm, seconds/km, nullable
persistence: athlete_intensity_profile 테이블 (V5 migration), athlete당 1행,
             uk_athlete_intensity_profile_athlete UNIQUE(athlete_id), FK athlete
API: GET/PUT /api/v1/athlete/intensity-profile (com.runningai.athlete 패키지)
```

- PUT은 **전체 교체**다: 두 필드 모두 optional이고, 요청에서 필드를 `null`로 보내면 그 metric을 명시적으로
  "미설정"으로 되돌린다(부분 patch가 아님).
- Validation: `@Positive`(둘 다 optional이므로 null은 통과, `<= 0`만 `400 VALIDATION_ERROR`) + entity 내부의
  defensive guard(직접 서비스 호출 시에도 `IllegalArgumentException`).
- Garmin 자동 LTHR 수집 없음: 이번 Phase는 Garmin connector/`activity_raw`를 전혀 건드리지 않는다.
- 실제 사용자 LTHR/pace 값은 코드·테스트·문서 어디에도 없다. 모든 예시/테스트 값은 synthetic이다
  (예: LTHR 160/170 bpm, threshold pace 290/300/305 초/km — 실제 athlete 값이 아님).

## 4. Target Selection

```text
PACE priority: threshold pace가 있으면 항상 PACE가 primary (HR도 있으면 함께 populate)
HR fallback: threshold pace 없고 LTHR만 있으면 HEART_RATE
qualitative fallback: 둘 다 없거나 intensity class에 정의된 band가 없으면 QUALITATIVE (prescription은 실패하지 않음)
REST: 항상 NONE (pace/HR/treadmill 전부 null)
CROSS_TRAINING: profile 존재 여부와 무관하게 모든 segment가 항상 QUALITATIVE
                (running threshold를 cycling에 적용하지 않음)
```

Pace를 HR보다 우선하는 이유는 향후 outdoor pace / 트레드밀 km/h / Garmin pace target으로 직접 변환하기
가장 쉽기 때문이며, 생리학적 우월성을 의미하지 않는다(README에 명시).

## 5. Pace Model

```text
VERY_EASY: fast = threshold pace × 1.25, slow = threshold pace × 1.45
EASY:      fast = threshold pace × 1.15, slow = threshold pace × 1.30
rounding:  Math.round로 가장 가까운 정수 초로 반올림 (동일 input → 동일 output)
```

`fastSecondsPerKm`/`slowSecondsPerKm`으로 명명해 "숫자가 작을수록 빠르다"는 혼동을 피했다(`min`/`max` 사용
안 함). `VERY_EASY`/`EASY` 외 intensity class(예: `HARD`, `MODERATE`, `NONE`)는 band가 정의되어 있지 않아
`null`을 반환한다(5B-1이 QUALITY 외 intent에서 이 두 class만 생성하기 때문).

## 6. Heart Rate Model

```text
VERY_EASY: 65% ~ 78% LTHR
EASY:      75% ~ 85% LTHR
rounding:  Math.round(LTHR × percent / 100), min <= max 항상 보장
```

## 7. Treadmill

```text
speed formula: speedKph = 3600 / paceSecondsPerKm
               minSpeedKph = 3600 / slowSecondsPerKm, maxSpeedKph = 3600 / fastSecondsPerKm
               (pace와 speed는 반대 방향이므로 slow→min, fast→max)
speed rounding: BigDecimal.setScale(1, HALF_UP) — 예: 11.25(exact) → 11.3, 10.714... → 10.7
warm-up incline: 0.0% ~ 0.5%
main incline:    0.5% ~ 1.0%
cool-down incline: 0.0% ~ 0.5%
```

Incline은 pace profile 유무와 무관하게 running segment(REST 아님, CROSS_TRAINING 아님)면 항상 제공되고,
speed는 pace target이 없으면 `null`이다. Incline 수치는 야외 달리기와 동일한 생리학적 부하를 의미하지 않는
RunningAI treadmill 운영 기본값이다(README에 명시).

## 8. API

```text
GET profile:         200, profile 없으면 {"initialized": false, ...: null} (404 아님)
PUT profile:         200, 전체 교체, LTHR/threshold pace <= 0 → 400 VALIDATION_ERROR
GET workout targets: /api/v1/workout-intensity-targets[?date=YYYY-MM-DD], 200,
                     TargetedWorkoutPrescription(asOfDate, intent, totalDurationMinutes, segments,
                     targetAvailability, profile, prescription). QUALITY 추천은 기존과 동일하게
                     422 QUALITY_PRESCRIPTION_NOT_SUPPORTED. 잘못된 date는 400 INVALID_REQUEST.
```

## 9. Tests

```text
Java total: 314
passed: 314
failed: 0
```

Baseline 268 → 314 (+46 신규 테스트). 분해:

```text
profile:        AthleteIntensityProfileServiceTest(8) + AthleteIntensityProfileApiTest(5)
pace/HR/speed/
incline:        RunningIntensityTargetPolicyTest(13, 순수 conversion 규칙)
fallback/
cross-training/
REST:           WorkoutIntensityTargetPolicyTest(9, primary 선택/가용성/REST/CROSS_TRAINING/결정론)
historical:     WorkoutIntensityTargetServiceTest(5, profile 즉시 반영 + 과거 date도 현재 profile 사용)
API/QUALITY:    WorkoutIntensityTargetApiTest(6) + WorkoutIntensityTargetQualityApiTest(1)
migration:      SchemaMigrationTest에 V5 + unique constraint 테스트 1개 추가
```

Python connector는 변경하지 않았으므로 재실행하지 않았다(지시서 84절).

## 10. Database

```text
migration: YES (V5__create_athlete_intensity_profile.sql)
schema: athlete_intensity_profile (id, athlete_id UNIQUE FK, lactate_threshold_heart_rate_bpm nullable,
        lactate_threshold_pace_seconds_per_km nullable, created_at, updated_at). CHECK 제약은 기존
        V1~V4 스타일(미사용)을 따라 넣지 않았다 — 값 검증은 DTO(@Positive)와 entity 양쪽에서 수행.
PostgreSQL validation: 완료. 로컬 Docker Compose PostgreSQL(`running-ai-postgres`)에 실제로
        `./gradlew bootRun --spring.profiles.active=local`을 실행해 V5가 정상 적용됨을 확인했다
        ("Migrating schema \"public\" to version \"5 - create athlete intensity profile\"" 로그 +
        `\d athlete_intensity_profile`로 컬럼/제약 구조 확인).
```

V1~V4는 수정하지 않았다.

## 11. Garmin

```text
network dependency: 없음 (이번 Phase는 Garmin connector/network를 전혀 호출하지 않음)
auto LTHR ingestion: 없음 (LTHR/threshold pace는 오직 GET/PUT /api/v1/athlete/intensity-profile로만 관리)
```

## 12. Secrets / diff review

- 실제 Garmin/athlete LTHR·threshold pace 값: 코드·테스트·문서·work-order 어디에도 없음. 모든 수치는
  synthetic(예: 160, 170, 300 등)이며 이는 지시서 예시 수치와도 우연히 겹칠 수 있으나 실제 사용자 데이터가
  아니다.
- `git diff`로 변경 파일을 검토해 Garmin credential, `.env`, 빌드 산출물, IDE 파일이 없음을 확인했다.
- secrets grep(`password|token|secret|api_key|GARMIN_|INTERVALS_`)에 매칭되는 신규 내용 없음.

## 13. Documentation 갱신

`README.md`에 "Athlete Intensity Profile"과 "Workout Intensity Targets" 절을 추가했다(API 표, PUT의
전체-교체 의미, target 우선순위, pace/HR heuristic 수치와 "초기 scheduling heuristic이며 생리학적으로
검증된 값이 아님" 명시, treadmill speed 공식/rounding, incline 운영 기본값, 알려진 한계). `CLAUDE.md`의
Architecture 절에서 "intensity targets"를 planned에서 implemented로 옮기고 새 endpoint 2개를 반영했다.

## 14. Git

```text
branch: main
commit: (아래 커밋 참고)
push: 완료
```

## 15. Known Limitations

```text
QUALITY target 없음 (QUALITY prescription 자체가 여전히 422로 unsupported)
running threshold only (cycling/다른 modality의 threshold 모델 없음)
profile history 없음 (과거 date 조회도 항상 현재 profile 사용)
race pace 없음
RPE model 없음
pace/HR heuristics는 실제 데이터로 튜닝되지 않은 초기값
treadmill incline은 운영 기본값일 뿐 생리학적 outdoor-equivalent 보장 없음
```

## 16. Next Phase (제안, 자동 시작 안 함)

Phase 5C — Structured Workout Rendering:

```text
TargetedWorkoutPrescription
  → Intervals.icu workout
  → Garmin compatible structured steps
```

실제 renderer 구현 전에 기존 legacy RunningAI(main PC)의 Intervals.icu/Garmin renderer를 먼저 조사해야
한다(지시서 명시).
