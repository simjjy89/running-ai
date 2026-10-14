RunningAI Phase 7F-3B-2B — Production Train MCP Activation

0. 역할 및 실행 승인

RunningAI 시니어 백엔드 아키텍트 / 개발 리드 / 운영 배포 책임자 / 보안 QA.

Phase 7F-3B-2A 운영 Read-only 릴리스 성공을 기준으로, ChatGPT에서 RunningAI의 실제 Workout Draft를 생성·조회할 수 있도록 운영 Train MCP를 활성화한다.

사용자는 이번 Phase의 운영 Train MCP 활성화, 정상적인 최신 Injury Report 기록, Gateway 설정 변경, OAuth Worker의 Train Scope 추가를 명시적으로 승인했다.

동일한 작업에 대한 승인을 반복해서 요청하지 말고 승인된 범위 내에서 실행한다.

단, 실제 Workout Draft 승인, Publish Scope 활성화, Intervals.icu 게시 작업은 이번 승인 범위에 포함되지 않는다.

기준 상태가 달라졌거나, 검증되지 않은 쓰기 권한이 노출되거나, 롤백이 불가능하면 즉시 STOP한다.

⸻

1. 현재 기준

Git / 운영 코드

* Canonical main: e76dc6b
* main-3 HEAD: e76dc6b
* 운영 Spring: 127.0.0.1:18080
* 운영 Gateway: 127.0.0.1:17910
* 운영 DB: running_ai
* Flyway: V26
* 운영 JAR: e76dc6b 릴리스 완료
* Gateway는 canonical 기반 독립 실행 경로로 전환 완료
* 기존 JAR 및 Gateway 롤백 자산 확보
* 서버 1,442 테스트 PASS
* Gateway 46/46 PASS

현재 MCP

기존 Read 7종 정상.

Train MCP 6종은 구현되어 있으나 비활성화 상태.

OAuth Worker 지원 Scope:

* runningai:read
* offline_access

운영 Injury Report

운영 DB의 injury_report는 현재 0건이다.

개발 DB running_ai_7f2에는 과거 ACTIVE, UNKNOWN, RESOLVED 기록이 있지만, 해당 행들을 운영에 복제하지 않는다.

기존 외부 이벤트 보호

Intervals.icu:

* Athlete ID 설정: "0"
* 실제 계정: i6***48
* 날짜: 2026-10-16
* Event ID: 144442046
* Marker: runningai:workout:v1:0:2026-10-16

이 이벤트의 수정·삭제·재게시·takeover는 금지한다.

⸻

2. 이번 Phase의 명확한 목표

ChatGPT에서 다음 흐름이 가능하도록 한다.

1. 실제 TrainingContext 조회
2. 실제 Injury Gate 반영
3. 로컬 Claude CLI를 사용한 훈련 Draft 생성
4. 생성된 Draft 조회
5. 필요 시 수정안 생성 가능
6. 사용자 승인 기능은 도구로 노출하되 명시적 개별 승인 없이는 실행하지 않음

Intervals.icu로 실제 훈련을 보내는 기능은 계속 차단한다.

MCP 권한

기존 Read 7종은 runningai:read를 유지한다.

Train Scope에 허용할 도구는 다음 5개다.

* create_workout_draft
* get_workout_draft
* revise_workout_draft
* approve_workout_draft
* preview_draft_publish

다음 도구는 이번 단계에서 절대 활성화하지 않는다.

* publish_approved_draft
* 기존 금지된 publish_workout

Gateway의 도구별 Scope 정책과 Allowlist를 모두 만족해야 한다.

⸻

3. STEP 1 — 운영 변경 전 백업 및 기준 상태 확인

변경 전에 다음을 확인한다.

1. 운영 Spring 및 Gateway 정상
2. 실제 실행 JAR의 SHA-256 및 release sidecar
3. Gateway 실행 경로와 백업된 런처
4. 현재 설정 및 Scheduled Task 정의
5. 기존 OAuth Worker의 배포 버전과 환경 설정
6. 기존 OAuth Read 도구 정상
7. 운영 DB 테이블별 기준 행 수
8. 운영 Injury Report 0건
9. 운영 게시 기능 OFF
10. 기존 Intervals 이벤트 불변
11. Watchface/Relay 정상

롤백 가능한 기존 설정을 보존한다.

비밀키·토큰은 출력하거나 Git에 커밋하지 않는다.

OAuth Worker 역시 기존 배포를 되돌릴 수 있는 버전과 절차를 확보한다.

이 단계에서 예상하지 못한 차이가 있으면 STOP한다.

⸻

4. STEP 2 — 운영 Injury Report 신규 기록

사용자의 최신 자기 보고:

* 2026-10-11 하프마라톤 이후 무릎 외측 통증이 있었음
* 당시 보행 중에도 통증이 있었음
* 이후 현재 통증 없음
* 현재 보행 중 통증 없음
* 가벼운 러닝 중 통증 없음
* 러닝 직후 통증 없음
* 다음 날까지 통증과 붓기 없음
* 사용자가 해당 무릎 외측 증상의 해소를 RESOLVED 자기 신고로 승인했음

사용자가 승인한 범위에서 운영 Injury Report API를 통해 현재 상태 1건만 기록한다.

조건:

* status=RESOLVED
* body_site 및 side는 기존 도메인 표현 사용
* pain_present=false
* pain_on_walking=false
* swelling=false
* pain_intensity 등 미보고 수치는 null
* 미보고 항목을 정상으로 추정하지 않음
* 현재 자기 신고의 실제 시각과 DB 기록 시각을 구분
* 신고 시각을 인위적으로 현재로 바꾸어 유효기간을 연장하지 않음
* 동일한 자기 신고가 이미 기록됐으면 중복 INSERT 금지

과거 ACTIVE·UNKNOWN 레코드를 개발 DB에서 복사하거나 새로 만들어 과거 이력을 복원하지 않는다.

필요하다면 기존 notes 필드에 사용자가 직접 보고한 부상 및 회복 경과를 간결히 기록하되, 허구의 의료 판단은 포함하지 않는다.

주의: 운영 DB에는 과거 ACTIVE 레코드가 없기 때문에 최신 RESOLVED 1건만으로는 daysSinceLatestActiveReport가 계산되지 않을 수 있다. 이를 임의의 과거 레코드로 보정하지 않는다. Coach가 실제로 전달받는 Injury History와 그 한계를 보고한다.

저장 후 기존 Injury Gate로 상태와 48시간 freshness를 재검증한다.

Gate가 STALE 또는 차단을 반환한다면 기록을 임의 갱신하거나 우회하지 않는다.

이번 단계에서 허용된 운영 DB 변경은 해당 최신 자기 신고의 정상 저장 및 추후 명시적으로 승인받은 테스트 Draft 생성뿐이다.

⸻

5. STEP 3 — Spring Train MCP 활성화

현재 운영 JAR e76dc6b를 그대로 사용한다.

재빌드 및 신규 릴리스는 원칙적으로 하지 않는다.

실제 설정 바인딩을 확인하고 다음 값을 활성화한다.

RUNNINGAI_MCP_TRAIN_ENABLED=true

다음 값은 계속 false를 유지한다.

* RUNNING_AI_DRAFT_PUBLISHING_ENABLED=false
* WORKOUT_PUBLISHING_ENABLED=false

이미 확인된 Injury History notesExcerpt 제한:

RUNNING_AI_COACH_INJURY_HISTORY_MAX_NOTES_CHARS=0

을 유지한다.

중요한 사전 검증

Train MCP 도구 등록과 Draft 생성이 Draft Publishing 스위치 OFF 상태에서도 정상 동작하는지 확인한다.

Draft 생성 목적으로 게시 마스터 스위치를 켜야 한다면 임의로 켜지 말고 STOP한다.

Train MCP는 6종이 서버에 등록될 수 있으나, Gateway에서는 Publish 도구를 노출하지 않아야 한다.

설정 변경 전후에 게시 관련 직접 REST 경로가 예상치 않게 개방되는지도 확인한다.

기존 안전한 운영 재시작 절차를 사용한다.

Spring 재시작 후:

* Read 7종 정상
* Train 6종 등록
* 기존 Injury Gate 정상
* 외부 게시 OFF
* DB V26
* 기존 활동 48건 보존

을 확인한다.

실패하면 이전 스위치 상태로 롤백하고 STOP한다.

⸻

6. STEP 4 — Gateway Allowlist 확장

현재 Gateway는 기본 Read 7종 Allowlist를 사용한다.

실제 배포 런처에서 다음 구성을 적용한다.

* 기존 Read 7종 전부 유지
* Train 5종 추가
* Publish 도구는 제외
* 기존 publish_workout 금지 유지

Gateway 내부에 하드코딩된 Scope 정책은 그대로 사용한다.

필수 검증

* Read-only 토큰으로 Train 호출 시 403
* Read-only 토큰의 tools/list에서 Train 도구 미노출
* Train Scope로 허용된 Train 도구만 노출
* Publish Scope가 없어도 Publish 도구를 노출하지 않음
* Allowlist에 없는 Publish 도구 호출 차단
* 무토큰 401
* 위조 토큰 401
* 기존 Read 7종 정상

설정의 기존 환경변수를 덮어쓰지 않도록 주의한다.

가능하면 별도 Gateway 테스트 인스턴스로 선검증한 다음 운영 17910에 반영한다.

실패 시 기존 Read 7종 Allowlist로 즉시 롤백한다.

⸻

7. STEP 5 — OAuth Worker Train Scope 추가

현재 Worker는 지원 Scope를 제한하고 있다.

이번 단계에서 추가 허용할 Scope는 정확히 하나다.

runningai:train

최종 지원 Scope:

* runningai:read
* runningai:train
* offline_access

runningai:publish는 추가하지 않는다.

다음 사항을 유지한다.

* 기존 GitHub OAuth 인증
* 기존 단일 사용자 allowlist
* 기존 issuer/audience
* 기존 token validation
* redirect URI 정책
* 기존 Worker URL
* 기존 OAuth Read 연결 호환성
* 사용자 승인 기반 Scope 발급

Worker 배포 전 테스트:

1. Read만 요청하면 Read만 발급
2. Train은 명시적으로 요청하고 승인해야 발급
3. Publish 요청은 거부
4. Read 토큰으로 Train 접근 불가
5. Train 토큰으로 Publish 접근 불가
6. 기존 Read 토큰 유효
7. 잘못된 subject 거부
8. 무단 Scope 확대 불가

검증이 통과하면 Worker를 배포하고 실제 메타데이터 및 권한 동작을 확인한다.

기존 OAuth 사용자 동의 범위를 자동으로 확대해서는 안 된다.

Worker 배포가 기존 Read 사용을 깨뜨리면 이전 Worker 버전으로 롤백하고 STOP한다.

⸻

8. STEP 6 — ChatGPT 연결 재승인

Worker에서 runningai:train을 지원하더라도 기존 ChatGPT 연결에 Train 권한이 자동 추가되지는 않는다.

사용자가 직접 ChatGPT 연결 재승인 절차를 진행하도록 정확한 방법을 안내한다.

필요한 Scope:

* runningai:read
* runningai:train

다음은 요청하지 않는다.

* runningai:publish

사용자의 브라우저나 ChatGPT 계정에 대한 로그인·재승인을 자동으로 성공했다고 주장하지 않는다.

실제 사용자 조작이 필요한 지점에 도달하면 필요한 단계와 확인 방법을 보고한다.

재승인 전에도 기존 Read 7종은 정상 작동해야 한다.

⸻

9. STEP 7 — ChatGPT E2E 검증 준비

연결이 재승인된 후 ChatGPT에서 다음 도구를 사용할 수 있어야 한다.

* get_training_context
* get_workout_recommendation
* create_workout_draft
* get_workout_draft

첫 번째 실사용 테스트에서는 사용자에게 훈련을 보여주기 전 실제 승인·게시를 하지 않는다.

Draft 생성 원칙

* 실제 운영 TrainingContext 사용
* 최신 Injury Gate 및 Injury History 반영
* 실제 Claude CLI 사용
* Mock 데이터 및 가짜 AI 출력 금지
* 안전한 회복 목적 훈련 요청
* 최근 부상 및 회복 상태 반영

운영 테스트용 Draft를 생성하려면 그 날짜와 생성 요청을 사용자에게 확인받는다.

Draft 생성은 운영 DB에 새로운 데이터가 기록되는 작업이므로 자동으로 실행하지 않는다.

테스트 완료 목표

1. ChatGPT Read 정상
2. ChatGPT Train Scope 인식
3. Train Tool 노출
4. 실제 Draft 생성
5. Draft 조회
6. 사용자에게 전체 훈련 구조 제시
7. Publish Tool 미노출
8. 실제 Intervals 쓰기 0건

이번 단계에서는 새 Draft에 대해 Approve 호출도 하지 않는다.

⸻

10. STEP 8 — 시스템 보호 및 롤백

모든 단계에서 다음을 확인한다.

* 운영 Spring 정상
* Gateway 정상
* 기존 Read 7종 정상
* Watchface/Relay 정상
* 운영 활동 데이터 보존
* 기존 승인·게시 기록 보존
* Intervals 이벤트 144442046 불변
* Garmin 직접 쓰기 없음

단계별 복구:

* Spring 문제 → Train 스위치 OFF 후 이전 설정으로 재시작
* Gateway 문제 → 기존 Read 7종 Allowlist 복원
* Worker 문제 → 이전 Worker 버전 복원
* OAuth 문제 → 기존 Read 연결 유지 또는 정상 복구 안내

운영 Injury Report는 append-only이므로 임의 삭제하지 않는다.

각 단계 실패 시 후속 단계를 진행하지 않고 STOP한다.

⸻

11. 이번 Phase의 절대 금지 항목

외부 게시

* Intervals.icu POST/PUT/PATCH/DELETE
* 기존 이벤트 takeover
* 2026-10-16 재게시
* Publish MCP 활성화
* runningai:publish Scope 추가
* Garmin 직접 훈련 업로드

운영 DB

* 개발 DB 전체 또는 일부 복사
* 과거 Injury Report 임의 생성
* 기존 Draft/Approval/Publication 수정
* 사용자 승인 없는 새 Draft 자동 생성
* 사용자 승인 없는 Draft Approval

개발 및 배포

* DB 스키마 변경
* 신규 Flyway Migration
* Spring Boot 버전 변경
* 관련 없는 리팩터링
* canonical/main-3 reset 또는 rebase
* origin push
* 레거시 저장소 변경
* Watchface/Relay 변경

⸻

12. 완료 보고

다음 순서로 보고한다.

A. Injury Report

* 신규 기록 여부
* 실제 저장값
* 신고/기록 시각
* Injury Gate 판정
* 기록 유효기간

B. Spring

* PID와 재시작 결과
* Train MCP 도구 등록 결과
* Read 7종 회귀
* 게시 스위치 OFF 확인

C. Gateway

* Read 7종 유지
* Train 5종 Allowlist 등록
* Publish 도구 차단
* Scope별 거부 테스트 결과

D. OAuth Worker

* 지원 Scope 목록
* Worker 배포 결과
* 기존 Read OAuth 호환성
* Publish Scope 거부 확인

E. ChatGPT 연결

* 기존 Read 호출 결과
* Train 재승인 필요 여부
* 사용자가 수행해야 할 정확한 조치
* Train 호출 가능 여부

F. 시스템

* 운영 DB 변경 행 수와 대상
* 외부 Intervals 쓰기 0건
* 기존 이벤트 보호
* Watchface/Relay 상태
* Git 및 배포 변경
* 롤백 자산 유지

G. 최종 판정

* PASS
* PARTIAL
* STOP

사용자의 ChatGPT 재승인이 필요한 경우, 서버 측 배포가 성공했더라도 실제 ChatGPT Train 도구 호출을 검증하기 전에는 전체 E2E를 PASS라고 선언하지 않는다.

13. 완료 기준

* 운영 Injury Report 정상 기록 또는 차단 사유 명확화
* 최신 Spring에서 Train MCP 등록
* Gateway에서 Train 5종 노출 가능
* Worker runningai:train 지원
* runningai:publish 계속 차단
* 기존 Read 7종 정상
* 기존 OAuth Read 연결 유지
* 운영 DB의 기존 데이터 보존
* 외부 Intervals 쓰기 0건
* 필요한 경우 사용자 재승인 대기 상태 보고

작업 완료 후 STOP한다.

성공하더라도 Phase 7F-3B-2C Publish MCP 활성화는 자동 진행하지 않는다.

장기 아키텍처 원칙:
Modular Monolith + Single-Tenant Self-hosted.
