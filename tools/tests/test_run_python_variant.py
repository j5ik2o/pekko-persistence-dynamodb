"""Real CLI acceptance tests, with all variants and runner mutations in temp trees."""
import hashlib
import json
import os
from pathlib import Path
import struct
import subprocess
import sys
import tempfile
import unittest

RUNNER = Path(os.environ.get("VARIANT_RUNNER", Path(__file__).resolve().parents[1] / "run-python-variant.py"))
BASE = b'VALUE = "baseline"\n'
MUTANT = b'VALUE = "mutated!"\n'
TEST = '''import subject
print(subject.VALUE, flush=True)
assert subject.VALUE == "baseline", "wrong-value: " + subject.VALUE
'''


def sha(raw):
    return hashlib.sha256(raw).hexdigest()


class VariantTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="python-variant-test-")
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name).resolve()
        self.input = self.root / "prepared"
        self.input.mkdir()
        (self.input / "subject.py").write_bytes(BASE)
        (self.input / "test_subject.py").write_text(TEST)
        self.count = 0

    def run_cli(self, extra=(), runner=RUNNER, expected=0):
        self.count += 1
        out = self.root / ("run-" + str(self.count))
        argv = [sys.executable, "-I", "-X", "pycache_prefix=" + str(self.root / ("parent-cache-" + str(self.count))),
                str(runner), "--input-dir", str(self.input), "--source", "subject.py",
                "--test", "test_subject.py", "--out", str(out), "--label", "fixture"] + list(extra)
        child = subprocess.run(argv, capture_output=True)
        self.assertEqual(child.returncode, expected, (child.stdout, child.stderr))
        result = json.loads((out / "result.json").read_bytes()) if (out / "result.json").is_file() else None
        return out, result, child

    def test_variants_and_stale_control(self):
        shared = self.root / "shared"
        shared.mkdir()
        source = shared / "subject.py"
        probe = shared / "test.py"
        probe.write_text(TEST + '\n')
        env = dict(os.environ, PYTHONPYCACHEPREFIX=str(self.root / "shared-cache"))
        def control(raw):
            source.write_bytes(raw)
            os.utime(source, ns=(1700000000000000000,) * 2)
            return subprocess.run([sys.executable, str(probe)], cwd=shared, env=env, capture_output=True)
        self.assertEqual(control(MUTANT).returncode, 1)
        cached = next((self.root / "shared-cache").rglob("subject.*.pyc"))
        _, flags, mtime, size = struct.unpack("<4sIII", cached.read_bytes()[:16])
        self.assertEqual((flags, mtime, size), (0, 1700000000, len(BASE)))
        stale = control(BASE)
        self.assertEqual(stale.returncode, 1)
        self.assertEqual(stale.stdout, b"mutated!\n")
        self.assertIn(b"wrong-value: mutated!", stale.stderr)
        self.assertEqual(source.read_bytes(), BASE)
        records = []
        for raw, exit_code in [(BASE, 0), (MUTANT, 1), (BASE, 0)]:
            (self.input / "subject.py").write_bytes(raw)
            os.utime(self.input / "subject.py", ns=(1700000000000000000,) * 2)
            out, result, child = self.run_cli()
            self.assertEqual(result["child_exit"], exit_code)
            self.assertEqual(result["status"], "complete")
            observed = result["observed"]
            self.assertEqual(observed["cache"], str(out / "cache"))
            self.assertEqual(observed["pid"], result["child_pid"])
            self.assertEqual(observed["run_id"], result["run_id"])
            self.assertEqual(observed["isolated"], 1)
            self.assertNotIn(str(out / "input"), observed["initial_sys_path"])
            self.assertIn(str(out / "input"), observed["test_sys_path"])
            self.assertEqual(result["input_files"], result["staged_files"])
            self.assertEqual(result["post_files"], result["staged_files"])
            self.assertEqual((out / "input/subject.py").read_bytes(), raw)
            self.assertEqual((self.input / "subject.py").read_bytes(), raw)
            self.assertEqual(observed["source"]["sha256"], sha(raw))
            for name, log in result["logs"].items():
                actual = (out / name).read_bytes()
                self.assertEqual((log["bytes"], log["sha256"]), (len(actual), sha(actual)))
            self.assertEqual(json.loads(child.stdout)["sha256"], sha((out / "result.json").read_bytes()))
            self.assertEqual(result["bootstrap"]["sha256"], sha((out / "bootstrap.py").read_bytes()))
            if exit_code:
                self.assertIn(b"wrong-value: mutated!", (out / "stderr.raw").read_bytes())
            records.append(result)
        self.assertEqual(len({r["observed"]["pid"] for r in records}), 3)
        self.assertEqual(len({r["run_id"] for r in records}), 3)

    def test_empty_directory_fixture_is_preserved(self):
        (self.input / "fixtures/empty/nested").mkdir(parents=True)
        (self.input / "test_subject.py").write_text(
            'from pathlib import Path\n'
            'assert Path("fixtures/empty/nested").is_dir(), "prepared empty directory disappeared"\n')
        out, result, _ = self.run_cli()
        self.assertEqual(result["child_exit"], 0)
        self.assertTrue((out / "input/fixtures/empty/nested").is_dir())
        self.assertEqual(result["input_files"]["fixtures/empty/nested"], {"kind": "directory"})
        self.assertEqual(result["post_files"], result["input_files"])

    def test_staged_directory_changes_make_record_incomplete(self):
        for command in ['Path("new-empty").mkdir()', 'Path("fixtures/empty").rmdir()']:
            (self.input / "fixtures/empty").mkdir(parents=True, exist_ok=True)
            (self.input / "test_subject.py").write_text('from pathlib import Path\n' + command + '\n')
            with self.subTest(command=command):
                _, result, _ = self.run_cli(expected=1)
                self.assertEqual(result["child_exit"], 0)
                self.assertEqual(result["status"], "incomplete")
                self.assertIn("staged files changed after execution", result["errors"])

    def test_nested_import_layout_and_descendant_cache(self):
        nested = self.input / "tests"
        nested.mkdir()
        (nested / "sibling.py").write_text('TOKEN = "fixture"\n')
        (self.input / "fixture.txt").write_text("fixture")
        (nested / "test_nested.py").write_text(
            'import os, subprocess, sys\nfrom pathlib import Path\nimport sibling, subject\n'
            'assert subject.VALUE == "baseline"\n'
            'assert sibling.TOKEN == Path("fixture.txt").read_text()\n'
            'assert os.environ["PYTHONPYCACHEPREFIX"] == sys.pycache_prefix\n'
            'actual = subprocess.check_output([sys.executable, "-c", "import sys; print(sys.pycache_prefix)"])\n'
            'assert actual.decode().strip() == sys.pycache_prefix\n')
        _, result, _ = self.run_cli(("--test", "tests/test_nested.py"))
        self.assertEqual(result["child_exit"], 0)
        self.assertEqual(result["post_files"], result["input_files"])

    def test_input_rejections(self):
        for extra in [("--source", "../subject.py"), ("--source", str(self.input / "subject.py")),
                      ("--source", "./subject.py"), ("--source", "a/../subject.py"),
                      ("--test", "subject.py"), ("--out", str(self.input / "out")),
                      ("--out", str(self.input)), ("--timeout", "nan")]:
            with self.subTest(extra=extra):
                self.run_cli(extra, expected=2)
        for name in ["bad.pyc", "__pycache__", ".git", "link", "alias.py"]:
            path = self.input / name
            if name in {"__pycache__", ".git"}:
                path.mkdir()
            elif name == "link":
                path.symlink_to(self.root, target_is_directory=True)
            elif name == "alias.py":
                os.link(self.input / "subject.py", path)
            else:
                path.write_bytes(b"")
            with self.subTest(name=name):
                self.run_cli(expected=2)
            path.rmdir() if path.is_dir() and not path.is_symlink() else path.unlink()
        out, _, _ = self.run_cli()
        self.run_cli(("--out", str(out)), expected=2)

    def test_original_change_after_copy_is_independent(self):
        # Test starts only after the parent has finished copying; no timing race.
        (self.input / "test_subject.py").write_text(
            'from pathlib import Path\nPath(' + repr(str(self.input / "subject.py")) + ').write_bytes(b"changed")\n' + TEST)
        out, result, _ = self.run_cli()
        self.assertEqual(result["child_exit"], 0)
        self.assertEqual((self.input / "subject.py").read_bytes(), b"changed")
        self.assertEqual((out / "input/subject.py").read_bytes(), BASE)

    def test_staged_rewrite(self):
        for name in ["subject.py", "test_subject.py", "fixture.txt"]:
            (self.input / "fixture.txt").write_text("fixture")
            (self.input / "test_subject.py").write_text(
                'from pathlib import Path\nPath(__file__).with_name(' + repr(name) + ').write_text("changed")\n')
            _, result, _ = self.run_cli(expected=1)
            self.assertIn("staged files changed after execution", result["errors"])
            self.assertEqual(result["status"], "incomplete")

    def test_different_observed_prefix(self):
        # A real exec changes the direct child's option; observation comes from that Python.
        wrapper = self.root / "other-python"
        wrapper.write_text('#!' + sys.executable + '\nimport os, sys\nargs=sys.argv[1:]\n'
                           'args[2]="pycache_prefix=" + ' + repr(str(self.root / "other-cache")) + '\n'
                           'os.execv(sys.executable, [sys.executable] + args)\n')
        wrapper.chmod(0o755)
        _, result, _ = self.run_cli(("--python", str(wrapper)), expected=1)
        self.assertEqual(result["observed"]["cache"], str(self.root / "other-cache"))
        self.assertIn("bootstrap observation mismatch", result["errors"])

    def test_missing_observation(self):
        (self.input / "test_subject.py").write_text(
            'from pathlib import Path\n(Path(__file__).parent.parent / "observation.json").unlink()\n')
        _, result, _ = self.run_cli(expected=1)
        self.assertEqual(result["status"], "incomplete")
        self.assertTrue(any("FileNotFoundError" in e for e in result["errors"]))

    def test_launch_timeout_save_failure_and_binary_logs(self):
        _, result, _ = self.run_cli(("--python", str(self.root / "missing")), expected=1)
        self.assertIsNone(result["child_exit"])
        self.assertTrue(any("FileNotFoundError" in e for e in result["errors"]))
        (self.input / "test_subject.py").write_text('import signal\nsignal.pause()\n')
        _, result, _ = self.run_cli(("--timeout", "1"), expected=1)
        self.assertTrue(result["timeout"])
        self.assertLess(result["child_exit"], 0)
        (self.input / "test_subject.py").write_text(
            'from pathlib import Path\n(Path(__file__).parent.parent / "result.json").mkdir()\n')
        out, result, child = self.run_cli(expected=1)
        self.assertIsNone(result)
        self.assertIn(b"cannot save result", child.stderr)
        self.assertTrue((out / "observation.json").exists())
        self.assertTrue((out / "stdout.raw").exists())
        (self.input / "test_subject.py").write_text('import os\nos.write(1, b"\\xff\\x00")\nos.write(2, b"\\xfe")\n')
        out, result, _ = self.run_cli()
        self.assertEqual((out / "stdout.raw").read_bytes(), b"\xff\x00")
        self.assertEqual((out / "stderr.raw").read_bytes(), b"\xfe")
        self.assertEqual(result["logs"]["stdout.raw"]["sha256"], sha(b"\xff\x00"))

    def test_timeout_cleanup_permission_error_is_recorded(self):
        wrapper = self.root / "kill-failure.py"
        wrapper.write_text(
            'import importlib.util, os, sys\n'
            'spec=importlib.util.spec_from_file_location("runner", ' + repr(str(RUNNER)) + ')\n'
            'm=importlib.util.module_from_spec(spec)\nspec.loader.exec_module(m)\n'
            'os.killpg=lambda *args: (_ for _ in ()).throw(PermissionError("denied"))\n'
            'sys.exit(m.main())\n')
        (self.input / "test_subject.py").write_text("import time\ntime.sleep(0.2)\n")
        _, result, _ = self.run_cli(("--timeout", "0.01"), runner=wrapper, expected=1)
        self.assertTrue(any("timeout process-group cleanup failed: denied" in error
                            for error in result["errors"]))

    def test_timeout_cleanup_wait_is_bounded(self):
        import time
        wrapper = self.root / "wait-failure.py"
        wrapper.write_text(
            'import importlib.util, subprocess, sys\n'
            'spec=importlib.util.spec_from_file_location("runner", ' + repr(str(RUNNER)) + ')\n'
            'm=importlib.util.module_from_spec(spec)\nspec.loader.exec_module(m)\n'
            'original=m.subprocess.Popen\n'
            'class Child:\n'
            '    def __init__(self, *a, **k): self.inner=original(*a, **k); self.waits=0\n'
            '    def __getattr__(self, name): return getattr(self.inner, name)\n'
            '    def kill(self): raise PermissionError("denied")\n'
            '    def wait(self, timeout=None):\n'
            '        self.waits += 1\n'
            '        if self.waits == 2: raise subprocess.TimeoutExpired("child", timeout)\n'
            '        return self.inner.wait(timeout=timeout)\n'
            'm.subprocess.Popen=Child\n'
            'm.os.killpg=lambda *args: (_ for _ in ()).throw(PermissionError("denied"))\n'
            'sys.exit(m.main())\n')
        (self.input / "test_subject.py").write_text("import time\ntime.sleep(0.2)\n")
        _, result, _ = self.run_cli(("--timeout", "0.01"), runner=wrapper, expected=1)
        time.sleep(0.25)
        self.assertIsNone(result["child_exit"])
        self.assertIn("timeout child cleanup failed: denied", result["errors"])
        self.assertIn("timeout child wait expired after cleanup", result["errors"])

    def test_copy_change_is_incomplete(self):
        # Inject an input change precisely after validation, before copy; run actual CLI main.
        wrapper = self.root / "copy-change.py"
        wrapper.write_text('import importlib.util, sys\nfrom pathlib import Path\n'
            'spec=importlib.util.spec_from_file_location("runner", ' + repr(str(RUNNER)) + ')\n'
            'm=importlib.util.module_from_spec(spec)\nspec.loader.exec_module(m)\n'
            'original=m.validate\ndef validate(args):\n'
            '    prepared=original(args)\n    (prepared[0]/"subject.py").write_bytes(b"changed")\n    return prepared\n'
            'm.validate=validate\nsys.exit(m.main())\n')
        _, result, _ = self.run_cli(runner=wrapper, expected=1)
        self.assertTrue(any("input changed during copy" in e for e in result["errors"]))

    def test_runner_mutations_are_caught(self):
        # Each runner copy is separate from suite input and gets fresh process/cache/output.
        original = RUNNER.read_bytes()
        mutations = [
            ('"cache": str(cache),\n                    "source":', '"cache": observed.get("cache"),\n                    "source":',
             "test_different_observed_prefix", "0 != 1"),
            ('if result["post_files"] != before:', 'if False:', "test_staged_rewrite", "0 != 1")]
        for index, (old, new, test, message) in enumerate(mutations):
            with self.subTest(test=test):
                mutant = self.root / ("runner-mutant-" + str(index) + ".py")
                text = original.decode()
                self.assertEqual(text.count(old), 1)
                mutant.write_text(text.replace(old, new))
                env = dict(os.environ, VARIANT_RUNNER=str(mutant))
                check = subprocess.run([sys.executable, "-I", "-X", "pycache_prefix=" + str(self.root / ("mutation-cache-" + str(index))),
                                        str(Path(__file__).resolve()), "VariantTests." + test], env=env, capture_output=True)
                self.assertEqual(check.returncode, 1, check.stderr)
                self.assertIn(message.encode(), check.stderr)
                self.assertIn(b"FAILED (", check.stderr)
        self.assertEqual(RUNNER.read_bytes(), original)


if __name__ == "__main__":
    unittest.main()
