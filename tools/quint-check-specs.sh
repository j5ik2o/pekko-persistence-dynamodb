#!/usr/bin/env bash
# Checks every specs/<dir>/*.qnt with the quint pinned in package-lock.json:
# typecheck, every `run` of every *_test.qnt, and every `quint run` listed in
# specs/<dir>/ci-runs.txt. Run it from the repository root. CI runs it in the
# `quint` job of .github/workflows/ci.yml. Design: docs/design/quint-in-ci.md.
#
# QUINT and QUINT_SPECS_DIR replace the quint executable and the specs
# directory; tools/tests/test_quint_check_specs.py uses them.
set -uo pipefail
shopt -s nullglob

quint=${QUINT:-./node_modules/.bin/quint}
specs=${QUINT_SPECS_DIR:-specs}
seed=0x7

if [ "${BASH_VERSINFO[0]}" -lt 4 ] || { [ "${BASH_VERSINFO[0]}" -eq 4 ] && [ "${BASH_VERSINFO[1]}" -lt 4 ]; }; then
  echo "::error::bash 4.4 or later is required, not $BASH_VERSION"
  exit 1
fi
for tool in jq node; do
  if ! command -v "$tool" >/dev/null; then
    echo "::error::$tool is required"
    exit 1
  fi
done

work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT

failed=()

# fail <file> <message>: report one problem; the caller records the failure.
fail() {
  echo "::error file=$1::$2"
  return 1
}

check_version() {
  local expected actual
  expected=$(jq -r '.packages["node_modules/@informalsystems/quint"].version // empty' package-lock.json)
  actual=$("$quint" --version 2>/dev/null | tail -n 1)
  [ -n "$expected" ] || fail package-lock.json "package-lock.json pins no @informalsystems/quint" || return 1
  [ "$actual" = "$expected" ] ||
    fail package-lock.json "quint ${actual:-missing} is not the $expected that package-lock.json pins; run npm ci" || return 1
  echo "quint $actual"
}

# check_test <file>: the main module is the one named after the file; every
# `run` of it must end in Test, because `quint test` without --match runs only
# those, and quint must pass as many tests as there are runs.
check_test() {
  local spec=$1 base ir out runs=() bad=() name passing
  base=$(basename "$spec" .qnt)
  ir=$work/$base.ir.json
  out=$work/$base.test.out
  "$quint" parse --out="$ir" "$spec" </dev/null >/dev/null || fail "$spec" "quint parse failed" || return 1
  jq -e --arg m "$base" 'any(.modules[]; .name == $m)' "$ir" >/dev/null ||
    fail "$spec" "no module is named $base, so the main module is not known" || return 1
  while IFS= read -r name; do
    runs+=("$name")
    case $name in *Test) ;; *) bad+=("$name") ;; esac
  done < <(jq -r --arg m "$base" '.modules[] | select(.name == $m) | .declarations[] | select(.qualifier == "run") | .name' "$ir")
  [ ${#runs[@]} -gt 0 ] || fail "$spec" "module $base defines no run; every test file needs at least one" || return 1
  [ ${#bad[@]} -eq 0 ] || fail "$spec" "quint test skips these runs because their names do not end in Test: ${bad[*]}" || return 1
  # With --seed, --max-samples defaults to 1, so give the default of 10000 explicitly.
  "$quint" test "$spec" --main "$base" --seed "$seed" --max-samples 10000 </dev/null 2>&1 | tee "$out"
  [ "${PIPESTATUS[0]}" -eq 0 ] || fail "$spec" "quint test failed" || return 1
  passing=$(sed -nE 's/^ *([0-9]+) passing.*/\1/p' "$out" | tail -n 1)
  [ "$passing" = "${#runs[@]}" ] ||
    fail "$spec" "quint test passed ${passing:-0} test(s) but module $base defines ${#runs[@]} run(s)" || return 1
}

# value_of <flag> <args...>: the value of --flag, written as `--flag v` or `--flag=v`.
value_of() {
  local flag=$1 i
  shift
  local args=("$@")
  for ((i = 0; i < ${#args[@]}; i++)); do
    case ${args[i]} in
      "$flag") echo "${args[i + 1]:-}"; return 0 ;;
      "$flag"=*) echo "${args[i]#*=}"; return 0 ;;
    esac
  done
  return 1
}

# values_of <flag> <args...>: the values after --flag up to the next option.
values_of() {
  local flag=$1 i on=false
  shift
  for arg in "$@"; do
    if $on; then
      case $arg in --*) on=false ;; *) echo "$arg"; continue ;; esac
    fi
    case $arg in
      "$flag") on=true ;;
      "$flag"=*) echo "${arg#*=}" ;;
    esac
  done
}

# check_outcome: whether the run in $out ended as $expect says. It reads the
# locals of check_run.
check_outcome() {
  case $expect in
    pass)
      [ "$status" -eq 0 ] || fail "$list" "line $n: quint run failed (exit $status)" || return 1
      grep -q '\[ok\] No violation found' "$out" || fail "$list" "line $n: quint run did not report [ok] No violation found" || return 1
      max=$(sed -nE 's/.*Trace length statistics: max=([0-9]+),.*/\1/p' "$out" | tail -n 1)
      [ -n "$max" ] || fail "$list" "line $n: quint run printed no trace length statistics" || return 1
      # max=1 means no trace took a step, so the invariant was checked on initial states only.
      [ "$max" -gt 1 ] || fail "$list" "line $n: no trace took a step (max trace length $max)" || return 1
      ;;
    violation)
      [ "$status" -ne 0 ] || fail "$list" "line $n: quint run found no violation, but the line expects one" || return 1
      grep -q '\[violation\]' "$out" && grep -q 'Invariant violated' "$out" ||
        fail "$list" "line $n: quint run failed without an invariant violation" || return 1
      # A violating run prints no trace length statistics; its counterexample must take a step.
      grep -q '^\[State 1\]$' "$out" || fail "$list" "line $n: the counterexample takes no step" || return 1
      ;;
  esac
}

# check_run <ci-runs.txt> <line number> <expect> <spec> <args...>
check_run() {
  local list=$1 n=$2 expect=$3 spec=$4
  shift 4
  local args=("$@") where="$list:$n" samples steps invariant out status max w count start
  local witnesses=() invariants=()
  for flag in --seed --max-samples --max-steps; do
    value_of "$flag" "${args[@]}" >/dev/null || fail "$list" "line $n: $flag is required" || return 1
  done
  if value_of --verbosity "${args[@]}" >/dev/null; then
    fail "$list" "line $n: do not set --verbosity; the check runs quint with --verbosity=2" || return 1
  fi
  samples=$(value_of --max-samples "${args[@]}")
  steps=$(value_of --max-steps "${args[@]}")
  [[ $samples =~ ^[0-9]+$ ]] && [ "$samples" -ge 2 ] ||
    fail "$list" "line $n: --max-samples must be 2 or more, not '$samples'" || return 1
  [[ $steps =~ ^[0-9]+$ ]] && [ "$steps" -ge 1 ] ||
    fail "$list" "line $n: --max-steps must be 1 or more, not '$steps'" || return 1
  invariant=$(value_of --invariant "${args[@]}" || true)
  mapfile -t invariants < <(values_of --invariants "${args[@]}")
  # --invariant defaults to `true`, which checks nothing.
  [ -n "$invariant" ] || [ ${#invariants[@]} -gt 0 ] ||
    fail "$list" "line $n: --invariant or --invariants is required" || return 1
  for w in "$invariant" "${invariants[@]}"; do
    [ "$w" != true ] || fail "$list" "line $n: the invariant 'true' checks nothing" || return 1
  done
  mapfile -t witnesses < <(values_of --witnesses "${args[@]}")

  out=$work/run.out
  start=$SECONDS
  "$quint" run "$spec" "${args[@]}" --verbosity=2 >"$out" 2>&1 </dev/null
  status=$?
  echo "$where: $expect, exit $status, $((SECONDS - start))s: quint run $(basename "$spec") ${args[*]}"
  # The full output (every trace with --verbosity=2) is too long for the log.
  grep -oE '\[ok\] No violation found.*|\[violation\].*|Trace length statistics:.*|[A-Za-z0-9_]+ was witnessed in .*|Use --seed=.*|error: .*' "$out"

  if ! check_outcome; then
    tail -n 40 "$out"
    return 1
  fi
  for w in "${witnesses[@]}"; do
    count=$(sed -nE "s/^$w was witnessed in ([0-9]+) trace.*/\\1/p" "$out" | tail -n 1)
    [ -n "$count" ] && [ "$count" -ge 1 ] ||
      fail "$list" "line $n: witness $w was seen in ${count:-no} trace(s); see the comment at the top of ci-runs.txt" || return 1
  done
}

# check_runs <dir>: every line of <dir>/ci-runs.txt, and every spec that is not
# a test file must be the subject of a line.
# shellcheck disable=SC2094 # fail writes to stdout, not to the list it reads.
check_runs() {
  local dir=$1 list=$1/ci-runs.txt n=0 line words spec ok=true covered=() s
  [ -f "$list" ] || fail "$dir" "$list is missing; list the quint runs of the specs in $dir" || return 1
  set -f
  while IFS= read -r line || [ -n "$line" ]; do
    n=$((n + 1))
    case $line in '' | '#'*) continue ;; esac
    read -r -a words <<<"$line"
    [ ${#words[@]} -ge 2 ] || { fail "$list" "line $n: expected '<pass|violation> <file> <args>'"; ok=false; continue; }
    case ${words[0]} in pass | violation) ;; *) fail "$list" "line $n: the first word must be pass or violation"; ok=false; continue ;; esac
    if [[ ! ${words[1]} =~ ^[A-Za-z0-9_.-]+\.qnt$ ]] || [ ! -f "$dir/${words[1]}" ]; then
      fail "$list" "line $n: ${words[1]} is not a .qnt file of $dir"
      ok=false
      continue
    fi
    covered+=("${words[1]}")
    check_run "$list" "$n" "${words[0]}" "$dir/${words[1]}" "${words[@]:2}" || ok=false
  done <"$list"
  set +f
  for spec in "$dir"/*.qnt; do
    case $spec in *_test.qnt) continue ;; esac
    s=$(basename "$spec")
    printf '%s\n' "${covered[@]}" | grep -qxF "$s" ||
      { fail "$spec" "$list runs no quint run of $s"; ok=false; }
  done
  $ok
}

check_dir() {
  local dir=${1%/} spec tests=() ok=true
  for spec in "$dir"/*.qnt; do
    echo "quint typecheck $spec"
    "$quint" typecheck "$spec" </dev/null || { fail "$spec" "quint typecheck failed"; ok=false; }
  done
  tests=("$dir"/*_test.qnt)
  if [ ${#tests[@]} -eq 0 ]; then
    fail "$dir" "$dir has no *_test.qnt"
    ok=false
  fi
  for spec in "${tests[@]}"; do
    check_test "$spec" || ok=false
  done
  check_runs "$dir" || ok=false
  $ok
}

check_version || exit 1

# Only specs/<dir>/<file>.qnt is checked, so a spec anywhere else would be left out.
misplaced=0
while IFS= read -r -d '' spec; do
  if [[ ! $spec =~ ^$specs/[^/]+/[^/]+\.qnt$ ]]; then
    echo "::error file=$spec::a spec must be at $specs/<dir>/<file>.qnt to be checked"
    misplaced=1
  fi
done < <(find "$specs" -name '*.qnt' -type f -print0 2>/dev/null)
[ "$misplaced" -eq 0 ] || failed+=("$specs")

dirs=()
for dir in "$specs"/*/; do
  qnts=("$dir"*.qnt)
  [ ${#qnts[@]} -gt 0 ] && dirs+=("${dir%/}")
done
if [ ${#dirs[@]} -eq 0 ]; then
  echo "::error::No Quint spec matched $specs/*/*.qnt"
  exit 1
fi

for dir in "${dirs[@]}"; do
  echo "::group::$dir"
  start=$SECONDS
  check_dir "$dir" || failed+=("$dir")
  echo "$dir: $((SECONDS - start))s"
  echo '::endgroup::'
done

if [ ${#failed[@]} -gt 0 ]; then
  for dir in "${failed[@]}"; do
    echo "::error file=$dir::quint check failed (see the group for $dir above)"
  done
  exit 1
fi
echo "Every Quint spec passed."
