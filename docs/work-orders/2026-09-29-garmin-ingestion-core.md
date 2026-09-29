# 2026-09-29 — Phase 3A: Garmin Activity Ingestion Core (offline, fixture 기반)

| 항목 | 내용 |
|------|------|
| 날짜 | 2026-09-29 |
| 작업 | Phase 3A. Garmin JSON payload → raw 저장 → mapping → Activity upsert → reprocess |
| 상태 | 완료 |
| 커밋 | `feat: add Garmin activity ingestion core` |
| 지시서 원문 | [2026-09-29-garmin-ingestion-core-instruction.md](2026-09-29-garmin-ingestion-core-instruction.md) |
| 이전 작업 | [2026-09-29-postgresql-flyway-activity-raw.md](2026-09-29-postgresql-flyway-activity-raw.md) |

---

## 1. 작업 목적

Garmin 실제 인증 / 네트워크 접속(Phase 3B) 전에, **어떤 Garmin JSON payload가 들어와도**
원본을 먼저 보존하고, RunningAI `Activity`로 정규화하며, 같은 활동을 여러 번 받아도 중복 없이
갱신하고, 저장된 raw를 언제든 다시 처리할 수 있는 ingestion core를 완성한다.

이번 작업에서 Garmin Connect에 접속하는 코드는 **한 줄도 없다.** 로그인, OAuth, token, scraping,
FIT/TCX parser, 실제 fetch, credential 저장, Intervals.icu, scheduler, AI, training load, workout 생성은 모두 범위 밖이다.

## 2. 기존 상태

- `main` = `7a48104` (Phase 2: PostgreSQL compose, Flyway V1–V3, `activity_raw` JSONB, `ActivityRawService`). 작업 트리 clean.
- 테스트 20개 PASS (H2 + Flyway, PostgreSQL 17.11 실검증 완료).
- `ActivityService.create`는 HTTP용 create 전용(중복 409). upsert 없음.
- `ActivityRawService.saveOrUpdate`는 같은 external key의 Activity가 있으면 자동 link.

## 3. 구현 architecture

### 3.1 Package

```text
com.runningai
├─ activity
│  ├─ Activity                 (+ Activity(athleteId, NormalizedActivity), updateFrom(NormalizedActivity))
│  ├─ ActivityService          (+ upsertExternalActivity)
│  ├─ ActivityUpsertResult     (신규) activity + created flag
│  ├─ NormalizedActivity       (신규) source-independent 정규화 데이터 record
│  ├─ ActivityRawService       (+ linkToActivity)
│  └─ ... (ActivityRaw, repositories, DTO, controller: 변경 없음)
└─ integration/garmin
   ├─ GarminActivityIngestionService   ingest(JsonNode[, fetchedAt]), reprocess(garminActivityId)
   ├─ GarminActivityMapper             extractActivityId / parse / toNormalized / map  (pure, DB 접근 없음)
   ├─ GarminActivityPayload            mapping에 필요한 필드만 담은 record
   ├─ GarminActivityMappingException   단일 exception + Reason(code)
   └─ GarminIngestionResult            garminActivityId, activityId, activityRawId, action(CREATED|UPDATED)
```

GarminClient는 만들지 않았다 (지시서 Option B). Phase 3B의 client는 `JsonNode`를 `ingest()`에 넘기면 되고
ingestion core는 바뀌지 않는다. HTTP endpoint도 추가하지 않았다 (`POST /api/v1/garmin/*` → 404 확인).

### 3.2 데이터 흐름

```text
Garmin payload (JsonNode)
      |
      v  mapper.extractActivityId            activityId 없으면 여기서 실패 (저장할 key가 없음)
      |
      v  ActivityRawService.saveOrUpdate      → activity_raw (JSONB)      [transaction 1, commit]
      |
      v  GarminActivityMapper.map             → NormalizedActivity         [pure]
      |
      v  ActivityService.upsertExternalActivity → activity (insert/update) [transaction 2, commit]
      |
      v  ActivityRawService.linkToActivity    → activity_raw.activity_id  [transaction 3, commit]
      |
      v  GarminIngestionResult
```

실패 시:

```text
Garmin payload
      |
      v
activity_raw   (commit 완료)
      |
      v
mapping failure → GarminActivityMappingException
                  raw preserved / activity not created / raw.activity_id = null
```

### 3.3 Transaction 설계 (raw-first)

`GarminActivityIngestionService`는 **@Transactional이 아니다.** 전체를 하나의 transaction으로 묶으면
mapping 예외에서 raw까지 rollback되어 raw-first의 목적이 사라진다. 대신 각 단계가 자기 transaction으로
commit한다 (`ActivityRawService.saveOrUpdate`, `ActivityService.upsertExternalActivity`,
`ActivityRawService.linkToActivity`). 같은 bean 내부 self-invocation으로 proxy가 우회되는 문제를 피하려고
transaction 경계는 모두 다른 bean(service)에 두었다.

link 단계(transaction 3)가 실패하면 raw는 unlinked로 남지만, 다음 `saveOrUpdate`가 같은 external key의
Activity를 찾아 자동 link하므로 자가 복구된다. 별도 retry / lock framework는 두지 않았다 (DB unique constraint가 최종 방어선).

## 4. Garmin fixture 구조

`server/src/test/resources/fixtures/garmin/` — 모두 **synthetic** payload. 실제 사용자 데이터, credential, GPS 좌표 없음.

| 파일 | activityId | typeKey | 용도 |
|------|-----------|---------|------|
| `running-activity.json` | 188081596 | `running` | RUN. laps / elevation 등 mapping에 안 쓰는 필드 포함 (raw 보존 검증) |
| `running-activity-refetched.json` | 188081596 | `running` | 같은 activity 재수집. duration 3600000→3612000 ms, distance 10000→10020, avgHR 155→156, maxHR 172→173, lap 3개 |
| `treadmill-activity.json` | 188090001 | `treadmill_running` | TREADMILL_RUN. duration 2400500 ms → 2401 s (반올림) |
| `indoor-cycling-activity.json` | 188090002 | `indoor_cycling` | INDOOR_CYCLING. `maxHR` 없음 → null |
| `swimming-activity.json` | 188090003 | `lap_swimming` | 지원하지 않는 type |
| `missing-start-time.json` | 188090004 | `running` | 시작 시각 없음 |
| `missing-activity-id.json` | (없음) | `running` | activityId 없음 |

fixture 필드 contract (Garmin Connect activity list 형태를 본뜸, Phase 3B에서 실제 payload로 확인 필요):

```text
activityId      number|string         → Activity.externalId (GARMIN + id)
activityName    string (optional)     → GarminActivityPayload.activityName (Activity에는 저장 안 함)
activityType    {typeKey} 또는 string  → ActivityType
startTime       ISO-8601 offset 포함   → startedAt (UTC Instant)   ← fixture 기본
startTimeGMT    "yyyy-MM-dd HH:mm:ss" 또는 ISO, UTC로 해석 (fallback)
startTimeLocal + timeZoneId            (fallback)
duration        MILLISECONDS           → durationSeconds (Math.round(ms/1000))
distance        metres                 → distanceMeters (optional)
averageHR/maxHR bpm                    → averageHeartRate / maxHeartRate (optional)
```

## 5. Mapping 정책

- **Activity type** (`GarminActivityMapper.ACTIVITY_TYPES`, 대소문자 무시):
  `running`, `run`, `trail_running`, `track_running` → `RUN`;
  `treadmill_running`, `treadmill`, `indoor_running` → `TREADMILL_RUN`;
  `indoor_cycling`, `indoor_biking`, `cycling_indoor`, `virtual_ride` → `INDOOR_CYCLING`.
  그 외 → `UNSUPPORTED_GARMIN_ACTIVITY_TYPE` 예외. **임의로 RUN에 매핑하지 않는다.**
- **시간**: `startTime`(offset) 우선, 없으면 `startTimeGMT`(UTC), 없으면 `startTimeLocal`+`timeZoneId`.
  모두 UTC `Instant`로 저장 (기존 정책 유지). 파싱 실패 → `GARMIN_ACTIVITY_START_TIME_INVALID`.
- **duration**: milliseconds → 초 반올림. 없거나 숫자가 아니면 `GARMIN_ACTIVITY_DURATION_MISSING`.
- **distance / HR**: 없으면 null (0을 unknown으로 쓰지 않음). 음수나 비숫자 → `GARMIN_ACTIVITY_MAPPING_FAILED`.
- Mapper는 DB를 전혀 모른다. Service에는 JSON 필드 파싱이 없다.

Error model: `GarminActivityMappingException` 하나 + `Reason` enum

| Reason | code |
|--------|------|
| ACTIVITY_ID_MISSING | `GARMIN_ACTIVITY_ID_MISSING` |
| ACTIVITY_TYPE_MISSING | `GARMIN_ACTIVITY_TYPE_MISSING` |
| UNSUPPORTED_ACTIVITY_TYPE | `UNSUPPORTED_GARMIN_ACTIVITY_TYPE` |
| START_TIME_MISSING / START_TIME_INVALID | `GARMIN_ACTIVITY_START_TIME_MISSING` / `_INVALID` |
| DURATION_MISSING | `GARMIN_ACTIVITY_DURATION_MISSING` |
| INVALID_VALUE | `GARMIN_ACTIVITY_MAPPING_FAILED` |

## 6. Activity upsert 정책

`ActivityService.upsertExternalActivity(NormalizedActivity)`

- key = `externalSource + externalId` (`GARMIN + Garmin activityId`).
- 없으면 INSERT (기본 athlete 귀속) → `created = true`.
- 있으면 `Activity.updateFrom(...)`으로 `activityType, startedAt, durationSeconds, distanceMeters,
  averageHeartRate, maxHeartRate`만 갱신 → `created = false`. `id`, `athleteId`, external key, `createdAt` 유지, `updatedAt` 갱신(JPA Auditing).
- `updateFrom`은 external identity가 다르면 `IllegalArgumentException` (잘못된 호출 방어).
- 사용자용 `POST /api/v1/activities`(`create`)는 손대지 않았다: 여전히 중복 409.

## 7. Raw 저장 정책

Phase 2의 `ActivityRawService.saveOrUpdate`를 그대로 재사용: `GARMIN + externalId` 없으면 insert,
있으면 `payload` / `fetchedAt` update, row id와 `createdAt` 유지. 추가한 것은 `linkToActivity(rawId, activityId)` 하나
(이미 같은 activity에 연결돼 있으면 no-op). 새 migration 없음 (V1–V3 미수정).

## 8. Reprocessing 정책

`GarminActivityIngestionService.reprocess(garminActivityId)`

```text
activity_raw 조회 (GARMIN + id, 없으면 ACTIVITY_RAW_NOT_FOUND)
→ 저장된 JSONB payload로 mapper 실행
→ ActivityService.upsertExternalActivity
→ ActivityRawService.linkToActivity
```

- Garmin에 접속하지 않고 DB JSONB만 사용한다. `fetchedAt`은 바꾸지 않는다 (reprocess는 fetch가 아님).
- mapper 수정, 새 normalized 필드 추가, type mapping 개선 후 과거 데이터를 다시 가공하는 용도.
- API로 노출하지 않는다. 이번 단계는 Garmin raw만 지원한다.

## 9. Idempotency 정책

- 같은 payload를 N번 `ingest` → `activity` 1행, `activity_raw` 1행. 2회째부터 action = `UPDATED`.
- 값이 바뀐 payload → 같은 `Activity.id` / `ActivityRaw.id`를 유지한 채 normalized 값, raw payload, `fetchedAt`, `updatedAt` 갱신.
- 동시성: DB unique constraint(`uk_activity_external`, `uk_activity_raw_external`)가 최종 방어선. lock / retry 없음 (scheduler 단계에서 재검토).

## 10. Logging

`GarminActivityIngestionService`가 INFO로 started / raw stored / completed(action 포함), WARN으로 mapping failed(reason code, rawId)를 남긴다.
raw payload 본문은 어떤 로그에도 출력하지 않는다.

## 11. 테스트 결과

### H2 + Flyway (`test` profile)

```text
cd server
.\gradlew clean test
→ BUILD SUCCESSFUL, 63 tests, 63 passed, 0 failed
   기존 20 + 신규 43 (GarminActivityMapperTest 32, GarminActivityIngestionServiceTest 11)
```

| 테스트 클래스 | 개수 | 내용 |
|---------------|------|------|
| `RunningAiApplicationTests`, `HealthApiTest`, `ActivityApiTest`, `ActivityRawServiceTest`, `SchemaMigrationTest` | 20 | 기존 회귀, 변경 없음 |
| `GarminActivityMapperTest` (pure unit, Spring 없음) | 32 | RUN / TREADMILL_RUN / INDOOR_CYCLING fixture mapping, typeKey 10종 parameterized, string type 허용, unsupported → 예외(RUN 기본값 금지), activityId / startTime / activityType / duration 누락, 잘못된 시각, offset / GMT / local+zone 시간 변환 3종 + 우선순위, ms→s 변환 5 case, optional 값 null, 음수 거부 |
| `GarminActivityIngestionServiceTest` (SpringBootTest, **비-transactional**) | 11 | 최초 ingest(raw+activity+link, 전체 payload 보존), type 3종 ingest, 동일 payload 2회 idempotent, 변경 payload in-place update(id / createdAt 유지, updatedAt / fetchedAt 갱신), unsupported type raw 보존, startTime 누락 raw 보존, activityId 누락 시 아무것도 저장 안 함, reprocess로 activity 생성, reprocess로 activity 갱신, unsupported raw reprocess 실패 + raw 유지, 없는 id reprocess → not found |

테스트 isolation: ingestion 테스트는 transaction 경계 자체가 검증 대상이므로 `@Transactional` rollback 대신
`@AfterEach`에서 `activity_raw` → `activity` 순으로 삭제한다. 나머지 테스트는 기존 rollback 패턴 유지.
모든 테스트는 network 없이 실행된다 (HTTP client 코드 자체가 없음).

### 실제 PostgreSQL 17.11

Phase 2와 같은 방식(scratchpad의 PostgreSQL 17.11 바이너리, `running_ai_test` DB, `SPRING_APPLICATION_JSON`으로
datasource + `json_type=JSONB` override)으로 전체 suite 재실행:

```text
.\gradlew test --rerun-tasks   (jdbc:postgresql://localhost:5432/running_ai_test)
→ BUILD SUCCESSFUL, 63 passed, 0 failed
flyway_schema_history: 1 / 2 / 3 success, 신규 migration 없음
테스트 종료 후 activity 0행, activity_raw 0행 (cleanup 확인)
```

### 기동 smoke (`bootRun --args='--spring.profiles.active=test'`)

```text
GET  /api/v1/health            → 200 {"status":"UP","application":"running-ai"}
GET  /actuator/health          → 200
POST /api/v1/activities        → 201 (create 의미 유지)
POST 동일 external key         → 409 DUPLICATE_ACTIVITY (유지)
GET  /api/v1/activities/1      → 200,  /999 → 404 ACTIVITY_NOT_FOUND
GET  /api/v1/activities        → 200
POST /api/v1/garmin/ingest     → 404 NOT_FOUND (endpoint 없음, 의도)
```

## 12. 알려진 제한사항

- **Garmin 인증, network client, 실제 activity fetch, credential 저장 모두 미구현.** ingest 입력은 테스트 fixture뿐이다.
- fixture 필드 contract(`duration` ms, `startTime` offset, `activityType.typeKey` 등)는 synthetic이다.
  실제 Garmin payload가 다르면(예: duration이 초 단위) **mapper 한 곳만** 바꾸면 된다. raw는 그대로 보존되므로 reprocess로 재가공 가능.
- 지원 type 3종 외(수영, 야외 자전거 등)는 예외로 끝난다. batch / scheduler 단계에서 SKIP 정책 필요.
- `activityName` 등 정규화하지 않는 필드는 raw에만 있다. Activity에 필드를 추가하려면 V4 migration + mapper + reprocess.
- 단일 payload ingest만 있다. batch는 호출 측에서 `for each → ingest()`.
- Ingestion 실패 이력(왜 실패했는지)을 DB에 남기지 않는다. 지금은 예외 + WARN 로그뿐.
- link 단계 실패 시 raw는 다음 saveOrUpdate까지 unlinked로 남을 수 있다 (자가 복구, 3.3 참고).
- 동시 ingest에 대한 lock 없음 (unique constraint로 한쪽 실패).
- Docker는 여전히 이 PC에 없다. PostgreSQL 검증은 로컬 바이너리 인스턴스로 수행했다.

## 13. Phase 3B 준비사항

1. 기존 RunningAI(메인 PC)의 Garmin 수집 방식 / 실제 payload 샘플 확인 → fixture contract와 mapper 보정
2. Garmin session / credential 전략 (환경변수 `GARMIN_USERNAME` / `GARMIN_PASSWORD` 예약됨, token 저장 위치 결정)
3. `integration/garmin`에 GarminClient 구현 → `List<JsonNode>` 반환
4. recent activity fetch → `for each payload: ingestionService.ingest(payload, fetchedAt)`
5. unsupported type SKIP / 실패 집계 정책 (batch 결과 record)
6. incremental ingestion / sync cursor (마지막 fetched activity 기준)
7. scheduler 연결
