#!/usr/bin/env bash
# Validates RunningAI Linux artifacts without starting anything.
#
#   validate-runtime.sh --static   repository artifacts only (unit files, env examples, scripts):
#                                  runs on any machine with bash, no install needed
#   validate-runtime.sh            an installed host (Java/Python/systemd, directories, permissions,
#                                  env files, jar, connector venv, installed units)
#
# Prints PASS / WARN / FAIL lines, never prints a secret value, exit code 1 if any FAIL.
set -uo pipefail
# shellcheck source=lib.sh
. "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

MODE=host
case "${1:-}" in
    --static) MODE=static ;;
    ""|--host) MODE=host ;;
    -h|--help) sed -n '2,10p' "${BASH_SOURCE[0]}"; exit 0 ;;
    *) die "unknown option: $1" ;;
esac

fails=0
warns=0
pass() { printf 'PASS  %s\n' "$1"; }
fail() { printf 'FAIL  %s\n' "$1"; fails=$((fails + 1)); }
soft() { printf 'WARN  %s\n' "$1"; warns=$((warns + 1)); }
check() { local name="$1"; shift; if "$@" >/dev/null 2>&1; then pass "$name"; else fail "$name"; fi; }

# code_lines FILE : the file without comment lines
code_lines() { grep -v '^[[:space:]]*#' "$1"; }
# mode_of PATH : octal permission bits (empty when unavailable)
mode_of() { stat -c '%a' "$1" 2>/dev/null || stat -f '%Lp' "$1" 2>/dev/null || true; }
# owner_only MODE : true when group/other have no access (last two digits are 0)
owner_only() { [ -n "$1" ] && [ "${1: -2}" = "00" ]; }

UNITS="$DEPLOY_DIR/systemd/running-ai-garmin-connector.service $DEPLOY_DIR/systemd/running-ai.service"

static_checks() {
    info "== Static checks: $DEPLOY_DIR =="
    for u in $UNITS; do
        n="$(basename "$u")"
        [ -f "$u" ] || { fail "$n exists"; continue; }
        check "$n: runs as a non-root User" bash -c "code_lines() { grep -v '^[[:space:]]*#' \"\$1\"; }; code_lines '$u' | grep -Eq '^User=[a-z_][a-z0-9_-]*\$' && ! code_lines '$u' | grep -Eq '^User=root\$'"
        check "$n: Restart=on-failure with RestartSec" bash -c "grep -Eq '^Restart=on-failure\$' '$u' && grep -Eq '^RestartSec=[0-9]+' '$u'"
        check "$n: StartLimitIntervalSec/Burst live in [Unit]" bash -c "awk '/^\\[/{s=\$0} /^StartLimitIntervalSec=/{a=s} /^StartLimitBurst=/{b=s} END{exit !(a==\"[Unit]\" && b==\"[Unit]\")}' '$u'"
        check "$n: mandatory EnvironmentFile (no leading -)" bash -c "grep -Eq '^EnvironmentFile=/' '$u'"
        check "$n: explicit WorkingDirectory" bash -c "grep -Eq '^WorkingDirectory=/' '$u'"
        check "$n: TimeoutStopSec set, default SIGTERM (no KillSignal)" bash -c "grep -Eq '^TimeoutStopSec=[0-9]+' '$u' && ! grep -Eq '^KillSignal=' '$u'"
        check "$n: no systemd watchdog / notify protocol" bash -c "! grep -Eq '^(WatchdogSec|NotifyAccess)=' '$u'"
        check "$n: hardening that is safe for its paths" bash -c "grep -Eq '^NoNewPrivileges=true\$' '$u' && grep -Eq '^PrivateTmp=true\$' '$u' && grep -Eq '^UMask=0077\$' '$u'"
        check "$n: no gradle bootRun / Windows paths / home paths / 0.0.0.0" bash -c "! grep -v '^[[:space:]]*#' '$u' | grep -Eiq 'bootRun|[A-Za-z]:\\\\|/home/|0\\.0\\.0\\.0'"
    done
    check "connector unit: 127.0.0.1-only connector, token store under StateDirectory 0700" bash -c "grep -q 'garmin_connector' '$DEPLOY_DIR/systemd/running-ai-garmin-connector.service' && grep -Eq '^StateDirectoryMode=0700\$' '$DEPLOY_DIR/systemd/running-ai-garmin-connector.service' && ! grep -q -- '--host' '$DEPLOY_DIR/systemd/running-ai-garmin-connector.service'"
    check "Spring unit: java -jar (not bootRun), 143 is a clean stop" bash -c "grep -Eq '^ExecStart=/usr/bin/java .* -jar /' '$DEPLOY_DIR/systemd/running-ai.service' && grep -Eq '^SuccessExitStatus=143\$' '$DEPLOY_DIR/systemd/running-ai.service'"
    check "Spring unit: ordered after the connector and PostgreSQL, connector only Wants= (no Requires=)" bash -c "s='$DEPLOY_DIR/systemd/running-ai.service'; grep -E '^After=' \$s | grep -q running-ai-garmin-connector.service && grep -E '^After=' \$s | grep -q postgresql.service && grep -Eq '^Wants=.*running-ai-garmin-connector.service' \$s && ! grep -Eq '^(Requires|BindsTo)=' \$s"

    for e in "$DEPLOY_DIR"/env/*.env.example; do
        n="$(basename "$e")"
        # Any key that can carry a secret must be empty in the example.
        check "$n: secret-looking keys are empty" bash -c "! grep -v '^[[:space:]]*#' '$e' | grep -Ei '^[A-Z_]*(PASSWORD|TOKEN|SECRET|KEY|EMAIL)[A-Z_]*=.+'"
        check "$n: no export / shell expansion" bash -c "! grep -v '^[[:space:]]*#' '$e' | grep -Eq '^export |\\$\\('"
    done
    check "running-ai.env.example: loopback bind, connector URL, scheduler enabled" bash -c "e='$DEPLOY_DIR/env/running-ai.env.example'; grep -Eq '^SERVER_ADDRESS=127\\.0\\.0\\.1\$' \$e && grep -Eq '^GARMIN_CONNECTOR_URL=http://127\\.0\\.0\\.1:' \$e && grep -Eq '^RUNNING_AI_GARMIN_SCHEDULER_ENABLED=true\$' \$e && ! grep -Eq '^GARMIN_(USERNAME|PASSWORD)' \$e"
    check "garmin-connector.env.example: only a port" bash -c "grep -Eq '^GARMIN_CONNECTOR_PORT=[0-9]+\$' '$DEPLOY_DIR/env/garmin-connector.env.example'"

    for s in "$DEPLOY_DIR"/scripts/*.sh; do
        n="$(basename "$s")"
        check "$n: bash -n syntax" bash -n "$s"
        check "$n: no Windows paths, user homes or Garmin credential variables" bash -c "! grep -v '^[[:space:]]*#' '$s' | grep -Eiq '[A-Za-z]:\\\\|/home/[a-z]|GARMIN_(USERNAME|PASSWORD)'"
        [ "$n" = lib.sh ] || check "$n: set -euo pipefail (or -uo for read-only checks)" bash -c "grep -Eq '^set -(euo|uo) pipefail\$' '$s'"
    done
    check "scripts never call Garmin or POST /sync, never remove Docker volumes" bash -c "! cat '$DEPLOY_DIR'/scripts/lib.sh '$DEPLOY_DIR'/scripts/install-runtime.sh '$DEPLOY_DIR'/scripts/deploy-app.sh '$DEPLOY_DIR'/scripts/status-running-ai.sh | grep -v '^[[:space:]]*#' | grep -Eq -e '-X *POST|--request *POST|--data|volume rm|system prune|compose .*down'"

    if have systemd-analyze; then
        for u in $UNITS; do
            if systemd-analyze verify "$u" >/dev/null 2>&1; then pass "systemd-analyze verify $(basename "$u")"
            else soft "systemd-analyze verify $(basename "$u") reported issues (expected before the runtime tree exists)"; fi
        done
    else
        soft "systemd-analyze not available: unit syntax checked by the structural checks above only"
    fi
    if have git && git -C "$REPO_ROOT" rev-parse >/dev/null 2>&1; then
        if git -C "$REPO_ROOT" diff --quiet HEAD -- scripts/windows 2>/dev/null; then pass "Windows runtime scripts unchanged"
        else soft "scripts/windows has uncommitted changes (Linux work must not modify it)"; fi
    fi
}

host_checks() {
    info "== Host checks (root=$RUNNINGAI_ROOT etc=$RUNNINGAI_ETC state=$RUNNINGAI_STATE user=$RUNNINGAI_USER) =="
    jm="$(java_major /usr/bin/java)"
    if [ -n "$jm" ] && [ "$jm" -ge 21 ]; then pass "/usr/bin/java is Java $jm"
    else fail "/usr/bin/java is not Java 21+ (found: ${jm:-none}); install JDK 21 or override ExecStart via 'systemctl edit running-ai'"; fi
    py="$(find_python)"
    if [ -n "$py" ]; then pass "Python >= 3.12 ($py)"; else fail "Python >= 3.12 not found"; fi
    if have systemctl; then pass "systemd available"; else fail "systemctl not found"; fi
    if id "$RUNNINGAI_USER" >/dev/null 2>&1; then
        pass "service user '$RUNNINGAI_USER' exists"
        [ "$(id -u "$RUNNINGAI_USER")" != 0 ] || fail "service user must not be root"
    else fail "service user '$RUNNINGAI_USER' missing (install-runtime.sh --create-user)"; fi

    for d in "$RUNNINGAI_ROOT/app" "$RUNNINGAI_ROOT/connector" "$RUNNINGAI_ETC" "$RUNNINGAI_STATE"; do
        if [ -d "$d" ]; then pass "directory $d"; else fail "directory $d missing"; fi
    done
    tokens="$RUNNINGAI_STATE/garmin-tokens"
    if [ -d "$tokens" ]; then
        m="$(mode_of "$tokens")"
        if [ "$m" = 700 ]; then pass "Garmin token store directory is 0700"; else fail "Garmin token store directory mode is $m (expected 700)"; fi
        if [ -f "$tokens/garmin_tokens.json" ]; then
            m="$(mode_of "$tokens/garmin_tokens.json")"
            if owner_only "$m"; then pass "Garmin token file is owner-only ($m)"; else fail "Garmin token file mode $m is too open"; fi
        else soft "no Garmin token yet: log in once as $RUNNINGAI_USER (see deploy/linux/README.md); the connector answers GARMIN_AUTH_REQUIRED until then"; fi
    else fail "Garmin token store directory $tokens missing"; fi

    for name in running-ai garmin-connector; do
        f="$RUNNINGAI_ETC/$name.env"
        if [ -f "$f" ]; then
            m="$(mode_of "$f")"
            if owner_only "$m"; then pass "$f present, mode $m"; else fail "$f mode $m is too open (chmod 600)"; fi
        else fail "$f missing (copy deploy/linux/env/$name.env.example)"; fi
    done
    f="$RUNNINGAI_ETC/running-ai.env"
    if [ -r "$f" ]; then
        grep -Eq '^DB_PASSWORD=.+' "$f" && pass "DB_PASSWORD is set" || fail "DB_PASSWORD is empty in $f"
        grep -Eq '^SERVER_ADDRESS=127\.0\.0\.1$' "$f" && pass "Spring bound to 127.0.0.1" || soft "SERVER_ADDRESS is not 127.0.0.1: Spring would listen on all interfaces and POST /sync has no authentication"
    else soft "cannot read $f as $(id -un) (run validate with sudo to check its content)"; fi

    if [ -f "$RUNNINGAI_ROOT/app/running-ai.jar" ] && [ "$(head -c 2 "$RUNNINGAI_ROOT/app/running-ai.jar")" = "PK" ]; then pass "Spring jar present"
    else fail "$RUNNINGAI_ROOT/app/running-ai.jar missing or not a jar (deploy-app.sh)"; fi
    venv="$RUNNINGAI_ROOT/connector/.venv/bin/python"
    if [ -x "$venv" ]; then
        if "$venv" -c "import garminconnect, fastapi, uvicorn" >/dev/null 2>&1; then pass "connector venv imports garminconnect/fastapi/uvicorn"
        else fail "connector venv is missing dependencies (deploy-app.sh --connector)"; fi
    else fail "connector venv $venv missing (deploy-app.sh --connector)"; fi

    for unit in running-ai-garmin-connector.service running-ai.service; do
        if [ -f "$SYSTEMD_DIR/$unit" ]; then
            pass "$unit installed"
            if have systemd-analyze; then
                systemd-analyze verify "$SYSTEMD_DIR/$unit" >/dev/null 2>&1 && pass "systemd-analyze verify $unit" || fail "systemd-analyze verify $unit"
            fi
        else fail "$unit not installed (install-runtime.sh)"; fi
    done
    if have timedatectl; then
        [ "$(timedatectl show -p NTPSynchronized --value 2>/dev/null)" = yes ] && pass "system clock synchronised (NTP)" || soft "clock is not NTP-synchronised: enable time sync (timedatectl set-ntp true)"
    fi
}

static_checks
[ "$MODE" = host ] && host_checks

info ""
info "$fails failure(s), $warns warning(s)"
[ "$fails" -eq 0 ]
