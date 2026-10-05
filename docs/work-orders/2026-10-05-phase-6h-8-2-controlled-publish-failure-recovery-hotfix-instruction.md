# Phase 6H-8.2 — Controlled Publish Failure Recovery Hotfix

## 발견된 문제

### 1. Java PATH discovery corruption

현재 기본값:

```powershell
[string]$PathJavaExe = $(($found = Get-Command java ...); ...)
```

에서 parenthesized assignment:

```powershell
($found = Get-Command java ...)
```

자체도 pipeline output으로 방출된다.

그 뒤 `$found.Source`도 방출되어 결과가 두 값이 되고, `[string]` 변환 시 대략:

```text
java.exe C:\Program Files\Java\jdk-21.0.2\bin\java.exe
```

형태로 합쳐진다.

이후:

```powershell
Split-Path
Join-Path
```

가 잘못된 candidate를 받아:

```text
DriveNotFoundException: drive 'java.exe C' not found
```

가 발생한다.

### 수정

PATH Java lookup을 별도 helper로 분리한다.

예:

```powershell
function Get-RunningAiPathJavaExe {
    $found = Get-Command java -ErrorAction SilentlyContinue
    if ($found) {
        return $found.Source
    }
    return $null
}
```

그리고:

```powershell
[string]$PathJavaExe = (Get-RunningAiPathJavaExe)
```

로 사용한다.

Java candidate에는 JDK home directory만 들어가야 한다.

---

## 2. Generic exception handling이 StrictMode에서 다시 실패

현재:

```powershell
$response = $ErrorRecord.Exception.Response
```

는 모든 exception에 `Response` property가 있다고 가정한다.

하지만:

```text
DriveNotFoundException
ArgumentException
FileNotFoundException
...
```

등에는 `Response`가 없다.

StrictMode에서는 property access 자체가 다시 예외를 발생시킨다.

### 수정

optional property로 접근한다.

```powershell
$responseProperty = $ErrorRecord.Exception.PSObject.Properties['Response']
$response = if ($responseProperty) { $responseProperty.Value } else { $null }
```

generic exception이면 안전하게:

```text
HttpStatus = null
Code       = null
Message    = original exception message
```

를 반환한다.

error formatter 자체가 secondary failure를 일으키면 안 된다.

---

## 3. Partial restart failure가 safe-mode runtime 복구를 건너뜀

현재:

```powershell
& $RuntimeRestarter $BaseUrl
$restarted = $true
```

순서다.

문제는 restarter가:

```text
Spring stop
Garmin stop
Garmin start
Java detection FAIL
```

처럼 중간까지 실행한 뒤 throw하면 `$restarted`가 여전히 false라는 점이다.

따라서 outer `finally`에서:

```powershell
if ($restarted) {
    safe-mode restart
}
```

가 실행되지 않는다.

실제로 이번 live run이 이 케이스다.

### 수정

restart **시작 전에** recovery-needed flag를 세운다.

```powershell
$restartAttempted = $false

try {
    $restartAttempted = $true
    & $RuntimeRestarter $BaseUrl

    ...
}
finally {
    # all switches false first
    ...

    if ($restartAttempted) {
        & $RuntimeRestarter $BaseUrl
    }
}
```

즉 partial restart failure도 반드시 safe-mode recovery를 시도해야 한다.

---

# 필수 Regression

## Java discovery

실제 PATH에 Java가 있는 환경에서:

```text
Get-RunningAiPathJavaExe
```

결과는 정확히 하나의 executable path 또는 null이어야 한다.

candidate에는:

```text
java.exe C:
```

같은 합쳐진 값이 절대 없어야 한다.

## Generic exception

synthetic `DriveNotFoundException`을:

```text
Get-RunningAiErrorDetails
```

에 전달해도 exception 없이 안전한 error detail을 반환해야 한다.

## Partial restart failure

fake restarter:

```text
1st call → throw
2nd call → success
```

로 설정한다.

기대:

```text
publish POST calls = 0
restart calls      = 2
all switches       = false
Outcome            = FAILED
```

즉 첫 publish-enabled restart가 부분 실패해도 finally의 safe-mode restart가 반드시 한 번 더 호출되어야 한다.

## Full regression

```text
PowerShell > 92/92
H2         1192/1192
PostgreSQL 1192/1192
Python      134/134
```

전부 GREEN.

---

# Live verification

hotfix merge/push 후 먼저:

```powershell
& ".\scripts\windows\status-running-ai.ps1"
```

safe mode 확인.

그 다음 Draft #17을 그대로 재개한다.

```powershell
& ".\scripts\windows\running-ai-coach.ps1" -DraftId 17
```

Draft #17은 이미 APPROVED이므로 새 `APPROVE`는 필요 없다.

Preview를 다시 확인하고 정확히:

```text
YES
```

를 입력한다.

기대 흐름:

```text
APPROVED #17
→ preview
→ YES
→ publish-enabled restart
→ Spring UP
→ second preview
→ SHA-256 / fields unchanged
→ POST publish exactly once
→ outcome=PUBLISHED
→ verified=true
→ switches=false
→ safe-mode restart
→ health UP
```

완료 marker:

```text
PHASE_6H_8_2_CONTROLLED_PUBLISH_FAILURE_RECOVERY_READY
```
