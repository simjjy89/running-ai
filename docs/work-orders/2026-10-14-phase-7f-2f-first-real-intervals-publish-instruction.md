# Phase 7F-2F — 사용자 지시서 (verbatim)

> 아래는 사용자가 이번 Phase에 제시한 지시문 원문이다. 수정하지 않고 그대로 보존한다.

---

RunningAI Phase 7F-2F — 최초 Intervals.icu 실게시 및 E2E 검증

0. 역할 및 최종 실행 승인

RunningAI 시니어 백엔드 아키텍트 / 개발 리드 / QA.

Phase 7F-2E의 결과를 이어받아 최초 실게시 검증을 수행한다.

사용자가 다음 단일 외부 게시 작업을 명시적으로 승인했다.

* 개발 DB: running_ai_7f2
* Draft ID: 21
* Draft Version: 1
* Approval ID: 5
* 날짜: 2026-10-16
* 제목: Short Easy Recovery Jog
* 유형: RECOVERY
* 시간: 30분
* 외부 서비스: Intervals.icu
* 외부 대상 계정: i6***48
* 외부 athlete-id 설정: "0"
* 허용 변경: 해당 날짜에 신규 WORKOUT 이벤트 정확히 1건 생성

위 조건이 모두 일치하고 안전성 검증에 통과하면, 사용자에게 같은 게시 승인을 다시 요청하지 말고 1회 실행한다.

승인은 해당 Draft와 계정에만 적용된다.

다른 날짜, 다른 Draft, 다른 계정에 대한 게시는 승인되지 않았다.

조건 불일치, 부상 상태 만료, 캘린더 충돌 또는 외부 게시 결과 불확실성이 발생하면 정해진 STOP 규칙을 따른다.

⸻

1. 현재 기준

Git

* 개발 저장소: C:\Users\simjy\orca\workspaces\running-ai-github\main-3
* 기준 HEAD: 30113c2
* Canonical 저장소: C:\running-ai-github
* 레거시 저장소: C:\running-ai

Git HEAD와 변경 상태를 확인한다.

기존 미추적 work-order 문서는 보존한다.

Reset, rebase, force push, canonical main 병합은 하지 않는다.

서버와 DB

운영:

* Spring 127.0.0.1:18080
* PostgreSQL DB running_ai
* MCP Gateway 127.0.0.1:17910

개발:

* Spring 127.0.0.1:18083
* PostgreSQL DB running_ai_7f2
* 개발 서버 현재 종료 상태

개발 설정:

* Draft Publishing: ON
* Legacy Publishing: OFF
* Garmin Sync: OFF
* Scheduler: OFF

외부:

* Intervals athlete-id "0" 유지
* 실제 인증 대상 계정 i6***48
* Asia/Seoul

운영 DB와 운영 서비스는 절대 변경하지 않는다.

Phase 7F-2E 완료 결과

* Draft #21, Version 1, APPROVED
* Approval #5
* Injury Report #3 RESOLVED
* 승인 시점 Injury Gate PASS
* Preview publishable=true
* Preview externalWriteRequired=true
* Preview expectedOutcome=PUBLISH
* structuredStepCount=3
* unpublishableReasons=[]
* 기존 외부 WORKOUT 이벤트 조회 시 0건
* 예상 외부 operation=CREATED
* 실제 외부 게시 아직 0건

Injury Report #3의 만료 예정 시각:

2026-10-16 07:54:24 KST

현재 시각을 실제로 확인하고 유효기간을 재계산한다.

⸻

2. STEP 1 — 실행 환경 안전 확인

개발 서버를 시작하기 전에 다음을 확인한다.

1. DB 연결 대상이 running_ai_7f2임을 검증한다.
2. 운영 DB running_ai가 아님을 검증한다.
3. 개발 서버가 127.0.0.1:18083으로만 바인딩되는지 확인한다.
4. 운영 Spring 18080과 Gateway 17910이 정상 실행 중인지 확인한다.
5. 개발 환경의 Garmin 동기화와 Scheduler가 비활성인지 확인한다.
6. Legacy Publishing이 비활성인지 확인한다.
7. 기존 레거시 PowerShell 게시 예약 작업이 여전히 비활성인지 확인한다.
8. INTERVALS_ATHLETE_ID를 변경하지 않는다.
9. 실제 키가 연결하는 Intervals 계정이 승인된 계정인지 확인한다.
10. 다른 개발 프로세스에서 동일 Draft를 게시 중이지 않은지 확인한다.

사전 점검에 실패하면 외부 요청 없이 STOP한다.

개발 인스턴스는 기존 검증된 start-dev-spring.ps1을 사용한다.

-IntervalsLive -DraftPublishLive 옵션의 실제 동작과 운영 DB 격리를 확인한 후 기동한다.

운영 Spring을 재시작하거나 재빌드하지 않는다.

⸻

3. STEP 2 — Draft 및 Approval 최종 검증

개발 DB 및 기존 API에서 다음을 재조회한다.

Draft

* ID=21
* Version=1
* Status=APPROVED
* Date=2026-10-16
* Title=Short Easy Recovery Jog
* WorkoutType=RECOVERY
* Duration=30분

Segment

1. WARM_UP — 5분 VERY_EASY, 60–68% LTHR
2. MAIN — 20분 VERY_EASY, 65–74% LTHR
3. COOL_DOWN — 5분 VERY_EASY, 58–65% LTHR

총 30분, 3개 구간이어야 한다.

Approval

* Approval ID=5
* Draft ID=21
* Version=1
* 상태 APPROVED
* 해당 날짜 승인 기록 1건

Publication

* Draft #21의 기존 publication 없음
* 기존 publication 3건 보존
* 새로운 외부 publication row가 없는 상태

Draft의 내용 또는 승인 대상이 바뀌었다면 STOP한다.

Approval을 다시 생성하거나 Draft를 수정하지 않는다.

⸻

4. STEP 3 — Injury Gate 최종 검증

게시 직전에 실제 WorkoutSafetyGateService를 실행한다.

필수 조건:

* 최신 Injury Report ID=3
* status=RESOLVED
* freshness 유효
* nonRestTrainingAllowed=true
* RECOVERY 허용
* 차단 사유 없음

2026-10-16 07:54:24 KST 이후이거나 실제 Gate가 STALE을 반환하면 게시하지 않는다.

금지:

* Injury Report timestamp 수정
* 만료 연장을 위한 자동 재등록
* 신규 RESOLVED 기록 임의 생성
* Injury Gate Mock 또는 우회
* 서비스 정책 변경

Gate가 실패하면 이유를 보고하고 STOP한다.

RESOLVED는 사용자의 증상 해소 자기 신고이지 의학적인 완치 보증이 아님을 유지한다.

⸻

5. STEP 4 — Publish Preview 재검증

다음 읽기 전용 엔드포인트를 호출한다.

GET /api/v1/workout-drafts/21/publish-preview

필수 결과:

* publishable=true
* externalWriteRequired=true
* expectedOutcome=PUBLISH
* restDay=false
* structuredStepCount=3
* unpublishableReasons=[]
* Approval ID=5

전송 예정 구조:

* Warm Up 5m 60-68% LTHR hr=1s
* Main 20m 65-74% LTHR hr=1s
* Cool Down 5m 58-65% LTHR hr=1s

Phase 7F-2E에서 승인한 내용과 완전히 일치해야 한다.

Payload 또는 Preview가 바뀌었다면 STOP한다.

Preview에서 직접 외부 쓰기를 실행해서는 안 된다.

⸻

6. STEP 5 — Intervals 캘린더 게시 직전 조회

기존 검증 스크립트를 사용한다.

scripts/dev/preflight-intervals-date.ps1

대상 날짜:

2026-10-16

검증 대상:

* Intervals 계정 i6***48
* athlete-id "0"
* Asia/Seoul
* 외부 WORKOUT 이벤트 수
* RunningAI owned marker 수
* Foreign event 수
* Legacy marker 여부
* 기존 일정 충돌 여부
* 예상 Operation

승인 대상 Marker:

runningai:workout:v1:0:2026-10-16

필수 결과:

* 해당 날짜 WORKOUT 이벤트 0건
* owned 0건
* foreign 0건
* 승인 대상 marker 없음
* 충돌 없음
* 예상 Operation=CREATED

CREATED가 아니면 이번 승인으로 UPDATE, NO_CHANGE 또는 기존 이벤트 takeover를 수행하지 않는다.

기존 일정이 한 건이라도 발견되면 STOP한다.

이 조회는 실제 게시 요청 직전에 다시 수행한다.

API 오류, 인증 오류 또는 결과 해석이 불명확한 경우에도 STOP한다.

⸻

7. STEP 6 — 실제 Intervals.icu 단일 게시

STEP 1~5가 모두 PASS한 경우에만 실행한다.

기존 서버의 정식 게시 API를 사용한다.

POST /api/v1/workout-drafts/21/publish

대상은 반드시 개발 서버:

127.0.0.1:18083

허용되는 외부 효과:

Intervals.icu의 2026-10-16 캘린더에 WORKOUT 1건 신규 생성

규칙:

1. 내부 게시 API 호출은 최초 1회만 수행한다.
2. 직접 Intervals API를 별도로 호출하여 이벤트를 만들지 않는다.
3. 기존 Publisher와 HttpIntervalsWorkoutClient를 그대로 사용한다.
4. 승인된 훈련 내용과 날짜를 변경하지 않는다.
5. 기존 이벤트 수정·삭제를 수행하지 않는다.
6. 외부 HTTP 요청을 임의 재시도하지 않는다.
7. 게시 실패 시 직접 SQL로 publication 성공을 기록하지 않는다.
8. 외부 API 키와 Authorization 헤더를 출력하지 않는다.
9. 구현을 고쳐 실패를 우회하지 않는다.

단일 게시 API 내부의 정상 동작에 필요한 list → create → readback은 허용한다.

예상 결과는 CREATED이나, 실제 성공 여부는 반드시 응답과 외부 Readback으로 검증한다.

⸻

8. STEP 7 — 게시 성공 검증

A. RunningAI 응답

다음을 확인한다.

* Draft ID=21
* Approval ID=5
* 대상 날짜=2026-10-16
* 실제 operation=CREATED
* 검증된 외부 게시 결과
* 외부 이벤트 ID
* 오류 없음

응답 필드명은 실제 DTO 계약을 따른다.

B. Intervals 외부 Readback

게시 결과를 신뢰하는 데 그치지 말고 실제 Intervals API로 읽기 전용 조회한다.

확인 사항:

1. 날짜가 정확히 2026-10-16
2. 계정이 i6***48
3. 이벤트 종류 WORKOUT
4. RunningAI marker 일치
5. 이벤트 제목 일치
6. 전체 30분
7. 세 구간 구조 일치
8. 승인된 Draft와 전송 내용 일치
9. 동일 날짜 예상하지 못한 추가 이벤트 없음
10. 기존 이벤트 수정·삭제 없음

C. 개발 DB 기록

* Draft #21의 publication 정확히 1건
* Approval #5 연결
* 외부 이벤트 ID 기록
* 게시 결과 CREATED
* 기존 publication 3건 보존
* 기존 Injury Report 3건 보존
* Draft #20 및 Approval #4 보존

D. 멱등성 검증

최초 게시 성공과 외부 Readback이 모두 확인된 후에만 실행한다.

동일 Draft에 대해 기존 게시 API를 다시 호출했을 때:

* 이미 게시된 결과를 반환해야 한다.
* 기존 publication을 재사용해야 한다.
* 추가 외부 POST/PUT/PATCH/DELETE가 발생하면 안 된다.
* 외부 이벤트 수는 여전히 1건이어야 한다.

이 검증은 외부 변경을 추가하지 않는다는 구현상 보장이 확인된 경우에만 수행한다.

그 보장이 불확실하면 반복 호출을 생략하고 STOP하여 보고한다.

⸻

9. STEP 8 — 실패 또는 결과 불명확 시 처리

실제 외부 게시가 타임아웃되거나 응답이 유실되면:

1. 새 게시 요청을 보내지 않는다.
2. 동일 marker와 날짜를 기준으로 Intervals를 읽기 전용 조회한다.
3. 외부 이벤트가 실제 생성되었는지 확인한다.
4. 생성되어 있다면 중복 생성 없이 기존 서비스의 복구·결과 채택 경로를 검증한다.
5. 결과가 불명확하거나 여러 이벤트가 발견되면 STOP한다.

다음 행위 금지:

* 무조건 재POST
* 임의로 다른 날짜에 게시
* 기존 이벤트 강제 UPDATE
* 자동 DELETE 후 재게시
* Injury Gate 우회
* 직접 SQL로 결과 조작

외부 게시가 성공했지만 DB 반영에 실패했더라도, 이벤트를 자동으로 삭제하거나 재생성하지 않는다.

외부 상태를 읽기 전용으로 수집하고, 필요한 수동 복구 절차만 보고한다.

예상 결과가 CREATED가 아닌 경우에는 후속 외부 변경을 금지한다.

⸻

10. STEP 9 — Garmin 전달 확인

Intervals.icu에 정상 생성되었다면 Garmin 연동 여부를 확인한다.

이번 승인에 포함된 것은 Intervals.icu 이벤트 신규 생성 1건뿐이다.

따라서 다음은 읽기 전용으로 수행한다.

* Intervals 이벤트 존재 확인
* 연결 설정상 Garmin 자동 전달 가능 여부 확인
* Garmin Connect 또는 기기에 훈련이 표시되는지 확인 가능한 범위에서 관찰

금지:

* Garmin에 별도 훈련 직접 업로드
* 중복 훈련 생성
* Garmin 동기화 설정 변경
* Garmin 계정의 기존 훈련 삭제

Garmin 전달이 확인되지 않더라도 Intervals 게시 성공을 실패로 바꾸지 않는다.

Intervals 게시 검증과 Garmin 전달 검증 결과를 구분하여 보고한다.

⸻

11. STEP 10 — 종료 및 운영 보호 확인

검증이 끝나면 개발 Spring 18083을 종료한다.

* 개발 서버 PID 확인
* 개발 서버 종료
* 포트 18083 해제 확인
* 운영 Spring 18080 정상
* Gateway 17910 정상
* 운영 DB 무변경 확인
* Watchface/Relay 영향 없음 확인
* 레거시 예약 게시 작업 비활성 유지

개발 인스턴스에서 실제 Intervals 쓰기 기능을 켠 상태로 방치하지 않는다.

⸻

12. 절대 금지 사항

이번 승인 범위를 벗어나는 다음 작업은 수행하지 않는다.

운영

* 운영 DB INSERT/UPDATE/DELETE
* 운영 Injury Report 추가
* 운영 Spring 빌드 또는 재시작
* Gateway 재시작 및 설정 변경
* OAuth Scope 확대
* Cloudflare Worker 배포
* 운영 MCP 쓰기 도구 활성화

외부 계정

* 2026-10-16 외 다른 날짜 게시
* 승인된 Draft #21 이외 게시
* 기존 Intervals 이벤트 UPDATE/DELETE
* 외부 이벤트 2건 이상 생성
* 다른 athlete 계정에 게시
* Garmin 직접 게시

코드 및 Git

* 게시 성공을 위한 도메인 정책 변경
* Injury Gate 우회
* DB 스키마 변경
* 기존 승인 기록 삭제
* Draft Revision
* canonical main 병합
* origin push
* 레거시 저장소 수정
* 관련 없는 리팩터링

코드 결함이 발견되면 실게시를 강행하지 말고 STOP한다.

⸻

13. 최종 보고 형식

A. 게시 전 검증

* 실행 시각(KST)
* Injury Gate 결과 및 유효성
* Draft #21 / Approval #5 검증
* Intervals 대상 계정
* 게시 직전 캘린더 이벤트 수
* 예상 Operation

B. 실제 게시 결과

* 내부 API 응답 코드
* 성공/실패/불확실
* 실제 Operation
* 외부 Event ID
* External Marker
* 외부 쓰기 횟수

C. 외부 Readback

* 이벤트 날짜
* 이벤트 종류
* 운동 시간
* 실제 Segment 내용
* 원본 Draft 일치 여부
* 예상하지 못한 이벤트 유무

D. 멱등성

* 재호출 여부
* alreadyPublished 또는 대응 필드
* 추가 외부 쓰기 횟수
* Publication 중복 여부

E. 시스템

* 개발 DB 변경 내용
* 운영 DB 변경 건수
* 운영 서비스 영향
* 개발 서버 종료 여부
* Garmin 전달 확인 여부
* Git 상태 및 변경 파일

F. 최종 판정

다음 중 하나로 보고한다.

PASS

* Injury Gate 유효
* 외부 CREATED 1건
* Readback 검증 성공
* 기존 이벤트 무변경
* 개발 DB publication 1건
* 운영 무변경
* 추가 외부 쓰기 없음

PARTIAL

* 외부 이벤트가 생성되었으나 일부 검증 실패 또는 결과 불명확
* 외부 중복 생성 금지
* 복구 절차 별도 보고

STOP

* 게시 이전 조건 미충족
* 외부 쓰기 미실행
* 실패 원인 및 차단 지점 보고

⸻

14. 완료 기준

이번 Phase는 최초 실계정 CREATED 경로를 검증하는 일회성 작업이다.

* 정상 조건이면 승인된 WORKOUT 1건 게시
* 외부 Readback으로 실제 생성 확인
* 추가 게시 없이 멱등성 확인
* 개발 DB에만 결과 기록
* 운영 DB / OAuth / Gateway 무변경
* 개발 인스턴스 안전 종료

성공하면 결과를 보고하고 STOP한다.

이번 사용자의 외부 쓰기 승인은 Draft #21의 최초 게시 1건에 한정된다. 추가 훈련 게시나 운영 ChatGPT MCP 활성화는 별도 Phase에서 수행한다.

장기 아키텍처는 Modular Monolith + Single-Tenant Self-hosted를 유지한다.
