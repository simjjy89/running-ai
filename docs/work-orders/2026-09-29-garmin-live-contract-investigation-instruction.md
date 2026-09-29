> 원본 작업지시서 (2026-09-29, Phase 3B-1). 구현 기록 / ADR은 `2026-09-29-garmin-live-contract-investigation.md` 참고.

# RunningAI Phase 3B-1
## Garmin 실제 Contract / Authentication / Connector Architecture 조사

## 1. 목적

현재 RunningAI는 다음 상태다.

```text
Phase 1    Spring Boot foundation                COMPLETE
Phase 2    PostgreSQL / Flyway / ActivityRaw     COMPLETE
Phase 3A   Garmin fixture ingestion core         COMPLETE
Phase 3A.5 Claude Code workflow                  COMPLETE
```

현재 latest known commit:

```text
f1e6722 chore: standardize Claude Code project workflow
```

현재 regression baseline:

```text
63 tests
63 passed
0 failed
```

이번 Phase 3B-1의 목표는 Garmin 실제 network integration을 바로 구현하는 것이 아니다.

다음을 확정하는 것이 목적이다.

1. 2026년 현재 사용할 Garmin 접근 방식
2. 인증/session/token 전략
3. 실제 Garmin activity payload contract
4. 기존 synthetic fixture와 실제 payload의 차이
5. Spring Boot와 Garmin connector 사이의 architecture
6. Phase 3B-2 구현 범위

**조사와 contract validation이 끝나기 전 production GarminClient를 구현하지 않는다.**

---

# 2. 반드시 기존 프로젝트 규칙 사용

Repository의 다음 항목을 먼저 읽고 따른다.

```text
CLAUDE.md
.claude/skills/running-ai-dev/SKILL.md
.claude/skills/running-ai-integration/SKILL.md
```

DB 변경이 필요한 경우에만:

```text
.claude/skills/running-ai-database/SKILL.md
```

를 사용한다.

이번 Phase에서는 원칙적으로 DB schema를 변경하지 않는다.

---

# 3. 작업 시작

먼저:

```bash
git status
git branch --show-current
git log --oneline -5
git remote -v
```

를 확인한다.

현재 repository의 Garmin 관련 구현도 읽는다.

특히:

```text
GarminActivityMapper
GarminActivityPayload
GarminActivityIngestionService
ActivityRawService
ActivityService.upsertExternalActivity
Garmin fixture
```

현재 구현을 확인하지 않고 contract를 추측하지 않는다.

---

# 4. Work Order

작업지시서를 저장한다.

```text
docs/work-orders/2026-09-29-garmin-live-contract-investigation-instruction.md
```

결과:

```text
docs/work-orders/2026-09-29-garmin-live-contract-investigation.md
```

이번 결과 문서는 향후 Garmin integration의 Architecture Decision Record 역할도 한다.

---

# 5. 현재 외부 상황 확인

2026년 현재 Garmin Connect 접근 방식을 조사한다.

최소 다음 세 가지를 구분한다.

### A. Garmin 공식 Connect Developer Program

조사:

```text
Activity API
Health API
Training API
승인 방식
personal vs business use
```

RunningAI 같은 개인 프로젝트에 현실적으로 적용 가능한지 평가한다.

공식 API가 존재한다는 이유만으로 사용할 수 있다고 가정하지 않는다.

---

# 6. Garth 상태 확인

과거 RunningAI 또는 Garmin community에서 사용하던:

```text
garth
```

상태를 확인한다.

현재 deprecated 상태라면 신규 architecture dependency로 채택하지 않는다.

기존 token/session이 존재하는 경우에도 그것을 장기 architecture의 전제로 삼지 않는다.

---

# 7. python-garminconnect 조사

현재 유지보수 중인:

```text
cyberjunky/python-garminconnect
```

를 조사한다.

확인할 항목:

```text
latest stable release
Python requirement
license
authentication flow
MFA support
token persistence
token refresh
recent activity fetch
single activity fetch
activity detail fetch
activity splits
known limitations
```

가능하면 stable release를 기준으로 한다.

`master` branch의 아직 release되지 않은 동작에 architecture를 의존하지 않는다.

---

# 8. Version pinning

Live probe가 필요하다면 조사 시점의 안정 버전을 명시적으로 pin한다.

조사 시점에 0.3.16이 최신 stable이면 예:

```text
garminconnect==0.3.16
```

실제 최신 stable을 다시 확인한 후 결정한다.

floating:

```text
garminconnect
```

만 설치해서 재현성이 깨지지 않게 한다.

---

# 9. 오래된 보안 버전 금지

token 저장 관련 과거 security issue가 있으므로 오래된 버전을 사용하지 않는다.

특히 알려진 보안 수정 이전 버전을 dependency로 채택하지 않는다.

현재 stable release가 security fix를 포함하는지 확인한다.

---

# 10. Garmin 인증을 Java로 재구현하지 않는다

이번 Phase에서 다음을 하지 않는다.

```text
Garmin private SSO protocol Java 재구현
OAuth consumer secret reverse engineering
Cloudflare 우회 로직 작성
TLS fingerprint bypass 직접 구현
WAF bypass 직접 구현
rate limit 회피 로직
브라우저 자동화 기반 우회 구현
```

Garmin이 접근을 차단하거나 rate limit을 반환하면 반복적으로 재시도하지 않는다.

정상적으로 사용할 수 있는 유지보수 중인 client의 public interface까지만 조사한다.

---

# 11. Architecture 후보 비교

최소 아래 세 방식을 비교한다.

## Option A — Native Java GarminClient

```text
Spring Boot
   ↓
Java GarminClient
   ↓
Garmin Connect
```

평가:

```text
auth complexity
maintenance burden
Garmin private contract 변화 대응
testability
deployment
```

---

## Option B — Python Garmin Connector

```text
Spring Boot
      ↓
local connector
      ↓
python-garminconnect
      ↓
Garmin Connect
```

Spring Boot는:

```text
business/domain logic
DB
ingestion
analysis
workout generation
scheduler/orchestration
```

를 담당한다.

Python connector는 오직:

```text
Garmin authentication
Garmin read transport
raw JSON retrieval
```

만 담당한다.

---

## Option C — Existing RunningAI collection mechanism

기존 메인 PC RunningAI에 이미 안정적으로 Garmin 데이터를 가져오는 코드가 repository나 접근 가능한 자료에 존재하면 조사한다.

단 현재 repository에서 찾을 수 없다면 추측하지 않는다.

```text
NOT AVAILABLE IN CURRENT REPOSITORY
```

라고 기록한다.

---

# 12. 권장 architecture를 선정

조사 결과를 근거로:

```text
A / B / C
```

중 Phase 3B-2에서 사용할 방향을 하나 제안한다.

평가 기준:

```text
안정성
유지보수성
credential isolation
Spring server와의 결합도
Windows 개발환경
향후 Raspberry Pi Linux 환경
테스트 가능성
Garmin 변화 대응
```

단 아직 live payload를 확인하지 못했다면 최종 확정과 provisional 결정을 구분한다.

---

# 13. 중요한 architecture 원칙

어떤 connector를 선택하더라도 현재 3A 구조는 유지한다.

최종 형태:

```text
Garmin transport
      ↓
raw JsonNode
      ↓
GarminActivityIngestionService
      ↓
activity_raw
      ↓
GarminActivityMapper
      ↓
activity
```

Garmin network library가 직접:

```text
ActivityRepository
JPA Entity
PostgreSQL
```

을 만지게 하지 않는다.

---

# 14. 실제 Garmin Payload Contract 조사

최소 다음 read operation의 payload shape를 조사한다.

```text
recent activities
single activity
activity details
activity splits
```

현재 python-garminconnect 기준으로 대응하는 기능이 있다면 확인한다.

예상되는 개념:

```text
get_activities
get_activity
get_activity_details
get_activity_splits
```

실제 method 이름은 현재 stable source를 source of truth로 한다.

---

# 15. 최소 Activity contract

현재 RunningAI가 normalized Activity에 필요로 하는 필드는:

```text
activityId
activityType
startedAt
durationSeconds
distanceMeters
averageHeartRate
maxHeartRate
```

이다.

실제 Garmin recent activity payload에서 각각 어느 field로 오는지 확인한다.

결과 문서에 표로 작성한다.

예:

| RunningAI | Garmin field | Garmin type | Unit | Required |
|---|---|---|---|---|
| externalId | ? | ? | - | yes |
| activityType | ? | ? | - | yes |
| startedAt | ? | ? | ? | yes |
| durationSeconds | ? | ? | ? | yes |
| distanceMeters | ? | ? | ? | no |
| averageHeartRate | ? | ? | bpm | no |
| maxHeartRate | ? | ? | bpm | no |

실제 검증 없이 `?`를 추측해서 채우지 않는다.

---

# 16. 특히 확인할 field

현재 synthetic fixture에서 사용하고 있는:

```text
activityId
activityType.typeKey
startTime
startTimeGMT
duration
distance
averageHR
maxHR
```

가 실제 응답과 동일한지 확인한다.

---

# 17. Duration 단위 확인

Phase 3A는 synthetic fixture에서:

```text
duration milliseconds
→ durationSeconds 변환
```

으로 구현되어 있다.

이번 Phase에서 반드시 실제 Garmin payload의 `duration` unit을 확인한다.

공개된 현재 Garmin client 자료에서는 seconds일 가능성이 강하게 보이지만:

**live payload 또는 충분히 신뢰할 수 있는 current source로 확정하기 전 production mapper를 변경하지 않는다.**

확인 결과를 Work Order에 기록한다.

---

# 18. Distance 단위

동일하게:

```text
distance
```

의 실제 unit이 meter인지 검증한다.

---

# 19. Time contract

다음 필드의 존재와 의미를 확인한다.

```text
startTimeLocal
startTimeGMT
startTime
```

특히 Garmin의 GMT 문자열이:

```text
UTC이지만 Z가 없는 문자열
```

형태일 가능성을 확인한다.

현재 mapper의:

```text
offset 포함 ISO time 우선
GMT fallback
```

정책이 실제 payload와 맞는지 검증한다.

---

# 20. Activity Type

실제 payload의 구조가:

```text
activityType
  typeId
  typeKey
  parentTypeId
```

등인지 확인한다.

실제 Running:

```text
running
```

Treadmill:

```text
?
```

Indoor Cycling:

```text
?
```

의 `typeKey`를 실제 데이터 또는 current source에서 확인한다.

synthetic alias를 실제 Garmin contract라고 간주하지 않는다.

---

# 21. Summary와 Details의 역할 구분

RunningAI가 활동 하나를 저장하는 데:

```text
recent activities summary
```

만으로 충분한지 검토한다.

그리고 향후 상세 분석에:

```text
activity details
splits/laps
FIT
```

중 무엇이 필요한지 별도로 분리한다.

Phase 3B-2 첫 구현에서는 가능한 한 **summary ingestion만으로 시작**한다.

필요하지 않은 상세 endpoint를 모든 activity마다 무조건 호출하지 않는다.

---

# 22. Garmin Detail 데이터를 Activity에 모두 넣지 않는다

현재 Activity schema를 단순하게 유지한다.

예를 들어 실제 Garmin payload에:

```text
cadence
training effect
elevation
power
VO2max
temperature
recovery
training load
```

등이 있다고 해서 이번 Phase에서 Activity column을 전부 추가하지 않는다.

원본은:

```text
activity_raw.payload JSONB
```

로 보존 가능하다.

향후 Training Analysis 단계에서 필요한 field를 선별한다.

---

# 23. Live Probe — 조건부 수행

환경과 인증이 허용되면 **읽기 전용 live probe**를 수행할 수 있다.

단 다음 원칙을 지킨다.

```text
read only
minimal calls
no write endpoint
no repeated failed login attempts
no credential logging
no token logging
```

Garmin account에 어떠한 data mutation도 하지 않는다.

---

# 24. Credential 취급

다음은 repository나 Work Order에 절대 기록하지 않는다.

```text
Garmin email
Garmin password
MFA code
access token
refresh token
cookies
session content
```

사용자 credential을 command line argument에 직접 넣지 않는다.

가능하면 client가 제공하는 interactive/local credential mechanism을 사용한다.

---

# 25. Token 위치

token/session 파일은 repository 밖에 저장한다.

예를 들어 library default user home storage를 사용한다.

Repository 아래:

```text
running-ai/
```

에 token을 저장하지 않는다.

`.env`에 long-lived token 내용을 기록하지 않는다.

---

# 26. Live Login 실패 정책

로그인 시 다음이 발생하면:

```text
401
403
429
Cloudflare challenge
unexpected auth response
```

자동 반복 로그인을 하지 않는다.

한두 번의 진단 이상으로 계속 retry하지 않는다.

다음을 기록하고 중단한다.

```text
LIVE_AUTH_BLOCKED
```

그 상태에서도 public contract 조사와 architecture 문서는 완료한다.

---

# 27. MFA

MFA가 필요한 경우:

```text
interactive local input
```

을 사용한다.

MFA 값을 파일이나 Work Order에 저장하지 않는다.

---

# 28. Live Payload 저장 주의

실제 Garmin raw JSON을 repository에 그대로 commit하지 않는다.

실제 payload에는:

```text
username
profile id
GPS
location
device info
activity identifiers
personal metrics
```

등이 포함될 수 있다.

---

# 29. Sanitized fixture

실제 payload를 성공적으로 확보했다면 기존 synthetic fixture를 바로 덮어쓰지 않는다.

먼저:

```text
actual raw
↓
field inventory
↓
privacy sanitization
↓
synthetic-but-contract-accurate fixture
```

를 만든다.

삭제/변경 대상 예:

```text
real activityId
user/profile IDs
GPS coordinates
location names
device serial numbers
personal names
timestamps if identifying
```

수치 자체도 필요하면 synthetic value로 변경한다.

중요한 것은:

```text
field names
nesting
types
units
nullable behavior
```

를 보존하는 것이다.

---

# 30. Fixture 보정

Live contract가 확인된 경우:

```text
running
treadmill
indoor cycling
```

fixture를 실제 contract 형태로 보정한다.

단 보정 때문에 현재 테스트가 깨질 경우:

```text
실제 Garmin contract가 source of truth
```

로 두고 mapper를 수정한다.

---

# 31. Mapper 수정 허용 범위

Phase 3B-1에서 live contract가 명확히 확인된 경우에 한해서:

```text
GarminActivityMapper
GarminActivityPayload
fixtures
mapper tests
ingestion tests
```

를 최소 범위로 보정할 수 있다.

다른 production 기능은 변경하지 않는다.

---

# 32. Duration 수정 예

실제로:

```text
duration = seconds
```

임이 확인되면:

현재:

```text
milliseconds → seconds
```

변환을 제거한다.

동시에 regression test를 수정/추가해 unit contract를 고정한다.

---

# 33. Contract regression test

실제 contract 기반 fixture가 확보되면 최소 다음을 검증한다.

```text
actual-shaped RUN fixture
actual-shaped TREADMILL fixture
actual-shaped INDOOR_CYCLING fixture

duration unit
distance unit
start time
activity type
HR
```

fixture 값은 개인정보가 아닌 synthetic value를 사용한다.

---

# 34. Auth Probe Tool

Live contract 확인에 작은 probe가 필요하면 production `server/` 코드에 넣지 않는다.

권장:

```text
tools/garmin-probe/
```

또는 일회성 scratch directory.

Repository에 남길 경우:

```text
credentials 없음
tokens 없음
raw personal data 없음
```

을 반드시 보장한다.

---

# 35. Python Connector 후보

`python-garminconnect`를 사용하는 probe가 필요하면 Python environment를 server Gradle project와 섞지 않는다.

예:

```text
tools/garmin-probe/
  requirements.txt
  probe.py
```

또는 더 적절한 최소 구조를 사용할 수 있다.

하지만 probe가 없어도 충분히 contract를 확정할 수 있다면 불필요한 파일을 만들지 않는다.

---

# 36. 3B-2용 Connector Interface 설계

이번 Phase에서 production client를 구현하지 않지만, 다음 boundary는 문서로 정의한다.

개념:

```text
GarminActivitySource
    fetchRecentActivities(...)
```

반환값은 network-specific Java DTO가 아니라 가능한 한:

```text
raw JSON
```

또는 transport-neutral structure로 ingestion service에 전달한다.

다만 interface 이름과 method는 **Phase 3B-2 구현 전에 확정하지 않아도 된다.**

미래 abstraction을 억지로 코드에 생성하지 않는다.

---

# 37. Python Sidecar를 선택할 경우

Option B가 선정되면 3B-2 architecture를 대략 다음처럼 설계한다.

```text
Spring Boot
    |
    | localhost IPC/HTTP 또는 controlled process boundary
    v
Garmin Connector
    |
    v
python-garminconnect
    |
    v
Garmin Connect
```

하지만 이번 Phase에서 sidecar server 자체를 구현하지 않는다.

---

# 38. Connector 통신 방식 비교

Python connector를 선택한다면 다음 방식도 비교한다.

### Local HTTP

장점:

```text
Spring과 Python 분리
재시작 용이
테스트 용이
향후 Raspberry Pi container화 용이
```

### Subprocess

장점:

```text
구성이 단순
별도 listening port 없음
```

단점:

```text
process lifecycle
error handling
JSON framing
```

Phase 3B-2에 어느 방식이 적절한지 제안한다.

---

# 39. Security boundary

선정 architecture에서:

```text
Garmin credential/token
```

을 가능하면 Spring Boot application DB와 분리한다.

Spring이 raw password를 DB에 저장하는 구조를 만들지 않는다.

---

# 40. Official API 장기 옵션

현재 개인 프로젝트에서 바로 사용하지 못하더라도 Work Order에는:

```text
Garmin Connect Developer Program
```

을 장기적인 공식 migration path로 기록한다.

RunningAI가 향후 서비스/사업 형태가 되는 경우:

```text
unofficial connector
→ official Activity API
```

로 교체 가능한 architecture를 유지한다.

---

# 41. 결과 Architecture Decision

결과 문서에 반드시 아래 형태의 결론을 작성한다.

```text
Decision:
Option A / B / C

Reason:
...

Rejected:
...

Risks:
...

Fallback:
...
```

---

# 42. Contract 결과

예:

```text
Recent Activity Contract

activity id:
activity type:
start time:
duration:
distance:
average HR:
max HR:

Confirmed by:
LIVE / CURRENT_LIBRARY_SOURCE / BOTH
```

`LIVE`와 library-source inference를 명확하게 구분한다.

---

# 43. 신뢰도 표시

각 contract field에 가능하면:

```text
CONFIRMED_LIVE
CONFIRMED_SOURCE
UNCONFIRMED
```

중 하나를 붙인다.

추측을 confirmed로 표시하지 않는다.

---

# 44. 실제 payload 발견 사항

Live probe 성공 시 실제 값 자체를 Work Order에 복사하지 않는다.

예:

```text
activityId is numeric
```

는 기록 가능.

하지만:

```text
activityId = 실제 사용자 ID
```

는 기록하지 않는다.

---

# 45. 이번 Phase에서 DB migration 금지

현재:

```text
V1
V2
V3
```

는 그대로 둔다.

새 data field가 많이 발견되더라도 migration을 만들지 않는다.

ActivityRaw JSONB가 이 목적을 위해 이미 존재한다.

---

# 46. 기존 ingestion semantics 유지

Contract 보정을 하더라도 다음은 유지한다.

```text
raw-first
idempotency
reprocess
externalSource + externalId
Activity PK 유지
ActivityRaw PK 유지
```

---

# 47. 전체 Regression

production mapper/fixture를 수정한 경우:

```powershell
cd server
.\gradlew.bat clean test
```

반드시 수행한다.

baseline:

```text
63 tests
```

새 contract test가 추가되면 총 테스트 개수는 증가할 수 있다.

모든 테스트는 PASS해야 한다.

---

# 48. Network-independent regression

live Garmin 계정이 없어도 기존 server test suite는 전부 PASS해야 한다.

즉 Garmin login이:

```text
gradlew test
```

의 전제조건이 되어서는 안 된다.

---

# 49. Secrets 검사

commit 전 최소 다음을 검사한다.

```text
garmin email
password
token
refresh token
cookie
session
GPS coordinates
real activity id
personal profile information
```

---

# 50. Git

이번 Phase에서 실제 repository 변경이 발생했다면:

```bash
git status
git diff
```

를 확인한다.

테스트 성공 후 commit한다.

권장 commit:

```text
docs: define Garmin live integration strategy
```

실제 mapper/fixture contract까지 수정됐다면:

```text
refactor: align Garmin ingestion with live contract
```

등 현재 변경 내용에 맞는 commit을 선택한다.

---

# 51. Push

검증 완료 후:

```bash
git push origin main
```

한다.

단 live credentials/token/raw personal payload는 절대 push하지 않는다.

---

# 52. 최종 보고

다음 형식을 따른다.

## Research

```text
Official Garmin API:
garth:
python-garminconnect:
latest tested/studied version:
```

## Architecture decision

```text
selected:
reason:
rejected alternatives:
```

## Authentication

```text
method:
MFA:
token persistence:
credential storage:
failure/rate-limit policy:
```

민감한 실제 값은 쓰지 않는다.

## Live probe

```text
attempted:
success:
read operations used:
```

## Actual contract

| Field | Garmin field | Unit | Confidence |
|---|---|---|---|
| externalId | | | |
| activityType | | | |
| startedAt | | | |
| duration | | | |
| distance | | | |
| average HR | | | |
| max HR | | | |

## Synthetic 3A differences

```text
duration:
activityType:
time:
other:
```

## Code changes

```text
mapper:
fixtures:
tests:
probe:
```

## Regression

```text
total:
passed:
failed:
```

## Git

```text
branch:
commit:
push:
```

## Phase 3B-2 recommendation

구현할 connector 구조와 정확한 범위를 제안한다.

**3B-2를 자동으로 시작하지 않는다.**

---

# 53. 이번 Phase의 성공 조건

성공 기준은 Garmin에서 데이터를 많이 가져오는 것이 아니다.

다음 질문에 확실히 답할 수 있어야 한다.

```text
1. RunningAI는 2026년 현재 어떤 방식으로 Garmin을 읽을 것인가?

2. 인증/session은 어느 component가 책임질 것인가?

3. Spring Boot는 Garmin credential을 직접 알아야 하는가?

4. Garmin recent activity의 실제 field와 unit은 무엇인가?

5. 3A synthetic fixture 중 무엇을 수정해야 하는가?

6. 3B-2에서 구현해야 할 코드가 정확히 무엇인가?
```

이 질문에 근거 있게 답할 수 있으면 Phase 3B-1은 완료다.
