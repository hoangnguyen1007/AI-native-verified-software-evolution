#!/usr/bin/env python3
"""Registered fixed Step-4 native cohort; no general admission or semantic certificate.

Target code runs only in the owned guest through the protected runner. Existing
provisioning, supervisor and opaque export protocols retain their original scope.
"""
import argparse
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import time
import uuid

sys.dont_write_bytecode = True
REPO = Path(__file__).resolve().parents[4]
RESOURCES = REPO / 'analyzer-execution/src/main/resources'
FIXTURE = REPO / 'analyzer-execution/src/test/resources/native/java-evidence'
INDEX_SOURCE = REPO / 'analyzer-execution/src/main/java/com/evolution/analysis/execution/javac/CapturedJavacIndex.java'
INDEX_CONTROL = Path(__file__).with_name('native_index_control.py')
sys.path.insert(0, str(RESOURCES / 'mvp'))
from capture import export_request, unique
from launch import sequence, wait_bootstrap


def digest(path):
    with Path(path).open('rb') as stream:
        return 'sha256:' + hashlib.file_digest(stream, 'sha256').hexdigest()


def load_module(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def input_plan(material_root, indexer=False):
    material_root = Path(material_root).resolve()
    if (material_root.parent != (REPO / '.evo/executor').resolve()
            or not re.fullmatch('m844-native-materials-[a-f0-9]{8,32}', material_root.name)):
        raise ValueError('FIXTURE_MATERIAL_ROOT')
    recipe = load_module('fixed_native_recipe', FIXTURE / 'recipe.py')
    manifest = recipe.verify_materials(material_root)
    if {p.name for p in material_root.iterdir()} != {row['path'] for row in manifest['files']} | {'materials.json'}:
        raise ValueError('FIXTURE_MATERIAL_DENOMINATOR')
    paths = []
    for base, prefix in [(FIXTURE, 'source'), (material_root, 'materials')]:
        for path in sorted(base.rglob('*')):
            if path.is_symlink():
                raise ValueError('FIXTURE_INPUT_ALIAS')
            if not path.is_file():
                continue
            relative = path.relative_to(base).as_posix()
            if not re.fullmatch('[A-Za-z0-9_./-]+', relative) or '..' in Path(relative).parts:
                raise ValueError('FIXTURE_INPUT_PATH')
            if prefix == 'source' and path.suffix not in {'.java', '.proto', '.py'}:
                raise ValueError('FIXTURE_UNREGISTERED_SOURCE')
            paths.append((path, dict(path=prefix + '/' + relative, size=path.stat().st_size, digest=digest(path))))
    if type(indexer) is not bool:
        raise ValueError('FIXTURE_INDEXER_FLAG')
    if indexer:
        for path in [INDEX_SOURCE, INDEX_CONTROL]:
            if path.is_symlink() or not path.is_file():
                raise ValueError('FIXTURE_INDEX_TOOL_INPUT')
            paths.append((path, dict(path='tooling/' + path.name, size=path.stat().st_size, digest=digest(path))))
    return sorted(paths, key=lambda item: item[1]['path'])


def preflight(root):
    setup = json.loads((root / 'setup.json').read_bytes(), object_pairs_hook=unique)
    attempt = str(uuid.UUID(setup['attempt']))
    if Path(setup['root']).resolve() != root or setup['workspace'] != str(REPO):
        raise ValueError('FIXTURE_REGISTRATION_SCOPE')
    registration = json.loads((Path.home() / '.evo/core-mvp/registrations' / (attempt + '.json')).read_bytes(), object_pairs_hook=unique)
    if registration['attempt'] != attempt or Path(registration['root']).resolve() != root:
        raise ValueError('FIXTURE_REGISTRATION_SCOPE')
    names = {'setup.json', 'provision-request.json', 'profile.json', 'controller-config.json', 'known_hosts'}
    if set(registration['files']) != names or any(digest(root / name) != registration['files'][name] for name in names):
        raise ValueError('FIXTURE_REGISTRATION_CHANGED')
    profile = json.loads((root / 'profile.json').read_bytes(), object_pairs_hook=unique)
    spec = profile['nativeFixture']
    plan = input_plan(spec['materials'], spec.get('indexer', False))
    spring = any(row['path'] == 'materials/spring-context.jar' for _, row in plan)
    if spec.get('spring', False) is not spring:
        raise ValueError('FIXTURE_SPRING_VARIANT_CHANGED')
    if (spec['schema'] != 'm844-authored-native-fixture:v1' or spec['controller'] != digest(__file__)
            or spec['inputs'] != [row for _, row in plan] or profile['qualified'] is not False):
        raise ValueError('FIXTURE_INPUTS_CHANGED')
    for executable in ['python', 'powershell']:
        if digest(setup[executable]) != setup[executable + 'Pin']:
            raise ValueError('FIXTURE_EXECUTABLE_CHANGED')
    for name, pin in profile['enforcement'].items():
        if digest(RESOURCES / name) != pin:
            raise ValueError('FIXTURE_ENFORCEMENT_CHANGED')
    return setup, plan


def main():
    import paramiko
    parser = argparse.ArgumentParser()
    parser.add_argument('--root', type=Path, required=True)
    args = parser.parse_args()
    root = args.root.resolve()
    setup, plan = preflight(root)
    attempt = str(uuid.UUID(setup['attempt']))
    work_attempt = uuid.UUID(attempt).hex
    work = '/work/' + work_attempt
    owner_path = root / ('owner-' + work_attempt + '.json')
    powershell = Path(setup['powershell'])
    supervisor = load_module('native_fixture_supervisor', RESOURCES / 'hyperv/supervisor.py')
    storage = load_module('native_fixture_storage', RESOURCES / 'hyperv/host_storage.py')
    host_storage = setup['hostStorage']
    receipt_path = Path(host_storage['receipt'])
    if digest(receipt_path) != host_storage['receiptDigest']:
        raise ValueError('FIXTURE_STORAGE_CHANGED')
    volume = storage.PinnedVolume(json.loads(receipt_path.read_bytes(), object_pairs_hook=unique), str(REPO), host_storage['owner'])
    volume.contained(root)
    watcher = None
    build_exit = None
    request_path = root / 'export-request.json'
    with request_path.open('x', encoding='utf-8') as stream:
        json.dump(dict(request=export_request(work_attempt), collectorPin=digest(RESOURCES / 'guest/export.py')), stream, sort_keys=True)

    def checked(argv, timeout=360):
        result = subprocess.run([str(a) for a in argv], stdin=subprocess.DEVNULL, stdout=subprocess.PIPE,
                                stderr=subprocess.PIPE, timeout=timeout)
        if len(result.stdout) + len(result.stderr) > 1048576:
            raise RuntimeError('FIXTURE_CONTROL_RESPONSE_CAPACITY')
        if result.returncode:
            with (root / ('control-' + uuid.uuid4().hex + '.private.log')).open('xb') as stream:
                stream.write(result.stdout + result.stderr)
            raise RuntimeError('FIXTURE_CONTROL_FAILED')
        return result.stdout

    provision_request = root / 'provision-request.json'

    def provision(action):
        return checked([powershell, '-NoProfile', '-NonInteractive', '-File', RESOURCES / 'hyperv/provision.ps1',
                        '-Action', action, '-Request', provision_request, '-RequestPin', digest(provision_request)])

    def supervise():
        nonlocal watcher
        config = dict(schema='hyperv-supervisor-config:m8.4-v1', root=str(root), owner=str(owner_path),
                      powershell=str(powershell), powershellPin=digest(powershell),
                      driver=str(RESOURCES / 'hyperv/worker.ps1'), driverPin=digest(RESOURCES / 'hyperv/worker.ps1'),
                      controlPid=os.getpid(), runtimeMillis=900000, overlayBytes=2147483648,
                      logBytes=16777216, logs=[str(root / 'native-cohort.log'), str(root / 'index-tool-compile.log')]
                      + [str(root / ('index-' + r['name'] + '.log')) for r in load_module('index_contexts', INDEX_CONTROL).contexts(work, '/opt/evo-inputs/' + work_attempt,
                          spring=json.loads((root / 'profile.json').read_bytes(), object_pairs_hook=unique)['nativeFixture'].get('spring', False))], pollMillis=100)
        with (root / 'supervisor-config.json').open('x') as stream:
            json.dump(config, stream, sort_keys=True)
        with (root / 'supervisor-private.log').open('xb') as errors:
            watcher = subprocess.Popen([sys.executable, '-I', '-c', (RESOURCES / 'hyperv/supervisor.py').read_text(),
                                        '--config', str(root / 'supervisor-config.json')], stdin=subprocess.DEVNULL,
                                       stdout=subprocess.DEVNULL, stderr=errors, close_fds=True,
                                       creationflags=subprocess.CREATE_NO_WINDOW | subprocess.CREATE_NEW_PROCESS_GROUP)
        deadline = time.monotonic() + 45
        while not (root / 'supervisor-ready.json').exists():
            if watcher.poll() is not None or time.monotonic() >= deadline:
                raise RuntimeError('FIXTURE_SUPERVISOR_NOT_READY')
            time.sleep(0.1)

    def start():
        provision('Start')
        wait_bootstrap(root, setup, work_attempt)

    def build():
        nonlocal build_exit
        client = paramiko.SSHClient()
        client.load_host_keys(str(root / 'known_hosts'))
        try:
            client.connect(setup['guestAddress'], username='evoctl', key_filename=setup['identityFile'],
                           allow_agent=False, look_for_keys=False, timeout=5, banner_timeout=5, auth_timeout=5)

            def remote(command, data=None, log=None, timeout=60):
                stdin, out, err = client.exec_command(command, timeout=timeout)
                if data is not None:
                    stdin.write(data)
                stdin.channel.shutdown_write()
                channel = out.channel
                if log is not None:
                    result = storage.capture_channel_log(channel, log, 16777216, time.monotonic() + timeout, volume)
                    if not result['complete']:
                        raise RuntimeError('FIXTURE_NATIVE_LOG_INCOMPLETE')
                    return channel.recv_exit_status(), b''
                collected = bytearray()
                end = time.monotonic() + timeout
                while not channel.exit_status_ready() or channel.recv_ready() or channel.recv_stderr_ready():
                    if time.monotonic() >= end:
                        channel.close()
                        raise RuntimeError('FIXTURE_REMOTE_DEADLINE')
                    if channel.recv_ready():
                        collected.extend(channel.recv(65536))
                    if channel.recv_stderr_ready():
                        collected.extend(channel.recv_stderr(65536))
                    if len(collected) > 65536:
                        channel.close()
                        raise RuntimeError('FIXTURE_REMOTE_CAPACITY')
                    time.sleep(0.01)
                return channel.recv_exit_status(), bytes(collected)

            def control(command):
                code, output = remote(command)
                if code:
                    raise RuntimeError('FIXTURE_GUEST_CONTROL_FAILED')
                return output

            marker = json.loads(control('sudo -n cat /var/lib/evo/bootstrap-complete.json'), object_pairs_hook=unique)
            if marker['attempt'] != work_attempt or marker['work'] != work or marker['workBytes'] != 1073741824:
                raise ValueError('FIXTURE_BOOTSTRAP_SCOPE')
            for name in ['runner.py', 'export.py', 'bootstrap.py']:
                if control('sudo -n sha256sum /usr/local/lib/evo/' + name).decode().split()[0] != digest(RESOURCES / 'guest' / name)[7:]:
                    raise ValueError('FIXTURE_GUEST_TOOL_CHANGED')
            staging = '/home/evoctl/m844-input-' + work_attempt
            control('mkdir -m 700 ' + staging)
            with client.open_sftp() as sftp:
                made = {staging}
                for source, row in plan:
                    current = staging
                    for part in Path(row['path']).parts[:-1]:
                        current += '/' + part
                        if current not in made:
                            sftp.mkdir(current)
                            made.add(current)
                    with sftp.open(staging + '/' + row['path'], 'wx') as output, source.open('rb') as stream:
                        while chunk := stream.read(65536):
                            output.write(chunk)
            sealed = '/opt/evo-inputs/' + work_attempt
            control('sudo -n test ! -L /opt/evo-inputs && sudo -n install -d -m 755 /opt/evo-inputs && sudo -n test ! -e ' + sealed + ' && sudo -n mv -T ' + staging + ' ' + sealed)
            control('sudo -n chown -R root:root ' + sealed)
            control('sudo -n find ' + sealed + ' -type d -exec chmod 555 {} +')
            control('sudo -n find ' + sealed + ' -type f -exec chmod 444 {} +')
            control('sudo -n chmod 555 ' + sealed + '/materials/protoc ' + sealed + '/materials/protoc-gen-grpc-java')
            for _, row in plan:
                if control('sudo -n sha256sum ' + sealed + '/' + row['path']).decode().split()[0] != row['digest'][7:]:
                    raise ValueError('FIXTURE_STAGED_INPUT_CHANGED')
            indexed = json.loads((root / 'profile.json').read_bytes(), object_pairs_hook=unique)['nativeFixture'].get('indexer', False)

            def run_native(argv, log, timeout=120):
                request = dict(attempt=work_attempt, work=work, argv=argv, memoryBytes=1073741824, tasksMax=128, cpuPercent=100)
                return remote('sudo -n /usr/bin/python3 /usr/local/lib/evo/runner.py', json.dumps(request), root / log, timeout=timeout)[0]

            def index_control(action, data=None):
                code, output = remote('sudo -n /usr/bin/python3 ' + sealed + '/tooling/native_index_control.py '
                                      + action + ' ' + work + ' ' + sealed, data)
                if code:
                    raise RuntimeError('FIXTURE_INDEX_CONTROL_' + action.upper().replace('-', '_'))
                return output

            if indexed:
                control('sudo -n install -d -o evobuild -g evobuild -m 700 ' + work + '/index-tool-classes')
                compile_exit = run_native(['/usr/bin/javac', '-J-Xmx256m', '--release', '21', '-encoding', 'UTF-8', '-proc:none',
                        '-classpath', work + '/index-tool-classes', '-sourcepath', work + '/index-tool-classes',
                        '-d', work + '/index-tool-classes', sealed + '/tooling/CapturedJavacIndex.java'], 'index-tool-compile.log')
                if compile_exit:
                    raise RuntimeError('FIXTURE_INDEX_TOOL_COMPILE_FAILED')
                index_control('seal-tool')
            native = dict(attempt=work_attempt, work=work, argv=['/usr/bin/python3', sealed + '/source/recipe.py', sealed, work],
                          memoryBytes=1073741824, tasksMax=128, cpuPercent=100)
            with (root / 'native-request.json').open('x') as stream:
                json.dump(native, stream, sort_keys=True)
            build_exit, _ = remote('sudo -n /usr/bin/python3 /usr/local/lib/evo/runner.py', json.dumps(native), root / 'native-cohort.log', timeout=600)
            with (root / 'native-exit.json').open('x') as stream:
                json.dump(dict(attempt=work_attempt, exit=build_exit, qualified=False, semanticTrust='PRODUCER_ASSERTION'), stream, sort_keys=True)
            if indexed and build_exit == 0:
                index_control('prepare-index')
                exits = {}
                for row in load_module('index_contexts_run', INDEX_CONTROL).contexts(work, sealed,
                        spring=json.loads((root / 'profile.json').read_bytes(), object_pairs_hook=unique)['nativeFixture'].get('spring', False)):
                    argv = ['/usr/bin/java', '-Xmx640m', '-cp', '/opt/evo-index-tools/' + work_attempt + '/classes',
                            'com.evolution.analysis.execution.javac.CapturedJavacIndex', work, row['output'],
                            ':'.join(row['classpath']), '1000000'] + row['sources']
                    exits[row['name']] = run_native(argv, 'index-' + row['name'] + '.log')
                index_control('finish', json.dumps(exits))
        finally:
            client.close()

    def capture():
        checked([sys.executable, RESOURCES / 'mvp/capture.py', '--root', root / 'capture', '--config', root / 'controller-config.json',
                 '--owner', owner_path, '--work-attempt', work_attempt, '--request', request_path])

    def stop():
        if owner_path.exists():
            owner = json.loads(owner_path.read_bytes(), object_pairs_hook=unique)
            command = [powershell, '-NoProfile', '-NonInteractive', '-File', RESOURCES / 'hyperv/worker.ps1', '-Action', 'Stop',
                       '-Workspace', root, '-Attempt', attempt, '-LaunchDigest', owner['launch'], '-Fence', str(owner['fence'])]
            off = json.loads(checked(command, 45), object_pairs_hook=unique)
            if off['state'] != 'Off' or off['vmId'] != owner['vmId']:
                raise RuntimeError('FIXTURE_TERMINATION_UNCONFIRMED')
            if watcher is not None:
                with (root / 'controller-protocol-complete.json').open('x') as stream:
                    json.dump(dict(attempt=attempt, fence=owner['fence'], launch=owner['launch']), stream, sort_keys=True)
                    stream.flush()
                    os.fsync(stream.fileno())
                if watcher.wait(timeout=45) != 0:
                    raise RuntimeError('FIXTURE_SUPERVISOR_FAILED')

    sequence(lambda: provision('Prepare'), supervise, start, build, capture, stop)
    print(json.dumps(dict(schema='m844-authored-native-fixture:v1', attempt=work_attempt,
                          buildExit=build_exit, captured=True, state='Off', qualified=False, semanticTrust='PRODUCER_ASSERTION')))
    return 0 if build_exit == 0 else 1


if __name__ == '__main__':
    try:
        sys.exit(main())
    except Exception as error:
        print(json.dumps(dict(schema='m844-authored-native-fixture-failure:v1', failureType=type(error).__name__, qualified=False)), file=sys.stderr)
        sys.exit(1)
