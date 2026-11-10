# Manual canary evidence matcher

`ppd-canary.py` is an offline decision aid for #914. Before choosing fan-out, a PM can match an explicit target configuration to one small completed task. The stdlib CLI reads only the supplied JSON and the artifact and verification log paths in the adopted record. It never executes commands, calls models, discovers runs, reads transcripts, changes configuration, or dispatches work. It has no registry, server, or automatic T3 gate and is not integrated into CI.

The export is trusted input. The matcher checks internal consistency and local bytes. It does not attest to host truth, remote execution, or actual model identity. A passing source canary does not guarantee future quality, performance, or parallel capacity. `unknown` does not mean model failure or a ban.

## Export schema 1

The following is a synthetic fixture, not observed remote execution. SHA256 placeholders must be replaced with the hashes of the explicit local files. All timestamps need a timezone. JSON object keys must be unique.

```json
{
  "schema": 1,
  "target": {"requested_config": {
    "providerInstance": "fixture-provider",
    "modelSelector": "fixture-model",
    "options": [{"id": "reasoning", "value": "high"}],
    "runtimeMode": "fixture",
    "environment": "fixture-local",
    "project": "fixture-project",
    "repo": "fixture-repo"
  }},
  "records": [{
    "identity": {
      "task": "fixture-task", "thread": "fixture-thread", "run": "fixture-run",
      "attempt": 1, "worktree": "fixture-tree", "head": "fixture-head",
      "requestedAt": "2026-11-11T00:00:00Z",
      "startedAt": "2026-11-11T00:01:00Z",
      "endedAt": "2026-11-11T00:02:00Z",
      "sources": ["synthetic-fixture"]
    },
    "requested": {"providerInstance": "fixture-provider", "modelSelector": "fixture-model",
      "options": [{"id": "reasoning", "value": "high"}], "runtimeMode": "fixture",
      "environment": "fixture-local", "project": "fixture-project", "repo": "fixture-repo"},
    "observed": {
      "host_config": {"providerInstance": "fixture-provider", "modelSelector": "fixture-model",
        "options": [{"id": "reasoning", "value": "high"}], "runtimeMode": "fixture",
        "environment": "fixture-local", "project": "fixture-project", "repo": "fixture-repo"},
      "executedModel": null,
      "terminal": {"status": "completed", "pendingQuestion": false,
        "descendantsComplete": true, "descendants": []}
    },
    "reported": {"text": "The task passed"},
    "artifact": {"path": "report.txt", "sha256": "REPLACE_WITH_SHA256",
      "head": "fixture-head", "run": "fixture-run", "attempt": 1},
    "verification": {"kind": "observed", "exitCode": 0,
      "log": {"path": "check.log", "sha256": "REPLACE_WITH_SHA256",
        "head": "fixture-head", "run": "fixture-run", "attempt": 1}}
  }]
}
```

`target.requested_config`, `records[].requested`, and `observed.host_config` share one configuration shape. Every shown key is required. Optional string keys `runtime`, `version`, and `revision` participate when explicitly supplied. Other configuration keys are rejected. Unknown values are never defaulted. `modelSelector` is a host-recorded setting, not proof of executed model identity. Missing, empty, `auto`, `default`, `unknown`, `ambiguous`, and `*` selectors yield `unknown`. Exporters must resolve ambiguous aliases before supplying a concrete selector.

Options are unique `id` and `value` pairs. Values are typed JSON strings, booleans, integers, or finite floats. Nulls, objects, and arrays are rejected. Options sort by `id`; object keys sort recursively. Canonical JSON uses UTF-8, compact separators, and unescaped Unicode. SHA256 of those bytes is the configuration fingerprint. Boolean `true`, string `"true"`, integer `1`, and float `1.0` remain different. Identity, task, thread, run, attempt, worktree, head, timestamps, and sources never enter the fingerprint. Requested and observed configurations must both exactly match the target, including explicit optional fields.

## Latest record and evidence rules

The exporter supplies the complete attempt history relevant to the target. Records order lexicographically by `(requestedAt, startedAt, attempt)` after parsing timestamps as instants. `attempt` is a positive integer. Any duplicate ordering key is ambiguous. End time never selects the winner. Requested time must precede or equal start time. Start and end must not exceed `--as-of`; end must follow or equal start. Missing candidate ordering metadata yields `unknown`. Incomplete requested configuration anywhere in the export also yields `unknown`, since the matcher cannot exclude an attempt with unknown configuration.

Only the latest exact requested configuration is assessed. Its observed host configuration must match too. A newer failed, interrupted, pending, or incomplete attempt prevents an older success from passing. A different provider or an old configuration supplies no matching canary.

The parent and every descendant must be explicitly exported. `descendantsComplete` asserts that the flattened `descendants` array contains all descendants at every depth, not merely direct children. Each descendant has a unique nonempty `id`, `status`, and `pendingQuestion`. Every node must have `pendingQuestion: false`. An empty descendant array plus `descendantsComplete: true` explicitly declares no descendants. This assertion is trusted metadata; the matcher cannot discover omitted children. Live or unexported children yield `unknown` even if the parent is completed.

`artifact` and `verification.log` require explicit paths, lowercase SHA256, and exact `head`, `run`, and `attempt` bindings to the adopted identity. Relative paths resolve from the JSON file's directory. Absolute paths are allowed. The matcher reads bytes and compares hashes without printing file contents. Supply only intended evidence paths. No glob or environment snapshot is used. Read-only work can use an artifact report and the existing head without a new commit.

Verification requires `kind: "observed"` and an integer `exitCode` from an actual check. A boolean is not an exit code. `reported` is ignored. A success sentence, timestamp, or parent status alone cannot pass. Metadata is an exporter assertion, not independent verification that a log came from that command.

## CLI and results

```sh
python3 tools/ppd-canary.py target/ppd-canary-acceptance/doc-example/export.json \
  --as-of 2026-11-11T01:00:00Z --max-age-seconds 3600
python3 -m unittest discover -s tools/tests -p test_ppd_canary.py -v
```

The command above assumes the synthetic example is saved at that path with its report, log, and hashes supplied. It assesses the fixture at its explicit historical time.

Both time arguments are mandatory on every invocation. There is no persisted expiration or implicit wall-clock trust. Age is `as-of - endedAt`. The maximum age is a nonnegative integer number of seconds, with the boundary included.

JSON output includes `inferred_assessment`, `reason_codes`, `adopted_record`, `config_fingerprint`, `identity_scope`, and `unobserved_items`. `adopted_record` is the selected identity, or null when selection was impossible. Do not put secrets in export metadata because this identity is printed.

| Assessment | Meaning |
|:--|:--|
| `pass` | Latest exact match has complete terminal success, fresh evidence, valid artifact and log bytes, and observed verification exit 0. |
| `needs-canary` | Known target has no exact record, evidence expired, or latest fully evidenced attempt failed, was interrupted, or had a nonzero verification exit. No model cause is inferred. |
| `unknown` | Configuration, ordering, descendants, questions, evidence hashes, verification metadata, or requested/observed consistency is missing or ambiguous. |

`identity_scope` is always `host_configuration`. `actual_model` remains `unknown`. `executedModel` preserves only the export's observation, including null, and never falls back to `modelSelector`. Even a nonnull exporter claim does not establish model identity. `unobserved_items` always includes `actual_model_identity`.

Reason codes identify the failed predicate, such as `no_exact_record`, `expired`, `latest_attempt_failed`, `ambiguous_order`, `incomplete_descendant_export`, `nonterminal_execution`, `missing_real_exit`, `evidence_binding_mismatch`, and `evidence_hash_mismatch`. The tool returns the first missing predicate rather than claiming exhaustive diagnosis. Assessment JSON exits 0 for all three outcomes. Invalid JSON or time arguments and unreadable exports exit 2 with a content-free error. Callers must read the assessment; exit 0 is not a gate.

The tests create synthetic fixtures and actual temporary artifact/log bytes. These fixtures do not represent observed model runs. Local acceptance may separately use explicitly supplied host metadata and an actual local check log, labeled as such. This still checks trusted metadata consistency, not remote execution or actual model identity.
