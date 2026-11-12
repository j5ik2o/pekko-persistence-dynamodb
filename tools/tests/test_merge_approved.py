import json
import os
from pathlib import Path
import subprocess
import tempfile
import time
import unittest

SCRIPT = Path(__file__).resolve().parents[1] / "merge-approved.sh"
SHA = "a" * 40


class MergeApprovedTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.bin = self.root / "bin"
        self.bin.mkdir()
        self.state = {"state": "OPEN", "headRefOid": SHA, "body": "Refs #12", "commits": [{"oid": SHA}]}
        self.checks = [{"bucket": "pass"}]
        self.check_runs = [
            {"id": 1, "name": "CodeRabbit", "status": "completed", "conclusion": "success", "output": {"title": "Review completed", "summary": "Review completed."}, "started_at": "2026-11-11T06:45:03Z"},
        ]
        self.status = {"statuses": [{"context": "Check unresolved comments", "state": "success"}]}
        self.review_exit = 0
        self.diff = ""
        self.inputs = []
        self.install("gh", '#!/usr/bin/env python3\nimport json, os, sys\n'
                     'open(os.environ["GH_INPUT_LOG"],"a").write("null\\n" if not sys.stdin.read() else "data\\n")\n'
                     'args=sys.argv[1:]\n'
                     'if args[0:2]==["pr","view"]: print(open(os.environ["GH_PR" ]).read())\n'
                     'elif args[0:2]==["pr","checks"]: print(open(os.environ["GH_CHECKS"]).read())\n'
                     'elif args[:2]==["api","repos/owner/repo/commits/' + SHA + '/check-runs"]: print(json.dumps([{"check_runs": json.load(open(os.environ["GH_CHECK_RUNS"]))}]))\n'
                     'elif args[:2]==["api","repos/owner/repo/commits/' + SHA + '/status"]: print(open(os.environ["GH_STATUS"]).read())\n'
                     'elif args[:2]==["api","repos/owner/repo/issues/comments/42"]: print(open(os.environ["GH_REVIEW_COMMENT"]).read())\n'
                     'elif args[:2]==["api","repos/owner/repo/collaborators/reviewer/permission"]: print(open(os.environ["GH_PERMISSION"]).read())\n'
                     'elif args[:2]==["api","repos/owner/repo/issues/7/timeline"]: print(os.environ.get("GH_TIMELINE", json.dumps([[{ "event":"ready_for_review", "created_at":"2026-11-11T06:45:02Z"}]])))\n'
                     'elif args[:2]==["pr","diff"]: print(os.environ.get("GH_DIFF",""))\n'
                     'elif args[0:2]==["pr","merge"]: open(os.environ["MERGE_LOG"],"w").write(" ".join(args)); p=json.load(open(os.environ["GH_PR"])); p["state"]="MERGED" if os.environ.get("MERGE_LEAVES_OPEN") != "1" else "OPEN"; open(os.environ["GH_PR"],"w").write(json.dumps(p))\n'
                     'elif args[:2]==["issue","close"]: open(os.environ["ISSUE_LOG"],"a").write("close " + " ".join(args[2:]) + "\\n")\n'
                     'elif args[:2]==["issue","comment"]: open(os.environ["ISSUE_LOG"],"a").write("comment " + " ".join(args[2:]) + "\\n")\n'
                     'elif args[:2]==["api","graphql"]: print(json.dumps({"data":{"repository":{"pullRequest":{"reviewThreads":{"pageInfo":{"hasNextPage":False},"nodes":[{"isResolved": os.environ.get("REVIEW_EXIT") != "1", "path":"x", "comments":{"nodes":[]}}]}}}}}))\n'
                     'else: raise SystemExit(2)\n')
        self.install("git", '#!/usr/bin/env python3\nimport os,sys\n'
                     'args=sys.argv[1:]\n'
                     'if args[:2]==["diff","--name-only"]: print(os.environ.get("GIT_DIFF",""))\n'
                     'elif args[:2]==["fetch","--quiet"]: open(os.environ["GIT_LOG"],"a").write("fetch\\n")\n'
                     'elif args[:2]==["log","-1"]: print(os.environ.get("GIT_SUBJECT","feat(storage): preserve values"))\n'
                     'else: raise SystemExit(2)\n')
        self.install("check-review-threads.sh", '#!/usr/bin/env bash\nexit "$REVIEW_EXIT"\n')
        (self.root / "pr.json").write_text(json.dumps(self.state))
        (self.root / "checks.json").write_text(json.dumps(self.checks))
        (self.root / "check-runs.json").write_text(json.dumps(self.check_runs))
        (self.root / "status.json").write_text(json.dumps(self.status))
        (self.root / "review-comment.json").write_text(json.dumps({"issue_url": "https://api.github.com/repos/owner/repo/issues/7", "body": f"Reviewed.\nindependent-review: head={SHA} result=pass", "created_at": "2026-11-11T06:46:00Z", "user": {"login": "reviewer", "type": "User"}}))
        (self.root / "permission.json").write_text(json.dumps({"permission": "write"}))
        self.env = dict(os.environ, PATH=str(self.bin) + os.pathsep + os.environ["PATH"],
                        GH_PR=str(self.root / "pr.json"), GH_CHECKS=str(self.root / "checks.json"),
                        GH_CHECK_RUNS=str(self.root / "check-runs.json"),
                        GH_STATUS=str(self.root / "status.json"),
                        GH_REVIEW_COMMENT=str(self.root / "review-comment.json"), GH_PERMISSION=str(self.root / "permission.json"),
                        GH_INPUT_LOG=str(self.root / "stdin.log"), MERGE_LOG=str(self.root / "merge.log"),
                        ISSUE_LOG=str(self.root / "issue.log"),
                        GIT_LOG=str(self.root / "git.log"),
                        REVIEW_EXIT="0")

    def install(self, name, content):
        path = self.bin / name
        path.write_text(content)
        path.chmod(0o755)

    def run_script(self, *args, stdin=""):
        return subprocess.run([str(SCRIPT), "owner/repo", "7", SHA, *args], input=stdin, text=True,
                              env=self.env, capture_output=True)

    def set_rate_limited(self):
        self.check_runs[0].update(status="completed", conclusion="neutral", completed_at="2026-11-11T06:45:30Z",
                                  output={"title": "Review rate limited", "summary": "Review skipped"})
        (self.root / "check-runs.json").write_text(json.dumps(self.check_runs))

    def set_review_comment(self, **updates):
        comment = json.loads((self.root / "review-comment.json").read_text())
        comment.update(updates)
        (self.root / "review-comment.json").write_text(json.dumps(comment))

    def test_gate_failures(self):
        cases = [
            ("head", lambda: self.state.update(headRefOid="b" * 40)),
                ("pending", lambda: setattr(self, "checks", [{"bucket": "pending"}])),
            ("threads", lambda: self.env.update(REVIEW_EXIT="1")),
            ("unscoped", lambda: None),
            ("handoff", lambda: self.env.update(GH_DIFF="docs/HAND_OFF.md")),
        ]
        for label, setup in cases:
            with self.subTest(label=label):
                self.state.update(headRefOid=SHA)
                self.checks = [{"bucket": "pass"}]
                self.env.update(REVIEW_EXIT="0", GH_DIFF="", GIT_SUBJECT="feat(storage): avoid version bump")
                if label == "unscoped":
                    self.env["GIT_SUBJECT"] = "fix: avoid bump"
                setup()
                (self.root / "pr.json").write_text(json.dumps(self.state))
                (self.root / "checks.json").write_text(json.dumps(self.checks))
                result = self.run_script("--dry-run", "--subject", self.env["GIT_SUBJECT"])
                self.assertNotEqual(result.returncode, 0, result.stdout)
                self.assertIn("error:", result.stderr)

    def test_dry_run_uses_full_git_subject_and_never_merges(self):
        self.env["GIT_SUBJECT"] = "feat(storage): retain complete subject"
        result = self.run_script("--dry-run", "--subject", self.env["GIT_SUBJECT"])
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn(r"feat\(storage\):\ retain\ complete\ subject\ \(#7\)", result.stdout)
        self.assertFalse((self.root / "merge.log").exists())
        self.assertFalse((self.root / "git.log").exists())

    def test_dry_run_without_subject_stops_before_fetch_or_merge(self):
        result = self.run_script("--dry-run")
        self.assertNotEqual(result.returncode, 0, result.stdout)
        self.assertIn("--dry-run requires --subject", result.stderr)
        self.assertFalse((self.root / "git.log").exists())
        self.assertFalse((self.root / "merge.log").exists())

    def test_subject_must_use_lowercase_type_and_scope(self):
        for subject, expected in [
            ("fix(scope): x", True),
            ("Fix(scope): x", False),
            ("FIX(scope): x", False),
            ("fix(): x", False),
            ("fix: x", False),
            ("修正(scope): x", False),
        ]:
            with self.subTest(subject=subject):
                result = self.run_script("--dry-run", "--subject", subject)
                self.assertEqual(result.returncode == 0, expected, result.stderr)

    def test_empty_checks_are_rejected(self):
        self.checks = []
        (self.root / "checks.json").write_text(json.dumps(self.checks))
        result = self.run_script("--dry-run", "--subject", "fix(scope): x")
        self.assertNotEqual(result.returncode, 0, result.stdout)
        self.assertIn("checks are empty", result.stderr)

    def test_missing_ready_event_names_the_draft_round_trip(self):
        self.env["GH_TIMELINE"] = json.dumps([[{"event": "committed"}]])
        result = self.run_script("--dry-run", "--subject", "fix(scope): x")
        self.assertNotEqual(result.returncode, 0, result.stdout)
        self.assertIn("no ready_for_review event; convert it to draft and mark it ready again (gh pr ready --undo, then gh pr ready)", result.stderr)
        self.assertFalse((self.root / "merge.log").exists())

    def test_ready_then_unregistered_coderabbit_is_rejected(self):
        self.check_runs = []
        (self.root / "check-runs.json").write_text(json.dumps(self.check_runs))
        time.sleep(1)
        result = self.run_script("--dry-run", "--subject", "fix(scope): x")
        self.assertNotEqual(result.returncode, 0, result.stdout)
        self.assertIn("CodeRabbit", result.stderr)
        self.assertFalse((self.root / "merge.log").exists())

    def test_stale_coderabbit_success_is_rejected_when_fresh_run_is_missing(self):
        self.check_runs[0]["started_at"] = "2026-11-11T06:45:01Z"
        (self.root / "check-runs.json").write_text(json.dumps(self.check_runs))
        result = self.run_script("--dry-run", "--subject", "fix(scope): x")
        self.assertNotEqual(result.returncode, 0, result.stdout)
        self.assertIn("CodeRabbit is missing", result.stderr)
        self.assertFalse((self.root / "merge.log").exists())

    def test_missing_review_thread_status_is_rejected(self):
        (self.root / "status.json").write_text(json.dumps({"statuses": []}))
        result = self.run_script("--dry-run", "--subject", "fix(scope): x")
        self.assertNotEqual(result.returncode, 0, result.stdout)
        self.assertIn("Check unresolved comments is missing", result.stderr)
        self.assertFalse((self.root / "merge.log").exists())

    def test_failed_review_thread_status_is_rejected(self):
        (self.root / "status.json").write_text(json.dumps({"statuses": [{"context": "Check unresolved comments", "state": "failure"}]}))
        result = self.run_script("--dry-run", "--subject", "fix(scope): x")
        self.assertNotEqual(result.returncode, 0, result.stdout)
        self.assertIn("Check unresolved comments did not succeed", result.stderr)
        self.assertFalse((self.root / "merge.log").exists())

    def test_review_thread_status_needs_no_ready_ordering(self):
        self.env["GH_TIMELINE"] = json.dumps([[{"event": "ready_for_review", "created_at": "2026-11-11T06:45:02Z"}]])
        (self.root / "status.json").write_text(json.dumps({"statuses": [{"context": "Check unresolved comments", "state": "success", "created_at": "2026-11-11T06:40:00Z"}]}))
        result = self.run_script("--dry-run", "--subject", "fix(scope): x")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("gh pr merge", result.stdout)

    def test_completed_required_runs_after_ready_are_accepted(self):
        result = self.run_script("--dry-run", "--subject", "fix(scope): x")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("gh pr merge", result.stdout)
        self.assertFalse((self.root / "merge.log").exists())

    def test_rate_limited_neutral_coderabbit_is_rejected(self):
        self.set_rate_limited()
        result = self.run_script("--dry-run", "--subject", "fix(scope): x")
        self.assertNotEqual(result.returncode, 0, result.stdout)
        self.assertIn("did not review this PR because it was rate limited", result.stderr)
        self.assertIn("@coderabbitai review", result.stderr)
        self.assertIn("--substitute-review", result.stderr)
        self.assertFalse((self.root / "merge.log").exists())

    def test_rate_limited_neutral_accepts_valid_substitute_review(self):
        self.set_rate_limited()
        result = self.run_script("--substitute-review", "42", "--dry-run", "--subject", "fix(scope): x")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("gh pr merge", result.stdout)

    def test_substitute_review_rejects_mismatched_head(self):
        self.set_rate_limited()
        self.set_review_comment(body=f"independent-review: head={'b' * 40} result=pass")
        result = self.run_script("--substitute-review", "42", "--dry-run", "--subject", "fix(scope): x")
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("marker head does not match", result.stderr)

    def test_substitute_review_rejects_failed_result(self):
        self.set_rate_limited()
        self.set_review_comment(body=f"independent-review: head={SHA} result=fail")
        result = self.run_script("--substitute-review", "42", "--dry-run", "--subject", "fix(scope): x")
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("result must be pass", result.stderr)

    def test_substitute_review_rejects_comment_on_another_pr(self):
        self.set_rate_limited()
        self.set_review_comment(issue_url="https://api.github.com/repos/owner/repo/issues/8")
        result = self.run_script("--substitute-review", "42", "--dry-run", "--subject", "fix(scope): x")
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("does not belong", result.stderr)

    def test_substitute_review_rejects_non_writer(self):
        self.set_rate_limited()
        (self.root / "permission.json").write_text(json.dumps({"permission": "read"}))
        result = self.run_script("--substitute-review", "42", "--dry-run", "--subject", "fix(scope): x")
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("write access", result.stderr)

    def test_substitute_review_rejects_bot_author(self):
        self.set_rate_limited()
        self.set_review_comment(user={"login": "reviewer", "type": "Bot"})
        result = self.run_script("--substitute-review", "42", "--dry-run", "--subject", "fix(scope): x")
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("non-bot", result.stderr)

    def test_substitute_review_must_follow_rate_limited_run(self):
        self.set_rate_limited()
        self.set_review_comment(created_at="2026-11-11T06:45:29Z")
        result = self.run_script("--substitute-review", "42", "--dry-run", "--subject", "fix(scope): x")
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("created after", result.stderr)

    def test_successful_coderabbit_rejects_substitute_argument(self):
        result = self.run_script("--substitute-review", "42", "--dry-run", "--subject", "fix(scope): x")
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("allowed only", result.stderr)

    def test_rate_limit_without_substitute_stops(self):
        self.set_rate_limited()
        result = self.run_script("--dry-run", "--subject", "fix(scope): x")
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("wait for capacity", result.stderr)
        self.assertIn("--substitute-review", result.stderr)

    def test_unexplained_neutral_coderabbit_is_rejected(self):
        self.check_runs[0].update(conclusion="neutral", output={"title": "Review skipped", "summary": ""})
        (self.root / "check-runs.json").write_text(json.dumps(self.check_runs))
        result = self.run_script("--dry-run", "--subject", "fix(scope): x")
        self.assertNotEqual(result.returncode, 0, result.stdout)
        self.assertIn("neutral without a verified completed review", result.stderr)
        self.assertFalse((self.root / "merge.log").exists())

    def test_wait_does_not_wait_on_rate_limited_neutral_coderabbit(self):
        self.check_runs[0].update(
            conclusion="neutral",
            output={"title": "Review rate limited", "summary": "Review skipped"},
        )
        (self.root / "check-runs.json").write_text(json.dumps(self.check_runs))
        self.install("sleep", "#!/usr/bin/env bash\nexit 99\n")
        result = self.run_script("--wait", "--dry-run", "--subject", "fix(scope): x")
        self.assertNotEqual(result.returncode, 0, result.stdout)
        self.assertIn("did not review this PR because it was rate limited", result.stderr)

    def test_completed_required_runs_started_same_second_as_ready_are_accepted(self):
        for check_run in self.check_runs:
            check_run["started_at"] = "2026-11-11T06:45:02Z"
        (self.root / "check-runs.json").write_text(json.dumps(self.check_runs))
        result = self.run_script("--dry-run", "--subject", "fix(scope): x")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("gh pr merge", result.stdout)
        self.assertFalse((self.root / "merge.log").exists())

    def test_wait_rechecks_missing_required_check_until_it_completes(self):
        self.env["MERGE_APPROVED_WAIT_SECONDS"] = "10"
        self.install("sleep", "#!/usr/bin/env bash\nexit 0\n")
        original = self.check_runs[:]
        self.check_runs = []
        (self.root / "check-runs.json").write_text(json.dumps(self.check_runs))
        self.install("gh", self.bin.joinpath("gh").read_text().replace(
            'print(json.dumps([{"check_runs": json.load(open(os.environ["GH_CHECK_RUNS"]))}]))',
            'p=os.environ["GH_CHECK_RUNS"]; f=os.environ["CHECK_CALLS_FILE"]; n=int(open(f).read()) if os.path.exists(f) else 0; open(f,"w").write(str(n+1)); data=json.load(open(p)); data = json.load(open(os.environ["GH_CHECK_RUNS_DONE"])) if n else data; print(json.dumps([{"check_runs":data}]))'))
        self.env["CHECK_CALLS_FILE"] = str(self.root / "check-calls")
        (self.root / "check-runs-done.json").write_text(json.dumps(original))
        self.env["GH_CHECK_RUNS_DONE"] = str(self.root / "check-runs-done.json")
        result = self.run_script("--wait", "--dry-run", "--subject", "fix(scope): x")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertGreaterEqual(int((self.root / "check-calls").read_text()), 2)

    def test_issue_actions_run_only_after_verified_merge(self):
        result = self.run_script("--close", "12", "--comment", "13=recorded", "--subject", "fix(scope): x")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("close 12", (self.root / "issue.log").read_text())
        self.assertIn("comment 13", (self.root / "issue.log").read_text())

    def test_merge_without_issue_actions_succeeds_on_every_bash(self):
        shells = ["bash"] + (["/bin/bash"] if Path("/bin/bash").exists() else [])
        for shell in shells:
            with self.subTest(shell=shell):
                (self.root / "pr.json").write_text(json.dumps(self.state))
                (self.root / "merge.log").unlink(missing_ok=True)
                result = subprocess.run([shell, str(SCRIPT), "owner/repo", "7", SHA, "--subject", "fix(scope): x"],
                                        input="", text=True, env=self.env, capture_output=True)
                self.assertEqual(result.returncode, 0, result.stderr)
                self.assertTrue((self.root / "merge.log").exists())
                self.assertFalse((self.root / "issue.log").exists())

    def test_substitute_review_accepts_crlf_marker(self):
        self.set_rate_limited()
        self.set_review_comment(body=f"Reviewed.\r\nindependent-review: head={SHA} result=pass\r\n")
        result = self.run_script("--dry-run", "--subject", "fix(scope): x", "--substitute-review", "42")
        self.assertEqual(result.returncode, 0, result.stderr)

    def test_issue_actions_stop_when_pr_is_not_merged(self):
        self.env["MERGE_LEAVES_OPEN"] = "1"
        result = self.run_script("--close", "12", "--comment", "13=recorded", "--subject", "fix(scope): x")
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("issue actions were not run", result.stderr)
        self.assertFalse((self.root / "issue.log").exists())

    def test_merge_body_rejects_unscoped_bump_version_lines(self):
        self.state["body"] = "Refs #12\nfix: this would bump the version"
        (self.root / "pr.json").write_text(json.dumps(self.state))
        result = self.run_script("--dry-run", "--subject", "fix(scope): x")
        self.assertNotEqual(result.returncode, 0, result.stdout)
        self.assertIn("merge body", result.stderr)

    def test_parent_loop_input_is_not_consumed_by_gh(self):
        command = f'while IFS= read -r line; do {SCRIPT} owner/repo 7 {SHA} --dry-run --subject "fix(scope): x" >/dev/null || exit; printf "%s\\n" "$line"; done'
        result = subprocess.run(["bash", "-c", command], input="first\nsecond\n", text=True,
                                env=self.env, capture_output=True)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(result.stdout, "first\nsecond\n")
        self.assertTrue(all(line == "null" for line in (self.root / "stdin.log").read_text().splitlines()))


if __name__ == "__main__":
    unittest.main()
