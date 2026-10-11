# WO-RUNNINGAI-PHASE-6I-1.7D-3 — Controlled PC Reboot & Automatic Runtime Recovery E2E (결과 보고, 진행 중)

지시서: `2026-10-12-phase-6i-1-7d-3-controlled-reboot-e2e-instruction.md`

이 문서는 STEP A~D까지의 결과를 재부팅 전에 디스크에 고정한 것이다. 재부팅 이후
STEP E~J의 결과는 이 문서의 뒷부분에 이어서 추가된다(기존 내용은 수정하지 않음).

## STEP A — 재부팅 전 Preflight 결과 (전부 PASS)

1. **Git**: Canonical HEAD `43ed6af2049e1564fb35b76dbd8e22d99774ec19`(기대값과 일치),
   branch `main`, Working Tree Clean.
2. **Docker daemon**: 정상 응답 (Server Version 29.8.1).
3. **PostgreSQL**: `8ff2a3adc495 running-ai-postgres`, `Up 7 hours`, Health `healthy`,
   Volume `running-ai-github_running_ai_postgres_data`(+ 개발 Worktree용
   `main-3_running_ai_postgres_data`도 별도 존재, 운영과 무관).
4. **Spring** `127.0.0.1:18080/actuator/health`: `200`.
5. **Connector** `127.0.0.1:8765/health`: `200`. 소유권: `LAUNCHER_CHILD`
   (Launcher PID `25372`, Listener PID `28684`, 둘 다 CreationDate
   `2026-10-12 00:45:08`).
6. **Relay** `127.0.0.1:17845/health`: `200`.
7. **Tailscale**: 서비스 `Running`/`Automatic`. Funnel 대상:
   `https://laptop-mmdqucic.tail4597a5.ts.net` → `http://127.0.0.1:17845` (On).
8. **Public HTTPS** `https://laptop-mmdqucic.tail4597a5.ts.net/health`: `200`.
9. **인증 Public** `GET /today-workout`(Bearer, 값 미출력): `200`.
10. **RunningAI-Startup**: `State=Ready`. Action:
    `powershell.exe -NoProfile -ExecutionPolicy Bypass -WindowStyle Hidden -File
    C:\running-ai-github\scripts\windows\start-running-ai.ps1`,
    `WorkingDirectory=C:\running-ai-github`. Principal: `UserId=simjy`,
    `LogonType=Interactive`, `RunLevel=Limited`. Trigger: `LogonTrigger`,
    `Enabled=True`, `Delay=`(공란, 즉시 — Phase 6I-1.7D-1A에서 관측한 즉시 기동 동작과 일치).
11. **RunningAI-Watchdog**: `State=Ready`(Enabled).
    `LastRunTime=2026-10-12 07:54:16`, `LastTaskResult=0`.
12. **watchdog-state.json**: `version=2`.
13. **Restart Budget/LongTerm Lockout**: 5개 컴포넌트 전부 `restarts=0`,
    `failures=0`, `lockedOutSince=`공란(해제).
14. **현재 운영 PID/CreationDate**: Spring `29984`(CreationDate `00:45:12`),
    Connector Launcher `25372`/Listener `28684`(둘 다 `00:45:08`),
    Relay `13588`(CreationDate `00:45:29`).
15. **DB**: `activity` 6행, 최신 Activity `id=6`, `started_at=2026-10-05 15:02:39+00`;
    `garmin_sync_state`: `high_water_started_at=2026-10-05 15:02:39+00`,
    `last_successful_sync_at=2026-10-05 16:24:14.229834+00`.
16. **민감 파일 존재 여부**(내용 미출력): `.env` 존재(SHA256 기록, 내용 미출력),
    `tools/external-relay/secrets/watch-token.json` 존재(SHA256 기록, 내용 미출력),
    `C:\Users\simjy\.garminconnect` 디렉터리 존재. `tools\garmin-connector\.env`는
    원래부터 부재(커넥터는 Canonical `.env`를 사용, 별도 `.env` 없음 — 비정상 아님).

비정상 항목 없음. STEP B로 진행.

## STEP B — 안전한 데이터 백업 (전부 PASS)

### PostgreSQL

- 논리 백업: `docker exec running-ai-postgres pg_dump -U running_ai -d running_ai -Fc`
  (Custom 형식, 서버와 동일한 `pg_dump 17.11` 사용) → 컨테이너 내 임시 파일 생성 →
  `docker cp`로 호스트의 Git 저장소 밖 경로로 복사 → 컨테이너 내 임시 파일 삭제.
- 백업 파일: `C:\Users\simjy\.runningai-state-backups\postgres\running_ai.pre-reboot.20261011-225810Z.dump`
- 크기: `1,582,314` bytes
- SHA256: `CF26704BA26EB86FC899E774290EBDC3B515F48C8A4D5F87689CF4FB1507EA39`
- 생성 시각(UTC): `2026-10-11T22:58:10Z`
- 형식 검증: `pg_restore --list`로 읽기 전용 목록 조회 성공 — `TOC Entries: 185`,
  `Format: CUSTOM`, `Dumped from database version: 17.11`, `Dumped by pg_dump
  version: 17.11` — 서버/덤프 버전 완전 일치, 손상 없이 읽을 수 있는 유효한 백업임을
  확인. **실제 복원은 시도하지 않았다.**
- 비밀번호는 명령줄 인수나 출력 어디에도 노출되지 않았다(컨테이너 내부
  `docker exec` 실행이므로 호스트 인증 정보를 별도로 전달할 필요가 없었음).
- 복원 절차(참고, 실행하지 않음): 동일 PostgreSQL 17.x 서버에
  `pg_restore -U running_ai -d running_ai --clean --if-exists <dump 파일>`
  (대상 DB가 이미 존재하고 스키마가 충돌할 경우 `--clean --if-exists`로 기존 객체를
  먼저 제거 후 복원 — 운영 DB 복원 시에는 반드시 사전에 별도 백업 후 수행).
- 백업 디렉터리 ACL: 상속 비활성화, `NT AUTHORITY\SYSTEM`과
  `LAPTOP-MMDQUCIC\simjy`에만 `FullControl` 부여(Phase 6I-1.7C-3에서 확립한 동일
  패턴 재사용).

### Watchdog

- `watchdog-state.json`(v2) + `.bak` 신규 백업:
  `watchdog-state.v2.pre-reboot.20261011-225835Z.json` /
  `.json.bak` — 둘 다 SHA256 `466C8B06AFA55F4D8540A22BB638B2D1563F0A8AA41BA9F0A961C01E1FAF38BD`
  로 원본과 완전히 일치.
- 기존 v1 스냅샷(`watchdog-state.v1.pre-v2-transition.20261011-051306Z.json`)과
  이전 v2 스냅샷(`watchdog-state.v2.pre-activation.20261011-102440Z.json`)은
  그대로 보존됨(덮어쓰지 않음, 삭제하지 않음) — 디렉터리 목록으로 직접 재확인.
- 무결성 검사: JSON 파싱 성공, `version=2`, `restarts`/`longTerm` 전부 0/해제
  상태로 기록(위 STEP A 13번과 동일).

### 애플리케이션 설정

- `.env`, `tools/external-relay/secrets/watch-token.json` 존재 확인 및 SHA256만
  기록(위 STEP A 16번). 내용은 출력하지 않았고, 기존 파일을 삭제·수정·덮어쓰지
  않았다.

백업 실패 없음 → NO-GO 사유 없음.

## STEP C — 재부팅 직전 기준선 (타임스탬프 포함, 토큰/비밀번호 미포함)

기준 시각: **2026-10-11T22:58Z (2026-10-12 07:58 KST)** 전후로 수집.

| 구분 | 비교 기준 값 |
|---|---|
| Git | Canonical HEAD `43ed6af2049e1564fb35b76dbd8e22d99774ec19`, Working Tree Clean |
| PostgreSQL | DB명 `running_ai`, Volume `running-ai-github_running_ai_postgres_data`, Container `8ff2a3adc495`, Health `healthy` |
| Activity | 행 수 `6`, 최신 `id=6` / `started_at=2026-10-05 15:02:39+00` |
| Garmin Sync | `high_water_started_at=2026-10-05 15:02:39+00`, `last_successful_sync_at=2026-10-05 16:24:14.229834+00` |
| Connector | Launcher `25372` / Listener `28684`, 둘 다 CreationDate `2026-10-12 00:45:08`, Ownership `LAUNCHER_CHILD` |
| Spring | PID `29984`, CreationDate `2026-10-12 00:45:12`, Health `200` |
| Relay | PID `13588`, CreationDate `2026-10-12 00:45:29`, Health `200` |
| Tailscale | 서비스 `Running`/`Automatic`, Funnel `https://laptop-mmdqucic.tail4597a5.ts.net` → `127.0.0.1:17845` (On) |
| Watchdog | `LastRunTime=2026-10-12 07:54:16`, `LastTaskResult=0`, State v2, Lockout 전부 해제, Restart Budget 전부 0 |
| API | Local Spring/Connector/Relay `200`/`200`/`200`; Public `/health` `200`; 인증 `/today-workout` Local·Public 둘 다 `200` |

재부팅 후 PID가 달라지는 것은 그 자체로 실패 사유가 아니다(판정 기준이 아님, 지시서
명시 사항).

## STEP D — 재부팅 승인 게이트

**REBOOT_PRECHECK=GO**

STEP A(Preflight) 전부 PASS, STEP B(백업) 전부 PASS, STEP C(기준선) 기록 완료.

이 시점에서 작업을 STOP한다. 다음 명령은 사용자의 명시적 재부팅 승인 전까지 실행하지
않는다: `Restart-Computer`, `shutdown /r`, Windows 로그오프, Docker Desktop 재시작,
Scheduled Task 수동 실행.

사용자는 미저장 작업을 정리한 뒤 직접 Windows를 재부팅해야 한다. 재부팅 과정에서 이
Claude Code 세션이 중단될 수 있으므로, 위 STEP A~C의 전체 기준선은 이미 이 문서
(`docs/work-orders/2026-10-12-phase-6i-1-7d-3-controlled-reboot-e2e-result.md`)와
외부 백업(STEP B)으로 디스크에 고정되었다 — 세션이 끊기더라도 재개 시 이 문서를 다시
읽어 STEP E부터 이어갈 수 있다.

---

*(이 아래는 재부팅 이후, 사용자 재로그인 뒤에 STEP E부터 이어서 기록한다.)*
