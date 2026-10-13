# Phase 7E-3 — 운영 통합 준비 및 RISK-H3 해결 결과

작성일 2026-10-13 · 브랜치 `main-3` @ `d7b40b7`
지시문 원문: [2026-10-13-phase-7e-3-instruction.md](2026-10-13-phase-7e-3-instruction.md)
런북: [2026-10-13-phase-7e-3-release-candidate-runbook.md](2026-10-13-phase-7e-3-release-candidate-runbook.md)

**운영 DB·운영 Spring·docker-compose.yml·Relay·Funnel·Watchface·Watchdog 전부 무변경.
canonical main 병합 없음, origin push 없음.** STEP 1~5 수행 후 STOP한다.

---

## 0. 한 줄 결론

미커밋 67개 경로를 **6개 논리 커밋**으로 정리했고(비밀키·FIT 원본·GPS 0건 유입), V21~V26 전체
배포 시퀀스를 **운영 DB의 정확한 복제본에서 끝까지 리허설**해 모든 단계의 소요와 결과를 실측했다.
RISK-H3는 재측정 결과 여전히 열려 있으며, 수정 중 **운영을 깨뜨릴 수 있는 선결 조건 하나**를
리허설 중에 발견했다(§2.2).

---

## 1. STEP 1 — Git 정리

### 1.1 커밋 목록

| # | 커밋 | 내용 | 파일 |
|---|---|---|---|
| 1 | `dc5e7c4` | docs: 7D-2 / 7D-GPT-LIVE-2,3 / 7E-0 작업지시서 보존 | 14 |
| 2 | `c5fb255` | feat(mcp-worker): Cloudflare Worker OAuth 인가 서버 | 13 |
| 3 | `8cf2ed7` | feat(intervals): Intervals 전용 Import + 분석 3요소 정체성 (V24, V25) | 39 |
| 4 | `8be8db1` | feat(strava): Strava 데이터 export Import (V26) | 24 |
| 5 | `36e81cf` | chore(dev): 세션과 무관하게 사는 개발 Spring 런처 | 1 |
| 6 | `d7b40b7` | chore(dev): 런처가 Intervals Importer도 켤 수 있게 | 1 |

작업 트리 **깨끗함**(미커밋 0). 분류 근거: 커밋 2는 7D-2, 커밋 3은 7E-1, 커밋 4는 7E-2 산출물이다.

### 1.2 Phase 간 충돌 처리

4개 파일이 **두 Phase의 변경을 동시에** 담고 있었다. 한 커밋에 몰아넣지 않고 중간 상태를 만들어
갈랐다:

| 파일 | 7E-1 커밋 | 7E-2 커밋 |
|---|---|---|
| `application.yml` | `intervals.activity-import` 블록 | `strava.archive-import` 블록 |
| `SchemaMigrationTest.java` | V24·V25 (총 25) | V26 추가 (총 26) |
| `CLAUDE.md` | 분석 버저닝 + Intervals Import + Strava **복구 예정** 노트 | 그 노트를 **Strava Import 구현**으로 교체 |
| `.env.example` | `RUNNING_AI_INTERVALS_ACTIVITY_IMPORT_ENABLED` | `RUNNING_AI_STRAVA_ARCHIVE_*` |

**중간 커밋이 실제로 green인지 검증했다**: `8cf2ed7`(7E-1)을 격리된 임시 worktree에 체크아웃해
전체 테스트를 돌렸고 **BUILD SUCCESSFUL**. 최종 HEAD에서도 **1,385건 PASS / 0 실패**.

### 1.3 비밀·개인정보 검사

| 검사 | 결과 |
|---|---|
| 비밀키·토큰·client_secret 리터럴 | **0건** |
| JWK 개인 성분(`"d"`) | **0건** |
| FIT 원본 바이트(`*.fit`, `*.gz`, `*.zip`) | **0건** — 테스트 픽스처는 전부 SDK로 생성한 합성 파일 |
| 실제 GPS 좌표 | **0건** — 픽스처 좌표는 의도적으로 실제 장소가 아님 |
| `.env` 실파일 | **0건** |

**중요한 함정 하나**: `tools/mcp-worker/test/fixtures/.wrangler/`에 `wrangler dev` 테스트가 남긴
miniflare 상태(KV blob — OAuth grant와 토큰 해시 포함, SQLite, dev 로그)가 있었다. `.gitignore`
변경을 **Worker와 같은 커밋에** 넣어 막았다. 실제로 git이 스테이징한 Worker 파일은 소스 11개뿐이고
`.wrangler/`·`node_modules/`·`*.log`는 0건임을 확인했다.

---

## 2. STEP 2 — RISK-H3

### 2.1 재측정 — 여전히 열려 있다

| 지점 | 5432 | Spring 18080/18081/18082·게이트웨이 17910 |
|---|---|---|
| `127.0.0.1` | 도달 | 도달 |
| `192.168.0.24` (LAN) | **도달** | 전부 차단 |
| `100.108.54.79` (Tailscale) | **도달** | 전부 차단 |
| `172.22.80.1` (WSL) | **도달** | 전부 차단 |

노출된 것은 PostgreSQL 하나뿐이다. 원인은 compose의 `ports: "5432:5432"`에 호스트 IP가 없어
Docker가 모든 인터페이스에 게시하는 것이고, **Docker Desktop의 포트 게시는 Windows 방화벽을
우회**하므로 방화벽(세 프로파일 모두 켜짐)은 방어선이 아니다. 방화벽 규칙 열거는 관리자 권한이
필요해 실패했지만, 도달성 실측이 더 직접적인 증거다.

### 2.2 발견 — 그냥 바꾸면 운영이 흔들린다

리허설 중 확인:

```
localhost  ->  ::1  (IPv6 먼저)  ->  127.0.0.1
```

운영 Spring은 `.env`에 `DB_URL`이 **없어서** 기본값 `jdbc:postgresql://localhost:5432/...`를 쓴다.
바인딩을 `127.0.0.1`로 좁히면 IPv6 리스너가 사라지고, PgJDBC가 `::1`을 먼저 시도해 실패한 뒤
IPv4로 폴백한다. 연결은 되지만 **드라이버의 폴백 동작에 운영을 의존**시키는 상태가 된다.

→ **선결 조건**: 포트를 바꾸기 전에 `.env`에 `DB_URL=jdbc:postgresql://127.0.0.1:5432/running_ai`를
추가하고 재기동한다. 단독으로 안전하고 롤백은 그 줄 삭제뿐이다. (승인 항목 A)

### 2.3 변경안 (작성만, 미적용)

```diff
   ports:
-      - "${POSTGRES_PORT:-5432}:5432"
+      - "127.0.0.1:${POSTGRES_PORT:-5432}:5432"
```

주석 포함 전문과 실행 순서는 런북 §1.3~1.5.

### 2.4 영향 분석

현재 5432에 맺힌 established 커넥션은 **전부 `127.0.0.1` 경유 하나뿐**이다. LAN·tailnet에서 실제로
쓰는 클라이언트는 없다. 운영 Spring(A 이행 후), 개발 인스턴스, `docker exec psql` 모두 영향 없음.
Watchdog·Relay·Watchface·Funnel은 DB를 쓰지 않는다.

**주의**: `docker compose up -d`는 포트 변경 시 컨테이너를 **재생성**한다. 데이터는 named volume에
있어 보존되지만 수십 초의 단절이 생긴다.

### 2.5 백업·복구·롤백 — 말이 아니라 실행해서 검증했다

| 단계 | 결과 |
|---|---|
| `pg_dump -Fc` 운영 백업 | 2,837,204 바이트, 운영 무중단 |
| 사본 DB로 `pg_restore` | 오류 0건 |
| 12개 테이블 행 수 대조 | **전 항목 일치** (활동 8 / 샘플 21,828 / 랩 123 / 드래프트 19 …) |

포트 변경의 롤백은 **compose 한 줄 되돌리고 `up -d`** — 스키마도 데이터도 바뀌지 않으므로 덤프조차
필요 없다.

### 2.6 범위에서 분리한 것

**DB 역할·권한 변경은 하지 않았다.** `running_ai`가 superuser인 점은 별개 문제이고 최소권한 역할로
바꾸려면 애플리케이션 권한 검증이 따로 필요하다. 이번 변경은 네트워크 노출면만 줄인다.

### 2.7 STOP

지시대로 **포트 변경은 실행 직전에 멈췄다.** docker-compose.yml은 무변경이다.

---

## 3. STEP 3 — V21~V26 Release Candidate

### 3.1 운영 복제본에서 전체 시퀀스 리허설

`pg_dump`로 뜬 V20 운영 사본(`running_ai_restoretest`)에 **실제 배포 순서 그대로** 적용했다.

| 단계 | 결과 | 소요 |
|---|---|---|
| Flyway V21~V26 | **6/6 성공, 순서대로** (21→22→23→24→25→26) | **135 ms** |
| V20 데이터 보존 | 11개 테이블 **운영과 행 수 동일** | — |
| 분석 재계산 dry run | `examined 8 / pending 8`, 전건 `ENGINE_REVISION_CHANGED`, DB 변경 0 | — |
| 분석 재계산 실행 | **8 재계산 / 0 실패**, 7건이 다이내믹스 획득 | ~10 s |
| 재계산 재실행 | `reused 8 / reanalysed 0` | — |
| Intervals Import | **25 적재 / 46,880 샘플 / 0 실패** | 2 m 36 s |
| Strava Import | **15 적재 / 33 보강 / 116 랩 / 18,541 샘플 / 0 실패** | 1 m 31 s |
| 전체 시퀀스 재실행 | **8개 테이블 Δ=0** | — |

최종 48 활동 · 75,206 샘플 · 239 랩 · 2026-06-05~10-10 — **독립적으로 만든 `running_ai_realcopy`와
행 수까지 완전히 일치**했다. 절차가 재현 가능하다는 뜻이다.

결정성 교차 확인: 활동 7(서울레이스 하프)의 `speed_hr_decoupling_percent`가 **6.2392 %** 로
기존 실측값과 소수점까지 동일.

### 3.2 배포 가능 여부 — **가능**

근거: V21~V26이 전부 가산적(컬럼 삭제·타입 변경·데이터 이동 없음), 운영 복제본에서 전 과정
무오류, 전체 멱등, 테스트 1,385건 PASS, 백업·복원 검증 완료.

**단, 조건 두 가지**:
1. 백업 없이 시작하지 않는다. V21~V26에는 `down` 스크립트가 없어 **롤백 수단은 덤프 복원뿐**이다.
2. 적용 후 **분석 재계산이 필수**다. V24가 기존 8건을 리비전 0으로 표시하므로, 돌리지 않으면
   7건이 러닝 다이내믹스를 얻지 못한 채 남는다.

### 3.3 롤백

| 상황 | 절차 |
|---|---|
| 기동/Flyway 실패 | 이전 jar로 되돌리고 **덤프 복원** |
| 적용 후 동작 이상 | 덤프 복원이 유일하게 확실한 경로(스키마가 코드보다 앞서면 `ddl-auto: validate`가 실패) |
| 재계산 결과 불만족 | 재계산은 교체이며 원본 샘플·랩·존을 건드리지 않으므로 언제든 다시 계산 가능 |

---

## 4. STEP 4 — ChatGPT 실데이터 전환

### 4.1 합성 환경 유지 — 유지됨

18081 → `running_ai_7d_smoke` 가동 중. 세션 종료로 다시 죽지 않도록 작업 스케줄러 소유로
기동돼 있다. **ChatGPT 연결 복구는 로그로 확인됐다**: 18081 로그에
`Implementation[name=openai-mcp]` initialize(17:55:56)와 `get_recent_activities: ok`(17:55:57).

### 4.2 실데이터 경로 — 별도 환경으로 준비됨

18082 → `running_ai_realcopy`, **`127.0.0.1` 전용**, 터널에 올리지 않음. 전환은 **환경변수 1개**:

```
RUNNING_AI_GATEWAY_TARGET_URL:  …:18081/mcp  ->  …:18082/mcp   + 게이트웨이 재기동
```

ChatGPT 재등록 불필요. 롤백은 되돌려 재기동(약 10초). 전환 중에도 18081을 내리지 않는다.

### 4.3 읽기 전용 보장 (실측)

| 층 | 보장 |
|---|---|
| Spring 18082 | MCP 도구 **7종 전부 읽기**, `publish_workout` **미노출**. 게시·스케줄러 스위치 전부 false |
| Gateway | allowlist가 읽기 7종. `publish_workout`이 들어가면 **기동 자체가 실패**(`server.js:65`) |
| OAuth | scope `runningai:read`, GitHub 계정 1개(숫자 id), 무토큰·위조 토큰 401 |
| 네트워크 | 터널은 17910만 노출, 18082는 루프백 전용 |

### 4.4 선결 조건

**RISK-H3를 먼저 해결한다.** 실데이터가 공개 HTTPS 경로에 올라가는 순간 같은 호스트의 5432가
LAN·tailnet에 열려 있다는 사실의 의미가 달라진다. 순서는 **RISK-H3 → 실데이터 전환**이다.

### 4.5 쓰기는 분리

쓰기·훈련 생성·승인·게시는 전부 이후 Phase(승인 항목 G). 본 전환은 읽기만 연다.

---

## 5. 승인 항목

| # | 항목 | 운영 영향 | 선행 |
|---|---|---|---|
| **A** | `.env`에 `DB_URL=…127.0.0.1…` 추가 + 재기동 | 재기동 1회 | — |
| **B** | compose 5432 루프백 제한 (RISK-H3) | DB 재생성, 수십 초 단절 | A |
| **C** | V21~V26 적용 + 분석 재계산 | 재기동 + 8건 재계산 | 백업 |
| **D** | Intervals 전용 Import (25건) | 데이터 추가 | C |
| **E** | Strava 아카이브 Import (15건 + 33건 보강) | 데이터 추가 | D |
| **F** | ChatGPT 실데이터 읽기 전환 | 게이트웨이 env 1개 | B |
| **G** | ChatGPT 쓰기·게시 확장 | — | 이후 Phase |

**A~G 중 어느 것도 실행하지 않았다.**

---

## 6. 잔여 위험

| # | 위험 | 심각도 | 현황 |
|---|---|---|---|
| 1 | RISK-H3 (5432 LAN·tailnet 노출, 평문 superuser) | **높음** | 변경안·영향분석·롤백 준비 완료, 승인 대기 |
| 2 | `running_ai`가 superuser | 중간 | **의도적으로 범위 분리**. 최소권한 역할은 별건 |
| 3 | V21~V26에 `down` 스크립트 없음 | 중간 | 롤백은 덤프 복원뿐. 백업이 사실상 유일한 안전망 |
| 4 | `ActivityType` 미확장으로 46건 미적재 | 중간 | 사용자 결정 대기 |
| 5 | 인터벌 그룹 복원 불가(Strava 랩에 강도 없음) | 중간 | 데이터의 한계. 사유 기록됨 |
| 6 | Quick Tunnel 주소 변동 | 낮음 | 재기동 시 Worker `ORIGIN_MCP_URL` 갱신 1회 |
| 7 | Import 엔드포인트가 API 키 미설정 시 500 반환 | 낮음 | `IntervalsException`이 Import 컨트롤러에 매핑되지 않음. 기능 영향 없음, 다음 정리 때 |

---

## 7. 이번 단계에서 건드리지 않은 것

운영 DB `running_ai`(V20, 활동 8, 샘플 21,828 — 재확인함), 운영 Spring 18080,
`docker-compose.yml`, Relay, Funnel, Watchface, Watchdog, canonical `main`, `origin`.

작업 중 만든 복제본 `running_ai_restoretest`는 리허설 종료 후 **삭제**했다 — 실데이터 사본을
필요 이상으로 남기지 않기 위해서다. 백업 덤프 2개는 `.runtime/backup/`(gitignored)에 있다.
