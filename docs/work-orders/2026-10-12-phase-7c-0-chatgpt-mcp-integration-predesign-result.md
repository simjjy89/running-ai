# WO-RUNNINGAI-PHASE-7C-0 — ChatGPT ↔ RunningAI 직접 연동 사전 설계 및 기술 검증 (결과 보고)

지시서: `2026-10-12-phase-7c-0-chatgpt-mcp-integration-predesign-instruction.md`

설계·조사 전용 Phase. **코드·설정·운영 변경 없음**: MCP/운영 API를 인터넷에 노출하지 않았고,
Relay/워치페이스/Funnel 설정·운영 DB·origin/main 전부 무변경. 산출물은 이 문서(실행 가능한
설계 + 후속 Phase 작업지시서 초안)뿐이다.

기준: `main-3` HEAD `b7903a8` (7B-2 Injury Safety Gate, 7B-3 Running Dynamics 포함).

---

## 1. 기존 Spring AI MCP 서버 현황 (조사 결과)

- **서버**: Spring AI 1.1.8 MCP server (Boot 3.5 호환 1.x 라인), **Streamable HTTP
  stateless**, 엔드포인트 `POST /mcp`. `RUNNINGAI_MCP_ENABLED`(`running-ai.mcp.enabled`,
  기본 **false**)가 Spring AI 서버 자체와 도구 등록(`McpToolConfig`,
  `@ConditionalOnProperty`)을 함께 끈다. resources/prompts/completions/annotation-scan 전부
  비활성(`application.yml`).
- **도구**: 정확히 1개 — `publish_workout({"date":"YYYY-MM-DD"})`. 전송 어댑터일 뿐이며 모든
  안전규칙(마스터 스위치, per-date single-flight, idempotency, readback 검증, **7B-2 Injury
  Safety Gate**)은 `WorkoutPublishApplicationService` 계층의 것을 그대로 상속한다. 7B-2에서
  `WORKOUT_SAFETY_*` 실패 코드도 이미 매핑되어 있다.
- **인증**: `/mcp`는 **무인증**. 현재 구조 그대로는 절대 외부 노출 불가(CLAUDE.md에도 명시).
- **전송 호환성**: ChatGPT가 요구하는 Streamable HTTP와 일치 — 전송 계층은 이미 호환이고,
  부족한 것은 (a) 공개 HTTPS 경로, (b) OAuth, (c) 읽기 전용 도구들이다.

## 2. ChatGPT 연결 조건과 제약 (외부 조사 결과)

ChatGPT의 커스텀 MCP 연결(2025-09 "developer mode" 출시, 2026 현재) 요건:

| 항목 | 요건 |
|---|---|
| 전송 | 공개 **HTTPS** 원격 서버, **Streamable HTTP**(SSE도 가능), 안정된 URL(관례상 `/mcp`) |
| 인증 | 사용자 데이터에 접근하는 커넥터는 **OAuth 2.1 + PKCE + Dynamic Client Registration(DCR)** — ChatGPT가 서버의 OAuth 메타데이터(`/.well-known/oauth-authorization-server` 등)를 발견해 스스로 클라이언트 등록. **정적 Bearer 토큰 방식은 커스텀 커넥터에서 허용되지 않음**. "No authentication" 옵션도 존재하지만 전세계 공개와 같으므로 본 과제 규칙상 배제 |
| 플랜 | developer mode는 유료 플랜 필요. **쓰기(write) 도구의 허용 범위는 출시 후 변동**: 2025-09 출시 시 Plus/Pro에 확인 모달과 함께 전체 쓰기 허용 → 2026 현재 문서들은 full MCP(쓰기)를 Business/Enterprise/Edu 중심으로, Plus/Pro는 read/fetch 중심으로 기술. **구현 전 소유자 계정 플랜에서 실측 확인 필요**(7C-1 STEP 0) |
| 도구 설계 | deep-research 커넥터는 `search`/`fetch` 2개 도구 관례가 있으나, developer mode 일반 채팅에서는 임의 이름의 도구 호출 가능. 쓰기 도구는 ChatGPT가 실행 전 확인 모달을 띄움 |
| 알려진 불안정 | 커넥터가 OAuth 완료 후 채팅에 나타나지 않는 사례, 같은 서버가 Claude에서는 되고 ChatGPT에서 불안정한 사례가 2026년에도 보고됨 — E2E 검증 단계를 계획에 명시적으로 포함해야 하는 이유 |

출처: [OpenAI help — Developer mode & full MCP](https://help.openai.com/en/articles/12584461-developer-mode-apps-and-full-mcp-connectors-in-chatgpt-beta),
[OpenAIDevs 발표](https://x.com/OpenAIDevs/status/1965807401745207708),
[Auth0 — ChatGPT에 OAuth MCP 서버 연결](https://auth0.com/blog/add-remote-mcp-server-chatgpt/),
[coworker.ai ChatGPT MCP 2026](https://coworker.ai/blog/chatgpt-mcp), [peliqan 2026](https://peliqan.io/blog/chatgpt-mcp/),
[hatchable 호스팅 가이드](https://hatchable.com/articles/host-an-mcp-server-for-chatgpt-apps), [matagi 2026 가이드](https://matagi.ai/blog/guides/how-to-connect-chatgpt-to-mcp-server).

**결론(기술 가능성): 가능하다.** 전송은 이미 호환. 필요한 것은 ① OAuth 2.1+DCR 인증 계층,
② 공개 HTTPS 경로(기존 Tailscale Funnel 재사용 가능 — 아래 6), ③ 읽기 전용 도구 세트다.

## 3. 읽기 전용 조회 MCP 도구 설계 (목표 질문 → 도구 매핑)

새 패키지 `integration.mcp.read`(가칭), **기존 읽기 전용 서비스만 호출**(신규 비즈니스 로직
없음). 모두 DB-only — Garmin/Intervals 호출 없음(최신화는 기존 `coach/data-refresh`의 몫).

| 도구 | 입력 | 매핑되는 기존 구현 | 응답(요약) |
|---|---|---|---|
| `get_recent_activities` | `{days?≤90, limit?≤20}` | ActivityRepository + analysis/intervals 요약 (TrainingContextV2Builder의 evidence 패턴 재사용) | 날짜·유형·거리·시간·HR + 분석 유무 |
| `get_activity_analysis` | `{activityId}` 또는 `{date}` | `RunningActivityAnalysisService.find` (6H-4 + **7B-3 runningDynamics 포함**) | halves·decoupling·zone·LTHR 노출·다이내믹스 |
| `get_training_context` | `{date?}` | `GET /api/v1/coach/training-context?version=V2` 동일 경로 (DB-only, LTHR provenance 경고 포함) | V2 스냅숏 |
| `get_recovery_context` | `{date?}` | `RecoveryContextBuilder` | HRV/수면/RHR/BB/스트레스 + 기준선 |
| `get_fitness_trend` | `{oldest,newest≤90d}` | `IntervalsEnrichmentStore.fitnessDays` | CTL/ATL/derivedForm 일별 |
| `get_workout_recommendation` | `{date?}` | **기존 결정론적** `WorkoutRecommendationService` — 아무것도 영속하지 않는 진짜 읽기 전용 | intent·시간범위·근거 |
| `get_athlete_profile` | — | `AthleteIntensityProfileService` + 7B-3 provenance | LTHR/페이스 + `sourceTracked=false` 경고 |

목표 질문 커버리지:
- "최근 러닝 분석해줘" → `get_recent_activities` + `get_activity_analysis`
- "오늘 훈련 추천해줘" → `get_workout_recommendation`(+`get_recovery_context`) — 읽기 전용으로
  충족. AI Coach **Draft 생성은 쓰기**(DB 저장 + Claude CLI 호출)이므로 Stage 2로 분리
- "이번 주 훈련 계획 짜줘" → RunningAI에는 다주/주간 계획 기능이 **없음**(한 세션 단위).
  읽기 도구들이 증거를 주고 **주간 구성은 ChatGPT 자신의 추론**으로 답하는 형태가 Stage 1의
  정직한 한계. RunningAI가 영속하는 주간 계획은 별도 기능 과제
- "지난달보다 좋아졌는지" → `get_fitness_trend`(CTL 추이) + `get_recent_activities`(기간 비교)

응답 규칙(기존 V2 컨텍스트와 동일): 외부 ID·GPS 좌표·원본 페이로드·자격증명 절대 미포함,
null은 미상(추정 금지), 측정만 제공하고 판정하지 않음.

## 4. 권한 분리 설계

- **스위치 분리**: 읽기 도구는 새 `running-ai.mcp.read-enabled`(기본 false), 쓰기 도구
  (`publish_workout`, 향후 draft 계열)는 기존 스위치 체계 유지. 읽기만 켜고 쓰기는 끈 상태가
  Stage 1의 운영 형태.
- **OAuth scope 분리**: 게이트웨이가 발급하는 토큰에 `runningai:read` / `runningai:write`
  scope를 넣고, scope 없는 요청이 쓰기 도구에 닿으면 게이트웨이가 차단(심층방어 1층) +
  Spring 쪽 스위치(2층) + 기존 도메인 안전장치(마스터 스위치·Injury Gate·승인 절차, 3층).
- **구조 강제**: `McpReadToolsArchitectureTest`(신규) — 읽기 도구 소스가
  `WorkoutPublishApplicationService`/`IntervalsWorkoutPublisher`/`WorkoutDraftService`/
  `draftpublish`/Garmin-write 타입을 참조하지 못함을 기존 `CoachArchitectureTest` 패턴으로 검사.
- 승인·게시의 명시적 사용자 확인 절차와 `RUNNING_AI_DRAFT_PUBLISHING_ENABLED` 등 기존
  스위치는 변경하지 않는다.

## 5. 인증·HTTPS·비밀정보 설계

**선택지 비교** (ChatGPT가 DCR을 요구하므로 "정적 토큰 프록시"는 탈락):

| 안 | 내용 | 장점 | 단점 |
|---|---|---|---|
| A. 외부 IdP (Auth0 free tier 등) | IdP가 AS 역할(DCR 지원), Spring/게이트웨이는 JWT 검증만 하는 Resource Server | 구현 최소, 검증된 DCR·PKCE, Auth0 공식 ChatGPT 가이드 존재 | 외부 서비스 의존(계정·가용성), 단일 사용자 치고는 과함 |
| B. 자체 AS (Spring Authorization Server) | Spring 안 또는 별도 게이트웨이에 OAuth 2.1 AS 내장 | 외부 의존 없음 | 구현·보안 부담 큼, AS 보안사고 리스크를 직접 짊어짐 |
| C. 경량 게이트웨이 + 외부 IdP 조합 | **권고안**: 새 `tools/mcp-gateway`(별도 프로세스, 127.0.0.1)가 ① OAuth 메타데이터/JWT 검증(IdP=Auth0) ② 검증 통과 요청만 `127.0.0.1:18080/mcp`로 프록시 ③ scope→도구 그룹 매핑 ④ 레이트리밋/로깅 | Spring 코드 불변(무인증 /mcp는 계속 로컬 전용), 인증 실패가 Spring에 닿지 않음, Relay 패턴과 동일한 운영 모델 | 프로세스 1개 추가(Watchdog 감시 대상 추가 필요) |

- **HTTPS**: Tailscale Funnel이 TLS 종단(아래 6).
- **비밀정보**: Intervals/Garmin 키는 지금처럼 서버에만. ChatGPT에는 어떤 비밀도 전달하지
  않음(OAuth 토큰은 IdP 발급 단기 JWT). 게이트웨이 설정(IdP 도메인·audience)은 `.env`,
  로그에 토큰 미기록(Relay의 REDACT 패턴 재사용).

## 6. Tailscale Funnel / External Relay 재사용성

- **External Relay(17845)는 재사용하지 않는다** — 워치페이스 실서비스 중이고 "변경 금지"
  대상. 코드도 목적(오늘 운동 1문항)에 특화되어 MCP 프록시로 부적합.
- **Tailscale Funnel 인프라는 재사용 가능**: Funnel은 한 노드에서 **443 / 8443 / 10000** 세
  포트를 독립적으로 서빙할 수 있다. 현재 443 → 127.0.0.1:17845(relay)는 그대로 두고, **8443 →
  127.0.0.1:<mcp-gateway>** 를 추가하면 relay 설정을 건드리지 않고 같은 `*.ts.net` 호스트로
  MCP를 노출할 수 있다(`https://<host>.ts.net:8443/mcp`). 기존 Funnel 설정 변경이 아니라
  **추가**이며, 제거도 독립적(`tailscale funnel --https=8443 off`).
- **공개 위험 평가**: Funnel은 Cloudflare Access류의 앞단 인증이 없으므로 8443은 공개
  엔드포인트가 된다. 수용 조건(전부 충족해야 운영 허용):
  ① 게이트웨이가 JWT 검증 실패 시 프록시 전 즉시 401(Spring 미접촉) ② Stage 1은 읽기 도구만
  활성(쓰기 스위치 off — 워크아웃 변조 불가) ③ 응답에 GPS/외부ID/비밀 없음 ④ 레이트리밋 +
  감사 로그 ⑤ 미사용 시 `funnel --https=8443 off`로 상시 차단 가능. ChatGPT의 egress IP
  고정이 보장되지 않으므로 IP 허용목록은 설계에 넣지 않는다.
- 대안으로 검토·보류: Cloudflare Named Tunnel(+Access) — 앞단 인증이 가능하지만 ChatGPT
  커넥터가 Access 벽을 통과하려면 결국 OAuth가 필요해 이중화 이득이 작고, 6I-1에서 Funnel로
  수렴한 결정을 뒤집는 비용이 더 크다.

## 7. 단계별 구현 계획 (후속 Phase 작업지시서 초안)

**7C-1 — 연결 핸드셰이크 검증 (코드 최소, 운영 무변경 원칙 유지)**
0. 소유자 ChatGPT 플랜에서 developer mode·커스텀 커넥터·쓰기 가능 여부 실측 확인(스크린샷 기록).
1. IdP(권고: Auth0 free) 테넌트 생성, DCR·PKCE 활성, audience=`runningai-mcp`.
2. `tools/mcp-gateway` 신규(127.0.0.1 바인딩): OAuth 보호 리소스 메타데이터 서빙, JWT 검증,
   `/mcp` 프록시(대상은 **개발 PC의 테스트 Spring 인스턴스**, 운영 18080 아님), 토큰 무기록 로그.
3. Funnel 8443 추가(개발 장비 우선), ChatGPT developer mode에서 커넥터 등록 → OAuth 플로 →
   `publish_workout`이 아닌 **echo/health성 더미 도구 1개**로 요청→응답 왕복 확인.
4. 완료 기준: ChatGPT 채팅에서 더미 도구 호출 성공 + 무토큰/위조토큰 401 확인. 실패 시(플랜
   제약 등) 여기서 중단하고 보고.

**7C-2 — 읽기 전용 MCP 도구 구현** (위 3·4 설계대로; `read-enabled` 스위치, 아키텍처 테스트,
scope 분리, 전체 회귀. 운영 미배포.)

**7C-3 — E2E 검증**: 목표 질문 4종을 실제 ChatGPT에서 수행, 응답 정확성·토큰 흐름·레이트리밋
확인 후 운영 반영 여부 별도 승인.

**7C-4 (보류)** — 쓰기 도구(draft 생성/승인): 플랜 제약 실측 결과와 7C-3 운영 경험 후 별도
지시로만. Injury Gate·승인 절차·스위치는 그대로 통과 경로가 된다.

**선행 의존성**: 운영 Spring은 아직 7B-2/7B-3 미배포(구버전 기동 중). 7C-2 운영 검증 전에
V21/V22 마이그레이션 + 재기동(별도 승인) 필요.

## 8. LTHR 출처/이력 미구현 후속 처리

7B-3 STEP D 설계안(append-only `athlete_intensity_profile_history`: source MANUAL/GARMIN,
garmin_calendar_date, fetched_at, applied, previous 값)을 **Phase 7B-4**로 분리 제안. 7C와
독립적으로 진행 가능하고, 완료 시 `LactateThresholdProvenanceV2.sourceTracked`가 true로
바뀌며 ChatGPT 읽기 도구(`get_athlete_profile`)의 경고문도 실데이터 기반으로 대체된다.
자동 반영·profile-sync Scheduler 활성화는 계속 별도 승인 대상.

## 안전 조건 요약 (구현 착수 전 확정 사항)

1. 무인증 `/mcp` 직접 노출 금지 — 게이트웨이 JWT 검증 통과분만 프록시.
2. Relay(:443 Funnel 경로·프로세스·토큰) 무변경 — MCP는 :8443 추가로만.
3. Stage 1은 읽기 전용 도구만 활성, 쓰기 스위치 전부 off 유지.
4. ChatGPT 플랜 제약(쓰기 가능 여부) 실측 전에는 쓰기 도구 설계를 확정하지 않음.
5. 운영 DB/Flyway/origin/main/재시작: 각 단계 모두 별도 승인 전 금지 유지.

작업 완료. 지시서에 따라 STOP한다.
