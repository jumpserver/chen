#!/usr/bin/env bash
set -euo pipefail
root=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
cd "$root"
export CHEN_BIN=${CHEN_BIN:-"$root/data/bin/chen-go"}
export WISP_BIN=${WISP_BIN:-"$root/data/bin/wisp"}
export WORK_DIR=${WISP_WORK_DIR:-"$root"}
export CHEN_STATE_DIR=${CHEN_STATE_DIR:-"$root/data/go"}
mode=${1:-managed}
if (( $# )); then shift; fi
default_port=8082
if [[ "$mode" == single || "$mode" == standalone ]]; then default_port=8083; fi
export CHEN_HTTP_ADDR=${CHEN_HTTP_ADDR:-127.0.0.1:${CHEN_PORT:-$default_port}}
mkdir -p "$(dirname -- "$CHEN_BIN")"
CGO_ENABLED=1 go build -tags="${GO_BUILD_TAGS:-}" -trimpath -ldflags="-X main.version=${VERSION:-dev}" -o "$CHEN_BIN" ./cmd/chen-go
case "$mode" in
    single|standalone) exec "$root/entrypoint.sh" single "$@" ;;
    backend) exec "$root/entrypoint.sh" backend "$@" ;;
    managed|wisp) "$root/scripts/build-wisp.sh" ;;
    *) echo 'Usage: ./start.sh [managed|backend|single] [Chen options]' >&2; exit 2 ;;
esac
export CORE_HOST=${CORE_HOST:-http://127.0.0.1:8080}
exec "$root/entrypoint.sh" managed "$@"
