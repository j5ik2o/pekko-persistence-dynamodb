import importlib.util
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

SCRIPT = Path(__file__).resolve().parents[1] / 'ppd-evidence.py'
spec = importlib.util.spec_from_file_location('evidence', SCRIPT)
evidence = importlib.util.module_from_spec(spec)
spec.loader.exec_module(evidence)


def good():
    return dict(head='abc', reviewed_head='abc', state='open', required_checks=['build'],
                errors=[], review_threads={'exit_code': 0}, checks=[dict(
                    name='build', head='abc', status='completed', conclusion='success')])


class PrEvidenceTest(unittest.TestCase):
    def test_prototype_false_reports(self):
        cases = []
        value = good()
        value['review_threads']['exit_code'] = 1
        cases.append(value)
        value = good()
        value['reviewed_head'] = 'old'
        cases.append(value)
        value = good()
        value['checks'][0]['name'] = 'CodeRabbit'
        value['checks'][0]['conclusion'] = 'neutral'
        cases.append(value)
        value = good()
        value['checks'][0]['status'] = 'in_progress'
        value['checks'][0]['conclusion'] = None
        cases.append(value)
        for value in cases:
            with self.subTest(value=value):
                self.assertEqual(evidence.assess_pr(value)['assessment'], 'blocked')
        self.assertEqual(evidence.assess_pr(good())['assessment'], 'evidence_satisfied')

    def test_unknown_is_not_pending(self):
        value = good()
        value['review_threads']['exit_code'] = 2
        self.assertEqual(evidence.assess_pr(value)['assessment'], 'unknown')
        value = good()
        value['errors'] = ['API unavailable']
        self.assertEqual(evidence.assess_pr(value)['assessment'], 'unknown')

    def collect(self, runs, jobs, thread_exit=0, after='abc', external_checks=None):
        def run(argv):
            endpoint = argv[2] if argv[0] == 'gh' else ''
            if '/pulls/' in endpoint and '/reviews?' not in endpoint:
                self.pr_reads += 1
                body = dict(head={'sha': 'abc' if self.pr_reads == 1 else after}, state='open')
            elif '/reviews?' in endpoint:
                body = [[]]
            elif '/attempts/' in endpoint:
                self.assertIn('/attempts/2/jobs?', endpoint)
                body = [{'jobs': jobs}]
            elif '/actions/runs?' in endpoint:
                body = [{'workflow_runs': runs}]
            elif '/check-runs?' in endpoint:
                body = [{'check_runs': external_checks or []}]
            elif '/statuses?' in endpoint:
                body = [[]]
            else:
                self.assertEqual(argv[0], 'bash')
                return dict(kind='observed', source=argv, exit_code=thread_exit, stdout='', stderr='')
            return dict(kind='observed', source=argv, exit_code=0, stdout=json.dumps(body), stderr='')
        self.pr_reads = 0
        with patch.object(evidence, 'capture', side_effect=run):
            return evidence.collect('owner/repo', 1, 'abc', ['build'])

    def test_collector_latest_run_attempt_and_real_thread_exit(self):
        old = dict(workflow_id=7, id=10, run_number=1, run_attempt=1, head_sha='abc',
                   status='completed', conclusion='cancelled', name='CI')
        new = dict(old, id=11, run_number=2, run_attempt=2, conclusion='success')
        job = dict(name='build', status='completed', conclusion='success', html_url='job-url')
        snapshot = self.collect([new, old], [job])
        self.assertEqual([r['id'] for r in snapshot['runs']], [11])
        self.assertEqual(snapshot['checks'][0]['attempt'], 2)
        self.assertEqual(evidence.assess_pr(snapshot)['assessment'], 'evidence_satisfied')
        snapshot = self.collect([old, new], [job], thread_exit=1)
        self.assertEqual(evidence.assess_pr(snapshot)['assessment'], 'blocked')
        self.assertEqual(snapshot['review_threads']['exit_code'], 1)

    def test_queued_new_run_without_jobs_cannot_inherit_success(self):
        run = dict(workflow_id=7, id=11, run_number=2, run_attempt=2, head_sha='abc',
                   status='queued', conclusion=None, name='CI')
        snapshot = self.collect([run], [])
        report = evidence.assess_pr(snapshot)
        self.assertEqual(report['assessment'], 'blocked')
        self.assertIn('missing required check: build', report['reasons'])
        self.assertTrue(any('pending workflow' in r for r in report['reasons']))

    def test_head_changes_during_collection(self):
        snapshot = self.collect([], [], after='changed')
        self.assertEqual(evidence.assess_pr(snapshot)['assessment'], 'unknown')

    def test_collection_failure_is_saved_as_unknown(self):
        with patch.object(evidence, 'capture', return_value=dict(
                kind='observed', source=['gh', 'api'], exit_code=1, stdout='', stderr='offline')):
            snapshot = evidence.collect('owner/repo', 1, 'abc', ['build'])
        self.assertEqual(snapshot['records'][0]['exit_code'], 1)
        self.assertEqual(evidence.assess_pr(snapshot)['assessment'], 'unknown')

    def test_nullable_check_app_preserves_external_check_and_thread_capture(self):
        check = dict(app=None, id=123, name='build', head_sha='abc',
                     status='completed', conclusion='success', html_url='external-job')
        snapshot = self.collect([], [], external_checks=[check])
        self.assertEqual(snapshot['errors'], [])
        self.assertEqual(snapshot['checks'][0]['source'], 'external-job')
        self.assertEqual(snapshot['review_threads']['exit_code'], 0)
        self.assertEqual(evidence.assess_pr(snapshot)['assessment'], 'evidence_satisfied')

    def test_missing_observations_do_not_claim_closed_or_mismatched_pr(self):
        report = evidence.assess_pr(dict(errors=['GitHub collection failed'],
                                         reviewed_head='abc', required_checks=['build']))
        self.assertEqual(report['assessment'], 'unknown')
        self.assertNotIn('PR is not open', report['reasons'])
        self.assertNotIn('reviewed head does not match current head', report['reasons'])
        closed = good()
        closed['state'] = 'closed'
        self.assertIn('PR is not open', evidence.assess_pr(closed)['reasons'])
        mismatch = good()
        mismatch['head'] = 'other'
        self.assertIn('reviewed head does not match current head', evidence.assess_pr(mismatch)['reasons'])

    def test_capture_preserves_process_exit(self):
        record = evidence.capture([sys.executable, '-c', 'print("unresolved"); raise SystemExit(1)'])
        self.assertEqual(record['exit_code'], 1)
        self.assertIn('unresolved', record['stdout'])

    def test_cli_serializes_report_and_nonzero_exit(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'snapshot.json'
            value = good()
            value['review_threads']['exit_code'] = 1
            path.write_text(json.dumps(value))
            result = subprocess.run([sys.executable, str(SCRIPT), 'pr', str(path)],
                                    capture_output=True, text=True)
            self.assertEqual(result.returncode, 1)
            self.assertEqual(json.loads(result.stdout)['assessment'], 'blocked')
            self.assertIn('review-thread exit 1', result.stderr)


class ProgressTest(unittest.TestCase):
    def task(self):
        return dict(task='876', source_thread='offline PPD child', initial_head='same',
                    current_head='same', git_status='', updatedAt='2099-01-01', events=[
                        dict(type='command_execution', text='cat /repo/SKILL.md'),
                        dict(type='compaction')] * 14)

    def assess(self, task):
        return evidence.assess_progress({'tasks': [task]})['findings'][0]

    def test_historical_three_tasks(self):
        snapshot = json.loads((Path(__file__).parent / 'fixtures/repeated-preparation.json').read_text())
        reports = evidence.assess_progress(snapshot)['findings']
        self.assertEqual([r['compactions'] for r in reports], [16, 18, 14])
        self.assertEqual([r['assessment'] for r in reports],
                         ['investigate_repeated_preparation'] * 3)

    def test_recorded_repetition_despite_recent_updated_at(self):
        report = self.assess(self.task())
        self.assertEqual(report['assessment'], 'investigate_repeated_preparation')
        self.assertEqual(report['compactions'], 14)
        self.assertEqual(report['repeated_reads']['/repo/SKILL.md'], 14)

    def test_bare_filename_reads_trigger_the_same_diagnostic(self):
        for command, filename in [('cat AGENTS.md', 'AGENTS.md'),
                                  ("sed -n '1,200p' SKILL.md", 'SKILL.md')]:
            with self.subTest(command=command):
                task = self.task()
                task['events'] = [dict(type='command_execution', text=command),
                                  dict(type='compaction')] * 3
                report = self.assess(task)
                self.assertEqual(report['assessment'], 'investigate_repeated_preparation')
                self.assertEqual(report['repeated_reads'][filename], 3)

    def test_commit_diff_and_observed_verification_are_visible(self):
        for field, value in [('current_head', 'new'), ('git_status', ' M tools/file.py')]:
            task = self.task()
            task[field] = value
            self.assertEqual(self.assess(task)['assessment'], 'repetition_with_progress_evidence')
        task = self.task()
        task['events'].append(dict(type='verification', kind='observed', exit_code=0,
                                   source='unittest', run_id='run-1', head='same'))
        self.assertEqual(len(self.assess(task)['verification']), 1)

    def test_unchanged_dirty_tree_does_not_count_as_new_progress(self):
        task = self.task()
        task.update(initial_git_status=' M file.py', git_status=' M file.py',
                    initial_diff_sha256='same', current_diff_sha256='same')
        self.assertEqual(self.assess(task)['assessment'], 'investigate_repeated_preparation')
        task['current_diff_sha256'] = 'changed'
        self.assertEqual(self.assess(task)['assessment'], 'repetition_with_progress_evidence')

    def test_reported_verification_does_not_prove_progress(self):
        task = self.task()
        task['events'].append(dict(type='verification', kind='reported', exit_code=0))
        self.assertEqual(self.assess(task)['assessment'], 'investigate_repeated_preparation')

    def test_absent_trigger_is_not_health_or_completion(self):
        task = self.task()
        task['events'] = []
        self.assertEqual(self.assess(task)['assessment'], 'no_repetition_trigger')
        del task['current_head']
        self.assertEqual(self.assess(task)['assessment'], 'unknown')


if __name__ == '__main__':
    unittest.main()
