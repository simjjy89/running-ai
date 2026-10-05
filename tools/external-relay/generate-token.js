'use strict';

// Generates the watch-scoped bearer token and writes it straight to
// secrets/watch-token.json - never to stdout, so it never lands in a
// terminal scrollback or a tool log. Re-run this (and rebuild+reinstall the
// watch face with the new value) to rotate the token.

const crypto = require('crypto');
const fs = require('fs');
const path = require('path');

const dir = path.join(__dirname, 'secrets');
fs.mkdirSync(dir, { recursive: true });
const token = crypto.randomBytes(24).toString('base64url');
const file = path.join(dir, 'watch-token.json');
fs.writeFileSync(file, JSON.stringify({ token, generated_at: new Date().toISOString() }, null, 2), 'utf8');
console.log(`Token written to ${file} (not printed).`);
