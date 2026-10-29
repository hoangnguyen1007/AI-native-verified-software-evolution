#!/usr/bin/python3
"""Root-owned fixed SDK probes under the existing cgroup containment primitive.
No caller argv, application execution, credential inheritance or semantic promotion.
"""
import importlib.util
import json
import os
from pathlib import Path
import re
import subprocess
import sys

from guest_semantic_runner import digest,require_file,unique

CLANG_VERSION='''import ctypes,sys
class CXString(ctypes.Structure):
 _fields_=[('data',ctypes.c_void_p),('private_flags',ctypes.c_uint)]
lib=ctypes.CDLL(sys.argv[1]);lib.clang_getClangVersion.restype=CXString
lib.clang_getCString.argtypes=[CXString];lib.clang_getCString.restype=ctypes.c_char_p
lib.clang_disposeString.argtypes=[CXString]
value=lib.clang_getClangVersion()
try: print(lib.clang_getCString(value).decode('utf8'))
finally: lib.clang_disposeString(value)
'''

RUBY_SHAPE='# typed: true\nclass Counter\n def self.twice(n)\n  n*2\n end\n def self.run\n  twice(4)\n end\nend\n'
RUST_SHAPE='pub fn twice(n: i32) -> i32 { n*2 }\npub fn run() -> i32 { twice(4) }\n'
SWIFT_SHAPE='func twice(_ n: Int) -> Int { n*2 }\nfunc run() -> Int { twice(4) }\n'
BASE_PROBES=('node','go','dotnet','dart','ruby','clang','kotlin','scala','groovy')
EXTRA_PROBES=('rust','swift','php','ruby-parse-shape','ruby-symbol-shape','rust-parse-shape','swift-parse-shape','swift-typed-shape')


def probe_inputs():return {'rust-parse-shape':RUST_SHAPE,'swift-parse-shape':SWIFT_SHAPE,'swift-typed-shape':SWIFT_SHAPE}


def commands(registration):
    sdk=registration['sdkRoot'];java=registration['runtime'].get('java',{}).get('path');python=registration['runtime']['python']['path'];jvm=sdk+'/jvm/'
    kotlin=[jvm+name for name in ('kotlin-compiler-embeddable-1.9.25.jar','kotlin-stdlib-1.9.25.jar','kotlin-script-runtime-1.9.25.jar','kotlin-reflect-1.6.10.jar','kotlin-daemon-embeddable-1.9.25.jar','trove4j-1.0.20200330.jar','annotations-24.1.0.jar')]
    scala=[jvm+name for name in ('scala-compiler-2.13.16.jar','scala-library-2.13.16.jar','scala-reflect-2.13.16.jar','java-diff-utils-4.15.jar','jline-3.27.1.jar')]
    baseline=dict(node=[sdk+'/node/bin/node','--version'],go=[sdk+'/go/bin/go','version'],dotnet=[sdk+'/dotnet/dotnet','--version'],dart=[sdk+'/dart/bin/dart','--version'],
                ruby=[sdk+'/sorbet/libexec/sorbet','--version'],clang=[python,'-I','-c',CLANG_VERSION,sdk+'/libclang/libclang-18.1.1.data/platlib/clang/native/libclang.so'],
                kotlin=[java,'-cp',':'.join(kotlin),'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','-version','-no-stdlib','-no-reflect'],
                scala=[java,'-cp',':'.join(scala),'scala.tools.nsc.Main','-version'],groovy=[java,'-cp',jvm+'groovy-4.0.27.jar','groovy.ui.GroovyMain','--version'])
    extras={'rust':[sdk+'/rust/bin/rustc','--version','--verbose'],'swift':[sdk+'/swift/usr/bin/swiftc','--version'],
            'php':['/usr/bin/env','LD_LIBRARY_PATH='+sdk+'/php/usr/lib/x86_64-linux-gnu',sdk+'/php/usr/bin/php8.1','-n','--version'],
            'ruby-parse-shape':[sdk+'/sorbet/libexec/sorbet','--no-config','--stop-after=parser','--print=parse-tree-json-with-locs','-e',RUBY_SHAPE],
            'ruby-symbol-shape':[sdk+'/sorbet/libexec/sorbet','--no-config','--no-stdlib','--stop-after=resolver','--print=symbol-table-json','-e',RUBY_SHAPE],
            'rust-parse-shape':[sdk+'/rust-analyzer/bin/rust-analyzer','parse'],
            'swift-parse-shape':[sdk+'/swift/usr/bin/swiftc','-frontend','-dump-parse','-module-name','Fixture','-'],
            'swift-typed-shape':[sdk+'/swift/usr/bin/swiftc','-frontend','-typecheck','-dump-ast','-module-name','Fixture','-']}
    names=registration.get('probeNames',list(BASE_PROBES))
    if not isinstance(names,list) or len(set(names))!=len(names) or set(names)-set((*BASE_PROBES,*EXTRA_PROBES)):raise ValueError('SDK_PROBE_PROFILE_NOT_REGISTERED')
    if 'probeScope' in registration:
        if registration['probeScope']!='EXACT_NATIVE_CONTEXTS':raise ValueError('SDK_PROBE_SCOPE_UNSUPPORTED')
        contexts=registration.get('nativeCampaign',{}).get('contexts',{});languages={context['language'] for context in contexts.values()};required={'c':{'clang'},'cpp':{'clang'},'objectivec':{'clang'},'csharp':{'dotnet'},'visualbasic':{'dotnet'},'python':{'node'},'go':{'go'},'dart':{'dart'},'php':{'php'},'ruby':{'ruby','ruby-parse-shape','ruby-symbol-shape'},'rust':{'rust','rust-parse-shape'},'swift':{'swift','swift-parse-shape','swift-typed-shape'},'typescript':{'node'},'javascript':{'node'}}
        if not languages or not languages<=required.keys() or set(names)!=set().union(*(required[language] for language in languages)):raise ValueError('SDK_NATIVE_PROBE_DENOMINATOR_MISMATCH')
    elif not set(BASE_PROBES)<=set(names):raise ValueError('SDK_PROBE_PROFILE_NOT_REGISTERED')
    if set(names)&{'kotlin','scala','groovy'} and not java:raise ValueError('SDK_IMAGE_JAVA_RUNTIME_REQUIRED')
    allowed=dict(baseline,**extras);return {name:allowed[name] for name in names}


def validate(value,registration):
    if (not isinstance(value,dict) or set(value)!={'schema','attempt','recipe'} or value['schema']!='sdk-worker-control-request:m8.4-v1'
            or value['attempt']!=registration['attempt'] or not re.fullmatch('[a-f0-9]{32}',value['attempt']) or value['recipe']!='probe-versions'):
        raise ValueError('SDK_GUEST_RECIPE_NOT_REGISTERED')


def main():
    import pwd
    base=Path(__file__).resolve().parent
    registration=json.loads((base/'registration.json').read_bytes(),object_pairs_hook=unique)
    raw=sys.stdin.buffer.read(16385)
    if len(raw)>16384:raise ValueError('SDK_GUEST_REQUEST_WORKER_REQUIRED')
    request=json.loads(raw,object_pairs_hook=unique);validate(request,registration)
    sdk=Path(registration['sdkRoot'])
    if str(sdk)!='/opt/evo-toolset' or sdk.is_symlink() or not os.statvfs(sdk).f_flag&os.ST_RDONLY:raise ValueError('SDK_MEDIA_NOT_MOUNTED_READONLY')
    require_file(sdk/'TOOLSET.JSON',registration['manifest'])
    for tool in registration['runtime'].values():require_file(tool['path'],tool)
    require_file(base/'base-runner.py',registration['baseRunner'])
    spec=importlib.util.spec_from_file_location('sdk_cgroup_primitive',base/'base-runner.py');runner=importlib.util.module_from_spec(spec);spec.loader.exec_module(runner)
    root=Path('/work')/request['attempt']
    if str(root)!=registration['work'] or not root.is_dir() or any(p.is_symlink() for p in (root,*root.parents)):raise ValueError('SDK_WORK_ROOT_MISMATCH')
    account=pwd.getpwnam('evobuild');results={}
    for name,argv in commands(registration).items():
        context=root/'probes'/name;context.mkdir(parents=True,mode=0o755)
        home=context/'home';home.mkdir(mode=0o700);os.chown(home,account.pw_uid,account.pw_gid)
        native=dict(attempt=request['attempt']+'-'+name,work=str(context),memoryBytes=2147483648,tasksMax=256,cpuPercent=100,
                    argv=['/usr/bin/env','DOTNET_CLI_HOME='+str(home),'DOTNET_ROOT='+str(sdk/'dotnet'),'DOTNET_CLI_TELEMETRY_OPTOUT=1',
                          'DOTNET_SKIP_FIRST_TIME_EXPERIENCE=1','DOTNET_GENERATE_ASPNET_CERTIFICATE=false','DOTNET_NOLOGO=1','DART_SUPPRESS_ANALYTICS=true','CI=true',*argv])
        out=context/'stdout';err=context/'stderr'
        command=runner.build_command(native,context)
        command=[part.replace('RuntimeMaxSec=3600','RuntimeMaxSec=90') for part in command]
        with out.open('xb') as stdout,err.open('xb') as stderr:
            text=probe_inputs().get(name)
            result=subprocess.run(command,input=text.encode('utf8') if text is not None else None,stdin=subprocess.DEVNULL if text is None else None,stdout=stdout,stderr=stderr,check=False,timeout=120)
        results[name]=dict(exit=result.returncode,stdout=dict(size=out.stat().st_size,digest=digest(out)),stderr=dict(size=err.stat().st_size,digest=digest(err)))
    for tool in registration['runtime'].values():require_file(tool['path'],tool)
    require_file(sdk/'TOOLSET.JSON',registration['manifest'])
    response=dict(schema='sdk-native-version-probes:m8.4-v1',attempt=request['attempt'],results=results,qualified=False)
    if 'campaign' in registration:
        from jvm_guest import run
        response['native']=run(registration,base)
    elif 'nativeCampaign' in registration:
        from native_guest import prepare,run
        registration=prepare(registration,base)
        response['native']=run(registration,base)
    print(json.dumps(response,sort_keys=True))
    native_ok=all(value['exit']==0 for value in response.get('native',{}).get('results',{}).values())
    return 0 if all(value['exit']==0 for value in results.values()) and native_ok else 2


if __name__=='__main__':
    try:sys.exit(main())
    except (ValueError,OSError,KeyError,TypeError):
        print('SDK_REGISTERED_GUEST_RECIPE_FAILED',file=sys.stderr);sys.exit(64)
