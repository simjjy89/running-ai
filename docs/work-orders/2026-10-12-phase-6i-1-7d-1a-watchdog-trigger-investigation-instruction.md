# WO-RUNNINGAI-PHASE-6I-1.7D-1A — Watchdog Scheduled Task Trigger Investigation (작업 지시서 원문)

## Watchdog Scheduled Task Trigger Investigation

### 목적

RunningAI-Watchdog Scheduled Task가 Enabled인데도 자동 실행되지 않는 원인을 규명한다.

현재 기록:
- LastRunTime: 2026-10-10 12:52:17
- LastTaskResult: 21 (과거 결과)
- 활성화 이후 신규 자동 실행 기록 없음
- Watchdog 수동 일반 실행은 이전 Phase에서 성공
- 운영 Spring/Connector/Relay/PostgreSQL은 마지막 확인 시 정상

이번 작업은 읽기 전용 조사만 수행한다.

### STEP A — 실제 Scheduled Task 상태 확인

다음 PowerShell 명령을 실행한다.

```powershell
$task = Get-ScheduledTask -TaskName "RunningAI-Watchdog" -ErrorAction Stop
$info = Get-ScheduledTaskInfo -TaskName "RunningAI-Watchdog"

$task | Select-Object TaskName, TaskPath, State
$info | Select-Object LastRunTime, LastTaskResult, NextRunTime, NumberOfMissedRuns

$task.Actions | Select-Object Execute, Arguments, WorkingDirectory
$task.Principal | Select-Object UserId, LogonType, RunLevel
$task.Triggers | Format-List *
$task.Settings | Select-Object Enabled, MultipleInstances, StartWhenAvailable, ExecutionTimeLimit
```

개별 값의 존재 여부와 예상 설정 일치 여부를 보고한다.

### STEP B — 실제 등록된 XML 분석

소스 코드에서 기대하는 설정이 아니라 Windows Task Scheduler에 실제 저장된 XML을 조회한다.

```powershell
[xml]$xml = Export-ScheduledTask -TaskName "RunningAI-Watchdog"

$xml.Task.Triggers.LogonTrigger |
    Select-Object UserId, Enabled, Delay, StartBoundary, EndBoundary

$xml.Task.Triggers.LogonTrigger.Repetition |
    Select-Object Interval, Duration, StopAtDurationEnd
```

검증 대상:

- LogonTrigger가 실제 존재하는가?
- 해당 사용자와 UserId가 일치하는가?
- Enabled인가?
- Delay가 PT5M인가?
- Repetition.Interval이 PT5M인가?
- Repetition.Duration은 무엇인가?
- EndBoundary가 이미 만료되었는가?
- Task 자체는 Enabled인가?
- 등록된 Principal이 실제 로그인 계정과 일치하는가?

Repetition.Duration이 없거나 예상과 달라도 즉시 설정을 수정하지 않는다. 먼저 Windows의 실제 해석을 확인한다.

### STEP C — 로그인 세션 및 작업 이벤트 확인

Security 이벤트 로그 대신 접근 가능한 정보부터 사용한다.

```powershell
quser
```

현재 사용자 세션의 로그인 시각을 확인한다.

Task Scheduler Operational 이벤트 조회:

```powershell
try {
    Get-WinEvent -FilterHashtable @{
        LogName = "Microsoft-Windows-TaskScheduler/Operational"
        StartTime = (Get-Date).AddDays(-3)
    } -ErrorAction Stop |
    Where-Object { $_.Message -like "*RunningAI-Watchdog*" } |
    Select-Object -First 30 TimeCreated, Id, LevelDisplayName, Message
} catch {
    Write-Output "TaskScheduler Operational events unavailable"
}
```

이벤트 로그 조회가 실패해도 관리자 권한을 요구하거나 보안 감사 정책을 변경하지 않는다.

확인할 항목:

- 작업 활성화 시점
- 실제 신규 로그인 여부
- 로그인 트리거 발생 여부
- 작업 시작 이벤트 존재 여부
- 작업 실행 실패 이벤트 존재 여부
- 작업 반복 예약 여부

잠금 해제와 실제 로그인을 구분한다.

이벤트가 없다는 것만으로 로그인이 발생하지 않았다고 단정하지 않는다.

### STEP D — 원인 분류

다음 중 하나로 분류한다.

A. 작업 활성화 이후 새로운 로그인 이벤트가 없어 트리거 자체가 시작되지 않음.

B. 로그인은 있었지만 등록된 UserId/Principal 조건이 일치하지 않음.

C. 트리거는 정상 시작했으나 Repetition 설정이 등록 과정에서 유실되었거나 유효하지 않음.

D. 트리거는 발생했으나 실행 조건 또는 Task Scheduler 오류로 작업 시작이 거부됨.

E. 작업은 시작했지만 watch-running-ai.ps1 실행이 실패함.

F. 현재 증거로 원인 확정 불가.

LastTaskResult=21은 LastRunTime이 갱신되지 않았다면 새로운 실패 증거로 취급하지 않는다.

### STEP E — 후속 조치 제안

원인별로 최소 변경 방안을 제안한다.

- A: 로그아웃 없이 Scheduler 작업을 1회 수동 호출해 실행 환경을 검증하는 방법 검토
- B: 계정 식별 정보 확인
- C: 안전한 Trigger 변경안 마련
- D: Task Scheduler 이벤트 및 실행 조건 분석
- E: 스크립트 Exit Code/Watchdog 로그 분석
- F: 추가 읽기 전용 증거 수집

중요:

`Start-ScheduledTask`는 일반 Watchdog를 실제로 실행하므로 이번 작업에서 호출하지 않는다.

이 명령은 사용자가 별도 승인한 이후, 모든 서비스가 UP이고 복구 계획이 0건임을 다시 검증한 상태에서만 제안한다.

로그아웃/재부팅 역시 이번 작업에서 금지한다.

### 절대 금지

- Windows 로그아웃 또는 재부팅
- Enable/Disable/Register/Unregister-ScheduledTask
- Start-ScheduledTask
- watch-running-ai.ps1 일반 모드 실행
- 실제 서비스 재시작
- Watchdog State/PID/Sidecar 변경
- PostgreSQL 변경
- 운영 .env 수정
- 코드 수정 및 Git Push
- Garmin 동기화 및 훈련 게시
- 보안 로그 정책/권한 변경

### 최종 보고

1. Task State
2. LastRunTime/LastTaskResult/NextRunTime
3. 실제 Trigger XML
4. UserId/Principal 일치 여부
5. Repetition Interval/Duration
6. 실제 로그인 세션 확인 결과
7. TaskScheduler Operational 이벤트
8. 원인 A~F 판정
9. 운영 서비스 불변 여부
10. 최소 수정 또는 안전한 실행 검증 권고

조사 후 STOP한다.
