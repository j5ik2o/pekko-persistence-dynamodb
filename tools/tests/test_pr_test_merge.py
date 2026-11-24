"""Tests for .github/scripts/pr-test-merge.sh, which selects the PR CI test merge."""
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

SCRIPT = Path(__file__).resolve().parents[2] / ".github" / "scripts" / "pr-test-merge.sh"
PR = 7


class PrTestMergeTests(unittest.TestCase):
    def setUp(self):
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        self.root = Path(temp.name)
        self.origin = self.root / "origin.git"
        self.author = self.root / "author"
        self.git("init", "--quiet", "--bare", str(self.origin))
        self.git("init", "--quiet", "-b", "main", str(self.author))
        self.git("-C", str(self.author), "remote", "add", "origin", str(self.origin))
        self.old_base = self.commit("shared.txt", "base\n", "base")
        self.push("main")
        self.git("-C", str(self.author), "switch", "--quiet", "-c", "feature")
        self.head = self.commit("feature.txt", "feature\n", "feature")
        self.push(f"HEAD:refs/pull/{PR}/head")
        self.publish_github_merge(self.old_base)
        self.git("-C", str(self.author), "switch", "--quiet", "main")

        bin_dir = self.root / "bin"
        bin_dir.mkdir()
        self.gh_calls = self.root / "gh-calls"
        self.pr_state = self.root / "pr.tsv"
        self.set_pr("open", self.head)
        gh = bin_dir / "gh"
        gh.write_text('#!/bin/sh\necho "$*" >> "$GH_CALLS"\ncat "$PR_STATE"\n')
        gh.chmod(0o755)
        self.env = dict(os.environ, PATH=f"{bin_dir}{os.pathsep}{os.environ['PATH']}",
                        GH_CALLS=str(self.gh_calls), PR_STATE=str(self.pr_state))

    def git(self, *args, cwd=None):
        return subprocess.run(["git", "-c", "user.name=Test", "-c", "user.email=test@example.com",
                               "-c", "init.defaultBranch=main", *args],
                              cwd=cwd, check=True, capture_output=True, text=True).stdout.strip()

    def commit(self, name, text, message):
        (self.author / name).write_text(text)
        self.git("-C", str(self.author), "add", name)
        self.git("-C", str(self.author), "commit", "--quiet", "-m", message)
        return self.git("-C", str(self.author), "rev-parse", "HEAD")

    def push(self, refspec):
        self.git("-C", str(self.author), "push", "--quiet", "--force", "origin", refspec)

    def publish_github_merge(self, base):
        """Write refs/pull/N/merge as GitHub does: base first, head second."""
        tree = self.git("-C", str(self.author), "merge-tree", "--write-tree", base, self.head)
        merge = self.git("-C", str(self.author), "commit-tree", tree, "-p", base, "-p", self.head,
                         "-m", "GitHub merge")
        self.push(f"{merge}:refs/pull/{PR}/merge")
        return merge

    def advance_main(self, name="main.txt", text="main\n"):
        self.git("-C", str(self.author), "switch", "--quiet", "main")
        base = self.commit(name, text, "main moves")
        self.push("main")
        return base

    def set_pr(self, state, head):
        self.pr_state.write_text(f"{state}\tmain\t{head}\n")

    def run_script(self, event_head=None):
        runner = self.root / f"runner-{len(list(self.root.glob('runner-*')))}"
        clone = runner / "clone"
        runner.mkdir()
        self.git("clone", "--quiet", "--branch", "main", str(self.origin), str(clone))
        output = runner / "output"
        output.touch()
        env = dict(self.env, PR_NUMBER=str(PR), EVENT_HEAD_SHA=event_head or self.head,
                   GITHUB_REPOSITORY="owner/repo", GITHUB_OUTPUT=str(output),
                   RUNNER_TEMP=str(runner), PR_TEST_MERGE_ATTEMPTS="2", PR_TEST_MERGE_DELAY="0")
        result = subprocess.run(["bash", str(SCRIPT)], cwd=clone, env=env,
                                capture_output=True, text=True)
        outputs = dict(line.split("=", 1) for line in output.read_text().splitlines())
        return result, outputs, runner

    def checkout_in_job(self, outputs, runner):
        """Do what a product job does: check out, load the bundle, read the parents."""
        job = runner / "job"
        self.git("clone", "--quiet", str(self.origin), str(job))
        self.git("fetch", "--quiet", "origin", f"refs/pull/{PR}/merge", cwd=job)
        self.git("checkout", "--quiet", "--detach", outputs["checkout_ref"], cwd=job)
        if "bundle_name" in outputs:
            self.git("fetch", "--quiet", str(runner / "pr-test-merge.bundle"), "refs/pr-ci/merge", cwd=job)
            self.git("checkout", "--quiet", "--detach", "FETCH_HEAD", cwd=job)
        return " ".join([self.git("rev-parse", "HEAD", cwd=job),
                         self.git("show", "-s", "--format=%P", "HEAD", cwd=job)])

    def assert_checks(self, outputs, runner, base):
        sha = outputs["test_merge"].split()[0]
        self.assertEqual(outputs["test_merge"], f"{sha} {base} {self.head}")
        self.assertEqual((outputs["base_sha"], outputs["head_sha"]), (base, self.head))
        self.assertEqual(self.checkout_in_job(outputs, runner), outputs["test_merge"])

    def test_uses_the_github_merge_when_it_has_the_current_parents(self):
        result, outputs, runner = self.run_script()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertNotIn("bundle_name", outputs)
        self.assert_checks(outputs, runner, self.old_base)
        self.assertIn(f"api repos/owner/repo/pulls/{PR}", self.gh_calls.read_text())

    def test_ignores_an_event_base_that_main_has_passed(self):
        # The event still names old_base; GitHub has already merged onto the new tip.
        new_base = self.advance_main()
        github_merge = self.publish_github_merge(new_base)
        result, outputs, runner = self.run_script()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(outputs["checkout_ref"], github_merge)
        self.assert_checks(outputs, runner, new_base)

    def test_builds_the_merge_when_the_github_merge_keeps_an_old_base(self):
        new_base = self.advance_main()
        result, outputs, runner = self.run_script()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("built test merge", result.stdout)
        self.assertEqual(outputs["bundle_name"], "pr-test-merge-1")
        self.assertEqual(outputs["checkout_ref"], new_base)
        self.assert_checks(outputs, runner, new_base)

    def test_fails_an_event_for_an_old_head(self):
        old_head = self.head
        self.git("-C", str(self.author), "switch", "--quiet", "feature")
        self.head = self.commit("feature.txt", "feature 2\n", "feature again")
        self.push(f"HEAD:refs/pull/{PR}/head")
        self.publish_github_merge(self.old_base)
        self.set_pr("open", self.head)
        result, outputs, _ = self.run_script(event_head=old_head)
        self.assertEqual(result.returncode, 1)
        self.assertIn(f"event is for head {old_head}", result.stdout)
        self.assertEqual(outputs, {})

    def test_fails_a_closed_pr(self):
        self.set_pr("closed", self.head)
        result, outputs, _ = self.run_script()
        self.assertEqual(result.returncode, 1)
        self.assertEqual(outputs, {})

    def test_fails_when_the_current_parents_conflict(self):
        self.git("-C", str(self.author), "switch", "--quiet", "main")
        self.advance_main("feature.txt", "main side\n")
        result, outputs, _ = self.run_script()
        self.assertEqual(result.returncode, 1)
        self.assertIn("Cannot create a conflict-free test merge", result.stdout)
        self.assertEqual(outputs, {})


if __name__ == "__main__":
    unittest.main()
