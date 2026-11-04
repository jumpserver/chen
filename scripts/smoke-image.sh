#!/usr/bin/env bash
# Disposable image acceptance test; no existing database or Core is contacted.
set -euo pipefail
image=${1:?image required}
platform=${2:-linux/$(docker info --format '{{.Architecture}}')}
root=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
work=$(mktemp -d)
chmod 700 "$work"
name="chen-image-smoke-$$"
network="$name"
pg="$name-pg"
chen="$name-chen"
managed="$name-managed"
supervisor="$name-supervisor"
cleanup() {
    docker rm -f -v "$chen" "$pg" "$managed" "$supervisor" >/dev/null 2>&1 || true
    docker network rm "$network" >/dev/null 2>&1 || true
    rm -rf "$work"
}
trap cleanup EXIT
# Check both the published image metadata and the container's effective config.
expected_health='["CMD","chen-go","healthcheck"]'
[[ $(docker image inspect --format '{{json .Config.Healthcheck.Test}}' "$image") == "$expected_health" ]]
[[ $(docker image inspect --format '{{.Config.User}}' "$image") == '10001:10001' ]]
# Run supervisor faults with the image's Bash and runtime user, without Core.
docker run --rm --name "$supervisor" --platform "$platform" --entrypoint bash \
    -e CHEN_ENTRYPOINT=/opt/chen/entrypoint.sh \
    -v "$root:/tmp/chen-source:ro" "$image" /tmp/chen-source/scripts/test-entrypoint.sh
# The real packaged Wisp cannot register against a closed loopback port. Chen
# must remain unavailable. Exercise both Chen's deadline and Wisp's own retries.
for startup_timeout in 4s 120s; do
    docker create --platform "$platform" --name "$managed" \
        -e CORE_HOST=http://127.0.0.1:1 -e CHEN_STARTUP_TIMEOUT="$startup_timeout" \
        --health-interval=1s --health-start-period=0s --health-retries=1 "$image" managed >/dev/null
    docker start "$managed" >/dev/null
    for attempt in {1..80}; do
        [[ $(docker inspect --format '{{.State.Health.Status}}' "$managed") != healthy ]]
        if [[ $(docker inspect --format '{{.State.Running}}' "$managed") == false ]]; then break; fi
        if (( attempt == 80 )); then echo 'Managed startup failure did not stop container' >&2; exit 1; fi
        sleep 1
    done
    [[ $(docker inspect --format '{{.State.ExitCode}}' "$managed") == 1 ]]
    docker logs "$managed" > "$work/managed.log" 2>&1
    if [[ $startup_timeout == 4s ]]; then
        grep -q wisp_startup_timeout "$work/managed.log"
    else
        grep -q '注册终端失败退出' "$work/managed.log"
        ! grep -q wisp_startup_timeout "$work/managed.log"
    fi
    docker rm -v "$managed" >/dev/null
done
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
[[ $(docker inspect --format '{{json .Config.Healthcheck.Test}}' "$chen") == "$expected_health" ]]
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
python3 "$root/scripts/smoke-http.py" "http://$address" "1"
docker exec "$chen" chen-go healthcheck
docker exec "$chen" chen-go readycheck
docker exec "$chen" chen-go --version
docker exec "$chen" bash -c 'test "$(id -u)" = 10001; ! command -v java; ! command -v mvn; for f in /proc/[0-9]*/comm; do test "$(cat "$f" 2>/dev/null)" != wisp; done'
docker stop --time 45 "$chen" >/dev/null
exit_code=$(docker inspect --format '{{.State.ExitCode}}' "$chen")
[[ "$exit_code" == 0 ]] || { echo "Single mode stop failed with status $exit_code" >&2; exit 1; }
echo 'Image smoke passed: non-root single mode, authentication, query, completion, large values, cancel, audit, CSV/XLSX export, graceful stop'
echo 'Managed negative acceptance passed: supervisor faults, unreachable Core, bounded startup, no false healthy'
