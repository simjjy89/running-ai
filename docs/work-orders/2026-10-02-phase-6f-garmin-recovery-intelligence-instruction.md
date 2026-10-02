RunningAI Phase 6F 작업을 시작한다.

# Phase 6F : Garmin Recovery Intelligence

## 현재 환경

현재는 External PC에서 작업 중이다.

Repository root 기준으로 작업한다.
경로명(C:\running-ai-github, C:\running-ai)에 의존하지 않는다.

작업 시작 전 확인:

- git status
- git branch
- Phase 6E commit 존재 여부
- working tree clean 여부

---

# 작업 목표

Phase 6E에서 구현된 Claude AI Coach는 현재:

- TrainingState
- Training Load
- LTHR
- LT Pace

기반으로 WorkoutDraft를 생성한다.

하지만 recovery 정보가 없다.

이번 Phase 목표:

Garmin recovery 데이터를 수집하고,
개인 baseline 대비 상태를 계산하여,
ClaudeAiCoach가 WorkoutDraft 생성 시 활용하도록 한다.

데이터 흐름:

Garmin
 ↓
Recovery Snapshot
 ↓
Recovery Context
 ↓
Training Context
 ↓
ClaudeAiCoach
 ↓
WorkoutDraft


---

# 절대 지켜야 하는 설계 원칙

## Spring 역할

Spring은 아래까지만 담당한다.

- Garmin recovery 데이터 수집
- 데이터 정규화
- DB 저장
- baseline 계산
- deviation 계산
- RecoveryContext 생성
- Claude context 전달


Spring에서 훈련 결정을 하지 않는다.

금지:

- HRV 낮음 → Easy Run 강제
- Sleep 부족 → Interval 제거
- RHR 증가 → Long Run 변경


훈련 선택과 rationale은 ClaudeAiCoach가 판단한다.


---

## 이번 Phase 범위 제외

절대 구현하지 않는다.

- Intervals workout publish
- Garmin workout write
- approve workflow
- 자동 publish scheduler 변경


이번 Phase는 Draft 생성까지만 한다.


---

# Garmin 조사 결과

사용 라이브러리:

garminconnect 0.3.16


확인된 method:


HRV

get_hrv_data(date)
get_hrv_data_range(start,end)


Sleep

get_sleep_data(date)
get_sleep_daily(start,end)


Resting Heart Rate

get_rhr_day(date)
get_rhr_daily(start,end)


Body Battery

get_body_battery(start,end)


Stress

get_stress_data(date)
get_all_day_stress(date)


실제 library source 기준으로 adapter 작성한다.

schema 추측 금지.

typed.py가 experimental이면 결과 문서에 기록한다.


---

# Architecture

다음 구조로 구현한다.


Garmin Connector

↓

GarminRecoveryClient

↓

RecoveryMapper

↓

RecoverySnapshot

↓

RecoveryRepository

↓

RecoveryBaselineService

↓

RecoveryContextBuilder

↓

TrainingContextBuilder

↓

ClaudeAiCoach

↓

WorkoutDraft


---

# Database

Flyway migration 추가.

일별 recovery snapshot 저장.


테이블:

garmin_recovery_daily


필요 정보:

- athlete_id
- recovery_date

- HRV
- HRV status

- sleep duration
- sleep score

- resting heart rate

- body battery

- stress


조건:

athlete_id + recovery_date unique

같은 날짜 재수집은 idempotent 처리.


---

# Baseline

기준:

최근 28일


최소 데이터:

7 valid days


계산:

- current value
- baseline value
- difference
- difference percentage
- sample count


데이터 부족:

INSUFFICIENT_DATA


처리.


Spring은 계산만 한다.

"좋다/나쁘다" 판단하지 않는다.


---

# RecoveryContext 추가

Phase 6E TrainingContext에 recovery 영역 추가.


예:

RecoveryContext:

- hrv
- sleep
- restingHeartRate
- bodyBattery
- stress


없는 데이터:

null 처리.

추측값 생성 금지.


---

# API 추가

Manual sync endpoint 필요.


예:

POST /api/v1/garmin/recovery-sync


Request:

{
 "date":"2026-10-02"
}


Response:

{
 "date":"2026-10-02",
 "updated":true,
 "availableMetrics":[]
}


---

# Backfill

가능하면 최근 28일 recovery history sync 구현.


조건:

- Garmin rate limit 고려
- sequential 처리
- 429 폭주 retry 금지


---

# Claude Prompt 변경

Claude에게 전달하는 TrainingContext에 RecoveryContext 추가.


중요:

없는 recovery 데이터는 생성하지 않는다.

null 그대로 전달.


Claude가:

- workout 종류
- 시간
- 강도
- rationale

을 판단한다.


---

# Evaluation 추가

다음 상황 테스트 추가:

1. HRV 감소
2. Resting HR 증가
3. Sleep 부족
4. Body Battery 낮음
5. Stress 증가
6. Recovery 정상
7. Mixed signal
8. Recovery 없음
9. Recovery stale
10. 사용자 피로 호소 + wearable 정상


검증:

- missing data 생성 금지
- unsafe workout 없음
- validator 통과
- recovery context 반영


---

# Test

Python:

- Garmin parsing
- missing field
- malformed response
- error handling
- normalized contract


Spring/Kotlin:

- migration
- persistence
- idempotency
- baseline
- RecoveryContext
- Claude integration
- API


전체 regression GREEN 목표.


---

# Live Validation

가능하면:

- Garmin 실제 read
- recovery sync
- 28일 backfill
- baseline 계산
- recovery 기반 Claude Draft 생성


불가능하면:

NOT_RUN

으로 기록.

Fake PASS 금지.


---

# Documentation

작업 시작 시:

docs/work-orders/
2026-10-02-phase-6f-garmin-recovery-intelligence-instruction.md


완료 후:

docs/work-orders/
2026-10-02-phase-6f-garmin-recovery-intelligence-result.md


작성.


---

# Git

완료 후:

- git status clean
- commit 생성
- push 여부 기록


예상 commit:

feat: add Garmin recovery intelligence
feat: integrate recovery context into AI coach
test: add recovery aware coach evaluation
docs: record Phase 6F result


작업 시작.
