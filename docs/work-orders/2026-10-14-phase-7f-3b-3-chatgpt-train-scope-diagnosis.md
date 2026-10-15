# Phase 7F-3B-3 — ChatGPT train-scope selection: diagnosis, and the one fact nobody had measured

Date: 2026-10-14. Branch `main-3`. Base commit `36448fe`.
**Nothing was deployed.** No Worker deploy, no connector removal/reinstall, no production DB change,
no Intervals write. One local source change (Worker logging) + this record.

## Outcome in one line

Every server-side surface already advertises `runningai:train` correctly, and ChatGPT re-authorized
**89 minutes after** that became true and still got a read-only grant. So this is **not** a RunningAI
advertisement bug — it is a client-side scope-selection behaviour, and the exact shape of it was
**unobservable** until this phase, because the Worker never recorded what the client asked for.

## STEP 2 — the OAuth scope path, measured live against production

All probes below were run read-only against `https://running-ai-mcp.simjjy89.workers.dev`
on 2026-10-14 ~04:25 UTC. No token, code or key is reproduced here.

| Surface | Measured value | Verdict |
|---|---|---|
| `GET /.well-known/oauth-authorization-server` → `scopes_supported` | `["runningai:read","runningai:train","offline_access"]` | train advertised |
| `GET /.well-known/oauth-protected-resource/mcp` → `scopes_supported` | `["runningai:read","runningai:train"]` | train advertised |
| `POST /mcp` with no token → `WWW-Authenticate` | `Bearer realm="OAuth", resource_metadata="…/.well-known/oauth-protected-resource/mcp", scope="runningai:read runningai:train"` | train advertised in the challenge |
| `client_id` document `https://chatgpt.com/oauth/client.json` | HTTP 200, CIMD, **no `scope` field at all** | the client does not pin scopes in its metadata |

Secondary observations worth keeping:

- The root `/.well-known/oauth-protected-resource` (no `/mcp` suffix) is **404**, and so is
  `/.well-known/openid-configuration`. That is RFC 9728-correct — the challenge points at the
  suffixed URL — but it means a client that only probes the unsuffixed path learns nothing.
- `publish_workout` / `runningai:publish` were re-confirmed unreachable: the Worker's
  `SCOPES_SUPPORTED` cannot mint the scope, and the gateway's `TOOL_SCOPES` is hard-coded.

## The measurement that had never been taken: the actual granted scope

Read-only from the OAuth KV namespace (`wrangler kv key get`, nothing written):

- Exactly **one** grant exists: `clientId = https://chatgpt.com/oauth/client.json` (CIMD),
  `redirectUri = https://chatgpt.com/connector_platform_oauth_redirect`.
- `createdAt = 2026-10-14T04:21:20Z`.
- **`scope = ["runningai:read"]`** — and nothing else.

### Correction to the project record

Several documents state the stored grant carries `['runningai:read','offline_access']` — e.g.
`2026-10-14-phase-7f-3b-2b-production-train-mcp-activation.md:169` and the follow-up note. **That was
never measured.** Every one of those lines traces back to the Worker's own `SCOPES_SUPPORTED`
constant, which happened to have that value before 7F-3B-2B. The first real read of a grant is the
one above, and it shows **no `offline_access`**. The historical result documents are left as written
(they record what was believed at the time); this file is the correction.

This matters, because the earlier belief supported a theory — "ChatGPT mirrors the advertised scope
list" — that the measurement does not support.

### The ordering fact that rules out a server-side cause

| Time (UTC) | Event |
|---|---|
| 2026-10-14 **02:52:56** | Worker version `2438d9a0` deployed — train advertised in AS metadata, PRM and the 401 challenge |
| 2026-10-14 **04:21:20** | ChatGPT's current grant created — scope `["runningai:read"]` |

ChatGPT re-authorized **1 h 28 min after** every server surface began advertising
`runningai:train`, and still did not obtain it. Widening the advertisement further cannot fix this.

## STEP 1 / STEP 5.1 — the confirmed cause, and the limit of what is observable

The Worker grants `requested INTERSECT SCOPES_SUPPORTED`, then force-adds the base read scope
(`src/index.js`, `finishGithubSignIn`). A stored grant of exactly `["runningai:read"]` is therefore
produced by **two different client behaviours that are indistinguishable after the fact**:

1. **ChatGPT sent `scope=runningai:read`** — a stale value cached at connector-creation time, when
   the PRM and the challenge genuinely did advertise only the read scope. Fixed by making the client
   re-discover (remove + re-add the connector).
2. **ChatGPT sent no `scope` parameter at all** — the library's `parseAuthRequest` turns a missing
   `scope` into `[]` with no default (verified in
   `node_modules/@cloudflare/workers-oauth-provider/dist/oauth-provider.js`), so the whole
   `["runningai:read"]` is **our own fallback**, not a client request. Re-adding the connector would
   then change nothing, and the fix is option **B**.

Nothing currently stored can separate the two: the grant records only the outcome, and the authorize
transaction in KV is **AES-GCM sealed** (correct design — it was inspected and is not readable).
The Worker logged `authorize: redirecting to GitHub sign-in` and nothing about scope.

So the honest STEP 5.1 answer is: **the cause is confirmed to be client-side scope selection; which
of the two client behaviours it is has not yet been observed, and could not have been.**

## STEP 4 — the minimal local change (made, tested, NOT deployed)

File: **`tools/mcp-worker/src/index.js`** — the only file changed.

- New pure helper `describeRequestedScopes(scope)`: renders recognised scope names only; anything
  unrecognised is **counted, never echoed**, so a client cannot write arbitrary text into the log.
- `startGithubSignIn` now logs `authorize: client sent NO scope parameter` **or**
  `authorize: client requested scope [...]` — this single line separates case 1 from case 2.
- The existing approval line now also states what was granted:
  `sign-in approved for the permitted owner; granted scope [...]`.

Nothing else moved: no scope is added, dropped or reinterpreted, and the grant computation is
byte-for-byte unchanged. This is observability, not a policy change.

### Test results

| Suite | Result |
|---|---|
| `tools/mcp-worker` chain (`npm test`) | **34/34** |
| `tools/mcp-worker` OAuth e2e (`npm run test:oauth`) | **49/49** |
| `tools/mcp-gateway` (`npm test`) | **46/46** |

The e2e suite already pins the STEP 4 requirements and still passes unchanged:
*asking for read only grants read only — never train*; *train is granted only when it is explicitly
requested*; *asking for `runningai:publish` does NOT grant it*; *an unknown scope is dropped, and the
base scope is still granted*. The suite's **"secrets must not appear in any log"** block also still
passes, which is the check that matters for a change that adds client-influenced text to the log.

No draft was created, and no Intervals write was attempted.

## STEP 3 / STEP 5.3 — the smallest fix, in order

**Step 1 (do this first).** Deploy nothing; instead *observe*. Either run `npx wrangler tail` against
`running-ai-mcp` while re-connecting (the tail event carries the `/authorize` query string), or
deploy the one-line change above and re-connect. One re-connect then prints which case is true.

**Step 2a — if the log says `client requested scope [runningai:read]` (case 1).** The connector is
replaying a cached scope. Minimal fix = **remove and re-add the ChatGPT connector**, which forces
fresh discovery of the PRM that now advertises train. **No code change at all.** This is option A.

**Step 2b — if the log says `client sent NO scope parameter` (case 2).** Re-adding cannot help, and
option A is impossible. Minimal fix = option **B**: a **second protected resource** (e.g.
`/mcp/train`) whose PRM requires `runningai:read runningai:train`, added as a separate "RunningAI
Training" connector. The user's deliberate act of adding that connector is the explicit consent; the
existing read-only resource keeps its read-only default, so no existing grant is widened. The
library supports multiple resource servers (`resourceServers`, `withRequiredScopes`), so this stays
inside `tools/mcp-worker/src/index.js` plus its two test suites.

**Not adopted, as instructed:** auto-adding train to every grant, treating read as train, bypassing
the gateway check, enabling publish alongside, or widening the existing read-only connection.

## STEP 5.2 — does the tool list need refreshing?

The gateway recomputes `tools/list` **per request**, filtering to
`ALLOWED_TOOLS INTERSECT scopes the presented token actually carries` (`tools/mcp-gateway/server.js`,
`proxy()` + `scopeFor`). There is **no server-side tool-list cache anywhere in this stack**: the
moment a token carries `runningai:train`, the same connector's `tools/list` returns 12 instead of 7,
with no redeploy and no re-registration.

Whether ChatGPT's own UI re-reads that list, or pins what it saw at install time, is a property of
the ChatGPT client that **cannot be verified from this repository** — and per the instruction it is
not assumed either way. What is certain is that nothing on the RunningAI side has to be refreshed.

## STEP 5.5 / 5.6 — impact and what the operator must do

- **Existing read connection:** unaffected. The local change does not touch scope computation, and
  the live read leg was already verified in 7F-3B-2B. Until a Worker deploy happens, production
  behaviour is byte-identical to now.
- **Production change required:** none for diagnosis if `wrangler tail` is used; one Worker deploy if
  the log line is preferred. Neither touches Spring, the gateway, the DB or Intervals.
- **User re-approval required:** yes, in both branches — a new grant can only be created by the user
  re-authorizing. In branch 2a that is remove + re-add; in branch 2b it is adding a second connector.
  Neither is performed in this phase.
- `runningai:publish` stays un-issuable in every branch above.

## Standing facts added by this phase

1. The first **measured** grant scope in this project's history is `["runningai:read"]` — the
   `offline_access` in older documents was a constant, not an observation.
2. ChatGPT's CIMD document declares **no `scope`**, so the client chooses at runtime.
3. The authorize transaction in KV is encrypted, so **the Worker log is the only possible witness**
   to what a client requested. That is why the log line exists.

---

# Phase 7F-3B-3 / tasks 6-8 — production Worker deploy, regression, log observation

Executed 2026-10-14 (KST evening) under the owner's final deployment approval. Scope of the
change: the two diagnostic log lines described above, nothing else. No Spring change, no gateway
change, no DB write, no Intervals call, no commit, no push.

## 6. Cloudflare Worker deploy

| Fact | Value |
| --- | --- |
| Worker name | `running-ai-mcp` (production, the only environment; no `--env`) |
| Changed file | `tools/mcp-worker/src/index.js` only (`describeRequestedScopes()` + 2 `log()` calls) |
| Config/vars/secrets | untouched — `wrangler.jsonc` not modified, no `secret put` |
| Previous live version | `2438d9a0-5250-4f33-a9a9-9199c569dbce` (2026-10-14T02:52:56Z) |
| Rollback | available: `npx wrangler rollback 2438d9a0-5250-4f33-a9a9-9199c569dbce` |
| Pre-deploy tests | `npm run test:all` → **49/49**, including the 4 scope-issuance checks and the 5 "no secret in any log" checks, which now also cover the new lines |
| New version | `68aa9faf-6b2f-4109-b7de-a46a5af7442c` |
| Deployed at | 2026-10-14T10:54:06Z (19:54 KST) |

## 7. Production regression

| # | Check | Result |
| --- | --- | --- |
| 1 | `GET /health` | **PASS** — 200, `{"status":"ok","service":"running-ai-mcp-worker"}` |
| 2 | PRM `/.well-known/oauth-protected-resource/mcp` | **PASS** — 200, `scopes_supported:["runningai:read","runningai:train"]`, `resource`/`authorization_servers` unchanged |
| 3 | Read connection + read-only tools | **PARTIAL** — see below |
| 4 | Scope computation / issuance policy | **PASS** — unchanged code path; AS metadata still `["runningai:read","runningai:train","offline_access"]`; `POST /mcp` unauthenticated still challenges `scope="runningai:read runningai:train"`; 49/49 incl. "asking for read only grants read only", "train only when explicitly requested" |
| 5 | `runningai:publish` un-issuable | **PASS** — absent from `SCOPES_SUPPORTED`, absent from both metadata documents, gateway allowlist still lacks `publish_approved_draft` |
| 6 | Runtime errors / sensitive log exposure | **PASS** — `wrangler tail` shows only request lines and one expected `401 invalid_token` warning for the deliberately forged token; no token, header or body content |

Worker auth layer re-verified after the deploy: `POST /mcp` with no token → 401 with the correct
`WWW-Authenticate`; with a forged bearer → 401; `/register` → 405 on GET (endpoint present).

### Why item 3 is PARTIAL, and why it is not a deploy regression

The machine rebooted at **2026-10-14 14:35 KST**. Spring production (18080) came back via
`RunningAI-Startup`, but the MCP gateway and `cloudflared` do not start automatically **by design**
(`tools/mcp-worker/README.md`, "After a reboot"). At the start of this task both were down:

- no `cloudflared` process, and `vault-courtesy-receivers-approximate.trycloudflare.com`
  **no longer resolves** (a Quick Tunnel hostname dies with its process);
- `RunningAI-McpGateway` task state `Ready`, last run 11:46 KST, `LastTaskResult 1073807364`
  (0x40010004 = terminated at shutdown), nothing listening on 17910.

This predates the deploy and is unaffected by it, so no rollback was performed. What was verified
instead, after starting the documented production launcher `RunningAI-McpGateway`
(no code/config/permission change, no write of any kind):

- gateway live again on `127.0.0.1:17910` (PID 20700); `POST /mcp` without a token → 401 with the
  Worker's `resource_metadata` challenge; forged bearer → 401; non-`/mcp` path → 200 health only;
- production Spring `/mcp` `tools/list` → **13 tools** (read 7 + train 5 + `publish_approved_draft`,
  the last one not allowlisted at the gateway);
- production Spring read tool `get_athlete_profile` → real answer (LTHR 176, threshold pace 290 s/km).

So Worker, gateway and Spring are each verified; the only missing segment is the public tunnel,
which cannot be restored without the one step this instruction forbids: a new Quick Tunnel gets a
new hostname, which means editing `ORIGIN_MCP_URL` in `wrangler.jsonc` and redeploying. That needs
the owner's approval and is **not** done here.

## 8. OAuth diagnostic log observation

`npx wrangler tail --format pretty` is running against the production Worker and collecting
(`Connected to running-ai-mcp`, tail valid until 2026-10-14T16:54:24Z; re-running the command
renews it). It already captured live traffic, confirming collection works.

What a re-authorization will print, both from `describeRequestedScopes()` / the sign-in line:

- `authorize: client requested scope [...]` **or** `authorize: client sent NO scope parameter` —
  the fact that KV cannot answer, because the authorize transaction is encrypted at rest;
- `sign-in approved for the permitted owner; granted scope [...]`.

Unrecognised scope names are counted, never echoed. No authorization code, token, cookie or user
identifier is logged; the existing `log()` redaction still applies. No connection was disconnected
and no re-authentication was forced from this side.

---

# Phase 7F-3B-3 / STEP 1-5 — MCP tunnel restored, Worker re-pointed, path re-verified

Executed 2026-10-14 (KST evening) under the owner's approval for **one** Quick Tunnel restart and
**one** Worker deploy. No Spring change, no gateway change, no DB write, no Intervals call, no
commit, no push. The OAuth scope-computation code and the per-tool scope policy are byte-identical
to the previous section.

## STEP 1 — pre-flight

| Fact | Value |
| --- | --- |
| Live Worker version at start | `68aa9faf-6b2f-4109-b7de-a46a5af7442c` (matches the record above) |
| Gateway | `127.0.0.1:17910` LISTEN, PID 20700, running from `C:\running-ai-github\.runtime\mcp-gateway\server.js` (off the dev worktree, per 7F-3B-2A) |
| Gateway `/health` | 200 `{"status":"ok","service":"running-ai-mcp-gateway"}` |
| Gateway unauthenticated `POST /mcp` | 401 + `WWW-Authenticate … resource_metadata=…` |
| Production Spring | `127.0.0.1:18080` LISTEN, PID 16120; `tools/list` = **13** tools; `get_athlete_profile` → real answer (LTHR 176, threshold pace 290 s/km) |
| `cloudflared` | **no process**; `vault-courtesy-receivers-approximate.trycloudflare.com` does not resolve (a Quick Tunnel hostname dies with its process) |
| Working tree | only `tools/mcp-worker/src/index.js` (the 2 diagnostic log lines, +17/−1) — unchanged, still uncommitted |
| Backup | `wrangler.jsonc` and `src/index.js` copied to the session scratchpad before any edit |

## STEP 2 — Quick Tunnel restored

`cloudflared tunnel --url http://127.0.0.1:17910` (launched detached, log to the scratchpad; the
log contains no token or credential).

| # | Check | Result |
| --- | --- | --- |
| 1 | New HTTPS address issued | **PASS** — `https://comedy-awarded-trucks-constraints.trycloudflare.com` |
| 2 | DNS resolves | **PASS** — A records returned |
| 3 | Reaches the gateway | **PASS** — `GET /health` through the tunnel → 200, gateway's own body |
| 4 | Unauthenticated request still blocked | **PASS** — `POST /mcp` with no token → 401, challenge intact |
| 5 | Gateway auth/scope check not bypassed | **PASS** — forged bearer → 401; the gateway log records `auth rejected: no bearer token` and `auth rejected: ERR_JOSE_ALG_NOT_ALLOWED` at 11:09:37Z, i.e. **the tunnel really carried the traffic to the gateway and the gateway judged it** |
| 6 | Process persistent | **PASS** — PID 18220 alive, QUIC connection registered (`icn06`) |

## STEP 3 — Worker setting and single deploy

| Fact | Value |
| --- | --- |
| Changed | `wrangler.jsonc` → `ORIGIN_MCP_URL` only, **1 line**, `https://…/mcp` protocol+path preserved |
| Everything else | untouched — no secret, no var, no binding, no `src/index.js` change (repo diff is exactly these 2 files) |
| Scope logic | unchanged: `SCOPES_SUPPORTED = [read, train, offline_access]`, `FORWARDABLE_SCOPES = [read, train]`, gateway `TOOL_SCOPES` hard-coded, `publish_approved_draft` still **not** allowlisted |
| Pre-deploy tests | `npm run test:all` → **49/49**, incl. the 4 scope-issuance checks and the 5 "no secret in any log" checks |
| Previous version | `68aa9faf-6b2f-4109-b7de-a46a5af7442c` (rollback: `npx wrangler rollback 68aa9faf-…`) |
| **New version** | **`0d75fa82-eb1a-4538-9c63-06246d852968`** |

No unexpected setting or code change was found, so the deploy proceeded exactly once.

## STEP 4 — full-path verification

| # | Check | Result |
| --- | --- | --- |
| 1 | Worker `/health` 200 | **PASS** |
| 2 | PRM 200 | **PASS** — `scopes_supported:["runningai:read","runningai:train"]`, resource/AS unchanged |
| 3 | Worker → Tunnel | **NOT OBSERVABLE WITHOUT A TOKEN** — see below |
| 4 | Tunnel → Gateway | **PASS** — proven by the gateway's own log entries for requests sent to the new hostname |
| 5 | Gateway → Spring | **PASS** — gateway targets `http://127.0.0.1:18080/mcp`; Spring answers 13 tools and a real `get_athlete_profile` |
| 6 | `tools/list` through the Worker with existing auth | **PENDING — cannot be performed from here** |
| 7 | Read tool `get_athlete_profile` through the Worker | **PENDING — cannot be performed from here** |
| 8 | No-token / forged-token blocked | **PASS** — at the Worker 401 (challenge advertises `runningai:read runningai:train`), at the tunnel 401, at the gateway 401 |
| 9 | `runningai:publish` un-issuable | **PASS** — absent from `SCOPES_SUPPORTED`, absent from AS metadata and PRM, and `publish_approved_draft` absent from the gateway allowlist (`RUNNING_AI_ALLOWED_TOOLS` lists 12 tools) |
| 10 | Worker runtime errors | **PASS** — `wrangler tail` shows only `Ok` request lines; no exception, no token/header/body content |

### Why 6 and 7 are PENDING rather than FAILED

They are not a measured failure — they are **unmeasurable from this side**, and that was true before
the deploy as well:

- The Worker fetches `ORIGIN_MCP_URL` only inside `apiHandler`, which `OAuthProvider` reaches **after**
  it has validated an access token. An unauthenticated probe therefore never exercises the
  Worker → Tunnel leg at all; there is no code path that does.
- A production-valid access token exists only inside the ChatGPT connector. The OAuth KV holds
  exactly **one** grant (`clientId = https://chatgpt.com/oauth/client.json`, expiry 1794543688) and
  **no live token key**; access tokens are stored as hashes in any case, so none can be read back.
- Minting one locally is impossible: the gateway trusts only hop tokens signed by the production
  `HOP_SIGNING_JWK` with the production `iss`/`aud`, and that key is a Worker secret.

So the one remaining segment is Cloudflare's outbound fetch to a public hostname that has been
proven, from this machine over the public internet, to resolve, terminate TLS, and deliver traffic
to the gateway. Per the instruction, nothing further was changed and no second deploy was made.

## STEP 5 — OAuth diagnostic log armed

`npx wrangler tail --format pretty` is running against the production Worker, output to the session
scratchpad. `Connected to running-ai-mcp`, tail valid until **2026-10-14T16:54:24Z** (re-running the
command renews it); live `/health` traffic was captured, so collection is confirmed working.

On the next authorization it will print, from the already-deployed diagnostic lines:

- `authorize: client requested scope [...]` **or** `authorize: client sent NO scope parameter`
- `sign-in approved for the permitted owner; granted scope [...]`

Unrecognised scope names are counted, never echoed; no code, token, cookie or user identifier is
logged. **No OAuth negotiation outcome is claimed here** — the reconnect has not happened yet.

## STEP 5 result — the diagnostic fired: **case 1 confirmed**

Three authorizations reached the production Worker while the tail was collecting
(11:24:29Z, 11:25:11Z, 11:32:41-46Z; two callbacks at 11:32 were `Canceled`, the third completed).
Every one of them printed the same line:

```
INFO authorize: client requested scope [runningai:read]
INFO sign-in approved for the permitted owner; granted scope [runningai:read]
```

and the raw request confirms it independently — the query string carries
`scope=runningai%3Aread`, an explicit single-scope request.

**So it is case 1, not case 2: ChatGPT DOES send a `scope` parameter, and it sends read only.**
The read-only grant is therefore the client's request, not the Worker's fallback. Option B (a second
`/mcp/train` protected resource) is not yet required; option A's premise holds.

### But a plain reconnect does not re-discover — measured, not assumed

Across the whole tail window (from 11:12Z) there is **not one** request to `POST /mcp` and **not one**
request to any `/.well-known/...` document, while three authorizations completed. A client adding a
connector for the first time must probe `/mcp` to receive the 401 challenge and then read the PRM;
none of that happened. The connector replayed a `scope` value it already held.

KV before/after is consistent with a disconnect-then-reconnect rather than a fresh install: the old
grant `925-A4eliB1bo2aQ` is gone and a new grant `GpE-rybrgvk4rC58` exists (+ one access token,
expiring 2026-10-14T12:32:55Z; grant valid to 2026-11-13T11:32:54Z).

**Consequence:** option A only works if the connector is **deleted outright and added again from
scratch**, forcing fresh discovery. "Disconnect / sign in again" on the existing connector is proven
insufficient — it was just performed three times and the requested scope never changed. If a true
delete-and-re-add also yields `scope=runningai:read`, then the scope is pinned on OpenAI's side and
option B becomes the remaining fix.

### STEP 4 items 6-7 still PENDING

A valid production token now exists, but **no MCP traffic followed it**: no `POST /mcp` at the
Worker, and the gateway log has nothing after 11:09:37Z (those entries are this phase's own forged
-token probes). The authenticated read through the Worker therefore has not yet been exercised — one
real tool call from the ChatGPT chat would close items 6 and 7.

No change was made in response to this observation: no deploy, no code edit, no connector action.

## STEP 4 items 6-7 — closed by a real ChatGPT call at 12:08Z

The owner ran `get_recent_activities(days=14, limit=5)` from ChatGPT (connector
`RunningAI_OAuth_Smoke`, account Primary). Observation only — no deploy, no code edit, no connector
change, no permission change was made in this verification.

### Hop-by-hop evidence, correlated

| Time (UTC) | Where | Evidence |
| --- | --- | --- |
| 12:08:08.272 | Worker | `authorize: client requested scope [runningai:read]` (4th occurrence) |
| 12:08:12.628 | Worker | `sign-in approved for the permitted owner; granted scope [runningai:read]` |
| 12:08:14 | Worker | `POST /oauth/token` Ok → new grant `R7CGWPRNz7zee3UI` (token valid to 13:08:16Z) |
| 12:08:22 | Worker | `POST /mcp` → `mcp forwarded status=200` |
| 12:08:22.966 | **Gateway** | `initialize authorized` |
| 12:08:23 | Worker | `GET /internal/jwks.json` — the gateway fetching the Worker's JWKS to verify the hop token |
| 12:08:23.701 | **Gateway** | `tools/call get_recent_activities authorized (scope runningai:read)` |
| 12:08:23/24 | Worker | second `POST /mcp` → `mcp forwarded status=200` |

The Worker's own `mcp forwarded status=200` is the proof of the previously unobservable
**Worker → Tunnel** leg, and the gateway's two lines are the matching **Tunnel → Gateway** arrivals,
timestamped between the Worker's receipt and its 200.

### The returned data is production, byte-for-byte

A direct read of production Spring (`127.0.0.1:18080`, same arguments) returns exactly what ChatGPT
reported: `totalInWindow:3`, latest `2026-10-11`, `distanceMeters:21240.0` (21.24 km),
`durationSeconds:6723` (1:52:03), `analysisAvailable:true`, `intervalsMatched:true`. So the chain
terminated at the production instance, not at a dev or synthetic one.

### Verdict

| # | Check | Result |
| --- | --- | --- |
| 3 | Worker → Tunnel | **PASS** (was NOT OBSERVABLE; now directly logged) |
| 4 | Tunnel → Gateway | **PASS** |
| 5 | Gateway → Spring | **PASS**, production data confirmed |
| 6 | `tools/list` through the Worker | **not exercised in this window** — the two MCP calls were `initialize` and `tools/call`; the client used a cached tool list. `initialize` traversed the identical authenticated chain and was authorized |
| 7 | Read tool through the Worker | **PASS** |

By the instruction's own criterion ("기존 인증을 이용한 Worker 경유 Read 도구 호출이 성공해야 전체
경로 PASS"), the **full path is PASS**. External writes: 0 — the token carries `runningai:read`, and
the gateway's allowlist has no `publish_approved_draft`.

### Two incidental facts worth keeping

1. At 12:05:14Z a bare `POST /oauth/token` (no preceding `/authorize`) returned
   `400 invalid_grant - Grant not found`. Each new authorization **replaces** the stored grant, so
   every earlier refresh token is invalidated — which is why repeated reconnects force a fresh
   sign-in rather than refreshing silently.
2. Across the entire tail window (11:12Z → 12:08Z) there is still **no** `/.well-known/*` request and
   **no** unauthenticated `/mcp` probe, through four authorizations. The connector never
   re-discovered. Option A therefore remains **untested**: the owner reports being unable to find a
   full-delete option in the mobile UI, so delete-and-re-add has not been performed.

## RESOLVED — Option A worked: the re-registered connector requested and received `runningai:train`

Observation only: no code change, no deploy, no permission change, no connector action from this
side, no commit.

### The decisive log pair (2026-10-14T15:03Z)

```
15:03:08.187Z INFO authorize: client requested scope [runningai:read runningai:train]
15:03:13.159Z INFO sign-in approved for the permitted owner; granted scope [runningai:read runningai:train]
```

Confirmed independently at rest: the stored grant `_lV22cIOhYjcsbxu`
(`clientId = https://chatgpt.com/oauth/client.json`, created 15:03:11Z) carries
**`scope = ['runningai:read', 'runningai:train']`**.

### What made this authorization different — the re-discovery finally happened

Immediately before it, traffic that had been **absent from every previous reconnect** appeared:

| Time (UTC) | Request |
| --- | --- |
| 15:02:41, 15:02:44, 15:03:02 | `POST /mcp` (unauthenticated probe → 401 challenge) |
| each time | `GET /.well-known/oauth-protected-resource/mcp` |
| each time | `GET /.well-known/oauth-authorization-server` |
| each time | `GET /.well-known/openid-configuration` (404, as before) |

That is a first-time connector install reading the challenge and the PRM, exactly the sequence that
never occurred during the four disconnect/reconnect cycles of 11:24-13:29Z — all of which requested
`[runningai:read]`. The earlier conclusion stands and is now proven in both directions:
**a reconnect replays the cached scope; only a full delete-and-re-add re-discovers it.**

### Case judgement, evidence-based

| Hypothesis | Verdict |
| --- | --- |
| **Case 1** — client replays a scope cached at install time | **CONFIRMED and resolved.** Six authorizations requested `[runningai:read]`; after re-registration the same client requested `[runningai:read runningai:train]` against unchanged server code |
| **Case 2** — client sends no `scope` parameter | **REFUTED.** `scope=` was present in every single `/authorize` request |
| Server advertises train incorrectly | **REFUTED.** AS metadata, PRM and the 401 challenge advertised train throughout, before and after |
| Worker/gateway policy strips or downgrades train | **REFUTED.** The Worker granted exactly what was requested, both times; `FORWARDABLE_SCOPES` and the gateway's hard-coded `TOOL_SCOPES` were never touched in this phase |

### Cross-validation of the just-completed read call (16:18Z)

| Time (UTC) | Where | Evidence |
| --- | --- | --- |
| 16:18:28 | Worker | `POST /oauth/token` Ok (refresh — no new sign-in needed) |
| 16:18:31 | Worker | `POST /mcp` |
| 16:18:32.217 | **Gateway** | `tools/call get_athlete_profile authorized (scope runningai:read)` |
| 16:18:32 | Worker | `GET /internal/jwks.json` (gateway verifying the hop token) |
| 16:18:32.757 | Worker | `mcp forwarded status=200` |

The gateway's `(scope runningai:read)` is the scope **the tool requires** (`server.js:311` logs
`needed`), not the token's scope — `get_athlete_profile` is a read tool, so this is expected and is
not evidence that train was lost. The grant above is the authority on what the token carries.

Also notable: `tools/list authorized` at 15:03:25, 15:04:20 and 15:05:18 was served to a
train-carrying token, so the gateway's filter now returns **12** tools (7 read + 5 train) instead of
7. `publish_approved_draft` stays unreachable — not in the gateway allowlist, and `runningai:publish`
remains un-issuable at the Worker.

### Minimal remaining step

One read-only train-scope call from ChatGPT — **`get_workout_draft`** — would prove the per-tool
scope check actually admits `runningai:train` end to end. It reads a stored draft: no AI-coach call,
no DB write, no Intervals write. Everything needed for it is already live; no deploy, no code change
and no further authorization is required.
