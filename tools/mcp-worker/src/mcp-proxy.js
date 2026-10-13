// RunningAI MCP proxy handler (Phase 7D-2) — the Worker's resource-server leg.
//
// Reached ONLY through `OAuthProvider`'s apiRoute, i.e. only after the library has verified the
// user's opaque access token against KV. This handler adds the checks the library cannot make for
// us (which scope, which person), then hands the request to the local gateway over an explicitly
// minted, short-lived RS256 JWT.
//
// What it deliberately does NOT do: it does not re-implement the MCP method allowlist, the tool
// allowlist, the `tools/list` filtering or the `server/discover` handling. Those live in
// `tools/mcp-gateway` (Phase 7C-2b/7C-3, 29/29 PASS, live-smoked against ChatGPT's real probe
// order) and that gateway sits on the only path to Spring. Duplicating them here would create two
// allowlists that can silently drift — so the gateway stays the single authority, and every
// request, including an authenticated one, still has to get past it.
//
// Secrets never reach a log line: no token, no Authorization header, no request or response body.

const MAX_BODY_BYTES = 65536; // same cap as the gateway

function json(status, body, extraHeaders = {}) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json', ...extraHeaders },
  });
}

function wwwAuthenticate(resource, error, description) {
  return `Bearer realm="RunningAI", error="${error}", error_description="${description}", ` +
    `resource_metadata="${resource.replace(/\/mcp$/, '')}/.well-known/oauth-protected-resource/mcp"`;
}

/**
 * @param request  the inbound Request (already authenticated by OAuthProvider)
 * @param auth     ctx.auth — the verified token (scope[], userId, ...)
 * @param props    ctx.props — what completeAuthorization() stored (githubId, githubLogin)
 * @param config   { requiredScope, allowedUserId, originUrl, issuer, audience, privateJwk,
 *                   tokenTtlSeconds }
 * @param deps     { fetchImpl, now, log } — injected so this is testable off-Cloudflare
 */
export async function handleMcpRequest(request, { auth, props, config, deps = {} }) {
  const fetchImpl = deps.fetchImpl || fetch;
  const now = deps.now || Date.now;
  const log = deps.log || (() => {});

  const url = new URL(request.url);
  if (url.pathname !== '/mcp') return json(404, { error: 'not_found' });
  if (request.method !== 'POST') {
    return json(405, { error: 'method_not_allowed' }, { Allow: 'POST' });
  }

  const scopes = Array.isArray(auth && auth.scope) ? auth.scope : [];
  if (!scopes.includes(config.requiredScope)) {
    log('WARN', 'mcp rejected: missing required scope');
    return json(403, { error: 'insufficient_scope', error_description: `Scope ${config.requiredScope} is required` },
      { 'WWW-Authenticate': wwwAuthenticate(config.audience, 'insufficient_scope', 'Insufficient scope') });
  }

  // Two independent identity checks against the same allowlist: the grant's subject as the
  // library recorded it, and the GitHub identity captured at sign-in. A token minted for anyone
  // else never reaches the mint call, and the gateway checks `sub` a third time.
  const subject = auth && typeof auth.userId === 'string' ? auth.userId : '';
  const githubId = props && props.githubId !== undefined ? String(props.githubId) : '';
  if (!subject || subject !== config.allowedUserId || `github|${githubId}` !== config.allowedUserId) {
    log('WARN', 'mcp rejected: subject not on the allowlist');
    return json(403, { error: 'access_denied', error_description: 'This user is not permitted to use this resource' });
  }

  const body = await request.text();
  if (body.length > MAX_BODY_BYTES) {
    log('WARN', 'mcp rejected: body too large');
    return json(413, { error: 'payload_too_large' });
  }

  let hopToken;
  try {
    hopToken = await mintHopToken(config, subject, now());
  } catch (e) {
    log('ERROR', 'hop token could not be minted');
    return json(500, { error: 'internal_error' });
  }

  let upstream;
  try {
    upstream = await fetchImpl(config.originUrl, {
      method: 'POST',
      headers: {
        Authorization: `Bearer ${hopToken}`,
        'Content-Type': 'application/json',
        Accept: request.headers.get('accept') || 'application/json, text/event-stream',
      },
      body,
    });
  } catch (e) {
    log('ERROR', 'origin unreachable');
    return json(502, { error: 'bad_gateway', error_description: 'The RunningAI MCP origin is unreachable' });
  }

  log('INFO', `mcp forwarded status=${upstream.status}`);
  return new Response(upstream.body, {
    status: upstream.status,
    headers: { 'Content-Type': upstream.headers.get('content-type') || 'application/json' },
  });
}

// Imported lazily so this module stays dependency-free for tests that stub the mint.
async function mintHopToken(config, subject, now) {
  const { mintInternalToken } = await import('./internal-jwt.js');
  return mintInternalToken({
    privateJwk: config.privateJwk,
    issuer: config.issuer,
    audience: config.audience,
    // `subject` is the identity the OAuth layer authenticated (`github|<numeric id>`), already
    // checked against the allowlist above — never a constant and never client-supplied.
    subject,
    // Least privilege: the hop token carries ONLY the read scope that was just verified, even if
    // the user's token also carries others (offline_access).
    scope: config.requiredScope,
    ttlSeconds: config.tokenTtlSeconds || 90,
    now,
  });
}
