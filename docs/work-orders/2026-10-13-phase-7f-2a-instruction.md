# Phase 7F-2A instruction (verbatim)

Recorded as given by the owner on 2026-10-13. Not edited, not summarised.

---

RunningAI Phase 7F-2A — Intervals.icu 실제 게시 경로 검증

역할

RunningAI 시니어 백엔드 아키텍트 / 개발 리드 / QA.

현재 기준

* main-3: 4ebfad5
* canonical main: 799e3b5
* Phase 7F-1 완료
* MCP Read 7종 + Train 6종 구현
* 운영 Train MCP 비활성화
* 운영 Intervals.icu 실게시 0건

목표

ChatGPT OAuth 권한을 확대하기 전에 기존 AI Workout Draft → Intervals.icu 게시 경로가 실제 계정에서도 정상 동작하는지 검증한다.

이번 작업은 안전한 검증 준비와 Mock E2E까지 수행한다.

실제 외부 캘린더에 쓰는 행위는 별도 사용자 승인 후 실행한다.

STEP 1 — 실게시 경로 검토

다음 흐름의 실제 구현을 확인한다.

Draft 생성 → Revision → Approval → Preview → Publish → Intervals.icu

검증 항목:

* 대상 Intervals athlete ID 선택 방식
* API 키 로딩 및 노출 방지
* 날짜와 시간대
* 훈련 구조 변환
* 기존 일정 덮어쓰기 가능성
* 게시 성공 응답과 저장 결과
* 타임아웃 후 중복 게시 가능성
* 기존 날짜 기반 게시 기능과의 충돌

STEP 2 — 안전한 개발 환경 준비

* 운영 Spring 18080과 별도 프로세스 사용
* 운영 DB와 분리된 개발 DB 사용
* 기존 운영 데이터 변경 금지
* 운영 Intervals 캘린더의 기존 훈련 수정·삭제 금지
* 외부 게시 대상은 명시적으로 확인
* 실계정 자격증명은 환경변수 또는 비밀 저장소로만 전달
* 로그·테스트 결과에 API 키 출력 금지

STEP 3 — 실게시 전 검증

실제 외부 요청 없이 다음을 확인한다.

1. Draft 생성 성공
2. Revision 정상 반영
3. Approval 및 버전 검증
4. Preview와 승인 내용 일치
5. 외부 전송 payload 검증
6. 기존 일정 충돌 감지
7. 동일 Draft 중복 게시 방지
8. 타임아웃·응답 유실 시 처리
9. 게시 대상 Athlete ID 검증
10. Injury Gate 우회 불가

STEP 4 — 실제 게시 실행 계획 작성

실게시를 위한 다음 정보를 보고한다.

* 게시 예정 날짜
* 훈련 이름과 내용
* 실제 Intervals athlete ID의 마스킹된 식별 정보
* 해당 날짜 기존 일정 존재 여부
* 생성될 이벤트 종류
* 외부 요청의 정확한 영향
* 실패 시 확인 및 복구 절차
* 예상되는 Garmin 캘린더 연동 영향

실제 게시에 사용할 훈련은 회복 목적의 짧은 테스트 훈련으로 한다.

단, 부상 상태에 따라 운동 실행이 허용되지 않으면 Injury Gate를 우회하지 않는다.

STEP 5 — 승인 대기

여기까지 완료하면 STOP한다.

사용자의 명시적 승인 없이:

* 실제 Intervals API 쓰기 호출 금지
* 기존 캘린더 수정·삭제 금지
* 운영 OAuth scope 확대 금지
* Cloudflare Worker 배포 금지
* 운영 Gateway 쓰기 도구 공개 금지
* 운영 Spring 재시작 금지
* canonical main 병합 및 origin push 금지

완료 보고

1. 실게시 경로 검토 결과
2. Mock E2E 결과
3. 외부 게시 전 발견한 위험
4. 게시 예정 날짜 및 훈련 상세
5. 실제 게시 시 예상 영향
6. 변경 파일 및 커밋
7. 사용자 승인 후 실행할 명령과 검증 방법

완료 후 STOP한다.

추가적인 대규모 리팩터링이나 별도 조사 Phase를 만들지 말고, 실게시 검증에 필요한 최소 작업만 수행한다.
