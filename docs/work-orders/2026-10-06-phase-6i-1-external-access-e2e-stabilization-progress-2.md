# Phase 6I-1 — External Access E2E Stabilization — Progress note 2

Status: **IN PROGRESS, not READY.** Covers two addenda since progress note 1: the Cloudflare
Named Tunnel setup script (2026-10-07/08, commit `19776a2`) and the transport pivot to Tailscale
Funnel (2026-10-09, this commit). Do not read this as `PHASE_6I_1_EXTERNAL_ACCESS_E2E_READY` -
no live Tailscale login, Funnel enable, external hostname, or acceptance test has been run yet.

## Part A — Cloudflare Named Tunnel setup script (2026-10-07/08)

Implemented exactly per the work order's requirements: `scripts/windows/external/`
`RunningAI.CloudflaredSetup.ps1` (pure/parsing helpers: hostname validation, `.env` round trip,
`cloudflared tunnel list`/`create` output parsing, "already exists" detection, config.yml
generation with the fixed `127.0.0.1:17845` origin and a mandatory catch-all `http_status:404`
last rule, and an overwrite-protection marker comment) and
`setup-running-ai-external-access.ps1` (the orchestrator: install check -> login check (runs
`cloudflared tunnel login` in the foreground only if not already authenticated) -> hostname
resolve/save to `.env` -> Named Tunnel create-or-reuse -> DNS route create-or-reuse -> config.yml
write (refuses to touch a pre-existing config.yml it did not write) -> Windows Service
install/ensure (requires an elevated shell) -> external relay health ensure -> external HTTPS
verification). `-DryRun` was live-verified on this machine (real cloudflared install present) to
make zero file/service/Cloudflare-API changes while still reporting accurate "would do X" steps.
`Test-CloudflaredSetup.ps1` (26 checks) covers every pure function plus a live `-DryRun`
zero-change proof. Never reached the real `cloudflared tunnel login` step - the user chose to
pivot transport before running it for real.

## Part B — Transport pivot: Cloudflare Named Tunnel -> Tailscale Funnel (2026-10-09)

### Why

Per your addendum: no domain purchase, zero ongoing cost, Tailscale Funnel's stable `*.ts.net`
HTTPS hostname, no ipTIME WireGuard/port-forwarding, admin work done directly on the Main PC.

### What was preserved exactly as before (nothing in this list changed)

- `tools/external-relay` (the relocated relay, its contract, its Node regression tests - 14/14,
  re-run and confirmed in this commit).
- `/today-workout`'s Bearer-token auth scheme.
- `scripts/windows/external/{start,stop,status}-external-relay.ps1` (the relay's own managed
  lifecycle - untouched).
- The legacy `C:\running-ai\watchface-relay` process and its 3 ad hoc Cloudflare quick-tunnel
  processes - still running, still untouched; per item 19 of the addendum they stay up until a
  real Tailscale Funnel E2E run succeeds, exactly the same "do not disrupt a possibly-still-live
  path" principle every prior transport decision in this phase has followed.
- All prior PowerShell/Node regression (109 baseline + 26 Cloudflare checks going into this step).

### Refactor: two helpers were transport-specific only by accident

`ConvertTo-RunningAiHostname`, `Get-RunningAiExternalHostnameFromEnvFile`, and
`Set-RunningAiExternalHostnameInEnvFile` (the `RUNNING_AI_EXTERNAL_BASE_URL` `.env` round trip)
and a new `Test-RunningAiExternalAccess` (the health/auth/unsafe-path HTTPS verification) moved
from `RunningAI.CloudflaredSetup.ps1` into `RunningAI.ExternalRelay.Common.ps1` - they were never
actually about Cloudflare, and Tailscale's setup needs the exact same logic. Both orchestrators
now share one implementation instead of two copies that could silently drift apart.
`Test-CloudflaredSetup.ps1` was updated to dot-source the new location; all of its existing
assertions are unchanged and still pass (26/26). This is the only change made to the
already-committed Cloudflare files beyond adding a clearly marked deprecation banner.

### Cloudflare files marked deferred, not deleted

`RunningAI.CloudflaredSetup.ps1` and `setup-running-ai-external-access.ps1` both gained a
`DEFERRED 2026-10-09` banner at the top of their comment-based help, pointing at the Tailscale
files as the current transport and explicitly stating they are kept as a historical/fallback
reference. `progress-1.md` got a one-line forward-pointer added at its top (its original content
is otherwise untouched - it was an accurate record of its own date). Nothing was removed from
Git; `git log` still shows the full Cloudflare implementation history.

### New: Tailscale Funnel setup

`scripts/windows/external/RunningAI.TailscaleSetup.ps1` (pure/parsing helpers):

- `ConvertTo-RunningAiTailscaleVersion` / `Test-RunningAiTailscaleVersionSupported` - parses
  `tailscale version`'s first line (tolerating a `-t<commit>` suffix) and compares against the
  documented Funnel minimum, v1.38.3.
- `Test-RunningAiTailscaleLoggedIn` - reads `BackendState` from `tailscale status --json`
  (`"Running"` = logged in and connected; anything else needs `tailscale up`).
- `Test-RunningAiTailscaleMagicDnsEnabled` - reads `CurrentTailnet.MagicDNSEnabled`; returns
  `$null` (not a false negative) when the field cannot be found at all, so the orchestrator can
  tell "confirmed off" apart from "could not verify."
- `Find-RunningAiTailscaleSelfDnsName` - the device's own `Self.DNSName`, trailing dot stripped -
  the fallback hostname source.
- `Find-RunningAiFunnelHostname` - parses the `https://<name>.ts.net` URL Tailscale's own CLI
  prints, from either the enable command's stdout or `tailscale funnel status`.
- `Test-RunningAiFunnelAlreadyServingTarget` - string-contains check against `funnel status`
  output, so a re-run does not try to re-enable an already-configured target.

`scripts/windows/external/setup-running-ai-tailscale-funnel.ps1` (the orchestrator), in order:

1. Tailscale installed? If not: `winget` found -> attempts
   `winget install --id Tailscale.Tailscale -e --accept-package-agreements --accept-source-agreements`
   (package ID and current version 1.104.1 confirmed live via `winget search tailscale` on this
   machine); `winget` not found -> prints the official download link and stops. Either way, any
   install failure stops with the manual-install link rather than retrying blindly.
2. Version check (>= 1.38.3) - stops with an upgrade command if too old.
3. Login check (`BackendState`). If not logged in: prints the exact command and what the operator
   will see (`tailscale up`, the `https://login.tailscale.com/a/...` URL, "approve this device"),
   then runs it in the foreground so the real browser flow happens in the operator's own session -
   **this step was never reached in this session**; Tailscale is not installed on this machine yet
   (confirmed: no binary, no service, `winget list` shows nothing installed).
4. MagicDNS check - stops with the exact admin-console link
   (`https://login.tailscale.com/admin/dns`) only when the field is confirmed `false`; an
   unconfirmable result is a warning, not a hard stop (the live Funnel-enable attempt is the
   authoritative check either way).
5. Ensures the external relay is healthy via the existing `start-external-relay.ps1` (byte-for-
   byte the same call the Cloudflare script makes - no new relay-management code).
6. Funnel target is always built from `Get-ExternalRelayConfiguredPort()` (`tools/external-relay/
   config.json`, currently 17845) - Spring (8080) and the Garmin connector (8765) are never
   referenced anywhere in this file (asserted by a dedicated regression check).
7. Checks `tailscale funnel status` for our exact target first (idempotent skip); otherwise runs
   `tailscale funnel --bg http://127.0.0.1:<port>` - the exact syntax you specified, confirmed
   against Tailscale's own current docs (`tailscale.com/kb/1223/funnel`,
   `/kb/1242/tailscale-serve`): the `-bg`/`--bg` flag makes the config persistent and auto-resuming
   after a restart, and a full `http://host:port` target (not just a bare port) is an accepted
   target form. HTTPS-enablement and the Funnel tailnet-policy attribute grant - the two
   prerequisites with no reliable CLI pre-check I could find documented - are **not** guessed at;
   if either is actually missing, this live command fails and its own diagnostic text is printed
   verbatim, with an instruction to address what it says and re-run.
8. Resolves the `*.ts.net` hostname (enable-command output -> `funnel status` -> `Self.DNSName`,
   in that order) and saves it to `.env` as `RUNNING_AI_EXTERNAL_BASE_URL` - the same key the
   Cloudflare script used, so nothing downstream needs to know which transport is live.
9. Reboot persistence: dynamically discovers whatever Windows Service matches `*Tailscale*`
   (never a hard-coded guessed service name), reports its status/start type, and sets it to
   `Automatic` if it is not already - the minimal startup integration the work order asked for
   (item 18), consistent with Tailscale's own documented behavior that a `-bg` Funnel
   configuration resumes automatically once `tailscaled` is running again, which this script does
   not attempt to reimplement itself.
10. Verification via the shared `Test-RunningAiExternalAccess` (`/health` -> UP/RELAY_DOWN/
    TUNNEL_DOWN/UNKNOWN, `/today-workout` with no credential -> expects 401, an unlisted path ->
    expects 404) - identical logic and classification vocabulary to the Cloudflare script, now
    shared rather than duplicated.
11. Final summary explicitly documents (item 17): Funnel has no Cloudflare-Access equivalent, so
    the relay's own Bearer token is now the sole auth boundary for the public endpoint, not one
    layer among several; the legacy Cloudflare quick tunnel is untouched; Phase 6I-1 is still not
    READY.

### Live inventory before implementing (per "do not guess and duplicate")

Confirmed on this machine before writing any code: **Tailscale is not installed** - no
`tailscale.exe` on PATH or in either Program Files location, no `Tailscale` Windows Service,
`winget list --id tailscale.tailscale` finds nothing installed. `winget search tailscale` does
find the real package (`Tailscale.Tailscale`, v1.104.1) to install from, confirming the auto-
install path is plausible rather than guessed.

### Secrets

Nothing in this step ever had a Tailscale auth key, node key, or API token to handle - Tailscale's
own `tailscale up`/`tailscale status`/`tailscale funnel` commands manage all of that internally
and this script never reads any Tailscale state file directly; it only parses their stdout/JSON
for the fields listed above (BackendState, MagicDNSEnabled, DNSName, the public `.ts.net` URL -
none of these are secrets). Confirmed by a full grep of every new/changed file for API-key/
password/token/private-key patterns: clean.

## Regression

- **PowerShell**: `Test-RunningAI.ps1` 39/39, `Test-CoachOperator.ps1` 25/25, `Test-Watchdog.ps1`
  36/36, `Test-ExternalRelay.ps1` 9/9, `Test-CloudflaredSetup.ps1` 26/26 (unchanged after the
  hostname-helper refactor), `Test-TailscaleSetup.ps1` 24/24 (new). **Total 159/159.**
- **Node**: `tools/external-relay/test/*.test.js` **14/14**, unchanged and re-confirmed.
- **Server-side (Java/Kotlin)**: zero files under `server/` touched (confirmed via `git status`
  both for this step and Part A) - the 1203/1203 baseline is unaffected and was not re-run.
- **-DryRun zero-change proof**: both `setup-running-ai-external-access.ps1 -DryRun` (Part A,
  real cloudflared install present) and `setup-running-ai-tailscale-funnel.ps1 -DryRun` (Part B,
  Tailscale not installed - exercises the "would winget install" branch) were run live on this
  machine; neither created `.env`, neither touched any service, both printed
  `files changed: 0` / `services changed: 0` / `Cloudflare writes: 0` or `Tailscale writes: 0`.

## Still undecided / explicitly deferred

Same list as progress note 1's items 2-8, now read as "Tailscale Funnel" wherever it said
"Cloudflare Named Tunnel", plus:

1. The real `tailscale up` login and `tailscale funnel --bg ...` enable have not been run - both
   require your own browser interaction and are the explicit stop point for this step, per your
   instruction.
2. Whether MagicDNS and the Funnel tailnet-policy attribute are already granted on your tailnet is
   unknown until the live run reaches those checks.
3. The full acceptance sequence (local E2E, real LTE/5G + Garmin 265, PC reboot test) - not
   started.

## Commits

Two since progress note 1: `19776a2` (Part A, Cloudflare setup script - already pushed) and this
commit (Part B, the Tailscale pivot + Cloudflare deprecation marking + shared-helper refactor).

## Push

Pending your instruction, as with every other phase this session.
