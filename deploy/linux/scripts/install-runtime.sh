#!/usr/bin/env bash
# Prepares a Linux/Raspberry Pi host for RunningAI: checks dependencies, creates the directory
# layout and (optionally) the service user, installs the systemd units and env templates.
#
# It never installs packages, never starts services, never touches Garmin tokens, and never
# overwrites an existing env file. Steps that need root use sudo; --dry-run needs no privileges.
#
#   install-runtime.sh [--dry-run] [--create-user] [--enable]
#     --dry-run      print every action, change nothing
#     --create-user  create the system user/group if missing (nologin shell)
#     --enable       "systemctl enable" both units (they are still not started)
set -euo pipefail
# shellcheck source=lib.sh
. "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

DRY_RUN=0
CREATE_USER=0
ENABLE=0
while [ $# -gt 0 ]; do
    case "$1" in
        --dry-run) DRY_RUN=1 ;;
        --create-user) CREATE_USER=1 ;;
        --enable) ENABLE=1 ;;
        -h|--help) sed -n '2,12p' "${BASH_SOURCE[0]}"; exit 0 ;;
        *) die "unknown option: $1" ;;
    esac
    shift
done
export DRY_RUN

problems=0
info "== Dependency check (nothing is installed) =="
jm="$(java_major)"
if [ -z "$jm" ]; then
    warn "Java not found. Install JDK 21 (Raspberry Pi OS: sudo apt install openjdk-21-jre-headless)"; problems=1
elif [ "$jm" -lt 21 ]; then
    warn "Java $jm found, Java 21 is required"; problems=1
else
    info "Java $jm OK"
fi
py="$(find_python)"
if [ -z "$py" ]; then warn "Python >= 3.12 not found (needed for the Garmin connector venv)"; problems=1; else info "Python OK ($py)"; fi
if have systemctl; then info "systemd OK"; else warn "systemctl not found: these units need a systemd host"; problems=1; fi
if have psql; then info "PostgreSQL client found"; else info "psql not found (fine if PostgreSQL runs in Docker or on another host)"; fi
if have curl; then info "curl OK"; else warn "curl not found (needed by the status/deploy health checks)"; fi

java_path="$(command -v java || true)"
if [ -n "$java_path" ] && [ "$java_path" != /usr/bin/java ]; then
    real="$(readlink -f "$java_path" 2>/dev/null || echo "$java_path")"
    info "NOTE: java resolves to $real, but running-ai.service uses /usr/bin/java. If /usr/bin/java is not Java 21, override it:"
    info "      sudo systemctl edit running-ai    # add: [Service] / ExecStart= / ExecStart=$real \$JAVA_OPTS -jar ${RUNNINGAI_ROOT}/app/running-ai.jar"
fi

info "== Layout (root=$RUNNINGAI_ROOT etc=$RUNNINGAI_ETC state=$RUNNINGAI_STATE user=$RUNNINGAI_USER) =="
if [ "$DRY_RUN" != 1 ] && [ "$(id -u)" -ne 0 ] && ! have sudo; then die "run as root or install sudo (or use --dry-run)"; fi

if ! id "$RUNNINGAI_USER" >/dev/null 2>&1; then
    if [ "$CREATE_USER" = 1 ]; then
        run useradd --system --user-group --no-create-home --home-dir "$RUNNINGAI_STATE" --shell /usr/sbin/nologin "$RUNNINGAI_USER"
    else
        warn "user '$RUNNINGAI_USER' does not exist; re-run with --create-user (services must not run as root)"; problems=1
    fi
fi

# Application/connector trees are root-owned and read-only for the service user (it cannot rewrite its own code).
for d in "$RUNNINGAI_ROOT" "$RUNNINGAI_ROOT/app" "$RUNNINGAI_ROOT/connector" "$RUNNINGAI_ROOT/scripts"; do
    run install -d -m 0755 -o root -g root "$d"
done
run install -d -m 0750 -o root -g "$RUNNINGAI_GROUP" "$RUNNINGAI_ETC"
run install -d -m 0750 -o "$RUNNINGAI_USER" -g "$RUNNINGAI_GROUP" "$RUNNINGAI_STATE"
run install -d -m 0750 -o "$RUNNINGAI_USER" -g "$RUNNINGAI_GROUP" "$RUNNINGAI_STATE/runtime"
run install -d -m 0700 -o "$RUNNINGAI_USER" -g "$RUNNINGAI_GROUP" "$RUNNINGAI_STATE/garmin-tokens"
info "(logs go to journald; /var/log/running-ai is intentionally not created)"

# Env files: root-only 0600 (systemd reads them as root before dropping privileges). Never overwritten.
for name in running-ai garmin-connector; do
    target="$RUNNINGAI_ETC/$name.env"
    if [ -e "$target" ]; then
        info "keeping existing $target"
    else
        run install -m 0600 -o root -g root "$DEPLOY_DIR/env/$name.env.example" "$target"
        info "created $target from the example: review and edit it"
    fi
done

info "== systemd units =="
for unit in running-ai-garmin-connector.service running-ai.service; do
    tmp="$(mktemp)"
    render_unit "$DEPLOY_DIR/systemd/$unit" > "$tmp"
    run install -m 0644 -o root -g root "$tmp" "$SYSTEMD_DIR/$unit"
    rm -f "$tmp"
done
run systemctl daemon-reload
if [ "$ENABLE" = 1 ]; then run systemctl enable running-ai-garmin-connector.service running-ai.service; fi

info "== Next steps =="
info "1. edit $RUNNINGAI_ETC/running-ai.env (DB_PASSWORD) and create the database/user (see deploy/linux/README.md)"
info "2. deploy-app.sh --connector     (installs the jar and the connector venv)"
info "3. log in to Garmin once, interactively, as $RUNNINGAI_USER (see README); then: sudo systemctl enable --now running-ai-garmin-connector running-ai"
info "4. validate-runtime.sh, then status-running-ai.sh"
if [ "$problems" != 0 ]; then warn "some prerequisites are missing (see above)"; exit 2; fi
