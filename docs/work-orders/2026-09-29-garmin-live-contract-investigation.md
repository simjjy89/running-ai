# 2026-09-29 — Phase 3B-1: Garmin 실제 Contract / Authentication / Connector Architecture 조사 (ADR)

| 항목 | 내용 |
|------|------|
| 날짜 | 2026-09-29 |
| 작업 | Garmin 접근 방식·인증·payload contract 조사, 3B-2 architecture 확정, mapper/fixture contract 보정 |
| 상태 | 완료 (production GarminClient 미구현, 의도) |
| 커밋 | `refactor: align Garmin ingestion with confirmed Garmin contract` |
| 지시서 원문 | [2026-09-29-garmin-live-contract-investigation-instruction.md](2026-09-29-garmin-live-contract-investigation-instruction.md) |
| 이전 작업 | [2026-09-29-claude-code-project-setup.md](2026-09-29-claude-code-project-setup.md), [2026-09-29-garmin-ingestion-core.md](2026-09-29-garmin-ingestion-core.md) |
| 문서 성격 | 향후 Garmin integration의 **Architecture Decision Record** |

---

## 1. 목적과 범위

Garmin network 연동을 구현하기 전에 (1) 2026년 현재 접근 방식, (2) 인증/session/token 책임, (3) 실제 activity payload contract,
(4) 3A synthetic fixture와의 차이, (5) Spring Boot ↔ connector architecture, (6) 3B-2 구현 범위를 근거 있게 확정한다.
production GarminClient, sidecar, scheduler, Intervals.icu, 새 API, DB migration은 만들지 않았다.

## 2. 작업 전 상태 / 조사 환경

- `main` = `f1e6722`, clean. 63 tests PASS. `CLAUDE.md`, `running-ai-dev`, `running-ai-integration` skill 적용.
- 이 PC: Windows 외부 PC. **실제 Python 없음**(Windows Store stub만), Docker 없음, `~/.garminconnect` / `~/.garth` 토큰 없음,
  `GARMIN_*` 환경변수 없음, 세션은 비대화형(interactive MFA 불가). PyPI / GitHub raw / developer.garmin.com / sso.garmin.com은 HTTPS 도달 가능(읽기 GET만 사용).
- 기존 RunningAI(메인 PC)의 Garmin 수집 코드: **NOT AVAILABLE IN CURRENT REPOSITORY** (추측하지 않음).

## 3. Research

### 3.1 Garmin Connect Developer Program (공식)

- API: Health API, **Activity API**(30+ activity type, FIT/GPX/TCX 파일, Ping/Pull 또는 Push), Women's Health API, Training API(workout/plan push), Courses API.
  (developer.garmin.com/gc-developer-program/overview, /activity-api, 2026-09-29 조회)
- 자격: Program FAQ — *"available for enterprise use"*, *"only for business use"*. 개인/개인용 개발자 경로는 언급 없음. 승인 후 evaluation environment,
  라이선스/유지비 없음(일부 metric은 상용 시 수수료·최소 주문 수량). 최종 사용자 OAuth consent 모델(cloud-to-cloud).
- 2026년 상태(비공식 출처, UNVERIFIED): 접근 신청 form이 수개월간 "under construction / Stay tuned", 신규 신청 일시 중단이라는 forum 보고.
- **평가**: 개인 프로젝트 RunningAI에는 현재 적용 불가. 서비스/사업화 시 **장기 공식 migration path**로만 유지 (8장).

### 3.2 garth (`matin/garth`)

- 최신 **0.8.0 (2026-03-28)**, PyPI classifier *Development Status :: 7 - Inactive*. README: *"Garth is deprecated and no longer maintained. Garmin changed their auth flow, breaking the mobile auth approach"* — 2026-03-17/18부터 `/mobile/api/login`이 429(issue #217), discussion #222 deprecation 공지, 최종 릴리스.
- 기존 저장 세션(OAuth1 ~1년)은 만료까지 동작 가능하나 신규 로그인 불가. python-garminconnect는 0.3.0(2026-04-02)부터 garth 의존 제거.
- **결정: 신규 architecture dependency로 채택하지 않는다.**

### 3.3 python-garminconnect (`cyberjunky/python-garminconnect`, PyPI `garminconnect`)

| 항목 | 확인 결과 (0.3.16 tag 소스 / PyPI / release notes) |
|------|------|
| latest stable | **0.3.16 (2026-09-18)**. 0.3.x가 2026-04 이후 활발히 릴리스 (0.3.0 04-02 … 0.3.16 09-18) |
| Python | **>= 3.12** (0.3.3부터) |
| license | MIT |
| 의존성 | `curl_cffi>=0.15.0`, `requests>=2.33.0`, `ua-generator>=1.0` (garth 없음) |
| 인증 flow | 자체 구현. 모바일 SSO(`sso.garmin.com/mobile/api/login`, Android app client id) → service ticket → `diauth.garmin.com` DI OAuth Bearer(access+refresh). 5단계 strategy chain(mobile / SSO widget / web portal × TLS impersonation 유무), `verify_login=True`면 API가 실제로 token을 받아들일 때만 성공 |
| MFA | `prompt_mfa` callback(대화형 입력) 또는 `return_on_mfa=True` + `resume_login(state, code)` 2단계 |
| token 저장 | `login(tokenstore)` 또는 `GARMINTOKENS` env → `~/.garminconnect/garmin_tokens.json`, 파일 0600 / 디렉터리 0700, symlink 경로 거부, 원자적 쓰기. `logout()`은 로컬 파일만 삭제(Garmin 측 revoke 아님) |
| token refresh | 요청 전 만료 임박 시 자동 refresh, refresh token 유효 시 무기한. refresh token 만료/폐기 시에만 credential(+MFA) 재로그인. 구체적 만료 기간 UNVERIFIED |
| recent activities | `get_activities(start=0, limit=20, activitytype=None, activitysubtype=None)` → `GET /activitylist-service/activities/search/activities?start&limit` (limit ≤ 1000) |
| 기간 조회 | `get_activities_by_date(startdate, enddate=None, activitytype=None, sortorder=None)` — 같은 endpoint, 20개씩 paging |
| single activity | `get_activity(activity_id)` → `/activity-service/activity/{id}` (summary + basic splits) |
| details | `get_activity_details(activity_id, maxchart=2000, maxpoly=4000)` → `/activity-service/activity/{id}/details` (`metricDescriptors` + positional `activityDetailMetrics`) |
| splits | `get_activity_splits(id)` → `/activity-service/activity/{id}/splits`, `typedsplits`, `split_summaries` |
| activity types | `get_activity_types()` → `/activity-service/activity/activityTypes` |
| retry 정책 | `connectapi` 데코레이터: **5xx/network만** 최대 `retry_attempts`(기본 3) 지수 backoff. **401 / 429 / 4xx는 즉시 실패, 재시도 없음** (429 → `GarminConnectTooManyRequestsError`) |
| 알려진 제한 | 비공식 API. SSO 429는 **계정(email) 단위**(issue #344, 0.3.2의 widget strategy로 완화), 48h+ 계정 차단 forum 보고. **open issue #444 (2026-09-28, 0.3.16)**: 유효 DI token으로 connectapi 호출 시 403 — 로그인은 curl_cffi, API 세션은 plain requests라 TLS fingerprint 불일치를 Cloudflare가 거부하는 사례. "Cloudflare bypass"는 라이브러리 내부 TLS impersonation으로 구현됨 |
| 보안 | **GHSA-wjhr-76vg-2hvc / CVE-2026-54447 (High, CWE-732)**: ≤ 0.3.4는 `garmin_tokens.json`을 umask대로(0644) 기록 → **0.3.5(2026-06-04)에서 수정**. 0.3.10/0.3.11 추가 hardening(원자적 쓰기, symlink 거부, JWT `alg:none` 거부, temp 파일 예측 불가). 공급망 사고·`GARMINTOKENS` 유출 advisory는 발견되지 않음(UNVERIFIED beyond GitHub/GitLab/OpenCVE) |

**Version pin 결정**: 3B-2 probe/connector는 `garminconnect==0.3.16` (조사 시점 stable, CVE 수정 포함, `master` 미릴리스 동작에 의존하지 않음). 0.3.5 미만 금지.

### 3.4 Live probe

```text
attempted:   NO  (LIVE_PROBE_NOT_POSSIBLE)
reason:      이 PC에 Python 3.12 runtime 없음, Garmin credential/token 없음, 비대화형 세션(MFA 입력 불가)
success:     -
read ops:    -
auth blocks: 발생 없음 (로그인 시도 자체를 하지 않음)
```

Garmin 계정에 어떠한 요청도 보내지 않았다. 따라서 모든 contract 항목의 최고 신뢰도는 **CONFIRMED_SOURCE**이며 CONFIRMED_LIVE는 없다.

## 4. Architecture 후보 비교

| 기준 | A. Native Java GarminClient | **B. Python connector (python-garminconnect)** | C. 기존 RunningAI 수집 방식 |
|------|------|------|------|
| auth 복잡도 | Garmin private SSO/DI OAuth/TLS impersonation을 Java로 재구현 — 지시서 §10 금지 범위와 충돌 | 라이브러리가 담당, public interface만 사용 | 코드가 repo에 없음 |
| 유지보수 | Garmin 변경마다 직접 대응(garth 사례: 한 달 만에 deprecated) | 활발한 upstream(2026년 17 릴리스), pin 후 검증 업그레이드 | NOT AVAILABLE IN CURRENT REPOSITORY |
| credential isolation | Spring JVM이 email/password/token 보유 | **Spring은 credential을 모름**, token은 connector 호스트의 `~/.garminconnect` | - |
| Spring 결합도 | 낮음(단일 프로세스) | 프로세스 경계 필요(5장) | - |
| Windows 개발 | JVM만 | Python 3.12 + curl_cffi wheel 필요(있음) | - |
| Raspberry Pi | JVM | Python 3.12 + curl_cffi aarch64 wheel(있음), container화 용이 | - |
| 테스트 | Java mock | connector는 fixture/contract test, Spring은 이미 network 없이 테스트 | - |
| Garmin 변화 대응 | 최악 | 업그레이드로 대응, 실패 시 connector만 교체 | - |

```text
Decision:  Option B — python-garminconnect 기반 Python connector (provisional: live 검증 전)
Reason:    Garmin 인증을 Java로 재구현하지 않는다는 원칙, 유지보수 중인 유일한 현행 client,
           Spring과 credential 분리, 현재 3A ingestion core를 그대로 재사용 가능
Rejected:  A (private auth 재구현·bypass 코드 작성 금지, garth 사례로 본 유지보수 부담),
           C (repo에 존재하지 않음 — 메인 PC 조사 후 재평가 가능), garth (deprecated),
           공식 Developer Program (business-only, 현재 신청 불가 보고)
Risks:     비공식 API·계정 단위 429/차단, 라이브러리의 TLS impersonation 의존, open issue #444(0.3.16 403),
           Python 3.12 runtime 추가, Garmin 정책 변경 시 전체 중단 가능
Fallback:  (1) 차단 시 즉시 중단·수동 재로그인, 자동 반복 없음 (2) connector 교체 가능한 경계 유지
           (3) 사업화 시 공식 Activity API로 connector만 교체 (4) 메인 PC 기존 수집 방식 조사 후 C 재검토
```

`final` 확정 조건: 3B-2에서 실제 계정으로 read-only probe 1회 성공 + payload contract CONFIRMED_LIVE.

## 5. 선정 architecture (3B-2 설계 방향)

```text
Spring Boot (server/)                         Python connector (tools/garmin-connector/, 별도 프로세스)
  business/domain, DB, ingestion,               Garmin authentication, token store (~/.garminconnect),
  analysis, workout, scheduler/orchestration    read transport, raw JSON 반환만
        │  localhost HTTP (권장, 아래)                     │  python-garminconnect==0.3.16
        └──────── raw JsonNode ───────────────►  GarminActivityIngestionService.ingest(payload, fetchedAt)
                                                     → activity_raw → GarminActivityMapper → activity
```

- **불변**: 3A 구조 유지. Garmin 라이브러리는 `ActivityRepository` / JPA / PostgreSQL을 절대 만지지 않는다. connector는 mapping도 하지 않는다(raw 그대로 전달).
- **통신 방식 비교**: Local HTTP(권장) — Spring/Python 분리, 재시작·테스트 용이, Pi에서 container/service화 쉬움, listening port(127.0.0.1 한정) 필요.
  Subprocess — 구성 단순·port 없음이나 process lifecycle, error/JSON framing, MFA 대화형 처리가 Spring 안으로 들어와 결합도 상승.
  **제안: 3B-2는 localhost HTTP(127.0.0.1 bind, 최소 endpoint: 인증 상태 조회, recent activities 읽기).** MFA/최초 로그인은 connector 쪽 CLI로 대화형 수행, Spring은 그 뒤 token 기반 read만.
- **Security boundary**: Garmin email/password/MFA/token은 connector 호스트 파일시스템(0600)에만 존재. Spring DB에 raw password/token 저장 금지. `.env`에 long-lived token 금지. Spring↔connector 사이는 localhost 한정, 필요 시 공유 secret header.
- **Interface 개념(코드로 만들지 않음)**: `GarminActivitySource.fetchRecentActivities(limit)` → `List<JsonNode>`. 이름/시그니처는 3B-2에서 확정.
- **실패 정책**: 401/403/429/Cloudflare challenge → 해당 run 중단, `LIVE_AUTH_BLOCKED` 기록, 자동 반복 로그인 금지(라이브러리도 4xx 무재시도). 5xx/network만 라이브러리 기본 backoff.

## 6. 실제 Garmin activity-list contract

한 항목 = `GET /activitylist-service/activities/search/activities` 응답 원소 (`get_activities()`). 응답은 bare list 또는 `{"activityList": [...]}`일 수 있음(`get_last_activity` 소스가 둘 다 처리; 어느 쪽인지 UNCONFIRMED → 3B-2 connector가 둘 다 unwrap).

| RunningAI | Garmin field | Garmin type | Unit | Required | Confidence | 근거 |
|---|---|---|---|---|---|---|
| externalId | `activityId` | integer | - | yes | CONFIRMED_SOURCE | typed.py `activity_id: int`; GarminDB/garminexport/tapiriik `int(activityId)`; HA 공개 sample |
| activityType | `activityType.typeKey` (obj: `typeId`, `typeKey`, `parentTypeId`, `isHidden`…) | object/string | - | yes | CONFIRMED_SOURCE | typed.py `ActivityType`; tapiriik `act["activityType"]["typeKey"]`; HA sample `{typeId:1, typeKey:running, parentTypeId:17}` |
| startedAt | `startTimeGMT` | string `"yyyy-MM-dd HH:mm:ss"`, UTC, **zone 표기 없음** | UTC | yes | CONFIRMED_SOURCE | tapiriik `strptime(act["startTimeGMT"], "%Y-%m-%d %H:%M:%S")` + `pytz.utc.localize`; garminexport `parse(startTimeGMT).replace(tzinfo=utc)`; HA sample `'2024-09-21 08:03:29'` (local 09:03:29, UK BST) |
| (참고) | `startTimeLocal` | string 동일 포맷, zone 없음 | local | - | CONFIRMED_SOURCE | typed.py, GarminDB `ignoretz=True`, HA sample |
| durationSeconds | `duration` | number (float) | **seconds** | yes | CONFIRMED_SOURCE | demo.py `duration/60 → min`; GarminDB `secs_to_dt_time`; tapiriik `ActivityStatisticUnit.Seconds`, `timedelta(0, float(duration))`; HA sample 2128.2 s for 5.09 km(내부 일관) |
| distanceMeters | `distance` | number (float) | **metres** | no | CONFIRMED_SOURCE | demo.py `distance/1000 → km`; GarminDB `Distance.from_meters`; tapiriik `Meters`; HA sample 5090.28 |
| averageHeartRate | `averageHR` | number (float in typed model) | bpm | no | CONFIRMED_SOURCE (값 int/float 여부는 UNCONFIRMED) | typed.py `average_hr: float`; tapiriik `BeatsPerMinute`; GarminDB `float` |
| maxHeartRate | `maxHR` | number | bpm | no | CONFIRMED_SOURCE (동일) | 동일 |

기타 확인된 필드(정규화하지 않음, raw JSONB에 보존): `activityName`, `description`, `elapsedDuration`, `movingDuration`(정지 활동 시 null), `averageSpeed`/`maxSpeed`(m/s), `elevationGain/Loss`(m), `calories`, `avgPower`/`maxPower`/`normPower`, `aerobic/anaerobicTrainingEffect`, `activityTrainingLoad`, `averageRunningCadenceInStepsPerMinute`, `hasPolyline`, `privacy{typeKey}`, `startLatitude/startLongitude`(GPS — fixture에 넣지 않음).
주의: `elapsedDuration` 단위는 출처가 엇갈림(tapiriik(구) ms `/1000`, GarminDB 초) → **사용하지 않음**, `duration`만 사용.

**Activity type keys** (GarminDB `garmin_connect_enums.py` typeId 포함, CONFIRMED_SOURCE):

| RunningAI | Garmin `typeKey` | typeId | 비고 |
|---|---|---|---|
| RUN | `running` | 1 (parent 17) | HA sample로 값 구조 확인 |
| RUN | `trail_running`, `track_running` | 6, 8 | 자식 type |
| TREADMILL_RUN | `treadmill_running` | 18 | 2026-09 실제 export에도 등장(edpft/fitness-tracker #172) |
| TREADMILL_RUN | `indoor_running` | 156 | |
| INDOOR_CYCLING | `indoor_cycling` | 25 | 실제 export 등장 |
| INDOOR_CYCLING | `virtual_ride` | 152 | |
| (미지원, 예외) | `lap_swimming`, `cycling`, `walking` … | | 3B-2 batch에서 SKIP 집계 예정 |

`treadmill`, `indoor_biking`, `cycling_indoor`, `run`은 Garmin key가 아닌 **alias**(tolerance) — mapper 주석에 구분 표기.

## 7. 3A synthetic fixture와의 차이 및 보정

```text
duration:      3A ms(3600000) → 실제 seconds(3600.0).  mapper: ms→s 변환 제거, Math.round(seconds)
time:          3A `startTime` ISO offset 우선 → 실제 필드는 startTimeGMT(zone 없음)+startTimeLocal.
               mapper: startTimeGMT 최우선, `startTime`(offset)과 startTimeLocal+timeZoneId는 비-Garmin fallback으로만 유지.
               startTimeLocal 단독은 instant를 알 수 없으므로 실패(신규 테스트)
activityType:  구조 동일(typeKey). typeId 값(18/25) 실제와 일치 확인, isHidden 추가
other:         laps는 list 응답에 없음 → 제거. description, movingDuration, elapsedDuration, averageSpeed,
               hasPolyline, privacy 등 실제 필드로 교체. timeZoneId(미확인) 제거. HR/거리/칼로리 float 표기
```

**Code changes (허용 범위 §31 내)**
- `GarminActivityMapper`: duration seconds, startTimeGMT 우선순위, contract 주석. `GarminActivityPayload` 변경 없음.
- fixtures 7종: 실제 contract 형태(synthetic 값, GPS/실제 ID 없음).
- `GarminActivityMapperTest`: 32 → **35** (GMT zone 없음=UTC, GMT 우선순위, startTimeLocal 단독 실패, 초 반올림 6 case, metres/bpm 반올림). `GarminActivityIngestionServiceTest`: raw 보존 assertion을 laps → calories/privacy/description으로 교체(11개 유지).
- probe tool: 만들지 않음(Python runtime 없음, contract는 소스로 확정 가능). 다른 production 코드·DB migration 변경 없음.

## 8. Summary vs Details 역할 분리

- **3B-2 첫 구현은 recent activities summary만** ingest한다. RunningAI Activity에 필요한 7개 필드가 모두 list 항목에 있으므로 활동당 추가 호출 불필요.
- 상세 분석용(향후 별도 Phase): `get_activity_splits`(lap), `get_activity_details`(positional metric 시계열), FIT 파일(Activity API/다운로드). 모든 activity마다 무조건 호출하지 않으며, 필요 시 raw 테이블을 별도로 둔다(현 `activity_raw`는 summary 원본).
- cadence / training effect / power / VO2max 등은 Activity column으로 추가하지 않는다 (JSONB 보존, Training Analysis 단계에서 선별).

## 9. 성공 조건 질문에 대한 답

1. **어떤 방식으로 Garmin을 읽는가** — 별도 Python connector 프로세스가 `python-garminconnect==0.3.16`으로 읽고(비공식 mobile SSO/DI OAuth), Spring은 raw JSON만 받는다. 공식 API는 business-only라 장기 path.
2. **인증/session 책임** — connector. 최초 로그인/MFA는 connector 호스트에서 대화형, 이후 `~/.garminconnect` token 자동 refresh.
3. **Spring이 credential을 알아야 하는가** — 아니오. Spring DB/`.env`에 password·token 저장 금지.
4. **실제 field/unit** — 6장 표. `duration` 초, `distance` m, `startTimeGMT` UTC zone 없음, `activityType.typeKey`, HR bpm.
5. **3A fixture 중 수정한 것** — 7장. duration 단위, 시간 필드, 부수 필드. (보정 완료, 66 tests PASS)
6. **3B-2 구현 코드** — 10장.

## 10. Phase 3B-2 recommendation (자동 시작하지 않음)

```text
tools/garmin-connector/            Python 3.12, garminconnect==0.3.16 pin, requirements.txt
  - CLI: login (대화형, MFA), status, activities --limit N  (read-only, write endpoint 없음)
  - localhost HTTP (127.0.0.1): GET /health, GET /activities?limit=N → 원본 list 그대로(activityList unwrap만)
  - 실패: 401/403/429 → 즉시 실패 응답, 자동 재로그인 없음; token/credential 로그 금지
server/ (Spring)
  - integration/garmin: GarminActivitySource(HTTP client, raw JsonNode 반환) + 최소 설정(connector base URL)
  - GarminSyncService: source.fetchRecentActivities(limit) → for each: ingestionService.ingest(payload, now)
      결과 집계(created/updated/skipped-unsupported/failed), 계정 차단 시 중단
  - 첫 live probe 1회로 6장 표를 CONFIRMED_LIVE로 갱신 (실제 raw는 저장·commit하지 않고 field inventory만)
  - incremental sync cursor(마지막 startTimeGMT 기준)와 scheduler는 그 다음 단계
테스트: Spring 쪽은 fixture/HTTP stub만(network 없음). connector는 credential-free unit test.
```

## 11. 알려진 제한사항

- Live 검증 없음: 모든 contract는 CONFIRMED_SOURCE. 실제 계정 probe 전까지 Decision은 provisional.
- python-garminconnect는 비공식·TLS impersonation 의존. Garmin 변경 시 언제든 중단 가능(garth 선례). open issue #444 주시.
- 계정 단위 429/차단 가능 → 3B-2에서 호출 빈도 최소화(하루 수회), 자동 반복 금지.
- 이 PC에는 Python 3.12가 없어 connector 개발/실행은 메인 PC 또는 Pi에서.
- Activity list 응답 wrapper 형태(bare list vs `activityList`) UNCONFIRMED.
- `averageHR`/`maxHR`가 int인지 float인지 UNCONFIRMED (mapper는 둘 다 처리, 반올림).
