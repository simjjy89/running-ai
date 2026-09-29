> 원본 작업지시서 (2026-09-29, Phase 3B-2). 구현 기록은 `2026-09-29-garmin-connector-spring-integration.md` 참고.

# RunningAI Phase 3B-2
## Python Garmin Connector + Spring Boot Integration

## 1. 현재 상태

완료:

```text
Phase 1     Spring Boot foundation                  ✅
Phase 2     PostgreSQL / Flyway / ActivityRaw       ✅
Phase 3A    Garmin ingestion core                   ✅
Phase 3A.5  Claude Code workflow                    ✅
Phase 3B-1  Garmin live contract / architecture     ✅
```

현재 known latest commit:

```text
cbf64b1 refactor: align Garmin ingestion with confirmed Garmin contract
```

현재 Java regression baseline:

```text
66 tests
66 passed
0 failed
```

Phase 3B-1 결정:

```text
Spring Boot
      │
      │ localhost HTTP
      ▼
Python Garmin Connector
      │
      │ garminconnect==0.3.16
      ▼
Garmin Connect
```

책임 분리:

```text
Python Connector
- Garmin 인증
- MFA
- token/session
- Garmin read transport
- raw activity retrieval

Spring Boot
- ingestion
- raw JSONB 저장
- normalization
- idempotency
- DB
- 향후 분석 / 훈련 생성
```

Spring Boot는 Garmin password/token을 몰라야 한다.

---

# 2. 이번 Phase 목표

다음을 구현한다.

```text
1. tools/garmin-connector Python project
2. interactive Garmin login CLI
3. token 기반 status 확인
4. localhost-only HTTP connector
5. GET /health
6. GET /activities?limit=N
7. Spring GarminActivitySource
8. Spring → connector HTTP 호출
9. GarminSyncService
10. fetch → 기존 3A ingestion 연결
11. sync 결과 집계
12. connector / Spring 모두 network-independent test
13. 가능한 환경이면 read-only live probe
```

---

# 3. 이번 Phase에서 하지 않는 것

구현하지 않는다.

```text
incremental sync cursor
@Scheduled 자동 실행
Windows Task Scheduler 연결
systemd
Garmin write API
workout 생성/전송
Intervals.icu
FIT 다운로드
FIT parsing
Activity Detail 전체 수집
splits 자동 수집
Training Load
ChatGPT connector
public Garmin REST API
```

위 항목은 이후 Phase로 넘긴다.

---

# 4. Project rules

먼저 반드시 읽는다.

```text
CLAUDE.md

.claude/skills/running-ai-dev/SKILL.md
.claude/skills/running-ai-integration/SKILL.md
```

DB schema 변경이 필요할 때만:

```text
.claude/skills/running-ai-database/SKILL.md
```

를 읽는다.

이번 Phase에서는 DB migration이 필요하지 않아야 한다.

---

# 5. Repository 확인

작업 시작:

```bash
git status
git branch --show-current
git log --oneline -5
git remote -v
```

기존 Garmin 관련 코드를 먼저 확인한다.

```text
GarminActivityMapper
GarminActivityPayload
GarminActivityIngestionService
ActivityRawService
ActivityService
```

3A ingestion logic을 다시 구현하지 않는다.

---

# 6. Work Order

지시서:

```text
docs/work-orders/
2026-09-29-garmin-connector-spring-integration-instruction.md
```

결과:

```text
docs/work-orders/
2026-09-29-garmin-connector-spring-integration.md
```

---

# 7. Python connector 위치

다음 구조를 기본으로 한다.

```text
tools/
└─ garmin-connector/
   ├─ README.md
   ├─ requirements.txt
   ├─ garmin_connector/
   │  ├─ __init__.py
   │  ├─ __main__.py
   │  ├─ api.py
   │  ├─ auth.py
   │  ├─ client.py
   │  └─ errors.py
   │
   └─ tests/
```

현재 repository 구조에 더 자연스러운 방식이 있다면 최소 범위에서 조정 가능하다.

---

# 8. Python version

지원:

```text
Python >= 3.12
```

Garmin library:

```text
garminconnect==0.3.16
```

을 명시적으로 pin한다.

`garth`는 추가하지 않는다.

---

# 9. 다른 Python dependency

localhost HTTP server가 필요하므로 가벼운 HTTP framework를 사용한다.

FastAPI + Uvicorn을 우선 검토한다.

단 작업 시점의 Python 3.12 호환 stable release를 확인하고 version을 pin한다.

불필요한 dependency를 추가하지 않는다.

테스트에는 pytest를 사용할 수 있다.

---

# 10. 인증 책임

Garmin login은 connector만 담당한다.

Spring에는 다음이 없어야 한다.

```text
GARMIN_USERNAME
GARMIN_PASSWORD
GARMIN_TOKEN
GARMIN_REFRESH_TOKEN
MFA
```

Spring DB에도 저장하지 않는다.

---

# 11. Login CLI

HTTP로 login endpoint를 만들지 않는다.

반드시 local CLI를 사용한다.

목표 UX:

```powershell
cd tools\garmin-connector

python -m garmin_connector login
```

필요한 email/password는 interactive prompt로 입력한다.

password는 echo하지 않는다.

MFA가 필요한 경우 connector host의 interactive terminal에서 입력한다.

---

# 12. 실제 library API 확인

`garminconnect==0.3.16`의 실제 source/signature를 확인한 뒤 구현한다.

다음을 추측하지 않는다.

```text
login method signature
MFA resume method
token save method
token load method
token path argument
```

0.3.16 source가 source of truth다.

---

# 13. Token persistence

library가 지원하는 token storage mechanism을 사용한다.

권장 위치:

```text
~/.garminconnect/
```

또는 library의 공식 default.

Repository 아래에는 token을 저장하지 않는다.

```text
running-ai/
tools/garmin-connector/
```

안에 token/cache를 두지 않는다.

---

# 14. CLI commands

최소 다음 명령을 지원한다.

```text
login
status
serve
activities --limit N
```

예:

```powershell
python -m garmin_connector status

python -m garmin_connector activities --limit 3

python -m garmin_connector serve
```

`activities`는 read-only diagnostic 명령이다.

---

# 15. CLI output 보안

기본 CLI에서 raw JSON 전체를 stdout으로 출력하지 않는다.

`activities`는 다음 정도만 표시한다.

```text
count
activity ID 일부 또는 masking
activity type
start time
duration
distance
```

필요하다면 `--json` 같은 옵션도 만들지 않는다.

실제 raw payload 검증은 테스트 또는 메모리에서 처리한다.

---

# 16. Connector HTTP binding

HTTP connector는 기본:

```text
127.0.0.1
```

에만 bind한다.

기본 port 예:

```text
8765
```

따라서:

```text
http://127.0.0.1:8765
```

Spring과 같은 host에서만 접근한다.

이번 Phase에서는:

```text
0.0.0.0
```

외부 bind를 지원하지 않는다.

---

# 17. Connector HTTP API

최소 두 endpoint만 만든다.

### Health

```http
GET /health
```

예:

```json
{
  "status": "UP",
  "service": "garmin-connector"
}
```

Garmin login 여부 때문에 health가 DOWN이 되지는 않는다.

프로세스 자체 상태 확인용이다.

---

# 18. Activities

```http
GET /activities?limit=20
```

validation:

```text
1 <= limit <= 100
```

응답은 **JSON array**로 고정한다.

```json
[
  {
    "...": "Garmin raw activity item"
  }
]
```

wrapper를 Spring에 노출하지 않는다.

---

# 19. Raw-preserving contract

중요하다.

Connector는 Garmin activity item 내부 필드를 임의로 rename하거나 normalize하지 않는다.

즉:

```text
Garmin activity dict
      ↓
JSON serialization
      ↓
Spring JsonNode
```

형태다.

Spring 쪽 `GarminActivityMapper`가 normalization을 담당한다.

Connector는 transport adapter일 뿐이다.

---

# 20. Garmin read call

current `garminconnect==0.3.16`의 stable API를 확인하여 recent activities를 가져온다.

예상 개념:

```text
get_activities(...)
```

정확한 signature는 source에서 확인한다.

pagination이 필요한 경우에도 이번 Phase에서는 `limit`에 필요한 최소 요청만 수행한다.

---

# 21. No detail fan-out

활동 N개를 가져왔다고:

```text
get_activity_details
get_activity_splits
```

를 활동마다 추가 호출하지 않는다.

Phase 3B-2는 summary activity list만 사용한다.

Garmin request 수를 최소화한다.

---

# 22. Authentication error mapping

Connector는 upstream 문제를 구조화한다.

최소 error code:

```text
GARMIN_AUTH_REQUIRED
GARMIN_FORBIDDEN
GARMIN_RATE_LIMITED
GARMIN_UPSTREAM_ERROR
GARMIN_CONNECTOR_ERROR
```

예:

```json
{
  "code": "GARMIN_RATE_LIMITED",
  "message": "Garmin request was rate limited"
}
```

token/password/cookie는 message에 포함하지 않는다.

---

# 23. HTTP status

권장:

```text
401 → GARMIN_AUTH_REQUIRED
403 → GARMIN_FORBIDDEN
429 → GARMIN_RATE_LIMITED
502 → GARMIN_UPSTREAM_ERROR
500 → GARMIN_CONNECTOR_ERROR
```

실제 garminconnect exception type을 확인하여 안정적으로 mapping한다.

---

# 24. Retry policy

Connector 자체에서:

```text
401
403
429
```

를 자동 retry하지 않는다.

로그인을 자동 반복하지 않는다.

Garmin client library가 내부적으로 하는 정상 token refresh는 허용한다.

---

# 25. Logging

로그 가능:

```text
connector startup
request activity count
upstream success/failure
status code
```

로그 금지:

```text
password
MFA
token
cookie
session
full Garmin raw JSON
GPS data
```

---

# 26. FastAPI docs

내부 connector이므로 Swagger/ReDoc/OpenAPI UI는 필요 없다.

가능하면 비활성화한다.

이 connector는 사용자-facing API가 아니다.

---

# 27. Spring configuration

Spring에는 connector URL만 둔다.

예:

```yaml
running-ai:
  garmin:
    connector:
      base-url: ${GARMIN_CONNECTOR_URL:http://127.0.0.1:8765}
```

여기에 credential을 추가하지 않는다.

---

# 28. Spring package

현재 구조를 확인 후 대략:

```text
com.runningai.integration.garmin
```

안에 다음을 구성한다.

```text
GarminActivitySource
HttpGarminActivitySource
GarminConnectorProperties
GarminConnectorException
GarminSyncService
GarminSyncResult
```

실제 naming convention에 맞춘다.

---

# 29. GarminActivitySource

이제 network boundary가 확정되었으므로 interface 도입을 허용한다.

개념:

```java
public interface GarminActivitySource {
    List<JsonNode> fetchRecentActivities(int limit);
}
```

이 interface는 Garmin private authentication을 전혀 알지 않는다.

---

# 30. HttpGarminActivitySource

책임:

```text
Spring
↓
localhost connector GET /activities
↓
List<JsonNode>
```

Spring Boot 3에서 제공하는 적절한 HTTP client를 사용한다.

새 reactive stack이 필요하지 않다면 WebClient를 위해 WebFlux를 추가하지 않는다.

`RestClient` 사용을 우선 검토한다.

---

# 31. Timeout

무한 대기를 허용하지 않는다.

적절한:

```text
connect timeout
read timeout
```

을 둔다.

값은 설정 가능하게 하거나 합리적인 default를 사용한다.

과도한 retry framework는 추가하지 않는다.

---

# 32. Spring error mapping

Connector 오류를 구분한다.

예:

```text
GarminAuthenticationRequiredException
GarminRateLimitedException
GarminConnectorUnavailableException
```

단 class 폭발은 피한다.

단일 exception + reason enum도 허용한다.

---

# 33. GarminSyncService

흐름:

```text
GarminActivitySource.fetchRecentActivities(limit)
        ↓
List<JsonNode>
        ↓
for each
        ↓
GarminActivityIngestionService.ingest(raw)
```

기존 3A ingestion service를 그대로 사용한다.

중복 ingestion logic을 만들지 않는다.

---

# 34. Sync 결과

결과를 반환한다.

예:

```java
record GarminSyncResult(
    int fetched,
    int created,
    int updated,
    int skipped,
    int failed
)
```

현재 `GarminActivityIngestionService` 결과와 실제 API를 확인하여 자연스럽게 구현한다.

---

# 35. Unsupported activity

예:

```text
swimming
strength_training
walking
```

등 현재 RunningAI가 지원하지 않는 type은 전체 sync를 실패시키지 않는다.

정책:

```text
raw 저장
Activity 미생성
skipped +1
다음 activity 계속
```

지원 type:

```text
RUN
TREADMILL_RUN
INDOOR_CYCLING
```

---

# 36. Malformed payload

activityId가 없어 raw 자체도 저장할 수 없는 등 malformed data는:

```text
failed +1
```

처리하고 다음 activity로 진행한다.

로그에는 raw payload 전체를 찍지 않는다.

---

# 37. Connector-level failure

다음은 per-activity failure와 다르다.

```text
connector down
auth required
403
429
Garmin upstream unavailable
```

이 경우 sync 자체를 즉시 중단한다.

빈 목록으로 성공 처리하지 않는다.

---

# 38. Idempotency

이번 Phase에서도 핵심 invariant를 유지한다.

같은 recent 20개를 계속 sync해도:

```text
Activity count 증가 없음
ActivityRaw count 증가 없음
```

이미 존재하는 활동은 update 처리된다.

---

# 39. Incremental cursor는 아직 금지

이번에는:

```text
최근 N개 fetch
+
기존 idempotency
```

만 사용한다.

DB에:

```text
last_sync_at
cursor
checkpoint
```

를 만들지 않는다.

그것은 Phase 3C에서 한다.

---

# 40. Scheduler도 금지

다음을 추가하지 않는다.

```java
@Scheduled
```

자동 sync는 Phase 3C에서 설계한다.

---

# 41. Public Spring API도 아직 불필요

이번 Phase에서는:

```http
POST /api/v1/garmin/sync
```

를 만들지 않는다.

우선 application service를 완성한다.

실제 operational trigger는 3C 또는 이후 ChatGPT integration을 고려해 설계한다.

---

# 42. Python test

Connector 테스트는 실제 Garmin network를 사용하지 않는다.

garminconnect client를 fake/mock하여 최소 다음을 검증한다.

```text
health
activities list response
limit validation
auth required mapping
403 mapping
429 mapping
upstream error mapping
raw item preservation
```

---

# 43. Credential test

login test에서 실제 password를 넣지 않는다.

library client를 fake/mock한다.

가능하면:

```text
password echo 없음
token output 없음
```

도 검증한다.

---

# 44. Spring HTTP tests

실제 Python connector 없이도 Java test가 PASS해야 한다.

fake/local test server 또는 Spring test facility를 이용해:

```text
JSON array parsing
connector errors
timeout/error handling
```

을 검증한다.

실제 Garmin network에 연결하지 않는다.

---

# 45. Spring Sync tests

최소:

```text
3 supported activities
→ fetched=3
→ created=3

same activities second sync
→ Activity rows remain 3
→ ActivityRaw rows remain 3

supported + unsupported
→ skipped count

malformed
→ failed count

connector 429
→ sync abort
```

를 검증한다.

---

# 46. Java regression

기존:

```text
66 tests
```

는 모두 통과해야 한다.

새 테스트가 추가되므로 최종 개수는 증가한다.

```powershell
cd server
.\gradlew.bat clean test
```

---

# 47. Python 없는 현재 PC 처리

현재 Phase 3B-1 환경에서는 Python 3.12 runtime이 없었다.

작업 시작 시 다시 확인한다.

```powershell
py -0p
python --version
```

Python 3.12+가 없다면:

**사용자 허가 없이 system-wide Python을 설치하지 않는다.**

그 경우:

```text
PYTHON_RUNTIME_UNAVAILABLE
```

를 결과에 기록한다.

---

# 48. Python runtime이 없을 때

Python connector source와 tests는 작성할 수 있지만:

```text
Python tests PASS
```

라고 거짓으로 보고하지 않는다.

가능하면 repository의 기존 CI 환경을 사용할 수 있는지 조사한다.

단 이 Phase만을 위해 과도한 CI infrastructure를 추가하지 않는다.

실행하지 못한 검증은 정확히 표시한다.

---

# 49. Python runtime이 있는 경우

isolated virtualenv를 사용한다.

예:

```powershell
cd tools\garmin-connector

py -3.12 -m venv .venv
.\.venv\Scripts\python.exe -m pip install -r requirements.txt
```

`.venv/`는 commit하지 않는다.

---

# 50. Python tests

예:

```powershell
.\.venv\Scripts\python.exe -m pytest
```

모두 PASS해야 한다.

---

# 51. Live probe 조건

다음이 모두 가능할 때만 수행한다.

```text
Python 3.12+
connector tests PASS
interactive terminal
user credential 입력 가능
Garmin 인증 정상
```

---

# 52. Live probe 절차

최소 호출만 한다.

```text
1. login 또는 기존 token 확인
2. connector 시작
3. GET /health
4. GET /activities?limit=1
```

활동 1건만 읽는다.

---

# 53. Live payload 검증

출력/문서에는 실제 activity payload를 남기지 않는다.

다음만 확인한다.

```text
activityId 존재
activityType.typeKey 존재
startTimeGMT 존재
duration unit
distance unit
averageHR / maxHR shape
```

결과는:

```text
CONFIRMED_LIVE
```

로 ADR에 갱신할 수 있다.

---

# 54. Live probe 이후

실제 raw 파일을 repository에 저장하지 않는다.

temporary file을 만들었다면 삭제한다.

다음을 다시 검색한다.

```text
email
token
cookie
GPS
actual activity ID
profile ID
```

---

# 55. Live probe 실패

다음이 발생하면:

```text
401
403
429
Cloudflare
MFA failure
```

반복 retry하지 않는다.

결과에만 기록한다.

```text
LIVE_PROBE_BLOCKED
```

---

# 56. Connector README

다음 내용을 작성한다.

```text
requirements
setup
venv
login
MFA
status
serve
health
activities
security
token storage
```

실제 credential 예시는 절대 쓰지 않는다.

---

# 57. Root README

간략한 architecture를 추가한다.

```text
Garmin Connect
      ↓
Python Connector
      ↓ localhost HTTP
Spring Boot
      ↓
activity_raw
      ↓
activity
```

미구현인 scheduler/cursor를 완료된 것처럼 쓰지 않는다.

---

# 58. Secrets

commit 전 반드시 검사한다.

```text
email
password
MFA
access token
refresh token
cookie
session
GPS
real activity id
real profile id
.env
.venv
```

---

# 59. DB

이번 Phase에서는:

```text
V1
V2
V3
```

를 수정하지 않는다.

새 migration도 만들지 않는다.

필요하다고 판단되면 먼저 이유를 문서화하고 임의로 schema를 변경하지 않는다.

---

# 60. Definition of Done

다음이 충족되어야 한다.

```text
[ ] Python connector project 생성
[ ] garminconnect==0.3.16 pin
[ ] interactive login CLI
[ ] MFA 대응
[ ] token library storage
[ ] status CLI
[ ] activities CLI
[ ] localhost-only serve
[ ] GET /health
[ ] GET /activities
[ ] no HTTP login endpoint
[ ] structured error contract
[ ] 401/403/429 no automatic retry
[ ] Spring GarminActivitySource
[ ] Spring HTTP implementation
[ ] connector URL configuration
[ ] GarminSyncService
[ ] result aggregation
[ ] unsupported SKIP
[ ] malformed FAILED
[ ] connector failure abort
[ ] existing idempotency preserved
[ ] Java regression PASS
[ ] Python test PASS 또는 runtime unavailable 명확히 기록
[ ] live probe 조건 충족 시 1회 수행
[ ] no secrets/raw personal payload committed
[ ] docs/work-orders 업데이트
[ ] README 업데이트
[ ] git diff review
[ ] commit
[ ] push
```

---

# 61. 권장 commit

```text
feat: connect Garmin transport to RunningAI server
```

테스트/검증 후:

```bash
git add .
git commit -m "feat: connect Garmin transport to RunningAI server"
git push origin main
```

---

# 62. 최종 보고

## Connector

```text
Python:
garminconnect:
HTTP framework:
bind:
port:
token storage:
MFA:
```

## HTTP API

```text
GET /health:
GET /activities:
```

## Spring

```text
GarminActivitySource:
Http implementation:
GarminSyncService:
```

## Sync behavior

```text
supported:
unsupported:
malformed:
connector failure:
idempotency:
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

실행하지 못했다면 이유를 명확히 작성한다.

## Live probe

```text
attempted:
login:
health:
activities:
contract confirmation:
```

실제 개인정보는 작성하지 않는다.

## Database

```text
migration added:
schema changed:
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

## Limitations

남아 있는 제약을 작성한다.

## Next Phase

다음은 별도 Phase 3C로 남긴다.

```text
incremental sync cursor
scheduler
operational trigger
connector process supervision
Windows → Raspberry Pi deployment strategy
```

3C를 자동 시작하지 않는다.

---

# 63. 최종 architecture invariant

이번 작업이 끝난 후 반드시 다음 경계가 유지되어야 한다.

```text
Garmin Connect
      │
      ▼
python-garminconnect
      │
      ▼
Garmin Connector
   auth / token / transport
      │
      │ localhost JSON
      ▼
GarminActivitySource
      │
      ▼
GarminSyncService
      │
      ▼
GarminActivityIngestionService
      │
      ├───────────────┐
      ▼               ▼
 activity_raw       activity
```

**Python은 DB를 모른다.**

**Spring은 Garmin password/token을 모른다.**

**Mapper는 network를 모른다.**

이 세 가지를 깨지 않는다.
