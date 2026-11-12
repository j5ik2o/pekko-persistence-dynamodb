import copy
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

TOOL = Path(__file__).parents[1] / "aislop-gate.py"
SPEC = importlib.util.spec_from_file_location("aislop_gate", TOOL)
gate = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(gate)

FIXTURES = Path(__file__).parent / "fixtures" / "aislop"


def load(name):
    return json.loads((FIXTURES / name).read_text())


BASELINE = {"version": 1, "entries": [{"rule": "ai-slop/unused-import", "engine": "ai-slop", "file": "A.scala",
                                       "fingerprint": "0f8c3c09de4e5a2f", "count": 1, "reason": "false positive"}]}


class EvaluateTests(unittest.TestCase):
    def setUp(self):
        self.full = load("scan-full.json")
        self.scala = load("scan-scala.json")

    def test_scan_with_scala_and_only_baselined_findings_passes(self):
        self.assertEqual(gate.evaluate(self.full, self.scala, BASELINE), [])

    def test_scan_without_any_supported_file_fails_on_coverage_and_scala(self):
        empty = load("scan-no-scala.json")
        problems = gate.evaluate(empty, empty, BASELINE)
        self.assertTrue(any("withheld the score" in p for p in problems), problems)
        self.assertTrue(any("analyzed no Scala file" in p for p in problems), problems)

    def test_withheld_score_fails(self):
        self.full["scoreable"] = False
        self.full["coverage"]["scoreable"] = False
        self.assertTrue(any("withheld the score" in p for p in gate.evaluate(self.full, self.scala, BASELINE)))

    def test_scan_that_reads_no_scala_file_fails_even_when_scoreable(self):
        problems = gate.evaluate(self.full, load("scan-no-scala.json"), BASELINE)
        self.assertEqual(len(problems), 1, problems)
        self.assertIn("analyzed no Scala file", problems[0])

    def test_finding_outside_the_baseline_fails(self):
        finding = self.full["diagnostics"][0]
        del finding["baselined"]
        problems = gate.evaluate(self.full, self.scala, BASELINE)
        self.assertEqual(problems, [f"new finding {finding['rule']} {finding['filePath']}:{finding['line']}: {finding['message']}"])

    def test_info_finding_outside_the_baseline_passes(self):
        finding = self.full["diagnostics"][0]
        del finding["baselined"]
        finding["severity"] = "info"
        self.assertEqual(gate.evaluate(self.full, self.scala, BASELINE), [])

    def test_missing_or_invalid_baseline_fails(self):
        for summary in (None, {"path": ".aislop/ci-baseline.json", "status": "missing", "accepted": 0, "new": 3, "stale": 0}):
            full = copy.deepcopy(self.full)
            full["baseline"] = summary
            self.assertTrue(any("did not apply the baseline" in p for p in gate.evaluate(full, self.scala, BASELINE)))

    def test_stale_baseline_entry_fails(self):
        self.full["baseline"]["stale"] = 2
        self.assertEqual(gate.evaluate(self.full, self.scala, BASELINE),
                         ["2 baseline finding(s) no longer occur; remove the entries"])

    def test_baseline_entry_without_reason_fails(self):
        baseline = copy.deepcopy(BASELINE)
        baseline["entries"][0]["reason"] = " "
        self.assertEqual(gate.evaluate(self.full, self.scala, baseline),
                         ["baseline entry without a reason: ai-slop/unused-import A.scala"])

    def test_checked_in_baseline_gives_every_entry_a_reason(self):
        baseline = json.loads(gate.BASELINE.read_text())
        self.assertTrue(baseline["entries"])
        self.assertEqual([e for e in baseline["entries"] if not e.get("reason", "").strip()], [])


FAKE_AISLOP = """import sys
from pathlib import Path
name = "scala" if "--include" in sys.argv else "full"
sys.stdout.write(Path(sys.argv[0]).with_name(name + ".json").read_text())
sys.exit(int(Path(sys.argv[0]).with_name("exit").read_text()))
"""


class MainTests(unittest.TestCase):
    def run_gate(self, full, scala, exit_code=0, stdout=None):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            script = root / "aislop.py"
            script.write_text(FAKE_AISLOP)
            (root / "full.json").write_text(stdout if stdout is not None else json.dumps(full))
            (root / "scala.json").write_text(json.dumps(scala))
            (root / "exit").write_text(str(exit_code))
            wrapper = root / "aislop"
            wrapper.write_text(f"#!/bin/sh\nexec {sys.executable} {script} \"$@\"\n")
            wrapper.chmod(0o755)
            env = dict(os.environ, AISLOP=str(wrapper))
            return subprocess.run([sys.executable, str(TOOL)], env=env, capture_output=True, text=True)

    def test_clean_scan_exits_zero(self):
        result = self.run_gate(load("scan-full.json"), load("scan-scala.json"))
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("227 Scala; 3 accepted, 0 new, 0 stale", result.stderr)

    def test_new_finding_exits_one_even_though_aislop_exits_one(self):
        full = load("scan-full.json")
        del full["diagnostics"][0]["baselined"]
        result = self.run_gate(full, load("scan-scala.json"), exit_code=1)
        self.assertEqual(result.returncode, 1, result.stderr)
        self.assertIn("new finding", result.stderr)

    def test_scan_without_scala_exits_one(self):
        result = self.run_gate(load("scan-no-scala.json"), load("scan-no-scala.json"))
        self.assertEqual(result.returncode, 1, result.stderr)
        self.assertIn("analyzed no Scala file", result.stderr)

    def test_report_that_is_not_json_exits_two(self):
        result = self.run_gate(None, load("scan-scala.json"), exit_code=1, stdout="boom")
        self.assertEqual(result.returncode, 2, result.stderr)
        self.assertIn("without a JSON report", result.stderr)

    def test_report_without_coverage_exits_two(self):
        result = self.run_gate({"diagnostics": []}, load("scan-scala.json"))
        self.assertEqual(result.returncode, 2, result.stderr)
        self.assertIn("unexpected aislop report", result.stderr)

    def test_missing_aislop_exits_two(self):
        env = dict(os.environ, AISLOP="/nonexistent/aislop")
        result = subprocess.run([sys.executable, str(TOOL)], env=env, capture_output=True, text=True)
        self.assertEqual(result.returncode, 2, result.stderr)
        self.assertIn("cannot run", result.stderr)


if __name__ == "__main__":
    unittest.main()
