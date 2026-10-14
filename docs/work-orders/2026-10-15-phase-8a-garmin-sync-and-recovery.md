# Phase 8A — Garmin 증분 동기화 관측성 및 회복 데이터 커버리지

- 날짜: 2026-10-15
- 브랜치: `main-3`
- 기준 커밋: `36448fe`
- 지시 원문: `2026-10-15-phase-8a-garmin-sync-and-recovery-instruction.md`
- 상태: **개발 환경 검증 완료 / 운영 미적용 (STOP)**

---

## 0. 한 줄 요약

작업 1의 체크리스트 9개 중 8개는 **이미 구현되어 있었고 재구현하지 않았다.** 실제로 비어 있던 것은
**"실패를 데이터 없음과 구별할 수 없다"** 하나였고, 이번 Phase는 그 한 가지를 Garmin 활동 동기화와
회복 수집 양쪽에 채운 뒤(V27/V28), 그 사실 위에 커버리지 보고와 읽기 전용 신선도 API를 올린 것이다.

---

## 1. 먼저 보고해야 할 사실 — 작업 1은 대부분 이미 구현되어 있다

지시서가 "기존 구현이 정상이면 재구현하지 않는다"고 명시했으므로, 코드를 먼저 읽고 항목별로 실태를
확인했다.

| 지시서 항목 | 실태 | 근거 |
| --- | --- | --- |
| 마지막 성공 시점 저장 및 재실행 | **이미 구현** | `garmin_sync_state.last_successful_sync_at` (V4) |
| 신규 활동 증분 조회 | **이미 구현** | high-water mark + 7일 overlap window, `GarminIncrementalSyncService` |
| Activity / ActivityRaw 중복 방지 | **이미 구현** | `external_source + external_id` upsert, 재수집은 UPDATE |
| 원본 데이터 보존 | **이미 구현** | raw-first 3-트랜잭션 수집, 매핑 실패해도 raw는 커밋됨 |
| 실패 후 재시도 및 부분 실패 복구 | **이미 구현** | `failed > 0`이면 체크포인트 미전진 → 다음 실행이 같은 창을 재수집 |
| 동시 실행 방지 | **이미 구현** | `GarminSyncOperationService`의 `ReentrantLock` 단일 비행 |
| 인증 만료 / Garmin 429 대응 | **이미 구현** | `GarminConnectorErrorMapper` → `AUTH_REQUIRED` / `RATE_LIMITED`, 스케줄러는 틱 종료 후 재시도 없음 |
| 수집 실패 시 기존 데이터 보존 | **이미 구현** | 커넥터 실패는 중단일 뿐, 이미 수집된 행은 유지 |
| **최신 동기화 시각 및 처리 건수 기록** | **미구현** | 건수는 **로그에만** 존재했고 DB에 남지 않았다 |

### 스케줄러가 OFF인 이유

`GarminSyncScheduler`는 코드가 완성되어 있고 `@ConditionalOnProperty`로 꺼져 있을 뿐이며,
기능적 결함 때문에 꺼진 것이 아니다. 운영에서 꺼져 있는 이유는 두 가지다.

1. 7E/7F의 과거 데이터 작업(`historical-backfill`, `intervals-activity-import`,
   `strava-archive-import`)이 **Garmin 스케줄러가 켜져 있으면 기동을 거부**한다.
2. 스케줄러가 실패하기 시작해도 **DB에 아무 흔적이 남지 않아** 운영자가 알아챌 방법이 없었다.

(2)가 이번 Phase에서 해소된 항목이며, 이것이 스케줄러 활성화의 선결 조건이었다.

---

## 2. 실제로 비어 있던 것 — 실패와 "데이터 없음"의 구별 불가

### 2-1. 활동 동기화

`garmin_sync_state`는 **성공만** 기록한다(설계상 옳다 — 실패한 실행이 체크포인트를 움직여서는 안
된다). 그 결과 DB만 보고서는 아래 세 가지가 완전히 동일하게 보였다.

```
last_successful_sync_at = 2026-10-12  →  (a) 이후 Garmin에 새 활동이 없었다
                                         (b) 이후 동기화가 한 번도 돌지 않았다
                                         (c) 동기화가 돌았고 429/401로 실패했다
```

(a)는 정상, (b)는 스케줄러 문제, (c)는 커넥터/인증 문제로 **대응이 전부 다르다.**

### 2-2. 회복 데이터

`RecoverySnapshotService.upsert`는 값이 하나도 없는 날에 **행을 만들지 않는다**(빈 행은 "Garmin에
데이터가 있었다"로 읽히므로 이 규칙은 옳고 유지했다). 따라서 행의 부재가 다시 세 가지를 뭉갠다.

```
garmin_recovery_daily에 행 없음  →  (a) 아직 조회한 적 없다            → 백필 가치 있음
                                    (b) 조회했고 Garmin에 정말 없다    → 다시 물어도 소용없음
                                    (c) 조회가 실패했다                 → 재시도 가치 있음
```

지시서의 "원본 Garmin 데이터가 존재하는 날짜만 백필한다"는 (b)를 (a)와 구별할 수 있어야만
성립한다.

---

## 3. 구현

### 3-1. V27 `garmin_sync_attempt` — 활동 동기화 시도 기록

athlete당 1행, **마지막 시도**의 결과를 담는다.

- `outcome`: `SUCCESS` / `PARTIAL`(완주했으나 매핑 실패 항목 존재 → 체크포인트 미전진) / `FAILED`
- `fetched` / `created` / `updated` / `skipped` / `failed` / `pages_fetched` / `checkpoint_advanced`
- `consecutive_failure_count`: 완주한 시도가 0으로 되돌린다 → **증가하는 숫자가 곧 "스케줄러가 계속
  실패 중"이라는 신호**
- `last_failure_at` / `last_failure_code`: 이후 성공해도 **지우지 않는다** → "언제 마지막으로
  고장났는가"가 계속 답 가능

**`garmin_sync_state`에 컬럼을 붙이지 않고 별도 테이블로 만든 이유**: `garmin_sync_state`는 두 개의
타임스탬프가 모두 `NOT NULL`이라 **한 번도 성공한 적 없는 athlete의 실패를 담을 수 없다.** 기존
테이블의 의미(성공만 기록)를 그대로 보존하면서 bootstrap 실패도 기록하려면 분리가 필요했다.
6H-6 백필의 "`garmin_sync_state`를 절대 건드리지 않는다" 불변식도 그대로 유지된다.

기록 지점은 `GarminSyncOperationService.runSync()` 한 곳 — 수동 API와 스케줄러의 **공통 단일 경로**다.

- **단일 비행에 막혀 거부된 요청은 기록하지 않는다.** Garmin에 도달하지 않았으므로 수집 시도가
  아니다. 이를 기록하면 바쁜 스케줄러가 **일어나지도 않은 실패를 만들어낸다.**
- 기록은 `REQUIRES_NEW` 트랜잭션이며, 기록 자체의 실패는 로그만 남기고 삼킨다. **관측성이 수집
  결과를 잃거나 원래 예외를 바꾸는 이유가 되어서는 안 된다.**

### 3-2. V28 `garmin_recovery_collection` — 회복 수집 일자별 결과

(athlete, recovery_date)당 1행, 재시도 시 교체된다(시도 이력이 아니라 최신 결과).

- `outcome`: `STORED` / `NO_DATA` / `FAILED`
- 지표별 `*_outcome` 5개: `AVAILABLE` / `NO_DATA` / `ERROR` / `MALFORMED`
  — 지시서의 확인 지표 5종(HRV, 수면, 안정시 심박수, Body Battery, Stress)에 1:1 대응
- 컬럼명을 `*_status`가 아니라 `*_outcome`으로 둔 이유: `garmin_recovery_daily.hrv_status`는
  **Garmin 자신의 라벨(`BALANCED` 등)** 이므로 수집 결과와 섞이면 안 된다.

### 3-3. 커버리지 보고 — `GET /api/v1/garmin/recovery-sync/coverage`

저장된 값과 수집 결과를 조인해 일자별 상태를 낸다. **Garmin을 호출하지 않는다.**

| 상태 | 의미 | 재수집 가치 |
| --- | --- | --- |
| `COMPLETE` | 5개 지표 모두 저장됨 | 없음 |
| `PARTIAL` | 일부만 저장됨 | **있음** (Garmin이 뒤늦게 채우는 경우가 있다) |
| `CONFIRMED_NO_DATA` | 성공적으로 물었고 Garmin에 아무것도 없다 | **없음** |
| `COLLECTION_FAILED` | 마지막 호출이 실패했다 | 있음 |
| `NOT_COLLECTED` | 기록이 없다 (미조회, 또는 Phase 8A 이전 수집) | 있음 |

저장된 값이 상태를 먼저 결정하고, 수집 기록은 값이 없을 때만 쓴다. 값이 있는 날은 그 뒤의 재수집이
실패했더라도 여전히 커버된 것이며, 그 실패는 `lastCollectionOutcome`에 별도로 보인다.

### 3-4. `onlyMissing` 백필 모드

`POST /api/v1/garmin/recovery-sync/backfill`에 `"onlyMissing": true`를 추가했다. **기본값 false로
기존 동작은 한 글자도 바뀌지 않는다.**

- `COMPLETE` / `CONFIRMED_NO_DATA` 일자는 **커넥터 호출 없이** 건너뛴다
- `PARTIAL` / `COLLECTION_FAILED` / `NOT_COLLECTED`는 재수집한다
- 건너뛴 날에는 **백필 지연(2s)도 넣지 않는다** — 지연은 두 번의 커넥터 호출 사이를 벌리기 위한
  것이므로 호출하지 않은 날에 자는 것은 의미가 없다

덮어쓰기 위험은 없다: `RecoverySnapshot.merge`가 이미 **"이번에 오지 않은 지표는 저장된 값을
지우지 않는다"** 를 보장하며, 이번 Phase는 그 규칙을 바꾸지 않았다(테스트로 고정했다).

### 3-5. `GET /api/v1/data-freshness` — 읽기 전용 신선도 (작업 3)

지시서의 "기존 API를 우선 재사용"에 따라 먼저 기존 자산을 확인했다.

- `POST /api/v1/coach/data-refresh` (6H-9)는 신선도를 보고하지만 **실제로 수집해서** 답한다.
  "수집할 가치가 있는지 먼저 알고 싶다"에는 쓸 수 없다.
- `CoachDataFreshnessEvaluator`는 DB-only지만 **코치 결정 가능 여부**(blocker/warning)를 판정한다.
  작업 3이 요구하는 것은 판정이 아니라 사실이다.

따라서 **읽기 전용 GET 1개**만 추가했다. 새 영속화는 없고, 전부 기존 테이블/스토어를 읽는다.
"이 분석이 최신인가"는 `AnalysisReusePolicy`(7E-1의 단일 권위)를 그대로 호출하며 재구현하지 않았다.

소스별로 동일한 형태를 보고한다: 최신 데이터 날짜, 지연 일수, 창 내 건수, 마지막 시도/성공/실패,
연속 실패 수. 여기서 가장 중요한 필드는 **`collectionTracked`** 다.

- `true` (GARMIN_ACTIVITY, GARMIN_RECOVERY): 시도 기록이 있으므로 데이터 부재의 원인을 귀속할 수 있다
- `false` (INTERVALS_FITNESS, INTERVALS_ACTIVITY, STRAVA_ACTIVITY): 시도 기록이 없다. 이들은 일회성
  수동 임포트이므로 데이터가 오래된 것이 **정상**이며, 이 보고서는 그렇다고 **명시**한다.
  `lastAttemptOutcome = null`만 내보내면 "한 번도 시도하지 않았다"로 오독되는데, 그것은 이 보고서가
  할 수 없는 주장이다.

창(window)은 **건수만** 제한하고 "최신 날짜" 탐색은 제한하지 않는다(365일). 40일 전에 멈춘 소스가
`null`로 보여 "데이터가 아예 없다"로 읽히는 것을 막기 위해서이며, 이는
`CoachDataFreshnessEvaluator`가 이미 쓰는 "찾는 창 ≠ 판정하는 임계값" 규칙과 같다.

### 3-6. 중복 제거 (리팩터링 1건)

"지표가 전달되었는가"의 정의가 매퍼와 커버리지 양쪽에 생길 뻔해서, `GarminRecoveryMetricGroup`
enum 하나로 모았고 `GarminRecoveryMapper`가 이를 쓰도록 바꿨다. **동작은 동일**하며(이름/순서/판정
모두 보존) 기존 `GarminRecoveryMapperTest`가 그대로 통과한다.

---

## 4. 하지 않은 것 (의도적)

- **스케줄러를 켜지 않았다.** 설정 파일에도 손대지 않았다.
- **운영 DB를 건드리지 않았다.** 검증은 전부 H2(테스트 프로파일) + Flyway 실제 마이그레이션.
- **회복 수집 스케줄러를 만들지 않았다.** 6F의 "수동 전용" 결정은 이번 범위 밖이다.
- **훈련부하 계산식을 바꾸지 않았다**(감사 P3-4의 "하지 않는다" 권고 유지).
- **분석 엔진에 손대지 않았다.** `RUNNING_ANALYSIS_ENGINE_REVISION`은 **그대로 2** — 이번 변경은
  분석이 쓰는 값을 하나도 바꾸지 않으므로 bump 규칙에 해당하지 않는다.
- **부상 게이트 / OAuth / MCP / 게시 경로는 한 줄도 바꾸지 않았다.**

---

## 5. 테스트

### 신규 테스트 3개 클래스 / 30개 테스트

`GarminSyncAttemptRecordingTest` (10) — 지시서 시나리오 1~6

| 시나리오 | 테스트 |
| --- | --- |
| 1. 신규 활동 1건 수집 | 건수 기록 + 체크포인트 전진 |
| 2. 동일 활동 중복 수집 | activity/raw 각 1행 유지, `created=0 updated=1`로 중복 제거가 **기록에서 보인다** |
| 3. Garmin 429 | `GARMIN_RATE_LIMITED` 기록, 체크포인트 불변 |
| 4. 인증 실패 | `GARMIN_AUTH_REQUIRED` 기록 (403/UNAVAILABLE도 함께) |
| 5. 수집 중 프로세스 종료 | 페이지 사이 커넥터 사망 → 수집분 유지·체크포인트 불변·실패 기록 → 다음 실행이 중복 없이 완료 |
| 6. 스케줄러 중복 실행 | 거부된 쪽은 **기록되지 않는다** |
| 추가 | 연속 실패 누적 후 성공 시 streak만 0, 실패 이력은 보존 |
| 추가 | 매핑 실패 항목 → `PARTIAL` + 체크포인트 보류 |

`GarminRecoveryCoverageApiTest` (10) — 시나리오 7~9

| 시나리오 | 테스트 |
| --- | --- |
| 7. 회복 데이터 일부 누락 | `PARTIAL` + 누락 지표 명시 + 지표별 `NO_DATA`/`ERROR` 구별 |
| 8. 원본에 없는 날짜 | 빈 행 생성 없음 + `CONFIRMED_NO_DATA` 기록 |
| 8-보강 | `onlyMissing`이 `CONFIRMED_NO_DATA`를 **다시 묻지 않고**, 실패 일자는 재시도 (커넥터 호출 횟수로 검증) |
| 8-보강 | `PARTIAL` 재수집이 **저장된 값을 덮어쓰지 않는다** |
| 9. TrainingContextV2 갱신 | 백필 5일 → `recoveryDaysAvailable` 0 → 5 |
| 추가 | 커버리지 API가 Garmin을 **전혀 호출하지 않는다** |
| 추가 | 플래그 없는 기존 백필 요청의 동작 불변 |

`DataFreshnessApiTest` (10) — 작업 3

외부 호출 0회 검증, 소스별 독립적 최신 날짜(Intervals 임포트가 Garmin을 최신으로 보이게 하지 않음),
창 밖 소스의 실제 나이 보고, 실패 후에도 "마지막 성공"이 보존됨, `collectionTracked` 정직성,
미사용 설치에서 0이 아닌 null, 창 clamp, 분석 건수.

### 결과

| 실행 | 결과 |
| --- | --- |
| H2 (기본, `./gradlew clean test`) | **1472 tests, 0 failures, 0 errors, 0 skipped** |
| PostgreSQL 17 실물 (임시 DB `running_ai_8a_test`) | **1472 tests, 0 failures, 0 errors, 0 skipped** |

기준선 1442개 + 신규 30개 = 1472개. **기존 1442개 중 회귀 0건.**

PostgreSQL 검증은 **새 임시 데이터베이스를 만들어** 수행했고, `running_ai` /
`running_ai_realcopy`를 포함한 기존 데이터베이스는 **읽지도 쓰지도 않았다.** 검증 후 임시 DB는
DROP했다(목록으로 확인). V27/V28의 컬럼 타입, 유니크 제약, CHECK 제약이 PostgreSQL에서 의도대로
생성되는 것을 직접 확인했다.

### 회귀

- 기존 테스트 수정 3건 — 전부 **계약 변경에 따른 호출부/기대값 갱신**이며 테스트를 약화시키지 않았다:
  - `SchemaMigrationTest`: 마이그레이션 목록에 `27`, `28` 추가
  - `GarminRecoverySyncApiTest`: `backfill(end, days)` → `backfill(end, days, false)` 1곳
  - `GarminSyncApiTest` / `GarminRecoverySyncApiTest`: 신규 테이블 `@AfterEach` 정리 추가
- 삭제하거나 비활성화한 테스트: **0건**

### 개발 중 발견하여 수정한 실제 버그 1건

최초 구현은 비어 있는 엔티티를 `save()` 한 뒤 필드를 채웠다. identity PK는 `save()`가 즉시 INSERT를
일으키므로 `NOT NULL` 제약에 걸렸고, **"기록 실패는 삼킨다"는 (의도적으로 옳은) 설계 때문에 운영에서는
조용히 아무것도 기록되지 않았을 것이다.** 신규 테스트가 이를 잡아냈고, 양쪽 서비스에서 "결과를 채운
뒤 저장"으로 고쳤다.

---

## 6. 변경 파일

**마이그레이션 (신규 2)**
- `V27__create_garmin_sync_attempt.sql`
- `V28__create_garmin_recovery_collection.sql`

**신규 (Java, `integration.garmin`)**
- `GarminSyncAttempt` / `…Outcome` / `…Repository` / `…Service`
- `GarminRecoveryCollection` / `…Outcome` / `…Repository` / `…Service`
- `GarminRecoveryMetricOutcome`, `GarminRecoveryMetricGroup`
- `GarminRecoveryCoverageStatus`, `GarminRecoveryCoverageResponse`
- `GarminCollectionFailureCode`

**신규 (Kotlin, `datafreshness`)**
- `DataFreshnessModels.kt`, `DataFreshnessService.kt`, `DataFreshnessController.kt`

**수정**
- `GarminSyncOperationService` — 시도 기록 (단일 경로)
- `GarminSyncStatusService` / `GarminSyncStatusResponse` — `lastAttempt` 추가 (하위 호환)
- `GarminRecoverySyncService` — 결과 기록 + `onlyMissing` + `coverage`
- `GarminRecoverySyncController` / `…BackfillResponse` / `…Properties`
- `GarminRecoveryMapper` — 지표 판정을 enum으로 일원화 (동작 동일)
- `ActivityRepository` — 소스별 최신/건수 조회 2개
- `CoachDataRefreshService` — `backfill(..., false)` 호출부 1곳
- `application.yml` — `max-coverage-days` 문서화

---

## 7. 운영 적용 시 설정과 예상 영향 (적용하지 않음 — 승인 필요)

### 7-1. 반드시 필요한 것: 마이그레이션

V27, V28이 적용된다. **둘 다 순수 `CREATE TABLE`이며 기존 테이블을 건드리지 않는다.** 기존 행의
수정/삭제가 없고 다운타임 요인이 아니다.

### 7-2. 설정 변경: 없음 (이번 배포 자체에는)

새 기능은 전부 **엔드포인트 호출로만** 동작한다. 새 스위치도, 새 스케줄러도 없다.
`max-coverage-days: 365`는 기본값이며 env 변수가 필요 없다.

### 7-3. 배포 직후의 예상 모습 (중요)

- `garmin_sync_attempt`는 **비어 있다.** 첫 동기화 실행 전까지 `GET /garmin/sync/status`의
  `lastAttempt`는 `null`이며, 이는 "실패"가 아니라 "아직 기록된 시도가 없다"이다.
- 운영의 기존 회복 일자 21행에는 **수집 기록이 없으므로** 커버리지가 `NOT_COLLECTED`가 아니라
  `PARTIAL`/`COMPLETE`로 나온다(값이 있으므로). 값이 없는 과거 일자는 `NOT_COLLECTED`로 보고되며,
  이는 정확하다 — Phase 8A 이전에는 결과를 기록하지 않았다.
- **따라서 운영에서 첫 `onlyMissing` 백필은 값이 없는 모든 날짜를 실제로 조회한다.** 2회차부터
  `CONFIRMED_NO_DATA`가 쌓이면서 호출이 줄어든다. 이는 설계대로다.

### 7-4. 다음 단계로 제안하되 **이번에 하지 않은 것**

| 항목 | 선결 조건 | 영향 |
| --- | --- | --- |
| `RUNNING_AI_GARMIN_SCHEDULER_ENABLED=true` | 과거 데이터 작업(백필/임포터)과 **동시에 켜지 말 것** — 임포터가 기동 거부 | 신규 활동 자동 유입. 실패 시 이제 `garmin_sync_attempt`에 흔적이 남는다 |
| 운영 회복 백필 (`onlyMissing: true`, 28일) | 커넥터 가동, 첫 실패에서 중단(기존 동작) | `garmin_recovery_daily` 21행 → 베이스라인 최소 7일 충족 기대 |

두 항목 모두 **별도 승인 사항**이며 이번 Phase에서 실행하지 않았다.

---

## 8. 한계

- **운영 데이터로 검증하지 않았다.** 전부 H2 + 합성 payload다. 커넥터 계약 자체는 이번에 바뀌지
  않았으므로 새로운 외부 계약 위험은 없다.
- `garmin_sync_attempt`는 **마지막 1회**만 보관한다. 시도 이력(시계열)은 보관하지 않으며, 필요해지면
  별도 Phase에서 append-only 테이블로 다루어야 한다. 현재 질문("지금 신뢰할 수 있는가")에는 1행으로
  충분하다.
- 회복 수집에는 **소스 단위 연속 실패 수가 없다**(일자별 1행이므로). `data-freshness`의
  `consecutiveFailureCount`는 회복 소스에서 의도적으로 `null`이며, 일자 순서로 추정하지 않는다.
- Phase 8A 이전에 수집된 회복 일자는 기록이 없어 `NOT_COLLECTED`로 보인다. **소급 복원은 불가능하며**
  (그 시점의 결과가 어디에도 남아 있지 않다), 추측해서 채우지 않았다.
- 운영 Spring 인스턴스에 배포하지 않았다. 빌드 산출물(jar) 교체도 하지 않았다.

---

## 9. 다음 단계

1. 본 보고서 검토 → 운영 적용 승인 여부 결정
2. (승인 시) V27/V28 적용 → `GET /api/v1/data-freshness`로 현재 실태 1회 측정
3. (승인 시) 회복 백필 `onlyMissing: true`로 28일 → 베이스라인 충족 확인
4. (별도 승인) Garmin 증분 동기화 스케줄러 활성화 + 24시간 후 `lastAttempt` 확인
