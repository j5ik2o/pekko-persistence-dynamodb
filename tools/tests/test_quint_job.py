"""Tests for the steps of ci.yml that select and run the quint job: the quint outputs
of "Select PR checks", "Point out code that the specs describe", and
"Fetch the Rust evaluator". Design: docs/design/quint-in-ci.md."""
import hashlib
import io
import os
from pathlib import Path
import subprocess
import tarfile
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

CI = Path(__file__).resolve().parents[2] / ".github" / "workflows" / "ci.yml"
MIGRATION = "base/base-v2/src/main/scala/com/github/j5ik2o/pekko/persistence/dynamodb/migration/KeyMigration.scala"
AGGREGATE = ("journal/journal-base/src/main/scala/com/github/j5ik2o/pekko/persistence/dynamodb/journal/"
             "aggregate/JournalAggregateStore.scala")
KEY_PARTS = "base/base/src/main/scala/com/github/j5ik2o/pekko/persistence/dynamodb/model/PersistenceIdKeyParts.scala"
SNAPSHOT_SORT_KEY = ("snapshot/snapshot-base/src/main/scala/com/github/j5ik2o/pekko/persistence/dynamodb/snapshot/"
                     "SortKeyResolver.scala")


def step(job, name):
    steps = yaml.safe_load(CI.read_text())["jobs"][job]["steps"]
    return next(s for s in steps if s.get("name") == name)


def outputs(text):
    """Parse a GITHUB_OUTPUT file, including name<<DELIMITER values."""
    values, lines = {}, iter(text.splitlines())
    for line in lines:
        if "<<" in line and "=" not in line.split("<<", 1)[0]:
            name, delimiter = line.split("<<", 1)
            body = []
            for body_line in lines:
                if body_line == delimiter:
                    break
                body.append(body_line)
            values[name] = "\n".join(body)
        elif "=" in line:
            name, value = line.split("=", 1)
            values[name] = value
    return values


class Workdir:
    def __init__(self, test):
        temp = tempfile.TemporaryDirectory()
        test.addCleanup(temp.cleanup)
        self.root = Path(temp.name)
        self.bin = self.root / "bin"
        self.bin.mkdir()

    def tool(self, name, text):
        path = self.bin / name
        path.write_text(text)
        path.chmod(0o755)

    def env(self, **values):
        return dict(os.environ, PATH=f"{self.bin}{os.pathsep}{os.environ['PATH']}", RUNNER_TEMP=str(self.root),
                    **values)


@unittest.skipUnless(HAVE_PYYAML, PYYAML_REASON)
class SelectQuintTests(unittest.TestCase):
    def select(self, paths, event="pull_request"):
        work = Workdir(self)
        (work.root / "paths").write_bytes(b"".join(p.encode() + b"\0" for p in paths))
        # The fake git prints the changed paths as git diff --name-only -z does.
        work.tool("git", f'#!/bin/sh\ncat "{work.root / "paths"}"\n')
        output = work.root / "output"
        result = subprocess.run(["bash", "-e", "-c", step("changes", "Select PR checks")["run"]],
                                env=work.env(GITHUB_EVENT_NAME=event, GITHUB_OUTPUT=str(output),
                                             BASE_SHA="b" * 40, HEAD_SHA="h" * 40),
                                capture_output=True, text=True)
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        return outputs(output.read_text())

    def test_selects_quint_alone_for_a_change_of_specs(self):
        out = self.select(["specs/key-migration/keyMigration.qnt"])
        self.assertEqual((out["scala"], out["tools"], out["quint"]), ("false", "false", "true"))
        self.assertNotIn("quint_code_files", out)

    def test_selects_quint_for_the_package_files_and_the_workflow(self):
        for path in ("package.json", "package-lock.json", ".github/workflows/ci.yml"):
            with self.subTest(path=path):
                self.assertEqual(self.select([path])["quint"], "true")

    def test_selects_quint_and_the_tools_tests_for_the_check_script(self):
        out = self.select(["tools/quint-check-specs.sh"])
        self.assertEqual((out["scala"], out["tools"], out["quint"]), ("true", "true", "true"))

    def test_lists_the_code_that_the_specs_describe_when_specs_did_not_change(self):
        out = self.select([MIGRATION, "README.md", AGGREGATE, KEY_PARTS, SNAPSHOT_SORT_KEY])
        self.assertEqual((out["scala"], out["quint"]), ("true", "true"))
        self.assertEqual(out["quint_code_files"].splitlines(), [MIGRATION, AGGREGATE, KEY_PARTS, SNAPSHOT_SORT_KEY])

    def test_lists_no_code_when_specs_changed_too(self):
        out = self.select([MIGRATION, "specs/key-migration/ci-runs.txt"])
        self.assertEqual(out["quint"], "true")
        self.assertNotIn("quint_code_files", out)

    def test_does_not_select_quint_for_other_code(self):
        out = self.select(["journal/journal-base/src/main/scala/com/github/j5ik2o/pekko/persistence/dynamodb/"
                           "journal/JournalPlugin.scala", "docs/design/key-migration.md"])
        self.assertEqual((out["scala"], out["quint"]), ("true", "false"))
        self.assertNotIn("quint_code_files", out)

    def test_selects_quint_without_a_code_list_outside_a_pull_request(self):
        for event in ("push", "workflow_dispatch"):
            with self.subTest(event=event):
                out = self.select([MIGRATION], event=event)
                self.assertEqual((out["scala"], out["quint"]), ("true", "true"))
                self.assertNotIn("quint_code_files", out)


@unittest.skipUnless(HAVE_PYYAML, PYYAML_REASON)
class PointOutCodeTests(unittest.TestCase):
    def test_warns_about_each_file_and_lists_it_in_the_summary(self):
        work = Workdir(self)
        summary = work.root / "summary"
        point_out = step("quint", "Point out code that the specs describe")
        self.assertEqual(point_out["if"], "needs.changes.outputs.quint_code_files != ''")
        result = subprocess.run(["bash", "-e", "-c", point_out["run"]],
                                env=work.env(FILES=f"{MIGRATION}\n{AGGREGATE}\n", GITHUB_STEP_SUMMARY=str(summary)),
                                capture_output=True, text=True)
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        warnings = [line for line in result.stdout.splitlines() if line.startswith("::warning ")]
        self.assertEqual(len(warnings), 2, result.stdout)
        self.assertTrue(warnings[0].startswith(f"::warning file={MIGRATION}::"))
        self.assertIn("section 8.3", warnings[0])
        text = summary.read_text()
        self.assertIn(f"- `{MIGRATION}`", text)
        self.assertIn(f"- `{AGGREGATE}`", text)
        self.assertIn("docs/design/key-migration.md", text)


@unittest.skipUnless(HAVE_PYYAML, PYYAML_REASON)
class FetchEvaluatorTests(unittest.TestCase):
    def setUp(self):
        self.work = Workdir(self)
        archive = io.BytesIO()
        with tarfile.open(fileobj=archive, mode="w:gz") as tar:
            data = b"#!/bin/sh\necho evaluator\n"
            info = tarfile.TarInfo("quint_evaluator")
            info.size, info.mode = len(data), 0o755
            tar.addfile(info, io.BytesIO(data))
        self.archive = archive.getvalue()
        (self.work.root / "good.tar.gz").write_bytes(self.archive)
        self.calls = self.work.root / "gh-calls"
        # Attempt N of the fake gh follows the word on line N of the plan:
        # good writes the archive, partial writes part of it and fails, fail fails.
        # Like gh, it refuses to replace an existing file without --clobber.
        self.work.tool("gh", f'''#!/bin/bash
echo "$*" >> "{self.calls}"
n=$(wc -l < "{self.calls}" | tr -d " ")
plan=$(sed -n "${{n}}p" "{self.work.root / "plan"}")
out=""; clobber=false; prev=""
for arg in "$@"; do
  [ "$prev" = --output ] && out=$arg
  [ "$arg" = --clobber ] && clobber=true
  prev=$arg
done
if [ -e "$out" ] && ! $clobber; then echo "$out already exists" >&2; exit 1; fi
case $plan in
  good) cp "{self.work.root / "good.tar.gz"}" "$out" ;;
  partial) head -c 10 "{self.work.root / "good.tar.gz"}" > "$out"; exit 1 ;;
  *) exit 1 ;;
esac
''')
        self.work.tool("sleep", "#!/bin/sh\n")
        # sha256sum -c - as coreutils does it, for macOS where it may be missing.
        self.work.tool("sha256sum", '''#!/usr/bin/env python3
import hashlib, sys
ok = True
for line in sys.stdin:
    digest, path = line.rstrip("\\n").split("  ", 1)
    actual = hashlib.sha256(open(path, "rb").read()).hexdigest()
    print(f"{path}: {'OK' if actual == digest else 'FAILED'}")
    ok = ok and actual == digest
sys.exit(0 if ok else 1)
''')
        self.dir = self.work.root / "evaluator"

    def fetch(self, plan, digest=None):
        (self.work.root / "plan").write_text("\n".join(plan) + "\n")
        fetch = step("quint", "Fetch the Rust evaluator")
        env = self.work.env(VERSION="v0.6.0", DIR=str(self.dir), FETCH_ATTEMPTS="3", FETCH_RETRY_DELAY="30",
                            ARCHIVE_SHA256=digest or hashlib.sha256(self.archive).hexdigest())
        self.assertEqual({k: fetch["env"][k] for k in ("FETCH_ATTEMPTS", "FETCH_RETRY_DELAY")},
                         {"FETCH_ATTEMPTS": "3", "FETCH_RETRY_DELAY": "30"})
        result = subprocess.run(["bash", "-e", "-c", fetch["run"]], env=env, capture_output=True, text=True)
        calls = self.calls.read_text().splitlines() if self.calls.exists() else []
        return result, calls

    def test_fetches_and_extracts_the_evaluator(self):
        result, calls = self.fetch(["good"])
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertEqual(len(calls), 1)
        self.assertTrue((self.dir / "quint_evaluator").is_file())

    def test_tries_again_after_a_download_that_left_part_of_the_archive(self):
        result, calls = self.fetch(["partial", "good"])
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertEqual(len(calls), 2)
        self.assertIn("::warning::Attempt 1 to download the Rust evaluator failed", result.stdout)
        self.assertTrue((self.dir / "quint_evaluator").is_file())

    def test_fails_after_three_failed_downloads(self):
        result, calls = self.fetch(["fail", "partial", "fail", "good"])
        self.assertEqual(result.returncode, 1)
        self.assertEqual(len(calls), 3)
        self.assertIn("::error::Could not download the Rust evaluator v0.6.0 after 3 attempts.", result.stdout)
        self.assertFalse(self.dir.exists())

    def test_fails_without_retrying_when_the_digest_does_not_match(self):
        result, calls = self.fetch(["good", "good"], digest="0" * 64)
        self.assertEqual(result.returncode, 1)
        self.assertEqual(len(calls), 1)
        self.assertIn("FAILED", result.stdout)
        self.assertFalse(self.dir.exists())


if __name__ == "__main__":
    unittest.main()
