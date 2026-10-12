# WO-RUNNINGAI-PHASE-7D-1 (작업지시서 원문)

## 운영 반영 전 안정화 및 Release Candidate 준비

### 목표

Phase 7D-0의 CONDITIONAL GO 결과를 기반으로 RunningAI V23의 안전한 운영 배포를 준비한다.

운영 배포 자체는 수행하지 않는다.

### STEP 1 — 감사 결과 보존

1. Phase 7D-0의 instruction/result 문서 검토
2. 미추적 파일 6건의 목록과 필요 여부 확인
3. 비밀정보 및 테스트 산출물 혼입 여부 점검
4. 승인된 문서만 main-3에 커밋
5. Git 정합성 및 FF-only 병합 가능 여부 재확인

origin push와 Canonical main 병합은 금지한다.

### STEP 2 — 운영 보안 점검

#### PostgreSQL

- Docker 포트 5432의 바인딩 범위 확인
- Windows 방화벽 규칙 확인
- LAN 내 외부 장치 접근 가능 여부 확인
- PostgreSQL 인증 및 pg_hba.conf 확인
- localhost 제한 적용 가능성과 기존 의존 서비스 영향 평가

실제 방화벽과 포트 설정은 변경하지 않는다.

#### MCP

- RUNNINGAI_MCP_READ_ENABLED=false 유지
- 기존 쓰기 MCP 비활성 유지
- raw Spring /mcp의 외부 노출 차단 검증
- Gateway의 미지원 메서드 및 5xx 처리 확인
- OAuth 미설정 상태에서 개인 데이터 외부 공개 금지

### STEP 3 — Windows Watchdog 콘솔 문제

Phase 7D-0에서 확인한 5분 간격 CMD 창 발생 원인을 개선한다.

1. Scheduled Task 부모 프로세스와 cmd 자식 실행 관계 분석
2. 기존 Watchdog의 ProcessStartInfo 및 창 숨김 방식 검토
3. UI 없는 백그라운드 실행 방안 설계
4. 대안별 자동 복구 권한 및 환경변수·로그 영향 검증
5. 격리된 테스트 환경에서 콘솔 창 미표시 검증

운영 작업 스케줄러와 Watchdog은 변경하지 않는다.

기존 자동 복구 기능을 잃는 해결책은 채택하지 않는다.

### STEP 4 — 운영 배포 구성 고정

Phase 7B-4 및 7C-2까지의 운영 대상 기능을 확정한다.

다음 설정을 명시한다.

- Injury Safety Gate 활성
- MCP 읽기 기본 OFF
- MCP 쓰기 및 Workout Publisher 스위치 정책 확인
- Legacy Writer 4종 Disabled
- Garmin/Intervals 스케줄러 설정 유지
- 외부 Relay 및 Watchface 연동 유지

운영 환경변수 전체를 점검하고 .env.example의 누락 항목을 보완한다. 실제 자격증명은 추가하지 않는다.

### STEP 5 — Release Candidate 검증

운영이 아닌 개발 환경에서 다음 절차를 검증한다.

1. 대상 Git SHA 확정
2. clean build
3. 빌드 JAR의 SHA256 및 포함 클래스·리소스 확인
4. V21/V22/V23 Flyway 파일 포함 여부 확인
5. PostgreSQL 사본 DB로 개발 서버 기동
6. 핵심 API 및 조회 도구 점검
7. 기존 기능 회귀 검증
8. 배포 후 부상 상태 보고 절차 검증
9. 장애 시 코드 롤백 및 데이터 복구 절차 확인

구버전 코드가 V23 스키마에서 실제로 재기동 가능한지도 검증한다.

Flyway 이력을 수동으로 삭제하는 롤백 절차는 사용하지 않는다.

### STEP 6 — 배포 승인 체크리스트

운영 반영 시 반드시 승인받을 항목을 구분한다.

A. main-3 → Canonical main 병합

B. 운영 PostgreSQL 백업 및 Flyway V21~V23 적용

C. 운영 Spring 재빌드·재기동

D. 부상 상태 ACTIVE 입력 및 안전 게이트 검증

E. Watchdog 및 외부 Relay 정상 상태 검증

F. 원격 Git push 여부

이번 Phase에서는 A~F 중 어느 것도 실제 실행하지 않는다.

### 최종 보고

- 모든 위험 항목의 해결 여부
- 변경 파일 및 커밋
- 운영 배포용 Git SHA
- JAR 검증 결과
- DB 마이그레이션 및 복구 검증 결과
- 배포 중단 조건
- 배포 예상 영향 및 다운타임
- 최종 GO / NO-GO

완료 후 STOP한다.
