# Freeze a Codex transcript for parallel review

This manual offline CLI uses Python 3.9 or later and the standard library.
It reads only the explicitly supplied source. It does not discover sessions,
run models, dispatch reviewers, execute external commands, or use the network.

## Create and hand off a bundle

Set `SOURCE` to an absolute JSONL path, `SESSION` to its session ID, `CWD`
to its absolute recorded working directory, and `BUNDLE` to a new directory.
Before you dispatch reviewers, run:

```sh
python3 tools/freeze-transcript.py create \
  --source "$SOURCE" --expected-session-id "$SESSION" \
  --expected-cwd "$CWD" --out "$BUNDLE" --at-eof
```

Copy `manifest_sha256` from successful stdout into `MANIFEST_SHA`.
Pass the same bundle path and this hash to every reviewer. Each reviewer runs:

```sh
python3 tools/freeze-transcript.py verify \
  --bundle "$BUNDLE" --expected-manifest-sha256 "$MANIFEST_SHA"
```

After exit 0, each reviewer reads that bundle's `transcript.jsonl`.
The external expected manifest hash binds the cutoff and prefix hash together.
Keep the bundle private. It contains the original conversation bytes.

To reproduce a prefix before an already observed spawn, set `CALL_ID` and
choose another new `BUNDLE` directory. Run:

```sh
python3 tools/freeze-transcript.py create \
  --source "$SOURCE" --expected-session-id "$SESSION" \
  --expected-cwd "$CWD" --out "$BUNDLE" --before-call-id "$CALL_ID"
```

This is a retrospective reproduction. It does not prove which input earlier
reviewers used. The two capture modes are mutually exclusive.

## Capture and verification contract

`--at-eof` captures the opened regular source's first fstat size, N, and reads
only N bytes from that descriptor. Later appends are excluded. The source must
be append-only. An incomplete final line fails rather than being dropped.
No future spawn ID is needed.

`--before-call-id` searches all N captured bytes for exactly one outer
`response_item` with a `function_call` payload and the exact `call_id`.
Its name must be `spawn_agent`. Its arguments must be a JSON object encoded
as a string, with a nonempty string `task_name`. The target event is excluded.
Quoted output is not an event. Missing, duplicate, or unsupported targets fail
without a whole-file fallback.

Every captured JSONL line must be a strict UTF-8 JSON object. Duplicate keys
and non-JSON constants fail. Exactly one `session_meta` must occur first.
Its ID and normalized absolute cwd must match the required arguments.
Bytes are preserved without reformatting.

The new directory has mode 0700. `transcript.jsonl` and `snapshot.json` have
mode 0600. Existing directories, files, symlinks, and source aliases fail.
The saved prefix is reread and checked before the manifest is published last
through a no-clobber hard link. A filesystem without hard-link support fails.
There is no overwrite rename fallback, force option, or automatic cleanup.
A failed save leaves an incomplete directory. Retry with a new name.

`snapshot.json` uses schema 1 and format `codex-jsonl-observed-v1`. It records:

- The input and resolved source paths, captured size, and UTC capture time.
- The session ID, cwd, and capture mode.
- The exclusive byte offset and one-based exclusive line number.
- For a spawn cutoff, its call ID, name, task name, and original event-line SHA256 including LF.
- The fixed artifact filename, line count, byte count, and SHA256.

EOF has exclusive byte offset N and line number equal to the line count plus
one. A spawn cutoff records the target line number and its starting byte offset.
The event hash is not a hash of the full source.

Successful stdout is JSON metadata, paths, and hashes. Errors contain fixed
reason codes without source fragments or exception representations. Exit 2
means invalid input, schema, selector, or existing output. Exit 1 means I/O
or saved-artifact verification failure. Only exit 0 after final verification means success.

`verify` reads the bundle's two regular files and never rereads the source.
It rejects symlinks, missing files, unknown schema, and mismatches in the
expected manifest hash, artifact hash, bytes, lines, session, or cutoff.
It validates recorded spawn metadata but cannot reconstruct the excluded
event. Supply the PM's expected hash independently of the bundle.

This is local byte verification. It does not prove that a reviewer read the
artifact, attest to source authenticity or model identity, or protect against
an arbitrary external writer, in-place source rewrites, or power loss.

## Run the synthetic regression suite

```sh
python3 -m unittest discover -s tools/tests -p test_freeze_transcript.py -v
```

Fixtures are generated in temporary directories. The append-boundary test
injects an append immediately after fstat, without a sleep or timing race.
`FREEZE_SCRIPT` can select an isolated copy for mutation checks.
