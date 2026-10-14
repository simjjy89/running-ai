# Phase 7F-2E — 사용자 지시서 (verbatim)

> 아래는 사용자가 이번 Phase에 제시한 지시문 원문이다. 수정하지 않고 그대로 보존한다.

---

RunningAI Phase 7F-2E — Draft #21 Approval & Publish Preview

0. 역할 및 실행 권한

RunningAI 시니어 백엔드 아키텍트 / 개발 리드 / QA.

이번 작업은 Phase 7F-2D의 후속 작업이다.

사용자가 Draft #21, Version 1의 승인을 명시적으로 허가했다.

승인 대상:

* Draft ID: 21
* Version: 1
* 날짜: 2026-10-16
* 이름: Short Easy Recovery Jog
* 유형: RECOVERY
* 운동 시간: 30분

사용자 승인 범위:

1. 승인 전 Draft 상태·버전 검증
2. 최신 Injury Gate 재검증
3. 개발 DB에서 Draft #21 승인
4. 승인 후 Publish Preview 조회
5. 결과 보고 후 STOP

사용자에게 동일한 Draft 승인 여부를 다시 질문하지 않는다.

단, 대상이 변경되었거나 안전 조건이 충족되지 않으면 승인하지 않고 STOP한다.

실제 Intervals.icu 게시 권한은 부여되지 않았다.

⸻

1. 현재 기준

Git

* 개발 저장소: C:\Users\simjy\orca\workspaces\running-ai-github\main-3
* 기준 HEAD: 30113c2
* Canonical 저장소: C:\running-ai-github
* 레거시 저장소: C:\running-ai — 절대 변경 금지

시작 시 Git 상태를 확인하되 reset/rebase/force 작업은 하지 않는다.

환경

운영:

* Spring: 127.0.0.1:18080
* DB: running_ai
* Gateway: 127.0.0.1:17910

개발:

* Spring: 127.0.0.1:18083
* DB: running_ai_7f2
* 현재 개발 서버 종료 상태
* Draft Publishing Enabled
* Legacy Publishing Disabled
* Garmin Scheduler Disabled

Intervals:

* Athlete ID 설정: "0" 유지
* 실제 계정: i6***48
* Timezone: Asia/Seoul

기존 부상 기록

개발 DB에 다음 보고가 있다.

* #1 ACTIVE — 2026-10-11 하프 이후 무릎 외측 통증
* #2 UNKNOWN — 통증 해소, 러닝 후 상태 미확인
* #3 RESOLVED — 사용자 회복 자기 신고

최신 RESOLVED Report는 2026-10-14 07:54 KST에 기록되었다.

기존 Injury Gate 정책의 48시간 유효기간은 2026-10-16 07:54 KST까지다.

실행 시점에 실제 유효성을 재확인한다.

유효기간이 지났다면 보고 후 STOP한다. 기존 Report의 시각을 변경하거나 동일 내용을 자동 재등록해 유효기간을 연장하지 않는다.

⸻

2. STEP 1 — 개발 환경 확인

개발 Spring 18083 기동 전:

1. 연결 DB가 정확히 running_ai_7f2인지 검증한다.
2. 운영 DB running_ai와 분리되어 있는지 확인한다.
3. 개발 환경의 Scheduler/Garmin Sync가 비활성인지 확인한다.
4. Legacy Publishing이 비활성인지 확인한다.
5. INTERVALS_ATHLETE_ID의 실효 값 "0"을 유지한다.
6. 운영 Spring 및 Gateway 상태를 읽기 전용으로 확인한다.

기존 개발 스크립트를 사용하여 18083을 기동한다.

운영 서비스는 재시작하지 않는다.

⸻

3. STEP 2 — Draft #21 원본 검증

기존 REST API 또는 도메인 서비스로 Draft #21을 조회한다.

다음 값이 정확히 일치해야 한다.

필드    기대값
Draft ID    21
Version    1
Status    DRAFT
Date    2026-10-16
Title    Short Easy Recovery Jog
Workout Type    RECOVERY
Duration    30분

Segment:

1. WARM_UP — 5분, VERY_EASY, 60–68% LTHR
2. MAIN — 20분, VERY_EASY, 65–74% LTHR
3. COOL_DOWN — 5분, VERY_EASY, 58–65% LTHR

다음 항목도 확인한다.

* 총 Segment 시간 = 30분
* 기존 Version 1 내용과 일치
* 승인 후 수정 가능성을 차단하는 도메인 규칙 존재
* 동일 날짜 승인 기록이 아직 없는지 확인
* 해당 Draft가 이미 승인되었는지 확인
* 기존 Publish 기록 여부 확인

Version, 날짜, 훈련 내용, 상태가 다르면 즉시 STOP한다.

이미 승인된 경우에는 중복 승인을 수행하지 말고 기존 Approval을 검증한 뒤 보고한다.

새로운 Revision을 생성하지 않는다.

⸻

4. STEP 3 — Injury Gate 재검증

승인 직전에 실제 Injury Gate를 호출한다.

확인 항목:

* 최신 Report ID
* status = RESOLVED
* freshness = 유효
* nonRestTrainingAllowed = true
* RECOVERY 허용 여부
* 다른 안전 차단 조건 존재 여부

단순히 DB의 RESOLVED 문자열만 읽어서 안전하다고 판단하지 않는다.

반드시 기존 WorkoutSafetyPolicy/WorkoutSafetyGateService 결과를 사용한다.

다음 경우 승인하지 않는다.

* Injury Report가 STALE
* status가 ACTIVE 또는 UNKNOWN으로 변경됨
* nonRestTrainingAllowed=false
* WorkoutSafetyGate가 차단
* Draft 내용이 승인 대상과 다름

허용 판정은 현재 도메인 정책의 결과이며 의학적 완치 판정이 아니다.

⸻

5. STEP 4 — Draft #21 승인

STEP 2와 STEP 3이 모두 PASS한 경우에만 승인한다.

기존 WorkoutDraftApprovalService 또는 승인 REST API를 사용한다.

승인 대상:

* draftId = 21
* acknowledgedVersion = 1
* confirmedByAthlete = true
* 기타 승인 API가 요구하는 필수 값은 실제 DTO 계약에 맞춰 입력

사용자는 이번 요청에서 Draft #21 Version 1을 명시적으로 승인했다.

따라서 승인 여부를 다시 질문할 필요는 없다.

하지만 확인 플래그만 직접 DB에 작성해서는 안 된다.

반드시 기존 서비스의 승인 경로를 사용하여 다음을 적용한다.

* 버전 일치 검증
* Injury Gate 검증
* 승인 불변성
* 날짜당 승인 1건 제약
* 중복 승인 방지
* 도메인 예외 처리

승인 결과를 DB에서 다시 읽어 다음을 검증한다.

1. Approval ID
2. Draft ID = 21
3. Version = 1
4. Approval Status
5. Approved At
6. 동일 날짜 승인 중복 없음
7. Published At 또는 외부 게시 기록이 생성되지 않았음

승인 API 실패 시 직접 SQL로 우회하지 않는다.

⸻

6. STEP 5 — Publish Preview 조회

승인이 성공한 경우에만 기존 읽기 전용 Preview API를 호출한다.

GET /api/v1/workout-drafts/21/publish-preview

실제 엔드포인트와 인증 규칙은 현재 구현 계약을 따른다.

확인 항목:

Draft

* draftId = 21
* approvalId = STEP 4의 승인 ID
* acknowledgedVersion = 1
* Workout Type = RECOVERY
* 날짜 = 2026-10-16

Preview

* publishable
* reasons
* restDay
* externalWriteRequired
* expectedOutcome
* rendered workout payload
* total duration
* segment count

Intervals

* Athlete ID = "0"
* 실제 대상 계정 = i6***48
* Timezone = Asia/Seoul
* 기존 이벤트 충돌 여부
* 예상 Operation

예상 결과가 CREATED이더라도 실제 게시하지 않는다.

Preview가 오류를 반환하거나 예상과 다른 payload를 보여주면 결과를 보고하고 STOP한다.

게시 미리보기가 추가적인 외부 요청을 발생시킨다면 읽기 전용인지 확인하고, 쓰기 경로가 있다면 실행하지 않는다.

⸻

7. STEP 6 — 외부 캘린더 Preflight

기존 검증된 스크립트를 사용한다.

scripts/dev/preflight-intervals-date.ps1

대상 날짜:
2026-10-16

다음을 확인한다.

1. 외부 Intervals 계정 일치
2. 해당 날짜 기존 WORKOUT 이벤트 수
3. 기존 RunningAI Marker
4. 외부 이벤트 충돌
5. 예상 Operation
6. 기존 이벤트 수정 위험

실제 외부 API에는 읽기 전용 GET만 허용한다.

과거에 조회한 0건 결과를 그대로 재사용하지 않는다.

게시 직전에 다시 조회해야 한다는 조건도 최종 보고에 포함한다.

⸻

8. STEP 7 — 승인 후 데이터 무결성 확인

개발 DB에서 다음을 검증한다.

* Draft #21 내용 변경 없음
* Draft Version 1 유지
* Approval 1건만 존재
* 해당 날짜 승인 중복 없음
* 새로운 publication row 없음
* 외부 publish status 변경 없음
* 이전 REST Draft #20 / Approval #4 유지
* Injury Report #1, #2, #3 보존
* 운영 DB 변경 없음

승인 작업 중 문제가 발생하면 새로운 외부 게시로 이어가지 않는다.

⸻

9. STEP 8 — 개발 서버 안전 종료

검증이 끝나면 개발 Spring 18083을 종료한다.

* 18083 Listener 종료 확인
* 운영 Spring 18080 정상 확인
* Gateway 17910 정상 확인
* 운영 Watchface/Relay 영향 없음 확인

프로세스를 강제 종료해야 하는 경우 개발 인스턴스의 PID와 실행 경로를 확인한다.

운영 프로세스를 종료하지 않는다.

⸻

10. 금지 사항

사용자가 승인하지 않은 다음 행위는 절대 실행하지 않는다.

Intervals.icu

* POST 이벤트 생성
* PUT 이벤트 수정
* PATCH
* DELETE
* 실제 publish API 호출
* 자동 게시 작업 활성화

운영

* 운영 DB INSERT/UPDATE/DELETE
* 운영 Spring 재시작
* 운영 Gateway 재시작
* OAuth Scope 확대
* Cloudflare Worker 배포
* 운영 MCP 쓰기 도구 공개

개발

* Draft #21 Revision
* 승인 대상 변경
* 임의 Injury Report 추가
* RESOLVED 기록 시간 수정
* Injury Gate 우회
* 기존 승인 기록 삭제
* 직접 SQL로 Approval 우회

Git

* canonical main 병합 금지
* origin push 금지
* 레거시 저장소 변경 금지

⸻

11. 완료 보고

다음 내용을 간결하게 보고한다.

A. 승인 전 검증

* Draft ID / Version / Status
* 날짜 / Workout Type / 전체 Segment
* 기존 Approval 존재 여부
* Injury Gate 상태 및 Freshness

B. 승인 결과

* Approval ID
* 승인 시각
* Draft Version 일치 여부
* 승인 성공 여부
* 중복 승인 방지 결과

C. Publish Preview

* publishable
* externalWriteRequired
* expectedOutcome
* 전체 전송 예정 훈련 내용
* 예상 외부 이벤트 변경

D. 캘린더 확인

* 대상 Intervals 계정
* 기존 이벤트 수
* 충돌 여부
* 예상 Operation

E. 최종 시스템 상태

* 개발 DB 변경 건수
* 운영 DB 변경 0건
* 실제 Intervals 쓰기 0건
* 기존 Injury Report 보존
* 개발 서버 종료
* Git HEAD 및 변경 파일
* 다음 단계 차단 조건

완료 기준

다음 조건을 모두 만족해야 PASS다.

1. Draft #21 Version 1 정확히 확인
2. Injury Gate 최신 상태 유효
3. 기존 승인 서비스로 승인 성공
4. Approval ID 확인
5. Publish Preview 정상
6. 캘린더 충돌 없음
7. 운영 DB 변경 없음
8. 외부 Intervals 쓰기 0건
9. 개발 서버 종료 확인

조건을 만족하지 못하면 원인을 보고하고 STOP한다.

성공하더라도 실제 게시를 자동 실행하지 않는다.

다음 Phase는 실제 Intervals.icu 게시 1건 검증이며, 별도의 명시적 사용자 승인이 필요하다.

불필요한 리팩터링 없이 기존 구현을 재사용하고 작업 완료 후 STOP한다.
