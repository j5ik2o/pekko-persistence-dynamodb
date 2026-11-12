# PPD evidence CLI

## Merge an approved pull request

Run `tools/merge-approved.sh <owner/repo> <pr> <approved-head-sha>` only after reviewing that exact head. The script checks the PR state and head, requires all visible checks to pass or skip, and reads check runs for that exact head SHA. Every required check must have a run started in the same second as or after the PR's latest `ready_for_review` event; for each required check name, only the highest-ID qualifying run counts. `CodeRabbit` must complete with `success`. A rate-limited run can use `--substitute-review <comment-id>` with the conditions below. Other neutral or failed runs block the merge. `unresolved-review-threads` must succeed. The script also runs `check-review-threads.sh`, rejects `HAND_OFF` and `.audit/` files in the PR diff, and requires a lowercase scoped English Conventional Commit squash subject.

Use `--substitute-review <comment-id>` only when CodeRabbit's completed check for the approved head has `output.title` set to `Review rate limited`. The comment must belong to this PR, come from a non-bot repository collaborator with `admin`, `maintain`, or `write` permission, and be newer than the rate-limited check run. Its body must contain exactly one marker line whose SHA matches the approved head and whose result is `pass`. For example:

```text
independent-review: head=0123456789abcdef0123456789abcdef01234567 result=pass
```

The marker records an independent review verdict. It does not replace CodeRabbit when CodeRabbit succeeded, and it cannot override a pending or failed CodeRabbit check. When CodeRabbit is rate limited, wait for capacity and comment `@coderabbitai review` to rerun it, or record the independent review with the marker above and pass its issue-comment ID with `--substitute-review`.

Use `--wait` to poll pending or missing checks until they complete. The default timeout is 300 seconds; set `MERGE_APPROVED_WAIT_SECONDS` to change it. Failed, neutral, and rate-limited checks stop immediately, including when `--wait` is set. Use repeated `--close <issue>` and `--comment <issue>=<body>` options to record the merge and close related issues. The script runs those actions only after `gh pr view --json state` reports `MERGED`.

A single commit supplies its complete subject from `git log` after fetching `pull/<pr>/head`; multiple commits require `--subject "type(scope): description"`. The script appends ` (#<pr>)` to the subject. It uses the first `Refs #N` in the PR body, or defaults to `Refs #<pr>`, as the merge body. PR body lines beginning with an unscoped type counted by `bump-version.yml` are rejected. The script keeps that type list locally: `build`, `ci`, `feat`, `fix`, `docs`, `style`, `refactor`, `perf`, `test`, `revert`, and `chore`. Keep the list aligned with the workflow when either changes. Use `--dry-run` to print the exact merge command without merging. Since fetching updates local `FETCH_HEAD`, `--dry-run` requires `--subject` and never fetches. Every `gh` invocation receives `/dev/null` as stdin.

Run `tools/cleanup-merged-worktree.sh <owner/repo> <worktree-path>` to remove a clean, unused worktree whose branch has one merged pull request and a successful `CI` run for its merge commit. The script checks that the fetched `refs/pm/<N>` matches the PR's GitHub head SHA and that the worktree HEAD belongs to the pull request or `refs/pm/main` history. Fetches write `refs/pm/<N>` and `refs/pm/main`; they do not update `refs/remotes/origin/main` or other tracking refs. It checks command lines, current directories, and open files for processes using the worktree. It keeps the branch. `git worktree remove` also removes ignored files such as build output under `target/`; stashes belong to the shared repository and remain after removing a worktree. Use `--dry-run` to print the `git worktree remove` command without removing the worktree. The script never uses `--force`, and every `gh` invocation receives `/dev/null` as stdin.

```sh
tools/merge-approved.sh j5ik2o/pekko-persistence-dynamodb 123 FULL_APPROVED_HEAD_SHA --dry-run --subject "fix(storage): preserve values"
tools/merge-approved.sh j5ik2o/pekko-persistence-dynamodb 123 FULL_APPROVED_HEAD_SHA --dry-run --subject "fix(storage): preserve values" --substitute-review 456
tools/merge-approved.sh j5ik2o/pekko-persistence-dynamodb 123 FULL_APPROVED_HEAD_SHA --wait --close 966 --comment '966=Merged in #123'
```

Run `python3 tools/check-dependency-exclusion.py --project <project> --exclude <org:name> --suite '<project>/testOnly <suite>' --resolve-central` before you exclude a transitive dependency from a published module. The "Change a published dependency" section of `AGENTS.md` describes its five steps.

`ppd-evidence.py` uses Python 3.9+ and the standard library. It stores observations and generates assessments for #913 and #915. It never posts, merges, cancels, deletes, schedules or reads T3 transcripts. Supply exported snapshots only for the current PPD thread and its children.

## Collect and assess PR evidence

```sh
python3 tools/ppd-evidence.py collect --repo j5ik2o/pekko-persistence-dynamodb \
  --pr 123 --reviewed-head FULL_SHA --required-check lint \
  --required-check 'test (11, 2.13.16)' --output /tmp/pr-evidence.json
python3 tools/ppd-evidence.py pr /tmp/pr-evidence.json
python3 tools/ppd-evidence.py git --output /tmp/worktree-evidence.json
python3 -m unittest discover -s tools/tests -v
```

Replace the sample check names with the complete set the PM requires. The tool does not discover branch protection requirements. An empty required list is unknown. `collect` needs authenticated `gh`, plus `bash` and `jq` for the existing `check-review-threads.sh`. It uses GET requests and executes that repository-relative script. Each command records its actual exit code, output, source and observation time. A timeout or missing executable is unknown. The thread script's exit 1 means unresolved threads; exit 2 means collection failed.

The JSON contains `snapshot` and `report`. Keep it outside the repository. `pr` replays the stored snapshot without network access and prints a status sentence to stderr. The snapshot preserves the caller's reviewed head as a claim, rather than an independently verified review. It records the current head before and after collection. A head or PR-state change during collection produces unknown. Collection is sequential and is not an atomic GitHub snapshot.

The collector selects the highest workflow run number and attempt for each workflow ID on the current SHA, then fetches jobs for that exact attempt. A queued workflow with no jobs blocks evidence satisfaction. Old cancelled runs cannot replace a new successful run, and a new pending attempt cannot inherit an earlier success. External checks use the highest check ID per app and name; commit statuses use the highest status ID per context. All producers of a required name must succeed. Neutral and skipped checks do not satisfy a required name. Unknown, failed and pending states remain distinct in the reasons. Pagination failures are unknown, not an empty success result. Raw records retain job and step timestamps; the CLI makes no duration estimates or future timeout guarantees.

`git` reads the current directory's HEAD, porcelain status, diff against HEAD and five recent commits. It saves the diff and its SHA-256. It does not visit other worktrees. Untracked file names appear in status, but their contents are not hashed. Compare successive snapshots to investigate edits within an already dirty tree.

Exit codes are 0 for satisfied evidence, 1 for blocked evidence and 2 for unknown or failed collection. Satisfied evidence is **not merge authorization**. The PM must separately inspect the diff, review contents and branch protection. Every observation can become stale immediately. This tool adds no CI wiring or automation.

## Assess offline progress

```sh
python3 tools/ppd-evidence.py progress snapshot.json --output /tmp/progress-report.json
```

The input is an object with `observed_at` and a `tasks` array. Each task has the following fields.

| Field | Meaning |
| --- | --- |
| `task`, `source_thread`, `observed_at` | Task identifier, source and actual export time. Missing time remains null; replay time is not substituted. |
| `initial_head`, `current_head`, `git_status` | Observed worktree values. Missing or null values produce unknown. |
| `initial_git_status` | Optional baseline porcelain status, defaulting to clean. An unchanged dirty status is not new progress. |
| `initial_diff_sha256`, `current_diff_sha256` | Optional fingerprints from two `git` snapshots. A changed fingerprint counts as an artifact delta. |
| `diff_changed` | Optional observed comparison of baseline and current diff fingerprints. Omit if unavailable. |
| `events` | Exported activity records in the observation window. |

Each event has a `type`. `compaction` counts compactions. `command_execution` has `text`; only its first line is scanned for `cat`, `sed`, `tail` and `head` file reads. Paths are counted once per command. This is a heuristic, not a shell parser. A `verification` event has `kind`, `source`, `head`, `run_id`, `exit_code` and optionally `attempt` and `observed_at`. Include actual process results, not prose claims. The report retains these fields. Any observed verification execution is activity evidence, including a failing execution; it is not successful completion. Repeated execution of the same verification does not demonstrate forward progress by itself.

At least three compactions and three reads of one file, with unchanged head, clean status and no observed verification, produce `investigate_repeated_preparation`. Progress evidence alongside repetition produces `repetition_with_progress_evidence`, which still warrants human interpretation. Other complete inputs produce `no_repetition_trigger`. Neither label means healthy or complete. `updatedAt` never establishes progress. The tool diagnoses no model or runtime cause and takes no action against a worker.

Progress exits 1 for investigation candidates, 2 for unknown worktree evidence and 0 otherwise. A 0 exit only means the heuristic did not select an investigation candidate. The fixture under `tests/fixtures` preserves the three supplied historical repetition patterns with path prefixes normalized and command output removed.
