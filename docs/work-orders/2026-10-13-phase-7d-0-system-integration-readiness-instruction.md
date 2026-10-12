# WO-RUNNINGAI-PHASE-7D-0 (작업지시서 원문)

전체 시스템 통합 검증 및 운영 반영 준비 점검

## 역할

RunningAI 시니어 백엔드 아키텍트 / 개발 리드 / QA 책임자.

## 목표

Phase 7B-4 및 Phase 7C-2까지 구현된 RunningAI 전체 시스템을 검토하고 운영 반영 가능 여부를 평가한다.

이번 Phase는 검증·감사·문서화 전용이다. 운영 배포 및 Git 원격 반영은 승인하지 않는다.

## 작업 환경

* Canonical: C:\running-ai-github
* 개발 Worktree: C:\Users\simjy\orca\workspaces\running-ai-github\main-3
* 레거시: C:\running-ai (절대 변경 금지)
* Watchface: C:\running-ai-watchface (변경 금지)
* Spring Boot 3.5.16 / Java 21
* Gradle 8.14.5
* PostgreSQL 17 / Flyway
* Garmin Connector / Intervals.icu
* Claude CLI AI Coach
* Tailscale Funnel / External Relay

## STEP 1 — Git 정합성 검증

다음 항목을 확인한다.

1. main, main-3, origin/main의 HEAD
2. 각 브랜치의 커밋 관계와 분기 여부
3. 미커밋 파일 및 미추적 파일
4. Phase 7B-4 커밋 50f86d0 포함 여부
5. Phase 7C-3 준비 커밋 8730f00 포함 여부
6. 누락된 작업지시서·결과보고서
7. 불필요한 임시 파일 및 테스트 산출물

Canonical main으로 FF-only 병합할 수 있는지 검토하되 실제 병합하지 않는다.

origin push도 수행하지 않는다.

## STEP 2 — 시스템 아키텍처 검증

현재 코드 기준으로 다음 전체 흐름을 점검한다.

Garmin / Intervals.icu
→ 데이터 수집
→ PostgreSQL
→ 러닝 분석
→ Running Dynamics
→ TrainingContextV2
→ AI Coach
→ Workout Draft
→ 승인 및 게시

추가로 다음 흐름을 검증한다.

ChatGPT
→ MCP Gateway
→ 읽기 전용 MCP Tools
→ RunningAI 분석 서비스

확인 사항:

* 서비스 간 책임 분리
* 트랜잭션 경계
* 중복 데이터 처리
* 재시도와 멱등성
* 외부 API 장애 대응
* 인증 및 비밀정보 관리
* 데이터 수집 신선도
* 수집 실패 후 복구 정책

## STEP 3 — DB 및 Flyway 검증

운영 DB는 변경하지 않는다.

V20에서 V23까지의 마이그레이션을 PostgreSQL 사본 DB에서 검증한다.

검증 항목:

1. V21 Injury Safety Gate
2. V22 Running Dynamics
3. V23 Lactate Threshold History
4. 기존 데이터 보존
5. 제약조건과 인덱스
6. 마이그레이션 중 장애 발생 시 대응
7. 구버전 애플리케이션과 V23 스키마의 호환성
8. 백업 및 복원 가능성

Flyway 이력 수동 삭제를 일반 롤백 방법으로 사용하지 않는다.

운영 적용 시 필요한 백업, 재기동 순서, 장애 대응 및 forward-fix 절차를 작성한다.

## STEP 4 — 핵심 기능 통합 테스트

다음 기능을 검증한다.

* Garmin 활동 수집 및 재처리
* 1Hz 샘플·랩 데이터 정합성
* 러닝 다이내믹스 분석
* 젖산역치 출처 및 변경 이력
* Intervals.icu 훈련부하
* Garmin 회복 지표
* TrainingContextV2 구성
* AI Coach 분석 및 추천
* Injury Safety Gate
* Workout Draft → Approve → Publish
* MCP 읽기 전용 도구 7종

실제 외부 게시 및 운영 데이터 변경 없이 Mock 또는 사본 DB에서 검증한다.

현재 무릎 통증이 남아 있으므로 안전 게이트를 우회하거나 RESOLVED로 변경하지 않는다.

테스트용 RESOLVED 시나리오는 격리된 테스트 데이터에서만 허용한다.

## STEP 5 — 회귀 및 품질 검증

기준 테스트: Phase 7B-4에서 보고된 1,272/1,272 PASS.

* 전체 회귀 테스트 상태 확인
* 필요한 통합 테스트만 보완
* 미검증 경로 확인
* 테스트 격리 및 재현성 검증
* 소스 수준 읽기 전용 보장의 한계 검토
* 설정 스위치 조합 검증
* 운영 환경과 개발 환경 차이 분석

Gradle 전체 테스트가 필요하면 개발 환경에서 단 한 번 실행한다.

Claude Stop Hook과 동시에 Gradle 테스트를 실행하지 않는다.

## STEP 6 — Windows 운영 안정성 검증

기존 운영 서비스를 중단하지 않고 현재 구성을 점검한다.

* RunningAI Watchdog
* Windows 작업 스케줄러
* Spring 자동 복구
* Garmin Connector 자동 복구
* External Relay 자동 복구
* 재부팅 및 로그인 후 복구 설계
* PID 및 프로세스 소유권 관리
* 포트 충돌
* 로그 파일 관리
* 중복 기동 방지

특히 CMD/PowerShell 창이 반복적으로 나타나는 현상의 원인을 읽기 전용으로 조사한다.

예약 작업을 중지·삭제하거나 설정을 변경하지 않는다.

## STEP 7 — MCP 보안 점검

Phase 7C-2의 E2E PASS 결과를 기준으로 확인한다.

* 읽기 도구 7종 정상 등록
* 쓰기 도구 비노출
* server/discover 처리
* 5xx 내부 정보 노출 방지
* 외부 REST API 차단
* OAuth Gateway 테스트
* 임시 Cloudflare Tunnel 종료 상태
* 기존 Tailscale Funnel 443 유지

Auth0 연동은 보류한다.

실제 Garmin 및 개인 회복 데이터를 인증 없이 외부 공개하지 않는다.

## STEP 8 — 운영 반영 준비 보고

다음 결과물을 작성한다.

1. 전체 시스템 구성도
2. 기능별 구현·검증 상태표
3. Git 정합성 보고서
4. DB 마이그레이션 영향 분석
5. 보안 및 장애 위험 목록
6. 운영 배포 Runbook
7. 롤백·복구 Runbook
8. 배포 전후 검증 체크리스트
9. 잔여 버그 및 기술부채
10. 최종 GO / CONDITIONAL GO / NO-GO 판단

위험 사항은 Critical / High / Medium / Low로 분류한다.

각 문제에 대해 근거, 영향, 재현 방법, 권고 조치를 기재한다.

## 금지 사항

* 운영 서버 재시작
* 운영 PostgreSQL 변경
* 운영 Flyway 적용
* 운영 Garmin Sync 실행
* Intervals.icu 실제 게시
* Tailscale Funnel 설정 변경
* Watchface 변경
* Auth0 신규 연동
* Canonical main 병합
* origin push
* 레거시 저장소 변경

## 완료 조건

실제 검증 근거를 바탕으로 RunningAI의 운영 반영 준비 상태를 판단한다.

문서는 docs/work-orders에 저장한다.

검증 중 발견한 문제가 있더라도 별도 승인 없이 운영 수정이나 대규모 리팩터링을 진행하지 않는다.

최종 결과 보고 후 STOP한다.
