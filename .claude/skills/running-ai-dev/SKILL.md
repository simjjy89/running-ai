---
name: running-ai-dev
description: Standard RunningAI development workflow for any non-trivial code change, refactor, test, bug fix or feature in the Spring Boot server (investigate → smallest change → tests → full regression → diff/secrets review → work-order docs → commit/push on request). Use it whenever you are about to edit Java, Gradle, YAML or test files under server/.
---

# RunningAI development workflow

Project-wide invariants are in the root `CLAUDE.md`; this skill is the step-by-step
procedure. Database/schema specifics: `running-ai-database`. Garmin / external systems:
`running-ai-integration`.

## Workflow

1. **Repository state**: `git status`, `git branch --show-current`, `git log --oneline -5`.
   Do not overwrite unexpected local changes. Note the latest commit for the report.
2. **Investigate first**: read the classes you will touch and their tests. Package layout
   is `com.runningai.{common,athlete,activity,integration.garmin}`; tests mirror it under
   `server/src/test/java`. Reuse existing services and patterns (records for DTOs,
   constructor injection, `@Transactional` on services, `ErrorResponse` codes).
3. **Scope the impact**: list callers, tests, migrations, docs and API contracts affected.
   If the task conflicts with the code as it exists, the code is the source of truth for
   facts (names, commands, counts); architecture changes still need an explicit request.
4. **Smallest safe change**: implement only what was asked, following the existing style.
   No speculative refactoring, no future-proof abstractions, no fake or stubbed
   implementations presented as real. Existing patterns first.
5. **Tests**: add or extend tests next to the existing ones. Preferred layers: pure unit
   tests for mappers/pure logic; `@SpringBootTest` + `@ActiveProfiles("test")` (H2 +
   Flyway) for services/persistence; MockMvc for HTTP. Isolation: `@Transactional`
   rollback by default; use `@AfterEach` cleanup only when transaction boundaries are the
   thing under test. Never delete or disable a failing regression test to get green.
6. **Full regression** (see below). Report exact totals.
7. **Diff review**: `git status`, `git diff` (and `--cached`). Unrelated production
   changes, migration edits or contract changes must be justified or reverted.
8. **Docs**: update README only for developer-facing usage; write the work-order record
   (`docs/work-orders/YYYY-MM-DD-<name>.md`, plus `*-instruction.md` verbatim when an
   instruction was given): purpose, decisions, what changed, test results, limitations,
   next steps.
9. **Secrets check** before staging: grep the diff for `password`, `token`, `secret`,
   `api_key`, `GARMIN_`, `INTERVALS_`, GPS-like fields; confirm no `.env`, build output,
   IDE files, logs.
10. **Commit / push** only when the task asks for it and tests pass; use the commit
    message the task specifies (Conventional Commits style otherwise).

## Running the tests

All Gradle commands run from `server/` with the wrapper. **Java 21 is required.**

```powershell
cd server
.\gradlew.bat clean test          # full regression (H2 in-memory + Flyway)
.\gradlew.bat test --tests "com.runningai.integration.garmin.*"   # one package
```

Git Bash / POSIX: `./gradlew clean test`.

`gradlew` honours `JAVA_HOME`. If the build fails with an unsupported class version or a
toolchain error, check `java -version` for the JDK `gradlew` is using and point
`JAVA_HOME` at a JDK 21 (per machine, e.g. through `.claude/settings.local.json` `env`,
never hard-coded in the repo). Report a JDK mismatch as the cause instead of working
around it. The wrapper downloads Gradle 8.14.x on first run; a PKIX/TLS error means a
proxy is intercepting the download, not a project problem.

`scripts/dev/validate-server.ps1` runs the same suite (the Stop hook uses it); pass
`-Force -Clean` to run it regardless of the cache.

Smoke run without PostgreSQL: `.\gradlew.bat bootRun --args='--spring.profiles.active=test'`
then `GET http://localhost:8080/api/v1/health`.

## Completion report

Implemented / files / architecture decisions · tests (`gradlew clean test`: total, passed,
failed; existing vs new) · migration added? · branch, commit, push · limitations ·
suggested next steps (do not start them). Never describe unimplemented work as done.
