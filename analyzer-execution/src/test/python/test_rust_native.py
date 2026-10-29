"""Actual pinned rust-analyzer controls; only first-party authored Rust source."""
import hashlib
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

WORKSPACE=Path(__file__).resolve().parents[4];ENGINE=WORKSPACE/'analyzer-execution/src/main/resources/polyglot/rust_engine.py'


class RustNativeControls(unittest.TestCase):
    def test_type_parameter_reference_is_not_promoted_to_a_proven_supertrait(self):
        rows=self.analyze({'lib.rs':'trait Child<T>: T {}\n'});facts=[r['fields'] for r in rows if r.get('kind')=='OBSERVATION' and r['fields'].get('role')=='type-bound'];self.assertEqual(1,len(facts));self.assertNotEqual('RESOLVED',facts[0]['status']);self.assertTrue(facts[0].get('reason'))
    def test_native_trait_implementation_and_supertrait_keep_direct_targets_and_owners(self):
        text='trait Parent {} trait Child: Parent {} struct S; impl Parent for S {} impl Child for S {} impl S {}';rows=self.analyze({'lib.rs':text});questions={r['key']:r['fields'] for r in rows if r.get('kind')=='CENSUS'};facts=[r['fields'] for r in rows if r.get('kind')=='OBSERVATION'];declared={s['name']:s['id'] for f in facts if f.get('role')=='declaration' for s in f['symbols'] if s['name'] in ('Parent','Child','S')};relations=[r['fields'] for r in rows if r.get('kind')=='OBSERVATION' and questions[r['key']]['category']=='A05' and r['fields']['status']=='RESOLVED'];self.assertEqual({('SUPERTRAIT','Parent',declared['Child']),('IMPLEMENTS_TRAIT','Parent',declared['S']),('IMPLEMENTS_TRAIT','Child',declared['S'])},{(f['relation'],f['symbols'][0]['name'],f['owner']) for f in relations});self.assertTrue(all(len(f['targets'])==1 for f in relations))
    def test_native_generic_trait_binding_uses_trait_name_instead_of_type_argument(self):
        text='trait Shape<T> {} struct Arg; struct S; impl Shape<Arg> for S {}';rows=self.analyze({'lib.rs':text});questions={r['key']:r['fields'] for r in rows if r.get('kind')=='CENSUS'};relations=[r['fields'] for r in rows if r.get('kind')=='OBSERVATION' and questions[r['key']]['category']=='A05' and r['fields']['status']=='RESOLVED'];self.assertEqual(['Shape'],[s['name'] for f in relations for s in f['symbols']]);self.assertTrue(all(f['relation']=='IMPLEMENTS_TRAIT' for f in relations))
    def test_closure_callers_are_not_invented_as_the_outer_function(self):
        text='fn twice(n:i32)->i32 {n*2}\nfn run()->i32 {let f=||twice(4); f()}\n';rows=self.analyze({'lib.rs':text});call=next(r['fields'] for r in rows if r.get('kind')=='OBSERVATION' and r['fields'].get('role')=='call' and text[r['fields']['span']['start']:r['fields']['span']['end']]=='twice(4)');self.assertIsNone(call['caller']);self.assertEqual('PARTIAL',call['status']);self.assertEqual('RESOLVED',call['targetStatus']);self.assertEqual('UNRESOLVED',call['callerStatus'])
    def test_native_caller_uses_the_nearest_function_scope(self):
        text='fn twice(n:i32)->i32 {n*2}\nfn outer()->i32 {fn inner()->i32 {twice(4)} inner()}\n';rows=self.analyze({'lib.rs':text});declared=[s for r in rows if r.get('kind')=='OBSERVATION' and r['fields'].get('role')=='declaration' for s in r['fields']['symbols'] if s['name']=='inner'];calls=[r['fields'] for r in rows if r.get('kind')=='OBSERVATION' and r['fields'].get('role')=='call'];call=next(c for c in calls if text[c['span']['start']:c['span']['end']]=='twice(4)');self.assertEqual(1,len(declared));self.assertEqual(declared[0]['id'],call['caller']);self.assertEqual('RESOLVED',call['callerStatus'])
    def analyze(self,files):
        analyzer=WORKSPACE/'.evo/tools/m845-rust-analyzer-win-20250224/rust-analyzer.exe'
        if not analyzer.is_file():raise RuntimeError('Pinned native rust-analyzer required')
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory);source=root/'src';source.mkdir();work=root/'state'
            for name,text in files.items():(source/name).write_bytes(text.encode('utf8'))
            request=dict(schema='native-rust-request:m8.4-v1',language='rust',dialect='2021',scope='sha256:'+'1'*64,tool='sha256:'+hashlib.sha256(ENGINE.read_bytes()).hexdigest(),provider={'id':'frontend.rust','version':'ra0.3.2319-m8.4-v4'},repositoryScope='urn:test:authored-rust',packageScope='fixture',sourceRoot=str(source),workRoot=str(work),analyzer=dict(size=analyzer.stat().st_size,digest='sha256:'+hashlib.sha256(analyzer.read_bytes()).hexdigest()),crate={'root':'lib.rs','name':'fixture','cfg':[],'target':None},files=[dict(path=name,text=text,sha256='sha256:'+hashlib.sha256(text.encode('utf8')).hexdigest()) for name,text in sorted(files.items())])
            file=root/'request.json';file.write_text(json.dumps(request),encoding='utf8');result=subprocess.run([sys.executable,'-I',str(ENGINE),'--request',str(file),'--analyzer',str(analyzer)],stdout=subprocess.PIPE,stderr=subprocess.PIPE,timeout=180)
            detail=result.stderr[-8192:]
            if result.returncode and (work/'rust-analyzer.stderr').is_file():detail+=(work/'rust-analyzer.stderr').read_bytes()[-8192:]
            self.assertEqual(0,result.returncode,detail.decode(errors='replace'));return [json.loads(line) for line in result.stdout.decode('utf8').splitlines()]
    def call(self,rows,files,written):return next(r['fields'] for r in rows if r.get('kind')=='OBSERVATION' and r['fields']['role']=='call' and r['fields']['span'] and files[r['fields']['span']['path']].encode('utf-16-le')[r['fields']['span']['start']*2:r['fields']['span']['end']*2].decode('utf-16-le')==written)
    def test_crossfile_generic_native_target_original_crlf_and_nonbmp(self):
        files={'service.rs':'pub fn twice<T>(n:T)->T { n }\n','lib.rs':'mod service;\r\npub fn run()->i32{let marker="😀";service::twice(4)}\r\n'};rows=self.analyze(files);call=self.call(rows,files,'service::twice(4)');self.assertEqual('RESOLVED',call['status']);self.assertEqual('twice',call['symbols'][0]['name']);self.assertEqual('service.rs',call['symbols'][0]['span']['path'])
    def test_missing_and_function_pointer_calls_do_not_invent_targets(self):
        files={'lib.rs':'pub fn run(f:fn(i32)->i32)->i32 { missing(3); f(4) }\n'};rows=self.analyze(files)
        for written in ('missing(3)','f(4)'):
            call=self.call(rows,files,written);self.assertEqual('UNRESOLVED',call['status']);self.assertFalse(call['targets'])
    def test_native_parse_error_keeps_opaque_denominator(self):
        rows=self.analyze({'lib.rs':'pub fn broken( {\n'});self.assertTrue(any(r['fields']['status']=='ERROR' for r in rows if r.get('kind')=='OBSERVATION'));self.assertEqual(rows[-1]['census'],rows[-1]['observations'])


if __name__=='__main__':unittest.main()
