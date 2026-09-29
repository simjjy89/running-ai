Phase 3C-1을 진행해줘.

CLAUDE.md와 running-ai-dev / running-ai-integration /
running-ai-database skill을 따라.

작업지시서:
docs/work-orders/2026-09-30-garmin-incremental-sync-instruction.md

필요한 파일만 읽고 기존 architecture를 재조사하지 마.
구현 → regression → 가능하면 live validation → 문서화 →
secrets 검사 → commit → push까지 진행하고
Phase 3C-2는 시작하지 마.

# RunningAI Phase 3C-1
## Garmin Incremental Sync / High-Water Mark / Overlap Window

## 1. 목표

Phase 3B까지 실제 Garmin activity의 전체 경로가 검증됐다.

```text
Garmin Connect
→ Python Garmin Connector
→ Spring HttpGarminActivitySource
→ GarminSyncService
→ GarminActivityIngestionService
→ activity_raw
→ activity
→ PostgreSQL
```

실제 Garmin activity로 다음도 검증 완료:

```text
live authentication        PASS
live fetch                 PASS
Spring transport           PASS
raw persistence            PASS
normalization              PASS
second sync idempotency    PASS
```

이번 Phase의 목적은 현재의:

```text
최근 N개를 매번 전부 sync
```

방식에서:

```text
마지막 성공 sync 위치를 기억하고
필요한 최근 범위만 다시 조회하는
incremental sync
```

구조로 전환하는 것이다.

---

# 2. 프로젝트 규칙

반드시 다음을 따른다.

```text
CLAUDE.md

running-ai-dev
running-ai-integration
running-ai-database
```

이번 작업에는 DB schema 변경이 있으므로 database skill도 사용한다.

---

# 3. Git 상태 확인

먼저:

```bash
git status
git branch --show-current
git log --oneline -5
git remote -v
```

를 실행한다.

Phase 3B-3 local commit:

```text
cf4c04f
```

가 origin/main에 아직 없다면 현재 GitHub 인증 상태를 확인한다.

사용자 credential을 새로 만들거나 저장하지 않는다.

인증이 이미 가능하다면 해당 commit을 push한 뒤 3C-1 작업을 시작한다.

---

# 4. Work Order

저장:

```text
docs/work-orders/
2026-09-30-garmin-incremental-sync-instruction.md
```

결과:

```text
docs/work-orders/
2026-09-30-garmin-incremental-sync.md
```

---

# 5. 핵심 설계

단순 timestamp cursor만 사용하지 않는다.

다음 전략을 사용한다.

```text
High-water mark
+
Overlap window
+
Idempotent ingestion
```

개념:

```text
last high-water
       │
       ├─────────────┐
       │             │
       ▼             ▼
   overlap 시작      최신 Garmin
       │─────────────│
          재조회
```

기본 overlap:

```text
7 days
```

으로 한다.

설정 가능하게 만든다.

---

# 6. 왜 overlap이 필요한가

다음을 고려한다.

```text
Garmin sync 지연
기기에서 며칠 뒤 업로드
Garmin activity 수정
거리/HR/title 변경
timezone/metadata 보정
```

따라서:

```text
lastActivityStartedAt 이후만 조회
```

하면 늦게 변경된 기존 activity를 놓칠 수 있다.

---

# 7. 새로운 DB state

Garmin sync 진행 상태를 저장한다.

과도하게 generic한 framework를 만들지 않는다.

권장:

```text
garmin_sync_state
```

Athlete별 한 행.

예:

```text
id
athlete_id
high_water_started_at
last_successful_sync_at
created_at
updated_at
```

---

# 8. Sync state 의미

### high_water_started_at

Garmin upstream에서 성공적으로 처리한 범위 중:

```text
가장 최신 parseable activity start time
```

을 나타낸다.

### last_successful_sync_at

RunningAI에서 incremental sync 전체가 성공한 시각.

둘은 다른 의미다.

---

# 9. Athlete 관계

현재 single-user지만 Athlete model이 존재하므로:

```text
garmin_sync_state.athlete_id
```

를 둔다.

Athlete당 Garmin sync state는 하나다.

DB unique constraint를 둔다.

---

# 10. Flyway

기존:

```text
V1
V2
V3
```

는 절대 수정하지 않는다.

새 migration:

```text
V4__create_garmin_sync_state.sql
```

또는 현재 naming convention에 맞는 정확한 이름을 사용한다.

---

# 11. Entity

예:

```text
GarminSyncState
```

필드:

```text
id
athlete
highWaterStartedAt
lastSuccessfulSyncAt
createdAt
updatedAt
```

시간은 기존 정책대로:

```text
Instant
TIMESTAMPTZ
UTC
```

를 사용한다.

---

# 12. Repository / Service

필요 최소 수준으로:

```text
GarminSyncStateRepository
GarminSyncStateService
```

를 구현한다.

책임:

```text
Athlete sync state 조회
최초 생성
성공 후 checkpoint update
```

---

# 13. Cursor는 activityId가 아니다

Garmin activity ID가 증가 순서를 보장한다고 가정하지 않는다.

따라서:

```text
externalId
```

를 cursor로 사용하지 않는다.

---

# 14. 동일 timestamp 문제

여러 활동의:

```text
startTimeGMT
```

가 동일할 가능성을 고려한다.

이를 위해 timestamp를 exclusive cursor로 사용하지 않는다.

Overlap window + idempotency가 이를 해결한다.

---

# 15. Connector pagination

현재 connector:

```http
GET /activities?limit=N
```

에 pagination offset을 추가한다.

권장:

```http
GET /activities?start=0&limit=50
```

validation:

```text
start >= 0
1 <= limit <= 100
```

기존 호출:

```text
GET /activities?limit=N
```

은 계속:

```text
start=0
```

으로 동작해야 한다.

---

# 16. python-garminconnect

실제 stable API의:

```text
get_activities(start, limit)
```

signature를 source에서 확인한다.

추측하지 않는다.

---

# 17. Connector는 그대로 raw 반환

pagination을 추가해도:

```text
Garmin dict
→ JSON
```

구조는 유지한다.

필드 rename/normalization 금지.

---

# 18. Spring source

GarminActivitySource가 pagination을 지원하게 한다.

개념:

```java
List<JsonNode> fetchActivities(int start, int limit)
```

현재:

```java
fetchRecentActivities(limit)
```

가 있다면 기존 사용처와 테스트를 확인하여 가장 단순하게 확장한다.

불필요한 compatibility layer를 만들지 않는다.

---

# 19. Incremental Sync Algorithm

기본 흐름:

```text
1. GarminSyncState 조회

2. state 없음
   → bootstrap sync

3. state 있음
   → cutoff 계산

4. Garmin activities를 newest → oldest 순으로 page fetch

5. cutoff까지 필요한 page 수집

6. 각 payload를 기존 ingestion service로 처리

7. 전체 sync가 안전하게 완료됐을 때만 state advance
```

---

# 20. Bootstrap

sync state가 없는 최초 실행은 무제한 과거 history를 가져오지 않는다.

기본:

```text
첫 page 50건
```

정도만 처리한다.

설정 가능하게 한다.

예:

```text
page-size = 50
```

첫 bootstrap에서는 한 page만으로 충분하다.

historical backfill은 별도 future Phase다.

---

# 21. Incremental cutoff

state가 존재하면:

```text
cutoff =
highWaterStartedAt - overlap
```

기본:

```text
overlap = 7 days
```

---

# 22. Pagination

Garmin newest → oldest 순으로 page를 가져온다.

예:

```text
start=0  limit=50
start=50 limit=50
start=100 limit=50
...
```

각 page의 가장 오래된 parseable startTimeGMT가:

```text
cutoff 이전
```

으로 충분히 내려가면 pagination을 중단한다.

---

# 23. Overlap 범위는 다시 ingest

이미 DB에 있는 activity라도 overlap 안에 있으면 다시 ingest한다.

기존 idempotency가:

```text
INSERT
또는
UPDATE
```

를 결정한다.

이것이 Garmin 수정 데이터 반영 방식이다.

---

# 24. High-water 계산

sync 성공 후 high-water는:

```text
기존 high-water
vs
이번 fetch에서 발견한 최신 parseable startTimeGMT
```

중 더 최신 값으로 갱신한다.

시간이 뒤로 이동하지 않는다.

---

# 25. Unsupported Activity

예:

```text
SWIMMING
WALKING
STRENGTH
```

은 기존 정책대로:

```text
raw 저장
skipped +1
```

한다.

unsupported activity도 정상적인 Garmin activity이므로 sync 자체는 실패가 아니다.

---

# 26. Malformed Activity

예:

```text
activityId 없음
startTimeGMT 파싱 불가
```

등은:

```text
failed +1
```

로 처리한다.

---

# 27. Cursor advance 실패 정책

이번 sync에서:

```text
failed > 0
```

이면 high-water를 advance하지 않는다.

이유:

```text
처리 실패 데이터를 cursor가 지나쳐
영구적으로 놓치는 것을 방지
```

한다.

이미 성공한 Activity/ActivityRaw transaction은 rollback하지 않는다.

다음 sync에서 overlap/idempotency로 재처리한다.

---

# 28. Connector-level failure

다음은 즉시 sync abort:

```text
AUTH_REQUIRED
403
429
connector unavailable
Garmin upstream failure
timeout
invalid response
```

이 경우에도:

```text
GarminSyncState
```

를 advance하지 않는다.

---

# 29. Partial processing

예:

```text
page 1 ingestion 성공
page 2 connector failure
```

가 발생해도 page 1의 Activity 저장은 남아도 된다.

다음 run에서 다시 overlap fetch하며 idempotency로 처리한다.

Sync state만 advance하지 않는다.

---

# 30. Max pages

무한 pagination 방지를 위해:

```text
max-pages
```

설정을 둔다.

예:

```text
10
```

기본 page-size=50이면 최대 500 activities.

일반 개인 계정에는 충분하다.

---

# 31. Max pages 도달

cutoff에 도달하기 전에 max-pages를 모두 사용했다면:

```text
성공으로 처리하지 않는다.
```

state를 advance하지 않는다.

명확한:

```text
INCREMENTAL_WINDOW_INCOMPLETE
```

계열 오류 또는 이에 준하는 결과를 반환한다.

---

# 32. Configuration

예:

```yaml
running-ai:
  garmin:
    sync:
      page-size: 50
      overlap: 7d
      max-pages: 10
```

Spring Duration binding 방식에 맞춘다.

실제 property naming은 현재 config convention을 따른다.

---

# 33. Sync result

기존 GarminSyncResult를 확장하거나 새 incremental result를 만든다.

과도하게 크게 만들지 않는다.

유용한 값:

```text
fetched
created
updated
skipped
failed
pagesFetched
checkpointAdvanced
highWaterStartedAt
```

실제 existing result 구조를 조사 후 최소 변경한다.

---

# 34. Scheduler 금지

이번 Phase에서는 여전히:

```java
@Scheduled
```

를 추가하지 않는다.

---

# 35. Public API 금지

아직:

```http
POST /api/v1/garmin/sync
```

를 추가하지 않는다.

운영 trigger는 Phase 3C-2.

---

# 36. Connector supervision 금지

Python connector process 자동 실행/감시는 이번 범위가 아니다.

---

# 37. Initial state 테스트

sync state 없는 상태에서:

```text
latest page fetch
→ ingestion
→ high-water 생성
→ lastSuccessfulSyncAt 설정
```

을 검증한다.

---

# 38. Second incremental sync 테스트

state가 있는 상태에서:

```text
cutoff = highWater - overlap
```

가 적용되는지 확인한다.

필요 이상의 old page를 fetch하지 않는지도 검증한다.

---

# 39. Late update 테스트

예:

```text
기존 activity
start time = highWater - 2 days
payload 변경
```

7-day overlap 안에 있으므로 다음 sync에서 다시 fetch되고 Activity가 update되어야 한다.

---

# 40. Same timestamp 테스트

서로 다른 externalId이지만:

```text
startTimeGMT 동일
```

한 activity 2건이 있어도 둘 다 처리되어야 한다.

exclusive timestamp cursor로 하나가 누락되면 안 된다.

---

# 41. Idempotency 테스트

같은 incremental window를 반복 실행해도:

```text
Activity row count
ActivityRaw row count
```

가 증가하지 않아야 한다.

---

# 42. Unsupported 테스트

overlap window 안의 unsupported activity는:

```text
skipped
raw persisted
```

되어야 하며 checkpoint는 정상 advance 가능하다.

---

# 43. Malformed 테스트

malformed activity 발생 시:

```text
failed > 0
checkpointAdvanced = false
```

를 검증한다.

---

# 44. Connector failure 테스트

pagination 도중 connector failure:

```text
checkpoint 미갱신
```

을 검증한다.

---

# 45. Max page 테스트

cutoff에 도달하기 전에 max-pages 초과:

```text
state 미갱신
```

을 검증한다.

---

# 46. Database migration test

Flyway:

```text
V1
V2
V3
V4
```

가 H2와 PostgreSQL에서 정상 동작하도록 한다.

기존 migration은 수정하지 않는다.

---

# 47. Regression

Java 전체:

```powershell
cd server
.\gradlew.bat clean test
```

전부 PASS해야 한다.

Python connector 변경이 있으므로:

```powershell
cd tools\garmin-connector
.\.venv\Scripts\python.exe -m pytest
```

도 전부 PASS해야 한다.

---

# 48. Live smoke validation

Regression 완료 후 현재 준비된 Garmin connector와 local PostgreSQL을 사용해 manual live validation을 수행할 수 있다.

최소:

```text
incremental sync 1회
incremental sync 2회
```

를 수행한다.

실제 identifier/raw payload는 문서에 남기지 않는다.

---

# 49. Live 기대

첫 3C sync가 state 없는 bootstrap이라면:

```text
checkpoint created = true
high-water exists
lastSuccessfulSyncAt exists
```

두 번째 sync:

```text
overlap fetch
duplicates 없음
checkpoint 정상 유지/advance
```

를 확인한다.

---

# 50. Secrets

다음은 절대 commit하지 않는다.

```text
Garmin email
password
MFA
token
cookies
raw personal Garmin JSON
GPS
real activity IDs
.env
.venv
```

---

# 51. Definition of Done

완료 조건:

```text
[ ] V4 garmin_sync_state migration
[ ] GarminSyncState entity/repository/service
[ ] high-water persistence
[ ] last successful sync persistence
[ ] connector start pagination
[ ] Spring pagination support
[ ] bootstrap sync
[ ] incremental cutoff
[ ] 7-day overlap
[ ] multi-page fetch
[ ] max-pages guard
[ ] unsupported SKIP
[ ] malformed failure prevents checkpoint advance
[ ] connector failure prevents checkpoint advance
[ ] late update captured
[ ] same timestamp activities preserved
[ ] repeat sync idempotent
[ ] H2/Flyway tests PASS
[ ] PostgreSQL validation PASS where available
[ ] Python tests PASS
[ ] Java tests PASS
[ ] manual live validation if environment available
[ ] docs updated
[ ] secrets scan
[ ] git diff review
[ ] commit
[ ] push
```

---

# 52. 권장 commit

```text
feat: add incremental Garmin activity sync
```

---

# 53. 완료 보고

## Incremental strategy

```text
checkpoint:
overlap:
page size:
max pages:
```

## Database

```text
migration:
table:
high-water:
last successful sync:
```

## Bootstrap

```text
pages:
fetched:
created:
updated:
skipped:
failed:
checkpoint:
```

## Incremental second run

```text
pages:
fetched:
created:
updated:
skipped:
failed:
checkpoint advanced:
```

## Failure safety

```text
malformed:
connector failure:
max pages:
```

## Tests

```text
Java total:
passed:
failed:

Python total:
passed:
failed:
```

## Live validation

```text
attempted:
bootstrap:
second sync:
duplicate rows:
```

실제 Garmin identifier는 기록하지 않는다.

## Git

```text
branch:
commit:
push:
```

## Next Phase

Phase 3C-2:

```text
operational sync trigger
manual command/API
sync status/result 조회
```

3C-2는 자동 시작하지 않는다.

---

# 54. 핵심 invariant

이번 Phase가 끝난 뒤 구조는:

```text
Garmin
  ↓
paged recent activities
  ↓
overlap window
  ↓
idempotent ingestion
  ↓
PostgreSQL

       +
       
garmin_sync_state
  ├─ high-water
  └─ last successful sync
```

여야 한다.

**Cursor가 데이터 정합성보다 앞서가면 안 된다.**

**실패한 sync는 checkpoint를 advance하면 안 된다.**

**이미 처리한 overlap 데이터는 idempotency로 안전하게 재처리한다.**
