# Phase 7D-GPT-LIVE-2 — 배포 직전 구성 완료 보고 (KV 생성 + wrangler.jsonc 확정, 배포 승인 대기)

지시서: `2026-10-13-phase-7d-gpt-live-2-instruction.md`
선행 보고: `...-step1-2-result.md`, `...-predeploy-review-result.md`
기준: `main-3` HEAD `34cf121`. **Worker 배포 0건, 실데이터 0건, 운영 변경 0건.**

사용자 확정값: Cloudflare 서브도메인 `simjjy89.workers.dev`, Worker 이름 `running-ai-mcp`,
공개 주소 `https://running-ai-mcp.simjjy89.workers.dev`, GitHub OAuth App 등록 완료.

---

## 1. 작업 환경 / 미커밋 산출물 보존 확인

`main-3` 브랜치, HEAD `34cf121` 그대로. 이전 Phase의 미커밋 산출물이 **전부 보존**되어 있다.

| 경로 | 상태 |
|---|---|
| `tools/mcp-worker/` (소스 7, 테스트 2, 스크립트 1, 설정 2) | 보존 (이번에 `wrangler.jsonc`만 값 채움) |
| `.gitignore` (`.dev.vars`, `.wrangler/` 추가) | 보존 |
| `tools/mcp-gateway/README.md` (+8/−1) | 보존, 코드 무변경 |
| 7D-2 문서 6건 | 보존 |

로컬 개발 잔재(`.dev.vars`, `.wrangler/`) **없음** — 이전 세션에서 삭제된 상태 그대로다.

## 2. 테스트 재확인 — 100/100 PASS

Cloudflare 계정·네트워크·실데이터 없이 실행.

```
tools/mcp-worker   npm test            28/28 PASS
tools/mcp-worker   npm run test:oauth  43/43 PASS
tools/mcp-gateway  npm test            29/29 PASS   (코드 무변경 재확인)
                                       ───────────
                                       100/100
```

`wrangler.jsonc` 값을 채운 **뒤에도** 동일하게 통과했다 — OAuth 테스트는 배포 설정 파일에서
`global_fetch_strictly_public` 플래그 존재를 직접 검증하므로, 이 재실행이 CIMD 활성 상태를
다시 보증한다. 서버(Gradle) 코드는 이번에도 무변경이므로 회귀 1,539/1,539 그대로 유효.

## 3. Wrangler 로그인 / 계정 상태

`npx wrangler whoami` — **로그인 상태 정상**.

| 항목 | 값 |
|---|---|
| 계정 | `Simjjy89@gmail.com's Account` (`10709325b37c66a73e92da7974328935`) |
| 필요한 권한 | `workers_scripts(write)`, `workers_kv(write)`, `workers_tail(read)` **보유** |

**기존 Worker 보호 확인(읽기 전용 조회):**

- `runningai-watchface-relay` — 존재, 최종 배포 2026-10-09T05:32Z. **이번 작업에서 조회만
  했고 변경 0건.** 새 Worker는 `running-ai-mcp`라는 **별개 스크립트 이름**이므로 배포해도
  릴레이에 영향이 없다.
- `running-ai-mcp` — 계정에 **아직 존재하지 않음**(code 10007). 첫 배포가 신규 생성이 된다.

## 4. KV 네임스페이스 생성 (완료)

```
npx wrangler kv namespace create OAUTH_KV
→ id = fb4f6f1c669d44a8a6d05ec5c18233b9
```

사전 조회에서 계정의 KV 네임스페이스 목록이 `[]`였다 — 즉 **기존 릴레이 Worker는 KV를 쓰지
않으며**, 이 네임스페이스는 다른 어떤 것과도 공유되지 않는 신규 자원이다. 용도는 OAuth
라이브러리의 grant/토큰 해시 저장소뿐이고, 네임스페이스 id는 계정 자격증명 없이는 아무것도
할 수 없으므로 비밀값이 아니다(커밋 가능).

## 5. `wrangler.jsonc` 확정 (비밀값 0건)

| 키 | 확정값 | 비고 |
|---|---|---|
| `name` | `running-ai-mcp` | 릴레이와 별개 스크립트 |
| `kv_namespaces[0].id` | `fb4f6f1c669d44a8a6d05ec5c18233b9` | 위에서 생성 |
| `PUBLIC_BASE_URL` | `https://running-ai-mcp.simjjy89.workers.dev` | 끝 슬래시 없음, ChatGPT가 쓸 주소와 글자 단위 동일 |
| `ORIGIN_MCP_URL` | `https://origin-not-configured.invalid/mcp` | **임시 자리표시자.** `.invalid`는 RFC 2606 예약 TLD라 절대 resolve되지 않는다 → 터널 설정 전에는 도구 호출이 502로 끝나고, 어떤 실주소로도 새지 않는다 |
| `ALLOWED_GITHUB_ID` | `86603575` | 공개 API(`api.github.com/users/simjjy89`) 조회값. 틀렸다면 로그인 시 403으로 **안전하게** 실패한다 |
| `ALLOWED_GITHUB_LOGIN` | `simjjy89` | |
| `HOP_TOKEN_TTL_SECONDS` | `90` | |
| `compatibility_flags` | `nodejs_compat`, `global_fetch_strictly_public` | 후자는 CIMD 필수 |

**번들 검증(업로드 없음):** `npx wrangler deploy --dry-run --outdir <scratch>` → 251.19 KiB
(gzip 56.49 KiB), 바인딩 7개(KV 1 + vars 6) 정상 해석. 배포 명령 자체는 실행하지 않았다.

## 6. 남은 작업 = 비밀값 3건 + 배포 (사용자 조작/승인 필요)

비밀값은 **사용자 터미널에서만** 입력한다. 이 세션(Claude)에는 어떤 형태로도 출력하지 않으며,
키 생성 역시 Claude가 실행하지 않는다(`npm run keygen`의 stdout이 개인키이기 때문).

| 이름 | 종류 | 출처 |
|---|---|---|
| `HOP_SIGNING_JWK` | secret | `node scripts/generate-signing-key.mjs`의 **stdout 1줄** (stderr의 kid·공개 JWK는 비밀 아님) |
| `GITHUB_CLIENT_ID` | secret | GitHub OAuth App |
| `GITHUB_CLIENT_SECRET` | secret | GitHub OAuth App |

절차 원문은 `tools/mcp-worker/README.md` 4~5단계.

---

## 현재 상태 / 보호 대상

- 운영 Spring 18080 · 운영 PostgreSQL(V20, 항목 G 미적용) · Relay 17845 · Funnel 443 ·
  Watchdog · `C:\running-ai` · `C:\running-ai-watchface` · Canonical `main` · `origin`
  — **접근·변경 0건**
- `runningai-watchface-relay` Worker — 읽기 조회만, **변경 0건**
- 외부 노출 0건(모든 테스트는 127.0.0.1 / 로컬 workerd) · 실데이터 0건 · 사본 DB 미생성
- Worker 배포: **NOT_RUN** (승인 대기)
- ChatGPT 실제 OAuth E2E: **NOT_RUN** (배포 선행)
- 커밋/푸시: 하지 않음 (지시 없음)
