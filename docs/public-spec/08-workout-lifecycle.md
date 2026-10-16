# 08. 훈련 생성 · 승인 · 게시 · 수정 · 결과 분석 시퀀스

> 대상 독자: 전원. 이 장이 RunningAI의 **심장**이다.

### 쉽게 말하면

운동 하나가 태어나서 시계에 도착하고, 바뀌고, 측정되기까지는 **6단계**다.

```
① 초안 생성 → ② (선택) 수정 → ③ 승인 → ④ 미리보기 → ⑤ 게시 → ⑥ 달리고 나서 분석
                                                      ↘ ⑤' 게시 후 수정 (Phase 10)
```

**③ 승인 전에는 아무것도 밖으로 안 나간다.** 승인은 돌이킬 수 없다 — 승인된 초안은
**불변(immutable)** 이고, 바꾸려면 ①부터 새로 만들거나(미게시), ⑤' 수정 경로를 쓴다(게시 후).

---

## 1. 상태 기계

```mermaid
stateDiagram-v2
    [*] --> DRAFT : POST /workout-drafts
    DRAFT --> DRAFT : POST revisions — 새 version, 이전은 SUPERSEDED
    DRAFT --> SUPERSEDED : 더 새 버전이 생김
    DRAFT --> APPROVED : POST approve — V9 UNIQUE athlete+date
    APPROVED --> APPROVED : 수정 시도 → WORKOUT_DRAFT_APPROVED_IMMUTABLE
    APPROVED --> PUBLISHED : POST publish — V10 UNIQUE draft_id
    APPROVED --> SKIPPED_REST : REST 초안 — 외부 호출 0회
    PUBLISHED --> [*]

    state "게시 후 수정 (V31, 별도 생명주기)" as REV {
        [*] --> PROPOSED
        PROPOSED --> APPROVED_R : /approve
        APPROVED_R --> APPLIED : /apply
        PROPOSED --> SUPERSEDED_R : /abandon
        APPROVED_R --> SUPERSEDED_R : /abandon
        APPLIED --> [*]
    }
    PUBLISHED --> REV
```

> ⚠️ **용어 함정**: `POST /workout-drafts/{id}/revisions`는 **승인 전 초안의 버전 N+1**을
> 뜻하고, Phase 10의 "revision"은 **이미 게시된 워크아웃의 수정**이다. 그래서 Phase 10은
> `/{id}/revisions`를 재사용할 수 없었고 `/published-revisions`라는 별도 경로를 만들었다.

---

## 2. ① ~ ③ 생성 · 수정 · 승인 ✅ `SHIPPED`

```mermaid
sequenceDiagram
    autonumber
    actor U as 러너 (REST 또는 ChatGPT)
    participant WD as WorkoutDraftService
    participant GATE as WorkoutSafetyGateService
    participant CTX as CoachTrainingContextBuilder
    participant AI as ClaudeAiCoach
    participant VAL as WorkoutDraftValidator
    participant DB as workout_draft (V6/V8/V20)

    U->>WD: POST /api/v1/workout-drafts {date, constraints}
    WD->>GATE: 부상 게이트 조회
    alt 차단됨
        GATE-->>WD: REST only + constraintText
        Note right of GATE: 비-REST 결과는 WorkoutSafetyViolationException<br/>조용히 REST로 바꿔치기하지 않는다
    end
    WD->>CTX: 컨텍스트 구성 (DB만)
    CTX-->>WD: TrainingContext V1/V2 + injuryHistory
    WD->>AI: 직렬화된 컨텍스트 (크기 검사 통과분)
    AI-->>WD: 초안 JSON
    WD->>VAL: 하드 안전 검증
    VAL-->>WD: 수락 / 거부 (재작성 없음)
    WD->>DB: DRAFT 저장 + 컨텍스트 스냅샷(sha256)
    DB-->>U: 초안

    U->>WD: POST /{id}/revisions (수정 요청)
    WD->>GATE: 게이트 재조회
    WD->>AI: 재호출
    WD->>DB: version+1 저장, 이전 행 SUPERSEDED

    U->>WD: POST /{id}/approve
    WD->>GATE: 게이트 재조회 (승인도 워크아웃 생산 경로다)
    WD->>DB: workout_draft_approval (V9) — athlete+date 유니크
```

**승인의 불변식**

| 규칙 | 강제 방법 |
| --- | --- |
| 선수+날짜당 승인 1건 | `UNIQUE (athlete_id, workout_date)` |
| 승인된 초안은 불변 | 수정 시도 → `WORKOUT_DRAFT_APPROVED_IMMUTABLE` |
| 승인은 DB만 | 외부 호출 0회 |
| 게시 스위치가 꺼져 있어도 승인·미리보기는 동작 | 스위치는 **게시만** 막는다 |

**MCP 경유 추가 요건**: `confirmedByAthlete: true` + `acknowledgedVersion` echo
([06장 §3](06-chatgpt-mcp-oauth.md)).

---

## 3. ④ ~ ⑤ 미리보기와 게시 ✅ `SHIPPED`

```mermaid
sequenceDiagram
    autonumber
    actor U as 러너
    participant PS as ApprovedWorkoutDraftPublishService
    participant SW as PublishingModeGuard / 스위치
    participant GATE as 부상 게이트
    participant MAP as WorkoutDraftStructuredWorkoutMapper
    participant REN as IntervalsWorkoutRenderer
    participant PUB as IntervalsWorkoutPublisher
    participant IC as Intervals.icu
    participant LED as workout_draft_publish_attempt (V29)
    participant OK as workout_draft_publication (V10)

    U->>PS: GET /{id}/publish-preview  (읽기 전용)
    PS->>GATE: 게이트 확인
    PS->>MAP: 정확한 전송 매핑 (반복 전개, 총량 재검산)
    PS->>REN: 결정론적 텍스트 렌더
    REN-->>U: 미리보기 텍스트 + 게시 가능성 판정

    U->>PS: POST /{id}/publish
    PS->>SW: RUNNING_AI_DRAFT_PUBLISHING_ENABLED?
    PS->>PS: 초안별 단일 비행 lock
    alt 이미 게시됨
        PS-->>U: 저장된 결과 (외부 호출 0회)
    end
    PS->>GATE: 부상 게이트 (네트워크 호출 전)
    alt REST 초안
        PS->>OK: SKIPPED_REST_DAY (렌더러·게시자 호출 0회)
    else
        PS->>LED: 시도 기록 (append-only)
        PS->>PUB: publish(structuredWorkout, date)
        PUB->>IC: GET 이벤트 목록 (external_id 마커로 소유권 판단)
        alt 마커 없음
            PUB->>IC: POST (CREATE)
        else 텍스트 다름
            PUB->>IC: PUT (UPDATE, 같은 event id)
        else 텍스트 동일
            PUB-->>PS: NO_CHANGE (쓰기 0회)
        end
        PUB->>IC: GET readback 검증
        PS->>OK: PUBLISHED + event id + verified
    end
```

### 3.1 게시자의 불변식

| 규칙 | 이유 |
| --- | --- |
| **"re-list, never re-POST"** | 응답이 유실된 POST를 재시도하면 중복 이벤트가 생긴다. 대신 목록을 다시 읽어 실제 상태를 본다 |
| 소유권 마커 = `external_id` | 남의 이벤트를 절대 건드리지 않고, 동시에 멱등 키가 된다 |
| readback 검증 | 썼다고 믿지 않고 **다시 읽어** 확인한다 |
| 실패는 `workout_draft_publication`에 저장되지 않는다 | 그 테이블은 **성공 전용**. 실패 이력은 V29 원장에 |
| 저장소에서 Intervals에 쓰는 코드는 **단 하나** | `HttpIntervalsWorkoutClient` ← `IntervalsWorkoutPublisher` |

### 3.2 시도 원장 (V29)

| 컬럼 | 의미 |
| --- | --- |
| `external_write_attempted` | 외부 쓰기를 시도했는가 |
| `remote_state_certain` | **원격 상태를 확신하는가** — 타임아웃이면 **false** |

**두 컬럼이 별개인 이유**: 서로 다른 질문이다. "썼는지 모른다"는 "실패"가 아니다.
이 원장은 **아무 재시도도 유발하지 않는다.** 메시지가 아니라 **코드만** 저장한다.

조회: `GET /api/v1/workout-drafts/{id}/publish-attempts`

### 3.3 실측 상태

| 분기 | 상태 |
| --- | --- |
| UPDATE | ✅ 운영 3건 (event 140389091 · 140391756 · 140511724, 전부 verified=true) |
| CREATE | 🟢 dev DB에서 event 144442046 CREATED 성공. **운영 미실행** |
| REST | ✅ `SKIPPED_REST_DAY` |

---

## 4. ⑥ 게시물이 시계에 닿았는가 — 도달 확인

```
GET /api/v1/workout-drafts/{id}/delivery-status     (패키지 delivery)
```

| 판정 | 의미 |
| --- | --- |
| Intervals 측 | readback으로 **검증됨** |
| `GARMIN_NOT_FOUND` | **실패가 아니다.** Garmin 캘린더는 확정된 워크아웃만 돌려주고, Intervals→Garmin 푸시는 **Intervals.icu 자체 연동**이다 |
| `deviceDelivery` | **영구히 `UNKNOWN`** — 시계 도달은 어떤 API로도 증명 불가 |

계약 상태는 `STATIC_SOURCE_CONFIRMED` → Phase 8B 운영 릴리스에서 Garmin
`/scheduled-workouts`가 **live-verified** 되었다.

> `delivery` 패키지가 따로 있는 이유: `DraftPublishArchitectureTest`가
> `draftpublish → integration.garmin` 임포트를 금지한다.

---

## 5. ⑤' 게시 후 수정 (Phase 10) ✅ `SHIPPED`

### 5.1 왜 별도 생명주기인가

게시된 워크아웃을 바꾸려면 승인 이력을 건드려야 할 것 같지만, 그러면
`UNIQUE (athlete_id, workout_date)`(승인)와 `UNIQUE (draft_id)`(게시)와 **싸우게** 된다.
그래서 `published_workout_revision`(V31)은 **`workout_draft` / `workout_draft_approval` /
`workout_draft_publication` 중 아무것도 쓰지 않는다.** 그 셋은 **영원히 읽기 전용 입력**이다.

### 5.2 상태와 제약

```
PROPOSED → APPROVED → APPLIED        (+ SUPERSEDED: abandon 또는 대체)
```

**내용을 승인하는 것**과 **외부 쓰기를 인가하는 것**은 **두 개의 분리된 사람 결정**이다.

"게시물당 열린 수정은 최대 1건"은 nullable **`open_slot`**(열려 있으면 `publication_id`,
닫히면 NULL) 위의 **평범한 `UNIQUE` + `CHECK`** 로 강제된다.

> 🧩 **왜 PostgreSQL 부분 인덱스가 아닌가**: 테스트가 도는 H2로 이식되지 않기 때문이다.
> **로드베어링 규칙은 두 엔진에서 같은 메커니즘으로 강제되어야 한다.**

### 5.3 충돌 감지 — 쓰기 전에 거부

`base_text_sha256` = 정규화된 게시 텍스트의 SHA-256. **렌더링은 순수 함수이므로 다시 렌더해서
재도출했고, 그래서 백필이 필요 없었다.**

| 코드 | 상황 |
| --- | --- |
| `REVISION_REMOTE_EVENT_MISSING` | 대상 이벤트가 사라졌다 |
| `REVISION_REMOTE_EVENT_DUPLICATE` | 같은 마커가 둘 이상 |
| `REVISION_REMOTE_EVENT_REPLACED` | 다른 이벤트로 교체됐다 |
| `REVISION_REMOTE_EVENT_MODIFIED` | 외부에서 편집됐다 |

**네 가지 모두 어떤 쓰기도 일어나기 전에 거부한다.**

> 🔧 **Phase 10 감사에서 고친 것**: 충돌 검사가 **base뿐 아니라 제안된 텍스트도 수락**한다.
> 응답이 유실된 쓰기는 Intervals.icu에 **이 수정 자신의 출력**을 남기는데, 그것을 외부 편집으로
> 부르는 것은 틀렸을 뿐 아니라 **복구 불가능**했다 — 내용은 살아 있는데 원장은 적용된 적 없다고
> 말하는 상태. 수락해도 비용이 없고(덮어쓸 것이 없다), 게시자가 쓰기 없이 `NO_CHANGE`로 답한다.

> 🚪 **`abandon`은 풀리지 않는 충돌의 유일한 출구**: 게시물당 열린 수정이 1건이므로, 적용될 수
> 없는 수정은 **모든 후속 수정을 영구히 막는다.** `POST /{id}/abandon`이 `SUPERSEDED`로 닫는다 —
> 자동이 아니고, 외부 호출도 없고, 이미 종결된 수정에는 거부된다.

### 5.4 적용 순서 (순서 자체가 안전장치다)

```mermaid
sequenceDiagram
    autonumber
    actor U as 러너
    participant SVC as PublishedWorkoutRevisionApplyService
    participant PUB as IntervalsWorkoutPublisher
    participant IC as Intervals.icu

    U->>SVC: POST /published-workout-revisions/{id}/apply
    SVC->>SVC: ① 기능 스위치 RUNNING_AI_PUBLISHED_REVISION_ENABLED
    SVC->>SVC: ② 초안 게시 마스터 스위치
    SVC->>SVC: ③ 수정별 단일 비행
    alt 이미 적용됨
        SVC-->>U: 저장된 결과 (외부 호출 0회)
    end
    SVC->>SVC: ④ 부상 게이트 — 어떤 네트워크 호출보다 먼저
    SVC->>SVC: ⑤ 렌더 (순수)
    SVC->>IC: ⑥ 충돌 읽기 (inspect)
    SVC->>PUB: ⑦ publish — 이 Phase는 HTTP 코드를 추가하지 않았다
    PUB->>IC: GET 목록 + PUT
    SVC->>SVC: ⑧ 반환된 event id == 목표 id 단언
    Note right of SVC: CREATED는 성공한 수정으로 기록될 수 없다<br/>application 테이블 CHECK는 UPDATED / NO_CHANGE만 허용
    SVC->>SVC: ⑨ 기록 (_application 성공전용 / _attempt append-only)
```

**적용 비용 = GET 2회** — 이 서비스의 충돌 읽기 + 게시자 자신의 목록 조회. **중복이 아니다**:
게시자는 **자기가 읽은 것**으로 결정해야 한다. 새 읽기 전용
`IntervalsWorkoutPublisher.inspect(date)`가 게시자의 소유권 규칙을 재사용하므로 규칙이
복제되지도, 두 번째 쓰기 경로가 생기지도 않는다.

> 🚫 **어떤 것도 Intervals 이벤트를 삭제하지 않는다.**

### 5.5 REST / MCP 표면

```
POST|GET /api/v1/workout-drafts/{draftId}/published-revisions
GET      /api/v1/published-workout-revisions/{id}
POST     /api/v1/published-workout-revisions/{id}/approve
POST     /api/v1/published-workout-revisions/{id}/abandon
GET      /api/v1/published-workout-revisions/{id}/apply-preview
POST     /api/v1/published-workout-revisions/{id}/apply
```

MCP 6종은 같은 DTO·같은 코드를 쓰며, **세 커밋 호출 전부**(`approve` / `abandon` / `apply`)에
`confirmedByAthlete` + acknowledged echo가 요구된다.

**실측**: Phase 10 완료 시 수정 #1이 event 145043355에 `UPDATED`로 적용, **FR265에서 확인**.

---

## 6. ⑥ 결과 분석 ✅ `SHIPPED`

```mermaid
flowchart LR
    RUN["달리기 완료"] --> SYNC["Garmin 증분 동기화"]
    SYNC --> DET["상세 수집 (랩·존·샘플)"]
    DET --> AN["RunningActivityAnalysisService"]
    AN --> ST[("activity_analysis + interval_group + interval (V16)")]
    ST --> CTX["다음 날 TrainingContext V2"]
    CTX --> COACH["AI 코치"]
    ST --> REP["GET /training-analysis-report"]
```

```
POST|GET /api/v1/activities/{activityId}/analysis
POST     /api/v1/activities/analysis-refresh[?dryRun=true]
```

재분석은 **모든 새 값이 존재한 뒤 한 트랜잭션에서 치환**되므로, 실패가 저장된 결과를
파괴하지 않는다. 스윕에서 실패한 활동은 **이전 분석을 유지**하고 스윕은 계속된다.

---

## 7. 전 생명주기에 걸친 "하지 않는 것"

| 하지 않는 것 | 이유 |
| --- | --- |
| 자동 게시 재시도 | 미지 상태의 POST를 반복하지 않는다 |
| 놓친 실행 보정 | 어제 못 한 운동을 오늘로 옮기지 않는다 |
| 알림 | 없음 |
| 코치 출력 재작성 | 검증기는 **수락 또는 거부**만 |
| 자동 부상 해제 | 시간 경과로 `RESOLVED`를 추론하지 않는다 |
| Intervals 이벤트 삭제 | 없음 |
| Garmin 쓰기 | 설계상 영구 부재 |
