# Phase 7D-GPT-LIVE-2 — 배포 전 최종 검토 결과 (4개 항목 PASS, 결함 2건 수정, 배포 대기)

지시: 실제 Cloudflare 배포 전 4개 항목 점검 → 설정 순서 안내 → STOP.
기준: `main-3` HEAD `34cf121` + 미커밋 7D-2 산출물. **외부 배포 0건, 실데이터 0건, 운영 변경 0건.**

검토는 주장이 아니라 **실측**으로 했다. 새 테스트 `tools/mcp-worker/test/oauth-e2e-test.js`가
`wrangler dev`(로컬 workerd + 로컬 KV, 계정 불필요) 위에서 **OAuth 전 구간**을 실행한다 —
ChatGPT 형태의 클라이언트 → DCR → PKCE S256 authorize → (스텁 GitHub 로그인) → callback →
토큰 교환 → `POST /mcp` → 홉 토큰 발급 → **실제 게이트웨이 프로세스** → 합성 MCP 백엔드.

```
tools/mcp-worker   npm test        28/28 PASS   (홉 토큰 ↔ 무변경 게이트웨이)
tools/mcp-worker   npm run test:oauth  43/43 PASS   (OAuth 전 구간 + 7종 긍정 테스트)
tools/mcp-gateway  npm test        29/29 PASS   (무변경 재확인)
                                   ───────────
                                   합계 100/100
```

---

## 항목 1 — 홉 토큰 클레임 / JWKS / 개인키 보관

**발급 경로는 단 하나.** `mintInternalToken`을 호출하는 곳은 `mcp-proxy.js:105`의
`mintHopToken` 뿐이고, 그 앞에 scope·사용자 검사가 전부 끝난 뒤에만 도달한다(테스트 파일 외
호출처 0건, grep 확인).

| 클레임 | 값의 출처 | 확인 |
|---|---|---|
| `sub` | `auth.userId` = `github|<숫자 id>` — 로그인 시 GitHub가 반환한 **숫자 id**로 `completeAuthorization`에 기록된 값. 상수도, 클라이언트 입력도 아니다 | 코드 + 게이트웨이 `sub` 대조 통과 |
| 허용 대상 | `auth.userId === ALLOWED_GITHUB_ID` **그리고** `github|props.githubId === ALLOWED_GITHUB_ID` (독립 2회) | 타 사용자 → 403, 로그인 단계에서도 403 |
| `scope` | `runningai:read` **고정** — 사용자 토큰이 `offline_access`를 더 갖고 있어도 홉 토큰에는 검증된 읽기 scope만 담는다(최소권한) | 게이트웨이 scope 검사 통과 |
| `aud` | `${PUBLIC_BASE_URL}/mcp` | 불일치 시 게이트웨이 401(테스트) |
| `iss` | `${PUBLIC_BASE_URL}/internal/` | 불일치 시 게이트웨이 401(테스트) |
| 수명 | `iat`/`nbf` 즉시, `exp` = +90초(최대 600 제한) | 만료 토큰 → 401(테스트) |

**JWKS 공개 필드 검증(실측).** `/internal/jwks.json` 응답 키의 필드 집합이 정확히
`alg,e,kid,kty,n,use` 였다 — `d,p,q,dp,dq,qi,oth,k` 전부 부재. `publicJwkOf()`에 **개인키가 든
JWK를 통째로 넣어도** 같은 결과임을 별도 검증했다(화이트리스트 방식이라 구조적으로 샐 수 없다).

**개인키 보관.** `HOP_SIGNING_JWK`는 `wrangler secret`에만 둔다. `wrangler.jsonc`의 `vars`에는
없고(주석으로만 이름 표기), 로컬 개발용 `.dev.vars`는 `.gitignore`에 추가했으며 테스트는
매 실행 키를 새로 만들고 끝나면 삭제한다. 커밋 대상 파일 전수 스캔 결과 개인키 흔적 0건.

## 항목 2 — `iss` 파라미터 / CIMD·DCR / PKCE S256

지시대로 **메타데이터 선언과 실제 콜백을 대조**했다.

| 확인 | 메타데이터 선언 | 실제 측정 |
|---|---|---|
| 성공 콜백 `iss` | `authorization_response_iss_parameter_supported: true` | 302 `…/connector_platform_oauth_redirect?code=…&state=state-success&**iss=http://localhost:8788**` ✅ |
| 실패 콜백 `iss` | 동일 | `response_type=token`(미지원) → 302 + `error=…` + **`iss`** ✅ (아래 결함 1 수정 후) |
| PKCE | `code_challenge_methods_supported:["S256"]` | S256 challenge로 인가 → **verifier 불일치 시 토큰 교환 거부**, 일치 시 발급 ✅ |
| DCR | `registration_endpoint:/register` | ChatGPT 고정 콜백 + `token_endpoint_auth_method:none` 공개 클라이언트 등록 201 ✅ |
| CIMD | `client_id_metadata_document_supported` | **컴파일 플래그에 종속** — 테스트 설정(플래그 생략)에서는 `false`, **배포용 `wrangler.jsonc`에는 `global_fetch_strictly_public`이 들어 있음**을 테스트가 파일로 검증 ✅ |
| 토큰 수명 | — | `expires_in: 3600`, refresh 토큰 발급, **refresh grant로 재발급 성공** ✅ |

라이브러리 동작도 번들에서 직접 확인했다: `iss`는 `request.issuer`가 있을 때 성공
(`dist:5638`)·실패(`dist:23`) 리다이렉트 양쪽에 붙고, `request.issuer`는 `parseAuthRequest`가
항상 채운다(`dist:5524`). 즉 **항상 반환**된다.

> **배포 시 주의(측정에서 드러난 사실):** 라이브러리는 issuer를 **요청 URL의 origin**에서,
> `resource`는 `PUBLIC_BASE_URL`에서 만든다. 두 값이 어긋나면 audience 결합이 깨진다.
> `PUBLIC_BASE_URL`은 ChatGPT가 쓸 `*.workers.dev` 주소와 **글자 그대로 같아야** 한다.

## 항목 3 — 홉 토큰의 사용 범위 / 우회 차단 / 로그

**사전 인증된 MCP 요청에만 사용:** 위 1번의 단일 호출 경로 + 403/405/413/404가 전부 발급
**이전에** 끝난다(테스트에서 origin 호출 횟수 0 확인).

**게이트웨이 직접 접근(= Cloudflare 우회) 차단 — 전부 실측:**

| 시도 | 결과 |
|---|---|
| 토큰 없이 터널/게이트웨이 직격 | **401** + `WWW-Authenticate … resource_metadata=` |
| **Worker가 발급한 진짜 OAuth 액세스 토큰**을 게이트웨이에 그대로 제시 | **401** — 불투명 문자열이라 JWT 검증을 통과할 수 없다 |
| `alg:none` 위조 JWT | **401** |
| 위 전부에 대해 백엔드 도달 | **0건** |

액세스 토큰이 불투명(`userId:grantId:secret`, 점 2개짜리 JWT가 아님)이라는 것도 테스트가
단언한다 — 이 설계의 전제 자체를 회귀로 고정했다.

**로그 유출 검사(실측).** 전 구간 실행 후 Worker 로그와 게이트웨이 로그를 문자열 검색:

- OAuth 액세스 토큰 — 없음
- GitHub Client Secret — 없음 (스텁 GitHub가 **실제로 secret을 수신했음**을 먼저 확인해
  검사가 공허하지 않음을 보장)
- 개인키 `d` 값 — 없음
- 게이트웨이 로그의 `Bearer ey…` 형태 — 없음

추가 보강: Worker의 `log()`에 게이트웨이와 같은 레닥션 정규식을 넣었다(우리가 쓰지 않은
의존성 예외 메시지에 대한 심층 방어).

## 항목 4 — 정상 인증 사용자의 합성 데이터 7종 긍정 테스트

정식 OAuth 액세스 토큰으로 Worker → 게이트웨이 → 합성 백엔드까지 통과한 결과:

- `initialize` 성공
- `tools/list` → **정확히 7종**
- `tools/call` × 7 — `get_recent_activities`, `get_activity_analysis`, `get_training_context`,
  `get_recovery_context`, `get_fitness_trend`, `get_workout_recommendation`,
  `get_athlete_profile` **전부 합성 페이로드 일치** ✅
- 같은 토큰으로 `publish_workout` → **−32602**, `server/discover` → **200/−32601**,
  `GET /mcp` → 토큰 없으면 401 / 있으면 405, `/api/v1/*`·`/actuator/*` → 404

백엔드는 **합성 스텁**이며 수치는 전부 가공값(2020-01-05 등)이다. 실제 Spring·실데이터
연결은 STEP 3·4 사항이므로 **실제 ChatGPT E2E는 PASS로 판정하지 않는다.**

---

## 검토 중 발견해 수정한 결함 2건

1. **OAuth 오류가 표준 오류 리다이렉트로 돌아가지 않았다.** `/authorize`의 `catch`가
   `AuthorizationError`까지 삼키고 400 JSON을 반환해, 클라이언트는 `state`도 `iss`도 없는
   불투명한 실패만 받았다. → `AuthorizationError.redirectTo`가 있으면 302로 되돌리고, 없을
   때만(리다이렉트 URI가 검증되지 않은 경우) 로컬 렌더링하도록 수정. 임의 주소로는 절대
   리다이렉트하지 않는다.
2. **CIMD가 조용히 꺼져 있었다.** `global_fetch_strictly_public` 플래그가 없으면 라이브러리가
   CIMD를 비활성화하고 경고만 남긴다. → 배포용 `wrangler.jsonc`에 플래그 추가 + 테스트가
   배포 설정 파일에서 플래그 존재를 검증.

(1차 작업에서 이미 수정한 `resourceMetadata.scopes_supported` 기동 거부 건 포함, 배포 전에
잡은 실제 오류는 총 3건이다.)

테스트를 위해 추가한 유일한 제품 코드 변경은 `GITHUB_OAUTH_BASE` / `GITHUB_API_BASE`
**오버라이드**다. 운영에서는 **설정하지 않으며**(기본값이 진짜 GitHub), 설정하더라도 https
또는 루프백 http만 허용하고 그 외는 기동 거부한다 — 로그인 채널이 조용히 평문으로
내려가는 경로를 만들지 않기 위해서다.

---

## 정확한 설정값 이름

### Cloudflare Worker — `vars` (`wrangler.jsonc`, 비밀 아님)

| 이름 | 값 |
|---|---|
| `PUBLIC_BASE_URL` | `https://running-ai-mcp.<subdomain>.workers.dev` (끝 슬래시 없음, ChatGPT가 쓸 주소와 동일) |
| `ORIGIN_MCP_URL` | `https://<random>.trycloudflare.com/mcp` (터널 재시작 시 **이 값만** 갱신) |
| `ALLOWED_GITHUB_ID` | 본인 GitHub **숫자** id |
| `ALLOWED_GITHUB_LOGIN` | 본인 GitHub login |
| `HOP_TOKEN_TTL_SECONDS` | `90` |
| `GITHUB_OAUTH_BASE` / `GITHUB_API_BASE` | **설정하지 않음** (테스트 전용) |

### Cloudflare Worker — `secrets` (`wrangler secret put`, 절대 vars 아님)

| 이름 | 값 |
|---|---|
| `HOP_SIGNING_JWK` | `npm run keygen`의 stdout 한 줄(개인 JWK) |
| `GITHUB_CLIENT_ID` | GitHub OAuth App Client ID |
| `GITHUB_CLIENT_SECRET` | GitHub OAuth App Client Secret |

### KV 바인딩

| 이름 | 값 |
|---|---|
| `OAUTH_KV` | `wrangler kv namespace create OAUTH_KV`가 출력한 id |

### 로컬 게이트웨이 — 환경변수 (`tools/mcp-gateway`, **코드 무변경**)

| 이름 | 값 |
|---|---|
| `RUNNING_AI_GATEWAY_TARGET_URL` | `http://127.0.0.1:18081/mcp` |
| `RUNNING_AI_OAUTH_ISSUER` | `https://running-ai-mcp.<sub>.workers.dev/internal/` ← **끝 슬래시 필수** |
| `RUNNING_AI_OAUTH_AUDIENCE` | `https://running-ai-mcp.<sub>.workers.dev/mcp` |
| `RUNNING_AI_ALLOWED_SUB` | `github|<숫자 id>` |
| `RUNNING_AI_PUBLIC_BASE_URL` | `https://running-ai-mcp.<sub>.workers.dev` |
| `RUNNING_AI_GATEWAY_PORT` | `17910` (기본값) |
| `RUNNING_AI_ALLOWED_TOOLS` | 설정 불필요 (기본값 = 읽기 7종) |

### GitHub OAuth App

| 항목 | 값 |
|---|---|
| Application name | `RunningAI MCP` |
| Homepage URL | `https://running-ai-mcp.<sub>.workers.dev` |
| **Authorization callback URL** | `https://running-ai-mcp.<sub>.workers.dev/callback` |
| 요구 scope | `read:user` (신원 확인만, Worker가 지정) |

### ChatGPT 커넥터

| 항목 | 값 |
|---|---|
| Server URL | `https://running-ai-mcp.<sub>.workers.dev/mcp` |
| 인증 | OAuth (ChatGPT가 메타데이터로 자동 발견) |
| ChatGPT 콜백 | `https://chatgpt.com/connector_platform_oauth_redirect` (고정, 별도 등록 불필요) |

---

## 배포 순서 (사용자 조작 — 승인 전 진행하지 않음)

1. **Cloudflare 계정 + workers.dev 주소 확정** — `npx wrangler login` → 1차
   `npx wrangler deploy`로 실제 주소 확인 → `PUBLIC_BASE_URL`에 반영
2. **GitHub OAuth App callback 등록** — 위 표의 callback URL 그대로
3. **KV + Secrets 설정** — `wrangler kv namespace create OAUTH_KV`, `wrangler secret put` ×3
4. **Worker 배포** — `npx wrangler deploy`
5. **합성 데이터 ChatGPT OAuth E2E** — 로컬 합성 Spring → 게이트웨이(위 env) → Quick Tunnel →
   `ORIGIN_MCP_URL` 갱신 → ChatGPT 커넥터 등록 → **실제 브라우저 GitHub 로그인** → 7종 호출 +
   실패 테스트를 외부 경로에서 재실측

5번까지 PASS한 뒤에야 STEP 4(운영 DB 사본, 무릎 상태 ACTIVE 유지)로 넘어간다.

## 현재 상태

- 운영 Spring 18080 · 운영 PostgreSQL(V20) · Relay 17845 · Funnel 443 · Watchdog ·
  Canonical `main` · `origin` — **접근·변경 0건**
- 외부 노출 0건(모든 검증은 127.0.0.1) · 실데이터 0건 · 사본 DB 미생성
- 실제 ChatGPT E2E: **NOT_RUN** (배포 승인 대기)
- 서버 코드 무변경이므로 Gradle 회귀 1,539/1,539 그대로 유효(재실행 없음)
