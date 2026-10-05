# RunningAI Phase 6H-9 — Coach Data Freshness Pipeline

## 1. 목적

AI Coach가 Draft를 생성하기 직전에 필요한 최신 데이터를 RunningAI DB에 안전하게 반영한다.

현재 실제 구조:

```text
Garmin activity summary sync       존재
Garmin activity detail collection  개별 수동
Running analysis                   개별 수동
Intervals activity enrichment      개별 수동
Intervals fitness enrichment       범위 수동
Garmin recovery sync               일자 수동

TrainingContext V2                 DB-only
Claude Coach                       DB snapshot 사용
```

따라서 다음과 같은 gap이 존재한다.

```text
Garmin/Intervals에는 최신 데이터 존재
        ↓
RunningAI DB refresh 안 됨
        ↓
TrainingContext V2가 오래된 DB를 읽음
        ↓
Claude가 stale evidence로 훈련 판단
```

Phase 6H-9의 목표:

```text
Coach Data Refresh
        ↓
Local Freshness Validation
        ↓
TrainingContext V2
        ↓
Claude Draft
```

---

# 2. 핵심 아키텍처 원칙

`TrainingContextV2Builder`의 다음 invariant를 절대 깨지 않는다.

```text
TrainingContext V2 build
= DB-only
= Garmin call 0
= Intervals call 0
= analysis recompute 0
```

즉 다음은 금지한다.

```kotlin
TrainingContextV2Builder.build()
    -> Garmin
    -> Intervals
```

외부 refresh는 반드시 별도 service/controller에서 완료한다.

---

# 3. 신규 컴포넌트

권장:

```text
CoachDataRefreshService
CoachDataRefreshController
CoachDataFreshnessEvaluator
```

API:

```text
POST /api/v1/coach/data-refresh
```

예:

```json
{
  "date": "2026-10-05"
}
```

body 생략 시 athlete timezone의 today.

---

# 4. 역할 분리

```text
CoachDataRefreshService
    = 외부 source → RunningAI DB refresh

CoachDataFreshnessEvaluator
    = DB-only readiness 판단

TrainingContextV2Builder
    = DB-only evidence serialization

WorkoutDraftService
    = TrainingContext → Claude
```

각 책임을 섞지 않는다.

---

# 5. Refresh 전체 순서

기본 pipeline:

```text
1. Garmin incremental activity sync

2. Recent activity completion
   ├─ detail collection
   ├─ RunningAI analysis
   └─ Intervals activity enrichment

3. Intervals fitness refresh

4. Garmin recovery refresh

5. Local freshness evaluation

6. Return refresh report
```

외부 workout write는 단 하나도 없다.

---

# 6. Safety switches

이 pipeline은 ingestion/enrichment 전용이다.

다음 switch 상태와 관계없이 workout write를 수행해서는 안 된다.

그러나 Main PC 운영 시 기본 안전 조건은 계속:

```text
RUNNING_AI_DRAFT_PUBLISHING_ENABLED=false
WORKOUT_PUBLISHING_ENABLED=false
WORKOUT_PUBLISHING_SCHEDULER_ENABLED=false
RUNNINGAI_MCP_ENABLED=false
```

Refresh service 자체에는 Intervals write client dependency를 주입하지 않는다.

---

# 7. Garmin summary refresh

기존:

```text
GarminSyncOperationService.runSync()
```

를 재사용한다.

새 Garmin summary sync 로직을 만들지 않는다.

기존:

```text
high-water mark
overlap
idempotent ingestion
single-flight
no retry
```

정책 그대로 사용.

---

# 8. Summary sync failure

Garmin activity summary sync가 실패하면 refresh 전체를:

```text
NOT_READY
```

로 종료한다.

이 상태에서는 새 Coach Draft를 생성하면 안 된다.

이유:

```text
최신 activity 존재 여부 자체를 신뢰할 수 없음
```

---

# 9. Recent activity completion window

기본:

```text
7 calendar days ending on coach date D
```

권장.

상수/config:

```text
running-ai.coach.refresh.recent-activity-days=7
```

범위:

```text
D-6 ... D
```

목적은 historical backfill이 아니라 **최근 코칭 evidence completeness**다.

90-day 전체를 매번 처리하지 않는다.

---

# 10. Activity 대상

recent window 내 RunningAI DB의 supported activities를 조회한다.

Activity row를 Intervals에서 생성하지 않는다.

Garmin summary ingestion이 source truth다.

---

# 11. Garmin detail completion

최근 activity가 detail completeness requirement를 충족하지 않는 경우에만 기존:

```text
GarminActivityDetailIngestionService
```

를 사용한다.

기존 raw-first / completeness 정책을 보존한다.

---

# 12. 불필요한 Garmin detail 재호출 금지

이미:

```text
required detail parts complete
AND sample stream FULL
```

이면 Garmin detail call:

```text
0
```

이어야 한다.

EMPTY stream이 정상 상태인 activity도 기존 semantics를 따른다.

---

# 13. DOWNSAMPLED / UNKNOWN

최근 running activity의 sample stream이:

```text
DOWNSAMPLED
UNKNOWN
```

이면 silently complete로 간주하지 않는다.

refresh result에 명시한다.

예:

```text
RECENT_ACTIVITY_SAMPLE_NOT_FULL
```

---

# 14. Running analysis

RUN / TREADMILL_RUN만 기존:

```text
RunningActivityAnalysisService
```

를 사용한다.

Cycling 등을 Running Analysis에 강제로 넣지 않는다.

---

# 15. Analysis recompute 조건

다음 경우만 분석:

```text
analysis 없음
OR
analysisVersion != RUNNING_ANALYSIS_VERSION
OR
이번 refresh에서 detail이 실제 변경됨
```

그 외:

```text
analysis recompute = 0
```

---

# 16. Indoor cycling

Indoor cycling은 activity summary 및 Intervals enrichment에는 포함할 수 있다.

단 현재 Running Analysis Engine의 running-specific:

```text
pace decoupling
running cadence
running interval extraction
```

등을 cycling에 적용하지 않는다.

Cross Training 분석은 별도 Phase다.

---

# 17. Intervals activity enrichment

최근 supported activity에 기존:

```text
IntervalsEnrichmentService
```

를 사용한다.

Intervals 방향은 **GET only**.

다음 dependency 사용 금지:

```text
IntervalsWorkoutPublisher
IntervalsWorkoutClient write path
```

---

# 18. Activity enrichment policy

최근 activity의 Intervals metrics는 freshness를 위해 refresh 가능하다.

권장:

```text
recent 7-day activities:
  enrich each once per refresh
```

activity 수는 bounded되어 있으므로 허용.

다만 동일 refresh 안에서 중복 call은 금지한다.

---

# 19. Intervals match result

다음은 오류가 아니다.

```text
UNMATCHED
AMBIGUOUS
```

Refresh report에 명시하되 전체 pipeline을 실패시키지 않는다.

예:

```text
intervalsMatched=2
intervalsUnmatched=1
intervalsAmbiguous=0
```

---

# 20. Intervals fitness refresh

Coach load 판단을 최신화한다.

기존:

```text
IntervalsEnrichmentService.enrichFitness()
```

재사용.

권장 refresh 범위:

```text
D-7 ... D
```

90일 전체를 매번 다시 가져오지 않는다.

기존 90-day 데이터는 이미 DB에 존재한다.

---

# 21. Fitness rationale

TrainingContext V2는:

```text
current
7 days ago
28 days ago
```

등을 DB에서 읽는다.

기존 historical rows를 유지하면서 최근 7일만 갱신하면 최신 CTL/ATL/form 변화가 반영된다.

---

# 22. Intervals failure

다음:

```text
401
403
429
timeout
connection failure
```

에서 자동 retry 금지.

Refresh result는:

```text
NOT_READY
```

또는 stage failure를 반환한다.

특히 401은 반복 호출하지 않는다.

---

# 23. Garmin Recovery

기존:

```text
GarminRecoverySyncService
```

재사용.

새 recovery transport를 만들지 않는다.

---

# 24. Recovery refresh 범위

기본적으로:

```text
D
D-1
```

최대 2일만 refresh한다.

이유:

```text
today = partial/live recovery 가능
yesterday = 가장 최근 complete recovery 가능
```

기존 28-day history를 매 Coach generation마다 backfill하지 않는다.

---

# 25. Recovery rate-limit discipline

새 retry 금지.

기존 recovery 호출을 sequential하게 사용한다.

필요하면 기존 configured delay를 재사용한다.

Garmin 429 발생 시 즉시 중단하고 상태에 기록한다.

---

# 26. Missing recovery metrics

Garmin이 정상 응답했지만 특정 metric이 unavailable인 것은 transport failure가 아니다.

기존 semantics:

```text
missing stays null
```

유지.

없는 값을 임의 추정하지 않는다.

---

# 27. Freshness evaluator

모든 network stage 후:

```text
CoachDataFreshnessEvaluator
```

가 **DB만 읽어서** readiness를 판단한다.

network call 0.

---

# 28. Freshness report 모델

권장:

```kotlin
data class CoachDataRefreshResult(
    val date: LocalDate,
    val readyForCoach: Boolean,
    val reasons: List<String>,

    val garmin: GarminRefreshSummary,
    val activities: RecentActivityRefreshSummary,
    val intervals: IntervalsRefreshSummary,
    val recovery: RecoveryRefreshSummary,
    val freshness: CoachDataFreshness,
)
```

---

# 29. Garmin freshness

포함:

```text
lastSuccessfulSyncAt
syncAgeMinutes
fetched
created
updated
failed
checkpointAdvanced
```

credential/raw payload는 포함하지 않는다.

---

# 30. Recent activity freshness

포함:

```text
recentActivityCount
recentRunningActivityCount

detailComplete
detailCollected
detailFailed

analysisCurrent
analysisComputed
analysisMissing

fullSamples
noSampleStreams
sampleIncomplete

intervalsMatched
intervalsUnmatched
intervalsAmbiguous
```

---

# 31. Fitness freshness

포함:

```text
latestFitnessDate
fitnessAgeDays
daysFetched
daysStored
failedDays
```

---

# 32. Recovery freshness

포함:

```text
latestRecoveryDate
recoveryAgeDays
daysAttempted
daysUpdated
latestAvailableMetrics
```

실제 recovery health values는 refresh operational response에 반복 노출하지 않아도 된다.

---

# 33. readyForCoach hard conditions

최소 다음은 hard blocker로 둔다.

```text
Garmin summary sync failed
recent running activity detail collection failed
recent running activity analysis missing/outdated after refresh
Intervals fitness request failed
Garmin recovery request transport/auth failure
```

이 경우:

```text
readyForCoach=false
```

---

# 34. Non-blocking conditions

다음은 warning이지 hard failure가 아니다.

```text
Intervals activity UNMATCHED
Intervals activity AMBIGUOUS
Recovery metric 일부 unavailable
최근 7일 activity 자체가 없음
오늘 fitness row 없음 but latest acceptable row exists
오늘 recovery row 일부 null
```

---

# 35. Fitness age policy

권장:

```text
fitnessAgeDays <= 2
```

이면 usable.

초기 값은 config:

```text
running-ai.coach.refresh.max-fitness-age=2d
```

형태로 둘 수 있다.

테스트 가능한 duration/date policy로 구현한다.

---

# 36. Recovery age policy

권장:

```text
recoveryAgeDays <= 2
```

단 metric availability와 source age를 구분한다.

Recovery가 2일 이내이지만 sleep만 없는 경우:

```text
ready + warning
```

가능.

---

# 37. Activity absence ≠ stale

아주 중요하다.

```text
last run = 5 days ago
```

그 자체는 stale data가 아니다.

Garmin incremental sync가 방금 성공했다면:

```text
실제로 5일간 러닝이 없었다
```

는 신뢰 가능한 evidence다.

즉 freshness evaluator는:

```text
lastActivityDate
```

가 아니라:

```text
lastSuccessfulGarminSyncAt
```

을 source freshness 판단에 사용한다.

---

# 38. TrainingContext V2 freshness metadata

TrainingContext V2에 additive section 추가를 권장한다.

예:

```json
"sourceFreshness": {
  "garminLastSuccessfulSyncAt": "...",
  "garminSyncAgeMinutes": 1,
  "newestActivityDate": "2026-10-05",
  "fitnessSourceDate": "2026-10-05",
  "fitnessAgeDays": 0,
  "recoverySourceDate": "2026-10-05",
  "recoveryAgeDays": 0
}
```

이 값들은 DB-only로 계산한다.

---

# 39. Claude prompt

Coach에게 다음 distinction을 명확히 전달한다.

```text
"no recent activity"
!=
"activity data may be stale"
```

예:

```text
Garmin source was refreshed 2 minutes ago.
No running activity exists in the last 5 days.
```

이면 실제 rest streak로 해석.

반면:

```text
Garmin source freshness unknown / stale
```

이면 강한 결론 금지.

---

# 40. No hallucinated freshness

Claude에게:

```text
Do not call data "recent" solely because the metric value exists.
Use sourceFreshness/sourceDate/ageDays.
```

규칙 추가.

---

# 41. Coach CLI integration

현재:

```text
running-ai-coach.ps1
```

신규 Draft 생성 경로를:

```text
health
→ coach data refresh
→ freshness report
→ generate
```

로 변경한다.

---

# 42. Resume path

다음:

```powershell
running-ai-coach.ps1 -DraftId 17
```

에서는 refresh를 하지 않는다.

이미 생성된 Draft는 immutable context snapshot을 가지고 있기 때문이다.

즉:

```text
generate → refresh
resume   → no refresh
revision → no implicit external refresh
```

---

# 43. Revision semantics

Revision은 기존 Draft와 immutable evidence를 유지한다.

Revision 전에 새 source data를 몰래 가져와 context를 바꾸지 않는다.

최신 data로 다시 판단하고 싶다면:

```text
새 Draft 생성
```

을 사용한다.

---

# 44. CLI freshness display

Generate 전에 예:

```text
--- Coach data refresh ---

Garmin summary        : OK (1 min ago)
Recent activities     : 2
Detail complete       : 2/2
Running analysis      : 2/2 current

Intervals activities  : 2 matched
Intervals fitness     : latest 2026-10-05 (0d old)

Garmin recovery       : latest 2026-10-05 (0d old)

Coach readiness       : READY
```

---

# 45. NOT_READY behavior

예:

```text
Coach readiness : NOT READY

Reasons:
- GARMIN_RECOVERY_SYNC_AUTH_FAILED
```

이면:

```text
Claude calls = 0
Draft rows = 0
approve calls = 0
publish calls = 0
```

이어야 한다.

---

# 46. No silent fallback

Refresh failed 후 기존 stale DB로 자동 generate 금지.

즉:

```text
refresh failure
→ "그냥 기존 데이터로 생성"
```

을 자동으로 하지 않는다.

---

# 47. Explicit debug bypass

필요하면 개발용으로만:

```text
-SkipRefresh
```

를 지원할 수 있다.

단 사용할 경우:

```text
WARNING: generating from stored data without freshness refresh
```

를 크게 출력.

`-SkipRefresh`는 approve/publish gate와 무관하며 어떤 human gate도 우회하지 않는다.

가능하면 초기 Phase에서는 아예 생략해도 된다.

---

# 48. Scheduler

신규 optional scheduler를 만들 수 있다.

권장 config:

```text
running-ai.coach.refresh.scheduler.enabled=false
```

default OFF.

---

# 49. Scheduler service reuse

scheduler가 존재한다면 반드시 동일:

```text
CoachDataRefreshService
```

를 호출한다.

별도 sync logic 복제 금지.

---

# 50. 초기 cadence

활성화할 경우 권장:

```text
every 1 hour
```

또는:

```text
06:00
12:00
18:00
```

하지만 Phase 6H-9 live validation 전에는 OFF 유지.

---

# 51. Scheduler failure

자동 retry 금지.

한 tick 실패:

```text
log
→ stop
→ next scheduled tick
```

기존 Garmin scheduler 철학과 동일.

---

# 52. Concurrent refresh

Coach refresh 전체에 single-flight 필요.

동시에 두 요청이 오면:

```text
COACH_DATA_REFRESH_ALREADY_RUNNING
HTTP 409
```

즉시 반환.

queue하지 않는다.

---

# 53. Existing sub-service locks

하위 Garmin/recovery/enrichment single-flight semantics를 그대로 존중한다.

deadlock을 만들지 않는다.

---

# 54. Historical backfill과 관계

Coach refresh는 historical backfill 대체가 아니다.

```text
Historical Backfill
= 최대 90일 재구축 / completeness verification

Coach Data Refresh
= 최근 상태 유지
```

역할을 문서화한다.

---

# 55. No DB migration unless needed

가능하면 기존 timestamps/state로 freshness를 판단한다.

새 refresh history table은 이번 Phase 필수사항이 아니다.

필요성이 명확하지 않으면 migration을 추가하지 않는다.

---

# 56. Test — TrainingContext purity

기존 V2 test를 강화한다.

`TrainingContextV2Builder.build()` 호출 시:

```text
Garmin calls    0
Intervals calls 0
```

계속 보장.

---

# 57. Test — refresh order

fake services로:

```text
Garmin summary
→ detail
→ analysis
→ intervals activity
→ intervals fitness
→ recovery
→ evaluate
```

순서를 검증한다.

---

# 58. Test — idempotent second refresh

동일 DB 상태에서 두 번째 refresh:

```text
Garmin detail collection 0
analysis recompute       0
```

이어야 한다.

summary/fitness/recovery의 의도된 refresh call은 허용.

---

# 59. Test — new running activity

신규 activity가 summary sync에서 유입됐다고 가정.

다음까지 완료:

```text
summary
detail FULL
analysis RUNNING_ANALYSIS_V1
Intervals match/enrichment
```

그리고:

```text
readyForCoach=true
```

---

# 60. Test — cycling

최근 cycling activity 존재.

기대:

```text
summary             ✅
detail/enrichment    policy대로
Running analysis    0
coach readiness      정상
```

running analysis 강제 호출 금지.

---

# 61. Test — Garmin failure

summary sync가 401/429/connection failure.

기대:

```text
readyForCoach=false
Claude calls=0
```

retry 0.

---

# 62. Test — detail failure

최근 running activity detail 실패.

기대:

```text
readyForCoach=false
analysis not fabricated
Claude calls=0
```

---

# 63. Test — Intervals activity unmatched

기대:

```text
warning
readyForCoach may remain true
```

---

# 64. Test — Intervals fitness failure

401 / 429 / timeout:

```text
readyForCoach=false
Claude calls=0
```

---

# 65. Test — Recovery partial

Garmin request 성공:

```text
HRV available
sleep unavailable
stress available
```

이면:

```text
readyForCoach=true
warning present
```

---

# 66. Test — Recovery transport failure

```text
readyForCoach=false
Claude calls=0
```

---

# 67. Test — real rest streak

Garmin sync:

```text
SUCCESS just now
```

최근 activity:

```text
none for 5 days
```

TrainingContext는:

```text
consecutiveRestDays=5
source freshness=recent
```

로 표현.

이를 stale로 오판하지 않는다.

---

# 68. Test — stale DB without refresh

Freshness evaluator만 직접 호출하여:

```text
last Garmin successful sync too old
```

이면:

```text
readyForCoach=false
```

를 검증.

---

# 69. Test — CLI generate

fake RunningAI server:

```text
health
→ data-refresh READY
→ POST workout-drafts
```

정확한 순서.

---

# 70. Test — CLI refresh NOT_READY

```text
health
→ data-refresh NOT_READY
```

이후:

```text
POST workout-drafts = 0
```

---

# 71. Test — CLI resume

```text
-DraftId
```

사용 시:

```text
data-refresh calls = 0
GET draft
```

---

# 72. External write safety

자동 테스트 전체에서:

```text
Intervals workout POST/PUT = 0
Garmin workout write       = 0
Draft publish              = 0
```

---

# 73. Regression baseline

현재 baseline:

```text
PowerShell    96/96
H2            1192/1192
PostgreSQL    1192/1192
Python        134/134
```

신규 테스트 추가 후 기존 전체 GREEN.

---

# 74. Live validation — Step 1

모든 publishing switch false 확인.

```text
RUNNING_AI_DRAFT_PUBLISHING_ENABLED=false
WORKOUT_PUBLISHING_ENABLED=false
WORKOUT_PUBLISHING_SCHEDULER_ENABLED=false
RUNNINGAI_MCP_ENABLED=false
```

---

# 75. Live validation — Step 2

실제 Main PC에서 refresh only:

```http
POST /api/v1/coach/data-refresh
```

date:

```text
2026-10-05
```

---

# 76. Live validation expected

보고:

```text
Garmin sync SUCCESS
recent activity completeness
Intervals fitness latest date
Recovery latest date
readyForCoach
```

credential/raw payload 출력 금지.

---

# 77. Live validation — external writes

refresh-only live run:

```text
Intervals reads > 0 가능
Garmin reads > 0 가능

Intervals workout writes = 0
Garmin workout writes = 0
```

---

# 78. Live validation — Coach

refresh result가 READY일 때만 신규 synthetic Draft 생성.

한글 Goal 사용.

Draft는:

```text
DRAFT
```

상태에 그대로 둔다.

approve/publish 하지 않는다.

---

# 79. Freshness evidence check

생성된 Draft/TrainingContext가:

```text
fresh Garmin sync
fresh fitness source date
fresh recovery source date
```

를 정확히 반영하는지 확인.

특히 Claude가 오래된 source를:

```text
"very recent"
```

이라고 잘못 표현하지 않는지 확인.

---

# 80. Existing Draft immutability

기존 Draft #17의:

```text
context snapshot
hash
assessment
segments
```

는 절대 변경하지 않는다.

---

# 81. Docs

신규:

```text
docs/architecture/coach-data-freshness.md
```

필수 diagram:

```text
Garmin summaries ─┐
Garmin details ───┤
Running analysis ─┤
Intervals metrics ├─> RunningAI DB
Intervals fitness ┤
Garmin recovery ──┘
                         ↓
                Freshness Evaluator
                         ↓
                  TrainingContext V2
                         ↓
                     Claude
```

---

# 82. Work-order files

Instruction:

```text
docs/work-orders/
2026-10-05-phase-6h-9-coach-data-freshness-instruction.md
```

Result:

```text
docs/work-orders/
2026-10-05-phase-6h-9-coach-data-freshness-result.md
```

---

# 83. 6H-8 final result update

6H-9 시작 전 또는 첫 docs commit에서 6H-8 결과 문서에도 최종 실제 성공을 기록한다.

반드시 포함:

```text
Draft #17
approvalId=3
publishable=true
structuredStepCount=3
PUBLISHED
intervalsOperation=UPDATED
verified=true
safe-mode restart=OK
```

API key 문자열은 절대 기록하지 않는다.

---

# 84. Definition of Done

```text
[ ] CoachDataRefreshService
[ ] CoachDataRefreshController
[ ] CoachDataFreshnessEvaluator

[ ] Garmin incremental sync reused
[ ] recent detail completeness
[ ] current running analysis
[ ] recent Intervals activity enrichment
[ ] recent Intervals fitness refresh
[ ] D/D-1 recovery refresh

[ ] no retries
[ ] single-flight
[ ] bounded calls

[ ] TrainingContextV2 remains DB-only
[ ] source freshness metadata added
[ ] no-activity vs stale-source distinction

[ ] CLI generate refresh-before-generate
[ ] NOT_READY prevents Claude call
[ ] resume does not refresh
[ ] revisions do not silently change evidence

[ ] cycling never forced into running analysis

[ ] Intervals workout writes 0
[ ] Garmin workout writes 0

[ ] H2 GREEN
[ ] PostgreSQL GREEN
[ ] PowerShell GREEN
[ ] Python GREEN

[ ] live refresh-only PASS
[ ] live fresh-context Draft PASS
[ ] Draft remains DRAFT

[ ] docs
[ ] result report
[ ] commits
[ ] push
```

완료 marker:

```text
PHASE_6H_9_COACH_DATA_FRESHNESS_READY
```

여기서 멈춘다.

실제 workout approve/publish는 Phase 6H-9 검증에 포함하지 않는다.
