"""Trusted local microfixture runner. No Maven, target project or transitive resolution."""
import argparse, hashlib, json, os, platform, subprocess, time
from pathlib import Path
ROOT=Path(__file__).resolve().parent
def digest(data): return hashlib.sha256(data).hexdigest()
def main():
    parser=argparse.ArgumentParser()
    parser.add_argument('--output',required=True)
    args=parser.parse_args()
    out=Path(args.output).resolve()
    assert out.is_relative_to(ROOT.parent.parent.resolve())
    out.mkdir(parents=True,exist_ok=False)
    labels=json.loads((ROOT/'runtime-labels.json').read_text('utf-8'))['cases']
    lock=json.loads((ROOT/'artifacts.lock.json').read_text('utf-8'))
    jars={}
    for a in lock['artifacts']:
        path=ROOT/'.cache'/'jars'/a['file']
        assert digest(path.read_bytes())==a['sha256']
        jars[a['file']]=path
    sources=sorted((ROOT/'src/test/resources/fixtures').glob('*.java'))+[ROOT/'RuntimeProbe.java']
    inputs=[{'path':p.relative_to(ROOT).as_posix(),'sha256':digest(p.read_bytes())} for p in sources]
    for p in [ROOT/'src/test/resources/fixtures/legacy.xml',ROOT/'runtime-labels.json',ROOT/'artifacts.lock.json',ROOT/'run_runtime.py',ROOT/'PROTOCOL.md']:
        inputs.append({'path':p.relative_to(ROOT).as_posix(),'sha256':digest(p.read_bytes())})
    rows=[]; runs=[]
    for fw,boot in [('5.3.31','2.7.18'),('6.1.14','3.3.5'),('6.2.0','3.4.0')]:
        selected=[p for n,p in jars.items() if n.endswith('-'+fw+'.jar') or n.endswith('-'+boot+'.jar') or 'annotation-api' in n]
        selected.sort(key=lambda p:p.name)
        classes=out/('classes-'+fw); classes.mkdir()
        cp=os.pathsep.join(str(p) for p in selected)
        compile_cmd=['javac','-proc:none','-parameters','--release','17','-cp',cp,'-d',str(classes),*map(str,sources)]
        start=time.monotonic()
        try:
            compiled=subprocess.run(compile_cmd,capture_output=True,text=True,timeout=30)
            (out/('compile-'+fw+'.txt')).write_text(compiled.stdout+compiled.stderr,encoding='utf-8')
            assert compiled.returncode==0, 'COMPILE_FAILED'
            run_cmd=['java','-Xmx128m','-Djava.awt.headless=true','-cp',str(classes)+os.pathsep+cp,'research.RuntimeProbe',str(ROOT/'src/test/resources/fixtures/legacy.xml'),fw]
            result=subprocess.run(run_cmd,capture_output=True,text=True,timeout=30)
            (out/('stdout-'+fw+'.jsonl')).write_text(result.stdout,encoding='utf-8',newline='\n')
            (out/('stderr-'+fw+'.txt')).write_text(result.stderr,encoding='utf-8')
            assert result.returncode==0, 'RUNTIME_FAILED'
            observations=[json.loads(line) for line in result.stdout.splitlines() if line]
            assert len({o['id'] for o in observations})==len(observations),'DUPLICATE_OBSERVATION'
            actual={o['id']:o['actual'] for o in observations}
            expected=[c for c in labels if c['version']==fw]
            assert set(actual)=={c['id'] for c in expected}, 'DENOMINATOR_MISMATCH'
            rows.extend(dict(c,actual=actual[c['id']],status='PASS' if c['expected']==actual[c['id']] else 'DISAGREEMENT') for c in expected)
        except (AssertionError,subprocess.TimeoutExpired,OSError) as error:
            reason = str(error) if isinstance(error,AssertionError) else 'PROCESS_TIMEOUT' if isinstance(error,subprocess.TimeoutExpired) else 'PROCESS_UNAVAILABLE'
            rows.extend(dict(c,status=type(error).__name__,reason=reason) for c in labels if c['version']==fw)
        runs.append({'framework':fw,'boot':boot,'seconds':round(time.monotonic()-start,6),'classpath':[p.name for p in selected]})
    canonical=json.dumps({'schema':'m4-r0-runtime-result-v1','inputs':sorted(inputs,key=lambda p:p['path']),'cases':rows},sort_keys=True,separators=(',',':')).encode()
    (out/'runtime-results.json').write_bytes(canonical)
    environment={'python':platform.python_version(),'java':subprocess.run(['java','-version'],capture_output=True,text=True).stderr.strip(),'runs':runs}
    (out/'environment.json').write_text(json.dumps(environment,indent=2)+'\n',encoding='utf-8',newline='\n')
    summary={'cases':len(rows),'passed':sum(r['status']=='PASS' for r in rows),'sha256':digest(canonical)}
    (out/'summary.json').write_text(json.dumps(summary,sort_keys=True,indent=2)+'\n',encoding='utf-8',newline='\n')
    print(json.dumps(summary))
    raise SystemExit(0 if summary['cases']==summary['passed'] else 1)
if __name__=='__main__': main()
