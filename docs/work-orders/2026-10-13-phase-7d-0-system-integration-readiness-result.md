# WO-RUNNINGAI-PHASE-7D-0 — 전체 시스템 통합 검증 및 운영 반영 준비 점검 (결과 보고)

지시서: `2026-10-13-phase-7d-0-system-integration-readiness-instruction.md`
수행일: 2026-10-13 (KST) · 브랜치 `main-3` @ `8730f00` · 수행 PC: Main PC

---

## 0. 최종 판정 — **CONDITIONAL GO**

| 항목 | 판정 |
|---|---|
| 코드 품질 / 회귀 | **GO** — Spring 1,272/1,272, Gateway 29/29, Connector 134/134, Relay 14/14, Windows 스크립트 229/229. 전부 이번 세션에서 실제 실행 |
| DB 마이그레이션 V21→V23 | **GO** — 운영 **실데이터 사본 DB**에서 V20→V23 무손실 적용 (252 ms, 활동 8/8 · 샘플 21,828 · Draft 19건 전부 보존) |
| 신규 기능 (7B-2 / 7B-3 / 7B-4 / 7C-2a / 7C-2b) | **GO** — 사본 DB 실데이터로 전 경로 실증 |
| 운영 반영(배포) | **CONDITIONAL** — 아래 선행조건 4건 충족 전 배포 금지 |
| ChatGPT ↔ 실데이터 외부 연동 | **NO-GO** — Auth0/게이트웨이 미구축 + raw `/mcp` 스택트레이스 유출 재현됨 |

### CONDITIONAL GO 선행조건 (4건)

| # | 조건 | 근거 |
|---|---|---|
| C1 | **배포 직후 "부상 상태 보고"가 운영 절차에 포함되어야 한다.** V21 적용 순간부터 안전 게이트가 fail-closed로 동작하여 REST 외 모든 Draft 생성·수정·승인·게시가 차단된다. | 사본 DB 실측: 배포 직후 상태 = `NO_INJURY_STATE_RECORDED`, EASY Draft 승인 → HTTP 422 |
| C2 | **Canonical FF 병합 + 재빌드 + 재기동을 하나의 단위로 수행해야 한다.** 운영 Spring은 `C:\running-ai-github\server\build\libs\running-ai-server-0.0.1-SNAPSHOT.jar`(= `main` @ `370ee8f`)로 기동 중이고 V21~V23 코드는 `main-3`에만 있다. 병합 없이 재기동하면 stale jar가 그대로 뜬다. | `Win32_Process` CommandLine 실측 + `[[main-pc-environment]]`의 stale-jar 사례 |
| C3 | **`RUNNINGAI_MCP_READ_ENABLED`는 계속 false로 유지한다.** 게이트웨이 없이 raw Spring `/mcp`를 노출하면 미지 메서드에서 HTTP 500 + **Java 스택트레이스 전문**이 응답 본문으로 나간다. | RISK-H1 (이번 세션 재현) |
| C4 | **레거시 Main PC 쓰기 작업(`RunningAI-TrainingCommand` / `RunningAI-CommandChannel` / `RunningAI-TodayWorkout`)은 Disabled를 유지한다.** 현재 전부 Disabled이며 이 상태가 "하나의 캘린더에 두 개의 writer 금지" 불변식을 지탱한다. | `Get-ScheduledTask` 실측 |

> 이번 Phase에서 **운영 배포 · 운영 DB 변경 · 운영 Flyway 적용 · Garmin Sync 실행 · Intervals.icu 게시 · Tailscale Funnel 변경 · Watchface 변경 · Auth0 연동 · Canonical main 병합 · origin push · 레거시 저장소 변경은 일절 수행하지 않았다.** 검증은 전부 읽기 전용 또는 격리된 사본 환경에서 수행했고 임시 자원은 모두 철거했다(§9).

---

## STEP 1 — Git 정합성 검증

### 1-1. HEAD와 브랜치 관계

| 참조 | HEAD | 비고 |
|---|---|---|
| `origin/main` | `370ee8f` | |
| `main` (Canonical `C:\running-ai-github`) | `370ee8f` | working tree **clean**, origin/main과 동일 |
| `main-3` (개발 worktree) | `8730f00` | main보다 **12 커밋 앞섬**, 분기 없음 |
| `main-3-2` (worktree) | `ddefd48` | main의 **조상** — 분기가 아니라 42 커밋 뒤처진 stale worktree |
| `origin/main-3` | `24ff0b5` | main-3보다 11 커밋 뒤처짐 (push 미수행 — 지시대로) |

```
origin/main = main (370ee8f)
                   └─ 24ff0b5 (= origin/main-3)
                          └─ … 11 commits … ─► 8730f00 (main-3)   [FF 가능]

ddefd48 (main-3-2) ──► main의 조상 (stale, 42 커밋 뒤처짐)
```

### 1-2. FF-only 병합 가능 여부 — **가능 (실제 병합 미수행)**

```
git merge-base --is-ancestor main main-3   → true
git rev-list --count main..main-3          → 12
git log --oneline main-3..main             → (비어 있음)
```

`main-3`는 `main`의 **엄격한 후손**이므로
`git -C C:\running-ai-github merge --ff-only main-3`가 충돌 없이 성립한다.
지시대로 **실제 병합은 수행하지 않았다.**

### 1-3. 지정 커밋 포함 여부

| 커밋 | 내용 | main | main-3 | origin/main | origin/main-3 |
|---|---|:--:|:--:|:--:|:--:|
| `50f86d0` | Phase 7B-4 LTHR provenance (V23) | ✗ | **✓** | ✗ | ✗ |
| `8730f00` | Phase 7C-3 STEP 1 준비 | ✗ | **✓** | ✗ | ✗ |

두 커밋 모두 `main-3`에만 존재 → **운영 미반영** 상태가 사실로 확인됨.

### 1-4. main-3의 12개 커밋

| # | SHA | 내용 | 마이그레이션 |
|---|---|---|---|
| 1 | `24ff0b5` | docs: Phase 7A-1 서울레이스 하프 분석 | — |
| 2 | `e1766cd` | feat(injury): 영속 부상/통증 안전 게이트 | **V21** |
| 3 | `b7903a8` | feat(analysis): Garmin 러닝 다이내믹스 + LTHR 출처 | **V22** |
| 4 | `b173c3b` | docs: 7C-0 ChatGPT MCP 사전설계 | — |
| 5 | `2d508f5` | feat(mcp-poc): TEST_ONLY MCP PoC | — |
| 6 | `8c22011` | docs: 7C-1.1 E2E PASS + 7C-2 설계 | — |
| 7 | `0663419` | feat(mcp): 읽기 전용 MCP 도구 7종 | — |
| 8 | `5b5b00c` | feat(mcp-gateway): OAuth 2.1 리소스 서버 게이트웨이 | — |
| 9 | `4336b97` | docs: 7종 실데이터 검증 기록 | — |
| 10 | `03e3044` | docs: 7B-4 설계 + 부상 체크리스트 문구 수정 | — |
| 11 | `50f86d0` | feat(athlete): LTHR provenance 원장 | **V23** |
| 12 | `8730f00` | docs+test: 7C-3 STEP 1 | — |

diff 총계: **99 files changed, 7,537 insertions(+), 62 deletions(-)**

### 1-5. 미커밋·미추적 파일 — 6건 (전부 문서, 소스 변경 0)

```
?? docs/work-orders/2026-10-10-phase-6i-1-6c-spring-port-config-consistency-instruction.md
?? docs/work-orders/2026-10-10-phase-6i-1-6c-spring-port-config-consistency-result.md
?? docs/work-orders/2026-10-10-phase-6i-1-6c-3-spring-port-propagation-instruction.md
?? docs/work-orders/2026-10-10-phase-6i-1-6c-3-spring-port-propagation-result.md
?? docs/work-orders/2026-10-12-phase-7b-1-analysis-quality-and-ai-coach-draft-pilot-instruction.md
?? docs/work-orders/2026-10-12-phase-7b-1-analysis-quality-and-ai-coach-draft-pilot-result.md
```

추적 파일 변경(M/A/D) **0건**. 빌드 산출물(`server/build`, `server/.gradle`)은 `.gitignore` 적용 확인.
→ **DEBT-1 (Low)**: 3개 Phase(6I-1.6C, 6I-1.6C-3, 7B-1)의 작업지시서·결과보고서가 3~9일째 미커밋 상태. 기록 유실 위험.

### 1-6. 작업지시서·결과보고서 누락 (Phase 6I 이후)

| 문서 | 상태 |
|---|---|
| `2026-10-06-phase-6i-1-external-access-e2e-stabilization-instruction.md` | result 없음 (progress-1/2만 존재) |
| `2026-10-09-...-repo-managed-external-relay-reboot-recovery-progress-2-instruction.md` | result 없음 |
| `2026-10-11-phase-6i-1-7d-1-supervised-watchdog-activation-instruction.md` | result 없음 (7D-1A/7D-2로 대체된 것으로 보이나 명시 기록 없음) |
| `2026-10-13-phase-7c-1-1-funnel-chatgpt-mcp-e2e-result.md` | instruction 없음 |
| `2026-10-13-phase-7c-2-realdata-readonly-verification-result.md` | instruction 없음 |
| Phase 7C-3 | `...-7c-3-realdata-oauth-runbook.md`만 존재, instruction/result 쌍 없음 |

> 2026-09-30 이전의 "instruction만 있고 result 없음" 20여 건은 당시 명명 규칙(결과를 `-result.md`가 아닌 본문 파일에 기록)의 산물이며 실제 누락이 아니다.

### 1-7. 불필요한 임시 파일·테스트 산출물

- 저장소 내: **없음**. `tools/mcp-gateway/node_modules/`는 `.gitignore`의 `node_modules/`로 제외(커밋 `5b5b00c`에서 추가됨).
- 비밀정보 스캔: `git diff main..main-3` 전문에 API key / 비밀번호 / 토큰 / 개인키 패턴 **0건**.
- 운영 PC 측: 2026-10-12 09:52부터 Garmin ConnectIQ 시뮬레이터(`monkeydo` cmd PID 41280 + java PID 7568)가 Watchface를 띄운 채 **약 26시간 방치** → **DEBT-2 (Low)**.

---

## STEP 2 — 시스템 아키텍처 검증

### 2-1. 전체 데이터 흐름 (현재 코드 기준)

```
[Garmin Connect]                                  [Intervals.icu]
   │ Python connector 127.0.0.1:8765                 │ HTTPS, API key = env only
   │ (Spring은 Garmin 자격증명을 절대 보유하지 않음)      │
   ▼                                                 ▼
GarminActivitySource / GarminRecoveryClient    IntervalsReadClient
GarminActivityDetailSource / LactateThreshold  (GET 전용 · 구조적으로 write 메서드 없음)
   │                                                 │
   ▼  raw-first                                      ▼  raw-first
activity_raw · activity_raw_payload            intervals_raw_payload
   │   ── 순수 매퍼 (외부 필드명은 여기서 끝난다) ──
   ▼
┌──────────────── PostgreSQL 17 · Flyway V1..V23 ────────────────┐
│ activity · activity_detail · activity_lap · activity_zone       │
│ activity_sample(1 Hz) · activity_detail_collection              │
│ activity_analysis (+V22 running dynamics)                       │
│ activity_analysis_interval[_group]                              │
│ activity_source_link · activity_intervals_metrics               │
│ intervals_fitness_daily · garmin_recovery_daily                 │
│ athlete_intensity_profile (+V23 history ledger)                 │
│ injury_report (V21)                                             │
│ workout_draft (+V20 context snapshot) · _approval · _publication│
│ historical_backfill_run · _activity · garmin_sync_state         │
└─────────────────────────────────────────────────────────────────┘
   │ DB-only (Garmin/Intervals 호출 0회)
   ▼
RunningActivityAnalysisService ──► RunningDynamicsCalculator (V22, 랩 extra_metrics)
   │   측정 전용 — 등급·점수·임계값·좋음/나쁨 라벨 금지
   ▼
TrainingContextV2Builder (DB-only)
   └─► CoachContextSerializer (canonical JSON + SHA-256, 64 KiB 가드)
   │
   ├──[ WorkoutSafetyGateService : 시스템 전체 단일 안전 정책 ]──┐
   ▼                                                            │
ClaudeAiCoach (Claude CLI 세션 사용, 자격증명 미보유)             │
   └─► WorkoutDraft(DRAFT) ──revision──[게이트]────────────────┘
                 │
          approve ─[게이트]─► APPROVED
                 │
       publish-preview ─[게이트]─► publish ─[게이트]
                                      │ RUNNING_AI_DRAFT_PUBLISHING_ENABLED
                                      ▼
            IntervalsWorkoutRenderer → IntervalsWorkoutPublisher
            → HttpIntervalsWorkoutClient   ★ 저장소 내 유일한 Intervals write 경로

[ChatGPT] ──HTTPS──► Tailscale Funnel :443 (계획: --set-path /mcp)
                          └─► mcp-gateway (OAuth 2.1 RS, 127.0.0.1:17910)
                                 │ JWT 검증 + 메서드/도구 allowlist + fail-closed
                                 ▼
                          Spring POST /mcp (STATELESS)
                                 │ running-ai.mcp.read-enabled
                                 ▼
                          ReadMcpTools (읽기 전용 7종)

[Garmin FR265 Watchface] ──HTTPS──► Tailscale Funnel :443 ──► external-relay :17845
                                     (Bearer + rate limit) ──► Intervals.icu (GET)
```

### 2-2. 서비스 간 책임 분리 — **PASS**

- 레이어링 `Controller → Service → Entity → Repository` 유지. 모든 `@*Mapping` 메서드는 서비스 위임 또는 DTO 변환만 수행(전수 확인).
- Garmin 필드명은 Garmin 매퍼에서, Intervals 필드명은 enrichment 매퍼에서 종료 → 도메인 오염 없음.
- `analysis` 패키지는 측정 전용. 해석(등급·판단)은 코치 몫으로 구조적으로 분리.
- **읽기 전용 MCP의 구조적 보증** — `McpReadToolsArchitectureTest`가 3중으로 강제:
  1. 소스에 publish/draft/sync/transport 타입 참조 금지 (문자열 스캔)
  2. `.save(` / `.saveAll(` / `.delete` / `.analyse(` / `.upsert` / `.replace(` / `.record(` 패턴 금지
  3. 빈 생성자·@Bean 파라미터에 `integration.intervals` · `integration.garmin` · `draftpublish` · `coachrefresh` · `backfill` 패키지 타입 금지 (리플렉션)
  → 한계는 §STEP 5-4 참조.

### 2-3. 트랜잭션 경계 — **PASS** (주목할 설계 1건)

- `AthleteIntensityProfileService.replaceDefaultProfile` / `mergeGarminSnapshot`:
  **비관적 행 잠금(`findWithLockingByAthleteId`) → 프로필 쓰기 → 원장 insert**를 하나의 트랜잭션으로 수행.
  원장 insert 실패 시 프로필 변경도 함께 롤백되고, 동시 쓰기가 직렬화되어 각 원장 행의 `previous_*` 스냅숏이 **실제로 대체한 값**을 가리킨다.
- `InjuryStateService.record`: append-only insert 전용. `current()` / `history()`는 `readOnly = true`.
- 상세 수집은 **파트별 자체 커밋**(`activity_raw_payload` → 매퍼 → 파생 테이블 교체), Historical Backfill은 **작업 단위당 1 커밋 체크포인트** → 중단 후 재개 가능.
- `spring.jpa.open-in-view: false` — 뷰 렌더링 구간에 트랜잭션이 열려 있지 않음.

### 2-4. 중복 데이터 처리 / 멱등성 — **PASS**

| 경로 | 멱등 장치 |
|---|---|
| 활동 수집 | 외부 식별자 `external_source + external_id` 유니크 (중복 POST → 409) |
| Garmin 회복 | `garmin_recovery_daily` athlete+date 유니크 **merge** — 누락 지표가 저장값을 지우지 않음 |
| 활동 상세 | 파트 단위 replace + `activity_detail_collection`에 파트별 상태 기록 |
| Intervals 매칭 | `activity_source_link` 유니크. 0건=UNMATCHED(정상), 2건+=AMBIGUOUS(자동 연결 금지), 타 활동 선점 시 409 `INTERVALS_LINK_CONFLICT` |
| 워크아웃 게시 | per-date Intervals 마커(`external_id`) + CREATE/UPDATE/NO_CHANGE + **readback 검증**, blind POST 재시도 금지 |
| Draft 게시 | `workout_draft_publication` draft당 유니크, 실패는 저장하지 않음, 재게시는 저장된 결과 반환 |
| 승인 | athlete+date당 승인 Draft 1건 (실측: `WORKOUT_DATE_ALREADY_APPROVED` → 409) |
| 단일 비행 | 게시는 per-date, Draft 게시는 per-draft in-JVM single-flight |
| LTHR 원장 | 동일값 재제출 = `applied=false` 관측 행(변경 아님) |

### 2-5. 재시도 정책 — **의도적 "재시도 없음"**

| 경로 | 정책 |
|---|---|
| Garmin 커넥터 | `Garmin(retry_attempts=0)` — 라이브러리 재시도 비활성, 한 요청 = 한 Garmin 호출 |
| Intervals | 호출당 요청 1회, 재시도 없음, 429 즉시 중단 |
| 회복 백필 | 순차 처리, 일자 간 2 s 지연, 첫 실패에서 중단 |
| 게시 스케줄러 | 재시도 없음, 놓친 실행 보충 없음 |
| Watchdog | 컴포넌트당 10분/3회 재시작 예산 + 24시간/6회 **실패** 시 영구 LOCKED_OUT (운영자가 `clear-watchdog-lockout.ps1`로만 해제) |

설계 의도(외부 API 보호 · 중복 쓰기 방지)에 부합. 대가는 **일시적 네트워크 오류가 곧바로 사용자 가시 실패가 된다**는 점 → DEBT-5.

### 2-6. 외부 API 장애 대응 — **PASS (실측)**

사본 DB 검증 인스턴스에서 Garmin/Intervals base-url을 **죽은 포트 `127.0.0.1:9`로 고정**했음에도
기동 성공 + 읽기 전 경로 정상 응답 + 로그에 외부 호출 시도 **0건**.
→ 외부 시스템 장애가 DB 기반 읽기·분석·컨텍스트 생성을 막지 않는다.

- Backfill: discovery 캡 초과 시 **PAUSE**(절단이 아니라 정지), 분석 실패·링크 충돌도 PAUSE. FAILED는 재개 불가(의도).
- 전 컴포넌트가 health 엔드포인트를 제공하고 Watchdog가 이를 소비.

### 2-7. 인증 및 비밀정보 관리

| 항목 | 상태 |
|---|---|
| Garmin 자격증명 | Spring에 **없음**. 로컬 Python 커넥터의 토큰 스토어(`~/.garminconnect`)만 사용 |
| Intervals API key | 환경변수 `INTERVALS_API_KEY`만. `.env`는 git-ignored |
| Claude | CLI 자체 로그인 세션 사용. `ANTHROPIC_API_KEY`를 읽거나 저장·전달하지 않음 |
| Spring REST API | **인증 없음** — `SERVER_ADDRESS=127.0.0.1` 루프백 바인딩이 유일한 경계 (실측 확인) |
| External Relay | 루프백 바인딩 + Bearer 토큰 + 분당 20회 rate limit + 미지 경로 404 (테스트 14/14 PASS) |
| mcp-gateway | 루프백 하드코딩, Auth0 JWT(iss/aud/sig/exp/nbf/scope/sub, RS256 + 원격 JWKS), fail-closed |
| 로깅 | 게이트웨이·릴레이 모두 Authorization/토큰/본문 비로깅 + 정규식 레드랙션 |
| **PostgreSQL** | 컨테이너 포트가 `0.0.0.0:5432`로 **모든 인터페이스에 바인딩** → **RISK-M1** |

### 2-8. 데이터 수집 신선도 — **주의 필요**

운영 `.env` 실측 기준, 자동 수집은 **Garmin 프로필 동기화(매일 04:30 KST) 하나뿐**이다.

| 스케줄러 | 운영 설정 | 효과 |
|---|---|---|
| `GARMIN_PROFILE_SYNC_ENABLED` | **true** (cron `0 30 4 * * *`) | 유일한 자동 Garmin 호출 |
| Garmin 활동 sync 스케줄러 | 미설정 → false | 활동 수집은 **수동 / coach-refresh에서만** |
| `RUNNING_AI_COACH_REFRESH_SCHEDULER_ENABLED` | 미설정 → false | 자동 갱신 없음 |
| `WORKOUT_PUBLISHING_SCHEDULER_ENABLED` | false | |

운영 DB 실측: 활동 **8건**, 최신 `2026-10-10`. 회복 21일, Intervals fitness 99일, Draft 19건.
→ **RISK-M2**: 코치 호출 직전에 `POST /api/v1/coach/data-refresh`를 돌리지 않으면 Draft가 **최대 수일 묵은 데이터**로 생성될 수 있다. 설계상 `TrainingContextV2`가 `dataCoverage`에 소스 날짜·age를 사실 그대로 싣고 코치가 판단하므로 "조용한 왜곡"은 아니지만, 운영 절차로 보완해야 한다.

### 2-9. 수집 실패 후 복구 정책 — **PASS**

- Backfill: PAUSED = 안전 정지(사람이 `/resume`), 체크포인트 1건/작업단위, 재개 시 discovery는 offset 0부터, `garmin_sync_state`를 절대 건드리지 않음.
- 상세 수집: `/reprocess`가 저장된 raw에서 **Garmin 호출 0회**로 재계산.
- 분석: `POST /api/v1/activities/{id}/analysis`가 파생 행을 교체 재계산 — 이번 검증에서 실증(0.85 s, Garmin 호출 0).
- 스트림 충실도: DOWNSAMPLED/UNKNOWN을 다른 `maxChartSize`로 **자동 재요청하지 않음**(정책), Backfill에서는 PAUSE.

---

## STEP 3 — DB 및 Flyway 검증

**운영 DB는 변경하지 않았다.** 검증은 `pg_dump -Fc` → 신규 DB `running_ai_7d0` 복원 → 해당 사본에만 Flyway 적용하는 방식으로 수행했고, 검증 종료 후 사본 DB와 덤프 파일을 모두 삭제했다.

### 3-1. 운영 DB 현황 (읽기 전용)

```
flyway_schema_history : 20행, 전부 success=t, 최신 = V20 (2026-10-03 12:51)
DB 크기               : 27 MB
athlete 1 · activity 8 · activity_analysis 8 · activity_sample 21,828
garmin_recovery_daily 21 · intervals_fitness_daily 99 · workout_draft 19
athlete_intensity_profile 1 (LTHR 176 bpm / 290 s·km⁻¹, updated 2026-10-05)
injury_report / athlete_intensity_profile_history : 테이블 자체가 없음 (V21/V23 미적용)
```

### 3-2. 사본 DB에서의 V20 → V23 적용 결과 — **PASS**

```
Current version of schema "public": 20
Migrating to version "21 - create injury report"                       → 103 ms
Migrating to version "22 - add running dynamics to activity analysis"  → 116 ms
Migrating to version "23 - create athlete intensity profile history"   →  33 ms
Successfully applied 3 migrations, now at version v23 (총 252 ms)
→ 애플리케이션 기동 성공 (ddl-auto: validate 통과) · /actuator/health = UP
```

### 3-3. 기존 데이터 보존 — **무손실**

| 테이블 | 적용 전 | 적용 후 |
|---|--:|--:|
| athlete | 1 | 1 |
| activity | 8 | 8 |
| activity_analysis | 8 | 8 |
| activity_sample | 21,828 | 21,828 |
| garmin_recovery_daily | 21 | 21 |
| intervals_fitness_daily | 99 | 99 |
| workout_draft | 19 | 19 |
| injury_report | (없음) | 0 (신규 빈 테이블) |
| athlete_intensity_profile_history | (없음) | 0 (신규 빈 테이블) |

V22는 `activity_analysis`에 **nullable 컬럼 24개를 추가**만 하며 기존 8행은 전부 NULL로 남는다(재분석 전까지). 기본값도, 0도, 추정치도 넣지 않는다 — 설계 의도와 일치.

### 3-4. 제약조건과 인덱스 — **PASS**

```
injury_report:
  injury_report_pkey (p)
  fk_injury_report_athlete (f)             → athlete(id)
  ck_injury_report_status (c)              → ACTIVE | RESOLVED | UNKNOWN
  ck_injury_report_side (c)                → NULL | LEFT | RIGHT | BOTH | UNSPECIFIED
  ck_injury_report_pain_intensity (c)      → NULL | 0..10
  idx_injury_report_athlete_recorded       → (athlete_id, recorded_at DESC)

athlete_intensity_profile_history:
  athlete_intensity_profile_history_pkey (p)
  fk_profile_history_athlete (f)           → athlete(id)
  ck_profile_history_source (c)            → MANUAL | GARMIN
  idx_profile_history_athlete_recorded     → (athlete_id, recorded_at DESC)
```

두 인덱스 모두 "최신 1건 조회"(`findFirstBy...OrderByRecordedAtDesc`)와 정확히 일치.

### 3-5. 마이그레이션 중 장애 발생 시

- PostgreSQL의 DDL은 트랜잭션 안에서 동작하고, Flyway는 **마이그레이션 1개 = 1 트랜잭션**으로 실행한다. V21/V23은 `CREATE TABLE`+`CREATE INDEX`, V22는 `ALTER TABLE ... ADD COLUMN`(테이블 재작성 없는 메타데이터 변경)으로 전부 **원자적**이며 실패 시 해당 버전은 통째로 롤백된다.
- 세 마이그레이션 모두 **순수 additive**: 기존 컬럼 삭제·타입 변경·데이터 이동이 없다 → 적용 중 기존 읽기 경로가 깨질 여지가 없다.
- 총 252 ms(27 MB DB) → 잠금 유지 시간이 실질적으로 0에 가깝다.

### 3-6. 구버전 애플리케이션과 V23 스키마의 호환성 — **호환됨 (중요)**

세 마이그레이션이 모두 additive이므로 **V23 스키마 위에서 구버전(V20 기준) jar를 기동해도 Hibernate `validate`가 통과**한다. 구버전은 새 테이블/컬럼을 매핑하지 않을 뿐 기존 매핑은 전부 유효하다.

→ **운용상의 의미**: 코드 롤백(구버전 jar 재기동)이 **스키마 되돌리기 없이** 성립한다. 이것이 §7 롤백 Runbook의 근간이다.
→ **반대 방향은 성립하지 않는다**: V21~V23 코드를 V20 스키마에서 기동하면 `injury_report` 등 미존재 테이블 때문에 Hibernate validate가 실패하여 기동이 중단된다. 즉 **"코드 먼저, 스키마 나중"은 불가**하고, Flyway가 기동 시 자동 적용하므로 순서가 구조적으로 보장된다.

### 3-7. Flyway 이력 수동 삭제 금지

`flyway_schema_history` 행을 손으로 지우는 것은 **정상 롤백 절차가 아니다.** V21~V23은 additive이므로 되돌릴 필요 자체가 없고, 남아 있어도 무해하다. 스키마 되돌리기가 꼭 필요한 예외 상황에서는 **DROP이 아니라 백업 복원**(§7-C)을 사용한다. 이 원칙은 커밋 `8730f00`에서 7B-4 결과보고서의 롤백 절에 이미 반영되어 있다.

### 3-8. 백업 및 복원 가능성 — **검증됨**

이번 검증 자체가 백업·복원의 실증이다.

```
docker exec running-ai-postgres pg_dump -U running_ai -d running_ai -Fc -f /tmp/ra.dump
   → 2,837,204 bytes (약 2.8 MB), 운영 Spring 접속 중에도 MVCC 스냅숏으로 무중단 수행
docker exec running-ai-postgres psql -U running_ai -d postgres -c "CREATE DATABASE running_ai_7d0 ..."
docker exec running-ai-postgres pg_restore -U running_ai -d running_ai_7d0 --no-owner /tmp/ra.dump
   → 복원 후 테이블 25개 · 전 행수 · flyway 20행 전부 원본과 일치
```

27 MB / 2.8 MB 규모에서 백업·복원 각각 수 초. **배포 전 백업은 비용이 사실상 없다.**

---

## STEP 4 — 핵심 기능 통합 테스트

검증 환경(전부 격리, 운영 무접촉):

| 요소 | 내용 |
|---|---|
| 코드 | `main-3` @ `8730f00`, `gradlew bootJar` |
| DB | **운영 실데이터 사본** `running_ai_7d0` (pg_dump/restore), V21~V23 신규 적용 |
| Spring | 포트 **18095**, `SERVER_ADDRESS=127.0.0.1` |
| 스위치 | 게시 3종 전부 false, `RUNNINGAI_MCP_ENABLED=false`, `RUNNINGAI_MCP_READ_ENABLED=true`, 스케줄러 전부 false |
| 외부 | Garmin/Intervals base-url = `http://127.0.0.1:9` (죽은 포트) → 외부 호출 구조적 불가 |
| 운영 측 | 18080 Spring · 8765 커넥터 · 17845 릴레이 · Funnel 전부 무접촉 |

### 4-1. Garmin 활동 수집 및 재처리 — **PASS**

- 사본에 운영 실데이터 8건(RUN 5 / TREADMILL_RUN 등) 보존. `GET /api/v1/activities` 정상.
- `POST /api/v1/activities/7/analysis` (하프마라톤, 6,723 s / 21.24 km) → **HTTP 200, 0.85 s, Garmin 호출 0건** (로그에 외부 호출 시도 없음). 재처리가 저장된 raw만으로 성립함을 실증.

### 4-2. 1 Hz 샘플·랩 데이터 정합성 — **PASS**

- `activity_sample` 21,828행이 마이그레이션 전후 동일.
- 분석 결과 `inputSampleCompleteness = FULL`, `status = COMPLETE`.
- `TrainingContextV2`의 `dataCoverage.fullSampleActivityCount = 8` = `analysedActivityCount = 8` → 실데이터에서는 두 값이 **일치**. (7C-2 스모크에서 관찰된 `0 vs FULL` 불일치는 합성 시딩 산물이었음이 실데이터로 재확인됨.)

### 4-3. 러닝 다이내믹스 분석 (Phase 7B-3 / V22) — **PASS**

재분석 전: `runningDynamics.lapsWithDynamicsCount = 0` (V22 컬럼 전부 NULL — 기대대로)
재분석 후 (활동 7, 2026-10-11 하프):

```
lapsWithDynamicsCount            22
avgStrideLengthCm               107.62   (1st 110.00 → 2nd 105.23, -4.33 %)
samePaceStrideLengthChangeCm     -2.23   (같은 페이스대 랩 비교)
avgVerticalOscillationCm          8.72   (+0.03 %)
avgVerticalRatioPercent           8.13   (+4.58 %)
avgGroundContactTimeMs          246.26   (242.30 → 250.23, +3.27 %)
avgRunningPowerWatts            320.95 / max 653.0  (329.82 → 312.09, -5.37 %)
samePaceRunningPowerChangeWatts  -9.0
```

하프 후반 피로(보폭 감소 · 접지시간 증가 · 파워 감소)와 **물리적으로 정합**하며, 어떤 등급·점수·좋음/나쁨 라벨도 포함되지 않음 → `analysis` 패키지 불변식 유지.

### 4-4. 젖산역치 출처 및 변경 이력 (Phase 7B-4 / V23) — **PASS**

| 단계 | 결과 |
|---|---|
| A. 기준선 (원장 이전 값 176/290) | `sourceTracked=false` + `"Source ... is not tracked for this value; treat its provenance as unconfirmed."` |
| B. **동일값** 176/290 재제출 | 원장에 `applied=false` 관측 행 기록, `sourceTracked` **여전히 false** |
| C. 변경값 177/289 제출 | `applied=true`, `sourceTracked=true`, `source="MANUAL"`, `lastAppliedChangeAt` 기록 |

원장 실측:

```
 id | source | applied | new_bpm | prev_bpm | new_pace | prev_pace
  1 | MANUAL | f       |     176 |      176 |      290 |       290
  2 | MANUAL | t       |     177 |      176 |      289 |       290
```

**핵심 불변식 실증**: 관측 행(`applied=false`)은 값의 출처를 **소급 인증하지 않는다.** 원장 이전 값은 끝까지 "출처 미확인"으로 남는다 — 7B-4 요구사항 4와 정확히 일치.

### 4-5. Intervals.icu 훈련부하 — **PASS**

`get_fitness_trend(2026-09-20 ~ 2026-10-12)` → 23일 저장, 용어 명시 포함:

```
"terminology": "ctl = Intervals.icu calculated fitness; atl = Intervals.icu calculated fatigue;
                derivedForm = ctl - atl, RunningAI-derived"
2026-09-20  ctl 14.63  atl 22.76  derivedForm -8.13
2026-09-21  ctl 14.29  atl 19.73  derivedForm -5.45  …
```

주관적 `fatigue` 필드가 ATL로 매핑되지 않음(용어 불변식 유지), `derivedForm`이 RunningAI 파생임을 응답 자체가 선언.

### 4-6. Garmin 회복 지표 — **PASS**

`get_recovery_context` → 실데이터 기반 개인 기준선 포함:

```
hrv.lastNightAvgMs : current 30.0 / baseline 42.7 / -29.8 % / sampleCount 18 / baselineStatus AVAILABLE
sleep.durationHours: current 3.43 …
garminHrvStatus    : BALANCED
```

기준선은 28일 창·최소 7일 유효 규칙대로 AVAILABLE, 측정값만 제시하고 등급 판정 없음.

### 4-7. TrainingContextV2 구성 — **PASS**

```
contextVersion V2 · sizeBytes 11,038 (가드 65,536 이내)
sha256 7625e633d2680c88b38f854c77d2c1f6d2b234b1ea402d636062ef443b50fc00
dataCoverage: historyWindowDays 90 · supportedActivityCount 8 · analysedActivityCount 8
              fullSampleActivityCount 8 · intervalsMatchedActivityCount 8
              fitnessWindowDays 90 / 89일 보유 · recoveryWindowDays 28 / 21일 보유
              oldestActivityDate 2026-09-18 · newestActivityDate 2026-10-11
```

DB-only(Garmin/Intervals 호출 0), 결정적 canonical JSON + SHA-256, 크기 가드 내.

### 4-8. AI Coach 분석 및 추천 — **부분 검증 (의도적)**

- **결정론적 추천 경로**는 실증: 안전 게이트 해제(격리 사본) 후
  `get_workout_recommendation` → `intent=EASY, 30~60분, confidence=LOW, dataSufficiency=MEDIUM, reasons=[RECENT_REST, LOAD_TREND_DECREASING, QUALITY_HISTORY_UNAVAILABLE, DEFAULT_EASY]`, 응답에 "nothing was stored, synced or published by this call" 명시.
- **Claude CLI 실호출 Draft 생성은 수행하지 않았다.** 이번 Phase는 검증 전용이고, Draft 생성은 DB 쓰기 + 외부 LLM 호출을 동반한다. 품질 근거는 Phase 7B-1(Draft id=19, 통증 피드백 반영 REST 처방, 8개 항목 전부 ✅)의 기록으로 대체한다.

### 4-9. Injury Safety Gate (Phase 7B-2 / V21) — **PASS (4개 분기 전부 실증)**

| # | 상태 | 결과 |
|---|---|---|
| 1 | 보고 이력 없음 (= **배포 직후의 실제 상태**) | `NO_INJURY_STATE_RECORDED`, 비REST 차단. EASY Draft 16 승인 → **HTTP 422** `WORKOUT_SAFETY_NO_INJURY_STATE_RECORDED` |
| 2 | 같은 상태에서 **REST** Draft 19 승인 | **HTTP 200 APPROVED** — REST는 항상 허용 |
| 3 | ACTIVE(무릎/우측/통증 4/계단통증) 보고 | `INJURY_ACTIVE`, 비REST 차단. EASY 승인 → `WORKOUT_SAFETY_INJURY_ACTIVE` |
| 4 | RESOLVED 보고 *(격리 사본 전용 시나리오)* | `INJURY_RESOLVED_CONFIRMED`, 허용. EASY 승인 → **409 `WORKOUT_DATE_ALREADY_APPROVED`** = 안전 게이트는 통과하고 **그 다음 업무 규칙**이 걸린 것 → 게이트 해제 자체가 정상 동작함을 증명 |
| 5 | RESOLVED를 72 h 묵히기 *(사본 DB SQL)* | `INJURY_STATE_STALE`, 다시 차단 → **"오래된 all-clear는 오늘을 인증하지 않는다"** 규칙 실증 |

호출 지점 전수 확인(단일 정책, 경로별 중복 구현 없음):

```
WorkoutDraftService.generate     / .revise        (+ constraintText로 프롬프트에 주입)
WorkoutDraftApproval.approve
ApprovedWorkoutDraftPublishService.preview / .publish
WorkoutPublishApplicationService.publish  (→ 스케줄러·MCP publish_workout도 이 경로를 통과)
ReadMcpTools.getWorkoutRecommendation     (차단 사실을 응답에 명시, 추천 억제)
```

> **지시 준수**: 운영 DB에는 어떤 부상 보고도 기록하지 않았다. 위 RESOLVED/staleness 시나리오는 전부 격리된 사본 DB에서만 수행했고 사본은 검증 후 삭제했다. 운영에는 여전히 `injury_report` 테이블 자체가 없다(V21 미적용).

### 4-10. Workout Draft → Approve → Publish — **PASS (외부 쓰기 0건)**

| 호출 | 결과 |
|---|---|
| `POST /api/v1/workout-publish` | **409 `WORKOUT_PUBLISHING_DISABLED`** |
| `POST /api/v1/workout-drafts/19/publish` | **409 `DRAFT_PUBLISHING_DISABLED`** |
| `GET /api/v1/workout-drafts/19/publish-preview` | 200 — `restDay:true, publishable:true, externalWriteRequired:false, expectedOutcome:"SKIPPED_REST_DAY"`, `renderedWorkoutText:"REST DAY - approved rest; no workout is sent to Intervals.icu or Garmin"` |

세 게시 경로 전부 fail-closed. **Intervals.icu에 어떤 요청도 보내지 않았다.**

### 4-11. MCP 읽기 전용 도구 7종 — **PASS**

`tools/list` → 정확히 7종, 전부 `readOnlyHint=true`:

| 도구 | 결과 |
|---|---|
| `get_recent_activities` | 14일 창 4건, 실데이터 |
| `get_activity_analysis` | `date` 인자만 허용(내부 id 미노출), 실데이터 분석 반환 |
| `get_training_context` | V2 / 11,038 B / sha256 |
| `get_recovery_context` | HRV·수면·기준선 실데이터 |
| `get_fitness_trend` | CTL/ATL/derivedForm + 용어 선언 |
| `get_workout_recommendation` | 안전 게이트 결정을 응답에 포함 |
| `get_athlete_profile` | LTHR + provenance (`sourceTracked`) |
| **입력 검증** | 미지 키 `activityId` → `INVALID_ARGUMENT`, 날짜 누락 → `INVALID_ARGUMENT` |
| **`publish_workout`** | 미등록 → `-32602 "Tool not found: publish_workout"` |

---

## STEP 5 — 회귀 및 품질 검증

### 5-1. 전체 회귀 테스트 — **1,272 / 1,272 PASS (기준치와 동일)**

이번 세션에서 **1회만** 실행(`gradlew clean test`, Claude Stop Hook과 동시 실행 없음).

```
$env:JAVA_HOME='C:\Program Files\Java\jdk-21.0.2'   # 세션 기본값은 JDK 8 — 반드시 선행
server> .\gradlew.bat clean test --console=plain
BUILD SUCCESSFUL in 8m 37s
```

| 집계 방식 | 결과 |
|---|---|
| 로그 `PASSED` 라인 | **1,272** / `FAILED` **0** |
| JUnit XML 합산 | tests **1,272**, failures **0**, errors **0**, skipped **0** |

Phase 7B-4가 보고한 1,272/1,272와 **정확히 일치** → 그 이후 회귀 없음.

### 5-2. 보조 테스트 스위트 (전부 이번 세션 실행)

| 스위트 | 명령 | 결과 |
|---|---|---|
| `tools/mcp-gateway` | `node test/gateway-test.js` | **29 / 29 PASS** |
| `tools/garmin-connector` | `python -m pytest -q` | **134 / 134 PASS** |
| `tools/external-relay` | `node --test test/server.test.js test/workout-types.test.js` | **14 / 14 PASS** |
| `scripts/windows/tests/Test-RunningAI.ps1` | | **90 PASS / 0 FAIL** |
| `scripts/windows/tests/Test-Watchdog.ps1` | | **48 PASS / 0 FAIL** |
| `scripts/windows/tests/Test-WatchdogRecoveryPolicy.ps1` | | **55 PASS / 0 FAIL** |
| `scripts/windows/tests/Test-ConnectorOwnership.ps1` | | **36 PASS / 0 FAIL** |
| **합계 (Spring 제외)** | | **406 / 406 PASS** |

> `node --test test` (디렉터리 형태)는 Node 24에서 모듈 해석 실패로 1건 FAIL 처리된다 — 파일을 직접 지정하면 14/14 PASS. 테스트 내용이 아니라 **실행 방법 문제**이며 `tools/external-relay/package.json`에 `test` 스크립트가 없는 것이 원인 → **DEBT-3 (Low)**.

### 5-3. 이번 Phase에서 추가로 수행한 통합 검증 (코드 변경 0)

작업지시서의 "필요한 통합 테스트만 보완" 범위에서, **코드를 한 줄도 바꾸지 않고** 실데이터 사본 DB로 다음을 직접 실증했다. 기존 자동화 테스트로는 덮이지 않던 경로들이다.

| 검증 | 기존 테스트가 덮지 못하던 부분 |
|---|---|
| V20→V23 실데이터 마이그레이션 | H2 기반 `SchemaMigrationTest`는 빈 스키마에서 시작 — **기존 21,828행 위에서의 ALTER**는 미검증이었음 |
| 안전 게이트 4개 분기 (실데이터) | 단위 테스트는 가짜 Clock/Repository 기반 |
| LTHR 원장 3단계 전이 | 동일 |
| 러닝 다이내믹스 실측값 | `RunningDynamicsCalculatorTest`는 합성 랩 기반 — **실제 22랩 하프 데이터**는 미검증이었음 |
| 게시 스위치 XOR 기동 거부 (실 환경변수) | 아래 5-5 |
| raw `/mcp` 미지 메서드 5xx | 아래 7-3 |

### 5-4. 미검증 경로 / 읽기 전용 보장의 한계

| # | 한계 | 설명 |
|---|---|---|
| L1 | **MCP 읽기 전용 보장은 "소스 텍스트 + 리플렉션" 수준** | `McpReadToolsArchitectureTest`는 금지 타입명 문자열과 `.save(`류 패턴을 스캔한다. 우회 수단이 원리적으로 존재한다: 리플렉션, 문자열로 조립한 빈 이름, `ApplicationContext` 주입, Kotlin 확장 함수를 통한 간접 호출, 별칭 import. **진짜 런타임 보증은 게이트웨이의 도구 allowlist**이며 이것이 다층 방어의 핵심 층이다. |
| L2 | **Claude CLI 실호출 Draft 생성** | 이번 Phase 미수행(외부 LLM 호출 + DB 쓰기). 근거는 Phase 7B-1 기록으로 대체. |
| L3 | **실제 Intervals.icu 게시** | `main-3` 코드로는 **단 한 번도 실행된 적 없음**. Draft 게시 경로(6G)의 실외부 쓰기 상태는 여전히 **NOT_RUN**(mock/fake만). |
| L4 | **Auth0 실연동 게이트웨이** | 게이트웨이 29/29는 전부 **mock AS + 자체 서명 JWT** 기반. 실제 Auth0 테넌트와의 JWKS/discovery 호환성은 미검증. |
| L5 | **재부팅 전체 복구** | 코드 변경 없음이지만 V21~V23 배포 후의 재부팅 복구는 미수행(지시상 금지). Phase 6I-1.7D-3 결과가 최신 근거. |
| L6 | **PostgreSQL 기반 전체 회귀** | 이번 1,272건은 H2 프로파일. PG17 전체 회귀는 6H-7 당시 1,144건이 마지막 → **DEBT-4 (Medium)**. PG 실행 시 Hikari 풀을 2로 낮춰야 함(`[[main-pc-environment]]`). |

### 5-5. 설정 스위치 조합 검증

**실 환경변수 이름으로** 기동 검증(메모리에 기록된 "yml 플레이스홀더 누락" 버그 클래스 방지):

```
WORKOUT_PUBLISHING_ENABLED=true + RUNNING_AI_DRAFT_PUBLISHING_ENABLED=true
→ 기동 실패 (의도)
  BeanCreationException: 'publishingModeGuard'
  Caused by: IllegalStateException: Legacy workout publishing and AI draft publishing
             cannot be enabled at the same time
```

| 조합 | 결과 |
|---|---|
| 둘 다 false (현재 운영) | 정상 기동, 양쪽 게시 경로 409 |
| legacy만 true | 정상 기동 |
| draft만 true | 정상 기동 |
| 둘 다 true | **기동 거부** ✅ 실측 |
| `RUNNINGAI_MCP_READ_ENABLED=true`, write=false | MCP 서버 ON, 도구 **7종만**, `publish_workout` 미등록 ✅ 실측 |
| 둘 다 false | `/mcp` 자체가 서비스되지 않음 (`McpServerDisabledTest`) |
| 둘 다 true | 8종 공존, hint 정확 (`McpReadWriteSeparationTest`) |

### 5-6. 운영 환경과 개발 환경의 차이 — **중요**

| 항목 | 운영 (Canonical) | 개발 (main-3 worktree) | 영향 |
|---|---|---|---|
| 코드 | `main` @ `370ee8f` | `main-3` @ `8730f00` | **12 커밋 차이 = V21~V23 전부 미반영** |
| 실행 jar | `C:\running-ai-github\server\build\libs\...jar` | 별도 빌드 | **C2 선행조건의 근거** |
| DB 스키마 | **V20** | (사본에서 V23 검증 완료) | 배포 시 3개 적용 |
| 포트 | Spring 18080 / 커넥터 8765 / 릴레이 17845 | 검증용 18095 | 충돌 없음 |
| `RUNNING_AI_TRAINING_CONTEXT_VERSION` | **V2** | 기본값 V1 | 운영은 이미 V2 — 7B-3의 V2 전용 다이내믹스 필드가 배포 즉시 코치 프롬프트에 반영된다 |
| `GARMIN_PROFILE_SYNC_ENABLED` | **true** (04:30) | false | 배포 후 매일 1행씩 V23 원장에 GARMIN 관측 행이 쌓인다(정상) |
| 테스트 DB | — | H2 (+ 이번 PG 사본) | L6 |
| `JAVA_HOME` | 스크립트가 JDK 21을 자체 탐색 | 세션 기본값 **JDK 8** | gradlew 전 반드시 설정 |

---

## STEP 6 — Windows 운영 안정성 검증

**모든 조사는 읽기 전용.** 예약 작업의 중지·삭제·설정 변경, 서비스 재시작을 일절 수행하지 않았다.

### 6-1. 현재 운영 구성 (실측)

| 컴포넌트 | PID | 바인딩 | 실행 경로 |
|---|---|---|---|
| Spring Boot | 29984 | `127.0.0.1:18080` | `C:\running-ai-github\server\build\libs\running-ai-server-0.0.1-SNAPSHOT.jar` (JDK 21) |
| External Relay | 13588 | `127.0.0.1:17845` | `C:\running-ai-github\tools\external-relay\server.js` |
| Garmin Connector | 25372(런처) → 28684(리스너) | `127.0.0.1:8765` | `C:\running-ai-github\tools\garmin-connector\.venv` |
| PostgreSQL 17 | 컨테이너 `running-ai-postgres` | **`0.0.0.0:5432`** | Up 30 h (healthy) |

세 프로세스 모두 **같은 부모(PID 12252 = 이미 종료된 `start-running-ai.ps1`)** 에서 기동 → 저장소 관리 런타임이 일관되게 적용되어 있음.

헬스 체크(읽기 전용):

```
GET 127.0.0.1:18080/actuator/health → {"status":"UP"}
GET 127.0.0.1:17845/health          → {"status":"ok"}
GET 127.0.0.1:8765/health           → {"status":"UP","service":"garmin-connector"}
```

### 6-2. Watchdog — **정상**

```
.runtime\watchdog-status.json (2026-10-12T21:44:22Z = 10-13 06:44 KST)
overall: UP
components: docker UP / postgres UP / connector UP / spring UP / external-relay UP
blocked: []   lastAction: NONE   recoveryBusy: false
restartBudget: 10분/3회, 전 컴포넌트 used=0
longTermLockout: 24시간/6회, 전 컴포넌트 lockedOut=false, failureCount=0
connectorOwnership.verdict: LAUNCHER_CHILD
```

`watchdog.log` 실측 주기: `21:04:27 → 21:09:25 → 21:14:26 → … → 21:59:30` = **정확히 5분 간격**, 전 tick `state=UP action=NONE result=OK`.

### 6-3. Windows 작업 스케줄러 전수 (루트 경로)

**저장소 관리 작업 (`C:\running-ai-github`)**

| 작업 | 상태 | 트리거 | 액션 |
|---|---|---|---|
| `RunningAI-Startup` | Ready | AtLogon | `powershell.exe -NoProfile -ExecutionPolicy Bypass -WindowStyle Hidden -File …\start-running-ai.ps1` |
| `RunningAI-Watchdog` | **Running** | AtLogon + **Repetition PT5M** (Delay PT5M, 무기한) | `powershell.exe … -WindowStyle Hidden -File …\watch-running-ai.ps1` |

**레거시 작업 (`C:\running-ai`) — 활성**

| 작업 | 상태 | 주기 | 액션 | 게시 도달성 |
|---|---|---|---|---|
| `RunningAI-TrainingDecision` | Ready | **PT1M** | wscript → `process-training-decisions.ps1` | approve/reject만 — **Intervals 호출 없음** |
| `RunningAI-NtfyDecisionListener` | Ready | **PT1M** | wscript → `process-ntfy-decision-actions.ps1` | ntfy 폴링 + 결정 파일 작성 |
| `RunningAI-ChatGPT-Sync` | Ready | PT10M | wscript → `run-chatgpt-pipeline.ps1` | |
| `RunningAI-NewRunCheck` | Ready | PT30M | wscript → `check-new-run.ps1` | Intervals **GET만** |
| `RunningAI-DailyBrief` | Ready | 매일 23:30 | wscript | |
| `RunningAI-WeeklyReport` | Ready | 매주 20:00 | wscript | |
| `RunningAI-CommandBridge` | Ready | AtLogon | `node bridge\transports\http.js` | **LastTaskResult = 1 (실패)** → DEBT-6 |

**레거시 작업 — Disabled (불변식을 지탱하는 상태)**

`RunningAI-TodayWorkout` · `RunningAI-CommandChannel` · `RunningAI-TrainingCommand` · `RunningAI-RemoteWakeupScheduler` · `RunningAI-CodexRequestWatchdog`

레거시 워크아웃 **writer**(`create-today-workout.ps1`)에 도달하는 호출자는 `command-channel-worker.ps1`과 `process-training-commands.ps1` 둘뿐이고, 두 스크립트를 구동하는 작업이 모두 Disabled다.
→ **현재 레거시 워크아웃 쓰기 경로는 활성화되어 있지 않다** (CLAUDE.md의 "두 writer 금지" 요구와 일치). 단, `RunningAI-TrainingDecision`(매분)이 승인 큐에 계속 기록을 남기므로 `RunningAI-TrainingCommand`를 다시 Enable하면 **즉시 레거시 writer가 되살아난다** → **RISK-M3**.

### 6-4. ⭐ CMD / PowerShell 창이 반복적으로 나타나는 현상 — 원인 분석 (읽기 전용)

**결론: 가장 유력한 원인은 `RunningAI-Watchdog`이며, 레거시 작업이 아니다.**

근거 체인:

1. **레거시 작업은 창을 띄우지 않는다.** PT1M인 `RunningAI-TrainingDecision` / `RunningAI-NtfyDecisionListener`를 포함한 모든 레거시 작업은 `wscript.exe`(GUI 서브시스템, 콘솔 없음)로 `.vbs` 래퍼를 실행하고, 래퍼는 전부 `shell.Run command, 0, False` — **window style 0 = 완전 숨김**이다. (`run-ntfy-decision-hidden.vbs`, `run-training-decision-hidden.vbs`, `run-chatgpt-sync-hidden.vbs`, `run-new-run-check-hidden.vbs` 전수 확인; 일부 파일에는 "background RunningAI automation must never flash a console window" 주석까지 있다.)

2. **저장소 관리 작업은 VBS 래퍼를 쓰지 않는다 — 의도적으로.** Phase 4 작업지시서가 "창이 크게 뜨지 않도록 Scheduled Task PowerShell 옵션으로 해결(**VBS wrapper 도입 금지**)"이라고 못박았고, 설치 스크립트는 그대로 구현했다:
   ```
   install-running-ai-scheduled-task.ps1:26
   $arguments = "-NoProfile -ExecutionPolicy Bypass -WindowStyle Hidden -File …"
   ```
   그런데 `powershell.exe`는 **콘솔 서브시스템** 앱이다. `-WindowStyle Hidden`은 PowerShell 자신이 기동한 **뒤에** 적용되므로, 그 이전에 Windows가 만든 conhost 창이 **잠깐 보였다 사라진다**. 이것이 wscript 래퍼를 쓰는 레거시와의 결정적 차이다.

3. **Principal이 Interactive다.** 실측:
   ```
   RunningAI-Watchdog   LogonType=Interactive  RunLevel=Limited  Settings.Hidden=False
   RunningAI-Startup    LogonType=Interactive  RunLevel=Limited  Settings.Hidden=False
   ```
   (`Settings.Hidden`은 작업 스케줄러 UI에서 작업을 숨기는 옵션일 뿐 창과 무관하다.) Interactive 토큰 = 사용자 데스크톱에 창이 생길 수 있는 조건.

4. **주기가 정확히 일치한다.** `RunningAI-Watchdog`의 Repetition은 **PT5M**이고 `watchdog.log`가 5분 간격 실행을 기록하고 있다(6-2). "반복적으로"의 주기와 맞아떨어진다.

5. **tick마다 추가로 `cmd.exe` 자식이 3개 생성된다.** Watchdog의 기본 프로브는 Docker/Postgres 상태를 `Invoke-NativeQuiet` / `Invoke-NativeText`로 읽는데, 두 헬퍼의 구현이 `cmd /c …`다:
   ```
   RunningAI.Common.ps1:167   cmd /c "`"$Exe`" $Arguments >nul 2>&1"
   RunningAI.Common.ps1:174   return (cmd /c "`"$Exe`" $Arguments 2>&1" | Out-String)
   ```
   매 tick: `docker info` 1회 + `docker compose ps -a -q postgres` 1회 + `docker inspect` 1회 = **`cmd /c` 3회**. PowerShell 호스트 창 1개 + cmd 자식 3개가 **5분마다** 뜬다.

**판정**: Critical/High가 아닌 **RISK-M4 (Medium, 기능 영향 없음 · 사용성 저해)**. Watchdog은 정상 동작 중이며 창 깜빡임은 부작용이다.

**운영자가 직접 할 수 있는 구분 테스트 (읽기 전용)**: 창이 뜨는 시각을 기록해 `RunningAI-Watchdog`의 `LastRunTime`(5분 간격)과 비교한다. 5분 주기와 일치하면 위 진단이 확정되고, 1분 주기면 레거시 쪽을 다시 봐야 한다.

**권고 조치 (별도 승인 필요 — 이번 Phase에서는 수행하지 않음)**:
- (a) 작업 액션을 `conhost.exe --headless powershell.exe …` 또는 `Start-Process -WindowStyle Hidden` 중계로 바꾸거나,
- (b) Principal을 "사용자가 로그온했는지 여부에 관계없이 실행(`LogonType=Password`/`S4U`)"으로 전환 — 이 경우 세션 0에서 실행되어 창이 원천적으로 생기지 않지만, **Docker Desktop이 사용자 세션에 의존**하므로 Postgres 프로브가 깨질 수 있다(Phase 4가 AtStartup/SYSTEM 구조를 포기한 바로 그 이유). → (a)를 우선 권고.
- (c) 프로브의 `cmd /c`를 `ProcessStartInfo` 직접 호출(이미 `RunningAI.Common.ps1`에 UTF-8용으로 존재하는 패턴)로 바꾸면 자식 창 3개가 사라진다.

### 6-5. 자동 복구 설계 — **PASS**

| 항목 | 상태 |
|---|---|
| 의존 순서 | Docker → PostgreSQL → Connector → Spring, **한 번에 한 단계만**, 각 단계 검증 후 진행 |
| 복구 수단 | 멱등한 `start-running-ai.ps1` 재사용 (별도 복구 코드 없음) |
| 재시작 금지 조건 | Garmin 인증/403/429/업스트림 장애(정보성 힌트만), 실행 중이지만 unhealthy한 PostgreSQL, 외부 프로세스가 점유한 포트, 예산 소진 |
| 절대 금지 | Garmin 호출, `POST /sync`, `compose down`, 볼륨 삭제, 프로세스명 기반 kill, **RunningAI 소유가 입증되지 않은 프로세스 접근** |
| External Relay | 코어 체인 장애와 **독립적으로** 복구 |
| 상태 파일 | `watchdog-state.json` + `.bak`, **원자적 쓰기**, 교차 프로세스 락, 틱 종료 시 최신 상태에 재조정 후 저장 |
| State 읽기 실패 | `STATE_UNAVAILABLE_FAIL_SAFE` → **그 tick은 복구하지 않음**(fail-safe) |
| 운영 모드 | `-DryRun`(완전 읽기 전용) / `-NoRecovery` 제공 |

### 6-6. 재부팅 및 로그인 후 복구 설계

- 두 작업 모두 **AtLogon** 트리거 (AtStartup/SYSTEM 구조는 Docker Desktop의 사용자 세션 의존 때문에 Phase 4에서 의도적으로 배제).
- `RunningAI-Startup`이 전체 스택을 기동, `RunningAI-Watchdog`이 **로그인 +5분**부터 5분마다 감시.
- Phase 6I-1.7D-1A에서 로그인 시각(`Win32_LogonSession`) + Delay PT5M과 첫 실행 시각이 정확히 일치함을 실측 확인.
- **한계**: 로그인하지 않으면 아무것도 기동되지 않는다(설계 의도 — 개인 PC).
- `Microsoft-Windows-TaskScheduler/Operational` 채널은 이 PC에서 **비활성** → 작업 스케줄러 이벤트 기반 사후 분석 불가 → DEBT-7 (Low).

### 6-7. PID / 프로세스 소유권 관리 — **PASS**

- PID 파일 `.runtime\{spring,garmin-connector,external-relay}.pid` + `garmin-connector.owner.json`.
- 소유권 판정은 **PID 번호만이 아니라 커맨드라인 + 저장소 경로**로 수행 → PID 재사용으로 남의 프로세스를 죽이지 않는다.
- 커넥터는 런처 PID(25372)와 실제 리스너 PID(28684)가 다른 구조를 1급으로 다룬다 — 현재 판정 `LAUNCHER_CHILD` (정상).
- `status-running-ai.ps1`은 **stale/외부 소유 `*.pid`를 삭제하지 않는다**(커밋 `370ee8f`에서 수정 완료).
- `Test-ConnectorOwnership.ps1` 36/36 PASS.

### 6-8. 포트 충돌 — **없음**

```
현재 LISTENING: 5432(docker) · 8765(connector) · 17845(relay) · 18080(spring)
검증용 18095/18096은 종료 후 소멸 확인
```

운영 Spring은 `.env`의 `SERVER_PORT=18080`을 쓰고, Watchdog도 같은 값을 `Resolve-RunningAiSpringPort`로 해석한다(기본 파라미터 8080이 아니라). 이 정합성은 Phase 6I-1.6C/6C-3에서 수정된 사항이며 **그 두 Phase의 문서가 아직 미커밋**(DEBT-1)이다.

### 6-9. 로그 파일 관리 — **PASS**

```
C:\running-ai-github\.runtime\logs : 총 890 KB
```
크기 기반 회전(기본 10 MB) + 14일 보존 후 삭제, 회전은 Watchdog의 매 tick에서 수행. 가장 오래된 파일이 11일 전(보존 기간 내).

### 6-10. 중복 기동 방지 — **PASS (이중)**

1. Scheduled Task `MultipleInstances = IgnoreNew` (실측)
2. 스크립트 레벨 뮤텍스 `Local\RunningAI-Watchdog-<repo경로 SHA1 12자리>` — 저장소 경로별로 구분되므로 worktree가 여러 개여도 서로를 막지 않는다.
   실행 시간 제한: Watchdog PT30M, Startup PT1H.
3. 레거시 스크립트도 각자 `Local\RunningAI.TrainingDecision` 등 뮤텍스를 보유.

---

## STEP 7 — MCP 보안 점검

### 7-1. 읽기 도구 7종 등록 — **PASS**

사본 DB 인스턴스(`RUNNINGAI_MCP_READ_ENABLED=true`, write=false)에서 `tools/list` → 정확히 7종, 전부 `readOnlyHint=true` (§4-11).

### 7-2. 쓰기 도구 비노출 — **PASS (3중 방어)**

| 층 | 메커니즘 | 실측 |
|---|---|---|
| 1. Spring | `@ConditionalOnProperty("running-ai.mcp.enabled")` — read만 켜면 `publish_workout` 빈 자체가 없음 | 강제 호출 → `-32602 "Tool not found: publish_workout"` |
| 2. Gateway allowlist | 도구 allowlist에 없는 이름은 프록시 전에 거부 | `authorized but forced publish_workout → -32602, not proxied` PASS |
| 3. Gateway 기동 거부 | `ALLOWED_TOOLS`에 `publish_workout`이 있으면 **기동 자체를 거부** | `publish_workout on the allowlist refuses to start` PASS |
| 추가 | `tools/list` 응답을 allowlist로 **재필터링** | `a write tool listed by a misbehaving backend is filtered out` PASS |

### 7-3. ⚠️ `server/discover` 처리 및 5xx 내부 정보 노출 — **RISK-H1**

ChatGPT는 앱 생성 시 `initialize`보다 **먼저** `server/discover`를 보낸다(7C-2 스모크에서 9회 재시도 실측).

**이번 세션에서 재현한 raw Spring `/mcp` 동작:**

```
POST /mcp  {"jsonrpc":"2.0","id":"disc-1","method":"server/discover","params":{}}
→ HTTP 500
→ 본문: {"stackTrace":[{"methodName":"handlePost","fileName":"WebMvcStatelessServerTransport.java",
         "lineNumber":145,"className":"io.modelcontextprotocol.server.transport.WebMvcStatelessServerTransport"},
         {"className":"org.springframework.web.servlet.DispatcherServlet", …}, …]}

POST /mcp  {"method":"tools/bogus", …}  → HTTP 500 (동일)
```

**원인**: Spring AI 1.1.8 `WebMvcStatelessServerTransport`가 미지 JSON-RPC 메서드를 `-32601`로 거절하지 않고 **HTTP 500 + Java 스택트레이스 본문**으로 응답한다.

**영향**
- (보안) 프레임워크·라이브러리·버전·내부 클래스 구조가 **인증 없이** 노출된다. 공격 표면 정찰에 직접 쓰인다.
- (기능) ChatGPT가 5xx를 받으면 앱 생성을 포기한다.

**현재 완화 상태**
- `tools/mcp-gateway`가 메서드 allowlist(`initialize`/`ping`/`tools/list`/`tools/call`/`notifications/*`)로 **백엔드에 닿기 전에** 차단하고, 커밋 `8730f00`이 이 동작을 테스트로 고정했다:
  ```
  PASS unauthenticated server/discover -> 401 with resource_metadata (OAuth trigger)
  PASS authenticated   server/discover -> HTTP 200 JSON-RPC -32601, never 5xx, not proxied
  ```
- **그러나 게이트웨이는 아직 운영에 배치되지 않았다.** 완화는 "게이트웨이가 항상 앞단에 있을 때"만 성립한다.

**권고**: C3 유지(`RUNNINGAI_MCP_READ_ENABLED=false`). 근본 수정(Spring 측에서 미지 메서드를 `-32601`로 응답)은 별도 Phase 과제 → DEBT-8.

### 7-4. 외부 REST API 차단 — **PASS**

- 게이트웨이는 `/mcp` 외 모든 경로 404, `GET /mcp` 405, 프록시 타깃은 **기동 시 고정된 단일 루프백 URL**이며 경로/호스트 포워딩이 없다 → 범용 REST 프록시로 전용될 수 없다.
- `PASS arbitrary path -> 404`, `PASS still zero backend hits after path/method probing`.
- 요청 본문 64 KiB 초과 시 연결 파기.

### 7-5. OAuth Gateway 테스트 — **29 / 29 PASS (이번 세션 실행)**

```
discovery (unauthenticated, metadata only)        2/2
negative paths (backend must stay at zero hits)  19/19
  무토큰 / 서명 오류 / 변조 / 만료 / nbf 미래 / iss 불일치 / aud 불일치
  / scope 부족(403) / sub 불일치(403) / HS256 거부 / 미지 kid
  / publish_workout 강제(-32602) / 비허용 메서드(-32601)
  / server/discover 2종 / 임의 경로 404 / GET /mcp 405
  → 위 전부에서 backend hits = 0
authorized path                                   4/4
fail closed on JWKS outage (503, 통과 없음)        2/2
startup validation (잘못된 설정 기동 거부)          2/2
```

암호 처리는 `jose`(유지보수되는 라이브러리) — 자체 구현 없음. 토큰/헤더/본문은 어떤 로그 라인에도 남지 않는다.

**한계(L4)**: 전부 mock AS 기반. 실제 Auth0 테넌트와의 discovery/JWKS 호환성은 미검증.

### 7-6. 임시 Cloudflare Tunnel 종료 상태 — **종료 확인**

```
Get-Process → cloudflared 프로세스 0개
LISTENING 포트에 18090 / 18091 없음
DB 목록: postgres, running_ai  (running_ai_7c2_smoke 없음 — 7C-2 종료 시 DROP 완료)
worktree 목록에 smoke-7c2 없음
```

### 7-7. 기존 Tailscale Funnel 443 — **변경 없음**

```
tailscale serve status / funnel status:
# Funnel on:
#     - https://laptop-mmdqucic.tail4597a5.ts.net
https://laptop-mmdqucic.tail4597a5.ts.net (Funnel on)
|-- / proxy http://127.0.0.1:17845
```

`/` → external-relay 단일 항목 그대로. **`/mcp` 경로는 추가되지 않았다.** 이번 Phase에서 Funnel 설정을 변경하지 않았다.

> 참고: 현재 Funnel은 `/`를 통째로 릴레이에 매핑한다. 7C-2c에서 `/mcp`를 추가할 때 **경로 분리가 올바르게 동작하는지**가 핵심 검증 포인트가 된다(잘못 설정하면 릴레이가 MCP 트래픽을 받거나 그 반대가 된다).

### 7-8. Auth0 연동 — **보류 (지시대로 미수행)**

### 7-9. 실데이터 외부 공개 — **없음**

- 운영 Spring은 `127.0.0.1`에만 바인딩.
- 외부에서 닿는 유일한 RunningAI 표면은 Funnel → external-relay이며, `/health`(무인증, 상수 응답) 외 모든 데이터는 Bearer 토큰 + rate limit 뒤에 있다. 릴레이는 **Spring에 전혀 연결되어 있지 않고** Intervals.icu를 직접 조회한다 — Garmin 원시 데이터·회복 지표·분석 결과는 구조적으로 도달 불가.
- MCP 읽기 스위치는 운영에서 **off**.

---

## STEP 8 — 운영 반영 준비 보고

### 8-1. 전체 시스템 구성도

```
┌──────────────────── 외부 ────────────────────┐   ┌──────── Main PC (Windows 11) ────────┐
│                                              │   │                                       │
│  Garmin Connect ◄── (토큰: ~/.garminconnect) ─┼───┤ garmin-connector  :8765 (loopback)    │
│                                              │   │   FastAPI / Python / retry 0          │
│  Intervals.icu  ◄── (INTERVALS_API_KEY) ─────┼───┤ Spring Boot 3.5.x :18080 (loopback)   │
│                                              │   │   Java 21 / JPA / Flyway              │
│  Claude CLI (로그인 세션) ◄───────────────────┼───┤   ├ activity · analysis · enrichment  │
│                                              │   │   ├ coach(V1/V2) · draftpublish       │
│  ChatGPT  ──(계획)──► Funnel :443 /mcp ───────┼───┤   ├ injury (안전 게이트)              │
│                          │                   │   │   ├ backfill · recovery · training    │
│                          ▼                   │   │   └ integration/mcp[/read]            │
│                   mcp-gateway :17910         │   │                                       │
│                   (OAuth 2.1 RS, loopback)   │   │ PostgreSQL 17 :5432 (docker)          │
│                                              │   │   running_ai · Flyway V1..V20(운영)   │
│  Garmin FR265 ──► Funnel :443 / ─────────────┼───┤ external-relay :17845 (loopback)      │
│   (Watchface)                                │   │   Bearer + rate limit → Intervals GET │
│                                              │   │                                       │
│                                              │   │ RunningAI-Startup  (AtLogon)          │
│                                              │   │ RunningAI-Watchdog (AtLogon + PT5M)   │
└──────────────────────────────────────────────┘   └───────────────────────────────────────┘
                                                    ┌──── C:\running-ai (레거시, 변경 금지) ─┐
                                                    │ 활성: TrainingDecision(1분)           │
                                                    │       NtfyDecisionListener(1분)       │
                                                    │       ChatGPT-Sync(10분)              │
                                                    │       NewRunCheck(30분, GET만)        │
                                                    │ Disabled: TodayWorkout · CommandChannel│
                                                    │           TrainingCommand · RemoteWakeup│
                                                    └───────────────────────────────────────┘
```

**신뢰 경계**: 모든 RunningAI 서비스는 `127.0.0.1`에만 바인딩한다. 인터넷에 노출되는 것은 Tailscale Funnel 뒤의 external-relay 하나뿐이며, 그 릴레이는 Spring에 연결되어 있지 않다. PostgreSQL만 예외적으로 `0.0.0.0:5432`에 떠 있다(RISK-M1).

### 8-2. 기능별 구현 · 검증 상태표

| 기능 | Phase | 마이그레이션 | 구현 | 자동화 테스트 | 실데이터 검증 | 운영 반영 |
|---|---|---|---|---|---|---|
| Activity API / raw 저장 | 1~2 | V2·V3 | ✅ | ✅ | ✅ | ✅ 운영 중 |
| Garmin 수집 + 증분 sync | 3 | V4 | ✅ | ✅ | ✅ | ✅ (스케줄러는 off) |
| 훈련 부하 / 상태 / 결정 컨텍스트 | 4 | — | ✅ | ✅ | ✅ | ✅ |
| 워크아웃 추천 / 처방 / 강도 목표 | 4B~4D | V5 | ✅ | ✅ | ✅ | ✅ |
| StructuredWorkout / 렌더러 / 게시자 | 5C | — | ✅ | ✅ | ✅ (5C-3.5/5C-4 DEVICE_VERIFIED) | ⏸ 스위치 off |
| 게시 운영 트리거 + 스케줄러 | 6A·6B | — | ✅ | ✅ | — | ⏸ 둘 다 off |
| MCP write 어댑터 (`publish_workout`) | 6C | — | ✅ | ✅ | — | ⏸ off |
| Garmin 회복 인텔리전스 | 6F | V7 | ✅ | ✅ | ✅ (21일 보유) | ✅ 수동 |
| Draft 승인 + 안전 게시 게이트웨이 | 6G | V9·V10 | ✅ | ✅ | ⚠️ **실 외부 게시 NOT_RUN** | ⏸ off |
| 게시 모드 XOR 가드 | 6G.1 | — | ✅ | ✅ | ✅ **이번 세션 기동 거부 실증** | ✅ |
| 상세 활동 (detail/lap/zone/sample) | 6H-1A~1C | V11~V15 | ✅ | ✅ | ✅ LIVE_VERIFIED | ✅ |
| 러닝 분석 엔진 | 6H-4 | V16 | ✅ | ✅ | ✅ (8/8 활동 분석) | ✅ |
| Intervals 보강 (CTL/ATL/Form) | 6H-5 | V17·V18 | ✅ | ✅ | ✅ LIVE_VERIFIED | ✅ |
| 90일 Historical Backfill | 6H-6 | V19 | ✅ | ✅ | ✅ | ✅ 수동 |
| TrainingContext V2 | 6H-7 | V20 | ✅ | ✅ | ✅ (11,038 B/sha256) | ✅ **운영 V2 사용 중** |
| 코치 데이터 신선도 파이프라인 | 6H-9 | — | ✅ | ✅ | ✅ | ✅ (스케줄러 off) |
| Windows 런타임 / Watchdog | 6I | — | ✅ | ✅ 229/229 | ✅ | ✅ 운영 중 |
| External Relay / Funnel | 6I-1 | — | ✅ | ✅ 14/14 | ✅ | ✅ 운영 중 |
| **부상 안전 게이트** | **7B-2** | **V21** | ✅ | ✅ | ✅ **4분기 실증** | ❌ **미반영** |
| **러닝 다이내믹스** | **7B-3** | **V22** | ✅ | ✅ | ✅ **22랩 실측** | ❌ **미반영** |
| **LTHR provenance 원장** | **7B-4** | **V23** | ✅ | ✅ | ✅ **3단계 전이 실증** | ❌ **미반영** |
| **읽기 전용 MCP 도구 7종** | **7C-2a** | — | ✅ | ✅ | ✅ **7/7 실데이터** | ❌ 스위치 off |
| **OAuth MCP Gateway** | **7C-2b** | — | ✅ | ✅ 29/29 (mock AS) | ⚠️ 실 Auth0 미검증 | ❌ 미배치 |
| ChatGPT ↔ MCP E2E | 7C-1.1 / 7C-2 | — | ✅ | — | ✅ (합성 데이터, 사용자 확인) | ❌ |
| 목표 대회 / 목표 기록 영속 | — | — | ❌ 미구현 | — | — | — |
| 인터벌/반복 워크아웃 구조, QUALITY 구조 | — | — | ❌ 미구현 | — | — | — |
| 사이클링 임계 프로파일, RPE 모델, 리포팅 | — | — | ❌ 미구현 | — | — | — |

### 8-3. Git 정합성 보고서 (요약)

| 항목 | 결과 |
|---|---|
| main / origin/main | `370ee8f` 동일, working tree clean |
| main-3 | `8730f00`, main보다 12 커밋 앞섬, **FF-only 병합 가능** |
| main-3-2 | `ddefd48` = main의 조상(stale worktree) — 분기 아님 |
| 7B-4 `50f86d0` | main-3에만 존재 |
| 7C-3 `8730f00` | main-3에만 존재 |
| 미커밋/미추적 | 문서 6건 (소스 변경 0) |
| 문서 쌍 누락 | 6건 (§1-6) |
| 임시/빌드 산출물 누출 | 없음 |
| 비밀정보 누출 | `main..main-3` 전체 diff에서 0건 |
| **이번 Phase의 Git 조작** | **병합 0 · push 0 · 커밋 0** (지시대로) |

### 8-4. DB 마이그레이션 영향 분석

| 버전 | 유형 | 대상 | 기존 데이터 영향 | 롤백 난이도 |
|---|---|---|---|---|
| V21 | `CREATE TABLE injury_report` + index | 신규 | 없음 | 불필요(테이블만 남음) |
| V22 | `ALTER TABLE activity_analysis ADD COLUMN` × 24 (전부 nullable) | 기존 테이블 | 기존 8행 모두 NULL 유지, 재작성 없음 | 불필요 |
| V23 | `CREATE TABLE athlete_intensity_profile_history` + index | 신규 | 없음 | 불필요 |

- **총 적용 시간 252 ms** (실데이터 27 MB 사본 실측). 잠금 시간 사실상 무시 가능.
- **전부 additive** → 구버전 jar가 V23 스키마에서도 기동된다(§3-6). 이것이 "코드만 롤백" 전략을 성립시킨다.
- **역방향은 불가**: V21~V23 코드는 V20 스키마에서 Hibernate validate 실패로 기동되지 않는다. Flyway가 기동 시 자동 적용하므로 순서는 구조적으로 보장된다.

**동작 변화 (스키마가 아니라 코드에서 비롯되는 것 — 배포 영향의 본질)**

| 변화 | 영향 |
|---|---|
| 안전 게이트 활성화 | **배포 직후 REST 외 전 Draft 경로 차단** (보고 전까지). 의도된 fail-closed |
| V2 컨텍스트에 러닝 다이내믹스 + LTHR provenance 추가 | 운영이 이미 `V2`이므로 **다음 코치 호출부터 즉시** 프롬프트/스냅숏이 달라진다. `context_sha256`이 바뀌는 것은 정상 |
| 프로필 sync(매일 04:30)가 원장에 기록 시작 | 하루 1행 수준. 값이 같으면 `applied=false` 관측 행 |
| 기존 8건 분석의 다이내믹스 컬럼 | 재분석 전까지 NULL. 필요 시 `POST /api/v1/activities/{id}/analysis`(Garmin 호출 0) |

### 8-5. 보안 및 장애 위험 목록

#### Critical — **0건**

> 운영 Spring이 루프백 바인딩이고, 게시 스위치 3종이 모두 off이며, 레거시 writer 작업이 전부 Disabled이고, 운영 DB가 손상 없이 백업 가능한 27 MB라는 점이 결합되어 현재 Critical 위험은 없다.

#### High — 2건

**RISK-H1 — raw Spring `/mcp`가 미지 JSON-RPC 메서드에 HTTP 500 + Java 스택트레이스 전문을 반환**
- *근거*: 이번 세션 직접 재현. `{"method":"server/discover"}` → HTTP 500, 본문에 `WebMvcStatelessServerTransport.java:145`, `DispatcherServlet`, `FrameworkServlet` 등 내부 클래스·파일명·행번호. `tools/bogus`도 동일. Spring AI 1.1.8 `WebMvcStatelessServerTransport`의 미지 메서드 처리 부재.
- *영향*: 인증 없는 정찰 정보 유출 + ChatGPT 앱 생성 실패.
- *재현*: `RUNNINGAI_MCP_READ_ENABLED=true`로 기동 → `curl -X POST :PORT/mcp -d '{"jsonrpc":"2.0","id":1,"method":"tools/bogus","params":{}}'`.
- *권고*: (단기) **C3 유지** — 읽기 스위치 off, raw `/mcp`를 절대 노출하지 않음. (중기) 7C-2c에서 게이트웨이를 반드시 앞단에 두고, Funnel `/mcp`는 게이트웨이로만 매핑. (근본) Spring 측에서 미지 메서드를 `-32601`/HTTP 200으로 응답하고 5xx 본문을 새니타이즈 → DEBT-8.

**RISK-H2 — 운영 코드와 검증 코드의 12커밋 괴리 (stale jar 사고 위험)**
- *근거*: 운영 Spring의 CommandLine이 `C:\running-ai-github\server\build\libs\running-ai-server-0.0.1-SNAPSHOT.jar`(= `main` @ `370ee8f`). V21~V23은 `main-3`에만 존재. `[[main-pc-environment]]`에 "worktree 커밋을 병합하기 전에 Canonical에서 `gradlew bootJar`를 돌리면 stale jar를 조용히 재사용한다"는 실제 사고 기록이 있다.
- *영향*: 병합 누락 상태로 재기동하면 **구버전이 그대로 뜨는데 Flyway는 V20 그대로**라 겉보기엔 정상이고, 안전 게이트가 없는 채로 운영이 계속된다. 반대로 스키마만 올리고 코드를 안 올리면 기동 실패.
- *재현*: 병합 없이 Canonical에서 `gradlew bootJar` 후 재기동 → `unzip -p <jar> | grep InjuryReport`가 비어 있음.
- *권고*: **C2** — 병합 → 재빌드 → **jar 내용 직접 검증** → 재기동을 하나의 Runbook 단위로 수행(§8-6).

#### Medium — 5건

**RISK-M1 — PostgreSQL이 `0.0.0.0:5432`로 모든 인터페이스에 바인딩**
- *근거*: `docker ps` → `0.0.0.0:5432->5432/tcp, [::]:5432->5432/tcp`. `docker-compose.yml`의 `"${POSTGRES_PORT:-5432}:5432"`가 호스트 IP를 지정하지 않음.
- *영향*: 같은 LAN/Wi-Fi(카페, 공유 네트워크)에 있는 누구나 TCP 5432에 도달 가능. 비밀번호 인증이 유일한 방어선이 되고, 개인 GPS·심박·수면 데이터 전체가 그 뒤에 있다. (Tailscale Funnel은 5432를 노출하지 않으므로 인터넷 직노출은 아니다.)
- *재현*: 같은 네트워크의 다른 기기에서 `psql -h <PC-IP> -p 5432 -U running_ai`.
- *권고*: `ports: ["127.0.0.1:${POSTGRES_PORT:-5432}:5432"]`로 변경. 1줄 변경이지만 **컨테이너 재생성이 필요하므로 이번 Phase 범위 밖** — 별도 승인 후 수행.

**RISK-M2 — 자동 데이터 수집이 사실상 없음 (코치 입력 신선도)**
- *근거*: `.env` 실측 — 활동 sync 스케줄러·coach-refresh 스케줄러 모두 미설정(off). 운영 최신 활동 `2026-10-10`.
- *영향*: 코치 호출 직전에 `POST /api/v1/coach/data-refresh`를 돌리지 않으면 수일 묵은 데이터로 Draft가 생성된다.
- *권고*: 코치 운영 절차(`RunningAI.CoachOperator.ps1`)에 data-refresh 선행을 고정하거나, `RUNNING_AI_COACH_REFRESH_SCHEDULER_ENABLED=true`를 별도 승인 후 검토.

**RISK-M3 — 레거시 쓰기 경로가 "Disabled 유지"에만 의존**
- *근거*: `create-today-workout.ps1`의 호출자는 `command-channel-worker.ps1`/`process-training-commands.ps1`뿐이고 이를 구동하는 작업 4종이 모두 Disabled. 그러나 `RunningAI-TrainingDecision`(매분)은 계속 승인 큐에 기록한다.
- *영향*: `RunningAI-TrainingCommand`/`CommandChannel`을 다시 Enable하면 **즉시** 레거시 writer가 살아나고, Spring 게시와 동시에 켜지면 하나의 Intervals 캘린더에 두 writer가 생긴다. Spring의 `PublishingModeGuard`는 Spring 내부 두 경로만 보므로 이 조합을 막지 못한다.
- *권고*: Disabled 상태를 배포 전후 체크리스트에 명시(§8-8). 장기적으로는 레거시 스케줄 작업 영구 제거(별도 Phase).

**RISK-M4 — CMD/PowerShell 창이 5분마다 깜빡임**
- *근거·분석*: §6-4. `RunningAI-Watchdog`(PT5M, Interactive, 콘솔 서브시스템 `powershell.exe` 액션, GUI 래퍼 없음) + tick마다 `cmd /c` 자식 3개.
- *영향*: 기능 영향 없음. 사용성 저해 + 포커스 가로채기.
- *재현*: 창이 뜨는 시각과 `(Get-ScheduledTaskInfo RunningAI-Watchdog).LastRunTime`(5분 간격) 비교.
- *권고*: §6-4의 (a)+(c). **이번 Phase에서는 수행하지 않음** (예약 작업 변경 금지).

**RISK-M5 — `.env.example`가 현재 설정과 크게 괴리**
- *근거*: `application.yml`이 참조하는 환경변수 28개 중 **19개가 `.env.example`에 없다.** 특히 `RUNNINGAI_MCP_READ_ENABLED`(개인 데이터를 MCP에 노출하는 스위치), `RUNNING_AI_INJURY_SAFETY_STALENESS_HOURS`(안전 게이트 유효기간), `RUNNING_AI_TRAINING_CONTEXT_VERSION`(운영에서 이미 V2)이 미문서화.
- *영향*: 재구축/타 PC 이관 시 보안상 중요한 스위치를 모른 채 넘어간다. `[[phase-6h5-intervals-state]]`에 기록된 "yml 플레이스홀더 누락으로 환경변수가 조용히 무시되던" 버그 클래스와 같은 뿌리.
- *권고*: `.env.example` 갱신(문서 변경만, 저위험) → DEBT-9.
- *참고(별건, Low)*: `running-ai.garmin.scheduler.enabled`는 주석이 `RUNNING_AI_GARMIN_SCHEDULER_ENABLED`를 안내하지만 yml 값이 리터럴 `false`로 **플레이스홀더가 없다.** 환경변수 소스의 우선순위가 `application.yml`보다 높아 실제로는 동작하지만, 파일 내 다른 모든 스위치와 표기가 불일치한다.

#### Low — 4건

| ID | 내용 | 권고 |
|---|---|---|
| RISK-L1 | Garmin ConnectIQ 시뮬레이터(cmd 41280 + java 7568)가 26시간째 Watchface를 띄운 채 방치 | 사용자가 닫기 (Watchface 변경 금지에 해당하지 않는 단순 프로세스 종료) |
| RISK-L2 | `RunningAI-CommandBridge`(레거시, AtLogon)의 `LastTaskResult = 1` (실패) | 미사용이면 Disable 검토(별도 승인) |
| RISK-L3 | 레거시 `state/today_plan.json`이 `2026-09-26`에 멈춰 있음 — 릴레이의 명시적 REST 오버라이드 소스 | 릴레이 동작 확인 후 정리 |
| RISK-L4 | `Microsoft-Windows-TaskScheduler/Operational` 로그 채널 비활성 → 작업 실행 사후 분석 불가 | 필요 시 활성화(별도 승인) |

### 8-6. 운영 배포 Runbook (별도 승인 전 실행 금지)

> 전제: C1~C4 충족. 소요 약 15분. **무중단 아님** — Spring 재기동이 포함된다.

**Phase 0 — 사전 점검 (읽기 전용, 약 2분)**
```powershell
# 0-1 운영 상태
Invoke-WebRequest http://127.0.0.1:18080/actuator/health -UseBasicParsing   # UP
Get-Content C:\running-ai-github\.runtime\watchdog-status.json | ConvertFrom-Json | % overall   # UP
# 0-2 레거시 writer가 Disabled인지 (C4)
Get-ScheduledTask RunningAI-TodayWorkout,RunningAI-CommandChannel,RunningAI-TrainingCommand,RunningAI-RemoteWakeupScheduler |
  Select TaskName,State     # 전부 Disabled 이어야 함
# 0-3 게시 스위치
Select-String C:\running-ai-github\.env -Pattern 'PUBLISHING_ENABLED|MCP'   # 전부 false, READ 스위치 부재
# 0-4 Canonical clean + FF 가능
git -C C:\running-ai-github status --short            # 비어 있어야 함
git -C C:\running-ai-github merge-base --is-ancestor main main-3 ; $?   # True
```

**Phase 1 — 백업 (약 1분, 되돌리기의 유일한 보험)**
```powershell
$ts = Get-Date -Format yyyyMMdd-HHmmss
docker exec running-ai-postgres pg_dump -U running_ai -d running_ai -Fc -f /tmp/pre-v23-$ts.dump
docker cp running-ai-postgres:/tmp/pre-v23-$ts.dump C:\running-ai-github\.runtime\backups\
# 크기 확인 (약 2.8 MB 이상)
Get-Item C:\running-ai-github\.runtime\backups\pre-v23-$ts.dump | Select Length
# 현재 jar도 보관 (코드 롤백용)
Copy-Item C:\running-ai-github\server\build\libs\running-ai-server-0.0.1-SNAPSHOT.jar `
          C:\running-ai-github\.runtime\backups\running-ai-server-pre-v23-$ts.jar
```
> 백업 파일 크기가 0이거나 명령이 실패하면 **여기서 중단**한다.

**Phase 2 — Watchdog 일시 정지 (재기동 중 오탐 복구 방지)**
```powershell
Disable-ScheduledTask -TaskName RunningAI-Watchdog     # 재기동 후 반드시 되돌린다 (Phase 6)
```

**Phase 3 — FF 병합 + 재빌드 + jar 검증 (RISK-H2 대응)**
```powershell
git -C C:\running-ai-github merge --ff-only main-3
git -C C:\running-ai-github log --oneline -1           # 8730f00 이어야 함
$env:JAVA_HOME='C:\Program Files\Java\jdk-21.0.2'
cd C:\running-ai-github\server ; .\gradlew.bat clean bootJar -x test
# ★ jar 내용 직접 검증 — stale jar 사고 방지
& "$env:JAVA_HOME\bin\jar.exe" tf .\build\libs\running-ai-server-0.0.1-SNAPSHOT.jar |
  Select-String 'injury/WorkoutSafetyGateService|V21__create_injury_report|V22__add_running_dynamics|V23__create_athlete_intensity_profile_history'
# → 4줄 모두 나와야 한다. 하나라도 빠지면 중단하고 Phase 7(롤백)으로.
```

**Phase 4 — 재기동 (Flyway가 V21→V23을 자동 적용)**
```powershell
cd C:\running-ai-github
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\windows\stop-running-ai.ps1
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\windows\start-running-ai.ps1
# 기동 로그에서 마이그레이션 확인
Select-String .\.runtime\logs\spring.out.log -Pattern 'Migrating schema|Successfully applied|now at version'
```

**Phase 5 — 즉시 검증 (§8-8 체크리스트)**

**Phase 6 — Watchdog 재개**
```powershell
Enable-ScheduledTask -TaskName RunningAI-Watchdog
# 5분 후 watchdog-status.json의 overall이 UP인지 확인
```

**Phase 7 — 부상 상태 최초 보고 (C1, 배포 완료의 일부)**
```powershell
# ⚠️ 선수의 실제 상태를 그대로 보고한다. 무릎 통증이 남아 있다면 ACTIVE 이며,
#    RESOLVED로 적는 것은 안전 게이트 우회에 해당한다.
Invoke-RestMethod -Method Post -Uri http://127.0.0.1:18080/api/v1/athlete/injury-reports `
  -ContentType 'application/json; charset=utf-8' `
  -Body ([Text.Encoding]::UTF8.GetBytes('{"bodySite":"KNEE","side":"...","painPresent":true,"painIntensity":<실제값>,"status":"ACTIVE","notes":"<관찰 내용>"}'))
# 응답의 safety.nonRestTrainingAllowed 가 false 이고 reasonCode 가 INJURY_ACTIVE 이면 정상
```
> 이 상태에서 코치는 **REST만** 처방할 수 있다. 그것이 현재 선수 상태에 대한 올바른 시스템 동작이다. 48시간마다 재보고하지 않으면 `INJURY_STATE_STALE`로 다시 차단된다.

**Forward-fix 원칙**: V21~V23 적용 후 문제가 발견되면 **스키마를 되돌리지 않는다.** 세 마이그레이션은 additive라 그대로 두어도 무해하다. 코드 결함이면 코드만 롤백(§8-7 A), 데이터 결함이면 새 마이그레이션(V24)으로 전진 수정한다.

### 8-7. 롤백 · 복구 Runbook

**A. 코드만 롤백 — 권장 경로 (약 5분, 데이터 손실 0)**

V21~V23이 additive이므로 **스키마를 V23에 둔 채 구버전 jar로 되돌릴 수 있다**(§3-6에서 호환성 확인).
```powershell
Disable-ScheduledTask -TaskName RunningAI-Watchdog
powershell -File C:\running-ai-github\scripts\windows\stop-running-ai.ps1
# 백업해 둔 jar로 교체
Copy-Item C:\running-ai-github\.runtime\backups\running-ai-server-pre-v23-<ts>.jar `
          C:\running-ai-github\server\build\libs\running-ai-server-0.0.1-SNAPSHOT.jar -Force
powershell -File C:\running-ai-github\scripts\windows\start-running-ai.ps1
Enable-ScheduledTask -TaskName RunningAI-Watchdog
```
- 효과: 안전 게이트·다이내믹스·LTHR 원장이 비활성화되고 V20 시점 동작으로 복귀. `injury_report` / `athlete_intensity_profile_history` 테이블과 V22 컬럼은 **남지만 무해**하다.
- Git: `git -C C:\running-ai-github reset --hard 370ee8f` (push를 한 적이 없으므로 원격 영향 없음).

**B. 하지 말아야 할 것**
- ❌ `DELETE FROM flyway_schema_history WHERE version IN ('21','22','23')` — 정상 절차가 아니다. 이후 Flyway가 이미 존재하는 객체를 다시 만들려다 실패하고, 스키마와 이력이 영구히 어긋난다.
- ❌ `DROP TABLE injury_report` 등 수동 스키마 되돌리기.
- ❌ `docker compose down -v` (볼륨 삭제 = 전 데이터 소실).

**C. 데이터 복원이 꼭 필요한 경우 (최후 수단)**
```powershell
powershell -File C:\running-ai-github\scripts\windows\stop-running-ai.ps1   # 접속을 모두 끊는다
docker exec running-ai-postgres psql -U running_ai -d postgres -c "ALTER DATABASE running_ai RENAME TO running_ai_broken_<ts>;"
docker exec running-ai-postgres psql -U running_ai -d postgres -c "CREATE DATABASE running_ai OWNER running_ai;"
docker cp C:\running-ai-github\.runtime\backups\pre-v23-<ts>.dump running-ai-postgres:/tmp/restore.dump
docker exec running-ai-postgres pg_restore -U running_ai -d running_ai --no-owner /tmp/restore.dump
# 복원 검증 후 구버전 jar로 기동 (A와 동일)
```
- 원본 DB를 **삭제하지 않고 rename**하여 보존한다(사고 분석 가능).
- 이번 세션에서 동일 절차(pg_dump → createdb → pg_restore)를 실데이터로 실행해 **전 행수·flyway 20행 일치**를 확인했다.

**D. 기동 실패 시 분류**

| 증상 | 원인 | 조치 |
|---|---|---|
| Flyway 마이그레이션 실패 로그 | DDL 오류 | 해당 버전은 트랜잭션 롤백됨 → 로그 확인 후 A |
| `SchemaManagementException` (validate 실패) | 코드/스키마 불일치(예: 구버전 코드 + V23) | A 또는 올바른 jar로 재기동 |
| `IllegalStateException: Legacy workout publishing and AI draft publishing…` | 게시 스위치 둘 다 true | `.env` 수정 후 재기동 |
| 포트 점유 | 이전 프로세스 잔존 | `stop-running-ai.ps1` 후 재시도 |
| Watchdog가 계속 재시작 시도 | Watchdog Enable 상태에서 배포 중 | Phase 2를 건너뛴 경우 — Disable 후 재개 |
| 컴포넌트 `LOCKED_OUT` | 24h/6회 실패 초과 | 원인 조사 후 `clear-watchdog-lockout.ps1` |

### 8-8. 배포 전후 검증 체크리스트

**배포 전 (전부 읽기 전용)**

- [ ] `git -C C:\running-ai-github status --short` → 비어 있음
- [ ] `git -C C:\running-ai-github merge-base --is-ancestor main main-3` → True
- [ ] `C:\running-ai-github\server> .\gradlew.bat clean test` → **1,272/1,272 PASS** (JAVA_HOME=JDK 21)
- [ ] `node tools\mcp-gateway\test\gateway-test.js` → 29/29
- [ ] `python -m pytest -q` (garmin-connector) → 134/134
- [ ] `node --test tools\external-relay\test\server.test.js tools\external-relay\test\workout-types.test.js` → 14/14
- [ ] `scripts\windows\tests\Test-RunningAI.ps1` / `Test-Watchdog.ps1` / `Test-WatchdogRecoveryPolicy.ps1` / `Test-ConnectorOwnership.ps1` → 전부 exit 0
- [ ] 운영 health 3종 UP · `watchdog-status.json` overall UP, lockout 0
- [ ] 레거시 `TodayWorkout`/`CommandChannel`/`TrainingCommand`/`RemoteWakeupScheduler` **Disabled** (C4)
- [ ] `.env`: `WORKOUT_PUBLISHING_ENABLED=false`, `WORKOUT_PUBLISHING_SCHEDULER_ENABLED=false`, `RUNNING_AI_DRAFT_PUBLISHING_ENABLED=false`, `RUNNINGAI_MCP_ENABLED=false`, `RUNNINGAI_MCP_READ_ENABLED` 부재/false (C3)
- [ ] `pg_dump` 백업 성공 + 파일 크기 > 2 MB
- [ ] 현재 jar 백업 완료
- [ ] 진행 중인 Historical Backfill 없음 (`historical_backfill_run`에 RUNNING 상태 0건)

**배포 후 (즉시)**

- [ ] `spring.out.log`에 `Successfully applied 3 migrations ... now at version v23`
- [ ] `SELECT count(*), max(installed_rank) FROM flyway_schema_history;` → **23행**, 전부 `success = t`
- [ ] 행 수 보존: activity 8 · activity_analysis 8 · activity_sample 21,828 · workout_draft 19 · intervals_fitness_daily 99 · garmin_recovery_daily 21
- [ ] `GET /actuator/health` → UP, `GET /api/v1/health` → 정상
- [ ] `GET /api/v1/activities` → 8건
- [ ] `GET /api/v1/athlete/intensity-profile` → 176 / 290 (변경 없음)
- [ ] `GET /api/v1/athlete/injury-state` → `NO_INJURY_STATE_RECORDED`, `nonRestTrainingAllowed = false` ← **정상**
- [ ] `GET /api/v1/coach/training-context?version=V2` → 200, `contextVersion = V2`
- [ ] `POST /api/v1/workout-publish` → 409 `WORKOUT_PUBLISHING_DISABLED`
- [ ] `/mcp` 미서비스(MCP 스위치 둘 다 off이므로 404여야 함)
- [ ] 릴레이 `GET /health` → ok, Watchface 정상 (Funnel 무변경)
- [ ] 부상 상태 최초 보고 완료, 응답 `reasonCode`가 실제 상태와 일치 (C1)
- [ ] Watchdog 재활성화 + 5분 뒤 overall UP

**배포 +24시간**

- [ ] Garmin 프로필 sync(04:30) 실행 후 `athlete_intensity_profile_history`에 GARMIN 행 1건 (값 동일이면 `applied=false`)
- [ ] `watchdog.log`에 재시작/lockout 0건
- [ ] `.runtime\logs` 총량 증가가 정상 범위
- [ ] 부상 상태 재보고(48h 내) — 안 하면 `INJURY_STATE_STALE`로 차단됨

### 8-9. 잔여 버그 및 기술부채

| ID | 심각도 | 내용 | 권고 |
|---|---|---|---|
| DEBT-1 | Low | 3개 Phase(6I-1.6C, 6I-1.6C-3, 7B-1)의 작업지시서·결과보고서 6건 미커밋 | 다음 커밋에 포함 |
| DEBT-2 | Low | Garmin ConnectIQ 시뮬레이터 26시간 방치 | 종료 |
| DEBT-3 | Low | `tools/external-relay/package.json`에 `test` 스크립트 없음 (`node --test test` 디렉터리 형태 실패) | 스크립트 추가 |
| DEBT-4 | **Medium** | PostgreSQL 전체 회귀가 6H-7(1,144건) 이후 미실행. 현재 1,272건은 H2 기준 | PG17 전체 회귀 1회 (Hikari pool 2) |
| DEBT-5 | Medium | 전 경로 "재시도 없음" — 일시적 네트워크 오류가 즉시 사용자 가시 실패 | 멱등 GET에 한해 1회 백오프 재시도 검토 |
| DEBT-6 | Low | 레거시 `RunningAI-CommandBridge` LastTaskResult=1 | 미사용이면 Disable |
| DEBT-7 | Low | TaskScheduler Operational 로그 채널 비활성 | 필요 시 활성화 |
| DEBT-8 | **Medium** | Spring AI MCP 트랜스포트의 미지 메서드 500 + 스택트레이스 (RISK-H1의 근본 원인) | Spring 측 `-32601` 응답 + 5xx 새니타이즈 |
| DEBT-9 | Medium | `.env.example`가 환경변수 28개 중 19개 누락 (MCP 읽기 스위치 포함) | 문서 갱신 |
| DEBT-10 | Low | `running-ai.garmin.scheduler.enabled`만 yml 플레이스홀더 없이 리터럴 `false` | 표기 통일 |
| DEBT-11 | **Medium** | Draft 게시 경로(6G)의 **실제 Intervals 외부 쓰기 NOT_RUN** — mock만 | 별도 승인 하에 통제된 1회 실게시 |
| DEBT-12 | Medium | 게이트웨이 29/29가 전부 mock AS 기반 — 실 Auth0 미검증 | 7C-3에서 수행 |
| DEBT-13 | Medium | 목표 대회 / 목표 기록이 시스템에 전혀 없음 (7A-1·7B-1에서 지적) | 신규 Phase |
| DEBT-14 | Medium | LTHR(176, 사람 입력)과 Garmin 추정 최대심박(~192, 기기 설정)이 **검증되지 않은 채 섞여** Zone5/LTHR100 지표에 쓰임 (7B-1 #2) | 데이터 품질 검증 Phase — **V23 원장이 LTHR 쪽 출처 추적은 해결했으나 최대심박 쪽은 미해결** |
| DEBT-15 | Low | 분석 결과 해석(스퍼트 vs 감속 아티팩트 등)을 매번 사람이 수작업 (7B-1 #3) | 패턴 플래그 보조 로직 |

### 8-10. 최종 판정

## **CONDITIONAL GO**

**GO인 이유** — 코드는 운영에 올릴 품질에 도달했다.
- 회귀 1,272/1,272 + 보조 스위트 406/406, 전부 이번 세션 실측.
- V21→V23을 **운영 실데이터 사본**에 적용해 252 ms, 무손실, 제약·인덱스 정상을 확인했다.
- 신규 기능 5종을 전부 실데이터로 실증했다: 안전 게이트 4분기, 러닝 다이내믹스 22랩, LTHR 원장 3단계, MCP 7종, 게시 스위치 XOR 기동 거부.
- 세 마이그레이션이 전부 additive라 **코드만 롤백**이 성립하고, 백업·복원을 실제로 수행해 검증했다.
- Critical 위험 0건.

**CONDITIONAL인 이유** — 배포는 코드 품질만의 문제가 아니다.
- **C1**: V21은 배포 즉시 시스템 동작을 바꾼다. 부상 보고가 없으면 REST 외 전부 차단된다. 이는 결함이 아니라 설계된 안전 동작이지만, **모르고 배포하면 "코치가 망가졌다"로 오진한다.** 보고 절차가 배포의 일부여야 한다.
- **C2**: 운영 코드가 12커밋 뒤처져 있고, 이 저장소에는 stale jar로 인한 실제 사고 이력이 있다. 병합·재빌드·jar 내용 검증·재기동이 하나의 단위로 묶여야 한다.
- **C3**: raw `/mcp`의 스택트레이스 유출을 이번 세션에 직접 재현했다. 게이트웨이가 배치되기 전까지 읽기 스위치는 off여야 한다.
- **C4**: 레거시 writer 비활성이 "두 writer 금지" 불변식의 유일한 실질적 보증이다.

**NO-GO 영역 (이번 판정에서 명시적으로 제외)**
- ChatGPT ↔ **실제 개인 데이터** 연동: Auth0 미연동 + 게이트웨이 미배치 + RISK-H1 미해결. 합성 데이터 E2E PASS는 이 영역의 승인 근거가 되지 않는다.
- Draft 게시의 실제 외부 쓰기(DEBT-11): `main-3` 코드로 단 한 번도 실행된 적 없다. 게시 스위치를 켜는 것은 **별도의 통제된 Phase**여야 한다.

---

## 9. 이번 Phase에서 수행한 변경과 임시 자원 (전수)

**저장소**: 소스 코드 변경 **0줄**. 추가 파일 2건(본 문서 + 지시서 원문, 둘 다 `docs/work-orders/`, **미커밋**).
**Git**: 병합 0 · 커밋 0 · push 0 · 브랜치 생성/삭제 0.

**생성했다가 전부 철거한 임시 자원**

| 자원 | 철거 확인 |
|---|---|
| 사본 DB `running_ai_7d0` | `DROP DATABASE` 완료 — 남은 DB는 `postgres`, `running_ai` 둘뿐 |
| 덤프 `/tmp/ra_7d0.dump` (컨테이너 내) | 삭제 완료 |
| 검증 Spring (포트 18095, PID 13104) | 종료 — 18095 LISTENING 없음 |
| XOR 가드 검증 Spring (포트 18096) | 기동 거부로 자체 종료 — 18096 LISTENING 없음 |
| Gradle 빌드 산출물 | `main-3` worktree 내 `server/build` (git-ignored) |

**운영 무접촉 확인 (검증 종료 시점)**

```
운영 Spring :18080  → {"status":"UP"}        (재시작 0회)
운영 DB running_ai  → flyway 20행, injury_report 테이블 없음 (V20 그대로)
Tailscale Funnel    → "/ proxy http://127.0.0.1:17845" 단일 항목 그대로
Scheduled Task      → 중지·삭제·설정 변경 0건
레거시 C:\running-ai → 읽기만 수행, 변경 0건
Watchface C:\running-ai-watchface → 접근 0건
```

---

## 10. 지시사항 준수 확인

| 금지 항목 | 준수 |
|---|---|
| 운영 서버 재시작 | ✅ 하지 않음 (18080 무중단) |
| 운영 PostgreSQL 변경 | ✅ 읽기 전용 쿼리만 |
| 운영 Flyway 적용 | ✅ 사본 DB에만 적용 후 삭제 |
| 운영 Garmin Sync 실행 | ✅ 하지 않음 |
| Intervals.icu 실제 게시 | ✅ 하지 않음 (외부 호출 0건) |
| Tailscale Funnel 설정 변경 | ✅ 하지 않음 (상태 읽기만) |
| Watchface 변경 | ✅ 접근하지 않음 |
| Auth0 신규 연동 | ✅ 하지 않음 |
| Canonical main 병합 | ✅ 하지 않음 (가능 여부만 확인) |
| origin push | ✅ 하지 않음 |
| 레거시 저장소 변경 | ✅ 읽기만 |
| 승인 없는 운영 수정·대규모 리팩터링 | ✅ 소스 변경 0줄 |
| 안전 게이트 우회 / 운영 데이터 RESOLVED 변경 | ✅ 하지 않음 — RESOLVED 시나리오는 격리 사본 DB에서만 수행 후 삭제 |
| Gradle 전체 테스트 1회 | ✅ 1회 (`clean test`), Stop Hook 동시 실행 없음 |

작업 완료. 지시서에 따라 **STOP**한다.
