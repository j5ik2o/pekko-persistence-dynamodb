# Verify the JVM used by local sbt tests

Run from this checkout with Python 3. Use an explicit sbt executable and JDK directory.
Quote each sbt test command as one argument.

```bash
python3 tools/verify-sbt-runtime.py \
  --sbt /Users/j5ik2o/.local/share/mise/installs/sbt/1.12.12/bin/sbt \
  --java-home /Users/j5ik2o/.local/share/mise/installs/java/temurin-11.0.33+10 \
  --expected-major 11 \
  'schema-check/testOnly *FactoryClassReferenceSpec'
```

If you omit `--sbt`, the runner resolves it with `mise which sbt`.
Paths containing a `shims` directory are rejected. The runner passes `--java-home`
to sbt and reads `java.version` and `java.home` inside that same launcher JVM.
It does not use `JAVA_HOME` or a separate `java -version` as proof.

Read the printed `target/verify-sbt-runtime/<run_id>/result.json` path.
A `success` requires exit code zero, one launcher observation, and at least one
fresh JUnit report with test cases. Every fresh report must parse and include
`java.version` and `java.home` for each suite. All observed majors must match.
Only reports under `**/target/test-reports/*.xml` changed during this invocation,
with modification times inside its execution window, qualify. Unchanged or old
reports cannot prove the fork JVM, even if sbt exits successfully.

The JSON records the repository, HEAD, run ID, exact arguments, real command exit,
observed JVM properties, timestamps, and evidence paths with SHA-256 hashes.
The runner retains normal build output in `sbt.log`. It does not snapshot the
environment or copy all JUnit properties. Keep the referenced XML alongside the
JSON for later inspection. A later build can overwrite XML, so check its hash.

`runtime_status` is `match`, `mismatch`, or `unknown`. `status` is `failure` when
sbt fails, otherwise `success`, `mismatch`, or `unknown`. Exit codes are zero for
success, 3 for mismatch, and 4 for missing or invalid evidence. Argument errors
exit 2. An sbt failure preserves its positive exit code. A terminating signal is
recorded as a negative command exit and returned as 128 plus the signal number.

Use only `test` or `testOnly` with optional project and `Test/` prefixes.
Test patterns accept letters, digits, underscores, dots, stars, dollar signs,
and hyphens. Arbitrary sbt commands, test framework options, Scala switches,
publish, release, and `runMain` are rejected. Run other local checks separately.

This is a manual local test runner. It is not integrated into CI or other tasks.
Do not run concurrent builds in this checkout. Freshness uses file metadata and
hashes, not process attestation. A malicious executable or a concurrent report
writer can forge evidence. The runner trusts this repository's forked JUnit
reporter and cannot prove unreported tests or intermediate overwritten reports.
It compares Java majors, so it records but does not require identical patch
versions or home paths. It does not change repository or user settings.

Run its regression tests without sbt or Docker.

```bash
python3 -m unittest discover -s tools/tests -p test_verify_sbt_runtime.py -v
```
