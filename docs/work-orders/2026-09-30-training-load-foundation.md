# 2026-09-30 — Phase 4A: Training Load Foundation

| 항목 | 내용 |
|------|------|
| 날짜 | 2026-09-30 |
| 작업 | duration 기반 training load, 7일/28일 rolling, ISO 주간 요약, 조회 API 2개 |
| 상태 | 완료 |
| 커밋 | `feat: add training load foundation` |
| 지시서 원문 | [2026-09-30-training-load-foundation-instruction.md](2026-09-30-training-load-foundation-instruction.md) |
| 이전 작업 | [2026-09-30-raspberry-pi-deployment-preparation.md](2026-09-30-raspberry-pi-deployment-preparation.md) |

## 1. 범위와 구조

`com.runningai.training` 패키지 신규(6개 클래스) + `ActivityRepository` 쿼리 1개 + `Clock` bean. 새 테이블·migration·캐시·persisted aggregate 없음, Garmin/connector/ActivityRaw 의존 없음.

```text
Normalized Activity (activity table) -> TrainingLoadService -> DailyTrainingLoad -> TrainingLoadSummary / WeeklyTrainingSummary -> REST
```

| 클래스 | 역할 |
|--------|------|
| `TrainingLoadService` | 계산 전부. `daily(from,to)`, `summary(asOf)`, `weekly(date)`, `today()` (+ 테스트용 zone 지정 overload, package-private) |
| `DailyTrainingLoad` / `TrainingLoadSummary` / `WeeklyTrainingSummary` | record DTO (Entity 노출 없음) |
| `TrainingLoadController` | `GET /api/v1/training-load`, `GET /api/v1/training-load/weekly` (로직 없음) |
| `ClockConfig` (`common/config`) | `Clock.systemUTC()` bean. 서비스는 `LocalDate.now()`를 직접 부르지 않음 |
| `ActivityRepository.findByAthleteIdAndStartedAtGreaterThanEqualAndStartedAtLessThan` | 범위 조회 |

## 2. Load 정의

- 기준 지표: `trainingLoadMinutes = 총 duration(초) / 60.0` — **지원 활동 1분 = load 1분**. 초 단위로 합산하고 마지막에만 분으로 나눈다(중간 반올림 없음, 100초 = 1.6667).
- 대상: `RUN`, `TREADMILL_RUN`, `INDOOR_CYCLING` (현재 정규화되는 3종 전부).
- 러닝 전용 지표(거리·시간): `RUN`, `TREADMILL_RUN`만. `INDOOR_CYCLING`은 load와 `cyclingDurationSeconds`에만 반영, 러닝 거리·시간에는 미포함.
- 단위: 거리는 항상 meters(내부 km 혼용 없음), 시간은 seconds, load는 minutes.
- 결측 처리(entity 확인 결과 `durationSeconds`는 NOT NULL int, `distanceMeters`는 nullable): duration 0 → activityCount에는 포함, load 0. distance null → 거리 합산 0, activity는 제외하지 않음. 활동이 없는 날은 0으로 채워 반환.
- Source: normalized `Activity`만. `ActivityRaw`만 있는 항목(예: 미지원 type)은 계산에 포함되지 않는다(테스트로 확인).

## 3. Timezone / 범위 semantics

- Activity의 `startedAt`은 UTC `Instant`. **일/주 grouping은 athlete timezone**(`Athlete.timezone`, 기본 Asia/Seoul)의 캘린더이며, zone은 서비스 안에서 `athleteService.getDefaultAthlete().getTimezone()`으로 읽는다(Seoul 하드코딩 없음).
- 로컬 날짜 `D` → `D.atStartOfDay(zone)` ~ `(D+1).atStartOfDay(zone)`를 UTC 범위로 변환해 조회하고, 각 activity는 `startedAt.atZone(zone).toLocalDate()`로 일에 배정한다(DST 지역에서도 atStartOfDay 사용으로 안전).
- 모든 범위는 **`[fromInclusive, toExclusive)`**: `startedAt >= from AND startedAt < to`. 인접 범위에서 경계 instant가 중복되지 않는다.
- **Rolling 7일 / 28일**: `asOfDate`를 포함한 최근 7 / 28 캘린더 일. asOf=2026-09-30 → 7일 `[09-24 00:00, 10-01 00:00)`, 28일 `[09-03 00:00, 10-01 00:00)` (Seoul). 쿼리는 28일 구간 1회이고 7일은 그 뒷부분을 합산.
- **주간**: ISO week, 월요일 00:00 ~ 다음 월요일 00:00 (Seoul). `weekStart`=월요일, `weekEnd`=일요일(포함). 일요일 23:59:59 local은 이전 주, 월요일 00:00 local은 새 주.
- `today()` = `LocalDate.now(clock.withZone(athleteZone))`.

## 4. API

| Endpoint | 설명 |
|----------|------|
| `GET /api/v1/training-load[?date=YYYY-MM-DD]` | `{asOfDate, load7Days, load28Days, runningDistance7DaysMeters, runningDistance28DaysMeters, runningDuration7DaysSeconds, runningDuration28DaysSeconds, activityCount7Days, activityCount28Days}` |
| `GET /api/v1/training-load/weekly[?date=YYYY-MM-DD]` | `{weekStart, weekEnd, activityCount, trainingLoadMinutes, runningDistanceMeters, runningDurationSeconds, cyclingDurationSeconds}` (date가 속한 월~일) |

- `date`가 없으면 athlete-local 오늘. 잘못된 값(`abc`, `2026-13-40`)은 기존 `GlobalExceptionHandler`의 `400 INVALID_REQUEST`(structured error).
- single-user 유지: athleteId query 없음. Daily 전용 API는 만들지 않았고 서비스 수준(`daily`)에서만 제공.
- 응답은 항상 Garmin과 무관: scheduler가 켜져 있든 꺼져 있든 같은 값(같은 데이터 → 같은 결과, deterministic).

## 5. Tests

```text
Java: clean test → 150 total, 150 passed, 0 failed   (기존 128 + 신규 22)
Python: 변경 없음, 실행 안 함
```

`TrainingLoadServiceTest`(17, H2+Flyway, `@Transactional`): 빈 이력 전부 0 / 단일 60분 10km 러닝(load 60, 3600s, 10000m, count 1) / treadmill 러닝 포함 / indoor cycling(load +45, 러닝 거리·시간 불변, cyclingDuration 2700) / 혼합 주간(RUN+TREADMILL+CYCLING, 거리 없는 cycling, 이전 주 활동 제외) / 초 단위 비반올림 / **7일 경계**(정확히 7일 전 00:00 포함, 1초 전 제외, 마지막 초 포함, 다음날 00:00 제외) / **28일 경계** / **UTC↔Seoul 경계**(2026-09-29T15:30Z는 09-30 00:30 local, 14:59:59Z는 09-29) / 다른 timezone(UTC)에서는 다르게 묶임 / **일요일 23:59 vs 월요일 00:00 주 경계** / 일요일은 ISO 주의 마지막 날 / 0초·거리 null 처리 / 빈 날 포함 daily / **ActivityRaw만 있는 항목 미포함** / 결정성 / `today()`가 local 날짜.
`TrainingLoadApiTest`(5, MockMvc, Clock 고정 2026-09-29T16:00Z = Seoul 2026-09-30 01:00: UTC 날짜와 local 날짜가 달라 local today 처리를 증명): 기본 날짜 / 명시 날짜(미래 활동 제외) / weekly 기본 / weekly 명시(일요일 → 이전 주) / 잘못된 날짜 400 두 endpoint.
기존 API·ingestion·sync·scheduler 테스트는 변경 없이 통과.

## 6. Database

```text
migration: NO   schema change: NO   aggregate persistence: NO (derived, 요청마다 계산)
```

기존 `ix_activity_athlete_started (athlete_id, started_at)`가 이 범위 쿼리를 그대로 지원해 V5가 필요 없다. 28일 구간은 개인 규모에서 작아 entity 조회 후 Java 집계로 충분(native SQL·캐시 없음).

## 7. Garmin

network/connector/ActivityRaw 의존 없음. live validation 불필요(고정 fixture data + 고정 Clock으로 검증).

## 8. Known limitations

- duration-only: 강도(HR, pace, RPE, TRIMP) 반영 없음 — 같은 60분이라도 easy/interval이 동일 load.
- 지원 3종 외 활동(수영 등)은 정규화되지 않으므로 load에 포함되지 않음.
- Garmin readiness / Training Effect / recovery / VO2max 등 독점 지표를 복제하지 않으며 readiness·recovery 모델 없음.
- Athlete가 하나(default)뿐이라 multi-athlete 조회 미지원. timezone은 조회 시점의 현재 athlete timezone을 과거 활동에도 적용(timezone 이력 없음).
- 자정을 넘긴 활동은 시작 시각 기준 하루에 전부 배정(분할하지 않음).
- 캐시 없음: 요청마다 최대 28일 활동을 조회.

## 9. Future extension (자동 시작 안 함)

Phase 4B Training State: acute/chronic load, ramp, weekly progression, monotony/strain 검토, training state DTO. 강도 반영(`HeartRateLoadCalculator`/TRIMP 등)은 별도 Phase에서 `TrainingLoadService`의 활동 단위 load 계산 지점(`Totals.add`)을 교체·확장하면 된다.
