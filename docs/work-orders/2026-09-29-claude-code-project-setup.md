# 2026-09-29 — Phase 3A.5: Claude Code Project Rules / Skills / Hooks

| 항목 | 내용 |
|------|------|
| 날짜 | 2026-09-29 |
| 작업 | Claude Code 개발환경 표준화: root `CLAUDE.md`, project skill 3개, Stop validation hook |
| 상태 | 완료 (기능 코드 변경 없음) |
| 커밋 | `chore: standardize Claude Code project workflow` |
| 지시서 원문 | [2026-09-29-claude-code-project-setup-instruction.md](2026-09-29-claude-code-project-setup-instruction.md) |
| 이전 작업 | [2026-09-29-garmin-ingestion-core.md](2026-09-29-garmin-ingestion-core.md) |
| Claude Code | **2.1.284** (Windows, Git Bash 사용 가능) |

---

## 1. 작업 목적

RunningAI의 개발 원칙과 architecture context를 repository 자체에 두어, Claude Code가 긴 작업지시서 없이도
조사 → 최소 구현 → 테스트 → 검증 → 문서화를 일관되게 수행하도록 한다. Phase 3B 기능(GarminClient, 인증,
network, scheduler, Intervals.icu, 새 API, migration)은 구현하지 않았다.

## 2. 작업 전 상태 / 규격 확인

- `main` = `0a396e6`, 작업 트리 clean. `CLAUDE.md`, `.claude/`, `scripts/` 없음.
- 설치된 CLI `claude --version` → `2.1.284`. 공식 문서(code.claude.com: hooks-guide, skills, memory)로 다음을 확인했다.
  - Project hooks: `.claude/settings.json`(공유) / `.claude/settings.local.json`(개인). 구조 `hooks.<Event>[].hooks[] = {type:"command", command, timeout(초)}`.
    `Stop` 이벤트는 matcher 없음, stdin JSON에 `stop_hook_active` 포함, **exit 2 = stop 차단 + stderr를 Claude에게 전달**, 그 외 non-zero는 non-blocking.
    Windows에서 shell-form command는 Git Bash(있으면) 아니면 PowerShell로 실행. `${CLAUDE_PROJECT_DIR}` 사용 가능.
  - Skills: `.claude/skills/<name>/SKILL.md`, YAML frontmatter `name`, `description`(자동 호출 판단에 사용) 등.
  - `CLAUDE.md`: repository root, 200줄 이하 권장. `@path` import 지원(이번에는 사용하지 않음).

## 3. 추가된 파일

```text
CLAUDE.md                                        (60줄) 최상위 프로젝트 규칙
.claude/settings.json                             Stop hook 정의 (공유)
.claude/skills/running-ai-dev/SKILL.md            개발 workflow
.claude/skills/running-ai-database/SKILL.md       DB / Flyway / JPA 규칙
.claude/skills/running-ai-integration/SKILL.md    Garmin / 외부 연동 규칙 + 현재 상태
scripts/dev/validate-server.ps1                   hook이 실행하는 validation script
.gitignore                                        + .claude/settings.local.json, .claude/.validate-*
README.md                                         + "Claude Code" 절, repository 구조 갱신
docs/work-orders/2026-09-29-claude-code-project-setup-instruction.md
docs/work-orders/2026-09-29-claude-code-project-setup.md   (이 문서)
```

`server/` 아래는 변경하지 않았다.

## 4. CLAUDE.md 요약

- 프로젝트 정의: AI 기반 개인 러닝 훈련 플랫폼. 기존 PowerShell / Node.js 구현은 메인 PC에만 있고 repo에 없음.
- 현재 architecture: Spring Boot 3.5.x / Java 21 / Gradle wrapper / PostgreSQL 17 / Flyway / JPA·Hibernate 6 / Jackson / JUnit 5 + Spring Test(H2).
  패키지 `com.runningai.{common, athlete, activity, integration.garmin}`. 구현됨 vs 계획(미구현) 명시. Garmin network/auth 코드 없음 명시.
- 작업 전 확인: `git status / branch / log -5` + 관련 코드 읽기. "existing implementation first, guessing second". legacy 기능 추측 재구현 금지.
- 불변 규칙: architecture 유지, speculative refactoring / 미래 abstraction / 불필요 interface / Lombok 금지, Entity를 DTO로 노출 금지, Controller에 business logic 금지,
  기존 API contract 5개 보호, Flyway invariant, UTC Instant / TIMESTAMPTZ, 내부 PK ≠ 외부 ID.
- Secrets: Garmin credential/token/session, Intervals.icu key, `.env`, private key, 실제 GPS, 실제 raw payload 금지. 로그에도 금지.
- 테스트/완료: `server`에서 `.\gradlew.bat clean test`(Java 21), 실패 상태 완료 보고 금지, 회귀 테스트 삭제/비활성화 금지, commit 전 diff 검사,
  work-order 두 파일 규칙, 완료 보고 항목. 세부 절차는 skill로 위임.

## 5. Skills

| skill | 역할 | 트리거(description) |
|-------|------|---------------------|
| `running-ai-dev` | 10단계 workflow(상태 확인 → 조사 → 영향 범위 → 최소 구현 → 테스트 → 전체 회귀 → diff → 문서 → secrets → commit/push), 테스트 명령·JDK 21 진단, 완료 보고 형식 | server/ 아래 Java / Gradle / YAML / 테스트를 바꾸는 모든 non-trivial 변경 |
| `running-ai-database` | Flyway 소유·`validate`·V1~V3 불변·새 V\<N\> 규칙, H2/PostgreSQL 호환 SQL 타입, `${json_type}` placeholder, 시간/ID/JSONB 정책, `activity` vs `activity_raw` 표, DB 테스트 규칙(H2 차이, PostgreSQL 검증 방법) | migration, `@Entity`, repository, datasource 설정, persistence 동작 변경 |
| `running-ai-integration` | network layer ≠ ingestion layer, raw-first, 외부 ID, credential/log 규칙, contract 추측 금지; Garmin Phase 3A **구현됨 / 미구현 목록**, pipeline 모양과 transaction 경계, idempotency·reprocess·unsupported type 정책, synthetic fixture contract 경고, Intervals.icu 현재 상태(없음) | `integration.*`, mapper/ingestion, fixtures, HTTP client / credential / scheduler 도입 |

중복 최소화: CLAUDE.md는 invariant만, dev는 절차만, database/integration은 각 영역 규칙만 두고 서로 참조한다.

## 6. Hook

| 항목 | 내용 |
|------|------|
| 파일 | `.claude/settings.json` |
| 이벤트 | `Stop` (Claude가 응답을 마치려는 시점, matcher 없음) |
| command | `powershell -NoProfile -ExecutionPolicy Bypass -File "${CLAUDE_PROJECT_DIR}/scripts/dev/validate-server.ps1"` |
| timeout | 900초 |
| 하는 일 | `server/`에 commit되지 않은 변경(추적/미추적 포함)이 있을 때만 `server\gradlew.bat test` 실행 |
| 비용 제어 | (1) `server/` 변경 없음 → 즉시 exit 0. (2) 변경 내용 fingerprint(`git status/diff/untracked blob hash`의 SHA-256)가 마지막 성공과 같으면 skip. cache: `.claude/.validate-cache`(git-ignore). (3) `stop_hook_active=true`(이미 이 hook 때문에 한 번 계속한 상태)면 exit 0 → 무한 루프 방지 |
| JDK | `JAVA_HOME` → PATH의 `java`가 가리키는 `java.home` 순서로 JDK 21 탐색. 사용자 경로 하드코딩 없음. 못 찾으면 exit 2 + 원인 메시지 |
| 성공 | exit 0, fingerprint 저장, 요약 1줄 |
| 실패 | **exit 2** + stderr에 실패 테스트 / `What went wrong` 요약 + 전체 로그 경로(`.claude/.validate-server.log`, git-ignore). Claude는 stop이 차단되고 stderr를 받아 실패를 처리해야 한다 |
| 하지 않는 것 | commit, push, DB 파괴 작업, Flyway migrate, credential 조작 |
| 수동 실행 | `powershell -File scripts/dev/validate-server.ps1 -Force [-Clean]` |

### 실제 검증 결과 (hook command를 Git Bash에서 `CLAUDE_PROJECT_DIR` 설정 후 그대로 실행)

```text
settings.json JSON 파싱                                   OK (command / timeout 읽힘)
1) server/ 변경 없음, stop_hook_active=false             exit 0 "nothing to validate"
2) stop_hook_active=true                                 exit 0 "skipping"
3) -Force -Clean (전체 회귀)                              exit 0, JDK 21 자동 선택, 63 PASSED, BUILD SUCCESSFUL, fingerprint 저장
4) 임시 실패 테스트 추가 후 hook 실행                      exit 2, stderr:
     TemporaryHookProbeTest > deliberatelyFails() FAILED
     64 tests completed, 1 failed / Execution failed for task ':test'
   cache는 이전 성공 fingerprint 유지 → 임시 테스트 삭제 (commit하지 않음)
5) -Force (test 단일 task)                               exit 0, 63 PASSED
6) 다시 hook 실행 (clean tree)                            exit 0 skip
```

검증 과정에서 고친 PowerShell 5.1 함정: native 명령의 stderr가 `$ErrorActionPreference='Stop'`에서 terminating error가 됨(→ `cmd /c` 캡처),
`$home`은 읽기 전용 자동 변수, `Start-Process`가 `.bat` 인자 공백을 잃음(→ `cmd /c .\gradlew.bat` 토큰 전달), 한 원소 배열이 문자열로 풀림(→ `[string[]]`),
로그를 `server/build` 아래 두면 `clean`이 실패(→ `.claude/`로 이동), cmd가 `gradlew.bat`를 현재 디렉터리에서 찾지 않음(→ `.\` 접두사).

## 7. 회귀 테스트

```text
cd server
.\gradlew.bat clean test   (hook script -Force -Clean 경유 및 직접 실행 동일)
→ BUILD SUCCESSFUL, 63 tests, 63 passed, 0 failed  (baseline 유지, 테스트 개수 변화 없음)
```

## 8. 알려진 제한사항

- Hook 설정은 **세션 시작 시 로드**된다. 이번 세션에서는 hook command를 동일 조건으로 직접 실행해 검증했고, 실제 Stop 이벤트 연동은 다음 세션부터 적용된다.
- `Stop` hook은 응답이 끝날 때마다 실행되지만 `server/` 변경이 없거나 fingerprint가 같으면 수 초 내 종료한다. 변경이 있을 때 첫 검증은 약 40~60초.
- JDK 21 탐색은 `JAVA_HOME`과 PATH의 java만 본다. 둘 다 21이 아니면 hook은 exit 2로 원인을 알려주며, 개인 PC 설정은 `.claude/settings.local.json`의 `env`(git-ignore)로 두는 것을 권장한다.
- Windows/PowerShell 5.1 기준 script다. macOS/Linux에서 hook을 쓰려면 같은 로직의 `.sh`가 필요하다(현재 주 개발환경이 Windows라 만들지 않음).
- Skill 자동 호출은 description 매칭에 의존한다. 필요하면 `/running-ai-dev` 등으로 직접 호출할 수 있다.
- `CLAUDE.local.md`, `@import`는 사용하지 않았다.

## 9. 향후 운영 방법

- 규칙 변경: invariant는 `CLAUDE.md`, 절차/영역 규칙은 해당 SKILL.md에만 반영(중복 금지). 200줄 이내 유지.
- Garmin/Intervals 상태가 바뀌면 `running-ai-integration`의 "current state" 절을 같은 commit에서 갱신한다.
- 새 migration 추가 시 `running-ai-database`의 불변 migration 목록에 파일명을 추가한다.
- hook을 잠시 끄려면 `.claude/settings.local.json`에서 override하거나 `/hooks`로 확인·비활성화한다. script 수동 실행: `-Force [-Clean]`.
- 개인 환경 설정(JDK 경로 등)은 `.claude/settings.local.json` `env`에 두고 commit하지 않는다.

## 10. Phase 3B readiness

다음 세션에서 Claude는 `CLAUDE.md`를 자동으로 읽고, Garmin 작업이면 `running-ai-integration`(현재 구현/미구현 목록, pipeline 모양, synthetic contract 경고),
코드 변경이면 `running-ai-dev`(절차, 테스트 명령, 보고 형식), schema 변경이면 `running-ai-database`를 description 매칭으로 활용한다.
작업 종료 시 Stop hook이 `server/` 변경에 대해 테스트를 강제한다. Phase 3B(GarminClient / 인증 / fetch / cursor / scheduler)는 시작하지 않았다.
