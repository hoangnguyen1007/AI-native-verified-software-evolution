#!/usr/bin/env python3
"""Registered authored fixtures only. An independent host process always reaps this candidate.
This harness cannot issue a qualification certificate or open production admission.
"""
import argparse
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import threading
import time
import uuid
from hyperv_export_fixture import unique, export_request


def wait_bootstrap(reader, attempt, work, deadline, clock=time.monotonic, pause=time.sleep):
    while True:
        if clock() >= deadline:
            raise RuntimeError('WORKER_BOOTSTRAP_NOT_READY')
        code, raw, _ = reader()
        if clock() >= deadline:
            raise RuntimeError('WORKER_BOOTSTRAP_NOT_READY')
        if code == 0:
            observation = json.loads(raw, object_pairs_hook=unique)
            if (not isinstance(observation, dict) or observation.get('schema') != 'guest-worker-bootstrap-observation:m8.4-v1'
                    or observation.get('attempt') != attempt or observation.get('work') != work):
                raise RuntimeError('BOOTSTRAP_SCOPE_MISMATCH')
            return observation
        remaining = deadline - clock()
        if remaining <= 0:
            raise RuntimeError('WORKER_BOOTSTRAP_NOT_READY')
        pause(min(1, remaining))


def maven_fixture_command(executable, root, owner_path, request_path,storage=None):
    command=[executable,str(Path(__file__).with_name('hyperv_publication_native.py')),
            '--config',str(root/'controller-config.json'),'--root',str(root),'--owner',str(owner_path),
            '--request',str(request_path),'--preallocated']
    if storage is not None:command+=['--host-storage-receipt',storage['receipt'],'--storage-owner',storage['owner']]
    return command


def main():
    import paramiko
    parser=argparse.ArgumentParser()
    parser.add_argument('--root',type=Path,required=True)
    parser.add_argument('--powershell',type=Path,required=True)
    parser.add_argument('--stop-mode',choices=['complete','loss','runtime'],required=True)
    parser.add_argument('--runtime-millis',type=int,default=600000)
    parser.add_argument('--bootstrap-diagnostic',action='store_true',help='Only inspect trusted first-boot setup; never run native fixtures')
    parser.add_argument('--management-wait-seconds',type=int,default=300)
    parser.add_argument('--native-maven',action='store_true',help='Run only the registered offline Maven fixture inside this candidate')
    parser.add_argument('--native-log-bomb',action='store_true',help='Force bounded capture of a registered native stdout producer')
    args=parser.parse_args();root=args.root.resolve();repo=Path(__file__).resolve().parents[4]
    if not 1 <= args.management_wait_seconds <= 600:raise ValueError('INVALID_MANAGEMENT_WAIT_POLICY')
    setup=json.loads((root/'setup.json').read_text(encoding='utf-8-sig'),object_pairs_hook=unique)
    attempt=uuid.UUID(setup['attempt']).hex;work='/work/'+attempt
    owner_path=root/('owner-'+attempt+'.json');owner=json.loads(owner_path.read_bytes(),object_pairs_hook=unique)
    resources=repo/'analyzer-execution/src/main/resources'
    spec=importlib.util.spec_from_file_location('campaign_host_storage',resources/'hyperv/host_storage.py')
    storage_module=importlib.util.module_from_spec(spec);spec.loader.exec_module(storage_module)
    volume=None
    if setup.get('hostStorage') is not None:
        configured=setup['hostStorage']
        with Path(configured['receipt']).open('rb') as source:raw=source.read(16385)
        if len(raw)>16384:raise ValueError('HOST_STORAGE_RECEIPT_WORKER_REQUIRED')
        if 'sha256:'+hashlib.sha256(raw).hexdigest()!=configured['receiptDigest']:raise ValueError('HOST_STORAGE_RECEIPT_PIN_MISMATCH')
        volume=storage_module.PinnedVolume(json.loads(raw,object_pairs_hook=unique),str(repo),configured['owner'])
        volume.contained(root)
    planned=['private-home-system-devices-and-old-work','cannot-modify-capture-agent',
             'no-control-or-metadata-network','bounded-memory-allocation','bounded-process-count']
    if args.native_maven:planned+=['offline-maven-generated-reactor','bounded-work-filesystem']
    if args.native_log_bomb:planned+=['bounded-native-log-producer']
    with (root/'campaign-intent.json').open('x',encoding='utf-8') as out:
        json.dump(dict(attempt=attempt,controls=planned,stopMode=args.stop_mode,qualified=False),out,sort_keys=True)
    supervisor=resources/'hyperv/supervisor.py';driver=resources/'hyperv/worker.ps1';provision=resources/'hyperv/provision.ps1'
    def digest(path):
        with path.open('rb') as source:return 'sha256:'+hashlib.file_digest(source,'sha256').hexdigest()
    config=dict(schema='hyperv-supervisor-config:m8.4-v1',root=str(root),owner=str(owner_path),
                powershell=str(args.powershell.resolve()),powershellPin=digest(args.powershell),driver=str(driver),driverPin=digest(driver),
                controlPid=os.getpid(),runtimeMillis=args.runtime_millis,overlayBytes=2147483648,logBytes=16777216,
                logs=[str(root/'native-maven.log'),str(root/'campaign-private.log'),str(root/'native-log-bomb.log')],pollMillis=100)
    with (root/'supervisor-config.json').open('x',encoding='utf-8') as out:json.dump(config,out,sort_keys=True)
    # Captured platform bytes, not a mutable second script read. No inherited terminal/secret output.
    captured=supervisor.read_text(encoding='utf-8')
    with (root/'supervisor-private.log').open('xb') as errors:
        watcher=subprocess.Popen([os.sys.executable,'-I','-c',captured,'--config',str(root/'supervisor-config.json')],
                stdin=subprocess.DEVNULL,stdout=subprocess.DEVNULL,stderr=errors,close_fds=True,
                creationflags=subprocess.CREATE_NO_WINDOW|subprocess.CREATE_NEW_PROCESS_GROUP)
    ready=time.monotonic()+45
    while not (root/'supervisor-ready.json').exists():
        if watcher.poll() is not None or time.monotonic()>=ready:raise RuntimeError('INDEPENDENT_SUPERVISOR_NOT_READY')
        time.sleep(.1)
    request=root/'provision-request.json'
    started=subprocess.run([str(args.powershell),'-NoProfile','-NonInteractive','-File',str(provision),'-Action','Start','-Request',str(request),'-RequestPin',digest(request)],
                           stdin=subprocess.DEVNULL,stdout=subprocess.PIPE,stderr=subprocess.PIPE,timeout=300)
    if started.returncode:
        with (root/'provision-start-private.log').open('xb') as out:out.write(started.stdout+started.stderr)
        raise RuntimeError('PROVISIONED_START_FAILED')
    client=paramiko.SSHClient();client.load_host_keys(str(root/'known_hosts'))
    deadline=time.monotonic()+args.management_wait_seconds
    connection_failures={}
    def connection_failure(kind):
        connection_failures[kind]=connection_failures.get(kind,0)+1
        if time.monotonic()>=deadline:
            with (root/'management-failure.json').open('x',encoding='utf-8') as out:
                json.dump(dict(schema='authored-management-failure:m8.4-v1',attempt=attempt,
                               failures=connection_failures,last=kind,qualified=False),out,sort_keys=True)
            raise RuntimeError(kind) from None
    while True:
        try:
            client.connect(setup['guestAddress'],username='evoctl',key_filename=setup['identityFile'],allow_agent=False,
                           look_for_keys=False,timeout=3,banner_timeout=3,auth_timeout=3);break
        except paramiko.BadHostKeyException:
            # Bootstrap may not yet have replaced the image key. Never accept that key or authenticate to it.
            client.close()
            connection_failure('FRESH_HOST_KEY_NOT_INSTALLED')
            time.sleep(2)
        except paramiko.AuthenticationException:
            client.close()
            connection_failure('FRESH_MANAGEMENT_AUTHENTICATION_REJECTED')
            time.sleep(2)
        except (OSError,paramiko.SSHException):
            client.close()
            connection_failure('FRESH_MANAGEMENT_TRANSPORT_NOT_READY')
            time.sleep(2)
    def remote(command,data=None,maximum=32768,timeout=60):
        stdin,out,err=client.exec_command(command,timeout=timeout)
        if data is not None:stdin.write(data)
        stdin.channel.shutdown_write();channel=out.channel;stdout=bytearray();stderr=bytearray();end=time.monotonic()+timeout
        while not channel.exit_status_ready() or channel.recv_ready() or channel.recv_stderr_ready():
            if time.monotonic()>end:channel.close();raise RuntimeError('CAMPAIGN_CONTROL_DEADLINE')
            if channel.recv_ready():stdout.extend(channel.recv(65536))
            if channel.recv_stderr_ready():stderr.extend(channel.recv_stderr(65536))
            if len(stdout)+len(stderr)>maximum:channel.close();raise RuntimeError('CAMPAIGN_CONTROL_RESPONSE_OVERSIZE')
            time.sleep(.01)
        return channel.recv_exit_status(),bytes(stdout),bytes(stderr)
    def checked(command):
        code,out,_=remote(command)
        if code:raise RuntimeError('CAMPAIGN_CONTROL_FAILED')
        return out
    if args.bootstrap_diagnostic:
        # Owned fresh clone only. Diagnostics stay operator-private and are never qualification evidence.
        commands=['sudo -n cloud-init status --format json',
                  "sudo -n stat -c '%a %U %n' /etc/ssh /etc/ssh/sshd_config.d /etc/ssh/evo_worker_keys /home/evoctl /home/evoctl/.ssh /home/evoctl/.ssh/authorized_keys",
                  'sudo -n journalctl -b -u ssh --no-pager -n 30',
                  'sudo -n tail -n 30 /var/log/cloud-init-output.log']
        for index,command in enumerate(commands):
            code,out,err=remote(command)
            with (root/('bootstrap-diagnostic-'+str(index)+'.private.log')).open('xb') as log:
                log.write(str(code).encode()+b'\n'+out+err)
        client.close()
        with (root/'controller-protocol-complete.json').open('x',encoding='utf-8') as out:
            json.dump(dict(attempt=owner['attempt'],fence=owner['fence'],launch=owner['launch']),out,sort_keys=True);out.flush();os.fsync(out.fileno())
        watcher.wait(timeout=60)
        result=json.loads((root/'supervisor-result.json').read_text(),object_pairs_hook=unique)
        if result['state']['state']!='Off':raise RuntimeError('DIAGNOSTIC_VM_TERMINATION_NOT_CONFIRMED')
        print(json.dumps(dict(attempt=attempt,diagnosticsCaptured=True,state='Off',qualified=False)))
        return
    checked('sudo -n true')
    bootstrap=wait_bootstrap(lambda:remote('sudo -n cat /var/lib/evo/bootstrap-complete.json'),
                             attempt,work,time.monotonic()+args.management_wait_seconds)
    with (root/'bootstrap-observation.json').open('x',encoding='utf-8') as out:json.dump(bootstrap,out,sort_keys=True)
    # The actual image's installed cloud-init validator must accept this exact seed.
    code,out,err=remote('sudo -n cloud-init schema --system')
    with (root/'cloud-init-schema.private.log').open('xb') as log:log.write(out+err)
    if code:raise RuntimeError('NATIVE_CLOUD_INIT_SCHEMA_REJECTED')
    for name in ['runner.py','export.py','bootstrap.py']:
        actual=checked('sudo -n sha256sum /usr/local/lib/evo/'+name).decode().split()[0]
        if 'sha256:'+actual!=digest(resources/'guest'/name):raise RuntimeError('BOOTSTRAPPED_TOOL_PIN_MISMATCH')
    transport=dict(workerHost=setup['guestAddress'],knownHostsFile=str(root/'known_hosts'),hostKeyAlias=setup['guestAddress'],identityFile=setup['identityFile'])
    with (root/'controller-config.json').open('x',encoding='utf-8') as out:json.dump(transport,out,sort_keys=True)
    # Trusted platform unit suites execute as root but mock native spawning. No target build on host.
    stage='/home/evoctl/campaign-'+attempt;testroot='/root/campaign-'+attempt
    checked('mkdir -m 0700 '+stage)
    with client.open_sftp() as sftp:
        for name in ['test_runner.py','test_export.py','test_worker_seed.py']:
            sftp.put(str(Path(__file__).with_name(name)),stage+'/'+name)
        for name in ['runner.py','export.py','bootstrap.py','make_worker_seed.py']:
            sftp.put(str(resources/'guest'/name),stage+'/'+name)
        baseline=subprocess.run(['git','show','HEAD:analyzer-execution/src/main/resources/guest/runner.py'],stdout=subprocess.PIPE,stderr=subprocess.DEVNULL,check=True).stdout
        with sftp.open(stage+'/runner-baseline.py','wb') as out:out.write(baseline)
    checked('sudo -n install -d -m 0700 '+testroot)
    checked('sudo -n cp '+stage+'/*.py '+testroot+'/')
    environment='sudo -n env EVO_GUEST_RESOURCE_ROOT='+testroot+' EVO_GUEST_EXPORT_TEST_PATH='+testroot+'/export.py PYTHONPATH='+testroot+' '
    red=remote(environment+'EVO_GUEST_RUNNER_TEST_PATH='+testroot+'/runner-baseline.py /usr/bin/python3 -m unittest test_runner.RunnerValidationTest.test_home_replaced_by_symlink_cannot_retarget_root_ownership_change')
    if red[0]!=1 or b'64 != 0' not in red[2]:raise RuntimeError('ROOT_OWNERSHIP_RACE_RED_NOT_OBSERVED')
    green=remote(environment+'EVO_GUEST_RUNNER_TEST_PATH='+testroot+'/runner.py /usr/bin/python3 -m unittest discover -s '+testroot+" -p 'test_*.py'",timeout=120)
    with (root/'platform-guest-tests-private.log').open('xb') as out:out.write(red[1]+red[2]+green[1]+green[2])
    if green[0]:raise RuntimeError('GUEST_PLATFORM_TESTS_FAILED')
    results={}
    def native(name,source,expected=0,memory=268435456,tasks=32,verify=None):
        request=dict(attempt=attempt,work=work,argv=['/usr/bin/python3','-c',source],memoryBytes=memory,tasksMax=tasks,cpuPercent=50)
        code,out,err=remote('sudo -n /usr/bin/python3 /usr/local/lib/evo/runner.py',json.dumps(request),timeout=45)
        witness=verify() if verify is not None else True
        results[name]=dict(passed=code==expected and witness,exit=code,resourceWitness=witness,stdoutDigest='sha256:'+hashlib.sha256(out).hexdigest(),stderrDigest='sha256:'+hashlib.sha256(err).hexdigest())
        with (root/('control-'+name+'.json')).open('x',encoding='utf-8') as record:
            json.dump(dict(attempt=attempt,name=name,result=results[name],qualified=False),record,sort_keys=True)
        if not results[name]['passed']:raise RuntimeError('REGISTERED_CONTROL_FAILED:'+name)
        return code
    native('private-home-system-devices-and-old-work',"import os,pathlib; assert os.getuid()!=0; assert not os.access('/home/evoctl',os.R_OK); assert not os.access('/root',os.R_OK); assert not os.access('/etc/ssh',os.R_OK); assert not os.access('/var/lib/evo',os.R_OK); assert not os.path.exists('/dev/sr0'); assert not os.access('/work/6c3f72f1f2ef4561adfdab60f493549d',os.R_OK); assert not os.path.exists('/mnt/c'); assert not os.path.exists('/var/run/docker.sock')")
    native('cannot-modify-capture-agent',"from pathlib import Path\ntry: Path('/usr/local/lib/evo/runner.py').write_text('forged')\nexcept (PermissionError,OSError): pass\nelse: raise AssertionError('capture agent writable')")
    native('no-control-or-metadata-network',"import socket\nfor family,ip in [(socket.AF_INET,'127.0.0.1'),(socket.AF_INET,'172.30.84.1'),(socket.AF_INET,'169.254.169.254'),(socket.AF_INET6,'::1')]:\n s=socket.socket(family);s.settimeout(.4)\n try:s.connect((ip,22));raise AssertionError('control network reachable')\n except OSError:pass\n finally:s.close()")
    def oom_witness():
        code,out,err=remote('sudo -n journalctl -b -u evo-'+attempt+' --no-pager -n 40 -o json',maximum=131072)
        with (root/'memory-journal.private.log').open('xb') as log:log.write(out+err)
        if code:return False
        for line in out.splitlines():
            value=json.loads(line,object_pairs_hook=unique)
            if value.get('_PID')=='1' and value.get('_UID')=='0' and 'oom' in value.get('MESSAGE','').lower():return True
        return False
    # An unrestricted allocation must exit 0, so an assertion failure cannot masquerade as OOM.
    native('bounded-memory-allocation',"import os;a=bytearray(512*1024*1024);os._exit(0)",expected=1,memory=134217728,verify=oom_witness)
    native('bounded-process-count',"import os,time,errno\nchildren=[]\ntry:\n for _ in range(64):\n  child=os.fork()\n  if child==0:time.sleep(.2);os._exit(0)\n  children.append(child)\n raise AssertionError('process quota absent')\nexcept OSError as e:assert e.errno==errno.EAGAIN and len(children)<32\nfinally:\n for child in children:os.waitpid(child,0)")
    if args.native_maven:
        native_request=root/'native-request.json'
        with native_request.open('x',encoding='utf-8') as request_file:
            json.dump(dict(request=export_request(attempt)),request_file,sort_keys=True)
        command=maven_fixture_command(os.sys.executable,root,owner_path,native_request,setup.get('hostStorage'))
        result=subprocess.run(command,stdin=subprocess.DEVNULL,stdout=subprocess.PIPE,stderr=subprocess.PIPE,timeout=360)
        with (root/'native-control.private.log').open('xb') as out:out.write(result.stdout+result.stderr)
        if result.returncode:raise RuntimeError('REGISTERED_OFFLINE_MAVEN_FIXTURE_FAILED')
        observation=json.loads((root/'native-result.json').read_text(),object_pairs_hook=unique)
        expected={'model/target/generated-sources/evo/fixture/Generated.java':'88196b39f5d19646ce44935fb0bdb49228dea4cf049c8eaf9cd20c41857fbfe2',
                  'model/target/classes/fixture/Generated.class':'545cd424d601dab99ee0df94739b361bad5c85bd7cdb7dd1e2dc975c74834cd5',
                  'app/target/classes/fixture/Caller.class':'0af3ef25593c7473ad03c0aca468137bfcc95f2f9d5bbdd317a09bf5059752c5'}
        outputs=observation['outputs']
        if set(outputs)!=set(expected) or any(outputs[name]['digest']!='sha256:'+value for name,value in expected.items()):
            raise RuntimeError('REGISTERED_MAVEN_OUTPUT_ORACLE_MISMATCH')
        results['offline-maven-generated-reactor']=dict(passed=True,buildExit=observation['buildExit'],consumerExit=observation['consumerExit'],outputRoles=len(outputs))
        with (root/'control-offline-maven-generated-reactor.json').open('x',encoding='utf-8') as record:
            json.dump(dict(attempt=attempt,name='offline-maven-generated-reactor',result=results['offline-maven-generated-reactor'],qualified=False),record,sort_keys=True)
        native('bounded-work-filesystem',"import os,errno\nf=os.open('quota-probe.bin',os.O_WRONLY|os.O_CREAT|os.O_EXCL,0o600);written=0\ntry:\n for _ in range(2048):written+=os.write(f,b'\\0'*1048576)\n raise AssertionError('work filesystem limit absent')\nexcept OSError as e:assert e.errno==errno.ENOSPC and 67108864<written<1073741824\nfinally:os.close(f)")
    with (root/'conformance-observation.json').open('x',encoding='utf-8') as out:json.dump(dict(schema='authored-closing-conformance-observation:m8.4-v1',attempt=attempt,rootOwnershipRaceBaselineFailed=True,platformGuestTestsPassed=True,results=results,qualified=False),out,sort_keys=True)
    print(json.dumps(dict(attempt=attempt,platformGuestTestsPassed=True,controls=len(results),passed=sum(x['passed'] for x in results.values()),qualified=False)),flush=True)
    if args.native_log_bomb:
        request=dict(attempt=attempt,work=work,argv=['/usr/bin/python3','-c',"import os\nwhile True:os.write(1,b'x'*65536)"],memoryBytes=268435456,tasksMax=32,cpuPercent=50)
        stdin,out,err=client.exec_command('sudo -n /usr/bin/python3 /usr/local/lib/evo/runner.py',timeout=30)
        stdin.write(json.dumps(request));stdin.channel.shutdown_write()
        report=storage_module.capture_channel_log(out.channel,root/'native-log-bomb.log',65536,time.monotonic()+30,volume)
        if report['complete'] or report['reason']!='LOG_BUDGET_EXHAUSTED' or report['capturedBytes']!=65536:
            raise RuntimeError('NATIVE_LOG_BUDGET_NOT_ENFORCED')
        with (root/'control-bounded-native-log-producer.json').open('x',encoding='utf-8') as record:
            json.dump(dict(attempt=attempt,name='bounded-native-log-producer',result=dict(passed=True,capture=report),qualified=False),record,sort_keys=True)
        active=checked('sudo -n systemctl show evo-'+attempt+' -p ActiveState -p TasksCurrent').decode()
        if 'ActiveState=active' not in active:raise RuntimeError('NATIVE_LOG_PRODUCER_NOT_ACTIVE')
        with (root/'active-before-controller-loss.json').open('x',encoding='utf-8') as target:
            json.dump(dict(attempt=attempt,unit=active,mode='log-bomb-controller-loss'),target)
        # Keep the blocked target producer alive until the independent process observes controller death.
        os._exit(79)
    if args.stop_mode in {'loss','runtime'}:
        # Target descendants keep running after the authenticated controller channel disappears.
        request=dict(attempt=attempt,work=work,argv=['/usr/bin/python3','-c',"import os,time\npid=os.fork()\nwhile True:print('heartbeat',flush=True);time.sleep(1)"],memoryBytes=268435456,tasksMax=32,cpuPercent=50)
        stdin,out,err=client.exec_command('sudo -n /usr/bin/python3 /usr/local/lib/evo/runner.py',timeout=30)
        stdin.write(json.dumps(request));stdin.channel.shutdown_write();time.sleep(2)
        active=checked('sudo -n systemctl show evo-'+attempt+' -p ActiveState -p TasksCurrent').decode()
        if 'ActiveState=active' not in active:raise RuntimeError('SURVIVING_CHILD_NOT_ACTIVE')
        with (root/'active-before-controller-loss.json').open('x',encoding='utf-8') as target:json.dump(dict(attempt=attempt,unit=active,mode=args.stop_mode),target)
        if args.stop_mode=='loss':os._exit(79)
        client.close()
        while watcher.poll() is None:time.sleep(.2)
    else:
        client.close()
        with (root/'controller-protocol-complete.json').open('x',encoding='utf-8') as out:
            json.dump(dict(attempt=owner['attempt'],fence=owner['fence'],launch=owner['launch']),out,sort_keys=True);out.flush();os.fsync(out.fileno())
        watcher.wait(timeout=60)
    result=json.loads((root/'supervisor-result.json').read_text(),object_pairs_hook=unique)
    if result['state']['state']!='Off':raise RuntimeError('CAMPAIGN_VM_TERMINATION_NOT_CONFIRMED')
    print(json.dumps(dict(attempt=attempt,supervisorReason=result['reason'],state='Off',qualified=False)))


if __name__=='__main__':
    main()
