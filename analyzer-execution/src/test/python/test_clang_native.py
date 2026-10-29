"""Actual libclang controls over authored strings. No host target repository is indexed."""
import hashlib
import json
from pathlib import Path
import subprocess
import tempfile
import unittest
import sys

WORKSPACE=Path(__file__).resolve().parents[4];SOURCE=WORKSPACE/'analyzer-execution/src/main/resources/polyglot/clang_engine.py'


def handle(file):return dict(size=file.stat().st_size,digest='sha256:'+hashlib.sha256(file.read_bytes()).hexdigest())


class ClangNativeControls(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.bindings=WORKSPACE/'.evo/tools/m845-libclang-win-18.1.1/libclang-18.1.1.data/platlib';cls.library=cls.bindings/'clang/native/libclang.dll'
        if not cls.library.is_file():raise RuntimeError('Pinned native Windows libclang 18.1.1 required for authored API controls; Linux acceptance is separate')
    def analyze(self,language,text,extra=None,script=SOURCE,version='18.1.1-m8.4-v2'):
        dialect,native,suffix={'c':('c17','c','c'),'cpp':('c++20','c++','cpp'),'objectivec':('gnu17','objective-c','m')}[language]
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory);files={'main.'+suffix:text};files.update(extra or {})
            for path,value in files.items():(root/path).write_bytes(value.encode('utf8'))
            request=dict(schema='native-clang-request:m8.4-v1',language=language,dialect=dialect,scope='sha256:'+'1'*64,tool='sha256:'+hashlib.sha256(script.read_bytes()).hexdigest(),provider={'id':'frontend.clang','version':version},repositoryScope='urn:test:authored-clang',packageScope='fixture',sourceRoot=str(root),files=[dict(path=path,text=value,sha256='sha256:'+hashlib.sha256(value.encode('utf8')).hexdigest()) for path,value in sorted(files.items())],library=handle(self.library),bindings=handle(self.bindings/'clang/cindex.py'),translationUnit='main.'+suffix,arguments=['-x',native,'-std='+dialect,'-fsyntax-only','--target=x86_64-unknown-linux-gnu','-nostdinc','-nostdinc++'],originRoots=[])
            file=root/'request.json';file.write_text(json.dumps(request),encoding='utf8');result=subprocess.run([sys.executable,'-I',str(script),'--request',str(file),'--bindings',str(self.bindings),'--library',str(self.library)],stdout=subprocess.PIPE,stderr=subprocess.PIPE,timeout=60)
            self.assertEqual(0,result.returncode,result.stderr[-8192:].decode(errors='replace'));rows=[json.loads(line) for line in result.stdout.decode('utf8').splitlines()];self.assertEqual({f'A{i:02}' for i in range(1,13)},{r['fields']['category'] for r in rows if r.get('kind')=='CENSUS'});self.assertEqual(rows[-1]['census'],rows[-1]['observations']);return rows
    def call(self,rows,text,written,role='call'):
        return next(r['fields'] for r in rows if r.get('kind')=='OBSERVATION' and r['fields']['role']==role and r['fields']['span'] and text.encode('utf-16-le')[r['fields']['span']['start']*2:r['fields']['span']['end']*2].decode('utf-16-le')==written)
    def test_c_direct_call_declarations_types_and_original_utf8_crlf_nonbmp_positions(self):
        text='int twice(int n) { return n*2; }\r\nint run(void) { const char *marker="😀"; return twice(4); }\r\n'
        call=self.call(self.analyze('c',text),text,'twice(4)');self.assertEqual('RESOLVED',call['status']);self.assertEqual('twice',call['symbols'][0]['name']);self.assertEqual('PROJECT',call['symbols'][0]['origin'])
    def test_cpp_native_overload_binding_is_separate_from_c(self):
        text='int twice(int n) { return n*2; }\nconst char *twice(const char *s) { return s; }\nint run() { return twice(4); }\n'
        call=self.call(self.analyze('cpp',text),text,'twice(4)');self.assertEqual('RESOLVED',call['status']);self.assertIn('int (int)',call['symbols'][0]['signature']);self.assertEqual('PROJECT',call['symbols'][0]['origin'])
    def test_objectivec_native_message_binding_keeps_runtime_dispatch_conditional(self):
        text='__attribute__((objc_root_class))\n@interface Counter\n+ (int)twice:(int)n;\n@end\n@implementation Counter\n+ (int)twice:(int)n { return n*2; }\n@end\nint run(void) { return [Counter twice:4]; }\n'
        call=self.call(self.analyze('objectivec',text),text,'[Counter twice:4]','message-send');self.assertEqual('CONDITIONAL',call['status']);self.assertEqual('twice:',call['symbols'][0]['name']);self.assertTrue(call['uncertainty']);self.assertEqual('PROJECT',call['symbols'][0]['origin'])
    def test_error_recovery_cannot_certify_dropped_native_expressions_as_absent(self):
        for language in ('c','cpp','objectivec'):
            with self.subTest(language=language):
                rows=self.analyze(language,'int run(void) { return missing(3); }');observed=[r['fields'] for r in rows if r.get('kind')=='OBSERVATION'];self.assertTrue(any(r['status']=='ERROR' for r in observed));self.assertFalse(any(r['status']=='RESOLVED' and r['targets'] for r in observed));self.assertFalse(any(r['role']=='syntax-absent' for r in observed))
    def test_indirect_function_pointer_call_does_not_claim_variable_as_exact_function(self):
        text='int run(int (*callback)(int)) { return callback(3); }'
        call=self.call(self.analyze('c',text),text,'callback(3)');self.assertEqual('UNRESOLVED',call['status']);self.assertEqual([],call['targets']);self.assertEqual('callback',call['calleeSymbol']['name'])
    def test_included_and_orphan_headers_both_remain_in_closed_document_membership(self):
        text='#include "api.h"\nint run(void) { return twice(4); }'
        rows=self.analyze('c',text,{'api.h':'int twice(int n);\n','orphan.h':'int never_loaded(void);\n'});self.assertEqual({'main.c','api.h','orphan.h'},{r['key'] for r in rows if r.get('kind')=='DOCUMENT'});self.assertTrue(any(r['fields']['status']=='ERROR' and r['fields']['role']=='opaque-syntax' and r['key'].startswith('orphan.h:') for r in rows if r.get('kind')=='OBSERVATION'));self.assertEqual('RESOLVED',self.call(rows,text,'twice(4)')['status'])
    def test_direct_state_reads_writes_and_compound_access_are_native_in_each_language(self):
        text='int state; int run(void) { state=1; state+=2; ++state; return state; }'
        for language in ('c','cpp','objectivec'):
            with self.subTest(language=language):
                rows=self.analyze(language,text);access=[r['fields'] for r in rows if r.get('kind')=='OBSERVATION' and r['fields']['role']=='value-access' and r['fields']['span'] and text[r['fields']['span']['start']:r['fields']['span']['end']]=='state']
                self.assertEqual(['WRITE','READ_WRITE','READ_WRITE','READ'],[r['access'] for r in access]);self.assertTrue(all(r['status']=='RESOLVED' and r['symbols'][0]['name']=='state' for r in access))
    def test_address_and_indirect_memory_write_do_not_claim_the_pointer_variable_was_written(self):
        text='int state; int run(void) { int *ptr=&state; *ptr=3; return state; }';rows=self.analyze('c',text)
        accesses=[r['fields'] for r in rows if r.get('kind')=='OBSERVATION' and r['fields']['role'] in ('value-access','indirect-access')]
        self.assertTrue(any(r.get('access')=='ADDRESS' and r['status']=='RESOLVED' for r in accesses));self.assertFalse(any(r.get('access')=='WRITE' and any(s['name']=='ptr' for s in r['symbols']) for r in accesses))
        self.assertTrue(any(r['role']=='indirect-access' and r['status']=='UNRESOLVED' and r['reason']=='CLANG_INDIRECT_MEMORY_TARGET_NOT_ESTABLISHED' and r.get('access')=='WRITE' for r in accesses))
    def test_cpp_field_mutation_preserves_native_base_type_and_guard_uncertainty(self):
        text='struct Base { int n; }; struct Child:Base {}; int run(Child &x) { if (x.n>0) { x.n+=1; } return x.n; }';rows=self.analyze('cpp',text);facts=[r['fields'] for r in rows if r.get('kind')=='OBSERVATION']
        self.assertTrue(any(r['role']=='heritage' and r['status']=='RESOLVED' and any(s['name']=='Base' for s in r['symbols']) for r in facts))
        self.assertTrue(any(r['role']=='value-access' and r.get('access')=='READ_WRITE' and r['status']=='RESOLVED' and any(s['name']=='n' for s in r['symbols']) for r in facts))
        self.assertTrue(any(r['role']=='guard' and r['status']=='RESOLVED' and r['truth']=='UNKNOWN' for r in facts))
    def test_unevaluated_operand_does_not_become_a_confirmed_state_read(self):
        text='int state; unsigned long run(void) { return sizeof state; }';rows=self.analyze('c',text)
        refs=[r['fields'] for r in rows if r.get('kind')=='OBSERVATION' and r['fields']['role']=='value-access'];self.assertTrue(refs);self.assertTrue(all(r['status']=='PARTIAL' and r.get('access')!='READ' for r in refs))
    def test_provider_upgrade_preserves_logical_ids_for_unchanged_native_declarations(self):
        controls=WORKSPACE/'analyzer-execution/src/test/resources/polyglot/clang-v1-identity';fixture=json.loads((controls/'fixture.json').read_bytes())
        self.assertEqual('frozen-platform-control:m8.4-v1',fixture['schema'])
        for name in ('clang_engine.py','native_protocol.py','guest_semantic_runner.py'):self.assertEqual(fixture['files'][name],handle(controls/name))
        text='int state; int run(void) { state+=1; return state; }'
        old=self.analyze('cpp',text,script=controls/'clang_engine.py',version='18.1.1-m8.4-v1');new=self.analyze('cpp',text)
        def symbols(rows):return {(s['owner'],s['signature']):s['id'] for r in rows if r.get('kind')=='OBSERVATION' for s in r['fields'].get('symbols',[]) if s['name'] in ('state','run')}
        self.assertEqual(2,len(symbols(old)));self.assertEqual(symbols(old),symbols(new))


if __name__=='__main__':unittest.main()
