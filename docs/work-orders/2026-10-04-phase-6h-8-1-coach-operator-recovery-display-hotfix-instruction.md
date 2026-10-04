# Phase 6H-8.1 — Coach Operator Recovery Display Hotfix

## 목적

실제 Main PC live resume에서 발견된 Coach Operator 표시 오류를 수정한다.

실제 재현:

```text
Draft #10
MAIN ... x5
PropertyNotFoundException: type
Recovery ...
PropertyNotFoundException: repetitions
```

동시에 이미 publish된 Draft short-circuit는 정상 동작했으며 external write는 발생하지 않았다.

이번 hotfix에서는 publish를 수행하지 않는다.

---

## 1. Root cause

현재:

```powershell
Write-RunningAiSegment -Segment $Segment.recovery
```

로 `RecoveryResponse`를 `SegmentResponse`처럼 렌더링한다.

그러나 두 API shape는 다르다.

### SegmentResponse

```text
type
durationMinutes
intensity
description
primaryTargetType
pace...
heartRateBpm...
heartRatePercentLthr...
treadmill...
repetitions
recoveryDurationMinutes
recovery
```

### RecoveryResponse

```text
durationMinutes
intensity
description
primaryTargetType
pace...
heartRatePercentLthr...
treadmill...
incline...
```

Recovery에는 다음이 없다.

```text
type
repetitions
recovery
recoveryDurationMinutes
heartRateBpmMin
heartRateBpmMax
```

---

## 2. Recovery 전용 renderer

다음 함수를 추가한다.

권장:

```powershell
Write-RunningAiRecovery
```

표시 예:

```text
  MAIN 2m (HARD)
    Controlled fast rep
    Pace 4:35-4:45/km
    x5
      Recovery 1m (VERY_EASY)
        Easy jog recovery between reps
        HR 65-75% LTHR
```

Recovery renderer에서는 절대 다음을 읽지 않는다.

```text
type
repetitions
recovery
```

---

## 3. Main renderer 수정

기존:

```powershell
if ($Segment.recovery) {
    Write-RunningAiSegment -Segment $Segment.recovery ...
}
```

를:

```powershell
if ($Segment.recovery) {
    Write-RunningAiRecovery -Recovery $Segment.recovery ...
}
```

형태로 수정한다.

legacy:

```text
recoveryDurationMinutes
```

fallback은 그대로 보존한다.

---

## 4. Optional property 안전성

`Format-RunningAiSegmentTarget`도 Recovery에서 사용할 수 있으므로 absent property에 안전해야 한다.

특히 RecoveryResponse에는 absolute BPM fields가 없다.

다음과 같은 helper를 권장한다.

```powershell
function Get-RunningAiOptionalProperty {
    param(
        [Parameter(Mandatory)]$Object,
        [Parameter(Mandatory)][string]$Name
    )

    $property = $Object.PSObject.Properties[$Name]
    if ($null -eq $property) {
        return $null
    }

    return $property.Value
}
```

target formatter에서 optional JSON field를 직접:

```powershell
$Segment.heartRateBpmMin
```

읽지 말고 safe getter를 사용한다.

이렇게 해야 `%LTHR`가 없는 qualitative recovery에서도 StrictMode 오류가 나지 않는다.

---

## 5. Fail-closed display

이번 live smoke에서 더 중요한 안전성 문제:

```text
Draft display error
→ workflow continued
```

가 실제로 발생했다.

운영 workflow에서는 **Draft를 완전하게 보여주지 못하면 승인 단계로 가면 안 된다.**

따라서 `Show-RunningAiWorkoutDraft` 오류는 terminating failure로 취급하고:

```text
DISPLAY_FAILED
```

또는 기존 `ERROR` outcome으로 종료한다.

요구사항:

```text
display failure
→ APPROVE prompt 0
→ approve API calls 0
→ publish-preview calls 0
→ publish calls 0
```

---

## 6. 기존 published short-circuit는 보존

이번 live 검증에서 확인된 정상 동작을 regression test로 고정한다.

```text
APPROVED draft
publication != null

→ preview 표시
→ NO_EXTERNAL_WRITE_NEEDED
→ publish switch enable 0
→ runtime restart 0
→ POST /publish 0
```

---

## 7. 테스트 fixture 개선

현재 `New-DraftFixture`가:

```powershell
segments = @()
```

라서 실제 nested recovery shape를 전혀 테스트하지 못했다.

새 fixture를 추가한다.

실제 API shape에 맞게:

```text
WARM_UP

MAIN
  repetitions = 5
  recovery =
    durationMinutes = 1
    intensity = VERY_EASY
    primaryTargetType = HEART_RATE
    heartRatePercentLthrMin = 65
    heartRatePercentLthrMax = 75
    type 없음
    repetitions 없음
    bpm 없음

COOL_DOWN
```

---

## 8. 신규 regression tests

최소 다음 테스트를 추가한다.

### A. targeted recovery display

```text
MAIN x5
Recovery 1m
HR 65-75% LTHR
```

가 error 없이 출력.

### B. recovery has no `type`

StrictMode에서도 PropertyNotFoundException 0.

### C. recovery has no `repetitions`

StrictMode에서도 PropertyNotFoundException 0.

### D. qualitative recovery

pace / %LTHR / bpm이 모두 없는 Recovery도 오류 없이 표시.

### E. display failure fail-closed

synthetic malformed draft를 넣어 display가 실패하도록 하고:

```text
approve calls = 0
publish calls = 0
```

확인.

### F. Draft #10-compatible shape

실제 #10과 동일한 구조의 synthetic fixture:

```text
WU HR
MAIN PACE x5
Recovery HR
CD HR
```

전체 표시 PASS.

---

## 9. Server 변경

서버 production code 변경 금지.

DB migration 없음.

Garmin/Intervals integration 변경 없음.

---

## 10. Regression

현재 baseline:

```text
H2              1192 / 1192
PostgreSQL      1192 / 1192
PowerShell        86 / 86
Python           134 / 134
```

신규 PowerShell test 수는 증가해야 한다.

모두 GREEN.

---

## 11. Live smoke

merge 후 다시 다음만 실행:

```powershell
cd C:\running-ai-github

& ".\scripts\windows\running-ai-coach.ps1" `
    -DraftId 10
```

기대:

```text
MAIN 2m (HARD)
  ...
  x5
    Recovery 1m (VERY_EASY)
      Easy jog recovery between reps
      HR 65-75% LTHR
```

다음 오류가 없어야 한다.

```text
PropertyNotFoundException
type 속성을 찾을 수 없습니다
repetitions 속성을 찾을 수 없습니다
```

그리고 마지막:

```text
publication = PUBLISHED
already published; no new external write performed
```

---

## 12. Safety verification

live smoke 동안:

```text
RUNNING_AI_DRAFT_PUBLISHING_ENABLED=false
WORKOUT_PUBLISHING_ENABLED=false
WORKOUT_PUBLISHING_SCHEDULER_ENABLED=false
RUNNINGAI_MCP_ENABLED=false

runtime restart = 0
Intervals write = 0
Garmin write = 0
```

---

## 13. Result doc

```text
docs/work-orders/
2026-10-04-phase-6h-8-1-coach-operator-recovery-display-hotfix-result.md
```

완료 marker:

```text
PHASE_6H_8_1_COACH_OPERATOR_RECOVERY_DISPLAY_READY
```

여기서 멈춘다.

실제 새로운 Draft approve/publish는 수행하지 않는다.
