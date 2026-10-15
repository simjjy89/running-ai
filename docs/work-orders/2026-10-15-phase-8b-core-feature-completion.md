# Phase 8B — 핵심 기능 완성 (개발 환경 통합 개발)

- 날짜: 2026-10-15
- 브랜치: `phase-8b-core-completion` (base `b1feda7`, worktree `main-3`)
- 상태: **개발 완료 / 운영 미반영**
- 운영 영향: **없음.** 운영 배포 0건, 운영 DB 쓰기 0건, 외부 쓰기 0건, 스케줄러 활성화 0건,
  OAuth 권한 변경 0건, push/merge 0건, 신규 유료 서비스 0건.

기존 미커밋 변경(`tools/mcp-worker/src/index.js`, `wrangler.jsonc` — 7F-3B-3 진단 코드,
이미 운영 배포됨)과 미추적 워크오더 4건은 **건드리지 않았다.** 브랜치 전환으로 그대로 보존되어 있다.

---

## 0. 테스트 실측

| 스위트 | 명령 | 기준선 | 결과 |
| --- | --- | --- | --- |
| Spring (H2) | `gradlew clean test` | 1472 | **1589 PASS**, 실패 0, 에러 0, skip 0 |
| Spring (PostgreSQL 17) | 동일 + `SPRING_APPLICATION_JSON` 오버라이드, `running_ai_8b` 일회용 DB | — | **1589 PASS**, 실패 0, 에러 0, skip 0 (BUILD SUCCESSFUL 7m 5s) |
| garmin-connector | `pytest` | 140 | **154 passed** |
| external-relay | `node --test test/*.test.js` | 14 | **31 passed** |
| mcp-gateway | `npm test` | 46 | **46/46** |
| mcp-worker | `npm run test:all` | 34 + 49 | **34/34 + 49/49** |

신규 테스트 **+117건 (Spring)**, +14 (connector), +17 (relay). 비활성화/삭제한 테스트 **0건**.

---

## 1. Workstream A — Garmin 동기화 안정화

### 문제

Phase 8A가 `garmin_sync_attempt`에 실패를 기록하게 만들었지만 **그 값을 읽는 코드가 없었다.**
고정 지연 1시간이므로, Garmin 세션이 거부되기 시작하면 **하루 24회 인증 실패를 무기한 반복**한다.

### 구현

**`GarminSyncCircuitBreaker`** — 원장을 읽어 자동 동기화 가능 여부를 판정한다.

**가장 중요한 설계 결정: 차단 대상은 스케줄러뿐이다.** 수동 `POST /api/v1/garmin/sync`는
절대 차단하지 않는다. 이유는 세 가지이며 모두 대칭적이다.

1. 사람이 동기화를 요청하는 행위 자체가 차단기가 기다리던 개입이다. 먼저 플래그를 지우게 하는 것은
   같은 요청 앞에 단계를 하나 더 두는 것일 뿐이다.
2. 자격증명이 고쳐졌음을 증명하는 경로가 수동 경로다. 그리고 **완주한 시도가 연속 실패를 0으로
   되돌리므로, 그것이 곧 AUTH 차단 해제다** — 손으로 지울 플래그가 없다.
3. Garmin 계정을 위험에 빠뜨리는 것은 자동 루프이지 의도적인 단건 요청이 아니다.

따라서 이 차단기는 **성공했을 동기화를 막는 것이 구조적으로 불가능하다.** 최악의 경우 자동 시도가
지연될 뿐이고, 증분 동기화는 7일 overlap으로 다음 실행이 같은 구간을 다시 읽으므로 데이터 손실이 없다.

### 실패 종류별 정책 — 하나의 숫자로 합치지 않는다

| 종류 | 코드 | 임계값 | 쿨다운 | 근거 |
| --- | --- | --- | --- | --- |
| `AUTH` | `GARMIN_AUTH_REQUIRED`, `GARMIN_FORBIDDEN` | 3 | **없음(무기한)** | 401/403은 기다려도 낫지 않는다. 사람이 커넥터 세션을 갱신해야 하고, 완주한 시도만이 해제한다 |
| `RATE_LIMIT` | `GARMIN_RATE_LIMITED` | 1 | 6h | 429는 "속도를 줄여라"다. 첫 번째에 물러선다 |
| `TRANSIENT` | `GARMIN_UNAVAILABLE`, `GARMIN_UPSTREAM_ERROR`, `INCREMENTAL_WINDOW_INCOMPLETE`, `UNEXPECTED_*` | 8 | 2h | 커넥터 재시작이나 하룻밤의 불안정이 수집을 멈추게 해서는 안 된다 |
| PARTIAL 정체 | — | 6 | 12h | 아래 참조 |
| 요청량 바닥 | — | — | `minimum-interval` 15m | 스케줄 설정 오류가 요청 폭주가 되지 못하게 하는 상한 |

`GarminFailureClass`는 **코드만** 본다 — 메시지를 보지 않으므로 Garmin이 돌려준 텍스트가 판정에
영향을 줄 경로가 없다. 모르는 코드는 예외가 아니라 `UNKNOWN`(= TRANSIENT 취급)이다: 원장은 돌고 있는
버전이 쓰고 나중의 버전이 읽으므로, 낯선 문자열에 터지는 차단기가 더 나쁘다.

### V30 — 원장이 표현하지 못하던 두 상태

**`STARTED`.** 결과는 실행이 끝난 뒤에만 기록되었으므로, **JVM이 중간에 죽으면(워치독 재시작, 절전,
정전) 그 시도는 DB에 전혀 남지 않고 원장은 계속 그 이전 실행을 설명했다.** 이제 커넥터 호출 전에
`STARTED`를 쓰고 실제 결과로 덮어쓴다. 재기동 시 남아 있는 `STARTED` 행이 곧 중단의 증거이며,
`GarminSyncAttemptReconciler`가 `INTERRUPTED`로 전환한다.

**`INTERRUPTED`는 실패가 아니다.** 이 PC가 죽은 것이지 Garmin이 거부한 것이 아니다. 실패로 세면
**워치독 재시작 한 번 한 번이 수집을 멈추는 쪽으로 가는 걸음**이 된다. 따라서 어떤 streak도 건드리지
않는다.

**`consecutive_partial_count`.** 기존 `consecutive_failure_count`의 의미는 **그대로 두었다**(FAILED만
센다). PARTIAL은 진짜로 실패가 아니다 — Garmin이 답했고 매핑 가능한 항목은 저장되었다. 그러나
체크포인트를 전진시키지 않으므로, 반복되면 **같은 구간을 다시 읽고 같은 매핑 불가 항목에 다시 부딪히는
일이 영원히 계속되며 실패 카운터는 0에 머문다.** 둘을 따로 세는 것이 두 사실을 동시에 말하는 유일한
방법이고, 한 컬럼에 두 의미를 싣지 않는다.

### 동시 실행 / 백필 충돌

활동 동기화와 회복 동기화는 **서로 다른 락**을 가진다 — 서로를 손상시킬 수 없으므로 그 자체는 옳다.
그러나 시간당 틱이 28일 회복 백필(140회 순차 호출) 한가운데에 끼어들 수 있었다. `GarminActivityRegistry`는
**조언적(advisory) 레지스트리**다: 기존 락을 대체하지 않고, 스케줄러가 "Garmin이 이미 바쁘다"를 볼 수
있게만 한다. 틀려도 비용은 **자동 틱 1회 생략**이며 데이터에는 절대 영향이 없다. 90일 과거 백필은
스케줄러가 켜져 있으면 애초에 기동을 거부하므로 역방향 충돌이 성립하지 않는다.

### 검증

- `GarminSyncCircuitBreakerTest` — 원장이 가질 수 있는 모든 상태에 대한 순수 정책 테스트.
  **규칙마다 "작동한다"와 "한 단계 일찍 작동하지 않는다"를 쌍으로 고정**했다. 비대칭적이기 때문이다:
  차단 실패는 Garmin 요청 몇 건을 낭비하지만, **잘못된 차단은 수집을 멈춘다.**
- `GarminSyncAttemptLifecycleTest` — 실제 스키마 위에서 STARTED → INTERRUPTED, PARTIAL streak,
  그리고 **"원장이 최악의 상태여도 수동 경로는 막히지 않는다"**.
- `GarminSyncSchedulerTest` — 차단된 틱이 `runSync()`를 **호출조차 하지 않음**을 검증.

**운영 스케줄러는 켜지 않았다.** 기본값은 종전과 같이 OFF이며, 차단기 자체는
`RUNNING_AI_GARMIN_CIRCUIT_BREAKER_ENABLED=false`로 8B 이전 동작을 정확히 되돌릴 수 있다.

---

## 2. Workstream B — API 예외 처리

8A-1에서 실측된 결함 그대로 수정했다.

| 요청 | 이전 | 현재 |
| --- | --- | --- |
| 미지원 HTTP 메서드 | **500** + 전체 스택 트레이스 | **405** + `Allow` 헤더 + WARN 1줄 |
| 필수 파라미터 누락 | **500** + 전체 스택 트레이스 | **400 `INVALID_REQUEST`** + WARN 1줄 |
| 존재하지 않는 경로 | 404 | **404 (불변, 테스트로 고정)** |

`ResponseEntityExceptionHandler` 상속은 쓰지 않았다 — 이미 매핑된 모든 예외의 본문을
`{code,message,timestamp}`에서 `ProblemDetail`로 바꿔 기존 계약을 깨뜨린다. 핸들러 2개만 추가했다.

**부수 효과가 본질이었다.** 포괄 핸들러의 `log.error(ex)`가 프로브 4건만으로 `spring.out.log`에
ERROR 4건·200줄을 쌓았고, **워치독이 그 로그를 읽는다.** 잘못된 호출 몇 번이 운영 오류 신호를 희석하던
문제가 함께 사라진다.

404와 405가 섞이지 않음을 회귀 테스트로 고정했다: 모르는 URL은 404, 아는 URL의 틀린 메서드는 405.
`WorkoutPublishApiTest`의 거짓이 된 주석도 정정했다.

---

## 3. Workstream C — 게시 실패 관측 가능성 (V29)

`workout_draft_publication`은 **성공만** 저장한다. 의도된 설계다: 실패 행을 쓰면 재시도를 막거나,
Intervals.icu에 워크아웃이 있다고 거짓말하게 된다. **이 결정은 바꾸지 않았다.**

대가는 게시가 실제로 돌기 시작한 뒤에야 드러났다 — **실패는 그 요청의 HTTP 응답에만 존재했고,
응답이 사라지면 사실도 사라졌다.** "이 드래프트가 게시에 실패한 적이 있는가, 몇 번, 무엇에 대해?"는
외부 쓰기가 잘못됐을 때 가장 먼저 묻는 질문인데 DB로는 답할 수 없었다.

`V29__create_workout_draft_publish_attempt.sql` — **append-only, 시도당 1행**(V10은 드래프트당 1행).

| outcome | 의미 |
| --- | --- |
| `PUBLISHED` | Intervals.icu에 기록되고 readback 검증됨 |
| `SKIPPED_REST_DAY` | 승인된 휴식일 — 설계상 아무것도 보내지 않음 |
| `ALREADY_PUBLISHED` | 저장된 결과 반환, 외부 호출 0 |
| `REFUSED` | RunningAI가 막음(부상 게이트, 게시 불가 드래프트). 전송 없음 |
| `FAILED` | 외부 게시가 예외를 던짐 |

**두 불리언은 서로 다른 질문이므로 분리했다:**
- `external_write_attempted` — Intervals.icu를 바꿨을 **가능성**이 있는 지점까지 갔는가?
- `remote_state_certain` — 지금 Intervals.icu가 무엇을 들고 있는지 **아는가**?

두 번째가 `false`인 경우(타임아웃, 연결 끊김)가 위험한 상태이며, **추측하지 않고 기록한다.**

### 중복 게시 위험에 대하여 — 이미 올바르게 구현되어 있었다

작업 지시의 "불확실한 상태에서 무조건 재 POST 금지"는 `IntervalsWorkoutPublisher.createWithoutBlindRetry`가
Phase 5C-3부터 이미 지키고 있다: 결과 불명 시 POST를 반복하지 않고 **마커로 날짜를 다시 조회해 서버가
실제로 가진 것을 채택**한다. 따라서 V29는 **재시도를 유도하지 않는다** — 읽는 코드가 publish 경로에
하나도 없다. 그 위에 재시도를 얹으면 publication 행과 Intervals 마커(중복을 실제로 막는 두 가지) 옆에
세 번째 진실 공급원이 생긴다.

**"시도가 아닌 것"도 의도적이다:** 스위치 OFF, 동시 요청 거부, 미승인 드래프트는 기록하지 않는다.
승인된 드래프트에 도달한 적이 없으므로 그것을 게시하려는 시도가 아니다 — Garmin 수집 원장과 같은 선이다.

**저장 내용은 코드와 숫자뿐이다.** 실패 코드는 예외의 **타입과 reason**에서만 만들고 메시지는 쓰지
않는다. 메시지에 API 키와 워크아웃 텍스트를 넣은 예외로 테스트가 이를 고정한다.

조회: `GET /api/v1/workout-drafts/{id}/publish-attempts`.

---

## 4. Workstream D — AI Draft → Watchface

### 문제

릴레이는 Intervals.icu에 캘린더 이벤트를 묻고 REST 여부는 레거시 `C:\running-ai\state\today_plan.json`에서
읽었다. **RunningAI로 가는 경로가 하나도 없었다.** 결과:

- 승인된 AI 드래프트는 **게시된 뒤에야** 시계에 도달했다.
- **휴식일은 영영 도달하지 못했다.** 휴식은 설계상 게시되지 않으므로 캘린더에서는
  "코치가 휴식을 처방함"과 "아무도 아무 계획을 세우지 않음"이 구별되지 않는다.
  즉 **코치가 휴식을 지시한 날 시계는 "계획 없음"을 보여주고 있었다.**

### 구현

**`GET /api/v1/today-workout`** — 승인(approval)을 보고한다. 게시가 아니라 승인이 선수의 결정이다.

**시계 와이어 계약 `{date,status,label}`은 한 글자도 바꾸지 않았다.**

**자유 텍스트는 이 계약을 넘지 않는다.** 소비자는 14글자 픽셀 폰트 디스플레이에 먹이는 릴레이이고,
코치 제목은 모델이 쓴 텍스트다. 그래서 구조(type, duration, repetitions)만 넘기고 **릴레이가 자기
라벨을 만든다.** 인터벌은 `INTRVL 6X`로 표기한다 — 드래프트는 시간 기반이라 거리를 갖지 않으므로
`6X800M`이라고 쓰면 800을 **날조**하는 것이다.

**버려지는 응답 2가지:** 요청한 날짜가 아닌 응답, 백엔드가 `stale`로 표시한 응답.
다른 날의 계획을 오늘 것으로 보여주는 것은 아무것도 안 보여주는 것보다 나쁘다.
`NO_PLAN`은 결정이 아니라 "RunningAI가 아직 아무것도 정하지 않음"이므로 하위 소스로 넘어간다.

**폴백은 제거하지 않았다.** 백엔드가 죽었거나 배포 중이거나 이 PC에서 돌지 않을 때도 시계는 동작해야
한다. RunningAI 실패는 기록 후 건너뛰며, **절대 시계가 보는 오류가 되지 않는다.** 재시도 없음, 1회 호출.
`"spring_base_url": ""`로 8B 이전 동작을 정확히 복원할 수 있다.

### 위치

엔드포인트는 `coach`가 아니라 `draftpublish`에 있다. `CoachArchitectureTest`가 coach/draft 소스의
`draftpublish` import를 **전면 금지**하기 때문이다 — 의도적으로 뭉툭한 가드이며, **워크아웃을 설계하는
경로가 그것을 보내는 경로로 가는 길을 얻지 못하게 한다.** 읽기 편의를 위해 완화하지 않았다.

### 미완료

**실기기 검증.** 별도 저장소 `C:\running-ai-watchface`는 이번 범위에서 건드리지 않았고, 기기 설치는
승인 없이 하지 않는다. 서버·릴레이 양쪽 구현과 31건의 테스트는 완료되어 있다.

---

## 5. Workstream E — Garmin 도달 검증

### 선행 조사 결과: **실현 가능**

`python-garminconnect 0.3.16`에 `get_scheduled_workouts(year, month)`가 존재한다
(calendar-service, `calendarItems[]`, `itemType == "workout"`). 읽기 전용 GET.

### 구현

- 커넥터 `GET /scheduled-workouts?year=&month=` — 원본 그대로 반환. **`month`는 1-based**이며
  0-indexing은 라이브러리가 한다. 커넥터가 또 빼면 안 된다.
- `GET /api/v1/workout-drafts/{id}/delivery-status` — 3계층 사실 보고.

### 설계의 대부분은 "모르는 것"에 들어갔다

| 값 | 의미 |
| --- | --- |
| `intervalsDelivery` | `NOT_PUBLISHED` / `REST_DAY_NOT_SENT` / `PUBLISHED_AND_VERIFIED` / `PUBLISHED_UNVERIFIED` |
| `garminDelivery` | `GARMIN_PRESENT` / `GARMIN_NOT_FOUND` / `GARMIN_LOOKUP_FAILED` / `GARMIN_NOT_CHECKED` |
| `deviceDelivery` | **영구히 `UNKNOWN`** |

**`GARMIN_NOT_FOUND`는 실패가 아니다.** 라이브러리가 명시하듯 calendar-service는 이미 캘린더에 커밋된
워크아웃만 반환하며, 아직 실행되지 않은 Intervals 푸시는 영영 실행되지 않을 푸시와 구별되지 않는다.
그래서 `minutesSincePublish`를 함께 주고 **판단은 사람이나 코치에게 남긴다.**

**`deviceDelivery`는 영구 `UNKNOWN`이다.** 시계가 실제로 동기화했는지 보고하는 API가 없다.
`GARMIN_PRESENT`를 "시계에 있다"로 오독하게 두는 대신 그 질문에 **명시적으로** 답한다.

조회 실패는 부재가 아니라 **자기 상태**이며, Intervals 쪽 답을 버리지 않는다.
미게시 드래프트와 휴식일은 **Garmin 요청을 0회** 쓴다 — 보낸 것이 없으므로 받았을 것도 없다.

### Garmin 쓰기 경로는 없고, 앞으로도 없다

같은 라이브러리에 `schedule_workout`, `unschedule_workout`, `upload_*_workout`,
`push_workout_to_device`가 있다. **커넥터 테스트가 모듈의 AST를 파싱해 그중 하나라도 나타나면 빌드를
깨뜨리고**, 노출된 모든 라우트가 GET임을 별도로 단언한다. (텍스트 grep이 아니라 AST인 이유: 이 모듈의
주석이 "그것들을 호출하지 않는다"고 말하기 위해 그 이름들을 정확히 적고 있다.)

### 한계

**페이로드 형태는 설치된 라이브러리 소스 기준(STATIC_SOURCE_CONFIRMED)이며 LIVE_VERIFIED가 아니다.**
실제 Garmin 계정에 단 한 번도 호출하지 않았다. 매퍼는 방어적으로 읽는다(없는 필드는 null, 이상한 형태는
빈 목록). 실계약 검증은 운영 승인 항목이다.

---

## 6. Workstream F — 분석 및 코치 컨텍스트

### F-1. 측정되지 않은 것을 코치에게 알린다

가져온 활동에 없는 지표를 컨텍스트가 **그냥 생략**하고 있었다. 생략된 필드는 코치에게 **나쁜 값으로
읽힌다** — 사실과 정반대다. 케이던스가 없는 세션은 "케이던스가 낮은 세션"처럼 보였지만, 실제로는
**임포터가 Intervals/Strava의 한쪽 다리 rpm을 Garmin의 spm으로 변환하기를 거부**해서 없는 것이다.

`MetricAvailability`(패키지 `activity`)가 소스별 구조적 부재를 진술한다:

| 소스 | 없는 것 | 이유 |
| --- | --- | --- |
| `INTERVALS_ICU` | 케이던스, 러닝 다이내믹스, 랩 구조, 인터벌 그룹, HR 존 | rpm 미변환 / 컬럼 없음 / splits 비어 있고 랩을 날조하지 않음 / 7-존 vs 5-존 |
| `STRAVA` | 케이던스, 러닝 다이내믹스, 인터벌 그룹 | rpm 미변환 / 컬럼 없음 / 재인코딩에 `intensity`·`wkt_step_index` 없음 |
| `GARMIN` | 없음 | — |

`dataCoverage.metricUnavailability`(이유는 한 번만, 영향 활동 수와 함께)와 활동별
`dataQuality.unavailableMetrics`(열거형만)로 노출된다. 스냅샷 64 KiB 상한을 고려한 분리다.

**프롬프트 규칙을 추가했다:** 부재한 지표에서 하락·약점·변화를 추론하지 말 것, 그 지표를 가진 활동과
가지지 않은 활동을 비교하지 말 것, 측정되지 않은 것의 개선을 요구하지 말 것, 그 지표가 결정을 바꿨을
상황이면 "알 수 없다"고 말할 것.

`MetricAvailability`는 `ExternalSource` 옆에 둔다 — 활동의 출처에 관한 사실이고, 코치 컨텍스트와
분석 리포트 둘 다 그것을 말해야 하기 때문이다. **소스만으로 판정**한다: 이 부재들은 임포트의 속성이고
한 번 결정되어 문서화되어 있으므로, 행에서 다시 유도하면 그 결정과 조용히 어긋날 수만 있다.

### F-2. `GET /api/v1/training-analysis-report`

보유 전 구간에 대한 서술적 리포트: 커버리지와 소스 구성, 등간격 구간별 볼륨, 표준 거리 근처 최속 기록,
전·후반 durability(속도 변화·HR 변화·decoupling), 러닝 다이내믹스 추세, 그리고 **한계**.

**DB 전용.** Garmin·Intervals·코치 호출 0회, 분석 트리거 0회. 분석이 없는 활동은
**"미분석"으로 센다** — 읽기에서 분석하면 GET이 비싸지고 비결정적이 된다.

**측정하고 판단하지 않는다**(이 패키지의 규칙). 신경 쓴 지점:

- **"최속 기록"은 그 거리 근처의 가장 빠른 활동 전체**이며 그렇게 라벨링된다. 검증된 PB가 아니다.
  진짜 best effort는 롤링 윈도우가 필요한데, 가져온 절반의 이력은 그것을 지탱할 샘플 스트림이 없다.
- **페이스로 비교한다.** 조금 더 긴 거리를 더 오래 달린 기록이 더 느린 기록이 되지 않는다.
- **가장 최근 기록을 최속 기록 옆에 함께 보고한다.** 오래된 빠른 기록이 현재 상태로 통하지 못하게.
- **한쪽 반에 데이터가 없는 지표는 "비교 불가"**이며, 변화 0이 아니다.
- **구간은 올림으로 나눈다.** 내림이면 끝에 짧은 잔여 구간이 생기고, 그것이 볼륨 감소처럼 읽힌다.
- 거리를 기록한 활동이 없는 구간은 `null`이지 `0`이 아니다.
- **응답에 rating/score/verdict 단어가 나타나면 테스트가 빌드를 깨뜨린다.**

### 하지 않은 것 — 그리고 그 이유

**결정론적 처방 경로의 QUALITY 워크아웃 구조는 구현하지 않았다.** 작업 지시 자체가
"결정론적 추천 엔진이 임의로 고강도 운동을 자동 선택하도록 변경하지 않는다"고 못 박고 있고,
`WorkoutPrescriptionPolicy`는 `QUALITY_PRESCRIPTION_NOT_SUPPORTED`로 **명시적으로 거부**한다.
한편 **인터벌·템포·구간별 목표·자연어 수정은 AI 드래프트 경로에 이미 전부 존재한다**(`WorkoutDraftSegment`의
`repetitions`/`recovery`, 구간별 pace·%LTHR·treadmill 목표, `POST .../revisions`). 즉 지시가 요구한
기능은 구현되어 있고, 비어 있는 것은 "결정론 엔진도 QUALITY를 처방하게 하는 것"뿐이며 그것은 같은
지시가 금지한 방향이다. **중복 개발을 피하고 금지 사항을 지키기 위해 의도적으로 남겨둔다.**

**1년 데이터는 만들지 않았다.** 보유 범위는 2026-06-02 ~ 현재(약 4.5개월)이며 그 이전은 API로도
아카이브로도 도달 불가다. 리포트는 **보유한 것만** 측정하고 보간하지 않는다.

---

## 7. PostgreSQL 통합 검증

일회용 DB `running_ai_8b`(도커 `running-ai-postgres`)를 새로 만들어 전체 스위트를 실행했다.
**운영 DB `running_ai`는 읽지도 쓰지도 않았다.**

- **전체 스위트 1589건 PASS** (실패 0, 에러 0, skip 0; `BUILD SUCCESSFUL in 7m 5s`)
- Flyway **V1..V30 전부 적용**, `flyway_schema_history` 30행 전부 `success=t`
- `SchemaMigrationTest`가 적용 목록을 명시적으로 고정 — 번호 누락/충돌 시 빌드 실패
- PostgreSQL에서 실행되었음의 증거: 스키마가 `jsonb` 컬럼으로 생성되었다
  (`activity_raw.payload`, `workout_draft.segments`, `activity_raw_payload.payload` 등).
  H2 프로파일은 같은 마이그레이션을 `json_type: JSON` 플레이스홀더로 돌리므로 `jsonb`가 나올 수 없다.
- V29 제약 3종(outcome 열거, 실패 코드 존재/부재 대칭, duration >= 0)과 인덱스
  `(draft_id, attempted_at DESC)`, V30의 새 CHECK 2종이 PostgreSQL에 실제로 생성됨을 `\d`로 확인했다.
- Hikari 풀은 2/0으로 제한했다(컨텍스트 캐시 × 기본 풀 10이면 PG `max_connections`를 소진한다 —
  main-pc 환경 기록 참조). 종료 시 연결은 전부 해제되었다(실행 중 80 → 종료 후 16).

일회용 DB `running_ai_8b`는 증거로 남겨 두었다. **운영 DB `running_ai`는 읽지도 쓰지도 않았다.**

---

## 8. 운영 반영 준비 (전부 **승인 필요**, 이번에 실행하지 않음)

### 배포 대상

| 항목 | 내용 |
| --- | --- |
| Spring jar | 브랜치 `phase-8b-core-completion` 7커밋 |
| Flyway | **V29, V30** — 둘 다 신규 테이블/컬럼만, 기존 데이터 변경 없음 |
| 설정 | 신규 스위치 `RUNNING_AI_GARMIN_CIRCUIT_BREAKER_ENABLED`(기본 true). **기존 스위치 변경 없음** |
| 커넥터 | `tools/garmin-connector` 재시작 필요(신규 엔드포인트) |
| 릴레이 | `tools/external-relay` 재시작 필요 + `config.json`에 `spring_base_url` |

### 마이그레이션 안전성

- V29는 새 테이블만 만든다. 기존 행 0건 영향.
- V30은 `garmin_sync_attempt`에 컬럼 1개 추가(DEFAULT 0)와 CHECK 2개 교체. **운영 원장은 athlete당
  1행**이므로 영향 범위가 극소다. 기존 outcome 값 3종은 새 CHECK에 모두 포함된다.
- 롤백: V29/V30은 되돌릴 필요가 없다(읽는 코드가 없으면 그냥 쓰이지 않는 테이블/컬럼). jar만 되돌리면 된다.

### 배포 후 실제 검증 항목

1. `GET /api/v1/garmin/sync/status` → `automaticSync` 필드가 보이고 `allowed=true`인지
2. `GET /api/v1/garmin/sync` → **405 + `Allow: POST`** (이전 500)
3. `GET /api/v1/intervals/fitness` (파라미터 없이) → **400** (이전 500)
4. `GET /api/v1/today-workout` → 오늘 승인 드래프트 유무와 일치하는지
5. 릴레이 `GET /today-workout` → Spring 경로로 답하는지(릴레이 로그 `TODAY_WORKOUT_SOURCE source=RUNNING_AI`)
6. `GET /api/v1/training-analysis-report` → 운영 48건 이력에 대해 응답하는지
7. **커넥터 `/scheduled-workouts` 실계약 1회 실측** — 현재 LIVE_VERIFIED 아님

---

## 9. 사용자 승인이 필요한 항목 (한곳에 모음)

| # | 항목 | 위험 | 비고 |
| --- | --- | --- | --- |
| 1 | 브랜치 push 및 canonical main 병합 | 낮음 | 7커밋, 테스트 전원 통과 |
| 2 | 운영 배포(jar + V29/V30 + 커넥터·릴레이 재시작) | 중간 | §8 체크리스트 |
| 3 | **커넥터 `/scheduled-workouts` 실계약 1회 호출** | 낮음 | 읽기 전용 GET 1회. 이것 없이는 E가 LIVE_VERIFIED가 아니다 |
| 4 | Garmin 활동 스케줄러 활성화 | 중간 | 8A-1 절차 + 이제 차단기가 붙어 있음. 수동 동기화 1회 성공이 선행 |
| 5 | 운영 회복 데이터 백필(≤28일) | 낮음 | 베이스라인 7일 충족용. 21행 → 7일 이상 |
| 6 | 운영 CREATE 분기 1회 실증 | **높음** | 실제 외부 쓰기. 이제 실패해도 V29에 남는다 |
| 7 | 워치페이스 실기기 검증 | 낮음 | 별도 저장소 접근 + 기기 설치 |
| 8 | `publish_approved_draft` MCP 개방 | **가장 높음** | 로드맵 P1-2. **이번 범위 밖, 권장하지 않음** |

---

## 10. 남은 위험 (위험도 순)

1. **Garmin 예정 워크아웃 계약이 실측되지 않았다 (E).** 형태가 다르면 `GARMIN_NOT_FOUND`가 항상
   반환될 수 있다 — 안전한 방향의 오류(없다고 말할 뿐 잘못된 긍정이 아니다)이지만, 승인 항목 #3 전까지
   이 엔드포인트의 Garmin 절반은 신뢰할 수 없다.
2. **릴레이 전환은 시계에 즉시 영향을 주는 유일한 경로다 (D).** 폴백과 날짜 검증으로 방어했지만,
   전환 전 병행 비교(Spring 응답 vs Intervals 응답)를 한 번 수행할 것을 권한다.
3. **회복 베이스라인이 여전히 미충족이다.** `garmin_recovery_daily` 21행 < 28일 창. 승인 항목 #5 전까지
   코치는 `INSUFFICIENT_DATA`를 받는다. 이번 작업으로 바뀌지 않았다.
4. **데이터 신선도.** Garmin 증분 동기화 스케줄러가 꺼져 있다. 차단기가 붙어 이제 켜는 것이 더 안전해졌지만
   켜지는 않았다(승인 항목 #4).
5. **운영 `injury_report` 중복 2행**(7F-3B-2B에서 원인 규명 완료). append-only 원장이므로 삭제 대상이
   아니고 게이트 판정에 영향 없음. 변경하지 않았다.
6. **`minimum-interval` 15분이 기존 1시간 고정 지연보다 짧다.** 즉 현재 설정에서는 실질적으로 작동하지
   않는 상한이다. 의도적이다 — 설정 오류에 대한 안전망이지 정상 동작의 제약이 아니다.

---

## 11. Git

브랜치 `phase-8b-core-completion`, 7커밋, **push 없음 / 병합 없음**.

```
c08b574 test: pin the whole lifecycle, and what it does at each handover when it fails
35029ce feat(analysis): tell the coach what was never measured, and report the history
77d712b feat(garmin): check whether a published workout reached Garmin, read-only
02698f2 feat(watchface): let the approved AI plan reach the watch, REST days included
a62582c feat(draftpublish): record publish attempts, so a failure stops disappearing
2f9d65d feat(garmin): stop the scheduler repeating a sync that is going nowhere
2fb0e06 fix(api): answer a wrong verb with 405 and a missing parameter with 400
```

커밋 전 점검: 자격증명·토큰·`.env`·실제 GPS·빌드 산출물·IDE 파일 **0건**.
기존 미커밋 변경(mcp-worker 2파일)과 미추적 워크오더 4건은 **손대지 않았다.**
