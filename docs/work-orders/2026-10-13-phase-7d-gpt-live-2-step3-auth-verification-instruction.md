# WO-RUNNINGAI-PHASE-7D-GPT-LIVE-2 STEP 3 (지시서 원문)

RunningAI MCP — GitHub OAuth 인증 검증 및 ChatGPT E2E

사용자가 Cloudflare Dashboard에서 다음 Secret 등록을 완료했다고 보고했다.

* GITHUB_CLIENT_ID
* GITHUB_CLIENT_SECRET

기존 HOP_SIGNING_JWK는 등록된 상태다.

이제 Phase 7D-GPT-LIVE-2 STEP 3를 이어서 진행해 줘.

1. 인증 설정 검증

* wrangler secret list에서 3개 Secret 존재 확인
* /internal/jwks.json HTTP 200 확인
* JWKS kid=d914b4c25d6b242f 확인
* 공개 JWK에 개인키 필드가 없는지 확인
* OAuth AS 및 Protected Resource 메타데이터 확인
* 무인증 /mcp 요청 401 확인
* 잘못된 토큰 및 허용되지 않은 경로 차단 검증
* 기존 runningai-watchface-relay 불변 확인

Secret 값 자체는 절대 출력하지 않는다.

2. 합성 데이터 E2E 환경 준비

검증이 PASS하면 기존 Phase 7C-2 합성 데이터 스모크 구성을 재사용한다.

* 합성 데이터 전용 Spring 18081
* 기존 MCP Gateway 17910
* 로컬 PostgreSQL 합성 데이터 DB
* Cloudflare Quick Tunnel
* Worker → Gateway → Spring 연결
* MCP 읽기 도구 7종만 노출
* publish_workout 및 모든 쓰기 동작 차단

운영 데이터 및 실제 Garmin 정보는 사용하지 않는다.

Worker의 ORIGIN_MCP_URL을 수정해야 할 경우 변경 전 연결 대상과 승인 필요 사항을 보고한다. 인증되지 않은 원점 접근을 허용하지 않는다.

3. ChatGPT 연결 준비

합성 데이터 환경이 정상이고 외부 인증 차단 검증까지 완료되면 다음 정보를 보고하고 STOP한다.

1. 합성 데이터 서비스 기동 상태
2. Worker 및 Gateway 인증 검증 결과
3. ChatGPT에 등록할 MCP URL
4. ChatGPT에서 GitHub 로그인하는 정확한 절차
5. 테스트 도구 7종과 예상 결과
6. 운영 서비스 영향 여부

사용자가 ChatGPT에서 직접 OAuth 로그인 및 MCP 호출을 수행할 수 있도록 테스트 환경은 유지한다.

실제 Garmin 데이터 연결, 운영 DB 변경, canonical main 병합, origin push는 하지 않는다.

기존 코드와 테스트를 재사용하고 불필요한 신규 기능 개발은 하지 않는다.
