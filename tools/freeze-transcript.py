#!/usr/bin/env python3
"""Freeze explicitly selected Codex JSONL bytes. Python 3.9+, stdlib only."""
import argparse
import datetime
import hashlib
import json
import os
from pathlib import Path
import re
import stat
import sys


class Rejected(Exception):
    def __init__(self, code, status=2):
        self.code = code
        self.status = status


def require(condition, code, status=2):
    if not condition:
        raise Rejected(code, status)


def pairs(items):
    result = {}
    for key, value in items:
        require(key not in result, "duplicate_key")
        result[key] = value
    return result


def reject_constant(value):
    raise Rejected("invalid_json")


def decode(data):
    try:
        value = json.loads(data.decode("utf-8") if isinstance(data, bytes) else data,
                           object_pairs_hook=pairs,
                           parse_constant=reject_constant)
    except (ValueError, UnicodeError):
        raise Rejected("invalid_json_or_utf8") from None
    require(isinstance(value, dict), "json_not_object")
    return value


def sha(data):
    return hashlib.sha256(data).hexdigest()


def absolute(value):
    require(isinstance(value, str) and os.path.isabs(value), "absolute_path_required")
    return value


def text(value):
    return isinstance(value, str) and bool(value.strip())


def records(data):
    require(bool(data) and data.endswith(b"\n"), "partial_line")
    lines = data.split(b"\n")[:-1]
    events = [decode(line) for line in lines]
    require(events[0].get("type") == "session_meta", "missing_session_meta")
    require(sum(e.get("type") == "session_meta" for e in events) == 1, "duplicate_session_meta")
    session = events[0].get("payload")
    require(isinstance(session, dict) and text(session.get("id")), "invalid_session_meta")
    return lines, events, {"id": session["id"], "cwd": os.path.normpath(absolute(session.get("cwd")))}


def regular_read(path):
    fd = os.open(path, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK)
    with os.fdopen(fd, "rb") as stream:
        require(stat.S_ISREG(os.fstat(stream.fileno()).st_mode), "regular_file_required")
        return stream.read()


def exclusive_write(path, data):
    fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(fd, "wb") as stream:
        stream.write(data)
        stream.flush()
        os.fsync(stream.fileno())


def artifact(data):
    return {"path": "transcript.jsonl", "lines": data.count(b"\n"),
            "bytes": len(data), "sha256": sha(data)}


def hexhash(value):
    return isinstance(value, str) and re.fullmatch(r"[0-9a-f]{64}", value) is not None


def verify(bundle, expected):
    require(hexhash(expected), "invalid_expected_hash")
    require(not bundle.is_symlink() and bundle.is_dir(), "bundle_directory_required")
    raw = regular_read(bundle / "snapshot.json")
    require(sha(raw) == expected, "manifest_hash_mismatch")
    manifest = decode(raw)
    require(type(manifest.get("schema")) is int and manifest["schema"] == 1
            and manifest.get("format") == "codex-jsonl-observed-v1", "unsupported_manifest")
    data = regular_read(bundle / "transcript.jsonl")
    _, _, session = records(data)
    require(manifest.get("session") == session, "session_mismatch")
    saved_artifact = manifest.get("artifact")
    require(isinstance(saved_artifact, dict)
            and type(saved_artifact.get("lines")) is int
            and type(saved_artifact.get("bytes")) is int
            and saved_artifact == artifact(data), "artifact_mismatch")
    source = manifest.get("source")
    cutoff = manifest.get("cutoff")
    require(isinstance(source, dict) and isinstance(cutoff, dict), "invalid_manifest")
    absolute(source.get("input_path"))
    absolute(source.get("resolved_path"))
    size = source.get("captured_size_bytes")
    require(type(size) is int and size >= len(data), "invalid_captured_size")
    try:
        stamp = datetime.datetime.fromisoformat(source["captured_at_utc"])
        require(stamp.utcoffset() == datetime.timedelta(0), "invalid_capture_time")
    except (KeyError, TypeError, ValueError):
        raise Rejected("invalid_capture_time") from None
    require(type(cutoff.get("line_exclusive")) is int
            and cutoff["line_exclusive"] == data.count(b"\n") + 1
            and type(cutoff.get("byte_offset_exclusive")) is int
            and cutoff["byte_offset_exclusive"] == len(data), "cutoff_mismatch")
    if manifest.get("mode") == "at-eof":
        require(size == len(data) and set(cutoff) == {"line_exclusive", "byte_offset_exclusive"},
                "cutoff_mismatch")
    else:
        require(manifest.get("mode") == "before-call-id" and size > len(data)
                and text(cutoff.get("call_id")) and cutoff.get("name") == "spawn_agent"
                and text(cutoff.get("task_name")) and hexhash(cutoff.get("event_sha256")),
                "invalid_cutoff")
    return {"manifest_path": str(bundle / "snapshot.json"), "manifest_sha256": expected,
            "artifact_path": str(bundle / "transcript.jsonl"), **manifest}


def create(args):
    source = Path(absolute(args.source))
    cwd = os.path.normpath(absolute(args.expected_cwd))
    require(text(args.expected_session_id), "invalid_expected_session")
    out = Path(os.path.abspath(args.out))
    require(not os.path.lexists(out), "output_exists")
    fd = os.open(source, os.O_RDONLY | os.O_NONBLOCK)
    with os.fdopen(fd, "rb", buffering=0) as stream:
        info = os.fstat(stream.fileno())
        require(stat.S_ISREG(info.st_mode), "regular_source_required")
        captured_at = datetime.datetime.now(datetime.timezone.utc).isoformat()
        data = stream.read(info.st_size)
        require(len(data) == info.st_size, "source_short_read")
    lines, events, session = records(data)
    require(session == {"id": args.expected_session_id, "cwd": cwd}, "session_mismatch")
    cutoff = {"line_exclusive": len(lines) + 1, "byte_offset_exclusive": len(data)}
    if args.before_call_id is not None:
        matches = [(i, e["payload"]) for i, e in enumerate(events)
                   if e.get("type") == "response_item" and isinstance(e.get("payload"), dict)
                   and e["payload"].get("type") == "function_call"
                   and e["payload"].get("call_id") == args.before_call_id]
        require(bool(matches), "cutoff_not_found")
        require(len(matches) == 1, "ambiguous_cutoff")
        index, call = matches[0]
        require(call.get("name") == "spawn_agent" and isinstance(call.get("arguments"), str),
                "unsupported_cutoff")
        task = decode(call["arguments"]).get("task_name")
        require(text(task), "unsupported_cutoff")
        cutoff = {"line_exclusive": index + 1,
                  "byte_offset_exclusive": sum(len(line) + 1 for line in lines[:index]),
                  "call_id": args.before_call_id, "name": "spawn_agent", "task_name": task,
                  "event_sha256": sha(lines[index] + b"\n")}
        data = data[:cutoff["byte_offset_exclusive"]]
    manifest = {"schema": 1, "format": "codex-jsonl-observed-v1",
                "source": {"input_path": args.source, "resolved_path": str(source.resolve()),
                           "captured_size_bytes": info.st_size, "captured_at_utc": captured_at},
                "session": session, "mode": "at-eof" if args.at_eof else "before-call-id",
                "cutoff": cutoff, "artifact": artifact(data)}
    try:
        os.mkdir(out, 0o700)
        exclusive_write(out / "transcript.jsonl", data)
        saved = regular_read(out / "transcript.jsonl")
        require(artifact(saved) == manifest["artifact"] and records(saved)[2] == session,
                "saved_prefix_mismatch", 1)
        raw = (json.dumps(manifest, sort_keys=True, indent=2) + "\n").encode("utf-8")
        temporary = out / ".snapshot.pending"
        exclusive_write(temporary, raw)
        require(decode(regular_read(temporary)) == manifest, "saved_manifest_mismatch", 1)
        os.link(temporary, out / "snapshot.json")
        temporary.unlink()
        return verify(out, sha(raw))
    except Rejected as error:
        raise Rejected(error.code, 1) from None


class Parser(argparse.ArgumentParser):
    def error(self, message):
        raise Rejected("invalid_arguments")


def main():
    parser = Parser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True, parser_class=Parser)
    capture = commands.add_parser("create")
    for name in ("source", "expected-session-id", "expected-cwd", "out"):
        capture.add_argument("--" + name, required=True)
    modes = capture.add_mutually_exclusive_group(required=True)
    modes.add_argument("--at-eof", action="store_true")
    modes.add_argument("--before-call-id")
    check = commands.add_parser("verify")
    check.add_argument("--bundle", required=True)
    check.add_argument("--expected-manifest-sha256", required=True)
    try:
        args = parser.parse_args()
        result = create(args) if args.command == "create" else verify(
            Path(os.path.abspath(args.bundle)), args.expected_manifest_sha256)
        print(json.dumps(result, sort_keys=True))
        return 0
    except Rejected as error:
        print(error.code, file=sys.stderr)
        return error.status
    except OSError:
        print("io_failure", file=sys.stderr)
        return 1
    except (ValueError, TypeError, KeyError, RecursionError, OverflowError):
        print("invalid_structure", file=sys.stderr)
        return 2


if __name__ == "__main__":
    sys.exit(main())
