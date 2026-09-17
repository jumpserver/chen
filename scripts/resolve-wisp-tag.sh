#!/usr/bin/env sh

set -eu

script_dir=$(CDPATH='' cd -- "$(dirname -- "$0")" && pwd)
chen_root=$(CDPATH='' cd -- "${script_dir}/.." && pwd)

branch=${WISP_BRANCH:-}

if [ -z "${branch}" ]; then
    current_branch=$(git -C "${chen_root}" symbolic-ref --quiet --short HEAD 2>/dev/null || true)

    if [ -n "${current_branch}" ]; then
        # Prefer the configured tracking branch. This lets a locally named
        # worktree branch still select the canonical Chen/Wisp branch.
        branch=$(git -C "${chen_root}" config --get "branch.${current_branch}.merge" 2>/dev/null || true)
        branch=${branch#refs/heads/}
        branch=${branch:-${current_branch}}
    fi
fi

case "${branch}" in
    refs/heads/*)
        branch=${branch#refs/heads/}
        ;;
    refs/remotes/*/*)
        branch=${branch#refs/remotes/}
        branch=${branch#*/}
        ;;
esac

# JumpServer feature branches use pr@<base>@<topic> (and occasionally repr@).
# Wisp images are shared at the base-branch level, not per component PR.
case "${branch}" in
    pr@*@* | repr@*@*)
        branch=${branch#*@}
        branch=${branch%%@*}
        ;;
esac

# Match Docker tag rules and docker/metadata-action's replacement behavior.
tag=$(printf '%s' "${branch}" \
    | sed 's/[^A-Za-z0-9_.-][^A-Za-z0-9_.-]*/-/g; s/^[.-][.-]*//' \
    | cut -c 1-128)

printf '%s\n' "${tag:-latest}"
