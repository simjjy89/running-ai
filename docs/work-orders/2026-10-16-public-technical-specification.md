# RunningAI 공식 웹문서용 기술 명세서 작성 (2026-10-16)

**문서 작업만 수행. 운영 코드·DB·배포 설정 변경 0건. `C:\running-ai` 레거시 저장소 접근·변경 없음.**

Branch `phase-9-stage-4-garmin-scheduler-switch`, base `5955379`.

## 1. 요청

ChatGPT가 인터랙티브 HTML 기술문서 사이트를 제작할 수 있도록, "Phase 15까지 완성된 최종
제품" 기준의 Markdown 명세서 + Mermaid 다이어그램 소스를 16개 주제로 작성.
대상 독자는 일반 러너 · 신규 설치자 · 백엔드 개발자 · 시스템 운영자.

## 2. 조사에서 확인된 가장 중요한 사실 — 요청 전제의 정정

**저장소에 Phase 11~15라는 이름의 계획 문서·코드·마이그레이션은 존재하지 않는다.**

| 검증 | 결과 |
| --- | --- |
| `grep -rliE "phase 1[1-5]\|phase-1[1-5]" --include=*.md .` | **0건** |
| 문서 257건 중 최신 | Phase 10 완료 / Phase 9 Stage 4 |
| 최신 마이그레이션 | **V31** |

실재하는 최고 단계는 **Phase 10(완료, 운영 반영)** 이고 **Phase 9가 운영 활성화 진행 중**
(Stage 4 = Garmin 스케줄러, 코드 완료·미활성)이다. 저장소에 존재하는 유일한 미래 로드맵은
`2026-10-15-feature-completion-audit-and-roadmap.md`의 P1~P4다.

따라서 명세서는 **Phase 11~15를 본 문서의 제안 설계안으로 명시**하고, 모든 항목에
🔵 `DESIGN` 라벨을 부여했다. 구현·검증되었다고 주장하지 않는다.

## 3. 산출물

`docs/public-spec/` — Markdown 19개 + `diagrams/` Mermaid 16개 (약 239 KB, 26,000 단어)

| 파일 | 요청 항목 | 사실/설계 비중 |
| --- | --- | --- |
| `00-INDEX.md` | 문서 맵 · 상태 라벨 규약 · HTML 제작 지침 | — |
| `01-product-overview.md` | ① 제품 개요와 핵심 가치 | 95 / 5 |
| `02-feature-specification.md` | ② 전체 기능 정의 | **100 / 0** |
| `03-architecture-and-stack.md` | ③ 아키텍처와 기술 스택 | **100 / 0** |
| `04-components-and-protocols.md` | ④ 컴포넌트와 통신 | **100 / 0** |
| `05-data-collection.md` | ⑤ Garmin·Intervals·Strava 수집 | **100 / 0** |
| `06-chatgpt-mcp-oauth.md` | ⑥ MCP·OAuth·Gateway | **100 / 0** |
| `07-ai-coaching.md` | ⑦ Claude CLI 코칭 | **100 / 0** |
| `08-workout-lifecycle.md` | ⑧ 생성→승인→게시→수정→분석 | **100 / 0** |
| `09-safety-recovery-load.md` | ⑨ 부상 게이트·회복·훈련부하 | **100 / 0** |
| `10-training-plan-and-goals.md` | ⑩ 장기 계획·대회 목표 | 5 / **95 🔵** |
| `11-watch-integration.md` | ⑪ FR265 워치페이스 | 85 / 15 |
| `12-data-model.md` | ⑫ PostgreSQL 데이터 모델 | **100 / 0** |
| `13-distribution-and-install.md` | ⑬ 공개 배포·설치 | 50 / **50 🔵** |
| `14-multi-user-isolation.md` | ⑭ 사용자별 분리 | 20 / **80 🔵** |
| `15-operations-and-security.md` | ⑮ 운영·모니터링·복구·보안 | **100 / 0** |
| `16-user-scenarios.md` | ⑯ 사용자 시나리오 | 90 / 10 |
| `17-roadmap-phase-9-15.md` | 로드맵 | 60 / 40 🔵 |
| `18-evidence-ledger.md` | 내부 근거 원장 | — |

### 상태 라벨 (전 장 공통)

✅ `SHIPPED`(구현+운영 실증) / 🟢 `BUILT`(구현+테스트, 운영 실증 없음) /
⏸️ `OFF`(구현, 기본 비활성) / ⛔ `ABSENT`(미구현) / 🔵 `DESIGN`(제안, 코드 없음)

검증 강도는 `STATIC_SOURCE_CONFIRMED` / `LIVE_VERIFIED` / `DEVICE_VERIFIED`로 구분 유지.

## 4. 조사 방법 (근거는 18장에 전수 기록)

소스 직접 판독(`build.gradle`, `application.yml`, `ClaudeCliClient.kt`,
`WorkoutDraftValidator.kt`, `TodayWorkoutController.kt`, `mcp-gateway/server.js`,
`garmin-connector/api.py`, `RunningAI.Watchdog.ps1`, `V31__*.sql`), 마이그레이션 33테이블 전수,
라우팅 매핑 전수 grep(컨트롤러 31개), MCP 도구명 20종 추출, 설정 스위치 13종 전수,
문서 257건 검색.

## 5. 의도적으로 "검증됨"이라고 쓰지 않은 것

운영 CREATE 분기(dev DB만), 90일 백필(복사 DB만), 회복 베이스라인(21행 < 28일 창),
부상 이력 컨텍스트 E2E(운영 원장 2행 모두 RESOLVED), 코치 데이터 리프레시(스케줄러 OFF),
시계 도달(`deviceDelivery` 영구 UNKNOWN), **현재 총 테스트 수(측정하지 않음 — 인용한
1442 PASS는 2026-10-15 Phase 10 이전 수치)**.

## 6. 변경 없음 확인

`git status --short` 결과 신규는 `docs/public-spec/`와 본 작업 기록뿐이다.
`server/src/main/resources/application.yml`(M)과
`GarminSchedulerSwitchBindingTest.java`(??)는 **본 작업 이전부터 존재하던 Phase 9 Stage 4
Route A의 미커밋 변경**이며 건드리지 않았다.

## 7. 한계와 다음 단계

- 운영 실측 수치는 전부 **인용 시점 스냅샷**이다. HTML 사이트에 넣을 때 날짜를 함께 표기할 것
  (18장 §5, §9에 금지사항 6개 명시).
- Phase 11~15 설계안은 **검토를 거치지 않은 제안**이다. 채택 시 각각 별도 작업 지시서와
  감사가 필요하다.
- 테스트는 실행하지 않았다(문서 전용 변경이라 코드 경로에 영향 없음).
