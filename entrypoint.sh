#!/usr/bin/env bash
set -euo pipefail
umask 077
chen_bin=${CHEN_BIN:-/usr/local/bin/chen-go}
wisp_bin=${WISP_BIN:-/usr/local/bin/wisp}
mode=${1:-managed}
if (( $# )); then shift; fi
case "$mode" in
    single|standalone) exec "$chen_bin" single "$@" ;;
    backend) exec "$chen_bin" "$@" ;;
    healthcheck|version|--version|--help|-h) exec "$chen_bin" "$mode" "$@" ;;
    managed|wisp) ;;
    wisp-only) export EXECUTE_PROGRAM=''; exec "$wisp_bin" "$@" ;;
    *) exec "$mode" "$@" ;;
esac
export COMPONENT_NAME=chen
export WORK_DIR=${WORK_DIR:-/opt/chen}
export BIND_HOST=${WISP_BIND_HOST:-127.0.0.1}
export BIND_PORT=${WISP_BIND_PORT:-9090}
export CHEN_WISP_ADDRESS=${CHEN_WISP_ADDRESS:-127.0.0.1:${BIND_PORT}}
export EXECUTE_PROGRAM=''
export LOG_LEVEL=${LOG_LEVEL:-INFO}
mkdir -p "$WORK_DIR/data"
chen_pid=''
wisp_pid=''
stop_child() {
    local pid=$1 limit=$2 elapsed=0
    [[ -n "$pid" ]] || return 0
    if kill -0 "$pid" 2>/dev/null; then
        kill -TERM "$pid" 2>/dev/null || true
        while kill -0 "$pid" 2>/dev/null && (( elapsed < limit )); do sleep 1; elapsed=$((elapsed + 1)); done
        if kill -0 "$pid" 2>/dev/null; then kill -KILL "$pid" 2>/dev/null || true; fi
    fi
    wait "$pid" 2>/dev/null || true
}
cleanup() {
    local status=$?
    trap - EXIT INT TERM
    # Keep Wisp alive while Chen finishes sessions and drains its durable outbox.
    stop_child "$chen_pid" 40
    stop_child "$wisp_pid" 10
    exit "$status"
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM
if [[ -n ${WISP_CONFIG:-} ]]; then
    "$wisp_bin" --config "$WISP_CONFIG" &
else
    "$wisp_bin" &
fi
wisp_pid=$!
"$chen_bin" "$@" &
chen_pid=$!
while kill -0 "$wisp_pid" 2>/dev/null && kill -0 "$chen_pid" 2>/dev/null; do sleep 1; done
status=0
if ! kill -0 "$wisp_pid" 2>/dev/null; then wait "$wisp_pid" || status=$?; else wait "$chen_pid" || status=$?; fi
# A component stopping on its own is a service failure, even if it exits zero.
if (( status == 0 )); then status=1; fi
exit "$status"
