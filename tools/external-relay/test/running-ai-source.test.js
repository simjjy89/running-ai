'use strict';

// Phase 8B (Workstream D): the relay's primary source is RunningAI's approved
// draft, with the pre-existing Intervals.icu path kept as the fallback.
//
// Each test spawns a disposable copy of THIS repo's server.js against a fake
// RunningAI backend on a throwaway port, with a throwaway token. WATCHFACE_RELAY_MOCK
// is deliberately NOT set here (unlike server.test.js): that escape hatch short-circuits
// resolveToday() before any source is consulted, which is exactly the code under test.
// Real Intervals.icu is never reachable either - the fallback is pointed at a closed
// port, so "the fallback ran" is observable as the relay's own upstream error rather
// than as a network call.
//
// The live production relay (port 17845, real token, real watch traffic) is never
// contacted.

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
        try { json = JSON.parse(body); } catch (e) { /* error paths are not all JSON */ }
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
    } catch (e) { /* not listening yet */ }
    if (Date.now() - start > deadlineMs) { throw new Error('relay did not become healthy in time'); }
    await new Promise((r) => setTimeout(r, 50));
  }
}

// A stand-in for Spring's GET /api/v1/today-workout. `respond` receives the
// parsed `date` query parameter and returns {status, body} or null for a 500.
async function fakeRunningAi(respond) {
  const requests = [];
  const server = http.createServer((req, res) => {
    const url = new URL(req.url, 'http://127.0.0.1');
    requests.push({ method: req.method, pathname: url.pathname, date: url.searchParams.get('date') });
    const answer = respond(url.searchParams.get('date'));
    if (!answer) {
      res.writeHead(503, { 'Content-Type': 'application/json' });
      return res.end('{"code":"DOWN"}');
    }
    const body = Buffer.from(JSON.stringify(answer), 'utf8');
    res.writeHead(200, { 'Content-Type': 'application/json', 'Content-Length': body.length });
    res.end(body);
  });
  await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve));
  return {
    url: `http://127.0.0.1:${server.address().port}`,
    requests,
    stop: () => new Promise((resolve) => server.close(resolve)),
  };
}

async function spawnRelay({ springBaseUrl }) {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'external-relay-ra-'));
  fs.copyFileSync(path.join(__dirname, '..', 'server.js'), path.join(dir, 'server.js'));
  fs.copyFileSync(path.join(__dirname, '..', 'workout-types.js'), path.join(dir, 'workout-types.js'));
  const token = crypto.randomBytes(24).toString('base64url');
  const port = 18900 + Math.floor(Math.random() * 400);
  fs.writeFileSync(
    path.join(dir, 'config.json'),
    JSON.stringify({
      schema_version: '1.0',
      port,
      // No caching: these tests are about which source answered, and a cache hit
      // would silently make the second test in a file read the first one's answer.
      cache_ttl_seconds: 0,
      rate_limit_per_minute: 200,
      spring_base_url: springBaseUrl,
      spring_timeout_ms: 2000,
    }),
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
    // No INTERVALS_ICU_API_KEY: the fallback must be unable to reach anything,
    // so a test that expects RunningAI to answer cannot pass by accident.
    env: { ...process.env, WATCHFACE_RELAY_MOCK: '', INTERVALS_ICU_API_KEY: '' },
    stdio: 'ignore',
  });
  await waitForHealth(port, 5000);
  return {
    port,
    token,
    todayWorkout: () => request(port, {
      method: 'GET',
      path: '/today-workout',
      headers: { Authorization: `Bearer ${token}` },
    }),
    stop: async () => {
      proc.kill();
      await new Promise((resolve) => proc.once('exit', resolve));
      fs.rmSync(dir, { recursive: true, force: true });
    },
  };
}

function plan(overrides) {
  return {
    date: null,                       // filled in per request by the fake
    status: 'HAS_PLAN',
    source: 'APPROVED_DRAFT',
    workoutType: 'EASY',
    totalDurationMinutes: 40,
    repetitions: null,
    approvedAt: '2026-10-15T00:00:00Z',
    published: false,
    publishedAt: null,
    stale: false,
    athleteToday: null,
    ...overrides,
  };
}

async function withRelay(respond, assertions) {
  const backend = await fakeRunningAi(respond);
  const relay = await spawnRelay({ springBaseUrl: backend.url });
  try {
    await assertions(relay, backend);
  } finally {
    await relay.stop();
    await backend.stop();
  }
}

test('an approved workout reaches the watch without needing to be published', async () => {
  await withRelay(
    (date) => plan({ date, athleteToday: date, published: false }),
    async (relay) => {
      const res = await relay.todayWorkout();
      assert.equal(res.status, 200);
      assert.equal(res.json.status, 'HAS_PLAN');
      assert.equal(res.json.label, 'EASY 40M');
    },
  );
});

// The gap this workstream exists to close. A REST day is never published to
// Intervals.icu by design, so the calendar-only relay could not tell "the coach
// prescribed rest" from "nobody has planned anything".
test('an approved REST day is reported as REST, which the Intervals-only path could never show', async () => {
  await withRelay(
    (date) => plan({ date, athleteToday: date, status: 'REST', workoutType: null, totalDurationMinutes: null }),
    async (relay) => {
      const res = await relay.todayWorkout();
      assert.equal(res.status, 200);
      assert.equal(res.json.status, 'REST');
      assert.equal(res.json.label, null);
    },
  );
});

test('an interval session is labelled with its repeat count', async () => {
  await withRelay(
    (date) => plan({ date, athleteToday: date, workoutType: 'INTERVAL', repetitions: 6, totalDurationMinutes: 60 }),
    async (relay) => {
      assert.equal((await relay.todayWorkout()).json.label, 'INTRVL 6X');
    },
  );
});

test('the wire contract keeps exactly its three fields', async () => {
  await withRelay(
    (date) => plan({ date, athleteToday: date }),
    async (relay) => {
      const res = await relay.todayWorkout();
      assert.deepEqual(Object.keys(res.json).sort(), ['date', 'label', 'status']);
    },
  );
});

test('the label never exceeds the watch slot or leaves the pixel font character set', async () => {
  await withRelay(
    (date) => plan({ date, athleteToday: date, workoutType: 'CROSS_TRAINING', totalDurationMinutes: 1200 }),
    async (relay) => {
      const label = (await relay.todayWorkout()).json.label;
      assert.ok(label.length <= 14, `label too long for the watch slot: ${label}`);
      assert.match(label, /^[0-9A-Z :+\-./%!]*$/);
    },
  );
});

// NO_PLAN from RunningAI means "RunningAI has decided nothing", which is not a
// decision - so the older sources still get their turn.
test('NO_PLAN is not treated as a decision and falls through to the older sources', async () => {
  await withRelay(
    (date) => plan({ date, athleteToday: date, status: 'NO_PLAN', source: 'NONE', workoutType: null }),
    async (relay) => {
      // No Intervals key and no legacy file on this machine, so the fallback fails
      // outright - which is the proof that it was reached at all.
      const res = await relay.todayWorkout();
      assert.equal(res.status, 502);
      assert.deepEqual(res.json, { status: 'UPSTREAM_ERROR' });
    },
  );
});

// An answer for another date must never be shown as today's.
test('a backend answer for the wrong date is discarded rather than displayed', async () => {
  await withRelay(
    () => plan({ date: '2000-01-01', athleteToday: '2000-01-01' }),
    async (relay) => {
      const res = await relay.todayWorkout();
      assert.equal(res.status, 502);          // discarded, then the fallback failed
    },
  );
});

test('a backend answer flagged stale is discarded rather than displayed', async () => {
  await withRelay(
    (date) => plan({ date, athleteToday: date, stale: true }),
    async (relay) => {
      assert.equal((await relay.todayWorkout()).status, 502);
    },
  );
});

// The watch must keep working when the backend is down, mid-deploy, or simply
// not running on this machine.
test('a backend failure degrades to the older sources instead of erroring out early', async () => {
  await withRelay(
    () => null,                                // the fake answers 503
    async (relay, backend) => {
      const res = await relay.todayWorkout();
      assert.equal(res.status, 502);           // the fallback was reached and itself failed
      assert.equal(backend.requests.length, 1, 'the backend is asked exactly once, with no retry');
    },
  );
});

test('an unreachable backend is stepped over, not retried', async () => {
  // Port 9 is the discard port: a connection here fails immediately.
  const relay = await spawnRelay({ springBaseUrl: 'http://127.0.0.1:9' });
  try {
    assert.equal((await relay.todayWorkout()).status, 502);
  } finally {
    await relay.stop();
  }
});

// The escape hatch that restores the exact pre-Phase-8B behaviour.
test('an empty spring_base_url disables the RunningAI source entirely', async () => {
  const backend = await fakeRunningAi((date) => plan({ date, athleteToday: date }));
  const relay = await spawnRelay({ springBaseUrl: '' });
  try {
    assert.equal((await relay.todayWorkout()).status, 502);
    assert.equal(backend.requests.length, 0, 'the backend must not be contacted at all');
  } finally {
    await relay.stop();
    await backend.stop();
  }
});

test('the relay only ever reads from the backend, and only the one endpoint', async () => {
  await withRelay(
    (date) => plan({ date, athleteToday: date }),
    async (relay, backend) => {
      await relay.todayWorkout();
      assert.equal(backend.requests.length, 1);
      assert.equal(backend.requests[0].method, 'GET');
      assert.equal(backend.requests[0].pathname, '/api/v1/today-workout');
      assert.ok(backend.requests[0].date, 'the date is always sent explicitly, never left to the backend');
    },
  );
});
