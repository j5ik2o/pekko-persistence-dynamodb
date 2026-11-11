import json
import os
from pathlib import Path
import subprocess
import tempfile
import time
import unittest

SCRIPT = Path(__file__).resolve().parents[1] / "cleanup-merged-worktree.sh"
class CleanupMergedWorktreeTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.bin = self.root / "bin"
        self.bin.mkdir()
        self.remote = self.root / "origin.git"
        self.repo = self.root / "repo"
        self.worktree = self.root / "work tree"
        self.run_git("init", "--bare", str(self.remote))
        self.run_git("init", "-b", "main", str(self.repo))
        self.run_git("-C", str(self.repo), "config", "user.email", "test@example.com")
        self.run_git("-C", str(self.repo), "config", "user.name", "Test")
        (self.repo / "base.txt").write_text("base\n")
        self.run_git("-C", str(self.repo), "add", "base.txt")
        self.run_git("-C", str(self.repo), "commit", "-m", "base")
        self.run_git("-C", str(self.repo), "remote", "add", "origin", str(self.remote))
        self.run_git("-C", str(self.repo), "push", "-u", "origin", "main")
        self.run_git("-C", str(self.repo), "switch", "-c", "feature/test")
        (self.repo / "feature.txt").write_text("feature\n")
        self.run_git("-C", str(self.repo), "add", "feature.txt")
        self.run_git("-C", str(self.repo), "commit", "-m", "feature")
        self.run_git("-C", str(self.repo), "push", "-u", "origin", "feature/test")
        self.run_git("-C", str(self.repo), "push", "origin", "HEAD:refs/pull/8/head")
        self.run_git("-C", str(self.repo), "switch", "main")
        self.run_git("-C", str(self.repo), "merge", "--no-ff", "feature/test", "-m", "merge feature")
        self.merge_sha = self.git("-C", str(self.repo), "rev-parse", "HEAD").strip()
        self.run_git("-C", str(self.repo), "push", "origin", "main")
        self.run_git("-C", str(self.repo), "worktree", "add", str(self.worktree), "feature/test")
        self.run_git("-C", str(self.repo), "update-ref", "refs/pm/8", "HEAD~1")
        self.pr = [{"number": 8, "state": "MERGED", "mergedAt": "2026-01-01T00:00:00Z",
                    "mergeCommit": {"oid": self.merge_sha}}]
        self.run_state = [{"status": "completed", "conclusion": "success", "headSha": self.merge_sha}]
        self.install("gh", "#!/usr/bin/env python3\nimport json, os, sys\n"
                     "sys.stdin.read()\nargs=sys.argv[1:]\n"
                     "if args[:2] == ['pr','list']: print(open(os.environ['GH_PR']).read())\n"
                     "elif args[:2] == ['run','list']: print(open(os.environ['GH_RUN']).read())\n"
                     "else: raise SystemExit(2)\n")
        self.write_json("pr.json", self.pr)
        self.write_json("run.json", self.run_state)
        self.env = dict(os.environ, PATH=str(self.bin) + os.pathsep + os.environ["PATH"],
                        GH_PR=str(self.root / "pr.json"), GH_RUN=str(self.root / "run.json"))

    def install(self, name, content):
        path = self.bin / name
        path.write_text(content)
        path.chmod(0o755)

    def write_json(self, name, value):
        (self.root / name).write_text(json.dumps(value))

    def git(self, *args):
        return subprocess.check_output(["git", *args], text=True)

    def run_git(self, *args):
        subprocess.run(["git", *args], check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)

    def run_script(self, *args):
        return subprocess.run([str(SCRIPT), "owner/repo", str(self.worktree), *args],
                              text=True, capture_output=True, env=self.env)

    def assert_stopped(self, message):
        result = self.run_script()
        self.assertNotEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn(message, result.stderr)
        self.assertTrue(self.worktree.exists())

    def test_rejects_unmerged_and_closed_pull_requests(self):
        for state, merged_at in [("OPEN", None), ("CLOSED", None)]:
            with self.subTest(state=state):
                self.pr[0].update(state=state, mergedAt=merged_at)
                self.write_json("pr.json", self.pr)
                self.assert_stopped("not merged")
        self.pr[0].update(state="MERGED", mergedAt="2026-01-01T00:00:00Z")
        self.write_json("pr.json", self.pr)

    def test_rejects_detached_worktree(self):
        self.run_git("-C", str(self.worktree), "checkout", "--detach")
        self.assert_stopped("detached")

    def test_rejects_failed_fetch(self):
        self.run_git("-C", str(self.repo), "push", "origin", ":refs/pull/8/head")
        self.assert_stopped("could not fetch pull request head")

    def test_force_fetch_replaces_existing_pull_request_ref(self):
        wrong = self.git("-C", str(self.repo), "rev-parse", "origin/main").strip()
        self.run_git("-C", str(self.repo), "update-ref", "refs/pm/8", wrong)
        result = self.run_script("--dry-run")
        self.assertEqual(result.returncode, 0, result.stderr)
        fetched = self.git("-C", str(self.repo), "rev-parse", "refs/pm/8").strip()
        expected = self.git("-C", str(self.repo), "rev-parse", "feature/test").strip()
        self.assertEqual(fetched, expected)

    def test_rejects_head_outside_both_histories(self):
        self.run_git("-C", str(self.repo), "checkout", "--orphan", "unrelated")
        (self.repo / "orphan.txt").write_text("orphan\n")
        self.run_git("-C", str(self.repo), "add", "orphan.txt")
        self.run_git("-C", str(self.repo), "commit", "-m", "orphan")
        orphan = self.git("-C", str(self.repo), "rev-parse", "HEAD").strip()
        self.run_git("-C", str(self.repo), "-C", str(self.worktree), "reset", "--hard", orphan)
        self.assert_stopped("not in the pull request or origin/main history")

    def test_rejects_running_or_failed_ci(self):
        for run in [
            {"status": "in_progress", "conclusion": None, "headSha": self.merge_sha},
            {"status": "completed", "conclusion": "failure", "headSha": self.merge_sha},
            {"status": "completed", "conclusion": "success", "headSha": "b" * 40},
        ]:
            with self.subTest(run=run):
                self.write_json("run.json", [run])
                self.assert_stopped("not completed successfully")
        self.write_json("run.json", self.run_state)

    def test_rejects_modified_and_untracked_files(self):
        (self.worktree / "feature.txt").write_text("modified\n")
        self.assert_stopped("modified or untracked")
        self.run_git("-C", str(self.worktree), "checkout", "--", "feature.txt")
        (self.worktree / "untracked.txt").write_text("untracked\n")
        self.assert_stopped("modified or untracked")

    def test_rejects_worktree_used_by_another_process(self):
        process = subprocess.Popen(["python3", "-c", "import time,sys; time.sleep(30)", str(self.worktree)])
        try:
            time.sleep(0.1)
            self.assertIn(str(process.pid), subprocess.check_output(["pgrep", "-f", str(self.worktree)], text=True).split())
            self.assert_stopped("in use")
        finally:
            process.terminate()
            process.wait()

    def test_dry_run_preserves_worktree_and_branch(self):
        result = self.run_script("--dry-run")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("worktree remove", result.stdout)
        self.assertTrue(self.worktree.exists())
        self.assertIn("feature/test", self.git("-C", str(self.repo), "branch", "--list", "feature/test"))

    def test_removes_worktree_and_keeps_branch(self):
        result = self.run_script()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertFalse(self.worktree.exists())
        self.assertIn("feature/test", self.git("-C", str(self.repo), "branch", "--list", "feature/test"))


if __name__ == "__main__":
    unittest.main()
