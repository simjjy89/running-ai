# RunningAI Phase 6H-7.2 — Windows UTF-8 API Path Hardening

## 1. 목적

Windows PowerShell 5.1에서 RunningAI API로 한글 자연어를 보낼 때 발생한 mojibake를 제거한다.

실제 관찰:

```text
requestedGoal에 한글 입력
        ↓
POST /api/v1/workout-drafts
        ↓
Claude가 "goal text arrived with corrupted characters" 경고
```

또한 `.ps1` 파일 안의 한글 메시지도 실행 시:

```text
이제 실제 Intervals.icu WRITE가 발생합니다.
```

가 mojibake로 출력된 사례가 있었다.

이번 Phase에서는 두 문제를 분리해서 해결한다.

```text
A. HTTP JSON request body UTF-8
B. PowerShell script source / console text encoding
```

---

## 2. 핵심 원칙

Spring/Kotlin domain 로직을 인코딩 문제의 원인으로 가정하지 않는다.

먼저 재현 테스트로 경계를 확인한다.

```text
PowerShell
→ raw request bytes
→ Spring @RequestBody
→ SessionConstraints
→ Claude prompt
```

각 단계에서 문자열이 정확히 유지되는지 검증한다.

---

## 3. 절대 하지 않을 것

이번 Phase에서:

```text
Workout logic 변경 금지
TrainingContext 변경 금지
Claude coaching policy 변경 금지
Draft validation 변경 금지
DB migration 금지
Garmin 호출 금지
Intervals 호출 금지
Draft approve 금지
Draft publish 금지
```

publishing switches는 계속 모두 false.

---

## 4. 작업 전 확인

Repository:

```text
C:\running-ai-github
```

시작 시:

```powershell
git status
git log -1 --oneline
git rev-parse origin/main
```

기존 작업 파일을 reset/clean/delete하지 않는다.

특히:

```text
scripts/windows/publish-approved-draft-controlled.ps1
```

에 로컬 변경이 있다면 보존한다.

---

## 5. Work order

본 지시서 저장:

```text
docs/work-orders/
2026-10-04-phase-6h-7-2-windows-utf8-api-path-instruction.md
```

결과:

```text
docs/work-orders/
2026-10-04-phase-6h-7-2-windows-utf8-api-path-result.md
```

---

# Part A — 서버 UTF-8 경계 확인

## 6. 현재 API

대상:

```text
POST /api/v1/workout-drafts

POST /api/v1/workout-drafts/{id}/revisions
```

필드:

```text
requestedGoal
userFeedback
painOrFatigueFeedback
request
```

---

## 7. Spring UTF-8 regression test

`WorkoutDraftApiTest` 또는 적절한 integration test에 실제 Unicode fixture를 추가한다.

예시는 synthetic Korean text를 사용한다.

```text
"하프마라톤 일주일 전 가볍게 훈련하고 싶어요"
```

실제 사용자 개인 내용은 fixture에 넣지 않는다.

---

## 8. Generate request test

UTF-8 JSON bytes로:

```http
Content-Type: application/json; charset=UTF-8
```

전송했을 때:

```text
requestedGoal
userFeedback
painOrFatigueFeedback
```

가 `SessionConstraints`에 byte-for-byte / character-for-character 동일하게 도달하는지 검증한다.

---

## 9. Revision request test

다음도 동일하게 검증한다.

```text
POST /workout-drafts/{id}/revisions
```

Korean `request`가:

```text
controller
→ service
→ coach
```

까지 정확히 유지되어야 한다.

---

## 10. Claude prompt boundary test

`ClaudeCoachPromptBuilder`에 한글 constraint/request를 넣었을 때 생성 prompt에 동일 문자열이 포함되는지 검증한다.

escape된 JSON 표현은 허용하되 decode 시 동일 Unicode string이어야 한다.

---

## 11. 서버 설정 변경 원칙

기존 Spring Boot/Jackson이 UTF-8 request를 정상 처리한다면:

```text
application.yml encoding property 추가 금지
custom CharacterEncodingFilter 추가 금지
Controller별 charset workaround 금지
```

불필요한 서버 변경을 하지 않는다.

---

# Part B — PowerShell UTF-8 JSON helper

## 12. 문제

Windows PowerShell 5.1에서는:

```powershell
$json = $body | ConvertTo-Json
Invoke-RestMethod -Body $json
```

처럼 .NET string을 직접 넘기는 경로에서 encoding 동작이 호출 방식과 Content-Type에 의존할 수 있다.

RunningAI 운영 helper에서는 이를 암묵적으로 두지 않는다.

---

## 13. UTF-8 bytes를 명시적으로 생성

공용 helper를 추가한다.

권장 위치:

```text
scripts/windows/RunningAI.Common.ps1
```

권장 함수:

```powershell
ConvertTo-RunningAiUtf8JsonBytes
```

개념:

```powershell
$json = $Value | ConvertTo-Json -Depth $Depth
[System.Text.Encoding]::UTF8.GetBytes($json)
```

---

## 14. 공용 API helper

권장:

```powershell
Invoke-RunningAiJsonRequest
```

지원:

```text
GET
POST
PUT
DELETE
```

최소 이번 Phase에서 POST가 확실히 동작해야 한다.

Body가 있을 때:

```text
Object
→ ConvertTo-Json
→ UTF-8 bytes
→ Invoke-RestMethod
```

Content-Type:

```text
application/json; charset=utf-8
```

로 고정한다.

---

## 15. 중요한 invariant

다음처럼 string JSON body를 그대로 보내지 않는다.

```powershell
Invoke-RestMethod `
    -Body ($body | ConvertTo-Json)
```

RunningAI helper에서는 반드시 UTF-8 byte[]를 사용한다.

---

## 16. 함수 예시 형태

구현 세부는 repository convention에 맞춰도 되지만 의미는 아래와 같아야 한다.

```powershell
function ConvertTo-RunningAiUtf8JsonBytes {
    param(
        [Parameter(Mandatory)]
        $Value,

        [int]$Depth = 30
    )

    $json = $Value | ConvertTo-Json -Depth $Depth

    return [System.Text.UTF8Encoding]::new($false).GetBytes($json)
}
```

---

## 17. Invoke helper

개념:

```powershell
function Invoke-RunningAiJsonRequest {
    param(
        [string]$Method,
        [string]$Uri,
        $Body
    )

    if ($null -eq $Body) {
        return Invoke-RestMethod `
            -Method $Method `
            -Uri $Uri
    }

    $bytes = ConvertTo-RunningAiUtf8JsonBytes $Body

    return Invoke-RestMethod `
        -Method $Method `
        -Uri $Uri `
        -ContentType "application/json; charset=utf-8" `
        -Body $bytes
}
```

필요한 timeout/error handling은 기존 convention을 따른다.

---

# Part C — PowerShell self-test

## 18. 로컬 HttpListener regression

`Test-RunningAI.ps1`에서 외부 network 없이 UTF-8 body를 확인한다.

로컬 `HttpListener` 또는 동등한 테스트 서버를 사용한다.

보낼 문자열:

```text
러닝 훈련 테스트
가볍게 달리고 싶어요
```

synthetic fixture.

---

## 19. Raw byte 확인

서버가 받은 request body bytes를:

```text
UTF-8
```

로 decode했을 때 original Korean string과 정확히 같아야 한다.

단순히 JSON parse 성공만 테스트하지 않는다.

---

## 20. Content-Type 확인

수신측에서:

```text
application/json
charset=utf-8
```

가 전달됐는지도 검증한다.

---

## 21. ASCII regression

영문-only payload도 기존과 동일하게 동작해야 한다.

---

## 22. Unicode 확장 테스트

한글만 하지 말고 최소:

```text
한글
ASCII
숫자
기호
```

혼합 문자열 하나를 round-trip한다.

emoji 테스트는 optional.

---

# Part D — 운영 API script

## 23. 사용자용 helper script

매번 긴 `Invoke-RestMethod`를 직접 쓰지 않게 간단한 운영 script를 추가한다.

권장:

```text
scripts/windows/invoke-running-ai-api.ps1
```

또는 repository convention에 맞는 이름.

---

## 24. 최소 기능

예:

```powershell
.\scripts\windows\invoke-running-ai-api.ps1 `
    -Method POST `
    -Path "/api/v1/workout-drafts" `
    -Body $body
```

내부에서는 공용 UTF-8 helper를 반드시 사용한다.

---

## 25. Base URL

기본:

```text
http://127.0.0.1:8080
```

optional parameter로 override 가능.

---

## 26. Secrets

스크립트가:

```text
.env
API key
Garmin credential
Intervals credential
```

을 출력하면 안 된다.

---

# Part E — 기존 Controlled Publish script

## 27. mojibake 원인 확인

현재 controlled publish script의 한글 literal이 PowerShell 5.1에서 깨진 이유를 재현/확인한다.

가능한 원인:

```text
UTF-8 no BOM .ps1
+
Windows PowerShell 5.1 source decoding
```

추측으로 수정하지 말고 raw file encoding을 확인한다.

---

## 28. 권장 해결

운영 `.ps1`의 실행 안정성을 위해 가능하면 사용자-facing runtime message를 ASCII/English로 유지한다.

예:

```text
This action will write to Intervals.icu.
Type YES to continue:
```

이 방법을 우선한다.

---

## 29. 한글 literal이 필요한 경우

PowerShell 5.1 compatibility 때문에 file encoding을 명시적으로 관리한다.

단 repository 전체 encoding policy를 무작정 BOM으로 바꾸지 않는다.

`.gitattributes` 또는 existing convention을 먼저 확인한다.

---

## 30. 전역 console 변경 금지

다음과 같은 설정을 앱 전역에 무조건 강제하지 않는다.

```powershell
chcp 65001
[Console]::OutputEncoding = ...
```

필요성이 테스트로 입증된 경우에만 최소 범위에서 적용한다.

HTTP body fix와 console display fix를 섞지 않는다.

---

# Part F — 실제 reproduction test

## 31. Main PC live UTF-8 smoke

자동 테스트 전부 GREEN 이후 한 번만 수행한다.

publish 없이:

```text
POST /api/v1/workout-drafts
```

에 synthetic Korean requestedGoal을 보낸다.

---

## 32. Live text

예:

```text
오늘은 가볍게 달리고 싶고 무리한 훈련은 원하지 않습니다.
```

개인정보 없는 synthetic operational text.

---

## 33. 확인할 것

Draft assessment / warnings에서:

```text
corrupted characters
garbled
encoding
```

관련 경고가 없어야 한다.

---

## 34. 더 확실한 검증

가능하면 Claude 결과만 보고 추정하지 말고 서버 debug/test hook 없이:

```text
request
→ stored context snapshot
```

또는 이미 저장되는 safe field를 통해 전달값이 동일함을 확인한다.

민감 prompt/raw response logging은 추가하지 않는다.

---

## 35. Live revision

새로 만든 DRAFT에 Korean revision도 한 번 테스트한다.

```text
조금 더 가볍게 수정해줘
```

정상 전달 확인.

approve 금지.

publish 금지.

---

# Part G — 테스트

## 36. Spring H2

현재 baseline:

```text
1188 passed
```

전부 PASS.

신규 UTF-8 tests 추가 후 증가 가능.

---

## 37. PostgreSQL 17

전체 regression PASS.

---

## 38. PowerShell

실행:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass `
    -File scripts/windows/tests/Test-RunningAI.ps1
```

전부 PASS.

---

## 39. Python

connector code 변경 없음.

최종 project regression 정책에 따라:

```text
134 passed
```

확인.

---

# Part H — Publishing safety

## 40. 전체 Phase 동안

반드시:

```text
RUNNING_AI_DRAFT_PUBLISHING_ENABLED=false
WORKOUT_PUBLISHING_ENABLED=false
WORKOUT_PUBLISHING_SCHEDULER_ENABLED=false
RUNNINGAI_MCP_ENABLED=false
```

---

## 41. External calls

이번 Phase에서:

```text
Garmin write = 0
Intervals write = 0
Draft publish = 0
```

---

# Part I — Documentation

## 42. README

Windows/API 호출 예제에 UTF-8-safe helper 사용법을 추가한다.

기존 raw `Invoke-RestMethod` 예제가 자연어 POST에 사용된다면 교체 또는 주석 추가.

---

## 43. Architecture note

필요하면:

```text
docs/architecture/windows-api-encoding.md
```

추가.

내용:

```text
PowerShell object
→ JSON
→ explicit UTF-8 bytes
→ HTTP Content-Type charset=utf-8
→ Spring/Jackson
```

---

# Part J — Definition of Done

```text
[ ] Korean generate API round-trip test
[ ] Korean revision API round-trip test
[ ] Claude prompt Unicode test

[ ] explicit UTF-8 JSON byte helper
[ ] common RunningAI JSON API helper
[ ] Content-Type charset=utf-8

[ ] PowerShell HttpListener raw-byte regression
[ ] ASCII regression
[ ] mixed Unicode regression

[ ] controlled-publish script mojibake fixed
[ ] script source encoding policy documented

[ ] no unnecessary Spring encoding config
[ ] no DB migration

[ ] live Korean generate smoke PASS
[ ] live Korean revision smoke PASS
[ ] no corrupted-character warning

[ ] Draft remains DRAFT
[ ] no approval
[ ] no publish

[ ] Garmin writes 0
[ ] Intervals writes 0

[ ] all four publishing switches false

[ ] H2 full regression GREEN
[ ] PostgreSQL full regression GREEN
[ ] Windows PowerShell tests GREEN
[ ] Python regression GREEN

[ ] docs written
[ ] secrets scan
[ ] diff review
[ ] commits
[ ] push
```

---

## 44. 권장 commits

```text
fix: send RunningAI JSON requests as explicit UTF-8

test: cover Windows Unicode API round trips

docs: document UTF-8-safe Windows API usage
```

---

## 45. 완료 보고 형식

```text
Baseline SHA:
Final SHA:

Root cause:

HTTP body cause:
Script-source cause:
Server change required:

UTF-8 helper:
API helper:

Generate Korean test:
Revision Korean test:
Prompt Korean test:
Raw-byte PowerShell test:

Live Korean generate:
Live Korean revision:
Corruption warning:

DB migration:

H2:
PostgreSQL:
PowerShell:
Python:

Garmin calls:
Intervals calls:
External writes:

Publishing switches:

Docs:
Commits:
Push:

Known limitations:
```

완료 marker:

```text
PHASE_6H_7_2_WINDOWS_UTF8_API_PATH_READY
```

여기서 멈춘다.

Draft approve/publish나 Garmin/Intervals write는 수행하지 않는다.
