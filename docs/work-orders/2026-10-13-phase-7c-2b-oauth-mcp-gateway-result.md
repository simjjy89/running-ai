# WO-RUNNINGAI-PHASE-7C-2b — Auth0 OAuth 인증 및 MCP Gateway 구현 (결과 보고)

지시서: `2026-10-13-phase-7c-2b-oauth-mcp-gateway-instruction.md`

기준: `main-3` `0663419`에서 시작. 개발·격리 테스트까지 완료 — **STEP 4(Auth0 테넌트)와
STEP 6(ChatGPT OAuth 실연동)은 사용자 계정 조작이 필요한 지점이라 지시대로 거기서 멈춘다.**
운영 Spring/PostgreSQL/Flyway/Connector/Intervals/Watchface/Relay/Funnel 무변경, Canonical·
origin 무접촉, 인증 정보·토큰 커밋 0건(이번 Phase에는 실제 비밀값 자체가 존재하지 않음 —
테스트 키는 실행 시 메모리에서 생성).

## STEP 1 — 7C-2a 검토 결과

- `read-enabled` 기본 false, 쓰기 차단, Injury Gate 유지, 조회 무부작용: 7C-2a의 기존 자동
  테스트가 전부 커버하고 있음을 재확인(해당 스위트 재실행 PASS).
- **신규 회귀 추가 1**: `McpServerSwitchEnvironmentPostProcessorTest` — read/write 4조합 OR
  진리표 + 미설정=off + **직접 설정된 `spring.ai.mcp.server.enabled`가 계산값에 덮임**
  (도구 그룹 0개인 채로 서버만 떠 있는 상태가 불가능함을 고정).
- **신규 회귀 추가 2**: 같은 날짜에 활동 2개일 때 `get_activity_analysis`가 늦은 쪽을
  결정적으로 선택하고, `get_recent_activities` 목록(최신순)과 합치함을 단정 — ID를 노출하지
  않는 설계에서 날짜가 유일한 cross-tool 핸들이라는 식별 방식의 명시적 검증.

## STEP 2·3 — Gateway 구현 (`tools/mcp-gateway`, Node ESM + `jose`)

구조: `server.js`(단일 파일 resource server) + `package.json`(`jose ^6` — 유지보수되는 검증
라이브러리, 자체 암호 구현 없음; JWKS는 `createRemoteJWKSet` 캐시/회전 내장) + 자동 테스트.

| 지시 기능 | 구현 |
|---|---|
| ① RFC 9728 메타데이터 | `GET /.well-known/oauth-protected-resource`(+`/mcp` path-appended형) — resource/AS/scope만, 데이터 없음 |
| ② 401 WWW-Authenticate | RFC 6750 `Bearer ... error, resource_metadata="..."` (invalid_request/invalid_token/insufficient_scope 구분) |
| ③ AS metadata 연동 | issuer의 `openid-configuration` → `oauth-authorization-server` 순 discovery로 `jwks_uri` 획득(지연+캐시, 실패 시 503) |
| ④ PKCE S256 | AS(Auth0)-클라이언트(ChatGPT) 간 흐름이라 게이트웨이는 관여하지 않음 — 토큰 검증만(사양 확인은 STEP 4 항목) |
| ⑤ CIMD/DCR/사전등록 | 클라이언트 등록도 AS 소관 — Auth0 "Auth for MCP"(2026-05 GA)가 CIMD·DCR 지원(7C-2a STEP 7 조사), 테넌트에서 실측 예정 |
| ⑥ resource 호환 | audience=resource(`PUBLIC_BASE_URL/mcp`)로 고정하는 설계 + Auth0 Resource Parameter Compatibility는 STEP 4 실측 항목 |
| ⑦⑧ 토큰 검증 | `jwtVerify`: signature/issuer/audience/exp/nbf/**alg RS256 고정**/kid, clockTolerance 5s |
| ⑨ allowlist | `sub` 정확히 1건(`RUNNING_AI_ALLOWED_SUB`), scope `runningai:read` 필수 |
| ⑩ Fail Closed | 필수 env 누락·allowlist에 publish_workout → **기동 거부(exit 1)**; JWKS/AS 장애 → 503; 모든 검증 실패 → 401/403, **백엔드 무접촉** |

보안 경계(STEP 3): 127.0.0.1 바인딩 하드코딩, 프록시 대상은 기동 시 고정된 loopback URL
1개(비-loopback이면 기동 거부 — REST 프록시 불가), method allowlist(initialize/ping/
tools/list/tools/call(+notifications)), tool allowlist(기본=읽기 7종; `publish_workout`은
allowlist에 올리는 것 자체가 기동 거부), **tools/list 응답도 allowlist로 재필터링**(백엔드가
오동작해도 쓰기 도구명이 밖으로 안 나감), 비인증 상태에서 접근 가능한 것은 메타데이터와
/health뿐(도구 목록조차 토큰 필요), 토큰·헤더·바디 무로깅. 운영 Spring(18080)은 어떤 설정
조합에서도 대상이 아님(기본 대상 17902 PoC; 이후 개발 인스턴스).

callback URL: 지시대로 **추정하여 고정하지 않음** — 게이트웨이는 callback을 다루지 않고
(AS 소관), Auth0 redirect allowlist에 넣을 정확한 값은 STEP 6에서 ChatGPT 설정 화면 실측값
으로 입력한다.

## STEP 5 — Mock OAuth/JWKS 자동 테스트: **27/27 PASS**

`test/gateway-test.js`: 로컬 mock AS(진짜 RSA 키쌍 + JWKS + discovery)와 **호출 횟수를 세는
가짜 백엔드**를 띄우고 실제 게이트웨이 프로세스를 구동. 지시된 부정 케이스 전수:

토큰 없음 / 타키 서명 / 변조 JWT / 만료 / 미래 nbf / 오issuer / 오audience / scope 누락 /
비허용 sub / HS256(비허용 alg) / 미지 kid / JWKS 장애(503) / publish_workout 강제(-32602) /
비허용 method(-32601) / 임의 경로 404·GET 405 — **전부 통과했고, 이 모든 비인가 요청 동안
백엔드 히트 카운터 0을 별도 단정**. ("잘못된 resource"는 audience 불일치 케이스로 커버 —
RFC 8707 resource는 토큰 발급 시 audience로 귀결.)

긍정 경로: 정상 토큰의 tools/list·tools/call 프록시 성공(정확히 2회만 백엔드 도달),
**오동작 백엔드가 publish_workout을 목록에 넣어도 게이트웨이가 걸러냄** 단정. 기동 검증
2종(설정 누락/금지 allowlist → exit 1)까지 포함.

Spring 전체 회귀(`clean test`): **1,264개 전부 PASS, 실패 0** (7C-2a 1,257 + 신규 7:
postprocessor 6(파라미터라이즈 4 + 2) + 동일날짜 선택 1). 중복 실행 없음.

## STEP 4·6 — 사용자 조작 대기 (여기서 STOP)

### 요청 1 — Auth0 테넌트 준비 (STEP 4, 1회)

1. https://auth0.com 무료 가입 → 테넌트 생성(리전 무관).
2. **APIs → Create API**: Name `RunningAI MCP`, Identifier는 일단 임시값
   `https://runningai-mcp.local/mcp` (실연동 직전에 임시 터널 주소로 교체/재생성 예정 —
   "주소 변경 시 재등록 필요 설정"이 바로 이것). Signing RS256.
3. API → Permissions(scopes)에 `runningai:read` 추가.
4. **User Management → Users → Create User**(본인 이메일/비밀번호 1명). 생성 후 그 사용자
   상세의 `user_id`(`auth0|...`)를 확보 — 게이트웨이 `RUNNING_AI_ALLOWED_SUB`에 들어갈 값.
5. **공개 가입 차단**: Authentication → Database → (기본 커넥션) → **Disable Sign Ups** ON.
6. Dashboard에서 "Auth for MCP"/CIMD·DCR 관련 설정 메뉴가 보이는지, 무료 플랜 제약 고지가
   있는지 화면 기준으로 확인(유료 요구 시 결제하지 말고 그 화면 내용을 알려주세요).
7. 완료 후: **테넌트 도메인**(`https://<tenant>.<region>.auth0.com/`)과 **user_id** 두 값만
   알려주세요(비밀번호·클라이언트 시크릿은 절대 공유 금지 — 필요하지도 않습니다).

### 요청 2 — ChatGPT 설정 화면 실측 (STEP 2 잔여)

ChatGPT → Plugins → Add custom MCP server 화면에서 OAuth 선택 시 표시되는 **callback/redirect
URL**(있다면)과 클라이언트 등록 방식 안내(CIMD 자동/수동 client_id 입력 여부)를 캡처 또는
그대로 알려주세요 — Auth0 redirect allowlist·등록 방식(CIMD vs DCR vs 사전등록)을 실측값으로
확정합니다.

두 요청이 완료되면 STEP 6을 진행합니다: 게이트웨이+PoC 로컬 기동 → Quick Tunnel 발급 →
Auth0 API identifier/audience를 그 주소로 맞춤 → ChatGPT OAuth 연결 → ①~⑧ 검증(미인증
거부·타권한 거부 포함) → 임시 터널 정리 — 전 과정에서 실데이터 대신 ping 고정 응답만
사용(게이트웨이 대상=17902 PoC).

## 7C-2c 운영 연결 선행 조건 (불변)

① 운영 V21/V22 적용+Spring 재기동 승인 ② Funnel 443에 `/mcp`·`/.well-known/
oauth-protected-resource` 경로 마운트 승인(Relay `/` 불변) ③ STEP 6 E2E PASS ④ 운영
게이트웨이 대상 전환(PoC→운영 /mcp, read-enabled=true·write off) 각각 별도 승인.

## GO/NO-GO

**조건부 GO** — 코드·자동 검증은 전부 완료(27/27 + 1,264/1,264). 남은 변수는 Auth0 무료
플랜에서의 CIMD/DCR 실측(요청 1-6)과 ChatGPT OAuth 화면 실측(요청 2)뿐이며, 어느 쪽이든
실측 결과에 따라 사전등록 클라이언트 fallback으로 전환 가능한 설계다.
