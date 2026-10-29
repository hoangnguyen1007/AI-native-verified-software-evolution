#!/usr/bin/env python3
"""Fresh owned VM for fixed authored TS/JS controls. Never a general executor certificate.

This scoped recipe reuses pinned Hyper-V containment and opaque transport, without
changing Core MVP bytes, registration, allowlist, acceptance or publication semantics.
"""
import argparse
from contextlib import contextmanager
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tarfile
import tempfile
import time
import uuid

RESOURCES=Path(__file__).resolve().parents[1]
TEMPLATE_FILES=('setup.json','provision-request.json','profile.json','controller-config.json','known_hosts')
CONTROLS=('hyperv/provision.ps1','hyperv/worker.ps1','hyperv/supervisor.py','hyperv/host_storage.py',
          'guest/make_worker_seed.py','guest/bootstrap.py','guest/runner.py','guest/export.py',
          'mvp/capture.py','mvp/launch.py','polyglot/authored_worker.py','polyglot/guest_semantic_runner.py',
          'polyglot/typescript-worker.cjs','polyglot/typescript-tool.json','polyglot/node-tool.json')


def unique(pairs):
    value={}
    for key,item in pairs:
        if key in value:raise ValueError('DUPLICATE_FIXTURE_FIELD')
        value[key]=item
    return value


def read(path, maximum=1048576):
    with Path(path).open('rb') as source:raw=source.read(maximum+1)
    if len(raw)>maximum:raise ValueError('FIXTURE_METADATA_WORKER_REQUIRED')
    return json.loads(raw,object_pairs_hook=unique)


def digest(path):
    with Path(path).open('rb') as source:return 'sha256:'+hashlib.file_digest(source,'sha256').hexdigest()


def handle(path):return dict(size=Path(path).stat().st_size,digest=digest(path))


def write(path,value):
    with Path(path).open('x',encoding='utf-8',newline='\n') as target:
        json.dump(value,target,sort_keys=True,separators=(',',':'),ensure_ascii=False);target.flush();os.fsync(target.fileno())


def unaliased(path):
    path=Path(path).absolute()
    if '..' in path.parts or any(p.is_symlink() or (hasattr(p,'is_junction') and p.is_junction()) for p in (path,*path.parents)):
        raise ValueError('FIXTURE_PATH_ALIAS')
    return path


@contextmanager
def frozen(path,pin):
    """Validate a private byte snapshot before sending any bytes over management SSH."""
    path=unaliased(path)
    if type(pin['size']) is not int or not 0<=pin['size']<=268435456:raise ValueError('FIXTURE_INPUT_WORKER_REQUIRED')
    with tempfile.TemporaryFile('w+b') as target:
        total=0;hashed=hashlib.sha256()
        with path.open('rb') as source:
            while block:=source.read(65536):
                total+=len(block)
                if total>pin['size']:raise ValueError('FIXTURE_INPUT_CHANGED')
                hashed.update(block);target.write(block)
        if total!=pin['size'] or 'sha256:'+hashed.hexdigest()!=pin['digest']:raise ValueError('FIXTURE_INPUT_CHANGED')
        target.seek(0);yield target


def load(name,path):
    spec=importlib.util.spec_from_file_location(name,path);module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module);return module


def validate_template(root,catalog=None):
    root=unaliased(root).resolve();setup=read(root/'setup.json');attempt=str(uuid.UUID(setup['attempt']))
    catalog=Path.home()/'.evo/core-mvp/registrations' if catalog is None else Path(catalog)
    record=read(catalog/(attempt+'.json'))
    if (record.get('schema')!='core-mvp-registration:m8.4-v1' or record.get('attempt')!=attempt
            or Path(record['root']).resolve()!=root or set(record['files'])!=set(TEMPLATE_FILES)
            or any(digest(root/name)!=record['files'][name] for name in TEMPLATE_FILES)):
        raise ValueError('PINNED_OPERATOR_TEMPLATE_REQUIRED')
    return setup


def fixture_profile(template):
    profile={k:v for k,v in template.items() if k not in ('approvedBy','pipeline','cacheInputs')}
    profile.update(schema='polyglot-authored-profile:m8.4-v1',acceptanceScope='POLYGLOT_AUTHORED_FIXTURE',
                   authorization='HUMAN_TASK_M845_STEP1',qualified=False)
    return profile


def require_fresh_attempt(root):
    if any((root/name).exists() for name in ('polyglot-launch-intent.json','supervisor-config.json','provision-prepared.json','native-result.json','termination-result.json')) or any(root.glob('owner-*.json')):
        raise ValueError('POLYGLOT_ATTEMPT_RECONCILIATION_REQUIRED')


def snapshot_controls(root):
    pins={}
    for name in CONTROLS:
        raw=unaliased(RESOURCES/name).read_bytes();target=root/'control-sources'/name;target.parent.mkdir(parents=True,exist_ok=True)
        with target.open('xb') as output:output.write(raw)
        pins[name]='sha256:'+hashlib.sha256(raw).hexdigest()
    return pins


def fixture_context(source,language,version):
    def hashed(value):
        return 'sha256:'+hashlib.sha256(json.dumps(value,separators=(',',':'),ensure_ascii=False).encode('utf-8')).hexdigest()
    inventory=hashed(['authored-typescript-fixture',source])
    return dict(inventory=inventory,scope=hashed(['language-context:m8.4-v1',inventory,'root','app','main','baseline',language,version]))


def fixture_inputs(root,compiler,cases=None):
    """Only platform-authored strings plus exact public TypeScript declaration bytes."""
    tool=read(RESOURCES/'polyglot/typescript-tool.json')
    if digest(compiler)!='sha256:'+tool['compilerSha256']:raise ValueError('TYPESCRIPT_PIN_MISMATCH')
    libraries=[]
    for path in sorted(compiler.parent.glob('lib.*.d.ts')):
        raw=unaliased(path).read_bytes()
        libraries.append(dict(path='toolchain/lib/'+path.name,text=raw.decode('utf-8'),sha256='sha256:'+hashlib.sha256(raw).hexdigest()))
    if not libraries:raise ValueError('NATIVE_DECLARATION_LIBRARY_REQUIRED')
    source="export function twice(value:number):number { return value*2; }\nexport const answer=twice(4);\n"
    controls={
        'typescript':('typescript','main.ts',source),
        'typescript-replay':('typescript','main.ts',source),
        'javascript':('javascript','main.js','/** @param {number} value */\nexport function twice(value) { return value*2; }\nexport const answer=twice(4);\n'),
        'missing-import':('typescript','missing.ts',"import {absent} from './absent';\nexport const answer=absent();\n")}
    if cases is not None:
        authored=read(unaliased(cases))
        if set(authored)!={'schema','cases'} or authored['schema']!='authored-typescript-architecture-cases:m8.4-v1' or not authored['cases']:raise ValueError('AUTHORED_TYPESCRIPT_CASE_SCHEMA')
        for name,case in authored['cases'].items():
            if (not re.fullmatch('[a-z][a-z0-9-]{0,63}',name) or name in controls or set(case)!={'language','path','text','expected'}
                    or case['language'] not in ('typescript','javascript') or case['path']!=('main.ts' if case['language']=='typescript' else 'main.js') or not isinstance(case['text'],str)):raise ValueError('AUTHORED_TYPESCRIPT_CASE_FIELDS')
            controls[name]=(case['language'],case['path'],case['text'])
        write(root/'qualification-cases.json',authored)
    result={}
    for name,(language,file,text) in controls.items():
        source_hash='sha256:'+hashlib.sha256(text.encode()).hexdigest()
        context=fixture_context(text,language,tool['version'])['scope']
        value=dict(schema='typescript-capture-request:m8.4-v1',scope=context,repositoryScope='urn:test:typescript-native',
                   packageScope='app',language=language,projectFiles=[file],
                   files=[dict(path=file,text=text,sha256=source_hash),*libraries],
                   options=dict(target='ES2022',module='CommonJS',moduleResolution='Node10',strict=True))
        if language=='javascript':value['options'].update(allowJs=True,checkJs=True)
        path=root/(name+'.input.json');write(path,value);result[name]=path
    return result


def register(args):
    setup=validate_template(args.template);profile=fixture_profile(read(args.template/'profile.json'))
    receipt_path=Path(setup['hostStorage']['receipt']);receipt=read(receipt_path)
    if digest(receipt_path)!=setup['hostStorage']['receiptDigest']:raise ValueError('HOST_VOLUME_RECEIPT_CHANGED')
    storage=load('polyglot_registration_storage',RESOURCES/'hyperv/host_storage.py')
    volume=storage.PinnedVolume(receipt,str(args.workspace.resolve()),receipt['owner']);volume.check()
    attempt=uuid.uuid4();root=Path(receipt['driveLetter']+':/polyglot-'+attempt.hex);root.mkdir();volume.contained(root)
    subprocess.run(['icacls',str(root),'/inheritance:r','/grant:r','*'+receipt['owner']+':(OI)(CI)F','*S-1-5-18:(OI)(CI)F'],check=True,stdout=subprocess.DEVNULL)
    payload=root/'payload';payload.mkdir()
    from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey
    from cryptography.hazmat.primitives import serialization
    key=Ed25519PrivateKey.generate();private=root/'guest-host-ed25519';public=root/'guest-host-ed25519.pub'
    private.write_bytes(key.private_bytes(serialization.Encoding.PEM,serialization.PrivateFormat.OpenSSH,serialization.NoEncryption()))
    public.write_bytes(key.public_key().public_bytes(serialization.Encoding.OpenSSH,serialization.PublicFormat.OpenSSH)+b'\n')
    (root/'known_hosts').write_bytes(setup['guestAddress'].encode()+b' '+public.read_bytes())
    mac=profile['network']['mac'].lower();mac=':'.join(mac[i:i+2] for i in range(0,12,2));seed=root/'worker-seed.iso'
    subprocess.run([sys.executable,str(RESOURCES/'guest/make_worker_seed.py'),'--attempt',attempt.hex,'--mac',mac,
                    '--address',setup['guestAddress']+'/24','--public-key',setup['identityFile']+'.pub','--host-private-key',str(private),
                    '--host-public-key',str(public),'--output',str(seed),'--work-bytes','1073741824'],check=True,stdout=subprocess.DEVNULL)
    node=read(RESOURCES/'polyglot/node-tool.json')
    # Extract a single regular public runtime byte stream; never unpack archive paths, links or scripts.
    member='node-'+node['version']+'-linux-x64/bin/node'
    with frozen(args.node_archive,dict(size=args.node_archive.stat().st_size,digest='sha256:'+node['sha256'])) as captured:
        with tarfile.open(fileobj=captured,mode='r:xz') as archive:
            item=archive.getmember(member)
            if not item.isfile() or not 1<=item.size<=268435456:raise ValueError('NODE_RUNTIME_ARCHIVE_MEMBER')
            with archive.extractfile(item) as source,(payload/'node').open('xb') as target:shutil.copyfileobj(source,target)
    for source,name in ((args.compiler,'typescript.js'),(RESOURCES/'polyglot/typescript-worker.cjs','typescript-worker.cjs'),
                        (RESOURCES/'polyglot/typescript-tool.json','typescript-tool.json'),(RESOURCES/'guest/runner.py','base-runner.py'),
                        (RESOURCES/'polyglot/guest_semantic_runner.py','guest_semantic_runner.py')):
        with unaliased(source).open('rb') as incoming,(payload/name).open('xb') as outgoing:shutil.copyfileobj(incoming,outgoing)
    contexts=fixture_inputs(payload,args.compiler.resolve(),getattr(args,'cases',None))
    profile.update(seed=digest(seed),controls=snapshot_controls(root),runtime=node,
                   payload={path.name:handle(path) for path in sorted(payload.iterdir())},contexts=list(contexts))
    write(root/'profile.json',profile)
    request=read(args.template/'provision-request.json')
    request.update(attempt=str(attempt),workspace=str(root),launch=digest(root/'profile.json'),driverPin=digest(RESOURCES/'hyperv/worker.ps1'),seed=dict(path=str(seed),**handle(seed)))
    write(root/'provision-request.json',request)
    setup.update(attempt=str(attempt),root=str(root),vmName='evo-worker-'+attempt.hex,acceptanceScope='POLYGLOT_AUTHORED_FIXTURE',
                 authorization='HUMAN_TASK_M845_STEP1',workspace=str(args.workspace.resolve()))
    setup.pop('approvedBy',None);write(root/'setup.json',setup)
    write(root/'controller-config.json',dict(workerHost=setup['guestAddress'],hostKeyAlias=setup['guestAddress'],identityFile=setup['identityFile'],knownHostsFile=str(root/'known_hosts')))
    sys.path.insert(0,str(RESOURCES/'mvp'));from capture import export_request
    write(root/'export-request.json',dict(request=export_request(attempt.hex),collectorPin=digest(RESOURCES/'guest/export.py')))
    files={name:digest(root/name) for name in (*TEMPLATE_FILES,'export-request.json')}
    catalog=Path.home()/'.evo/polyglot-fixtures/registrations';catalog.mkdir(parents=True,exist_ok=True)
    write(catalog/(str(attempt)+'.json'),dict(schema='polyglot-authored-registration:m8.4-v1',attempt=str(attempt),root=str(root),files=files))
    return dict(root=str(root),attempt=str(attempt),scope='POLYGLOT_AUTHORED_FIXTURE',qualified=False)


def validate_registration(root):
    root=unaliased(root).resolve();setup=read(root/'setup.json');attempt=str(uuid.UUID(setup['attempt']))
    record=read(Path.home()/'.evo/polyglot-fixtures/registrations'/(attempt+'.json'));profile=read(root/'profile.json')
    if (record['schema']!='polyglot-authored-registration:m8.4-v1' or Path(record['root']).resolve()!=root
            or record['attempt']!=attempt or set(record['files'])!=set((*TEMPLATE_FILES,'export-request.json'))
            or any(digest(root/name)!=pin for name,pin in record['files'].items())
            or setup['acceptanceScope']!='POLYGLOT_AUTHORED_FIXTURE' or profile['qualified'] is not False
            or set(profile['controls'])!=set(CONTROLS)
            or any(digest(RESOURCES/name)!=pin for name,pin in profile['controls'].items())
            or any(digest(root/'control-sources'/name)!=pin for name,pin in profile['controls'].items())
            or digest(sys.executable)!=setup['pythonPin'] or digest(setup['powershell'])!=setup['powershellPin']):
        raise ValueError('POLYGLOT_REGISTRATION_CHANGED')
    actual={path.name:handle(unaliased(path)) for path in (root/'payload').iterdir()}
    if actual!=profile['payload']:raise ValueError('SEALED_POLYGLOT_PAYLOAD_CHANGED')
    for name,version in profile['runtimeLibraries'].items():
        if __import__('importlib.metadata').metadata.version(name)!=version:raise ValueError('CONTROL_LIBRARY_VERSION_CHANGED')
    return setup,profile


def run_native(root,setup,profile):
    import paramiko
    attempt=uuid.UUID(setup['attempt']).hex;work='/work/'+attempt;base='/opt/evo-polyglot/'+attempt
    client=paramiko.SSHClient();client.load_host_keys(str(root/'known_hosts'))
    def remote(command,data=None,deadline=90):
        stdin,out,err=client.exec_command(command,timeout=deadline)
        if data is not None:stdin.write(data)
        stdin.channel.shutdown_write();channel=out.channel;output=bytearray();errors=bytearray();end=time.monotonic()+deadline
        while not channel.exit_status_ready() or channel.recv_ready() or channel.recv_stderr_ready():
            if time.monotonic()>end:channel.close();raise RuntimeError('SEMANTIC_GUEST_CONTROL_TIMEOUT')
            if channel.recv_ready():output.extend(channel.recv(65536))
            if channel.recv_stderr_ready():errors.extend(channel.recv_stderr(65536))
            if len(output)+len(errors)>1048576:channel.close();raise RuntimeError('SEMANTIC_CONTROL_RESPONSE_WORKER_REQUIRED')
            time.sleep(.01)
        if channel.recv_exit_status():
            with (root/('guest-control-failure-'+uuid.uuid4().hex+'.private.log')).open('xb') as target:target.write(output+errors)
            raise RuntimeError('SEMANTIC_GUEST_CONTROL_FAILED')
        return bytes(output)
    try:
        client.connect(setup['guestAddress'],username='evoctl',key_filename=setup['identityFile'],allow_agent=False,look_for_keys=False,timeout=5,banner_timeout=5,auth_timeout=5)
        bootstrap=json.loads(remote('sudo -n cat /var/lib/evo/bootstrap-complete.json'),object_pairs_hook=unique)
        if (bootstrap['attempt'],bootstrap['work'],bootstrap['workBytes'])!=(attempt,work,1073741824):raise ValueError('SEMANTIC_BOOTSTRAP_SCOPE_MISMATCH')
        remote('sudo -n mountpoint -q '+work)
        remote('sudo -n chown evoctl:evoctl '+work)
        remote('sudo -n chmod 0750 '+work)
        staging='/home/evoctl/evo-upload-'+attempt;remote('install -d -m 0700 '+staging)
        with client.open_sftp() as sftp:
            for name,pin in sorted(profile['payload'].items()):
                if not re.fullmatch('[a-zA-Z0-9_.-]+',name):raise ValueError('SEALED_PAYLOAD_NAME')
                with frozen(root/'payload'/name,pin) as source,sftp.open(staging+'/'+name,'wx') as target:
                    shutil.copyfileobj(source,target,65536)
        # No guest interpretation of payload bytes before exact input hashes are confirmed.
        for name,pin in profile['payload'].items():
            actual=remote('sudo -n sha256sum -- '+staging+'/'+name).decode('ascii').split()[0]
            if 'sha256:'+actual!=pin['digest']:raise ValueError('UPLOADED_SEMANTIC_BYTES_CHANGED')
        remote('sudo -n install -d -m 0755 '+base+' '+work+'/results')
        tools={}
        for name,key in (('node','node'),('typescript-worker.cjs','worker'),('typescript.js','compiler'),('base-runner.py','baseRunner'),('typescript-tool.json','compilerPin'),('guest_semantic_runner.py','recipe')):
            remote('sudo -n install -m '+('0555' if name=='node' else '0444')+' '+staging+'/'+name+' '+base+'/'+name)
            tools[key]=dict(path=base+'/'+name,**profile['payload'][name])
        contexts={}
        for name in profile['contexts']:
            if not re.fullmatch('[a-z][a-z0-9-]{0,63}',name):raise ValueError('SEALED_CONTEXT_NAME')
            file=name+'.input.json';remote('sudo -n install -m 0444 '+staging+'/'+file+' '+base+'/'+file)
            contexts[name]=dict(input=dict(path=base+'/'+file,**profile['payload'][file]))
        registry=dict(schema='polyglot-guest-registration:m8.4-v1',attempt=attempt,work=work,tools=tools,contexts=contexts)
        remote('sudo -n tee '+base+'/registration.json',json.dumps(registry))
        remote('sudo -n chmod 0444 '+base+'/registration.json')
        remote('sudo -n chown root:root '+work)
        remote('sudo -n chmod 0755 '+work)
        results={}
        for name in contexts:
            output=remote('sudo -n /usr/bin/python3 '+base+'/guest_semantic_runner.py',json.dumps(dict(schema='polyglot-guest-request:m8.4-v1',attempt=attempt,context=name)),180)
            result=json.loads(output,object_pairs_hook=unique)
            if (result['schema'],result['context'],result['exit'],result['qualified'],result['trust'])!=('polyglot-guest-result:m8.4-v1',name,0,False,'PRODUCER_ASSERTION'):
                raise ValueError('SEMANTIC_NATIVE_RESULT_MISMATCH')
            results[name]=result
        if results['typescript']['output']['digest']!=results['typescript-replay']['output']['digest']:raise ValueError('SEMANTIC_NATIVE_REPLAY_CHANGED')
        write(root/'native-result.json',dict(schema='polyglot-authored-native-result:m8.4-v1',attempt=attempt,contexts=results,qualified=False,trust='PRODUCER_ASSERTION'))
    finally:client.close()


def run(root):
    require_fresh_attempt(root)
    setup,profile=validate_registration(root);attempt=setup['attempt'];owner_path=root/('owner-'+uuid.UUID(attempt).hex+'.json');watcher=None
    storage=load('polyglot_run_storage',RESOURCES/'hyperv/host_storage.py');receipt=read(setup['hostStorage']['receipt'])
    if digest(setup['hostStorage']['receipt'])!=setup['hostStorage']['receiptDigest']:raise ValueError('HOST_VOLUME_RECEIPT_CHANGED')
    storage.PinnedVolume(receipt,setup['workspace'],setup['hostStorage']['owner']).contained(root)
    supervisor=load('polyglot_supervisor',RESOURCES/'hyperv/supervisor.py')
    sys.path.insert(0,str(RESOURCES/'mvp'));from launch import sequence,wait_bootstrap
    def checked(argv,timeout=360):
        result=subprocess.run(argv,stdin=subprocess.DEVNULL,stdout=subprocess.PIPE,stderr=subprocess.PIPE,timeout=timeout)
        if len(result.stdout)+len(result.stderr)>1048576:raise RuntimeError('POLYGLOT_CONTROL_RESPONSE_WORKER_REQUIRED')
        if result.returncode:
            with (root/('control-failure-'+uuid.uuid4().hex+'.private.log')).open('xb') as target:target.write(result.stdout+result.stderr)
            raise RuntimeError('POLYGLOT_CONTROL_FAILED')
        return result.stdout
    def provision(action):
        request=root/'provision-request.json'
        checked([setup['powershell'],'-NoProfile','-NonInteractive','-File',str(RESOURCES/'hyperv/provision.ps1'),'-Action',action,'-Request',str(request),'-RequestPin',digest(request)])
    def supervise():
        nonlocal watcher
        config=dict(schema='hyperv-supervisor-config:m8.4-v1',root=str(root),owner=str(owner_path),powershell=setup['powershell'],powershellPin=setup['powershellPin'],
                    driver=str(RESOURCES/'hyperv/worker.ps1'),driverPin=digest(RESOURCES/'hyperv/worker.ps1'),controlPid=os.getpid(),runtimeMillis=900000,
                    overlayBytes=2147483648,logBytes=16777216,logs=[str(root/'supervisor-private.log')],pollMillis=100)
        write(root/'supervisor-config.json',config)
        with (root/'supervisor-private.log').open('xb') as errors:
            watcher=subprocess.Popen([sys.executable,'-I','-c',(RESOURCES/'hyperv/supervisor.py').read_text(),
                                      '--config',str(root/'supervisor-config.json')],stdin=subprocess.DEVNULL,stdout=subprocess.DEVNULL,stderr=errors,
                                      close_fds=True,creationflags=subprocess.CREATE_NO_WINDOW|subprocess.CREATE_NEW_PROCESS_GROUP)
        deadline=time.monotonic()+45
        while not (root/'supervisor-ready.json').exists():
            if watcher.poll() is not None or time.monotonic()>deadline:raise RuntimeError('POLYGLOT_SUPERVISOR_UNAVAILABLE')
            time.sleep(.1)
    def stop():
        if owner_path.exists():
            owner=read(owner_path)
            config=read(root/'supervisor-config.json') if (root/'supervisor-config.json').exists() else dict(powershell=setup['powershell'],powershellPin=setup['powershellPin'],driver=str(RESOURCES/'hyperv/worker.ps1'),driverPin=digest(RESOURCES/'hyperv/worker.ps1'),root=str(root))
            observed=supervisor.control(config,owner,'Stop')
            if observed['state']!='Off':raise RuntimeError('POLYGLOT_TERMINATION_UNCONFIRMED')
            write(root/'termination-result.json',observed)
            if watcher is not None:
                write(root/'controller-protocol-complete.json',dict(attempt=attempt,fence=owner['fence'],launch=owner['launch']))
        if watcher is not None and watcher.wait(timeout=45)!=0:raise RuntimeError('POLYGLOT_SUPERVISOR_FAILED')
    def start():provision('Start');wait_bootstrap(root,setup,uuid.UUID(attempt).hex)
    def capture():checked([sys.executable,str(RESOURCES/'mvp/capture.py'),'--root',str(root/'capture'),'--config',str(root/'controller-config.json'),'--owner',str(owner_path),
                           '--work-attempt',uuid.UUID(attempt).hex,'--request',str(root/'export-request.json')])
    write(root/'polyglot-launch-intent.json',dict(schema='polyglot-launch-intent:m8.4-v1',attempt=attempt,profile=digest(root/'profile.json')))
    sequence(lambda:provision('Prepare'),supervise,start,lambda:run_native(root,setup,profile),capture,stop)
    return dict(root=str(root),scope='POLYGLOT_AUTHORED_FIXTURE',status='EXPORTED_AND_OFF',contexts=len(profile['contexts']),qualified=False)


def main():
    parser=argparse.ArgumentParser();commands=parser.add_subparsers(dest='command',required=True)
    registration=commands.add_parser('register')
    for name in ('template','workspace','compiler','node-archive'):registration.add_argument('--'+name,type=Path,required=True)
    registration.add_argument('--cases',type=Path)
    launch=commands.add_parser('run');launch.add_argument('--root',type=Path,required=True)
    args=parser.parse_args();print(json.dumps(register(args) if args.command=='register' else run(args.root.resolve()),sort_keys=True))


if __name__=='__main__':main()
