"""Pinned Pyright + native AST controls over authored strings only."""
import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest
from unittest import mock

WORKSPACE=Path(__file__).resolve().parents[4];SOURCE=WORKSPACE/'analyzer-execution/src/main/resources/polyglot/python_engine.py'


def handle(file):return dict(size=file.stat().st_size,digest='sha256:'+hashlib.sha256(file.read_bytes()).hexdigest())


class PythonNativeControls(unittest.TestCase):
    def test_native_lexical_caller_is_the_nearest_nested_function(self):
        text='def twice(n: int) -> int: return n*2\ndef outer() -> int:\n    def inner() -> int: return twice(4)\n    return inner()\n';rows=self.analyze({'main.py':text});call=self.call(rows,text,'twice(4)');declared=[s for r in rows if r.get('kind')=='OBSERVATION' and r['fields'].get('role')=='declaration' for s in r['fields']['symbols'] if s['name']=='inner'];self.assertEqual(1,len(declared));self.assertEqual(declared[0]['id'],call['caller']);self.assertEqual('RESOLVED',call['callerStatus'])
    def test_lambda_default_executes_in_the_enclosing_body_but_lambda_body_keeps_a_gap(self):
        prefix='def twice(n: int) -> int: return n*2\ndef outer() -> int:\n'
        for expression,closed in [('lambda n=twice(4): n',True),('lambda: twice(4)',False)]:
            text=prefix+'    callback='+expression+'\n    return callback()\n';rows=self.analyze({'main.py':text});call=self.call(rows,text,'twice(4)');outer=next(s for r in rows if r.get('kind')=='OBSERVATION' and r['fields'].get('role')=='declaration' for s in r['fields']['symbols'] if s['name']=='outer');self.assertEqual(outer['id'] if closed else None,call['caller']);self.assertEqual('RESOLVED' if closed else 'PARTIAL',call['status']);self.assertEqual('RESOLVED' if closed else 'UNRESOLVED',call['callerStatus'])
    def analyze(self,files):
        node_path=shutil.which('node')
        if not node_path:raise RuntimeError('Node runtime required on host')
        sdk=WORKSPACE/'.evo/tools/m845-pyright-api-1.1.396/package';node=Path(node_path).resolve();python=Path(sys.executable).resolve()
        if not (sdk/'langserver.index.js').is_file():raise RuntimeError('Pinned real Pyright SDK required')
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory);sources=root/'sources';sources.mkdir();work=root/'work'
            for name,text in files.items():(sources/name).write_bytes(text.encode('utf8'))
            request=dict(schema='native-python-request:m8.4-v1',language='python',dialect='3.10',parserVersion=sys.version.split()[0],scope='sha256:'+'1'*64,tool='sha256:'+hashlib.sha256(SOURCE.read_bytes()).hexdigest(),provider={'id':'frontend.python','version':'pyright-1.1.396-m8.4-v1'},repositoryScope='urn:test:authored-python',packageScope='fixture',sourceRoot=str(sources),workRoot=str(work),files=[dict(path=name,text=text,sha256='sha256:'+hashlib.sha256(text.encode('utf8')).hexdigest()) for name,text in sorted(files.items())],moduleNames={name:name.removesuffix('.py').removesuffix('.pyi') for name in files},node=handle(node),python=handle(python),server=handle(sdk/'langserver.index.js'),cli=handle(sdk/'index.js'))
            file=root/'request.json';file.write_text(json.dumps(request),encoding='utf8');result=subprocess.run([sys.executable,'-I',str(SOURCE),'--request',str(file),'--node',str(node),'--sdk',str(sdk),'--python',str(python)],stdout=subprocess.PIPE,stderr=subprocess.PIPE,timeout=150)
            self.assertEqual(0,result.returncode,result.stderr[-8192:].decode(errors='replace'));rows=[json.loads(line) for line in result.stdout.decode('utf8').splitlines()];self.assertEqual({f'A{i:02}' for i in range(1,13)},{r['fields']['category'] for r in rows if r.get('kind')=='CENSUS'});self.assertEqual(rows[-1]['census'],rows[-1]['observations']);return rows
    def call(self,rows,text,written):return next(r['fields'] for r in rows if r.get('kind')=='OBSERVATION' and r['fields']['role']=='call' and r['fields']['span'] and text.encode('utf-16-le')[r['fields']['span']['start']*2:r['fields']['span']['end']*2].decode('utf-16-le')==written)
    def test_native_call_binding_crossfile_and_original_crlf_nonbmp_range(self):
        text='from service import twice\r\ndef run() -> int:\r\n    marker="😀"; return twice(4)\r\n'
        rows=self.analyze({'main.py':text,'service.py':'def twice(n: int) -> int:\n    return n * 2\n'});call=self.call(rows,text,'twice(4)');self.assertEqual('RESOLVED',call['status']);self.assertEqual('twice',call['symbols'][0]['name']);self.assertEqual('PROJECT',call['symbols'][0]['origin']);self.assertEqual('service',call['symbols'][0]['owner'])
    def test_missing_and_dynamic_calls_do_not_get_invented_targets(self):
        text='def run(target):\n    missing(3)\n    target.work()\n'
        rows=self.analyze({'main.py':text})
        for written in ('missing(3)','target.work()'):
            call=self.call(rows,text,written);self.assertEqual('UNRESOLVED',call['status']);self.assertEqual([],call['targets'])
        self.assertTrue(any(d['severity']=='ERROR' for d in rows[-1]['diagnostics']))
    def test_syntax_error_keeps_opaque_categories(self):
        rows=self.analyze({'main.py':'def broken( {\n'});observed=[r['fields'] for r in rows if r.get('kind')=='OBSERVATION'];self.assertTrue(any(r['status']=='ERROR' for r in observed));self.assertFalse(any(r['status']=='RESOLVED' and r['targets'] for r in observed))

    def test_utf8_bom_unicode_declaration_and_reordered_membership_replay(self):
        text='\ufeffdef tính(n: int) -> int:\r\n    return n*2\r\ndef run() -> int:\r\n    return tính(4)\r\n'
        first=self.analyze({'main.py':text,'other.py':'def other():\n    return 1\n'});second=self.analyze({'other.py':'def other():\n    return 1\n','main.py':text});self.assertEqual(first,second)
        call=self.call(first,text,'tính(4)');self.assertEqual('RESOLVED',call['status']);self.assertEqual('tính',call['symbols'][0]['name'])

    def test_native_augmented_assignment_is_read_write(self):
        rows=self.analyze({'main.py':'def run():\n    count=1\n    count+=2\n    return count\n'})
        access=[r['fields'].get('access') for r in rows if r.get('kind')=='OBSERVATION' and r['fields']['role']=='value-access' and any(s['name']=='count' for s in r['fields']['symbols'])]
        self.assertIn('READ_WRITE',access)


class PythonHostPrerequisiteControls(unittest.TestCase):
    def test_missing_node_reports_the_runtime_prerequisite_before_resolving_a_path(self):
        with mock.patch.object(shutil,'which',return_value=None):
            with self.assertRaisesRegex(RuntimeError,'Node runtime required on host'):
                PythonNativeControls().analyze({})
    def test_present_node_still_requires_the_pinned_pyright_sdk(self):
        with mock.patch.object(shutil,'which',return_value=sys.executable),mock.patch.object(Path,'is_file',return_value=False):
            with self.assertRaisesRegex(RuntimeError,'Pinned real Pyright SDK required'):
                PythonNativeControls().analyze({})


if __name__=='__main__':unittest.main()
