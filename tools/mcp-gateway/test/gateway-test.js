// Phase 7C-2b STEP 5: automated verification of the gateway against a MOCK authorization server
// (local JWKS + locally minted tokens) and a fake MCP backend that counts every request it sees.
// The core assertion of the whole suite: EVERY unauthorized/forbidden request leaves the backend
// hit counter untouched. Node >= 20; run `npm install` first, then `npm test`.

import { spawn } from 'node:child_process';
import http from 'node:http';
import { fileURLToPath } from 'node:url';
import path from 'node:path';
import { exportJWK, generateKeyPair, SignJWT } from 'jose';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const GATEWAY = path.join(__dirname, '..', 'server.js');

const AS_PORT = 17921;
const BACKEND_PORT = 17922;
const GATEWAY_PORT = 17920;
const ISSUER = `http://127.0.0.1:${AS_PORT}/`;
const AUDIENCE = 'https://runningai-mcp.test/mcp';
const PUBLIC_BASE = 'https://runningai-mcp.test';
const SUB = 'auth0|owner-1';
const SCOPE = 'runningai:read';

let checks = 0; let failures = 0;
function assert(name, condition) {
  checks += 1;
  if (condition) process.stdout.write(`  PASS ${name}\n`);
  else { failures += 1; process.stdout.write(`  FAIL ${name}\n`); }
}

// ---- mock authorization server (JWKS + discovery) -----------------------------------------------

const goodKeys = await generateKeyPair('RS256');
const rogueKeys = await generateKeyPair('RS256'); // a signer the JWKS does NOT know
const goodJwk = { ...(await exportJWK(goodKeys.publicKey)), kid: 'good-kid', alg: 'RS256', use: 'sig' };

const asServer = http.createServer((req, res) => {
  const url = (req.url || '').split('?')[0];
  const send = (body) => { res.writeHead(200, { 'Content-Type': 'application/json' }); res.end(JSON.stringify(body)); };
  if (url === '/.well-known/openid-configuration') {
    return send({ issuer: ISSUER, jwks_uri: `${ISSUER}jwks.json` });
  }
  if (url === '/jwks.json') return send({ keys: [goodJwk] });
  res.writeHead(404); res.end();
});
await new Promise((r) => asServer.listen(AS_PORT, '127.0.0.1', r));

// ---- fake MCP backend, counting hits -------------------------------------------------------------

let backendHits = 0;
const backend = http.createServer((req, res) => {
  backendHits += 1;
  let body = '';
  req.on('data', (c) => { body += c; });
  req.on('end', () => {
    const msg = JSON.parse(body);
    const result = msg.method === 'tools/list'
      ? { tools: [
          { name: 'ping_running_ai', annotations: { readOnlyHint: true } },
          // deliberately misbehaving backend: lists a write tool, which the gateway must filter out
          { name: 'publish_workout', annotations: { readOnlyHint: false } },
          // the 7F-1 lifecycle tools: visible only to a token that actually holds their scope
          { name: 'create_workout_draft', annotations: { readOnlyHint: false } },
          { name: 'approve_workout_draft', annotations: { readOnlyHint: false } },
          { name: 'publish_approved_draft', annotations: { readOnlyHint: false } },
        ] }
      : { content: [{ type: 'text', text: '{"ok":true}' }], structuredContent: { ok: true, tool: msg.params && msg.params.name }, isError: false };
    res.writeHead(200, { 'Content-Type': 'application/json' });
    res.end(JSON.stringify({ jsonrpc: '2.0', id: msg.id ?? null, result }));
  });
});
await new Promise((r) => backend.listen(BACKEND_PORT, '127.0.0.1', r));

// ---- token minting -------------------------------------------------------------------------------

async function token(overrides = {}, { key = goodKeys.privateKey, kid = 'good-kid', alg = 'RS256' } = {}) {
  const now = Math.floor(Date.now() / 1000);
  const claims = { scope: SCOPE, ...overrides };
  let jwt = new SignJWT(claims)
    .setProtectedHeader({ alg, kid })
    .setIssuer(overrides.iss ?? ISSUER)
    .setAudience(overrides.aud ?? AUDIENCE)
    .setSubject(overrides.sub ?? SUB)
    .setIssuedAt(overrides.iat ?? now)
    .setExpirationTime(overrides.exp ?? now + 300);
  if (overrides.nbf !== undefined) jwt = jwt.setNotBefore(overrides.nbf);
  return jwt.sign(key);
}

// ---- gateway under test --------------------------------------------------------------------------

const gateway = spawn(process.execPath, [GATEWAY], {
  env: {
    ...process.env,
    RUNNING_AI_GATEWAY_PORT: String(GATEWAY_PORT),
    RUNNING_AI_GATEWAY_TARGET_URL: `http://127.0.0.1:${BACKEND_PORT}/mcp`,
    RUNNING_AI_OAUTH_ISSUER: ISSUER,
    RUNNING_AI_OAUTH_AUDIENCE: AUDIENCE,
    RUNNING_AI_ALLOWED_SUB: SUB,
    RUNNING_AI_PUBLIC_BASE_URL: PUBLIC_BASE,
    RUNNING_AI_ALLOWED_TOOLS:
      'ping_running_ai,create_workout_draft,approve_workout_draft,publish_approved_draft',
  },
  stdio: ['ignore', 'pipe', 'inherit'],
});
await new Promise((resolve, reject) => {
  const t = setTimeout(() => reject(new Error('gateway did not start')), 5000);
  gateway.stdout.on('data', (d) => { if (String(d).includes('GATEWAY_STARTED')) { clearTimeout(t); resolve(); } });
  gateway.on('exit', () => reject(new Error('gateway exited during startup')));
});

const BASE = `http://127.0.0.1:${GATEWAY_PORT}`;
async function mcp(payload, auth) {
  const res = await fetch(`${BASE}/mcp`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', ...(auth ? { Authorization: `Bearer ${auth}` } : {}) },
    body: JSON.stringify(payload),
  });
  let body = null; try { body = await res.json(); } catch { /* empty */ }
  return { status: res.status, body, www: res.headers.get('www-authenticate') };
}
const LIST = { jsonrpc: '2.0', id: 1, method: 'tools/list', params: {} };
const PING = { jsonrpc: '2.0', id: 2, method: 'tools/call', params: { name: 'ping_running_ai', arguments: {} } };

try {
  process.stdout.write('discovery (unauthenticated, metadata only):\n');
  const meta = await (await fetch(`${BASE}/.well-known/oauth-protected-resource`)).json();
  assert('protected-resource metadata names the resource, AS and scope',
    meta.resource === `${PUBLIC_BASE}/mcp` && meta.authorization_servers[0] === ISSUER &&
    meta.scopes_supported.includes(SCOPE));
  const metaMcp = await fetch(`${BASE}/.well-known/oauth-protected-resource/mcp`);
  assert('the path-appended metadata form is served too', metaMcp.status === 200);

  process.stdout.write('negative paths (backend must stay at zero hits):\n');
  const noTok = await mcp(LIST);
  assert('no token -> 401 with resource_metadata challenge',
    noTok.status === 401 && /resource_metadata=/.test(noTok.www || ''));
  assert('wrong signature -> 401', (await mcp(LIST, await token({}, { key: rogueKeys.privateKey, kid: 'good-kid' }))).status === 401);
  const good = await token();
  const tampered = good.slice(0, good.lastIndexOf('.') - 2) + 'xx' + good.slice(good.lastIndexOf('.'));
  assert('tampered JWT -> 401', (await mcp(LIST, tampered)).status === 401);
  assert('expired token -> 401', (await mcp(LIST, await token({ exp: Math.floor(Date.now() / 1000) - 60 }))).status === 401);
  assert('nbf in the future -> 401', (await mcp(LIST, await token({ nbf: Math.floor(Date.now() / 1000) + 600 }))).status === 401);
  assert('wrong issuer -> 401', (await mcp(LIST, await token({ iss: 'http://127.0.0.1:59999/' }))).status === 401);
  assert('wrong audience -> 401', (await mcp(LIST, await token({ aud: 'https://other-api.test' }))).status === 401);
  const noScope = await mcp(LIST, await token({ scope: 'openid profile' }));
  assert('missing read scope -> 403 insufficient_scope', noScope.status === 403 && /insufficient_scope/.test(noScope.www || ''));
  assert('foreign subject -> 403', (await mcp(LIST, await token({ sub: 'auth0|someone-else' }))).status === 403);
  assert('HS256 (disallowed alg) -> 401',
    (await mcp(LIST, await new SignJWT({ scope: SCOPE }).setProtectedHeader({ alg: 'HS256' })
      .setIssuer(ISSUER).setAudience(AUDIENCE).setSubject(SUB).setExpirationTime('5m')
      .sign(Buffer.from('a-32-byte-minimum-shared-secret!!')))).status === 401);
  assert('unknown kid -> 401', (await mcp(LIST, await token({}, { key: rogueKeys.privateKey, kid: 'rogue-kid' }))).status === 401);
  assert('backend saw ZERO of the above', backendHits === 0);

  const forced = await mcp({ jsonrpc: '2.0', id: 3, method: 'tools/call',
    params: { name: 'publish_workout', arguments: { date: '2026-10-13' } } }, await token());
  assert('authorized but forced publish_workout -> JSON-RPC -32602, not proxied',
    forced.status === 200 && forced.body.error && forced.body.error.code === -32602 && backendHits === 0);
  const unknownMethod = await mcp({ jsonrpc: '2.0', id: 4, method: 'resources/read', params: {} }, await token());
  assert('non-allowlisted MCP method -> -32601, not proxied',
    unknownMethod.body.error && unknownMethod.body.error.code === -32601 && backendHits === 0);
  // ChatGPT's real probe order (7C-2 smoke finding): server/discover arrives BEFORE initialize.
  // Unauthenticated it must trigger the OAuth challenge; authenticated it must get a clean
  // JSON-RPC -32601 (HTTP 200) and never a 5xx, or ChatGPT aborts app creation.
  const discoverNoAuth = await mcp({ jsonrpc: '2.0', id: 40, method: 'server/discover', params: {} });
  assert('unauthenticated server/discover -> 401 with resource_metadata (OAuth trigger)',
    discoverNoAuth.status === 401 && /resource_metadata=/.test(discoverNoAuth.www || ''));
  const discoverAuth = await mcp({ jsonrpc: '2.0', id: 41, method: 'server/discover', params: {} }, await token());
  assert('authenticated server/discover -> HTTP 200 JSON-RPC -32601, never 5xx, not proxied',
    discoverAuth.status === 200 && discoverAuth.body.error && discoverAuth.body.error.code === -32601 && backendHits === 0);
  assert('arbitrary path -> 404', (await fetch(`${BASE}/api/v1/activities`, { method: 'POST', body: '{}' })).status === 404);
  assert('GET /mcp -> 405', (await fetch(`${BASE}/mcp`)).status === 405);
  assert('still zero backend hits after path/method probing', backendHits === 0);

  process.stdout.write('authorized path:\n');
  const list = await mcp(LIST, await token());
  assert('authorized tools/list is proxied', list.status === 200 && Array.isArray(list.body.result.tools));
  assert('a write tool listed by a misbehaving backend is filtered out of tools/list',
    list.body.result.tools.every((t) => t.name !== 'publish_workout') &&
    list.body.result.tools.some((t) => t.name === 'ping_running_ai'));
  const ping = await mcp(PING, await token());
  assert('authorized allowlisted tools/call is proxied and answers',
    ping.status === 200 && ping.body.result && ping.body.result.structuredContent.ok === true);
  assert('exactly the two authorized requests reached the backend', backendHits === 2);

  // ---- Phase 7F-1: per-tool scope separation --------------------------------------------------
  // The property under test: a read-only connection cannot create, revise, approve or publish
  // training - it cannot even see those tools - and holding the publish scope is not implied by
  // holding the train scope. Every refusal must leave the backend untouched.
  process.stdout.write('per-tool scope separation (7F-1):\n');
  const READ = 'runningai:read';
  const TRAIN = `${READ} runningai:train`;
  const PUBLISH = `${TRAIN} runningai:publish`;
  const call = (name) => ({ jsonrpc: '2.0', id: 50, method: 'tools/call', params: { name, arguments: {} } });
  const namesFor = async (scope) =>
    (await mcp(LIST, await token({ scope }))).body.result.tools.map((t) => t.name);

  const readNames = await namesFor(READ);
  assert('read-only token sees NO training tool in tools/list',
    readNames.includes('ping_running_ai') &&
    !readNames.includes('create_workout_draft') &&
    !readNames.includes('approve_workout_draft') &&
    !readNames.includes('publish_approved_draft'));

  let before = backendHits;
  for (const tool of ['create_workout_draft', 'approve_workout_draft', 'publish_approved_draft']) {
    const refused = await mcp(call(tool), await token({ scope: READ }));
    assert(`read-only token calling ${tool} -> 403 insufficient_scope, not proxied`,
      refused.status === 403 && /insufficient_scope/.test(refused.www || '') && backendHits === before);
  }

  const trainNames = await namesFor(TRAIN);
  assert('train token sees the draft tools but NOT the publish tool',
    trainNames.includes('create_workout_draft') && trainNames.includes('approve_workout_draft') &&
    !trainNames.includes('publish_approved_draft'));

  before = backendHits;
  const created = await mcp(call('create_workout_draft'), await token({ scope: TRAIN }));
  assert('train token calling create_workout_draft is proxied',
    created.status === 200 && created.body.result && backendHits === before + 1);
  const publishDenied = await mcp(call('publish_approved_draft'), await token({ scope: TRAIN }));
  assert('scopes are NOT hierarchical: train alone cannot publish -> 403, not proxied',
    publishDenied.status === 403 && backendHits === before + 1);

  const publishNames = await namesFor(PUBLISH);
  assert('publish token sees all enabled tools', publishNames.includes('publish_approved_draft'));
  before = backendHits;
  const published = await mcp(call('publish_approved_draft'), await token({ scope: PUBLISH }));
  assert('publish token calling publish_approved_draft is proxied',
    published.status === 200 && published.body.result && backendHits === before + 1);

  before = backendHits;
  assert('the base scope is still required: a train-only token cannot even list -> 403',
    (await mcp(LIST, await token({ scope: 'runningai:train' }))).status === 403 && backendHits === before);
  const stillBanned = await mcp(call('publish_workout'), await token({ scope: PUBLISH }));
  assert('publish_workout stays unreachable even with every scope -> -32602, not proxied',
    stillBanned.status === 200 && stillBanned.body.error && stillBanned.body.error.code === -32602 &&
    backendHits === before);
  const unknownTool = await mcp(call('delete_everything'), await token({ scope: PUBLISH }));
  assert('an unknown tool is refused even with every scope -> -32602, not proxied',
    unknownTool.body.error && unknownTool.body.error.code === -32602 && backendHits === before);
  const foreignWithScopes = await mcp(call('create_workout_draft'),
    await token({ scope: PUBLISH, sub: 'auth0|someone-else' }));
  assert('a foreign subject is refused however many scopes the token carries -> 403, not proxied',
    foreignWithScopes.status === 403 && backendHits === before);

  const meta7f = await (await fetch(`${BASE}/.well-known/oauth-protected-resource`)).json();
  assert('metadata advertises exactly the scopes the enabled tools require',
    meta7f.scopes_supported.includes('runningai:read') &&
    meta7f.scopes_supported.includes('runningai:train') &&
    meta7f.scopes_supported.includes('runningai:publish'));

  // A gateway started with NO new configuration - production's current one - must be unchanged by
  // 7F-1: the same seven read tools, the same single advertised scope, the same resource name, and
  // no training tool reachable even by a token that carries every scope.
  process.stdout.write('backwards compatibility of an unchanged (read-only) gateway:\n');
  const legacyPort = GATEWAY_PORT + 7;
  const legacy = spawn(process.execPath, [GATEWAY], {
    env: { ...process.env,
      RUNNING_AI_GATEWAY_PORT: String(legacyPort),
      RUNNING_AI_GATEWAY_TARGET_URL: `http://127.0.0.1:${BACKEND_PORT}/mcp`,
      RUNNING_AI_OAUTH_ISSUER: ISSUER, RUNNING_AI_OAUTH_AUDIENCE: AUDIENCE,
      RUNNING_AI_ALLOWED_SUB: SUB, RUNNING_AI_PUBLIC_BASE_URL: PUBLIC_BASE },
    stdio: ['ignore', 'pipe', 'inherit'],
  });
  await new Promise((resolve, reject) => {
    const t = setTimeout(() => reject(new Error('legacy gateway did not start')), 5000);
    legacy.stdout.on('data', (d) => { if (String(d).includes('GATEWAY_STARTED')) { clearTimeout(t); resolve(); } });
  });
  try {
    const legacyBase = `http://127.0.0.1:${legacyPort}`;
    const legacyMeta = await (await fetch(`${legacyBase}/.well-known/oauth-protected-resource`)).json();
    assert('an unconfigured gateway still advertises exactly runningai:read',
      JSON.stringify(legacyMeta.scopes_supported) === JSON.stringify(['runningai:read']) &&
      legacyMeta.resource_name === 'RunningAI MCP (read-only)');
    const hitsBeforeLegacy = backendHits;
    const legacyCall = await fetch(`${legacyBase}/mcp`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${await token({ scope: PUBLISH })}` },
      body: JSON.stringify(call('create_workout_draft')),
    });
    const legacyBody = await legacyCall.json();
    assert('a training tool is unreachable on an unconfigured gateway even with every scope',
      legacyBody.error && legacyBody.error.code === -32602 && backendHits === hitsBeforeLegacy);
    const legacyList = await fetch(`${legacyBase}/mcp`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${await token()}` },
      body: JSON.stringify(LIST),
    });
    const legacyTools = (await legacyList.json()).result.tools.map((t) => t.name);
    assert('and its tools/list still exposes no training tool',
      legacyTools.every((n) => !n.startsWith('create_') && !n.startsWith('approve_') && !n.startsWith('publish_')));
  } finally { legacy.kill(); }

  process.stdout.write('fail closed on JWKS outage:\n');
  await new Promise((r) => asServer.close(r));
  // a fresh gateway (no cached JWKS) must refuse, not pass
  const gateway2 = spawn(process.execPath, [GATEWAY], {
    env: { ...process.env,
      RUNNING_AI_GATEWAY_PORT: String(GATEWAY_PORT + 5),
      RUNNING_AI_GATEWAY_TARGET_URL: `http://127.0.0.1:${BACKEND_PORT}/mcp`,
      RUNNING_AI_OAUTH_ISSUER: ISSUER, RUNNING_AI_OAUTH_AUDIENCE: AUDIENCE,
      RUNNING_AI_ALLOWED_SUB: SUB, RUNNING_AI_PUBLIC_BASE_URL: PUBLIC_BASE,
      RUNNING_AI_ALLOWED_TOOLS: 'ping_running_ai' },
    stdio: ['ignore', 'pipe', 'inherit'],
  });
  await new Promise((resolve, reject) => {
    const t = setTimeout(() => reject(new Error('gateway2 did not start')), 5000);
    gateway2.stdout.on('data', (d) => { if (String(d).includes('GATEWAY_STARTED')) { clearTimeout(t); resolve(); } });
  });
  const hitsBefore = backendHits;
  const outage = await fetch(`http://127.0.0.1:${GATEWAY_PORT + 5}/mcp`, {
    method: 'POST', headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${await token()}` },
    body: JSON.stringify(LIST),
  });
  assert('JWKS/AS unreachable -> 503, never a pass', outage.status === 503);
  assert('and the backend saw nothing', backendHits === hitsBefore);
  gateway2.kill();

  process.stdout.write('startup validation:\n');
  const bad = spawn(process.execPath, [GATEWAY], {
    env: { ...process.env, RUNNING_AI_GATEWAY_PORT: '17929',
      RUNNING_AI_OAUTH_ISSUER: ISSUER, RUNNING_AI_OAUTH_AUDIENCE: AUDIENCE,
      RUNNING_AI_ALLOWED_SUB: SUB, RUNNING_AI_PUBLIC_BASE_URL: PUBLIC_BASE,
      RUNNING_AI_ALLOWED_TOOLS: 'ping_running_ai,publish_workout' },
    stdio: ['ignore', 'ignore', 'pipe'],
  });
  const badExit = await new Promise((resolve) => bad.on('exit', resolve));
  assert('publish_workout on the allowlist refuses to start', badExit === 1);
  const noEnv = spawn(process.execPath, [GATEWAY], {
    env: { ...process.env, RUNNING_AI_OAUTH_ISSUER: '', RUNNING_AI_OAUTH_AUDIENCE: '',
      RUNNING_AI_ALLOWED_SUB: '', RUNNING_AI_PUBLIC_BASE_URL: '' },
    stdio: ['ignore', 'ignore', 'pipe'],
  });
  assert('missing required config refuses to start', (await new Promise((r) => noEnv.on('exit', r))) === 1);
} finally {
  gateway.kill();
  backend.close();
}

process.stdout.write(`\n${checks - failures}/${checks} checks passed\n`);
process.exit(failures === 0 ? 0 : 1);
