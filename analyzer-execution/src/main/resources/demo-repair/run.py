"""Run only the sealed demo fixture, in a networkless QEMU guest.

Docker packages and constrains the emulator. Only the VM compiles or runs the fixture.
"""
import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess


PROPOSAL = 'architecture.repair-proposal:demo-v1'
VERIFICATION = 'architecture.repair-verification:demo-v1'
CLIENT = 'src/main/java/app/Client.java'
BEFORE = 'sha256:10026b4edd69da18adc2587c77db51b149a6ac597d1758681736a291e6ce3eaa'
AFTER = 'sha256:011ce2d22e7bc8e4359940a80366be5d3df1021c0e20a96563333db200cf1073'
POLICIES = {'sha256:8f301531495bb8b68637768aac4e5d4e6883aca2cd919001f2849436268a3a58',
            'sha256:2fe739e3cb106a0f95aeaf536d2a277571189ade7c7ba7421e3a7668a91a7c03'}
FILES = {'build.gradle': b'plugins { java }',
         'src/main/java/app/App.java': b'package app; @org.springframework.context.annotation.ComponentScan("app") class App {}',
         'src/main/java/app/Store.java': b'package app; @org.springframework.stereotype.Component public class Store { public Store(){} }'}
JARS = {'spring-aop': 'fe84f18bdefb3ad4f993357d31e61613bb55f348d7ea69ec1ca968b3240b3b08',
        'spring-beans': '33adf77b49236f2966c9cec7eb5a1aea8ade81d7ada80c36d91bbf89d3298e15',
        'spring-context': 'a0d2de9df7902edc5f6ae55e2936327bdeac177dbc5392906b8d36f3691fe121',
        'spring-core': '3b82629da38717dd616a70ec1055f9a8e208d58132071a6314a32159f8bce162',
        'spring-expression': '37cfc1fe8ca22bb789c20aac6b90e09f2b0ea73085f3271ae61cf12cdb7e8876',
        'spring-jcl': '2b59288f030ce77a89ec88aa426f32eb9a309056f3646dd5157f9f31f0204fd7'}
CASES = ('baseline', 'candidate', 'control')
CHECKS = [tree + '.' + check for tree in CASES for check in ('build', 'dev-off', 'dev-on')]
MEMORY = 1610612736
PIDS = 64
CPUS = 1000000000
DOCKER = ['docker', '--context', 'desktop-linux']


def digest(data):
    return 'sha256:' + hashlib.sha256(data).hexdigest()


def canonical(value):
    return json.dumps(value, ensure_ascii=False, separators=(',', ':'), sort_keys=True).encode('utf-8')


def identity(kind, components):
    return kind + ':' + digest(canonical(dict(kind=kind, components=components, version=1)))


def tree(files):
    inventory = [dict(path=path, contentDigest=digest(data)) for path, data in sorted(files.items())]
    repo = identity('repository', ['https://example.test/m2-fixture.git'])
    return dict(snapshot=identity('snapshot', [repo, digest(canonical(inventory))]), files=inventory)


def require(ok, message):
    if not ok:
        raise ValueError(message)


def prepare(proposal_file, cache, context):
    with proposal_file.open('rb') as stream:
        raw = stream.read(65537)
    require(len(raw) <= 65536, 'PROPOSAL_LIMIT')
    outcome = json.loads(raw)
    proposal = outcome['proposal']
    candidate = proposal['candidate']
    require(outcome['version'] == PROPOSAL and outcome['status'] == 'PROPOSED'
            and proposal['identity'] == digest(canonical([PROPOSAL, candidate])), 'PROPOSAL_MISMATCH')
    require(candidate['policySource'] in POLICIES and candidate['affectedScope']['rule'] == {'id': 'client.no-store', 'version': '2'}
            and candidate['edit'] == {'path': CLIENT, 'before': BEFORE, 'after': AFTER}, 'UNREGISTERED_PROPOSAL')
    patch = candidate['patch']
    require(digest(patch.encode()) == candidate['patchDigest'], 'PATCH_MISMATCH')
    lines = patch.splitlines()
    require(len(lines) == 8 and lines[0] == 'diff --git a/'+CLIENT+' b/'+CLIENT
            and lines[1:4] == ['--- a/'+CLIENT, '+++ b/'+CLIENT, '@@ -1 +1 @@']
            and lines[4].startswith('-') and lines[6].startswith('+')
            and lines[5] == lines[7] == r'\ No newline at end of file', 'PATCH_FORMAT')
    before, after = lines[4][1:].encode(), lines[6][1:].encode()
    require(digest(before) == BEFORE and digest(after) == AFTER
            and after == before.replace(b'Client(Store store)', b'Client()'), 'SOURCE_MISMATCH')
    sources = {name: FILES | {CLIENT: data} for name, data in zip(CASES, (before, after, before.replace(b'{} }', b'{ /* retained injection */ } }')))}
    plan = dict(proposal=proposal['identity'], patch=candidate['patchDigest'], policy=candidate['policySource'],
                affectedRegion=candidate['affectedScope']['region'], requiredRuntimeChecks=CHECKS,
                **{name: tree(files) for name, files in sources.items()})
    require(plan['baseline']['snapshot'] == candidate['baseline']['snapshot'], 'BASELINE_SNAPSHOT_MISMATCH')
    resources = Path(__file__).parent
    inputs = {name+'/'+path: data for name, files in sources.items() for path, data in files.items()}
    inputs['ProfileCheck.java'] = (resources/'ProfileCheck.java').read_text(encoding='utf-8').encode()
    for name, expected in JARS.items():
        file = name+'-6.2.0.jar'
        bytes_ = (cache/'org'/'springframework'/name/'6.2.0'/file).read_bytes()
        require(digest(bytes_) == 'sha256:'+expected, 'SPRING_JAR_CHANGED: '+name)
        inputs['lib/'+file] = bytes_
    controls = {}
    for name in ('Dockerfile', 'guest-init.sh'):
        data = (resources/name).read_text(encoding='utf-8').encode()
        (context/name).write_bytes(data)
        controls[name] = digest(data)
    for name in ('run.py', 'ProfileCheck.java'):
        controls[name] = digest((resources/name).read_text(encoding='utf-8').encode())
    for name, data in inputs.items():
        file = context/'inputs'/name
        file.parent.mkdir(parents=True, exist_ok=True)
        file.write_bytes(data)
    manifest = ''.join(hashlib.sha256(data).hexdigest()+'  '+name+'\n' for name, data in sorted(inputs.items())).encode()
    (context/'inputs'/'manifest.sha256').write_bytes(manifest)
    return plan, digest(manifest), controls


def docker(*args, timeout=30):
    return subprocess.check_output([*DOCKER, *args], text=True, encoding='utf-8', errors='replace', timeout=timeout).strip()


def run(image):
    container = docker('create', '--pull=never', '--network=none', '--read-only', '--cap-drop=ALL',
                       '--security-opt=no-new-privileges', '--memory='+str(MEMORY), '--memory-swap='+str(MEMORY),
                       '--cpus=1', '--pids-limit='+str(PIDS), '--log-driver=local', '--log-opt=max-size=64k',
                       '--log-opt=max-file=1', '--log-opt=compress=false', image)
    require(re.fullmatch('[a-f0-9]{64}', container), 'CONTAINER_ID_UNAVAILABLE')
    try:
        config = json.loads(docker('inspect', container))[0]
        host = config['HostConfig']
        require(config['Image'] == image and not config['Mounts'] and host['NetworkMode'] == 'none'
                and host['ReadonlyRootfs'] and not host['Privileged'] and host['CapDrop'] == ['ALL']
                and host['SecurityOpt'] == ['no-new-privileges'] and host['Memory'] == host['MemorySwap'] == MEMORY
                and host['PidsLimit'] == PIDS and host['NanoCpus'] == CPUS
                and host['LogConfig'] == {'Type':'local','Config':{'max-file':'1','max-size':'64k','compress':'false'}}, 'WORKER_POLICY_MISMATCH')
        docker('start', container)
        exit_code = docker('wait', container, timeout=250)
        state = json.loads(docker('inspect', '--format', '{{json .State}}', container))
        log = docker('logs', container)
        require(exit_code == '0' and state['Status'] == 'exited' and not state['OOMKilled'], 'GUEST_EXIT_FAILED')
        return container, log
    finally:
        docker('rm', '--force', container)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('proposal', type=Path)
    parser.add_argument('output', type=Path)
    parser.add_argument('--base', required=True, help='Pinned official jdk-alpine image digest')
    parser.add_argument('--maven-cache', type=Path, default=Path.home()/'.m2/repository')
    args = parser.parse_args()
    require(re.fullmatch(r'(docker.io/library/)?eclipse-temurin@sha256:[a-f0-9]{64}', args.base), 'UNPINNED_BASE_IMAGE')
    endpoint = json.loads(docker('context', 'inspect', 'desktop-linux'))[0]['Endpoints']['docker']['Host']
    require(endpoint == 'npipe:////./pipe/dockerDesktopLinuxEngine', 'LOCAL_DOCKER_DESKTOP_REQUIRED')
    args.output.mkdir()
    context = args.output/'context'
    context.mkdir()
    plan, input_digest, controls = prepare(args.proposal, args.maven_cache, context)
    subprocess.run([*DOCKER,'build','--platform=linux/amd64','--build-arg','BASE='+args.base,'--iidfile',
                    str(args.output/'image-id'),str(context)],check=True,timeout=600)
    image = (args.output/'image-id').read_text().strip()
    require(re.fullmatch(r'sha256:[a-f0-9]{64}',image), 'IMAGE_ID_UNAVAILABLE')
    container, log = run(image)
    (args.output/'serial.txt').write_text(log, encoding='utf-8')
    actual = [line for line in log.splitlines() if line.startswith('EVO_')]
    expected = ['EVO_VM_INPUTS '+input_digest[7:], 'EVO_VM_ISOLATION network=loopback-only disks=none']
    for name in CASES:
        expected.extend(['EVO_CHECK '+name+'.build', 'EVO_CHECK '+name+'.dev-off',
                         'EVO_DEPENDENCY '+name+' '+('false' if name=='candidate' else 'true'), 'EVO_CHECK '+name+'.dev-on'])
    require(actual == expected + ['EVO_VM_COMPLETE'], 'GUEST_CHECKS_INCOMPLETE')
    receipt = dict(version='demo-repair.vm-capture:v1', proposal=plan['proposal'], plan=digest(canonical([VERIFICATION,plan])),
                   inputs=input_digest, controls=controls, base=args.base, image=image, container=container,
                   observed=actual, checks=CHECKS, serial=digest(log.encode()))
    (args.output/'capture.json').write_bytes(canonical(receipt))
    print(canonical(receipt).decode())


if __name__ == '__main__':
    main()
