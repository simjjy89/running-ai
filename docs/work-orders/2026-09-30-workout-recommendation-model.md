# 2026-09-30 — Phase 5A: Workout Recommendation Model

| 항목 | 내용 |
|------|------|
| 날짜 | 2026-09-30 |
| 작업 | 오늘의 workout intent 하나 + duration range / intensity class / confidence / data sufficiency / reasons / summary, `GET /api/v1/workout-recommendation` |
| 상태 | 완료 |
| 커밋 | `feat: add workout recommendation model` |
| 지시서 원문 | [2026-09-30-workout-recommendation-model-instruction.md](2026-09-30-workout-recommendation-model-instruction.md) |
| 이전 작업 | [2026-09-30-training-decision-context.md](2026-09-30-training-decision-context.md) |

## 1. 범위와 구조

```text
TrainingDecisionContext -> WorkoutRecommendationService -> WorkoutRecommendationPolicy -> WorkoutRecommendation
```

`com.runningai.training`에 추가: `WorkoutRecommendation`(record), `WorkoutRecommendationService`, `WorkoutRecommendationPolicy`(package-private, 규칙 전부), `WorkoutRecommendationController`, enum 4개(`IntensityClass`, `RecommendationConfidence`, `DataSufficiency`, `WorkoutRecommendationReason`). **Intent enum은 새로 만들지 않고 4C의 `CandidateTrainingType`을 재사용**했다. Service는 `TrainingDecisionContextService`만 의존한다(ActivityRepository, TrainingLoadService 직접 접근 없음). 기존 코드 변경 없음, 설정 property 추가 없음(숫자는 policy 상수), migration·persistence·cache·Garmin·LLM 없음.

## 2. Selection precedence

첫 번째로 맞는 규칙이 선택되며, 선택된 intent는 반드시 context의 `candidateTrainingTypes`에 있어야 한다. 후보 목록의 순서나 첫 항목은 쓰지 않는다.

| # | 조건 | intent |
|---|------|--------|
| 1 | (어제·오늘 long run **그리고** 연속 활동 2일 이상) **또는** 연속 활동 4일 이상 | REST |
| 2 | 어제·오늘 long run **또는** 연속 활동 3일 이상 | RECOVERY |
| 3 | 28일간 활동 없음 (`LIMITED_HISTORY`) | EASY |
| 4 | long run due (아래) | LONG |
| 5 | 기본 | EASY (`DEFAULT_EASY`) |
| 6 | EASY가 후보에 없을 때만 | CROSS_TRAINING |

- **long run due**: `daysSinceLongRun >= 6`(알려진 이전 long run 필요) **그리고** dataSufficiency HIGH **그리고** 연속 활동 <= 2일 **그리고** loadTrend != INCREASING **그리고** `daysSinceRunning <= 3`. 숫자는 캘린더 배치 heuristic이며 생리학적 회복 보장이 아니다. 같은 7일에 long을 두 번 넣지 않는다(`daysSinceLongRun <= 1`이면 규칙 2 이전에 LONG이 배제됨).
- **QUALITY는 선택하지 않는다.** 4C가 quality 세션을 분류하지 못하므로(후보에 있어도) 초기 모델은 QUALITY를 자동 선택하지 않는다. 후보에 QUALITY가 있으면 `QUALITY_HISTORY_UNAVAILABLE` reason을 붙인다. enum/API에는 유지된다.
- **CROSS_TRAINING**: 4C 후보 규칙상 EASY는 항상 후보에 있으므로 실제 4C 입력에서는 도달하지 않는 안전 fallback이다(정책 단위 테스트로만 검증). 지시서 50번(“최근 cycling만 있으면 기본은 EASY”)을 따랐다. 어떤 경우에도 intent는 null이 아니다(마지막 방어 분기는 RECOVERY 또는 REST).
- `acuteChronicRatio`, monotony, strain 등 4B 수치에는 어떤 threshold도 걸지 않았다. loadTrend는 4C의 서술 라벨을 그대로 쓴다(INCREASING이면 LONG 보류, UNKNOWN이면 sufficiency가 HIGH가 될 수 없어 LONG 보류).

### 지시서와 다르게 정한 두 곳

1. **LONG due에서 `daysSinceLongRun == null`은 LONG이 아니다.** 지시서 19번은 “null or >= 6”이지만, 46번 예시(long 이력 없는 충분한 history의 쉬고 난 다음 날 → EASY)와 65번 테스트가 충돌한다. long run 이력이 한 번도 없으면 습관이 없으므로 LONG을 자동 선택하지 않는 쪽을 택했다(47번은 “lastLongRun > 6 days ago”).
2. **28번 예시(“LONG_RUN_RECENT + MULTIPLE_ACTIVE_DAYS → RECOVERY, HIGH”)는 15번·45번·64번(“long 어제 + 연속 활동 → REST”)과 충돌**한다. 명시적 테스트 요구인 64번을 따라 이 조합은 REST다. RECOVERY는 long run 직후(연속 활동 1일 이하)이거나 long 없이 연속 3일일 때 선택된다.

## 3. Duration / Intensity (정적 매핑, 범위만)

| intent | 분 | intensity |
|--------|----|-----------|
| REST | 0 - 0 | NONE |
| RECOVERY | 20 - 40 | VERY_EASY |
| EASY | 30 - 60 | EASY |
| QUALITY | 30 - 70 | HARD |
| LONG | 75 - 120 | EASY |
| CROSS_TRAINING | 30 - 60 | EASY |

정확한 시간 선택은 Phase 5B 몫이다. intensity class는 pace/HR/LTHR/RPE 모델이 없는 정성 라벨이다.

## 4. Data sufficiency / Confidence

- **DataSufficiency** (`recentPattern`의 활동일 수 기준, 기본 14일): LOW = 28일간 활동 없음 또는 활동일 3일 미만 / HIGH = 활동일 6일 이상 **그리고** loadTrend 정의됨(직전 주 존재) / MEDIUM = 그 사이.
- **Confidence** (규칙과 데이터가 얼마나 명확한가, 정답 확률 아님): REST·RECOVERY 규칙 = HIGH(단 sufficiency LOW면 MEDIUM) / LONG due = MEDIUM / 기본 EASY = sufficiency HIGH면 MEDIUM, 아니면 LOW / limited history EASY·CROSS_TRAINING·방어 분기 = LOW. 기본 EASY는 대안이 명확히 배제된 것이 아니므로 HIGH가 되지 않는다.

## 5. Reasons / Summary

`WorkoutRecommendationReason`(선언 순서로 출력, 중복 없음): `RECENT_LONG_RUN`, `MULTIPLE_ACTIVE_DAYS`(연속 3일 이상), `RECENT_REST`, `LOAD_TREND_INCREASING|STABLE|DECREASING`(UNKNOWN은 reason 없음), `LOW_RECENT_ACTIVITY`, `NO_RECENT_RUNNING`, `RECENT_CYCLING`(4C reason 전달), `LONG_RUN_DUE`, `LIMITED_HISTORY`, `QUALITY_HISTORY_UNAVAILABLE`, `DEFAULT_EASY`. `RECENT_CYCLING`은 지시서 예시 목록에 없지만 50번 시나리오를 설명하기 위해 4C reason을 그대로 전달하도록 추가했다.

summary는 분기마다 고정된 영어 템플릿이다(locale 전략이 아직 없어 지시서 31번에 따라 영어 deterministic). 회복 완료, 부상 위험, 과훈련, 의학적 안전 같은 표현을 쓰지 않으며 테스트가 금지어를 검사한다. LLM 호출 없음.

## 6. API

`GET /api/v1/workout-recommendation[?date=YYYY-MM-DD]` — date 없으면 athlete-local 오늘, 잘못된 값은 `400 INVALID_REQUEST`. 응답: `asOfDate`, `recommendedIntent`, `durationMinMinutes`, `durationMaxMinutes`, `intensityClass`, `confidence`, `dataSufficiency`, `reasons`, `summary`, `decisionContext`(4C 전체, explainability 우선). steps, warmup, cooldown, repeatCount, pace/HR target, incline, intervalDistance 필드는 없다(API 테스트가 부재를 확인). 기존 API는 모두 무변경.

## 7. Tests

```text
Java: clean test → 240 total, 240 passed, 0 failed   (기존 207 + 신규 33: policy 19, service 9, API 5)
Python: 변경 없음, 실행 안 함
```

- **policy(순수 규칙)**: 어제 long → RECOVERY(20-40, VERY_EASY, HIGH) / long + 연속 활동 → REST(0/0/NONE), long + 2일 활동도 REST / 4일 REST·3일 RECOVERY / 이력이 희박하면 restrictive confidence가 MEDIUM으로 제한 / 쉬는 날 정상 → EASY(MEDIUM, sufficiency HIGH, reasons 정확히 일치) / 후보 순서를 뒤집어도 같은 결과 / long due(7일) → LONG(75-120), 경계 6일 vs 5일 / due 조건 하나씩 실패(INCREASING, UNKNOWN, 3일 넘게 러닝 없음, long 이력 없음, 희박한 history, 연속 3일) 시 LONG 보류, DECREASING은 유지 / limited history → EASY, LOW/LOW / cycling만 있는 이력 → EASY / EASY 후보 없음 → CROSS_TRAINING / QUALITY만 후보여도 QUALITY 미선택, reason 유무 / data sufficiency 경계값 / 6개 intent의 duration·intensity 매핑 / **4 후보 세트 × 6 daysSinceLong × 6 연속일수 × 4 trend × 4 활동일수 = 2,304개 context**에서 항상 후보 안의 intent, QUALITY 아님, 두 번 계산해 동일, reasons 중복·순서, summary 비어 있지 않고 금지어 없음.
- **service(DB)**: 빈 이력, 어제 long → RECOVERY, long + 연속 5일 → REST, 정상 rested → EASY(QUALITY·LONG이 후보인데도, reasons 정확히 일치), long due → LONG, 26개 as-of 날짜에서 후보 소속·QUALITY 아님, 결정성, 과거 날짜에서 미래 long run 미반영, athlete-local 오늘(`09-29T15:30Z`가 09-30 활동).
- **API**(고정 Clock 2026-09-30 01:00 Seoul): 기본 날짜 REST + nested context 직렬화(enum 문자열), workout 구조 필드 부재, 과거 날짜 look-ahead 없음, `/training-decision-context` 무변경, malformed date 400.

## 8. Database / Garmin

```text
migration: NO   schema change: NO   persistence: NO (요청마다 계산, cache 없음)
Garmin: network·connector·activity_raw 의존 없음, 외부 모델 호출 없음
```

## 9. Known limitations

- quality-session 모델 없음 → QUALITY 미선택. pace / HR zone / LTHR / RPE 모델 없음.
- 정확한 workout 시간 선택, workout steps 없음. race goal 인식 없음. readiness/recovery 모델 없음.
- 모든 숫자(6일, 연속 2/3/4일, 3일, 활동일 3/6일)는 스케줄링 heuristic이며 의학·부상 위험 기준이 아니다. 실제 사용 데이터로 튜닝된 값이 아니다.
- long run 이력이 없는 사용자는 LONG이 자동 선택되지 않는다.
- summary는 영어 템플릿 하나뿐이다.

## 10. Next Phase (자동 시작 안 함)

Phase 5B — Workout Prescription: `WorkoutRecommendation` → 정확한 시간 → workout 구조(warm-up / main / cooldown) → pace/HR/intensity target 모델. Garmin / Intervals.icu rendering은 그 이후.
