'use strict';

// RUNNER ARCADE watch face relay.
//
// Small, standalone, read-only HTTP service: the one job it does is answer
// "what is today's scheduled run?" for the watch face, by querying
// Intervals.icu server-side so the Intervals.icu API key never has to leave
// this machine (and never reaches the watch build, a log file, or a capture).
//
// Deliberately its own process, separate from bridge/ (which accepts
// *inbound* training commands over a different port and a different auth
// scheme) - this module only reads, and only one thing.
//
// Auth: a single long-lived bearer token in secrets/watch-token.json, created
// by generate-token.js. This token is NOT the Intervals.icu API key - it only
// authorizes a GET of today's already-summarized plan, nothing else - but it
// is the credential the watch PRG embeds, so it is readable by anyone who
// decompiles the PRG. Rotate it (generate-token.js) if the build is ever
// shared or the token leaks; nothing else needs to change on this server to
// rotate it.

const crypto = require('crypto');
const fs = require('fs');
const http = require('http');
const https = require('https');
const path = require('path');

const ROOT = __dirname;
const CONFIG = JSON.parse(fs.readFileSync(path.join(ROOT, 'config.json'), 'utf8').replace(/^\uFEFF/, ''));
const { labelForEvent } = require('./workout-types');

const PORT = Number(CONFIG.port || 17845);
const HOST = '127.0.0.1'; // never bind anything but loopback; cloudflared fronts this
const CACHE_TTL_MS = Number(CONFIG.cache_ttl_seconds || 240) * 1000;
const RATE_LIMIT_PER_MINUTE = Number(CONFIG.rate_limit_per_minute || 20);
const LOG_FILE = path.join(ROOT, 'logs', 'relay.log');
const TODAY_PLAN_FILE = 'C:\\running-ai\\state\\today_plan.json';

const tokenPath = path.join(ROOT, 'secrets', 'watch-token.json');
if (!fs.existsSync(tokenPath)) {
  throw new Error('No secrets/watch-token.json - run generate-token.js first');
}
const WATCH_TOKEN = JSON.parse(fs.readFileSync(tokenPath, 'utf8')).token;
if (!WATCH_TOKEN || WATCH_TOKEN.length < 20) {
  throw new Error('watch-token.json token is missing or too short');
}

function log(level, message) {
  try {
    fs.mkdirSync(path.dirname(LOG_FILE), { recursive: true });
    const safe = String(message).replace(/(authorization|token|api[_-]?key)\s*[:=]\s*\S+/ig, '$1=[REDACTED]');
    fs.appendFileSync(LOG_FILE, `${new Date().toISOString()} [${level}] ${safe}\n`, 'utf8');
  } catch (e) { /* logging must never crash the request */ }
}

function reply(res, status, payload) {
  const body = Buffer.from(JSON.stringify(payload), 'utf8');
  res.writeHead(status, {
    'Content-Type': 'application/json; charset=utf-8',
    'Content-Length': body.length,
    'Cache-Control': 'no-store',
  });
  res.end(body);
}

// ---- Asia/Seoul "today", with no dependency on the server's own TZ setting.
// Korea has no DST, so a fixed +09:00 offset is exact, not an approximation.
function seoulDateString(d) {
  const shifted = new Date(d.getTime() + 9 * 3600 * 1000);
  return shifted.toISOString().slice(0, 10);
}

// ---- Rate limiting, one bucket per IP per minute, same shape as bridge/transports/http.js.
const rateBuckets = new Map();
function rateCheck(ip) {
  const minute = Math.floor(Date.now() / 60000);
  const key = `${ip}|${minute}`;
  const count = (rateBuckets.get(key) || 0) + 1;
  rateBuckets.set(key, count);
  for (const k of rateBuckets.keys()) { if (!k.endsWith(`|${minute}`)) { rateBuckets.delete(k); } }
  return count <= RATE_LIMIT_PER_MINUTE;
}

function authenticate(req) {
  const auth = String(req.headers.authorization || '');
  if (!auth.startsWith('Bearer ')) { return false; }
  const supplied = Buffer.from(auth.slice('Bearer '.length));
  const expected = Buffer.from(WATCH_TOKEN);
  if (supplied.length !== expected.length) { return false; }
  return crypto.timingSafeEqual(supplied, expected);
}

// ---- Intervals.icu, read-only. The API key lives only in this process's
// environment - never in a response, a log line, or anything sent downstream.
function intervalsGet(urlPath) {
  const apiKey = process.env.INTERVALS_ICU_API_KEY;
  if (!apiKey) { return Promise.reject(new Error('INTERVALS_ICU_API_KEY not set in this process environment')); }
  const auth = Buffer.from(`API_KEY:${apiKey}`).toString('base64');
  return new Promise((resolve, reject) => {
    const req = https.request({
      hostname: 'intervals.icu',
      path: urlPath,
      method: 'GET',
      headers: { Authorization: `Basic ${auth}` },
      timeout: 10000,
    }, (res) => {
      let chunks = [];
      res.on('data', (c) => chunks.push(c));
      res.on('end', () => {
        if (res.statusCode !== 200) { return reject(new Error(`intervals.icu HTTP ${res.statusCode}`)); }
        try { resolve(JSON.parse(Buffer.concat(chunks).toString('utf8'))); }
        catch (e) { reject(new Error('intervals.icu returned non-JSON')); }
      });
    });
    req.on('error', reject);
    req.on('timeout', () => req.destroy(new Error('intervals.icu request timed out')));
    req.end();
  });
}

function readExplicitPlan(dateStr) {
  try {
    const raw = fs.readFileSync(TODAY_PLAN_FILE, 'utf8').replace(/^\uFEFF/, '');
    const plan = JSON.parse(raw);
    if (String(plan.date) !== dateStr) { return null; }
    return plan;
  } catch (e) { return null; }
}

// ---- The one piece of business logic: resolve today's plan to {status, label}.
// status is one of HAS_PLAN / REST / NO_PLAN. Never invents a plan the sources
// do not carry, and an explicit REST decision (from the existing daily
// training pipeline's state file) always wins over an absence of events,
// because "no event" is also what a not-yet-generated day looks like.
async function resolveToday(dateStr) {
  // Validation-only escape hatch: lets this session prove the HAS_PLAN rendering path on
  // the actual watch face without a working Intervals.icu key (see the validation report -
  // the configured key is currently rejected with 401 by Intervals.icu itself, unrelated to
  // this relay or the watch build). Never set in normal operation; start.ps1 does not set it.
  if (process.env.WATCHFACE_RELAY_MOCK) {
    return { status: 'HAS_PLAN', label: labelForEvent({ name: '인터벌 6x800m', moving_time: 3000 }) };
  }
  const explicit = readExplicitPlan(dateStr);
  if (explicit && String(explicit.decision).toUpperCase() === 'REST') {
    return { status: 'REST', label: null };
  }
  const events = await intervalsGet(`/api/v1/athlete/0/events?oldest=${dateStr}&newest=${dateStr}&category=WORKOUT`);
  const runs = (Array.isArray(events) ? events : []).filter((e) => /Run|Ride/.test(String(e && e.type)));
  if (runs.length === 0) { return { status: 'NO_PLAN', label: null }; }
  const label = labelForEvent(runs[0]);
  if (!label) { return { status: 'NO_PLAN', label: null }; }
  return { status: 'HAS_PLAN', label: label };
}

let cache = { date: null, at: 0, result: null };

async function todayWorkout() {
  const dateStr = seoulDateString(new Date());
  if (cache.date === dateStr && (Date.now() - cache.at) < CACHE_TTL_MS) {
    return { date: dateStr, ...cache.result };
  }
  const result = await resolveToday(dateStr);
  cache = { date: dateStr, at: Date.now(), result };
  return { date: dateStr, ...result };
}

const server = http.createServer((req, res) => {
  const ip = req.socket.remoteAddress || 'unknown';
  if (!rateCheck(ip)) { return reply(res, 429, { status: 'RATE_LIMITED' }); }

  if (req.method === 'GET' && req.url === '/health') {
    return reply(res, 200, { status: 'ok' });
  }

  if (req.method === 'GET' && req.url === '/today-workout') {
    if (!authenticate(req)) {
      log('WARN', `AUTH_FAILED ip=${ip}`);
      return reply(res, 401, { status: 'AUTH_FAILED' });
    }
    todayWorkout()
      .then((data) => { log('INFO', `TODAY_WORKOUT_OK status=${data.status}`); reply(res, 200, data); })
      .catch((err) => { log('ERROR', `TODAY_WORKOUT_FAILED error=${err.message}`); reply(res, 502, { status: 'UPSTREAM_ERROR' }); });
    return;
  }

  reply(res, 404, { status: 'NOT_FOUND' });
});

server.requestTimeout = 15000;
server.headersTimeout = 10000;
server.listen(PORT, HOST, () => log('INFO', `RELAY_STARTED host=${HOST} port=${PORT}`));

process.on('SIGTERM', () => server.close(() => process.exit(0)));
process.on('SIGINT', () => server.close(() => process.exit(0)));
