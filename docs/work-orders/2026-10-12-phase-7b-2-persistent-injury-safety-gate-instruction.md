# WO-RUNNINGAI-PHASE-7B-2 — Persistent Injury State & Workout Safety Gate (작업 지시서 원문)

## 1. 목표

RunningAI가 사용자의 통증 상태를 지속적으로 기억하고, 통증 정보가 누락되거나 오래된
상황에서 부적절한 러닝 훈련을 생성·승인·게시하지 않도록 한다.

AI의 판단을 대체하는 것이 아니라, AI가 반드시 지켜야 하는 안전 제약을 서버에서 보장하는
것이 목적이다.

Phase 7B-1에서 정상 생성된 Draft #19는 그대로 보존한다.

## 2. 작업 기준

* Canonical main: 370ee8f779f0a8bb810835249f20e77d5d40c77b
* 개발 Worktree: main-3
* 마지막 확인된 main-3 HEAD: 24ff0b5304c34b1659b594772413705933dab477
* Spring Boot 3.5.16 / Java 21 / PostgreSQL 17 / Flyway
* 운영 서비스 재시작 금지
* origin/main 변경 금지

작업 시작 시 실제 Git 상태를 재확인한다. Phase 7B-1의 로컬 결과 문서나 다른 미추적
파일을 임의로 삭제·덮어쓰지 않는다.

## STEP A — 기존 안전 경로 분석

먼저 다음 경로를 조사한다.

1. WorkoutDraftService.generate
2. WorkoutDraftService.revise
3. WorkoutDraftApprovalService.approve
4. ApprovedWorkoutDraftPublishService.preview/publish
5. 기존 deterministic Workout Publish 및 Scheduler
6. MCP를 통한 훈련 게시 경로
7. running-ai-coach.ps1
8. TrainingContextV2Builder

현재 painOrFatigueFeedback이 어느 경로에서 전달되고 어느 경로에서 누락되는지 확인한다.

안전 게이트를 컨트롤러에만 구현하지 않는다. CLI, HTTP, Scheduler 등 모든 진입점에서
공통 정책을 사용하도록 설계한다.

## STEP B — 통증 상태 저장 모델

기본 athlete에 연결되는 통증 상태 모델을 설계한다.

최소한 다음 정보를 관리한다.

* Athlete ID
* 통증 부위 및 좌우 구분
* 통증 존재 여부
* 통증 강도(0~10)
* 보행 시 통증
* 계단 통증
* 붓기 및 움직임 제한
* 사용자 보고 시각
* 마지막 확인 시각
* ACTIVE / RESOLVED / UNKNOWN 상태
* 사용자 자유 입력 피드백(선택)

통증은 사용자가 보고한 증상이며, 의학적 진단으로 저장하거나 해석하지 않는다.

Flyway 마이그레이션은 현재 마지막 버전을 확인한 뒤 다음 사용 가능한 번호로 작성한다.

기존 activity 및 AI Coach 데이터는 변경하지 않는다.

통증 이력을 덮어써서 잃지 않도록 변경 기록을 보존한다.

## STEP C — 안전 정책

서버에서 공통으로 사용할 WorkoutSafetyPolicy에 해당하는 정책을 구현한다.

기본 규칙:

1. ACTIVE + 보행 통증: 러닝·인터벌·장거리 훈련 금지.
2. 현재 상태 UNKNOWN 또는 오래됨: 안전 상태를 임의로 정상으로 간주하지 않고 러닝 훈련
   생성 차단.
3. ACTIVE 통증이 있으나 보행 통증 없음: 증상 및 최근 확인 정보를 고려해 제한 여부 판단.
   자동으로 정상 상태로 전환하지 않음.
4. 명시적으로 RESOLVED 확인: 정상적인 훈련 생성 검토 가능. 이것만으로 의료적 안전이
   보장되는 것은 아님.
5. 통증 피드백이 생략됐다는 이유로 기존 ACTIVE 상태를 해제하지 않음.

안전 상태의 신선도 기준은 설정 가능하게 하고, 기본 기준을 명시적으로 문서화한다.

ACTIVE 상태는 시간이 지났다는 이유만으로 자동 RESOLVED 처리하지 않는다.

차단 시 구체적인 오류 코드와 필요한 확인 정보를 반환한다. 증상의 원인을 진단하지 않는다.

## STEP D — AI Coach와 통합

저장된 통증 정보를 기존 SessionConstraints 및 Coach Context 구성에 반영한다.

우선순위:

1. 구조화된 최신 통증 상태
2. 이번 요청의 명시적 통증 피드백
3. 그 밖의 목표 및 훈련 요구

자유 텍스트가 기존 ACTIVE 상태를 임의로 해제할 수 없어야 한다.

REST만 허용되는 상태에서는 AI에게 해당 제약을 명시하고, 생성 결과가 실제로 REST인지
서버에서 검증한다.

AI가 EASY, QUALITY, LONG 등 금지된 훈련을 반환하면 저장하지 않고 차단한다.

새로운 REST 훈련을 서버가 임의로 만들어 AI의 결과인 것처럼 표시하지 않는다.

AI 호출 중 안전 상태가 변경될 수 있으므로, 저장 직전 상태를 재검증한다.

## STEP E — 승인·게시 안전성

Draft 생성 시뿐 아니라 다음 단계에서도 현재 안전 상태를 검증한다.

* Draft 수정
* Draft 승인
* Publish Preview
* 실제 Publish 직전

안전 상태가 나빠진 뒤 이전에 생성한 러닝 Draft를 승인하거나 게시하는 경우에도
차단해야 한다.

기존 독립적인 Workout Publish/Scheduler/MCP 경로 역시 안전 정책을 우회하지 못하도록
확인한다.

승인과 외부 게시의 기존 명시적 사용자 확인 절차는 그대로 유지한다.

RUNNING_AI_DRAFT_PUBLISHING_ENABLED 등 기존 안전 스위치는 변경하지 않는다.

## STEP F — 회귀 테스트

실제 운영 DB 대신 격리된 테스트 DB와 Fixture로 검증한다.

필수 테스트:

1. 통증 상태 저장 및 조회.
2. ACTIVE 보행 통증에서 러닝 Draft 차단.
3. ACTIVE 상태에서 허용된 REST Draft만 저장 가능.
4. 통증 정보 누락 시 기존 ACTIVE 상태 유지.
5. 오래된 통증 상태를 정상으로 오판하지 않음.
6. RESOLVED 변경 시 명시적인 사용자 확인 필요.
7. 기존에 생성된 Draft 승인 전 안전성 재검증.
8. 기존 Approved Draft의 Publish 안전성 재검증.
9. CLI와 직접 HTTP 호출이 같은 정책 적용.
10. AI가 안전 제약을 무시하는 응답을 반환하면 Fail Closed.
11. AI 호출과 저장 사이 통증 상태 변경에 대한 경쟁 조건 검증.
12. 기존 Draft #19 및 기타 기존 Draft API 호환성 검증.

기존 전체 관련 회귀 테스트도 실행한다.

## STEP G — 운영 분리

이번 Phase에서는 개발 Worktree에만 구현한다.

실제 운영 PostgreSQL에는 마이그레이션을 적용하지 않는다.

운영 Spring, Connector, Relay를 재시작하지 않는다.

운영 Draft #19는 변경하지 않는다.

실제 훈련 생성·승인·게시를 실행하지 않는다.

테스트에는 사용자의 통증 정보와 유사한 합성 Fixture만 사용한다.

새 통증 정보가 로그나 외부 공개 API에 불필요하게 노출되지 않도록 검증한다.

## STEP H — 결과 보고

다음을 보고한다.

1. 기존 안전성 우회 경로.
2. 통증 상태 모델.
3. 저장 및 이력 관리 방식.
4. 안전 정책 판정표.
5. AI Coach Context 연동.
6. Draft/Approval/Publish 게이트 적용 결과.
7. 테스트 결과.
8. 기존 API 호환성.
9. 운영 무변경 결과.
10. 남은 개선 과제.
11. 다음 단계 GO/NO-GO.

코드와 테스트는 main-3에 커밋한다.

origin/main Push, Canonical 병합 및 운영 배포는 별도 승인 대상이다.

## 완료 기준

사용자가 통증 정보를 매번 직접 반복 입력하지 않더라도 RunningAI가 마지막으로 확인된
통증 상태를 알고 있어야 한다.

통증이 남아 있거나 상태가 불명확한 경우, 안전 정책에 어긋나는 러닝 훈련의
생성·승인·게시가 모든 지원 경로에서 차단되어야 한다.

단순히 AI가 REST를 선택했다는 것만으로 PASS 처리하지 않는다. 서버의 독립적인 차단을
테스트로 증명해야 한다.

완료 후 STOP한다.
