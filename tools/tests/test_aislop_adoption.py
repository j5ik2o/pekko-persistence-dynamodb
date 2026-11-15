"""Check the repository's adoption of the canonical aislop gate (j5ik2o/ci, tag aislop-check-v3).

The canonical checker and bootstrap live in j5ik2o/ci. This repository keeps
only its own data (the accepted list) and a verbatim copy of the bootstrap.
scripts/aislop-check-pin.json pins the two canonical files by ref and SHA-256;
the SHA-256 of the bootstrap copy is fixed by PINNED_BOOTSTRAP_SHA256 below. These
tests read the checked-in adoption instead of the network, and run from the
TAKT verify_tools step of the ppd-tools-change workflow.
"""
import hashlib
import json
from pathlib import Path
import re
import unittest

ROOT = Path(__file__).resolve().parents[2]
SCRIPTS = ROOT / "scripts"
BOOTSTRAP = SCRIPTS / "aislop-check-bootstrap.mjs"
PIN = SCRIPTS / "aislop-check-pin.json"
ACCEPTED = SCRIPTS / "aislop-accepted.json"
PACKAGE = ROOT / "package.json"

PINNED_REF = "aislop-check-v3"
PINNED_BOOTSTRAP_SHA256 = "04f58b167f7b94066f7afe294cd0289f160efa5a768818ef1aa343ab26ab54e5"
PINNED_CHECKER_SHA256 = "c53e1b91dd45ead4bfdfd3dc4bbec794e2fd81b96857a90f7c5aaea414f8e30c"
PINNED_TEST_SHA256 = "95dda876c46cea1ca3be454d82be31080ecf9ff1f387d0a06ea014e5bc1c9130"

V2_HASHES = {
    "3cb6f3059008382927c73f10be2ea2cdede9c31ff4821f36c6cb074f9873453a",
    "cc737d15f89bf6985c0e545ddcd792aa3f7584b76982ef1679052e7b94b109bb",
    "19e08330e04e871ef67ecfc0184c6fc88f14d882ca0da3fd382cc281b20de201",
}

WORKFLOWS = [
    ROOT / ".takt" / "workflows" / "ppd-code-change.yaml",
    ROOT / ".takt" / "workflows" / "ppd-tools-change.yaml",
]


def sha256(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


class BootstrapCopyTest(unittest.TestCase):
    def test_bootstrap_is_checked_in(self):
        self.assertTrue(BOOTSTRAP.is_file(), f"missing {BOOTSTRAP.relative_to(ROOT)}")

    def test_bootstrap_is_the_pinned_copy(self):
        self.assertEqual(sha256(BOOTSTRAP), PINNED_BOOTSTRAP_SHA256)

    def test_bootstrap_is_not_the_v2_copy(self):
        self.assertNotIn(sha256(BOOTSTRAP), V2_HASHES)

    def test_bootstrap_caches_under_node_modules(self):
        text = BOOTSTRAP.read_text(encoding="utf-8")
        self.assertIn('path.join(ROOT, "node_modules", ".aislop-check", pin.ref)', text)


class PinTest(unittest.TestCase):
    def setUp(self):
        self.pin = json.loads(PIN.read_text(encoding="utf-8"))
        self.bootstrap_source = BOOTSTRAP.read_text(encoding="utf-8")

    def test_pin_points_at_the_pinned_ref(self):
        self.assertEqual(self.pin["ref"], PINNED_REF)

    def test_pin_records_the_pinned_canonical_hashes(self):
        self.assertEqual(self.pin["files"]["check-aislop.mjs"], PINNED_CHECKER_SHA256)
        self.assertEqual(self.pin["files"]["check-aislop.test.mjs"], PINNED_TEST_SHA256)

    def test_pin_keeps_no_v2_value(self):
        joined = PIN.read_text(encoding="utf-8").lower()
        for value in V2_HASHES:
            self.assertNotIn(value, joined)
        self.assertNotIn("aislop-check-v2", joined)

    def test_pin_covers_exactly_the_two_canonical_files(self):
        self.assertEqual(sorted(self.pin), ["files", "ref"])
        self.assertEqual(
            sorted(self.pin["files"]),
            ["check-aislop.mjs", "check-aislop.test.mjs"],
        )

    def test_bootstrap_requested_names_are_keys_of_the_pin(self):
        # The bootstrap fetches each canonical file by name and verifies it against
        # the pin hash; a pin key the bootstrap does not request (or a missing key)
        # breaks the check.
        requested = set(
            re.findall(r'fetchWithinBudget\("([^"]+)"\)', self.bootstrap_source)
        )
        self.assertEqual(requested, {"check-aislop.mjs", "check-aislop.test.mjs"})
        self.assertEqual(requested, set(self.pin["files"]))

    def test_bootstrap_passes_pin_files_to_the_fetcher(self):
        # Each fetched file must be verified against the pin hash for that name.
        self.assertIsNotNone(
            re.search(
                r"fetchCanonicalFile\(\s*pin\.ref,\s*name,\s*pin\.files\[name\],",
                self.bootstrap_source,
            )
        )

    def test_bootstrap_uses_the_pin_ref_for_the_fetch_url_and_cache(self):
        # The fetched URL and the cache path must both use pin.ref, so the pin's
        # ref stays the single source of which canonical revision is used.
        self.assertIn("${RAW_BASE}/${ref}/aislop/${name}", self.bootstrap_source)
        self.assertIn('path.join(ROOT, "node_modules", ".aislop-check", pin.ref)', self.bootstrap_source)
        self.assertEqual(self.pin["ref"], PINNED_REF)


class PackageTest(unittest.TestCase):
    def setUp(self):
        self.package = json.loads(PACKAGE.read_text(encoding="utf-8"))

    def test_aislop_is_pinned_exactly(self):
        self.assertEqual(self.package["devDependencies"]["aislop"], "0.20.1")

    def test_scripts_run_the_bootstrap(self):
        scripts = self.package["scripts"]
        self.assertEqual(
            scripts["check:aislop"],
            "node scripts/aislop-check-bootstrap.mjs check",
        )
        self.assertEqual(
            scripts["test:aislop"],
            "node scripts/aislop-check-bootstrap.mjs test",
        )


class AcceptedListTest(unittest.TestCase):
    """Mirror the pinned checker's own shape rules on the checked-in list."""

    def setUp(self):
        self.accepted = json.loads(ACCEPTED.read_text(encoding="utf-8"))

    def test_engines_are_listed(self):
        self.assertIsInstance(self.accepted["engines"], list)
        self.assertTrue(self.accepted["engines"])

    def test_lint_is_not_an_engine(self):
        self.assertNotIn("lint", self.accepted["engines"])

    def test_missing_tools_allowed_carry_a_reason(self):
        for tool, reason in self.accepted.get("missingToolsAllowed", {}).items():
            self.assertTrue(reason.strip(), f"{tool} lacks a reason")

    def test_every_group_has_a_reason_and_findings(self):
        self.assertTrue(self.accepted["accepted"])
        for group in self.accepted["accepted"]:
            self.assertTrue(group["reason"].strip())
            self.assertTrue(group["findings"])

    def test_every_finding_has_the_expected_keys_and_listed_engine(self):
        for group in self.accepted["accepted"]:
            for finding in group["findings"]:
                self.assertEqual(
                    sorted(finding),
                    ["engine", "file", "message", "rule"],
                )
                self.assertIn(finding["engine"], self.accepted["engines"])


class WorkflowWiringTest(unittest.TestCase):
    def step_block(self, text, name):
        start = text.index(f"  - name: {name}")
        rest = text[start + 1:]
        end = rest.find("\n  - name: ")
        return text[start:start + 1 + end] if end != -1 else text[start:]

    def rule_pairs(self, block):
        return re.findall(r"-\s*condition:\s*(.+?)\s*\n\s*next:\s*(\S+)", block)

    def test_each_workflow_runs_npm_ci_then_the_bootstrap(self):
        for path in WORKFLOWS:
            with self.subTest(workflow=path.name):
                block = self.step_block(path.read_text(encoding="utf-8"), "verify_aislop")
                self.assertIn("npm ci", block)
                self.assertIn("node scripts/aislop-check-bootstrap.mjs check", block)
                self.assertLess(
                    block.index("npm ci"),
                    block.index("node scripts/aislop-check-bootstrap.mjs check"),
                )
                self.assertIn("timeout_ms: 900000", block)
                self.assertIn("output: json", block)

    def test_workflows_no_longer_call_the_local_gate(self):
        for path in WORKFLOWS:
            with self.subTest(workflow=path.name):
                block = self.step_block(path.read_text(encoding="utf-8"), "verify_aislop")
                self.assertNotIn("aislop-gate.py", block)

    def test_workflow_rules_map_each_condition_to_its_next_step(self):
        for path in WORKFLOWS:
            with self.subTest(workflow=path.name):
                text = path.read_text(encoding="utf-8")
                block = self.step_block(text, "verify_aislop")
                self.assertIn('"all_pass": false', block)
                names = re.findall(r"^  - name: (\S+)$", text, re.MULTILINE)
                predecessor = names[names.index("verify_aislop") - 1]
                self.assertIn(
                    (
                        f"when(context.{predecessor}.result.json.all_pass == true)",
                        "verify_aislop",
                    ),
                    self.rule_pairs(self.step_block(text, predecessor)),
                )
                aislop_pairs = self.rule_pairs(block)
                self.assertIn(
                    ("when(context.verify_aislop.result.json.all_pass == true)", "COMPLETE"),
                    aislop_pairs,
                )
                self.assertIn(("when(true)", "develop"), aislop_pairs)


class OldGateRemovalTest(unittest.TestCase):
    def test_local_gate_files_are_gone(self):
        for rel in [
            "tools/aislop-gate.py",
            "tools/tests/test_aislop_gate.py",
            "tools/tests/fixtures/aislop",
            ".aislop/ci-baseline.json",
            ".aislop/config.yml",
            ".aislop",
        ]:
            with self.subTest(path=rel):
                self.assertFalse((ROOT / rel).exists(), f"{rel} still exists")

    def test_no_config_or_docs_reference_the_local_gate(self):
        pattern = re.compile(r"aislop-gate\.py|ci-baseline\.json")
        docs = [
            ROOT / "tools" / "README.md",
            *WORKFLOWS,
            ROOT / ".takt" / "workflows" / "rules" / "ppd-coding-rules.md",
            ROOT / ".takt" / "facets" / "knowledge" / "ppd-rules.md",
        ]
        for path in docs:
            text = path.read_text(encoding="utf-8")
            with self.subTest(path=path.relative_to(ROOT)):
                self.assertIsNone(pattern.search(text))


if __name__ == "__main__":
    unittest.main()
