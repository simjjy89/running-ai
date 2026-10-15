# Integrated Release Preparation — instruction (verbatim)

RunningAI Integrated Release — 통합 릴리스 준비 작업지시서

목표

RunningAI Core Feature Completion의 6개 Workstream을 하나의 릴리스로 정리하고, 운영 환경에 안전하게 반영할 수 있도록 준비한다.

기존의 작은 Phase별 지시·보고 방식으로 돌아가지 않는다.

기존 개발 결과:

* Branch: phase-8b-core-completion
* Base: b1feda7
* 8 local commits
* Spring 1589/1589 PASS (H2, PostgreSQL)
* Flyway V29/V30
* Garmin Connector 154 PASS
* External Relay 31 PASS
* MCP Gateway / Worker PASS
* 운영 변경 및 외부 쓰기 없음

1. 통합 코드 검토

6개 Workstream의 변경 사항을 검토하라.

특히 다음을 확인한다.

* Garmin 차단기와 수동 동기화 예외
* Garmin scheduled-workouts의 미검증 계약 처리
* 게시 실패 원장의 트랜잭션 안정성
* Relay 기존 응답 형식 호환성
* 인증·권한 및 부상 게이트
* Flyway V29/V30의 하위 호환성
* 운영 설정 기본값의 안전성

결함 발견 시 격리된 개발 브랜치에서 직접 수정하고 테스트한다.

기존 검증된 기능을 재개발하지 않는다.

2. Git 정합성

다음을 확인한다.

* feature branch와 canonical main 차이
* 원격 origin 상태
* 8개 커밋의 변경 범위
* 기존 미커밋 Worker 진단 코드
* 휘발성 Quick Tunnel URL
* 미추적 Work Order 문서
* 운영 시크릿 포함 여부

운영 Worker에 배포된 코드가 Git에서 유실되지 않도록 안전한 정리 계획을 수립한다.

임시 터널 주소나 시크릿을 Git에 기록하지 않는다.

3. 통합 테스트

필요한 수정이 끝나면 다음을 실행한다.

* 전체 Spring 테스트
* PostgreSQL 17 통합 테스트
* Garmin Connector 테스트
* MCP Gateway / Worker 테스트
* Relay 테스트
* Flyway V1~V30 재현
* Read/Train 권한 및 publish 차단 회귀
* 부상 안전 게이트 회귀
* 통합 훈련 생성·승인·Relay 표시 시뮬레이션

실제 Garmin/Intervals 쓰기 없이 검증한다.

4. 운영 배포 계획

다음 배포 순서를 하나의 Runbook으로 작성한다.

1. 배포 전 서비스·DB·Git 기준선 기록
2. PostgreSQL 백업 및 복구 검증
3. V29/V30 적용 영향 확인
4. Spring 배포 및 회귀 테스트
5. Garmin Connector 배포 및 읽기 검증
6. Relay 병행 실행 및 응답 비교
7. 기존 Watchface 응답 계약 검증
8. 운영 서비스 전환
9. 재부팅·복구 및 롤백 검증

각 단계는 이전 단계가 PASS일 때만 진행하도록 한다.

Flyway 적용 후 이전 JAR로 돌아갈 수 있는지 호환성을 검증하고, DB를 무조건 되돌리는 방식은 사용하지 않는다.

5. 운영 활성화 계획

별도 운영 승인 후 아래 순서로 실행할 계획을 준비한다.

1. Garmin 수동 동기화 1회
2. 동기화 결과·DB 영향 확인
3. 24시간 안정성 관측
4. Garmin 스케줄러 활성화
5. 최초 스케줄 실행 확인
6. 24시간 이상 안정성 관측
7. 회복 데이터 onlyMissing 백필
8. 최신 TrainingContextV2 검증

401, 429, 중복 활동, 체크포인트 불일치 시 중단 기준을 명확하게 작성한다.

백필과 활동 동기화의 동시 실행을 피한다.

6. 작업 권한

자율 진행:

* 코드 리뷰
* 개발 환경 결함 수정
* 테스트 추가 및 실행
* 격리된 개발 브랜치 로컬 커밋
* 문서 작성
* 배포 리허설
* 통합 릴리스 Runbook 작성

별도 승인 필요:

* 원격 push 및 canonical main 병합
* 운영 JAR/Connector/Relay 배포
* 운영 DB 마이그레이션
* 운영 Garmin 동기화 및 스케줄러 활성화
* 운영 백필
* 실제 Intervals.icu 게시
* OAuth 권한 변경

추가 유료 서비스 도입 금지.
레거시 C:\running-ai 변경 금지.

7. 최종 산출물

하나의 통합 릴리스 보고서를 제출한다.

* 최종 변경 기능
* 발견·수정한 결함
* 전체 테스트 결과
* Git 릴리스 후보
* 운영 변경 파일 및 설정
* 데이터 마이그레이션 영향
* 배포 순서
* 롤백 계획
* 미검증 외부 의존성
* 사용자 승인 필요 사항

중간 단계마다 STOP하지 않는다.

개발 환경의 모든 검증 및 릴리스 준비를 완료한 후 사용자에게 한 번만 최종 보고한다.

운영 배포·Git push·실제 외부 쓰기 전 STOP한다.
