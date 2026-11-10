import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest


RUNNER = Path(__file__).resolve().parents[1] / "verify-sbt-runtime.py"
FAKE = r'''
import json
import os
from pathlib import Path
import re
import sys
import xml.etree.ElementTree as ET

config = json.loads(Path("fixture.json").read_text())
Path("args.json").write_text(json.dumps(sys.argv[1:]))
marker = re.search(r"PPD_JVM_[a-f0-9]+=", sys.argv[4])[0]
if config.get("launcher", True):
    print(marker + config.get("launcher_version", "11.0.33") + "\t/fake/jdk")
report = Path("module/target/test-reports/TEST-fixture.xml")
mode = config.get("report", "fresh")
if mode != "missing" and mode != "stale":
    report.parent.mkdir(parents=True, exist_ok=True)
    if mode == "broken":
        report.write_text("<broken")
    else:
        suite = ET.Element("testsuite", name="fixture")
        props = ET.SubElement(suite, "properties")
        for key, value in {"java.version": config.get("fork_version", "11.0.33"),
                           "java.home": "/fake/fork"}.items():
            if key != config.get("omit"):
                ET.SubElement(props, "property", name=key, value=value)
        if mode != "empty":
            ET.SubElement(suite, "testcase", name="runs")
        report.write_bytes(ET.tostring(suite))
        if mode == "old":
            os.utime(report, (1, 1))
if config.get("extra_report"):
    other = report.with_name("TEST-other.xml")
    other.write_text('<testsuite><properties><property name="java.version" value="26"/>'
                     '<property name="java.home" value="/other"/></properties>'
                     '<testcase name="other"/></testsuite>')
if config.get("signal"):
    import signal
    os.kill(os.getpid(), signal.SIGTERM)
sys.exit(config.get("exit", 0))
'''


class RuntimeRunnerTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.repo = Path(self.temp.name).resolve()
        subprocess.run(["git", "init", "-q", str(self.repo)], check=True)
        subprocess.run(["git", "-C", str(self.repo), "-c", "user.name=Fixture",
                        "-c", "user.email=fixture@example.invalid", "commit", "-qm", "fixture",
                        "--allow-empty"], check=True)
        self.sbt = self.repo / "fake sbt"
        self.sbt.write_text("#!" + sys.executable + "\n" + FAKE)
        self.sbt.chmod(0o755)
        self.jdk = self.repo / "jdk 11"
        (self.jdk / "bin").mkdir(parents=True)
        (self.jdk / "bin/java").touch()
        self.command = "schema-check/testOnly *FactoryClassReferenceSpec"

    def run_fixture(self, config, command=None, sbt=None, resolve=False, env=None):
        (self.repo / "fixture.json").write_text(json.dumps(config))
        argv = [sys.executable, str(RUNNER)]
        if not resolve:
            argv += ["--sbt", str(sbt or self.sbt)]
        argv += ["--java-home", str(self.jdk), "--expected-major", "11", command or self.command]
        process = subprocess.run(argv, cwd=self.repo, capture_output=True, text=True, env=env)
        results = list(self.repo.glob("target/verify-sbt-runtime/*/result.json"))
        result = json.loads(results[0].read_text()) if results else None
        return process, result

    def test_both_match_and_exact_args_and_evidence(self):
        process, result = self.run_fixture({})
        self.assertEqual(process.returncode, 0, process.stdout + process.stderr)
        self.assertEqual(result["status"], "success")
        self.assertEqual(result["runtime_status"], "match")
        args = json.loads((self.repo / "args.json").read_text())
        self.assertEqual(args[:3], ["--java-home", str(self.jdk), "-batch"])
        self.assertEqual(args[4:], [self.command])
        self.assertIn('System.getProperty("java.version")', args[3])
        self.assertEqual(result["command"], [str(self.sbt), *args])
        self.assertEqual(result["command_exit"], 0)
        self.assertEqual(result["repo"], str(self.repo))
        self.assertEqual(result["head"], subprocess.check_output(
            ["git", "rev-parse", "HEAD"], cwd=self.repo, text=True).strip())
        self.assertEqual(result["launcher"][0]["java.version"], "11.0.33")
        self.assertEqual(result["forks"][0]["jvms"][0]["java.version"], "11.0.33")
        for item in [result["log"], result["forks"][0]["evidence"]]:
            self.assertEqual(item["sha256"], hashlib.sha256(Path(item["path"]).read_bytes()).hexdigest())
            self.assertGreaterEqual(item["mtime_ns"], result["started_ns"])
            self.assertLessEqual(item["mtime_ns"], result["finished_ns"])

    def test_launcher_match_fork_mismatch(self):
        process, result = self.run_fixture({"fork_version": "26.0.2.1"})
        self.assertEqual(process.returncode, 3)
        self.assertEqual(result["status"], "mismatch")

    def test_shim_ignores_requested_jdk(self):
        process, result = self.run_fixture({"launcher_version": "26.0.2.1", "fork_version": "26.0.2.1"})
        self.assertEqual(process.returncode, 3)
        self.assertEqual(result["launcher"][0]["java.version"], "26.0.2.1")
        self.assertEqual(json.loads((self.repo / "args.json").read_text())[:2],
                         ["--java-home", str(self.jdk)])

    def test_missing_unparseable_and_empty_evidence(self):
        for config in ({"launcher": False}, {"launcher_version": "garbage"},
                       {"report": "missing"}, {"report": "broken"}, {"report": "empty"},
                       {"omit": "java.version"}, {"omit": "java.home"},
                       {"fork_version": "garbage"}, {"report": "old"}):
            with self.subTest(config=config):
                process, result = self.run_fixture(config)
                self.assertEqual(process.returncode, 4, process.stdout + process.stderr)
                self.assertEqual(result["status"], "unknown")
                for path in self.repo.glob("target/verify-sbt-runtime/*/result.json"):
                    path.unlink()

    def test_successful_fake_launcher_with_stale_junit(self):
        report = self.repo / "module/target/test-reports/TEST-fixture.xml"
        report.parent.mkdir(parents=True)
        report.write_text('<testsuite><properties><property name="java.version" value="11.0.33"/>'
                          '<property name="java.home" value="/fake"/></properties>'
                          '<testcase name="old"/></testsuite>')
        process, result = self.run_fixture({"report": "stale"})
        self.assertEqual(process.returncode, 4)
        self.assertEqual(result["ignored_stale_reports"], [str(report)])
        self.assertEqual(result["forks"], [])

    def test_real_command_failure_preserved_even_with_matching_jvms(self):
        process, result = self.run_fixture({"exit": 17})
        self.assertEqual(process.returncode, 17)
        self.assertEqual(result["command_exit"], 17)
        self.assertEqual(result["runtime_status"], "match")
        self.assertEqual(result["status"], "failure")

    def test_command_failure_preserved_with_runtime_mismatch(self):
        process, result = self.run_fixture({"exit": 9, "fork_version": "26"})
        self.assertEqual(process.returncode, 9)
        self.assertEqual(result["runtime_status"], "mismatch")

    def test_command_signal_preserved(self):
        process, result = self.run_fixture({"signal": True})
        self.assertEqual(process.returncode, 143)
        self.assertEqual(result["command_exit"], -15)
        self.assertEqual(result["status"], "failure")

    def test_reject_non_test_commands_before_execution(self):
        for command in ("publish", "release", "runMain Foo", "test;publish", "test\npublish",
                        "set javaHome := None", "schema-check/testOnly *Foo -- -z publish"):
            with self.subTest(command=command):
                process, result = self.run_fixture({}, command=command)
                self.assertEqual(process.returncode, 2)
                self.assertIsNone(result)
                self.assertFalse((self.repo / "args.json").exists())

    def test_every_fresh_report_is_checked(self):
        process, result = self.run_fixture({"extra_report": True})
        self.assertEqual(process.returncode, 3)
        self.assertEqual(len(result["forks"]), 2)

    def test_optional_mise_resolution_uses_real_path(self):
        mise = self.repo / "mise"
        mise.write_text("#!" + sys.executable + "\nimport sys\n"
                        "assert sys.argv[1:] == ['which', 'sbt']\nprint(" + repr(str(self.sbt)) + ")\n")
        mise.chmod(0o755)
        env = {**os.environ, "PATH": str(self.repo) + os.pathsep + os.environ["PATH"]}
        process, result = self.run_fixture({}, resolve=True, env=env)
        self.assertEqual(process.returncode, 0, process.stdout + process.stderr)
        self.assertEqual(result["command"][0], str(self.sbt))

    def test_known_shim_path_rejected(self):
        shim = self.repo / "shims/sbt"
        shim.parent.mkdir()
        shim.write_text(self.sbt.read_text())
        shim.chmod(0o755)
        process, result = self.run_fixture({}, sbt=shim)
        self.assertEqual(process.returncode, 4)
        self.assertEqual(result["status"], "unknown")
        self.assertFalse((self.repo / "args.json").exists())

    def test_missing_executable_records_unknown(self):
        process, result = self.run_fixture({}, sbt=self.repo / "missing")
        self.assertEqual(process.returncode, 4)
        self.assertEqual(result["status"], "unknown")
        self.assertIsNone(result["command_exit"])


if __name__ == "__main__":
    unittest.main()
