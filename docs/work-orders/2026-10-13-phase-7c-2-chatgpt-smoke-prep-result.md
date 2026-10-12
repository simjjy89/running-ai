# Phase 7C-2 ChatGPT 통합 스모크 테스트 — 준비 완료 보고 (ChatGPT 실호출 전 STOP)

지시서: `2026-10-13-phase-7c-2-chatgpt-smoke-prep-instruction.md`

## 구성 (전부 임시, 운영 무접촉)

| 구성요소 | 내용 |
|---|---|
| worktree | `C:\Users\simjy\orca\workspaces\running-ai-github\smoke-7c2`, 브랜치 `smoke-7c2` @ **4336b97**(7종 실데이터 검증 완료 커밋). main-3 파일/빌드 무접촉, 코드 수정 0 |
| 빌드 | `gradlew bootJar -x test` (테스트 재실행 없음 — 해당 커밋의 1,264/1,264 PASS가 유효, 조건 10) |
| DB | 기존 `running-ai-postgres` 컨테이너 안 **새 DB `running_ai_7c2_smoke`** — Flyway V1~V22 신규 적용, **100% 합성 데이터**(실데이터 복제 아님). 운영 `running_ai` DB는 flyway 20행 그대로 |
| 개발 Spring | 포트 **18090**, `RUNNINGAI_MCP_READ_ENABLED=true`, 쓰기 스위치 전부 false(publish_workout 미등록), Garmin/Intervals base-url 죽은 포트 127.0.0.1:9 고정, 기동 로그 "Registered tools: 7" |
| 경로 필터 | `mcp-path-filter.js`(scratchpad, Node): 127.0.0.1:**18091**에서 **`POST /mcp`만** 18090으로 전달, 그 외 전부 404 — Quick Tunnel이 경로 제한을 못 하므로 REST API(쓰기 포함)가 공개 주소에 노출되지 않게 차단. **v2(아래 "ChatGPT 앱 생성 실패 진단·수정")에서 JSON-RPC 메서드 allowlist와 5xx 새니타이즈 추가** |
| 터널 | `cloudflared tunnel --url http://127.0.0.1:18091` (Quick Tunnel, 표준 HTTPS 443) |

**OAuth Gateway 미사용 판단**: `tools/mcp-gateway`는 fail-closed JWT 전용(무토큰=전부 401)이라
AS 없는 무인증 단계에서는 경로에 넣으면 모든 ChatGPT 요청이 거부된다. 설계를 약화(무인증
모드 추가)하지 않고 7C-1.1 PoC와 같은 무인증+합성데이터 자세를 취했고, "재사용"은 Spring
읽기 전용 도구 구조(7C-2a 전체) 쪽으로 충족. Gateway는 Auth0 연동(7C-2b STEP 4·6)에서 투입.

## 합성 데이터 (실값과 전부 다름 — LTHR 165/310 s/km vs 실제 176/290)

- 프로필: PUT intensity-profile(165 bpm / 310 s/km)
- 활동 6건(10-01~10-12, MANUAL + `SMOKE-*` id): RUN 5 + TREADMILL_RUN 1
- 부상: RESOLVED 1건("synthetic smoke-test seed" 명기) → 추천 게이트 통과 상태
- SQL 시딩(`seed-smoke.sql`, scratchpad): garmin_recovery_daily 29일(기준선 AVAILABLE),
  intervals_fitness_daily 29일, activity_intervals_metrics 2건,
  activity_analysis 2건(10-12: 세션지표+7B-3 다이내믹스 전체 / 10-08: 인터벌 그룹 1·work rep 5)

## 사전 검증 (로컬 + 터널 경유 전부 PASS)

- initialize / tools/list = 정확히 읽기 7종 (터널 경유 재확인)
- 7종 개별 호출 전부 합성 데이터로 정상 응답 (training-context V2 6,439B + sha256 포함)
- 입력 검증: days=999 / 미지 키 / 잘못된 날짜 / 91일 초과 범위 → 전부 `INVALID_ARGUMENT`,
  무활동 날짜 → `ACTIVITY_NOT_FOUND` (isError:true 계약 그대로)
- `publish_workout` 강제 호출(터널 경유) → `-32602 Tool not found` (미등록)
- 터널에서 `/api/**`·`/actuator/**` → 404 (경로 필터 동작)
- 운영: 18080 `{"status":"UP"}` 유지, 운영 DB V20 불변, Relay/Funnel 무접촉

## 임시 MCP URL (ChatGPT 커넥터 등록용, No-auth) — **E2E 후 폐기됨**

`https://openings-generation-enabled-heat.trycloudflare.com/mcp` (Quick Tunnel, 현재 소멸)

실제 개인 데이터 연동은 OAuth(Auth0, 7C-2b STEP 4·6) 완료 후 별도 승인으로 진행한다.

---

## ChatGPT 앱 생성 실패 진단 + 경로 필터 v2 (2026-10-13, 사용자 승인 수정)

### 증상과 원인

ChatGPT 앱(플러그인) 생성 시 "앱을 만드는 데 필요한 서비스를 일시적으로 사용할 수 없습니다".

읽기 전용 진단 결과: **요청은 서버까지 도달**했다. cloudflared metrics 총 15건 중 사전 검증
6건(200×4·404×2)을 뺀 **9건이 전부 500**이고, Spring 로그의
`Missing handler for request type: server/discover` 9줄(01:43:31~01:46:49 KST)과 1:1 일치.
**ChatGPT는 `initialize`보다 먼저 `server/discover`를 보내고, 9회 재시도 내내 500만 받자 생성을
중단**했다(initialize/tools/list는 한 번도 오지 않음).

원인: **Spring AI 1.1.8 `WebMvcStatelessServerTransport`는 미지 JSON-RPC 메서드를 -32601로
거절하지 않고 HTTP 500 + Java 스택트레이스 본문으로 응답**한다(`tools/bogus`로도 재현). 7C-1.1
PoC가 통과했던 이유는 PoC가 미지 메서드에 -32601/HTTP 200을 돌려주기 때문(`tools/mcp-poc/server.js:152`);
OAuth용 `tools/mcp-gateway`의 method allowlist도 같은 처리를 한다. 즉 **raw Spring `/mcp`를
ChatGPT에 직접 노출한 이번 스모크 구성에서만 드러난 간극**이며, 최종 게이트웨이 경유
아키텍처에는 영향이 없다. 도구 7종 JSON Schema는 전부 규격 적합(원인 아님).

### 수정 (승인 범위: 테스트용 Node 경로 필터만)

- JSON-RPC 메서드 allowlist = `initialize`·`ping`·`tools/list`·`tools/call`·`notifications/*`
  (게이트웨이와 동일). 그 외 메서드는 **Spring에 전달하지 않고** 필터가 `-32601 Method not found`
  /HTTP 200/`application/json`, **요청 id 보존**으로 응답. id 없는 notification은 202 무본문.
- 잘못된 요청: 파싱 불가 → `-32700`, 배열 배치·method 누락/비문자열 → `-32600` (전부 HTTP 200).
- **5xx 새니타이즈**: Spring이 예기치 못한 5xx를 내면 본문을 밖으로 흘리지 않고 `-32603 Internal error`
  로 대체, 원본은 **로컬 필터 로그에만** 기록. `GET /mcp` → 405, `/mcp` 외 경로 → 404 유지.
- 요청/응답 본문·헤더·토큰·개인 데이터는 로깅하지 않음(로그는 메서드명과 사유만).
- Spring 18090 재시작 없음, 합성 DB 무변경, Quick Tunnel 유지 → **외부 URL 그대로**.

### 회귀 검증 (전부 외부 Quick Tunnel 경유, 9/9 PASS)

| # | 항목 | 결과 |
|---|---|---|
| 1 | `server/discover` | `-32601` / HTTP 200 / id `"disc-1"` 보존 |
| 2 | `tools/bogus` | `-32601` / HTTP 200 |
| 3 | `initialize` | 200 정상 핸드셰이크(protocolVersion 2025-06-18) |
| 4 | `tools/list` | 읽기 도구 정확히 7종 |
| 5 | 7종 개별 호출 | 전부 HTTP 200·isError 없음(793~11,905 bytes) |
| 6 | `publish_workout` | `-32602 Tool not found` (미등록) |
| 7 | `/api/**`·`/actuator/**`·`/` | 전부 404 |
| 8 | 비정상 JSON-RPC 6종 | -32700/-32600/202/405 규격대로 |
| 9 | 스택트레이스 유출 | 0건 (`stackTrace`/`java.`/`com.runningai` 스캔) — `tools/call` params 누락 시 Spring이 낸 500도 `-32603`으로 치환, 원본은 로컬 로그에만 |

필터 v2 기동(01:52:43) 이후 **`server/discover`가 Spring에 도달한 건 0건**(로그의 10번째 항목은
01:48 진단용 직접 호출).

---

## ChatGPT E2E 검증 결과: **PASS** (2026-10-13, 사용자 실호출 확인)

ChatGPT 앱 "RunningAI Smoke 7C2" 생성 성공 후, **읽기 도구 7종을 실제로 발견·호출하여 전부 정상
응답 수신**. 7C-1.1의 `ping` 고정응답 PoC를 넘어, **구현된 조회 도구 7종이 ChatGPT에서 end-to-end로
동작함을 처음으로 실증**했다(전부 합성 데이터).

사용자 확인 항목: 최근 합성 활동 6건 조회 / 러닝 다이내믹스 분석 / 합성 인터벌 5회 분석 /
V2 TrainingContext / Recovery / CTL·ATL·Form / 합성 부상 RESOLVED 상태에서 EASY 추천 /
합성 LTHR 165 bpm / 범위 초과 입력 거부 / 없는 날짜 `ACTIVITY_NOT_FOUND`.

### 보완 검토 — `fullSampleActivityCount=0` vs `inputSampleCompleteness=FULL`

**코드 불일치가 아니라 이번 합성 시딩의 산물이다. 수정 불필요.** 두 값은 서로 다른 테이블을 본다:

| 값 | 출처 | 의미 |
|---|---|---|
| `dataCoverage.fullSampleActivityCount` (V2 컨텍스트) | `activity_detail_collection`의 `ACTIVITY_DETAILS_STREAM` 파트 (V13/V14) — `TrainingContextV2Builder.streamCompleteness()` | Garmin `/samples` 수집이 **지금** 기록하고 있는 스트림 충실도 |
| `analysis.inputSampleCompleteness` (개별 분석) | `activity_analysis.input_sample_completeness` (V16) | **분석 시점에** 그 스트림이 어땠는지 복사해 둔 스냅숏 |

이번 스모크 DB 실측: `activity_analysis` 2건(둘 다 `FULL`) / `activity_detail_collection`
**0건** / `activity_sample` **0건**. 합성 분석 행을 SQL로 직접 넣었을 뿐 상위의 Garmin 수집
단계(스트림 수집 + 상태 기록)는 존재하지 않으므로(커넥터는 죽은 포트 고정),
`streamCompleteness()`가 전 활동에 대해 null → 카운트 0이 **정확한 동작**이다. 실데이터
파이프라인(4336b97 검증)에서는 두 값이 같은 수집에서 유래해 일치한다.

다만 설계상 둘은 **의도적으로 다른 시점**을 가리킨다(V16 주석: "분석 시점의 충실도를 함께
보관해, 전해상도 분석과 다운샘플 분석을 독자가 구분할 수 있게"). 따라서 스트림을 다른
fidelity로 재수집하고 **재분석하지 않으면** 실데이터에서도 일시적으로 달라질 수 있으며, 그것이
버그가 아니라 설계된 의미다. 코치 입장에서 `dataCoverage`는 "지금 이 선수에 대해 가진 증거의
양", `inputSampleCompleteness`는 "이 분석이 선 자리"로 읽어야 한다.

## 보안 정리 (E2E 직후 수행, 2026-10-13)

| 항목 | 결과 |
|---|---|
| Quick Tunnel | cloudflared 종료 — 외부 URL **502**(소멸), cloudflared 프로세스 0 |
| Node 경로 필터(18091) / 개발 Spring(18090) | 종료 — 두 포트 모두 LISTENING 없음 |
| 합성 DB | `DROP DATABASE running_ai_7c2_smoke` — 잔존 DB는 운영 `running_ai`뿐 |
| worktree | `smoke-7c2` 제거 + 브랜치 삭제. 작업 트리 clean·고유 커밋 0이었고 HEAD 4336b97은 **main-3에 포함**되어 유실 없음. 이 문서를 포함한 모든 기록은 main-3에 보존 |
| 운영 Spring | 18080 `{"status":"UP"}` 유지(재시작 없음) |
| 운영 DB | `flyway_schema_history` 20행(V20) — 검증 전후 동일 |
| Relay / Funnel 443 | relay 17845 정상, `tailscale serve status` = `/ proxy http://127.0.0.1:17845` 단일 항목 그대로(`/mcp` 미추가) |
| main-3 7B-4 | 커밋 `50f86d0` 보존, 소스 변경 없음(미추적 문서만 존재) |
| origin push / 운영 변경 | 수행 안 함 |

테스트 스크립트(기동 PS1·시딩 SQL·필터 v2 JS)는 세션 scratchpad에만 존재하며 저장소에
커밋하지 않았다. 필터 v2의 핵심(메서드 allowlist·-32601·5xx 새니타이즈)은 7C-2c에서
`tools/mcp-gateway`가 이미 동일하게 수행하므로 재구현 대상이 아니다.

**실제 개인 러닝 데이터의 외부 연결은 OAuth 인증 완료와 별도 승인 전까지 금지한다.**
