#!/usr/bin/env bash
# Select the commit that PR CI checks: a merge of the current base branch tip
# and the current PR head. An event can carry an old base SHA, and GitHub can
# keep refs/pull/N/merge on an old base for many minutes after the base moves,
# so both are compared with the current PR instead of the event. When GitHub's
# merge stays old, this builds the merge here and bundles it, so that every job
# checks the same commit.
#
# Run inside a clone whose origin is the repository. Inputs (environment):
# PR_NUMBER, EVENT_HEAD_SHA, GH_TOKEN, GITHUB_REPOSITORY, GITHUB_OUTPUT,
# RUNNER_TEMP, and optionally PR_TEST_MERGE_ATTEMPTS and PR_TEST_MERGE_DELAY.
# Outputs: checkout_ref, test_merge ("sha base head"), base_sha, head_sha,
# and bundle_name when the merge was built here.
set -euo pipefail

attempts=${PR_TEST_MERGE_ATTEMPTS:-6}
delay=${PR_TEST_MERGE_DELAY:-5}
sha40='^[0-9a-f]{40}$'
if [[ ! "$PR_NUMBER" =~ ^[1-9][0-9]*$ || ! "$EVENT_HEAD_SHA" =~ $sha40 ]]; then
  echo '::error::Missing or invalid PR number or head SHA in the event'
  exit 1
fi

for (( attempt = 1; attempt <= attempts; attempt++ )); do
  if (( attempt > 1 )); then sleep "$delay"; fi
  # Reading the PR also asks GitHub to compute refs/pull/N/merge again.
  current=$(gh api "repos/$GITHUB_REPOSITORY/pulls/$PR_NUMBER" \
    --jq '[.state, .base.ref, .head.sha] | @tsv')
  IFS=$'\t' read -r state base_ref head_sha <<< "$current"
  if [[ "$state" != open || "$head_sha" != "$EVENT_HEAD_SHA" ]]; then
    printf '::error::This event is for head %s, but the PR is %s with head %s\n' \
      "$EVENT_HEAD_SHA" "$state" "$head_sha"
    exit 1
  fi
  # The API's base SHA can lag a push to the base branch, so read the tip.
  tip=$(git ls-remote --exit-code origin "refs/heads/$base_ref")
  IFS=$'\t' read -r base_sha tip_ref <<< "$tip"
  if [[ ! "$base_sha" =~ $sha40 || "$tip_ref" != "refs/heads/$base_ref" ]]; then
    printf '::error::Cannot read the tip of base branch %s\n' "$base_ref"
    exit 1
  fi
  expected="$base_sha $head_sha"
  parents=
  if git fetch --quiet --no-tags origin "+refs/pull/$PR_NUMBER/merge:refs/pr-ci/candidate"; then
    candidate=$(git rev-parse --verify 'refs/pr-ci/candidate^{commit}')
    parents=$(git show -s --format=%P "$candidate")
  fi
  printf 'Attempt %s: current parents (%s) %s, GitHub merge parents %s\n' \
    "$attempt" "$base_ref" "$expected" "${parents:-none}"
  if [[ "$parents" == "$expected" ]]; then
    printf 'Using GitHub test merge %s\n' "$candidate"
    printf 'checkout_ref=%s\ntest_merge=%s %s\nbase_sha=%s\nhead_sha=%s\n' \
      "$candidate" "$candidate" "$expected" "$base_sha" "$head_sha" >> "$GITHUB_OUTPUT"
    exit 0
  fi
done

# Build the merge from the parents read in the last attempt, so that every job
# checks the same commit even if a branch moves again.
git fetch --quiet --no-tags origin "$base_sha" "$head_sha"
if ! tree=$(git merge-tree --write-tree "$base_sha" "$head_sha"); then
  printf '::error::Cannot create a conflict-free test merge of %s\n' "$expected"
  exit 1
fi
merge=$(GIT_AUTHOR_NAME='PR CI' GIT_AUTHOR_EMAIL='noreply@github.com' \
  GIT_COMMITTER_NAME='PR CI' GIT_COMMITTER_EMAIL='noreply@github.com' \
  git commit-tree "$tree" -p "$base_sha" -p "$head_sha" -m "Test merge of $head_sha into $base_ref")
git update-ref refs/pr-ci/merge "$merge"
git bundle create --quiet "$RUNNER_TEMP/pr-test-merge.bundle" refs/pr-ci/merge "^$base_sha"
printf 'GitHub merge stayed outdated; built test merge %s\n' "$merge"
printf 'checkout_ref=%s\ntest_merge=%s %s\nbase_sha=%s\nhead_sha=%s\nbundle_name=pr-test-merge-%s\n' \
  "$base_sha" "$merge" "$expected" "$base_sha" "$head_sha" "${GITHUB_RUN_ATTEMPT:-1}" >> "$GITHUB_OUTPUT"
