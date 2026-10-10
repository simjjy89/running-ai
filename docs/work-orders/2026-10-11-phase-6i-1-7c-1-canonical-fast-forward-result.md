# WO-RUNNINGAI-PHASE-6I-1.7C-1 — Controlled Canonical Fast-Forward & Non-Disruptive Validation (결과 보고)

지시서 원문: [2026-10-11-phase-6i-1-7c-1-canonical-fast-forward-instruction.md](2026-10-11-phase-6i-1-7c-1-canonical-fast-forward-instruction.md)

## 1. Preflight (STEP A) PASS/FAIL

**PASS.** 11개 항목 전부 기준 상태와 일치:

| 항목 | 결과 |
|---|---|
| Canonical HEAD/브랜치 | `f6a2135`, `main` |
| origin/main / main-3 / origin/main-3 SHA | 전부 Expected와 정확히 일치 |
| Canonical 작업 트리 | Clean |
| Dev Worktree Untracked 문서 | 기존 4개 + 이번 지시서 1개만 존재(보존) |
| 포트 18080/8765/17845 | 전부 127.0.0.1 LISTEN, PID 5092/9484/10224 |
| Local Health | Spring 200, Connector 200, Relay 200 |
| PostgreSQL | `8ff2a3adc495`, healthy, Volume 불변 |
| Watchdog | `Disabled` |
| RunningAI-Startup | Logon 트리거, `IgnoreNew`, 경로 `C:\running-ai-github\scripts\windows\start-running-ai.ps1` |
| SERVER_PORT/ADDRESS | `18080` / `127.0.0.1` |
| 중복 실행 | 없음(java.exe 1개, node.exe 1개, connector 트램폴린+엔진 쌍 1개) |

STOP 조건 미해당, STEP B로 진행.

## 2. Git Hook/자동 배포 위험 검사

`C:\running-ai-github\.git\hooks\`에 `.sample` 플레이스홀더만 존재 — **활성 Hook 없음**. `git merge --ff-only`로 인한 자동 배포/서비스 재시작 트리거는 구조적으로 존재하지 않음을 확인했다.

**주의 사항(위험은 아니나 기록)**: `RunningAI-Startup` 작업은 Canonical 디스크 경로(`C:\running-ai-github\scripts\windows\start-running-ai.ps1`)를 직접 참조하므로, Fast-forward 직후 그 작업이 (로그온 등으로) 실행되면 **새 코드**(Runtime Lock 포함)로 실행된다. 다만 모든 서비스가 이미 Healthy 상태이므로 기존 멱등성 로직("이미 Healthy면 재시작하지 않음")에 의해 안전하게 No-op으로 끝날 것으로 판단된다 — 이번 Phase에서는 실제로 트리거되지 않았다(아래 15번 항목 확인).

## 3. 변경 파일 및 영향 분석

`f6a2135..36e1e96` 사이 27개 파일(6,377줄 추가/83줄 삭제):

- **수정된 실행 스크립트**(8개): `RunningAI.Common.ps1`, `RunningAI.Watchdog.ps1`, `Send-CtrlC.ps1`, `external/start-external-relay.ps1`, `external/stop-external-relay.ps1`, `start-running-ai.ps1`, `stop-running-ai.ps1`, `watch-running-ai.ps1`.
- **신규 실행 스크립트**(2개): `RunningAI.ConnectorOwnership.ps1`, `clear-watchdog-lockout.ps1`.
- **신규/수정 테스트**(5개), **신규 아키텍처 문서**(1개), **작업 지시서/결과 문서**(13개).

**실행 중 프로세스 대비**: 현재 운영 중인 Spring/Connector/Relay 프로세스는 `f6a2135` 이전 코드로 기동되었으며, Fast-forward는 **디스크상의 파일만** 갱신한다 — 이미 실행 중인 프로세스의 동작에는 어떤 영향도 주지 않는다(코드는 프로세스 시작 시점에만 읽힌다).

**Runtime State v1 보존 가능 여부**: 가능 — `.runtime/`과 `.env`는 `.gitignore`에 의해 **Git 추적 대상이 전혀 아니므로**(`git check-ignore`로 확인) `git merge`가 이 파일들을 절대 건드리지 않는다.

**PID/Sidecar 처리**: 기존 `.pid` 파일 3개 그대로 존재, `.owner.json` 사이드카는 아직 생성된 적 없음(새 소유권 모델이 실제 운영 프로세스에 대해 한 번도 실행되지 않았기 때문 — 정상).

## 4. Rollback 계획

- **Git 복귀 기준점**: Old SHA `f6a2135`, New SHA `36e1e96`. 필요 시 `git reset --hard f6a2135`로 복귀 가능하나, **이번 Phase에서는 수행하지 않았고 자동화하지도 않았다**.
- **코드 롤백이 실행 중 프로세스를 자동으로 되돌리지 않는다는 점**: 명시적으로 확인 — Git 롤백은 디스크 파일만 되돌리며, 이미 실행 중인 Spring/Connector/Relay는 영향받지 않는다. 서비스 버전을 되돌리려면 별도로 중지/재시작이 필요하다(이번 Phase에서는 수행하지 않음).
- **변경 이후 생성된 Runtime State**: 이번 Phase 동안 `.runtime/watchdog-state.json`은 전혀 쓰이지 않았다(Watchdog Disabled, Fast-forward는 `.runtime/`을 건드리지 않음) — 롤백 시 고려할 "새로 생성된 State"는 없다.
- **PostgreSQL 데이터/Volume**: 이번 Phase에서 전혀 건드리지 않았으므로 롤백 논의 대상이 아니다.
- 별도 승인 전까지 `git reset --hard`/`git clean`/Force Push는 사용하지 않았으며 앞으로도 자동 수단으로 사용하지 않는다.

## 5. Fast-forward 실행 여부 및 결과

**실행함, 성공.** `git merge --ff-only origin/main-3` 1회 실행, `Fast-forward` 메시지로 완료(Merge Commit 없음, Rebase 없음, Force 없음, 신규 커밋 없음, `origin/main` Push 없음).

```
Updating f6a2135..36e1e96
Fast-forward
 27 files changed, 6377 insertions(+), 83 deletions(-)
```

## 6. main / origin/main SHA

- `main` HEAD: `36e1e96cc92a7c6f1d808b243bee19bc9a316abf` (목표와 정확히 일치)
- `origin/main`: `f6a213588c5b7855b0dc7bf99948896291240ed7` (변경 없음 — 이번 Phase에서 정상)

## 7. main-3 / origin/main-3 SHA

둘 다 `36e1e96cc92a7c6f1d808b243bee19bc9a316abf` — Fast-forward 이후에도 변동 없음(이번 작업이 main-3에 어떤 영향도 주지 않았음을 확인).

## 8. Canonical Working Tree 상태

Fast-forward 직후 `git status --porcelain=v1 --untracked-files=all` 결과 **완전히 비어 있음**(Clean) — 추적 파일 변경 없음, 예상치 못한 Untracked 파일 없음.

## 9. Spring/Connector/Relay PID 불변 여부

전부 불변 — Fast-forward 전후 CreationDate까지 완전히 동일:

| 컴포넌트 | PID | CreationDate (불변 확인) |
|---|---|---|
| Spring | 5092 | 2026-10-10 02:42:54 |
| Connector (Launcher) | 30876 | 2026-10-10 02:42:51 |
| Connector (Listener) | 9484 | 2026-10-10 02:42:51 |
| External Relay | 10224 | 2026-10-10 02:43:11 |

## 10. Local/Public API 상태

- Spring `/actuator/health`: Local 200, `UP`.
- Connector `/health`: Local 200, `{"status":"UP","service":"garmin-connector"}`.
- External Relay `/health`: Local 200, Public(`https://laptop-mmdqucic.tail4597a5.ts.net/health`) 200, 둘 다 `{"status":"ok"}`.
- `/today-workout` (인증, Bearer 토큰 — 값은 출력하지 않음): Local 200, Public 200, **응답 본문 완전 일치**, `status=NO_PLAN`(정상 API 응답, 실패 아님 — 지시사항대로 분류).
- `/today-workout` 무인증: Local 401, Public 401 — 정상.

## 11. PostgreSQL 컨테이너/Volume 보존

`8ff2a3adc495`, `Up 29h (healthy)`, Volume `running-ai-github_running_ai_postgres_data` — Fast-forward 전후 완전히 동일.

## 12. Watchdog Disabled 유지

Fast-forward 전후 모두 `RunningAI-Watchdog` = `Disabled` 확인.

## 13. .env/PID/Sidecar/State 보존

- `.env`: LastWriteTime `2026-10-10 02:41:51`(서비스 기동 이전 시점) — Fast-forward로 전혀 변경되지 않음(애초에 Git 추적 대상 아님).
- PID 파일 3개: LastWriteTime 전부 서비스 기동 시점 그대로.
- `watchdog-state.json`: LastWriteTime `2026-10-10 12:53:30`, `version: 1` — 완전히 불변. **실제 v2 마이그레이션은 수행하지 않았다.**

## 14. 남은 Ctrl+C 및 Forced Shutdown 위험

Phase 6I-1.7C-0에서 보고한 내용과 동일 — 이번 Phase는 Git 코드 반영만 수행했고 어떤 종료 동작도 실행하지 않았으므로 위험 수준에 변화 없음:

- 이 Main PC의 Ctrl+C 콘솔 신호 전달이 샌드박스/환경 특성상 불안정함(6I-1.7B-2B~6I-1.7C-0에서 반복 확인). Forced Fallback은 PowerShell 스탠드인과 실제 Python 3.12 venv 프로세스 양쪽에서 안전함이 증명되어 있다.
- 신규 코드가 디스크에 반영되었을 뿐 아직 **한 번도 실행되지 않았으므로**, 실제 운영 Connector/Spring/Relay에 대해 새 Lock/소유권/Ctrl+C 로직이 작동하는지는 이번 Phase에서 검증되지 않았다 — 이는 명시적으로 다음 Phase(Watchdog 활성화/재시작 검증)의 범위다.

## 15. Phase 6I-1.7C-2 진입 GO/NO-GO

**GO.**

- Git Fast-forward가 안전하게, 1회, 추가 커밋/Merge Commit/Force 없이 완료되었다.
- 운영 서비스(Spring/Connector/Relay/PostgreSQL)는 PID·CreationDate·Health 응답까지 완전히 불변임을 확인했다.
- Public/Local API(인증 포함)가 Fast-forward 전후 동일하게 정상 응답한다.
- Watchdog는 계속 Disabled 상태이며, Git Hook이나 자동 배포로 인한 의도치 않은 서비스 재시작은 발생하지 않았다.
- **단, 이 GO 판정은 "코드가 안전하게 Canonical에 반영되었다"는 것만 의미한다 — 신규 코드(Runtime Lock, 신뢰 PID 검증, 장기 Lockout 등)가 실제 운영 환경에서 Watchdog 활성화/수동 재시작을 통해 실제로 작동하는지는 아직 검증되지 않았다.** 이 검증과 Watchdog 재활성화 승인은 명시적으로 별도 Phase(6I-1.7C-2 이후)에서 진행한다.

**금지사항(STEP G) 전체 준수 확인**: start/stop/watch 스크립트 실행 없음, RunningAI-Startup 수동 실행 없음, Watchdog 활성화 없음, 서비스 재시작 없음, 운영 포트 테스트 없음(단, 기존에 이미 실행 중인 서비스에 대한 읽기 전용 HTTP GET 호출만 수행 — 이는 STEP F가 명시적으로 요구한 검증이며 "운영 포트를 사용하는 테스트 실행" 금지 조항은 신규 테스트 하네스 실행을 의미하는 것으로 해석했다), 재부팅 없음, Docker Compose 없음, PostgreSQL 변경 없음, .env 수정 없음, State 마이그레이션 없음, PID/Sidecar 변경 없음, Garmin 동기화 없음, 훈련 생성/게시 없음, `git reset/clean/stash` 없음, `origin/main` Push 없음, Legacy 경로 접근 없음.
