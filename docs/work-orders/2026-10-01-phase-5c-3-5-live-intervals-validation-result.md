# Phase 5C-3.5 Live Intervals Validation — result

Baseline commit `138ae4e`. No production code was changed: the live server matched the Phase 5C-3 contract on every
checked point. No identifier, key or event id is recorded here (status only).

## Environment
- credential available: yes (`INTERVALS_API_KEY` present in the process; value never printed). `INTERVALS_ATHLETE_ID` not set → default `0` (key-owner shortcut).
- live validation attempted: yes
- test date safely isolated: yes — a read-only scan (WORKOUT lookup plus an all-category event listing) picked a future date with **0 events of any category**; nothing existing was touched.
- Local environment note (not a code issue): the JDK 21 trust store did not contain the certificate chain seen on this PC
  (`PKIX path building failed`, while Windows/PowerShell reached the host). The live run used the Windows trust store
  (`-Djavax.net.ssl.trustStoreType=Windows-ROOT` via `JAVA_TOOL_OPTIONS`, test JVM only). Certificate verification was never disabled.
  Anyone running Spring against Intervals.icu on this PC needs the same JVM option (or the corporate CA imported into the JDK).

## Connectivity
- authentication: PASS
- lookup (per-day WORKOUT list): PASS

## First publish
- operation: CREATED
- verified: true
- remote id returned: yes
- external_id round-trip = **PASS** (present, equal to the expected marker)
- date round-trip: PASS
- description round-trip: PASS (byte-exact)
- type behavior: `Run` stored and returned unchanged
- name behavior: stored and returned unchanged

## Idempotency
- second identical publish: NO_CHANGE, verified=true, same remote id, 0 extra POST, 0 extra PUT
- remote owned event count: 1

## Update
- changed publish (main block duration 30m → 35m): UPDATED, verified=true, 1 PUT, 0 POST
- same remote id: yes
- readback: external_id PASS, date PASS, description exact (new text)
- final repeated publish: NO_CHANGE, verified=true, same id, 0 writes; event count still 1

Sequence observed: `CREATE → NO_CHANGE → UPDATE (same remote ID) → NO_CHANGE`. No duplicate event.

## Server normalization
- newline: none observed (no CR in readback)
- trailing newline: none added
- description: exact match, length delta 0
- other normalization: none observed. The publisher's minimal `normalize` (CRLF/LF, trailing newlines) was therefore not exercised live; it is left unchanged and was not widened.

## Code changes
- required: no
- reason: no live mismatch
- files: none (production); the temporary runner test was deleted
- new regression tests: none

## Tests
- Java total: 396
- passed: 396
- failed: 0 (skipped 0) — `gradlew clean test`, JDK 21

## Verification status
- Intervals renderer: UNIT_VERIFIED
- Intervals publisher: SERVER_VERIFIED
- Intervals server readback: SERVER_VERIFIED
- Pace Garmin: UNRESOLVED
- %LTHR Garmin: ASSUMED
- Treadmill cue Garmin: DEVICE validation required

## Cleanup
- test event: **retained** on the isolated test date (synthetic "RunningAI workout", Run, 35m main block). No delete function exists in the publisher by design and no delete was attempted. It should be removed manually (or on explicit instruction) so it does not sync to a device.
- temporary files removed: yes (runner source deleted; scratch output outside the repo)

## Limitations
- One athlete, one date, one workout shape (HR-based, no pace/treadmill cues); other renderer variants were not published live.
- Legacy `[RunningAI-Control]` takeover, foreign-event conflict and duplicate paths are unit-tested only, not exercised live.
- Garmin device sync of the published event is Phase 5C-4 (not started).
