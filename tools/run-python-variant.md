# Run one Python variant

This manual runner addresses source restoration followed by stale `.pyc` reads.
Prepare a **small input tree** containing one trusted test script, its source,
and only the fixtures it needs. Invoke once per baseline, mutant, or restored
variant. The runner does not create mutations or interpret test failures.

```sh
mkdir /tmp/variant-input
printf 'VALUE = "baseline"\n' > /tmp/variant-input/subject.py
printf 'import subject\nassert subject.VALUE == "baseline", "wrong-value"\n' > /tmp/variant-input/test_subject.py
python3 tools/run-python-variant.py \
  --input-dir "$(cd /tmp/variant-input && pwd -P)" \
  --source subject.py --test test_subject.py \
  --out "$(cd /tmp && pwd -P)/variant-baseline-evidence" --label baseline
```

Use fresh paths if these already exist. `--input-dir` must be absolute and
canonical; source/test must be distinct canonical relative regular `.py` files.
The entire explicitly prepared tree is copied, preserving relative paths and
bytes. Symlinks, hardlink aliases, special files, `.git`, `.pyc`, and
`__pycache__` are rejected. `--out` must be a new canonical directory outside
the input tree, with an existing parent. Existing outputs, including partial
outputs, are never reused, overwritten, or automatically removed.

The runner compares path/byte-count/SHA256 manifests before and after copying,
and checks the staged manifest after execution, including fixture files.
Directory entries are recorded as `{"kind": "directory"}`, including empty
directories; their creation or removal during execution makes the record incomplete.
Detected input changes during copying make the record incomplete. These checks
are not an atomic snapshot: transient changes between reads may escape detection.
Keep prepared inputs quiescent while copying. After copying, changes to original
input bytes do not affect the staged copy. File permissions and timestamps are
not preserved; fixtures must depend on their bytes and relative layout.

The direct child uses the exact argv saved in `result.json`:
`PYTHON -I -X pycache_prefix=OUTPUT/cache OUTPUT/bootstrap.py ...`.
`--python EXEC` selects one executable, defaulting to `sys.executable`; no shell
or extra argument DSL is used. The environment also sets
`PYTHONPYCACHEPREFIX=OUTPUT/cache` for ordinary Python descendants. `-I` ignores
Python environment settings for the direct child, so the explicit `-X` matters.
Descendants can override or ignore these settings.

The fixed bootstrap observes run ID, PID, executable, version, isolation flag,
cache prefix, and staged source/test paths and hashes in the **same process**
before calling `runpy.run_path(test, run_name="__main__")`. The parent checks
these observations against its saved manifest and child PID. `observation.json`
also records `sys.path` before/after setup: isolated startup does not add the
bootstrap/script directory. The bootstrap explicitly prepends the staged test
script directory, then staged input root (deduplicated), preserving sibling
imports and root package imports. The child cwd is the staged input root;
`sys.argv` for the test contains only its staged script path. Tests must run as
one standalone script, for example with `unittest.main()`.

Each output contains:

- `input/`: copied source, test, and fixtures; `cache/`: dedicated bytecode cache.
- `bootstrap.py` and `observation.json`: bootstrap bytes and direct child observations.
- `stdout.raw`, `stderr.raw`: child output streamed directly as bytes, including non-UTF8.
- `result.json`: schema version, label, run ID, UTC start/end timestamps, exact argv,
  cwd, input reference and manifest, staged paths/manifest, bootstrap hash, cache,
  actual Python observations, child exit, timeout, final staged manifest, raw log
  paths/byte counts/hashes, status, and errors.

Only metadata (including result path/hash) is printed to stdout. Environment
values are not dumped. Test output itself can contain secrets; choose trusted
programs and handle their raw logs appropriately.

Runner exit codes:

| Code | Meaning |
| --- | --- |
| 2 | Invalid CLI/input, before allocating output |
| 1 | Incomplete evidence: missing/mismatching observation, changed staged files, timeout, launch/copy/save failure |
| 0 | Complete record, **regardless of child exit code** |

A recorded child exit 1 does not mean a killed mutation. Read `child_exit` and
raw logs to establish the intended difference. There is no assertion parser or
`passed`/`killed` classification. `result.json` is written last. A save failure
can leave no usable result; exit 1 and preserved partial files remain the evidence.
Other incomplete runs save an incomplete result when possible. Do not treat
mere presence of a result file as completion; check the runner exit and status.

`--timeout SECONDS` defaults to 60 and must be positive and finite. The child
starts a new POSIX session. On timeout the runner sends SIGKILL only to its owned
process group. If that fails, it tries to kill the direct child, then waits for a
bounded interval and records cleanup errors. This does not guarantee stopping
escaped descendants. Normal exit does not clean up descendant processes.

This runner is for trusted explicit programs on macOS/Linux, **not a sandbox**.
It does not attest that the selected source was imported, all dependencies were
recorded, loaders were honest, or the host was authentic. Hashes describe bytes
read at observation points. Tests can perform external actions and can tamper
with evidence; do not run untrusted code. No git cloning, patching, batching,
model calls, network access by the runner, dispatch, or CI integration is provided.
The implementation uses Python 3.9+ APIs. Validation for this change ran on
macOS with Python 3.13.3; Python 3.9 and Linux were not executed.

Run the acceptance suite:

```sh
python3 -I -X pycache_prefix=/tmp/new-variant-suite-cache \
  tools/tests/test_run_python_variant.py
```

The suite reproduces timestamp/size-based stale mutant reads after restoration
in an explicit temporary shared path/cache control, then checks the actual CLI's
baseline child 0, mutant child 1 (`wrong-value: mutated!`), and restored child 0.
It checks real child observations, hashes, logs, invalid input, input-copy
independence, staged rewrites, missing/different observations, launch/timeout/save
failures, and binary logs. Deterministic phase hooks test copy changes without a
sleep race. Two isolated runner source copies remove prefix/hash checks and must
fail the corresponding regressions with `0 != 1`, proving those regressions
exercise the checks. Runner mutation paths and suite subject paths are distinct.
A fresh staged path alone can avoid old bytecode: removing prefix configuration
is **not** claimed to necessarily reproduce stale reads. Prefix validation has
its own observed different-prefix negative control.
