# WO-RUNNINGAI-PHASE-6I-1.7D-3 — Controlled PC Reboot & Automatic Runtime Recovery E2E (작업 지시서 원문)

## 1. 목표

실제 Windows PC 재부팅 후 다음 구성요소가 운영자의 수동 Start 없이 자동 복구되는지 검증한다.

* Docker Desktop
* PostgreSQL 17
* Garmin Connector
* Spring Boot 3.5.16
* External Relay
* Tailscale Funnel
* RunningAI-Startup
* RunningAI-Watchdog
* Garmin FR265 외부 접근

이번 Phase에서는 기존 운영 설정을 변경하지 않는다.

재부팅은 Preflight와 백업 준비를 모두 통과한 뒤, 사용자에게 별도로 승인받아 사용자가 직접 실행한다.

## 2. 기준 환경

Canonical:
C:\running-ai-github

Development:
C:\Users\simjy\orca\workspaces\running-ai-github\main-3

Legacy:
C:\running-ai — 접근 및 변경 금지

Expected:

* Canonical main: 43ed6af2049e1564fb35b76dbd8e22d99774ec19
* origin/main: f6a213588c5b7855b0dc7bf99948896291240ed7
* main-3/origin/main-3: fe23684df51ac20c0e755a6f185ef6c34919c77a

재부팅 전 서비스 PID는 기존 보고서를 참고만 한다. 작업 시작 시 실제 PID와 CreationDate를 새 기준선으로 확보한다.

### STEP A — 재부팅 전 Preflight

읽기 전용으로 다음 항목을 검사한다.

1. Canonical Git HEAD/Working Tree.
2. Docker daemon 상태.
3. PostgreSQL Container ID/Health/Volume.
4. Spring 18080 Health.
5. Connector 8765 Health 및 소유권.
6. Relay 17845 Health.
7. Tailscale 서비스 및 Funnel 구성.
8. Public HTTPS /health.
9. 인증된 /today-workout 정상 응답.
10. RunningAI-Startup Scheduled Task의 Action/Trigger/Principal.
11. RunningAI-Watchdog Enabled 및 최근 자동 실행 결과.
12. watchdog-state.json version 2.
13. Restart Budget 및 LongTerm Lockout.
14. 현재 운영 서비스 PID/CreationDate.
15. DB의 activity 건수 및 garmin_sync_state.
16. 운영 .env, 토큰, 세션 파일 존재 여부.

민감한 파일 내용은 출력하지 않는다.

하나라도 비정상이면 STOP하고 원인을 보고한다.

### STEP B — 안전한 데이터 백업

재부팅 전에 반드시 수행한다.

PostgreSQL

* 기존 Docker Volume 이름과 데이터 경로를 기록한다.
* PostgreSQL 17에 호환되는 pg_dump 방식으로 RunningAI 데이터베이스의 일관된 논리 백업을 생성한다.
* 기존 운영 환경의 인증 방식과 DB명을 안전하게 확인하되 비밀번호를 출력하거나 명령줄 인수에 노출하지 않는다.
* 백업을 Git 저장소 밖의 ACL로 보호된 디렉터리에 저장한다.
* 백업 크기, SHA256, 생성 시각을 기록한다.
* 복원에 필요한 PostgreSQL 버전과 절차를 문서화한다.
* 백업 형식 검사를 수행하여 읽을 수 있는 백업인지 확인한다.
* 실제 운영 DB에 복원을 시도하지 않는다.

Watchdog

* watchdog-state.json v2 및 .bak 백업.
* 기존 v1 스냅샷 유지.
* 파일 무결성 검사.
* 장기 Lockout/Restart History 기록.

애플리케이션 설정

* .env 및 필요한 Relay/Garmin 비밀정보 파일 보존 상태 확인.
* 민감정보의 원문을 보고서에 기록하지 않는다.
* 기존 설정 파일을 삭제하거나 덮어쓰지 않는다.

백업 실패 시 NO-GO.

### STEP C — 재부팅 직전 기준선 고정

다음 정보를 타임스탬프와 함께 기록한다.

| 구분 | 비교 기준 |
|---|---|
| Git | Canonical HEAD |
| PostgreSQL | DB명, Volume, 데이터 기준선 |
| Activity | 행 수, 최신 Activity 식별값 |
| Garmin Sync | HighWater / LastSync |
| Connector | Launcher/Listener PID, Ownership |
| Spring | PID, Health |
| Relay | PID, Health |
| Tailscale | 서비스 상태, Funnel 대상 |
| Watchdog | LastRunTime, State v2, Lockout |
| API | Local/Public HTTP 상태 |

기준선 파일에는 토큰·비밀번호를 포함하지 않는다.

재부팅 뒤 프로세스 PID가 달라지는 것은 정상적인 결과이며, PID가 동일해야 한다는 조건으로 판정하지 않는다.

### STEP D — 재부팅 승인 게이트

Preflight, 백업, 기준선 기록이 모두 PASS이면 다음 상태로 보고한다.

REBOOT_PRECHECK=GO

이때 작업을 일단 STOP한다.

사용자가 실제 재부팅을 승인할 때까지 다음 명령을 실행하지 않는다.

* Restart-Computer
* shutdown /r
* Windows 로그오프
* Docker Desktop 재시작
* Scheduled Task 수동 실행

사용자가 미저장 작업을 정리하고 직접 Windows를 재부팅하도록 한다.

재부팅 전 Claude Code 프로세스는 중단될 수 있으므로, 기준선과 복구 절차는 디스크에 문서로 저장한다.

### STEP E — 재부팅 후 자동 기동 검증

사용자가 재로그인한 뒤, 저장된 기준선을 읽고 검증을 재개한다.

수동 Start를 먼저 실행하지 않는다.

먼저 다음 순서를 관찰한다.

1. Windows 로그인 시각 확인.
2. RunningAI-Startup 실행 기록.
3. Docker Desktop 기동.
4. PostgreSQL HEALTHY.
5. Connector UP.
6. Spring UP.
7. External Relay UP.
8. Tailscale Funnel 연결.
9. RunningAI-Watchdog 자동 실행 재개.

각 구성요소의 실제 기동 시각과 완료 시각을 기록한다.

정상 기동 중인 서비스를 조기에 실패로 오판하지 않도록 상태를 단계적으로 관찰한다.

로그인 직후, 약 5분, 10분, 15분 시점에 상태를 비교한다. 이는 예상 검증 시점이며 보장된 기동 시간은 아니다.

### STEP F — Watchdog 자동 실행 검증

다음 조건이 필요하다.

* RunningAI-Watchdog Enabled.
* 실제 신규 LogonTrigger 발생.
* 약 5분 지연 후 최초 실행.
* 이후 약 5분 반복.
* 연속 3회 이상 LastTaskResult=0.
* watchdog-status.json checkedAt 갱신.
* 구성요소 상태 UP.
* 예상하지 못한 Recovery Action 없음.
* Restart Budget 비정상 증가 없음.
* 장기 Lockout 오탐 없음.
* State v2 및 .bak 유효.

자동 기동 과정의 실제 장애를 Watchdog가 복구했다면 Action/Result/Component/PID를 명확하게 기록하고, 정상 복구인지 별도 판정한다.

정상 기동으로 UP이 됐다면 Watchdog가 복구했다고 주장하지 않는다.

### STEP G — DB 영속성 확인

재부팅 전후 다음을 비교한다.

1. DB 접속 성공.
2. activity 행 수.
3. 최신 Activity 기준값.
4. garmin_sync_state HighWater.
5. last_successful_sync_at.
6. Flyway schema history.
7. 테이블 및 중요 제약조건.
8. PostgreSQL Volume 일치 여부.

예상하지 못한 데이터 손실이나 스키마 변경이 없어야 한다.

재부팅 후 자동으로 데이터 동기화가 발생했다면, 합법적인 추가 데이터와 데이터 손실을 구분해서 보고한다.

단순히 행 수가 다르다는 이유만으로 무조건 실패 처리하지 않는다.

### STEP H — External Relay 및 기기 검증

Local

* Spring /actuator/health 200.
* Connector /health 200.
* Relay /health 200.
* 인증된 /today-workout 200.
* 무인증 요청 401.

Public

* Tailscale Funnel 연결 확인.
* HTTPS /health 200.
* 인증된 /today-workout 200.
* Local/Public 응답 일관성 확인.

LTE/5G

가정 Wi-Fi가 아닌 별도 모바일 네트워크에서 사용자 직접 확인.

* 외부 HTTPS 접근.
* 인증 API 동작.
* 재부팅 후 Funnel 재연결 상태.

Garmin FR265

* Watchface의 Relay 연결 상태 확인.
* 오늘 훈련 데이터 표시 확인.
* 캐시 정보와 최신 응답 구분.
* 재부팅 후 동기화 주기 정상 여부 확인.

Intervals.icu에 실제 훈련 데이터가 없거나 API 키가 유효하지 않다면 해당 조건을 미충족으로 기록한다.

NO_PLAN 응답은 API 실패가 아니지만, 실제 훈련 데이터 E2E 성공의 증거도 아니다.

### STEP I — 장애 시 안전 대응

다음 상황에서는 검증을 중단하고 진단 보고한다.

* PostgreSQL 데이터 불일치 또는 손상
* 외부 프로세스에 대한 소유권 오판
* 비정상적인 강제 종료
* 중복 Connector/Spring/Relay 기동
* Watchdog 무한 재시작 시도
* 장기 Lockout 오작동
* 민감정보 파일 소실

서비스가 자동 복구되지 않는 경우, 로그와 원인을 먼저 수집한다.

원인 규명 전에 무작정 반복 재시작하거나 State/Volume을 초기화하지 않는다.

수동 복구 또는 롤백은 현재 운영 상태와 영향 범위를 정리한 후 별도 승인받는다.

### STEP J — 최종 보고

다음 18개 항목을 기록한다.

1. 재부팅 전 Git SHA.
2. Preflight 결과.
3. PostgreSQL 백업 파일 및 검증 결과.
4. Watchdog State 백업 결과.
5. 재부팅 승인 여부.
6. Windows 재부팅/로그인 시각.
7. RunningAI-Startup 자동 실행 결과.
8. Docker Desktop 자동 기동 결과.
9. PostgreSQL Health 및 Volume.
10. Connector 소유권 및 Health.
11. Spring Boot Health.
12. External Relay Health.
13. Tailscale Funnel 재연결.
14. Watchdog 3회 이상 자동 실행.
15. DB 영속성 및 Garmin Sync 기준선.
16. Public LTE/5G 검증.
17. Garmin FR265 표시 검증.
18. 남은 위험과 E2E GO/NO-GO.

## Git 및 작업 범위

결과 문서는 개발 Worktree main-3에 작성하고 별도 커밋한다.

기존 Canonical 코드, origin/main, 운영 설정을 변경하지 않는다.

다음은 금지한다.

* 고의적인 장애 주입
* PostgreSQL Volume 삭제·초기화
* 데이터베이스 스키마 변경
* Watchdog 재등록 또는 설정 변경
* 강제 Git Reset/Force Push
* 실제 Garmin 훈련 생성·게시
* 레거시 저장소 접근

재부팅 성공만으로 PHASE_6I_1_EXTERNAL_ACCESS_E2E_READY를 선언하지 않는다. Intervals.icu 실데이터, FR265 및 모바일 외부망 검증 결과를 포함해 최종 판단한다.
