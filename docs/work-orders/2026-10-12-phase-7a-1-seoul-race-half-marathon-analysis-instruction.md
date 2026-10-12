# WO-RUNNINGAI-PHASE-7A-1 — 서울레이스 하프마라톤 실제 데이터 분석 및 AI Coach 검증 (작업 지시서 원문)

## 목표

2026-10-11 서울레이스 하프마라톤의 실제 Garmin·Intervals.icu 데이터를 RunningAI로 분석하고, 분석 결과를 개인 맞춤 훈련 생성에 활용할 수 있는지 검증한다.

기존 분석 엔진과 AI Coach 구현을 최대한 재사용한다.

### STEP 1 — 최신 상태 확인

* main/main-3 기준 SHA: 370ee8f
* PostgreSQL, Garmin Connector, Spring Health 확인
* 현재 DB Activity 최신 시점 확인
* 2026-10-11 하프마라톤 존재 여부 확인
* 기존 RunningActivityAnalysisService, CoachDataRefreshService, WorkoutDraftService의 기능 및 API 확인

운영 서비스는 중지하거나 재시작하지 않는다.

### STEP 2 — 실제 운동 데이터 수집

하프마라톤이 DB에 없다면 기존 Coach Data Refresh 파이프라인을 사용한다.

* Garmin 활동 증분 동기화
* Garmin 상세 데이터·Lap·Sample 수집
* RunningActivityAnalysisService 분석
* Intervals.icu 활동 매칭 및 Fitness 데이터 갱신
* Garmin Recovery 데이터 갱신

허용된 기존 증분 동기화 기능만 사용하고, 중복 저장·무제한 재시도·전체 DB 초기화는 금지한다.

401/403/429 등 오류가 발생하면 원인을 보고하고 중단한다.

### STEP 3 — 하프마라톤 심층 분석

반드시 실제 데이터를 근거로 다음 항목을 산출한다.

1. 거리, 기록, 평균 페이스
2. 1km 랩별 페이스
3. 전반/후반 페이스 및 속도 변화
4. 후반 페이스 저하 시작 시점
5. 구간별 심박수와 페이스 변화
6. 케이던스 변화
7. RunningAI Speed-HR Decoupling
8. 장거리 후반 피로 패턴
9. 최근 운동 대비 훈련부하
10. Garmin Recovery 및 Intervals Fitness/Fatigue/Form과의 관계

수치 분석과 AI Coach의 해석은 명확히 분리한다.

누락 데이터는 추정하지 않고 null 또는 분석 불가로 표시한다.

### STEP 4 — 실제 사용자 피드백 반영

이미 보고된 내용:

* 후반 다리가 무겁고 힘이 빠짐
* 레이스 중 무릎 바깥쪽 통증
* 레이스 직후 보행 시에도 통증

이 증상들을 분석에 반영한다.

단, 현재 통증이 사라졌다고 가정하지 않는다. 현재 상태가 미확인이라면 달리기 재개나 고강도 훈련을 처방하지 않는다.

통증의 의학적 원인을 단정하지 않는다.

### STEP 5 — AI Coach 훈련 생성 준비

다음 정보가 TrainingContextV2에 정확히 전달되는지 확인한다.

* 10월 11일 하프 분석 결과
* 최근 러닝 기록
* Garmin 회복 데이터
* Intervals.icu 훈련부하
* 사용자 통증·피로 피드백
* 기존 목표 기록

readyForCoach를 확인한다.

훈련 생성에 필요한 데이터가 충분하고 통증 관련 조건이 안전하게 처리된다면 DRAFT만 생성한다.

현재 통증 상태가 불명확하면 러닝 Draft 생성을 보류하고 필요한 정보를 보고한다. 휴식을 우선하는 방향을 유지한다.

APPROVE 및 PUBLISH는 수행하지 않는다.

### STEP 6 — 사용자에게 제공할 결과

다음 4개를 한글로 정리한다.

A. 하프마라톤 분석 리포트

실제 기록과 수치, 페이스 저하 원인에 대한 근거 기반 해석.

B. 회복 분석

Garmin 회복 지표, Intervals.icu 부하, 사용자 통증 피드백을 함께 해석.

C. 다음 훈련안

가능하면 RunningAI AI Coach가 생성한 DRAFT를 제시. 생성 불가능하면 명확한 보류 사유를 제시.

D. 개선 과제

실제 분석을 수행하면서 드러난 정확도·데이터 수집·프롬프트·훈련 생성 문제를 우선순위대로 정리.

## 개발 규칙

* 기존 기능 우선 재사용
* 불필요한 신규 아키텍처 구현 금지
* 코드 수정은 main-3에서만
* 실제 Workout 게시 금지
* Watchdog/Relay/Tailscale 설정 변경 금지
* PC 재부팅 금지
* 다른 Worktree와 레거시 저장소 변경 금지
* origin/main Push 금지

## 완료 기준

실제 10월 11일 러닝 데이터를 분석하고 AI Coach의 해석 품질을 검증하는 것이 최우선이다.

인프라 테스트 통과만으로 Phase 7A를 완료 처리하지 않는다.

완료 후 분석 결과와 다음 훈련 생성 가능 여부를 보고하고 STOP한다.
