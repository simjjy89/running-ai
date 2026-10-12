// Protocol/safety tests for tools/mcp-poc/server.js (Phase 7C-1). Dependency-free; Node >= 18.
// Spawns the real server twice (no-auth mode, then bearer-token mode) and asserts the work
// order's STEP 3/4 local checks. Exit code 0 = all pass.

'use strict';

const { spawn } = require('child_process');
const path = require('path');

const SERVER = path.join(__dirname, '..', 'server.js');
const PORT = 17999;
const BASE = `http://127.0.0.1:${PORT}`;
let failures = 0;
let checks = 0;

function assert(name, condition) {
  checks += 1;
  if (condition) {
    process.stdout.write(`  PASS ${name}\n`);
  } else {
    failures += 1;
    process.stdout.write(`  FAIL ${name}\n`);
  }
}

function startServer(env) {
  const child = spawn(process.execPath, [SERVER], {
    env: { ...process.env, RUNNING_AI_MCP_POC_PORT: String(PORT), ...env },
    stdio: ['ignore', 'pipe', 'inherit'],
  });
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => reject(new Error('server did not start in time')), 5000);
    child.stdout.on('data', (d) => {
      if (String(d).includes('MCP_POC_STARTED')) { clearTimeout(timer); resolve(child); }
    });
    child.on('exit', () => reject(new Error('server exited during startup')));
  });
}

async function rpc(payload, headers = {}) {
  const res = await fetch(`${BASE}/mcp`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', Accept: 'application/json', ...headers },
    body: JSON.stringify(payload),
  });
  let body = null;
  try { body = await res.json(); } catch (e) { /* 202 etc. */ }
  return { status: res.status, body };
}

async function noAuthSuite() {
  process.stdout.write('no-auth mode:\n');

  const init = await rpc({ jsonrpc: '2.0', id: 1, method: 'initialize',
    params: { protocolVersion: '2025-06-18', capabilities: {}, clientInfo: { name: 'test', version: '0' } } });
  assert('initialize returns the negotiated protocol version',
    init.status === 200 && init.body.result && init.body.result.protocolVersion === '2025-06-18');
  assert('initialize advertises only the tools capability',
    JSON.stringify(Object.keys(init.body.result.capabilities)) === JSON.stringify(['tools']));

  const older = await rpc({ jsonrpc: '2.0', id: 11, method: 'initialize',
    params: { protocolVersion: '2024-11-05' } });
  assert('an older supported protocol version is echoed back',
    older.body.result && older.body.result.protocolVersion === '2024-11-05');
  const unknownVer = await rpc({ jsonrpc: '2.0', id: 12, method: 'initialize',
    params: { protocolVersion: '1999-01-01' } });
  assert('an unknown protocol version falls back to the latest supported',
    unknownVer.body.result && unknownVer.body.result.protocolVersion === '2025-06-18');

  const notified = await fetch(`${BASE}/mcp`, { method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ jsonrpc: '2.0', method: 'notifications/initialized' }) });
  assert('notifications/initialized is acknowledged with 202', notified.status === 202);

  const list = await rpc({ jsonrpc: '2.0', id: 2, method: 'tools/list' });
  const tools = (list.body.result && list.body.result.tools) || [];
  assert('tools/list returns exactly one tool', tools.length === 1);
  assert('the only tool is ping_running_ai', tools[0] && tools[0].name === 'ping_running_ai');
  assert('no write tool (publish_workout) is listed', tools.every((t) => t.name !== 'publish_workout'));
  assert('readOnlyHint is true on the read-only tool',
    tools[0] && tools[0].annotations && tools[0].annotations.readOnlyHint === true);

  const call = await rpc({ jsonrpc: '2.0', id: 3, method: 'tools/call',
    params: { name: 'ping_running_ai', arguments: {} } });
  const sc = call.body.result && call.body.result.structuredContent;
  assert('ping_running_ai succeeds', call.status === 200 && call.body.result && call.body.result.isError === false);
  assert('ping result is the fixed TEST_ONLY payload',
    sc && sc.success === true && sc.mode === 'TEST_ONLY' && sc.service === 'RunningAI MCP PoC');
  assert('ping result leaks no token-like field',
    !JSON.stringify(call.body).match(/token|api[_-]?key|authorization/i));

  const forcedWrite = await rpc({ jsonrpc: '2.0', id: 4, method: 'tools/call',
    params: { name: 'publish_workout', arguments: { date: '2026-10-12' } } });
  assert('a forced publish_workout call is refused server-side with a JSON-RPC error',
    forcedWrite.body.error && forcedWrite.body.error.code === -32602 &&
    /write\/operational tool/.test(forcedWrite.body.error.message));

  const unknownTool = await rpc({ jsonrpc: '2.0', id: 5, method: 'tools/call', params: { name: 'exec_sql' } });
  assert('an unknown tool is refused', unknownTool.body.error && unknownTool.body.error.code === -32602);

  const unknownMethod = await rpc({ jsonrpc: '2.0', id: 6, method: 'resources/list' });
  assert('an unsupported MCP method is -32601', unknownMethod.body.error && unknownMethod.body.error.code === -32601);

  const batch = await rpc([{ jsonrpc: '2.0', id: 7, method: 'ping' }]);
  assert('a JSON-RPC batch is refused', batch.body.error && batch.body.error.code === -32600);

  const getMcp = await fetch(`${BASE}/mcp`);
  assert('GET /mcp is 405 (stateless, POST only)', getMcp.status === 405);

  const otherPath = await fetch(`${BASE}/api/v1/activities`, { method: 'POST', body: '{}' });
  assert('no REST path exists to proxy (404 on anything but /mcp)', otherPath.status === 404);

  const health = await fetch(`${BASE}/health`);
  const healthBody = await health.json();
  assert('GET /health is ok and labelled TEST_ONLY', health.status === 200 && healthBody.mode === 'TEST_ONLY');
}

async function authSuite() {
  process.stdout.write('bearer-token mode:\n');

  const noToken = await rpc({ jsonrpc: '2.0', id: 1, method: 'tools/list' });
  assert('a request without a token is 401', noToken.status === 401);

  const badToken = await rpc({ jsonrpc: '2.0', id: 2, method: 'tools/list' },
    { Authorization: 'Bearer wrong-token-wrong-token' });
  assert('a request with a wrong token is 401', badToken.status === 401);

  const good = await rpc({ jsonrpc: '2.0', id: 3, method: 'tools/list' },
    { Authorization: 'Bearer poc-test-token-poc-test-token' });
  assert('a request with the right token succeeds', good.status === 200 && !!good.body.result);

  const health = await fetch(`${BASE}/health`);
  assert('health stays reachable without auth (parity with the relay contract)', health.status === 200);
}

(async () => {
  let child = await startServer({});
  try { await noAuthSuite(); } finally { child.kill(); }

  child = await startServer({ RUNNING_AI_MCP_POC_TOKEN: 'poc-test-token-poc-test-token' });
  try { await authSuite(); } finally { child.kill(); }

  process.stdout.write(`\n${checks - failures}/${checks} checks passed\n`);
  process.exit(failures === 0 ? 0 : 1);
})().catch((e) => {
  process.stdout.write(`FATAL ${e && e.message}\n`);
  process.exit(1);
});
