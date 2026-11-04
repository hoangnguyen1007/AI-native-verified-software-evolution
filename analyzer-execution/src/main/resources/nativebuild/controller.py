#!/usr/bin/env python3
"""Pinned host controller: fresh identity/seed, supervised Hyper-V clone, immutable-byte
upload, offline Maven/Gradle in guest, opaque export, authoritative Off. No host target call."""
import argparse
import base64
import hashlib
import importlib.util
import io
import ipaddress
import json
import os
from pathlib import Path, PurePosixPath
import posixpath
import re
import shlex
import subprocess
import sys
import tempfile
import time
import uuid

RESOURCES = Path(__file__).resolve().parents[1]
ACTIVE_ROOT = None
STAGE = 'PREPARATION'


def unique(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError('NATIVE_DUPLICATE_FIELD')
        result[key] = value
    return result


def read(path, maximum=1048576):
    with safe(path).open('rb') as source:
        raw = source.read(maximum + 1)
    if len(raw) > maximum:
        raise ValueError('NATIVE_METADATA_WORKER_REQUIRED')
    return json.loads(raw, object_pairs_hook=unique)


def safe(path):
    value = Path(path).absolute()
    if '..' in value.parts or str(value.anchor).startswith('\\\\'):
        raise ValueError('NATIVE_PATH_ESCAPE')
    for part in (value, *value.parents):
        if part.is_symlink() or (hasattr(part, 'is_junction') and part.is_junction()):
            raise ValueError('NATIVE_PATH_ALIAS')
    return value


def handle(path):
    path = safe(path)
    with path.open('rb') as source:
        return dict(digest='sha256:' + hashlib.file_digest(source, 'sha256').hexdigest(), size=path.stat().st_size)


def require(pin):
    if set(pin) != {'path', 'content'} or handle(pin['path']) != pin['content']:
        raise ValueError('NATIVE_REGISTERED_PIN_CHANGED')


def pinned_rows(pin, maximum, scratch):
    """Freeze the whole metadata manifest before interpreting paths or opening guest files."""
    expected=pin['content']
    with tempfile.TemporaryFile(mode='w+b',dir=scratch) as frozen:
        digest=hashlib.sha256();size=0
        with safe(pin['path']).open('rb') as source:
            while block:=source.read(65536):
                size+=len(block)
                if size>expected['size']:raise ValueError('NATIVE_MANIFEST_CHANGED')
                digest.update(block);frozen.write(block)
        if size!=expected['size'] or 'sha256:'+digest.hexdigest()!=expected['digest']:raise ValueError('NATIVE_MANIFEST_CHANGED')
        frozen.seek(0)
        while True:
            row=frozen.readline(maximum+1)
            if not row:break
            if len(row)>maximum:raise ValueError('NATIVE_MANIFEST_ROW_WORKER_REQUIRED')
            yield json.loads(row,object_pairs_hook=unique)


def module(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    result = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(result)
    return result


def write(path, value):
    path = safe(path)
    raw = json.dumps(value, sort_keys=True, separators=(',', ':'), ensure_ascii=False).encode('utf-8')
    pending = path.with_name(path.name + '.' + uuid.uuid4().hex + '.part')
    with pending.open('xb') as output:
        output.write(raw); output.flush(); os.fsync(output.fileno())
    try:
        os.link(pending, path)
    finally:
        pending.unlink()


def private_state(profile):
    require(profile['storageReceipt'])
    storage = module('native_storage', RESOURCES / 'hyperv/host_storage.py')
    receipt = read(profile['storageReceipt']['path'], 16384)
    volume = storage.PinnedVolume(receipt, profile['workspace'], profile['storageOwner'])
    volume.check(); root = safe(profile['stateDirectory']); volume.contained(root)
    if not root.is_dir():
        raise ValueError('REGISTERED_PRIVATE_STATE_REQUIRED')
    owner = profile['storageOwner']
    if not re.fullmatch(r'S-1-[0-9]+(?:-[0-9]+)+', owner):
        raise ValueError('NATIVE_STORAGE_OWNER_INVALID')
    require(profile['powershell'])
    # Replace the directory DACL rather than retaining old explicit broad access rules.
    script = """$ErrorActionPreference='Stop';$v=[Console]::In.ReadToEnd()|ConvertFrom-Json;
$sid=[Security.Principal.SecurityIdentifier]::new($v.owner);
$acl=[Security.AccessControl.DirectorySecurity]::new();$acl.SetOwner($sid);$acl.SetAccessRuleProtection($true,$false);
foreach($id in @($v.owner,'S-1-5-18')){$acl.AddAccessRule([Security.AccessControl.FileSystemAccessRule]::new([Security.Principal.SecurityIdentifier]::new($id),'FullControl','ContainerInherit,ObjectInherit','None','Allow'))};
Set-Acl -LiteralPath $v.path -AclObject $acl"""
    subprocess.run([profile['powershell']['path'],'-NoProfile','-NonInteractive','-EncodedCommand',base64.b64encode(script.encode('utf-16le')).decode('ascii')],
                   check=True,input=json.dumps(dict(path=str(root),owner=owner)).encode('utf-8'),stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL,env=host_environment(profile),
                   timeout=profile['resources']['controlMillis'] / 1000)
    return volume


def host_environment(profile):
    allowed={'SYSTEMROOT','WINDIR','TEMP','TMP','USERPROFILE','HOMEDRIVE','HOMEPATH','PROGRAMFILES','PROGRAMFILES(X86)','PROGRAMDATA','COMPUTERNAME','USERNAME','USERDOMAIN'}
    env={key:value for key,value in os.environ.items() if key.upper() in allowed}
    system=Path(env.get('SystemRoot',env.get('SYSTEMROOT','C:/Windows')))
    powershell=safe(profile['powershell']['path']).parent
    env['PATH']=os.pathsep.join(map(str,[powershell,system/'System32',system]))
    program_files=Path(env.get('ProgramFiles',env.get('PROGRAMFILES','C:/Program Files')))
    env['PSModulePath']=os.pathsep.join(map(str,[powershell/'Modules',system/'System32/WindowsPowerShell/v1.0/Modules',program_files/'WindowsPowerShell/Modules']))
    env['PYTHONPATH']=str(safe(profile['pythonModules']));env['PYTHONNOUSERSITE']='1';env['PYTHONDONTWRITEBYTECODE']='1'
    return env


def modules(profile):
    require(profile['pythonModuleManifest'])
    root = safe(profile['pythonModules']); expected = set()
    for row in pinned_rows(profile['pythonModuleManifest'],profile['resources']['maxRowBytes'],profile['stateDirectory']):
        if set(row) != {'path', 'digest', 'size'}:
            raise ValueError('NATIVE_MODULE_MANIFEST_INVALID')
        relative = relative_path(row['path'])
        if relative in expected or handle(root / relative) != dict(digest=row['digest'], size=row['size']):
            raise ValueError('NATIVE_MODULE_BYTES_CHANGED')
        expected.add(relative)
    observed = set()
    for path in root.rglob('*'):
        safe(path)
        if path.is_file(): observed.add(path.relative_to(root).as_posix())
    if not expected or observed != expected:
        raise ValueError('NATIVE_MODULE_DENOMINATOR_OPEN')
    sys.dont_write_bytecode = True
    sys.path.insert(0, str(root))


def relative_path(value, dot=False):
    if dot and value == '.': return ''
    if not isinstance(value, str) or not value or '\\' in value or '\0' in value or value.startswith('/') or any(p in ('', '.', '..') for p in value.split('/')):
        raise ValueError('NATIVE_LOGICAL_PATH_INVALID')
    return value


def cas_path(root, content):
    digest = content['digest']
    if not re.fullmatch('sha256:[0-9a-f]{64}', digest) or type(content['size']) is not int or content['size'] < 0:
        raise ValueError('NATIVE_CONTENT_HANDLE_INVALID')
    return safe(root / 'sha256' / digest[7:9] / digest[9:])


def main():
    global ACTIVE_ROOT, STAGE
    parser = argparse.ArgumentParser()
    parser.add_argument('--prepare-state', type=Path)
    parser.add_argument('--job', type=Path)
    parser.add_argument('--request-pin')
    parser.add_argument('--controller-pid', type=int)
    args = parser.parse_args()
    if args.prepare_state:
        private_state(read(args.prepare_state)); return
    if not args.job or not args.request_pin or not args.controller_pid:
        raise ValueError('NATIVE_JOB_SCOPE_REQUIRED')
    root = safe(args.job)
    ACTIVE_ROOT=root
    if handle(root / 'request.json')['digest'] != args.request_pin:
        raise ValueError('NATIVE_REQUEST_PIN_CHANGED')
    job = read(root / 'request.json'); profile = job['profile']; limits = profile['resources']; launch = job['launch']
    if job['schema'] != 'native-build-job:m8.4-v1': raise ValueError('NATIVE_JOB_SCHEMA')
    attempt = uuid.UUID(launch['attempt']); work = '/work/' + attempt.hex; source = work + '/source'
    launch_digest = 'sha256:' + hashlib.sha256(json.dumps(launch, sort_keys=True, separators=(',', ':'), ensure_ascii=False).encode('utf-8')).hexdigest()
    volume = private_state(profile); volume.contained(root)
    require(profile['python']); require(profile['powershell']); modules(profile)
    from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey
    from cryptography.hazmat.primitives import serialization
    import paramiko
    import pycdlib
    def keypair(name):
        key = Ed25519PrivateKey.generate(); private = root / name
        with private.open('xb') as output: output.write(key.private_bytes(serialization.Encoding.PEM, serialization.PrivateFormat.OpenSSH, serialization.NoEncryption()))
        return private, key.public_key().public_bytes(serialization.Encoding.OpenSSH, serialization.PublicFormat.OpenSSH).decode('ascii')
    identity, public = keypair('controller-ed25519'); host_private, host_public = keypair('guest-host-ed25519')
    network = profile['network']; address = ipaddress.IPv4Interface(network['guestCidr']); controller_ip = ipaddress.IPv4Address(network['controller'])
    if address.ip == controller_ip: raise ValueError('NATIVE_NETWORK_SCOPE')
    with (root / 'known_hosts').open('x', encoding='ascii') as output: output.write(str(address.ip) + ' ' + host_public + '\n')
    write(root / 'controller-config.json', dict(workerHost=str(address.ip), hostKeyAlias=str(address.ip), identityFile=str(identity), knownHostsFile=str(root / 'known_hosts')))
    sys.path.insert(0, str(RESOURCES / 'guest'))
    seed_tool = module('native_seed', RESOURCES / 'guest/make_worker_seed.py')
    mac = ':'.join(network['mac'][i:i+2].lower() for i in range(0, 12, 2))
    config, metadata, net = seed_tool.configuration(attempt.hex, mac, str(address), public, host_private.read_text(encoding='ascii'), host_public, limits['workBytes'])
    policy = dict(schema='native-build-guest-policy:m8.4-v1', attempt=attempt.hex,
                  tools={role:dict(executable=value['executable'], content=value['tool']['binary']) for role,value in profile['tools'].items()}, resources=limits)
    for path, raw, permissions in [('/usr/local/lib/evo/nativebuild/runner.py', (RESOURCES/'nativebuild/guest_runner.py').read_bytes(), '0700'),
                                   ('/usr/local/lib/evo/nativebuild/gradle-init.gradle', (RESOURCES/'nativebuild/gradle-init.gradle').read_bytes(), '0644'),
                                   ('/usr/local/lib/evo/nativebuild/context.json', json.dumps(dict(contexts=job['contexts'], source=source, work=work)).encode(), '0644'),
                                   ('/var/lib/evo/native-build-policy.json', json.dumps(policy).encode(), '0400')]:
        config['write_files'].append(dict(path=path, owner='root:root', permissions=permissions, encoding='b64', content=base64.b64encode(raw).decode('ascii')))
    seed = root / 'worker-seed.iso'; iso = pycdlib.PyCdlib(); iso.new(interchange_level=3, joliet=3, rock_ridge='1.09', vol_ident='cidata')
    try:
        for name, iso_name, value in zip(('user-data','meta-data','network-config'),('USERDATA','METADATA','NETWORK'),(config,metadata,net)):
            raw=(('#cloud-config\n' if name=='user-data' else '')+json.dumps(value)+'\n').encode(); iso.add_fp(io.BytesIO(raw),len(raw),iso_path='/'+iso_name+'.;1',rr_name=name,joliet_path='/'+name)
        with seed.open('xb') as output: iso.write_fp(output); output.flush(); os.fsync(output.fileno())
    finally: iso.close()
    provision = dict(schema='hyperv-native-provision-request:m8.4-v1', attempt=str(attempt), workspace=str(root), launch=launch_digest, fence=launch['fence'],
                     driverPin=handle(RESOURCES/'hyperv/worker.ps1')['digest'], baseRoot=profile['baseRoot'], disk=profile['disk']['path'],
                     images=[dict(path=p['path'], **p['content']) for p in profile['images']], seed=dict(path=str(seed), **handle(seed)),
                     network=dict(switch=network['switchName'],mac=network['mac'],guest=str(address.ip),controller=str(controller_ip)),
                     resources=dict(processors=limits['processors'],memoryBytes=limits['memoryBytes']))
    write(root/'provision-request.json',provision)
    export = dict(attempt=attempt.hex,nonce=uuid.uuid4().hex,work=work,maxRowBytes=limits['maxRowBytes'],maxObjectBytes=limits['maxObjectBytes'],maxCaptureBytes=limits['maxCaptureBytes'])
    write(root/'export-request.json',dict(request=export,collectorPin=handle(RESOURCES/'guest/export.py')['digest']))
    owner_path=root/('owner-'+attempt.hex+'.json'); watcher=None; client=None
    def checked(argv, timeout=None):
        result=subprocess.run(argv,stdin=subprocess.DEVNULL,stdout=subprocess.PIPE,stderr=subprocess.DEVNULL,env=host_environment(profile),timeout=timeout or limits['controlMillis']/1000)
        if result.returncode or len(result.stdout)>1048576: raise RuntimeError('NATIVE_CONTROL_OPERATION_FAILED')
        return result.stdout
    def provision_call(action):
        return checked([profile['powershell']['path'],'-NoProfile','-NonInteractive','-File',str(RESOURCES/'hyperv/provision.ps1'),'-Action',action,'-Request',str(root/'provision-request.json'),'-RequestPin',handle(root/'provision-request.json')['digest']])
    try:
        STAGE='PROVISIONING';provision_call('Prepare')
        supervisor=dict(schema='hyperv-supervisor-config:m8.4-v1',root=str(root),owner=str(owner_path),powershell=profile['powershell']['path'],powershellPin=profile['powershell']['content']['digest'],
                        driver=str(RESOURCES/'hyperv/worker.ps1'),driverPin=handle(RESOURCES/'hyperv/worker.ps1')['digest'],controlPid=args.controller_pid,
                        runtimeMillis=limits['runtimeMillis']+limits['controlMillis']*6,overlayBytes=limits['overlayBytes'],logBytes=limits['logBytes'],logs=[str(root/'native-build.private.log')],pollMillis=100)
        write(root/'supervisor-config.json',supervisor)
        with (root/'supervisor.private.log').open('xb') as errors:
            watcher=subprocess.Popen([sys.executable,'-I',str(RESOURCES/'hyperv/supervisor.py'),'--config',str(root/'supervisor-config.json')],stdin=subprocess.DEVNULL,stdout=subprocess.DEVNULL,stderr=errors,
                                     env=host_environment(profile),close_fds=True,creationflags=subprocess.CREATE_NO_WINDOW|subprocess.CREATE_NEW_PROCESS_GROUP)
        deadline=time.monotonic()+limits['controlMillis']/1000
        while not (root/'supervisor-ready.json').exists():
            if watcher.poll() is not None or time.monotonic()>deadline: raise RuntimeError('NATIVE_SUPERVISOR_UNAVAILABLE')
            time.sleep(.1)
        STAGE='STARTING';provision_call('Start')
        client=paramiko.SSHClient(); client.load_host_keys(str(root/'known_hosts')); client.set_missing_host_key_policy(paramiko.RejectPolicy())
        deadline=time.monotonic()+limits['controlMillis']/1000
        while True:
            try:
                client.connect(str(address.ip),username='evoctl',key_filename=str(identity),allow_agent=False,look_for_keys=False,timeout=3,banner_timeout=3,auth_timeout=3); break
            except (paramiko.BadHostKeyException,paramiko.AuthenticationException): raise RuntimeError('NATIVE_TRANSPORT_AUTHENTICATION_FAILED') from None
            except (OSError,paramiko.SSHException):
                client.close()
                if time.monotonic()>deadline: raise RuntimeError('NATIVE_TRANSPORT_UNAVAILABLE') from None
                time.sleep(1)
        def remote(command,data=None,timeout=None,log=None):
            seconds=timeout or limits['controlMillis']/1000; stdin,out,err=client.exec_command(command,timeout=seconds)
            if data is not None: stdin.write(data)
            stdin.channel.shutdown_write(); channel=out.channel
            if log:
                storage=module('native_log_storage',RESOURCES/'hyperv/host_storage.py')
                report=storage.capture_channel_log(channel,log,limits['logBytes'],time.monotonic()+seconds,volume)
                if not report['complete']: raise RuntimeError('NATIVE_BUILD_RESOURCE_LIMIT')
                return channel.recv_exit_status(),b''
            raw=bytearray();end=time.monotonic()+seconds
            while not channel.exit_status_ready() or channel.recv_ready() or channel.recv_stderr_ready():
                if time.monotonic()>end: channel.close(); raise RuntimeError('NATIVE_CONTROL_TIMEOUT')
                if channel.recv_ready(): raw.extend(channel.recv(65536))
                if channel.recv_stderr_ready(): channel.recv_stderr(65536)
                if len(raw)>1048576: channel.close();raise RuntimeError('NATIVE_CONTROL_RESPONSE_WORKER_REQUIRED')
                time.sleep(.01)
            return channel.recv_exit_status(),bytes(raw)
        def control(command,data=None):
            code,raw=remote(command,data)
            if code: raise RuntimeError('NATIVE_GUEST_CONTROL_FAILED')
            return raw
        deadline=time.monotonic()+limits['controlMillis']/1000
        while True:
            code,raw=remote('sudo -n cat /var/lib/evo/bootstrap-complete.json')
            if code==0:
                boot=json.loads(raw,object_pairs_hook=unique)
                if boot['attempt']!=attempt.hex or boot['work']!=work or boot['workBytes']!=limits['workBytes']: raise ValueError('NATIVE_BOOTSTRAP_SCOPE_MISMATCH')
                break
            if time.monotonic()>deadline: raise RuntimeError('NATIVE_BOOTSTRAP_TIMEOUT')
            time.sleep(1)
        control('sudo -n mountpoint -q '+shlex.quote(work));control('sudo -n chown evoctl:evoctl '+shlex.quote(work));control('sudo -n chmod 0750 '+shlex.quote(work))
        STAGE='INPUT_TRANSFER';content_root=safe(job['contentDirectory']); table=cas_path(content_root,job['sourceTable'])
        if handle(table)!=job['sourceTable']:raise ValueError('NATIVE_SOURCE_TABLE_CHANGED')
        made={work};total=0
        with client.open_sftp() as sftp:
            def mkdir(path):
                current=''
                for part in PurePosixPath(path).parts:
                    if part=='/':current='';continue
                    current+='/'+part
                    if current.startswith(work) and current not in made: sftp.mkdir(current);made.add(current)
            def upload(local,destination,expected,mode=0o600):
                nonlocal total
                total+=expected['size']
                if total>limits['inputBytes']:raise ValueError('NATIVE_INPUT_RESOURCE_CLASS_REQUIRED')
                mkdir(posixpath.dirname(destination))
                # Freeze registered bytes before opening a guest file: changed host bytes cannot leak.
                with tempfile.TemporaryFile(mode='w+b',dir=root) as frozen:
                    digest=hashlib.sha256();size=0
                    with safe(local).open('rb') as source_file:
                        while block:=source_file.read(65536):
                            size+=len(block)
                            if size>expected['size']:raise ValueError('NATIVE_INPUT_CHANGED')
                            digest.update(block);frozen.write(block)
                    if size!=expected['size'] or 'sha256:'+digest.hexdigest()!=expected['digest']:raise ValueError('NATIVE_INPUT_CHANGED')
                    frozen.seek(0)
                    with sftp.open(destination,'wx') as target:
                        while block:=frozen.read(65536):target.write(block)
                    sftp.chmod(destination,mode)
            mkdir(source);mkdir(work+'/home');mkdir(work+'/cache/maven');mkdir(work+'/cache/gradle')
            for links in (False,True):
                with table.open('rb') as rows:
                    for raw in rows:
                        if len(raw)>limits['maxRowBytes']:raise ValueError('NATIVE_INPUT_ROW_WORKER_REQUIRED')
                        row=json.loads(raw,object_pairs_hook=unique);relative=relative_path(row['path']);destination=source+'/'+relative
                        if row['kind']=='DIRECTORY':
                            if not links:mkdir(destination)
                        elif row['kind']=='FILE':
                            if not links: upload(cas_path(content_root,row['content']),destination,row['content'],0o700 if int(row['mode'],8)&0o111 else 0o600)
                        elif row['kind']=='SYMBOLIC_LINK':
                            if links:
                                target=row['target']
                                if not isinstance(target,str) or target.startswith('/') or '\0' in target or '\\' in target:raise ValueError('NATIVE_SOURCE_LINK_ADAPTER_REQUIRED')
                                resolved=posixpath.normpath(posixpath.join(posixpath.dirname(relative),target))
                                if resolved=='..' or resolved.startswith('../'):raise ValueError('NATIVE_SOURCE_LINK_ESCAPE')
                                mkdir(posixpath.dirname(destination));sftp.symlink(target,destination)
                        else:raise ValueError('NATIVE_SOURCE_KIND_PENDING')
            for materials in profile['materials']:
                require(materials['manifest']);material_root=safe(materials['directory']);seen=set()
                for row in pinned_rows(materials['manifest'],limits['maxRowBytes'],root):
                    if set(row) not in ({'path','digest','size'},{'path','digest','size','mode'}):raise ValueError('NATIVE_MATERIAL_MANIFEST_INVALID')
                    relative=relative_path(row['path'])
                    if relative in seen:raise ValueError('NATIVE_MATERIAL_DUPLICATE')
                    mode=row.get('mode','0600')
                    if not isinstance(mode,str) or not re.fullmatch('[0-7]{3,6}',mode):raise ValueError('NATIVE_MATERIAL_MODE_INVALID')
                    seen.add(relative);upload(material_root/relative,work+'/cache/'+materials['role']+'/'+relative,dict(digest=row['digest'],size=row['size']),0o700 if int(mode,8)&0o111 else 0o600)
            settings=b'<settings><mirrors><mirror><id>isolated-offline</id><mirrorOf>*</mirrorOf><url>http://127.0.0.1:9/unavailable/</url></mirror></mirrors></settings>'
            with sftp.open(work+'/settings.xml','wx') as output:output.write(settings)
        control('sudo -n chown -R evobuild:evobuild '+shlex.quote(work))
        runner_pin=handle(RESOURCES/'nativebuild/guest_runner.py')['digest']
        if control('sudo -n sha256sum /usr/local/lib/evo/nativebuild/runner.py').decode().split()[0]!=runner_pin[7:]:raise ValueError('NATIVE_RUNNER_CHANGED')
        recipe=job['recipe'];role=recipe['tool']['role'];commands=recipe['commands']
        if len(commands)!=1:raise ValueError('NATIVE_RECIPE_COMMAND_SET')
        command=commands[0];directory=source+('/'+relative_path(command['rootDirectory'],True) if command['rootDirectory']!='.' else '')
        arguments=list(command['arguments'])
        if role=='maven':arguments=['--offline','-s',work+'/settings.xml','-Dmaven.repo.local='+work+'/cache/maven',*arguments]
        elif role=='gradle':arguments=['--offline','-g',work+'/cache/gradle','--project-cache-dir',work+'/project-cache',*arguments]
        else:raise ValueError('NATIVE_BUILD_FAMILY_REQUIRED')
        STAGE='BUILD';write(root/'target-requested.json',dict(attempt=str(attempt),launch=launch_digest))
        code,_=remote('sudo -n /usr/bin/python3 /usr/local/lib/evo/nativebuild/runner.py',json.dumps(dict(attempt=attempt.hex,role=role,arguments=arguments,directory=directory)),timeout=limits['runtimeMillis']/1000,log=root/'native-build.private.log')
        returned=json.loads(control('sudo -n cat /var/lib/evo/native-build-finished.json'),object_pairs_hook=unique)
        if returned.get('schema')!='native-build-launcher-returned:m8.4-v1' or returned.get('attempt')!=attempt.hex or returned.get('role')!=role or returned.get('tool')!=recipe['tool']['binary'] or returned.get('exitCode')!=code:raise ValueError('NATIVE_LAUNCHER_RECEIPT_SCOPE_MISMATCH')
        write(root/'native-result.json',dict(schema='native-build-execution:m8.4-v1',attempt=str(attempt),launch=launch_digest,recipe=launch['recipe'],exitCode=code,role=role,
                                            semanticTrust='PRODUCER_ASSERTION',semanticValidated=False,offline=True,launcherReceipt=returned))
        STAGE='CAPTURE';checked([sys.executable,'-s',str(RESOURCES/'mvp/capture.py'),'--root',str(root/'capture'),'--config',str(root/'controller-config.json'),'--owner',str(owner_path),
                 '--work-attempt',attempt.hex,'--request',str(root/'export-request.json'),'--powershell',profile['powershell']['path'],'--manifest-bytes',str(limits['maxManifestBytes'])],timeout=limits['controlMillis']/1000*3)
    finally:
        if client is not None:client.close()
        if owner_path.exists():
            owner=read(owner_path,65536)
            off=json.loads(checked([profile['powershell']['path'],'-NoProfile','-NonInteractive','-File',str(RESOURCES/'hyperv/worker.ps1'),'-Action','Stop','-Workspace',str(root),'-Attempt',str(attempt),'-LaunchDigest',launch_digest,'-Fence',str(launch['fence'])]),object_pairs_hook=unique)
            if off['state']!='Off' or off['vmId']!=owner['vmId']:raise ValueError('NATIVE_TERMINATION_UNCONFIRMED')
            if watcher is not None:
                marker=root/'controller-protocol-complete.json'
                if not marker.exists():write(marker,dict(attempt=str(attempt),fence=launch['fence'],launch=launch_digest))
                if watcher.wait(timeout=limits['controlMillis']/1000)!=0:raise ValueError('NATIVE_SUPERVISOR_FAILED')


if __name__=='__main__':
    try:main()
    except Exception as failure:
        # No raw target data, transport credentials or arbitrary exception text in public diagnostics.
        reason=str(failure) if re.fullmatch(r'(?:NATIVE|REGISTERED)_[A-Z0-9_]{1,80}',str(failure)) else 'NATIVE_CONTROLLER_FAILED'
        if ACTIVE_ROOT is not None:
            try:write(ACTIVE_ROOT/'failure.json',dict(schema='native-build-failure:m8.4-v1',reason=reason,stage=STAGE))
            except (OSError,ValueError):pass
        print('NATIVE_CONTROLLER_FAILED',file=sys.stderr);sys.exit(1)
