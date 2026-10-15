// RunningAI MCP Worker (Phase 7D-2) — the stable public HTTPS front door for ChatGPT.
//
// Roles, kept separate on purpose:
//   ChatGPT  --OAuth 2.1 (PKCE S256, DCR/CIMD)-->  THIS WORKER (authorization server + resource
//                                                  server; opaque tokens in KV, GitHub sign-in)
//   THIS WORKER  --short-lived RS256 JWT-->        tools/mcp-gateway on the main PC (unchanged)
//   gateway  --loopback-->                         dev Spring /mcp  --> read-only MCP tools
//
// Why two token systems: see `src/internal-jwt.js`. In short, the library's access tokens are
// opaque KV-backed strings that an off-Cloudflare resource server structurally cannot verify, so
// the Worker re-asserts the authenticated identity to the gateway as a signed JWT instead of the
// gateway being taught to trust anything unverified.
//
// What this address does NOT expose: nothing but `/mcp` is proxied anywhere, the origin URL is
// fixed in configuration, and `/api/...` or `/actuator/...` are not routes here (and are 404 at
// the gateway too). Anyone who finds the tunnel address directly still faces the gateway's full
// JWT verification, so bypassing this Worker buys nothing.
//
// Deploy: see README.md. Secrets live in `wrangler secret`, never in this file or in wrangler.jsonc.

import { AuthorizationError, OAuthProvider } from '@cloudflare/workers-oauth-provider';
import { handleMcpRequest } from './mcp-proxy.js';
import { authorizationServerMetadata, jwksDocument } from './internal-jwt.js';

// The BASE scope: every token must carry it to reach the resource at all.
const REQUIRED_SCOPE = 'runningai:read';
// The draft/approve lifecycle scope (Phase 7F-3B-2B). Separately requestable and separately
// revocable: a connection that only ever reads never has to be granted it.
const TRAIN_SCOPE = 'runningai:train';
// `runningai:publish` is deliberately ABSENT. The gateway's hard-coded policy gives that scope to
// publish_approved_draft alone, so as long as this Worker cannot issue it, no token reaching the
// gateway can ever authorize an external Intervals.icu write - whatever a client asks for.
const SCOPES_SUPPORTED = [REQUIRED_SCOPE, TRAIN_SCOPE, 'offline_access'];
// What may be re-asserted to the gateway in the hop token. `offline_access` is a refresh-token
// grant, not a resource permission, so it never travels onward.
const FORWARDABLE_SCOPES = [REQUIRED_SCOPE, TRAIN_SCOPE];
const GITHUB_OAUTH_BASE = 'https://github.com';
const GITHUB_API_BASE = 'https://api.github.com';
const USER_AGENT = 'RunningAI-MCP-Worker';

/**
 * What the CLIENT asked for, rendered for the log. This is the single fact that distinguishes
 * "the client requested read-only" from "the client sent no scope parameter at all and the base
 * scope was purely our own fallback": both produce the identical stored grant, and the authorize
 * transaction is encrypted at rest, so neither KV nor the grant can tell them apart afterwards.
 * Recognised scope names only - anything else is counted, never echoed into the log.
 */
function describeRequestedScopes(scope) {
  const requested = Array.isArray(scope) ? scope : [];
  if (requested.length === 0) return 'client sent NO scope parameter';
  const known = requested.filter((s) => SCOPES_SUPPORTED.includes(s));
  const unrecognised = requested.length - known.length;
  return `client requested scope [${known.join(' ')}]` + (unrecognised ? ` + ${unrecognised} unrecognised` : '');
}

/**
 * The upstream base URLs are overridable ONLY so `test/oauth-e2e-test.js` can run the whole flow
 * against a stub GitHub on loopback. Leave both unset in production — the defaults are the real
 * GitHub. An override may only be https, or http on loopback: a plain-http override pointing at
 * some other host would be a downgrade of the sign-in channel, so it is refused outright.
 */
function upstreamBase(env, name, fallback) {
  const raw = typeof env[name] === 'string' ? env[name].trim() : '';
  if (!raw) return fallback;
  const url = new URL(raw);
  const loopback = url.hostname === '127.0.0.1' || url.hostname === 'localhost';
  if (url.protocol !== 'https:' && !(url.protocol === 'http:' && loopback)) {
    throw new Error(`${name} must be https, or http on loopback`);
  }
  return raw.replace(/\/$/, '');
}

function log(level, message) {
  // Identities and statuses only. Tokens, headers and bodies never reach this function. The
  // redaction below (same pattern as the gateway) is defence in depth for the one case we do not
  // author ourselves: an error message thrown by a dependency.
  const safe = String(message).replace(/(authorization|token|secret|api[_-]?key|bearer)\s*[:=]\s*\S+/ig, '$1=[REDACTED]');
  console.log(`${new Date().toISOString()} ${level} ${safe}`);
}

function json(status, body, headers = {}) {
  return new Response(JSON.stringify(body), {
    status, headers: { 'Content-Type': 'application/json', ...headers },
  });
}

function required(env, name) {
  const value = env[name];
  if (typeof value !== 'string' || !value.trim()) {
    throw new Error(`missing required configuration ${name}`);
  }
  return value.trim();
}

/**
 * Configuration is resolved per request and FAILS CLOSED: a Worker has no startup phase to refuse
 * in, so a missing secret becomes a 500 for every request rather than a quietly weakened check.
 */
function configOf(env) {
  const publicBase = required(env, 'PUBLIC_BASE_URL').replace(/\/$/, '');
  let privateJwk;
  try {
    privateJwk = JSON.parse(required(env, 'HOP_SIGNING_JWK'));
  } catch (e) {
    throw new Error('HOP_SIGNING_JWK is not valid JSON');
  }
  if (!privateJwk.kid) throw new Error('HOP_SIGNING_JWK has no kid');
  const allowedGithubId = required(env, 'ALLOWED_GITHUB_ID');
  return {
    publicBase,
    privateJwk,
    issuer: `${publicBase}/internal/`,
    audience: `${publicBase}/mcp`,
    originUrl: required(env, 'ORIGIN_MCP_URL'),
    allowedGithubId,
    allowedUserId: `github|${allowedGithubId}`,
    allowedGithubLogin: required(env, 'ALLOWED_GITHUB_LOGIN'),
    requiredScope: REQUIRED_SCOPE,
    forwardableScopes: FORWARDABLE_SCOPES,
    tokenTtlSeconds: Number(env.HOP_TOKEN_TTL_SECONDS || 90),
    githubClientId: required(env, 'GITHUB_CLIENT_ID'),
    githubClientSecret: required(env, 'GITHUB_CLIENT_SECRET'),
    githubOauthBase: upstreamBase(env, 'GITHUB_OAUTH_BASE', GITHUB_OAUTH_BASE),
    githubApiBase: upstreamBase(env, 'GITHUB_API_BASE', GITHUB_API_BASE),
  };
}

// ---- the resource leg: POST /mcp ------------------------------------------------------------

const apiHandler = {
  async fetch(request, env, ctx) {
    let config;
    try {
      config = configOf(env);
    } catch (e) {
      log('ERROR', `configuration refused: ${e.message}`);
      return json(500, { error: 'server_misconfigured' });
    }
    return handleMcpRequest(request, { auth: ctx.auth, props: ctx.props, config, deps: { log } });
  },
};

// ---- the authorization leg: GitHub sign-in, one permitted person ------------------------------

async function startGithubSignIn(request, env, config) {
  const oauth = env.OAUTH_PROVIDER;
  const authRequest = await oauth.parseAuthRequest(request);
  log('INFO', `authorize: ${describeRequestedScopes(authRequest.scope)}`);
  const { state, headers } = await oauth.beginUpstream(authRequest, { data: {} });
  const target = new URL(`${config.githubOauthBase}/login/oauth/authorize`);
  target.searchParams.set('client_id', config.githubClientId);
  target.searchParams.set('redirect_uri', `${config.publicBase}/callback`);
  target.searchParams.set('scope', 'read:user'); // identity only — no repo, no write
  target.searchParams.set('state', state);
  target.searchParams.set('allow_signup', 'false');
  headers.set('Location', target.toString());
  log('INFO', 'authorize: redirecting to GitHub sign-in');
  return new Response(null, { status: 302, headers });
}

async function finishGithubSignIn(request, env, config) {
  const oauth = env.OAUTH_PROVIDER;
  const code = new URL(request.url).searchParams.get('code');
  if (!code) return json(400, { error: 'invalid_request', error_description: 'missing code' });

  const { request: authRequest, headers } = await oauth.finishUpstream(request);

  const tokenRes = await fetch(`${config.githubOauthBase}/login/oauth/access_token`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', Accept: 'application/json', 'User-Agent': USER_AGENT },
    body: JSON.stringify({
      client_id: config.githubClientId,
      client_secret: config.githubClientSecret,
      code,
      redirect_uri: `${config.publicBase}/callback`,
    }),
  });
  const tokenBody = await tokenRes.json().catch(() => ({}));
  if (!tokenRes.ok || !tokenBody.access_token) {
    log('WARN', 'sign-in failed: GitHub code exchange rejected');
    return json(401, { error: 'access_denied', error_description: 'GitHub sign-in failed' });
  }

  const userRes = await fetch(`${config.githubApiBase}/user`, {
    headers: {
      Authorization: `Bearer ${tokenBody.access_token}`,
      Accept: 'application/vnd.github+json',
      'User-Agent': USER_AGENT,
    },
  });
  const user = await userRes.json().catch(() => ({}));
  if (!userRes.ok || user.id === undefined) {
    log('WARN', 'sign-in failed: GitHub profile unreadable');
    return json(401, { error: 'access_denied', error_description: 'GitHub sign-in failed' });
  }

  // Single-user allowlist, checked on BOTH the stable numeric id and the login name. A GitHub
  // login can be renamed and reused by someone else; the numeric id cannot, so the id is the
  // authority and the login is a second, independent agreement check.
  if (String(user.id) !== config.allowedGithubId || String(user.login) !== config.allowedGithubLogin) {
    log('WARN', 'sign-in refused: GitHub identity is not the permitted owner');
    return json(403, { error: 'access_denied', error_description: 'This account is not permitted to use this connector' });
  }

  const requested = Array.isArray(authRequest.scope) ? authRequest.scope : [];
  const granted = requested.filter((s) => SCOPES_SUPPORTED.includes(s));
  if (!granted.includes(REQUIRED_SCOPE)) granted.push(REQUIRED_SCOPE);

  const { redirectTo } = await oauth.completeAuthorization({
    request: authRequest,
    userId: `github|${user.id}`,
    metadata: { label: String(user.login) },
    scope: granted,
    // Never store the GitHub token: this connector needs an identity, not GitHub access.
    props: { githubId: String(user.id), githubLogin: String(user.login) },
  });
  headers.set('Location', redirectTo);
  log('INFO', `sign-in approved for the permitted owner; granted scope [${granted.join(' ')}]`);
  return new Response(null, { status: 302, headers });
}

const defaultHandler = {
  async fetch(request, env, ctx) {
    const url = new URL(request.url);
    const path = url.pathname;

    if (path === '/health') return json(200, { status: 'ok', service: 'running-ai-mcp-worker' });

    let config;
    try {
      config = configOf(env);
    } catch (e) {
      log('ERROR', `configuration refused: ${e.message}`);
      return json(500, { error: 'server_misconfigured' });
    }

    // The internal issuer the GATEWAY discovers. Identities and a public key only.
    if (path === '/internal/.well-known/openid-configuration' ||
        path === '/internal/.well-known/oauth-authorization-server') {
      return json(200, authorizationServerMetadata(config.issuer, `${config.publicBase}/internal/jwks.json`));
    }
    if (path === '/internal/jwks.json') {
      return json(200, jwksDocument(config.privateJwk), { 'Cache-Control': 'public, max-age=300' });
    }

    try {
      if (path === '/authorize') return await startGithubSignIn(request, env, config);
      if (path === '/callback') return await finishGithubSignIn(request, env, config);
    } catch (e) {
      // An OAuth-level problem (bad response_type, bad scope, …) must go back to the client as a
      // standard error redirect, carrying `state` and `iss`, or the connector only sees an opaque
      // failure. The library hands us a ready-made redirect, but only when it has validated the
      // redirect URI — without one, rendering locally is the safe answer (never invent a target).
      if (e instanceof AuthorizationError && e.redirectTo) {
        log('WARN', `authorize refused: ${e.code} (redirecting the client)`);
        return new Response(null, { status: 302, headers: { Location: e.redirectTo } });
      }
      log('ERROR', `sign-in error: ${e && e.message}`);
      return json(400, {
        error: (e instanceof AuthorizationError && e.code) || 'invalid_request',
        error_description: (e instanceof AuthorizationError && e.description) || undefined,
      });
    }

    return json(404, { error: 'not_found' });
  },
};

// The provider needs the public URL, which only exists in `env`, so it is built on first request
// and reused for the life of the isolate.
let provider = null;

function buildProvider(env) {
  const publicBase = required(env, 'PUBLIC_BASE_URL').replace(/\/$/, '');
  return new OAuthProvider({
    apiRoute: '/mcp',
    apiHandler,
    defaultHandler,
    authorizeEndpoint: '/authorize',
    tokenEndpoint: '/oauth/token',
    clientRegistrationEndpoint: '/register',   // DCR (RFC 7591)
    clientIdMetadataDocumentEnabled: true,     // CIMD — ChatGPT's preferred registration
    scopesSupported: SCOPES_SUPPORTED,
    // What the protected-resource metadata advertises, i.e. what a client learns it may ask for
    // (Phase 7F-3B-2B adds the train scope so ChatGPT can request it at re-authorization). The
    // library treats this as advertisement and challenge content, NOT as a per-token requirement -
    // it never compares a granted token against this list - so every already-issued read-only
    // token keeps working unchanged, and `offline_access` stays out because it is a refresh grant,
    // not a resource permission. Enforcement is elsewhere and unchanged: handleMcpRequest demands
    // REQUIRED_SCOPE, and the gateway demands the right scope per tool.
    requiredScopes: [REQUIRED_SCOPE, TRAIN_SCOPE],
    accessTokenTTL: 3600,
    resourceMetadata: {
      resource: `${publicBase}/mcp`,
      authorization_servers: [publicBase],
      // No `scopes_supported` here: the library requires the scope list to live in
      // `requiredScopes` above and refuses to start if both are set.
      bearer_methods_supported: ['header'],
      resource_name: 'RunningAI MCP',
    },
  });
}

export default {
  async fetch(request, env, ctx) {
    try {
      if (!provider) provider = buildProvider(env);
    } catch (e) {
      log('ERROR', `configuration refused: ${e.message}`);
      return json(500, { error: 'server_misconfigured' });
    }
    return provider.fetch(request, env, ctx);
  },
};
