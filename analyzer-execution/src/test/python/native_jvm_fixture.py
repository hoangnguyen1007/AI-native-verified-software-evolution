"""Real native compiler tests over authored strings only, never host repository indexing."""
import hashlib
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

WORKSPACE=Path(__file__).resolve().parents[4]
SOURCE=WORKSPACE/'analyzer-execution/src/main/resources/polyglot/jvm'


class JvmFixture(unittest.TestCase):
    engine='';language='';dialect='';suffix=''
    @classmethod
    def setUpClass(cls):
        cls.sdk=Path(os.environ.get('POLYGLOT_JVM_SDK',str(WORKSPACE/'.evo/tools/m845-jvm-20261027'))).resolve();cls.platform=(WORKSPACE/'analyzer/target/classes').resolve()
        if not (cls.sdk/'materials.json').is_file():raise RuntimeError('Pinned real JVM SDK required')
        cls.temporary=tempfile.TemporaryDirectory();cls.classes=Path(cls.temporary.name)/'classes';cls.classes.mkdir();cls.classpath=os.pathsep.join([str(cls.platform),str(cls.sdk/'*')])
        java_sources=[str(SOURCE/'NeutralJvm.java')]
        if cls.language!='scala':java_sources.append(str(SOURCE/(cls.engine+'.java')))
        subprocess.run(['javac','-proc:none','-cp',cls.classpath,'-d',str(cls.classes),*java_sources],check=True,stdout=subprocess.PIPE,stderr=subprocess.PIPE,timeout=60)
        if cls.language=='scala':
            compile_cp=os.pathsep.join([str(cls.platform),str(cls.classes),*[str(p) for p in sorted(cls.sdk.glob('*.jar'))]])
            compiled=subprocess.run(['java','-cp',cls.classpath,'scala.tools.nsc.Main','-classpath',compile_cp,'-d',str(cls.classes),str(SOURCE/'ScalaEngine.scala')],stdout=subprocess.PIPE,stderr=subprocess.PIPE,timeout=60)
            if compiled.returncode:raise RuntimeError(compiled.stderr.decode(errors='replace')[-8192:])
    @classmethod
    def tearDownClass(cls):cls.temporary.cleanup()
    def analyze(self,text):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory);files={'main.'+self.suffix:text} if isinstance(text,str) else text
            for name,value in files.items():(root/name).write_text(value,encoding='utf8',newline='')
            tool='sha256:'+hashlib.sha256((SOURCE/(self.engine+('.scala' if self.language=='scala' else '.java'))).read_bytes()+(SOURCE/'NeutralJvm.java').read_bytes()).hexdigest()
            classpath=[str(self.sdk/'kotlin-stdlib-1.9.25.jar')] if self.language=='kotlin' else [str(self.sdk/'scala-library-2.13.16.jar')] if self.language=='scala' else []
            request=dict(schema='native-jvm-request:m8.4-v1',language=self.language,dialect=self.dialect,scope='sha256:'+'1'*64,tool=tool,provider=dict(id='frontend.'+self.language+'-native',version=self.dialect+'-m8.4-v1'),repositoryScope='urn:test:authored-'+self.language,packageScope='app',sourceRoot=str(root),classpath=classpath,platformArtifacts=classpath,files=[dict(path=name,text=value,sha256='sha256:'+hashlib.sha256(value.encode()).hexdigest()) for name,value in sorted(files.items())])
            file=root/'request.json';file.write_text(json.dumps(request),encoding='utf8');classpath=os.pathsep.join([str(self.classes),self.classpath])
            result=subprocess.run(['java','-Xmx768m','-cp',classpath,'evo.polyglot.'+self.engine,str(file)],stdout=subprocess.PIPE,stderr=subprocess.PIPE,timeout=60)
            self.assertEqual(0,result.returncode,result.stderr[-4096:].decode(errors='replace'));rows=[json.loads(line) for line in result.stdout.decode('utf8').splitlines()];self.assertEqual('PRODUCER_ASSERTION',rows[0]['trust']);return rows
