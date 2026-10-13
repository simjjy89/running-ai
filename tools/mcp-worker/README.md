# mcp-worker

Cloudflare Worker front door for the RunningAI **read-only** MCP (Phase 7D-2). It replaces Auth0
with a Cloudflare-hosted OAuth 2.1 authorization server and gives ChatGPT one **stable HTTPS
address** that never changes when the local tunnel restarts.

```
ChatGPT ──OAuth 2.1 (PKCE S256, CIMD/DCR)──► Worker  https://running-ai-mcp.<sub>.workers.dev/mcp
                                              │  authorization server: GitHub sign-in, ONE user,
                                              │  opaque access tokens in Workers KV
                                              │  resource server: scope + subject checks
                                              ▼  mints a 90-second RS256 JWT (its own key)
                                   cloudflared Quick Tunnel (public, ephemeral)
                                              ▼
                        tools/mcp-gateway  127.0.0.1:17910   ← UNCHANGED from Phase 7C-2b
                        verifies signature/iss/aud/exp/nbf/alg/kid/scope/sub via the Worker's JWKS,
                        enforces the MCP method + read-tool allowlist, filters tools/list
                                              ▼
                             dev Spring 127.0.0.1:18081 /mcp  (copy DB, all writes off)
```

## Why there are two token systems

`@cloudflare/workers-oauth-provider` issues **opaque** access tokens — literally
`${userId}:${grantId}:${secret}`, validated by hashing them and looking the hash up in the
`OAUTH_KV` namespace (`dist/oauth-provider.js:4883`, `:4489`). They are not JWTs, nothing signs
them, and there is no JWKS and no RFC 7662 introspection endpoint; the library's own
`docs/resource-servers.md` states that a resource server off Cloudflare cannot validate them.

The existing gateway is a resource server off Cloudflare. So the Worker does not try to make the
gateway swallow a token it cannot verify, and the gateway is **not** taught to trust an unverified
header. Instead the Worker becomes a second, explicit issuer: after the library has verified the
user's token, the Worker signs a short-lived RS256 JWT carrying the identity it just authenticated,
and publishes the matching JWKS. The gateway keeps doing exactly what it already did, with **zero
code changes** — only environment values move from Auth0's to the Worker's.

`src/internal-jwt.js` holds that trust boundary; `test/chain-test.js` proves it against the real
gateway process (28/28, no Cloudflare account needed).

## What is checked, and where

| check | Worker | gateway |
|---|---|---|
| OAuth access token valid & unexpired | library, KV-backed | — |
| GitHub identity is the one permitted owner | at sign-in **and** per request (`auth.userId` + `props.githubId`) | `sub` allowlist (third check) |
| `runningai:read` scope present | yes | yes |
| hop-token signature / issuer / audience / exp / nbf / RS256 / known kid | mints it | **verifies it** |
| MCP method allowlist, read-tool allowlist, `tools/list` filtering, `publish_workout` refusal, `server/discover` → JSON-RPC −32601 | deliberately not duplicated | **sole authority** (Phase 7C-2b/7C-3, 29/29) |

The Worker proxies nothing but `POST /mcp` to one fixed origin URL, so it can never act as a
general REST proxy, and `/api/...` and `/actuator/...` are routes at neither hop.

## Deploy (one-time, ~20 minutes)

Everything below is free: Workers free plan (100k req/day), Workers KV free tier, a GitHub OAuth
app, and `cloudflared` Quick Tunnel. No domain, no card, no Auth0.

**1. Signing key**

```bash
cd tools/mcp-worker
npm install
npm run keygen          # prints kid + public JWK to stderr, PRIVATE JWK to stdout
```

Keep the stdout line for step 5. Never commit it, never paste it into a work order.

**2. GitHub OAuth app** — <https://github.com/settings/developers> → *New OAuth App*

| field | value |
|---|---|
| Application name | `RunningAI MCP` |
| Homepage URL | `https://running-ai-mcp.<your-subdomain>.workers.dev` |
| Authorization callback URL | `https://running-ai-mcp.<your-subdomain>.workers.dev/callback` |

Generate a client secret. Your numeric GitHub id: `curl -s https://api.github.com/users/<login> | grep '"id"'`.

**3. KV namespace**

```bash
npx wrangler login
npx wrangler kv namespace create OAUTH_KV      # paste the printed id into wrangler.jsonc
```

**4. Fill in `wrangler.jsonc`** — `kv_namespaces[0].id`, `PUBLIC_BASE_URL`, `ALLOWED_GITHUB_ID`,
`ALLOWED_GITHUB_LOGIN`. Leave `ORIGIN_MCP_URL` until the tunnel is up (step 7).

**5. Secrets** (never vars, never the config file)

```bash
npx wrangler secret put HOP_SIGNING_JWK        # the stdout line from step 1
npx wrangler secret put GITHUB_CLIENT_ID
npx wrangler secret put GITHUB_CLIENT_SECRET
npx wrangler deploy
```

The first deploy prints the real `*.workers.dev` address. If it differs from what you guessed,
correct `PUBLIC_BASE_URL` (and the GitHub app's callback URL) and deploy again.

**6. Local side** — dev Spring on 18081 against the copy DB, then the gateway:

```powershell
$env:RUNNING_AI_GATEWAY_TARGET_URL = 'http://127.0.0.1:18081/mcp'
$env:RUNNING_AI_OAUTH_ISSUER       = 'https://running-ai-mcp.<sub>.workers.dev/internal/'   # trailing slash
$env:RUNNING_AI_OAUTH_AUDIENCE     = 'https://running-ai-mcp.<sub>.workers.dev/mcp'
$env:RUNNING_AI_ALLOWED_SUB        = 'github|<your numeric id>'
$env:RUNNING_AI_PUBLIC_BASE_URL    = 'https://running-ai-mcp.<sub>.workers.dev'
node tools/mcp-gateway/server.js
```

**7. Tunnel** — `cloudflared tunnel --url http://127.0.0.1:17910`, then put the printed
`https://<random>.trycloudflare.com/mcp` into `ORIGIN_MCP_URL` and `npx wrangler deploy`.

This is the **only** step that repeats when the tunnel restarts: one variable, one deploy, ~15
seconds. ChatGPT is never re-registered, because the Worker's address never changes.

**8. ChatGPT** — add a custom MCP connector with server URL
`https://running-ai-mcp.<sub>.workers.dev/mcp`, choose OAuth, sign in with GitHub.

## Verify before trusting it

```bash
npm run test:all                                 # 28/28 + 43/43, offline, no account
curl -s https://<worker>/health
curl -s https://<worker>/.well-known/oauth-authorization-server   # S256, CIMD, DCR, iss param
curl -i -X POST https://<worker>/mcp -d '{}'     # 401 + WWW-Authenticate ... resource_metadata=
curl -i -X POST https://<tunnel>.trycloudflare.com/mcp -d '{}'    # 401 — bypassing the Worker buys nothing
```

## Operating

- **Stop sharing:** `Ctrl-C` the `cloudflared` window. The Worker stays up and returns 502; no
  RunningAI data is reachable. Stopping the gateway or the dev Spring has the same effect.
- **Take it down entirely:** `npx wrangler delete` (or delete the Worker in the dashboard).
- **Revoke the connector without deleting anything:** rotate the hop key
  (`npm run keygen` → `wrangler secret put HOP_SIGNING_JWK` → `wrangler deploy`); every token the
  gateway sees from then on is signed by a key it can verify, and old ones are already expired.
- **Logs:** `npx wrangler tail` for the Worker (identities and statuses only — no tokens, headers
  or bodies), the gateway's stdout locally, dev Spring's log for the tools.
- **After a reboot:** dev Spring 18081 → gateway → `cloudflared` → update `ORIGIN_MCP_URL` →
  `wrangler deploy`. Nothing starts automatically, by design.

## Configuration

| name | kind | meaning |
|---|---|---|
| `PUBLIC_BASE_URL` | var | the Worker's own address, no trailing slash |
| `ORIGIN_MCP_URL` | var | public URL of the local gateway's `/mcp` (the tunnel) |
| `ALLOWED_GITHUB_ID` | var | numeric GitHub id of the one permitted user (the authority) |
| `ALLOWED_GITHUB_LOGIN` | var | that account's login, checked as well |
| `HOP_TOKEN_TTL_SECONDS` | var | hop-token lifetime, default 90 (max 600) |
| `GITHUB_OAUTH_BASE` / `GITHUB_API_BASE` | var | **test only** — point the sign-in at a stub. Leave UNSET in production (defaults are the real GitHub). An override must be https, or http on loopback |
| `HOP_SIGNING_JWK` | **secret** | private RSA JWK from `npm run keygen` |
| `GITHUB_CLIENT_ID` / `GITHUB_CLIENT_SECRET` | **secret** | the GitHub OAuth app |
| `OAUTH_KV` | KV binding | the library's grant/token store |

`compatibility_flags` must keep `global_fetch_strictly_public`, or the library disables CIMD and
only DCR registration works.

`PUBLIC_BASE_URL` must be **exactly** the hostname ChatGPT will use. The library derives the OAuth
issuer from the incoming request's origin, while `resource` comes from `PUBLIC_BASE_URL`; if the two
disagree (reaching the same Worker by another hostname, say) the audience binding no longer lines
up. One canonical address, everywhere.

## Tests

| command | what it covers |
|---|---|
| `npm test` | 28 checks: the minted hop token against the real, unmodified gateway process, plus every required rejection, with a hit-counting backend proving nothing unauthorized got through |
| `npm run test:oauth` | 43 checks: the whole OAuth flow under `wrangler dev` with a stub GitHub — DCR, PKCE S256, the `iss` parameter on success *and* error callbacks, refresh, the seven read tools over synthetic data, direct-to-gateway bypass attempts, and a log scan for leaked secrets |
| `npm run test:all` | both |

Neither needs a Cloudflare account, a network, or any real data. `test/fixtures/` holds the
test-only wrangler config; the signing key it uses is generated per run and deleted afterwards.
