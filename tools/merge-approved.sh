#!/usr/bin/env bash
set -euo pipefail

fail() { printf 'error: %s\n' "$*" >&2; exit 1; }
usage() { printf 'usage: %s <owner/repo> <pr> <approved-head-sha> [--subject <subject>] [--dry-run] [--wait] [--close <issue>]... [--comment <issue>=<body>]...\n' "$0" >&2; exit 2; }
[[ $# -ge 3 ]] || usage
repo=$1 pr=$2 approved_sha=$3; shift 3
subject=''; dry_run=false; wait_for_checks=false; close_issues=(); comments=()
while (($#)); do
  case $1 in
    --subject) (($# >= 2)) || usage; subject=$2; shift 2 ;;
    --dry-run) dry_run=true; shift ;;
    --wait) wait_for_checks=true; shift ;;
    --close) (($# >= 2)) || usage; [[ $2 =~ ^[0-9]+$ ]] || usage; close_issues+=("$2"); shift 2 ;;
    --comment) (($# >= 2)) || usage; [[ $2 =~ ^[0-9]+=.+$ ]] || usage; comments+=("$2"); shift 2 ;;
    *) usage ;;
  esac
done
[[ $repo =~ ^[^/]+/[^/]+$ ]] || fail 'repository must be <owner/repo>'
[[ $pr =~ ^[0-9]+$ ]] || fail 'pull request must be an integer'
[[ $approved_sha =~ ^[[:xdigit:]]{40}$ ]] || fail 'approved head must be a full commit SHA'
if $dry_run && [[ -z $subject ]]; then fail '--dry-run requires --subject to avoid fetching the pull request head'; fi
for tool in gh jq git sleep; do command -v "$tool" >/dev/null || fail "$tool is required"; done

if ! pr_data=$(gh pr view "$pr" --repo "$repo" --json state,headRefOid,body,commits </dev/null); then fail 'could not read pull request'; fi
state=$(jq -r '.state // empty' <<<"$pr_data")
head=$(jq -r '.headRefOid // empty' <<<"$pr_data")
[[ $state == OPEN ]] || fail "pull request is not open (state: ${state:-unknown})"
[[ $head == "$approved_sha" ]] || fail "head SHA mismatch (expected $approved_sha, found ${head:-unknown})"

ready_events=$(gh api "repos/$repo/issues/$pr/timeline" --paginate --slurp </dev/null) || fail 'could not read pull request ready timeline'
ready_at=$(jq -r 'flatten | [.[] | select(.event == "ready_for_review") | .created_at] | last // empty' <<<"$ready_events")
[[ -n $ready_at ]] || fail 'pull request has no ready_for_review event'

wait_seconds=${MERGE_APPROVED_WAIT_SECONDS:-300}
[[ $wait_seconds =~ ^[0-9]+$ ]] || fail 'MERGE_APPROVED_WAIT_SECONDS must be an integer'
deadline=$SECONDS
if $wait_for_checks; then deadline=$((SECONDS + wait_seconds)); fi
while :; do
  if ! checks=$(gh pr checks "$pr" --repo "$repo" --json bucket </dev/null); then fail 'could not read pull request checks'; fi
  checks_ok=false
  if jq -e 'type == "array" and length > 0 and all(.[]; ((.bucket // "") | ascii_downcase) as $s | ($s == "pass" or $s == "skipping"))' <<<"$checks" >/dev/null; then checks_ok=true; fi
  if jq -e 'type == "array" and any(.[]; ((.bucket // "") | ascii_downcase) == "fail")' <<<"$checks" >/dev/null; then fail 'a pull request check failed'; fi
  check_runs=$(gh api "repos/$repo/commits/$approved_sha/check-runs" --paginate --slurp </dev/null | jq '{check_runs: [.[] | .check_runs[]]}') || fail 'could not read checks for approved head'
  missing=$(jq -r --arg ready "$ready_at" '
    def named($name): [.check_runs[] | select(.name == $name and .started_at >= $ready)] | sort_by(.id) | if length == 0 then [] else [last] end;
    (named("CodeRabbit")) as $cr | (named("unresolved-review-threads")) as $threads |
    [if ($cr | length) == 0 then "CodeRabbit is missing" elif any($cr[]; ((.output.title // "") + " " + (.output.summary // "")) | test("rate[- ]limit"; "i")) then "CodeRabbit did not review this PR because it was rate limited; after capacity returns, comment @coderabbitai review on the PR to rerun the review" elif any($cr[]; .status != "completed") then "CodeRabbit is pending" elif any($cr[]; .conclusion == "neutral") then "CodeRabbit returned neutral without a verified completed review" elif any($cr[]; .conclusion != "success") then "CodeRabbit did not succeed" else empty end,
     if ($threads | length) == 0 then "unresolved-review-threads is missing" elif any($threads[]; .status != "completed") then "unresolved-review-threads is pending" elif any($threads[]; .conclusion != "success") then "unresolved-review-threads did not succeed" else empty end] | .[]' <<<"$check_runs") || fail 'could not evaluate required checks'
  if grep -Eq '^CodeRabbit (did not review|returned neutral|was skipped|did not succeed)' <<<"$missing"; then
    fail "required checks blocked: $(tr '\n' ';' <<<"$missing" | sed 's/;$//')"
  fi
  if $checks_ok && [[ -z $missing ]]; then break; fi
  [[ -n $missing ]] || missing='pull request checks are empty or pending'
  if ! $wait_for_checks || (( SECONDS >= deadline )); then fail "required checks blocked: $(tr '\n' ';' <<<"$missing" | sed 's/;$//')"; fi
  remaining=$((deadline - SECONDS))
  if (( remaining > 5 )); then remaining=5; fi
  sleep "$remaining"
done
if ! "$(dirname "$0")/check-review-threads.sh" "$repo" "$pr" </dev/null; then fail 'review-thread check did not pass'; fi

if [[ -z $subject ]]; then
  commit_count=$(jq '.commits | length' <<<"$pr_data")
  [[ $commit_count == 1 ]] || fail 'multiple commits require --subject'
  if ! git fetch --quiet origin "pull/$pr/head" </dev/null; then fail 'could not fetch pull request head'; fi
  subject=$(git log -1 --format=%s FETCH_HEAD </dev/null) || fail 'could not read commit subject'
fi
if ! changed_files=$(gh pr diff "$pr" --repo "$repo" --name-only </dev/null); then fail 'could not read pull request diff'; fi
if grep -E '(^|/)HAND_OFF([^/]*$)|(^|/)\.audit(/|$)' <<<"$changed_files" >/dev/null; then
  fail 'diff contains HAND_OFF or .audit files'
fi
[[ $subject =~ ^[a-z]+\([a-zA-Z0-9._/-]+\):[[:space:]]+[^[:space:]].*$ ]] || fail 'subject must be a scoped English Conventional Commit'
subject="$subject (#$pr)"
pr_body=$(jq -r '.body // empty' <<<"$pr_data")
if grep -Eq '^(build|ci|feat|fix|docs|style|refactor|perf|test|revert|chore):' <<<"$pr_body"; then
  fail 'PR body contains a merge body line counted by bump-version.yml'
fi
body=$(grep -Eo 'Refs #[0-9]+' <<<"$pr_body" | head -n 1 || true)
[[ -n $body ]] || body="Refs #$pr"
if $dry_run; then
  printf 'gh pr merge %q --repo %q --squash --match-head-commit %q --subject %q --body %q\n' "$pr" "$repo" "$approved_sha" "$subject" "$body"
  exit 0
fi
gh pr merge "$pr" --repo "$repo" --squash --match-head-commit "$approved_sha" --subject "$subject" --body "$body" </dev/null || fail 'GitHub rejected the merge'
if ! merged_data=$(gh pr view "$pr" --repo "$repo" --json state </dev/null); then fail 'merge command returned, but could not verify pull request state'; fi
[[ $(jq -r '.state // empty' <<<"$merged_data") == MERGED ]] || fail 'pull request did not reach MERGED state; issue actions were not run'
for issue in "${close_issues[@]}"; do gh issue close "$issue" --repo "$repo" </dev/null || fail "pull request merged, but could not close issue $issue"; done
for comment in "${comments[@]}"; do
  issue=${comment%%=*}; message=${comment#*=}
  gh issue comment "$issue" --repo "$repo" --body "$message" </dev/null || fail "pull request merged, but could not comment on issue $issue"
done
