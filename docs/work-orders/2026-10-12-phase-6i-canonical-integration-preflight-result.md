# RunningAI Phase 6I — Canonical Integration Preflight (결과 보고)

지시서: `2026-10-12-phase-6i-canonical-integration-preflight-instruction.md`

검토 전용 작업이다. 코드/설정/운영 상태를 전혀 변경하지 않았다.

## 1. Git 정합성

- Canonical `main` HEAD: `43ed6af2049e1564fb35b76dbd8e22d99774ec19`, branch `main`,
  Working Tree Clean.
- `main-3` HEAD: `4c054cd2385dcf4fe1d3151cf5892aab4c6e5418`(= `origin/main-3`).
- `origin/main`: `f6a213588c5b7855b0dc7bf99948896291240ed7` — Canonical과 다르지만
  이는 기존부터 알려진 상태(Canonical이 `origin/main`보다 앞서 있고, 이번 Phase
  시리즈 전체에서 `origin/main` Push가 의도적으로 보류되어 온 것)이며 새로운
  문제가 아니다.
- `git merge-base main origin/main-3` = `43ed6af2...` = Canonical `main` 자신 →
  **Canonical `main`이 `origin/main-3`의 직계 조상**이다. `main-3`가 Canonical
  대비 **3개 커밋 순수하게 앞서 있을 뿐**, 분기(diverge)는 전혀 없다.
- `main-3`가 Canonical보다 앞서는 3개 커밋 전부 확인:
  - `fe23684` — Phase 6I-1.7D-2M Canonical FF + 재부팅 전 기준선 기록
  - `02914ce` — Phase 6I-1.7D-3 재부팅 전 기준선 동결(`REBOOT_PRECHECK=GO`)
  - `4c054cd` — Phase 6I-1.1-E2E-P1 Intervals→Relay 읽기 전용 검증 결과
- `git diff --name-status 43ed6af..origin/main-3`: **6개 파일, 전부 신규 추가
  (`A`), 전부 `docs/work-orders/` 내부** — 삭제·수정된 기존 파일 없음, 운영
  코드/테스트/설정 변경 없음.
- `git diff --check 43ed6af..origin/main-3`: 통과(공백 오류 없음).

### 누락·충돌·의도치 않은 변경

- **충돌/의도치 않은 변경**: 없음. 순수 추가뿐이므로 충돌 가능성 자체가 구조적으로
  없다.
- **누락 발견(새 항목)**: 개발 Worktree(`main-3`)에 다음 4개 파일이 **Git에
  전혀 추적되지 않은 채(untracked)** 디스크에만 존재한다. Canonical에는 이 파일
  들이 아예 없다.
  - `docs/work-orders/2026-10-10-phase-6i-1-6c-spring-port-config-consistency-instruction.md`
  - `docs/work-orders/2026-10-10-phase-6i-1-6c-spring-port-config-consistency-result.md`
  - `docs/work-orders/2026-10-10-phase-6i-1-6c-3-spring-port-propagation-instruction.md`
  - `docs/work-orders/2026-10-10-phase-6i-1-6c-3-spring-port-propagation-result.md`

  파일 수정 시각은 `2026-10-10`으로, 이번 Phase 6I-1.1 시리즈보다 이전의, 별도
  세션이 작성했을 것으로 보이는 Phase 6I-1.6C("Spring Port 설정 일관성") 작업
  기록이다. 이 작업 자체의 코드 변경(Spring 포트 해석 로직, `RunningAI.Common.ps1`의
  `Resolve-RunningAiSpringPort`)은 이미 Canonical에 반영되어 있는 것으로 보이나
  (아래 3번 항목에서 실제로 그 코드가 존재함을 확인했다), **그 작업의 기록 문서
  자체는 어느 브랜치에도 커밋된 적이 없다** — 이번 Phase의 작업 범위는 아니지만,
  문서화 공백으로 기록해 둔다. 이번 검토는 읽기 전용이므로 이 파일들을 커밋하거나
  삭제하지 않았다.

## 2. 코드 및 보안 감사 결과 — External Relay (`tools/external-relay/server.js`)

### 환경변수 로딩 우선순위

`RunningAI.Common.ps1`의 `Import-DotEnvIntoProcess`(312행)를 직접 확인: 이미
`Env:` 네임스페이스에 존재하는 키는 **절대 덮어쓰지 않고** 건너뛰며(`SkippedExisting`),
없는 키만 `.env`에서 채운다(`Applied`). 적용 결과 로그는 **키 이름과 개수만**
기록하고 값은 절대 출력하지 않는다(`Write-Step`에 값이 전달되지 않음을 직접
확인). 우선순위는 **명시적 프로세스 환경변수 > `.env` > (정의 없음, 에러)**이며,
이는 `Test-ExternalRelay.ps1`의 357/373번 체크
(`an existing process-env INTERVALS_ICU_API_KEY is never overridden by .env`)로
이미 회귀 테스트가 걸려 있음을 확인했다.

`server.js` 자신은 `.env`를 직접 읽지 않고 `process.env.INTERVALS_ICU_API_KEY`만
읽는다(96행) — `.env` 적재는 전적으로 `start-external-relay.ps1`의 책임이며,
이 분리 자체가 올바른 설계다.

### Mock 비활성화

`WATCHFACE_RELAY_MOCK`(140행 분기)은 `.env`, `scripts/windows/*.ps1`,
`scripts/windows/external/*.ps1` 전체를 재검색한 결과 **어디에도 설정되어
있지 않음**을 재확인했다(Phase 6I-1.1-E2E-P1에서 이미 확인한 것과 동일한
결과, 이번에 독립적으로 재검증). 운영 기동 경로가 이 변수를 설정하지 않는다는
소스 주석(139행)과 일치한다.

### 인증 처리

Bearer 토큰(`secrets/watch-token.json`)을 `crypto.timingSafeEqual`로 비교
(84-91행). 길이가 다르면 `timingSafeEqual` 호출 전에 즉시 `false`를 반환하는데,
이는 이론적으로 아주 미세한 타이밍 부채널(토큰 길이 추정)을 남기지만, 이미
디컴파일 가능한 고정 길이 토큰이라는 전제(헤더 주석 18행) 위에서는 실질적
위험이 없는, 업계에 널리 쓰이는 패턴이다 — 새로운 결함으로 분류하지 않는다.
인증 실패 로그(`AUTH_FAILED ip=${ip}`)는 IP만 남기고 헤더 값 자체를 절대
포함하지 않는다.

### NO_PLAN 분기

레거시 `today_plan.json`의 명시적 `REST` 결정이 Intervals.icu 이벤트 유무보다
항상 우선(143-146행), 그 다음 `Run|Ride` 정규식 필터(148행) 통과 이벤트가
없으면 `NO_PLAN`, 있어도 `labelForEvent`가 `null`을 반환하면 역시 `NO_PLAN`
(149-152행, 즉 "이벤트는 있지만 라벨을 만들 수 없는" 경우도 `NO_PLAN`으로
안전하게 수렴 — `HAS_PLAN`을 섣불리 선언하지 않는 설계). Phase 6I-1.1-E2E-P1에서
실측한 `{"status":"NO_PLAN"}` 결과와 이 분기 로직이 정확히 일치함을 재확인했다.

### 에러 처리

`intervalsGet`(95-119행)은 비-200 상태, 타임아웃, 비-JSON 응답을 모두 구분된
`Error`로 reject하고, 라우트 핸들러(180-182행)는 이를 전부 **클라이언트에게는
동일한 `502 {"status":"UPSTREAM_ERROR"}`로만** 노출한다 — Intervals.icu의 실제
오류 본문이나 상태 코드 세부사항이 클라이언트(워치)로 전달되지 않는다. 서버
로그(`log('ERROR', ...)`)에는 `err.message`만 남는데, 이 메시지들
(`intervals.icu HTTP ${code}`, `intervals.icu request timed out`,
`INTERVALS_ICU_API_KEY not set...`, `intervals.icu returned non-JSON`) 중
어느 것도 키나 토큰 값을 포함하지 않음을 모든 호출 경로를 직접 추적해 확인했다.

### 민감정보 노출 가능성

- `log()` 함수(48-54행)는 `(authorization|token|api[_-]?key)\s*[:=]\s*\S+` 패턴을
  `[REDACTED]`로 치환하는 방어적 백스톱을 가지고 있으나, 실제로 이 코드베이스의
  어떤 `log()` 호출도 현재 비밀값을 메시지에 포함하지 않는다(위 에러 처리
  항목에서 전부 확인) — 즉 이 정규식은 "현재는 불필요하지만 미래의 실수를
  방지하는" 안전망으로 기능하고 있다.
- 업스트림 Intervals.icu의 원본 이벤트 페이로드(개인 GPS, 상세 운동 데이터 포함
  가능)는 `labelForEvent(runs[0])`로 **파생된 짧은 라벨 문자열만** 클라이언트에
  전달되고, 원본 JSON 자체는 절대 응답에 포함되지 않는다(135-153행 전체 추적
  확인).
- 토큰/API 키는 이번 감사 전체에서 한 번도 원문으로 출력하지 않았다.

### 추가로 발견한 구조적 기술 부채(신규, 이번 감사에서 처음 식별)

1. **(기존에 이미 보고됨, 재확인)** `server.js` 37행 `TODAY_PLAN_FILE` 상수가
   Legacy 저장소(`C:\running-ai\state\today_plan.json`) 절대 경로를 하드코딩.
2. **(신규 발견)** `workout-types.js`의 `TYPE_KEYWORDS`(13-26행) 라벨 사전은
   자체 주석(4-12행)에 따르면 **Legacy 저장소의
   `running-ai/config/running-workout-types.json` 레지스트리 값을 수동으로
   미러링**하도록 설계되어 있다 — 그 레지스트리가 바뀌면 이 파일도 "둘 다
   갱신해야 한다"는 주석만 있을 뿐 자동 동기화나 검증 장치가 없다. 레이블이
   조용히 어긋나도(새 운동 종류 추가 등) 이를 감지할 자동화된 안전장치가
   없다는 뜻이며, 이는 순수한 상관관계/정합성 리스크이지 보안 취약점은 아니다.
3. **(신규 발견)** `Test-ExternalRelay.ps1`의 91번 체크("never contains a
   hard-coded legacy repo path")는 **`start-external-relay.ps1` 자신**의
   레거시 경로 부재만 검증하며, `server.js` 자체에 남아 있는 위 1번 항목의
   하드코딩된 레거시 경로는 **이 회귀 테스트의 보호 범위 밖**이다 — 즉 같은
   종류의 결함을 막는 안전장치가 한쪽 파일에는 있고 다른 파일에는 없다.

이 세 가지는 전부 Legacy 저장소에 대한 **읽기 전용 참조 코드**를 설명하는
것이며, 이번 감사에서 Legacy 저장소 자체를 열거나 수정하지 않았다.

## 3. 테스트 검토 결과

- 지난 Phase(6I-1.7D-2R) 이후 운영 코드/테스트 코드 자체는 전혀 변경되지
  않았음을 위 1번 항목의 `diff --name-status`로 이미 확인했다 — 따라서 그때
  기록된 전체 회귀 결과(6개 PowerShell 스위트 82+48+16+36+55+5=242 PASS,
  Node 14/14 PASS, 0 FAIL)는 **현재도 유효**하다고 판단한다(이번 감사에서는
  재실행하지 않음 — 코드가 바뀌지 않았으므로 재실행이 새로운 정보를 주지
  않는다).
- `Test-ExternalRelay.ps1`을 직접 읽어 커버리지를 확인: 스크립트 파싱,
  포트 설정 읽기, 마커 식별, Health 판정(정상/다른 status/비-2xx/무응답),
  레거시 경로 부재(시작 스크립트만), 중복 시작 방지, FOREIGN 프로세스 보호,
  매니지드 프로세스만 종료, 상태 조회, `.env` 로딩 우선순위(2건) — 모두
  이번 감사에서 확인한 코드 동작과 정확히 일치.
- Node `server.test.js`/`workout-types.test.js`는 Health, 인증
  (무인증/오답/비-Bearer/정답), Mock 경유 `HAS_PLAN`, 404, 잘못된 Method,
  Rate Limiting, 라벨 분류 유틸리티 6건을 커버 — 전부 직접 소스와 대조해
  일치 확인.
- **실행 가능한 회귀 테스트 범위의 한계**: 기존 테스트는 전부 Mock 또는
  디스포저블 스폰 프로세스를 사용하며, **실제 Intervals.icu 계정에 진짜
  Run/Ride WORKOUT 이벤트가 존재하는 상황의 End-to-End**는 어떤 자동화
  테스트로도 커버되지 않는다(커버할 수도 없다 — 외부 서비스의 실제 데이터에
  의존하기 때문). 이는 결함이 아니라 테스트 설계상 당연한 경계다.

## 4. FF-only 병합 가능 여부

**가능**. `git merge-base --is-ancestor main origin/main-3`가 참이고, 변경
전체가 `docs/work-orders/`에 대한 순수 추가뿐이며, `git diff --check`가
통과한다. 병합 시 다음 명령 그대로 충돌 없이 완료될 것으로 판단한다
(이번 작업에서는 **실행하지 않았다** — 지시서가 검토 전용임을 명시):

```powershell
git merge-base --is-ancestor main origin/main-3   # true
git merge --ff-only origin/main-3                 # 충돌 가능성 없음(순수 FF)
```

## 5. 실제 WORKOUT 데이터 유무에 따른 검증 항목 구분

**실제 WORKOUT 없이 이미 검증 가능/검증 완료**:

- Intervals.icu 인증 성공 여부(이미 확인: HTTP 200).
- Relay의 인증 처리(토큰 정답/오답/누락), Health, 404 라우팅, Rate Limiting.
- `NO_PLAN` 분기 자체의 정확성(이미 실측 확인).
- Mock 비활성 상태, 환경변수 로딩 우선순위(소스/회귀 테스트로 확인).
- Local/Public(Tailscale Funnel) 응답 일관성, TLS, 포트 노출 범위.
- 에러 처리 경로의 정보 노출 여부(소스 추적으로 확인, 실제 업스트림 장애
  상황 재현 없이도 코드 경로상 안전함을 판단 가능).

**실제 Run/Ride WORKOUT 이벤트가 있어야만 검증 가능(현재 미검증, 데이터
부재로 보류 중)**:

- `FULL_DATA_PATH_PASS`(분류 A) 경로 전체 — 실제 이벤트 → 실제 라벨 생성 →
  `HAS_PLAN` 응답 → FR265 실제 표시까지의 End-to-End.
- `labelForEvent`/`classifyTypeFromName`이 실제 운영 데이터의 다양한 한국어
  운동명 패턴에 대해 올바르게 동작하는지(단위 테스트는 합성 데이터만 사용).
- 레거시 `today_plan.json`의 명시적 `REST` 분기가 실제로 발동하는 상황
  (레거시 파일 접근 금지로 인해 존재 여부조차 확인 불가 — 이 경로 자체가
  이번 감사의 사각지대).
- FR265 실물 기기에서의 실제 표시(Phase 6I-1.1-E2E-P1에서 이미
  `FR265_PHYSICAL_CHECK=DEFERRED`로 기록, 변경 없음).

## 6. Canonical Watchdog DryRun 및 Relay-only 복구 실행 전 조건 (계획만, 미실행)

다음 조건이 **전부** 충족된 상태에서만, 별도 승인을 받아 실행을 검토한다.
이번 작업에서는 아래 어떤 명령도 실행하지 않았다.

1. Canonical `main` Working Tree Clean, HEAD가 알려진 기대 SHA와 일치.
2. 5개 구성요소(Docker/PostgreSQL/Connector/Spring/Relay) 전부 `UP`,
   `watch-running-ai.ps1 -DryRun` 직전 수동 Health 확인으로 재검증.
3. `RunningAI-Watchdog` Scheduled Task `Enabled`, 최근 자동 실행
   `LastTaskResult=0`이 최소 1회 이상 확인된 상태.
4. `watchdog-state.json`이 `version=2`이고, 5개 컴포넌트 전부 Restart Budget
   `0`, LongTerm Lockout 전부 해제 상태.
5. 직전 운영 상태(PID/CreationDate/PostgreSQL Volume/DB 기준선)를 이번
   보고서 또는 더 최신 보고서 기준으로 다시 한번 캡처해 둔 상태.
6. Relay-only 복구(`start-external-relay.ps1` 단독 실행 또는
   `Invoke-RunningAiRelayRecoveryAction` 경유)를 시도할 경우, **대상이
   `external-relay` 하나로 국한**되고 핵심 체인(docker/postgres/connector/
   spring)에는 어떠한 Action도 계획되지 않은 상태(`Get-RecoveryPlan`의
   `Actions` 중 `external-relay` 외 항목이 없음)임을 DryRun 출력으로 사전
   확인.
7. `-DryRun` 실행은 상태 파일/PID/로그 어느 것도 변경하지 않음을 전제로
   하되, 실제 비-DryRun 복구 실행은 **운영자가 즉시 결과를 관찰할 수 있는
   감독 환경**에서만, 그리고 Relay가 실제로 비정상(DOWN/UNHEALTHY)일 때만
   고려한다 — 현재처럼 Relay가 이미 정상(`UP`)인 상태에서 인위적으로 복구를
   유발하는 것은 범위 밖이다.
8. 레거시 Scheduled Task나 레거시 저장소 경로는 이 절차 어디에도 관여하지
   않음을 재확인.

## 7. 남은 위험 및 검증 공백

- `docs/work-orders/`의 4개 Phase 6I-1.6C 문서가 Git에 전혀 추적되지 않은
  채 디스크에만 존재(1번 항목) — 데이터 손실 위험은 낮지만(로컬 디스크에는
  존재) 다른 작업자/다른 머신에서는 보이지 않는 문서 공백.
- `workout-types.js`의 레거시 레이블 레지스트리 수동 미러링(2번 항목) —
  향후 레이블 정의가 레거시 쪽에서 바뀌면 조용히 어긋날 수 있는, 자동화되지
  않은 정합성 리스크.
- `server.js`의 하드코딩된 레거시 경로가 `start-external-relay.ps1`과 달리
  회귀 테스트의 보호를 받지 못함(3번 항목) — 코드 자체의 결함은 아니지만
  테스트 커버리지의 비대칭.
- 실제 Run/Ride WORKOUT 데이터가 없어 `FULL_DATA_PATH_PASS`(분류 A) 경로는
  여전히 미검증(5번 항목).
- FR265 실물 기기 확인 여전히 `DEFERRED`.
- Phase 6I-1.7D-3의 실제 PC 재부팅 E2E 여전히 미실행(`REBOOT_PRECHECK=GO`
  상태 유지, 변경 없음).
- 레거시 `today_plan.json`의 실제 존재/내용은 이번에도 확인 불가(접근 금지
  유지).

## 8. 다음 단계 GO / NO-GO

**GO** — `origin/main-3` → Canonical `main` FF-only 병합을 별도 승인 하에
진행할 수 있는 상태로 판단한다. 근거: 순수 Git 조상 관계, 변경 전부가
`docs/work-orders/`에 대한 추가뿐, 코드/보안 감사에서 새로운 차단 사유
(Blocking Issue) 없음, 기존 회귀 결과가 코드 변경 없음으로 인해 그대로
유효.

단, 다음은 이 GO 판정에 **포함되지 않는다**(별도 판단 필요):

- Canonical `main`으로의 실제 FF 병합 실행 자체(이번 작업의 범위 밖,
  검토 전용).
- Watchdog DryRun/Relay-only 복구의 실제 실행(6번 항목의 조건 충족 확인 및
  별도 승인 후에만).
- `PHASE_6I_1_EXTERNAL_ACCESS_E2E_READY` 선언(FR265 실물 확인, LTE/5G 외부망
  확인, 실제 WORKOUT 데이터 기반 A 경로 검증, 재부팅 E2E가 모두 남아 있음).

## 지시사항 준수 확인

- 레거시 `C:\running-ai` 변경: 없음(경로 문자열만 소스 코드에서 읽었을 뿐,
  그 경로 자체를 열거나 수정하지 않음).
- Canonical `main` 변경 또는 병합: 수행하지 않음(가능성만 판단).
- 운영 서비스 중지·재시작, DB 변경, `.env`/인증 토큰 수정, 재부팅: 수행하지
  않음.
- `origin/main` Push: 수행하지 않음.
- 실제 WORKOUT 생성·수정·삭제: 수행하지 않음.
- 민감정보 원문 출력: 없음.

작업 완료. 지시서에 따라 STOP한다.
