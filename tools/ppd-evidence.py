#!/usr/bin/env python3
"""Read-only PR evidence collection and offline progress assessment."""
import argparse
import collections
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import re
import subprocess
import sys

LIMITS = ('Evidence only, not merge authorization. Check the diff, review contents and '
          'branch protection separately. Snapshots can become stale.')


def now():
    return datetime.now(timezone.utc).isoformat()


def capture(argv):
    try:
        result = subprocess.run(argv, capture_output=True, text=True, timeout=120)
        return dict(kind='observed', source=argv, observed_at=now(), exit_code=result.returncode,
                    stdout=result.stdout, stderr=result.stderr)
    except (OSError, subprocess.TimeoutExpired) as error:
        return dict(kind='unknown', source=argv, observed_at=now(), exit_code=None,
                    stdout='', stderr=str(error))


def collect(repo, number, reviewed, required):
    records = []

    def query(endpoint, paginated=False):
        command = ['gh', 'api', endpoint]
        if paginated:
            command += ['--paginate', '--slurp']
        record = capture(command)
        records.append(record)
        if record['exit_code'] != 0:
            raise ValueError('GitHub collection failed: ' + endpoint)
        try:
            return json.loads(record['stdout'])
        except json.JSONDecodeError as error:
            raise ValueError('GitHub returned invalid JSON: ' + endpoint) from error

    data = dict(schema_version=1, kind='observed', source=f'{repo}#{number}', observed_at=now(),
                reviewed_head=reviewed, reviewed_head_kind='reported', required_checks=required,
                records=records, errors=[])
    try:
        pr = query(f'repos/{repo}/pulls/{number}')
        data['pull_request'] = pr
        data['reviews'] = query(f'repos/{repo}/pulls/{number}/reviews?per_page=100', True)
        head = pr['head']['sha']
        data.update(head=head, state=pr['state'])
        pages = query(f'repos/{repo}/actions/runs?head_sha={head}&per_page=100', True)
        runs = [r for page in pages for r in page['workflow_runs'] if r['head_sha'] == head]
        latest = {}
        for run in runs:
            key = str(run['workflow_id'])
            if key not in latest or (run['run_number'], run['run_attempt']) > (
                    latest[key]['run_number'], latest[key]['run_attempt']):
                latest[key] = run
        data['runs'] = list(latest.values())
        checks = []
        for run in data['runs']:
            pages = query(f"repos/{repo}/actions/runs/{run['id']}/attempts/{run['run_attempt']}/jobs?per_page=100", True)
            for page in pages:
                for job in page['jobs']:
                    checks.append(dict(name=job['name'], head=head, status=job['status'],
                                       conclusion=job['conclusion'], source=job['html_url'],
                                       run_id=run['id'], attempt=run['run_attempt']))
        pages = query(f'repos/{repo}/commits/{head}/check-runs?filter=latest&per_page=100', True)
        external = {}
        for page in pages:
            for check in page['check_runs']:
                app = check.get('app') or {}
                if app.get('slug') == 'github-actions':
                    continue
                key = (app.get('id'), check['name'])
                if key not in external or check['id'] > external[key]['id']:
                    external[key] = check
        for check in external.values():
            checks.append(dict(name=check['name'], head=check['head_sha'],
                               status=check['status'], conclusion=check['conclusion'],
                               source=check['html_url'], run_id=check['id'], attempt=None))
        pages = query(f'repos/{repo}/commits/{head}/statuses?per_page=100', True)
        contexts = {}
        for page in pages:
            for status in page:
                if status['context'] not in contexts or status['id'] > contexts[status['context']]['id']:
                    contexts[status['context']] = status
        for status in contexts.values():
            checks.append(dict(name=status['context'], head=head, status=status['state'],
                               conclusion=status['state'], source=status['target_url'],
                               run_id=status['id'], attempt=None))
        data['checks'] = checks
        script = Path(__file__).resolve().with_name('check-review-threads.sh')
        data['review_threads'] = capture(['bash', str(script), repo, str(number)])
        after = query(f'repos/{repo}/pulls/{number}')
        if after['head']['sha'] != head or after['state'] != pr['state']:
            data['errors'].append('PR changed during collection; collect again')
    except (ValueError, KeyError, TypeError) as error:
        data['errors'].append(str(error))
    return data


def check_state(status, conclusion):
    if status in ('queued', 'in_progress', 'pending', 'waiting', 'requested', 'expected'):
        return 'pending'
    if status == 'success' or (status == 'completed' and conclusion == 'success'):
        return 'success'
    if status == 'completed' and conclusion in ('neutral', 'skipped'):
        return 'neutral'
    if status in ('failure', 'error') or conclusion in (
            'failure', 'cancelled', 'timed_out', 'action_required', 'startup_failure', 'stale'):
        return 'failed'
    return 'unknown'


def assess_pr(data):
    reasons = list(data.get('errors', []))
    unknown = bool(reasons)
    head = data.get('head')
    if not head:
        unknown = True
        reasons.append('current head unknown')
    if not data.get('reviewed_head'):
        unknown = True
        reasons.append('reviewed head unknown')
    elif head and head != data['reviewed_head']:
        reasons.append('reviewed head does not match current head')
    if data.get('state') not in ('open', 'closed'):
        unknown = True
        reasons.append('PR state unknown')
    elif data['state'] == 'closed':
        reasons.append('PR is not open')
    thread = data.get('review_threads', {})
    code = thread.get('exit_code')
    if code != 0:
        reasons.append(f'review-thread exit {code}')
        unknown |= code != 1
    if not data.get('required_checks'):
        unknown = True
        reasons.append('required check list is empty')
    by_name = collections.defaultdict(list)
    for check in data.get('checks', []):
        if check.get('head') != head:
            reasons.append('check head mismatch: ' + check['name'])
            unknown = True
            continue
        by_name[check['name']].append(check_state(check.get('status'), check.get('conclusion')))
    for name in data.get('required_checks', []):
        states = by_name.get(name, [])
        if not states:
            reasons.append('missing required check: ' + name)
        elif any(state != 'success' for state in states):
            reasons.append('required check not successful: ' + name)
    for name, states in by_name.items():
        for state in set(states) - {'success', 'neutral'}:
            reasons.append(f'{state} check: {name}')
            unknown |= state == 'unknown'
    for run in data.get('runs', []):
        state = check_state(run.get('status'), run.get('conclusion'))
        if state not in ('success', 'neutral'):
            reasons.append(f"{state} workflow: {run['name']} run {run['id']} attempt {run['run_attempt']}")
            unknown |= state == 'unknown'
    assessment = 'unknown' if unknown else ('blocked' if reasons else 'evidence_satisfied')
    return dict(kind='inferred', source=data.get('source'), observed_at=data.get('observed_at'),
                head=head, reviewed_head=data.get('reviewed_head'), assessment=assessment,
                reasons=reasons, runs=[dict(run_id=r['id'], attempt=r['run_attempt'],
                                           source=r.get('html_url'), status=r['status'],
                                           conclusion=r.get('conclusion')) for r in data.get('runs', [])],
                review_thread_exit=code, limits=LIMITS)


def assess_progress(data):
    findings = []
    for task in data['tasks']:
        reads = collections.Counter()
        events = task['events']
        for event in events:
            if event['type'] != 'command_execution':
                continue
            command = (event.get('text') or '').split('\n', 1)[0]
            if re.search(r'\b(cat|sed|tail|head)\s', command):
                reads.update(set(re.findall(r'/?(?:[A-Za-z0-9_.-]+/)*[A-Za-z0-9_.-]+\.(?:md|txt|scala|json|py)', command)))
        compactions = sum(e['type'] == 'compaction' for e in events)
        verification = [e for e in events if e['type'] == 'verification' and
                        e.get('kind') == 'observed' and e.get('exit_code') is not None and
                        e.get('head') == task.get('current_head') and e.get('source') and e.get('run_id')]
        known = all(task.get(k) is not None for k in ('initial_head', 'current_head', 'git_status'))
        delta = known and (task['initial_head'] != task['current_head'] or
                           task['git_status'] != task.get('initial_git_status', '') or
                           task.get('diff_changed') is True or
                           (task.get('initial_diff_sha256') is not None and
                            task.get('current_diff_sha256') is not None and
                            task['initial_diff_sha256'] != task['current_diff_sha256']))
        trigger = compactions >= 3 and max(reads.values(), default=0) >= 3
        assessment = ('unknown' if not known else
                      'investigate_repeated_preparation' if trigger and not delta and not verification else
                      'repetition_with_progress_evidence' if trigger else 'no_repetition_trigger')
        findings.append(dict(task=task['task'], kind='inferred', source=task.get('source_thread'),
                             observed_at=task.get('observed_at', data.get('observed_at')),
                             compactions=compactions, repeated_reads=dict(reads), artifact_delta=delta,
                             verification=verification, assessment=assessment))
    return dict(kind='inferred', findings=findings,
                limits='Candidates only. No trigger does not prove health or completion. '
                       'Activity timestamps and reported success do not prove progress.')


def collect_git():
    records = {name: capture(command) for name, command in (
        ('head', ['git', 'rev-parse', 'HEAD']),
        ('status', ['git', 'status', '--porcelain=v1', '--untracked-files=all']),
        ('diff', ['git', 'diff', 'HEAD', '--no-ext-diff', '--no-textconv']),
        ('log', ['git', 'log', '-5', '--format=%H %cI %s']))}
    return dict(schema_version=1, kind='observed', source=str(Path.cwd()), observed_at=now(),
                records=records,
                head=records['head']['stdout'].strip() if records['head']['exit_code'] == 0 else None,
                git_status=records['status']['stdout'] if records['status']['exit_code'] == 0 else None,
                diff_sha256=hashlib.sha256(records['diff']['stdout'].encode()).hexdigest()
                if records['diff']['exit_code'] == 0 else None)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest='command', required=True)
    local = sub.add_parser('git')
    local.add_argument('--output')
    live = sub.add_parser('collect')
    live.add_argument('--repo', required=True)
    live.add_argument('--pr', type=int, required=True)
    live.add_argument('--reviewed-head', required=True)
    live.add_argument('--required-check', action='append', required=True)
    live.add_argument('--output', required=True)
    for name in ('pr', 'progress'):
        offline = sub.add_parser(name)
        offline.add_argument('snapshot')
        offline.add_argument('--output')
    args = parser.parse_args()
    try:
        if args.command == 'git':
            result = collect_git()
            if args.output:
                Path(args.output).write_text(json.dumps(result, indent=2) + '\n')
            print(json.dumps(result, indent=2))
            return 0 if all(r['exit_code'] == 0 for r in result['records'].values()) else 2
        if args.command == 'collect':
            if not re.fullmatch(r'[\w.-]+/[\w.-]+', args.repo) or args.pr <= 0:
                parser.error('expected owner/repo and positive PR number')
            snapshot = collect(args.repo, args.pr, args.reviewed_head, args.required_check)
            report = assess_pr(snapshot)
            result = dict(snapshot=snapshot, report=report)
        else:
            snapshot = json.loads(Path(args.snapshot).read_text())
            if args.command == 'pr':
                report = assess_pr(snapshot.get('snapshot', snapshot))
            else:
                report = assess_progress(snapshot)
            result = report
        if args.output:
            Path(args.output).write_text(json.dumps(result, indent=2, ensure_ascii=False) + '\n')
        print(json.dumps(result, indent=2, ensure_ascii=False))
        if args.command == 'progress':
            return 2 if any(f['assessment'] == 'unknown' for f in report['findings']) else int(
                any(f['assessment'] == 'investigate_repeated_preparation' for f in report['findings']))
        print(f"{report['assessment']}: head={report['head']} reviewed={report['reviewed_head']}; "
              + '; '.join(report['reasons']) + f"; runs={report['runs']}; thread_exit={report['review_thread_exit']}. " + LIMITS, file=sys.stderr)
        return 2 if report['assessment'] == 'unknown' else int(report['assessment'] != 'evidence_satisfied')
    except (OSError, ValueError, KeyError, TypeError) as error:
        print(json.dumps(dict(assessment='unknown', error=str(error))), file=sys.stderr)
        return 2


if __name__ == '__main__':
    sys.exit(main())
