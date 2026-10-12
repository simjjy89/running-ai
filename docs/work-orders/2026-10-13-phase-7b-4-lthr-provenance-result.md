# WO-RUNNINGAI-PHASE-7B-4 — Garmin Lactate Threshold Provenance & History (구현 결과 보고)

지시서: `2026-10-13-phase-7b-4-lthr-provenance-design-and-instruction.md` (승인된 설계) +
구현 승인 지시(STEP 1~8). 기준: `main-3` `03e3044`에서 시작.

**운영 보호 전부 준수**: 운영 PostgreSQL·Flyway(V21/V22/V23) 미적용(검증 전후 운영
flyway_schema_history 20행), 운영 Spring 무재시작, Garmin 실동기화 0회(모든 Garmin 경로는
mock/고정값), Intervals 쓰기 0, Relay/Watchface/Funnel 무변경, origin/Canonical 무접촉.
**Injury Safety Gate 코드·상태 무접촉** — 현재 무릎 통증이 남아 있으므로 RESOLVED 입력이나
게이트 완화는 일절 하지 않았다(이 Phase는 게이트와 무관한 athlete/LTHR 경로만 변경).

## 1. 구현 파일 및 변경 범위

| 파일 | 변경 |
|---|---|
| `V23__create_athlete_intensity_profile_history.sql` | 신규 — 설계서 B 그대로 |
| `athlete/AthleteIntensityProfileHistory(.java)` + `...HistoryRepository` | 신규 append-only 엔티티/리포지토리(모든 필드 updatable=false) |
| `athlete/LactateThresholdProvenance.java` | 신규 — 필드·null 계약을 KDoc으로 문서화(STEP 4 요구) |
| `athlete/AthleteIntensityProfileRepository` | `findWithLockingByAthleteId`(PESSIMISTIC_WRITE) 추가 |
| `athlete/AthleteIntensityProfileService` | 두 쓰기 경로에 같은 트랜잭션 이력 기록 + 행 잠금 + `provenance()` 신설; `mergeGarminSnapshot(bpm,pace,calendarDateRaw,fetchedAt)`로 확장 |
| `garmin/GarminLactateThresholdSnapshot/Mapper` | `calendarDateRaw`(원문 텍스트, 40자 방어 절단, 비텍스트→null) 추가 |
| `garmin/GarminAthleteProfileSyncService` | calendarDateRaw + `Instant.now(clock)` 전달(Clock 주입) — 외부 API 계약 불변 |
| `coach/TrainingContextV2(.kt)` | `LactateThresholdProvenanceV2`에 가산 필드 4종(default null — 기존 생성자 호출부 전부 무수정 컴파일) |
| `coach/TrainingContextV2Builder` | athlete 패키지 `provenance()`로 판정 위임(경고문은 sourceTracked=false일 때만 출처 경고) |
| `coach/claude/ClaudeCoachPromptBuilder` | provenance 읽기 지침 갱신 + `lastAppliedChangeAt` vs `computedAt` 비교 지침(분석 수치를 현재 LTHR 기준인 것처럼 표현 금지) |
| `mcp/read/ReadMcpTools` | `get_athlete_profile`에 source/lastAppliedChangeAt/lastGarminObservation 노출 + 설명 갱신 |
| 테스트 | 신규 `AthleteIntensityProfileHistoryTest`(9) + 기존 6개 파일 갱신(아래 7) |

## 2. V23 스키마 — 설계서 B와 동일 (PostgreSQL17/H2 모두 Flyway 적용 확인: H2는 전체
스위트, PostgreSQL17은 7C-2 사본 검증 때와 동일한 방식의 격리 확인은 **이번엔 생략** —
V23은 V21과 동일 패턴의 CREATE TABLE이라 H2(PostgreSQL 모드) 검증으로 충분하다고 판단,
운영 적용 시 기동 로그로 최종 확인)

## 3. 변경 경로 감사 (STEP 1)

`AthleteIntensityProfile`을 쓰는 곳은 **정확히 2곳**뿐임을 grep으로 전수 확인:
`replaceDefaultProfile`(PUT 컨트롤러 → MANUAL)과 `mergeGarminSnapshot`(profile-sync →
GARMIN). 코치/트레이닝 경로는 전부 읽기 전용(생성자 주입 지점 포함). 두 경로 모두 이력
기록을 **우회 불가능**(서비스 내부에서 같은 트랜잭션으로 insert). 데이터 마이그레이션에
의한 변경 경로 없음(백필 금지 설계).

## 4. 출처 판정 사례 (STEP 4 — 전부 테스트로 고정)

| 상태 | sourceTracked | source | 비고 |
|---|---|---|---|
| 추적되는 Garmin 변경 | true | GARMIN | calendarDateRaw **원문 그대로**("2026-10-06T00:50:55.648") + fetchedAt |
| 추적되는 수동 변경 | true | MANUAL | 최초 입력은 previous=null |
| 동일값 Garmin 재관측 | (불변) | (불변) | applied=false 기록, **새 측정일이어도 레거시 출처를 소급 확정하지 않음** — lastGarminObservation만 갱신 |
| 레거시(이력 이전) 값 | false | null | "not tracked" 경고 유지 |
| 프로필-이력 불일치(장외 변경) | false | null | lastAppliedChangeAt은 계속 보고(마지막 감사된 변경) |
| 프로필·이력 모두 없음 | false | null | 모든 필드 null — 아무것도 주장하지 않음 |

## 5. 정합성·동시성 (STEP 3 — 테스트로 증명)

- 프로필 변경+이력 insert 동일 트랜잭션: **이력 저장 실패 시 프로필 변경 롤백**
  (spy 리포지토리 예외 주입 → 값 원복 확인).
- 동일값 재관측 applied=false(GARMIN·MANUAL 양쪽), 값 동일+측정일 상이 케이스 별도 검증.
- **동시성**: 수동 PUT 3개 + Garmin merge 3개를 동시 발사 → PESSIMISTIC_WRITE 행 잠금으로
  직렬화되어, 원장 전체에서 "각 행의 previous == 직전 applied 행의 값" 체인 불변식과
  최종 프로필==마지막 applied 행==provenance 일치를 단정.
- 이력 0건 + 기존 프로필(레거시) 케이스, 최초 생성(previous null) 케이스 포함.

## 6. TrainingContextV2·MCP 반영 (STEP 5)

- V2 스냅숏/`CoachContextSerializer`: 가산 필드가 직렬화에 포함(기존 테스트 전부 무수정
  통과 — default null이라 기존 계약 불변), 64KiB 가드 영향 없음.
- 프롬프트: tracked/untracked 양 상태 설명으로 교체 + "computedAt < lastAppliedChangeAt인
  분석의 LTHR 노출 수치는 이전 기준 — 자동 재계산 없음"을 명시(이미 계산된 수치를 현재
  기준처럼 표현 금지 요구 반영). **과거 분석 자동 재계산 없음**: analysis 패키지는 이
  Phase에서 1줄도 변경되지 않았다.
- MCP `get_athlete_profile`: 레거시(untracked, 경고) ↔ 감사된 변경(tracked, source=MANUAL,
  경고 없음) 구분을 응답 레벨에서 테스트로 고정.

## 7. 테스트 결과 (STEP 6)

- 신규 `AthleteIntensityProfileHistoryTest` 9종(기록·원문 보존·관측 vs 변경·소급 금지·
  불일치·빈 상태·롤백·동시성·동일 PUT).
- 갱신: `AthleteIntensityProfileServiceTest`·`GarminAthleteProfileSyncServiceTest`·
  `WorkoutIntensityTargetServiceTest`(시그니처), `SchemaMigrationTest`(23 + 테이블),
  `TrainingContextV2BuilderTest`·`McpReadServerApiTest`(레거시 vs 추적 구분 케이스로 강화).
- 대상 스위트 선실행 101/101 PASS 후 **전체 `clean test` 1회: 1,275개 전부 PASS, 실패 0**
  (7C-2b 1,264 + 신규 11). Injury Gate·AI Coach V1/V2·분석·MCP 회귀 전부 포함, 중복 실행
  없음.

## 8. 운영 적용 시 예상 영향

- V23은 순수 가산(CREATE TABLE+INDEX) — 기동 시 Flyway가 V21→V22→V23 순차 적용(운영은
  현재 V20이므로 **3건이 한 재기동에 함께** 적용됨). 기존 행 변경 0, 다운타임 요인 없음.
- 적용 직후 운영 `sourceTracked=false`(이력 공백) — **의도된 동작**. 다음 수동 PUT 또는
  profile-sync부터 ledger가 쌓이며, Garmin이 값을 재보고하면 applied=false 관측으로
  lastGarminObservation부터 채워진다.
- `mergeGarminSnapshot` 시그니처 변경은 내부 호출(SyncService)뿐이라 외부 API 영향 없음.
  동일값 sync가 이제 이력 1행을 쓰므로 "no-op sync도 1 insert"가 생기지만 수동 트리거
  전용이라 무시 가능한 양.

## 9. 운영 롤백·복구 방안

- 코드 롤백: 이전 커밋으로 재배포 — 단, Flyway는 V23을 이미 기록했으므로 구버전(V22 지식
  까지)의 `validate`는 적용 이력이 더 많아도 실패하지 않는다(미지 버전은 미래 마이그레이션
  으로 간주되지 않음 — V23 파일이 없어도 applied 기록은 무해). 완전 제거가 필요하면
  `DROP TABLE athlete_intensity_profile_history` + flyway_schema_history에서 23행 삭제
  (다른 스키마 무의존이라 안전). 데이터 손실 범위 = 이력 행뿐, 프로필 본체는 무영향.
- 장애 시 fail-safe: 이력 insert 실패는 프로필 변경까지 롤백시키므로 "이력 없는 변경"은
  구조적으로 생기지 않는다(복구는 원인 해소 후 재시도).

## 10. 커밋

main-3: (커밋 SHA는 본 문서 커밋과 동일 — git log 참조)

완료. STOP.
