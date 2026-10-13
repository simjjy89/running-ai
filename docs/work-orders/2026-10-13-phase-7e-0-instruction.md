# WO-RUNNINGAI-PHASE-7E-0 (사용자 지시 원문)

> 아래는 사용자가 전달한 지시문을 **원문 그대로** 보존한 것이다. 편집하지 않는다.

---

WO-RUNNINGAI-PHASE-7E-0

ChatGPT 중심 RunningAI 완성 및 과거 데이터 통합

최종 목표

RunningAI를 ChatGPT에서 직접 사용하는 개인 러닝 플랫폼으로 완성한다.

사용자는 ChatGPT를 통해 데이터 조회·분석·훈련 생성·수정·승인·게시·동기화·상태 관리를 수행한다.

별도 웹 UI나 OpenAI Platform Tunnel은 사용하지 않는다.

기존 Cloudflare Worker + GitHub OAuth + MCP Gateway 아키텍처를 유지한다.

현재 기준

* ChatGPT OAuth MCP 읽기 도구 7종 E2E PASS
* 개발 Spring V23 구현 및 검증
* 운영 Spring V20 유지
* 실제 Garmin 데이터 사본 검증 완료
* ChatGPT 공개 연결은 아직 합성 데이터
* 운영 PostgreSQL 네트워크 노출 위험 RISK-H3 미해결
* 실제 데이터 외부 연결 및 운영 배포 별도 승인 필요

⸻

STEP 1 — 과거 데이터 현황 조사

다음 데이터 소스를 조사한다.

1. 운영 PostgreSQL
2. Garmin Connect API로 접근 가능한 과거 활동
3. Intervals.icu의 과거 활동·훈련부하
4. 기존 RunningAI 수집 파일 및 Raw 저장소
5. 레거시 데이터 중 마이그레이션 가치가 있는 자료

레거시 C:\running-ai는 읽기 전용 조사만 허용하고 절대 변경하지 않는다.

데이터 소스별로 확인한다.

* 최초·최종 기록 날짜
* 활동 수
* 운동 종류
* 외부 Activity ID
* Raw 원본 존재 여부
* 랩 및 샘플 보존 여부
* 회복 데이터 가용 기간
* 기존 DB와 중복된 활동
* 마이그레이션 불가능한 항목

필요하면 사용자에게 다른 과거 데이터 출처나 백업 파일 존재 여부를 확인한다.

STEP 2 — Historical Backfill 설계 및 구현

목표는 가능한 전체 운동 이력을 RunningAI PostgreSQL에 적재하는 것이다.

기존 IngestionService, ActivityRaw, GarminActivityMapper와 관련 DB 구조를 우선 재사용한다.

필수 요구사항:

* Raw-first 저장 원칙 유지
* 원본 데이터와 분석 결과 분리
* Garmin 외부 ID 기반 멱등성
* Garmin·Intervals 동일 활동 중복 방지
* 기존 활동의 더 좋은 상세 데이터 보강
* 데이터 출처 및 원본 측정일 보존
* Asia/Seoul과 UTC 변환 정확성
* 배치 단위 처리 및 진행 상태 기록
* 중단 시 이어서 실행 가능
* 429·timeout·API 실패 시 백오프
* 체크포인트를 통한 재시작
* 실패 건 재시도 및 원인 추적

기존에 정상 저장된 비NULL 상세 값을 품질이 낮은 데이터로 덮어쓰지 않는다.

Garmin이 제공하지 않는 샘플·회복 데이터를 추정 생성하지 않는다.

전체 수집 전 소량 구간으로 먼저 시험한다.

STEP 3 — 실제 데이터 사본에서 Backfill 검증

운영 DB는 변경하지 않는다.

실제 데이터 사본에 대해 다음 순서로 검증한다.

1. 기존 행 수 및 외부 ID 보존
2. 제한된 과거 기간 시험 수집
3. 동일 구간 재실행 후 중복 0건 검증
4. Garmin·Intervals 활동 매칭
5. 랩·샘플·러닝 다이내믹스 분석
6. CTL·ATL 기간별 정합성
7. 수집 중단·재개·재시도 검증
8. 수집 전후 데이터 품질 비교

가능하다면 전체 과거 기록 Backfill을 사본 환경에서 검증한다.

최종 보고에는 수집 대상 수, 성공, 중복, 실패, 미지원, 결측 건수를 구분한다.

STEP 4 — ChatGPT MCP 쓰기 기능 확장

기존 MCP 읽기 도구 7종은 유지한다.

이미 존재하는 Spring REST·서비스·도메인 로직을 재사용해 ChatGPT에서 필요한 작업을 연결한다.

목표 기능:

* Garmin·Intervals 수동 동기화 요청
* 과거 데이터 Backfill 작업 요청·진행상태 조회
* 선수 프로필 조회·수정
* 부상 상태 보고·조회
* AI Coach 분석 요청
* 훈련 Draft 생성
* 훈련 Draft 수정
* 훈련 승인
* 승인된 훈련 외부 게시
* 게시 결과·실패 조회
* 훈련 계획 목록 및 이력 관리

기능별로 기존 구현 여부를 먼저 조사한다.

이미 구현된 서비스는 MCP Adapter만 추가하고, 비즈니스 로직을 중복 작성하지 않는다.

모든 쓰기 작업은 입력 검증, 소유자 확인, 멱등성, 감사 로그, 상태 전이를 보장한다.

무제한 SQL 실행이나 임의 시스템 명령 실행 도구는 만들지 않는다.

STEP 5 — OAuth 읽기·쓰기 권한 분리

현재 runningai:read 권한을 그대로 모든 쓰기 기능에 적용하지 않는다.

읽기, 훈련 변경, 동기화, 외부 게시에 대한 세분화된 권한을 설계한다.

Cloudflare Worker가 요청자의 GitHub 사용자 ID와 허용된 scope를 검증한다.

내부 RS256 홉 토큰도 해당 작업에 허용된 권한만 포함해야 한다.

MCP Gateway는 도구별로 필요한 scope를 검증한다.

특히 다음 작업은 ChatGPT의 명시적 사용자 확인 후에만 실행되도록 한다.

* 외부 운동 게시
* 이미 게시된 운동 변경
* 대량 데이터 수정
* 삭제·초기화

운동 게시 시 Draft ID·대상·내용을 확인하고, 중복 호출로 이중 게시되지 않도록 한다.

Injury Safety Gate는 기존 정책 유지:

* ACTIVE / UNKNOWN / STALE에서 위험한 훈련 작업 차단
* 사용자의 명시적 통증 해소 보고 없이 RESOLVED 전환 금지
* 안전 게이트 우회 도구 생성 금지

STEP 6 — 상시 외부 접속 설계

기존 Quick Tunnel은 임시 테스트 경로이므로 영구 운영 방식으로 간주하지 않는다.

Cloudflare Worker의 고정 공개 주소를 유지하면서, Gateway로 이어지는 안정적인 연결을 설계한다.

기존 Tailscale Watchface Funnel은 변경하지 않는다.

필수 사항:

* 재부팅 후 연결 복구
* 터널 연결 상태 확인
* 연결 실패 시 자동 재시도
* Gateway 기동 상태 감시
* 인증 실패·권한 거부 로그
* 쓰기 작업 감사 로그
* 실제 운영 DB 외부 직접 노출 방지
* 원격 서비스 장애 시 fail-closed

RISK-H3 PostgreSQL 접근 노출은 운영 실데이터 상시 연결 전에 처리할 선행 보안 항목으로 유지한다.

단, 이번 작업에서 운영 방화벽·Docker 포트는 변경하지 않는다.

STEP 7 — ChatGPT 통합 시나리오 검증

합성 데이터와 실데이터 사본을 분리해 검증한다.

검증할 사용자 시나리오:

A. 과거 전체 러닝 기록 조회

B. 작년과 올해의 러닝 성과 비교

C. 서울레이스 하프 후반 저하 원인 분석

D. 최근 회복 상태 기반 오늘의 훈련 판단

E. 훈련 Draft 생성 및 수정

F. 승인된 훈련의 게시 준비

G. Garmin 과거 데이터 동기화 요청 및 상태 조회

H. 부상 상태 보고와 추천 차단

실제 Intervals.icu 게시 및 운영 DB 쓰기는 승인 전까지 실행하지 않는다.

STEP 8 — 최종 운영 전환 계획

다음 세 작업을 독립된 승인 항목으로 정리한다.

A. 운영 PostgreSQL 네트워크 보안 개선 및 V23 배포

B. 운영 DB에 과거 전체 데이터 Backfill

C. ChatGPT MCP 실제 데이터 읽기·쓰기 및 외부 게시 활성화

각 작업에 대해 예상 영향, 소요 시간, 데이터 검증, 롤백·복구 절차를 제시한다.

A/B/C를 사용자 승인 없이 한꺼번에 실행하지 않는다.

완료 기준

사용자가 ChatGPT에서 과거 러닝 데이터를 자유롭게 분석하고, 훈련을 생성·수정·승인·게시할 수 있도록 필요한 백엔드와 MCP 기능을 준비한다.

이번 Phase에서는 먼저 구현 현황 조사와 과거 데이터 Backfill의 사본 검증을 우선 완료한다.

이후 쓰기 기능 구현 범위와 운영 전환 승인 항목을 보고한다.

운영 Spring, 운영 DB, Watchface Relay, Tailscale Funnel, Canonical main, origin은 별도 승인 전까지 변경하지 않는다.

작업 결과를 보고하고 STOP한다.
