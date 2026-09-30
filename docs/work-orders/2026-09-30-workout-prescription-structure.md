# 2026-09-30 — Phase 5B-1: Workout Prescription Structure

| 항목 | 내용 |
|------|------|
| 날짜 | 2026-09-30 |
| 작업 | 추천 intent를 정확한 총 시간 + warm-up / main / cool-down 구조로 변환, `GET /api/v1/workout-prescription` |
| 상태 | 완료 |
| 커밋 | `feat: add workout prescription structure` |
| 지시서 원문 | [2026-09-30-workout-prescription-structure-instruction.md](2026-09-30-workout-prescription-structure-instruction.md) |
| 이전 작업 | [2026-09-30-workout-recommendation-model.md](2026-09-30-workout-recommendation-model.md) |

## 1. 5A invariant 확인과 최소 수정 (작업 시작 전)

확인 결과 5A `WorkoutRecommendationPolicy`의 방어 분기(`else`)는 후보가 `[QUALITY]`뿐이거나 `[QUALITY, LONG]`처럼 EASY/CROSS_TRAINING/RECOVERY/REST가 하나도 없을 때 **후보에 없는 `REST`를 조용히 반환**했다(`candidates.contains(RECOVERY) ? RECOVERY : REST`). 지시서 5번이 금지한 “candidate 밖 fallback”이다. 최소 수정: 방어 분기가 `RECOVERY` → `REST` 순으로 **후보 안에서만** 고르고, 둘 다 후보에 없으면 `IllegalStateException("No selectable workout intent among candidates ...")`을 던진다(500 `INTERNAL_SERVER_ERROR`, 내부 invariant 위반). 4C가 만드는 후보 집합에서는 도달하지 않으므로 기존 동작·응답은 그대로다. 테스트로 고정: `[QUALITY]`, `[QUALITY, LONG]`은 예외, `[QUALITY, REST]`→REST, `[QUALITY, RECOVERY]`→RECOVERY(후보 안). 기존 5A 테스트 1개를 이 의미로 교체했고 나머지 5A 테스트는 무수정 통과.

## 2. 범위와 구조

```text
WorkoutRecommendation -> WorkoutPrescriptionService -> WorkoutPrescriptionPolicy -> WorkoutPrescription
```

`com.runningai.training`에 추가: `WorkoutPrescription`, `WorkoutSegment`, `SegmentType`(WARM_UP, MAIN, COOL_DOWN, REST), `WorkoutPrescriptionPolicy`(package-private, 모든 규칙), `WorkoutPrescriptionService`, `WorkoutPrescriptionController`. `IntensityClass`는 5A 것을 재사용했다. `common.exception`에 `UnprocessableRequestException`(code + message, `GlobalExceptionHandler`에서 422)을 추가했다. Service는 `WorkoutRecommendationService`에만 의존하며(Repository, `TrainingDecisionContextService`, Clock, Garmin 접근 없음) `asOfDate`는 recommendation 값을 그대로 쓴다. migration·persistence·cache 없음.

## 3. Duration 선택

- 기본값: REST 0 / RECOVERY 30 / EASY 45 / LONG 90 / CROSS_TRAINING 45. **총 시간 = clamp(기본값, `durationMinMinutes`, `durationMaxMinutes`)**. 범위가 source of truth이며 정수 분만 사용한다(초 변환은 5C).
- 범위 위반은 조용히 보정하지 않는다: `min < 0` 또는 `min > max`, 총 시간이 0인데 REST가 아닌 경우(MAIN 0), REST 범위가 0을 허용하지 않는 경우는 `IllegalStateException`(내부 오류).

## 4. Segment 배분 (정수 연산, 프레임워크 없음)

| intent | warm-up | cool-down | main |
|--------|---------|-----------|------|
| RECOVERY | 5 | 5 | 나머지 |
| EASY | 총 시간의 약 20% | 약 10% | 나머지 |
| LONG | min(10, 총 시간의 약 10%) | warm-up과 동일 | 나머지 |
| CROSS_TRAINING | 5 | 5 | 나머지 |
| REST | REST segment 1개 (0분, NONE) | - | - |

“약 N%”는 5분 단위로 반올림(0.5는 올림)한다: EASY 45분 → 9→10, 4.5→5 → 10/30/5. 짧은 총 시간에서는 warm-up과 cool-down이 각각 총 시간의 1/4을 넘지 않도록 잘라 MAIN이 항상 절반 이상이 되게 한다. EASY 35분(범위 30~35) → 5/25/5. 생성 직후 방어 검증: segment 합 = 총 시간, 모든 segment >= 0, REST 외 MAIN > 0, REST 총 0, 총 시간 ∈ [min, max].

Intensity: warm-up/cool-down `VERY_EASY`; MAIN은 RECOVERY `VERY_EASY`, EASY·LONG·CROSS_TRAINING `EASY`. Description은 고정 문자열(“Easy warm-up”, “Steady easy running”, “Easy cool-down” 등), CROSS_TRAINING main은 “modality not specified”이며 종목을 고르지 않는다. Summary는 `"<N>-minute <kind> session with warm-up, steady main work, and cool-down."` 템플릿, REST는 “Rest day: no training is prescribed.” 영어 고정 문장(locale 프레임워크 없음), LLM 호출 없음.

## 5. QUALITY

Prescription을 만들지 않는다. `recommendedIntent = QUALITY`면 `UnprocessableRequestException("QUALITY_PRESCRIPTION_NOT_SUPPORTED")` → `422`와 `{code, message, timestamp}`. 5A는 QUALITY를 선택하지 않으므로 production 경로에서는 발생하지 않는다.

## 6. API

`GET /api/v1/workout-prescription[?date=YYYY-MM-DD]` — 날짜 없으면 athlete-local 오늘(recommendation을 통해), 잘못된 값은 `400 INVALID_REQUEST`. 응답: `asOfDate`, `intent`, `totalDurationMinutes`, `segments[{type, durationMinutes, intensityClass, description}]`, `summary`, `recommendation`(5A 전체, decisionContext 포함). pace, heartRate, lthr, incline, repeat, distanceTarget, steps 등 필드는 없다(테스트가 부재 확인). 기존 API 전부 무변경.

## 7. Tests

```text
Java: clean test → 268 total, 268 passed, 0 failed   (기존 240 + 신규 28, 5A 테스트 1개는 교체)
Python: 변경 없음, 실행 안 함
```

- **policy(순수)** 14개: REST(REST segment 1개, 0/NONE), RECOVERY 30 = 5/20/5, EASY 45 = 10/30/5, LONG 90 = 10/70/10, CROSS_TRAINING 45 = 5/35/5(종목 이름 없음), QUALITY 422 코드, 좁은 범위 30~35 → 35 = 5/25/5, 범위가 기본값보다 높으면 최소값(RECOVERY 40~60 → 40), 넓은 범위 30~90 → 45 유지, 잘못된 범위(min>max, 음수, MAIN 0, REST 범위) 예외, **4개 intent × 약 10,000+ 범위 조합**에서 총 시간 ∈ 범위 / segment 합 = 총 시간 / 음수 없음 / MAIN > 0 / 순서 WARM_UP-MAIN-COOL_DOWN / recommendation 그대로 포함, 기본 범위에서 intent·asOfDate 보존, intensity 매핑, 결정성.
- **service(DB)** 8개: 빈 이력 EASY 45, 어제 long → RECOVERY 5/20/5, long + 연속 활동 → REST, long due → LONG 10/70/10, 26개 as-of 날짜에서 nested recommendation이 `WorkoutRecommendationService` 결과와 동일하고 intent·범위·합 유지, 결정성, 과거 날짜에서 미래 long run 미반영, athlete-local 오늘.
- **API** 5개(고정 Clock 2026-09-30 01:00 Seoul): 기본 날짜 RECOVERY 구조 전체 필드, 과거 날짜 look-ahead 없음(EASY 10/30/5), 금지 필드 부재(4개 위치 × 14개 이름), `/workout-recommendation` 무변경, malformed date 400. 별도 클래스 1개: `WorkoutRecommendationService`를 mock해 QUALITY를 주입 → 422 `QUALITY_PRESCRIPTION_NOT_SUPPORTED`, segments 없음.
- **5A invariant**: QUALITY-only 후보는 예외, 후보 밖 intent 없음(위 1절).

## 8. Database / Garmin

```text
migration: NO   schema change: NO   persistence: NO (요청마다 계산, cache 없음)
Garmin: network·connector·activity_raw 의존 없음, rendering 없음
```

## 9. Known limitations

- 정확한 시간은 heuristic 기본값이며 athlete별 적응 없음(과거 활동 시간과 무관).
- pace / HR / LTHR / treadmill speed·incline 모델 없음, interval·repeat 구조 없음, QUALITY 구조 없음.
- cross-training 종목 미지정. 시간은 정수 분.
- 5A 방어 분기 수정 외에 5A 동작 변경 없음.

## 10. Next Phase (자동 시작 안 함)

Phase 5B-2 — Intensity Target Model: pace / HR / LTHR 연동 / treadmill speed·incline / 목표 선택과 fallback. 그 이후 Phase 5C — Intervals.icu / Garmin structured workout rendering.
