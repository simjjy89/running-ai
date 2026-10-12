// RunningAI MCP PoC server (Phase 7C-1). TEST_ONLY: proves the ChatGPT <-> RunningAI
// connection path (Secure MCP Tunnel -> local MCP over Streamable HTTP) with FAKE data.
//
// Hard guarantees, by construction:
//  - Binds 127.0.0.1 ONLY (hard-coded, not configurable): the public leg is the OpenAI
//    tunnel-client's outbound connection, never an inbound port.
//  - Exactly ONE tool, ping_running_ai, which returns a fixed, fake payload. There is no
//    database driver, no outbound HTTP call of any kind (this file never imports a client
//    and never calls fetch/http.request), no file read beyond startup, no Garmin, no
//    Intervals.icu, no Spring proxying. A denylisted or unknown tool name is rejected
//    server-side with a JSON-RPC error - not merely hidden from tools/list.
//  - No secret ever enters a response or a log line. The optional bearer token (see below)
//    is compared, never printed.
//
// Transport: MCP Streamable HTTP, stateless (one JSON-RPC message per POST /mcp, JSON
// response; no session, no SSE stream) - the same mode the Spring server uses (Phase 6C),
// and sufficient for initialize / tools/list / tools/call round trips.
//
// Optional auth: if RUNNING_AI_MCP_POC_TOKEN is set, every /mcp request must carry
// "Authorization: Bearer <token>" (timing-safe compare). Default off: behind tunnel-client
// the server is loopback-only and the tunnel is authenticated by the OpenAI runtime API key;
// the token mode exists so the 401 path itself is testable (work order STEP 4-4).

'use strict';

const http = require('http');
const crypto = require('crypto');

const HOST = '127.0.0.1'; // never configurable - see header comment
const PORT = Number(process.env.RUNNING_AI_MCP_POC_PORT || 17901);
const TOKEN = process.env.RUNNING_AI_MCP_POC_TOKEN || '';

const SERVER_INFO = { name: 'running-ai-mcp-poc', title: 'RunningAI MCP PoC', version: '0.1.0' };
const SUPPORTED_PROTOCOL_VERSIONS = ['2025-06-18', '2025-03-26', '2024-11-05'];
const LATEST_PROTOCOL_VERSION = SUPPORTED_PROTOCOL_VERSIONS[0];

// Write/operational tool names that must be REJECTED server-side if ever called here, so the
// separation from the Spring MCP server is enforced, not just implied by absence (STEP 3).
const DENIED_TOOL_NAMES = new Set(['publish_workout']);

const PING_TOOL = {
  name: 'ping_running_ai',
  title: 'Ping RunningAI (test only)',
  description: 'Connection test for the RunningAI MCP integration. Returns a fixed, fake ' +
    'payload and touches no real data: no database, no Garmin, no Intervals.icu, no workout ' +
    'can be read or written through this tool.',
  inputSchema: { type: 'object', properties: {}, required: [], additionalProperties: false },
  annotations: {
    title: 'Ping RunningAI (test only)',
    readOnlyHint: true,       // genuinely read-only: static response, zero side effects
    destructiveHint: false,
    idempotentHint: true,
    openWorldHint: false,
  },
};

const PING_RESULT = {
  success: true,
  service: 'RunningAI MCP PoC',
  mode: 'TEST_ONLY',
  message: 'RunningAI MCP connection verified',
};

function log(level, message) {
  // Defensive redaction, same pattern as tools/external-relay: nothing that looks like a
  // credential is ever written, even by mistake.
  const safe = String(message).replace(/(authorization|token|api[_-]?key|bearer)\s*[:=]\s*\S+/ig, '$1=[REDACTED]');
  process.stdout.write(`${new Date().toISOString()} ${level} ${safe}\n`);
}

function timingSafeEqual(a, b) {
  const ba = Buffer.from(String(a));
  const bb = Buffer.from(String(b));
  if (ba.length !== bb.length) { return false; }
  return crypto.timingSafeEqual(ba, bb);
}

function sendJson(res, status, body) {
  const text = JSON.stringify(body);
  res.writeHead(status, { 'Content-Type': 'application/json', 'Content-Length': Buffer.byteLength(text) });
  res.end(text);
}

function rpcResult(res, id, result) {
  sendJson(res, 200, { jsonrpc: '2.0', id, result });
}

function rpcError(res, id, code, message) {
  sendJson(res, 200, { jsonrpc: '2.0', id: id === undefined ? null : id, error: { code, message } });
}

function handleRpc(res, msg) {
  const { id, method, params } = msg;
  const isNotification = id === undefined || id === null;

  if (typeof method !== 'string') {
    return rpcError(res, id, -32600, 'Invalid Request: method is required');
  }

  if (method.startsWith('notifications/')) {
    // notifications/initialized etc.: acknowledged, no body (stateless server keeps no state).
    res.writeHead(202);
    return res.end();
  }
  if (isNotification) {
    res.writeHead(202);
    return res.end();
  }

  switch (method) {
    case 'initialize': {
      const requested = params && typeof params.protocolVersion === 'string' ? params.protocolVersion : '';
      const protocolVersion = SUPPORTED_PROTOCOL_VERSIONS.includes(requested) ? requested : LATEST_PROTOCOL_VERSION;
      log('INFO', `MCP initialize: requestedProtocolVersion=${requested || '(none)'} -> ${protocolVersion}`);
      return rpcResult(res, id, {
        protocolVersion,
        capabilities: { tools: { listChanged: false } },
        serverInfo: SERVER_INFO,
        instructions: 'TEST_ONLY connection-verification server for RunningAI (Phase 7C-1). ' +
          'It serves exactly one tool, ping_running_ai, with fixed fake data. No real athlete ' +
          'data is reachable here and no workout can be created, modified or published.',
      });
    }
    case 'ping':
      return rpcResult(res, id, {});
    case 'tools/list':
      log('INFO', 'MCP tools/list');
      return rpcResult(res, id, { tools: [PING_TOOL] });
    case 'tools/call': {
      const name = params && typeof params.name === 'string' ? params.name : '';
      if (name === PING_TOOL.name) {
        log('INFO', `MCP tools/call: ${name}`);
        return rpcResult(res, id, {
          content: [{ type: 'text', text: JSON.stringify(PING_RESULT) }],
          structuredContent: PING_RESULT,
          isError: false,
        });
      }
      if (DENIED_TOOL_NAMES.has(name)) {
        // Explicit server-side refusal (STEP 3): a write tool is not merely unlisted here, a
        // forced call is rejected before anything else could happen - and nothing else exists
        // in this process for it to reach anyway.
        log('WARN', `MCP tools/call REFUSED write tool: ${name}`);
        return rpcError(res, id, -32602,
          `Tool '${name}' is a write/operational tool and is not available on this read-only ` +
          'PoC server. This server cannot create, modify or publish workouts.');
      }
      log('WARN', `MCP tools/call unknown tool: ${name || '(missing name)'}`);
      return rpcError(res, id, -32602, `Unknown tool: ${name || '(missing name)'}`);
    }
    default:
      return rpcError(res, id, -32601, `Method not found: ${method}`);
  }
}

const server = http.createServer((req, res) => {
  const url = (req.url || '').split('?')[0];

  if (req.method === 'GET' && url === '/health') {
    return sendJson(res, 200, { status: 'ok', service: SERVER_INFO.name, mode: 'TEST_ONLY' });
  }

  if (url !== '/mcp') {
    // No other path exists: this server is not a proxy and serves no REST API (STEP 3).
    return sendJson(res, 404, { error: 'not found' });
  }
  if (req.method !== 'POST') {
    res.writeHead(405, { Allow: 'POST', 'Content-Type': 'application/json' });
    return res.end(JSON.stringify({ error: 'method not allowed: stateless MCP accepts POST only' }));
  }

  if (TOKEN) {
    const auth = String(req.headers.authorization || '');
    const ok = auth.startsWith('Bearer ') && timingSafeEqual(auth.slice('Bearer '.length), TOKEN);
    if (!ok) {
      log('WARN', 'MCP request rejected: missing or invalid bearer token');
      res.writeHead(401, { 'WWW-Authenticate': 'Bearer', 'Content-Type': 'application/json' });
      return res.end(JSON.stringify({ error: 'unauthorized' }));
    }
  }

  let body = '';
  let tooLarge = false;
  req.on('data', (chunk) => {
    body += chunk;
    if (body.length > 65536) { tooLarge = true; req.destroy(); }
  });
  req.on('end', () => {
    if (tooLarge) { return; }
    let msg;
    try {
      msg = JSON.parse(body);
    } catch (e) {
      return rpcError(res, null, -32700, 'Parse error');
    }
    if (Array.isArray(msg)) {
      // JSON-RPC batching was removed from the MCP spec (2025-06-18); refuse rather than guess.
      return rpcError(res, null, -32600, 'Invalid Request: batch messages are not supported');
    }
    if (!msg || typeof msg !== 'object' || msg.jsonrpc !== '2.0') {
      return rpcError(res, msg && msg.id, -32600, 'Invalid Request: not a JSON-RPC 2.0 message');
    }
    try {
      handleRpc(res, msg);
    } catch (e) {
      log('ERROR', `Unhandled error: ${e && e.message}`);
      rpcError(res, msg.id, -32603, 'Internal error');
    }
  });
});

server.listen(PORT, HOST, () => {
  log('INFO', `MCP_POC_STARTED host=${HOST} port=${PORT} auth=${TOKEN ? 'bearer' : 'off'} mode=TEST_ONLY`);
});
