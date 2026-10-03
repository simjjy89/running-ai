# RunningAI Phase 6H-6 — 90-Day Historical Backfill & Reprocessing

## 0. 작업 환경

이번 작업은 **Main PC**에서 수행한다.

Repository:

`C:\running-ai-github`

Baseline:

`50a59f0`

현재 완료 상태:

- Phase 6H-1A Detailed Activity Foundation ✅
- Phase 6H-1B Garmin Detailed Contract LIVE_VERIFIED ✅
- Phase 6H-1C Sample Fidelity ✅
- Phase 6H-4 Running Analysis Engine ✅
- Phase 6H-5 Intervals.icu Enrichment ✅
- Java 21 System Normalization ✅
- INTERVALS_API_KEY rotation ✅

현재 baseline:

- Spring: 1073 passed
- Python: 134 passed
- PostgreSQL 17: full suite GREEN
- Running Analysis version: `RUNNING_ANALYSIS_V1`
- Garmin sample default maxChart: 20000

Runtime safety:

```text
WORKOUT_PUBLISHING_ENABLED=false
WORKOUT_PUBLISHING_SCHEDULER_ENABLED=false
RUNNING_AI_DRAFT_PUBLISHING_ENABLED=false
RUNNINGAI_MCP_ENABLED=false
```

Legacy writer tasks:

`Disabled`

현재:

- Historical backfill = NOT_RUN
- TrainingContext V2 = NOT_RUN
- external workout writes = 0

이번 Phase의 목표는 **최근 90일의 운동 데이터 기반을 완성**하는 것이다.

---

# 1. 최종 데이터 목표

90-day activity history:

```text
Garmin activity summary
        ↓
Garmin detailed activity
├─ detail raw
├─ laps
├─ HR zones
├─ power zones
└─ full-resolution samples
        ↓
RunningAI Analysis V1
        ↓
Intervals activity enrichment
├─ training load
├─ intensity
├─ CTL
└─ ATL
```

별도로 최근 28일:

```text
Garmin Recovery
├─ HRV
├─ sleep
├─ resting HR
├─ Body Battery
└─ stress
```

를 유지한다.

Recovery는 이번 Phase에서도 **28일**이다.

Recovery를 90일까지 확장하지 않는다.

---

# 2. 작업 기록

지시서:

`docs/work-orders/2026-10-03-phase-6h-6-90-day-historical-backfill-instruction.md`

결과:

`docs/work-orders/2026-10-03-phase-6h-6-90-day-historical-backfill-result.md`

실제 Garmin/Intervals activity ID, GPS, API key, token은 결과 문서에 기록하지 않는다.

---

# 3. Git preflight

확인:

- repo = `C:\running-ai-github`
- branch = main
- working tree clean
- main = origin/main
- `50a59f0` ancestor

필요 시:

`git pull --ff-only origin main`

만 사용.

금지:

- reset --hard
- clean -fd
- force push
- destructive rebase

---

# 4. Live DB backup

실제 historical backfill 실행 전에 Main PostgreSQL DB의 backup을 만든다.

`.runtime/backups/phase-6h-6/`

또는 equivalent local-only path.

`.runtime` gitignore 확인.

가능하면 PostgreSQL native `pg_dump` 사용.

Docker PostgreSQL이라 host command가 없으면 container 내부 `pg_dump` 사용 가능.

확인:

- dump 생성 성공
- file size > 0
- repository 밖 또는 `.runtime`
- git tracked 아님

실제 secret을 명령/log에 노출하지 않는다.

rollback 명령을 result document에 기록한다.

---

# 5. DB size baseline

backfill 전 DB 크기를 기록한다.

가능하면:

```sql
SELECT pg_database_size(current_database());
```

그리고 주요 table row count도 기록.

최소:

```text
activity
activity_raw
activity_raw_payload
activity_detail
activity_lap
activity_zone
activity_sample
activity_analysis
activity_analysis_interval
activity_analysis_interval_group
activity_source_link
activity_intervals_metrics
intervals_fitness_daily
garmin_recovery_daily
```

backfill 후 같은 지표를 비교한다.

---

# 6. Backfill window

기본값:

- athlete timezone 기준 today
- days = 90

2026-10-03에 실행하면:

```text
2026-07-06 ~ 2026-10-03
```

90 calendar days inclusive.

API가 explicit:

```json
{
  "endDate": "2026-10-03",
  "days": 90
}
```

를 받을 수 있게 한다.

최대:

`90 days`

이번 Phase에서는 90일보다 긴 범위를 허용하지 않는다.

---

# 7. 기존 Incremental Sync 재사용 금지

현재:

`GarminIncrementalSyncService`

는 no-state bootstrap에서:

```text
one page only
```

를 가져온다.

따라서 historical backfill에 직접 재사용하지 않는다.

또한 historical backfill은:

`garmin_sync_state`

를 절대 advance/update하지 않는다.

Live run 전후:

```text
garmin_sync_state
```

의:

- high_water_started_at
- last_successful_sync_at

을 비교한다.

Historical backfill 때문에 변경되면 FAIL.

---

# 8. 별도 Historical Backfill architecture

권장 구조:

```text
HistoricalBackfillService
│
├─ GarminHistoricalActivityDiscoveryService
├─ GarminActivityDetailIngestionService
├─ RunningActivityAnalysisService
├─ IntervalsHistoricalEnrichmentService
└─ GarminRecoverySyncService
```

하나의 giant method 안에 모든 로직을 몰아넣지 않는다.

외부 I/O와 local computation을 구분한다.

---

# 9. Manual only

Backfill은:

- scheduler 없음
- startup auto-run 없음
- background periodic job 없음

명시적인 manual trigger만 허용.

예:

```text
POST /api/v1/historical-backfill
POST /api/v1/historical-backfill/{runId}/resume
GET  /api/v1/historical-backfill/{runId}
```

장시간 작업이어도 숨은 background executor를 만들지 않는다.

현재 요청 thread에서 순차적으로 실행해도 된다.

Checkpoint 때문에 중단되어도 resume 가능해야 한다.

---

# 10. Persistent backfill run

새 migration:

`V19__create_historical_backfill.sql`

권장 table:

`historical_backfill_run`

최소:

```text
id
athlete_id
start_date
end_date
requested_days

status
current_phase
stop_reason

target_activity_count
completed_activity_count

started_at
updated_at
completed_at
```

Status 예:

```text
RUNNING
PAUSED
COMPLETED
FAILED
```

`PAUSED`

는 안전하게 resume 가능한 외부/데이터 상황.

`FAILED`

는 invariant/code failure 등 자동 resume하면 안 되는 상태.

---

# 11. Per-activity persistent checkpoint

같은 migration 또는 별도 migration에:

`historical_backfill_activity`

를 만든다.

최소:

```text
id
run_id
activity_id
garmin_external_id
started_at
ordinal

summary_status
detail_status
sample_completeness
analysis_status
intervals_status

last_error_code
updated_at
```

Unique:

```text
run_id + activity_id
run_id + garmin_external_id
```

실제 ID는 DB에 필요하므로 저장하되 log/result document에는 출력하지 않는다.

---

# 12. Processing phases

Backfill run의 명확한 phase:

```text
GARMIN_DISCOVERY
GARMIN_DETAIL_ANALYSIS
INTERVALS_ACTIVITIES
INTERVALS_FITNESS
GARMIN_RECOVERY
VERIFY
COMPLETED
```

현재 phase를 DB에 저장한다.

중단/서버 재시작 후 해당 phase에서 resume 가능해야 한다.

---

# 13. Discovery semantics

Garmin activity list는 newest → oldest로 paging된다.

기존:

`GarminActivitySource.fetchActivities(start, limit)`

사용 가능.

Backfill page size:

가능하면 `100`.

Discovery는 newest에서 시작해 과거로 내려간다.

90-day start boundary보다 오래된 activity가 포함된 page까지 확인한 뒤 종료한다.

Garmin history end의:

- empty page
- short page

도 정상 종료 조건.

---

# 14. Discovery resume safety

Offset pagination은 새로운 activity가 추가되면 offset이 움직일 수 있다.

따라서 discovery가 중간에 PAUSED 된 경우:

**저장된 offset에서 이어가지 않는다.**

resume 시:

```text
start=0
```

부터 discovery를 다시 수행한다.

왜냐하면:

- summary ingestion은 idempotent
- historical_backfill_activity upsert도 idempotent
- 90-day window는 고정

이기 때문이다.

offset drift로 activity를 누락하는 것보다 안전하다.

Discovery가 COMPLETE 된 후 target snapshot은 freeze한다.

---

# 15. Activity summary ingestion

90-day window 안의 supported activity는 기존:

`GarminActivityIngestionService`

를 이용해 저장한다.

새 summary mapper를 만들지 않는다.

기존 activity가 있으면 update.

신규이면 create.

unsupported activity type:

```text
SKIPPED_UNSUPPORTED
```

로 기록하고 계속 진행.

unsupported type 때문에 임의 mapping을 새로 만들지 않는다.

---

# 16. Incremental high-water isolation test

자동 테스트 필수:

Historical discovery가:

- activity ingest는 수행하지만
- `GarminSyncStateService.advance()`

를 호출하지 않는지 확인한다.

Live run에서도 backfill 전후 `garmin_sync_state` equality 확인.

---

# 17. Discovery activity cap

잘못된 cutoff/pagination으로 무한 수집되지 않도록 safety cap을 둔다.

예:

```text
max-pages
max-activities
```

설정 가능.

90-day request에서 비정상적으로 cap을 넘으면:

`BACKFILL_DISCOVERY_LIMIT_EXCEEDED`

로 PAUSED/FAILED 처리.

cap 때문에 조용히 일부만 완료 처리하지 않는다.

---

# 18. Processing order

Discovery 완료 후 target activities를:

```text
started_at ASC
```

즉 oldest → newest

순서로 처리한다.

이 순서는:

- deterministic
- resume friendly
- historical analysis 확인이 쉬움

을 위한 것이다.

Garmin list endpoint의 newest-first 순서를 그대로 사용하지 않는다.

---

# 19. Detail stage

각 supported activity마다 기존:

`GarminActivityDetailIngestionService`

를 사용한다.

새 duplicate detail ingestion 코드를 만들지 않는다.

---

# 20. Existing complete activity skip

이미 detail data가 있고 다음을 만족하면 불필요한 Garmin 재호출을 피할 수 있다.

최소:

- required detail parts 정상
- sample stream이 있는 경우 `sample_completeness = FULL`
- collection에 FETCH_FAILED / MAPPING_FAILED 없음

이면 detail fetch SKIP 가능.

단 기존:

```text
DOWNSAMPLED
UNKNOWN
```

은 반드시 live refetch 대상.

현재 track session의 기존 downsampled stream도 이번 backfill에서 FULL로 승격되어야 한다.

---

# 21. Detail integrity rule

새 fetch 결과:

`DetailCollectionOutcome.COMPLETE`

가 기본 성공 조건.

정상 `EMPTY` part:

예:

power zones 없음

은 COMPLETE를 깨지 않는다.

하지만:

- FETCH_FAILED
- MAPPING_FAILED
- PARTIAL
- FAILED

가 있으면 해당 activity에서 backfill을 PAUSE한다.

다음 activity로 조용히 넘어가지 않는다.

---

# 22. Sample fidelity gate

Sample part가 NORMALIZED 되었으면:

```text
sample_completeness = FULL
```

이어야 한다.

다음이면 PAUSE:

```text
DOWNSAMPLED
UNKNOWN
```

Stop reason 예:

```text
SAMPLE_STREAM_DOWNSAMPLED
SAMPLE_STREAM_FIDELITY_UNKNOWN
```

`GARMIN_DETAIL_SAMPLES_MAX_CHART_SIZE=20000`이 부족한 초장거리 activity라면 자동으로 100000으로 재시도하지 않는다.

Operator가 설정을 검토한 뒤 resume하도록 한다.

---

# 23. EMPTY sample stream

source가 실제로 sample data를 제공하지 않아 sample part가:

`EMPTY`

인 경우는 데이터 오류로 보지 않는다.

record:

`NO_SAMPLE_STREAM`

등으로 남기고 가능한 lap/zone 분석은 진행 가능.

sample metrics는 null.

없는 data를 만들어내지 않는다.

---

# 24. Garmin call discipline

한 activity detail collection은 현재 5 parts를 순차 fetch한다.

Backfill도 자동 retry 금지.

activity 사이에는 configurable delay를 둔다.

권장 default:

```text
2s
```

예:

```yaml
running-ai:
  historical-backfill:
    activity-delay: 2s
```

첫 activity 전에는 불필요한 delay 없음.

---

# 25. Garmin STOP conditions

Garmin에서:

- 401
- 403
- 429
- connector unavailable

등 account/network level failure가 발생하면 즉시 전체 backfill PAUSE.

다음 activity 요청 금지.

stored checkpoint 유지.

automatic retry 없음.

---

# 26. Analysis stage

detail integrity가 확보된 activity는 바로:

`RunningActivityAnalysisService.analyse(activityId)`

실행.

Network call 없음.

현재 version:

`RUNNING_ANALYSIS_V1`

저장 확인.

분석 실패 시 다음 activity로 넘어가지 말고:

`ANALYSIS_FAILED`

로 PAUSE.

Derived data가 source raw보다 중요하지는 않지만, 6H-6의 목표는 analysis까지 완성하는 것이므로 fail-fast한다.

---

# 27. Existing analysis

이미:

`RUNNING_ANALYSIS_V1`

분석이 존재하고 detail source가 재수집되지 않았다면 재사용 가능.

하지만 detail을 새로 fetch/reprocess한 경우에는 analysis를 다시 계산한다.

stale derived analysis를 남기지 않는다.

---

# 28. Per-activity checkpoint

한 activity가:

```text
summary
→ detail
→ analysis
```

까지 완료될 때마다 DB checkpoint를 commit한다.

서버가 다음 activity에서 꺼져도 이전 activity를 다시 Garmin에서 fetch할 필요가 없어야 한다.

---

# 29. Intervals activity backfill strategy

6H-5에서 live 확인:

```text
GET activity detail
=
activity list item과 동일 shape
```

따라서 historical backfill에서는 activity마다:

`GET /api/v1/activity/{id}`

하지 않는다.

`intervals=true`

도 사용하지 않는다.

Garmin lap/sample이 source of truth다.

---

# 30. Intervals list windowing

90 days를 activity list window로 나눈다.

권장:

최대 31 calendar days per request.

예: 2026-07-06 ~ 2026-10-03이면:

```text
2026-07-06 ~ 2026-08-05
2026-08-06 ~ 2026-09-05
2026-09-06 ~ 2026-10-03
```

즉 activity list:

약 3 GET.

모든 request sequential.

---

# 31. Intervals raw-first batch storage

각 list response의 activity item을:

`intervals_raw_payload`

에 raw-first upsert한다.

Payload type:

`ACTIVITY`

기존 V18 structure 재사용.

개별 activity detail GET 없이도 normalize 가능해야 한다.

---

# 32. Local matching

Intervals list snapshots를 모두 수집한 후 network 없이 각 RunningAI activity를 matching한다.

6H-5 matcher 그대로 사용:

```text
SOURCE_ID
→ EXTERNAL_ID
→ COMPOSITE
```

현재 live Garmin-connected activities에서는:

`external_id = Garmin activity id`

가 확인되었으므로 SOURCE_ID 우선.

---

# 33. Composite matcher 유지

6H-5에서 확정한 rule 유지:

```text
start <= 30s
duration <= 5s
distance <= 5m
```

실측 compatible type pair만.

다른 Garmin id를 명시적으로 가진 Intervals 후보는 composite에서 제외.

0 candidates:

`UNMATCHED`

2+:

`AMBIGUOUS`

자동 선택 금지.

---

# 34. Intervals unmatched/ambiguous policy

Intervals enrichment는 secondary source다.

따라서:

`UNMATCHED`

또는

`AMBIGUOUS`

activity가 있다고 전체 Garmin historical backfill을 중단하지 않는다.

item status에 기록하고 계속.

단:

`activity_source_link` conflict처럼 이미 다른 local activity와 동일 external id가 연결되는 **data-integrity conflict**는 PAUSE.

---

# 35. Intervals activity metrics

Matched activity에는 기존:

`activity_intervals_metrics`

저장.

- training_load
- intensity
- ctl_after_activity
- atl_after_activity
- source_updated_at

등 6H-5 LIVE_VERIFIED mapping 재사용.

새 mapping을 만들지 않는다.

---

# 36. Intervals fitness history

최근 90일 CTL/ATL history도 수집한다.

Wellness payload는 하루 1 row 수준이므로 backfill-specific path에서는 90-day range를 **한 번의 GET**으로 요청하는 것을 우선한다.

만약 실제 API/transport 제한 때문에 실패한다면 자동 분할 retry 금지.

그 경우 PAUSE하고 원인을 기록.

필요하다면 구현 시 처음부터 deterministic 31-day windows로 설계해도 된다.

중요한 것은:

- sequential
- no retry
- exact window
- duplicate-free

이다.

기존 manual enrichment endpoint의 `31-day guard`는 변경하지 않는다.

HistoricalBackfill 전용 path가 별도로 처리한다.

---

# 37. Fitness mapping

기존 6H-5 semantics 유지:

```text
CTL = calculated fitness
ATL = calculated fatigue
derived_form = CTL - ATL
```

`wellness.fatigue`

는 subjective fatigue이므로 절대 ATL로 사용하지 않는다.

missing day는 row를 만들어내지 않는다.

---

# 38. Intervals call budget

90-day backfill에서 목표:

```text
Activity lists ≈ 3 GET
Wellness       ≈ 1 GET
```

즉 정상적으로:

`~4 Intervals GET`

정도.

activity별 detail GET 금지.

실제 call count를 result에 보고한다.

---

# 39. Intervals STOP conditions

- 401
- 403
- 429
- timeout
- connection failure

발생 시 즉시 PAUSE.

자동 retry 없음.

이미 저장된 Garmin/detail/analysis는 그대로 유지.

resume은 Intervals phase부터 가능해야 한다.

---

# 40. Garmin Recovery — 28 days only

Activity 90-day history와 별개로 Recovery는 기존 policy 그대로:

`28 days`

유지.

Default end:

athlete local today.

2026-10-03 기준:

```text
2026-09-06 ~ 2026-10-03
```

28 calendar days inclusive.

---

# 41. Recovery implementation reuse

새 recovery mapper/client를 만들지 않는다.

기존:

`GarminRecoverySyncService`

를 재사용한다.

가능하면 orchestration에서 하루 단위:

`syncDay(date)`

를 호출하면서 checkpoint를 남긴다.

또는 기존 backfill method를 reuse하되 resume semantics를 명확히 보장한다.

---

# 42. Recovery processing order

기존 service semantics와 동일하게:

newest → oldest

유지 가능.

가장 최근 recovery가 우선 저장되도록 한다.

---

# 43. Recovery checkpoint

Historical run에:

`nextRecoveryDate`

또는 equivalent persisted cursor를 둔다.

각 day 성공 후 checkpoint commit.

중간에 Garmin 429 등이 발생하면:

해당 date에서 PAUSE.

resume 시 이미 완료된 날짜를 다시 Garmin에서 요청하지 않는 것을 우선한다.

---

# 44. Recovery delay

기존:

`GARMIN_RECOVERY_BACKFILL_DELAY`

정책과 동일 또는 호환되는 delay 사용.

기본:

`2s`

자동 retry 없음.

---

# 45. Recovery missing metrics

어떤 날에:

- HRV 없음
- sleep 없음
- Body Battery 없음

등이 있어도 정상 source absence일 수 있다.

기존 mapper semantics대로 nullable/unavailable로 저장.

그 날짜를 FAIL로 만들지 않는다.

---

# 46. Backfill safety guard

Historical backfill 시작 시 runtime 설정 확인.

반드시:

```text
WORKOUT_PUBLISHING_ENABLED=false
WORKOUT_PUBLISHING_SCHEDULER_ENABLED=false
RUNNING_AI_DRAFT_PUBLISHING_ENABLED=false
RUNNINGAI_MCP_ENABLED=false
```

추가로 Garmin concurrent automation 방지를 위해:

```text
running-ai.garmin.scheduler.enabled=false
running-ai.garmin.profile-sync.scheduler.enabled=false
```

확인.

하나라도 unsafe하면 start refuse.

---

# 47. Legacy Windows task preflight

Live execution 직전 Claude가 Main PC Scheduled Tasks 확인.

Writer로 알려진:

- RunningAI-TodayWorkout
- RunningAI-TrainingCommand
- RunningAI-CommandChannel
- RunningAI-RemoteWakeupScheduler

모두 Disabled 확인.

삭제하지 않는다.

Startup/Watchdog는 action을 확인하고 불필요하게 disable하지 않는다.

---

# 48. In-JVM single-flight

동시에 historical backfill 두 개 실행 금지.

`tryLock` 또는 equivalent.

동시에 동일 athlete의 RUNNING run이 DB에도 존재하면 새 run 거부.

response:

`HISTORICAL_BACKFILL_ALREADY_RUNNING`

등 명확한 code.

---

# 49. Resume contract

Resume 가능 상태:

`PAUSED`

Resume 시:

- window 변경 금지
- days 변경 금지
- runId 유지
- completed items 재처리 금지

phase별 semantics:

### GARMIN_DISCOVERY
restart discovery from offset 0.

### GARMIN_DETAIL_ANALYSIS
first incomplete activity부터.

### INTERVALS
stored checkpoint/window부터 또는 idempotent window refetch.

### RECOVERY
next incomplete date부터.

---

# 50. Completed run resume

COMPLETED run에 resume 요청 시:

새 network call을 하지 않는다.

409 또는 deterministic no-op response.

무엇을 선택했는지 test/documentation에 고정한다.

---

# 51. No automatic resume

PAUSED run을 scheduler가 자동 resume하지 않는다.

Human/manual trigger만.

특히 429 이후 자동 resume 금지.

---

# 52. Backfill API response

최소 포함:

```text
runId
status
phase

startDate
endDate

targetActivities
completedActivities
skippedUnsupported

detailFull
detailNoSamples
analysisComplete
analysisPartial

intervalsMatched
intervalsUnmatched
intervalsAmbiguous

fitnessDays
recoveryDays

stoppedAt
stopReason
```

실제 external IDs는 response에서 필요 없으면 제외.

---

# 53. Data verification phase

모든 network phase가 끝난 뒤 local-only VERIFY 수행.

각 supported activity 확인:

```text
activity exists
detail exists
no detail failure
sample FULL or legitimate EMPTY
analysis exists
analysis_version = RUNNING_ANALYSIS_V1
```

Intervals:

```text
matched → source_link + metrics
unmatched → explicit status
ambiguous → explicit status
```

Fitness/recovery도 count 확인.

---

# 54. Existing DOWNSAMPLED cleanup

현재 90-day window 내에서:

`DOWNSAMPLED`

sample stream이 있는 activity를 모두 조회.

Backfill 후:

- FULL
- legitimate EMPTY

가 아닌 기존 DOWNSAMPLED가 남아 있으면 run COMPLETED 처리하지 않는다.

특히 Phase 6H-4 보고에서 확인된 track activity:

```text
1366 / 2712 DOWNSAMPLED
```

는 이번 Phase에서 full-resolution으로 다시 수집되어야 한다.

---

# 55. UNKNOWN fidelity

Normalized sample stream인데:

`UNKNOWN`

이면 완료 처리하지 않는다.

PAUSE.

왜 totalMetricsCount가 없는지 확인 후 별도 결정.

"아마 full일 것"이라고 간주 금지.

---

# 56. No FIT ingestion

이번 Phase에서도 FIT archival ingestion은 하지 않는다.

API sample stream이 current capability에 충분하다는 6H-1B 결정을 유지.

---

# 57. No TrainingContext V2

이번 Phase에서는:

- TrainingContextBuilder
- Claude prompt
- workout generation

을 변경하지 않는다.

90-day dataset을 완성하고 실제 분포를 확인한 뒤 6H-7에서 통합한다.

---

# 58. No publish

Backfill 어디에서도:

- Intervals POST
- Intervals PUT
- workout publisher
- Garmin workout push

호출 금지.

External workout writes:

`0`

---

# 59. Test — discovery

최소:

- 90-day boundary
- newest-first paging
- short page
- empty page
- activity older than cutoff
- duplicate activity
- unsupported type
- discovery resume from 0
- max-page guard
- max-activity guard
- garmin_sync_state untouched

---

# 60. Test — detail

- already FULL → skip
- DOWNSAMPLED → collect
- UNKNOWN → collect
- successful FULL
- legitimate EMPTY
- PARTIAL → pause
- mapping failure → pause
- 401/403/429 → stop immediately
- no retry
- activity delay

---

# 61. Test — analysis

- detail fetch changed → reanalyse
- existing fresh analysis + unchanged detail → skip allowed
- analysis failure → pause
- version current
- no network calls

---

# 62. Test — Intervals batching

- 90 days split into deterministic windows
- no overlapping duplicate date
- list GET count bounded
- no activity-detail GET
- no intervals=true GET
- SOURCE_ID match
- composite fallback
- unmatched continue
- ambiguous continue
- link conflict pause
- 401/403/429 stop
- no retry

---

# 63. Test — fitness

- 90-day wellness
- CTL
- ATL
- derived form
- missing day
- null CTL/ATL
- subjective fatigue ignored
- upsert idempotent

---

# 64. Test — recovery

- exactly 28-day window
- newest-first
- missing metric accepted
- checkpoint after each date
- pause on external failure
- resume from incomplete date
- no repeated completed dates where implementation supports it

---

# 65. Test — checkpoint

Simulate crash/failure:

```text
activity 1 COMPLETE
activity 2 DETAIL COMPLETE
activity 2 ANALYSIS failure
```

resume:

- activity 1 no Garmin calls
- activity 2 detail no unnecessary refetch
- analysis retried locally
- then activity 3...

검증.

---

# 66. Test — idempotency

전체 pipeline을 fake source로 두 번 실행.

기대:

- duplicate activity 0
- duplicate raw 0
- duplicate details 0
- duplicate sample 0
- duplicate analysis 0
- duplicate source link 0
- duplicate Intervals metrics 0
- duplicate fitness day 0

---

# 67. Migration validation

V19 또는 필요한 신규 migration.

기존 V1~V18 수정 금지.

검증:

- H2
- PostgreSQL 17 throwaway
- live Main DB

기존 rows 유지.

repair/drop/truncate 금지.

---

# 68. Full regression

Spring baseline:

`1073 passed`

실행:

```text
gradlew clean test
```

기대:

- test count 증가
- failed 0
- skipped unexpected 0

Python connector:

baseline `134 passed`.

Connector를 변경하지 않는 것이 기본.

PostgreSQL 17 full suite도 GREEN 확인.

기존 PG connection-pool test issue는 test JVM에서만 작은 Hikari pool을 사용.

production pool 변경 금지.

---

# 69. Live execution preflight

자동 tests GREEN 후에만 실제 backfill 실행.

확인:

```text
DB backup OK
working tree clean
runtime health UP

Java = 21
Gradle JVM = 21

Garmin token VALID
Intervals key SET

all publish switches false
Garmin schedulers false
legacy writers Disabled
```

---

# 70. Live run call estimate

Discovery 후 target count `N`이 확정되면 실제 detail call 예상치를 계산하여 log/result에 기록.

대략:

```text
Garmin discovery:
  ceil(activity pages)

Garmin detail:
  5 × activities requiring detail refetch

Intervals:
  ~3 activity-list GET
  ~1 wellness GET

Garmin recovery:
  up to 28 connector recovery requests
```

Recovery connector 내부가 여러 Garmin upstream request를 수행할 수 있다는 점도 문서화한다.

예상값을 limit으로 오인하지 않는다.

---

# 71. Live run

실제:

```text
POST /api/v1/historical-backfill
```

body:

```json
{
  "endDate": "2026-10-03",
  "days": 90
}
```

실행.

현재 날짜가 달라졌다면 athlete-local today 기준으로 바꾼다.

---

# 72. Live interruption

실제 run이:

- 401
- 403
- 429
- connection failure
- data fidelity failure

때문에 PAUSED 되면 **그 자리에서 멈춘다.**

설정 변경이나 원인 확인 없이 즉시 resume 금지.

Result를 먼저 보고한다.

---

# 73. Live completion verification

COMPLETED 후 SQL/local API로 검증.

최소:

- target count
- activity count
- detail COMPLETE count
- sample FULL count
- sample EMPTY count
- DOWNSAMPLED 0
- UNKNOWN 0
- analysis V1 count
- Intervals matched/unmatched/ambiguous
- fitness day count
- recovery day count
- duplicate count
- garmin_sync_state unchanged

---

# 74. Data volume report

backfill 전후:

```text
DB size
sample row count
raw payload row count
analysis row count
```

비교.

90-day full-resolution storage가 실제 어느 정도 용량을 차지하는지 기록한다.

이 결과가 향후 Raspberry Pi storage sizing에 사용될 수 있게 한다.

---

# 75. Spot checks

대표 최소 3 activity:

- long run
- interval
- treadmill

확인.

### Long run

- FULL sample
- analysis V1
- Intervals metrics link

### Interval

- FULL sample
- interval repetitions 유지
- analysis recomputed
- source link

### Treadmill

- FULL sample
- GPS null 정상
- analysis complete

실제 개인 수치를 문서에 과도하게 기록하지 않는다.

---

# 76. Recovery spot check

최근 몇 날짜를 확인.

- stored date correct in Asia/Seoul semantics
- available/unavailable metrics preservation
- no future dates

---

# 77. Incremental sync post-check

Backfill 완료 후 기존:

`GarminIncrementalSyncService`

가 정상적으로 계속 동작할 수 있는지 fake/automated test로 확인.

Live incremental sync를 굳이 호출할 필요는 없다.

Backfill이 incremental state를 오염시키지 않았는지가 핵심.

---

# 78. Runtime final state

Phase 종료 후:

```text
WORKOUT_PUBLISHING_ENABLED=false
WORKOUT_PUBLISHING_SCHEDULER_ENABLED=false
RUNNING_AI_DRAFT_PUBLISHING_ENABLED=false
RUNNINGAI_MCP_ENABLED=false
```

Garmin automatic schedulers도 기존 상태 false 유지.

legacy writers Disabled.

---

# 79. Architecture document

작성:

`docs/architecture/historical-backfill.md`

포함:

```text
90-day activity window
28-day recovery window

Discovery
→ Detail
→ Analysis
→ Intervals Activity
→ Intervals Fitness
→ Recovery
→ Verify
```

그리고:

- persistent checkpoint
- resume semantics
- no retry
- rate-limit stop
- incremental sync isolation
- sample fidelity gate
- provenance

를 문서화한다.

---

# 80. Git

논리적 commit 예:

```text
feat: add persistent historical backfill checkpoint
feat: add 90-day Garmin detail and analysis backfill
feat: batch Intervals historical enrichment
test: cover historical backfill resume and fail-stop
docs: document 90-day historical data pipeline
```

Live DB data 자체는 git에 들어가지 않는다.

Full regression GREEN 후 fast-forward push.

force push 금지.

---

# 81. Completion criteria

다음 모두 만족 시 완료:

```text
90-day Garmin activity discovery ✅
persistent checkpoint ✅
resume ✅

Garmin incremental state unchanged ✅

supported activity detail complete ✅
sample streams FULL or legitimate EMPTY ✅
DOWNSAMPLED = 0 ✅
UNKNOWN = 0 ✅

Running Analysis V1 populated ✅

Intervals batch enrichment ✅
SOURCE_ID matching preserved ✅
CTL / ATL history populated ✅

28-day Garmin Recovery populated ✅

automatic retry = 0 ✅
401/403/429 fail-stop ✅

Intervals writes = 0
workout writes = 0

TrainingContext V2 = NOT_RUN

tests GREEN
live verification GREEN
working tree clean
origin/main in sync
```

완료 후:

`PHASE_6H_6_90_DAY_HISTORICAL_BACKFILL_READY`

를 보고하고 멈춘다.

---

# 82. 최종 보고

최종 보고 항목:

1. baseline SHA
2. final SHA
3. migrations
4. backfill run id/status
5. actual 90-day window
6. target activity count
7. newly created activities
8. existing updated activities
9. unsupported skipped
10. discovery pages / Garmin calls
11. detail refetched count
12. detail skipped-as-already-full count
13. detail COMPLETE count
14. sample FULL count
15. legitimate EMPTY sample count
16. DOWNSAMPLED remaining
17. UNKNOWN remaining
18. sample rows before/after
19. analysis RUNNING_ANALYSIS_V1 count
20. analysis COMPLETE/PARTIAL counts
21. interval analyses count
22. Intervals activity-list GET count
23. Intervals matched
24. Intervals unmatched
25. Intervals ambiguous
26. link conflicts
27. CTL/ATL fitness days
28. Intervals total GET count
29. Intervals write count
30. Garmin Recovery window
31. Recovery completed days
32. Recovery unavailable/missing metrics summary
33. Garmin 401/403/429
34. Intervals 401/403/429
35. automatic retries
36. pause/resume actually exercised 여부
37. garmin_sync_state before/after
38. historical checkpoint state
39. DB size before/after
40. raw payload rows before/after
41. duplicate checks
42. long-run spot check
43. interval spot check
44. treadmill spot check
45. publishing switches
46. legacy task states
47. external workout writes
48. TrainingContext V2 = NOT_RUN
49. Spring tests
50. Python tests
51. PostgreSQL tests
52. commits
53. push
54. working tree
55. known limitations
56. exact dataset available for Phase 6H-7
