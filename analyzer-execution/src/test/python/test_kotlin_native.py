"""Explicit real compiler controls on platform-authored fixtures only; never host target analysis."""
import hashlib
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

WORKSPACE=Path(__file__).resolve().parents[4]
SOURCE=WORKSPACE/'analyzer-execution/src/main/resources/polyglot/jvm'


class KotlinCompilerControls(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.sdk=Path(os.environ.get('POLYGLOT_JVM_SDK',str(WORKSPACE/'.evo/tools/m845-jvm-20261027'))).resolve()
        cls.platform=(WORKSPACE/'analyzer/target/classes').resolve()
        if not (cls.sdk/'kotlin-compiler-embeddable-1.9.25.jar').is_file():raise RuntimeError('Pinned real Kotlin SDK required')
        cls.temporary=tempfile.TemporaryDirectory();cls.classes=Path(cls.temporary.name)/'classes';cls.classes.mkdir()
        cls.classpath=os.pathsep.join([str(cls.platform),str(cls.sdk/'*')])
        subprocess.run(['javac','-proc:none','-cp',cls.classpath,'-d',str(cls.classes),str(SOURCE/'NeutralJvm.java'),str(SOURCE/'KotlinEngine.java')],check=True,stdout=subprocess.PIPE,stderr=subprocess.PIPE,timeout=60)

    @classmethod
    def tearDownClass(cls):cls.temporary.cleanup()

    def analyze(self,text):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory);source=root/'main.kt';source.write_text(text,encoding='utf8',newline='')
            tool='sha256:'+hashlib.sha256((SOURCE/'KotlinEngine.java').read_bytes()+(SOURCE/'NeutralJvm.java').read_bytes()).hexdigest()
            request=dict(schema='native-jvm-request:m8.4-v1',language='kotlin',dialect='1.9.25',scope='sha256:'+'1'*64,tool=tool,provider=dict(id='frontend.kotlin-native',version='1.9.25-m8.4-v1'),repositoryScope='urn:test:authored-kotlin',packageScope='app',sourceRoot=str(root),classpath=[str(self.sdk/'kotlin-stdlib-1.9.25.jar')],files=[dict(path='main.kt',text=text,sha256='sha256:'+hashlib.sha256(text.encode()).hexdigest())])
            file=root/'request.json';file.write_text(json.dumps(request),encoding='utf8');classpath=os.pathsep.join([str(self.classes),self.classpath])
            result=subprocess.run(['java','-Xmx768m','-cp',classpath,'evo.polyglot.KotlinEngine',str(file)],stdout=subprocess.PIPE,stderr=subprocess.PIPE,timeout=60)
            self.assertEqual(0,result.returncode,result.stderr[-4096:].decode(errors='replace'))
            rows=[json.loads(line) for line in result.stdout.decode('utf8').splitlines()];self.assertEqual('PRODUCER_ASSERTION',rows[0]['trust']);return rows

    def test_native_overload_targets_declarations_types_and_original_nonbmp_call_span(self):
        source='package fixture\nfun twice(value:Int):Int=value*2\nfun twice(value:String):String=value\nfun use():Int { val marker="😀"; return twice(4) }\n'
        rows=self.analyze(source);census=[r for r in rows if r.get('kind')=='CENSUS'];observed=[r for r in rows if r.get('kind')=='OBSERVATION'];calls=[r for r in observed if r['fields'].get('role')=='call']
        self.assertEqual(1,len(calls));call=calls[0]['fields'];self.assertEqual('RESOLVED',call['status']);self.assertEqual('twice',call['symbols'][0]['name']);self.assertIn('kotlin.Int',call['symbols'][0]['signature']);self.assertNotIn('kotlin.String',call['symbols'][0]['signature']);self.assertEqual('PROJECT',call['symbols'][0]['origin'])
        span=call['span'];encoded=source.encode('utf-16-le');self.assertEqual('twice(4)',encoded[span['start']*2:span['end']*2].decode('utf-16-le'));self.assertEqual({f'A{i:02}' for i in range(1,13)},{r['fields']['category'] for r in census});self.assertEqual(len(census),len(observed))

    def test_missing_function_keeps_diagnostics_and_never_invents_a_target(self):
        rows=self.analyze('package fixture\nfun run():Int = missing(3)\n');calls=[r for r in rows if r.get('kind')=='OBSERVATION' and r['fields'].get('role')=='call'];self.assertEqual(1,len(calls));self.assertIn(calls[0]['fields']['status'],('UNRESOLVED','ERROR'));self.assertEqual([],calls[0]['fields']['targets']);self.assertTrue(rows[-1]['diagnostics'])


if __name__=='__main__':unittest.main()
