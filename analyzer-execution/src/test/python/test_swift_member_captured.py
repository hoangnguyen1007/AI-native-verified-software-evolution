"""Real isolated Swift compiler/Syntax bytes; replay never invokes a tool."""
import io
import json
from pathlib import Path
import sys
import tempfile
import types
import unittest
from unittest.mock import patch

BASE=Path(__file__).resolve().parents[2];sys.path.insert(0,str(BASE/'main/resources/polyglot'))
import native_protocol as protocol
import swift_engine

class SwiftMemberCaptured(unittest.TestCase):
    def test_implicit_self_cannot_become_the_callers_function_as_a_callee(self):
        fixture=BASE/'test/resources/polyglot/swift-member-native-v1';meta=json.loads((fixture/'fixture.json').read_text(encoding='utf-8'))
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory);(root/'objects').mkdir();request=dict(meta['request']);request['nativeOriginTool']=request['tool'];request['tool']='sha256:'+'7'*64;request['provider']=dict(id='native.swift.captured',version='6.0.3-projection.4')
            def save(raw):
                handle=dict(digest=protocol.sha(raw),size=len(raw));(root/'objects'/handle['digest'][7:]).write_bytes(raw);return handle
            native={}
            for name,handle in meta['records'].items():
                raw=(fixture/name).read_bytes();self.assertEqual(handle,dict(digest=protocol.sha(raw),size=len(raw)));native[name]=save(raw)
            records=dict(schema='captured-swift-native-records:m8.4-v1',scope=request['scope'],originalTool=request['nativeOriginTool'],documents={f['path']:f['sha256'] for f in request['files']},syntax={'main.swift':native['syntax.json']},ast=native['ast.txt'],stderr=native['diagnostics.txt'],version=native['version.txt'],adapterExit=0,audit={})
            records_raw=protocol.canonical(records).encode();(root/'records.json').write_bytes(records_raw);request['nativeRecords']=dict(digest=protocol.sha(records_raw),size=len(records_raw));(root/'request.json').write_text(protocol.canonical(request),encoding='utf-8');stream=io.BytesIO()
            with patch.object(swift_engine.subprocess,'run',side_effect=AssertionError('Data-only replay')),patch.object(sys,'stdout',types.SimpleNamespace(buffer=stream)):swift_engine.analyze(root/'request.json',recorded=root)
            rows=[json.loads(line) for line in stream.getvalue().splitlines()];calls=[r['fields'] for r in rows if r.get('kind')=='OBSERVATION' and r['fields'].get('role')=='call'];self.assertEqual(1,len(calls));call=calls[0];self.assertEqual(['twice'],[s['name'] for s in call['symbols']]);self.assertEqual('CONDITIONAL',call['status']);self.assertTrue(call['uncertainty']);self.assertNotIn(call['caller'],call['targets']);self.assertEqual('(Derived) -> (Int) -> Int',call['symbols'][0]['nativeInterfaceType'])
            declarations=[s for row in rows if row.get('kind')=='OBSERVATION' and row['fields'].get('role')=='declaration' for s in row['fields']['symbols']];twice=next(s for s in declarations if s['name']=='twice');counter=next(s for s in declarations if s['name']=='counter');self.assertEqual('(Derived) -> (Int) -> Int',twice['nativeInterfaceType']);self.assertEqual('Int',counter['nativeInterfaceType']);self.assertEqual(twice['id'],call['targets'][0])

if __name__=='__main__':unittest.main()
