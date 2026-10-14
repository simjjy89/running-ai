# Phase 8A — 운영 적용 (조건부 승인 집행)

- 날짜: 2026-10-15
- 승인 근거: "RunningAI Phase 8A — 운영 적용 조건부 승인"
- 배포 커밋: `f1451f5` (canonical `main`, worktree clean)
- 배포 jar: `53d6ada77eca86694152a71da869b9f72191f5a95b804e26c1ce861a02e2fc9f`
- 상태: **운영 적용 완료 / STOP**

승인 범위 밖(운영 Garmin 스케줄러 활성화, 운영 회복 백필, OAuth 권한 변경,
Intervals.icu 게시)은 **하나도 실행하지 않았다.**

---

## 1. 배포 전 검증

### A. Flyway 정합성

| # | 항목 | 결과 |
| --- | --- | --- |
| 1 | main-3 / canonical main / origin 마이그레이션 전수 확인 | PASS — main-3 V1..V28, canonical main V1..V26(배포 전), origin은 32커밋 뒤처진 V1..V20 |
| 2 | V27/V28 중복·버전 충돌 | **FINDING 1건, 해소** (아래) |
| 3 | 운영 `flyway_schema_history` 읽기 전용 확인 | PASS — V26까지 전부 `success=t`, 실패/미완료 행 없음 |
| 4 | V27 게시 실패 이력 계획과의 충돌 | **FINDING 2건째 아님 — 동일 건.** 로드맵 P1-3이 `V27__create_workout_draft_publish_attempt.sql`을 계획하고 있었다 |
| 5 | 순수 additive 여부 | PASS — 두 파일 모두 `CREATE TABLE` 1개뿐. `ALTER`/`DROP`/`UPDATE`/`DELETE`/`INSERT` 0건 |
| 6 | Flyway validate + PostgreSQL 적용·재실행 | PASS (아래 리허설) |

**FINDING (A-2/A-4) — 계획 단계의 V27 충돌.**
미구현 과제 P1-3(게시 실패 관측성)이 `V27`을 선점 계획하고 있었고, Phase 8A가 V27/V28을
실제로 사용했다. 파일은 아직 존재하지 않았으므로 **운영 충돌이 아니라 문서 충돌**이며,
마이그레이션 파일이 만들어지기 전에 로드맵을 **V29로 재번호 부여**해 해소했다(커밋 `f1451f5`).
배포 STOP 사유에 해당하지 않는다.

**리허설 (A-6).** 운영 DB를 그대로 덤프해 `running_ai_8a_rehearsal`로 복원(V26, activity 48건)한 뒤
신규 jar를 기동:

```
Successfully validated 28 migrations
Migrating schema "public" to version "27 - create garmin sync attempt"
Migrating schema "public" to version "28 - create garmin recovery collection"
Successfully applied 2 migrations, now at version v28
```

Hibernate `ddl-auto: validate` 통과(기동 성공), `activity` 48건 불변, 신규 테이블 0행.
재기동 시 `Schema "public" is up to date. No migration necessary.` — 재실행 안전 확인.
리허설 DB는 검증 후 삭제했다.

### B. 실패 기록 영속성

운영이 아니라 **운영 복제본**에서, 커넥터를 죽은 포트로 돌려 외부 트래픽 0으로 실측했다.

| # | 항목 | 결과 |
| --- | --- | --- |
| 1 | 401/429/네트워크 오류 시 FAILED/PARTIAL 기록 | PASS — 2회 실패 후 `outcome=FAILED`, `failure_code=GARMIN_UNAVAILABLE` |
| 2 | 서비스 트랜잭션 실패 후 기록 보존 | PASS — 동기화는 503으로 throw했으나 행은 남았다(`REQUIRES_NEW`) |
| 3 | 이후 성공해도 마지막 실패 정보 유지 | PASS — `recordCompleted`는 `lastFailureAt/Code`를 건드리지 않고 `consecutiveFailureCount`만 0으로 되돌린다 |
| 4 | 저장 정책 명시 | **마지막 시도만** 보관(athlete당 1행, athlete+date당 1행). 전체 시도 이력이 아니다. 예외적으로 `last_failure_at/_code`는 이후 성공에도 살아남는 "마지막 고장 시점" 흔적이다 |
| 5 | 기록 오류가 원래 결과를 바꾸지 않음 | PASS — 2회 실패 후에도 체크포인트 `high_water=2026-10-10T22:39:40Z` / `last_successful=2026-10-12T03:24:44Z` 불변 |

`GarminCollectionFailureCode`는 예외의 **타입과 reason만** 사용하고 메시지는 쓰지 않는다 —
자격증명·토큰·페이로드 조각이 DB로 들어갈 경로가 없다.

### C. 보안

| # | 항목 | 결과 |
| --- | --- | --- |
| 1 | `GET /api/v1/data-freshness` 인증·인가 | PASS(조건부) — **인증 없음**, 단 RunningAI 운영 API 전체의 기존 규약과 동일. Spring은 `127.0.0.1:18080`에만 바인드되고, 외부 경로인 MCP 게이트웨이는 `url !== '/mcp'`를 전부 거부하므로 외부에서 도달 불가 |
| 2 | 민감정보 노출 | PASS — 응답은 날짜·건수·결과코드뿐. GPS·자격증명·원본 페이로드 없음 |
| 3 | Read/Train 및 부상 게이트 회귀 | PASS — 전체 1,472 테스트 통과(실패 0, 에러 0, 스킵 0) |
| 4 | 스케줄러 OFF / 게시 OFF | PASS |

---

## 2. 운영 적용

1. 운영 PostgreSQL 백업: `.runtime/backup/running_ai-prod-PRE-8A-20261015-075436.dump` (7.2 MB, pg_dump -Fc)
   — 복원 가능성은 같은 덤프를 리허설 DB로 실제 복원해 **증명**했다.
2. Git 분리: Phase 8A 파일만 `4727b9f`로 커밋. 7F-3B-3의 `tools/mcp-worker/*` 변경과
   관련 문서는 **의도적으로 스테이지하지 않았고 배포되지 않았다.**
3. canonical `main`을 `--ff-only`로 전진(`d86d966` → `f1451f5`). 강제 merge/reset 없음.
4. 기존 배포 절차 사용: `release-spring-jar.ps1 -VerifyOnly -ExpectedMaxMigration 28` → 통과 후
   `stop` → `release -SkipBuild` → `start`.
5. 직전 운영 jar는 `.runtime/released/running-ai-server-20261015-075946-a5a0d8a2.jar`로 보존(= `e76dc6b`, V26).

---

## 3. 배포 후 검증

| 항목 | 결과 |
| --- | --- |
| Flyway V27/V28 | `success=t`, 08:00:06 적용, 18ms / 6ms |
| `garmin_sync_attempt` | 생성됨, 0행(정상) |
| `garmin_recovery_collection` | 생성됨, 0행(정상) |
| `GET /api/v1/data-freshness` | 200 |
| 회복 coverage 조회 | 200 — COMPLETE 2 / PARTIAL 1 / NOT_COLLECTED 4 (7일) |
| Garmin 체크포인트 | **불변** — high_water 2026-10-10T22:39:40Z, last_successful 2026-10-12T03:24:44Z |
| Activity / ActivityRaw | **불변** — 48 / 10 |
| Workout Draft / 승인 / 게시 | **불변** — 19 / 3 / 3 |
| 부상 안전 게이트 | 정상 — `injury-state` 200, 최신 `RESOLVED`, publish-preview 정상 판정 |
| OAuth Read/Train | 정상 — 백엔드 MCP 툴 13개(read 7 + train 5 + publish 1), 게이트웨이는 토큰 없으면 401 |
| Garmin 스케줄러 | OFF — `enabled: false` 기본값, .env 오버라이드 0건, 스케줄 로그 0줄 |
| 게시 | OFF — `/workout-publish` 409 `WORKOUT_PUBLISHING_DISABLED`, `/publish` 409 `DRAFT_PUBLISHING_DISABLED` |
| 외부 데이터 쓰기 | **0건** — 기동 후 ERROR 0, Intervals/Garmin 쓰기 로그 0 |

**부수 관찰(Phase 8A와 무관, 수정하지 않음).** 존재하지 않는 라우트에
`GET /api/v1/workout-drafts`를 보내면 `GlobalExceptionHandler`가
`HttpRequestMethodNotSupportedException`을 405가 아니라 500으로 매핑한다. 기존 결함이며
이번 변경이 건드린 코드가 아니다.

---

## 4. 롤백

필요 없었다. 준비된 자산:

- 이전 운영 jar: `.runtime/released/running-ai-server-20261015-075946-a5a0d8a2.jar` (`e76dc6b`, V26)
- DB 백업: `.runtime/backup/running_ai-prod-PRE-8A-20261015-075436.dump`

V27/V28은 additive이므로 **테이블 DROP이나 Flyway 이력 삭제는 하지 않는다.** 애플리케이션
장애 시 이전 jar 복구가 1순위이며, 스키마 복구가 필요하면 별도 승인을 받는다.

---

## 5. 남은 일 (실행하지 않음)

- 운영 Garmin 스케줄러 활성화 — 이번 Phase가 선결 조건(실패의 DB 가시성)을 충족시켰을 뿐, 활성화는 별도 승인 사항
- 운영 회복 백필(`onlyMissing=true`) — 승인 범위 밖
- 7F-3B-3 ChatGPT train scope 작업 — 미배포 상태로 워크트리에 남아 있음
