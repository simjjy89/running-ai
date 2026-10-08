# RunningAI Phase 6I-1 — External Access E2E Stabilization

## 0. Work Order 저장

이 작업지시서 전문을 먼저 다음 경로에 저장한다.

```text
C:\running-ai-github\docs\work-orders\
2026-10-06-phase-6i-1-external-access-e2e-stabilization-instruction.md
```

작업 완료 후:

```text
C:\running-ai-github\docs\work-orders\
2026-10-06-phase-6i-1-external-access-e2e-stabilization-result.md
```

를 작성한다.

기존 `C:\running-ai` legacy repo는 절대 reset / clean / delete / overwrite 하지 않는다.

---

# 1. 목표

현재 RunningAI의 핵심 내부 파이프라인은 실사용 가능한 상태다.

```text
Garmin
  ↓
RunningAI
  ↓
Coach freshness
  ↓
Claude
  ↓
Draft
  ↓
Approval
  ↓
Intervals.icu
  ↓
Garmin 265
```

그러나 외부 접근은 아직 운영 수준으로 검증되지 않았다.

이번 Phase의 목표:

```text
외부 LTE / 5G
      ↓
고정 HTTPS hostname
      ↓
Cloudflare Access 인증
      ↓
Cloudflare Named Tunnel
      ↓
READ-only RunningAI relay
      ↓
127.0.0.1 Spring
      ↓
오늘 훈련 / 상태
      ↓
Garmin Forerunner 265
```

최종적으로 PC 앞에 있지 않아도 RunningAI의 READ 기능을 사용할 수 있어야 한다.

---

# 2. Definition of Success

다음 전부 통과해야 Phase 완료다.

```text
[1] trycloudflare 임시 URL 사용 안 함
[2] 고정 HTTPS hostname 확보
[3] 외부 인증 필수
[4] Spring 8080 직접 외부 노출 없음
[5] relay는 READ-only
[6] POST/PUT/PATCH/DELETE 외부 접근 차단
[7] cloudflared 자동 시작
[8] relay 자동 시작
[9] PC 재부팅 후 자동 복구
[10] 외부 LTE/5G 실측 성공
[11] 실제 Garmin 265에서 데이터 표시 성공
[12] 기존 publish safety 불변
```

완료 marker:

```text
PHASE_6I_1_EXTERNAL_ACCESS_E2E_READY
```

---

# 3. 현재 상태를 먼저 Inventory

구현 전에 기존 외부 접근 구성부터 조사한다.

최소 확인 대상:

```text
C:\running-ai-github
C:\running-ai-watchface
C:\running-ai
```

그리고 Windows:

```text
Scheduled Tasks
Windows Services
running cloudflared processes
relay processes
ports
startup scripts
watchdog
```

특히 기존에 사용했던:

```text
127.0.0.1:17845
trycloudflare.com
cloudflared
relay
watchface endpoint
```

의 실제 구현 위치와 ownership을 찾는다.

추측해서 새 구현을 중복으로 만들지 않는다.

---

# 4. Inventory 결과 기록

result 문서에 다음 표를 남긴다.

```text
Component       Location                 Managed by
-------------------------------------------------------------
Spring          ...                      RunningAI runtime
Relay           ...                      ...
cloudflared     ...                      ...
Startup         ...                      ...
Watchdog        ...                      ...
Watchface       C:\running-ai-watchface  ...
```

기존 relay가 repo 밖/untracked라면 명시한다.

---

# 5. 아키텍처 원칙

절대 다음처럼 구성하지 않는다.

```text
Internet
   ↓
Cloudflare
   ↓
Spring :8080
```

Spring에는 operational POST endpoint가 많기 때문이다.

반드시:

```text
Internet
   ↓
Cloudflare Access
   ↓
Cloudflare Tunnel
   ↓
127.0.0.1:17845
READ-only relay
   ↓
127.0.0.1:8080
Spring
```

구조로 제한한다.

---

# 6. Network Binding

Spring:

```text
127.0.0.1:8080
```

또는 현재 안전한 local binding 유지.

Garmin connector:

```text
127.0.0.1:8765
```

Relay:

```text
127.0.0.1:17845
```

Cloudflared만 relay에 접근한다.

다음 금지:

```text
0.0.0.0:8080
0.0.0.0:8765
0.0.0.0:17845
```

특별한 이유 없이 LAN/public bind 하지 않는다.

---

# 7. Quick Tunnel 폐기

다음 형태:

```text
https://random-name.trycloudflare.com
```

는 개발용 smoke test로만 허용한다.

운영 endpoint로 사용 금지.

최종 환경은 Named Tunnel을 사용한다.

---

# 8. Fixed Hostname

hostname은 configuration으로 관리한다.

예:

```text
runningai.<user-domain>
```

실제 domain은 코드에 하드코딩하지 않는다.

환경/config 예:

```text
RUNNING_AI_EXTERNAL_BASE_URL=https://runningai.example.com
```

실제 hostname이 결정되면 `.env` 또는 local configuration에 둔다.

---

# 9. Domain prerequisite

Cloudflare에서 관리 가능한 domain이 없다면:

```text
FIXED_HOSTNAME_PREREQUISITE_MISSING
```

으로 명확히 중단한다.

`trycloudflare` URL을 고정 endpoint처럼 취급하여 Phase를 PASS시키면 안 된다.

---

# 10. Named Cloudflare Tunnel

Named tunnel을 생성하고 고정 hostname을 route한다.

origin:

```text
http://127.0.0.1:17845
```

catch-all ingress:

```text
http_status:404
```

필수.

즉 명시된 hostname 외 traffic은 origin으로 전달하지 않는다.

---

# 11. cloudflared Windows Service

cloudflared는 interactive terminal process가 아니라 Windows Service로 운영한다.

목표:

```text
PC boot
  ↓
Windows
  ↓
cloudflared service
  ↓
named tunnel connected
```

사용자가 PowerShell 창을 열어두어야 유지되는 구조 금지.

---

# 12. cloudflared config

credential/config는 repo 밖에 둔다.

절대 Git commit 하지 않는다.

Cloudflare 공식 Windows service 경로 또는 동일 수준의 system-owned secure path를 사용한다.

최소 설정:

```yaml
tunnel: <UUID>
credentials-file: <absolute path>

ingress:
  - hostname: <fixed hostname>
    service: http://127.0.0.1:17845

  - service: http_status:404
```

credential UUID/file 내용은 result 문서에 기록하지 않는다.

Tunnel name 정도만 기록 가능.

---

# 13. Access authentication

고정 hostname 앞에는 Cloudflare Access를 둔다.

Watchface처럼 browser login이 불가능한 client를 위해:

```text
Service Auth
Service Token
```

정책을 사용한다.

External request는 인증 없이 성공해서는 안 된다.

---

# 14. Authentication test

반드시 다음 두 경우를 실제 검증한다.

### Credential 없음

```text
GET https://<hostname>/...
```

Expected:

```text
DENIED
```

200 금지.

### Credential 있음

Cloudflare service token headers 포함.

Expected:

```text
200
```

---

# 15. Credential handling

다음 값:

```text
CF Access Client ID
CF Access Client Secret
Tunnel credential
```

은:

```text
Git
docs
logs
console dumps
screenshots
test fixture
```

에 남기지 않는다.

출력 시:

```text
configured=true
length=...
```

정도만 허용.

---

# 16. Garmin credential handling

Watchface source에 실제 secret literal을 넣지 않는다.

예를 들어 다음 금지:

```monkeyc
const CLIENT_SECRET = "real-secret";
```

대신 app setting/property를 사용한다.

소스에는 setting key만 존재.

예:

```text
runningAiAccessClientId
runningAiAccessClientSecret
runningAiBaseUrl
```

실제 값은 local/device configuration으로 주입한다.

---

# 17. Relay 책임

Relay는 **reverse proxy 전체**가 아니다.

허용된 API만 명시적으로 구현한다.

최소:

```text
GET /health
GET /v1/today
```

기존 watchface가 이미 다른 path/contract를 사용한다면 먼저 조사하고 backward compatibility를 보존한다.

---

# 18. `/health`

외부 health에는 민감한 내부 정보를 노출하지 않는다.

예:

```json
{
  "status": "UP",
  "service": "running-ai-relay"
}
```

금지:

```text
DB credentials
filesystem paths
Garmin token location
Intervals key
stack traces
full environment
```

---

# 19. `/v1/today`

Watchface에 필요한 최소 데이터만 반환한다.

예시:

```json
{
  "date": "2026-10-06",
  "hasPlan": true,
  "type": "EASY",
  "durationMin": 40,
  "status": "PLANNED"
}
```

실제 wire contract는 기존 watchface 구현을 먼저 확인하고 결정한다.

응답은 작게 유지한다.

---

# 20. Watch bandwidth

Garmin watch에 불필요한 TrainingContext 전체를 보내지 않는다.

절대 다음 전송 금지:

```text
raw Garmin payload
1Hz samples
90-day history
Claude prompt
rationale 전체
database entity 전체
```

Watch endpoint는 compact DTO만 반환.

---

# 21. HTTP method allow-list

Relay 외부 endpoint:

```text
GET
```

만 허용한다.

다음은 모두 거절:

```text
POST
PUT
PATCH
DELETE
OPTIONS를 통한 unsafe proxy routing
```

OPTIONS가 필요한 경우 CORS metadata 용도로만 처리하고 upstream write로 이어지지 않는다.

---

# 22. Path allow-list

절대 generic:

```text
/api/**
```

forwarding을 만들지 않는다.

예:

```text
ALLOW:
GET /health
GET /v1/today

DENY:
everything else
```

---

# 23. Publish endpoint 외부 금지

다음 계열은 외부 relay에서 접근 불가능해야 한다.

```text
/api/v1/workout-drafts
/api/v1/.../approve
/api/v1/.../publish
/api/v1/garmin/sync
/api/v1/garmin/recovery-sync
/api/v1/coach/data-refresh
/api/v1/intervals/enrichment
historical backfill
admin / operational APIs
```

URL guessing으로도 도달할 수 없어야 한다.

---

# 24. Safety invariant

다음 기존 safety를 이번 Phase에서 변경하지 않는다.

```text
RUNNING_AI_DRAFT_PUBLISHING_ENABLED=false
WORKOUT_PUBLISHING_ENABLED=false
WORKOUT_PUBLISHING_SCHEDULER_ENABLED=false
RUNNINGAI_MCP_ENABLED=false
```

외부 READ phase는 publish switch와 독립적이다.

---

# 25. Relay failure model

Spring이 DOWN이면 relay는:

```text
503
```

같은 명확한 unavailable response를 반환한다.

오래된 cached workout을 현재값처럼 위장하지 않는다.

---

# 26. No silent cache

Phase 6I-1에서는 특별한 이유가 없다면 relay cache를 추가하지 않는다.

source of truth:

```text
RunningAI Spring/DB
```

---

# 27. Relay management

기존 relay가 이미 있으면 재사용/hardening한다.

없다면 tracked implementation으로 이동시킨다.

권장 위치:

```text
C:\running-ai-github\tools\external-relay\
```

또는 프로젝트 구조에 더 적절한 위치.

별도 임시 desktop script 상태로 두지 않는다.

---

# 28. Relay automatic startup

relay가 별도 process라면 자동 시작해야 한다.

다음 중 기존 Runtime architecture와 가장 일관적인 방법 선택:

```text
RunningAI-Startup integration
또는
별도 RunningAI-ExternalRelay Scheduled Task
또는
Windows Service
```

선택 이유를 문서화한다.

---

# 29. Existing Startup 보존

현재:

```text
RunningAI-Startup
RunningAI-Watchdog
```

를 깨지 않는다.

기존 startup task의 semantics를 유지한다.

기존 task를 이름만 같다는 이유로 overwrite하지 않는다.

---

# 30. Watchdog integration

현재 watchdog은:

```text
docker
postgres
connector
spring
```

을 관리한다.

relay를 watchdog에 추가할지 별도 watchdog으로 관리할지 설계 후 결정한다.

권장 dependency:

```text
docker
 ↓
postgres
 ↓
connector
 ↓
spring
 ↓
relay
```

cloudflared는 Windows Service이므로 별도 상태 확인이 적절하다.

---

# 31. cloudflared restart responsibility

RunningAI watchdog이 cloudflared를 무조건 kill/restart하는 구조는 피한다.

Windows Service Control Manager가 1차 ownership을 갖는다.

Watchdog에서는 우선:

```text
service state
tunnel reachability
```

를 관측한다.

필요하면 bounded recovery만 수행한다.

---

# 32. Restart budget

relay를 watchdog 관리 대상으로 추가한다면 기존 철학 유지:

```text
max 3 restart / 10 min
```

같은 bounded restart budget.

무한 restart loop 금지.

---

# 33. Status integration

`status-running-ai.ps1`에 외부 상태를 추가한다.

예:

```text
Docker           RUNNING
PostgreSQL       HEALTHY
GarminConnector  UP
Spring           UP
ExternalRelay    UP 127.0.0.1:17845
Cloudflared      RUNNING
ExternalEndpoint UP
Watchdog         UP
```

---

# 34. External status probe

`ExternalEndpoint` probe는 GET-only.

credential을 로그에 출력하지 않는다.

실제 public hostname을 통해 호출한다.

Expected:

```text
UP
AUTH_FAILED
TUNNEL_DOWN
RELAY_DOWN
UNKNOWN
```

을 구분할 수 있으면 좋다.

---

# 35. Dedicated scripts

권장:

```text
scripts/windows/external/
  install-running-ai-external-access.ps1
  status-running-ai-external-access.ps1
  test-running-ai-external-access.ps1
```

필요한 경우:

```text
uninstall-running-ai-external-access.ps1
```

도 제공.

---

# 36. Install script safety

installer는 idempotent해야 한다.

기존 tunnel/service/task가:

```text
RunningAI용임이 확인
```

될 때만 update.

다른 cloudflared tunnel/service configuration을 무단 overwrite 금지.

---

# 37. DryRun

가능하면 installer에:

```powershell
-DryRun
```

지원.

DryRun에서는:

```text
files changed 0
services changed 0
tasks changed 0
Cloudflare writes 0
```

---

# 38. Secrets and `.env`

secret 값은 `.env.example`에 실제 값으로 넣지 않는다.

예:

```text
RUNNING_AI_EXTERNAL_BASE_URL=
CF_ACCESS_CLIENT_ID=
CF_ACCESS_CLIENT_SECRET=
```

placeholder 정도만 허용.

기존 `.env`를 console에 dump하지 않는다.

---

# 39. Connect IQ request

Watchface request는 HTTPS만 사용.

예시 구조:

```text
makeWebRequest(
  fixedUrl,
  null,
  {
    method = GET,
    headers = {
      CF-Access-Client-Id
      CF-Access-Client-Secret
    }
  }
)
```

실제 Monkey C 문법은 현재 SDK에 맞게 구현한다.

---

# 40. Watchface error states

최소 다음 상태를 구분한다.

```text
NO_NETWORK
AUTH_FAILED
SERVER_DOWN
NO_PLAN
HAS_PLAN
```

모든 오류를:

```text
NO PLAN DATA
```

로 뭉개지 않는다.

---

# 41. Watchface fallback

network error 때문에 기존 화면 전체가 깨지면 안 된다.

RunningAI remote data failure 시:

```text
시계 시간 / 날짜 / 기본 워치페이스
```

는 정상 rendering.

---

# 42. AOD 영향 금지

외부 network polling 때문에 AOD rendering이나 battery usage가 비정상적으로 증가하지 않도록 한다.

AOD 상태에서 무한 HTTP polling 금지.

---

# 43. Polling policy

Watchface에서 초 단위 polling 금지.

적절한 cache/refresh interval 사용.

예:

```text
app startup
foreground wake
적절한 수분 단위 refresh
```

정확한 interval은 기존 watchface lifecycle을 보고 결정.

---

# 44. Simulator tests

Connect IQ Simulator에서:

```text
valid credentials -> 200
invalid credentials -> auth error
relay down -> server error
no workout -> NO_PLAN
workout exists -> HAS_PLAN
```

검증.

---

# 45. Relay unit tests

필수:

```text
GET allowed path -> forwarded
GET unknown path -> 404

POST allowed-looking path -> 405/404
PUT -> denied
PATCH -> denied
DELETE -> denied

upstream failure -> 503
secret not logged
```

---

# 46. Security regression test

다음 URL들을 public hostname으로 테스트한다.

```text
/api/v1/workout-drafts
/api/v1/garmin/sync
/api/v1/coach/data-refresh
/api/v1/intervals/enrichment/fitness
```

Access credential을 **가지고 있어도** relay에서 차단되어야 한다.

즉 Cloudflare 인증 성공:

```text
!= full Spring access
```

---

# 47. Port exposure test

Main PC에서:

```text
netstat
Get-NetTCPConnection
```

등으로 검증.

Expected:

```text
8080    loopback only
8765    loopback only
17845   loopback only
```

---

# 48. Local E2E

먼저 Main PC에서 public hostname을 통해:

```text
Main PC
 ↓ Internet/Cloudflare
fixed HTTPS hostname
 ↓ tunnel
relay
 ↓
Spring
```

실제 round trip 검증.

localhost URL로 우회하면 안 된다.

---

# 49. Local E2E success criteria

```text
GET health -> 200
GET today  -> 200

wrong/no credential -> denied
unsafe method -> denied
unsafe path -> denied
```

---

# 50. True external test

Main PC에서만 성공했다고 Phase를 PASS시키지 않는다.

반드시 Main PC LAN 밖에서 검증한다.

가장 중요한 실측:

```text
Garmin 265
   ↓ Bluetooth
Phone
   ↓ LTE/5G
Cloudflare
   ↓
Main PC
```

---

# 51. LTE/5G validation

휴대폰 Wi-Fi OFF 확인.

가능하면:

```text
Wi-Fi OFF
cellular data ON
Garmin Connect reachable
```

상태에서 watchface refresh.

---

# 52. Actual Garmin 265 validation

실제 FR265에서 최소:

```text
오늘 계획 있음
```

과:

```text
오늘 계획 없음
```

둘 중 현재 실제 상태에 맞는 결과가 정확히 표시되어야 한다.

Synthetic data를 실제 DB에 넣어 검증하지 않는다.

필요하면 test endpoint/mock는 local simulator에만 사용.

---

# 53. Watchface response proof

result 문서에 secret 없이 기록:

```text
Device             : Garmin Forerunner 265
Network            : phone LTE/5G, Wi-Fi off
External hostname  : configured
Authentication     : PASS
HTTP outcome       : PASS
Displayed state    : ...
Test timestamp     : ...
```

---

# 54. PC reboot test

이 Phase에서 가장 중요한 acceptance test 중 하나다.

Main PC를 정상 재부팅한다.

사용자가 별도 PowerShell 창을 열지 않는다.

---

# 55. After reboot expected

자동으로:

```text
Docker/Postgres
Garmin connector
Spring
Relay
cloudflared
```

이 필요한 순서/ownership으로 복구.

---

# 56. Reboot external test

재부팅 후 다시:

```text
LTE/5G
 ↓
Garmin 265
 ↓
fixed hostname
 ↓
RunningAI
```

검증.

재부팅 전 한 번만 성공은 Phase PASS 아님.

---

# 57. Tunnel process kill test

가능하면 controlled test:

```text
cloudflared process/service stop
```

후 recovery behavior 확인.

무한 restart loop가 없는지 확인.

테스트 완료 후 정상 복귀.

---

# 58. Relay process kill test

relay만 종료한 뒤:

```text
external request -> fails safely
automatic recovery
external request -> works again
```

확인.

---

# 59. Spring failure test

Spring을 중단하면 relay는:

```text
503
```

등 safe failure.

POST fallback이나 다른 service로 routing 금지.

---

# 60. Cloudflare down/network unavailable

Network outage 중 watchdog이 시스템을 공격적으로 반복 restart하지 않는다.

network failure와 local process failure를 구분한다.

---

# 61. Logging

외부 access log에 허용:

```text
timestamp
method
path
status
latency
request id
```

금지:

```text
Access client secret
Authorization
Cloudflare service token
Garmin token
Intervals API key
full response body
```

---

# 62. Log retention

기존 RunningAI log rotation 정책을 가능하면 재사용.

무한 logfile growth 금지.

---

# 63. Existing tests baseline

현재 Phase 6H-9 baseline:

```text
H2           1203/1203
PostgreSQL   1203/1203
PowerShell    100/100
Python        134/134
```

전부 유지.

신규 테스트는 추가.

---

# 64. External PowerShell tests

최소 다음 자동화:

```text
fixed hostname configured
relay UP
cloudflared service RUNNING

unauthenticated denied
authenticated GET succeeds

unsafe POST denied
unsafe path denied

no secrets emitted
```

---

# 65. No external writes

Phase 전체 live verification 중:

```text
Workout publish calls = 0
Intervals workout writes = 0
Garmin workout writes = 0
Coach approval calls = 0
```

---

# 66. Draft 상태

기존 Draft #17 변경 금지.

Draft #18:

```text
DRAFT
```

상태 유지.

외부 접근 검증을 위해 approve/publish하지 않는다.

---

# 67. Cloudflare configuration documentation

새 문서:

```text
docs/architecture/external-access.md
```

포함:

```text
architecture
trust boundaries
hostname
relay allow-list
Cloudflare Access
Windows startup
failure recovery
secret ownership
watchface request flow
```

실제 secrets 제외.

---

# 68. Architecture diagram

문서에 반드시:

```text
                        INTERNET
                           │
                           │ HTTPS
                           ▼
                  Cloudflare Access
                    Service Auth
                           │
                           ▼
                  Cloudflare Tunnel
                           │
                           ▼
                  127.0.0.1:17845
                  READ-ONLY RELAY
                           │
                    GET allow-list
                           │
                           ▼
                   127.0.0.1:8080
                     RunningAI
                           │
                           ▼
                     PostgreSQL
```

---

# 69. Trust boundary documentation

명확히 기록:

```text
Cloudflare Access credential
= 외부 relay 접근 권한

NOT
= RunningAI admin 권한

NOT
= workout publish 권한
```

---

# 70. Do not expand scope

이번 Phase에서는 하지 않는다.

```text
외부에서 Draft 생성
외부에서 Coach refresh
외부에서 approve
외부에서 publish
외부에서 Garmin sync trigger
외부에서 DB admin
```

이들은 후속 Phase.

---

# 71. Phase 6I-2 후보

6I-1 완료 후에만:

```text
External Control
```

을 설계한다.

예:

```text
외부에서 오늘의 러닝코치 요청
```

단 write와 분리.

---

# 72. Phase 6I-3 후보

그 후 필요하면:

```text
External Approved Publish
```

검토.

기존:

```text
APPROVE
YES
TOCTOU
safe-mode
```

를 절대 우회하지 않는다.

---

# 73. Final live acceptance sequence

최종 테스트는 반드시 이 순서로 수행한다.

```text
1. Main PC runtime SAFE
2. relay UP
3. cloudflared service UP
4. fixed hostname resolve
5. unauthenticated request DENIED
6. authenticated GET PASS
7. unsafe method DENIED
8. unsafe Spring path DENIED

9. phone Wi-Fi OFF
10. phone LTE/5G ON
11. Garmin 265 watchface request
12. actual RunningAI data displayed

13. Main PC reboot
14. no manual terminal
15. runtime auto-start
16. relay auto-start
17. tunnel auto-connect
18. Garmin 265 LTE test again PASS
```

---

# 74. Final report

result 문서에 최소:

```text
Fixed hostname       : configured
Named tunnel         : PASS
Cloudflared service  : PASS
Relay loopback       : PASS
Access auth          : PASS
Unsafe paths blocked : PASS
Unsafe methods       : PASS

Local public E2E     : PASS
LTE/5G E2E           : PASS
Garmin 265 E2E       : PASS
PC reboot recovery   : PASS

External writes      : 0

H2                    : x/x
PostgreSQL            : x/x
PowerShell            : x/x
Python                : x/x
Connect IQ tests      : x/x
```

secret/token/tunnel credential 값은 기록하지 않는다.

---

# 75. Completion Marker

모든 항목 통과 후에만:

```text
PHASE_6I_1_EXTERNAL_ACCESS_E2E_READY
```

를 기록한다.

다음 중 하나라도 미완료면 READY 금지:

```text
fixed hostname
authentication
LTE/5G
real Garmin 265
PC reboot recovery
```

---

# 76. 이번 Phase의 핵심 판단 기준

"Cloudflare tunnel connected"는 성공 기준이 아니다.

"외부 curl 200"만으로도 성공 기준이 아니다.

"Simulator에서 watchface 표시"만으로도 성공 기준이 아니다.

최종 성공은:

```text
Main PC를 재부팅해도
사용자가 터미널을 열지 않고
집 밖의 LTE/5G 경로에서
실제 Garmin 265가
고정 HTTPS 주소를 통해
RunningAI의 실제 최신 데이터를
인증된 READ-only 경로로 읽는다.
```

## Addendum (2026-10-06) — Transport decision deferred, relay relocation first

verbatim follow-up instruction, received after the inventory step above (existing
`C:\running-ai\watchface-relay` found to be Intervals.icu-direct + quick-tunnel, not
Spring-backed) and after the user answered the two blocking inventory questions (A: harden
the existing relay in place; A: relocate its code into this repo):

> Cloudflare hostname/domain 결정은 잠시 보류해줘.
>
> 방금 외부 접근 방식을 다시 검토했고, 집 공유기가 ipTIME AX2004M이라서 먼저 무료 기반의 ipTIME
> WireGuard VPN 경로를 feasibility test 하기로 했다. 따라서 지금은 `cloudflared tunnel login`,
> Named Tunnel 생성, DNS route, Cloudflare Access 설정은 진행하지 마라. 이것은
> `FIXED_HOSTNAME_PREREQUISITE_MISSING`으로 종료하는 것도 아니고, Cloudflare 방식을 폐기하는 것도
> 아니다. 외부 access transport 결정을 잠시 deferred 하는 것이다.
>
> 대신 transport와 무관하게 필요한 작업만 먼저 진행해줘.
>
> 1. 기존 `C:\running-ai\watchface-relay\server.js`를 `C:\running-ai-github\tools\external-relay\`
>    쪽으로 이관한다.
> 2. 기존 installed watchface와의 wire contract를 정확히 보존한다.
>    - `GET /today-workout`
>    - 기존 Bearer token 방식
>    - 기존 Intervals.icu-direct + `today_plan.json` fallback/logic
>    - 응답 JSON shape 변경 금지
> 3. Spring backend로 data source를 바꾸지 않는다.
> 4. Spring endpoint를 새로 만들지 않는다.
> 5. Relay의 기존 logic을 regression test로 먼저 고정한 뒤 이관한다.
> 6. secret/token은 코드/Git/test fixture/log/result doc에 남기지 않는다.
> 7. Relay를 managed lifecycle로 편입하기 위한 startup/status/watchdog 설계는 진행해도 된다.
> 8. 하지만 relay bind address, public hostname, Cloudflare-specific headers/configuration은 아직
>    확정하거나 변경하지 않는다.
> 9. 현재 실행 중인 3개의 duplicate quick-tunnel 프로세스는 기존 watchface가 아직 사용할 가능성이
>    있으므로 지금 즉시 종료하지 않는다. 현재 endpoint/usage를 먼저 inventory에 기록하고 migration
>    시점에 정리한다.
> 10. Phase 결과를 READY로 선언하지 않는다. 현재는 infrastructure-neutral relay hardening 단계까지만
>     진행한다.
>
> 추가로 새로운 제약을 기록해줘:
>
> - Garmin Connect IQ external request에는 HTTPS requirement를 고려해야 한다.
> - 따라서 ipTIME WireGuard가 연결된다고 해서 `http://LAN-IP:17845`를 최종 watchface endpoint로
>   확정하지 않는다.
> - AX2004M WireGuard + valid HTTPS를 무료로 구성할 수 있는지 먼저 검증하고, 어려우면 Cloudflare
>   Named Tunnel로 되돌아간다.
> - 어느 방식을 선택해도 월 유료 인프라는 사용하지 않는다.
> - 유료 서비스가 필요해지는 순간 구현을 중단하고 보고한다.
>
> 현재 Phase 6I-1의 Cloudflare-specific implementation은 HOLD로 두고, relay relocation + regression +
> managed lifecycle preparation까지만 진행해줘.
>
> 완료 시 변경 파일, regression 결과, 기존 relay contract 보존 증거, 아직 미결정인 external transport
> 항목을 보고해줘.

New standing constraints recorded from this addendum (apply to all remaining Phase 6I-1 work):

- Garmin Connect IQ external HTTP requests must be considered to require HTTPS - a bare
  `http://<LAN-IP>:17845` must never be finalized as the watch face endpoint even if an ipTIME
  WireGuard VPN path works at the network level.
- Before committing to either transport: verify whether an ipTIME AX2004M WireGuard VPN path
  can be paired with valid HTTPS at zero ongoing cost; if that is not feasible, fall back to the
  already-planned Cloudflare Named Tunnel path (not abandoned, only deferred).
- No paid infrastructure, under either transport choice. The moment paid infrastructure would
  become necessary, implementation stops and this is reported rather than decided unilaterally.

여기까지다.

## Addendum 2 (2026-10-07) — Named Tunnel confirmed, Cloudflare-specific setup script requested

Verbatim follow-up: the user confirmed Cloudflare Named Tunnel as the transport (not ipTIME
WireGuard, not Spring exposed directly, origin = external-relay only, 3 existing quick tunnels
kept until Named Tunnel E2E succeeds, no paid Cloudflare features), and requested a single
idempotent command, `scripts\windows\external\setup-running-ai-external-access.ps1`, to perform
the entire Cloudflare Named Tunnel + Windows Service setup, stopping at any point requiring real
browser interaction (`cloudflared tunnel login`) rather than guessing or bypassing it. Implemented
in commit `19776a2` (`RunningAI.CloudflaredSetup.ps1`, the orchestrator script, and
`Test-CloudflaredSetup.ps1`, 26 checks) - see progress note 2 for the full result.

## Addendum 3 (2026-10-09) — Transport pivoted to Tailscale Funnel

Verbatim follow-up, received before the Cloudflare path's live `cloudflared tunnel login`/hostname
step was ever run by the user:

> RunningAI Phase 6I-1 external transport를 Cloudflare Named Tunnel에서
> Tailscale Funnel로 전환한다.
>
> 이유:
> - 별도 도메인을 구매하지 않는다.
> - 월 유료 인프라 0원을 유지한다.
> - Tailscale Funnel의 stable *.ts.net HTTPS hostname을 사용한다.
> - ipTIME/WireGuard/port-forwarding은 사용하지 않는다.
> - Main PC 관리 작업은 직접 PC에서 수행한다.
>
> 기존 완료 작업은 보존:
> - tools/external-relay relocation
> - existing /today-workout contract
> - Bearer token auth
> - Node regression tests
> - external relay lifecycle scripts
> - PowerShell tests
> - legacy relay는 cutover 전까지 유지
>
> Cloudflare-specific implementation은 더 진행하지 않는다.
> 기존 Cloudflare setup script/docs는 삭제하지 말고 historical/deferred 상태로 남기거나
> 명확히 deprecated/deferred 표시한다.
>
> 새 최종 사용자 UX:
>
> .\scripts\windows\external\setup-running-ai-tailscale-funnel.ps1
>
> 요구사항:
>
> 1. idempotent/resumable.
> 2. Tailscale 설치 여부 검사.
> 3. 설치되어 있지 않으면 자동 설치 가능 여부를 검토하고,
>    불확실하면 공식 설치 안내 후 STOP.
> 4. tailscale status로 로그인 상태 검사.
> 5. 로그인 안 되어 있으면 `tailscale up` 실행.
>    browser/user interaction이 필요한 순간 정확히 안내하고 STOP/대기.
> 6. Funnel prerequisites를 검사:
>    - supported Tailscale version
>    - MagicDNS
>    - HTTPS
>    - Funnel enablement
> 7. external relay가 127.0.0.1:17845에서 healthy인지 확인.
> 8. Funnel target은 반드시 127.0.0.1:17845.
> 9. Spring 8080과 Garmin connector 8765는 절대 Funnel에 직접 노출하지 않는다.
> 10. `tailscale funnel --bg http://127.0.0.1:17845`
>     형태를 현재 CLI 문법에 맞게 사용.
> 11. Funnel hostname을 자동 감지하고 저장.
> 12. /health와 /today-workout 외부 HTTPS 검증.
> 13. 기존 Bearer token auth를 그대로 보존.
> 14. 인증 없이 /today-workout이 성공하면 FAIL.
> 15. unsafe methods/paths는 relay contract대로 차단.
> 16. secret/token은 console/log/Git/docs에 출력 금지.
> 17. Funnel은 public endpoint이므로 relay auth가 mandatory임을 문서화.
> 18. Windows reboot 후 Funnel config가 유지되는지 공식 Tailscale 동작을 확인하고,
>     필요하면 최소한의 startup integration만 추가.
> 19. 기존 quick Cloudflare tunnel은 Tailscale E2E 성공 전까지 종료하지 않는다.
> 20. 실제 Garmin 265 + LTE/5G + reboot acceptance 전까지
>     PHASE_6I_1_EXTERNAL_ACCESS_E2E_READY 선언 금지.
>
> 테스트:
> - PowerShell regression 유지
> - relay Node regression 유지
> - new Tailscale setup tests 추가
> - DryRun은 실제 Tailscale/Funnel 상태 변경 0
> - no secrets
> - no Spring server changes
>
> 먼저 구현/테스트까지만 하고,
> 실제 `tailscale up` 또는 Funnel enablement처럼 사용자 browser interaction이 필요한 지점에서
> STOP해서 정확한 명령을 알려줘.

New standing constraints recorded from this addendum:

- Cloudflare Named Tunnel work (`RunningAI.CloudflaredSetup.ps1`,
  `setup-running-ai-external-access.ps1`, `Test-CloudflaredSetup.ps1`) is DEFERRED, not deleted -
  kept as a historical/fallback reference, clearly marked, in case Tailscale Funnel does not work
  out.
- The 3 pre-existing ad hoc Cloudflare quick-tunnel processes stay running and untouched until a
  real Tailscale Funnel end-to-end run succeeds (same "do not disrupt a possibly-still-in-use
  path" principle as every prior transport decision in this phase).
- Funnel has no equivalent of Cloudflare Access: the relay's own Bearer-token check on
  `/today-workout` is now the ONLY auth boundary for the public endpoint, not one layer among
  several - this must be documented, not merely true in passing.
- Exact current Tailscale CLI syntax was verified against Tailscale's own published docs
  (tailscale.com/kb/1223/funnel, /kb/1080/cli, /kb/1242/tailscale-serve) rather than assumed from
  training-data memory alone, given how much CLI surface has changed across Tailscale versions.
