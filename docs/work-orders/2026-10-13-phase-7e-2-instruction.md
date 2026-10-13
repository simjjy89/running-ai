# WO-RUNNINGAI-PHASE-7E-2 (지시문 원문)

> 사용자 지시 원문입니다. 가공하지 않고 그대로 보존합니다.

---

WO-RUNNINGAI-PHASE-7E-2

ChatGPT 연결 복구 및 Strava Historical Import

역할

RunningAI 시니어 백엔드 아키텍트 / 데이터 마이그레이션 리드 / QA.

목표

Phase 7E-1의 검증된 구현을 보존하고, Strava 전체 백업을 이용해 과거 활동을 RunningAI DB 사본에 통합한다.

우선 현재 끊어진 ChatGPT OAuth Smoke 조회 경로부터 복구한다.

운영 DB, 운영 Spring, Watchface, Relay, Funnel은 변경하지 않는다.

STEP 1 — 7E-1 결과 보존 및 정합성 점검

1. main-3의 미커밋 파일 전체 확인
2. V24, V25 마이그레이션 정합성 확인
3. 1,331개 테스트 PASS 결과 및 관련 문서 확인
4. 이전 작업의 미커밋 산출물 충돌 여부 점검
5. 운영 Release Candidate 대상이 V21~V25로 확대됐음을 배포 계획에 반영

Intervals 원본 27건 중 25건이 적재된 원인을 정확히 확인한다.

나머지 2건을 중복, 제외 대상, 미지원 운동, 오류 등으로 구분하고 실제 외부 ID와 근거를 남긴다.

STEP 2 — ChatGPT OAuth Smoke 연결 복구

ChatGPT에서 RunningAI OAuth Smoke의 get_recent_activities 호출을 시도했으나 내부 오류가 발생했다.

원인부터 분석한다.

* Gateway 17910 상태
* RUNNING_AI_GATEWAY_TARGET_URL 확인
* 합성 Spring 18081 상태
* 사본 Spring 18082 상태
* Cloudflare Tunnel 상태
* Worker OAuth 메타데이터 및 인증 상태
* 프로세스 종료 원인 및 Stop Hook 영향

운영 Spring 18080으로 Gateway를 연결하지 않는다.

합성 데이터 전용 18081을 격리된 환경으로 복구하고, ChatGPT에서 합성 데이터 6건 조회가 다시 성공하는지 검증한다.

실제 데이터 사본 18082는 필요할 경우 로컬 전용으로 별도 복구하되 외부 연결하지 않는다.

STEP 3 — Strava ZIP 확보 및 검증

사용자는 Strava 전체 백업을 Google Drive에 보관하고 있다.

파일명: export_1577472398.zip

ChatGPT에서 확인된 SHA-256:
aaf50ee8814b040284ba1e7dcdf0b9adbf54c25d9b0398d4ea5b31bf56b28533

내용:

* activities.csv: 94건
* activities/*.fit.gz: 94개
* ZIP CRC 정상

Google Drive에서 파일을 개인용 비공개 다운로드 방식으로 확보한다.

Git 저장소 밖에 보관하고 SHA-256을 검증한다.

접근이 불가능하면 사용자에게 정확한 파일 전달 절차를 안내하고 해당 STEP에서 STOP한다.

원본 ZIP에 포함된 GPS 경로, 개인정보, 계정정보는 로그나 Git에 저장하지 않는다.

STEP 4 — FIT 데이터 품질 조사

FIT 원본에서 다음 정보를 조사한다.

* session
* record
* lap
* event
* heart_rate
* cadence
* power
* speed
* distance
* position_lat / position_long
* running dynamics
* device 및 activity provenance

활동별로 원본 메시지 수와 실제 유효한 센서 필드 수를 확인한다.

작은 FIT 파일도 크기만으로 제외하지 말고 실제 메시지를 검사한다.

센서 데이터가 없는 활동은 요약 기록만 보존하며 가짜 샘플이나 랩을 만들지 않는다.

STEP 5 — 데이터 중복 매칭

다음 출처를 비교한다.

A. Garmin Connect 10건

B. Intervals OAUTH_CLIENT 27건

C. Intervals STRAVA 34건

D. Strava 아카이브 94건

E. RunningAI 기존 DB 8건 및 사본 Import 결과

매칭 우선순위:

1. 정확한 Strava Activity ID
2. 정확한 Garmin/Intervals 외부 ID 연결
3. 날짜·시간·지속시간·거리·운동 종류 기반 복합 매칭

불확실한 매칭은 자동 병합하지 않고 검토 대상으로 남긴다.

각 출처의 원본 ID와 메타데이터를 추적 가능하게 보존한다.

최종적으로 고유 활동 수, 중복 활동 수, 매칭 불명확 활동 수를 보고한다.

STEP 6 — Strava FIT Importer 구현

기존 Intervals Importer, ActivityRaw, Activity 및 분석 서비스를 재사용한다.

필수 조건:

* Raw-first
* 출처 및 외부 ID 보존
* gzip FIT 파싱 검증
* Garmin ID 임의 생성 금지
* 기존 고품질 데이터의 잘못된 덮어쓰기 금지
* 멱등성
* 실패 재시도
* 중단 후 재개
* 파일별 오류 격리
* 결측·품질 정보 기록
* 기존 분석 엔진 재사용

기존 Intervals 케이던스 원본 rpm은 그대로 보존한다.

one-leg rpm을 spm으로 환산하는 기능은 원본과 파생값을 분리하고 출처·단위를 표시하는 설계안 및 테스트를 먼저 제시한다. Garmin 원본 측정값으로 오인되도록 저장하지 않는다.

STEP 7 — 사본 DB 통합 검증

운영 DB에는 절대 쓰지 않는다.

검증 대상:

1. 기존 Garmin 8건 보존
2. Intervals Importer 25건 및 제외 2건 검증
3. Strava 94건 중 복구 가능한 전체 활동 처리
4. 중복 제거
5. 동일 Import 재실행 시 고유 활동 증가 0건
6. 강제 종료 후 재개
7. 러닝 분석 재계산
8. 1Hz 샘플 및 랩 보존
9. TrainingContextV2 통합
10. MCP 읽기 7종 로컬 검증
11. 활동 총 건수 및 기간 검증
12. Flyway V21~V25 적용 검증

6월부터 현재까지의 실제 데이터 커버리지를 보고한다.

STEP 8 — 운영 반영 준비

이전 승인된 A 및 B-1은 보존하되, V24·V25와 신규 Strava Importer가 추가됐으므로 운영 반영 계획을 갱신한다.

별도 승인 항목:

A. PostgreSQL 접근 제한 및 V21~V25 운영 배포

B. Garmin Backfill 및 기존 분석 재계산

C. Intervals 전용 활동 Import

D. Strava 아카이브 Import

E. ChatGPT 실제 데이터 읽기 연결

F. ChatGPT 쓰기·훈련 생성·승인·게시 기능 확장

현재 단계에서 A~F를 실제 실행하지 않는다.

완료 보고

* ChatGPT OAuth Smoke 복구 결과
* Strava FIT 파일 분석 결과
* 활동별 데이터 품질 및 복구 가능성
* 출처별 중복 및 고유 활동 수
* Importer 구현 내용
* 사본 DB 적재 결과
* 회귀 테스트 결과
* 운영 반영 전 잔여 위험
* 다음 단계 권고

검증 완료 후 STOP한다.

운영 Spring 18080, 운영 DB, Relay, Watchface, Funnel, Watchdog, Canonical main, origin은 변경하지 않는다.

---

## 추가 지시 (STEP 5 보고 후, 범위 확정)

> STEP 1~5 결과를 보고하고 Importer 범위를 질의한 데 대한 답변입니다.

1번 전체 구현으로 진행해 줘.

목표는 RunningAI에 과거 운동 이력을 최대한 통합하는 것이다.

다음 조건을 지켜줘.

1. 기존 활동 25건의 FIT 랩·러닝파워 데이터를 보강하되, 원본 값과 출처를 보존하고 더 높은 품질의 기존 데이터를 덮어쓰지 말 것.
2. 실제 랩이 존재하는 5건의 인터벌 구조를 정확하게 복원할 것.
3. 트레드밀 4건과 실내 사이클 11건도 적재할 것. 단, 러닝 분석 엔진이 사이클 활동을 러닝으로 처리하지 않도록 종목을 구분할 것.
4. HR이 없는 15건은 NULL을 유지하고, 심박·훈련부하·러닝 다이내믹스 값을 추정 생성하지 말 것.
5. Strava·Intervals·Garmin 활동 간 중복 방지 및 재실행 멱등성을 검증할 것.
6. FIT 원본과 파생 데이터의 단위·출처를 구분하고, 기존 분석 엔진과의 호환성을 검증할 것.
7. V26을 포함한 모든 변경은 사본 DB에서 검증하고 운영 DB는 변경하지 말 것.

완료 후 신규 적재 15건, 기존 활동 보강 25건, 인터벌 5건, 데이터 품질, 테스트 결과를 보고하고 STOP해.
