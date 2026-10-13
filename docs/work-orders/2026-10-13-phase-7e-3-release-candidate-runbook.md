# Phase 7E-3 — V21~V26 Release Candidate 런북 + RISK-H3 수정 절차

작성일 2026-10-13 · 브랜치 `main-3` @ `d7b40b7` · **아직 어느 것도 운영에 적용하지 않았다**

이 문서는 **실행 계획**이다. 모든 절차는 운영 DB의 정확한 복제본
(`running_ai_restoretest`, `pg_dump`로 뜬 V20 운영 사본)에서 **실제로 리허설해 측정한 값**으로 쓰였고,
추정치는 하나도 없다. 운영 적용은 승인 후 별도 세션에서 한다.

---

## 0. 리허설에서 측정된 것

| 단계 | 측정 결과 | 소요 |
|---|---|---|
| `pg_dump -Fc` 운영 백업 | 2,837,204 바이트 | 수 초 |
| 사본으로 복원 + 12개 테이블 대조 | **전 항목 일치** | 수 초 |
| Flyway V21~V26 | **6/6 성공, 순서대로** | **135 ms** |
| V20 데이터 보존 | 11개 테이블 **운영과 행 수 동일** | — |
| 분석 재계산(Garmin 8건) | 8 재계산 / 0 실패, 7건이 다이내믹스 획득 | ~10 s |
| Intervals 전용 Import | 25 적재 / 46,880 샘플 / 0 실패 | **2 m 36 s** |
| Strava 아카이브 Import | 15 적재 / 33 보강 / 116 랩 / 18,541 샘플 / 0 실패 | **1 m 31 s** |
| 전체 시퀀스 재실행 | **8개 테이블 Δ=0** (완전 멱등) | — |
| 최종 상태 | 48 활동 · 75,206 샘플 · 239 랩, 2026-06-05~10-10 | — |

리허설 최종 상태는 독립적으로 만든 `running_ai_realcopy`와 **행 수까지 완전히 일치**했다.
즉 이 절차는 재현 가능하다.

---

## 1. RISK-H3 — PostgreSQL 포트 노출

### 1.1 현재 상태 (2026-10-13 재측정)

| 지점 | 5432 도달 | 비고 |
|---|---|---|
| `127.0.0.1` | **가능** | 정상 |
| `192.168.0.24` (LAN) | **가능** | ← 문제 |
| `100.108.54.79` (Tailscale) | **가능** | ← 문제 |
| `172.22.80.1` (WSL) | **가능** | ← 문제 |

대조군 — Spring 18080/18081/18082와 게이트웨이 17910은 **LAN·tailnet 양쪽에서 전부 닫혀 있다**.
노출된 것은 PostgreSQL 하나뿐이다.

원인은 `docker-compose.yml`의 `ports: - "${POSTGRES_PORT:-5432}:5432"`다. 호스트 IP를 적지 않으면
Docker가 **모든 인터페이스**(`0.0.0.0` + `[::]`)에 게시한다. Windows 방화벽 프로파일은 세 개 모두
켜져 있지만 **Docker Desktop의 포트 게시는 이를 우회**하므로 방화벽은 이 건의 방어선이 아니다.
7D-2에서 이미 LAN·tailnet 양쪽에서 **평문 superuser 인증 성공**까지 실측됐다.

### 1.2 선결 조건 — 먼저 고쳐야 할 것 (중요)

리허설 중 확인한 사실:

```
localhost  ->  ::1          (IPv6 먼저)
           ->  127.0.0.1
```

운영 Spring은 `.env`에 `DB_URL`이 **없어서** `application-local.yml`의 기본값
`jdbc:postgresql://localhost:5432/running_ai`를 쓴다. 지금은 IPv6 리스너(`::1:5432`)가 있어서 문제가
없지만, 바인딩을 `127.0.0.1`로 좁히면 **그 리스너가 사라진다**. PgJDBC는 해석된 주소를 순회하므로
결국 IPv4로 붙긴 하지만, 매 커넥션마다 실패 시도가 한 번씩 선행된다 — 드라이버의 폴백 동작에
운영을 의존시키는 셈이다.

**따라서 포트 변경 전에, 별개의 무중단 작업으로 먼저:**

```
# canonical C:\running-ai-github\.env 에 한 줄 추가
DB_URL=jdbc:postgresql://127.0.0.1:5432/running_ai
```

운영 Spring 재기동 후 `/api/v1/health`와 활동 8건 조회로 확인한다. 이 변경은 **단독으로 안전**하고
되돌리기도 그 줄을 지우는 것뿐이다.

### 1.3 변경안 (미적용)

```diff
   ports:
-      - "${POSTGRES_PORT:-5432}:5432"
+      # Loopback ONLY. Without the host IP Docker publishes on every interface (0.0.0.0 and [::]),
+      # and Docker Desktop's publishing bypasses the Windows Firewall - which is how 5432 came to
+      # accept cleartext superuser connections from the LAN and the tailnet (RISK-H3, measured in
+      # Phase 7D-2 and re-measured in 7E-3). Every known client is on this host:
+      # production Spring, the dev instances, and `docker exec psql` (which never leaves the
+      # container). Pair this with DB_URL=jdbc:postgresql://127.0.0.1:5432/... because `localhost`
+      # resolves to ::1 first here and this binding publishes IPv4 only.
+      - "127.0.0.1:${POSTGRES_PORT:-5432}:5432"
```

### 1.4 영향 분석

| 클라이언트 | 접속 방법 | 영향 |
|---|---|---|
| 운영 Spring 18080 | `localhost:5432` → **1.2에서 `127.0.0.1`로 고정** | 없음 (선결 조건 이행 시) |
| 개발 인스턴스 18081/18082/18083 | `127.0.0.1:5432` 명시 | 없음 |
| `docker exec … psql` | 컨테이너 내부 소켓 | 없음 |
| Watchdog / Relay / Watchface / Funnel | DB 미사용 | 없음 |
| LAN·tailnet·WSL의 외부 클라이언트 | — | **차단됨 (이것이 목적)** |

측정: 현재 5432에 맺힌 established 커넥션은 **전부 `127.0.0.1` 경유 하나뿐**이다. LAN·tailnet에서
실제로 쓰고 있는 클라이언트는 없다.

**주의**: `docker compose up -d`는 포트 변경 시 **컨테이너를 재생성**한다. 데이터는 named volume
`running_ai_postgres_data`에 있어 보존되지만, 재생성 동안 수 초~수십 초의 DB 단절이 생기고 운영
Spring의 Hikari 풀이 재연결한다. 운영 Spring을 먼저 내리고 나중에 올리는 편이 깔끔하다.

### 1.5 실행 순서 (승인 후)

```
0. 사전       : pg_dump -Fc 백업 + SHA-256 기록          <- 1.6
1. 선결       : .env 에 DB_URL=…127.0.0.1… 추가, 운영 Spring 재기동, health 확인
2. 운영 중지  : scripts\windows\stop-running-ai.ps1
3. compose    : 위 diff 적용
4. 재생성     : docker compose up -d   (볼륨 보존 확인: docker volume ls)
5. 검증       : docker ps 가 127.0.0.1:5432->5432 만 보이는지
                Test-NetConnection 192.168.0.24 -Port 5432  -> False 기대
                Test-NetConnection 100.108.54.79 -Port 5432 -> False 기대
                Test-NetConnection 127.0.0.1 -Port 5432     -> True  기대
6. 운영 기동  : scripts\windows\start-running-ai.ps1
7. 검증       : /api/v1/health, 활동 8건, 샘플 21,828행
```

### 1.6 백업 · 롤백 (실제로 검증함)

```
# 백업 (운영 무중단, 읽기 전용)
docker exec running-ai-postgres pg_dump -U running_ai -Fc -d running_ai > .runtime\backup\running_ai-prod-<ts>.dump

# 복원 검증 (운영을 건드리지 않고 사본에서)
docker exec running-ai-postgres psql -U running_ai -d postgres -c "CREATE DATABASE running_ai_restoretest;"
docker exec -i running-ai-postgres pg_restore -U running_ai -d running_ai_restoretest < <dump>
# -> 12개 테이블 행 수 대조. 본 리허설에서 전 항목 일치 확인.
```

**롤백**: compose의 `ports` 한 줄을 되돌리고 `docker compose up -d`. 스키마도 데이터도 바뀌지 않으므로
덤프는 필요 없다 — 포트 변경의 롤백은 포트 변경뿐이다.

### 1.7 범위에서 뺀 것

**DB 역할·권한 변경은 하지 않는다.** 현재 `running_ai`가 superuser인 점은 별개의 문제이고, 최소권한
역할로 바꾸는 작업은 애플리케이션 권한 검증이 따로 필요하다. 이번 변경은 **네트워크 노출면만** 줄인다.
`POSTGRES_PASSWORD`/`DB_PASSWORD` 교체도 별건이다.

---

## 2. V21~V26 Release Candidate

### 2.1 마이그레이션 성격

| 버전 | 내용 | 기존 데이터 영향 |
|---|---|---|
| V21 | `injury_report` 신설 | 없음 (신규 테이블) |
| V22 | `activity_analysis`에 러닝 다이내믹스 컬럼 추가 | 없음 (nullable 추가) |
| V23 | `athlete_intensity_profile_history` 신설 | 없음 (신규 테이블) |
| V24 | `activity_analysis`에 리비전·다이제스트 추가 | 기존 행을 리비전 0으로 표시 → **재계산 대상** |
| V25 | `intervals_activity_import` 신설 | 없음 (신규 테이블) |
| V26 | `strava_activity_import` 신설 | 없음 (신규 테이블) |

**전부 가산적(additive)이다.** 컬럼 삭제·타입 변경·데이터 이동이 없다. 리허설에서 V20 데이터
11개 테이블 전부가 행 수까지 보존됨을 확인했다.

### 2.2 배포 순서 (승인 후)

```
1. 백업            pg_dump -Fc            (1.6)
2. 운영 Spring 중지
3. 기동            Flyway가 V21~V26 자동 적용 (측정 135 ms)
4. 확인            flyway_schema_history 26행, success 전부 t
                   활동 8 / 샘플 21,828 / 드래프트 19 불변
5. 분석 재계산     POST /api/v1/activities/analysis-refresh?dryRun=true   -> pending 8 예상
                   POST /api/v1/activities/analysis-refresh               -> reanalysed 8
6. 재확인          재실행 시 reused 8 / reanalysed 0
```

5번은 **필수다**. V24 이후 기존 8건은 리비전 0이 되어 "오래된 분석"으로 잡히고, 재계산해야
7건이 러닝 다이내믹스를 얻는다(활동 2 = INDOOR_CYCLING은 설계상 대상 아님).

### 2.3 롤백

| 상황 | 절차 |
|---|---|
| 기동 실패 / Flyway 실패 | 이전 jar로 되돌리고 **덤프에서 복원**. V21~V26은 `down` 스크립트가 없다 |
| 적용은 됐으나 동작 이상 | 덤프 복원이 유일하게 확실한 경로. 스키마가 앞섰으므로 코드만 되돌리면 Hibernate validate가 실패한다 |
| 분석 재계산 결과 불만족 | 재계산은 **덮어쓰기가 아니라 교체**이며 원본 샘플·랩·존을 건드리지 않는다. 원본이 그대로이므로 언제든 다시 계산할 수 있다 |

**핵심**: 롤백 수단은 2.2의 1번 덤프 하나뿐이다. 백업 없이는 시작하지 않는다.

---

## 3. 과거 데이터 Import 실행 순서

마이그레이션과 **분리된 별도 승인 항목**이다(C, D). 순서에 의미가 있다.

```
전제: V21~V26 적용 완료 + 분석 재계산 완료 + 게시 스위치·Garmin 스케줄러 전부 false
      (둘 다 이 조건이 아니면 스스로 기동을 거부한다)

1. Intervals 전용 Import      POST /api/v1/intervals/activity-import
                              {"oldest":"2026-07-01","newest":"2026-10-13"}
                              기대: imported 25 / samples 46,880 / failed 0     (측정 2m36s)

2. Strava 아카이브 Import     POST /api/v1/strava/archive-import  {}
                              기대: imported 15 / enriched 33 / laps 116
                                    samplesEnriched 18,541 / failed 0           (측정 1m31s)
```

**순서를 바꾸면 안 된다.** Strava Importer는 Intervals가 적재한 25건을 `apple_health_uuid`로 찾아
보강한다. 먼저 돌리면 그 25건이 아직 없어 보강 대상이 사라지고, Strava가 같은 운동을 **새 활동으로
만들지 않도록** 막아 주는 것은 식별자 매칭이다.

전제 조건: 아카이브를 운영 PC의 **저장소 밖** 경로에 풀고 `RUNNING_AI_STRAVA_ARCHIVE_PATH`로 지정.
두 Importer 모두 기본 off이며 명시적 POST 외에 트리거가 없다. 재-POST가 곧 재개이자 재시도다.

**중단해도 안전하다**: 리허설에서 전체 시퀀스를 다시 돌렸을 때 8개 테이블 Δ=0이었다.

---

## 4. ChatGPT 실데이터 조회 전환

### 4.1 현재 구조 (무변경)

```
ChatGPT → Worker(고정 https) → Quick Tunnel → Gateway 17910 → Spring 18081 → running_ai_7d_smoke (합성)
                                                                  18082 → running_ai_realcopy (실데이터, 루프백 전용)
```

### 4.2 전환 방법

**환경변수 1개 + 게이트웨이 재기동.**

```
RUNNING_AI_GATEWAY_TARGET_URL:  http://127.0.0.1:18081/mcp   ->   http://127.0.0.1:18082/mcp
```

ChatGPT 재등록 불필요(Worker 주소가 고정). **롤백은 되돌려 재기동, 약 10초.**
합성 18081은 전환 중에도 **계속 띄워 둔다** — 그래야 롤백이 즉시 가능하다.

### 4.3 읽기 전용임을 보장하는 것 (실측)

| 층 | 보장 |
|---|---|
| Spring 18082 | 노출 MCP 도구 **7종 전부 읽기**, `publish_workout` **미노출**. 게시·스케줄러 스위치 전부 false |
| Gateway | 도구 allowlist가 읽기 7종. `publish_workout`이 목록에 있으면 **기동 자체가 실패**(server.js:65) |
| OAuth | scope `runningai:read`, GitHub 계정 **1개**(숫자 id), 무토큰·위조 토큰 전부 401 |
| 네트워크 | 터널은 17910만 노출. 18082는 `127.0.0.1` 전용 |

### 4.4 선결 조건

**RISK-H3(1장)을 먼저 해결한다.** 실데이터가 공개 HTTPS 경로에 올라가는 순간, 같은 호스트의
5432가 LAN·tailnet에 열려 있다는 사실의 의미가 달라진다. 순서는 **1장 → 4장**이다.

### 4.5 다음 Phase로 미루는 것

쓰기·훈련 생성·승인·게시(승인 항목 F)는 전부 이후 Phase다. 본 전환은 **읽기만** 연다.

---

## 5. 승인이 필요한 항목

| # | 항목 | 운영 영향 | 선행 |
|---|---|---|---|
| **A** | `.env`에 `DB_URL=…127.0.0.1…` 추가 + 재기동 | 재기동 1회 | — |
| **B** | docker-compose 5432 루프백 제한 (RISK-H3) | DB 재생성, 수십 초 단절 | A |
| **C** | V21~V26 적용 + 분석 재계산 | 재기동 + 8건 재계산 | 백업 |
| **D** | Intervals 전용 Import (25건) | 데이터 추가 | C |
| **E** | Strava 아카이브 Import (15건 + 33건 보강) | 데이터 추가 | D |
| **F** | ChatGPT 실데이터 읽기 전환 | 게이트웨이 env 1개 | B |
| **G** | ChatGPT 쓰기·게시 확장 | — | 이후 Phase |

A~G 중 **어느 것도 실행하지 않았다.**
