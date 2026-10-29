#!/usr/bin/env python3
"""Fresh registered SDK worker with a separate read-only tool-only disc.
Fixed authored SDK probes only; no generic executor or semantic certificate.
"""
import argparse
import importlib.util
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import time
import uuid

import authored_worker as base

RESOURCES=Path(__file__).resolve().parents[1]
CONTROLS=tuple(dict.fromkeys((*base.CONTROLS,'polyglot/sdk_worker.py','polyglot/sdk_guest.py','polyglot/sdk_media.py','polyglot/provision-sdk.ps1','polyglot/jvm_guest.py',
                            'polyglot/jvm/NeutralJvm.java','polyglot/jvm/KotlinEngine.java','polyglot/jvm/ScalaEngine.scala','polyglot/jvm/GroovyEngine.java',
                            'polyglot/native_guest.py','polyglot/native_protocol.py','polyglot/lsp_client.py','polyglot/python_engine.py','polyglot/go_engine.py','polyglot/typescript_engine.py',
                            'polyglot/go/go_engine.go','polyglot/clang_engine.py','polyglot/dotnet/RoslynEngine.cs',
                            'polyglot/dart_engine.py','polyglot/dart/dart_engine.dart','polyglot/php_engine.py','polyglot/php/php_engine.php',
                            'polyglot/rust_engine.py','polyglot/native_lsp_projection.py','polyglot/ruby_engine.py','polyglot/swift_engine.py','polyglot/swift_indexstore.py','polyglot/swift/SwiftCensus.swift','polyglot/worker-sdk.ps1')))
SCOPE='AUTHORED_NATIVE_SDK_AND_FRONTEND'


def private_controller_identity(root):
    """A fresh transport key in the worker's already private state directory."""
    from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey
    from cryptography.hazmat.primitives import serialization
    root=base.unaliased(root);private=root/'controller-ed25519';public=root/'controller-ed25519.pub';key=Ed25519PrivateKey.generate()
    with private.open('xb') as target:target.write(key.private_bytes(serialization.Encoding.PEM,serialization.PrivateFormat.OpenSSH,serialization.NoEncryption()))
    with public.open('xb') as target:target.write(key.public_key().public_bytes(serialization.Encoding.OpenSSH,serialization.PublicFormat.OpenSSH)+b'\n')
    return private,dict(private=base.handle(private),public=base.handle(public))


def work_bytes(value):
    if type(value) is not int or value not in (1073741824,2147483648):raise ValueError('SDK_REGISTERED_WORK_VOLUME_PROFILE')
    return value


def image_runtime_names(sdk):
    if sdk.get('probeScope')=='EXACT_NATIVE_CONTEXTS' and not set(sdk.get('probeNames',[]))&{'kotlin','scala','groovy'}:return ('python3',)
    return ('java','python3')


def register(args):
    capacity=work_bytes(getattr(args,'work_bytes',2147483648))
    selected=base.read(args.template/'setup.json')
    if selected.get('acceptanceScope')=='AUTHORED_NATIVE_SDK_TEMPLATE':
        from sdk_template import validate as validate_native_template
        template=validate_native_template(args.template)
    else:template=base.validate_template(args.template)
    profile=base.fixture_profile(base.read(args.template/'profile.json'))
    media=base.read(args.media)
    if media['schema']!='polyglot-sdk-media-registration:m8.4-v1' or media['qualified'] is not False:raise ValueError('SDK_MEDIA_REGISTRATION_REQUIRED')
    sdk=base.unaliased(media['path'])
    if base.handle(sdk)!=media['media']:raise ValueError('SDK_MEDIA_BYTES_CHANGED')
    sdk.chmod(0o444)
    receipt=base.read(template['hostStorage']['receipt'])
    if base.digest(template['hostStorage']['receipt'])!=template['hostStorage']['receiptDigest']:raise ValueError('HOST_STORAGE_RECEIPT_CHANGED')
    storage=base.load('sdk_host_storage',RESOURCES/'hyperv/host_storage.py')
    volume=storage.PinnedVolume(receipt,str(args.workspace.resolve()),receipt['owner']);volume.check()
    attempt=uuid.uuid4();root=Path(receipt['driveLetter']+':/polyglot-sdk-'+attempt.hex);root.mkdir();volume.contained(root)
    subprocess.run(['icacls',str(root),'/inheritance:r','/grant:r','*'+receipt['owner']+':(OI)(CI)F','*S-1-5-18:(OI)(CI)F'],check=True,stdout=subprocess.DEVNULL)
    from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey
    from cryptography.hazmat.primitives import serialization
    key=Ed25519PrivateKey.generate();private=root/'guest-host-ed25519';public=root/'guest-host-ed25519.pub'
    private.write_bytes(key.private_bytes(serialization.Encoding.PEM,serialization.PrivateFormat.OpenSSH,serialization.NoEncryption()))
    public.write_bytes(key.public_key().public_bytes(serialization.Encoding.OpenSSH,serialization.PublicFormat.OpenSSH)+b'\n')
    (root/'known_hosts').write_bytes(template['guestAddress'].encode()+b' '+public.read_bytes())
    controller,transport=private_controller_identity(root);template['identityFile']=str(controller);profile['controllerTransport']=transport
    mac=profile['network']['mac'].lower();mac=':'.join(mac[i:i+2] for i in range(0,12,2));seed=root/'worker-seed.iso'
    subprocess.run([sys.executable,str(RESOURCES/'guest/make_worker_seed.py'),'--attempt',attempt.hex,'--mac',mac,'--address',template['guestAddress']+'/24',
                    '--public-key',template['identityFile']+'.pub','--host-private-key',str(private),'--host-public-key',str(public),'--output',str(seed),'--work-bytes',str(capacity)],check=True,stdout=subprocess.DEVNULL)
    controls={}
    for name in CONTROLS:
        source=base.unaliased(RESOURCES/name);raw=source.read_bytes();out=root/'control-sources'/name;out.parent.mkdir(parents=True,exist_ok=True)
        with out.open('xb') as target:target.write(raw)
        controls[name]=base.digest(out)
    profile.update(schema='polyglot-sdk-worker-profile:m8.4-v1',acceptanceScope=SCOPE,controls=controls,seed=base.digest(seed),sdk=media,
                   workPolicy=dict(workBytes=capacity,memoryBytes=2147483648,tasksMax=256,cpuPercent=100,overlayBytes=1073741824,runtimeMillis=900000))
    if getattr(args,'campaign',None):
        campaign=base.read(args.campaign)
        validate_campaign(campaign)
        sealed={}
        assets=campaign_assets(campaign)
        for name,item in assets.items():
            path=base.unaliased(item['path']);target=root/'campaign'/name
            target.parent.mkdir(exist_ok=True)
            with base.frozen(path,{k:item[k] for k in ('size','digest')}) as source,target.open('xb') as out:__import__('shutil').copyfileobj(source,out,65536)
            sealed[name]=dict(path=str(target),**base.handle(target))
        # Captured source text is data. No context can choose an executable or compiler entry point.
        sealed=dict(schema=campaign['schema'],contexts=campaign['contexts'],assets=sealed) if 'assets' in campaign else dict(schema=campaign['schema'],contexts=campaign['contexts'],**sealed)
        base.write(root/'campaign.json',sealed);profile['campaign']=base.handle(root/'campaign.json')
    base.write(root/'profile.json',profile)
    request=base.read(args.template/'provision-request.json')
    request.update(schema='hyperv-polyglot-provision-request:m8.4-v1',attempt=str(attempt),workspace=str(root),launch=base.digest(root/'profile.json'),driverPin=base.digest(RESOURCES/'polyglot/worker-sdk.ps1'),seed=dict(path=str(seed),**base.handle(seed)),
                   tools=dict(root=media['root'],path=str(sdk),**media['media']),resources=dict(processors=2,memoryBytes=3221225472))
    base.write(root/'provision-request.json',request)
    template.update(attempt=str(attempt),root=str(root),vmName='evo-worker-'+attempt.hex,acceptanceScope=SCOPE,authorization='HUMAN_TASK_M845_STEP1',workspace=str(args.workspace.resolve()),python=sys.executable,pythonPin=base.digest(sys.executable))
    template.pop('approvedBy',None);base.write(root/'setup.json',template)
    base.write(root/'controller-config.json',dict(workerHost=template['guestAddress'],hostKeyAlias=template['guestAddress'],identityFile=template['identityFile'],knownHostsFile=str(root/'known_hosts')))
    sys.path.insert(0,str(RESOURCES/'mvp'));from capture import export_request
    base.write(root/'export-request.json',dict(request=export_request(attempt.hex),collectorPin=base.digest(RESOURCES/'guest/export.py')))
    catalog=Path.home()/'.evo/polyglot-sdk-workers/registrations';catalog.mkdir(parents=True,exist_ok=True)
    base.write(catalog/(str(attempt)+'.json'),dict(schema='polyglot-sdk-worker-registration:m8.4-v1',attempt=str(attempt),root=str(root),files={name:base.digest(root/name) for name in (*base.TEMPLATE_FILES,'export-request.json')}))
    return dict(root=str(root),attempt=str(attempt),scope=SCOPE,qualified=False)


def validate_campaign(value):
    if value.get('schema')=='authored-native-campaign:m8.4-v1':
        from native_guest import validate
        validate(value);return
    if (set(value)!={'schema','platform','adapters','contexts'} or value['schema']!='authored-jvm-campaign:m8.4-v1'
            or not isinstance(value['contexts'],dict) or not value['contexts']):raise ValueError('SDK_JVM_CAMPAIGN_REQUIRED')
    for name,item in value['contexts'].items():
        if (not re.fullmatch('[a-z][a-z0-9-]{0,63}',name) or set(item)!={'language','request'} or item['language'] not in ('kotlin','scala','groovy')
                or item['request']['language']!=item['language'] or item['request']['schema']!='native-jvm-request:m8.4-v1'):raise ValueError('SDK_JVM_CONTEXT_REQUIRED')
        for source in item['request']['files']:
            path=source['path']
            if (not isinstance(path,str) or not path or path.startswith('/') or '\\' in path or ':' in path or '\x00' in path
                    or any(part in ('','.','..') for part in path.split('/'))):raise ValueError('SDK_JVM_SOURCE_PATH')
            raw=source['text'].encode('utf8',errors='strict')
            if 'sha256:'+__import__('hashlib').sha256(raw).hexdigest()!=source['sha256']:raise ValueError('SDK_JVM_SOURCE_HASH')
        if len({source['path'] for source in item['request']['files']})!=len(item['request']['files']) or not item['request']['files']:raise ValueError('SDK_JVM_SOURCE_MEMBERSHIP')


def campaign_assets(value):return value['assets'] if 'assets' in value else {name:value[name] for name in ('platform','adapters')}


def validate_registration(root):
    setup=base.read(root/'setup.json');profile=base.read(root/'profile.json');attempt=str(uuid.UUID(setup['attempt']))
    registered=base.read(Path.home()/'.evo/polyglot-sdk-workers/registrations'/(attempt+'.json'))
    if (registered['schema']!='polyglot-sdk-worker-registration:m8.4-v1' or Path(registered['root']).resolve()!=root.resolve() or registered['attempt']!=attempt
            or set(registered['files'])!=set((*base.TEMPLATE_FILES,'export-request.json')) or any(base.digest(root/name)!=pin for name,pin in registered['files'].items())
            or setup['acceptanceScope']!=SCOPE or profile['acceptanceScope']!=SCOPE or profile['qualified'] is not False
            or set(profile['controls'])!=set(CONTROLS) or any(base.digest(RESOURCES/name)!=pin or base.digest(root/'control-sources'/name)!=pin for name,pin in profile['controls'].items())
            or base.handle(base.unaliased(profile['sdk']['path']))!=profile['sdk']['media']
            or base.digest(sys.executable)!=setup['pythonPin'] or base.digest(setup['powershell'])!=setup['powershellPin']):raise ValueError('SDK_WORKER_REGISTRATION_CHANGED')
    if base.digest(setup['hostStorage']['receipt'])!=setup['hostStorage']['receiptDigest']:raise ValueError('SDK_HOST_VOLUME_CHANGED')
    if 'controllerTransport' in profile:
        identity=base.unaliased(setup['identityFile']);identity.relative_to(root.resolve());transport=profile['controllerTransport']
        if set(transport)!={'private','public'} or base.handle(identity)!=transport['private'] or base.handle(str(identity)+'.pub')!=transport['public']:raise ValueError('SDK_CONTROLLER_TRANSPORT_CHANGED')
    if 'campaign' in profile:
        if base.handle(root/'campaign.json')!=profile['campaign']:raise ValueError('SDK_JVM_CAMPAIGN_CHANGED')
        campaign=base.read(root/'campaign.json');validate_campaign(campaign)
        for item in campaign_assets(campaign).values():
            if base.handle(base.unaliased(item['path']))!={key:item[key] for key in ('size','digest')}:raise ValueError('SDK_NATIVE_TOOL_CHANGED')
    for name,version in profile['runtimeLibraries'].items():
        if __import__('importlib.metadata').metadata.version(name)!=version:raise ValueError('SDK_CONTROL_LIBRARY_CHANGED')
    return setup,profile


def probe(root,setup,profile):
    import paramiko
    attempt=uuid.UUID(setup['attempt']).hex;work='/work/'+attempt;control='/opt/evo-sdk-control/'+attempt
    client=paramiko.SSHClient();client.load_host_keys(str(root/'known_hosts'))
    def remote(command,data=None,timeout=60,allowed=(0,)):
        stdin,out,err=client.exec_command(command,timeout=timeout)
        if data is not None:stdin.write(data)
        stdin.channel.shutdown_write();channel=out.channel;end=time.monotonic()+timeout;stdout=bytearray();stderr=bytearray()
        while not channel.exit_status_ready() or channel.recv_ready() or channel.recv_stderr_ready():
            if time.monotonic()>end:channel.close();raise RuntimeError('SDK_MANAGEMENT_WATCHDOG')
            if channel.recv_ready():stdout.extend(channel.recv(65536))
            if channel.recv_stderr_ready():stderr.extend(channel.recv_stderr(65536))
            if len(stdout)+len(stderr)>1048576:channel.close();raise RuntimeError('SDK_CONTROL_RESPONSE_WORKER_REQUIRED')
            time.sleep(.01)
        if channel.recv_exit_status() not in allowed:
            with (root/('guest-failure-'+uuid.uuid4().hex+'.private.log')).open('xb') as target:target.write(stdout+stderr)
            raise RuntimeError('SDK_GUEST_OPERATION_FAILED')
        return bytes(stdout)
    try:
        client.connect(setup['guestAddress'],username='evoctl',key_filename=setup['identityFile'],allow_agent=False,look_for_keys=False,timeout=5,banner_timeout=5,auth_timeout=5)
        observed=json.loads(remote('sudo -n cat /var/lib/evo/bootstrap-complete.json'),object_pairs_hook=base.unique)
        if (observed['attempt'],observed['work'],observed['workBytes'])!=(attempt,work,work_bytes(profile['workPolicy']['workBytes'])):raise ValueError('SDK_BOOTSTRAP_SCOPE_MISMATCH')
        remote('sudo -n install -d -m 0755 /opt/evo-toolset '+control)
        remote('sudo -n mount -o ro,nodev,nosuid /dev/disk/by-label/EVO_TOOLSET /opt/evo-toolset')
        actual=remote('sudo -n sha256sum /opt/evo-toolset/TOOLSET.JSON').decode('ascii').split()[0]
        if 'sha256:'+actual!=profile['sdk']['manifest']['digest']:raise ValueError('SDK_MOUNTED_MEDIA_MANIFEST_CHANGED')
        remote('sudo -n chown evoctl:evoctl '+work);remote('sudo -n chmod 0750 '+work)
        staging=work+'/controls';remote('install -d -m 0700 '+staging)
        transfers=[('polyglot/sdk_guest.py','sdk_guest.py'),('polyglot/guest_semantic_runner.py','guest_semantic_runner.py'),('guest/runner.py','base-runner.py'),('polyglot/jvm_guest.py','jvm_guest.py')]
        transfers.extend(('polyglot/'+name,name) for name in ('native_guest.py','native_protocol.py','lsp_client.py','typescript_engine.py','typescript-worker.cjs','typescript-tool.json','python_engine.py','go_engine.py','clang_engine.py','dart_engine.py','php_engine.py','rust_engine.py','native_lsp_projection.py','ruby_engine.py','swift_engine.py','swift_indexstore.py'))
        transfers.extend([('polyglot/dart/dart_engine.dart','dart_engine.dart'),('polyglot/php/php_engine.php','php_engine.php'),('polyglot/swift/SwiftCensus.swift','SwiftCensus.swift')])
        with client.open_sftp() as sftp:
            for source,name in transfers:
                path=root/'control-sources'/source
                with base.frozen(path,base.handle(path)) as captured,sftp.open(staging+'/'+name,'wx') as target:
                    __import__('shutil').copyfileobj(captured,target,65536)
                remote('sudo -n install -m 0444 '+staging+'/'+name+' '+control+'/'+name)
                if remote('sudo -n sha256sum '+control+'/'+name).decode('ascii').split()[0]!=profile['controls'][source][7:]:raise ValueError('SDK_GUEST_CONTROL_CHANGED')
        runtime={}
        for name in image_runtime_names(profile['sdk']):
            path=remote('readlink -f /usr/bin/'+name).decode('ascii').strip()
            if not re.fullmatch('/[A-Za-z0-9_./+-]+',path):raise ValueError('SDK_IMAGE_RUNTIME_PATH_INVALID')
            size=int(remote('stat -c %s '+path).decode('ascii'));sha=remote('sha256sum '+path).decode('ascii').split()[0];runtime['python' if name=='python3' else name]=dict(path=path,size=size,digest='sha256:'+sha)
        registry=dict(schema='sdk-probe-registration:m8.4-v1',attempt=attempt,work=work,sdkRoot='/opt/evo-toolset',manifest=profile['sdk']['manifest'],runtime=runtime,baseRunner=base.handle(root/'control-sources/guest/runner.py'))
        if 'probeNames' in profile['sdk']:registry['probeNames']=profile['sdk']['probeNames']
        if 'probeScope' in profile['sdk']:registry['probeScope']=profile['sdk']['probeScope']
        if 'campaign' in profile:
            prepared=transfer_campaign(root,control,client,remote);registry['nativeCampaign' if prepared['schema']=='authored-native-campaign:m8.4-v1' else 'campaign']=prepared
        base.write(root/'guest-registration.json',registry)
        remote('sudo -n tee '+control+'/registration.json',json.dumps(registry));remote('sudo -n chmod 0444 '+control+'/registration.json');remote('sudo -n chown root:root '+work);remote('sudo -n chmod 0755 '+work)
        raw=remote('sudo -n /usr/bin/python3 '+control+'/sdk_guest.py',json.dumps(dict(schema='sdk-worker-control-request:m8.4-v1',attempt=attempt,recipe='probe-versions')),600,allowed=(0,2))
        result=json.loads(raw,object_pairs_hook=base.unique);base.write(root/'sdk-probe-result.json',result)
        if set(result['results'])!=set(registry.get('probeNames',('node','go','dotnet','dart','ruby','clang','kotlin','scala','groovy'))) or result['qualified'] is not False:raise ValueError('SDK_PROBE_DENOMINATOR_CHANGED')
        if 'campaign' in profile:
            registered=registry.get('nativeCampaign',registry.get('campaign'))
            if set(result['native']['results'])!=set(registered['contexts']) or result['native']['qualified'] is not False:raise ValueError('SDK_JVM_DENOMINATOR_CHANGED')
            base.write(root/'native-campaign-result.json',result['native'])
    finally:client.close()


def transfer_campaign(root,control,client,remote):
    campaign=base.read(root/'campaign.json');validate_campaign(campaign)
    if 'assets' in campaign:return transfer_native_campaign(root,control,campaign,client,remote)
    target=dict(schema='registered-jvm-campaign:m8.4-v1',contexts={})
    with client.open_sftp() as sftp:
        for name in ('platform','adapters'):
            item=campaign[name];path=control+'/'+name+'.jar';stage=remote_staging(base.read(root/'setup.json')['attempt'],name)
            with base.frozen(item['path'],{k:item[k] for k in ('size','digest')}) as source,sftp.open(stage,'wx') as out:__import__('shutil').copyfileobj(source,out,65536)
            remote('sudo -n install -m 0444 '+stage+' '+path)
            if remote('sudo -n sha256sum '+path).decode('ascii').split()[0]!=item['digest'][7:]:raise ValueError('SDK_JVM_TRANSFER_CHANGED')
            target[name]=dict(path=path,size=item['size'],digest=item['digest'])
        for name,item in sorted(campaign['contexts'].items()):
            directory=control+'/inputs/'+name;remote('sudo -n install -d -m 0755 '+directory+'/src')
            request=dict(item['request']);request['sourceRoot']=directory+'/src';artifacts=['/opt/evo-toolset/jvm/'+jar for jar in (('kotlin-stdlib-1.9.25.jar',) if item['language']=='kotlin' else ('scala-library-2.13.16.jar',) if item['language']=='scala' else ('groovy-4.0.27.jar',))]
            request['classpath']=artifacts;request['platformArtifacts']=artifacts
            # SFTP writes temporary root-owned payloads through the management identity only.
            for ordinal,source in enumerate(request['files']):
                stage=remote_staging(base.read(root/'setup.json')['attempt'],'source-'+name+'-'+str(ordinal))
                raw=source['text'].encode('utf8');path=directory+'/src/'+source['path']
                with sftp.open(stage,'wx') as out:out.write(raw)
                # Native source names never enter the shell. Root-owned Python receives JSON on stdin.
                program="import json,pathlib,shutil,sys;x=json.load(sys.stdin);p=pathlib.Path(x['to']);p.parent.mkdir(parents=True,exist_ok=True);shutil.copyfile(x['from'],p);p.chmod(0o444)"
                remote('sudo -n /usr/bin/python3 -c '+__import__('shlex').quote(program),json.dumps({'from':stage,'to':path}))
            raw=json.dumps(request,sort_keys=True,separators=(',',':'),ensure_ascii=False).encode('utf8');path=directory+'/request.json'
            remote('sudo -n tee '+path,raw.decode('utf8'));remote('sudo -n chmod 0444 '+path)
            target['contexts'][name]=dict(language=item['language'],request=dict(path=path,size=len(raw),digest='sha256:'+__import__('hashlib').sha256(raw).hexdigest()))
    return target


def transfer_native_campaign(root,control,campaign,client,remote):
    target=dict(schema=campaign['schema'],contexts=campaign['contexts'],assets={});attempt=base.read(root/'setup.json')['attempt']
    with client.open_sftp() as sftp:
        for name,item in sorted(campaign['assets'].items()):
            stage=remote_staging(attempt,name);path=control+'/asset-'+name
            with base.frozen(item['path'],{k:item[k] for k in ('size','digest')}) as source,sftp.open(stage,'wx') as out:__import__('shutil').copyfileobj(source,out,65536)
            remote('sudo -n install -m '+('0555' if name=='go' else '0444')+' '+stage+' '+path)
            if remote('sudo -n sha256sum '+path).decode('ascii').split()[0]!=item['digest'][7:]:raise ValueError('NATIVE_ASSET_TRANSFER_CHANGED')
            target['assets'][name]=dict(path=path,size=item['size'],digest=item['digest'])
    return target


def remote_staging(attempt,name):
    if not re.fullmatch('[a-z][a-z0-9-]{0,95}',name):raise ValueError('SDK_REMOTE_STAGING_NAME')
    return '/work/'+uuid.UUID(attempt).hex+'/controls/'+name


def run(root):
    base.require_fresh_attempt(root);setup,profile=validate_registration(root);attempt=setup['attempt'];owner_path=root/('owner-'+uuid.UUID(attempt).hex+'.json');watcher=None
    storage=base.load('sdk_storage_run',RESOURCES/'hyperv/host_storage.py');storage.PinnedVolume(base.read(setup['hostStorage']['receipt']),setup['workspace'],setup['hostStorage']['owner']).contained(root)
    supervisor=base.load('sdk_supervisor',RESOURCES/'hyperv/supervisor.py');sys.path.insert(0,str(RESOURCES/'mvp'));from launch import sequence,wait_bootstrap
    def checked(argv,timeout=360):
        result=subprocess.run(argv,stdin=subprocess.DEVNULL,stdout=subprocess.PIPE,stderr=subprocess.PIPE,timeout=timeout)
        if len(result.stdout)+len(result.stderr)>1048576:raise RuntimeError('SDK_CONTROL_RESPONSE_WORKER_REQUIRED')
        if result.returncode:
            with (root/('control-failure-'+uuid.uuid4().hex+'.private.log')).open('xb') as target:target.write(result.stdout+result.stderr)
            raise RuntimeError('SDK_CONTROL_FAILED')
        return result.stdout
    def provision(action):
        request=root/'provision-request.json';checked([setup['powershell'],'-NoProfile','-NonInteractive','-File',str(RESOURCES/'polyglot/provision-sdk.ps1'),'-Action',action,'-Request',str(request),'-RequestPin',base.digest(request)])
    def supervise():
        nonlocal watcher
        policy=profile['workPolicy'];config=dict(schema='hyperv-supervisor-config:m8.4-v1',root=str(root),owner=str(owner_path),powershell=setup['powershell'],powershellPin=setup['powershellPin'],driver=str(RESOURCES/'polyglot/worker-sdk.ps1'),driverPin=base.digest(RESOURCES/'polyglot/worker-sdk.ps1'),controlPid=os.getpid(),runtimeMillis=policy['runtimeMillis'],overlayBytes=policy['overlayBytes'],logBytes=16777216,logs=[str(root/'supervisor-private.log')],pollMillis=100)
        base.write(root/'supervisor-config.json',config)
        with (root/'supervisor-private.log').open('xb') as errors:watcher=subprocess.Popen([sys.executable,'-I','-c',(RESOURCES/'hyperv/supervisor.py').read_text(),'--config',str(root/'supervisor-config.json')],stdin=subprocess.DEVNULL,stdout=subprocess.DEVNULL,stderr=errors,close_fds=True,creationflags=subprocess.CREATE_NO_WINDOW|subprocess.CREATE_NEW_PROCESS_GROUP)
        deadline=time.monotonic()+45
        while not (root/'supervisor-ready.json').exists():
            if watcher.poll() is not None or time.monotonic()>deadline:raise RuntimeError('SDK_SUPERVISOR_UNAVAILABLE')
            time.sleep(.1)
    def stop():
        if owner_path.exists():
            owner=base.read(owner_path);config=base.read(root/'supervisor-config.json') if (root/'supervisor-config.json').exists() else dict(powershell=setup['powershell'],powershellPin=setup['powershellPin'],driver=str(RESOURCES/'polyglot/worker-sdk.ps1'),driverPin=base.digest(RESOURCES/'polyglot/worker-sdk.ps1'))
            state=supervisor.control(config,owner,'Stop')
            if state['state']!='Off':raise RuntimeError('SDK_TERMINATION_UNCONFIRMED')
            base.write(root/'termination-result.json',state)
            if watcher is not None:base.write(root/'controller-protocol-complete.json',dict(attempt=attempt,fence=owner['fence'],launch=owner['launch']))
        if watcher is not None and watcher.wait(timeout=45)!=0:raise RuntimeError('SDK_SUPERVISOR_FAILED')
    def start():provision('Start');wait_bootstrap(root,setup,uuid.UUID(attempt).hex)
    def capture():checked([sys.executable,str(RESOURCES/'mvp/capture.py'),'--root',str(root/'capture'),'--config',str(root/'controller-config.json'),'--owner',str(owner_path),'--work-attempt',uuid.UUID(attempt).hex,'--request',str(root/'export-request.json')])
    base.write(root/'polyglot-launch-intent.json',dict(schema='polyglot-sdk-launch-intent:m8.4-v1',attempt=attempt,profile=base.digest(root/'profile.json')))
    sequence(lambda:provision('Prepare'),supervise,start,lambda:probe(root,setup,profile),capture,stop)
    if base.handle(profile['sdk']['path'])!=profile['sdk']['media']:raise ValueError('SDK_MEDIA_CHANGED_DURING_EXECUTION')
    failures=[name for name,value in base.read(root/'sdk-probe-result.json')['results'].items() if value['exit']!=0]
    if 'campaign' in profile:failures.extend('native:'+name for name,value in base.read(root/'native-campaign-result.json')['results'].items() if value['exit']!=0)
    return dict(root=str(root),scope=SCOPE,status='PROBE_PARTIAL_EXPORTED_AND_OFF' if failures else 'PROBED_EXPORTED_AND_OFF',failed=failures,qualified=False)


def main():
    parser=argparse.ArgumentParser();commands=parser.add_subparsers(dest='command',required=True);create=commands.add_parser('register')
    for name in ('template','workspace','media'):create.add_argument('--'+name,type=Path,required=True)
    create.add_argument('--campaign',type=Path)
    create.add_argument('--work-bytes',type=int,default=2147483648)
    launch=commands.add_parser('run');launch.add_argument('--root',type=Path,required=True);args=parser.parse_args()
    print(json.dumps(register(args) if args.command=='register' else run(args.root.resolve()),sort_keys=True))


if __name__=='__main__':main()
