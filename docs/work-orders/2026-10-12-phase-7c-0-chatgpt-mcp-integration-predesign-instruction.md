# WO-RUNNINGAI-PHASE-7C-0 — ChatGPT ↔ RunningAI 직접 연동 사전 설계 및 기술 검증 (작업 지시서 원문)

최종 사용 목표는 ChatGPT에서 아래처럼 요청하는 것이다.

- "최근 러닝 분석해줘"
- "오늘 훈련 추천해줘"
- "이번 주 훈련 계획 짜줘"
- "지난달보다 러닝 실력이 좋아졌는지 분석해줘"

기존 RunningAI 아키텍처와 MCP 서버를 먼저 조사하고, 실제 ChatGPT에서 이용 가능한 연결
방식을 검증해 줘.

특히 다음 사항을 조사해야 한다.

1. 기존 Spring AI MCP 서버와 현재 publish_workout 도구 구조.
2. ChatGPT에서 RunningAI MCP 도구를 실제 호출하기 위한 연결 조건과 제약.
3. 최신 Garmin 활동, 분석 결과, 러닝 다이내믹스, 젖산역치, Intervals.icu, 회복 지표를
   읽기 전용으로 조회하는 API/MCP 설계.
4. 읽기 전용 분석 도구와 훈련 생성·승인·게시 도구의 권한 분리.
5. OAuth 등 인증, HTTPS, 접근 범위 제한, 비밀정보 관리.
6. 기존 Tailscale Funnel/External Relay를 재사용할 수 있는지와 외부 공개 위험.
7. ChatGPT 연결 후 실제 요청→RunningAI 응답까지 검증할 단계별 구현 계획.
8. Garmin 젖산역치 출처/변경 이력 미구현 사항의 별도 후속 처리 계획.

주의사항:

- 현재 MCP 또는 운영 API를 인증 없이 인터넷에 노출하지 않는다.
- 기존 서비스, 워치페이스, Relay를 중단하거나 변경하지 않는다.
- 운영 DB 변경 및 Flyway 적용 금지.
- origin/main 병합 및 운영 배포 금지.
- 이번 7C-0은 기술 가능성 확인과 실행 가능한 설계·작업지시서 작성까지 진행한다.
- 실제 구현에 들어가기 전에 필요한 연결 방식과 안전 조건을 명확히 보고한다.

설계 결과를 docs/work-orders에 남기고 STOP한다.
