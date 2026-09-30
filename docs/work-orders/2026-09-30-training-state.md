# 2026-09-30 — Phase 4B: Training State Metrics

| 항목 | 내용 |
|------|------|
| 날짜 | 2026-09-30 |
| 작업 | acute/chronic load, 7일 progression, ramp, monotony, strain 계산 + `GET /api/v1/training-state` |
| 상태 | 완료 |
| 커밋 | `feat: add athlete training state metrics` |
| 지시서 원문 | [2026-09-30-training-state-instruction.md](2026-09-30-training-state-instruction.md) |
| 이전 작업 | [2026-09-30-training-load-foundation.md](2026-09-30-training-load-foundation.md) |

## 1. 범위와 구조

`com.runningai.training`에 `TrainingState`(record), `TrainingStateService`, `TrainingStateController` 3개만 추가. **4A 코드 변경 없음**(`TrainingLoadService.daily/today` 재사용, repository query 추가 없음). migration·persistence·cache·Garmin 의존·판정/threshold 없음.

```text
Normalized Activities -> TrainingLoadService.daily(D-27, D) [쿼리 1회] -> TrainingStateService.compute -> TrainingState
```

28개 일별 load 시계열 하나에 모든 창이 들어 있다: current 7일 = 뒤 7개, previous 7일 = 그 앞 7개, chronic = 전체 28개. 계산은 순수 함수(`compute`)이고 같은 입력이면 같은 결과다. 일/주 경계와 `[from,to)`는 4A와 동일(athlete timezone, 주입된 Clock).

## 2. Metric 정의 (load = 지원 활동 1분 = 1 load minute)

| 필드 | 정의 |
|------|------|
| `acuteLoad` | 최근 7 calendar days load 합 (= `current7DayLoad` = 4A `load7Days`) |
| `chronicLoad` | 최근 28일 load 합 **/ 4** = 최근 4주의 평균 주간 load (28일 총합이 아님) |
| `acuteChronicRatio` | acute / chronic, chronic = 0이면 **null** |
| `current7DayLoad` / `previous7DayLoad` | D-6..D / D-13..D-7 (rolling, 4A의 월~일 주간 요약과 다름) |
| `weeklyLoadChangePercent` | (current − previous) / previous × 100, previous = 0이면 **null** (둘 다 0이어도 null) |
| `runningDistance7DaysMeters`, `previousRunningDistance7DaysMeters`, `runningDistanceChangePercent` | RUN + TREADMILL_RUN 거리(m), cycling 제외, previous = 0 → null |
| `runningDuration7DaysSeconds`, `previousRunningDuration7DaysSeconds`, `runningDurationChangePercent` | 러닝 시간(초), previous = 0 → null |
| `rampLoad` | current7DayLoad − previous7DayLoad (load minutes, 음수 가능). 별도 ramp % 필드 없음(`weeklyLoadChangePercent`가 같은 의미) |
| `monotony` | 최근 7일 daily load의 mean / **모집단 표준편차(÷N)**, 휴식일 0 포함해 7개 전부 사용. SD = 0이면 **null** |
| `strain` | current7DayLoad × monotony, monotony null이면 null |
| `activeDays7Days` / `restDays7Days` | daily load > 0인 날 수 / 7 − active (같은 날 여러 활동은 active 1일) |

구현 세부: monotony는 무차원이므로 **정수 초 단위 일별 값**으로 계산한다(평균·편차가 정확해 같은 값 7개면 SD가 정확히 0 → null. 분(double)으로 계산하면 100초처럼 나누어떨어지지 않는 값에서 ulp 오차로 거대한 monotony가 나올 수 있어 테스트로 방어). load 자체는 초 합 / 60.0로 마지막에만 분 변환. Null은 JSON에서도 **명시적 null**(`@JsonInclude(ALWAYS)`, 전역 설정이 null을 생략하기 때문에 이 DTO에만 지정)이라 “정의 불가”와 “필드 없음”이 구분된다.
어떤 값에도 등급·threshold·해석이 없다 (`FATIGUED` 등 분기 코드 없음). 예: 최근 7일 100 + 그 이전 0이면 acute 100, chronic 25, ratio 4를 그대로 반환.

## 3. API

`GET /api/v1/training-state[?date=YYYY-MM-DD]` — date가 없으면 athlete-local 오늘, 잘못된 값은 `400 INVALID_REQUEST`. 응답은 위 필드의 JSON(double은 가공 없이 number). 기존 `/api/v1/training-load`, `/weekly` 등 모든 API는 무변경.

## 4. Tests

```text
Java: clean test → 172 total, 172 passed, 0 failed   (기존 150 + 신규 22: 서비스 18, API 4)
Python: 변경 없음, 실행 안 함
```

- **monotony**: 10×7 → mean 10, SD 0, monotony·strain null / 100초×7(정수 분이 아님)도 null / 0,30,0,60,30,0,90 → mean 30, 모집단 SD = √(7200/7), monotony ≈ 0.93541, strain = 210 × monotony, active 4 · rest 3 / 표본 SD(÷6)와 다름을 확인 / 같은 날 run 30 + cycling 45 → daily 75, active 1일.
- **acute/chronic**: 21일 × 20분 + 7일 × 60분 → acute 420, chronic (420+420)/4 = 210, ratio 2.0, previous 140, ramp +280 / 최근 7일만 100 → acute 100, chronic 25, ratio 4 (해석 없이 반환) / 활동 없음 → ratio null.
- **progression**: 100→120 → +20%, ramp +20 / 200→150 → −25%, ramp −50 / previous 0 → percent 3종 null, ramp = current / 두 주 모두 0 → null(0% 아님).
- **running metrics**: cycling 거리·시간은 러닝 progression에서 제외되지만 load에는 포함, TREADMILL_RUN은 러닝에 포함.
- **timezone / boundary**: `09-23T15:00Z`(= 09-24 00:00 Seoul)는 current의 첫 순간, `09-23T14:59:59Z`는 previous의 마지막 초, `09-16T15:00Z`는 previous의 첫 순간, `09-16T14:59:59Z`는 previous 밖(28일 baseline에는 포함), `09-29T15:30Z`(UTC 날짜는 09-29)는 09-30 current / asOf 다음날 00:00 local 활동 제외 / asOf를 과거로 옮기면 모든 창이 이동 / 반복 계산 결정성 / 빈 이력의 모든 필드.
- **API**(고정 Clock 2026-09-30 01:00 Seoul): 기본 날짜(local today, 전 필드 값), 미정의 값이 명시적 null, 명시 날짜, malformed date 400.
기존 128개(training-load 22개 포함 150개)는 수정 없이 통과.

## 5. Database / Garmin

```text
migration: NO   schema change: NO   persistence: NO (요청마다 계산, cache 없음)
Garmin: network·connector·activity_raw 의존 없음, live validation 불필요
```

## 6. Known limitations

- duration-only load: 강도(HR, pace, RPE, TRIMP) 미반영, easy와 interval이 같은 load.
- readiness / recovery / injury-risk 판정과 threshold 없음(숫자만 제공). ratio·monotony·strain의 의미 있는 범위는 정해지지 않았다.
- chronic은 평균 주간 load(28일 ÷ 4)이며 EWMA가 아니다. 앞 3주가 비어 있으면 ratio가 크게 나오는 것은 계산상 정상이다.
- monotony는 SD = 0(전부 같은 값 또는 전부 0)에서, percent 계열은 previous = 0에서 정의되지 않아 null.
- 자정을 넘긴 활동은 시작 시각의 하루에 배정, timezone 이력 없음, single athlete.

## 7. Next Phase (자동 시작 안 함)

Phase 4C — 훈련 의사결정 입력/맥락: 최근 hard/easy/rest 패턴, 마지막 workout type, 장거리·quality 이후 경과일, load trend, 후보 훈련 종류, recommendation context. 워크아웃 세부 세트 생성은 Phase 5.
