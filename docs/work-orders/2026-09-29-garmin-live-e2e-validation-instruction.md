현재 origin/main 최신 상태에서 Phase 3B-3만 진행해줘. 실제 Garmin 로그인/token은 이미 이 PC에 준비돼 있다. CLAUDE.md와 running-ai-dev/running-ai-integration skill을 따르고, 최근 activity 1건만 이용해 Connector → Spring → PostgreSQL E2E와 두 번째 sync idempotency를 검증해. 실제 activity ID, 이름, GPS, token, credential, raw payload는 문서/fixture/git에 남기지 마. activityName mojibake는 어느 layer에서 생기는지 진단한 뒤 안전한 경우에만 수정해. scheduler/cursor/public sync API는 만들지 말고, regression → 문서화 → secrets 검사 → commit → push까지 진행한 뒤 멈춰.

# RunningAI Phase 3B-3
## Garmin Live End-to-End Validation + activityName Encoding Diagnosis

## 1. 현재 상태

현재 RunningAI는 다음 단계까지 완료되어 있다.

```text
Phase 1     Spring Boot foundation                  COMPLETE
Phase 2     PostgreSQL / Flyway / ActivityRaw       COMPLETE
Phase 3A    Garmin ingestion core                   COMPLETE
Phase 3A.5  Claude Code workflow                    COMPLETE
Phase 3B-1  Garmin contract / architecture 조사    COMPLETE
Phase 3B-2  Python connector + Spring bridge        COMPLETE
```

현재 known latest commit:

```text
2f978c2 feat: connect Garmin transport to RunningAI server
```

현재 regression baseline:

```text
Java:   83 passed
Python: 31 passed
```

실제 Garmin 계정으로 다음까지 live 확인됨:

```text
Garmin login                     SUCCESS
token persistence                SUCCESS
GET /activities?limit=1          SUCCESS
actual activityType.typeKey      treadmill_running
actual startTimeGMT              present
actual duration                  seconds
actual distance                  metres
actual averageHR / maxHR         present
```

따라서 Garmin activity contract는 이제 주요 필드 기준 `CONFIRMED_LIVE`로 승격 가능하다.

단 실제 `activityName` 한글 문자열이 connector 응답에서 mojibake 상태로 확인됐다.

예:

```text
ì´... Fartlek
```

PowerShell의 `OutputEncoding=UTF-8` 변경 후에도 동일하므로 단순 PowerShell 표시 문제로 단정하지 않는다.

---

# 2. 이번 Phase 목표

이번 작업은 두 가지다.

## A. 실제 Garmin E2E 검증

다음 전체 경로를 실제 Garmin activity 1건으로 검증한다.

```text
Garmin Connect
      ↓
python-garminconnect
      ↓
Garmin Connector
      ↓ localhost HTTP
HttpGarminActivitySource
      ↓
GarminSyncService
      ↓
GarminActivityIngestionService
      ↓
ActivityRaw
      ↓
GarminActivityMapper
      ↓
Activity
      ↓
PostgreSQL
```

그리고 동일 activity를 다시 sync하여 실제 데이터 기준 idempotency도 검증한다.

## B. activityName encoding 원인 진단

다음 중 어느 단계에서 한글이 깨지는지 확인한다.

```text
Garmin upstream response
→ python-garminconnect returned Python object
→ connector serialization
→ FastAPI HTTP response
→ Spring JsonNode
→ PowerShell display
```

원인을 확인한 뒤 필요한 경우 최소 범위로 수정한다.

---

# 3. 이번 Phase에서 하지 않는 것

다음은 구현하지 않는다.

```text
incremental sync cursor
scheduler
@Scheduled
Windows Task Scheduler
public Garmin sync REST API
ChatGPT integration
Intervals.icu
workout generation
FIT parsing
splits ingestion
Activity Detail ingestion
connector process supervision
Raspberry Pi deployment
DB schema 확장
```

Phase 3C를 자동으로 시작하지 않는다.

---

# 4. 프로젝트 규칙

먼저 반드시 읽는다.

```text
CLAUDE.md
.claude/skills/running-ai-dev/SKILL.md
.claude/skills/running-ai-integration/SKILL.md
```

DB 관련 변경이 필요할 경우에만:

```text
.claude/skills/running-ai-database/SKILL.md
```

를 사용한다.

이번 Phase에서는 DB migration이 없어야 한다.

---

# 5. 작업 시작

먼저:

```bash
git status
git branch --show-current
git log --oneline -5
git remote -v
```

를 확인한다.

현재 실제 connector와 Spring 구현을 먼저 읽는다.

특히:

```text
tools/garmin-connector/
GarminActivitySource
HttpGarminActivitySource
GarminSyncService
GarminActivityIngestionService
GarminActivityMapper
ActivityRawService
ActivityService
```

를 조사한다.

---

# 6. Work Order

지시서 원문:

```text
docs/work-orders/
2026-09-29-garmin-live-e2e-validation-instruction.md
```

결과:

```text
docs/work-orders/
2026-09-29-garmin-live-e2e-validation.md
```

결과 문서에는 실제 개인정보나 실제 raw payload를 넣지 않는다.

---

# 7. 실제 Garmin credential 처리

현재 host에는 이미 Garmin login/token이 성공한 상태다.

기존 token store를 재사용한다.

다음을 하지 않는다.

```text
password 재입력 요구
token 출력
token 복사
token repository 저장
token Spring 설정 저장
```

Spring은 Garmin credential을 몰라야 한다.

---

# 8. Connector 상태 확인

먼저 connector가 정상인지 확인한다.

예:

```powershell
cd C:\running-ai-github\tools\garmin-connector
.\.venv\Scripts\Activate.ps1

python -m garmin_connector status
```

그리고:

```powershell
python -m garmin_connector activities --limit 1
```

실제 Garmin 요청은 최소화한다.

---

# 9. Connector 실행

필요 시:

```powershell
python -m garmin_connector serve
```

기본:

```text
127.0.0.1:8765
```

만 사용한다.

외부 bind하지 않는다.

---

# 10. Live Contract 최종 갱신

실제 account에서 이미 다음이 확인됐다.

```text
activityId             present
activityType.typeKey   treadmill_running
startTimeGMT           yyyy-MM-dd HH:mm:ss
duration               seconds
distance               metres
averageHR              bpm
maxHR                  bpm
```

ADR / integration skill에서 해당 항목을:

```text
CONFIRMED_SOURCE
```

에서 가능한 범위 내:

```text
CONFIRMED_LIVE
```

로 갱신한다.

실제 값 자체는 문서에 남기지 않는다.

---

# 11. E2E 실행 방법

현재 public REST sync endpoint가 없으므로 다음 중 가장 작은 방법을 선택한다.

## Option A — temporary application runner

예:

```text
Spring profile 또는 one-shot runner
→ GarminSyncService.syncRecent(1)
→ 결과 출력
→ 검증 후 제거
```

## Option B — integration harness

예:

```text
scripts/dev/
tools/
```

아래에 local-only validation harness를 작성한다.

## Option C — test profile이 아닌 local command

기존 architecture를 깨지 않고 `GarminSyncService`를 직접 호출하는 최소 방법을 사용한다.

---

# 12. 금지

단순 검증을 위해:

```text
POST /api/v1/garmin/sync
```

같은 운영 REST endpoint를 새로 만들지 않는다.

이번 Phase는 E2E validation이지 운영 trigger 구현이 아니다.

---

# 13. PostgreSQL

실제 local PostgreSQL을 사용한다.

가능하면 기존:

```text
docker compose
```

환경을 사용한다.

Docker가 없거나 기존 PostgreSQL instance가 있다면 안전한 local DB를 사용한다.

production DB 개념은 없다.

---

# 14. Database safety

실제 검증 전:

```text
activity
activity_raw
```

현재 상태를 확인한다.

기존 RunningAI local test data가 있으면 무조건 truncate하지 않는다.

이번 live activity가 어떤 row인지 external key로 식별한다.

---

# 15. First sync

실제 최근 Garmin activity 1건만 가져온다.

개념:

```text
syncRecent(1)
```

기대:

```text
fetched = 1
created = 1
updated = 0
skipped = 0
failed = 0
```

단 동일 activity가 이미 DB에 있다면:

```text
created = 0
updated = 1
```

도 정상이다.

결과 문서에 상황을 명확하게 적는다.

---

# 16. ActivityRaw 확인

실제 DB에서 해당 row를 확인한다.

검증:

```text
external_source = GARMIN
external_id exists
payload is JSONB
activity_id linked
fetched_at exists
created_at exists
```

실제 `external_id` 값은 결과 문서에 작성하지 않는다.

---

# 17. Activity normalized 확인

실제 DB에서 확인:

```text
activity_type = TREADMILL_RUN
duration_seconds ≈ 1801
distance_meters ≈ 5393
average_heart_rate = 153
max_heart_rate = 170
```

실제 live 확인값과 일관되는지만 검증한다.

정확한 identifying activity ID는 문서에 쓰지 않는다.

---

# 18. Time 확인

실제 Garmin:

```text
startTimeGMT
```

가 UTC 기준으로 Activity.startedAt에 올바르게 저장됐는지 확인한다.

예:

```text
Garmin startTimeGMT
→ UTC Instant
```

offset/timezone 착오가 없는지 검증한다.

---

# 19. Second sync

같은 recent activity를 다시 한 번:

```text
syncRecent(1)
```

한다.

기대:

```text
fetched = 1
created = 0
updated = 1
skipped = 0
failed = 0
```

---

# 20. Real-data idempotency

두 번째 sync 이후 확인:

```text
Activity row count       증가 없음
ActivityRaw row count    증가 없음

Activity.id              동일
ActivityRaw.id           동일

Activity.createdAt       유지
Activity.updatedAt       갱신 가능

ActivityRaw.createdAt    유지
ActivityRaw.fetchedAt    갱신
```

이를 결과 문서에 기록한다.

---

# 21. Raw payload 보안

PostgreSQL에 실제 Garmin raw JSONB가 저장되는 것은 현재 architecture상 의도된 동작이다.

하지만 다음에는 노출하지 않는다.

```text
console 전체 출력
Work Order 전체 payload 복사
Git fixture
README
test fixture
logs
```

---

# 22. activityName Encoding 진단

다음 layer를 순서대로 확인한다.

## Layer 1 — python-garminconnect return object

connector serialization 전에 activity dict의:

```python
type(activity["activityName"])
repr(activity["activityName"])
```

를 local diagnostic으로 확인한다.

실제 문자열 내용 전체를 문서/로그에 남기지 않는다.

필요하다면 다음만 기록한다.

```text
valid unicode:
contains mojibake:
```

---

# 23. Python codepoint 진단

필요한 경우 실제 문자열 내용을 출력하지 않고 codepoint / encoding 가능 여부를 조사한다.

예:

```python
name = activity.get("activityName")
print(type(name))
print(name.encode("utf-8", errors="strict") is not None)
```

또는 안전한 diagnostic helper를 사용한다.

개인 activity name 자체를 영구 로그에 저장하지 않는다.

---

# 24. Layer 2 — FastAPI serialization

python object에서는 정상인데 HTTP response에서 깨지는지 확인한다.

가능하면 test client 또는 local request로:

```text
response headers
Content-Type
charset
raw bytes
```

를 조사한다.

정상 기대:

```text
application/json
UTF-8 JSON
```

---

# 25. Layer 3 — HTTP raw bytes

PowerShell display를 거치기 전 raw HTTP bytes를 확인한다.

필요하면 Python 자체 client로 localhost connector를 호출하여:

```python
response.content
response.encoding
response.json()
```

을 확인한다.

실제 이름 전체를 commit하지 않는다.

---

# 26. Layer 4 — Spring JsonNode

Spring `HttpGarminActivitySource`가 실제 connector 응답을 받아:

```text
JsonNode.activityName
```

을 어떻게 읽는지 확인한다.

Python에서는 정상인데 Spring에서 깨지면 Java HTTP decoding 문제를 조사한다.

---

# 27. Layer 5 — PowerShell

Python / FastAPI / Spring 모두 정상인데 PowerShell에서만 깨지면:

```text
Windows PowerShell 5.1 display issue
```

로 분류한다.

그 경우 production connector 코드를 수정하지 않는다.

---

# 28. 임의 latin1→utf8 변환 금지

원인을 확인하기 전 다음과 같은 코드를 production에 넣지 않는다.

```python
value.encode("latin1").decode("utf-8")
```

또는 Java에서 유사한 강제 변환을 넣지 않는다.

정상 Unicode 문자열을 망가뜨릴 수 있다.

---

# 29. 인코딩 수정 허용 조건

실제로 connector 또는 library boundary에서 잘못 decode된 것이 확인됐을 때만 수정한다.

수정 위치는 가능한 upstream에 가깝게 한다.

```text
garminconnect result
→ connector response
```

사이에서 문제가 있다면 connector boundary에서 해결한다.

Spring mapper에 activityName 보정 로직을 넣지 않는다.

---

# 30. python-garminconnect 내부 문제일 경우

문제가 dependency 내부에 있으면 다음을 구분한다.

```text
library bug
Garmin upstream header issue
curl_cffi response decoding issue
```

가능하면 monkey patch보다 connector boundary의 안전한 최소 workaround를 검토한다.

다만 workaround가 모든 문자열에 안전하다는 근거가 없으면 구현하지 않는다.

---

# 31. activityName은 normalized Activity 필수가 아님

현재 RunningAI normalized Activity에는 activityName이 필수가 아니다.

따라서 인코딩 문제가 unresolved여도:

```text
E2E ingestion
```

자체는 성공할 수 있다.

인코딩 문제로 전체 Phase를 실패 처리하지 않는다.

---

# 32. 별도 issue / TODO

인코딩을 안전하게 해결할 수 없다면 결과에:

```text
GARMIN_ACTIVITY_NAME_ENCODING
```

known issue로 남긴다.

향후 activityName을 normalized field로 사용하기 전에 반드시 해결한다.

---

# 33. Python regression

connector 코드가 변경되면:

```powershell
cd tools\garmin-connector
.\.venv\Scripts\python.exe -m pytest
```

실행한다.

baseline:

```text
31 passed
```

신규 테스트가 추가되면 증가 가능.

---

# 34. Java regression

Spring 코드가 변경되면:

```powershell
cd server
.\gradlew.bat clean test
```

실행한다.

baseline:

```text
83 passed
```

신규 테스트가 추가되면 증가 가능.

---

# 35. No-network regression 유지

정규 regression suite는 여전히 Garmin live network 없이 통과해야 한다.

live E2E를 일반:

```text
gradlew test
pytest
```

에 포함하지 않는다.

---

# 36. Live test 분리

실제 Garmin account가 필요한 검증은 명시적으로:

```text
manual live validation
```

으로 유지한다.

CI나 일반 regression이 Garmin credential을 요구하지 않게 한다.

---

# 37. Secrets 검사

commit 전 반드시 확인한다.

```text
Garmin email
password
MFA
access token
refresh token
cookies
session
actual activity ID
GPS coordinates
activityName real value
profile identifier
```

실제 사용자의 Garmin activity ID를 fixture나 문서에 복사하지 않는다.

---

# 38. 로그 검사

특히 diagnostic 과정에서 임시:

```text
print(payload)
logger.debug(payload)
repr(full object)
```

등이 남아있지 않은지 확인한다.

---

# 39. Temporary harness 처리

E2E용으로 임시 runner/harness를 만든 경우:

- 향후 운영에도 의미가 있으면 명확한 dev tool로 남길 수 있다.
- 오직 일회성이라면 검증 후 제거한다.

불필요한 debug endpoint는 남기지 않는다.

---

# 40. Migration

이번 Phase:

```text
new migration = NO
schema change = NO
```

가 기본이다.

V1–V3를 수정하지 않는다.

---

# 41. Documentation 갱신

다음 내용을 결과 문서와 integration skill에 반영한다.

```text
Garmin live login confirmed
recent activity read confirmed
treadmill_running confirmed live
duration seconds confirmed live
distance metres confirmed live
startTimeGMT confirmed live
HR fields confirmed live
```

실제 값 자체는 기록하지 않는다.

---

# 42. Phase 3B 완료 판정

다음이 모두 성공하면 Phase 3B 전체를 완료 처리한다.

```text
Garmin auth           LIVE PASS
Garmin fetch          LIVE PASS
connector HTTP        LIVE PASS
Spring client         LIVE PASS
GarminSyncService     LIVE PASS
ActivityRaw save      LIVE PASS
Activity normalize    LIVE PASS
PostgreSQL save       LIVE PASS
second sync           LIVE PASS
idempotency           LIVE PASS
```

---

# 43. Definition of Done

```text
[ ] CLAUDE.md / skills 적용
[ ] live connector status 확인
[ ] recent activity 1건 fetch
[ ] live contract CONFIRMED_LIVE 갱신
[ ] Spring → connector 호출 성공
[ ] GarminSyncService live 실행
[ ] ActivityRaw PostgreSQL 저장 확인
[ ] Activity PostgreSQL 저장 확인
[ ] TREADMILL_RUN mapping 확인
[ ] duration seconds mapping 확인
[ ] distance metres mapping 확인
[ ] HR mapping 확인
[ ] startTimeGMT → Instant 확인
[ ] 같은 activity 두 번째 sync
[ ] Activity row 중복 없음
[ ] ActivityRaw row 중복 없음
[ ] PK 유지
[ ] fetchedAt/update 동작 확인
[ ] activityName 깨짐 발생 layer 확인
[ ] 안전한 경우에만 encoding 수정
[ ] Python regression PASS
[ ] Java regression PASS
[ ] live raw payload 미커밋
[ ] secrets scan PASS
[ ] work order 결과 작성
[ ] README / skill 필요한 최소 갱신
[ ] git diff 검토
[ ] commit
[ ] push
```

---

# 44. 권장 commit

인코딩 production fix가 없는 경우:

```text
test: validate live Garmin end-to-end ingestion
```

실제 connector encoding fix가 포함되면:

```text
fix: preserve Garmin activity name encoding
```

또는 실제 변경 내용에 맞게 선택한다.

---

# 45. 최종 보고 형식

## Live Garmin

```text
authentication:
recent activity fetch:
activity type:
duration unit:
distance unit:
time:
heart rate:
```

실제 identifier나 activityName은 쓰지 않는다.

## E2E

```text
connector:
Spring client:
sync service:
raw persistence:
normalized persistence:
```

## First sync

```text
fetched:
created:
updated:
skipped:
failed:
```

## Second sync

```text
fetched:
created:
updated:
skipped:
failed:
```

## Idempotency

```text
Activity duplicate:
ActivityRaw duplicate:
Activity PK preserved:
ActivityRaw PK preserved:
```

## Encoding diagnosis

```text
python-garminconnect:
Python object:
FastAPI JSON:
raw HTTP:
Spring JsonNode:
PowerShell:
root cause:
fix:
```

## Tests

```text
Java total:
Java passed:
Java failed:

Python total:
Python passed:
Python failed:
```

## Database

```text
migration:
schema change:
```

예상:

```text
NO
NO
```

## Git

```text
branch:
commit:
push:
```

## Remaining limitation

미해결 사항을 명확하게 작성한다.

## Next Phase

Phase 3C 후보:

```text
incremental sync cursor
scheduler
operational trigger
connector process supervision
Windows/Raspberry Pi deployment
```

3C는 자동 시작하지 않는다.

---

# 46. 핵심 원칙

이번 Phase의 성공 기준은 새로운 기능을 많이 만드는 것이 아니다.

목표는:

```text
실제 Garmin activity 1건이

Garmin Connect
→ Python connector
→ Spring
→ raw JSONB
→ normalized Activity
→ PostgreSQL

까지 실제로 관통하고,

같은 activity를 다시 받아도
중복이 생기지 않는다는 것을 증명하는 것
```

이다.

그리고 activityName encoding 문제는:

```text
추측하지 말고
어느 layer에서 깨지는지 먼저 확인한 뒤
안전한 경우에만 수정
```

한다.
