# WO-RUNNINGAI-PHASE-6I-1.7D-2M — Canonical Fast-Forward & Pre-Reboot Baseline Freeze (결과 보고)

지시서: `2026-10-12-phase-6i-1-7d-2m-canonical-ff-pre-reboot-baseline-instruction.md`

실제 재부팅이나 장애 주입은 수행하지 않았다.

## 1. 사전 SHA

- Canonical `main` (FF 전): `36e1e96cc92a7c6f1d808b243bee19bc9a316abf` (기대값과 일치)
- `origin/main`: `f6a213588c5b7855b0dc7bf99948896291240ed7` (기대값과 일치)
- 개발 Worktree(`main-3`) / `origin/main-3`: `43ed6af2049e1564fb35b76dbd8e22d99774ec19`
  (기대값과 일치)
- Canonical Tracked Working Tree: Clean. 기존 Untracked/Ignored 파일(`.env`,
  `.runtime/`, `tools/external-relay/secrets/` 등) 전부 보존 확인.

## 2. 변경 파일 및 영향 범위

`HEAD..origin/main-3` 전체 15개 파일, 전부 다음 두 경로 중 하나에만 속함(사전 검증한
정규식 `^(docs/work-orders/|scripts/windows/tests/)`와 100% 일치, 벗어난 파일 0건):

- `docs/work-orders/*` — Phase 6I-1.7C-1부터 6I-1.7D-2R까지의 작업 지시서/결과 보고서
  13개 (문서만, 실행 코드 아님).
- `scripts/windows/tests/Test-MainPcPythonVenvCtrlC.ps1` — Phase 6I-1.7D-2R의 결정론적
  Forced-fallback Fixture 수정.
- `scripts/windows/tests/Test-WatchdogRecoveryPolicy.ps1` — Phase 6I-1.7D-2의 격리 복구
  정책 신규 Check 5건 추가.

Production 런타임 코드(`RunningAI.Watchdog.ps1`, `RunningAI.ConnectorOwnership.ps1`,
`RunningAI.Common.ps1`, `watch-running-ai.ps1`, `start-running-ai.ps1` 등)와 Flyway
마이그레이션·설정 파일은 이번 Fast-forward 범위에 전혀 포함되지 않았다.

## 3. Fast-forward 실행 결과

지시서 STEP B의 PowerShell 블록을 그대로, 한 글자도 바꾸지 않고 실행했다.

1. `git merge-base --is-ancestor HEAD origin/main-3` → 0 (Fast-forward 가능).
2. 변경 경로 검사 → 범위를 벗어난 파일 0건.
3. `git diff --check HEAD origin/main-3` → 0 (공백 오류 없음).
4. `git merge --ff-only origin/main-3` → `Fast-forward`, 병합 커밋 없이 정상 완료.

`origin/main` Push는 수행하지 않았다.

## 4. 사후 Git SHA

Canonical `main` HEAD: `43ed6af2049e1564fb35b76dbd8e22d99774ec19` — 목표 SHA와 정확히 일치.
Working Tree는 Fast-forward 직후에도 여전히 Clean.

## 5. Watchdog 정상 실행 여부

Fast-forward 전후 동일하게 확인:

- Scheduled Task 상태: `Ready`(Enabled) — 변경 없음.
- `LastRunTime=2026-10-12 07:44:16`, `LastTaskResult=0` — Fast-forward 작업(순수 Git
  명령, 파일 시스템의 `.runtime` 디렉터리나 Scheduled Task에는 전혀 영향 없음) 전후로
  동일한 마지막 성공 기록을 그대로 유지.
- 로그 최근 3줄 전부 `state=UP action=NONE result=OK` — Fast-forward 전후 동일.
- `watchdog-state.json`: `version=2`, 모든 컴포넌트 `restarts=0`, `longTerm.failures=0`,
  `lockedOutSince` 공란 — Restart Budget/Lockout 비정상 증가 없음.

(참고: 지시서 STEP C의 안내대로, 5분 주기 정상 로그/상태 파일 갱신 자체는 실패 판정
기준으로 사용하지 않았다 — 실제 Recovery Action 발생 여부와 예산 수치만으로 판정했다.)

## 6. PID/DB/PostgreSQL 불변 여부

Fast-forward 전후 완전히 동일(둘 다 같은 스냅샷):

- Spring `29984`, Connector Launcher `25372`/Listener `28684`, Relay `13588` —
  CreationDate 전부 `2026-10-12 00:45:08~00:45:29`.
- PostgreSQL: `8ff2a3adc495 running-ai-postgres`, healthy, "Up 7 hours" — 재시작 없음.
- 애플리케이션 DB: `activity` 6행, `garmin_sync_state`
  `high_water_started_at=2026-10-05 15:02:39+00`,
  `last_successful_sync_at=2026-10-05 16:24:14.229834+00` — 불변.

## 7. 인증 Local/Public API 상태

- Local Health: Spring/Connector/Relay 전부 `200 OK` (Fast-forward 전후 동일).
- 인증 `GET http://127.0.0.1:17845/today-workout` (Bearer 토큰, 값은 출력하지 않음):
  Fast-forward 전후 모두 `200 OK`.

## 8. 재부팅 전 체크리스트 (STEP D — 계획만 작성, 미실행)

다음 Phase(실제 PC 재부팅을 통한 전체 E2E 자동 복구 검증, 별도 승인 필요)를 위한 사전
점검 항목 초안이다. 이번 작업에서는 이 계획만 작성했으며 어떤 항목도 실행하지 않았다.

1. **서비스 및 데이터 백업 기준**: 재부팅 직전 `watchdog-state.json`/`.bak`의 SHA256과
   `C:\Users\simjy\.runningai-state-backups\`의 최신 외부 백업이 최신 상태인지 확인하고,
   필요하면 재부팅 직전 스냅샷을 추가로 1건 더 남긴다(기존 백업을 덮어쓰지 않음). 애플리케이션
   DB는 PostgreSQL 볼륨 자체가 영속 저장소이므로 별도 백업 없이 볼륨 존재만 재확인한다.
2. **재부팅 직전 건강 상태 기록**: 이번 보고서의 5~7번 항목과 동일한 형식으로 PID/
   CreationDate/Health/DB 기준선/Watchdog State를 재부팅 "바로 직전" 시점 기준으로 다시
   한 번 캡처해, 재부팅 후 비교할 최종 기준선으로 삼는다.
3. **RunningAI-Startup Logon 자동 기동 순서**: `RunningAI-Startup`(Watchdog와는 독립된
   별도 AtLogOn Task)이 재부팅 후 로그인 시 Docker Desktop → PostgreSQL → Connector →
   Spring → Relay 순서로 정상 기동하는지, 그 `LastTaskResult`가 0인지 확인하는 절차를
   사전에 정의한다. Phase 6I-1.7D-1A에서 이미 이 Task가 로그온 이벤트에 반응해 전체 스택을
   재시작시키는 것을 직접 관측한 바 있다(6I-1.7D-1A 결과 보고서 참고) — 재부팅도 로그온을
   동반하므로 동일한 동작이 예상된다.
4. **Docker Desktop 및 PostgreSQL 자동 복구 조건**: Docker Desktop이 Windows 시작 시
   자동 실행되도록 설정되어 있는지, PostgreSQL 컨테이너가 `restart: unless-stopped` 등
   compose 정책에 따라 Docker Desktop 기동 후 자동으로 healthy 상태에 도달하는지 확인하는
   절차와 예상 소요 시간을 사전에 정의한다.
5. **Garmin Connector Launcher/Listener 소유권 판정**: 재부팅 후 재기동된 Connector의
   새 PID 쌍에 대해 `Get-RunningAiConnectorOwnership`이 `LAUNCHER_CHILD`로 정상 분류하는지
   확인하는 읽기 전용 점검 절차를 정의한다(이번 Phase에서 이미 검증된 동일한 로직,
   재부팅 후에도 같은 코드가 그대로 적용됨).
6. **Spring 18080 및 Relay 17845 Health**: 재부팅 후 두 Health 엔드포인트가 `200 OK`를
   반환하기까지의 예상 대기 시간(Spring은 JVM 기동 시간, Relay는 Node 기동 시간)을
   가늠하고, 조급하게 "DOWN"으로 오판하지 않도록 재시도/대기 기준을 사전에 정한다.
7. **Tailscale Funnel 외부 HTTPS**: 외부 공개 HTTPS 엔드포인트가 재부팅 후 Tailscale
   서비스/Funnel이 자동으로 재연결되는지, 재연결까지 걸리는 시간과 확인 방법(퍼블릭 URL로
   읽기 전용 Health 요청)을 사전에 정의한다.
8. **Watchdog 로그인 후 5분 지연 및 반복 실행**: 이번 Phase 6I-1.7D-1/1A에서 이미
   실증적으로 확인한 "로그인 이벤트 후 `Delay=PT5M`, 이후 `Interval=PT5M` 반복" 동작이
   재부팅이라는 또 다른 형태의 로그인 이벤트에서도 동일하게 재현되는지 관찰할 시점(로그인
   후 약 5분, 10분, 15분)을 사전에 정의한다.
9. **PostgreSQL DB 영속성**: 재부팅 전후 `activity` 테이블 행 수, `garmin_sync_state`의
   HighWater/LastSync 값이 정확히 동일하게 유지되는지(볼륨이 Docker Desktop 재시작에도
   살아남는지) 확인하는 절차를 정의한다 — 이번 보고서의 6번 항목과 동일한 쿼리를 재부팅
   후에도 그대로 재사용한다.
10. **재부팅 후 Public/LTE/5G 및 FR265 검증**: 외부 네트워크(가정 Wi-Fi가 아닌 LTE/5G
    등 별도 네트워크 경로)에서 퍼블릭 엔드포인트 접근이 가능한지, 실제 Garmin Forerunner
    265 기기와의 연동(워크아웃 동기화 등 읽기 전용 확인 가능한 범위)이 영향받지 않았는지
    확인하는 절차를 사전에 정의한다(실제 Garmin 동기화나 훈련 게시는 이번 또는 다음
    Phase 어디에서도 수행하지 않는다 — 금지 항목).
11. **자동 복구 실패 시 수동 대응 및 Rollback**: 재부팅 후 특정 구성요소가 자동으로
    복구되지 않을 경우의 수동 대응 순서(예: Docker Desktop 수동 기동, `start-running-ai.ps1`
    수동 1회 실행)와, 이번 Fast-forward 자체를 되돌려야 하는 극단적 상황에 대비한 Rollback
    절차(Canonical을 이전 SHA `36e1e96c...`로 되돌리는 것은 코드 레벨의 Rollback일 뿐,
    운영 서비스 자체의 복구 절차와는 별개임을 명확히 구분)를 사전에 정의한다.
12. **비밀정보·환경변수 보존 확인**: 재부팅 전후 `.env`, `tools/external-relay/secrets/
    watch-token.json`, Garmin Connector의 세션/토큰 파일 등 Git에 추적되지 않는 민감
    파일들이 재부팅 과정에서 전혀 변경되지 않았음을(파일 존재 여부와 SHA256만, 내용은
    출력하지 않고) 확인하는 절차를 정의한다.

이 계획은 어디까지나 "무엇을, 어떤 순서로, 어떤 기준으로 확인할 것인가"를 정의한 것이며,
실제 재부팅은 사용자의 별도 승인을 받은 후에만 수행한다.

## 9. 잔여 위험

- 이번 Phase는 Git 수준의 Fast-forward와 운영 불변성 재확인에 한정된다. 실제 PC 재부팅을
  통한 전체 자동 기동/복구 E2E 검증은 아직 한 번도 수행되지 않았으며, 이는 명시적으로
  범위 밖이다.
- Phase 6I-1.7D-1A에서 이미 관측한 바와 같이, 재부팅(로그온 이벤트 포함)은
  `RunningAI-Startup`의 독립적인 AtLogOn 트리거를 동반 기동시켜 전체 서비스 스택의 PID가
  바뀌는 것이 정상적이고 예상된 동작이다 — 다음 Phase에서 "PID 변경"을 결함으로 오인하지
  않도록 이 사실을 재확인했다.
- `Test-MainPcPythonVenvCtrlC.ps1`의 Forced-fallback 관련 사전 이슈(Phase 6I-1.7D-2R에서
  수정 완료)는 이번 Fast-forward로 Canonical에 함께 반영되어 더 이상 잔여 위험이 아니다.

## 10. Phase 6I-1.7D-3 진입 GO/NO-GO

**GO.**

Fast-forward가 운영 서비스/PID/PostgreSQL/애플리케이션 데이터/Watchdog 상태에 어떠한
영향도 주지 않고 정확히 목표 SHA로 완료되었고, 변경 범위가 사전에 승인된 두 경로(문서,
테스트)로만 제한되어 있음을 직접 재검증했다. STEP D의 재부팅 전 체크리스트 초안도
작성되어, 실제 재부팅 승인이 내려지면 바로 사용할 수 있는 상태다.

## 지시사항 준수 확인

- 운영 서비스 재시작, Watchdog 활성화 상태 변경·재등록, 장애 주입: 수행하지 않음.
- PC 재부팅/로그아웃: 수행하지 않음.
- 운영 State/Sidecar 임의 수정, Docker Compose down/up, PostgreSQL 변경: 수행하지 않음.
- Garmin 동기화/훈련 게시: 수행하지 않음.
- Git Reset/Clean/Force Push: 수행하지 않음(순수 `fetch`+`merge --ff-only`만 수행).
- `origin/main` Push: 수행하지 않음.
- Legacy(`C:\running-ai`) 저장소: 전혀 접근하지 않음.

작업 완료. 지시서에 따라 STOP한다.
