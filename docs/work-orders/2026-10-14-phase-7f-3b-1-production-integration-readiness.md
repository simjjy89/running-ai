# Phase 7F-3B-1 — Production Integration Readiness (결과)

**날짜** 2026-10-14 · **브랜치** `main-3` @ `e76dc6b` · **지시 원문**
`2026-10-14-phase-7f-3b-1-production-integration-readiness-instruction.md`

이 단계는 **준비 전용**이다. 운영 Spring/Gateway 재시작, 운영 DB 변경, canonical main 병합,
origin push, OAuth Worker 배포, Intervals 실게시 — **어느 것도 실행하지 않았다.** 이 문서가
산출물이고, 실행은 7F-3B-2다.

---

## 1 — Git 통합 검증 (STEP 1)

| 항목 | 실측값 |
|---|---|
| canonical main (`C:\running-ai-github`) | `799e3b5`, 작업트리 **clean** |
| dev worktree (`…\main-3`) | `e76dc6b`, 작업트리 **clean** (미추적 파일 0건 — 삭제할 것도 없었음) |
| merge-base | `799e3b5` = main의 HEAD 그 자체 |
| 차이 | main-3가 **5 ahead / 0 behind** |
| **FF-only 병합** | **가능** (`git merge --ff-only main-3`가 조건을 만족) |
| origin | `origin/main` = `370ee8f` — 로컬 main보다 **25 뒤처짐**. push는 이 단계 범위 밖 |

### 릴리스 대상 5커밋

```
4ebfad5 feat(mcp): ChatGPT training lifecycle tools behind per-tool OAuth scopes
5beb23d test(draftpublish): wire-level Mock E2E for the approved-draft publish path
30113c2 feat(ops): read-only Intervals date preflight for the first real publish
05c32d7 docs: Phase 7F-2C..2F work orders - injury gate through the first real Intervals publish
e76dc6b feat(coach): give the AI coach the injury history, not only a blocking gate
```

**배포 대상 커밋 = `e76dc6b`.** 43파일 / +7,261 / −35. 신규 운영 코드는 5개 파일뿐
(`InjuryHistoryContext.kt`, `InjuryHistoryContextBuilder.kt`, `McpTrainToolConfig.kt`,
`TrainMcpTools.kt`, `scripts/dev/preflight-intervals-date.ps1`) — 나머지는 테스트와 문서다.

### 비밀키 / 운영 환경설정 혼입 — 없음

추가된 줄 전체를 `api[_-]?key|secret|password|token|bearer|authorization|credential|private[_-]?key|client[_-]?secret`
로 훑은 결과 **실제 자격증명 0건**. 적중한 항목은 전부 셋 중 하나다:

- 문서 서술(워커 scope 설명, 아키텍처 문서),
- 합성 테스트 상수 — `wire-test-key-not-real`, 빈 `running-ai.intervals.api-key=`,
  `"0123456789… PRIVATE-TAIL-NOT-FOR-THE-PROMPT"`(notes 절단을 검증하는 더미),
- 자격증명을 **읽는** 스크립트 코드(`preflight-intervals-date.ps1`이 `.env`에서
  `INTERVALS_API_KEY`를 읽는 정규식) — 값이 아니라 코드다.

`.env` / `.runtime/` 둘 다 `.gitignore`에 있고(42행, 71행), 5커밋 어디에도 포함되지 않았다.
`.env.example`은 플레이스홀더만 추가했다.

---

## 2 — Spring 릴리스 검증 (STEP 2)

| 항목 | 결과 |
|---|---|
| Java 21 | `C:\Program Files\Java\jdk-21.0.2` — 운영 JVM 4개 전부 이 JDK로 기동 중 |
| Flyway | 5커밋에 **마이그레이션 추가 없음**. 운영 DB는 V26, 새 jar도 V26 → **마이그레이션 불필요** |
| Injury History 코드 | `InjuryHistoryContext.kt` + `InjuryHistoryContextBuilder.kt` 포함, V1·V2 양쪽 `CoachTrainingContext`가 운반 |
| Train MCP 6종 | `create_workout_draft` `get_workout_draft` `revise_workout_draft` `approve_workout_draft` `preview_draft_publish` `publish_approved_draft` — 코드상 정확히 6개 |
| Read 7종 유지 | `ReadMcpTools`의 7종 변경 없음. 운영 `.env`의 `RUNNINGAI_MCP_READ_ENABLED=true` 그대로 유효 |
| Train 기본 OFF | `application.yml`: `train-enabled: ${RUNNINGAI_MCP_TRAIN_ENABLED:false}`. 운영 `.env`에 **키 자체가 없음** → false |
| Draft Publishing 기본 OFF | 운영 `.env` `RUNNING_AI_DRAFT_PUBLISHING_ENABLED=false` (게시 스위치 4종 전부 false 확인) |
| MCP 서버 스위치 | `McpServerSwitchEnvironmentPostProcessor`가 `write \|\| read \|\| train`로 확장됨. read/train 기본 false일 때 계산값은 7C-2a 이전과 **동일** → 회귀 없음 |

### `release-spring-jar.ps1` 흐름 — 검증 완료

3단계가 물리적으로 분리돼 있다: **build**(`gradlew bootJar`, 출력은 절대 실행하지 않음) →
**verify**(zip을 열어 boot loader·BOOT-INF/classes·의존 jar·`Start-Class`·Flyway 최대 버전을 실측;
`-ExpectedMaxMigration 26`이면 불일치 시 거부) → **release**(런타임 뮤텍스 안에서 `.new` 임시
파일로 쓰고 **rename으로 원자 교체**).

롤백 자산이 자동 생성된다: 기존 릴리스는 `.runtime\released\running-ai-server-<타임스탬프>-<sha8>.jar`
로 복사되고(기본 5개 보존), `.runtime\running-ai-server.jar.json` 프로비넌스 사이드카에
commit/branch/dirty/sha256/maxMigration이 기록된다. dirty 작업트리에서의 릴리스는 기본 거부
(`-AllowDirty` 명시 필요).

**현재 운영 사이드카 실측:**
`gitCommit 799e3b5` / `gitBranch main` / `gitDirty false` / `migrationCount 26` / `maxMigration 26`,
sha256 `760a2ee7…`. 즉 운영 jar는 7F-1·7F-3A **이전** 소스다 — 새 릴리스가 필요하다는 것이 확인됐다.

> **주의:** `.runtime\released\` 가 **아직 비어 있다**(디렉터리 미생성). 현재 운영 jar는 백업 대상이
> 없던 최초 릴리스였기 때문이다. 다음 릴리스가 바로 `799e3b5` jar를 백업으로 만들어 주므로,
> **롤백 자산은 그 릴리스 직후부터 존재**한다. 그 전에 수동 복사가 필요하면 §8 Stage 2에 적었다.

---

## 3 — Gateway 운영 경로 안정화 계획 (STEP 3)

### 실측된 문제 — 보고된 것보다 한 단계 더 깊다

```
작업 스케줄러 RunningAI-McpGateway (State: Running)
  └─ Execute: …\orca\workspaces\running-ai-github\main-3\.runtime\mcp-gateway.cmd   ← 런처도 dev
       └─ node …\main-3\tools\mcp-gateway\server.js                                  ← 실행 파일도 dev
            └─ 로그 …\main-3\.runtime\logs\mcp-gateway.log                           ← 로그도 dev
```

`server.js`만 dev worktree인 게 아니라 **런처와 로그까지 전부 dev worktree**에 있다. 운영
external-relay는 올바르게 `C:\running-ai-github\tools\external-relay\server.js`에서 돈다 — 게이트웨이만
예외다.

**근본 원인:** `node_modules/`가 `.gitignore` 대상이라 canonical 체크아웃에는 `jose` 의존성이
설치돼 있지 않다(`C:\running-ai-github\tools\mcp-gateway\node_modules` 부재 확인). 그래서 유일하게
바로 실행 가능한 사본이 main-3였다.

**현재 위험도는 낮지만 0은 아니다.** 돌고 있는 `server.js`는 이미 `e76dc6b`의 7F-1 scope 정책을
담고 있다. 다만 런처가 `RUNNING_AI_ALLOWED_TOOLS`를 **설정하지 않아** 내장 기본값(Read 7종)이
쓰이고, 따라서 `SCOPES_SUPPORTED`는 `["runningai:read"]` 하나뿐이다(실측: 메타데이터 응답이
정확히 그렇다). 즉 **지금은 안전하다.** 문제는 dev worktree의 파일 한 줄만 바뀌어도 다음 재시작에
릴리스 절차 없이 운영 동작이 바뀐다는 **구조**다.

### 런처 실측 내용 (값은 마스킹)

```
RUNNING_AI_GATEWAY_PORT=17910
RUNNING_AI_GATEWAY_TARGET_URL=http://127.0.0.1:18080/mcp      ← 운영 Spring
RUNNING_AI_OAUTH_ISSUER=***          RUNNING_AI_OAUTH_AUDIENCE=***
RUNNING_AI_ALLOWED_SUB=***           RUNNING_AI_PUBLIC_BASE_URL=***
RUNNING_AI_REQUIRED_SCOPE=runningai:read
(RUNNING_AI_ALLOWED_TOOLS 미설정 → 내장 Read 7종 기본값)
```

환경변수 7개가 전부 이 `.cmd` 한 파일에 있으므로 **재구성이 아니라 복사로 재현 가능**하다.
`.runtime/`은 양쪽 worktree 모두 gitignore 대상이라 커밋 사고 위험도 없다.

### 확정 계획 — 7F-3B-2에서 실행

1. **의존성 설치**: `C:\running-ai-github\tools\mcp-gateway`에서 `npm ci`
   (`jose@^6.1.0` 하나. lockfile이 커밋돼 있으므로 버전 고정).
2. **런처 이설**: `main-3\.runtime\mcp-gateway.cmd` → `C:\running-ai-github\.runtime\mcp-gateway.cmd`
   로 복사하고, **두 경로만** canonical로 치환(`server.js` 경로, 로그 경로). 환경변수 7개는
   바이트 단위로 보존 — 재입력하지 않는다(오타가 곧 OAuth 장애다).
3. **예비 포트 선검증**(7E-4에서 성공한 기법 재사용): 같은 런처를 `RUNNING_AI_GATEWAY_PORT=17911`
   로 복사해 띄우고, `/.well-known/oauth-protected-resource` 응답이 현재 17910의 응답과
   **바이트 단위로 동일**한지 비교. 동일하면 issuer/audience/public-base/scope 재구성이 정확하다는
   뜻이다. 17911은 검증 후 종료.
4. **작업 스케줄러 재지정**: `RunningAI-McpGateway`의 Execute를 canonical `.cmd`로 변경.
   작업 이름·트리거·계정은 그대로 → OS 소유 구조 유지.
5. **재시작 + 회귀 확인**: 작업 재시작 후 Read 7종 `tools/list` 확인, 무토큰 401,
   `publish_workout` 부재 확인.
6. **롤백**: Execute를 `main-3\.runtime\mcp-gateway.cmd`로 되돌리고 작업 재시작. **약 10초.**
   dev 런처·dev `node_modules`는 **삭제하지 않고 그대로 둔다**(그것이 롤백 자산이다).

PID는 재시작이므로 바뀐다(이건 불가피). **포트 17910, 공개 URL, OAuth 등록, ChatGPT 커넥터는
전부 불변** — 기존 OAuth 연결은 끊기지 않는다.

---

## 4 — Injury History 개인정보 보호 (STEP 4)

### 확인된 노출 경로 — 실재한다

`get_training_context`(Read 7종 중 하나)는 `CoachContextSerializer`가 만든 **직렬화된 컨텍스트
전체**를 그대로 반환한다. `injuryHistory`는 V1·V2 **양쪽**의 `CoachTrainingContext`에 실린다.
운영은 `RUNNING_AI_TRAINING_CONTEXT_VERSION=V2`다. 따라서 **7F-3A를 배포하는 순간,
기존 `runningai:read` 토큰이 부상 이력을 보게 된다** — 자유 입력 `notesExcerpt` 포함.

**운영 `.env`에 injury-history 캡 설정이 하나도 없다**(실측). 즉 현재대로 배포하면
`max-notes-excerpt-chars`가 기본값 **200자**로 적용된다.

### 확정할 운영 기본 정책

운영 `.env`에 아래 한 줄을 **JAR 교체와 같은 재시작에서** 추가한다:

```
RUNNING_AI_COACH_INJURY_HISTORY_MAX_NOTES_CHARS=0
```

- **0 = 자유 입력 전면 차단.** 코치 프롬프트에도, MCP 응답에도 free-text가 들어가지 않는다.
- **유지되는 것**: 최신 부상 상태(`status`), 게이트 판정(`nonRestTrainingAllowed`/`reasonCode`),
  구조화된 증상 필드, `previousReports`, `omittedReportCount`, `daysSinceLatestActiveReport`.
  즉 **안전 판단에 필요한 정보는 하나도 잃지 않는다.**
- `history-window-days`(180), `max-previous-reports`(4)는 기본값 유지 — 과거 재발을 코치가
  계속 볼 수 있어야 한다는 7F-3A의 설계 의도 그대로다.
- **Phase 7B-2 안전 게이트는 이 값에 전혀 영향받지 않는다**(캡은 프롬프트/컨텍스트 전용).
- **접근 범위 불변**: 게이트웨이는 `RUNNING_AI_ALLOWED_SUB` 단일 subject + 루프백 target.
  데이터는 본인 인증 연결에만 간다.
- **로그**: 게이트웨이는 `authorization|token|api_key|bearer` 패턴을 `[REDACTED]`로 치환하고,
  코치 실패는 프롬프트·원문 응답·자격증명·스택트레이스를 담지 않는다(테스트로 고정).
- **Self-hosted 인스턴스별 독립 설정**: 세 캡 모두 환경변수이므로 인스턴스마다 다르게 둘 수 있다.
  코드 변경 불필요.

---

## 5 — OAuth Scope 및 MCP 검증 (STEP 5)

정책은 `tools/mcp-gateway/server.js`에 **하드코딩**돼 있다(`TOOL_SCOPES`). 환경변수로 쓰기
도구를 read scope로 **낮출 수 없다** — 이것이 설계의 핵심이다.

| 요구 | 구현 | 상태 |
|---|---|---|
| 1. Read만으로 쓰기 도구 불가 | 6종 전부 `TOOL_SCOPES`에 train/publish로 등재 | ✅ 테스트 통과 |
| 2. Train으로 외부 게시 불가 | `publish_approved_draft`만 `runningai:publish`, scope는 **비계층적** | ✅ `train alone cannot publish -> 403` |
| 3. Publish만으로 Draft 생성 불가 | `create_workout_draft`는 `runningai:train` 요구 | ✅ |
| 4. allowlist ∧ scope 둘 다 통과해야 호출 | `ALLOWED_TOOLS.has(name)` 먼저, 그 다음 `granted.includes(scopeFor(name))` | ✅ |
| 5. 기존 read 연결 자동 확대 금지 | scope는 토큰에 박힌 값. 기존 토큰엔 train/publish가 **없음** → 재동의 없이는 불가 | ✅ |
| 6. 미승인 Draft 게시 차단 | `ApprovedWorkoutDraftPublishService`가 승인 행 요구 + MCP는 `acknowledgedApprovalId` 일치까지 요구 | ✅ |
| 7. 무토큰/위조/권한부족 차단 | 401 / 401(`alg:none`) / 403 challenge, **백엔드 트래픽 0** | ✅ |
| 8. tools/list 비노출 | `ALLOWED_TOOLS ∩ 부여된 scope`로 필터 | ✅ `read token sees no training tool` |
| 9. `publish_workout` 영구 금지 | allowlist에 있으면 **기동 자체 실패**(FATAL) | ✅ `publish_workout on the allowlist refuses to start` |
| 10. ChatGPT 무승인 외부게시 방지 | `confirmedByAthlete: true` **＋** 선행 읽기로만 얻는 `acknowledgedApprovalId` 에코, 커밋 서비스 진입 **전** 검사 | ✅ |

**추가로 중요한 두 가지:**

- **scope 실패는 백엔드에 닿기 전에 403 challenge로 끝난다**(`server.js` 주석과 테스트가 함께
  보증: "backend at zero hits"). JSON-RPC 오류가 아니라 HTTP 403 + `WWW-Authenticate`이므로
  클라이언트가 "재인가가 필요하다"를 배운다.
- **현재 Worker가 구조적 차단막이다.** `tools/mcp-worker/src/index.js:25-26`:
  `SCOPES_SUPPORTED = ['runningai:read', 'offline_access']`, 요청 scope를 여기에 교집합
  (`line 179`). **train/publish를 담은 토큰은 지금 발급 자체가 불가능하다.** 이 단계에서
  건드리지 않았다.

---

## 6 — 운영 Injury Report 처리 계획 (STEP 6)

**운영 실측(읽기 전용):**
`GET /api/v1/athlete/injury-state` → `nonRestTrainingAllowed: false`,
`reasonCode: NO_INJURY_STATE_RECORDED`. `GET /api/v1/athlete/injury-reports` → **0건**.

이것은 결함이 아니라 **정상 동작**이다. 자기 신고가 없으면 게이트는 닫혀 있어야 한다.

**개발 DB의 Report #1~#3은 복사하지 않는다.** 복사하면 (a) 남이 쓴 신고가 본인 신고로
둔갑하고, (b) 신고 시각이 조작되며, (c) append-only 원장의 의미가 무너진다.

### 7F-3B-2에서의 절차 — 사용자가 직접 수행

```
POST http://127.0.0.1:18080/api/v1/athlete/injury-reports
{ "status": "RESOLVED", "painPresent": false, ... }     ← 실제 상태만, 본인 표현으로
```

- `status`만 필수. `reportedAt`은 **생략**한다 → 서버가 실제 신고 시각을 기록한다(조작 없음).
- `notes`는 선택. `MAX_NOTES_CHARS=0`이면 프롬프트/MCP에 노출되지 않지만 **DB에는 저장된다**
  — 남기지 않을 자유도 사용자에게 있다.
- **append-only**: 이 신고는 기존 이력을 지우지 않는다. 운영은 0건이므로 이것이 1번째 행이 된다.
- **48시간 freshness**: `RESOLVED`는 48시간 안에서만 유효하다. 따라서 이 등록은
  **실게시 직전에** 해야 한다 — 며칠 먼저 해두면 게이트가 다시 닫힌다.
- **검증**: 등록 직후 `GET /injury-state`가 `nonRestTrainingAllowed: true`로 바뀌는지 확인.
  Draft 생성이 `WorkoutSafetyViolationException` 없이 통과하는지로 2차 확인.

**이번 단계에서 운영 DB에 어떤 행도 쓰지 않았다.** 위 호출은 전부 GET만 실행했다.

---

## 7 — 외부 게시 중복 방지 (STEP 7)

기존 이벤트: `2026-10-16` / event `144442046` / marker `runningai:workout:v1:0:2026-10-16`.
**개발 DB가 만든 것이고, 운영 DB에는 대응하는 게시 이력이 없다.**

이 비대칭이 바로 위험이다. 운영이 같은 날짜로 게시를 시도하면 publisher는 marker가 같은
기존 이벤트를 발견하고 **UPDATE(takeover)** 로 처리할 수 있다 — 개발이 만든 이벤트를 운영이
가져가 버린다.

**따라서 7F-3B-2에서 지키는 규칙:**

- **2026-10-16을 운영 게시 대상으로 삼지 않는다.** 자동도 수동도 아니다.
- **takeover 금지.** marker가 같다고 해서 운영이 그 이벤트를 자기 것으로 만들지 않는다.
- **게시 기록 복사 금지.** `workout_draft_publication` 행을 개발→운영으로 옮기지 않는다
  (옮기면 운영이 하지 않은 쓰기를 했다고 기록하는 것이다).
- **기존 이벤트 삭제·수정 금지.** 그대로 둔다. 7F-2F의 증거물이다.

**구조적으로 자동 게시가 불가능한 상태인 것도 확인했다:** 운영 `.env`의 게시 스위치 4종
(`WORKOUT_PUBLISHING_ENABLED`, `WORKOUT_PUBLISHING_SCHEDULER_ENABLED`,
`RUNNING_AI_DRAFT_PUBLISHING_ENABLED`, `RUNNINGAI_MCP_ENABLED`) 전부 `false`. 스케줄러 없음.
즉 **지금 운영이 스스로 어떤 날짜든 게시할 경로가 없다.**

새 날짜로 실게시를 시험하려면 **날짜를 정해 사용자 승인부터 받는다.** 승인 전에는
`scripts/dev/preflight-intervals-date.ps1`(읽기 전용)로 해당 날짜가 비어 있는지 먼저 확인한다.

---

## 8 — 운영 배포 및 롤백 계획 (STEP 8)

각 Stage는 **진입 조건 → 실행 → 검증 → 롤백**이다. 어느 Stage에서 멈춰도 그 앞까지는 정상 상태다.

| # | Stage | 실행 | 검증 | 롤백 | 중단 |
|---|---|---|---|---|---|
| 1 | **Git FF 통합** | canonical에서 `git merge --ff-only main-3` | `git log -1` = `e76dc6b`, status clean | `git reset --hard 799e3b5` | 0초 |
| 2 | **릴리스 빌드** | canonical에서 `release-spring-jar.ps1 -VerifyOnly -ExpectedMaxMigration 26` | Start-Class·의존jar·`maxMigration=26` 출력 | 없음(운영 미변경) | 0초 |
| 3 | **JAR 교체 + 재시작** | `release-spring-jar.ps1 -ExpectedMaxMigration 26` → `stop-running-ai.ps1` → `start-running-ai.ps1`. **같은 재시작에서 `.env`에 `RUNNING_AI_COACH_INJURY_HISTORY_MAX_NOTES_CHARS=0` 추가** | `/api/v1/health` UP, 사이드카 `gitCommit=e76dc6b` | `.runtime\released\`의 `799e3b5` jar를 `running-ai-server.jar`로 복사 후 재시작 | **~30–60초** |
| 4 | **Read 7종 회귀** | 게이트웨이 경유 `tools/list` + 7종 각 1회 호출 | 정확히 7종, 전부 `readOnly=true`. `get_training_context`의 `notesExcerpt` **부재** 확인 | Stage 3 롤백 | 0초 |
| 5 | **Gateway 경로 분리** | §3의 1–3단계 (`npm ci`, 런처 복사, **17911 선검증**) | 17911 메타데이터가 17910과 바이트 동일 | 17911 종료. 운영 무영향 | 0초 |
| 6 | **Gateway 재시작** | 작업 Execute를 canonical로 변경 → 작업 재시작 | Read 7종 재확인, 무토큰 401 | Execute를 main-3 `.cmd`로 환원 + 재시작 | **~10초** |
| 7 | **Train MCP 활성화** | `.env`에 `RUNNINGAI_MCP_TRAIN_ENABLED=true` → Spring 재시작 | Spring `tools/list` **13종**. **게이트웨이 경유는 여전히 7종**(allowlist 미변경) | 키 제거 후 재시작 | **~30–60초** |
| 8 | **운영 Injury Report** | **사용자 본인이** `POST /injury-reports` (§6) | `injury-state` → `nonRestTrainingAllowed: true` | append-only — 되돌리려면 새 `ACTIVE` 신고. 48h 후 자동 재차단 | 0초 |
| 9 | **Worker Train Scope** | Worker `SCOPES_SUPPORTED`에 `runningai:train` 추가 → 배포. **게이트웨이 `.cmd`에 `RUNNING_AI_ALLOWED_TOOLS`로 train 5종 추가**(기본값이 Read 7종이므로 명시 필수) → 재시작 | 메타데이터 `scopes_supported`에 train 등장 | Worker를 이전 버전으로 되돌리고 `.cmd`의 ALLOWED_TOOLS 환원 | **~10초** |
| 10 | **ChatGPT 재동의** | 커넥터 연결 해제 → 재인가(새 scope 집합 동의) | 새 토큰에 `runningai:train` 포함 | 기존 read 토큰으로 재연결 | 0초 |
| 11 | **Draft 생성·조회·승인** | ChatGPT에서 create → get → approve | 각 단계 성공. approve는 `confirmedByAthlete`+`acknowledgedVersion` 요구 | 드래프트는 미게시 상태로 방치 가능(외부 영향 0) | 0초 |
| 12 | **Publish Scope 활성화** | **별도 사용자 승인 후**: Worker에 `runningai:publish` 추가, `.cmd` ALLOWED_TOOLS에 `publish_approved_draft` 추가, `.env`에 `RUNNING_AI_DRAFT_PUBLISHING_ENABLED=true` → 재시작 | `PublishingModeGuard`가 기동을 막지 않음(legacy는 false여야 함) | 세 변경 전부 환원 | **~30–60초** |
| 13 | **실게시 확인** | **사전 승인된 새 날짜**로 publish. 2026-10-16 **금지** | `CREATED` + readback 검증 + 재호출 시 추가 쓰기 0 | Intervals 이벤트 수동 삭제(사용자 판단) | 0초 |
| 14 | **Garmin 연결 확인** | 커넥터 health + 수동 sync 1회 | 정상 응답 | 없음(읽기) | 0초 |

**총 예상 중단 시간: Spring 재시작 3회(Stage 3·7·12) × 30–60초 + Gateway 재시작 2회
(Stage 6·9) × 10초 ≈ 2–4분.** 연속이 아니라 분산되며, 각 재시작 사이에 검증이 들어간다.
Stage 7과 12를 Stage 3에 합치면 재시작 1회로 줄일 수 있으나 **권장하지 않는다** — 한 번에 바꾸면
무엇이 깨졌는지 분리할 수 없다.

### Stage 12에 대한 운영상 주의

MCP로 게시하려면 `RUNNING_AI_DRAFT_PUBLISHING_ENABLED=true`가 **상시** 켜져 있어야 한다.
이는 `publish-approved-draft-controlled.ps1`(게시 직전 켜고 직후 끄는 방식)과 다른 위험 프로필이다.
Stage 12는 "그 스위치를 계속 켠 채로 운영한다"는 결정이므로, Stage 11까지와 분리해 **별도 승인**을
받는 것이 맞다.

---

## 9 — 테스트 (STEP 9)

| 대상 | 명령 | 결과 |
|---|---|---|
| 서버 전체 | `.\gradlew.bat clean test` (JDK 21.0.2) | **1,442 PASS / 0 FAIL**, BUILD SUCCESSFUL (2m 51s) |
| MCP Gateway | `node test/gateway-test.js` | **46/46 PASS** |
| Train MCP 권한 | `McpTrainServerApiTest` 21 / `McpTrainSwitchSeparationTest` 2 / `TrainMcpToolsArchitectureTest` 6 | **29 PASS** |
| Injury-aware Coach | `CoachInjuryHistoryContextTest` 13 / `WorkoutDraftSafetyGateTest` 10 | **23 PASS** |
| Read Tool 회귀 | `McpReadServerApiTest` 14 / `McpReadToolsArchitectureTest` 4 / `McpReadWriteSeparationTest` 1 | **19 PASS** |
| Draft→Approval→Preview E2E | `DraftPublishIntervalsWireE2ETest` (wire-level Mock) | **12 PASS** |

**외부 Intervals 실게시는 반복하지 않았다.** 운영 Spring(18080)·게이트웨이(17910)에는 읽기 전용
요청만 보냈고 두 프로세스 모두 재시작하지 않았다(PID 41436 / 48104 유지).

### 9.1 서버 전체 테스트 결과

```
BUILD SUCCESSFUL in 2m 51s
9 actionable tasks: 9 executed
PASSED = 1442   FAILED = 0   (SKIPPED 1건은 테스트가 아니라 Gradle 태스크
                              :checkKotlinGradlePluginConfigurationErrors)
```

로그에 나타나는 `FAILED` 문자열 7건은 전부 **테스트 이름**이다(`status=401, reason=AUTH_FAILED
PASSED`, `a FAILED run is not resumable() PASSED` 등) — 실패가 아니다. 지시서가 제시한 기준선
1,442 PASS와 **정확히 일치**한다.

`clean`이 안전한 이유도 확인했다: 운영 JVM(PID 41436)은 `C:\running-ai-github\.runtime\running-ai-server.jar`
를, dev JVM 2개는 `main-3\.runtime\dev-spring-1808x.jar`를 각각 잡고 있다. **어떤 JVM도
`main-3\server\build\libs`를 열고 있지 않다.**

---

## 10 — 실행 전 필요한 사용자 승인

| # | 승인 항목 | 이유 |
|---|---|---|
| 1 | Stage 1 FF 병합 + (선택) origin push | canonical 이력 변경 |
| 2 | Stage 3 운영 Spring 재시작 | 운영 중단 발생 |
| 3 | `MAX_NOTES_CHARS=0` 정책 확정 | 개인정보 노출 범위 결정 |
| 4 | Stage 6 Gateway 재시작 | ChatGPT 연결 일시 중단 |
| 5 | Stage 8 Injury Report 등록 | **사용자 본인만 가능** — 대행 불가 |
| 6 | Stage 9 Worker train scope 확대 | 권한 확대 |
| 7 | Stage 10 ChatGPT 재동의 | 사용자 조작 필요 |
| 8 | **Stage 12 publish scope + 상시 게시 스위치** | **외부 쓰기 권한 상시화 — 별도 승인** |
| 9 | **Stage 13 실게시 날짜 지정** | 2026-10-16 금지, 날짜는 사용자가 정함 |

---

## 11 — 아키텍처

**Modular Monolith + Single-Tenant Self-hosted 유지.** 이 단계는 코드를 한 줄도 바꾸지 않았다
(문서 2건만 추가). 게이트웨이 경로 분리는 배포 토폴로지 정리이지 아키텍처 변경이 아니다.
Injury History 캡은 전부 환경변수이므로 인스턴스별 독립 설정이 코드 변경 없이 가능하다.

---

## 12 — 한계 / 미해결

- `origin/main`이 로컬 main보다 25커밋 뒤처져 있다. push는 이 단계 범위 밖이며, 7F-3B-2에서
  별도 판단이 필요하다.
- Stage 13 실게시 날짜가 미정이다.
- 운영 Claude CLI는 PATH에 존재하지만 **현재 jar로는 서비스 중 검증되지 않았다**(7F-3A의
  항목 8과 동일). Stage 3 재시작 후 Draft 생성으로 재확인해야 한다.
- 개발 DB(18081/18082)와 운영 DB가 같은 Intervals 계정을 공유하는 구조는 그대로다. §7의
  날짜 충돌은 그 구조의 결과이며, 근본 분리는 이 단계의 범위가 아니다.
