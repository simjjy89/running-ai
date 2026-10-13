# WO-RUNNINGAI-PHASE-7D-2-PREFLIGHT — PostgreSQL 보안 조치 및 운영 배포 최종 준비 (결과 보고)

지시서: `2026-10-13-phase-7d-2-postgresql-preflight-instruction.md`
수행일: 2026-10-13 (KST) · 브랜치 `main-3` (워크트리 `C:\Users\simjy\orca\workspaces\running-ai-github\main-3`) · 수행 PC: Main PC
선행 Phase: 7D-1 (GO, `docs/work-orders/2026-10-13-phase-7d-1-release-candidate-preparation-result.md`)
승인 요청서: `2026-10-13-phase-7d-2-item-g-approval-request.md`

---

## 0. 결론 요약

| 항목 | 값 |
|---|---|
| 이번 Phase의 운영 변경 | **0건** — 포트·방화벽·컨테이너·DB·Spring·Funnel·Relay·Watchdog·Canonical `main`·`origin` 전부 무변경 |
| 수행한 것 | 읽기 전용 실측(점검) + 변경/복구 절차 확정 + RC 재검증 + 승인 요청서 작성 |
| G 항목 판정 | **실행 가능 (READY)** — 단, 7D-1이 적어둔 절차 그대로는 **실행하면 안 된다** (아래 F-1 / F-2 / F-3) |
| RC 운영 배포 가능 여부 | **가능 (GO 유지)** — RC SHA·JAR SHA-256·FF 가능성·마이그레이션·MCP OFF·레거시 writer·부상 게이트 7개 항목 전부 재확인 PASS |
| 이번 Phase가 새로 찾은 것 | **F-1 볼륨 실제 이름 불일치**, **F-2 Tailscale IPv6 노출 경로 누락**, **F-3 override 파일 무효**, **F-4 superuser 강등 시 클러스터 락아웃** |

> 이번 점검에서 수행한 모든 명령은 **읽기 전용**이다 (`docker inspect` / `docker volume inspect` / `psql -tAc SELECT` / `netstat` / `Get-NetFirewallRule` / `tailscale status` / `Get-FileHash` / TCP connect 프로브 / 스크래치패드 복사본에 대한 `docker compose config`). 운영 컨테이너·DB·파일을 쓴 명령은 **한 건도 없다.**

---

## STEP 1 — PostgreSQL 구성 확인

### 1-1. Docker / Compose 구성

| 항목 | 실측값 |
|---|---|
| Docker Engine | **29.8.1** (server) / 29.8.1 (client) |
| Compose 플러그인 | **v5.5.1** (컨테이너 라벨 `com.docker.compose.version`) |
| 이미지 | `postgres:17` → 런타임 **PostgreSQL 17.11 (Debian 17.11-1.pgdg13+2)** |
| 컨테이너 | `running-ai-postgres` · **Up 32 hours (healthy)** · `restart=unless-stopped` · Created `2026-09-29T14:42:47Z` |
| Compose 프로젝트 | `running-ai-github` · working_dir `C:\running-ai-github` · config_files `C:\running-ai-github\docker-compose.yml` |
| 호스트에 공개된 다른 컨테이너 | **없음** (실행 중 컨테이너는 이 1개가 전부) |

운영 compose 정의 (`C:\running-ai-github\docker-compose.yml`, Canonical `main` = `370ee8f`):

```yaml
    ports:
      - "${POSTGRES_PORT:-5432}:5432"
```

운영 `.env` (`C:\running-ai-github\.env`) 에는 **`POSTGRES_PORT`가 없다** → 기본값 5432가 적용된다.
(`.env`에 존재하는 키: `POSTGRES_PASSWORD`, `DB_PASSWORD`, `INTERVALS_API_KEY`, `GARMIN_PROFILE_SYNC_*`,
`SERVER_ADDRESS=127.0.0.1`, `SERVER_PORT=18080`, `RUNNING_AI_TRAINING_CONTEXT_VERSION=V2`,
`RUNNING_AI_DRAFT_PUBLISHING_ENABLED=false`, `WORKOUT_PUBLISHING_ENABLED=false`,
`WORKOUT_PUBLISHING_SCHEDULER_ENABLED=false`, `RUNNINGAI_MCP_ENABLED=false`,
`RUNNING_AI_EXTERNAL_BASE_URL`, `INTERVALS_ICU_API_KEY`. **`DB_URL`·`DB_USERNAME`·`POSTGRES_DB`·`POSTGRES_USER`는 없다** → 전부 소스 기본값.)

### 1-2. 실제 포트 바인딩 — 0.0.0.0 + :: (IPv4·IPv6 전부)

```
HostConfig.PortBindings : {"5432/tcp":[{"HostIp":"","HostPort":"5432"}]}      <- HostIp 미지정
NetworkSettings.Ports   : {"5432/tcp":[{"HostIp":"0.0.0.0","HostPort":"5432"},
                                       {"HostIp":"::",     "HostPort":"5432"}]}
docker ps PORTS         : 0.0.0.0:5432->5432/tcp, [::]:5432->5432/tcp
```

호스트의 실제 LISTEN 소켓:

| 소켓 | PID | 프로세스 |
|---|---|---|
| `0.0.0.0:5432` | 2416 | `com.docker.backend.exe` |
| `[::]:5432` | 2416 | `com.docker.backend.exe` |
| `[::1]:5432` | 26632 | `C:\Program Files\WSL\wslrelay.exe` (WSL localhost 포워딩) |

**현재 연결된 클라이언트는 전부 루프백이다.** ESTABLISHED 11건 모두 `127.0.0.1 -> 127.0.0.1:5432`,
클라이언트 PID는 전부 **29984 = 운영 Spring** (`java -jar C:\running-ai-github\server\build\libs\running-ai-server-0.0.1-SNAPSHOT.jar`, Hikari 풀).
비루프백 주소에서 들어온 연결은 **현 시점 0건**이다.

### 1-3. 도달성 실측 (읽기 전용 TCP connect, 같은 호스트 출발)

| 대상 | 결과 |
|---|---|
| `127.0.0.1:5432` | **CONNECT OK** |
| `[::1]:5432` | **CONNECT OK** (wslrelay 경유) |
| `192.168.0.24:5432` (LAN, `이더넷 2`) | **CONNECT OK** |
| `100.108.54.79:5432` (Tailscale IPv4) | **CONNECT OK** |
| **`[fd7a:115c:a1e0::cc01:36d4]:5432` (Tailscale IPv6)** | **CONNECT OK** — **F-2: 7D-1이 기록하지 않은 경로** |
| `172.22.80.1:5432` (WSL vEthernet) | CONNECT OK |
| `169.254.*` | 전부 거부 |

> **F-2 (신규 발견).** 7D-1은 tailnet 노출을 IPv4(`100.108.54.79`)로만 실측했다. 이 호스트에는 Tailscale IPv6 주소
> `fd7a:115c:a1e0::cc01:36d4`가 있고 컨테이너 포트가 `[::]`에도 게시되어 있어 **IPv6 tailnet 경로로도 5432가 열려 있다.**
> `127.0.0.1:5432:5432`를 적용하면 IPv4 와일드카드 게시와 IPv6 게시가 **둘 다** 사라지므로 이 경로도 함께 닫힌다.

### 1-4. 방화벽 범위

| 항목 | 실측 |
|---|---|
| 프로파일 | Domain/Private/Public 전부 Enabled, 기본 인바운드 = Block |
| 활성 네트워크 분류 | `이더넷 2`(iptime5G) = **Private**, `Tailscale` = **Private** |
| 문제의 허용 규칙 | **`Docker Desktop Backend`** → Program `com.docker.backend.exe`, Protocol **TCP**, LocalPort **Any**, Profile **Private + Public**, Action **Allow**, Enabled **True** |

→ 게시된 컨테이너 포트는 전부 이 규칙을 탄다. **방화벽은 5432를 막고 있지 않다.** (7D-1 결론 재확인.)

참고(이번에 함께 확인): `OpenJDK Platform binary` (`C:\program files\java\jdk-21.0.2\bin\java.exe`) 에 대해서도
Private+Public / LocalPort Any 허용 규칙이 있다. **운영 Spring이 LAN에 노출되지 않는 유일한 이유는 `SERVER_ADDRESS=127.0.0.1`이다**
(방화벽이 아니다). `.env.example`의 경고 문구가 사실임을 실측으로 확인했다.

### 1-5. Tailscale 접근 범위

| 항목 | 실측 |
|---|---|
| 자기 노드 | `LAPTOP-MMDQUCIC` — `100.108.54.79`, `fd7a:115c:a1e0::cc01:36d4` |
| tailnet 피어 | **개인 기기 0대.** 피어 23개는 전부 Tailscale 인프라의 `funnel-ingress-node` (IPv6 only) |
| Funnel/Serve 노출 | `https://laptop-mmdqucic.tail4597a5.ts.net` → `/ proxy http://127.0.0.1:17845` **1건뿐.** 5432는 Funnel에 없음 |

> **7D-1 표현 보정.** "사용자의 **모든 Tailscale 기기**가 DB에 직접 TCP 도달한다"는 서술은 *현재 시점에는* 과장이다 —
> tailnet에 개인 피어가 0대이기 때문이다. 정확한 위험 서술은 다음과 같다:
> **"지금 휴대폰이나 노트북을 tailnet에 추가하는 순간, 그 기기는 아무 추가 설정 없이 운영 DB에 TCP 도달한다"**
> (기본 ACL은 tailnet 멤버 간 전 포트 허용). 즉 위험의 성격은 "이미 뚫려 있다"가 아니라 **"기기 1대 추가 = 즉시 노출"** 이며,
> 조치 필요성은 동일하다. 인터넷 직노출은 아니다 (Funnel은 5432를 게시하지 않음).

### 1-6. 운영 DB 볼륨 및 영속 저장소 — **F-1 (중요)**

| 항목 | 실측 |
|---|---|
| 컨테이너 마운트 | `volume` **`running-ai-github_running_ai_postgres_data`** → `/var/lib/postgresql/data` (rw) |
| Mountpoint | `/var/lib/docker/volumes/running-ai-github_running_ai_postgres_data/_data` (Docker Desktop WSL VM 내부) |
| 볼륨 생성 시각 | `2026-09-29T14:42:47Z` (컨테이너와 동일) |
| 익명 볼륨 | **없음** (마운트는 이 1건이 전부) |
| 다른 볼륨 | `main-3_running_ai_postgres_data` (2026-10-10 생성, **미사용 고아**, 어느 컨테이너에도 마운트되지 않음) |
| 로컬 볼륨 합계 | 118.9 MB (`docker system df`) |
| DB 크기 | **27 MB** (`pg_database_size('running_ai')`) |
| 호스트 디스크 여유 | **143.7 GB** |

> **F-1 (중요 — 7D-1의 중단 조건이 틀렸다).**
> 7D-1 §6-G의 중단 조건은 *"재생성 후 `docker volume ls`에 **`running_ai_postgres_data`**가 없으면 즉시 중단"* 이다.
> 그런 이름의 볼륨은 **지금도 존재하지 않는다.** Compose는 볼륨 이름에 프로젝트명을 접두사로 붙이므로 실제 이름은
> **`running-ai-github_running_ai_postgres_data`** 이다. 7D-1 문구대로 확인하면 **정상 상황에서도 항상 "중단"으로 오판**한다.
> 본 Phase의 절차는 실제 이름으로 수정했다.

추가 확인: compose 프로젝트명은 `-f`로 넘긴 파일의 **부모 디렉터리 이름**에서 결정된다. 운영 스크립트는 항상
`-f C:\running-ai-github\docker-compose.yml`을 넘기므로 프로젝트명은 `running-ai-github`로 고정이고, 따라서
재생성 후에도 볼륨 이름은 **바뀌지 않는다** (스크래치패드 복사본에 대한 `docker compose config`로 접두사 규칙 검증).
**워크트리(`...\main-3`)에서 `docker compose up -d`를 실행하면 프로젝트명이 `main-3`가 되어 다른 볼륨을 보게 된다 — 절대 금지.**

### 1-7. DB 접속 경로 전수 조사 (Spring / Connector / Relay / Watchdog)

| 구성요소 | 포트 | DB 접속 방식 | `127.0.0.1` 제한 시 영향 |
|---|---|---|---|
| **Spring (운영)** | `127.0.0.1:18080` (PID 29984) | `.env`에 `DB_URL` 없음 → `application-local.yml` 기본값 `jdbc:postgresql://localhost:5432/running_ai` · 실측 연결은 전부 `127.0.0.1` | **없음** (단 1-8의 `localhost` 주의) |
| **Garmin connector** | `127.0.0.1:8765` (PID 28684, 런처 25372 = `LAUNCHER_CHILD` 정상) | **DB 미사용** — 소스에 DB/psycopg/5432 참조 0건 | **없음** |
| **External relay** | `127.0.0.1:17845` (PID 13588, node) | **DB 미사용** — `C:\running-ai\state\today_plan.json` 파일만 읽음 | **없음** |
| **Watchdog** | (스케줄 작업) | `docker compose -f ... ps -a -q postgres` + `docker inspect` = **Docker API만 사용, 5432 미사용** | **없음** |
| Gradle 테스트 | — | `localhost` | 없음 |
| compose healthcheck | — | 컨테이너 내부 `pg_isready` | **없음** (호스트 포트 미경유) |
| 저장소 전체 | — | 비루프백 DB 호스트 참조 0건 | **없음** |

→ **5432를 루프백으로 제한해도 깨지는 소비자는 없다.** (7D-1 결론 재확인 + Relay/Connector 소스 직접 확인으로 보강.)

### 1-8. `localhost` 해석 주의 (유일한 실패 시나리오)

이 호스트에서 `localhost`는 **`::1` → `127.0.0.1` 순**으로 해석된다 (`Dns.GetHostAddresses` 실측).
현재는 `[::1]:5432`를 **wslrelay**가 듣고 있어 양쪽 다 열려 있다. `127.0.0.1:5432:5432`를 적용하면
**IPv4 루프백만 게시**되며, `[::1]` 리스너가 유지될지는 Docker Desktop/WSL 동작에 달려 있어 사전 단정이 불가능하다.

- 운영 Spring은 **영향 없음이 유력하다**: JDK 기본값은 `java.net.preferIPv6Addresses=false`라 `InetAddress.getAllByName("localhost")`가
  IPv4를 먼저 돌려주고, pgjdbc는 해석된 주소를 순서대로 시도한다. 실측상 현재 연결도 전부 IPv4다.
- 그래도 **잔여 위험 0**으로 만들고 싶다면 적용과 함께 `.env`에 `DB_URL=jdbc:postgresql://127.0.0.1:5432/running_ai` 1줄을 명시하면 된다.
  → 승인 요청서에 **선택 항목 G-opt**로 분리했다 (필수 아님, Spring 재기동 필요).

### 1-9. 인증·암호화·감사 (읽기 전용)

| 항목 | 값 | 평가 |
|---|---|---|
| `listen_addresses` | `*` | 컨테이너 내부 설정이므로 **그대로 둔다.** 외부 차단은 Docker 게시 주소로 하는 것이 맞다 |
| `pg_hba.conf` | `local/127.0.0.1/::1 → trust` + `host all all all scram-sha-256` | 컨테이너 네트워크 기준이라 호스트 외부는 항상 비밀번호 요구 |
| `password_encryption` | `scram-sha-256` | 양호 |
| **`ssl`** | **`off`** | 쿼리·결과(심박/수면/HRV/페이스)가 **평문**으로 흐름. 루프백 전용이 되면 실질 위험은 소멸 |
| **`log_connections`** | **`off`** | **"지금까지 외부 접속이 없었다"를 증명할 수 없다.** 이번 점검의 명시적 한계(현재 연결이 전부 루프백이라는 스냅샷만 확인) |

### 1-10. 애플리케이션 DB 계정 권한 — **F-4 (락아웃 함정)**

```
로그인 가능 롤 : running_ai 만 존재 (rolsuper=t, rolcreatedb=t, rolcreaterole=t,
                                    rolreplication=t, rolbypassrls=t)
클러스터 전체  : 슈퍼유저는 running_ai 가 유일 (postgres 롤 자체가 없음)
DB 소유자     : running_ai (running_ai / postgres / template0 / template1 전부)
public 스키마 : 소유자 pg_database_owner · public 내 모든 테이블 소유자 running_ai
```

**최소 권한 전환 가능성 평가: 기술적으로는 가능하나, G와 함께 하면 안 되고 별도 Phase로 분리해야 한다.**

| 판단 | 근거 |
|---|---|
| **F-4 — `ALTER ROLE running_ai NOSUPERUSER`는 금지** | 이 클러스터의 **유일한 슈퍼유저**다. 강등하면 슈퍼유저가 0이 되어 되돌릴 수단이 사라지고, 복구는 컨테이너 내부 단일 사용자 모드(`postgres --single`)뿐이다 |
| `POSTGRES_USER` 변경은 무의미 | 해당 환경변수는 **볼륨 초기 부팅 때만** 사용된다. 기존 볼륨에서는 아무 효과가 없다 (= 계정 교체는 compose 수정으로 되지 않는다) |
| Flyway가 DDL을 수행한다 | 앱 롤은 `CREATE ON SCHEMA public` + 기존 객체 소유권이 필요하다. 단순한 `GRANT SELECT/INSERT`만으로는 기동이 깨진다 |
| `REASSIGN OWNED`는 범위가 넓다 | 공유 객체(데이터베이스 소유권 포함)까지 옮길 수 있어 무계획 실행은 위험하다 |
| 안전한 순서 (별도 Phase 제안) | ① 백업 ② `CREATE ROLE running_ai_app LOGIN NOSUPERUSER` ③ `GRANT CONNECT/USAGE/CREATE` + public 내 객체 소유권만 선별 이전 ④ `.env`의 `DB_USERNAME/DB_PASSWORD` 교체 후 기동·마이그레이션 검증 ⑤ `running_ai`는 **슈퍼유저인 채로 남겨 비상/정비 전용**으로만 사용 |

> 지금 `127.0.0.1` 제한(G)을 적용하면 **이 superuser 자격증명에 접근 시도조차 로컬 프로세스만 가능**해지므로,
> 최소 권한 전환의 시급성은 High → Low로 떨어진다. **G가 먼저이고, 계정 분리는 그 다음이다.**

---

## STEP 2 — 변경 및 복구 계획 (실행은 승인 후)

### 2-0. 전제와 설계 판단

**F-3 (신규 발견 — override 파일 방식은 무효다).**
운영 스크립트(`start-/stop-/status-running-ai.ps1`, `RunningAI.Watchdog.ps1`)는 **항상 `-f C:\running-ai-github\docker-compose.yml`을 명시**한다.
`-f`를 명시하면 Compose의 자동 파일 탐색이 꺼지므로 `docker-compose.override.yml`은 **읽히지 않는다.**
수동으로 `-f A -f B`를 붙여 재생성해도, 다음 번 `start-running-ai.ps1`(또는 부팅/Watchdog 복구)이 **단일 `-f`로 `up -d`를 돌리는 순간
config-hash가 달라져 컨테이너를 `0.0.0.0` 바인딩으로 조용히 되돌린다.**
→ **지속되는 유일한 방법은 추적 파일 `C:\running-ai-github\docker-compose.yml` 자체를 바꾸는 것이다.**

그 결과 G는 Canonical 작업트리의 파일 내용을 바꿔야 하고, 깨끗한 git 상태를 유지하려면 **승인 항목 A(FF-only 병합)에 얹는 것이 가장 안전하다**:

- `docker-compose.yml`은 **JAR에 포함되지 않는다** → 이 1줄을 커밋해도 **RC JAR SHA-256은 바뀌지 않는다.**
- A(FF 병합)만으로는 **운영 동작이 바뀌지 않는다** (JAR 재빌드 전까지). 7D-1이 이미 확인한 성질이다.
- 따라서 **"A(병합) → G(재생성)"는 RC 배포(C)와 독립적으로 먼저 수행할 수 있다.**

변경 내용 (1줄):

```diff
     ports:
-      - "${POSTGRES_PORT:-5432}:5432"
+      - "127.0.0.1:${POSTGRES_PORT:-5432}:5432"
```

스크래치패드 복사본으로 **문법·결과 검증 완료** (운영 파일 무변경):
`docker compose config` → `host_ip: 127.0.0.1 / target: 5432 / published: "5432"`.

### 2-1. 사전 백업 및 복원 검증 (운영 클러스터를 건드리지 않는 방식)

```powershell
# (1) 백업 - 컨테이너 안에서 덤프 후 호스트로 복사
$ts  = Get-Date -Format 'yyyyMMdd-HHmmss'
$dir = 'C:\running-ai-github\.runtime\backups\phase-7d-2'
New-Item -ItemType Directory -Force -Path $dir | Out-Null
docker exec running-ai-postgres pg_dump -U running_ai -d running_ai -Fc -f "/tmp/pre-g-$ts.dump"
docker cp "running-ai-postgres:/tmp/pre-g-$ts.dump" "$dir\pre-g-$ts.dump"
docker exec running-ai-postgres rm -f "/tmp/pre-g-$ts.dump"
Get-FileHash "$dir\pre-g-$ts.dump" -Algorithm SHA256 | Format-List
(Get-Item "$dir\pre-g-$ts.dump").Length    # 기대: 약 2.8 MB (DB 27 MB, 7D-1 실측치와 동급)

# (2) 아카이브 무결성 - 운영 DB를 건드리지 않는 1차 검증
docker run --rm -v "${dir}:/b" postgres:17 pg_restore --list "/b/pre-g-$ts.dump" | Measure-Object -Line

# (3) 실제 복원 검증 - 격리된 임시 컨테이너 (포트 게시 없음, 운영 볼륨 미사용)
docker run -d --name running-ai-restore-check -e POSTGRES_PASSWORD=checkonly `
  -e POSTGRES_USER=running_ai -e POSTGRES_DB=running_ai postgres:17
#   (healthy 대기 후)
docker cp "$dir\pre-g-$ts.dump" running-ai-restore-check:/tmp/check.dump
docker exec running-ai-restore-check pg_restore -U running_ai -d running_ai --clean --if-exists /tmp/check.dump
docker exec running-ai-restore-check psql -U running_ai -d running_ai -tAc `
 "select 'activity',count(*) from activity union all select 'activity_sample',count(*) from activity_sample union all select 'workout_draft',count(*) from workout_draft union all select 'garmin_recovery_daily',count(*) from garmin_recovery_daily union all select 'intervals_fitness_daily',count(*) from intervals_fitness_daily;"
docker rm -f running-ai-restore-check        # 검증 끝나면 임시 컨테이너만 제거
```

**기대 행수 (2026-10-13 08:3x KST 운영 실측 기준선):**

| 테이블 | 행수 |
|---|---:|
| `activity` | 8 |
| `activity_sample` | 21,828 |
| `workout_draft` | 19 |
| `garmin_recovery_daily` | 21 |
| `intervals_fitness_daily` | 99 |
| Flyway 최신 버전 | **V20** (`V20__add_workout_draft_context_snapshot.sql`, success) |
| `historical_backfill_run` | `COMPLETED` 1건 (**RUNNING 0건 → 백업 안전**) |

**중단 조건:** 덤프 파일 부재 / 2 MB 미만 / `pg_dump` 비정상 종료 / `pg_restore --list` 실패 / (3)의 행수 불일치 / `historical_backfill_run`에 `RUNNING` 존재.

### 2-2. 변경 대상과 예상 중단 시간

| 대상 | 변경 |
|---|---|
| **파일** | `C:\running-ai-github\docker-compose.yml` 1줄 (ports) |
| **컨테이너** | `running-ai-postgres` **재생성 1회** (= 기존 컨테이너 제거 + 동일 이름으로 신규 생성) |
| **볼륨** | `running-ai-github_running_ai_postgres_data` **그대로 재사용** (명명 볼륨이라 컨테이너 수명과 무관) |
| **이미지 / DB 데이터 / 스키마 / 설정** | 무변경 |

**예상 중단 시간** (실측 근거: Spring 기동 10.107 s, PG healthcheck interval 5 s, stop 스크립트 타임아웃 Spring 30 s / connector 15 s / relay 15 s)

| 구성요소 | 절차 A (권장, 스크립트 경로) | 절차 B (최소 영향, 대안) |
|---|---|---|
| PostgreSQL | **약 10 ~ 20 초** | 약 10 ~ 20 초 |
| Spring API (`127.0.0.1:18080`) | 약 40 ~ 90 초 | **무중단** (Hikari 재연결, 재생성 순간 진행 중 요청만 실패) |
| External relay / 워치페이스 `/today-workout` | 약 40 ~ 90 초 | **무중단** |
| Garmin connector | 약 40 ~ 90 초 | **무중단** |
| **전체 소요 (백업 포함)** | **약 3 ~ 5 분** | 약 2 ~ 3 분 |

> 워치페이스는 Relay가 `C:\running-ai\state\today_plan.json`을 읽어 응답하므로 **Spring이 내려가도 응답 자체는 유지된다**
> (절차 A에서는 Relay 프로세스도 함께 내려가므로 그 구간만 중단).
> 절차 A를 권장하는 이유: 운영에서 검증된 스크립트만 사용하고, 상태 전이가 결정적이며, 실패 시 복구 경로가 기존 Runbook과 동일하기 때문이다.
> 절차 B(= `docker compose up -d`만 수행, Spring/Relay/Connector 무중단)는 중단을 최소화하고 싶을 때의 대안이며, 되돌리기 방법은 동일하다.

### 2-3. 실행 절차 (절차 A — 권장)

```powershell
# ── G-0. 사전 조건 확인 (전부 읽기 전용) ─────────────────────────────────────
Get-ScheduledTask -TaskName 'RunningAI-Watchdog' | Select-Object State
docker ps --format '{{.Names}} {{.Status}} {{.Ports}}'
docker volume ls --filter name=running_ai_postgres_data
docker exec running-ai-postgres psql -U running_ai -d running_ai -tAc "select status,count(*) from historical_backfill_run group by status;"
git -C C:\running-ai-github status --short          # 반드시 비어 있어야 한다

# ── G-1. Watchdog 정지 (자동 복구가 절차에 개입하지 못하게) ──────────────────
Disable-ScheduledTask -TaskName 'RunningAI-Watchdog'

# ── G-2. 백업 + 복원 검증  → §2-1 그대로 수행 (중단 조건 해당 시 여기서 중단)

# ── G-3. compose 1줄 반영  (승인 항목 A와 함께. 작업트리를 더럽히지 않는다) ──
git -C C:\running-ai-github merge --ff-only main-3
Select-String -Path C:\running-ai-github\docker-compose.yml -Pattern '5432'
#   기대: - "127.0.0.1:${POSTGRES_PORT:-5432}:5432"

# ── G-4. 중지 → 재생성 → 기동 (운영 스크립트만 사용) ─────────────────────────
powershell -File C:\running-ai-github\scripts\windows\stop-running-ai.ps1      # -StopDatabase 는 쓰지 않는다
powershell -File C:\running-ai-github\scripts\windows\start-running-ai.ps1
#   start 스크립트가 수행하는 'docker compose -f ... up -d' 가 config-hash 변경을 감지하고
#   running-ai-postgres 를 새 포트 바인딩으로 재생성한 뒤 healthy 를 기다린다.
#   별도의 docker 명령을 손으로 칠 필요가 없다.

# ── G-5. Watchdog 복귀 ───────────────────────────────────────────────────────
Enable-ScheduledTask -TaskName 'RunningAI-Watchdog'
```

**절대 금지:** `docker compose down -v`, `docker volume rm`, `docker volume prune`, `docker system prune`,
워크트리(`...\orca\workspaces\...\main-3`)에서의 `docker compose up`, DB 재초기화.

### 2-4. 적용 직후 확인 항목 (순서대로)

| # | 확인 | 명령 | 기대 |
|---|---|---|---|
| 1 | **볼륨 보존** | `docker volume ls --filter name=running_ai_postgres_data` | **`running-ai-github_running_ai_postgres_data`** 존재 (CreatedAt `2026-09-29T14:42:47Z` 유지) |
| 2 | **바인딩 변경** | `docker inspect running-ai-postgres --format '{{json .HostConfig.PortBindings}}'` | `{"5432/tcp":[{"HostIp":"127.0.0.1","HostPort":"5432"}]}` |
| 3 | 공개 포트 | `docker ps --format '{{.Ports}}'` | `127.0.0.1:5432->5432/tcp` **만** (`0.0.0.0` / `[::]` 없음) |
| 4 | 리스너 | `netstat -ano | findstr :5432` | `127.0.0.1:5432` LISTENING만. **`0.0.0.0:5432` / `[::]:5432` 소멸** |
| 5 | **로컬 접속** | `docker exec running-ai-postgres psql -U running_ai -d running_ai -tAc "select 1;"` | `1` |
| 6 | **데이터 보존** | §2-1의 행수 쿼리 | activity 8 / sample 21,828 / draft 19 / recovery 21 / fitness 99 **완전 일치** |
| 7 | Flyway | `select version from flyway_schema_history order by installed_rank desc limit 1;` | **V20** (G 단독 적용 시 마이그레이션은 일어나지 않는다) |
| 8 | **Spring DB 복구** | `GET http://127.0.0.1:18080/actuator/health` | `200` / `UP` |
| 9 | Spring 실데이터 | `GET http://127.0.0.1:18080/api/v1/activities?page=0&size=1` | `200` |
| 10 | Spring 로그 | `Get-Content .runtime\logs\spring.out.log -Tail 40` | Hikari 연결 성공, `Successfully validated 20 migrations`, 커넥션 오류 없음 |
| 11 | **LAN 차단** | `Test-NetConnection 192.168.0.24 -Port 5432` | **실패(차단)** |
| 12 | **Tailnet IPv4 차단** | `Test-NetConnection 100.108.54.79 -Port 5432` | **실패(차단)** |
| 13 | **Tailnet IPv6 차단** | `Test-NetConnection fd7a:115c:a1e0::cc01:36d4 -Port 5432` | **실패(차단)** |
| 14 | **외부 기기 확정 검증** | 다른 기기에서 `Test-NetConnection 192.168.0.24 -Port 5432` | **실패** — 같은 호스트 프로브는 방화벽을 우회할 수 있으므로 **이 1회가 유일한 확정 증거다** |
| 15 | Relay / 워치페이스 | `GET http://127.0.0.1:17845/health` · Funnel `/today-workout` | `{"status":"ok"}` / 정상 |
| 16 | Connector | `GET http://127.0.0.1:8765/health` | 정상 |
| 17 | Watchdog | 재활성화 5분 후 `.runtime\watchdog-status.json` | `overall=UP`, 5개 컴포넌트 UP, `restartBudget.used` 전부 0, `longTermLockout` 전부 false |

**중단/롤백 트리거:** 1·5·6 중 하나라도 실패 → **즉시 2-5의 복구.** 8이 90초 내 UP이 아니면 → 2-5의 (R3) 먼저 시도.

### 2-5. 실패 시 원상 복구

| 상황 | 복구 |
|---|---|
| **(R1) 포트/재생성 문제, 데이터는 무사** | `git -C C:\running-ai-github checkout -- docker-compose.yml` (또는 A를 되돌릴 경우 `git -C C:\running-ai-github reset --hard 370ee8f`) → `stop-running-ai.ps1` → `start-running-ai.ps1`. 컨테이너가 원래의 `0.0.0.0` 바인딩으로 재생성된다. 소요 ≈ 2분 |
| **(R2) Spring이 DB에 붙지 못함** | 먼저 (R3)을 시도. 그래도 실패하면 (R1) |
| **(R3) `localhost`/IPv6 해석 문제로 의심될 때** | `.env`에 `DB_URL=jdbc:postgresql://127.0.0.1:5432/running_ai` 추가 → Spring만 재기동. (`.env` 수정 전 `.runtime\secret-backup\`에 사본 보관 — 기존 관행 그대로) |
| **(R4) 볼륨/데이터 이상 (최악)** | 컨테이너 기동 후 `pg_restore -U running_ai -d running_ai --clean --if-exists` 로 §2-1 덤프 복원. 덤프는 이미 격리 컨테이너에서 복원 검증을 마친 것이다 |
| **(R5) Watchdog이 개입** | `Disable-ScheduledTask -TaskName 'RunningAI-Watchdog'` 후 위를 수행. 락아웃 시 `scripts\windows\clear-watchdog-lockout.ps1 -Component postgres` |

> **어떤 경우에도 `docker compose down -v`·`docker volume rm`·DB 재초기화는 하지 않는다.** 데이터 문제는 항상 "덤프 복원"으로 푼다.

---

## STEP 3 — 운영 RC 확인 (전 항목 재검증 PASS)

| # | 항목 | 결과 | 실측 근거 |
|---|---|---|---|
| 1 | **RC SHA `2208872`** | PASS | `22088724a2bc6d4dd5be13272b597f2ea72b8955` 존재, `main-3` HEAD `34cf121`의 **조상**. `2208872..34cf121` 차이 = `.gitignore` 1건 + 7D-1 문서 2건 → **JAR 영향 없음** |
| 2 | **RC JAR SHA-256** | PASS | `ACEDE7D6B8FE658DBB037C85ACE82B80B58B09B79A51394EA388B2F8E9E562FA` · 75,397,694 B · 2026-10-13 07:43:48 — **7D-1 기록과 완전 일치** |
| 2b | 롤백용 운영 JAR | PASS | `1A7BE11B5DFF8E74A310A79EA945D900439767B232FC497E553767022227CA98` · 75,289,548 B · 2026-10-06 01:23:37 — **7D-1 기록과 완전 일치** |
| 3 | **FF-only 병합 가능성** | PASS | Canonical `main` `370ee8f`가 `main-3` `34cf121`의 조상 · Canonical 작업트리 **clean** · `origin/main`=`370ee8f`, `origin/main-3`=`24ff0b5` (이번에도 push 0건) |
| 4 | **V20 → V23 Runbook** | PASS | 저장소에 V1..V23 전부 존재 · **RC JAR 안에 `V21__create_injury_report.sql` / `V22__add_running_dynamics_to_activity_analysis.sql` / `V23__create_athlete_intensity_profile_history.sql` + `injury/WorkoutSafetyGateService.class` 실재 확인** · 운영 DB는 **현재 V20**(success) · 적용은 C의 Spring 재기동 시 Flyway 자동 (7D-1 실측 84 ms, 추가 전용) |
| 5 | **MCP 읽기/쓰기 기본 OFF** | PASS | 쓰기 `RUNNINGAI_MCP_ENABLED=false` (.env 명시) · 읽기 `RUNNINGAI_MCP_READ_ENABLED` **키 자체가 .env에 없음** → 소스 기본값 `false` (`application.yml:59`, `McpReadToolConfig` `@ConditionalOnProperty`) · **운영 실측 `GET 127.0.0.1:18080/mcp` → 404** |
| 6 | **Legacy Writer 4종 Disabled** | PASS | `RunningAI-CommandChannel` / `RunningAI-RemoteWakeupScheduler` / `RunningAI-TodayWorkout` / `RunningAI-TrainingCommand` = **전부 Disabled** (추가로 `RunningAI-CodexRequestWatchdog`도 Disabled) |
| 7 | **Injury Safety Gate 적용 절차** | PASS (절차 확정) | 아래 3-7 |

### 3-7. Injury Safety Gate ACTIVE 적용 절차 (배포 후 수행, 승인 항목 D)

- **현재 운영에는 존재하지 않는다**: `GET 127.0.0.1:18080/api/v1/athlete/injury-state` → **404** (구버전 JAR, V20 스키마). 정상이며 RC 배포 후 활성화된다.
- 입력: `POST /api/v1/athlete/injury-reports`
  `{"bodySite":"KNEE","side":"<실제>","painPresent":true,"painIntensity":<실제값>,"status":"ACTIVE","notes":"<실제 증상>"}`
- 기대 응답: `safety.nonRestTrainingAllowed=false`, `reasonCode=INJURY_ACTIVE`
- 검증: 비-REST Draft 승인 → `WORKOUT_SAFETY_INJURY_ACTIVE` 차단 / REST Draft 승인 → 200
- **정책 재확인** (소스 기준, `WorkoutSafetyPolicy` + `InjurySafetyProperties`)
  1. 보고 이력 없음 → REST only (`NO_INJURY_STATE_RECORDED`) — **데이터 부재를 "부상 없음"으로 보지 않는다**
  2. `UNKNOWN` → REST only
  3. **freshness 48시간 초과 → ACTIVE·RESOLVED·UNKNOWN 가리지 않고 REST only** (`INJURY_STATE_STALE`)
  4. `ACTIVE` → REST only. **이후 호출에서 증상 필드를 빼도 해제되지 않는다. 오직 명시적 `RESOLVED` 보고만 해제한다**
  5. 신선한 `RESOLVED` → 일반 훈련 "고려 가능" (의학적 clearance가 아니라 서버 허가)
- **운영 주의:** 48시간마다 재보고하지 않으면 자동으로 전면 차단으로 되돌아간다. `running-ai.injury-safety.staleness-hours`로 조정 가능 (기본 48).
- **전제 (7D-1과 동일):** 실제 상태를 그대로 입력한다. 통증이 남아 있는데 `RESOLVED`로 적는 것은 안전 게이트 우회다.

---

## STEP 4 — 최종 보고

### 4-1. PostgreSQL 노출 경로

| 경로 | 현재 | `127.0.0.1` 적용 후 |
|---|---|---|
| `127.0.0.1:5432` (루프백 IPv4) | 열림 — 운영 Spring이 사용 | **열림 (유지)** |
| `[::1]:5432` (루프백 IPv6, wslrelay) | 열림 | 닫힐 수 있음 — **영향 없음** (Spring은 IPv4 우선, §1-8 / R3 대비책 보유) |
| `192.168.0.24:5432` (LAN) | **열림** — 방화벽 `Docker Desktop Backend` 규칙이 허용 | **닫힘** |
| `100.108.54.79:5432` (Tailscale IPv4) | **열림** | **닫힘** |
| `[fd7a:115c:a1e0::cc01:36d4]:5432` (Tailscale IPv6) | **열림 — 7D-1 미기록 (F-2)** | **닫힘** |
| `172.22.80.1:5432` (WSL vEthernet) | 열림 | **닫힘** |
| 인터넷 (Funnel) | **닫힘** — Funnel은 `443 → 127.0.0.1:17845` 1건뿐 | 닫힘 (불변) |

유효 방어선은 **지금 `host all all all scram-sha-256`의 비밀번호 1개뿐**이며, 그 비밀번호가 보호하는 롤은 **클러스터의 유일한 슈퍼유저**다.
`ssl=off`이므로 LAN/tailnet 경유 트래픽은 평문이고, `log_connections=off`이므로 **과거 외부 접속 여부는 증명할 수 없다**
(확인한 것은 "현재 연결이 전부 루프백"이라는 스냅샷뿐이다).

### 4-2. 5432 제한에 따른 예상 영향

- **기능 영향: 0건.** 소비자 전수 조사 결과 Spring·Gradle 테스트·compose healthcheck·Watchdog·Connector·Relay 어느 것도 비루프백 5432를 쓰지 않는다.
- **비용:** 컨테이너 재생성 1회. **데이터 손실 없음** (명명 볼륨 유지).
- **잔여 위험 1건:** `localhost` → `::1` 우선 해석 (§1-8). JDK 기본 설정상 Spring은 IPv4를 먼저 시도하므로 가능성은 낮고, 대비책(R3 / 선택 항목 G-opt)을 준비했다.
- **부수 효과(긍정):** 슈퍼유저 단일 롤·평문 전송·접속 로그 부재의 위험이 **로컬 프로세스 범위로 축소**되어, 계정 최소 권한화(F-4)의 시급성이 High → Low로 내려간다.
- **외부 DB 접근이 필요해지면:** 포트를 다시 여는 대신 SSH/Tailscale 포트포워딩 또는 `docker exec psql`을 쓴다.

### 4-3. 백업·복구 준비 상태

| 항목 | 상태 |
|---|---|
| 백업 수단 | 준비됨 — `pg_dump 17.11` 컨테이너 내 사용 가능 |
| 백업 위치 | 준비됨 — `C:\running-ai-github\.runtime\backups\` 존재 (phase-6h-6 / 6h-7 덤프 보관 이력 있음), `.runtime/`는 git-ignored |
| 용량 | 충분 — DB 27 MB, 예상 덤프 ≈ 2.8 MB, 디스크 여유 **143.7 GB** |
| 복원 검증 방식 | 확정 — **운영 클러스터 무접촉**: `pg_restore --list` + 격리 임시 컨테이너 복원 + 행수 대조 |
| 기준선 행수 | 확보 — activity 8 / sample 21,828 / draft 19 / recovery 21 / fitness 99 / Flyway V20 |
| 진행 중 작업 | 없음 — `historical_backfill_run` = `COMPLETED` 1건, `RUNNING` 0건 |
| 선례 | 7D-1이 동일 방식의 `pg_dump -Fc` → `pg_restore` 왕복을 **전 행수·값·타임스탬프 완전 일치**로 이미 검증함 |

**백업 자체는 이번 Phase에서 수행하지 않았다** (운영 무변경 지시 준수). 명령은 §2-1에 확정되어 있다.

### 4-4. 실제 변경 명령 / 롤백 명령

**변경 (핵심):**

```powershell
Disable-ScheduledTask -TaskName 'RunningAI-Watchdog'
#   (여기서 §2-1 백업 + 복원 검증)
git -C C:\running-ai-github merge --ff-only main-3      # compose 1줄이 들어온다 (JAR 영향 없음)
powershell -File C:\running-ai-github\scripts\windows\stop-running-ai.ps1
powershell -File C:\running-ai-github\scripts\windows\start-running-ai.ps1   # up -d 가 재생성
Enable-ScheduledTask -TaskName 'RunningAI-Watchdog'
```

**롤백 (핵심):**

```powershell
Disable-ScheduledTask -TaskName 'RunningAI-Watchdog'
git -C C:\running-ai-github reset --hard 370ee8f        # 또는: git checkout -- docker-compose.yml
powershell -File C:\running-ai-github\scripts\windows\stop-running-ai.ps1
powershell -File C:\running-ai-github\scripts\windows\start-running-ai.ps1
Enable-ScheduledTask -TaskName 'RunningAI-Watchdog'
```

데이터 복구가 필요한 경우에만 추가로 §2-5 (R4)의 `pg_restore`.

### 4-5. 예상 서비스 중단 시간

| 구성요소 | 예상 중단 |
|---|---|
| PostgreSQL | **10 ~ 20 초** |
| Spring API | 40 ~ 90 초 |
| 워치페이스 / Relay | 40 ~ 90 초 (절차 B 선택 시 무중단) |
| Garmin connector | 40 ~ 90 초 |
| **백업 포함 전체 작업** | **3 ~ 5 분** |

근거: Spring 기동 실측 10.107 s, PG healthcheck 5 s 간격, stop 타임아웃 Spring 30 s / connector 15 s / relay 15 s, 덤프 ≈ 2.8 MB.

### 4-6. 적용 직후 확인 항목

§2-4의 17개 항목. 그중 **반드시 통과해야 하는 6개**:
**(1) 볼륨 `running-ai-github_running_ai_postgres_data` 보존 · (2) `HostIp=127.0.0.1` · (6) 행수 완전 일치 ·
(8) Spring `/actuator/health` UP · (11~13) LAN·Tailnet IPv4·IPv6 차단 · (14) 다른 기기에서의 차단 확정 검증.**

### 4-7. RC 운영 배포 가능 여부

**가능 — 7D-1의 GO를 유지한다.** STEP 3의 7개 항목이 전부 재검증 PASS이고, RC/롤백 JAR 해시가 7D-1 기록과 일치하며,
FF-only 병합 전제(Canonical clean + 조상 관계)가 그대로 성립한다. 운영 DB는 V20, 진행 중인 백필도 없다.

**권고 순서:** **G(+A) → 운영 확인 → 이후 별도 승인으로 B·C·D·E → (선택) F.**
G는 배포와 독립적이고 되돌리기 쉬우며, *지금 존재하는* 노출을 줄인다. 먼저 하는 편이 배포 시 변수도 줄인다.

---

## 5. 7D-1 문서에 대한 정정 사항 (후속 작업자가 반드시 알아야 할 것)

| ID | 7D-1 서술 | 정정 |
|---|---|---|
| **F-1** | 중단 조건: "`docker volume ls`에 **`running_ai_postgres_data`**가 없으면 중단" | 실제 볼륨명은 **`running-ai-github_running_ai_postgres_data`** (compose 프로젝트 접두사). 원문대로 확인하면 **정상 상황도 항상 중단으로 오판**한다 |
| **F-2** | tailnet 도달을 IPv4 `100.108.54.79`로만 실측 | **IPv6 `[fd7a:115c:a1e0::cc01:36d4]:5432`도 열려 있다.** 적용 후 반드시 확인 대상에 포함 |
| **F-3** | (언급 없음) | `docker-compose.override.yml` 방식은 **동작하지 않는다** (스크립트가 `-f` 명시). 손으로 `-f` 2개로 재생성해도 **다음 `start-running-ai.ps1`에서 0.0.0.0로 조용히 되돌아간다** |
| **F-4** | (언급 없음) | `running_ai`는 **클러스터의 유일한 슈퍼유저**다. `NOSUPERUSER` 강등은 **복구 불가 락아웃**을 만든다. 최소 권한화는 반드시 별도 Phase |
| F-5 | "tailnet 전체 = 사용자의 모든 Tailscale 기기가 도달" | 현재 tailnet 피어는 **개인 기기 0대** (전부 Tailscale `funnel-ingress-node`). 정확한 위험은 **"기기 1대 추가 시 즉시 노출"** |
| F-6 | G 절차: "Spring 중지 → `docker compose up -d` → Spring 기동" | `start-running-ai.ps1`이 이미 `up -d`를 수행하므로 **별도 docker 명령이 불필요**하다. 또한 `stop-running-ai.ps1`은 **Relay와 Connector도 함께 중지**한다 (중단 범위가 Spring만이 아니다) |

---

## 6. 변경 파일 / 한계

**운영 변경 0건.** 소스 코드(`server/src/**`) 0줄, 운영 스크립트(`scripts/windows/**`) 0줄, `docker-compose.yml` 0줄,
운영 `.env` 0줄, 방화벽·포트·컨테이너·DB·Funnel·Relay·Watchdog·Canonical `main`·`origin` 전부 무변경.

이번 Phase가 만든 파일 (문서 3건, `main-3` 워크트리):

| 파일 | 내용 |
|---|---|
| `docs/work-orders/2026-10-13-phase-7d-2-postgresql-preflight-instruction.md` | 지시서 원문 보존 |
| `docs/work-orders/2026-10-13-phase-7d-2-postgresql-preflight-result.md` | 본 문서 |
| `docs/work-orders/2026-10-13-phase-7d-2-item-g-approval-request.md` | **G 항목 승인 요청서** |

### 한계 (정직하게)

1. **외부 기기에서의 차단 확정 검증은 아직 불가능하다.** 이번 도달성 프로브는 전부 같은 호스트에서 출발했으므로 Windows 방화벽을 우회했을 수 있다.
   적용 후 **다른 기기에서 1회** `Test-NetConnection 192.168.0.24 -Port 5432` 실패를 확인해야 "차단됨"이 확정된다 (§2-4 #14).
2. **과거 외부 접속 여부는 여전히 증명 불가다** (`log_connections=off`). 확인한 것은 "현재 연결이 전부 루프백"이라는 스냅샷뿐이다.
3. **컨테이너 재생성 동작은 실제로 실행해 보지 않았다.** 운영 무변경 지시에 따라 compose 문법·`host_ip` 결과·볼륨 접두사 규칙만
   스크래치패드 복사본으로 검증했다. "config-hash 변경 → 재생성"은 Compose의 문서화된 표준 동작이지만, 이번에 운영에서 실측한 사실은 아니다.
4. `[::1]:5432` 리스너(wslrelay)가 적용 후 남을지는 **확정할 수 없다.** 영향 없을 것으로 평가하되 대비책(R3)을 준비했다.
5. `main-3_running_ai_postgres_data` 고아 볼륨 (2026-10-10, 미사용) 은 **건드리지 않았다.** 정리는 별도 판단 사항이며 G 절차에 포함하지 않는다.
