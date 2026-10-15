# Phase 8B 릴리스 안정화 — 지시문 (verbatim)

RunningAI — Release 8B 최종 안정화 및 운영 전환 준비

역할과 목표

RunningAI 시니어 백엔드 아키텍트 / DevOps 리드 / QA 책임자로서 작업한다.

Release Candidate 940d3f6은 개발 검증을 통과했다.

현재 해야 할 일은 기능을 새로 개발하는 것이 아니라, 운영 장애를 복구하고 Git 정합성과 롤백 안전성을 확보하여 Release 8B를 배포 가능한 상태로 만드는 것이다.

각 단계마다 승인 요청하지 말고 허용된 범위에서 연속 진행한다.

STEP 1 — 기존 운영 MCP 연결 복구

최우선 작업이다.

현재 보고 상태:

* Gateway 17911 미응답
* Tunnel 502
* ChatGPT OAuth Smoke 조회 도구 실패
* 운영 Spring은 기존 V28 유지

다음 순서로 진행한다.

1. Windows 실행 프로세스, 서비스, 포트 바인딩, 운영 런처 확인
2. Gateway 실제 포트와 17910/17911 설정 차이 확인
3. cloudflared 프로세스 및 원본 URL 확인
4. 운영 로그를 통해 최초 장애 지점 식별
5. 기존 검증된 런처로 Gateway와 Tunnel 복구
6. 필요 시 기존 Worker와 동일한 코드·권한·시크릿 구성을 유지한 채 ORIGIN_MCP_URL만 교체하는 제한적 재배포 1회 허용
7. 변경 전후 Worker 코드 및 바인딩을 비교하고, 기존 운영 버전 롤백 수단 확보
8. /health, PRM, 인증된 tools/list, get_athlete_profile, get_workout_draft를 읽기 전용으로 검증

제한적 Worker 재배포는 기존과 동일한 Worker 코드임을 검증할 수 있을 때만 허용한다. 단순 URL 교체 외 코드·권한·보안 설정 변경이 필요하다면 배포하지 않는다.

운영 Spring, DB, Garmin, Intervals 설정은 변경하지 않는다.

복구 실패 시 임의 우회하지 말고 안전하게 중단한다.

STEP 2 — Git 릴리스 정합성

현재 상태:

* RC: 940d3f6
* branch: phase-8b-core-completion
* base: b1feda7
* 13 local commits
* local main이 origin/main보다 28커밋 앞섬

다음 사항을 감사한다.

1. 28개 커밋 전체 내용과 생성 순서
2. 로컬 main / main-3 / feature branch 관계
3. origin/main과 운영 배포 버전의 관계
4. Phase 7D/7E/7F 변경 포함 범위
5. 미커밋 및 미추적 파일
6. 운영 Worker 진단 코드의 Git 반영 여부
7. 휘발성 Tunnel URL 및 시크릿 혼입 여부

기존 운영 기능을 누락시키지 않는 릴리스 후보를 확정한다.

28개 커밋을 무검토 상태로 일괄 push하지 않는다.

force push, reset –hard, clean 금지.

코드 수정이 필요하면 격리된 작업 브랜치에 로컬 커밋할 수 있다.

STEP 3 — V30 롤백 위험 제거

실측된 결함:

V30의 garmin_sync_attempt에 STARTED 또는 INTERRUPTED가 있으면 기존 V28 JAR가 해당 enum을 해석하지 못한다.

따라서 기존 JAR 재기동 성공만으로 롤백 PASS를 선언할 수 없다.

필수 작업:

1. V30 신규 상태의 생성 경로 조사
2. 운영 스케줄러 OFF 상태에서 실제 생성 가능성 확인
3. 이전 JAR가 신규 상태를 읽는 경로 확인
4. 데이터 손실 없이 가능한 복구 방식 우선 검토
5. 읽기 호환성 및 롤포워드 복구 절차 작성
6. 개발 DB에서 장애와 복구를 반복 실증
7. 최종 롤백 가능 조건을 명시

특히 기존 Runbook의 아래 명령을 무조건 실행하는 것은 금지한다.

DELETE FROM garmin_sync_attempt WHERE outcome IN ('STARTED','INTERRUPTED')

이는 운영 감사 이력 삭제이므로 일반적인 롤백 단계로 취급하지 않는다.

가능하면 데이터 보존과 롤포워드 방식으로 복구한다. 데이터 삭제가 불가피한 경우 백업, 영향 분석 및 별도 사용자 승인이 필요하다.

STEP 4 — 최종 통합 검증

다음을 검증한다.

* Spring H2 / PostgreSQL 전체 테스트
* Garmin Connector
* External Relay
* MCP Gateway / Worker
* Flyway V1~V30
* V28 → V30 배포 리허설
* V30에서 장애 발생 시 복구 리허설
* Garmin 차단기 정상 동작
* 게시 시도 원장 정합성
* AI Draft → Relay 호환성
* 부상 안전 게이트
* Read/Train 권한
* Publish 차단 유지
* 인증정보 및 민감정보 노출 없음

기존 검증된 테스트를 삭제하거나 비활성화하지 않는다.

STEP 5 — 운영 배포 최종 Runbook

하나의 Runbook에 다음을 정리한다.

1. 배포 대상 Git SHA 및 아티팩트 SHA
2. 기존 운영 버전
3. PostgreSQL 백업 및 복원 방법
4. V29/V30 스키마 적용
5. Spring 배포
6. Connector 배포
7. Relay 병행 비교
8. OAuth/MCP Read·Train 검증
9. 운영 상태 모니터링
10. 오류 발생 시 중단 및 복구
11. 배포 완료 판정
12. Garmin 수동 동기화 및 스케줄러 활성화의 별도 승인 지점

운영 배포는 아직 실행하지 않는다.

승인 범위

이번에 허용하는 작업:

* 기존 운영 MCP 경로 복구
* 동일 코드 기반의 URL 변경에 한정한 Worker 재배포 1회
* 개발 환경 코드 수정 및 테스트
* Git 상태 감사
* 격리된 브랜치 로컬 커밋
* 롤백 리허설
* 최종 배포 Runbook 작성

금지:

* RC 운영 JAR 배포
* 운영 Flyway V29/V30 적용
* 운영 Garmin 동기화·백필
* 실제 Intervals 게시
* OAuth publish 권한 활성화
* main merge / 원격 push
* 운영 데이터 삭제
* 신규 유료 서비스 도입

최종 보고

다음 5가지만 중심으로 보고한다.

A. 기존 ChatGPT MCP 연결 복구 결과

B. Git 28개 커밋 감사 결과와 안전한 통합 경로

C. V30 롤백 위험 해결 여부

D. 전체 테스트 및 배포 리허설 결과

E. 운영 릴리스 최종 승인 가능 여부

허용 범위의 작업은 자율적으로 끝까지 진행하고, 모든 검증이 끝나면 한 번만 통합 보고한 후 STOP한다.
