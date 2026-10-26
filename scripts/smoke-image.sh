#!/usr/bin/env bash
# Disposable image acceptance test; no existing database or Core is contacted.
set -euo pipefail
image=${1:?image required}
platform=${2:-linux/$(docker info --format '{{.Architecture}}')}
expected_db2=${3:-0}
root=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
work=$(mktemp -d)
chmod 700 "$work"
name="chen-image-smoke-$$"
network="$name"
pg="$name-pg"
chen="$name-chen"
cleanup() {
    docker rm -f -v "$chen" "$pg" >/dev/null 2>&1 || true
    docker network rm "$network" >/dev/null 2>&1 || true
    rm -rf "$work"
}
trap cleanup EXIT
password=$(python3 -c 'import secrets; print(secrets.token_hex(24))')
docker network create "$network" >/dev/null
docker run -d --name "$pg" --network "$network" --network-alias postgres \
    -e POSTGRES_USER=chen_test -e POSTGRES_DB=chen_test -e POSTGRES_PASSWORD="$password" postgres:16-alpine >/dev/null
for attempt in {1..60}; do
    if docker exec "$pg" pg_isready -h 127.0.0.1 -U chen_test -d chen_test >/dev/null 2>&1; then break; fi
    if (( attempt == 60 )); then echo 'PostgreSQL fixture did not start' >&2; exit 1; fi
    sleep 1
done
docker create --platform "$platform" --name "$chen" --network "$network" \
    -p 127.0.0.1::8082 "$image" single --backends /opt/chen/backends.yml >/dev/null
CHEN_SMOKE_PASSWORD="$password" python3 - <<'PY' | docker cp -a - "$chen:/opt/chen"
import io,json,os,sys,tarfile
config={'version':1,'backends':[{'selector':'image-pg','protocol':'postgresql','host':'postgres','port':5432,'user':'chen_test','password':os.environ['CHEN_SMOKE_PASSWORD'],'database':'chen_test','use_ssl':False}]}
data=json.dumps(config).encode()
with tarfile.open(fileobj=sys.stdout.buffer,mode='w|') as tar:
    member=tarfile.TarInfo('backends.yml'); member.uid=10001; member.gid=10001; member.mode=0o600; member.size=len(data)
    tar.addfile(member,io.BytesIO(data))
PY
unset password
docker start "$chen" >/dev/null
address=$(docker port "$chen" 8082/tcp)
python3 "$root/scripts/smoke-http.py" "http://$address" "$expected_db2"
docker exec "$chen" chen-go healthcheck
docker exec "$chen" chen-go --version
docker exec "$chen" bash -c 'test "$(id -u)" = 10001; ! command -v java; ! command -v mvn; for f in /proc/[0-9]*/comm; do test "$(cat "$f" 2>/dev/null)" != wisp; done'
docker stop --time 45 "$chen" >/dev/null
exit_code=$(docker inspect --format '{{.State.ExitCode}}' "$chen")
[[ "$exit_code" == 0 ]] || { echo "Single mode stop failed with status $exit_code" >&2; exit 1; }
echo 'Image smoke passed: non-root single mode, authentication, query, completion, large values, cancel, audit, CSV/XLSX export, graceful stop'
