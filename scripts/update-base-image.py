#!/usr/bin/env python3
"""Update only Chen's three base defaults after both image variants are published."""

import argparse
import difflib
import json
import os
from pathlib import Path
import re
import subprocess
import sys
from urllib.parse import quote


REFERENCES = {
    "Dockerfile": r"(?m)^ARG CHEN_BASE_IMAGE=jumpserver/chen-base:([^\s]+)$",
    "Makefile": r"(?m)^BASE_VERSION \?= ([^\s]+)$",
    "docker-compose.yml": r"\$\{CHEN_BASE_IMAGE:-jumpserver/chen-base:([^\s}]+)\}",
}


def run(*args, cwd=None):
    return subprocess.run(args, cwd=cwd, check=True, text=True, capture_output=True).stdout.strip()


def replace_reference(name, original, tag):
    matches = list(re.finditer(REFERENCES[name], original))
    if len(matches) != 1:
        raise ValueError(f"Expected exactly one base default in {name}, found {len(matches)}")
    start, end = matches[0].span(1)
    return original[:start] + tag + original[end:]


def replacement_contents(root, tag):
    if not re.fullmatch(r"[0-9]{8}_[0-9]{6}", tag):
        raise ValueError("Expected a UTC timestamp tag: YYYYMMDD_HHMMSS")
    replacements = {}
    for name in REFERENCES:
        original = (root / name).read_text()
        replacements[name] = (original, replace_reference(name, original, tag))
    return replacements


def is_writeback_commit(root):
    subject = run("git", "log", "-1", "--format=%s", cwd=root)
    match = re.fullmatch(r"perf: update Chen base image to ([0-9]{8}_[0-9]{6})", subject)
    if not match:
        return False
    changed = run("git", "diff-tree", "--no-commit-id", "--name-only", "-r", "HEAD", cwd=root).splitlines()
    if not changed or not set(changed).issubset(REFERENCES):
        return False
    # PR paths are cumulative. Check the exact generated delta, not just its message.
    for name in REFERENCES:
        before = run("git", "show", f"HEAD^:{name}", cwd=root) + "\n"
        after = run("git", "show", f"HEAD:{name}", cwd=root) + "\n"
        if replace_reference(name, before, match[1]) != after:
            return False
    return True


def update(root, tag, branch, source_sha, repository, dry_run=False):
    run("git", "check-ref-format", f"refs/heads/{branch}", cwd=root)
    if not re.fullmatch(r"[0-9a-f]{40}", source_sha):
        raise ValueError("Expected a full source commit SHA")
    if not re.fullmatch(r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+", repository):
        raise ValueError("Expected GITHUB_REPOSITORY=owner/repository")
    if run("git", "rev-parse", "HEAD", cwd=root) != source_sha:
        raise ValueError("Checkout no longer matches the image source commit")
    if run("git", "status", "--porcelain", "--untracked-files=no", cwd=root):
        raise ValueError("Refusing to commit over existing tracked changes")
    remote = run("git", "ls-remote", "--exit-code", "origin", f"refs/heads/{branch}", cwd=root)
    if remote.split()[0] != source_sha:
        raise ValueError("Source branch advanced while building; leave its defaults unchanged and rebuild")

    replacements = replacement_contents(root, tag)
    metadata = json.loads(run("gh", "api", f"repos/{repository}/branches/{quote(branch, safe='')}", cwd=root))
    # dev/version branches always go through the existing pr@<base>@<topic> flow.
    protected = metadata["protected"] or branch == "dev" or branch.startswith("v")
    destination = f"pr@{branch}@build_chen_base_{tag}" if protected else branch
    run("git", "check-ref-format", f"refs/heads/{destination}", cwd=root)
    print(json.dumps({"source": branch, "source_sha": source_sha, "destination": destination,
                      "tag": tag, "create_pr": bool(protected), "dry_run": dry_run}))
    changed = [name for name, (before, after) in replacements.items() if before != after]
    if dry_run:
        for name in changed:
            before, after = replacements[name]
            sys.stdout.writelines(difflib.unified_diff(before.splitlines(True), after.splitlines(True),
                                                     fromfile=name, tofile=name))
        return
    if not changed:
        print("Base defaults already match; no commit needed")
        return

    if protected:
        run("git", "checkout", "-b", destination, cwd=root)
    for name in changed:
        (root / name).write_text(replacements[name][1])
    run("git", "add", "--", *changed, cwd=root)
    run("git", "-c", "user.name=github-actions[bot]", "-c",
        "user.email=github-actions[bot]@users.noreply.github.com", "commit", "-m",
        f"perf: update Chen base image to {tag}", cwd=root)
    # gh's credential helper reads GH_TOKEN; credentials never enter a remote URL.
    run("gh", "auth", "setup-git", cwd=root)
    # Non-force push also rejects a source branch that moves after the earlier check.
    run("git", "push", "origin", f"HEAD:refs/heads/{destination}", cwd=root)
    if protected:
        existing = json.loads(run("gh", "pr", "list", "--repo", repository, "--head", destination,
                                 "--base", branch, "--state", "open", "--json", "url", cwd=root))
        if existing:
            print(existing[0]["url"])
        else:
            print(run("gh", "pr", "create", "--repo", repository, "--head", destination, "--base", branch,
                      "--title", f"perf: update Chen base image to {tag}",
                      "--body", "Publish the standard amd64/arm64 and Db2 amd64 base images before updating "
                      "the Dockerfile, Makefile and Compose defaults. Both variants completed successfully "
                      f"for source commit {source_sha}.", cwd=root))


if __name__ == "__main__":
    if sys.argv[1:] == ["--is-writeback"]:
        print(str(is_writeback_commit(Path.cwd())).lower())
        sys.exit(0)
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("tag")
    parser.add_argument("--source-branch", required=True)
    parser.add_argument("--source-sha", required=True)
    parser.add_argument("--repository", default=os.environ.get("GITHUB_REPOSITORY", ""))
    parser.add_argument("--dry-run", action="store_true", help="Show the plan/diff without writing, committing or pushing")
    args = parser.parse_args()
    try:
        update(Path.cwd(), args.tag, args.source_branch, args.source_sha, args.repository, args.dry_run)
    except (ValueError, KeyError, subprocess.CalledProcessError) as error:
        print(f"Base reference update failed: {error}", file=sys.stderr)
        sys.exit(1)
