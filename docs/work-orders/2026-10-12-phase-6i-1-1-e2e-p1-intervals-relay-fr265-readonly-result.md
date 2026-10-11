# WO-RUNNINGAI-PHASE-6I-1.1-E2E-P1 — Intervals.icu → External Relay → Garmin FR265 Read-Only Validation (결과 보고)

지시서: `2026-10-12-phase-6i-1-1-e2e-p1-intervals-relay-fr265-readonly-instruction.md`

읽기 전용 점검만 수행했다. 실제 훈련 생성·수정·게시, API 키/Watch Token 회전, 운영
서비스 재시작, PC 재부팅은 수행하지 않았다. 기존 `REBOOT_PRECHECK=GO`(Phase 6I-1.7D-3)
상태는 그대로 유지된다.

## 1. 운영 Preflight (STEP A, 전부 PASS)

- Git: Canonical HEAD `43ed6af2049e1564fb35b76dbd8e22d99774ec19`(기대값과 일치),
  Working Tree Clean.
- Docker/PostgreSQL: `8ff2a3adc495 running-ai-postgres`, `healthy`, `Up 7 hours`.
- Connector `8765/health`: `200`. Spring `18080/actuator/health`: `200`.
  Relay `17845/health`: `200`.
- Tailscale: 서비스 `Running`, Funnel `https://laptop-mmdqucic.tail4597a5.ts.net`
  → `127.0.0.1:17845` (On, 단일 대상만 프록시 — 다른 내부 포트 노출 없음).
- Watchdog: `State=Ready`(Enabled), `LastRunTime=2026-10-12 08:04:16`,
  `LastTaskResult=0`. 최근 로그 3건 전부 `state=UP action=NONE result=OK`.
- Restart Budget/LongTerm Lockout: 5개 컴포넌트 전부 `0`/해제 — 비정상 Recovery
  Action 없음.
- PID/CreationDate: Spring `29984`(`00:45:12`), Connector Launcher
  `25372`/Listener `28684`(둘 다 `00:45:08`), Relay `13588`(`00:45:29`) —
  Phase 6I-1.7D-3 기준선과 완전히 동일.
- PostgreSQL Volume: `running-ai-github_running_ai_postgres_data` — 불변.
- DB 기준선: `activity` 6행, `garmin_sync_state`
  `high_water_started_at=2026-10-05 15:02:39+00`,
  `last_successful_sync_at=2026-10-05 16:24:14.229834+00` — 불변.

비정상 사항 없음. 외부 연동 검증 진행.

## 2. Intervals API 인증 상태

`.env`의 `INTERVALS_ICU_API_KEY`로 직접 `GET
https://intervals.icu/api/v1/athlete/0/events?oldest=2026-10-12&newest=2026-10-12&category=WORKOUT`
를 Basic 인증(`API_KEY:<key>`를 Base64)으로 1회 호출.

- **HTTP 200**, 응답 JSON 유효.
- 인증은 **성공**했다. (참고: `server.js`의 코드 주석에는 "현재 설정된 키가
  Intervals.icu로부터 401로 거부되고 있다"는 과거 기록이 남아 있으나, 이번 직접
  조회로 **현재는 그 키가 정상 인증됨**을 확인했다 — 과거 어느 시점의 상태였는지는
  이 조사 범위 밖이다.)

**중요한 한계**: 이 호출에 사용한 키는 Canonical `.env` 파일에서 읽은 값이다.
**실제로 지금 떠 있는 Relay(Node, PID 13588) 프로세스가 기동 당시 자신의 메모리에
로드한 `process.env.INTERVALS_ICU_API_KEY` 값이 `.env`의 현재 값과 동일하다고
가정하지 않았다** — 다른 프로세스의 메모리 내 환경변수를 안전하게 직접 조회하는
것은 민감정보 접근 위험이 있어 시도하지 않았다. 대신 STEP 4(Relay의 데이터
필터링 결과)에서 Relay 자신의 실제 응답이 이 직접 조회 결과와 **정확히 일치**함을
별도로 확인해, 간접적으로 두 값이 실질적으로 같은 결과를 내고 있음을 뒷받침했다
(완전한 증명은 아님 — 아래 7번 항목 참고).

## 3. 당일 Workout 존재 여부

2026-10-12(Asia/Seoul, 고정 UTC+9 오프셋으로 계산, 서버 로직과 동일) 기준:

- WORKOUT 카테고리 이벤트: **0건**.
- Run/Ride 타입 이벤트: **0건**.

실제 훈련 이벤트가 없음 — 인증 실패가 아니라 정상적인 데이터 부재다.

## 4. Relay의 데이터 필터링 결과

- `server.js` 소스 분석 결과, 로직은 지시서에 기술된 그대로임을 확인:
  `/today-workout` Bearer 토큰 인증(`crypto.timingSafeEqual`) → 레거시
  `C:\running-ai\state\today_plan.json`의 명시적 `REST` 결정이 있으면 최우선
  → 없으면 위와 동일한 Intervals.icu GET → `Run|Ride` 정규식 필터 → 통과하는
  이벤트가 없으면 `NO_PLAN`, 있으면 `labelForEvent()`로 Label 생성 후 `HAS_PLAN`.
  `cache_ttl_seconds=240`(`config.json`에서 직접 확인, 지시서 기재값과 일치),
  `rate_limit_per_minute=20`.
- `WATCHFACE_RELAY_MOCK`: `.env`와 `scripts/windows/*.ps1`,
  `scripts/windows/external/*.ps1` 전체를 검색했으나 어디에도 설정되어 있지
  않음 — 운영 기동 경로(`start-external-relay.ps1`)가 이 변수를 설정하지
  않는다는 소스 코드 주석의 주장과 일치. 또한 이번 실제 호출 결과가 Mock의
  고정 응답(`HAS_PLAN`, Label `"인터벌 6x800m"`)이 아니라 실제 Intervals.icu
  조회 결과와 정확히 일치하는 `NO_PLAN`이었다는 **행동적 증거**로도 Mock이
  비활성 상태임을 뒷받침한다.
- 실제 Relay 응답: `{"date":"2026-10-12","status":"NO_PLAN","label":null}` —
  STEP 2/3의 직접 조회 결과(0건)와 정확히 일치. Relay가 Intervals.icu로부터
  읽은 실제 데이터를 올바르게 필터링해 `NO_PLAN`을 반환했다고 판단한다.
- 레거시 `today_plan.json`에 의한 명시적 `REST` override는 **발생하지 않은 것으로
  보인다**(결과가 `REST`가 아니라 `NO_PLAN`이므로) — 다만 이 파일 자체를 열람하지
  않았으므로(레거시 저장소 접근 금지), "파일이 아예 없다"와 "파일은 있지만 오늘
  날짜가 아니거나 REST가 아니다"를 구분할 수는 없다. 결과에는 영향 없음.

## 5. Local HTTP 결과

| 요청 | 결과 |
|---|---|
| `GET /health` | `200` |
| `GET /today-workout`(무인증) | `401` |
| `GET /today-workout`(인증) | `200`, `{"date":"2026-10-12","status":"NO_PLAN","label":null}` |
| `GET /not-a-real-path` | `404` |

지시서에 기술된 모든 기대 결과와 정확히 일치.

## 6. Public HTTPS 결과

| 요청 | 결과 |
|---|---|
| `GET https://laptop-mmdqucic.tail4597a5.ts.net/health` | `200` |
| `GET /today-workout`(무인증) | `401` |
| `GET /today-workout`(인증) | `200`, `{"date":"2026-10-12","status":"NO_PLAN","label":null}` |

- TLS 연결 성공(HTTPS 응답 정상 수신).
- Local/Public 응답 바디가 **byte 단위로 동일**함을 확인(날짜/캐시 경계 문제
  없음 — 두 호출 모두 짧은 시간 간격 내, 같은 Seoul 날짜 경계 안에서 수행됨).
- `tailscale funnel status`로 확인한 프록시 대상은 `127.0.0.1:17845` 단 하나뿐
  — Spring(18080)이나 Connector(8765) 등 다른 내부 포트는 전혀 노출되지 않음.

## 7. Mock/캐시 영향

- Mock: 비활성(위 4번 항목 — 설정 파일 검색 + 실제 응답의 행동적 증거 둘 다
  Mock이 아님을 뒷받침). **완전한 증명은 아니다** — 운영 Node 프로세스의 실제
  메모리 내 환경변수를 직접 읽지 않았기 때문에, 이론적으로는 그 프로세스만
  기동 시점에 `WATCHFACE_RELAY_MOCK`이 설정된 상태였을 가능성을 코드/설정 파일
  검색만으로 100% 배제할 수는 없다. 다만 Mock이 설정되어 있었다면 응답이
  고정된 `HAS_PLAN`/`"인터벌 6x800m"`이어야 하는데 실제로는 `NO_PLAN`이었고,
  이는 직접 Intervals.icu 조회 결과(0건)와 정확히 일치하므로, 실질적으로 Mock이
  아니라고 판단한다.
- 캐시: `cache_ttl_seconds=240`. Local 호출과 Public 호출 사이의 시간 간격이
  240초 이내였으므로, 두 응답이 일치한 것은 "같은 Intervals.icu 조회 결과를
  캐시에서 재사용했기 때문"일 수도 있고 "둘 다 독립적으로 캐시 미스를 겪고도
  같은 실제 데이터를 받았기 때문"일 수도 있다 — 구분할 필요는 없다(어느
  쪽이든 데이터 자체의 정확성에는 영향 없음). 캐시를 강제로 초기화하거나
  재시작으로 결과를 맞추는 행위는 하지 않았다.

## 8. 데이터 전달 분류

**B. CONNECTIVITY_PASS_NO_PLAN**

- Intervals.icu 직접 조회 정상(HTTP 200, 유효한 JSON) — 충족.
- 해당 날짜(2026-10-12)의 대상(Run/Ride) 훈련 이벤트 없음 — 충족.
- Relay가 `NO_PLAN` 정상 반환 — 충족.
- Local/Public 응답 완전히 일치 — 충족.

`HTTP 200 + NO_PLAN`을 `FULL_DATA_PATH_PASS`(A)로 판정하지 않았다. 실제 Run/Ride
훈련 이벤트가 존재하는 날짜에 대한 재검증은 이번 Phase 범위 밖이다(해당 날짜가
오면 별도로 재확인 필요).

## 9. FR265 검증 상태

**FR265_PHYSICAL_CHECK=DEFERRED**

이번 세션에서는 Garmin Forerunner 265 실물 기기를 직접 확인하지 않았다. 워치페이스
소스·바이너리는 전혀 변경하지 않았다. 사용자가 가능할 때 기기에서 다음을 확인해
주시면 된다: 현재 화면이 `NOT SYNCED` / `NO PLAN` / `REST` / 실제 훈련 표시 중
무엇인지, 그리고 그것이 캐시된 오래된 화면이 아니라 최근 동기화 결과인지(필요
시 정상 동기화 주기를 통해 재확인). 기기 확인 없이는 Garmin E2E PASS를 선언하지
않는다. 이번 Relay 검증 결과(`NO_PLAN`)에 비추어 보면, 기기에도 `NO PLAN`이
표시되는 것이 기대되는 정상 상태다.

## 10. 운영 PID/DB/Watchdog 불변

검증 전후 비교, 전부 동일(변경 없음):

- Spring `29984`, Connector Launcher `25372`/Listener `28684`, Relay `13588` —
  CreationDate 전부 그대로.
- PostgreSQL: `8ff2a3adc495`, healthy, Volume 동일.
- DB: `activity` 6행, Garmin Sync HighWater/LastSync 불변.
- Watchdog: `Enabled` 유지, State v2의 `restarts`/`longTerm.failures` 전부 `0`,
  `lockedOutSince` 전부 공란 — 비정상 Recovery Action 없음. (정상적인 5분 주기
  `LastRunTime` 갱신 자체는 허용되는 부수효과로 실패 판정에 사용하지 않음.)
- `.env`/`watch-token.json` SHA256: Phase 6I-1.7D-3에서 기록한 재부팅 전 기준선
  (`.env`=`8C46D1C6...`, `watch-token.json`=`C73829B3...`)과 **완전히 동일** —
  이번 검증 과정에서 전혀 수정되지 않았다.
- Canonical Working Tree: Clean, HEAD 불변.

## 11. 레거시 경로 관련 기술 부채

`tools/external-relay/server.js` 37번째 줄에 다음 하드코딩된 절대 경로가
남아 있다(소스 코드를 읽기만 했을 뿐, 이 경로 자체는 전혀 열거나 수정하지
않았다):

```js
const TODAY_PLAN_FILE = 'C:\\running-ai\\state\\today_plan.json';
```

이 경로는 Legacy 저장소(`C:\running-ai`) 내부를 직접 가리키며, 기존 REST
우선순위 계약(명시적 REST 결정이 Intervals.icu 이벤트 부재보다 항상 우선)이
전적으로 이 파일의 존재와 내용에 의존하고 있음을 의미한다. 이번 Phase에서는
지시서에 따라 이 경로를 삭제·교체하지 않았으며, 향후 별도 개선 과제(Legacy
의존성 제거 또는 Canonical 내부로의 이관)로 분류한다.

## 12. 남은 미검증 항목

- 운영 Node Relay 프로세스의 실제 메모리 내 `INTERVALS_ICU_API_KEY`/
  `WATCHFACE_RELAY_MOCK` 값(`.env`와 동일하다고 가정하지 않음, 행동적 증거로만
  뒷받침됨).
- 레거시 `today_plan.json`의 실제 존재 여부와 내용(접근 금지, 간접 추론만 가능).
- 실제 Run/Ride 훈련 이벤트가 존재하는 날짜에서의 `FULL_DATA_PATH_PASS`(A) 경로
  재검증 — 오늘은 데이터가 없어 테스트 불가.
- FR265 실물 기기 확인(`DEFERRED`).
- 가정 Wi-Fi가 아닌 LTE/5G 등 별도 모바일 네트워크에서의 외부 접근 확인(사용자
  직접 확인 필요, 이번 세션에서 수행하지 않음).
- Phase 6I-1.7D-3의 실제 PC 재부팅 E2E 자체는 여전히 미실행(`REBOOT_PRECHECK=GO`
  상태 유지, 변경 없음).

## 13. 다음 Phase 권고

- 당장의 추가 코드 변경은 필요 없다 — 이번 조사 범위의 모든 구성요소(Intervals
  인증, Relay 필터링, Local/Public 일관성)가 정상 동작함을 확인했다.
- 사용자가 가능한 시점에 FR265 실물 기기를 확인해 `NO PLAN` 표시와 최근 동기화
  여부를 보고해 주면, 이번 Phase의 유일한 미완료 항목(STEP G)이 닫힌다.
- 실제 Run/Ride 훈련이 Intervals.icu에 등록된 날짜가 생기면, 동일한 절차로
  `FULL_DATA_PATH_PASS`(A) 경로를 재검증하는 짧은 후속 Phase를 권장한다.
- 레거시 `today_plan.json` 의존성 제거는 별도의, 더 큰 범위의 개선 과제로
  남겨둔다(이번 Phase에서 손대지 않음).
- `PHASE_6I_1_EXTERNAL_ACCESS_E2E_READY`는 이번 Phase만으로 선언하지 않는다 —
  FR265 실물 확인과 LTE/5G 외부망 확인이 완료된 뒤에 재검토한다.

## 지시사항 준수 확인

- PC 재부팅/로그아웃, Docker/서비스 재시작, Watchdog 변경: 수행하지 않음.
- Intervals API 키 회전, Watch Token 회전, 운영 `.env` 수정: 수행하지 않음
  (위 10번 항목 SHA256 불변으로 재확인).
- 실제 훈련 생성·수정·게시, Garmin Connect 업로드: 수행하지 않음.
- FR265 앱 재설치·워치페이스 수정: 수행하지 않음.
- 레거시 저장소(`C:\running-ai`, `C:\running-ai-watchface`) 접근·수정: 수행하지
  않음(소스 코드에 적힌 경로 문자열을 읽었을 뿐, 그 경로 자체를 열지 않았다).
- 데이터베이스 쓰기, 운영 코드 변경: 수행하지 않음.
- `origin/main` Push: 수행하지 않음.
- 토큰/API 키 원문: 이 문서를 포함해 어디에도 출력하지 않음.

## 완료 판정

`EXTERNAL_DATA_PATH_READONLY_CHECK=B_CONNECTIVITY_PASS_NO_PLAN`

기존 `REBOOT_PRECHECK=GO`(Phase 6I-1.7D-3)는 그대로 유지된다. 전체
`PHASE_6I_1_EXTERNAL_ACCESS_E2E_READY`는 선언하지 않는다(FR265 실물 확인, LTE/5G
외부망 확인, 재부팅 E2E가 모두 남아 있음).

작업 완료. 지시서에 따라 STOP한다.
