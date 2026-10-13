// RunningAI internal trust boundary — token minting (Phase 7D-2).
//
// WHY THIS FILE EXISTS
// --------------------
// `@cloudflare/workers-oauth-provider` (the Cloudflare OAuth Provider Library) issues OPAQUE
// access tokens of the form `${userId}:${grantId}:${secret}`, validated by hashing the token and
// looking the hash up in the `OAUTH_KV` namespace. They are NOT JWTs, they are NOT signed, and
// the library publishes NO JWKS — verified by reading the shipped bundle, not assumed from docs:
//   dist/oauth-provider.js:4883  `const accessToken = \`${userId}:${grantId}:${generateRandomString(TOKEN_LENGTH)}\``
//   dist/oauth-provider.js:4489  `tokenData = await env.OAUTH_KV.get(\`token:${userId}:${grantId}:${id}\`, ...)`
// Its own docs/resource-servers.md says an off-Cloudflare resource server cannot validate them:
// validation is a Worker Service Binding RPC, there is no RFC 7662 introspection endpoint.
//
// The existing `tools/mcp-gateway` (Phase 7C-2b, 29/29 PASS) is an off-Cloudflare resource server
// that verifies an RS256 JWT against a JWKS. Those two facts do not meet. The resolution is NOT to
// weaken the gateway (explicitly forbidden) but to make the Worker a SECOND, explicit issuer: the
// Worker verifies the user's OAuth token with the library, and then mints a short-lived RS256 JWT
// of its own — signed, issuer-bound, audience-bound, scope-bound and subject-bound — for the one
// hop from the Worker to the local gateway. The gateway keeps verifying exactly what it always
// verified (signature / iss / aud / exp / nbf / alg / kid / scope / sub) with ZERO code change.
//
// The private key lives only in a Worker secret. The public half is served as JWKS by the Worker,
// which is also the issuer, so the gateway's existing metadata discovery finds it unchanged.
//
// Runs unchanged on workerd and on Node >= 20: WebCrypto + btoa only, no dependencies.

const encoder = new TextEncoder();

function base64url(bytes) {
  let binary = '';
  for (const b of bytes) binary += String.fromCharCode(b);
  return btoa(binary).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
}

function base64urlJson(value) {
  return base64url(encoder.encode(JSON.stringify(value)));
}

function requireString(value, name) {
  if (typeof value !== 'string' || !value.trim()) throw new TypeError(`${name} is required`);
  return value;
}

/** The public half of the signing key — the only part that ever leaves the Worker. */
export function publicJwkOf(privateJwk) {
  if (!privateJwk || privateJwk.kty !== 'RSA') throw new TypeError('an RSA private JWK is required');
  requireString(privateJwk.kid, 'kid');
  return { kty: 'RSA', n: privateJwk.n, e: privateJwk.e, kid: privateJwk.kid, alg: 'RS256', use: 'sig' };
}

export function jwksDocument(privateJwk) {
  return { keys: [publicJwkOf(privateJwk)] };
}

/**
 * The AS metadata document the gateway discovers. It carries identities only — issuer and the
 * JWKS location — never a credential, and is safe to serve openly (the gateway already serves an
 * equally open RFC 9728 document).
 */
export function authorizationServerMetadata(issuer, jwksUri) {
  return {
    issuer: requireString(issuer, 'issuer'),
    jwks_uri: requireString(jwksUri, 'jwksUri'),
    id_token_signing_alg_values_supported: ['RS256'],
    response_types_supported: [],
    grant_types_supported: [],
    // This issuer mints tokens only for the Worker's own internal hop; it has no public endpoints.
    authorization_endpoint: null,
    token_endpoint: null,
  };
}

// Importing an RSA key costs real CPU and the Workers free plan allows 10 ms per invocation, so
// the imported key is cached per isolate. Keyed by the JWK itself: a rotated secret never reuses
// a stale key.
let cachedJwkJson = null;
let cachedKey = null;

export async function importSigningKey(privateJwk) {
  const json = JSON.stringify(privateJwk);
  if (cachedJwkJson === json && cachedKey) return cachedKey;
  const material = { ...privateJwk, alg: 'RS256' };
  delete material.use;
  delete material.key_ops;
  const key = await crypto.subtle.importKey(
    'jwk', material, { name: 'RSASSA-PKCS1-v1_5', hash: 'SHA-256' }, false, ['sign'],
  );
  cachedJwkJson = json;
  cachedKey = key;
  return key;
}

function newJti() {
  const bytes = crypto.getRandomValues(new Uint8Array(12));
  return base64url(bytes);
}

/**
 * Mints the short-lived RS256 JWT for the Worker -> gateway hop.
 *
 * Every claim the gateway checks is set explicitly and from verified input: `subject` must be the
 * identity the OAuth layer actually authenticated (never a constant), `scope` the scope that
 * token actually carries. A short `ttlSeconds` keeps a leaked hop token near-worthless; it is not
 * a substitute for any of the other checks.
 */
export async function mintInternalToken({
  privateJwk, issuer, audience, subject, scope, ttlSeconds = 90, now = Date.now(),
}) {
  requireString(issuer, 'issuer');
  requireString(audience, 'audience');
  requireString(subject, 'subject');
  requireString(scope, 'scope');
  if (!Number.isFinite(ttlSeconds) || ttlSeconds <= 0 || ttlSeconds > 600) {
    throw new TypeError('ttlSeconds must be a positive number of at most 600');
  }
  const key = await importSigningKey(privateJwk);
  const issuedAt = Math.floor(now / 1000);
  const header = { alg: 'RS256', typ: 'JWT', kid: privateJwk.kid };
  const payload = {
    iss: issuer,
    aud: audience,
    sub: subject,
    scope,
    iat: issuedAt,
    nbf: issuedAt,
    exp: issuedAt + Math.floor(ttlSeconds),
    jti: newJti(),
  };
  const signingInput = `${base64urlJson(header)}.${base64urlJson(payload)}`;
  const signature = await crypto.subtle.sign('RSASSA-PKCS1-v1_5', key, encoder.encode(signingInput));
  return `${signingInput}.${base64url(new Uint8Array(signature))}`;
}
