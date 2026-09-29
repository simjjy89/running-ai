> 원본 작업지시서 (2026-09-29, Phase 3A.5). 구현 기록은 `2026-09-29-claude-code-project-setup.md` 참고.

# RunningAI Phase 3A.5 작업지시서
## Claude Code Project Rules / Skills / Hooks 구축

## 1. 작업 목적

RunningAI는 현재 다음 단계까지 구현되어 있다.

### Spring Boot Foundation
- Java 21
- Spring Boot 3.5.16
- Gradle 8.14.5
- PostgreSQL
- Flyway
- JPA / Hibernate
- Activity API
- ActivityRaw JSONB 저장

### Garmin Ingestion Core
- Garmin fixture 기반 ingestion
- raw-first persistence
- GarminActivityMapper
- Activity upsert
- ActivityRaw upsert
- idempotency
- reprocess
- transaction boundary 분리
- 실제 network 호출 없음

현재 latest commit:

```text
0a396e6 feat: add Garmin activity ingestion
```

현재 전체 테스트:

```text
63 tests
63 passed
0 failed
```

이번 작업에서는 RunningAI 기능을 추가하지 않는다.

목표는 앞으로 Claude Code가 RunningAI를 개발할 때 반복적으로 필요한 프로젝트 규칙과 작업 방법을 repository 자체에 정의하는 것이다.

구축 대상:

```text
CLAUDE.md

.claude/
├─ skills/
│  ├─ running-ai-dev/
│  │  └─ SKILL.md
│  ├─ running-ai-database/
│  │  └─ SKILL.md
│  └─ running-ai-integration/
│     └─ SKILL.md
│
└─ settings.json 또는 현재 Claude Code 공식 project hook 설정 파일
```

그리고 안전한 validation hook을 구성한다.

---

# 2. 가장 중요한 원칙

이번 작업은 다음 Phase 3B 기능 구현이 아니다.

절대 구현하지 않는다.

```text
GarminClient
Garmin login
Garmin authentication
Garmin network fetch
Garmin scheduler
Intervals.icu 연동
새 REST API
DB schema 변경
새 Flyway migration
```

이번 작업은 Claude Code 개발 환경 표준화만 수행한다.

---

# 3. 작업 시작 전

현재 repository 상태를 먼저 확인한다.

```bash
git status
git branch --show-current
git log --oneline -5
git remote -v
```

예상:

```text
branch: main
latest: 0a396e6
```

기존 변경사항이 있다면 덮어쓰지 않는다.

현재 repository를 조사하여:

- 실제 package 구조
- 테스트 실행 명령
- Gradle wrapper 위치
- docs/work-orders 구조
- 기존 README
- 현재 .gitignore
- Claude 관련 파일 존재 여부

를 먼저 확인한다.

---

# 4. Claude Code 최신 규격 확인

현재 설치된 Claude Code가 지원하는:

```text
CLAUDE.md
project skills
hooks
settings
```

의 실제 형식과 경로를 로컬 CLI 도움말 또는 설치된 공식 기능 기준으로 확인한다.

추측으로 deprecated 설정 파일이나 존재하지 않는 hook syntax를 만들지 않는다.

특히 hooks configuration은 현재 설치된 Claude Code가 실제로 읽는 project-level configuration을 사용한다.

---

# 5. Work Order 문서

이번 작업지시서 원문을 저장한다.

```text
docs/work-orders/
2026-09-29-claude-code-project-setup-instruction.md
```

완료 결과도 별도로 작성한다.

```text
docs/work-orders/
2026-09-29-claude-code-project-setup.md
```

결과 문서에는 최소 다음을 기록한다.

- 추가된 CLAUDE.md 내용 요약
- 추가된 Skills
- 각 Skill의 역할
- Hook 구성
- Hook 실행 시점
- Hook failure 정책
- 실제 검증 결과
- Claude Code version
- 향후 운영 방법

---

# 6. Root CLAUDE.md

Repository root에:

```text
CLAUDE.md
```

를 생성한다.

이 문서는 RunningAI에서 Claude가 항상 따라야 하는 **최상위 프로젝트 규칙**만 가진다.

너무 세부적인 DB/Garmin 규칙까지 전부 넣지 않는다.

세부 규칙은 Skills로 분리한다.

---

# 7. CLAUDE.md 기본 내용

다음 내용을 포함한다.

## Project

```text
RunningAI
```

목적:

```text
AI-powered personal running training platform
```

현재 architecture:

```text
Spring Boot
PostgreSQL
Garmin ingestion
Intervals.icu integration 예정
training analysis 예정
workout generation 예정
reporting 예정
```

---

# 8. 현재 기술 Stack

CLAUDE.md에 다음을 명시한다.

```text
Java 21
Spring Boot 3.5.x
Gradle Wrapper
PostgreSQL 17
Flyway
Spring Data JPA / Hibernate 6
Jackson
JUnit / Spring Test
```

현재 repository의 실제 dependency와 다른 부분이 있으면 실제 코드 기준으로 작성한다.

---

# 9. 작업 시작 규칙

모든 개발 작업 전에 Claude는 최소 다음을 확인한다.

```bash
git status
git branch --show-current
git log --oneline -5
```

그리고 관련 코드를 먼저 읽는다.

원칙:

```text
existing implementation first
guessing second
```

즉 기존 구현을 조사하지 않고 새 architecture를 만들지 않는다.

---

# 10. Work Order 규칙

모든 의미 있는 개발 작업은 다음 위치에 기록한다.

```text
docs/work-orders/
```

파일명:

```text
YYYY-MM-DD-descriptive-name.md
```

사용자가 상세 작업지시서를 제공한 경우 가능하면:

```text
*-instruction.md
```

로 원문도 보존한다.

완료 결과는 별도 문서로 남긴다.

---

# 11. 코드 변경 원칙

CLAUDE.md에 다음 원칙을 명시한다.

```text
- 기존 architecture를 우선 유지한다.
- 기능 요구 없이 대규모 refactoring을 하지 않는다.
- 미래를 예상한 abstraction을 만들지 않는다.
- 필요 없는 interface를 만들지 않는다.
- Lombok을 추가하지 않는다.
- Entity를 API DTO로 직접 노출하지 않는다.
- DTO에는 Java record 사용 가능.
- Controller에 business logic을 넣지 않는다.
- external system code와 domain/application logic을 분리한다.
- 새 dependency는 필요성이 명확할 때만 추가한다.
```

---

# 12. 기존 API Contract 보호

기존 endpoint 또는 public service contract는 명시적인 작업 요구가 없는 한 변경하지 않는다.

기존 API:

```text
GET  /api/v1/health
POST /api/v1/activities
GET  /api/v1/activities/{id}
GET  /api/v1/activities
GET  /actuator/health
```

기능 추가 과정에서 기존 HTTP semantics가 바뀌지 않도록 한다.

---

# 13. Database Migration 원칙

Root CLAUDE.md에는 핵심 원칙만 둔다.

```text
Flyway owns schema changes.
Hibernate validates mappings.
Applied migrations must never be edited.
Schema changes require a new migration.
```

상세 내용은 database skill로 이동한다.

---

# 14. Secrets 원칙

절대 repository에 포함하지 않는다.

```text
Garmin username/password
Garmin tokens
Garmin session data
Intervals.icu API key
real .env
private keys
real user GPS coordinates
raw private user payload fixture
```

로그에도 credential/token/raw payload 전체를 출력하지 않는다.

---

# 15. 테스트 원칙

코드 변경 후 최소:

```powershell
cd server
.\gradlew clean test
```

를 실행한다.

테스트 실패 상태에서 완료라고 보고하지 않는다.

기존 regression을 삭제하거나 disable해서 통과시키지 않는다.

테스트를 고쳐야 한다면 production behavior 변경과 함께 이유를 설명한다.

---

# 16. Git 원칙

작업 후:

```bash
git status
git diff
```

를 확인한다.

commit 전:

```text
credential
token
.env
private activity data
build artifact
IDE file
```

포함 여부를 확인한다.

사용자가 commit / push까지 요청한 작업이면 테스트 성공 후 수행한다.

---

# 17. Completion Report 규칙

작업 완료 시 최소 다음을 보고한다.

```text
구현 내용
architecture 결정
테스트 결과
DB migration 여부
Git branch / commit / push
제한사항
다음 단계
```

구현하지 않은 것을 구현했다고 표현하지 않는다.

---

# 18. Skill 1 — running-ai-dev

경로:

```text
.claude/skills/running-ai-dev/SKILL.md
```

역할:

```text
RunningAI 일반 개발 작업의 기본 workflow
```

이 Skill에는 root CLAUDE.md보다 구체적인 개발 절차를 넣는다.

---

# 19. running-ai-dev 내용

다음 workflow를 정의한다.

```text
1. Repository 상태 확인
2. 관련 코드 조사
3. 변경 영향 범위 파악
4. 최소 구현
5. 관련 테스트 작성
6. 전체 regression 실행
7. git diff 검토
8. 문서 업데이트
9. secrets 검사
10. commit / push
```

다음 원칙도 포함한다.

```text
Smallest safe change
Existing patterns first
No speculative refactoring
No fake implementation
No disabled failing tests
```

---

# 20. running-ai-dev 테스트 명령

Windows 기본:

```powershell
cd server
.\gradlew.bat clean test
```

또는 repository에서 실제 동작하는 wrapper 명령을 확인하여 정확하게 기록한다.

JDK:

```text
Java 21 required
```

현재 `JAVA_HOME`이 Java 8일 수 있다는 기존 환경 이력도 고려하되 하드코딩된 특정 사용자 경로는 Skill에 넣지 않는다.

Java 21이 아닌 경우 명확하게 실패 원인을 보고하도록 한다.

---

# 21. Skill 2 — running-ai-database

경로:

```text
.claude/skills/running-ai-database/SKILL.md
```

역할:

```text
PostgreSQL / Flyway / JPA schema 관련 작업 규칙
```

---

# 22. running-ai-database 핵심 규칙

다음을 포함한다.

```text
PostgreSQL is production/local database.
Flyway owns schema.
Hibernate ddl-auto = validate.
Never modify applied migration V1/V2/V3.
Create a new V<N> migration for schema changes.
```

현재 migration:

```text
V1 athlete
V2 activity
V3 activity_raw
```

실제 파일명을 확인해서 정확하게 기록한다.

---

# 23. DB Type 정책

현재 정책을 기록한다.

```text
UTC Instant
PostgreSQL TIMESTAMPTZ
JSONB for raw external payload
DB PK != external service ID
```

외부 ID:

```text
external_source + external_id
```

로 구분한다.

---

# 24. Activity / ActivityRaw 정책

database skill에 명시한다.

```text
activity
= RunningAI normalized data

activity_raw
= external source raw payload
```

ActivityRaw:

```text
payload = JSONB
activity_id nullable
raw-first ingestion 지원
```

---

# 25. Raw Payload 정책

현재 정책:

```text
1 external activity = 1 activity_raw row
```

재수집:

```text
same externalSource + externalId
→ payload update
→ fetchedAt update
→ existing row ID preserved
```

raw version history는 현재 사용하지 않는다.

---

# 26. Database Test 원칙

H2 테스트가 존재하지만 PostgreSQL 차이를 의식한다.

현재 차이:

```text
PostgreSQL JSONB
H2 JSON
```

DB-specific 기능 추가 시 가능한 경우 PostgreSQL에서도 검증한다.

Docker가 없는 환경 때문에 Testcontainers를 억지로 요구하지 않는다.

---

# 27. Skill 3 — running-ai-integration

경로:

```text
.claude/skills/running-ai-integration/SKILL.md
```

역할:

```text
Garmin / Intervals.icu 등 외부 integration 개발 규칙
```

---

# 28. integration 기본 원칙

다음을 포함한다.

```text
Network layer and ingestion/domain layer are separate.

External payload is preserved before normalization when appropriate.

Do not make domain logic depend directly on HTTP clients.

External source IDs never become internal DB PKs.

Network failures must not corrupt normalized data.

Credentials never appear in logs.
```

---

# 29. Garmin 현재 상태 기록

현재 Garmin 구현 상태를 명확히 기록한다.

현재 구현됨:

```text
synthetic fixture ingestion
GarminActivityMapper
GarminActivityIngestionService
raw-first persistence
Activity upsert
ActivityRaw upsert
reprocessing
idempotency
```

현재 미구현:

```text
Garmin authentication
Garmin session
Garmin network client
real Garmin payload fetch
scheduler
incremental sync cursor
```

Claude가 다음 작업에서 fixture ingestion을 실제 Garmin 연동으로 착각하지 않게 한다.

---

# 30. Garmin Raw-first 정책

다음 흐름을 명시한다.

```text
external Garmin payload

↓ raw transaction

activity_raw commit

↓ mapping

normalized data

↓ activity transaction

activity upsert

↓ raw link transaction

activity_raw.activity_id
```

mapping failure 시 가능한 경우 raw를 보존한다.

---

# 31. 예외 — External ID 없음

raw 저장 key 자체가 없는 경우:

```text
activityId missing
```

현재 정책대로 raw persistence가 불가능할 수 있음을 기록한다.

이를 임의로 random ID 등으로 보정하지 않는다.

---

# 32. Garmin Idempotency

동일:

```text
GARMIN + externalId
```

재수집 시:

```text
Activity       1 row
ActivityRaw    1 row
```

PK는 유지한다.

normalized field와 raw payload는 최신 값으로 갱신 가능하다.

---

# 33. Reprocessing 정책

기존 JSONB raw payload에서:

```text
ActivityRaw
↓
mapper
↓
Activity upsert
```

가 가능해야 한다.

mapper 변경 때문에 historical Garmin 데이터를 무조건 다시 다운로드하는 구조를 만들지 않는다.

---

# 34. Synthetic Contract 주의

현재 Garmin fixture contract는 실제 Garmin API contract로 확정된 것이 아니다.

특히:

```text
duration unit
activity type field
time fields
payload shape
```

은 Phase 3B에서 실제 payload를 확인하여 보정해야 한다.

현재 synthetic fixture를 Garmin 공식 contract처럼 취급하지 않는다.

---

# 35. Intervals.icu 원칙

아직 Spring server 구현이 없거나 미완성이라면 그렇게 명확히 기록한다.

향후에도:

```text
IntervalsClient
↓
application service
```

형태로 network layer를 분리한다.

PowerShell 기존 구현을 조사하기 전에 Java 구현을 추측해서 만들지 않는다.

---

# 36. Skills Trigger 설명

각 SKILL.md 상단에는 Claude가 언제 해당 skill을 사용할지 명확하게 기술한다.

예:

### running-ai-dev

```text
Use for any non-trivial RunningAI code change, refactor, test, or feature implementation.
```

### running-ai-database

```text
Use whenever changing JPA entities, repositories, Flyway migrations, PostgreSQL schema, JSONB, indexes, constraints, or persistence semantics.
```

### running-ai-integration

```text
Use whenever working with Garmin, Intervals.icu, external APIs, ingestion, synchronization, retries, credentials, or external payload mapping.
```

현재 Claude Code Skill format에서 description/frontmatter 등이 요구되면 공식 형식에 맞춰 적용한다.

---

# 37. Hook 목표

Hook의 목적은 Claude가 작업을 마쳤는데 regression test를 실행하지 않는 상황을 줄이는 것이다.

최소 하나의 validation hook을 만든다.

하지만 다음은 자동화하지 않는다.

```text
git commit
git push
database destructive operation
Flyway migrate production DB
credential operation
```

---

# 38. Hook 실행 내용

가능하면 코드 변경이 완료되는 시점 또는 Claude가 작업을 종료하기 전에:

```powershell
server\gradlew.bat test
```

를 실행한다.

단 현재 Claude Code hook lifecycle에서 적합한 이벤트를 실제 지원 문서/CLI를 확인해서 선택한다.

존재하지 않는 이벤트 이름을 추측하지 않는다.

---

# 39. Hook 비용 고려

매 file edit마다 전체 63개 테스트를 실행하지 않는다.

그렇게 하면 개발 속도가 지나치게 느려진다.

권장:

```text
작업 종료 / validation 단계
→ full test
```

필요하다면 빠른 hook과 최종 full test를 구분할 수 있으나 이번에는 과도하게 복잡하게 만들지 않는다.

---

# 40. Hook Failure

테스트 실패 시:

```text
exit code != 0
```

을 Claude가 명확하게 인지할 수 있어야 한다.

테스트 실패를 무시하고 success 처리하는 wrapper를 만들지 않는다.

---

# 41. Cross-platform 고려

현재 주 개발환경은 Windows이다.

따라서 PowerShell / `.bat` wrapper를 우선 지원한다.

하지만 가능하면 hook command를 repository-relative하게 구성한다.

특정 사용자 경로:

```text
C:\Users\...
```

등을 하드코딩하지 않는다.

---

# 42. Optional Validation Script

Hook에서 복잡한 command가 필요하면 repository에 작은 validation script를 둘 수 있다.

예:

```text
scripts/dev/validate-server.ps1
```

다만 기존 scripts 구조를 먼저 확인한다.

단순 command 하나면 별도 script를 만들지 않는다.

---

# 43. README 업데이트

README에는 개발자가 알아야 하는 정도만 간단히 추가한다.

예:

```text
## Claude Code

Project instructions are defined in CLAUDE.md.

Reusable project-specific skills:
- running-ai-dev
- running-ai-database
- running-ai-integration

Claude Code hooks run project validation automatically.
```

README를 Claude 운영 매뉴얼로 과도하게 키우지 않는다.

세부사항은 CLAUDE.md / Skill / Work Order에 둔다.

---

# 44. Skill 중복 최소화

중요:

같은 내용을 세 파일에 반복 복사하지 않는다.

역할을 구분한다.

```text
CLAUDE.md
→ 프로젝트 전체 invariant

running-ai-dev
→ 작업 workflow

running-ai-database
→ DB-specific rules

running-ai-integration
→ external integration-specific rules
```

---

# 45. CLAUDE.md 크기

CLAUDE.md를 지나치게 길게 만들지 않는다.

Claude가 매 요청마다 읽는 핵심 context이므로:

```text
현재 architecture
불변 규칙
작업 workflow
security
testing
```

위주로 간결하게 작성한다.

세부적인 예제와 긴 설명은 Skills에 둔다.

---

# 46. 현재 구현을 Source of Truth로 사용

작업지시서의 내용과 실제 repository가 충돌하면 실제 repository를 조사한다.

예:

```text
package 이름
Gradle command
migration filename
Activity field
test count
```

등은 실제 코드가 source of truth다.

단 architecture 변경은 임의로 하지 않는다.

---

# 47. 테스트

이번 작업은 production Java 기능을 변경하지 않는 것이 원칙이지만 전체 regression test를 실행한다.

```powershell
cd server
.\gradlew.bat clean test
```

예상 baseline:

```text
63 passed
0 failed
```

테스트 개수가 자연스럽게 바뀔 이유가 없다면 63개가 유지되어야 한다.

---

# 48. Hook 자체 검증

Hook 또는 validation command를 실제로 실행해 정상 동작을 확인한다.

최소 확인:

```text
command executable
correct working directory
exit 0 on test success
```

가능하면 안전한 방법으로 실패 exit code 전달 여부도 검증한다.

실제 production test를 일부러 망가뜨려 commit하지 않는다.

---

# 49. Security 검사

다음을 검색한다.

```text
password
token
secret
GARMIN_USERNAME
GARMIN_PASSWORD
INTERVALS_API_KEY
```

placeholder / documentation은 허용하지만 실제 credential이 없는지 검토한다.

---

# 50. Git diff 검토

완료 후:

```bash
git status
git diff
```

확인한다.

이번 작업에서 예상되는 변경:

```text
CLAUDE.md
.claude/...
README.md
docs/work-orders/...
(optional validation script)
```

Spring domain / ingestion production code 변경이 보이면 왜 필요한지 재검토한다.

---

# 51. Definition of Done

다음이 모두 만족되어야 한다.

1. Root `CLAUDE.md` 생성
2. RunningAI architecture 명시
3. 개발 기본 원칙 명시
4. Work Order 규칙 명시
5. test-before-completion 규칙 명시
6. secrets 규칙 명시
7. Flyway invariant 명시
8. `running-ai-dev` skill 생성
9. `running-ai-database` skill 생성
10. `running-ai-integration` skill 생성
11. 각 skill trigger/description 명확
12. Skill간 역할 중복 최소화
13. Garmin Phase 3A 현재 상태 반영
14. synthetic Garmin contract 주의사항 반영
15. raw-first 정책 반영
16. reprocess/idempotency 정책 반영
17. project-level validation hook 생성
18. hook에서 자동 commit 하지 않음
19. hook에서 자동 push 하지 않음
20. hook 실행 확인
21. README 간단 업데이트
22. instruction work order 저장
23. result work order 저장
24. 전체 Gradle test PASS
25. credential/token 미포함 확인
26. git diff 검토
27. commit
28. origin/main push

---

# 52. Commit

권장 commit message:

```text
chore: standardize Claude Code project workflow
```

테스트 성공 후:

```bash
git add .
git commit -m "chore: standardize Claude Code project workflow"
git push origin main
```

---

# 53. 최종 보고 형식

## Claude Code configuration

```text
CLAUDE.md:
Claude Code version:
project settings:
hook event:
hook command:
```

## Skills

```text
running-ai-dev:
running-ai-database:
running-ai-integration:
```

각 skill 역할을 1~2줄로 설명한다.

## Hook

```text
trigger:
command:
success behavior:
failure behavior:
```

## Regression

```text
gradlew clean test

total:
passed:
failed:
```

## Files

추가/변경된 파일을 정리한다.

## Git

```text
branch:
commit:
push:
```

## 제한사항

현재 Claude Code 버전이나 hook 기능 때문에 의도한 구조와 달라진 부분이 있다면 정확히 설명한다.

## Phase 3B readiness

다음 작업에서 Claude가 어떤 project instructions / skills를 자동으로 활용할 수 있는지 설명한다.

다음 Phase 3B 구현은 자동으로 시작하지 않는다.

---

# 54. 중요

이번 작업에서 가장 중요한 것은 파일을 많이 만드는 것이 아니다.

목표는:

```text
RunningAI의 개발 원칙과 architecture context를
Claude가 repository에서 스스로 읽고

매번 긴 작업지시서 없이도
일관된 방식으로

조사 → 구현 → 테스트 → 검증 → 문서화
```

할 수 있는 개발환경을 만드는 것이다.

기능 구현보다 간결성, 실제 작동 여부, 유지보수성을 우선한다.
