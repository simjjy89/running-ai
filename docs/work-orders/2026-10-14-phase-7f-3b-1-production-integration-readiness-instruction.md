# Phase 7F-3B-1 — Production Integration Readiness (지시 원문)

> 사용자가 제공한 지시를 **원문 그대로** 보존한다. 편집/요약하지 않는다.

RunningAI Phase 7F-3B-1 — Production Integration Readiness

역할

RunningAI 시니어 백엔드 아키텍트 / 개발 리드 / QA.

현재 기준

* Phase 7F-3A 완료
* main-3 HEAD: e76dc6b
* main-3가 canonical main보다 5커밋 앞섬
* 서버 테스트 1,442 PASS
* MCP Gateway 테스트 46/46 PASS
* Intervals 실계정 CREATED 검증 완료
* 운영 Spring JAR에는 7F-1/3A 미반영
* 운영 MCP Read 7종만 활성
* 운영 Injury Report 0건
* Gateway가 main-3 worktree의 server.js에서 실행 중

목표

RunningAI의 ChatGPT Train/Publish 기능을 운영에 안전하게 연결하기 위한 마지막 배포 준비를 완료한다.

이미 검증된 기능을 재구현하지 않는다.

이번 단계에서는 운영 서비스 변경을 실행하지 않는다.

STEP 1 — Git 통합 검증

* main-3, canonical main, origin 상태 확인
* 5개 선행 커밋과 작업트리 상태 확인
* FF-only 병합 가능 여부 확인
* 비밀키 및 운영 환경설정 혼입 여부 확인
* 정식 릴리스 대상 커밋 식별

작업트리의 미추적 문서는 임의 삭제하지 않는다.

canonical main 병합과 origin push는 이번 단계에서 실행하지 않는다.

STEP 2 — Spring 릴리스 검증

* Java 21 / Spring Boot 3.5.16 유지
* 운영 DB Flyway V26 호환성 확인
* 최신 Injury History 코드 포함 확인
* Train MCP 6종 포함 확인
* 운영 기본 설정에서 기존 Read 7종 유지 확인
* Train 기능 기본 OFF 확인
* Draft Publishing 기본 OFF 확인
* release-spring-jar.ps1의 빌드·백업·롤백 흐름 확인

실제 운영 JAR 교체 및 Spring 재시작은 실행하지 않는다.

STEP 3 — Gateway 운영 경로 안정화 계획

현재 운영 Gateway가 main-3 worktree의 server.js를 사용하는 문제를 해결할 배포 방식을 확정한다.

요구사항:

* 개발 worktree와 운영 실행 파일 분리
* 검증된 릴리스 버전 사용
* 기존 PID·포트·서비스 설정 보존
* Gateway 재시작 시 Read 7종 회귀 검증
* 기존 OAuth 연결 유지
* 이전 릴리스로 롤백 가능
* 자격증명과 환경변수 보호

운영 경로 변경이나 프로세스 재시작은 아직 하지 않는다.

STEP 4 — Injury History 개인정보 보호

현재 구조화된 Injury History가 운영 Read MCP에 포함될 수 있음을 확인한다.

운영 기본 정책:

* 최신 부상 상태 및 안전 판단 정보 유지
* 자유 입력 notesExcerpt 기본 0자
* 민감정보 불필요한 로그 출력 금지
* 기존 인증된 사용자에게만 데이터 제공
* 추후 Self-hosted 인스턴스별 독립 설정 가능

이 정책을 운영 배포 계획에 반영한다.

STEP 5 — OAuth Scope 및 MCP 검증

기존 구현을 기준으로 확인한다.

* runningai:read
* runningai:train
* runningai:publish

필수 사항:

1. Read scope만으로 쓰기 도구 접근 불가
2. Train scope로 외부 게시 불가
3. Publish scope만으로 Draft 생성 불가
4. allowlist와 scope를 모두 통과해야 도구 호출 가능
5. 기존 read-only OAuth 연결의 권한 자동 확대 금지
6. 승인되지 않은 Draft 게시 차단
7. 무토큰·위조 토큰·권한 부족 차단
8. tools/list에서 미승인 도구 비노출
9. 기존 publish_workout 금지 유지
10. ChatGPT에서 별도 승인 없이 외부 게시하지 않도록 흐름 유지

OAuth Worker 배포 및 운영 Scope 확대는 실행하지 않는다.

STEP 6 — 운영 Injury Report 처리 계획

운영 DB에는 Injury Report가 0건이다.

개발 DB의 Report #1~#3을 임의 복사하지 않는다.

운영 전환 시 사용자의 명시적 동의 아래 정상 Injury Report API로 최신 자기 신고를 기록할 절차를 준비한다.

* 실제 신고 값만 기록
* 기존 부상 이력 보존 원칙 유지
* 실제 보고 시각 사용
* 48시간 freshness 유지
* Injury Gate 정상 검증

이번 단계에서는 운영 DB에 어떤 기록도 추가하지 않는다.

STEP 7 — 외부 게시 중복 방지

Intervals 계정 i6***48에는 이미 다음 이벤트가 존재한다.

* Date: 2026-10-16
* Event ID: 144442046
* Marker: runningai:workout:v1:0:2026-10-16

이 이벤트는 개발 DB에서 게시되었으며 운영 DB에는 게시 이력이 없다.

따라서 운영 전환 과정에서:

* 해당 날짜 자동 게시 금지
* 기존 이벤트 takeover 금지
* 게시 기록 임의 복사 금지
* 기존 이벤트 삭제·수정 금지

새로운 실게시 테스트를 위해 다른 날짜를 사용하더라도 반드시 사용자 승인부터 받는다.

STEP 8 — 운영 배포 및 롤백 계획

다음 순서의 실행 가능한 계획을 만든다.

1. Git FF 통합
2. Canonical 릴리스 빌드
3. 운영 JAR 안전 교체 및 Spring 재시작
4. 기존 Read 7종 회귀 확인
5. Gateway 운영 실행 파일 경로 분리
6. 운영 Gateway 안전 재시작
7. Train MCP 활성화
8. 운영 Injury Report 정상 등록
9. OAuth Worker Train Scope 지원
10. ChatGPT 연결 재승인
11. Draft 생성·조회·승인 검증
12. 별도 승인 후 Publish Scope 활성화
13. 실제 게시 확인
14. Garmin 연결 확인

실제 배포 과정에서 단계별 롤백이 가능하도록 구체적인 실행 조건을 명시한다.

운영 변경은 아직 하지 않는다.

STEP 9 — 테스트

* 기존 전체 서버 테스트
* Gateway 테스트
* Train MCP 권한 테스트
* Injury-aware Coach 테스트
* Read Tool 회귀 테스트
* 테스트 환경의 Draft → Approval → Preview E2E

외부 Intervals 실제 게시를 반복하지 않는다.

STEP 10 — 완료 보고

다음을 간결히 보고한다.

1. FF-only 병합 가능 여부
2. 배포 대상 커밋
3. Spring 릴리스 검증 결과
4. Gateway 경로 변경 계획
5. Injury History 개인정보 설정
6. OAuth scope별 활성화 계획
7. 전체 테스트 결과
8. 운영 배포 시 예상 중단 시간
9. 단계별 롤백 방법
10. 운영 실행 전 필요한 사용자 승인

금지 사항

* canonical main 병합
* origin push
* 운영 Spring/Gateway 재시작
* 운영 DB 변경
* 운영 Injury Report 생성
* OAuth Worker 배포
* OAuth Scope 확대
* 실제 Intervals 게시·수정·삭제
* 기존 게시 기록 조작
* Watchface·Relay 변경
* 레거시 저장소 변경
* 관련 없는 리팩터링

작업 완료 후 결과를 보고하고 STOP한다.

추가적인 분석 Phase를 만들지 않는다. 다음 단계는 실제 운영 활성화 7F-3B-2가 되어야 한다.

장기 아키텍처는 Modular Monolith + Single-Tenant Self-hosted를 유지한다.
