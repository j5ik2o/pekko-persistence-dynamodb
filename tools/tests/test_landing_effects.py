import importlib.util
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

SPEC = importlib.util.spec_from_file_location("landing_effects", Path(__file__).parents[1] / "landing-effects.py")
le = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(le)

PUBLISH = """name: Publish
on:
  push:
    branches: [main]
    tags: [v*]
jobs:
  publish:
    if: github.repository_owner == 'j5ik2o'
    strategy:
      matrix:
        java: [11, 17]
    steps:
      - uses: actions/checkout@v5
      - run: sbt +publishSigned
"""
CI = """name: CI
on: [push, pull_request]
jobs:
  test:
    steps:
      - uses: actions/checkout@v5
      - run: ./tools/test.sh
"""


class EvidenceTests(unittest.TestCase):
    def report(self, definitions, **changes):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            workflows = []
            for i, (name, state, raw) in enumerate(definitions):
                filename = f"{i}.yaml"
                (root / filename).write_text(raw)
                workflows.append(dict(path=f".github/workflows/{name}.yml", state=state,
                    git_blob_sha=str(i), source_revision="a" * 40, raw_file=filename,
                    raw_sha256=le.digest(raw.encode())))
            snapshot = dict(repository="fixture/repo", destination_branch="main", collection_complete=True,
                            inventories=dict(base=workflows, head=workflows), unknowns=[])
            snapshot.update(changes)
            le.save(root / "snapshot.json", snapshot)
            return le.analyze(root / "snapshot.json")

    def test_ppd_publish_survives_disabled_bump(self):
        report = self.report([("publish", "active", PUBLISH), ("Bump", "disabled_manually", CI)])
        publish = report["workflows"][0]
        self.assertEqual(publish["trigger"]["status"], "potential")
        self.assertEqual(publish["state"], "active")
        self.assertIn("publish", {x["kind"] for x in publish["effect_candidates"]})
        self.assertIn("/jobs/publish/steps/1/run", {x["pointer"] for x in publish["effect_candidates"]})
        self.assertIn("run_or_local_script", {x["kind"] for x in report["unknowns"]})
        self.assertIn("if", publish["conditions"]["jobs"]["publish"])
        self.assertIn("strategy", publish["conditions"]["jobs"]["publish"])
        self.assertFalse(report["prospective_tree_verified"])

    def test_example_ci_inventory_keeps_opaque_unknowns(self):
        report = self.report([("ci", "active", CI)])
        self.assertFalse(report["workflows"][0]["effect_candidates"])
        self.assertTrue({"external_action", "run_or_local_script"}.issubset({x["kind"] for x in report["unknowns"]}))

    def test_unquoted_on(self):
        self.assertIn("on", le.load_yaml(PUBLISH.encode()))

    def test_literal_trigger_controls(self):
        for config, expected in [("push", "potential"), (["push"], "potential"),
                ({"push": ""}, "potential"), ({"push": {}}, "potential"),
                ({"push": {"branches": ["main"]}}, "potential"),
                ({"push": {"branches": ["other"]}}, "excluded"),
                ({"push": {"tags": ["v1"]}}, "excluded"),
                ({"push": {"tags": ["v*"]}}, "excluded"),
                ({"pull_request": ""}, "excluded"),
                ({"push": {"branches": ["m*"]}}, "unknown"),
                ({"push": {"branches": ["!main"]}}, "unknown"),
                ({"push": {"paths": ["**"]}}, "unknown"),
                ({"push": {"branches": "main"}}, "unknown"),
                (False, "unknown")]:
            with self.subTest(config=config):
                result = le.push_trigger(config, "main")
                self.assertEqual(result["status"], expected)
                self.assertEqual(result["raw"], config)
                self.assertEqual(result["pointer"], "/on")

    def test_reusable_cycle_is_explicitly_unexpanded(self):
        raw = "on: push\njobs:\n  loop:\n    uses: ./.github/workflows/loop.yml\n"
        report = self.report([("loop", "active", raw)])
        self.assertIn("unexpanded_reusable_workflow", {x["kind"] for x in report["unknowns"]})

    def test_indirect_candidates_separate(self):
        raw = CI.replace("on: [push, pull_request]", "on:\n  workflow_run:\n    workflows: [CI]\n    branches: [main]\n    types: [completed]")
        report = self.report([("after", "active", raw)])
        self.assertEqual(report["workflows"][0]["trigger"]["status"], "excluded")
        self.assertEqual(report["indirect_candidates"][0]["raw"]["workflows"], ["CI"])

    def test_bad_yaml(self):
        bad = ["on: push\non: pull_request\njobs: {}", "on: push\njobs: &j {a: *j}",
               "on: [push\njobs: {}", "on: push\njobs: []", "on: push\njobs: {a: null}",
               "on: push\njobs: {a: {steps: [bad]}}", "on: push\njobs: {a: {uses: []}}", "on: []\njobs: {}", "on: {push: []}\njobs: {}"]
        for raw in bad:
            with self.subTest(raw=raw):
                report = self.report([("bad", "active", raw)])
                self.assertTrue(report["parse_failures"])
                self.assertEqual(report["workflows"][0]["trigger"]["status"], "unknown")

    def test_missing_collection(self):
        report = self.report([], collection_complete=False)
        self.assertIn("missing_collection", {x["kind"] for x in report["unknowns"]})

    def test_shared_aliases_remain_unknown(self):
        raw = "on: push\njobs:\n  test:\n    steps:\n      - &step {run: echo hello}\n      - *step\n"
        report = self.report([("shared", "active", raw)])
        self.assertTrue(report["parse_failures"])
        self.assertEqual(report["workflows"][0]["trigger"]["status"], "unknown")
        self.assertIn("shared YAML alias", report["workflows"][0]["trigger"]["reason"])

    def test_alias_fanout_returns_unknown_without_expansion(self):
        raw = "on: push\njobs: {}\na0: &a0 [leaf]\n" + "".join(
            f"a{i}: &a{i} [*a{i - 1}, *a{i - 1}]\n" for i in range(1, 28))
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "raw.yaml").write_text(raw)
            le.save(root / "snapshot.json", dict(collection_complete=True, destination_branch="main",
                inventories={"base": [dict(path="fanout.yml", state="active", raw_file="raw.yaml",
                    raw_sha256=le.digest(raw.encode()))]}))
            process = subprocess.run([sys.executable, str(Path(le.__file__)), "analyze",
                str(root / "snapshot.json")], capture_output=True, text=True, timeout=3)
            self.assertEqual(process.returncode, 1, process.stdout + process.stderr)
            report = json.loads(process.stdout)
            self.assertTrue(report["parse_failures"])
            self.assertEqual(report["workflows"][0]["trigger"]["status"], "unknown")
            self.assertIn("shared YAML alias", report["workflows"][0]["trigger"]["reason"])

    def test_drift_and_changed_workflow_remain_visible(self):
        report = self.report([("publish", "active", PUBLISH)],
                             unknowns=[dict(kind="observation_drift")], inventories={"base": []})
        self.assertIn("observation_drift", {x["kind"] for x in report["unknowns"]})

    def test_changed_workflow_inventory(self):
        report = self.report([], inventories={"base": [], "head": [dict(
            path=".github/workflows/new.yml", git_blob_sha="new", raw_file=None)]})
        self.assertIn("changed_workflow_inventory", {x["kind"] for x in report["unknowns"]})

    def test_cli_exit_codes(self):
        script = str(Path(le.__file__))
        invalid = subprocess.run([sys.executable, script, "collect"], capture_output=True)
        self.assertEqual(invalid.returncode, 2)
        with tempfile.TemporaryDirectory() as d:
            snapshot = Path(d) / "snapshot.json"
            le.save(snapshot, dict(collection_complete=False, inventories={}))
            missing = subprocess.run([sys.executable, script, "analyze", str(snapshot)], capture_output=True)
            self.assertEqual(missing.returncode, 1)
            self.assertIn(b"missing_collection", missing.stdout)

    def test_raw_hash_mismatch(self):
        with tempfile.TemporaryDirectory() as d:
            root = Path(d)
            (root / "raw").write_text(CI)
            le.save(root / "snapshot.json", dict(collection_complete=True, inventories={"base": [
                dict(path="ci", raw_file="raw", raw_sha256="wrong")]}))
            self.assertTrue(le.analyze(root / "snapshot.json")["parse_failures"])

    def test_managed_404_and_real_exit_recorded(self):
        with tempfile.TemporaryDirectory() as d:
            collector = le.Collector(Path(d))
            response = subprocess.CompletedProcess([], 1, b'{"status":"404"}', b'HTTP 404')
            with patch.object(le.subprocess, "run", return_value=response) as run:
                self.assertIsNone(collector.get("repos/fixture/repo/contents/dynamic/dependabot", allow_404=True))
            self.assertFalse(collector.failed)
            self.assertEqual(collector.sources[0]["exit_code"], 1)
            self.assertEqual(collector.unknowns[0]["kind"], "managed_workflow_404")
            self.assertEqual(run.call_args.args[0][:4], ["gh", "api", "--method", "GET"])

    def test_missing_dependency_has_diagnostic(self):
        import builtins
        original = builtins.__import__
        def missing(name, *args, **kwargs):
            if name == "yaml":
                raise ImportError("fixture")
            return original(name, *args, **kwargs)
        with patch("builtins.__import__", side_effect=missing):
            with self.assertRaisesRegex(ValueError, "PyYAML required"):
                le.load_yaml(CI.encode())

    def test_collect_checks_sha_and_states_before_after(self):
        import argparse
        with tempfile.TemporaryDirectory() as d:
            responses = [dict(commit=dict(sha="a" * 40)), dict(base=dict(ref="main", sha="a" * 40), head=dict(sha="b" * 40), state="open"),
                         dict(workflows=[], total_count=0), dict(tree=[]), dict(tree=[]), {}, {},
                         dict(commit=dict(sha="c" * 40)), dict(base=dict(ref="main", sha="a" * 40), head=dict(sha="d" * 40), state="open"),
                         dict(workflows=[dict(path="ci", state="active")])]
            def api(args, **kwargs):
                self.assertEqual(args[:4], ["gh", "api", "--method", "GET"])
                return subprocess.CompletedProcess(args, 0, json.dumps(responses.pop(0)).encode(), b"")
            out = Path(d) / "evidence"
            with patch.object(le.subprocess, "run", side_effect=api), patch("builtins.print"):
                self.assertEqual(le.collect(argparse.Namespace(out=out, repo="fixture/repo", branch="main", pr=1, head=None)), 0)
            snapshot = json.loads((out / "snapshot.json").read_text())
            self.assertEqual(snapshot["observed_head_sha"], "b" * 40)
            self.assertIn("observation_drift", {x["kind"] for x in snapshot["unknowns"]})
            self.assertFalse(responses)

    def test_collect_records_invalid_workflow_state_response_as_unknown(self):
        import argparse
        invalid_responses = [
            dict(workflows=None, total_count=0),
            dict(workflows={}, total_count=0),
            [dict(path="ci", state="active")],
        ]
        for invalid in invalid_responses:
            with self.subTest(invalid=invalid), tempfile.TemporaryDirectory() as d:
                responses = [dict(commit=dict(sha="a" * 40)),
                    dict(base=dict(ref="main", sha="a" * 40), head=dict(sha="b" * 40), state="open"),
                    dict(workflows=[], total_count=0), dict(tree=[]), dict(tree=[]), {}, {},
                    dict(commit=dict(sha="a" * 40)),
                    dict(base=dict(ref="main", sha="a" * 40), head=dict(sha="b" * 40), state="open"),
                    invalid]

                def api(args, **kwargs):
                    return subprocess.CompletedProcess(args, 0, json.dumps(responses.pop(0)).encode(), b"")

                out = Path(d) / "evidence"
                with patch.object(le.subprocess, "run", side_effect=api), patch("builtins.print"):
                    result = le.collect(argparse.Namespace(out=out, repo="fixture/repo", branch="main", pr=1, head=None))
                snapshot = json.loads((out / "snapshot.json").read_text())
                self.assertEqual(result, 1)
                kinds = {x["kind"] for x in snapshot["unknowns"]}
                expected = "collection_failure" if isinstance(invalid, list) else "invalid_workflow_state_inventory"
                self.assertIn(expected, kinds)


if __name__ == "__main__":
    unittest.main()
