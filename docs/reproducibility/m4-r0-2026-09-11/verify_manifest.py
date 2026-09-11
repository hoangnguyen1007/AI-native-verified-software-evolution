"""Create once, or verify, the M4-R0 deliverable byte manifest."""
import argparse, hashlib, json
from pathlib import Path
HERE=Path(__file__).resolve().parent
REPO=HERE.parents[2]
MANIFEST=HERE/'manifest.json'
def sha(data): return hashlib.sha256(data).hexdigest()
def canonical(value): return json.dumps(value,sort_keys=True,separators=(',',':')).encode('utf-8')
def main():
    parser=argparse.ArgumentParser()
    parser.add_argument('--create',action='store_true')
    args=parser.parse_args()
    if args.create:
        assert not MANIFEST.exists(), 'Refusing to overwrite evidence manifest'
        files=[]
        selected=set()
        for directory in [REPO/'benchmarks/m4-r0',HERE]:
            for p in directory.rglob('*'):
                if not p.is_file() or p.is_symlink(): continue
                rel=p.relative_to(REPO)
                if '.cache' in rel.parts or '__pycache__' in rel.parts or p.suffix=='.class': continue
                selected.add(p)
        for name in ['docs/current-state.md','docs/project-context.md','docs/roadmap.md',
                     'docs/architecture/m4-spring-intelligence.md','docs/architecture/conditional-architecture-semantics.md',
                     'docs/research/research-questions.md','docs/architecture/m4-r0-semantics-gate.md',
                     'docs/research/2026-09-11-m4-r0-spring-semantics.md']:
            selected.add(REPO/name)
        for p in sorted(selected,key=lambda p:p.relative_to(REPO).as_posix()):
            data=p.read_bytes()
            files.append({'path':p.relative_to(REPO).as_posix(),'bytes':len(data),'sha256':sha(data)})
        body={'schema':'m4-r0-deliverable-manifest-v1','files':files}
        MANIFEST.write_bytes(canonical(dict(body,manifestPayloadSha256=sha(canonical(body)))))
    content=json.loads(MANIFEST.read_text('utf-8'))
    digest=content.pop('manifestPayloadSha256')
    assert sha(canonical(content))==digest
    for row in content['files']:
        p=(REPO/row['path']).resolve()
        assert p.is_relative_to(REPO) and not p.is_symlink()
        data=p.read_bytes()
        assert len(data)==row['bytes'] and sha(data)==row['sha256'], row['path']
    print(json.dumps({'filesVerified':len(content['files']),'manifestPayloadSha256':digest}))
if __name__=='__main__': main()
