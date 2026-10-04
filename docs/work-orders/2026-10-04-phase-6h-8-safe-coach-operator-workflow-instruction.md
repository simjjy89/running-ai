# RunningAI Phase 6H-8 — Safe Coach CLI / Operator Workflow

## 1. 목표

현재 AI Coach 실제 운영 흐름은 정상 동작하지만 여러 PowerShell 명령을 수동으로 실행해야 한다.

현재:

```text
generate
→ JSON 직접 검토
→ revise
→ JSON 직접 검토
→ approve
→ publish-preview
→ controlled publish script
→ YES
→ runtime restart
→ publish
→ safe-mode restart
```

Phase 6H-8에서는 이를 하나의 안전한 Windows 운영 CLI로 통합한다.

최종 UX:

```text
running-ai-coach.ps1
        ↓
Generate / Resume
        ↓
사람이 읽을 수 있는 Draft 표시
        ↓
[R] Revise
[A] Approve
[Q] Quit
        ↓
Publish Preview
        ↓
YES human gate
        ↓
Controlled Publish
        ↓
Intervals read-back verification
        ↓
Publishing OFF
        ↓
RunningAI safe mode
```

핵심 원칙:

**AI는 Draft를 만든다.
사람이 승인한다.
사람이 최종 YES를 입력해야만 외부 write가 발생한다.**

---

# 2. Baseline

Phase 6H-7.2 완료 상태에서 시작한다.

검증 baseline:

```text
Spring H2          1192 / 1192
PostgreSQL 17      1192 / 1192
PowerShell           32 / 32
Python              134 / 134
```

6H-7.2 완료 marker:

```text
PHASE_6H_7_2_WINDOWS_UTF8_API_PATH_READY
```

작업 시작 시 실제 local HEAD를 기록한다.

```powershell
git status
git log -1 --oneline
git rev-parse HEAD
git rev-parse origin/main
```

보고서에는 실제 SHA를 기록한다.

---

# 3. Worktree / live checkout 안전성

개발은 현재 agent worktree:

```text
C:\Users\simjy\orca\workspaces\running-ai-github\main-3
```

또는 별도 worktree에서 진행 가능하다.

실제 운영 checkout:

```text
C:\running-ai-github
```

을 테스트 완료 전 수정하지 않는다.

다음 명령 금지:

```text
git reset --hard
git clean -fd
force push
```

legacy:

```text
C:\running-ai
```

는 절대 수정하지 않는다.

---

# 4. 특별 주의 — 기존 untracked publish script

현재 live checkout에만 다음 untracked 파일이 존재할 수 있다.

```text
C:\running-ai-github\
scripts\windows\publish-approved-draft-controlled.ps1
```

이 파일에는 실제 Main PC controlled-publish 검증 과정에서 얻은 중요한 운영 로직이 있다.

절대 삭제부터 하지 않는다.

먼저 live checkout에서 존재 여부를 확인하고:

```powershell
git status --short
```

존재하면 `.runtime/backups/phase-6h-8/` 아래에 백업한다.

예:

```text
.runtime/backups/phase-6h-8/
publish-approved-draft-controlled.pre-6h-8.ps1
```

그 후 새 tracked implementation과 비교한다.

`git clean`으로 처리하지 않는다.

---

# 5. Work order

본 지시서 저장:

```text
docs/work-orders/
2026-10-04-phase-6h-8-safe-coach-operator-workflow-instruction.md
```

완료 결과:

```text
docs/work-orders/
2026-10-04-phase-6h-8-safe-coach-operator-workflow-result.md
```

---

# 6. 범위

이번 Phase의 중심은 Windows operator workflow다.

권장 신규 tracked script:

```text
scripts/windows/running-ai-coach.ps1
```

기존 controlled publisher도 tracked 코드로 편입한다.

```text
scripts/windows/publish-approved-draft-controlled.ps1
```

공용 기능은:

```text
scripts/windows/RunningAI.Common.ps1
```

또는 별도의 작은 operator helper 파일로 분리 가능하다.

---

# 7. 서버 production code

원칙적으로 **서버 production code 변경 없음**.

현재 API로 이미 필요한 모든 동작이 가능하다.

```text
POST /api/v1/workout-drafts

GET  /api/v1/workout-drafts/{id}

POST /api/v1/workout-drafts/{id}/revisions

POST /api/v1/workout-drafts/{id}/approve

GET  /api/v1/workout-drafts/{id}/publish-preview

POST /api/v1/workout-drafts/{id}/publish
```

CLI 편의를 이유로 새 server endpoint를 만들지 않는다.

정말 필요한 경우에만 별도 근거를 남긴다.

---

# 8. Phase 6H-7.2 UTF-8 helper 재사용

6H-7.2에서 만든 UTF-8-safe JSON HTTP helper를 반드시 재사용한다.

중요 invariant:

```text
PowerShell object
→ JSON
→ UTF-8 byte[]
→ Write-Output -NoEnumerate
→ HTTP request
```

response:

```text
Invoke-WebRequest
→ RawContentStream
→ explicit UTF-8 decode
→ ConvertFrom-Json
```

`Invoke-RestMethod -Body ($object | ConvertTo-Json)` 방식으로 되돌아가지 않는다.

---

# 9. UTF-8 helper duplication 금지

6H-7.2에 이미 존재하는 함수 이름과 구현을 먼저 조사한다.

동일 기능을:

```text
running-ai-coach.ps1
publish-approved-draft-controlled.ps1
```

안에 복사하지 않는다.

공용 helper 한 곳만 source of truth로 유지한다.

---

# 10. CLI invocation

신규 workflow의 기본 사용 예:

```powershell
.\scripts\windows\running-ai-coach.ps1 `
    -Date 2026-10-04 `
    -AvailableMinutes 35 `
    -Environment OUTDOOR `
    -Goal "하프마라톤 대비 가벼운 테이퍼 훈련"
```

Resume:

```powershell
.\scripts\windows\running-ai-coach.ps1 `
    -DraftId 10
```

---

# 11. Parameters

최소 지원:

```text
-Date
-AvailableMinutes
-Environment
-Goal
-UserFeedback
-PainOrFatigueFeedback
-DraftId
-BaseUrl
```

기본 BaseUrl:

```text
http://127.0.0.1:8080
```

---

# 12. Goal 입력

`-Goal`은 한글을 포함해 UTF-8-safe해야 한다.

멀티라인 목적이 필요하면 optional:

```text
-GoalFile
```

지원 가능.

파일은 raw bytes를 UTF-8로 명시 decode한다.

Windows PowerShell의 자동 encoding 추측에 의존하지 않는다.

---

# 13. Generate vs Resume

다음 둘은 동시에 허용하지 않는다.

```text
-DraftId
-Date / Goal 기반 신규 생성
```

`-DraftId`가 있으면 기존 Draft를 조회해 resume한다.

없으면 신규 Draft를 생성한다.

---

# 14. Runtime health preflight

첫 동작:

```text
GET /actuator/health
```

또는 기존 RunningAI health helper를 사용한다.

`UP`이 아니면 Draft 생성/수정/승인을 시도하지 않는다.

기본 동작은:

```text
RunningAI not running
→ 명확한 오류
→ external write 0
```

이다.

---

# 15. 자동 runtime start

기본적으로 CLI가 서버를 몰래 시작하지 않는다.

optional:

```text
-StartIfNeeded
```

를 지원할 수 있다.

단 지원한다면 기존:

```text
start-running-ai.ps1
```

만 호출한다.

Docker/Spring 프로세스를 직접 구현하지 않는다.

---

# 16. Draft display

Raw JSON만 출력하지 않는다.

사람이 승인 판단을 할 수 있도록 요약 view를 제공한다.

예:

```text
Draft #12 / v2 / DRAFT
Date     : 2026-10-04
Title    : Pre-Race Sharpening
Type     : INTERVAL
Duration : 35 min

Recovery:
  Strong readiness...

Load:
  Light recent load...

Workout:
  Warm Up 10m
    HR 70-80% LTHR

  5 x
    Main 2m
      Pace 4:35-4:45/km
    Recovery 1m
      HR 65-75% LTHR

  Cool Down 10m
    HR 65-75% LTHR

Warnings:
  ...
```

---

# 17. Numeric target 표시

최소 표시:

```text
primaryTargetType
pace
%LTHR
treadmill speed
incline
repetitions
recovery
```

absolute BPM이 존재하면 숨기지 않는다.

기존 Draft라면 표시하되:

```text
ABSOLUTE BPM — NOT PUBLISHABLE
```

처럼 명확히 보여준다.

---

# 18. Draft 상태 표시

반드시:

```text
DRAFT
SUPERSEDED
APPROVED
```

를 눈에 띄게 표시한다.

---

# 19. DRAFT 메뉴

DRAFT 상태에서는:

```text
[R] Revise
[A] Approve
[Q] Quit
```

만 허용한다.

`PUBLISH` 메뉴는 표시하지 않는다.

---

# 20. Revision

`R` 선택:

```text
Revision request:
```

를 입력받는다.

한글 UTF-8-safe path를 사용한다.

API:

```text
POST /api/v1/workout-drafts/{id}/revisions
```

새 version을 받으면 이전 Draft 대신 새 Draft를 표시한다.

---

# 21. Revision loop

사용자가 원하는 만큼:

```text
review
→ revise
→ review
→ revise
```

할 수 있다.

자동 revision 횟수 제한을 만들 필요는 없다.

Claude를 자동 반복 호출하지 않는다.

항상 사람이 `R`을 선택해야 한다.

---

# 22. Approve gate

Approve는 irreversible lifecycle action이다.

따라서 단순 `A` 누름 후 바로 API를 호출하지 않는다.

최종 확인:

```text
Type APPROVE to approve Draft #12:
```

정확히:

```text
APPROVE
```

가 입력되어야 한다.

---

# 23. Approval 후

승인 성공 시:

```text
status=APPROVED
approvalId
```

를 표시한다.

즉시 publish하지 않는다.

먼저 자동으로 publish-preview를 호출한다.

---

# 24. Publish preview

API:

```text
GET /api/v1/workout-drafts/{id}/publish-preview
```

이 단계는 publish switch가 false여도 동작해야 한다.

외부 Intervals call 0.

---

# 25. Preview display

표시:

```text
publishable
externalWriteRequired
expectedOutcome
structuredStepCount
unpublishableReasons
publication
```

그리고:

```text
renderedWorkoutText
```

전체를 보여준다.

---

# 26. Unpublishable

다음이면:

```text
publishable=false
```

즉시 중단한다.

외부 write 0.

CLI가 Draft를 자동 수정하지 않는다.

```text
Draft approved but cannot be published losslessly.
No external write performed.
```

를 명확하게 출력한다.

---

# 27. Already published

preview의:

```text
publication != null
```

이면 새로운 external publish를 시도하지 않는다.

기존 publication을 표시하고 종료한다.

이 경우 publish switch를 켜지 않는다.

runtime restart도 하지 않는다.

---

# 28. REST day

preview가:

```text
restDay=true
externalWriteRequired=false
expectedOutcome=SKIPPED_REST_DAY
```

이면 Intervals publish를 하지 않는다.

기본 operator workflow에서는:

```text
Approved REST day.
No external workout is required.
```

로 완료 처리한다.

단 REST publication DB 기록이 반드시 필요하다는 별도 requirement가 생기면 이후 Phase로 다룬다.

이번 Phase에서는 외부 write가 필요 없는 REST 때문에 publish switch를 켜고 runtime을 재기동하지 않는다.

---

# 29. External publish human gate

실제 external write가 필요한 경우에만:

```text
This will write the approved workout to Intervals.icu.

Type exactly YES to publish:
```

를 표시한다.

정확한:

```text
YES
```

외에는 모두 취소.

---

# 30. Publish gate 메시지

Windows PowerShell 5.1 source encoding 문제를 피하기 위해 safety-critical runtime prompt는 ASCII/English를 권장한다.

특히:

```text
Type exactly YES to publish:
Publishing cancelled.
Controlled publish completed.
```

등은 ASCII로 유지한다.

---

# 31. Controlled publish

기존 live untracked script의 검증된 동작을 tracked implementation으로 옮긴다.

단 이번에 구조를 개선한다.

---

# 32. `.env` 직접 수정 최소화

기존 controlled script는 `.env`를 편집했다.

Phase 6H-8에서는 우선 다음 방법을 검토한다.

```text
current PowerShell process env
→ RUNNING_AI_DRAFT_PUBLISHING_ENABLED=true
→ other publishing switches=false
→ start-running-ai.ps1
```

`start-running-ai.ps1`는 existing process environment를 `.env`보다 우선하므로 이 방식을 사용할 수 있다.

가능하다면 **`.env`를 수정하지 않는다.**

---

# 33. Publish runtime environment

실제 publish 직전에만:

```text
RUNNING_AI_DRAFT_PUBLISHING_ENABLED=true

WORKOUT_PUBLISHING_ENABLED=false
WORKOUT_PUBLISHING_SCHEDULER_ENABLED=false
RUNNINGAI_MCP_ENABLED=false
```

로 process environment를 설정한다.

---

# 34. 금지

절대:

```text
WORKOUT_PUBLISHING_ENABLED=true
WORKOUT_PUBLISHING_SCHEDULER_ENABLED=true
RUNNINGAI_MCP_ENABLED=true
```

로 변경하지 않는다.

Draft controlled publishing과 legacy publisher는 별개다.

---

# 35. Runtime restart

controlled publish에 필요한 경우:

```text
stop-running-ai.ps1
→ start-running-ai.ps1
→ actuator health UP
```

순서.

자체적으로 Spring/connector 프로세스를 kill/start하지 않는다.

기존 runtime scripts만 사용한다.

---

# 36. Preview revalidation after restart

publish-enabled runtime이 올라온 후 **publish 요청 전에 다시 preview**한다.

즉:

```text
preview before gate
        ↓
YES
        ↓
runtime restart
        ↓
preview again
        ↓
publish
```

---

# 37. TOCTOU protection

두 preview를 비교한다.

최소:

```text
draftId
approvalId
date
workoutType
structuredStepCount
renderedWorkoutText
```

가 동일해야 한다.

다르면:

```text
PREVIEW_CHANGED_AFTER_RESTART
```

로 중단.

external write 0.

---

# 38. Preview hash

권장:

```text
SHA-256(renderedWorkoutText)
```

를 계산한다.

before / after hash가 동일해야 한다.

사용자에게 hash 전체를 보여줄 필요는 없지만 결과 보고에는 테스트한다.

---

# 39. Actual publish

모든 gate 통과 후 딱 한 번:

```text
POST /api/v1/workout-drafts/{id}/publish
```

한다.

CLI level retry 금지.

---

# 40. Publish success

정상:

```text
outcome=PUBLISHED
verified=true
```

이어야 한다.

Intervals operation:

```text
CREATED
UPDATED
NO_CHANGE
```

모두 정상 결과로 취급한다.

---

# 41. Read-back verification

`verified=true`가 아니면 성공으로 표시하지 않는다.

```text
PUBLISH REQUEST COMPLETED
```

와:

```text
VERIFIED SUCCESS
```

를 구분한다.

최종 SUCCESS 조건:

```text
outcome == PUBLISHED
verified == true
```

---

# 42. Idempotency

동일 approved Draft에 publish를 다시 시도할 경우 server가 기존 publication을 반환할 수 있다.

CLI는:

```text
alreadyPublished=true
```

를 오류로 취급하지 않는다.

단 이 경우에도 새 external write가 없었다는 것을 표시한다.

---

# 43. 반드시 finally cleanup

어느 지점에서 실패해도:

```text
finally
```

에서 안전 모드 복귀를 시도한다.

---

# 44. Final environment

cleanup 후 현재 process:

```text
RUNNING_AI_DRAFT_PUBLISHING_ENABLED=false
WORKOUT_PUBLISHING_ENABLED=false
WORKOUT_PUBLISHING_SCHEDULER_ENABLED=false
RUNNINGAI_MCP_ENABLED=false
```

보장.

---

# 45. Safe-mode runtime

publish-enabled runtime을 한 번이라도 시작했다면 마지막에:

```text
stop
→ start with all switches false
→ health UP
```

까지 검증한다.

---

# 46. `.env` 상태

가능하면 `.env` 자체는 한 번도 수정하지 않는다.

만약 기술적으로 불가피해 `.env`를 건드려야 한다면:

```text
backup
try
finally
restore
```

로 **원본 전체를 정확히 복원**한다.

단 key를 임의로 append하는 기존 방식보다 원본 복원이 우선이다.

---

# 47. Java 21 stale-session issue

이전 live controlled publish에서 stale PowerShell environment 때문에:

```text
Java 21 is not available
```

문제가 발생했다.

Phase 6H-8에서는 tracked runtime을 신뢰할 수 있도록 이 부분도 조사한다.

---

# 48. Java discovery hardening

현재 `start-running-ai.ps1`가 current process의:

```text
JAVA_HOME
PATH
```

만 검사한다면 다음 fallback을 추가하는 것을 권장한다.

순서:

```text
1. current JAVA_HOME
2. current PATH
3. Machine JAVA_HOME
4. User JAVA_HOME
5. Program Files의 JDK 21
```

실제 `java.exe`를 실행해 major version 21임을 확인한다.

---

# 49. PowerShell NativeCommandError

Java version 확인 시:

```text
java -XshowSettings
```

가 stderr를 정상 사용한다.

Windows PowerShell 5.1에서 이를 오류로 오인하지 않도록 기존에 검증한 패턴을 사용한다.

---

# 50. Java tests

PowerShell runtime test에 Java discovery의 pure/helper 부분을 가능한 범위에서 테스트한다.

실제 특정 사용자 경로를 fixture에 하드코딩하지 않는다.

---

# 51. Console mojibake cleanup

기존 untracked controlled publish script의 한글 safety prompt는 ASCII로 변경한다.

이걸로 Phase 6H-7.2 Part E leftover를 닫는다.

---

# 52. 6H-7.2 documentation leftover

이번 Phase에서 함께 README를 정리한다.

추가:

```text
Windows PowerShell JSON API
```

설명:

```text
Never pass natural-language ConvertTo-Json strings directly as -Body.
Use the RunningAI UTF-8 JSON helper.
```

---

# 53. Architecture note

6H-7.2에서 빠졌던 문서를 추가한다.

권장:

```text
docs/architecture/windows-api-encoding.md
```

내용:

```text
PowerShell object
→ JSON
→ UTF-8 bytes
→ HTTP

HTTP raw bytes
→ UTF-8 decode
→ JSON object
```

그리고 byte[] unrolling 문제와:

```text
Write-Output -NoEnumerate
```

이 필요한 이유를 기록한다.

---

# 54. Operator workflow architecture

신규 문서 권장:

```text
docs/architecture/coach-operator-workflow.md
```

다이어그램:

```text
TrainingContext V2
       ↓
Claude Coach
       ↓
DRAFT
       │
       ├── Revise
       │      ↓
       │    DRAFT vN
       │
       └── APPROVE (human)
               ↓
        Publish Preview
               ↓
       Publishable?
        │           │
       NO          YES
        │           ↓
       STOP     YES gate
                    ↓
              controlled switch
                    ↓
               Intervals
                    ↓
              read-back verify
                    ↓
               safe mode
```

---

# 55. CLI must not automate coaching judgement

CLI가 다음을 하지 않는다.

```text
Draft 자동 revision
Draft 내용 자동 변경
target 자동 변경
운동 강도 자동 변경
approve 자동 실행
```

그 판단은 Claude + 사용자에게 남긴다.

---

# 56. CLI must not auto-publish

다음 옵션 금지:

```text
-AutoApprove
-AutoPublish
-Yes
-ForcePublish
```

human gate를 우회하는 parameter를 만들지 않는다.

---

# 57. Non-interactive mode

향후 automation을 위해 `-NonInteractive`를 만든다면:

```text
generate
retrieve
preview
```

까지만 허용한다.

`approve` 또는 external publish는 절대 허용하지 않는다.

---

# 58. Error handling

API 오류는 최소:

```text
HTTP status
RunningAI error code
safe message
```

를 보여준다.

response body 전체나 secrets를 dump하지 않는다.

---

# 59. Known important errors

friendly handling:

```text
WORKOUT_DRAFT_NOT_FOUND
WORKOUT_DRAFT_SUPERSEDED
WORKOUT_DRAFT_APPROVED_IMMUTABLE
WORKOUT_DATE_ALREADY_APPROVED

AI_COACH_*

WORKOUT_DRAFT_APPROVAL_REQUIRED
DRAFT_PUBLISHING_DISABLED
DRAFT_PUBLISH_ALREADY_RUNNING
UNPUBLISHABLE_DRAFT

INTERVALS_*
```

---

# 60. Secrets

출력 금지:

```text
INTERVALS_API_KEY
Authorization header
Garmin credential/token
.env contents
Claude auth material
```

---

# 61. Logs

operator log를 남긴다면:

```text
.runtime/logs/coach-operator.log
```

처럼 runtime 영역에만 둔다.

기록 가능:

```text
timestamp
draftId
version
status
date
operation
verified
```

기록 금지:

```text
API key
raw prompt
personal free-text feedback
raw Claude response
full workout JSON
```

초기 Phase에서는 별도 log 없이 console만 사용해도 된다.

---

# 62. PowerShell tests

기존:

```text
scripts/windows/tests/Test-RunningAI.ps1
```

확장 또는:

```text
scripts/windows/tests/Test-CoachOperator.ps1
```

신규 생성 가능.

---

# 63. Local fake HTTP server

외부 네트워크 없이 `HttpListener`로 fake RunningAI API를 구성한다.

request sequence를 기록한다.

---

# 64. Generate safety test

Generate 수행 시 fake server가 확인:

```text
POST /workout-drafts
```

만 발생.

publish endpoint 호출:

```text
0
```

---

# 65. Revision test

Revision:

```text
POST /workout-drafts/{id}/revisions
```

정확히 한 번.

한글 request bytes round-trip도 재검증.

---

# 66. Approval test

사람 승인 gate 전:

```text
/approve calls = 0
```

확인.

정확한 승인 입력 후에만:

```text
/approve calls = 1
```

---

# 67. Publish gate test

`YES`가 아닌:

```text
Y
yes
Yes
ENTER
NO
```

에서는:

```text
/publish calls = 0
```

이어야 한다.

정확히 uppercase:

```text
YES
```

에서만 가능.

---

# 68. No bypass parameter test

script parameter에 human gate 우회 기능이 없는 것을 검토한다.

---

# 69. Preview-before-publish test

publish request보다 반드시 먼저:

```text
GET publish-preview
```

가 발생해야 한다.

runtime restart를 포함하면 second preview도 검증한다.

---

# 70. Changed-preview test

before:

```text
hash=A
```

after restart:

```text
hash=B
```

이면:

```text
/publish calls = 0
```

---

# 71. Unpublishable test

preview:

```text
publishable=false
```

→ external write 0.

---

# 72. Existing publication test

preview:

```text
publication != null
```

→ publish switch enable 0
→ runtime restart 0
→ publish POST 0.

---

# 73. REST test

```text
externalWriteRequired=false
```

→ publish switch enable 0
→ external write 0.

---

# 74. Cleanup failure test

fake publish가 실패해도 finally cleanup path가 실행되는 것을 검증한다.

최종 switch 값:

```text
false
false
false
false
```

---

# 75. UTF-8 tests

6H-7.2 PowerShell 32 tests를 깨지 않는다.

신규 operator flow에서도:

```text
Goal Korean
Revision Korean
response Unicode
```

가 정확히 유지되어야 한다.

---

# 76. Source encoding

safety-critical `.ps1` runtime literal은 ASCII 중심으로 유지한다.

이렇게 해서 Windows PowerShell 5.1의 UTF-8-no-BOM source parsing 문제에 의존하지 않는다.

사용자가 입력하는 한글은 UTF-8 helper를 통해 정상 처리한다.

---

# 77. Server tests

서버 production code 변경이 없어도 full regression:

```text
H2
PostgreSQL 17
```

을 실행한다.

baseline보다 테스트 수가 증가할 수 있다.

---

# 78. Python

connector 변경 없음.

최종:

```text
134 passed
```

확인.

---

# 79. No live external publish during development

자동 테스트와 live smoke 동안:

```text
Intervals writes = 0
Garmin writes = 0
```

유지.

---

# 80. Live smoke phase 1

tests GREEN 후 Main PC에 merge.

먼저:

```text
running-ai-coach.ps1 -DraftId <DRAFT>
```

로 review/resume만 확인.

approve하지 않는다.

publish하지 않는다.

---

# 81. Live smoke phase 2

synthetic 또는 실제 다음 훈련 Draft를 generate한다.

한글 Goal 정상 확인.

Revision 정상 확인.

여기까지 external write 0.

---

# 82. Controlled publish live validation

별도 명시적 사용자 승인 후에만 실제 한 번 검증한다.

순서:

```text
Draft review
→ APPROVE
→ preview
→ YES
→ publish
→ verified=true
→ safe-mode UP
```

이번 Phase 개발자가 임의로 실행하지 않는다.

---

# 83. Live checkout integration

main-3 tests/commit 완료 후 live checkout에 fast-forward한다.

단 기존 untracked:

```text
publish-approved-draft-controlled.ps1
```

가 tracked file과 충돌한다면:

1. `.runtime/backups/phase-6h-8/` 백업
2. 내용 비교
3. 해당 untracked 파일만 명시적으로 이동
4. `git merge --ff-only`
5. 새 tracked script 확인

한다.

`git clean` 금지.

---

# 84. Git

권장 commit 분리:

```text
fix: harden Windows runtime prerequisites

feat: add safe AI coach operator workflow

test: cover coach operator safety gates

docs: document coach operations and UTF-8 API usage
```

실제 변경 범위에 따라 조정 가능.

---

# 85. Definition of Done

```text
[ ] running-ai-coach.ps1 tracked

[ ] generate supported
[ ] resume by DraftId supported
[ ] human-readable Draft output

[ ] revision loop
[ ] Korean revision safe

[ ] APPROVE human gate
[ ] no auto approve

[ ] publish-preview automatic after approval
[ ] rendered workout visible

[ ] unpublishable fail closed
[ ] existing publication short-circuit
[ ] REST short-circuit

[ ] YES external-write gate
[ ] no human-gate bypass parameter

[ ] controlled draft publishing only
[ ] legacy publisher remains false
[ ] scheduler remains false
[ ] MCP remains false

[ ] preview revalidation after restart
[ ] preview hash/change protection

[ ] publish exactly once
[ ] verified=true required for success

[ ] finally safety cleanup
[ ] all four switches false afterward
[ ] safe-mode runtime health UP afterward

[ ] .env not modified if process-env solution works

[ ] controlled publish script tracked
[ ] old mojibake safety messages fixed

[ ] Java stale-session handling investigated
[ ] Java 21 runtime robust on Main PC

[ ] 6H-7.2 UTF-8 helper reused
[ ] request byte[] unrolling regression preserved
[ ] response raw UTF-8 decode preserved

[ ] PowerShell operator tests GREEN
[ ] H2 full regression GREEN
[ ] PostgreSQL full regression GREEN
[ ] Python 134 GREEN

[ ] Garmin writes 0 during automated tests
[ ] Intervals writes 0 during automated tests

[ ] README updated
[ ] windows-api-encoding architecture doc
[ ] coach-operator-workflow architecture doc
[ ] result work-order

[ ] secrets scan
[ ] diff review
[ ] commits
[ ] push
```

---

# 86. 완료 보고

최종 보고에 반드시 포함:

```text
Baseline SHA:
Final SHA:

Server production code changed:
DB migration:

Operator script:
Controlled publish script:

UTF-8 helper reused:
Request byte handling:
Response byte handling:

Generate:
Resume:
Revision:
Approve gate:
Preview:
Publish gate:
REST behavior:
Already-published behavior:
Unpublishable behavior:

Preview-before hash:
Preview-after hash protection:

.env modified:
Process-env strategy:

Java 21 discovery:

Final switch values:
RunningAI safe-mode health:

PowerShell tests:
H2:
PostgreSQL:
Python:

Automated Garmin calls:
Automated Intervals calls:
Automated external writes:

Live smoke:
Live controlled publish performed:

README:
Architecture docs:

Legacy/untracked script handling:

Commits:
Push:

Known limitations:
Next recommended phase:
```

완료 marker:

```text
PHASE_6H_8_SAFE_COACH_OPERATOR_WORKFLOW_READY
```

여기서 멈춘다.

**실제 훈련 approve/publish는 개발 완료 후 별도 사용자 승인 없이는 수행하지 않는다.**
