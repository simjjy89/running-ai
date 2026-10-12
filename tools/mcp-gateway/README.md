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
| `RUNNING_AI_REQUIRED_SCOPE` | default `runningai:read` |
| `RUNNING_AI_ALLOWED_TOOLS` | comma list; default = the seven 7C-2a read tools |

Run / test:

```
npm install
npm start
npm test     # 27 checks: mock AS + minted JWTs + hit-counting fake backend
```

With a temporary public address, Auth0's API identifier and `RUNNING_AI_OAUTH_AUDIENCE` /
`RUNNING_AI_PUBLIC_BASE_URL` must match that address and the ChatGPT connector must be
re-registered whenever it changes — one reason the fixed Funnel `/mcp` address (7C-2c) exists.
