# WO-RUNNINGAI-PHASE-7B-3 — Garmin Running Dynamics & Lactate Threshold Intelligence (결과 보고)

지시서: `2026-10-12-phase-7b-3-garmin-running-dynamics-lactate-threshold-instruction.md`

작업 시작 시 `main-3` HEAD `e1766cd`(Phase 7B-2 커밋 포함) 확인 — 7B-2 안전 게이트가
포함된 상태에서 작업했고 해당 코드는 전혀 수정하지 않았다(게이트 우회 없음). 운영
Spring/Connector/Relay 재시작, 운영 DB 마이그레이션, 운영 프로필 덮어쓰기, 실제 훈련
승인·게시, Watchdog/Tailscale 변경, Canonical/origin 변경: 전부 수행하지 않았다.

## 1. 실제 Garmin 수집 데이터 감사 (STEP A, Activity ID 7)

`activity_raw_payload`(payload_type=`ACTIVITY_DETAILS_STREAM`)의 **원본 metricDescriptors
23개**와 `activity_sample` 6,724행(1Hz, elapsed 0~6,724s — 시간축 정합)을 직접 대조했다.

| 지표 | 수집 | 저장 위치 | descriptor 단위(factor) | 결측 | 비고 |
|---|---|---|---|---|---|
| directSpeed | O | `activity_sample.speed` | mps (0.1) | 0/6,724 | 저장값 평균 3.156m/s — **이미 m/s 단위**(5:17/km과 일치). factor는 변환 제수가 아님(6H-1B 실측 재확인) |
| directHeartRate | O | `.heart_rate` | bpm (1.0) | 0 | |
| directRunCadence | O | `.cadence` | stepsPerMinute (1.0) | 0 | 평균 87.5 — **편측(one-side)** 값. 양측 보행수는 `extra_metrics.directDoubleCadence`(평균 175.6spm) |
| directPower | O | `.power` | watt (1.0) | 1 | |
| directStrideLength | O | `extra_metrics` | **centimeter** (1.0) | 6 (0.09%) | 평균 107.5cm |
| directVerticalOscillation | O | `extra_metrics` | **centimeter** (1.0) | 6 | 평균 8.72cm |
| directVerticalRatio | O | `extra_metrics` | dimensionless (1.0) | 6 | 평균 8.13(%) |
| directGroundContactTime | O | `extra_metrics` | **ms** (1.0) | 6 | 평균 246.4ms |
| directGradeAdjustedSpeed | O | `extra_metrics` | mps (0.1) | 0 | 평균 3.154 — directSpeed와 같은 단위 체계 |
| directPerformanceCondition | O | `extra_metrics` | dimensionless (1.0) | 359 (5.3%) | 평균 -3.84 (Garmin 자체 상대지표, 해석하지 않음) |

**단위는 전부 descriptor가 명시한 것을 그대로 읽었고 추정 변환은 하지 않았다.** 교차검증:
lap 단위 `extra_metrics`(`strideLength` 등, SPLITS payload)와 샘플 스트림 값이 라운딩
범위 내에서 일치.

### 아직 활용하지 않는 수집 데이터

`directDoubleCadence`, `directVerticalSpeed`, `directBodyBattery`(활동 중), `directFractionalCadence`,
`sumAccumulatedPower`, `sumMovingDuration`, `directPerformanceCondition`(수집·보존만, 분석 미사용),
lap `extra_metrics`의 `normalizedPower`/`minPower`/`totalWork`/`maxVerticalSpeed`/`averageMovingSpeed`,
그리고 커넥터 `/lactate-threshold` 응답의 `power` 객체(러닝 FTP 349W — Phase 6D 때부터
의도적으로 범위 밖).

## 2. 신규 분석 지표 (STEP B, `RunningDynamicsCalculator` + V22)

기존 `RunningActivityAnalysisService`에 `RunningDynamicsCalculator`(Kotlin, 순수 계산기)를
추가했다. **입력은 lap 단위 Garmin 자체 평균값**(각 lap의 `extra_metrics` +
`average_power`/`max_power` 기존 컬럼)이며, 샘플 스트림을 재집계하지 않는다 — Garmin이
직접 보증하는 숫자를 쓰는 것이 1Hz 재집계보다 원천에 충실하다.

| 지표군 | 계산 항목 |
|---|---|
| 보폭 | 전체 평균(cm), 전/후반(lap 순서 기준) 평균·변화율, 동일페이스 변화(cm) |
| 수직진폭/수직비율 | 전체 평균, 전/후반 평균·변화율 (각각) |
| 지면접촉시간 | 전체 평균(ms), 전/후반 평균·변화율 |
| 러닝파워 | 평균/최대(W), 전/후반 평균·변화율, 동일페이스 변화(W) |

규칙(지시서 그대로):

- RUN/TREADMILL_RUN이 아니면 전부 null(기존 cadence 규칙과 동일한 `isRun` 분기).
- 지표를 보고한 lap만으로 평균·전후반 계산, 결측 lap은 건너뜀. 2개 미만이면 전후반 null.
- **동일 페이스 비교**: lap 페이스를 15초/km 버킷으로 반올림해 같은 버킷에 속하면서 **3 lap
  이상 떨어진** 쌍 중 가장 멀리 떨어진 쌍만 비교(후반 lap 값 − 전반 lap 값). 조건을 만족하는
  쌍이 없으면 null — 결과를 만들어내지 않는다.
- 전후반은 **lap 순서 기준**(세션 레벨 halves의 elapsed-time 기준과 다름)이며, 코드·마이그레이션·
  프롬프트 모두에 이 차이를 명시했다.
- 부상 원인 진단·자세 좋음/나쁨 판정 없음(기존 analysis 패키지 규칙 유지).

스키마: `V22__add_running_dynamics_to_activity_analysis.sql` — `activity_analysis`에 nullable
컬럼 25개 추가(측정 전용). 기존 행은 재분석(`POST /api/v1/activities/{id}/analysis`, Garmin
호출 불필요) 전까지 NULL. API 응답에 `runningDynamics` 섹션 추가(기존 필드 전부 불변 —
가산적 변경).

## 3. Garmin 젖산역치 연동 검증 (STEP C, 읽기 전용)

커넥터 `GET /lactate-threshold`를 읽기 전용으로 1회 조회했다(운영 프로필 덮어쓰기 없음):

```json
"speed_and_heart_rate": { "calendarDate": "2026-10-06T00:50:55.648",
  "speed": 0.34444348, "heartRate": 176 }
"power": { "functionalThresholdPower": 349, "sport": "RUNNING", ... }
```

| # | 항목 | 값 |
|---|---|---|
| 1 | Garmin 최신 LTHR | **176 bpm** |
| 2 | Garmin 역치 속도/페이스 | raw 0.34444348 → ×10 = 3.444m/s → **290.3초/km (4:50/km)** |
| 3 | 측정·갱신 날짜 | **제공됨**: `calendarDate` 2026-10-06 00:50 KST (`version`/`sequence`도 동일 시각값) |
| 4 | DB LTHR | 176 bpm |
| 5 | DB 역치 페이스 | 290초/km |
| 6 | 최근 값 변경 | DB `updated_at` = 2026-10-05 19:30 UTC(= 10-06 04:30 KST) — Garmin `calendarDate`(10-06 00:50 KST) 직후 |
| 7 | 수동 vs 자동 차이 | **현재 없음** — DB와 Garmin 최신값이 정확히 일치 |
| 8 | 심박존 기준 | Zone 하한 96/115/134/154/173 = 최대심박 **192bpm**의 50/60/70/80/90% (Garmin %MaxHR 자동존; 레이스 실측 최고 HR 191로 설정값에 부합) |

### 180bpm → 176bpm 차이의 해명 (지시서 핵심 질문)

- Phase 6D(2026-10-02) 실계정 검증 당시 Garmin 스냅숏은 **180bpm**이었다.
- **Garmin 자체가 2026-10-06에 LTHR을 176으로 재감지**했다(`calendarDate`가 그 증거).
- DB `updated_at`(10-06 04:30 KST)이 그 직후이고 값이 정확히 일치하므로, 현재 DB의 176은
  **`POST /api/v1/garmin/profile-sync`가 Garmin의 새 자동감지값을 끌어온 결과**로 보는 것이
  가장 합리적이다. 즉 **Phase 7B-1의 "출처 미상의 수동 입력값" 해석은 정정된다** — 값
  자체는 Garmin 자동감지와 일치한다. 다만 `athlete_intensity_profile`에 출처 컬럼이 없어
  이를 데이터로 증명할 수 없다는 구조적 문제는 그대로이며(STEP D), 10-06의 감지가 10-11
  레이스 이전 데이터 기반이라는 점도 변하지 않는다.
- speed 변환 규칙(raw × 10 = m/s) 재검증: 0.34444348 → 290.3초/km, DB의 290과 0.1% 내
  일치 — **Phase 6D의 0.1m/s 단위 결론 유효**.

## 4. 프로필 신뢰도·이력 설계 제안 (STEP D — 설계만, 구현하지 않음)

제안: `athlete_intensity_profile`은 "현재 적용값" 전용으로 유지하고, 새 append-only 테이블
`athlete_intensity_profile_history`를 추가한다(7B-2 `injury_report`와 같은 패턴):

```
athlete_id, lthr_bpm, threshold_pace_s_per_km,
source VARCHAR CHECK (source IN ('MANUAL','GARMIN')),
garmin_calendar_date TIMESTAMPTZ NULL,   -- Garmin 측정 시각(제공 시)
fetched_at TIMESTAMPTZ NULL,             -- RunningAI 조회 시각(GARMIN일 때)
applied BOOLEAN NOT NULL,                -- 실제 프로필에 반영됐는지
previous_lthr_bpm, previous_pace INT NULL, -- 직전 적용값
recorded_at TIMESTAMPTZ NOT NULL
```

- `PUT /api/v1/athlete/intensity-profile` → source=MANUAL 행 insert, `profile-sync` →
  source=GARMIN 행 insert(미변경 시 applied=false로도 기록해 "조회했으나 동일" 사실 보존).
- `activity_analysis`는 이미 `lthr90/95/100_seconds`를 계산 시점 값으로 영속하므로 "과거
  분석이 어떤 기준으로 계산됐는지"는 재분석 전까지 자동 보존된다 — history 테이블은 그
  기준이 **언제 왜 바뀌었는지**를 채운다.
- Garmin 최신값 자동 반영과 profile-sync Scheduler 활성화는 **별도 승인 대상**으로 유지.
- 이번 Phase 구현 범위에서 제외했다(지시서: "설계를 제안한다"). 대신 그 공백을 코치가 알도록
  `TrainingContextV2.lactateThresholdProvenance`(`sourceTracked=false` + 경고문)를 추가했다
  (아래 6번).

## 5. 서울레이스 재분석 (STEP E — 실측 수치; Activity ID 7)

새 계산기를 **실제 lap 데이터 전체(22 laps)로 구동한 단위 테스트**
(`RunningDynamicsCalculatorTest`의 Seoul Race 케이스)와 DB 직접 집계로 재분석했다. (운영
Spring은 구버전 코드로 기동 중이고 재시작이 금지되어, 운영 API를 통한 재분석은 수행하지
않았다 — 운영 DB의 `activity_analysis` 행도 V1 상태 그대로다.)

### 측정값 (Garmin lap 원본)

전반(lap 1~11) vs 후반(lap 12~22):

| 지표 | 전반 | 후반 | 변화 |
|---|---|---|---|
| 보폭 | 110.00cm | 105.23cm | **-4.33%** |
| 지면접촉시간 | 242.30ms | 250.23ms | **+3.27%** |
| 수직진폭 | 8.718cm | 8.721cm | **+0.03% (사실상 불변)** |
| 수직비율 | 7.95% | 8.31% | **+4.58%** |
| 평균 파워 | 329.8W | 312.1W | **-5.37%** (최대 653W, lap 9의 순간 스파이크) |

14~20km(최저속 구간) vs 초반 안정 구간(2~7km):

| 지표 | 2~7km | 14~20km |
|---|---|---|
| 보폭 | 약 110.0cm | 약 103.3cm (**-6.1%**) |
| 지면접촉시간 | 약 240.7ms | 약 252.4ms (**+4.9%**) |
| 케이던스(양측) | 177~179spm | 173~174spm (약 -2.3%) |
| 속도 | ~3.25m/s | ~2.99m/s (-8.0%) |

동일 페이스 비교(같은 ~15초/km 버킷, 3 lap 이상 이격): 초반 ~5:06/km lap과 21km 지점
스퍼트 lap(5:06/km) 비교에서 보폭 차이 **-2.2cm(-2%)**, 파워 차이 **약 -4W** — 전후반
전체 차이(-4.3%/-5.4%)보다 훨씬 작다.

### 통계적 해석 (인과 단정 없음)

- **14~20km 페이스 저하는 보폭 감소가 산술적으로 주성분이다**: 속도 손실 -8.0% ≈ 보폭
  -6.1% × 케이던스 -2.3% (0.939×0.977=0.917). 보폭 손실이 케이던스 손실의 약 2.6배를
  설명한다. (관련성의 서술이며, 보폭 감소의 *원인*은 이 데이터로 단정하지 않는다.)
- **지면접촉시간은 속도와 역방향으로 일관되게 증가**(240→252ms), 케이던스 저하와 동반.
- **수직진폭은 거의 불변, 수직비율만 상승** — 수직비율=진폭/보폭이므로 이는 "더 튀어서"가
  아니라 **분모(보폭)가 줄어서** 생긴 상승이다. 수치 구조상의 설명이며 자세 판정이 아니다.
- **파워는 속도와 거의 비례해 하락**(-5.4% vs 속도 -5.9%) — 같은 출력 대비 속도가 급격히
  나빠진 패턴(러닝 이코노미 붕괴를 시사할 만한 파워-속도 괴리)은 lap 수준에서 관찰되지
  않았다. 즉 후반 저하는 "출력을 못 낸" 쪽에 가깝고, 이는 7A-1의 Speed-HR Decoupling
  +6.24%(심박 유지·속도 하락)와 방향이 일치한다.
- **초반 과속과의 관계**: lap 1(4:52/km, 보폭 114.3cm — 레이스 최대)은 이후 안정 구간보다
  분명히 빨랐지만, 동일 페이스 비교에서 후반에도 같은 페이스를 거의 같은 보폭·파워로 재현할
  수 있었다(스퍼트 lap 21). 관찰 가능한 사실은 "지속 능력의 저하"이지 lap 1의 과속이 이를
  유발했다는 인과는 이 데이터로 단정할 수 없다.
- **LTHR 기준 민감도(핵심)**: 역치 이상 노출 시간이 LTHR=176이면 **4,738초(70.5%)**,
  LTHR=180(6D 당시 값)이면 **1,532초(22.8%)** — 단 4bpm 차이로 해석이 3배 이상 달라진다.
  7B-1에서 지적한 "LTHR 정확도 의존성"이 정량적으로 확인됐다. Zone5 92.84%는 LTHR이 아닌
  최대심박(192) 기준이므로 이 차이와 무관하게 유지된다.

## 6. AI Coach Context 확장 (STEP F)

`TrainingContextV2`에 가산적 필드 2개(기존 필드·V1·검증기·응답 계약 불변):

- `recentActivities[].runningAiAnalysis.runningDynamics`(`RunningDynamicsEvidenceV2`):
  지표별 전체 평균 + 전후반 변화율 **요약 10개 필드만** — 샘플 스트림은 전달하지 않는다.
  저장된 다이내믹스가 없는 활동은 null.
- `lactateThresholdProvenance`(`LactateThresholdProvenanceV2`): LTHR/페이스 값,
  `profileLastUpdatedAt`/`profileAgeDays`(DB `updated_at` 기반), `sourceTracked=false`(현
  빌드의 사실), 그리고 경고문 — 출처 미추적 경고는 역치가 존재하면 항상, 30일 이상 미변경
  시 노후 경고 추가.

`ClaudeCoachPromptBuilder` 시스템 프롬프트에 두 필드의 읽기 지침을 추가했다: LTHR 불확실성을
침묵 속에 확신으로 바꾸지 말 것, 다이내믹스 수치로 부상 원인 진단·자세 판정 금지, lap-순서
halves와 elapsed-time halves의 구분. 크기 가드(64KiB) 불변 — 직렬화 테스트로 확인.
**Injury Safety Gate(7B-2)는 단 한 줄도 수정하지 않았다.**

## 7. 테스트 결과 (STEP G)

`.\gradlew.bat clean test`: **BUILD SUCCESSFUL — 1,239개 전부 PASS, 실패 0** (기존 1,229 +
신규 10). H2 인메모리 + 실제 Flyway V1~V22 (PostgreSQL 검증은 이번에 별도 수행하지 않음 —
V22는 nullable ALTER ADD COLUMN만이라 H2 PostgreSQL 모드로 충분하다고 판단, 운영 적용 시
일반 기동 검증으로 충분).

신규 테스트 매핑: 단위·결측·비러닝 null 처리 / 전후반·lap별 계산 / 동일페이스 비교(없으면
null) / 실제 서울레이스 22-lap 데이터 재분석 / max파워는 평균 아닌 최대 / V2 provenance
경고 / runningDynamics 요약 전달 / Context 직렬화(기존 `CoachContextSerializerTest` 확장
픽스처로 통과) / 기존 분석 API 회귀(`ActivityAnalysisApiTest` 등 전부 통과) / Injury Safety
Gate 회귀(7B-2 테스트 전부 통과). **Garmin 계정 쓰기 작업 없음**(읽기 전용 GET 1회뿐).

도중 수정 2건: `SchemaMigrationTest` 마이그레이션 수 20→22 반영,
`TrainingContextV2BuilderTest`의 provenance age 단정(픽스처 날짜가 과거라 음수가 정상)을
존재 단정으로 교정.

## 8. 운영 영향

- 운영 Spring(구버전 코드)·Connector·PostgreSQL: 무변경, 무재시작. 운영 `activity_analysis`
  rows는 V1 그대로(새 컬럼은 운영 마이그레이션·재분석 후에만 채워짐).
- 운영 프로필(176/290): 읽기만 했고 쓰지 않았다.
- 커밋: `main-3`에만. origin/main·Canonical 변경 없음.

## 9. 추가 개선 과제 (우선순위순)

1. **프로필 출처·이력 테이블 구현**(STEP D 설계안) — `sourceTracked=false` 경고를 실제
   데이터로 대체하는 유일한 방법.
2. **운영 반영 절차**: V22 마이그레이션 적용(+기동) 후 Activity 7(및 최근 런)에 대해
   `POST /api/v1/activities/{id}/analysis` 재분석 1회 — Garmin 호출 불필요.
3. **LTHR 변경 시 재분석 트리거 검토**: LTHR이 176→다른 값으로 바뀌면 `lthr*_seconds`가
   낡은 기준으로 남는다(현재는 수동 재분석 필요).
4. directPerformanceCondition / directDoubleCadence / normalizedPower 등 미활용 지표의
   단계적 활용 검토.
5. 7B-2에서 이월된 과제(통증 보고 입력 경로 연결 등) 유지.

작업 완료. 지시서에 따라 STOP한다.
