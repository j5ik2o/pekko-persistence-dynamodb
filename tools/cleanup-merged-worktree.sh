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
for tool in gh git jq pgrep lsof; do command -v "$tool" >/dev/null || fail "$tool is required"; done

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
if ! pr_details=$(gh pr view "$pr" --repo "$repo" --json headRefOid </dev/null); then fail 'could not read pull request head SHA'; fi
if ! pr_head=$(jq -r '.headRefOid // empty' <<<"$pr_details"); then fail 'could not parse pull request head SHA'; fi
[[ $pr_head =~ ^[[:xdigit:]]{40}$ ]] || fail 'pull request head SHA is missing'
if ! fetched_head=$(git -C "$worktree" rev-parse "refs/pm/$pr" </dev/null); then fail 'could not read fetched pull request head'; fi
[[ $fetched_head == "$pr_head" ]] || fail 'fetched pull request head does not match GitHub head SHA'
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
escape_ere() { printf '%s' "$1" | sed 's/[][\\.^$*+?(){}|]/\\&/g'; }
active_pids=''
needles=("$worktree_arg")
[[ $worktree_arg == "$worktree" ]] || needles+=("$worktree")
for needle in "${needles[@]}"; do
  if matching_pids=$(pgrep -f "$(escape_ere "$needle")" </dev/null); then
    :
  else
    pgrep_status=$?
    [[ $pgrep_status == 1 ]] || fail 'could not inspect process command lines'
    matching_pids=''
  fi
  matching_pids=$(printf '%s\n' "$matching_pids" | awk -v self="$$" -v parent="$PPID" '$0 != self && $0 != parent && NF')
  [[ -z $matching_pids ]] || active_pids+=$'\n'$matching_pids
done
for mode in cwd files; do
  [[ -z $active_pids ]] || break
  if [[ $mode == cwd ]]; then
    lsof_args=(-a -d cwd -Fpn)
  else
    lsof_args=(+D "$worktree" -Fpn)
  fi
  lsof_error_file=$(mktemp "${TMPDIR:-/tmp}/cleanup-lsof.XXXXXX") || fail 'could not prepare process inspection'
  lsof_output=''
  if lsof_output=$(lsof "${lsof_args[@]}" 2>"$lsof_error_file"); then lsof_status=0; else lsof_status=$?; fi
  lsof_errors=$(cat "$lsof_error_file") || fail 'could not read process inspection result'
  rm -f "$lsof_error_file" || fail 'could not clean up process inspection result'
  [[ -z $lsof_errors ]] || fail "could not inspect processes using worktree ($mode): $lsof_errors"
  [[ $lsof_status == 0 || $lsof_status == 1 ]] ||
    fail "could not inspect processes using worktree ($mode)"
  mode_pids=$(printf '%s\n' "$lsof_output" | awk -v root="$worktree" '
    /^p[0-9]+$/ { pid = substr($0, 2) }
    /^n/ {
      path = substr($0, 2)
      if (substr(path, 1, 1) != "/") path = "/" path
      if (path == root || index(path, root "/") == 1) print pid
    }' | sort -u | awk -v self="$$" -v parent="$PPID" '$0 != self && $0 != parent && NF')
  if [[ $lsof_status == 1 && -n $lsof_output && -z $mode_pids ]]; then
    fail "could not inspect processes using worktree ($mode)"
  fi
  [[ -z $mode_pids ]] || active_pids+=$'\n'$mode_pids
done
active_pids=$(printf '%s\n' "$active_pids" | sort -u | awk 'NF')
[[ -z $active_pids ]] || fail "worktree path is in use by process(es): $active_pids"

common_dir=$(git -C "$worktree" rev-parse --path-format=absolute --git-common-dir </dev/null) || fail 'could not locate the main worktree'
main_root=$(dirname "$common_dir")
if $dry_run; then
  printf 'git -C %q worktree remove %q\n' "$main_root" "$worktree"
  exit 0
fi
git -C "$main_root" worktree remove "$worktree" </dev/null || fail 'git could not remove the worktree'
