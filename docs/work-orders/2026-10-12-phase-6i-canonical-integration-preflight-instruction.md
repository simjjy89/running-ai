# RunningAI Phase 6I — Canonical Integration Preflight (작업 지시서 원문)

## 역할

RunningAI 시니어 백엔드 아키텍트 / 개발 리드 / QA.

## 현재 상태

* EXTERNAL_DATA_PATH_READONLY_CHECK = B
* CONNECTIVITY_PASS_NO_PLAN
* Intervals.icu API 인증 HTTP 200
* Local/Public Relay NO_PLAN 응답 일치
* REBOOT_PRECHECK = GO
* PHASE_6I_1_EXTERNAL_ACCESS_E2E_READY = 미선언
* 최신 검증 커밋: 4c054cd
* origin/main-3 Push 완료
* Canonical main 및 origin/main 미변경

## 이번 작업: READ-ONLY PREFLIGHT

1. main-3와 Canonical main의 HEAD, 브랜치 관계, Git 상태를 확인한다.
2. 두 브랜치의 전체 변경 이력을 비교하고 누락·충돌·의도치 않은 변경을 확인한다.
3. External Relay의 환경변수 로딩 우선순위, Mock 비활성화, 인증 처리, NO_PLAN 분기, 에러 처리 및 민감정보 노출 가능성을 감사한다.
4. 기존 테스트 결과와 실행 가능한 회귀 테스트 범위를 검토한다.
5. FF-only 병합 가능 여부를 판단한다.
6. 실제 WORKOUT 데이터가 없는 상태에서 검증 가능한 항목과 실제 WORKOUT이 필요한 항목을 구분한다.
7. Canonical Watchdog DryRun 및 Relay-only 복구를 위한 실행 전 조건을 작성한다.

## 절대 금지

* 레거시 C:\running-ai 변경
* Canonical main 변경 또는 병합
* 운영 서비스 중지·재시작
* DB 변경
* .env 및 인증 토큰 수정
* 재부팅
* origin/main Push
* 실제 WORKOUT 생성·수정·삭제
* 민감정보 원문 출력

## 결과 보고

* Git 정합성
* 코드 및 보안 감사 결과
* 테스트 검토 결과
* FF-only 병합 가능 여부
* 남은 위험 및 검증 공백
* 다음 단계 GO / NO-GO

이번 작업은 검토 전용이다. 결과 보고 후 반드시 STOP한다.
