# WO-RUNNINGAI-PHASE-6I-1.7D-2 — Isolated Watchdog Recovery & Failure Simulation (결과 보고)

지시서: `2026-10-12-phase-6i-1-7d-2-isolated-watchdog-recovery-simulation-instruction.md`

운영 서비스는 전체 작업 기간 동안 중지·재시작되지 않았다. 모든 장애 시뮬레이션은 격리된
임시 디렉터리/State 파일/포트에서만 수행했다.

## 1. 운영 Preflight (STEP A)

전부 PASS, 읽기 전용으로만 확인:

1. 5개 구성요소 Health: Spring/Connector/Relay 전부 `200 OK`.
2. PID/CreationDate: Spring `29984`, Connector Launcher `25372`/Listener `28684`, Relay
   `13588` — 전부 직전 Phase(6I-1.7D-1A) 보고와 동일, 변경 없음.
3. PostgreSQL: `8ff2a3adc495 running-ai-postgres` healthy, 볼륨
   `running-ai-github_running_ai_postgres_data` 동일.
4. 애플리케이션 DB 기준선: `activity` 테이블 6행, 최신 `started_at=2026-10-05 15:02:39+00`;
   `garmin_sync_state` athlete 1, `high_water_started_at=2026-10-05 15:02:39+00`,
   `last_successful_sync_at=2026-10-05 16:24:14.229834+00`.
5. Watchdog Scheduled Task: `Ready`(Enabled).
6. 최근 자동 실행: 5분 간격으로 계속 정상 실행 중(`LastTaskResult=0`), 로그에 연속
   `state=UP action=NONE result=OK` 기록.
7. `watchdog-state.json`: `version=2`.
8. LongTerm Lockout: 5개 컴포넌트 전부 `failures=0`, `lockedOutSince=` 공란(해제 상태).
9. Restart Budget: 전부 `0` (비정상 증가 없음).
10. Git: Canonical HEAD `36e1e96c...`(기대값 일치, Working Tree Clean), 개발 Worktree
    HEAD `39917fb4...`(기대값 일치), `origin/main`=`f6a2135...`, `origin/main-3`=`39917fb4...`
    — 전부 기대값과 일치.

운영 환경에 문제 없음 확인, 격리 테스트 진행.

## 2. 테스트 격리 보장 방법 (STEP B)

`RUNNING_AI_TEST_RUNTIME_DIR` 환경변수를 **dot-source 이전에** 설정해 `$script:RuntimeDir`를
결정하도록 하고, `Get-RunningAiRuntimeLockName`/`Get-RunningAiWatchdogStateLockName`은 이
경로를 해시하여 Lock 이름을 만들기 때문에 격리 테스트의 Runtime Lock은 Canonical의 실제
`.runtime` Lock과 이름 자체가 다름을 보장한다(기존 `Test-WatchdogRecoveryPolicy.ps1`의
BUSY/Lock 경합 검증에서 이미 확립된 패턴을 그대로 재사용).

핵심 안전장치: `Invoke-RunningAiCoreRecoveryAction`/`Invoke-RunningAiRelayRecoveryAction`은
**이미 dot-source된 현재 프로세스에서는 절대 직접 호출하지 않는다** — 그 프로세스의
`$script:RuntimeDir`/`$script:RepoRoot`는 파일이 처음 로드된 시점에 영구 고정되어, 이후
환경변수를 바꿔도 되돌릴 수 없기 때문이다(지시서 STEP B가 경고한 바로 그 실패 사례와
동일한 함정). 대신 모든 신규 검증은 격리 환경변수를 상속한 **새로 생성(spawn)된 자식
PowerShell 프로세스**가 처음부터 다시 dot-source하여 실행하거나, 실행 경계 자체를 주입형
`$Runner`/`$Observe` 클로저로 완전히 대체했다. 실제 `start-running-ai.ps1`/
`stop-running-ai.ps1`/Docker/PostgreSQL 명령/Scheduled Task는 신규 테스트 어디에서도
호출되지 않았으며, 실제 포트 8765/18080/17845는 사용되지 않았다(모든 신규 테스트는
`Get-Random`으로 뽑은 20000~40000대 임시 포트만 사용).

## 3. 장애 분류 결과 (STEP C)

11개 시나리오 중 8개는 기존 `Test-Watchdog.ps1`/`Test-ConnectorOwnership.ps1`/
`Test-WatchdogRecoveryPolicy.ps1`에 이미 완전히 커버되어 있음을 확인했고, 다음 3개 격차를
`Test-WatchdogRecoveryPolicy.ps1`에 End-to-End(분류+계획, 실제 `$Port` 경유 소유권 모델
포함) 신규 Check로 보강했다:

- Connector `ORPHANED_MANAGED_PROCESS` → `Get-ConnectorComponentState`가 `UNHEALTHY`로
  분류하고 `Get-RecoveryPlan`이 `RESTART_CONNECTOR`(PreStop=true)를 계획, Blocked 없음 —
  PASS.
- Connector `FOREIGN_PROCESS` → `DEGRADED`, Action 없음, `Blocked.Reason=FOREIGN_PROCESS`
  로 거부 — PASS.
- Connector `UNKNOWN_OWNER` → `DEGRADED`, Action 없음, `Blocked.Reason=UNKNOWN_OWNER`로
  거부 — PASS.

나머지 8개(Connector DOWN, HEALTH 실패+관리 PID 생존, Launcher 생존+Listener 부재, PID
재사용 거부, Spring DOWN, PostgreSQL stopped/unhealthy, 외부 Relay DOWN)는 기존 테스트가
이미 증명하고 있어 추가 구현 없이 재실행만으로 재확인했다. NO-GO로 보류한 시나리오는 없음.

## 4. Core 복구 검증 (STEP D)

- Connector/Spring 복구 성공/실패, 실제 실패 컴포넌트 예산 귀속, BUSY 예산 미소비,
  UNKNOWN_FAILURE 오탐 Lockout 방지, 종료 실패 이후 신규 Start 차단, 재관찰 기반 성공
  판정, Fail-safe — 전부 기존 `Test-WatchdogRecoveryPolicy.ps1`에 이미 구현되어 PASS.
- 신규 보강 2건:
  - `Invoke-WatchdogRecovery`의 **BLOCKED** 결과: 틱을 중단(`.Failed=true`)시키지만
    장기 실패 예산은 전혀 소비하지 않음(UNKNOWN_FAILURE는 단기 시도 횟수는 소비하되
    장기 예산은 소비하지 않는 것과 구분됨을 함께 확인) — PASS.
  - `Test-RunningAiRecoverySucceeded`의 "Exit Code 0은 성공의 증거가 아니다" 규칙: 아무것도
    리스닝하지 않는 컴포넌트는 미성공으로, 실제 정상 Health 응답기를 둔 컴포넌트는 성공으로
    판정됨을 디스포저블 루프백 HTTP 응답기로 직접 검증 — PASS.

복구 성공 판정은 전부 Runner 반환값이 아니라 최종 관찰 상태(재관찰) 기준임을 재확인했다.

## 5. Relay 독립 복구 검증

`external-relay`는 핵심 체인(docker/postgres/connector/spring)과 완전히 독립된 별도
Planning Pass로 처리됨, Spring 실패가 Relay 복구를 막지 않고 Relay 실패가 핵심 체인을
막지 않음을 기존 `Test-WatchdogRecoveryPolicy.ps1`/`Test-ExternalRelay.ps1`에서 확인 —
재실행 PASS. 신규 추가 없음(기존 커버리지로 충분).

## 6. 재시작 예산 및 Lockout 결과 (STEP E)

10개 시나리오(10분/3회 단기 제한, 24시간/6회 장기 제한, Lockout 이후 자동 복구 금지, 다른
컴포넌트 영향 없음, 프로세스 재시작 후 Lockout 유지, v2/.bak 일관성, State 손상 시
Fail-safe, 운영자 Lockout 해제 시뮬레이션, State 저장 경합, Lockout 해제-Watchdog 저장
충돌 방지) **전부 기존 `Test-WatchdogRecoveryPolicy.ps1`에 이미 구현되어 있었다** — 신규
구현 없이 재실행으로만 재확인, 전부 PASS. 운영 Lockout 상태는 실제로 변경하지 않았다
(모든 검증은 격리된 임시 State 파일에서만 수행).

## 7. Runtime Lock 안전성

`RUNNING_AI_TEST_RUNTIME_DIR` 기반 격리 Lock 이름이 Canonical 실제 Lock과 다름을
`Get-RunningAiRuntimeLockName`/`Get-RunningAiWatchdogStateLockName` 해시값 비교로
재확인했고, 교차 프로세스 Lock 경합(BUSY, 비정상 종료 후 재획득 가능) 시나리오도 기존
테스트에서 재확인 — PASS.

## 8. State 영속성 검증

v1→v2 마이그레이션 보존, `.bak` 일관성, 손상 파일 격리(quarantine, `-ReadOnly`에서는
격리 생략), 백업 존재 시 "파일 누락"을 첫 실행으로 오인하지 않는 Fail-safe 등 전부 기존
테스트에서 PASS 재확인. 이번 Phase의 어떤 작업도 실제 운영
`C:\running-ai-github\.runtime\watchdog-state.json`/`.bak`을 건드리지 않았다(아래 9번
항목에서 SHA/내용 불변 재확인).

## 9. 전체 회귀 결과 (STEP F)

개발 Worktree(`main-3`)에서 직접 재실행하여 검증(에이전트 보고를 그대로 신뢰하지 않고
전부 재실행):

| 테스트 파일 | 결과 |
|---|---|
| Test-RunningAI.ps1 | 82/82 PASS |
| Test-Watchdog.ps1 | 48/48 PASS |
| Test-ExternalRelay.ps1 | 16/16 PASS |
| Test-WatchdogRecoveryPolicy.ps1 | 55/55 PASS (신규 5건 포함) |
| Test-ConnectorOwnership.ps1 | **36/36 PASS** (2회 재실행, 매번 전부 통과) |
| Test-MainPcPythonVenvCtrlC.ps1 | 4/5 PASS (아래 상세) |

**중요 발견 — 투명하게 보고**: 이번 Phase를 위임한 에이전트(fork)의 최초 보고는
`Test-ConnectorOwnership.ps1`에서 "2건의 기존 Ctrl+C 환경 실패가 3회 재실행에서 일관되게
발생했다"고 기술했으나, 이를 그대로 신뢰하지 않고 직접 2회 재실행한 결과 **두 번 모두
36/36 전체 통과**(실패 0건)였다. 대신 `Test-MainPcPythonVenvCtrlC.ps1`에서 직접 발견한
1건의 실패를 진단 스크립트로 근본 원인까지 추적했다:

- 실패 체크: "forced fallback stops ONLY the verified managed PIDs, frees the port, and a
  bystander python.exe survives untouched" (`TimeoutSec 0`으로 강제 경로를 바로 타도록
  설계된 체크).
- 3회 재실행 모두 동일하게 실패(일관적, 우연한 Flake 아님).
- 별도 진단 스크립트로 단계별 재현한 결과: 실제로는 **모든 안전 보장이 전부 정상 동작**
  했다 — Bystander 프로세스는 끝까지 생존, Connector의 관리 대상 PID(Launcher/Listener)는
  둘 다 정상 종료, 포트는 정상 해제됨. 유일하게 어긋난 부분은 `Result` 값이 `'forced'`가
  아니라 `'graceful'`로 보고된 것뿐이었다.
- 근본 원인: 이 테스트 파일(Phase 6I-1.7C-0에서 작성, 이번 Phase의 변경 대상 아님)은
  "`TimeoutSec 0`을 주면 Graceful 단계를 건너뛰고 바로 강제 종료 경로로 간다"고 가정하고
  작성되었으나, 실제 `Stop-RunningAiConnectorManaged`는 `TimeoutSec` 값과 무관하게 먼저
  실제 Ctrl+C 신호(`Send-CtrlC.ps1`)를 보낸 뒤 `Wait-Until -TimeoutSec 0`으로 **즉시 한 번
  확인**한다 — 이번 조사에서, 이 환경의 Ctrl+C 신호 전달이 (Phase 6I-1.7C-0 당시 문서화된
  "샌드박스 콘솔 신호 전달 제한"과 달리) **지금은 실제로 성공**하고 있어, 그 즉시 확인
  시점에 이미 프로세스가 정상 종료되어 있었던 것으로 확인했다. 실제로 같은 파일의 "Ctrl+C
  양쪽 모두 Graceful 종료" 체크(세 번째 체크)도 이번 6회 실행(3+3회) 전부 PASS했다 — 이는
  `Test-ConnectorOwnership.ps1`의 재실행이 36/36으로 깨끗하게 나온 것과도 정확히 일치하는
  방향의 증거다.
- **결론**: 이것은 Watchdog/Connector 복구 로직의 결함이 아니라, 과거 환경의 "Ctrl+C가
  작동하지 않는다"는 전제 위에 설계된 **격리 테스트 자체의 가정(assumption)이 현재
  환경에서는 더 이상 성립하지 않는 것**이다(오히려 Ctrl+C가 이제 더 안정적으로 작동한다는
  긍정적 신호). 실제 강제-Fallback 메커니즘의 안전성 자체는 이번 진단으로 독립적으로
  재확인했다. 이 Phase의 범위(새로운 Watchdog 복구 정책 시나리오)에 속하지 않는, 다른
  Phase(6I-1.7C-0)에서 작성된 파일의 사전 존재 이슈이므로 이번 Phase에서는 수정하지
  않았고, 지시서의 "실패한 테스트를 삭제하거나 결과를 PASS로 바꾸지 않는다"는 원칙에 따라
  파일을 그대로 두고 여기 투명하게 기록한다. 향후 별도 Phase에서 이 Check의 단언 조건을
  "`Result`가 `graceful` 또는 `forced` 중 하나이고, Bystander 생존 + 관리 PID 종료 + 포트
  해제가 전부 참"으로 완화하는 경미한 수정을 권고한다.

이번 Phase에서 새로 구현한 5개 체크는 전부 PASS했다. 기존 실패 테스트를 삭제하거나
결과를 바꾼 사실 없음.

## 10. 운영 PID 및 PostgreSQL 불변 (STEP G)

격리 테스트 완료 후 재확인:

- Spring `29984`/Connector `25372`,`28684`/Relay `13588` — CreationDate 전부 동일
  (`2026-10-12 00:45:08~00:45:29`), 변경 없음.
- PostgreSQL: 동일 컨테이너 ID `8ff2a3adc495`, `healthy`, "Up 5 hours" — 테스트 기간 중
  재시작되지 않음.
- 애플리케이션 DB: `activity` 테이블 여전히 6행 — 변경 없음.
- `watchdog-state.json`: `version=2`, 모든 컴포넌트 `failures=0`/`lockedOutSince=` 공란 —
  격리 테스트로 인한 어떤 변경도 없음(격리 테스트는 전부 별도 임시 State 파일만 사용).
- 격리 테스트 프로세스 잔여: 없음(최종 확인에서 발견된 1건은 확인 명령 자체의
  `-Command` 인자 문자열에 검색 패턴이 리터럴로 포함되어 자기 자신과 매치된 거짓 양성이며,
  실제 잔여 프로세스 아님).
- Canonical Working Tree: Clean, HEAD `36e1e96c...` 그대로.
- 외부 Relay: `/health` 200 OK, 인증 `GET /today-workout` 200 OK(토큰 값은 출력하지 않음).

## 11. Watchdog 주기 실행 유지

격리 테스트 전/중/후 모두 5분 간격 자동 실행이 끊김 없이 계속되었다(예: 중간에 포착된
`05:34:16` 틱도 `LastTaskResult=0`, `UP/NONE/OK`로 정상 종료 확인). 격리 테스트가 실제
Watchdog 틱 타이밍이나 실행에 어떤 영향도 주지 않았다.

## 12. 변경 파일 및 상태

- `scripts/windows/tests/Test-WatchdogRecoveryPolicy.ps1` (+229줄): STEP C 3건
  (ORPHANED_MANAGED_PROCESS/FOREIGN_PROCESS/UNKNOWN_OWNER 소유권 인지 분류+계획 End-to-End)
  + STEP D 2건(BLOCKED 예산 미귀속, Exit Code 0 불충분 재관찰 규칙) 신규 Check 추가.
- 작업 지시서 원문: `docs/work-orders/2026-10-12-phase-6i-1-7d-2-isolated-watchdog-recovery-simulation-instruction.md`
- 본 결과 보고서: `docs/work-orders/2026-10-12-phase-6i-1-7d-2-isolated-watchdog-recovery-simulation-result.md`

코드/테스트 수정은 개발 Worktree(`main-3`)에서만 수행했다. Canonical(`C:\running-ai-github`)과
Legacy(`C:\running-ai`)는 이번 작업에서 전혀 접근하지 않았다. 검증 완료 후 두 파일을
커밋하고, 전체 검증 성공(위 9번 항목의 1건 비차단 발견 포함)에 따라 `origin/main-3`로
Fast-forward Push한다(아래 Commit SHA 참고). Canonical main과 `origin/main`은 변경하지
않았다.

## 13. 남은 운영 위험

- `Test-MainPcPythonVenvCtrlC.ps1`의 Forced-fallback 체크가 "Ctrl+C가 이 환경에서 이제
  정상 작동한다"는 조건 아래 `Result`값 단언 실패 상태로 남아 있음 — 실제 안전성 결함은
  아니지만, 다음에 이 파일을 다루는 작업에서 단언 조건을 완화하는 경미한 수정이 필요하다
  (위 9번 항목 참고). 이번 Phase 범위 밖이라 수정하지 않았다.
- 이번 Phase는 어디까지나 **격리 환경에서의 복구 정책/상태 영속성 검증**이다. 실제
  운영 환경에서 Connector/Spring/Relay/PostgreSQL을 고의로 중단시켜 Watchdog의 실제
  자동 복구가 끝까지 성공하는지(E2E) 검증한 적은 아직 한 번도 없다 — 이는 명시적으로
  범위 밖이며, 별도 승인 없이는 수행하지 않는다.

## 14. Phase 6I-1.7D-3 진입 GO/NO-GO

**GO.**

STEP A~G 전부 PASS, 운영 서비스/PID/PostgreSQL/애플리케이션 데이터/Watchdog 상태 전부
불변, 이번 Phase에서 신규 구현한 5개 격리 테스트 전부 PASS, 격리 안전성(STEP B) 설계가
실제로 Canonical 운영 경로를 한 번도 건드리지 않았음을 직접 재실행으로 재확인했다. 유일한
발견(13번 항목)은 Watchdog 복구 정책 자체의 결함이 아닌, 다른 Phase에서 작성된 테스트
파일의 환경 가정 노후화이며 GO 판정을 막지 않는다.

## 지시사항 준수 확인

- 실제 Spring/Connector/Relay 종료, 실제 PostgreSQL 종료·재시작: 수행하지 않음.
- 실제 Watchdog 비활성화·재등록, Windows 로그아웃·재부팅: 수행하지 않음.
- 운영 PID/Sidecar/State/.env 수정: 수행하지 않음.
- 실제 Garmin 동기화, 훈련 생성·게시: 수행하지 않음.
- Canonical Git Merge/Reset, `origin/main` Push: 수행하지 않음.
- Legacy(`C:\running-ai`) 저장소: 전혀 접근하지 않음.
- 격리 RuntimeDir/State가 의도한 시점에 적용되지 않아 실제 스크립트가 실행되는 과거의
  실패 패턴: 이번에는 재발하지 않았음을 STEP B 설계 검토 및 전체 재실행으로 확인했다.

작업 완료. 지시서에 따라 STOP한다.
