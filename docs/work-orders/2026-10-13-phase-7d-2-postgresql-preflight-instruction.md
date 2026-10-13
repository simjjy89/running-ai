# WO-RUNNINGAI-PHASE-7D-2-PREFLIGHT (지시서 원문)

> 사용자가 제공한 지시문을 그대로 보존한다. 편집하지 않는다.

---

PostgreSQL 보안 조치 및 운영 배포 최종 준비

목표

Phase 7D-1의 GO 결과를 기반으로 운영 변경 항목 G(PostgreSQL 외부 노출 제한)를 최우선 검토한다.

이번 작업은 변경 전 점검과 실행계획 확정까지 수행한다. 실제 운영 설정 변경은 별도 승인받는다.

STEP 1 — PostgreSQL 구성 확인

* 현재 Docker Compose 설정 확인
* 실제 포트 바인딩 확인
* Docker Engine 버전 확인
* 운영 DB 볼륨 및 영속 저장소 확인
* Spring/Connector/Relay/Watchdog의 DB 접속 경로 확인
* 현재 방화벽 및 Tailscale 접근 범위 확인
* 운영 애플리케이션 DB 계정의 superuser 권한과 최소 권한 전환 가능성 평가

STEP 2 — 변경 및 복구 계획

127.0.0.1:5432:5432 적용에 필요한 정확한 실행 절차를 작성한다.

필수 포함 사항:

1. 운영 DB 사전 백업 및 복원 검증
2. DB 컨테이너 변경 대상·예상 중단 시간
3. 기존 데이터 볼륨 보존 방법
4. Docker Compose 서비스 단위 재생성 방법
5. Spring DB 연결 복구 확인
6. 로컬 PostgreSQL 접속 정상 확인
7. LAN 및 Tailnet IP에서 5432 접근 차단 확인
8. 실패 시 원상 복구 방법

docker compose down -v, DB 초기화, 볼륨 삭제는 금지한다.

STEP 3 — 운영 RC 확인

* RC SHA 2208872 검증
* RC JAR SHA256 검증
* main / main-3 FF-only 병합 가능 여부 재확인
* V20→V23 마이그레이션 Runbook 재확인
* MCP 읽기/쓰기 기본 OFF 확인
* 기존 Legacy Writer 4종 Disabled 확인
* Injury Safety Gate ACTIVE 적용 절차 확인

이번 작업에서 병합·배포·DB 마이그레이션은 수행하지 않는다.

STEP 4 — 최종 보고

다음을 보고한다.

* PostgreSQL 노출 경로
* 5432 제한에 따른 예상 영향
* 백업·복구 준비 상태
* 실제 변경 명령과 롤백 명령
* 예상 서비스 중단 시간
* 적용 직후 확인할 항목
* RC 운영 배포 가능 여부

검증 결과를 바탕으로 G 항목에 대한 승인 요청을 별도로 작성하고 STOP한다.

운영 DB, 운영 Spring, Funnel, Relay, Watchdog, Canonical main, origin은 변경하지 않는다.
