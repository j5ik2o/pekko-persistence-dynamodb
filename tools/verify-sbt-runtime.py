#!/usr/bin/env python3
"""Run local sbt tests and verify launcher and self-observed test-fork JVM evidence."""

import argparse
import base64
import hashlib
import json
from pathlib import Path
import re
import subprocess
import sys
import time
import uuid
import xml.etree.ElementTree as ET
import zipfile


VERSION = re.compile(r"(?:1\.)?([0-9]+)(?:[.u+_-][0-9A-Za-z.+_-]+)?")
TEST_COMMAND = re.compile(
    r"([A-Za-z0-9_-]+)/(?:Test/)?(?:test|testOnly(?: [A-Za-z0-9_.*$][A-Za-z0-9_.*$-]*)+)"
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


def test_evidence(path):
    root = ET.parse(path).getroot()
    suites = [root] if root.tag == "testsuite" else list(root.findall("testsuite"))
    if not suites or any(not s.findall("testcase") for s in suites):
        raise ValueError("no test cases")
    return [{"suite": s.get("name"), "cases": len(s.findall("testcase"))} for s in suites]


def prepare_probe(java_home, directory, run_id):
    source = Path(__file__).resolve().parent / "jvm-probe/RuntimeProbe.java"
    classes = directory / "classes"
    classes.mkdir()
    subprocess.run([str(java_home / "bin/javac"), "--release", "11", "-d", str(classes),
                    str(source)], check=True, capture_output=True, text=True)
    jar = directory / "runtime-probe.jar"
    with zipfile.ZipFile(jar, "w") as archive:
        archive.writestr("META-INF/MANIFEST.MF", "Manifest-Version: 1.0\nPremain-Class: RuntimeProbe\n\n")
        archive.write(classes / "RuntimeProbe.class", "RuntimeProbe.class")
    output = directory / "forks"
    output.mkdir()
    encoded = base64.urlsafe_b64encode(str(output).encode()).decode()
    return "-javaagent:" + str(jar) + "=" + run_id + ":" + encoded


def instrumentation(project, agent, run_id):
    scope = 'LocalProject(' + json.dumps(project) + ') / '
    option = json.dumps(agent)
    # Validate the actual group's options, which custom grouping may replace.
    grouping = ('set ' + scope + 'Test / testGrouping := { '
                'val gs = (' + scope + 'Test / testGrouping).value; '
                'require(gs.size == 1, "unsupported fork grouping"); '
                'gs.head.runPolicy match { case Tests.SubProcess(o) => '
                'require(o.runJVMOptions.count(_ == ' + option + ') == 1, '
                '"fork group ignores probe javaOptions"); '
                'case _ => sys.error("non-fork tests unsupported") }; '
                'println("PPD_GROUP_' + run_id + '"); gs }')
    return ['set ' + scope + 'Test / javaOptions += ' + option,
            'set ' + scope + 'Test / Keys.test / aggregate := false',
            'set ' + scope + 'Test / Keys.testOnly / aggregate := false', grouping]


def collect_execution_evidence(result, repo, directory, run_id, before):
    started = result["started_ns"]
    finished = time.time_ns()
    for path in reports(repo):
        item = evidence(path)
        if item == before.get(str(path)) or not started <= item["mtime_ns"] <= finished:
            result["ignored_stale_reports"].append(str(path))
            continue
        try:
            result["reports"].append({"evidence": item, "suites": test_evidence(path)})
        except (ET.ParseError, ValueError) as error:
            result["errors"].append({"evidence": item, "error": str(error)})
    for path in sorted((directory / "forks").glob("*.probe")):
        item = evidence(path)
        try:
            lines = path.read_text().splitlines()
            if len(lines) != 3 or lines[0] != run_id or not started <= item["mtime_ns"] <= finished:
                raise ValueError("invalid or stale fork probe")
            values = [base64.b64decode(v, validate=True).decode() for v in lines[1:]]
            if not all(values):
                raise ValueError("empty fork properties")
            result["forks"].append({"evidence": item, "jvms": [
                {"java.version": values[0], "java.home": values[1]}]})
        except (ValueError, UnicodeError) as error:
            result["errors"].append({"evidence": item, "error": str(error)})


def run_sbt(args, repo, java_home, directory, run_id, result):
    if args.sbt:
        sbt = Path(args.sbt).expanduser().resolve()
    else:
        sbt = Path(subprocess.check_output(
            ["mise", "which", "sbt"], cwd=repo, text=True).strip()).resolve()
    if not sbt.is_file() or "shims" in sbt.parts:
        raise ValueError("select a real sbt executable, not a shim")
    before = {str(path): evidence(path) for path in reports(repo)}
    marker = "PPD_JVM_" + run_id + "="
    probe = ('eval println("' + marker + '" + System.getProperty("java.version")'
             ' + "\\t" + System.getProperty("java.home"))')
    agent = prepare_probe(java_home, directory, run_id)
    result["probe_source"] = evidence(Path(__file__).resolve().parent / "jvm-probe/RuntimeProbe.java")
    project = TEST_COMMAND.fullmatch(args.commands[0])[1]
    command = [str(sbt), "--java-home", str(java_home), "-batch", probe,
               *instrumentation(project, agent, run_id), *args.commands]
    if args.fork_java_home:
        fork_home = args.fork_java_home.expanduser().resolve()
        if not (fork_home / "bin/java").is_file():
            raise ValueError("--fork-java-home must contain bin/java")
        command.insert(4, 'set LocalProject(' + json.dumps(project) +
                       ') / Test / javaHome := Some(file(' + json.dumps(str(fork_home)) + '))')
    result["command"] = command
    result["started_ns"] = time.time_ns()
    log_path = directory / "sbt.log"
    with log_path.open("w") as log:
        process = subprocess.Popen(command, cwd=repo, stdout=subprocess.PIPE,
                                   stderr=subprocess.STDOUT, text=True, errors="replace")
        for line in process.stdout:
            sys.stdout.write(line)
            log.write(line)
            match = re.fullmatch(r"(?:\[info\] )?" + re.escape(marker) + r"([^\t\r\n]+)\t([^\r\n]+)\r?\n?", line)
            if line.strip() == "PPD_GROUP_" + run_id:
                result["groups"].append(run_id)
            if match:
                result["launcher"].append({"java.version": match[1], "java.home": match[2]})
        result["command_exit"] = process.wait()
        process.stdout.close()
    result["log"] = evidence(log_path)
    collect_execution_evidence(result, repo, directory, run_id, before)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--sbt", help="explicit sbt executable; otherwise use mise which sbt")
    parser.add_argument("--java-home", required=True, type=Path)
    parser.add_argument("--fork-java-home", type=Path, help="optional in-memory test fork JDK override")
    parser.add_argument("--expected-major", required=True, type=int)
    parser.add_argument("commands", nargs=1, help="quoted test or testOnly commands")
    args = parser.parse_args()
    if args.expected_major < 1 or not all(TEST_COMMAND.fullmatch(c) for c in args.commands):
        parser.error("one explicit project/test or project/testOnly command is required")
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
              "runner_source": evidence(Path(__file__).resolve()),
              "reports": [], "groups": [], "ignored_stale_reports": [], "errors": []}
    started = time.time_ns()
    result["started_ns"] = started
    try:
        run_sbt(args, repo, java_home, directory, run_id, result)
    except (OSError, ValueError, subprocess.SubprocessError) as error:
        result["errors"].append({"error": str(error)})
    launcher = result["launcher"]
    observed = launcher + [jvm for report in result["forks"] for jvm in report["jvms"]]
    if any(major(jvm["java.version"]) not in (None, args.expected_major) for jvm in observed):
        runtime = "mismatch"
    elif (len(launcher) != 1 or len(result["forks"]) != 1 or result["groups"] != [run_id]
          or not result["reports"] or result["errors"]
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
