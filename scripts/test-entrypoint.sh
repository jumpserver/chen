#!/usr/bin/env bash
# Process supervision faults; actual gRPC readiness is tested in cmd/chen-go.
set -euo pipefail
root=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
entrypoint=${CHEN_ENTRYPOINT:-"$root/entrypoint.sh"}
work=$(mktemp -d)
pid=''
watchdog=''
cleanup() {
    [[ -z "$pid" ]] || kill -TERM "$pid" 2>/dev/null || true
    [[ -z "$pid" ]] || wait "$pid" 2>/dev/null || true
    [[ -z "$watchdog" ]] || kill "$watchdog" 2>/dev/null || true
    rm -rf "$work"
}
trap cleanup EXIT
cat > "$work/child" <<'CHILD'
#!/usr/bin/env bash
name=${0##*/}
printf '%s-start\n' "$name" >> "$TRACE"
printf '%s\n' "$$" > "$WORK_DIR/$name.pid"
trap 'printf "%s-stop\n" "$name" >> "$TRACE"; exit 0' TERM
if [[ $name == "${FAIL_CHILD:-}" ]]; then
    if [[ ${FAIL_WHEN:-startup} == running ]]; then
        while [[ ! -e "$WORK_DIR/fail-now" ]]; do sleep .05; done
    fi
    exit "${FAIL_STATUS:-17}"
fi
while :; do sleep .05; done
CHILD
chmod +x "$work/child"
ln -s child "$work/chen"
ln -s child "$work/wisp"
export CHEN_BIN="$work/chen" WISP_BIN="$work/wisp" WORK_DIR="$work" TRACE="$work/trace"
wait_started() {
    for attempt in {1..100}; do
        if [[ -f "$TRACE" ]] && [[ $(wc -l < "$TRACE") -ge 2 ]]; then return; fi
        sleep .05
    done
    echo 'children failed to start' >&2; exit 1
}
wait_status() {
    local expected=$1 status=0
    (sleep 12; kill -KILL "$pid" 2>/dev/null || true) &
    watchdog=$!
    wait "$pid" || status=$?
    kill "$watchdog" 2>/dev/null || true
    wait "$watchdog" 2>/dev/null || true
    watchdog=''
    pid=''
    [[ "$status" == "$expected" ]] || { echo "expected $expected, got $status" >&2; exit 1; }
    for name in chen wisp; do
        if [[ -f "$work/$name.pid" ]] && kill -0 "$(cat "$work/$name.pid")" 2>/dev/null; then
            echo "$name survived supervisor shutdown" >&2; exit 1
        fi
    done
}
"$entrypoint" managed &
pid=$!
wait_started
kill -TERM "$pid"
wait_status 143
[[ $(tail -n 2 "$TRACE") == $'chen-stop\nwisp-stop' ]]
for child in wisp chen; do
    for when in startup running; do
        for status in 0 17; do
            rm -f "$TRACE" "$work/fail-now" "$work/chen.pid" "$work/wisp.pid"
            FAIL_CHILD=$child FAIL_WHEN=$when FAIL_STATUS=$status "$entrypoint" managed &
            pid=$!
            wait_started
            touch "$work/fail-now"
            expected=$status
            if (( expected == 0 )); then expected=1; fi
            wait_status "$expected"
        done
    done
done
for mode in single standalone; do
    rm -f "$TRACE" "$work/chen.pid" "$work/wisp.pid"
    FAIL_CHILD=chen FAIL_STATUS=23 "$entrypoint" "$mode" &
    pid=$!
    wait_status 23
    [[ $(cat "$TRACE") == chen-start ]]
done
echo 'Entrypoint passed: TERM order, startup/runtime failures, zero exit, single/standalone'
