# 2026-10-01 — Phase 5C-3: IntervalsWorkoutPublisher 결과

| 항목 | 내용 |
|------|------|
| 날짜 | 2026-10-01 |
| 작업 | `RenderedIntervalsWorkout → IntervalsWorkoutPublisher → IntervalsWorkoutClient → Intervals.icu → readback` |
| 상태 | 완료 (live validation은 SKIPPED) |
| 커밋 | `feat: add Intervals workout publisher` |
| 지시서 원문 | [2026-10-01-phase-5c-3-intervals-workout-publisher.md](2026-10-01-phase-5c-3-intervals-workout-publisher.md) |
| 이전 작업 | 34e072d `feat: add Intervals workout renderer` (Phase 5C-2) |

## 0. 작업 전 상태

```text
git switch main / git pull --ff-only origin main: Already up to date
HEAD: 34e072d (지시서 기준 commit과 일치), working tree: clean
regression baseline: Java 346 passed
```

## 1. 참고한 contract (추측 없음)

legacy 소스는 이 PC에 없고 Phase 5C-0 조사 문서가 근거다. 거기서 확인된 것: endpoint `https://intervals.icu/api/v1/athlete/0/events`
(GET 목록·단건, POST, PUT `{id}`), HTTP Basic(`API_KEY:<key>`), `start_date_local="…T00:00:00"`, marker 기반
managed/unmanaged 구분(managed = 같은 날짜 WORKOUT 이벤트 중 description에 `[RunningAI-Control]`이 있는 것, 그 외 이벤트는 CONFLICT로
덮어쓰지 않음), 서버 readback 검증 철학. 조사 문서에 없는 세부(`type`, `name`, `external_id` 필드, 목록 query 파라미터,
athlete id `0`의 의미)는 공식 Intervals.icu API 문서(`/api/v1/docs`, 포럼 API 안내)에서 확인했다: 이벤트 필드
`category, start_date_local, type, name, description, external_id`, 목록 `oldest/newest/category`, "athlete id 0 = API key 소유자",
Basic auth user `API_KEY`. 조사 문서와 충돌하는 점은 없었다.

## 2. 구성과 책임

```text
RenderedIntervalsWorkout
  -> IntervalsWorkoutPublisher   publish 오케스트레이션, identity/marker, 비교, readback 검증 (renderer 로직 재계산 없음)
  -> IntervalsWorkoutClient      (interface, 1 call = 1 request, 재시도 없음)
       HttpIntervalsWorkoutClient  RestClient, Basic auth, JSON 매핑, HTTP 오류 분류
  -> Intervals.icu
```

`com.runningai.integration.intervals`: `IntervalsWorkoutPublisher`, `IntervalsWorkoutClient`(interface; `GarminActivitySource` 선례),
`HttpIntervalsWorkoutClient`, `IntervalsConfig`(별도 `intervalsRestClient` bean, 명시적 timeout), `IntervalsProperties`
(`running-ai.intervals.*`), `IntervalsException`(+`Reason`/`getCode()`), `IntervalsEvent`, `IntervalsEventDraft`, `IntervalsPublishOperation`
(`CREATED/UPDATED/NO_CHANGE`), `IntervalsPublishResult(operation, remoteEventId, verified, scheduledDate)`. Renderer, `training`, DB는 수정하지
않았다. 새 HTTP 라이브러리 없음. 두 번째 `RestClient` bean이 생겼지만 Garmin source는 bean 이름 매칭으로 그대로 주입된다(wiring 테스트).

## 3. 인증 / 설정

`running-ai.intervals`: `base-url`(기본 `https://intervals.icu`), `athlete-id`(기본 `0`, env `INTERVALS_ATHLETE_ID`), `api-key`(env
`INTERVALS_API_KEY`만, 기본 빈 값), `connect-timeout` 3s, `read-timeout` 15s (무한 timeout 없음). key가 없어도 앱은 기동하며 모든 client 호출이
요청을 보내기 **전에** `INTERVALS_NOT_CONFIGURED`로 실패한다. `IntervalsProperties.toString()`, `IntervalsEvent.toString()`, 예외 메시지는
key·응답 본문·workout text를 출력하지 않는다. `.env.example`에는 key 자리표시자만 있다. legacy 환경변수명 `INTERVALS_ICU_API_KEY`는 쓰지 않고
skill에 이미 예약돼 있던 `INTERVALS_API_KEY`를 사용했다(기존 convention 유지).

## 4. Logical identity / marker / legacy 호환

- identity = RunningAI 소유 + athlete(`athlete-id`) + 예약 날짜. 하루 workout은 하나(slot/type 개념은 현재 domain에 없음).
- **marker = 이벤트 `external_id`** = `runningai:workout:v1:<athleteId>:<yyyy-MM-dd>`. legacy는 description 안의 `[RunningAI-Control] command_id=…`를
  썼지만, description에 marker 줄을 넣으면 Intervals가 그 줄을 workout 문법으로 해석해 step/cue 순서에 영향을 줄 수 있고(실기기에서 cue 위치가
  결과를 바꾼다는 5C-0 발견), "description == 렌더 텍스트" 정확 비교와 readback 검증도 복잡해진다. 그래서 별도 필드를 택했다. 이 선택의
  위험은 §11 참고.
- **legacy 호환(integration layer에만 존재)**: `external_id`가 없고 description에 `[RunningAI-Control]`이 있는 이벤트는 RunningAI 소유로 인식해
  새 이벤트를 만들지 않고 **제자리 UPDATE**한다(새 marker 인수, event id 유지). 한 번 인수되면 이후는 NO_CHANGE. legacy command_id 의미는
  보존하지 않는다 — 한 날짜는 한 경로(legacy 또는 Spring)로만 publish해야 한다.
- 소유가 아닌 이벤트는 절대 수정하지 않는다. 같은 날짜에 소유 이벤트가 없고 타인 이벤트만 있으면 legacy와 같이 보수적으로
  `INTERVALS_UNMANAGED_WORKOUT_CONFLICT`(생성도 하지 않음). 소유 이벤트가 2개 이상(legacy+Spring 혼재 포함)이면 `INTERVALS_DUPLICATE_OWNED_WORKOUT`로
  아무 쓰기도 하지 않고 중단한다(자동 선택/삭제 없음).

## 5. 상태 머신

```text
list(date, category=WORKOUT, 1일 window)  ->  owned / foreign 분류 (다른 날짜 항목 방어적 제외)
 owned >= 2          -> DUPLICATE_OWNED_WORKOUT
 owned == 1, 동일    -> NO_CHANGE  (쓰기 없음; 방금 list한 상태로 verified=true)
 owned == 1, 다름    -> PUT 같은 id -> readback -> UPDATED
 owned == 0, foreign -> UNMANAGED_WORKOUT_CONFLICT
 owned == 0          -> POST -> readback -> CREATED
```

비교: `description`을 **정확 비교**하되 줄바꿈 표기(`\r\n`/`\r`→`\n`)와 끝의 줄바꿈만 정규화한다. 줄 내부 공백, 대소문자, 앞 공백은 그대로 비교해
실제 rendering 변경을 놓치지 않는다(테스트로 고정). 렌더 텍스트는 description에 **그대로** 전달한다. 빈 텍스트(REST 렌더 결과 포함)는
`INTERVALS_EMPTY_WORKOUT`으로 거부하고 어떤 요청도 보내지 않는다. 이벤트 본문: `category=WORKOUT`, `start_date_local=<date>T00:00:00`,
`type=Run`, `name=RunningAI workout`, `description`, `external_id`.

## 6. Timeout / Retry 정책

자동 재시도는 어느 요청에도 없다. 특히 POST 결과가 불확실한 경우(`TIMEOUT`, `CONNECTION_FAILED`, 5xx = `isOutcomeUnknown`) 같은 POST를 다시 보내지
않고 marker로 날짜를 재조회한다: 1개 발견 → 실제로 생성된 것이므로 채택 후 readback(`CREATED`, POST 1회), 0개 → 원래 오류를 그대로 던지고(다음
publish는 항상 조회부터 시작하므로 안전), 2개 → duplicate 중단, 재조회 자체가 실패 → 그 오류를 던지며 원래 create 실패는 suppressed로 보존.
4xx는 확정 실패라 재조회 없이 즉시 전파. PUT 실패도 재시도하지 않는다. 429는 자동 재시도하지 않고 호출자에게 전달.

## 7. HTTP 오류 분류

401 `AUTH_FAILED`, 403 `FORBIDDEN`, 429 `RATE_LIMITED`, 기타 4xx `CLIENT_ERROR`, 5xx `UPSTREAM_ERROR`, read/connect timeout `TIMEOUT`,
연결 실패 `CONNECTION_FAILED`, 2xx인데 형태가 다름 `INVALID_RESPONSE`, key 없음 `NOT_CONFIGURED`, 그리고 publisher 수준의 `DUPLICATE_OWNED_WORKOUT`,
`UNMANAGED_WORKOUT_CONFLICT`, `READBACK_MISMATCH`, `EMPTY_WORKOUT`. 코드는 `INTERVALS_<REASON>`. 이번 Phase에는 이 예외를 HTTP로 노출하는
controller가 없다. 로그는 INFO로 날짜·operation·verified만, 불확실한 create 복구 시 WARN으로 reason만 남긴다(Authorization, key, 본문, workout text 없음).

## 8. Readback 검증

CREATE/UPDATE 직후 `GET …/events/{id}`로 다시 읽어 **id, marker(`external_id`), 예약 날짜, workout text(정규화 비교)** 를 확인한다. 하나라도 다르면
`INTERVALS_READBACK_MISMATCH`(메시지에는 어긋난 필드 이름만, 이벤트 id는 `getRemoteEventId()`로 수동 점검용 제공)이며 성공으로 반환하지 않고
자동 재수정 루프도 없다. 이 단계는 `RunningAI→Intervals`의 SERVER 경계를 확정하기 위한 것으로, 이후 Garmin 쪽 문제는 Intervals→Garmin 구간으로 좁혀진다.
Intervals 서버에 target 텍스트가 보존되는 것은 Garmin 지원의 증거가 아니다.

## 9. Tests

```text
Java: clean test → 396 total, 396 passed, 0 failed   (기존 346 + 신규 50)
Python: 변경 없음, 실행 안 함
```

- `HttpIntervalsWorkoutClientTest`(20): Basic auth 헤더·1일 WORKOUT window URL·이벤트 매핑·null 필드, create POST 본문 필드, update PUT 대상 id, key 없음 → 요청 0건,
  401/403/429/400/404/422/500/502/503 분류와 요청 정확히 1회·응답 본문/키 미노출, timeout/connection 분류, 잘못된 성공 응답 3종, `isOutcomeUnknown` 범위, toString 비노출.
- `IntervalsWorkoutPublisherTest`(28, 상태를 가진 `FakeIntervalsWorkoutClient`): CREATE+readback, 동일 재publish → NO_CHANGE(쓰기 0, remote 1개), 변경 → 같은 id UPDATE(remote 1개 유지),
  날짜별 독립, 타인 이벤트 수정 안 함/생성도 안 함, 소유+타인 혼재 시 소유만 UPDATE, 다른 athlete marker는 비소유, 다른 날짜 무시, legacy 이벤트 제자리 인수 후 NO_CHANGE,
  legacy+Spring 혼재 → duplicate, 소유 2개 → 중단·쓰기 0, create timeout이 실제로 성공한 경우 채택(POST 1회), 적용 안 된 timeout은 1회 실패 후 다음 publish로 복구,
  5xx도 불확실 처리, 4xx는 재조회 없음, 복구 조회에서 2개 발견 → duplicate, 복구 조회 실패 시 원인 보존, update 실패 재시도 없음, readback 불일치 3종(텍스트·marker·날짜)과
  mismatch 후 재수정 없음, 줄바꿈·끝 줄바꿈 무시, 공백/대소문자/들여쓰기 차이는 UPDATE, 빈 workout 거부, 조회 오류 전파 시 쓰기 0, marker 형식, normalize 경계.
- `IntervalsWiringTest`(2): 기본값·credential 없음·Garmin source 주입 모호성 없음, key 없는 상태의 publish가 네트워크 전에 실패. 이 테스트는 개발 PC에 실제
  `INTERVALS_API_KEY`가 있어도 실제 캘린더에 쓰지 않도록 빈 key와 도달 불가능한 URL을 테스트 속성으로 고정했다.
- Publisher 테스트는 `RenderedIntervalsWorkout` fixture를 직접 사용하며 pace/%LTHR/cue 렌더링은 다시 검증하지 않는다(5C-2 책임).

## 10. Live Intervals validation

```text
attempted: NO
status:    SKIPPED — credential unavailable on this machine
```

`INTERVALS_API_KEY`(및 legacy 이름 `INTERVALS_ICU_API_KEY`)는 Process/User/Machine 어느 범위에도 설정돼 있지 않았다(값은 조회하지 않고 존재 여부만 확인).
credential을 저장소·이력에서 찾거나 새로 만들지 않았다. 또한 현재 프로젝트에는 사용자의 실제 캘린더와 분리된 test/safe-date convention이 없어, key가 있어도
안전한 live write 방법이 확인되기 전에는 쓰기 검증을 하지 않는 것이 맞다. first/second publish, readback, duplicate 없음은 live에서 **관찰되지 않았다**.

## 11. 알려진 위험 / 미검증 가정

- **`external_id` 동작은 live 미검증**: 공식 스키마(`EventEx`)에 있는 필드지만, 이 서버가 POST/PUT에서 저장하고 목록·단건 응답에 돌려주는지는 이번에 확인하지
  못했다. 돌려주지 않으면 첫 publish는 `READBACK_MISMATCH(marker)`로 안전하게 실패한다(이벤트는 남을 수 있음 → `getRemoteEventId()`). 그 뒤 같은 날짜의 재publish는
  소유 이벤트를 찾지 못하고 `UNMANAGED_WORKOUT_CONFLICT`로 막히므로 중복 생성은 일어나지 않는다. 5C-4 또는 첫 live validation에서 가장 먼저 확인할 것.
- `type=Run`·`name` 값은 공식 문서의 필수 필드 설명에 근거한 선택이며 legacy payload와 바이트 단위로 대조하지 못했다(legacy 소스 없음).
- 서버가 description을 줄바꿈·끝 공백 외에 다른 방식으로 정규화하면 NO_CHANGE 대신 UPDATE 또는 READBACK_MISMATCH가 날 수 있다. 과도한 정규화를 피하려고 의도적으로
  비교를 엄격하게 두었으며 실제 서버 관찰 후 최소한으로 조정한다.
- 같은 날짜에 사용자의 다른 WORKOUT 이벤트가 있으면 publish가 막힌다(legacy와 동일한 보수적 선택).

## 12. Database / Legacy / 파일

```text
migration: NO   schema change: NO   remote id persistence: NO (marker 기반 idempotency만)
legacy production files modified: NONE (이 PC에는 legacy 코드가 없음; Python/Garmin 코드도 무변경)
```

## 13. Verification status

```text
Intervals renderer:           UNIT_VERIFIED (5C-2)
Intervals publisher:          UNIT_VERIFIED   (live SKIPPED → SERVER_VERIFIED 아님)
Intervals server readback:    UNIT_VERIFIED   (fake/mock에서만; 실제 서버 관찰 없음)

Pace Garmin:                  UNRESOLVED (그대로)
%LTHR Garmin:                 ASSUMED (그대로)
Treadmill cue Garmin:         새 Spring cue는 기기 검증 필요 (그대로)
```

## 14. Remaining limitations

- Intervals → Garmin transport는 여전히 불투명하다.
- Garmin pace target은 UNRESOLVED, %LTHR Garmin은 ASSUMED, 새 Spring treadmill cue는 기기 검증이 필요하다.
- scheduler / 자동 일일 생성 / publisher를 호출하는 controller가 없다. 삭제·취소도 없다.
- legacy publishing path는 그대로 유지된다. cross-training renderer 이행은 하지 않았다.

## 15. Next Phase (자동 시작 안 함)

Phase 5C-4 — 실제 Intervals → Garmin Connect → Forerunner 265 기기 검증(PACE target, %LTHR target, treadmill speed/incline cue). 그 전에 §10/§11의 live
Intervals validation(특히 `external_id` 왕복)을 안전한 날짜로 한 번 수행하는 것을 권장한다.
