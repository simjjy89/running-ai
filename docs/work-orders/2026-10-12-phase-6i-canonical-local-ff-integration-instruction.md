# RunningAI Phase 6I — Canonical Local FF-only Integration (작업 지시서 원문)

## 역할

RunningAI 시니어 백엔드 아키텍트 / 개발 리드 / QA.

## 목표

검증된 origin/main-3 변경사항을 Canonical 로컬 main에 FF-only 방식으로 통합한다.

이번 작업은 Git 통합까지만 수행한다. 운영 서비스와 외부 공개 경로는 절대 변경하지 않는다.

## 기준 정보

* Canonical: C:\running-ai-github
* 개발 브랜치: main-3
* Canonical main 기존 HEAD: 43ed6af
* origin/main-3 기존 감사 HEAD: 4c054cd
* origin/main-3 최신 보고 HEAD: 54aa849
* 레거시 C:\running-ai: 변경 금지
* 운영 상태: REBOOT_PRECHECK=GO
* 외부 데이터 경로: CONNECTIVITY_PASS_NO_PLAN
* 실제 WORKOUT E2E: 미검증

### STEP 1 — 병합 전 상태 확인

1. Canonical 저장소의 현재 브랜치와 HEAD 확인.
2. origin/main 및 origin/main-3의 최신 참조 확인.
3. 모든 관련 worktree의 Git 상태 확인.
4. 최신 54aa849 커밋의 변경사항 확인.
5. main..origin/main-3 전체 diff 재검토.
6. Git merge-base로 FF-only 가능 여부 재확인.
7. 기존 미추적 문서 4개의 존재 및 경로 확인.

### STOP 조건

* Canonical main HEAD가 예상과 다름
* 최신 커밋에 미감사 코드 변경이 존재
* docs/work-orders/ 외의 예상치 못한 변경 발견
* 브랜치가 분기됨
* 미추적 파일과 병합 대상 파일이 충돌
* 운영 파일이나 민감정보 변경이 필요함

### STEP 2 — Canonical 로컬 FF-only 병합

모든 사전 조건을 충족한 경우에만 실행한다.

Canonical 저장소에서:

```
git merge --ff-only origin/main-3
```

절대 수행하지 말 것:

* 일반 merge commit
* rebase
* reset –hard
* git clean
* force push
* 자동 stash
* 미추적 파일 삭제 또는 이동
* 임의의 추가 커밋

병합 실패 시 임의 복구를 시도하지 않고 STOP한다.

### STEP 3 — 병합 후 정합성 검증

1. Canonical main HEAD와 origin/main-3 HEAD 동일 여부 확인.
2. 병합 전후 변경 파일 목록 비교.
3. 예상 밖의 파일 변경 여부 확인.
4. 미추적 문서 4개의 보존 여부 확인.
5. 기존 운영 상태를 변경하지 않았는지 확인.
6. origin/main은 기존 상태로 유지됐는지 확인.
7. 추가 커밋이나 불필요한 Git 변경이 발생하지 않았는지 확인.

### STEP 4 — 다음 단계 사전 준비

다음 작업에 필요한 조건만 문서화한다.

* Canonical Watchdog DryRun
* Relay-only 중지 및 복구
* 내부/외부 인증 HTTPS 검증
* 실제 WORKOUT 데이터 경로 검증
* FR265 실물 동기화 검증
* origin/main Push
* 재부팅 및 로그온 자동 복구

위 항목은 이번 작업에서 실행하지 않는다.

## 결과 보고 형식

1. PRE_MERGE_GATE = PASS / FAIL
2. CANONICAL_LOCAL_FF = PASS / FAIL / NOT_EXECUTED
3. 병합 전후 Git HEAD
4. 변경 파일 및 커밋 목록
5. 미추적 문서 보존 결과
6. Canonical 작업 트리 상태
7. origin/main 불변 확인
8. 운영 서비스 불변 확인
9. WATCHDOG_DRYRUN_READY = GO / NO_GO

## 절대 제약

* 운영 프로세스 중지·재시작 금지
* DB 변경 금지
* .env 및 토큰 변경 금지
* Watchdog 실행 금지
* origin/main Push 금지
* 재부팅 금지
* 레거시 저장소 변경 금지
* E2E READY 선언 금지

검증 통과 후 결과를 보고하고 반드시 STOP한다.
