# Phase 7C-3 — ChatGPT 실데이터 연동 런북 (STEP 1 완료 + STEP 2 Auth0 절차; 사용자 조작 대기)

목표: ChatGPT → Quick Tunnel → **기존 `tools/mcp-gateway`(OAuth, 무수정)** → 개발 Spring(사본
PostgreSQL) 경로로, 인증된 본인만 실제 Garmin·Intervals 분석을 조회. 기존 구현 재사용 —
이번 Phase에서 새로 만든 코드는 **게이트웨이 테스트 2건 추가뿐**(아래 STEP 1-5).

## STEP 1 — 현재 상태 정리 (완료)

1. main-3 @ `50f86d0`(7B-4), 소스 clean. 3. **7C-2 스모크 문서 2건 검토 후 커밋**(이 커밋):
   검토 결론 — 합성 데이터·무인증 PoC로서 적절했고, 핵심 산출은 **ChatGPT가 initialize 전에
   `server/discover`를 보내며 5xx를 받으면 앱 생성을 중단한다**는 실측. 보안 정리(터널/필터/
   18090 종료, 합성 DB DROP, worktree 제거, 운영 V20·Funnel 불변) 기록 확인.
2. V23 포함 확인(`V23__create_athlete_intensity_profile_history.sql`).
4. 7B-4 결과 문서의 롤백 절차 정정: **flyway_schema_history 수동 삭제는 운영 절차가 아님** —
   표준 롤백은 코드 롤백만, V23 테이블·이력은 무해하게 잔존.
5. 호환성: `tools/mcp-gateway` `npm test` **29/29 PASS** — 스모크 실측을 반영해
   `server/discover` 2건 추가(미인증 → 401+resource_metadata = OAuth 트리거 / 인증 후 →
   HTTP 200 JSON-RPC **-32601**(절대 5xx 아님)·백엔드 무접촉). 읽기 도구 7종은 7B-4 전체
   회귀(1,272/1,272, 같은 커밋)로 이미 검증 — **Gradle 재실행 없음**(지시 준수).

## STEP 2 — Auth0 설정 절차 (사용자 조작; 아래 "사용자 안내" 그대로 수행)

게이트웨이가 기대하는 값(코드 무수정으로 환경변수만):

| env | 값 |
|---|---|
| `RUNNING_AI_OAUTH_ISSUER` | `https://<테넌트도메인>/` (끝 슬래시 포함) |
| `RUNNING_AI_OAUTH_AUDIENCE` | Auth0 API Identifier = **`https://<터널주소>/mcp`** (터널 발급 후 입력) |
| `RUNNING_AI_ALLOWED_SUB` | Auth0 사용자 `user_id` (`auth0|...`) |
| `RUNNING_AI_PUBLIC_BASE_URL` | `https://<터널주소>` |
| `RUNNING_AI_GATEWAY_TARGET_URL` | `http://127.0.0.1:18081/mcp` (개발 Spring) |
| `RUNNING_AI_ALLOWED_TOOLS` | (기본값 = 읽기 7종, 설정 불필요) |

Quick Tunnel 주소는 매 실행 변하므로 **주소 재발급 시 재등록 대상**: ① Auth0 API
Identifier(=audience) ② Auth0 앱 Allowed Callback URLs(ChatGPT callback은 불변이면 유지)
③ ChatGPT 커넥터의 Server URL. (고정 주소 전환은 7C-2c Funnel `/mcp` 승인 후.)

## STEP 3 — 실데이터 테스트 구성 (Auth0 완료 후 제가 수행)

7C-2 실데이터 검증과 동일 패턴 재사용: ① `pg_dump` 사본 `running_ai_7c3` 생성(운영 V20
불변) ② 개발 Spring 18081 기동(main-3 빌드, V21~**V23** 사본에만 적용, read-enabled=true,
쓰기 전부 off, Garmin/Intervals base-url 죽은 포트) ③ 사본에 **실제 현재 무릎 상태**를
injury-report로 입력 — 통증이 남아 있으므로 **ACTIVE**(보행통증 등 실측 그대로; RESOLVED
금지 — 추천 도구가 안전 판정만 반환하는 것이 정답 동작) ④ Activity 7 재분석(다이내믹스)
⑤ 게이트웨이 17910 기동(위 env) ⑥ `cloudflared tunnel --url http://127.0.0.1:17910` ⑦
터널 주소로 Auth0 audience/callback 정렬 ⑧ 외부에서 무토큰 401·위조 401·메타데이터 검증
후 ChatGPT 등록 안내.

## STEP 4 — ChatGPT 검증 시나리오 (사용자 계정)

OAuth 로그인(Auth0 화면) → 동의 → 커넥터 활성 후: ① "최근 러닝 5개 분석" ② "서울레이스
하프 후반 페이스 저하 분석" ③ "최근 HRV·훈련부하 분석" ④ "현재 무릎 상태를 고려한 훈련
추천"(→ 안전 게이트 응답 확인). 응답 수치를 7A-1/7B-3 문서값과 대조. 비인가 검증: 로그아웃
상태/타 계정 시도 시 거부. 종료 후 터널·게이트웨이·18081 종료, 사본 DROP, 운영 재확인.
