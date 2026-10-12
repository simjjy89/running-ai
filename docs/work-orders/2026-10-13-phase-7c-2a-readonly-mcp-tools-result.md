# WO-RUNNINGAI-PHASE-7C-2a — RunningAI 읽기 전용 MCP 도구 7종 구현 (결과 보고)

지시서: `2026-10-13-phase-7c-2a-readonly-mcp-tools-instruction.md`

기준: `main-3` `8c22011`에서 시작. 개발·테스트까지만 수행 — 외부 공개·OAuth 구축·운영 배포
없음. 운영 Spring/PostgreSQL/Connector/Relay/Watchface/Funnel/Scheduler 무변경, Canonical·
origin 무접촉, 인증 정보 코드·로그 0건.

## STEP 1 — 기존 코드 조사 결과 (전부 재사용, 분석 공식 중복 구현 0)

| 도구가 쓰는 것 | 재사용한 기존 구현 |
|---|---|
| 활동 목록/식별 | `ActivityRepository`(V2 빌더와 같은 half-open 윈도 쿼리), `AthleteService` |
| 심층 분석 | `RunningActivityAnalysisService.find()` — **읽기만**, `analyse()` 재계산 경로는 아키텍처 테스트로 금지 |
| 러닝 다이내믹스 | 7B-3 `RunningDynamicsMetrics`를 저장된 그대로 직렬화 (재계산 없음) |
| 코치 컨텍스트 | `TrainingContextV2Builder.build()` + **`CoachContextSerializer`** — 코치가 보는 바로 그 canonical JSON(금지필드·크기 가드 포함)을 재사용 |
| 회복 | `RecoveryContextBuilder.build()` (metric별 date/ageDays/baselineStatus 내장) |
| Fitness | `IntervalsEnrichmentStore.fitnessDays()` (읽기 저장소) |
| 추천 | `WorkoutRecommendationService.recommend()` — `@Transactional(readOnly)`·무영속 확인 |
| 부상 게이트 | 7B-2 `WorkoutSafetyGateService.currentDecision()` 그대로 |
| LTHR 출처 | `TrainingContextV2Builder.lactateThresholdProvenance(date)` — private였던 것을 공개 오버로드로 추출(유일한 기존 코드 수정, 동작 불변) |

## STEP 2 — 구현 (`integration.mcp.read`, Kotlin)

새 파일: `ReadMcpTools.kt`(도구 7종 + 공통 배관), `McpReadToolConfig.kt`(조건부 등록),
`McpServerSwitchEnvironmentPostProcessor.java` + `META-INF/spring.factories`(아래 STEP 3),
`application.yml`에 `running-ai.mcp.read-enabled`(기본 false).

도구 계약 요약 (단위·기간·결측·오류 전부 명시, 입력 스키마는 `additionalProperties:false`
+ 서버 측 미지 키 거부):

| 도구 | 입력 | 핵심 출력(단위) |
|---|---|---|
| `get_recent_activities` | `days?` 1..90(기본14), `limit?` 1..20(기본10) | 날짜(선수 TZ)·유형·초·m·bpm + `analysisAvailable`/`intervalsMatched` — **ID류 미노출** |
| `get_activity_analysis` | `date?`(생략=최근 90일 내 최신) | 6H-4 전체 + 7B-3 다이내믹스(cm/%,ms/W, lap-순서 halves 명시) + 인터벌 그룹 요약(최대 3) |
| `get_training_context` | `date?`(기본 오늘) | canonical V2 스냅숏 + sizeBytes + sha256 |
| `get_recovery_context` | `date?` | HRV ms·수면 h/점수·RHR·BB·스트레스, metric별 기록일/ageDays/28일 기준선/`baselineStatus` |
| `get_fitness_trend` | `oldestDate!`,`newestDate!`(범위 ≤90일) | 저장된 날만 [{date,ctl,atl,derivedForm}] + 용어 주석(ctl=fitness, atl=fatigue, derivedForm=RunningAI-derived) |
| `get_workout_recommendation` | `date?` | injurySafety 판정 + (허용 시) intent/시간범위/강도/confidence/근거/summary + "AI 처방 아님·무저장" 주석 |
| `get_athlete_profile` | 없음 | LTHR bpm·역치 s/km + provenance(sourceTracked:false, 마지막 변경, 경고문) |

- 결측 표현: 응답은 non_null 직렬화 — **생략/null = 미상, 추정값 생성 없음**(도구 설명에 명시).
- 오류 계약: `isError:true` + `{success:false, code, message}` — `INVALID_ARGUMENT` /
  `ACTIVITY_NOT_FOUND` / `ANALYSIS_NOT_FOUND` / `INSUFFICIENT_DATA`(예약) / `INTERNAL_ERROR`
  (내부 상세·스택 미노출).
- 1Hz 원본 샘플·GPS·내부/외부 ID·자격증명은 어떤 응답에도 없음(테스트로 ID 미노출 단정).

## STEP 3 — 읽기 전용 보장

- **스위치 분리**: 쓰기 어댑터는 기존 `running-ai.mcp.enabled`(publish_workout), 읽기 7종은
  새 `running-ai.mcp.read-enabled`(`RUNNINGAI_MCP_READ_ENABLED`, 기본 false). Spring AI 서버
  자체는 **둘 중 하나라도 on일 때만** 켜짐 — YAML로 OR를 표현할 수 없어
  `McpServerSwitchEnvironmentPostProcessor`가 계산(read 기본값 false에서는 기존 동작과
  정확히 동일, 기존 MCP 테스트 전부 무수정 통과로 증명).
- 읽기 전용 모드(read on, write off) 보장 — **전부 자동 테스트로 증명**
  (`McpReadServerApiTest`):
  - tools/list = 정확히 읽기 7종, `publish_workout` 부재, 전 도구 `readOnlyHint:true`
  - `publish_workout` 강제 호출 → 미등록이므로 실패 + `WorkoutPublishApplicationService`
    모크 `verifyNoInteractions`
  - **7종 전부 호출 후 10개 테이블 row count 불변** 단정(DB 변경 0)
  - Garmin/Intervals 전송 빈(publisher/client/recoveryClient) 모크 전부 무접촉 단정
  - Draft/승인/게시·동기화·재분석 트리거: 코드 경로 자체가 없음(아래 아키텍처 테스트) +
    Scheduler 설정 무접촉
- `McpReadToolsArchitectureTest`: 소스 스캔(publisher/draft/sync/Garmin-write/outbound-HTTP
  타입 + `.analyse(`/`.save(`/`.delete`/`.upsert` 패턴 금지) + 생성자/빈 메서드 의존성에
  intervals·garmin·draftpublish·coachrefresh·backfill 패키지 금지.
- 양 스위치 동시 on 공존(`McpReadWriteSeparationTest`): 8개 도구, publish_workout의
  readOnlyHint=false 유지 — 등록 조건 분리의 명시적 증명.

## STEP 4 — 분석 품질과 안전성

1. 다이내믹스: 저장값 그대로(전후반 변화율·동일페이스 비교 포함), 결측은 생략/null —
   fixture에 없는 `avgVerticalOscillationCm`가 응답에서 추정되지 않음을 테스트로 단정.
2. 젖산역치: `get_athlete_profile`/`get_training_context` 둘 다 7B-3 provenance 재사용 —
   `sourceTracked:false` + "출처 미추적" 경고 유지(테스트 단정). Garmin 재동기화·프로필
   수정 코드 없음(아키텍처 테스트의 `GarminAthleteProfileSyncService`·`.save(` 금지).
3. 회복·부상: metric별 기록일·ageDays·baselineStatus 그대로 노출.
   `get_workout_recommendation`은 **7B-2 게이트를 먼저 평가** — 기록없음/UNKNOWN/ACTIVE/
   48h 초과면 추천 자체를 반환하지 않고(`recommendation` 생략 + `suppressedReason`) 안전
   판정만 반환. 서버가 REST 처방을 지어내지 않음(7B-2 원칙 유지, 게이트 코드 무수정).
4. 추천: 결정론적·무영속임을 응답 `note`로 명시("not an AI-coach prescription, nothing was
   stored"), 근거(reasons)·confidence·dataSufficiency·summary 포함.

## STEP 5 — 테스트 결과

- 신규 18개: `McpReadServerApiTest` 13 + `McpReadToolsArchitectureTest` 4 +
  `McpReadWriteSeparationTest` 1 — 전부 PASS.
- 기존 MCP 스위트(`integration.mcp.*`) 무수정 전부 PASS — EnvironmentPostProcessor가 기존
  동작을 바꾸지 않음을 증명.
- **전체 회귀 `gradlew clean test`: 1,257개 전부 PASS, 실패 0** (기존 1,239 + 신규 18).
  V1/V2 코치, 7B-2 Injury Gate, 7B-3 분석 회귀 포함. 중복 실행 없음(전체 1회).
- 개발 중 수정 1건: null 필드가 non_null 직렬화로 생략되는 것을 테스트가 `isNull`로
  단정했던 것을 `missing-or-null`로 교정(응답 계약 자체를 "생략=미상"으로 문서화).

## STEP 7 — 7C-2b 선행 설계 점검 보고

1. **등록 방식**: Auth0 "Auth for MCP"가 2026-05 GA — **CIMD 등록 + DCR + Resource Parameter
   Compatibility Mode를 공식 지원**([Auth0/aembit 가이드](https://aembit.io/blog/configuring-an-mcp-server-with-auth0-as-the-authorization-server/),
   [WorkOS 비교](https://workos.com/blog/best-mcp-server-authentication-providers)). 권고:
   **CIMD 우선**(ChatGPT도 지원 확인됨), DCR을 예비로 — 사전 등록 정적 클라이언트는 ChatGPT
   UI가 client_id/secret 입력을 요구하는 경우의 최후 수단.
2. **PKCE/resource/audience**: MCP 2025-06-18 사양 = authorization_code + PKCE S256 필수,
   RFC 8707 resource indicator로 audience 제한 JWT 발급 — Auth0 호환 모드 존재. audience는
   `runningai-mcp`(가칭) API로 고정.
3. **보호 리소스 메타데이터**: 게이트웨이가 `/.well-known/oauth-protected-resource` 서빙 +
   401 응답에 `WWW-Authenticate: Bearer resource_metadata="..."` — ChatGPT가 이걸로 IdP를
   발견. RFC 9728 그대로.
4. **Funnel 경로 충돌**: 운영 반영 시 `--set-path /mcp` 외에 `--set-path
   /.well-known/oauth-protected-resource`도 게이트웨이로 마운트 필요(루트 `/`는 Relay
   유지). Tailscale serve는 경로별 핸들러를 지원하므로 충돌 없음 — 변경은 443 설정 집합
   추가라 별도 승인 대상(7C-2c).
5. **JWT 검증**: 게이트웨이에서 iss(테넌트)/aud(`runningai-mcp`)/exp/nbf/alg(RS256 고정,
   JWKS 캐시)/scope(`runningai:read`)/sub(허용목록 1건) 전부 일치 필수, 실패·장애 시 Fail
   Closed(401/503, Spring 미접촉).
6. **실연동 절차**: ① Auth0 테넌트+API(audience)+사용자 1명 생성(사용자 수동) ② 게이트웨이
   로컬 기동 + 임시 Quick Tunnel(7C-1.1 방식)로 ChatGPT 등록 → OAuth 로그인 → 읽기 도구
   호출 검증 ③ 성공 시에만 고정주소(Funnel /mcp) 승인 요청. 운영 Spring이 아닌 **개발
   인스턴스**를 대상으로 먼저 수행.
7. **비용**: Auth0 free tier(현행 25,000 MAU·기본 기능)로 1인 사용은 충분 — 2b 착수 시
   가격 페이지 재확인을 체크리스트에 포함. 예상 비용 0원.

이번 Phase에서 Auth0 테넌트 생성·Funnel 변경은 하지 않았다(지시 준수).

## 완료 조건 대조

7종 구현 ✅ / 전체 회귀 1,257 PASS ✅ / publish_workout 접근 불가(미등록+강제호출 실패+모크
무접촉) ✅ / 운영·외부 무변경 ✅ / 문서·테스트 기록 ✅ / main-3 커밋 ✅.

**7C-2b 착수 판단: GO 가능** — 선행 점검 7항목에 차단 요소 없음. 필요한 사용자 조작은
Auth0 테넌트 1회 생성뿐이며, 운영 반영(7C-2c)은 V21/V22 적용 승인과 Funnel /mcp 승인이
각각 선행 조건으로 남는다.
