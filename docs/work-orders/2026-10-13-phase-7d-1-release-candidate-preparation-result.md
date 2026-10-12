# WO-RUNNINGAI-PHASE-7D-1 — 운영 반영 전 안정화 및 Release Candidate 준비 (결과 보고)

지시서: `2026-10-13-phase-7d-1-release-candidate-preparation-instruction.md`
수행일: 2026-10-13 (KST) · 브랜치 `main-3` · 수행 PC: Main PC
선행 Phase: 7D-0 (CONDITIONAL GO, `docs/work-orders/2026-10-13-phase-7d-0-system-integration-readiness-result.md`)

---

## 0. 결론 요약

| 항목 | 값 |
|---|---|
| **운영 배포용 Git SHA (RC)** | **`22088724a2bc6d4dd5be13272b597f2ea72b8955`** (`2208872`) |
| RC JAR SHA-256 | §5-2 |
| 최종 판정 | **GO (조건부 승인 항목 A~F는 미실행, 승인 대기)** |
| 이번 Phase의 커밋 | `ce93a46`(문서 보존) · `2208872`(.env.example 완성) · 최종 문서 커밋 1건 |
| 운영 변경 | **없음** — 배포·병합·push·운영 DB/스케줄러/방화벽/Funnel 변경 0건 |

7D-0이 남긴 선행조건 C1~C4 중 **C3(MCP 읽기 OFF)과 C4(레거시 writer Disabled)는 이번 Phase에서 재검증 완료**,
**C1(배포 직후 부상 보고)과 C2(FF 병합→재빌드→jar 검증→재기동)는 절차로 확정**되어 STEP 6 승인 체크리스트에 편입했다.

---

## STEP 1 — 감사 결과 보존

### 1-1. 미추적 파일 8건 검토 결과 — 전부 보존 필요, 전부 커밋

7D-0이 보고한 6건에 더해, 7D-0 자신의 문서 2건이 미추적 상태였다(총 8건).

| 파일 | 크기 | 필요 여부 | 판단 근거 |
|---|---:|---|---|
| `2026-10-10-phase-6i-1-6c-spring-port-config-consistency-instruction.md` | 4,574 B | **필요** | 운영 Spring 포트(18080)와 운영자 스크립트의 포트 해석을 일치시킨 수정의 지시서. 코드(`38ae998`)는 이미 커밋·푸시됨 |
| `…-6c-spring-port-config-consistency-result.md` | 9,096 B | **필요** | 위 수정의 결과보고서. `Resolve-RunningAiSpringPort` 도입 경위 — Watchdog이 왜 8080이 아닌 18080을 보는지의 유일한 기록 |
| `2026-10-10-phase-6i-1-6c-3-spring-port-propagation-instruction.md` | 5,710 B | **필요** | 운영자 API 클라이언트 BaseUrl 전파 |
| `…-6c-3-spring-port-propagation-result.md` | 6,889 B | **필요** | 코드(`f6a2135`) 이미 커밋·푸시됨 |
| `2026-10-12-phase-7b-1-…-draft-pilot-instruction.md` | 4,090 B | **필요** | 7B-2(부상 안전 게이트)가 탄생한 백로그의 출처 |
| `…-7b-1-…-draft-pilot-result.md` | 16,167 B | **필요** | "사람이 통증 피드백을 깜빡하면 아무 게이트 없이 러닝 Draft가 생성된다"는 지적 = 7B-2 구현 근거. 현재 배포 대상 기능의 정당화 문서 |
| `2026-10-13-phase-7d-0-…-instruction.md` | 6,239 B | **필요** | 7D-0 지시서 원문 |
| `2026-10-13-phase-7d-0-…-result.md` | 90,191 B | **필요** | 이번 배포 판단의 전체 근거 |

**버릴 파일 없음.** 6건은 코드가 이미 운영에 반영된 변경의 문서인데 문서만 누락된 상태였고(코드 커밋 `38ae998`·`f6a2135`가 `origin/main`에 존재함을 확인), 2건은 배포 판단 근거다.

### 1-2. 비밀정보 및 테스트 산출물 혼입 점검 — **혼입 0건**

| 검사 | 결과 |
|---|---|
| 자격증명 패턴 (`api_key`/`password`/`secret`/`token`/`bearer` + 10자 이상 값) | **0건** |
| GPS 좌표 패턴 (소수점 4자리 이상 위경도) | **0건** |
| Garmin 활동 ID / `externalId` | **0건** |
| 장문 base64 / raw 페이로드 덤프 | **0건** |
| 빌드 산출물·테스트 산출물 | **0건** (`server/build`, `server/.gradle`는 `.gitignore` 적용됨) |

**Tailscale Funnel 호스트명 판단**: 7D-0 결과보고서에 Funnel 호스트명이 2회 등장한다. 검토 결과:

- 이 저장소(`simjjy89/running-ai`)는 **PUBLIC**이며, 동일 호스트명은 이미 `origin/main`의 **문서 5건**에 들어 있다(= 이미 공개 상태).
- Funnel 호스트명은 공개 TLS 인증서를 받으므로 **Certificate Transparency 로그에 어차피 공개**된다. 문서에서 지우는 것의 보안 효과는 사실상 0이다.
- 실질 방어선은 호스트명의 비공개가 아니라 릴레이의 Bearer 토큰 + 분당 20회 레이트리밋 + 루프백 바인딩이며, 이는 §2-2에서 외부 실측으로 확인했다.

→ **문서 일관성을 유지(미삭제)하고, 공개 저장소 노출 자체를 RISK-L5로 기록**한다. 저장소 공개 범위 변경은 소유자 결정 사항이다.

### 1-3. 커밋 — `ce93a46`

```
docs: preserve the Phase 6I-1.6C/6C-3, 7B-1 and 7D-0 work orders
 8 files changed, 2454 insertions(+)
```
전부 `docs/work-orders/` 아래 문서. **소스·빌드·설정 변경 0.**

### 1-4. Git 정합성 및 FF-only 재확인 — **가능**

```
git merge-base --is-ancestor main main-3   → true
git rev-list --count main..main-3          → 13 (문서 커밋 후), 최종 15
git log --oneline main-3..main             → (비어 있음)
git status --short                         → clean
```

`main`(= `origin/main`) `370ee8f`는 변함없고, `main-3`는 여전히 **엄격한 후손**이다.
**origin push 0건 · Canonical main 병합 0건** (지시대로).

### 1-5. 부수 발견 — `.gitignore` 공백

`server/.kotlin/`(Kotlin 컴파일러 세션 디렉터리)이 `.gitignore`에 없어 빌드 후 `git status`에 미추적으로 나타난다.
`server/build/`·`server/.gradle/`는 이미 제외되어 있으므로 명백한 누락이다 → 최종 문서 커밋에 함께 수정(빌드 산출물 제외 규칙만 변경, RC JAR에 영향 없음).

---

## STEP 2 — 운영 보안 점검

**방화벽·포트·pg_hba·Funnel 설정을 일절 변경하지 않았다.** 전부 읽기 전용 확인 + 외부로부터의 읽기 전용 프로브다.

### 2-1. PostgreSQL — **RISK-H3 (7D-0의 RISK-M1을 상향)**

#### 수집한 증거

| 항목 | 실측 |
|---|---|
| Docker 포트 바인딩 | `5432/tcp -> 0.0.0.0:5432`, `[::]:5432` · `HostConfig.PortBindings` = `{"5432/tcp":[{"HostIp":"","HostPort":"5432"}]}` (HostIp 미지정) |
| 실제 LISTEN 소켓 | `::` 및 `::1` (모든 인터페이스) |
| Windows 방화벽 프로파일 | Domain/Private/Public **전부 Enabled**, DefaultInboundAction = NotConfigured(= OS 기본 Block) |
| 활성 네트워크 분류 | `이더넷 2` = **Private**(인터넷 연결), `Tailscale` = Private |
| **방화벽 인바운드 허용 규칙** | `Docker Desktop Backend` → Program `com.docker.backend.exe`, **Protocol TCP, LocalPort Any, Profile Private+Public, Action Allow, Enabled True** |
| TCP 연결 실측 (읽기 전용 connect) | `192.168.0.24:5432` **연결 수락** · `100.108.54.79:5432`(tailnet) **연결 수락** · `172.22.80.1:5432`(WSL) 수락 · 169.254.* 전부 거부 |
| `pg_hba.conf` | `local/127.0.0.1/::1 → trust` (컨테이너 내부 전용) + **`host all all all scram-sha-256`** |
| 인증·암호화 | `password_encryption = scram-sha-256` · **`ssl = off`** · `log_connections = off` |
| 로그인 롤 | `running_ai` — **rolsuper = t**, rolcreatedb = t (유일한 로그인 롤) |

#### 왜 상향했는가 (7D-0 Medium → High)

7D-0 시점에는 "0.0.0.0 바인딩" 사실만 알았다. 이번에 추가로 확인된 것:

1. **방화벽이 막지 않는다.** `com.docker.backend.exe`에 대해 **TCP/LocalPort=Any**를 Private·Public **양쪽** 프로파일에서 허용하는 인바운드 규칙이 존재한다. 게시된 컨테이너 포트는 전부 이 규칙을 탄다.
2. **tailnet 전체에서 도달 가능하다.** LAN뿐 아니라 `100.108.54.79:5432`가 연결을 수락한다. 즉 사용자의 **모든 Tailscale 기기**가 DB에 직접 TCP 도달한다(Funnel은 5432를 노출하지 않으므로 인터넷 직노출은 아니다).
3. **전송이 평문이다.** `ssl = off` — SCRAM이 비밀번호 자체는 가리지만, **쿼리와 결과(심박·수면·HRV·GPS 파생 데이터)는 네트워크에 평문으로 흐른다.**
4. **앱 롤이 superuser다.** `running_ai`가 `rolsuper`라 자격증명 1개가 뚫리면 DB 전체 통제 + `COPY ... PROGRAM`을 통한 컨테이너 내 명령 실행까지 이어진다.
5. **접속 감사 기록이 없다.** `log_connections = off`라 **"지금까지 외부 접속이 없었다"를 증명할 수 없다.** 이번 감사의 명시적 한계다.

유일하게 성립하는 방어선은 `host all all all scram-sha-256`의 **비밀번호 하나**다.

> 주의: 위 TCP 연결 실측은 같은 호스트에서 출발한 연결이라 Windows 방화벽을 우회했을 가능성이 있다. 다만 (1)의 허용 규칙이 존재하므로 외부 호스트에서도 통과하는 것이 자연스러운 귀결이다. 다른 기기에서의 확정 검증은 소유자가 `psql -h 192.168.0.24 -p 5432 -U running_ai` 1회로 할 수 있다.

#### localhost 제한 적용 가능성 — **의존 서비스 영향 0건**

5432 소비자를 전수 조사했다.

| 소비자 | 접속 방식 | 127.0.0.1 제한 시 영향 |
|---|---|---|
| Spring (운영) | `.env`에 `DB_URL` 없음 → `application-local.yml` 기본값 `jdbc:postgresql://localhost:5432/running_ai` | **없음** |
| Gradle 테스트 | localhost | **없음** |
| docker-compose healthcheck | 컨테이너 내부 `pg_isready` | **없음** (호스트 포트 미사용) |
| Watchdog Postgres 프로브 | `docker compose ps -q` + `docker inspect` (Docker API) | **없음** (5432 미사용) |
| 저장소 전체 | 비루프백 DB 호스트 참조 **0건**, `192.168.*`/`100.*` 참조 **0건** | **없음** |

→ `docker-compose.yml`의 `"${POSTGRES_PORT:-5432}:5432"` 를 **`"127.0.0.1:${POSTGRES_PORT:-5432}:5432"`** 로 바꾸면 알려진 모든 소비자가 그대로 동작한다.
비용은 **컨테이너 재생성 1회**(`docker compose up -d`)이며, 명명 볼륨 `running_ai_postgres_data`는 유지되므로 **데이터 손실 없음**. Spring 중지 → 컨테이너 재생성 → Spring 기동 순서가 필요하다.

**이번 Phase에서는 변경하지 않았다**(지시: 실제 방화벽·포트 설정 변경 금지). 배포 승인 항목에 별도 G로 제안한다(§6).

### 2-2. MCP — **전 항목 PASS**

| 점검 | 결과 | 근거 |
|---|---|---|
| `RUNNINGAI_MCP_READ_ENABLED=false` 유지 | ✅ | 운영 `.env`에 키 자체가 부재 → 소스 기본값 false. 이번 Phase에서 추가하지 않음 |
| 쓰기 MCP 비활성 유지 | ✅ | `RUNNINGAI_MCP_ENABLED=false` |
| **raw Spring `/mcp` 외부 노출 차단** | ✅ **외부 실측** | 아래 Funnel 프로브 |
| Gateway 미지원 메서드 / 5xx 처리 | ✅ **29/29** | 아래 |
| OAuth 미설정 상태 개인정보 공개 | ✅ 공개 0건 | 아래 |

#### 공개 Funnel 표면 프로브 (외부 HTTPS, 읽기 전용)

```
GET  /health                              -> 200  {"status":"ok"}        (릴레이, 설계상 무인증·데이터 없음)
GET  /mcp                                 -> 404
POST /mcp                                 -> 404
GET  /api/v1/activities                   -> 404
GET  /api/v1/athlete/injury-state         -> 404
GET  /actuator/health                     -> 404
GET  /today-workout                       -> 401   (Bearer 게이트 동작)
GET  /.well-known/oauth-protected-resource-> 404   (게이트웨이 미배치, 정상)
```

**인터넷에서 Spring에 닿는 경로는 존재하지 않는다.** Funnel은 `/ → 127.0.0.1:17845`(릴레이) 한 줄뿐이고 릴레이는 Spring에 연결되어 있지 않다. Spring은 `SERVER_ADDRESS=127.0.0.1`로 루프백에만 바인딩한다.

#### 게이트웨이 재검증 (RC SHA에서 실행, 29/29 PASS)

```
PASS unauthenticated server/discover -> 401 with resource_metadata (OAuth trigger)
PASS authenticated   server/discover -> HTTP 200 JSON-RPC -32601, never 5xx, not proxied
PASS non-allowlisted MCP method      -> -32601, not proxied
PASS authorized but forced publish_workout -> -32602, not proxied
PASS JWKS/AS unreachable             -> 503, never a pass
PASS publish_workout on the allowlist refuses to start
PASS arbitrary path -> 404 / GET /mcp -> 405 / backend hits = 0
```

RISK-H1(raw `/mcp`의 HTTP 500 + 스택트레이스)은 **완화 수단이 게이트웨이에만 있다**는 사실이 그대로다. 따라서 C3(읽기 스위치 OFF 유지)이 배포 구성에 고정된다.

---

## STEP 3 — Windows Watchdog 콘솔 문제

**운영 `RunningAI-Watchdog` / `RunningAI-Startup`은 조회만 했고 변경·중지·재등록하지 않았다.** 검증은 고유 이름의 **임시 테스트 작업**(`RunningAI-7D1-TEST-*`, 트리거 없음·온디맨드 전용)을 등록→실행→**즉시 삭제**하는 방식으로 격리 수행했고, 종료 시 잔존 0건을 확인했다.

### 3-1. 측정 방법

1. **관찰자**(`probe-windows.ps1`): 15 ms 주기로 시스템 전체의 **보이는** 최상위 콘솔 호스트 창(`ConsoleWindowClass` / `CASCADIA_HOSTING_WINDOW_CLASS` / `PseudoConsoleWindow`)을 열거. 1초 워밍업으로 **기존 창**(이 세션의 터미널, 방치된 Garmin 시뮬레이터)을 목록화해 제외하고, 그 이후 **새로 나타난 창만** 집계. 창을 만들거나 닫거나 포커스하지 않는 순수 관찰.
2. **부하**(`tick-payload.ps1`): 실제 틱의 **프로세스 생성 형태를 그대로 재현** — `Get-DefaultProbes`가 내는 `cmd /c docker info` 1회 + `cmd /c docker compose ps` 1회 + `cmd /c docker inspect` 1회, 그리고 인프로세스 HTTP 헬스 프로브. RunningAI 런타임 상태는 읽기만 한다.
3. 각 변형을 운영 Watchdog과 **동일한 Principal**(해당 사용자, `LogonType=Interactive`, `RunLevel=Limited`)로 등록해 실행.

### 3-2. 측정 결과

| 변형 | 작업 액션 | **새로 나타난 콘솔 창** | 틱 동작 | `LastTaskResult` 충실도 |
|---|---|:--:|---|---|
| **A (현행 운영)** | `powershell.exe -WindowStyle Hidden -File …` | **2** — `WindowsTerminal`/`CASCADIA_HOSTING_WINDOW_CLASS` + `powershell`/`PseudoConsoleWindow` | 정상 | ✅ 21 (정확) |
| **B** | `conhost.exe --headless powershell.exe … -File …` | **0** | 정상 | ❌ **항상 0** |
| **C** | `cmd.exe /c start "" /b powershell.exe …` | **2** (`WindowsTerminal` + `cmd`/PseudoConsole) | 정상 | — |
| **D** | `conhost --headless cmd /c "powershell … & exit /b %ERRORLEVEL%"` | **0** | — | ❌ 0 |
| **E** | `conhost --headless powershell -Command "& script; exit $LASTEXITCODE"` | **0** | — | ❌ 0 |

세 변형의 틱 로그가 **완전히 동일**했다 — 기능 손실 없음:

```
A: docker info exit=0 / compose ps -> 64 chars / inspect -> true/healthy / spring health HTTP 200
B: docker info exit=0 / compose ps -> 64 chars / inspect -> true/healthy / spring health HTTP 200
C: docker info exit=0 / compose ps -> 64 chars / inspect -> true/healthy / spring health HTTP 200
```

`GetConsoleWindow()` 비교: A·C는 `PseudoConsoleWindow` 핸들 존재, **B는 핸들 0 = 콘솔 자체가 없음**(진짜 headless).

### 3-3. 원인 확정 — 그리고 7D-0 분석의 부분 정정

**확정된 원인**: Task Scheduler가 **콘솔 서브시스템 앱인 `powershell.exe`** 를 Interactive 토큰으로 띄우는 순간 Windows가 콘솔 호스트를 할당한다. 이 PC의 기본 터미널은 **Windows Terminal**이므로 `CASCADIA_HOSTING_WINDOW_CLASS` 창이 실제로 뜨고, `-WindowStyle Hidden`은 PowerShell이 **기동한 뒤** 적용되므로 그 전에 창이 보인다. 반복 주기 PT5M이 곧 창의 주기다.

**7D-0에서 "틱마다 `cmd /c` 자식 3개가 창을 추가한다"고 본 부분은 측정 결과 사실이 아니다.** 자식 `cmd.exe`는 부모의 (숨겨진) 콘솔을 **상속**하므로 창을 만들지 않는다 — A 변형이 `cmd /c` 3회를 포함하고도 창이 2개(호스트 1쌍)에 그친 것이 그 증거다. 원인은 **호스트 기동 1회**이며, 레거시 작업(`wscript.exe` + `shell.Run cmd,0,False`)이 창을 만들지 않는다는 7D-0의 결론은 그대로 유효하다.

### 3-4. 대안별 영향 평가

| 방안 | 콘솔 창 | 자동 복구 권한 | 환경변수 | 로그 | 채택 |
|---|:--:|---|---|---|---|
| **B. `conhost --headless`** | **0** | **불변** (동일 사용자·Interactive 토큰·동일 세션 → Docker Desktop 접근 유지) | **불변** (틱 로그에서 docker/health 전부 동일 동작 확인) | **불변** (`watchdog.log`·`watchdog-status.json`·`watchdog-state.json`은 파일 I/O) | **권고** (보완 1건 필요) |
| C. `cmd /c start /b` | 2 | 불변 | 불변 | 불변 | ❌ 무효 |
| **Principal을 S4U/"로그온 여부 무관"으로** | 0 | **상실** — 세션 0에서 실행되면 Docker Desktop(사용자 세션 의존)에 접근 못 해 Docker/Postgres 프로브가 깨진다. Phase 4가 AtStartup/SYSTEM 구조를 포기한 바로 그 이유 | — | — | ❌ **금지 조건 위반**("자동 복구 기능을 잃는 해결책은 채택하지 않는다") |
| VBS 래퍼 (`wscript` + `WScript.Quit`) | 0 | 불변 | 불변 | 불변 | ❌ Phase 4 지시서가 "VBS wrapper 도입 금지"로 명시 |
| 기본 터미널을 Console Host로 변경 | **미측정** | 불변 | 불변 | 불변 | ⏸ 사용자 전역 설정 변경이라 이번 범위 밖 |

### 3-5. B의 유일한 대가와 보완책

`conhost --headless`는 **자식의 종료 코드를 전달하지 않는다**(D·E로 명령 형태를 바꿔도 동일 → conhost 자체의 성질). 측정: 자식이 `exit 21`이어도 `LastTaskResult = 0`.

- **자동 복구 기능 자체는 전혀 영향받지 않는다.** 복구 판단·예산·lockout은 전부 Watchdog 내부 상태(`watchdog-state.json`)와 로그로 이뤄지며 종료 코드에 의존하지 않는다.
- 잃는 것은 **운영자 진단 표면 1개**: "Watchdog이 스스로 죽었다"(exit 20) 신호. 다만 그 경우 `watchdog-status.json`의 `checkedAt`이 갱신되지 않으므로 **status 파일의 staleness(>10분)로 동등하게 탐지**할 수 있다.
- **권고 보완(후속 Phase)**: `watchdog-status.json`에 `lastExitCode` / `lastRunOutcome`을 추가해 진단 표면을 복원. 이번 RC 범위에는 포함하지 않았다(STEP 4가 배포 대상을 7B-4/7C-2까지로 고정했고, 지시가 운영 Watchdog 변경을 금지했기 때문).

### 3-6. 적용 방법 (승인 후, 이번 Phase 미실행)

운영 작업의 **액션 한 줄**만 바뀐다. 트리거·Principal·반복주기·실행시간제한은 그대로 둔다.

```
현행: C:\Windows\System32\WindowsPowerShell\v1.0\powershell.exe
      -NoProfile -ExecutionPolicy Bypass -WindowStyle Hidden -File C:\running-ai-github\scripts\windows\watch-running-ai.ps1

변경: C:\Windows\System32\conhost.exe
      --headless "C:\Windows\System32\WindowsPowerShell\v1.0\powershell.exe" -NoProfile -ExecutionPolicy Bypass -WindowStyle Hidden -File C:\running-ai-github\scripts\windows\watch-running-ai.ps1
```

`RunningAI-Startup`(로그온 1회)에도 같은 변경을 적용하면 로그온 시 창 1회도 사라진다.
되돌리기는 액션 문자열을 원복하는 것뿐이며, 설치 스크립트(`install-running-ai-watchdog-task.ps1`)를 함께 고쳐야 재설치 시 일관성이 유지된다 → 후속 Phase 과제.

### 3-7. 정리 확인

```
RunningAI-7D1-TEST-* 잔존 작업: 0
RunningAI-Watchdog : State=Ready, LogonType=Interactive, 액션 문자열 변경 없음, LastRun 07:34:16 LastResult=0
watchdog-status.json: checkedAt 2026-10-12T22:34:17Z, overall UP, lastAction NONE, recoveryBusy False
```

---

## STEP 4 — 운영 배포 구성 고정

### 4-1. 배포 대상 기능 확정 (Phase 7B-4 / 7C-2까지)

| 기능 | Phase | 마이그레이션 | 배포 후 상태 |
|---|---|---|---|
| 부상 안전 게이트 | 7B-2 | V21 | **활성** (코드에 상시 적용, 스위치 없음) |
| Garmin 러닝 다이내믹스 | 7B-3 | V22 | **활성** (재분석 시 채워짐) |
| LTHR provenance 원장 | 7B-4 | V23 | **활성** (프로필 쓰기 시 자동 기록) |
| 읽기 전용 MCP 도구 7종 | 7C-2a | — | **비활성** (`RUNNINGAI_MCP_READ_ENABLED=false`) |
| OAuth MCP 게이트웨이 | 7C-2b | — | **미배치** (저장소에만 존재) |

### 4-2. 운영 설정 고정표 (실측)

| 항목 | 값 | 출처 | 배포 시 |
|---|---|---|---|
| `SERVER_ADDRESS` | `127.0.0.1` | `.env` | **유지** (보안 통제) |
| `SERVER_PORT` | `18080` | `.env` | 유지 |
| `RUNNING_AI_TRAINING_CONTEXT_VERSION` | `V2` | `.env` | 유지 — 7B-3 다이내믹스가 **배포 즉시** 코치 컨텍스트에 반영됨 |
| `WORKOUT_PUBLISHING_ENABLED` | `false` | `.env` | **유지** |
| `WORKOUT_PUBLISHING_SCHEDULER_ENABLED` | `false` | `.env` | **유지** |
| `RUNNING_AI_DRAFT_PUBLISHING_ENABLED` | `false` | `.env` | **유지** |
| `RUNNINGAI_MCP_ENABLED` | `false` | `.env` | **유지** |
| `RUNNINGAI_MCP_READ_ENABLED` | (부재 → false) | 기본값 | **유지 — 추가 금지 (C3)** |
| `GARMIN_PROFILE_SYNC_ENABLED` | `true`, cron `0 30 4 * * *` | `.env` | 유지 — 배포 후 매일 V23 원장에 1행 추가(동일값이면 `applied=false`) |
| Garmin 활동 sync 스케줄러 | (부재 → false) | 기본값 | 유지 |
| `RUNNING_AI_COACH_REFRESH_SCHEDULER_ENABLED` | (부재 → false) | 기본값 | 유지 → 코치 호출 전 수동 `data-refresh` 필요 (RISK-M2) |
| `RUNNING_AI_INJURY_SAFETY_STALENESS_HOURS` | (부재 → 48) | 기본값 | 유지 |
| `RUNNING_AI_EXTERNAL_BASE_URL` | Funnel 호스트 | `.env` | 유지 |

**레거시 Writer 4종 — 전부 Disabled 재확인**

```
RunningAI-CommandChannel        Disabled
RunningAI-RemoteWakeupScheduler Disabled
RunningAI-TodayWorkout          Disabled
RunningAI-TrainingCommand       Disabled
```

**저장소 관리 작업**: `RunningAI-Startup` Ready · `RunningAI-Watchdog` Ready (변경 없음)

**외부 Relay / Watchface 연동 유지**

```
relay 127.0.0.1:17845 /health -> {"status":"ok"} (PID 13588, 2026-10-12 00:45 기동)
tailscale serve status        -> "/ proxy http://127.0.0.1:17845"   (/mcp 미추가)
```

### 4-3. `.env.example` 보완 — 커밋 `2208872` (= **RC SHA**)

`application.yml`이 읽는 환경변수는 **28개**인데 `.env.example`은 **9개**만 문서화하고 있었다. 보완 후 **28/28 전부 존재**(기계 검증), 각 항목에 소스 기본값과 그 기본값인 이유를 함께 기재.

특히 보안상 중요한 누락 3건을 채웠다:

- `RUNNINGAI_MCP_READ_ENABLED` — 훈련·회복·분석 데이터를 `/mcp`로 내보내는 스위치
- `SERVER_ADDRESS` — 무인증 REST API를 LAN에서 막아주는 **유일한** 통제
- `RUNNING_AI_INJURY_SAFETY_STALENESS_HOURS` — 부상 상태 신뢰 유효기간

추가로 **`GARMIN_USERNAME` / `GARMIN_PASSWORD`를 삭제**했다. 저장소 전체에서 **아무것도 읽지 않는** 변수인데(인증은 `python -m garmin_connector login` 1회, 토큰은 라이브러리가 저장소 밖에 보관), 빈 자격증명 칸을 남겨두면 **아무 이득 없이 실제 Garmin 자격증명을 파일에 적어 넣도록 유도**한다. "Spring은 Garmin 자격증명을 보유하지 않는다"는 불변식과도 어긋난다.

Windows 스크립트·릴레이·게이트웨이가 읽는 변수(`SERVER_PORT`, `RUNNING_AI_EXTERNAL_BASE_URL`, `INTERVALS_ICU_API_KEY`, 게이트웨이 OAuth 설정)와 테스트 전용 탈출구도 포함해, 이 파일이 주장하는 "단일 인벤토리"가 실제로 성립하게 했다.

검증:

| 검사 | 결과 |
|---|---|
| `application.yml`의 28개 변수가 전부 존재하는가 | ✅ 28/28 |
| 아무도 읽지 않는 변수가 들어갔는가(날조 검사) | ✅ 0건 |
| 실제 자격증명/토큰이 들어갔는가 | ✅ 0건 (전부 플레이스홀더·기본값) |
| `Test-RunningAI.ps1` (비밀 스캔 포함) | ✅ **90 PASS / 0 FAIL** |
| 런타임 영향 | **없음** — 서버는 `.env`만 읽고 `.env.example`은 읽지 않는다 |

---

## STEP 5 — Release Candidate 검증

검증 환경(전부 격리, 운영 무접촉):

| 요소 | 내용 |
|---|---|
| 코드 | `main-3` @ **`2208872`** (= RC SHA), 워킹트리 clean |
| 빌드 | `gradlew clean build` (compile + 전체 테스트 + bootJar) 1회 |
| DB | 운영 **실데이터 사본** 2개 — `running_ai_rc`(마이그레이션 대상) / `running_ai_restore`(백업 복원 대상), 둘 다 `pg_dump -Fc` → `pg_restore` |
| 포트 | 18095(RC) · 18097(구버전 jar) · 18098(복원 스택) — 운영 18080과 분리 |
| 외부 | Garmin/Intervals base-url = `http://127.0.0.1:9` (죽은 포트) |

### 5-1. 대상 Git SHA 확정

```
22088724a2bc6d4dd5be13272b597f2ea72b8955   (short 2208872)
docs(config): complete .env.example and stop inviting Garmin credentials
```

- `main`(`370ee8f`)으로부터 **14 커밋** 앞섬, FF-only 병합 가능.
- 이 SHA 이후의 커밋은 **문서와 `.gitignore`뿐**이며 JAR 내용에 영향을 주지 않는다(§5-10).

### 5-2. clean build + JAR 검증

```
BUILD SUCCESSFUL in 6m 30s
11 actionable tasks: 11 executed
```

| 항목 | 값 |
|---|---|
| 경로 | `server/build/libs/running-ai-server-0.0.1-SNAPSHOT.jar` |
| 크기 | 75,397,694 B |
| **SHA-256** | **`ACEDE7D6B8FE658DBB037C85ACE82B80B58B09B79A51394EA388B2F8E9E562FA`** |
| 전체 엔트리 | 844 |
| `com/runningai` 클래스 | 563 |

### 5-3. 포함 클래스·리소스 확인

| 필수 클래스 (Phase 7) | 결과 |
|---|---|
| `injury/WorkoutSafetyGateService` · `injury/WorkoutSafetyPolicy` · `injury/InjuryStateController` | **PRESENT** |
| `analysis/RunningDynamicsCalculator` | **PRESENT** |
| `athlete/AthleteIntensityProfileHistory` · `athlete/LactateThresholdProvenance` | **PRESENT** |
| `mcp/read/ReadMcpTools` · `mcp/read/McpReadToolConfig` | **PRESENT** |
| `mcp/PublishWorkoutMcpTool` · `draftpublish/PublishingModeGuard` · `coach/TrainingContextV2Builder` | **PRESENT** |

| 필수 리소스 | 결과 |
|---|---|
| `BOOT-INF/classes/application.yml` · `application-local.yml` | **PRESENT** |
| `META-INF/spring.factories` | **PRESENT** (124 B, 내용 = `EnvironmentPostProcessor=…McpServerSwitchEnvironmentPostProcessor`) |

> `spring.factories`는 `BOOT-INF/classes/META-INF/` 가 아니라 **아카이브 루트**에 배치된다. 정적 경로만 보면 미등록으로 오판하기 쉬워, **동작으로 직접 검증**했다: 쓰기 스위치 false + 읽기 스위치 true로 기동하자 로그에 `Registered tools: 7`이 찍혔다. 포스트프로세서가 없으면 MCP 서버 자체가 꺼져야 하므로, **패키징된 jar에서 OR 로직이 실제로 동작함이 증명**된다.

| 들어 있으면 안 되는 것 | 결과 |
|---|---|
| `.env` · `secrets/` · `watch-token` · `*.pem` · `*.key` | **0건** |

### 5-4. V21 / V22 / V23 Flyway 파일 포함 — **23/23**

```
V1 … V20 (기존) +
V21__create_injury_report.sql                       PRESENT
V22__add_running_dynamics_to_activity_analysis.sql  PRESENT
V23__create_athlete_intensity_profile_history.sql   PRESENT
```

참고로 **운영 중인 구버전 jar**는 V1..V20 **20개만** 포함하며 `injury/`·`RunningDynamicsCalculator`·`AthleteIntensityProfileHistory`·`mcp/read/` 클래스가 **전혀 없다**(SHA-256 `1A7BE11B…`). 두 아티팩트가 명확히 구분됨을 확인했다.

### 5-5. 사본 DB로 개발 서버 기동

```
Current version of schema "public": 20
Migrating to "21 - create injury report"
Migrating to "22 - add running dynamics to activity analysis"
Migrating to "23 - create athlete intensity profile history"
Successfully applied 3 migrations, now at version v23 (execution time 00:00.084s)
→ /actuator/health = {"status":"UP"}
```

**재기동 멱등성**(2회차 기동):

```
Successfully validated 23 migrations (00:00.084s)
Current version of schema "public": 23
Schema "public" is up to date. No migration necessary.
```

### 5-6. 핵심 API 및 조회 도구 점검

| 엔드포인트 | 결과 |
|---|---|
| `GET /api/v1/health` · `/actuator/health` | 200 UP |
| `GET /api/v1/activities` | 200, 실데이터 8건 |
| `GET /api/v1/athlete/intensity-profile` | 200, 176/290 (불변) |
| `GET /api/v1/athlete/injury-state` | 200, `NO_INJURY_STATE_RECORDED` |
| `GET /api/v1/training-load` · `/training-state` | 200 |
| `GET /api/v1/workout-recommendation` | 200 |
| `GET /api/v1/recovery-context` | 200 |
| `GET /api/v1/coach/training-context?version=V2` | 200, 11,038 B, sha256 `7625e633…` |
| `GET /api/v1/activities/7/analysis` | 200, `COMPLETE` / `inputSampleCompleteness=FULL` |
| `GET /api/v1/intervals/fitness?oldest&newest` | 200 |
| `POST /api/v1/workout-publish` | **409 `WORKOUT_PUBLISHING_DISABLED`** |

**MCP 읽기 도구 7종** (읽기 스위치 ON 구성에서):

```
tools/list -> 7종, 전부 readOnlyHint=true
get_recent_activities       isError=False    812 B
get_activity_analysis       isError=False  1,607 B
get_training_context        isError=False 10,617 B
get_recovery_context        isError=False  1,483 B
get_fitness_trend           isError=False  2,156 B
get_workout_recommendation  isError=False    563 B
get_athlete_profile         isError=False    327 B
publish_workout             -> -32602 "Tool not found: publish_workout"
```

**운영 구성(MCP 두 스위치 모두 false)으로 재기동**:

```
'Registered tools' 로그 라인: 0
POST /mcp -> HTTP 404
GET /actuator/health -> 200
```

→ **배포 후 운영에서 `/mcp`는 존재하지 않는다**(C3 구성의 실증).

### 5-7. 기존 기능 회귀 검증

| 스위트 | 결과 |
|---|---|
| Spring (`gradlew clean build`, H2) | **1,272 / 1,272 PASS** — 로그 집계와 JUnit XML 집계 일치 (failures 0 / errors 0 / skipped 0) |
| `tools/mcp-gateway` | **29 / 29 PASS** |
| `tools/garmin-connector` (pytest) | **134 / 134 PASS** |
| `tools/external-relay` (node --test) | **14 / 14 PASS** |
| `scripts/windows/tests/Test-RunningAI.ps1` | **90 PASS / 0 FAIL** (`.env.example` 변경 후 재실행) |
| **합계** | **1,539 / 1,539 PASS** |

**실데이터 재현성**: 7B-3 다이내믹스와 7B-4 provenance가 7D-0과 **수치까지 동일**하게 재현되었다.

```
7B-3  lapsWithDynamicsCount 22 · avgStrideLengthCm 107.617… · strideLengthChangePercent -4.332…
      avgGroundContactTimeMs 246.264… (+3.272 %) · avgRunningPowerWatts 320.955 · samePace -9.0 W
      (재분석 0.68 s, Garmin 호출 0건)
7B-4  LTHR 176/290 · sourceTracked=false + "provenance unconfirmed" 경고
```

### 5-8. 배포 후 부상 상태 보고 절차 검증 (C1 리허설)

**선수의 실제 상태(무릎 통증 잔존 = ACTIVE)로만 수행했다. RESOLVED는 어디에도 사용하지 않았고, 운영 DB에는 어떤 부상 보고도 쓰지 않았다.**

| 단계 | 결과 |
|---|---|
| 1. 마이그레이션 직후 상태 (= 배포 직후 운영의 모습) | `allowed=false`, `NO_INJURY_STATE_RECORDED` |
| 2. 비REST(EASY) Draft 승인 시도 | **HTTP 422 `WORKOUT_SAFETY_NO_INJURY_STATE_RECORDED`** |
| 3. 실제 상태 보고 (KNEE/RIGHT/통증3/계단통증, status=ACTIVE) | 저장됨, `allowed=false`, `INJURY_ACTIVE` |
| 4-a. 다시 비REST 승인 시도 | **`WORKOUT_SAFETY_INJURY_ACTIVE`** (차단) |
| 4-b. REST Draft 승인 | **HTTP 200** (REST는 항상 허용) |
| 5. `get_workout_recommendation` | `injurySafety.reasonCode=INJURY_ACTIVE`, **`recommendation` 필드 자체가 없음**, `suppressedReason` 제시 |

→ **배포 직후 운영에서 수행할 절차가 그대로 동작함을 확인**했다. 보고 전에는 REST 외 아무것도 생성·승인·게시되지 않는다.

### 5-9. 장애 시 코드 롤백 및 데이터 복구 — **실증 완료**

#### (A) 구버전 코드 × V23 스키마 — **기동 성공**

운영에 실제로 떠 있는 **그 jar**(`1A7BE11B…`, V1..V20만 포함)를 V23이 적용된 사본 DB에 붙였다.

```
Successfully validated 23 migrations (execution time 00:00.045s)
Current version of schema "public": 23
Schema "public" is up to date. No migration necessary.
Hibernate SchemaManagementException / validation error : 0건
/actuator/health = UP
```

Flyway는 로컬에 없는 상위 버전(V21~V23)을 **future 마이그레이션으로 허용**하고, Hibernate `validate`는 매핑되지 않은 신규 테이블·컬럼을 무시한다. 데이터 서빙도 정상이었다.

| 구버전 jar에서의 동작 | 결과 |
|---|---|
| `/api/v1/health` · `/activities` · `/athlete/intensity-profile` · `/training-state` · `/activities/7/analysis` | **전부 200, 실데이터 정상** |
| `GET /api/v1/coach/training-context?version=V2` | 200 (sizeBytes 10,411 / sha256 `550d12bb…`) |
| `GET /api/v1/athlete/injury-state` · `/injury-reports` | **404** |
| `activity_analysis`의 V22 컬럼 | 응답에 `runningDynamics` 키 자체가 없음 (무시) |

> **롤백의 실제 의미 2가지**
> 1. **부상 안전 게이트가 사라진다.** 엔드포인트가 404가 되고 Draft 생성/승인/게시의 게이트 호출도 함께 없어진다. 롤백은 "안전 기능 제거"를 수반하므로, 롤백 후에는 게시 스위치가 전부 false인지 반드시 재확인해야 한다.
> 2. **V2 컨텍스트 스냅숏의 sha256이 달라진다**(`7625e633…` ↔ `550d12bb…`). 7B-3이 V2 스냅숏에 러닝 다이내믹스·LTHR provenance를 추가했기 때문이며, Draft마다 자기 스냅숏을 저장하므로 과거 기록은 영향받지 않는다. 정상이다.

#### (B) 백업 → 복원 → 구버전 코드 — **완전 일치**

```
pg_dump -Fc  → 2,837,204 B (운영 Spring 접속 중 무중단)
CREATE DATABASE running_ai_restore + pg_restore --no-owner
```

| DB | `athlete|activity|analysis|sample|lap|recovery|fitness|draft|raw_payload|flyway` |
|---|---|
| **운영** | `1\|8\|8\|21828\|123\|21\|99\|19\|40\|20` |
| **백업 복원본** | `1\|8\|8\|21828\|123\|21\|99\|19\|40\|20` ← **완전 동일** |
| 사본(V23 적용 후) | `1\|8\|8\|21828\|123\|21\|99\|19\|40\|23` (flyway만 +3) |

프로필 값도 타임스탬프까지 동일: `176/290@2026-10-05 19:30:01.556876+00`.
복원본에 구버전 jar를 붙여 기동 → `Successfully validated 20 migrations` / `Current version: 20` / 핵심 API 200. **백업→복원→구버전 코드 경로 전체가 동작한다.**

#### (C) Flyway 이력 수동 삭제 — 사용하지 않음

V21~V23은 전부 additive이므로 되돌릴 필요가 없다. `flyway_schema_history` 행을 지우면 이후 Flyway가 이미 존재하는 객체를 다시 만들려다 실패하고 스키마와 이력이 영구히 어긋난다. **정상 롤백은 (A) 코드만**, 데이터 손상 시에만 (B)다.

### 5-10. RC 이후 커밋이 아티팩트에 미치는 영향

RC(`2208872`) 이후 이 Phase가 추가한 커밋은 **문서 + `.gitignore`** 뿐이다.

```
git diff --stat 2208872..HEAD   →  docs/work-orders/*.md, .gitignore 만 변경
```

`.gitignore`와 `docs/`는 `bootJar` 산출물에 포함되지 않으므로, **최종 HEAD로 빌드해도 RC와 기능적으로 동일한 jar가 나온다**(바이트 동일은 아님 — Spring Boot bootJar는 기본적으로 재현 가능 빌드가 아니다). 배포 시 **최종 HEAD를 병합해도 무방**하며, 엄밀한 아티팩트 동일성이 필요하면 `2208872`를 사용한다.

### 5-11. RC 검증 중 발견한 결함 1건 (**기존 결함, 이번 RC의 회귀 아님**)

**필수 쿼리 파라미터 누락 시 HTTP 400이 아니라 500**

| 항목 | 내용 |
|---|---|
| 재현 | `GET /api/v1/intervals/fitness` (필수 `oldest`/`newest` 생략) → `500 {"code":"INTERNAL_SERVER_ERROR","message":"Unexpected server error"}` |
| 원인 | `GlobalExceptionHandler`가 `MethodArgumentTypeMismatchException`·`HttpMessageNotReadableException` 등은 처리하지만 **`MissingServletRequestParameterException`을 처리하지 않아** catch-all `Exception` 핸들러(500)로 떨어진다 |
| 영향 범위 | 필수 `@RequestParam`을 가진 엔드포인트 — `GET /api/v1/intervals/fitness`, `POST /api/v1/intervals/enrichment/fitness[/reprocess]`. 날짜 형식 오류(`?oldest=notadate`)는 정상적으로 **400**을 반환하므로 "누락"에만 해당 |
| **회귀 여부** | **아님** — 운영 중인 구버전 jar에서도 동일하게 500이 나오는 것을 확인했다. 6H-5부터 존재한 기존 동작 |
| 정보 유출 | **없음** — 응답 본문은 `"Unexpected server error"` 고정이며 스택트레이스가 나가지 않는다 |
| 심각도 | **Low** — 클라이언트 오류를 서버 오류로 오분류하고, 평범한 잘못된 요청에 서버 측 스택트레이스가 로깅된다 |
| 권고 | `GlobalExceptionHandler`의 400 핸들러에 `MissingServletRequestParameterException`을 추가(한 줄). **RC 범위 밖이므로 이번에 수정하지 않았다** — STEP 4가 배포 대상을 7B-4/7C-2로 고정했고, 수정하면 RC SHA가 바뀐다 → DEBT-16 |

---

## STEP 6 — 배포 승인 체크리스트

**A~F 중 어느 것도 이번 Phase에서 실행하지 않았다.** 각 항목은 개별 승인 대상이며, 아래는 승인 시 수행할 내용과 중단 조건이다.

### A. `main-3` → Canonical `main` 병합

| | |
|---|---|
| 명령 | `git -C C:\running-ai-github merge --ff-only main-3` |
| 사전 조건 | Canonical 워킹트리 clean · `git merge-base --is-ancestor main main-3` = true |
| 현재 상태 | **충족** (실측) |
| 영향 | Canonical `main`이 `370ee8f` → 최종 HEAD로 전진. **운영 동작은 아직 바뀌지 않는다**(jar 재빌드 전) |
| 되돌리기 | `git -C C:\running-ai-github reset --hard 370ee8f` (origin에 push하지 않았으므로 원격 영향 없음) |
| **중단 조건** | 워킹트리가 dirty하거나 FF가 거부되면 즉시 중단 |

### B. 운영 PostgreSQL 백업 및 Flyway V21~V23 적용

| | |
|---|---|
| 백업 | `docker exec running-ai-postgres pg_dump -U running_ai -d running_ai -Fc -f /tmp/pre-v23-<ts>.dump` → `docker cp`로 `.runtime\backups\`에 복사 |
| 적용 방식 | **수동 Flyway 실행 없음.** C의 Spring 재기동 시 Flyway가 자동 적용한다(실측 84 ms) |
| 예상 영향 | 추가 전용 3개(테이블 2개 신설 + nullable 컬럼 24개). 기존 데이터 변경 0 |
| **중단 조건** | 백업 파일이 없거나 2 MB 미만 · `pg_dump` 비정상 종료 · 진행 중인 Historical Backfill(RUNNING) 존재 |

### C. 운영 Spring 재빌드·재기동

| | |
|---|---|
| 순서 | ① Watchdog `Disable-ScheduledTask` ② 현재 jar를 `.runtime\backups\`에 보관 ③ `gradlew clean bootJar -x test` ④ **jar 내용 검증** ⑤ `stop-running-ai.ps1` → `start-running-ai.ps1` ⑥ Watchdog `Enable-ScheduledTask` |
| ④ 검증 명령 | jar 안에 `V21__create_injury_report.sql` · `V22__add_running_dynamics…` · `V23__create_athlete_intensity_profile_history.sql` · `injury/WorkoutSafetyGateService.class` 4개가 모두 있어야 한다 |
| 기대 로그 | `Successfully applied 3 migrations … now at version v23` |
| **중단 조건** | ④에서 하나라도 누락(= stale jar) · Flyway 마이그레이션 실패 · `SchemaManagementException` · 기동 후 health가 UP이 되지 않음 → 즉시 §7-A 코드 롤백 |

### D. 부상 상태 ACTIVE 입력 및 안전 게이트 검증

| | |
|---|---|
| 전제 | **선수의 실제 상태를 그대로 입력한다.** 무릎 통증이 남아 있으면 `ACTIVE`이며, `RESOLVED`로 적는 것은 안전 게이트 우회다 |
| 명령 | `POST /api/v1/athlete/injury-reports` with `{"bodySite":"KNEE","side":"…","painPresent":true,"painIntensity":<실제값>,"status":"ACTIVE","notes":"…"}` |
| 기대 | 응답 `safety.nonRestTrainingAllowed=false`, `reasonCode=INJURY_ACTIVE` |
| 확인 | 비REST Draft 승인 → `WORKOUT_SAFETY_INJURY_ACTIVE` / REST Draft 승인 → 200 |
| 운영 주의 | **48시간마다 재보고**하지 않으면 `INJURY_STATE_STALE`로 다시 전면 차단된다 |
| **중단 조건** | 보고 후에도 `nonRestTrainingAllowed=true`가 나오면 게이트 오작동 → 즉시 롤백 |

### E. Watchdog 및 외부 Relay 정상 상태 검증

| | |
|---|---|
| 확인 | 재활성화 5분 후 `watchdog-status.json`의 `overall=UP`, 5개 컴포넌트 전부 UP, `restartBudget.used` 전부 0, `longTermLockout` 전부 false |
| 릴레이 | `GET 127.0.0.1:17845/health` → `{"status":"ok"}` · Funnel `tailscale serve status` = `/ proxy http://127.0.0.1:17845` 단일 항목 · 워치페이스 `/today-workout` 정상 |
| **중단 조건** | 컴포넌트 `LOCKED_OUT` 발생 · 재시작 예산 소진 · 릴레이 DOWN |

### F. 원격 Git push 여부

| | |
|---|---|
| 현재 | `origin/main` = `370ee8f`, `origin/main-3` = `24ff0b5`. 이번 Phase에서 **push 0건** |
| **push 전에 반드시 결정할 것** | 이 저장소는 **PUBLIC**이다. push하면 Funnel 호스트명이 포함된 문서 2건이 새로 공개된다(동일 호스트명은 이미 5건이 공개 상태이고, Funnel 호스트명은 CT 로그에 어차피 공개되므로 증분 노출은 사실상 0이지만, **공개 여부 자체가 소유자 결정 사항**이다) |
| 권고 | 저장소를 private으로 전환하거나, 공개 유지라면 현 상태로 push해도 새로운 비밀정보 노출은 없다(비밀정보 스캔 0건) |

### G. (추가 제안) PostgreSQL 바인딩을 루프백으로 제한

STEP 2에서 **RISK-H3**으로 확인된 사항이다. A~F와 독립적으로 승인받을 수 있다.

| | |
|---|---|
| 변경 | `docker-compose.yml` → `ports: ["127.0.0.1:${POSTGRES_PORT:-5432}:5432"]` |
| 순서 | Spring 중지 → `docker compose up -d`(컨테이너 재생성, 명명 볼륨 유지 = **데이터 손실 없음**) → Spring 기동 |
| 영향 | 알려진 소비자 전부 영향 없음(전수 조사 완료, §2-1) |
| 효과 | LAN·tailnet에서의 DB 직접 도달 차단 |
| **중단 조건** | 재생성 후 `docker volume ls`에 `running_ai_postgres_data`가 없으면 즉시 중단(데이터 유실 위험) |

---

## 최종 보고

### 1. 위험 항목 해결 여부

| ID | 내용 | 7D-0 | 7D-1 결과 |
|---|---|---|---|
| RISK-H1 | raw Spring `/mcp`가 미지 메서드에 500 + 스택트레이스 | High | **완화 확정** — 외부 Funnel에서 `/mcp` 404 실측, 운영 구성(스위치 OFF)에서 `/mcp` 미서비스 실증, 게이트웨이 29/29. 근본 수정은 DEBT-8 |
| RISK-H2 | 운영 코드와 RC의 커밋 괴리(stale jar 사고) | High | **절차화** — RC SHA·JAR SHA-256 확정 + 승인 항목 C에 **jar 내용 검증 단계** 명시 |
| RISK-H3 | PostgreSQL이 LAN·tailnet에서 도달 가능 | (M1, Medium) | **High로 상향 + 해결안 확정** — 방화벽 허용 규칙·tailnet 도달·평문 전송·superuser 롤·접속 로그 부재를 실측. 1줄 수정안과 영향 0건을 검증, 승인 항목 G로 제시 |
| RISK-M2 | 자동 수집 부재로 코치 입력이 묵을 수 있음 | Medium | **미해결(운영 절차 과제)** — 코치 호출 전 `data-refresh` 선행을 배포 구성표에 명시 |
| RISK-M3 | 레거시 writer가 "Disabled 유지"에만 의존 | Medium | **상태 재확인** — 4종 전부 Disabled, 승인 체크리스트 A/E에 편입 |
| RISK-M4 | 5분마다 콘솔 창 | Medium | **원인 확정 + 해결안 측정 완료** — `conhost --headless`로 창 0건·기능 동일 실증. 적용은 후속 승인(§STEP 3-6) |
| RISK-M5 | `.env.example` 괴리 | Medium | **해결** — 28/28 문서화, Garmin 자격증명 유도 제거 (커밋 `2208872`) |
| RISK-L1 | Garmin 시뮬레이터 방치 | Low | 미해결(사용자 조치) — 여전히 실행 중 |
| RISK-L2~L4 | CommandBridge 실패 · stale `today_plan.json` · TaskScheduler 로그 비활성 | Low | 미해결(별도 판단) |
| **RISK-L5** | **공개 GitHub 저장소에 Funnel 호스트명 노출** | 신규 | 평가 완료 — 증분 노출 0(이미 5건 공개 + CT 로그 공개), 실방어선은 Bearer+레이트리밋. 저장소 공개 여부는 승인 항목 F |

### 2. 변경 파일 및 커밋

| 커밋 | 내용 | 파일 |
|---|---|---|
| `ce93a46` | 7D-0 등 작업문서 8건 보존 | `docs/work-orders/*.md` 8개 (+2,454 줄) |
| **`2208872`** | `.env.example` 완성 + Garmin 자격증명 칸 제거 | `.env.example` (+196 / −23) |
| `<최종>` | 7D-1 지시서·결과보고서 + `.gitignore`에 `server/.kotlin/` 추가 | `docs/work-orders/*.md` 2개, `.gitignore` |

**소스 코드(`server/src/**`) 변경 0줄.** 운영 스크립트(`scripts/windows/**`) 변경 0줄.

### 3. 운영 배포용 Git SHA

```
RC         : 22088724a2bc6d4dd5be13272b597f2ea72b8955   (2208872)
최종 HEAD  : 위 + 문서/.gitignore 커밋 1건 (jar 내용 동일, 배포에 사용해도 무방)
현재 운영  : 370ee8f (main = origin/main)
```

### 4. JAR 검증 결과

| 항목 | RC JAR | 운영(롤백용) JAR |
|---|---|---|
| SHA-256 | `ACEDE7D6B8FE658DBB037C85ACE82B80B58B09B79A51394EA388B2F8E9E562FA` | `1A7BE11B5DFF8E74A310A79EA945D900439767B232FC497E553767022227CA98` |
| 크기 | 75,397,694 B | 75,289,548 B |
| Flyway 파일 | **23개 (V1..V23)** | 20개 (V1..V20) |
| Phase 7 클래스 11종 | **전부 PRESENT** | **전부 없음** |
| 비밀정보 혼입 | 0건 | — |
| `spring.factories` | PRESENT (동작으로 검증: `Registered tools: 7`) | — |

### 5. DB 마이그레이션 및 복구 검증 결과

| 검증 | 결과 |
|---|---|
| V20 → V23 적용 (운영 실데이터 사본) | **성공, 84 ms**, 3개 전부 success |
| 데이터 보존 | **완전 일치** — activity 8 / analysis 8 / sample 21,828 / lap 123 / recovery 21 / fitness 99 / draft 19 / raw_payload 40 |
| 재기동 멱등성 | `Successfully validated 23 migrations` / `up to date` |
| 제약·인덱스 | V21 5개 제약 + 1 인덱스, V23 3개 제약 + 1 인덱스 (7D-0 확인 그대로) |
| **구버전 코드 × V23 스키마** | **기동 성공** — Flyway가 future 마이그레이션 허용, Hibernate validate 통과, 핵심 API 전부 200 |
| 백업 → 복원 | `pg_dump -Fc` 2.8 MB → `pg_restore` → **운영과 전 행수·프로필 값·타임스탬프 완전 일치** |
| 복원본 + 구버전 코드 | 기동 성공, `Current version: 20` |
| Flyway 이력 수동 삭제 | **사용하지 않음** |

### 6. 배포 중단 조건 (하나라도 해당하면 즉시 중단 → §7-A 코드 롤백)

1. 백업 파일이 생성되지 않거나 2 MB 미만
2. Canonical 워킹트리가 dirty하거나 FF-only 병합이 거부됨
3. 재빌드한 jar에 V21/V22/V23 또는 `injury/WorkoutSafetyGateService.class`가 하나라도 없음 (**stale jar**)
4. Flyway 마이그레이션 실패 로그 또는 `SchemaManagementException`
5. 기동 후 `/actuator/health`가 UP이 되지 않음
6. 부상 상태 ACTIVE 보고 후에도 `nonRestTrainingAllowed=true`
7. 게시 스위치 중 하나라도 true로 바뀌어 있음 / `PublishingModeGuard` 기동 거부
8. 레거시 writer 4종 중 하나라도 Enabled
9. Watchdog 컴포넌트 `LOCKED_OUT` 또는 재시작 예산 소진
10. 진행 중인 Historical Backfill(RUNNING) 존재

### 7. 배포 예상 영향 및 다운타임

| 단계 | 소요(실측 기반) | 다운타임 |
|---|---|---|
| 사전 점검(읽기 전용) | ~2분 | 없음 |
| 백업 `pg_dump` + jar 보관 | ~1분 | **없음** (MVCC, 운영 Spring 접속 중 수행 가능) |
| Watchdog 일시 정지 | 즉시 | 없음 |
| FF 병합 + `clean bootJar -x test` + jar 검증 | ~2분 | 없음 |
| **Spring 중지 → 기동 (Flyway 포함)** | **~30–60초** | **이 구간만 API 중단** |
| 검증 + Watchdog 재개 + 부상 보고 | ~5분 | 없음 |
| **합계** | **~15분** | **~30–60초** |

**기능적 영향**

| 변화 | 설명 |
|---|---|
| 부상 안전 게이트 활성 | 보고 전까지 **REST 외 모든 Draft 생성·수정·승인·게시 차단**. 설계된 fail-closed 동작이며 결함이 아니다 |
| 코치 컨텍스트 변경 | 운영이 이미 `V2`이므로 **다음 코치 호출부터** 러닝 다이내믹스·LTHR provenance가 프롬프트/스냅숏에 포함된다. `context_sha256`이 바뀌는 것은 정상 |
| 기존 분석 8건 | V22 컬럼은 재분석 전까지 NULL. 필요 시 `POST /api/v1/activities/{id}/analysis` (Garmin 호출 0) |
| Garmin 프로필 sync(매일 04:30) | 배포 후 V23 원장에 하루 1행 추가(동일값이면 `applied=false`) |
| 워치페이스 / Relay / Funnel | **영향 없음** — 릴레이는 Spring에 연결되어 있지 않다 |
| `/mcp` | **존재하지 않음** (두 스위치 모두 false, 404 실증) |
| 외부 쓰기 | **없음** — 게시 스위치 3종 전부 false |

### 8. 최종 판정

## **GO** — 단, STEP 6의 A~G는 **각각 별도 승인 후** 실행

**GO 근거**

1. **품질**: 회귀 **1,539/1,539 PASS**(Spring 1,272 + 게이트웨이 29 + 커넥터 134 + 릴레이 14 + Windows 90), 전부 RC SHA에서 이번 세션 실행.
2. **아티팩트**: RC JAR SHA-256 확정, Flyway 23개·Phase 7 클래스 11종 전부 포함, 비밀정보 0건, 패키징된 `spring.factories`의 동작까지 실증.
3. **마이그레이션**: 운영 실데이터 사본에서 V20→V23 **84 ms, 무손실**, 재기동 멱등.
4. **되돌릴 수 있다**: 구버전 jar가 V23 스키마에서 **실제로 기동**하고, 백업→복원이 **완전 일치**함을 증명했다. 롤백 경로가 가설이 아니라 실측이다.
5. **배포 직후 절차가 검증됐다**: C1(부상 보고)이 선수의 실제 ACTIVE 상태로 리허설되어, 무엇이 차단되고 무엇이 허용되는지 확정됐다.
6. **운영 무접촉**: 운영 DB·서비스·스케줄러·방화벽·Funnel 변경 0건, 외부 쓰기 0건.

**GO가 "지금 배포하라"는 뜻이 아닌 이유**

- 7D-0의 C1~C4는 **절차로 확정**됐을 뿐 아직 실행되지 않았다. 특히 **C2(병합→재빌드→jar 검증→재기동을 한 단위로)** 를 지키지 않으면 stale jar 사고가 그대로 재현된다.
- **RISK-H3(PostgreSQL LAN/tailnet 노출)** 은 배포와 독립적이지만 **지금 존재하는** 위험이다. 배포 여부와 무관하게 승인 항목 G를 먼저 처리할 것을 권고한다.
- ChatGPT ↔ **실제 개인 데이터** 연동은 여전히 **NO-GO**다(Auth0 미연동·게이트웨이 미배치·RISK-H1 근본 미해결).

### 9. 잔여 기술부채 (7D-0 대비 변동분)

| ID | 상태 |
|---|---|
| DEBT-1 (문서 미커밋) | **해결** (`ce93a46`) |
| DEBT-9 (`.env.example` 누락) | **해결** (`2208872`) |
| DEBT-16 **(신규)** | 필수 쿼리 파라미터 누락 시 500 — `GlobalExceptionHandler`에 `MissingServletRequestParameterException` 추가 필요. 기존 결함, Low |
| DEBT-17 **(신규)** | `conhost --headless` 적용 시 `LastTaskResult`가 항상 0 → `watchdog-status.json`에 `lastExitCode`/`lastRunOutcome` 추가 권고 |
| DEBT-18 **(신규)** | `server/.kotlin/`이 `.gitignore`에 없었음 → 최종 커밋에서 해결 |
| DEBT-2~8, 10~15 | 7D-0 기재 그대로 (미해결) |

---

## 10. 이번 Phase에서 생성했다가 철거한 자원

| 자원 | 철거 확인 |
|---|---|
| 사본 DB `running_ai_rc` / `running_ai_restore` | `DROP DATABASE` — 남은 DB는 `postgres`, `running_ai` 둘뿐 |
| 덤프 `/tmp/rc-pre.dump` (컨테이너 내) | 삭제 완료 |
| 검증 Spring 4개 (18095 RC · 18095 MCP-off · 18097 구버전 · 18098 복원) | 전부 종료 — 18095~18098 LISTENING 0 |
| 임시 Scheduled Task `RunningAI-7D1-TEST-{A,B,C,D,E,EXITA,EXITB}` | **전부 Unregister** — 잔존 0건 |
| 실험 스크립트(관찰자·틱 페이로드·드라이버) | 세션 scratchpad에만 존재, 저장소 미커밋 |

> 컨테이너 `/tmp`에 **7C-2 스모크 테스트의 잔여물 `seed-smoke.sql`(7,690 B, 합성 데이터 전용)** 이 남아 있는 것을 발견했다. 내 산출물이 아니고 운영 Postgres 컨테이너 내부라 **건드리지 않고 보고만** 한다(DEBT-19, Low).

**운영 무결성 최종 확인**

```
운영 DB  : flyway_rows=20, injury_report 테이블 없음, profile=176/290   (V20 그대로)
Spring   : 127.0.0.1:18080 {"status":"UP"}   (재시작 0회)
Relay    : {"status":"ok"} / Connector: {"status":"UP"}
Watchdog : checkedAt 2026-10-12T22:59:18Z, overall UP, lastAction NONE
Funnel   : "/ proxy http://127.0.0.1:17845"  (단일 항목, /mcp 미추가)
운영 jar : SHA256 1A7BE11B… (검증 전후 동일 = 무변경)
Canonical: 370ee8f, working tree clean, 병합 0건
```

## 11. 지시사항 준수 확인

| 금지/제한 항목 | 준수 |
|---|---|
| 운영 배포 | ✅ 수행하지 않음 |
| origin push | ✅ 0건 |
| Canonical main 병합 | ✅ 0건 (FF 가능 여부만 확인) |
| 실제 방화벽·포트 설정 변경 | ✅ 0건 (읽기 전용 조회 + 읽기 전용 TCP connect) |
| 운영 작업 스케줄러·Watchdog 변경 | ✅ 0건 (고유 이름의 임시 테스트 작업만 등록→삭제) |
| 자동 복구 기능을 잃는 해결책 채택 | ✅ 채택하지 않음 (S4U/세션 0 방안은 명시적으로 기각) |
| 안전 게이트 우회 / 운영 데이터 RESOLVED | ✅ 없음 — 리허설은 격리 사본에서 **ACTIVE로만** 수행 후 사본 삭제 |
| `.env.example`에 실제 자격증명 추가 | ✅ 없음 (플레이스홀더만, 오히려 유도 칸 2개 제거) |
| Flyway 이력 수동 삭제 롤백 | ✅ 사용하지 않음 |
| STEP 6 A~F 실행 | ✅ 0건 |
| 운영 서비스 중단 | ✅ 0건 |

작업 완료. 지시서에 따라 **STOP**한다.
