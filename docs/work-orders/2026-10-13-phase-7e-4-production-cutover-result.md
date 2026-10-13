# Phase 7E-4 — RunningAI 운영 전환 실행 결과

실행일 2026-10-13 · 브랜치 `main-3` · 운영 PC(LAPTOP-MMDQUCIC)
**A~F 전부 실행 완료.** G(쓰기·게시 확장)는 승인 범위 밖이므로 손대지 않았다.

---

## 0. 요약

| # | 항목 | 결과 |
|---|---|---|
| A | `.env`에 `DB_URL=…127.0.0.1…` | 완료 |
| B | docker-compose 5432 루프백 제한 (RISK-H3) | **해결 — 외부 도달 전부 차단** |
| C | 운영 DB V21~V26 + 분석 재계산 | **26/26 성공 (153 ms), 8건 재계산** |
| D | Intervals 전용 Import | **25건 / 46,880 샘플 / 실패 0** |
| E | Strava 아카이브 Import | **15건 신규 + 33건 보강 / 실패 0** |
| F | ChatGPT 실데이터 읽기 전환 | 게이트웨이 → 운영 Spring 18080 |

최종 상태: **활동 48건 · 샘플 75,206개 · 랩 239개 · Flyway V26**, 리허설 수치와 **전 항목 일치**.

---

## 1. 전환 전 확인

- `main-3` Clean, canonical `main`(`370ee8f`) 기준 **23 커밋 앞섬**(7D/7E 작업분 8 커밋 포함).
- 운영 스택 전부 정상: Spring 18080, Gateway 17910, 외부 릴레이 17845, Garmin 커넥터 8765,
  Funnel, Watchdog(UP), Watchface(ConnectIQ 시뮬레이터).
- 전환 중 Watchdog이 Spring을 임의 재기동하지 못하도록 **작업을 일시 비활성화**했고, 종료 후 복구했다.

### 백업 (STOP 게이트)

```
.runtime/backup/running_ai-prod-7e4-20261013-184453.dump   2,837,204 바이트
SHA-256 742E23FD9A328BD4F7B04F06D78D6E3C14D0A92909B712E03994E09851BD7FB9
```

컨테이너 내부에서 `pg_dump -Fc` 후 `docker cp`로 반출했다(PowerShell 리다이렉션이 바이너리를
훼손할 여지를 없애기 위해서다). 복원 가능성은 **추정하지 않고 실제로 복원해 확인**했다:
throwaway DB `running_ai_7e4_restorecheck`로 `pg_restore` 후 12개 테이블 행 수 대조 → **전 항목 일치**.

| 기준값(전환 전) | |
|---|---|
| Flyway | 20행 (V20) |
| 활동 | 8 |
| 샘플 | 21,828 |
| 랩 | 123 |
| 드래프트 | 19 |
| 회복 일자 | 21 |

---

## 2. RISK-H3 — PostgreSQL 외부 노출

### 선결 조건(A)

운영 `.env`에 `DB_URL=jdbc:postgresql://127.0.0.1:5432/running_ai`를 추가했다. 이 호스트에서
`localhost`는 **`::1`로 먼저 해석**되는데, 포트 바인딩을 127.0.0.1로 좁히면 IPv6 리스너가 사라져
매 커넥션이 실패 시도를 한 번씩 선행하게 된다. 재기동 후 health UP·활동 8건으로 확인했다.

### 변경(B)

`docker-compose.yml`의 `ports`를 `"127.0.0.1:${POSTGRES_PORT:-5432}:5432"`로 바꾸고
`docker compose up -d`로 컨테이너를 재생성했다. 볼륨 `running-ai-github_running_ai_postgres_data`는
그대로 유지되어 데이터가 보존됐다(재생성 직후 활동 8 / 샘플 21,828 / Flyway 20 확인).

### 검증 (실측)

| 지점 | 전환 전 | 전환 후 |
|---|---|---|
| `127.0.0.1` | 도달 | **도달 (정상)** |
| `192.168.0.24` (LAN) | 도달 | **차단** |
| `100.108.54.79` (Tailscale) | 도달 | **차단** |
| `172.22.80.1` (WSL) | 도달 | **차단** |

- 호스트 리스너: `127.0.0.1:5432` **단 하나**. `0.0.0.0`·`::`·`::1` 리스너 **없음**.
- `docker ps` → `127.0.0.1:5432->5432/tcp`.
- 대조군: 위 3개 IP 전부 **ICMP는 응답**한다. 즉 "주소가 죽어서"가 아니라 **포트가 닫혀서** 실패한다.

범위에서 제외한 것은 런북대로다: DB 역할·권한 최소화, 비밀번호 교체는 별건이다.

---

## 3. V21~V26 적용 (C)

운영 Spring이 쓰는 jar은 canonical 체크아웃(`main` @ `370ee8f`)의 빌드 산출물이라 V21~V26을 담고
있지 않다. canonical main 병합이 이번 승인 범위 밖이므로, **`main-3`에서 빌드한 boot jar을 운영
jar 경로에 배치**했다(이전 jar은 `.runtime/backup/running-ai-server-PRE-7E4-370ee8f.jar`로 보존).
SHA-256 동일함을 확인했고, jar 안에 V21~V26 6개 마이그레이션이 들어 있음을 직접 확인했다.

```
26/26 success,  V21~V26 합계 153 ms
V21 injury_report 59ms · V22 running dynamics 56ms · V23 profile_history 7ms
V24 engine_revision 11ms · V25 intervals_activity_import 11ms · V26 strava_activity_import 9ms
```

**기존 데이터 전량 보존**: 11개 테이블 행 수가 전환 전과 동일(활동 8 / 샘플 21,828 / 랩 123 /
존 75 / 상세 8 / 수집 48 / raw 10 / 드래프트 19 / 회복 21 / athlete 1 / 분석 8).
신규 테이블 4개는 모두 0행으로 생성.

### 분석 재계산

`POST /api/v1/activities/analysis-refresh?dryRun=true` → **pending 8**, 전부 `ENGINE_REVISION_CHANGED`
(V24가 기존 행을 리비전 0으로 표시하므로 설계대로다). 실행 → **재계산 8 / 실패 0**.
재실행 → **reused 8 / reanalysed 0** (멱등).

**7건이 러닝 다이내믹스를 획득**했고, 활동 2번(INDOOR_CYCLING)은 설계상 대상이 아니라 null이다.
8건 전부 `RUNNING_ANALYSIS_V1` / revision 2 / digest 보유, digest **8개 전부 서로 다름**.

---

## 4. 과거 데이터 Import (D, E)

승인된 순서를 지켰다 — **Intervals 먼저, Strava 나중**(Strava가 `apple_health_uuid`로 Intervals
적재분을 찾아 보강하므로 순서를 바꾸면 보강 대상이 사라진다).

### D. Intervals 전용 (2m28s)

```
candidatesSeen 71 / processed 71
imported 25 · samplesWritten 46,880 · failed 0
alreadyLinked 8 (기존 Garmin) · skippedGarminSourced 2 · skippedUnsupportedType 2
skippedNoSourceData 34 (Strava 스텁) · skippedAmbiguous 0
```

### E. Strava 아카이브 (1m29s)

```
filesInArchive 94 / processed 94 / rowsUnparsable 0 / filesMissing 0
imported 15 · enrichedExisting 33 · lapsWritten 116 · samplesWritten 6,498
samplesEnriched 18,541 · skippedUnsupportedType 46 · skippedAmbiguous 0 · failed 0
```

### 리허설 대비

| 항목 | 리허설 | 운영 | |
|---|---|---|---|
| Intervals imported | 25 | **25** | 일치 |
| Intervals 샘플 | 46,880 | **46,880** | 일치 |
| Strava imported | 15 | **15** | 일치 |
| Strava enriched | 33 | **33** | 일치 |
| Strava 랩 | 116 | **116** | 일치 |
| Strava 신규 샘플 | 6,498 | **6,498** | 일치 |
| Strava 보강 샘플 | 18,541 | **18,541** | 일치 |
| 최종 활동 | 48 | **48** | 일치 |
| 최종 샘플 | 75,206 | **75,206** | 일치 |
| 최종 랩 | 239 | **239** | 일치 |

**차이 0건.** 수치를 맞추려고 데이터를 손댄 곳은 없다.

### 출처별 최종 분포

| 출처 | 활동 | 기간 | 샘플 | HR | 파워 | 랩 |
|---|---|---|---|---|---|---|
| GARMIN | 8 | 2026-09-18~10-10 | 21,828 | 100 % | 96.4 % | 123 |
| INTERVALS_ICU | 25 | 2026-08-02~09-12 | 46,880 | 100 % | 78.7 % | 84 |
| STRAVA | 15 | 2026-06-05~08-15 | 6,498 | 0 % | 0 % | 32 |

### 중복·덮어쓰기 검증

- 동일 외부 식별자 중복: **0건**.
- 120초 이내 시작 쌍: 1건 발견 → **중복이 아님**. 2026-09-02 Intervals 두 건으로,
  `i183614316`(63 s / 128 m, 잘못 눌린 시작)과 85초 뒤 시작한 실제 러닝
  `i182664832`(2,650 s / 7,023 m)다. Intervals.icu 원본에 실제로 둘 다 존재한다.
- **Garmin 8건은 비트 단위로 불변**: 샘플 21,828(HR 100 %)·랩 123·존 75 모두 전환 전과 동일하고,
  Garmin 샘플 중 `STRAVA_FIT` 출처 표식이 찍힌 것은 **0건**이다. Garmin 랩도 손대지 않았다.
- Strava 보강은 **NULL만 채웠다**: `power_source` 표식이 정확히 **18,541개** = `samplesEnriched`.
  HR은 한 건도 채우지 않았는데, Intervals가 이미 100 % 보유하고 8월 FIT에는 HR이 없기 때문이다.
- 랩 116개 = Intervals 적재분 84 + Strava 신규 32. Intervals 활동은 `icu_lap_count`=0이라 랩이
  없었고 Strava FIT이 공급했다. **Garmin 랩에는 쓰지 않았다.**
- 케이던스는 설계대로 **변환하지 않았다**(Intervals/FIT은 한쪽 다리 rpm, 컬럼은 Garmin spm).

### 멱등성

두 Importer를 **다시 실행**해 17개 테이블 행 수를 비교했다.

```
imported 0 / alreadyImported 25 · 15 / alreadyEnriched 33 / failed 0
17개 테이블 전부 Δ = 0
```

---

## 5. 분석·훈련 컨텍스트

전체 48건 대상 `analysis-refresh?dryRun=true` → **examined 48 / reused 48 / pending 0 / failed 0**.
계약 `RUNNING_ANALYSIS_V1` 48건, 엔진 리비전 2 48건, digest 48건이고 **digest 48개가 전부 서로 다르다**
(충돌 없음).

`analysis_status`: GARMIN 8 COMPLETE · INTERVALS_ICU 25 COMPLETE · **STRAVA 15 PARTIAL**.
PARTIAL은 설계대로 **데이터**를 설명하는 값이지 세션 평가가 아니다 — 해당 15건은 Apple Watch
실내 기록을 Strava가 재인코딩한 것이라 HR·파워·GPS가 애초에 없다.

`get_training_context`(V2)는 정상 응답(14,224 바이트, SHA-256 포함)하며 size guard 내에 있다.

---

## 6. ChatGPT 실데이터 읽기 전환 (F)

### 대상 DB 확인

`scripts/dev/start-dev-spring.ps1`은 **DB명 `running_ai`(운영)를 구조적으로 거부**하므로 18082를
운영 DB로 돌릴 수 없고, 18082가 보던 `running_ai_realcopy`는 V23 / 활동 8건짜리 **옛 스냅샷**이라
실데이터 조회 목적을 만족하지 못한다. 따라서 게이트웨이 대상은 **운영 Spring 18080**으로 했다.
조회값은 스냅샷이 아니라 운영 DB 그 자체다.

### 읽기 전용 보장 (실측)

- 운영 18080의 `tools/list` → **정확히 7종, 전부 `readOnly=true`**:
  `get_recent_activities` `get_activity_analysis` `get_training_context` `get_recovery_context`
  `get_fitness_trend` `get_workout_recommendation` `get_athlete_profile`.
- `publish_workout` 호출 → `Unknown tool: invalid_tool_name`. `RUNNINGAI_MCP_ENABLED=false`이므로
  **등록 자체가 되지 않는다**(차단이 아니라 부재).
- 게이트웨이는 allowlist에 `publish_workout`이 있으면 **기동 자체가 실패**한다(server.js:65).
- 게시 스위치 4종·Importer 2종 전부 false, Garmin 스케줄러 off.
- 실데이터 확인: `get_athlete_profile` → **LTHR 176 / 290**(합성 환경은 165/310).
- 90일 창 `get_recent_activities` → **37건**(전환 전 8건). 적재한 과거 기록이 실제로 보인다.

### 인증 확인

- 게이트웨이 무토큰 → **401** + RFC 6750 challenge.
- 위조 토큰(`alg:none`) → **401**.
- Worker 경유 무토큰 → **401**.
- 터널 직접 → **401**(게이트웨이까지 살아 있음).
- scope `runningai:read`, 허용 subject **GitHub 계정 1개**.
- 신규 게이트웨이의 protected-resource 메타데이터가 기존 게이트웨이와 **바이트 단위로 동일** →
  issuer/audience/public base/scope 재구성이 정확함을 확인했다.

### 전환 절차

위험을 줄이려고 **먼저 예비 포트 17911에 동일 설정으로 띄워 검증**한 뒤 17910을 교체했다.
또한 기존 게이트웨이는 사용자 셸의 자식이라 세션 종료 시 함께 죽는 구조였으므로,
`RunningAI-McpGateway` **작업 스케줄러 작업으로 등록**해 OS가 소유하게 했다
(런처 `.runtime/mcp-gateway.cmd`, 로그 `.runtime/logs/mcp-gateway.log`).

**합성 18081은 계속 띄워 뒀다.** 롤백은 런처의 TARGET_URL을 `…18081/mcp`로 되돌리고
작업을 재시작하는 것 하나뿐이고 약 10초다.

### 사용자가 직접 해야 하는 마지막 확인

ChatGPT 앱에서의 최종 조회는 **사용자 OAuth 로그인이 필요**하므로 이 세션에서 대신할 수 없다.
ChatGPT에서 RunningAI 커넥터로 아래를 물어보면 전환 성공 여부가 즉시 드러난다.

- "내 LTHR 알려줘" → **176 / 290**이면 실데이터(165/310이면 합성, 롤백된 상태).
- "최근 90일 활동 보여줘" → **37건**이 나오면 과거 기록 통합까지 성공.

---

## 7. 기존 서비스 회귀

| 구성요소 | 상태 |
|---|---|
| Spring 18080 | UP, health UP |
| PostgreSQL | HEALTHY, 루프백 전용 |
| Garmin 커넥터 8765 | UP |
| 외부 릴레이 17845 | UP, `/health` → `{"status":"ok"}` |
| Funnel | `https://laptop-mmdqucic.tail4597a5.ts.net` → 릴레이, **HTTP 200** |
| Watchface | 릴레이 `/today-workout` → 401(릴레이 자체 인증, 정상 동작) |
| Gateway 17910 | UP, 읽기 7종, 루프백 |
| Watchdog | **재활성화**, overall UP, 5개 구성요소 전부 UP, lastAction NONE, 재기동 예산 0 소비 |
| 개발 18081/18082 | 계속 가동(롤백용) |

모든 포트가 `127.0.0.1` 바인딩이다.

---

## 8. 남은 문제 / 다음 Phase

1. **운영 jar과 canonical 소스가 어긋나 있다(중요).** 운영은 `main-3` 빌드를 쓰는데 canonical
   체크아웃은 `main` @ `370ee8f`다. 이 상태에서 누군가 `start-running-ai.ps1 -Build`를 실행하면
   **V20 코드가 다시 빌드되어 V26 스키마와 만나고 Hibernate validate가 실패한다.**
   → 다음 작업은 `main-3`를 canonical main에 병합(fast-forward 가능)하고 재빌드하는 것이다.
   병합 시 canonical 체크아웃의 `docker-compose.yml`은 작업 트리에 동일 내용 수정본이 있으므로
   `git checkout -- docker-compose.yml`로 되돌린 뒤 fast-forward하면 된다(내용이 같아 손실 없음).
2. **canonical main 병합·origin push는 이번 승인 범위 밖**이라 하지 않았다.
3. Strava 유래 15건은 HR·파워·GPS가 없어 분석이 PARTIAL이다. 아카이브 자체의 한계이며
   보강 수단이 없다. 인터벌 그룹도 Strava 랩에서는 도출 불가(설계대로)다.
4. `athlete_intensity_profile_history`가 비어 있어 LTHR 176/290이 `sourceTracked:false`로 보고된다.
   V23은 소급 입력을 하지 않으므로 사용자가 값을 한 번 재확인해야 해소된다.
5. Intervals API 키 누락 시 **500**이 나오는 알려진 버그(`IntervalsException` 미매핑)는 그대로다.
6. 승인 항목 **G(쓰기·게시 확장)**는 손대지 않았다.
7. 백업 복원 검증용 DB `running_ai_7e4_restorecheck`를 남겨 뒀다(V20 스냅샷, 추가 롤백 경로).

---

## 9. 롤백 수단

| 대상 | 절차 |
|---|---|
| ChatGPT 대상 | 런처 TARGET_URL → `…18081/mcp`, 작업 재시작 (~10초) |
| 포트 바인딩 | compose `ports` 한 줄 되돌리고 `docker compose up -d` |
| 코드 | `.runtime/backup/running-ai-server-PRE-7E4-370ee8f.jar` 복귀 (단, 스키마가 앞서므로 DB 복원 동반 필요) |
| DB | `.runtime/backup/running_ai-prod-7e4-20261013-184453.dump` (**복원 가능성 실측 완료**) |

V21~V26에는 down 스크립트가 없다. DB 롤백 수단은 위 덤프 하나뿐이다.
