# WO-RUNNINGAI-PHASE-7B-1 — 분석 품질 검증 및 AI 맞춤 훈련 Draft 파일럿 (결과 보고)

지시서: `2026-10-12-phase-7b-1-analysis-quality-and-ai-coach-draft-pilot-instruction.md`

운영 서비스는 중지·재시작하지 않았다. 재부팅, Watchdog/Relay/Tailscale 설정 변경,
`origin/main` Push, 다른 Worktree·레거시 저장소 변경은 수행하지 않았다. 개발·문서는
`main-3`에서만 작업했다.

## STEP 0 — 최신 상태 재확인

- `main-3` HEAD: `24ff0b5`(지시서 기대값과 일치), Working Tree Clean(4개 미추적
  work-order 문서만 존재, 무관).
- PostgreSQL(`running-ai-postgres` 컨테이너) healthy, Spring `18080/actuator/health` 200.

## STEP A — 심박 및 분석 품질 검증 (실측, 해석 변경 없음)

### A-1. athlete_intensity_profile 실제 LTHR

`GET /api/v1/athlete/intensity-profile` → `lactateThresholdHeartRateBpm=176`,
`lactateThresholdPaceSecondsPerKm=290`. 이 값은 `AthleteIntensityProfile`
엔티티(`server/src/main/java/com/runningai/athlete/AthleteIntensityProfile.java`)에
**오직 `PUT /api/v1/athlete/intensity-profile`을 통해 사용자가 직접 입력한 값**이며,
Garmin 자동 수집 경로는 존재하지 않는다(Phase 5B-2 설계 그대로, 코드베이스 전체에
근거 운동 검사나 산출 로직 없음). **즉 176bpm이 실제 젖산역치인지, 추정값인지,
과거 특정 테스트에서 나온 값인지 RunningAI는 어떤 근거도 보유하고 있지 않다** —
이는 버그가 아니라 설계상 알려진 한계(Phase 5B-2 문서에 기록됨)다.

### A-2/A-3. Garmin Zone 1~5 기준값 vs RunningAI 분석 결과

`activity_zone` 원본(activity_id=7, `zone_type=HEART_RATE`)을 직접 조회:

| Zone | 하한(bpm) | 시간(초) | 비율 |
|---|---|---|---|
| 1 | 96 | 15.0 | 0.22% |
| 2 | 115 | 20.0 | 0.30% |
| 3 | 134 | 23.0 | 0.34% |
| 4 | 154 | 423.0 | 6.29% |
| 5 | 173 | 6241.2 | **92.84%** |

RunningAI의 `ZoneExposureCalculator`는 이 값을 **그대로 재배포(pass-through)**
할 뿐 자체 계산을 하지 않는다 — Garmin 기기/계정에 설정된 zone 경계를 그대로
가져온 것이다(`ExposureCalculators.kt`). 비율의 분모는 활동 시간이 아니라
"zone으로 보고된 시간의 합"이며(6,722.17초, 실제 활동시간 6,724초와 거의 일치),
이는 설계 의도대로 동작한 것이다.

하한값의 비율(96/115/134/154/173)을 역산하면 Garmin 표준 %MaxHR 5-zone 공식
(50/60/70/80/90%)과 정확히 일치하며, 이는 **Garmin 기기/계정에 설정된 최대심박
약 192bpm(173 ÷ 0.90)을 기준으로 한 자동 zone**임을 시사한다. RunningAI는 이
"최대심박 192" 설정값 자체를 저장하거나 검증하지 않는다(zone 하한만 저장).
실측 참고: 이번 레이스 중 샘플 최고 심박은 **191bpm**(`activity_sample` 최댓값
조회) — 설정된 최대심박 추정치(192)에 거의 근접했다.

### A-4. Zone 5가 92.84%인 이유

Zone 5 하한이 173bpm인데, 레이스 2km 이후 평균 심박이 거의 전 구간
175~182bpm(1km 랩별 평균, Phase 7A-1 STEP 3 표)이었다. 즉 **"Zone 5의 92.84%"는
RunningAI의 계산 오류가 아니라, Garmin이 설정한 최대심박 기준 zone 경계(173bpm)가
이 사용자의 레이스 평균 심박보다 낮게 잡혀 있어서 생기는 당연한 결과**다. 하프마라톤을
거의 전구간 LTHR 근처~이상으로 달렸다면 %MaxHR 기준 Zone 5는 원래 이렇게 높게
나온다 — "92.84%가 위험하다/정상이다"라는 판단 자체를 내리지 않는다(지시사항대로
GOOD/BAD 단정 금지).

### A-5. LTHR 이상 70.5%의 설정 의존성

`ThresholdExposureCalculator`는 `AthleteIntensityProfile`의 LTHR(176bpm)을 사용해
독립적으로 계산한다 — **Garmin zone 5(173bpm 경계)와는 완전히 다른, 별개의 입력값과
별개의 계산 경로**다. 두 경계가 173 vs 176으로 3bpm 차이밖에 나지 않아 92.84%와
70.5% 모두 거의 같은 구간을 가리키는 것처럼 보이지만, 이는 **우연한 근접**이지
같은 지표가 아니다. 70.5%(LTHR100, 4,738초/6,722.17초)는 "176bpm이 실제 역치"라는
가정이 맞을 때만 의미가 있다 — 그 LTHR 자체가 A-1에서 확인했듯 근거 미상의
사용자 입력값이므로, **70.5%라는 숫자의 산술은 정확하지만 그 해석(의학적
역치 초과)의 신뢰도는 LTHR 176bpm의 정확도에 전적으로 의존한다.**

### A-6. Speed-HR Decoupling +6.24% 산식 재검증

`SessionMetricsCalculator.decouplingPercent`: `(EF1-EF2)/EF1*100`,
`EF=평균속도/평균심박`. Phase 7A-1 실측값(전반 평균속도 3.2525 m/s, HR 176.54 /
후반 평균속도 3.0593 m/s, HR 177.10)으로 직접 재계산:

```
EF1 = 3.2525/176.54 = 0.018423
EF2 = 3.0593/177.10 = 0.017274
(EF1-EF2)/EF1*100 = 6.24%
```

코드의 공식·부호·분할 기준(elapsed-time 중앙값, 샘플 인덱스 아님) 모두 문서화된
그대로이며 **+6.24%는 산술적으로 정확하다.** 다만 이 지표는 RunningAI 자체 정의
지표로 의학적 임계값이 없으며, 코드에도 그렇게 명시되어 있다(`decouplingPercent`
주석).

### A-7. 초반 과속 / 후반 피로 / 무릎 통증 — 독립 설명 후보 구분

RunningAI가 보유한 데이터(속도·심박·케이던스·파워)만으로 셋 중 무엇이 원인인지는
구분할 수 없다는 것이 사실이다. 각각을 분리해서 보면:

- **초반 과속 가설**: 1km 랩 페이스 4:52 → 이후 7km까지 5:03~5:11로 즉시 안정화.
  순수하게 "초반에 너무 빨리 뛰었다"고 보기엔 1km 이후 7km간 페이스가 매우
  안정적이었다 — 초반 과속이 유일한 원인이라면 이 안정 구간이 설명되지 않는다.
- **후반 전신 피로 가설**: 14~20km 구간의 지속적 페이스 손실(+15~34초/km)이
  심박 저하 없이(오히려 유지/상승) 발생 — Speed-HR Decoupling(+6.24%)과
  방향이 일치하고, Garmin Recovery(HRV -29.8%, 수면 -32.7%, Body Battery -48.9%)
  라는 익일 객관적 증거와도 일치한다. **데이터상 가장 뒷받침이 강한 가설.**
- **무릎 통증 가설**: 케이던스가 14~20km 구간 177~179spm → 173~174spm으로 하락한
  사실은 관찰되나, RunningAI는 통증을 측정하지 않으므로 이 케이던스 하락이
  "통증 때문"인지 "전신 피로 때문"인지 "둘 다"인지 **데이터만으로는 구분 불가**다.
  이번 Phase에서 사용자가 보고한 통증은 "레이스 중 무릎 바깥쪽 통증"이므로
  통증이 페이스 저하에 기여했을 가능성을 배제할 수 없지만, 그 기여도는 추정하지
  않는다.

세 가설은 상호 배타적이지 않으며(특히 후반 피로 + 무릎 통증은 동시에 작용했을
가능성이 높음), RunningAI는 **원인을 단정하지 않고 관찰된 상관관계만 보고한다.**

### A-8. 마지막 240m와 21km 랩 분리 — 스퍼트 해석 수정

`activity_lap` 원본 재조회:

| 랩 | 거리 | 시간 | 환산 페이스 | 평균HR | 평균 케이던스 |
|---|---|---|---|---|---|
| 21(20~21km) | 1,000m | 306.4초 | **5:06/km** | 182 | 174.14 |
| 22(마지막 240m) | 240m | 85.4초 | **환산 5:56/km** | **188(최고)** | **171.08(최저)** |

Phase 7A-1 결과 보고서는 "21~22km 구간에서 HR이 182→188로 치솟으며 페이스를
일부 회복(막판 스퍼트)"라고 **두 랩을 하나의 "스퍼트" 서술로 묶었는데, 이는 부정확한
해석이다.** 실제로는:

- **랩 21(완전한 1km)**: 페이스가 직전 랩(20: 5:39)보다 33초/km 빨라지고 HR도
  177→182로 상승 — 이것만이 "속도를 실제로 끌어올린" 진짜 스퍼트 구간이다.
- **랩 22(마지막 240m, 부분 랩)**: 환산 페이스는 오히려 **레이스 평균(5:17)보다도
  느리다.** HR은 레이스 전체 최고치(188)를 찍었지만 케이던스는 레이스 전체
  최저치(171.08)다 — 속도·HR·케이던스가 함께 움직이지 않는 조합은, "더 빨리
  달렸다"가 아니라 **결승선 통과 직후 감속/정지 과정(HR의 생리적 관성 지연 +
  보행 전환)이 240m 부분 랩에 포함되었을 가능성이 더 높은 패턴**이다. Garmin의
  자동 1km 랩 분할 특성상 마지막 부분 랩은 "감속 구간"을 포함하기 쉽다.

**수정된 해석**: 진짜 막판 스퍼트는 20~21km 구간(랩21) 하나뿐이며, 마지막 240m는
스퍼트의 연장이 아니라 결승선 통과 후 감속 아티팩트일 가능성이 더 높다. 이 역시
단정이 아니라 데이터 패턴에 기반한 재해석이며, RunningAI는 "감속 중이었다"를
직접 측정하지 않으므로 가능성으로만 제시한다.

### STEP A 종합 결론

- 분석 엔진의 산식·코드 로직은 전부 재검증했고 **버그는 발견되지 않았다**(Zone
  pass-through, Decoupling 공식, 랩 분리 전부 문서화된 대로 동작).
- 가장 중요한 품질 리스크는 **코드가 아니라 입력 설정값**이다: LTHR 176bpm과
  Garmin 추정 최대심박 ~192bpm 둘 다 출처·검증 근거가 RunningAI 내부에 없다.
  두 값이 엇비슷해서(173 vs 176) Zone5와 LTHR100 비율이 둘 다 높게 나오지만,
  이는 서로 다른 두 설정이 우연히 근접한 결과이지 교차검증된 결과가 아니다.
  → STEP E 개선 과제로 분리.

## STEP B — 훈련 안전성 검증

지시서에 따라 사용자에게 현재(2026-10-12) 무릎 상태를 직접 질의했다(아래는
사용자의 실제 답변, 임의 가정 없음):

| 항목 | 답변 |
|---|---|
| 평지 보행 시 통증 | **약간 남아있음** |
| 계단 오르내릴 때 통증 | 없음 |
| 붓기/움직임 제한 | 없음 |
| 병원 진료 여부 | 아직 없음 |

지시서 STEP B 규칙: **"보행 시 통증이 남아 있으면 러닝·고강도 훈련을 생성하지
않는다."** 평지 보행 통증이 "약간 남아있음"으로 확인되었으므로 **이 규칙이
적용된다** — 러닝/고강도 훈련은 생성 대상에서 제외한다.

`readyForCoach=true`(Phase 7A-1에서 확인됨)는 데이터 신선도만을 의미하며 운동
안전성과는 무관함을 재확인했다 — RunningAI에는 통증 상태를 반영하는 자동 게이트가
없고(STEP E 과제), 이번 Phase에서는 **사람이 직접 확인한 통증 정보를
`painOrFatigueFeedback`을 통해 AI Coach에 명시적으로 전달**하는 방식으로 안전
조건을 수동 적용했다.

## STEP C — 기존 AI Coach 사용 (Draft 생성)

`GET /api/v1/coach/training-context?date=2026-10-12&version=V2` 미리보기로
TrainingContextV2가 하프마라톤 분석(activity_id=7)·회복 지표·Intervals
Fitness를 정확히 포함하는 것을 재확인한 뒤(응답 크기 10,405 bytes, SHA-256
기록됨), `POST /api/v1/workout-drafts`를 **딱 1회** 호출했다.

요청 바디(`painOrFatigueFeedback`에 STEP B에서 확인한 실제 통증 정보를 그대로 전달):

> "2026-10-11 하프마라톤 레이스 이후, 현재(2026-10-12) 평지 보행 시 무릎
> 바깥쪽에 약한 통증이 남아있음. 계단 오르내릴 때는 통증 없음. 붓기나 관절
> 움직임 제한은 없음. 아직 병원 진료는 받지 않았음. 전반적으로 다리가 무거운
> 피로감도 남아있음."

응답(요약, 전문은 `draftId=19`로 DB에 저장됨):

```
status=DRAFT, workoutType=REST, totalDurationMinutes=0, provider=CLAUDE, model=sonnet
rationale: "You raced a half marathon yesterday at very high intensity, and today
  you're reporting lingering lateral knee pain on flat walking plus heavy legs,
  alongside a wearable picture (poor sleep, low HRV, low Body Battery) that
  confirms significant fatigue. The responsible move is full rest today: no
  running until the knee pain is assessed..."
warnings: ["Lateral knee pain reported after a long race - monitor closely and
  see a doctor or physio if it persists or worsens over the next 48 hours,
  especially before resuming any running.", ...]
```

**APPROVE/PUBLISH는 호출하지 않았다**(draft 상태 그대로 `DRAFT`). `running-ai-coach.ps1`은
메인 PC 전용 레거시 스크립트로 이 worktree에 존재하지 않아 호출 대상이 아니다
(이번 Phase는 Spring API 직접 호출로 동일한 파이프라인 — `WorkoutDraftService` →
`TrainingContextV2Builder`/`CoachDataFreshnessEvaluator`(내부, 검증 통과) →
`ClaudeAiCoach` — 를 검증했다).

## STEP D — 훈련 Draft 평가

| 기준 | 평가 |
|---|---|
| 1. 최근 레이스 피로 반영 | ✅ loadAssessment가 TL 172, Zone5 93%, derivedForm -12를 명시적으로 인용 |
| 2. 통증 상태 무시 여부 | ✅ rationale 첫 문장부터 무릎 통증을 명시, warnings에도 재강조 |
| 3. 회복 상태-강도 일치 | ✅ HRV -30%/수면 3.4h/Body Battery -49% 전부 인용 후 REST 선택 — 일치 |
| 4. 운동시간/구간 구성 적절성 | ✅ REST이므로 0분·세그먼트 없음 — 적절 |
| 5. 페이스/HR 목표 근거 | 해당 없음(REST, 세그먼트 자체가 없음) |
| 6. 미제공 데이터 임의 가정 여부 | ✅ 통증의 의학적 원인을 추정하지 않고 "병원 진료 권고"로 처리 — 과도한 가정 없음 |
| 7. REST 선택 가능 여부 | ✅ 실제로 REST를 선택함(이전 Phase 7A-1 보류와 달리 이번엔 안전하게 **생성까지 완료**) |
| 8. 목표기록·최근활동 일관성 | 저장된 목표기록 자체가 시스템에 없음(Phase 7A-1 확인) — Draft도 목표를 언급하지 않아 일관됨(임의 날조 없음) |

**결론: 이번 Draft(`id=19`)는 승인 가능한 품질이다** — 다만 이번 Phase의 범위상
APPROVE는 수행하지 않았다(지시서 금지 사항). 결과적으로 "AI Coach가 통증 피드백을
무시하지 않고 적절히 REST를 처방할 수 있는가"라는 이번 Phase의 핵심 검증 질문에
**예(단일 사례 기준)**라는 답을 얻었다.

## STEP E — 다음 개발 과제 (우선순위순, 이번 Phase에서 구현하지 않음)

1. **지속적인 통증/부상 상태 저장 및 안전 게이트**: 현재는 매 Draft 요청마다
   사람이 `painOrFatigueFeedback`을 수동으로 입력해야 안전이 보장된다. 사람이
   깜빡하면 아무 게이트도 없이 러닝 Draft가 생성될 수 있다 — 가장 시급.
2. **심박존 및 LTHR 데이터 품질 검증**: LTHR(176, 사용자 입력)과 Garmin 추정
   최대심박(~192, 기기 설정)이 서로 다른 출처이면서 검증되지 않은 채 섞여
   쓰이고 있다. 둘 중 하나가 부정확하면 Zone5/LTHR100 같은 지표가 조용히
   왜곡된다.
3. **분석 결과의 근거 기반 해석**: 이번 Phase처럼 "스퍼트 vs 감속 아티팩트"
   같은 재해석을 매번 사람이 수작업으로 해야 한다 — 패턴(부분랩, 속도-HR-케이던스
   불일치)을 구조화해서 일관되게 플래그하는 보조 로직이 있으면 좋음.
4. **분석 결과에서 훈련 생성까지의 일관된 연결**: 이번엔 작동했지만, 통증
   피드백이 빠진 호출 경로(예: 자동화된 `running-ai-coach.ps1` 스케줄 실행)에서는
   #1의 게이트 부재가 바로 위험으로 이어진다.
5. **목표 대회·목표 기록 영속 관리**: 시스템에 전혀 없음(Phase 7A-1에서 이미 확인).

## STEP F — 사용자에게 제공할 결과

(본문은 이번 작업의 최종 채팅 응답에 한글로 정리하여 전달한다.)

## 지시사항 준수 확인

- 운영 서비스 중지·재시작: 없음.
- APPROVE/PUBLISH/Garmin 업로드: 수행하지 않음(Draft `id=19`는 `DRAFT` 상태로 유지).
- Watchdog/Relay/Tailscale 설정 변경, 재부팅: 수행하지 않음.
- 다른 Worktree·레거시 저장소 변경: 접근하지 않음.
- `origin/main` Push: 수행하지 않음(main-3 로컬 문서 기록만).
- 중복 호출·무제한 재시도·DB 초기화: 없음(`training-context` GET 1회,
  `workout-drafts` POST 1회).

작업 완료. 지시서에 따라 STOP한다.
