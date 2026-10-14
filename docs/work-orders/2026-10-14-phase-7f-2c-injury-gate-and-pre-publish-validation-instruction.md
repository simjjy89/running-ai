# Phase 7F-2C — owner instruction (verbatim)

RunningAI Phase 7F-2C — Injury Gate 해소 및 첫 실게시 직전 검증

역할

RunningAI 시니어 백엔드 아키텍트 / 개발 리드 / QA.

현재 기준

* main-3 HEAD: 30113c2
* Phase 7F-2A / 7F-2B 준비 완료
* 개발 Spring: 127.0.0.1:18083
* 개발 PostgreSQL: running_ai_7f2
* 운영 Spring: 18080
* 운영 PostgreSQL: running_ai
* Intervals Athlete ID: "0" 유지
* 레거시 자동 게시 작업 비활성 확인
* 2026-10-15 캘린더: 이전 확인 시 WORKOUT 0건
* 실제 Intervals 게시: 미실행

STEP 1. 실제 Injury Report 기록

사용자가 2026-10-14 직접 보고한 상태:

* 현재 무릎 통증 없음
* 보행 시 무릎 통증 없음
* 2026-10-11 하프마라톤 후 무릎 바깥쪽 통증이 있었음
* 당시 보행 시에도 통증이 있었음
* 현재 러닝 중 통증 여부는 미확인
* 러닝 후 통증 재발 여부도 미확인

위 정보를 기존 Injury Report API/도메인 스키마에 맞춰 개발 DB에 기록한다.

조건:

1. 사용자 자기 보고 사실만 기록한다.
2. 과거 통증 이력을 삭제하지 않는다.
3. 임의의 의학적 완치 판정은 생성하지 않는다.
4. 확인되지 않은 통증 항목을 0으로 채우지 않는다.
5. 러닝 제한을 강제로 해제하지 않는다.
6. 사용자가 보고한 날짜와 실제 기록 시각을 구분한다.
7. 기존 Injury Gate의 모든 검증을 그대로 적용한다.

스키마상 정확한 상태를 표현할 수 없다면 허위 데이터로 통과시키지 말고 STOP한다.

STEP 2. Injury Gate 검증

개발 DB에 기록된 Injury Report를 대상으로 다음을 수행한다.

* injury-state 조회
* WorkoutSafetyGateService 실행
* 기록 유효기간 48시간 검증
* RECOVERY 훈련 허용 여부 확인
* REST 요구 여부 확인

운동 허용 판정은 기존 도메인 로직만 사용한다.

판정 결과와 근거를 보고한다.

STEP 3. AI Workout Draft 생성

대상 날짜: 2026-10-15

조건:

* availableMinutes: 30
* requestedGoal: recovery
* 최신 TrainingContext 활용
* 최신 Injury Report 반영
* 실제 로컬 Claude CLI 호출
* AI 응답 위조 및 테스트용 강제 결과 금지

코치가 REST를 반환하면 정상 결과로 인정하고, 실제 운동 게시 검증은 보류한다.

Non-REST 훈련이 생성되어도 Injury Gate가 차단한다면 STOP한다.

STEP 4. Draft → Approval → Preview

기존 API와 서비스를 사용한다.

1. Draft 생성
2. Draft 내용 확인
3. 필요 시 Revision
4. 최신 Draft Version 확인
5. Injury Gate 재검증
6. 개발 DB에서 Approval
7. Publish Preview 생성

Approval 전에 실제 Draft의 날짜·종목·강도·운동 구조·대상 Intervals 계정을 검토한다.

이번 Approval은 개발 환경에서의 검증용 내부 상태 변경으로만 허용한다. 외부 게시 승인은 아니다.

STEP 5. 캘린더 재조회

기존 preflight-intervals-date.ps1을 사용한다.

* 날짜: 2026-10-15
* INTERVALS_ATHLETE_ID: "0"
* 대상 API 키 소유 계정 확인
* 기존 이벤트 존재 여부 확인
* 예상 operation 확인
* 덮어쓰기 또는 충돌 발견 시 STOP

STEP 6. 외부 게시 직전 STOP

이번 작업에서는 실제 Intervals POST/PUT/PATCH/DELETE를 절대 실행하지 않는다.

다음 사항도 금지한다.

* 운영 DB 변경
* 운영 서비스 재시작
* 운영 OAuth Scope 변경
* Cloudflare Worker 배포
* MCP 쓰기 도구 운영 공개
* Injury Gate 우회
* 외부 훈련 자동 게시
* canonical main 병합
* origin push

개발 인스턴스가 게시 가능 설정으로 기동되어 있으므로, 준비 완료 후 더 이상 사용할 필요가 없다면 안전하게 종료한다.

STEP 7. 결과 보고

다음 항목을 간결히 보고한다.

1. 실제 저장한 Injury Report 필드와 값
2. Injury Gate 최종 결과
3. AI가 생성한 훈련 전체 내용
4. Draft ID / Version / Approval ID
5. Publish Preview 결과
6. 외부 Intervals 대상 계정
7. 해당 날짜 기존 일정 수
8. 예상 외부 변경 내용
9. 실게시 가능 여부 및 차단 요인
10. 변경 파일·커밋·서비스 상태

중요:

사용자의 명시적 실게시 승인 전까지 외부 쓰기 0건을 유지한다.

완료 후 STOP한다.
