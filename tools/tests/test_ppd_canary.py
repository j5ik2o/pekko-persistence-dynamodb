"""Cheap synthetic fixtures exercise the real CLI and real temporary file bytes."""
import copy
import hashlib
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

CLI = Path(__file__).resolve().parents[1] / 'ppd-canary.py'
AS_OF = '2026-11-11T01:00:00+00:00'


class CanaryTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.directory = Path(self.temp.name)
        config = dict(providerInstance='fixture-provider', modelSelector='fixture-model',
                      options=[dict(id='reasoning', value='high'), dict(id='enabled', value=True)],
                      runtimeMode='fixture', environment='fixture-local', project='fixture-project',
                      repo='fixture-repo')
        identity = dict(task='fixture-task', thread='fixture-thread', run='fixture-run', attempt=1,
                        worktree='fixture-tree', head='fixture-head', sources=['synthetic-fixture'],
                        requestedAt='2026-11-11T00:00:00Z', startedAt='2026-11-11T00:01:00Z',
                        endedAt='2026-11-11T00:02:00Z')
        (self.directory / 'report.txt').write_bytes(b'fixture report\n')
        (self.directory / 'check.log').write_bytes(b'fixture check\n')
        def evidence(path):
            return dict(path=path, sha256=hashlib.sha256(
                (self.directory / path).read_bytes()).hexdigest(),
                        head=identity['head'], run=identity['run'], attempt=1)
        self.record = dict(identity=identity, requested=copy.deepcopy(config), reported='success',
                           observed=dict(host_config=copy.deepcopy(config), executedModel=None,
                                         terminal=dict(status='completed', pendingQuestion=False,
                                                       descendantsComplete=True, descendants=[])),
                           artifact=evidence('report.txt'), verification=dict(
                               kind='observed', exitCode=0, log=evidence('check.log')))
        self.export = dict(schema=1, target=dict(requested_config=config), records=[self.record])

    def run_cli(self, export=None, age=3600):
        path = self.directory / 'export.json'
        path.write_text(json.dumps(self.export if export is None else export), encoding='utf-8')
        process = subprocess.run([sys.executable, str(CLI), str(path), '--as-of', AS_OF,
                                  '--max-age-seconds', str(age)], capture_output=True, text=True)
        self.assertEqual(process.returncode, 0, process.stderr)
        return json.loads(process.stdout)

    def assessment(self, expected, reason=None, export=None, age=3600):
        result = self.run_cli(export, age)
        self.assertEqual(result['inferred_assessment'], expected, result)
        if reason:
            self.assertIn(reason, result['reason_codes'])
        return result

    def test_exact_and_options_reordered(self):
        first = self.assessment('pass')
        self.record['requested']['options'].reverse()
        self.record['observed']['host_config']['options'].reverse()
        second = self.assessment('pass')
        self.assertEqual(first['config_fingerprint'], second['config_fingerprint'])
        self.assertEqual(second['identity_scope'], 'host_configuration')
        self.assertIsNone(second['executedModel'])
        self.assertEqual(second['actual_model'], 'unknown')
        self.assertIn('actual_model_identity', second['unobserved_items'])

    def test_changed_target(self):
        for key, value in [('providerInstance', 'other'), ('environment', 'other'),
                           ('options', [dict(id='enabled', value='true')]),
                           ('revision', 'new-revision')]:
            with self.subTest(key=key):
                export = copy.deepcopy(self.export)
                export['target']['requested_config'][key] = value
                self.assessment('needs-canary', 'no_exact_record', export)

    def test_expiry(self):
        self.assessment('needs-canary', 'expired', age=10)

    def test_timezone_missing_has_distinct_reason(self):
        for key in ['requestedAt', 'startedAt', 'endedAt']:
            with self.subTest(key=key):
                export = copy.deepcopy(self.export)
                export['records'][0]['identity'][key] = self.record['identity'][key].removesuffix('Z')
                self.assessment('unknown', 'ambiguous_timestamp', export)

    def test_malformed_timestamp_has_invalid_reason(self):
        self.record['identity']['startedAt'] = 'not-a-date'
        self.assessment('unknown', 'invalid_timestamp')

    def test_ambiguous_models_and_missing_config(self):
        for value in [None, '', 'auto', 'ambiguous', 'unknown']:
            with self.subTest(value=value):
                export = copy.deepcopy(self.export)
                export['target']['requested_config']['modelSelector'] = value
                self.assessment('unknown', export=export)
        del self.record['requested']['environment']
        self.assessment('unknown', 'missing_configuration')

    def test_observed_mismatch(self):
        self.record['observed']['host_config']['providerInstance'] = 'different'
        self.assessment('unknown', 'requested_observed_mismatch')

    def test_completed_parent_is_insufficient(self):
        terminal = self.record['observed']['terminal']
        terminal['descendants'] = [dict(id='child', status='running', pendingQuestion=False)]
        self.assessment('unknown', 'nonterminal_execution')
        terminal['descendants'] = []
        del terminal['descendantsComplete']
        self.assessment('unknown', 'incomplete_descendant_export')

    def test_questions_and_descendant_failure(self):
        terminal = self.record['observed']['terminal']
        terminal['pendingQuestion'] = True
        self.assessment('unknown', 'pending_or_unexported_question')
        terminal['pendingQuestion'] = False
        terminal['descendants'] = [dict(id='child', status='failed', pendingQuestion=False)]
        self.assessment('needs-canary', 'latest_attempt_failed')

    def test_missing_and_corrupt_bytes(self):
        for kind in ['artifact', 'log']:
            for problem in ['missing', 'hash', 'path', 'binding']:
                with self.subTest(kind=kind, problem=problem):
                    export = copy.deepcopy(self.export)
                    container = export['records'][0]
                    if kind == 'log':
                        container = container['verification']
                    evidence = container[kind]
                    if problem == 'missing':
                        del container[kind]
                    elif problem == 'hash':
                        evidence['sha256'] = '0' * 64
                    elif problem == 'path':
                        evidence['path'] = 'absent'
                    else:
                        del evidence['sha256']
                    self.assessment('unknown', export=export)
        (self.directory / 'report.txt').write_bytes(b'changed')
        self.assessment('unknown', 'evidence_hash_mismatch')

    def test_verification_metadata(self):
        for change in [{'kind': 'reported'}, {'exitCode': None}, {'exitCode': True}]:
            export = copy.deepcopy(self.export)
            export['records'][0]['verification'].update(change)
            self.assessment('unknown', export=export)
        del self.record['verification']
        self.assessment('unknown', 'missing_actual_verification')

    def test_bindings(self):
        for kind in ['artifact', 'log']:
            for key in ['head', 'run', 'attempt']:
                with self.subTest(kind=kind, key=key):
                    export = copy.deepcopy(self.export)
                    record = export['records'][0]
                    evidence = record['artifact'] if kind == 'artifact' else record['verification']['log']
                    evidence[key] = 'wrong'
                    self.assessment('unknown', 'evidence_binding_mismatch', export)

    def test_nonzero_and_interrupted(self):
        self.record['verification']['exitCode'] = 1
        self.assessment('needs-canary', 'latest_attempt_failed')
        self.record['verification']['exitCode'] = 0
        self.record['observed']['terminal']['status'] = 'interrupted'
        self.assessment('needs-canary', 'latest_attempt_failed')

    def test_newer_attempt_blocks_old_success(self):
        for state in ['failed', 'running']:
            export = copy.deepcopy(self.export)
            newer = copy.deepcopy(self.record)
            newer['identity']['attempt'] = 2
            newer['identity']['endedAt'] = '2026-11-11T00:01:30Z'
            newer['artifact']['attempt'] = 2
            newer['verification']['log']['attempt'] = 2
            newer['observed']['terminal']['status'] = state
            export['records'].append(newer)
            self.assessment('needs-canary' if state == 'failed' else 'unknown', export=export)

    def test_ordering_ties_and_missing(self):
        self.export['records'].append(copy.deepcopy(self.record))
        self.assessment('unknown', 'ambiguous_order')
        self.export['records'].pop()
        del self.record['identity']['startedAt']
        self.assessment('unknown', 'missing_timestamp')

    def test_identity_does_not_change_fingerprint(self):
        before = self.assessment('pass')['config_fingerprint']
        self.record['identity']['worktree'] = 'other-tree'
        self.assertEqual(before, self.assessment('pass')['config_fingerprint'])

    def test_typed_options_and_optional_runtime(self):
        baseline = self.assessment('pass')['config_fingerprint']
        for value in ['true', 1, 1.0]:
            export = copy.deepcopy(self.export)
            for config in [export['target']['requested_config'],
                           export['records'][0]['requested'],
                           export['records'][0]['observed']['host_config']]:
                config['options'][1]['value'] = value
            self.assertNotEqual(baseline, self.assessment('pass', export=export)['config_fingerprint'])
        for bad in [None, [], {}]:
            export = copy.deepcopy(self.export)
            export['target']['requested_config']['options'][0]['value'] = bad
            self.assessment('unknown', export=export)

    def test_new_request_wins_over_late_old_completion(self):
        newer = copy.deepcopy(self.record)
        newer['identity'].update(requestedAt='2026-11-11T00:10:00Z',
                                 startedAt='2026-11-11T00:11:00Z',
                                 endedAt='2026-11-11T00:12:00Z')
        newer['verification']['exitCode'] = 1
        self.record['identity']['endedAt'] = '2026-11-11T00:50:00Z'
        self.export['records'].insert(0, newer)
        self.assessment('needs-canary', 'latest_attempt_failed')

    def test_reported_text_is_ignored(self):
        self.record['reported'] = 'SECRET-REPORT-CONTENTS'
        result = self.assessment('pass')
        self.assertNotIn('SECRET-REPORT-CONTENTS', json.dumps(result))
        self.record['verification']['log']['attempt'] = True
        self.assessment('unknown', 'evidence_binding_mismatch')

    def test_required_time_arguments_and_invalid_json(self):
        path = self.directory / 'bad.json'
        path.write_text('{"schema":1,"schema":1}')
        for args in [[], ['--as-of', AS_OF, '--max-age-seconds', '3600']]:
            process = subprocess.run([sys.executable, str(CLI), str(path)] + args,
                                     capture_output=True, text=True)
            self.assertEqual(process.returncode, 2)
            self.assertNotIn(path.read_text(), process.stderr)


if __name__ == '__main__':
    unittest.main()
