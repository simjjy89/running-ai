# Phase 7C-2 설계 — 읽기 전용 RunningAI MCP 도구 7종 + 고정 HTTPS + OAuth (구현 계획)

전제(7C-1.1에서 실증): ChatGPT 커스텀 MCP는 표준 443 + `/mcp` 하위 경로 + Streamable HTTP
stateless로 동작한다. 실데이터 연결 전 확정해야 하는 3요소 — 고정 주소, OAuth, 접근 통제 —
를 아래와 같이 확정 제안한다. (이 문서는 설계이며, 구현·운영 반영은 각각 별도 승인.)

## A. 고정 HTTPS 주소 (권고: Tailscale Funnel 443 + `/mcp` 경로 추가)

```
tailscale funnel --bg --set-path /mcp http://127.0.0.1:<gateway-port>
```

- 결과: `https://laptop-mmdqucic.tail4597a5.ts.net/mcp` (고정, 무료, 인증서 자동).
- Relay의 `/ → 17845` 핸들러는 그대로 유지(워치 경로 `/health`, `/today-workout` 영향 없음
  — 경로 핸들러는 최장일치 라우팅). **443 설정 집합에 핸들러 1개가 추가되는 변경이므로 운영
  반영 시 명시 승인 + 전후 JSON 백업 비교 + 즉시 롤백 명령(`--set-path /mcp off` 형태) 문서화.**
- 7C-1.1 실측으로 "ChatGPT가 루트를 요구할지 모른다"는 우려는 해소됨(하위 경로 정상).
- 예비안: 보유 도메인 + Cloudflare Named Tunnel(무료지만 도메인 필요), Quick Tunnel은
  주소가 매번 바뀌므로 운영 부적합(PoC 전용).

## B. OAuth 인증 (권고: 외부 IdP Auth0 free tier + 로컬 검증 게이트웨이)

ChatGPT 지원 방식(공식 문서): OAuth(정적 사전등록 클라이언트 / CIMD / DCR) 또는 무인증.
무인증·고정 Bearer는 실데이터에 불가(지시사항). 비교:

| 안 | 내용 | 평가 |
|---|---|---|
| **B1. Auth0 free (권고)** | IdP가 AS(authorization_code+PKCE S256, 필요 시 DCR까지), 단일 사용자 계정만 생성. 게이트웨이는 JWKS로 RS256 JWT 검증만 | 무료(월 25k MAU 한도 >> 1인), 검증된 보안, 코드 최소. ChatGPT 연동 공식 가이드 존재 |
| B2. 자체 AS 내장 | 게이트웨이에 OAuth 2.1 AS 직접 구현 | 외부 의존 없음이나 AS 보안을 직접 짊어짐 — 1인용 치고 과대 리스크, 비권고 |

**게이트웨이** `tools/mcp-gateway` (신규, Relay와 동일한 의존성-제로 Node 원칙):
127.0.0.1:<port> 바인딩, 역할은 ① `Authorization: Bearer <JWT>` 검증 — **issuer(테넌트
URL)·audience(`runningai-mcp`)·exp·nbf·alg(RS256 고정)·scope** 전부 일치해야 통과, JWKS
캐시(+키 롤오버 대응) ② 통과 요청만 로컬 Spring `/mcp`로 프록시(타 경로/메서드 404/405)
③ OAuth Protected Resource metadata(`/.well-known/oauth-protected-resource`) 서빙으로
ChatGPT가 IdP를 발견하게 함 ④ 레이트리밋(기본 60req/min)·응답크기 상한·토큰 무기록 로깅.
**Fail Closed**: 검증 실패·JWKS 조회 실패·설정 누락 시 전부 401/503, Spring 미접촉.

## C. 사용자 접근 통제 (단일 사용자)

- IdP 테넌트에 사용자 **1명만** 생성, 회원가입 비활성.
- 게이트웨이가 JWT `sub`를 **허용목록 1건과 대조**(`RUNNING_AI_MCP_ALLOWED_SUB`) — 테넌트
  설정 실수로 제3자 토큰이 발급돼도 차단되는 2차 방어.
- scope: 7C-2에서는 `runningai:read`만 발급(IdP API 권한에 write scope 자체를 정의하지
  않음). 게이트웨이도 read scope 없으면 401. 쓰기 도구는 Spring 쪽 기존 스위치 체계에서
  계속 OFF — 3중(발급 불가/게이트웨이/스위치).
- 토큰 수명 단기(기본 1h), refresh는 ChatGPT-IdP 간 표준 흐름. 폐기 = IdP에서 세션/그랜트
  해지 + 필요 시 allowed-sub 회전.

## D. 읽기 전용 도구 7종 (Spring `integration.mcp.read`, 스키마는 7C-1 결과 문서 STEP 5 확정안)

`get_recent_activities` / `get_activity_analysis`(7B-3 다이내믹스 포함) /
`get_training_context`(V2) / `get_recovery_context` / `get_fitness_trend` /
`get_workout_recommendation`(결정론적·무영속) / `get_athlete_profile`(provenance 경고 포함).

- 등록 스위치 `running-ai.mcp.read-enabled`(기본 false, 기존 `running-ai.mcp.enabled`
  (쓰기 publish_workout)와 **독립** — read만 on인 조합이 7C-2 운영형).
- 전부 기존 `@Transactional(readOnly)` 서비스 호출만: DB 변경·Garmin Sync·Intervals 호출·
  Draft 저장·게시 코드 경로 없음. 응답에 내부/외부 ID·GPS·원본 페이로드·비밀값 불포함.
- `McpReadToolsArchitectureTest`(신규): read 도구 소스가 publisher/draft/Garmin-write/
  `WorkoutPublishApplicationService` 타입을 참조하지 못함을 소스 스캔+생성자 의존성으로 강제
  (기존 `CoachArchitectureTest` 패턴).
- 예외 코드: `INVALID_ARGUMENT` / `NOT_FOUND` / `INSUFFICIENT_DATA` / `INTERNAL_ERROR`
  (스택·내부 상세 미노출), 전 도구 `readOnlyHint:true` + `openWorldHint:false`.

## E. 구현 순서 (각 단계 종료 시 보고, 운영 반영은 별도 승인)

1. **7C-2a (개발)**: Spring read 도구 7종 + 스위치 + 아키텍처/단위/MockMvc-MCP 테스트,
   전체 회귀. H2/로컬로 검증, 운영 미배포.
2. **7C-2b (인증 인프라)**: Auth0 테넌트(사용자 수동 1회: 계정 생성·API/Application 등록)
   + `tools/mcp-gateway` 구현 + 로컬 JWT 검증 테스트(만료/오audience/오issuer/무scope/
   위조서명 → 전부 401 자동 테스트).
3. **7C-2c (운영 연결, 전부 승인 후)**: 운영 V21/V22 적용+Spring 재기동(선행 의존) →
   `read-enabled=true` → Funnel 443 `--set-path /mcp` 추가 → ChatGPT 플러그인을 OAuth로
   재등록 → 목표 질문 4종 실검증(7C-3) → 이상 시 `/mcp` 경로 제거만으로 즉시 차단.

## F. 선행 조건·잔여 리스크

- 운영 Spring은 아직 7B-2(V21)/7B-3(V22) 미배포 — 7C-2c 전에 적용 필요(별도 승인).
- Auth0 무료 티어 정책 변경 리스크: 게이트웨이는 표준 JWT 검증이라 IdP 교체 가능하게 유지.
- ChatGPT 커넥터 동작 변화(2026년에도 보고되는 불안정) — 7C-3에서 재검증으로 흡수.
- LTHR 출처 이력(7B-4)은 독립 과제로 병행 가능 — `get_athlete_profile` 경고문이 실데이터로
  대체되는 시점.
