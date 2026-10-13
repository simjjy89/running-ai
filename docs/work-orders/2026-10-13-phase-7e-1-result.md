# Phase 7E-1 — Intervals 전용 활동 Importer + 분석 버전 관리 수정 결과

작성일 2026-10-13 · 브랜치 `main-3` @ `34cf121`(미커밋 작업 트리)
지시문 원문: [2026-10-13-phase-7e-1-instruction.md](2026-10-13-phase-7e-1-instruction.md)
선행 조사: [2026-10-13-phase-7e-0-step1-3-result.md](2026-10-13-phase-7e-0-step1-3-result.md)

**운영 DB `running_ai` 변경 0건 · 운영 Spring 18080 무접촉 · 외부 쓰기 0건 · 커밋/푸시 미실행.**
STEP 1~7 전부 수행했고, 지시대로 개발·사본 검증 완료 후 STOP한다.

---

## 0. 한 줄 결론

Intervals.icu에만 존재하던 과거 기록 **25건(46,880 샘플)** 이 사본 DB에 실제로 적재돼 분석까지 완료됐고,
**분석 버전 문자열만으로는 구버전 분석을 구별할 수 없던 결함**은 "문자열 변경"이 아니라
**계약(contract) / 엔진 리비전 / 입력 다이제스트 3요소 정책**으로 교체돼 기존 8건이 자동 재분석 대상으로
잡히는 것까지 실데이터 사본에서 확인됐다. 전체 테스트 **1,331건 전부 통과**.

---

## 1. STEP 1 — 기존 산출물 확인

| 항목 | 상태 |
|---|---|
| 브랜치 / HEAD | `main-3` @ `34cf121` |
| 7E-0 결과보고서 | 검토 완료, 본 작업의 전제로 사용 |
| 미커밋 문서 15건 + `tools/mcp-worker/` | **그대로 보존**(삭제·수정 없음) |
| 운영 `running_ai` | **V20 유지**, 8 활동 / 21,828 샘플 — 본 작업 종료 시점에도 동일 |
| 개발 사본 `running_ai_realcopy` | V23 → 본 작업에서 V25로 전진(사본 한정) |
| ChatGPT OAuth 연결 | **무변경** — Worker / Gateway / 합성 스택 설정 미접촉 |

---

## 2. STEP 2 — Intervals 전용 활동 Importer (구현 완료)

설계 문서: [`docs/architecture/intervals-activity-import.md`](../architecture/intervals-activity-import.md)

### 2.1 라이브 계약 확인 (2026-10-13, 실계정 27건 전수 실측)

문서 추정이 아니라 **실제 응답을 전수 측정**해 매퍼를 작성했다. 핵심 사실:

* `/streams`는 스트림 객체 배열이고, **한 활동 안에서 모든 스트림의 `data` 길이가 동일**(27/27).
* `time`은 **인덱스가 아니라 경과 초**다. 마지막 값이 `elapsed_time`과 일치(27/27)하고, 기록 공백이 있으면
  배열이 더 짧다. 따라서 샘플 시각은 `start_date + time[i]`이며 `start_date + i`가 **아니다**.
  (실측: `i180579414`는 6,651 포인트인데 `time` 최대값이 6,880)
* `latlng`는 **위도 = `data`, 경도 = `data2`**.
* `icu_median_time_delta` = 1 (27/27) → 네이티브 1 Hz.
* 스트림 13종, 활동별 커버리지 상이: `time`/`heartrate` 27, `distance`/`velocity_smooth` 26,
  `cadence` 25, `watts`·`altitude`·`latlng`·`torque`·`fixed_altitude` 19,
  `stance_time`·`vertical_oscillation` 18, `step_length` 17.
* `icu_lap_count`는 **27건 전부 0**.
* `start_date_local`은 `start_date`를 Asia/Seoul로 본 값과 **27/27 정확히 일치**.
* `OAUTH_CLIENT` 활동의 `external_id`는 클라이언트가 만든 **UUID** — Garmin ID가 아니다.

### 2.2 요구사항 1~12 대응

| # | 요구사항 | 구현 |
|---|---|---|
| 1 | 1 Hz 스트림 13종 수집 | `IntervalsStreamMapper` — 13종 전부 인식, 활동별 보유/결측/미지원을 각각 보고 |
| 2 | 원본 Raw 저장 | 요약 → `intervals_raw_payload(ACTIVITY)`, 스트림 → `activity_raw_payload(INTERVALS_ICU, ACTIVITY_DETAILS_STREAM)`. **둘 다 매퍼보다 먼저 독립 트랜잭션으로 커밋** |
| 3 | 기존 Activity 모델 정규화 | `ActivityService.upsertExternalActivity` 재사용 — 신규 모델 없음 |
| 4 | Garmin/Strava와 구별되는 출처 | `external_source = INTERVALS_ICU` + `intervals_activity_import.source_label`(OAUTH_CLIENT / STRAVA / GARMIN_CONNECT) |
| 5 | 원본 Activity ID 보존 | `external_id = i180579445` (Intervals ID 원문). **Garmin ID 생성 0건** |
| 6 | 날짜·타임존·거리·시간 검증 | 시작 시각 존재 / `start_date_local`이 선수 타임존의 `start_date`와 일치 / 지속시간 > 0 / 거리 ≥ 0 — 위반 시 행 생성 전에 `FAILED` |
| 7 | 심박·케이던스·파워·보폭·접지시간·수직진폭 | 심박·파워는 컬럼, 나머지는 `extra_metrics`에 **원 단위 그대로**(§2.4) |
| 8 | 기존 활동과 중복 매칭 | 기존 `IntervalsActivityMatcher`를 **역방향으로 그대로** 사용(§2.3) |
| 9 | 재실행 멱등성 | `uk_activity_external` + 활동별 커밋 레코드. 실측: 재실행 시 **12개 테이블 전부 Δ=0** |
| 10 | 실패 시 재시도·진행 상태 | `intervals_activity_import` 1행/활동, 커밋 단위 진행 기록. **재-POST가 곧 재개이자 재시도**, `attempt_count` 누적 |
| 11 | 데이터 품질·결측 표시 | `stream_types_available/imported`, `missing_stream_types`, `sample_count`, `sample_span_seconds`, `quality_flags` |
| 12 | 중복 활동의 원본 ID 연결 보존 | `activity_source_link` + 레코드의 `source_external_id` / `strava_activity_id` |

### 2.3 중복 방지 — "애매하면 병합하지 않는다"

적재 전 후보를 **그 날짜 ±1일의 저장된 활동들**과 기존 매처로 대조한다(동일 허용오차,
방향만 반대). 결과별 처리:

* 1건 일치 → **새 행을 만들지 않고** 그 활동에 Intervals 정체성을 붙이고(`activity_source_link`)
  훈련부하·CTL/ATL를 보강 → `LINKED_EXISTING`
* 2건 이상 → `SKIPPED_AMBIGUOUS`, **자동 병합 없음**(사람이 판단)
* `source = GARMIN_CONNECT` → 6H-5 enrichment 경로의 몫. 해당 Garmin 활동이 있으면 연결, 없으면 `SKIPPED_GARMIN_SOURCED`
* 0건 → **그때만** 신규 활동 생성

### 2.4 단위 충돌 1건 — 변환하지 않았다 (중요)

Intervals의 러닝 케이던스는 **한쪽 다리 rpm**, Garmin `directRunCadence`(= `activity_sample.cadence`에
지금까지 들어있던 유일한 값)는 **spm**이다. 배수가 정확히 2라는 것은 실데이터로 검증했다
(`distance/duration = 2 × cadence/60 × average_stride`, 유효숫자 4자리 일치).

**검증했다는 것이 변환해도 된다는 뜻은 아니다.** `activity_sample`은 "원 소스 값 그대로"가 6H-1A 규칙이고,
다른 모든 행이 spm으로 읽는 컬럼에 rpm을 넣으면 틀린 값과 맞는 값을 구별할 수 없게 된다. 그래서:

* 케이던스 스트림은 `extra_metrics`에 소스 키 그대로 보존
* `activity_sample.cadence` / `activity_detail.average_running_cadence_spm`은 **NULL**
* 해당 25건 전부 `quality_flags = CADENCE_SOURCE_RPM_NOT_NORMALIZED`

→ **결과로서 명시**: Intervals 적재분 25건은 **케이던스 지표를 사용할 수 없다**. 단위 정규화 단계를 별도로
승인받기 전까지 그렇다. `stance_time`(ms) / `vertical_oscillation`(mm) / `step_length`(mm)도 같은 이유로
무변환 보존이며, 분석 엔진의 러닝 다이내믹스는 **랩**에서 계산하는데 이 활동들에는 랩이 없다.

### 2.5 하지 않은 것

* **랩 생성 0건.** 원본이 `icu_lap_count = 0`이므로 `activity_lap`에 아무것도 쓰지 않고, 그 사실을
  `SPLITS` 파트 = `EMPTY`로 기록했다.
* **Intervals HR 존 미적재.** `icu_hr_zone_times`는 **7존** 모델이고 저장된 `activity_zone`은 전부 Garmin의
  **5존** 모델이다. 섞으면 존 번호가 두 가지 의미를 갖게 되므로 적재하지 않았다(Raw에는 보존).
* **샘플 완전성을 FULL이라 주장하지 않음.** V14의 `FULL`은 "소스가 말한 네이티브 포인트 수를 전부 저장"이라는
  뜻인데, Intervals는 네이티브 포인트 수를 말하지 않고 `/streams`에 크기 파라미터도 없다. 대조할 소스 진술이
  없으므로 정직한 값은 `UNKNOWN`이다(저장 포인트 수는 `source_metrics_count`에 기록).
  → 이 때문에 6H-6 Backfill의 최종 VERIFY 스윕을 **`external_source = GARMIN`으로 한정**했다. 그 스윕은
  *Garmin* 수집이 달성해야 하는 기준을 말하는 것이고, 다른 소스가 만든 활동을 그 기준으로 심판하면
  Backfill이 고칠 수도 없는 데이터 때문에 멈춘다.

### 2.6 API (스케줄러·기동 트리거·MCP 도구 없음)

```
POST /api/v1/intervals/activity-import              {"oldest","newest","dryRun"}
GET  /api/v1/intervals/activity-import              기록된 모든 판정 (DB 전용)
POST /api/v1/intervals/activity-import/{id}/reprocess   저장된 Raw에서 재정규화, Intervals 호출 0회
```

기본 OFF(`RUNNING_AI_INTERVALS_ACTIVITY_IMPORT_ENABLED=false`). 게시 스위치 2종 / 드래프트 게시 /
Garmin 스케줄러 2종 중 하나라도 켜져 있으면 **시작 자체를 거부**한다
(`INTERVALS_ACTIVITY_IMPORT_UNSAFE_RUNTIME`). `running-ai.mcp.enabled`는 의도적으로 이 목록에서 제외했다 —
MCP 서버의 유일한 쓰기 도구는 `running-ai.workout-publishing.enabled`를 통과해야 하고 그건 목록에 있으며,
읽기 전용 MCP 도구는 적재 결과 검증에 계속 쓸 수 있어야 하기 때문이다.

---

## 3. STEP 3 — 분석 버전 관리 (정책으로 교체)

### 3.1 결함

`analysis_version`이 V22(러닝 다이내믹스 추가) 전후로 똑같이 `RUNNING_ANALYSIS_V1`이라,
시스템의 모든 "이 분석 최신인가?" 검사 — Backfill 재사용 규칙, VERIFY 단계, 코치 신선도 평가 —
가 **구버전 엔진이 만든 빈 다이내믹스 행을 최신이라고 답했다**. Backfill을 다시 돌려도 고칠 수 없었다
(7E-0 실측: 90일 재수집 Δ=0).

### 3.2 정책 (문자열 변경이 아님)

저장된 분석이 **서로 독립인 두 사실**을 갖고, 재사용 판단에 **세 번째**를 쓴다:

| 컬럼 | 의미 | 바뀌는 시점 |
|---|---|---|
| `analysis_version` | 소비자가 읽는 **출력 계약** | 필드/컬럼이 생기거나 사라지거나 의미가 바뀔 때. 여전히 `RUNNING_ANALYSIS_V1` |
| `analysis_engine_revision` | 숫자를 만든 **로직** | 계산기 변경·지표 추가·읽는 입력 변경 등 **값이 달라질 수 있는 모든 변경**. `0` = 리비전 추적 이전(V24가 부여, 코드가 만들지 않음), `1` = 6H-4 엔진, `2` = V22 다이내믹스 |
| `analysis_input_digest` | **무엇을 먹였는지**의 SHA-256 | 활동 정체 지표, 기록된 상세 파트의 상태/행수/샘플 완전성, 선수 LTHR이 바뀔 때 |

다이제스트는 샘플 행이 아니라 `activity_detail_collection`에 **기록된 사실**로 계산한다 — 그래서
"재계산이 필요한가?"를 묻는 비용이 수만 행 스캔이 아니라 작은 질의 하나다. 타임스탬프는 일부러 제외했다:
동일 데이터를 다시 수집한 것이 데이터 변경처럼 보이면 매 수집마다 전건 재계산이 강제된다.

`AnalysisReusePolicy`가 유일한 판단 지점이고, 문자열을 상수와 비교하던 **모든 호출부가 이것을 거치도록
교체**됐다(Backfill 재사용/VERIFY, 코치 신선도 평가). 다이제스트가 없는 행은 절대 재사용하지 않는다 —
"데이터가 바뀌었는지 모른다"를 "안 바뀌었다"로 취급하는 것이 바로 위의 결함이기 때문이다.

> **향후 규칙(기계적):** 이 패키지가 쓰는 값을 바꿀 수 있는 코드 변경을 하면 같은 커밋에서
> `RUNNING_ANALYSIS_ENGINE_REVISION`을 올린다. 마이그레이션도, 수동 대상 목록도, 호출부 수정도 필요 없다.

### 3.3 재분석 스윕

```
POST /api/v1/activities/analysis-refresh[?dryRun=true]
```

결함이 활동 단위로는 보이지 않기 때문에 필요하다 — 전수 질의만이 "어떤 행이 구버전 엔진 산물인지"를 답한다.
단일 재분석과 **같은 정책**을 쓰므로 dry run이 실제 실행과 어긋날 수 없다. 실패한 활동은 실패로 보고되고
**이전 분석을 그대로 유지**하며, 스윕은 다음 활동으로 계속한다.

### 3.4 재분석 실패 시 보존

`analyse()`는 모든 새 값이 만들어진 뒤 **한 트랜잭션**으로 교체하며, 준비 단계에서 아무것도 삭제하지 않는다.
계산기 실패를 주입한 테스트에서 저장된 분석의 `computed_at` / 샘플 수 / decoupling 값이 전부 불변임을 확인했다.

---

## 4. STEP 4 — 실데이터 사본 검증 (실행 완료)

### 4.1 환경

| 항목 | 값 |
|---|---|
| 인스턴스 | **127.0.0.1:18084** (loopback 전용), 새로 기동 |
| DB | `running_ai_realcopy` (V23 → V25) |
| Garmin 커넥터 | `http://127.0.0.1:9` — **Garmin 트래픽 0건을 구조적으로 보장** |
| Intervals | 실제 API Key, **GET 전용** |
| 스위치 | 게시/스케줄러/드래프트 게시/MCP 쓰기 **전부 false**, MCP 읽기 true, 컨텍스트 V2 |
| 로그 | `.runtime/logs/7e1-verify-18084.{out,err}.log` |

운영 18080·Docker Compose·Relay·Funnel·Gateway 설정은 **일절 건드리지 않았다.**

### 4.2 마이그레이션

`V24__add_analysis_engine_revision.sql`, `V25__create_intervals_activity_import.sql` 적용 성공.
V24가 기존 8행을 `analysis_engine_revision = 0`, `analysis_input_digest = NULL`로 표시 → 전부 재분석 대상.

### 4.3 기존 Garmin 8건 재분석

| 단계 | 결과 |
|---|---|
| dry run | `examined 8 / reused 0 / pending 8`, 전건 사유 `ENGINE_REVISION_CHANGED`. **DB 변경 0** |
| 실행 | `reanalysed 8 / failed 0` |
| 재실행 | `reused 8 / reanalysed 0` |

**결정성**: 재분석 전후 8건의 `speed_hr_decoupling_percent` / 보폭 / 접지시간 / 러닝파워가
**소수점까지 전부 동일**(예: 활동 7 = 6.239219 유지). `activity` / `activity_sample`(21,828) /
`activity_lap`(123) / `activity_zone`(75) 행 수 **불변** — 원본 데이터 보존 확인.

> 지시문의 "구버전 7건"은 사본에서 **8건 전부**가 대상으로 잡혔다. 7E-0이 사본에서 이미 7건을 강제
> 재분석해 뒀기 때문에 사본에는 더 이상 "다이내믹스 결손 7건"이 남아 있지 않고, V24는 *언제 계산됐는지
> 기록이 없는* 행을 전부 리비전 0으로 표시하기 때문이다. 어느 엔진이 만들었는지 기록이 없는 행을
> NULL 컬럼 모양으로 추정하는 것은 이 패키지가 금지하는 추론이라 일부러 하지 않았다.
> **운영 DB에서는 지시문대로 동작한다**: 운영은 V20이라 다이내믹스 컬럼 자체가 없고,
> V21~V25 적용 시 8건 전부가 리비전 0이 되어 재분석 대상이 되며, 그중 7건이 다이내믹스를 새로 얻는다
> (활동 2 = INDOOR_CYCLING은 설계상 다이내믹스 대상이 아님).

### 4.4 Intervals 전용 적재

dry run(쓰기 0, 스트림 호출 0) → 실행 → 재실행 순으로 진행했고, 세 번 모두 판정이 동일했다.

| 판정 | 건수 | 의미 |
|---|---:|---|
| `IMPORTED` | **25** | 신규 활동 생성 |
| `SKIPPED_UNSUPPORTED_TYPE` | 2 | `Ride`, `Workout` — 대응 `ActivityType` 없음, 근사하지 않음 |
| `SKIPPED_ALREADY_LINKED` | 8 | Garmin 활동에 이미 Intervals 정체성이 연결돼 있음 |
| `SKIPPED_GARMIN_SOURCED` | 2 | Garmin 상류이지만 해당 Garmin 활동이 미적재(`breathwork` 2건) |
| `SKIPPED_NO_SOURCE_DATA` | 34 | STRAVA 스텁 — API가 데이터를 주지 않음(실패가 아님) |
| `FAILED` | **0** | — |
| 합계 | 71 | 후보 전수 |

소요 2분 29초, Intervals 호출 = 목록 1 + 스트림 25 = **26회**(활동당 1회, 재시도 0).

행 변화:

| 테이블 | 전 | 후 | Δ |
|---|---:|---:|---:|
| `activity` | 8 | **33** | +25 |
| `activity_sample` | 21,828 | **68,708** | **+46,880** |
| `activity_detail` | 8 | 33 | +25 |
| `activity_analysis` | 8 | 33 | +25 |
| `activity_intervals_metrics` | 8 | 33 | +25 |
| `activity_detail_collection` | 48 | 123 | +75 (활동당 3파트) |
| `activity_raw_payload` | 40 | 65 | +25 (스트림 원문) |
| `intervals_activity_import` | — | 71 | +71 |
| **`activity_lap`** | 123 | **123** | **0 — 랩 생성 없음** |
| **`activity_zone`** | 75 | **75** | **0 — Intervals 존 미적재** |
| **`activity_source_link`** | 8 | **8** | **0 — 자기 참조 링크 없음** |
| `intervals_raw_payload` | 167 | 167 | 0 — 해당 요약 Raw는 6H-5 시기(2026-10-03)에 이미 있었고 **제자리 교체**됨(`created_at` 10-03 / `updated_at` 10-13로 확인) |

`activity` 33행의 `count(distinct external_source||external_id) = 33` — 중복 정체성 0.

### 4.5 멱등성

동일 요청 재실행 → `imported 0 / alreadyImported 25`, **12개 테이블 전부 Δ = 0**, 스트림 호출 **0회**
(목록 1회만). 소요 2.9초.

### 4.6 중단 후 재개 (실제 프로세스 강제 종료)

적재된 25건 중 **가장 오래된 6건을 삭제**해 "중간까지 끝난 상태"를 만든 뒤 import를 시작하고,
**12초 뒤 JVM을 강제 종료**했다(`Stop-Process -Force`).

* 종료 시점: 활동 24/33, 적재 레코드 69, 샘플 65,056 — 요청은 HTTP 000(연결 끊김)
* JVM 재기동 후 **같은 POST를 그대로 다시** 호출(별도 resume 엔드포인트 없음): 17.9초
  → `imported 1 / refreshed 1 / alreadyImported 23`
* 최종: `activity 33` / `activity_sample 68,708` / `activity_analysis 33` /
  `intervals_activity_import 71` — **전량 적재 상태와 완전히 동일**, 중복 정체성 0

중단 시 진행 중이던 1건은 활동 행만 있고 샘플이 없는 상태였는데, 재개가 이를 `REFRESHED`로 복구했다.

### 4.7 1 Hz 스트림 및 랩 커버리지

| 보유 스트림 종류 | 활동 수 | 샘플 |
|---:|---:|---:|
| 13종(전부) | 17 | 37,078 |
| 12종 | 1 | 47 |
| 10종 | 1 | 309 |
| 5종 | 6 | 9,446 |
| 합계 | **25** | **46,880** |

* 샘플 수 범위 47 ~ 6,651, 중앙값 수준 2,000대. `icu_median_time_delta = 1` → 1 Hz.
* **랩 커버리지 0/25** — 원본에 랩이 없어서이며, 생성하지 않았다. 따라서 이 25건에 대해
  **인터벌 구조 분석은 구조적으로 영구 불가**(6H-4는 랩에서 구조를 *읽지* 추정하지 않는다).
* 결측 스트림은 활동별로 기록됨(예: 6건은 `watts,altitude,latlng,torque,fixed_altitude,stance_time,
  vertical_oscillation,step_length` 결측).

### 4.8 분석 결과 생성

| 출처 | 활동 | 샘플 | decoupling | LTHR 노출 | 러닝 다이내믹스 | 랩 | HR 존 | Intervals 부하 |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| GARMIN | 8 | 21,828 | 7 | 8 | 7 | 123 | 8 | 8 |
| INTERVALS_ICU | 25 | 46,880 | **25** | **25** | 0 | 0 | 0 | **25** |

25건 전부 `analysis_status = COMPLETE`, 엔진 리비전 2. 실제로 얻은 것: 세션 지표, 전후반 분할,
속도-심박 decoupling, LTHR 노출 초. 얻지 못한 것: HR 존 노출(존 미적재), 랩 지표, 러닝 다이내믹스,
인터벌 그룹 — 전부 §2.4/§2.5의 결정에 따른 **예상된 결과**이며 숨기지 않고 기록돼 있다.

### 4.9 TrainingContextV2 반영

```
historyWindowDays 90 · supportedActivityCount 33 · analysedActivityCount 33
fullSampleActivityCount 8 · intervalsMatchedActivityCount 33
oldestActivityDate 2026-08-02  (적재 전: 2026-09-18)
newestActivityDate 2026-10-11 · fitnessDaysAvailable 90 · recoveryDaysAvailable 27
snapshot 14,233 bytes (상한 64 KiB 이내)
```

**코치가 볼 수 있는 이력이 47일 더 과거로 확장됐다.** `fullSampleActivityCount`가 8로 남는 것은
§2.5대로 Intervals 스트림의 완전성을 FULL이라 주장하지 않기 때문이며, 데이터 품질이 정직하게 드러난다.

### 4.10 운영 DB 접근

읽기 전용 `SELECT`만 사용했다. 종료 시점 운영 `running_ai`: **flyway V20 / activity 8 / analysis 8 /
sample 21,828** — 세션 시작 시점과 동일.

---

## 5. STEP 5 — Strava / Apple Watch 복구 준비

준비 문서: [`docs/architecture/strava-archive-recovery.md`](../architecture/strava-archive-recovery.md)
**구현 0건.** 아카이브 실물을 보기 전에는 파일 구조를 추측하지 않는다는 지시를 지켰다.

핵심 실측 결과:

* **Intervals API로는 영구 불가 — 추정이 아니라 측정됨.**
  `GET /activity/{id}` → 스텁, `/streams` → `422 "Cannot read Strava activities via the API"`,
  `/file` → `422 "Activity has no original file to download"`.
* **매칭은 휴리스틱이 아니라 ID 정확 일치가 된다.** `STRAVA` 출처 활동은 Intervals가
  **Strava 활동 ID 자체를 자기 활동 ID로** 쓴다(34건 전부 `i` 접두사 없는 순수 숫자).
  Strava 내보내기 `activities.csv`의 `Activity ID`와 **그대로 조인**된다. 그 34행은 이미
  `intervals_activity_import`에 `SKIPPED_NO_SOURCE_DATA`로 저장돼 있어, 아카이브를 **알려진 대상 집합과
  대조**할 수 있다.
* **27건 ↔ 34건 중복 분석(실측).** 34개 Strava ID와 27건의 `strava_id`(26개 보유) 및 Garmin 10건의
  `strava_id`는 **교집합 0** — 서로 다른 업로드다. 다만 **정확히 1쌍이 동일 세션으로 거의 확실**하다:
  Strava `19929243277`과 Intervals `i180579462`가 **같은 초**(`2026-08-06T18:21:47`)에 시작하고,
  후자는 Apple Watch(`Watch7,9`) `Workout`이며 **`strava_id`가 없다**. 즉 같은 운동이 두 시스템에
  따로 올라갔다. 이 활동은 현재 `SKIPPED_UNSUPPORTED_TYPE`이라 **RunningAI에 행이 없어서**,
  아카이브 적재 시 매처가 대조할 대상이 없다 → **별도 처리 필요**(문서에 명시).
  나머지 같은 날 2쌍은 3시간 39분 / 10시간 38분 차이로 다른 세션이다.
* **FIT/TCX/GPX 지원 현황: 0.** `server/`에 파서도 의존성도 없다(6H-1B의 "A — API 상세로 충분,
  FIT는 보관용, 미구현" 결정과 일치).
* **CSV만으로도 34건 활동 행 복구가 가능**하다(종목·시간·거리·평균/최대 심박). 파서 없이 훈련부하와
  일별 패턴까지 복원된다 — 아카이브 도착 시 1순위 작업.
* **Apple Health `export.xml`: 최후 수단으로 가능.** Apple Watch가 이미 확인된 기록 장치지만,
  러닝 다이내믹스가 없고 Strava/Intervals ID가 없어 복합 매칭만 가능하다. Strava 아카이브가 못 덮는
  세션에 한해 검토할 가치가 있으며, 병행 트랙이 아니다.
* **스크래핑·정책 우회 없음.** 아카이브는 계정 소유자가 Strava 공식 내보내기로 받는다.

참고(27건 충실도): Intervals는 **원본 업로드 파일을 제공한다**
(`/activity/i180579414/file` → 402 KB, `/fit-file` → 260 KB). 더 높은 충실도 경로가 존재하지만
6H-1B 결정에 따라 **사용하지 않았다** — `/streams`로 이미 1 Hz를 얻으므로 FIT 파서를 들일 이유가 없다.

---

## 6. STEP 6 — ChatGPT / MCP 연계 (개발 환경 검증만)

18084에서 MCP 읽기 도구 **7종 전부** 호출했다. 쓰기 도구는 등록되지 않았다(`RUNNINGAI_MCP_ENABLED=false`).

| 도구 | 결과 |
|---|---|
| `get_recent_activities` | 90일/20건 요청 → 적재된 Intervals 활동이 정상 포함(2026-08-23 ~ 09-13 구간 확인), `intervalsMatched=true` |
| `get_activity_analysis` | 2026-09-13(Intervals 적재분) → `analysisEngineRevision: 2`, `inputSampleCompleteness: UNKNOWN`, decoupling 4.752 %, LTHR 노출 2816/2803/2779초, `lapCount 0`, `intervalGroups []` — **실제 상태 그대로** |
| `get_training_context` | V2, 33활동/33분석, `oldestActivityDate 2026-08-02` |
| `get_recovery_context` | 정상 (HRV/수면/안정시심박 + 28일 기준선) |
| `get_fitness_trend` | 2026-08-01~10-13, 74일 CTL/ATL/derivedForm |
| `get_workout_recommendation` | 부상 안전 게이트가 정상 동작(`INJURY_ACTIVE`, KNEE_LATERAL → REST만 허용) |
| `get_athlete_profile` | LTHR 176 / 임계 페이스 290 s/km + provenance |

또한 `GET /api/v1/activities/{id}/intervals`가 적재 활동에 대해서도 동작하도록 보강했다
(그 행은 Intervals ID를 **자기 정체성**으로 갖기 때문에 `activity_source_link`가 없다 — 매칭이 일어난
적 없으므로 자기 참조 링크를 만들지 않고, 활동 행에서 정체성을 읽는다).

**ChatGPT OAuth Worker / Gateway / 합성 데이터 연결은 변경하지 않았다. MCP 쓰기 기능 신규 공개 없음.**

---

## 7. STEP 7 — 최종 보고 (지시 항목順)

| # | 항목 | 결과 |
|---|---|---|
| 1 | 실제 신규 적재 활동 수 | **25** (사본 DB, 샘플 46,880) |
| 2 | 중복으로 연결된 활동 수 | **8** 이미 연결됨(`SKIPPED_ALREADY_LINKED`) + 신규 연결 0. 중복 생성 **0** |
| 3 | 적재 실패·지원 불가 | 실패 **0** / 미지원 종목 **2**(`Ride`, `Workout`) / 소스 데이터 없음 **34**(STRAVA) / Garmin 상류 미적재 **2** |
| 4 | 1 Hz 스트림 및 랩 커버리지 | 스트림 25/25(13종 보유 17건, 5~12종 8건), 샘플 46,880, 완전성 전건 `UNKNOWN`(§2.5). **랩 0/25 — 원본에 없음** |
| 5 | Garmin 기존 8건 재분석 | 8/8 재분석, 실패 0, **파생값 전건 비트 동일**(결정성 확인), 원본 샘플·랩·존 행 수 불변 |
| 6 | 분석 버전 관리 수정 | 계약 + 엔진 리비전 + 입력 다이제스트 3요소 정책 + `AnalysisReusePolicy` 단일 판단 지점 + 전수 스윕 API(§3) |
| 7 | 테스트 통과 수 | **1,331 / 1,331 통과**(실패 0, 스킵 0). 신규 테스트 5개 클래스 추가 |
| 8 | Strava 34건 복구 준비 | ID 정확 매칭 경로 확정, 중복 1쌍 식별, CSV 단독 복구 가능성 확인, 파서 현황 0 — **아카이브 대기**(§5) |
| 9 | 운영 DB 마이그레이션 추가분 | **V24, V25 2개**(§8) |
| 10 | 운영 반영 영향·중단 조건 | §9 |

---

## 8. 운영 DB에 필요한 추가 Flyway

운영은 현재 **V20**이다. 기존 승인(항목 A)의 V21~V23에 더해 이번 단계가 2개를 추가한다.

| 버전 | 내용 | 성격 |
|---|---|---|
| `V24__add_analysis_engine_revision.sql` | `activity_analysis`에 `analysis_engine_revision INTEGER NOT NULL`(기존 행 0) + `analysis_input_digest VARCHAR(64)` + CHECK | **추가 전용**. 기존 파생값 변경 없음 |
| `V25__create_intervals_activity_import.sql` | `intervals_activity_import` 테이블 + 인덱스 신설 | **신규 테이블 전용**. 기존 테이블 무변경 |

둘 다 기존 컬럼을 바꾸거나 지우지 않고, 어떤 파생값도 수정하지 않는다. 수동 SQL은 필요 없다.

**롤백 성격**: V24 적용 후 구 JAR로 되돌려도 Flyway는 미래 마이그레이션을 허용하고 Hibernate `validate`는
추가 컬럼을 문제 삼지 않으므로 기동한다(7E-0에서 V23에 대해 입증된 경로와 동일). `flyway_schema_history`
행은 삭제 금지.

---

## 9. 운영 반영 시 예상 영향과 중단 조건

### 9.1 영향

* **스키마**: 추가 전용, 데이터 손실 경로 없음.
* **분석**: V24 적용 직후 기존 8건이 전부 "구버전"으로 표시된다. 스윕을 돌리기 전까지 Backfill과 코치
  신선도 평가가 이 8건을 **재분석 필요**로 본다. 스윕 1회(DB 전용, 외부 호출 0, 사본 실측 수 초)로 해소된다.
* **적재**: Importer는 기본 OFF다. 켜지 않으면 운영 동작은 전혀 바뀌지 않는다.
  켜고 실행하면 활동 8 → 33, 샘플 21,828 → 68,708이 되고 TrainingContextV2의 이력 시작일이
  2026-09-18 → 2026-08-02로 당겨진다.
* **코치 입력 변화**: 스냅숏 크기 14 KiB 수준(상한 64 KiB), `recentActivities`는 기존 상한 8건 유지.
* **ChatGPT**: 읽기 도구 응답에 `analysisEngineRevision` 필드가 추가된다(가산적). 기존 필드 변경 없음.

### 9.2 중단 조건 (하나라도 걸리면 즉시 중지)

1. V24/V25 적용 실패 또는 `flyway info`에 pending 잔존
2. 재분석 스윕에서 `failed > 0`
3. 재분석 전후 기존 활동의 `activity` / `activity_sample` / `activity_lap` / `activity_zone` 행 수가 변함
4. Importer 재실행 시 신규 행이 1건이라도 생김(멱등성 위반)
5. `SKIPPED_AMBIGUOUS`가 발생하는데 자동으로 병합된 흔적이 있음
6. `activity`의 `count(*) ≠ count(distinct external_source||external_id)`
7. Intervals 호출이 활동당 1회를 초과하거나 429 이후에도 호출이 계속됨
8. 운영 Spring 기동 실패 또는 `/api/v1/health` 비정상
9. `activity_lap` 또는 `activity_zone` 행 수가 Importer 실행으로 증가(랩/존 생성 금지 위반)

### 9.3 권고 순서

1. 기존 승인된 **항목 A**(Postgres 포트 loopback 한정 + V21~V23) 실행 시 **V24까지 함께** 적용.
   V25는 테이블 신설뿐이라 같이 가도 되고, Importer를 켜지 않는 한 비활성이다.
2. **B-1** = 기존 Backfill 90일 + **재분석 스윕**(`POST /api/v1/activities/analysis-refresh`).
   7E-0의 "강제 전건 재분석"이 이제 정책 기반 스윕으로 대체된다.
3. **B-2** = Importer 활성화 후 1회 실행. 사본에서 검증 완료. 별도 승인 권장
   (운영에 `external_source = INTERVALS_ICU` 활동 행이 처음 생기는 변경이기 때문).

---

## 10. 변경 사항 요약

### 10.1 신규 파일

```
server/src/main/resources/db/migration/V24__add_analysis_engine_revision.sql
server/src/main/resources/db/migration/V25__create_intervals_activity_import.sql
server/src/main/kotlin/com/runningai/analysis/AnalysisVersioning.kt
server/src/main/kotlin/com/runningai/enrichment/IntervalsActivityImportEntities.kt
server/src/main/kotlin/com/runningai/enrichment/IntervalsActivityImportStore.kt
server/src/main/kotlin/com/runningai/integration/intervals/IntervalsStreamMapper.kt
server/src/main/kotlin/com/runningai/integration/intervals/IntervalsImportMappers.kt
server/src/main/kotlin/com/runningai/integration/intervals/IntervalsActivityImportService.kt
server/src/main/kotlin/com/runningai/integration/intervals/IntervalsActivityImportController.kt
server/src/test/kotlin/com/runningai/analysis/AnalysisVersioningTest.kt
server/src/test/kotlin/com/runningai/analysis/AnalysisReuseApiTest.kt
server/src/test/kotlin/com/runningai/integration/intervals/IntervalsStreamMapperTest.kt
server/src/test/kotlin/com/runningai/integration/intervals/IntervalsImportCandidateMapperTest.kt
server/src/test/kotlin/com/runningai/integration/intervals/IntervalsActivityImportServiceTest.kt
docs/architecture/intervals-activity-import.md
docs/architecture/strava-archive-recovery.md
docs/work-orders/2026-10-13-phase-7e-1-instruction.md
docs/work-orders/2026-10-13-phase-7e-1-result.md
```

### 10.2 수정 파일

| 파일 | 변경 |
|---|---|
| `IntervalsReadClient` / `HttpIntervalsReadClient` | `getActivityStreams` 추가(**GET 전용 구조 유지**) |
| `AnalysisModels` / `ActivityAnalysisEntities` | 리비전·다이제스트 필드 |
| `RunningActivityAnalysisService` | 다이제스트 계산, `analyseIfNeeded`, `reuseDecision` |
| `ActivityAnalysisController` | 응답에 `analysisEngineRevision`, 재분석 스윕 컨트롤러 |
| `HistoricalBackfillService` | 재사용·VERIFY를 정책으로 교체, VERIFY 스윕을 GARMIN 한정 |
| `CoachDataFreshnessEvaluator` | 문자열 비교 → 정책 |
| `IntervalsEnrichmentService` | Intervals 적재 활동의 `view()` 보강 |
| `ReadMcpTools` | 분석 응답에 `analysisEngineRevision` |
| `application.yml` | `running-ai.intervals.activity-import.*` |
| `running-analysis-engine.md` | 버전 정책 절 추가 |
| 기존 테스트 7개 | 새 생성자 인자 / 새 인터페이스 메서드 / V24·V25 반영 |

### 10.3 환경 영향

* 운영 `running_ai` **변경 0**, 운영 Spring 18080 **무접촉**, Docker Compose·Relay·Funnel·Gateway **무변경**.
* 외부 쓰기 **0건** — Intervals는 GET만, Garmin은 `127.0.0.1:9`로 차단.
* 사본 `running_ai_realcopy`는 검증으로 변경됨(§4.4). V25로 전진.
* 검증 인스턴스 **18084는 종료했다**(최종 전체 테스트 전에 중지). 로그는 보존:
  `.runtime/logs/7e1-verify-18084.*.log`. 종료 시점에 LISTEN 중인 것은 운영 18080, Gateway 17910,
  Garmin 커넥터 8765뿐이다.
* 로그·문서·커밋 후보에 자격증명/토큰/API Key/실제 GPS 좌표 **미기록**. 테스트 픽스처는 합성.
* 커밋/푸시 **미실행**, `main` 병합 **미실행**.

### 10.4 세션 중 관찰된 환경 변화 (본 작업과 무관)

세션 시작 시점에 `127.0.0.1:18081`(합성 스택, PID 13980)과 `127.0.0.1:18082`(사본 읽기 전용 스택,
PID 9116)가 LISTEN 상태였으나, 세션 종료 시점에는 **두 프로세스 모두 존재하지 않는다**.
본 세션이 종료시킨 프로세스는 자체 기동한 18084 인스턴스(PID 14444 / 42364)뿐이며, 두 PID에 대한
어떤 조작도 하지 않았다. 원인은 확인하지 못했다(두 인스턴스는 PID 파일도 로그도 남기지 않은 임시 기동).

**영향**: MCP Gateway(17910)는 정상이지만 그 upstream이 18081/18082 중 하나라면 ChatGPT 읽기 경로가
현재 끊겨 있을 수 있다. 운영 Spring 18080, Garmin 커넥터 8765, Gateway 17910은 모두 정상이다.
복구가 필요하면 지시 바란다 — 임의로 재기동하지 않았다.

---

## 11. 한계와 다음 단계

1. **케이던스 단위 정규화 미결.** Intervals 적재 25건은 케이던스 지표를 쓸 수 없다. rpm→spm 변환을
   어디서(매퍼? 분석 엔진? 조회 시점?) 할지는 설계 결정이라 임의로 하지 않았다.
2. **러닝 다이내믹스 미활용.** `stance_time` / `vertical_oscillation` / `step_length`가 샘플에 저장돼
   있지만, 6H-4 엔진은 이를 **랩**에서 계산한다. 샘플 기반 다이내믹스를 추가하면 Garmin 활동의 결과도
   바뀌므로(= 엔진 리비전 상승) 이번 범위에서 제외했다. 이제는 리비전만 올리면 전건 재분석이 자동으로
   된다는 점이 §3 정책의 첫 수혜 사례가 될 것이다.
3. **Intervals HR 존 미적재.** 7존/5존 혼재 문제를 해결하려면 `activity_zone`에 존 모델을 식별하는
   수단이 필요하다 — 스키마 결정 사항.
4. **미지원 종목 2건**(`Ride`, `Workout`)과 Garmin `breathwork` 2건은 `ActivityType` 확장 전까지 적재 불가.
5. **Strava 34건**은 아카이브 도착 대기(§5).
6. **운영 반영은 미실행** — 지시대로 STOP. 기존 A + B-1 승인 기록은 유지되며, 이 문서만으로 운영을
   변경하지 않았다.
