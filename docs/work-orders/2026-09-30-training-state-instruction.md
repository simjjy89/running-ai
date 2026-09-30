> 원본 작업지시서 (2026-09-30, Phase 4B). 구현 기록은 `2026-09-30-training-state.md` 참고.
> 추가 사용자 지시: CLAUDE.md, running-ai-dev 준수 (database skill은 필요 시) / Phase 4A `TrainingLoadService` 재사용 / acute·chronic, 7d progression, ramp, monotony, strain / measurement만 — READY/FATIGUED/DANGER 등 readiness·risk 판정이나 threshold 추가 금지 / Garmin network, DB aggregate persistence, cache, migration 금지 / 구현 → 전체 Java regression → README/결과 문서 → secrets 검사 → diff review → commit → push / Phase 4C 시작 금지 / 결과 보고는 한글.

# RunningAI Phase 4B
## Acute / Chronic Load / Ramp / Weekly Progression / Monotony / Strain

## 1. 현재 상태

완료: Phase 1 Spring Boot foundation, 2 PostgreSQL / Flyway, 3A Garmin ingestion, 3B Real Garmin E2E, 3C Sync / Scheduler / Runtime preparation, 4A Training Load Foundation. latest known commit `8fd18ce`, Java baseline 150 / 150.
Phase 4A: `TrainingLoadService`, `DailyTrainingLoad`, `WeeklyTrainingSummary`, `TrainingLoadSummary`, rolling 7/28-day, weekly Monday~Sunday, athlete timezone, duration-based load.

## 2. 이번 Phase 목표

저장된 normalized Activity와 4A load 계산 위에, 현재 athlete의 훈련 상태를 설명하는 derived metrics를 만든다: Acute Load, Chronic Load, Acute/Chronic Ratio, Previous 7-day Load, Weekly Load Change, Running Distance Change, Running Duration Change, Ramp, Monotony, Strain, TrainingState DTO.
READY / FATIGUED / OVERTRAINED / SAFE / DANGER / REST_REQUIRED 같은 판정은 하지 않는다.

## 3–4. 핵심 원칙 / Garmin 지표 복제 금지

Phase 4B = measurement / description, Phase 4C = decision / recommendation. 근거 없는 threshold를 “위험”으로 해석하지 않는다. Garmin Training Readiness / Acute Load / Load Focus / Training Status / Recovery Time / Firstbeat 독점 지표를 복제·추정하지 않고 RunningAI 자체의 단순하고 설명 가능한 지표만 사용한다.

## 5–6. Load source / TrainingStateService

새로운 독립 load 계산 금지. 반드시 기존 `TrainingLoadService`를 source로 재사용(load 정의: supported activity duration seconds / 60.0). `TrainingStateService`: TrainingLoadService 결과 조합, acute/chronic, weekly progression, ramp, monotony, strain, TrainingState 생성. ActivityRepository를 직접 중복 조회하지 않는 방향 우선(필요한 최소 API가 없으면 4A service를 자연스럽게 확장 가능).

## 7. TrainingState DTO 필드

asOfDate / acuteLoad, chronicLoad, acuteChronicRatio / current7DayLoad, previous7DayLoad, weeklyLoadChangePercent / runningDistance7DaysMeters, previousRunningDistance7DaysMeters, runningDistanceChangePercent / runningDuration7DaysSeconds, previousRunningDuration7DaysSeconds, runningDurationChangePercent / rampLoad / monotony, strain / activeDays7Days, restDays7Days. 필요 이상의 값은 넣지 않는다.

## 8–12. Acute / Chronic / Ratio

acuteLoad = 최근 7 calendar days load 합 (= Phase 4A load7Days, 중복 계산 금지). chronicLoad = 최근 28일 총 load / 4 (최근 4주 평균 주간 load; 28-day total과 혼동되지 않게 README·DTO 문서에 명시). acuteChronicRatio = acuteLoad / chronicLoad, chronicLoad == 0이면 억지로 0/Infinity가 아닌 null(프로젝트 DTO nullable convention). 0.8 = good, 1.2 = safe, 1.5 = dangerous 같은 threshold 금지, 숫자만 제공.

## 13–14. Current / Previous 7-day window

current = asOfDate 포함 최근 7일(D-6 ~ D, athlete timezone), previous = 직전 7일(D-13 ~ D-7). 예: current 09-24~09-30, previous 09-17~09-23. 시간 범위 `[fromInclusive, toExclusive)`.

## 15–20. Progression / Ramp

weeklyLoadChangePercent = (current7DayLoad - previous7DayLoad) / previous7DayLoad * 100. previous load = 0이면 Infinity 대신 null; previous=0, current=0도 null(0%는 “비교 기준이 있었다”는 의미가 되므로 피함). Running distance / duration 변화도 동일 공식, previous=0 → null. rampLoad = current7DayLoad - previous7DayLoad (load minutes, 복잡한 회귀 slope 아님). weeklyLoadChangePercent와 중복되는 ramp percent 필드는 만들지 않는다.

## 21–23. Daily load series

Monotony를 위해 최근 7일 각각의 load 필요. TrainingLoadService가 daily load series를 반환하도록 최소 확장. day는 athlete local date 기준(UTC date grouping 금지). local day에 supported normalized activity가 없거나 load 합이 0이면 daily load = 0이며 rest day도 monotony 계산에 포함(7개 값 모두 사용).

## 24–29. Monotony / Strain

monotony = meanDailyLoad / standardDeviationDailyLoad (최근 7 calendar days). 모집단 표준편차(N으로 나눔, sample N-1 아님). SD = 0(예: 30×7)이면 Infinity 대신 null(또는 명시적 unavailable). 전부 0인 주도 monotony = null, strain = null. strain = current7DayLoad × monotony, monotony null이면 null. monotony > 2 = 위험, strain > X = 위험 같은 규칙 금지.

## 30–33. Active / Rest days, 같은 날 여러 활동, calendar week와의 구분

activeDays7Days = 최근 7일 중 dailyLoad > 0인 날 수, restDays7Days = 7 - activeDays7Days. 같은 날 Run 30 + Cycling 45 = daily load 75, active day 1. current/previous 7day는 rolling 7-day이고 Phase 4A `WeeklyTrainingSummary`의 월~일 calendar week와 다름을 명확히 구분.

## 34–39. API / Serialization / Date / Clock / Timezone

`GET /api/v1/training-state?date=YYYY-MM-DD` (없으면 athlete local today). 응답은 위 필드의 JSON. 내부 double을 JSON number로 그대로 반환(display rounding은 UI/report layer 책임), 불필요한 formatting layer 금지. 잘못된 date는 `400 INVALID_REQUEST`(4A의 date parsing/error semantics 재사용). 4A의 Clock injection 재사용(`LocalDate.now()` 직접 호출 금지). Athlete timezone source 재사용(Asia/Seoul 하드코딩 금지).

## 40–43. Repository / Query 효율 / 최대 범위 / Daily series API

새 repository query를 추가하지 않는다. TrainingState 하나를 위해 ActivityRepository를 7-day / previous 7-day / 28-day / daily / distance / duration별로 반복 호출하지 않고 필요한 최대 범위(28일) 한 번 조회 후 memory aggregation. chronic 28일 + current/previous 7일이 28일 안에 모두 들어간다. `List<DailyTrainingLoad>` Java API는 가능하나 HTTP daily API는 추가하지 않는다.

## 44–47. Persistence / Migration / Cache / Determinism

training_state / acute_load / chronic_load 테이블 금지(derived calculation 유지), migration = NO, schema change = NO, cache·Redis 금지. 동일 activities / asOfDate / timezone이면 동일 TrainingState.

## 48–49. Empty history / acute-only

Activity 없음: acuteLoad 0, chronicLoad 0, ratio null, current7DayLoad 0, previous7DayLoad 0, weeklyLoadChangePercent null, running metrics 0, rampLoad 0, monotony null, strain null, activeDays 0, restDays 7. 최근 7일 100 + 이전 21일 0이면 acute 100, chronic 25, ratio 4 — 계산상 그대로 반환하고 위험 판정으로 해석하지 않는다.

## 50–65. 테스트

constant daily load 10×7(mean 10, SD 0, monotony null, strain null) / variable daily load 0,30,0,60,30,0,90(수동 계산한 mean, population SD, monotony, strain, floating tolerance) / rest days(activeDays 4, restDays 3) / 같은 날 run+cycling 합산·active day 1 / acute·chronic(28일 fixture: 7-day total, 28-day total / 4, ratio) / weekly progression positive(100→120: +20%, ramp +20) / negative(200→150: -25%, ramp -50) / previous zero(null) / both zero(null) / running distance progression에 cycling distance 제외 / TREADMILL_RUN 포함 / timezone boundary(UTC vs local에서 current 7-day, previous 7-day, daily series) / asOfDate 다음날 00:00 local activity 제외 / API default date(고정 Clock, athlete local today) / explicit date / malformed date 400.

## 66–70. Regression / 비의존 / 금지

기존 API 유지(`health`, `activities`, `garmin/sync`, `garmin/sync/status`, `training-load`, `training-load/weekly`, `actuator/health`). Garmin connector/network 호출 없음, Garmin scheduler 상태와 무관하게 동일 결과. `if (ratio > 1.5) return FATIGUED` 같은 코드 금지(Phase 4C scope). 부상 가능성 확률 등 injury prediction 금지.

## 71–77. README / 문서 / 검증

README에 `GET /api/v1/training-state`와 metric 정의: acute = 7-day load, chronic = 28-day load / 4, ratio = acute / chronic, ramp = current 7d - previous 7d, monotony = 7-day daily load mean / population SD, strain = 7-day load × monotony. Limitations 기록: duration-only load, no intensity weighting, no readiness interpretation, no injury-risk interpretation, monotony undefined when SD=0, percent change undefined when previous=0. 결과 문서 `2026-09-30-training-state.md`. `cd server; .\gradlew.bat clean test` baseline 150 + 신규 전부 PASS, Python 변경 없음, migration NO / schema change NO, 새 secret 없음이지만 diff review·secrets scan 수행.

## 78–79. Definition of Done / commit

TrainingStateService, TrainingState DTO, acute, chronic = 28d / 4, ratio + zero-denominator, current/previous 7-day load, weekly load change %, running distance/duration change %, ramp, 7-day daily series, active/rest days, population SD, monotony + zero-SD handling, strain, no readiness classification, no risk thresholds, `GET /api/v1/training-state`, athlete timezone, injected Clock, deterministic, no Garmin/persistence/cache/migration, service·API·timezone·boundary tests, full Java regression, README, result document, secrets scan, diff review, commit, push. 권장 commit `feat: add athlete training state metrics`.

## 80. 완료 보고 형식

Training State(acute, chronic, ratio, ramp) / Weekly Progression(current load, previous load, load change, running distance change, running duration change) / Monotony·Strain(daily window, SD, zero-SD behavior, monotony, strain, active/rest days) / API / Tests(Java total·passed·failed, timezone, boundary, monotony, progression) / Database(migration, schema change, persistence) / Garmin(network dependency) / Git / Known Limitations(duration-only load, intensity weighting 없음, readiness·recovery·injury risk 판정 없음) / Next Phase(자동 시작 금지; Phase 4C Training Recommendation Inputs / Decision Context: recent hard/easy/rest pattern, last workout type, days since long run, days since quality session, load trend, candidate training type, recommendation context; 워크아웃 세부 세트 생성은 Phase 5).

## 81. 핵심 invariant

```text
Normalized Activities → TrainingLoadService → TrainingStateService → TrainingState
```

TrainingState는 현재 훈련 흐름을 설명하지만 무엇을 해야 하는지 결정하지 않는다. **측정과 판단을 분리한다. ACWR/monotony 등의 숫자를 근거 없이 위험 등급으로 바꾸지 않는다. Phase 4C가 이 상태를 실제 훈련 의사결정 입력으로 사용한다.**
