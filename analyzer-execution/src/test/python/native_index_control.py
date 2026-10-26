#!/usr/bin/python3
"""Pinned guest control for the fixed native cohort; never a semantic validator.

Only the controller calls this root-owned script. Compiler requests use runner.py
as evobuild; the trusted index tool is compiled before any target can run.
"""
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import re
import shutil
import stat
import sys


def contexts(work, sealed, spring=False):
    if type(spring) is not bool:
        raise ValueError('NATIVE_SPRING_VARIANT_REQUIRED')
    w, s = PurePosixPath(work), PurePosixPath(sealed) / 'source'
    m = PurePosixPath(sealed) / 'materials'
    spring_cp = [m / (name + '.jar') for name in ['spring-aop', 'spring-beans', 'spring-context', 'spring-core', 'spring-expression', 'spring-jcl']] if spring else []
    jars = [m / (name + '.jar') for name in ['failureaccess', 'grpc-api', 'grpc-protobuf',
            'grpc-protobuf-lite', 'grpc-stub', 'guava', 'javax.annotation-api', 'protobuf-java']]
    rows = []

    def add(name, sources, cp=(), expected='PARTIAL'):
        rows.append(dict(name=name, sources=list(map(str, sources)), classpath=list(map(str, cp or [PurePosixPath(sealed) / 'tooling/empty-classpath'])),
                         expectedState=expected, output='index/' + name + '.json'))

    proto = w / 'protobuf/generated/fixture/proto'
    add('protobuf-schema', [proto / (name + '.java') for name in
        ['EchoGrpc', 'EchoOuterClass', 'Reply', 'ReplyOrBuilder', 'Request', 'RequestOrBuilder']], jars)
    add('protobuf-client', [s / 'protobuf/client/fixture/ProtoCaller.java'], [w / 'protobuf/schema/classes'] + jars)
    add('lombok-model', [s / 'lombok/model/fixture/Person.java'], [m / 'lombok.jar'])
    add('lombok-client', [s / 'lombok/client/fixture/LombokCaller.java'], [w / 'lombok/model/classes'])
    add('mapstruct-model', [s / 'mapstruct/model/fixture/Input.java', s / 'mapstruct/model/fixture/Output.java'])
    add('mapstruct-mapper', [s / ('mapstruct/springmapper' if spring else 'mapstruct/mapper') / 'fixture/PersonMapper.java', w / 'mapstruct/generated/fixture/PersonMapperImpl.java'],
        [w / 'mapstruct/model/classes', m / 'mapstruct.jar'] + spring_cp)
    add('mapstruct-client', [s / 'mapstruct/client/fixture/MapStructCaller.java'],
        [w / 'mapstruct/model/classes', w / 'mapstruct/mapper/classes'])
    add('custom-generator', [s / 'custom/generator/Generate.java'])
    add('custom-producer', [w / 'custom/generated/java/fixture/Generated.java'] +
        ([w / 'custom/generated/java/fixture' / (name + '.java') for name in ['Choice', 'BlueChoice', 'OtherChoice']] if spring else []), spring_cp)
    add('custom-client', [s / 'custom/client/fixture/CustomCaller.java'], [w / 'custom/producer/classes'])
    add('custom-broken', [s / 'custom/broken/fixture/Broken.java'], expected='ERROR')
    return rows


def handle(path):
    h = hashlib.sha256()
    with path.open('rb') as stream:
        while chunk := stream.read(65536):
            h.update(chunk)
    return dict(digest='sha256:' + h.hexdigest(), size=path.stat().st_size)


def regular_tree(root):
    """No links, sockets, devices or cross-mount traversal at the quiescent boundary."""
    device = root.stat().st_dev
    result = []
    for base, directories, names in os.walk(root, followlinks=False):
        for path in [Path(base)] + [Path(base) / n for n in sorted(directories + names)]:
            info = path.lstat()
            if info.st_dev != device or not (stat.S_ISDIR(info.st_mode) or stat.S_ISREG(info.st_mode)):
                raise ValueError('INDEX_UNSEALED_NODE')
            if stat.S_ISREG(info.st_mode):
                result.append(path)
    return sorted(set(result))


def freeze(root):
    files = regular_tree(root)
    os.chown(root, 0, 0)
    os.chmod(root, 0o555)
    for path in files:
        os.chown(path, 0, 0)
        os.chmod(path, 0o444)
    for base, directories, _ in os.walk(root, followlinks=False):
        for name in directories:
            path = Path(base) / name
            os.chown(path, 0, 0)
            os.chmod(path, 0o555)


def main():
    if (sys.platform != 'linux' or os.geteuid() != 0
            or Path('/sys/class/dmi/id/product_name').read_text().strip() != 'Virtual Machine'):
        raise ValueError('INDEX_REGISTERED_GUEST_CONTROL_REQUIRED')
    if len(sys.argv) != 4 or sys.argv[1] not in {'seal-tool', 'prepare-index', 'finish'}:
        raise ValueError('INDEX_CONTROL_ACTION')
    action, work, sealed = sys.argv[1], Path(sys.argv[2]), Path(sys.argv[3])
    if (not re.fullmatch('/work/[a-f0-9]{32}', str(work))
            or sealed != Path('/opt/evo-inputs') / work.name
            or any(p.is_symlink() for p in (work, *work.parents, sealed, *sealed.parents))):
        raise ValueError('INDEX_CONTROL_SCOPE')
    tool = Path('/opt/evo-index-tools') / work.name / 'classes'
    classes = work / 'index-tool-classes'
    if action == 'seal-tool':
        files = regular_tree(classes)
        if not files or any(p.suffix != '.class' for p in files):
            raise ValueError('INDEX_TOOL_CLASSES_REQUIRED')
        tool.parent.mkdir(parents=True, exist_ok=False)
        shutil.copytree(classes, tool)
        (sealed / 'tooling/empty-classpath').mkdir(mode=0o555)
        freeze(tool.parent)
        freeze(classes)
    elif action == 'prepare-index':
        # The protected build service has returned and KillMode=control-group
        # quiesced descendants before this controller-owned boundary.
        freeze(work)
        import pwd
        account = pwd.getpwnam('evobuild')
        for path in [work / 'home', work / 'index']:
            path.mkdir(exist_ok=path.name == 'home')
            os.chown(path, account.pw_uid, account.pw_gid)
            os.chmod(path, 0o700)
    else:
        if ({p.relative_to(classes).as_posix(): handle(p) for p in regular_tree(classes)}
                != {p.relative_to(tool).as_posix(): handle(p) for p in regular_tree(tool)}):
            raise ValueError('INDEX_TOOL_CLASSES_CHANGED')
        exits = json.loads(sys.stdin.read(65537))
        rows = contexts(str(work), str(sealed), spring=(sealed / 'materials/spring-context.jar').is_file())
        if set(exits) != {r['name'] for r in rows} or any(type(v) is not int for v in exits.values()):
            raise ValueError('INDEX_CONTEXT_DENOMINATOR')
        freeze(work / 'index')
        outputs = {p.relative_to(work).as_posix(): handle(p) for p in regular_tree(work / 'index')}
        for row in rows:
            row['exit'] = exits[row['name']]
            row['captured'] = outputs.get(row['output'])
            row['captureState'] = 'CAPTURED' if row['captured'] is not None and row['exit'] == 0 else 'PROCESS_FAILED' if row['exit'] else 'OUTPUT_UNAVAILABLE'
            row['sourceInputs'] = {str(p): handle(Path(p)) for p in row['sources']}
            row['classpathInputs'] = []
            for entry in row['classpath']:
                path = Path(entry)
                row['classpathInputs'].append(dict(path=entry, files={
                    (p.relative_to(path).as_posix() if path.is_dir() else ''): handle(p)
                    for p in (regular_tree(path) if path.is_dir() else [path])}))
        result = dict(schema='m844-native-post-index:v1', attempt=work.name,
                      cohort=handle(work / 'native-cohort.json'), contexts=rows, outputs=outputs,
                      toolSource=handle(sealed / 'tooling/CapturedJavacIndex.java'),
                      controlSource=handle(sealed / 'tooling/native_index_control.py'),
                      toolClasses={p.relative_to(classes).as_posix(): handle(p) for p in regular_tree(classes)},
                      blocked=[dict(name='custom-dependent', prerequisite='custom-broken', state='BLOCKED')],
                      qualified=False, semanticTrust='PRODUCER_ASSERTION')
        with (work / 'native-post-index.json').open('x') as stream:
            json.dump(result, stream, sort_keys=True, separators=(',', ':'))
        os.chmod(work / 'native-post-index.json', 0o444)
        print(json.dumps(dict(contexts=len(rows), indexFiles=len(outputs), qualified=False)))


if __name__ == '__main__':
    main()
