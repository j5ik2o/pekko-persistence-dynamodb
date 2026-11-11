import json
import os
from pathlib import Path
import subprocess
import tempfile
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
        self.review_exit = 0
        self.diff = ""
        self.inputs = []
        self.install("gh", '#!/usr/bin/env python3\nimport json, os, sys\n'
                     'open(os.environ["GH_INPUT_LOG"],"a").write("null\\n" if not sys.stdin.read() else "data\\n")\n'
                     'args=sys.argv[1:]\n'
                     'if args[0:2]==["pr","view"]: print(open(os.environ["GH_PR" ]).read())\n'
                     'elif args[0:2]==["pr","checks"]: print(open(os.environ["GH_CHECKS"]).read())\n'
                     'elif args[:2]==["pr","diff"]: print(os.environ.get("GH_DIFF",""))\n'
                     'elif args[0:2]==["pr","merge"]: open(os.environ["MERGE_LOG"],"w").write(" ".join(args))\n'
                     'elif args[:2]==["api","graphql"]: print(json.dumps({"data":{"repository":{"pullRequest":{"reviewThreads":{"pageInfo":{"hasNextPage":False},"nodes":[{"isResolved": os.environ.get("REVIEW_EXIT") != "1", "path":"x", "comments":{"nodes":[]}}]}}}}}))\n'
                     'else: raise SystemExit(2)\n')
        self.install("git", '#!/usr/bin/env python3\nimport os,sys\n'
                     'args=sys.argv[1:]\n'
                     'if args[:2]==["diff","--name-only"]: print(os.environ.get("GIT_DIFF",""))\n'
                     'elif args[:2]==["fetch","--quiet"]: pass\n'
                     'elif args[:2]==["log","-1"]: print(os.environ.get("GIT_SUBJECT","feat(storage): preserve values"))\n'
                     'else: raise SystemExit(2)\n')
        self.install("check-review-threads.sh", '#!/usr/bin/env bash\nexit "$REVIEW_EXIT"\n')
        (self.root / "pr.json").write_text(json.dumps(self.state))
        (self.root / "checks.json").write_text(json.dumps(self.checks))
        self.env = dict(os.environ, PATH=str(self.bin) + os.pathsep + os.environ["PATH"],
                        GH_PR=str(self.root / "pr.json"), GH_CHECKS=str(self.root / "checks.json"),
                        GH_INPUT_LOG=str(self.root / "stdin.log"), MERGE_LOG=str(self.root / "merge.log"),
                        REVIEW_EXIT="0")

    def install(self, name, content):
        path = self.bin / name
        path.write_text(content)
        path.chmod(0o755)

    def run_script(self, *args, stdin=""):
        return subprocess.run([str(SCRIPT), "owner/repo", "7", SHA, *args], input=stdin, text=True,
                              env=self.env, capture_output=True)

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
                result = self.run_script("--dry-run")
                self.assertNotEqual(result.returncode, 0, result.stdout)
                self.assertIn("error:", result.stderr)

    def test_dry_run_uses_full_git_subject_and_never_merges(self):
        self.env["GIT_SUBJECT"] = "feat(storage): retain complete subject"
        result = self.run_script("--dry-run")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn(r"feat\(storage\):\ retain\ complete\ subject\ \(#7\)", result.stdout)
        self.assertFalse((self.root / "merge.log").exists())

    def test_parent_loop_input_is_not_consumed_by_gh(self):
        command = f'while IFS= read -r line; do {SCRIPT} owner/repo 7 {SHA} --dry-run >/dev/null || exit; printf "%s\\n" "$line"; done'
        result = subprocess.run(["bash", "-c", command], input="first\nsecond\n", text=True,
                                env=self.env, capture_output=True)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(result.stdout, "first\nsecond\n")
        self.assertTrue(all(line == "null" for line in (self.root / "stdin.log").read_text().splitlines()))


if __name__ == "__main__":
    unittest.main()
