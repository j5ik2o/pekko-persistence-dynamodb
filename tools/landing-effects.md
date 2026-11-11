# Landing workflow evidence

`landing-effects.py` saves workflow definitions and unknown evidence for a destination branch push. It reports strings that suggest publishing, tags, releases, or deployment. A candidate string is never proof that a command executed. The output contains no approval, safety, or mergeability decision.

## Collect evidence

Use the existing PyYAML dependency in `docs/requirements.txt`:

```sh
python3 -m venv target/docs-venv
target/docs-venv/bin/pip install -r docs/requirements.txt
target/docs-venv/bin/python tools/landing-effects.py collect \
  --repo j5ik2o/pekko-persistence-dynamodb --branch main --pr 923 \
  --out target/landing-ppd
target/docs-venv/bin/python tools/landing-effects.py analyze \
  target/landing-ppd/snapshot.json > target/landing-ppd/reanalysis.json
target/docs-venv/bin/python -m unittest discover -s tools/tests -v
```

The output directory must be new, under `target/` or outside this checkout. Keep raw data there and do not commit it. `gh` must already have read access. The collector invokes only `gh api --method GET` with argument lists. It never executes workflow text, reads secrets, snapshots the environment, or writes to GitHub.

Without `--pr`, the candidate defaults to the observed destination branch commit. `--head` accepts a full explicit commit SHA, including a merged commit. It is mutually exclusive with `--pr`. PR mode saves separate inventories at the current destination branch SHA and candidate PR head SHA. The snapshot also records the PR's reported base SHA. It does not reconstruct the prospective squash tree. `prospective_tree_verified` is always false. Changed workflow inventories produce an additional unknown.

## Saved data

`snapshot.json` records the repository, destination branch, observed base and head SHAs, PR base SHA, collection interval, workflow inventories, and API source records. Each workflow records its path, Git blob SHA, source revision, current API workflow state, raw file reference, raw SHA256, and blob API source index. Workflow state is an observation at collection time, including when the definition comes from an older revision.

Numbered `.stdout` and `.stderr` files retain each API response. Source records contain the exact GET arguments, API path, actual process exit code, response SHA256, and timestamps. Tree inventories use immutable revisions. Destination branch, PR base/head/state, and API workflow paths/states are read before and after collection. Differences remain visible as `observation_drift`. Matching reads cannot prove an atomic snapshot or detect a change that reverted between reads. Permission responses are observations, never evidence of executed effects.

`analysis.json` records each workflow's direct push trigger as `excluded`, `potential`, or `unknown`, with the raw `on` configuration and JSON pointer. Definition-level trigger analysis remains separate from the observed enabled/disabled state. Disabling Bump therefore cannot remove an active Publish candidate.

The parser uses PyYAML `BaseLoader` to preserve an unquoted `on` key. Duplicate keys, cycles, shared container aliases, malformed YAML, and unsupported job/step shapes produce unknown evidence. Shared aliases are not expanded, so nested alias fanout cannot multiply analysis work. A missing PyYAML dependency produces an explicit diagnostic. There is no regex YAML fallback.

## Analysis limits

Direct push analysis supports unfiltered push, literal branch equality or inequality, and tags-only exclusion for a branch push. Branch globs, negation, expressions, paths, and other filters remain unknown. Tag patterns do not restrict a branch push when a literal branch filter is present. No GitHub glob matching is approximated with `fnmatch`.

`effect_candidates` are case-insensitive string matches for `publish`, `ci-release`, `sonatype`, `release`, `git tag`, `gh release`, and `deploy`, at JSON pointer locations. They can match names or comments encoded as strings and can miss arbitrary commands. Each candidate points to the original string and unevaluated conditions. Job and step `if`, dependencies, environment, and matrix expressions remain unevaluated. The raw YAML is the complete condition evidence.

Every action reference and every `run` command remains unknown. Local actions and scripts are opaque. All reusable workflows, including a local self-cycle, are explicitly unexpanded. No recursion or external repository inspection occurs. `workflow_run` configuration is saved separately as an indirect candidate, including raw upstream names, branches, and types. It does not establish an actual push-triggered execution chain.

API-managed workflows missing from the repository inventory remain unknown. A contents API 404 for `dynamic/dependabot` is retained with its real nonzero exit. Missing, failed, truncated, or unsupported paginated collection remains visible. The API workflow list is limited to 100 entries; a larger list is an explicit collection failure.

Malformed workflow-state responses, including a missing or non-list `workflows` field, remain unknown and are treated as an empty state inventory for comparison. Collection records the failure and exits 1.

Exit 0 means the requested evidence was saved and parsed. Unknowns normally remain present at exit 0. It never means no effects or authorization. Exit 1 means collection, dependency, input-file, or parse failure. Exit 2 means invalid CLI arguments. An existing output directory is an operational failure and is not overwritten.

## Regression observations

The fixtures preserve two contrasting inventories. PPD has an active main-push Publish with `sbt +publishSigned` even when Bump is disabled. Example has only a CI workflow, with opaque external actions and a local script. Neither inventory proves absence of external effects. The tests also cover literal triggers, unsupported filters, managed-workflow 404, missing collection, drift, reusable cycles, and malformed YAML. Live evidence belongs in local `target/` snapshots, with actual sources and observation times, rather than frozen claims about current repository settings.

When checking temporary mutations, use a fresh `PYTHONPYCACHEPREFIX` for each run. Timestamp-based bytecode caching can otherwise load an earlier source version after its source has been restored.
