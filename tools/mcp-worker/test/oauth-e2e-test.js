// Phase 7D-2 pre-deployment review: the WHOLE OAuth chain, driven locally, with no Cloudflare
// account and no real data.
//
//   ChatGPT-shaped client  ->  Worker (wrangler dev, real OAuthProvider, real KV)
//                          ->  stub GitHub (stands in for the sign-in a human does in a browser)
//                          ->  Worker mints the RS256 hop token
//                          ->  tools/mcp-gateway  (the real, unmodified process)
//                          ->  synthetic MCP backend serving the seven read tools
//
// It answers the four review questions with measurements rather than claims:
//   1. the hop token's sub/aud/iss/scope come from the authenticated GitHub id, and the published
//      JWKS carries public key material only;
//   2. `authorization_response_iss_parameter_supported` matches what the success AND failure
//      callbacks actually return, and PKCE S256 + DCR behave as ChatGPT needs;
//   3. the hop token is only ever minted for an already-authenticated MCP request, the gateway
//      still refuses anything reaching it directly, and no secret reaches a log line;
//   4. a properly authenticated user really can read all seven tools (synthetic data).
//
// Run: npm run test:oauth      (needs tools/mcp-gateway/node_modules and tools/mcp-worker/node_modules)

import { spawn } from 'node:child_process';
import http from 'node:http';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { webcrypto } from 'node:crypto';

import { publicJwkOf } from '../src/internal-jwt.js';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const FIXTURES = path.join(__dirname, 'fixtures');
const GATEWAY = path.join(__dirname, '..', '..', 'mcp-gateway', 'server.js');
const DEV_VARS = path.join(FIXTURES, '.dev.vars');
const DEV_LOG = path.join(FIXTURES, 'wrangler-dev.log');

const WORKER = 'http://localhost:8788';          // must match PUBLIC_BASE_URL in wrangler.test.jsonc
const GATEWAY_PORT = 17930;
const BACKEND_PORT = 17932;
const GITHUB_PORT = 17934;
const OWNER_ID = '4242';
const OWNER_LOGIN = 'runningai-owner';
const OWNER_SUB = `github|${OWNER_ID}`;
const SCOPE = 'runningai:read';
const CHATGPT_REDIRECT = 'https://chatgpt.com/connector_platform_oauth_redirect';
const GITHUB_CLIENT_SECRET = 'stub-github-client-secret-do-not-reuse';
const READ_TOOLS = ['get_recent_activities', 'get_activity_analysis', 'get_training_context',
  'get_recovery_context', 'get_fitness_trend', 'get_workout_recommendation', 'get_athlete_profile'];

let checks = 0; let failures = 0;
function assert(name, condition, detail) {
  checks += 1;
  if (condition) process.stdout.write(`  PASS ${name}\n`);
  else { failures += 1; process.stdout.write(`  FAIL ${name}${detail ? ` — ${detail}` : ''}\n`); }
}

const b64url = (bytes) => Buffer.from(bytes).toString('base64url');

// ---- synthetic MCP backend: the seven read tools, obviously fake values --------------------------

// Every number here is invented. Nothing in this file touches a real activity, a real DB or Garmin.
const SYNTHETIC = {
  get_recent_activities: { synthetic: true, activities: [
    { date: '2020-01-05', type: 'RUN', distanceMetres: 10000, durationSeconds: 3000, averageHeartRate: 150 },
    { date: '2020-01-03', type: 'RUN', distanceMetres: 5000, durationSeconds: 1500, averageHeartRate: 140 },
  ] },
  get_activity_analysis: { synthetic: true, date: '2020-01-05', speedHrDecouplingPercent: 5.0, analysisStatus: 'COMPLETE' },
  get_training_context: { synthetic: true, contextVersion: 'V2', sizeBytes: 1234, dataCoverage: { analysedActivities: 2 } },
  get_recovery_context: { synthetic: true, date: '2020-01-05', hrv: 50.0, sleepHours: 7.5, bodyBattery: 70 },
  get_fitness_trend: { synthetic: true, days: [{ date: '2020-01-05', ctl: 20.0, atl: 15.0, derivedForm: 5.0 }] },
  get_workout_recommendation: { synthetic: true, intent: 'EASY', durationMinutesRange: [30, 60], note: 'not an AI prescription' },
  get_athlete_profile: { synthetic: true, lactateThresholdHeartRate: 170, thresholdPaceSecondsPerKm: 300, sourceTracked: false },
};

let backendHits = 0;
const backend = http.createServer((req, res) => {
  backendHits += 1;
  let body = '';
  req.on('data', (c) => { body += c; });
  req.on('end', () => {
    const msg = JSON.parse(body);
    let result;
    if (msg.method === 'tools/list') {
      result = { tools: READ_TOOLS.map((name) => ({ name, annotations: { readOnlyHint: true } })) };
    } else if (msg.method === 'initialize') {
      result = { protocolVersion: '2025-06-18', serverInfo: { name: 'running-ai-synthetic', version: '0' }, capabilities: { tools: {} } };
    } else {
      const name = msg.params && msg.params.name;
      const payload = SYNTHETIC[name] || { synthetic: true, unknown: name };
      result = { content: [{ type: 'text', text: JSON.stringify(payload) }], structuredContent: payload, isError: false };
    }
    res.writeHead(200, { 'Content-Type': 'application/json' });
    res.end(JSON.stringify({ jsonrpc: '2.0', id: msg.id ?? null, result }));
  });
});

// ---- stub GitHub: the browser sign-in a human would do ------------------------------------------

let nextGithubUser = { id: Number(OWNER_ID), login: OWNER_LOGIN };
let sawClientSecret = false;
const github = http.createServer((req, res) => {
  const url = new URL(req.url, `http://127.0.0.1:${GITHUB_PORT}`);
  if (url.pathname === '/login/oauth/authorize') {
    // The human approves: GitHub bounces straight back to the Worker's callback.
    const back = new URL(url.searchParams.get('redirect_uri'));
    back.searchParams.set('code', 'stub-github-code');
    back.searchParams.set('state', url.searchParams.get('state'));
    res.writeHead(302, { Location: back.toString() });
    return res.end();
  }
  if (url.pathname === '/login/oauth/access_token') {
    let body = '';
    req.on('data', (c) => { body += c; });
    return req.on('end', () => {
      try { if (JSON.parse(body).client_secret === GITHUB_CLIENT_SECRET) sawClientSecret = true; } catch { /* ignore */ }
      res.writeHead(200, { 'Content-Type': 'application/json' });
      res.end(JSON.stringify({ access_token: 'stub-github-user-token', token_type: 'bearer', scope: 'read:user' }));
    });
  }
  if (url.pathname === '/api/user') {
    res.writeHead(200, { 'Content-Type': 'application/json' });
    return res.end(JSON.stringify(nextGithubUser));
  }
  res.writeHead(404); res.end();
});

// ---- bring everything up -------------------------------------------------------------------------

async function newSigningJwk() {
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

const signingJwk = await newSigningJwk();

await new Promise((r) => backend.listen(BACKEND_PORT, '127.0.0.1', r));
await new Promise((r) => github.listen(GITHUB_PORT, '127.0.0.1', r));

const gateway = spawn(process.execPath, [GATEWAY], {
  env: {
    ...process.env,
    RUNNING_AI_GATEWAY_PORT: String(GATEWAY_PORT),
    RUNNING_AI_GATEWAY_TARGET_URL: `http://127.0.0.1:${BACKEND_PORT}/mcp`,
    RUNNING_AI_OAUTH_ISSUER: `${WORKER}/internal/`,
    RUNNING_AI_OAUTH_AUDIENCE: `${WORKER}/mcp`,
    RUNNING_AI_ALLOWED_SUB: OWNER_SUB,
    RUNNING_AI_PUBLIC_BASE_URL: WORKER,
    RUNNING_AI_ALLOWED_TOOLS: READ_TOOLS.join(','),
  },
  stdio: ['ignore', 'pipe', 'inherit'],
});
let gatewayLog = '';
gateway.stdout.on('data', (d) => { gatewayLog += String(d); });
await new Promise((resolve, reject) => {
  const t = setTimeout(() => reject(new Error('gateway did not start')), 8000);
  const check = () => { if (gatewayLog.includes('GATEWAY_STARTED')) { clearTimeout(t); resolve(); } };
  gateway.stdout.on('data', check);
  gateway.on('exit', () => reject(new Error('gateway exited during startup')));
});

fs.writeFileSync(DEV_VARS,
  `HOP_SIGNING_JWK=${JSON.stringify(signingJwk)}\n` +
  'GITHUB_CLIENT_ID=stub-github-client-id\n' +
  `GITHUB_CLIENT_SECRET=${GITHUB_CLIENT_SECRET}\n`);

const devLog = fs.openSync(DEV_LOG, 'w');
const worker = spawn(process.execPath,
  [path.join(__dirname, '..', 'node_modules', 'wrangler', 'bin', 'wrangler.js'),
    'dev', '--config', 'wrangler.test.jsonc', '--port', '8788', '--local'],
  { cwd: FIXTURES, stdio: ['ignore', devLog, devLog], env: { ...process.env, CI: '1' } });

async function waitForWorker() {
  for (let i = 0; i < 120; i += 1) {
    try {
      const res = await fetch(`${WORKER}/health`);
      if (res.ok) return;
    } catch { /* not up yet */ }
    await new Promise((r) => setTimeout(r, 500));
  }
  throw new Error(`worker did not start — see ${DEV_LOG}`);
}
await waitForWorker();

// ---- the OAuth dance, exactly as a connector performs it ------------------------------------------

async function registerClient() {
  const res = await fetch(`${WORKER}/register`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({
      client_name: 'ChatGPT (test)',
      redirect_uris: [CHATGPT_REDIRECT],
      token_endpoint_auth_method: 'none',
      grant_types: ['authorization_code', 'refresh_token'],
      response_types: ['code'],
    }),
  });
  return { status: res.status, body: await res.json() };
}

async function pkce() {
  const verifier = b64url(webcrypto.getRandomValues(new Uint8Array(32)));
  const digest = await webcrypto.subtle.digest('SHA-256', new TextEncoder().encode(verifier));
  return { verifier, challenge: b64url(new Uint8Array(digest)) };
}

/** Walks authorize -> stub GitHub -> callback and returns the final redirect the client receives. */
async function signIn(clientId, challenge, state, { responseType = 'code' } = {}) {
  const authorize = new URL(`${WORKER}/authorize`);
  authorize.searchParams.set('response_type', responseType);
  authorize.searchParams.set('client_id', clientId);
  authorize.searchParams.set('redirect_uri', CHATGPT_REDIRECT);
  authorize.searchParams.set('scope', `${SCOPE} offline_access`);
  authorize.searchParams.set('state', state);
  authorize.searchParams.set('code_challenge', challenge);
  authorize.searchParams.set('code_challenge_method', 'S256');
  authorize.searchParams.set('resource', `${WORKER}/mcp`);

  const first = await fetch(authorize, { redirect: 'manual' });
  const firstLocation = first.headers.get('location');
  const cookie = (first.headers.getSetCookie ? first.headers.getSetCookie() : [])
    .map((c) => c.split(';')[0]).join('; ');
  if (!firstLocation || !firstLocation.startsWith(`http://127.0.0.1:${GITHUB_PORT}`)) {
    return { stage: 'authorize', status: first.status, location: firstLocation };
  }

  const bounced = await fetch(firstLocation, { redirect: 'manual' });
  const callbackUrl = bounced.headers.get('location');
  const done = await fetch(callbackUrl, { redirect: 'manual', headers: cookie ? { Cookie: cookie } : {} });
  return {
    stage: 'callback',
    status: done.status,
    location: done.headers.get('location'),
    githubRedirect: firstLocation,
    body: done.status >= 400 ? await done.json().catch(() => null) : null,
  };
}

async function exchange(params) {
  const res = await fetch(`${WORKER}/oauth/token`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body: new URLSearchParams(params).toString(),
  });
  return { status: res.status, body: await res.json().catch(() => null) };
}

async function mcp(payload, token, target = `${WORKER}/mcp`) {
  const res = await fetch(target, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      Accept: 'application/json, text/event-stream',
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
    },
    body: JSON.stringify(payload),
  });
  let body = null; try { body = await res.json(); } catch { /* empty */ }
  return { status: res.status, body, www: res.headers.get('www-authenticate') };
}

let accessToken = null;
try {
  process.stdout.write('\n1. hop-token claims and the published JWKS:\n');
  const jwks = await (await fetch(`${WORKER}/internal/jwks.json`)).json();
  const key = jwks.keys[0];
  const PRIVATE_FIELDS = ['d', 'p', 'q', 'dp', 'dq', 'qi', 'oth', 'k'];
  assert('JWKS publishes exactly one key, RS256, with a kid',
    jwks.keys.length === 1 && key.kty === 'RSA' && key.alg === 'RS256' && key.use === 'sig' && !!key.kid);
  assert('JWKS carries public fields only (no d/p/q/dp/dq/qi)',
    PRIVATE_FIELDS.every((f) => key[f] === undefined) && Object.keys(key).sort().join(',') === 'alg,e,kid,kty,n,use');
  assert('publicJwkOf() strips private material even from a full private JWK',
    PRIVATE_FIELDS.every((f) => publicJwkOf(signingJwk)[f] === undefined) && signingJwk.d !== undefined);
  const internalMeta = await (await fetch(`${WORKER}/internal/.well-known/oauth-authorization-server`)).json();
  assert('the internal issuer advertises itself and its JWKS, nothing else',
    internalMeta.issuer === `${WORKER}/internal/` && internalMeta.jwks_uri === `${WORKER}/internal/jwks.json`);

  process.stdout.write('\n2. discovery, DCR, PKCE S256 and the iss parameter:\n');
  const asMeta = await (await fetch(`${WORKER}/.well-known/oauth-authorization-server`)).json();
  assert('AS metadata: PKCE S256, DCR endpoint, iss parameter supported',
    asMeta.code_challenge_methods_supported.includes('S256') &&
    typeof asMeta.registration_endpoint === 'string' &&
    asMeta.authorization_response_iss_parameter_supported === true);
  // CIMD needs `global_fetch_strictly_public`, which this test config deliberately omits so the
  // Worker can reach loopback. So assert it is off HERE and on in the config that ships.
  const prodConfig = fs.readFileSync(path.join(__dirname, '..', 'wrangler.jsonc'), 'utf8');
  assert('CIMD follows the compatibility flag: off in the test config, ON in the deployed config',
    asMeta.client_id_metadata_document_supported === false &&
    /"global_fetch_strictly_public"/.test(prodConfig));
  const prMeta = await (await fetch(`${WORKER}/.well-known/oauth-protected-resource/mcp`)).json();
  assert('protected-resource metadata names this resource, this AS and the read scope',
    prMeta.resource === `${WORKER}/mcp` && prMeta.authorization_servers[0] === WORKER &&
    prMeta.scopes_supported.join() === SCOPE);

  const reg = await registerClient();
  assert('DCR registers a public client for ChatGPT\'s stable callback',
    reg.status === 201 && typeof reg.body.client_id === 'string' &&
    reg.body.redirect_uris[0] === CHATGPT_REDIRECT && reg.body.token_endpoint_auth_method === 'none');
  const clientId = reg.body.client_id;

  const { verifier, challenge } = await pkce();
  const signedIn = await signIn(clientId, challenge, 'state-success');
  assert('the sign-in redirect asks GitHub for identity scope only',
    /[?&]scope=read%3Auser(&|$)/.test(signedIn.githubRedirect || ''));
  const finalUrl = new URL(signedIn.location);
  assert('the success callback returns code + state + iss (matching the metadata claim)',
    signedIn.status === 302 && finalUrl.origin + finalUrl.pathname === CHATGPT_REDIRECT &&
    !!finalUrl.searchParams.get('code') && finalUrl.searchParams.get('state') === 'state-success' &&
    finalUrl.searchParams.get('iss') === WORKER,
    `iss=${finalUrl.searchParams.get('iss')}`);
  const code = finalUrl.searchParams.get('code');

  const wrongVerifier = await exchange({ grant_type: 'authorization_code', code, client_id: clientId,
    redirect_uri: CHATGPT_REDIRECT, code_verifier: (await pkce()).verifier });
  assert('a wrong PKCE verifier is refused', wrongVerifier.status >= 400);
  const token = await exchange({ grant_type: 'authorization_code', code, client_id: clientId,
    redirect_uri: CHATGPT_REDIRECT, code_verifier: verifier });
  assert('the correct verifier yields an access token, a refresh token and the read scope',
    token.status === 200 && typeof token.body.access_token === 'string' &&
    typeof token.body.refresh_token === 'string' && token.body.expires_in === 3600 &&
    String(token.body.scope).includes(SCOPE));
  accessToken = token.body.access_token;
  assert('the access token is opaque, NOT a JWT (so the gateway could never verify it directly)',
    accessToken.split('.').length !== 3 && accessToken.split(':').length === 3);

  const refreshed = await exchange({ grant_type: 'refresh_token', refresh_token: token.body.refresh_token, client_id: clientId });
  assert('the refresh grant issues a new access token', refreshed.status === 200 && !!refreshed.body.access_token);

  const badFlow = await signIn(clientId, (await pkce()).challenge, 'state-error', { responseType: 'token' });
  const errUrl = badFlow.location ? new URL(badFlow.location) : null;
  assert('an unsupported response_type redirects with error AND iss',
    !!errUrl && errUrl.origin + errUrl.pathname === CHATGPT_REDIRECT &&
    !!errUrl.searchParams.get('error') && errUrl.searchParams.get('iss') === WORKER,
    badFlow.location);

  process.stdout.write('\n3. the hop token is only used for authenticated MCP traffic:\n');
  const hitsBeforeNegatives = backendHits;
  const anon = await mcp({ jsonrpc: '2.0', id: 1, method: 'tools/list' });
  assert('no token at the Worker -> 401 + resource_metadata challenge',
    anon.status === 401 && /resource_metadata=/.test(anon.www || ''));
  assert('a made-up token at the Worker -> 401',
    (await mcp({ jsonrpc: '2.0', id: 1, method: 'tools/list' }, 'not:a:real-token')).status === 401);
  assert('the Worker never forwarded any of that', backendHits === hitsBeforeNegatives);

  const direct = `http://127.0.0.1:${GATEWAY_PORT}/mcp`;
  const directAnon = await mcp({ jsonrpc: '2.0', id: 1, method: 'tools/list' }, null, direct);
  assert('reaching the gateway directly without a token -> 401',
    directAnon.status === 401 && /resource_metadata=/.test(directAnon.www || ''));
  assert('replaying the Worker\'s own OAuth access token straight at the gateway -> 401',
    (await mcp({ jsonrpc: '2.0', id: 1, method: 'tools/list' }, accessToken, direct)).status === 401);
  assert('a forged JWT-looking token at the gateway -> 401',
    (await mcp({ jsonrpc: '2.0', id: 1, method: 'tools/list' }, 'eyJhbGciOiJub25lIn0.eyJzdWIiOiJnaXRodWJ8NDI0MiJ9.', direct)).status === 401);
  assert('still nothing reached the backend', backendHits === hitsBeforeNegatives);

  nextGithubUser = { id: 9999, login: 'somebody-else' };
  const intruder = await signIn(clientId, (await pkce()).challenge, 'state-intruder');
  assert('a different GitHub account is refused at sign-in, with no code issued',
    intruder.status === 403 && !intruder.location);
  nextGithubUser = { id: Number(OWNER_ID), login: OWNER_LOGIN };

  process.stdout.write('\n4. the authenticated owner reads all seven tools (synthetic data):\n');
  const init = await mcp({ jsonrpc: '2.0', id: 10, method: 'initialize', params: { protocolVersion: '2025-06-18', capabilities: {}, clientInfo: { name: 'test', version: '0' } } }, accessToken);
  assert('initialize succeeds end to end', init.status === 200 && init.body.result.serverInfo.name === 'running-ai-synthetic');
  const list = await mcp({ jsonrpc: '2.0', id: 11, method: 'tools/list' }, accessToken);
  assert('tools/list returns exactly the seven read tools',
    list.status === 200 && list.body.result.tools.map((t) => t.name).sort().join() === [...READ_TOOLS].sort().join());

  for (const name of READ_TOOLS) {
    const res = await mcp({ jsonrpc: '2.0', id: 20, method: 'tools/call', params: { name, arguments: {} } }, accessToken);
    const got = res.body && res.body.result && res.body.result.structuredContent;
    assert(`tools/call ${name} returns its synthetic payload`,
      res.status === 200 && got && got.synthetic === true &&
      JSON.stringify(got) === JSON.stringify(SYNTHETIC[name]));
  }

  const publish = await mcp({ jsonrpc: '2.0', id: 30, method: 'tools/call',
    params: { name: 'publish_workout', arguments: { date: '2020-01-05' } } }, accessToken);
  assert('publish_workout, even for the authenticated owner -> JSON-RPC -32602',
    publish.status === 200 && publish.body.error && publish.body.error.code === -32602);
  const discover = await mcp({ jsonrpc: '2.0', id: 31, method: 'server/discover' }, accessToken);
  assert('server/discover -> HTTP 200 JSON-RPC -32601, never 5xx',
    discover.status === 200 && discover.body.error && discover.body.error.code === -32601);
  assert('GET /mcp without a token -> 401 (authentication is checked first)',
    (await fetch(`${WORKER}/mcp`)).status === 401);
  assert('GET /mcp WITH a valid token -> 405: only POST is an MCP transport',
    (await fetch(`${WORKER}/mcp`, { headers: { Authorization: `Bearer ${accessToken}` } })).status === 405);
  assert('/api/v1/* at the Worker -> 404',
    (await fetch(`${WORKER}/api/v1/activities`, { method: 'POST', body: '{}' })).status === 404);
  assert('/actuator/* at the Worker -> 404', (await fetch(`${WORKER}/actuator/health`)).status === 404);

  process.stdout.write('\n   secrets must not appear in any log:\n');
  await new Promise((r) => setTimeout(r, 500));
  const workerLog = fs.readFileSync(DEV_LOG, 'utf8');
  assert('the stub GitHub really did receive the client secret (so the check below is meaningful)', sawClientSecret);
  assert('no OAuth access token in the Worker log', !workerLog.includes(accessToken));
  assert('no GitHub client secret in the Worker log', !workerLog.includes(GITHUB_CLIENT_SECRET));
  assert('no private key material in the Worker log', !workerLog.includes(signingJwk.d));
  assert('no hop-token signature material in the gateway log',
    !gatewayLog.includes(signingJwk.d) && !gatewayLog.includes(accessToken) && !/Bearer\s+ey/.test(gatewayLog));
} finally {
  worker.kill();
  gateway.kill();
  backend.close();
  github.close();
  try { fs.unlinkSync(DEV_VARS); } catch { /* already gone */ }
}

process.stdout.write(`\n${checks - failures}/${checks} checks passed\n`);
process.exit(failures === 0 ? 0 : 1);
