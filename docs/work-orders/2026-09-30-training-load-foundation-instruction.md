> 원본 작업지시서 (2026-09-30, Phase 4A). 구현 기록은 `2026-09-30-training-load-foundation.md` 참고.
> 추가 사용자 지시: CLAUDE.md, running-ai-dev 준수 (database skill은 필요 시, 기본은 migration/schema 변경 없음) / normalized Activity만 source, duration-based load + 7d/28d rolling + weekly aggregation / athlete local timezone 기준 / Garmin network·connector 호출 금지 / 구현 → 전체 Java regression → README/결과 문서 → secrets 검사 → diff review → commit → push / Phase 4B 시작 금지 / 결과 보고는 한글.

# RunningAI Phase 4A
## Training Load Foundation / Rolling Load / Weekly Summary

## 1. 현재 상태

완료: Phase 1 Spring Boot foundation, 2 PostgreSQL / Flyway, 3A Garmin ingestion, 3B Real Garmin E2E, 3C-1 Incremental sync, 3C-2 Manual sync API / status, 3C-3 Automatic scheduler, 3C-4A Windows orchestration, 3C-4B Windows watchdog, 3C-4C Raspberry Pi deployment preparation. latest known commit `4e0a991`.
Garmin activity를 수집·정규화해 PostgreSQL에 저장할 수 있다. 이번 Phase부터 저장된 활동 기반으로 최근 훈련량, 주간 훈련량, 단기/장기 load, 훈련 추세 계산의 기반을 만든다.

## 2. 이번 Phase 목표

복잡한 readiness 점수가 아니라, 다음을 정확하고 투명하게 계산한다: Daily Training Load, Weekly Training Load, Rolling 7-day Load, Rolling 28-day Load, Distance, Duration, Activity Count. 향후 Phase 4B에서 사용할 `TrainingLoadService` 기반을 만든다.

## 3. 중요한 설계 원칙

Garmin/Firstbeat 독점 지표를 복제하지 않는다: Garmin Training Readiness, Training Effect, Firstbeat EPOC, VO2max 추정, HRV readiness, recovery time 추정은 구현하지 않는다. RunningAI가 직접 설명 가능한 계산만 사용한다.

## 4–7. 초기 Load 모델

`loadMinutes = activityDurationMinutes` (60분 러닝 = load 60). 모든 활동에서 HR 품질이 같다고 가정할 수 없으므로 duration-based로 시작하고, HR weighted / LTHR / TRIMP / RPE / pace intensity는 별도 Phase. 계산 대상 `RUN`, `TREADMILL_RUN`, `INDOOR_CYCLING`. 러닝 통계는 `RUN`, `TREADMILL_RUN`만; Indoor Cycling은 total training load에는 포함, running distance에는 포함하지 않는다. 의미가 명확한 이름 `trainingLoadMinutes`를 쓰고 모호한 `load` 필드를 DB에 만들지 않는다.

## 8–9. 계산 중심 설계 / DB migration

새 persistent aggregate table을 만들지 않는다: `Activity table → TrainingLoadService → computed view/result` (계산식이 바뀔 가능성, derived data 중복 방지, migration 최소화, 재계산 용이). 기본 `migration = NO`, `schema change = NO`. index가 필요하면 먼저 기존 index를 확인하고 불필요하게 V5를 만들지 않는다.

## 10–12. 계산 timezone

Activity timestamp는 DB에서 UTC Instant이지만 daily/weekly grouping은 athlete local timezone(기본 Asia/Seoul) 기준이다(UTC date 기준 group 금지). 예: `2026-09-30 00:00 Asia/Seoul ~ 2026-10-01 00:00 Asia/Seoul`을 UTC Instant 범위로 변환해 query. 주간 기준: Monday 00:00 ~ next Monday, ISO week, athlete timezone.

## 13–16. DTO / Service

`DailyTrainingLoad`(date, activityCount, totalDurationSeconds, trainingLoadMinutes, runningDistanceMeters, runningDurationSeconds, cyclingDurationSeconds), `TrainingLoadSummary`(asOfDate, load7Days, load28Days, runningDistance7Days, runningDistance28Days, runningDuration7Days, runningDuration28Days, activityCount7Days, activityCount28Days), `WeeklyTrainingSummary`(weekStart, weekEnd, activityCount, trainingLoadMinutes, runningDistanceMeters, runningDurationSeconds, cyclingDurationSeconds). 필요 이상의 필드는 만들지 않는다. `TrainingLoadService`: daily calculation, weekly calculation, rolling windows, activity type aggregation.

## 17–18. Repository query / Range semantics

N+1이나 activity 전체 메모리 로딩을 피한다. ActivityRepository를 확장해 athlete + `startedAt >= from AND startedAt < to` 범위 조회. 개인 규모에서는 범위 내 entity 조회 후 Java aggregate 허용, 과도한 native SQL 최적화 금지. 반드시 `[fromInclusive, toExclusive)`.

## 19–20. Rolling 정의

7-day: asOfDate 포함 최근 7 calendar days (asOf = 2026-09-30 → 2026-09-24 00:00 ~ 2026-10-01 00:00 Asia/Seoul). 28-day: asOfDate 포함 최근 28 calendar days.

## 21–22. Rounding / Distance

durationSeconds는 초 단위 유지, `trainingLoadMinutes = totalSeconds / 60.0` (double 또는 적절한 decimal), 중간 정수 반올림 금지. distance canonical unit은 meters (내부에서 km와 섞지 않음, display에서만 변환 가능).

## 23–27. Missing / zero / raw

duration null 가능 여부를 현재 schema/entity에서 먼저 확인(추측 금지), null이면 load 0 또는 기존 domain invariant. duration=0: activityCount에는 포함, load 0. distance null: distance aggregation 0, activity는 제외하지 않음. Indoor Cycling: trainingLoadMinutes 포함, runningDistance 미포함, cyclingDurationSeconds 포함. ActivityRaw만 있고 normalized Activity가 없으면 계산 제외(normalized Activity가 source of truth).

## 28–29. Idempotency / Future extensibility

같은 data에 대해 deterministic (같은 query 반복 시 동일 결과). 향후 DurationLoadCalculator / HeartRateLoadCalculator / TrimpLoadCalculator를 붙일 수 있게 만들 수는 있으나 strategy framework를 과하게 만들지 않는다.

## 30–35. API

`GET /api/v1/training-load?date=YYYY-MM-DD` (없으면 athlete local 오늘) → `{asOfDate, load7Days, load28Days, runningDistance7DaysMeters, runningDistance28DaysMeters, runningDuration7DaysSeconds, runningDuration28DaysSeconds, activityCount7Days, activityCount28Days}`.
`GET /api/v1/training-load/weekly?date=YYYY-MM-DD` → date가 포함된 Monday~Sunday week `{weekStart, weekEnd, activityCount, trainingLoadMinutes, runningDistanceMeters, runningDurationSeconds, cyclingDurationSeconds}`.
Daily API(`/daily`)는 만들지 않고 서비스/테스트 수준에서만 제공. single-user/default athlete 유지, athleteId를 query로 노출하지 않음.

## 36–37. Clock / Timezone source

`LocalDate.now()`를 service 내부에서 직접 호출하지 않고 `Clock`을 주입. Athlete의 timezone 필드를 사용하고 `ZoneId.of("Asia/Seoul")`을 business service 곳곳에 반복하지 않는다.

## 38–50. 테스트

Empty history(모두 0) / Single run(60min 10km → load 60, duration 3600, distance 10000, count 1) / Treadmill 포함 / Indoor cycling(45min → load +45, running 미변화, cyclingDuration +2700) / Mixed week(RUN+TREADMILL+CYCLING) / 7-day 경계(정확히 7일 전 00:00 포함, 다음날 00:00 제외) / 28-day 경계 / UTC·Korea 경계(UTC 2026-09-29 15:30 = Seoul 2026-09-30 00:30, daily/weekly가 local 기준) / Sunday 23:59 local = 이전 주, Monday 00:00 local = 새 주 / ActivityRaw만 있으면 제외 / API default date(Clock 고정, local today) / explicit date / invalid date(`date=abc`) → 기존 structured error pattern 400.

## 51–57. Regression / 비의존 / 성능 / 캐시 / 영속화

기존 API 유지(`health`, `activities`, `garmin/sync`, `garmin/sync/status`, `actuator/health`). Training Load API는 Garmin connector, Garmin network, ActivityRaw를 호출하지 않고 오직 normalized Activity/athlete data만 사용하며, Garmin scheduler on/off와 무관하게 동일해야 한다. 28-day range query는 충분히 작으므로 premature optimization 금지, 캐시(Redis 포함) 금지, `training_load_daily`/`training_load_weekly` 같은 aggregate table 금지.

## 58–64. README / 결과 문서 / 검증

README에 Training Load 섹션(두 endpoint, load 정의 `1 minute of normalized supported activity = 1 load minute`). 결과 문서: load definition, timezone semantics, rolling range semantics, supported activity types, API, tests, known limitations, future extension. 새 secret 없음이지만 diff review·secrets scan 수행. `cd server; .\gradlew.bat clean test` baseline 128 + 신규, 전부 PASS. Python 변경 없음. DB `migration: NO`, `schema change: NO`. Live Garmin validation 불필요.

## 65–66. Definition of Done / commit

TrainingLoadService, duration-based load, RUN·TREADMILL_RUN·INDOOR_CYCLING, running distance 분리, cycling duration 분리, athlete timezone, rolling 7/28, ISO Monday week, `[fromInclusive,toExclusive)`, 두 DTO, 두 API, no Garmin dependency, no aggregate persistence, no cache, no migration, timezone/week boundary/mixed/API tests, 기존 regression PASS, README, 결과 문서, secrets scan, diff review, commit, push. 권장 commit `feat: add training load foundation`.

## 67. 완료 보고 형식

Load Model(base metric, unit, supported activities, running-only metrics) / Rolling Windows(7-day, 28-day, timezone, range semantics) / Weekly(week start, week end, timezone) / API / Tests(Java total·passed·failed, timezone tests, boundary tests) / Database(migration, schema change, aggregate persistence) / Garmin(network dependency, live validation) / Git / Known Limitations(duration-only load, HR intensity weighting 없음, RPE 없음, TRIMP 없음, readiness 없음, recovery 모델 없음) / Next Phase(자동 시작 금지; Phase 4B Training State: acute load, chronic load, ramp, weekly progression, monotony/strain 검토, training state DTO).

## 68. 핵심 invariant

```text
Normalized Activities → TrainingLoadService → Daily / Weekly aggregation → 7d / 28d rolling load
```

load 계산은 deterministic, daily/weekly 기준은 athlete timezone, 현재는 duration 기반으로 단순하고 설명 가능해야 하며, Garmin 독점 지표를 추측해서 복제하지 않는다.
