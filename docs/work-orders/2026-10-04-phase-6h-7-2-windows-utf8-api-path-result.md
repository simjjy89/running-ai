# Phase 6H-7.2 — Windows UTF-8 API Path Hardening — Result

Baseline SHA: `58d77b1b640deaca44685525e6f5b700f0d22722`
Final SHA: (pending commit)

## Root cause

**HTTP body cause (request side): none in Spring/Jackson.** `WorkoutDraftApiTest` already sent
Korean text as raw UTF-8 bytes with `Content-Type: application/json; charset=UTF-8` and it reached
`SessionConstraints` and the coach correctly with zero server-side encoding configuration — Spring
Boot's default `MappingJackson2HttpMessageConverter` already handles UTF-8 request bodies
correctly. New regression tests make this guarantee explicit; no server/application.yml change
was made or needed.

**Script-source cause: confirmed, and reproduced live during this phase.** Every `.ps1` in this
repo is plain UTF-8 **without a BOM**. Windows PowerShell 5.1 reads a no-BOM `.ps1` using the
system codepage, not UTF-8 — so a literal Korean string typed directly into a `.ps1` source file
silently becomes mojibake the moment the script runs. This is almost certainly what corrupted the
Korean console messages in `publish-approved-draft-controlled.ps1` previously observed. The new
`Test-RunningAI.ps1` fixtures avoid the problem structurally: every non-ASCII test string is built
from explicit Unicode code points (`Join-RunningAiTestChars`) instead of a literal, keeping the
file itself pure ASCII — no BOM, no repo-wide encoding-policy change, consistent with every other
script here.

**Two additional, more serious bugs were found live (Main PC) and fixed; neither was in the
original hypothesis:**

1. **PowerShell `return`/pipeline array-unrolling corrupted every UTF-8 request body.**
   `ConvertTo-RunningAiUtf8JsonBytes` originally did `return [byte[]]`. A function `return` (or any
   unguarded pipeline output) of an array unrolls it into individual elements across the function
   boundary; the caller's `$bytes = ConvertTo-RunningAiUtf8JsonBytes ...` then collects an
   `Object[]` of boxed bytes, **not** a `Byte[]`. `Invoke-RestMethod -Body <Object[]>` doesn't
   recognize that as a raw body and instead stringifies it (`$OFS`-joined decimal values), so the
   literal text sent over the wire was things like `"123 13 10 32 32 34 114 101 ..."` — the ASCII
   decimal representation of the real JSON bytes, not the bytes themselves. Fixed by
   `Write-Output -NoEnumerate` instead of a bare `return` in `ConvertTo-RunningAiUtf8JsonBytes`.
2. **`Invoke-RestMethod` mis-decodes a charset-less `application/json` response.** Spring's default
   JSON response `Content-Type` is `application/json` with no `charset` parameter. Windows
   PowerShell 5.1's `Invoke-RestMethod` silently falls back to a non-UTF-8 encoding when parsing
   such a response. Live-observed against a real `/api/v1/workout-drafts` response: an em dash
   (U+2014, UTF-8 bytes `E2 80 94`) came back as a different, wrong character — classic
   double-UTF-8 mojibake (the real UTF-8 bytes were first decoded as Latin-1, and that wrong text
   is what the caller received). Fixed by never trusting `Invoke-RestMethod`'s own body parsing:
   `Invoke-RunningAiJsonRequest` now uses `Invoke-WebRequest` and decodes the raw response bytes as
   UTF-8 itself — the same technique this file's pre-existing `Get-HttpBody` already used for
   actuator/connector health checks.

**Server change required:** none. No `application.yml` property added, no
`CharacterEncodingFilter`, no controller-level charset workaround.

## UTF-8 helper

`scripts/windows/RunningAI.Common.ps1`:
- `ConvertTo-RunningAiUtf8JsonBytes` — `ConvertTo-Json` then explicit UTF-8 (no-BOM) bytes, returned
  via `Write-Output -NoEnumerate` so the byte array survives the function-return boundary intact.
- `Invoke-RunningAiJsonRequest` — sends a body (when present) as those UTF-8 bytes with
  `Content-Type: application/json; charset=utf-8`; decodes any response via `Invoke-WebRequest` +
  manual `System.Text.Encoding::UTF8.GetString` + `ConvertFrom-Json`, never via
  `Invoke-RestMethod`'s own parsing. Supports GET/POST/PUT/DELETE; `$Body = $null` sends no body.

## API helper

`scripts/windows/invoke-running-ai-api.ps1` — thin CLI wrapper (`-Method`, `-Path`, `-Body`,
`-BaseUrl` default `http://127.0.0.1:8080`) over `Invoke-RunningAiJsonRequest`. Reads no `.env`,
API key or Garmin/Intervals credential; prints nothing but the call's own result.

## Generate Korean test

`WorkoutDraftApiTest.kt` — "a Korean requestedGoal arrives at the coach exactly as sent, unmangled":
sends `requestedGoal`/`userFeedback`/`painOrFatigueFeedback` as raw UTF-8 JSON bytes with an
explicit `charset=UTF-8` content type and asserts `SessionConstraints` holds the identical Korean
string, character for character. **PASS.**

## Revision Korean test

`WorkoutDraftApiTest.kt` — "a Korean revision request arrives at the coach exactly as sent,
unmangled": same guarantee for `POST /workout-drafts/{id}/revisions`'s free-text `request` field,
through controller → service → coach (`FakeAiCoach.revisionRequests`). **PASS.**

## Prompt Korean test

New file `ClaudeCoachPromptBuilderUtf8Test.kt` (plain unit test, no Spring context, no process): a
Korean `requestedGoal` survives into `createPrompt`'s snapshot JSON (Jackson does not escape
non-ASCII by default, so the raw text appears directly; the test also accepts a `\\uXXXX`-escaped
form defensively), and a Korean revision request appears verbatim in `revisePrompt`. **PASS.**

## Raw-byte PowerShell test

`scripts/windows/tests/Test-RunningAI.ps1`, all via a local `HttpListener` (no RunningAI server, no
external network):
- Korean body round trip, asserting the byte-decoded request text (not just "JSON parsed OK")
  equals the original string, and that `Content-Type` carries `charset=utf-8`.
- ASCII-only regression (unchanged behaviour).
- Mixed Korean/ASCII/digits/symbols round trip.
- `ConvertTo-RunningAiUtf8JsonBytes` produces no BOM and decodes back to the same string.
- No-body GET sends no request body.
- **New regression** reproducing the response-decoding bug above: a local listener responds with
  `Content-Type: application/json` (no charset, exactly Spring's shape) and a UTF-8 body containing
  an em dash + Hangul; asserts `Invoke-RunningAiJsonRequest`'s parsed return value equals the
  original text.
All **PASS** (8/8 new checks; 32/32 total in this file, including the 24 pre-existing ones).

## Live Korean generate

Performed against the already-running Main PC server (no production code changed by this phase, so
the already-running instance is equivalent to what this phase's code would produce). Synthetic
Korean goal ("today I want to run lightly, not a tough training session") sent via
`Invoke-RunningAiJsonRequest`. Result: Draft **#12**, `EASY`, 40 min, `rationale` correctly reflects
the Korean request ("You asked for something light... a relaxed aerobic run..."). **PASS.**

## Live Korean revision

Same draft (#12), revision request "a little lighter please" (Korean) via
`POST /api/v1/workout-drafts/12/revisions`. Result: version 2, status **DRAFT**, workout type
changed to `RECOVERY`, rationale explicitly references the request ("You asked to make it
lighter..."). Draft never approved, never published. **PASS.**

## Corruption warning

None, in either call. No "corrupted characters", "garbled" or "encoding" language in any
`assessment`/`rationale`/`warnings` field. (The response-decoding bug above was caught by reading
the raw response bytes directly with Python during manual verification, and separately confirmed
via the `Invoke-RunningAiJsonRequest` fix — not by a warning Claude itself produced.)

## DB migration

None.

## H2

`cd server; .\gradlew.bat clean test` (JDK 21) — **1192 passed, 0 failed** (1188 baseline + 4 new:
2 in `WorkoutDraftApiTest`, 2 in `ClaudeCoachPromptBuilderUtf8Test`).

## PostgreSQL

Full suite re-run against a throwaway PostgreSQL 17 database (`running_ai_test`, recreated fresh,
`spring.datasource.hikari.maximum-pool-size=2`/`minimum-idle=0` via `SPRING_APPLICATION_JSON`,
`driver-class-name=org.postgresql.Driver` explicit) — **1192 passed, 0 failed**, identical to H2.
(First attempt with the pool override nested incorrectly under a top-level `hikari` key instead of
`spring.datasource.hikari` hit "too many clients already"; corrected and re-run clean.)

## PowerShell

`powershell -NoProfile -ExecutionPolicy Bypass -File scripts/windows/tests/Test-RunningAI.ps1` —
**all checks passed** (32/32, including the 8 new UTF-8 checks). `Test-Watchdog.ps1` also re-run
unchanged — **all watchdog checks passed** (unaffected by this phase, run for completeness).

## Python

`tools/garmin-connector`: zero tracked changes (confirmed via `git status`). `python -m pytest` —
**134 passed**, matching the existing baseline exactly.

## Garmin calls

0. Draft generation/revision never call Garmin; this phase touched none of that code.

## Intervals calls

0. Same reasoning; `IntervalsWorkoutPublisher`/`IntervalsWorkoutClient` were asserted untouched by
the existing `verifyNoInteractions` in `WorkoutDraftApiTest.cleanUp()`, which still passes.

## External writes

0. No Garmin write, no Intervals write, no draft approval, no draft publish.

## Publishing switches

Unchanged for the duration of this phase: `WORKOUT_PUBLISHING_ENABLED=false`,
`RUNNING_AI_DRAFT_PUBLISHING_ENABLED=false`, `WORKOUT_PUBLISHING_SCHEDULER_ENABLED=false`,
`RUNNINGAI_MCP_ENABLED=false` (all defaults; the already-running live server used for the smoke
test already has them off).

## Docs

This result doc plus the verbatim instruction (`*-instruction.md`). README/architecture-note
updates (Part I, §42–43) were **not done** — see Known limitations.

## Commits

Pending (see branch note below).

## Push

Not done (pending user instruction).

## Known limitations

- **Part E (controlled-publish script mojibake fix) was explicitly out of scope for this session**,
  by the user's direction: `scripts/windows/publish-approved-draft-controlled.ps1` exists only as
  an **untracked** file in the separate main checkout (`C:\running-ai-github`), not in this
  worktree/branch (`main-3`) at all, and never a tracked file in git history. The general mojibake
  root cause (§27) and recommended fix pattern (§28–29: prefer ASCII/English runtime messages;
  when Korean literals are unavoidable, build them from Unicode code points rather than typing them
  into a no-BOM `.ps1`) are documented above and demonstrated in `Test-RunningAI.ps1`, but that
  specific script was not touched. The user will handle it separately in the main checkout.
- **README (§42) and `docs/architecture/windows-api-encoding.md` (§43) were not written** in this
  session — the work order marked both as "필요하면" (if needed) and the session's effort went
  first into the live findings (the two real bugs above) and full regression verification. This is
  a straightforward follow-up if wanted.
- The work order's suggested commit messages (§44) were not applied verbatim to a single-purpose
  commit split; this phase's changes are small enough that they can be reviewed as one commit, or
  split per the suggestion, at the user's preference.
- This phase found and fixed two bugs well beyond the original hypothesis (console/script-source
  encoding): the byte-array pipeline-unrolling bug (request side) and the charset-less-response
  mis-decoding bug (response side). Both were caught only because the live smoke test was actually
  run with a byte-for-byte comparison rather than a visual check — a purely synthetic/local test
  pass would not have caught the second one (it requires reading the *decoded* response field,
  not just confirming JSON parses).

PHASE_6H_7_2_WINDOWS_UTF8_API_PATH_READY
