# WO-RUNNINGAI-PHASE-7D-GPT-LIVE-2 (지시서 원문)

ChatGPT ↔ RunningAI 실제 데이터 연동

## 역할

RunningAI 시니어 백엔드 아키텍트 / MCP 연동 개발 리드 / 보안 및 QA 책임자.

## 최우선 목표

사용자가 현재 ChatGPT 대화에서 RunningAI MCP 읽기 도구 7종을 직접 호출하여 자신의 Garmin 및 Intervals.icu 데이터를 분석하도록 한다.

OpenAI Platform, Secure MCP Tunnel, OpenAI API Key는 사용하지 않는다. Auth0 설정도 보류한다.

Claude CLI AI Coach와 기존 RunningAI 분석 엔진은 유지한다.

새로운 웹 UI, 러닝 분석 알고리즘, 훈련 생성 엔진은 개발하지 않는다.

## 현재 상태

* 개발 HEAD: 34cf121
* 검증 RC: 2208872
* Canonical main: 370ee8f
* 운영 PostgreSQL: Flyway V20
* 개발 마이그레이션: V23
* 서버 회귀 테스트: 1,539/1,539 PASS
* MCP 읽기 도구 7종 구현 및 실호출 검증 완료
* Cloudflare Quick Tunnel 합성 데이터 E2E 성공
* tools/mcp-gateway OAuth JWT 검증 및 server/discover 처리 구현
* 실제 데이터의 ChatGPT OAuth 인증 E2E는 미완료

## STEP 1 — 기존 연결 코드 재사용 검토

다음 기존 구성요소를 우선 분석한다.

* tools/mcp-gateway
* Spring /mcp
* MCP 읽기 도구 7종
* Cloudflare Quick Tunnel 실행 절차
* Phase 7C-2 합성 데이터 스모크 테스트
* ChatGPT의 server/discover 호환성 수정

이미 통과한 구현을 다시 만들지 않는다.

특히 기존 Gateway가 Auth0 JWT의 어떤 속성에 의존하는지 확인한다.

* issuer
* audience
* JWKS
* RS256
* scope: runningai:read
* subject allowlist

인증 공급자 변경이 가능한 구조인지 판단한다.

## STEP 2 — Auth0 없는 OAuth 방식 확정

우선순위:

A. Cloudflare OAuth Provider Library + Cloudflare Access

B. Cloudflare OAuth Provider Library + 기존 GitHub 로그인

C. 위 방식이 실제 ChatGPT와 호환되지 않을 경우, 기존 Gateway에 적용 가능한 다른 표준 OAuth 2.1 공급자

공식 자료:

* https://developers.cloudflare.com/agents/model-context-protocol/protocol/authorization/
* https://github.com/cloudflare/workers-oauth-provider
* https://developers.openai.com/plugins/build/auth

검증 사항:

1. ChatGPT OAuth Authorization Code + PKCE S256 지원
2. CIMD 또는 DCR 클라이언트 등록
3. OAuth protected resource metadata
4. authorization server discovery
5. 사용자 1명으로 제한할 수 있는지
6. read-only scope 제한
7. 토큰 만료 및 refresh 처리
8. Cloudflare 무료 계정에서 사용 가능 여부
9. 기존 Gateway에 대한 최소 변경 범위
10. 고정 HTTPS 주소 필요 여부

Cloudflare Quick Tunnel URL이 매번 달라지는 문제도 검토한다.

가능하다면 Cloudflare Workers의 고정 HTTPS 엔드포인트를 사용하고, 로컬 RunningAI로 연결하는 경로는 별도로 보호한다.

Workers OAuth Provider가 발급하는 토큰이 기존 Gateway의 Auth0 JWT 검증과 동일하다고 가정하지 않는다.

JWT 방식 변경이 필요하면 서명·발급자·audience·scope·subject를 검증하는 명시적인 신뢰 경계를 구현한다.

검증을 끄거나 토큰을 무조건 승인하는 임시 우회는 금지한다.

## STEP 3 — 합성 데이터로 OAuth E2E 먼저 검증

이미 성공한 7C-2 합성 데이터 스모크를 재사용한다.

검증 순서:

1. 인증 공급자 설정
2. 로컬 MCP 개발 인스턴스 기동
3. OAuth Gateway 연결
4. Cloudflare HTTPS 연결
5. ChatGPT custom MCP plugin 등록
6. 실제 브라우저 로그인
7. 읽기 도구 7종 호출

필수 실패 테스트:

* 무인증 요청 거절
* 잘못된 토큰 거절
* 만료된 토큰 거절
* 잘못된 audience/issuer 거절
* scope 부족 거절
* 허용되지 않은 사용자 거절
* publish_workout 거절
* /api 및 /actuator 차단
* 미지원 server/discover에 5xx 대신 정상 JSON-RPC 오류

OAuth 연결이 정상임을 확인하기 전에는 실제 Garmin 데이터에 연결하지 않는다.

## STEP 4 — 실제 데이터 사본 연결

STEP 3 PASS 후 진행한다.

* 운영 PostgreSQL의 사본 DB 생성
* 사본에만 V21~V23 적용
* 별도 Spring 포트 사용
* 모든 조회 도구는 사본 DB만 접근
* 자동 동기화·운동 게시·쓰기 기능 OFF
* Injury Safety Gate ACTIVE 유지
* 데이터 측정일과 수집 시각 표시

사본 DB라도 실제 개인 데이터이므로 공개 접근을 허용하지 않는다.

Cloudflare 외부 URL에서 로컬 원본 Gateway로 직접 우회 접근할 수 없어야 한다.

OAuth 계층을 거치지 않은 모든 도구 호출은 거절해야 한다.

## STEP 5 — ChatGPT 실제 분석

사용자가 ChatGPT에서 직접 다음을 요청하도록 한다.

1. 최근 러닝 5개 조회
2. 2026-10-11 서울레이스 하프 심층 분석
3. 최신 HRV·수면·Body Battery와 훈련부하 분석
4. LTHR 및 Threshold Pace 분석
5. 현재 무릎 상태를 반영한 안전한 운동 추천

실제 RunningAI 데이터와 ChatGPT 결과가 일치해야 한다.

현재 무릎 통증 상태에서 훈련 안전 게이트를 RESOLVED로 변경하지 않는다.

## STEP 6 — 사용 가능한 상태 유지

E2E 검증이 완료되면 다음 정보를 사용자에게 제공한다.

* ChatGPT에 등록한 RunningAI 플러그인 이름
* 사용자가 직접 로그인하는 방법
* 연결 유지 및 재연결 방법
* PC 재부팅 후 기동 방법
* 로그 확인 방법
* 안전한 종료 방법

자동 시작이나 상시 외부 노출은 별도 승인 후 적용한다.

## 기존 시스템 보호

다음은 절대 변경하지 않는다.

* C:\running-ai 레거시
* C:\running-ai-watchface
* 운영 Spring 18080
* 운영 PostgreSQL 스키마·데이터
* 기존 Relay 17845
* 기존 Funnel 443
* 운영 Watchdog
* Canonical main
* origin

PostgreSQL 보안 항목 G 및 운영 배포 A~F는 계속 별도 승인 대상으로 유지한다.

## 실행·승인 기준

소스 조사, 설계 검증, 로컬 테스트, 합성 데이터 스모크는 진행한다.

Cloudflare 계정 생성, OAuth 애플리케이션 등록, 외부 Worker 배포, 비용 발생, 실제 데이터 외부 연결처럼 사용자 조작·승인이 필요한 지점에서는 정확한 절차를 제시하고 STOP한다.

사용자가 승인한 범위에서만 다음 단계를 진행한다.

## 완료 조건

최종 성공 기준은 다음과 같다.

ChatGPT가 인증된 RunningAI MCP 도구를 호출해 2026-10-11 서울레이스 하프의 실제 Garmin 러닝 데이터를 반환하고, Injury Safety Gate가 정상 적용되는 것.

인증이 확립되지 않은 상태에서 실제 데이터 조회를 시도하지 않는다.

이미 검증된 기능은 재개발하지 않고 필요한 연결 부분만 최소한으로 구현한다.
