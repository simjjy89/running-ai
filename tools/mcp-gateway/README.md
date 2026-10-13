# mcp-gateway

OAuth 2.1 **resource-server** gateway in front of the RunningAI read-only MCP (Phase 7C-2b).
It is not an authorization server: Auth0 (or any compliant AS) issues tokens; this process only
verifies them and forwards allowlisted read-only MCP traffic to ONE fixed local target.

Guarantees (see `server.js` header for the full list):

- binds **127.0.0.1 only**; the public leg is a separate tunnel (Quick Tunnel for the PoC,
  Funnel `/mcp` path for the eventual fixed address — both separate approvals).
- **fail closed**: bad config refuses to start; missing/invalid/expired/mis-scoped/mis-audienced/
  foreign-`sub` tokens, disallowed algs, unknown kids and JWKS outages all end before the backend
  is touched (401/403/503). Verification is `jose` (maintained library — no hand-rolled crypto):
  RS256 via remote JWKS discovered from the issuer's metadata.
- serves RFC 9728 protected-resource metadata (+ the path-appended form) and RFC 6750
  `WWW-Authenticate ... resource_metadata="..."` challenges, which is how ChatGPT discovers the AS.
- method allowlist (initialize/ping/tools/list/tools/call) and tool allowlist (the 7C-2a read
  tools by default; `publish_workout` can never be allowlisted — startup refuses). `tools/list`
  responses are re-filtered to the allowlist as defense in depth. No generic proxying: the target
  URL is fixed at startup and must be loopback.
- **per-tool scope policy** (Phase 7F-1), hard-coded in `server.js` and not configurable — which
  scope a tool demands is a security property of that tool, not an operator knob:

  | scope | tools |
  |---|---|
  | `runningai:read` (base) | the seven read tools; also initialize / ping / tools/list |
  | `runningai:train` | `create_workout_draft`, `get_workout_draft`, `revise_workout_draft`, `approve_workout_draft`, `preview_draft_publish` |
  | `runningai:publish` | `publish_approved_draft` |

  Scopes are **not hierarchical**: `runningai:publish` does not imply `runningai:train`, and no
  amount of `runningai:read` reaches either. A missing scope is a 403 challenge naming it, raised
  before the backend is touched, and `tools/list` is filtered to the allowlist **intersected with
  the token's granted scopes**, so a read-only connection cannot even see a tool it could not call.
  A tool with no entry requires the base scope. The default allowlist is unchanged, so a gateway
  started with no new configuration behaves exactly as it did before 7F-1.
- no token, Authorization header or body ever reaches a log line.

Configuration (env):

| var | meaning |
|---|---|
| `RUNNING_AI_GATEWAY_PORT` | listen port (default 17910) |
| `RUNNING_AI_GATEWAY_TARGET_URL` | local MCP endpoint (default `http://127.0.0.1:17902/mcp`; must be loopback) |
| `RUNNING_AI_OAUTH_ISSUER` | AS issuer URL (Auth0 tenant, with trailing `/`) — required |
| `RUNNING_AI_OAUTH_AUDIENCE` | the API identifier / resource the token must be minted for — required |
| `RUNNING_AI_ALLOWED_SUB` | the ONE permitted subject — required |
| `RUNNING_AI_PUBLIC_BASE_URL` | public base URL (tunnel origin) for metadata/challenges — required |
| `RUNNING_AI_REQUIRED_SCOPE` | the BASE scope every token must carry; default `runningai:read` |
| `RUNNING_AI_ALLOWED_TOOLS` | comma list of tools this gateway will pass at all; default = the seven 7C-2a read tools. Adding a 7F-1 lifecycle tool here does not weaken its scope requirement. |

Run / test:

```
npm install
npm start
npm test     # 46 checks: mock AS + minted JWTs + hit-counting fake backend, incl. the 7F-1 scope matrix
```

With a temporary public address, the AS's API identifier and `RUNNING_AI_OAUTH_AUDIENCE` /
`RUNNING_AI_PUBLIC_BASE_URL` must match that address and the ChatGPT connector must be
re-registered whenever it changes — one reason the fixed Funnel `/mcp` address (7C-2c) exists.

**Phase 7D-2 — the authorization server is `tools/mcp-worker`, not Auth0.** Nothing here changed:
the Worker publishes RFC 8414 metadata and a JWKS at its own `/internal/` issuer and mints the
RS256 tokens this gateway already knew how to verify, so the switch is four environment values
(issuer / audience / allowed sub / public base URL). Because the Worker's `*.workers.dev` address
is fixed, a restarted tunnel no longer means re-registering the ChatGPT connector. See
`tools/mcp-worker/README.md`.
