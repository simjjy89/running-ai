# RunningAI Phase 6G.1 — Publishing Mode Mutual Exclusion Guard

Phase 6G까지 완료된 현재 main을 기준으로 작업한다.

이번 작업은 아주 작은 safety hardening이다.

현재 RunningAI에는 두 개의 workout publishing path가 존재한다.

Legacy deterministic publishing:

`WORKOUT_PUBLISHING_ENABLED`

AI approved-draft publishing:

`RUNNING_AI_DRAFT_PUBLISHING_ENABLED`

두 path 모두 같은 athlete/date의 Intervals workout event를 대상으로 할 수 있으므로 절대로 동시에 활성화되어서는 안 된다.

운영 문서에만 의존하지 말고 application configuration invariant로 강제한다.

## 요구사항

두 publishing switch가 동시에 true이면 application startup을 fail-fast 한다.

예:

`WORKOUT_PUBLISHING_ENABLED=true`
`RUNNING_AI_DRAFT_PUBLISHING_ENABLED=true`

→ application startup FAIL

명확한 메시지:

`Legacy workout publishing and AI draft publishing cannot be enabled at the same time`

또는 repository error/style에 맞는 동등한 문구.

한쪽만 true:

→ startup 정상

둘 다 false:

→ startup 정상

## 중요

다음은 변경하지 않는다.

- existing legacy publisher semantics
- Phase 6G approved draft publisher semantics
- scheduler enable/default
- MCP
- Claude
- Intervals publisher
- Garmin
- WorkoutDraft lifecycle

두 switch의 mutual exclusion만 추가한다.

## 구현 방향

Spring configuration/property 계층에서 처리한다.

가능하면 별도 safety configuration/validator bean으로 분리한다.

비즈니스 publish 호출이 발생한 뒤에야 실패하도록 만들지 말고, 잘못된 configuration 자체를 startup 시점에서 발견한다.

다만 Spring context를 의도적으로 올리지 않는 unit test들이 영향을 받지 않도록 repository convention에 맞게 구현한다.

## Tests

최소:

1. legacy=false / draft=false → PASS
2. legacy=true / draft=false → PASS
3. legacy=false / draft=true → PASS
4. legacy=true / draft=true → startup/config validation FAIL
5. legacy scheduler default OFF 유지
6. draft publishing default OFF 유지
7. 기존 legacy publish tests regression
8. Phase 6G publish tests regression

전체:

`gradlew clean test`

GREEN 필수.

현재 baseline은 851 tests다.

Python connector는 변경하지 않는다.

실제 Intervals/Garmin write는 절대 수행하지 않는다.

## Documentation

다음 instruction/result 문서를 작성한다.

`docs/work-orders/2026-10-02-phase-6g1-publishing-mode-mutual-exclusion-instruction.md`

`docs/work-orders/2026-10-02-phase-6g1-publishing-mode-mutual-exclusion-result.md`

CLAUDE.md의 publishing architecture에도:

`Legacy publishing XOR AI Draft publishing`

규칙을 명시한다.

## Git

논리적 commit 후 전체 테스트가 GREEN이고 working tree가 clean이면 origin/main으로 safe fast-forward push한다.

force push 금지.

최종 보고:

- 구현 위치
- fail-fast 방식
- 4개 switch combination 결과
- test count
- default switch 상태
- actual external writes = 0
- commit SHA
- push 결과
- working tree clean 여부
