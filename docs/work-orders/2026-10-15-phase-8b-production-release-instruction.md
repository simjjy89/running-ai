RunningAI Phase 8B — Production Release Execution

역할

RunningAI 시니어 백엔드 아키텍트 / DevOps 리드 / QA 책임자.

Phase 8B의 개발·검증·릴리스 리허설이 완료되었다.

사용자는 기존 아키텍처를 유지하면서 추가 비용 없이 핵심 기능을 운영에 반영하려고 한다.

이번 작업은 승인된 릴리스 범위 내에서 준비, 배포, 검증 및 Git 정리까지 자율적으로 진행한다.

중간 단계마다 불필요한 승인 요청을 하지 않는다.

1. 릴리스 확정

기준 정보:

* Core RC: 940d3f6
* Release stabilization: 0fce192
* Production Spring: f1451f5 / Flyway V28
* Target Flyway: V30

필수 작업

1. 두 개발 브랜치의 관계 및 변경 사항 확인
2. Stabilization 변경을 최종 릴리스 후보에 통합
3. 중복 변경, 누락된 파일, 충돌 확인
4. 운영 Worker 코드의 Git 정합성 확인
5. 임시 Tunnel URL과 모든 시크릿 혼입 방지
6. 새 릴리스 커밋 SHA 확정
7. 해당 커밋에서 모든 테스트와 빌드 재실행

기존 RC 940d3f6을 그대로 배포하지 말고, Stabilization 변경까지 포함한 최종 릴리스 후보를 사용한다.

2. GitHub 기준선 반영

운영에서 이미 검증된 기존 코드에 한해 다음을 수행한다.

* origin/main 상태 재확인
* 로컬 main의 35개 선행 커밋과 docs-only 변경 확인
* Git 정합성 및 시크릿 검사
* --ff-only 가능 여부 확인
* 기존 운영 코드 기준선까지 origin/main 반영

force push 금지.

Phase 8B 신규 코드는 운영 배포·검증이 끝날 때까지 origin/main에 병합하지 않는다.

3. 운영 배포 전 보호

1. 현재 운영 JAR 및 체크섬 보존
2. PostgreSQL 운영 DB 백업 및 복원 가능성 확인
3. Flyway V28 현재 상태 확인
4. garmin_sync_attempt 행과 outcome 확인
5. Garmin 활동 스케줄러 OFF 확인
6. Coach Data Refresh 자동 수집 OFF 확인
7. Publish 기능 비활성 유지 확인
8. Gateway / Tunnel / Worker 정상 확인
9. 기존 Read / Train 연결 상태 확인
10. 실행 중인 셸과 테스트 프로세스가 운영에 미치는 영향 확인

V28 복귀가 가능한 상태인지 데이터 기준으로 확인한다.

백업 실패 또는 예상 외 쓰기 작업이 발생하면 배포를 중단한다.

4. Phase 8B 운영 배포

기존 통합 릴리스 Runbook을 기준으로 순차 실행한다.

Spring / DB

* Flyway V29/V30 적용
* Hibernate validate
* 최종 릴리스 JAR 배포
* 애플리케이션 기동 및 Health 검증
* 기존 Activity / Analysis / Draft 정합성 확인
* 신규 API 동작 확인
* 게시 차단 및 부상 안전 게이트 확인

Garmin Connector

* 기존 Connector 백업
* 새 Connector 배포
* Health 및 기존 읽기 API 검증
* /scheduled-workouts 실제 읽기 계약 1회 검증
* 응답 구조 불일치 시 신뢰 가능한 도달 판정 금지
* Garmin 쓰기 API 사용 금지

External Relay

* 기존 Relay 응답 스냅샷 확보
* 신규 Spring 연동을 병행 모드에서 비교
* 기존 Watchface 응답 계약 검증
* 날짜·REST DAY·미승인 Draft·오류 fallback 확인
* 결과가 정상일 때만 신규 경로로 전환
* 검증 실패 시 기존 경로 유지

MCP / Worker

* Worker 재배포는 원칙적으로 하지 않음
* 기존 OAuth Read / Train 유지
* Gateway allowlist 12개 유지
* publish_approved_draft 외부 차단 유지
* Worker → Gateway → Spring 경로 확인
* 인증된 Read/Train 호출 검증

인증된 호출이 로컬에서 불가능하면 해당 항목을 PENDING으로 기록하고, 나머지 테스트를 완료한 다음 사용자에게 한 번만 검증을 요청한다.

5. 배포 직후 관측

다음을 검증한다.

* Spring 정상 기동
* Flyway V30
* Garmin 스케줄러 OFF
* Coach Refresh 자동 수집 OFF
* 운영 Activity 및 Draft 예상 외 변경 없음
* Garmin sync attempt 신규 상태 확인
* MCP Read/Train 기능
* Relay 기존 프로토콜 호환
* 부상 안전 게이트
* Publish 차단
* 신규 ERROR 로그 및 Watchdog 이상 없음

관측 기간에는 다음 작업을 실행하지 않는다.

* Garmin 수동 증분 동기화
* Coach Data Refresh
* Garmin 스케줄러 활성화
* 회복 백필
* 실제 Intervals.icu 게시

6. 장애 및 롤백

V30에서 STARTED 또는 INTERRUPTED 행이 존재하는 경우 V28 JAR로 무조건 되돌리지 않는다.

우선 롤포워드 복구를 검토한다.

V28로 복귀하려면 다음을 확인한다.

1. 해당 신규 상태 행이 없는지 확인
2. /garmin/sync/status 호환성 확인
3. /data-freshness 호환성 확인
4. DB 백업 확보
5. 실제 롤백 가능 여부 판단

데이터 삭제가 필요한 quarantine 또는 별도의 DB 복원은 사전 승인 없이 진행하지 않는다.

운영 DB의 감사 이력을 삭제하거나 Flyway repair를 임의 실행하지 않는다.

7. 최종 Git 정리

운영 기능 검증이 통과하면:

1. 운영 배포 커밋과 테스트된 커밋 일치 확인
2. canonical main의 Fast-forward 가능 여부 확인
3. Phase 8B 변경 사항을 main에 FF 반영
4. origin/main push
5. main / origin/main / 운영 버전 정합성 검증

운영 릴리스 검증이 실패하면 Phase 8B 코드는 main에 반영하지 않는다.

8. 최종 보고

최종 통합 보고서 한 건만 제출한다.

A. 릴리스

* 최종 Git SHA
* 운영 JAR SHA
* Flyway 버전
* Connector 버전
* Relay 버전
* 배포 시각

B. 검증

* 전체 테스트 결과
* DB 무결성
* OAuth Read/Train
* Garmin 실계약
* Relay 호환성
* 안전 게이트 및 Publish 차단

C. 운영 상태

* Spring / PostgreSQL / Connector
* Gateway / Tunnel / Worker
* Watchdog
* Garmin 스케줄러 OFF
* Recovery Backfill 미실행

D. Git 상태

* main
* origin/main
* 릴리스 브랜치
* 미커밋 파일

E. 남은 승인

* Garmin 수동 동기화
* 자동 스케줄러 활성화
* 회복 백필
* 실제 워치페이스 확인

최종 지시

승인된 범위에서 배포와 검증을 연속 수행하라.

실패한 검증은 PASS로 보고하지 않는다.

운영 데이터 손실, 예기치 않은 외부 쓰기, 인증 정책 변경 등 중대한 위험이 발생하면 즉시 STOP한다.

모든 검증이 성공하면 GitHub 정합성까지 완료하고 최종 보고 후 STOP.
