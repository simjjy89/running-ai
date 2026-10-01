# 2026-09-30 — Phase 5C-0: Legacy Intervals.icu / Garmin Structured Workout Pipeline Investigation

> **DEPRECATED (Phase 5C-5): historical investigation of the legacy pipeline. Do not use for new workout publishing.**
> Canonical path: Spring `IntervalsWorkoutPublisher`. The legacy scripts described here are reference / manual-rollback only.

| 항목 | 내용 |
|------|------|
| 날짜 | 2026-09-30 |
| 작업 | `C:\running-ai` legacy pipeline READ-ONLY 조사, Spring 5B-2 ↔ legacy field mapping, capability matrix, Phase 5C 아키텍처 제안 |
| 상태 | 완료 (조사만; 구현 없음) |
| 커밋 | `docs: investigate legacy structured workout pipeline` |
| 지시서 원문 | [2026-09-30-legacy-structured-workout-investigation-instruction.md](2026-09-30-legacy-structured-workout-investigation-instruction.md) |
| 이전 작업 | 46255ff `feat: add workout intensity target model` (Spring, Phase 5B-2) |

**Scope note**: 이 문서는 `C:\running-ai`(legacy PowerShell 프로젝트)를 READ-ONLY로 조사한 결과다.
legacy repo에는 어떤 파일도 쓰지 않았고, 실제 Intervals.icu/Garmin publish나 sync는 실행하지 않았다.
조사 시작 시점에 legacy repo는 이미 다수의 unstaged 변경(사용자의 별도 진행 중 작업)이 있는 상태였으며,
이는 이 조사 이전부터 존재하던 것으로 이 조사가 만든 변경이 아니다 — `git status`/`git diff`/`git add`/
`git reset` 등 어떤 git 명령도 legacy repo에서 실행하지 않았다(자세한 내용은 L절 참고).

---

## A. Executive Summary

Legacy는 `scripts/intervals-structured-workout.ps1`의 `ConvertTo-IntervalsStructuredWorkoutDescription`이
PowerShell 워크아웃 객체를 Intervals.icu Workout Builder **text**(구조화 payload가 아니라 파싱되는 문자열)로
직렬화하고, `create-today-workout.ps1`/`training-workout-dispatcher.ps1`을 통해 `POST/PUT/DELETE
https://intervals.icu/api/v1/athlete/0/events`로 publish한다. Pace는 실제 structured target(server가
`workout_doc.steps[].pace={start,end,units}`로 되돌려줌, event 138398536으로 재현 확인)이고, HR은 absolute
bpm이 아니라 %LTHR range(`hr=1s` suffix, Garmin Instant HR)로만 structured 처리되며 이마저도 Garmin 실기기
readback으로 검증된 적은 없다(정적 추적만 존재). Treadmill speed/incline은 structured target이 전혀 아니고
Garmin이 화면에 표시하는 step 텍스트("cue")로만 전달되며, 그 cue는 `Get-GarminSafeStepCue()`가 만들고
**step 줄에서 duration/target 토큰보다 앞에 와야만** Garmin 화면에 보인다는 것이 실제 Forerunner 265 관찰로
확인된 규칙이다(반대로 workout-level Notes와 예전 방식의 segment.notes 배치는 Intervals에는 보여도 Garmin
화면에는 전혀 나타나지 않았음이 확인됨). 현재 파이프라인의 목표 delivery mode는 `GARMIN`
(`config/training-delivery-config.json`)이며, Apple Watch 경로는 폐기됐다. **Garmin으로의 최종 전달은
Intervals.icu 계정의 자체 Garmin Connect 연동에 전적으로 의존하며, 이 repo에는 그 이후 단계(Intervals →
Garmin Connect → 기기)를 구동하거나 관측하는 코드가 전혀 없다** — repo 자신의 진단 문서가 이를 명시적으로
인정한다.

## B. Legacy Flow

```text
input  : Training Command JSON (config/training-command-schema.json 필드 shape)
           또는 자동 planner의 workout_description 텍스트
   ↓
builder: (Command Channel 경로만) command-channel-worker.ps1 / select-recommendation-core.ps1
           → 워크아웃 object 조립 (type, segments[], pace_target, hr_target, target_type, lthr_bpm, notes)
   ↓
dispatch: scripts/training-workout-dispatcher.ps1 Resolve-TrainingWorkoutRenderPlan
           → running이면 ConvertTo-IntervalsStructuredWorkoutDescription으로,
             INDOOR_CYCLING(cross-training)이면 별도 렌더러로 분기
   ↓
renderer: scripts/intervals-structured-workout.ps1
           ConvertTo-IntervalsStructuredWorkoutDescription
           → Render-Step (label + Get-GarminSafeStepCue(note) + measure + target)
           → Intervals.icu Workout Builder text (description 문자열)
   ↓
publisher: scripts/create-today-workout.ps1 (-TrainingPlanFile 경로) 또는
           scripts/create-today-workout.ps1 하단 자동 planner 경로
           → Invoke-RestMethod POST/PUT/GET/DELETE
             https://intervals.icu/api/v1/athlete/0/events[...]
           → 곧바로 GET으로 재조회(readback)해 workout_doc.steps/pace/HR 텍스트가
             실제로 서버에 반영됐는지 검증(Test-WriterMetricReadback 등)
   ↓
Intervals.icu: 서버가 description을 파싱해 workout_doc(구조화 JSON)을 생성/보관.
             이 repo가 관측 가능한 마지막 지점.
   ↓
Garmin: Intervals.icu 계정에 연결된 Garmin Connect 동기화(this repo 밖, 완전히 불투명).
        Garmin Forerunner 265에서 실제로 보이는 화면은 사용자가 육안으로만 확인 가능하고,
        이 repo는 그 결과를 코드로 검증할 방법이 없다(DIAGNOSIS 문서에서 명시적으로 인정).
```

## C. File Inventory

| File | Responsibility | Keep? |
|---|---|---|
| `scripts/intervals-structured-workout.ps1` | 핵심 renderer: pace/HR target 변환, `Get-GarminSafeStepCue`, step 조립, description 조립, readback 검증 헬퍼 | **PORT_LOGIC** (규칙을 Java로 이식) |
| `scripts/running-workout-types.ps1` | Workout type registry(`config/running-workout-types.json` 로딩), `Test-RunningWorkoutMetricUnits`(metric-only 검증) | PORT_LOGIC (registry 개념) / REWRITE (구체 타입은 QUALITY 미구현이라 대부분 불필요) |
| `scripts/training-workout-dispatcher.ps1` | running vs cross-training(cycling) 렌더러 분기 | DROP (Spring 범위는 현재 running만; 필요해지면 재설계) |
| `scripts/cross-training-structured-workout.ps1` | INDOOR_CYCLING 전용 렌더러(별도 %LTHR+cadence 경로) | KEEP_LEGACY_ONLY (Spring에 cross-training 도메인 자체가 아직 없음, 5B-2도 QUALITATIVE만 지원) |
| `scripts/create-today-workout.ps1` | Publisher: Intervals.icu HTTP 호출(POST/PUT/GET/DELETE), 인증 헤더 생성, idempotency(marker 기반 create/update/conflict), 자동 planner 경로 | PORT_LOGIC (idempotency 규칙) / REWRITE (HTTP client는 Java) |
| `scripts/training-control-common.ps1` | `Get-CommandValidation`(fail-closed validator), `Get-TrainingDeliveryConfig`/`Test-TrainingDeliveryGarminModeActive`(delivery mode 판독) | PORT_LOGIC (validator 규칙, delivery mode 개념) |
| `scripts/training-writer-common.ps1` | `Resolve-TrainingWriterPlanType`, `Test-TrainingWriterDeliverySyncReady`, `Get-TrainingWriterEventPayload` | PORT_LOGIC |
| `scripts/command-channel-worker.ps1` | 외부 Command Channel(Google Drive 큐) 오케스트레이션의 일부로 렌더 플랜 호출 | DROP (Spring에는 Command Channel/외부 AI 승인 큐가 없음; RunningAI Spring 서버가 직접 트리거) |
| `config/training-delivery-config.json` | `active_delivery_mode: GARMIN` 등 delivery mode 설정 | PORT_LOGIC (개념만; Spring에선 애초에 GARMIN 전용으로 시작 가능) |
| `config/runner-hr-profile.json` | 사용자의 실제 `lthr_bpm`/`max_hr_bpm` (개인 데이터 — 값은 이 문서에 옮기지 않음) | DROP as a file (Spring은 이미 `AthleteIntensityProfile` DB 테이블로 이 역할을 대체함, Phase 5B-2 완료) |
| `config/running-workout-types.json` | Canonical running workout type registry(12종 + alias, 위험도, structured mapping) | KEEP_LEGACY_ONLY / 참고자료 (Spring 5B-1은 REST/RECOVERY/EASY/LONG/CROSS_TRAINING/QUALITY 6종만 지원, legacy 12종보다 훨씬 단순) |
| `config/training-command-schema.json` | Command Channel JSON schema(`target_type`, `lthr_bpm`, `segments[].hr_min/max` 등 포함) | DROP as a schema (Spring엔 이미 `TargetedWorkoutPrescription`이 있음); 필드 이름은 참고 |
| `TRAINING_CONTROL.md` | 전체 아키텍처 spec 문서(가장 신뢰할 수 있는 단일 소스) | 참고 전용(레포에 남김, 이식 대상 아님) |
| `docs/DIAGNOSIS-EVENT-138398536-GARMIN-PACE-TARGET.md` | Pace target이 Intervals엔 있는데 Garmin엔 "목표 없음"으로 보인 사고에 대한 read-only 진단 | 참고 전용 — **중요 risk 근거** |
| `docs/DECISION-HEART-RATE-STRUCTURED-TARGET-NOT-IMPLEMENTED.md` | Absolute bpm structured target을 만들지 않기로 한 결정 기록 | 참고 전용 — **중요 risk 근거** |
| `docs/HR-TARGET-PERCENT-LTHR-IMPLEMENTATION.md` | %LTHR + `hr=1s` 구현 기록, 미검증 한계 명시 | 참고 전용 — **중요 risk 근거** |
| `docs/work-orders/20260928-2040-garmin-treadmill-step-notes.md` | Segment notes가 사라지던 원인(prompt wording) 수정 기록 | 참고 전용 |
| `docs/work-orders/20260928-2305-garmin-step-cue-serialization.md` | Cue를 target 앞으로 옮긴 수정 기록(현재 `Get-GarminSafeStepCue` 배치 규칙의 근거) | 참고 전용 — **가장 중요한 root-cause 문서** |
| `scripts/test-structured-running-workout.ps1` | 12종 canonical running workout type 전체 회귀(라벨/measure/target 순서, idempotency, metric 단위, HR target 렌더) | Golden-master 후보 (규칙 재현 fixture로 재사용 검토) |
| `scripts/test-workout-metric-units.ps1` | Metric-only(비-imperial) 검증 회귀 | Golden-master 후보 |
| `scripts/test-hr-target-percent-lthr.ps1` | `target_type=HR` %LTHR 렌더링 22개 assertion(정적 추적으로만 검증됨, 아래 참고) | PORT_LOGIC 근거 자료 |
| `scripts/test-hr-target-schema.ps1` | Command schema 레벨 HR validation 회귀 | 참고 |
| `scripts/test-treadmill-run-step-notes-survival.ps1` | 5-step treadmill(9.7/10.7/12.0/10.7/9.6 km/h + incline 0.5-1%) cue 배치 회귀 | **Golden-master 후보** (cue 포맷 규칙의 정본) |
| `scripts/test-training-delivery-mode.ps1` | GARMIN vs APPLE_WATCH delivery mode gating 회귀 | 참고 (Spring은 애초에 단일 모드로 시작 가능해 불필요할 수 있음) |

## D. Intervals Syntax

CODE_CONFIRMED (모두 `scripts/intervals-structured-workout.ps1` 직접 인용, 실제 함수/코드 근거):

```text
warm-up / easy / cool-down (CONTINUOUS/WARMUP_MAIN_COOLDOWN 매핑, 실제 EASY_RUN 예):
  Easy Run
  - 5km 6:30-7:00/km Pace

pace target (범위, Get-IntervalsPaceTarget):
  단일값(min=max): "6:30/km Pace"
  범위(min<max):   "6:30-7:00/km Pace"
  (내부적으로 pace seconds/km -> "M:SS/km" 변환은 Convert-SecondsPerKmToIntervalsPace,
   소수 second는 반올림 후 정수초로 표현)

HR target, %LTHR 구조화 경로 (target_type=HR, Get-IntervalsHrPercentLthrTarget):
  단일값: "81.0% LTHR hr=1s"
  범위:   "75.4-81.0% LTHR hr=1s"
  (LTHR 기준 bpm -> 퍼센트 변환은 Convert-BpmToPercentLthr, 소수 1자리까지 반올림)

HR target, PACE 모드의 참고 전용 텍스트(구조화 아님, Get-IntervalsHrReferenceTokens 대상):
  "HR target reference (not structured): 135-145 bpm"

step cue (Get-GarminSafeStepCue, target 앞에 배치):
  "- Warm Up 9.7kph Incline0.5-1pct 8m 6:10/km Pace"
```

## E. Garmin Compatibility

| Feature | Code supports | Garmin 265 physically verified | Evidence |
|---|---:|---:|---|
| Pace target (structured) | YES | **PARTIAL / INCONSISTENT** | `DIAGNOSIS-EVENT-138398536`: Intervals 서버 readback은 확인됐지만 실제 Garmin에서 "목표 없음"으로 보인 사례가 문서화됨(원인 미확정). LIVE_PREVIOUSLY_CONFIRMED(실패 방향), UNKNOWN(근본 원인) |
| HR target (%LTHR + hr=1s, structured) | YES | **NO (미검증)** | `HR-TARGET-PERCENT-LTHR-IMPLEMENTATION.md`가 명시: "Intervals.icu가 `hr=1s`를 실제로 인식하는지, 서버가 `%LTHR` 텍스트를 그대로 유지하는지" 둘 다 이 세션에서 확인하지 못했다고 스스로 기록. ASSUMED |
| HR target (absolute bpm, structured) | NO (의도적으로 미구현) | N/A | `DECISION-HEART-RATE-STRUCTURED-TARGET-NOT-IMPLEMENTED.md`: 사용자가 Garmin에서 직접 확인 — bpm 텍스트는 target이 아니라 라벨 텍스트로만 보였음. LIVE_PREVIOUSLY_CONFIRMED (미지원 사실 자체는 확인됨) |
| Speed cue (텍스트, "Nkph") | YES | **YES** (간접) | `20260928-2305` 작업의 동기: 사용자가 Forerunner 265에서 segment notes가 전혀 안 보인다고 보고 → cue-before-target 배치로 수정. 수정 자체의 사후 재확인 문서는 repo에 없음. CODE_CONFIRMED(배치 규칙) + LIVE_PREVIOUSLY_CONFIRMED(수정 전 실패 관찰)이지만 수정 후 성공은 ASSUMED |
| Incline cue (텍스트, "IncineX-Ypct") | YES | 위와 동일 | 위와 동일 |
| Workout-level Notes | YES (Intervals에 표시) | **NO** | `20260928-2305`: "workout-level Notes가 Intervals에는 보이지만 Garmin 시계에는 표시되지 않았다" — repo 문서가 명시적으로 이 경로를 실패로 분류. LIVE_PREVIOUSLY_CONFIRMED |
| Segment notes (target 뒤 배치, 이전 방식) | YES (Intervals에 표시) | **NO** | 동일 문서: 구 방식(측정값 뒤에 note 배치)은 Garmin에 전혀 보이지 않았음이 수정 동기 자체. LIVE_PREVIOUSLY_CONFIRMED |
| Idempotent update (marker 기반) | YES | N/A (서버/API 레벨 동작, 기기 무관) | `create-today-workout.ps1` 코드 직접 확인. CODE_CONFIRMED |

## F. Cue Formatting — `Get-GarminSafeStepCue()`

```text
input:  raw segment note 문자열 (예: "12.0 km/h | 경사 0.5-1%"), null/공백 허용
output: 짧은 ASCII-safe cue 문자열 또는 $null

규칙 (코드 순서 그대로, CODE_CONFIRMED):
1. null/공백 -> $null
2. '|' -> 공백으로 치환 (여러 항목 구분자 제거)
3. '경사\s*' -> 'Incline' (한글 레이블 정규화)
4. '(?i)\bincline\s+' -> 'Incline' (영문 레이블도 정규화, 대소문자 무관)
5. 'N km/h' (소수 허용) -> 'Nkph' (단위 앞 공백 제거)
6. 'N%' 또는 'N-M%' -> 'Npct' 또는 'N-Mpct' (percent 기호를 항상 숫자 뒤 'pct'로 치환)
7. 남은 '%' 모두 제거 (Intervals 파서가 '%'를 자체 target 토큰으로 오인할 수 있어서)
8. 연속 공백 축소 + trim
9. 결과가 공백뿐이면 $null

길이 제한: 코드에 명시적 문자 수 제한 없음 (ASSUMED: 실제 Garmin 화면 표시 길이 제한은 이 레포가
          알지 못하며, 원본 note가 이미 짧다는 실사용 전제에 의존)
허용 문자: 정규화 후 사실상 ASCII 영숫자 + 공백만 남도록 설계(한글 라벨은 'Incline'으로 치환되어
          사라짐; 다른 한글 텍스트가 note에 남아있으면 그대로 통과 — 비한글 라벨만 처리 대상)
speed formatting:   'N km/h' -> 'Nkph' (소수점 유지, 단위 붙여쓰기)
incline formatting: '경사'/'incline' -> 'Incline' (라벨만 치환, 수치와 %는 규칙 6/7이 처리)
null 처리:          입력 전체가 없으면 $null 반환, 호출부(Render-Step)가 빈 문자열 필터로 slot에서 제거
pace-only 처리:     note가 없으면 cue 자체가 없음(pace target 렌더링과 무관, 별도 경로)
HR-only 처리:       note가 없으면 동일하게 cue 없음; HR 텍스트 자체는 cue가 아니라 target 표현(%LTHR
                    또는 reference bpm)이라 이 함수의 대상이 아님
```

**Step Rendering Order (B.6절)**: 실제 코드(`Render-Step`, `intervals-structured-workout.ps1:270-304`)는
`label → cue → measure → target` 순서로 조립한다(`(@($label,$cue,$measure,$target)|Where-Object{...})
-join' '`). 과거의 `label → measure → pace → note`(target 뒤에 note) 방식은 현재 코드에는 남아있지 않다 —
단, 레포 어딘가에 있는 **cross-training 렌더러(`cross-training-structured-workout.ps1`)는 별도 파일이라 이
조사에서 상세 대조하지 않았음**(범위 밖: 이번 Spring 5B-2는 CROSS_TRAINING을 QUALITATIVE로만 처리). 옛
순서는 `docs/work-orders/20260928-2040-garmin-treadmill-step-notes.md`의 "이전" 예시(`label measure target
note`)로만 문서에 남아있고, 이것이 바로 Garmin에 보이지 않던 실패 사례였다. CODE_CONFIRMED + TEST_CONFIRMED
(`test-treadmill-run-step-notes-survival.ps1`).

## G. Publish Flow

```text
endpoint:      https://intervals.icu/api/v1/athlete/0/events (GET 목록, GET 단건 {id}, POST 생성,
               PUT {id} 수정, DELETE {id} 취소) — 실제 코드에서 '0'을 athlete id 자리에 literal로
               사용(Intervals.icu API가 인증된 토큰의 소유자를 '0'으로 self-reference하도록 지원하는
               것으로 보임; 이 레포가 실제 athlete numeric id를 별도로 저장하지 않음)
create/update: marker 기반. 매 write 전에 대상 날짜의 오늘자 WORKOUT 카테고리 event를 조회해
               description에 '[RunningAI-Control] command_id=...' marker가 있는 것만 "managed"로
               취급한다. CREATE_WORKOUT/UPSERT_WORKOUT: 같은 command_id의 managed event가 이미
               있으면 DUPLICATE(재작성 없이 그대로 성공 반환), 다른 관리/비관리 event가 있으면
               CONFLICT. REPLACE_WORKOUT/CANCEL_WORKOUT: 정확히 managed event 1개만 있을 때만
               허용, 그 외엔 CONFLICT. 즉 진짜 idempotency: "같은 command_id로 다시 호출해도 새
               event가 생기지 않는다"가 코드로 보장된다.
idempotency:   CODE_CONFIRMED (위 marker 로직, create-today-workout.ps1:172-181)
timezone:      명시적 타임존 변환 코드가 없다. `$today=(Get-Date).ToString("yyyy-MM-dd")`(자동
               planner 경로)와 `start_date_local="${today}T00:00:00"`처럼 **로컬 머신 wall-clock
               날짜**를 그대로 사용한다. Training Control adapter 경로(`-TrainingPlanFile`)도
               `plan.target_date`를 `yyyy-MM-dd`로만 파싱하고 타임존 변환이 없다. 즉 legacy는
               "PC가 이미 Asia/Seoul에서 돌고 있다"는 암묵적 가정에 의존한다(Spring의 명시적
               `athlete.timezone` 필드와 다름). CODE_CONFIRMED
auth source:   환경변수 `INTERVALS_ICU_API_KEY` (`[Environment]::GetEnvironmentVariable(...,
               "User")` 우선, 없으면 프로세스 `$env:` fallback). Basic Auth 헤더를
               `Convert::ToBase64String("API_KEY:$ApiKey")`로 직접 구성. config 파일에는
               저장하지 않음(TRAINING_CONTROL.md 보안 절: "Command, Result, Drive, log에 저장하지
               않는다"). 실제 키 값은 이 문서 어디에도 옮기지 않았다(SECRET는 환경변수에만 존재,
               `SECRET_PRESENT_IN_LEGACY_CONFIG`에 해당하는 config 파일 노출은 발견되지 않음).
```

## H. Spring Mapping

| Spring `TargetedWorkoutPrescription` | Legacy input | Mapping |
|---|---|---|
| `intent` (`CandidateTrainingType`) | `plan.type` / `Workout.type` (12종 canonical + alias) | **TRANSFORM** — Spring 6종(REST/RECOVERY/EASY/LONG/CROSS_TRAINING/QUALITY) vs legacy 12종(TEMPO/THRESHOLD/INTERVAL/PROGRESSION/FARTLEK/HILL/STRIDES 등 세분화). 1:1 아님 |
| `segments[].type`(WARM_UP/MAIN/COOL_DOWN/REST) | `segment.name` + `typeDefinition.structured_mapping`(CONTINUOUS/WARMUP_MAIN_COOLDOWN/STAGED_PROGRESSION/...) + `Get-RunningWorkoutDefaultStepLabel` | **TRANSFORM** — legacy는 구조 매핑이 5종류나 있고 반복(repeat/group)까지 있음; Spring 5B-1은 항상 정확히 3구간(또는 REST 1구간)만 있음 |
| `durationMinutes` | `segment.duration_min` 또는 `segment.distance_km`(둘 중 하나, `Render-Step`의 `Convert-MinutesToIntervalsDuration`/`Convert-KmToIntervalsDistance`) | **TRANSFORM** — Spring은 항상 duration(분)만 사용; legacy는 distance 기반 segment도 지원(예: `5km`) |
| `intensityClass` | 없음(legacy는 intensity class 개념이 없고 pace/HR 값 자체가 전부) | **MISSING (legacy 쪽)** — legacy가 참고할 상위 qualitative label이 없음; Spring이 더 상위 추상화를 가짐 |
| `paceTarget.fastSecondsPerKm`/`slowSecondsPerKm` | `segment.pace_min_sec_per_km`/`pace_max_sec_per_km` (또는 workout-level `pace_target.min/max_sec_per_km` fallback) | **DIRECT** (같은 단위: seconds/km, fast=min, slow=max로 이름만 다름) |
| HR `%LTHR` | `segment.hr_min`/`hr_max`(bpm, 절대값) + `Workout.lthr_bpm`(또는 `config/runner-hr-profile.json` fallback) → `Get-IntervalsHrPercentLthrTarget`이 %로 변환 | **TRANSFORM** — legacy는 절대 bpm을 입력받아 렌더 시점에 %LTHR로 변환(러너 LTHR을 알아야 변환 가능). Spring은 이미 `HeartRateTarget`에 `minPercentLthr/maxPercentLthr` **와** `minBpm/maxBpm`을 모두 갖고 있어 legacy보다 앞서 있음(legacy renderer 입력에 맞추려면 %만 뽑아서 넘기거나, bpm을 넘기고 legacy 스타일 변환을 다시 하거나 선택 가능 — Spring 값을 그대로 쓰는 쪽이 이중 변환 오차가 없어 더 낫다) |
| `treadmillTarget.minSpeedKph`/`maxSpeedKph` | 없음(legacy엔 structured speed 필드가 없음; `segment.notes`에 자유 텍스트로만 들어감, 예: `"12.0 km/h | 경사 0.5-1%"`) | **MISSING (legacy 쪽)** — Spring이 legacy보다 상위 개념(숫자 speed 필드)을 이미 갖고 있음. Renderer가 이 숫자를 legacy의 cue 텍스트 포맷으로 다시 "하향 변환"해야 함 |
| `treadmillTarget.minInclinePercent`/`maxInclinePercent` | 없음(위와 동일하게 자유 텍스트) | **MISSING (legacy 쪽)** — 위와 동일 |
| `primaryTargetType` | 없음(legacy는 workout-level `target_type`(`PACE`\|`HR`) 필드로 전체 워크아웃 단위 선택; Spring은 세그먼트 단위) | **TRANSFORM** — legacy는 워크아웃 전체에 대해 하나의 target_type만 고르고 그 모드가 아닌 쪽 값은 무시(diagnostic만 남김); Spring은 세그먼트마다 독립적으로 pace/HR 가용성을 판단함(현재 EASY/LONG/RECOVERY는 모든 세그먼트가 같은 profile을 참조하므로 실질적으로 같은 결과가 나오지만, 구조적으로 legacy보다 유연함) |
| REST 처리 | `Workout.type=REST` → structured Run을 만들지 않음("REST는 structured Run을 만들지 않는다") | **DIRECT (개념 일치)** — 둘 다 REST에 numeric target을 만들지 않음 |
| CROSS_TRAINING 처리 | 별도 렌더러(`cross-training-structured-workout.ps1`), cadence(rpm)를 structured target으로, HR을 %LTHR로 primary 사용 | **LEGACY_ONLY** — Spring 5B-2는 CROSS_TRAINING을 의도적으로 QUALITATIVE-only로만 처리(운동 종목 미지정이라 cycling 전용 threshold 적용 금지, 5B-2 work order 참고). Legacy의 cycling %LTHR/cadence 로직은 Spring에 대응 도메인이 아직 없음 |
| `profile`(`AthleteIntensityProfileResponse`) | `config/runner-hr-profile.json`(LTHR/max HR) + workout-level `lthr_bpm` override | **TRANSFORM/UPGRADE** — legacy는 JSON 파일 하나, Spring은 이미 DB-backed `AthleteIntensityProfile`(Phase 5B-2). Legacy엔 threshold **pace** 저장소가 아예 없었다(config 전체 검색 결과 없음, `DIAGNOSIS-EVENT-138398536` 40-47행) — Spring이 여기서도 legacy보다 앞서 있음 |
| step cue(`Get-GarminSafeStepCue`) | `segment.notes` (자유 텍스트) | **MISSING (Spring 쪽)** — Spring `TargetedWorkoutSegment`에는 cue/notes에 해당하는 필드가 없음. Renderer가 `treadmillTarget`의 숫자값으로부터 cue 텍스트를 **생성**해야 하며, legacy처럼 사람이 입력한 자유 텍스트에 의존하지 않아도 됨(오히려 더 안전 — "값을 지어내지 않는다"는 legacy 원칙을 그대로 지키면서 numeric 필드에서 결정론적으로 생성 가능) |
| `[RunningAI-Control] command_id=...` marker / idempotency | 동일 개념 없음(Spring 쪽은 아직 publish 자체가 없음) | **MISSING (Spring 쪽)** — Phase 5C-2/5C-3에서 반드시 이식해야 하는 개념 |

## I. Gaps

**Spring에 부족한 것** (Phase 5C에서 구현해야 함):
- Intervals.icu/Garmin 렌더링 도메인 자체가 없음(당연히, 이번이 5C-0이므로)
- Publish 대상 식별(idempotency marker), HTTP client, 인증 설정
- Cue 생성 로직(`Get-GarminSafeStepCue` 동등물) — 단, Spring은 숫자 필드에서 생성하므로 legacy보다 입력이 더 안전함
- CROSS_TRAINING을 실제로 사용하게 되면 그때는 cycling 전용 threshold 도메인이 필요(현재는 QUALITATIVE로 의도적으로 제한)

**Legacy에 부족한 것** (Spring이 이미 legacy보다 나은 부분):
- Threshold **pace** 저장소가 legacy엔 전혀 없었음(Spring `AthleteIntensityProfile`이 이미 해결)
- Treadmill speed/incline이 legacy에선 숫자 필드가 아니라 사람이 입력한 자유 텍스트(`segment.notes`)일 뿐이라
  값의 정확성이 사람에게 의존함; Spring은 이미 결정론적 숫자 필드(`TreadmillTarget`)를 갖고 있음
- HR target이 legacy에선 bpm→%LTHR 변환을 렌더 시점에 함(LTHR 소스가 workout override 또는 파일); Spring은
  이미 %와 bpm을 둘 다 미리 계산해 갖고 있어 렌더러가 단순 포맷팅만 하면 됨

## J. Recommended Architecture

**Option B를 권장한다** (지시서 30절의 두 옵션 중): `TargetedWorkoutPrescription` → `StructuredWorkout`(중간
도메인) → `IntervalsWorkoutRenderer` → `IntervalsWorkoutPublisher`, 필요 시 `GarminSafeCueFormatter` 분리.

근거: legacy 코드 전체에서 `hr=1s`, `Npct`, `Nkph`, Intervals 전용 텍스트 파싱 규칙(cue는 target **앞**에
와야 함, `%`는 파서가 target 토큰으로 오인하므로 항상 제거)이 전부 렌더러 내부에 존재했고, 그 규칙들이 실제
Garmin 표시 여부와 직결되는 매우 renderer-specific한 지식이다. 이런 문자열을 Spring domain(`TargetedWorkoutPrescription`,
`TreadmillTarget` 등)에 새어들어가게 하면, 향후 두 번째 renderer(다른 platform, 또는 Intervals.icu API가
structured JSON `workout_doc`을 직접 쓰는 방식으로 바뀔 경우)를 붙일 때 domain을 다시 건드려야 한다. Option B는
domain(이미 완성된 5B-2 산출물)을 그대로 유지하면서 renderer/adapter 계층만 추가한다.

```text
TargetedWorkoutPrescription (Spring domain, 변경 없음)
        ↓
StructuredWorkoutMapper       (TargetedWorkoutPrescription -> StructuredWorkout, 순수 변환)
        ↓
StructuredWorkout             (renderer-agnostic 중간 모델: step 목록 + 각 step의 measure/target/cue-source)
        ↓
IntervalsWorkoutRenderer       (StructuredWorkout -> Intervals.icu Workout Builder text)
        │    └─ GarminSafeCueFormatter (숫자 speed/incline -> "Nkph"/"N-Mpct" cue 텍스트, legacy Get-GarminSafeStepCue 규칙 이식)
        ↓
IntervalsWorkoutPublisher      (HTTP client + marker 기반 idempotency + readback 검증)
        ↓
Intervals.icu API
```

## K. Migration Strategy

```text
REUSE_AS_IS:
  - 없음 (PowerShell 코드를 Java 런타임에서 그대로 실행하는 방식은 채택하지 않음 — 지시서 34절 원칙과 일치:
    "최종 Spring architecture는 Java 구현을 선호한다")

PORT_LOGIC (규칙/알고리즘을 Java로 이식, 정확한 사양은 legacy 코드+테스트가 근거):
  - pace seconds/km -> "M:SS/km Pace" 변환 규칙(Convert-SecondsPerKmToIntervalsPace, Get-IntervalsPaceTarget)
  - bpm -> %LTHR 변환 규칙(Convert-BpmToPercentLthr, Get-IntervalsHrPercentLthrTarget, "hr=1s" suffix)
  - Get-GarminSafeStepCue의 정규화 규칙(경사/incline -> Incline, "N km/h" -> "Nkph", "%" 제거 -> "pct")
  - step 렌더링 순서(label -> cue -> measure -> target)와 "cue는 target 앞에" 배치 규칙
  - marker 기반 idempotency(command_id 임베딩, managed/unmanaged 판별, CREATE/UPDATE/CONFLICT/DUPLICATE 상태 머신)
  - readback 검증 철학("서버가 accept했다고 target이 실제로 있다는 보장이 아니다" — 최소한 구조/개수 검증)

REWRITE (개념은 유지하되 구현은 새로 작성):
  - HTTP client (PowerShell Invoke-RestMethod -> Spring RestClient, 기존 Garmin connector 패턴과 동일)
  - 인증(env var 읽기는 유사하되 Spring @ConfigurationProperties로)
  - Workout type registry(legacy 12종 -> Spring 6종 intent에 맞는 훨씬 단순한 매핑, 처음부터 다시 설계)

DROP:
  - Command Channel(Google Drive 큐), 외부 AI(ChatGPT/Claude/Codex) 승인 플로우 전체 — Spring 서버가 직접
    trigger하므로 이 중간 레이어 자체가 불필요
  - Apple Watch 관련 모든 delivery-mode 분기(TRAINING_CONTROL.md 자체가 "Apple Watch는 더 이상 사용하지
    않는다"고 명시) — Spring은 GARMIN 전용으로 시작
  - ntfy 승인 알림/HMAC 서명 콜백 체계 — Spring에는 승인 큐 개념이 없음(현재까지는 자동 생성만 고려)
  - cross-training(cycling) 렌더러 — Spring에 cross-training 도메인이 생기기 전까지는 이식 대상 아님

KEEP_LEGACY_ONLY:
  - `scripts/cross-training-structured-workout.ps1`과 그 config는 legacy에 그대로 남겨두고 Spring이
    cross-training을 지원하게 될 때 다시 조사
```

## L. Risks

```text
- Intervals.icu undocumented syntax: 이 repo의 모든 지식은 역공학(관찰+시행착오)에서 나온 것이며 공식
  문서 근거가 없다(HR-TARGET-PERCENT-LTHR-IMPLEMENTATION.md가 스스로 인정: WebSearch 접근 불가로 hr=1s를
  공식 문서로 재검증하지 못함). Java 이식 후에도 이 가정이 깨질 수 있음.
- Garmin cue limitations: 표시 길이 제한, 특수문자 제한이 코드로 명시되어 있지 않고 실기기 관찰에만 의존.
- Pace target이 Garmin에서 "목표 없음"으로 보이는 미해결 버그(event 138398536): 원인 미확정(4가지 가설
  중 어느 것도 이 repo 안에서 검증 불가). Java 이식본에서도 동일 증상이 재발할 수 있음.
- HR %LTHR + hr=1s: 렌더링 텍스트가 서버에 그대로 남는지까지만 검증됐고, 실제 Garmin 표시는 전혀 검증된
  적이 없음(ASSUMED). Phase 5C 구현 전 반드시 사용자가 Intervals.icu UI에서 직접 확인이 필요(레포가 제안한
  "안전한 다음 단계"이기도 함).
- Manual sync: Intervals.icu -> Garmin Connect -> 기기 동기화가 자동인지, 사용자가 Garmin Connect 앱을
  열어야 하는지는 이 repo에서 전혀 관측 불가(UNKNOWN, 아래 표 참고).
- Timezone: legacy는 명시적 타임존 변환이 없고 "PC가 Asia/Seoul"이라는 암묵적 가정에 의존. Spring은
  `athlete.timezone`을 명시적으로 다루므로 이식 시 이 가정을 그대로 가져가면 안 되고, athlete timezone
  기준으로 명시적 변환을 추가해야 한다.
- Duplicate workout: marker 기반 idempotency는 견고하지만, marker 없는 legacy event(과거 수동 생성분)와의
  충돌 처리(UNMANAGED로 보수적으로 처리, 자동 덮어쓰기 안 함)까지 그대로 이식해야 함 — 빠뜨리면 사용자의
  기존 수동 workout을 덮어쓸 위험.
- Secret handling: API key는 항상 환경변수에서만 읽고 어떤 파일/로그에도 남기지 않는다는 원칙이 legacy
  전체에 일관되게 적용돼 있음(TRAINING_CONTROL.md 보안 절) — Spring 이식 시 동일 수준으로 유지해야 함.
- Legacy dependency: 이번 조사에서 실제로 연 파일들은 전부 `C:\running-ai`(별도 machine-local 경로)에만
  있고 이 repo(`running-ai-github`)에서 실행/참조 불가능 — Phase 5C 구현은 반드시 "사양으로 옮겨적은 내용"에
  근거해야 하며 legacy 파일을 직접 import/실행하는 방식은 애초에 불가능하다(별개 프로젝트, 별개 언어).
```

## M. Proposed Phase 5C Breakdown

```text
Phase 5C-1: Structured Workout Domain Model
  - StructuredWorkout / StructuredWorkoutStep(renderer-agnostic 중간 모델)
  - StructuredWorkoutMapper: TargetedWorkoutPrescription -> StructuredWorkout (순수, DB/HTTP 없음)
  - 이번 조사에서 확인된 legacy golden-master fixture(D/F절 예시, 특히 5-step treadmill)를
    참고 fixture로 재구성(synthetic 값만 사용, 실제 athlete 값 배제)

Phase 5C-2: Intervals.icu Renderer
  - IntervalsWorkoutRenderer: pace/hr=1s/incline-cue 변환 규칙을 legacy와 동일하게 이식
  - GarminSafeCueFormatter: Get-GarminSafeStepCue 규칙 이식(숫자 speed/incline -> cue 텍스트)
  - 단위 테스트: 위 golden-master fixture로 byte-level 비교

Phase 5C-3: Intervals.icu Publisher
  - HTTP client(RestClient, 기존 GarminConnectorConfig 패턴)
  - marker 기반 idempotency(command_id 대체 개념 필요 - Spring엔 command_id가 없으므로 athlete+date+intent
    조합 등 자체 식별자 설계 필요)
  - readback 검증(최소: 서버가 되돌려준 step 수/타입이 기대와 일치하는지)
  - 이 단계까지는 dry-run/사용자 명시적 승인 없이는 절대 실제 publish 호출 금지 권장(5C-0에서 발견한
    미검증 리스크가 많으므로)

Phase 5C-4: Garmin End-to-End Validation
  - 실제 사용자 Garmin Forerunner 265로 최소 1개 워크아웃(pace-only, 그다음 HR %LTHR)을 라이브로 확인
  - 이 조사에서 ASSUMED로 남은 두 가지(pace target 표시 일관성, %LTHR+hr=1s 표시 여부)를 우선 검증
```

## Capability Matrix

| Capability | Legacy | Spring | Next Action |
|---|---:|---:|---|
| Exact duration | YES (분/거리 혼용) | YES (분 전용, 5B-1) | Spring 값 그대로 사용 가능 |
| Warm-up/main/cooldown | YES (5가지 structured_mapping) | YES (3구간 고정, 5B-1) | 5C에선 Spring 3구간만 지원하면 충분 |
| Pace target | YES (structured, 서버 확인) | YES (fast/slow seconds/km, 5B-2) | 렌더러가 "M:SS/km Pace" 텍스트로 포맷팅만 하면 됨 |
| %LTHR | YES (구현됨, **Garmin 미검증**) | YES (percent+bpm 모두 보유, 5B-2) | 5C-2에서 렌더링 이식, 5C-4에서 반드시 실기기 검증 |
| Treadmill speed | 아니오 (텍스트 cue만) | YES (숫자 필드, 5B-2) | Spring이 더 앞서 있음 — cue 생성만 새로 필요 |
| Incline | 아니오 (텍스트 cue만) | YES (숫자 필드, 5B-2) | 위와 동일 |
| Garmin-safe cue | YES (`Get-GarminSafeStepCue`) | 없음 | 5C-2에서 이식 필요(`GarminSafeCueFormatter`) |
| Intervals rendering | YES | 없음 | 5C-2 |
| Intervals publish | YES (marker idempotency) | 없음 | 5C-3 |
| Garmin delivery | **repo 밖, 불투명**(Intervals 계정의 자체 Garmin Connect 연동에 의존) | 없음 | 5C-4에서 실측만 가능, 코드로 보장 불가 |
| Idempotent update | YES (command_id marker) | 없음 | 5C-3, Spring 자체 식별자 설계 필요 |

## Evidence Level 요약

```text
CODE_CONFIRMED         : renderer 전체 로직, cue 규칙, idempotency 상태 머신, delivery mode 설정, auth 소스
TEST_CONFIRMED         : pace/HR 렌더링 형식(관련 테스트 파일 존재, 이번 조사는 실행하지 않고 정적 추적만),
                         5-step treadmill cue 배치
LIVE_PREVIOUSLY_CONFIRMED : pace target Intervals 서버 반영(event 138398536), segment/workout notes가
                         Garmin에 보이지 않았던 실패, absolute bpm이 target으로 인식되지 않는다는 사실
ASSUMED                : cue-before-target 수정 이후 실제 Garmin 표시 성공 여부, %LTHR+hr=1s의 Garmin
                         실제 표시 여부, cue 길이/문자 제한
UNKNOWN                : Intervals.icu -> Garmin Connect -> 기기 동기화가 자동인지 수동 개입이 필요한지
                         (repo가 관측할 수 없는 영역, exact_manual_checks로 사용자에게 위임됨)
```

## 완료 보고

### Legacy Flow
```text
entry:      Training Command JSON 또는 자동 planner workout_description
builder:    command-channel-worker.ps1 / select-recommendation-core.ps1 (워크아웃 object 조립)
renderer:   scripts/intervals-structured-workout.ps1 (ConvertTo-IntervalsStructuredWorkoutDescription)
publisher:  scripts/create-today-workout.ps1 (POST/PUT/GET/DELETE events, marker idempotency)
Intervals:  description text -> workout_doc(구조화 JSON), 이 repo가 관측 가능한 마지막 지점
Garmin:     Intervals 계정 자체 Garmin Connect 연동, repo 밖, 코드로 관측 불가
```

### Key Files
```text
file: scripts/intervals-structured-workout.ps1
responsibility: pace/%LTHR 변환, Get-GarminSafeStepCue, step 조립, description 조립, readback 검증

file: scripts/create-today-workout.ps1
responsibility: Intervals.icu HTTP publish, marker 기반 idempotency, 인증 헤더 구성

file: config/training-delivery-config.json
responsibility: active_delivery_mode=GARMIN, Apple Watch 경로 비활성화 플래그
```

### Pace
```text
syntax: "M:SS/km Pace"(단일) 또는 "M:SS-M:SS/km Pace"(범위)
rounding: 반올림된 정수 초
Garmin verified: PARTIAL — Intervals 서버 반영은 확인(event 138398536), 실제 기기 표시는 실패 사례가
                 문서화돼 있고 원인 미확정
```

### HR / LTHR
```text
syntax: "P% LTHR hr=1s"(단일) 또는 "P-P% LTHR hr=1s"(범위); absolute bpm은 구조화 target 아님(참고 텍스트만)
hr=1s: Garmin Instant HR 표시를 요청하는 modifier(주석 기반, 공식 문서 재검증 안 됨)
Garmin verified: NO — 서버 readback(텍스트 보존)까지만 확인, 실제 기기 표시는 미검증(ASSUMED)
```

### Treadmill
```text
speed representation: structured target 아님, "Nkph" cue 텍스트만
incline representation: structured target 아님, "N-Mpct" 또는 "Npct" cue 텍스트만 ('%' 기호는 항상 제거)
cue: Get-GarminSafeStepCue가 step 줄에서 duration/target 토큰보다 앞에 배치해야 Garmin에 보임
Garmin verified: 배치 규칙 자체는 실패 관찰(이전 방식)에 근거해 만들어졌으나, 수정 후 성공 여부는 repo에
                 재확인 기록 없음(ASSUMED)
```

### Notes / Cue
```text
workout notes: Intervals엔 보이지만 Garmin에는 보이지 않음(LIVE_PREVIOUSLY_CONFIRMED)
segment.notes: 원본 그대로는(target 뒤 배치) Garmin에 보이지 않았음; cue로 가공해 target 앞에 배치하면
               보일 것으로 기대되나 사후 확인 문서 없음
step cue: 위 "Cue" 항목과 동일 메커니즘
```

### Publish
```text
endpoint: https://intervals.icu/api/v1/athlete/0/events (+ /{id})
create/update: marker(command_id) 기반 CREATE/UPSERT/REPLACE/CANCEL, managed/unmanaged 구분
idempotency: 있음, command_id 재사용 시 DUPLICATE로 안전 처리(재작성 없음)
timezone: 명시적 변환 없음, PC 로컬 wall-clock 날짜(Asia/Seoul 가정, 비명시적)
auth source: 환경변수 INTERVALS_ICU_API_KEY, config/log에 저장 안 함
```

### Garmin Delivery
```text
path: RunningAI(legacy) -> Intervals.icu -> (repo 밖, 불투명) -> Garmin Connect -> Garmin 265
manual sync required: UNKNOWN (repo 코드로 관측/제어 불가, DIAGNOSIS 문서가 사용자의 수동 확인을 요청함)
direct Garmin upload: 없음 — 이 repo엔 Garmin API 호출 코드가 전혀 없음(검색 결과 전무, DIAGNOSIS 문서 확인)
```

### Spring Mapping
```text
direct: paceTarget.fastSecondsPerKm/slowSecondsPerKm, REST 처리
transform: intent(6종<->12종), segment 구조(3구간<->5가지 매핑), HR(%와 bpm 동시 보유<->렌더시점 변환),
           target_type(세그먼트별<->워크아웃 전체)
missing (Spring 쪽): cue/idempotency marker/HTTP publisher — 전부 Phase 5C 구현 대상
legacy-only: cross-training cycling 렌더러, Command Channel 승인 큐, Apple Watch delivery mode,
             ntfy 승인 콜백
```

### Recommended Architecture
```text
TargetedWorkoutPrescription
→ StructuredWorkoutMapper
→ StructuredWorkout
→ IntervalsWorkoutRenderer (+ GarminSafeCueFormatter)
→ IntervalsWorkoutPublisher
→ Intervals.icu API
```

### Reuse Decisions
```text
REUSE_AS_IS: 없음
PORT_LOGIC: pace/HR 변환 공식, cue 정규화 규칙, step 렌더링 순서, idempotency 상태 머신, readback 검증 철학
REWRITE: HTTP client, 인증 설정, workout type registry
DROP: Command Channel, Apple Watch delivery mode, ntfy 승인 콜백, cross-training 렌더러(당분간)
```

### Risks
```text
Intervals undocumented syntax(공식 문서 미검증), Garmin pace target 표시 불일치(미해결 버그),
%LTHR+hr=1s 미검증, Garmin 전달 경로 전체가 repo 밖 불투명, timezone 암묵적 가정, marker 없는
legacy event와의 충돌 처리, secret은 항상 env var로만
```

### Tests / Evidence
```text
static inspection: 완료 (renderer/publisher/config/docs 전체 read-only 조사)
tests executed: 없음 (모든 legacy 테스트는 정적으로만 추적; 일부는 레포 자체가 이전 세션에서도
                실제 실행이 차단됐다고 기록함 — HR-TARGET-PERCENT-LTHR-IMPLEMENTATION.md)
live calls: 없음 (Intervals.icu/Garmin 어떤 호출도 실행하지 않음)
```

### Git
```text
branch: main
commit: (아래 커밋 참고)
push: 완료
```

### Recommended Phase 5C-1
```text
scope: StructuredWorkout/StructuredWorkoutStep 중간 도메인 모델 + StructuredWorkoutMapper만 구현
       (Intervals 렌더링/publish는 5C-2/5C-3으로 분리, 자동 시작하지 않음)
```
