"""Tests for tools/quint-check-specs.sh with a fake quint.

The fake reads `// fake: ...` lines of the spec it is given:
  // fake: typecheck-fail
  // fake: module <name>                     (default: the file name)
  // fake: runs <name> ...                   (the `run` definitions of the module)
  // fake: passing <n>                       (default: the number of runs)
  // fake: test-fail
  // fake: run <init> <ok|violation|error> [max=<n>] [state1=no] [joined] [witness=<name>:<n>,...]
The run behaviour is chosen by the --init argument (default: init). Every call is
logged to $FAKE_QUINT_LOG as one JSON array of arguments."""
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
SCRIPT = ROOT / "tools" / "quint-check-specs.sh"

FAKE_QUINT = r'''#!/usr/bin/env python3
import json, os, re, sys

args = sys.argv[1:]
with open(os.environ["FAKE_QUINT_LOG"], "a") as log:
    log.write(json.dumps(args) + "\n")
if args == ["--version"]:
    print(os.environ.get("FAKE_QUINT_VERSION", "0.32.0"))
    sys.exit(0)
command, spec = args[0], next(a for a in args[1:] if a.endswith(".qnt"))
directives = [line.split("// fake:", 1)[1].split() for line in open(spec) if "// fake:" in line]
def directive(name):
    return next((d[1:] for d in directives if d[0] == name), None)
base = os.path.basename(spec)[:-4]
runs = directive("runs") or []

def value(flag, default=None):
    for i, a in enumerate(args):
        if a == flag:
            return args[i + 1]
        if a.startswith(flag + "="):
            return a.split("=", 1)[1]
    return default

if command == "typecheck":
    sys.exit(1 if directive("typecheck-fail") is not None else 0)
if command == "parse":
    module = (directive("module") or [base])[0]
    ir = {"modules": [{"name": module, "declarations":
          [{"qualifier": "run", "name": r} for r in runs] + [{"qualifier": "val", "name": "helper"}]}]}
    open(value("--out"), "w").write(json.dumps(ir))
    sys.exit(0)
if command == "test":
    passing = int((directive("passing") or [len(runs)])[0])
    print(f"  {base}")
    for r in runs:
        print(f"    ok {r} passed 1 test(s)")
    print(f"  {passing} passing (1ms)")
    sys.exit(1 if directive("test-fail") is not None else 0)
if command == "run":
    sys.stdin.read()   # a quint that reads stdin must not eat the rest of ci-runs.txt
    init = value("--init", "init")
    plan = next((d[1:] for d in directives if d[0] == "run" and d[1] == init), ["init", "ok"])
    outcome, options = plan[1], dict(o.split("=", 1) if "=" in o else (o, "yes") for o in plan[2:])
    verbose = value("--verbosity") == "2"
    if outcome == "error":
        print("error: [QNT404] Name 'x' not found")
        sys.exit(1)
    if verbose:
        print("An example execution:")
        print("[State 0]")
        if options.get("state1", "yes") == "yes":
            print("[State 1]")
    if outcome == "violation":
        print("[violation] Found an issue (1ms at 10 traces/second).")
        print("Use --seed=0x1 --backend=rust to reproduce.")
        print("error: Invariant violated")
        sys.exit(1)
    prefix = "Running... 57 samples/s" if "joined" in options else ""
    print(f"{prefix}[ok] No violation found (1ms at 10 traces/second).")
    if verbose and "max" in options and options["max"] != "none":
        print(f"Trace length statistics: max={options['max']}, min=1, average=1.00")
    witnesses = dict(w.split(":") for w in options.get("witness", "").split(",") if w)
    listed = []
    for i, a in enumerate(args):
        if a == "--witnesses":
            listed = []
            for b in args[i + 1:]:
                if b.startswith("--"):
                    break
                listed.append(b)
    if listed:
        print("Witnesses:")
        for w in listed:
            if w in witnesses:
                print(f"{w} was witnessed in {witnesses[w]} trace(s) out of 10 explored (10.00%)")
    print("Use --seed=0x1 --backend=rust to reproduce.")
    sys.exit(0)
sys.exit(2)
'''

SPEC = "module spec {}\n// fake: run init ok max=5 witness=wA:4\n"
TEST = "module spec_test {}\n// fake: runs aTest bTest\n"
RUNS = "# comment\n\npass spec.qnt --invariant safety --max-samples 10 --max-steps 5 --seed 0x7 --witnesses wA\n"


class QuintCheckSpecsTests(unittest.TestCase):
    def setUp(self):
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        self.root = Path(temp.name)
        (self.root / "package-lock.json").write_text(json.dumps(
            {"packages": {"node_modules/@informalsystems/quint": {"version": "0.32.0"}}}))
        self.quint = self.root / "fake-quint"
        self.quint.write_text(FAKE_QUINT)
        self.quint.chmod(0o755)
        self.log = self.root / "quint-calls"
        self.dir = self.root / "specs" / "one"
        self.write("spec.qnt", SPEC)
        self.write("spec_test.qnt", TEST)
        self.write("ci-runs.txt", RUNS)

    def write(self, name, text, directory=None):
        path = (directory or self.dir) / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(text)
        return path

    def check(self, **env):
        result = subprocess.run(["bash", str(SCRIPT)], cwd=self.root, capture_output=True, text=True,
                                env=dict(os.environ, QUINT=str(self.quint), QUINT_SPECS_DIR="specs",
                                         FAKE_QUINT_LOG=str(self.log), **env))
        return result, result.stdout + result.stderr

    def calls(self, command):
        if not self.log.exists():
            return []
        return [c for c in map(json.loads, self.log.read_text().splitlines()) if c[0] == command]

    def assert_fails(self, message, **env):
        result, out = self.check(**env)
        self.assertEqual(result.returncode, 1, out)
        self.assertIn(message, out)
        return out

    def test_passes_and_runs_every_step_with_the_fixed_seed(self):
        result, out = self.check()
        self.assertEqual(result.returncode, 0, out)
        self.assertIn("Every Quint spec passed.", out)
        self.assertEqual(sorted(c[1] for c in self.calls("typecheck")), ["specs/one/spec.qnt", "specs/one/spec_test.qnt"])
        self.assertEqual(self.calls("test"), [["test", "specs/one/spec_test.qnt", "--main", "spec_test",
                                               "--seed", "0x7", "--max-samples", "10000"]])
        run = self.calls("run")
        self.assertEqual(len(run), 1)
        self.assertEqual(run[0][:2], ["run", "specs/one/spec.qnt"])
        self.assertEqual(run[0][-1], "--verbosity=2")
        self.assertIn("wA was witnessed in 4 trace(s)", out)

    def test_runs_every_line_when_quint_reads_stdin(self):
        self.write("ci-runs.txt", RUNS + RUNS.splitlines()[-1].replace("--seed 0x7", "--seed 0x8") + "\n")
        result, out = self.check()
        self.assertEqual(result.returncode, 0, out)
        self.assertEqual(len(self.calls("run")), 2)

    def test_reads_the_flag_equals_value_form(self):
        self.write("ci-runs.txt", "pass spec.qnt --invariant=safety --max-samples=10 --max-steps=5 --seed=0x7\n")
        result, out = self.check()
        self.assertEqual(result.returncode, 0, out)

    def test_accepts_ok_joined_to_a_progress_bar(self):
        self.write("spec.qnt", SPEC.replace("max=5", "max=5 joined"))
        result, out = self.check()
        self.assertEqual(result.returncode, 0, out)

    def test_fails_on_another_quint_version(self):
        out = self.assert_fails("quint 0.31.0 is not the 0.32.0 that package-lock.json pins", FAKE_QUINT_VERSION="0.31.0")
        self.assertEqual(self.calls("typecheck"), [])

    def test_fails_without_any_spec(self):
        for path in self.dir.iterdir():
            path.unlink()
        self.assert_fails("::error::No Quint spec matched specs/*/*.qnt")

    def test_fails_on_a_spec_outside_specs_dir_file(self):
        for place in (self.root / "specs" / "top.qnt", self.root / "specs" / "one" / "deep" / "x.qnt"):
            with self.subTest(place=str(place)):
                place.parent.mkdir(parents=True, exist_ok=True)
                place.write_text(SPEC)
                self.assert_fails("a spec must be at specs/<dir>/<file>.qnt to be checked")
                place.unlink()

    def test_fails_on_a_typecheck_error_and_checks_the_other_directories(self):
        self.write("spec.qnt", SPEC + "// fake: typecheck-fail\n")
        other = self.root / "specs" / "two"
        self.write("spec.qnt", SPEC, other)
        self.write("spec_test.qnt", TEST, other)
        self.write("ci-runs.txt", RUNS, other)
        out = self.assert_fails("::error file=specs/one/spec.qnt::quint typecheck failed")
        self.assertIn("::error file=specs/one::quint check failed", out)
        self.assertNotIn("::error file=specs/two::", out)
        self.assertIn("specs/two/spec.qnt", [c[1] for c in self.calls("run")])

    def test_fails_without_a_test_file(self):
        (self.dir / "spec_test.qnt").unlink()
        self.assert_fails("specs/one has no *_test.qnt")

    def test_fails_on_a_run_that_quint_test_skips(self):
        self.write("spec_test.qnt", TEST.replace("bTest", "rejectedStaysAtHead"))
        self.assert_fails("do not end in Test: rejectedStaysAtHead")

    def test_fails_on_a_test_file_without_runs(self):
        self.write("spec_test.qnt", "module spec_test {}\n")
        self.assert_fails("module spec_test defines no run")

    def test_fails_when_quint_test_passes_fewer_tests_than_runs(self):
        self.write("spec_test.qnt", TEST + "// fake: passing 1\n")
        self.assert_fails("quint test passed 1 test(s) but module spec_test defines 2 run(s)")

    def test_fails_when_quint_test_fails(self):
        self.write("spec_test.qnt", TEST + "// fake: test-fail\n")
        self.assert_fails("::error file=specs/one/spec_test.qnt::quint test failed")

    def test_fails_when_no_module_is_named_after_the_test_file(self):
        self.write("spec_test.qnt", TEST + "// fake: module other\n")
        self.assert_fails("no module is named spec_test")

    def test_fails_without_ci_runs(self):
        (self.dir / "ci-runs.txt").unlink()
        self.assert_fails("specs/one/ci-runs.txt is missing")

    def test_fails_on_a_spec_that_no_line_runs(self):
        self.write("other.qnt", SPEC)
        self.assert_fails("specs/one/ci-runs.txt runs no quint run of other.qnt")

    def test_fails_on_lines_that_check_less_than_they_seem(self):
        line = "pass spec.qnt --invariant safety --max-samples 10 --max-steps 5 --seed 0x7"
        cases = {
            line.replace(" --seed 0x7", ""): "line 1: --seed is required",
            line.replace(" --max-samples 10", ""): "line 1: --max-samples is required",
            line.replace(" --max-steps 5", ""): "line 1: --max-steps is required",
            line.replace(" --invariant safety", ""): "line 1: --invariant or --invariants is required",
            line.replace("--invariant safety", "--invariant true"): "line 1: the invariant 'true' checks nothing",
            line.replace("--invariant safety", "--invariants a true"): "line 1: the invariant 'true' checks nothing",
            line.replace("--max-samples 10", "--max-samples 1"): "line 1: --max-samples must be 2 or more, not '1'",
            line.replace("--max-samples 10", "--max-samples=1"): "line 1: --max-samples must be 2 or more, not '1'",
            line.replace("--max-steps 5", "--max-steps 0"): "line 1: --max-steps must be 1 or more, not '0'",
            line + " --verbosity 3": "line 1: do not set --verbosity",
            line.replace("pass ", "fail "): "line 1: the first word must be pass or violation",
            line.replace("spec.qnt", "../one/spec.qnt"): "line 1: ../one/spec.qnt is not a .qnt file of specs/one",
            line.replace("spec.qnt", "missing.qnt"): "line 1: missing.qnt is not a .qnt file of specs/one",
            "pass": "line 1: expected '<pass|violation> <file> <args>'",
        }
        for text, message in cases.items():
            with self.subTest(line=text):
                self.write("ci-runs.txt", text + "\n")
                self.assert_fails(message)

    def test_does_not_expand_the_arguments_of_a_line(self):
        (self.root / "wA-file").write_text("")
        self.write("ci-runs.txt", "pass spec.qnt --invariant safety --max-samples 10 --max-steps 5 --seed 0x7 "
                                  "--hide w* $(touch pwned)\n")
        result, out = self.check()
        self.assertEqual(result.returncode, 0, out)
        self.assertIn("w*", self.calls("run")[0])
        self.assertIn("$(touch", self.calls("run")[0])
        self.assertFalse((self.root / "pwned").exists())

    def test_fails_when_a_pass_line_finds_a_violation(self):
        self.write("spec.qnt", SPEC.replace("run init ok", "run init violation"))
        self.assert_fails("line 3: quint run failed (exit 1)")

    def test_fails_when_no_trace_takes_a_step(self):
        self.write("spec.qnt", SPEC.replace("max=5", "max=1"))
        self.assert_fails("line 3: no trace took a step (max trace length 1)")

    def test_fails_without_trace_length_statistics(self):
        self.write("spec.qnt", SPEC.replace("max=5", "max=none"))
        self.assert_fails("line 3: quint run printed no trace length statistics")

    def test_fails_on_a_witness_that_no_trace_sees(self):
        for witness in ("witness=wA:0", "witness=wB:3"):
            with self.subTest(witness=witness):
                self.write("spec.qnt", SPEC.replace("witness=wA:4", witness))
                self.assert_fails("line 3: witness wA was seen in")

    def test_accepts_a_violation_line_whose_counterexample_takes_a_step(self):
        self.write("spec.qnt", SPEC + "// fake: run initBad violation\n")
        self.write("ci-runs.txt", RUNS + "violation spec.qnt --init initBad --invariant noLoss "
                                         "--max-samples 10 --max-steps 5 --seed 0x7\n")
        result, out = self.check()
        self.assertEqual(result.returncode, 0, out)

    def test_fails_on_a_violation_line_that_finds_no_violation(self):
        self.write("ci-runs.txt", RUNS + "violation spec.qnt --invariant noLoss --max-samples 10 --max-steps 5 --seed 0x7\n")
        self.assert_fails("line 4: quint run found no violation, but the line expects one")

    def test_fails_on_a_violation_line_that_fails_for_another_reason(self):
        self.write("spec.qnt", SPEC + "// fake: run initBad error\n")
        self.write("ci-runs.txt", RUNS + "violation spec.qnt --init initBad --invariant noLoss "
                                         "--max-samples 10 --max-steps 5 --seed 0x7\n")
        self.assert_fails("line 4: quint run failed without an invariant violation")

    def test_fails_on_a_violation_in_the_initial_state(self):
        self.write("spec.qnt", SPEC + "// fake: run initBad violation state1=no\n")
        self.write("ci-runs.txt", RUNS + "violation spec.qnt --init initBad --invariant noLoss "
                                         "--max-samples 10 --max-steps 5 --seed 0x7\n")
        self.assert_fails("line 4: the counterexample takes no step")


if __name__ == "__main__":
    unittest.main()
