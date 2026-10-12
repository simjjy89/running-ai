# WO-RUNNINGAI-PHASE-6I-1.1-E2E-P2 — Canonical Watchdog DryRun & Production Invariance (결과 보고)

지시서: `2026-10-12-phase-6i-1-1-e2e-p2-canonical-watchdog-dryrun-instruction.md`

서비스 중지·재시작·장애 주입은 수행하지 않았다. `main-3-2` Worktree, 레거시
저장소, 워치페이스 별도 저장소는 전혀 접근하지 않았다.

## 1. Preflight 결과 (STEP A, 전부 PASS)

1. Canonical HEAD `54aa849b0204cea2bca1649cc69b58f5baf20a1c`(기대값과 일치),
   Working Tree Clean.
2. Docker/PostgreSQL: `8ff2a3adc495 running-ai-postgres`, `healthy`,
   `Up 11 hours`.
3. Connector `8765/health`/Spring `18080/actuator/health`/Relay
   `17845/health`: 전부 `200`.
4. PID/CreationDate: Spring `29984`(`00:45:12`), Connector Launcher
   `25372`/Listener `28684`(둘 다 `00:45:08`), Relay `13588`(`00:45:29`) —
   직전 Phase들과 완전히 동일.
5. Connector Ownership: `LAUNCHER_CHILD`(LauncherPid `25372`, ListenerPid
   `28684`).
6-7. Watchdog: `Enabled`. Preflight 수행 중 **자동 5분 주기 틱이 실제로
   진행 중(`State=Running`)**이었음을 발견 — DryRun 수동 실행과의 Mutex
   경합을 피하기 위해 틱이 끝날 때까지 기다린 뒤(완료 확인:
   `LastRunTime=2026-10-12 11:34:16`, `LastTaskResult=0`, `State=Ready`로
   복귀) STEP C를 진행했다.
8. `watchdog-state.json`: `version=2`, Restart Budget/LongTerm Lockout 5개
   컴포넌트 전부 `0`/해제.
9. Tailscale Funnel: `https://laptop-mmdqucic.tail4597a5.ts.net` →
   `127.0.0.1:17845` (On).
10. 기존 재부팅 백업/기준선: `C:\Users\simjy\.runningai-state-backups\`
    내 v1/v2 Watchdog State 백업 3건과 PostgreSQL 논리 백업(`postgres\
    running_ai.pre-reboot.20261011-225810Z.dump`) 전부 그대로 보존됨을
    재확인.

비정상 사항 없음. STEP B/C 진행.

## 2. Canonical HEAD

`54aa849b0204cea2bca1649cc69b58f5baf20a1c` — 작업 시작부터 종료까지 변경
없음.

## 3. DryRun Exit Code

**`WATCHDOG_DRYRUN_EXIT=0`**

실행 출력에 "Another watchdog run is in progress; skipping" 메시지는
**나타나지 않았다** — 따라서 이번 결과는 지시서가 경고한 Mutex 경합에 의한
무의미한 Exit=0이 아니라, **실제로 관측·계획이 수행된 진짜 PASS**다.

소스 확인(STEP B, `watch-running-ai.ps1` 직접 재검토)으로 DryRun 경로의
읽기 전용성을 재확인했다:

- `Read-WatchdogState -ReadOnly:$DryRun`(78행) — 확인.
- `Get-RuntimeObservation ... -ReadOnly:$DryRun`(84행), 내부적으로
  `Get-TrackedProcessId -ReadOnly`까지 전파됨(이전 Phase에서 이미 확인한
  `RunningAI.Watchdog.ps1`의 프로브 구조와 일치) — 확인.
- DryRun 분기(89-101행)는 Recovery Executor(`Invoke-WatchdogRecovery`,
  108행 이후)에 도달하기 **전에** `exit $ExitCode.Ok`로 종료 — Recovery
  Executor 실행 불가 확인.
- `$writeFiles = -not $DryRun`(67행)이 로그 로테이션(68-72행)을 완전히
  건너뛰게 하고, `Write-WatchdogLog`/`Save-WatchdogState` 계열 호출은
  전부 DryRun의 `exit`보다 뒤(104행 이후)에만 존재 — State/Status/로그
  쓰기 불가 확인.
- `status-running-ai.ps1`은 이번 작업에서 **실행하지 않았다** — 이 스크립트의
  읽기 전용 부작용 경로를 이번 대화에서 감사한 적이 없으므로, 지시서의
  명시적 조건("완전히 감사되지 않았다면 실행하지 않는다")에 따라 보류했다.

## 4. 구성요소별 상태

| 구성요소 | 상태 |
|---|---|
| docker | UP |
| postgres | UP |
| connector | UP |
| spring | UP |
| external-relay | UP |
| **Overall** | **UP** |

## 5. Planned Actions 및 Blocked

- Planned actions: **none**
- Blocked: **none**

5개 구성요소 전부 정상이므로 복구 계획 자체가 생성되지 않았다 — 기대 결과와
정확히 일치.

## 6. 운영 PID 불변

DryRun 전후 완전히 동일: Spring `29984`, Connector Launcher
`25372`/Listener `28684`, Relay `13588` — CreationDate 전부
`2026-10-12 00:45:08~00:45:29`로 변경 없음.

## 7. PostgreSQL/DB 불변

`8ff2a3adc495 running-ai-postgres`, `healthy`, `Up 11 hours`(재시작 없음),
Volume `running-ai-github_running_ai_postgres_data` 불변. `activity` 6행,
`garmin_sync_state` `high_water_started_at=2026-10-05 15:02:39+00`,
`last_successful_sync_at=2026-10-05 16:24:14.229834+00` — 전부 불변.

## 8. Watchdog State 불변

`watchdog-state.json` SHA256: `466C8B06AFA55F4D8540A22BB638B2D1563F0A8AA41BA9F0A961C01E1FAF38BD`
— **Phase 6I-1.7D-3에서 기록한 재부팅 전 백업과 완전히 동일한 해시**임을
확인했다. `version=2`, Restart Budget/LongTerm Lockout 5개 컴포넌트 전부
`0`/해제 그대로.

참고로 이번 Preflight 중 관측된 자동 5분 주기 틱(`LastRunTime=11:34:16`,
`LastTaskResult=0`)은 정상적인 운영 동작이며, 모든 구성요소가 이미 UP이라
아무 변화도 기록하지 않았으므로(저장 내용이 기존과 동일) 위 SHA256 불변과
모순되지 않는다 — 이번 DryRun 수동 실행 자체는 이 자동 틱과 무관하게
State 파일에 전혀 쓰기를 수행하지 않았다.

## 9. 기존 E2E-P1 결과 유지 여부

Phase 6I-1.1-E2E-P1 결과를 그대로 재사용(이번 Phase에서 Intervals.icu를
다시 호출하지 않았다):

- Intervals.icu API 인증: HTTP 200 (유지)
- 2026-10-12 WORKOUT 이벤트: 0건 (유지)
- Local Relay: `NO_PLAN` (유지)
- Public Relay: `NO_PLAN` (유지)
- Local/Public 응답 일치 (유지)

이번 Phase에서 실제 훈련 생성이나 API 키 변경은 수행하지 않았다. FR265
실물 확인과 LTE/5G 외부망 확인은 이전과 동일하게 사용자 직접 검증으로
남겨둔다.

## 10. 재부팅 준비 상태

`REBOOT_PRECHECK=GO`(Phase 6I-1.7D-3) 상태는 변경 없이 유지된다. 이번
Phase는 실제 재부팅이나 Watchdog 일반 실행을 수행하지 않았으며, Canonical
Fast-forward 이후에도 Watchdog DryRun 경로 자체가 안전하게 읽기 전용으로
동작함을 실측으로 재확인했다는 점에서 재부팅 전 신뢰도를 한 단계 더
높였다.

## 11. 다음 단계 GO / NO-GO

**GO.**

Canonical Fast-forward(54aa849) 이후 Watchdog DryRun이 정확히 설계된
대로(읽기 전용, Exit 0, 5개 구성요소 UP, Planned Actions 0) 동작함을
확인했고, 운영 PID/PostgreSQL/DB/Watchdog State/`.env`/토큰 전부 DryRun
전후 완전히 불변임을 확인했다. Mutex 경합도 발생하지 않아 결과가
결정적(PASS, INCONCLUSIVE 아님)이다.

단, 다음은 이 GO 판정에 포함되지 않는다: `origin/main` Push, 실제 Watchdog
일반 실행/Relay-only 중지·복구, 실제 PC 재부팅, FR265 실물·LTE/5G 확인,
실제 WORKOUT 데이터 기반 `FULL_DATA_PATH_PASS` 경로 검증. **전체 External
Access E2E READY는 선언하지 않는다.**

## 지시사항 준수 확인

- 서비스 Stop/Start/Restart, Relay Kill, 장애 주입: 수행하지 않음.
- Watchdog 일반 실행(`-DryRun` 없이): 수행하지 않음.
- Docker Compose 변경, Windows 재부팅/로그오프: 수행하지 않음.
- Intervals.icu 키 변경, 훈련 생성·게시: 수행하지 않음.
- Canonical Merge/Reset, `origin/main` Push: 수행하지 않음.
- 미추적 문서 삭제: 수행하지 않음.
- 다른 Worktree(`main-3-2`) 변경: 전혀 접근하지 않음.
- 레거시 저장소/워치페이스 별도 저장소: 전혀 접근하지 않음.

작업 완료. 지시서에 따라 STOP한다.
