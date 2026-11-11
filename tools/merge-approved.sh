#!/usr/bin/env bash
set -euo pipefail

fail() { printf 'error: %s\n' "$*" >&2; exit 1; }
usage() { printf 'usage: %s <owner/repo> <pr> <approved-head-sha> [--subject <subject>] [--dry-run]\n' "$0" >&2; exit 2; }
[[ $# -ge 3 ]] || usage
repo=$1 pr=$2 approved_sha=$3; shift 3
subject=''; dry_run=false
while (($#)); do
  case $1 in
    --subject) (($# >= 2)) || usage; subject=$2; shift 2 ;;
    --dry-run) dry_run=true; shift ;;
    *) usage ;;
  esac
done
[[ $repo =~ ^[^/]+/[^/]+$ ]] || fail 'repository must be <owner/repo>'
[[ $pr =~ ^[0-9]+$ ]] || fail 'pull request must be an integer'
[[ $approved_sha =~ ^[[:xdigit:]]{40}$ ]] || fail 'approved head must be a full commit SHA'
for tool in gh jq git; do command -v "$tool" >/dev/null || fail "$tool is required"; done

if ! pr_data=$(gh pr view "$pr" --repo "$repo" --json state,headRefOid,body,commits </dev/null); then fail 'could not read pull request'; fi
state=$(jq -r '.state // empty' <<<"$pr_data")
head=$(jq -r '.headRefOid // empty' <<<"$pr_data")
[[ $state == OPEN ]] || fail "pull request is not open (state: ${state:-unknown})"
[[ $head == "$approved_sha" ]] || fail "head SHA mismatch (expected $approved_sha, found ${head:-unknown})"

if ! checks=$(gh pr checks "$pr" --repo "$repo" --json bucket </dev/null); then fail 'could not read pull request checks'; fi
if ! jq -e 'type == "array" and length > 0 and all(.[]; ((.bucket // "") | ascii_downcase) as $s | ($s == "pass" or $s == "skipping"))' <<<"$checks" >/dev/null; then
  fail 'checks are empty, pending, failed, or in an unsupported state'
fi
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
[[ $subject =~ ^[a-zA-Z]+\([a-zA-Z0-9._/-]+\):[[:space:]]+[^[:space:]].*$ ]] || fail 'subject must be a scoped English Conventional Commit'
subject="$subject (#$pr)"
body=$(jq -r '.body // empty' <<<"$pr_data" | grep -Eo 'Refs #[0-9]+' | head -n 1 || true)
[[ -n $body ]] || body="Refs #$pr"
if $dry_run; then
  printf 'gh pr merge %q --repo %q --squash --match-head-commit %q --subject %q --body %q\n' "$pr" "$repo" "$approved_sha" "$subject" "$body"
  exit 0
fi
gh pr merge "$pr" --repo "$repo" --squash --match-head-commit "$approved_sha" --subject "$subject" --body "$body" </dev/null || fail 'GitHub rejected the merge'
