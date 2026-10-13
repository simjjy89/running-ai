# Phase 7D-GPT-LIVE-3 — 실제 Garmin 데이터 연결 준비 (STEP 1~4 완료, STEP 5 승인 대기 STOP)

기준: `main-3` HEAD `34cf121`, 미커밋 산출물 보존. **커밋·푸시 0건, 운영 변경 0건,
공개 HTTPS 경로 변경 0건, 외부 Garmin/Intervals 호출 0건.**
선행 보고: `2026-10-13-phase-7d-gpt-live-2-step3-auth-verification-result.md`.

지시 원문: `2026-10-13-phase-7d-gpt-live-3-instruction.md`.

---

## STEP 1 — OAuth E2E 결과 보존 (PASS)

사용자의 ChatGPT 실호출은 **게이트웨이·Spring 로그에 그대로 남아 있었다.** 아래는 보고받은 내용의
재기술이 아니라 로그 실측이다 (게이트웨이 UTC, Spring KST, 동일 사건).

### Worker → Tunnel → Gateway → Spring 전 구간 성공

게이트웨이 로그(`gateway-17910.log`)에 기록된 인가된 호출:

| UTC | 이벤트 |
|---|---|
| 03:45:18 | `initialize authorized` ×2, `tools/list authorized` |
| 03:46:01 | `tools/call get_recent_activities authorized` |
| 03:46:04 | `tools/call get_recent_activities authorized` |
| 03:46:10 | `tools/call get_training_context authorized` |
| 03:48:14 | `tools/call get_recent_activities authorized` |
| 03:50:20~25 | `get_activity_analysis` → `get_training_context` → `get_recovery_context` → `get_fitness_trend` → `get_workout_recommendation` → `get_athlete_profile` 연속 인가 |

Spring 18081 로그의 같은 시각 대응 기록: `Client initialize request - Protocol: 2025-11-25 …
Info: Implementation[name=openai-mcp]` 와 `name=openai-mcp (Codex)`. **ChatGPT 클라이언트가 실제로
도달했다는 1차 증거이며, 체인 전체(Worker 인증 → Quick Tunnel → 게이트웨이 홉 토큰 검증 → 합성 Spring)가
동작했다는 뜻이다.** 토큰 없이/위조 토큰으로 들어온 앞선 시도는 같은 로그에 전부 `auth rejected`로 남아 있다.

### 도구 7종 — 7/7 호출 성공

`ReadMcpTools` 로그 기준으로 7종 전부 `ok`가 기록됐다: `get_recent_activities`,
`get_activity_analysis`, `get_training_context`, `get_recovery_context`, `get_fitness_trend`,
`get_workout_recommendation`, `get_athlete_profile`.

두 건의 `refused: code=INVALID_ARGUMENT`(12:46:01 `get_recent_activities`, 12:05:02
`get_fitness_trend`)도 같은 로그에 있다. **도구 결함이 아니라 호출자가 스키마에 없는 인자를 보낸 것을
거부한 정상 동작이며**(`get_fitness_trend`는 `oldestDate`/`newestDate`가 필수), 두 경우 모두 올바른
인자로 재호출했을 때 `ok`가 됐다. 알 수 없는 인자 키를 조용히 기본값으로 처리하지 않는 설계가 의도대로
작동한 증거다.

`server/discover`는 게이트웨이가 3회 `method refused` 했다 — allowlist에 없는 메서드이고, ChatGPT는
이를 받고 정상적으로 `initialize`로 넘어갔다.

### 민감정보 노출 — 없음

게이트웨이·Spring·cloudflared 로그 전체를 `Authorization`, `Bearer <값>`, `eyJ`(JWT), `client_secret`,
`GITHUB_CLIENT*`, `HOP_SIGNING*`, JWK 개인키 필드 `"d":`, `BEGIN PRIVATE KEY`, `INTERVALS_API_KEY`,
`POSTGRES_PASSWORD`, `password=`, `access_token`, `refresh_token`, `gho_`/`ghp_` 패턴으로 스캔했다.

유일한 히트는 `gateway-17910.log`의 `Bearer` 4건인데, 전부 **`auth rejected: no bearer token`** 문자열
자체다 — 토큰 값이 아니다. **토큰·클라이언트 시크릿·개인키·DB 비밀번호가 로그에 남은 사례는 0건.**
Spring 로그는 도구 이름과 성공/실패 코드만 남기고 인자·응답 본문을 남기지 않는다.

오프라인 회귀도 현재 소스로 재실행해 확인했다(아래 STEP 4).

### 미커밋 작업물 — 전부 보존

세션 시작 시점과 종료 시점의 `git status`가 동일하다: `.gitignore`, `tools/mcp-gateway/README.md`
수정분과 work-order 9건 + `tools/mcp-worker/` 미추적분이 그대로다. **`server/` 이하 변경 0건**
(코드를 한 줄도 건드리지 않았다). 이번 단계에서 추가된 파일은 work-order 2건과
`.runtime/backup/`(gitignore 대상)의 운영 DB 덤프뿐이다.

---

## STEP 2 — 실제 데이터 사본 준비 (완료)

### 운영 DB는 읽기 전용으로만 접근

`pg_dump -Fc`(읽기 전용)로 운영 `running_ai`를 덤프했다. **운영 DB에 쓰기·스키마 변경 0건.**

| 항목 | 값 |
|---|---|
| 덤프 파일 | `.runtime/backup/running_ai-prod-20261013-125626.dump` (gitignore 대상) |
| 크기 | 2,837,204 bytes |
| SHA-256 | `2BF8986928073085A9381F951F2642CBD837BCACDCD9D20A36B8C854E4A13AF0` |

### 사본 DB 생성 및 동일성 검증

`CREATE DATABASE running_ai_realcopy` → `pg_restore`. 복원 직후 **15개 테이블의 행 수가 운영과 완전히
일치**함을 확인했다:

```
activity=8 activity_analysis=8 activity_detail=8 activity_intervals_metrics=8
activity_lap=123 activity_raw=10 activity_raw_payload=40 activity_sample=21828
activity_source_link=8 activity_zone=75 athlete=1 athlete_intensity_profile=1
flyway=20 garmin_recovery_daily=21 intervals_fitness_daily=99 workout_draft=19
```

운영·사본 두 줄의 출력이 문자 단위로 동일했다.

### 사본에만 Flyway V21~V23 적용

개발 Spring 기동 시 Flyway가 사본에만 적용했다:

```
Migrating schema "public" to version "21 - create injury report"
Migrating schema "public" to version "22 - add running dynamics to activity analysis"
Migrating schema "public" to version "23 - create athlete intensity profile history"
Successfully applied 3 migrations to schema "public", now at version v23
```

**운영 DB는 V20 그대로다** — 적용 후 재확인: `flyway_schema_history` 20행, 최신 version `20`,
`injury_report` 테이블 **존재하지 않음**(information_schema 조회 0건), 운영 Spring 18080 `UP`.

### 개발 Spring — 별도 포트, 모든 쓰기 OFF

| 항목 | 값 |
|---|---|
| 포트 | **127.0.0.1:18082** (PID 9116), `SERVER_ADDRESS=127.0.0.1` |
| DB | `running_ai_realcopy` (V23) |
| jar | `running-ai-server-0.0.1-SNAPSHOT.jar`, SHA-256 `ACEDE7D6…E562FA` = **7D-1이 기록한 RC jar와 일치** |
| 등록 도구 | `Registered tools: 7` — 읽기 7종만, `publish_workout` 미등록 |

꺼진 스위치: `RUNNINGAI_MCP_ENABLED=false`, `WORKOUT_PUBLISHING_ENABLED=false`,
`WORKOUT_PUBLISHING_SCHEDULER_ENABLED=false`, `RUNNING_AI_DRAFT_PUBLISHING_ENABLED=false`,
`GARMIN_SYNC_SCHEDULER_ENABLED=false`, `GARMIN_RECOVERY_SCHEDULER_ENABLED=false`.

외부 시스템 차단: `GARMIN_CONNECTOR_URL=http://127.0.0.1:9`(죽은 포트), `INTERVALS_API_KEY` 빈 값,
`INTERVALS_ATHLETE_ID=0`. **실제 Garmin·Intervals에 도달할 경로가 구성상 없다.** DB 비밀번호는 기동 시
컨테이너에서 읽어오므로 스크립트에 남지 않는다.

### 무릎 상태 — ACTIVE로 취급, 시각 조작 없음

운영 DB에는 `injury_report`가 **아예 없다**(V21 미적용). 사본에 V21을 적용한 직후에도 표는 비어 있었고,
그 상태에서 `get_workout_recommendation`은 이미 차단됐다:

> `nonRestTrainingAllowed:false`, `reasonCode:"NO_INJURY_STATE_RECORDED"`

그 다음, 마지막 실제 사용자 보고를 **사본에만** 1건 기록했다. 출처는
`docs/work-orders/2026-10-12-phase-7a-1-…-instruction.md`에 보존된 사용자 보고
("레이스 중 무릎 바깥쪽 통증", "레이스 직후 보행 시에도 통증")이고, 시각은 그 보고의 원래 시각
**2026-10-12 12:23:12 KST를 그대로** 썼다 — `reported_at`과 `recorded_at` 둘 다. **현재 시각으로
최신화하지 않았고, RESOLVED로 바꾸지 않았다.**

보고되지 않은 항목은 추론하지 않고 NULL로 두었다: `pain_intensity`, `pain_on_stairs`, `swelling`,
`range_of_motion_limited`, 그리고 좌/우(`side='UNSPECIFIED'`). `notes`에 전사 출처와 "시각을 의도적으로
갱신하지 않았음"을 명시했다.

기록 후 재검증 결과 — **여전히 차단**:

> `nonRestTrainingAllowed:false`, `reasonCode:"INJURY_ACTIVE"`,
> "An ACTIVE injury/pain report is on file (KNEE_LATERAL, UNSPECIFIED). Running, interval and long
> training are blocked until the athlete explicitly reports status=RESOLVED."

즉 **기록이 없을 때도(NO_INJURY_STATE_RECORDED), ACTIVE일 때도(INJURY_ACTIVE) 훈련은 허용되지 않는다.**
참고로 48h 신선도 창 기준 이 보고는 약 24.7시간 전이라 아직 "신선"하며, 48시간이 지나면 같은 행이
`INJURY_STATE_STALE`로 바뀌어 **역시 차단**된다. 세 경로 모두 차단이다.

---

## STEP 3 — 실제 데이터 로컬 검증 (2026-10-11 서울레이스 하프)

**모든 수치는 사본 DB에 저장된 실제 값이다.** V22 러닝 다이내믹스 컬럼은 기존 행에서 NULL이므로
`POST /api/v1/activities/7/analysis`로 **사본에서만** 재분석했다 — 저장된 랩·샘플만 읽는
DB 전용 연산이고 Garmin·Intervals 호출은 0건이다.

### 활동 식별 및 총계 — 지시한 값과 일치

| 항목 | 저장된 실제 값 | 지시값 |
|---|---|---|
| 날짜/종류 | 2026-10-11 07:39:40 KST, `RUN` | — |
| 거리 | `activity` 21,240.0 m / 분석 21,238.1 m = **21.24 km** | 21.24 km ✅ |
| 시간 | 분석 `analysisDurationSeconds` **6,724 s = 1:52:04** (`activity.duration_seconds` 6,723 s = 1:52:03) | 1:52:04 ✅ |
| 평균/최대 심박 | 177 / 191 bpm | — |
| 분석 상태 | `RUNNING_ANALYSIS_V1`, `COMPLETE`, 입력 완전성 **FULL**, 유효 샘플 6,724 | — |

> 1초 차이는 조작이 아니다: `activity`는 Garmin 요약의 duration, 분석은 샘플 스트림의 실제 경과 구간이다.
> 사용자가 기억하는 1:52:04와 일치하는 쪽은 분석값이다.

### 보폭 · 접지시간 · 러닝파워 (22개 랩 전부에서 측정됨, `lapsWithDynamicsCount=22`)

| 지표 | 전체 | 전반 | 후반 | 변화 |
|---|---|---|---|---|
| 보폭 | 107.6 cm | 110.0 cm | 105.2 cm | **−4.33 %** |
| 접지시간 | 246.3 ms | 242.3 ms | 250.2 ms | **+3.27 %** |
| 러닝파워 | 321.0 W (최대 653 W) | 329.8 W | 312.1 W | **−5.37 %** |
| 상하진폭 | 8.72 cm | 8.718 cm | 8.721 cm | +0.03 % (사실상 불변) |
| 수직비 | 8.13 % | 7.95 % | 8.31 % | +4.58 % |

**같은 페이스 비교**(15 s/km 단위로 같은 페이스이면서 3랩 이상 떨어진 랩쌍): 보폭 **−2.23 cm**,
러닝파워 **−9 W**. 같은 속도를 내는 데 보폭이 줄고 파워가 떨어진 것으로, 페이스 변화로 설명되지 않는
후반 변화가 있었다는 뜻이다.

> 러닝다이내믹스 "halves"는 **랩 순서** 기준 분할이고, 아래 심박·속도 halves는 **경과시간 중점** 기준이다.
> 서로 다른 분할이며 같은 값으로 제시하지 않는다(V22 주석의 명시적 규칙).

### 심박 · 케이던스 · 페이스 변화 (경과시간 중점 분할)

| 지표 | 전반 | 후반 | 변화 |
|---|---|---|---|
| 심박 | 176.54 bpm | 177.10 bpm | **+0.57 bpm (+0.32 %)** |
| 속도 | 3.2525 m/s (**5:07.5/km**) | 3.0593 m/s (**5:26.9/km**) | **−5.94 %** |
| 케이던스 | 88.16 | 86.93 | −1.23 (−1.40 %) |
| Speed-HR 디커플링 | — | — | **+6.239 %** |

랩 단위: 랩 수 22, 속도 평균 3.154 m/s, 속도 **CV 5.05 %**, 심박 평균 177.18 bpm,
**심박 progression +23 bpm**, 케이던스 평균 175.47 spm(양발).

> `speed_hr_decoupling_percent`는 `(EF1−EF2)/EF1×100`(EF = 평균속도/평균심박)인 **RunningAI 자체 파생
> 지표**다. Garmin이나 Intervals.icu의 값이 아니고, 임계값도 없다. 실제값 **6.239 %**이며 — 지시문에 적힌
> 6.677 %는 합성 스모크 데이터의 2026-10-12 세션 값으로, 실제 레이스와 무관하다.

### 후반 페이스 저하 — 랩 실측

| 랩 | 1 | 5 | 10 | 15 | 16 | 20 | 21 | 22 (240 m) |
|---|---|---|---|---|---|---|---|---|
| 페이스 s/km | 291.8 | 307.8 | 319.8 | 331.2 | **345.0** | 338.8 | **306.4** | 355.9 |
| 심박 | 165 | 178 | 178 | 175 | 172 | 177 | **182** | **188** |
| 파워 W | 340 | 330 | 324 | 309 | 298 | 297 | **329** | 321 |
| 보폭 cm | 114.3 | 110.1 | 108.9 | 104.4 | 100.5 | 101.4 | 109.9 | 105.3 |

읽히는 사실: **1랩 4:52/km에서 16랩 5:45/km까지 단조롭게 느려졌고**(최대 −53 s/km), 같은 구간에서
파워 340→298 W, 보폭 114.3→100.5 cm로 함께 떨어졌다. **21랩에서 306.4 s/km·182 bpm·329 W로 되돌아온
스퍼트가 있었고**, 마지막 랩 22는 **240 m짜리 부분 랩**(85.4 s)이라 환산 페이스 355.9 s/km는 1 km 랩과
직접 비교할 수 없다. 심박은 평균 177 bpm으로 **저장된 LTHR 176 bpm을 레이스 내내 상회**했다.

심박 존: Zone 5가 존 합계의 **92.84 %**(6,241 s), Zone 4 6.29 %. LTHR 노출: **≥90 % 6,657 s,
≥95 % 6,636 s, ≥100 % 4,738 s**(6,724 s 중 **70.5 %**). 존 비율 분모는 활동 시간이 아니라 **존 합계**
(6,722.17 s)라는 6H-4 규칙 그대로다.

### 회복 지표 — 데이터 있음, 다만 **구멍이 있다**

`get_recovery_context`(기준일 2026-10-13)가 읽은 최신 행은 **2026-10-12**(1일 전):

| 지표 | 현재 | 28일 기준선 | 차이 |
|---|---|---|---|
| HRV | **30.0 ms** | 42.7 ms | **−12.7 ms (−29.8 %)** |
| 수면 | **3.43 h** | 5.1 h | −1.67 h (−32.7 %) |
| 수면 점수 | **39** | 56.1 | −17.1 (−30.5 %) |
| 안정시 심박 | **58 bpm** | 55.5 | +2.5 (+4.6 %) |
| Body Battery 최고 | **25** | 49.0 | **−24 (−48.9 %)** |
| 스트레스 평균 | 38 | 34.8 | +3.3 (+9.4 %) |

레이스 당일 2026-10-11: HRV 45 ms, 주간평균 44, 상태 `BALANCED`, 수면 **4.37 h**, 수면점수 51,
안정시 심박 53 bpm.

**빠진 날짜를 명시한다**: 28일 창에 21일만 저장돼 있고(HRV 값이 있는 날은 19일), **2026-10-04와
2026-10-07 ~ 2026-10-10이 통째로 없다.** 레이스 직전 나흘의 회복 데이터가 없다는 뜻이다. 기준선은
최소 7일 조건을 넘겨 `AVAILABLE`이지만, 레이스 전 구간 해석에는 이 공백을 반드시 끼고 봐야 한다.
없는 값은 추정하지 않았다.

### 훈련부하 — 있음

Intervals.icu 매칭 성공(`intervalsMatched:true`). 레이스 활동의 값: **training load 172**,
intensity 95.97, 활동 후 CTL 16.39 / ATL 32.42.

일별 피트니스(용어: **CTL = Intervals 계산 피트니스, ATL = 계산 피로도**, 주관적 `fatigue` 필드는
ATL로 쓰지 않음; Form은 API 필드가 없어 `derived_form = ctl − atl`로 RunningAI가 파생):

| 날짜 | CTL | ATL | derived_form | ramp |
|---|---|---|---|---|
| 2026-10-10 (레이스 전날) | 12.64 | 10.98 | +1.66 | 0.16 |
| **2026-10-11 (레이스)** | **16.39** | **32.42** | **−16.03** | **4.20** |
| 2026-10-12 | 16.01 | 28.10 | −12.10 | 4.10 |

하루 만에 ATL이 10.98 → 32.42로 약 3배, derived_form이 +1.66 → −16.03으로 떨어졌다. 저장된 범위는
2026-07-06 ~ 2026-10-12, 99일.

> 지시문의 CTL 50.18 / ATL 42.60은 합성값이다. **실제 CTL은 16 수준**으로, 한 자릿수 배 차이가 난다.

### 젖산역치 — 출처 불명, 7일 경과

`get_athlete_profile` 실제 응답:

```json
{"lactateThresholdHeartRateBpm":176,"lactateThresholdPaceSecondsPerKm":290,"initialized":true,
 "provenance":{"sourceTracked":false,"profileLastUpdatedAt":"2026-10-05T19:30:01.556876Z",
 "profileAgeDays":7,
 "warning":"Source (manual entry vs a Garmin auto-sync) is not tracked for this value; treat its provenance as unconfirmed."}}
```

* 값: **LTHR 176 bpm / 임계 페이스 290 s/km (4:50/km)** — 지시문의 165/310은 합성값이다.
* 최신성: **2026-10-05 갱신, 7일 경과.** 레이스(10-11)보다 **앞선** 값이다.
* 출처: **추적되지 않음.** `athlete_intensity_profile_history` 0행 — V23은 설계상 소급 기록을 만들지
  않으므로(마이그레이션 주석의 "No backfill"), 이 값이 수동 입력인지 Garmin 동기화인지 **시스템은 모른다.**
  `sourceTracked:false`로 정직하게 보고되고 있다.

**이것이 위 분석 해석에 직접 영향을 준다**: Zone 5 92.84 %와 "LTHR 이상 70.5 %"는 전부 이 출처 불명의
176 bpm에 상대적인 수치다. 레이스 평균 심박이 177 bpm으로 LTHR과 사실상 같다는 점을 보면, 임계치 자체가
낮게 잡혀 있을 가능성을 배제할 수 없다. 측정값은 그대로 두고, 이 의존성을 명시해 둔다.

---

## STEP 4 — 외부 전환 전 보안 확인 (전 항목 PASS, 단 선결 리스크 1건)

### 합성 데이터 연결 유지 — 유지됨

| 구성요소 | 상태 |
|---|---|
| 합성 Spring 18081 (PID 13980) | 기동 유지, DB `running_ai_7d_smoke` |
| 게이트웨이 17910 (PID 1760) | 기동 유지, target = 18081 |
| Quick Tunnel (PID 8400) | `cloudflared tunnel --url http://127.0.0.1:17910` — **17910만 노출** |
| Worker | `ORIGIN_MCP_URL` 미변경 |

**결정적 증거** — 같은 도구를 두 인스턴스에 호출한 결과:

| | 공개 경로가 보는 18081 | 로컬 전용 18082 |
|---|---|---|
| `get_athlete_profile` | **LTHR 165 / 310** (합성) | **LTHR 176 / 290** (실제) |

ChatGPT가 지금 연결돼 있는 경로는 **여전히 합성값을 반환한다.** 실데이터는 공개 경로에 올라가지 않았다.

### 실데이터 인스턴스 — localhost 전용

`18082`는 `127.0.0.1`에만 바인드돼 있다. 호스트의 **모든** 비루프백 IPv4에서 실측:

| IP | 18082 | 18081 | 18080 |
|---|---|---|---|
| 192.168.0.24 (LAN) | 닫힘 | 닫힘 | 닫힘 |
| 100.108.54.79 (tailnet) | 닫힘 | 닫힘 | 닫힘 |
| 172.22.80.1 (WSL) | 닫힘 | 닫힘 | 닫힘 |

터널은 17910만 바라보므로 **18082로 가는 외부 경로는 존재하지 않는다.**

### 무인증 · 위조 토큰 · 터널 직접 접근 차단

| 지점 | 시도 | 결과 |
|---|---|---|
| Worker `/mcp` | 토큰 없음 | **401** + `WWW-Authenticate: Bearer realm="OAuth", resource_metadata=…, scope="runningai:read"` |
| Worker `/mcp` | 임의 문자열 토큰 | **401** `invalid_token` |
| Worker `/mcp` | 정확한 `kid`·`sub`·`scope`를 넣은 **위조 서명 JWT** | **401** `invalid_token` |
| **터널 직접**(Worker 우회) | 토큰 없음 | **401** `invalid_request` + `resource_metadata` |
| **터널 직접** | 임의 토큰 / 위조 JWT | **401 / 401** `invalid_token` |

**Worker를 건너뛰어 터널로 직접 때려도 얻는 것이 없다.** 게이트웨이가 홉 토큰 서명을 독립 검증하기 때문이다.

사용자 제한은 오프라인 스위트가 덮는다 — 아래 테스트의 "다른 GitHub 계정 사인인 거부" 포함
(`RUNNING_AI_ALLOWED_SUB=github|86603575`).

### 경로 차단 — 쓰기 API 포함 전부 404

터널·Worker 양쪽에서 `/api/v1/activities`(GET·POST), `/api/v1/athlete/intensity-profile`,
`/api/v1/athlete/injury-reports`, `/actuator/health`, `/actuator/env`, `/`,
`/mcp/../api/v1/activities` → **16/16 전부 404 `{"error":"not_found"}`**. 응답 본문을 실제 값
(`176`, `290`, `21240`, `KNEE`, Garmin 활동 ID)으로 스캔했고 **유출 0건**이다. 공개 주소에서 쓰기 API에
닿을 수 없다.

### 실데이터의 로그·오류 유출 — 없음

18082 로그 70줄 전체를 `176|290|21240|24679734836|KNEE|password|Bearer|hrv|lactate`로 스캔 → **0건**.
남는 것은 `MCP read tool <name>: ok` 형태의 이름·결과뿐이고, stderr은 0바이트다.

도구 응답 7종도 금지 항목으로 스캔했다: Garmin 활동 ID, Intervals 활동 ID, 위경도, 내부 DB id,
`externalId`, 자격증명 패턴 → **전부 0건.** 실데이터에서도 요약만 나가고 원시 샘플·GPS·외부 식별자는
나가지 않는다.

### 운영 DB는 외부 MCP 경로에 없음

외부 경로는 `Worker → Tunnel → 17910 게이트웨이 → 18081 Spring → running_ai_7d_smoke` 하나뿐이다.
운영 `running_ai`도, 실데이터 사본 `running_ai_realcopy`도 이 경로에 **없다.** 운영 Spring 18080은
터널에도 게이트웨이에도 연결돼 있지 않다.

### 오프라인 회귀 — 100/100

| 스위트 | 결과 |
|---|---|
| mcp-worker | **28/28 PASS** |
| OAuth E2E (실제 게이트웨이 프로세스 대상) | **43/43 PASS** |
| mcp-gateway | **29/29 PASS** |

포함 항목: 홉 토큰 검증, PKCE S256, DCR, 콜백 `iss`, refresh, 읽기 7종, **다른 GitHub 계정 사인인 거부**,
게이트웨이 직접 우회, `publish_workout` → `-32602`, `/api/v1/*`·`/actuator/*` → 404,
**로그에 토큰·클라이언트 시크릿·개인키가 남지 않는지 스캔**.

### ⚠ 선결 리스크 — RISK-H3가 실데이터 사본으로 확대됐다

7D-1이 올린 RISK-H3가 **미해결 상태 그대로이며, 이번에 영향 범위가 2배가 됐다.** 실측:

```
192.168.0.24:5432   reachable=True
100.108.54.79:5432  reachable=True   ← tailnet
```

그리고 **두 주소 모두에서 실제로 접속이 된다**(컨테이너 밖에서 확인):

```
FROM 192.168.0.24    -> running_ai_realcopy  rolsuper=true  ssl=off
FROM 100.108.54.79   -> running_ai_realcopy  rolsuper=true  ssl=off
```

즉 **개인 실데이터가 든 사본 DB가, 평문(ssl=off)으로, 슈퍼유저 권한으로, LAN과 tailnet 양쪽에서
접근 가능하다.** Spring 포트는 전부 막혀 있는데 PostgreSQL만 `0.0.0.0`으로 열려 있다. 이번 단계가
만든 결함은 아니지만, 이번 단계가 **같은 열린 포트 뒤에 실데이터 DB를 하나 더 놓았다.**

해결은 이미 승인 요청으로 올라가 있는 **항목 G**(`ports: ["127.0.0.1:5432:5432"]`)이고, 알려진 소비자는
모두 localhost를 쓰므로 영향은 0으로 평가돼 있다. **실데이터를 공개 HTTPS 경로에 붙이기 전에 항목 G를
먼저 처리할 것을 권고한다.** MCP 경로와 무관한 별개 통로지만, 노출되는 데이터는 동일하다.

---

## STEP 5 — 승인 대기 보고 (여기서 STOP)

### 1. 사본 DB 및 개발 Spring 준비 상태 — 준비 완료

* `running_ai_realcopy`: 운영 스냅숏 행 수 일치 확인 → V21~V23 적용 → **V23**
* 개발 Spring **127.0.0.1:18082**, RC jar(SHA 일치), 읽기 7종만 등록, 쓰기·스케줄러·외부 연동 전부 OFF
* 운영 `running_ai`는 **V20 그대로**, 운영 Spring 18080 `UP`, 변경 0건

### 2. MCP 실제 데이터 7종 로컬 검증 — 7/7 PASS

| 도구 | 결과 |
|---|---|
| `get_recent_activities` | 14일 창 4건(10-11 하프 포함), 30일 창에서도 정상 |
| `get_activity_analysis` (2026-10-11) | `COMPLETE`/`FULL`, 세션지표 + 러닝다이내믹스 전체 |
| `get_training_context` | **V2**, 11,494 B(64 KiB 가드 이내), sha256 `f4c43881…`, 커버리지 8/8 활동·89일 피트니스·21일 회복 |
| `get_recovery_context` | 기준선 `AVAILABLE`, HRV 30.0 vs 42.7 |
| `get_fitness_trend` | 실제 CTL/ATL/derived_form 반환 |
| `get_workout_recommendation` | **차단** — `INJURY_ACTIVE` (의도된 결과) |
| `get_athlete_profile` | 176 / 290, `sourceTracked:false` 경고 동반 |

`tools/list` = **정확히 7종**, `publish_workout` 미등록.

### 3. 실제 Garmin 데이터 조회 가능 여부 — 가능

2026-10-11 하프의 Garmin 원본 계열이 사본에 전부 있다: 랩 22, **샘플 6,724**(완전성 FULL),
존 10, 상세 1, 원시 페이로드 5, Intervals 매칭 1, 분석 1. 거리·시간·보폭·접지시간·러닝파워·심박·
케이던스·페이스가 모두 **저장된 실측값으로** 조회됐다. 없는 것은 명시했다: **회복 데이터 2026-10-04 및
10-07~10-10 누락**, **젖산역치 출처 미추적**.

### 4. 합성 → 실데이터 전환에 필요한 정확한 설정 — 한 줄

터널도 Worker도 ChatGPT 재등록도 건드릴 필요가 없다. **게이트웨이의 target 한 개만 바꾼다:**

```
RUNNING_AI_GATEWAY_TARGET_URL = http://127.0.0.1:18081/mcp   (현재, 합성)
                              → http://127.0.0.1:18082/mcp   (실데이터 사본)
```

절차: 게이트웨이 PID 1760 종료 → 같은 기동 스크립트에서 위 한 줄만 바꿔 재기동(포트 17910 유지).
`ORIGIN_MCP_URL`·터널 주소·Worker 버전·OAuth 등록 **전부 그대로**. 소요 약 10초.

**이 전환은 실행하지 않았다.** 지시대로 STOP 상태다.

### 5. 보안 검증 결과 — 통과, 단 항목 G 선행 권고

통과: 합성 연결 유지 확인, 실데이터 localhost 전용(비루프백 3개 IP에서 전 포트 닫힘), Worker·터널
무인증/위조 토큰 401, 터널 직접 접근 우회 불가, 비-MCP 경로 16/16 404, 로그·오류·도구 응답 실데이터
유출 0건, 운영 DB 외부 경로 미포함, 오프라인 100/100.

미해결: **RISK-H3** — PostgreSQL 5432가 LAN·tailnet에 평문·슈퍼유저로 열려 있고, 이제 그 뒤에
실데이터 사본이 하나 더 있다. **항목 G를 먼저 적용할 것을 권고한다.**

### 6. 전환 실패 시 합성 데이터 즉시 복구 절차

1. 게이트웨이(17910) 종료
2. `RUNNING_AI_GATEWAY_TARGET_URL=http://127.0.0.1:18081/mcp`로 되돌려 재기동
3. 확인: `get_athlete_profile` → **165 / 310**이면 합성으로 복귀 완료

합성 Spring 18081과 `running_ai_7d_smoke`는 전환 중에도 **계속 띄워 둔다**(내리지 않는다). 따라서 복구는
게이트웨이 재기동 한 번, 약 10초다. 터널·Worker·ChatGPT 등록은 어느 쪽에서도 건드리지 않으므로 재인증이
필요 없다.

더 나아간 복구가 필요하면: 개발 Spring 18082 종료 → `DROP DATABASE running_ai_realcopy`.
운영 복원이 필요한 경우(이번 단계는 운영을 건드리지 않았으므로 해당 없음)
`.runtime/backup/running_ai-prod-20261013-125626.dump`를 `pg_restore`하면 된다 —
7D-1에서 round-trip이 검증된 경로다.

---

## 변경하지 않은 것

운영 Spring 18080(PID 29984, 재시작 없음, `UP`), 운영 DB `running_ai`(V20, 20행, 쓰기 0건),
Relay 17845(PID 13588), Watchface, Funnel 443, Watchdog, Canonical `main`, `origin`,
`C:\running-ai`, `C:\running-ai-watchface` — **전부 접근·변경 0건.**
`server/` 이하 코드 변경 0건, 신규 기능·웹 UI 개발 0건, 커밋·푸시 0건.

## 한계

* 실데이터는 **아직 ChatGPT에서 호출되지 않았다.** 본 보고의 7종 검증은 전부 localhost 18082 직접 호출이다.
* 사본의 `injury_report` 1행과 재분석된 `activity_analysis` 1행은 이번 단계가 **사본에만** 쓴 행이다.
  운영 DB에는 둘 다 없다.
* 회복 데이터 공백(10-04, 10-07~10-10)은 메울 방법이 없다 — Garmin 재동기화가 필요한데 이번 단계는
  외부 호출을 하지 않는다.
* 젖산역치 출처는 V23 설계상 소급 복원이 불가능하다. 해소하려면 사용자가 값을 한 번 재확인해
  새 이력 행을 만드는 수밖에 없다.
