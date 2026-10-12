# WO-RUNNINGAI-PHASE-7B-3 — Garmin Running Dynamics & Lactate Threshold Intelligence (작업 지시서 원문)

## 1. 목표

RunningAI가 Garmin FR265에서 제공하는 러닝 다이내믹스 및 젖산역치 정보를 적극적으로 활용해
러닝 분석 정확도를 높이고, AI Coach가 개인 맞춤 훈련을 생성할 때 참고하도록 개선한다.

이미 구현된 Phase 6D 젖산역치 동기화 및 Phase 6H 상세 활동 수집 기능을 재사용한다.

Spring Boot 3.5.16 / Java 21 유지.

## 2. STEP A — 기존 구현 및 실데이터 감사

먼저 최신 Worktree 및 Git 상태를 확인한다.

Phase 7B-2의 미반영 코드가 있다면 보존하고, 최신 안전 게이트를 포함한 상태에서 작업한다.

대상은 2026-10-11 서울레이스 하프마라톤 Activity ID 7.

아래 Garmin 데이터가 실제로 저장되어 있는지 확인한다.

- directSpeed
- directHeartRate
- directRunCadence
- directPower
- directStrideLength
- directVerticalOscillation
- directVerticalRatio
- directGroundContactTime
- directGradeAdjustedSpeed
- directPerformanceCondition

각 데이터에 대해 수집 여부, 저장 위치, 단위, 결측률, 샘플 수 및 시간축 정합성을 확인한다.

Garmin 원본 descriptor의 단위와 factor를 검증한다. 단위를 추정해 변환하지 않는다.

## 3. STEP B — 러닝 다이내믹스 분석 엔진

기존 RunningActivityAnalysisService를 확장한다.

구간별로 다음을 계산한다.

**보폭**: 전체 평균 / 전반·후반 변화율 / 1km 구간별 평균 / 동일 페이스 범위에서의 변화

**수직진폭 및 수직비율**: 전체 평균 / 전반·후반 변화 / 구간별 추세 / 페이스 변화와의 관계

**지면접촉시간**: 전체 평균 / 후반 변화 / 속도 및 케이던스와의 관계

**러닝파워**: 평균·최대 파워 / 구간별 파워 / 동일 페이스에서의 파워 변화 / 파워와 속도·심박의 관계

운동 유형에 따라 제공되지 않는 항목은 null 처리한다.

통계 계산 시 결측치와 불규칙한 샘플 간격을 고려한다.

동일 페이스 비교는 데이터가 충분할 때만 수행한다. 비교 가능한 구간이 없다면 결과를 만들지
않는다.

특정 러닝 다이내믹스 수치로 부상 원인을 진단하거나 러닝 자세를 좋음/나쁨으로 판정하지 않는다.

## 4. STEP C — Garmin 젖산역치 연동 검증

기존 구현을 재사용한다.

- GarminGateway.lactate_threshold()
- GET /lactate-threshold
- GarminLactateThresholdMapper
- GarminAthleteProfileSyncService
- POST /api/v1/garmin/profile-sync
- athlete_intensity_profile

우선 **읽기 전용으로** 최신 Garmin 원본을 조회하고 현재 DB 값과 비교한다.

검증할 항목:

1. Garmin 최신 LTHR
2. Garmin 젖산역치 속도 및 환산 페이스
3. 측정·갱신 날짜가 제공되는지 여부
4. 기존 DB의 LTHR
5. 기존 DB의 역치 페이스
6. 최근 값 변경 여부
7. 수동 입력값과 자동 감지값의 차이
8. Garmin 심박존의 기준 설정

특히 과거 Phase 6D 실계정 검증의 180bpm / 4:50/km와 Phase 7B-1의 176bpm 차이를 조사한다.

Garmin 응답의 speed 변환 규칙(기존 실측 기반 0.1m/s 단위)을 재검증한다.

조회 결과를 곧바로 운영 프로필에 덮어쓰지 않는다.

## 5. STEP D — 프로필 신뢰도 및 이력 설계

기존 모델에는 젖산역치 값의 출처와 변경 이력이 없다.

최소한 다음 정보를 관리할 수 있도록 설계를 제안한다.

- LTHR 수치 / 역치 페이스 / 값의 출처: MANUAL / GARMIN / Garmin 측정 시각(제공되는 경우) /
  RunningAI 조회 시각 / 마지막 변경 시각 / 이전 값 / 실제 적용 여부

과거 분석의 젖산역치 기준과 현재 기준이 달라져도, 언제 어떤 설정으로 계산했는지 추적할 수
있어야 한다.

Garmin 최신값이 조회됐다는 이유만으로 신뢰할 수 있다고 단정하지 않는다.

자동 반영 및 Scheduler 활성화는 별도 승인 대상으로 둔다.

## 6. STEP E — 서울레이스 재분석

새 분석 엔진으로 Activity ID 7을 다시 평가한다.

핵심 질문:

- 14~20km 페이스 저하가 보폭 감소와 관련되는가?
- 케이던스와 지면접촉시간은 어떻게 변화했는가?
- 후반 수직진폭·수직비율의 추세는 어떠한가?
- 러닝파워 감소 또는 파워 대비 속도 변화가 나타나는가?
- 초반 과속과 후반 페이스 저하의 관계는 무엇인가?
- LTHR 기준 차이가 기존 심박 해석에 얼마나 영향을 미치는가?

반드시 실제 수치를 보여준다.

속도 저하와 다른 지표 사이의 상관관계를 인과관계로 단정하지 않는다.

측정값, 통계 분석, AI 해석을 분리한다.

## 7. STEP F — AI Coach Context 확장

새 분석 결과를 TrainingContextV2에 반영한다.

Context에는 모든 Garmin 샘플을 직접 전달하지 않는다. 대신 요약 통계를 전달한다.

- 러닝 다이내믹스 전후반 변화 / 주요 구간별 변화 / 파워·속도·심박 관계 / 젖산역치 값 및 출처 /
  분석 데이터 품질 / 설정값이 불확실한 경우의 경고

기존 Coach Context 크기 제한을 유지한다.

Phase 7B-2에서 구현한 Injury Safety Gate를 우회하지 않는다.

## 8. STEP G — 테스트

단위 테스트와 통합 테스트에 다음을 포함한다.

- 각 Garmin 동적 지표의 파싱 및 단위 검증
- 샘플 결측/NaN/불규칙 간격 처리
- 전후반 및 구간별 계산
- 러닝파워·보폭 분석
- 젖산역치 현재값 비교
- 수동값/자동값 충돌
- 변경 이력 및 분석 재현성
- AI Coach Context 직렬화
- 기존 분석 API 호환성
- Injury Safety Gate 회귀
- 기존 전체 Spring 테스트

테스트 중 실제 Garmin 계정에 쓰기 작업을 하지 않는다.

## 9. 결과 보고

다음을 한글로 제출한다.

1. 실제 Garmin 수집 데이터 목록
2. 아직 활용하지 않는 데이터 목록
3. 신규 분석 지표와 계산 방식
4. 10월 11일 하프 재분석 리포트
5. Garmin 젖산역치 실제 조회 결과
6. 기존 176bpm과 Garmin 값 비교
7. LTHR/심박존 설정 신뢰도 평가
8. AI Coach 반영 결과
9. 테스트 결과
10. 추가 개선 과제

## 10. 안전 및 Git 규칙

- 개발 Worktree main-3 사용
- 운영 DB 마이그레이션 금지
- 운영 프로필 자동 덮어쓰기 금지
- 운영 Spring/Connector/Relay 재시작 금지
- 실제 훈련 승인·게시 금지
- Watchdog/Tailscale 변경 금지
- Canonical main 및 origin/main 변경 금지
- 다른 Worktree와 레거시 저장소 변경 금지

코드·테스트 작업은 개발 환경에서 수행하고, 검증이 끝난 변경만 개발 브랜치에 커밋한다.

완료 후 STOP.

## 완료 기준

**Garmin 러닝 다이내믹스를 실제 운동 데이터에서 계산하고, 젖산역치의 출처와 최신성을 확인하여,
AI Coach가 더 정확한 분석 근거를 활용하는 것이 목표다.**

단순히 필드를 추가하거나 Garmin 응답을 저장했다는 이유만으로 완료 처리하지 않는다.
