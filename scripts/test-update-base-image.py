#!/usr/bin/env python3
"""Exercise release writeback against disposable local Git repositories only."""

import contextlib
import importlib.util
import io
import json
from pathlib import Path
import re
import subprocess
import tempfile
import unittest
from unittest.mock import patch


ROOT = Path(__file__).resolve().parent.parent
spec = importlib.util.spec_from_file_location("update_base_image", ROOT / "scripts/update-base-image.py")
release = importlib.util.module_from_spec(spec)
spec.loader.exec_module(release)
TAG = "20261026_120000"


class WorkflowTests(unittest.TestCase):
    def test_publication_requires_private_token_before_either_build(self):
        workflow = (ROOT / ".github/workflows/build-base-image.yml").read_text()
        self.assertNotIn("github.token", workflow)
        self.assertNotIn("contents: write", workflow)
        self.assertNotIn("pull_request_target", workflow)
        credential_check = workflow.index("- name: Check publication credentials")
        self.assertLess(credential_check, workflow.index("- name: Build standard"))
        self.assertLess(credential_check, workflow.index("- name: Build Db2"))
        block = workflow[credential_check:workflow.index("- name: Set up QEMU")]
        self.assertIn("if: env.PUBLISH == 'true' && steps.source.outputs.build == 'true'", block)
        self.assertIn("GH_TOKEN: ${{ secrets.PRIVATE_TOKEN }}", block)
        self.assertIn('if [ -z "$GH_TOKEN" ]; then', block)
        self.assertIn("exit 1", block)


class UpdateBaseImageTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name) / "source"
        self.remote = Path(self.temp.name) / "remote.git"
        self.root.mkdir()
        self.branch = "pr@dev@base_test"
        self.real_run = release.run
        self.real_run("git", "init", "--bare", str(self.remote))
        self.real_run("git", "init", "-b", self.branch, cwd=self.root)
        self.git("config", "user.name", "Release Test")
        self.git("config", "user.email", "test@example.invalid")
        for name in release.REFERENCES:
            (self.root / name).write_text((ROOT / name).read_text())
        (self.root / "unrelated.txt").write_text("Do not change\n")
        self.git("add", ".")
        self.git("commit", "-m", "fixture")
        self.sha = self.git("rev-parse", "HEAD")
        self.git("remote", "add", "origin", str(self.remote))
        self.git("push", "origin", f"HEAD:refs/heads/{self.branch}")
        self.gh_calls = []
        self.protected = False
        self.existing_prs = []
        self.before_push = None
        self.stdout = io.StringIO()
        self.addCleanup(patch.stopall)
        patch.object(release, "run", side_effect=self.dispatch).start()

    def git(self, *args):
        return self.real_run("git", *args, cwd=self.root)

    def dispatch(self, *args, cwd=None):
        if args[0] == "gh":
            self.gh_calls.append(args)
            if args[1] == "api":
                return json.dumps({"protected": self.protected})
            if args[1:3] == ("auth", "setup-git"):
                return ""
            if args[1:3] == ("pr", "list"):
                return json.dumps(self.existing_prs)
            if args[1:3] == ("pr", "create"):
                return "https://github.com/example/chen/pull/1"
            self.fail(f"Unexpected gh invocation: {args}")
        if args[:2] == ("git", "push") and self.before_push:
            self.before_push()
        return self.real_run(*args, cwd=cwd)

    def update(self, dry_run=False):
        with contextlib.redirect_stdout(self.stdout):
            release.update(self.root, TAG, self.branch, self.sha, "example/chen", dry_run)

    def remote_sha(self, branch=None):
        return self.git("ls-remote", "origin", f"refs/heads/{branch or self.branch}").split()[0]

    def set_branch(self, branch):
        self.branch = branch
        self.git("checkout", "-b", branch)
        self.git("push", "origin", f"HEAD:refs/heads/{branch}")

    def advance_remote(self):
        other = Path(self.temp.name) / "concurrent"
        self.real_run("git", "clone", "--branch", self.branch, str(self.remote), str(other))
        self.real_run("git", "config", "user.name", "Concurrent Test", cwd=other)
        self.real_run("git", "config", "user.email", "test@example.invalid", cwd=other)
        (other / "concurrent.txt").write_text("Another user's commit\n")
        self.real_run("git", "add", "concurrent.txt", cwd=other)
        self.real_run("git", "commit", "-m", "Concurrent change", cwd=other)
        self.real_run("git", "push", "origin", self.branch, cwd=other)

    def test_same_repo_branch_updates_only_three_defaults(self):
        (self.root / "private-local-file").write_text("Never stage this\n")
        self.update()
        self.assertEqual(set(self.git("diff", "--name-only", "HEAD^", "HEAD").splitlines()), set(release.REFERENCES))
        self.assertEqual(self.remote_sha(), self.git("rev-parse", "HEAD"))
        self.assertFalse(any(call[1] == "pr" for call in self.gh_calls))
        for name, pattern in release.REFERENCES.items():
            self.assertEqual(re.search(pattern, (self.root / name).read_text())[1], TAG)
        self.assertEqual((self.root / "unrelated.txt").read_text(), "Do not change\n")
        self.assertEqual(self.git("ls-files", "private-local-file"), "")
        self.assertIn("$(BASE_VERSION)$(VARIANT)", (self.root / "Makefile").read_text())
        self.assertTrue(release.is_writeback_commit(self.root))

    def test_protected_branch_uses_pr_without_changing_source(self):
        self.protected = True
        self.update()
        destination = f"pr@{self.branch}@build_chen_base_{TAG}"
        self.assertEqual(self.remote_sha(), self.sha)
        self.assertEqual(self.remote_sha(destination), self.git("rev-parse", "HEAD"))
        create = next(call for call in self.gh_calls if call[1:3] == ("pr", "create"))
        self.assertEqual(create[create.index("--base") + 1], self.branch)

    def test_dev_uses_existing_pr_naming_and_reuses_automatic_pr(self):
        self.set_branch("dev")
        self.existing_prs = [{"url": "https://github.com/example/chen/pull/2"}]
        self.update()
        self.assertEqual(self.git("branch", "--show-current"), f"pr@dev@build_chen_base_{TAG}")
        self.assertEqual(self.remote_sha(), self.sha)
        self.assertFalse(any(call[1:3] == ("pr", "create") for call in self.gh_calls))

    def test_version_branch_always_uses_pr(self):
        self.set_branch("v5")
        self.update()
        self.assertEqual(self.remote_sha(), self.sha)
        self.assertEqual(self.git("branch", "--show-current"), f"pr@v5@build_chen_base_{TAG}")

    def test_dry_run_has_no_files_commit_push_or_pr_mutations(self):
        self.set_branch("dev")
        before = {name: (self.root / name).read_bytes() for name in release.REFERENCES}
        self.update(dry_run=True)
        self.assertEqual(before, {name: (self.root / name).read_bytes() for name in release.REFERENCES})
        self.assertEqual(self.git("rev-parse", "HEAD"), self.sha)
        self.assertEqual(self.remote_sha(), self.sha)
        self.assertTrue(all(call[1] == "api" for call in self.gh_calls))
        self.assertIn("--- Dockerfile", self.stdout.getvalue())

    def test_dirty_tracked_work_is_preserved(self):
        (self.root / "unrelated.txt").write_text("User edit\n")
        with self.assertRaisesRegex(ValueError, "existing tracked changes"):
            self.update()
        self.assertEqual((self.root / "unrelated.txt").read_text(), "User edit\n")
        self.assertEqual(self.git("rev-parse", "HEAD"), self.sha)

    def test_source_advanced_during_build_is_not_overwritten(self):
        self.advance_remote()
        newer = self.remote_sha()
        with self.assertRaisesRegex(ValueError, "Source branch advanced"):
            self.update()
        self.assertEqual(self.remote_sha(), newer)
        self.assertEqual(self.git("status", "--porcelain"), "")

    def test_push_race_rejects_non_fast_forward(self):
        self.before_push = self.advance_remote
        with self.assertRaises(subprocess.CalledProcessError):
            self.update()
        self.assertNotEqual(self.remote_sha(), self.git("rev-parse", "HEAD"))

    def test_missing_or_duplicate_reference_fails_before_writing(self):
        for content in ["# absent\n", "BASE_VERSION ?= old\nBASE_VERSION ?= duplicate\n"]:
            with self.subTest(content=content):
                (self.root / "Makefile").write_text(content)
                before = (self.root / "Dockerfile").read_bytes()
                with self.assertRaisesRegex(ValueError, "exactly one"):
                    release.replacement_contents(self.root, TAG)
                self.assertEqual((self.root / "Dockerfile").read_bytes(), before)

    def test_invalid_tag_is_not_interpreted_as_code(self):
        for tag in ["latest", "20261026_120000;touch x", "$(touch x)", "20261026_120000-db2"]:
            with self.subTest(tag=tag), self.assertRaises(ValueError):
                release.replacement_contents(self.root, tag)

    def test_marker_cannot_hide_other_changes(self):
        self.update()
        (self.root / "unrelated.txt").write_text("Changed\n")
        self.git("add", "unrelated.txt")
        self.git("commit", "--amend", "--no-edit")
        self.assertFalse(release.is_writeback_commit(self.root))

    def test_marker_cannot_hide_dockerfile_runtime_change(self):
        self.update()
        with (self.root / "Dockerfile").open("a") as file:
            file.write("RUN echo unrelated runtime change\n")
        self.git("add", "Dockerfile")
        self.git("commit", "--amend", "--no-edit")
        self.assertFalse(release.is_writeback_commit(self.root))


if __name__ == "__main__":
    unittest.main(verbosity=2)
