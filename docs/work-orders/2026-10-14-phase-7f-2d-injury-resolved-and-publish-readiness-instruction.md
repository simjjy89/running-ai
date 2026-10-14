RunningAI Phase 7F-2D — Injury RESOLVED 기록 및 최초 Intervals 실게시 준비

0. 역할과 목표

RunningAI 시니어 백엔드 아키텍트 / 개발 리드 / QA로 작업한다.

목표: 사용자의 최신 부상 회복 자기 신고를 개발 DB에 정확하게 기록하고, 기존 Injury Gate를 재평가한 다음 실제 Claude CLI로 회복 훈련 Draft를 생성한다.

최초 Intervals.icu 실게시를 위한 사전 검증까지 진행하되, 실제 외부 게시 직전에 STOP한다.

이미 완료된 7F-2A/2B/2C를 반복하거나 새로운 조사 Phase를 만들지 않는다.

⸻

1. 현재 작업 기준

Git

* 개발 저장소: C:\Users\simjy\orca\workspaces\running-ai-github\main-3
* Canonical 저장소: C:\running-ai-github
* 레거시 저장소: C:\running-ai — 절대 수정·삭제·덮어쓰기 금지
* 개발 브랜치: main-3
* 기준 HEAD: 30113c2
* Canonical main 병합 금지
* origin push 금지

작업 시작 시 실제 HEAD와 working tree를 확인한다. 기준과 다르면 무작정 reset하지 말고 원인을 확인한다.

서버 및 데이터베이스

구분    운영    개발
Spring    127.0.0.1:18080    127.0.0.1:18083
PostgreSQL DB    running_ai    running_ai_7f2
Flyway    V26    V26
Draft Publishing    OFF    ON
Legacy Publishing    OFF    OFF
Garmin 자동 동기화    기존 운영 유지    OFF
Scheduler    기존 운영 유지    OFF

* 개발 Spring 18083은 현재 종료 상태
* INTERVALS_ATHLETE_ID는 미설정 상태를 유지하며 실효 값 "0"을 사용한다.
* 외부 대상 Intervals 계정은 기존 i6***48, 시간대 Asia/Seoul
* 개발 DB는 운영 데이터에서 분리된 복사본
* 운영 DB 및 운영 서비스 변경 금지

기존 Injury Report

개발 DB running_ai_7f2:

Report #1 — ACTIVE

* 2026-10-11 하프마라톤 이후 무릎 외측 통증
* 당시 보행 중에도 통증 있음

Report #2 — UNKNOWN

* 현재 통증 없음
* 보행 중 통증 없음
* 당시 러닝 중·후 상태가 아직 확인되지 않아 UNKNOWN으로 저장

기존 Report #1과 #2는 수정하거나 삭제하지 않는다.

기존 Draft

* 2026-10-15 REST Draft #20
* Version 1
* Approval #4
* SKIPPED_REST_DAY
* 해당 날짜의 개발 DB 승인 기록은 보존한다.
* 2026-10-15를 새로운 테스트 날짜로 사용하지 않는다.

⸻

2. 사용자 자기 신고 및 명시적 승인

사용자가 2026-10-14에 다음을 직접 보고했다.

1. 현재 무릎 바깥쪽 통증 없음
2. 보행 시 통증 없음
3. 하프마라톤 이후 가볍게 달려봄
4. 해당 러닝 중 통증 없음
5. 러닝 직후에도 통증 없음
6. 다음 날까지 통증 및 붓기 없음

그리고 다음을 명시적으로 승인했다.

현재 무릎 외측 통증이 해소되었다는 자기 신고로 RunningAI 개발 DB에 RESOLVED를 기록하는 것을 승인한다. 기존 이력을 보존하고 7F-2D 작업을 진행한다. 운영 DB나 Intervals 캘린더는 아직 변경하지 않는다.

이 RESOLVED 기록에 대해서는 추가 승인을 요청하지 않는다.

단, 이는 현재 증상이 해소되었다는 자기 신고이지 의학적 완치 진단이나 고강도 운동 허가가 아니다.

⸻

3. STEP 1 — 신규 Injury Report 기록

기존 Injury Report API, DTO, 도메인 모델 및 상태 전이 규칙을 확인한 다음, 개발 DB에만 신규 Report를 추가한다.

저장 원칙

* status = RESOLVED
* body_site = knee (lateral) — 기존 표현 방식과 일치
* side = UNSPECIFIED
* pain_present = false
* pain_on_walking = false
* swelling = false — 사용자가 붓기 없음을 직접 보고한 범위에서만 기록
* pain_intensity = null — 별도 수치 미보고
* pain_on_stairs = null — 미보고
* range_of_motion_limited = null — 미보고
* 그 밖의 미보고 필드는 null 또는 도메인에서 허용하는 미확인 값 유지

가벼운 러닝 중·후·다음 날 통증이 없었다는 사실은 기존 스키마에서 지원하는 범위에서만 표현한다.

없는 필드를 만들거나 다른 필드에 억지로 매핑하지 않는다.

시간 및 중복 처리

* reported_at은 실제 사용자 신고 시각에 근거한다.
* recorded_at은 실제 저장 시각을 사용한다.
* 시각을 임의로 소급하거나 미래로 설정하지 않는다.
* 기존 러닝의 정확한 날짜·거리·시간은 추정하지 않는다.
* 48시간 유효기간을 인위적으로 연장하지 않는다.
* 기존 이력을 훼손하지 않는다.
* 동일 자기 신고에 대한 RESOLVED 기록이 이미 존재하는지 먼저 확인한다.
* 중복 실행 시 동일 기록을 불필요하게 추가하지 않는다.

DB 검증

쓰기 전에 다음을 확인한다.

1. DB 이름이 정확히 running_ai_7f2인지
2. 운영 DB와 접속이 분리됐는지
3. 기존 Report #1과 #2가 보존돼 있는지
4. 기존 API를 통한 정상 저장 경로인지

저장 후 실제 신규 ID, 상태, 각 필드, 기록 시각을 조회해 보고한다.

운영 DB에는 어떠한 INSERT/UPDATE/DELETE도 실행하지 않는다.

⸻

4. STEP 2 — Injury Gate 재평가

신규 RESOLVED 기록을 저장한 뒤 기존 도메인 서비스를 실제로 실행한다.

검증 대상:

* 최신 Injury Report
* injury-state
* 48시간 Freshness
* WorkoutSafetyGateService
* WorkoutSafetyPolicy
* nonRestTrainingAllowed
* RECOVERY 허용 여부
* EASY 허용 여부
* QUALITY 관련 별도 안전 규칙

예상 결과를 강제하지 않는다.

RESOLVED 신고가 정상 반영되더라도 다른 안전 조건으로 차단된다면 기존 정책대로 중단한다.

Injury Gate 통과는 훈련을 무조건 수행해도 된다는 뜻이 아니다. AI 코치의 회복 판단을 별도로 적용한다.

⸻

5. STEP 3 — 개발 인스턴스 기동

기존 검증된 개발 실행 스크립트를 사용한다.

* 주소: 127.0.0.1:18083
* DB: running_ai_7f2
* Draft Publishing: ON
* Legacy Publishing: OFF
* Garmin 동기화: OFF
* Scheduler: OFF

개발 인스턴스가 운영 DB로 연결되지 않는지 먼저 확인한다.

실제 게시 기능이 활성화되어 있더라도 이번 단계에서는 게시 API를 호출하지 않는다.

운영 Spring 18080과 Gateway 17910은 재시작하지 않는다.

API 키 및 인증정보를 로그·보고서에 출력하지 않는다.

⸻

6. STEP 4 — 새로운 테스트 날짜 선정

2026-10-15는 기존 REST Draft 승인 기록이 있으므로 제외한다.

2026-10-16 이후의 미래 날짜 중 다음 조건을 만족하는 날짜 하나를 선정한다.

* 개발 DB에서 해당 날짜 승인 기록 없음
* 개발 DB의 게시 기록과 충돌 없음
* 실제 Intervals 캘린더에 해당 날짜 WORKOUT 이벤트 없음
* 기존 RunningAI Marker 또는 레거시 이벤트와 충돌 없음
* Asia/Seoul 기준 미래 날짜

기존 scripts/dev/preflight-intervals-date.ps1을 사용한다.

INTERVALS_ATHLETE_ID는 계속 "0"을 유지한다.

외부 API는 GET 등 읽기 전용 요청만 허용한다.

이전 조회 결과를 그대로 신뢰하지 말고 새로 확인한다.

날짜를 정할 수 없으면 결과를 보고하고 STOP한다.

⸻

7. STEP 5 — 실제 AI Workout Draft 생성

STEP 2에서 Injury Gate가 허용하는 경우에만 진행한다.

입력

* date: STEP 4에서 선정한 날짜
* availableMinutes: 30
* requestedGoal: recovery
* Athlete timezone: Asia/Seoul
* 최신 Injury Report
* 실제 RunningAI TrainingContext
* 실제 로컬 Claude CLI

필수 원칙

1. 실제 AI Coach를 사용한다.
2. 생성 결과를 위조하거나 Mock으로 대체하지 않는다.
3. 최신 회복 정보와 10월 11일 하프마라톤 부상 이력을 반영한다.
4. 코치에게 무조건 Non-REST를 생성하라고 강제하지 않는다.
5. REST가 반환되면 REST를 정상 판단으로 인정한다.
6. Injury Gate를 우회하지 않는다.
7. 테스트를 위해 강도를 높이지 않는다.
8. 고강도 인터벌·템포런·장거리 러닝을 요구하지 않는다.

Non-REST Draft 생성 시 다음을 보고한다.

* Draft ID / Version
* 훈련 날짜
* Workout Type
* 총 운동 시간
* 워밍업
* 본운동
* 쿨다운
* 각 구간의 시간·속도·페이스·심박 목표
* AI 추천 근거
* 안전 경고

REST라면 사유를 보고하고 실게시 준비를 보류한다.

⸻

8. STEP 6 — Draft 상세 검토 및 Preview 가능성 확인

새로운 Draft에 대한 사용자 승인은 아직 없다.

따라서 다음을 실행하지 않는다.

* approve_workout_draft
* POST /api/v1/workout-drafts/{id}/approve
* publish_approved_draft
* POST /api/v1/workout-drafts/{id}/publish

새 Draft의 상세 내용을 사용자에게 보고한다.

기존 서비스에서 승인 전 Preview 조회가 가능하다면 읽기 전용으로 수행한다.

승인 후에만 Preview가 가능하도록 설계된 경우에는 우회하지 않고, 승인 이후 단계로 남긴다.

승인 전 상태를 수정해서 Preview를 강제로 얻지 않는다.

⸻

9. STEP 7 — 외부 게시 영향 검증

다음 내용을 확인하여 실제 외부 게시 시 예상되는 변경을 정리한다.

대상

* Intervals.icu 계정: 기존 i6***48
* Athlete ID 설정: "0"
* Timezone: Asia/Seoul

게시 예정 내용

* 날짜
* 이벤트 이름
* Workout 유형
* Event Marker
* 전체 훈련 구조
* 기존 일정과 충돌 여부
* 예상 operation: CREATED / NO_CHANGE / UPDATED / CONFLICT

안전성

* 기존 캘린더 덮어쓰기 방지
* 승인 버전 불변성
* 날짜당 승인 1건 제약
* Draft 중복 게시 방지
* 동시 요청 방지
* 타임아웃 후 무조건 재POST 금지
* 게시 후 Readback 확인 절차
* Garmin 전달 확인 방법
* 외부 이벤트 복구 절차

이 항목은 기존 코드·테스트·읽기 전용 Preflight 결과를 근거로 확인한다.

실제 Intervals.icu 쓰기 요청은 한 번도 실행하지 않는다.

⸻

10. STEP 8 — 안전한 종료

이번 단계에서 허용된 변경:

* 개발 DB 신규 RESOLVED Injury Report 1건
* 개발 DB 신규 Workout Draft
* 개발 환경 검증에 필요한 일시적 프로세스 기동
* 읽기 전용 Intervals 조회
* 필요한 검증 문서 작성

금지 사항:

* 운영 DB 변경
* 운영 Spring/Gateway 재시작
* 외부 Intervals POST/PUT/PATCH/DELETE
* 기존 Injury Report 수정·삭제
* 기존 2026-10-15 승인 기록 삭제
* 새 Draft 사용자 승인 없이 approve 호출
* Injury Gate 우회
* Cloudflare Worker 배포
* OAuth Scope 확대
* 운영 MCP 쓰기 도구 공개
* Watchface / Relay 변경
* canonical main 병합
* origin push
* 레거시 저장소 변경

개발 서버 18083은 작업 완료 후 안전하게 종료하고 프로세스 종료를 확인한다.

⸻

11. 완료 보고 형식

다음 내용을 간결하게 보고한다.

A. Injury Report

* 신규 Report ID
* 실제 저장된 필드 및 값
* 신고 시각과 기록 시각
* 기존 Report #1, #2 보존 여부

B. Injury Gate

* 최신 상태
* Freshness
* nonRestTrainingAllowed
* RECOVERY / EASY 허용 여부

C. 새 Workout Draft

* Draft ID / Version
* 날짜
* Workout Type
* 전체 Segment
* AI 추천 근거
* 경고 사항

D. 실게시 준비

* Intervals 계정
* 기존 이벤트 수
* 예상 생성 이벤트
* Preview 수행 여부
* 사용자 승인이 필요한 사항

E. 시스템 상태

* 개발 DB 변경 건수
* 운영 DB 변경 0건
* 외부 Intervals 쓰기 0건
* 개발 서버 종료 확인
* 변경 파일 / Git 상태
* 다음 단계의 정확한 차단 조건

⸻

완료 기준

* RESOLVED 자기 신고가 개발 DB에 정확히 기록됨
* 기존 ACTIVE / UNKNOWN 이력 보존
* Injury Gate 정상 재평가
* 외부 게시 없는 실계정 캘린더 Preflight
* 실제 Claude CLI로 Draft 생성 또는 안전한 REST 판단 확인
* Non-REST Draft일 경우 사용자 검토용 상세 확보
* 운영 및 외부 시스템 무변경
* 사용자 Draft 승인 및 Intervals 실게시 승인 없이 STOP

대규모 리팩터링, 별도 조사 Phase, 새로운 도메인 기능 구현은 하지 않는다.

장기 아키텍처 원칙은 Modular Monolith + Single-Tenant Self-hosted를 유지한다.
