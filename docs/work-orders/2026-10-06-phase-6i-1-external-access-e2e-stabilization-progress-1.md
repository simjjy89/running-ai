# Phase 6I-1 — External Access E2E Stabilization — Progress note 1

Status: **IN PROGRESS, not READY.** This note covers only the infrastructure-neutral relay
relocation/hardening step (addendum instruction, 2026-10-06). No Cloudflare, no tunnel, no DNS,
no Access, no transport decision - all explicitly on hold per that instruction. Do not read this
as `PHASE_6I_1_EXTERNAL_ACCESS_E2E_READY` - that marker has not been reached and is not claimed
here.

## What was inventoried first (per the original instruction's "do not guess and duplicate" rule)

- `C:\running-ai\watchface-relay` is a standalone Node.js service (`server.js`,
  `workout-types.js`, `generate-token.js`, `config.json`) with **no `package.json` / no npm
  dependencies** - pure Node built-ins (`http`, `https`, `fs`, `crypto`, `path`).
- It queries **Intervals.icu directly** (`INTERVALS_ICU_API_KEY` env var) and reads a legacy flat
  file (`C:\running-ai\state\today_plan.json`) for an explicit REST override. It does **not**
  talk to the Spring backend (port 8080) at all.
- Contract: `GET /health` -> `{"status":"ok"}` (no auth); `GET /today-workout` -> `{"date",
  "status":"HAS_PLAN"|"REST"|"NO_PLAN","label"}` (Bearer-token gated, `secrets/watch-token.json`,
  `crypto.timingSafeEqual`). Binds `127.0.0.1` only.
- Already-live, currently running, untouched by this step:
  - 1 `node.exe` process serving the relay from the original location, on `127.0.0.1:17845`.
  - **3 duplicate** `cloudflared.exe tunnel --url http://127.0.0.1:17845` quick-tunnel processes
    (ad hoc `Start-Process`, no Windows Service, no Named Tunnel, no `cert.pem` on this machine -
    `cloudflared` has never been authenticated to any Cloudflare account here).
  - The installed watch face (`C:\running-ai-watchface\source\ArcadeRelay.mc`) is hardcoded
    against one of these quick-tunnel's `*.trycloudflare.com` URLs and the real watch-scoped
    token - both treated as sensitive, neither reproduced here.
  - `RunningAI-Startup` (this repo's own scheduled task) only launches the Garmin connector and
    Spring - it does not start the relay or any tunnel; those are started manually via
    `watchface-relay\start.ps1` or by hand.
- None of the above was stopped, restarted, or reconfigured. Per the addendum's §9, the 3
  duplicate quick-tunnel processes are recorded here and left running untouched; cleanup is
  deferred to the actual cutover, once a transport is chosen.

## What was done this step

### 1. Relay relocated into this repo: `tools/external-relay/`

`server.js`, `workout-types.js`, `generate-token.js`, `config.json` copied from
`C:\running-ai\watchface-relay` **byte-for-byte unchanged** - verified by SHA-256 before and
after the copy:

```
server.js         61e2645a30b16ec99759602122dbc8b7e7200b2e3109f8bb11b7234d0696f6ef  (both locations, identical)
workout-types.js  7e87b4e3e0d774f7bb3f087fccbe08da400752fe8334e49224df62cb5b0f5da3  (both locations, identical)
```

`generate-token.js` and `config.json` diffed empty (`diff` produced no output) against the
originals. **No logic changed, no response shape changed, no auth scheme changed, no data-source
changed.** The original files at `C:\running-ai\watchface-relay` were not modified or deleted -
they remain the ones actually serving the live watch face traffic.

A new `tools/external-relay/README.md` documents the migration, the preserved contract, and
explicitly that this location is not yet the live one.

### 2. Regression tests written and run (locking in current behavior before/during the move)

`tools/external-relay/test/` (pure Node, `node:test`, zero npm dependencies):

- `workout-types.test.js` - direct unit tests of the pure label-formatting functions. Found and
  correctly characterized one pre-existing quirk rather than "fixing" it: `extractIntervalMetric`'s
  regex requires a lowercase `m` unit suffix (no `/i` flag) - `"6 X 800M"` (uppercase M) does
  **not** match, `"6x800m"` / `"6×800m"` do. Preserved as-is, documented in the test, not changed.
- `server.test.js` - black-box HTTP tests against disposable spawned instances of this
  directory's own `server.js` (copied fresh into an OS temp dir per instance so the suite always
  exercises exactly the committed bytes), each on a test-only port with a freshly generated
  disposable token (never the real production token) and `WATCHFACE_RELAY_MOCK=1` (the server's
  own pre-existing escape hatch). **Never contacts the live production process (port 17845),
  its real token, or the real `today_plan.json`.** Covers: `/health` (no auth), `/today-workout`
  401 (missing header / wrong token / wrong scheme), 200 with the mocked `HAS_PLAN` payload,
  unknown-path 404, wrong-method 404, and rate limiting (isolated instance with a lowered
  `rate_limit_per_minute` so the test is fast and deterministic without needing 20+ requests
  against the shared functional-test instance).
- Not covered (accepted gap, consistent with "lock in current behavior for an unchanged
  migration" rather than a rewrite): the real `today_plan.json` REST-decision path and the real
  Intervals.icu event-lookup path inside `resolveToday` - exercising either would require either
  touching the real legacy state file or mocking the Intervals.icu network call. Documented in
  `tools/external-relay/README.md`.

Result: **14/14 passed** (`node --test tools/external-relay/test/*.test.js`).

### 3. Managed-lifecycle scripts prepared (process only, no transport)

`scripts/windows/external/`:

- `RunningAI.ExternalRelay.Common.ps1` - shared helpers (marker/port/health-check), mirroring the
  existing `Get-ConnectorMarkers`/`Test-ConnectorHealth` pattern in `RunningAI.Common.ps1`.
- `start-external-relay.ps1` - starts `node server.js` from `tools/external-relay` if not already
  healthy; refuses to start a second process on an already-occupied port (so it will not conflict
  with the still-running legacy instance - verified live: it correctly reports "already running,
  not restarted" against the real port 17845, since the legacy instance answers `/health`
  identically).
- `stop-external-relay.ps1` - stops only a PID this script itself started and tracked; reports
  "not running under RunningAI control" for the legacy ad hoc instance (verified live - does
  not touch it).
- `status-external-relay.ps1` - read-only `/health` check plus managed/unmanaged reporting
  (verified live against the real running instance: `ExternalRelay UP 127.0.0.1:17845 (not
  started by RunningAI scripts)`).

**None of these three scripts are wired into `start-running-ai.ps1`, `stop-running-ai.ps1`,
`status-running-ai.ps1`, `RunningAI.Watchdog.ps1`, or any scheduled task.** That integration, and
cutting the live process over from the legacy location to `tools/external-relay`, are deferred to
a later step once the transport decision is made - starting it now would either conflict with or
silently duplicate the relay currently serving the real watch face.

`RunningAI.Common.ps1`'s log-rotation name lists (`$script:ManagedLogNames`,
`$script:RotatedLogPattern`) were extended to include `external-relay.out`/`external-relay.err`
so the new scripts' logs participate in the existing retention/rotation machinery once wired in -
a small, additive, backward-compatible change verified against the existing
`Test-Watchdog.ps1` log-rotation checks (unchanged, still passing).

New test file `scripts/windows/tests/Test-ExternalRelay.ps1` (9 checks, all passing): scripts
parse; config-port parsing against the real committed `config.json`; `Test-ExternalRelayHealth`
true/false/false/false across `{"status":"ok"}` 200, a different status value, a non-2xx
response, and nothing listening (all via a disposable fake `HttpListener`, no real port 17845
touched); marker correctness; no hard-coded legacy repo path in any new script (an existing
regression test, `Test-RunningAI.ps1`'s "scripts contain no hard-coded user/drive paths or
credentials" check, already enforces this repo-wide - this was caught and fixed during this step,
see Known issues below); and the port-conflict refusal behavior, exercised against a real (but
non-HTTP, disposable) TCP listener occupying a test port.

## Regression results

- **PowerShell**: `Test-RunningAI.ps1` 39/39, `Test-CoachOperator.ps1` 25/25, `Test-Watchdog.ps1`
  36/36 (all unchanged, still green after the `RunningAI.Common.ps1` log-name addition), plus the
  new `Test-ExternalRelay.ps1` 9/9. **Total 109/109** (baseline 100 + 9 new).
- **Node (new)**: `tools/external-relay/test/*.test.js` **14/14**.
- **Server-side (Java/Kotlin)**: zero files under `server/` touched this step (`git status`
  confirms). The existing baseline (H2 1203/1203, PostgreSQL 1203/1203) is unaffected and was not
  re-run, since nothing it covers changed.
- **Python** (`tools/garmin-connector`): untouched, not re-run (nothing it covers changed).

## Known issues found and fixed during this step

- My first draft of the new PowerShell scripts' doc comments referenced the literal legacy path
  `C:\running-ai\watchface-relay` for context. `Test-RunningAI.ps1`'s existing "scripts contain no
  hard-coded user/drive paths or credentials" check (which scans every `.ps1` under
  `scripts/windows`) correctly flags any literal `C:\running-ai` - this was caught before
  committing anything, and the comments were reworded to describe the legacy instance without the
  literal path (pointing at `tools/external-relay/README.md` instead, where the path does appear,
  outside the scanned directory).
- My first unit-test assertion for `extractIntervalMetric('6 X 800M')` assumed case-insensitive
  matching; the real regex has no `/i` flag, so this actually returns `null`. Corrected the test
  to characterize the real (slightly surprising, but existing and unchanged) behavior rather than
  the behavior I expected.

## Contract-preservation evidence (summary)

- Byte-identical `server.js`/`workout-types.js` (SHA-256 match above); `generate-token.js`/
  `config.json` diffed empty.
- 14/14 new tests pass against the relocated copy, proving `/health`, `/today-workout` auth
  (correct/missing/wrong-scheme/wrong-token), the mocked `HAS_PLAN` response shape, 404s, and
  rate limiting all behave identically to the original.
- Zero Spring endpoint created or referenced anywhere in `tools/external-relay`.
- Zero secret values (the real watch token, the real Intervals.icu API key) appear in any file
  written this step - confirmed by `git status`/`git diff` review and by this document itself
  only ever describing them as "the real token" / "the real API key", never a value.

## Still undecided / explicitly deferred (do not start on these without further instruction)

1. **External transport**: ipTIME AX2004M WireGuard VPN + free valid HTTPS (feasibility not yet
   tested) vs. Cloudflare Named Tunnel (prerequisite check already done: no `cert.pem` on this
   machine, so `cloudflared tunnel login` has never been run here - the user will do that step
   personally if/when Cloudflare is chosen). Per the addendum, if WireGuard+HTTPS cannot be done
   for free, fall back to Cloudflare; paid infrastructure is out of scope for either path, and
   hitting a point where it would be required stops the work for a report rather than a
   unilateral decision.
2. **Fixed hostname / domain** - not asked for yet; still pending.
3. Cloudflare Access (Service Token) configuration, Named Tunnel creation, DNS route - all on
   hold, not started.
4. Relay bind address and any Cloudflare-specific request header handling - unchanged
   (`127.0.0.1` only, exactly as today), not finalized for whichever transport is eventually
   chosen.
5. Cutting the live process over from `C:\running-ai\watchface-relay` to `tools/external-relay`
   (requires copying the real `secrets/watch-token.json` so the already-installed watch build
   keeps working without a rebuild - a file operation, not something to be done by printing the
   token anywhere) - deferred until the transport is settled, so the live relay is only moved
   once, not twice.
6. Wiring `start-external-relay.ps1`/`stop-external-relay.ps1`/`status-external-relay.ps1` into
   `start-running-ai.ps1`/`stop-running-ai.ps1`/`status-running-ai.ps1`/
   `RunningAI.Watchdog.ps1`/a scheduled task - deferred for the same reason.
7. Terminating the 3 duplicate ad hoc quick-tunnel `cloudflared.exe` processes and the original
   `node.exe` process - deferred to the actual cutover.
8. The full acceptance sequence (local E2E, real LTE/5G + Garmin 265, PC reboot test) - not
   started; none of its prerequisites (hostname, auth, live cutover) exist yet.

## Commits

Not yet committed (pending your instruction, as with every other phase this session).

## Push

Not done.
