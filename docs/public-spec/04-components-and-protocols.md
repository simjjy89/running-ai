# 04. 컴포넌트별 역할과 통신 방식

> 대상 독자: 💻 백엔드 개발자 · 🛠 시스템 운영자

### 쉽게 말하면

각 프로세스는 **하나의 일만** 한다. 그리고 "누가 무엇을 들고 있는가"가 보안의 핵심이다.

- Garmin 비밀번호는 **Python 커넥터만** 안다.
- Intervals API 키는 **Spring과 릴레이만** 안다.
- ChatGPT는 **아무 비밀도 모르고**, OAuth 토큰 하나로만 들어온다.
- 시계는 **긴 수명의 토큰 하나**만 들고 릴레이에게 "오늘 뭐 뛰지?"만 묻는다.

---

## 1. 컴포넌트 카탈로그

### 1.1 Garmin Connector (Python) ✅ `SHIPPED`

| 항목 | 값 |
| --- | --- |
| 바인드 | `127.0.0.1:8765` |
| 프로토콜 | HTTP/JSON, **전 라우트 GET** |
| 보유 자격증명 | **Garmin 계정 세션 (저장소 유일)** |
| 재시도 | **라이브러리 재시도 비활성** — `Garmin(retry_attempts=0)` |
| 404 처리 | `GARMIN_NOT_FOUND` |

**라우트**

```
GET /health
GET /activities                          요약 목록
GET /lactate-threshold                   LTHR 프로필
GET /recovery?date=                      HRV·수면·안정시HR·Body Battery·스트레스
GET /activities/{id}/detail
GET /activities/{id}/splits
GET /activities/{id}/hr-zones
GET /activities/{id}/power-zones
GET /activities/{id}/samples
GET /scheduled-workouts?year=&month=     ※ month는 1-based (라이브러리가 0-index 처리)
```

> 🔒 **쓰기 경로는 존재하지 않으며 추가할 수 없다.** 커넥터 테스트가 소스 AST를 파싱해
> `schedule_workout` / `upload_*_workout` / `push_workout_to_device`가 나타나면 **빌드를
> 실패**시키고, **모든 라우트가 GET임을 단언**한다.

**왜 별도 프로세스인가** — Spring이 Garmin 자격증명을 **절대 보유하지 않게** 하기 위해서다.
Spring이 침해돼도 Garmin 계정은 그 프로세스 밖에 있다.

### 1.2 RunningAI Server (Spring Boot) ✅ `SHIPPED`

| 항목 | 값 |
| --- | --- |
| 바인드 | `127.0.0.1:18080` (운영) |
| 역할 | 진실의 저장소, 전 도메인 로직, 유일한 Intervals 쓰기 주체 |
| 보유 자격증명 | `INTERVALS_API_KEY`(환경변수만), DB 비밀번호 |
| MCP 엔드포인트 | `POST /mcp` — Streamable HTTP, **무상태**, **인증 없음** → **절대 공개 노출 금지** |

**예외 처리 계약** (Phase 8B-B):

| 상황 | 응답 | 로그 |
| --- | --- | --- |
| 잘못된 HTTP 메서드 | **405 + `Allow` 헤더** | WARN, 스택 없음 |
| `@RequestParam` 누락 | **400** | WARN, 스택 없음 |
| 404 | 변경 없음 (고정됨) | — |

> 이전에는 둘 다 catch-all에 도달해 **500 + 스택트레이스**가 되었고, 워치독이 읽는 로그를
> 희석시켰다.

### 1.3 MCP Gateway (Node) ✅ `SHIPPED`

| 항목 | 값 |
| --- | --- |
| 바인드 | `127.0.0.1:17910` (운영) / `17911` (테스트) — **하드코딩, 설정 불가** |
| 프록시 대상 | 기동 시 고정된 **단일 로컬 URL** (경로·호스트 포워딩 없음) |
| 암호 | **직접 구현하지 않음** — JWT 검증은 `jose`(panva), JWKS 캐싱/회전은 `createRemoteJWKSet` |
| PID 파일 | **없음** — 포트의 리스닝 소켓으로 식별 후 커맨드라인 마커로 소유 증명 |

**통과시키는 것** — `initialize` / `ping` / `tools/list` / `tools/call` / `notifications/*`
**그게 전부다.** `tools/call`은 (allowlist ∩ 토큰 스코프)에 있을 때만, `tools/list` 응답도
같은 교집합으로 **필터링**된다(심층 방어).

**fail-closed 지점** — 설정 누락, 토큰 누락/무효/만료/스코프 불일치/audience 불일치/타 subject,
미지 서명키, JWKS 도달 불가 → 전부 **백엔드 트래픽이 발생하기 전에** 401/403/503.

> ⚠️ **Phase 10 감사에서 고친 결함**: 예전 `TOOL_SCOPES[tool] || REQUIRED_SCOPE` 폴백은
> **매핑되지 않은 쓰기 도구를 읽기 전용 토큰에게 조용히 제공**했다. 호출이 성공했기 때문에
> **스스로를 알릴 수 없는 실패**였다. 지금은 **deny-by-default**: 모든 서빙 가능 도구가 명시
> 항목을 갖고(읽기 도구는 `BASE_SCOPE` 센티널), 매핑 없는 도구는 `undefined`→거부이며,
> allowlist에 매핑 없는 도구가 있으면 **게이트웨이가 기동을 거부하고 이름을 밝힌다.**

### 1.4 Cloudflare Worker + Tunnel ✅ `SHIPPED` (부분 제약)

- Worker: ChatGPT ↔ Auth0 ↔ 터널 사이의 **OAuth 중계**
- Tunnel: **Quick Tunnel** — 호스트명이 **프로세스 수명과 함께 생성/소멸**한다

> ⚠️ 그래서 **워치독은 터널을 절대 재시작하지 않는다**(§15). 재시작하면 새 호스트명이 발급되고
> Worker에 배포된 `ORIGIN_MCP_URL`이 **조용히 무효**가 된다. 유용한 출력은
> `tunnel.startedAtUtc`이며, 이것이 배포된 Worker 버전보다 새로우면 그 URL은 상한 것이다.

### 1.5 External Relay (Node) ✅ `SHIPPED`

| 항목 | 값 |
| --- | --- |
| 바인드 | `127.0.0.1:17845` — **하드코딩** |
| 인증 | `secrets/watch-token.json`의 **단일 장수명 Bearer** |
| 캐시 / 레이트리밋 | 기본 240초 / 분당 20회 |
| 로그 | `authorization` / `token` / `api_key` 패턴을 **정규식으로 마스킹** |

**데이터 출처 우선순위** (Phase 8B-D)

1. Spring `GET /api/v1/today-workout` (기본 `http://127.0.0.1:18080`)
2. Intervals.icu 직접 조회
3. 레거시 상태 파일 `C:\running-ai\state\today_plan.json`

`config.spring_base_url = ""` 로 두면 8B 이전 동작으로 **정확히 복귀**한다.

> 🎯 **왜 토큰이 시계에 박혀도 되는가**: 이 토큰은 Intervals API 키가 **아니다.** 이미 요약된
> 오늘의 계획 GET 하나만 허가한다. PRG를 디컴파일하면 읽히므로, 빌드가 공유되면
> `generate-token.js`로 **회전**한다 — 서버 쪽은 아무것도 바꿀 필요 없다.

### 1.6 Claude Code CLI ✅ `SHIPPED`

Spring이 **프로세스로 1회 호출**한다. 상세는 [07장](07-ai-coaching.md).

### 1.7 PostgreSQL 17 ✅ `SHIPPED`

```yaml
ports:
  - "127.0.0.1:${POSTGRES_PORT:-5432}:5432"   # 호스트 IP 없으면 Docker가 0.0.0.0 + [::] 에 publish
```

> 🔐 **실제 사고 사례**: 호스트 IP를 생략하면 Docker Desktop의 publish가 **Windows 방화벽을
> 우회**한다. 그 결과 5432가 LAN과 tailnet에서 **평문 superuser 접속을 수락**하고 있었다
> (Phase 7D-2 실측, 7E-3 재실측 → 7E-4에서 RISK-H3으로 폐쇄). `localhost`가 여기서는 `::1`로
> 먼저 해석되고 이 바인딩은 IPv4만 publish하므로, `DB_URL`은 반드시
> `jdbc:postgresql://127.0.0.1:5432/...` 로 쓴다.

---

## 2. 통신 매트릭스

| 발신 | 수신 | 프로토콜 | 인증 | 방향 |
| --- | --- | --- | --- | --- |
| Spring | Garmin Connector | HTTP/JSON | 없음 (루프백) | 읽기 |
| Garmin Connector | Garmin Connect | HTTPS | Garmin 세션 | **읽기만** |
| Spring | Intervals.icu | HTTPS | **Basic (API 키)** | 읽기 + **쓰기** |
| Spring | 로컬 FS (Strava) | 파일 | OS 권한 | 읽기 |
| Spring | Claude CLI | **프로세스 + stdin** | CLI 자체 로그인 세션 | 요청/응답 |
| Spring | PostgreSQL | JDBC | DB 계정 | 읽기/쓰기 |
| Gateway | Spring `/mcp` | HTTP/JSON-RPC | 없음 (루프백) | 프록시 |
| Worker | Tunnel → Gateway | HTTPS | **OAuth Bearer (JWT)** | 프록시 |
| ChatGPT | Worker | HTTPS | OAuth 인가 코드 흐름 | 대화 |
| Relay | Spring | HTTP | 없음 (루프백) | 읽기 |
| Watchface | Relay (터널 경유) | HTTPS | **정적 Bearer** | 읽기 |
| Intervals.icu | Garmin Connect | — | — | **Intervals 자체 연동 (본 저장소 밖)** |

### 2.1 통신 규칙

1. **프롬프트는 커맨드라인이 아니라 stdin으로 간다** — 훈련 컨텍스트가 프로세스 테이블에
   나타나면 같은 PC의 임의 프로세스가 읽는다.
2. **자격증명은 로그에 닿지 않는다** — 게이트웨이는 Authorization 헤더·토큰·요청 바디를
   로그 라인에 **보내지 않고**, 릴레이는 정규식으로 마스킹한다.
3. **외부 호출은 호출당 1요청, 재시도 없음**이 기본이다. Intervals 읽기 클라이언트는 429면
   **중단**한다.
4. **멱등성은 마커로 달성한다** — Intervals 이벤트의 `external_id`가 소유권 마커이자
   멱등 키다.

---

## 3. 비동기·동시성 제어

| 메커니즘 | 범위 | 효과 |
| --- | --- | --- |
| 날짜별 단일 비행 | `WorkoutPublishApplicationService` | 같은 날짜 동시 게시 차단 |
| 초안별 단일 비행 | `ApprovedWorkoutDraftPublishService` | 같은 초안 동시 게시 차단 |
| 수정별 단일 비행 | `PublishedWorkoutRevisionApplyService` | 같은 수정 동시 적용 차단 |
| `AlreadyRunningException` | Garmin 동기화 | 동시 동기화 차단. **단일 비행에 거부된 요청은 시도로 기록하지 않는다** (Garmin에 도달하지 않았으므로) |
| `REQUIRES_NEW` 관측 트랜잭션 | 수집 기록 | 기록 실패가 결과를 바꾸지 않음 |
| `STARTED` 선기록 (V30) | 동기화 시도 | JVM이 죽어도 증거가 남고, `GarminSyncAttemptReconciler`가 `INTERRUPTED`로 정리 — **실패가 아니며 연속 실패 카운터를 움직이지 않는다** |

---

## 4. 컴포넌트 아키텍처 (Mermaid)

`diagrams/02-component-architecture.mmd` 참조.
