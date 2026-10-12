# WO-RUNNINGAI-PHASE-7C-1 — ChatGPT MCP 연결 가능성 검증 및 보안 PoC (결과 보고)

지시서: `2026-10-12-phase-7c-1-chatgpt-mcp-connectivity-poc-instruction.md`

기준: `main-3` HEAD `b173c3b`에서 시작. 운영 Spring/PostgreSQL/Connector/Relay·Tailscale
Funnel(:443)·운영 Flyway·Garmin/Intervals 계정·origin/main: **전부 무변경**. 인증 정보는
어디에도 커밋하지 않았다(PoC는 비밀값 자체를 갖지 않음). 7B-2 안전 게이트는 접근조차 하지
않았다(PoC는 Spring과 완전 분리).

## STEP 0 — ChatGPT 환경 확인 (공식 문서 기준)

OpenAI 공식 developers 문서 2건을 직접 확인했다
([Add custom MCP server](https://developers.openai.com/api/docs/guides/custom-mcp-server),
[Secure MCP Tunnel](https://developers.openai.com/api/docs/guides/secure-mcp-tunnels)):

1. **Add custom MCP server**: 존재. ChatGPT(웹) **Plugins → ＋ → Add custom MCP server**.
   이름/설명 → Connection(Server URL **또는 Tunnel**) → 인증 설정 → 위험 고지 동의 →
   "Create as a plugin" → 플러그인 설치 후 대화에서 사용.
2. **개인/워크스페이스 플러그인 경로**: 플러그인은 personal/workspace 양쪽 컨텍스트에서
   설치 가능하며 "Workspace permissions and security restrictions (Lockdown 포함) apply".
   문서가 플랜별 가용성을 명시하지 않으므로 **플랜별 추측은 하지 않는다** — 소유자 계정
   UI에서의 실측 확인 항목으로 남긴다(아래 "사용자에게 요청하는 조작").
3. **Secure MCP Tunnel**: 사용 가능(2026-05 출시). OpenAI Platform에서 tunnel 생성 →
   `tunnel_id` 발급 → 오픈소스 `tunnel-client`(Apache-2.0, 최신 v0.0.16, Windows amd64
   바이너리 제공)가 내부망에서 `api.openai.com:443/v1/tunnel/*`로 **아웃바운드 전용**
   long-poll → 로컬 MCP 서버로 JSON-RPC 전달. **인바운드 포트/공개 엔드포인트 불필요.**
4. **Platform 조직 ↔ ChatGPT workspace 연결 조건**: tunnel은 Platform 조직 권한
   (Read/Manage/Use)으로 관리되고, ChatGPT에서 보이려면 **해당 tunnel을 대상 ChatGPT
   workspace에 명시적으로 연결(associate)** 해야 하며 조작자는 Tunnels **Read + Use** 권한
   필요. 권한 부여 후 반영에 최대 30분.
5. **필요 계정 요소(문서 기준)**: OpenAI Platform 계정/조직 + runtime API key(`sk-...`,
   tunnel-client 실행용) + ChatGPT 유료 계정(커스텀 MCP 추가 UI 접근) + 위 workspace 연결.
   인증 옵션: **OAuth(정적 자격증명 / CIMD / DCR 모두 지원) 또는 No authentication** —
   7C-0 조사 때의 "DCR 필수" 전제는 현행 문서 기준으로 **완화되었음을 확인**(지시서 STEP 1의
   "DCR을 필수로 고정하지 않는다"와 부합). 쓰기 도구는 기본적으로 실행 전 확인 모달,
   읽기 전용 판정은 `readOnlyHint` annotation을 따른다.

## STEP 1 — 연결 방법 결정: **A. Secure MCP Tunnel (채택)**

- 공개 HTTPS 엔드포인트가 아예 생기지 않아 "무인증 인터넷 노출 금지" 제약을 구조적으로
  충족하고, **기존 Tailscale Funnel(:443)을 전혀 건드리지 않는다** — B안(Funnel 8443 +
  OAuth 게이트웨이, 7C-0 설계)은 Tunnel 불가 시의 예비안으로 격하.
- 전송 인증은 OpenAI 조직 컨텍스트 + tunnel-client의 runtime API key가 담당. 로컬 MCP는
  127.0.0.1 전용이라 tunnel-client 외에는 도달 불가.
- **실서비스(7C-2, 실데이터) 전제 조건으로 기록**: Tunnel이 전송을 보호하더라도, 실데이터
  도구 활성 시에는 (a) ChatGPT workspace 연결·플러그인 설치 권한이 소유자 1인에게만 있고
  (b) 도구 scope가 읽기 전용으로 강제됨을 재확인한 뒤 진행한다. 필요 시 로컬 MCP 앞 bearer
  검증(PoC에 이미 구현)을 심층방어로 유지한다.

## STEP 2 — 격리된 MCP PoC 구현 (`tools/mcp-poc`)

- **완전 독립 프로세스**: 의존성 없는 Node 단일 파일(`server.js`), **127.0.0.1 하드코딩**
  바인딩(설정 불가), 기본 포트 17901 — 운영 Spring(18080)·Connector(8765)·Relay(17845)와
  무관. Streamable HTTP stateless `POST /mcp`(initialize/ping/tools/list/tools/call),
  `GET /health`.
- 도구는 `ping_running_ai` **하나뿐**, 지시서의 고정 fake 응답을 그대로 반환. 파일 내에
  DB 드라이버·외부 HTTP 호출 코드 자체가 없다(구조적 보장). 토큰/개인데이터는 응답·로그
  어디에도 없음(Relay와 동일한 REDACT 로깅 패턴).
- 선택적 bearer 인증(`RUNNING_AI_MCP_POC_TOKEN`, timing-safe 비교) — 401 경로 검증용.

## STEP 3 — 읽기/쓰기 분리 검증 (전 항목 자동 테스트로 증명)

`tools/mcp-poc/test/poc-protocol-test.js` — **23/23 PASS** (실제 서버 2회 기동):

| 지시서 요구 | 검증 결과 |
|---|---|
| tools/list에 쓰기 도구 없음 | PASS — 정확히 1개(`ping_running_ai`), `publish_workout` 부재 단정 |
| 강제 `publish_workout` 호출 거부 | PASS — 서버 측 JSON-RPC `-32602` "write/operational tool is not available" (이름 숨김이 아닌 명시적 차단; 프로세스 자체에 쓰기 코드가 없어 2중) |
| 읽기 scope로 쓰기 실행 불가 | 해당 구조 자체가 없음 — PoC에는 쓰기 도구가 존재하지 않고, 미지 도구는 전부 `-32602` 거부(테스트 포함). scope 모델은 7C-2 설계에 반영(STEP 5) |
| 임의 REST 프록시 호출 불가 | PASS — `/mcp` 외 모든 경로 404, 프록시/아웃바운드 코드 부재 |
| 운영 Spring `/mcp` 직접 공개 금지 | PASS — 운영 인스턴스에 `POST /mcp` 실측 **404**(MCP 스위치 기본 off 그대로), 노출 작업 일절 없음 |
| readOnlyHint 사용 범위 | PASS — 진짜 읽기 전용(고정 응답, 부작용 0)인 `ping_running_ai`에만 `readOnlyHint:true` |

## STEP 4 — 연결·인증 검증 결과

로컬(실서버 기동, fetch 기반 자동 테스트):

1. `initialize` — PASS (2025-06-18/2025-03-26/2024-11-05 버전 협상, 미지 버전은 최신으로 fallback)
2. `tools/list` — PASS 3. `ping_running_ai` — PASS (고정 TEST_ONLY payload, 토큰류 문자열 부재 단정)
4. 인증 실패 — PASS (무토큰 401, 오토큰 401, 정상 토큰 200; `WWW-Authenticate: Bearer`)
5. 비인가 접근 차단 — 127.0.0.1 전용 바인딩(코드 고정) + 선택적 bearer
6. 타임아웃/재연결 — 전송이 tunnel-client 소관(자체 long-poll 재연결, `/healthz`·`doctor` 제공);
   PoC 서버는 stateless라 재연결 시 상태 손실 없음. 실연결 후 항목으로 이월
7. **실제 ChatGPT에서 도구 선택** — **PENDING_USER**: 사용자 계정 조작(아래)이 필요하며,
   지시서 원칙대로 **사용자 계정에서 확인되기 전에는 PASS로 기록하지 않는다**

OAuth metadata/PKCE/issuer·audience 검증은 공개 HTTPS(B안)를 선택하지 않았으므로 해당 없음
(지시서 조건부 요구사항).

### 사용자에게 요청하는 조작 (이 Phase가 멈추는 지점)

1. **ChatGPT UI 실측**: 소유자 계정(웹)에서 Plugins → ＋ → "Add custom MCP server"가
   보이는지, Connection에 **Tunnel** 옵션이 있는지 확인(스크린샷 권장).
2. **OpenAI Platform**: 조직 설정에서 Tunnel 생성 → `tunnel_id` 확보, runtime API key 발급,
   해당 tunnel을 ChatGPT workspace에 연결(associate), 본인 권한 Tunnels Read+Use 확인.
3. **메인 PC에서** (제가 이후 단계에서 대행 가능한 부분): `tunnel-client` v0.0.16(Windows
   amd64) 다운로드·검증 → `node tools/mcp-poc/server.js` 기동 →
   `tunnel-client init --profile runningai-poc --tunnel-id <id> --mcp-server-url
   http://127.0.0.1:17901/mcp` → `doctor` → `run`.
4. ChatGPT에서 플러그인 생성(Connection: Tunnel)·설치 후 "ping_running_ai 도구를 호출해줘"
   — 응답의 `"RunningAI MCP connection verified"` 확인 결과를 알려주시면 STEP 4-7을 PASS로
   확정 기록한다.

(1·2에서 플랜/권한 제약으로 Tunnel 옵션이 없으면: 이 Phase는 "A안 불가"로 보고를 갱신하고
B안(7C-0의 Funnel 8443 + OAuth 게이트웨이) 재검토로 넘어간다 — 운영을 건드려 억지로 연결하지
않는다.)

## STEP 5 — 7C-2 읽기 전용 도구 설계 확정

공통 규칙: 모든 도구 **DB-only 읽기**(Garmin Sync/Intervals 호출/Draft 저장/게시/DB 변경
없음 — 기존 읽기 서비스·`@Transactional(readOnly)` 경로만 호출), `readOnlyHint:true`,
응답에 외부 ID·GPS·원본 페이로드·자격증명 불포함, null=미상(추정 금지), 측정만 제공.
등록 스위치 `running-ai.mcp.read-enabled`(기본 false, 쓰기 스위치와 독립), 아키텍처 테스트로
읽기 도구 소스의 publish/draft/Garmin-write 타입 참조 금지. 공통 예외 코드:
`INVALID_ARGUMENT`, `NOT_FOUND`, `INSUFFICIENT_DATA`, `INTERNAL_ERROR`(상세 미노출).

| 도구 | 입력(JSON Schema 요지) | 출력 요지 | 개인정보 최소화 |
|---|---|---|---|
| `get_recent_activities` | `{days?:int 1..90=14, limit?:int 1..20=10}` | `[{date,type,durationSeconds,distanceMeters?,avgHr?,maxHr?,analysisAvailable,intervalsMatched}]` | 내부/외부 ID 미노출 — 날짜+유형으로 지칭 |
| `get_activity_analysis` | `{date:date}` 또는 `{index?:int=0}`(최근 n번째) | 6H-4 분석 + 7B-3 runningDynamics 요약(halves·decoupling·zone%·LTHR노출·다이내믹스) | 샘플/GPS 미노출, 요약만 |
| `get_training_context` | `{date?:date=today}` | TrainingContextV2 스냅숏(기존 preview와 동일 — LTHR provenance 경고 포함) | V2 기존 금지필드 규칙 그대로 |
| `get_recovery_context` | `{date?:date=today}` | HRV/수면/RHR/BodyBattery/스트레스 + 28일 기준선 | 측정+기준선만, 등급 없음 |
| `get_fitness_trend` | `{oldestDate:date, newestDate:date}` (범위≤90d, 초과 시 INVALID_ARGUMENT) | `[{date,ctl,atl,derivedForm}]` | — |
| `get_workout_recommendation` | `{date?:date=today}` | 결정론적 intent·시간범위·강도·근거(기존 서비스 — **아무것도 영속하지 않음을 확인했으므로 읽기 전용으로 분류**) | — |
| `get_athlete_profile` | `{}` | LTHR/역치페이스 + provenance(`sourceTracked:false` 경고 포함) | 값+메타만 |

## STEP 6 — 운영 보호 준수

운영 Spring/PG/Connector/Relay 무변경·무재시작(건강 확인용 읽기 GET만). Funnel :443 무변경
(Funnel 명령 자체를 실행하지 않음). V21/V22 운영 미적용. Garmin/Intervals 쓰기 0회(이번
Phase는 두 시스템에 읽기 호출도 없음). origin/main 무접촉. 커밋에 비밀값 없음(PoC 토큰은
테스트 하드코딩 더미뿐). 7B-2 게이트 코드 무접촉.

## 완료 보고 요약

1. **연결 가능 여부**: 문서·로컬 기준 **가능 판정**(Tunnel 경로 공식 지원 + 전송·프로토콜
   로컬 전수 검증). 최종 확정은 사용자 계정 실측 1회가 남음(PENDING_USER).
2. **필요 권한**: ChatGPT 유료 계정 + OpenAI Platform 조직(runtime API key, Tunnel
   Read+Use, workspace 연결).
3. **방식**: A. Secure MCP Tunnel — 공개 엔드포인트 0개, Funnel 무변경이 결정적 이유.
4. **격리 환경**: `tools/mcp-poc`(127.0.0.1:17901, 의존성 0, 운영과 완전 분리).
5. **MCP 초기화·도구 실행**: 로컬 23/23 PASS.
6. **인증 검증**: 401/200 경로 검증 완료(로컬); Tunnel 전송 인증은 OpenAI 측 메커니즘.
7. **쓰기 노출 방지**: 목록 부재 + 서버 측 거부 + 코드 부재 3중, 자동 테스트로 증명.
8. **운영 영향**: 없음.
9. **7C-2 GO/NO-GO**: **조건부 GO** — 사용자 계정에서 STEP 4-7(ChatGPT 실호출) PASS 확인
   후 착수. Tunnel 옵션이 계정에서 불가로 판명되면 NO-GO로 전환하고 B안 재설계.

STOP (사용자 조작 대기).
