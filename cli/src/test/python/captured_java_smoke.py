"""Registered capture -> packaged CLI -> durable graph -> all scoped gap pages.

No target build, signer, test classpath or missing-fixture fallback is available.
"""
import argparse
import json
from pathlib import Path
import subprocess


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('jar', type=Path)
    parser.add_argument('registration', type=Path)
    args = parser.parse_args()

    def run(*command):
        result = subprocess.run(['java', '-Xmx1536m', '-jar', str(args.jar), *command],
                                capture_output=True, timeout=600)
        assert result.returncode in (0, 3), (result.returncode, result.stderr[:600])
        return result.returncode, json.loads(result.stdout)['data']

    code, result = run('captured-java', str(args.registration), '--json')
    summary = result['semantic']
    assert code == (0 if summary['state'] == 'SUCCEEDED' else 3)
    graph = args.registration / 'graphs' / (summary['graph'][7:] + '.graph.json')
    contexts = json.loads(graph.read_text(encoding='utf-8'))['graph']['contexts']
    counts = {}
    for view in ('CURRENT', 'HISTORY', 'ALL'):
        union = set()
        for context in contexts:
            scoped = set()
            cursor = None
            while True:
                command = ['gaps', '--graph', str(graph), '--context', context['identity'],
                           '--gap-view', view, '--limit', '50', '--json']
                if cursor:
                    command.extend(['--cursor', cursor])
                _, page = run(*command)
                ids = [row['identity'] for row in page['items']]
                assert len(ids) == len(set(ids)) and not scoped.intersection(ids)
                scoped.update(ids)
                cursor = page['continuation']
                if not cursor:
                    break
                assert ids, 'An open page must advance its cursor'
            union.update(scoped)
        counts[view] = len(union)
        assert counts[view] == summary[view.lower()]
    assert counts['ALL'] == counts['CURRENT'] + counts['HISTORY']
    assert counts['CURRENT'] == summary['jobGaps']
    print(json.dumps(dict(result='PASS', processExit=code, graph=summary['graph'],
                          qualification=summary['qualification'], gapViews=counts)))


if __name__ == '__main__':
    main()
