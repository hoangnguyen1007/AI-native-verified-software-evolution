"""Actual TS checker through the neutral SDK recipe; authored inputs only."""
import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest

WORKSPACE=Path(__file__).resolve().parents[4];BASE=WORKSPACE/'analyzer-execution/src/main/resources/polyglot'
def handle(file):return dict(digest='sha256:'+hashlib.sha256(file.read_bytes()).hexdigest(),size=file.stat().st_size)

class TypeScriptNativeLane(unittest.TestCase):
    def test_each_pack_preserves_census_full_positions_native_bindings_and_callers(self):
        cases=json.loads((WORKSPACE/'analyzer-execution/src/test/resources/polyglot/typescript-architecture-cases.json').read_text(encoding='utf-8'))['cases'];node=Path(shutil.which('node'));sdk=WORKSPACE/'.evo/tools/typescript-5.9.3/package'
        for case in cases.values():
            with self.subTest(language=case['language']),tempfile.TemporaryDirectory() as directory:
                root=Path(directory);(root/case['path']).write_bytes(case['text'].encode());request=dict(schema='native-typescript-request:m8.4-v1',language=case['language'],dialect='5.9.3',scope='sha256:'+'1'*64,tool='sha256:'+'2'*64,provider=dict(id='frontend.'+case['language']+'-native',version='m8.4-v1'),repositoryScope='urn:test:authored-ts-native',packageScope='app',sourceRoot=str(root),workRoot=str(root/'state'),files=[dict(path=case['path'],text=case['text'],sha256='sha256:'+hashlib.sha256(case['text'].encode()).hexdigest())],options=dict(target='ES2022',module='CommonJS',moduleResolution='Node10',strict=True,allowJs=True,checkJs=True),node=handle(node),compiler=handle(sdk/'lib/typescript.js'),worker=handle(BASE/'typescript-worker.cjs'));file=root/'request.json';file.write_text(json.dumps(request));result=subprocess.run([sys.executable,'-I',str(BASE/'typescript_engine.py'),'--request',str(file),'--node',str(node),'--sdk',str(sdk)],stdout=subprocess.PIPE,stderr=subprocess.PIPE,timeout=45);self.assertEqual(0,result.returncode,result.stderr.decode());rows=[json.loads(line) for line in result.stdout.splitlines()];self.assertEqual('language-capture-result:m8.4-v1',rows[0]['schema']);census={r['key']:r for r in rows if r.get('kind')=='CENSUS'};observations={r['key']:r for r in rows if r.get('kind')=='OBSERVATION'};self.assertEqual(census.keys(),observations.keys());self.assertEqual({f'A{i:02}' for i in range(1,13)},{r['fields']['category'] for r in census.values()})
                caller=next(s for r in observations.values() if census[r['key']]['fields']['category']=='A02' for s in r['fields'].get('symbols',[]) if s['name']=='run')
                calls=[r['fields'] for r in observations.values() if r['fields']['role']=='call'];call=next(f for f in calls if case['text'][f['span']['start']:f['span']['end']]==case['expected']['call']);self.assertEqual(caller['id'],call['caller']);self.assertEqual('RESOLVED',call['callerStatus']);self.assertEqual(case['path'],call['span']['path']);self.assertEqual(['twice'],[s['name'] for s in call['symbols']]);self.assertEqual('CONDITIONAL' if case['language']=='typescript' else 'RESOLVED',call['status']);self.assertEqual(len(census),rows[-1]['census'])

if __name__=='__main__':unittest.main()
