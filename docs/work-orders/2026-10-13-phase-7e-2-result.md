# Phase 7E-2 — ChatGPT 연결 복구 + Strava 아카이브 Importer 결과

작성일 2026-10-13 · 브랜치 `main-3` @ `34cf121`(미커밋 작업 트리)
지시문 원문: [2026-10-13-phase-7e-2-instruction.md](2026-10-13-phase-7e-2-instruction.md)
선행: [7E-1 결과](2026-10-13-phase-7e-1-result.md) · 설계: [`strava-archive-import.md`](../architecture/strava-archive-import.md)

**운영 DB `running_ai` 변경 0건 · 운영 Spring 18080 무접촉 · 외부 쓰기 0건 · 커밋/푸시 미실행.**
STEP 1~8 전부 수행하고 STOP한다.

---

## 0. 한 줄 결론

끊어졌던 ChatGPT 경로의 원인은 **합성 Spring 18081의 프로세스 소멸** 하나였고(게이트웨이·터널·Worker 전부 정상),
세션과 함께 죽지 않도록 재구성해 복구했다. Strava 아카이브 94건은 **추정이 아니라 정확한 식별자**로 기존 데이터와
결합돼, 신규 15건 적재 + 기존 33건 보강(랩 116개·샘플 18,541개)을 **중복 0건·실패 0건**으로 끝냈다.
전체 테스트 **1,385건 통과**.

---

## 1. STEP 1 — 7E-1 산출물 보존 및 정합성

| 항목 | 결과 |
|---|---|
| 미커밋 파일 | 7E-1 산출물 전부 보존, 삭제·되돌림 0건 |
| V24 / V25 | 정합성 확인. **V23 상태의 `running_ai_7d_smoke`에 신규 적용 성공** → V25 도달 (독립 검증) |
| 테스트 | **1,331건 PASS** 재현 확인 (본 작업 전 기준선) |
| 충돌 | 없음 |
| 운영 RC 범위 | V21~V25 → 본 작업으로 **V21~V26** 으로 확대 (§8) |

### Intervals 27건 중 25건만 적재된 이유 — 실제 외부 ID와 근거

오류가 아니라 **설계대로의 제외 2건**이다. 둘 다 `NO_ACTIVITY_TYPE_FOR_*`:

| Intervals ID | Strava ID | 시각(KST) | Intervals type | FIT 실측 sport/sub_sport | 판정 |
|---|---|---|---|---|---|
| `i180579412` | `19929242917` | 2026-08-15 13:29 | `Ride` | `cycling` / `indoor_cycling` | **미지원 종목**. `ActivityType`에 실외 Ride 없음 |
| `i180579462` | *(없음)* | 2026-08-06 18:21:47 | `Workout` | `fitness_equipment` / `stair_climbing` | **미지원 종목 + 중복**. 아래 참조 |

* `i180579412`는 Intervals가 `Ride`라고만 말해 7E-1이 보류했지만, **FIT 원본이 스스로 `indoor_cycling`이라고
  기록**하고 있었다. 본 Phase에서 Strava 아카이브 경로로 `INDOOR_CYCLING`으로 적재했다.
* `i180579462`는 **Intervals가 같은 운동을 두 번 들고 있는 것**이다. `apple_health_uuid`
  `C73CBC61-283D-48EB-8F83-9DDB462C5540`가 Strava `19929243277`(Stair-Stepper)과 **문자 단위로 동일**하다.
  7E-1이 "거의 확실하다"고만 적었던 추정이 식별자로 증명됐다. Intervals가 이 건의 `strava_id`를 기록하지
  않았기 때문에 7E-0의 ID 교집합 검사가 0을 보고했던 것이다. 종목이 계단오르기라 **적재 대상이 아니다**.
  `elapsed 57,536초` vs `timer 9,142초` 차이도 FIT이 설명한다 — 워치를 16시간 켜 둔 기록이다.

---

## 2. STEP 2 — ChatGPT OAuth Smoke 복구

### 2.1 원인

| 구성요소 | 상태 | 판정 |
|---|---|---|
| Gateway 17910 (PID 1760) | **UP**, `/health` 200, OAuth env 정상 | 정상 |
| `RUNNING_AI_GATEWAY_TARGET_URL` | `http://127.0.0.1:18081/mcp` | 정상 (문서·메모리 일치) |
| Cloudflare Quick Tunnel (PID 8400) | **UP**, 터널 → 게이트웨이 메타데이터 200 | 정상 |
| Worker OAuth | 메타데이터 정상, 무토큰 `401` + 올바른 `resource_metadata` | 정상 |
| **합성 Spring 18081** | **DOWN** | ← **단일 원인** |
| 사본 Spring 18082 | DOWN | (외부 경로와 무관) |

게이트웨이는 살아 있고 업스트림만 죽어 있었으므로 ChatGPT에는 내부 오류로 보였다.

### 2.2 프로세스 종료 원인 — Stop Hook은 범인이 아니다

`.claude/settings.json`의 Stop 훅은 `scripts/dev/validate-server.ps1`이고, 이 스크립트는 **`gradlew test`만
실행하며 어떤 프로세스도 종료하지 않는다**(전문 확인).

실제 원인은 **세션 수명**이다. 죽은 세 인스턴스(18081/PID 13980, 18082/PID 9116, 18084/PID 14000)는 모두 이전
Claude 세션의 백그라운드 자식 프로세스였고 세션 종료와 함께 회수됐다. Windows 런처가 소유한 운영 18080(PID
29984)과, 별도로 기동된 게이트웨이·터널은 **셋 다 살아남았다** — 이 대조가 결론의 근거다.

### 2.3 복구

세션에 묶이지 않도록 **Windows 작업 스케줄러가 소유**하는 기동 경로를 만들었다:
`scripts/dev/start-dev-spring.ps1`.

* 운영 DB `running_ai` 지정 거부, 운영 포트 18080 거부, 개발 포트 18081~18099로 제한
* `127.0.0.1` 전용 바인드, 쓰기 스위치 전부 off, Garmin 커넥터는 죽은 포트
* `DB_PASSWORD`는 기동 시점에 canonical `.env`에서 읽고 **디스크에 쓰지 않음**
* 빌드 산출물을 **복사해서** 실행 — 실행 중 JVM이 jar를 잠가 `gradlew clean`이 실패하던 문제 제거

**검증 결과**

| 항목 | 결과 |
|---|---|
| 18081 기동 | UP, PID의 부모 = 작업 스케줄러의 `cmd.exe` (세션 무관) |
| 바인드 | `127.0.0.1` 전용 |
| 합성 DB | `running_ai_7d_smoke`, V23 → **V26 적용 성공** |
| MCP 도구 | `tools/list` 7종 정상 |
| **합성 6건 조회** | **성공** (2026-09-20 ~ 10-12), LTHR **165/310** = 합성 마커 |
| 운영 18080 | **무접촉** |

**남은 1단계**: ChatGPT 앱에서의 최종 확인은 사용자 계정의 대화형 OAuth가 필요해 제가 수행할 수 없습니다.
체인의 모든 구간(Worker → 터널 → 게이트웨이 → 18081 → 합성 DB)을 개별 검증했으므로, ChatGPT에서
`get_recent_activities`를 한 번 호출해 6건이 보이는지 확인해 주세요.

> 부수 발견(차단 아님): 게이트웨이의 `WWW-Authenticate`는 비표준 형태인
> `/.well-known/oauth-protected-resource`(404)를 광고합니다. ChatGPT가 보는 것은 Worker의 헤더이고 그쪽은
> RFC 9728 형태 `…/oauth-protected-resource/mcp`(200)로 정확하므로 현재 경로에는 영향이 없습니다.

---

## 3. STEP 3 — Strava ZIP 확보 및 검증

| 항목 | 결과 |
|---|---|
| 확보 경로 | Google Drive 마운트 `G:\내 드라이브\export_1577472398.zip` |
| 보관 위치 | `C:\Users\simjy\running-ai-archives\strava\` — **Git 저장소 밖** |
| 크기 | 1,322,349 바이트 |
| SHA-256 | `aaf50ee8814b040284ba1e7dcdf0b9adbf54c25d9b0398d4ea5b31bf56b28533` — **지시서 값과 완전 일치** |
| 내용 | `activities.csv` 94행, `activities/*.fit.gz` 94개, 기타 CSV 37개 |
| 추출 | `activities.csv` + `activities/`만 전개. 개인정보 CSV(로그인·연락처 등)는 전개하지 않음 |

GPS 좌표·개인정보·계정정보는 로그·Git·문서 어디에도 기록하지 않았다.

---

## 4. STEP 4 — FIT 데이터 품질 조사

**94개 전부 디코드 성공, 파싱 오류 0건.** 총 record 메시지 **76,791개**.

### 4.1 두 가지 출처 계열

| 계열 | 수 | 특징 |
|---|---|---|
| Strava 재인코딩 | 84 | `manufacturer=strava`, 개발자 필드 `apple_health_uuid`, 2026-08-27에 일괄 업로드 |
| Garmin 원본 | 10 | `manufacturer=garmin`, 랩 `intensity`+`wkt_step_index`, 러닝 다이내믹스 완비 |

### 4.2 종목별 메시지·센서 실측

| Strava type | FIT sport / sub_sport | 수 | HR | cadence | power | GPS | 러닝 다이내믹스 |
|---|---|---|---|---|---|---|---|
| Run | running / (여러 sub) | 36 | 21건만 | Garmin 7건만 | 26건 | 24건 | Garmin 7건만 |
| Ride | cycling / indoor_cycling | 12 | 1건 | 0 | 0 | 0 | 0 |
| Weight Training | training / strength_training | 38 | 0 | 0 | 0 | 0 | 0 |
| Walk | walking | 3 | 0 | 0 | 0 | 0 | 0 |
| Stair-Stepper | fitness_equipment / stair_climbing | 3 | 0 | 0 | 0 | 0 | 0 |
| Workout | training / 62 | 2 | 2건 | 0 | 0 | 0 | 0 |

작은 파일도 크기로 제외하지 않고 전부 메시지를 검사했다(최소 2 record). 센서가 없는 활동에 **가짜 샘플이나
가짜 랩을 만들지 않았다**.

### 4.3 결정적 품질 사실

* **2026-08 러닝 15건의 FIT에는 심박이 아예 없다.** 그러나 같은 운동의 Intervals 사본에는 **샘플 100 %에
  심박이 있다.** 두 export가 서로 다른 것을 잃어버린, 같은 Apple Health 운동의 **독립적인 두 사본**이다.
* 반대로 FIT 쪽이 **러닝파워를 훨씬 많이** 갖고 있다(2026-08-18 장거리: Intervals 2,373 → FIT 6,549 포인트).
* **Intervals가 `icu_lap_count=0`이라 보고한 25건 전부에 FIT에는 실제 랩이 있다.** 그중 5건은 진짜 반복 구조다.
* Strava 재인코딩 랩에는 **`intensity`도 `wkt_step_index`도 없다**(`lap_trigger=manual`만 존재).

---

## 5. STEP 5 — 출처 간 중복 매칭

### 5.1 조인 키: `apple_health_uuid`

Strava는 Apple Health 운동을 FIT으로 재인코딩하면서 session 메시지에 개발자 필드 `apple_health_uuid`를
기록한다. **이 값이 Intervals.icu의 `external_id`와 문자 단위로 동일하다.**

| 조인 | 결과 |
|---|---|
| Intervals `OAUTH_CLIENT` 27 ↔ 아카이브 (`apple_health_uuid`) | **27/27**, 모순 0 |
| Intervals `STRAVA` 스텁 34 ↔ 아카이브 (Strava Activity ID) | **34/34** |
| Intervals `GARMIN_CONNECT` 10 ↔ 아카이브 (Strava ID) | **10/10** |

**휴리스틱(날짜·거리 복합 매칭)은 한 건도 쓰지 않았다** — 전부 정확 식별자로 해결됐다.
71개 Intervals 행이 전부 94개 파일 안에 들어가므로 **아카이브는 모든 출처의 상위집합**이다.

### 5.2 고유·중복·불명확 활동 수

| 구분 | 수 | 비고 |
|---|---|---|
| 전체 고유 운동 | **94** | 아카이브 = 상위집합. UUID 중복 0, Strava ID 중복 0 |
| 이미 RunningAI에 있던 것 | 33 | Intervals 25 + Garmin 8 |
| 시스템 간 중복(동일 운동 다중 보유) | 71 | Intervals 71행 → 70개 파일 (아래 1건이 2행) |
| **Intervals 내부 중복** | **1** | `i180579462` + STRAVA 스텁 `19929243277` = 같은 계단오르기 |
| 매칭 불명확 | **0** | 전 건 정확 식별자로 해결 |
| 다른 어느 시스템에도 없던 것 | **61** | 그중 적재 가능 15, 미지원 종목 46 |

### 5.3 파일명 함정

아카이브 파일명은 **업로드 ID**이고 `Activity ID`와 다르다(94건 전부 불일치). 둘을 혼동하지 않는다.

---

## 6. STEP 6 — Strava FIT Importer 구현

설계 문서: [`docs/architecture/strava-archive-import.md`](../architecture/strava-archive-import.md)

### 6.1 신규 구성요소

| 파일 | 역할 |
|---|---|
| `V26__create_strava_activity_import.sql` | 파일당 1행: 멱등성 키 + 재개점 + 진행 로그 + 품질 기록 + 출처 |
| `FitActivityDecoder.kt` | 공식 Garmin FIT SDK로 **디코딩 전용**. FIT 용어가 여기서 끝남 |
| `StravaArchive.kt` | export 디렉터리 읽기 + RFC 4180 CSV + SHA-256 |
| `StravaFitMapper.kt` | FIT → `NormalizedActivity`/`ActivityDetailData`/`LapData`/`SampleData` |
| `StravaSampleEnricher.kt` | NULL만 채우는 병합 + 출처 각인 |
| `StravaArchiveImportService.kt` | 오케스트레이션·식별·멱등성·재개 |
| `StravaArchiveImportController.kt` | `POST|GET /api/v1/strava/archive-import` |
| `scripts/dev/start-dev-spring.ps1` | 세션과 무관하게 사는 개발 인스턴스 기동 |

**새 의존성 1건**: `com.garmin:fit:21.176.0`(공식 Garmin FIT SDK, Maven Central, 전이 의존성 0, 893 KB).
FIT은 프로파일 기반 바이너리 포맷이라 필드의 의미·배율·단위가 버전별 프로파일에 달려 있다 — 임시 파서로는
정확히 읽을 수 없어 포맷 소유자의 디코더를 쓴다. **쓰기는 하지 않는다.**

### 6.2 지시한 필수 조건 대응

| 조건 | 구현 |
|---|---|
| Raw-first | 디코드 결과를 `activity_raw_payload`(STRAVA)에 먼저 커밋 |
| 출처·외부 ID 보존 | `external_source=STRAVA`, `external_id`=Strava Activity ID 그대로 + `file_sha256` |
| gzip FIT 파싱 검증 | 94/94 성공, 손상 입력은 `FIT_GZIP_UNREADABLE`로 격리 |
| **Garmin ID 임의 생성 금지** | 합성 0건. 테스트로 고정 |
| **고품질 데이터 덮어쓰기 금지** | §6.3 |
| 멱등성 | 파일 SHA-256 비교 → 재실행 시 17개 테이블 **Δ=0** |
| 실패 재시도 | 재-POST가 곧 재시도, `attempt_count` 누적, 자동 재시도 없음 |
| 중단 후 재개 | 파일별 커밋. 강제 종료 후 재개 **무손실** 검증 |
| 파일별 오류 격리 | 1건 실패가 나머지 93건을 막지 않음 (테스트로 고정) |
| 결측·품질 기록 | `quality_flags` + `sensor_channels` + 파트별 상태 |
| 기존 분석 엔진 재사용 | `RunningActivityAnalysisService` 그대로. 엔진 코드 변경 0줄 |

### 6.3 "덮어쓰지 않는다"의 정확한 규칙

* **샘플**: 저장값이 `null`인 지표만 채운다. 기존 값은 **절대** 덮지 않는다. 채운 값마다
  `extra_metrics`에 `"<지표>_source": "STRAVA_FIT"`를 각인한다.
* **랩**: 랩이 없을 때만 쓴다. Garmin처럼 **자기 랩을 가진 출처는 손대지 않는다**
  (`LAPS_PRESENT_NOT_REPLACED`). 단 **이 Importer가 직접 쓴 랩은 자기 것이므로 다시 쓸 수 있다** — 그러지
  않으면 매퍼 수정이 영원히 반영되지 않는다.
* **시계가 겹치지 않는 두 스트림은 병합하지 않는다.** 저장 샘플과 맞물리지 않는 FIT 레코드는 **버린다**
  (덧붙이지 않는다 — 다른 출처가 정의한 스트림에 점을 끼워 넣으면 인덱스의 의미가 달라진다).

### 6.4 케이던스 rpm → spm: 설계안

지시대로 **환산 기능은 구현하지 않고 설계안과 테스트만 제시**한다.

* **현 동작(구현됨)**: FIT 케이던스는 한쪽 다리 **rpm**, `activity_sample.cadence`는 Garmin의 **spm**(배수 2).
  컬럼은 `null`로 두고 원본값은 `extra_metrics.cadence_rpm`에 보존하며
  `CADENCE_SOURCE_RPM_NOT_NORMALIZED`를 기록한다. 7E-1의 Intervals 결정과 동일해 두 Importer가 컬럼의
  의미를 두고 엇갈리지 않는다. **테스트로 고정됨**
  (`cadence is never written to the spm column and the rpm source value is kept`).
* **환산 설계안(미구현, 승인 필요)**: 원본 `cadence_rpm`은 그대로 두고, 파생 컬럼
  `cadence_spm_derived` + `cadence_source`(`MEASURED_SPM` / `DERIVED_FROM_RPM`)를 **새로 추가**한다.
  기존 `cadence` 컬럼은 측정값 전용으로 남겨 **Garmin 원본으로 오인될 여지를 구조적으로 없앤다.**
  분석 엔진은 `cadence` 하나만 읽으므로 파생값을 쓰려면 엔진 측 명시적 선택이 필요하고, 그때
  `RUNNING_ANALYSIS_ENGINE_REVISION`을 올린다.
* **본 Phase에서는 무의미하다**: 적재된 15건과 보강된 25건 **전부 케이던스 채널이 없다**. 환산할 원본이 없다.

### 6.5 랩 평균속도는 파생값이며 그렇게 표시된다

Strava 재인코딩 랩은 `total_distance`와 `total_timer_time`은 기록하지만 `avg_speed`는 기록하지 않는다.
그대로 두면 복원한 랩이 경계값만 남고 랩 지표가 전부 `null`이 된다. 평균속도는 정의상 거리÷시간이므로
**같은 랩 메시지가 말한 두 값으로** 계산하되, `extra_metrics.average_speed_source =
DERIVED_DISTANCE_OVER_TIME`과 `LAP_SPEED_DERIVED_FROM_DISTANCE_AND_TIME`로 **측정값과 구분**한다.
실측 분포: 파생 71 · 측정 123 · 속도 없음 45(거리 0인 실내 사이클).

---

## 7. STEP 7 — 사본 DB 통합 검증

인스턴스 `127.0.0.1:18082` → `running_ai_realcopy`, Garmin 커넥터 죽은 포트, 쓰기 스위치 전부 off.

### 7.1 Import 결과

```
files 94 · imported 15 · enriched 33 · skippedUnsupportedType 46
skippedAmbiguous 0 · failed 0 · samplesWritten 6,498 · lapsWritten 116 · samplesEnriched 18,541
```

### 7.2 지시한 12개 검증 항목

| # | 항목 | 결과 |
|---|---|---|
| 1 | 기존 Garmin 8건 보존 | **보존**. 랩 123개 불변, `LAPS_PRESENT_NOT_REPLACED` 8/8 |
| 2 | Intervals 25건 + 제외 2건 | 25건 전부 UUID로 매칭·보강. 제외 2건 근거 §1 |
| 3 | Strava 94건 처리 | 94/94 처리, 실패 0 |
| 4 | 중복 제거 | **동일 시각 중복 행 0건**. 신규 생성 시 ±120초 충돌 가드 |
| 5 | 재실행 시 증가 0 | **17개 테이블 Δ=0** |
| 6 | 강제 종료 후 재개 | 94건 중 10건 커밋 시점에 JVM kill → 재-POST → **무손실**, 완주본과 동일 |
| 7 | 러닝 분석 재계산 | 48/48 분석, 엔진 리비전 2 |
| 8 | 1 Hz 샘플·랩 보존 | 샘플 75,206 (68,708 + 6,498), 랩 239 (123 + 116) |
| 9 | TrainingContextV2 | `get_training_context` V2 정상, 14,234 바이트, SHA-256 산출 |
| 10 | MCP 읽기 7종 | 7종 전부 응답. LTHR **176/290** = 실데이터 마커 |
| 11 | 총 건수·기간 | **48건**, 2026-06-05 ~ 2026-10-10 |
| 12 | Flyway | V21~**V26** 적용 성공 |

### 7.3 신규 적재 15건 — 품질 그대로

| 종목 | 수 | 기간 | 샘플 | 랩 | HR |
|---|---|---|---|---|---|
| INDOOR_CYCLING | 11 | 2026-06-05 ~ 08-15 | 4,908 | 24 | **0 (NULL 유지)** |
| TREADMILL_RUN | 4 | 2026-07-09 ~ 07-28 | 1,590 | 8 | **0 (NULL 유지)** |

**심박·훈련부하·러닝 다이내믹스를 추정 생성하지 않았다.** 15건의 심박 샘플 수 = **0**,
`analysis_status = PARTIAL`(데이터에 대한 서술이지 세션에 대한 평가가 아님).

**사이클이 러닝으로 처리되지 않음**: `INDOOR_CYCLING` 12건의 분석에서 `cadence_change_spm` **0건**,
`avg_stride_length_cm` **0건**.

### 7.4 기존 25건 보강 결과

| 지표 | 보강 전 | 보강 후 |
|---|---|---|
| 랩 | **0** | **84** (25/25건) |
| 랩 지표 산출 | 0건 | **25/25건** |
| 심박 샘플 | 46,880 (100 %) | **46,880 (100 %) — 불변** |
| 러닝파워 샘플 | 18,344 | **36,885** (FIT이 **18,541** 기여) |

### 7.5 인터벌 5건 — 복원된 것과 복원할 수 없는 것

| Intervals ID | 날짜 | 랩 | 랩 평균속도 | 랩속도 CV | 인터벌 그룹 |
|---|---|---|---|---|---|
| `i180579442` | 2026-08-09 | 10 | 2.471 m/s | 12.31 % | 0 |
| `i182664832` | 2026-09-02 | 5 | 2.502 m/s | 14.56 % | 0 |
| `i183643449` | 2026-09-05 | 9 | 2.821 m/s | 16.12 % | 0 |
| `i184593978` | 2026-09-08 | 12 | 3.143 m/s | 21.66 % | 0 |
| `i185107499` | 2026-09-10 | 8 | 2.555 m/s | 16.94 % | 0 |

**솔직한 한계**: 랩 경계·구간 거리·구간 시간·랩 평균속도는 **정확히 복원**됐지만, `interval_groups`는 **0으로
남는다**. `IntervalStructureExtractor`는 그룹을 만들 때 랩의 `intensity_type` + `workout_step_index`를
요구하는데 **Strava 재인코딩에는 둘 다 없다**(`lap_trigger=manual`뿐). 선수가 랩 버튼을 눌렀을 뿐 워치가
프로그램된 워크아웃을 기록한 적이 없으므로, 랩의 페이스 패턴을 보고 ACTIVE/RECOVERY를 붙이는 것은
**analysis 패키지가 금지하는 추론**이다. 만들지 않았고, 이유를
`LAP_INTENSITY_NOT_RECORDED_BY_SOURCE` / `LAP_WORKOUT_STEP_NOT_RECORDED_BY_SOURCE`로 기록했다.

### 7.6 2026-06 이후 실데이터 커버리지

| 월(KST) | Garmin | Intervals | Strava | 합계 | 러닝 |
|---|---|---|---|---|---|
| 2026-06 | 0 | 0 | 4 | 4 | 0 |
| 2026-07 | 0 | 0 | 10 | 10 | 4 |
| 2026-08 | 0 | 15 | 1 | 16 | 15 |
| 2026-09 | 5 | 10 | 0 | 15 | 14 |
| 2026-10 | 3 | 0 | 0 | 3 | 3 |
| **합계** | **8** | **25** | **15** | **48** | **36** |

기존 8건(2026-09-18~) → **48건(2026-06-05~)**, 커버리지 **23일 → 128일**.

### 7.7 적재하지 않은 46건

근력 38 · 걷기 3 · 계단오르기 3 · 호흡운동 2. 현 `ActivityType`(RUN/TREADMILL_RUN/INDOOR_CYCLING)으로
표현할 수 없어 **`SKIPPED_UNSUPPORTED_TYPE`으로 기록만** 남겼다(근사 금지). 도메인 모델 확장은 별건이다.

---

## 8. STEP 8 — 운영 반영 준비 (실행하지 않음)

| 항목 | 상태 | 갱신 내용 |
|---|---|---|
| A. PostgreSQL 접근 제한 + 마이그레이션 | 승인됨, **범위 확대** | V21~V25 → **V21~V26** |
| B. Garmin Backfill + 분석 재계산 | 승인됨(B-1 포함) | 변경 없음 |
| C. Intervals 전용 Import | 미승인 | 변경 없음 |
| **D. Strava 아카이브 Import** | 미승인 | **본 Phase로 구현 완료**, 아카이브 파일 운영 PC 배치 필요 |
| E. ChatGPT 실데이터 읽기 | 미승인 | **선결: 승인 A의 item G**(PG 5432 루프백 제한) |
| F. ChatGPT 쓰기 확장 | 미승인 | 변경 없음 |

A~F 중 **어느 것도 실행하지 않았다.**

---

## 9. 테스트 결과

```
./gradlew.bat clean test
BUILD SUCCESSFUL — tests=1385  failures=0  errors=0  skipped=0
```

기준선 1,331 → **1,385**(+54). 신규 테스트 분포: 디코더 8, 매퍼 14, 보강기 7, 아카이브 7, 서비스 통합 18.
**모든 FIT 픽스처는 테스트 안에서 SDK 인코더로 생성한 합성 파일**이며, 실제 아카이브의 바이트는 저장소에
한 개도 들어가지 않는다. 좌표도 실제 위치가 아닌 값을 쓴다.

기존 테스트 수정 1건: `SchemaMigrationTest`에 V26 추가.

---

## 10. 운영 반영 전 잔여 위험

| # | 위험 | 심각도 | 현황 |
|---|---|---|---|
| 1 | **RISK-H3** PostgreSQL 5432이 LAN·tailnet에서 평문 superuser로 접속 가능 | **높음** | 미해결. 실데이터가 공개 HTTPS 경로에 올라가기 **전에** 승인 A의 item G 선행 필요 |
| 2 | ChatGPT 최종 확인 미완 | 중간 | 체인 전 구간 검증 완료, 사용자 계정 1회 호출만 남음 |
| 3 | 인터벌 그룹 복원 불가 | 중간 | 원본에 강도 정보 부재(§7.5). 데이터의 한계이지 결함 아님 |
| 4 | 46건 미적재 | 중간 | `ActivityType` 확장 여부는 사용자 결정 대기 |
| 5 | 케이던스 미정규화 | 낮음 | 40건 모두 케이던스 채널 자체가 없어 현재 영향 0 |
| 6 | Quick Tunnel 주소 변동 | 낮음 | 재기동 시 Worker `ORIGIN_MCP_URL` 갱신 필요(기존 사항) |
| 7 | 미커밋 산출물 누적 | 중간 | 7D·7E-0·7E-1·7E-2 산출물이 모두 미커밋 |

---

## 11. 다음 단계 권고

1. **ChatGPT에서 `get_recent_activities` 1회 호출** — 합성 6건 확인으로 STEP 2 종결.
2. **승인 A의 item G(PG 5432 루프백 제한) 선행** — E(실데이터 연결)의 선결 조건.
3. **미커밋 산출물 커밋** — 7D·7E-0·7E-1·7E-2가 모두 작업 트리에만 있다.
4. **`ActivityType` 확장 여부 결정** — 46건(근력 38 등)의 운명이 여기 달려 있다.
5. **운영 반영은 V21~V26을 한 묶음으로** — V26은 신규 테이블 전용이라 추가 위험이 없고, Importer를
   켜지 않는 한 비활성이다.

---

## 12. 변경 파일

```
신규
  server/src/main/resources/db/migration/V26__create_strava_activity_import.sql
  server/src/main/kotlin/com/runningai/integration/strava/FitActivityDecoder.kt
  server/src/main/kotlin/com/runningai/integration/strava/StravaArchive.kt
  server/src/main/kotlin/com/runningai/integration/strava/StravaFitMapper.kt
  server/src/main/kotlin/com/runningai/integration/strava/StravaSampleEnricher.kt
  server/src/main/kotlin/com/runningai/integration/strava/StravaArchiveImportService.kt
  server/src/main/kotlin/com/runningai/integration/strava/StravaArchiveImportController.kt
  server/src/main/kotlin/com/runningai/enrichment/StravaActivityImportEntities.kt
  server/src/main/kotlin/com/runningai/enrichment/StravaActivityImportStore.kt
  server/src/test/kotlin/com/runningai/integration/strava/{SyntheticFitFiles,FitActivityDecoderTest,
      StravaFitMapperTest,StravaSampleEnricherTest,StravaArchiveTest,StravaArchiveImportServiceTest}.kt
  scripts/dev/start-dev-spring.ps1
  docs/architecture/strava-archive-import.md
  docs/work-orders/2026-10-13-phase-7e-2-{instruction,result}.md

수정
  server/build.gradle                                   (com.garmin:fit 추가)
  server/src/main/java/com/runningai/activity/ExternalSource.java   (STRAVA 추가)
  server/src/main/resources/application.yml              (running-ai.strava.archive-import)
  server/src/test/java/com/runningai/SchemaMigrationTest.java       (V26)
```

**분석 엔진 코드는 한 줄도 바뀌지 않았고 `RUNNING_ANALYSIS_ENGINE_REVISION`도 올리지 않았다** —
계산기가 바뀐 것이 아니라 계산기에 주는 데이터가 늘었을 뿐이고, 그 경우를 위해 만든 장치가 입력 다이제스트다.
다만 랩 **개수**가 같은 채 내용만 풍부해지는 경우는 다이제스트가 볼 수 없으므로, 데이터를 바꾼 Importer가
스스로 재계산을 요청한다(§6.3).
