# 02. 전체 기능 정의 및 상세 동작

> 대상 독자: 전원. 각 기능은 **상태 배지 → 쉽게 말하면 → 기술적으로는 → 계약/제약** 순서로
> 기술한다. 배지 정의는 [00-INDEX §0.1](00-INDEX.md) 참조.

---

## A. 데이터 수집

### A1. Garmin 활동 요약 수집 + 증분 동기화 ✅ `SHIPPED`

**쉽게 말하면** — 시계로 뛴 기록을 가져온다. 전부 다시 가져오는 게 아니라 "마지막으로 성공한
지점 이후"만 가져온다.

**기술적으로는** — Spring은 Garmin 자격증명을 **절대 보유하지 않는다.** 로컬 Python 커넥터
(`tools/garmin-connector`, 127.0.0.1:8765)가 유일한 자격증명 보유자이고, Spring은 그 커넥터의
HTTP만 호출한다. 체크포인트는 `garmin_sync_state`에 있으며 **성공한 것만** 기록한다.
매핑 불가능한 항목이 섞이면 `PARTIAL`로 끝나고 **체크포인트를 전진시키지 않는다.**

| 계약 | 값 |
| --- | --- |
| 트리거 | `POST /api/v1/garmin/sync` (수동) · `GarminSyncScheduler` (⏸️ `OFF`) |
| 페이지 크기 / 겹침 | 50 / 7일 / 최대 10페이지 |
| 중복 실행 방지 | `GarminSyncAlreadyRunningException` (단일 비행) |
| 시도 원장 | `garmin_sync_attempt` (V27/V30): `STARTED` → `SUCCESS` / `PARTIAL` / `FAILED` / `INTERRUPTED` |

### A2. Garmin 상세 수집 (랩·존·샘플) ✅ `SHIPPED` · `LIVE_VERIFIED`

**쉽게 말하면** — 한 번의 달리기 안에서 "언제 얼마나 빨랐는지"를 초 단위로 가져온다.

**기술적으로는** — raw-first 원칙: 원본 payload를 `activity_raw_payload`(V11)에 **먼저 독립
커밋**하고, 순수 매퍼가 `activity_detail` / `activity_lap` / `activity_zone` / `activity_sample`
(V12)로 변환한다. **Garmin 필드명은 매퍼에서 끝난다** — 도메인에 누출되지 않는다.

실측으로 확정된 제약 (Phase 6H-1B, 실제 4개 활동):

- `metricsIndex`는 payload마다 다르다 — **인덱스를 하드코딩하면 안 된다.** 반드시 각 payload의
  `metricDescriptors`로 파싱한다 (한 기기에서 4가지 레이아웃이 관측됨).
- `directTimestamp`는 epoch ms의 **JSON float**
- descriptor의 `unit.factor`는 **변환 제수가 아니다**
- lap의 `lapIndex`는 1-based, `intensityType`(WARMUP/ACTIVE/RECOVERY/COOLDOWN)과 `wktStepIndex`를
  보유. **랩과 워크아웃 스텝은 1:1이 아니다**
- 존은 항상 5개, 상한 없음. 파워 없는 활동의 파워 존은 `[]` → `EMPTY`이지 실패가 아니다

**샘플 충실도** — `/samples`는 기본적으로 ~2000 샘플 초과 시 **다운샘플링**한다.
`GARMIN_DETAIL_SAMPLES_MAX_CHART_SIZE`(기본 20000)를 항상 보내되, **요청했다는 사실이
완전성을 뜻하지 않으므로** 모든 수집이 `sample_completeness`(FULL / DOWNSAMPLED / UNKNOWN)를
`activity_detail_collection`(V14)에 기록한다. 판정 권위는 응답의 `totalMetricsCount`다.
**DOWNSAMPLED 응답을 다른 크기로 재요청하지 않는다.**

### A3. Garmin 회복 지표 (HRV·수면·안정시심박·Body Battery·스트레스) 🟢 `BUILT`

**쉽게 말하면** — "오늘 몸이 얼마나 회복됐는지"를 시계에서 가져온다.

**기술적으로는** — 커넥터 `GET /recovery?date=` → `GarminRecoveryMapper` →
`garmin_recovery_daily`(V7, athlete+date 유니크, **멱등 병합: 없는 지표가 저장된 지표를 지우지
않는다**) → `RecoveryBaselineService`(28일 개인 베이스라인, 유효일 7일 미만이면
`INSUFFICIENT_DATA`) → `RecoveryContextBuilder` → 코치 컨텍스트.

**Spring은 회복 수치를 훈련 결정으로 바꾸지 않는다. 그건 코치의 일이다.**

> ⚠️ 운영 실측: `garmin_recovery_daily` **21행** — 28일 창을 못 채워 베이스라인이 아직
> 실사용 불가. 감사 보고서 P3-2가 이 공백을 지적한다. 회복 **수집**은 동작하나 **베이스라인
> 산출의 운영 실증은 없다.**

### A4. Intervals.icu 보강 (CTL/ATL/Form) ✅ `SHIPPED` · `LIVE_VERIFIED`

**용어를 섞지 않는다** (이 저장소의 명시적 규칙):

| RunningAI 용어 | Intervals 필드 | 비고 |
| --- | --- | --- |
| CTL = 계산된 피트니스 | `icu_ctl` / wellness `ctl` | |
| ATL = 계산된 피로 | `icu_atl` / wellness `atl` | |
| — | wellness `fatigue` | **주관적 입력. ATL에 절대 매핑하지 않는다** |
| `derived_form` | (API 필드 없음) | `ctl - atl`, **RunningAI 파생**임을 명시. 둘 다 있을 때만 |

Intervals를 향한 읽기 경로는 **구조적으로 쓰기 메서드가 없는** `IntervalsReadClient`다
(GET 전용, 호출당 1요청, 재시도 없음, 429면 중단).

**Intervals 활동은 두 번째 Activity 행이 되지 않는다** — `activity_source_link`(V17)가
Intervals id를 기존 활동에 붙인다. 매칭 우선순위: `SOURCE_ID`(`source=GARMIN_CONNECT` +
`external_id`=Garmin id) → `EXTERNAL_ID` → `COMPOSITE`(실측 관측된 타입 쌍만, 허용오차
|start|≤30s, |duration|≤5s, |distance|≤5m). 2건 이상이면 `AMBIGUOUS`로 **자동 링크하지 않는다.**

### A5. Intervals.icu 전용 활동 임포트 ✅ `SHIPPED` (운영 25건)

**쉽게 말하면** — Garmin에는 없고 Intervals에만 있는 과거 기록을 가져온다.

**기술적으로는** — Garmin Connect가 2026-09-18 이전을 보유하지 않아 27개 세션이 Intervals에만
존재했고, 90일 백필은 Garmin 탐색 기반이라 **원리적으로 도달 불가**였다(실측: 90일 재실행 시
0행 추가). 후보는 **같은 날의 저장된 활동들과 매칭**된다 — 1건 매치면 링크+보강, 2건 이상이면
`SKIPPED_AMBIGUOUS`(자동 병합 금지), 0건일 때만 신규 생성.

실측으로 확정된 제약:

- 모든 스트림은 길이가 같다. `time`은 **인덱스가 아니라 경과 초**이므로 샘플 시각은
  `start_date + time[i]`
- `latlng`는 위도가 `data`, 경도가 `data2`
- `icu_lap_count`는 27건 전부 **0**
- **케이던스를 변환하지 않는다** — Intervals는 한쪽 다리 rpm, 컬럼은 Garmin spm(배수 2, 산술
  검증됨). 스트림은 `extra_metrics`에 원천 키로 남고 컬럼은 null,
  `CADENCE_SOURCE_RPM_NOT_NORMALIZED` 기록 → **임포트 활동은 케이던스 지표가 없다**
- **랩을 날조하지 않는다**(`SPLITS` = `EMPTY`), **Intervals HR 존을 임포트하지 않는다**(7존 vs 5존)
- `sample_completeness`는 항상 `UNKNOWN` — Intervals는 네이티브 포인트 수를 밝히지 않고
  `/streams`에 크기 파라미터가 없어 FULL을 증명할 방법이 없다

### A6. Strava 아카이브 임포트 ✅ `SHIPPED` (운영 15건 신규 + 33건 보강)

**쉽게 말하면** — Strava에서 내려받은 내보내기 ZIP 안의 FIT 파일을 읽어 과거 기록을 복원한다.

**기술적으로는** — 이 활동들은 **어떤 API로도 도달 불가능**하다(Intervals는 전건
`422 Cannot read Strava activities via the API`, Garmin은 2026-09-18 이전이 없음). 그래서 소스는
**로컬 디렉터리**이고, 디코딩은 Garmin 공식 FIT SDK(`com.garmin:fit:21.176.0`, **디코드 전용**)가
한다. 이 저장소에 **Strava HTTP 클라이언트는 없고 추가하지 않는다.**

**조인은 휴리스틱이 아니라 정확하다**: Strava가 Apple Health 워크아웃을 FIT으로 재인코딩할 때
세션 메시지에 개발자 필드 `apple_health_uuid`를 찍는데, 그 값이 Intervals.icu의 `external_id`와
**문자 단위로 일치**한다(실측 27/27, 모순 0). 매치가 없으면 신규 활동이지만, 비-Strava 활동과
±120초 시작시각 충돌이 있으면 중복 생성 대신 `SKIPPED_AMBIGUOUS`.

**저장된 값을 절대 덮어쓰지 않는다** — 아카이브와 Intervals는 같은 운동의 **서로 다른 독립
내보내기**이고 각자 상대가 없는 것을 갖는다(Intervals는 8월 FIT에 없는 HR을 100% 보유, FIT은
훨씬 많은 러닝 파워와 Intervals가 0이라던 랩을 보유). 그래서 NULL만 채우고
`"<metric>_source":"STRAVA_FIT"`를 `extra_metrics`에 찍는다. 랩은 없을 때만 쓰되, **이 임포터가
직접 쓴 랩**은 다시 쓸 수 있다(`laps_written` 추적 → 매퍼 수정이 과거 데이터에 도달 가능).
**Garmin 자신의 랩은 절대 건드리지 않는다.** 저장된 샘플과 매칭되지 않는 FIT 레코드는
**추가하지 않고 버린다.**

| 제약 | 내용 |
| --- | --- |
| 타입 판정 | **FIT의 sport가 결정한다. Strava 라벨이 아니다** (내보내기는 `cycling`/`indoor_cycling` 파일에 "Ride"라고 적는다) |
| 미지원 종목 | 거부. 근력/걷기/계단은 **근사하지 않고 제외**. 야외 라이딩은 `ActivityType` 자체가 없음 |
| 케이던스 | 변환하지 않음 (A5와 동일 결정) |
| 인터벌 그룹 | **원리적 불가** — 재인코딩에 `intensity` / `wkt_step_index` 없음(`lap_trigger=manual`뿐). 페이스로 ACTIVE/RECOVERY 추론은 **금지** |
| 랩 평균속도 | RunningAI 파생(`average_speed_source=DERIVED_DISTANCE_OVER_TIME`) |
| `sample_completeness` | **FULL** — FIT 활동 파일은 장비 기록 그 자체이고 스스로 개수를 밝힌다 (A5의 UNKNOWN과 대비) |
| 분석 재실행 | 랩을 쓰거나 샘플을 채웠으면 **직접 `analyse()` 호출**. `analysis_input_digest`는 파트를 상태와 **행 수**로 요약하므로, 같은 개수의 더 풍부한 랩으로 교체된 것을 digest가 알아볼 수 없기 때문 |

### A7. 90일 과거 백필 파이프라인 🟢 `BUILT` (운영 실행 이력 없음)

7단계 재개 가능 파이프라인: `GARMIN_DISCOVERY` → `GARMIN_DETAIL_ANALYSIS` →
`INTERVALS_ACTIVITIES` → `INTERVALS_FITNESS` → `GARMIN_RECOVERY`(28일 고정, 넓히지 않음) →
`VERIFY` → `COMPLETED`. 작업 단위마다 체크포인트 1커밋(V19). 요청 스레드에서 동기 실행되며
**스케줄러 없음, 자동 재개 없음** — `PAUSED`는 안전한 정지이고 사람이 `/resume`한다.
`FAILED`는 **재개 불가**, `COMPLETED` 재개는 결정론적 409.

- 기존 서비스만 재사용한다. **`garmin_sync_state`를 절대 건드리지 않는다.**
- 탐색은 재개 시 항상 offset 0부터 다시 시작한다. 상한 초과는 잘라내지 않고 `PAUSED`.
- NORMALIZED 스트림이 FULL이 아니면 `PAUSED` — **다른 maxChartSize로 자동 재시도하지 않는다.**
- 최종 VERIFY 충실도 스윕은 **GARMIN 출처 활동만** 대상으로 한다(다른 소스가 만든 활동은 이
  파이프라인이 수집한 적이 없다).

> 🔒 **안전 연동**: 네 개의 게시 스위치와 두 개의 Garmin 스케줄러가 **모두 false**가 아니면
> 기동을 거부한다(`HISTORICAL_BACKFILL_UNSAFE_RUNTIME`). 외부 쓰기는 어디에도 없다.

### A8. 수집 관측성 — "실패는 부재가 아니다" ✅ `SHIPPED` (Phase 8A)

`GET /api/v1/garmin/recovery-sync/coverage` — 저장된 값과 수집 기록을 조인해
COMPLETE / PARTIAL / CONFIRMED_NO_DATA / COLLECTION_FAILED / NOT_COLLECTED로 답한다.
**저장된 값이 먼저 결정하고, 기록은 아무것도 저장되지 않았을 때만 발언한다.**
`POST .../backfill`의 `onlyMissing`(**기본 false — 기존 동작과 바이트 동일**)은 COMPLETE와
CONFIRMED_NO_DATA 날짜를 커넥터 호출 없이 건너뛴다.

`GET /api/v1/data-freshness` — DB만 읽고(네트워크 0회) 모든 소스를 균일하게 보고한다.
최신 데이터 날짜는 **집계 창과 독립된 365일 넓은 조회**로 찾으므로, 오래된 소스가 "데이터
없음"처럼 읽히는 null 대신 **진짜 나이**를 보고한다. 핵심 필드는 `collectionTracked`: 수동
임포터는 시도 원장이 없으므로 **false**이고, 그래서 그들의 오래된 데이터가 "새 게 없다"로
오독되지 않는다.

**기록이 결과를 바꾸지 않는다** — 관측 기록은 자체 `REQUIRES_NEW` 트랜잭션에서 실행되고,
기록 실패는 로그만 남기고 삼켜진다.

---

## B. 분석

### B1. 러닝 분석 엔진 ✅ `SHIPPED` (운영 48/48)

**쉽게 말하면** — 뛴 기록에서 "전반/후반 페이스 차이", "심박 존별 시간", "인터벌 반복별 기록"
같은 **객관적 사실**만 뽑는다. 잘했다/못했다는 말하지 않는다.

**기술적으로는** — 저장된 행만 읽는다(Garmin 호출 0, Intervals 호출 0, 게시 0).

| 규칙 | 내용 |
| --- | --- |
| 결측 | **null. 절대 추정하지 않는다** |
| 전후반 분할 | **경과 시간 중앙값**. 샘플 인덱스 분할·인덱스 폴백 **없음** |
| 이동시간 필터 / 속도 임계 | **없음** |
| 존 비율 | 활동 길이가 아니라 **존 합계** 기준 |
| LTHR 노출 | **실제 샘플 간격을 적분**(마지막 샘플은 기여 0). LTHR 없으면 null |
| `speed_hr_decoupling_percent` | `(EF1-EF2)/EF1*100`, `EF=avgSpeed/avgHR` — **RunningAI 파생 지표**(Garmin도 Intervals도 아님), 임계값 없음 |
| CV | 전부 **모집단 SD**(제수 n) |
| 케이던스 지표 | RUN / TREADMILL_RUN 에서만 |
| 인터벌 구조 | **읽는다, 추론하지 않는다** — `intensity_type` + `workout_step_index`가 같은 연속 랩(V15 1급 컬럼). 그룹 성립 조건은 ACTIVE 존재 + 같은 스텝 2회 이상 + 사이의 RECOVERY 블록. 속도/심박 패턴으로 추론 **금지** |
| `recovery_hr_drop_bpm` | RunningAI 인터벌 회복 심박 변화(고정 10초 창). **Garmin의 Recovery HR이 아니다** |

**이 패키지에는 점수·등급·판정이 들어갈 수 없다.** `analysis_status`(COMPLETE / PARTIAL /
INSUFFICIENT_DATA)조차 **세션이 아니라 데이터**의 상태다.

**분석 신원은 문자열이 아니라 세 가지 사실이다** (Phase 7E-1, V24):

```
analysis_version          = 출력 계약        (RUNNING_ANALYSIS_V1)
analysis_engine_revision  = 값을 만든 로직    (0=추적 이전, 1=6H-4, 2=V22 다이내믹스)
analysis_input_digest     = 입력의 SHA-256   (타임스탬프 없음 → 동일 데이터 재수집은 변경이 아님)
```

> 📏 **규칙**: 이 패키지가 쓰는 값을 바꿀 수 있는 모든 변경은 **같은 커밋에서**
> `RUNNING_ANALYSIS_ENGINE_REVISION`을 올린다. 그러면 모든 재사용 판정이 마이그레이션 없이
> 자동으로 따라온다. 재사용 여부는 **`AnalysisReusePolicy` 단일 지점**만 답하며, digest가 없는
> 행은 절대 재사용되지 않는다. 재분석은 **모든 새 값이 존재한 뒤 한 트랜잭션에서 치환**되므로
> 실패가 저장된 결과를 파괴하지 않는다.

### B2. 훈련 분석 리포트 🟢 `BUILT`

`GET /api/v1/training-analysis-report` — 보유 이력 전체를 집계한다(커버리지, 균등 올림 분할
볼륨, 표준 거리 근처 최고 노력 **페이스 비교**, 지구력, 다이내믹스 추세, 한계).
**측정하고 판단하지 않는다** — 응답에 rating/score/verdict 계열 단어가 나타나면 테스트가
빌드를 실패시킨다. **"최고 노력"은 그 거리 근처의 가장 빠른 활동 전체이지 검증된 PB가 아니다.**

### B3. 지표 결측 사유 명시 ✅ `SHIPPED` (Phase 8B-F)

`MetricAvailability` — "이 활동이 **구조적으로** 가질 수 없는 지표와 그 이유"를 말하며,
`dataCoverage.metricUnavailability`와 활동별 `dataQuality.unavailableMetrics`로 노출된다.
5개 중 4개는 **임포트의 속성**이라 `external_source`로 판정한다(케이던스, 러닝 다이내믹스,
인터벌 그룹, HR 존 — **두 임포터 모두 `activity_zone`을 쓰지 않는다**).

나머지 하나 `LAP_STRUCTURE`는 **소스의 속성이 아니라서 행에게 묻는다** — Strava 아카이브
임포트가 Intervals 출처 활동에 랩을 써 넣기 때문에, 같은 행이 `external_source` 변경 없이 랩을
얻는다. 소스로 판정했을 때는 랩이 있는 25개 Intervals 출처 활동 전부를 "랩 없음"이라고 잘못
주장했다. 지금은 창당 한 번의 배치 조회(`ActivityDetailStore.activityIdsWithLaps`)로 답하며,
덕분에 상세를 수집한 적 없는 Garmin 활동도 그 사실을 스스로 말한다.

프롬프트에는 **"없는 지표를 낮은 값으로 읽지 말라"** 는 규칙이 명시된다.

---

## C. AI 코칭 (상세: [07장](07-ai-coaching.md))

| 기능 | 상태 | 한 줄 |
| --- | --- | --- |
| C1. Claude CLI 코치 | ✅ `SHIPPED` | 도구 전면 차단, stdin 프롬프트, 재시도 없음 |
| C2. TrainingContext V1 / V2 | ✅ `SHIPPED` | 운영은 V2. 둘 다 `CoachTrainingContext` 구현 |
| C3. 초안 생성·수정·승인 | ✅ `SHIPPED` | 운영 19건 / 승인 3건 |
| C4. 부상 이력 컨텍스트 | 🟢 `BUILT` | 운영 원장 2행이 모두 RESOLVED라 E2E 미검증 |
| C5. 컨텍스트 스냅샷 영속화 (V20) | ✅ `SHIPPED` | 초안마다 version/snapshot/sha256 보존 |

---

## D. 게시 (상세: [08장](08-workout-lifecycle.md))

### D1. Intervals 워크아웃 렌더러 ✅ `SHIPPED` · `DEVICE_VERIFIED`

`StructuredWorkout` → Intervals.icu Workout Builder 텍스트. 결정론적, 순수 함수, HTTP 없음.
`M:SS/km Pace` 문법과 %LTHR, 트레드밀 큐는 Garmin FR265에서 **DEVICE_VERIFIED**(Phase 5C-4).

### D2. 게시자 (CREATE / UPDATE / NO_CHANGE + readback) ✅ `SHIPPED`

소유권 마커는 `external_id`. **실패한 POST를 맹목적으로 재시도하지 않는다** — 대신 목록을
다시 읽는다("re-list, never re-POST"). 저장소 전체에서 Intervals에 POST/PUT 할 수 있는 코드는
`HttpIntervalsWorkoutClient` 하나뿐이고, 그것을 호출하는 것은 `IntervalsWorkoutPublisher`
하나뿐이다. **이것이 유일한 정식 게시 경로다** (Phase 5C-5).

### D3. 승인 드래프트 게시 게이트웨이

| 분기 | 상태 | 근거 |
| --- | --- | --- |
| UPDATE | ✅ `SHIPPED` | 운영 `workout_draft_publication` 3건 전부 PUBLISHED/UPDATED/verified=true |
| CREATE | 🟢 `BUILT` (운영 기준) | 7F-2F에서 event 144442046 CREATED 성공했으나 **dev DB**에서였다 |
| REST 일 | ✅ `SHIPPED` | `SKIPPED_REST_DAY` — 렌더러·게시자·클라이언트 호출 0회 |

**손실 초안은 fail-closed** (`UNPUBLISHABLE_DRAFT`): 절대 bpm 심박 타깃(렌더러는 %LTHR만 안다),
반쪽 범위, 경사 없는 트레드밀 속도, 음수 경사, 페이스/속도 없는 MODERATE·HARD,
CROSS_TRAINING·미지 타입, 그리고 Phase 10의 `POWER` 기준.

### D4. 게시 시도 원장 ✅ `SHIPPED` (V29)

`workout_draft_publish_attempt` — append-only, 시도당 1행. `workout_draft_publication`은
**성공 전용 그대로**다. 게시 경로의 어떤 코드도 이 원장을 읽지 않으며, **재시도를 유발하지
않는다.** `external_write_attempted`와 `remote_state_certain`은 **서로 다른 질문**이라 별개
컬럼이고, 타임아웃이면 후자가 false다. 메시지가 아니라 **코드만** 저장한다.
조회: `GET /api/v1/workout-drafts/{id}/publish-attempts`

### D5. 게시 후 수정 (revision) ✅ `SHIPPED` (Phase 10, V31, 실제 적용 1건)

게시된 워크아웃을 **승인 이력을 건드리지 않고** 바꾸는 유일한 경로. 상세는 [08장 §5](08-workout-lifecycle.md).

### D6. 레거시 날짜 기반 게시 ⏸️ `OFF`

`POST /api/v1/workout-publish` → `WorkoutPublishApplicationService`. 결정론적 처방 경로이며
AI 초안 경로와 **XOR**로 강제된다(둘 다 켜면 기동 거부).

---

## E. 조작 표면

| 표면 | 상태 | 설명 |
| --- | --- | --- |
| REST API (~45 매핑 / 31 컨트롤러) | ✅ `SHIPPED` | 전체 기능의 정본 |
| MCP 도구 **20종** | ✅ `SHIPPED` | read 7 + train 5 + publish 1 + revision 6 + 레거시 1(영구 차단) |
| PowerShell 운영 CLI | ✅ `SHIPPED` | `scripts/windows/*` — 시작·정지·상태·워치독·게시 |
| 워치페이스 릴레이 | ✅ `SHIPPED` | `{date,status,label}` 와이어 계약 |
| 웹 UI | ⛔ `ABSENT` | 존재하지 않음. ChatGPT가 사실상의 UI |

---

## F. 운영·감시 (상세: [15장](15-operations-and-security.md))

| 기능 | 상태 |
| --- | --- |
| Windows 워치독 (3범주 감시·복구) | ✅ `SHIPPED` |
| Garmin 동기화 서킷 브레이커 | ✅ `SHIPPED` (Phase 8B-A) |
| 스케줄러 5종 | 구현 완료 / 대부분 ⏸️ `OFF` |
| 중복 실행 방지 (단일 비행 4종) | ✅ `SHIPPED` |
| 롤백 자산 (`scripts/ops/v30-rollback-*.sql`) | ✅ `SHIPPED` |

---

## G. 미구현 — 공백의 정직한 목록

| 항목 | 상태 | 사유 |
| --- | --- | --- |
| 결정론 처방의 QUALITY 워크아웃 | ⛔ `ABSENT` | `WorkoutPrescriptionPolicy`가 `QUALITY_PRESCRIPTION_NOT_SUPPORTED`로 **명시 거부**. AI 초안 경로에는 존재 |
| 결정론 처방의 인터벌/반복 구조 | ⛔ `ABSENT` | 동일 |
| 러닝 파워 게시 | ⛔ `ABSENT` | `POWER` 기준은 **표현·검증은 되나 게시에서 거부**. 검증된 Intervals.icu 러닝 파워 문법이 이 저장소에 없다. 렌더러는 조용히 타깃 없는 스텝으로 격하되느니 **예외를 던진다**. 해제 조건 = 문법 검증 + 렌더 분기 추가 + 거부 삭제 |
| 절대 bpm 심박 타깃 게시 | ⛔ `ABSENT` | 렌더러는 %LTHR만 안다 (6G부터 동일 사유) |
| Garmin 쓰기 경로 | ⛔ `ABSENT` (설계상 영구) | 커넥터 테스트가 AST를 파싱해 쓰기 함수명이 등장하면 빌드를 실패시키고, 모든 라우트가 GET임을 단언 |
| 시계 도달 기계 확인 | ⛔ `ABSENT` | `deviceDelivery`는 영구 `UNKNOWN`. 5C-4의 FR265 확인은 **사람의 육안 1회** |
| 사이클링 임계 프로필 / 레이스 페이스 / RPE | ⛔ `ABSENT` | — |
| 놓친 실행 보정 / 재시도 / 알림 | ⛔ `ABSENT` | **의도적** |
| 임포트 활동 케이던스 | ⏸️ 의도적 비활성 | rpm↔spm 미변환 결정 |
| Strava 랩의 인터벌 그룹 | ⛔ `ABSENT` (원리적 불가) | 재인코딩에 `intensity` / `wkt_step_index` 없음 |
| 장기 훈련 계획 · 대회 목표 | ⛔ `ABSENT` → 🔵 `DESIGN` | [10장](10-training-plan-and-goals.md) |
| 멀티 사용자 / 계정 분리 | ⛔ `ABSENT` → 🔵 `DESIGN` | `athlete` 테이블 주석 "single-user today, multi-athlete ready", 전 서비스가 `getDefaultAthlete()` 호출. [14장](14-multi-user-isolation.md) |
| 공개 배포 / 설치 마법사 | ⛔ `ABSENT` → 🔵 `DESIGN` | [13장](13-distribution-and-install.md) |
| 웹 대시보드 | ⛔ `ABSENT` → 🔵 `DESIGN` | — |
