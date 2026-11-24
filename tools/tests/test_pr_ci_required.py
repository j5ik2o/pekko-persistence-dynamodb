"""Tests for the "Verify required results" step of the PR CI Required job in ci.yml."""
import json
import os
import shutil
from pathlib import Path
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

CI = Path(__file__).resolve().parents[2] / ".github" / "workflows" / "ci.yml"
SCALA_VERSIONS = ("2.12.20", "2.13.16", "3.6.4")
MATRIX = [f"test ({jdk}, {scala})" for jdk in (11, 17, 19) for scala in SCALA_VERSIONS]
MIMA = [f"mima ({scala})" for scala in SCALA_VERSIONS]


def job(name, step, status="completed", conclusion="success", step_conclusion="success"):
    return {"name": name, "run_attempt": 1, "status": status, "conclusion": conclusion,
            "steps": [{"name": step, "conclusion": step_conclusion}]}


def passed_jobs():
    return [job("changes", "Select PR checks"), job("lint", "sbt lint")] + \
        [job(name, "sbt mimaReportBinaryIssues") for name in MIMA] + \
        [job(name, "sbt test") for name in MATRIX]


@unittest.skipUnless(HAVE_PYYAML, PYYAML_REASON)
class PrCiRequiredTests(unittest.TestCase):
    def setUp(self):
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        self.root = Path(temp.name)
        steps = yaml.safe_load(CI.read_text())["jobs"]["required"]["steps"]
        self.step = next(step["run"] for step in steps if step["name"] == "Verify required results")
        bin_dir = self.root / "bin"
        bin_dir.mkdir()
        self.calls = self.root / "calls"
        # The fake jobs API answers read N from reads/N.json, or from the last file.
        # reads/N.fail makes read N exit 1 with that file's text on stderr, after
        # printing reads/N.json when it exists.
        (bin_dir / "gh").write_text(
            '#!/bin/sh\necho "$*" >> "$CALLS"\nn=$(wc -l < "$CALLS" | tr -d " ")\n'
            'if [ -f "$READS/$n.fail" ]; then\n'
            '  [ -f "$READS/$n.json" ] && cat "$READS/$n.json"; cat "$READS/$n.fail" >&2; exit 1\n'
            'fi\n'
            'f="$READS/$n.json"; [ -f "$f" ] || f=$(ls "$READS"/*.json | sort -n | tail -n 1)\n'
            'cat "$f"\n')
        (bin_dir / "sleep").write_text("#!/bin/sh\n")
        # Like jq 1.6, this jq exits 0 on empty input even with -e.
        real_jq = shutil.which("jq")
        (bin_dir / "jq-1.6").write_text(
            f'#!/bin/bash\ninput=$(cat)\n[ -z "$input" ] && exit 0\n'
            # The original PATH keeps a version manager's jq shim from finding this file.
            f'printf %s "$input" | PATH="{os.environ["PATH"]}" "{real_jq}" "$@"\n')
        for tool in ("gh", "sleep", "jq-1.6"):
            (bin_dir / tool).chmod(0o755)
        self.reads = self.root / "reads"
        self.reads.mkdir()
        self.env = dict(os.environ, PATH=f"{bin_dir}{os.pathsep}{os.environ['PATH']}",
                        CALLS=str(self.calls), READS=str(self.reads), GITHUB_REPOSITORY="owner/repo",
                        GITHUB_RUN_ID="1", GITHUB_RUN_ATTEMPT="1", CHANGES_RESULT="success",
                        RUN_SCALA="true", LINT_RESULT="success", MIMA_RESULT="success",
                        MATRIX_RESULT="success",
                        RUN_TOOLS="false", TOOLS_RESULT="skipped")

    def answer(self, *reads):
        for n, jobs in enumerate(reads, start=1):
            (self.reads / f"{n}.json").write_text(json.dumps([{"jobs": jobs}]))

    def answer_bad_reads_then_pass(self, write):
        """Make reads 1 and 2 bad with write(path), and read 3 report every job as passed."""
        self.answer(passed_jobs(), passed_jobs(), passed_jobs())
        for n in (1, 2):
            write(self.reads / f"{n}.json")

    def assert_passes_on_read_3(self):
        result, reads = self.run_step()
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertEqual(reads, 3)
        self.assertIn("Every required job and step succeeded (read 3)", result.stdout)

    def run_step(self, **env):
        result = subprocess.run(["bash", "-e", "-o", "pipefail", "-c", self.step],
                                env=dict(self.env, **env), capture_output=True, text=True)
        calls = self.calls.read_text().splitlines() if self.calls.exists() else []
        return result, len(calls)

    def test_passes_when_every_job_is_reported_as_succeeded(self):
        self.answer(passed_jobs())
        result, reads = self.run_step()
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertEqual(reads, 1)

    def test_reads_again_when_the_api_lags_a_job_that_just_finished(self):
        lagging = passed_jobs()
        lagging[-1] = job(MATRIX[-1], "sbt test", status="in_progress", conclusion=None,
                          step_conclusion=None)
        self.answer(lagging, lagging, passed_jobs())
        result, reads = self.run_step()
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertEqual(reads, 3)
        self.assertIn("Every required job and step succeeded (read 3)", result.stdout)

    def test_reads_again_when_the_api_returns_an_empty_response(self):
        self.answer_bad_reads_then_pass(lambda path: path.write_text(""))
        self.assert_passes_on_read_3()

    def test_reads_again_when_the_api_returns_truncated_json(self):
        self.answer_bad_reads_then_pass(lambda path: path.write_text('[{"jobs": ['))
        self.assert_passes_on_read_3()

    def test_reads_again_when_the_gh_api_call_fails(self):
        self.answer_bad_reads_then_pass(lambda path: (
            path.unlink(), path.with_suffix(".fail").write_text("API rate limit exceeded\n")))
        self.assert_passes_on_read_3()

    def test_reads_again_when_gh_fails_after_printing_a_passing_answer(self):
        # Only the exit status says that this read failed.
        self.answer_bad_reads_then_pass(
            lambda path: path.with_suffix(".fail").write_text("gh: HTTP 502\n"))
        self.assert_passes_on_read_3()

    def test_reads_again_on_an_empty_response_with_a_jq_that_accepts_empty_input(self):
        jq16 = self.root / "jq16"
        jq16.mkdir()
        (jq16 / "jq").symlink_to(self.root / "bin" / "jq-1.6")
        self.env["PATH"] = f"{jq16}{os.pathsep}{self.env['PATH']}"
        self.answer_bad_reads_then_pass(lambda path: path.write_text(""))
        self.assert_passes_on_read_3()

    def test_fails_when_a_job_stays_failed(self):
        failed = passed_jobs()
        failed[3] = job(MATRIX[1], "sbt test", conclusion="failure", step_conclusion="failure")
        self.answer(failed)
        result, reads = self.run_step()
        self.assertEqual(result.returncode, 1)
        self.assertEqual(reads, 6)
        self.assertIn("::error::A required job or step did not succeed.", result.stdout)

    def test_fails_when_a_required_step_was_skipped(self):
        skipped = passed_jobs()
        skipped[1] = job("lint", "sbt lint", step_conclusion="skipped")
        self.answer(skipped)
        result, reads = self.run_step()
        self.assertEqual(result.returncode, 1)
        self.assertEqual(reads, 6)

    def test_fails_without_reading_the_api_when_needs_did_not_pass(self):
        self.answer(passed_jobs())
        result, reads = self.run_step(MATRIX_RESULT="failure")
        self.assertEqual(result.returncode, 1)
        self.assertEqual(reads, 0)
        self.assertIn("::error::Required jobs did not complete as expected.", result.stdout)

    def test_fails_without_reading_the_api_when_mima_did_not_pass(self):
        self.answer(passed_jobs())
        for mima in ("failure", "cancelled", "skipped"):
            with self.subTest(mima=mima):
                self.calls.unlink(missing_ok=True)
                result, reads = self.run_step(MIMA_RESULT=mima)
                self.assertEqual(result.returncode, 1)
                self.assertEqual(reads, 0)
                self.assertIn("::error::Required jobs did not complete as expected.", result.stdout)

    def test_fails_when_a_mima_job_failed(self):
        failed = passed_jobs()
        index = failed.index(job(MIMA[2], "sbt mimaReportBinaryIssues"))
        failed[index] = job(MIMA[2], "sbt mimaReportBinaryIssues", conclusion="failure",
                            step_conclusion="failure")
        self.answer(failed)
        result, reads = self.run_step()
        self.assertEqual(result.returncode, 1)
        self.assertEqual(reads, 6)
        self.assertIn("::error::A required job or step did not succeed.", result.stdout)

    def test_fails_when_a_mima_step_was_skipped(self):
        skipped = passed_jobs()
        index = skipped.index(job(MIMA[0], "sbt mimaReportBinaryIssues"))
        skipped[index] = job(MIMA[0], "sbt mimaReportBinaryIssues", step_conclusion="skipped")
        self.answer(skipped)
        result, reads = self.run_step()
        self.assertEqual(result.returncode, 1)
        self.assertEqual(reads, 6)

    def test_fails_when_a_mima_job_is_missing(self):
        missing = [j for j in passed_jobs() if j["name"] != MIMA[1]]
        self.answer(missing)
        result, reads = self.run_step()
        self.assertEqual(result.returncode, 1)
        self.assertEqual(reads, 6)

    def test_passes_without_lint_and_mima_when_scala_is_out_of_scope(self):
        self.answer([job("changes", "Select PR checks")] +
                    [job(name, "Validate CI selection") for name in MATRIX])
        result, reads = self.run_step(RUN_SCALA="false", LINT_RESULT="skipped", MIMA_RESULT="skipped")
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertEqual(reads, 1)

    def test_fails_when_scala_is_out_of_scope_but_mima_ran(self):
        self.answer(passed_jobs())
        result, reads = self.run_step(RUN_SCALA="false", LINT_RESULT="skipped", MIMA_RESULT="success")
        self.assertEqual(result.returncode, 1)
        self.assertEqual(reads, 0)


if __name__ == "__main__":
    unittest.main()
