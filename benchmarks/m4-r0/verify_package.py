"""Offline integrity/denominator and introduced-link checks for the final research package."""
import hashlib, json, re
from pathlib import Path
ROOT=Path(__file__).resolve().parent
REPO=ROOT.parent.parent
EVIDENCE=REPO/'docs/reproducibility/m4-r0-2026-09-11'
def sha(path): return hashlib.sha256(path.read_bytes()).hexdigest()
def read(path): return json.loads(path.read_text('utf-8'))
def canonical(value): return json.dumps(value,ensure_ascii=False,sort_keys=True,separators=(',',':')).encode()
def main():
    checks={}
    artifacts=read(ROOT/'artifacts.lock.json')['artifacts']
    assert len(artifacts)==26 and len({a['coordinate'] for a in artifacts})==26
    for a in artifacts:
        p=ROOT/'.cache/jars'/a['file']
        assert sha(p)==a['sha256'] and p.stat().st_size==a['bytes']
    checks['artifactHashes']=len(artifacts)
    sources=read(ROOT/'sources-acquired.lock.json')['sources']
    for s in sources:
        if s['status']=='ACQUIRED':
            p=ROOT/'.cache/sources'/(s['id']+'.txt')
            assert sha(p)==s['sha256'] and p.stat().st_size==s['bytes']
    checks['sourceHashes']=sum(s['status']=='ACQUIRED' for s in sources)
    assert checks['sourceHashes']==34
    catalog=read(ROOT/'mechanisms.json')['mechanisms']
    ids={m['id'] for m in catalog}
    assert len(ids)==len(catalog)==29 and 'spring.mechanism.unclassified' in ids
    documented=set(re.findall(r'^\| `(spring\.[a-z.-]+)` \|', (REPO/'docs/architecture/m4-spring-intelligence.md').read_text('utf-8'),re.M))
    assert documented==ids, (ids-documented, documented-ids)
    checks['catalogRows']=len(catalog)
    census=read(ROOT/'annotation-census.json')
    assert census['classFiles']==15594 and len(census['annotations'])==402
    assert len({(a['artifact'],a['entry']) for a in census['annotations']})==402
    assert all(set(a['candidateFamilies'])<=ids for a in census['annotations'])
    checks['annotationDeclarations']=len(census['annotations'])
    runtime=read(EVIDENCE/'runtime-run-2/runtime-results.json')
    formal=read(EVIDENCE/'oracle-run-3/results.json')
    for i in runtime['inputs']: assert sha(ROOT/i['path'])==i['sha256']
    for p,digest in formal['inputs'].items(): assert sha(ROOT/p)==digest
    assert len(runtime['cases'])==79 and all(c['status']=='PASS' for c in runtime['cases'])
    assert len(formal['cases'])==70 and all(c['status']=='PASS' for c in formal['cases'])
    assert len(formal['solver'])==64 and all(c['status']=='PASS' for c in formal['solver'])
    assert (EVIDENCE/'runtime-run-2/runtime-results.json').read_bytes()==(EVIDENCE/'runtime-run-3/runtime-results.json').read_bytes()
    assert (EVIDENCE/'oracle-run-3/results.json').read_bytes()==(EVIDENCE/'oracle-run-4/results.json').read_bytes()
    checks.update(runtimeCases=79,formalCases=70,cnfCases=64,semanticReplay='BYTE_IDENTICAL')
    gapdir=EVIDENCE/'gap-run-2'
    gaps=read(gapdir/'capability-gaps.json')
    snapshot=read(gapdir/'study-snapshot.json')
    observations=(ROOT/'gap-observations.tsv').read_text('utf-8').splitlines()
    payloads={'sha256:'+hashlib.sha256(row.encode()).hexdigest() for row in observations}
    assert len(gaps)==len(observations)==452
    assert len({g['gapIdentity'] for g in gaps})==452
    for g in gaps:
        assert g['schemaVersion']=='capability-gap-record-v1'
        assert g['context']['snapshotIdentity']==snapshot['identity']
        assert g['sourceSpans']==[] and g['evidenceRequirements'] and g['affectedOutputs']
        for observation in g['observationReferences']:
            assert observation['sourceResultIdentity']=='sha256:'+sha(ROOT/'gap-observations.tsv')
            assert observation['payloadDigest'] in payloads
    for f in snapshot['files']:
        name=f['path']
        path=(REPO/'analyzer/src/main/java'/name.removeprefix('platform-contract-source/')) if name.startswith('platform-contract-source/') else ROOT/name
        assert 'sha256:'+sha(path)==f['contentDigest'], name
    assert (gapdir/'capability-gaps.json').read_bytes()==(EVIDENCE/'gap-run-3/capability-gaps.json').read_bytes()
    checks.update(capabilityGapRecords=452,gapReplay='BYTE_IDENTICAL')
    changed=['docs/current-state.md','docs/project-context.md','docs/roadmap.md',
             'docs/architecture/m4-spring-intelligence.md','docs/architecture/conditional-architecture-semantics.md',
             'docs/research/research-questions.md']
    new=['docs/architecture/m4-r0-semantics-gate.md','docs/research/2026-09-11-m4-r0-spring-semantics.md',
         'docs/reproducibility/m4-r0-2026-09-11/README.md','benchmarks/m4-r0/README.md','benchmarks/m4-r0/PROTOCOL.md']
    links=0
    for name in changed+new:
        path=REPO/name
        before=ROOT/'.cache/before'/name
        text=path.read_text('utf-8')
        old=before.read_text('utf-8') if before.exists() else ''
        for label,target in re.findall(r'\[([^]\n]+)\]\(([^)\n]+)\)',text):
            if target.startswith(('http:','https:','#','mailto:')) or ']('+target+')' in old: continue
            local=target.split('#',1)[0]
            assert (path.parent/local).exists(), (name,target)
            links+=1
    checks['introducedLocalLinks']=links
    print(json.dumps(checks,sort_keys=True))
if __name__=='__main__': main()
