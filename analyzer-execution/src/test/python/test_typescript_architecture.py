"""Pinned real TS 5.9.3 checker over platform-authored TS/JS fixture strings.
No analyzed repository, plugin, lifecycle or user application executes here."""
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import unittest

WORKSPACE=Path(__file__).resolve().parents[4]
BASE=WORKSPACE/'analyzer-execution/src'

class TypeScriptArchitecture(unittest.TestCase):
    def analyze(self,case):
        compiler=WORKSPACE/'.evo/tools/typescript-5.9.3/package/lib/typescript.js';self.assertTrue(compiler.is_file());pin=json.loads((BASE/'main/resources/polyglot/typescript-tool.json').read_text());self.assertEqual(pin['compilerSha256'],hashlib.sha256(compiler.read_bytes()).hexdigest());node=shutil.which('node');self.assertIsNotNone(node)
        files=[dict(path=case['path'],text=case['text'],sha256='sha256:'+hashlib.sha256(case['text'].encode()).hexdigest())]
        for path in sorted(compiler.parent.glob('lib.*.d.ts')):
            raw=path.read_bytes();files.append(dict(path='toolchain/lib/'+path.name,text=raw.decode(),sha256='sha256:'+hashlib.sha256(raw).hexdigest()))
        request=dict(schema='typescript-capture-request:m8.4-v1',scope='sha256:'+'1'*64,repositoryScope='urn:test:authored-ts-architecture',packageScope='app',language=case['language'],projectFiles=[case['path']],files=files,options=dict(target='ES2022',module='CommonJS',moduleResolution='Node10',strict=True,allowJs=True,checkJs=True))
        worker=Path(os.environ.get('POLYGLOT_TS_WORKER',str(BASE/'main/resources/polyglot/typescript-worker.cjs')));result=subprocess.run([node,str(worker),str(compiler)],input=json.dumps(request).encode(),stdout=subprocess.PIPE,stderr=subprocess.PIPE,timeout=45);self.assertEqual(0,result.returncode,result.stderr.decode());return [json.loads(line) for line in result.stdout.splitlines()]
    def test_javascript_inferred_types_are_not_reported_as_an_absent_category(self):
        case=dict(language='javascript',path='main.js',text='/** @param {number} n */\nexport function twice(n) {return n*2;}\nexport const answer=twice(4);\n');rows=self.analyze(case);questions={r['key']:r for r in rows if r.get('kind')=='CENSUS'};inferred=[r['fields'] for r in rows if r.get('kind')=='OBSERVATION' and questions[r['key']]['fields']['category']=='A04'];self.assertTrue(any(r['status']=='RESOLVED' and r['type']=='number' for r in inferred),inferred)
    def test_each_language_accounts_for_written_and_inferred_architecture_categories(self):
        cases=json.loads((BASE/'test/resources/polyglot/typescript-architecture-cases.json').read_text(encoding='utf-8'))['cases']
        for name,case in cases.items():
            with self.subTest(language=case['language']):
                rows=self.analyze(case);questions={r['key']:r for r in rows if r.get('kind')=='CENSUS'};observations={r['key']:r for r in rows if r.get('kind')=='OBSERVATION'};self.assertEqual(questions.keys(),observations.keys());self.assertEqual(12,len(case['expected']['categoryControls']))
                for category,control in case['expected']['categoryControls'].items():
                    emitted=[dict(observations[k]['fields'],role=q['fields']['role']) for k,q in questions.items() if q['fields']['category']==category];self.assertTrue(emitted,category)
                    if control['written']:self.assertTrue(any(f['status'] not in ('NOT_APPLICABLE','ERROR') and f['role'] not in ('absent-category','implicit-generation-census') for f in emitted),(name,category))
                calls=[observations[k]['fields'] for k,q in questions.items() if q['fields']['category']=='A06' and q['fields']['role']=='call'];call=next(f for f in calls if case['text'][f['span']['start']:f['span']['end']]==case['expected']['call']);self.assertEqual('CONDITIONAL' if case['language']=='typescript' else 'RESOLVED',call['status']);self.assertEqual(['twice'],[s['name'] for s in call['symbols']]);self.assertEqual([],rows[-1]['diagnostics']);guards=[observations[k]['fields'] for k,q in questions.items() if q['fields']['category']=='A10' and q['fields']['role']=='guard'];self.assertTrue(guards);self.assertTrue(all(f.get('truth')=='UNKNOWN' for f in guards))

if __name__=='__main__':unittest.main()
