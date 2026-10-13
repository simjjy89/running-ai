# Phase 7E-0 — STEP 1 과거 데이터 조사 + STEP 3 사본 Backfill 검증 결과

작성일 2026-10-13 · 브랜치 `main-3` @ `34cf121` · **`server/` 코드 변경 0건, 운영 DB·운영 Spring 변경 0건**
지시문 원문: [2026-10-13-phase-7e-0-instruction.md](2026-10-13-phase-7e-0-instruction.md)

이번 세션은 완료 기준이 지정한 **① 구현 현황 조사**와 **② 과거 데이터 Backfill의 사본 검증**을 수행했다.
STEP 2의 신규 구현, STEP 4~7의 구현, STEP 8의 실행은 **승인 대기**로 남긴다(사유는 §2, §9).

---

## 0. 한 줄 결론

운영 DB에 없는 과거 기록이 분명히 존재하지만, **그것을 가져올 수 있는 경로는 기존 Garmin Backfill이 아니다.**
Garmin Connect에는 2026-09-18 이전 기록이 **아예 없고**, 과거 이력은 Intervals.icu에만 있으며 그중
**27건은 API로 복구 가능**, **34건(7월 전체 포함)은 Intervals API로 복구 불가**다.
기존 6H-6 Backfill은 실데이터 사본에서 **멱등성·매칭·CTL/ATL 정합성 모두 PASS**했고, 이미 **포화 상태**다
(90일 재수집 결과 신규 행 0건).

---

## 1. STEP 1 — 과거 데이터 현황 조사

### 1.1 조사 환경

| 대상 | 접근 방법 | 변경 여부 |
|---|---|---|
| 운영 PostgreSQL `running_ai` (V20) | `psql` 읽기 전용 SELECT | 변경 없음 |
| 실데이터 사본 `running_ai_realcopy` (V23) | 전용 Spring 18083 + `psql` | **검증 목적 쓰기 수행** (§3) |
| Garmin Connect | 로컬 커넥터 `127.0.0.1:8765` GET | 읽기 전용 |
| Intervals.icu | `https://intervals.icu` GET (API Key) | 읽기 전용 |
| 레거시 `C:\running-ai` | `ls`/`cat`/`grep`만 | **변경 없음(읽기 전용 준수)** |

### 1.2 소스 1 — 운영 PostgreSQL `running_ai` (스키마 V20)

| 테이블 | 행 수 | 기간 |
|---|---:|---|
| `activity` | **8** | 2026-09-18 ~ 2026-10-11 (KST) |
| `activity_raw` | 10 | — (활동 8 + 미지원 2) |
| `activity_raw_payload` | 40 | — |
| `activity_detail` | 8 | — |
| `activity_lap` | 123 | — |
| `activity_sample` | **21,828** | — |
| `activity_zone` | 75 | — |
| `activity_detail_collection` | 48 | — |
| `activity_analysis` | 8 | — |
| `garmin_recovery_daily` | 21 | 2026-09-17 ~ 2026-10-12 (**결손 5일**) |
| `intervals_fitness_daily` | 99 | 2026-07-06 ~ 2026-10-12 |
| `activity_intervals_metrics` | 8 | — |
| `activity_source_link` | 8 | — |
| `intervals_raw_payload` | 166 | — |
| `workout_draft` | 19 | — |
| `historical_backfill_run` | 1 | 2026-07-06~10-03, COMPLETED |

운영 활동 8건 전부 `external_source=GARMIN`이고 외부 ID는 `24403005968`~`24679734836`.
`activity_raw` 10건 중 2건은 `activity_id IS NULL` — 미지원 종목(`breathwork`)이 **Raw-first 원칙대로 원본만 보존**된 정상 상태다.

### 1.3 소스 2 — Garmin Connect API

`GET /activities?limit=100&start=0` 1회 호출 결과 **계정 전체 활동이 10건뿐**이었다.

| 날짜(KST) | Garmin ID | typeKey | 거리 | DB 적재 |
|---|---|---|---:|---|
| 2026-09-18 11:10 | 24403005968 | treadmill_running | 4.19 km | O |
| 2026-09-19 08:00 | 24413424790 | running | 9.92 km | O |
| 2026-09-21 12:24 | 24438659533 | **breathwork** | — | Raw만 |
| 2026-09-22 07:48 | 24449361556 | **breathwork** | — | Raw만 |
| 2026-09-26 07:01 | 24498369528 | indoor_cycling | — | O |
| 2026-09-29 11:10 | 24536068763 | treadmill_running | 5.39 km | O |
| 2026-09-30 23:50 | 24555873613 | track_running | 6.88 km | O |
| 2026-10-06 00:02 | 24615615796 | track_running | 7.35 km | O |
| 2026-10-06 22:48 | 24627620081 | track_running | 7.71 km | O |
| 2026-10-11 07:39 | 24679734836 | running | 21.24 km | O |

* **최초 기록 2026-09-18, 그 이전은 Garmin에 존재하지 않는다.** (시계 사용 개시 시점으로 추정)
* 미지원 2건은 `GarminActivityMapper.ACTIVITY_TYPES`에 `breathwork`가 없어 `UNSUPPORTED_ACTIVITY_TYPE`.
  `ActivityType` enum은 `RUN / TREADMILL_RUN / INDOOR_CYCLING` 3종뿐이다.
* 회복 데이터: `GET /recovery?date=2026-09-01` → HRV·수면·안정시심박·바디배터리·스트레스 **전부 `NO_DATA`**.
  즉 **회복 데이터의 하한은 2026-09-17**이며 그 이전은 Garmin이 제공하지 않는다(추정 생성 금지 대상).
* **운영 DB와 Garmin은 이미 완전 동기화 상태** — 신규 획득 가능분 0건.

### 1.4 소스 3 — Intervals.icu

`GET /api/v1/athlete/0/activities?oldest=2000-01-01&newest=2026-10-13` → **71건, 2026-07-02 ~ 2026-10-11**.

| source | 건수 | 기간 | API 데이터 | DB 적재 |
|---|---:|---|---|---|
| `GARMIN_CONNECT` | 10 | 2026-09-18 ~ 10-11 | 전체 | **중복(이미 보유)** |
| `OAUTH_CLIENT` | **27** | 2026-08-02 ~ 09-13 | 전체 + 스트림 | **미보유 — 복구 가능** |
| `STRAVA` | **34** | 2026-07-02 ~ 08-26 | **불가** | **미보유 — 복구 불가** |

* STRAVA 34건은 응답 본문이 `{id, icu_athlete_id, start_date_local, source,`
  `_note: "STRAVA activities are not available via the API"}`뿐이다. 종목·거리·시간조차 없다.
* 중복 여부를 실측했다: 실데이터 보유 활동 36건의 `strava_id` 집합과 STRAVA 스텁 34건의 `id` 집합의
  **교집합은 0건**. 즉 34건은 다른 활동의 사본이 아니라 **진짜로 별개의 활동**이다. 7월 27건이 여기 속한다.
* `OAUTH_CLIENT` 27건 상세(실측): Run 23 / VirtualRun 2 / Ride 1 / Workout 1, 러닝 25건 합계 **124.2 km**,
  기기 `Intervals Companion` 19 + `Watch7,9`(Apple Watch) 8, **27건 전부 심박 보유**.
  스트림 종류 — `heartrate` 27, `time` 27, `distance` 26, `velocity_smooth` 26, `cadence` 25,
  `watts`/`altitude`/`latlng`/`torque`/`fixed_altitude` 19, `stance_time`/`vertical_oscillation` 18, `step_length` 17.
  샘플 예시: `i180579414`(08-18, 15.12 km) → 1 Hz 스트림 6,651 포인트, 요약 필드 85개.
  **단, `icu_lap_count`가 27건 모두 0 — 랩이 하나도 없다.**
* 훈련부하(wellness): **104일, 2026-07-02 ~ 2026-10-13**. 단 `ctl>0`인 날은 2026-08-02부터 73일.
  **7월 STRAVA 활동은 Intervals 자체 피트니스 모델에도 부하로 반영되어 있지 않다.**

### 1.5 소스 4·5 — 레거시 `C:\running-ai` (읽기 전용)

| 경로 | 내용 | 마이그레이션 가치 |
|---|---|---|
| `data/archive/activities.json` | 31건 요약, 2026-08-02 ~ 10-06 (Intervals 파생) | **낮음** — Intervals API가 상위 집합 |
| `data/garmin_activity/` | 5개 활동 디렉터리(`raw/normalized/report_record.json`) | **없음** — 전부 DB 보유분 |
| `data/history.json` | 최근 31 run 스냅숏 (2026-10-09 생성) | 없음 |
| `data/latest.json` | Fitness 12.64 / Fatigue 10.98 / Form 1.66 스냅숏 | 없음 |
| `reports/` | 93개 `.md` 훈련 리포트, 2026-08-26 ~ | **중간** — 원본 데이터가 아니라 *과거 코칭 기록*. 활동 데이터로서의 가치는 없음 |
| `logs/` 22 MB, `diagnostics/`, `config/` | 운영 로그·진단 | 없음 |

`data/`·`reports/` 전체에 대해 `2026-0[1-6]-` 패턴을 grep한 결과 **0건** —
**이 PC 어디에도 2026-07-02 이전 기록은 존재하지 않는다.**

### 1.6 STEP 1 종합 — 복구 가능/불가 구분

| 구분 | 건수 | 기간 | 상태 |
|---|---:|---|---|
| 이미 DB에 있음 (Garmin) | **8** | 2026-09-18 ~ 10-11 | 완료 |
| 미지원 종목, Raw만 보존 | 2 | 2026-09-21 ~ 09-22 | `ActivityType` 확장 시에만 승격 가능 |
| **Intervals API로 복구 가능** | **27** | 2026-08-02 ~ 09-13 | **신규 적재 경로 필요** |
| **Intervals API로 복구 불가** | **34** | 2026-07-02 ~ 08-26 | **Strava 등 외부 출처 필요** |
| 2026-07-02 이전 | 0 | — | 어떤 소스에도 없음 |

복구 가능한 27건에 대해 기대할 수 있는 것과 없는 것:

* 가능: 요약(거리/시간/심박/고도/칼로리), 1 Hz 스트림, 러닝 다이내믹스(stance_time·vertical_oscillation·step_length),
  파워, Intervals 훈련부하/강도/CTL·ATL, 원본 측정일(`start_date_local` + `start_date` UTC).
* 불가: **랩 구조(27건 전부 lap 0)** → 6H-4 분석 엔진의 인터벌 그룹 추출은 구조상 성립하지 않는다.
  Garmin HR/파워 존, Garmin 회복 데이터도 없다. **추정 생성하지 않는다.**

---

## 2. STEP 2 — Historical Backfill 설계 (구현 보류, 승인 필요)

### 2.1 핵심 발견: 기존 Backfill로는 목표를 달성할 수 없다

기존 6H-6 `HistoricalBackfillService`는 **GARMIN_DISCOVERY가 모든 단계의 입력**이다
(`GarminHistoricalActivityDiscoveryService`가 Garmin 활동 목록을 훑어 대상 집합을 만들고,
Intervals 단계는 그렇게 만들어진 `activity` 행에 *enrichment를 붙이는* 역할만 한다).

따라서 Garmin에 존재하지 않는 27건은 **창을 아무리 넓혀도 영원히 발견되지 않는다.**
§3의 90일 재수집이 신규 행 0건으로 끝난 것이 이를 실측으로 증명한다.

추가 제약:
* `MAX_DAYS = 90` < 전체 이력 폭 104일(2026-07-02 ~ 10-13).
* CLAUDE.md의 6H-5 불변식 *"An Intervals activity never becomes a second Activity row"* 는
  **Garmin 활동의 중복 생성 금지**를 뜻한다. Garmin에 대응물이 없는 Intervals 전용 활동을
  `external_source=INTERVALS_ICU`로 적재하는 것은 그 취지와 충돌하지 않지만,
  **문서화된 경계를 건드리는 결정이므로 임의로 진행하지 않았다.** `ExternalSource.INTERVALS_ICU`는
  enum에 이미 존재하므로 스키마 변경 없이 수용 가능하다.

### 2.2 제안 설계 (승인 시 구현)

신규 패키지 `integration.intervals` 확장 — `IntervalsHistoricalActivityIngestionService`:

1. **Raw-first**: `intervals_raw_payload`(V18, `ACTIVITY`)에 목록·상세·스트림 원문을 먼저 커밋.
2. **승격**: 순수 매퍼 → `activity`(`external_source=INTERVALS_ICU`, `external_id=i<id>`),
   `activity_detail`/`activity_sample`(스트림 기반). **랩은 없으므로 `activity_lap`에 아무것도 쓰지 않는다.**
3. **멱등성**: `uk_activity_external(external_source, external_id)`로 보장. 재실행 시 0건 추가.
4. **중복 방지**: 적재 전 `activity_source_link`와 Garmin `external_id`를 조회해
   이미 Garmin 활동으로 존재하는 Intervals 활동(`source=GARMIN_CONNECT`)은 **승격 대상에서 제외**,
   기존 6H-5 enrichment 경로로만 처리. 사후에 같은 활동이 Garmin으로 들어오면
   6H-5 매처의 `AMBIGUOUS`/`INTERVALS_LINK_CONFLICT` 규칙을 그대로 적용해 **자동 병합 금지**.
5. **품질 보호**: 기존 비NULL 상세 값은 덮어쓰지 않는다(Garmin 우선). Intervals 적재는
   Garmin 대응물이 없는 활동에만 행을 만든다.
6. **출처·측정일 보존**: `external_source` + Intervals `start_date`(UTC) + `start_date_local`(Asia/Seoul) 동시 보존.
7. **체크포인트·재개**: 6H-6의 `historical_backfill_run` + per-activity 체크포인트 패턴 재사용,
   새 phase `INTERVALS_ACTIVITY_INGEST` 추가. 429/timeout은 기존 정책대로 **PAUSED(재시도 아님)**.
8. **시험 순서**: 3건 → 10건 → 27건 전량, 매 단계 사본에서만.

### 2.3 같이 처리해야 하는 결함 1건 (§3.6에서 실측)

`activity_analysis.analysis_version`이 V22(러닝 다이내믹스 컬럼 추가) 전후로 **동일하게 `RUNNING_ANALYSIS_V1`**이라,
Backfill의 "신선한 V1 분석은 재사용" 규칙이 **다이내믹스가 비어 있는 구버전 행을 영구히 재사용**한다.
→ 분석 버전 문자열을 올리거나(권장) 재분석 강제 플래그가 필요하다.

---

## 3. STEP 3 — 실데이터 사본 Backfill 검증 (실행 완료)

### 3.1 검증 환경 구성

기존 18082는 7D-GPT-LIVE-3에서 **외부 연동을 의도적으로 차단**해 둔 인스턴스다
(`GARMIN_CONNECTOR_URL=http://127.0.0.1:9`, `INTERVALS_API_KEY` 공백).
실제로 18082에 Backfill을 요청하자 즉시 `PAUSED / GARMIN_UNAVAILABLE`로 멈췄다 — **설계대로 fail-closed**.

18082를 건드리지 않고, 검증 전용 인스턴스를 **새로 띄웠다**:

| 항목 | 값 |
|---|---|
| 포트 | **127.0.0.1:18083** (loopback 전용), PID 44352 |
| JAR | `main-3/server/build/libs/running-ai-server-0.0.1-SNAPSHOT.jar` (RC jar, 재빌드 없음) |
| DB | `running_ai_realcopy` (V23) |
| Garmin | `http://127.0.0.1:8765` (실제 커넥터) |
| Intervals | 실제 API Key |
| 스위치 | workout-publishing / scheduler / draft-publishing / mcp / garmin scheduler / profile-sync **전부 false** |
| 로그 | `.runtime/logs/bfverify-18083.{out,err}.log` |

사본 지정 확인: LTHR 176/290 + `injury-state = ACTIVE(KNEE_LATERAL)` → `running_ai_realcopy` 확정.
운영 18080은 `/api/v1/athlete/injury-state` → **404**(V20, injury 없음)로 명확히 구별된다.

### 3.2 검증 1 — 기존 행 수·외부 ID 보존 (지시 1번)

수집 전 스냅숏 17개 테이블 기록. 활동 외부 ID 8개 전부 보존, `count = count(distinct external_source||external_id) = 8`.

### 3.3 검증 2 — 제한 구간 시험 수집 + 중단/재개 (지시 2·7번)

18082에서 `PAUSED/GARMIN_UNAVAILABLE`로 멈춘 run #2(2026-10-07~10-13)를 18083에서 **resume**.

```
POST /api/v1/historical-backfill/2/resume  → 200, 97.9 s
status COMPLETED / phase COMPLETED
target 1 · completed 1 · skippedUnsupported 0
detailRefetched 0 · detailSkippedAlreadyComplete 1 · detailFull 1
analysisComplete 1 · intervalsMatched 1 · fitnessDays 7 · recoveryDays 28
```

행 변화:

| 테이블 | 전 | 후 | Δ |
|---|---:|---:|---:|
| `activity` / `activity_detail` / `activity_lap` / `activity_sample` / `activity_zone` / `activity_analysis` / `activity_raw*` / `activity_source_link` / `activity_intervals_metrics` / **`garmin_sync_state`** | — | — | **0** |
| `garmin_recovery_daily` | 21 | **27** | **+6** |
| `intervals_fitness_daily` | 99 | 100 | +1 |
| `intervals_raw_payload` | 166 | 167 | +1 |

**중단→재개가 손실 없이 동작**했고, `garmin_sync_state`는 문서대로 **건드리지 않았다**.

### 3.4 검증 3 — 동일 구간 재실행, 중복 0건 (지시 3번)

동일 창(2026-10-07~10-13)으로 새 run #3 실행 → 95.8 s, COMPLETED, 동일 집계.

**17개 테이블 중 데이터 테이블 16개 전부 Δ = 0.** 증가한 것은 실행 기록 `historical_backfill_run` +1뿐.
`intervals_raw_payload`조차 늘지 않았다(동일 키 교체 저장). → **멱등성 PASS.**

### 3.5 검증 — 전체 과거 기록 Backfill (지시: "가능하다면 전량")

최대 폭 90일(2026-07-16 ~ 2026-10-13)으로 run #4 실행 → 104.8 s, COMPLETED.

```
target 8 · completed 8 · skippedUnsupported 2
detailRefetched 0 · detailSkippedAlreadyComplete 8 · detailFull 8 · detailNoSamples 0
analysisComplete 8 · analysisPartial 0
intervalsMatched 8 · intervalsUnmatched 0 · intervalsAmbiguous 0
fitnessDays 90 · recoveryDays 28
```

**모든 데이터 테이블 Δ = 0.** → Garmin 기준 수집은 **이미 포화**이며, §2.1의 결론을 실측으로 뒷받침한다.

### 3.6 검증 4~6 — 매칭 / 분석 / CTL·ATL

**Garmin ↔ Intervals 매칭: 8/8 `SOURCE_ID`**, UNMATCHED 0 / AMBIGUOUS 0.

| activity | Garmin ID | Intervals ID | method | load | CTL후 | ATL후 |
|---:|---|---|---|---:|---:|---:|
| 5 | 24403005968 | i187812124 | SOURCE_ID | 23 | 13.20 | 16.63 |
| 3 | 24413424790 | i188081596 | SOURCE_ID | 89 | 14.98 | 26.26 |
| 2 | 24498369528 | i190392973 | SOURCE_ID | 3 | 12.75 | 10.06 |
| 1 | 24536068763 | i191378893 | SOURCE_ID | 37 | 12.74 | 11.48 |
| 4 | 24555873613 | i191925076 | SOURCE_ID | 41 | 13.41 | 15.41 |
| 6 | 24615615796 | i193886143 | SOURCE_ID | 50 | 13.91 | 19.45 |
| 8 | 24627620081 | i194301205 | SOURCE_ID | 47 | 13.91 | 19.45 |
| 7 | 24679734836 | i195954145 | SOURCE_ID | 172 | 16.39 | 32.42 |

**CTL/ATL 기간별 정합성**: DB `intervals_fitness_daily` 100일을 Intervals live wellness 104일과 1:1 대조 →
**불일치 0건**, `derived_form = ctl - atl` 전건 일치. DB에 없는 날은 2026-07-02~07-05 4일뿐이며
(90일 창 밖, 해당 구간 `ctl = 0`) 데이터 손실이 아니다.

**수집 품질**: `activity_detail_collection` 48행 — `ACTIVITY_DETAILS_STREAM` 8건 전부
`NORMALIZED / sample_completeness = FULL`, `POWER_ZONES` 7 NORMALIZED + 1 EMPTY(실내 자전거, 정상).
DOWNSAMPLED·UNKNOWN **0건**.

**회복 데이터**: 2026-09-17 ~ 2026-10-13 **27일 연속, 결손 0일**.
이전에 알려진 구멍(2026-10-04, 10-07~10-10)이 이번 수집으로 메워졌다. 2026-09-16 이전은 §1.3대로 Garmin 미제공.

### 3.7 검증 5 — 러닝 다이내믹스에서 발견한 결함

최초 상태에서 `activity_analysis` 8행 중 **러닝 다이내믹스(V22)가 채워진 것은 1건(활동 7)뿐**이었다.
나머지 7건은 `avg_stride_length_cm` / `avg_ground_contact_time_ms` / `avg_running_power_watts` 등이 전부 NULL.

원인: 7건은 V22 이전 코드로 계산됐는데 `analysis_version`이 **똑같이 `RUNNING_ANALYSIS_V1`**이라,
Backfill의 "상세가 그대로이고 분석이 신선한 V1이면 재사용" 규칙이 **구버전 행을 계속 재사용**한다.
Backfill을 몇 번 돌려도 영원히 채워지지 않는다(§3.5에서 실제로 Δ=0).

강제 재분석(`POST /api/v1/activities/{id}/analysis`)으로 사본에서 전건 복구했다:

| activity | 종목 | stride(cm) | GCT(ms) | power(W) | VO(cm) | decoupling(%) |
|---:|---|---:|---:|---:|---:|---:|
| 5 | TREADMILL_RUN | 105.0 | 254.0 | 300.7 | 8.79 | 7.511 |
| 3 | RUN | 119.5 | 232.1 | 358.2 | 8.93 | 5.679 |
| 2 | INDOOR_CYCLING | – | – | – | – | – |
| 1 | TREADMILL_RUN | 115.8 | 251.6 | 329.9 | 9.39 | −2.662 |
| 4 | RUN | 95.3 | 272.3 | 276.5 | 8.76 | 4.387 |
| 6 | RUN | 104.7 | 259.2 | 307.6 | 8.98 | 2.739 |
| 8 | RUN | 92.6 | 275.7 | 265.8 | 8.70 | 4.192 |
| 7 | RUN | 107.6 | 246.3 | 321.0 | 8.72 | 6.239 |

INDOOR_CYCLING에 다이내믹스가 없는 것은 **설계대로**(RUN/TREADMILL_RUN 전용).
재분석은 결정적이었다 — 기존 `speed_hr_decoupling_percent` 값이 전건 **변하지 않았다**(활동 7 = 6.239 % 유지).

### 3.8 검증 7 — 오류·재시도 의미론

| 요청 | 결과 |
|---|---|
| COMPLETED run resume | **409** `HISTORICAL_BACKFILL_ALREADY_COMPLETED` |
| 존재하지 않는 run resume | **404** `HISTORICAL_BACKFILL_NOT_FOUND` |
| `days = 120` | **400** `INVALID_BACKFILL_REQUEST: days must be between 1 and 90` |
| 외부 차단 인스턴스(18082) | **PAUSED / GARMIN_UNAVAILABLE** (fail-closed, 데이터 변경 0) |

### 3.9 STEP 3 집계 (지시 요구 형식)

| 구분 | Garmin 경로(검증 완료) | Intervals 전용(미구현) | Strava 전용(복구 불가) |
|---|---:|---:|---:|
| 수집 대상 | 10 | 27 | 34 |
| 성공 | **8** | 0 | 0 |
| 중복(멱등 스킵) | 8 (재실행 시) | – | – |
| 실패 | **0** | – | – |
| 미지원 | **2** (`breathwork`) | 2 예상 (`Ride`/`Workout`) | 미상 |
| 결측 | 랩/샘플 결측 0, 회복 결손 **0일**(2026-09-17 이후) | 랩 **27건 전부 결측** | 전부 |

---

## 4. STEP 4 — MCP 쓰기 확장: 기존 구현 현황 조사 (구현 보류)

지시가 요구한 기능별로 **기존 Spring 구현 여부**를 실제 컨트롤러에서 확인했다.

| 목표 기능 | 기존 구현 | 필요한 작업 |
|---|---|---|
| Garmin 수동 동기화 | `POST /api/v1/garmin/sync`, `GET /sync/status` | **Adapter만** |
| Garmin 회복 동기화 | `POST /api/v1/garmin/recovery-sync`, `/backfill` | **Adapter만** |
| Garmin 프로필 동기화 | `POST /api/v1/garmin/profile-sync` | **Adapter만** |
| Intervals 수동 동기화 | `POST /api/v1/intervals/enrichment/activities/{id}`, `/fitness`(+`/reprocess`) | **Adapter만** |
| Backfill 요청·상태 | `POST /api/v1/historical-backfill`, `/{runId}/resume`, `GET /{runId}` | **Adapter만** |
| 선수 프로필 조회·수정 | `GET`/`PUT /api/v1/athlete/intensity-profile` (+V23 이력 원장) | **Adapter만** |
| 부상 보고·조회 | `POST`/`GET /api/v1/athlete/injury-reports`, `GET /injury-state` | **Adapter만** |
| AI Coach 분석 요청 | `POST /api/v1/coach/data-refresh`, `GET /api/v1/coach/training-context` | **Adapter만** |
| 훈련 Draft 생성 | `POST /api/v1/workout-drafts` | **Adapter만** |
| 훈련 Draft 수정 | `POST /api/v1/workout-drafts/{id}/revisions` | **Adapter만** |
| 훈련 승인 | `POST /api/v1/workout-drafts/{id}/approve` | **Adapter만** |
| 게시 준비·게시 | `GET /{id}/publish-preview`, `POST /{id}/publish` | **Adapter만** (+확인 흐름 §5) |
| 활동 분석 요청 | `POST`/`GET /api/v1/activities/{id}/analysis` | **Adapter만** |
| 게시 결과·실패 조회 | **부분** — 재게시 시 저장된 결과를 돌려주지만 전용 조회 없음 | 읽기 엔드포인트 1개 신설 |
| 훈련 계획 목록·이력 | **없음** — `GET /api/v1/workout-drafts/{id}`만 있고 **목록 조회 없음** | 목록 엔드포인트 신설 |

→ **16개 중 14개는 비즈니스 로직 신규 작성 없이 MCP Adapter만 추가하면 된다.** 신설이 필요한 것은 2개뿐이다.

Injury Safety Gate는 이미 `WorkoutPublishApplicationService`, `WorkoutDraftService`, `WorkoutDraftApproval`,
`ApprovedWorkoutDraftPublishService`, 그리고 MCP 읽기 도구에까지 연결돼 있다. **우회 경로를 만들 필요가 없다.**

---

## 5. STEP 5 — OAuth 권한 분리 (설계, 미구현)

현재 상태(실측): Worker `src/index.js`에 `REQUIRED_SCOPE = 'runningai:read'` 단일 상수,
Gateway `server.js`에 `RUNNING_AI_REQUIRED_SCOPE` 단일 검사. **도구별 scope 검사는 존재하지 않는다.**

제안 scope 체계:

| scope | 대상 도구 |
|---|---|
| `runningai:read` | 기존 읽기 7종 + 신설 조회 도구 |
| `runningai:sync` | Garmin/Intervals 동기화, Backfill 시작·재개, 활동 분석 |
| `runningai:train` | Draft 생성·수정·승인, 프로필 수정, 부상 보고 |
| `runningai:publish` | **외부 게시 전용** (가장 좁게) |

구현 지점:
1. Worker: 승인 화면에서 scope 선택·기록, 홉 토큰에 **그 요청에 필요한 scope만** 담는다(현재 `requiredScope` 상수 → 요청별).
2. Gateway: JSON-RPC 본문의 `tools/call` 도구명을 읽어 **도구→필요 scope 표**로 검사(현재는 전역 1회 검사뿐).
3. ChatGPT 명시 확인 필요 작업(게시 / 게시물 변경 / 대량 수정 / 삭제·초기화)은 도구 설명과
   `confirm: true` 필수 인자로 2단계화하고, 게시 도구는 **Draft ID + 대상 날짜 + 내용 요약을 에코백**한 뒤 실행.
4. 이중 게시 방지는 기존 수단으로 충분하다 — per-draft in-JVM single-flight + `workout_draft_publication`
   draft당 유니크 + Intervals `external_id` 마커. 재호출은 **저장된 결과 반환**.
5. Injury Gate 정책 변경 없음, 우회 도구 금지.

---

## 6. STEP 6 — 상시 외부 접속 (설계, 미구현)

현재: `cloudflared` Quick Tunnel 프로세스 1개(2026-10-13 12:06 기동) — **주소가 재기동마다 바뀌는 임시 경로**.
Worker 주소(`https://running-ai-mcp.simjjy89.workers.dev`)는 고정이므로 ChatGPT 재등록은 불필요하고,
터널이 바뀌면 **Worker 변수 1개만** 갱신하면 된다.

제안: Quick Tunnel → **Named Tunnel(고정 호스트명) + `cloudflared service install`**(Windows 서비스).
그러면 재부팅 복구·자동 재시도가 서비스 수준에서 해결되고, Worker 변수 갱신이 더 이상 필요 없다.
감시는 기존 `RunningAI-Watchdog` 스케줄 작업에 ① 터널 상태 ② Gateway 17910 ③ Spring 헬스 3종을 추가.
인증 실패·권한 거부·쓰기 감사 로그는 Gateway/Worker에 이미 있는 구조화 로그를 확장(토큰·비밀 미출력 원칙 유지).

**RISK-H3**는 선행 보안 항목으로 유지한다. 이번 작업에서 방화벽·Docker 포트는 **변경하지 않았다.**

참고로 레거시 작업 상태를 확인했다: `RunningAI-TodayWorkout`, `RunningAI-CommandChannel`은 **Disabled**
(CLAUDE.md가 "pending owner action"으로 적어 둔 항목이 이미 처리돼 있다). 나머지 레거시 작업 7종은 Ready 상태.

---

## 7. STEP 7 — ChatGPT 통합 시나리오 검증 (미실행)

시나리오 A·B는 **데이터가 없어 현재 의미 있는 검증이 불가능**하다(전체 이력 104일, 작년 데이터 0건 — §1).
C·D·H는 실데이터 사본에서 이미 성립하고, E·F·G는 STEP 4 Adapter 구현 후에 가능하다.
합성/실데이터 분리 검증 원칙은 유지한다. 실제 Intervals 게시·운영 DB 쓰기는 승인 전까지 실행하지 않았다.

---

## 8. 변경 사항 / 환경 영향

* `server/` 소스 변경 **0건**. 빌드·테스트 재실행 없음(코드 미변경).
* 신규 문서 2개(본 문서 + 지시문 원문). 커밋/푸시 **미실행**.
* 운영 DB `running_ai` **변경 0건**, 운영 Spring 18080 **무접촉**, 18081/18082 **무접촉**.
* `running_ai_realcopy`(사본)는 검증으로 변경됨: `historical_backfill_run` +3행,
  `garmin_recovery_daily` 21→27, `intervals_fitness_daily` 99→100, `intervals_raw_payload` 166→167,
  `activity_analysis` 8행 재계산(다이내믹스 채움). **활동·랩·샘플·상세 행 수는 불변.**
* 검증 인스턴스 **18083은 종료했다**(§참고). `.runtime/logs/bfverify-18083.*.log` 보존.
* 로그·문서에 자격증명/토큰/API Key/비밀번호 **미기록**.

---

## 9. STEP 8 — 운영 전환 승인 항목 (A/B/C, 동시 실행 금지)

### 항목 A — 운영 PostgreSQL 네트워크 보안 개선 + V23 배포

* 내용: `docker-compose.yml` → `ports: ["127.0.0.1:5432:5432"]`, 컨테이너 재생성,
  `main-3` → `main` FF 병합, `pg_dump -Fc` 백업, V21~V23 적용, 운영 JAR 재빌드·재기동.
* 영향: Postgres 5432가 LAN/tailnet에서 사라진다(알려진 소비자는 전부 localhost → 기능 영향 0).
  운영 Spring 재기동 수 분간 중단. injury/다이내믹스/프로필 이력/MCP 읽기 기능이 운영에 들어온다.
* 소요: 약 30~45분.
* 검증: 재기동 후 `/api/v1/health`, `flyway` 23, `injury-state` 200, 비루프백 IP에서 5432 연결 실패 실측.
* 롤백: 입증된 경로 — V20 JAR은 V23 스키마에서도 기동한다(Flyway가 미래 마이그레이션 허용, Hibernate validate 통과).
  `flyway_schema_history` 행은 삭제 금지. 최악의 경우 `pg_restore`로 행 단위 복원.
  **롤백 시 부상 안전 게이트가 사라진다**(엔드포인트 404)는 점을 인지해야 한다.

### 항목 B — 운영 DB 과거 전체 데이터 Backfill

B를 **두 갈래로 분리**해 승인받기를 권한다.

* **B-1 (지금 가능, 저위험)**: 기존 6H-6 Backfill 90일 + 전 활동 강제 재분석.
  * 기대 결과: 활동 신규 0건(이미 포화), **회복 결손 5일 보충**, 러닝 다이내믹스 **7건 보충**.
  * 소요: 약 5분. 외부 쓰기 0. 롤백: 사전 `pg_dump` 복원.
  * 사본에서 **이미 그대로 실행해 검증 완료**(§3).
* **B-2 (신규 구현 필요)**: Intervals 전용 27건 적재(§2.2).
  * 선행: 설계 승인 → 구현 → 테스트 → 사본 검증. 7월 34건은 **이 경로로도 복구 불가**.
  * 소요: 구현 포함 별도 산정 필요.

### 항목 C — ChatGPT MCP 실데이터 읽기·쓰기 및 외부 게시 활성화

* 선행 조건: **A 완료**(RISK-H3 해소) + STEP 4 Adapter 구현 + STEP 5 scope 분리 + STEP 6 고정 터널.
* 전환 자체는 여전히 환경변수 1개(`RUNNING_AI_GATEWAY_TARGET_URL` → 18082/운영) + Gateway 재기동.
* 롤백: 변수 되돌리고 재기동(약 10초). 18081 합성 스택은 롤백 경로로 계속 유지.
* **쓰기·게시 활성화는 읽기 전환과 분리해 별도 승인**할 것을 권한다.

---

## 10. 사용자 확인이 필요한 사항

1. **7월 34건(및 8월 일부)의 다른 출처가 있는가?**
   Strava 계정 데이터 내보내기(bulk export), 원본 앱(Nike/Apple Health), 또는 다른 백업이 있으면
   복구 범위가 2026-07-02까지 넓어진다. 없으면 **RunningAI의 분석 가능 이력은 2026-08-02 시작**으로 확정된다.
2. **Intervals 전용 27건 적재(B-2)를 진행할 것인가?**
   진행 시 `external_source=INTERVALS_ICU` 활동 행이 처음으로 생긴다(스키마 변경 불필요, enum 기존 보유).
   랩이 없으므로 인터벌 구조 분석은 해당 27건에 대해 영구히 불가하다.
3. **B-1(회복 5일 + 다이내믹스 7건 보충)을 A와 함께 승인할 것인가?** 사본에서 검증 완료, 저위험.

---

## 11. 사용자 결정 (2026-10-13, §10 질의 응답)

| § | 질의 | 결정 |
|---|---|---|
| 10.1 | 7월 34건의 다른 출처 | **원본 앱/다른 백업이 있다** → 어떤 앱·백업인지 확인 후 가용성 조사 필요 (미해결) |
| 10.2 | Intervals 전용 27건 적재(B-2) | **진행 — 단, 설계 승인이 선행 조건** |
| 10.3 | B-1(회복 5일 + 다이내믹스 7건) | **항목 A와 함께 승인** |

### 결정에 따른 다음 작업 순서

1. **(차단 중)** 7월 데이터의 원본 앱/백업 종류 확인 → 가용성 조사 → 복구 경로 판단.
2. **B-2 설계서 작성 → 사용자 승인 → 구현 → 사본 검증.** 설계 초안은 §2.2.
3. **항목 A + B-1 실행.** 승인은 받았으나 본 Phase는 지시에 따라 STOP 상태이므로,
   운영 변경(main 병합 / `docker-compose.yml` 포트 / V21~V23 적용 / 운영 Spring 재기동)은
   **실행 시점을 별도로 지시받은 뒤** 착수한다. A/B/C 동시 실행 금지 원칙은 유지.
4. STEP 4 Adapter(14종) → STEP 5 scope 분리 → STEP 6 고정 터널 → 항목 C.
