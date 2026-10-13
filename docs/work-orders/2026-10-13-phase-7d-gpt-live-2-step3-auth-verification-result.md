# Phase 7D-GPT-LIVE-2 STEP 3 — GitHub OAuth 인증 검증 + 합성 데이터 E2E 환경 (PASS, ChatGPT 실호출 전 STOP)

지시: 사용자가 Cloudflare Dashboard에서 `GITHUB_CLIENT_ID`/`GITHUB_CLIENT_SECRET` 등록 완료 보고 →
STEP 3 재개(인증 설정 검증 → 합성 데이터 E2E 환경 준비 → ChatGPT 연결 정보 보고 후 STOP).

기준: `main-3` HEAD `34cf121`, 미커밋 산출물 보존. **커밋·푸시 0건, 운영 변경 0건, 실데이터 0건.**
선행 보고: `...-gpt-live-2-worker-deploy-result.md`.

---

## 1. 인증 설정 검증 — 전 항목 PASS

Secret 값은 조회하지도 출력하지도 않았다(이름/존재 여부만).

| # | 확인 | 결과 |
|---|---|---|
| 1 | `wrangler secret list` | **3건 전부 존재**: `GITHUB_CLIENT_ID`, `GITHUB_CLIENT_SECRET`, `HOP_SIGNING_JWK` (type `secret_text`, 값 조회 불가) |
| 2 | `GET /internal/jwks.json` | **200** — 이전의 500 `server_misconfigured`에서 전환. 설정 해석이 통과했다는 뜻이며, 이것이 STEP 3의 게이트였다 |
| 3 | JWKS `kid` | **`d914b4c25d6b242f`** — 10-13 02:35Z 등록분과 일치 (키 교체 없음) |
| 4 | 공개 JWK에 개인키 필드 | **없음.** 필드 집합이 정확히 `["alg","e","kid","kty","n","use"]`; 문서 전체를 재귀 스캔해 `d,p,q,dp,dq,qi,oth,k` **0건**. `kty=RSA, alg=RS256, use=sig`, 키 1개 |
| 5 | OAuth AS 메타데이터 | `issuer` = `https://running-ai-mcp.simjjy89.workers.dev`, `code_challenge_methods_supported:["S256"]`, `registration_endpoint:/register`(DCR), **`client_id_metadata_document_supported:true`**(CIMD), `authorization_response_iss_parameter_supported:true`, `grant_types:[authorization_code, refresh_token]`, `scopes:[runningai:read, offline_access]` |
| 6 | Protected Resource 메타데이터 | `resource=…/mcp`, `authorization_servers=[…]`, `scopes_supported:["runningai:read"]`, `bearer_methods_supported:["header"]` |
| 7 | 무인증 `POST /mcp` | **401** + `WWW-Authenticate: Bearer realm="OAuth", resource_metadata="…/.well-known/oauth-protected-resource/mcp", scope="runningai:read"` → ChatGPT OAuth 트리거 정상 |
| 8 | 잘못된 토큰 3종 | 전부 **401**: 임의 문자열 / 라이브러리 형식(`user:grant:secret`)을 흉내 낸 가짜 / 홉 토큰 모양의 JWT |
| 9 | 허용되지 않은 경로 | `/api/v1/*`, `/actuator/*`, `/`, `/mcp/../api/v1/activities`, `/internal/`, 임의 경로 → 전부 **404**. `POST`로도 404(일반 REST 프록시로 쓸 수 없음) |
| 10 | `/mcp`의 다른 메서드 | `GET/PUT/DELETE/PATCH` → **401**(인증을 라우팅보다 먼저 검사) |
| 11 | 기존 `runningai-watchface-relay` | **불변** — 최신 배포 `2026-10-09T05:32:02.789Z` / `da2cf611-2b20-489e-9d92-cf10951d1fd6`, STEP 3 전후 동일 |

> 9번은 Secret 등록으로 달라진 지점이다. 등록 전에는 설정 해석이 라우팅보다 먼저 fail closed 되어
> 500이었고, 지금은 예고한 대로 **404**다(두 홉 모두 그런 라우트가 없음). 어느 쪽이든 데이터는 나가지 않는다.

### 오프라인 회귀 (배포본과 같은 소스, 계정·네트워크 불필요)

`npm run test:all` → **28/28 + 43/43 전부 PASS**. 포함 항목: 실제 게이트웨이 프로세스를 상대로 한
홉 토큰 검증, PKCE S256, DCR, 성공·실패 콜백의 `iss`, refresh, 읽기 7종, **다른 GitHub 계정의 사인인
거부**, 게이트웨이 직접 우회 시도, 로그에 토큰/클라이언트 시크릿/개인키가 남지 않는지 스캔.

`src/*.js` 수정 시각(10-13 09:40~10:06)이 최초 배포(10:41 KST)보다 앞서므로 배포본 = 현재 소스다.

---

## 2. 합성 데이터 E2E 환경 — 기동 완료

Phase 7C-2 구성을 재사용하되 포트는 README의 7D 배치(18081 / 17910)를 따랐다.

| 구성요소 | 상태 |
|---|---|
| 합성 Spring | **127.0.0.1:18081** `UP` (PID 13980). `Registered tools: 7`. `SERVER_ADDRESS=127.0.0.1`로 루프백 바인드 |
| 합성 DB | 기존 `running-ai-postgres` 컨테이너 안 **신규 DB `running_ai_7d_smoke`** — Flyway **V1~V23 신규 적용**(23 migrations). 운영 `running_ai`는 20행 그대로 |
| MCP Gateway | **127.0.0.1:17910**, 기존 `tools/mcp-gateway/server.js` **코드 수정 0**. 도구 allowlist = 읽기 7종, target = 루프백 18081 |
| Quick Tunnel | `https://vault-courtesy-receivers-approximate.trycloudflare.com` → 17910 (QUIC, icn05) |
| Worker | `https://running-ai-mcp.simjjy89.workers.dev` — `ORIGIN_MCP_URL`만 변경 후 재배포, Version `50d3ca08-b437-495d-bb0d-abbe93a16c12` |

### 쓰기 차단

합성 Spring은 **모든 쓰기 스위치 off**로 기동했다: `RUNNINGAI_MCP_ENABLED=false`(→ `publish_workout`
미등록), `WORKOUT_PUBLISHING_ENABLED=false`, `WORKOUT_PUBLISHING_SCHEDULER_ENABLED=false`,
`RUNNING_AI_DRAFT_PUBLISHING_ENABLED=false`, Garmin 두 스케줄러 false. 외부 시스템은
`GARMIN_CONNECTOR_URL=http://127.0.0.1:9`(죽은 포트), `INTERVALS_API_KEY` 빈 값, `INTERVALS_ATHLETE_ID=0`
으로 고정 — **실제 Garmin/Intervals에 도달할 경로가 없다.** DB 비밀번호는 기동 시 컨테이너에서
읽어오므로 스크립트 파일에 남지 않는다.

### 합성 데이터 (실값과 전부 다름)

- 프로필 **LTHR 165 bpm / 임계 페이스 310 s/km** (실제 176 / 290 과 다름)
- 활동 6건 `MANUAL` + `SMOKE-001~006` (2026-09-20 ~ 10-12, RUN 5 + TREADMILL_RUN 1)
- `activity_analysis` 2건 — 10-12 세션지표 + 7B-3 러닝 다이내믹스 전체, 10-08 인터벌 그룹 1·work rep 5
- `garmin_recovery_daily` 29일(기준선 AVAILABLE), `intervals_fitness_daily` 29일,
  `activity_intervals_metrics` 2건
- `injury_report` 2건 — 둘 다 `RESOLVED`, notes에 "synthetic smoke-test seed … not a real injury record" 명기

### 읽기 도구 7종 실호출 (합성 Spring 직접)

| 도구 | 결과 |
|---|---|
| `get_recent_activities` | `totalInWindow=6`, 합성 6건 |
| `get_activity_analysis` (10-12) | 세션지표 + 다이내믹스, `RUNNING_ANALYSIS_V1` / `COMPLETE` |
| `get_activity_analysis` (10-08) | 인터벌 그룹 + 5 rep |
| `get_training_context` | V2, 6,510 B, sha256 포함 |
| `get_recovery_context` | HRV 기준선 `sampleCount=28` |
| `get_fitness_trend` | `daysStored=29`, CTL/ATL/derivedForm, 용어 문구 그대로 |
| `get_workout_recommendation` | `INJURY_RESOLVED_CONFIRMED` → `EASY` |
| `get_athlete_profile` | LTHR 165 / 310, provenance `MANUAL` |

`tools/list` = **정확히 7종**, `publish_workout` 미등록.

부상 안전 게이트는 **두 상태 모두 실측**했다. 20일 전 RESOLVED 기록만 있을 때는
`nonRestTrainingAllowed=false` / `INJURY_STATE_STALE`(48h 신선도 창 초과)로 **차단**됐고, 최신 RESOLVED
체크인을 넣은 뒤에야 `INJURY_RESOLVED_CONFIRMED`로 통과했다. 게이트가 살아 있다는 직접 증거다.

> `get_fitness_trend`에 `days`를 넘긴 첫 호출은 `INVALID_ARGUMENT: Unknown argument(s): days`로 거부됐다.
> 이 도구의 스키마는 `oldestDate`/`newestDate` **필수**이며, 거부가 정상 동작이다(호출자 실수).

---

## 3. 외부 인증 차단 검증 (터널·게이트웨이·Worker 전 구간)

ORIGIN 연결 **전에** 터널이 이미 fail closed임을 먼저 확인했다.

| 지점 | 시도 | 결과 |
|---|---|---|
| 게이트웨이 17910 | 토큰 없음 | **401** + `WWW-Authenticate … resource_metadata=` |
| 게이트웨이 17910 | 위조 서명 JWT(정확한 kid·sub·scope 포함) | **401** `ERR_JWS_SIGNATURE_VERIFICATION_FAILED` |
| 게이트웨이 17910 | `alg=none` JWT | **401** `ERR_JOSE_ALG_NOT_ALLOWED` |
| 게이트웨이 17910 | `/api/**`, `/actuator/**`, `/`, `/mcp/../api/v1/activities` | 전부 **404** |
| **공개 터널** | 토큰 없는 `POST /mcp` | **401** — Worker를 우회해도 얻는 것이 없다 |
| **공개 터널** | 위조 JWT | **401** |
| **공개 터널** | `GET /api/v1/activities`, `/api/v1/athlete/intensity-profile`, `/actuator/health`, `/actuator/env`, `/` | 전부 **404** |
| **공개 터널** | `POST /api/v1/activities` (쓰기 시도) | **404** — 공개 주소에서 쓰기 API에 닿을 수 없다 |
| Worker (ORIGIN 연결 후) | 토큰 없음 / 가짜 토큰 | **401 / 401** — 오리진이 살아난 뒤에도 그대로 |

게이트웨이 로그 전문(시작 1줄 + 거부 5줄)에 **백엔드로 전달된 호출은 0건**이다. 로그에는
`AS metadata discovered: jwks via /internal/jwks.json` 1줄이 있는데, 게이트웨이가 Worker의 JWKS를
실제로 발견했다는 뜻 — issuer 배선이 맞다는 긍정 증거다. 토큰·헤더·본문은 로깅되지 않는다.

### `ORIGIN_MCP_URL` 변경 — 사전 보고 후 사용자 승인으로 실행

| | 값 |
|---|---|
| 변경 전 | `https://origin-not-configured.invalid/mcp` (RFC 2606 예약, 영구 NXDOMAIN) |
| 변경 후 | `https://vault-courtesy-receivers-approximate.trycloudflare.com/mcp` |

연결 대상은 터널 → 게이트웨이 17910 → **합성 Spring 18081 → `running_ai_7d_smoke`** 뿐이다.
운영 Spring 18080과 운영 DB `running_ai`는 이 경로에 **없다**. `wrangler deploy`로 새 버전
`50d3ca08`을 올렸고 비용 변화·릴레이 영향은 없다. 배포 diff는 `ORIGIN_MCP_URL` **한 줄뿐**이었다.

---

## 4. 운영 서비스 — 전부 무영향

| 대상 | 검증 후 상태 |
|---|---|
| 운영 Spring 18080 | `{"status":"UP"}` (PID 29984, 재시작 없음) |
| 운영 PostgreSQL `running_ai` | `flyway_schema_history` **20행(V20)** — 변경 전후 동일, 항목 G 미적용 유지 |
| Relay 17845 | 정상(PID 13588) |
| Funnel 443 | `/ proxy http://127.0.0.1:17845` 단일 항목 그대로 (`/mcp` 미추가) |
| `runningai-watchface-relay` | `da2cf611` / 2026-10-09T05:32:02.789Z **불변** |
| Canonical `main` · `origin` | 접근 0건 |
| `C:\running-ai` · `C:\running-ai-watchface` | 접근 0건 |
| git | 커밋·푸시 **0건**, 미커밋 산출물 그대로 보존 |

실제 Garmin 데이터, 운영 DB 사본(STEP 4), 운영 배포 A~F, PostgreSQL 항목 G는 **전부 미착수**이며
계속 별도 승인 대상이다.

---

## 5. ChatGPT 연결 정보 (사용자 실행용)

**등록할 MCP 서버 URL** (고정, 터널이 바뀌어도 불변):

```
https://running-ai-mcp.simjjy89.workers.dev/mcp
```

**절차**

1. ChatGPT → 설정 → 커넥터 → 커스텀 커넥터 추가
2. 서버 URL에 위 주소 입력, 인증 방식 **OAuth** 선택
3. ChatGPT가 `/.well-known/oauth-protected-resource/mcp`를 읽고 AS를 찾은 뒤
   CIMD 또는 DCR로 자기 자신을 등록한다 (수동 client id/secret 입력 없음)
4. 브라우저가 `…workers.dev/authorize` → **GitHub 로그인 화면**으로 이동
5. GitHub 계정 `simjjy89`로 로그인하고 권한 요청을 승인
   (요청 scope는 신원 확인용뿐. 다른 계정으로 로그인하면 **거부**되고 code가 발급되지 않는다)
6. 콜백이 ChatGPT로 돌아오면 연결 완료 — 도구 7종이 보여야 한다

**테스트 도구 7종과 예상 결과 (전부 합성 데이터)**

| 도구 | 요청 예 | 예상 |
|---|---|---|
| `get_recent_activities` | "최근 30일 러닝 보여줘" | 합성 6건 (`SMOKE-*`, 09-20~10-12) |
| `get_activity_analysis` | "2026-10-12 러닝 분석해줘" | 10.2 km / 60분, 디커플링 6.677 %, 다이내믹스 전체 |
| `get_activity_analysis` | "2026-10-08 인터벌 분석해줘" | 그룹 1개, 800 m × 5 rep, 회복 HR 32 bpm 하강 |
| `get_training_context` | "지금 훈련 컨텍스트" | V2 스냅숏 6,510 B + sha256 |
| `get_recovery_context` | "HRV·수면·Body Battery" | 기준선 28일, HRV 60.0 vs 61.9 |
| `get_fitness_trend` | "2026-09-15~2026-10-13 CTL/ATL" | 29일, CTL 38.42→, derivedForm = ctl − atl |
| `get_workout_recommendation` | "오늘 뭐 뛸까" | `INJURY_RESOLVED_CONFIRMED` → `EASY` 30~? 분 |
| `get_athlete_profile` | "내 LTHR" | **165 bpm / 310 s/km** (합성값 — 실제 176/290이 아님) |

실패 테스트도 직접 해볼 수 있다: `publish_workout` 호출 요청 → `-32602 Tool not found`,
없는 날짜 → `ACTIVITY_NOT_FOUND`, 범위 초과 입력 → `INVALID_ARGUMENT`.

**운영 영향**: 없음. 위 도구가 보는 것은 `running_ai_7d_smoke` 합성 DB 뿐이다.

### 유지·종료

- 터널 창을 닫거나 `cloudflared`를 종료하면 Worker는 502를 돌려주고 데이터 경로가 끊긴다.
  게이트웨이나 합성 Spring만 내려도 같다. Worker 자체는 떠 있고 ChatGPT 재등록은 필요 없다.
- 터널을 다시 켜면 주소가 바뀌므로 `ORIGIN_MCP_URL`만 고치고 `npx wrangler deploy` (약 15초).
- 로그: Worker `npx wrangler tail`, 게이트웨이/합성 Spring은 세션 scratchpad의 로그 파일.
- 완전 철거: 합성 Spring·게이트웨이·cloudflared 종료 → `DROP DATABASE running_ai_7d_smoke`.

---

## 다음 단계 (미착수, 승인 필요)

사용자의 ChatGPT 실호출 결과 확인 후 STEP 4(운영 DB **사본** 생성 → 사본에만 V21~V23 → 별도 포트)로
넘어간다. 실데이터 연결은 그 승인 전까지 하지 않는다. 테스트 환경은 사용자가 직접 OAuth 로그인과
도구 호출을 할 수 있도록 **기동 상태로 유지**한다.
