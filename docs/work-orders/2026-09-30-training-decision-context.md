# 2026-09-30 — Phase 4C: Training Decision Context

| 항목 | 내용 |
|------|------|
| 날짜 | 2026-09-30 |
| 작업 | 최근 훈련 패턴, 경과일, 연속 활동/휴식일, load trend, 후보 훈련 종류, decision reasons + `GET /api/v1/training-decision-context` |
| 상태 | 완료 |
| 커밋 | `feat: add training decision context` |
| 지시서 원문 | [2026-09-30-training-decision-context-instruction.md](2026-09-30-training-decision-context-instruction.md) |
| 이전 작업 | [2026-09-30-training-state.md](2026-09-30-training-state.md) |

## 1. 범위와 구조

```text
Activities(28일, 쿼리 1회) -> TrainingLoadService.aggregate -> 일별 load 28개
                           -> TrainingStateService.compute   -> TrainingState (4B 그대로)
                           -> TrainingDecisionContextService -> TrainingDecisionContext
```

`com.runningai.training`에 추가: `TrainingDecisionContextService`, `TrainingDecisionContext`, `DailyTrainingPattern`, enum 5개(`SessionClassification`, `ClassificationReason`, `LoadTrend`, `CandidateTrainingType`, `DecisionReason`), `TrainingProperties`, `TrainingDecisionContextController`.

4A 변경은 하나뿐이다: `TrainingLoadService.daily(from,to,zone)` 안의 “활동 → 일별 집계” 루프를 package-private `aggregate(from, to, zone, activities)`로 추출했다(동작 동일, 기존 테스트 무수정 통과). 이유: 일별 분류는 개별 활동(단일 run 90분 여부)이 필요한데 `daily()`는 합계만 주므로, 활동을 한 번만 읽고 같은 집계 로직을 재사용하기 위함이다. 새 repository query 없음(기존 범위 조회 재사용). 상한은 `asOfDate` 다음날 현지 00:00 미만이라 미래 활동이 들어올 수 없다.

## 2. Session classification

| 분류 | 조건 | reason |
|------|------|--------|
| `LONG` | 그날 RUN/TREADMILL_RUN **단일 활동** duration >= `long-run-min-duration`(기본 90m) | `DURATION_THRESHOLD` |
| `EASY_OR_GENERAL` | 러닝이 있으나 LONG 아님 (cycling이 함께 있어도) | `RUNNING_ACTIVITY` |
| `INDOOR_CYCLING` | cycling만 있음 | `CYCLING_ONLY` |
| `REST` | 지원 활동 없음 또는 load 0 | `NO_ACTIVITY` |
| `QUALITY_CANDIDATE` | **이번 Phase에서 부여하지 않음** | - |

우선순위 `LONG > QUALITY_CANDIDATE > EASY_OR_GENERAL > INDOOR_CYCLING > REST`. 같은 날 run 30 + cycling 45는 load 75, `EASY_OR_GENERAL`. LONG은 RunningAI heuristic이다.

**Quality 결정**: 정규화 Activity는 duration, distance, 평균 HR, 최대 HR만 가진다(lap, pace zone, 개인 LTHR 없음). 평균 pace와 HR만으로 interval/tempo를 확신할 수 없고 절대 bpm threshold는 금지이므로 quality 판별은 “사용 불가”로 표시했다: `qualityDetectionAvailable=false`, `lastQualityDate`/`daysSinceQuality`=null, quality-recent 규칙 없음. 고HR 45분 run이 quality로 분류되지 않음을 테스트로 고정했다. 나중에 lap/pace/LTHR 모델이 생기면 `classify()`와 후보 규칙에 추가한다.

## 3. History, consecutive, load trend

- 패턴 창 14일(`pattern-days`), 이력 창 28일(고정, 4B chronic 창과 동일). 28일 밖은 검색하지 않고 null.
- `lastRunningDate`(RUN·TREADMILL_RUN), `lastActiveDate`(지원 활동 전체), `lastLongRunDate`와 각 `daysSince*`(캘린더 일, 당일 0).
- `consecutiveActiveDays`/`consecutiveRestDays`: asOfDate부터 거꾸로 일별 load > 0 / == 0인 날을 센다(최대 28).
- loadTrend: 4B `weeklyLoadChangePercent` 재사용. null → UNKNOWN, > +band → INCREASING, < -band → DECREASING, 그 외 STABLE. band 기본 10%(`stable-band-percent`), ±정확히 10%는 STABLE. 설명용 라벨이며 안전 등급이 아니다.

## 4. Candidates / Reasons

`EnumSet`에 넣어 선언 순서(`REST, RECOVERY, EASY, QUALITY, LONG, CROSS_TRAINING`)로 출력하므로 순서가 결정적이고 중복이 없다. 규칙은 if/else 조건문이다(rule engine 없음).

| 우선순위 | 조건 | 후보 |
|---|------|------|
| 1 | 28일간 활동 없음 | REST, EASY, CROSS_TRAINING |
| 2 | `daysSinceLongRun <= 1` 또는 `consecutiveActiveDays >= 3` | REST, RECOVERY, EASY |
| 3 | `consecutiveRestDays >= 1` | EASY, QUALITY, LONG, CROSS_TRAINING |
| 4 | 그 외 | REST, RECOVERY, EASY, CROSS_TRAINING |

4번은 지시서가 정하지 않은 경우(오늘 활동 있음, 연속 1~2일, 최근 long 없음)이며 보수적으로 QUALITY·LONG을 제외했다. quality-recent 규칙은 quality를 판별하지 못하므로 구현하지 않았다.

Reasons: `LONG_RUN_RECENT`, `MULTIPLE_ACTIVE_DAYS`, `REST_DAY_RECENT`(asOf 당일 load 0), `LOAD_INCREASING`, `LOAD_DECREASING`, `LOW_RECENT_ACTIVITY`(이력은 있으나 최근 7일 활동 없음), `NO_RECENT_RUNNING`(28일 내 러닝 없음), `RECENT_CYCLING`(어제·오늘 cycling), `LIMITED_HISTORY`. `QUALITY_RECENT`는 구현되지 않아 enum에서 제외했다. context 단위 목록이며 후보별 근거는 없다.

## 5. API

`GET /api/v1/training-decision-context[?date=YYYY-MM-DD]` — date 없으면 athlete-local 오늘(주입된 Clock), 잘못된 값은 `400 INVALID_REQUEST`. 응답에 `trainingState`(4B와 동일 record)가 포함된다. 정의할 수 없는 값은 명시적 `null`. 기존 API(`/training-state` 포함)는 무변경.

설정(`application.yml`, `running-ai.training.*`): `classification.long-run-min-duration=90m`, `decision.stable-band-percent=10`, `decision.pattern-days=14`. 잘못된 값(0/음수 duration, 음수 band, pattern-days 범위 밖)은 기동 시 거부된다. `decision-history-days`는 4B의 28일 창과 묶여 있어 설정 항목으로 만들지 않았다.

## 6. Tests

```text
Java: clean test → 207 total, 207 passed, 0 failed   (기존 172 + 신규 35: 서비스 30, API 5)
Python: 변경 없음, 실행 안 함
```

- **분류**: 89분 vs 90분 경계, 하루 합산 100분(50+50)은 LONG 아님, TREADMILL_RUN 포함, run+cycling 합산·representative, cycling-only, long > easy > cycling 우선순위, 고HR run이 QUALITY로 분류되지 않음.
- **history/consecutive**: 빈 이력(LIMITED_HISTORY, REST/EASY/CROSS_TRAINING, UNKNOWN, 28 rest), 어제 long run(daysSince 1, LONG_RUN_RECENT, REST/RECOVERY/EASY), 2일 전 long(제한 해제), 연속 3일 활동(MULTIPLE_ACTIVE_DAYS), 연속 2일은 미해당, 4일 연속 휴식(daysSince 4, EASY/QUALITY/LONG/CROSS_TRAINING), 당일 활동 daysSince 0, cycling은 active지만 running 아님, LOW_RECENT_ACTIVITY, 28일 창 경계(09-02 제외 / 09-03 포함 → daysSince 27).
- **trend**: +15 INCREASING, +5 STABLE, -20 DECREASING, null UNKNOWN, ±10 정확히 STABLE, ±10.5, band 설정 변경, 실제 이력에서 +15%/-20%와 해당 reason, 내장 `trainingState`가 `TrainingStateService.state()`와 동일.
- **후보**: 여러 as-of 날짜(21개)에서 후보·reasons가 중복 없고 enum 순서, 같은 입력 → 같은 결과, 설정된 threshold(60분)/패턴 창(7일) 적용, 잘못된 설정 거부.
- **timezone / look-ahead**: `09-29T15:30Z`(UTC 날짜 09-29)는 09-30 현지 활동, `09-30T14:59:59Z`는 asOf 마지막 초 포함, `09-30T15:00Z`(10-01 00:00 KST)는 제외, 과거 asOf(09-26)에서 이후 활동이 recentPattern·last*·daysSince·후보·`trainingState` 어디에도 없음.
- **API**(고정 Clock 2026-09-30 01:00 Seoul): 기본 날짜(전 필드), 명시적 null, 과거 날짜에서 미래 활동 제외, `/training-state` 무변경, malformed date 400.

## 7. Database / Garmin

```text
migration: NO   schema change: NO   persistence: NO (요청마다 계산, cache 없음)
Garmin: network·connector·activity_raw 의존 없음, live validation 불필요
```

## 8. Known limitations

- Quality 세션 판별 불가(현재 정규화 필드 한계). pace / HR zone / LTHR 모델이 없다.
- Workout 세부 처방 없음, race goal 인식 없음, readiness·recovery·부상 위험 판정 없음. 후보는 “고려할 수 있는 종류”일 뿐 추천이 아니다.
- LONG(90분)과 loadTrend band(±10%)는 RunningAI heuristic이며 안전 기준이 아니다. 활동 시작 시각의 현지 날짜 기준, 자정을 넘긴 활동은 시작일에 배정.
- 후보 규칙 4번(연속 1~2일 활동)은 지시서에 명시가 없어 보수적으로 정한 값이다.

## 9. Next Phase (자동 시작 안 함)

Phase 5A — Workout Recommendation Model 후보: 이 context를 입력으로 하는 추천 모델 설계. 세부 세트/페이스/Garmin workout 생성은 그 이후.
