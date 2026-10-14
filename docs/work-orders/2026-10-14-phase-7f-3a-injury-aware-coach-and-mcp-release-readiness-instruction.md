# Phase 7F-3A — 사용자 지시 (원문 그대로)

> 아래는 작업 지시를 받은 그대로 보존한 것이다. 편집·요약하지 않는다.

---

RunningAI Phase 7F-3A — Injury-aware AI Coach & ChatGPT MCP Release Readiness

역할

RunningAI 시니어 백엔드 아키텍트 / 개발 리드 / QA.

목표

Phase 7F-2F의 실제 Intervals.icu 게시 성공을 기준으로 다음 두 가지를 수행한다.

1. AI Coach가 최신 Injury Gate 결과뿐 아니라 최근 부상 및 회복 이력을 인지하도록 최소한으로 개선한다.
2. ChatGPT MCP Train/Publish 기능을 안전하게 운영 활성화하기 위한 검증과 배포 계획을 완성한다.

기존 도메인과 배포 구조를 재설계하지 않는다.

1. 기준 상태

* 개발 브랜치: main-3
* HEAD: 30113c2
* 운영 canonical main은 별도 상태
* 운영 Spring: 18080
* 운영 Gateway: 17910
* 개발 DB: running_ai_7f2
* 운영 DB: running_ai
* Phase 7F-2F: PASS
* Intervals Event: 144442046
* 게시 날짜: 2026-10-16
* 실제 CREATED 1건, 외부 Readback 완료
* 중복 게시 0건
* 운영 OAuth Train/Publish 비활성화

현재 운영 DB에는 Injury Report가 0건이고, 개발 DB에만 최신 RESOLVED 자기 신고가 있다.

개발 DB의 승인·게시 기록을 운영 DB에 임의로 복사하지 않는다.

2. AI Coach 부상 이력 전달 개선

현재 문제:

Injury Gate가 RESOLVED이면 constraintText가 null이 되고, AI Coach의 TrainingContext에도 부상 이력이 전달되지 않는다.

이에 따라 AI Coach는 최근 무릎 외측 통증 및 러닝 복귀 상태를 모른 채 훈련을 생성한다.

다음 원칙으로 최소 수정한다.

* 기존 Injury Gate는 그대로 유지
* 최근 Injury Report의 요약 정보를 AI Coach 입력에 전달
* ACTIVE / UNKNOWN / RESOLVED 구분
* 보고 시각과 경과 시간 포함
* 부위, 통증, 보행 통증, 확인된 회복 정보 포함
* 미보고 사항은 UNKNOWN으로 표현
* 과거 통증 이력을 RESOLVED라고 삭제하지 않음
* 사용자의 의료적 완치 판정 생성 금지
* 민감한 원문 notes를 무조건 통째로 프롬프트에 포함하지 않음
* 프롬프트 길이 제한 및 안전한 구조화 적용

Coach에게 명확하게 전달한다.

"RESOLVED는 최신 자기 보고이며, 최근 부상으로부터의 복귀 훈련임을 고려해야 한다. 이는 고강도 운동을 자동 허용하는 의학적 판정이 아니다."

훈련 생성 시 Injury Gate와 Coach 결과를 모두 존중한다.

3. 회귀 테스트

다음 상태별 테스트를 추가한다.

1. Injury Report 없음
2. ACTIVE
3. UNKNOWN
4. RESOLVED
5. STALE
6. 최근 ACTIVE 이후 RESOLVED
7. 미보고 항목 null
8. Injury Gate 차단 시 Coach가 제한을 우회하지 못함
9. 기존 정상 TrainingContext 회귀 없음
10. REST 판단 및 Draft 게시 흐름 회귀 없음

실제 Claude CLI를 매 테스트마다 호출하지 않는다. 자동화 테스트는 기존 테스트 구조의 Fake/Mock을 활용하고, 필요한 경우 실제 Claude CLI 통합 검증을 별도로 1회 수행한다.

기존 전체 서버 테스트와 Gateway 테스트를 실행한다.

4. Intervals 실게시 결과 고정

Phase 7F-2F의 검증 결과를 회귀 기준으로 문서화한다.

* 계정 i6\*\*\*48
* 날짜 2026-10-16
* Marker runningai:workout:v1:0:2026-10-16
* Event ID 144442046
* 개발 DB Draft 21 / Approval 5 / Publication 4
* 실제 외부 CREATED 1건
* Readback PASS
* 멱등성 PASS

이 날짜는 이미 실제 Intervals에 게시된 날짜다.

운영 DB에는 해당 게시 기록이 없으므로, 운영 전환 과정에서 2026-10-16에 대한 신규 게시 또는 기존 이벤트 takeover를 자동 실행하지 않도록 보호한다.

개발 DB 데이터를 운영 DB로 임의 마이그레이션하거나 SQL로 게시 완료 상태를 만들어서는 안 된다.

INTERVALS_ATHLETE_ID="0"을 변경하지 않는다.

5. Garmin 전달 상태 확인

읽기 전용으로 다음을 확인한다.

* Intervals 이벤트 144442046 존재
* Garmin 연동 설정 상태
* Garmin Connect에서 해당 예정 훈련 확인 가능 여부
* FR265 표시 여부는 사용자가 직접 확인해야 하면 안내

Intervals 업로드 시각만으로 Garmin 전달 성공이라고 판정하지 않는다.

Garmin에서 확인되지 않더라도 Intervals 실게시 PASS 결과는 유지한다.

6. ChatGPT MCP 운영 활성화 준비

기존 7F-1에서 구현한 MCP 도구 6종과 OAuth 정책을 재검증한다.

Scopes:

* runningai:read
* runningai:train
* runningai:publish

필수 확인:

* 기존 Read 7종 회귀 없음
* Train/Publish 범위별 도구 접근 제어
* OAuth Worker 지원 Scope
* Gateway allowlist 및 fail-closed 정책
* 승인 전 게시 거부
* 운영 Injury Report 미등록 시 게시 차단
* 운영 AI Coach Claude CLI 경로
* 운영 Spring released JAR 버전
* main-3와 canonical main의 Git 정합성
* 배포·재시작·롤백 순서
* ChatGPT 커넥터 재승인 절차

주의:

운영 Spring의 Injury Report 0건을 개발 DB 기록으로 조용히 대체하지 않는다.

향후 운영 상태 기록이 필요하면 사용자 자기 신고에 따라 정상 API로 수행할 별도 승인 절차를 준비한다.

7. Self-hosted 아키텍처 유지

확정한 아키텍처:

Modular Monolith + Single-Tenant Self-hosted

* 인스턴스별 독립 PostgreSQL
* 인스턴스별 Garmin/Intervals 연결
* 외부 계정 ID와 키 하드코딩 금지
* Athlete 도메인 유지
* 기존 서비스 재사용
* 마이크로서비스 전환 금지

본 Phase에서 설치형 패키징이나 다중 사용자 기능까지 구현하지 않는다.

8. 실행 범위

허용:

* main-3에서 최소 코드 개선
* 부상 이력 전달 관련 테스트
* 기존 전체 테스트
* 읽기 전용 Intervals/Garmin 확인
* 운영 활성화 절차 문서화
* 정상 커밋

금지:

* 추가 Intervals 게시
* 기존 Intervals 이벤트 수정·삭제
* 운영 DB 변경
* 운영 Injury Report 자동 등록
* 운영 Spring/Gateway 재시작
* OAuth Worker 배포
* OAuth Scope 확대
* 운영 MCP 쓰기 기능 활성화
* canonical main 병합
* origin push
* Watchface/Relay 변경
* 레거시 저장소 변경

9. 완료 보고

1. AI Coach 부상 이력 전달 방식
2. 기존 Injury Gate 불변 여부
3. 상태별 테스트 결과
4. 전체 테스트 결과
5. Garmin 전달 확인 결과
6. 운영 MCP 활성화 준비 상태
7. 운영 DB의 남은 차단 조건
8. 변경 파일 및 커밋
9. 다음 Phase 7F-3B 운영 전환 실행 순서

작업 완료 후 STOP한다.

이번 단계는 새로운 실게시가 아니라 ChatGPT에서 안전하게 실제 훈련을 생성하기 위한 마지막 개선 및 운영 준비 작업이다.

불필요한 리팩터링과 추가 조사 Phase를 만들지 않는다.
