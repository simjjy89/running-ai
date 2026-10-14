# Phase 7F-3B-2A — Read-only Production Release (지시서 원문)

> 사용자가 제공한 작업지시서 원문. 수정 없이 보존한다.

## 0. 역할 및 승인 범위

RunningAI 시니어 백엔드 아키텍트 / 개발 리드 / 운영 배포 책임자 / QA.

Phase 7F-3B-1에서 완료한 검증 결과를 기반으로 실제 운영 배포를 수행한다.

사용자가 다음 작업을 명시적으로 승인했다.

1. 기존 운영 Spring JAR 및 배포 설정의 백업과 롤백 검증
2. main-3 → canonical main FF-only 통합
3. 최신 Spring JAR 릴리스 및 운영 Spring 안전 재시작
4. Gateway를 개발 worktree에서 독립 실행 경로로 전환
5. 운영 Gateway 안전 재시작
6. 기존 ChatGPT OAuth Read 7종 회귀 검증

위 범위 내에서는 같은 승인을 다시 요청하지 않는다.

단, 사전 조건 불충족, 백업 실패, 예상 외 코드 차이, 서비스 장애, DB 변경 위험 또는 롤백 불가능 상황이 발견되면 STOP한다.

이번 Phase의 목적은 최신 코드를 운영에 반영하면서 기존 Read-only 서비스의 동작을 그대로 유지하는 것이다.

Train/Publish 기능 활성화와 외부 서비스 쓰기는 승인되지 않았다.

## 1. 현재 기준

### Git 및 저장소

- 개발 worktree: `C:\Users\simjy\orca\workspaces\running-ai-github\main-3`
- Canonical repository: `C:\running-ai-github`
- Legacy repository: `C:\running-ai` — 변경·삭제·덮어쓰기 절대 금지
- Canonical main 기준: `799e3b5`
- main-3 배포 대상: `e76dc6b`
- main-3는 canonical main보다 5커밋 ahead, 0 behind
- FF-only 통합 가능 판정 완료
- origin push 금지

작업 시작 시 실제 Git 상태와 대상 커밋을 재검증한다.
기준 상태가 다르면 reset, rebase, force merge로 맞추지 말고 STOP한다.

### 운영 환경

- Spring: 127.0.0.1:18080
- MCP Gateway: 127.0.0.1:17910
- PostgreSQL DB: running_ai
- Flyway: V26
- 운영 활동: 48건
- 운영 Injury Report: 0건
- OAuth MCP: Read 7종
- 현재 운영 JAR 릴리스 기준: 799e3b5

### 개발 환경

- 개발 DB: running_ai_7f2
- 개발 Spring 18083: 중지 상태
- 7F-2F 실계정 Intervals CREATED 검증 완료

### 보호해야 할 외부 이벤트

- Intervals 날짜: 2026-10-16
- Event ID: 144442046
- Marker: `runningai:workout:v1:0:2026-10-16`

이 이벤트는 개발 DB에서 생성되었으며 운영 DB에는 대응하는 게시 기록이 없다.
절대로 재게시, takeover, 수정 또는 삭제하지 않는다.

## 2. STEP 1 — 배포 전 운영 상태 기록

실제 변경 전에 다음을 확인하고 배포 전 상태를 기록한다.

1. 운영 Spring PID, 기동 시각, 실행 JAR 경로
2. Gateway PID, 기동 시각, 실제 실행 server.js 경로
3. Windows Scheduled Task RunningAI-McpGateway 실행 경로
4. Spring 및 Gateway 포트
5. 운영 DB 이름 및 Flyway 버전
6. 기존 OAuth Read 7종 목록과 정상 응답
7. Train/Publish 비활성 상태
8. 운영 Injury Report 0건
9. 기존 활동 및 publication 수
10. Watchface, Relay 및 Watchdog 상태
11. 기존 Intervals 이벤트가 변경되지 않았는지 읽기 전용 확인

변경 전 기준 정보를 보고서 또는 로컬 배포 기록에 남긴다.
실제 비밀키, 토큰, 비밀번호, Authorization 헤더는 기록하지 않는다.
운영 DB에 대한 쓰기는 금지한다. 배포 전후 검증은 읽기 전용 조회를 사용한다.

## 3. STEP 2 — 기존 Spring JAR 백업 및 롤백 검증

이번 작업에서 가장 중요한 사전 조건이다.

Phase 7F-3B-1 결과에 따르면 `.runtime\released\` 디렉터리는 비어 있으며, 현재 운영 JAR을 복원할 독립 백업 자산이 충분하지 않을 수 있다.

따라서 신규 릴리스를 수행하기 전에 다음을 완료한다.

### 3.1 실행 중인 JAR 식별

- 실제 운영 PID의 실행 명령으로 사용 중인 JAR 확인
- JAR 파일 절대 경로 확인
- SHA-256 계산
- 기존 release sidecar의 커밋·해시와 비교
- 현재 JAR이 V26 운영 DB와 호환되는지 확인

경로를 추정해서 백업하지 않는다.

### 3.2 백업 생성

- 현재 실행 JAR의 바이트 단위 백업
- 현재 sidecar 백업
- 현재 Spring 실행 설정 백업
- 필요한 환경변수 이름과 값의 복원 수단 확보
- 백업 파일 및 메타데이터에 접근권한 설정
- SHA-256 일치 확인

자격증명은 Git 저장소나 배포 보고서에 노출하지 않는다.

### 3.3 롤백 가능성 검증

다음을 확인한다.

- 백업 JAR이 정상적인 JAR인지
- 백업 해시가 원본과 동일한지
- 기존 실행 스크립트에서 해당 JAR로 복귀할 수 있는지
- 해당 JAR이 Flyway V26 상태에서 기동 가능한 기존 운영 버전인지
- 이전 설정을 복원할 수 있는지
- Watchdog이나 Scheduled Task가 복원 중 최신 JAR로 다시 덮어쓰지 않는지

롤백 경로와 명령을 문서화한다.
백업 자산과 복구 절차가 검증되지 않으면 여기서 STOP한다.
운영 JAR을 교체하거나 Spring을 재시작하지 않는다.

## 4. STEP 3 — Canonical Git FF-only 통합

STEP 2가 PASS한 경우에만 진행한다.

1. canonical main HEAD가 799e3b5인지 확인
2. main-3 HEAD가 e76dc6b인지 확인
3. 양쪽 작업트리의 변경 사항 확인
4. merge-base 관계 재검증
5. 정상적인 fast-forward만 허용
6. canonical main을 e76dc6b로 FF-only 통합
7. 통합된 파일 및 커밋 확인
8. 개발 main-3 HEAD는 변경하지 않음

허용: 로컬 canonical main FF-only 업데이트

금지: merge commit 생성 / rebase / reset --hard / force / origin push / 레거시 저장소 수정

FF 통합 완료 시 canonical main과 main-3가 동일 커밋을 가리키는지 확인한다.

## 5. STEP 4 — 신규 Spring 릴리스

Canonical main e76dc6b를 기준으로 배포한다.

### 빌드 및 검증

- Java 21
- Spring Boot 3.5.16
- Gradle 8.14.5
- PostgreSQL / Flyway V26
- 예상 최대 Migration = 26
- 신규 Migration 없음
- Train MCP 6종 구현 포함
- Injury-aware Coach 포함
- Read MCP 7종 보존

기존 `scripts/windows/release-spring-jar.ps1`을 사용한다.
스크립트의 실제 인수와 동작은 실행 전 확인한다.
기존의 원자적 파일 교체, 뮤텍스, 백업, sidecar 절차를 우회하지 않는다.

### 운영 개인정보 설정

운영 환경에 아래 값을 설정한다.

`RUNNING_AI_COACH_INJURY_HISTORY_MAX_NOTES_CHARS=0`

설정이 실제 CoachTrainingContext 빌더에 적용되는지 확인한다.
자유 입력 notesExcerpt는 0자로 제한한다.
기존 구조화된 Injury History와 Injury Gate 판단은 유지한다.

설정이 적용되지 않거나 이름이 실제 구현과 다르면 임의로 유사 환경변수를 추가하지 말고 확인 후 STOP한다.

### 기능 스위치

다음 값은 반드시 OFF 유지.

- `RUNNINGAI_MCP_TRAIN_ENABLED=false`
- `RUNNING_AI_DRAFT_PUBLISHING_ENABLED=false`
- `WORKOUT_PUBLISHING_ENABLED=false`

실제 프로젝트 설정과 환경변수 바인딩을 확인한다.
새 JAR에 Train MCP 코드가 포함되어 있어도 도구는 등록되지 않아야 한다.

### 재시작

- 기존 운영 Spring의 안전한 종료 절차 사용
- Watchdog 또는 자동 재기동과 충돌하지 않도록 조율
- 승인된 릴리스 JAR 적용
- 운영 Spring 18080 재시작
- 새 PID 및 실제 JAR 해시 확인

운영 DB 데이터 변경, 새 Migration 적용, Ingestion 재실행 및 데이터 재처리는 금지한다.

Spring 기동 자체 또는 활성 스케줄러가 운영 DB를 자동 변경할 가능성이 있다면, 이를 차단할 수 있는 기존 운영 설정을 검증한 후 진행한다. 차단할 수 없다면 STOP한다.

## 6. STEP 5 — Spring Read-only 회귀 검증

Spring 재시작 직후 다음을 확인한다.

1. Spring 18080 정상
2. DB running_ai 연결
3. Flyway V26 유지
4. 활동 48건 보존
5. 기존 분석 데이터 조회 정상
6. MCP Read 7종 등록
7. Train MCP 6종 미등록
8. 게시 스위치 모두 OFF
9. 운영 Injury Report 0건
10. 운영 DB 데이터 변경 없음
11. 외부 Intervals 쓰기 없음
12. 릴리스 sidecar 및 JAR 해시 일치

Read 7종은 등록 여부만 보지 말고, 실제 데이터 조회가 가능한지 확인한다.
가능하면 다음 대표 기능을 포함한다.

- get_recent_activities
- get_training_context
- get_workout_recommendation
- get_athlete_profile

부상 이력 notesExcerpt가 운영 기본값 0자로 적용되는지도 확인한다.

### Spring 장애 시

- Gateway 전환을 진행하지 않는다.
- 검증된 이전 JAR과 설정을 복원한다.
- 기존 운영 Spring을 정상 복구한다.
- Read 7종 회귀 검증한다.
- 원인과 실제 복구 결과를 보고하고 STOP한다.

실패한 Spring 배포 상태에서 Gateway 변경을 연속 진행하지 않는다.

## 7. STEP 6 — Gateway 독립 실행 환경 준비

Spring 단계가 PASS한 후 진행한다.

현재 Gateway는 다음 경로에 의존한다.

`main-3\.runtime\mcp-gateway.cmd` → `main-3\tools\mcp-gateway\server.js` → `main-3\.runtime\logs`

개발 worktree 의존을 제거해야 한다.

### 의존성 설치

- Canonical main의 Gateway 코드 사용
- 해당 패키지의 package-lock.json 확인
- npm ci로 의존성 설치
- jose 등 필수 의존성 확인
- 실행되는 server.js의 SHA-256 기록
- 실제 Node.js 버전과 호환성 확인

개발 worktree의 node_modules를 무검증 복사하지 않는다.

### 실행 환경 분리

검증된 배포 전용 실행 경로를 구성한다.

- Gateway 코드
- 의존성
- 런처
- 로그 경로
- 스케줄러 참조 경로

필요한 설정 파일의 상대경로 참조도 확인한다.
사용자 또는 운영 환경에 필요한 자격증명은 기존 값을 안전하게 보존한다.
OAuth 관련 환경변수를 임의 재생성하지 않는다.
로그에 토큰을 출력하지 않는다.

### 17911 선검증

별도 테스트 포트 127.0.0.1:17911에서 Gateway를 기동한다.

- 기존 운영 Gateway 17910은 계속 유지
- 실계정 OAuth 설정은 보호
- Read 7종 정상
- Train/Publish 미노출
- 무토큰·위조 토큰 거부
- scope 정책 정상
- allowed tools 기본값 Read 7종 확인

포트 변경으로 callback URL, audience, issuer, 리버스 프록시 라우팅이 달라지는 문제가 없는지 확인한다.
테스트 환경에서 실제 OAuth 설정 변경이 필요하다면 운영 값을 변경하지 않는다.
17911 테스트 결과가 충분하지 않으면 Gateway 전환을 하지 않고 STOP한다.

## 8. STEP 7 — Gateway 스케줄러 경로 전환

STEP 6이 PASS하고 롤백 경로가 확인된 경우에만 진행한다.

### 변경 전 보호

- 기존 Scheduled Task 정의 내보내기
- 기존 Execute 경로 기록
- 기존 런처 파일 백업
- 실행 계정·권한·트리거·작업 디렉터리 확인
- 기존 로그 경로 확인
- 환경변수 7개의 실제 공급 경로 확인
- 이전 스케줄러 Execute 복원 절차 준비

### 실제 전환

- 기존 Gateway를 정상 종료
- 스케줄러 Execute를 독립 실행 경로로 변경
- 새 Gateway를 운영 포트 17910으로 시작
- 실행 PID 및 파일 경로 확인
- 개발 worktree 참조가 남아 있지 않은지 확인
- 로그 파일 정상 기록 확인
- 기존 GitHub OAuth 연결 유지

작업 스케줄러의 실행 계정, 인증정보, 불필요한 트리거를 변경하지 않는다.

### 회귀 검증

- 기존 OAuth Read 권한 정상
- Read MCP 7종 정상
- 기존 연결 토큰으로 조회 가능
- 무토큰 401
- 권한 부족 403
- Train/Publish 도구 미노출
- OAuth Worker 설정 변경 없음
- Gateway 오류 로그 없음

기존 연결이 깨지거나 인증 동작이 달라졌다면 즉시 이전 Execute 경로를 복원한다.
롤백 후 17910과 Read 7종이 복구되었는지 확인하고 STOP한다.

## 9. STEP 8 — 운영 최종 검증

Spring과 Gateway가 모두 정상 전환되면 전체 시스템을 확인한다.

### 서비스

- Spring 18080 UP
- MCP Gateway 17910 UP
- 운영 PostgreSQL 정상
- Flyway V26 유지
- 기존 ChatGPT OAuth Read 도구 정상
- Train/Publish OFF

### 데이터

- 운영 활동 48건 유지
- 기존 Draft/Approval/Publication 수량 유지
- Injury Report 0건 유지
- 운영 DB 쓰기 없음
- 개발 DB 무변경

### 외부 연동

- Intervals 이벤트 144442046 유지
- 2026-10-16 캘린더 추가 게시 없음
- 신규 외부 쓰기 0건
- Garmin 직접 쓰기 없음
- Watchface/Relay 기능 정상

### 운영 안정성

- Gateway 실행 파일은 개발 worktree에 의존하지 않음
- Spring 릴리스 provenance 확인
- JAR 백업 및 롤백 자산 보존
- Gateway 기존 실행 경로 복원 가능
- Watchdog과 자동 재시작 동작 이상 없음

검증은 가능한 범위에서 읽기 전용으로 수행한다.

## 10. STEP 9 — 단계별 실패 처리

- 백업 실패 → 릴리스 실행하지 않고 STOP.
- Git FF 불가능 → 병합하지 않고 STOP.
- Spring 빌드·릴리스 실패 → 운영에 적용하지 않고 STOP.
- Spring 재시작 후 장애 → 기존 JAR 및 설정 복구, Read 도구 확인 후 STOP.
- Gateway 의존성 설치 실패 → 기존 Gateway를 유지하고 STOP.
- Gateway 17911 검증 실패 → 17910을 변경하지 않고 STOP.
- Gateway 전환 실패 → 기존 스케줄러 Execute와 런처 복원, 정상 기동 확인 후 STOP.
- 운영 데이터 또는 외부 캘린더 변경 감지 → 원인 및 영향 범위를 조사하되 자동 데이터 수정·삭제는 하지 않고 STOP.
- 롤백 불가능 → 더 이상 변경하지 않고 현재 상태와 복구에 필요한 사항을 보고한다.

실패를 숨기기 위해 테스트 결과나 상태값을 조작하지 않는다.

## 11. 금지 사항

### 운영 기능

- Train MCP 활성화
- Publish MCP 활성화
- `RUNNING_AI_ALLOWED_TOOLS`에 쓰기 도구 추가
- OAuth Worker 코드 수정·배포
- OAuth 지원 Scope 확대
- 사용자 OAuth 재승인 요구

### 운영 DB

- Injury Report INSERT
- 기존 데이터 UPDATE/DELETE
- 개발 DB Injury Report 복사
- 개발 DB Draft/Approval/Publication 복사
- 새 마이그레이션 적용
- 운영 활동 재처리 및 재수집 실행

### 외부 서비스

- Intervals 이벤트 생성·수정·삭제
- 2026-10-16 이벤트 takeover
- 추가 훈련 게시
- Garmin 직접 게시 또는 설정 변경

### 코드 및 저장소

- origin push
- reset/rebase/force
- 레거시 저장소 변경
- Watchface/Relay 코드 변경
- 관련 없는 리팩터링
- 테스트를 통과시키기 위한 안전 정책 완화

## 12. 완료 보고

### A. Git

- canonical main 최종 HEAD / main-3 HEAD / FF-only 통합 여부 / origin push 여부 / 작업트리 상태

### B. Spring

- 기존 JAR 경로·SHA-256 / 백업 경로 및 검증 결과 / 신규 JAR SHA-256 / release sidecar /
  Spring 재시작 결과 / 실제 서비스 중단 시간 / Read 7종 검증 결과

### C. Gateway

- 기존 실행 경로 / 신규 실행 경로 / npm ci 결과 / 17911 검증 결과 / 17910 전환 결과 /
  기존 OAuth 연결 정상 여부 / 롤백 경로 검증

### D. 보안

- Train/Publish 비활성 / OAuth Worker 변경 없음 / Injury History notesExcerpt=0 /
  무토큰·권한 부족 테스트 결과

### E. 데이터 및 외부 영향

- 운영 DB 변경 건수 / 활동 및 기존 데이터 수량 / 운영 Injury Report 수 /
  Intervals 신규 쓰기 횟수 / 기존 이벤트 144442046 유지 여부 / Watchface/Relay 상태

### F. 최종 판정

PASS / PARTIAL / STOP 중 하나.
실패한 경우 해당 단계, 롤백 실행 결과, 잔여 위험을 명확하게 기재한다.

## 13. 완료 기준

PASS 조건

1. 기존 Spring JAR 백업 및 복구 가능성 검증
2. canonical main FF-only 통합
3. 최신 e76dc6b JAR 운영 반영
4. Spring 18080 정상
5. Gateway 독립 실행 경로 전환
6. Gateway 17910 정상
7. OAuth Read 7종 유지
8. Train/Publish 비활성
9. 운영 DB 데이터 무변경
10. 외부 Intervals 신규 쓰기 0건
11. Watchface/Relay 정상
12. 롤백 자산 확보

작업 완료 후 STOP한다.

이번 배포가 성공하더라도 Phase 7F-3B-2B의 운영 Injury Report 등록, Train MCP 활성화, Worker OAuth Scope 변경을 자동 진행하지 않는다.

장기 아키텍처는 Modular Monolith + Single-Tenant Self-hosted를 유지한다.
