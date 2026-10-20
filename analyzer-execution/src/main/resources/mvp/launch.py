#!/usr/bin/env python3
"""Human-approved Core MVP: owned Hyper-V -> offline Maven -> opaque export -> Off.
No general conformance certificate, semantic promotion or target execution on the host.
"""
import argparse
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import sys
import time
import uuid
from capture import unique


def sequence(prepare, supervise, start, build, capture, stop):
    """Always reconcile termination, including failures between prepare and export."""
    try:
        prepare()
        supervise()
        start()
        build()
        capture()
    finally:
        stop()


def main():
    parser=argparse.ArgumentParser()
    parser.add_argument('--root',type=Path,required=True)
    parser.add_argument('--powershell',type=Path,required=True)
    parser.add_argument('--control-pid',type=int,required=True)
    args=parser.parse_args();root=args.root.resolve();resources=Path(__file__).resolve().parents[1]
    setup=json.loads((root/'setup.json').read_bytes(),object_pairs_hook=unique)
    attempt=str(uuid.UUID(setup['attempt']));work_attempt=uuid.UUID(attempt).hex
    if setup.get('acceptanceScope')!='CORE_MVP' or setup.get('approvedBy')!='HUMAN':raise ValueError('CORE_MVP_APPROVAL_REQUIRED')
    if Path(setup['root']).resolve()!=root:raise ValueError('CORE_MVP_ROOT_MISMATCH')
    spec=importlib.util.spec_from_file_location('mvp_supervisor',resources/'hyperv/supervisor.py')
    supervisor=importlib.util.module_from_spec(spec);spec.loader.exec_module(supervisor)
    owner_path=root/('owner-'+work_attempt+'.json');watcher=None
    def digest(path):
        with Path(path).open('rb') as source:return 'sha256:'+hashlib.file_digest(source,'sha256').hexdigest()
    def checked(command,timeout=360):
        # Platform executables/scripts only. Target argv is confined to the guest runner.
        result=subprocess.run(command,stdin=subprocess.DEVNULL,stdout=subprocess.PIPE,stderr=subprocess.PIPE,timeout=timeout)
        if len(result.stdout)+len(result.stderr)>1048576:raise RuntimeError('CORE_MVP_CONTROL_RESPONSE_TOO_LARGE')
        if result.returncode:
            with (root/('control-failure-'+uuid.uuid4().hex+'.private.log')).open('xb') as log:log.write(result.stdout+result.stderr)
            raise RuntimeError('CORE_MVP_CONTROL_FAILED')
        return result.stdout
    request=root/'provision-request.json'
    def provision(action):
        return checked([str(args.powershell),'-NoProfile','-NonInteractive','-File',str(resources/'hyperv/provision.ps1'),'-Action',action,'-Request',str(request),'-RequestPin',digest(request)])
    def prepare():provision('Prepare')
    def supervise():
        nonlocal watcher
        config=dict(schema='hyperv-supervisor-config:m8.4-v1',root=str(root),owner=str(owner_path),
                    powershell=str(args.powershell.resolve()),powershellPin=digest(args.powershell),
                    driver=str(resources/'hyperv/worker.ps1'),driverPin=digest(resources/'hyperv/worker.ps1'),
                    controlPid=args.control_pid,runtimeMillis=900000,overlayBytes=2147483648,logBytes=16777216,
                    logs=[str(root/'native-maven.log')],pollMillis=100)
        with (root/'supervisor-config.json').open('x',encoding='utf-8') as target:json.dump(config,target,sort_keys=True)
        with (root/'supervisor-private.log').open('xb') as errors:
            watcher=subprocess.Popen([sys.executable,'-I','-c',(resources/'hyperv/supervisor.py').read_text(encoding='utf-8'),
                    '--config',str(root/'supervisor-config.json')],stdin=subprocess.DEVNULL,stdout=subprocess.DEVNULL,stderr=errors,
                    close_fds=True,creationflags=subprocess.CREATE_NO_WINDOW|subprocess.CREATE_NEW_PROCESS_GROUP)
        deadline=time.monotonic()+45
        while not (root/'supervisor-ready.json').exists():
            if watcher.poll() is not None or time.monotonic()>=deadline:raise RuntimeError('CORE_MVP_SUPERVISOR_UNAVAILABLE')
            time.sleep(.1)
    def start():provision('Start');wait_bootstrap(root,setup,work_attempt)
    def build():
        storage=setup['hostStorage']
        checked([sys.executable,str(resources/'mvp/native_build.py'),'--root',str(root),'--config',str(root/'controller-config.json'),
                 '--owner',str(owner_path),'--request',str(root/'native-request.json'),'--preallocated',
                 '--host-storage-receipt',storage['receipt'],'--storage-owner',storage['owner']])
    def capture():
        checked([sys.executable,str(resources/'mvp/capture.py'),'--root',str(root/'capture'),'--config',str(root/'controller-config.json'),
                 '--owner',str(owner_path),'--work-attempt',work_attempt,'--request',str(root/'export-request.json')])
    def stop():
        if owner_path.exists():
            owner=json.loads(owner_path.read_bytes(),object_pairs_hook=unique)
            observed=supervisor.control(json.loads((root/'supervisor-config.json').read_bytes(),object_pairs_hook=unique),owner,'Stop') if (root/'supervisor-config.json').exists() else json.loads(checked([
                str(args.powershell),'-NoProfile','-File',str(resources/'hyperv/worker.ps1'),'-Action','Stop','-Workspace',str(root),'-Attempt',attempt,
                '-LaunchDigest',owner['launch'],'-Fence',str(owner['fence'])],45),object_pairs_hook=unique)
            if observed['state']!='Off':raise RuntimeError('CORE_MVP_TERMINATION_UNCONFIRMED')
        if watcher is not None:
            marker=root/'controller-protocol-complete.json'
            if not marker.exists():
                owner=json.loads(owner_path.read_bytes(),object_pairs_hook=unique)
                with marker.open('x',encoding='utf-8') as target:
                    json.dump(dict(attempt=attempt,fence=owner['fence'],launch=owner['launch']),target,sort_keys=True);target.flush();os.fsync(target.fileno())
            if watcher.wait(timeout=45)!=0:raise RuntimeError('CORE_MVP_SUPERVISOR_FAILED')
    sequence(prepare,supervise,start,build,capture,stop)
    print(json.dumps(dict(scope='CORE_MVP',attempt=attempt,status='EXPORTED',qualified=False)))


def wait_bootstrap(root,setup,attempt):
    import paramiko
    client=paramiko.SSHClient();client.load_host_keys(str(root/'known_hosts'));deadline=time.monotonic()+300
    try:
        while time.monotonic()<deadline:
            try:
                if not client.get_transport() or not client.get_transport().is_active():
                    client.connect(setup['guestAddress'],username='evoctl',key_filename=setup['identityFile'],allow_agent=False,look_for_keys=False,
                                   timeout=3,banner_timeout=3,auth_timeout=3)
                _,out,_=client.exec_command('sudo -n cat /var/lib/evo/bootstrap-complete.json',timeout=10)
                raw=out.read(16385)
                if out.channel.recv_exit_status()==0:
                    value=json.loads(raw,object_pairs_hook=unique)
                    if value.get('attempt')!=attempt:raise ValueError('CORE_MVP_BOOTSTRAP_SCOPE_MISMATCH')
                    return
            except (OSError,paramiko.SSHException):client.close()
            time.sleep(2)
        raise RuntimeError('CORE_MVP_BOOTSTRAP_TIMEOUT')
    finally:client.close()


if __name__=='__main__':main()
