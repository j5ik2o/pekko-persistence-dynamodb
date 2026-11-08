#!/usr/bin/env bash
# Fail when a pull request still has review threads that nobody resolved.
#
# Usage: tools/check-review-threads.sh <owner/repo> <pr-number>
#
# Exit 0: no unresolved review threads.
# Exit 1: at least one unresolved review thread.
# Exit 2: the check could not run or could not read every thread.
set -euo pipefail

usage() {
  echo "usage: $0 <owner/repo> <pr-number>" >&2
}

if [[ $# -ne 2 ]]; then
  usage
  exit 2
fi

repo_slug=$1
pr_number=$2

if [[ ! "$repo_slug" =~ ^[^/]+/[^/]+$ ]]; then
  echo "error: repository must be <owner/repo>: $repo_slug" >&2
  usage
  exit 2
fi

if [[ ! "$pr_number" =~ ^[0-9]+$ ]]; then
  echo "error: pull request number must be an integer: $pr_number" >&2
  usage
  exit 2
fi

for command_name in gh jq; do
  if ! command -v "$command_name" >/dev/null 2>&1; then
    echo "error: $command_name is required but was not found on PATH" >&2
    exit 2
  fi
done

owner=${repo_slug%%/*}
name=${repo_slug##*/}

query=$(
  cat <<'GRAPHQL'
query($owner: String!, $repo: String!, $number: Int!) {
  repository(owner: $owner, name: $repo) {
    pullRequest(number: $number) {
      reviewThreads(first: 100) {
        pageInfo { hasNextPage }
        nodes {
          isResolved
          path
          line
          originalLine
          comments(first: 1) {
            nodes { author { login } body }
          }
        }
      }
    }
  }
}
GRAPHQL
)

if ! response=$(gh api graphql -f query="$query" -f owner="$owner" -f repo="$name" -F number="$pr_number" 2>&1); then
  echo "error: failed to read review threads for $repo_slug#$pr_number" >&2
  printf '%s\n' "$response" >&2
  exit 2
fi

if ! jq -e . >/dev/null 2>&1 <<<"$response"; then
  echo "error: GitHub returned invalid JSON for $repo_slug#$pr_number" >&2
  printf '%s\n' "$response" >&2
  exit 2
fi

if jq -e '(.errors // []) | length > 0' >/dev/null 2>&1 <<<"$response"; then
  echo "error: the GitHub API returned errors for $repo_slug#$pr_number" >&2
  jq -r '.errors[] | "  \(.message // .)"' <<<"$response" >&2 || true
  exit 2
fi

if ! jq -e '.data.repository.pullRequest != null' >/dev/null 2>&1 <<<"$response"; then
  echo "error: pull request $repo_slug#$pr_number was not found" >&2
  exit 2
fi

if [[ "$(jq -r '.data.repository.pullRequest.reviewThreads.pageInfo.hasNextPage' <<<"$response")" != "false" ]]; then
  echo "error: more than 100 review threads; this check cannot see the rest and will not guess" >&2
  exit 2
fi

unresolved=$(
  jq -r '
    [.data.repository.pullRequest.reviewThreads.nodes[] | select(.isResolved == false)]
    | .[]
    | [
        (.path // "unknown"),
        ((.line // .originalLine // "?") | tostring),
        (.comments.nodes[0].author.login // "unknown"),
        ((.comments.nodes[0].body // "") | gsub("\\s+"; " ") | .[0:160])
      ]
    | @tsv
  ' <<<"$response"
)

if [[ -z "$unresolved" ]]; then
  echo "no unresolved review threads"
  exit 0
fi

count=$(printf '%s\n' "$unresolved" | wc -l | tr -d ' ')
echo "unresolved review threads: $count in $repo_slug#$pr_number"
while IFS=$'\t' read -r path line author body; do
  echo "- $path:$line ($author) $body"
done <<<"$unresolved"
echo "fix: reply to each thread with the fix, a dismissal reason, or a follow-up issue, then resolve the thread."
exit 1
