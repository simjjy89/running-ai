# 18. 내부 근거 원장 (Evidence Ledger)

> 대상 독자: 문서 검수자 · HTML 사이트 제작자
>
> **목적**: 이 명세서의 모든 주장이 **어디서 왔는지** 추적 가능하게 한다. 사용자의 지시
> *"아직 구현되지 않은 기능을 실제 구현된 것처럼 검증 결과로 주장하지 마라. 현재 코드에서
> 확인된 사실과 향후 목표 설계를 내부 근거에 명확히 기록하라"* 에 대한 응답이다.

---

## 1. 조사 범위와 방법

| 항목 | 값 |
| --- | --- |
| 조사 일시 | **2026-10-16** |
| 브랜치 | `phase-9-stage-4-garmin-scheduler-switch` |
| base 커밋 | `5955379` ("docs: close Phase 10, and the Stage 4 switch that its own comment gets wrong") |
| 작업 디렉터리 | `running-ai-github` worktree `main-3` |
| 방법 | 소스 직접 판독 · 마이그레이션 전수 · 라우팅 매핑 전수 grep · 설정 전수 · 문서 257건 검색 |
| **변경 사항** | **운영 코드 0 · DB 0 · 배포 설정 0.** 신규 문서만 `docs/public-spec/` 에 생성 |
| **`C:\running-ai` 레거시** | **접근하지 않았고 변경하지 않았다** |

---

## 2. 🔴 가장 중요한 정정 — Phase 11~15는 존재하지 않는다

사용자 요청: *"Phase 9~15 로드맵을 분석해"*, *"Phase 15까지 모두 구현된 최종 완성 버전 기준"*

| 검증 | 명령 | 결과 |
| --- | --- | --- |
| 문서 검색 | `grep -rliE "phase 1[1-5]\|phase-1[1-5]" --include=*.md .` | **0건** |
| 패턴 집계 | `grep -rhoiE "phase[ -]1[1-5][^0-9]" --include=*.md .` | **0건** |
| 작업 기록 수 | `ls docs/work-orders \| wc -l` | 257 (최신은 Phase 10 / Phase 9 Stage 4) |
| 최신 마이그레이션 | `ls server/src/main/resources/db/migration` | **V31** |

**결론**: 실재하는 최고 Phase는 **10**(완료)이며 **9**가 운영 활성화 진행 중이다.
Phase 11~15는 **본 문서가 제안**했고, 전부 🔵 `DESIGN` 라벨을 달았다.

**근거**: 제안은 저장소 자신의 감사 보고서
`docs/work-orders/2026-10-15-feature-completion-audit-and-roadmap.md`의 P1~P4 공백 분석과,
사용자가 요청한 항목 ⑩(장기 계획) ⑬(공개 배포) ⑭(멀티 사용자)에서 도출했다.

---

## 3. 장별 근거 유형

| 장 | 사실 비중 | 설계 비중 | 주 근거 |
| --- | --- | --- | --- |
| 01 제품 개요 | 95% | 5% | CLAUDE.md, 감사 보고서 §0~§8 |
| 02 기능 정의 | **100%** | 0 | 소스 + 마이그레이션 + 감사 보고서 |
| 03 아키텍처 | **100%** | 0 | `build.gradle`, `application.yml`, 패키지 트리 |
| 04 컴포넌트 | **100%** | 0 | 각 도구의 소스 헤더 주석 |
| 05 데이터 수집 | **100%** | 0 | 커넥터 라우트, 임포터 문서, 실측 수치 |
| 06 MCP/OAuth | **100%** | 0 | `tools/mcp-gateway/server.js` 직접 판독 |
| 07 AI 코칭 | **100%** | 0 | `ClaudeCliClient.kt`, `WorkoutDraftValidator.kt` 판독 |
| 08 생명주기 | **100%** | 0 | 서비스 소스 + V9/V10/V29/V31 |
| 09 안전·회복·부하 | **100%** | 0 | `injury` 패키지 + CLAUDE.md |
| **10 장기 계획** | **5%** | **95% 🔵** | 현재 상태만 사실. 설계는 제안 |
| 11 워치 | 85% | 15% 🔵 | 릴레이·컨트롤러 소스. Named Tunnel은 제안 |
| 12 데이터 모델 | **100%** | 0 | 마이그레이션 33테이블 직접 판독 |
| **13 배포·설치** | 50% | **50% 🔵** | §1~3 사실(README/.env.example), §4~6 제안 |
| **14 멀티 사용자** | 20% | **80% 🔵** | 현재 제약만 사실. 설계는 전부 제안 |
| 15 운영·보안 | **100%** | 0 | 워치독 소스 + 운영 기록 |
| 16 시나리오 | 90% | 10% 🔵 | 시나리오 8만 제안 |
| 17 로드맵 | 60% | 40% 🔵 | Phase 9·10 사실, 11~15 제안 |

---

## 4. 코드에서 직접 확인한 사실 (발췌)

| 주장 | 확인 방법 | 위치 |
| --- | --- | --- |
| Spring Boot 3.5.16 / Java 21 / Kotlin 2.1.21 / Spring AI 1.1.8 / FIT SDK 21.176.0 | 판독 | `server/build.gradle` |
| Flyway V1~V31, 테이블 33개 | 전수 | `server/src/main/resources/db/migration/` |
| 컨트롤러 31개, 매핑 ~90줄 | 전수 grep | `@(Get\|Post\|Put\|Delete\|Request)Mapping` |
| Java 197 / Kotlin 112 / 테스트 166 파일 | `find \| wc -l` | `server/src` |
| 커넥터 라우트 10개 **전부 GET** | 판독 | `tools/garmin-connector/garmin_connector/api.py` |
| `TOOL_SCOPES` 19개 매핑 + deny-by-default `scopeFor` | 판독 | `tools/mcp-gateway/server.js:93-128` |
| MCP 도구 이름 20종 | 문자열 추출 | `ReadMcpTools.kt`, `TrainMcpTools.kt`, `PublishWorkoutMcpTool.java` |
| Claude CLI 플래그 5종 잠금 | 판독 | `ClaudeCliClient.kt:40-52` |
| 검증기 "accepts or rejects — never rewrites" | 판독 | `WorkoutDraftValidator.kt:14` |
| 워치독 3범주 | 판독 | `RunningAI.Watchdog.ps1:19-38` |
| `open_slot` UNIQUE + CHECK (부분 인덱스 아님) | 판독 | `V31__*.sql:88-95` |
| `ck_pwra_operation CHECK IN ('UPDATED','NO_CHANGE')` | 판독 | `V31__*.sql:134` |
| `athlete`: "Single-user today, multi-athlete ready" | 판독 | `V1__create_athlete.sql:1` |
| 전 서비스가 `getDefaultAthlete()` | grep | `ActivityService`, `AthleteIntensityProfileService` 외 |
| `TodayWorkoutResponse`에 `title` **부재가 의도적** | 판독 | `TodayWorkoutController.kt:36-47` |
| PostgreSQL 루프백 전용 바인딩 + 사유 주석 | 판독 | `docker-compose.yml` |
| 스케줄러 스위치 플레이스홀더화 (미커밋 변경) | `git diff` | `application.yml:120-132` |
| 스위치 13종과 기본값 | 판독 | `application.yml`, `.env.example` |

---

## 5. 운영 실측 수치의 출처와 유효기간

> ⚠️ 아래 수치는 **인용 시점의 스냅샷**이다. HTML 사이트에 넣을 때는 **반드시 날짜를
> 함께** 표기할 것.

| 수치 | 값 | 출처 | 측정일 |
| --- | --- | --- | --- |
| 활동 48건 (GARMIN 8 / INTERVALS_ICU 25 / STRAVA 15) | | 감사 보고서 §2 | 2026-10-15 |
| 데이터 범위 2026-06-02 ~ 2026-10-11 | | 감사 보고서 §P3 | 2026-10-15 |
| `activity_sample` 75,206행 | | 감사 보고서 §2 | 2026-10-15 |
| `activity_analysis` 48/48 | | 감사 보고서 §2 | 2026-10-15 |
| `intervals_fitness_daily` 99행 | | 감사 보고서 §2 | 2026-10-15 |
| `garmin_recovery_daily` **21행** (28일 창 미충족) | | 감사 보고서 §2 | 2026-10-15 |
| 초안 19건 (DRAFT 10 / APPROVED 3 / SUPERSEDED 6) | | 감사 보고서 §3 | 2026-10-15 |
| 게시 3건 전부 PUBLISHED/UPDATED/verified (event 140389091·140391756·140511724) | | 감사 보고서 §4 | 2026-10-15 |
| 서버 테스트 **1442 PASS / 실패 0 / skip 0 / 147 클래스 / 3m32s** | | 감사 보고서 §0 | **2026-10-15 (Phase 10 이전)** |
| gateway 46/46 · worker 34+49 · relay 14/14 | | 감사 보고서 §0 | 2026-10-15 |
| 본문 TODO/FIXME 0건, `@Disabled` 0건 | | 감사 보고서 §0 | 2026-10-15 |
| Intervals 임포트 25건 / 샘플 46,880 / 재실행 Δ=0 | | CLAUDE.md 7E-1 | 복사 DB 검증 |
| Strava 15건 신규 + 33건 보강 (랩 116, 샘플 18,541) | | CLAUDE.md 7E-2 | 복사 DB 검증 |
| `apple_health_uuid` ↔ Intervals `external_id` **27/27 일치** | | CLAUDE.md 7E-2 | 실측 |
| Intervals 매칭 SOURCE_ID **5/5** | | CLAUDE.md 6H-5 | 2026-10-03 라이브 |
| `icu_lap_count`가 27건 전부 0 | | CLAUDE.md 7E-1 | 2026-10-13 실측 |
| 수정 #1 → event 145043355 `UPDATED` → FR265 확인 | | Phase 10 완료 기록 | 2026-10-16 |
| dev CREATE 실증 event 144442046 | | CLAUDE.md 7F-2F | dev DB `running_ai_7f2` |

> 🔴 **중요**: "1442 PASS"는 **Phase 10 이전** 수치다. Phase 10과 Stage 4 Route A가 테스트를
> 추가했으므로 **현재 총수는 이보다 많다.** 본 문서는 현재 총수를 **측정하지 않았고,
> 따라서 주장하지 않는다.**

---

## 6. 검증 강도 구분 (문서 전체에서 일관 사용)

| 용어 | 의미 | 적용 예 |
| --- | --- | --- |
| `STATIC_SOURCE_CONFIRMED` | 라이브러리/문서 소스로만 확인 | Garmin `/scheduled-workouts` 계약 (8B 당시) |
| `LIVE_VERIFIED` | 실계정 1회 이상 실제 호출 | Garmin 상세 수집(6H-1B), Intervals 보강(6H-5), `/scheduled-workouts`(8B 릴리스) |
| `DEVICE_VERIFIED` | **실제 FR265에서 사람이 육안 확인** | Pace·%LTHR·트레드밀 큐(5C-4), 수정 #1(Phase 10) |

---

## 7. 의도적으로 "검증됨"이라고 쓰지 **않은** 것

| 항목 | 왜 |
| --- | --- |
| 운영 CREATE 분기 | dev DB에서만 성공. **운영 미실행** |
| 90일 백필 | 복사 DB까지만. **운영 실행 이력 없음** |
| 회복 베이스라인 | 21행 < 28일 창 → `INSUFFICIENT_DATA` 상태 |
| 부상 이력 컨텍스트 E2E | 운영 원장 2행이 **모두 RESOLVED** → 차단 경로 운영 미실증 |
| 코치 데이터 리프레시 | 엔드포인트 존재, 스케줄러 OFF |
| 훈련 분석 리포트 | 코드·테스트 존재, 운영 사용 이력 미확인 |
| 시계 도달 | **구조적으로 증명 불가** (`deviceDelivery` = UNKNOWN) |
| 현재 총 테스트 수 | 측정하지 않음 (§5 참조) |

---

## 8. 알려진 데이터 품질 결함 (숨기지 않음)

| 결함 | 상태 |
| --- | --- |
| 운영 `injury_report`에 동일 시각 RESOLVED **중복 2행** (2026-10-13 22:54:24.101553Z) | 원인 규명 완료(셸 `\|\|`가 파이프라인 전체 재시도). append-only라 삭제 대상 아님. **게이트 판정에 영향 없음**(타이브레이크) |
| Garmin 증분 동기화 최종 성공이 감사 시점 3일 전 | 스케줄러 OFF가 원인. Phase 9 Stage 4가 해소 단계 |

---

## 9. HTML 사이트 제작자를 위한 금지 사항

1. **🔵 `DESIGN` 항목에 완료형 동사를 쓰지 말 것.** "지원합니다" / "검증되었습니다" 금지.
   "~로 설계한다" / "~할 계획이다"로 유지.
2. **실측 수치에서 날짜를 떼지 말 것.** 48건·21행·1442 PASS 전부 스냅샷이다.
3. **`LIVE_VERIFIED`와 `DEVICE_VERIFIED`를 "검증됨"으로 뭉치지 말 것.**
4. **"Garmin에 워크아웃을 전송한다"고 쓰지 말 것.** RunningAI는 **Intervals까지만** 쓴다.
5. **"AI가 자동으로 훈련을 올려준다"고 쓰지 말 것.** 모든 외부 쓰기 앞에 사람 승인이 있다.
6. **"1년치 데이터 분석"을 주장하지 말 것.** 보유분은 약 4.5개월이다.

---

## 10. 이 작업이 변경한 것

| 대상 | 변경 |
| --- | --- |
| 운영 코드 (`server/`, `tools/`, `scripts/`) | **없음** |
| 데이터베이스 | **없음** |
| 배포 설정 (`.env`, compose, systemd) | **없음** |
| `C:\running-ai` 레거시 저장소 | **접근·변경 없음** |
| 신규 파일 | `docs/public-spec/` 19개 Markdown + `docs/public-spec/diagrams/` Mermaid 소스 |

> 참고: `server/src/main/resources/application.yml`과
> `server/src/test/.../GarminSchedulerSwitchBindingTest.java`에 **이 작업 이전부터 존재하던**
> 미커밋 변경(Phase 9 Stage 4 Route A)이 있다. **본 작업이 만든 것이 아니며 건드리지 않았다.**
