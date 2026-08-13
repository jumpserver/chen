#!/usr/bin/env bash

set -euo pipefail

script_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
chen_root=$(cd -- "${script_dir}/.." && pwd)
wisp_root=${WISP_SOURCE:-"${chen_root}/../wisp"}
source_dir="${wisp_root}/protobuf-java/org/jumpserver/wisp"
target_dir="${chen_root}/backend/wisp/src/main/java/org/jumpserver/wisp"

if [[ ! -d "${source_dir}" ]]; then
    echo "Wisp Java protobuf output was not found: ${source_dir}" >&2
    echo "Generate it in Wisp first (make proto-java), or set WISP_SOURCE." >&2
    exit 1
fi

shopt -s nullglob
generated_files=("${source_dir}"/*.java)
if (( ${#generated_files[@]} == 0 )); then
    echo "No generated Java files were found in ${source_dir}" >&2
    exit 1
fi

mkdir -p "${target_dir}"
for generated_file in "${generated_files[@]}"; do
    cp -- "${generated_file}" "${target_dir}/"
done

echo "Synchronized ${#generated_files[@]} Wisp Java protobuf file(s) into ${target_dir}"
