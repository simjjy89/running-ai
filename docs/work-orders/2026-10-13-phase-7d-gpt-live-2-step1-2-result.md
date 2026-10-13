# Phase 7D-GPT-LIVE-2 — STEP 1·2 결과 보고 (Auth0 없는 OAuth 확정 + 연결부 구현, 사용자 조작 대기)

지시서: `2026-10-13-phase-7d-gpt-live-2-instruction.md`
기준: `main-3` HEAD `34cf121`, 작업 트리 clean(미커밋 7D-2 문서 3건 제외).

**이번 보고 범위: STEP 1(재사용 검토) + STEP 2(방식 확정 및 연결부 구현)까지.**
STEP 3~6은 Cloudflare 계정·GitHub OAuth 앱·Worker 배포가 선행되어야 하므로 지시서의 승인
기준에 따라 **절차를 제시하고 STOP**한다. 외부 노출 0건, 실데이터 접속 0건, 운영 변경 0건.

---

## STEP 1 — 기존 구현 재사용 검토 (재개발 0건)

| 구성요소 | 상태 | 이번 Phase 처리 |
|---|---|---|
| `tools/mcp-gateway` (7C-2b) | OAuth 2.1 resource server, 29/29 PASS | **코드 무변경**. env 4개만 교체 |
| Spring `/mcp` + 읽기 도구 7종 (7C-2a) | 실데이터 정확성 검증 완료(7C-2 실데이터 보고) | **무변경** |
| `server/discover` 호환 처리 (7C-3) | 미인증 401+`resource_metadata`, 인증 후 JSON-RPC −32601 | **무변경**, 새 체인에서 재검증 |
| Quick Tunnel 실행 절차 (7C-1/7C-2) | 검증됨 | **유지**, 단 ChatGPT가 아니라 Worker가 이 주소를 바라보도록 위치만 이동 |
| 7C-2 합성 스모크 | 통과 | STEP 3에서 재사용 |

### 게이트웨이의 "Auth0 의존성" 실측 — **공급자 종속성 0건**

`tools/mcp-gateway/server.js` 전수 확인 결과, Auth0 고유 속성(테넌트 API, `auth0|` 접두사,
Management API, 커스텀 클레임)에 의존하는 코드는 **한 줄도 없다.** 검증하는 것은 표준 OAuth
속성뿐이다.

| 지시서 점검 항목 | 구현 위치 | 공급자 종속 |
|---|---|---|
| issuer | `jwtVerify({ issuer: ISSUER })` + `${ISSUER}.well-known/openid-configuration` → `oauth-authorization-server` 순차 탐색 | 없음 (RFC 8414/OIDC Discovery) |
| audience | `jwtVerify({ audience: AUDIENCE })` | 없음 |
| JWKS | 메타데이터의 `jwks_uri` → `createRemoteJWKSet` (jose, 캐시·로테이션 자동) | 없음 |
| RS256 | `algorithms: ['RS256']` 고정 | 없음 |
| scope `runningai:read` | `scope`(문자열/배열) 또는 `scp` 모두 수용 | 없음 |
| subject allowlist | `payload.sub !== ALLOWED_SUB` → 403 | 없음 (문자열 비교) |

**판정: 인증 공급자 교체 가능.** 조건은 단 두 가지 — ① 발급자가 issuer 기준 메타데이터에
`jwks_uri`를 공개할 것 ② 토큰이 **RS256 서명 JWT**일 것.

---

## STEP 2 — 방식 확정

### 결정적 사실: Workers OAuth Provider의 토큰은 JWT가 아니다

지시서의 경고("동일하다고 가정하지 않는다")대로 문서가 아니라 **배포된 번들을 직접 읽어**
확인했다 (`@cloudflare/workers-oauth-provider@1.2.3`).

```
dist/oauth-provider.js:4883  const accessToken = `${userId}:${grantId}:${generateRandomString(TOKEN_LENGTH)}`
dist/oauth-provider.js:4489  tokenData = await env.OAUTH_KV.get(`token:${userId}:${grantId}:${id}`, { type: 'json' })
```

발급 토큰은 **불투명(opaque) 문자열**이며, 검증은 SHA-256 해시로 `OAUTH_KV`를 조회하는
것이다. 서명도 JWKS도 없고, `docs/resource-servers.md`는 RFC 7662 introspection 엔드포인트가
없으며 Cloudflare 밖의 resource server는 이 토큰을 검증할 수 없다고 명시한다.

→ **A·B안을 그대로 적용하면 기존 게이트웨이는 토큰을 검증할 수단이 없다.** 선택지는
(가) 게이트웨이의 검증을 끄거나 헤더를 무조건 신뢰 — **지시서가 금지**, (나) Worker가 resource
server 역할을 맡고 **로컬 구간에 대해 명시적 신뢰 경계를 새로 구현** 둘뿐이다. (나)를 택했다.

### 확정안 — B (Workers OAuth Provider + GitHub 로그인) + 내부 RS256 홉 토큰

```
ChatGPT ──OAuth 2.1 (PKCE S256, CIMD/DCR)──► Worker (고정 HTTPS, *.workers.dev)
                                              │  AS: GitHub 로그인, 사용자 1명, 불투명 토큰(KV)
                                              │  RS: scope + subject 확인
                                              ▼  자체 키로 90초짜리 RS256 JWT 발급 + JWKS 공개
                                      cloudflared Quick Tunnel
                                              ▼
                    tools/mcp-gateway (무변경) — 서명/iss/aud/exp/nbf/alg/kid/scope/sub 검증
                                              ▼
                              dev Spring 18081 /mcp → 읽기 도구 7종
```

**A안(Cloudflare Access)을 택하지 않은 이유:** Access는 Worker 앞단의 *상위 로그인 수단*일 뿐
토큰 형식 문제를 전혀 바꾸지 못한다(위 구조가 똑같이 필요). 대신 Zero Trust 팀 도메인 설정이
추가되고, ChatGPT는 DCR/CIMD가 필요한데 Access는 사전 등록 클라이언트만 지원하므로 AS가 될
수도 없다. 비용·단계 모두 늘고 얻는 것이 없다. GitHub 로그인은 소유자가 이미 쓰는 계정이며
`read:user` 스코프(신원 조회)만 요구한다. A안은 README에 대안으로만 남긴다.
C안(다른 표준 AS)은 B가 전 항목을 충족하므로 불필요.

### 지시서 검증 10항목 — 전부 로컬 실측으로 확인

`wrangler dev --local`(계정 불필요)로 실제 기동해 확인한 값이다.

| # | 항목 | 결과 | 근거 |
|---|---|---|---|
| 1 | Authorization Code + PKCE **S256** | ✅ | AS 메타데이터 `code_challenge_methods_supported:["S256"]`, `/authorize`에 S256 challenge 전달 시 302 정상 |
| 2 | CIMD 또는 DCR 등록 | ✅ **양쪽 다** | `client_id_metadata_document_supported:true`, `registration_endpoint:/register` — 실제 DCR 등록으로 `client_id` 발급 성공(ChatGPT 콜백 URL·`token_endpoint_auth_method:none`) |
| 3 | protected resource metadata | ✅ | `/.well-known/oauth-protected-resource/mcp` → `{resource, authorization_servers, scopes_supported:["runningai:read"], bearer_methods_supported:["header"]}` |
| 4 | authorization server discovery | ✅ | `/.well-known/oauth-authorization-server` 정상. `authorization_response_iss_parameter_supported:true` → ChatGPT의 **고정 콜백**(`https://chatgpt.com/connector_platform_oauth_redirect`) 사용 가능 |
| 5 | 사용자 1명 제한 | ✅ **3중** | 로그인 시 GitHub 숫자 id+login 대조 → 요청마다 `auth.userId`/`props.githubId` 대조 → 게이트웨이 `sub` allowlist |
| 6 | read-only scope | ✅ | `requiredScopes:["runningai:read"]`, 게이트웨이가 다시 요구. 쓰기 도구는 애초에 allowlist 불가(기동 거부) |
| 7 | 만료·refresh | ✅ | access 1시간, refresh 30일(라이브러리 기본), 홉 토큰 90초. ChatGPT가 refresh 수행 |
| 8 | 무료 계정 | ✅ | Workers free 100k req/일 · KV free(읽기 10만/쓰기 1천 일) · `workers.dev` 무료 · GitHub OAuth 앱 무료 · Quick Tunnel 무료. **비용 0** |
| 9 | 게이트웨이 변경 범위 | ✅ **코드 0줄** | env 4개(issuer/audience/allowed_sub/public_base_url)만 교체 |
| 10 | 고정 HTTPS 주소 | ✅ | `https://running-ai-mcp.<sub>.workers.dev` — Quick Tunnel 주소가 바뀌어도 **ChatGPT 재등록 불필요**, Worker 변수 `ORIGIN_MCP_URL` 1개만 갱신 후 재배포(약 15초) |

10번이 지시서가 지적한 "Quick Tunnel URL이 매번 달라지는 문제"의 해법이다. 터널은 Worker
뒤로 숨고, 공개 주소는 영구 고정된다.

### 신뢰 경계 구현 (검증 우회 0건)

`src/internal-jwt.js`가 Worker→게이트웨이 1홉 전용 RS256 JWT를 발급한다. 모든 클레임은
**인증된 값에서만** 생성된다 — `sub`는 OAuth 계층이 실제로 인증한 신원(상수 아님), `scope`는
그 토큰이 실제로 가진 scope. 개인키는 Worker secret에만 존재하고 공개키는 Worker가 JWKS로
공개하므로, 게이트웨이는 **기존 메타데이터 탐색 경로 그대로** 키를 찾는다.

게이트웨이가 하던 검증은 하나도 줄지 않았다: 서명·issuer·audience·exp·nbf·RS256 고정·미지의
kid 거부·scope·subject. 토큰을 무조건 승인하거나 검증을 끄는 경로는 코드에 존재하지 않는다.

**Worker가 일부러 복제하지 않은 것:** MCP 메서드 allowlist, 읽기 도구 allowlist, `tools/list`
재필터, `publish_workout` 거부, `server/discover` → −32601. 이미 검증된 게이트웨이가 Spring에
닿는 유일한 경로이므로, 두 벌을 두면 조용히 어긋날 뿐이다. 게이트웨이를 단일 권한으로 둔다.

---

## 구현 산출물 (신규 `tools/mcp-worker/`, 기존 코드 변경 0)

| 파일 | 내용 |
|---|---|
| `src/internal-jwt.js` | 홉 토큰 발급 + JWKS/AS 메타데이터 생성. 의존성 0, WebCrypto만 사용(workerd/Node 공용), 키 import는 isolate 캐시(무료 플랜 10ms CPU 대응) |
| `src/mcp-proxy.js` | Worker의 resource 레그: scope·사용자·메서드·경로·64 KiB 본문 검사 후 홉 토큰을 붙여 고정 origin으로만 전달. 토큰·헤더·본문 무기록 |
| `src/index.js` | `OAuthProvider` 배선(DCR+CIMD, PKCE S256, scope, resourceMetadata) + GitHub 상위 로그인 + 사용자 1명 대조 + `/internal/` 발급자 엔드포인트. 설정 누락은 **전 요청 500**(fail closed) |
| `scripts/generate-signing-key.mjs` | RSA-2048 키 생성, 개인 JWK는 stdout(= `wrangler secret put` 입력), 공개 JWK/kid는 stderr |
| `test/chain-test.js` | **실제 게이트웨이 프로세스**를 띄워 체인 전체를 검증 |
| `wrangler.jsonc` | 자리표시자만. **비밀값 없음**. `global_fetch_strictly_public`(CIMD 필수) 포함 |
| `README.md` | 배포·운영·중단 런북 |

`.gitignore`에 `.dev.vars`, `.wrangler/` 추가(로컬 개발용 키·상태가 커밋되지 않도록).

### 테스트 결과

```
tools/mcp-worker  npm test   28/28 PASS   (Cloudflare 계정·네트워크 불필요)
tools/mcp-gateway npm test   29/29 PASS   (무변경 재확인)
```

체인 테스트가 증명하는 것 — 지시서 STEP 3의 **필수 실패 테스트 전 항목이 새 인증 체계에서도
그대로 거부**되고, 가짜 MCP 백엔드의 히트 카운터가 내내 움직이지 않는다는 사실이다.

| 필수 실패 테스트 | 결과 |
|---|---|
| 무인증 요청 | 401 + `WWW-Authenticate … resource_metadata=` (OAuth 트리거) |
| 잘못된 토큰 / 변조 토큰 | 401 |
| 만료 토큰 | 401 |
| 잘못된 issuer / audience | 401 |
| JWKS에 없는 키로 서명 | 401 |
| scope 부족 | 403 `insufficient_scope` |
| 허용되지 않은 사용자 | 403 (Worker에서 1차, 게이트웨이에서 2차) |
| `publish_workout` (유효 토큰으로 강제) | JSON-RPC **−32602**, 백엔드 무접촉 |
| `/api/v1/*`, `/actuator/*` | 404 (두 홉 모두 라우트 자체가 없음) |
| `server/discover` | HTTP 200 + JSON-RPC **−32601** (5xx 아님) |

추가로 Worker 핸들러 자체 검사(GET→405, 타 경로→404, scope 부족→403, 타 사용자→403, props
불일치→403, 64 KiB 초과→413, origin 불가→502)와 **전체 체인 통과**(Worker 핸들러 → 실제
게이트웨이 → 백엔드 응답)도 PASS.

### 로컬 실기동 확인 (`wrangler dev --local`, 계정 없이)

- `/health` 200, AS 메타데이터·보호 리소스 메타데이터 정상
- DCR 등록 성공 → `/authorize`(PKCE S256 + `resource`) → **GitHub 로그인으로 302**
- 토큰 없는 `POST /mcp` → **401 + `resource_metadata`**, 가짜 토큰 → 401
- 확인 후 workerd 프로세스 종료, `.dev.vars`(개발용 임시 키)·`.wrangler/` 상태 **삭제 완료**,
  8787 포트 닫힘 확인

배포 전에 잡아낸 두 가지 실제 오류: ① `resourceMetadata.scopes_supported`와 `requiredScopes`
동시 지정 시 라이브러리가 기동 거부 ② `global_fetch_strictly_public` 플래그 없으면 CIMD 비활성.
둘 다 수정 후 재확인했다.

---

## 보호 대상 — 전부 무변경

`C:\running-ai` 레거시 · `C:\running-ai-watchface` · 운영 Spring 18080 · 운영 PostgreSQL(V20,
항목 G 미적용 상태 유지) · Relay 17845 · Funnel 443 · Watchdog · Canonical `main` · `origin`
— **이번 작업에서 접근·변경 0건.** 외부 노출 0건(모든 테스트는 127.0.0.1). 실데이터 접근 0건
(사본 DB 생성도 하지 않았다 — STEP 4 사항). Gradle 서버 코드 무변경이므로 회귀는
1,539/1,539가 그대로 유효하며 재실행하지 않았다.

---

## STOP — 사용자 조작이 필요한 지점

아래 1~5는 전부 **사용자 계정 조작**이다. 승인 후 진행한다. 상세 절차는
`tools/mcp-worker/README.md`.

1. **Cloudflare 무료 계정 생성 / 로그인** (`npx wrangler login`) — 카드 등록 불필요
2. **GitHub OAuth 앱 등록** (Settings → Developer settings → OAuth Apps)
   - callback: `https://running-ai-mcp.<subdomain>.workers.dev/callback`
3. **KV 네임스페이스 생성** (`npx wrangler kv namespace create OAUTH_KV`) → id를 `wrangler.jsonc`에
4. **서명키 생성 + 시크릿 3개 등록** (`npm run keygen`, `wrangler secret put` ×3)
5. **Worker 배포** (`npx wrangler deploy`) → 고정 주소 확보

그다음 제가 수행할 작업(승인 범위 내):

- STEP 3 — 합성 데이터 OAuth E2E: 로컬 Spring(합성) → 게이트웨이(새 env) → Quick Tunnel →
  Worker → ChatGPT 커넥터 등록 → 브라우저 GitHub 로그인 → 도구 7종 호출 + 위 실패 테스트를
  **외부 경로에서** 재실측
- STEP 4 — STEP 3 PASS 이후에만: 운영 DB 사본(`running_ai_7d2`) 생성, 사본에만 V21~V23,
  Spring 18081, 쓰기 전부 off, 현재 무릎 상태 그대로 **ACTIVE** 유지(RESOLVED로 바꾸지 않음)
- STEP 5 — 사용자가 ChatGPT에서 5개 질문 수행, 7A-1/7B-3 문서값과 대조
- STEP 6 — 재기동·로그·종료 안내 정리

**인증이 확립되기 전에는 실데이터에 연결하지 않는다**(STEP 3 PASS가 STEP 4의 전제).

## 한계 / 미완료

- ChatGPT 실제 OAuth 로그인 E2E: **NOT_RUN** (Worker 배포 선행)
- GitHub 코드 교환 레그: 실제 OAuth 앱이 없어 로컬 검증 불가 — 배포 후 첫 로그인이 실측 지점
- Quick Tunnel 재시작 시 `ORIGIN_MCP_URL` 수동 갱신 + 재배포 1회 필요(자동 등록은 미구현;
  Worker에 admin 엔드포인트를 추가해야 하므로 공격면 증가를 이유로 보류)
- 자동 시작·상시 외부 노출: 미구현, 별도 승인 사항
- 운영 항목 G 및 배포 A~F: 이번 Phase와 무관하게 **승인 대기 상태 그대로**
