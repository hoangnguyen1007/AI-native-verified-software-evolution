"""Actual Go compiler APIs over authored fixture data; no target lifecycle on the host."""
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

WORKSPACE=Path(__file__).resolve().parents[4];SOURCE=WORKSPACE/'analyzer-execution/src/main/resources/polyglot/go_engine.py'


class GoNativeControls(unittest.TestCase):
    def test_anonymous_callers_remain_partial_instead_of_becoming_the_outer_function(self):
        text='package fixture\nfunc twice(n int) int {return n*2}\nfunc run() int {fn:=func() int {return twice(4)}; return fn()}\n';rows=self.analyze({'main.go':text});call=next(r['fields'] for r in rows if r.get('kind')=='OBSERVATION' and r['fields'].get('role')=='call' and text[r['fields']['span']['start']:r['fields']['span']['end']]=='twice(4)');self.assertIsNone(call['caller']);self.assertEqual('PARTIAL',call['status']);self.assertEqual('RESOLVED',call['targetStatus']);self.assertEqual('UNRESOLVED',call['callerStatus'])
    def test_native_caller_binds_to_the_enclosing_function_declaration(self):
        text='package fixture\nfunc twice(n int) int {return n*2}\nfunc run() int {return twice(4)}\n';rows=self.analyze({'main.go':text});declared=[s for r in rows if r.get('kind')=='OBSERVATION' and r['fields'].get('role')=='declaration' for s in r['fields']['symbols'] if s['name']=='run'];calls=[r['fields'] for r in rows if r.get('kind')=='OBSERVATION' and r['fields'].get('role')=='call'];call=next(c for c in calls if text[c['span']['start']:c['span']['end']]=='twice(4)');self.assertEqual(1,len(declared));self.assertEqual(declared[0]['id'],call['caller']);self.assertEqual('RESOLVED',call['callerStatus'])
    @classmethod
    def setUpClass(cls):
        cls.sdk=WORKSPACE/'.evo/tools/m845-go-win-1.24.0/go';cls.temporary=tempfile.TemporaryDirectory();cls.engine=Path(cls.temporary.name)/'go-engine.exe'
        if not (cls.sdk/'bin/go.exe').is_file():raise RuntimeError('Pinned actual Go 1.24.0 Windows SDK required for authored controls')
        env=dict(os.environ,GOTOOLCHAIN='local',GOWORK='off',GO111MODULE='off',GOPROXY='off',CGO_ENABLED='0',GOOS='windows',GOARCH='amd64')
        result=subprocess.run([str(cls.sdk/'bin/go.exe'),'build','-o',str(cls.engine),str(SOURCE.parent/'go/go_engine.go')],env=env,stdout=subprocess.PIPE,stderr=subprocess.PIPE,timeout=120)
        if result.returncode:raise RuntimeError((result.stdout+result.stderr).decode(errors='replace')[-8192:])
    @classmethod
    def tearDownClass(cls):cls.temporary.cleanup()
    def analyze(self,files,goos='linux',tags=()):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory);source=root/'source';source.mkdir();work=root/'work'
            for name,text in files.items():(source/name).write_bytes(text.encode('utf8'))
            request=dict(schema='native-go-request:m8.4-v1',language='go',dialect='1.24',scope='sha256:'+'1'*64,tool='sha256:'+hashlib.sha256((SOURCE.parent/'go/go_engine.go').read_bytes()).hexdigest(),provider={'id':'frontend.go','version':'go1.24.0-m8.4-v1'},repositoryScope='urn:test:authored-go',packageScope='fixture',packagePath='example.test/app',goarch='amd64',goos=goos,tags=list(tags),cgoEnabled=False,sourceRoot=str(source),workRoot=str(work),exports={},files=[dict(path=name,text=text,sha256='sha256:'+hashlib.sha256(text.encode('utf8')).hexdigest()) for name,text in sorted(files.items())],engine={'size':self.engine.stat().st_size,'digest':'sha256:'+hashlib.sha256(self.engine.read_bytes()).hexdigest()})
            file=root/'request.json';file.write_text(json.dumps(request),encoding='utf8');result=subprocess.run([sys.executable,'-I',str(SOURCE),'--request',str(file),'--engine',str(self.engine)],stdout=subprocess.PIPE,stderr=subprocess.PIPE,timeout=120)
            self.assertEqual(0,result.returncode,result.stderr[-8192:].decode(errors='replace'));rows=[json.loads(line) for line in result.stdout.decode('utf8').splitlines()];self.assertEqual({f'A{i:02}' for i in range(1,13)},{r['fields']['category'] for r in rows if r.get('kind')=='CENSUS'});self.assertEqual(rows[-1]['census'],rows[-1]['observations']);return rows
    def call(self,rows,files,written):return next(r['fields'] for r in rows if r.get('kind')=='OBSERVATION' and r['fields']['role']=='call' and r['fields']['span'] and files[r['fields']['span']['path']].encode('utf-16-le')[r['fields']['span']['start']*2:r['fields']['span']['end']*2].decode('utf-16-le')==written)
    def test_native_crossfile_generic_call_and_original_crlf_nonbmp_range(self):
        files={'service.go':'package app\nfunc twice[T ~int](n T) T { return n*2 }\n','main.go':'package app\r\nfunc run() int { const marker="😀"; return twice(4) }\r\n'}
        rows=self.analyze(files);call=self.call(rows,files,'twice(4)');self.assertEqual('RESOLVED',call['status']);self.assertEqual('twice',call['symbols'][0]['name']);self.assertEqual('PROJECT',call['symbols'][0]['origin']);self.assertIn('typeparams=1',call['symbols'][0]['signature'])
    def test_interface_dispatch_and_function_pointer_do_not_claim_runtime_implementations(self):
        files={'main.go':'package app\ntype Worker interface { Work() int }\nfunc run(w Worker, callback func(int) int) int { return w.Work()+callback(3) }\n'}
        rows=self.analyze(files);interface=self.call(rows,files,'w.Work()');self.assertEqual('CONDITIONAL',interface['status']);self.assertTrue(interface['uncertainty']);self.assertEqual('INTERFACE',interface['dispatch']);indirect=self.call(rows,files,'callback(3)');self.assertEqual('UNRESOLVED',indirect['status']);self.assertEqual([],indirect['targets']);self.assertEqual('callback',indirect['calleeSymbol']['name'])
    def test_missing_import_and_call_preserve_native_diagnostics(self):
        files={'main.go':'package app\nimport "missing.example/pkg"\nfunc run() int { return pkg.Work()+missing(3) }\n'};rows=self.analyze(files);call=self.call(rows,files,'missing(3)');self.assertEqual('UNRESOLVED',call['status']);self.assertFalse(call['targets']);self.assertTrue(any(d['severity']=='ERROR' for d in rows[-1]['diagnostics']))
    def test_fields_with_same_name_in_distinct_types_keep_distinct_native_owners(self):
        files={'main.go':'package app\ntype A struct { Value int }\ntype B struct { Value int }\nfunc run(a A,b B) int { return a.Value+b.Value }\n'};rows=self.analyze(files)
        fields=[s for r in rows if r.get('kind')=='OBSERVATION' for s in r['fields']['symbols'] if s['kind']=='FIELD' and s['name']=='Value'];self.assertEqual({'example.test/app::A','example.test/app::B'},{s['owner'] for s in fields});self.assertEqual(2,len({s['id'] for s in fields}))
    def test_broken_parser_still_keeps_an_opaque_document_denominator(self):
        rows=self.analyze({'main.go':'package app\nfunc broken( {\n'});observed=[r['fields'] for r in rows if r.get('kind')=='OBSERVATION'];self.assertTrue(any(r['status']=='ERROR' for r in observed));self.assertFalse(any(r['status']=='RESOLVED' and r['targets'] for r in observed))

    def test_native_build_tag_selection_and_detached_generate_directive_are_accounted(self):
        source='//go:build enterprise\n\npackage app\n\n//go:generate unknown-tool\n\nfunc run() int { return 1 }\n'
        rejected=self.analyze({'main.go':source});self.assertTrue(any(d['code']=='GO_SELECTED_CONTEXT_MISMATCH' for d in rejected[-1]['diagnostics']));self.assertFalse(any(r['fields']['status']=='RESOLVED' and r['fields']['targets'] for r in rejected if r.get('kind')=='OBSERVATION'))
        selected=self.analyze({'main.go':source},tags=['enterprise']);self.assertFalse(any(d['code']=='GO_SELECTED_CONTEXT_MISMATCH' for d in selected[-1]['diagnostics']));self.assertTrue(any(r['fields']['role']=='generation-directive' and r['fields']['status']=='UNSUPPORTED' for r in selected if r.get('kind')=='OBSERVATION'))

    def test_same_signature_methods_from_distinct_interfaces_do_not_collapse(self):
        files={'main.go':'package app\ntype A interface { Work() int }\ntype B interface { Work() int }\nfunc run(a A,b B) int { return a.Work()+b.Work() }\n'};rows=self.analyze(files)
        methods=[s for r in rows if r.get('kind')=='OBSERVATION' for s in r['fields']['symbols'] if s['kind']=='METHOD' and s['name']=='Work'];self.assertEqual(2,len({s['id'] for s in methods}));self.assertEqual({'example.test/app::A','example.test/app::B'},{s['owner'] for s in methods})


if __name__=='__main__':unittest.main()
