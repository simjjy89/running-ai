# RunningAI Garmin connector

A small, localhost-only Python process that owns everything Garmin-specific that the
Spring server must not know: Garmin Connect login, MFA, the token store and read-only
transport. It hands Garmin activity-list items to the server **unchanged**; all
normalisation happens in the server's `GarminActivityMapper`.

```text
Garmin Connect ──► python-garminconnect 0.3.16 ──► garmin-connector ──► 127.0.0.1:8765 ──► Spring Boot
```

The connector never touches the RunningAI database.

## Requirements

- Python **3.12 or newer**
- `garminconnect==0.3.16` (pinned; anything below 0.3.5 is forbidden because of
  CVE-2026-54447), `fastapi`, `uvicorn` — see `requirements.txt`
- A Garmin Connect account. The library uses Garmin's unofficial mobile SSO flow; it can
  be rate limited or blocked by Garmin at any time.

## Setup

```powershell
cd tools\garmin-connector
py -3.12 -m venv .venv
.\.venv\Scripts\python.exe -m pip install -r requirements.txt
```

(Linux / Raspberry Pi: `python3.12 -m venv .venv && .venv/bin/pip install -r requirements.txt`.)
`.venv/` is git-ignored.

## Commands

```powershell
.\.venv\Scripts\python.exe -m garmin_connector login
.\.venv\Scripts\python.exe -m garmin_connector status
.\.venv\Scripts\python.exe -m garmin_connector activities --limit 3
.\.venv\Scripts\python.exe -m garmin_connector serve            # http://127.0.0.1:8765
```

### login

Interactive only. Prompts for the Garmin email and password (password is read with
`getpass`, never echoed) and, if Garmin asks, for the MFA one-time code in the same
terminal. Credentials are passed to the library once and not kept. On success the
library writes tokens to the token store (see below). There is **no** HTTP login
endpoint and no way to pass a password on the command line.

If Garmin answers with 401 / 403 / 429 or a Cloudflare challenge, the command fails
once and stops. Do not run it in a loop; wait and retry manually later.

### status

Reports whether the token store exists and, with a single read-only profile request,
whether Garmin still accepts it (`VALID`, `INVALID_OR_EXPIRED`, `MISSING`). Exit code
0 only when valid. The display name is masked.

### activities --limit N

Read-only diagnostic. Prints a count and one masked summary line per activity
(masked id, type key, `startTimeGMT`, `duration`, `distance`). It never prints the
raw JSON.

### serve

Runs the HTTP connector on **127.0.0.1** only (`--port`, default 8765). Binding to
other interfaces is intentionally not supported. Swagger / ReDoc / OpenAPI are disabled.

## HTTP API

| Endpoint | Response |
|----------|----------|
| `GET /health` | `{"status": "UP", "service": "garmin-connector"}` — process liveness only; Garmin login state never makes it DOWN |
| `GET /activities?limit=N` (1–100, default 20) | JSON **array** of Garmin activity-list items, newest first, exactly as returned by Garmin (`activityList` wrapper removed, nothing renamed) |

Errors are always `{"code": "...", "message": "..."}` and never contain tokens,
cookies or payloads:

| HTTP | code | meaning |
|------|------|---------|
| 400 | `INVALID_REQUEST` | bad `limit` |
| 401 | `GARMIN_AUTH_REQUIRED` | no valid token store — run `login` on this host |
| 403 | `GARMIN_FORBIDDEN` | Garmin refused the request |
| 429 | `GARMIN_RATE_LIMITED` | Garmin rate limit — the server must stop, not retry |
| 502 | `GARMIN_UPSTREAM_ERROR` | Garmin Connect unavailable / unexpected response shape |
| 500 | `GARMIN_CONNECTOR_ERROR` | connector bug |

The connector performs exactly one Garmin request per `/activities` call and never
retries 401 / 403 / 429 or re-logs-in on its own. Only the library's built-in DI token
refresh happens automatically.

## Token storage and security

- Tokens live in `~/.garminconnect/garmin_tokens.json` (library default; file mode
  0600 in a 0700 directory). Override the directory with `--tokenstore`, but keep it
  **outside** the repository. Never put tokens in `.env`, the database or a commit.
- The refresh token grants persistent account access: treat the file like a password.
  `logout` is not wrapped here; to revoke, delete the file and revoke access in your
  Garmin account security settings.
- Logs contain counts and status codes only — no credentials, tokens, cookies, GPS or
  raw payloads.
- The Spring server only knows the connector URL (`GARMIN_CONNECTOR_URL`).

## Tests

```powershell
.\.venv\Scripts\python.exe -m pip install -r requirements-dev.txt
.\.venv\Scripts\python.exe -m pytest
```

All tests use fakes; none contact Garmin or the network.
