# Windows Runtime `.env` Loading Fix (result)

Baseline commit `83c4559`, branch `main`. Implements the fix scoped in
`2026-10-02-windows-runtime-env-loading-fix.md`.

## 1. Root cause

`start-running-ai.ps1` launches Spring as `java -jar <jar>` with no `--spring.profiles.active`
argument. `local` activates only as Spring Boot's **default** profile
(`spring.profiles.default: local` in `application.yml`), and only `application-local.yml` imports
the repo-root `.env` (`spring.config.import: optional:file:../.env[.properties]`).

Reproduced directly (clean shell, no `SERVER_ADDRESS`/`RUNNINGAI_MCP_ENABLED` pre-set,
`java -jar build/libs/running-ai-server-*.jar --server.port=8097` from `server/` — the same working
directory `start-running-ai.ps1` uses):

- `RUNNINGAI_MCP_ENABLED` **did** apply (`/mcp initialize` succeeded) — `application.yml` resolves
  it via a literal `${RUNNINGAI_MCP_ENABLED:false}` placeholder, and placeholder resolution is an
  exact-key lookup across every property source, including the one the `.env` import creates.
- `SERVER_ADDRESS` did **not** apply (Spring bound `0.0.0.0`/`::`, confirmed with
  `Get-NetTCPConnection`) — `server.address` is a built-in `ServerProperties` field reachable from
  an env-style key only through Spring Boot's legacy underscore-to-dotted relaxed-binding mapper,
  which is restricted to genuine `SystemEnvironmentPropertySource` entries (real OS environment
  variables), not to a plain file-backed property source created by `spring.config.import` — even
  with the `[.properties]` hint.

**Conclusion**: the repo-root `.env` is not a reliable substitute for a real process environment
variable through Spring's own import — it happens to work for properties this repo's YAML
references by literal placeholder, and silently does not for Spring Boot's own relaxed-bound
built-ins (and, by the same rule, any future one). The fix is in the launcher, not in Spring/Java:
make `start-running-ai.ps1` parse `.env` itself and set each entry as a real process environment
variable before spawning any child process.

## 2. Changed files

- `scripts/windows/RunningAI.Common.ps1` — new `Import-DotEnvIntoProcess` (pure parsing +
  environment-setting, returns `{Applied, SkippedExisting, MalformedLines}`) and
  `Initialize-DotEnvForThisProcess` (loads the repo-root `.env`, logs key names/counts only).
- `scripts/windows/start-running-ai.ps1` — calls `Initialize-DotEnvForThisProcess -Root $root` as
  the very first step inside the `try` block, before Docker/PostgreSQL/connector/Spring.
- `scripts/windows/tests/Test-RunningAI.ps1` — 11 new `Check` blocks (below); all pre-existing
  checks untouched and still pass.
- `docs/work-orders/2026-10-02-windows-runtime-env-loading-fix.md` (this task's plan).

No Java, Spring (`server/`) or Python (`tools/garmin-connector/`) file was touched.
`stop-running-ai.ps1`, `status-running-ai.ps1`, `install-running-ai-scheduled-task.ps1` and
`RunningAI.Watchdog.ps1` are unchanged — watchdog recovery re-invokes `start-running-ai.ps1` as a
fresh process, so it inherits the fix automatically; none of the others spawn Spring/the connector
directly.

## 3. Env precedence

**Existing process env > `.env` > Spring/consumer defaults.** `Import-DotEnvIntoProcess` checks
`Test-Path Env:$key` for every parsed key: if already set (operator's shell, Scheduled Task's own
environment, a future caller) it is left completely untouched and recorded in `SkippedExisting`;
only a key that is *not* already present gets `Set-Item Env:$key $value`, recorded in `Applied`.
Anything neither the process nor `.env` sets falls through to whatever default Spring (or Docker
Compose, unaffected by this change) already uses.

Parsing rules implemented exactly as specified: blank lines and `#`-comment lines ignored; split on
the **first** `=` only; key and whole-value whitespace trimmed at the ends, internal whitespace in
the value preserved verbatim (verified against the real
`GARMIN_PROFILE_SYNC_SCHEDULER_CRON=0 30 4 * * *` entry); UTF-8 read as raw bytes with an optional
leading BOM stripped before decoding; a line with no `=` (or an empty key) is counted as malformed
and skipped, never thrown. Values are never logged — `Initialize-DotEnvForThisProcess` prints only
key names and counts. Credentials reach Spring only through its environment: the `Start-Process
-ArgumentList "-jar ..."` call is unchanged, so nothing is ever appended as a command-line argument.

## 4. Tests

`scripts\windows\tests\Test-RunningAI.ps1`, non-destructive, no Docker/Garmin/network dependency:

**25 checks total, all PASS** (14 pre-existing + 11 new):
1. missing file → empty result, no throw
2. blank lines / `#` comments ignored; split on first `=` only (value itself contains `=`)
3. internal whitespace in a value preserved (cron-shaped value)
4. whitespace trimmed around key and around the whole value
5. malformed line (no `=`, or empty key) skipped and counted, never throws
6. UTF-8 file with a leading BOM parses correctly (BOM verified present in the raw bytes first)
7. a variable already set in the process is never overwritten (precedence)
8. `Initialize-DotEnvForThisProcess` logs the key name but never the value
9. no `.env` file present → no change to the process environment
10. `start-running-ai.ps1` calls the loader before any component step, and its `java -jar` argument
    list contains no `PASSWORD`/`API_KEY`/`TOKEN` text (static source check)
11. (existing) scripts contain no hard-coded user/drive paths or credentials — still passes with
    the new code

```
PS> powershell -NoProfile -ExecutionPolicy Bypass -File scripts\windows\tests\Test-RunningAI.ps1
... (25 PASS lines) ...
All checks passed.
```

## 5. Live validation — manual path

```
Remove-Item Env:SERVER_ADDRESS -ErrorAction SilentlyContinue
Remove-Item Env:RUNNINGAI_MCP_ENABLED -ErrorAction SilentlyContinue
.\scripts\windows\stop-running-ai.ps1
.\scripts\windows\start-running-ai.ps1
```

Output included:
```
[02:23:00] .env: applied 10 variable(s) not already set in the process environment
(POSTGRES_PASSWORD, DB_PASSWORD, INTERVALS_API_KEY, WORKOUT_PUBLISHING_ENABLED,
WORKOUT_PUBLISHING_SCHEDULER_ENABLED, GARMIN_PROFILE_SYNC_ENABLED,
GARMIN_PROFILE_SYNC_SCHEDULER_CRON, GARMIN_PROFILE_SYNC_SCHEDULER_ZONE, SERVER_ADDRESS,
RUNNINGAI_MCP_ENABLED)
[02:23:16] Spring Boot: UP on port 8080 (PID 27048)
```
(only key names logged, never values)

```
Get-NetTCPConnection -State Listen -LocalPort 8080 | Select-Object LocalAddress, LocalPort, OwningProcess
```
→ `LocalAddress = 127.0.0.1`, `LocalPort = 8080` (no `0.0.0.0`/`::` entry) — **PASS**.

`POST /mcp` `initialize` → `{"jsonrpc":"2.0","id":1,"result":{"protocolVersion":"2025-11-25", "capabilities":{"tools":{"listChanged":false}},"serverInfo":{"name":"running-ai","version":"0.0.1"}}}`
— **PASS**.

## 6. Scheduled Task validation

```
.\scripts\windows\stop-running-ai.ps1
Start-ScheduledTask -TaskName "RunningAI-Startup"
Start-Sleep -Seconds 30
```

- `Get-ScheduledTaskInfo -TaskName "RunningAI-Startup"` → `LastTaskResult = 0` — **PASS**.
- `.runtime\spring.pid` / `.runtime\garmin-connector.pid` present and alive (Spring PID `32148`,
  connector PID `37200`) — managed, **PASS**.
- `Get-NetTCPConnection -LocalPort 8080` → `127.0.0.1` only — **PASS**.
- `POST /mcp` `initialize` → succeeded identically to the manual-path check above — **PASS**.

(The Scheduled Task runs `start-running-ai.ps1` hidden with no console capture, so the
`.env: applied ...` line itself isn't written to a log file for this run — only
`start-running-ai.ps1`'s own stdout is affected, not Spring's/the connector's redirected logs — so
this path was confirmed behaviorally: bind address, managed PIDs and `/mcp` all correct, which is
only possible if `.env` was in fact applied before the Java process started.)

## 7. Commit

See the commit this result doc is part of (branch `main`); SHA recorded here right after the
commit is created, same as the Phase 6D work order's convention.

## Known limitation (not in scope, not fixed here)

`start-running-ai.ps1`'s `$SpringPort` parameter default (`$(if ($env:SERVER_PORT) {...} else
{8080})`) is evaluated at parameter-binding time, before `Initialize-DotEnvForThisProcess` runs
inside the script body. If a future `.env` ever introduced `SERVER_PORT` (it does not today — only
`SERVER_ADDRESS` and the other keys listed above), the script's own health-check polling would
still watch port 8080 while Spring actually came up on the `.env`-provided port. Fixing this would
require moving the `.env` load before parameter binding, which isn't natural in a PowerShell
`param()` block; flagged here rather than silently left for a future `SERVER_PORT` entry in `.env`
to rediscover the hard way.
