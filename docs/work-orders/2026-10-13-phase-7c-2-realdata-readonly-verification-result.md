# Phase 7C-2 실데이터 읽기 전용 MCP 검증 (결과 보고: PASS)

지시: OAuth 실연동(Auth0)은 후속 Phase로 보류하고, 인증 구축 없이 읽기 전용 MCP를 실데이터로
최대한 검증. 기준: `main-3` `5b5b00c`. **이번 Phase는 서버 코드 무변경(검증+문서만)** —
Gradle 전체 회귀는 7C-2b의 1,264/1,264가 그대로 유효하므로 재실행하지 않았다(지시 준수).

## 검증 방법 (운영 보호 설계)

운영 Spring(18080)은 구버전 코드(V20)로 기동 중 + 재시작 금지, 운영 DB 마이그레이션 금지
→ **운영 PostgreSQL 컨테이너 안에 사본 DB `running_ai_7c2`를 `pg_dump | psql`로 생성**
(활동 8·샘플 21,828행·V20 그대로 복제)하고, main-3 코드의 개발 Spring을 **18081 포트 + 사본
DB** 로 기동(`RUNNINGAI_MCP_READ_ENABLED=true`, Garmin/Intervals base-url은 죽은 포트
127.0.0.1:9로 고정 — 외부 호출이 시도조차 성공할 수 없는 구성). Flyway V21/V22는 **사본에만**
적용됨을 로그로 확인, 운영 `flyway_schema_history`는 검증 전/후 모두 20행.

사전 작업(사본에만 쓰기, 전부 기존 API 사용): ① `POST /activities/7/analysis` 재분석 —
V22 다이내믹스 컬럼이 실데이터로 채워짐 ② `POST /athlete/injury-reports`로 RESOLVED 1건
시딩(억제 케이스 검증 시엔 삭제 후 재시딩).

## 1·2. 도구 7종 실데이터 정확성 — 전부 기존 분석 결과와 일치

`POST 18081/mcp` (Streamable HTTP; `Accept: application/json, text/event-stream` 필수 —
curl 검증 시 이 헤더 누락이 400을 내는 것도 확인, ChatGPT/SDK 클라이언트는 항상 보냄):

| 도구 | 실측 응답 | 대조(기존 문서/DB) |
|---|---|---|
| get_recent_activities(30d) | 8건 최신순 — 10-11 RUN 6,723s/21,240m/HR177 ... 전건 analysis·intervals true | activity 테이블과 일치, ID 미노출 |
| get_activity_analysis(10-11) | decoupling **6.239**, speedChg **-5.94%**, hrChg **+0.32%**, zone5 **92.84%**, lthr100 **4,738s**, maxHR 191 | 7A-1 보고서와 전부 일치 |
| 〃 runningDynamics | stride **107.62cm/-4.33%**, GCT **246.3ms/+3.27%**, VO **8.72cm/+0.03%**, VR **+4.58%**, power **321W/-5.37%**, max **653W**, samePace stride **-2.23cm**/power -9W | 7B-3 실측·단위테스트 값과 일치 (power lap 평균 320.95 — DB 직접 집계 320.38은 샘플 기반, lap 기반 설계대로) |
| get_training_context | sizeBytes 11,374(64KiB 가드 내)·sha256, coverage 8/8, ctl/atl/form **16.01/28.10/-12.10**(ageDays 1), recent[0]=10-11에 dynamics 포함, provenance warning 존재 | 7A-1 fitness 표와 일치 |
| get_recovery_context(10-12) | HRV **30.0 vs 42.7(-29.8%)**, 수면 **3.43h/39점(-30.5%)**, BB **25(-48.9%)**, RHR **58(+4.6%)**, 전부 ageDays 0·AVAILABLE | 7A-1 회복 표와 일치 |
| get_fitness_trend(10-06~12) | 7일 전부 — 10-10 **12.64/10.98/+1.66**, 10-11 **16.39/32.42/-16.03**, 10-12 16.01/28.10/-12.10 | 7A-1 표와 일치 |
| get_workout_recommendation | injury 0건 상태: **억제**(NO_INJURY_STATE_RECORDED, recommendation 미반환) / RESOLVED 시딩 후: **EASY 30–60분**, 근거 RECENT_REST·LOAD_TREND_DECREASING 등 + "AI 처방 아님·무저장" 주석 | 7B-2 게이트 의미 그대로 |
| get_athlete_profile | **176bpm/290s**, sourceTracked=false, profileAgeDays 7, 경고문 | 운영 프로필 값·7B-3 provenance와 일치 |

`publish_workout` 강제 호출(실서버): Spring AI 자체 "Tool not found" 오류 — 읽기 모드에서
**등록 자체가 없음**을 실서버에서도 확인.

## 3. 무변경 증명 (실DB 사본)

9개 지표 스냅숏(activity/analysis/draft/injury/recovery/fitness/sync_state/sample counts +
profile updated_at) → **7종 전체 호출** → 재스냅숏: **완전 동일 (NO-WRITE PASS)**.
Garmin/Intervals는 죽은 포트 고정이라 쓰기는커녕 읽기 호출도 물리적으로 불가 + 7C-2a
아키텍처 테스트가 코드 레벨에서 이중 보장. Draft/승인/게시 0(쓰기 스위치 전부 off 기동).

## 4~6. 제약 준수

- ChatGPT 외부 연결: 이번 Phase에서 **신규 외부 노출 0** (7C-1.1의 가짜 데이터 PoC 결과가
  유일한 외부 실측이며, 실데이터는 어떤 형태로도 공개 주소에 연결하지 않았다).
- OAuth Gateway·테스트 코드: **무변경 유지** (`tools/mcp-gateway` 27/27 그대로).
- 운영: Spring 무재시작(검증 내내 18080 UP), 운영 DB V20 불변, Funnel 443 불변(8443 항목 0,
  relay 200). 사본 DB는 검증 후 **DROP**, 18081 프로세스 종료 확인.

## 7. 실데이터 → ChatGPT 연결 잔여 체크리스트 (후속 Phase 승인 항목)

**A. 인증 (7C-2b STEP 4·6 보류분)**
- [ ] Auth0 무료 테넌트 생성: API(audience)+scope `runningai:read`+사용자 1명+가입 차단
- [ ] "Auth for MCP"(CIMD/DCR) 무료 플랜 가용성 실측 — 유료 요구 시 사전등록 클라이언트 fallback
- [ ] ChatGPT OAuth 화면 실측: callback URL → Auth0 redirect allowlist 등록
- [ ] 게이트웨이 env 구성(issuer/audience/allowed_sub/public_base_url) 후 **가짜 PoC 대상**으로
      Quick Tunnel OAuth E2E (로그인·동의·인증 호출·미인증/타권한 거부·Relay 정상)

**B. 운영 데이터 준비 (별도 승인)**
- [ ] 운영 PostgreSQL에 V21/V22 적용 + 운영 Spring을 main-3 빌드로 재기동(승인 필요)
- [ ] 재기동 직후 1회: injury RESOLVED 보고 입력(7B-2 — 없으면 추천류 전부 REST-only) +
      Activity 7(및 최근 런) 재분석으로 다이내믹스 채움
- [ ] 운영 Spring에 `RUNNINGAI_MCP_READ_ENABLED=true`(쓰기 스위치는 전부 off 유지)

**C. 고정 주소 + 연결 (별도 승인)**
- [ ] Funnel 443에 `--set-path /mcp` + `--set-path /.well-known/oauth-protected-resource` →
      게이트웨이(대상=운영 127.0.0.1:18080/mcp) 마운트 — Relay `/` 불변, 전후 백업 비교
- [ ] Auth0 audience/API identifier를 고정 주소로 확정, ChatGPT 플러그인 재등록(OAuth)
- [ ] E2E: 목표 질문 4종 실검증(7C-3) → 이상 시 `/mcp` 경로 해제로 즉시 차단
- [ ] 운영 감시: 게이트웨이 프로세스를 Watchdog 감시 대상에 추가할지 결정

**결론: 읽기 전용 MCP 파이프라인은 실데이터 기준으로 완전 검증 완료.** 남은 것은 위
체크리스트의 인증·배포 작업뿐이며 전부 별도 승인 게이트 뒤에 있다.
