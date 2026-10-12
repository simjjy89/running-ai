# WO-RUNNINGAI-PHASE-6I-1.1-E2E-P3 — Operator Status Read-Only Hardening (결과 보고)

지시서: `2026-10-12-phase-6i-1-1-e2e-p3-operator-status-readonly-hardening-instruction.md`

개발 Worktree(`main-3`)에서만 수정했다. Canonical(`C:\running-ai-github`), `main-3-2`,
레거시 저장소는 전혀 접근하지 않았다.

## 1. 원인 및 수정 위치

`scripts/windows/status-running-ai.ps1`의 3개 호출
(`Get-TrackedProcessId 'garmin-connector'`/`'spring'`/`'external-relay'`,
원래 56/63/72행)이 전부 `-ReadOnly`를 누락하고 있었다. `RunningAI.Common.ps1`의
`Get-TrackedProcessId`(641-653행)를 재확인한 결과, `-ReadOnly`가 없으면
추적된 PID가 해당 컴포넌트와 일치하지 않을 때(정체가 바뀐 Stale/Foreign PID)
`Remove-PidFile $Name`(651행)을 호출해 `*.pid` 파일을 **실제로 삭제**한다 —
파일 헤더 주석이 "Read-only and safe"라고 선언하고 있음에도, 상태만 조회하려는
호출이 실제 파일 쓰기(삭제) 부작용을 가지고 있었던 것이 확인된 원인이다.

수정: 3개 호출 전부에 `-ReadOnly` 스위치를 추가했다(각 1줄, 최소 변경). 헤더
주석에도 이 수정을 설명하는 3줄을 추가해 향후 리더가 이유를 바로 알 수 있게
했다.

그 외 컴포넌트의 실행·복구 동작, 출력 형식, Exit Code, Health 조회 계약은
전혀 변경하지 않았다.

## 2. 스크립트 전체 정적 감사(STEP B 후반)

`status-running-ai.ps1` 136줄 전체를 다시 읽어, 위 3개 호출 외에 다른 파일
쓰기 경로가 있는지 확인했다: `Write-Host`(화면 출력), `Get-Content`(읽기
전용, `watchdog-status.json`), `Get-HttpBody`(읽기 전용 HTTP GET),
`Invoke-NativeQuiet`/`Invoke-NativeText`(읽기 전용 `docker info`/`docker
inspect`/`docker compose ps`), `Invoke-RunningAiTailscaleCommand`(읽기 전용
`tailscale funnel status`) — **이 3개 호출 외에 다른 파일 쓰기 경로는
발견되지 않았다.** 범위를 임의로 넓히지 않았다.

## 3. ReadOnly 전파 확인

`Get-TrackedProcessId -ReadOnly`가 지정되면, PID 불일치 시
`Write-Step "...read-only check, left untouched."`를 출력하고 `$null`을
반환할 뿐 `Remove-PidFile`은 호출되지 않음을 소스에서 재확인했다(646-649행).
반환값(`$null`)은 `-ReadOnly` 적용 전후로 동일하므로, 이 수정은
`status-running-ai.ps1`의 출력 형식(`" (not started by RunningAI scripts)"`
표시)이나 Exit Code 계약에 전혀 영향을 주지 않는다 — 오직 디스크 쓰기
부작용만 제거되었다.

## 4. Stale/Foreign PID 보존 테스트 (STEP C, 신규 8건 추가)

`scripts/windows/tests/Test-RunningAI.ps1`의 기존 `Invoke-StatusScriptIsolated`
헬퍼(이미 `RUNNING_AI_TEST_RUNTIME_DIR`/`RUNNING_AI_TEST_ENV_ROOT`를
dot-source 이전에 설정해 격리된 자식 프로세스로 `status-running-ai.ps1`을
실행하는 기존 패턴)를 `-SeedPidFiles` 매개변수로 확장해, 격리된 임시
RuntimeDir에 `*.pid` 파일을 직접(`Write-PidFile`을 호출하지 않고, 경로를
직접 구성해) 씨딩하고, 실행 전후 SHA256을 비교하도록 했다(이 테스트 파일
자신은 최상단에서 `RunningAI.Common.ps1`을 이미 dot-source했으므로 그
프로세스의 `$script:RuntimeDir`는 실제 운영 `.runtime`에 영구 고정되어
있다 — 그래서 PID 파일 씨딩/해시는 항상 경로를 직접 구성해서만 수행했다).

신규 Check 8건, 전부 PASS(3회 재실행 전부 결정적):

- Connector/Spring/Relay 각각 **STALE PID**(짧게 실행 후 이미 종료된
  프로세스의 PID) 씨딩 → 실행 후 `.pid` 파일 **존재 + SHA256 완전 동일**
  (보존 확인), 해당 컴포넌트 라벨이 출력에 정상적으로 나타남.
- Connector/Spring/Relay 각각 **FOREIGN PID**(실제로 살아있지만 해당
  컴포넌트의 마커를 전혀 포함하지 않는, 관련 없는 프로세스) 씨딩 → 동일하게
  **존재 + SHA256 완전 동일**(보존 확인), 그리고 그 PID가 "managed"로
  오인 보고되지 않음을 확인.
- Connector에 대해 **실제로 관리 중인(Marker가 전부 일치하는 살아있는)
  PID** 1건 씨딩 → 출력에 `"managed, PID <pid>"`로 정상 보고되고, 이
  정상 케이스에서도 `.pid` 파일이 보존됨(정상 조회 자체는 원래도 삭제를
  유발하지 않으므로 당연한 결과지만, 정상 경로가 수정으로 깨지지 않았음을
  재확인).
- 위 모든 격리 시나리오 실행 전후로 **실제 운영 `.runtime`의 진짜
  `garmin-connector.pid`/`spring.pid`/`external-relay.pid`**의 SHA256을
  직접 비교해 전혀 변경되지 않았음을 확인(이번 테스트가 실제 운영 PID 파일을
  변조하지 않았다는 가장 직접적인 증거).

모든 테스트는 디스포저블 임시 프로세스(`powershell.exe -Command "exit 0"`
또는 `Start-Sleep -Seconds 120`)만 생성·종료했으며, 실제 운영 Connector/
Spring/Relay 프로세스는 전혀 건드리지 않았다.

## 5. 전체 회귀 결과 (STEP D)

| 테스트 파일 | 결과 |
|---|---|
| Test-RunningAI.ps1 | **90/90 PASS**(기존 82 + 신규 8, 3회 재실행 전부 결정적) |
| Test-Watchdog.ps1 | 48/48 PASS |
| Test-ExternalRelay.ps1 | 16/16 PASS |
| Test-ConnectorOwnership.ps1 | 36/36 PASS |
| Test-WatchdogRecoveryPolicy.ps1 | 55/55 PASS |
| Test-MainPcPythonVenvCtrlC.ps1 | 5/5 PASS |

**합계 250/250 PASS, 0 FAIL, 0 SKIP.** 이번 Phase에서 변경하지 않은 파일에
대한 회귀(Watchdog/ExternalRelay/ConnectorOwnership/RecoveryPolicy/VenvCtrlC)도
전부 재실행해 여전히 녹색임을 재확인했다. 기존 실패 테스트를 삭제하거나
PASS로 간주한 사실 없음.

- `git diff --check`: 통과(공백 오류 없음).
- PowerShell 5.1 호환: `[System.Management.Automation.Language.Parser]::ParseFile`로
  `status-running-ai.ps1`/`Test-RunningAI.ps1` 둘 다 구문 오류 0건. PowerShell 7
  전용 연산자(`??`, `?.`) 사용 없음을 diff에서 직접 검색해 확인.
- 비밀정보 검사: `password|secret|api[_-]?key|token=...` 패턴으로 diff 전체를
  검색, 매치 없음.

## 6. 운영 무변경 검증 (STEP E)

테스트 전후 비교, 전부 동일:

- Spring `29984`, Connector Launcher `25372`/Listener `28684`, Relay
  `13588` — CreationDate 전부 `2026-10-12 00:45:08~00:45:29`로 불변.
- 실제 운영 `.runtime/*.pid` 파일: `garmin-connector.pid=25372`,
  `spring.pid=29984`, `external-relay.pid=13588` — 전부 현재 실제 살아있는
  PID와 정확히 일치, SHA256도 테스트 전후 직접 비교해 불변(위 4번 항목 마지막
  Check로 재확인).
- PostgreSQL: `8ff2a3adc495 running-ai-postgres`, `healthy`, `Up 11 hours` —
  재시작 없음. Volume `running-ai-github_running_ai_postgres_data` 불변.
- `activity` 6행, `garmin_sync_state` HighWater/LastSync 불변.
- Watchdog: `LastRunTime=2026-10-12 11:49:16`(정상 5분 주기 자동 실행,
  이번 작업과 무관), `LastTaskResult=0`. `watchdog-state.json` `version=2`,
  Restart Budget/LongTerm Lockout 5개 컴포넌트 전부 `0`/해제 — 비정상 변화
  없음.
- Canonical Git: HEAD `54aa849b0204cea2bca1649cc69b58f5baf20a1c` 그대로,
  Working Tree Clean — 이번 작업이 개발 Worktree에만 머물렀음을 재확인.

운영 서비스 재시작 0건.

## 7. 변경 파일 및 Commit SHA

- `scripts/windows/status-running-ai.ps1`: 3개 `Get-TrackedProcessId` 호출에
  `-ReadOnly` 추가(각 1줄), 헤더 주석에 설명 3줄 추가.
- `scripts/windows/tests/Test-RunningAI.ps1`: `Invoke-StatusScriptIsolated`에
  `-SeedPidFiles` 매개변수 추가(기존 호출부는 영향 없음, 하위 호환),
  Stale/Foreign/Managed PID 보존 신규 Check 8건 + 헬퍼 함수 2개 추가.
- 작업 지시서/결과 보고서 2건.

Commit SHA는 아래 커밋 직후 확정(이 문서 자체도 같은 커밋에 포함).

## 8. 다음 단계 GO/NO-GO

**GO.**

`status-running-ai.ps1`이 이제 모든 PID 상태(정상/Stale/Foreign)에서
실제로 읽기 전용임을 소스 검증과 8건의 신규 격리 테스트로 증명했고, 전체
회귀(250/250)가 녹색이며, 운영 서비스는 작업 전체 기간 동안 전혀 영향받지
않았다. Canonical Merge와 `origin/main` Push는 이번 Phase 범위 밖으로,
별도 승인 하에 다음 단계에서 고려한다.

## 지시사항 준수 확인

- Windows 재부팅·로그아웃, 실제 Relay 중지·복구, Watchdog 일반 실행·재등록,
  PostgreSQL 변경, 운영 `.env`/토큰 변경, 실제 Garmin 동기화·훈련 게시:
  전부 수행하지 않음.
- 다른 Worktree(`main-3-2`) 또는 레거시 저장소 변경: 전혀 접근하지 않음.
- Canonical Merge, `origin/main` Push: 수행하지 않음.

작업 완료. 지시서에 따라 STOP한다.
