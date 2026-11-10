#!/usr/bin/env python3
"""Record one caller-prepared Python variant; see run-python-variant.md."""
import argparse
from datetime import datetime, timezone
import hashlib
import json
import math
import os
from pathlib import Path
import signal
import stat
import subprocess
import sys
import uuid


BOOTSTRAP = r'''import hashlib
import json
import os
from pathlib import Path
import runpy
import sys

run_id, root, source, test, observation = sys.argv[1:]
def record(path):
    raw = Path(path).read_bytes()
    return {"path": path, "bytes": len(raw), "sha256": hashlib.sha256(raw).hexdigest()}
initial_path = list(sys.path)
sys.path[:0] = list(dict.fromkeys([str(Path(test).parent), root]))
with open(observation, "x", encoding="utf-8") as stream:
    json.dump({"run_id": run_id, "pid": os.getpid(), "executable": sys.executable,
               "version": sys.version, "isolated": sys.flags.isolated,
               "cache": sys.pycache_prefix, "initial_sys_path": initial_path,
               "test_sys_path": list(sys.path),
               "source": record(source), "test": record(test)}, stream, indent=2)
sys.argv = [test]
runpy.run_path(test, run_name="__main__")
'''


class InvalidInput(ValueError):
    pass


def stamp():
    return datetime.now(timezone.utc).isoformat()


def digest(path):
    raw = path.read_bytes()
    return {"bytes": len(raw), "sha256": hashlib.sha256(raw).hexdigest()}


def manifest(root):
    files = {}
    identities = set()
    for path in sorted(root.rglob("*")):
        rel = path.relative_to(root)
        mode = path.lstat()
        if path.name in {".git", "__pycache__"} or path.suffix == ".pyc":
            raise InvalidInput("forbidden input: " + str(rel))
        if stat.S_ISDIR(mode.st_mode):
            files[str(rel)] = {"kind": "directory"}
            continue
        if not stat.S_ISREG(mode.st_mode):
            raise InvalidInput("input must contain only regular files/directories: " + str(rel))
        identity = (mode.st_dev, mode.st_ino)
        if identity in identities or mode.st_nlink != 1:
            raise InvalidInput("input file alias: " + str(rel))
        identities.add(identity)
        files[str(rel)] = digest(path)
    return files


def regular_relative(root, value):
    path = Path(value)
    if (path.is_absolute() or path.as_posix() != value or
            any(part in {".", "..", ""} for part in value.split("/")) or path.suffix != ".py"):
        raise InvalidInput("source/test must be canonical relative .py paths")
    target = root / path
    if not target.is_file() or target.is_symlink():
        raise InvalidInput("source/test must be regular .py files")
    return path


def validate(args):
    if sys.platform not in {"darwin", "linux"}:
        raise InvalidInput("supported platforms: macOS/Linux")
    raw = Path(args.input_dir)
    if not raw.is_absolute() or raw != raw.resolve():
        raise InvalidInput("input-dir must be an absolute canonical directory")
    if not raw.is_dir():
        raise InvalidInput("input-dir must exist")
    root = raw.resolve()
    out = Path(args.out).absolute()
    if out != out.resolve() or out.exists() or out.is_relative_to(root):
        raise InvalidInput("out must be new, canonical, and outside input-dir")
    if not out.parent.is_dir():
        raise InvalidInput("out parent must exist")
    if not math.isfinite(args.timeout) or args.timeout <= 0:
        raise InvalidInput("timeout must be finite and positive")
    before = manifest(root)
    source = regular_relative(root, args.source)
    test = regular_relative(root, args.test)
    if source == test:
        raise InvalidInput("source and test must differ")
    return root, out, source, test, before


def write_json(path, value):
    with path.open("x", encoding="utf-8") as stream:
        json.dump(value, stream, indent=2, sort_keys=True)
        stream.write("\n")


def execute(args, root, out, source, test, before):
    out.mkdir()
    stage, cache = out / "input", out / "cache"
    observation = out / "observation.json"
    bootstrap = out / "bootstrap.py"
    result = {"schema": 1, "label": args.label, "run_id": uuid.uuid4().hex,
              "started": stamp(), "status": "incomplete", "errors": [],
              "input_dir": str(root), "input_files": before, "staged_dir": str(stage),
              "source": str(stage / source), "test": str(stage / test),
              "cache": str(cache), "child_exit": None, "timeout": False}
    try:
        stage.mkdir()
        cache.mkdir()
        for rel, expected in before.items():
            if expected == {"kind": "directory"}:
                (stage / rel).mkdir(parents=True, exist_ok=True)
                continue
            raw = (root / rel).read_bytes()
            if {"bytes": len(raw), "sha256": hashlib.sha256(raw).hexdigest()} != expected:
                raise ValueError("input changed during copy: " + rel)
            target = stage / rel
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(raw)
        result["staged_files"] = manifest(stage)
        if manifest(root) != before or result["staged_files"] != before:
            raise ValueError("input/stage changed during copy")
        bootstrap.write_text(BOOTSTRAP, encoding="utf-8")
        result["bootstrap"] = dict(path=str(bootstrap), **digest(bootstrap))
        argv = [args.python, "-I", "-X", "pycache_prefix=" + str(cache), str(bootstrap),
                result["run_id"], str(stage), str(stage / source), str(stage / test), str(observation)]
        result.update(argv=argv, cwd=str(stage))
        env = dict(os.environ, PYTHONPYCACHEPREFIX=str(cache))
        with (out / "stdout.raw").open("xb") as stdout, (out / "stderr.raw").open("xb") as stderr:
            child = subprocess.Popen(argv, cwd=stage, env=env, stdout=stdout, stderr=stderr,
                                     start_new_session=True)
            result["child_pid"] = child.pid
            try:
                result["child_exit"] = child.wait(timeout=args.timeout)
            except subprocess.TimeoutExpired:
                result["timeout"] = True
                try:
                    os.killpg(child.pid, signal.SIGKILL)
                except ProcessLookupError:
                    pass
                result["child_exit"] = child.wait()
                result["errors"].append("timeout")
        observed = json.loads(observation.read_bytes())
        result["observed"] = observed
        expected = {"run_id": result["run_id"], "pid": child.pid, "isolated": 1,
                    "cache": str(cache),
                    "source": dict(path=str(stage / source), **before[str(source)]),
                    "test": dict(path=str(stage / test), **before[str(test)])}
        if any(observed.get(key) != value for key, value in expected.items()):
            result["errors"].append("bootstrap observation mismatch")
        if not observed.get("executable") or not observed.get("version"):
            result["errors"].append("missing Python observation")
    except Exception as error:
        result["errors"].append(type(error).__name__ + ": " + str(error))
    try:
        # Cache artifacts belong under cache, so any new stage file is also a change.
        result["post_files"] = manifest(stage)
        if result["post_files"] != before:
            result["errors"].append("staged files changed after execution")
        result["logs"] = {name: dict(path=str(out / name), **digest(out / name))
                          for name in ("stdout.raw", "stderr.raw")}
    except Exception as error:
        result["errors"].append(type(error).__name__ + ": " + str(error))
    result["finished"] = stamp()
    if not result["errors"]:
        result["status"] = "complete"
    path = out / "result.json"
    try:
        write_json(path, result)
        metadata = dict(result=str(path), **digest(path), status=result["status"],
                        child_exit=result["child_exit"])
    except Exception as error:
        print("incomplete: cannot save result: " + str(error), file=sys.stderr)
        return 1
    print(json.dumps(metadata, sort_keys=True))
    return 0 if result["status"] == "complete" else 1


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--input-dir", required=True)
    parser.add_argument("--source", required=True)
    parser.add_argument("--test", required=True)
    parser.add_argument("--out", required=True)
    parser.add_argument("--label", required=True)
    parser.add_argument("--python", default=sys.executable, help="one Python executable (no shell)")
    parser.add_argument("--timeout", type=float, default=60, help="seconds; default 60")
    args = parser.parse_args()
    try:
        prepared = validate(args)
    except (ValueError, OSError) as error:
        parser.error(str(error))
    try:
        return execute(args, *prepared)
    except OSError as error:
        print("incomplete: " + str(error), file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
