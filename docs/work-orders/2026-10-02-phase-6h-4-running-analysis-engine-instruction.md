# RunningAI Phase 6H-4 — Detailed Running Analysis Engine + Java 21 Runtime Normalization

## 0. 작업 환경

이번 작업은 **Main PC**에서 수행한다.

Repository:

`C:\running-ai-github`

Baseline:

`b3862f1`

현재 기준:

- Phase 6H-1A Detailed Activity Foundation ✅
- Phase 6H-1B Garmin Detailed Contract LIVE_VERIFIED ✅
- Phase 6H-1C Data Fidelity & Runtime Safety ✅
- Garmin sample default `maxChart=20000`
- Sample completeness `FULL / DOWNSAMPLED / UNKNOWN`
- Historical backfill NOT_RUN
- Intervals enrichment NOT_RUN
- TrainingContext V2 NOT_RUN
- External workout writes 0

Test baseline:

- Spring: 947 passed
- Python: 134 passed
- PostgreSQL subset: 423 passed

Main PC runtime baseline:

- `WORKOUT_PUBLISHING_ENABLED=false`
- `WORKOUT_PUBLISHING_SCHEDULER_ENABLED=false`
- `RUNNING_AI_DRAFT_PUBLISHING_ENABLED=false`
- `RUNNINGAI_MCP_ENABLED=false`

현재 Java 상태:

- RunningAI Gradle/start script는 JDK 21 사용 가능
- user-scoped `JAVA_HOME`은 기존 JDK 21을 가리킴
- 그러나 새 shell에서 bare `java -version`은 Java 17을 가리킴
- Phase 6H-1C 조사 결과 machine PATH의 Oracle `javapath` shim 및 stale machine Java 설정이 우선되는 것으로 보임

이번 Phase는 두 가지를 수행한다.

1. Main PC Java runtime을 안전하게 Java 21로 정규화한다.
2. 저장된 상세 Garmin activity를 분석하는 순수 계산 계층을 구현한다.

이번 Phase에서는:

- 90일 historical backfill 금지
- Intervals enrichment 금지
- TrainingContext V2 변경 금지
- workout 실제 publish 금지

한다.

---

# 1. 작업 기록

지시서 저장:

`docs/work-orders/2026-10-02-phase-6h-4-running-analysis-engine-instruction.md`

결과 저장:

`docs/work-orders/2026-10-02-phase-6h-4-running-analysis-engine-result.md`

실제 activity id, GPS, Garmin token, API key 등 개인정보/secret은 commit하지 않는다.

---

# 2. Git preflight

확인:

- repository = `C:\running-ai-github`
- branch = main
- working tree clean
- origin/main 확인
- `b3862f1` ancestor 확인

필요 시:

`git pull --ff-only origin main`

만 사용.

금지:

- reset --hard
- clean -fd
- force push
- destructive rebase

---

# 3. Java 21 환경 진단

Analysis Engine 구현 전에 Java 환경부터 정리한다.

먼저 현재 상태를 **변경 없이** 조사한다.

PowerShell 새 세션 기준으로 최소 확인:

- `java -version`
- `javac -version`
- `$env:JAVA_HOME`
- `Get-Command java`
- `Get-Command javac`
- `where.exe java`
- `where.exe javac`

그리고 User / Machine scope 각각의:

- `JAVA_HOME`
- `Path`

를 별도로 확인한다.

단 secret이 없는 환경변수만 다룬다.

`.env` 전체를 출력하지 않는다.

확인해야 할 후보:

- Oracle `javapath`
- Oracle `javapath_target_*`
- stale Java 17 path
- stale machine `JAVA_HOME`
- 현재 실제 JDK 21 installation path

현재 설치되어 있는 JDK 21을 재사용한다.

새 JDK 다운로드/설치는 하지 않는다.

---

# 4. Java 21 정상화 목표

최종 목표는 **새 PowerShell session**에서 별도 session override 없이:

```text
java -version
→ 21.x

javac -version
→ 21.x

JAVA_HOME
→ 실제 JDK 21 root
```

가 되는 것이다.

그리고:

`scripts/windows/start-running-ai.ps1`

가 임시 `$env:JAVA_HOME=...` 같은 우회 없이 성공해야 한다.

Gradle 역시 Java 21을 사용해야 한다.

---

# 5. Java 환경 변경 안전 규칙

환경변수를 변경하기 전에 현재 값을 백업한다.

예:

`.runtime/java-env-backup-6h4/`

아래에 sanitized 또는 raw local-only backup 저장.

`.runtime`가 gitignore 대상인지 먼저 확인한다.

백업 대상:

- User JAVA_HOME
- Machine JAVA_HOME
- User PATH
- Machine PATH

repository에는 commit하지 않는다.

### 변경 원칙

가장 작은 변경을 사용한다.

우선순위:

1. existing JDK 21 path 정상 확인
2. stale JAVA_HOME 수정
3. PATH 내 Java 17 / Oracle shim precedence 수정

Machine PATH 전체를 재작성하지 않는다.

관련 Java entry만 정확히 수정한다.

다른 프로그램 PATH entry를 건드리지 않는다.

---

# 6. Oracle javapath 처리

`where.exe java` 결과에서 Oracle `javapath`가 Java 17을 가리키고 있고 이것이 JDK 21보다 먼저 선택되는 것이 확인되면 정리한다.

단 추측으로 삭제하지 않는다.

먼저 해당 shim이 실제로 어느 binary를 가리키는지 확인한다.

예:

- `java.exe`
- `javaw.exe`
- `javac.exe` 존재 여부
- link/junction/symlink target

그리고 현재 Java 17을 가리키는 stale shim임이 확인될 때만:

- 해당 PATH entry를 제거하거나
- JDK 21보다 뒤로 재배치

한다.

### Machine-wide 변경

bare `java`를 21로 만들기 위해 Machine PATH 수정이 꼭 필요할 경우:

- 변경 전 정확한 Machine PATH backup
- Java 관련 entry만 최소 수정
- unrelated entry 변경 금지
- 기존 값을 결과 문서에 secret 없는 범위에서 기록
- rollback 방법 기록

권한 부족이면 우회하거나 권한 상승을 강제하지 않는다.

그 경우:

`JAVA21_MACHINE_PATH_USER_ACTION_REQUIRED`

로 보고한다.

---

# 7. stale Machine JAVA_HOME

Machine scope `JAVA_HOME`이 존재하고 Java 17 또는 존재하지 않는 경로를 가리키는 경우 조사한다.

User `JAVA_HOME`은 JDK 21인데 Machine value가 stale한 상태라면 전체 환경에서 혼동을 유발할 수 있다.

안전하게 수정 가능하면 JDK 21 root로 정규화한다.

단:

- 실제 존재하는 JDK 21 root인지 확인
- JRE directory가 아닌 JDK root인지 확인
- `bin\java.exe`
- `bin\javac.exe`

둘 다 존재해야 한다.

확인되지 않으면 변경하지 않는다.

---

# 8. Java 환경 변경 후 검증

환경변수 변경 후 **기존 shell 값으로 판단하지 않는다.**

새 PowerShell process를 열어 검증한다.

최소:

```text
java -version
javac -version
$env:JAVA_HOME
where.exe java
where.exe javac
```

기대:

- java = 21
- javac = 21
- JAVA_HOME = JDK 21
- 첫 번째 resolved java가 JDK 21 계열

그리고 repository에서:

```text
.\gradlew -version
```

확인:

Gradle JVM = Java 21.

---

# 9. Canonical runtime Java 검증

환경 정리 후:

`scripts/windows/stop-running-ai.ps1`

→

`scripts/windows/start-running-ai.ps1`

→

`scripts/windows/status-running-ai.ps1`

순서로 확인한다.

임시 session override 금지.

기대:

- PostgreSQL HEALTHY
- Garmin connector UP
- Spring UP
- actuator UP

`RunningAI-Startup` Scheduled Task도 임시 Java override에 의존하는지 확인한다.

가능하면 canonical script 자체가 정상 환경을 사용하도록 하고, Scheduled Task에 임시 JDK path hack을 새로 넣지 않는다.

---

# 10. Java 정리 실패가 Analysis Engine을 막는 조건

다음 중 하나면 Analysis Engine 구현 전에 STOP:

- 실제 JDK 21 설치를 찾을 수 없음
- Gradle JVM이 21이 아님
- canonical start script가 Java version 때문에 실패

반면 다음 상태라면 Analysis Engine 진행 가능하되 결과에 limitation 기록:

- RunningAI/Gradle은 확실히 JDK21 사용
- bare `java`만 machine PATH 권한 문제로 17
- machine-wide 변경에 사용자/관리자 조치 필요

그 경우:

`JAVA21_MACHINE_PATH_USER_ACTION_REQUIRED`

를 명확히 남긴다.

---

# 11. Phase 6H-4 Analysis Engine 목적

현재 데이터:

```text
activity
activity_detail
activity_lap
activity_zone
activity_sample
activity_detail_collection
```

를 이용해:

```text
Detailed Activity
        ↓
Running Analysis Engine
        ↓
objective evidence
        ↓
activity_analysis
        ↓
향후 TrainingContext V2
        ↓
Claude AI Coach
```

구조를 만든다.

중요한 철학:

**RunningAI Analysis Engine은 코칭 판단을 하지 않는다.**

허용:

```text
HR second-half change = +7.2%
Speed second-half change = -2.1%
Interval speed CV = 1.4%
Final repetition speed change = -2.8%
Recovery HR drop = 19 bpm
```

금지:

```text
심폐 지구력이 나쁘다
오늘 훈련 실패
회복이 부족하다
과훈련이다
```

즉 facts / derived evidence only.

---

# 12. Lap workout fields first-class 승격

현재 `activity_lap.extra_metrics`에 있는 live Garmin fields:

- `intensityType`
- `wktIndex`
- `wktStepIndex`

를 first-class column으로 승격한다.

새 migration:

`V15__add_lap_workout_structure.sql`

권장 columns:

```text
intensity_type
workout_index
workout_step_index
```

모두 nullable.

`extra_metrics` raw preservation은 유지 가능.

`LapData`에도 의미 기반 필드로 노출한다.

unknown `intensityType`이 생겨도 ingestion 전체를 깨는 rigid enum은 피한다.

문자열 또는 forward-compatible 구조를 사용한다.

---

# 13. 기존 activity split reprocess

V15 적용 후 기존 stored SPLITS raw에서 first-class lap fields를 다시 채운다.

`/details/reprocess` 사용 가능.

조건:

- Garmin API call = 0
- raw payload 수정 없음
- 기존 lap count 동일
- first-class fields populate
- duplicate 0

---

# 14. Analysis schema

새 table:

`activity_analysis`

Flyway:

`V16__create_activity_analysis.sql`

최소:

```text
id
activity_id UNIQUE
analysis_version
analysis_status
input_sample_completeness
computed_at
```

`analysis_status`:

- COMPLETE
- PARTIAL
- INSUFFICIENT_DATA

이는 운동 평가가 아니라 계산 가능성 상태다.

---

# 15. Session-level metrics

최소 지원:

### Basic

```text
valid_sample_count
analysis_duration_seconds
analysis_distance_meters
```

### Heart rate halves

```text
first_half_avg_hr
second_half_avg_hr
hr_change_bpm
hr_change_percent
```

공식:

```text
hr_change_bpm =
secondHalfAvgHr - firstHalfAvgHr

hr_change_percent =
(secondHalfAvgHr - firstHalfAvgHr)
 / firstHalfAvgHr * 100
```

first half HR가 null/0이면 percent null.

---

# 16. Speed change

pace보다 speed를 원본 metric으로 사용한다.

저장:

```text
first_half_avg_speed
second_half_avg_speed
speed_change_percent
```

공식:

```text
(secondHalf - firstHalf)
 / firstHalf * 100
```

pace presentation은 추후 변환한다.

---

# 17. Speed-HR efficiency / decoupling

RunningAI derived metric:

```text
first_half_speed_hr_ratio
second_half_speed_hr_ratio
speed_hr_decoupling_percent
```

공식:

```text
EF1 = firstHalfAverageSpeed / firstHalfAverageHR
EF2 = secondHalfAverageSpeed / secondHalfAverageHR

decouplingPercent =
(EF1 - EF2) / EF1 * 100
```

이를 Garmin 또는 Intervals 공식 metric으로 명명하지 않는다.

good/bad threshold 없음.

---

# 18. Half split

sample count 절반으로 나누지 않는다.

우선:

`elapsedSeconds`

기준 activity midpoint.

fallback:

sample timestamps.

둘 다 신뢰할 수 없으면 half metrics = null.

index-half fallback 금지.

---

# 19. Valid sample policy

HR:

```text
heartRate != null
heartRate > 0
```

Speed:

```text
speed != null
speed >= 0
```

Cadence:

```text
cadence != null
cadence >= 0
```

근거 없는 speed threshold/정지 제거 heuristics를 추가하지 않는다.

---

# 20. Cadence analysis

RUN / TREADMILL_RUN 대상:

```text
first_half_avg_cadence
second_half_avg_cadence
cadence_change_spm
cadence_change_percent
```

없는 데이터는 null.

cycling에 running cadence 공식을 억지 적용하지 않는다.

---

# 21. HR-zone exposure

normalized `activity_zone` 사용.

저장:

```text
hr_zone1_seconds
hr_zone2_seconds
hr_zone3_seconds
hr_zone4_seconds
hr_zone5_seconds
hr_zone_total_seconds
```

비율:

```text
hr_zone1_percent
...
hr_zone5_percent
```

분모:

`sum(zone duration)`.

activity total duration을 강제 분모로 쓰지 않는다.

---

# 22. LTHR-relative exposure

Athlete intensity profile의 LTHR 사용.

LTHR가 존재할 때:

```text
lthr90_seconds
lthr95_seconds
lthr100_seconds
```

를 계산한다.

sample 개수를 seconds로 간주하지 않는다.

timestamp 또는 elapsed delta 기반으로 시간을 적분한다.

마지막 sample 뒤에 임의 1초 추가 금지.

LTHR null이면 metric null.

---

# 23. Lap metrics

최소:

```text
lap_count
lap_speed_mean
lap_speed_stddev
lap_speed_cv_percent
lap_hr_mean
lap_hr_progression
lap_cadence_mean
```

단 warmup/recovery/cooldown을 ACTIVE와 섞어서 interval repeatability로 사용하지 않는다.

---

# 24. Interval structure extraction

Phase 6H-1B live result:

**Garmin lap != structured workout step**

사용 evidence:

- intensityType
- workoutStepIndex
- lap sequence

기본 block:

**연속된 동일 `intensityType + workoutStepIndex` 조합**을 하나의 block으로 묶는다.

동일 step index가 비연속적으로 반복되면 별도 occurrence.

---

# 25. Interval repetition group

자동 interval group 조건:

- `ACTIVE`
- 동일 `workoutStepIndex`
- occurrence >= 2
- ACTIVE occurrence 사이에 RECOVERY block 존재

조건 미충족이면:

interval repetitions = not identified.

speed pattern만 보고 interval을 추론하지 않는다.

---

# 26. Repetition data

각 work occurrence:

```text
sequence
duration_seconds
distance_meters
average_speed
average_hr
max_hr
average_cadence
average_power
```

별도 table 권장:

`activity_analysis_interval`

예:

```text
activity_analysis_id
group_index
repetition_index
workout_step_index
duration_seconds
distance_meters
average_speed
average_hr
max_hr
average_cadence
average_power
```

---

# 27. Interval repeatability

group별:

```text
work_rep_count
mean_speed
speed_stddev
speed_cv_percent
first_rep_speed
last_rep_speed
last_vs_first_speed_change_percent
first_rep_hr
last_rep_hr
hr_progression_bpm
```

CV 정의:

population standard deviation / mean × 100.

documentation에 population SD임을 명시한다.

---

# 28. Recovery HR change

ACTIVE 다음 RECOVERY block이 있을 때 계산.

최소:

```text
recovery_start_hr
recovery_end_hr
recovery_hr_drop_bpm
recovery_duration_seconds
```

한 sample에 너무 민감하면 짧은 평균 window 사용 가능.

사용할 경우 exact window를 코드/문서에 고정한다.

이 값은 Garmin Recovery HR metric이 아니다.

`RunningAI interval recovery HR change`

로 정의한다.

---

# 29. Sample fidelity

`activity_detail_collection.sample_completeness`를 analysis에 전달한다.

FULL:
정상 계산.

DOWNSAMPLED:
계산 가능하지만 fidelity 표시.

UNKNOWN:
계산 가능 metric은 계산하되 UNKNOWN 유지.

lap/zone metric은 sample completeness와 독립 계산 가능.

---

# 30. Missing data

절대 추정하지 않는다.

power 없음 → null.

cadence 없음 → null.

LTHR 없음 → null.

interval 구조 없음 → empty/null.

정상적인 데이터 부재는 failure가 아니다.

---

# 31. Analysis versioning

`analysis_version` 저장 필수.

첫 버전:

`RUNNING_ANALYSIS_V1`

또는 repository convention에 맞는 deterministic value.

재분석 시 derived rows replace.

raw/detail/lap/sample은 수정하지 않는다.

---

# 32. Service 구조

권장:

```text
RunningActivityAnalysisService
        ↓
SessionMetricsCalculator
IntervalStructureExtractor
IntervalMetricsCalculator
ZoneExposureCalculator
ThresholdExposureCalculator
        ↓
ActivityAnalysisStore
```

calculator는 가능한 pure function.

giant service 금지.

---

# 33. API

manual/read-only computation API.

예:

```text
POST /api/v1/activities/{activityId}/analysis
GET  /api/v1/activities/{activityId}/analysis
```

POST:

stored normalized data
→ calculate
→ derived result replace.

Garmin API 호출 금지.

Intervals API 호출 금지.

publish 호출 금지.

---

# 34. Existing Main-PC live smoke

자동 테스트 GREEN 후 기존 4건으로 분석 smoke.

- outdoor/long run
- track interval
- treadmill
- indoor cycling

필요한 old completeness metadata는 stored raw `/details/reprocess`로 갱신 가능.

Garmin call = 0.

RUN/TREADMILL_RUN 우선.

Indoor cycling에 running-specific metric을 강제 적용하지 않는다.

---

# 35. Outdoor validation

확인:

- half HR
- half speed
- speed-HR decoupling
- cadence change
- HR zone exposure
- LTHR exposure

---

# 36. Track interval validation

확인:

- WARMUP/ACTIVE/RECOVERY/COOLDOWN
- repeated step occurrence
- work block count
- repetition speed CV
- HR progression
- last-vs-first speed change
- recovery HR drop

---

# 37. Treadmill validation

GPS 없이 성공해야 한다.

사용:

- speed
- HR
- cadence
- power

GPS/elevation null은 failure가 아니다.

---

# 38. Sanity check

API 200만 확인하지 않는다.

몇 개 metric을 manual calculation으로 spot-check.

최소:

- first-half HR
- second-half HR
- interval repetition count
- first/last rep speed
- recovery HR drop

실제 민감한 운동값은 문서에 과도하게 기록하지 않는다.

---

# 39. Tests

최소:

### Java/runtime

- Gradle uses JDK21
- canonical runtime starts without temp override

### Session calculator

- constant HR/speed
- rising HR
- falling speed
- missing HR
- missing speed
- irregular timestamps
- empty samples

### Decoupling

- known numeric result
- zero/null guards

### Cadence

- normal
- missing

### Zone exposure

- 5 zones
- zero seconds
- missing zones

### LTHR

- threshold present
- threshold absent
- irregular intervals

### Interval extractor

- warmup-active-recovery-active-cooldown
- repeated same step separated by recovery
- multiple laps per work block
- contiguous same step
- missing step index
- no recovery
- lap != step

### Repeatability

- identical reps CV=0
- known CV
- last-vs-first

### Recovery HR

- normal
- sparse
- missing
- no recovery

### Persistence

- first insert
- second replace
- no duplicate
- source data unchanged

### API

- supported run
- activity not found
- insufficient data
- GET after POST

---

# 40. Migration validation

Expected:

- V15 lap structure
- V16 analysis

V1~V14 수정 금지.

검증:

- H2
- PostgreSQL 17 throwaway DB
- live DB migration
- existing data preserved

repair/drop/truncate 금지.

---

# 41. Full regression

Spring baseline:

`947 passed`

실행:

`gradlew clean test`

기대:

- tests 증가
- 0 failed
- 0 unexpected skipped

Python:

baseline `134 passed`.

Connector 변경이 없으면 baseline 유지 확인.

PostgreSQL subset:

baseline `423 passed`.

analysis tests 추가 후 재검증.

production pool은 test 문제 때문에 변경하지 않는다.

---

# 42. Network/write guarantee

목표:

```text
Garmin live API calls = 0
Intervals API calls = 0
Historical backfill = NOT_RUN
External workout writes = 0
```

모든 분석은 현재 DB의 stored data만 사용한다.

---

# 43. Runtime safety final state

종료 시:

```text
WORKOUT_PUBLISHING_ENABLED=false
WORKOUT_PUBLISHING_SCHEDULER_ENABLED=false
RUNNING_AI_DRAFT_PUBLISHING_ENABLED=false
RUNNINGAI_MCP_ENABLED=false
```

legacy writer tasks Disabled 유지.

---

# 44. Intervals API key

이번 Phase에서는 사용하지 않는다.

현재:

`INTERVALS_API_KEY_ROTATION = USER_ACTION_REQUIRED`

상태 유지 가능.

단 **Phase 6H-5 시작 전 반드시 교체**한다.

실제 key 출력 금지.

---

# 45. No scope creep

하지 않는다:

- 90-day backfill
- Intervals enrichment
- TrainingContext V2
- Claude prompt 변경
- workout generation 변경
- workout publishing
- Garmin scheduler 변경
- FIT ingestion
- pace-only heuristic interval detection
- coaching score
- injury/risk/readiness score
- good/bad classification

---

# 46. Documentation

작성:

`docs/architecture/running-analysis-engine.md`

포함:

- input
- exact formula
- unit
- missing-data semantics
- applicability
- sample fidelity dependency

그리고 Java environment 결과도 result document에 기록한다.

특히:

`speed_hr_decoupling_percent`

는 Garmin/Intervals metric이 아니라:

**RunningAI-derived descriptive metric**

이다.

---

# 47. Git

논리적 commit 예:

```text
ops: normalize main PC Java 21 environment
feat: promote Garmin lap workout structure
feat: add detailed running analysis engine
test: cover running analysis metrics
docs: define running analysis evidence model
```

단 Windows User/Machine 환경변수 변경은 git 대상이 아니다.

full tests GREEN 후 safe fast-forward push.

force push 금지.

---

# 48. Completion criteria

Java:

```text
java -version = 21
javac -version = 21
gradlew -version JVM = 21
canonical start script = success without temp override
```

단 machine PATH 권한 문제라면:

`JAVA21_MACHINE_PATH_USER_ACTION_REQUIRED`

로 명시하고 RunningAI 자체 JDK21 사용이 검증되면 Analysis Engine 진행 가능.

Analysis:

```text
Lap workout structure first-class ✅
Session half metrics ✅
Speed-HR decoupling ✅
Cadence change ✅
HR zone exposure ✅
LTHR exposure ✅

Interval block extraction ✅
Repeated work-step detection ✅
Rep speed consistency ✅
HR progression ✅
Last-vs-first rep change ✅
Recovery HR change ✅

Derived data versioned ✅
Idempotent persistence ✅
Missing data = null ✅
No coaching judgment ✅

Outdoor live analysis smoke ✅
Track interval live analysis smoke ✅
Treadmill live analysis smoke ✅

Garmin calls = 0
Intervals calls = 0
Historical backfill = NOT_RUN
TrainingContext V2 = NOT_RUN
External writes = 0

Tests GREEN
Working tree clean
origin/main in sync
```

완료 후:

`PHASE_6H_4_RUNNING_ANALYSIS_ENGINE_READY`

를 보고하고 멈춘다.

---

# 49. 최종 보고

1. baseline SHA
2. final SHA
3. Java 21 detected installation
4. previous `java -version`
5. final `java -version`
6. final `javac -version`
7. final JAVA_HOME
8. `where java` first result
9. Oracle javapath action
10. machine/user PATH action
11. Gradle JVM version
12. canonical runtime start result
13. Java rollback information
14. migrations
15. lap first-class fields
16. analysis tables
17. analysis version
18. session metrics
19. exact decoupling formula
20. cadence metrics
21. HR-zone metrics
22. LTHR exposure
23. interval block rules
24. interval group rule
25. repetition metrics
26. recovery-HR definition
27. sample completeness handling
28. missing-data handling
29. outdoor smoke
30. interval smoke
31. treadmill smoke
32. indoor cycling behaviour
33. Garmin API calls
34. Intervals calls
35. historical backfill
36. external writes
37. publishing switches
38. Spring tests
39. Python tests
40. PostgreSQL tests
41. commits
42. push
43. working tree
44. known limitations
45. recommended Phase 6H-5 inputs
