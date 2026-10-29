#!/usr/bin/env python3
"""Package trusted platform adapter code and authored native controls, never target builds.

Source/SDK/class-byte pins accompany the deployment assets. This is a functional worker
input bundle; its presence or successful compilation does not qualify semantic accuracy.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import zipfile

from guest_semantic_runner import unique

BASE=Path(__file__).resolve().parent
VERSIONS={'kotlin':'1.9.25','scala':'2.13.16','groovy':'4.0.27'}


def canonical(value):return json.dumps(value,sort_keys=True,separators=(',',':'),ensure_ascii=False).encode('utf8')
def sha(value):return 'sha256:'+hashlib.sha256(value).hexdigest()
def handle(path):return dict(size=path.stat().st_size,digest=sha(path.read_bytes()))


def jar(directory,target):
    with zipfile.ZipFile(target,'x',compression=zipfile.ZIP_DEFLATED) as out:
        for file in sorted(directory.rglob('*.class')):
            info=zipfile.ZipInfo(file.relative_to(directory).as_posix(),date_time=(1980,1,1,0,0,0));info.compress_type=zipfile.ZIP_DEFLATED
            info.external_attr=0o100444<<16;out.writestr(info,file.read_bytes())


def build(sdk,platform,cases,output):
    sdk=sdk.resolve();platform=platform.resolve();output=output.resolve()
    if output.exists():raise ValueError('JVM_BUNDLE_REVISION_ALREADY_EXISTS')
    materials=json.loads((sdk/'materials.json').read_bytes(),object_pairs_hook=unique)
    artifacts=materials.get('artifacts',materials.get('materials',[]))
    # The acquisition manifest is not authority: verify the exact recorded bytes now.
    for item in artifacts:
        file=sdk/item['path']
        if handle(file)!={'size':item['size'],'digest':'sha256:'+item['sha256']}:raise ValueError('JVM_SDK_BYTES_CHANGED')
    if len(artifacts)!=13:raise ValueError('JVM_SDK_CLOSED_ARTIFACT_MEMBERSHIP_REQUIRED')
    fixture=json.loads(cases.read_bytes(),object_pairs_hook=unique)
    if fixture['schema']!='authored-jvm-cases:m8.4-v1':raise ValueError('JVM_CASES_SCHEMA')
    output.mkdir();classes=output/'classes';classes.mkdir();classpath=os.pathsep.join([str(platform),str(sdk/'*')])
    subprocess.run(['javac','-proc:none','-cp',classpath,'-d',str(classes),*[str(BASE/'jvm'/name) for name in ('NeutralJvm.java','KotlinEngine.java','GroovyEngine.java')]],check=True)
    compile_cp=os.pathsep.join([str(platform),str(classes),*[str(file) for file in sorted(sdk.glob('*.jar'))]])
    subprocess.run(['java','-cp',classpath,'scala.tools.nsc.Main','-classpath',compile_cp,'-d',str(classes),str(BASE/'jvm/ScalaEngine.scala')],check=True)
    jar(platform,output/'platform.jar');jar(classes,output/'adapters.jar')
    assets={name:dict(path=str(output/(name+'.jar')),**handle(output/(name+'.jar'))) for name in ('platform','adapters')}
    source_pins={file.name:sha(file.read_bytes()) for file in sorted((BASE/'jvm').iterdir()) if file.is_file()}
    tool=sha(canonical(['native-jvm-toolset:m8.4-v1',assets['platform']['digest'],assets['adapters']['digest'],sha((sdk/'materials.json').read_bytes()),source_pins]))
    contexts={}
    for name,case in sorted(fixture['cases'].items()):
        language=case['language'];version=VERSIONS[language];files=[dict(path=path,text=text,sha256=sha(text.encode('utf8'))) for path,text in sorted(case['files'].items())]
        inventory=sha(canonical([(file['path'],file['sha256']) for file in files]));scope=sha(canonical(['language-context:m8.4-v1',inventory,'authored','app','main','default',language,version]))
        request=dict(schema='native-jvm-request:m8.4-v1',language=language,dialect=version,scope=scope,tool=tool,provider=dict(id='frontend.'+language+'-native',version=version+'-m8.4-v2'),repositoryScope='urn:test:authored-'+language,packageScope='app',files=files)
        contexts[name]=dict(language=language,request=request)
    for name,original in fixture['replays'].items():contexts[name]=contexts[original]
    campaign=dict(schema='authored-jvm-campaign:m8.4-v1',**assets,contexts=contexts)
    (output/'campaign.json').write_bytes(canonical(campaign))
    (output/'tool.json').write_bytes(canonical(dict(schema='native-jvm-tool-materials:m8.4-v1',tool=tool,sources=source_pins,sdk=handle(sdk/'materials.json'),assets=assets,cases=handle(cases),trust='PINNED_TOOL_BYTES_ONLY',qualified=False)))
    return dict(path=str(output/'campaign.json'),tool=tool,contexts=len(contexts),qualified=False)


if __name__=='__main__':
    parser=argparse.ArgumentParser()
    for name in ('sdk','platform','cases','output'):parser.add_argument('--'+name,type=Path,required=True)
    args=parser.parse_args();print(json.dumps(build(args.sdk,args.platform,args.cases,args.output),sort_keys=True))
