#!/usr/bin/env bash
# One-screen RunningAI status on Linux. Read-only: systemd state, connector /health, Spring
# /actuator/health and GET /api/v1/garmin/sync/status. No Garmin login, no activity fetch, no tokens.
# Exit code 0 when PostgreSQL, the connector and Spring are all up; 1 otherwise.
set -uo pipefail
# shellcheck source=lib.sh
. "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

all_up=1
row() { printf '%-16s %s\n' "$1" "$2"; }

unit_state() {
    if have systemctl; then systemctl is-active "$1" 2>/dev/null || true; else echo "no-systemd"; fi
}

pg="$(unit_state "$POSTGRES_UNIT")"
if [ "$pg" = active ]; then
    row PostgreSQL "active ($POSTGRES_UNIT.service)"
else
    row PostgreSQL "${pg:-unknown} ($POSTGRES_UNIT.service; with the Docker option check 'docker compose ps' instead)"
    all_up=0
fi

cs="$(unit_state running-ai-garmin-connector)"
if http_get "http://127.0.0.1:$CONNECTOR_PORT/health" | grep -q '"status":"UP"'; then
    row GarminConnector "UP 127.0.0.1:$CONNECTOR_PORT (systemd: ${cs:-unknown})"
else
    row GarminConnector "DOWN 127.0.0.1:$CONNECTOR_PORT (systemd: ${cs:-unknown})"; all_up=0
fi

ss="$(unit_state running-ai)"
spring_up=0
if http_get "http://127.0.0.1:$SPRING_PORT/actuator/health" | grep -q '"status":"UP"'; then
    spring_up=1
    row Spring "UP port $SPRING_PORT (systemd: ${ss:-unknown})"
else
    row Spring "DOWN port $SPRING_PORT (systemd: ${ss:-unknown})"; all_up=0
fi

if [ "$spring_up" = 1 ]; then
    body="$(http_get "http://127.0.0.1:$SPRING_PORT/api/v1/garmin/sync/status" || true)"
    if [ -z "$body" ]; then
        row GarminSync "UNKNOWN (status endpoint unreachable)"
    elif printf '%s' "$body" | grep -q '"initialized":true'; then
        row HighWater "$(printf '%s' "$body" | sed -n 's/.*"highWaterStartedAt":"\([^"]*\)".*/\1/p')"
        row LastSync "$(printf '%s' "$body" | sed -n 's/.*"lastSuccessfulSyncAt":"\([^"]*\)".*/\1/p')"
    else
        row LastSync "never (sync state not initialized)"
    fi
else
    row GarminSync "UNKNOWN (Spring is down)"
fi
row Logs "journalctl -u running-ai -u running-ai-garmin-connector"

[ "$all_up" = 1 ]
