# Phase 7F-3B-3 — instruction (verbatim)

RunningAI — ChatGPT Train OAuth Scope Selection Fix

목표

ChatGPT가 RunningAI OAuth 재연결 시 runningai:train을 요청하지 않는 문제를 해결한다.

현재 Spring, Gateway, Worker의 권한 검사 및 도구 등록은 정상이다.

기존 기능을 다시 구현하지 않는다.

확인된 사실

* Spring MCP 도구 13종 정상 등록
* Gateway Read 7 + Train 5 허용
* Worker runningai:read / runningai:train 지원
* ChatGPT 재연결 시 신규 Grant 생성 확인
* 신규 Grant에는 runningai:read만 포함
* runningai:publish는 계속 차단
* 운영 DB와 Intervals 캘린더 정상

STEP 1 — ChatGPT 도구 갱신 가능성 확인

현재 RunningAI OAuth Smoke가 최초 Read 7종만 등록된 상태인지 확인한다.

다음 사항을 검토한다.

* ChatGPT 플러그인 또는 앱의 도구 목록 갱신 가능 여부
* 도구 목록이 설치 시점에 고정되는지
* OAuth 메타데이터와 도구 목록 캐싱 여부
* 기존 플러그인에 Train 도구가 반영될 수 있는 최소 절차

지원되지 않는 UI 기능을 임의로 있다고 가정하지 않는다.

STEP 2 — OAuth Scope 선택 경로 검증

MCP OAuth 규격에 따라 다음을 점검한다.

* 초기 401 WWW-Authenticate scope
* Protected Resource Metadata scopes_supported
* Authorization Server Metadata scopes_supported
* 실제 authorize 요청의 scope 유무
* insufficient_scope 응답과 재인증 동작
* 신규 토큰의 최종 scope
* tools/list 권한 필터링 결과

민감한 OAuth 코드, 토큰 및 키는 출력하지 않는다.

STEP 3 — 최소 해결 방법 선정

우선순위:

A. 기존 플러그인의 도구 목록 갱신 및 명시적 Scope 요청

B. 기존 연결에서 해결할 수 없다면 Read + Train을 명시적으로 요구하는 별도 Training MCP 연결

C. ChatGPT 클라이언트 제한으로 모두 불가능하다면, 실제 원인과 대안을 명확히 보고

다음 우회 방법은 채택하지 않는다.

* 요청하지 않은 runningai:train을 모든 Grant에 자동 추가
* Read Scope를 Train Scope처럼 해석
* Gateway 권한 검사 우회
* Publish 권한 동시 활성화
* 기존 read-only 연결의 권한 자동 확대

STEP 4 — 테스트

변경이 필요한 경우 먼저 로컬 또는 격리 환경에서 검증한다.

* Read-only Grant는 Read만 허용
* Train Grant는 Read + Train 허용
* Publish 호출은 항상 거부
* 명시적 동의 없는 권한 확대 없음
* 기존 OAuth Read 연결 정상
* tools/list 12종 노출 가능
* 신규 Draft 생성은 아직 실행하지 않음

STEP 5 — 결과 보고 및 STOP

다음 내용을 보고한다.

1. ChatGPT가 Train Scope를 요청하지 않는 확정 원인 또는 관측 가능한 한계
2. 플러그인 도구 목록 갱신 필요 여부
3. 가장 작은 해결 방법
4. 수정이 필요한 정확한 파일
5. 기존 Read 연결에 미치는 영향
6. 운영 변경 및 사용자 재승인 필요 여부

이번 작업에서는 운영 Worker 배포, 플러그인 삭제·재설치, 운영 DB 변경, Intervals 게시를 실행하지 않는다.

검증과 필요한 최소 로컬 수정까지 수행하고 STOP한다.

장기 아키텍처는 Modular Monolith + Single-Tenant Self-hosted를 유지한다.
