# WO-RUNNINGAI-PHASE-6I-1.7D-1A — Watchdog Scheduled Task Trigger Investigation (결과 보고)

지시서: `2026-10-12-phase-6i-1-7d-1a-watchdog-trigger-investigation-instruction.md`

읽기 전용 조사만 수행. 코드 변경, Git Push, Scheduled Task 등록/활성화/비활성화, 서비스 재시작,
State 변경, Garmin/훈련 관련 작업, 보안 로그 정책 변경 없음.

## 1. Task State

`RunningAI-Watchdog` — **Ready** (Enabled). 조사 시작 시점에는 자동 실행 기록이 없었으나,
조사 진행 중 사용자가 직접 로그오프 후 재로그인하여 실제 로그인 이벤트를 발생시킨 결과,
조사를 마치는 시점까지 **4회**의 자동 실행이 관측되었다 (아래 7번 항목).

## 2. LastRunTime / LastTaskResult / NextRunTime

조사 종료 시점 기준:

- LastRunTime: `2026-10-12 01:04:16` (4번째 자동 실행)
- LastTaskResult: `0` (성공)
- NumberOfMissedRuns: `0`
- NextRunTime: 조회 결과 공란 (AtLogon 트리거는 NextRunTime을 고정 시각으로 보고하지 않는 것이
  Windows Task Scheduler의 정상 동작이며, Repetition이 계속 진행 중임은 LastRunTime 간격으로
  확인됨)

조사 시작 시점의 구(舊) 기록(`LastRunTime=2026-10-10 12:52:17`, `LastTaskResult=21`)은
지시서 STEP D의 명시적 규칙대로 "LastRunTime이 갱신되지 않은 과거 결과"로 취급하여 새로운
실패 증거로 사용하지 않았다.

## 3. 실제 Trigger XML

`Export-ScheduledTask`로 확인한 실제 등록 XML(이전 Phase 6I-1.7D-1 STEP B에서 이미 확인·보존된
내용과 동일, 이번 조사에서 재확인만 수행):

- `LogonTrigger` 존재, `Enabled=true`
- `Delay=PT5M`
- `Repetition.Interval=PT5M`, `Repetition.Duration`은 공란(무기한 반복), `StopAtDurationEnd=true`
  (Duration이 없으므로 실질적 의미 없음 — 무기한 반복 유지)
- `EndBoundary` 없음(만료되지 않음)
- `UserId`는 등록 당시의 운영 계정과 일치

이번 조사에서 XML 내용이 이전 확인 시점과 달라진 점은 없었다 — 등록 정보 자체는 손상되거나
유실되지 않았다.

## 4. UserId / Principal 일치 여부

일치함. `Principal.UserId`는 실제 로그인한 운영 계정과 동일하며, 이번에 발생한 실제 로그인
세션의 소유자와도 일치한다.

## 5. Repetition Interval / Duration

- Interval: `PT5M` (5분) — 실측 자동 실행 간격과 정확히 일치 (`00:49:26 → 00:54:29 → 00:59:27 →
  01:04:16`, 매 회 약 5분 간격)
- Duration: 미설정(무기한) — 정상, 의도된 설정과 일치

## 6. 실제 로그인 세션 확인 결과

`quser`는 이 Windows 에디션에 설치되어 있지 않아 사용 불가. 대안으로 `Win32_LogonSession`
(`LogonType=2`, Interactive)을 조회하여 실제 로그인 세션 시작 시각을 확인:

- 로그인 세션 시작: `2026-10-12 00:44:11` (로컬)
- `RunningAI-Watchdog`의 첫 자동 실행: `00:49:26` — 로그인 시각 + `Delay=PT5M`과 정확히 일치

이는 "화면 잠금 해제"가 아니라 사용자가 의도적으로 수행한 **로그오프 후 재로그인에 의한
신규 로그인 이벤트**이며, 이 신규 로그인 이벤트가 `LogonTrigger`를 정상적으로 기동시켰음을
직접 증거로 확인했다.

## 7. TaskScheduler Operational 이벤트

`Microsoft-Windows-TaskScheduler/Operational` 채널은 이 머신에서 기본적으로 **비활성화
(`IsEnabled=False`)**되어 있어 조회 불가. 지시서 STEP C의 명시 조건에 따라, 이 로그 채널을
활성화하거나 권한을 상승시키는 조치는 취하지 않았다. 이벤트 로그 부재는 "로그인이 없었다"는
증거가 아니며, 실제로는 Win32_LogonSession 및 Watchdog 자체 로그(아래)로 신규 로그인과 트리거
정상 동작을 독립적으로 확인했다.

## 8. 원인 A~F 판정

**원인 A — 해결됨 (A, now resolved).**

최초 증상("Watchdog Scheduled Task가 Enabled인데도 자동 실행되지 않음")의 근본 원인은
"Scheduled Task 활성화(`Enable-ScheduledTask`) 이후 실제 신규 로그인 이벤트가 한 번도
발생하지 않아 `LogonTrigger`의 반복 스케줄 자체가 전혀 시작되지 않았던 것"으로 확정한다.
`StartWhenAvailable=True` 설정은 머신이 꺼져 있던 동안 놓친 "예정된 실행 시각"을 보정하는
기능이며, "로그인 이벤트 자체가 애초에 발생하지 않은 상황"을 소급 보정하지는 않는다 — 이는
Windows AtLogOn 트리거의 알려진 정상 동작이다.

사용자가 직접 로그오프 후 재로그인하여 신규 로그인 이벤트(`00:44:11`)를 발생시킨 결과,
`Delay=PT5M` 이후 트리거가 정상적으로 기동되어 `00:49:26`부터 5분 간격으로 4회 연속
자동 실행(`00:49:26 / 00:54:29 / 00:59:27 / 01:04:16`, 전부 `LastTaskResult=0`, 전부
`overall=UP`, `lastAction=NONE`)이 확인되었다. B~F에 해당하는 추가 결함은 발견되지 않았다.

## 9. 운영 서비스 불변 여부 — 중요 발견 (정직하게 보고)

**PID는 변경되었다.** 단, 이는 Watchdog 자체나 이번 조사에서 수행한 어떤 명령에 의한 것이
아니라, 사용자가 수행한 로그오프/재로그인이라는 동일한 로그인 이벤트에 의해 **별도의 기존
Scheduled Task인 `RunningAI-Startup`**(이번 Watchdog 작업과 무관하게 이미 존재하던,
마찬가지로 AtLogOn 트리거를 사용하는 자동화)가 독립적으로 기동되어 전체 스택을 새로
기동시킨 결과다.

확인된 사실관계:

- `RunningAI-Startup`의 `LastRunTime=2026-10-12 00:44:16`, `LastTaskResult=0`(성공) — 로그인
  세션 시작(`00:44:11`)과 거의 동시.
- 로그오프 시 세션에 종속되어 있던 기존 Spring/Connector/Relay 프로세스는 종료되었고,
  `RunningAI-Startup`이 로그인 직후 이들을 처음부터 다시 기동함.
- 신규 PID (기존 대비 전부 교체):
  - Spring: `29984` (CreationDate `2026-10-12 00:45:12`)
  - External-Relay: `13588` (CreationDate `00:45:29`)
  - Connector launcher/stub: `25372`, listener/engine: `28684` (둘 다 CreationDate `00:45:08`)
- PostgreSQL 컨테이너: 기존 43시간+ 가동 시간 대신 **"Up 16 minutes"**로 재시작 확인 —
  `start-running-ai.ps1`이 로그인 시 Docker 스택도 함께 기동한 결과로 판단됨.
- 이 재시작 이후 서비스 Health는 전부 정상(Spring/Connector/Relay `200 OK`)이며, Connector
  Ownership은 새 PID 조합(`LauncherPid=25372`, `ListenerPid=28684`)에 대해 정상적으로
  `LAUNCHER_CHILD`로 재해석됨 — 소유권 로직 자체는 PID가 바뀌어도 올바르게 동작함을 재확인.
- Watchdog State v2 (`watchdog-state.json`): `version=2` 유지, 모든 컴포넌트의
  `restarts` 카운트 `0`, `longTerm.failures` 전부 `0`, `lockedOutSince` 전부 공란 — 즉
  Watchdog는 이 재시작을 **자신이 수행한 복구 행위로 전혀 기록하지 않았다** (당연히 그렇다 —
  Watchdog가 PID를 바꾼 것이 아니라, 이미 바뀐 뒤의 건강한 상태를 관찰만 했을 뿐이다).

**결론**: Watchdog 자체는 이번 조사 전체에 걸쳐 단 한 번도 불필요하거나 잘못된 복구
Action을 수행하지 않았다(4회 모두 `lastAction=NONE`). 다만 "PID/CreationDate 불변"이라는
Phase 6I-1.7C-0부터 6I-1.7D-1까지 모든 단계에서 반복적으로 재확인해 온 운영 불변식은, 이번에
한하여 — Watchdog가 아닌 **별개의, 이전부터 존재하던 `RunningAI-Startup` 작업과 사용자의
로그오프/재로그인이라는 이번 조사 자체의 검증 방법이 상호작용한 결과로 — 처음으로 깨졌다.**
이는 코드 결함이 아니며, 사용자가 제안받고 직접 선택한 조치("지금 로그오프 후 재로그인해서
트리거 발생시키기")의 자연스럽고 예견 가능했던 부수효과다.

## 10. 최소 수정 또는 안전한 실행 검증 권고

- **Watchdog 트리거 메커니즘 자체에는 코드/설정 변경이 필요 없다.** `LogonTrigger` +
  `Delay=PT5M` + `Repetition Interval=PT5M`는 실제 로그인 이벤트 발생 시 지시서가 기대한
  대로 정확히 동작함이 실증되었다(4회 연속 성공, 전부 5분 간격, 전부 UP/NONE).
- 이후 이 스레드를 이어갈 작업자를 위한 권고: 지금까지의 "PID/CreationDate 변경 없음"
  베이스라인은 이번 로그오프/재로그인으로 인해 **자연스럽게, 그리고 의도대로 교체**되었음을
  명시적으로 인지해야 한다 — 새 베이스라인은 Spring=`29984`, Relay=`13588`,
  Connector=`25372`(launcher)/`28684`(listener), PostgreSQL "Up ~16min 시점"이다. 이를
  "예상치 못한 변경"으로 오인해 불필요한 롤백이나 추가 조사를 시작하지 않도록 주의가 필요하다.
- 향후 Watchdog의 자동 실행에 대한 신뢰도를 더 높이려면, 재부팅/로그오프 같은 인위적 트리거
  없이 **자연적인 다음 로그인(예: 일상적인 재시작)**에서도 동일하게 정상 기동되는지 한 차례
  더 관찰하는 것을 권장한다(단, 이는 이번 작업의 범위가 아니며 사용자가 별도로 판단할 사항).
- `Start-ScheduledTask`를 이용한 수동 기동은 이제 **불필요**하다 — 실제 트리거가 이미 정상
  동작을 증명했으므로, 인위적 수동 실행으로 추가 검증할 필요가 없다.

## 지시사항 준수 확인

- 로그아웃/재부팅: 이번 작업(조사) 자체에서는 수행하지 않음 — 사용자가 별도 결정으로
  스스로 수행.
- `Enable/Disable/Register/Unregister-ScheduledTask`: 호출하지 않음.
- `Start-ScheduledTask`: 호출하지 않음.
- `watch-running-ai.ps1` 일반 모드 실행: 수행하지 않음(관찰된 4회 실행은 전부 Scheduled
  Task의 자체 트리거에 의한 것이며, 이 조사에서 수동으로 기동한 적 없음).
- 실제 서비스 재시작: 이 조사 자체에서 수행하지 않음(재시작은 `RunningAI-Startup`이 사용자의
  로그인 행위에 반응하여 독립적으로 수행함, 위 9번 항목 참고).
- Watchdog State/PID/Sidecar 변경, PostgreSQL 변경, 운영 `.env` 수정, 코드 수정, Garmin
  동기화, 훈련 게시, 보안 로그 정책/권한 변경: 모두 수행하지 않음.

## 결론 및 STOP

원인 A 확정 및 해결, Watchdog 정상 자동 동작 4회 실증 확인. 지시서의 명시적 지침
("조사 후 STOP한다")에 따라 이 investigation은 여기서 종료하며, 추가 조치(이전
Phase 6I-1.7D-1의 중단된 STEP G~J 재개, `Start-ScheduledTask` 수동 실행 등)는 사용자의
별도 승인 없이는 수행하지 않는다.
