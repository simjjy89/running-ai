// Generates the RS256 key pair for the Worker -> gateway hop (Phase 7D-2).
//
//   node scripts/generate-signing-key.mjs
//
// Prints the PRIVATE JWK (one line) to stdout and the public half to stderr. The private JWK goes
// into a Worker secret and nowhere else:
//
//   npx wrangler secret put HOP_SIGNING_JWK      # paste the single stdout line
//
// Never commit the private JWK, never paste it into wrangler.jsonc, never put it in a work order.
// Rotating is just running this again and re-putting the secret; the gateway picks the new key up
// from the Worker's JWKS automatically (jose refetches on an unknown `kid`).

import { webcrypto } from 'node:crypto';

const { publicKey, privateKey } = await webcrypto.subtle.generateKey(
  { name: 'RSASSA-PKCS1-v1_5', modulusLength: 2048, publicExponent: new Uint8Array([1, 0, 1]), hash: 'SHA-256' },
  true,
  ['sign', 'verify'],
);

const privateJwk = await webcrypto.subtle.exportKey('jwk', privateKey);
const publicJwk = await webcrypto.subtle.exportKey('jwk', publicKey);

// A stable, non-secret key id derived from the public modulus, so a rotation always changes it.
const digest = await webcrypto.subtle.digest('SHA-256', new TextEncoder().encode(publicJwk.n));
const kid = Buffer.from(digest).toString('hex').slice(0, 16);

privateJwk.kid = kid;
privateJwk.alg = 'RS256';
publicJwk.kid = kid;
publicJwk.alg = 'RS256';
publicJwk.use = 'sig';

process.stderr.write(`kid: ${kid}\npublic JWK (served at /internal/jwks.json, not a secret):\n`);
process.stderr.write(`${JSON.stringify({ kty: 'RSA', n: publicJwk.n, e: publicJwk.e, kid, alg: 'RS256', use: 'sig' })}\n\n`);
process.stderr.write('private JWK follows on stdout — paste it into `wrangler secret put HOP_SIGNING_JWK`:\n');
process.stdout.write(`${JSON.stringify(privateJwk)}\n`);
