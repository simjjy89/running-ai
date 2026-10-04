# Windows PowerShell JSON API encoding (Phase 6H-7.2 / 6H-8)

```text
PowerShell object
     |  ConvertTo-Json
     v
JSON text
     |  [System.Text.UTF8Encoding]::new($false).GetBytes(...)
     v
UTF-8 byte[]                      <- Write-Output -NoEnumerate (see below)
     |  Invoke-WebRequest -Body <byte[]> -ContentType 'application/json; charset=utf-8'
     v
HTTP request                      -> Spring/Jackson (already correct, no server change)

HTTP response (Content-Type: application/json, no charset - Spring's default)
     |  Invoke-WebRequest -> RawContentStream.ToArray()
     v
raw bytes
     |  [System.Text.Encoding]::UTF8.GetString(...)
     v
JSON text
     |  ConvertFrom-Json
     v
PowerShell object
```

Both arrows into "HTTP request" / out of "HTTP response" are the two halves of
`Invoke-RunningAiJsonRequest` (`scripts/windows/RunningAI.Common.ps1`). Neither direction trusts
`Invoke-RestMethod`'s own body handling - it is used for neither sending nor parsing.

## Why not just `Invoke-RestMethod -Body ($x | ConvertTo-Json)`

That is the one invariant this file and `Invoke-RunningAiJsonRequest` exist to prevent
(`scripts/windows/RunningAI.Common.ps1` section comment, and CLAUDE.md). Two independent, real bugs
were found by building and live-testing the alternative (Phase 6H-7.2, Main PC):

### 1. Request side: `return`/pipeline array-unrolling silently corrupts the body

```powershell
function ConvertTo-RunningAiUtf8JsonBytes {
    param($Value, [int]$Depth = 30)
    $json = $Value | ConvertTo-Json -Depth $Depth
    return [System.Text.UTF8Encoding]::new($false).GetBytes($json)   # <-- looks right, is wrong
}
```

A bare `return` (or any unguarded pipeline output) of an array **unrolls it into individual
elements** across a function-return boundary - this is standard PowerShell pipeline behaviour, not
a bug in the language, but it is easy to miss for a `byte[]`. The caller's
`$bytes = ConvertTo-RunningAiUtf8JsonBytes ...` then collects an `Object[]` of *boxed bytes*, not an
actual `Byte[]`. `Invoke-RestMethod -Body <Object[]>` (or `Invoke-WebRequest`) does not recognize
that as a raw body and falls back to stringifying it - the literal bytes sent over the wire become
things like the text `"123 13 10 32 32 34 114 101 ..."` (the `$OFS`-joined **decimal** value of each
real JSON byte), not the JSON itself.

Fix: `Write-Output -NoEnumerate (...)` instead of `return (...)` wherever a function hands back a
byte array (or any array that must survive as one object). `ConvertTo-RunningAiUtf8JsonBytes` does
this; `scripts/windows/tests/Test-RunningAI.ps1` has a direct regression
(`ConvertTo-RunningAiUtf8JsonBytes produces no BOM and decodes back to the same string`) plus several
round-trip checks that would fail the same way if this regressed.

### 2. Response side: `Invoke-RestMethod` mis-decodes a charset-less `application/json` response

Spring's default JSON response `Content-Type` is `application/json` with **no** `charset`
parameter - this is normal, correct HTTP (RFC 8259 already implies UTF-8 for JSON) and is not a
server bug. Windows PowerShell 5.1's `Invoke-RestMethod`, however, silently falls back to a
non-UTF-8 encoding when parsing such a response. Live-observed against a real
`/api/v1/workout-drafts` response (Main PC): an em dash (U+2014, UTF-8 bytes `E2 80 94`) came back
as a different, wrong character - classic double-UTF-8 mojibake (the real UTF-8 bytes were decoded
as Latin-1 first, and *that* wrong text is what the caller received). Decoding the identical raw
response bytes as UTF-8 by hand gave back the correct em dash.

Fix: never call `Invoke-RestMethod` for a RunningAI response. `Invoke-RunningAiJsonRequest` uses
`Invoke-WebRequest`, reads `.RawContentStream.ToArray()`, decodes it as UTF-8 itself, and only then
calls `ConvertFrom-Json` on the resulting string - the same technique this file's pre-existing
`Get-HttpBody` already used for actuator/connector health checks (added earlier, for an unrelated
reason: non-text content types return `.Content` as `byte[]` in PS 5.1). A local-HttpListener
regression (`Invoke-RunningAiJsonRequest decodes a charset-less application/json response as UTF-8
(regression)`) reproduces this exact server shape without needing a real RunningAI server.

## Script-source encoding (a separate problem, not fixed by any of the above)

Every `.ps1` in this repo is plain UTF-8 **without a BOM**. Windows PowerShell 5.1 reads a no-BOM
`.ps1` using the system codepage, not UTF-8 - a Korean (or any non-ASCII) string **typed directly
into a `.ps1` source file** can become mojibake the moment the script runs, independent of anything
above (this is a source-parsing problem, not an HTTP problem). This is the leading suspect for the
mojibake previously observed in a `.ps1`'s own literal console messages.

This repository's answer is **not** to add a BOM (that would be inconsistent with every other
script here and the `.gitattributes` convention was deliberately left untouched) and **not** to
force a global console-encoding change (`chcp 65001` / `[Console]::OutputEncoding = ...`) - section
30 of the Phase 6H-7.2 work order explicitly forbids mixing an HTTP-body fix with a console-display
fix, and a blanket console override is a bigger, riskier change than this problem needs. Instead:

- Safety-critical runtime text (the final `YES`/`APPROVE` gates, cancellation/success messages in
  `RunningAI.CoachOperator.ps1`) is written in plain ASCII, so it never depends on this at all.
- A test fixture that genuinely needs non-ASCII text (Korean, or - for the response-decoding
  regression above - an em dash) is built from explicit Unicode code points
  (`Join-RunningAiTestChars` / `Join-Chars`, `[char]0xD55C` etc.) rather than typed as a literal,
  keeping the `.ps1` file itself pure ASCII. `scripts/windows/tests/Test-RunningAI.ps1` and
  `Test-CoachOperator.ps1` both do this.
- The athlete-facing natural-language fields that really do need Korean (a workout goal, a revision
  request) always arrive as a runtime **value** - typed by a human at a live console prompt, read
  from a `-GoalFile` whose raw bytes are explicitly UTF-8-decoded, or sent as a JSON request body -
  never as a literal baked into a `.ps1`'s own source.

## What this phase does not change

No `application.yml` encoding property, no `CharacterEncodingFilter`, no controller-level charset
workaround: Spring Boot's default `MappingJackson2HttpMessageConverter` already reads and writes
UTF-8 correctly (`server/src/test/kotlin/com/runningai/coach/WorkoutDraftApiTest.kt` and
`ClaudeCoachPromptBuilderUtf8Test.kt` make this an explicit, tested guarantee). Every bug above was a
Windows PowerShell client-side problem, not a server one.
