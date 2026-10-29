"""Actual analyzer 7.3.0 / Dart 3.7.0 controls over authored source only."""
import hashlib
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

WORKSPACE=Path(__file__).resolve().parents[4];SOURCE=WORKSPACE/'analyzer-execution/src/main/resources/polyglot/dart_engine.py'


def handle(file):return dict(size=file.stat().st_size,digest='sha256:'+hashlib.sha256(file.read_bytes()).hexdigest())


class DartNativeControls(unittest.TestCase):
    def test_same_signature_closures_keep_distinct_native_callers_and_full_scopes(self):
        text='int twice(int n)=>n*2;\nint run(){final a=(int n)=>twice(n); final b=(int n)=>twice(n);return a(1)+b(2);}\n';rows=self.analyze({'main.dart':text});closures=[s for r in rows if r.get('kind')=='OBSERVATION' and r['fields'].get('role')=='anonymous-function' for s in r['fields']['symbols']];self.assertEqual(2,len(closures));self.assertEqual(2,len({s['id'] for s in closures}));calls=[r['fields'] for r in rows if r.get('kind')=='OBSERVATION' and r['fields'].get('role')=='call' and r['fields'].get('span') and text[r['fields']['span']['start']:r['fields']['span']['end']]=='twice(n)'];self.assertEqual(2,len(calls))
        for call in calls:
            enclosing=[s for s in closures if s['span']['start']<=call['span']['start']<call['span']['end']<=s['span']['end']];self.assertEqual(1,len(enclosing));self.assertEqual(enclosing[0]['id'],call['caller'])
    def test_native_full_declaration_range_is_distinct_from_the_name_anchor(self):
        text='int twice(int n) => n*2;\nint run()=>twice(4);\n';rows=self.analyze({'main.dart':text});symbol=self.call(rows,{'main.dart':text},'twice(4)')['symbols'][0];span=symbol['span'];self.assertEqual('int twice(int n) => n*2;',text[span['start']:span['end']]);self.assertEqual('FULL_NATIVE_DECLARATION',symbol['locationPrecision'])
    def test_native_heritage_expands_class_alias_and_rejects_callable_alias(self):
        rows=self.analyze({'main.dart':'class Base {}\ntypedef Alias = Base;\nclass Child extends Alias {}\n'});questions={r['key'] for r in rows if r.get('kind')=='CENSUS' and r['fields']['category']=='A05'};facts=[r['fields'] for r in rows if r.get('kind')=='OBSERVATION' and r['key'] in questions];self.assertEqual(1,len(facts));self.assertEqual('RESOLVED',facts[0]['status']);self.assertEqual(['Base'],[s['name'] for s in facts[0]['symbols']]);rows=self.analyze({'main.dart':'typedef Callback = void Function();\nclass Broken extends Callback {}\n'});questions={r['key'] for r in rows if r.get('kind')=='CENSUS' and r['fields']['category']=='A05'};facts=[r['fields'] for r in rows if r.get('kind')=='OBSERVATION' and r['key'] in questions];self.assertEqual(1,len(facts));self.assertNotEqual('RESOLVED',facts[0]['status'])
    def test_native_mixin_constraints_extension_owner_and_missing_heritage(self):
        text='class Base {}\nmixin Named on Base {}\nextension Extra on Base { int twice(int n)=>n*2; }\nclass Broken extends Missing {}\n';rows=self.analyze({'main.dart':text});questions={r['key']:r['fields'] for r in rows if r.get('kind')=='CENSUS'};facts=[r['fields'] for r in rows if r.get('kind')=='OBSERVATION' and questions[r['key']]['category']=='A05'];self.assertEqual(3,len(facts));known=[f for f in facts if f['status']=='RESOLVED'];self.assertEqual({'SUPERCLASS_CONSTRAINT','ON_TYPE'},{f['relation'] for f in known});self.assertTrue(all([s['name'] for s in f['symbols']]==['Base'] for f in known));self.assertEqual(1,len([f for f in facts if f['status']=='UNRESOLVED' and not f['targets']]))
    def test_native_heritage_retains_each_relation_owner_and_exact_target(self):
        files={'main.dart':'class Base<T> {}\nmixin Named {}\nabstract class Contract {}\nclass Child extends Base<int> with Named implements Contract {}\n'};rows=self.analyze(files);questions={r['key'] for r in rows if r.get('kind')=='CENSUS' and r['fields']['category']=='A05'};facts=[r['fields'] for r in rows if r.get('kind')=='OBSERVATION' and r['key'] in questions];child=next(s for r in rows if r.get('kind')=='OBSERVATION' and r['fields']['role']=='declaration' for s in r['fields']['symbols'] if s['name']=='Child');self.assertEqual(3,len(facts));self.assertEqual({'Base','Named','Contract'},{s['name'] for f in facts for s in f['symbols']});self.assertEqual({'EXTENDS','WITH_MIXIN','IMPLEMENTS'},{f.get('relation') for f in facts})
        for fact in facts:self.assertEqual('RESOLVED',fact['status']);self.assertEqual(child['id'],fact['owner']);self.assertEqual(1,len(fact['targets']))
    def analyze(self,files):
        sdk=WORKSPACE/'.evo/tools/m845-dart-win-3.7.0/dart-sdk';dart=sdk/'bin/dart.exe';packages=WORKSPACE/'.evo/tools/m845-dart-analyzer-7.3.0/.dart_tool/package_config.json';engine=SOURCE.parent/'dart/dart_engine.dart'
        if not dart.is_file() or not packages.is_file():raise RuntimeError('Pinned real Dart/analyzer SDK required')
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory);source=root/'src';source.mkdir();work=root/'state'
            for name,text in files.items():(source/name).write_bytes(text.encode('utf8'))
            request=dict(schema='native-dart-request:m8.4-v1',language='dart',dialect='3.7',scope='sha256:'+'1'*64,tool='sha256:'+hashlib.sha256(engine.read_bytes()).hexdigest(),provider={'id':'frontend.dart','version':'3.7.0-analyzer7.3.0-m8.4-v3'},repositoryScope='urn:test:authored-dart',packageScope='fixture',sourceRoot=str(source),workRoot=str(work),sdkPath=str(sdk),runtime=handle(dart),packages=handle(packages),engine=handle(engine),files=[dict(path=name,text=text,sha256='sha256:'+hashlib.sha256(text.encode('utf8')).hexdigest()) for name,text in sorted(files.items())])
            file=root/'request.json';file.write_text(json.dumps(request),encoding='utf8');result=subprocess.run([sys.executable,'-I',str(SOURCE),'--request',str(file),'--dart',str(dart),'--packages',str(packages),'--engine',str(engine)],stdout=subprocess.PIPE,stderr=subprocess.PIPE,timeout=210)
            if result.returncode and (work/'dart-native.stderr').is_file():detail=(work/'dart-native.stderr').read_bytes()[-8192:]
            else:detail=result.stderr[-8192:]
            self.assertEqual(0,result.returncode,detail.decode(errors='replace'));rows=[json.loads(line) for line in result.stdout.decode('utf8').splitlines()];self.assertEqual({f'A{i:02}' for i in range(1,13)},{r['fields']['category'] for r in rows if r.get('kind')=='CENSUS'});self.assertEqual(rows[-1]['census'],rows[-1]['observations']);return rows
    def call(self,rows,files,written):return next(r['fields'] for r in rows if r.get('kind')=='OBSERVATION' and r['fields']['role']=='call' and r['fields']['span'] and files[r['fields']['span']['path']].encode('utf-16-le')[r['fields']['span']['start']*2:r['fields']['span']['end']*2].decode('utf-16-le')==written)
    def test_native_crossfile_generic_target_and_original_nonbmp_crlf_span(self):
        files={'service.dart':'T twice<T extends num>(T n) => (n*2) as T;\n','main.dart':'import "service.dart";\r\nint run() { const marker="😀"; return twice(4); }\r\n'};rows=self.analyze(files);call=self.call(rows,files,'twice(4)');self.assertEqual('RESOLVED',call['status']);self.assertEqual('twice',call['symbols'][0]['name']);self.assertEqual('PROJECT',call['symbols'][0]['origin']);self.assertIn('typeparams=1',call['symbols'][0]['signature'])
    def test_missing_and_dynamic_calls_never_receive_invented_targets(self):
        files={'main.dart':'void run(dynamic target) { missing(3); target.work(); }\n'};rows=self.analyze(files)
        for written in ('missing(3)','target.work()'):
            call=self.call(rows,files,written);self.assertEqual('UNRESOLVED',call['status']);self.assertFalse(call['targets'])
        self.assertTrue(any(d['severity']=='ERROR' for d in rows[-1]['diagnostics']))
    def test_syntax_failure_keeps_opaque_document_categories(self):
        rows=self.analyze({'main.dart':'int broken( {\n'});observed=[r['fields'] for r in rows if r.get('kind')=='OBSERVATION'];self.assertTrue(any(r['status']=='ERROR' for r in observed));self.assertFalse(any(r['status']=='RESOLVED' and r['targets'] for r in observed))
    def test_original_utf8_bom_and_private_state_read_write(self):
        files={'main.dart':'\ufeffint twice(int n)=>n*2;\r\nint run(){var count=1;count+=2;return twice(count);}\r\n'};rows=self.analyze(files);self.assertEqual('RESOLVED',self.call(rows,files,'twice(count)')['status']);self.assertTrue(any(r['fields'].get('access')=='READ_WRITE' for r in rows if r.get('kind')=='OBSERVATION'))


if __name__=='__main__':unittest.main()
