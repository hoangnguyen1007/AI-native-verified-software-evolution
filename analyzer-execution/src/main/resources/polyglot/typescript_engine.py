#!/usr/bin/env python3
"""Fixed TS/JS checker recipe in the native SDK lane. No package lifecycle,
application module, build configuration or compiler plugin is evaluated."""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess
import sys

sys.path.insert(0,str(Path(__file__).resolve().parent))
from native_protocol import Output,canonical,identity,order,sha
from guest_semantic_runner import require_file,unique

def analyze(request,node,sdk):
    r=json.loads(Path(request).read_bytes(),object_pairs_hook=unique);language=r['language']
    if language not in ('typescript','javascript'):raise ValueError('TYPESCRIPT_NATIVE_LANGUAGE')
    out=Output(request,'native-typescript-request:m8.4-v1',language,('5.9.3',));base=Path(__file__).resolve().parent
    require_file(node,r['node']);require_file(base/'typescript-worker.cjs',r['worker']);require_file(Path(sdk)/'lib/typescript.js',r['compiler'])
    pin=json.loads((base/'typescript-tool.json').read_bytes(),object_pairs_hook=unique)
    if r['compiler']['digest']!='sha256:'+pin['compilerSha256']:raise ValueError('TYPESCRIPT_NATIVE_COMPILER_PIN')
    if json.loads((Path(sdk)/'package.json').read_bytes(),object_pairs_hook=unique)['version']!='5.9.3':raise ValueError('TYPESCRIPT_NATIVE_VERSION')
    libraries=[]
    for file in sorted((Path(sdk)/'lib').glob('lib.*.d.ts')):
        raw=file.read_bytes();libraries.append(dict(path='toolchain/lib/'+file.name,text=raw.decode('utf8'),sha256=sha(raw)))
    if not libraries:raise ValueError('TYPESCRIPT_NATIVE_PLATFORM_DECLARATIONS_REQUIRED')
    compiler_input=dict(schema='typescript-capture-request:m8.4-v1',scope=r['scope'],repositoryScope=r['repositoryScope'],packageScope=r['packageScope'],language=language,projectFiles=list(out.sources),files=[dict(path=s.path,text=s.text,sha256=s.digest) for s in out.sources.values()]+libraries,options=r['options'])
    result=subprocess.run([str(node),str(base/'typescript-worker.cjs'),str(Path(sdk)/'lib/typescript.js')],input=canonical(compiler_input).encode('utf8'),stdout=subprocess.PIPE,stderr=subprocess.PIPE,timeout=120)
    if result.returncode:raise ValueError('TYPESCRIPT_NATIVE_CHECKER_OPERATION_FAILED')
    rows=[json.loads(line,object_pairs_hook=unique) for line in result.stdout.splitlines()];header=rows[0];end=rows[-1]
    if (header['schema']!='typescript-capture-result:m8.4-v2' or header['scope']!=r['scope'] or header['language']!=language or header['engine']!='5.9.3' or header['extractor']!=r['worker']['digest'] or end['schema']!='typescript-capture-end:m8.4-v1' or end['scope']!=r['scope']):raise ValueError('TYPESCRIPT_NATIVE_CHECKER_BINDING')
    documents={};questions={};observations={}
    for row in rows[1:-1]:
        if row['scope']!=r['scope']:raise ValueError('TYPESCRIPT_NATIVE_ROW_SCOPE')
        target={'DOCUMENT':documents,'CENSUS':questions,'OBSERVATION':observations}.get(row['kind'])
        if target is None or row['key'] in target:raise ValueError('TYPESCRIPT_NATIVE_ROW_KIND_OR_DUPLICATE')
        target[row['key']]=row
    if set(documents)!=set(out.sources) or set(questions)!=set(observations) or len(documents)!=end['documents'] or len(questions)!=end['census'] or len(observations)!=end['observations']:raise ValueError('TYPESCRIPT_NATIVE_CENSUS_NOT_CLOSED')
    for key,row in questions.items():
        observed=observations[key]
        if observed['fields']['obligation']!=identity(row):raise ValueError('TYPESCRIPT_NATIVE_OBLIGATION_BINDING')
        document=row['fields']['document']
        if document not in documents:raise ValueError('TYPESCRIPT_NATIVE_DOCUMENT_BINDING')
        for fields in (row['fields'],observed['fields']):
            if fields.get('span') is not None:fields['span']=dict(fields['span'],path=document)
        observed['fields']['obligation']=identity(row)
        observed['fields']['role']=row['fields']['role']
        if row['fields']['category']=='A06' and row['fields']['role']=='call':observed['fields']['callerStatus']='RESOLVED' if observed['fields'].get('caller') else 'UNRESOLVED'
    def write(value):sys.stdout.buffer.write((canonical(value)+'\n').encode('utf8'))
    write(dict(schema='language-capture-result:m8.4-v1',scope=r['scope'],language=language,dialect=r['dialect'],provider=r['provider'],tool=r['tool'],trust='PRODUCER_ASSERTION'))
    for key,row in sorted(documents.items(),key=lambda item:order(item[0])):
        expected=out.sources[key]
        if row['fields']['content']!=dict(digest=expected.digest,size=len(expected.raw)):raise ValueError('TYPESCRIPT_NATIVE_DOCUMENT_BYTES')
        row['fields']['role']=expected.role;write(row)
    for table in (questions,observations):
        for key,row in sorted(table.items(),key=lambda item:order(item[0])):write(row)
    write(dict(schema='language-capture-end:m8.4-v1',scope=r['scope'],documents=len(documents),census=len(questions),observations=len(observations),diagnostics=end['diagnostics']))

if __name__=='__main__':
    parser=argparse.ArgumentParser()
    for name in ('request','node','sdk'):parser.add_argument('--'+name,type=Path,required=True)
    args=parser.parse_args();analyze(args.request,args.node,args.sdk)
