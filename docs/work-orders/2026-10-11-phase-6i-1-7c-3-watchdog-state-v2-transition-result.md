# WO-RUNNINGAI-PHASE-6I-1.7C-3 — Controlled Watchdog State v2 Transition (결과 보고)

지시서 원문: [2026-10-11-phase-6i-1-7c-3-watchdog-state-v2-transition-instruction.md](2026-10-11-phase-6i-1-7c-3-watchdog-state-v2-transition-instruction.md)

## 1. Preflight PASS/FAIL

**PASS.** STEP A의 12개 항목 전부 기준과 정확히 일치했다: Canonical HEAD `36e1e96`(Clean), Git SHA 3종 전부 Expected 일치, Watchdog Disabled·RunningAI-Startup 미실행(둘 다 자체 쿼리의 Self-match만 확인되고 실제 프로세스 없음), Docker/PostgreSQL/Connector/Spring/Relay 전부 UP, LISTEN PID 5092/9484/10224 및 Launcher 30876 — CreationDate까지 기존 기준선과 완전 동일, Connector Ownership = LAUNCHER_CHILD, `.env`(`SERVER_PORT=18080`/`SERVER_ADDRESS=127.0.0.1`)·PID 파일 3종 정상, `watchdog-state.json` 존재·version=1·SHA256=`3863171171D7...`·Length=555·LastWriteTimeUtc=`2026-10-09T15:53:30.15Z`, `.bak` 부재(정상, 아직 한 번도 v2 저장된 적 없음), Runtime Lock/Watchdog State Lock 환경변수 충돌 없음.

## 2. 원본 State v1 백업 경로 및 SHA256

```
경로: C:\Users\simjy\.runningai-state-backups\watchdog-state.v1.pre-v2-transition.20261011-051306Z.json
SHA256: 3863171171D71FEAEA075BF493CDB1E1C28F728753F681E7BD74C6B2ABE931DA
```

원본과 백업 SHA256 완전 일치(검증 완료), 백업 파일의 `version` 필드 = `1`(확인 완료). 백업 디렉터리는 상속을 차단하고 `NT AUTHORITY\SYSTEM`과 현재 사용자(`LAPTOP-MMDQUCIC\simjy`)에게만 Full Control을 부여하도록 ACL을 재설정했다(`BUILTIN\Administrators` 등 기타 주체 제거). Canonical Git 저장소 외부(`C:\Users\simjy\...`)에 보관. 백업 생성 전 동일 경로 존재 여부를 확인해 덮어쓰지 않았다.

## 3. 기존 Restart History 개수

`Read-WatchdogState -ReadOnly`로 읽은 결과(v1 원본), 전환 전:

| 컴포넌트 | 항목 수 | 값(Unix seconds, UTC) |
|---|---|---|
| docker | 0 | - |
| postgres | 0 | - |
| connector | 2 | `1791560839`(2026-10-09T15:47:19Z), `1791561140`(2026-10-09T15:52:20Z) |
| spring | 0 | - |
| external-relay | 0 | - |

`Read-WatchdogState -ReadOnly` 파싱 결과가 원본 Raw JSON의 `restarts` 섹션과 완전히 일치함을 직접 비교로 확인했다(ReadOnly 경로는 파일을 전혀 건드리지 않음 — 이 단계에서 `.bak`/상태 파일 어디에도 쓰기가 발생하지 않았다).

## 4. v2 저장 결과

STEP D의 9단계를 순서대로 전부 수행, **단 1회의 `Save-WatchdogState` 호출**로 완료:

1. Watchdog Disabled 재확인 → 통과
2. `Enter-RunningAiWatchdogStateLock` 획득 → 성공
3. Lock 보유 상태에서 `Read-WatchdogState`(비-ReadOnly) 재읽기 → `Available=True`
4. 재읽기 직전 SHA256과 STEP 2 백업 SHA256 재대조 → 완전 일치(그 사이 다른 Writer 없음 확인)
5. 기존 History를 메모리에 그대로 보존(`Update-HistoryWindow` 호출하지 않음)
6. `New-EmptyLongTermState`로 신규 LongTermState 초기화(5개 컴포넌트 전부 failures=[], lockedOutSince=null)
7. `Save-WatchdogState -History <보존된 History> -LongTermState <신규>` 실행 → `version=2`로 저장
8. 저장 직후 재읽기+검증: version=2, History 완전 일치, LongTermState 전부 fresh, `.bak` 존재·version=2·본문과 바이트 단위 동일
9. `finally`에서 Lock 해제

`TRANSITION_RESULT=SUCCESS`. STOP 조건(Lock 실패/백업 불일치/파싱 실패/저장 중 예외/`.bak` 저장 실패/History 불일치/version 불일치) 중 어느 것도 발생하지 않았다.

## 5. State/.bak 무결성

전환 직후(STEP E):

- `watchdog-state.json` SHA256 = `.bak` SHA256 = `D6B52DEDDB6F161FD2ACDE716A04F5A1C62960A2A338F55F36E94D56A1D7A2F6` (완전 일치)
- 둘 다 `version=2`
- `restarts`: `{"docker":[],"postgres":[],"connector":[1791560839,1791561140],"spring":[],"external-relay":[]}` — 원본과 완전 동일
- `longTerm`: 5개 컴포넌트 전부 `{"failures":[],"lockedOutSince":null}`

## 6. 기존 History 보존 결과

전환 직후(STEP E) 시점 기준 **100% 보존 확인** — connector의 2개 타임스탬프, 값까지 완전 일치. v1이 장기 실패 기록을 전혀 갖고 있지 않았으므로 과거 실패를 추측해 신규 Lockout을 생성하지 않았다(지시사항 준수).

**주의(STEP G 이후 변화, 코드상 의도된 동작)**: NoRecovery 실행 후에는 connector의 두 항목이 10분 단기 예산 창(`Update-HistoryWindow`) 만료로 정리되어 `restarts.connector=[]`가 되었다 — 두 타임스탬프 모두 2026-10-09(약 36시간 전)로, 10분 창을 훨씬 벗어난 기록이므로 정상적인 만료 정리다. **전환 자체(STEP D~E)에서는 원본 이력이 완전히 보존되었음을 먼저 증명했고, v1 백업(2번 항목)에는 원본 2개 항목이 영구히 보존되어 있다.**

## 7. NoRecovery 실행 여부 및 Exit Code

사전 안전 점검(STEP F): 로그 최대 크기 143개 파일 중 최대 28,635 bytes(≈28KB, `LogMaxSizeMB=1024`=1GB 대비 안전), 최고(最古) 로그 2026-10-02(약 9일 전, `LogRetentionDays=3650`=10년 대비 안전) — 보존 설정이 기존 로그/증거를 전혀 삭제하지 않음을 확인한 뒤 실행.

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File ".\scripts\windows\watch-running-ai.ps1" -NoRecovery -ConnectorPort 8765 -SpringPort 18080 -LogMaxSizeMB 1024 -LogRetentionDays 3650
```

**`WATCHDOG_NORECOVERY_EXIT=0`**, 1회만 실행. `overall=UP`, `lastAction=NONE` — 예상 결과와 정확히 일치.

## 8. NoRecovery 이후 정상적인 History 만료 정리 내역

connector 컴포넌트의 기존 2개 Restart 기록(2026-10-09, 10분 창 밖)이 `Update-HistoryWindow`에 의해 정리되어 `[]`가 되었다 — 6번 항목에 기술한 대로 **코드상 의도된 정상 동작**이며, 그 외 예상치 못한 이력 삭제는 없었다. LongTermState는 이 실행 동안 어떤 실패도 발생하지 않았으므로 전부 fresh/unlocked 상태를 유지했다.

## 9. watchdog-status.json 결과

```
checkedAt  : 2026-10-11T05:15:54Z
overall    : UP
lastAction : NONE
```

정상 생성/갱신 확인.

## 10. Connector Ownership Verdict

전환 전/NoRecovery 후 두 차례 모두 동일하게 확인:

```
Verdict: LAUNCHER_CHILD
LauncherPid: 30876 (.pid 파일 추적 PID와 동일)
ListenerPid: 9484 (실제 8765 LISTEN 소유 PID와 동일)
ManagedPidSnapshot: PID=30876 CreationDate=2026-10-10 02:42:51 / PID=9484 CreationDate=2026-10-10 02:42:51
Owner sidecar(.owner.json) 신규 생성: 없음(False)
```

## 11. Spring/Connector/Relay PID 불변

| 컴포넌트 | PID | CreationDate(불변) |
|---|---|---|
| Spring | 5092 | 2026-10-10 02:42:54 |
| Connector Launcher | 30876 | 2026-10-10 02:42:51 |
| Connector Listener | 9484 | 2026-10-10 02:42:51 |
| External Relay | 10224 | 2026-10-10 02:43:11 |

전환 전/후 완전히 동일 — 재시작 없음 확증.

## 12. PostgreSQL 보존 여부

`8ff2a3adc495`, `Up 38h (healthy)`, Volume 변경 없음(명령 미실행) — 작업 시작부터 종료까지 완전히 불변.

## 13. Watchdog Disabled 상태

전환 전/후/NoRecovery 실행 전/후 전부 `RunningAI-Watchdog` = `Disabled` 확인. `RunningAI-Startup`도 `LastRunTime`이 2026-10-10에서 전혀 변하지 않아 이번 작업 중 트리거되지 않았음을 확인했다.

## 14. Git 브랜치 및 Working Tree

Canonical: 브랜치 `main`, HEAD `36e1e96`(불변), `git status --porcelain` 완전히 비어 있음(Clean) — 이번 Phase는 `.runtime/` 데이터 파일만 변경했으며 Git 추적 대상이 아니므로 Working Tree는 작업 시작부터 끝까지 Clean 상태를 유지했다.

## 15. 운영 변경 파일 목록

| 파일 | 변경 내용 |
|---|---|
| `C:\running-ai-github\.runtime\watchdog-state.json` | v1 → v2(Restart History 보존, longTerm 신규), 이후 NoRecovery로 단기 창 만료 정리 적용 |
| `C:\running-ai-github\.runtime\watchdog-state.json.bak` | 신규 생성(v2, 메인과 항상 동일) |
| `C:\running-ai-github\.runtime\watchdog-status.json` | NoRecovery 실행으로 갱신(overall/lastAction 등, 비밀정보 없음 확인) |
| `C:\running-ai-github\.runtime\logs\watchdog.log` | NoRecovery 실행 로그 1줄 추가(기존 이력 삭제 없음, 2026-10-09의 과거 장애 로그 그대로 보존) |
| `C:\Users\simjy\.runningai-state-backups\watchdog-state.v1.pre-v2-transition.20261011-051306Z.json` | 신규(Canonical 저장소 외부, 2번 항목) |

그 외 `.env`, PID 파일 3종, `.owner.json`(미생성), Spring/Connector/Relay 프로세스, PostgreSQL, Canonical Git 추적 파일은 전혀 변경되지 않았다.

## 16. 잔여 위험 및 Rollback 준비 상태

- **Rollback 준비**: 2번 항목의 v1 백업이 복원 가능한 상태로 보존되어 있다. 복원이 필요하면 `Enter-RunningAiWatchdogStateLock` 획득 → 다른 Writer 없음 확인 → 백업 파일을 `watchdog-state.json`에 수동 복사 → `version=1` 환원 확인 → Lock 해제 절차를 사용한다(자동화하지 않음, 지시사항 준수).
- **Ctrl+C 관련 위험**: 변화 없음(이번 Phase는 Ctrl+C/Forced Kill을 전혀 실행하지 않았다) — 기존 6I-1.7C-0/1/2 보고 내용 유지.
- **장기 Lockout 임계값 미검증**: 이번 전환으로 모든 컴포넌트가 "fresh/unlocked" 상태로 시작했으나, 실제 장기 실패 임계값(24시간/6회)이 운영 환경에서 올바르게 트리거되는지는 아직 실제로 관찰된 바 없다 — Watchdog가 비활성 상태이므로 당연하며, 활성화 이후에만 검증 가능하다.
- **State v2 전환 자체의 성공이 Watchdog 자동 복구 기능의 운영 안전성을 의미하지 않는다**(지시사항 명시) — 이는 Watchdog 활성화 승인 이후 별도로 검증해야 한다.

## 17. Phase 6I-1.7D 진입 GO/NO-GO

**GO** (State v2 전환 성공이라는 한정된 범위 내에서).

- v1 → v2 전환이 Lock 보호 하에 단 1회, 무결성 검증을 통과하며 완료되었다.
- 기존 Restart History가 전환 시점에 손실 없이 보존되었음이 증명되었고, 영구 백업도 확보되었다.
- NoRecovery 1회 실행이 예상대로 Exit 0, 복구 Action 0건으로 완료되었으며, 그 이후의 History 변화는 전부 사전에 명시된 정상 창 만료 정리였다.
- 운영 서비스(Spring/Connector/Relay/PostgreSQL)는 PID·CreationDate·Health까지 완전히 불변이었다.
- Watchdog Scheduled Task는 시작부터 끝까지 Disabled를 유지했다.

**이 GO 판정은 State v2 전환의 성공만을 의미하며, Watchdog 자동 복구 기능 자체가 실제 운영에서 안전하게 작동한다는 최종 판정이 아니다.** Watchdog 활성화 및 실제 자동 복구 검증은 별도의 승인된 Phase(6I-1.7D 이후)에서 진행해야 한다.
