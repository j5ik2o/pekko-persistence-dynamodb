"""Synthetic CLI regression tests; no session discovery or real transcript fixtures."""
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest


SCRIPT = Path(os.environ.get("FREEZE_SCRIPT", Path(__file__).resolve().parents[1] / "freeze-transcript.py"))


def line(event):
    return (json.dumps(event, ensure_ascii=False) + "\n").encode()


META = line({"type": "session_meta", "payload": {"id": "synthetic", "cwd": "/synthetic"}})
CALL = line({"type": "response_item", "payload": {"type": "function_call", "call_id": "cut",
            "name": "spawn_agent", "arguments": json.dumps({"task_name": "review"})}})
MESSAGE = line({"type": "response_item", "payload": {"type": "message", "content": "private sentinel"}})


class FreezeTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.source = self.root / "source.jsonl"
        self.source.write_bytes(META + MESSAGE + CALL + MESSAGE)
        self.out = self.root / "bundle"

    def cli(self, *args, wrapper=None):
        command = [sys.executable, str(SCRIPT)]
        if wrapper:
            command = [sys.executable, "-c", wrapper, str(SCRIPT)]
        return subprocess.run(command + list(args), capture_output=True, text=True)

    def create(self, *mode, wrapper=None, extra=()):
        return self.cli("create", "--source", str(self.source), "--expected-session-id", "synthetic",
                        "--expected-cwd", "/synthetic", "--out", str(self.out), *mode, *extra,
                        wrapper=wrapper)

    def verify(self, digest):
        return self.cli("verify", "--bundle", str(self.out), "--expected-manifest-sha256", digest)

    def ok(self, result):
        self.assertEqual(result.returncode, 0, result.stderr)
        return json.loads(result.stdout)

    def bad(self, result, code=None):
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(result.stdout, "")
        self.assertNotIn("private sentinel", result.stderr)
        if code:
            self.assertEqual(result.stderr.strip(), code)

    def test_eof_and_verify(self):
        result = self.ok(self.create("--at-eof"))
        self.assertEqual((self.out / "transcript.jsonl").read_bytes(), self.source.read_bytes())
        self.source.unlink()
        self.ok(self.verify(result["manifest_sha256"]))
        self.assertEqual(self.out.stat().st_mode & 0o777, 0o700)
        for name in ("transcript.jsonl", "snapshot.json"):
            self.assertEqual((self.out / name).stat().st_mode & 0o777, 0o600)

    def test_source_symlink_parent_keeps_filesystem_semantics(self):
        actual = self.root / "actual"
        (actual / "nested").mkdir(parents=True)
        alias = self.root / "alias"
        alias.symlink_to(actual / "nested", target_is_directory=True)
        selected = actual / "source.jsonl"
        prefix = META + line({"type": "message", "text": "selected explicit source"})
        selected.write_bytes(prefix + CALL + MESSAGE)
        explicit = alias / ".." / "source.jsonl"
        self.assertNotEqual(selected.read_bytes(), self.source.read_bytes())
        for name, mode, expected in [
            ("eof", ("--at-eof",), selected.read_bytes()),
            ("before", ("--before-call-id", "cut"), prefix),
        ]:
            with self.subTest(mode=name):
                self.out = self.root / name
                result = self.ok(self.create(*mode, extra=("--source", str(explicit),
                    "--expected-cwd", "/synthetic/../synthetic")))
                self.assertEqual((self.out / "transcript.jsonl").read_bytes(), expected)
                self.assertEqual(result["source"]["input_path"], str(explicit))
                self.assertEqual(result["source"]["resolved_path"], str(selected.resolve()))
                self.ok(self.verify(result["manifest_sha256"]))

    def test_output_symlink_parent_keeps_filesystem_semantics(self):
        actual = self.root / "actual"
        (actual / "nested").mkdir(parents=True)
        alias = self.root / "alias"
        alias.symlink_to(actual / "nested", target_is_directory=True)
        relative_root = Path(os.path.relpath(self.root))
        for name, out in [
            ("absolute", alias / ".." / "absolute"),
            ("relative", relative_root / "alias" / ".." / "relative"),
        ]:
            with self.subTest(path=name):
                result = self.ok(self.create("--at-eof", extra=("--out", str(out))))
                self.assertTrue((actual / name / "snapshot.json").is_file())
                self.assertFalse((self.root / name).exists())
                self.assertEqual(Path(result["artifact_path"]).resolve(),
                                 (actual / name / "transcript.jsonl").resolve())
                self.ok(self.cli("verify", "--bundle", str(out),
                                 "--expected-manifest-sha256", result["manifest_sha256"]))

    def test_verify_symlink_parent_keeps_filesystem_semantics(self):
        actual = self.root / "actual"
        (actual / "nested").mkdir(parents=True)
        alias = self.root / "alias"
        alias.symlink_to(actual / "nested", target_is_directory=True)
        self.out = actual / "bundle"
        created = self.ok(self.create("--at-eof"))
        relative_root = Path(os.path.relpath(self.root))
        for bundle in (alias / ".." / "bundle", relative_root / "alias" / ".." / "bundle"):
            with self.subTest(path=str(bundle)):
                result = self.ok(self.cli("verify", "--bundle", str(bundle),
                                         "--expected-manifest-sha256", created["manifest_sha256"]))
                self.assertEqual(Path(result["artifact_path"]).resolve(),
                                 (actual / "bundle" / "transcript.jsonl").resolve())

    def test_before_and_saved_append(self):
        result = self.ok(self.create("--before-call-id", "cut"))
        saved = (self.out / "transcript.jsonl").read_bytes()
        self.assertEqual(saved, META + MESSAGE)
        self.assertEqual(result["cutoff"]["line_exclusive"], 3)
        self.assertEqual(result["cutoff"]["event_sha256"], hashlib.sha256(CALL).hexdigest())
        with self.source.open("ab") as stream:
            stream.write(MESSAGE)
        self.ok(self.verify(result["manifest_sha256"]))
        self.assertEqual((self.out / "transcript.jsonl").read_bytes(), saved)

    def test_append_at_capture_boundary(self):
        original = self.source.read_bytes()
        wrapper = '''import os,runpy,sys
real = os.fstat
def capture(fd):
    info = real(fd)
    with open(sys.argv[sys.argv.index('--source')+1], 'ab') as stream:
        stream.write(b'{"type":"later"}\\n')
    return info
os.fstat = capture
sys.argv = sys.argv[1:]
runpy.run_path(sys.argv[0], run_name='__main__')
'''
        result = self.ok(self.create("--at-eof", wrapper=wrapper))
        self.assertEqual(result["source"]["captured_size_bytes"], len(original))
        self.assertEqual((self.out / "transcript.jsonl").read_bytes(), original)
        self.ok(self.verify(result["manifest_sha256"]))

    def test_cutoff_not_found(self):
        self.source.write_bytes(META + line({"type": "message", "text": CALL.decode()}))
        self.bad(self.create("--before-call-id", "cut"), "cutoff_not_found")
        self.assertFalse(self.out.exists())

    def test_cutoff_duplicate_and_unsupported(self):
        for suffix, code in [(CALL + CALL, "ambiguous_cutoff"),
                             (CALL + CALL.replace(b"spawn_agent", b"other_tool"), "ambiguous_cutoff"),
                             (CALL.replace(b'spawn_agent', b'other_tool'), "unsupported_cutoff"),
                             (CALL.replace(b'review', b'  '), "unsupported_cutoff"),
                             (CALL.replace(b'task_name', b'other_key'), "unsupported_cutoff")]:
            with self.subTest(code=code):
                self.source.write_bytes(META + suffix)
                self.bad(self.create("--before-call-id", "cut"), code)

    def test_invalid_records(self):
        for data in [META + b'{bad private sentinel}\n', META + b'{"a":1,"a":2}\n',
                     META + b'{"a":"\xff"}\n', META + MESSAGE[:-1], MESSAGE, META + META,
                     line({"type": "session_meta", "payload": {"id": "synthetic"}}),
                     META + b'[]\n', META + b'{"a": NaN}\n']:
            with self.subTest(data_length=len(data)):
                self.source.write_bytes(data)
                self.bad(self.create("--at-eof"))

    def test_session_and_modes(self):
        for extra in [("--expected-session-id", "wrong"), ("--expected-cwd", "/wrong")]:
            self.bad(self.create("--at-eof", extra=extra), "session_mismatch")
        self.bad(self.create())
        self.bad(self.create("--at-eof", "--before-call-id", "cut"))

    def test_existing_outputs_and_aliases(self):
        self.out.mkdir()
        self.bad(self.create("--at-eof"), "output_exists")
        self.out.rmdir()
        self.out.write_bytes(b"existing")
        self.bad(self.create("--at-eof"), "output_exists")
        self.out.unlink()
        for target in (self.source, self.root / "missing"):
            self.out.symlink_to(target)
            self.bad(self.create("--at-eof"), "output_exists")
            self.out.unlink()
        os.link(self.source, self.out)
        self.bad(self.create("--at-eof"), "output_exists")
        self.bad(self.create("--at-eof", extra=("--out", str(self.source))), "output_exists")

    def test_bundle_tampering(self):
        result = self.ok(self.create("--at-eof"))
        digest = result["manifest_sha256"]
        for name in ("transcript.jsonl", "snapshot.json"):
            path = self.out / name
            original = path.read_bytes()
            path.unlink()
            self.bad(self.verify(digest))
            path.symlink_to(self.source)
            self.bad(self.verify(digest))
            path.unlink()
            path.write_bytes(original + b" ")
            self.bad(self.verify(digest))
            path.write_bytes(original)
        manifest = self.out / "snapshot.json"
        original = json.loads(manifest.read_bytes())
        for key, value in [("schema", 2), ("cutoff", {}), ("session", {}), ("source", {})]:
            changed = dict(original, **{key: value})
            data = json.dumps(changed).encode()
            manifest.write_bytes(data)
            self.bad(self.verify(hashlib.sha256(data).hexdigest()))

    def test_nonregular_source_and_symlink_bundle(self):
        self.source.unlink()
        os.mkfifo(self.source)
        self.bad(self.create("--at-eof"), "regular_source_required")
        self.source.unlink()
        self.source.write_bytes(META)
        result = self.ok(self.create("--at-eof"))
        alias = self.root / "alias"
        alias.symlink_to(self.out, target_is_directory=True)
        self.bad(self.cli("verify", "--bundle", str(alias), "--expected-manifest-sha256",
                          result["manifest_sha256"]), "bundle_directory_required")

    def test_partial_save_and_no_link_fallback(self):
        for operation in ("write", "link"):
            self.out = self.root / operation
            wrapper = '''import os,runpy,sys
def fail(*args, **kwargs):
    raise OSError('private sentinel')
'''
            if operation == "write":
                wrapper += """realopen=os.open
def broken(path, flags, *args, **kwargs):
    if str(path).endswith('transcript.jsonl') and flags & os.O_CREAT:
        os.close(realopen(path, flags, *args, **kwargs))
        fail()
    return realopen(path, flags, *args, **kwargs)
os.open=broken
"""
            else:
                wrapper += "os.link=fail\n"
            wrapper += "sys.argv=sys.argv[1:]\nrunpy.run_path(sys.argv[0],run_name='__main__')\n"
            self.bad(self.create("--at-eof", wrapper=wrapper), "io_failure")
            self.assertTrue(self.out.is_dir())
            self.assertFalse((self.out / "snapshot.json").exists())
            self.bad(self.verify("0" * 64))
            self.bad(self.create("--at-eof"), "output_exists")


if __name__ == "__main__":
    unittest.main()
