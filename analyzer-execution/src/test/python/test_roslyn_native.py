"""Actual Roslyn C# and VB tests over platform-authored source only. No host target indexing."""
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

WORKSPACE=Path(__file__).resolve().parents[4]
SOURCE=WORKSPACE/'analyzer-execution/src/main/resources/polyglot/dotnet/RoslynEngine.cs'


class RoslynNativeControls(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.compiler=WORKSPACE/'.evo/tools/m845-roslyn-il-4.11.0/tasks/netcore/bincore'
        cls.refs=sorted((WORKSPACE/'.evo/tools/m845-dotnet-api-v1/packs/Microsoft.NETCore.App.Ref').rglob('*.dll'))
        if not cls.refs or not (cls.compiler/'csc.dll').is_file():raise RuntimeError('Pinned actual Roslyn 4.11.0 / .NET 8 reference assemblies required')
        cls.temporary=tempfile.TemporaryDirectory();cls.output=Path(cls.temporary.name)
        for file in cls.compiler.glob('Microsoft.CodeAnalysis*.dll'):shutil.copyfile(file,cls.output/file.name)
        cmd=['dotnet',str(cls.compiler/'csc.dll'),'/nologo','/noconfig','/nostdlib+','/langversion:12','/nullable:enable','/target:exe','/out:'+str(cls.output/'RoslynEngine.dll'),*['/r:'+str(p) for p in [*cls.refs,*cls.compiler.glob('Microsoft.CodeAnalysis*.dll')]],str(SOURCE)]
        result=subprocess.run(cmd,stdout=subprocess.PIPE,stderr=subprocess.PIPE,timeout=60)
        if result.returncode:raise RuntimeError((result.stdout+result.stderr).decode(errors='replace')[-8192:])
        (cls.output/'RoslynEngine.runtimeconfig.json').write_text(json.dumps({'runtimeOptions':{'tfm':'net8.0','framework':{'name':'Microsoft.NETCore.App','version':'8.0.0'}}}))
        cls.reference_rows=[dict(path=str(file),size=file.stat().st_size,sha256='sha256:'+hashlib.sha256(file.read_bytes()).hexdigest(),origin='PLATFORM') for file in cls.refs]
    @classmethod
    def tearDownClass(cls):cls.temporary.cleanup()
    def analyze(self,language,text,mutate=None,expected_exit=0):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory);name='main.'+('cs' if language=='csharp' else 'vb');(root/name).write_text(text,encoding='utf8',newline='')
            request=dict(schema='native-roslyn-request:m8.4-v1',compilerVersion='4.11.0.0',language=language,dialect='12.0' if language=='csharp' else '16.9',scope='sha256:'+'1'*64,repositoryScope='urn:test:authored-roslyn',packageScope='fixture',provider={'id':'frontend.roslyn','version':'4.11.0-m8.4-v1'},tool='sha256:'+hashlib.sha256(SOURCE.read_bytes()).hexdigest(),symbols=[],sourceRoot=str(root),files=[dict(path=name,text=text,sha256='sha256:'+hashlib.sha256(text.encode('utf8')).hexdigest())],references=self.reference_rows)
            if mutate:mutate(request)
            file=root/'request.json';file.write_text(json.dumps(request),encoding='utf8')
            result=subprocess.run(['dotnet',str(self.output/'RoslynEngine.dll'),str(file)],stdout=subprocess.PIPE,stderr=subprocess.PIPE,timeout=60)
            if expected_exit:
                self.assertNotEqual(0,result.returncode);return []
            self.assertEqual(0,result.returncode,result.stderr[-8192:].decode(errors='replace'));rows=[json.loads(line) for line in result.stdout.decode('utf8').splitlines()]
            self.assertEqual({f'A{i:02}' for i in range(1,13)},{row['fields']['category'] for row in rows if row.get('kind')=='CENSUS'})
            self.assertEqual(rows[-1]['census'],rows[-1]['observations']);self.assertEqual('PRODUCER_ASSERTION',rows[0]['trust']);return rows
    def call(self,rows,text,needle):
        fields=[r['fields'] for r in rows if r.get('kind')=='OBSERVATION' and r['fields']['role']=='call']
        return next(f for f in fields if text.encode('utf-16-le')[f['span']['start']*2:f['span']['end']*2].decode('utf-16-le')==needle)
    def test_absent_attributes_do_not_certify_absent_implicit_members(self):
        for language,text in [('csharp','class Plain {}'),('visualbasic','Class Plain\nEnd Class\n')]:
            with self.subTest(language=language):
                rows=self.analyze(language,text);keys={row['key'] for row in rows if row.get('kind')=='CENSUS' and row['fields']['category']=='A08'};fact=next(row['fields'] for row in rows if row.get('kind')=='OBSERVATION' and row['key'] in keys);self.assertEqual('UNSUPPORTED',fact['status']);self.assertEqual('NATIVE_IMPLICIT_GENERATION_CENSUS_REQUIRED',fact['reason'])
    def test_csharp_resolves_exact_overload_with_original_nonbmp_crlf_span(self):
        text='namespace Fixture;\r\nclass Counter { static int Twice(int n)=>n*2; static string Twice(string s)=>s+s; static int Run() { var marker="😀"; return Twice(4); } }\r\n'
        rows=self.analyze('csharp',text);call=self.call(rows,text,'Twice(4)');self.assertEqual('RESOLVED',call['status']);self.assertEqual('PROJECT',call['symbols'][0]['origin']);self.assertIn('System.Int32',call['symbols'][0]['signature'])
    def test_visualbasic_resolves_its_own_overload_and_span(self):
        text='''Namespace Fixture
Module Counter
 Function Twice(n As Integer) As Integer
  Return n*2
 End Function
 Function Twice(s As String) As String
  Return s+s
 End Function
 Function Run() As Integer
  Dim marker = "😀"
  Return Twice(4)
 End Function
End Module
End Namespace
'''
        call=self.call(self.analyze('visualbasic',text),text,'Twice(4)');self.assertEqual('RESOLVED',call['status']);self.assertEqual('PROJECT',call['symbols'][0]['origin']);self.assertIn('System.Int32',call['symbols'][0]['signature'])
    def test_missing_csharp_and_visualbasic_calls_never_become_exact_targets(self):
        for language,text in [('csharp','class C { int Run()=>Missing(3); }'),('visualbasic','Module C\nFunction Run() As Integer\nReturn Missing(3)\nEnd Function\nEnd Module')]:
            with self.subTest(language=language):
                rows=self.analyze(language,text);call=self.call(rows,text,'Missing(3)');self.assertIn(call['status'],('UNRESOLVED','ERROR'));self.assertEqual([],call['targets']);self.assertTrue(any(d['severity']=='ERROR' for d in rows[-1]['diagnostics']))
    def test_syntax_recovery_does_not_certify_partial_nodes(self):
        for language,text in [('csharp','class C { int Broken( {'),('visualbasic','Module C\nFunction Broken( As\nEnd Module')]:
            with self.subTest(language=language):
                rows=self.analyze(language,text);self.assertTrue(any(d['severity']=='ERROR' for d in rows[-1]['diagnostics']));self.assertFalse(any(r['fields']['status']=='RESOLVED' and r['fields']['targets'] for r in rows if r.get('kind')=='OBSERVATION'))
    def test_changed_reference_source_or_compiler_is_rejected(self):
        def wrong_ref(r):r['references']=[dict(r['references'][0],sha256='sha256:'+'0'*64)]
        for mutate in [wrong_ref,lambda r:r.update(compilerVersion='9.9.9.9'),lambda r:r['files'][0].update(sha256='sha256:'+'0'*64)]:
            self.analyze('csharp','class C {}',mutate,expected_exit=1)

    def test_csharp_ambiguous_overload_and_dynamic_dispatch_keep_uncertainty(self):
        text='class C { void F(string x) {} void F(System.Uri x) {} void Run(dynamic d) { F(null); d.Work(); } }'
        rows=self.analyze('csharp',text);ambiguous=self.call(rows,text,'F(null)');self.assertIn(ambiguous['status'],('CANDIDATES','AMBIGUOUS'));self.assertEqual(2,len(ambiguous['targets']));self.assertTrue(ambiguous['uncertainty'])
        dynamic=self.call(rows,text,'d.Work()');self.assertEqual('UNRESOLVED',dynamic['status']);self.assertEqual([],dynamic['targets']);self.assertEqual('ROSLYN_DYNAMIC_DISPATCH',dynamic['reason'])

    def test_native_state_binding_distinguishes_write_and_compound_read_write(self):
        rows=self.analyze('csharp','class C { int count; void Run() { count=1; count+=2; } }')
        accesses=[r['fields']['access'] for r in rows if r.get('kind')=='OBSERVATION' and r['fields']['role']=='value-access' and r['fields'].get('symbols') and r['fields']['symbols'][0]['name']=='count']
        self.assertCountEqual(['WRITE','READ_WRITE'],accesses)


if __name__=='__main__':unittest.main()
