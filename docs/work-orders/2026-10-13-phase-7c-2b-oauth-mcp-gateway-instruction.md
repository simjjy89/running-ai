# WO-RUNNINGAI-PHASE-7C-2b — Auth0 OAuth 인증 및 MCP Gateway 구현 (작업 지시서 원문)

## 목표

ChatGPT에서 인증된 사용자만 RunningAI 읽기 전용 MCP 도구를 호출할 수 있도록 OAuth 2.1 기반
인증 게이트웨이를 구축한다.

이번 단계는 개발 환경과 격리된 테스트 환경까지만 검증한다. 운영 Spring, 기존 Tailscale
Funnel 443 및 실제 Garmin 데이터는 외부에 연결하지 않는다.

## STEP 1 — Phase 7C-2a 검토

기존 조회 도구 7종의 등록 설정 및 호출 구조를 확인한다.

- read-enabled 기본 false 유지
- publish_workout 동시 등록 및 실행 차단 확인
- get_recent_activities와 get_activity_analysis 사이의 활동 선택 식별 방식 검증
- 훈련 추천 시 기존 Injury Safety Gate 유지
- 조회만으로 데이터 갱신이나 외부 동기화가 발생하지 않는지 검증

EnvironmentPostProcessor의 설정 우선순위 및 read/write 조합별 회귀 테스트를 추가한다.

## STEP 2 — OAuth 아키텍처 구현

신규 tools/mcp-gateway 패키지를 구성한다. 게이트웨이는 기본적으로 127.0.0.1에만 바인딩한다.
외부 요청에 대해 인증과 권한을 검증한 후, 개발용 RunningAI MCP 서버로만 요청을 전달한다.

기능: ① RFC 9728 Protected Resource Metadata ② OAuth 401 WWW-Authenticate ③ Auth0
authorization server metadata 연동 ④ Authorization Code + PKCE S256 호환 ⑤ CIMD 우선,
필요 시 DCR/사전 등록 ⑥ Auth0 resource parameter compatibility ⑦ access token 검증
⑧ JWT issuer/audience/signature/exp/nbf/scope/sub 검증 ⑨ subject allowlist ⑩ 인증·설정·
JWKS 장애 시 Fail Closed.

JWT 서명/JWKS 검증에 검증된 유지보수 라이브러리 사용, 자체 암호 구현 금지. OpenAI OAuth
callback URL은 ChatGPT 설정 화면 실측값 사용(추정 금지).

## STEP 3 — 읽기 전용 보안 경계

gateway 허용 도구는 7C-2a 읽기 7종뿐. publish_workout·임의 JSON-RPC method·미지 tool·REST
프록시·동기화/게시 금지. 모든 요청에 사용자 식별·권한 검증 강제, 인증 전 개인 데이터 반환
금지, 운영 Spring /mcp 직접 접근 금지. initialize/tools/list 접근은 ChatGPT OAuth 연결
규격에 맞게. 혼합 인증 시 비인증 상태에 도구 이름·스키마 외 실데이터 노출 금지.

## STEP 4 — Auth0 호환성

Auth0 테스트 테넌트에서: Auth for MCP 가용성 / API audience·resource 일치 / Resource
Parameter Compatibility / PKCE S256 / CIMD·DCR 실동작 / token endpoint auth method / scope
runningai:read / 사용자 1명·공개 가입 차단 / 토큰 만료·재인증 / redirect URI allowlist.
무료 플랜에서 불가·유료 요구 시 결제하지 말고 보고. 계정 생성·로그인·승인은 사용자에게 요청.

## STEP 5 — 개발 환경 검증 (Mock OAuth/JWKS 자동 테스트)

필수 부정 테스트: 토큰 없음 / 잘못된 서명 / 변조 JWT / 만료 / nbf 이전 / 잘못된 issuer /
잘못된 audience / read scope 누락 / 비허용 sub / 비허용 알고리즘 / 미지 kid / JWKS 조회 실패 /
잘못된 resource / publish_workout 강제 호출 / 임의 경로·타 서비스 접근. **모든 비인가 요청은
RunningAI 백엔드 호출 0회.**

## STEP 6 — ChatGPT OAuth 실연동 PoC

Auth0 설정과 로컬 자동 테스트 완료 후. 7C-1.1의 Cloudflare Quick Tunnel 방식 재사용(인증
게이트웨이에만 연결). 임시 주소 확보 → resource/audience 일치 → 주소 변경 시 재등록 필요
설정 사전 식별. 실제 개인 데이터 대신 고정 테스트 응답만.

검증: ① 플러그인 OAuth 연결 ② Auth0 로그인 화면 ③ 로그인·동의 ④ 토큰 발급·검증 ⑤ 인증된
ping_running_ai 성공 ⑥ 미인증 거부 ⑦ 타 사용자/권한 거부 ⑧ Relay 443 정상. 실제 OAuth
로그인 시점에 사용자 조작 요청.

## STEP 7 — 운영 보호 및 완료 보고

운영 Spring/PostgreSQL/Flyway/Connector/Intervals/Watchface/Relay 변경 금지. Canonical·origin
금지. 인증 정보·토큰 Git 금지. 테스트 후 임시 Tunnel 정리.

보고: Auth0 설정·비용 / Gateway 구조 / 메타데이터·resource 매칭 / JWT 검증 테스트 / ChatGPT
OAuth·도구 호출 / 접근 제한 검증 / 인프라 무변경 증거 / 7C-2c 선행 조건 / GO/NO-GO.

main-3 커밋 후 STOP.
