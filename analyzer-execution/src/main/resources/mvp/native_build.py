#!/usr/bin/env python3
"""Human-approved Core MVP offline Maven recipe, in an owned Hyper-V worker.
Fresh workspace; existing captured Maven cache only; -o forbids artifact fetches.
"""
import argparse
import hashlib
import json
import importlib.util
from pathlib import Path
import subprocess
import time
import uuid
from capture import unique, export_request


def parse_arguments(argv=None):
    parser = argparse.ArgumentParser()
    parser.add_argument('--root', type=Path, required=True)
    parser.add_argument('--config', type=Path, required=True)
    parser.add_argument('--request', type=Path, required=True)
    parser.add_argument('--owner', type=Path, required=True)
    parser.add_argument('--preallocated', action='store_true', help='Require the exact first-boot bounded work volume')
    parser.add_argument('--host-storage-receipt',type=Path)
    parser.add_argument('--storage-owner')
    return parser.parse_args(argv)


def main():
    import paramiko
    args = parse_arguments()
    config = json.loads(args.config.read_text(encoding='utf-8-sig'), object_pairs_hook=unique)
    initial = json.loads(args.request.read_text(encoding='utf-8-sig'), object_pairs_hook=unique)
    request = export_request(initial['request']['attempt'], initial['request'])
    attempt, work = request['attempt'], request['work']
    owner = json.loads(args.owner.read_text(encoding='utf-8-sig'), object_pairs_hook=unique)
    if uuid.UUID(owner['attempt']).hex != attempt or owner['fence'] != 1:
        raise ValueError('AUTHORED_OWNER_SCOPE_MISMATCH')
    resources = Path(__file__).resolve().parents[1]
    repo = Path.cwd().resolve()
    spec=importlib.util.spec_from_file_location('native_host_storage',resources/'hyperv/host_storage.py')
    storage=importlib.util.module_from_spec(spec);spec.loader.exec_module(storage)
    volume=None
    if bool(args.host_storage_receipt)!=bool(args.storage_owner):raise ValueError('HOST_STORAGE_BINDING_REQUIRED')
    if args.host_storage_receipt:
        with args.host_storage_receipt.open('rb') as source:raw=source.read(16385)
        if len(raw)>16384:raise ValueError('HOST_STORAGE_RECEIPT_WORKER_REQUIRED')
        volume=storage.PinnedVolume(json.loads(raw,object_pairs_hook=unique),str(repo),args.storage_owner)
        volume.contained(args.root)
    client = paramiko.SSHClient()
    keys = paramiko.HostKeys(config['knownHostsFile'])
    for kind, key in keys[config['hostKeyAlias']].items():
        client.get_host_keys().add(config['workerHost'], kind, key)

    def remote(command, data=None, deadline=45, maximum=1048576,log_file=None):
        stdin, out, err = client.exec_command(command, timeout=deadline)
        if data is not None:
            stdin.write(data)
        stdin.channel.shutdown_write()
        channel = out.channel
        if log_file is not None:
            report=storage.capture_channel_log(channel,log_file,maximum,time.monotonic()+deadline,volume)
            with (args.root/'native-log-capture.json').open('x',encoding='utf-8') as record:json.dump(report,record,sort_keys=True)
            if not report['complete']:
                channel.close()
                raise RuntimeError(report['reason'])
            return channel.recv_exit_status(),b'',b''
        stdout, stderr = bytearray(), bytearray()
        end = time.monotonic() + deadline
        while not channel.exit_status_ready() or channel.recv_ready() or channel.recv_stderr_ready():
            if time.monotonic() > end:
                channel.close()
                raise RuntimeError('AUTHORED_NATIVE_WATCHDOG_EXPIRED')
            if channel.recv_ready():
                stdout.extend(channel.recv(65536))
            if channel.recv_stderr_ready():
                stderr.extend(channel.recv_stderr(65536))
            if len(stdout) + len(stderr) > maximum:
                channel.close()
                raise RuntimeError('AUTHORED_NATIVE_LOG_WORKER_REQUIRED')
            time.sleep(0.01)
        return channel.recv_exit_status(), bytes(stdout), bytes(stderr)

    def checked(command, data=None):
        code, out, _ = remote(command, data)
        if code:
            raise RuntimeError('AUTHORED_CONTROL_OPERATION_FAILED')
        return out

    successful = False
    try:
        deadline = time.monotonic() + 90
        while True:
            try:
                client.connect(config['workerHost'], username='evoctl', key_filename=config['identityFile'],
                               allow_agent=False, look_for_keys=False, timeout=3, banner_timeout=3, auth_timeout=3)
                break
            except (paramiko.BadHostKeyException, paramiko.AuthenticationException):
                raise RuntimeError('PINNED_MANAGEMENT_AUTHENTICATION_FAILED') from None
            except (OSError, paramiko.SSHException):
                client.close()
                if time.monotonic() >= deadline:
                    raise RuntimeError('PINNED_MANAGEMENT_UNAVAILABLE') from None
                time.sleep(2)
        if args.preallocated:
            bootstrap=json.loads(checked('sudo -n cat /var/lib/evo/bootstrap-complete.json'),object_pairs_hook=unique)
            if bootstrap['attempt'] != attempt or bootstrap['work'] != work or bootstrap['workBytes'] != 1073741824:
                raise ValueError('PREALLOCATED_WORK_SCOPE_MISMATCH')
            checked('sudo -n mountpoint -q ' + work)
            checked('sudo -n chown evoctl:evoctl ' + work)
            checked('sudo -n chmod 0750 ' + work)
        else:
            checked('sudo -n test ! -e ' + work)
            checked('sudo -n install -d -m 0750 -o evoctl -g evoctl ' + work)
        fixture = resources / 'mvp/native/maven-generated'
        inputs = {}
        with client.open_sftp() as sftp:
            directories = {work}
            for source in sorted(fixture.rglob('*')):
                if source.is_symlink():
                    raise ValueError('AUTHORED_FIXTURE_LINK_UNEXPECTED')
                if not source.is_file():
                    continue
                relative = source.relative_to(fixture).as_posix()
                current = work
                for part in Path(relative).parts[:-1]:
                    current += '/' + part
                    if current not in directories:
                        sftp.mkdir(current)
                        directories.add(current)
                raw = source.read_bytes()
                with sftp.open(work + '/' + relative, 'wx') as target:
                    target.write(raw)
                inputs[relative] = hashlib.sha256(raw).hexdigest()
            settings = b'<settings><mirrors><mirror><id>captured-central</id><mirrorOf>*</mirrorOf><url>http://127.0.0.1:48184/central/</url></mirror></mirrors></settings>'
            with sftp.open(work + '/settings.xml', 'wx') as target:
                target.write(settings)
            inputs['settings.xml'] = hashlib.sha256(settings).hexdigest()
        # The source is a previously censused authored Maven cache in this independent image.
        cache = '/work/6c3f72f1f2ef4561adfdab60f493549d/m2'
        checked('sudo -n test -d ' + cache)
        checked('sudo -n test ! -L ' + cache)
        checked('sudo -n cp -a -- ' + cache + ' ' + work + '/m2')
        checked('sudo -n chown -R evobuild:evobuild ' + work)
        runner = resources / 'guest/runner.py'
        runner_pin = hashlib.sha256(runner.read_bytes()).hexdigest()
        before = checked('sudo -n sha256sum /usr/local/lib/evo/runner.py').decode().split()[0]
        if before != runner_pin:
            raise ValueError('PROTECTED_RUNNER_PIN_MISMATCH')
        native = dict(attempt=attempt, work=work, argv=['/usr/bin/mvn', '--offline', '--batch-mode', '--no-transfer-progress', '-s', work + '/settings.xml', '-Dmaven.repo.local=' + work + '/m2', 'compile'], memoryBytes=1073741824, tasksMax=128, cpuPercent=100)
        code, _, _ = remote('sudo -n /usr/bin/python3 /usr/local/lib/evo/runner.py', json.dumps(native), deadline=300,
                            maximum=16777216,log_file=args.root/'native-maven.log')
        if code:
            raise RuntimeError('AUTHORED_OFFLINE_MAVEN_FAILED')
        consumer = dict(native, argv=['/usr/bin/java', '-cp', 'app/target/classes:model/target/classes', 'fixture.Caller'])
        consumer_code, _, _ = remote('sudo -n /usr/bin/python3 /usr/local/lib/evo/runner.py', json.dumps(consumer), deadline=45)
        if consumer_code:
            raise RuntimeError('AUTHORED_GENERATED_CONSUMER_FAILED')
        after = checked('sudo -n sha256sum /usr/local/lib/evo/runner.py').decode().split()[0]
        if before != after:
            raise ValueError('PROTECTED_RUNNER_CHANGED')
        outputs = {}
        for relative in ['model/target/generated-sources/evo/fixture/Generated.java', 'model/target/classes/fixture/Generated.class', 'app/target/classes/fixture/Caller.class']:
            raw = checked('sudo -n cat -- ' + work + '/' + relative)
            outputs[relative] = dict(digest='sha256:' + hashlib.sha256(raw).hexdigest(), size=len(raw))
        for relative, expected in inputs.items():
            actual = checked('sudo -n sha256sum -- ' + work + '/' + relative).decode().split()[0]
            if actual != expected:
                raise ValueError('AUTHORED_INPUT_CHANGED')
        result = dict(schema='authored-offline-maven-publication-run:m8.4-v1', attempt=attempt, argv=native['argv'], buildExit=code, consumerExit=consumer_code, inputs=inputs, outputs=outputs, runnerPin='sha256:' + runner_pin, cacheOrigin=cache, qualified=False, semanticTrust='PRODUCER_ASSERTION')
        with (args.root / 'native-result.json').open('x', encoding='utf-8') as target:
            json.dump(result, target, sort_keys=True)
        successful = True  # Immediate handoff to the export helper, which always stops this worker.
        print(json.dumps(dict(attempt=attempt, buildExit=code, consumerExit=consumer_code, outputs=len(outputs), qualified=False)))
    except Exception as failure:
        with (args.root / ('native-failure-' + uuid.uuid4().hex + '.json')).open('x', encoding='utf-8') as target:
            json.dump(dict(failureType=type(failure).__name__, qualified=False), target)
        raise
    finally:
        client.close()
        if not successful:
            script = resources / 'hyperv/worker.ps1'
            stopped = subprocess.run(['pwsh', '-NoProfile', '-File', str(script), '-Action', 'Stop', '-Workspace', owner['workspace'], '-Attempt', owner['attempt'], '-LaunchDigest', owner['launch'], '-Fence', str(owner['fence'])], stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, timeout=45, check=True)
            if len(stopped.stdout) > 8192:
                raise ValueError('TERMINATION_RESPONSE_WORKER_REQUIRED')
            off = json.loads(stopped.stdout, object_pairs_hook=unique)
            if (off['state'], off['vmId'], off['attempt'], off['launch'], off['fence']) != ('Off', owner['vmId'], owner['attempt'], owner['launch'], owner['fence']):
                raise ValueError('AUTHORITATIVE_FAILURE_TERMINATION_UNCONFIRMED')
            with (args.root / ('native-failure-off-' + uuid.uuid4().hex + '.json')).open('xb') as target:
                target.write(stopped.stdout)


if __name__ == '__main__':
    main()
