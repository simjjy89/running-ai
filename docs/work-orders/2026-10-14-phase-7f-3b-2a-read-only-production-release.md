# Phase 7F-3B-2A — Read-only Production Release (결과)

- 일자: 2026-10-14
- 지시서 원문: `2026-10-14-phase-7f-3b-2a-read-only-production-release-instruction.md`
- 최종 판정: **PASS** (단, 잔여 확인 1건 — ChatGPT 실토큰 Read 호출은 사용자만 수행 가능, 9장 참조)

목적: 최신 코드(e76dc6b)를 운영에 반영하되, 기존 Read-only 서비스 동작은 그대로 유지한다.
Train/Publish 활성화와 외부 서비스 쓰기는 이번 범위가 **아니다**.

---

## 1. STEP 1 — 배포 전 운영 상태 (기준선)

| 항목 | 배포 전 값 |
| --- | --- |
| 운영 Spring | PID 41436, 기동 2026-10-13 19:40:07, `C:\running-ai-github\.runtime\running-ai-server.jar` |
| 운영 Spring 인자 | 없음 (설정은 전부 `C:\running-ai-github\.env` 경유) |
| Gateway | PID 48104, 기동 2026-10-13 19:06:49, `main-3\tools\mcp-gateway\server.js` ← **개발 worktree 의존** |
| Scheduled Task `RunningAI-McpGateway` | Execute = `main-3\.runtime\mcp-gateway.cmd` |
| 포트 | Spring 18080, Gateway 17910, Relay 17845, PostgreSQL 5432 |
| DB / Flyway | `running_ai` / 26개 적용, 최신 `V26__create_strava_activity_import.sql` |
| MCP 등록 도구 | Read 7종만 (`get_recent_activities`, `get_activity_analysis`, `get_training_context`, `get_recovery_context`, `get_fitness_trend`, `get_workout_recommendation`, `get_athlete_profile`) |
| Train/Publish | `RUNNINGAI_MCP_TRAIN_ENABLED` 미설정(기본 false), draft/legacy publishing 모두 false |
| Watchdog | `overall=UP`, 전 컴포넌트 UP, restart budget 0 사용 |
| Relay / Watchface | Relay 17845 `{"status":"ok"}`, Watchface SDK 프로세스 정상 |

운영 DB 행 수 기준선:

```
flyway_count 26 | activity 48 | injury_report 0 | workout_draft 19
workout_draft_approval 3 | workout_draft_publication 3 | activity_analysis 48
activity_sample 75206 | activity_lap 239 | intervals_fitness_daily 99 | garmin_recovery_daily 21
```

운영 publication 3건은 모두 2026-10-03~10-05의 `UPDATED` (event 140389091 / 140391756 / 140511724).
보호 대상 **144442046은 운영 DB에 존재하지 않는다** — 개발 DB(`running_ai_7f2`) 소유라는 전제가 재확인되었다.

Read 7종은 등록 여부만이 아니라 실제 응답까지 확인했다(대표 4종 포함, 전부 실데이터 반환).

> 비밀키·토큰·비밀번호·Authorization 헤더는 본 문서 어디에도 기록하지 않았다.

---

## 2. STEP 2 — 기존 Spring JAR 백업 및 롤백 검증

### 2.1 실행 중 JAR 식별 (추정 아님)

운영 PID 41436의 실제 커맨드라인에서 경로를 읽었다.

- 경로: `C:\running-ai-github\.runtime\running-ai-server.jar`
- SHA-256: `760a2ee706f78c1f5f08a1d4b61bec6fdd85b59085697acbe9dd226a67d35ef5`
- sidecar 일치: `gitCommit=799e3b5…`, `gitDirty=false`, `maxMigration=26` → **운영 DB V26과 호환 확인**

`.runtime\released\` 는 실제로 **존재하지 않았다**(7F-3B-1 지적대로 독립 백업 자산 없음). 그래서 릴리스 전에 직접 만들었다.

### 2.2 백업 생성

| 자산 | 경로 |
| --- | --- |
| JAR | `.runtime\backup\running-ai-server-PRE-7F3B2A-799e3b5.jar` |
| sidecar | `.runtime\backup\running-ai-server-PRE-7F3B2A-799e3b5.jar.json` |
| 실행 설정(.env) | `.runtime\backup\env.PRE-7F3B2A-20261014` (ACL을 소유자 전용으로 축소) |

SHA-256 원본/백업 일치 확인: `760a2ee7…` = `760a2ee7…` → **True** (76,509,271 bytes).

### 2.3 롤백 가능성 검증

백업 JAR을 zip으로 열어 릴리스 스크립트와 동일한 기준으로 구조 검증:

```
loaderEntries 113 | classEntries 696 | libJars 102
Start-Class com.runningai.RunningAiApplication
migrations 26 files, highest V26
integration/mcp/train/* 엔트리 0  (= 799e3b5 빌드임을 교차 확인)
```

**Watchdog 덮어쓰기 위험 검증(핵심)**: `RunningAI.Watchdog.ps1:782` 는 복구 시
`start-running-ai.ps1` 를 **`-Build` 없이** 호출한다. 즉 Watchdog은 릴리스를 수행할 수 없고
`.runtime\running-ai-server.jar` 를 쓰는 주체는 `release-spring-jar.ps1` 하나뿐이다.
→ 롤백 중 최신 JAR로 되돌아가 덮이는 경로는 **없다**.

### 2.4 문서화된 롤백 절차

```powershell
# 1) 배포창에서 Watchdog 간섭 차단
Disable-ScheduledTask -TaskName 'RunningAI-Watchdog'

# 2) Spring만 정상 종료
. 'C:\running-ai-github\scripts\windows\RunningAI.Common.ps1'
$m = Get-SpringMarkers
Stop-TrackedProcess -ProcessId (Get-TrackedProcessId 'spring' $m) -Markers $m -TimeoutSec 30
Remove-PidFile 'spring'

# 3) 이전 JAR/설정 복원
Copy-Item 'C:\running-ai-github\.runtime\backup\running-ai-server-PRE-7F3B2A-799e3b5.jar' `
          'C:\running-ai-github\.runtime\running-ai-server.jar' -Force
Copy-Item 'C:\running-ai-github\.runtime\backup\running-ai-server-PRE-7F3B2A-799e3b5.jar.json' `
          'C:\running-ai-github\.runtime\running-ai-server.jar.json' -Force
Copy-Item 'C:\running-ai-github\.runtime\backup\env.PRE-7F3B2A-20261014' `
          'C:\running-ai-github\.env' -Force

# 4) 기동 + Watchdog 복구
powershell -NoProfile -ExecutionPolicy Bypass -File 'C:\running-ai-github\scripts\windows\start-running-ai.ps1' -SpringPort 18080
Enable-ScheduledTask -TaskName 'RunningAI-Watchdog'
```

릴리스 스크립트가 자체 생성한 **두 번째** 롤백본도 존재한다:
`.runtime\released\running-ai-server-20261014-105352-760a2ee7.jar` (+ `.json`).

**STEP 2 판정: PASS.**

---

## 3. STEP 3 — Canonical Git FF-only 통합

사전 재검증: canonical `main` = `799e3b5`, `main-3` = `e76dc6b`, 양쪽 작업트리 clean,
`merge-base` = `799e3b5`, `main...main-3` = `0 ahead / 5 behind`, `799e3b5` 는 `e76dc6b` 의 조상.

```
git merge --ff-only main-3
Updating 799e3b5..e76dc6b
Fast-forward
 43 files changed, 7261 insertions(+), 35 deletions(-)
```

- canonical main 최종 HEAD: `e76dc6bc687e0ff0713644d39da3560c54e8b708`
- main-3 HEAD: 변경 없음, 동일 커밋
- merge commit **없음**, rebase/reset/force **없음**, `origin` push **없음**
- 변경 범위: docs / `scripts/dev` / coach injury-history / train MCP / gateway.
  **`server/src/main/resources/db/migration` 변경 0건** → 신규 마이그레이션 없음 재확인

**STEP 3 판정: PASS.**

---

## 4. STEP 4 — 신규 Spring 릴리스

### 4.1 빌드 및 전체 테스트

canonical main(e76dc6b)에서 Java 21로 전체 회귀:

```
.\gradlew.bat clean test  →  BUILD SUCCESSFUL in 2m 46s
147 test classes, tests=1442 skipped=0 failures=0 errors=0
```

### 4.2 릴리스 전 검증

```
release-spring-jar.ps1 -VerifyOnly -ExpectedMaxMigration 26
Source: main @ e76dc6b            (dirty 아님)
Start-Class : com.runningai.RunningAiApplication
dependencies: 102 jars
migrations  : 26 files, highest V26
sha256      : a5a0d8a2a6beb1a3e6b3b08b5a95bf6bc00d0134567e047ab68ef0922575efae
EXIT=0
```

### 4.3 설정 — 환경변수 이름 실검증

지시서의 `RUNNING_AI_COACH_INJURY_HISTORY_MAX_NOTES_CHARS` 가 실제 구현과 **일치**함을 먼저 확인했다
(`application.yml:204` → `max-notes-excerpt-chars: ${RUNNING_AI_COACH_INJURY_HISTORY_MAX_NOTES_CHARS:200}`
→ `InjuryHistoryContextProperties.maxNotesExcerptChars`). 이름 불일치가 없었으므로 STOP 사유는 발생하지 않았다.

`C:\running-ai-github\.env` 에 추가(기존 값은 수정하지 않음):

```
RUNNINGAI_MCP_TRAIN_ENABLED=false
RUNNING_AI_COACH_INJURY_HISTORY_MAX_NOTES_CHARS=0
```

기존 OFF 스위치 유지 확인: `RUNNING_AI_DRAFT_PUBLISHING_ENABLED=false`,
`WORKOUT_PUBLISHING_ENABLED=false`, `WORKOUT_PUBLISHING_SCHEDULER_ENABLED=false`,
`RUNNINGAI_MCP_ENABLED=false`, `RUNNINGAI_MCP_READ_ENABLED=true`.

### 4.4 기동이 운영 DB를 바꿀 수 있는가 (진행 전 검증)

- Flyway: jar 26 = DB 26 → 적용할 마이그레이션 없음
- 스케줄러: garmin sync `false`, workout publishing `false`, publishing scheduler `false`,
  coach refresh `false`. 유일한 활성은 **garmin profile-sync** (`GARMIN_PROFILE_SYNC_ENABLED=true`,
  cron `0 30 4 * * *` Asia/Seoul) — 기동 트리거가 아니라 **cron 전용**이며 배포 시각(10:53~10:58 KST)에
  발화 구간이 아니다. 또한 이는 배포 전부터 동일했던 기존 운영 설정이다.
- 기동 훅: 전 코드에서 `ApplicationRunner` 는 `AthleteService` 하나뿐이고, 기본 athlete가
  **있으면 조회만** 하는 멱등 구현(`findByName(...).orElseGet(save)`) — 운영에는 이미 존재.

→ 기동 자체로 인한 DB 변경 가능성 없음을 확인하고 진행.

### 4.5 재시작

```
10:53:26  Disable-ScheduledTask RunningAI-Watchdog          (배포창 간섭 차단)
10:53:45  Spring만 graceful stop (PID 41436, 2.3s), 18080 해제
          ※ connector / relay / PostgreSQL 은 건드리지 않음
10:53:52  release-spring-jar.ps1 -SkipBuild -ExpectedMaxMigration 26  → EXIT=0
          이전 릴리스 보관: .runtime\released\running-ai-server-20261014-105352-760a2ee7.jar
10:54:15  start-running-ai.ps1 -SpringPort 18080 → EXIT=0, Spring UP (PID 3576)
10:55:33  Watchdog 재활성화 + 즉시 1회 실행 → overall=UP, lastAction=NONE
```

- **실제 서비스 중단 시간: 약 30초** (18080 기준 10:53:45 → 10:54:15)
- 기동 로그 provenance: `released a5a0d8a2a6be... from main@e76dc6b, migrations up to V26`
- `.env` 22개 변수 주입 확인 — 신규 2개 포함
- 신규 sidecar: `sha256=a5a0d8a2…`, `gitCommit=e76dc6b…`, `gitDirty=false`, `maxMigration=26`
- 실행 중 JAR 해시 = sidecar 해시 **일치**

**STEP 4 판정: PASS.**

---

## 5. STEP 5 — Spring Read-only 회귀 검증

| # | 항목 | 결과 |
| --- | --- | --- |
| 1 | Spring 18080 | `{"status":"UP"}` |
| 2 | DB `running_ai` 연결 | 정상 |
| 3 | Flyway | 26건, head `V26__create_strava_activity_import.sql` |
| 4 | 활동 48건 | 유지 |
| 5 | 분석 데이터 조회 | `activity_analysis` 48, 도구 응답 정상 |
| 6 | MCP Read 7종 등록 | 7종 정확히 등록 |
| 7 | Train MCP 6종 | **미등록** — `create_workout_draft` 호출 시 `-32602 Tool not found` |
| 8 | 게시 스위치 | 전부 OFF |
| 9 | Injury Report | 0건 |
| 10 | 운영 DB 변경 | **0건** (11개 테이블 전수 비교, 기준선과 완전 동일) |
| 11 | 외부 Intervals 쓰기 | 0건 |
| 12 | sidecar/JAR 해시 | 일치 |

Read 7종 **기능** 확인(등록 여부가 아님):

- `get_recent_activities` → 14일 3건 실데이터
- `get_athlete_profile` → LTHR 176, threshold pace 290
- `get_workout_recommendation` → injury gate `NO_INJURY_STATE_RECORDED` 로 정상 차단 동작
- `get_training_context` → V2, 14799 bytes
- `get_fitness_trend` → 인자 제공 시 12일 반환 (무인자 시 `INVALID_ARGUMENT` 는 정상 계약)
- `get_recovery_context` → HRV/baseline 반환
- `get_activity_analysis` → 등록 확인

**신규 코드가 실제로 반영되었다는 증거**: `get_training_context` 응답에 7F-3A의
`injuryHistory` 블록이 새로 포함(`gate` / `totalReportCount` / `historyWindowDays` /
`omittedReportCount` / `previousReports`). 컨텍스트 크기 14281 → 14799 bytes 로 증가.
Injury **Gate** 판정은 변경 없음(`nonRestTrainingAllowed=false`).

### notesExcerpt = 0 검증

- 프로세스 환경변수 주입 확인 (기동 로그에 변수명 포함)
- `application.yml:204` 직접 플레이스홀더 바인딩 확인
- 구현 확인: `InjuryHistoryContext.kt:105-107` 의 `notesExcerptChars?.takeIf { it > 0 }`
  → **0이면 excerpt는 null**, 구조화 필드와 `notesPresent` 는 영향 없음
- 한계: 운영 Injury Report가 0건이라 `latest` 자체가 null 이므로 **운영에서 실제로 excerpt 경로를
  태워 본 것은 아니다**. Injury Report INSERT는 금지 사항이므로 수행하지 않았다.
  (단위 테스트 `CoachInjuryHistoryContextTest` 가 cap 동작을 커버)

**STEP 5 판정: PASS.**

---

## 6. STEP 6 — Gateway 독립 실행 환경 준비

### 6.1 코드 출처 및 동일성

배포 전용 경로 `C:\running-ai-github\.runtime\mcp-gateway\` 를 만들고 canonical main 코드를 복사.

`server.js` 의 SHA-256이 main-3 작업본과 달라 보였으나(`b5744972…` vs `fcfe1e1e…`),
원인은 **체크아웃 개행(CRLF vs LF)** 이며 git blob은 양쪽 모두 `5bf94a2af5b8874b9ee0a17030a8fddf94bd28c7`
로 **완전히 동일**함을 확인했다(`git hash-object` = `git rev-parse HEAD:...`).

| 파일 | 배포본 SHA-256 | git blob (권위) |
| --- | --- | --- |
| `server.js` | `b5744972014cd922…` | `5bf94a2af5b8874b9ee0a17030a8fddf94bd28c7` |
| `package.json` | `e4fe125ec3ba7d4d…` | — |
| `package-lock.json` | `9cfb2ea732082235…` | — |

`server.js` 는 외부 의존이 `node:http` 와 `jose` 뿐이고 **파일/상대경로 참조가 전혀 없다**(설정은 전부 환경변수).

### 6.2 의존성

```
npm ci --omit=dev  →  added 1 package, audited 2, found 0 vulnerabilities, EXIT=0
jose 6.2.12  (package-lock.json 고정값과 일치)
Node.js v24.19.0
```

개발 worktree의 `node_modules` 를 복사하지 않고 lock 기준으로 새로 설치했다.

### 6.3 배포 전용 실행 경로

| 구성요소 | 경로 |
| --- | --- |
| 코드 | `C:\running-ai-github\.runtime\mcp-gateway\server.js` |
| 의존성 | `C:\running-ai-github\.runtime\mcp-gateway\node_modules\` |
| 런처(운영) | `C:\running-ai-github\.runtime\mcp-gateway.cmd` |
| 런처(검증) | `C:\running-ai-github\.runtime\mcp-gateway-17911.cmd` |
| 로그 | `C:\running-ai-github\.runtime\logs\mcp-gateway.log` |

환경변수 7개는 기존 값을 **그대로** 옮겼다(포트만 다름). OAuth 값 재생성 없음.

게이트웨이 테스트 스위트를 배포 경로에서 실행: **46/46 통과**
(per-tool scope 분리, 비계층적 scope, `publish_workout` 영구 차단, JWKS 장애 시 fail-closed 포함).

### 6.4 17911 선검증

17910 운영 인스턴스를 **유지한 채** 17911 기동. 시작 로그 정책 라인:

```
port=17911 baseScope=runningai:read scopes=runningai:read
tools=get_recent_activities=runningai:read|...|get_athlete_profile=runningai:read
```

→ allowed tools 기본값 Read 7종, 광고 scope는 `runningai:read` 단일. Train/Publish **미노출**.

17911 vs 17910 동작 비교 — **완전 동일**:

| 검사 | 17911 | 17910(당시 운영) |
| --- | --- | --- |
| 무토큰 | 401 | 401 |
| `WWW-Authenticate` | `realm="RunningAI", error="invalid_request", resource_metadata=…` | 동일 문자열 |
| 위조 토큰 | 401 | 401 |
| protected-resource metadata | `scopes_supported:["runningai:read"]` | 동일 |

**포트 변경 영향 없음 확인**: resource / issuer / authorization_servers 는 모두
`RUNNING_AI_PUBLIC_BASE_URL` 에서 파생되며 로컬 포트와 무관 → callback URL·audience·issuer·프록시
라우팅 어느 것도 달라지지 않는다. 운영 OAuth 설정은 한 글자도 변경하지 않았다.

**STEP 6 판정: PASS.**

---

## 7. STEP 7 — Gateway 스케줄러 경로 전환

### 7.1 변경 전 보호

| 자산 | 경로 |
| --- | --- |
| Scheduled Task 정의 | `.runtime\backup\RunningAI-McpGateway.PRE-7F3B2A.xml` |
| 기존 런처 | `.runtime\backup\mcp-gateway.PRE-7F3B2A.cmd` |
| 기존 Execute | `main-3\.runtime\mcp-gateway.cmd` (원본도 그대로 남겨둠) |

실행 계정 `simjy` / `InteractiveToken`, 트리거 `MSFT_TaskTimeTrigger`, `ExecutionTimeLimit PT72H`,
`MultipleInstances IgnoreNew` — **모두 변경하지 않았다**. Action의 Execute 경로 하나만 교체.

### 7.2 전환

```
10:57:48  17911 검증 인스턴스 종료 → 운영 Gateway(PID 48104) 정상 종료, 17910 해제
          Set-ScheduledTask -Action (Execute = C:\running-ai-github\.runtime\mcp-gateway.cmd)
10:58:10  Start-ScheduledTask → Gateway UP (PID 48448), 17910 LISTEN
```

- **Gateway 중단 시간: 약 22초**
- 실행 파일 확인: `node.exe "C:\running-ai-github\.runtime\mcp-gateway\server.js"`
- **개발 worktree 참조 잔존 0건** (`orca.workspaces` / `main-3` grep 결과 없음)
- 로그 정상 기록: `GATEWAY_STARTED port=17910 … scopes=runningai:read` (토큰 미출력)
- Task 상태 `Running`, 계정/트리거/설정 변경 없음

### 7.3 회귀 검증

| 검사 | 결과 |
| --- | --- |
| 무토큰 | 401 + 전환 전과 동일한 challenge 헤더 |
| 위조 토큰 | 401 |
| protected-resource metadata | 전환 전과 **바이트 동일** |
| Train/Publish 도구 노출 | 없음 (정책 라인 Read 7종) |
| 권한 부족(403) 정책 | 스위트 46/46 에서 read 토큰의 train/publish 호출 → 403 insufficient_scope, 백엔드 미도달 |
| OAuth Worker | **변경 없음** (코드·설정·scope 전부 미접촉) |
| Gateway 오류 로그 | 없음 |

**전송 경로 실증**: cloudflared(PID 8400, 재시작하지 않음)가 `--url http://127.0.0.1:17910` 로
터널링 중이며, 터널 호스트로 직접 호출한 결과 `realm="RunningAI"` 챌린지가 돌아왔다
→ **외부 → cloudflared → 신규 Gateway 프로세스 경로가 살아있음을 live 확인**.
(Tailscale funnel 은 `/` → 17845 Relay 전용으로, 이번 변경과 무관하며 그대로다.)

**STEP 7 판정: PASS.**

---

## 8. STEP 8 — 운영 최종 검증

### 서비스

```
Spring 18080   UP (PID 3576)
Gateway 17910  UP (PID 48448)
Relay 17845    UP (PID 45576)
PostgreSQL     127.0.0.1:5432 healthy (Up 16h)
Flyway         V26 유지
Watchdog       overall=UP, lastAction=NONE (재시작 발생 0)
```

### 데이터 — 배포 전/후 완전 일치

```
flyway_count 26 | activity 48 | injury_report 0 | workout_draft 19
workout_draft_approval 3 | workout_draft_publication 3 | activity_analysis 48
activity_sample 75206 | activity_lap 239 | intervals_fitness_daily 99 | garmin_recovery_daily 21
```

→ **운영 DB 변경 0건.** 11개 테이블 전부 기준선과 동일.

개발 DB `running_ai_7f2` 도 무변경: publications 4 / injury_report 3 / activity 48,
draft 21 → `PUBLISHED / CREATED / 144442046 / verified=t` 그대로. 개발 Spring 18083은 계속 중지.

### 외부 연동

읽기 전용 preflight (`scripts/dev/preflight-intervals-date.ps1 -Date 2026-10-16`, 쓰기 경로 없음):

```
WORKOUT events on 2026-10-16 : 1  (owned=1 foreign=0)
OWNED(marker) id=144442046 type=Run name='RunningAI workout'
              external_id='runningai:workout:v1:0:2026-10-16'
no write was performed by this script.
```

- 이벤트 **144442046 그대로 유지**, 2026-10-16 추가 게시 **없음**
- 신규 외부 쓰기 **0건**, Garmin 직접 쓰기 없음
- Relay 정상, Watchface 아티팩트/프로세스 정상

### 운영 안정성

- Gateway 실행 파일이 개발 worktree에 의존하지 않음 ✔
- Spring 릴리스 provenance(sidecar + 실행 해시) 확인 ✔
- JAR 백업·롤백 자산 2중 확보 ✔
- Gateway 이전 실행 경로 복원 가능(Task XML + 런처 백업 + 원본 파일 잔존) ✔
- Watchdog 자동 재시작 이상 없음 ✔

**STEP 8 판정: PASS.**

---

## 9. 완료 보고

### A. Git

- canonical main 최종 HEAD: `e76dc6bc687e0ff0713644d39da3560c54e8b708`
- main-3 HEAD: 동일 (`e76dc6b…`), 변경하지 않음
- FF-only 통합: **예** (merge commit 없음)
- origin push: **없음**
- 작업트리: canonical clean / main-3 는 본 Phase 문서 2종만 untracked

### B. Spring

| 항목 | 값 |
| --- | --- |
| 기존 JAR | `.runtime\running-ai-server.jar`, `760a2ee706f78c1f5f08a1d4b61bec6fdd85b59085697acbe9dd226a67d35ef5` |
| 백업 | `.runtime\backup\running-ai-server-PRE-7F3B2A-799e3b5.jar` — 해시 일치, 부트 JAR 구조·V26 검증 완료 |
| 2차 백업 | `.runtime\released\running-ai-server-20261014-105352-760a2ee7.jar` |
| 신규 JAR | `a5a0d8a2a6beb1a3e6b3b08b5a95bf6bc00d0134567e047ab68ef0922575efae` |
| sidecar | `gitCommit=e76dc6b…`, `gitBranch=main`, `gitDirty=false`, `maxMigration=26` |
| 재시작 | 성공, PID 41436 → 3576 |
| 서비스 중단 | **약 30초** |
| Read 7종 | 등록 + 실데이터 응답 모두 정상 |

### C. Gateway

| 항목 | 값 |
| --- | --- |
| 기존 실행 경로 | `main-3\.runtime\mcp-gateway.cmd` → `main-3\tools\mcp-gateway\server.js` |
| 신규 실행 경로 | `C:\running-ai-github\.runtime\mcp-gateway.cmd` → `.runtime\mcp-gateway\server.js` |
| npm ci | 성공, jose 6.2.12, 취약점 0 |
| 자체 테스트 | 46/46 통과 |
| 17911 검증 | 통과 — 17910과 동작 동일, Read 7종만 노출 |
| 17910 전환 | 성공, PID 48448, 중단 약 22초 |
| 기존 OAuth 연결 | 설정·메타데이터 불변, 재승인 불필요 |
| 롤백 경로 | Task XML + 런처 백업 + 원본 경로 잔존 — 검증 완료 |

### D. 보안

- Train MCP: **비활성** (`RUNNINGAI_MCP_TRAIN_ENABLED=false`, 도구 미등록, 호출 시 `Tool not found`)
- Publish: **비활성** (draft/legacy 모두 false, `publish_workout` 은 게이트웨이에서 영구 차단)
- `RUNNING_AI_ALLOWED_TOOLS`: 설정하지 않음 → 기본 Read 7종
- OAuth Worker: **변경 없음**
- Injury History `notesExcerpt`: **0자** 설정 (바인딩·구현 경로 검증, 9장 한계 참조)
- 무토큰 401 / 위조 토큰 401 / 권한 부족 403: 전부 기대대로

### E. 데이터 및 외부 영향

- 운영 DB 변경 건수: **0**
- 활동 48건 등 11개 테이블 전부 배포 전과 동일
- 운영 Injury Report: **0건** (INSERT 없음)
- Intervals 신규 쓰기: **0회**
- 이벤트 144442046: **유지** (읽기 전용 확인)
- Watchface / Relay: 정상

### F. 최종 판정

**PASS** — 13장 완료 기준 12개 항목 모두 충족.

---

## 10. 한계 및 잔여 위험

1. **ChatGPT 실토큰 Read 호출은 미수행.** Cloudflare Worker가 토큰을 **자체 검증 후** 전달하므로,
   Worker가 발급한 진짜 토큰 없이는 공개 경로로 게이트웨이에 도달할 수 없다(무효 토큰은 Worker가
   `realm="OAuth"` 로 선차단). Worker 수정·재승인 요구는 금지 사항이라 수행하지 않았다.
   대신 ① cloudflared 터널을 통한 신규 게이트웨이 live 응답, ② 전환 전후 메타데이터/챌린지 바이트 동일,
   ③ 동일 git blob·동일 환경변수, ④ 자체 스위트 46/46 로 대체 검증했다.
   → **사용자가 ChatGPT에서 Read 도구 1회만 호출해 주면 최종 확인이 끝난다.**
2. **notesExcerpt=0 의 운영 실측 불가.** 운영 Injury Report가 0건이라 excerpt 경로 자체가 도달
   불가능하다. Report INSERT는 금지 사항. 설정 바인딩과 `takeIf { it > 0 }` 구현으로 검증을 대체했다.
3. **Garmin profile-sync 스케줄러는 계속 ON** (cron 04:30 KST). 배포 이전부터의 기존 설정이며
   이번 범위에서 변경하지 않았다. 내일 04:30 에 athlete 프로필이 갱신될 수 있다 — 정상 동작.
4. 본 Phase 문서 2종은 아직 커밋하지 않았다(커밋 지시 없음).

## 11. 다음 단계 (자동 진행하지 않음)

Phase 7F-3B-2B 에서 별도 승인 후에만:

- 운영 Injury Report 등록
- `RUNNINGAI_MCP_TRAIN_ENABLED` 활성화 및 `RUNNING_AI_ALLOWED_TOOLS` 확장
- Cloudflare Worker OAuth scope 확대 (`runningai:train` / `runningai:publish`)

장기 아키텍처는 Modular Monolith + Single-Tenant Self-hosted 유지.
