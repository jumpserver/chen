#!/usr/bin/env bash
set -euo pipefail
root=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
version=${WISP_VERSION:-v0.2.15}
out=${WISP_BIN:-"${root}/data/bin/wisp"}
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
if [[ -n ${WISP_SOURCE:-} ]]; then
    source_dir=${WISP_SOURCE}
    version=source
else
    go mod download "github.com/jumpserver/wisp@${version}"
    source_dir="$(go env GOMODCACHE)/github.com/jumpserver/wisp@${version}"
fi
[[ -f "$source_dir/go.mod" ]] || { echo 'Wisp Go source is unavailable' >&2; exit 1; }
cp -R "$source_dir/." "$work/"
chmod -R u+w "$work"
# Upstream v0.2.15 prints Config, including BootstrapToken. Remove only that
# diagnostic; never modify the developer checkout or the Go module cache.
config="$work/pkg/config/config.go"
sed '/fmt.Printf("%+v\\n", conf)/d' "$config" > "$config.safe"
mv "$config.safe" "$config"
mkdir -p "$(dirname -- "$out")"
(cd "$work" && GOMAXPROCS=${GOMAXPROCS:-2} GOMEMLIMIT=${GOMEMLIMIT:-1GiB} CGO_ENABLED=0 go build -p="${GO_BUILD_PARALLELISM:-2}" -trimpath -o "$out" .)
printf '%s\n' "$version" > "${out}.version"
