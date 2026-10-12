# WO-RUNNINGAI-PHASE-7C-2a — RunningAI 읽기 전용 MCP 도구 7종 구현 (작업 지시서 원문)

## 목표

ChatGPT에서 실제 RunningAI 분석 결과를 조회할 수 있도록 Spring AI MCP 서버에 읽기 전용
도구 7종을 구현한다.

이번 Phase는 개발 및 테스트까지만 진행한다. 외부 공개·OAuth 구축·운영 배포는 포함하지 않는다.

## STEP 1 — 기존 코드 조사

Phase 7B-3까지 구현된 서비스를 조사한다.

- Activity 및 ActivityDetail
- RunningActivityAnalysisService
- RunningDynamicsCalculator
- TrainingContextV2Builder
- Garmin Recovery
- Intervals.icu Fitness
- Athlete Profile 및 Garmin Lactate Threshold
- Injury Safety Gate

기존 서비스와 DTO를 최대한 재사용한다. 새로운 분석 공식을 중복 구현하지 않는다.

## STEP 2 — 읽기 전용 MCP 도구 구현

다음 7개 도구를 구현한다.

| Tool | 기능 |
|---|---|
| get_recent_activities | 최근 러닝 기록 목록 |
| get_activity_analysis | 활동별 심층 분석, 러닝 다이내믹스 |
| get_training_context | AI Coach용 TrainingContextV2 |
| get_recovery_context | Garmin 회복 상태 |
| get_fitness_trend | 기간별 체력·훈련부하 변화 |
| get_workout_recommendation | 저장 없는 훈련 추천 |
| get_athlete_profile | 신체·심박존·젖산역치 및 출처 |

모든 도구의 입력/출력 JSON Schema, 자료 단위, 기간, 결측값 표현, 오류 코드를 명시한다.

응답은 요약된 실측 근거를 중심으로 구성하고 대량 1Hz 원본 샘플은 전달하지 않는다.

## STEP 3 — 읽기 전용 보장

`running-ai.mcp.read-enabled`를 독립 설정으로 추가하고 기본값은 false로 유지한다.

읽기 전용 모드에서 반드시 보장할 사항:

- tools/list에 조회 도구 7개만 노출
- publish_workout을 강제 호출해도 실행 불가
- MCP 조회가 DB 데이터 변경을 유발하지 않음
- Garmin 및 Intervals.icu 쓰기 요청 0회
- Draft 생성·승인·게시 0회
- 외부 데이터 동기화 및 분석 재처리 자동 트리거 없음
- Scheduler 설정 변경 없음

기존 쓰기 도구와 읽기 도구의 등록 조건을 명시적으로 분리한다.

읽기 전용 도구의 쓰기 의존성을 탐지하는 아키텍처 테스트를 추가한다.

## STEP 4 — 분석 품질과 안전성

1. 러닝 다이내믹스: 보폭/수직진폭/수직비율/지면접촉시간/파워, 전후반 변화율과 동일 페이스
   비교, 결측 시 추정값 생성 금지
2. 젖산역치: Garmin 최신 LTHR·페이스·기준일 제공, sourceTracked=false 경고 유지, 프로필
   임의 수정·Garmin 재동기화 금지
3. 회복과 부상: 수집 시각·유효성 표시, 무릎 통증 ACTIVE/UNKNOWN/오래됨이면 고강도 훈련
   비권장, Injury Safety Gate 의미 약화 금지
4. 훈련 추천: 저장하지 않는 결정론적 추천만, AI Coach 처방으로 오인 표현 금지, 근거·제약·
   이유 반환, 기존 부상 안전 규칙 적용

## STEP 5 — 테스트

조회 도구 7종 정상 응답 / 입력 검증·오류 처리 / 실측 다이내믹스 매핑 / 젖산역치 provenance
경고 / 회복 미수집·만료·결측 / 부상 상태별 추천 제한 / 쓰기 도구 미노출·직접 호출 거부 /
DB·외부 서비스 무변경 / V1·V2 및 7B-2 게이트 회귀. Gradle 중복 실행 금지.

## STEP 6 — 운영 보호

Canonical main·origin push·운영 Spring 재시작·운영 PostgreSQL/Flyway·Relay/Watchface/Funnel
변경 금지. 외부 MCP 공개 금지. 인증 정보 코드·로그 금지.

## STEP 7 — 7C-2b 선행 설계 점검

1. Auth0 OAuth 클라이언트 등록 방식(CIMD/DCR/사전 등록) 선택
2. PKCE 및 resource/audience 호환성
3. 보호 리소스 메타데이터와 WWW-Authenticate 동작
4. Funnel /mcp 및 /.well-known 경로 충돌 가능성
5. JWT issuer/audience/exp/sub/scope 검증
6. 실제 ChatGPT OAuth 로그인 테스트 절차
7. Auth0 Free 플랜 제약 및 예상 비용

이번 Phase에서 실제 Auth0 테넌트 생성이나 Funnel 운영 설정 변경은 하지 않는다.

## 완료 조건

7개 도구 구현 / 전체 회귀 PASS / publish_workout 접근 불가 검증 / 운영·외부 무변경 /
문서·테스트 기록 / main-3 커밋. 완료 후 7C-2b 착수 판단 자료 보고 후 STOP.
