# WO-RUNNINGAI-PHASE-6I-1.1-E2E-P1 — Intervals.icu → External Relay → Garmin FR265 Read-Only Validation (작업 지시서 원문)

## 1. 작업 목표

PC 재부팅 없이 RunningAI의 실제 외부 데이터 전달 경로를 검증한다.

검증 대상:

Intervals.icu API → External Relay → Tailscale Funnel → Garmin FR265

특히 인증 성공, 실제 훈련 존재 여부, Relay 변환 결과, Garmin 표시 상태를 구분한다.

이번 Phase는 읽기 전용 점검이다. 실제 훈련 생성·수정·게시, API 키 재발급, 운영 서비스 재시작은 금지한다.

기존 REBOOT_PRECHECK=GO 상태와 Phase 6I-1.7D-3 재부팅 보류 결정은 유지한다.

## 2. Git 및 환경 기준

Canonical:
C:\running-ai-github

Development:
C:\Users\simjy\orca\workspaces\running-ai-github\main-3

Expected:

* Canonical main: 43ed6af2049e1564fb35b76dbd8e22d99774ec19
* origin/main: f6a213588c5b7855b0dc7bf99948896291240ed7
* main-3/origin/main-3: 02914ce42e97586663e3aa84857837dbd716711a

절대 접근·수정 금지:

* C:\running-ai 레거시 저장소
* C:\running-ai-watchface 별도 개발 저장소

현재 운영 서비스와 Watchdog를 그대로 유지한다.

### STEP A — 운영 Preflight

다음 상태를 읽기 전용으로 확인한다.

1. Docker/PostgreSQL Health.
2. Connector 8765 Health.
3. Spring 18080 Health.
4. Relay 17845 Health.
5. Tailscale Service/Funnel.
6. Watchdog Enabled 및 최근 자동 실행 성공 여부.
7. Watchdog Recovery Action/Lockout.
8. 서비스 PID 및 CreationDate.
9. Canonical Git Working Tree.
10. PostgreSQL 기존 Volume과 DB 기준선.

비정상적인 재시작이나 Recovery Action이 발견되면 외부 연동 테스트를 중단하고 원인부터 보고한다.

### STEP B — 데이터 흐름 및 인증 구조 확인

현재 tools/external-relay/server.js를 분석한다.

다음을 확인한다.

* /today-workout의 인증 방식.
* Intervals.icu에 실제 호출하는 URL과 HTTP Method.
* Intervals.icu 인증 헤더 생성 방식.
* INTERVALS_ICU_API_KEY의 설정 및 전달 경로.
* Relay가 사용하는 Asia/Seoul 날짜 계산.
* 이벤트의 Run/Ride 필터링.
* HAS_PLAN / REST / NO_PLAN 결정 조건.
* 응답 캐시 TTL: 현재 설정값 240초.
* WATCHFACE_RELAY_MOCK 사용 가능성과 운영 사용 여부.
* 기존 레거시 today_plan.json 참조의 우선순위.

API 키와 Watch Token은 원문을 출력하거나 로그에 기록하지 않는다.

운영 Relay 프로세스의 실제 환경변수 값을 안전하게 확인할 수 없다면, 확인하지 못한 부분을 명확히 기록한다. .env의 값과 실행 중 프로세스의 값이 같다고 추정하지 않는다.

레거시 경로를 직접 열거나 수정하지 않는다.

### STEP C — Intervals.icu 직접 조회

현재 서울 날짜를 기준으로, Relay의 실제 조회 로직과 동일한 읽기 전용 GET을 수행한다.

현재 소스 기준:

GET /api/v1/athlete/0/events?oldest={date}&newest={date}&category=WORKOUT

운영 환경에 설정된 인증 정보를 메모리에서만 사용한다.

확인 사항:

1. API 키 존재 여부.
2. HTTP 상태코드.
3. 응답 JSON 유효성.
4. 당일 WORKOUT 이벤트 건수.
5. Run/Ride 대상 건수.
6. Relay의 필터링 조건을 통과하는 이벤트 여부.
7. 실제 훈련 이벤트가 있다면 Relay가 생성할 예상 Label.
8. 인증 오류와 정상적인 데이터 부재 구분.

401/403/429 발생 시 원인을 분류하고 추가 반복 호출하지 않는다.

조회 결과에는 토큰, 인증 헤더, 원본 개인정보 및 불필요한 운동 상세 데이터를 남기지 않는다.

데이터가 없으면 테스트용 Workout을 생성하지 않는다.

### STEP D — Local Relay 검증

기존 운영 Relay에 대해 최소 횟수의 GET만 실행한다.

대상:
http://127.0.0.1:17845

확인:

* GET /health → 200
* GET /today-workout 무인증 → 401
* GET /today-workout 인증 → 200 또는 명확한 오류
* 응답 date/status/label 구조
* 임의의 미등록 경로 → 404

실제 Bearer Token은 기존 보안 파일에서 메모리로만 읽는다.

토큰을 URL, 프로세스 명령줄 인수, 로그, 문서에 노출하지 않는다.

인증 응답이 200이어도 status를 반드시 확인한다.

* HAS_PLAN: 실제 훈련 이벤트와 Label 일치 여부
* REST: 명시적 휴식 결정 여부는 접근 가능한 근거 내에서만 판단
* NO_PLAN: 정상적으로 훈련 데이터가 없는 것인지 확인
* 502: Intervals.icu 인증·네트워크 등 업스트림 원인 분류

Relay 캐시 240초를 고려한다.

재시작이나 캐시 강제 초기화로 결과를 맞추지 않는다.

### STEP E — Public HTTPS 검증

현재 구성된 Tailscale Funnel HTTPS 주소로 동일한 검증을 수행한다.

검증 항목:

1. /health 200.
2. 무인증 /today-workout 401.
3. 인증 /today-workout 200 또는 명확한 오류.
4. Local/Public 응답의 date/status/label 일치 여부.
5. TLS 연결 성공.
6. 다른 서비스의 내부 포트 노출 없음.

Local/Public 응답 비교 시 캐시와 날짜 경계를 고려한다.

Relay에는 분당 요청 제한이 있으므로 테스트 호출 횟수를 최소화한다.

### STEP F — 실제 데이터 전달 결과 분류

다음 기준으로 최종 분류한다.

A. FULL_DATA_PATH_PASS

* Intervals.icu 인증 성공.
* 실제 Run/Ride WORKOUT 이벤트 존재.
* Relay가 HAS_PLAN 반환.
* Local/Public 데이터 일치.

B. CONNECTIVITY_PASS_NO_PLAN

* Intervals.icu 직접 조회 정상.
* 해당 날짜의 대상 훈련 이벤트 없음.
* Relay NO_PLAN 정상 반환.
* Local/Public 일치.

C. EXPLICIT_REST

* Relay REST 반환.
* 명시적 휴식 결정의 출처가 검증 가능한 범위에서 확인됨.

D. UPSTREAM_AUTH_OR_API_FAILURE

* API 401/403/429/5xx 등 원인 확인.
* Relay 응답과 대조.
* 인증 설정은 변경하지 않음.

E. RELAY_OR_PUBLIC_PATH_FAILURE

* 직접 API는 정상이나 Relay 또는 Funnel에서 실패.
* 실패 구간을 특정.

F. SOURCE_UNVERIFIED

* Mock 사용 여부, 캐시 또는 프로세스 환경변수 등으로 실제 데이터 출처를 확인할 수 없음.

HTTP 200 + NO_PLAN을 FULL_DATA_PATH_PASS로 판정하지 않는다.

### STEP G — Garmin FR265 확인

이번 단계에서 Garmin 워치페이스 소스나 바이너리를 변경하지 않는다.

물리 기기 확인은 사용자가 가능할 때 수행한다.

확인할 내용:

* 기존 설치된 워치페이스의 연결 상태.
* NOT SYNCED / NO PLAN / REST / 실제 훈련 표시.
* 기기 표시와 Relay 응답의 일치.
* 캐시된 오래된 화면인지 여부.
* 필요 시 사용자가 정상 동기화 주기를 통해 다시 확인.

사용자가 아직 기기 확인을 완료하지 않았다면 FR265_PHYSICAL_CHECK=DEFERRED로 기록한다.

기기 확인 없이 Garmin E2E PASS 선언 금지.

### STEP H — 기술 부채 및 위험 보고

현재 Relay에 남아 있는 레거시 today_plan.json 절대 경로 참조를 기록한다.

이 경로는 기존 REST 우선순위 계약에 영향을 줄 수 있으므로 임의로 삭제하거나 교체하지 않는다.

이번에는 접근·수정하지 않고 향후 별도 개선 대상으로 분류한다.

기타 검토:

* 키 유효성 판단의 근거
* Relay 캐시 영향
* Local/Public 응답 차이
* 실제 데이터 미존재
* FR265 물리 검증 미완료
* 재부팅 E2E 미실행

### STEP I — 운영 무변경 확인

검증 전후 다음이 유지되어야 한다.

* Spring/Connector/Relay PID 및 CreationDate.
* PostgreSQL Container/Volume.
* DB Activity/HighWater.
* Watchdog 5분 주기.
* Restart Budget 및 Lockout.
* 운영 .env/Token 파일.
* Canonical Git Working Tree.

정상적인 Relay 요청 로그, 캐시, Watchdog 주기 기록 갱신은 허용되는 부수효과다.

### STEP J — 결과 및 Git

다음 항목을 보고한다.

1. 운영 Preflight.
2. Intervals API 인증 상태.
3. 당일 Workout 존재 여부.
4. Relay의 데이터 필터링 결과.
5. Local HTTP 결과.
6. Public HTTPS 결과.
7. Mock/캐시 영향.
8. 데이터 전달 분류 A~F.
9. FR265 검증 상태.
10. 운영 PID/DB/Watchdog 불변.
11. 레거시 경로 관련 기술 부채.
12. 남은 미검증 항목.
13. 다음 Phase 권고.

결과 문서만 main-3에 작성한다.

Git 작업이 필요하다면 사전 변경사항을 확인하고, 문서 전용 커밋을 origin/main-3에 Push할 수 있다.

Canonical main 및 origin/main은 수정하지 않는다.

## 절대 금지

* PC 재부팅/로그아웃
* Docker 및 서비스 재시작
* Watchdog 변경
* Intervals API 키 회전
* Watch Token 회전
* 운영 .env 수정
* 실제 훈련 생성·수정·게시
* Garmin Connect로 훈련 업로드
* FR265 앱 재설치·워치페이스 수정
* 레거시 저장소 접근·수정
* 데이터베이스 쓰기
* 운영 코드 변경
* origin/main Push

## 완료 판정

이번 Phase는 EXTERNAL_DATA_PATH_READONLY_CHECK 결과만 확정한다.

기존 REBOOT_PRECHECK=GO를 유지한다.

전체 PHASE_6I_1_EXTERNAL_ACCESS_E2E_READY는 선언하지 않는다.

검증 및 결과 보고 후 STOP한다.
