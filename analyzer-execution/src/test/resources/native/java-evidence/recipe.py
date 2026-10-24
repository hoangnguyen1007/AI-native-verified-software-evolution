#!/usr/bin/python3
"""Fixed authored native cohort. Execute only through the registered guest runner.

This produces native bytes and operational observations, not semantic attestations.
Inputs are root-owned outside the target's sole writable work volume.
"""
import hashlib
import json
import os
from pathlib import Path
import re
import selectors
import subprocess
import sys
import time

MATERIALS = frozenset({
    'lombok.jar', 'mapstruct.jar', 'mapstruct-processor.jar', 'protoc',
    'protoc-gen-grpc-java', 'protobuf-java.jar', 'grpc-api.jar', 'grpc-stub.jar',
    'grpc-protobuf.jar', 'grpc-protobuf-lite.jar', 'guava.jar', 'failureaccess.jar',
    'javax.annotation-api.jar'})


def unique(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError('DUPLICATE_NATIVE_INPUT_FIELD')
        result[key] = value
    return result


def file_digest(path):
    digest = hashlib.sha256()
    with path.open('rb') as stream:
        while chunk := stream.read(65536):
            digest.update(chunk)
    return 'sha256:' + digest.hexdigest()


def verify_materials(root):
    manifest = json.loads((root / 'materials.json').read_bytes(), object_pairs_hook=unique)
    if set(manifest) != {'schema', 'files'} or manifest['schema'] != 'm844-native-materials:v1':
        raise ValueError('NATIVE_MATERIAL_SCHEMA')
    seen = set()
    for row in manifest['files']:
        if (set(row) != {'path', 'size', 'digest', 'origin', 'publisherSha1', 'trust'}
                or row['path'] not in MATERIALS or row['path'] in seen
                or type(row['size']) is not int or not 0 < row['size'] <= 67108864):
            raise ValueError('NATIVE_MATERIAL_DENOMINATOR')
        seen.add(row['path'])
        path = root / row['path']
        if path.is_symlink() or not path.is_file() or path.stat().st_size != row['size']:
            raise ValueError('NATIVE_MATERIAL_CONTENT')
        digest = file_digest(path)
        if digest != row['digest']:
            raise ValueError('NATIVE_MATERIAL_CONTENT')
    if seen != MATERIALS:
        raise ValueError('NATIVE_MATERIAL_DENOMINATOR')
    return manifest


def bounded_command(argv, work, log, timeout=120, maximum=1048576):
    """Bound bytes and duration while retaining a complete log or explicit interruption."""
    started = time.monotonic()
    process = subprocess.Popen(argv, cwd=work, stdin=subprocess.DEVNULL,
                               stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
    size = 0
    try:
        with selectors.DefaultSelector() as selector, log.open('xb') as output:
            selector.register(process.stdout, selectors.EVENT_READ)
            eof = False
            while not eof:
                if time.monotonic() - started >= timeout:
                    raise RuntimeError('NATIVE_STAGE_DEADLINE')
                for key, _ in selector.select(min(0.25, timeout)):
                    chunk = os.read(key.fileobj.fileno(), 65536)
                    if not chunk:
                        eof = True
                        break
                    if size + len(chunk) > maximum:
                        raise RuntimeError('NATIVE_STAGE_LOG_CAPACITY')
                    output.write(chunk)
                    size += len(chunk)
            remaining = timeout - (time.monotonic() - started)
            if remaining <= 0:
                raise RuntimeError('NATIVE_STAGE_DEADLINE')
            return process.wait(timeout=remaining)
    finally:
        if process.poll() is None:
            process.kill()
            process.wait(timeout=10)
        process.stdout.close()


def main():
    if sys.platform != 'linux' or Path('/sys/class/dmi/id/product_name').read_text().strip() != 'Virtual Machine':
        raise RuntimeError('NATIVE_COHORT_REQUIRES_REGISTERED_GUEST')
    if len(sys.argv) != 3 or os.geteuid() == 0:
        raise ValueError('NATIVE_COHORT_BUILD_USER_REQUIRED')
    inputs, work = map(Path, sys.argv[1:])
    if (not re.fullmatch('/work/[a-f0-9]{32}', str(work)) or inputs != Path('/opt/evo-inputs') / work.name
            or not work.is_dir() or inputs.is_symlink() or inputs.stat().st_uid != 0
            or os.access(inputs, os.W_OK)):
        raise ValueError('NATIVE_COHORT_SCOPE')
    materials = inputs / 'materials'
    manifest = verify_materials(materials)
    source = inputs / 'source'
    logs = work / 'logs'
    logs.mkdir()
    stages = []

    def stage(name, argv, expected=0):
        exit_code = bounded_command([str(a) for a in argv], work, logs / (name + '.log'))
        observation = dict(name=name, argv=[str(a) for a in argv], exit=exit_code,
                           expected=expected, state='COMPLETED' if exit_code == expected else 'FAILED')
        stages.append(observation)
        # Durable partial progress before a later stage can fail.
        with (work / ('stage-' + name + '.json')).open('x') as out:
            json.dump(observation, out, sort_keys=True)
        if exit_code != expected:
            raise RuntimeError('NATIVE_COHORT_UNEXPECTED_STAGE_EXIT')

    def destination(relative):
        path = work / relative
        path.mkdir(parents=True)
        return path

    def javac(name, sources, output, classpath=None, processor=None, generated=None):
        argv = ['/usr/bin/javac', '--release', '21', '-encoding', 'UTF-8', '-g', '-parameters', '-d', output]
        if classpath:
            argv += ['-cp', ':'.join(map(str, classpath))]
        if processor:
            argv += ['-processorpath', processor[0], '-processor', processor[1], '-s', generated]
        else:
            argv += ['-proc:none']
        stage(name, argv + sorted(sources))

    stage('java-version', ['/usr/bin/java', '-version'])
    stage('javac-version', ['/usr/bin/javac', '-version'])
    stage('maven-version', ['/usr/bin/mvn', '--version'])
    stage('protoc-version', [materials / 'protoc', '--version'])

    proto = destination('protobuf/generated')
    stage('protobuf-generate', [materials / 'protoc', '--proto_path=' + str(source / 'protobuf/schema'),
          '--java_out=' + str(proto), '--grpc-java_out=' + str(proto),
          '--plugin=protoc-gen-grpc-java=' + str(materials / 'protoc-gen-grpc-java'),
          source / 'protobuf/schema/echo.proto'])
    proto_classes = destination('protobuf/schema/classes')
    proto_cp = [materials / name for name in sorted(MATERIALS) if name.endswith('.jar')
                and name not in {'lombok.jar', 'mapstruct.jar', 'mapstruct-processor.jar'}]
    javac('protobuf-schema', list(proto.rglob('*.java')), proto_classes, proto_cp)
    proto_client = destination('protobuf/client/classes')
    javac('protobuf-client', list((source / 'protobuf/client').rglob('*.java')), proto_client, [proto_classes] + proto_cp)
    stage('protobuf-consumer', ['/usr/bin/java', '-cp', ':'.join(map(str, [proto_client, proto_classes] + proto_cp)), 'fixture.ProtoCaller'])

    lombok_classes = destination('lombok/model/classes')
    lombok_generated = destination('lombok/generated')
    javac('lombok-model', list((source / 'lombok/model').rglob('*.java')), lombok_classes,
          [materials / 'lombok.jar'], (materials / 'lombok.jar', 'lombok.launch.AnnotationProcessorHider$AnnotationProcessor'), lombok_generated)
    lombok_client = destination('lombok/client/classes')
    javac('lombok-client', list((source / 'lombok/client').rglob('*.java')), lombok_client, [lombok_classes])
    stage('lombok-consumer', ['/usr/bin/java', '-cp', str(lombok_client) + ':' + str(lombok_classes), 'fixture.LombokCaller'])

    mapper_model = destination('mapstruct/model/classes')
    javac('mapstruct-model', list((source / 'mapstruct/model').rglob('*.java')), mapper_model)
    mapper_classes = destination('mapstruct/mapper/classes')
    mapper_generated = destination('mapstruct/generated')
    javac('mapstruct-generate', list((source / 'mapstruct/mapper').rglob('*.java')), mapper_classes,
          [mapper_model, materials / 'mapstruct.jar'], (materials / 'mapstruct-processor.jar', 'org.mapstruct.ap.MappingProcessor'), mapper_generated)
    mapper_client = destination('mapstruct/client/classes')
    javac('mapstruct-client', list((source / 'mapstruct/client').rglob('*.java')), mapper_client, [mapper_model, mapper_classes])
    stage('mapstruct-consumer', ['/usr/bin/java', '-cp', ':'.join(map(str, [mapper_client, mapper_model, mapper_classes])), 'fixture.MapStructCaller'])

    generator = destination('custom/generator/classes')
    javac('custom-generator-compile', list((source / 'custom/generator').rglob('*.java')), generator)
    custom_generated = work / 'custom/generated'
    stage('custom-generate', ['/usr/bin/java', '-cp', generator, 'Generate', custom_generated])
    custom_classes = destination('custom/producer/classes')
    javac('custom-producer', list((custom_generated / 'java').rglob('*.java')), custom_classes)
    custom_client = destination('custom/client/classes')
    javac('custom-client', list((source / 'custom/client').rglob('*.java')), custom_client, [custom_classes])
    broken = destination('custom/broken/classes')
    stage('custom-broken', ['/usr/bin/javac', '-XDrawDiagnostics', '--release', '21', '-proc:none', '-d', broken,
                           source / 'custom/broken/fixture/Broken.java'], expected=1)
    broken_diagnostic = (logs / 'custom-broken.log').read_text(encoding='utf-8')
    if ('Broken.java:3:5:' not in broken_diagnostic or 'compiler.err.cant.resolve.location' not in broken_diagnostic
            or 'MissingType' not in broken_diagnostic):
        raise RuntimeError('NATIVE_BROKEN_DIAGNOSTIC_MISMATCH')
    stages.append(dict(name='custom-dependent', state='BLOCKED', prerequisite='custom-broken',
                       source='custom/dependent/fixture/Blocked.java'))
    stage('custom-consumer-after-broken', ['/usr/bin/java', '-cp', str(custom_client) + ':' + str(custom_classes), 'fixture.CustomCaller'])

    partial_processor = destination('partialprocessor/processor/classes')
    javac('partial-processor-compile', [source / 'partialprocessor/PartialProcessor.java'], partial_processor)
    partial_classes = destination('partialprocessor/client/classes')
    partial_generated = destination('partialprocessor/generated')
    stage('partial-processor-failure', ['/usr/bin/javac', '--release', '21', '-encoding', 'UTF-8',
          '-processorpath', partial_processor, '-processor', 'fixture.PartialProcessor',
          '-s', partial_generated, '-d', partial_classes, source / 'partialprocessor/UsePartial.java'], expected=1)
    if not (partial_generated / 'fixture/GeneratedBeforeFailure.java').is_file():
        raise RuntimeError('NATIVE_PARTIAL_PROCESSOR_OUTPUT_LOST')
    if 'EVO_EXPECTED_PARTIAL_PROCESSOR_FAILURE' not in (logs / 'partial-processor-failure.log').read_text(encoding='utf-8'):
        raise RuntimeError('NATIVE_PARTIAL_PROCESSOR_DIAGNOSTIC_LOST')
    stage('custom-consumer-after-processor-failure', ['/usr/bin/java', '-cp', str(custom_client) + ':' + str(custom_classes), 'fixture.CustomCaller'])

    outputs = []
    for path in sorted(work.rglob('*')):
        if path.is_symlink():
            raise ValueError('NATIVE_COHORT_UNEXPECTED_LINK')
        if path.is_file():
            digest = file_digest(path)
            outputs.append(dict(path=path.relative_to(work).as_posix(), size=path.stat().st_size, digest=digest))
    result = dict(schema='m844-authored-native-cohort:v1', attempt=work.name, stages=stages,
                  materials=manifest, outputs=outputs, qualified=False, semanticTrust='PRODUCER_ASSERTION')
    with (work / 'native-cohort.json').open('x') as out:
        json.dump(result, out, sort_keys=True, separators=(',', ':'))
    print(json.dumps(dict(schema=result['schema'], attempt=work.name, stages=len(stages),
                          outputs=len(outputs), qualified=False, semanticTrust='PRODUCER_ASSERTION')))


if __name__ == '__main__':
    main()
