#!/usr/bin/env bash

set -euo pipefail
chen_root=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
wisp_root=${WISP_SOURCE:-"${chen_root}/../wisp"}
wisp_bin="${chen_root}/data/bin/wisp-local"
wisp_config=${WISP_CONFIG:-"${chen_root}/data/wisp-local.yml"}

if [[ ! -f "${wisp_root}/main.go" ]]; then
    echo "Wisp source was not found: ${wisp_root}" >&2
    exit 1
fi

for command in go java mvn; do
    if ! command -v "${command}" >/dev/null 2>&1; then
        echo "Required command was not found: ${command}" >&2
        exit 1
    fi
done

cd "${chen_root}"
mkdir -p "$(dirname -- "${wisp_bin}")"

echo "Building Wisp from ${wisp_root}"
(
    cd "${wisp_root}"
    go build -o "${wisp_bin}" .
)

echo "Building Chen"
mvn -pl backend/web -am -DskipTests package

shopt -s nullglob
chen_jars=("${chen_root}"/backend/web/target/web-*.jar)
if (( ${#chen_jars[@]} != 1 )); then
    echo "Expected one Chen executable jar, found ${#chen_jars[@]}" >&2
    exit 1
fi
chen_jar=${chen_jars[0]}

export COMPONENT_NAME=chen
export CORE_HOST=${CORE_HOST:-http://127.0.0.1:8080}
export BOOTSTRAP_TOKEN=${BOOTSTRAP_TOKEN:-PleaseChangeMe}
export BIND_HOST=${WISP_BIND_HOST:-127.0.0.1}
export BIND_PORT=${WISP_BIND_PORT:-9090}
export LOG_LEVEL=${LOG_LEVEL:-INFO}
export WORK_DIR=${WISP_WORK_DIR:-${chen_root}}
export EXECUTE_PROGRAM=""
wisp_connect_host=${WISP_CONNECT_HOST:-127.0.0.1}
export GRPC_CLIENT_WISP_ADDRESS=${GRPC_CLIENT_WISP_ADDRESS:-static://${wisp_connect_host}:${BIND_PORT}}

wisp_pid=""
chen_pid=""

cleanup() {
    status=$?
    trap - EXIT INT TERM
    for pid in "${chen_pid}" "${wisp_pid}"; do
        if [[ -n "${pid}" ]] && kill -0 "${pid}" 2>/dev/null; then
            kill -TERM "${pid}" 2>/dev/null || true
        fi
    done
    for pid in "${chen_pid}" "${wisp_pid}"; do
        if [[ -n "${pid}" ]]; then
            wait "${pid}" 2>/dev/null || true
        fi
    done
    exit "${status}"
}

trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

wait_for_wisp() {
    timeout=${WISP_START_TIMEOUT:-60}
    deadline=$((SECONDS + timeout))
    while (( SECONDS < deadline )); do
        if ! kill -0 "${wisp_pid}" 2>/dev/null; then
            status=0
            wait "${wisp_pid}" || status=$?
            echo "Wisp exited before becoming ready (status ${status})." >&2
            tail -n 20 "${WORK_DIR}/data/logs/wisp.log" 2>/dev/null || true
            return 1
        fi
        if (echo >/dev/tcp/"${wisp_connect_host}"/"${BIND_PORT}") 2>/dev/null; then
            return 0
        fi
        sleep 1
    done
    echo "Timed out waiting ${timeout}s for Wisp on ${wisp_connect_host}:${BIND_PORT}." >&2
    tail -n 20 "${WORK_DIR}/data/logs/wisp.log" 2>/dev/null || true
    return 1
}

echo "Starting Wisp on ${BIND_HOST}:${BIND_PORT}"
"${wisp_bin}" --config "${wisp_config}" &
wisp_pid=$!

if ! wait_for_wisp; then
    echo "If Core reports 401, enable service-account registration or restart a Core configured with registration=auto, then retry." >&2
    exit 1
fi
echo "Wisp is ready."

chen_port=${CHEN_PORT:-8082}
echo "Starting Chen on http://127.0.0.1:${chen_port}/chen/"
java \
    -Dfile.encoding=utf-8 \
    -DsocksProxyHost= \
    -DsocksProxyPort=0 \
    -XX:+ExitOnOutOfMemoryError \
    -jar "${chen_jar}" \
    --server.port="${chen_port}" \
    --spring.config.additional-location=optional:file:"${chen_root}/config/" \
    --spring.profiles.active=dev &
chen_pid=$!

echo "Wisp PID: ${wisp_pid}; Chen PID: ${chen_pid}. Press Ctrl-C to stop both."

while kill -0 "${wisp_pid}" 2>/dev/null && kill -0 "${chen_pid}" 2>/dev/null; do
    sleep 1
done

status=0
if ! kill -0 "${wisp_pid}" 2>/dev/null; then
    wait "${wisp_pid}" || status=$?
    echo "Wisp stopped with status ${status}" >&2
else
    wait "${chen_pid}" || status=$?
    echo "Chen stopped with status ${status}" >&2
fi
exit "${status}"
