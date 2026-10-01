# Windows Runtime `.env` Loading Fix (work order)

Repo `C:\running-ai-github`, branch `main`, HEAD `83c4559` at the start of this task, working
tree clean. `C:\running-ai` (legacy) is not touched. This work order intentionally does not
recap Phase 6D; see the Phase 6D work order/result docs for that.

## Confirmed symptom (owner-provided)

`C:\running-ai-github\.env` contains (among other operational switches) `SERVER_ADDRESS=127.0.0.1`
and `RUNNINGAI_MCP_ENABLED=true`.

- Fresh shell (neither variable set) → `scripts\windows\start-running-ai.ps1` → Spring binds
  `0.0.0.0`/`::` instead of `127.0.0.1`.
- Same shell with `$env:SERVER_ADDRESS` / `$env:RUNNINGAI_MCP_ENABLED` set manually before running
  the same script → binds `127.0.0.1:8080`, `/mcp initialize` succeeds.

## Root cause (verified by direct repro, not guessed)

`start-running-ai.ps1` launches Spring as `java -jar <jar>` via `Start-Process` with no
`--spring.profiles.active` argument; `application.yml` makes `local` the **default** profile, and
`application-local.yml` imports the repo-root `.env` via
`spring.config.import: optional:file:../.env[.properties]`, which Spring Boot loads as a
`.properties`-format file into a plain property source.

Reproduced directly (`java -jar build/libs/running-ai-server-*.jar --server.port=8097`, clean
shell, no env vars pre-set, same working directory `server/` that `start-running-ai.ps1` uses):

- `RUNNINGAI_MCP_ENABLED` **did** take effect (`/mcp initialize` succeeded, "Registered tools: 1"
  logged) — `application.yml` references it as a literal placeholder (`${RUNNINGAI_MCP_ENABLED:false}`),
  and placeholder resolution does an exact-name lookup across *any* property source, including the
  one `.env` import created.
- `SERVER_ADDRESS` did **not** take effect (bound `0.0.0.0`/`::`, confirmed via
  `Get-NetTCPConnection`) — `server.address` is a built-in Spring Boot `ServerProperties` field with
  no explicit placeholder in this repo's YAML; it is only reachable from a `SCREAMING_SNAKE_CASE`
  key like `SERVER_ADDRESS` through Spring Boot's legacy underscore-to-dotted relaxed-binding
  mapper, and that mapper is deliberately restricted to real `SystemEnvironmentPropertySource`
  entries (actual OS environment variables / `-D` system properties) — **not** to a plain
  `MapPropertySource` loaded from a flat file via `spring.config.import`, regardless of the
  `[.properties]` hint.

So the repo-root `.env` file is **not reliably applied** through Spring's own import mechanism: it
happens to work for properties this project's YAML references by literal placeholder, and silently
does not work for Spring Boot's own relaxed-bound built-in properties (`server.address`, and by the
same rule any future built-in property configured only by its env-style name). This is fragile and
depends on which specific property is involved, which is exactly the kind of thing that should not
be left as "sometimes works". The owner's instruction is correct that the fix belongs in the
launcher, not in Spring/Java/Python code: make `start-running-ai.ps1` parse `.env` itself and set
each entry as a **real process environment variable** before spawning `java -jar` (and, for
symmetry, before spawning the Garmin connector) — exactly reproducing the manually-`$env:`-set case
that is already confirmed to work, for every current and future `.env` key, independent of which
Spring binding path consumes it.

## Scope

- New shared helper in `scripts\windows\RunningAI.Common.ps1`: `Import-DotEnvIntoProcess` — parses
  `KEY=VALUE` lines from a file into the **current PowerShell process's** environment block, so
  every child process `Start-Process`es afterward (connector, Spring, and — via
  `RunningAI.Watchdog.ps1`'s `Invoke-RecoveryAction`, which always re-invokes
  `start-running-ai.ps1` as a fresh process — any watchdog-triggered restart) inherits it exactly
  like a manually-set `$env:` variable.
- `start-running-ai.ps1` calls it once, early, against the repo-root `.env`, before Docker/Postgres/
  connector/Spring steps, with precedence **existing process env > `.env` > Spring defaults**: a key
  already present in the process environment (operator's shell, Scheduled Task's own env, or a
  future caller) is left untouched; `.env` only fills in what is not already set; anything neither
  sets falls through to Spring's own defaults exactly as today.
- Parsing rules (all required by the owner): blank lines and `#`-comment lines ignored; split only
  on the **first** `=` (so a value may itself contain `=`); leading/trailing whitespace trimmed from
  the key and from the whole value, but internal whitespace inside the value is preserved verbatim
  (`GARMIN_PROFILE_SYNC_SCHEDULER_CRON=0 30 4 * * *` must survive with its internal spaces intact);
  UTF-8 with or without a leading BOM is accepted (bytes are read and decoded explicitly, not via
  `Get-Content`'s encoding auto-detection); a line with no `=` (or an empty key) is skipped as
  malformed and counted, never thrown.
- Never logs a value — only key names and counts (`Write-Step`), so the console/log never contains
  `DB_PASSWORD`, `INTERVALS_API_KEY`, or any other value from `.env`.
- Credentials reach the Spring process **only** via its environment (the same `Start-Process
  -ArgumentList "-jar ..."` call as before, unchanged) — never appended as a `-D`/command-line
  argument. This is an invariant of the existing script (nothing was ever passed that way); the new
  code does not change the `Start-Process` argument list at all, only the environment the process
  inherits.
- Docker Compose's own, independent reading of the repo-root `.env` (for `${POSTGRES_PASSWORD:-...}`
  etc. in `docker-compose.yml`) is untouched: the `docker compose ...` invocations are not modified
  in any way.
- `stop-running-ai.ps1`, `status-running-ai.ps1`, `install-running-ai-scheduled-task.ps1` and
  `RunningAI.Watchdog.ps1` are not modified — none of them spawn the Spring/connector process
  directly with configuration that depends on `.env` (watchdog recovery re-invokes
  `start-running-ai.ps1`, which already gets the fix).
- No Java, Spring (`server/`) or Python (`tools/garmin-connector/`) source file is touched.
- Existing self-tests in `scripts\windows\tests\Test-RunningAI.ps1` keep passing unmodified; new
  `Check` blocks are added there for the parsing rules and precedence (non-destructive, no real
  `.env` file or Docker/Garmin dependency).

## Required live validation (run after implementation + tests)

1. Manual path: unset `SERVER_ADDRESS`/`RUNNINGAI_MCP_ENABLED` in the shell, `stop-running-ai.ps1`,
   `start-running-ai.ps1`, confirm `Get-NetTCPConnection -LocalPort 8080` shows `127.0.0.1` only and
   `/mcp` `initialize` succeeds.
2. Scheduled Task path: `stop-running-ai.ps1`, `Start-ScheduledTask -TaskName RunningAI-Startup`,
   wait, confirm `LastTaskResult = 0`, Spring/connector PIDs are tracked (`.runtime\*.pid`), port 8080
   is `127.0.0.1` only, and `/mcp initialize` succeeds.

## Result doc

`2026-10-02-windows-runtime-env-loading-fix-result.md`, written after implementation, self-tests,
and both live validations above.
