'use strict';

// Black-box HTTP characterization tests for server.js, locked in ahead of the
// C:\running-ai\watchface-relay -> tools/external-relay migration (Phase 6I-1).
//
// Each test uses a disposable spawned instance of THIS repo's server.js
// (copied fresh into an OS temp dir so the suite always exercises exactly the
// committed bytes), on a test-only port, with a freshly generated disposable
// token (never the real production token, never printed/logged/committed)
// and WATCHFACE_RELAY_MOCK=1 (the server's own existing escape hatch, so
// this suite never calls real Intervals.icu and never touches the real
// C:\running-ai\state\today_plan.json). The live production relay (port
// 17845, real token, real watch traffic) is never contacted by this suite.

const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const http = require('node:http');
const crypto = require('node:crypto');
const { spawn } = require('node:child_process');

function request(port, options) {
  return new Promise((resolve, reject) => {
    const req = http.request({ host: '127.0.0.1', port, ...options }, (res) => {
      const chunks = [];
      res.on('data', (c) => chunks.push(c));
      res.on('end', () => {
        const body = Buffer.concat(chunks).toString('utf8');
        let json = null;
        try { json = JSON.parse(body); } catch (e) { /* not all responses are JSON-parseable in error paths */ }
        resolve({ status: res.statusCode, json, body });
      });
    });
    req.on('error', reject);
    req.end();
  });
}

async function waitForHealth(port, deadlineMs) {
  const start = Date.now();
  for (;;) {
    try {
      const res = await request(port, { method: 'GET', path: '/health' });
      if (res.status === 200) { return; }
    } catch (e) { /* server not listening yet */ }
    if (Date.now() - start > deadlineMs) { throw new Error('relay did not become healthy in time'); }
    await new Promise((r) => setTimeout(r, 50));
  }
}

// Spawns a disposable instance of the real server.js/workout-types.js in its
// own temp dir, with its own token/port/rate-limit. Caller must stop() it.
async function spawnRelay({ port, rateLimitPerMinute, token }) {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'external-relay-test-'));
  fs.copyFileSync(path.join(__dirname, '..', 'server.js'), path.join(dir, 'server.js'));
  fs.copyFileSync(path.join(__dirname, '..', 'workout-types.js'), path.join(dir, 'workout-types.js'));
  fs.writeFileSync(
    path.join(dir, 'config.json'),
    JSON.stringify({ schema_version: '1.0', port, cache_ttl_seconds: 240, rate_limit_per_minute: rateLimitPerMinute }),
    'utf8',
  );
  fs.mkdirSync(path.join(dir, 'secrets'), { recursive: true });
  fs.writeFileSync(
    path.join(dir, 'secrets', 'watch-token.json'),
    JSON.stringify({ token, generated_at: new Date().toISOString() }),
    'utf8',
  );
  const proc = spawn(process.execPath, ['server.js'], {
    cwd: dir,
    env: { ...process.env, WATCHFACE_RELAY_MOCK: '1' },
    stdio: 'ignore',
  });
  await waitForHealth(port, 5000);
  return {
    port,
    token,
    stop: async () => {
      proc.kill();
      await new Promise((resolve) => proc.once('exit', resolve));
      fs.rmSync(dir, { recursive: true, force: true });
    },
  };
}

let relay;

test.before(async () => {
  // Generous limit (well above the handful of requests the functional tests
  // below make) - the dedicated rate-limit test below spawns its own
  // isolated instance so the two concerns never share a request bucket.
  relay = await spawnRelay({ port: 18845, rateLimitPerMinute: 20, token: crypto.randomBytes(24).toString('base64url') });
});

test.after(async () => {
  if (relay) { await relay.stop(); }
});

test('GET /health returns 200 ok with no auth required', async () => {
  const res = await request(relay.port, { method: 'GET', path: '/health' });
  assert.equal(res.status, 200);
  assert.deepEqual(res.json, { status: 'ok' });
});

test('GET /today-workout with no Authorization header is rejected', async () => {
  const res = await request(relay.port, { method: 'GET', path: '/today-workout' });
  assert.equal(res.status, 401);
  assert.deepEqual(res.json, { status: 'AUTH_FAILED' });
});

test('GET /today-workout with the wrong token is rejected', async () => {
  const res = await request(relay.port, {
    method: 'GET',
    path: '/today-workout',
    headers: { Authorization: 'Bearer not-the-real-token-xxxxxxxxxxxx' },
  });
  assert.equal(res.status, 401);
  assert.deepEqual(res.json, { status: 'AUTH_FAILED' });
});

test('GET /today-workout with a non-Bearer scheme is rejected', async () => {
  const res = await request(relay.port, {
    method: 'GET',
    path: '/today-workout',
    headers: { Authorization: `Basic ${relay.token}` },
  });
  assert.equal(res.status, 401);
});

test('GET /today-workout with the correct token returns the mocked HAS_PLAN payload', async () => {
  const res = await request(relay.port, {
    method: 'GET',
    path: '/today-workout',
    headers: { Authorization: `Bearer ${relay.token}` },
  });
  assert.equal(res.status, 200);
  assert.equal(res.json.status, 'HAS_PLAN');
  assert.equal(typeof res.json.date, 'string');
  assert.equal(typeof res.json.label, 'string');
});

test('unknown paths return 404 NOT_FOUND', async () => {
  const res = await request(relay.port, { method: 'GET', path: '/something-else' });
  assert.equal(res.status, 404);
  assert.deepEqual(res.json, { status: 'NOT_FOUND' });
});

test('POST to /today-workout (wrong method) falls through to 404, never routes to the handler', async () => {
  const res = await request(relay.port, { method: 'POST', path: '/today-workout' });
  assert.equal(res.status, 404);
});

test('rate limiting kicks in after the configured per-minute threshold, from the same loopback IP', async () => {
  const isolated = await spawnRelay({ port: 18846, rateLimitPerMinute: 3, token: crypto.randomBytes(24).toString('base64url') });
  try {
    const results = [];
    for (let i = 0; i < 6; i += 1) {
      results.push(await request(isolated.port, { method: 'GET', path: '/health' }));
    }
    const limitedResults = results.filter((r) => r.status === 429);
    assert.ok(limitedResults.length > 0, 'expected at least one 429 RATE_LIMITED once the configured threshold (3/min) is exceeded');
    assert.deepEqual(limitedResults[0].json, { status: 'RATE_LIMITED' });
  } finally {
    await isolated.stop();
  }
});
