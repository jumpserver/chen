#!/usr/bin/env bash
# Check shutdown order and child failure propagation without Core credentials.
set -euo pipefail
root=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
work=$(mktemp -d)
pid=''
cleanup() { [[ -z "$pid" ]] || kill -TERM "$pid" 2>/dev/null || true; rm -rf "$work"; }
trap cleanup EXIT
cat > "$work/child" <<'CHILD'
#!/usr/bin/env bash
name=${0##*/}
printf '%s-start\n' "$name" >> "$TRACE"
trap 'printf "%s-stop\n" "$name" >> "$TRACE"; exit 0' TERM
while :; do sleep .05; done
CHILD
chmod +x "$work/child"
ln -s child "$work/chen"
ln -s child "$work/wisp"
export CHEN_BIN="$work/chen" WISP_BIN="$work/wisp" WORK_DIR="$work" TRACE="$work/trace"
"$root/entrypoint.sh" managed &
pid=$!
for attempt in {1..50}; do
    if [[ -f "$TRACE" ]] && [[ $(wc -l < "$TRACE") -ge 2 ]]; then break; fi
    sleep .1
done
kill -TERM "$pid"
status=0
wait "$pid" || status=$?
pid=''
[[ "$status" = 143 ]]
[[ $(tail -n 2 "$TRACE") == $'chen-stop\nwisp-stop' ]]
cat > "$work/fail" <<'CHILD'
#!/usr/bin/env bash
exit 17
CHILD
chmod +x "$work/fail"
CHEN_BIN="$work/fail" "$root/entrypoint.sh" managed &
pid=$!
status=0
wait "$pid" || status=$?
pid=''
[[ "$status" = 17 ]]
echo 'Entrypoint shutdown order and child failure propagation passed'
