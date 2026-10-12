# WO-RUNNINGAI-PHASE-7B-2 — Persistent Injury State & Workout Safety Gate (결과 보고)

지시서: `2026-10-12-phase-7b-2-persistent-injury-safety-gate-instruction.md`

작업 시작 시 `main-3` HEAD `24ff0b5`(기대값과 일치) 확인, Working Tree는 Phase 7A-1/7B-1의
미추적 work-order 문서만 존재(클린). 운영 Spring(`18080`)·Connector(`8765`)·PostgreSQL은
재시작하지 않았고, 운영 Draft #19는 변경하지 않았다(아래 9번 항목에서 재확인).

## 1. 기존 안전성 우회 경로

| 경로 | 7B-2 이전 통증 인지 여부 | 비고 |
|---|---|---|
| `WorkoutDraftService.generate/revise` | `painOrFatigueFeedback`을 요청마다 사람이 수동 입력해야만 프롬프트에 전달됨 | 저장된 상태 없음 |
| `WorkoutDraftApprovalService.approve` | 전혀 없음 | 생성 시점 안전 상태가 그대로 승인에 유지된다는 보장 없음 |
| `ApprovedWorkoutDraftPublishService.preview/publish` | 전혀 없음 | 승인 이후 통증이 발생해도 그대로 게시 가능했음 |
| `WorkoutPublishApplicationService`(결정론적 레거시 파이프라인) | **설계상 통증 개념 자체가 없음**(`TrainingDecisionContextService`/`WorkoutRecommendationPolicy`는 캘린더 패턴만 사용) | `WORKOUT_PUBLISHING_ENABLED=false`로 기본 차단되어 있으나, 켜질 경우 통증과 무관하게 발행 가능했던 유일한 경로 |
| `WorkoutPublishingScheduler`, `PublishWorkoutMcpTool` | 전부 `WorkoutPublishApplicationService.publish()`만 호출 | 위와 동일한 공백을 그대로 상속 |
| `running-ai-coach.ps1`(메인 PC, 본 repo 밖) | 코드 접근 불가 | 기존 `POST /api/v1/workout-drafts` HTTP만 호출하는 클라이언트로 전제(코드 미확인, 불가피) |
| `TrainingContextV2Builder` | 통증/부상 필드 없음(의도된 설계: Phase 7A-1에서 이미 확인) | 그대로 유지 — 이번 Phase는 Context가 아니라 `SessionConstraints` 레벨에서 주입 |

**결론**: 유일하게 실제로 통증을 반영할 수 있던 경로는 매 요청 수동 `painOrFatigueFeedback`
뿐이었고, 그조차 서버가 독립적으로 검증하지 않았다 — 안전은 전적으로 "사람이 매번
올바르게 입력했는가"에 의존했다. 이번 Phase는 이 의존성을 제거했다.

## 2. 통증 상태 모델

새 패키지 `com.runningai.injury`(Kotlin), 테이블 `injury_report`(V21, 기존 `activity_raw`
등 Flyway 체계와 동일한 append-only 방식):

```
athlete_id, body_site, side(LEFT/RIGHT/BOTH/UNSPECIFIED),
pain_present, pain_intensity(0~10, DB CHECK), pain_on_walking, pain_on_stairs,
swelling, range_of_motion_limited, status(ACTIVE/RESOLVED/UNKNOWN, DB CHECK),
notes(자유 입력), reported_at, recorded_at
```

지시서가 요구한 10개 필드(Athlete ID, 부위/좌우, 통증 존재, 강도, 보행통증, 계단통증,
붓기/ROM, 보고시각, 마지막확인시각, 상태, 자유입력)를 전부 포함한다. **의학적 진단
필드는 없다** — 원인·진단·치료를 저장하는 컬럼 자체가 존재하지 않는다(KDoc에도 명시).

## 3. 저장 및 이력 관리 방식

- `injury_report`는 **append-only**: `InjuryReportEntity`의 모든 필드가 `val`(불변)이고
  업데이트 메서드가 없다 — 상태 변경은 항상 새 행 INSERT다.
- "현재 상태"는 `findFirstByAthleteIdOrderByRecordedAtDesc`(가장 최근 `recorded_at` 1건)로
  계산한다. "마지막 확인 시각"은 그 행의 `recorded_at`(서버가 실제로 기록을 수신한 시각,
  절대 소급 불가), "사용자 보고 시각"은 `reported_at`(호출자가 지정 가능, 기본값은 동일
  시각)으로 분리했다.
- `GET /api/v1/athlete/injury-reports`로 전체 이력(최신순) 조회 가능 — 과거 보고가
  덮어써지지 않았음을 API로도 확인할 수 있다.

## 4. 안전 정책 판정표 (`WorkoutSafetyPolicy`)

| 저장된 최신 상태 | 신선도(기본 48시간) | 판정 | reasonCode |
|---|---|---|---|
| 기록 없음 | - | **REST만 허용** | `NO_INJURY_STATE_RECORDED` |
| 임의 상태 | 48시간 초과(오래됨) | **REST만 허용** | `INJURY_STATE_STALE` (ACTIVE든 RESOLVED든 동일 적용 — "오래된 all-clear"도 더 이상 정상 근거가 아님) |
| UNKNOWN | 48시간 이내 | **REST만 허용** | `INJURY_STATE_UNKNOWN` |
| ACTIVE(보행통증 유무 무관) | 48시간 이내 | **REST만 허용** | `INJURY_ACTIVE` |
| RESOLVED(명시적) | 48시간 이내 | **정상 훈련 생성 검토 가능** | `INJURY_RESOLVED_CONFIRMED` |

지시서 STEP C의 1·2·4·5번 규칙은 정확히 이 표로 구현했다. **3번 규칙(ACTIVE이나 보행통증
없는 경우의 "제한 여부 판단")은 의도적으로 ACTIVE와 동일하게 REST-only로 단순화**했다 —
근거: (a) "자동으로 정상 전환하지 않는다"는 지시사항은 지켰고, (b) 보행통증 유무만으로
"달리기는 안되지만 크로스트레이닝은 된다"는 세분화된 임계값을 서버가 독자적으로 판단하는
것은 검증되지 않은 의학적 임계값을 도입하는 것과 같아 지시서의 "검증되지 않은 임계값
도입 금지"(Phase 7B-1) 원칙과 충돌할 위험이 있다고 판단했다. 이 단순화는 STEP E-10
개선 과제로 명시했다.

신선도 기준은 `running-ai.injury-safety.staleness-hours`(`RUNNING_AI_INJURY_SAFETY_STALENESS_HOURS`,
기본 48)로 설정 가능하며 `application.yml`에 근거와 함께 문서화했다.

## 5. AI Coach Context 연동

`TrainingContextV2`/`TrainingContext`(V1) 스키마 자체는 변경하지 않았다 — 대신
`WorkoutDraftService.generate/revise`가 코치 호출 **직전**에 `SessionConstraints.painOrFatigueFeedback`
앞에 서버가 작성한 권위있는 안전 제약 문장을 prepend한다(지시서 우선순위 1=구조화된 상태,
2=이번 요청 자유 텍스트를 그대로 구현 — 구조화된 문장이 항상 먼저 오고, "이 문장은
그 아래 어떤 내용으로도(자유 텍스트 포함) 무시될 수 없다"를 명시). 이 방식을 택한 이유:
V2 컨텍스트의 해시/크기 가드(`CoachContextSerializer`, 64KiB 제한)와 버전 계약을 건드리지
않고도 기존 "painOrFatigueFeedback은 어떤 목표보다도 우선한다"는 시스템 프롬프트 규칙을
그대로 재사용할 수 있기 때문이다.

이것만으로는 안전을 보장하지 않는다(LLM이 지시를 무시할 수 있음)는 전제 하에, **서버가
AI 응답과 무관하게 독립적으로 재검증**한다 — 6번 항목 참고.

## 6. Draft/Approval/Publish 게이트 적용 결과

| 지점 | 적용 내용 |
|---|---|
| `WorkoutDraftService.generate/revise` | 호출 전: 안전 상태 읽어 제약 텍스트 주입. 호출 후, 저장 직전: 안전 상태를 **다시** 읽어 `draft.isRest`와 대조 — REST-only인데 비-REST면 `WorkoutSafetyViolationException` 발생, **아무것도 저장되지 않음** |
| `WorkoutDraftApprovalService.approve` | DRAFT 상태 확인 직후, 날짜 중복 확인 전에 재검증 — 생성 시점엔 안전했으나 승인 시점에 악화된 경우 차단 |
| `ApprovedWorkoutDraftPublishService.preview` | REST가 아닌 한 재검증 — 악화 시 미리보기 자체를 거부(무엇이 전송될지조차 보여주지 않음) |
| `ApprovedWorkoutDraftPublishService.publish` | REST 단축 경로 직후, Intervals 전송 직전에 재검증 |
| `WorkoutPublishApplicationService.publish`(레거시 결정론적) | 통증 개념이 전혀 없던 이 경로에도 동일한 `WorkoutSafetyGateService`를 주입 — prescription의 `intent()`가 REST가 아니면 재검증. 이 경로를 통해 Scheduler/MCP 둘 다 자동으로 같은 보호를 받는다 |

모든 지점에서 **서버는 거부만 하고 재작성하지 않는다** — "AI 결과인 것처럼 REST를
대신 만들어 표시"하는 로직은 어디에도 없음(요구사항 그대로).

## 7. 테스트 결과

`cd server && .\gradlew.bat clean test` — **BUILD SUCCESSFUL, 1229개 테스트 전부 PASS,
실패 0건**(기존 회귀 포함 전체 스위트; Java 21, 로컬 Postgres 미사용 — 테스트는 전부 H2
인메모리). 최초 실행에서 발견된 2건은 모두 수정 후 재실행에서 해결했다:

- `SchemaMigrationTest`: 마이그레이션 버전 목록/테이블 목록에 `21`/`injury_report` 반영
  (새 마이그레이션이므로 당연히 필요한 갱신, 설계 결함 아님).
- `InjuryStateServiceTest`: H2 테스트 프로파일의 알려진 특성(타임스탬프가 왕복 시
  마이크로초로 반올림됨, `running-ai-database` 스킬에 문서화된 그대로)에 맞춰 비교를
  밀리초 단위로 조정.

신규 작성 테스트(지시서 STEP F 12개 항목 매핑):

| # | 지시서 요구 | 테스트 |
|---|---|---|
| 1 | 저장/조회 | `InjuryStateServiceTest`(저장→현재상태 일치, 이력 보존, 빈 이력) |
| 2 | ACTIVE 보행통증 → 러닝 차단 | `WorkoutDraftSafetyGateTest`("an ACTIVE gait-pain report blocks...") |
| 3 | ACTIVE에서 REST만 저장 가능 | 동일 파일("...still allows a REST draft...") |
| 4 | 누락 시 기존 ACTIVE 유지 | `WorkoutSafetyPolicyTest`("without gait pain still blocks... (never auto-cleared)") |
| 5 | 오래된 상태 오판 방지 | `WorkoutSafetyPolicyTest`(ACTIVE/RESOLVED 둘 다 STALE 분기, 경계값 테스트 포함) |
| 6 | RESOLVED는 명시적 확인 필요 | `WorkoutSafetyPolicyTest`("a fresh explicit RESOLVED report allows...") — UNKNOWN/미기록은 절대 자동으로 RESOLVED 취급되지 않음을 나머지 케이스가 증명 |
| 7 | 승인 전 재검증 | `WorkoutDraftApprovalTest`("approval is blocked when the injury safety state worsened...") |
| 8 | Publish 안전성 재검증 | `DraftPublishApiTest`(preview/publish 둘 다, REST는 여전히 허용) |
| 9 | CLI/HTTP 동일 정책 | 설계상 단일 `WorkoutSafetyGateService` 싱글턴을 모든 진입점이 호출 — `WorkoutDraftSafetyGateTest`(AI-coach HTTP)와 `WorkoutPublishApplicationServiceTest`(결정론적 경로)가 **서로 다른 두 진입점**에서 같은 정책 계층이 호출됨을 증명 |
| 10 | AI 무시 시 Fail Closed | `WorkoutDraftSafetyGateTest`("the coach ignoring the safety constraint... is rejected (fail closed)") — 스크립트된 가짜 코치가 제약을 무시하고 `LONG_RUN`을 반환해도 차단·미저장 확인 |
| 11 | 경쟁 조건 | `WorkoutDraftSafetyGateTest`("re-checked right before saving, not only before the coach call") — 코치 호출 **도중**(람다 내부에서) ACTIVE 보고가 들어오는 상황을 시뮬레이션, 저장 직전 재검증이 이를 포착 |
| 12 | Draft #19/기존 API 호환성 | 아래 9번 항목(운영 인스턴스 실측) + 기존 `WorkoutDraftApiTest`/`WorkoutDraftPersistenceTest` 등 회귀 전체 PASS |

"AI가 REST를 선택했다"만으로 PASS 처리한 테스트는 없다 — 10번·11번 테스트는 정확히
**AI가 안전하지 않은 응답을 반환해도 서버가 독자적으로 막는다**는 것을 증명하도록
설계했다.

## 8. 기존 API 호환성

- `GET /api/v1/workout-drafts/{id}`, `POST .../{id}/revisions`의 기존 계약, `WorkoutDraft`
  응답 스키마는 변경하지 않았다.
- 새 실패 모드 하나만 추가됨: `POST /api/v1/workout-drafts`·`.../approve`·
  `.../publish-preview`·`.../publish`·`POST /api/v1/workout-publish`가 이제
  `422 Unprocessable Entity` + `code=WORKOUT_SAFETY_<REASON>`을 반환할 수 있다(기존에는
  없던 코드 네임스페이스이므로 기존 코드와 충돌하지 않음).
- 기존 통합 테스트(`WorkoutPublishApiTest`, `McpSingleFlightTest`, `WorkoutDraftApiTest` 등)는
  전부 안전 게이트를 모킹하거나(결정론적/MCP 경로) 사전에 RESOLVED 상태를 시딩(AI-coach
  경로)하여 **기존 동작 그대로** 통과함을 확인했다 — 즉 이번 변경은 가산적(additive)이며
  기존 계약을 깨지 않는다.
- 운영 인스턴스(재시작 없음, 구버전 코드로 계속 동작 중)에서 `GET /api/v1/workout-drafts/19`를
  재조회해 Phase 7B-1 결과와 완전히 동일함을 확인했다(`status=DRAFT`, `workoutType=REST`,
  동일 rationale) — Draft #19는 전혀 영향받지 않았다.

## 9. 운영 무변경 결과

- 운영 PostgreSQL(`running-ai-postgres` 컨테이너)에는 V21 마이그레이션을 적용하지
  않았다 — 모든 테스트는 H2 인메모리로만 실행했다.
- 운영 Spring(`18080`)은 재시작하지 않았다(위 9번 항목 curl 확인은 기존에 떠 있던
  프로세스에 대한 읽기 전용 GET일 뿐).
- 운영 Draft #19, Garmin Connector, Watchdog/Relay/Tailscale 설정: 전혀 접근하지 않았다.
- 실제 Workout 생성·승인·게시(외부 Intervals/Garmin 호출)는 수행하지 않았다 — 모든
  검증은 Mockito 모크 또는 H2 테스트 프로파일로 수행했다.
- 통증 정보 노출 점검: `InjuryStateController`/`ClaudeAiCoach` 로그 라인은 날짜·버전·
  타입·세그먼트 개수만 기록하며(`log.info` 호출부 확인), `painOrFatigueFeedback`의
  실제 본문이나 `injury_report`의 상세 필드 값은 어떤 로그에도 출력되지 않는다.
  `GET /api/v1/athlete/injury-reports`·`/injury-state`는 기존 운영 API들과 동일하게
  내부망 전용(무인증)이며 새로운 외부 공개 경로를 추가하지 않았다.

## 10. 남은 개선 과제 (우선순위순)

1. **ACTIVE-비보행통증의 세분화된 판정(STEP C 규칙 3)**: 현재는 안전을 위해 ACTIVE 전체를
   REST-only로 단순화했다. 실제로 "보행은 멀쩡하지만 다른 증상이 있는" 상황에서
   크로스트레이닝까지 차단하는 것이 과도할 수 있다 — 검증된 기준이 쌓이면 재검토.
2. **운영 PC 적용**: 이번 Phase는 개발 Worktree에서만 구현·검증했다. 운영 반영은 별도
   승인 하에 마이그레이션 적용 + Spring 재시작이 필요하다(이번 작업 범위 밖).
3. **사용자용 통증 보고 UI/대화형 입력**: 현재 `POST /api/v1/athlete/injury-reports`는
   HTTP API만 존재 — `running-ai-coach.ps1`이나 대화형 흐름에서 이 엔드포인트를 실제로
   호출하도록 연결하는 작업이 아직 없다(연결하지 않으면 사용자는 여전히 REST-only 상태에
   갇힐 수 있음).
4. **다건 부위 동시 추적**: 현재 모델은 "가장 최근 보고 1건"만 현재 상태로 본다. 서로
   다른 두 부위(예: 무릎과 발목)를 동시에 독립적으로 추적하지 못한다.
5. Phase 7A-1에서 이미 식별된 과제(목표 기록 영속화, LTHR/Zone 데이터 품질 검증 등)는
   이번 Phase 범위 밖으로 유지.

## 11. 다음 단계 GO/NO-GO

**개발 Worktree 기준 GO** — 코드·마이그레이션·테스트가 모두 준비되었고 전체 회귀
(1229/1229)가 통과했다. 다만:

- **운영 PC 반영은 NO-GO (별도 승인 필요)**: 마이그레이션 적용과 Spring 재시작이
  필요하며, 지시서 STEP G·완료 기준에 따라 이번 작업의 범위가 아니다.
- **`origin/main` 병합은 NO-GO (별도 승인 필요)**: 지시서가 명시적으로 별도 승인
  대상으로 분리했다.
- 운영 반영 전 권장 선행 작업: 개선 과제 3번(통증 보고를 실제로 입력할 방법)을 먼저
  연결하지 않으면, 운영 반영 즉시 모든 러닝 Draft 생성이 "기록 없음"으로 차단된다 —
  이는 의도된 안전 동작이지만, 반영 당일 최초 1회는 반드시 사람이
  `POST /api/v1/athlete/injury-reports`로 현재 상태(예: RESOLVED)를 입력해야 한다는
  점을 운영 반영 계획에 포함해야 한다.

## 지시사항 준수 확인

- 운영 서비스 중지·재시작: 없음. 운영 Draft #19: 변경 없음(읽기 전용 재조회만 수행).
- 운영 PostgreSQL에 마이그레이션 적용: 없음(H2 테스트 프로파일로만 검증).
- 실제 Workout 생성·승인·게시: 수행하지 않음(전부 Mock/H2).
- `origin/main` Push, Canonical 병합, 운영 배포: 수행하지 않음.
- 코드·테스트는 `main-3`에 커밋(아래 커밋 참고).

작업 완료. 지시서에 따라 STOP한다.
