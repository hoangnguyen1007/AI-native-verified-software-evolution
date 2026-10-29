"""Authored metadata controls; not a native Swift acceptance campaign."""
import io
import json
from pathlib import Path
import sys
import tempfile
import types
import unittest
from unittest.mock import patch

BASE=Path(__file__).resolve().parents[2]/'main/resources/polyglot';sys.path.insert(0,str(BASE))
import native_protocol as protocol
import swift_engine


class CapturedSwiftControls(unittest.TestCase):
    def fixture(self,root,typed=False):
        objects=root/'objects';objects.mkdir()
        def save(raw):
            handle=dict(digest=protocol.sha(raw),size=len(raw));(objects/handle['digest'][7:]).write_bytes(raw);return handle
        texts={'main.swift':'func run() -> Int { twice(4) }\n','service.swift':'func twice<T>(_ n: T) -> T { n }\n'}
        syntax={}
        for path,text in texts.items():
            name='run' if path=='main.swift' else 'twice'
            nodes=[dict(kind='functionDecl',start=0,end=len(text)-1,name=name,nameStart=5,nameEnd=5+len(name),owners=[],declaration=True)]
            if path=='main.swift':nodes.append(dict(kind='functionCallExpr',start=20,end=28,owners=['run@5'],callee=dict(start=20,end=25)))
            syntax[path]=save(protocol.canonical(dict(schema='native-swift-census:m8.4-v1',hasError=False,nodes=nodes)).encode())
        ast='(source_file "/captured/main.swift" (declref_expr location=/captured/main.swift:1:21 decl="Fixture.twice@/captured/service.swift:1:6 [with (substitution_map T -> Int)]"))'
        if typed:ast+='\n(source_file "/captured/service.swift" (func_decl range=[/captured/service.swift:1:1 - line:1:31] interface type="<T> (T) -> T"))'
        request=dict(schema='native-swift-request:m8.4-v1',scope='sha256:'+'1'*64,tool='sha256:'+'2'*64,nativeOriginTool='sha256:'+'3'*64,language='swift',dialect='6',repositoryScope='repo',packageScope='pkg',moduleName='Fixture',provider=dict(id='native.swift',version='6.0.3-projection.2'),sourceRoot='/captured',workRoot=str(root/'state'),files=[dict(path=p,text=t,sha256=protocol.sha(t.encode())) for p,t in texts.items()])
        records=dict(schema='captured-swift-native-records:m8.4-v1',scope=request['scope'],originalTool=request['nativeOriginTool'],documents={p:protocol.sha(t.encode()) for p,t in texts.items()},syntax=syntax,ast=save(ast.encode()),stderr=save(b''),version=save(b'Swift version 6.0.3\n'),adapterExit=0,audit={})
        raw=protocol.canonical(records).encode();(root/'records.json').write_bytes(raw);request['nativeRecords']=dict(digest=protocol.sha(raw),size=len(raw));file=root/'request.json';file.write_text(protocol.canonical(request),encoding='utf8');return file,request,records
    def project(self,root,request):
        stream=io.BytesIO()
        with patch.object(swift_engine.subprocess,'run',side_effect=AssertionError('Captured mode must never invoke native tools')),patch.object(sys,'stdout',types.SimpleNamespace(buffer=stream)):
            swift_engine.analyze(request,recorded=root)
        return [json.loads(line) for line in stream.getvalue().splitlines()]
    def test_native_generic_pointer_resolves_without_tools_and_keeps_exact_span_and_caller(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory);file,_,_=self.fixture(root);rows=self.project(root,file);calls=[r['fields'] for r in rows if r.get('kind')=='OBSERVATION' and r['fields']['role']=='call']
            self.assertEqual(1,len(calls));call=calls[0];self.assertEqual('RESOLVED',call['status']);self.assertEqual('twice',call['symbols'][0]['name']);self.assertEqual((20,28),(call['span']['start'],call['span']['end']));self.assertIsNotNone(call['caller'])
            declared=[r['fields']['symbols'][0] for r in rows if r.get('kind')=='OBSERVATION' and r['fields']['role']=='declaration']
            self.assertEqual(next(d['id'] for d in declared if d['name']=='run'),call['caller']);self.assertEqual(next(d['id'] for d in declared if d['name']=='twice'),call['targets'][0])
            self.assertFalse((root/'state').exists(),'Recorded metadata must not cause writes to the request-supplied native work locator')
    def test_optional_compiler_type_metadata_never_changes_logical_symbol_identity(self):
        ids=[]
        for typed in (False,True):
            with tempfile.TemporaryDirectory() as directory:
                root=Path(directory);file,_,_=self.fixture(root,typed);rows=self.project(root,file);ids.append(next(r['fields']['targets'] for r in rows if r.get('kind')=='OBSERVATION' and r['fields']['role']=='call'))
        self.assertEqual(ids[0],ids[1])
    def test_corrupt_native_bytes_or_wrong_membership_fail_before_projection(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory);file,request,records=self.fixture(root);(root/'objects'/records['ast']['digest'][7:]).write_bytes(b'corrupt')
            with self.assertRaises((ValueError,RuntimeError)):self.project(root,file)
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory);file,request,records=self.fixture(root);records['documents']={};raw=protocol.canonical(records).encode();(root/'records.json').write_bytes(raw);request['nativeRecords']=dict(digest=protocol.sha(raw),size=len(raw));file.write_text(protocol.canonical(request),encoding='utf8')
            with self.assertRaisesRegex(ValueError,'SWIFT_NATIVE_RECORD_SOURCE_MEMBERSHIP'):self.project(root,file)


if __name__=='__main__':unittest.main()
