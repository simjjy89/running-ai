# WO-RUNNINGAI-PHASE-7C-1.1 — ChatGPT MCP 실연동 E2E (결과 보고: **PASS**)

지시서 흐름: 7C-1.1(Tailscale Funnel 8443) → 연결 실패 진단 → 승인된 권고안 C(Cloudflare
Quick Tunnel)로 전환 → **ChatGPT 실호출 성공(사용자 확인)** → 보안 정리 완료.

기준: `main-3` `2d508f5`(7C-1 PoC). 운영 Spring/PostgreSQL/Connector/Watchface/origin
무변경. PoC(`tools/mcp-poc`) 코드는 이 Phase에서 1바이트도 수정하지 않고 재사용했다
(포트만 env로 17902).

## 1. 경과 요약

| 단계 | 결과 |
|---|---|
| STEP 1 Tailscale 현황(읽기 전용) | v1.104.1, 노드 `laptop-mmdqucic.tail4597a5.ts.net`, Funnel **443 → 127.0.0.1:17845(Relay)만** 존재, 8443 없음, HTTPS 지원. 설정 JSON 백업(스크래치, 저장소 밖 — 실호스트명 커밋 최소화) |
| STEP 2 PoC(17902) | 기존 7C-1 서버 재사용, 127.0.0.1 전용, 도구는 `ping_running_ai` 1개, `publish_workout` 서버 측 거부 재확인 |
| STEP 3 인증 호환성 | 공식 Add custom MCP server 문서 기준 Server URL + No authentication/OAuth 확인(7C-1 STEP 0). 무인증은 이 격리 PoC 한정 조건 전부 준수 |
| STEP 4 Funnel 8443 | 사용자 승인 후 `tailscale funnel --bg --https=8443 http://127.0.0.1:17902` — 443 전후 비교로 무변경 확인 |
| STEP 5 외부 검증(8443) | TLS(Let's Encrypt, strict Validated=True)·initialize·tools/list·ping·쓰기거부·404/405·443 Relay 200 — 전수 PASS |
| **ChatGPT 연결(8443)** | **FAIL** — "MCP 서버에 연결하지 못했습니다" |

## 2. 실패 진단 (지시된 8항목 전수)

PoC 프로세스/LISTEN/로컬 initialize/Funnel 매핑/외부 initialize(공개 엣지 경유 200)/DNS/
TLS/443 Relay — **전부 정상**. 결정적 증거: **PoC 접근 로그에 ChatGPT발 요청 0건** → 실패는
OpenAI egress 측. 판정: **ChatGPT MCP 커넥터의 비표준 포트(8443) 미지원**(커뮤니티 동일
사례 다수). GET /mcp=405는 stateless 사양 동작으로 원인 아님.

## 3. 해결: Cloudflare Quick Tunnel (사용자 승인, 권고안 C)

1. **Funnel 8443 해제**(백업 후 `--https=8443 off`) — 443 핸들러는 백업과 동일 유지,
   해제 후 8443 외부 접근 불가 실측, Relay `/health` 200.
2. 기존 설치된 `cloudflared 2026.9.3`로 `cloudflared tunnel --url http://127.0.0.1:17902`
   별도 프로세스 실행(기존 서비스/config.yaml 무접촉, 충돌 없음) →
   임시 `https://offshore-values-lot-permit.trycloudflare.com` (표준 443).
3. 외부 재검증 전수 PASS: TLS strict(ssl_verify=0), initialize(2025-06-18), tools/list
   1개(`ping_running_ai`), ping 고정응답, `publish_workout` `-32602` 거부, 443 Relay 200,
   cloudflared 로그 오류 0.

## 4. **ChatGPT E2E: PASS (2026-10-13, 사용자 계정 실측 확인)**

Server URL = `https://offshore-values-lot-permit.trycloudflare.com/mcp`, No authentication,
플러그인 설치 후 실호출 — 수신 응답(사용자 보고 원문):

```json
{"success": true, "service": "RunningAI MCP PoC", "mode": "TEST_ONLY",
 "message": "RunningAI MCP connection verified"}
```

→ 7C-1 결과 보고의 STEP 4-7(PENDING_USER)은 본 문서로 **PASS 확정**. 부수 확인: ChatGPT는
`/mcp` **하위 경로를 문제없이 사용**했다(루트 강제 아님) — 후속 고정주소 설계에 중요한 실측.

## 5. 보안 정리 (테스트 종료 직후)

- Quick Tunnel 프로세스만 종료 → 임시 URL **530**(터널 소멸, 라우팅 불가) 확인.
- PoC Node 프로세스 종료 → 17902 LISTEN 없음(connection refused).
- cloudflared 잔존 프로세스 0, Funnel 설정에 8443 항목 0.
- 기존 443 Relay: 외부 `/health` 200 + 로컬 17845 `/health` 200 재확인.
- Git/로그 점검: 임시 trycloudflare 주소는 이미 소멸한 1회용이라 기록 무해; 토큰·비밀값은
  애초에 존재하지 않음(무인증 PoC).

## 6. 결론 및 확정된 사실 (7C-2 설계 입력)

1. **ChatGPT ↔ 자체 호스팅 MCP 연동은 실증 완료** — Streamable HTTP stateless + 표준 443
   + `/mcp` 경로 + (PoC 한정) 무인증으로 initialize→tools/list→tools/call 전 구간 동작.
2. **포트 제약 실측**: 8443 불가, 443 필수 → 고정 주소 설계는 443 기반이어야 함.
3. **경로 제약 없음 실측**: `/mcp` 하위 경로 정상 → 기존 Funnel 443에 `--set-path /mcp`
   추가(Relay `/` 핸들러 불변)가 유효한 무료 고정주소 후보.
4. 무인증은 여기까지 — **실데이터(7C-2)는 OAuth 선행 필수**(별도 설계 문서 참조).

7C-2 상세 설계: `2026-10-13-phase-7c-2-readonly-mcp-tools-design.md`.
