# mcp-poc

TEST-ONLY MCP server for Phase 7C-1: proves the ChatGPT ↔ RunningAI connection path with
**fake data only**. It is NOT the future read-only RunningAI MCP (that is Phase 7C-2, in
Spring); this process exists purely to verify transport + auth end to end before any real
data is wired up.

What it is:

- Dependency-free Node (same discipline as `tools/external-relay`), binds **127.0.0.1 only**
  (hard-coded), default port 17901 (`RUNNING_AI_MCP_POC_PORT`).
- MCP Streamable HTTP, stateless, `POST /mcp` only: `initialize`, `ping`, `tools/list`,
  `tools/call`; `GET /health` for liveness.
- Exactly one tool: `ping_running_ai` → fixed
  `{"success":true,"service":"RunningAI MCP PoC","mode":"TEST_ONLY","message":"RunningAI MCP connection verified"}`.
- **No database, no Garmin, no Intervals.icu, no Spring proxying** — the file contains no DB
  driver and no outbound HTTP call at all. `publish_workout` (and any unknown tool) is
  rejected server-side with a JSON-RPC error, not merely unlisted.
- Optional bearer auth: set `RUNNING_AI_MCP_POC_TOKEN` and every `/mcp` request must carry
  `Authorization: Bearer <token>` (timing-safe compare; token is never logged).

Run:

```
node server.js
```

Test (23 protocol/safety checks, spawns the server itself):

```
node test/poc-protocol-test.js
```

Intended external leg (Phase 7C-1 STEP 1 decision: **Secure MCP Tunnel**, outbound-only —
no Tailscale Funnel change, no inbound port, no public endpoint):

```
# one-time, after creating a tunnel in OpenAI Platform settings (returns a tunnel_id):
set CONTROL_PLANE_API_KEY=sk-...   # never commit; never echo
tunnel-client init --profile runningai-poc --tunnel-id <tunnel_id> ^
  --mcp-server-url http://127.0.0.1:17901/mcp
tunnel-client doctor --profile runningai-poc --explain
tunnel-client run --profile runningai-poc
```

Then in ChatGPT: Plugins → + → Add custom MCP server → Connection: **Tunnel** → pick the
tunnel → create → install → ask it to call `ping_running_ai`.

Retirement: this directory is deleted (or left unused) once Phase 7C-2's real read-only MCP
replaces it. Nothing else depends on it.
