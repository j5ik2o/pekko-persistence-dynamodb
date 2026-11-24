"""Tests for the "sbt mimaReportBinaryIssues" step and the dependency cache of the mima job in ci.yml."""
import os
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
STEP = "sbt mimaReportBinaryIssues"

# The lines that sbt printed in run 45464545453, job mima (3.6.4).
TOO_MANY_REQUESTS = (
    "[error] sbt.librarymanagement.ResolveException: Error downloading "
    "io.github.j5ik2o:pekko-persistence-dynamodb-state-v1_3:1.3.0\n"
    "[error]   download error: Caught java.io.IOException (Server returned HTTP response code: 429 "
    "for URL: https://repo1.maven.org/maven2/io/github/j5ik2o/pekko-persistence-dynamodb-state-v1_3/1.3.0/"
    "pekko-persistence-dynamodb-state-v1_3-1.3.0.pom)\n"
)
NOT_FOUND = (
    "[error] sbt.librarymanagement.ResolveException: Error downloading "
    "io.github.j5ik2o:pekko-persistence-dynamodb-base_3:9.9.9\n"
    "[error]   not found: https://repo1.maven.org/maven2/io/github/j5ik2o/pekko-persistence-dynamodb-base_3/9.9.9/"
    "pekko-persistence-dynamodb-base_3-9.9.9.pom\n"
)
INCOMPATIBLE = (
    "[error] base: Failed binary compatibility check against "
    "io.github.j5ik2o:pekko-persistence-dynamodb-base_3:1.3.0! Found 1 potential problems\n"
)
PASSED = "[success] Total time: 51 s\n"


@unittest.skipUnless(HAVE_PYYAML, PYYAML_REASON)
class MimaRetryTests(unittest.TestCase):
    def setUp(self):
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        self.root = Path(temp.name)
        self.job = yaml.safe_load(CI.read_text())["jobs"]["mima"]
        self.step = next(step for step in self.job["steps"] if step.get("name") == STEP)
        bin_dir = self.root / "bin"
        bin_dir.mkdir()
        self.calls = self.root / "calls"
        self.sleeps = self.root / "sleeps"
        # Run N of the fake sbt prints runs/N.out and exits with runs/N.code,
        # or uses the last pair when there is no file for N.
        (bin_dir / "sbt").write_text(
            '#!/bin/sh\necho "$*" >> "$CALLS"\nn=$(wc -l < "$CALLS" | tr -d " ")\n'
            '[ -f "$RUNS/$n.code" ] || n=$(ls "$RUNS"/*.code | sed "s|.*/||; s|\\.code$||" | sort -n | tail -n 1)\n'
            'cat "$RUNS/$n.out"; exit "$(cat "$RUNS/$n.code")"\n')
        (bin_dir / "sleep").write_text('#!/bin/sh\necho "$1" >> "$SLEEPS"\n')
        for tool in ("sbt", "sleep"):
            (bin_dir / tool).chmod(0o755)
        self.runs = self.root / "runs"
        self.runs.mkdir()
        runner_temp = self.root / "runner-temp"
        runner_temp.mkdir()
        env = {key: str(value) for key, value in self.step["env"].items()}
        env["SCALA_VERSION"] = "3.6.4"
        self.env = dict(os.environ, **env, PATH=f"{bin_dir}{os.pathsep}{os.environ['PATH']}",
                        CALLS=str(self.calls), SLEEPS=str(self.sleeps), RUNS=str(self.runs),
                        RUNNER_TEMP=str(runner_temp))

    def answer(self, *runs):
        for n, (out, code) in enumerate(runs, start=1):
            (self.runs / f"{n}.out").write_text(out)
            (self.runs / f"{n}.code").write_text(str(code))

    def run_step(self):
        # GitHub Actions runs `shell: bash` as `bash --noprofile --norc -eo pipefail {0}`.
        script = self.root / "step.sh"
        script.write_text(self.step["run"])
        result = subprocess.run(["bash", "--noprofile", "--norc", "-eo", "pipefail", str(script)],
                                env=self.env, capture_output=True, text=True)
        calls = self.calls.read_text().splitlines() if self.calls.exists() else []
        sleeps = self.sleeps.read_text().split() if self.sleeps.exists() else []
        return result, calls, sleeps

    def test_runs_mima_once_for_the_scala_version_of_the_job(self):
        self.answer((PASSED, 0))
        result, calls, sleeps = self.run_step()
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertEqual(calls, ["-v ++3.6.4 mimaReportBinaryIssues"])
        self.assertEqual(sleeps, [])

    def test_retries_after_a_transient_download_error_and_passes(self):
        self.answer((TOO_MANY_REQUESTS, 1), (PASSED, 0))
        result, calls, sleeps = self.run_step()
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertEqual(len(calls), 2)
        self.assertEqual(sleeps, ["30"])
        self.assertIn("::warning::Attempt 1 failed on a transient download error", result.stdout)

    def test_retries_after_a_server_error_or_timeout(self):
        for error in ("Server returned HTTP response code: 503 for URL: https://repo1.maven.org/x.pom",
                      "Read timed out", "Connection reset"):
            with self.subTest(error=error):
                self.calls.unlink(missing_ok=True)
                self.answer((f"[error]   download error: Caught java.io.IOException ({error})\n", 1),
                            (PASSED, 0))
                result, calls, _ = self.run_step()
                self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
                self.assertEqual(len(calls), 2)

    def test_fails_without_retry_on_a_binary_compatibility_problem(self):
        self.answer((INCOMPATIBLE, 1), (PASSED, 0))
        result, calls, sleeps = self.run_step()
        self.assertEqual(result.returncode, 1)
        self.assertEqual(len(calls), 1)
        self.assertEqual(sleeps, [])
        self.assertIn("::error::MiMa found binary compatibility problems; not retrying.", result.stdout)

    def test_fails_without_retry_when_a_problem_comes_with_a_download_error(self):
        # A retry could pass only if the problem went away, which it does not;
        # failing at once keeps the problem in the first log.
        self.answer((TOO_MANY_REQUESTS + INCOMPATIBLE, 1), (PASSED, 0))
        result, calls, _ = self.run_step()
        self.assertEqual(result.returncode, 1)
        self.assertEqual(len(calls), 1)

    def test_fails_without_retry_when_the_baseline_does_not_exist(self):
        self.answer((NOT_FOUND, 1), (PASSED, 0))
        result, calls, sleeps = self.run_step()
        self.assertEqual(result.returncode, 1)
        self.assertEqual(len(calls), 1)
        self.assertEqual(sleeps, [])
        self.assertIn("failed without a transient download error; not retrying.", result.stdout)

    def test_fails_when_the_download_error_persists(self):
        self.answer((TOO_MANY_REQUESTS, 1))
        result, calls, sleeps = self.run_step()
        self.assertEqual(result.returncode, 1)
        self.assertEqual(len(calls), 3)
        self.assertEqual(sleeps, ["30", "60"])
        self.assertIn("::error::Downloads still failed after 3 attempts.", result.stdout)

    def test_keeps_a_dependency_cache_for_each_scala_version(self):
        caches = [step for step in self.job["steps"] if str(step.get("uses", "")).startswith("actions/cache@")]
        self.assertEqual(len(caches), 1)
        cache = caches[0]["with"]
        self.assertIn("${{ matrix.scala }}", cache["key"])
        self.assertIn("${{ matrix.scala }}", cache["restore-keys"])
        self.assertIn("~/.cache/coursier", cache["path"].split())
        # A second cache over the same paths would restore the shared one instead.
        setup_java = next(step for step in self.job["steps"] if str(step.get("uses", "")).startswith("actions/setup-java@"))
        self.assertNotIn("cache", setup_java["with"])
        steps = [step.get("uses", step.get("name")) for step in self.job["steps"]]
        self.assertLess(steps.index(caches[0]["uses"]), steps.index(STEP))


if __name__ == "__main__":
    unittest.main()
