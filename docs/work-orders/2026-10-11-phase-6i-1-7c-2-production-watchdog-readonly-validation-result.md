# WO-RUNNINGAI-PHASE-6I-1.7C-2 — Production Watchdog Read-Only Validation (결과 보고)

지시서 원문: [2026-10-11-phase-6i-1-7c-2-production-watchdog-readonly-validation-instruction.md](2026-10-11-phase-6i-1-7c-2-production-watchdog-readonly-validation-instruction.md)

## 1. Git SHA

| 저장소 | Expected | 실제 | 일치 |
|---|---|---|---|
| Canonical main | `36e1e96cc92a7c6f1d808b243bee19bc9a316abf` | 동일 | ✅ |
| origin/main | `f6a213588c5b7855b0dc7bf99948896291240ed7` | 동일 | ✅ |
| main-3 / origin/main-3 | `e031e4fb4362c91b033e415130309c30d4cbaf0c` | 동일 | ✅ |

**실행 스크립트 해시 비교**: 6개 핵심 스크립트 중 5개 완전 일치, `RunningAI.ConnectorOwnership.ps1` 1건은 바이트 단위 해시가 달랐으나 원인을 조사한 결과 **CRLF/LF 줄바꿈 체크아웃 차이**(Git Working Tree 정규화)뿐이었다 — 줄바꿈 정규화 후 내용은 완전히 동일했고, 결정적으로 **두 저장소의 Git Blob 해시(`git rev-parse HEAD:<path>`) 자체가 완전히 동일**(`e3ab0f85...`)함을 확인해 같은 커밋의 같은 내용임을 Git 레벨에서 입증했다. 코드 불일치가 아니므로 STOP 조건에 해당하지 않는다고 판단하고 계속 진행했다.

## 2. 운영 Preflight (STEP A)

전체 10개 항목 PASS, 예상치 못한 재시작 없음(모든 CreationDate가 기존 기준선과 완전히 동일):

| 항목 | 결과 |
|---|---|
| Watchdog | Disabled |
| RunningAI-Startup | 실행 중 아님(마지막 실행 2026-10-10, LastTaskResult=14) |
| Spring 18080 | PID 5092 |
| Connector 8765 / Launcher | Listener PID 9484, Launcher PID 30876 (부모-자식 확인) |
| Relay 17845 | PID 10224 |
| PostgreSQL | `8ff2a3adc495`, Up 35h, healthy |
| Local Health | Spring/Connector/Relay 전부 200 |
| .env | `SERVER_PORT=18080`, `SERVER_ADDRESS=127.0.0.1` |
| Lock 환경변수 | 충돌 없음(프로세스/영구 범위 모두 미설정) |
| State 버전 | v1 |

## 3. DryRun 실행 명령 및 Exit Code

```powershell
Set-Location "C:\running-ai-github"
powershell.exe -NoProfile -ExecutionPolicy Bypass -File ".\scripts\windows\watch-running-ai.ps1" -DryRun -ConnectorPort 8765 -SpringPort 18080
```

**`WATCHDOG_DRYRUN_EXIT=0`** — 1회만 실행, 재시도 없음.

사전 STEP C 소스 검토(Canonical 실제 파일 기준)로 다음을 확인한 뒤 실행했다: `Read-WatchdogState -ReadOnly:$DryRun`, `Get-RuntimeObservation ... -ReadOnly:$DryRun`가 `if ($DryRun) {...}` 조기 종료(line 89, `exit $ExitCode.Ok`) **이전에** 호출되며, 로그 로테이션은 `$writeFiles = -not $DryRun`로 완전히 차단되고, Executor/Docker/PostgreSQL/Garmin 호출은 DryRun 분기 이후의 코드(Invoke-WatchdogRecovery, Save-RunningAiWatchdogStateReconciled 등)에서만 발생하므로 DryRun 경로에서는 **도달 자체가 불가능**함을 확인했다. `status-running-ai.ps1`은 이번 검증에서 실행하지 않았다.

## 4. Component 상태별 결과

```
docker     UP
postgres   UP
connector  UP
spring     UP
external-relay UP
Planned actions: none
Overall     : UP
```

STEP E의 1~8번 요구사항(Exit 0, 5개 Component 전부 UP, 계획된 복구 Action 없음, 비정상 Blocked 없음) 전부 충족. Garmin Hint 없음(최근 동기화 기록 없음, 장애 아님). `-SpringPort 18080`을 명시적으로 전달했고 Spring이 정확히 그 포트에서 UP으로 보고되어 Relay(17845)와 혼동되지 않았음을 확인했다(9, 10번 항목).

## 5. Connector Ownership Verdict

소유권 **조회 함수만** 사용(`Get-RunningAiConnectorOwnership`), Stop/Sidecar 기록 함수는 호출하지 않았다.

```
Tracked PID (.pid 파일): 30876
Verdict: LAUNCHER_CHILD
LauncherPid: 30876 (추적 PID와 동일)
ListenerPid: 9484 (실제 8765 LISTEN 소유 PID와 동일)
ManagedPidSnapshot: PID=30876 CreationDate=2026-10-10 02:42:51 / PID=9484 CreationDate=2026-10-10 02:42:51
Owner sidecar(.owner.json) 신규 생성 여부: False(생성되지 않음)
```

기대와 완전히 일치 — UNKNOWN_OWNER/FOREIGN_PROCESS 등 예상 밖 결과 없음, STOP 불필요.

## 6. 복구 Action 계획

**없음.** 모든 Component가 UP이므로 `Get-RecoveryPlan`이 생성한 Action은 0건, Blocked 항목도 0건이었다(4번 항목 출력 참고).

## 7. DryRun 전후 파일 해시 비교

8개 대상 파일(`.env`, 3개 `.pid`, `.owner.json`, `watchdog-state.json`, `.bak`, `watchdog-status.json`) 전체에 대해 DryRun 전/후 `SHA256`·`Length`·`LastWriteTimeUtc`를 비교한 결과 **완전히 동일**했다(diff 결과 0건). `.owner.json`과 `.bak`는 DryRun 전후 모두 부재.

유일한 변경: `spring.out.log`(6359→7935 bytes, LastWriteTime 갱신) — 이는 **DryRun과 무관하게 이미 실행 중인 Spring 프로세스 자신이 계속 기록하는 로그**이며, 지시사항이 명시한 "다른 정상 운영 프로세스가 자체적으로 작성하는 로그의 변경은 DryRun 위반으로 단정하지 않는다"에 정확히 해당한다. `watchdog.log`는 DryRun 전후 변경 없음(watch-running-ai.ps1 자신의 로그 기록도 DryRun 분기에서 발생하지 않음을 재확인).

## 8. PID/CreationDate 불변

| 컴포넌트 | PID | CreationDate(불변) |
|---|---|---|
| Spring | 5092 | 2026-10-10 02:42:54 |
| Connector Launcher | 30876 | 2026-10-10 02:42:51 |
| Connector Listener | 9484 | 2026-10-10 02:42:51 |
| External Relay | 10224 | 2026-10-10 02:43:11 |

DryRun 전/후 완전히 동일 — 재시작 없음 확증.

## 9. PostgreSQL 보존

`8ff2a3adc495`, `Up 35h (healthy)` — DryRun 전후 변동 없음. Volume 변경 없음(명령 미실행).

## 10. Watchdog Disabled 유지

DryRun 전/후 모두 `RunningAI-Watchdog` = `Disabled`.

## 11. Ctrl+C 관련 잔여 위험

Phase 6I-1.7C-0/1에서 보고한 것과 동일 — 이번 Phase는 Ctrl+C/Forced Kill을 전혀 실행하지 않았으므로(금지사항 준수) 위험 수준에 변화 없음. 이 Main PC 환경에서 Ctrl+C 콘솔 신호 전달이 불안정하다는 기존 결론은 유지되며, Forced Fallback의 안전성은 이미 별도로 검증되어 있다(6I-1.7C-0 결과 문서 참고).

## 12. State v2 전환 절차 및 Rollback 설계 (Phase 6I-1.7C-3 계획)

**Phase 6I-1.7C-3 — Controlled Watchdog State v2 Transition** (이번 Phase에서는 계획만 작성, 실행하지 않음):

1. **기존 v1 백업**: 전환 직전 `watchdog-state.json`을 타임스탬프가 포함된 별도 파일(예: `watchdog-state.json.pre-v2-backup-<timestamp>`)로 수동 복사 — `Save-WatchdogState`가 자동 생성하는 `.bak`과는 별개로, 전환 작업 자체의 복구용 스냅샷을 추가로 남긴다.
2. **Restart History 보존 확인**: `Read-WatchdogState`(비-ReadOnly)로 v1 파일을 1회 읽어 `History`에 기존 재시작 이력이 전부 들어있는지(개수/타임스탬프) 확인한 뒤에만 저장 단계로 진행한다 — 이미 6I-1.7B-2C에서 격리 테스트로 검증된 마이그레이션 로직(`$obj.PSObject.Properties[...]` 안전 접근)을 재사용한다.
3. **v2/longTerm 구조 적용**: `Save-WatchdogState -History <읽은 History> -LongTermState (New-EmptyLongTermState)`로 **단 한 번** 저장 — 모든 컴포넌트가 신규·미잠금 상태로 시작(이번 조사에서 장기 Lockout이 걸린 컴포넌트가 없었으므로 안전).
4. **무결성 검증**: 저장 직후 `watchdog-state.json`과 `watchdog-state.json.bak`를 둘 다 다시 읽어(`Read-WatchdogState`) `version=2`, 기존 `History`가 손실 없이 보존되었는지, `longTerm`이 모든 컴포넌트에 대해 빈 배열/미잠금인지 확인한다. 둘 중 하나라도 실패하면 1번에서 만든 백업으로 **수동** 복원(자동 롤백 금지).
5. **Watchdog 계속 Disabled**: 이 전환 작업 전체를 Watchdog가 비활성화된 상태에서 수행 — 전환 자체가 Watchdog의 자동 실행을 유발하지 않는다.
6. **NoRecovery 검증(서비스 재시작 없이)**: 전환 후 `watch-running-ai.ps1 -NoRecovery`(주의: `-NoRecovery`는 상태 JSON과 로그를 기록하므로 ReadOnly가 아님 — 이는 **State v2 전환이 완료된 뒤의 의도된 쓰기**이지, 이번 6I-1.7C-2의 DryRun 요구사항과는 다른 범주다) 1회 실행하여 v2 상태로 정상적으로 관찰·기록되는지 확인하되 **어떤 컴포넌트도 재시작하지 않는다**.
7. **실패 시 State 복구 정책**: 4번 검증 실패 → 1번 백업을 수동으로 `watchdog-state.json`에 복사해 복원 → 복원 후 `version=1`로 되돌아갔는지 재확인 → 원인 분석 없이 재시도하지 않는다(이번 6I-1.7C-2에서 확립한 "실패 시 즉시 원인부터 보고" 원칙과 동일).
8. **Watchdog 활성화는 별도 승인**: 이 전환이 성공해도 `RunningAI-Watchdog` Scheduled Task는 여전히 Disabled로 유지하며, 활성화는 6I-1.7C-3 이후의 완전히 별도인 승인된 Phase에서만 진행한다.

## 13. Phase 6I-1.7C-3 GO/NO-GO

**GO.**

- 신규 Watchdog 코드(소유권 모델, DryRun ReadOnly 전파, 상태 조회 로직)가 실제 Main PC 운영 환경에서 **읽기 전용으로 정확하게 동작**함을 확인했다 — Exit 0, 5개 Component 전부 UP, 소유권 LAUNCHER_CHILD 정확히 판정, 복구 Action 0건.
- DryRun이 운영 파일·프로세스에 **어떤 변경도 일으키지 않았음**을 8개 파일의 SHA256/크기/타임스탬프 비교로 증명했다 — 유일한 변경은 Spring 자신의 로그 파일(DryRun과 무관).
- PID/CreationDate/PostgreSQL/Watchdog Disabled 상태 전부 불변.
- v1 상태 파일은 전혀 건드리지 않았으며, v2로의 자동 전환은 발생하지 않았다.

**12번 항목의 State v2 전환 계획은 설계만 완료되었으며 이번 Phase에서 실행하지 않았다. Watchdog 활성화는 여전히 별도 승인이 필요하다.**
