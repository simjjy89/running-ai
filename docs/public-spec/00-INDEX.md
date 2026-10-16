# RunningAI 공식 기술 명세서 — 인덱스

> **이 문서 묶음의 용도**: ChatGPT(또는 임의의 정적 사이트 생성기)가 고품질 인터랙티브 HTML
> 기술문서 웹사이트를 제작할 수 있도록 작성된 **소스 명세서**다. 각 장은 독립된 Markdown
> 파일이며, 다이어그램은 `diagrams/*.mmd`에 Mermaid 소스로 분리되어 있다.
>
> 작성일: **2026-10-16** · 기준 저장소: `running-ai-github` (branch `phase-9-stage-4-garmin-scheduler-switch`,
> base `5955379`) · Flyway **V31** · Spring Boot **3.5.16** / Java **21** / Kotlin **2.1.21**

---

## 0. 읽기 전에 — 이 문서가 지키는 정직성 규칙

사용자의 요청은 "Phase 15까지 모두 구현된 최종 완성 버전 기준"이다. 그러나 **실측 결과를 먼저
보고한다**:

| 사실 | 근거 |
| --- | --- |
| 저장소에 **Phase 11~15라는 이름의 계획 문서·코드·마이그레이션은 존재하지 않는다** | `docs/` 257개 문서 전수 grep 결과 `phase 11`~`phase 15` 일치 **0건** |
| 실제 저장소 최신 완료 단계는 **Phase 10** (2026-10-16 운영 반영, V31) | `docs/work-orders/2026-10-16-phase-10-completion.md` |
| **Phase 9**는 진행 중 (Stage 4 = Garmin 자동 동기화 활성화, 코드 완료 / 운영 미활성) | `docs/work-orders/2026-10-16-phase-9-stage-4-route-a.md` |
| 저장소에 존재하는 유일한 "미래 로드맵"은 P1~P4 4개 축 | `docs/work-orders/2026-10-15-feature-completion-audit-and-roadmap.md` |

따라서 본 명세서는 다음 두 층을 **라벨로 물리적으로 분리**해서 기술한다.

1. **현재 코드에서 확인된 사실** — 파일 경로와 함께 근거를 남긴다.
2. **"최종 완성 버전"의 목표 설계** — 본 문서가 Phase 11~15로 **제안하는 설계안**이며,
   저장소 감사 보고서가 식별한 실제 공백에서 도출했다. **구현된 것으로 주장하지 않는다.**

> ⚠️ **HTML 사이트 제작자에게**: 아래 상태 라벨은 장식이 아니다. 렌더링 시 반드시 시각적으로
> 구분되는 배지로 표시하고, `DESIGN` 항목은 "계획"임이 한눈에 보이도록 별도 색/아이콘/섹션
> 배경을 부여할 것. 라벨을 제거하고 평문으로 합치면 이 문서의 핵심 가치가 사라진다.

### 0.1 상태 라벨 규약 (전 장 공통)

| 배지 | 의미 | 판정 기준 |
| --- | --- | --- |
| ✅ `SHIPPED` | 구현 + **운영 환경 실증** | 운영 DB 행·실제 외부 호출 이력·장비 육안 확인 중 하나 이상 |
| 🟢 `BUILT` | 구현 + 자동 테스트 통과, 운영 실증 없음 | 코드와 테스트는 존재, 실제 운영 호출 이력 없음 |
| ⏸️ `OFF` | 구현 완료, **기본값 비활성** (의도적 안전장치) | 환경변수 스위치 기본 `false` |
| ⛔ `ABSENT` | 미구현 — 의도적 부재 포함 | 코드 없음. 사유를 함께 기재 |
| 🔵 `DESIGN` | **Phase 11~15 제안 설계 — 코드 없음** | 본 문서의 설계안. 저장소에 근거 없음 |

### 0.2 "검증"이라는 단어의 세 단계

이 저장소는 "테스트 통과"를 "완료"로 간주하지 않는다. 문서 전체가 아래 어휘를 일관되게 쓴다.

- `STATIC_SOURCE_CONFIRMED` — 외부 API 계약을 **라이브러리/문서 소스로만** 확인
- `LIVE_VERIFIED` — **실계정 1회 이상 실제 호출**로 계약 확인
- `DEVICE_VERIFIED` — **실제 시계(Garmin FR265)에서 사람이 육안 확인**

---

## 1. 문서 맵

| # | 파일 | 대상 독자 | 요청 항목 |
| --- | --- | --- | --- |
| 01 | [제품 개요와 핵심 가치](01-product-overview.md) | 일반 러너 · 전원 | ① |
| 02 | [전체 기능 정의 및 상세 동작](02-feature-specification.md) | 전원 | ② |
| 03 | [시스템 아키텍처와 기술 스택](03-architecture-and-stack.md) | 개발자 · 운영자 | ③ |
| 04 | [컴포넌트별 역할과 통신 방식](04-components-and-protocols.md) | 개발자 · 운영자 | ④ |
| 05 | [Garmin·Intervals.icu·Strava 데이터 수집 흐름](05-data-collection.md) | 개발자 | ⑤ |
| 06 | [ChatGPT MCP · OAuth · Gateway 연동 구조](06-chatgpt-mcp-oauth.md) | 개발자 · 운영자 | ⑥ |
| 07 | [Claude CLI 기반 AI 코칭 구조](07-ai-coaching.md) | 개발자 · 러너 | ⑦ |
| 08 | [훈련 생성→승인→게시→수정→분석 시퀀스](08-workout-lifecycle.md) | 전원 | ⑧ |
| 09 | [부상 안전 게이트 · 회복 · 훈련부하](09-safety-recovery-load.md) | 전원 | ⑨ |
| 10 | [장기 훈련 계획 및 대회 목표 관리](10-training-plan-and-goals.md) | 러너 · 개발자 | ⑩ (대부분 🔵) |
| 11 | [Garmin FR265 워치페이스와 동기화](11-watch-integration.md) | 러너 · 개발자 | ⑪ |
| 12 | [PostgreSQL 핵심 데이터 모델](12-data-model.md) | 개발자 | ⑫ |
| 13 | [GitHub 공개 배포 및 신규 사용자 설치 흐름](13-distribution-and-install.md) | 신규 사용자 | ⑬ (혼합) |
| 14 | [사용자별 DB·계정·API 키·OAuth 분리 구조](14-multi-user-isolation.md) | 개발자 · 운영자 | ⑭ (전부 🔵) |
| 15 | [운영·모니터링·장애 복구·보안 정책](15-operations-and-security.md) | 운영자 | ⑮ |
| 16 | [사용자 시나리오와 실제 사용 예시](16-user-scenarios.md) | 일반 러너 | ⑯ |
| 17 | [Phase 9~15 로드맵 정의](17-roadmap-phase-9-15.md) | 전원 | 로드맵 |
| 18 | [내부 근거 원장 (Evidence Ledger)](18-evidence-ledger.md) | 작성자 · 검수자 | 정직성 |

## 2. 다이어그램 소스 (Mermaid)

| 파일 | 종류 | 쓰이는 장 |
| --- | --- | --- |
| `diagrams/01-system-context.mmd` | flowchart | 01, 03 |
| `diagrams/02-component-architecture.mmd` | flowchart | 03, 04 |
| `diagrams/03-data-collection.mmd` | flowchart | 05 |
| `diagrams/04-mcp-oauth-chain.mmd` | sequenceDiagram | 06 |
| `diagrams/05-coach-pipeline.mmd` | flowchart | 07 |
| `diagrams/06-workout-lifecycle.mmd` | stateDiagram | 08 |
| `diagrams/07-publish-sequence.mmd` | sequenceDiagram | 08 |
| `diagrams/08-revision-sequence.mmd` | sequenceDiagram | 08 |
| `diagrams/09-injury-gate.mmd` | flowchart | 09 |
| `diagrams/10-watch-delivery.mmd` | sequenceDiagram | 11 |
| `diagrams/11-erd-core.mmd` | erDiagram | 12 |
| `diagrams/12-erd-collection.mmd` | erDiagram | 12 |
| `diagrams/13-install-flow.mmd` | flowchart | 13 |
| `diagrams/14-multiuser-isolation.mmd` | flowchart | 14 (🔵) |
| `diagrams/15-watchdog-recovery.mmd` | flowchart | 15 |
| `diagrams/16-roadmap-timeline.mmd` | timeline | 17 |

## 3. HTML 사이트 제작 지침 (ChatGPT용)

1. **정보 구조**: 좌측 사이드바 = 위 18개 장. 각 장 상단에 "이 장의 요약(3줄)" + "대상 독자 배지".
2. **이중 설명 레이어**: 모든 장은 `### 쉽게 말하면` 블록과 `### 기술적으로는` 블록을 쌍으로
   갖는다. HTML에서는 탭 또는 토글(아코디언)로 구현하고, 기본은 "쉽게 말하면"을 펼칠 것.
3. **상태 배지**: §0.1의 5개 라벨을 색상 배지 컴포넌트로. 사이드바에 "🔵 DESIGN 항목만 보기"
   필터 토글을 제공하면 좋다.
4. **다이어그램**: `diagrams/*.mmd`를 Mermaid로 렌더. 다크/라이트 모두에서 읽히는 테마 사용.
5. **코드/표**: 가로 스크롤 컨테이너 안에 둘 것(표가 넓다).
6. **금지**: `DESIGN` 항목에 "검증됨", "동작함", "지원합니다" 같은 완료형 표현을 붙이지 말 것.
   해당 항목의 동사는 "~할 계획이다 / ~로 설계한다"로 유지한다.
