# WO-RUNNINGAI-PHASE-7E-3 (지시문 원문)

> 사용자 지시 원문입니다. 가공하지 않고 그대로 보존합니다.

---

Phase 7E-3 — 운영 통합 준비 및 RISK-H3 해결

RunningAI 개발 리드 역할로 진행해 줘.

Phase 7E-2 결과:

* 사본 DB 48개 활동
* V21~V26 마이그레이션 검증
* 1,385개 테스트 PASS
* ChatGPT OAuth Smoke 연결 복구 확인

목표는 운영 통합을 위한 안전한 Release Candidate를 완성하는 것이다.

STEP 1 — Git 변경사항 정리

* main-3의 미커밋 67개 경로 전체 분류
* 다른 Phase 산출물과 충돌 확인
* 코드·마이그레이션·테스트·문서를 논리적 커밋으로 정리
* 비밀키, 토큰, FIT 원본, GPS 개인정보가 커밋되지 않도록 검사
* canonical main 병합 및 origin push는 하지 말 것

STEP 2 — RISK-H3 보안 수정 준비

* 현재 PostgreSQL 포트 노출과 방화벽 상태 재확인
* Docker Compose의 5432 바인딩을 127.0.0.1 전용으로 제한하는 변경안 작성
* 운영 애플리케이션과 사본 DB 접근 영향 분석
* 변경 전 백업·복구 및 롤백 절차 검증
* DB 역할과 권한 변경은 별도 작업으로 분리
* 포트 변경은 운영 영향이 있으므로 실행 직전 STOP하고 확인받을 것

STEP 3 — V21~V26 Release Candidate

* Flyway 적용 순서 확인
* 운영 DB 백업 및 복원 검증
* 기존 V20 데이터 보존 검증
* Garmin 기존 8건 재분석
* Intervals 25건 및 Strava 15건 Import 절차
* 반복 실행 멱등성 검증
* 운영 배포 실패 시 롤백 절차 작성

STEP 4 — ChatGPT 실데이터 전환 준비

* 합성 환경 18081 유지
* 실데이터 조회 경로는 별도 환경으로 준비
* OAuth 권한과 Gateway 제한 확인
* 실제 데이터 읽기만 우선 활성화하는 전환안 작성
* 쓰기·게시 기능은 이후 Phase로 분리

STEP 5 — 완료 보고

다음 항목을 보고해 줘.

1. Git 커밋 목록 및 변경 범위
2. RISK-H3 수정 절차와 위험
3. V21~V26 배포 가능 여부
4. 과거 데이터 Import 실행 순서
5. ChatGPT 실데이터 조회 전환 방법
6. 운영 변경 시 필요한 승인 사항

중요:
운영 DB, 운영 Spring, Docker Compose, Relay, Funnel, Watchface, Watchdog는 이번 준비 단계에서 변경하지 않는다.

검증 및 문서화 완료 후 STOP한다.
