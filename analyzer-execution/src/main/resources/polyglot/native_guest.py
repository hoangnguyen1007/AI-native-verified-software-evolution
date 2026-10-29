#!/usr/bin/python3
"""Fixed native frontend recipes under the registered SDK worker containment policy.

Only root-prepared inputs/tool handles are accepted. There is no application command,
MSBuild/npm/pip hook or caller-selected entry point. All source remains immutable.
"""
import hashlib
import importlib.util
import json
import os
from pathlib import Path,PurePosixPath
import re
import subprocess
import sys
import tarfile

from guest_semantic_runner import digest,require_file,unique

PROFILES={'csharp':('native-roslyn-request:m8.4-v1','12.0'), 'visualbasic':('native-roslyn-request:m8.4-v1','16.9'),
          'c':('native-clang-request:m8.4-v1','c17'), 'cpp':('native-clang-request:m8.4-v1','c++20'), 'objectivec':('native-clang-request:m8.4-v1','gnu17'),
          'python':('native-python-request:m8.4-v1','3.10'), 'go':('native-go-request:m8.4-v1','1.24'),
          'dart':('native-dart-request:m8.4-v1','3.7'),'php':('native-php-request:m8.4-v1','8.1'),'rust':('native-rust-request:m8.4-v1','2021'),
          'ruby':('native-ruby-request:m8.4-v1','sorbet-0.6'),'swift':('native-swift-request:m8.4-v1','6'),'typescript':('native-typescript-request:m8.4-v1','5.9.3'),'javascript':('native-typescript-request:m8.4-v1','5.9.3')}
REQUIRED={'csharp':{'roslyn'},'visualbasic':{'roslyn'},'c':set(),'cpp':set(),'objectivec':set(),'python':{'pyright'},'go':{'go'},'dart':set(),'php':set(),'rust':set(),'ruby':set(),'swift':set(),'typescript':{'typescript'},'javascript':{'typescript'}}


def logical(path):
    if (not isinstance(path,str) or not path or path.startswith('/') or any(c in path for c in ('\\',':','\0'))
            or any(part in ('','.','..') for part in path.split('/'))):raise ValueError('NATIVE_RECIPE_LOGICAL_PATH')
    path.encode('utf8',errors='strict');return path


def validate(campaign):
    if set(campaign)!={'schema','assets','contexts'} or campaign['schema']!='authored-native-campaign:m8.4-v1' or not campaign['contexts']:raise ValueError('NATIVE_CAMPAIGN_SCHEMA')
    if set(campaign['assets'])-{'roslyn','go','pyright','typescript'}:raise ValueError('NATIVE_CAMPAIGN_ASSET_ROLE')
    required=set()
    for name,item in campaign['contexts'].items():
        if not re.fullmatch('[a-z][a-z0-9-]{0,63}',name) or set(item)!={'language','request'} or item['language'] not in PROFILES:raise ValueError('NATIVE_CONTEXT_NOT_REGISTERED')
        request=item['request'];schema,dialect=PROFILES[item['language']]
        if request['schema']!=schema or request['language']!=item['language'] or request['dialect']!=dialect:raise ValueError('NATIVE_CONTEXT_TUPLE_MISMATCH')
        required.update(REQUIRED[item['language']]);paths=set()
        for source in request['files']:
            path=logical(source['path'])
            if path in paths or 'sha256:'+hashlib.sha256(source['text'].encode('utf8',errors='strict')).hexdigest()!=source['sha256']:raise ValueError('NATIVE_CONTEXT_SOURCE_MEMBERSHIP_OR_HASH')
            paths.add(path)
        if not paths:raise ValueError('NATIVE_CONTEXT_SOURCE_MEMBERSHIP_REQUIRED')
    if set(campaign['assets'])!=required:raise ValueError('NATIVE_CAMPAIGN_EXACT_ASSET_MEMBERSHIP')


def handle(path):return dict(path=str(path),size=Path(path).stat().st_size,digest=digest(path))


def sdk_handle(path):
    value=handle(path);resolved=Path(path).resolve(strict=True)
    if Path('/opt/evo-toolset') not in resolved.parents:raise ValueError('NATIVE_SDK_TOOL_ESCAPES_READONLY_MEDIA')
    if resolved!=Path(path):value['resolvedPath']=str(resolved)
    return value


def source_location(request):
    from native_protocol import identity
    # Stable physical source placement is semantic input for __FILE__, file! and
    # #file. Attempt IDs are operational and must never change these values.
    return Path('/opt/evo-native-inputs')/identity(['native-source-layout:m8.4-v1',request['scope'],request['files']])[7:]/'src'


def unpack_pyright(archive,root,version='1.1.396'):
    root.mkdir();size=0;names=set()
    with tarfile.open(archive,'r:gz') as source:
        for member in source:
            name=logical(member.name)
            if name.split('/')[0]!='package' or name in names:raise ValueError('PYRIGHT_SDK_ARCHIVE_MEMBERSHIP')
            names.add(name);path=root/name
            if member.isdir():path.mkdir(parents=True,exist_ok=True);continue
            if not member.isfile() or not 0<=member.size<=268435456-size:raise ValueError('PYRIGHT_SDK_ARCHIVE_WORKER_REQUIRED')
            size+=member.size;path.parent.mkdir(parents=True,exist_ok=True)
            with source.extractfile(member) as data,path.open('xb') as target:
                import shutil
                shutil.copyfileobj(data,target,65536)
            if path.stat().st_size!=member.size:raise ValueError('PYRIGHT_SDK_ARCHIVE_TRUNCATED')
            path.chmod(0o444)
    if json.loads((root/'package/package.json').read_bytes(),object_pairs_hook=unique)['version']!=version:raise ValueError('PYRIGHT_SDK_VERSION')
    for directory in sorted((p for p in root.rglob('*') if p.is_dir()),key=lambda p:len(p.parts),reverse=True):directory.chmod(0o555)
    root.chmod(0o555);return root/'package'


def prepare(registration,base):
    """Root management phase, before unprivileged target analysis sees any input."""
    campaign=registration['nativeCampaign'];validate(campaign);base=Path(base);sdk=Path(registration['sdkRoot']);tools=base/'native-tools';tools.mkdir(mode=0o755)
    for asset in campaign['assets'].values():require_file(asset['path'],asset)
    extras={}
    if 'pyright' in campaign['assets']:extras['pyright']=str(unpack_pyright(campaign['assets']['pyright']['path'],tools/'pyright'))
    if 'typescript' in campaign['assets']:extras['typescript']=str(unpack_pyright(campaign['assets']['typescript']['path'],tools/'typescript','5.9.3'))
    python=registration['runtime']['python']['path'];wrapper=tools/'python-isolated'
    if not re.fullmatch('/[A-Za-z0-9_./+-]+',python):raise ValueError('NATIVE_IMAGE_INTERPRETER_PATH')
    wrapper.write_text('#!/bin/sh\nexec '+python+' -I -S "$@"\n',encoding='utf8');wrapper.chmod(0o555)
    if any(item['language']=='php' for item in campaign['contexts'].values()):
        php=tools/'php-isolated';library=str(sdk/'php/usr/lib/x86_64-linux-gnu');binary=str(sdk/'php/usr/bin/php8.1');extensions=str(sdk/'php/usr/lib/php/20210902')
        if any(not re.fullmatch('/[A-Za-z0-9_./+-]+',path) for path in (library,binary,extensions)):raise ValueError('PHP_FIXED_SDK_PATH')
        php.write_text('#!/bin/sh\nexport LD_LIBRARY_PATH='+library+'\nexec '+binary+' -n -d extension_dir='+extensions+' '+ ' '.join('-d extension='+name+'.so' for name in ('ctype','tokenizer','phar','mbstring','iconv'))+' "$@"\n',encoding='utf8');php.chmod(0o555);extras['php']=str(php)
    version=subprocess.run([python,'-I','-S','-c','import sys;print(sys.version.split()[0])'],stdout=subprocess.PIPE,stderr=subprocess.PIPE,check=True,timeout=10).stdout.decode('ascii').strip()
    if 'roslyn' in campaign['assets']:
        roslyn=tools/'roslyn';roslyn.mkdir();asset=campaign['assets']['roslyn'];os.link(asset['path'],roslyn/'RoslynEngine.dll')
        for file in (sdk/'dotnet/sdk/8.0.406/Roslyn/bincore').glob('Microsoft.CodeAnalysis*.dll'):os.symlink(file,roslyn/file.name)
        (roslyn/'RoslynEngine.runtimeconfig.json').write_text(json.dumps({'runtimeOptions':{'tfm':'net8.0','rollForward':'Disable','framework':{'name':'Microsoft.NETCore.App','version':'8.0.13'}}}),encoding='utf8')
        for path in roslyn.iterdir():
            if not path.is_symlink():path.chmod(0o444)
        roslyn.chmod(0o555);extras['roslyn']=str(roslyn/'RoslynEngine.dll')
    import pwd
    user=pwd.getpwnam('evobuild');prepared={}
    for name,item in sorted(campaign['contexts'].items()):
        context=base/'native-inputs'/name;context.mkdir(parents=True);request=dict(item['request']);source=source_location(request)
        reuse=source.exists();source.mkdir(parents=True,exist_ok=True)
        if source.is_symlink() or any(parent.is_symlink() for parent in source.parents):raise ValueError('NATIVE_SOURCE_LAYOUT_ALIAS')
        request['sourceRoot']=str(source);request['sourceMaterialization']='native-source-layout:m8.4-v1'
        expected={logical(entry['path']) for entry in request['files']}
        if reuse and {p.relative_to(source).as_posix() for p in source.rglob('*') if p.is_file()}!=expected:raise ValueError('NATIVE_SOURCE_LAYOUT_MEMBERSHIP')
        for entry in request['files']:
            file=source/logical(entry['path']);raw=entry['text'].encode('utf8')
            if reuse:
                if file.is_symlink() or file.read_bytes()!=raw:raise ValueError('NATIVE_SOURCE_LAYOUT_BYTES_CHANGED')
            else:
                file.parent.mkdir(parents=True,exist_ok=True)
                with file.open('xb') as output:output.write(raw)
                file.chmod(0o444)
        for directory in sorted((p for p in source.rglob('*') if p.is_dir()),key=lambda p:len(p.parts),reverse=True):directory.chmod(0o555)
        source.chmod(0o555)
        work=Path(registration['work'])/'native'/name;work.mkdir(parents=True,mode=0o755);home=work/'home';home.mkdir(mode=0o700);os.chown(home,user.pw_uid,user.pw_gid)
        state=work/'state';state.mkdir(mode=0o700);os.chown(state,user.pw_uid,user.pw_gid);request['workRoot']=str(state)
        language=item['language']
        if language in ('csharp','visualbasic'):
            request['compilerVersion']='4.11.0.0';request['references']=[dict(path=str(path),size=path.stat().st_size,sha256=digest(path),origin='PLATFORM') for path in sorted((sdk/'dotnet/packs/Microsoft.NETCore.App.Ref/8.0.13/ref/net8.0').glob('*.dll'))]
            if not request['references']:raise ValueError('ROSLYN_CAPTURED_REFERENCE_PLATFORM_REQUIRED')
        elif language in ('c','cpp','objectivec'):
            library=sdk/'libclang/libclang-18.1.1.data/platlib/clang/native/libclang.so';bindings=library.parents[2]
            request['library']={k:v for k,v in handle(library).items() if k!='path'};request['bindings']={k:v for k,v in handle(bindings/'clang/cindex.py').items() if k!='path'}
        elif language=='python':
            request.update(parserVersion=version,node={k:v for k,v in handle(sdk/'node/bin/node').items() if k!='path'},python={k:v for k,v in handle(wrapper).items() if k!='path'},
                           server={k:v for k,v in handle(Path(extras['pyright'])/'langserver.index.js').items() if k!='path'},cli={k:v for k,v in handle(Path(extras['pyright'])/'index.js').items() if k!='path'})
        elif language=='go':request['engine']={k:v for k,v in campaign['assets']['go'].items() if k!='path'}
        elif language in ('typescript','javascript'):request.update(node={k:v for k,v in handle(sdk/'node/bin/node').items() if k!='path'},compiler={k:v for k,v in handle(Path(extras['typescript'])/'lib/typescript.js').items() if k!='path'},worker={k:v for k,v in handle(base/'typescript-worker.cjs').items() if k!='path'})
        elif language=='dart':
            request.update(sdkPath=str(sdk/'dart'),runtime=handle(sdk/'dart/bin/dart'),packages=handle(sdk/'dart-analyzer/package_config.json'),engine={k:v for k,v in handle(base/'dart_engine.dart').items() if k!='path'})
        elif language=='php':request.update(runtime={k:v for k,v in handle(Path(extras['php'])).items() if k!='path'},analyzer=handle(sdk/'phpstan/phpstan.phar'),engine={k:v for k,v in handle(base/'php_engine.php').items() if k!='path'})
        elif language=='rust':request.update(analyzer=handle(sdk/'rust-analyzer/bin/rust-analyzer'),compiler=handle(sdk/'rust/bin/rustc'))
        elif language=='ruby':request['analyzer']=handle(sdk/'sorbet/libexec/sorbet')
        elif language=='swift':request.update(compiler=sdk_handle(sdk/'swift/usr/bin/swiftc'),indexLibrary=sdk_handle(sdk/'swift/usr/lib/libIndexStore.so'),censusEngine={k:v for k,v in handle(base/'SwiftCensus.swift').items() if k!='path'},syntaxRuntime=sdk_handle(sdk/'swift/usr/bin/swift'),syntaxLibraries=str(sdk/'swift/usr/lib/swift/host'),syntaxHeaders=str(sdk/'swift-headers'),moduleCachePolicy='PRIVATE_UNIT_TMPFS_64M_EPHEMERAL')
        raw=json.dumps(request,sort_keys=True,separators=(',',':'),ensure_ascii=False).encode('utf8');file=context/'request.json';file.write_bytes(raw);file.chmod(0o444);context.chmod(0o555)
        # This exact raw prepared request is exported as audit/input evidence, separate
        # from stable semantic identities and the immutable blueprint registration.
        (work/'prepared-request.json').write_bytes(raw)
        prepared[name]=dict(language=language,request=handle(file))
    registration['preparedNative']=dict(contexts=prepared,extras=extras,pythonWrapper=handle(wrapper));return registration


def command(registration,base,name):
    base=PurePosixPath(base);sdk=PurePosixPath(registration['sdkRoot']);native=registration['preparedNative'];context=native['contexts'][name];request=context['request']['path'];language=context['language'];python=registration['runtime']['python']['path']
    if language in ('csharp','visualbasic'):return [str(sdk/'dotnet/dotnet'),native['extras']['roslyn'],request]
    if language in ('c','cpp','objectivec'):
        bindings=sdk/'libclang/libclang-18.1.1.data/platlib';return [python,'-I',str(base/'clang_engine.py'),'--request',request,'--bindings',str(bindings),'--library',str(bindings/'clang/native/libclang.so')]
    if language=='python':return [python,'-I',str(base/'python_engine.py'),'--request',request,'--node',str(sdk/'node/bin/node'),'--sdk',native['extras']['pyright'],'--python',native['pythonWrapper']['path']]
    if language=='go':return [python,'-I',str(base/'go_engine.py'),'--request',request,'--engine',registration['nativeCampaign']['assets']['go']['path']]
    if language in ('typescript','javascript'):return [python,'-I',str(base/'typescript_engine.py'),'--request',request,'--node',str(sdk/'node/bin/node'),'--sdk',native['extras']['typescript']]
    if language=='dart':return [python,'-I',str(base/'dart_engine.py'),'--request',request,'--dart',str(sdk/'dart/bin/dart'),'--packages',str(sdk/'dart-analyzer/package_config.json'),'--engine',str(base/'dart_engine.dart')]
    if language=='php':return [python,'-I',str(base/'php_engine.py'),'--request',request,'--php',native['extras']['php'],'--analyzer',str(sdk/'phpstan/phpstan.phar'),'--engine',str(base/'php_engine.php')]
    if language=='rust':return [python,'-I',str(base/'rust_engine.py'),'--request',request,'--analyzer',str(sdk/'rust-analyzer/bin/rust-analyzer'),'--compiler',str(sdk/'rust/bin/rustc')]
    if language=='ruby':return [python,'-I',str(base/'ruby_engine.py'),'--request',request,'--sorbet',str(sdk/'sorbet/libexec/sorbet')]
    if language=='swift':return [python,'-I',str(base/'swift_engine.py'),'--request',request,'--compiler',str(sdk/'swift/usr/bin/swiftc'),'--census',str(base/'SwiftCensus.swift')]
    raise ValueError('NATIVE_ENTRYPOINT_NOT_REGISTERED')


def run(registration,base):
    base=Path(base);spec=importlib.util.spec_from_file_location('native_cgroup_primitive',base/'base-runner.py');runner=importlib.util.module_from_spec(spec);spec.loader.exec_module(runner);results={}
    for name,context in sorted(registration['preparedNative']['contexts'].items()):
        request=require_file(context['request']['path'],context['request']);work=Path(registration['work'])/'native'/name
        argv=command(registration,base,name)
        if context['language'] in ('csharp','visualbasic'):argv=['/usr/bin/env','DOTNET_ROOT='+registration['sdkRoot']+'/dotnet','DOTNET_ROLL_FORWARD=Disable','DOTNET_CLI_TELEMETRY_OPTOUT=1','DOTNET_MULTILEVEL_LOOKUP=0',*argv]
        native=dict(attempt=registration['attempt']+'-'+name,work=str(work),memoryBytes=2147483648,tasksMax=256,cpuPercent=100,argv=argv)
        checked=[part.replace('RuntimeMaxSec=3600','RuntimeMaxSec=180') for part in runner.build_command(native,work)];output=work/'capture.ndjson';errors=work/'stderr'
        with output.open('xb') as out,errors.open('xb') as err:
            try:exit_code=subprocess.run(checked,stdin=subprocess.DEVNULL,stdout=out,stderr=err,timeout=210).returncode
            except subprocess.TimeoutExpired:exit_code=124
        require_file(request,context['request']);results[name]=dict(exit=exit_code,output=handle(output),stderr=handle(errors))
    return dict(schema='registered-native-campaign-result:m8.4-v1',results=results,trust='PRODUCER_ASSERTION',qualified=False)
