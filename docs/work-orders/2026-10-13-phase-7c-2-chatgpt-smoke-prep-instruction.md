# WO — Phase 7C-2 ChatGPT 통합 스모크 테스트 준비 (지시 원문)

Phase 7B-4 개발은 계속 진행한다.

별도로 Phase 7C-2의 ChatGPT 통합 스모크 테스트를 먼저 준비해 줘.

목표:
ChatGPT에서 ping_running_ai만이 아니라 실제 구현된 조회 도구 7종을 발견하고 호출할 수 있는지 확인한다.

조건:
1. 7B-4 작업 중인 main-3 파일과 빌드 결과를 건드리지 않는다.
2. 필요하면 기존 검증 완료 커밋 기준 별도 worktree를 사용한다.
3. 독립된 개발 서버와 테스트용 데이터만 사용한다.
4. 개인 Garmin/회복 데이터는 인증 없는 외부 주소에 노출하지 않는다.
5. OAuth가 없는 현재 단계에서는 비식별 합성 데이터만 ChatGPT에 제공한다.
6. 기존 MCP Gateway와 읽기 전용 도구 구조를 최대한 재사용한다.
7. Cloudflare Quick Tunnel로 표준 HTTPS 443 연결을 준비한다.
8. 읽기 도구 7개 발견 및 개별 호출, 입력 검증, 오류 응답, publish_workout 차단을 확인한다.
9. 운영 Spring/DB/Relay/Funnel은 변경하지 않는다.
10. 기존 Gradle 테스트 및 Claude Stop Hook과 중복 실행하지 않는다.

서버 및 터널 준비가 완료되면 임시 MCP URL을 보고하고 STOP한다. ChatGPT 실제 호출은 사용자와 함께 진행한다.

실제 개인 데이터 연동은 OAuth 인증 완료 이후 별도 승인으로 진행한다.
