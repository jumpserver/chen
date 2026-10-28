#!/usr/bin/env bash
set -euo pipefail
root=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
image=${1:?image required}
version=${2:?version required}
platform=${3:?platform required}
arch=${platform##*/}
case "$version-$arch" in *[!a-zA-Z0-9._-]*) echo 'Invalid package version or architecture' >&2; exit 2 ;; esac
name="chen-${version}-linux-${arch}"
staging=$(mktemp -d)
container=''
cleanup() { [[ -z "$container" ]] || docker rm -v "$container" >/dev/null; rm -rf "$staging"; }
trap cleanup EXIT
container=$(docker create --platform "$platform" "$image")
mkdir -p "$staging/$name/bin" "$root/build"
for file in chen-go wisp wisp.version; do docker cp "$container:/usr/local/bin/$file" "$staging/$name/bin/$file"; done
docker cp "$container:/opt/chen/entrypoint.sh" "$staging/$name/entrypoint.sh"
docker cp "$container:/opt/chen/conf" "$staging/$name/conf"
docker cp "$container:/opt/chen/licenses" "$staging/$name/licenses"
docker cp "$container:/opt/chen-deps" "$staging/$name/native"
cp "$root/LICENSE" "$root/README.md" "$staging/$name/"
cp "$root/docs/build.md" "$staging/$name/BUILD.md"
cat > "$staging/$name/start.sh" <<'START'
#!/usr/bin/env bash
set -euo pipefail
root=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
export CHEN_BIN="$root/bin/chen-go" WISP_BIN="$root/bin/wisp" WORK_DIR="$root"
export CHEN_STATE_DIR=${CHEN_STATE_DIR:-"$root/data/go"}
port=8082
if [[ ${1:-managed} == single || ${1:-managed} == standalone ]]; then port=8083; fi
export CHEN_HTTP_ADDR=${CHEN_HTTP_ADDR:-127.0.0.1:$port}
export LD_LIBRARY_PATH="$root/native/clidriver/lib${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}"
# The IBM CLI derives its narrow-character code page from the process locale.
export LANG=C.UTF-8 LC_ALL=C.UTF-8
exec "$root/entrypoint.sh" "$@"
START
chmod +x "$staging/$name/start.sh"
( cd "$staging" && tar -czf "$root/build/$name.tar.gz" "$name" )
( cd "$root/build" && shasum -a 256 "$name.tar.gz" > "$name.tar.gz.sha256" )
printf 'Created build/%s.tar.gz\n' "$name"
