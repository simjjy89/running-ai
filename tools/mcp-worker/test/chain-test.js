// Phase 7D-2: proves the Cloudflare Worker's minted hop token is accepted by the EXISTING,
// UNCHANGED `tools/mcp-gateway`, and that every required rejection still rejects — through the
// real gateway process, not a stand-in.
//
// What this test is really asserting: replacing Auth0 with a Cloudflare-hosted authorization
// server did NOT weaken the trust boundary. The gateway still demands a valid RS256 signature
// from a JWKS it discovers, the right issuer, the right audience, an unexpired token, the
// required scope and exactly one subject — and the fake MCP backend's hit counter proves nothing
// unauthorized ever reached it.
//
// Node >= 20. Requires `tools/mcp-gateway/node_modules` (jose) — `npm install` there once.
// No Cloudflare account, no network, no secret: the key pair is generated per run.

import { spawn } from 'node:child_process';
import http from 'node:http';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { webcrypto } from 'node:crypto';

import { authorizationServerMetadata, jwksDocument, mintInternalToken } from '../src/internal-jwt.js';
import { handleMcpRequest } from '../src/mcp-proxy.js';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const GATEWAY = path.join(__dirname, '..', '..', 'mcp-gateway', 'server.js');

const ISSUER_PORT = 17931;
const BACKEND_PORT = 17932;
const GATEWAY_PORT = 17930;
const ISSUER = `http://127.0.0.1:${ISSUER_PORT}/internal/`;
const JWKS_URI = `http://127.0.0.1:${ISSUER_PORT}/internal/jwks.json`;
const PUBLIC_BASE = 'https://runningai-mcp.test';
const AUDIENCE = `${PUBLIC_BASE}/mcp`;
const OWNER = 'github|4242';
const SCOPE = 'runningai:read';
const READ_TOOLS = 'get_recent_activities,get_activity_analysis,get_training_context,' +
  'get_recovery_context,get_fitness_trend,get_workout_recommendation,get_athlete_profile';

let checks = 0; let failures = 0;
function assert(name, condition) {
  checks += 1;
  if (condition) process.stdout.write(`  PASS ${name}\n`);
  else { failures += 1; process.stdout.write(`  FAIL ${name}\n`); }
}

// ---- signing keys (one trusted, one rogue that the JWKS never publishes) -------------------------

async function newKeyPair() {
  const { publicKey, privateKey } = await webcrypto.subtle.generateKey(
    { name: 'RSASSA-PKCS1-v1_5', modulusLength: 2048, publicExponent: new Uint8Array([1, 0, 1]), hash: 'SHA-256' },
    true, ['sign', 'verify'],
  );
  const jwk = await webcrypto.subtle.exportKey('jwk', privateKey);
  const pub = await webcrypto.subtle.exportKey('jwk', publicKey);
  const digest = await webcrypto.subtle.digest('SHA-256', new TextEncoder().encode(pub.n));
  jwk.kid = Buffer.from(digest).toString('hex').slice(0, 16);
  jwk.alg = 'RS256';
  return jwk;
}

const goodJwk = await newKeyPair();
const rogueJwk = await newKeyPair();

const mint = (overrides = {}) => mintInternalToken({
  privateJwk: overrides.privateJwk || goodJwk,
  issuer: overrides.issuer || ISSUER,
  audience: overrides.audience || AUDIENCE,
  subject: overrides.subject || OWNER,
  scope: overrides.scope || SCOPE,
  ttlSeconds: overrides.ttlSeconds || 90,
  now: overrides.now || Date.now(),
});

// ---- the Worker's issuer endpoints, served by the real modules -----------------------------------

const issuerServer = http.createServer((req, res) => {
  const url = (req.url || '').split('?')[0];
  const send = (body) => { res.writeHead(200, { 'Content-Type': 'application/json' }); res.end(JSON.stringify(body)); };
  if (url === '/internal/.well-known/openid-configuration' ||
      url === '/internal/.well-known/oauth-authorization-server') {
    return send(authorizationServerMetadata(ISSUER, JWKS_URI));
  }
  if (url === '/internal/jwks.json') return send(jwksDocument(goodJwk));
  res.writeHead(404); res.end();
});
await new Promise((r) => issuerServer.listen(ISSUER_PORT, '127.0.0.1', r));

// ---- fake MCP backend, counting every request it is allowed to see -------------------------------

let backendHits = 0;
const backend = http.createServer((req, res) => {
  backendHits += 1;
  let body = '';
  req.on('data', (c) => { body += c; });
  req.on('end', () => {
    const msg = JSON.parse(body);
    const result = msg.method === 'tools/list'
      ? { tools: READ_TOOLS.split(',').map((name) => ({ name, annotations: { readOnlyHint: true } })) }
      : { content: [{ type: 'text', text: '{"ok":true}' }], structuredContent: { ok: true, tool: msg.params && msg.params.name } };
    res.writeHead(200, { 'Content-Type': 'application/json' });
    res.end(JSON.stringify({ jsonrpc: '2.0', id: msg.id ?? null, result }));
  });
});
await new Promise((r) => backend.listen(BACKEND_PORT, '127.0.0.1', r));

// ---- the REAL gateway, unmodified ----------------------------------------------------------------

const gateway = spawn(process.execPath, [GATEWAY], {
  env: {
    ...process.env,
    RUNNING_AI_GATEWAY_PORT: String(GATEWAY_PORT),
    RUNNING_AI_GATEWAY_TARGET_URL: `http://127.0.0.1:${BACKEND_PORT}/mcp`,
    RUNNING_AI_OAUTH_ISSUER: ISSUER,
    RUNNING_AI_OAUTH_AUDIENCE: AUDIENCE,
    RUNNING_AI_ALLOWED_SUB: OWNER,
    RUNNING_AI_PUBLIC_BASE_URL: PUBLIC_BASE,
    RUNNING_AI_ALLOWED_TOOLS: READ_TOOLS,
  },
  stdio: ['ignore', 'pipe', 'inherit'],
});
await new Promise((resolve, reject) => {
  const t = setTimeout(() => reject(new Error('gateway did not start')), 8000);
  gateway.stdout.on('data', (d) => { if (String(d).includes('GATEWAY_STARTED')) { clearTimeout(t); resolve(); } });
  gateway.on('exit', () => reject(new Error('gateway exited during startup')));
});

const GATEWAY_MCP = `http://127.0.0.1:${GATEWAY_PORT}/mcp`;
const LIST = { jsonrpc: '2.0', id: 1, method: 'tools/list', params: {} };
const CALL = { jsonrpc: '2.0', id: 2, method: 'tools/call', params: { name: 'get_recent_activities', arguments: { days: 30 } } };

async function viaGateway(payload, token) {
  const res = await fetch(GATEWAY_MCP, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', ...(token ? { Authorization: `Bearer ${token}` } : {}) },
    body: JSON.stringify(payload),
  });
  let body = null; try { body = await res.json(); } catch { /* empty */ }
  return { status: res.status, body, www: res.headers.get('www-authenticate') };
}

const config = {
  requiredScope: SCOPE,
  allowedUserId: OWNER,
  originUrl: GATEWAY_MCP,
  issuer: ISSUER,
  audience: AUDIENCE,
  privateJwk: goodJwk,
  tokenTtlSeconds: 90,
};
const ownerAuth = { scope: [SCOPE], userId: OWNER, audience: AUDIENCE };
const ownerProps = { githubId: '4242', githubLogin: 'owner' };

function mcpRequest(body, { method = 'POST', url = `${PUBLIC_BASE}/mcp` } = {}) {
  return new Request(url, method === 'GET' ? { method } : {
    method, headers: { 'Content-Type': 'application/json' }, body: typeof body === 'string' ? body : JSON.stringify(body),
  });
}

try {
  process.stdout.write('\nA. the minted hop token against the UNCHANGED gateway:\n');
  const ok = await viaGateway(LIST, await mint());
  assert('Worker-minted RS256 token is accepted by the gateway',
    ok.status === 200 && Array.isArray(ok.body.result.tools) && ok.body.result.tools.length === 7);
  const called = await viaGateway(CALL, await mint());
  assert('an allowlisted read tool is proxied',
    called.status === 200 && called.body.result.structuredContent.tool === 'get_recent_activities');
  assert('exactly those two requests reached the backend', backendHits === 2);

  process.stdout.write('\nB. required rejections (the backend must not move):\n');
  const hitsBefore = backendHits;
  const noToken = await viaGateway(LIST);
  assert('no token -> 401 with resource_metadata challenge',
    noToken.status === 401 && /resource_metadata=/.test(noToken.www || ''));
  assert('garbage token -> 401', (await viaGateway(LIST, 'not-a-jwt-at-all')).status === 401);
  const good = await mint();
  const tampered = `${good.slice(0, good.lastIndexOf('.') - 2)}xx${good.slice(good.lastIndexOf('.'))}`;
  assert('tampered token -> 401', (await viaGateway(LIST, tampered)).status === 401);
  assert('expired token -> 401',
    (await viaGateway(LIST, await mint({ now: Date.now() - 600000, ttlSeconds: 60 }))).status === 401);
  assert('token signed by a key the JWKS does not publish -> 401',
    (await viaGateway(LIST, await mint({ privateJwk: rogueJwk }))).status === 401);
  assert('wrong issuer -> 401', (await viaGateway(LIST, await mint({ issuer: 'https://evil.example/' }))).status === 401);
  assert('wrong audience -> 401', (await viaGateway(LIST, await mint({ audience: 'https://other.example/mcp' }))).status === 401);
  const noScope = await viaGateway(LIST, await mint({ scope: 'openid' }));
  assert('missing runningai:read -> 403 insufficient_scope',
    noScope.status === 403 && /insufficient_scope/.test(noScope.www || ''));
  assert('a different subject -> 403', (await viaGateway(LIST, await mint({ subject: 'github|9999' }))).status === 403);
  const publish = await viaGateway({ jsonrpc: '2.0', id: 3, method: 'tools/call',
    params: { name: 'publish_workout', arguments: { date: '2026-10-13' } } }, await mint());
  assert('publish_workout with a VALID token -> JSON-RPC -32602, never proxied',
    publish.status === 200 && publish.body.error && publish.body.error.code === -32602);
  const discover = await viaGateway({ jsonrpc: '2.0', id: 4, method: 'server/discover', params: {} }, await mint());
  assert('server/discover -> HTTP 200 JSON-RPC -32601, never 5xx',
    discover.status === 200 && discover.body.error && discover.body.error.code === -32601);
  const api = await fetch(`http://127.0.0.1:${GATEWAY_PORT}/api/v1/activities`, { method: 'POST', body: '{}' });
  assert('/api/v1/* is not a route here -> 404', api.status === 404);
  const actuator = await fetch(`http://127.0.0.1:${GATEWAY_PORT}/actuator/health`);
  assert('/actuator/* is not a route here -> 404', actuator.status === 404);
  assert('the backend saw ZERO of the above', backendHits === hitsBefore);

  process.stdout.write('\nC. the Worker proxy handler (its own checks, before anything is minted):\n');
  let originCalls = 0;
  const countingFetch = async (url, init) => { originCalls += 1; return new Response('{}', { status: 200, headers: { 'Content-Type': 'application/json' } }); };
  const handler = (overrides = {}) => handleMcpRequest(overrides.request || mcpRequest(LIST), {
    auth: overrides.auth || ownerAuth,
    props: overrides.props || ownerProps,
    config: { ...config, ...(overrides.config || {}) },
    deps: { fetchImpl: overrides.fetchImpl || countingFetch },
  });

  assert('GET /mcp -> 405', (await handler({ request: mcpRequest(null, { method: 'GET' }) })).status === 405);
  assert('another path -> 404',
    (await handler({ request: mcpRequest(LIST, { url: `${PUBLIC_BASE}/api/v1/activities` }) })).status === 404);
  assert('token without runningai:read -> 403',
    (await handler({ auth: { ...ownerAuth, scope: ['offline_access'] } })).status === 403);
  assert('a token for another user -> 403',
    (await handler({ auth: { ...ownerAuth, userId: 'github|9999' } })).status === 403);
  assert('props that disagree with the grant subject -> 403',
    (await handler({ props: { githubId: '9999', githubLogin: 'someone-else' } })).status === 403);
  assert('body above the 64 KiB cap -> 413',
    (await handler({ request: mcpRequest('x'.repeat(65537)) })).status === 413);
  assert('nothing above reached an origin', originCalls === 0);
  const unreachable = await handler({ fetchImpl: async () => { throw new Error('connect ECONNREFUSED'); } });
  assert('origin unreachable -> 502, not a pass', unreachable.status === 502);

  let seenAuthHeader = null;
  await handler({ fetchImpl: async (url, init) => { seenAuthHeader = init.headers.Authorization; return new Response('{}', { status: 200 }); } });
  assert('the authorized path forwards a Bearer hop token',
    typeof seenAuthHeader === 'string' && seenAuthHeader.startsWith('Bearer ey'));

  process.stdout.write('\nD. full chain — Worker handler -> real gateway -> backend:\n');
  const chainBefore = backendHits;
  const chained = await handleMcpRequest(mcpRequest(CALL), {
    auth: ownerAuth, props: ownerProps, config, deps: { fetchImpl: fetch },
  });
  const chainedBody = await chained.json();
  assert('an authenticated ChatGPT-shaped call traverses the whole chain and answers',
    chained.status === 200 && chainedBody.result.structuredContent.tool === 'get_recent_activities' &&
    backendHits === chainBefore + 1);
  const refusedChain = await handleMcpRequest(mcpRequest(CALL), {
    auth: { ...ownerAuth, userId: 'github|9999' }, props: ownerProps, config, deps: { fetchImpl: fetch },
  });
  assert('an unauthorized caller is stopped at the Worker, before the gateway',
    refusedChain.status === 403 && backendHits === chainBefore + 1);

  process.stdout.write(`
E. hop-token scope forwarding (Phase 7F-3B-2B):
`);
  // Before 7F-3B-2B the hop token's scope was hard-wired to the base read scope, so a train-scoped
  // user token arrived at the gateway as read-only. These assertions pin the replacement rule:
  // exactly the forwardable scopes the access token was VERIFIED to hold, and nothing else.
  const TRAIN = 'runningai:train';
  const forwardConfig = { ...config, forwardableScopes: [SCOPE, TRAIN] };
  async function hopScopeFor(grantedScopes) {
    let seen = null;
    await handleMcpRequest(mcpRequest(LIST), {
      auth: { ...ownerAuth, scope: grantedScopes },
      props: ownerProps,
      config: forwardConfig,
      deps: {
        fetchImpl: async (url, init) => {
          const jwt = String(init.headers.Authorization).replace(/^Bearer /, '');
          seen = JSON.parse(Buffer.from(jwt.split('.')[1], 'base64url').toString()).scope;
          return new Response('{}', { status: 200 });
        },
      },
    });
    return seen;
  }
  assert('a read-only token forwards exactly runningai:read',
    (await hopScopeFor([SCOPE])) === SCOPE);
  assert('a read+train token forwards both, in the fixed Worker order',
    (await hopScopeFor([TRAIN, SCOPE])) === `${SCOPE} ${TRAIN}`);
  assert('offline_access is never forwarded',
    (await hopScopeFor([SCOPE, 'offline_access'])) === SCOPE);
  assert('a token claiming runningai:publish has it STRIPPED from the hop token',
    (await hopScopeFor([SCOPE, TRAIN, 'runningai:publish'])) === `${SCOPE} ${TRAIN}`);
  assert('an unknown scope is never forwarded',
    (await hopScopeFor([SCOPE, 'admin:everything'])) === SCOPE);
  assert('with no forwardableScopes configured it falls back to the base scope alone',
    await (async () => {
      let seen = null;
      await handleMcpRequest(mcpRequest(LIST), {
        auth: { ...ownerAuth, scope: [SCOPE, TRAIN] }, props: ownerProps, config,
        deps: { fetchImpl: async (url, init) => {
          const jwt = String(init.headers.Authorization).replace(/^Bearer /, '');
          seen = JSON.parse(Buffer.from(jwt.split('.')[1], 'base64url').toString()).scope;
          return new Response('{}', { status: 200 });
        } },
      });
      return seen === SCOPE;
    })());
} finally {
  gateway.kill();
  backend.close();
  issuerServer.close();
}

process.stdout.write(`\n${checks - failures}/${checks} checks passed\n`);
process.exit(failures === 0 ? 0 : 1);
