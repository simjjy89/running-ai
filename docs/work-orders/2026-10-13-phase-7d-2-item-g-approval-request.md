# 승인 요청서 — 운영 변경 항목 G: PostgreSQL 5432 바인딩을 루프백으로 제한

요청일: 2026-10-13 (KST) · 요청 근거: `2026-10-13-phase-7d-2-postgresql-preflight-result.md` (점검 완료, 운영 변경 0건)
대상 PC: Main PC · 대상 자원: `C:\running-ai-github\docker-compose.yml` 1줄, 컨테이너 `running-ai-postgres` 재생성 1회

---

## 1. 무엇을 승인해 달라는 것인가

**운영 PostgreSQL의 호스트 공개 주소를 `0.0.0.0`(+`::`)에서 `127.0.0.1`로 바꾸는 것** 1건이다.

```diff
 # C:\running-ai-github\docker-compose.yml
     ports:
-      - "${POSTGRES_PORT:-5432}:5432"
+      - "127.0.0.1:${POSTGRES_PORT:-5432}:5432"
```

DB 데이터·스키마·PostgreSQL 설정·이미지·방화벽 규칙·Funnel·Relay·Watchdog 구성은 **일절 바꾸지 않는다.**

---

## 2. 왜 필요한가 (실측 근거)

| 사실 | 실측 |
|---|---|
| 5432가 모든 인터페이스에 게시되어 있다 | `0.0.0.0:5432` + `[::]:5432` |
| 방화벽이 막지 않는다 | `Docker Desktop Backend` 규칙 = TCP / LocalPort **Any** / **Private+Public** / Allow / Enabled |
| LAN에서 도달한다 | `192.168.0.24:5432` CONNECT OK |
| Tailnet IPv4에서 도달한다 | `100.108.54.79:5432` CONNECT OK |
| **Tailnet IPv6에서도 도달한다** | `[fd7a:115c:a1e0::cc01:36d4]:5432` CONNECT OK — **7D-1이 기록하지 못한 경로** |
| 전송이 평문이다 | `ssl = off` — 심박·수면·HRV·페이스가 네트워크에 평문으로 흐름 |
| 앱 계정이 유일한 슈퍼유저다 | `running_ai` = 이 클러스터의 **유일한 로그인 롤이자 유일한 슈퍼유저** |
| 접속 감사 기록이 없다 | `log_connections = off` — **"외부 접속이 없었다"를 증명할 수 없다** |
| 유효 방어선 | **비밀번호 1개뿐** (`host all all all scram-sha-256`) |

현재 tailnet에 개인 기기가 0대라 "지금 당장 누가 들어오고 있다"는 상황은 아니다.
그러나 **휴대폰이나 노트북을 tailnet에 추가하는 순간, 그 기기는 아무 설정 없이 운영 DB에 TCP 도달한다.**
인터넷 직노출은 아니다 (Funnel은 `443 → 127.0.0.1:17845` 1건만 게시).

---

## 3. 영향 범위 — 기능 영향 0건 (전수 조사 완료)

| 소비자 | 접속 방식 | 영향 |
|---|---|---|
| 운영 Spring | `jdbc:postgresql://localhost:5432/...` (실측 연결 전부 `127.0.0.1`) | 없음 |
| Garmin connector | DB 미사용 (소스 참조 0건) | 없음 |
| External relay | DB 미사용 (`today_plan.json` 파일만 읽음) | 없음 |
| Watchdog | Docker API만 사용 (5432 미사용) | 없음 |
| compose healthcheck | 컨테이너 내부 `pg_isready` | 없음 |
| Gradle 테스트 | `localhost` | 없음 |

**잔여 위험 1건:** 이 호스트에서 `localhost`는 `::1`을 먼저 해석한다. JDK 기본값상 Spring은 IPv4를 먼저 시도하므로
영향 가능성은 낮으며, 실패 시 대비책(아래 R3 / 선택 항목 G-opt)을 준비했다.

---

## 4. 실행 절차 (승인 시)

```powershell
# G-0  사전 확인 (읽기 전용): Watchdog 상태 / 컨테이너 / 볼륨 / 백필 RUNNING 0건 / Canonical git clean
# G-1  Watchdog 중지
Disable-ScheduledTask -TaskName 'RunningAI-Watchdog'

# G-2  백업 + 복원 검증 (운영 클러스터 무접촉)
#      pg_dump -Fc → docker cp → SHA-256 → pg_restore --list → 격리 임시 컨테이너 복원 → 행수 대조
#      (전체 명령은 결과보고서 §2-1)

# G-3  compose 1줄 반영  ※ 승인 항목 A(FF-only 병합)와 함께 수행
git -C C:\running-ai-github merge --ff-only main-3
Select-String -Path C:\running-ai-github\docker-compose.yml -Pattern '5432'

# G-4  중지 → 재생성 → 기동 (운영 스크립트만 사용)
powershell -File C:\running-ai-github\scripts\windows\stop-running-ai.ps1
powershell -File C:\running-ai-github\scripts\windows\start-running-ai.ps1

# G-5  Watchdog 복귀
Enable-ScheduledTask -TaskName 'RunningAI-Watchdog'
```

**왜 A(FF 병합)와 묶는가:** 운영 스크립트는 항상 `-f C:\running-ai-github\docker-compose.yml`을 명시하므로
`docker-compose.override.yml`은 읽히지 않고, 수동 `-f` 2개 방식은 **다음 번 기동에서 조용히 `0.0.0.0`으로 되돌아간다.**
추적 파일 자체를 바꾸는 것이 유일하게 지속되는 방법이다.
`docker-compose.yml`은 JAR에 포함되지 않으므로 **RC JAR SHA-256은 바뀌지 않고**, A만으로는 운영 동작도 바뀌지 않는다
(JAR 재빌드 전까지). 따라서 **G(+A)는 RC 배포(B·C·D·E)와 독립적으로 먼저 수행할 수 있다.**

---

## 5. 예상 중단 시간

| 구성요소 | 예상 |
|---|---|
| PostgreSQL | **10 ~ 20 초** |
| Spring API / Relay(워치페이스) / Connector | 40 ~ 90 초 |
| 백업 포함 전체 작업 | **3 ~ 5 분** |

중단을 더 줄이고 싶다면 **절차 B**(= `docker compose up -d`만 실행, Spring/Relay/Connector 무중단, DB만 10~20초)를 선택할 수 있다.
다만 상태 전이가 덜 결정적이므로 기본 권고는 절차 A다.

---

## 6. 적용 직후 확인 (반드시 통과해야 하는 6개)

1. 볼륨 **`running-ai-github_running_ai_postgres_data`** 존재 (CreatedAt `2026-09-29T14:42:47Z` 유지)
2. `HostConfig.PortBindings` = `{"5432/tcp":[{"HostIp":"127.0.0.1","HostPort":"5432"}]}`
3. 행수 완전 일치 — activity 8 / activity_sample 21,828 / workout_draft 19 / garmin_recovery_daily 21 / intervals_fitness_daily 99, Flyway **V20**
4. `GET 127.0.0.1:18080/actuator/health` → 200 / UP
5. `192.168.0.24:5432` · `100.108.54.79:5432` · `[fd7a:115c:a1e0::cc01:36d4]:5432` **전부 차단**
6. **다른 기기에서 1회** `Test-NetConnection 192.168.0.24 -Port 5432` 실패 — 같은 호스트 프로브는 방화벽을 우회할 수 있어 **이것만이 확정 증거다**

나머지 11개 확인 항목은 결과보고서 §2-4.

---

## 7. 롤백

```powershell
Disable-ScheduledTask -TaskName 'RunningAI-Watchdog'
git -C C:\running-ai-github reset --hard 370ee8f        # 또는 git checkout -- docker-compose.yml
powershell -File C:\running-ai-github\scripts\windows\stop-running-ai.ps1
powershell -File C:\running-ai-github\scripts\windows\start-running-ai.ps1
Enable-ScheduledTask -TaskName 'RunningAI-Watchdog'
```

- (R3) Spring이 DB에 붙지 못하면 → `.env`에 `DB_URL=jdbc:postgresql://127.0.0.1:5432/running_ai` 추가 후 Spring만 재기동
- (R4) 데이터 이상 시에만 → §2-1 덤프를 `pg_restore --clean --if-exists`로 복원

**금지 사항 (절차·롤백 모두):** `docker compose down -v`, `docker volume rm`, `docker volume prune`, `docker system prune`,
DB 재초기화, 워크트리에서의 `docker compose up`.

---

## 8. 중단 조건 (하나라도 해당하면 즉시 중단)

1. 백업 덤프 부재 / 2 MB 미만 / `pg_dump` 비정상 종료
2. `pg_restore --list` 실패 또는 격리 복원 행수 불일치
3. `historical_backfill_run`에 `RUNNING` 존재
4. Canonical 작업트리가 dirty하거나 `--ff-only` 거부
5. 재생성 후 **`running-ai-github_running_ai_postgres_data`** 볼륨 부재
6. 운영 DB 행수 불일치
7. Spring이 90초 내 `UP`이 되지 않음 (먼저 R3 시도 후에도 실패 시)
8. Watchdog 컴포넌트 `LOCKED_OUT` 발생

---

## 9. 승인 요청 항목

| # | 항목 | 필수 여부 | 요청 |
|---|---|---|---|
| **G** | `docker-compose.yml` ports → `127.0.0.1:${POSTGRES_PORT:-5432}:5432` + 컨테이너 재생성 1회 | **본 요청의 본체** | **승인 요청** |
| **G-전제 A** | `git -C C:\running-ai-github merge --ff-only main-3` (compose 1줄 반영 경로) | **필수** — 이것 없이는 변경이 지속되지 않음 | **동시 승인 요청** |
| **G-opt** | 운영 `.env`에 `DB_URL=jdbc:postgresql://127.0.0.1:5432/running_ai` 1줄 추가 | 선택 | `localhost`→IPv6 잔여 위험을 0으로 만들고 싶을 때만. 미승인 시 R3로 사후 대응 |
| **G-후속** | DB 계정 최소 권한화 (`running_ai_app` 분리) | **이번 요청에 포함하지 않음** | 별도 Phase 제안. `running_ai`는 **유일한 슈퍼유저**라 강등 시 복구 불가 락아웃 — 단독으로 다뤄야 한다 |
| **G-후속2** | `log_connections = on` (접속 감사) | **이번 요청에 포함하지 않음** | 별도 판단. 현재는 외부 접속 이력 증명이 구조적으로 불가능하다는 사실만 기록 |

> **승인 범위를 벗어나는 행위는 하지 않는다.** B(백업+마이그레이션)·C(재빌드·재기동)·D(부상 상태 입력)·E(Watchdog/Relay 검증)·F(원격 push)는
> 본 요청에 **포함되지 않으며**, 각각 별도 승인 대상이다.

---

## 10. 요청 상태

**승인 대기.** 승인 전까지 운영 PostgreSQL·운영 Spring·Funnel·Relay·Watchdog·Canonical `main`·`origin`에 대한 변경은 **0건을 유지한다.**
