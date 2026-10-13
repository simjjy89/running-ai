# RunningAI Phase 7F-1 — ChatGPT Workout Draft MCP Integration

> The owner's instruction, kept verbatim.

## 역할

RunningAI 시니어 백엔드 아키텍트 / 개발 리드 / 보안 설계자 / QA.

## 현재 기준

- Canonical main / main-3: 799e3b5
- 운영 PostgreSQL: V26
- 운영 활동: 48건
- ChatGPT OAuth MCP 실데이터 Read 7종 검증 완료
- 운영 빌드·재시작 경로 안정화 완료
- 기존 훈련 Draft/Revision/Approval/Publish 서비스 구현
- 원격 push 없음
- Self-hosted Single-Tenant Modular Monolith 아키텍처 확정

## 목표

기존 RunningAI 훈련 생성·수정·승인·게시 서비스를 ChatGPT MCP에 연결한다.

**기존 도메인 로직을 재사용하고, 운영 실게시 이전까지 안전하게 검증한다.**

불필요한 재설계나 대규모 리팩터링은 금지한다.

## STEP 1 — 기존 기능 점검

다음 API와 서비스의 실제 동작 및 입력·출력 계약을 확인한다.

- POST /api/v1/workout-drafts
- GET /api/v1/workout-drafts/{id}
- POST /api/v1/workout-drafts/{id}/revisions
- POST /api/v1/workout-drafts/{id}/approve
- GET /api/v1/workout-drafts/{id}/publish-preview
- POST /api/v1/workout-drafts/{id}/publish

기존 Injury Gate, PublishingModeGuard, 승인 불변성, 멱등성 규칙을 유지한다.

API와 서비스가 이미 제공하는 기능을 MCP에서 중복 구현하지 않는다.

## STEP 2 — MCP 도구 6종 구현

다음 도구를 추가한다.

1. create_workout_draft
2. get_workout_draft
3. revise_workout_draft
4. approve_workout_draft
5. preview_draft_publish
6. publish_approved_draft

각 도구는 기존 서비스 계층을 호출하는 얇은 어댑터로 구현한다.

요구사항:

- 명확한 입력 스키마 및 검증
- 구조화된 결과 반환
- 날짜·시간대 Asia/Seoul 처리
- 기존 도메인 예외의 안전한 MCP 오류 변환
- 민감정보 노출 금지
- 호출 추적을 위한 correlation ID
- 재시도 시 기존 멱등성 보장 유지

Draft 생성 시 로컬 Claude CLI의 실행 가능 여부를 점검하고, 실패하면 원인을 명확히 반환한다.

CLI가 없거나 AI 응답이 유효하지 않을 때 임의의 훈련을 성공한 것처럼 생성하지 않는다.

## STEP 3 — OAuth Scope와 Tool 권한 분리

현재 Gateway는 단일 scope/allowlist 구조다.

다음과 같이 확장한다.

- runningai:read — 기존 Read Tool 7종
- runningai:train — Draft 생성·조회·수정·승인·게시 미리보기
- runningai:publish — 승인된 Draft 실제 게시

읽기 권한만으로 훈련 생성·수정·승인·게시를 실행할 수 없어야 한다.

권한 검사에는 인증된 subject와 OAuth scope를 사용하고, 도구 이름별 서버 측 정책을 적용한다.

기존 OAuth 연결이 요청하지 않은 권한을 자동 획득하지 않도록 한다.

Gateway의 기존 read-only fail-closed 동작은 유지하되, 명시적으로 허용된 새 도구만 제한적으로 지원한다.

기존 publish_workout 도구를 우회적으로 재사용하지 않는다.

인증 실패, 권한 부족, 허용되지 않은 subject, 미등록 도구는 모두 거부한다.

## STEP 4 — 안전한 게시 워크플로

게시 흐름을 다음과 같이 강제한다.

Draft 생성
→ Draft 조회
→ 필요 시 Revision
→ 명시적 Approve
→ Publish Preview
→ Publish

필수 검증:

- 승인되지 않은 Draft 게시 불가
- 승인 이후 Draft 불변
- 승인 당시 내용과 게시 내용 일치
- 게시 대상 Intervals 계정·날짜·훈련 내용 검증
- 동일 Draft 중복 게시 방지
- 동시 게시 요청에 대한 단일 비행 보장
- 게시 결과가 불확실할 때 무조건 재게시하지 않음
- Injury Gate 차단 상태에서 안전 규칙 우회 불가

MCP 호출 자체가 사용자 승인으로 간주되어서는 안 된다.

특히 ChatGPT가 훈련을 제안했다는 이유만으로 자동 승인·게시하지 않는다.

## STEP 5 — Self-hosted 아키텍처 준수

이번 Phase에서 다중 사용자 SaaS 기능은 구현하지 않는다.

다만 다음 원칙을 지킨다.

- 인스턴스별 독립 설정
- 외부 Intervals 계정 ID 하드코딩 금지
- 전역 공용 계정에 의존하는 신규 로직 금지
- 향후 계정별 Credential Provider 교체 가능
- Spring Boot 모듈형 모놀리스 유지
- 기존 Athlete 도메인 유지

현재 개인 운영 인스턴스의 설정과 데이터는 변경하지 않는다.

## STEP 6 — 테스트

기존 전체 테스트와 신규 MCP 테스트를 실행한다.

반드시 검증할 항목:

1. Read Tool 7종 회귀 없음
2. 신규 MCP 6종 정상 등록
3. Scope별 허용/거부
4. 무토큰·위조 토큰·권한 부족 거부
5. Draft 생성 및 수정
6. 승인 전 게시 거부
7. 승인 후 내용 불변
8. Injury Gate 차단
9. 중복 게시 방지
10. 동시 요청 처리
11. Intervals API 실패 및 타임아웃
12. Claude CLI 실패
13. Gateway 재시작 및 기존 OAuth 호환성

실제 외부 게시 테스트는 Mock/Fake Provider를 사용한다.

## STEP 7 — 운영 적용 정책

이번 작업에서는 다음을 허용한다.

- main-3 코드 개발
- 신규 MCP 도구 및 Gateway 권한 정책 구현
- 자동화 테스트
- 별도 테스트 환경 E2E 검증
- 문서화 및 커밋

다음은 별도 승인 전까지 금지한다.

- 실제 Intervals.icu 캘린더에 훈련 게시
- 운영 OAuth 권한 확대
- 운영 Gateway 쓰기 도구 공개
- 운영 DB 스키마 변경
- 운영 Spring/Gateway 무단 재시작
- Canonical main 병합 및 origin push
- 기존 Watchface·Relay 변경

## STEP 8 — 완료 보고

다음을 보고한다.

1. 구현된 MCP 도구 6종
2. OAuth Scope 및 Tool 매핑
3. 기존 서비스 재사용 여부
4. 테스트 결과
5. 실제 게시 전 검증 필요 항목
6. 변경 파일 및 커밋
7. 운영 적용 시 예상 서비스 영향
8. Phase 7F-2 실행 절차

## 완료 기준

- MCP 6종 구현
- Read 7종 정상 유지
- 권한 분리 테스트 PASS
- Draft → Revision → Approval → Preview → Mock Publish E2E PASS
- 실제 Intervals 게시 0건
- 운영 데이터 및 기존 서비스 무변경
- Git 작업트리 Clean

완료 후 결과를 보고하고 STOP한다.

**이번 Phase는 실게시 직전까지 구현·검증하는 작업이다. 추가 조사 Phase를 만들지 말고 기존 서비스를 최대한 재사용하여 빠르게 완료한다.**
