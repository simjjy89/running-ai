# external-relay

Small, standalone, read-only HTTP service that answers one question for the
RUNNER ARCADE watch face: "what is today's scheduled run?" It queries
Intervals.icu server-side (never the new Spring backend) so the Intervals.icu
API key never has to leave this machine or be embedded in the watch build.

## Origin and migration status (Phase 6I-1)

This is a relocation of the pre-existing `C:\running-ai\watchface-relay`
(`server.js`, `workout-types.js`, `generate-token.js`, `config.json`), moved
into this repo per Phase 6I-1's work order (§27) so it is tracked, tested, and
documented under this project's normal discipline instead of living outside
version control. **`server.js` and `workout-types.js` here are byte-for-byte
identical to the `C:\running-ai\watchface-relay` originals** (verified by
SHA-256 at migration time) - this move changes *where the code lives and how
it's tested*, not what it does. The already-installed watch face
(`C:\running-ai-watchface\source\ArcadeRelay.mc`) keeps working against the
same wire contract:

- `GET /health` -> `{"status":"ok"}`, no auth.
- `GET /today-workout` -> `{"date","status":"HAS_PLAN"|"REST"|"NO_PLAN","label"}`,
  Bearer-token gated.
- Data source: Intervals.icu directly (`INTERVALS_ICU_API_KEY` env var) plus
  the legacy `C:\running-ai\state\today_plan.json` explicit-REST override.
  **Not** wired to the Spring backend (port 8080) - that was an explicit,
  considered decision for this phase (keep the already-working watch
  contract unchanged), not an oversight.

**This location is not yet the live one.** As of this migration step, the
production process (node + 3 ad hoc `cloudflared tunnel --url
http://127.0.0.1:17845` quick-tunnel processes) is still running from the
original `C:\running-ai\watchface-relay` directory, serving the real watch
face traffic, using the real production token and the real
`today_plan.json`. It has not been stopped or touched. Cutting the live
process over to this location, and the external transport in front of it
(Cloudflare Named Tunnel vs. an ipTIME WireGuard VPN path - under feasibility
review as of 2026-10-06), are both separate, not-yet-decided steps. See
`docs/work-orders/2026-10-06-phase-6i-1-external-access-e2e-stabilization-*.md`.

## Run it

Manual (same as before the move):

```
node server.js
```

Needs, in the same directory: `config.json` (already present, not secret),
`secrets/watch-token.json` (run `node generate-token.js` once to create it -
this directory does not and must not ship one), and write access to `logs/`.
Binds `127.0.0.1` only - never put this directly on the internet; a tunnel or
VPN must front it.

## Rotate the watch token

```
node generate-token.js
```

Writes a new token to `secrets/watch-token.json` (never printed to stdout,
never committed - `secrets*` and `*.token` are repo-wide gitignored). This
token is embedded in the watch build (`ArcadeRelay.mc`'s `WATCH_TOKEN`) -
after rotating, patch the new value in, rebuild, reinstall.

## Tests

```
node --test tools/external-relay/test/*.test.js
```

Pure Node (`node:test`), no npm dependencies. `workout-types.test.js` is a
direct unit test of the pure label-formatting functions. `server.test.js`
spawns disposable, isolated instances of this directory's own `server.js` on
test-only ports with freshly generated disposable tokens and
`WATCHFACE_RELAY_MOCK=1` (the server's own existing escape hatch) - it never
contacts the real production process, its real token, or the real
`today_plan.json`. Not covered (would require either touching the real
`C:\running-ai\state\today_plan.json` or mocking the Intervals.icu network
call, both out of scope for a same-behavior migration): the real REST-decision
and real Intervals-event-lookup code paths inside `resolveToday`.

## Files

- `server.js` - the HTTP server (port from `config.json`, default 17845).
- `workout-types.js` - turns an Intervals.icu event's `name`/`moving_time`
  into the short label the watch face's pixel font can draw.
- `generate-token.js` - writes a new `secrets/watch-token.json`.
- `config.json` - port, cache TTL, rate limit. Not secret.
- `secrets/watch-token.json` (gitignored, not present in this repo) - the one
  credential this build embeds.
- `logs/relay.log` (gitignored) - request log, secrets redacted.
- `test/` - regression tests, see above.
