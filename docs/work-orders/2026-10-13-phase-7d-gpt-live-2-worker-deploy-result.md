# Phase 7D-GPT-LIVE-2 — Cloudflare Worker 신규 배포 결과 (PASS, Secret 등록 대기)

지시: 신규 Worker `running-ai-mcp` 생성·배포 승인. 선행 보고 `...-predeploy-config-result.md`.
기준: `main-3` HEAD `34cf121`, 미커밋 산출물 보존. **커밋·푸시 0건, 운영 변경 0건, 실데이터 0건.**

## 결과

| 항목 | 값 |
|---|---|
| 배포 | **PASS** |
| Worker URL | `https://running-ai-mcp.simjjy89.workers.dev` |
| Version ID | `da02a1d8-7db2-4589-be5e-cb744c5df892` |
| 배포 시각 | 2026-10-13T01:41:16.328Z (Source: Upload, 이 Worker의 유일한 배포) |
| 업로드 크기 | 251.19 KiB / gzip 56.49 KiB, Startup 2 ms |
| Origin | `https://origin-not-configured.invalid/mcp` — **그대로** |
| 기존 Relay 영향 | **없음** |

배포 전 `running-ai-mcp`는 계정에 존재하지 않았고(code 10007), `wrangler.jsonc` 6개 항목
(name / PUBLIC_BASE_URL / ORIGIN_MCP_URL / OAUTH_KV / ALLOWED_GITHUB_ID / ALLOWED_GITHUB_LOGIN)을
배포 직전 재확인했다. 출력된 주소가 `PUBLIC_BASE_URL`과 글자 단위로 일치 → issuer/audience 결합 정상.

## 배포 후 실측 (공개 주소에 실제 HTTP 요청)

| 확인 | 결과 |
|---|---|
| `GET /health` | **200** `{"status":"ok","service":"running-ai-mcp-worker"}` |
| `GET /.well-known/oauth-authorization-server` | issuer = `https://running-ai-mcp.simjjy89.workers.dev`, `code_challenge_methods_supported:["S256"]`, `registration_endpoint:/register`(DCR), **`client_id_metadata_document_supported: true`(CIMD 활성)**, `authorization_response_iss_parameter_supported: true`, `scopes_supported:["runningai:read","offline_access"]` |
| `GET /.well-known/oauth-protected-resource/mcp` | `resource=…/mcp`, `authorization_servers=[…]`, `scopes_supported:["runningai:read"]`, `bearer_methods_supported:["header"]` |
| `POST /mcp` (토큰 없음) | **401** + `WWW-Authenticate: Bearer realm="OAuth", resource_metadata="…/.well-known/oauth-protected-resource/mcp", scope="runningai:read"` → ChatGPT OAuth 트리거 정상 |

로컬 테스트(43/43)에서 예측한 메타데이터가 **실제 배포본에서 그대로 관측**됐다. CIMD가
배포본에서 켜져 있는 것까지 확인했다(`global_fetch_strictly_public` 플래그 실효 확인).

## 실데이터 접근 경로 — 연결되지 않음 (4중으로 확인)

| 확인 | 결과 |
|---|---|
| `ORIGIN_MCP_URL` | `https://origin-not-configured.invalid/mcp` — `nslookup` **Non-existent domain** (RFC 2606 예약 TLD, 영구 NXDOMAIN) |
| 홉 토큰 발급 가능 여부 | **불가.** `npx wrangler secret list` = `[]` → `HOP_SIGNING_JWK` 없음 → `GET /internal/jwks.json` **500 `server_misconfigured`** (fail closed) |
| cloudflared 터널 | **미기동** (프로세스 없음) |
| 로컬 origin | 게이트웨이 17910 **미기동**, dev Spring 18081 **미기동** |

즉 현재 Worker는 인증을 걸 수도, 어디로 전달할 수도 없는 상태다. 운영 Spring 18080은
LISTENING 상태 그대로이며 이 Worker와 **어떤 경로로도 연결되어 있지 않다**(origin이 다른 값).

> 참고: Secret 미등록 상태에서는 `/api/v1/*`·`/actuator/*`도 404가 아니라 **500**으로 응답한다.
> 설정 해석이 라우팅보다 먼저 fail closed 되기 때문이며, Secret 등록 후에는 테스트에서 검증한
> 대로 **404**가 된다(두 홉 모두 해당 라우트가 존재하지 않음). 어느 쪽이든 데이터는 나가지 않는다.

## 기존 Watchface Relay — 영향 없음

| 시점 | `runningai-watchface-relay` 최신 배포 |
|---|---|
| 배포 전 | 2026-10-09T05:32:02.789Z / version `da2cf611-2b20-489e-9d92-cf10951d1fd6` |
| 배포 후 | **동일** |

별개 스크립트 이름으로 신규 생성했으므로 구조적으로 영향이 없고, 타임스탬프·버전 id로도 확인했다.
KV `OAUTH_KV`(`fb4f6f1c669d44a8a6d05ec5c18233b9`)는 계정의 유일한 KV이며 릴레이는 KV를 쓰지 않는다.
요금제 변경 없음(Workers free + KV free + `workers.dev`).

## 다음 단계에 필요한 Secret 3건 (사용자 터미널 전용)

| 이름 | 출처 |
|---|---|
| `HOP_SIGNING_JWK` | `node scripts/generate-signing-key.mjs`의 stdout 1줄 (개인 RSA JWK) |
| `GITHUB_CLIENT_ID` | GitHub OAuth App |
| `GITHUB_CLIENT_SECRET` | GitHub OAuth App |

등록 전까지 Worker는 전 요청 500으로 fail closed 유지. 절차는 보고서 본문 및
`tools/mcp-worker/README.md` 5단계 참조 — 임시 키 파일은 `try/finally`로 **실패 시에도** 0으로
덮어쓴 뒤 삭제한다.

## 보호 대상 — 전부 무변경

운영 Spring 18080 · 운영 PostgreSQL(V20, 항목 G 미적용) · Relay 17845 · Funnel 443 · Watchdog ·
`C:\running-ai` · `C:\running-ai-watchface` · Canonical `main` · `origin` · `runningai-watchface-relay`
— **접근/변경 0건.** Git 커밋·푸시 0건, 미커밋 산출물 그대로 보존.

---

## 추가 — `HOP_SIGNING_JWK` 등록 (2026-10-13 02:35Z, 자동 실행)

개인키가 **디스크에도 로그에도 남지 않도록** 임시 파일 없이 파이프로 직접 등록했다:
`node scripts/generate-signing-key.mjs | wrangler secret put HOP_SIGNING_JWK` — stdout(개인 JWK)은
wrangler stdin으로만 흐르고, stderr(kid + 공개 JWK)만 따로 받았다.

- 결과: `✨ Success! Uploaded secret HOP_SIGNING_JWK`, 새 버전 `1b14ee29-3add-46a6-9c90-896dd9cca1ff`
- `kid = d914b4c25d6b242f` (비밀 아님), 공개 JWK 필드 집합이 정확히 `alg,e,kid,kty,n,use`임을
  기계적으로 검증 — `d,p,q,dp,dq,qi,oth,k` 전부 부재. 검증 실패 시 보관하지 않도록 먼저 확인한 뒤
  스크래치패드에만 공개 JWK를 남기고, 전이 파일(keyinfo/put.log)은 0으로 덮어쓴 뒤 삭제했다.
- `wrangler secret list` → `[{"name":"HOP_SIGNING_JWK","type":"secret_text"}]` (값은 조회 불가)
- `/internal/jwks.json`은 **여전히 500 `server_misconfigured`** — GitHub 시크릿 2건이 없어 설정
  해석이 fail closed 되기 때문이며, 이것이 다음 단계의 검증 게이트다. `/health` 200,
  토큰 없는 `POST /mcp` 401 유지.
- `runningai-watchface-relay` 2026-10-09T05:32:02.789Z / `da2cf611` **불변**. 운영 Spring 18080
  (127.0.0.1, PID 29984) 그대로, 게이트웨이 17910·dev Spring 18081·cloudflared **미기동**.

남은 Secret 2건(`GITHUB_CLIENT_ID`, `GITHUB_CLIENT_SECRET`)은 사용자가 Cloudflare Dashboard에서
직접 등록한다 — 값이 이 세션을 거치지 않게 하기 위함.
