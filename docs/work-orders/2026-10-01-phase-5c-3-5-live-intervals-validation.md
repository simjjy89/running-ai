# Phase 5C-3.5 Live Intervals Validation — work order (verbatim instruction)

기준 commit: `138ae4e`

사전 확인: `git status`, `git pull --ff-only origin main`, working tree clean, `INTERVALS_API_KEY` 존재 여부만
확인(값 출력 금지), `INTERVALS_ATHLETE_ID`는 설정하지 않고 기본값 `0`(API key owner shortcut) 사용 — 실제 athlete id를
찾지 않는다.

## 목적

이번 Phase는 기능 개발이 아니라 Phase 5C-3에서 구현한 Intervals publisher의 **실제 Intervals.icu server contract 검증**이 목적.

검증할 핵심:

- authentication
- 하루 단위 WORKOUT lookup
- CREATE
- `external_id` 저장 및 readback
- description readback
- scheduled date readback
- remote event id
- 동일 workout 재게시 시 NO_CHANGE
- 변경 workout 재게시 시 같은 remote event UPDATE
- 다시 동일 게시 시 NO_CHANGE
- duplicate event 없음

## 중요한 원칙

**실제 mismatch를 관찰하기 전에는 production 코드를 수정하지 않는다.**

순서: `live observation → mismatch 분류 → 필요한 경우 최소 수정 → regression → live 재검증`

추측으로 marker 전략, DTO, payload, normalization을 먼저 수정하지 않는다.

## 안전한 날짜 확인

쓰기 전에 실제 Intervals calendar를 **read-only GET**으로 조회. 테스트 날짜에는 RunningAI-owned WORKOUT 없음, foreign/unmanaged
WORKOUT 없음이어야 한다. 기존 WORKOUT이 하나라도 있으면 그 날짜에는 쓰지 않는다. 사용자의 실제 예정 훈련을 덮어쓰거나 삭제하지 않는다.

## Synthetic workout

Phase 5C-2에서 이미 검증된 가장 단순한 valid `RenderedIntervalsWorkout` fixture 사용. 새 Intervals workout syntax를 만들지 않는다.

## 검증 순서

1. Read-only connectivity — authentication 및 조회만. 실패하면 write 시도 금지.
2. First publish — 안전한 빈 날짜에 synthetic workout publish. 기대: CREATED, verified=true, remote event id 반환.
   readback에서 `external_id`, scheduled date, description, event id, type, name 확인. **최우선: `external_id` round-trip.**
   실제 external_id 값, athlete id, event id, API key는 결과 문서에 기록하지 않고 `external_id round-trip = PASS/FAIL` 형태로만 기록.
3. Second identical publish — 기대: NO_CHANGE, 추가 POST/PUT 없음, remote event 1개, event id 동일.
4. Changed workout — 같은 athlete/date/logical identity, description(예: duration)만 소폭 변경. 기대: UPDATED, verified=true,
   remote event id는 최초 CREATED와 동일. 새 event 생성 시 실패.
5. Final identical publish — 변경된 workout 재게시. 기대: NO_CHANGE, remote event 여전히 1개.

최종 성공 sequence: `CREATE → NO_CHANGE → UPDATE(same remote ID) → NO_CHANGE`

## external_id failure 정책

POST는 성공했지만 readback에서 `external_id`가 없음/null/변경됨이면 즉시 두 번째 POST를 보내지 않는다. remote event id로 기존
event를 식별하고 실제 server contract 차이를 먼저 기록한다. 즉시 하지 말 것: description marker 전환, DB mapping table 추가,
duplicate create, architecture redesign. 먼저 실제 현상 분석.

## Description normalization

local rendered text와 remote description 비교 (exact / CRLF-LF / trailing newline / 기타). 실제 관찰된 normalization만 코드에 허용.
모든 whitespace 제거나 대소문자 무시 같은 광범위 normalization 금지.

## type / name

Phase 5C-3의 `type=Run` 및 name이 실제 server에서 어떻게 저장/readback되는지 확인. 다르게 반환되면 먼저 기록하고 필요한 최소 수정만.

## 수정이 필요한 경우

live evidence가 있을 때만 최소 변경 허용: request/response DTO field mapping, actual `external_id` mapping, minimal newline
normalization, server가 요구하는 type/name, request payload contract correction.

금지: DB migration, new persistence table, scheduler, controller, Garmin integration, renderer rewrite, legacy 삭제.

수정했다면 해당 live mismatch를 재현하는 regression test 추가 후 `server\gradlew.bat clean test` 실행. baseline = 396 / 396 PASS.

## Credential / secret 정책

절대 출력/기록 금지: INTERVALS_API_KEY 값, Authorization header, athlete identifier, full external_id, remote event id, cookies,
full personal API response. 로그 및 결과 문서에는 PASS/FAIL 상태만.

## Temporary runner

임시 runner/script 사용 가능, 최종 commit 전 반드시 제거. public controller / production test endpoint 추가 금지.

## Cleanup

테스트 event를 자동 삭제하는 production 기능 구현 금지. 정리가 필요하면 이번 validation에서 만든 synthetic event임을 확실히 확인한 후
별도 처리하고, 결과 문서에는 retained/deleted 상태만 기록.

## 성공 시 Verification Status

- Intervals renderer = UNIT_VERIFIED
- Intervals publisher = SERVER_VERIFIED
- Intervals server readback = SERVER_VERIFIED

Garmin 상태 변경 금지: Pace Garmin = UNRESOLVED, %LTHR Garmin = ASSUMED, Treadmill cue Garmin = DEVICE validation required.

## 완료 보고 형식

Environment / Connectivity / First publish / Idempotency / Update / Server normalization / Code changes / Tests /
Verification status / Cleanup / Git 항목. 실제 secret/identifier 값은 보고하지 않는다.

Production 코드나 문서가 변경됐다면 diff/secrets check 후 commit/push. 변경이 필요 없었다면 억지 commit을 만들지 않는다.
Phase 5C-4는 시작하지 않는다.
