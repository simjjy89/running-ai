# RunningAI Phase 6F.1 — First-class REST Workout Support

현재 Phase 6F까지 완료되어 있고 working tree는 clean 상태다.

이번 작업의 목적은 Claude AI Coach가 정상적으로 `REST`를 선택했을 때 이를 오류가 아닌 정식 WorkoutDraft로 표현할 수 있도록 도메인/검증/저장 구조를 수정하는 것이다.

현재 문제:

- Claude eval 20건 중 2건에서 athlete가 exhaustion을 보고하자 Claude가 REST를 선택함
- REST 결과:
  - workoutType = REST
  - totalDurationMinutes = 0
  - segments = []
- 기존 Phase 6E validator는:
  - 최소 1개 segment
  - 최소 5분
  을 요구하여 REST를 reject함
- DB schema 역시 REST 표현을 전제로 하지 않았을 가능성이 있음

이것은 Claude 판단 오류가 아니라 WorkoutDraft domain limitation으로 본다.

## 핵심 정책

REST는 first-class workout prescription으로 지원한다.

정상 REST Draft:

```text
workoutType = REST
totalDurationMinutes = 0
segments = []
```

REST를 표현하기 위해 가짜 5분 운동이나 dummy segment를 만들지 않는다.

## Validation

REST:

```text
workoutType == REST
→ totalDurationMinutes == 0
→ segments must be empty
```

REST가 아닌 workout:

```text
totalDurationMinutes >= 5
segments must not be empty
```

기존 pace/HR/speed/range validation은 운동 segment가 있는 경우 그대로 유지한다.

Spring은 REST 선택 자체를 결정하지 않는다.
ClaudeAiCoach가 REST를 선택할 수 있고 Spring은 구조적으로 유효한지만 검증한다.

## Persistence

WorkoutDraft persistence가 REST를 정상 저장할 수 있는지 확인한다.

필요하면 Flyway migration 추가.

검증:

- REST draft 저장 가능
- 조회 가능
- versioning 가능
- revision 가능

예:

```text
v1 = REST
user revision → "몸은 괜찮아졌어. 30분 easy로 바꿔줘"
v2 = EASY
v1 = SUPERSEDED
v2 = DRAFT
```

반대도 가능해야 한다.

```text
v1 = EASY
revision → fatigue/exhaustion
v2 = REST
```

## JSON Contract

REST Draft JSON도 기존 WorkoutDraft schema를 그대로 사용한다.

예:

```json
{
  "workoutType": "REST",
  "totalDurationMinutes": 0,
  "segments": [],
  "assessment": {
    "rationale": "..."
  }
}
```

별도의 fake workout object를 만들지 않는다.

## Claude Prompt

현재 prompt에서 REST가 허용된 workout type이면 유지한다.

REST 선택 시 다음을 명확히 하도록 prompt를 보강해도 된다.

```text
If REST is the selected workout:
- totalDurationMinutes must be 0
- segments must be an empty array
- do not invent recovery or warm-up segments
```

단 Claude가 언제 REST를 선택해야 하는지 Spring rule이나 hard-coded threshold로 정하지 않는다.

## API

기존 draft API에서 REST Draft가 정상 반환되어야 한다.

검증:

- POST workout draft generation
- GET draft
- POST revision

REST도 일반 Draft처럼 처리한다.

이번 Phase에서 approve/publish는 구현하지 않는다.

## Future Publish Semantics

이번 Phase에서는 publish하지 않지만 향후 의미를 고려한다.

REST Draft는 향후:

```text
APPROVED REST
→ no Intervals workout event
→ no Garmin workout
→ SKIPPED_REST_DAY
```

형태로 연결될 수 있어야 한다.

지금은 enum/status를 미리 추가할 필요는 없고 domain이 이를 막지 않도록만 설계한다.

## Tests

최소 추가/수정:

1. valid REST draft passes validator
2. REST with duration > 0 fails
3. REST with segments fails
4. non-REST with duration 0 fails
5. non-REST with empty segments fails
6. REST persistence
7. REST JSON serialization/deserialization
8. REST API response
9. EASY → REST revision
10. REST → EASY revision
11. REST version superseding
12. existing workout validation regression

## Claude Eval

Phase 6F에서 실행한 20개 live coach eval을 다시 수행한다.

특히 기존 실패:

- scenario 08
- scenario 20

검증.

목표:

```text
20 / 20 valid WorkoutDraft
```

단 Claude 결과 자체가 다른 이유로 invalid하면 억지로 PASS 처리하지 않는다.

결과를 정확히 기록한다.

## Regression

반드시 전체 테스트 실행.

현재 기준:

```text
Python: 88 passed
Spring/Kotlin: 758 passed
```

이번 작업이 Python connector를 변경하지 않는다면 Python test는 최소 regression 확인만 한다.

Spring/Kotlin 전체 suite GREEN 필수.

## Safety

이번 Phase에서도 절대 실행하지 않는다.

- Intervals write
- Garmin workout write
- workout publish
- approve workflow
- automatic scheduler enable

REST support는 Draft domain까지만 수정한다.

## Documentation

작업 시작 시:

```text
docs/work-orders/2026-10-02-phase-6f1-first-class-rest-workout-instruction.md
```

완료 후:

```text
docs/work-orders/2026-10-02-phase-6f1-first-class-rest-workout-result.md
```

작성.

## Git

논리적 commit 생성.

예:

```text
feat: support rest workout drafts
test: validate rest workout lifecycle
docs: record Phase 6F.1 result
```

최종 보고:

1. 변경 파일
2. validator 변경
3. DB migration 여부
4. REST JSON example
5. persistence/versioning 결과
6. revision 결과
7. Phase 6F eval 20건 재실행 결과
8. 전체 test 결과
9. external write 0 확인
10. commit SHA
11. working tree clean 여부

구현, 테스트, live Claude eval까지 완료하고 최종 보고해줘.
