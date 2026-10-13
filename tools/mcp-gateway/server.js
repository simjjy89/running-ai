// RunningAI MCP OAuth Gateway (Phase 7C-2b).
//
// An OAuth 2.1 RESOURCE SERVER in front of the local RunningAI MCP endpoint: it verifies an
// Auth0-issued (or any standards-compliant AS's) JWT access token on EVERY /mcp request and
// forwards only allowlisted tool traffic to a fixed local target. It is NOT an authorization
// server: no login, no token issuance, no session - the AS does that, and this process only
// validates what the AS signed.
//
// Phase 7F-1 added per-tool authorization: each tool declares the OAuth scope it demands
// (TOOL_SCOPES below, hard-coded - not configurable), so one connection can be allowed to read
// training data, a second to also design and approve workouts, and a third to also send them to
// Intervals.icu. The default allowlist is still the seven read-only tools, so a gateway started
// with no new configuration behaves exactly as it did before.
//
// Hard guarantees, by construction:
//  - Binds 127.0.0.1 ONLY (hard-coded): the public leg is a separate tunnel; this process never
//    listens on a public interface.
//  - FAIL CLOSED everywhere: missing/invalid configuration refuses to start; a missing/invalid/
//    expired/mis-scoped/mis-audienced/foreign-subject token, an unknown signing key, or an
//    unreachable JWKS endpoint all end the request with 401/403/503 BEFORE anything is proxied -
//    the RunningAI backend sees zero traffic for an unauthorized request.
//  - The proxy target is FIXED at startup (one local URL). There is no path/host forwarding, so
//    this can never be used as a generic REST proxy.
//  - Only MCP methods initialize/ping/tools/list/tools/call (+ notifications/*) pass; tools/call
//    passes only for a name on the allowlist AND only when the token carries that tool's required
//    scope. publish_workout (or any unknown name) is rejected here with a JSON-RPC error even if
//    the backend were misconfigured, and tools/list responses are additionally FILTERED to the
//    allowlist intersected with what the token's scopes permit (defense in depth).
//  - No secret is ever logged or echoed: Authorization headers, tokens and request bodies never
//    reach a log line; /health and the RFC 9728 metadata expose configuration identities only
//    (issuer URL, resource URL), never a credential.
//
// Crypto is NOT hand-rolled: JWT signature/claim verification is `jose` (panva), a maintained,
// audited library; JWKS fetching/caching/rotation is jose's createRemoteJWKSet.
//
// Unauthenticated discovery, per the MCP authorization spec (2025-06-18) + RFC 9728: a request
// without a token gets 401 with `WWW-Authenticate: Bearer resource_metadata="..."`, and the
// protected-resource metadata document is served openly - it contains only the resource
// identifier, the authorization server and the scope names. No tool output, no athlete data and
// not even the tool list is reachable without a verified token.

import http from 'node:http';
import { createRemoteJWKSet, jwtVerify } from 'jose';

const HOST = '127.0.0.1'; // never configurable - see header comment

function requiredEnv(name) {
  const v = process.env[name];
  if (!v || !v.trim()) {
    process.stderr.write(`FATAL missing required environment variable ${name}\n`);
    process.exit(1);
  }
  return v.trim();
}

const PORT = Number(process.env.RUNNING_AI_GATEWAY_PORT || 17910);
const TARGET_URL = process.env.RUNNING_AI_GATEWAY_TARGET_URL || 'http://127.0.0.1:17902/mcp';
const ISSUER = requiredEnv('RUNNING_AI_OAUTH_ISSUER');           // e.g. https://<tenant>.auth0.com/
const AUDIENCE = requiredEnv('RUNNING_AI_OAUTH_AUDIENCE');       // the Auth0 API identifier (resource)
const ALLOWED_SUB = requiredEnv('RUNNING_AI_ALLOWED_SUB');       // exactly one permitted subject
const PUBLIC_BASE_URL = requiredEnv('RUNNING_AI_PUBLIC_BASE_URL').replace(/\/$/, '');
// The BASE scope: the minimum a token must carry to reach this resource at all, and what
// initialize / ping / tools/list require. Unchanged default, so an existing read-only connection
// behaves exactly as before.
const REQUIRED_SCOPE = (process.env.RUNNING_AI_REQUIRED_SCOPE || 'runningai:read').trim();
const ALLOWED_TOOLS = new Set((process.env.RUNNING_AI_ALLOWED_TOOLS ||
  'get_recent_activities,get_activity_analysis,get_training_context,get_recovery_context,' +
  'get_fitness_trend,get_workout_recommendation,get_athlete_profile')
  .split(',').map((t) => t.trim()).filter(Boolean));

// Per-tool scope policy (Phase 7F-1). HARD-CODED on purpose: which scope a tool demands is a
// security property of that tool, not an operator knob - no environment variable can downgrade a
// write tool to the read scope. Scopes are NOT hierarchical: holding runningai:publish does not
// imply runningai:train, and no amount of runningai:read ever reaches either. A tool with no entry
// here requires the base scope, which is what every read tool needs.
//
// The consequence that matters: a token granted only runningai:read cannot create, revise, approve
// or publish anything - it cannot even SEE those tools in tools/list.
const TOOL_SCOPES = {
  create_workout_draft: 'runningai:train',
  get_workout_draft: 'runningai:train',
  revise_workout_draft: 'runningai:train',
  approve_workout_draft: 'runningai:train',
  preview_draft_publish: 'runningai:train',
  // The one tool that writes to a system outside RunningAI gets its own scope, so "can design and
  // approve training" and "can send it to my calendar" are separately grantable and revocable.
  publish_approved_draft: 'runningai:publish',
};
const scopeFor = (tool) => TOOL_SCOPES[tool] || REQUIRED_SCOPE;

// Every scope this gateway can actually require, given what is enabled here. With the default
// read-only allowlist this is exactly [runningai:read], so the published metadata is unchanged.
const SCOPES_SUPPORTED = [...new Set([REQUIRED_SCOPE, ...[...ALLOWED_TOOLS].map(scopeFor)])];
const WRITE_TOOLS_ENABLED = [...ALLOWED_TOOLS].some((t) => TOOL_SCOPES[t]);

if (!new URL(TARGET_URL).hostname.match(/^127\.0\.0\.1$|^localhost$/)) {
  process.stderr.write('FATAL RUNNING_AI_GATEWAY_TARGET_URL must point at a loopback address\n');
  process.exit(1);
}
if (ALLOWED_TOOLS.has('publish_workout')) {
  process.stderr.write('FATAL publish_workout can never be on the gateway allowlist\n');
  process.exit(1);
}

const RESOURCE = `${PUBLIC_BASE_URL}/mcp`;
const METADATA_PATH = '/.well-known/oauth-protected-resource';
const ALLOWED_METHODS = new Set(['initialize', 'ping', 'tools/list', 'tools/call']);

function log(level, message) {
  const safe = String(message).replace(/(authorization|token|api[_-]?key|bearer)\s*[:=]\s*\S+/ig, '$1=[REDACTED]');
  process.stdout.write(`${new Date().toISOString()} ${level} ${safe}\n`);
}

// JWKS discovery is lazy and cached; a discovery/JWKS failure yields 503 (fail closed), never a pass.
let jwksPromise = null;
async function jwks() {
  if (!jwksPromise) {
    jwksPromise = (async () => {
      const base = ISSUER.endsWith('/') ? ISSUER : `${ISSUER}/`;
      const candidates = [`${base}.well-known/openid-configuration`,
        `${base}.well-known/oauth-authorization-server`];
      for (const url of candidates) {
        try {
          const res = await fetch(url);
          if (!res.ok) continue;
          const doc = await res.json();
          if (doc && typeof doc.jwks_uri === 'string') {
            log('INFO', `AS metadata discovered: jwks via ${new URL(doc.jwks_uri).pathname}`);
            return createRemoteJWKSet(new URL(doc.jwks_uri));
          }
        } catch (e) { /* try the next candidate */ }
      }
      throw new Error('authorization server metadata discovery failed');
    })();
    jwksPromise.catch(() => { jwksPromise = null; }); // a failed discovery is retried next request
  }
  return jwksPromise;
}

function scopesOf(payload) {
  if (typeof payload.scope === 'string') return payload.scope.split(' ').filter(Boolean);
  if (Array.isArray(payload.scope)) return payload.scope;
  if (Array.isArray(payload.scp)) return payload.scp;
  return [];
}

function sendJson(res, status, body, extraHeaders = {}) {
  const text = JSON.stringify(body);
  res.writeHead(status, { 'Content-Type': 'application/json', 'Content-Length': Buffer.byteLength(text), ...extraHeaders });
  res.end(text);
}

function challenge(res, status, error, description) {
  // RFC 6750 + RFC 9728: the challenge carries where the client can learn how to authorize.
  const www = `Bearer realm="RunningAI", error="${error}", error_description="${description}", ` +
    `resource_metadata="${PUBLIC_BASE_URL}${METADATA_PATH}"`;
  sendJson(res, status, { error, error_description: description }, { 'WWW-Authenticate': www });
}

function rpcError(res, id, code, message) {
  sendJson(res, 200, { jsonrpc: '2.0', id: id === undefined ? null : id, error: { code, message } });
}

/** Verifies the bearer token fully; returns the JWT payload or responds and returns null. */
async function authenticate(req, res) {
  const auth = String(req.headers.authorization || '');
  if (!auth.startsWith('Bearer ') || auth.length < 'Bearer '.length + 10) {
    log('WARN', 'auth rejected: no bearer token');
    challenge(res, 401, 'invalid_request', 'A bearer access token is required');
    return null;
  }
  let keySet;
  try {
    keySet = await jwks();
  } catch (e) {
    log('ERROR', 'auth unavailable: AS metadata/JWKS discovery failed');
    sendJson(res, 503, { error: 'authorization_unavailable',
      error_description: 'Token verification is temporarily unavailable' });
    return null;
  }
  let payload;
  try {
    ({ payload } = await jwtVerify(auth.slice('Bearer '.length), keySet, {
      issuer: ISSUER,
      audience: AUDIENCE,
      algorithms: ['RS256'],
      clockTolerance: '5s',
    }));
  } catch (e) {
    // jose folds every failure mode here: bad signature, tampering, exp, nbf, wrong iss/aud,
    // disallowed alg, unknown kid, malformed token. One uniform 401, no detail leakage.
    log('WARN', `auth rejected: ${e && e.code ? e.code : 'invalid token'}`);
    challenge(res, 401, 'invalid_token', 'The access token is missing, invalid or expired');
    return null;
  }
  if (!scopesOf(payload).includes(REQUIRED_SCOPE)) {
    log('WARN', 'auth rejected: missing required scope');
    challenge(res, 403, 'insufficient_scope', `Scope ${REQUIRED_SCOPE} is required`);
    return null;
  }
  if (payload.sub !== ALLOWED_SUB) {
    log('WARN', 'auth rejected: subject not on the allowlist');
    challenge(res, 403, 'insufficient_scope', 'This user is not permitted to use this resource');
    return null;
  }
  return payload;
}

async function proxy(res, rawBody, msg, grantedScopes = []) {
  let upstream;
  try {
    upstream = await fetch(TARGET_URL, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', Accept: 'application/json, text/event-stream' },
      body: rawBody,
    });
  } catch (e) {
    log('ERROR', 'backend unreachable');
    return sendJson(res, 502, { error: 'bad_gateway', error_description: 'The MCP backend is unreachable' });
  }
  const text = await upstream.text();
  if (msg.method === 'tools/list' && upstream.ok) {
    // Defense in depth, twice over: even if the backend ever listed more, only allowlisted tools
    // leave here - and only those THIS token's scopes actually permit, so a read-only connection
    // is not even shown a tool it could not call.
    try {
      const body = JSON.parse(text);
      if (body && body.result && Array.isArray(body.result.tools)) {
        body.result.tools = body.result.tools.filter((t) =>
          t && ALLOWED_TOOLS.has(t.name) && grantedScopes.includes(scopeFor(t.name)));
        return sendJson(res, upstream.status, body);
      }
    } catch (e) { /* fall through: pass the original text */ }
  }
  res.writeHead(upstream.status, { 'Content-Type': upstream.headers.get('content-type') || 'application/json' });
  res.end(text);
}

const server = http.createServer((req, res) => {
  const url = (req.url || '').split('?')[0];

  if (req.method === 'GET' && url === '/health') {
    return sendJson(res, 200, { status: 'ok', service: 'running-ai-mcp-gateway' });
  }
  // RFC 9728, openly served (identities only, no data): both the root form and the
  // path-appended form clients derive from a resource URL that has a path component.
  if (req.method === 'GET' && (url === METADATA_PATH || url === `${METADATA_PATH}/mcp`)) {
    return sendJson(res, 200, {
      resource: RESOURCE,
      authorization_servers: [ISSUER],
      scopes_supported: SCOPES_SUPPORTED,
      bearer_methods_supported: ['header'],
      resource_name: WRITE_TOOLS_ENABLED ? 'RunningAI MCP' : 'RunningAI MCP (read-only)',
    });
  }

  if (url !== '/mcp') {
    return sendJson(res, 404, { error: 'not_found' });
  }
  if (req.method !== 'POST') {
    res.writeHead(405, { Allow: 'POST', 'Content-Type': 'application/json' });
    return res.end(JSON.stringify({ error: 'method_not_allowed' }));
  }

  let body = '';
  let tooLarge = false;
  req.on('data', (chunk) => {
    body += chunk;
    if (body.length > 65536) { tooLarge = true; req.destroy(); }
  });
  req.on('end', async () => {
    if (tooLarge) return;
    try {
      const payload = await authenticate(req, res);
      if (!payload) return; // response already sent; backend untouched

      let msg;
      try {
        msg = JSON.parse(body);
      } catch (e) {
        return rpcError(res, null, -32700, 'Parse error');
      }
      if (Array.isArray(msg) || !msg || typeof msg !== 'object' || msg.jsonrpc !== '2.0') {
        return rpcError(res, msg && msg.id, -32600, 'Invalid Request');
      }
      const granted = scopesOf(payload);
      const method = typeof msg.method === 'string' ? msg.method : '';
      if (method.startsWith('notifications/')) {
        return proxy(res, body, msg, granted);
      }
      if (!ALLOWED_METHODS.has(method)) {
        log('WARN', `method refused: ${method || '(missing)'}`);
        return rpcError(res, msg.id, -32601, `Method not available through this gateway: ${method}`);
      }
      if (method === 'tools/call') {
        const name = msg.params && typeof msg.params.name === 'string' ? msg.params.name : '';
        if (!ALLOWED_TOOLS.has(name)) {
          log('WARN', `tool refused: ${name || '(missing name)'}`);
          return rpcError(res, msg.id, -32602,
            `Tool '${name}' is not available through this gateway`);
        }
        // Per-tool authorization, server side (Phase 7F-1). Checked here, BEFORE the backend is
        // touched: a token without the tool's scope causes zero backend traffic. The reply is an
        // HTTP 403 OAuth challenge naming the missing scope - not a JSON-RPC error - so the client
        // learns it must re-authorize rather than treating it as a tool-level failure.
        const needed = scopeFor(name);
        if (!granted.includes(needed)) {
          log('WARN', `tool refused: ${name} needs a scope this token does not carry`);
          return challenge(res, 403, 'insufficient_scope', `Scope ${needed} is required for ${name}`);
        }
        log('INFO', `tools/call ${name} authorized (scope ${needed})`);
      } else {
        log('INFO', `${method} authorized`);
      }
      return proxy(res, body, msg, granted);
    } catch (e) {
      log('ERROR', `unhandled: ${e && e.message}`);
      if (!res.headersSent) sendJson(res, 500, { error: 'internal_error' });
    }
  });
});

server.listen(PORT, HOST, () => {
  // The scope each enabled tool demands is printed at startup, so what this gateway will and will
  // not let a given token do is readable from the log rather than inferred from the code.
  const policy = [...ALLOWED_TOOLS].map((t) => `${t}=${scopeFor(t)}`).join('|');
  log('INFO', `GATEWAY_STARTED host=${HOST} port=${PORT} resource=${RESOURCE} ` +
    `baseScope=${REQUIRED_SCOPE} scopes=${SCOPES_SUPPORTED.join(',')} tools=${policy} target=local`);
});
