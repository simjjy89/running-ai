Phase 5C-3을 진행해줘.

기준 commit은 34e072d야.
먼저 origin/main을 pull하고 clean working tree인지 확인해.

CLAUDE.md와 관련 project skill을 따르고,
작업지시서는:

docs/work-orders/2026-10-01-phase-5c-3-intervals-workout-publisher.md

에 저장해.

이번 Phase는
RenderedIntervalsWorkout
→ IntervalsWorkoutPublisher
→ IntervalsWorkoutClient
→ Intervals server readback
까지 구현해.

CREATE / UPDATE / NO_CHANGE idempotency와
publish 후 readback verification이 핵심이야.

특히 create timeout 시 blind POST retry로 중복 workout을 만들지 마.

외부 노트북에 실제 Intervals credential이 없으면
live validation은 SKIPPED로 기록하고,
credential을 새로 만들거나 repo에서 찾으려고 하지 마.

Garmin 실기기 검증, scheduler, ChatGPT 연결,
legacy 삭제는 하지 마.

구현 → tests → 전체 Java regression
→ 가능한 경우에만 안전한 live validation
→ docs → full diff/secrets check
→ commit → push까지 진행하고 5C-4는 시작하지 마.
# RunningAI Phase 5C-3
## IntervalsWorkoutPublisher — HTTP / Authentication / Idempotency / Readback Verification

### 0. 기준 상태

기준 commit:

```text
34e072d
```

현재 완료된 새 Spring workout pipeline:

```text
TargetedWorkoutPrescription
        ↓
StructuredWorkoutMapper
        ↓
StructuredWorkout
        ↓
IntervalsWorkoutRenderer
        ↓
GarminSafeCueFormatter
        ↓
RenderedIntervalsWorkout
```

Phase 5C-2 상태:

```text
Java tests: 346 / 346 PASS

PACE rendering        UNIT_VERIFIED
PACE Garmin target    UNRESOLVED

%LTHR rendering       UNIT_VERIFIED
%LTHR Garmin support  ASSUMED

Treadmill cue         UNIT_VERIFIED
Cue ordering          legacy DEVICE_VERIFIED evidence
```

이번 Phase에서는 이 뒤에 실제 Intervals.icu publishing layer를 연결한다.

---

# 1. 외부 노트북에서 시작

먼저 repository 최신 상태를 맞춘다.

```powershell
cd <running-ai repository>

git status
git switch main
git pull --ff-only origin main
git log -5 --oneline
```

반드시 확인:

```text
34e072d 포함
branch = main
working tree clean
```

사용자가 작성한 미커밋 변경사항이 있으면 reset/delete하지 않는다.

---

# 2. Project rules

반드시 다음을 따른다.

```text
CLAUDE.md
관련 .claude/skills/*
```

필요한 경우:

```text
running-ai-dev
running-ai-integration
```

등 현재 repository에 존재하는 관련 skill을 사용한다.

Phase 5C-0 문서와 5C-1/5C-2 구현을 source of truth로 사용한다.

repo 전체 architecture를 다시 처음부터 탐색하지 않는다.

---

# 3. Work Order 저장

이 지시서를 다음 경로에 저장한다.

```text
docs/work-orders/
2026-10-01-phase-5c-3-intervals-workout-publisher.md
```

완료 결과 역시 기존 project convention대로 문서화한다.

---

# 4. 이번 Phase 목표

다음 pipeline을 완성한다.

```text
StructuredWorkout
        ↓
IntervalsWorkoutRenderer
        ↓
RenderedIntervalsWorkout
        ↓
IntervalsWorkoutPublisher
        ↓
IntervalsWorkoutClient
        ↓
Intervals.icu API
        ↓
Readback Verification
```

이번 Phase가 성공하면:

```text
RunningAI
→ workout generation
→ structured workout
→ Intervals syntax
→ Intervals publish
→ Intervals server verification
```

까지 새 Spring 경로가 연결된다.

---

# 5. 이번 Phase 구현 범위

구현:

```text
1. IntervalsWorkoutClient
2. IntervalsWorkoutPublisher
3. configuration / authentication
4. remote workout lookup
5. CREATE
6. UPDATE
7. NO_CHANGE
8. stable logical identity
9. marker-based ownership/idempotency
10. legacy marker compatibility 검토
11. publish 후 readback
12. readback verification
13. HTTP/error mapping
14. unit/integration tests
```

---

# 6. 이번 Phase에서 하지 않을 것

다음은 구현하지 않는다.

```text
Garmin Connect 직접 호출
Garmin device validation
scheduler
@Scheduled
ChatGPT integration
automatic daily generation
legacy PowerShell 삭제
legacy publishing path 제거
Command Channel
ntfy
Apple Watch mode
cross-training renderer migration
```

Garmin 실기기 검증은 Phase 5C-4다.

---

# 7. Architecture boundary

책임을 반드시 분리한다.

```text
StructuredWorkout
= provider-neutral domain

IntervalsWorkoutRenderer
= Intervals workout syntax

IntervalsWorkoutPublisher
= publish orchestration / idempotency / verification

IntervalsWorkoutClient
= HTTP transport / authentication

GarminSafeCueFormatter
= Garmin-visible cue formatting
```

Publisher에서:

```text
pace 계산
HR target 계산
treadmill cue 계산
```

을 다시 하지 않는다.

`RenderedIntervalsWorkout`을 그대로 사용한다.

---

# 8. Legacy reference

다음 legacy 코드를 behavior reference로 조사한다.

```text
create-today-workout.ps1
intervals-structured-workout.ps1
```

필요한 부분만 확인:

```text
Intervals endpoint
HTTP method
authentication 방식
event lookup 방식
event create/update payload
date 처리
marker 생성
marker 탐색
idempotency 판단
```

PowerShell 구조 전체를 Java로 번역하지 않는다.

---

# 9. API contract 추측 금지

Intervals endpoint와 payload는 memory나 추측으로 구현하지 않는다.

반드시:

```text
legacy implementation
기존 repository docs/tests
```

에서 실제 contract를 확인한다.

필요하다면 공식/current Intervals API documentation을 확인할 수 있지만,
existing project behavior와 충돌하면 먼저 차이를 문서화한다.

---

# 10. Configuration

Intervals 관련 configuration을 Spring configuration으로 둔다.

개념 예:

```yaml
running-ai:
  intervals:
    base-url: ...
    athlete-id: ...
    api-key: ...
```

실제 property 이름은 기존 project convention을 따른다.

절대 source code에 실제 credential을 넣지 않는다.

---

# 11. 외부 노트북 credential 정책

외부 노트북에 실제 Intervals credential이 없다면:

```text
live validation = SKIPPED
```

로 처리한다.

이것은 Phase 실패가 아니다.

credential을 GitHub에서 찾거나 commit history에서 복구하려 하지 않는다.

사용자에게 credential을 source code에 넣으라고 하지 않는다.

---

# 12. Secret policy

절대 commit하지 않는다.

```text
Intervals API key
Authorization header
real personal token
Garmin token
password
cookies
.env
real workout/event identifier
personal Garmin payload
GPS data
```

테스트에서는 synthetic value만 사용한다.

예:

```text
test-api-key
test-athlete-id
event-123
```

---

# 13. HTTP client

현재 Spring Boot project가 이미 사용하는 HTTP 방식부터 확인한다.

기존에:

```text
RestClient
WebClient
```

등이 있다면 그 convention을 따른다.

이번 기능 때문에 새 HTTP library/framework를 추가하지 않는다.

---

# 14. Timeout

외부 호출에는 명시적 timeout을 둔다.

기존 Garmin connector HTTP client와 project convention을 재사용할 수 있으면 재사용한다.

무한 timeout 금지.

---

# 15. Retry 정책

특히 중요한 원칙:

```text
blind POST retry 금지
```

이유:

```text
POST
→ Intervals에서 실제 생성 성공
→ response를 받기 전에 timeout
→ client가 POST retry
→ duplicate event
```

가능성이 있기 때문이다.

Unknown create result일 경우:

```text
marker lookup
→ remote 존재 여부 확인
→ create/update decision
```

으로 회복한다.

---

# 16. Idempotency 목표

같은 logical workout을 반복 publish해도 remote workout은 하나여야 한다.

예:

```text
publish #1
→ CREATED

publish #2
→ NO_CHANGE
```

Workout 내용이 변경되면:

```text
publish #3
→ UPDATED
```

기존 remote event identity를 유지한다.

---

# 17. Logical identity

Rendered text 전체를 idempotency key로 사용하지 않는다.

Workout text는 변경될 수 있기 때문이다.

Logical identity는 최소한:

```text
RunningAI-owned
+
athlete
+
scheduled date
```

를 의미해야 한다.

하루에 여러 RunningAI workout을 지원하는 구조가 이미 있다면 현재 domain의 slot/type identity를 함께 사용한다.

미래 기능을 위해 불필요하게 복잡한 identity framework를 만들지 않는다.

---

# 18. Marker

Remote Intervals event가 RunningAI 소유임을 알 수 있는 deterministic marker를 사용한다.

Phase 5C-0에서 확인한 legacy marker semantics를 먼저 조사한다.

가능하면 migration 기간 동안 기존 legacy workout도 인식할 수 있어야 한다.

중요:

```text
marker semantic은 보존
PowerShell 구조는 보존할 필요 없음
```

---

# 19. Legacy compatibility

기존 legacy publisher가 만든 workout을 Spring publisher가 발견할 수 있다면 가장 좋다.

예:

```text
legacy workout 존재
        ↓
Spring publisher 실행
        ↓
기존 event 발견
        ↓
NO_CHANGE 또는 UPDATE
```

새 event를 중복 생성하면 안 된다.

Legacy marker format이 너무 결합돼 있다면 compatibility helper를 integration layer에 둔다.

Domain에는 legacy marker가 들어가면 안 된다.

---

# 20. Publish state machine

기본 흐름:

```text
RenderedIntervalsWorkout
        ↓
logical identity
        ↓
remote candidate lookup
        ↓
       존재?
     /       \
   NO         YES
   ↓           ↓
CREATE       compare
               ↓
             same?
           /       \
         YES        NO
         ↓           ↓
    NO_CHANGE      UPDATE
         \           /
          \         /
           READBACK
               ↓
             VERIFY
```

---

# 21. Operation result

최소 operation:

```text
CREATED
UPDATED
NO_CHANGE
```

예:

```java
IntervalsPublishResult
```

필드 후보:

```text
operation
remoteEventId
verified
scheduledDate
```

필요한 최소 값만 둔다.

API raw response 전체를 result에 노출하지 않는다.

---

# 22. Candidate lookup

scheduled date를 알고 있으므로 remote history 전체를 가져오지 않는다.

가능한 가장 작은 date/window로 조회한다.

그리고:

```text
같은 날짜라는 이유만으로
사용자의 다른 event를 수정하면 안 된다.
```

반드시 RunningAI marker/ownership을 확인한다.

---

# 23. Duplicate owned workout

동일 logical marker를 가진 remote event가 2개 이상 발견되면:

```text
ambiguous
```

상태다.

자동으로 하나를 선택하거나 삭제하지 않는다.

명확하게 실패시킨다.

예:

```text
INTERVALS_DUPLICATE_OWNED_WORKOUT
```

---

# 24. CREATE

candidate가 없으면 create한다.

Renderer output:

```text
RenderedIntervalsWorkout.workoutText
```

를 그대로 전달한다.

Publisher가 다시 workout text를 생성하지 않는다.

---

# 25. UPDATE

candidate가 있고 desired state와 remote state가 다르면 UPDATE한다.

원칙:

```text
existing event ID 유지
```

delete + recreate 방식은 피한다.

---

# 26. NO_CHANGE

remote state가 desired state와 이미 동일하다면:

```text
NO_CHANGE
```

로 처리한다.

불필요한 update 요청을 보내지 않는다.

---

# 27. Compare 정책

가능하면 rendered workout text exact comparison을 사용한다.

단 Intervals server가 unavoidable normalization을 수행한다면 그 부분만 최소 normalization한다.

예:

```text
trailing newline
newline representation
```

등.

다음처럼 과도한 비교는 금지:

```text
all whitespace 제거
대소문자 무시
문장 전체 trim
```

이렇게 하면 실제 rendering regression을 놓칠 수 있다.

---

# 28. Readback Verification

CREATE/UPDATE 후 반드시 서버에서 다시 읽는다.

```text
CREATE / UPDATE
        ↓
remote ID
        ↓
GET/readback
        ↓
verify
```

검증 항목 최소:

```text
marker
scheduled date
workout text
```

필요하다면 title/name도 포함한다.

---

# 29. Readback 성공 기준

다음이 모두 맞아야 한다.

```text
RunningAI ownership marker
scheduled date
rendered workout content
```

성공:

```text
verified = true
```

---

# 30. Readback mismatch

HTTP CREATE/UPDATE가 2xx였더라도 readback이 다르면 성공으로 처리하지 않는다.

명확한 오류:

```text
INTERVALS_READBACK_MISMATCH
```

또는 현재 project convention에 맞는 equivalent.

이번 Phase에서 자동 재수정 retry loop는 만들지 않는다.

---

# 31. Readback의 목적

Phase 5C-4에서 다음 경계를 분리하기 위한 것이다.

```text
RunningAI
→ Intervals                 SERVER_VERIFIED
→ Garmin Connect
→ Garmin Forerunner 265     DEVICE validation
```

5C-3 readback 성공 후 Garmin에서 문제가 나면:

```text
RunningAI → Intervals 문제 아님
```

이라고 좁힐 수 있다.

---

# 32. Known Garmin 상태 유지

5C-3에서 절대로 다음 상태를 올려 잡지 않는다.

```text
Pace Garmin target
= UNRESOLVED

%LTHR Garmin
= ASSUMED
```

Intervals server에 target text가 존재한다고 Garmin 지원이 증명되는 것은 아니다.

---

# 33. Treadmill 상태

Readback에서:

```text
N.Nkph
Incline...
```

cue가 보존되는지만 확인한다.

Forerunner 265에서 실제 보이는지는 5C-4에서 다시 확인한다.

---

# 34. Error mapping

최소한 다음을 구분한다.

```text
configuration missing
authentication failure
authorization failure
rate limit
timeout
connection failure
invalid remote response
duplicate owned workout
readback mismatch
```

현재 project exception style을 따른다.

---

# 35. HTTP status

가능하면 최소:

```text
401
403
429
other 4xx
5xx
transport failure
```

를 의미 있게 분류한다.

Credential은 exception/log에 절대 포함하지 않는다.

---

# 36. Logging

INFO 수준 허용:

```text
scheduled date
operation
CREATED / UPDATED / NO_CHANGE
HTTP status
verification success/failure
```

금지:

```text
Authorization
API key
cookie
full personal response
full workout payload
```

Rendered workout 전문도 일반 INFO 로그에는 남기지 않는다.

---

# 37. Client tests

실제 network 없이 테스트한다.

현재 repository의 mock HTTP convention을 따른다.

최소:

```text
authentication
lookup
create
update
readback

401
403
429
5xx
transport/timeout where practical
```

---

# 38. CREATE test

Remote candidate 없음.

기대:

```text
lookup
→ CREATE
→ readback
→ VERIFIED
```

결과:

```text
operation = CREATED
verified = true
```

---

# 39. UPDATE test

Candidate 존재.

Remote workout != desired workout.

기대:

```text
lookup
→ UPDATE existing ID
→ readback
→ VERIFIED
```

결과:

```text
UPDATED
```

---

# 40. NO_CHANGE test

Candidate 존재.

Remote == desired.

기대:

```text
no CREATE
no UPDATE
```

결과:

```text
NO_CHANGE
```

---

# 41. Repeated publish test

같은 logical workout을 연속 실행:

```text
first  → CREATED
second → NO_CHANGE
```

Remote event count:

```text
1
```

이어야 한다.

---

# 42. Changed workout test

같은 date/identity인데 target이나 workout 내용 변경:

```text
first → CREATED
next  → UPDATED
```

Remote ID는 같아야 한다.

새 remote event가 만들어지면 실패.

---

# 43. Duplicate marker test

Remote에 같은 marker candidate 2개.

기대:

```text
publisher abort
no create
no update
```

---

# 44. Readback mismatch test

CREATE/UPDATE 성공 response 후 remote GET 결과가 예상과 다름.

기대:

```text
publish success로 반환하지 않음
readback mismatch exception
```

---

# 45. Unknown create outcome test

가능하면 다음 scenario도 테스트한다.

```text
CREATE request transport timeout
```

이때 publisher가 즉시 같은 POST를 retry하면 안 된다.

Recovery strategy는 marker lookup 기반이어야 한다.

구현 complexity가 과도하면 최소한 blind retry가 없음을 테스트/문서화한다.

---

# 46. Legacy compatibility test

Legacy marker fixture가 있다면:

```text
legacy event
→ Spring publisher
→ existing candidate detection
→ NO_CHANGE / UPDATE
```

를 테스트한다.

이 테스트가 가능하면 migration 안정성 측면에서 우선순위가 높다.

---

# 47. Renderer boundary test

Publisher test에서는:

```text
pace formatting
%LTHR formatting
cue normalization
```

을 다시 검증하지 않는다.

그것은 5C-2 책임이다.

Publisher test는 `RenderedIntervalsWorkout` fixture를 직접 사용한다.

---

# 48. Database

기본 정책:

```text
migration = NO
schema change = NO
```

remote ID 저장용 table을 편의상 추가하지 않는다.

Marker 기반 idempotency만으로 충분한지 먼저 확인한다.

정말 persistence가 필수라는 강한 이유가 있을 때만 재검토한다.

---

# 49. Legacy files

수정 금지:

```text
intervals-structured-workout.ps1
create-today-workout.ps1
```

그 외 legacy production path 역시 이번 Phase에서는 유지한다.

---

# 50. Java regression

전체 테스트 실행:

```powershell
cd server
.\gradlew.bat clean test
```

기준:

```text
346 / 346
```

새 publisher/client tests가 추가되므로 total 증가 예상.

모든 테스트 PASS 필수.

---

# 51. Python

이번 Phase는 Garmin Python connector와 관계없다.

정상적으로는:

```text
Python source changes = 0
```

이다.

---

# 52. Live Intervals validation

외부 노트북에 credential이 이미 안전하게 구성돼 있을 때만 수행한다.

없으면:

```text
SKIPPED — credential unavailable on this machine
```

로 기록한다.

새 credential을 요구하거나 repository에 저장하지 않는다.

---

# 53. Live validation 안전성

실제 Intervals smoke test를 수행할 경우 사용자의 실제 오늘 훈련을 임의로 덮어쓰지 않는다.

현재 project의 test/safe-date convention을 먼저 확인한다.

안전한 방법이 없으면 live write validation은 생략한다.

Unit/integration regression 완료를 우선한다.

---

# 54. Live validation 기대

안전하게 가능할 경우:

```text
publish #1
→ CREATED 또는 기존 event에 따른 UPDATED

publish #2
→ NO_CHANGE

readback
→ VERIFIED

duplicate
→ 없음
```

실제 remote event ID/API key를 report에 기록하지 않는다.

---

# 55. Verification terminology

상태 표현은 기존 convention을 유지한다.

```text
IMPLEMENTED
UNIT_VERIFIED
SERVER_VERIFIED
DEVICE_VERIFIED
ASSUMED
UNRESOLVED
```

5C-3 live readback 성공 시:

```text
Intervals publisher       SERVER_VERIFIED
Intervals server readback SERVER_VERIFIED
```

가능.

Live validation을 못했다면:

```text
UNIT_VERIFIED
```

까지만.

---

# 56. Documentation

완료 문서에는 최소 다음을 기록한다.

```text
Publisher responsibility
Client responsibility
Authentication
Logical identity
Marker semantics
Legacy compatibility
CREATE/UPDATE/NO_CHANGE
Timeout behavior
Retry policy
Readback verification
Duplicate marker handling
Live validation status
Remaining Garmin limitations
```

---

# 57. Diff review

구현 후:

```powershell
git status
git diff --stat
git diff
```

검토한다.

다음 파일이 들어가면 안 된다.

```text
build/
.gradle/
.venv/
temporary runner
scratch scripts
raw HTTP dump
IDE metadata
.env
credentials
```

---

# 58. Secrets check

commit 전 전체 diff에 대해 검사한다.

예:

```text
api_key
apikey
authorization
bearer
basic
password
secret
token
cookie
athlete
garmin
```

검색 결과는 직접 검토한다.

property name이나 synthetic test credential은 허용된다.

실제 credential은 절대 허용하지 않는다.

---

# 59. Definition of Done

```text
[ ] IntervalsWorkoutClient
[ ] IntervalsWorkoutPublisher
[ ] auth/configuration
[ ] no hardcoded secrets

[ ] remote candidate lookup
[ ] stable logical identity
[ ] RunningAI ownership marker
[ ] legacy marker compatibility 검토

[ ] CREATE
[ ] UPDATE
[ ] NO_CHANGE

[ ] repeated publish idempotency
[ ] changed workout updates same remote event
[ ] duplicate marker ambiguity protection

[ ] blind POST retry 없음
[ ] timeout behavior documented/tested

[ ] create readback
[ ] update readback
[ ] marker verification
[ ] date verification
[ ] workout content verification
[ ] mismatch failure

[ ] HTTP error mapping
[ ] secrets-safe logging

[ ] client tests
[ ] publisher tests
[ ] idempotency tests
[ ] readback mismatch tests

[ ] full Java regression PASS

[ ] DB migration 없음 unless strictly justified
[ ] legacy production files untouched
[ ] Garmin code untouched

[ ] documentation updated
[ ] diff reviewed
[ ] secrets checked

[ ] commit
[ ] push
```

---

# 60. 권장 commit

```text
feat: add Intervals workout publisher
```

현재 repository convention이 다르면 기존 convention을 따른다.

---

# 61. 완료 보고 형식

## Publisher

```text
Input:
Output:
Operations:
Logical identity:
Marker:
Legacy compatibility:
```

## HTTP

```text
Client:
Authentication:
Lookup:
Create:
Update:
Readback:
Timeout:
Retry:
```

## Idempotency

```text
First publish:
Second identical publish:
Changed workout:
Duplicate marker:
Unknown create outcome:
```

## Readback verification

```text
Marker:
Date:
Workout:
Mismatch behavior:
```

## Tests

```text
Java total:
passed:
failed:
new tests:
```

## Live Intervals

```text
attempted:
reason if skipped:
first publish:
second publish:
readback:
duplicate event:
```

실제 ID/credential 기록 금지.

## Database

```text
migration:
schema change:
```

## Legacy

```text
legacy production files modified:
```

## Verification status

```text
Intervals renderer:
Intervals publisher:
Intervals server readback:

Pace Garmin:
%LTHR Garmin:
Treadmill cue Garmin:
```

## Git

```text
branch:
commit:
push:
```

## Remaining limitations

반드시 포함:

```text
Intervals → Garmin transport remains opaque
Garmin pace target remains UNRESOLVED
%LTHR Garmin remains ASSUMED
new Spring treadmill cue still requires device validation
scheduler not implemented
legacy publishing path retained
```

---

# 62. 다음 Phase

Phase 5C-4:

```text
Real Intervals
→ Garmin Connect
→ Garmin Forerunner 265
```

실기기 검증.

최소 세 가지:

```text
A. PACE target
B. %LTHR target
C. treadmill speed/incline cue
```

이번 5C-3에서는 5C-4를 시작하지 않는다.