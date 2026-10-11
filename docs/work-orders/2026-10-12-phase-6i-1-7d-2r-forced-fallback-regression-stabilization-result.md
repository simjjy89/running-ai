# WO-RUNNINGAI-PHASE-6I-1.7D-2R — Forced Fallback Regression Test Stabilization (결과 보고)

지시서: `2026-10-12-phase-6i-1-7d-2r-forced-fallback-regression-stabilization-instruction.md`

Production 코드는 전혀 수정하지 않았다. 변경은 테스트 파일 1건에 한정했다.

## 1. 원래 실패 원인

`Test-MainPcPythonVenvCtrlC.ps1`의 "forced fallback..." 체크는 `TimeoutSec 0`을 주면
`Stop-RunningAiConnectorManaged`가 Graceful 단계를 건너뛰고 곧바로 Forced 경로로 진입한다고
가정하고 작성되었다. 실제로는 `TimeoutSec` 값과 무관하게 항상 먼저 `Send-CtrlC.ps1`로 실제
Ctrl+C를 전송한 뒤 `Wait-Until -TimeoutSec $TimeoutSec`로 결과를 확인한다. 이 환경의 Ctrl+C
전달이 (과거 문서화된 "샌드박스 콘솔 신호 전달 제한"과 달리) 빠르게 성공하면서,
`TimeoutSec 0`의 즉시 1회 확인 시점에 이미 프로세스가 정상 종료되어 있어 `Result='graceful'`
이 반환되었다 — 이는 실제 운영 함수의 결함이 아니라 테스트 자체의 실행 경로 가정이 틀린
것이었다(Phase 6I-1.7D-2 결과 보고서에서 이미 근본 원인을 규명함).

## 2. 수정 방식

전용 Python Fixture `listener_ignore_sigint.py`를 새로 추가했다. 기존 `listener.py`(SIGINT를
받으면 정상 종료)와 동일한 소켓 bind/listen 구조를 쓰되, `signal.signal(signal.SIGINT,
signal.SIG_IGN)`로 SIGINT를 명시적으로 무시한다. 기존 "Graceful Ctrl+C 양쪽 모두 정상 종료"
체크(세 번째 체크)는 원래의 `listener.py`를 그대로 사용하며 전혀 수정하지 않았다.

`Start-RunningAiTestVenvPair`에 `-ListenerScript` 매개변수를 추가해(기본값은 기존
`listener.py`) 두 Fixture를 하나의 함수로 재사용할 수 있게 리팩터링했다 — 로직 중복 없이
Forced 전용 체크만 새 Fixture를 사용하도록 했다.

Forced 전용 체크(네 번째 체크)를 재작성:

1. 새 Fixture로 Launcher/Listener 쌍을 기동.
2. 기동 직후 `$o.Verdict -eq 'LAUNCHER_CHILD'`, `ManagedPidSnapshot.Count -eq 2`를 직접 단언
   (STEP 3 항목 1-2).
3. **사전 검증(STEP 2)**: `Stop-RunningAiConnectorManaged`를 호출하기 전에, 동일한
   `Send-CtrlC.ps1` 헬퍼로 Launcher/Listener 각각에 직접 Ctrl+C를 보내고 2초 대기한 뒤
   Listener/Launcher 생존 및 포트 점유 상태를 확인한다. 셋 중 하나라도 거짓이면(즉 SIGINT
   무시가 실제로 보장되지 않으면) 즉시 예외를 던져 해당 체크를 FAIL로 보고한다 — PASS로
   처리하지 않는다.
4. 사전 검증을 통과한 뒤에만 실제 `Stop-RunningAiConnectorManaged`를 `TimeoutSec 3`(0이
   아님 — 실제 Graceful 대기·타임아웃 경과를 거치도록 함)로 호출.
5. 결과를 STEP 3의 항목대로 단언: `Result -eq 'forced'`, `ResultCode -eq 'STOPPED'`,
   `PortFreed`, `RemainingPids.Count -eq 0`, Launcher/Listener 전부 종료, Bystander 생존.
   `graceful`와 `forced`를 모두 허용하는 식의 완화는 전혀 하지 않았다.

PID 재사용/CreationDate 변조 거부(다섯 번째, 기존 체크)는 수정하지 않았다 — STEP 3 항목
12(강제 종료 직전 재검증)가 이미 이 별도 체크로 End-to-End 커버되고 있음을 주석으로
명시했다.

## 3. Ctrl+C 무시 조건 검증

사전 검증 단계에서 Launcher/Listener 각각에 실제 `Send-CtrlC.ps1`(운영 코드가 내부적으로
쓰는 바로 그 헬퍼)를 호출하고 2초 대기 후 프로세스 생존 및 포트 점유를 직접 확인했다.
5회 연속 실행(최초 1회 + 재현 3회 + 결정성 재확인 2회, 총 6회) 모두 사전 검증을 통과했고,
사전 검증 실패로 인한 FAIL은 한 번도 발생하지 않았다 — Fixture의 SIGINT 무시가 안정적으로
보장됨을 확인했다.

## 4. 실제 Forced 코드 경로 진입 증거

실행 로그에 다음 운영 코드 자체의 출력이 그대로 남았다(Fixture가 아니라
`Stop-RunningAiConnectorManaged` 내부의 `Write-Step` 호출):

```
[07:28:12] Connector managed PID(s) 30148, 3756 did not exit within 3s; forcing stop of verified process(es) only.
```

이는 Graceful 대기가 실제로 `TimeoutSec`(3초) 전부 경과했고, 그 후 진짜 Forced 분기(강제
`Kill()`)가 실행되었음을 직접 증명한다. 결과값 `Result='forced'`/`ResultCode='STOPPED'`도
함께 확인했다.

## 5. Bystander 보존 결과

Forced 종료 이후에도 비관리 Bystander(`time.sleep(120)` 스크립트) 프로세스는 매 실행(총
6회)마다 생존이 확인되었다. 관리 대상(Launcher/Listener)만 선택적으로 종료되고 비관리
프로세스는 전혀 건드리지 않음을 재확인했다.

## 6. 전체 6개 테스트 결과

개발 Worktree에서 직접 재실행(결정성 확인을 위해 ConnectorOwnership/VenvCtrlC는 총 3회,
나머지는 1회 이상):

| 테스트 파일 | 결과 |
|---|---|
| Test-RunningAI.ps1 | 82/82 PASS |
| Test-Watchdog.ps1 | 48/48 PASS |
| Test-ExternalRelay.ps1 | 16/16 PASS |
| Test-ConnectorOwnership.ps1 | 36/36 PASS (3회 재실행 전부 동일) |
| Test-WatchdogRecoveryPolicy.ps1 | 55/55 PASS |
| Test-MainPcPythonVenvCtrlC.ps1 | **5/5 PASS** (3회 재실행 전부 동일, 이전 0/5 실패 대비 완전 해결) |

SKIP 처리된 항목 없음(Python 3.12 인터프리터가 이미 이 환경에 존재하여 모든 체크가
실행됨). PowerShell 5.1 파서 호환성: `[System.Management.Automation.Language.Parser]::ParseFile`
로 구문 오류 없음 확인, PowerShell 7 전용 연산자(`??`, `?.`, 삼항 `? :`) 사용 없음을 diff
전체에서 직접 검색해 확인. `git diff --check` 통과(CRLF 경고만 존재, 실제 공백 오류 없음).
비밀정보 검사(`password|secret|api[_-]?key|token=...` 패턴) 통과 — diff에 해당 패턴 없음.

## 7. Node 테스트 결과

`node --test tools/external-relay/test/*.test.js` — **14/14 PASS** (서버 HTTP 동작 8건 +
workout-types 유틸리티 6건). 운영 포트 17845는 사용하지 않음(디스포저블 임시 포트 +
`WATCHFACE_RELAY_MOCK=1`, 테스트 자체 코멘트에 명시).

## 8. 운영 PID/DB/Watchdog 불변

테스트 전후 비교, 전부 동일:

- Spring `29984`, Connector Launcher `25372`/Listener `28684`, Relay `13588` —
  CreationDate 전부 `2026-10-12 00:45:08~00:45:29`로 불변.
- PostgreSQL: `8ff2a3adc495`, healthy, "Up 7 hours" — 재시작 없음.
- 애플리케이션 DB: `activity` 6행, `garmin_sync_state` HighWater/LastSync 불변.
- Watchdog: `RunningAI-Watchdog` Enabled(`Ready`), 테스트 중에도 5분 주기 자동 실행 계속됨
  (`LastRunTime=2026-10-12 07:34:16`, `LastTaskResult=0`), `version=2` State의
  `restarts`/`longTerm.failures` 전부 `0`, `lockedOutSince` 전부 공란 — 테스트로 인한
  Recovery Action, Restart Budget 소비, Lockout 전혀 없음.
- Local Health(Spring/Connector/Relay) 200, 인증 `GET /today-workout` 200 — 전부 정상.
- 격리 테스트 프로세스 잔여: 0건.

테스트로 인한 서비스 재시작은 0건이었다.

## 9. 변경 파일

- `scripts/windows/tests/Test-MainPcPythonVenvCtrlC.ps1`: 신규 SIGINT 무시 Fixture
  (`listener_ignore_sigint.py`) 추가, `Start-RunningAiTestVenvPair`에 `-ListenerScript`
  매개변수 추가(기존 호출부는 기본값으로 동작 변경 없음), Forced 전용 체크를 사전 검증 +
  `TimeoutSec 3` 실제 운영 함수 호출로 재작성. 기존 "Graceful Ctrl+C" 체크와
  "PID 재사용 거부" 체크는 변경하지 않음.
- 작업 지시서/결과 보고서 2건 (`docs/work-orders/2026-10-12-phase-6i-1-7d-2r-*`).

Production 코드(`RunningAI.Watchdog.ps1`, `RunningAI.ConnectorOwnership.ps1`,
`Send-CtrlC.ps1` 등)는 전혀 수정하지 않았다.

## 10. Commit SHA

커밋 메시지(지시서 지정): `test(windows): make connector forced-fallback regression deterministic`

실제 커밋 SHA는 이 문서 작성 직후 `git commit` 실행 결과로 확정되며, 본 보고서의 최종
커밋에는 이 결과 문서 자체도 포함된다(동일 커밋에서 함께 기록).

## 11. main/main-3 상태

- Canonical `main`: `36e1e96c...` — 변경하지 않음(기대값과 일치, Working Tree Clean 유지).
- `origin/main`: `f6a2135...` — 변경하지 않음.
- 개발 Worktree(`main-3`) / `origin/main-3`: 작업 시작 시 `91d543d3...`(기대값과 일치) →
  이번 커밋 이후 새 SHA로 Fast-forward Push (아래 10번 항목 SHA 참고).

## 12. Canonical FF 가능 여부

가능. 이번 변경은 테스트 파일 1건에 한정되고 운영 로직/Flyway 마이그레이션/설정값을 전혀
건드리지 않았으며, 전체 회귀(6개 PowerShell 스위트 + Node 14건)가 모두 결정적으로 PASS함을
확인했다. 다만 이번 작업 범위는 `origin/main-3` Push까지이며, 지시서에 따라 Canonical
`main`으로의 병합은 이번 작업에서 수행하지 않았다 — 별도 승인 시 바로 Fast-forward 가능한
상태임을 보고한다.

## 13. 남은 위험

- 없음. 이번 수정으로 Phase 6I-1.7D-2의 유일한 비결정적 실패가 근본적으로 해소되었고,
  Production 코드 변경이 전혀 없었으므로 운영 Watchdog의 실제 동작에 어떠한 영향도 없다.
- 참고로만 남김: 이 Fixture(`listener_ignore_sigint.py`)는 이 테스트 파일에만 존재하는
  디스포저블 스크립트이며 운영 Garmin Connector와는 무관하다.

## 지시사항 준수 확인

- Production 코드 수정: 없음.
- Canonical main 변경, `origin/main` Push: 없음.
- Watchdog 재등록, 서비스 재시작, 실제 장애 주입, Windows 재부팅: 없음.
- 테스트 프로세스는 전부 임시 디렉터리 + `RUNNING_AI_TEST_RUNTIME_DIR`격리 범위 내에서만
  생성/종료되었고, 운영 Python Connector·실제 포트 8765/18080/17845·운영 `.env`·실제
  Docker/PostgreSQL 명령·Watchdog Scheduled Task는 전혀 접근하지 않았다.

작업 완료. 지시서에 따라 STOP한다.
