# shellcheck shell=bash
# Shared settings and helpers for the RunningAI Linux scripts. Source it; do not execute.
# Every path/user below is an overridable default (export the variable before running a script).

RUNNINGAI_ROOT="${RUNNINGAI_ROOT:-/opt/running-ai}"        # app/, connector/, scripts/
RUNNINGAI_ETC="${RUNNINGAI_ETC:-/etc/running-ai}"          # running-ai.env, garmin-connector.env
RUNNINGAI_STATE="${RUNNINGAI_STATE:-/var/lib/running-ai}"  # garmin-tokens/, runtime/
RUNNINGAI_USER="${RUNNINGAI_USER:-runningai}"
RUNNINGAI_GROUP="${RUNNINGAI_GROUP:-$RUNNINGAI_USER}"
SYSTEMD_DIR="${SYSTEMD_DIR:-/etc/systemd/system}"
POSTGRES_UNIT="${POSTGRES_UNIT:-postgresql}"               # differs by distribution / Docker option
CONNECTOR_PORT="${CONNECTOR_PORT:-8765}"
SPRING_PORT="${SPRING_PORT:-8080}"

# Defaults baked into the unit files; render_unit rewrites them for a non-default layout.
_UNIT_DEFAULT_ROOT=/opt/running-ai
_UNIT_DEFAULT_ETC=/etc/running-ai
_UNIT_DEFAULT_STATE=/var/lib/running-ai
_UNIT_DEFAULT_USER=runningai

DEPLOY_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"   # <repo>/deploy/linux
REPO_ROOT="$(cd "$DEPLOY_DIR/../.." && pwd)"

info() { printf '%s\n' "$*"; }
warn() { printf 'WARN: %s\n' "$*" >&2; }
err()  { printf 'ERROR: %s\n' "$*" >&2; }
die()  { err "$*"; exit 1; }
have() { command -v "$1" >/dev/null 2>&1; }

# Runs a command with root privileges: directly as root, via sudo otherwise.
priv() {
    if [ "$(id -u)" -eq 0 ]; then "$@"
    elif have sudo; then sudo "$@"
    else die "root privileges are required for: $* (run as root or install sudo)"
    fi
}

# Runs (or, when DRY_RUN=1, only prints) a privileged command.
run() {
    if [ "${DRY_RUN:-0}" = 1 ]; then printf '+ %s\n' "$*"; else priv "$@"; fi
}

# Major version of the Java found on PATH (empty if none).
java_major() {
    local bin="${1:-java}"
    have "$bin" || return 0
    "$bin" -version 2>&1 | sed -n 's/.*version "\([0-9][0-9]*\).*/\1/p' | head -n 1
}

# Prints the first python >= 3.12 interpreter name found on PATH (empty if none).
find_python() {
    local c v
    for c in python3.13 python3.12 python3; do
        have "$c" || continue
        v="$("$c" -c 'import sys; print(1 if sys.version_info >= (3, 12) else 0)' 2>/dev/null || echo 0)"
        if [ "$v" = 1 ]; then printf '%s\n' "$c"; return 0; fi
    done
    return 0
}

# Prints a unit file with the default paths/user replaced by the configured layout.
render_unit() {
    sed -e "s#${_UNIT_DEFAULT_ROOT}#${RUNNINGAI_ROOT}#g" \
        -e "s#${_UNIT_DEFAULT_ETC}#${RUNNINGAI_ETC}#g" \
        -e "s#${_UNIT_DEFAULT_STATE}#${RUNNINGAI_STATE}#g" \
        -e "s#^User=${_UNIT_DEFAULT_USER}\$#User=${RUNNINGAI_USER}#" \
        -e "s#^Group=${_UNIT_DEFAULT_USER}\$#Group=${RUNNINGAI_GROUP}#" "$1"
}

# HTTP GET returning only the body; empty output (and non-zero status) on any failure.
http_get() { curl -fsS --max-time 5 "$1" 2>/dev/null; }

# Waits until a URL's body matches a grep pattern. wait_for URL PATTERN TIMEOUT_SECONDS
wait_for() {
    local url="$1" pattern="$2" timeout="${3:-120}" waited=0
    while [ "$waited" -lt "$timeout" ]; do
        if http_get "$url" | grep -q "$pattern"; then return 0; fi
        sleep 3
        waited=$((waited + 3))
    done
    return 1
}
