# WO-RUNNINGAI-PHASE-7C-1 — ChatGPT MCP 연결 가능성 검증 및 보안 PoC (작업 지시서 원문)

## 목표

RunningAI를 ChatGPT에서 직접 호출할 수 있는 연결 경로를 검증한다.

이번 단계에서는 운영 데이터를 외부에 노출하지 않고, 가짜 데이터만 반환하는 읽기 전용 MCP
도구로 실제 연결 가능성을 확인한다.

## STEP 0 — ChatGPT 환경 확인

현재 공식 OpenAI 문서를 기준으로 다음을 확인한다.

1. ChatGPT Plugins의 Add custom MCP server 사용 가능 여부
2. ChatGPT Work 모드의 개인 플러그인 설치·실행 경로
3. Secure MCP Tunnel 사용 가능 여부
4. OpenAI Platform 조직과 ChatGPT workspace 연결 조건
5. 실제 사용자 계정에서 필요한 권한 및 UI 조작

플랜별 권한을 추측하지 말고 실제 화면과 공식 문서를 기준으로 판단한다.

사용자 로그인, 권한 승인, 플러그인 설치가 필요하면 해당 단계에서 멈추고 사용자에게 필요한
조작만 요청한다.

## STEP 1 — 연결 방법 결정

우선순위:

A. Secure MCP Tunnel + 로컬 읽기 전용 MCP

B. 별도 HTTPS 엔드포인트 + OAuth 인증 게이트웨이

Secure MCP Tunnel이 사용 가능하면 기존 Tailscale Funnel 설정을 변경하지 않는다.

Secure MCP Tunnel 사용이 불가능할 때만 대체안을 검토한다.

DCR은 필수 요구사항으로 고정하지 않는다. CIMD, DCR, 사전 등록 OAuth 클라이언트 중 실제
지원되는 방식을 선택한다.

개인 활동 데이터를 조회하는 실서비스에서는 사용자 인증과 권한 검증이 반드시 필요하다.

## STEP 2 — 격리된 MCP PoC

현재 운영 Spring 서버와 독립된 프로세스·포트를 사용한다.

`ping_running_ai`라는 테스트 도구 하나만 제공한다.

응답 예시:

```json
{
  "success": true,
  "service": "RunningAI MCP PoC",
  "mode": "TEST_ONLY",
  "message": "RunningAI MCP connection verified"
}
```

이 도구는 실제 DB, Garmin, Intervals.icu에 접근하지 않는다.

개인 데이터와 인증 토큰을 응답하거나 로그에 남기지 않는다.

## STEP 3 — 읽기/쓰기 분리 설계 검증

기존 `publish_workout`이 ChatGPT용 읽기 전용 MCP에서 발견되거나 실행되지 않도록 구조적으로
분리한다.

필수 검증:

- `tools/list` 결과에 쓰기 도구 없음
- 강제로 `publish_workout` 호출 시 거부
- 읽기 전용 scope로 쓰기 실행 불가
- 임의의 REST API 프록시 호출 불가
- 운영 Spring의 기존 `/mcp` 직접 공개 금지
- readOnlyHint는 실제 읽기 전용 도구에만 사용

도구 이름을 숨기는 것만으로 만족하지 않고 서버 측에서 호출을 차단해야 한다.

## STEP 4 — 연결 및 인증 검증

선택한 연결 방식에 따라 다음을 검증한다.

1. MCP initialize
2. tools/list
3. ping_running_ai
4. 인증 실패 응답
5. 비인가 사용자 접근 차단
6. 타임아웃 및 재연결
7. 실제 ChatGPT 플러그인에서 도구 선택 여부

공개 HTTPS 방식을 선택했다면 OAuth metadata, PKCE S256, token issuer/audience/expiration/scope
검증을 포함한다.

실제 ChatGPT 연결은 사용자 계정에서 확인된 결과만 PASS로 기록한다.

MCP Inspector 성공만으로 ChatGPT 연동 완료라고 보고하지 않는다.

## STEP 5 — 후속 읽기 도구 설계 확정

7C-2에 구현할 읽기 전용 도구:

- get_recent_activities
- get_activity_analysis
- get_training_context
- get_recovery_context
- get_fitness_trend
- get_workout_recommendation
- get_athlete_profile

각 도구의 입력 스키마, 출력 스키마, 예외 코드, 개인정보 최소화 방침을 정의한다.

조회 과정에서 DB 변경, Garmin Sync, Draft 저장, Workout 게시가 발생하지 않도록 한다.

추천 기능도 결과를 저장하거나 갱신하지 않는 경우에만 읽기 전용으로 분류한다.

## STEP 6 — 운영 보호

- 운영 Spring / PostgreSQL / Connector / Relay 변경 금지
- 기존 Tailscale Funnel 443 포트 변경 금지
- 운영 Flyway V21/V22 적용 금지
- 실제 Garmin·Intervals 계정 쓰기 금지
- origin/main 병합 금지
- 개인 인증 정보 저장소 커밋 금지
- 기존 Phase 7B-2 안전 게이트 우회 금지

## 완료 보고

다음을 명확히 보고한다.

1. 실제 ChatGPT 연결 가능 여부
2. 사용자 계정의 필요한 권한
3. 선정한 연결 방식과 이유
4. 연결에 사용한 격리 환경
5. MCP 초기화 및 도구 실행 결과
6. 인증·권한 검증 결과
7. 쓰기 도구 노출 방지 검증
8. 실제 운영 영향
9. 7C-2 구현 착수 GO/NO-GO

이번 단계가 실패하더라도 운영 서비스를 건드려서 억지로 연결하지 않는다.

성공 시 문서와 테스트 결과를 남기고 STOP한다.
