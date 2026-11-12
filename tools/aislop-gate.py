#!/usr/bin/env python3
"""Fail when the local aislop scan skips Scala or reports a finding outside the baseline.

Exit codes: 0 when the scan covers Scala and every finding is in the baseline with a reason,
1 when a check fails, 2 when aislop cannot run or prints no JSON report.
"""
import json
import os
from pathlib import Path
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]
BASELINE = ROOT / ".aislop" / "ci-baseline.json"
SCALA_INCLUDE = "**/*.scala"


def evaluate(full, scala, baseline):
    problems = []
    if full["scoreable"] is not True or full["coverage"]["scoreable"] is not True:
        problems.append(f"aislop withheld the score: coverage={json.dumps(full['coverage'])}")
    if scala["coverage"]["supportedFiles"] == 0:
        problems.append(f"aislop analyzed no Scala file: --include '{SCALA_INCLUDE}' coverage={json.dumps(scala['coverage'])}")
    summary = full.get("baseline")
    if not summary or summary.get("status") != "ok":
        problems.append(f"aislop did not apply the baseline {BASELINE.relative_to(ROOT)}: {json.dumps(summary)}")
    for d in full["diagnostics"]:
        if d["severity"] != "info" and not d.get("baselined"):
            problems.append(f"new finding {d['rule']} {d['filePath']}:{d['line']}: {d['message']}")
    if summary and summary["stale"] > 0:
        problems.append(f"{summary['stale']} baseline finding(s) no longer occur; remove the entries")
    for e in baseline["entries"]:
        if not str(e.get("reason", "")).strip():
            problems.append(f"baseline entry without a reason: {e['rule']} {e['file']}")
    return problems


def scan(*args):
    env = dict(os.environ, AISLOP_NO_TELEMETRY="1", AISLOP_NO_HISTORY="1")
    command = [os.environ.get("AISLOP", "aislop"), "scan", "--json", *args, "."]
    try:
        result = subprocess.run(command, cwd=ROOT, env=env, capture_output=True, text=True, stdin=subprocess.DEVNULL)
    except OSError as error:
        raise RuntimeError(f"cannot run {command[0]}: {error}") from error
    try:
        return json.loads(result.stdout)
    except json.JSONDecodeError as error:
        raise RuntimeError(f"{' '.join(command)} exited {result.returncode} without a JSON report: {result.stderr.strip()}") from error


def main():
    try:
        full = scan()
        scala = scan("--include", SCALA_INCLUDE)
        baseline = json.loads(BASELINE.read_text())
        problems = evaluate(full, scala, baseline)
    except (RuntimeError, OSError, json.JSONDecodeError) as error:
        print(f"aislop-gate: {error}", file=sys.stderr)
        return 2
    except (KeyError, TypeError) as error:
        print(f"aislop-gate: unexpected aislop report or baseline shape: {error!r}", file=sys.stderr)
        return 2
    for problem in problems:
        print(f"aislop-gate: {problem}", file=sys.stderr)
    summary = full.get("baseline") or {"accepted": 0, "new": 0, "stale": 0}
    print(f"aislop-gate: aislop {full['cliVersion']} scanned {full['coverage']['supportedFiles']} files, "
          f"{scala['coverage']['supportedFiles']} Scala; {summary['accepted']} accepted, "
          f"{summary['new']} new, {summary['stale']} stale", file=sys.stderr)
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main())
