#!/usr/bin/env python3
"""Run local sbt tests and verify launcher and fresh JUnit JVM evidence."""

import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess
import sys
import time
import uuid
import xml.etree.ElementTree as ET


VERSION = re.compile(r"(?:1\.)?([0-9]+)(?:[.u+_-][0-9A-Za-z.+_-]+)?")
TEST_COMMAND = re.compile(
    r"(?:[A-Za-z0-9_-]+/)?(?:Test/)?(?:test|testOnly(?: [A-Za-z0-9_.*$][A-Za-z0-9_.*$-]*)+)"
)


def major(version):
    match = VERSION.fullmatch(version)
    return int(match[1]) if match else None


def evidence(path):
    data = path.read_bytes()
    stat = path.stat()
    return {"path": str(path), "sha256": hashlib.sha256(data).hexdigest(),
            "mtime_ns": stat.st_mtime_ns, "ctime_ns": stat.st_ctime_ns,
            "size": stat.st_size, "inode": stat.st_ino}


def reports(repo):
    return sorted(repo.glob("**/target/test-reports/*.xml"))


def fork_evidence(path):
    root = ET.parse(path).getroot()
    suites = [root] if root.tag == "testsuite" else list(root.findall("testsuite"))
    observations = []
    if not suites:
        raise ValueError("no JUnit suites")
    for suite in suites:
        if not suite.findall("testcase"):
            raise ValueError("no test cases")
        properties = suite.findall("./properties/property")
        values = {}
        for key in ("java.version", "java.home"):
            found = [p.get("value", p.text or "") for p in properties if p.get("name") == key]
            if len(found) != 1 or not found[0]:
                raise ValueError("missing or ambiguous " + key)
            values[key] = found[0]
        if major(values["java.version"]) is None:
            raise ValueError("unparseable java.version")
        observations.append({"suite": suite.get("name"), **values})
    return observations


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--sbt", help="explicit sbt executable; otherwise use mise which sbt")
    parser.add_argument("--java-home", required=True, type=Path)
    parser.add_argument("--expected-major", required=True, type=int)
    parser.add_argument("commands", nargs="+", help="quoted test or testOnly commands")
    args = parser.parse_args()
    if args.expected_major < 1 or not all(TEST_COMMAND.fullmatch(c) for c in args.commands):
        parser.error("only local test or testOnly commands are accepted")
    java_home = args.java_home.expanduser().resolve()
    if not (java_home / "bin/java").is_file():
        parser.error("--java-home must contain bin/java")
    repo = Path(subprocess.check_output(
        ["git", "rev-parse", "--show-toplevel"], text=True).strip()).resolve()
    head = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=repo, text=True).strip()
    run_id = uuid.uuid4().hex
    directory = repo / "target/verify-sbt-runtime" / run_id
    directory.mkdir(parents=True)
    result_path = directory / "result.json"
    result = {"run_id": run_id, "repo": str(repo), "head": head,
              "java_home": str(java_home), "expected_major": args.expected_major,
              "command": [], "command_exit": None, "launcher": [], "forks": [],
              "ignored_stale_reports": [], "errors": []}
    started = time.time_ns()
    result["started_ns"] = started
    try:
        if args.sbt:
            sbt = Path(args.sbt).expanduser().resolve()
        else:
            sbt = Path(subprocess.check_output(
                ["mise", "which", "sbt"], cwd=repo, text=True).strip()).resolve()
        if not sbt.is_file() or "shims" in sbt.parts:
            raise ValueError("select a real sbt executable, not a shim")
        before = {str(p): evidence(p) for p in reports(repo)}
        marker = "PPD_JVM_" + run_id + "="
        probe = ('eval println("' + marker + '" + System.getProperty("java.version")'
                 ' + "\\t" + System.getProperty("java.home"))')
        command = [str(sbt), "--java-home", str(java_home), "-batch", probe, *args.commands]
        result["command"] = command
        started = time.time_ns()
        result["started_ns"] = started
        log_path = directory / "sbt.log"
        with log_path.open("w") as log:
            process = subprocess.Popen(command, cwd=repo, stdout=subprocess.PIPE,
                                       stderr=subprocess.STDOUT, text=True, errors="replace")
            for line in process.stdout:
                sys.stdout.write(line)
                log.write(line)
                match = re.fullmatch(r"(?:\[info\] )?" + re.escape(marker) + r"([^\t\r\n]+)\t([^\r\n]+)\r?\n?", line)
                if match:
                    result["launcher"].append({"java.version": match[1], "java.home": match[2]})
            result["command_exit"] = process.wait()
            process.stdout.close()
        result["log"] = evidence(log_path)
        finished = time.time_ns()
        for path in reports(repo):
            item = evidence(path)
            if item == before.get(str(path)) or not started <= item["mtime_ns"] <= finished:
                result["ignored_stale_reports"].append(str(path))
                continue
            try:
                result["forks"].append({"evidence": item, "jvms": fork_evidence(path)})
            except (ET.ParseError, ValueError) as error:
                result["errors"].append({"evidence": item, "error": str(error)})
    except (OSError, ValueError, subprocess.SubprocessError) as error:
        result["errors"].append({"error": str(error)})
    launcher = result["launcher"]
    observed = launcher + [jvm for report in result["forks"] for jvm in report["jvms"]]
    if any(major(jvm["java.version"]) not in (None, args.expected_major) for jvm in observed):
        runtime = "mismatch"
    elif (len(launcher) != 1 or not result["forks"] or result["errors"]
          or any(major(jvm["java.version"]) is None for jvm in observed)):
        runtime = "unknown"
    else:
        runtime = "match"
    result["runtime_status"] = runtime
    code = result["command_exit"]
    if code not in (None, 0):
        result["status"] = "failure"
        exit_code = code if code > 0 else 128 - code
    elif code == 0 and runtime == "match":
        result["status"] = "success"
        exit_code = 0
    else:
        result["status"] = runtime
        exit_code = 3 if runtime == "mismatch" else 4
    result["exit_code"] = exit_code
    result["finished_ns"] = time.time_ns()
    result_path.write_text(json.dumps(result, indent=2) + "\n")
    print(f"runtime verification {result['status']}; result {result_path}")
    return exit_code


if __name__ == "__main__":
    sys.exit(main())
