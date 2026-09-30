# RunningAI on Raspberry Pi / Linux

Deployment artifacts for a systemd host (target: Raspberry Pi OS 64-bit, arm64). **Prepared and
statically validated only; nothing here has run on real hardware yet.** The Windows runtime
(`scripts/windows/`) is unrelated and untouched.

```text
Boot -> systemd -> PostgreSQL -> running-ai-garmin-connector.service -> running-ai.service -> Spring Garmin scheduler
```

| Concern | Owner |
|---------|-------|
| process lifecycle, restart, restart-storm limit, logs | **systemd** / journald (no PID files, no custom watchdog) |
| Garmin activity synchronisation and business logic | **Spring** (`RUNNING_AI_GARMIN_SCHEDULER_ENABLED=true`; do not add cron/timers that also sync) |
| persistent state | PostgreSQL |
| Garmin login, token store, read transport | Garmin connector (127.0.0.1 only; Spring never sees Garmin credentials) |

## Requirements

- Raspberry Pi OS 64-bit (arm64); any systemd distribution works.
- **Java 21** (`sudo apt install openjdk-21-jre-headless`). The Spring Boot jar is JVM bytecode: the same jar built on Windows runs on arm64.
- **Python 3.12+** for the connector. Python packages are platform specific (`curl_cffi` etc.), so the connector venv is always created **on the Pi** from `tools/garmin-connector/requirements.txt` (pins unchanged: `garminconnect==0.3.16`).
- PostgreSQL (see below), `curl`, working time sync (`timedatectl` shows `System clock synchronized: yes`).
- Storage: prefer an SSD over an SD card for PostgreSQL (write wear). Pick a JVM heap for your RAM, e.g. `JAVA_OPTS=-Xms128m -Xmx512m`.

## Layout

```text
source checkout (build machine or Pi):   ~/running-ai            (any path; only deploy-app.sh reads it)

/opt/running-ai/                          root-owned, world-readable: the service user cannot rewrite its own code
    app/running-ai.jar                    (+ running-ai.jar.previous, one generation kept)
    connector/garmin_connector/ ...       connector source
    connector/.venv/                      created on the Pi
    scripts/                              (optional: copy of deploy/linux/scripts)
/etc/running-ai/                          root:runningai 0750
    running-ai.env                        0600 root:root (systemd reads it as root; no secret in the repo)
    garmin-connector.env                  0600 root:root
/var/lib/running-ai/                      runningai 0750
    garmin-tokens/                        runningai 0700  <- Garmin token store (garmin_tokens.json, 0600)
    runtime/                              runningai 0750
logs: journald (no /var/log/running-ai, no extra rotation framework)
```

Root, config dir, state dir and user are configurable for the scripts with `RUNNINGAI_ROOT`, `RUNNINGAI_ETC`, `RUNNINGAI_STATE`, `RUNNINGAI_USER`, `RUNNINGAI_GROUP` (the unit files are rewritten accordingly by `install-runtime.sh`). The service user is `runningai` (system user, no login shell); services never run as root.

Never put the token store in the Git checkout, in `/opt/running-ai/app`, or in an env file.

## PostgreSQL: which option

| | A. native PostgreSQL (recommended for a personal Pi) | B. Docker PostgreSQL (fallback) |
|---|---|---|
| runtime | `postgresql.service` | Docker daemon + the repo's `docker-compose.yml` |
| pros | simplest boot chain, no Docker daemon, less RAM/CPU, fewer moving parts | same setup as development, easy to test/replace |
| cons | version comes from the distribution (Flyway/Postgres 15+ are fine) | Docker Desktop-style dependency, more resources |

Not final: choose at cut-over time. The units only *order* after `postgresql.service` (harmless if absent). For option B add `sudo systemctl edit running-ai` with
`[Unit]` `After=docker.service` `Wants=docker.service` and keep `restart: unless-stopped` on the container. Spring fails fast if the DB is not ready and `Restart=on-failure` retries (see limits below).

Native setup (choose your own password; never commit it):

```bash
sudo -u postgres psql -c "CREATE ROLE running_ai LOGIN PASSWORD '<choose-a-password>';"
sudo -u postgres psql -c "CREATE DATABASE running_ai OWNER running_ai;"
```

A fresh DB gets Flyway `V1`–`V4` applied automatically on first Spring start. Existing migrations must never be edited.

## Install

```bash
cd ~/running-ai/deploy/linux/scripts
./install-runtime.sh --dry-run                 # shows every action, needs no privileges
./install-runtime.sh --create-user             # sudo for the privileged steps; installs no packages, starts nothing
sudoedit /etc/running-ai/running-ai.env        # set DB_PASSWORD (and DB_URL if not local); keep SERVER_ADDRESS=127.0.0.1
./deploy-app.sh --connector                    # builds bootJar, installs it atomically, creates the venv, restarts, waits for health
```

`deploy-app.sh` options: `--dry-run`, `--jar PATH`, `--skip-build`, `--connector`, `--no-restart`. It stages the jar next to the target and renames it into place, keeping one previous jar for manual rollback (`sudo mv running-ai.jar.previous running-ai.jar && sudo systemctl restart running-ai`).

**Garmin login (once, interactive, as the service user; MFA is typed in this terminal):**

```bash
sudo -u runningai sh -c 'cd /opt/running-ai/connector && .venv/bin/python -m garmin_connector --tokenstore /var/lib/running-ai/garmin-tokens login'
```

Token migration options (decide at cut-over; nothing is copied by these scripts): (A) log in fresh on the Pi, or (B) manually copy `garmin_tokens.json` from the old host into that directory, owner `runningai`, mode 0600, directory 0700. Treat the file like a password and delete old copies.

Enable and start (order is handled by the units):

```bash
sudo systemctl enable --now running-ai-garmin-connector running-ai
```

## Operate

```bash
sudo systemctl restart running-ai-garmin-connector
sudo systemctl restart running-ai
sudo systemctl stop running-ai               # stop Spring first ...
sudo systemctl stop running-ai-garmin-connector   # ... then the connector
systemctl status running-ai running-ai-garmin-connector
journalctl -u running-ai -f
journalctl -u running-ai-garmin-connector -n 100
./status-running-ai.sh                       # PostgreSQL / connector / Spring / sync state (read-only, no Garmin call)
./validate-runtime.sh                        # host checks; add --static for the repository artifacts only
sudo systemctl reset-failed running-ai       # after hitting the start limit
```

Curl equivalents: `curl -s http://127.0.0.1:8765/health`, `curl -s http://127.0.0.1:8080/actuator/health`, `curl -s http://127.0.0.1:8080/api/v1/garmin/sync/status`.

## systemd behaviour

- **Order:** `running-ai` is `After=` PostgreSQL and the connector; it only `Wants=` the connector, so a connector restart never restarts Spring. Shutdown is the reverse (Spring first).
- **`After=` is ordering, not readiness.** Neither unit waits for the other to be *healthy*. Spring fails fast when the DB is down and `Restart=on-failure` (`RestartSec=30`) retries.
- **Restart storm limit:** `StartLimitIntervalSec=600`, `StartLimitBurst=3` per unit. After 3 starts in 10 minutes the unit stays failed until `systemctl reset-failed`. A slow first boot (PostgreSQL taking several minutes) can hit this; reset and start again.
- **Graceful stop:** default SIGTERM. Spring shuts down via its hooks (exit code 143 is declared a clean stop), uvicorn shuts down gracefully. `TimeoutStopSec` is 30 s (Spring) / 20 s (connector).
- **Garmin problems do not restart anything.** `GARMIN_AUTH_REQUIRED`, 403 and 429 are HTTP answers from a healthy connector process; the process keeps running and only a crash is restarted. Fix authentication by logging in again.
- **Hardening:** `NoNewPrivileges`, `PrivateTmp`, `ProtectSystem=full`, `ProtectHome=true`, `UMask=0077`; the connector's only writable path is `StateDirectory=running-ai/garmin-tokens` (0700). Because of `ProtectHome=true` a token store or venv under `/home` will not work.
- **Java:** the unit uses `/usr/bin/java`. If your Java 21 lives elsewhere, `install-runtime.sh` prints the `systemctl edit running-ai` override.

## Network and security

- The connector binds to 127.0.0.1 only. **Spring listens on all interfaces by default**, so the env example sets `SERVER_ADDRESS=127.0.0.1`; keep it. `POST /api/v1/garmin/sync` has no authentication: **do not expose this API to the LAN or internet** (no port forwarding, no reverse proxy) until authentication exists. No firewall rules are added by these scripts.
- Env files and the token store are owner-only; secrets live only in `/etc/running-ai` and `/var/lib/running-ai/garmin-tokens`, never in Git.
- Time: the database stores UTC instants; the Pi's local timezone only affects log timestamps. Keep NTP sync on (Garmin timestamps, scheduler).

## Migrating from Windows (decide later)

- **Option A – fresh DB:** start empty, Flyway creates V1–V4, the first sync bootstraps (first page only) and later syncs walk back through the overlap window. Older history is not re-imported and `garmin_sync_state` starts empty.
- **Option B – `pg_dump` / `pg_restore`:** carry over activities, raw payloads and the sync checkpoint (`garmin_sync_state`); use a PostgreSQL major version that can restore the dump and stop the Windows stack first so the checkpoint is consistent.
- Backups are not automated yet: add a scheduled `pg_dump` before relying on the Pi.

## Pi migration checklist

```text
[ ] Raspberry Pi OS 64-bit, network configured        [ ] time sync (NTP) enabled
[ ] Java 21          [ ] Python 3.12+          [ ] curl
[ ] PostgreSQL option chosen (A native / B Docker), role + database created
[ ] runningai user + directories (install-runtime.sh)
[ ] /etc/running-ai/*.env reviewed (DB_PASSWORD, SERVER_ADDRESS=127.0.0.1, scheduler flags)
[ ] deploy-app.sh --connector (jar + venv)            [ ] Garmin login or token migration
[ ] validate-runtime.sh has no FAIL
[ ] systemctl enable --now both units                 [ ] Flyway applied V1-V4 (journalctl -u running-ai)
[ ] connector /health UP    [ ] Spring /actuator/health UP    [ ] manual POST /api/v1/garmin/sync from localhost
[ ] scheduler tick observed (journalctl: "Garmin scheduled sync completed")
[ ] kill -9 test: process restarts after 30 s          [ ] reboot: everything comes back by itself
[ ] backup plan for PostgreSQL                         [ ] API not reachable from the LAN
```

## Known limitations

- Not validated on real hardware or on a systemd host yet (`RASPBERRY_PI_LIVE_VALIDATION_NOT_RUN`).
- `Restart=on-failure` recovers only exits. A process that is alive but unhealthy (HTTP DOWN) is not detected; use `status-running-ai.sh`, and add a health watchdog only if this happens in practice.
- No DB backup automation, no external API authentication, no scheduler history.
