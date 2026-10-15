# Phase 8A-1 — Garmin 운영 동기화 활성화 준비 (사전 점검, 실행 없음)

- 날짜: 2026-10-15 (측정 시각 08:11–08:13 KST = 2026-10-14T23:11–23:13Z)
- 브랜치: `main-3`, HEAD `b1feda7` (운영 배포본 `f1451f5` + 문서 1커밋)
- 운영 체크아웃: `C:\running-ai-github`, Spring `127.0.0.1:18080`, Flyway head **V28**
- 상태: **사전 점검 완료 / STOP**

금지 항목은 **하나도 실행하지 않았다**: 운영 Garmin 동기화 0회, 스케줄러 활성화 없음,
회복 백필 없음, 운영 DB 쓰기 0건, Worker/Spring 재배포 없음, commit/merge/push 없음,
OAuth·publish 권한 변경 없음.

유일한 외부 호출은 **loopback 전용**이었다: 운영 DB `SELECT`, `127.0.0.1:18080`의
읽기 전용 HTTP, `127.0.0.1:8765/health`(프로세스 생존 확인만, Garmin 미호출).

---

## 작업 1 — 스케줄러 확인

### 1.1 주기와 활성화 설정 — 기본값 / 환경변수 오버라이드 구분

| 속성 | application.yml 기본값 | 환경변수 | 운영 .env 값 | 운영 유효값 |
| --- | --- | --- | --- | --- |
| `running-ai.garmin.scheduler.enabled` | `false` (**리터럴, `${}` 플레이스홀더 없음**) | `RUNNING_AI_GARMIN_SCHEDULER_ENABLED` | **미설정** | **false** |
| `running-ai.garmin.scheduler.fixed-delay` | `1h` | `RUNNING_AI_GARMIN_SCHEDULER_FIXED_DELAY` | 미설정 | 1h |
| `running-ai.garmin.scheduler.initial-delay` | `1m` | `RUNNING_AI_GARMIN_SCHEDULER_INITIAL_DELAY` | 미설정 | 1m |
| `running-ai.garmin.sync.page-size` | `50` (플레이스홀더 없음) | — | — | 50 |
| `running-ai.garmin.sync.overlap` | `7d` (플레이스홀더 없음) | — | — | 7d |
| `running-ai.garmin.sync.max-pages` | `10` (플레이스홀더 없음) | — | — | 10 |
| `running-ai.garmin.connector.connect-timeout` | `2s` | — | — | 2s |
| `running-ai.garmin.connector.read-timeout` | `30s` | — | — | 30s |

**`enabled`만 `${}` 플레이스홀더가 없는 유일한 스위치다.** 그래도 환경변수는 유효하다:
`SystemEnvironmentPropertySource`가 application.yml보다 우선순위가 높고 `_` ↔ `.`/`-` 완화 바인딩을
수행하기 때문이며, 이 사실은 **추정이 아니라 테스트로 고정**되어 있다 —
`GarminSyncSchedulerWiringTest.environmentVariableNamesBindToTheSchedulerProperties`가
`RUNNING_AI_GARMIN_SCHEDULER_ENABLED` → `running-ai.garmin.scheduler.enabled` 해석을 직접 단언한다.
`.env.example:69-71`에도 같은 주의가 이미 문서화되어 있다.

`@ConditionalOnProperty`가 false면 `GarminSyncScheduler`와 `GarminSyncSchedulingConfig`
(`@EnableScheduling`) **빈 자체가 생성되지 않는다**. `GarminSyncSchedulerDisabledTest`는
`ScheduledAnnotationBeanPostProcessor`조차 없음을 단언한다.

**고정 지연(fixed-delay)이지 cron이 아니다.** 다음 틱은 **이전 실행 종료 시점**부터 1시간 뒤다.

### 1.2 마지막 성공 체크포인트 (운영 DB 실측)

```
garmin_sync_state  (1행, athlete_id=1)
  high_water_started_at   = 2026-10-10T22:39:40Z   (= 2026-10-11 07:39 KST)
  last_successful_sync_at = 2026-10-12T03:24:44Z   (= 2026-10-12 12:24 KST)
  updated_at              = 2026-10-12T03:24:44Z
```

`GET /api/v1/garmin/sync/status` → `initialized:true`, `lastAttempt:null` (= 8A 원장 미기록).
마지막 성공으로부터 **67.8시간** 경과.

`high_water`(10-10) < `last_successful`(10-12)인 것은 정상이다: 10-12 동기화는 성공했지만
10-10보다 새로운 활동이 Garmin에 없었다는 뜻이다.

### 1.3 현재 시각 기준 조회 범위

```
cutoff = high_water_started_at − overlap(7d) = 2026-10-03T22:39:40Z
```

페이지는 **최신 → 과거** 순으로 읽고, 한 페이지의 가장 오래된 파싱 가능 `startTime`이
cutoff 이하가 되면 멈춘다. 즉 실질 조회 범위는 **2026-10-03T22:39:40Z 이후 약 11일**이며,
overlap 7일은 "업로드 후 수정된 Garmin 활동"을 다시 집어 오기 위한 의도된 재조회 구간이다.

### 1.4 Garmin 예상 요청량 — **틱당 정확히 1회**

Garmin 계정 이력이 **2026-09-18 이후**뿐이고 2026-10-12 시점 목록이 **10건**이었다
(운영 `activity_raw` 10행 = 러닝 8 + `breathwork` 2). 따라서 page 0(limit=50)에서:

- `items.size()(약 10..13) < page-size(50)` → `cutoffReached=true`, break
- 동시에 페이지 최소 `startTime`(2026-09-18T02:10:45Z) ≤ cutoff(2026-10-03) → 어차피 break

**두 종료 조건 모두 page 0에서 충족**되므로 커넥터 호출 1회 = Garmin `get_activities(0, 50)` 1회.

| 트리거 | Garmin 요청/실행 | 하루 상한 |
| --- | --- | --- |
| 수동 `POST /api/v1/garmin/sync` | 1 | 조작 횟수만큼 |
| 활동 스케줄러 (활성화 시, 1h) | 1 | **24** |
| 프로필 동기화 스케줄러 (**이미 ON**, 04:30 KST) | 1 | 1 |

활동 스케줄러를 켜도 Garmin 요청은 **하루 약 25회**다. `max-pages=10`이 걸리는 최악의 경우에도
틱당 10회(하루 240회)가 상한이며, 현재 데이터 밀도에서는 도달 불가능하다.

**이 계정의 Garmin을 호출하는 다른 자동화는 없음을 확인했다.** 실행 중인 레거시 작업
(`RunningAI-NewRunCheck` 30분, `RunningAI-ChatGPT-Sync` 10분, `RunningAI-TrainingDecision` 1분,
`RunningAI-NtfyDecisionListener` 1분, `DailyBrief`, `WeeklyReport`)은 `C:\running-ai\scripts\`의
스크립트를 호출하며, `check-new-run.ps1`은 **Intervals.icu만** 조회한다(`garmin`/`:8765` 매치 0건).
Garmin을 호출하는 레거시 스크립트(`create-today-workout.ps1`, `command-channel-*`)의 작업은
모두 **Disabled**다(`RunningAI-TodayWorkout`, `RunningAI-CommandChannel`, `RunningAI-TrainingCommand`).

### 1.5 401 / 429 / 타임아웃 처리

경로: `python-garminconnect` → 커넥터(FastAPI) → `HttpGarminActivitySource` → `GarminConnectorErrorMapper`.

| 상황 | 커넥터 | Spring 예외 | 수동 API 응답 | 스케줄러 동작 | `failure_code` |
| --- | --- | --- | --- | --- | --- |
| Garmin 401 | `GARMIN_AUTH_REQUIRED` | `Reason.AUTH_REQUIRED` | 401 `GARMIN_AUTH_REQUIRED` | WARN 1줄, 틱 종료 | `GARMIN_AUTH_REQUIRED` |
| Garmin 403 | — | `FORBIDDEN` | 403 | 동일 | `GARMIN_FORBIDDEN` |
| Garmin 429 | `GARMIN_RATE_LIMITED` | `RATE_LIMITED` | 429 | 동일 | `GARMIN_RATE_LIMITED` |
| 커넥터 미기동/TCP 실패 | — | `UNAVAILABLE` | 503 | 동일 | `GARMIN_UNAVAILABLE` |
| 502 / 비JSON 본문 | — | `UPSTREAM_ERROR`/`CONNECTOR_ERROR` | 502 | 동일 | `GARMIN_UPSTREAM_ERROR` 등 |
| read-timeout 30s 초과 | — | `ResourceAccessException` → `UNAVAILABLE` | 503 | 동일 | `GARMIN_UNAVAILABLE` |
| max-pages 소진 | — | `GarminIncrementalSyncException` | 503 `INCREMENTAL_WINDOW_INCOMPLETE` | 동일 | `INCREMENTAL_WINDOW_INCOMPLETE` |

핵심 안전성 3가지:

1. **재시도가 어디에도 없다.** 커넥터는 `Garmin(retry_attempts=0)`으로 라이브러리 재시도를 끈다
   (`client.py:26-29`). 스케줄러는 틱 안에서 재시도하지 않는다. 429/401이 연타될 경로가 없다.
2. **실패는 체크포인트를 절대 전진시키지 못한다.** 커넥터 예외는 체크포인트를 건드리기 전에
   전파되고, 매핑 실패(`failed > 0`)는 `advance()` 자체를 건너뛴다.
3. **`failure_code`에 메시지가 들어가지 않는다.** `GarminCollectionFailureCode`는 예외의
   **타입과 reason만** 쓴다 → 자격증명·토큰·페이로드가 DB로 샐 경로가 없다.

### 1.6 중복 실행 차단

`GarminSyncOperationService`의 **JVM 내 `ReentrantLock.tryLock()`**. 두 번째 요청은 큐잉되지 않고
즉시 `GarminSyncAlreadyRunningException` → 수동 409 `GARMIN_SYNC_ALREADY_RUNNING`,
스케줄러는 `SKIPPED_ALREADY_RUNNING` 로그 후 다음 틱 대기. 분산 락은 없다(단일 인스턴스 전제).

거부된 요청은 **의도적으로 `garmin_sync_attempt`에 기록되지 않는다** — Garmin에 도달한 적이 없으므로
수집 시도가 아니다.

**주의 — 회복 동기화는 별도 락이다.** `GarminRecoverySyncService`는 자기 `ReentrantLock`을 갖는다.
따라서 **활동 동기화와 회복 동기화는 동시에 Garmin을 칠 수 있다.** 회복 백필(28일 = 140 호출,
2초 간격)을 수동 실행하는 동안 시간당 틱이 끼어들면 그 시점에 요청 1건이 추가된다.

### 1.7 프로세스 재시작 후 동작

- 체크포인트는 DB에 있으므로 재시작으로 잃지 않는다.
- 기동 후 **initial-delay 1분** 뒤 첫 틱, 이후 **이전 실행 종료 기준** 1시간 간격.
- **미실행분 보충(catch-up)이 없다.** 3일간 꺼져 있었어도 켜진 뒤 한 번만 돈다 — overlap 7일이
  그 공백을 덮으므로 데이터 손실은 없다.
- `ReentrantLock`은 메모리에만 있으므로 재시작 시 해제된다.

**실측된 부작용 하나 — 이 PC는 잠든다.** 프로필 동기화 로그에서 2026-10-11 틱이 04:30이 아니라
**04:43**에 발화했다(같은 PID, 즉 JVM은 살아 있었다). 고정 지연 스케줄은 슬립 구간만큼 밀리며,
"하루 24회"는 **상한**이지 보장값이 아니다.

### 1.8 실패 이력과 연속 실패 기록 (Phase 8A, V27)

`garmin_sync_attempt`: **athlete당 1행**, 매 시도마다 덮어쓰기. 전체 시도 이력이 아니다.

| 결과 | 조건 | `consecutive_failure_count` |
| --- | --- | --- |
| `SUCCESS` | 완주 + `failed == 0` | **0으로 리셋** |
| `PARTIAL` | 완주 + `failed > 0` (체크포인트 미전진) | **0으로 리셋** |
| `FAILED` | 예외 발생 | **+1** |

`last_failure_at` / `last_failure_code`는 **이후 성공에도 살아남는다**("언제 마지막으로 고장났나").
기록은 `REQUIRES_NEW` 트랜잭션이므로 호출자 트랜잭션이 롤백돼도 남고, 기록 실패는 로그 후 삼켜져
원래 결과나 예외를 절대 바꾸지 않는다.

**중요한 한계 2가지**

- **`consecutive_failure_count`를 읽어 행동하는 코드는 없다.** `DataFreshnessService`와
  `GarminSyncStatusResponse`가 보고만 한다. **자동 차단기가 없다** → 지속적 401이면 고정 지연
  1시간으로 **하루 24회 인증 실패를 무한정** 반복한다. 사람이 차단기다(§3.5).
- **JVM이 강제 종료되면 시도 행이 남지 않는다.** `REQUIRES_NEW`는 예외가 프로세스 안에서
  전파될 때만 동작한다. Watchdog이 Spring을 재시작하면(§1.10) 그 시도는 원장에서 사라진다.

### 1.9 수동 동기화와 스케줄러 동시 실행 방지

같은 `GarminSyncOperationService.runSync()` 단일 진입점을 쓰므로 §1.6의 락 하나가 둘을 모두 덮는다.
수동이 먼저면 스케줄러가 틱을 건너뛰고, 스케줄러가 먼저면 수동 요청이 409를 받는다.
**어느 쪽도 두 번째 Garmin 동기화를 시작하지 못한다.**

### 1.10 Watchdog과 충돌 여부

`RunningAI-Watchdog`: 로그온 트리거 + **5분 반복**, 마지막 실행 08:11:13 KST, `overall: UP`,
재시작 예산 10분당 3회, 24시간 6회 장기 락아웃, 현재 사용량 전 컴포넌트 0.

**이미 활동 스케줄러 전용 연동이 들어 있다.** `Get-GarminHint`(`RunningAI.Watchdog.ps1:465`)는
`spring.out.log` 마지막 300줄에서

- `Garmin scheduled sync completed` → 힌트 해제
- `Garmin scheduled sync failed: reason=(AUTH_REQUIRED|FORBIDDEN|RATE_LIMITED|UPSTREAM_ERROR)` → 힌트 설정

을 읽어 **최근 120분 이내**면 `overall`을 `DEGRADED`로 만든다. 주석이 명시하듯
**이 힌트는 절대 재시작을 유발하지 않는다** — 인증·레이트리밋은 프로세스 장애가 아니기 때문이다.
즉 **설계상 충돌은 없고, 오히려 스케줄러를 켜야 이 관측 경로가 처음으로 살아난다.**

남는 상호작용은 하나뿐이다: Watchdog이 **다른 이유로** Spring을 재시작하면 진행 중이던 동기화가
중단된다. 결과는 (a) 이미 커밋된 raw/activity 행은 남고, (b) 체크포인트는 전진하지 않으며,
(c) 다음 실행이 overlap으로 같은 구간을 멱등 재처리하고, (d) **그 시도만 원장에 남지 않는다**(§1.8).
데이터 손상은 없다.

---

## 작업 2 — 운영 수동 동기화 사전 점검 (실행 없음)

### 2.1 기준선 (2026-10-14T23:11–23:13Z 실측, 읽기 전용)

| 테이블 / 항목 | 값 |
| --- | --- |
| `activity` | **48** (GARMIN 8 / INTERVALS_ICU 25 / STRAVA 15) |
| `activity_raw` | **10** (전부 GARMIN, `fetched_at` 전부 2026-10-12T03:24:41Z) |
| ├ 정규화된 것 | 8 (running 2, track_running 3, treadmill_running 2, indoor_cycling 1) |
| └ `activity_id` NULL | **2** (`breathwork` 24449361556 / 24438659533 = 미지원 타입, `skipped`) |
| `activity_raw_payload` | 113 |
| `activity_detail` / `activity_analysis` | 48 / 48 |
| `garmin_sync_state` | 1행 (§1.2) |
| `garmin_sync_attempt` | **0행** |
| `garmin_recovery_daily` / `garmin_recovery_collection` | 21 / **0** |
| 마지막 성공 시각 | 2026-10-12T03:24:44Z (67.8h 전) |
| 최근 Garmin 활동 | 2026-10-10T22:39:40Z — `latestDataDate` **2026-10-11** (KST), 4일 전 |
| Flyway head | **V28** (V27/V28 `success=t`, 2026-10-15 08:00:06) |
| 운영 DB 백업 | `.runtime/backup/running_ai-prod-PRE-8A-20261015-075436.dump` (7.2 MB, `pg_dump -Fc`, **복원 증명됨**) — 단 **8A 배포 직전** 시점이며 그 이후 운영 데이터 변경은 없다 |
| Garmin 인증 상태 | **유효** — 프로필 동기화가 **2026-10-15 04:30:00 KST(3.7시간 전)** 성공(`UNCHANGED`, 3.5초). 10-09~10-15 **7일 연속 성공**, 401/429 0건 |
| 커넥터 | UP (`127.0.0.1:8765/health`), PID 11324, `LAUNCHER_CHILD` 소유권 검증됨 |
| Spring | UP, PID 22596, 08:00:10 기동, 기동 후 ERROR **0건** (§4.1의 자체 프로브 4건 제외) |
| `Garmin scheduled sync` 로그 | **전체 로그 통틀어 0줄** — 활동 스케줄러는 운영에서 **한 번도 돈 적이 없다** |

### 2.2 수동 1회 실행 시 예상 외부 요청

| 대상 | 횟수 |
| --- | --- |
| 커넥터 `GET /activities?start=0&limit=50` | **1** |
| Garmin `get_activities(0, 50)` | **1** |
| Intervals.icu / Strava / 그 외 | **0** |
| 외부 **쓰기** | **0** (게시 스위치 4종 전부 false) |

### 2.3 수동 1회 실행 시 예상 DB 변경 범위

`N` = Garmin 목록에 새로 생긴 항목 수, `M` = 그 중 지원 타입 수 (`M ≤ N`).

| 테이블 | 변경 | 비고 |
| --- | --- | --- |
| `activity_raw` | **UPDATE 10** (`payload` + `fetched_at` 갱신) + **INSERT N** | |
| `activity` | **UPDATE 8** (`updateFrom`) + **INSERT M** | |
| `garmin_sync_state` | **UPDATE 1** — `last_successful_sync_at` 갱신, `high_water`는 더 새로운 활동이 있을 때만 전진 | |
| `garmin_sync_attempt` | **INSERT 1** — 운영 **최초 행** | |
| `activity_detail` / `_lap` / `_sample` / `_zone` / `activity_analysis` | **0** | 증분 동기화는 요약만 수집한다 |
| `activity_intervals_metrics` / `intervals_*` / `strava_*` | **0** | |
| DELETE | **0** (전 경로에 삭제 없음) | |

합계 약 **20 + 2N 행 쓰기**. N이 0이어도 20행이 갱신된다(멱등 재수집).

> **원본 보존에 대한 정확한 사실.** `ActivityRawService.saveOrUpdate`는 기존 `payload`를
> **교체(`refresh`)**한다. 버전 이력이 없으므로 **이전 raw 스냅샷은 복구 불가**다.
> Garmin이 응답을 바꿨다면 옛 원본은 사라진다 → **수동 실행 직전 DB 백업이 유일한 보존 수단**이다.
> `activity_raw_payload`(상세 수집용, 113행)는 이 경로가 건드리지 않는다.

### 2.4 첫 성공 후 확인할 항목 (검증 쿼리 포함)

```sql
-- 1) 동기화 결과 / 건수 / 중복 / 오류 정보 / 연속 실패
SELECT outcome, attempted_at, fetched, created, updated, skipped, failed,
       pages_fetched, checkpoint_advanced, consecutive_failure_count,
       failure_code, last_failure_at, last_failure_code
FROM garmin_sync_attempt WHERE athlete_id = 1;
-- 기대: outcome=SUCCESS, pages_fetched=1, fetched 약 10+N, created=M, updated=8,
--       skipped=2(breathwork), failed=0, consecutive_failure_count=0, failure_code IS NULL

-- 2) 중복이 생기지 않았는지 (외부 동일성 유일 제약의 실제 결과)
SELECT external_source, count(*), count(DISTINCT external_id) FROM activity GROUP BY 1;
-- 기대: GARMIN 8+M, 두 수치가 항상 동일. INTERVALS_ICU 25 / STRAVA 15 불변

-- 3) 체크포인트 변화
SELECT high_water_started_at, last_successful_sync_at FROM garmin_sync_state;
-- 기대: last_successful 갱신. 신규 활동이 없으면 high_water = 2026-10-10T22:39:40Z 불변

-- 4) 원본 보존 (교체 의미 확인)
SELECT count(*) AS raw_rows, count(*) FILTER (WHERE activity_id IS NULL) AS unmapped,
       min(fetched_at), max(fetched_at) FROM activity_raw;
-- 기대: raw_rows = 10+N, unmapped = 2 (+ 새 미지원 타입), fetched_at 전부 금번 시각

-- 5) 기존 러닝 분석 정합성
SELECT count(*) FROM activity_analysis;   -- 기대: 48 불변 (증분 동기화는 분석을 만들지 않는다)
```

```
# 6) 통합 확인 — 네트워크 0회
GET /api/v1/garmin/sync/status   -> lastAttempt 가 null 이 아니게 됨
GET /api/v1/data-freshness       -> GARMIN_ACTIVITY.lastAttemptOutcome=SUCCESS,
                                    consecutiveFailureCount=0,
                                    analysis.analysisMissingOrOutdated 가 M 만큼 증가
```

**미리 알아야 할 정상 동작 2가지**

1. **신규 활동은 상세·분석 없이 들어온다.** 증분 동기화는 `activity` + `activity_raw`만 만든다.
   상세 수집(`POST /api/v1/garmin/activities/{garminActivityId}/details`)과 분석은 여전히 수동이며,
   따라서 `analysisMissingOrOutdated`가 0에서 M으로 오르는 것은 **결함이 아니라 설계**다.
2. **기존 8건의 `updateFrom`이 값을 바꿀 수 있다.** Garmin에서 활동을 편집했다면
   duration/distance/HR이 바뀌고, 그러면 `analysis_input_digest`가 달라져 해당 분석이 outdated가 된다.
   위 쿼리 5가 48 그대로인지로 즉시 드러난다.

---

## 작업 3 — 운영 활성화 계획 (수동 동기화 성공 이후에만)

### 3.1 전제 조건 (전부 충족되어야 진행)

1. §2 수동 동기화 1회 성공 — `garmin_sync_attempt.outcome = SUCCESS`
2. §2.4의 1~6 전 항목 기대치 일치, 특히 중복 0 / `activity_analysis` 48 불변
3. 직전에 `pg_dump -Fc` 신규 백업 1개
4. 게시 스위치 4종 전부 false 재확인
   (`WORKOUT_PUBLISHING_ENABLED`, `WORKOUT_PUBLISHING_SCHEDULER_ENABLED`,
   `RUNNING_AI_DRAFT_PUBLISHING_ENABLED`, `RUNNINGAI_MCP_ENABLED`)

### 3.2 활성화 절차 — 기존 스위치 재사용, 신규 스케줄러 없음

```
1) .env 에 한 줄만 추가:  RUNNING_AI_GARMIN_SCHEDULER_ENABLED=true
   - fixed-delay / initial-delay 는 건드리지 않는다 (1h / 1m 기본값 유지)
   - 주기 단축 금지, 새 스케줄러 추가 금지, 코드 변경 없음, 재빌드 없음
2) stop-running-ai.ps1 -> start-running-ai.ps1   (같은 jar, 재배포 아님)
3) 기동 후 3분간 spring.out.log 관측
```

### 3.3 활성화가 실제로 걸렸는지 판정하는 단 하나의 기준

`enabled`는 application.yml에 `${}` 플레이스홀더가 없는 유일한 스위치다(§1.1). 바인딩은
테스트로 보장되지만, **운영에서의 증거는 로그 한 줄뿐이다**:

```
기동 + 1분(initial-delay) 이내에 다음 중 하나가 반드시 나타나야 한다
  "Garmin incremental sync completed: ..."     (GarminIncrementalSyncService)
  "Garmin scheduled sync completed: ..."       (GarminSyncScheduler)
  "Garmin scheduled sync failed: reason=..."   (실패했어도 스케줄러는 살아 있다는 증거)
```

**기동 후 3분이 지나도 `Garmin scheduled sync` 문자열이 0줄이면 바인딩 실패로 간주하고
즉시 `.env` 한 줄을 되돌린 뒤 재기동한다.** "켠 줄 알았는데 안 켜진" 상태로 관측을 중단하는 것이
이 단계의 유일한 실질 위험이다.

### 3.4 활성화 후 최초 실행 관측 항목

| # | 확인 | 수단 |
| --- | --- | --- |
| 1 | 첫 틱 발화 | `spring.out.log`의 `Garmin scheduled sync` 1줄 |
| 2 | 요청량 | 같은 줄의 `pages=1` |
| 3 | 결과 기록 | `garmin_sync_attempt.attempted_at` 갱신, `outcome` |
| 4 | 중복 없음 | §2.4 쿼리 2 |
| 5 | 두 번째 틱 간격 | 첫 틱 종료 + 1h (슬립 시 지연은 정상) |
| 6 | Watchdog | `watchdog-status.json`의 `overall`이 `UP` 유지, `garminHint: null` |
| 7 | 외부 쓰기 0 | Intervals/Garmin 쓰기 로그 0줄 |

최소 **24시간(틱 12회 이상)** 관측 후에만 안정화로 판정한다.

### 3.5 즉시 중단 기준과 복구 절차 — **사람이 차단기다**

자동 차단기는 **존재하지 않는다**(§1.8). 아래는 수동 규칙이다.

| 트리거 | 조치 |
| --- | --- |
| `failure_code = GARMIN_RATE_LIMITED` **1회라도** | **즉시 중단.** 재시도가 없으므로 폭주는 아니지만, 1시간 간격이 과한 계정 상태라는 뜻이다. 최소 24시간 중단 후 수동 1회로 재검증 |
| `failure_code = GARMIN_AUTH_REQUIRED` **1회라도** | **즉시 중단.** 세션 만료이므로 틱을 반복해도 절대 자가 복구되지 않고 하루 24회 인증 실패만 쌓인다. 호스트에서 커넥터 재로그인 → 수동 1회 성공 후에만 재활성화 |
| `consecutive_failure_count >= 3` (사유 무관) | **중단**, 원인 규명 전까지 재활성화 금지 |
| `outcome = PARTIAL` 2회 연속 | 중단하지 않되 조사. 체크포인트가 전진하지 못하므로 매 틱이 같은 구간을 반복한다 |
| `INCREMENTAL_WINDOW_INCOMPLETE` | 중단. `max-pages`를 늘리기 전에 왜 11일 구간이 10페이지를 넘었는지 먼저 규명 |
| 활동 중복 행 발생 | **즉시 중단 + 백업 복원 검토** |
| Watchdog `overall = DEGRADED` + `garminHint` 설정 | 위 표의 사유별 규칙 적용 |

**중단 절차(30초, 롤백이 아니다):**

```
.env 에서 RUNNING_AI_GARMIN_SCHEDULER_ENABLED=false  ->  stop -> start
```

스케줄러 빈 자체가 사라진다. 체크포인트·데이터는 그대로이고 수동 `POST /api/v1/garmin/sync`는
계속 쓸 수 있다.

**데이터 복구가 필요한 경우에만** `.runtime/backup/*.dump`를 쓴다. 증분 동기화는 DELETE를 하지 않고
UPSERT만 하므로, 이 경로 때문에 복원이 필요해질 시나리오는 사실상 없다.

### 3.6 회복 백필

**이번 범위 밖.** 스케줄러 24시간 안정화 이후 **별도 승인**으로 `onlyMissing=true` 실행.
`datesWorthFetching` 9일(10-04, 10-06~10-10, 10-13~10-15) × 5 호출 = **45 Garmin 요청**,
2초 간격 최소 90초. 회복 동기화는 별도 락이므로(§1.6) 실행 중 시간당 틱과 겹칠 수 있다.

---

## 작업 4 — 발견된 결함 및 Git 상태 (조사만, 수정 없음)

### 4.1 404와 405의 구분 — 운영에서 직접 재현

읽기 전용 프로브만 사용했다(핸들러 진입 **전** 단계에서 예외가 나므로 어떤 컨트롤러도 실행되지 않는다).

| 요청 | 실제 | 기대 | 판정 |
| --- | --- | --- | --- |
| `GET /api/v1/no-such-path-xyz` | **404** `NOT_FOUND` | 404 | ✅ **정상 — 결함 아님** |
| `GET /api/v1/activities/999999` | **404** `ACTIVITY_NOT_FOUND` | 404 | ✅ 정상 |
| `GET /api/v1/activities/not-a-number` | **400** `INVALID_REQUEST` | 400 | ✅ 정상 |
| `GET /api/v1/garmin/sync` (POST 전용) | **500** | 405 | ❌ **결함** |
| `GET /api/v1/workout-drafts` (POST 전용) | **500** | 405 | ❌ **결함** |
| `DELETE /api/v1/activities/1` (GET 전용) | **500** | 405 | ❌ **결함** |
| `GET /api/v1/intervals/fitness` (필수 파라미터 누락) | **500** | 400 | ❌ **결함 — 8A 기록에 없던 신규 발견** |

**원인.** `GlobalExceptionHandler`는 `NoResourceFoundException`은 명시적으로 404로 잡지만
(그래서 경로 404는 멀쩡하다), `HttpRequestMethodNotSupportedException`과
`MissingServletRequestParameterException`에 대한 핸들러가 없어 `@ExceptionHandler(Exception.class)`
포괄 핸들러로 떨어진다. 스택 트레이스로 확인:

```
org.springframework.web.HttpRequestMethodNotSupportedException: Request method 'DELETE' is not supported
org.springframework.web.bind.MissingServletRequestParameterException: Required request parameter 'oldest' ... is not present
```

**부수 피해.** 포괄 핸들러는 `log.error("Unexpected error", ex)`로 **전체 스택 트레이스**를 남긴다.
위 4건의 프로브만으로 `spring.out.log`에 ERROR 4건·약 200줄이 쌓였다(그 전까지 기동 후 ERROR 0건).
Watchdog이 이 로그를 읽으므로, 잘못된 호출 몇 번이 운영 오류 신호를 희석한다.

`GlobalExceptionHandler`는 `ResponseEntityExceptionHandler`를 상속하지 않는다 — 이것이 Spring의
기본 MVC 예외 매핑이 전혀 적용되지 않는 구조적 이유다.

### 4.2 최소 수정 범위와 회귀 테스트 계획 (이번에 수정하지 않음)

**최소 수정 = `GlobalExceptionHandler`에 핸들러 2개 추가. 상속은 쓰지 않는다.**
`ResponseEntityExceptionHandler` 상속은 이미 매핑된 모든 예외의 **응답 본문 형태**
(`{code,message,timestamp}`)를 `ProblemDetail`로 바꿔 기존 계약을 깨뜨리므로 최소 변경이 아니다.

```java
@ExceptionHandler(HttpRequestMethodNotSupportedException.class)   // -> 405 METHOD_NOT_ALLOWED
@ExceptionHandler(MissingServletRequestParameterException.class)  // -> 400 INVALID_REQUEST (기존 코드 재사용)
```

- 405 응답에는 `Allow` 헤더(`ex.getSupportedHttpMethods()`)를 함께 실어 준다.
- 두 핸들러 모두 `log.error`가 아니라 `log.warn` 한 줄(스택 없음)로 낮춘다 — §4.1의 로그 오염 차단.
- 선택적 확장(같은 커밋에 넣을지는 별도 판단): `HttpMediaTypeNotSupportedException` → 415,
  `HttpMediaTypeNotAcceptableException` → 406.
- 기존 도메인별 `@RestControllerAdvice` 10개는 **건드리지 않는다**.
  `GarminSyncExceptionHandler`는 `@Order(HIGHEST_PRECEDENCE)`이므로 영향 없다.

**회귀 테스트 계획**

| # | 테스트 | 내용 |
| --- | --- | --- |
| 1 | 신규 `GlobalExceptionHandlerApiTest` | `GET /api/v1/garmin/sync` → 405 + `code=METHOD_NOT_ALLOWED` + `Allow: POST` |
| 2 | 동 | `DELETE /api/v1/activities/1` → 405 |
| 3 | 동 | `GET /api/v1/intervals/fitness` (파라미터 없음) → 400 `INVALID_REQUEST` |
| 4 | 동 (**회귀 방지**) | `GET /api/v1/no-such-path-xyz` → **404 `NOT_FOUND` 유지** (404/405가 섞이지 않음을 고정) |
| 5 | 동 (**회귀 방지**) | `GET /api/v1/activities/999999` → 404 `ACTIVITY_NOT_FOUND` 유지 |
| 6 | 기존 `WorkoutPublishApiTest.getNeverPublishes` | **주석 갱신 필요.** 현재 "the existing global catch-all currently answers unsupported methods with 500"이라고 명시. 단언은 `isNotEqualTo(2xx)`뿐이라 **통과는 유지**되지만 주석이 거짓이 된다 |
| 7 | 기존 `GarminSyncApiTest` / `WorkoutDraftApiTest` / `DraftPublishApiTest` | 409/422/404 매핑 불변 확인 |
| 8 | 전체 | `.\gradlew.bat clean test` — 현재 기준선 **1472** 통과 |

**MCP 영향 없음**: 게이트웨이는 `/mcp` 외 모든 URL을 거부하므로 이 수정은 MCP 표면에 닿지 않는다.

### 4.3 Spring tools/list 13개 vs Gateway 노출 목록

백엔드 `POST 127.0.0.1:18080/mcp` `tools/list` **실측 13개**:

| 그룹 | 도구 | Gateway 허용목록 | 요구 스코프 |
| --- | --- | --- | --- |
| read (7) | `get_recent_activities`, `get_activity_analysis`, `get_training_context`, `get_recovery_context`, `get_fitness_trend`, `get_workout_recommendation`, `get_athlete_profile` | ✅ 포함 | `runningai:read` |
| train (5) | `create_workout_draft`, `get_workout_draft`, `revise_workout_draft`, `approve_workout_draft`, `preview_draft_publish` | ✅ 포함 | `runningai:train` |
| publish (1) | **`publish_approved_draft`** | ❌ **미포함** | `runningai:publish` |

**Gateway 노출 = 12개** (운영 `.runtime/mcp-gateway.cmd`의 `RUNNING_AI_ALLOWED_TOOLS` 실측).
`tools/list`는 `허용목록 ∩ 보유 스코프`로 필터링된다.

`publish_workout`(Phase 6C)은 목록에 **없다** — `RUNNINGAI_MCP_ENABLED=false`라 애초에 등록되지 않고,
허용목록에 넣으면 게이트웨이가 FATAL로 기동을 거부한다(`server.js:100-103`).

**`publish_approved_draft`가 호출 가능한 상태인지 — 추정하지 않고 사실만:**

| 계층 | 상태 |
| --- | --- |
| Spring 등록 | **되어 있음** — `tools/list` 13개에 포함 |
| loopback 직접 호출 | `POST 127.0.0.1:18080/mcp`에는 **인증이 없다**. 호스트에서 직접 호출하면 도달한다 |
| Gateway 경유 | **불가** — 허용목록에 없어 `tools/list`에서 보이지 않고 `tools/call`도 403 |
| Worker 토큰 | **불가** — `runningai:publish`는 Worker가 발급하지 않는다 |
| 게시 마스터 스위치 | `RUNNING_AI_DRAFT_PUBLISHING_ENABLED=false` → 도달해도 409 `DRAFT_PUBLISHING_DISABLED` |

즉 **외부에서는 도달 불가, 로컬에서는 도달 가능하나 마스터 스위치에서 막힌다.** 방어선은 2중이다.

### 4.4 Phase 7F Worker 운영 버전 vs Git 미커밋 변경

**운영 Worker가 Git보다 앞서 있다.** 워크트리 미커밋 변경은 **이미 배포된 상태**다.

| 파일 | 미커밋 변경 | 배포 여부 |
| --- | --- | --- |
| `tools/mcp-worker/src/index.js` | `describeRequestedScopes()` + `/authorize`·sign-in 진단 로그 2줄 (+17/−1) | **배포됨** (2026-10-14T10:54:06Z, 이전 버전 `2438d9a0-…`) |
| `tools/mcp-worker/wrangler.jsonc` | `ORIGIN_MCP_URL` → `comedy-awarded-trucks-constraints.trycloudflare.com` (1줄) | **배포됨** (이전 버전 `68aa9faf-…`) |
| `docs/work-orders/2026-10-14-phase-7f-3b-3-*.md` (2개) | 미추적 | N/A |

이 변경은 버려서는 안 된다 — 7F-3B-3을 **해결한 바로 그 증거**를 만들어낸 코드다:

```
15:03:08.187Z INFO authorize: client requested scope [runningai:read runningai:train]
15:03:13.159Z INFO sign-in approved for the permitted owner; granted scope [runningai:read runningai:train]
```

원인은 **클라이언트 측 캐시**로 확정되었다: 재연결은 설치 시점 스코프를 재생하고,
완전 삭제 후 재등록해야만 재탐색이 일어난다. 서버 측 광고는 처음부터 올발랐다.

**안전한 정리 방법 (이번 범위 밖, commit 금지)**

1. `src/index.js`는 **그대로 커밋**한다 — 배포본과 일치하고, 진단 가치가 영구적이다.
   배포 전 `npm run test:all` 49/49 통과 이력이 있고, 그중 "로그에 비밀값 없음" 5개 검사가
   새 줄까지 덮는다.
2. `wrangler.jsonc`의 `ORIGIN_MCP_URL`은 **판단이 필요하다.** trycloudflare 퀵터널 호스트명은
   **cloudflared 재시작마다 바뀐다.** 지금 값을 커밋하면 다음 재시작 즉시 거짓이 되고,
   되돌리면 Git이 운영과 어긋난다. 권장: **그대로 커밋하되** 휘발성 값임을 명시하거나,
   고정 호스트명(named tunnel / 이미 보유한 Tailscale Funnel
   `RUNNING_AI_EXTERNAL_BASE_URL`)으로 옮기는 별도 과제로 분리한다. **후자가 근본 해결이다.**
3. 워크오더 2개는 함께 커밋한다.
4. 커밋 전 비밀값 점검: 두 diff 모두 토큰·키를 포함하지 않음을 확인했다
   (`ALLOWED_GITHUB_ID`는 기존 커밋된 값, 변경 없음).

### 4.5 Flyway V29 예약 확인

- 운영 적용 최대 버전 **V28** (`create garmin recovery collection`, 2026-10-15 08:00:06, `success=t`)
- 저장소 마이그레이션 파일 최대 **V28**. V29 파일은 **아직 존재하지 않는다**
- 로드맵 P1-3(게시 실패 관측성)이 `V29__create_workout_draft_publish_attempt.sql`로
  **이미 재번호 부여되어 있다**(`2026-10-15-feature-completion-audit-and-roadmap.md:196,201`,
  커밋 `f1451f5`에서 해소). 원래 V27을 선점하고 있었으나 8A가 V27/V28을 실제로 사용했다
- **결론: 다음 사용 가능한 번호는 V29이며 충돌 없음.** 이번 Phase 8A-1은 마이그레이션을
  추가하지 않는다

---

## 결론

### 1. 스케줄러 활성화 가능 여부

**기술적으로 준비 완료. 단, 지금 켜서는 안 된다 — 수동 동기화 1회 성공이 선결 조건이다.**

활성화를 뒷받침하는 사실:

| # | 근거 |
| --- | --- |
| 1 | Garmin 인증 **현재 유효** — 프로필 동기화가 3.7시간 전 성공, 7일 연속 성공, 401/429 0건 |
| 2 | 요청량이 틱당 **1회**(하루 25회 이하) — 레이트리밋 위험 사실상 없음 |
| 3 | **재시도가 어디에도 없다** — 라이브러리(`retry_attempts=0`), 틱 내부 모두 |
| 4 | 실패는 체크포인트를 전진시킬 수 없고, 8A V27이 그 실패를 DB에 보이게 만들었다 |
| 5 | 수동/스케줄러가 **같은 단일 진입점 + 단일 락**을 공유한다 |
| 6 | Watchdog에 **Garmin 전용 힌트 파서가 이미 있고, 재시작을 유발하지 않는다** |
| 7 | 환경변수 바인딩이 **테스트로 고정**되어 있다 |
| 8 | Garmin을 호출하는 **활성 레거시 작업 0개**, 레거시 워크아웃 작성기 작업은 Disabled |
| 9 | 게시 스위치 4종 전부 OFF → 동기화가 외부 쓰기로 번질 경로 없음 |

활성화 전에 반드시 해소할 것:

| # | 미해소 항목 |
| --- | --- |
| A | **활동 목록 엔드포인트가 2026-10-12 이후 호출된 적이 없다.** 프로필 동기화는 인증을 증명할 뿐 `get_activities` 동작을 증명하지 않는다 → §2 수동 실행이 정확히 이 게이트다 |
| B | **`garmin_sync_attempt` 0행** — 8A 관측 기능이 운영 Garmin 상대로 한 번도 검증되지 않았다 |
| C | **자동 차단기 없음** — 지속적 401이면 하루 24회 인증 실패를 무한 반복한다. §3.5의 수동 규칙이 유일한 차단기 |

### 2. 운영 수동 동기화 예상 영향

Garmin 요청 **1회**, 외부 쓰기 **0건**, DB **약 20 + 2N 행 UPSERT**(DELETE 0),
`garmin_sync_attempt` **최초 1행 생성**, `activity_analysis` **48 불변**.
신규 활동이 있으면 상세·분석 없이 요약만 들어오며 `analysisMissingOrOutdated`가 그만큼 오른다 — 정상이다.

### 3. 중복 및 실패 처리 검증 결과

| 항목 | 결과 |
| --- | --- |
| 중복 활동 | **구조적으로 불가** — `uk_activity_external(external_source, external_id)` + UPSERT. 10-12 동기화가 전 10건을 재수집했음에도 `activity` 48 / `activity_raw` 10 그대로 |
| 동시 실행 | **차단 확인** — 단일 `ReentrantLock`, 큐잉 없음, 거부는 시도로 기록하지 않음 |
| 실패 기록 | **8A 복제본 실측 완료** — 2회 실패 후 `FAILED`/`GARMIN_UNAVAILABLE` 기록, 서비스가 503을 던진 뒤에도 행 생존(`REQUIRES_NEW`), 이후 성공해도 `last_failure_*` 유지, 체크포인트 불변 |
| 연속 실패 | **기록은 되나 아무도 읽지 않는다** — 자동 차단 없음 |
| 미기록 사각지대 | **JVM 강제 종료** 시 시도 행이 남지 않는다 |
| 부분 실패 | `failed > 0` → `PARTIAL` + 체크포인트 보류. 단 `PARTIAL`도 연속 실패 카운터를 0으로 되돌린다 |

### 4. 권장 실행 순서

```
0. (별도 승인)  pg_dump -Fc 신규 백업
1. (별도 승인)  POST /api/v1/garmin/sync  - 수동 1회, Garmin 요청 1회
2.              §2.4 검증 1~6 전수 확인
3.              24시간 대기, 추가 조치 없음          <- 조급한 활성화 방지
4. (별도 승인)  .env 에 RUNNING_AI_GARMIN_SCHEDULER_ENABLED=true 1줄 추가 -> stop/start
5.              기동 후 3분 내 "Garmin scheduled sync" 로그 확인 (없으면 즉시 되돌림)
6.              24시간 / 틱 12회 이상 관측 후 안정화 판정
7. (별도 승인)  회복 백필 onlyMissing=true - 9일, Garmin 45회
8. (별도 과제)  GlobalExceptionHandler 405/400 수정 + 회귀 테스트
9. (별도 과제)  mcp-worker 미커밋 2파일 커밋 + 퀵터널 호스트명 고정화
```

### 5. 즉시 중단 및 복구 기준

`GARMIN_RATE_LIMITED` 1회 / `GARMIN_AUTH_REQUIRED` 1회 / `consecutive_failure_count >= 3` /
`INCREMENTAL_WINDOW_INCOMPLETE` / 중복 행 발생 → **즉시 중단**.
중단 = `.env` 한 줄 `false` + stop/start (30초, 롤백 아님, 데이터 불변).
데이터 복구는 `.runtime/backup/*.dump`로만 하며, 이 경로는 DELETE를 하지 않으므로 필요해질 일이
사실상 없다. 상세는 §3.5.

### 6. 운영 실행을 위한 별도 승인 요청

아래 3건은 각각 **별개 승인**이 필요하며, 이번 작업에서는 **하나도 실행하지 않았다**:

| 요청 | 범위 | 외부 영향 |
| --- | --- | --- |
| **승인 ①** | 운영 수동 Garmin 동기화 **1회** (`POST /api/v1/garmin/sync`) + 직전 DB 백업 | Garmin 읽기 1회, 외부 쓰기 0 |
| **승인 ②** | ① 성공 + 24시간 경과 후, `RUNNING_AI_GARMIN_SCHEDULER_ENABLED=true` 1줄 + 재기동 | Garmin 읽기 하루 24회 이하, 외부 쓰기 0 |
| **승인 ③** | ② 안정화 후, 회복 백필 `onlyMissing=true` (9일) | Garmin 읽기 45회, 외부 쓰기 0 |

사전 점검 완료. **STOP.**
