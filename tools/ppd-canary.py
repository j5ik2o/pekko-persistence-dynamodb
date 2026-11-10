#!/usr/bin/env python3
"""Match trusted manual exports and explicit local evidence bytes. Never run work."""
import argparse
import hashlib
import json
import math
from datetime import datetime
from pathlib import Path


class Unknown(ValueError):
    pass


def require(condition, reason):
    if not condition:
        raise Unknown(reason)


def text(value):
    return isinstance(value, str) and bool(value.strip())


def timestamp(value):
    require(text(value), 'missing_timestamp')
    try:
        result = datetime.fromisoformat(value.replace('Z', '+00:00'))
    except ValueError:
        raise Unknown('invalid_timestamp') from None
    require(result.utcoffset() is not None, 'ambiguous_timestamp')
    return result


def canonical(value):
    return json.dumps(value, sort_keys=True, separators=(',', ':'), ensure_ascii=False,
                      allow_nan=False).encode('utf-8')


def configuration(value):
    required = {'providerInstance', 'modelSelector', 'options', 'runtimeMode',
                'environment', 'project', 'repo'}
    require(isinstance(value, dict) and required <= value.keys(), 'missing_configuration')
    require(value.keys() <= required | {'runtime', 'version', 'revision'},
            'unsupported_configuration_key')
    for key in value.keys() - {'options'}:
        require(text(value[key]), 'missing_configuration')
    require(value['modelSelector'].strip().lower() not in
            {'auto', 'default', 'unknown', 'ambiguous', '*'}, 'ambiguous_host_model')
    options = value['options']
    require(isinstance(options, list), 'invalid_options')
    ids = set()
    for option in options:
        require(isinstance(option, dict) and set(option) == {'id', 'value'}, 'invalid_options')
        require(text(option['id']) and option['id'] not in ids, 'invalid_options')
        ids.add(option['id'])
        val = option['value']
        require(type(val) in (str, bool, int, float) and
                (type(val) is not float or math.isfinite(val)), 'invalid_options')
    return {**value, 'options': sorted(options, key=lambda option: option['id'])}


def fingerprint(value):
    return hashlib.sha256(canonical(value)).hexdigest()


def evidence_file(value, identity, directory):
    require(isinstance(value, dict), 'missing_evidence')
    for key in ('head', 'run', 'attempt'):
        require(key in value and type(value[key]) is type(identity[key]) and
                value[key] == identity[key], 'evidence_binding_mismatch')
    require(text(value.get('path')), 'missing_evidence_path')
    digest = value.get('sha256')
    require(isinstance(digest, str) and len(digest) == 64 and
            all(c in '0123456789abcdef' for c in digest), 'missing_evidence_hash')
    try:
        actual = hashlib.sha256((directory / value['path']).read_bytes()).hexdigest()
    except OSError:
        raise Unknown('unreadable_evidence') from None
    require(actual == digest, 'evidence_hash_mismatch')


def terminal_proof(terminal):
    require(isinstance(terminal, dict), 'missing_terminal_export')
    require(terminal.get('descendantsComplete') is True, 'incomplete_descendant_export')
    nodes = terminal.get('descendants')
    require(isinstance(nodes, list), 'incomplete_descendant_export')
    ids = set()
    for node in nodes:
        require(isinstance(node, dict) and text(node.get('id')) and node['id'] not in ids,
                'invalid_descendant_export')
        ids.add(node['id'])
    statuses = []
    for node in [terminal] + nodes:
        require(node.get('pendingQuestion') is False, 'pending_or_unexported_question')
        status = node.get('status')
        require(status in {'completed', 'failed', 'interrupted'}, 'nonterminal_execution')
        statuses.append(status)
    return all(status == 'completed' for status in statuses)


def assess(export, as_of, max_age, directory):
    result = {'inferred_assessment': 'unknown', 'reason_codes': [], 'adopted_record': None,
              'config_fingerprint': None, 'identity_scope': 'host_configuration',
              'actual_model': 'unknown', 'executedModel': None,
              'unobserved_items': ['actual_model_identity'],
              'limitations': 'Checks trusted export consistency and local bytes only. Does not prove '
                             'host truth or actual model identity. Manual aid, no automatic T3 gate.'}
    try:
        require(isinstance(export, dict) and type(export.get('schema')) is int and
                export['schema'] == 1, 'unsupported_schema')
        target = configuration(export.get('target', {}).get('requested_config'))
        target_bytes = canonical(target)
        result['config_fingerprint'] = fingerprint(target)
        records = export.get('records')
        require(isinstance(records, list), 'missing_records')
        candidates = []
        for record in records:
            require(isinstance(record, dict), 'invalid_record')
            requested = configuration(record.get('requested'))
            if canonical(requested) != target_bytes:
                continue
            identity = record.get('identity')
            require(isinstance(identity, dict), 'missing_identity')
            for key in ('task', 'thread', 'run', 'worktree', 'head'):
                require(text(identity.get(key)), 'missing_identity')
            require(isinstance(identity.get('sources'), list) and identity['sources'] and
                    all(text(source) for source in identity['sources']), 'missing_sources')
            attempt = identity.get('attempt')
            require(type(attempt) is int and attempt > 0, 'invalid_attempt')
            requested_at = timestamp(identity.get('requestedAt'))
            started_at = timestamp(identity.get('startedAt'))
            require(requested_at <= started_at <= as_of, 'invalid_order')
            candidates.append(((requested_at, started_at, attempt), record))
        if not candidates:
            result.update(inferred_assessment='needs-canary', reason_codes=['no_exact_record'])
            return result
        candidates.sort(key=lambda candidate: candidate[0])
        require(len({key for key, _ in candidates}) == len(candidates), 'ambiguous_order')
        record = candidates[-1][1]
        identity = record['identity']
        result['adopted_record'] = identity
        observed = record.get('observed')
        require(isinstance(observed, dict), 'missing_observed_configuration')
        require(canonical(configuration(observed.get('host_config'))) == target_bytes,
                'requested_observed_mismatch')
        # A host selector is never an observation of the model that executed work.
        result['executedModel'] = observed.get('executedModel')
        successful = terminal_proof(observed.get('terminal'))
        ended = timestamp(identity.get('endedAt'))
        require(timestamp(identity['startedAt']) <= ended <= as_of, 'invalid_order')
        verification = record.get('verification')
        require(isinstance(verification, dict) and verification.get('kind') == 'observed',
                'missing_actual_verification')
        require(type(verification.get('exitCode')) is int, 'missing_real_exit')
        evidence_file(record.get('artifact'), identity, directory)
        evidence_file(verification.get('log'), identity, directory)
        if not successful or verification['exitCode'] != 0:
            result.update(inferred_assessment='needs-canary', reason_codes=['latest_attempt_failed'])
        elif (as_of - ended).total_seconds() > max_age:
            result.update(inferred_assessment='needs-canary', reason_codes=['expired'])
        else:
            result.update(inferred_assessment='pass', reason_codes=['matching_completed_canary'])
    except Unknown as error:
        result['reason_codes'] = [str(error)]
        result['unobserved_items'].append('sufficient_matching_completion_evidence')
    except (AttributeError, TypeError, KeyError, ValueError):
        result['reason_codes'] = ['invalid_export_shape']
        result['unobserved_items'].append('sufficient_matching_completion_evidence')
    return result


def unique_object(pairs):
    result = {}
    for key, value in pairs:
        require(key not in result, 'duplicate_json_key')
        result[key] = value
    return result


def reject_constant(_):
    raise Unknown('invalid_json')


def finite_float(value):
    result = float(value)
    require(math.isfinite(result), 'invalid_json')
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('export', type=Path)
    parser.add_argument('--as-of', required=True, help='Explicit timezone-aware ISO timestamp')
    parser.add_argument('--max-age-seconds', required=True, type=int)
    args = parser.parse_args()
    try:
        as_of = timestamp(args.as_of)
        require(args.max_age_seconds >= 0, 'invalid_max_age')
        data = json.loads(args.export.read_text(encoding='utf-8'), object_pairs_hook=unique_object,
                          parse_constant=reject_constant, parse_float=finite_float)
    except (OSError, ValueError):
        parser.exit(2, 'Cannot read export or invalid JSON/time/age. No file contents displayed.\n')
    print(json.dumps(assess(data, as_of, args.max_age_seconds, args.export.parent),
                     sort_keys=True, ensure_ascii=True, allow_nan=False))


if __name__ == '__main__':
    main()
