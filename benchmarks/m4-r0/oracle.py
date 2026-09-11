"""Executable bounded research specification; not a Spring extractor or production reasoner."""
import argparse
import hashlib
import itertools
import json
import math
import platform
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parent

def canonical(value):
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(',', ':')).encode('utf-8')

def sha(data):
    return hashlib.sha256(data).hexdigest()

def condition(expr, assignment, state, gaps):
    op = expr['op']
    if op == 'const':
        assert expr['value'] in ('T', 'F', 'U')
        return expr['value']
    if op in ('all', 'any'):
        values = [condition(e, assignment, state, gaps) for e in expr['args']]
        decisive, identity = ('F', 'T') if op == 'all' else ('T', 'F')
        return decisive if decisive in values else 'U' if 'U' in values else identity
    if op == 'not':
        return {'T': 'F', 'F': 'T', 'U': 'U'}[condition(expr['arg'], assignment, state, gaps)]
    if op in ('bean', 'missingBean'):
        present = 'T' if 'T' in state.values() else 'U' if 'U' in state.values() else 'F'
        return present if op == 'bean' else {'T': 'F', 'F': 'T', 'U': 'U'}[present]
    if op in ('profile', 'property'):
        key = 'profile:' + expr['name'] if op == 'profile' else expr['name']
        if key not in assignment:
            gaps.add('CONFIGURATION_NOT_OBSERVED')
            return 'U'
        value = assignment[key]
        if op == 'profile':
            assert isinstance(value, bool)
            result = value
        elif value is None:
            result = expr['matchIfMissing']
        else:
            # This experiment's property domain is ASCII; Java Unicode case folding is outside it.
            assert isinstance(value, str) and value.isascii()
            result = value.lower() == expr['having'].lower() if expr['having'] else value.lower() != 'false'
        return 'T' if result else 'F'
    gaps.add('OPAQUE_CONDITION' if op == 'opaque' else 'UNCLASSIFIED_CONDITION')
    return 'U'

def registration(data, assignment, gaps, flat=False):
    state = {}
    steps = data['steps']
    assert len({s['id'] for s in steps}) == len(steps), 'Duplicate definition identity'
    for i, step in enumerate(steps):
        if i >= min(data.get('maxSteps', 32), 32):
            gaps.add('REGISTRATION_STEP_LIMIT')
            # No unvisited result is declared false; earlier observations remain in the trace.
            return 'U'
        state[step['id']] = condition(step['condition'], assignment, {} if flat else state, gaps)
    return state.get(data['query'], 'F')

def classify(values):
    if not values:
        return 'INVALID_MODEL'
    if all(v == 'T' for v in values):
        return 'MUST'
    if all(v == 'F' for v in values):
        return 'NEVER'
    if 'T' in values and 'F' in values:
        return 'MAY'
    return 'UNKNOWN'

def binding(data):
    # Pre-filtered, complete, single-context candidate set. Qualifier/type extraction is not modeled here.
    candidates = data['candidates']
    if not candidates:
        return 'UNSATISFIED'
    primary = [c for c in candidates if c.get('primary')]
    if primary:
        return primary[0]['id'] if len(primary) == 1 else 'AMBIGUOUS'
    if data['version'] == '6.2.0':
        regular = [c for c in candidates if not c.get('fallback')]
        if len(regular) == 1:
            return regular[0]['id']
    if len(candidates) == 1:
        return candidates[0]['id']
    names = [c for c in candidates if c['id'] == data['name']]
    priorities = [c for c in candidates if 'priority' in c]
    best = [] if not priorities else [c for c in priorities if c['priority'] == min(p['priority'] for p in priorities)]
    if data['version'] == '6.2.0' and names:
        return names[0]['id']
    if best:
        return best[0]['id'] if len(best) == 1 else 'AMBIGUOUS'
    return names[0]['id'] if names else 'AMBIGUOUS'

def space(data, gaps):
    domains = data['domains']
    keys = sorted(domains)
    if len(keys) > 12:
        gaps.add('CONFIGURATION_VARIABLE_LIMIT')
        return {'region': 'UNKNOWN', 'values': []}, []
    assert all(domains[k] for k in keys)
    assert all(len({canonical(v) for v in domains[k]}) == len(domains[k]) for k in keys)
    count = 0 if data.get('feasible') is False else math.prod(len(domains[k]) for k in keys)
    if count > 4096:
        gaps.add('CONFIGURATION_SPACE_LIMIT')
        return {'region': 'UNKNOWN', 'values': []}, []
    worlds = [] if count == 0 else [dict(zip(keys, row)) for row in itertools.product(*(domains[k] for k in keys))]
    values = [registration(data, w, gaps) for w in worlds]
    witnesses = []
    baseline = data['baseline']
    assert set(baseline) == set(keys) and all(baseline[k] in domains[k] for k in keys)
    for truth in ('T', 'F'):
        matching = [w for w, v in zip(worlds, values) if v == truth]
        if not matching:
            continue
        # Exhaustive minimum number of changed assignments relative to an explicit complete baseline.
        deltas = [{k: w[k] for k in keys if w[k] != baseline[k]} for w in matching]
        delta = min(deltas, key=lambda d: (len(d), canonical(d)))
        replay = dict(baseline, **delta)
        assert replay in worlds and registration(data, replay, set()) == truth
        assert not any(registration(data, dict(baseline, **{k:v for k,v in delta.items() if k != remove}), set()) == truth for remove in delta)
        witnesses.append({'truth': truth, 'baseline': baseline, 'delta': delta, 'assignment': replay,
                          'order': [s['id'] for s in data['steps']], 'replayDigest': sha(canonical({'assignment': replay, 'truth': truth}))})
    return {'region': classify(values), 'values': values}, witnesses

def evaluate(case):
    data, kind, gaps = case['input'], case['kind'], set()
    witnesses = []
    if kind == 'condition':
        actual = condition(data['expr'], data.get('assignment', {}), {}, gaps)
    elif kind == 'registration':
        actual = registration(data, data.get('assignment', {}), gaps)
    elif kind == 'region':
        actual = classify(data['values'])
    elif kind == 'binding':
        actual = binding(data)
    elif kind == 'precedence':
        merged = {}
        for source in data['sources']:
            merged.update(source)
        actual = merged.get(data['key'])
    elif kind == 'space':
        actual, witnesses = space(data, gaps)
    else:
        raise ValueError('Unregistered fixture kind')
    return dict(id=case['id'], actual=actual, expected=case['expected'],
                status='PASS' if actual == case['expected'] else 'DISAGREEMENT', gaps=sorted(gaps), witnesses=witnesses)

def satisfies(clauses, assignment):
    return all(any(assignment[abs(lit)] == (lit > 0) for lit in clause) for clause in clauses)

def exhaustive(clauses, n):
    count = 0
    for row in itertools.product((False, True), repeat=n):
        count += 1
        assignment = dict(enumerate(row, 1))
        if satisfies(clauses, assignment):
            return assignment, count
    return None, count

def dpll(clauses, n):
    nodes = 0
    def search(remaining, assigned):
        nonlocal nodes
        nodes += 1
        if any(not c for c in remaining):
            return None
        if not remaining:
            return {v: assigned.get(v, False) for v in range(1, n + 1)}
        units = [c[0] for c in remaining if len(c) == 1]
        if units:
            choices = [units[0]]
        else:
            variable = min(abs(l) for c in remaining for l in c)
            choices = [-variable, variable]
        for literal in choices:
            reduced = [[l for l in c if l != -literal] for c in remaining if literal not in c]
            found = search(reduced, assigned | {abs(literal): literal > 0})
            if found is not None:
                return found
        return None
    return search(clauses, {}), nodes

def solver_experiment():
    rows, timings = [], []
    for n in (2, 4, 8, 12):
        for seed in range(16):
            clauses = []
            for i in range(2 * n):
                clause = []
                for j in range(min(3, n)):
                    v = (i * 7 + j * 3 + seed) % n + 1
                    clause.append(v if (i + j + seed) % 3 else -v)
                clauses.append(sorted(set(clause)))
            if seed % 4 == 0:
                clauses.extend([[1], [-1]])
            start = time.perf_counter_ns()
            expected, valuations = exhaustive(clauses, n)
            elapsed_exhaustive = time.perf_counter_ns() - start
            start = time.perf_counter_ns()
            candidate, nodes = dpll(clauses, n)
            elapsed_dpll = time.perf_counter_ns() - start
            valid = (expected is None) == (candidate is None) and (candidate is None or satisfies(clauses, candidate))
            rows.append({'variables': n, 'seed': seed, 'clauses': clauses, 'sat': expected is not None,
                         'candidateSat': candidate is not None, 'exhaustiveValuations': valuations, 'dpllNodes': nodes,
                         'witness': None if candidate is None else [candidate[v] for v in range(1, n+1)], 'status': 'PASS' if valid else 'DISAGREEMENT'})
            timings.append({'variables': n, 'seed': seed, 'exhaustiveNs': elapsed_exhaustive, 'dpllNs': elapsed_dpll})
    return rows, timings

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--output', required=True)
    args = parser.parse_args()
    output = Path(args.output).resolve()
    assert output.is_relative_to(ROOT.parent.parent.resolve())
    output.mkdir(parents=True, exist_ok=False)
    cases = json.loads((ROOT/'cases.json').read_text('utf-8'))['cases']
    assert len({c['id'] for c in cases}) == len(cases)
    results = [evaluate(c) for c in cases]
    solver, timings = solver_experiment()
    baselines = []
    for c in cases:
        if c['id'].startswith('order-'):
            actual = registration(c['input'], {}, set(), flat=True)
            baselines.append({'id': c['id'], 'flatActual': actual, 'expected': c['expected'], 'disagrees': actual != c['expected']})
    inputs = {p: sha((ROOT/p).read_bytes()) for p in ('cases.json', 'oracle.py', 'PROTOCOL.md')}
    result = {'schema': 'm4-r0-formal-result-v1', 'inputs': inputs, 'cases': results, 'solver': solver, 'flatBaseline': baselines}
    raw = canonical(result)
    (output/'results.json').write_bytes(raw)
    (output/'timing.json').write_bytes(canonical({'python': platform.python_version(), 'samples': timings}))
    summary = {'formalCases': len(results), 'formalPassed': sum(r['status']=='PASS' for r in results),
               'cnfCases': len(solver), 'cnfPassed': sum(r['status']=='PASS' for r in solver),
               'flatDisagreements': sum(r['disagrees'] for r in baselines), 'sha256': sha(raw)}
    (output/'summary.json').write_bytes(canonical(summary))
    print(json.dumps(summary))
    raise SystemExit(0 if all(r['status']=='PASS' for r in results+solver) else 1)

if __name__ == '__main__':
    main()
