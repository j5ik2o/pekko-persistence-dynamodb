"""Tests for .github/scripts/pr-test-merge.sh, which selects the PR CI test merge,
and for the workflow step that checks out that merge in every job."""
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

try:
    import yaml
    HAVE_PYYAML = True
except ImportError:
    HAVE_PYYAML = False

PYYAML_REASON = (
    "requires PyYAML; see tools/README.md — install with "
    "`python3 -m venv target/docs-venv && target/docs-venv/bin/pip install -r docs/requirements.txt` "
    "(docs/requirements.txt pins PyYAML==6.0.2)"
)

ROOT = Path(__file__).resolve().parents[2]
SCRIPT = ROOT / ".github" / "scripts" / "pr-test-merge.sh"
VERIFY_STEP = "Check out the verified test merge"
PR = 7


def verify_steps():
    """The verify step of each job that checks out the test merge."""
    ci = yaml.safe_load((ROOT / ".github" / "workflows" / "ci.yml").read_text())
    tools = yaml.safe_load((ROOT / ".github" / "workflows" / "tools-tests.yml").read_text())
    jobs = {"lint": ci["jobs"]["lint"], "test": ci["jobs"]["test"],
            "tools-tests": tools["jobs"]["tools-tests"]}
    return {name: next(step for step in job["steps"] if step.get("name") == VERIFY_STEP)
            for name, job in jobs.items()}


@unittest.skipUnless(HAVE_PYYAML, PYYAML_REASON)
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
        # The fake API answers from PR_STATE and runs hook-N before its Nth answer.
        gh.write_text('#!/bin/sh\necho "$*" >> "$GH_CALLS"\n'
                      'n=$(wc -l < "$GH_CALLS" | tr -d " ")\n'
                      '[ -f "$GH_HOOKS/hook-$n" ] && sh "$GH_HOOKS/hook-$n"\n'
                      'cat "$PR_STATE"\n')
        gh.chmod(0o755)
        self.env = dict(os.environ, PATH=f"{bin_dir}{os.pathsep}{os.environ['PATH']}",
                        GH_CALLS=str(self.gh_calls), PR_STATE=str(self.pr_state),
                        GH_HOOKS=str(self.root))

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

    def github_merge(self, base, head=None):
        """Create a merge as GitHub does: base first, head second."""
        head = head or self.head
        tree = self.git("-C", str(self.author), "merge-tree", "--write-tree", base, head)
        merge = self.git("-C", str(self.author), "commit-tree", tree, "-p", base, "-p", head,
                         "-m", "GitHub merge")
        # Keep the object in origin under a ref that a clone does not fetch.
        self.push(f"{merge}:refs/objects/{merge}")
        return merge

    def publish_github_merge(self, base, head=None):
        """Write refs/pull/N/merge."""
        merge = self.github_merge(base, head)
        self.push(f"{merge}:refs/pull/{PR}/merge")
        return merge

    def advance_main(self, name="main.txt", text="main\n"):
        self.git("-C", str(self.author), "switch", "--quiet", "main")
        base = self.commit(name, text, "main moves")
        self.push("main")
        return base

    def set_pr(self, state, head, base_ref="main"):
        self.pr_state.write_text(f"{state}\t{base_ref}\t{head}\n")

    def run_script(self, event_head=None, event_base_ref="main"):
        runner = self.root / f"runner-{len(list(self.root.glob('runner-*')))}"
        clone = runner / "clone"
        runner.mkdir()
        self.git("clone", "--quiet", "--branch", "main", str(self.origin), str(clone))
        output = runner / "output"
        output.touch()
        env = dict(self.env, PR_NUMBER=str(PR), EVENT_HEAD_SHA=event_head or self.head,
                   EVENT_BASE_REF=event_base_ref,
                   GITHUB_REPOSITORY="owner/repo", GITHUB_OUTPUT=str(output),
                   RUNNER_TEMP=str(runner), GITHUB_RUN_ATTEMPT="1", PR_TEST_MERGE_ATTEMPTS="2", PR_TEST_MERGE_DELAY="0")
        result = subprocess.run(["bash", str(SCRIPT)], cwd=clone, env=env,
                                capture_output=True, text=True)
        outputs = dict(line.split("=", 1) for line in output.read_text().splitlines())
        return result, outputs, runner

    def run_verify_step(self, outputs, runner, test_merge=None):
        """Do what a product job does: check out checkout_ref, download the
        bundle, and run the workflow's verify step."""
        job = runner / "job"
        shutil.rmtree(job, ignore_errors=True)
        # Without a bundle the job clones only the base history, so the bundle
        # must carry the head; --no-local stops the clone copying every object.
        self.git("clone", "--quiet", "--no-local", str(self.origin), str(job))
        if "bundle_name" not in outputs:
            self.git("fetch", "--quiet", "origin", f"refs/pull/{PR}/merge", cwd=job)
        else:
            (runner / "pr-test-merge").mkdir(exist_ok=True)
            shutil.copy(runner / "pr-test-merge.bundle", runner / "pr-test-merge")
        self.git("checkout", "--quiet", "--detach", outputs["checkout_ref"], cwd=job)
        env = dict(self.env, RUNNER_TEMP=str(runner), BUNDLE=outputs.get("bundle_name", ""),
                   TEST_MERGE=outputs["test_merge"] if test_merge is None else test_merge)
        result = subprocess.run(["bash", "-e", "-c", verify_steps()["lint"]["run"]], cwd=job,
                                env=env, capture_output=True, text=True)
        self.job = job
        return result

    def checkout_in_job(self, outputs, runner):
        result = self.run_verify_step(outputs, runner)
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        return " ".join([self.git("rev-parse", "HEAD", cwd=self.job),
                         self.git("show", "-s", "--format=%P", "HEAD", cwd=self.job)])

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

    def test_uses_the_current_base_tip_after_main_moves(self):
        # The script reads the base tip itself, so an event's old base SHA cannot reach it.
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
        self.assertEqual((self.job / "main.txt").read_text(), "main\n")
        self.assertEqual((self.job / "feature.txt").read_text(), "feature\n")

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


    def test_uses_a_github_merge_that_is_refreshed_while_retrying(self):
        new_base = self.advance_main()
        refreshed = self.github_merge(new_base)
        (self.root / "hook-2").write_text(
            f'git --git-dir="{self.origin}" update-ref refs/pull/{PR}/merge {refreshed}\n')
        result, outputs, runner = self.run_script()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertNotIn("bundle_name", outputs)
        self.assertEqual(outputs["checkout_ref"], refreshed)
        self.assert_checks(outputs, runner, new_base)

    def test_builds_the_merge_when_github_has_no_merge_ref(self):
        self.git("--git-dir", str(self.origin), "update-ref", "-d", f"refs/pull/{PR}/merge")
        result, outputs, runner = self.run_script()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("built test merge", result.stdout)
        self.assert_checks(outputs, runner, self.old_base)

    def test_builds_the_merge_when_the_github_merge_has_an_old_head(self):
        old_head = self.head
        self.git("-C", str(self.author), "switch", "--quiet", "feature")
        self.head = self.commit("feature.txt", "feature 2\n", "feature again")
        self.push(f"HEAD:refs/pull/{PR}/head")
        self.publish_github_merge(self.old_base, old_head)
        self.set_pr("open", self.head)
        result, outputs, runner = self.run_script()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("built test merge", result.stdout)
        self.assert_checks(outputs, runner, self.old_base)

    def test_fails_an_event_for_a_base_the_pr_no_longer_targets(self):
        self.set_pr("open", self.head, base_ref="release")
        result, outputs, _ = self.run_script(event_base_ref="main")
        self.assertEqual(result.returncode, 1)
        self.assertIn("event is for base main, but the PR now targets release", result.stdout)
        self.assertEqual(outputs, {})

    def test_every_job_runs_the_same_verify_step(self):
        steps = verify_steps()
        self.assertEqual(len({step["run"] for step in steps.values()}), 1, steps)

    def test_verify_step_fails_a_checkout_that_is_not_the_test_merge(self):
        new_base = self.advance_main()
        result, outputs, runner = self.run_script()
        self.assertEqual(result.returncode, 0, result.stderr)
        sha, _, head = outputs["test_merge"].split()
        step = self.run_verify_step(outputs, runner, test_merge=f"{sha} {self.old_base} {head}")
        self.assertEqual(step.returncode, 1)
        self.assertIn("::error::Expected test merge", step.stdout)
        self.assertIn(new_base, step.stdout)


if __name__ == "__main__":
    unittest.main()
