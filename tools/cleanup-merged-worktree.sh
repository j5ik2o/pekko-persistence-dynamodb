#!/usr/bin/env bash
set -euo pipefail

fail() { printf 'error: %s\n' "$*" >&2; exit 1; }
usage() { printf 'usage: %s <owner/repo> <worktree-path> [--dry-run]\n' "$0" >&2; exit 2; }
[[ $# -ge 2 && $# -le 3 ]] || usage
repo=$1
worktree_arg=$2
worktree=$worktree_arg
dry_run=false
if [[ $# == 3 ]]; then
  [[ $3 == --dry-run ]] || usage
  dry_run=true
fi
[[ $repo =~ ^[^/]+/[^/]+$ ]] || fail 'repository must be <owner/repo>'
[[ -d $worktree ]] || fail "worktree path does not exist: $worktree"
worktree=$(cd "$worktree" && pwd -P) || fail 'could not resolve worktree path'
for tool in gh git jq pgrep; do command -v "$tool" >/dev/null || fail "$tool is required"; done

if ! git -C "$worktree" rev-parse --show-toplevel </dev/null >/dev/null; then fail 'path is not a git worktree'; fi
if ! branch=$(git -C "$worktree" symbolic-ref --quiet --short HEAD </dev/null); then fail 'worktree HEAD is detached'; fi
if ! head=$(git -C "$worktree" rev-parse HEAD </dev/null); then fail 'could not read worktree HEAD'; fi
if ! prs=$(gh pr list --repo "$repo" --head "$branch" --state all --json number,state,mergedAt,mergeCommit </dev/null); then fail 'could not list pull requests for worktree branch'; fi
if ! jq -e 'type == "array" and length == 1' <<<"$prs" >/dev/null; then fail 'expected exactly one pull request for worktree branch'; fi
pr=$(jq -r '.[0].number // empty' <<<"$prs")
state=$(jq -r '.[0].state // empty' <<<"$prs")
merged_at=$(jq -r '.[0].mergedAt // empty' <<<"$prs")
merge_sha=$(jq -r '.[0].mergeCommit.oid // empty' <<<"$prs")
[[ $pr =~ ^[0-9]+$ ]] || fail 'pull request number is missing'
[[ $state == MERGED && -n $merged_at ]] || fail "pull request is not merged (state: ${state:-unknown})"
[[ $merge_sha =~ ^[[:xdigit:]]{40}$ ]] || fail 'pull request merge commit is missing'

if ! git -C "$worktree" -c fetch.prune=false fetch origin "+pull/$pr/head:refs/pm/$pr" </dev/null; then fail 'could not fetch pull request head'; fi
if ! git -C "$worktree" fetch origin main:refs/remotes/origin/main </dev/null; then fail 'could not fetch origin/main'; fi
if ! git -C "$worktree" merge-base --is-ancestor "$head" "refs/pm/$pr" </dev/null &&
   ! git -C "$worktree" merge-base --is-ancestor "$head" "origin/main" </dev/null; then
  fail 'worktree HEAD is not in the pull request or origin/main history'
fi

if ! runs=$(gh run list --repo "$repo" --commit "$merge_sha" --workflow CI --json status,conclusion,headSha </dev/null); then fail 'could not read CI runs for merge commit'; fi
if ! jq -e --arg sha "$merge_sha" 'type == "array" and length > 0 and .[0].headSha == $sha and .[0].status == "completed" and .[0].conclusion == "success"' <<<"$runs" >/dev/null; then
  fail 'CI for pull request merge commit is not completed successfully'
fi

if ! status=$(git -C "$worktree" status --short --untracked-files=all </dev/null); then fail 'could not read worktree status'; fi
[[ -z $status ]] || fail 'worktree has modified or untracked files'
if matching_pids=$(pgrep -f "$worktree_arg" || true); then
  active_pids=$(printf '%s\n' "$matching_pids" | awk -v self="$$" -v parent="$PPID" '$0 != self && $0 != parent && NF')
else
  active_pids=''
fi
if [[ -z $active_pids && $worktree_arg != "$worktree" ]]; then
  if matching_pids=$(pgrep -f "$worktree" || true); then
    active_pids=$(printf '%s\n' "$matching_pids" | awk -v self="$$" -v parent="$PPID" '$0 != self && $0 != parent && NF')
  fi
fi
[[ -z $active_pids ]] || fail "worktree path is in use by process(es): $active_pids"

common_dir=$(git -C "$worktree" rev-parse --path-format=absolute --git-common-dir </dev/null) || fail 'could not locate the main worktree'
main_root=$(dirname "$common_dir")
if $dry_run; then
  printf 'git -C %q worktree remove %q\n' "$main_root" "$worktree"
  exit 0
fi
git -C "$main_root" worktree remove "$worktree" </dev/null || fail 'git could not remove the worktree'
