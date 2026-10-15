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
- Data source at the time of the move: Intervals.icu directly
  (`INTERVALS_ICU_API_KEY` env var) plus the legacy
  `C:\running-ai\state\today_plan.json` explicit-REST override. **Not**
  wired to the Spring backend - that was an explicit, considered decision
  for that phase (keep the already-working watch contract unchanged), not
  an oversight. Phase 8B changed it; see below. The wire contract above
  did not change.

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

## Data sources (Phase 8B)

The wire contract is unchanged. What changed is where the answer comes from:

1. **RunningAI's approved draft** - `GET /api/v1/today-workout` on the Spring
   backend (`spring_base_url` in `config.json`, default
   `http://127.0.0.1:18080`). First, because it is the only source that holds
   the athlete's actual decision.
2. **The legacy `C:\running-ai\state\today_plan.json`** explicit-REST
   override. Read, never written; it is not this repo's file.
3. **Intervals.icu** calendar events.

The two older sources answer narrower questions, which is why neither could
carry the watch on its own. Intervals.icu says what was *published*, so an
approved-but-unpublished plan is invisible to it - and a REST day, which is
never published by design, is indistinguishable there from a day nobody has
planned yet. That gap is the reason for this change.

They are kept as fallbacks rather than removed: the watch must keep working
while the backend is down, mid-deploy, or simply not running on this machine.
A RunningAI failure is logged and stepped over, never turned into an error the
watch sees, and the backend is asked exactly once with no retry.

Two answers from the backend are deliberately discarded rather than shown: one
whose `date` is not the date asked for, and one the backend itself flags
`stale`. Showing another day's plan as today's is worse than showing nothing.
A `NO_PLAN` answer is not a decision - it means RunningAI has decided nothing
yet - so the older sources still get their turn after it.

Set `"spring_base_url": ""` to disable the RunningAI source entirely and
restore the exact pre-8B behaviour.

The relay only ever issues `GET` to the backend, over loopback. The endpoint
answers with an approved draft's structure (type, duration, repetitions) and
carries no coach-written text, so no free text crosses into the watch build.

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
