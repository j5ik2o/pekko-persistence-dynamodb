"""Tests for the "Fetch the dependencies" step and the dependency cache of the test job in ci.yml."""
import os
from pathlib import Path
import re
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
STEP = "Fetch the dependencies"
TEST_STEP = "sbt test"

# The lines that sbt printed in run 45549627758, job test (17, 3.6.4).
TOO_MANY_REQUESTS = (
    "[error] sbt.librarymanagement.ResolveException: Error downloading org.scala-lang:scala3-library_3:3.6.4\n"
    "[error]   download error: Caught java.io.IOException (Server returned HTTP response code: 429 "
    "for URL: https://repo1.maven.org/maven2/org/scala-lang/scala3-library_3/3.6.4/scala3-library_3-3.6.4.pom)\n"
)
# The release resolution of project/ReleasedPlugins.scala only warns (run 45808287430, job test (11, 3.6.4)).
RELEASE_WARNING = (
    "[warn]   download error: Caught java.io.IOException (Server returned HTTP response code: 429 "
    "for URL: https://repo1.maven.org/maven2/io/github/j5ik2o/pekko-persistence-dynamodb-journal-v2_3/1.0.59/"
    "pekko-persistence-dynamodb-journal-v2_3-1.0.59.pom)\n"
    "[warn] the published plugins of 1.0.59 did not resolve: Error downloading\n"
)
COMPILE_ERROR = "[error] Foo.scala:1:1: Not found: Bar\n[error] (base / Compile / compileIncremental) Compilation failed\n"
NOT_FOUND = (
    "[error] sbt.librarymanagement.ResolveException: Error downloading com.example:missing_3:9.9.9\n"
    "[error]   not found: https://repo1.maven.org/maven2/com/example/missing_3/9.9.9/missing_3-9.9.9.pom\n"
)
PASSED = "[success] Total time: 51 s\n"


@unittest.skipUnless(HAVE_PYYAML, PYYAML_REASON)
class TestFetchRetryTests(unittest.TestCase):
    def setUp(self):
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        self.root = Path(temp.name)
        self.job = yaml.safe_load(CI.read_text())["jobs"]["test"]
        self.step = next(step for step in self.job["steps"] if step.get("name") == STEP)
        bin_dir = self.root / "bin"
        bin_dir.mkdir()
        self.calls = self.root / "calls"
        self.sleeps = self.root / "sleeps"
        self.home = self.root / "home"
        self.home.mkdir()
        # Run N of the fake sbt prints runs/N.out, adds runs/N.fetch files to the coursier
        # cache, and exits with runs/N.code; it uses the last run when there is no file for N.
        (bin_dir / "sbt").write_text(
            '#!/bin/sh\necho "$*" >> "$CALLS"\nn=$(wc -l < "$CALLS" | tr -d " ")\n'
            '[ -f "$RUNS/$n.code" ] || n=$(ls "$RUNS"/*.code | sed "s|.*/||; s|\\.code$||" | sort -n | tail -n 1)\n'
            'mkdir -p "$HOME/.cache/coursier/v1"\n'
            'i=0; while [ "$i" -lt "$(cat "$RUNS/$n.fetch")" ]; do i=$((i + 1)); '
            ': > "$HOME/.cache/coursier/v1/run$n-$i.pom"; done\n'
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
                        HOME=str(self.home), CALLS=str(self.calls), SLEEPS=str(self.sleeps),
                        RUNS=str(self.runs), RUNNER_TEMP=str(runner_temp))

    def answer(self, *runs):
        for n, (out, code, *fetch) in enumerate(runs, start=1):
            (self.runs / f"{n}.out").write_text(out)
            (self.runs / f"{n}.code").write_text(str(code))
            (self.runs / f"{n}.fetch").write_text(str(fetch[0] if fetch else 0))

    def run_step(self):
        # GitHub Actions runs `shell: bash` as `bash --noprofile --norc -eo pipefail {0}`.
        script = self.root / "step.sh"
        script.write_text(self.step["run"])
        result = subprocess.run(["bash", "--noprofile", "--norc", "-eo", "pipefail", str(script)],
                                env=self.env, capture_output=True, text=True)
        calls = self.calls.read_text().splitlines() if self.calls.exists() else []
        sleeps = self.sleeps.read_text().split() if self.sleeps.exists() else []
        return result, calls, sleeps

    def test_fetches_and_compiles_once_for_the_scala_version_of_the_job(self):
        self.answer((PASSED, 0))
        result, calls, sleeps = self.run_step()
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertEqual(calls, ["-v ++3.6.4 Test/compile journal-v2/releasedPluginClasspaths"])
        self.assertEqual(sleeps, [])

    def test_reports_no_fetched_file_when_the_cache_has_everything(self):
        cache = self.home / ".cache" / "coursier" / "v1"
        cache.mkdir(parents=True)
        (cache / "cached.pom").write_text("")
        self.answer((PASSED, 0, 0))
        result, _, _ = self.run_step()
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn("Fetched 0 files into the coursier cache.", result.stdout)

    def test_reports_the_files_that_every_attempt_fetched(self):
        self.answer((TOO_MANY_REQUESTS, 1, 2), (PASSED, 0, 3))
        result, _, _ = self.run_step()
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn("Fetched 5 files into the coursier cache.", result.stdout)

    def test_retries_after_a_transient_download_error_and_passes(self):
        self.answer((TOO_MANY_REQUESTS, 1), (PASSED, 0))
        result, calls, sleeps = self.run_step()
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertEqual(len(calls), 2)
        self.assertEqual(sleeps, ["60"])
        self.assertIn("::warning::Attempt 1 logged a transient download error", result.stdout)

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

    def test_retries_a_passing_run_that_warned_of_a_release_download_error(self):
        self.answer((RELEASE_WARNING + PASSED, 0), (PASSED, 0))
        result, calls, sleeps = self.run_step()
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertEqual(len(calls), 2)
        self.assertEqual(sleeps, ["60"])

    def test_continues_with_a_warning_when_the_release_download_error_persists(self):
        # The tests then fail and name the release that did not resolve.
        self.answer((RELEASE_WARNING + PASSED, 0))
        result, calls, sleeps = self.run_step()
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertEqual(len(calls), 3)
        self.assertEqual(sleeps, ["60", "120"])
        self.assertIn("::warning::Attempt 3 still logged a transient download error", result.stdout)

    def test_fails_without_retry_on_a_compile_error(self):
        self.answer((COMPILE_ERROR, 1), (PASSED, 0))
        result, calls, sleeps = self.run_step()
        self.assertEqual(result.returncode, 1)
        self.assertEqual(len(calls), 1)
        self.assertEqual(sleeps, [])
        self.assertIn("failed without a transient download error; not retrying.", result.stdout)

    def test_fails_without_retry_when_a_dependency_does_not_exist(self):
        self.answer((NOT_FOUND, 1), (PASSED, 0))
        result, calls, sleeps = self.run_step()
        self.assertEqual(result.returncode, 1)
        self.assertEqual(len(calls), 1)
        self.assertEqual(sleeps, [])

    def test_fails_when_the_download_error_persists(self):
        self.answer((TOO_MANY_REQUESTS, 1))
        result, calls, sleeps = self.run_step()
        self.assertEqual(result.returncode, 1)
        self.assertEqual(len(calls), 3)
        self.assertEqual(sleeps, ["60", "120"])
        self.assertIn("::error::Downloads still failed after 3 attempts.", result.stdout)

    def test_waits_stay_well_within_the_job_timeout(self):
        attempts = int(self.step["env"]["FETCH_ATTEMPTS"])
        delay = int(self.step["env"]["FETCH_RETRY_DELAY"])
        waits = sum(attempt * delay for attempt in range(1, attempts))
        self.assertLessEqual(waits, 5 * 60)
        self.assertLessEqual(waits, self.job["timeout-minutes"] * 60 // 10)

    def test_never_retries_sbt_test(self):
        steps = [step.get("name", step.get("uses")) for step in self.job["steps"]]
        self.assertLess(steps.index(STEP), steps.index(TEST_STEP))
        test_step = next(step for step in self.job["steps"] if step.get("name") == TEST_STEP)
        self.assertEqual(test_step["run"].strip(), "sbt -v ++${{ matrix.scala }} test")

    def test_keeps_a_dependency_cache_for_each_scala_version(self):
        caches = [step for step in self.job["steps"] if str(step.get("uses", "")).startswith("actions/cache@")]
        self.assertEqual(len(caches), 1)
        cache = caches[0]["with"]
        self.assertIn("${{ matrix.scala }}", cache["key"])
        self.assertNotIn("matrix.jdk", cache["key"])
        restore_keys = cache["restore-keys"].splitlines()
        self.assertEqual(restore_keys, ["test-deps-${{ runner.os }}-${{ matrix.scala }}-",
                                        "mima-deps-${{ runner.os }}-${{ matrix.scala }}-"])
        # The mima cache restores here only when both caches list the same paths.
        mima = yaml.safe_load(CI.read_text())["jobs"]["mima"]
        mima_cache = next(step for step in mima["steps"] if str(step.get("uses", "")).startswith("actions/cache@"))
        self.assertEqual(cache["path"].split(), mima_cache["with"]["path"].split())
        self.assertEqual(re.sub(r"^test-deps-", "mima-deps-", cache["key"]), mima_cache["with"]["key"])
        # A second cache over the same paths would restore the shared one instead.
        setup_java = next(step for step in self.job["steps"] if str(step.get("uses", "")).startswith("actions/setup-java@"))
        self.assertNotIn("cache", setup_java["with"])
        steps = [step.get("uses", step.get("name")) for step in self.job["steps"]]
        self.assertLess(steps.index(caches[0]["uses"]), steps.index(STEP))


if __name__ == "__main__":
    unittest.main()
