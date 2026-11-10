# Verify the JVM used by local sbt tests

Run from this checkout with Python 3 and a JDK containing `java` and `javac`.
Quote the single, explicitly project-scoped test command.

```bash
python3 tools/verify-sbt-runtime.py \
  --sbt /Users/j5ik2o/.local/share/mise/installs/sbt/1.12.12/bin/sbt \
  --java-home /Users/j5ik2o/.local/share/mise/installs/java/temurin-11.0.33+10 \
  --expected-major 11 \
  'schema-check/testOnly *FactoryClassReferenceSpec'
```

Without `--sbt`, resolution uses `mise which sbt`. Paths containing `shims` are
rejected. `--java-home` selects the launcher and the compiler for the small fixed
Java agent. The launcher reports only `java.version` and `java.home` through a
fixed in-memory sbt expression. The agent reports those same two properties from
inside the actual test fork, together with the run ID. Its filename is the fork
PID. It reads no other properties or environment variables. Compilation and JAR
packaging write only under `target/verify-sbt-runtime/<run_id>`.

The runner adds the agent using session-only `Test / javaOptions`. It validates
the actual `Test / testGrouping` value before tests execute. Only one subprocess
group with exactly one copy of the agent option is supported. Non-fork execution,
multiple groups, and groups that discard that option are rejected. Aggregation
is disabled for this invocation, so the command runs only the named project's
tests. Run other projects separately. This deliberately narrow contract prevents
one observed group from standing in for other unobserved groups.

For controlled mismatch checks, `--fork-java-home /path/to/another/jdk` sets only
that project's `Test / javaHome` in memory. This selection never counts as runtime
evidence. The actual agent output still determines the observed fork version.
No build file, user settings, or CI policy is changed.

A `success` requires sbt exit zero, one launcher observation, one validated group,
exactly one fresh agent record with the current run ID, and fresh JUnit test-case
evidence. Every observed Java major must match `--expected-major`. An observed
mismatch always makes runtime status `mismatch`, even with other missing evidence.
Missing, malformed, old, or unsupported evidence yields `unknown` otherwise.

JUnit `java.version` and `java.home` are **never fork evidence**. In sbt 1.12.12,
[JUnitXmlTestsListener](https://github.com/sbt/sbt/blob/v1.12.12/testing/src/main/scala/sbt/JUnitXmlTestsListener.scala)
reads the launcher's properties. A real launcher-11/fork-26 run can pass five tests
and still write Java 11 into fresh XML. XML here proves only that fresh test cases
were reported. Reports must change during this invocation and have modification
times within its execution window. Unchanged or old reports cannot establish
execution, even if sbt exits successfully.

Read the printed `target/verify-sbt-runtime/<run_id>/result.json` path. It records
repository, HEAD, run ID, exact sbt arguments, command exit, timestamps, launcher
properties, validated group markers, agent source hash, fork records, fresh case
reports, and evidence paths with SHA-256 hashes. `forks` now means agent records;
`reports` contains JUnit case evidence without JVM properties. Old results that
stored XML under `forks` do not prove the fork runtime. Keep `sbt.log`, the probe
records, and referenced XML with the JSON. Later builds can overwrite XML, so
verify its hash before reuse.

`runtime_status` is `match`, `mismatch`, or `unknown`. `status` is `failure` when
sbt fails, otherwise `success`, `mismatch`, or `unknown`. Exit codes are zero for
success, 3 for mismatch, and 4 for missing or invalid evidence. Argument errors
exit 2. A positive sbt failure code is preserved. A terminating signal is recorded
as a negative command exit and returned as 128 plus the signal number.

Only `project/test` and `project/testOnly patterns`, optionally with `Test/`, are
accepted. Patterns allow letters, digits, underscores, dots, stars, dollar signs,
and hyphens. Arbitrary settings, framework options, `runMain`, publish, release,
and Scala switches are rejected. Internal generated settings are fixed except
for quoted paths and the validated project name. Agent output paths are encoded.

This is a manual local runner with no CI integration. Do not run concurrent
builds in this checkout. This is runtime observation, not malicious-process
attestation. It trusts sbt's execution of the validated group and its test
reporter. Custom tasks that launch unrelated tests, adversarial executables,
concurrent writers, and unreported tests are outside the contract. Comparison
uses majors; patch versions and home paths are recorded for inspection.

Run regressions without sbt or Docker. The fake compiler and fake sbt exercise
classification and artifact handling; real acceptance must also exercise the
agent in an sbt test fork.

```bash
PYTHONPYCACHEPREFIX="$PWD/target/runtime-test-pycache" \
  python3 -m unittest discover -s tools/tests -p test_verify_sbt_runtime.py -v
```
