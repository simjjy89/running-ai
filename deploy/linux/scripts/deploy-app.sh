#!/usr/bin/env bash
# Builds the Spring Boot jar (or uses --jar) and installs it atomically into the runtime tree,
# optionally refreshes the Garmin connector source/venv, restarts the units and checks health.
#
#   deploy-app.sh [--dry-run] [--jar PATH] [--skip-build] [--connector] [--no-restart]
#     --jar PATH     deploy this jar instead of building
#     --skip-build   reuse the newest server/build/libs/running-ai-server-*.jar
#     --connector    also copy tools/garmin-connector and (re)create/refresh its venv
#     --no-restart   install only; do not restart services or check health
#     --dry-run      print every action; nothing is built, copied or restarted
#
# The build runs as the invoking user; only the install/restart steps use sudo. The previous jar is
# kept once as running-ai.jar.previous (manual rollback: mv it back and restart).
set -euo pipefail
# shellcheck source=lib.sh
. "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

DRY_RUN=0
JAR=""
SKIP_BUILD=0
CONNECTOR=0
RESTART=1
while [ $# -gt 0 ]; do
    case "$1" in
        --dry-run) DRY_RUN=1 ;;
        --jar) shift; JAR="${1:-}"; [ -n "$JAR" ] || die "--jar needs a path" ;;
        --skip-build) SKIP_BUILD=1 ;;
        --connector) CONNECTOR=1 ;;
        --no-restart) RESTART=0 ;;
        -h|--help) sed -n '2,13p' "${BASH_SOURCE[0]}"; exit 0 ;;
        *) die "unknown option: $1" ;;
    esac
    shift
done
export DRY_RUN

newest_jar() {
    # bootJar output only; the *-plain.jar is not runnable.
    find "$REPO_ROOT/server/build/libs" -maxdepth 1 -name 'running-ai-server-*.jar' ! -name '*-plain.jar' -print 2>/dev/null | sort | tail -n 1 || true
}

if [ -z "$JAR" ]; then
    if [ "$SKIP_BUILD" = 0 ]; then
        jm="$(java_major)"
        if [ -z "$jm" ] || [ "$jm" -lt 21 ]; then die "Java 21 is required to build (found: ${jm:-none})"; fi
        if [ "$DRY_RUN" = 1 ]; then
            info "+ (cd $REPO_ROOT/server && ./gradlew bootJar --console=plain)"
        else
            (cd "$REPO_ROOT/server" && ./gradlew bootJar --console=plain)
        fi
    fi
    JAR="$(newest_jar)"
    if [ -z "$JAR" ]; then
        if [ "$DRY_RUN" = 1 ]; then JAR="$REPO_ROOT/server/build/libs/running-ai-server-<version>.jar"; else die "no bootJar found under server/build/libs"; fi
    fi
fi
if [ "$DRY_RUN" != 1 ]; then
    [ -f "$JAR" ] || die "jar not found: $JAR"
    [ "$(head -c 2 "$JAR")" = "PK" ] || die "not a valid jar (zip) file: $JAR"
fi
info "jar: $JAR"

target="$RUNNINGAI_ROOT/app/running-ai.jar"
staged="$RUNNINGAI_ROOT/app/.running-ai.jar.new.$$"
# Stage next to the target and rename, so a half-copied jar never has the production name.
run install -m 0644 -o root -g root "$JAR" "$staged"
if [ "$DRY_RUN" = 1 ] || priv test -f "$target"; then
    run cp -p "$target" "$target.previous.tmp"
    run mv -f "$target.previous.tmp" "$target.previous"
fi
run mv -f "$staged" "$target"

if [ "$CONNECTOR" = 1 ]; then
    src="$REPO_ROOT/tools/garmin-connector"
    dst="$RUNNINGAI_ROOT/connector"
    info "connector: $src -> $dst"
    if [ "$DRY_RUN" = 1 ]; then
        info "+ tar (garmin_connector/, requirements.txt) | tar -x -C $dst"
    else
        (cd "$src" && tar --exclude='__pycache__' -cf - garmin_connector requirements.txt) | priv tar -xf - -C "$dst" --no-same-owner
    fi
    if [ "$DRY_RUN" = 1 ] || ! priv test -x "$dst/.venv/bin/python"; then
        py="$(find_python)"
        if [ -z "$py" ]; then
            if [ "$DRY_RUN" = 1 ]; then py="python3.12"; else die "Python >= 3.12 is required for the connector venv"; fi
        fi
        run "$py" -m venv "$dst/.venv"
    fi
    run "$dst/.venv/bin/python" -m pip install --disable-pip-version-check -r "$dst/requirements.txt"
fi

if [ "$RESTART" = 1 ]; then
    if [ "$CONNECTOR" = 1 ]; then run systemctl restart running-ai-garmin-connector.service; fi
    run systemctl restart running-ai.service
    if [ "$DRY_RUN" = 1 ]; then
        info "+ wait for http://127.0.0.1:$SPRING_PORT/actuator/health (UP, up to 120 s)"
    else
        have curl || die "curl is required for the health check (or use --no-restart)"
        if wait_for "http://127.0.0.1:$SPRING_PORT/actuator/health" '"status":"UP"' 120; then
            info "Spring health: UP"
        else
            die "Spring did not report UP within 120 s. Check: journalctl -u running-ai -n 100"
        fi
    fi
fi
info "done."
