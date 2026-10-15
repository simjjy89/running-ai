RunningAI Phase 9 — Production Operations & End-to-End Completion

0. 역할 및 작업 원칙

당신은 RunningAI 프로젝트의 시니어 백엔드 아키텍트, 개발 리드, DevOps 엔지니어 및 QA 책임자다.

Phase 8B 개발·테스트·운영 배포가 완료되었다.

이제 목표는 기능을 새로 만드는 것이 아니라, 운영 자동화와 실제 사용자 경험을 완성하는 것이다.

각 세부 작업마다 사용자에게 지시를 다시 요청하지 말고, 승인된 범위에서 설계·구현·테스트·수정·검증을 연속 진행하라.

단, 운영 데이터 변경과 외부 서비스에 영향을 주는 작업은 명시적인 사용자 승인 범위 안에서만 실행한다.

⸻

1. 현재 기준선

2026-10-15 기준:

* canonical main / origin/main: 78306e4
* 운영 JAR: 0fce192 기반
* PostgreSQL 17 / Flyway V30
* Spring 테스트: 1596 PASS
* Garmin Connector: 154 PASS
* External Relay: 31 PASS
* MCP Gateway: 46 PASS
* MCP Worker: 49 PASS
* 운영 Activity: 48건
* Garmin 활동 스케줄러: OFF
* Coach Refresh 자동 수집: OFF
* Garmin 수동 증분 동기화: 아직 미실행
* 회복 데이터: 최근 28일 중 NOT_COLLECTED 9일
* 게시 기능: OFF
* 실제 Intervals.icu 게시: 이번 작업 범위에서 제외

Read MCP 호출은 운영에서 확인됐다.

Train MCP 호출은 ChatGPT에서 get_workout_draft(17)이 성공했지만, 배포 후 Gateway의 runningai:train 권한 로그 대조가 남아 있다.

이전에 검증된 기능을 반복 조사하거나 재구현하지 않는다.

⸻

2. Workstream A — 운영 프로세스 안정화

A-1. Watchdog 정상화

현재 Watchdog에는 배포 중 추가한 임시 반복 트리거가 있다.

이를 기존 canonical 설정과 일치시키고 다음을 검증하라.

* 로그인 후 자동 시작
* 주기적 Watchdog 실행
* 재부팅 후 정상 복구 가능성
* 중복 Watchdog 프로세스 차단
* 배포 도중 프로세스 충돌 방지
* 서비스 비정상 종료 감지
* 반복 재시작 방지

기존 install-running-ai-watchdog-task.ps1의 실제 동작을 검토하고 필요한 부분을 수정하라.

운영 프로세스를 보호하기 위해 정상 실행 중인 Watchdog을 불필요하게 중지하지 않는다.

실제 PC 재부팅은 승인 없이 실행하지 않는다.

A-2. Gateway / Tunnel 감독

이전에 MCP Gateway가 종료됐지만 Watchdog 관리 대상이 아니어서 ChatGPT 연결이 중단됐다.

다음을 구현하라.

1. Gateway 생존 및 포트 확인
2. cloudflared Tunnel 상태 확인
3. Gateway 비정상 종료 감지
4. 기존 검증된 런처를 이용한 복구
5. 재시작 횟수 제한 및 백오프
6. 반복 장애 시 상태·로그 기록
7. Watchdog 상태 파일에 Gateway/Tunnel 포함
8. 재부팅 및 로그인 후 복구 시나리오 테스트

주의 사항:

* 운영 Gateway 포트는 17910
* 17911은 테스트용
* 현재 Worker OAuth 및 scope 정책 변경 금지
* Quick Tunnel hostname 변경 시 Worker 연결이 깨질 수 있음
* 이번 작업에서 Named Tunnel이나 유료 인프라를 강제로 도입하지 않음

서비스 중단을 유발하지 않는 개발·테스트 작업은 자율적으로 진행한다.

운영 작업 스케줄러 또는 감독 대상 변경이 필요하면 별도 승인 목록에 포함한다.

⸻

3. Workstream B — Garmin 자동 데이터 수집

목표

Garmin 활동이 별도의 수동 요청 없이 RunningAI에 자동으로 유입되도록 한다.

이미 구현된 기능:

* 증분 동기화
* 7일 overlap
* raw-first 저장
* 중복 방지
* 동시 실행 방지
* 실패 이력
* 자동 차단기
* 401/429 구분
* 체크포인트 관리

위 기능을 다시 만들지 않는다.

B-1. 최종 사전 점검

다음을 확인한다.

* 현재 체크포인트
* Garmin 인증 상태
* 운영 백업
* garmin_sync_attempt 상태
* V30의 STARTED/INTERRUPTED 관련 롤백 위험
* Garmin API 요청량
* 스케줄러 및 Coach Refresh 설정
* 운영 작업의 중단 조건

B-2. 운영 수동 동기화

별도 사용자 승인 후 1회 실행한다.

실행 절차:

1. 운영 DB 백업 및 복구 가능성 확인
2. DB 기준선 기록
3. Garmin 수동 동기화 1회
4. SUCCESS/PARTIAL/FAILED 확인
5. 활동 및 Raw 데이터 변경 검증
6. 중복·원본·체크포인트 검증
7. Garmin 실패 이력 정상 기록 확인
8. TrainingContextV2 최신성 확인

401/429, 데이터 무결성 이상, 예상하지 못한 삭제 또는 체크포인트 불일치가 발생하면 중단한다.

수동 동기화는 Garmin에 대한 읽기 작업이지만 운영 RunningAI DB에는 UPSERT가 발생할 수 있다.

B-3. 자동 스케줄러 활성화

운영 수동 동기화가 성공하고 24시간 관측을 통과한 뒤에만 진행한다.

* 기존 스케줄러 재사용
* 새로운 스케줄러 중복 생성 금지
* 정해진 실행 주기 유지
* 자동 차단기 정상 동작 확인
* 최초 실행 로그 검증
* 실패 이력 및 데이터 신선도 확인
* 활성화 후 최소 24시간 안정성 관측

401/429가 발생하거나 연속 실패 임계값에 도달하면 정해진 중단 정책을 적용한다.

관측 중 데이터 동기화와 기존 V28 JAR 롤백 간의 호환성도 계속 확인한다.

24시간 경과가 필요한 단계는 관측 기준과 기록 방식을 준비하되, 실제 관측 기간이 지나지 않았는데 완료라고 보고하지 않는다.

⸻

4. Workstream C — 회복 데이터 최신화

목표

RunningAI가 실제 Garmin 회복 데이터에 근거해 코칭하도록 한다.

현재 28일 창에서 9일이 NOT_COLLECTED 상태다.

NOT_COLLECTED는 데이터 없음이 아니라 조회가 수행되지 않았다는 뜻이다.

작업

1. 누락 날짜 확인
2. 회복 지표 원본 조회 가능성 확인
3. onlyMissing=true 동작 검증
4. Garmin 활동 동기화와 동시 실행 방지
5. 예상 API 요청량 계산
6. 기존 STORED 데이터 불필요한 덮어쓰기 방지
7. NO_DATA/FAILED/NOT_COLLECTED 구분
8. TrainingContextV2와 RecoveryContext 재검증

수집 대상:

* HRV
* 수면
* 안정시 심박
* Body Battery
* 스트레스

운영 백필은 사용자 승인 후 실행한다.

실제 존재하지 않는 측정값을 0이나 임의 값으로 채우지 않는다.

28일 창의 모든 날짜에 실제 측정값이 있어야만 성공으로 판정하는 오류를 피한다.

⸻

5. Workstream D — AI Coach 및 워치페이스 실사용 검증

D-1. AI Coach

기존 Draft 생성·수정·승인 기능을 재사용한다.

개발 환경에서 다음 흐름을 E2E 검증하라.

Garmin 데이터 → TrainingContextV2 → AI Coach → Draft 생성 → 수정 → 승인 → Today Workout API

검증 항목:

* 최신 데이터 사용
* 회복 상태 반영
* 부상 게이트
* 데이터 부족 고지
* 훈련 구조와 강도
* 승인된 Draft와 미승인 Draft 구분
* 이미 지난 훈련의 stale 처리
* REST DAY 처리

운영에 실제 새 Draft를 생성하거나 승인하지 않는다.

실제 Intervals.icu 게시는 수행하지 않는다.

D-2. Garmin 훈련 도달 검증

기존 /scheduled-workouts 읽기 경로를 활용한다.

이미 LIVE_VERIFIED된 Garmin Calendar 조회 기능을 다시 개발하지 않는다.

다음 개념을 명확히 구분한다.

* Intervals 게시 성공
* Garmin Calendar에서 워크아웃 발견
* Garmin 기기 동기화 확인
* 실제 시계에서 워크아웃 확인

Garmin Calendar 조회 성공을 Garmin FR265 실기기 다운로드 완료로 잘못 표현하지 않는다.

D-3. Watchface 연동

현재 Spring → Relay 계약과 기존 워치페이스 프로토콜을 유지한다.

검증 사항:

* 승인된 AI Draft 표시
* 날짜 일치
* 훈련명
* 훈련 유형
* 총 운동 시간
* REST DAY
* STALE
* 통신 실패 fallback
* 기존 Watchface UI 호환

실제 FR265 화면에서 확인할 사항을 체크리스트로 작성하라.

시계 화면 디자인·폰트 변경은 이번 작업에 포함하지 않는다.

실기기 조작이 필요한 작업은 사용자에게 마지막에 한 번만 요청한다.

⸻

6. Workstream E — 잔여 결함 및 MCP 최종 검증

E-1. Injury 정렬 동률 수정

현재 InjuryStateService의 history 정렬이 recorded_at DESC에만 의존한다.

동일 시각 기록 시 결과 순서가 불안정할 수 있다.

다음을 수행하라.

* recorded_at DESC, id DESC 기준의 결정적 정렬
* 동률 재현 테스트
* 최신 Injury Report 판정 검증
* 기존 부상 게이트 회귀 테스트

운영 injury_report의 기존 행은 수정·삭제하지 않는다.

E-2. Train OAuth 검증

ChatGPT에서 다음 도구를 실제 호출했다.

* get_workout_draft(17)
* 결과: APPROVED / EASY / 40분
* Correlation ID: 361a38ff-07f9-4b7f-8667-24e3f96dd222

Gateway와 Spring 로그를 교차 확인하라.

특히 확인할 사항:

1. 실제 tools/call 기록
2. OAuth 요청 및 승인 scope
3. runningai:train 권한 검사 통과 여부
4. 운영 Spring 도달 여부
5. Read/Train 권한 분리 유지
6. publish_approved_draft 외부 차단 유지

로그로 확인할 수 없다면 미검증으로 남긴다.

Read 성공을 Train 권한 성공으로 대체하지 않는다.

재인증이나 OAuth scope 변경은 필요성이 입증되기 전까지 수행하지 않는다.

⸻

7. 공통 개발·운영 원칙

자율 진행 허용

* 코드 분석
* 설계 및 구현
* 버그 수정
* 테스트 추가
* 개발 DB 테스트
* 배포·복구 리허설
* 운영 상태 읽기
* 로그 분석
* 문서 작성
* 격리된 작업 브랜치의 로컬 커밋

중간 작업마다 사용자에게 승인받지 않는다.

명시적 승인 후 실행

* 운영 Garmin 수동 동기화
* 운영 Garmin 스케줄러 활성화
* 운영 회복 백필
* 운영 작업 스케줄러 구성 변경
* 운영 JAR/Connector/Relay 재배포
* GitHub push 및 main 병합
* 실제 Intervals.icu 게시
* 운영 DB 데이터 수정·삭제
* 시계에 배포되는 변경
* OAuth scope 변경

승인이 필요한 작업은 하나의 실행 계획과 승인 요청으로 묶는다.

단, 사용자 승인이 필요한 작업을 문서에 기재했다는 이유만으로 실행 권한이 주어졌다고 해석하지 않는다.

절대 금지

* C:\running-ai 레거시 저장소 변경
* 테스트를 통과시키기 위한 테스트 삭제
* 확인되지 않은 외부 데이터 생성
* 부상 안전 게이트 우회
* Garmin/Intervals 계정에 대한 무단 쓰기
* 운영 Flyway repair 또는 데이터 삭제
* 신규 유료 인프라 도입
* 시크릿·토큰 Git 커밋
* force push / 강제 reset

⸻

8. 운영 안전 및 롤백

Flyway V30의 garmin_sync_attempt에는 V28에서 인식할 수 없는 STARTED/INTERRUPTED 상태가 존재한다.

운영 수동 동기화 이후에는 기존 V28 JAR로 단순 교체하는 것이 안전한 롤백인지 매번 확인해야 한다.

문제 발생 시:

1. 신규 작업 중단
2. 현재 DB 및 프로세스 상태 보존
3. 실행 로그와 감사 이력 확보
4. 롤포워드 복구 우선 검토
5. 기존 JAR과 스키마 호환성 확인
6. 필요 시 사용자 승인 후 복원

감사 이력 DELETE를 일반적인 롤백 명령으로 사용하지 않는다.

⸻

9. 작업 순서

다음 순서로 진행하라.

개발·검증 구간 — 자율 진행

1. 운영 기준선 확인
2. Watchdog / Gateway / Tunnel 감독 기능 구현
3. Injury 정렬 결함 수정
4. Garmin 동기화 안전성 최종 검증
5. 회복 백필 실행 계획 검증
6. AI Coach / Relay E2E 검증
7. MCP Train 권한 로그 대조
8. 전체 회귀 및 PostgreSQL 통합 테스트
9. 운영 실행 Runbook 작성

여기까지는 별도 승인 없이 진행한다.

운영 활성화 구간 — 승인 필요

안전한 개발 작업이 완료되면 다음 운영 작업을 하나의 승인 요청으로 제출한다.

1. 변경 코드 운영 배포
2. Garmin 수동 동기화 1회
3. 수동 동기화 후 24시간 관측
4. 조건 충족 시 자동 스케줄러 활성화
5. 활성화 후 최소 24시간 관측
6. 조건 충족 시 회복 데이터 백필
7. 최종 데이터 신선도 및 분석 검증
8. FR265 실기기 확인

사용자는 위 단계를 한 번에 포괄 승인하거나 일부만 승인할 수 있다.

각 단계는 앞 단계가 성공한 경우에만 실행한다.

중대한 오류, 예상 밖의 데이터 변경 또는 안전 조건 위반이 발생하면 후속 단계 자동 진행을 중단한다.

⸻

10. 최종 완료 기준

RunningAI의 일상 운영을 위한 완료 조건:

* Garmin 자동 활동 수집 정상
* 실패·인증 오류·429 차단 정책 정상
* 최신 활동 반영
* 회복 데이터 coverage 검증
* RunningAI 분석 데이터 최신
* AI Coach Draft 흐름 정상
* 승인된 훈련의 Relay 전달 정상
* Garmin Calendar 조회 정상
* FR265 실기기 확인 또는 명확한 미검증 표시
* Watchdog이 핵심 프로세스 감시
* ChatGPT MCP Read / Train 검증
* 기존 부상 게이트 정상
* 기존 게시 차단 정책 유지
* 전체 테스트 PASS
* 추가 비용 없음

최종 보고

다음 내용으로 통합 보고서 한 건을 제출하라.

1. 완료 기능 및 남은 기능
2. 테스트와 E2E 검증 결과
3. 운영 데이터 신선도
4. Watchdog/Gateway/Tunnel 안정성
5. Garmin 동기화 및 회복 백필 상태
6. AI Draft / Garmin / Watchface 연결 상태
7. Git 및 Flyway 상태
8. 실제 운영 영향
9. 승인 대기 작업과 권장 순서

개발·검증 도중 작은 작업마다 STOP하지 않는다.

승인되지 않은 운영 작업은 실행하지 않고, 독립적으로 수행 가능한 작업을 계속 진행한다.

최종적으로 모든 승인 불필요 작업이 끝나면 통합 보고서를 제출하고 STOP한다.
