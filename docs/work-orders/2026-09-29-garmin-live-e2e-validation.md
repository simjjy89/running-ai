# 2026-09-29 — Phase 3B-3: Garmin Live End-to-End Validation + activityName Encoding Diagnosis

| 항목 | 내용 |
|------|------|
| 날짜 | 2026-09-29 |
| 작업 | 실제 Garmin activity 1건으로 Connector → Spring → PostgreSQL E2E 검증, 2회 sync idempotency 검증, activityName mojibake 원인 진단 |
| 상태 | 완료 |
| 커밋 | `test: validate live Garmin end-to-end ingestion` |
| 지시서 원문 | [2026-09-29-garmin-live-e2e-validation-instruction.md](2026-09-29-garmin-live-e2e-validation-instruction.md) |
| 이전 작업 | [2026-09-29-garmin-connector-spring-integration.md](2026-09-29-garmin-connector-spring-integration.md), [2026-09-29-garmin-live-contract-investigation.md](2026-09-29-garmin-live-contract-investigation.md) |

---

## 1. 목적과 범위

Phase 3B-1(계약 조사)과 3B-2(connector/Spring 연결)까지는 완료됐지만 실제 Garmin 계정으로 전체 경로를 관통시켜본
적은 없었다. 이번 Phase는 새 기능을 추가하지 않고, 실제 Garmin activity 1건을 이용해:

1. Garmin Connect → python-garminconnect → connector → `HttpGarminActivitySource` → `GarminSyncService` →
   `GarminActivityIngestionService` → `ActivityRaw`/`Activity` → PostgreSQL 전체 경로가 실제로 동작하는지,
2. 동일 activity를 다시 sync했을 때 idempotency가 실데이터 기준으로도 성립하는지,
3. 사용자가 보고한 `activityName` 한글 mojibake가 어느 layer에서 발생하는지

를 검증했다. Scheduler/incremental cursor/운영 sync API/DB migration은 이번 Phase 범위 밖이며 구현하지 않았다.

## 2. 작업 전 상태

```text
branch: main (origin/main과 동일, working tree clean)
latest commit: 2f978c2 feat: connect Garmin transport to RunningAI server
regression baseline: Java 83 passed / Python 31 passed (live network 없이)
```

Garmin 로그인/token은 이 PC에 이미 준비되어 있었다(`python -m garmin_connector status` → `tokens: VALID`).
로컬에는 Docker/PostgreSQL/WSL이 준비되어 있지 않아 사용자가 Docker Desktop을 설치·기동했고, 이후
`docker compose up -d`로 `running-ai-postgres`(빈 볼륨, 기존 데이터 없음)를 띄운 뒤 진행했다.

## 3. E2E 실행 방법 (Option C 선택)

운영 sync 트리거(`POST /api/v1/garmin/sync` 등)는 만들지 않는다는 제약에 따라, 기존 아키텍처를 건드리지 않고
`GarminSyncService`를 직접 호출하는 임시 `CommandLineRunner`(`ManualLiveGarminSyncRunner`, 프로퍼티
`running-ai.garmin.manual-live-sync=true`로만 활성화)를 추가해 `./gradlew.bat bootRun`으로 1회씩 실행하는 방식을
사용했다. 결과 카운트만 로그로 남기고 raw payload는 출력하지 않는다. 검증이 끝난 뒤 이 클래스는 즉시 삭제했다
(일회성 harness이므로 39절에 따라 제거; 최종 diff에는 포함되지 않는다).

## 4. Live Garmin

```text
authentication: SUCCESS (기존 token store 재사용, 재로그인 없음)
recent activity fetch: SUCCESS (limit=1)
activity type: treadmill_running (라이브 확인)
duration unit: seconds (라이브 확인)
distance unit: metres (라이브 확인)
time: startTimeGMT 존재, UTC 없이 저장된 형식 (라이브 확인)
heart rate: averageHR/maxHR 존재 (라이브 확인)
```

실제 activity ID, activityName, GPS 등은 본 문서에 기록하지 않는다.

## 5. E2E

```text
connector: PASS (127.0.0.1:8765, GET /activities?limit=1)
Spring client: PASS (HttpGarminActivitySource → JsonNode 배열 파싱)
sync service: PASS (GarminSyncService.syncRecent(1))
raw persistence: PASS (activity_raw, payload JSONB)
normalized persistence: PASS (activity, TREADMILL_RUN / duration / distance / HR / startedAt)
```

## 6. First sync

```text
fetched: 1
created: 1
updated: 0
skipped: 0
failed: 0
```

DB는 이번 검증 전 완전히 비어 있었으므로(신규 Docker 볼륨) `created=1`이 나왔다. 기존 데이터가 있었다면
`created=0 / updated=1`도 정상 케이스였을 것이다.

`activity_raw`: `external_source=GARMIN`, `external_id` 존재, `payload`는 JSONB 객체, `activity_id`가 정상 링크됨,
`fetched_at`/`created_at` 모두 존재. `activity`: `activity_type=TREADMILL_RUN`, `duration_seconds`/`distance_meters`/
`average_heart_rate`/`max_heart_rate`가 라이브 값과 일관됨(실제 수치는 본 문서에 기록하지 않음).

## 7. Time 확인

`Activity.started_at`은 `TIMESTAMPTZ`로 저장되며 조회 시 `+00` 오프셋으로 표시되어 UTC로 저장됨을 확인했다.
`GarminActivityMapper.readStartTime`이 `startTimeGMT`(존재)를 우선 사용해 `Instant`로 변환하는 기존 로직 그대로
동작했고, timezone 착오는 관측되지 않았다.

## 8. Second sync

```text
fetched: 1
created: 0
updated: 1
skipped: 0
failed: 0
```

## 9. Idempotency

```text
Activity duplicate: 없음 (row count 1 → 1 유지)
ActivityRaw duplicate: 없음 (row count 1 → 1 유지)
Activity PK preserved: 예 (id 동일)
ActivityRaw PK preserved: 예 (id 동일)
Activity.createdAt: 유지
Activity.updatedAt: 변경 없음 (정규화 필드 값이 동일해 Hibernate가 dirty로 판단하지 않음 — 사양상 "갱신 가능"이며 필수 아님)
ActivityRaw.createdAt: 유지
ActivityRaw.fetchedAt: 갱신됨 (두 번째 sync 시각으로 갱신)
```

## 10. Encoding diagnosis

로컬 전용 diagnostic 스크립트(레포에 커밋하지 않음)로 실제 activity 1건의 `activityName`을 라이브 Garmin 호출
1회로 재사용하며 Layer 1~3을 동시에 진단했다. 실제 문자열 내용은 어떤 단계에서도 출력/로깅하지 않았다.

```text
python-garminconnect (Layer 1): str, length=18, 비-ASCII 문자 전부 유효한 한글 음절 코드포인트
                                 (U+AC00~U+D7A3 범위), UTF-8로 strict 인코딩 가능, Latin-1
                                 mojibake 서명 없음 → 이 layer는 CLEAN
FastAPI JSON (Layer 2):         media_type=application/json, JSON 직렬화 후 원본 문자열과
                                 완전히 일치(byte-level round-trip 확인) → CLEAN
raw HTTP (Layer 3):             FastAPI가 만드는 응답 바이트에 원본 UTF-8 바이트가 그대로
                                 포함됨 → CLEAN
Spring JsonNode / PostgreSQL:   실제 sync 이후 activity_raw.payload에 저장된 activityName의
                                 문자 길이=18(Layer 1과 동일), 바이트 길이=32(11 ASCII + 7×3
                                 UTF-8 한글 = 32와 일치), 비-ASCII 문자 전부 유효 한글 음절
                                 범위, Latin-1 supplement 범위(0x80~0xFF) 문자 없음 → CLEAN
PowerShell:                     Python/FastAPI/Spring/PostgreSQL 모든 layer가 clean이므로,
                                 사용자가 관측한 mojibake는 이 데이터 경로 어디에도 원인이
                                 없음. 남은 유일한 후보는 Windows PowerShell 5.1 콘솔/폰트
                                 표시 계층.
root cause:                     PowerShell 5.1 콘솔 표시 문제로 분류 (Windows PowerShell 5.1
                                 display issue). connector/Spring/DB 어디에서도 실제 데이터
                                 손상은 없었다.
fix:                             production 코드 변경 없음. latin1→utf8 강제 재인코딩 등의
                                 workaround는 넣지 않았다(정상 유니코드 문자열을 오히려
                                 손상시킬 수 있어 27/28절에 따라 금지). `activityName`은
                                 여전히 `NormalizedActivity`의 필수 필드가 아니므로 인코딩
                                 이슈와 무관하게 E2E는 성공으로 판정한다. `.claude/skills/
                                 running-ai-integration/SKILL.md`에 `GARMIN_ACTIVITY_NAME_
                                 ENCODING` known issue로 기록해 두었다 — 향후 activityName을
                                 normalized 필드로 쓰기 전에 이 진단을 재검증해야 한다.
```

## 11. Tests

```text
Java total: 83
Java passed: 83
Java failed: 0

Python total: 31
Python passed: 31
Python failed: 0
```

두 suite 모두 Garmin live network 없이(H2 + fixture, mock connector) 통과했다. `manual-live-sync` 임시 harness는
검증 후 삭제되어 정규 regression에 포함되지 않는다.

## 12. Database

```text
migration: NO
schema change: NO
```

V1~V3 migration은 수정하지 않았다. `activity`/`activity_raw` 테이블은 기존 스키마 그대로 사용했다.

## 13. Live Contract 갱신

`.claude/skills/running-ai-integration/SKILL.md`의 Garmin contract 섹션을 갱신해 `activityId`,
`activityType.typeKey`(`treadmill_running`), `startTimeGMT`→UTC, `duration`(초), `distance`(m), `averageHR`/`maxHR`을
`CONFIRMED_LIVE`로 표시했다. `GARMIN_ACTIVITY_NAME_ENCODING` known issue도 같은 파일에 추가했다. 실제 수치/식별자는
문서에 남기지 않았다.

## 14. Secrets / 로그 점검

- Garmin email/password/MFA/token/cookie/session 값: 코드·문서·로그 어디에도 없음.
- 실제 activity ID, activityName 실제 값, GPS 좌표: 코드·문서·로그 어디에도 없음.
- 진단에 사용한 임시 스크립트(스크래치패드 경로)는 레포 밖에 있었고 레포에 커밋되지 않았다.
- 임시 `ManualLiveGarminSyncRunner`는 결과 카운트만 로깅했고, 검증 후 삭제해 최종 diff에 포함되지 않는다.
- `git diff` / `git status`로 최종 변경 파일을 확인해 raw payload, 빌드 산출물, IDE 파일, `.env`가 없음을 확인했다.

## 15. Git

```text
branch: main
commit: (아래 커밋 참고)
push: 완료
```

## 16. Remaining limitation

- `activityName`은 여전히 정규화 대상이 아니며, PowerShell 콘솔 표시 문제로 분류된 것 외에 다른 표시 환경(예: 다른
  터미널, 다른 로케일)에서의 동작은 검증하지 않았다.
- 이번 E2E는 activity 1건, 1개 activity type(`treadmill_running`)만 사용했다. 다른 activity type이나 필드가
  없는 케이스는 fixture 기반 unit test로만 커버되어 있다.
- Garmin 계정 연결이 끊기거나 토큰이 만료되는 경우의 재인증 흐름은 이번 Phase에서 다시 검증하지 않았다(3B-2에서
  이미 커버).

## 17. Next Phase (제안, 자동 시작 안 함)

Phase 3C 후보:

```text
incremental sync cursor
scheduler (@Scheduled 또는 OS scheduler)
operational sync trigger (API 또는 CLI)
connector process supervision
Windows/Raspberry Pi 배포
```
