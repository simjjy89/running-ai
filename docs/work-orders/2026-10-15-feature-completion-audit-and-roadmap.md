# RunningAI — 기능 완성 우선 감사 보고서 및 로드맵 (2026-10-15)

Branch `main-3`, base `36448fe`. **코드 변경·배포·commit 없음.** 저장소 실제 코드, 운영 프로세스,
운영 DB(읽기 전용), 전체 테스트 실행 결과를 근거로 작성.

실행 환경 확인: Spring Boot **3.5.16** / Java **21** (`build.gradle`), Kotlin 2.1.21,
PostgreSQL 17(Docker), Flyway **V26**. 운영 Spring은 `e76dc6b` 기반 릴리스 jar
(`.runtime/running-ai-server.jar.json`, migrationCount 26)로 `127.0.0.1:18080` 가동.

## 0. 테스트 실측 (판정 근거이되, 판정 그 자체는 아님)

| 스위트 | 명령 | 결과 |
| --- | --- | --- |
| Spring 서버 | `gradlew clean test` | **1442/1442 PASS**, 실패 0, 에러 0, **skip 0**, 147 클래스, 3m32s |
| mcp-gateway | `npm test` | **46/46** |
| mcp-worker | `npm run test:all` | **34 + 49** (당일 실행) |
| external-relay | `node --test tools/external-relay/test/*.test.js` | **14/14** |
| 본문 TODO/FIXME | `server/src/main` 전수 | **0건** |
| `@Disabled`/`@Ignore` | `server/src/test` 전수 | **0건** |

테스트 전원 통과는 운영 완료의 근거가 아니다. 아래 판정은 **운영 프로세스·운영 DB·실제 외부
호출 이력**을 함께 본 결과다.

---

# STEP 1 — 전체 기능 감사

## 1. REST / MCP API 목록

**REST (약 45개 매핑, 14개 컨트롤러 그룹)**

| 영역 | 엔드포인트 |
| --- | --- |
| 헬스 | `GET /api/v1/health`, `GET /actuator/health` |
| 활동 | `POST /api/v1/activities`, `GET /api/v1/activities`, `GET /api/v1/activities/{id}` |
| 선수 프로필 | `GET` / `PUT /api/v1/athlete/intensity-profile` |
| 훈련 계산 | `GET /api/v1/training-load` (+`/weekly`), `/training-state`, `/training-decision-context`, `/workout-recommendation`, `/workout-prescription`, `/workout-intensity-targets` |
| Garmin | `POST /api/v1/garmin/sync`, `GET /garmin/sync/status`, `POST /garmin/profile-sync`, `POST /garmin/recovery-sync`(+`/backfill`), `POST /garmin/activities/{id}/details`(+`/reprocess`) |
| 분석 | `POST`/`GET /api/v1/activities/{id}/analysis`, `POST /api/v1/activities/analysis-refresh` |
| Intervals | `POST /intervals/enrichment/activities/{id}`(+`/reprocess`), `POST /intervals/enrichment/fitness`(+`/reprocess`), `GET /activities/{id}/intervals`, `GET /intervals/fitness`, `POST`/`GET /intervals/activity-import`, `POST .../{id}/reprocess` |
| Strava | `POST`/`GET /api/v1/strava/archive-import` |
| 백필 | `POST /api/v1/historical-backfill`, `POST /{runId}/resume`, `GET /{runId}` |
| 코치 | `GET /api/v1/coach/training-context`, `GET /api/v1/recovery-context`, `POST /api/v1/coach/data-refresh` |
| 드래프트 | `POST /api/v1/workout-drafts`, `GET /{id}`, `POST /{id}/revisions`, `POST /{id}/approve`, `GET /{id}/publish-preview`, `POST /{id}/publish` |
| 부상 | `POST /api/v1/athlete/injury-reports`, `GET /athlete/injury-state`, `GET /athlete/injury-reports` |
| 레거시 게시 | `POST /api/v1/workout-publish` |

**MCP — 코드에 정의된 도구 14개**

| 그룹 | 도구 | 게이트웨이 노출 |
| --- | --- | --- |
| read (7) | `get_recent_activities`, `get_activity_analysis`, `get_training_context`, `get_recovery_context`, `get_fitness_trend`, `get_workout_recommendation`, `get_athlete_profile` | 노출 · `runningai:read` |
| train (5) | `create_workout_draft`, `get_workout_draft`, `revise_workout_draft`, `approve_workout_draft`, `preview_draft_publish` | 노출 · `runningai:train` |
| publish (1) | `publish_approved_draft` | **allowlist 미등재** (Spring은 13개 노출, 게이트웨이는 12개만 통과) |
| 레거시 (1) | `publish_workout` | 영구 차단 (게이트웨이가 명시적으로 거부) |

→ **IMPLEMENTED_AND_VERIFIED** (read/train 12개는 2026-10-14~15 실제 ChatGPT 호출로 교차 검증됨)

## 2. 데이터 수집 및 분석 파이프라인

| 기능 | 상태 | 근거 |
| --- | --- | --- |
| Garmin 요약 수집 + 증분 동기화 | **IMPLEMENTED_AND_VERIFIED** | `garmin_sync_state` 최종 성공 2026-10-12 |
| Garmin 상세(랩/존/샘플) 수집 | **IMPLEMENTED_AND_VERIFIED** | 6H-1B LIVE_VERIFIED, 운영 `activity_sample` 75,206행 |
| Garmin 회복(HRV/수면/BB) | **IMPLEMENTED_NOT_E2E_VERIFIED** | 운영 `garmin_recovery_daily` **21행**뿐 — 28일 베이스라인 창 미충족 |
| Intervals 보강(CTL/ATL/form) | **IMPLEMENTED_AND_VERIFIED** | `intervals_fitness_daily` 99행, `activity_intervals_metrics` 33행 |
| Intervals 전용 활동 임포트 | **IMPLEMENTED_AND_VERIFIED** | 운영 `external_source=INTERVALS_ICU` **25건** |
| Strava 아카이브 임포트 | **IMPLEMENTED_AND_VERIFIED** | 운영 `STRAVA` **15건** |
| 90일 과거 백필 | **IMPLEMENTED_NOT_E2E_VERIFIED** | 운영 실행 이력 없음(복사 DB 검증까지) |
| 러닝 분석 엔진 | **IMPLEMENTED_AND_VERIFIED** | 운영 `activity_analysis` **48/48** (활동 수와 1:1) |
| 코치 데이터 리프레시(6H-9) | **IMPLEMENTED_NOT_E2E_VERIFIED** | 엔드포인트 존재, 스케줄러 OFF |

운영 데이터 실측: 활동 **48건** — GARMIN 8(최신 2026-10-10), INTERVALS_ICU 25(~2026-09-12),
STRAVA 15(~2026-08-15).

## 3. AI Coach 연동 / 훈련 생성·수정·승인

| 기능 | 상태 |
| --- | --- |
| Claude CLI 코치 (`ClaudeCliClient`) — 도구 전면 차단, stdin 프롬프트, 재시도 없음 | **IMPLEMENTED_AND_VERIFIED** |
| TrainingContext V2 | **IMPLEMENTED_AND_VERIFIED** (운영 `RUNNING_AI_TRAINING_CONTEXT_VERSION=V2`) |
| 드래프트 생성 / 수정 / 승인 | **IMPLEMENTED_AND_VERIFIED** — 운영 DB 드래프트 **19건**(DRAFT 10 / APPROVED 3 / SUPERSEDED 6), 승인 **3건** |
| 부상 이력 컨텍스트(7F-3A) | **IMPLEMENTED_NOT_E2E_VERIFIED** — 운영 ledger 2행 모두 RESOLVED |
| 컨텍스트 스냅샷 영속화(V20) | **IMPLEMENTED_AND_VERIFIED** |

## 4. Intervals.icu 게시 및 Garmin 동기화

| 항목 | 상태 | 핵심 사실 |
| --- | --- | --- |
| `IntervalsWorkoutPublisher` (CREATE/UPDATE/NO_CHANGE + readback) | **IMPLEMENTED_AND_VERIFIED** | — |
| 승인 드래프트 → Intervals 실제 게시 (**UPDATE 분기**) | **IMPLEMENTED_AND_VERIFIED** | 운영 DB `workout_draft_publication` **3건 전부 PUBLISHED / UPDATED / verified=true** (event 140389091·140391756·140511724, 2026-10-03~10-05) |
| **CREATE 분기** | **IMPLEMENTED_NOT_E2E_VERIFIED (운영 기준)** | 7F-2F에서 event 144442046 CREATED 성공했으나 **dev DB `running_ai_7f2`** 에서였음. 운영에서는 미실행 |
| 운영 draft-publishing 스위치 | **INTENTIONALLY_DISABLED** | `.env` `RUNNING_AI_DRAFT_PUBLISHING_ENABLED=false` |
| 레거시 날짜 기반 게시 + 스케줄러 | **INTENTIONALLY_DISABLED** | `WORKOUT_PUBLISHING_ENABLED=false`, 스케줄러 false |
| **Garmin 쓰기 경로** | **NOT_IMPLEMENTED (설계상 부재)** | Spring·커넥터 모두 Garmin에 GET만 존재. Intervals→Garmin 푸시는 **Intervals.icu 자체 연동**이며 본 저장소 밖 |
| Garmin 측 도달 확인(readback) | **NOT_IMPLEMENTED** | 5C-4의 FR265 DEVICE_VERIFIED는 **수동 육안 확인** 1회 |

## 5. Watchface / Relay

| 항목 | 상태 |
| --- | --- |
| `tools/external-relay` (`GET /health`, `/today-workout`, Bearer 게이트) | **IMPLEMENTED_AND_VERIFIED** — 운영 PID 31960이 **저장소 경로**에서 가동, watchdog `external-relay: UP`, 테스트 14/14 |
| 데이터 출처 | **PARTIALLY_IMPLEMENTED** — Intervals.icu 직접 조회 + 레거시 `C:\running-ai\state\today_plan.json` REST 오버라이드. **Spring 백엔드에 연결되어 있지 않음**(당시 의도적 결정) |
| 외부 전송 경로 | **PARTIALLY_IMPLEMENTED** — ad-hoc `cloudflared` quick tunnel(재부팅 시 호스트명 소멸). Named Tunnel / VPN 미결정 |
| 워치페이스 앱 자체 (`C:\running-ai-watchface`) | **저장소 밖** — 본 저장소에 소스 없음 |

## 6. 부상 안전 게이트

**IMPLEMENTED_AND_VERIFIED.** `WorkoutSafetyPolicy`(순수) → `WorkoutSafetyGateService`를 거치는
호출 지점 **6곳 전수 확인**: 드래프트 생성(`WorkoutDraftService:191`), 수정(`:202`),
승인(`WorkoutDraftApproval:107`), 게시 미리보기(`ApprovedWorkoutDraftPublishService:105`),
게시(`:136`), 레거시 결정론 게시(`WorkoutPublishApplicationService:73`). 우회 경로 없음.
MCP read 도구도 동일 게이트를 읽기만 한다(`ReadMcpTools:406`).

**데이터 품질 결함 1건**: 운영 `injury_report`에 동일 시각(2026-10-13 22:54:24.101553Z)
RESOLVED **중복 2행**. 7F-3B-2B에서 원인 규명 완료(셸 `||` 가 파이프라인 전체를 재시도).
append-only 원장 특성상 삭제 대상이 아니며 게이트 판정에는 영향 없음(최신 1행만 사용).

## 7. 스케줄러 / 중복 실행 방지 / 장애 복구

| 스케줄러 | 코드 | 운영 상태 |
| --- | --- | --- |
| `GarminSyncScheduler` | 구현 | **OFF** (`RUNNING_AI_GARMIN_SCHEDULER_ENABLED` 미설정) |
| `GarminProfileSyncScheduler` | 구현 | **ON** (`GARMIN_PROFILE_SYNC_ENABLED=true`, 04:30 KST) |
| `WorkoutPublishingScheduler` | 구현 | **OFF** |
| `CoachDataRefreshScheduler` | 구현 | **OFF** |
| MCP 서버 스위치 3종 | 구현 | `MCP_ENABLED=false`, `READ_ENABLED=true`, `TRAIN_ENABLED=true` |

**중복 실행 방지**: 날짜별 단일 비행(`WorkoutPublishApplicationService`), 드래프트별 단일
비행(`ApprovedWorkoutDraftPublishService`), 동기화 `AlreadyRunningException`, Intervals 마커
= `external_id` 멱등, 임포터 재-POST = 재개 겸 재시도. **IMPLEMENTED_AND_VERIFIED**

**장애 복구**: `RunningAI.Watchdog.ps1` — 10분 창 최대 3회 재시작 예산 + 24시간 창 6회 실패 시
lockout. 현재 `overall: UP` (docker / postgres / connector / spring / external-relay 전부 UP).
**IMPLEMENTED_AND_VERIFIED**

**운영 영향 1건**: Garmin 증분 동기화 스케줄러가 꺼져 있어 `garmin_sync_state` 최종 성공이
**2026-10-12** — 감사 시점 기준 3일 경과. 신규 활동이 자동으로 들어오지 않는다.

## 8. TODO / 미구현 / 비활성 / 실패 테스트

| 항목 | 상태 |
| --- | --- |
| 본문 TODO/FIXME | **0건** |
| 실패 테스트 / 비활성 테스트 | **0건** (1442 PASS, skip 0) |
| QUALITY 워크아웃 구조 | **NOT_IMPLEMENTED** (`WorkoutPrescriptionPolicy`가 `QUALITY_PRESCRIPTION_NOT_SUPPORTED`로 명시 거부) |
| 결정론 처방의 인터벌/반복 구조 | **NOT_IMPLEMENTED** (AI 드래프트 경로에는 존재) |
| 사이클링 임계 프로필 / 레이스 페이스 / RPE | **NOT_IMPLEMENTED** |
| 리포팅 | **NOT_IMPLEMENTED** |
| 놓친 실행 보정 / 재시도 / 알림 | **NOT_IMPLEMENTED** (의도적: 게시 경로에 재시도 없음) |
| 임포트 활동의 케이던스 지표 | **INTENTIONALLY_DISABLED** (rpm↔spm 미변환 결정) |
| Strava 랩의 인터벌 그룹 | **NOT_IMPLEMENTED (원천적 불가)** — 재인코딩에 `intensity` / `wkt_step_index` 없음 |

---

# STEP 2 — 기능 완성 로드맵

> 원칙: **이미 구현된 것은 다시 만들지 않는다.** 아래는 전부 "실제로 비어 있는 부분"만이다.
> 유료 인프라·신규 외부 API 도입 없음. 모든 단계는 기존 서비스 재사용.

## P1. AI 생성 → 수정 → 승인 → 미리보기 → 승인된 실제 게시

**실태: 코드는 전부 존재하고, 운영에서 UPDATE 분기까지 실제로 성공한 이력이 있다. 비어 있는 것은
"ChatGPT에서 마지막 한 걸음을 밟을 수단"과 "운영에서의 CREATE 분기"뿐이다.** 신규 기능 개발은
사실상 없고 대부분 설정·절차 작업이다.

### P1-1. 운영 CREATE 분기 1회 실증 (별도 승인 필요)

- **변경 파일**: 없음(설정만). `.env` `RUNNING_AI_DRAFT_PUBLISHING_ENABLED` 일시 true.
- **의존성**: `WORKOUT_PUBLISHING_ENABLED=false` 유지 필수(`PublishingModeGuard` XOR), 부상 게이트
  RESOLVED 상태, `INTERVALS_API_KEY`, 대상 날짜에 기존 이벤트가 없을 것.
- **테스트 방법**: `scripts\dev\preflight-intervals-date.ps1`로 예상 분기 CREATED 확인 →
  `GET /publish-preview` → `POST /{id}/publish` → Intervals 웹 육안 확인 → **재호출이 외부
  쓰기 0회로 저장된 결과를 반환**하는지 확인.
- **완료 기준**: 운영 `workout_draft_publication`에 `CREATED` + `verified=true` 1행, 재호출 Δ=0.
- **위험**: 실제 외부 쓰기. 날짜 오선택 시 기존 일정 덮어쓰기 → preflight로 사전 차단.

### P1-2. `publish_approved_draft`를 MCP로 도달 가능하게 (별도 승인 필요)

- **변경 파일**: `.runtime/mcp-gateway.cmd`(`RUNNING_AI_ALLOWED_TOOLS`에 추가),
  `tools/mcp-worker/src/index.js`(`SCOPES_SUPPORTED` / `FORWARDABLE_SCOPES`에 `runningai:publish`),
  양쪽 테스트 스위트.
- **의존성**: P1-1 선행 필수. 커넥터 **완전 삭제 후 재등록**으로 새 scope 재탐색(7F-3B-3에서
  입증된 유일한 방법 — 재연결만으로는 캐시된 scope가 그대로 재생된다).
- **테스트 방법**: 게이트웨이 테스트에 "publish scope 없는 토큰은 `tools/list`에서 보지도 못한다",
  worker e2e에 "publish는 명시 요청 시에만 발급" 추가.
- **완료 기준**: train 토큰 = 12개, publish 토큰 = 13개가 보이고, train 토큰의
  `publish_approved_draft` 호출이 **백엔드 트래픽 발생 전에** 403으로 거부.
- **위험**: **가장 높음.** 외부 쓰기 권한을 대화형 클라이언트에 여는 단계.
  `confirmedByAthlete` + `acknowledgedApprovalId` 이중 확인은 이미 구현되어 있다.

### P1-3. 게시 실패의 관측 가능성 (신규, 소규모)

- **공백**: 실패한 게시는 **저장되지 않는다**(설계). 따라서 실패 사유가 응답에만 존재하고
  사후 추적이 불가능하다.
- **변경 파일**: 신규 `V29__create_workout_draft_publish_attempt.sql`,
  `ApprovedWorkoutDraftPublishService`(실패 경로에 attempt 1행 기록), 테스트.
- **완료 기준**: 실패가 성공 행을 만들지 않으면서 시도 이력은 남는다. 기존 유니크 제약 불변.
- **위험**: 낮음. 단 **성공 경로의 트랜잭션 의미를 건드리지 말 것**.
- **버전 주의**: 이 항목은 원래 `V27`로 계획되어 있었으나, Phase 8A가 `V27`(garmin_sync_attempt)과
  `V28`(garmin_recovery_collection)을 먼저 사용했다. 다음 사용 가능한 번호는 **`V29`** 이다.

**구현 순서: P1-1 → P1-3 → P1-2** (관측 가능성을 권한 확대보다 먼저).

## P2. Intervals.icu → Garmin Connect → FR265 동기화 및 readback

**실태: Intervals 측 readback은 이미 구현·검증됨. 비어 있는 것은 "Garmin/시계에 실제로 도달했는지"를
기계가 확인할 방법이다. 현재는 5C-4의 수동 육안 확인 1회가 전부.**

> 중요: Intervals→Garmin 푸시는 **Intervals.icu 자체 연동**이다. 여기서 만들 것은 **푸시가 아니라
> 검증(읽기)** 이며, Garmin 쓰기 경로는 앞으로도 만들지 않는다.

### P2-1. 커넥터에 Garmin 예정 워크아웃 조회 추가 (읽기 전용)

- **변경 파일**: `tools/garmin-connector/garmin_connector/api.py`(신규 `GET /scheduled-workouts?date=`),
  `client.py`, `tests/`.
- **의존성**: `garminconnect` 라이브러리가 해당 조회를 지원하는지 **먼저 실측**. 미지원이면 이 항목
  전체가 NOT_FEASIBLE — 대안 없이 중단하고 수동 확인을 유지한다.
- **테스트 방법**: 커넥터 단위 테스트(합성 payload) + 실계정 1회 계약 실측.
- **완료 기준**: 날짜 1개에 대해 Garmin이 보유한 예정 워크아웃 목록을 원형 그대로 반환.
- **위험**: 외부 계약 미지. **라이브러리 미지원 가능성이 실질적으로 높다** — 선행 조사 필수.

### P2-2. 게시 후 도달 검증 서비스

- **변경 파일**: 신규 `integration/garmin/ScheduledWorkoutVerificationService.kt`, 신규
  `GET /api/v1/workout-drafts/{id}/delivery-status`, `V28` 결과 저장.
- **의존성**: P2-1 성공.
- **완료 기준**: 게시된 드래프트에 대해 `INTERVALS_VERIFIED` / `GARMIN_PRESENT` /
  `GARMIN_ABSENT` / `UNKNOWN`을 **판단 없이 사실만** 반환. 시계 자체 도달은 여전히 증명 불가이며
  `UNKNOWN`으로 명시한다.
- **위험**: 중간. Garmin 동기화 지연을 "실패"로 오판하지 않도록 시간 창 설계 필요.

**구현 순서: P2-1 실현 가능성 조사 → (가능할 때만) P2-2.** 불가 판정이어도 그것이 결론이다.

## P3. 1년 러닝 데이터 분석·회복·훈련부하 고도화

### 먼저 보고해야 할 사실: "1년 데이터"는 현재 어떤 경로로도 존재하지 않는다

운영 DB 실측 48건의 범위는 **2026-06-02 ~ 2026-10-11 (약 4.5개월)**. Garmin Connect는
2026-09-18 이전이 없고, Intervals는 2026-08-02부터, Strava 아카이브가 2026-06-02까지 끌어온
최대치다. 그 이전은 **API로도 아카이브로도 도달 불가**. 따라서 P3는 "1년"이 아니라
**"보유 전 구간의 밀도와 신선도를 올리는 것"** 으로 재정의해야 한다.

### P3-1. 데이터 신선도 복구 (최우선, 비용 0)

- **공백**: Garmin 증분 동기화 스케줄러 OFF → 최종 성공 2026-10-12. 신규 활동 자동 유입 없음.
- **변경 파일**: `.env`(`RUNNING_AI_GARMIN_SCHEDULER_ENABLED=true`) 또는
  `RUNNING_AI_COACH_REFRESH_SCHEDULER_ENABLED=true`.
- **완료 기준**: `garmin_sync_state.last_successful_sync_at`이 24시간 이내로 유지.
- **위험**: 낮음. 단 **백필·임포터는 스케줄러가 켜져 있으면 기동을 거부**하므로 과거 데이터
  작업과 동시에 켜지 말 것.

### P3-2. 회복 베이스라인 실사용 가능화

- **공백**: `garmin_recovery_daily` **21행** — `RecoveryBaselineService`의 28일 창을 못 채운다.
- **변경 파일**: 없음(기존 `POST /garmin/recovery-sync/backfill` 사용, 최대 28일).
- **완료 기준**: 유효일 7일 이상 확보 후 `INSUFFICIENT_DATA`가 아닌 베이스라인 반환.
- **위험**: 낮음. 커넥터 연속 호출이므로 첫 실패에서 즉시 중단(기존 동작).

### P3-3. 임포트 활동의 분석 공백 명시

- **공백**: Intervals/Strava 임포트분은 케이던스 지표가 없고, Strava 랩은 인터벌 그룹 도출이
  불가능하다. 현재 이 사실이 **코치 컨텍스트에 드러나지 않는다**(`dataCoverage`는 건수만 센다).
- **변경 파일**: `TrainingContextV2Builder` / `CoachContextSerializer`에 `metricUnavailability`
  (사실 나열, 품질 평가 아님) 추가, 테스트.
- **완료 기준**: 코치가 "케이던스 없음"을 "케이던스 나쁨"으로 오독할 수 없게 된다.
- **위험**: 낮음. **스냅샷 크기 상한(64 KiB) 재확인 필요**, 분석 엔진은 불변.

### P3-4. 훈련부하 고도화 — 하지 않는다

- `training_load`는 duration 기반이고, Intervals의 `training_load` / `intensity`는 이미 저장되어
  있으나 RunningAI 자체 부하 계산에는 쓰이지 않는다(역할 분리 원칙).
- **권고: 원칙을 유지하고 계산식을 바꾸지 말 것.** 두 값을 나란히 제시하는 일은 V2가 이미 한다.
  **추가 개발 불필요 — 중복 개발 방지 항목으로 명시한다.**

**구현 순서: P3-1 → P3-2 → P3-3.**

## P4. RunningAI Watchface 연동 완성

### P4-1. 릴레이를 Spring 백엔드에 연결

- **공백**: 릴레이가 Intervals를 직접 조회하고 REST 여부는 레거시
  `C:\running-ai\state\today_plan.json`에 의존한다. 즉 **승인된 AI 드래프트가 시계에 반영되지 않는다.**
- **변경 파일**: `tools/external-relay/server.js`(출처를 Spring으로), 신규 읽기 전용
  `GET /api/v1/today-workout`(Spring), 릴레이 테스트.
- **의존성**: Spring 18080 가용. **와이어 계약(`{date,status,label}`)은 한 글자도 바꾸지 말 것** —
  이미 설치된 워치페이스가 그 계약으로 동작한다.
- **완료 기준**: 레거시 JSON 파일 없이도 동일한 응답. 레거시 경로는 읽기만 유지하거나 의존을
  끊되 `C:\running-ai`는 **변경하지 않는다**.
- **위험**: 중간. 시계가 즉시 영향받는 유일한 경로 → 전환 전 병행 비교 검증 필요.

### P4-2. 릴레이 외부 전송 경로 안정화

- **공백**: ad-hoc quick tunnel — 재부팅마다 호스트명 소멸(MCP 터널에서 2026-10-14 실측된 것과
  동일한 문제).
- **선택지**: (a) Cloudflare Named Tunnel — **무료**, 고정 호스트명, 계정만 필요 /
  (b) 현행 유지 + 재부팅 절차 문서화.
- **완료 기준**: 재부팅 후 사람 개입 없이 시계가 동작하거나, 개입이 1단계로 축소.
- **위험**: 낮음(비용 0). 단 공개 노출 범위 재검토 필요 — 릴레이는 Bearer 게이트만 가진다.

### P4-3. 워치페이스 소스의 저장소 편입 (선택)

- **공백**: `C:\running-ai-watchface`가 버전 관리 밖.
- **권고**: 레거시 변경 금지 제약과 충돌하지 않는 **복사 편입**만 검토. 우선순위 최하.

**구현 순서: P4-1 → P4-2 → (선택) P4-3.**

---

## 전체 권고 순서

1. **P3-1 데이터 신선도** (비용 0, 위험 최저, 다른 모든 것의 입력)
2. **P3-2 회복 백필**
3. **P1-1 운영 CREATE 1회** — 별도 승인
4. **P1-3 게시 시도 이력**
5. **P4-1 릴레이 → Spring** (AI 드래프트가 시계에 도달하는 첫 경로)
6. **P2-1 Garmin 조회 실현 가능성 조사**
7. **P1-2 publish scope 개방** — 별도 승인, 가장 높은 위험
8. P3-3, P4-2, (P2-2)

## 하지 않을 것 (중복 개발 방지)

드래프트 생성/수정/승인 API, Intervals 렌더러·게시자·readback, 부상 게이트, 분석 엔진,
TrainingContext V2, 임포터 3종, 백필, 워치독, MCP read/train 도구, OAuth 경로 — **전부 구현 완료**.
훈련부하 계산식 교체(P3-4)도 하지 않는다.
