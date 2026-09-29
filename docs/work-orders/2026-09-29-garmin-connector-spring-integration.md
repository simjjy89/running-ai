# 2026-09-29 — Phase 3B-2: Python Garmin Connector + Spring Boot Integration

| 항목 | 내용 |
|------|------|
| 날짜 | 2026-09-29 |
| 작업 | `tools/garmin-connector`(Python) + Spring `GarminActivitySource` / `GarminSyncService` |
| 상태 | 완료 (live probe 제외 — 수행 불가, 아래 7장) |
| 커밋 | `feat: connect Garmin transport to RunningAI server` |
| 지시서 원문 | [2026-09-29-garmin-connector-spring-integration-instruction.md](2026-09-29-garmin-connector-spring-integration-instruction.md) |
| 이전 작업 | [2026-09-29-garmin-live-contract-investigation.md](2026-09-29-garmin-live-contract-investigation.md) (ADR) |

---

## 1. 목적과 범위

3B-1 ADR(Option B)을 코드로 옮긴다: Garmin 인증·MFA·token·읽기 transport는 Python connector가, ingestion·저장·정규화·idempotency는
Spring이 담당한다. 구현하지 않은 것(3C 이후): incremental cursor, `@Scheduled`, OS scheduler, sync HTTP API, connector 프로세스 감독,
FIT/details/splits 수집, Garmin write, Intervals.icu. DB migration 없음.

## 2. 작업 전 상태

- `main` = `cbf64b1`, clean. Java 66 tests PASS.
- 이 PC: 실제 Python 없음(Windows Store stub만). 3B-1과 동일하게 Garmin credential / token 없음, 비대화형 세션.

## 3. 구현 — Python connector (`tools/garmin-connector/`)

```text
tools/garmin-connector/
├─ README.md                 setup · login · MFA · status · serve · API · error contract · token 보안
├─ requirements.txt          garminconnect==0.3.16, fastapi==0.141.1, uvicorn==0.54.0
├─ requirements-dev.txt      + pytest==9.1.1, httpx==0.28.1 (TestClient)
├─ pyproject.toml            requires-python >=3.12, pytest 설정
├─ garmin_connector/
│  ├─ errors.py              ConnectorError(code, message, http_status) + translate(exc)
│  ├─ client.py              GarminGateway.from_tokens (credential 없는 Garmin() + login(tokenstore)),
│  │                         recent_activities(limit) = get_activities(0, limit), unwrap_activity_list,
│  │                         CachedGatewayProvider (auth 실패 시 cache drop, 재로그인 없음)
│  ├─ auth.py                interactive_login (input + getpass, prompt_mfa=terminal), status (token 파일 + read-only 1회 검증)
│  ├─ api.py                 FastAPI: GET /health, GET /activities?limit (1..100), docs/openapi 비활성, error handler
│  └─ __main__.py            CLI login | status | activities --limit | serve --port (host는 127.0.0.1 고정, 옵션 없음)
└─ tests/                    31 tests, 전부 fake (network 없음)
```

**garminconnect 0.3.16 실제 API에 맞춘 점 (소스 확인)**: `Garmin(email=None, password=None, prompt_mfa=cb, return_on_mfa=False, …)`;
`login(tokenstore)`는 token 로드 → 만료 임박 시 DI refresh → profile 1회 조회로 검증, credential이 없으면 `GarminConnectAuthenticationError`
("Username and password are required")를 내고 **절대 credential login으로 fallback하지 않음**; credential login 시 `<tokenstore>/garmin_tokens.json`(0600)에 dump;
`get_activities(start, limit)`; 예외: `GarminConnectAuthenticationError`(401), `GarminConnectTooManyRequestsError`(429), `GarminConnectConnectionError`(그 외 HTTP, `.response.status_code`).

**Error contract** (`{code, message}`, credential/token/payload 미포함):

| HTTP | code | 원인 |
|------|------|------|
| 400 | `INVALID_REQUEST` | limit 범위/형식 |
| 401 | `GARMIN_AUTH_REQUIRED` | token store 없음/거부 (`GarminConnectAuthenticationError`, 또는 응답 401) |
| 403 | `GARMIN_FORBIDDEN` | `GarminConnectConnectionError` + response 403 |
| 429 | `GARMIN_RATE_LIMITED` | `GarminConnectTooManyRequestsError` 또는 응답 429 |
| 502 | `GARMIN_UPSTREAM_ERROR` | 그 외 `GarminConnectConnectionError`, 응답 shape 이상 |
| 500 | `GARMIN_CONNECTOR_ERROR` | 예상치 못한 예외 |

**정책**: `/activities` 호출당 Garmin 요청 정확히 1회(`get_activities`), detail/splits fan-out 없음, 401/403/429 자동 재시도·재로그인 없음(라이브러리 내부 DI token refresh만 허용).
raw item은 rename/normalize 없이 그대로 JSON array로 반환(`activityList` wrapper만 제거). 로그는 count/status code만.

## 4. 구현 — Spring (`com.runningai.integration.garmin`)

| 클래스 | 역할 |
|--------|------|
| `GarminActivitySource` | interface `List<JsonNode> fetchRecentActivities(int limit)` — 인증/DB를 모름 |
| `HttpGarminActivitySource` | `RestClient`로 `GET {base-url}/activities?limit=N`, 1회 요청·재시도 없음, JSON array → `List<JsonNode>`. 오류 매핑: 401 AUTH_REQUIRED / 403 FORBIDDEN / 429 RATE_LIMITED / 502 UPSTREAM_ERROR / 그 외 CONNECTOR_ERROR / IO·timeout UNAVAILABLE / 비-array INVALID_RESPONSE |
| `GarminConnectorException` | 단일 exception + `Reason` enum + connector HTTP status |
| `GarminConnectorProperties` | `running-ai.garmin.connector.{base-url, connect-timeout(2s), read-timeout(30s)}` — credential 없음 |
| `GarminConnectorConfig` | timeout이 설정된 `RestClient` bean (`SimpleClientHttpRequestFactory`) |
| `GarminSyncService.syncRecent(limit)` | fetch → 활동별 `GarminActivityIngestionService.ingest(item, fetchedAt)` → `GarminSyncResult` 집계 |
| `GarminSyncResult` | `fetched, created, updated, skipped, failed` |

`application.yml`: `running-ai.garmin.connector.base-url: ${GARMIN_CONNECTOR_URL:http://127.0.0.1:8765}`. WebFlux 추가 없음(`RestClient`). 새 dependency 없음.

**Sync 정책**

```text
supported (RUN / TREADMILL_RUN / INDOOR_CYCLING)   → ingest: created 또는 updated
unsupported type (UNSUPPORTED_GARMIN_ACTIVITY_TYPE) → raw 저장됨, Activity 없음, skipped +1, 계속
malformed (id 없음 / startTime 없음 등)             → failed +1, 계속 (id가 있으면 raw는 보존)
connector-level (401/403/429/502/500/불가/응답 이상)  → GarminConnectorException 전파, 즉시 중단, 아무것도 ingest 안 함
idempotency                                        → 같은 window 재sync 시 updated만 증가, row/PK 불변
```

## 5. 데이터 흐름 / 불변 경계

```text
Garmin Connect → python-garminconnect → Garmin Connector (auth/token/transport, 127.0.0.1:8765)
   → localhost JSON → GarminActivitySource → GarminSyncService → GarminActivityIngestionService
   → activity_raw (JSONB) / activity
```

Python은 DB를 모른다 · Spring은 Garmin password/token을 모른다(설정에는 URL만) · Mapper는 network를 모른다. 3A ingestion 로직 재구현 없음.

## 6. 테스트

### Java (`server`, `gradlew.bat clean test`, H2 + Flyway, network 없음)

```text
total 83 · passed 83 · failed 0   (기존 66 + HttpGarminActivitySourceTest 11 + GarminSyncServiceTest 6)
```

- `HttpGarminActivitySourceTest` (MockRestServiceServer, Spring context 없음): array 파싱·중첩 필드 보존, 빈 배열, 401/403/429/502/500 → Reason(5 case, 요청 1회 검증), 비-JSON error body, 비-array body, `SocketTimeoutException` → UNAVAILABLE, limit 검증.
- `GarminSyncServiceTest` (`@SpringBootTest` + `@MockitoBean GarminActivitySource`, 비-transactional + cleanup): 지원 3건 → (3,3,0,0,0); 재sync → (3,0,3,0,0) & row/ID 불변; 지원+미지원 → skipped 1 & raw 2행; malformed 2 + 정상 1 → failed 2, raw 2행; connector 429 → 예외 전파 & 0행; 빈 window → (0,0,0,0,0).

### Python (`tools/garmin-connector`, pytest)

```text
total 31 · passed 31 · failed 0
```

- `test_errors` 6: 401/429/403/502/500 매핑, message에 응답 본문·비밀값 미포함, ConnectorError pass-through.
- `test_client` 7: bare list / `activityList` wrapper / None / unknown shape; `from_tokens`는 credential 없는 client + tokenstore 1회 로드;
  `get_activities(0, limit)` 정확히 1회; limit 검증; token 없음 → AUTH_REQUIRED(재시도 없음); cache drop 후 token 재로드(credential login 없음).
- `test_auth` 3: email은 input, password는 getpass(echo 없음), MFA callback이 터미널 입력, 출력에 email/password/MFA 없음; status MISSING / VALID(마스킹) / INVALID_OR_EXPIRED.
- `test_api` 12: health(login 무관 UP), array 그대로 반환, 기본 limit 20, limit 0/101/abc → 400 & Garmin 미호출, 5종 오류 매핑(1회 호출), `/login` `/docs` `/openapi.json` 없음.
- `test_cli` 3: id 마스킹, summary에 전체 id/raw 없음, `serve`에 host 옵션 없음.

**Python runtime**: 이 PC에 Python이 없어 **python-build-standalone CPython 3.12.14를 세션 scratchpad에만 풀어**(system-wide 설치·PATH·registry 변경 없음)
scratchpad 안의 venv에서 pinned 의존성(`garminconnect 0.3.16`, `curl_cffi 0.16.3`, `fastapi 0.141.1`, `uvicorn 0.54.0`)을 설치해 테스트를 실행했다. repo/사용자 환경에는 남긴 것이 없다.

### Connector 실기동 smoke (빈 token store, Garmin 요청 0회)

```text
python -m garmin_connector status      → tokens: MISSING, exit 1
python -m garmin_connector activities  → GARMIN_AUTH_REQUIRED (network 없이 실패), exit 1
python -m garmin_connector serve       → 127.0.0.1:8765 LISTENING (0.0.0.0 아님)
GET /health                → 200 {"status":"UP","service":"garmin-connector"}
GET /activities?limit=1    → 401 {"code":"GARMIN_AUTH_REQUIRED","message":"..."}
GET /activities?limit=0    → 400 INVALID_REQUEST
POST /login, GET /docs     → 404
로그: 상태 코드·count만
```

## 7. Live probe

```text
attempted:   NO — LIVE_PROBE_NOT_POSSIBLE
login:       불가 (credential 없음, 비대화형 세션이라 email/password/MFA 입력 불가)
health:      (connector 자체는 위 smoke로 확인)
activities:  실제 Garmin 호출 없음
contract:    CONFIRMED_SOURCE 유지, CONFIRMED_LIVE 없음
```

메인 PC에서 수행할 절차: `login` → `status` → `serve` → `GET /activities?limit=1` 1회 → activityId / activityType.typeKey / startTimeGMT / duration / distance / HR 존재·단위만 확인 후 ADR 표를 CONFIRMED_LIVE로 갱신, raw는 저장·commit 금지. 401/403/429/Cloudflare면 `LIVE_PROBE_BLOCKED` 기록 후 중단.

## 8. 변경 파일

```text
tools/garmin-connector/**                                 (신규, 17 files)
server/src/main/java/com/runningai/integration/garmin/
  GarminActivitySource, HttpGarminActivitySource, GarminConnectorException,
  GarminConnectorProperties, GarminConnectorConfig, GarminSyncService, GarminSyncResult   (신규)
server/src/main/resources/application.yml                 (+ running-ai.garmin.connector)
server/src/test/java/.../HttpGarminActivitySourceTest, GarminSyncServiceTest   (신규)
README.md, .claude/skills/running-ai-integration/SKILL.md, .gitignore(.venv 등)
docs/work-orders/2026-09-29-garmin-connector-spring-integration(-instruction).md
```

DB migration 추가 없음, V1–V3 미수정, 기존 API contract 변경 없음, 새 Java dependency 없음.

## 9. Limitations

- Live 검증 없음(7장). Decision은 여전히 provisional.
- python-garminconnect는 비공식 API·TLS impersonation 의존, 계정 단위 429/차단 가능, open issue #444(0.3.16 403). 자동 sync 도입(3C) 전에 호출 빈도 정책 필요.
- `status`는 read-only이지만 Garmin profile 요청 1회를 보낸다(token 검증). 빈번히 호출하지 않는다.
- connector 프로세스 감독·자동 시작 없음. 죽어 있으면 Spring은 `UNAVAILABLE`로 sync를 중단한다.
- `GarminSyncService`를 호출하는 운영 trigger(HTTP/CLI/scheduler)가 아직 없다 — 3C.
- Spring↔connector는 평문 localhost HTTP, 인증 header 없음(같은 host 전제). 원격 분리 시 재검토.
- `logout`/token 폐기는 connector CLI에 없음(파일 삭제 + Garmin 계정 설정에서 revoke, README에 기재).
- FastAPI TestClient의 httpx deprecation warning(StarletteDeprecationWarning) 1건 — 동작 영향 없음.

## 10. Next Phase (3C, 자동 시작 안 함)

1. incremental sync cursor (마지막 `startTimeGMT` 기준, DB 저장 설계 → migration 필요 시 database skill)
2. scheduler / operational trigger (Spring `@Scheduled` 또는 OS scheduler, sync API 여부 결정)
3. connector process supervision (Windows service / systemd / container)
4. Windows → Raspberry Pi 배포 전략 (Python 3.12 + curl_cffi aarch64, compose)
5. 메인 PC에서 read-only live probe 1회 → CONFIRMED_LIVE, 실제 typeKey 목록 보정
