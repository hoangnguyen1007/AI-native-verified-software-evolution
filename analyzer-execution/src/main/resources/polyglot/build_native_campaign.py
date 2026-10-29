#!/usr/bin/env python3
"""Build first-party native adapter assets and precommit authored worker contexts.

Compiles only platform-owned Roslyn/Go adapter sources. No analyzed project, package
installer, target plugin, MSBuild project or application main method runs on the host.
"""
import argparse
import base64
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess

from native_protocol import canonical,identity,order,sha
from guest_semantic_runner import digest,unique
from native_guest import PROFILES,REQUIRED,validate

BASE=Path(__file__).resolve().parent


def handle(path):return dict(path=str(path.resolve()),size=path.stat().st_size,digest=digest(path))


def build(workspace,cases,output,media_path=None):
    workspace=workspace.resolve();output=output.resolve()
    if output.exists():raise ValueError('NATIVE_ASSET_REVISION_ALREADY_EXISTS')
    fixture=json.loads(cases.read_bytes(),object_pairs_hook=unique)
    if fixture['schema']!='authored-native-cases:m8.4-v1':raise ValueError('NATIVE_CASES_SCHEMA')
    languages={case['language'] for case in fixture['cases'].values()};required=set().union(*(REQUIRED[language] for language in languages));output.mkdir();assets={}
    materials={}
    if 'typescript' in required:
        pin=json.loads((BASE/'typescript-tool.json').read_bytes(),object_pairs_hook=unique);source=workspace/'.evo/tools/typescript-5.9.3.tgz'
        if 'sha512-'+base64.b64encode(hashlib.sha512(source.read_bytes()).digest()).decode()!=pin['packageIntegrity']:raise ValueError('TYPESCRIPT_PACKAGE_INTEGRITY_CHANGED')
        target=output/'typescript-5.9.3.tgz';shutil.copyfile(source,target);assets['typescript']=handle(target);materials['typescript']=pin
    if 'roslyn' in required:
        compiler=workspace/'.evo/tools/m845-roslyn-il-4.11.0';sdk=workspace/'.evo/tools/m845-dotnet-api-v1'
        material=json.loads((compiler/'material.json').read_bytes(),object_pairs_hook=unique)
        if digest(compiler/'microsoft.net.compilers.toolset.4.11.0.nupkg')!='sha256:'+material['sha256']:raise ValueError('ROSLYN_COMPILER_PACKAGE_CHANGED')
        refs=sorted((sdk/'packs/Microsoft.NETCore.App.Ref').rglob('*.dll'));api=sorted((compiler/'tasks/netcore/bincore').glob('Microsoft.CodeAnalysis*.dll'))
        if not refs or len(api)!=3:raise ValueError('ROSLYN_EXACT_SDK_ASSEMBLIES_REQUIRED')
        target=output/'RoslynEngine.dll';command=['dotnet',str(compiler/'tasks/netcore/bincore/csc.dll'),'/nologo','/noconfig','/nostdlib+','/deterministic+','/langversion:12','/nullable:enable','/target:exe','/out:'+str(target),*['/r:'+str(path) for path in [*refs,*api]],str(BASE/'dotnet/RoslynEngine.cs')]
        env={name:value for name,value in os.environ.items() if name not in ('DOTNET_STARTUP_HOOKS','DOTNET_ADDITIONAL_DEPS','DOTNET_SHARED_STORE')};subprocess.run(command,env=env,check=True)
        assets['roslyn']=handle(target);materials['roslyn']=dict(package=digest(compiler/'microsoft.net.compilers.toolset.4.11.0.nupkg'),referenceAssemblies=[dict(name=path.name,size=path.stat().st_size,digest=digest(path)) for path in refs],compilerAssemblies=[dict(name=path.name,size=path.stat().st_size,digest=digest(path)) for path in api])
    if 'go' in required:
        sdk=workspace/'.evo/tools/m845-go-win-1.24.0';material=json.loads((sdk/'material.json').read_bytes(),object_pairs_hook=unique)
        if digest(sdk/'go1.24.0.windows-amd64.zip')!='sha256:'+material['sha256']:raise ValueError('GO_COMPILER_SDK_CHANGED')
        target=output/'go-engine-linux-amd64';env=dict(os.environ,GOENV='off',GOFLAGS='',GOROOT=str(sdk/'go'),GOTOOLCHAIN='local',GOWORK='off',GO111MODULE='off',CGO_ENABLED='0',GOPROXY='off',GOOS='linux',GOARCH='amd64')
        subprocess.run([str(sdk/'go/bin/go.exe'),'build','-trimpath','-buildvcs=false','-o',str(target),str(BASE/'go/go_engine.go')],env=env,check=True);assets['go']=handle(target);materials['go']=dict(sdk=digest(sdk/'go1.24.0.windows-amd64.zip'),compiler=digest(sdk/'go/bin/go.exe'),source=digest(BASE/'go/go_engine.go'))
    if 'pyright' in required:
        sdk=workspace/'.evo/tools/m845-native-extra-20261027';items=json.loads((sdk/'materials.json').read_bytes(),object_pairs_hook=unique)['artifacts'];item=next(item for item in items if item['file']=='pyright-1.1.396.tgz');source=sdk/item['file']
        if source.stat().st_size!=item['size'] or digest(source)!='sha256:'+item['sha256']:raise ValueError('PYRIGHT_PACKAGE_CHANGED')
        target=output/'pyright-1.1.396.tgz';shutil.copyfile(source,target);assets['pyright']=handle(target);materials['pyright']=item
    media=json.loads((media_path or workspace/'.evo/tools/m845-sdk-media-v1.json').read_bytes(),object_pairs_hook=unique)
    sources={str(path.relative_to(BASE)).replace('\\','/'):digest(path) for path in [BASE/name for name in ('native_guest.py','native_protocol.py','typescript_engine.py','typescript-worker.cjs','typescript-tool.json','lsp_client.py','native_lsp_projection.py','python_engine.py','go_engine.py','go/go_engine.go','clang_engine.py','dotnet/RoslynEngine.cs','dart_engine.py','dart/dart_engine.dart','php_engine.py','php/php_engine.php','rust_engine.py','ruby_engine.py','swift_engine.py','swift_indexstore.py','swift/SwiftCensus.swift')]}
    tool=identity(['native-sdk-adapter-toolset:m8.4-v1',media['manifest'],{name:value['digest'] for name,value in assets.items()},sources,materials]);contexts={}
    for name,case in sorted(fixture['cases'].items()):
        language=case['language'];schema,dialect=PROFILES[language];files=[dict(path=path,text=text,sha256=sha(text.encode('utf8'))) for path,text in sorted(case['files'].items(),key=lambda item:order(item[0]))]
        inventory=identity([[file['path'],file['sha256']] for file in files]);variant=case.get('variant','default');scope=identity(['language-context:m8.4-v1',inventory,'authored','app','main',variant,language,dialect])
        request=dict(schema=schema,language=language,dialect=dialect,variant=variant,scope=scope,tool=tool,repositoryScope='urn:test:authored-'+language,packageScope='app',provider=dict(id='frontend.'+language+'-native',version='m8.4-v1'),files=files)
        if language in ('c','cpp','objectivec'):
            from clang_engine import PROVIDER
            request['provider']=dict(PROVIDER)
        elif language in ('python','go','ruby','swift'):request['provider']['version']='m8.4-v2'
        elif language in ('dart','php'):request['provider']['version']='m8.4-v3'
        elif language=='rust':request['provider']['version']='m8.4-v4'
        if language in ('typescript','javascript'):request['options']=dict(target='ES2022',module='CommonJS',moduleResolution='Node10',strict=True,allowJs=True,checkJs=True)
        if language in ('csharp','visualbasic'):request['symbols']=[]
        elif language in ('c','cpp','objectivec'):
            native={'c':'c','cpp':'c++','objectivec':'objective-c'}[language];request.update(translationUnit=files[0]['path'],arguments=['-x',native,'-std='+dialect,'-fsyntax-only','--target=x86_64-unknown-linux-gnu','-nostdinc','-nostdinc++'],originRoots=[])
        elif language=='python':request['moduleNames']={file['path']:file['path'].removesuffix('.py').removesuffix('.pyi').replace('/','.') for file in files}
        elif language=='go':request.update(packagePath='example.test/app',goarch='amd64',goos='linux',tags=case.get('tags',[]),cgoEnabled=False,exports={})
        elif language=='rust':request['crate']=dict(root='lib.rs',name='fixture',cfg=case.get('cfg',[]),target=None)
        elif language=='swift':request['moduleName']='Fixture'
        contexts[name]=dict(language=language,request=request)
    for name,original in fixture['replays'].items():contexts[name]=contexts[original]
    campaign=dict(schema='authored-native-campaign:m8.4-v1',assets=assets,contexts=contexts);validate(campaign)
    (output/'campaign.json').write_text(canonical(campaign),encoding='utf8',newline='')
    (output/'tool.json').write_text(canonical(dict(schema='native-sdk-adapter-materials:m8.4-v1',tool=tool,sources=sources,materials=materials,sdk=media['manifest'],assets=assets,cases=handle(cases),qualified=False)),encoding='utf8',newline='')
    return dict(path=str(output/'campaign.json'),contexts=len(contexts),tool=tool,qualified=False)


if __name__=='__main__':
    parser=argparse.ArgumentParser()
    for name in ('workspace','cases','output'):parser.add_argument('--'+name,type=Path,required=True)
    parser.add_argument('--media',type=Path)
    args=parser.parse_args();print(json.dumps(build(args.workspace,args.cases,args.output,args.media),sort_keys=True))
