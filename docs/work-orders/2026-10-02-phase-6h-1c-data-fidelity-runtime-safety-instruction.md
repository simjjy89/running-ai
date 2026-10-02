# RunningAI Phase 6H-1C — Data Fidelity & Runtime Safety Hardening

## 0. 목적

이번 Phase는 Main PC에서 수행한다.

Repository:

`C:\running-ai-github`

Baseline:

`a8c8b67`

현재 상태:

- Phase 6H-1A 완료
- Phase 6H-1B `GARMIN_DETAILED_ACTIVITY_CONTRACT_LIVE_VERIFIED`
- Garmin detailed contract LIVE_VERIFIED
- Spring tests: 921 passed
- Python connector tests: 134 passed
- PostgreSQL subset: 397 passed
- Historical backfill: NOT_RUN
- Intervals enrichment: NOT_RUN
- Analysis Engine: NOT_RUN
- TrainingContext V2: NOT_RUN
- External workout writes: 0

이번 Phase의 목적은 세 가지다.

1. Garmin activity sample을 기본적으로 full-resolution으로 수집한다.
2. 저장된 sample stream이 FULL인지 DOWNSAMPLED인지 DB에서 명시적으로 알 수 있게 한다.
3. Main PC의 runtime write switches 및 개발환경을 현재 RunningAI architecture에 맞게 안전하게 정리한다.

이번 Phase에서는:

- 90일 historical backfill 금지
- Running Analysis Engine 구현 금지
- Intervals enrichment 구현 금지
- TrainingContext V2 구현 금지
- workout 실제 publish 금지

한다.

---

# 1. Work-order 기록

이 지시서를 작업 시작 전에 그대로 저장한다.

`docs/work-orders/2026-10-02-phase-6h-1c-data-fidelity-runtime-safety-instruction.md`

작업 결과:

`docs/work-orders/2026-10-02-phase-6h-1c-data-fidelity-runtime-safety-result.md`

민감정보는 문서나 git에 기록하지 않는다.

특히:

- INTERVALS_API_KEY
- Garmin token
- Garmin credential
- real activity id
- GPS 좌표

를 commit하지 않는다.

---

# 2. Git preflight

확인:

- repository = `C:\running-ai-github`
- branch = main
- working tree clean
- origin/main 확인
- `a8c8b67`가 ancestor인지 확인

가능하면:

`git pull --ff-only origin main`

만 사용한다.

금지:

- `git reset --hard`
- `git clean -fd`
- force push
- destructive rebase

legacy repository:

`C:\running-ai`

는 수정하지 않는다.

---

# 3. 현재 운영 write switch 먼저 차단

Phase 6H-1B 종료 후 Main PC `.env`에서 다음 값이 다시 true로 복원된 사실이 확인됐다.

- `WORKOUT_PUBLISHING_ENABLED=true`
- `RUNNINGAI_MCP_ENABLED=true`

현재 architecture에서는 안전한 운영 기본값이 아니다.

이번 Phase부터 Main PC runtime baseline을 아래로 변경한다.

```text
WORKOUT_PUBLISHING_ENABLED=false
WORKOUT_PUBLISHING_SCHEDULER_ENABLED=false
RUNNING_AI_DRAFT_PUBLISHING_ENABLED=false
RUNNINGAI_MCP_ENABLED=false
```

중요:

이번에는 작업 종료 후 이전 true 값으로 복원하지 않는다.

현재 architecture의 정상 baseline은 **전부 false**다.

`.env`는 repository에 commit하지 않는다.

변경 전 local backup은 허용한다.

예:

`.runtime/backup/...`

단 gitignore 대상인지 확인한다.

---

# 4. Legacy writer 점검

Main PC에서 기존 writer가 별도로 살아 있지 않은지 다시 확인한다.

특히 과거 알려진 task:

- `RunningAI-TodayWorkout`
- `RunningAI-TrainingCommand`
- `RunningAI-CommandChannel`
- `RunningAI-RemoteWakeupScheduler`

를 inventory한다.

삭제하지 않는다.

workout을 실제 생성/수정/publish하는 task라면 disabled 상태를 유지한다.

다음 task는 이름만 보고 끄지 않는다.

- `RunningAI-Startup`
- `RunningAI-Watchdog`

실제 action/command line을 확인하고 판단한다.

이번 Phase의 external workout writes는 반드시:

`0`

이어야 한다.

---

# 5. Sample maxChart policy 변경

현재 production code:

`GarminActivityDetailProperties.samplesMaxChartSize`

기본값은:

`null`

이며 이는 python-garminconnect default `maxchart=2000`을 사용한다.

Phase 6H-1B live measurement:

```text
2783 sec activity

default:
1399 / 2784 samples
→ DOWNSAMPLED

maxChart=20000:
2784 / 2784 samples
→ FULL_SAMPLE
```

따라서 RunningAI 기본 policy를 변경한다.

권장 production configuration:

```yaml
running-ai:
  garmin:
    detail:
      samples-max-chart-size: ${GARMIN_DETAIL_SAMPLES_MAX_CHART_SIZE:20000}
```

즉 default:

`20000`

환경변수로 override 가능해야 한다.

값 validation도 추가한다.

예:

- minimum > 0
- Garmin connector/library가 허용하지 않는 비정상값 fail-fast

구체적인 상한은 근거 없이 새로 만들지 않는다.

현재 library/connector contract상 필요한 validation만 적용한다.

---

# 6. Full-resolution을 "가정"하지 않는다

`maxChart=20000`을 설정했다고 해서 모든 Garmin activity가 FULL이라고 간주하면 안 된다.

Garmin live response에서 다음 필드가 확인됐다.

- `metricsCount`
- `totalMetricsCount`
- `activityDetailMetrics`

따라서 sample completeness는 실제 response 자체로 판정한다.

기본 원칙:

```text
storedSampleCount = 실제 normalize된 sample row 수
sourceMetricsCount = payload.metricsCount
sourceTotalMetricsCount = payload.totalMetricsCount
```

판정:

```text
FULL
    storedSampleCount == sourceTotalMetricsCount
    AND sourceTotalMetricsCount known

DOWNSAMPLED
    storedSampleCount < sourceTotalMetricsCount
    AND sourceTotalMetricsCount known

UNKNOWN
    sourceTotalMetricsCount missing/invalid
```

`metricsCount`도 별도로 보존한다.

가능하면 consistency validation:

```text
payload.metricsCount == activityDetailMetrics.size
```

를 수행한다.

불일치 시 데이터를 조용히 FULL로 표시하지 않는다.

실제 payload shape가 valid하지만 count가 불일치한다면:

`UNKNOWN`

또는 명확한 integrity 상태로 처리한다.

mapper가 값을 임의 보정하지 않는다.

---

# 7. DB Migration V14

현재 Flyway는 V13까지 적용돼 있다.

V1~V13 수정 금지.

새 migration:

`V14__add_activity_sample_collection_metadata.sql`

또는 repository naming convention에 맞는 이름.

권장:

`activity_detail_collection`

에 nullable metadata를 추가한다.

최소 후보:

```text
requested_max_chart_size INTEGER
source_metrics_count INTEGER
source_total_metrics_count INTEGER
stored_item_count INTEGER
sample_completeness VARCHAR(...)
```

단 기존 `item_count`가 normalized row count 역할을 이미 하므로 중복 컬럼을 만들 필요는 없다.

가능하면:

```text
item_count
```

를 `stored sample count`로 그대로 사용하고,

추가 컬럼은:

```text
requested_max_chart_size
source_metrics_count
source_total_metrics_count
sample_completeness
```

로 제한한다.

`sample_completeness` 값:

```text
FULL
DOWNSAMPLED
UNKNOWN
```

이 metadata는 `ACTIVITY_DETAILS_STREAM`에만 의미가 있다.

다른 payload type row에서는 nullable이어야 한다.

DB constraint가 복잡해져 유지보수성이 떨어지면 억지 CHECK constraint를 만들지 않는다.

기존 데이터는 유지한다.

기존 sample collection row는 migration 직후:

`sample_completeness = NULL`

이어도 된다.

기존 row를 근거 없이 FULL/DOWNSAMPLED로 backfill하지 않는다.

---

# 8. Domain model

provider-neutral metadata model을 만든다.

예:

```text
SampleCompleteness
- FULL
- DOWNSAMPLED
- UNKNOWN
```

그리고 `DetailPartRecord` 또는 sample collection 전용 metadata에:

```text
requestedMaxChartSize
sourceMetricsCount
sourceTotalMetricsCount
sampleCompleteness
```

를 추가한다.

Garmin-specific JSON key 이름은 integration layer 안에서 끝내고 domain에서는 의미 기반 이름을 사용한다.

---

# 9. Garmin sample metadata parser

`GarminSampleMapper`가 sample rows만 만드는 책임을 지나치게 확장하지 않는다.

필요하면 별도 parser:

`GarminSampleMetadataMapper`

또는 equivalent helper를 만든다.

입력:

Garmin ACTIVITY_DETAILS_STREAM raw JSON.

출력 예:

```text
requestedMaxChartSize
metricsCount
totalMetricsCount
actualPayloadSampleCount
completeness
```

판정은 deterministic이어야 한다.

누락값을 추정하지 않는다.

---

# 10. Collection persistence

현재:

`activity_detail_collection`

은 part별 마지막 상태를 저장한다.

SAMPLES 정상 normalize 후 다음을 함께 저장한다.

예:

```text
payload_type = ACTIVITY_DETAILS_STREAM
status = NORMALIZED
item_count = 2784
requested_max_chart_size = 20000
source_metrics_count = 2784
source_total_metrics_count = 2784
sample_completeness = FULL
```

downsampled fixture에서는:

```text
item_count = 1399
source_metrics_count = 1399
source_total_metrics_count = 2784
sample_completeness = DOWNSAMPLED
```

source total count를 모를 경우:

```text
sample_completeness = UNKNOWN
```

---

# 11. Reprocess semantics

이 부분이 중요하다.

`POST .../details/reprocess`

는 Garmin을 호출하지 않는다.

그러므로 stored raw payload에 이미:

- `metricsCount`
- `totalMetricsCount`

가 있다면 completeness metadata를 다시 계산할 수 있어야 한다.

단 `requestedMaxChartSize`는 raw payload 자체에 존재하지 않을 수 있다.

그 값이 raw payload로부터 증명되지 않는 기존 데이터라면 추정하지 않는다.

기존 raw의 completeness는:

`FULL/DOWNSAMPLED`

판정 가능하지만,

requested max chart는:

`NULL`

이어도 된다.

새 live fetch부터는 실제 configured request 값 `20000`을 기록한다.

---

# 12. Existing four Main-PC activities 처리

Phase 6H-1B에서 수집한 4 activity 중 긴 outdoor run은 현재 downsampled raw가 저장돼 있다.

V14 적용 및 code 배포 후 **bulk 작업은 하지 않는다.**

대표 activity 중 long outdoor run 한 건만 먼저 다시 collect한다.

목표:

기존:

```text
1399 / 2784
DOWNSAMPLED
```

신규:

```text
2784 / 2784
FULL
```

이 되는지 확인한다.

그 activity에 대해:

1차 collect
→ full-resolution 확인

2차 collect
→ idempotency 확인

그리고:

`reprocess`
→ Garmin call 0
→ completeness 유지

확인한다.

나머지 3건은 필요하면 re-collect 가능하지만 이번 Phase에서는 최소 live call 원칙을 따른다.

---

# 13. No hidden retries

Phase 6H-1A에서 발견하여 수정된:

`Garmin(retry_attempts=0)`

은 유지한다.

이번 Phase에서도:

- automatic retry 금지
- retry loop 금지
- 401/403/429 발생 시 즉시 stop

한다.

429 발생 시 maxChart 값을 낮춰 자동 재시도하지 않는다.

---

# 14. API response

현재 detail collect response에 sample fidelity 정보를 추가하는 것이 유용하면 추가한다.

예:

```json
{
  "payloadType": "ACTIVITY_DETAILS_STREAM",
  "status": "NORMALIZED",
  "itemCount": 2784,
  "sourceTotalMetricsCount": 2784,
  "sampleCompleteness": "FULL"
}
```

기존 client compatibility를 깨지 않는 additive change로 한다.

필수가 아니라면 DB/status API에서만 조회 가능하게 해도 된다.

어느 방식을 선택했는지 결과 문서에 기록한다.

---

# 15. Test cases — Sample fidelity

최소 automated test:

### configuration

- env 미설정 → `samplesMaxChartSize=20000`
- env override 정상 반영

### HTTP source

SAMPLES 요청 시:

```text
?maxChart=20000
```

가 포함됨.

다른 detail part에는 query param이 붙지 않음.

### FULL

```text
metricsCount = 2784
totalMetricsCount = 2784
rows = 2784

→ FULL
```

### DOWNSAMPLED

```text
metricsCount = 1399
totalMetricsCount = 2784
rows = 1399

→ DOWNSAMPLED
```

### UNKNOWN

`totalMetricsCount` 없음:

→ UNKNOWN

### inconsistency

`metricsCount != activityDetailMetrics.size`

인 경우 FULL로 잘못 판정하지 않음.

### reprocess

stored raw only:

- Garmin calls 0
- row count 동일
- completeness deterministic

### idempotency

두 번 collect:

- duplicate samples 0
- duplicate collection row 0
- item count 동일
- metadata 동일

---

# 16. Main PC live sample validation

자동 테스트가 모두 GREEN인 후 long outdoor activity 한 건만 live 검증한다.

Garmin activity id는 local variable/local uncommitted note에서만 관리.

확인:

```text
requested maxChart = 20000
source metrics count = 2784
source total metrics count = 2784
stored sample rows = 2784
sample completeness = FULL
```

수치는 해당 실제 activity 결과를 사용하고 위 값이 다르면 실제 응답을 따른다.

핵심 조건은:

```text
stored == sourceTotal
→ FULL
```

이다.

---

# 17. Historical backfill gate

Phase 6H-1C 성공 전에는 backfill 금지.

Phase 성공 조건에:

`FULL sample policy operational`

을 포함한다.

이번 Phase 종료 시에도:

`Historical backfill = NOT_RUN`

이다.

---

# 18. INTERVALS_API_KEY rotation

Phase 6H-1B 작업 중 실제 `INTERVALS_API_KEY` 값이 terminal/chat transcript에 한번 노출된 사실이 보고됐다.

커밋에는 포함되지 않았지만 credential hygiene 차원에서 기존 key 교체를 권장한다.

중요:

Claude/automation이 새 key를 생성하거나 화면에 출력하려 하지 않는다.

사용자가 Intervals.icu에서 직접 새 API key를 발급/교체한다.

새 key는 Main PC local `.env`에만 저장한다.

값을:

- terminal echo
- diff
- result document
- git
- log

에 출력하지 않는다.

가능하면 secret 존재 여부만 확인:

```text
INTERVALS_API_KEY configured = true
```

실제 값은 출력 금지.

사용자가 이 Phase 중 key rotation을 수행하지 않았다면 실패로 처리하지 말고:

`INTERVALS_API_KEY_ROTATION = USER_ACTION_REQUIRED`

로 결과에 남긴다.

이번 Phase에서는 Intervals API write를 수행하지 않는다.

---

# 19. .env diff 안전

앞으로 `.env`를 확인할 때:

`git diff`
`Get-Content .env`

등으로 secret 전체를 터미널에 출력하지 않는다.

필요한 설정은 값 자체가 아닌 상태만 확인한다.

예:

```text
WORKOUT_PUBLISHING_ENABLED=false
RUNNINGAI_MCP_ENABLED=false
INTERVALS_API_KEY=<SET>
```

API key 값 자체는 redact한다.

필요하면 PowerShell에서 boolean/SET 여부만 출력하는 helper를 사용한다.

---

# 20. JDK 21 Main-PC permanent setup

Phase 6H-1B에서:

`start-running-ai.ps1`

가 JDK 21을 찾지 못해 exit 13이 발생했다.

현재 실제 사용 가능한 JDK 21 installation path를 먼저 탐지한다.

새 JDK를 다운로드/설치하지 않는다.

이미 존재하는 JDK 21을 사용한다.

확인:

```text
java -version
javac -version
JAVA_HOME
```

실제 JDK 21 path가 확인되면 Main PC 사용자 환경의:

`JAVA_HOME`

을 해당 JDK root로 맞춘다.

PATH에서도:

`%JAVA_HOME%\bin`

이 정상적으로 사용되는지 확인한다.

Machine-wide 설정이 꼭 필요하지 않으면 user-scoped setting을 우선한다.

변경 후 새 shell에서:

```text
java -version
javac -version
```

둘 다 Java 21 확인.

그리고 별도 session override 없이:

`scripts/windows/start-running-ai.ps1`

가 성공해야 한다.

---

# 21. Runtime restart validation

환경 정리 후 canonical scripts로:

stop

→ start

→ status

순서 검증.

최종 기대:

```text
PostgreSQL HEALTHY
Garmin connector UP
Spring UP
Actuator UP
```

그리고 반드시:

```text
WORKOUT_PUBLISHING_ENABLED=false
WORKOUT_PUBLISHING_SCHEDULER_ENABLED=false
RUNNING_AI_DRAFT_PUBLISHING_ENABLED=false
RUNNINGAI_MCP_ENABLED=false
```

를 유지한다.

이번 Phase에서 어떤 이유로도 publish switch를 켜지 않는다.

---

# 22. PublishingModeGuard regression

기존 Phase 6G.1 동작을 다시 보장한다.

자동 테스트:

```text
false / false -> starts
true  / false -> starts
false / true  -> starts
true  / true  -> startup fails
```

기존 test가 충분하면 새로 중복 테스트하지 않는다.

단 1C 변경이 guard를 깨지 않았음을 full suite로 검증한다.

---

# 23. PostgreSQL migration

V14가 있다면 실제 PostgreSQL 17에서 검증한다.

가능하면 throwaway DB 사용.

확인:

- V1~V14 migration success
- `activity_detail_collection` 기존 rows 유지
- new columns nullable
- jsonb unchanged
- existing detail data 유지
- H2 migration success

기존 live DB에:

- repair
- drop
- truncate

금지.

---

# 24. Tests

코드 변경 후:

## Spring

baseline:

`921 passed`

실행:

`gradlew clean test`

기대:

- 0 failed
- unexpected skipped 0
- test count >= 921

## Python

Python connector 코드가 변경되지 않으면 full pytest 재실행 여부는 판단 가능하지만, release gate에서는 가능하면 수행한다.

baseline:

`134 passed`

## PostgreSQL subset

baseline:

`397 passed`

필요하면 Phase 6H-1B에서 발견한 connection pool issue를 고려해 test JVM에서:

small Hikari maximum pool size

를 명시한다.

production datasource pool 설정을 test 문제 때문에 변경하지 않는다.

---

# 25. No Analysis Engine yet

이번 Phase에서 다음 metric은 구현하지 않는다.

- HR drift
- pace fade
- cadence drift
- interval repeatability
- HR recovery
- decoupling
- threshold exposure
- training quality classification

그것들은 다음 Phase:

`6H-4 Running Analysis Engine`

범위다.

이번 Phase에서는 데이터 fidelity만 보장한다.

---

# 26. Documentation

업데이트:

`docs/architecture/detailed-activity-v2.md`

추가할 내용:

```text
Sample Fidelity Policy

Default maxChartSize = 20000

Completeness:
FULL
DOWNSAMPLED
UNKNOWN

Garmin response's totalMetricsCount is authoritative
for completeness classification.

RunningAI never assumes maxChartSize=20000 means FULL.
```

그리고:

`docs/architecture/garmin-detailed-activity-contract-static.md`

의 live section에도 정책 변경을 연결한다.

---

# 27. Git

논리적 commit 권장:

```text
feat: preserve Garmin sample fidelity metadata
ops: harden main PC runtime defaults
docs: define full-resolution activity sample policy
```

단 `.env` 변경은 commit하지 않는다.

JDK user environment 변경도 git 대상이 아니다.

tests GREEN 후 safe fast-forward push.

force push 금지.

---

# 28. Completion criteria

다음이 모두 만족되면 Phase 6H-1C 완료다.

```text
GARMIN_DETAIL_SAMPLES_MAX_CHART_SIZE default = 20000

long activity live collection:
sourceTotalMetricsCount == stored samples
sampleCompleteness = FULL

downsampled fixture:
sampleCompleteness = DOWNSAMPLED

missing source total:
sampleCompleteness = UNKNOWN

reprocess:
Garmin calls = 0
completeness preserved/recomputed correctly

runtime switches:
legacy publish = false
legacy scheduler = false
draft publish = false
MCP = false

JDK 21:
new shell에서 정상
start-running-ai.ps1 session override 없이 성공

Historical backfill = NOT_RUN
Intervals enrichment = NOT_RUN
Analysis Engine = NOT_RUN
TrainingContext V2 = NOT_RUN
External workout writes = 0

tests GREEN
working tree clean
origin/main in sync
```

Intervals API key rotation은 user action이므로:

`ROTATED`

또는

`USER_ACTION_REQUIRED`

둘 중 하나로 보고한다.

이 항목 하나만 남았다는 이유로 code Phase를 FAIL 처리하지 않는다.

---

# 29. STOP point

Phase 6H-1C 완료 후 자동으로 다음 작업을 시작하지 않는다.

정확히:

`PHASE_6H_1C_DATA_FIDELITY_READY`

를 보고하고 멈춘다.

그 다음 Phase는 별도 지시:

`Phase 6H-4 — Running Analysis Engine`

이다.

90일 historical backfill은 아직 하지 않는다.

---

# 30. 최종 보고 형식

최종 보고에 포함:

1. baseline SHA
2. final SHA
3. Flyway V14 여부/결과
4. samples maxChart default
5. env override test
6. source metrics count
7. source total metrics count
8. stored sample count
9. sample completeness
10. long-run live validation 결과
11. repeated collection idempotency
12. reprocess Garmin call count
13. downsampled test result
14. UNKNOWN completeness test
15. Main PC publishing switch final state
16. legacy task state
17. MCP state
18. Intervals API key rotation status
19. JAVA_HOME
20. `java -version`
21. canonical runtime start result
22. Spring test result
23. Python test result
24. PostgreSQL test result
25. Garmin live API calls
26. 401/403/429 count
27. Historical backfill = NOT_RUN
28. Analysis Engine = NOT_RUN
29. Intervals enrichment = NOT_RUN
30. External workout writes = 0
31. commit(s)
32. push result
33. working tree state

Secret 값이나 real Garmin activity id는 보고하지 않는다.
