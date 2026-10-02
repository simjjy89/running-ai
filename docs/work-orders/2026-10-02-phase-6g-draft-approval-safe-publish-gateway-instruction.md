# RunningAI Phase 6G — Draft Approval & Safe Publish Gateway

현재 외부 PC repository root는 `C:\running-ai` 이다.

Phase 6F / 6F.1까지 완료되어 있으며 기준 상태는 다음과 같다.

- Garmin Recovery Intelligence 구현 완료
- First-class REST WorkoutDraft 구현 완료
- Spring/Kotlin 전체 테스트: 782 passed
- Python connector: 88 passed
- Claude live coach eval: 20/20 passed
- 최신 기준 commit: `049cb36`
- Phase 6F 실제 Garmin live validation은 이 외부 PC의 corporate TLS interception 때문에 `BLOCKED_BY_CORPORATE_TLS`
- Garmin recovery 실제 read/backfill은 이 PC에서 더 시도하지 않는다.
- Main PC에서 추후 live validation 예정
- Intervals/Garmin external workout write는 지금까지 0건

이번 Phase 6G의 목표는:

`WorkoutDraft → 명시적 사용자 승인 → 안전한 publish gateway → 기존 검증된 renderer/publisher`

를 구현하는 것이다.

중요한 원칙:

- Claude는 운동을 설계할 수 있지만 승인하거나 publish할 수 없다.
- Spring은 Claude의 운동 판단을 다시 계산하거나 다른 운동으로 바꾸지 않는다.
- 사용자의 명시적 승인 없이는 절대로 external workout write가 발생하면 안 된다.
- REST는 승인 가능한 정식 WorkoutDraft이지만 Intervals/Garmin workout으로 만들지 않는다.
- 이 외부 PC에서는 실제 Intervals/Garmin write를 절대 수행하지 않는다.
- 모든 external publish 검증은 fake/mock client로 수행한다.
- 기존 Phase 5C publisher를 재사용하되, AI Draft를 무시하고 기존 deterministic prescription을 다시 계산하면 안 된다.

---

## 0. 시작 전 preflight

작업 시작 전에 반드시 확인한다.

- repository root 확인
- working tree clean
- current branch = main
- HEAD에 최소 다음 Phase 6F.1 commit이 포함되어 있는지 확인:
  - `83ca3fe`
  - `488f108`
  - `049cb36`

`049cb36`이 ancestor가 아니거나 working tree가 dirty하면 임의로 reset/clean하지 말고 STOP 후 보고한다.

작업 지시서 원문을 구현 전에 먼저 다음 파일에 그대로 저장한다.

`docs/work-orders/2026-10-02-phase-6g-draft-approval-safe-publish-gateway-instruction.md`

작업 완료 후:

`docs/work-orders/2026-10-02-phase-6g-draft-approval-safe-publish-gateway-result.md`

를 작성한다.

---

# 1. 목표 architecture

최종 구조는 아래와 같아야 한다.

WorkoutDraft
→ DRAFT
→ explicit user approval
→ APPROVED
→ publish preview
→ explicit publish request
→ REST?
   - YES → SKIPPED_REST_DAY
            external write = 0
   - NO  → WorkoutDraft publish bridge
            → StructuredWorkout
            → existing IntervalsWorkoutRenderer
            → existing IntervalsWorkoutPublisher
            → Intervals.icu

ClaudeAiCoach는 여기까지다.

ClaudeAiCoach
→ WorkoutDraft

그 이후:

Approval / Preview / Publish

에는 Claude가 관여하지 않는다.

Claude CLI invocation에는 계속 tools/MCP가 비활성화되어 있어야 한다.

Approve/publish API를 Claude MCP tool로 추가하지 않는다.

---

# 2. 매우 중요한 기존 publisher 제약

현재 기존:

`WorkoutPublishApplicationService`

는 날짜를 받아서:

`WorkoutIntensityTargetService`
→ `TargetedWorkoutPrescription`
→ `StructuredWorkoutMapper`
→ renderer
→ publisher

흐름으로 새로운 deterministic workout을 다시 계산한다.

Phase 6G에서는 이 서비스를 AI Draft publish에 사용하면 안 된다.

그렇게 하면 사용자가 승인한 Claude WorkoutDraft가 아니라 다른 workout이 publish될 수 있다.

따라서 AI Draft publish path는:

`WorkoutDraft`
→ 신규 dedicated bridge/mapper
→ `StructuredWorkout`
→ existing `IntervalsWorkoutRenderer`
→ existing `IntervalsWorkoutPublisher`

형태로 구현한다.

재사용 대상:

- `IntervalsWorkoutRenderer`
- `IntervalsWorkoutPublisher`
- provider-neutral structured workout types

재사용 금지:

- AI Draft publish 과정에서 `WorkoutIntensityTargetService.targetedPrescribe(...)`
- `WorkoutPublishApplicationService.publish(date)`를 통한 재처방

기존 `/api/v1/workout-publish`는 이번 Phase에서 삭제하거나 의미를 바꾸지 않는다.

기존 scheduler도 건드리지 않는다.

기존 publish switch/scheduler는 계속 default OFF 상태를 유지한다.

---

# 3. Draft approval lifecycle

현재:

`WorkoutDraftStatus`
- DRAFT
- SUPERSEDED

에 승인 상태를 정식으로 추가한다.

권장:

- DRAFT
- SUPERSEDED
- APPROVED

단 publish 성공 여부는 WorkoutDraftStatus에 섞지 않는다.

WorkoutDraft는 “어떤 운동안을 사용자가 승인했는가”를 표현하고,
external publish 결과는 별도 domain/table로 관리한다.

### 승인 규칙

승인 가능한 경우:

- 현재 version
- status == DRAFT
- 아직 해당 athlete/date에 승인된 다른 draft가 없음

승인 불가:

- SUPERSEDED
- 이미 다른 draft가 같은 athlete/date에 APPROVED
- 존재하지 않는 draft

같은 draft를 다시 approve하는 요청은 idempotent하게 처리한다.

이미 승인된 동일 draft에 대한 재승인은:
- duplicate approval row 생성 금지
- status 변경 없음
- 기존 승인 결과 반환

### 승인 후 immutable

APPROVED draft는 revision할 수 없다.

현재:

`POST /api/v1/workout-drafts/{id}/revisions`

에 APPROVED draft가 들어오면 명확한 conflict로 거부한다.

예:

`WORKOUT_DRAFT_APPROVED_IMMUTABLE`

사용자가 승인 후 운동을 바꾸고 싶다면 기존 승인안을 조용히 수정하면 안 된다.

새 Draft가 필요하다.

이번 Phase에서는 approved draft를 취소하거나 revoke하는 기능까지 확장하지 않아도 된다.

---

# 4. Approval audit / DB safety

명시적 승인을 DB에서 감사 가능하게 남긴다.

권장 모델:

`workout_draft_approval`

최소 필드:

- id
- draft_id
- athlete_id
- workout_date
- approved_at

제약:

- draft_id unique
- `(athlete_id, workout_date)` unique

목적:

한 athlete의 같은 날짜에 서로 다른 두 WorkoutDraft가 동시에 승인되지 않게 DB 레벨에서도 막는다.

WorkoutDraft.status를 APPROVED로 바꾸는 것과 approval row 생성은 같은 transaction에서 처리한다.

이미 적용된 migration 파일은 수정하지 않는다.

신규 Flyway migration 사용.

현재 V8까지 있으므로 다음 번호부터 사용한다.

H2와 PostgreSQL 양쪽에서 migration이 동작해야 한다.

---

# 5. Approval API

추가:

`POST /api/v1/workout-drafts/{id}/approve`

response에는 최소한 다음 의미가 포함되어야 한다.

- draftId
- draftGroupId
- version
- date
- workoutType
- status = APPROVED
- approvedAt

승인은 external write를 절대 발생시키지 않는다.

다음 dependency가 승인 코드에 들어가면 안 된다.

- IntervalsWorkoutPublisher
- IntervalsWorkoutClient
- Garmin write
- legacy WorkoutPublishApplicationService

Approval은 DB lifecycle operation일 뿐이다.

---

# 6. Publish preview

실제 external publish 전에 사람이 정확히 무엇이 나갈지 볼 수 있어야 한다.

read-only preview endpoint를 추가한다.

예:

`GET /api/v1/workout-drafts/{id}/publish-preview`

규칙:

- APPROVED draft만 preview 가능
- external network write 없음
- publish feature switch가 OFF여도 preview 가능

일반 workout이면:

WorkoutDraft
→ publish bridge
→ StructuredWorkout
→ IntervalsWorkoutRenderer

까지만 수행한다.

response에서 최소:

- draftId
- date
- workoutType
- restDay
- publishable
- renderedWorkoutText
- structuredStepCount

를 확인할 수 있어야 한다.

REST이면:

- restDay = true
- publishable = true
- renderedWorkoutText = null 또는 empty 대신 명시적 REST representation
- externalWriteRequired = false
- expectedOutcome = SKIPPED_REST_DAY

REST를 renderer에 넘겨 빈 문자열을 만든 다음 publisher에 넘기는 식으로 처리하지 않는다.

REST는 publisher 호출 전에 분기한다.

---

# 7. WorkoutDraft → StructuredWorkout publish bridge

신규 dedicated mapper/bridge를 만든다.

새 Spring 코드는 Kotlin-first를 유지한다.

예:

`WorkoutDraftStructuredWorkoutMapper`
또는 repository naming convention에 맞는 이름.

이 component의 목적은:

“승인된 AI WorkoutDraft를 기존 verified renderer가 소비할 수 있는 provider-neutral structure로 정확히 옮기는 것”

뿐이다.

새로운 훈련을 설계하거나 intensity를 변경하면 안 된다.

### 반드시 지킬 것

- AI가 만든 duration 변경 금지
- pace 변경 금지
- HR target 임의 변경 금지
- treadmill speed/incline 변경 금지
- segment 순서 변경 금지
- workoutType을 보고 Spring이 다른 workout을 추천하는 logic 금지

---

# 8. 현재 domain compatibility를 반드시 audit

현재 WorkoutDraft와 기존 StructuredWorkout 사이에 표현 차이가 있으므로 구현 전에 실제 code를 검사한다.

특히:

### repetitions

WorkoutDraftSegment에는:

- repetitions
- recoveryDurationMinutes

가 있다.

기존 StructuredWorkoutStep은 repeat 구조를 직접 가지고 있지 않다.

repeat block은 lossless하게 sequential step으로 펼칠 수 있다.

예:

5 x 3 min + 2 min recovery

는 승인된 의미를 바꾸지 않는 structural expansion으로 처리한다.

Draft의 현재 effectiveDuration 규칙은 마지막 repetition 뒤 recovery도 total에 포함한다.

mapper도 정확히 같은 시간을 보존해야 한다.

mapping 후 모든 StructuredWorkoutStep duration의 합이 WorkoutDraft.totalDurationMinutes와 정확히 일치하는지 검증한다.

불일치 시 external call 전에 FAIL.

### pace

WorkoutDraft:

- paceSecondsPerKmFast
- paceSecondsPerKmSlow

기존:

`PaceTarget`

으로 정확히 mapping 가능하면 사용한다.

### treadmill

WorkoutDraft:

- treadmillSpeedKphMin/Max
- inclinePercentMin/Max

기존 `TreadmillTarget` / Garmin-safe cue path와 호환되는지 확인하고 lossless하게 mapping한다.

### heart rate

중요:

현재 WorkoutDraft는 absolute BPM:

- heartRateBpmMin
- heartRateBpmMax

를 가지고 있다.

기존 IntervalsWorkoutRenderer의 active heart-rate rendering은 `%LTHR` 형태를 사용한다.

이 차이를 절대로 조용히 무시하지 않는다.

다음 중 하나를 명시적으로 선택하고 문서화한다.

A. existing athlete LTHR를 이용한 deterministic unit conversion이 training decision이 아닌 transport conversion으로 안전하게 정의 가능하고, 정확한 validator/test를 만들 수 있다면 이를 구현한다.

또는

B. 현재 Draft를 lossless하게 표현할 수 없다면 `UNPUBLISHABLE_DRAFT`로 fail closed한다.

중요:

- heart-rate target을 조용히 삭제 금지
- AI가 승인한 HR target 대신 Spring이 새로운 zone을 선택 금지
- 기존 WorkoutIntensityTargetService로 다시 target을 계산 금지

필요하다면 최소한의 explicit target metadata를 WorkoutDraft domain에 추가할 수 있지만 범위를 불필요하게 키우지 않는다.

“publish 성공시키기 위해 승인된 workout 의미를 변경”하는 것보다 fail closed가 우선이다.

### primary target ambiguity

pace/HR/treadmill target이 동시에 존재할 때 기존 renderer가 모든 target을 active target으로 표현하지 못한다면 이를 audit한다.

승인된 정보를 조용히 잃어버리지 않는다.

필요하면 publishability validation을 추가한다.

---

# 9. Publishability validator

approval과 publishability는 별개다.

사용자는 Draft 자체를 승인할 수 있지만 실제 renderer가 표현할 수 없는 경우 publish가 차단될 수 있다.

신규 validator를 두는 것을 권장한다.

예:

`ApprovedWorkoutDraftPublishabilityValidator`

검사 예:

- APPROVED 여부
- REST shape
- known workout type
- valid duration
- mapping 가능한 targets
- mapping 후 duration equality
- renderer가 표현하지 못하는 필수 target 존재 여부
- empty rendered workout 여부

실패하면:

`UNPUBLISHABLE_DRAFT`

또는 세부 reason code.

중요:

publisher/client 호출 전에 실패해야 한다.

---

# 10. Safe publish gateway

신규 service를 만든다.

예:

`ApprovedWorkoutDraftPublishService`

역할:

1. approved draft load
2. approval 확인
3. 이미 publish 완료되었는지 확인
4. single-flight guard
5. REST 여부 확인
6. 일반 workout이면 publishability validation
7. WorkoutDraft → StructuredWorkout
8. existing IntervalsWorkoutRenderer
9. existing IntervalsWorkoutPublisher
10. 성공 결과 persistence

DB transaction을 Intervals HTTP call 동안 열어두지 않는다.

Phase 6E에서 이미 발견했던 self-invoked transactional 문제를 다시 만들지 않는다.

transactional store component를 분리해도 된다.

---

# 11. 새로운 publish feature switch

AI Draft publish용 switch를 기존 legacy switch와 분리한다.

예:

`RUNNING_AI_DRAFT_PUBLISHING_ENABLED=false`

default 반드시 false.

기존:

`WORKOUT_PUBLISHING_ENABLED`

와 의미를 섞지 않는다.

이유:

기존 switch는 date-based deterministic publishing path를 제어하고,
Phase 6G는 approved AI draft publish를 제어한다.

### switch OFF

가능:

- Draft 생성
- revision
- approval
- preview

불가능:

- Phase 6G publish action

### switch ON

승인된 draft만 publish 가능.

이 외부 PC에서는 switch를 실제 Intervals credential과 함께 사용해 real write하지 않는다.

테스트에서는 fake/mock publisher로만 ON path를 검증한다.

---

# 12. Publish API

추가:

`POST /api/v1/workout-drafts/{id}/publish`

조건:

- feature switch enabled
- APPROVED
- current approval 존재
- publishable

DRAFT 직접 publish:

거부.

SUPERSEDED publish:

거부.

APPROVED지만 다른 date approval conflict:

DB/domain invariant상 없어야 한다.

---

# 13. REST publish semantics

REST는 이번 Phase의 핵심 안전 규칙이다.

APPROVED REST에 publish 요청:

`SKIPPED_REST_DAY`

그리고:

- IntervalsWorkoutRenderer 호출하지 않아도 됨
- IntervalsWorkoutPublisher 호출 0
- IntervalsWorkoutClient 호출 0
- Garmin workout 0
- empty workout 생성 금지
- calendar placeholder 생성 금지

REST는 “빈 workout”이 아니다.

“오늘은 운동을 publish하지 않는다는 승인된 코칭 결정”이다.

publication result는 DB에 저장해서 동일 REST draft publish 요청을 반복해도 다시 처리하지 않게 한다.

---

# 14. Publication audit

WorkoutDraft 승인 상태와 external publish 결과는 분리한다.

신규 table/domain 예:

`workout_draft_publication`

최소:

- id
- draft_id
- approval_id 또는 approval relation
- outcome
- intervals_operation nullable
- remote_event_id nullable
- verified nullable
- published_at

outcome 최소:

- PUBLISHED
- SKIPPED_REST_DAY

Intervals operation은 기존:

- CREATED
- UPDATED
- NO_CHANGE

를 그대로 재사용 가능.

REST:

- outcome = SKIPPED_REST_DAY
- intervals_operation = null
- remote_event_id = null
- external write = 0

일반 workout:

- outcome = PUBLISHED
- existing IntervalsPublishResult 기록

success publication은 draft별 unique.

---

# 15. Idempotency

같은 approved draft를 두 번 publish해도 두 번째 요청에서는 external publisher를 다시 호출하지 않는다.

이미 성공 publication row가 있으면 저장된 결과를 그대로 반환한다.

REST 역시 동일.

### publish failure

Intervals publisher가 exception을 던진 경우:

- 성공 publication으로 기록하지 않는다.
- draft는 APPROVED 상태 유지
- 명시적 재시도 가능
- 기존 IntervalsWorkoutPublisher의 remote idempotency/unknown-outcome recovery를 그대로 활용한다.
- HTTP failure를 성공처럼 기록하지 않는다.

publisher 실패를 이유로 Draft 내용을 변경하지 않는다.

---

# 16. Concurrent publish

같은 draft에 동시에 publish가 들어오면 두 external call이 발생하면 안 된다.

현재 single-instance deployment를 전제로 existing per-date guard와 비슷한 single-flight 방식을 사용할 수 있다.

가능하면 draft id 기준 guard.

두 번째 concurrent request는 명시적 conflict 처리.

DB unique publication constraint도 최종 방어선으로 둔다.

다른 draft/date는 서로 불필요하게 block하지 않는다.

---

# 17. 같은 날짜 두 승인안 방지

특히 중요하다.

서로 다른 draft group에서 같은 날짜 workout이 각각 만들어질 수 있다.

따라서 같은 athlete/date에는 한 개의 APPROVED draft만 존재하게 한다.

예:

Draft A
2026-10-03
APPROVED

Draft B
2026-10-03
DRAFT

Draft B approve:

409 conflict

외부 event identity가 날짜 기준이므로 이 제약 없이 두 draft를 차례로 publish하면 두 번째가 첫 번째를 UPDATE할 수 있다.

이 상황을 approval 단계에서 차단한다.

---

# 18. Approved Draft revision 금지

다음은 금지:

APPROVED v1
→ revise
→ silently v2

승인된 내용과 실제 publish 대상이 달라질 수 있기 때문이다.

`WorkoutDraftStore.loadForRevision` 또는 적절한 lifecycle layer에서 APPROVED를 명시적으로 거부한다.

SUPERSEDED 처리와 별도 error code를 사용한다.

---

# 19. Existing legacy path safety

이번 Phase 완료 후에도:

`POST /api/v1/workout-publish`

는 기존 legacy deterministic endpoint다.

Phase 6G의 approved draft workflow와 혼동하지 않도록 documentation에 명확히 남긴다.

자동 scheduler:

`WorkoutPublishingScheduler`

는 계속 default OFF.

Phase 6G를 구현했다고 scheduler를 enable하지 않는다.

MCP publish tool도 enable하거나 확장하지 않는다.

---

# 20. API error semantics

repository 기존 error style을 따르되 최소 다음을 구분한다.

예:

- WORKOUT_DRAFT_NOT_FOUND
- WORKOUT_DRAFT_SUPERSEDED
- WORKOUT_DRAFT_APPROVED_IMMUTABLE
- WORKOUT_DRAFT_APPROVAL_REQUIRED
- WORKOUT_DATE_ALREADY_APPROVED
- DRAFT_PUBLISHING_DISABLED
- DRAFT_PUBLISH_ALREADY_RUNNING
- UNPUBLISHABLE_DRAFT

정확한 HTTP status는 기존 conventions와 일관되게 선택한다.

409/422/503 등을 의미에 맞게 사용한다.

raw AI prompt, credentials, token, Intervals secret은 error response/log에 넣지 않는다.

---

# 21. 최소 테스트 matrix

최소 아래를 자동 테스트한다.

1. DRAFT → APPROVED 성공
2. 동일 draft 재승인 idempotent
3. SUPERSEDED 승인 거부
4. APPROVED revision 거부
5. 같은 athlete/date의 두 번째 draft 승인 거부
6. approval 자체는 external call 0
7. DRAFT publish 거부
8. SUPERSEDED publish 거부
9. publish switch OFF에서 일반 publish 거부
10. switch OFF에서도 preview 가능
11. approved REST preview
12. approved REST publish → SKIPPED_REST_DAY
13. REST publish에서 renderer/publisher/client call 0
14. 일반 approved draft preview가 renderer text를 생성
15. 일반 approved draft publish가 existing IntervalsWorkoutPublisher를 정확히 1회 호출
16. CREATED result persistence
17. UPDATED result persistence
18. NO_CHANGE result persistence
19. 성공 publish 재호출 → stored result 반환 / publisher 추가 호출 0
20. concurrent same-draft publish → external call 최대 1
21. publisher failure → 성공 publication row 없음
22. failure 후 explicit retry 가능
23. unknown/unmapped workout type fail closed
24. unpublishable target → publisher call 0
25. repeated interval expansion duration 보존
26. segment order 보존
27. pace target 보존
28. treadmill speed/incline cue 보존
29. HR mapping 정책 테스트
30. mapping 후 total duration exact equality
31. approval DB uniqueness
32. publication DB uniqueness
33. H2 Flyway full migration
34. existing PostgreSQL migration regression
35. old legacy publish tests regression
36. CoachArchitectureTest 유지
37. AiCoach/WorkoutDraftService가 publisher dependency를 얻지 않았음을 architecture test로 확인
38. publish switch default false
39. scheduler default false 유지
40. MCP/Claude가 approve/publish capability를 얻지 않았음

---

# 22. External write zero test

이번 외부 PC 작업에서는 실제 Intervals API를 호출하지 않는다.

반드시 fake/mock:

- IntervalsWorkoutPublisher 또는
- IntervalsWorkoutClient

를 사용한다.

테스트에서 publish switch를 true로 설정하는 것은 가능하지만 downstream은 fake여야 한다.

실제 `.env`의 Intervals credential을 사용해서 publish하지 않는다.

실제 Garmin workout write도 하지 않는다.

최종 result 문서에:

`External workout writes during Phase 6G implementation: 0`

을 명시한다.

---

# 23. Regression baseline

작업 전 기준:

- Spring/Kotlin: 782 passed
- Python connector: 88 passed
- Claude live eval: 20/20

Phase 6G는 Garmin connector를 수정하지 않는다면 Python functional code를 건드리지 않는다.

그래도 가능하면 Python 88 regression 확인.

Spring:

`gradlew clean test`

전체 GREEN 필수.

기존 782보다 테스트 수가 증가하는 것은 정상.

0 failed
0 unexpected skipped

---

# 24. PostgreSQL validation

가능하면 외부 PC에서 테스트용 PostgreSQL로 신규 migration을 실제 적용한다.

기존 V1–V8
→ 신규 Phase 6G migrations

upgrade path 확인.

검증:

- approval uniqueness
- approved REST
- normal approved workout
- publication result
- rerun/idempotency

Docker/PostgreSQL 실행 환경이 없다면 이를 억지로 설치/우회하지 말고 `NOT_RUN`으로 정확히 보고한다.

---

# 25. Live Claude eval

Phase 6G에서 Claude prompt/response contract를 변경하지 않았다면 기존 20-scenario live Claude eval을 다시 돌릴 필요는 없다.

단 WorkoutDraft target contract나 Claude prompt를 변경했다면:

- deterministic tests
- relevant coach eval
- 필요 시 20 scenario regression

을 다시 실행한다.

결과를 정확히 기록한다.

---

# 26. Security / architecture invariant

반드시 유지:

ClaudeAiCoach
→ no approval dependency
→ no publisher dependency
→ no Intervals write dependency
→ no Garmin write dependency

WorkoutDraftService
→ no publisher dependency

Approval service
→ DB only

ApprovedDraftPublishService
→ approved immutable Draft read
→ bridge/renderer/publisher

Intervals credentials는 existing integration layer 밖으로 이동시키지 않는다.

---

# 27. No scheduler in this Phase

다음은 하지 않는다.

- automatic Draft approval
- automatic approved Draft publish scheduler
- recovery scheduler 추가
- startup publish
- Claude autonomous publish
- MCP approval/publish
- Garmin live write

모든 Phase 6G publish는 explicit manual HTTP request만 가능하게 한다.

---

# 28. Documentation

CLAUDE.md 또는 architecture summary에서 lifecycle을 업데이트한다.

최종 lifecycle:

TrainingContext
→ ClaudeAiCoach
→ WorkoutDraft DRAFT
→ optional revision(s)
→ explicit APPROVED
→ preview
→ explicit publish

일반 workout:
→ StructuredWorkout
→ Intervals renderer
→ Intervals publisher
→ PUBLISHED

REST:
→ SKIPPED_REST_DAY
→ external write 0

그리고 다음을 명시한다.

Phase 6F Live Validation:
- External PC: BLOCKED_BY_CORPORATE_TLS
- Main PC: PENDING

Phase 6G:
- code/fake integration validation 가능
- real external publish: NOT_RUN

---

# 29. Git

작업을 논리적으로 commit한다.

예:

- `feat: add workout draft approval lifecycle`
- `feat: add approved draft safe publish gateway`
- `test: verify approved draft publishing safety`
- `docs: record Phase 6G result`

작업 종료 시:

- full tests green
- working tree clean

확인.

이 외부 PC에서 완료된 코드를 메인 PC가 나중에 받을 수 있어야 하므로, 테스트가 모두 성공하고 remote main이 예상 baseline에서 fast-forward 가능한 상태라면:

`git push origin main`

까지 수행한다.

force push 금지.

push가 reject되면 pull/rebase/reset을 임의로 수행하지 말고 STOP하고 보고한다.

최종 SHA와 push 결과를 기록한다.

---

# 30. 최종 보고 형식

작업 완료 후 반드시 아래 순서로 보고한다.

1. Preflight / baseline commit
2. 변경 파일
3. 신규 DB migrations
4. Approval lifecycle
5. Approval API example
6. Publish preview example
7. REST approval/publish example
8. 일반 workout approved publish flow
9. WorkoutDraft → StructuredWorkout mapping 정책
10. repetitions 처리
11. pace / HR / treadmill target mapping 결과
12. unpublishable draft 정책
13. 같은 날짜 중복 승인 방지
14. revision-after-approval 결과
15. publish idempotency 결과
16. concurrent publish 결과
17. external publisher failure/retry 결과
18. publication audit 결과
19. feature switch/default 상태
20. scheduler/MCP 상태
21. Spring/Kotlin test count
22. Python test count
23. PostgreSQL migration test
24. actual external workout writes count
25. real Intervals publish: NOT_RUN
26. real Garmin write: NOT_RUN
27. Phase 6F live validation: still PENDING on main PC
28. commit SHA
29. push result
30. working tree clean 여부

---

# 완료 기준

Phase 6G 완료는 다음을 모두 만족해야 한다.

- AI Draft와 external publishing 사이에 explicit approval 존재
- 동일 athlete/date의 승인 workout은 하나
- approved Draft immutable
- preview는 write 없이 가능
- DRAFT/SUPERSEDED direct publish 불가
- REST는 `SKIPPED_REST_DAY`
- REST external write 0
- 일반 workout만 verified lower-level publisher 사용
- AI Draft publish path에서 deterministic prescription 재생성 금지
- unsupported/lossy mapping은 fail closed
- successful publish idempotent
- concurrency safe
- feature switch default OFF
- scheduler OFF
- Claude/MCP autonomous publish 없음
- full regression GREEN
- external PC real write 0
- docs/commits complete
- safe fast-forward push 완료 또는 명확한 NOT_PUSHED 사유

구현, 테스트, 문서화, commit 및 안전한 push까지 진행한 뒤 최종 결과를 보고해줘.
