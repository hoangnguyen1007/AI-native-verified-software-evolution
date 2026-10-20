#!/usr/bin/env python3
"""Authored candidate export fixture. Streams opaque bytes; no host extraction or target execution."""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess
import time
import uuid
import paramiko


def unique(pairs):
    value = {}
    for key, item in pairs:
        if key in value:
            raise ValueError('DUPLICATE_EXPORT_METADATA')
        value[key] = item
    return value


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--config', type=Path, required=True)
    parser.add_argument('--root', type=Path, required=True)
    parser.add_argument('--owner', type=Path, required=True)
    parser.add_argument('--work-attempt', required=True)
    args = parser.parse_args()
    attempt = uuid.UUID(hex=args.work_attempt).hex
    if attempt != args.work_attempt:
        raise ValueError('NONCANONICAL_WORK_ATTEMPT')
    nonce = uuid.uuid4().hex
    args.root.mkdir()  # Preserve all earlier runs; a new output root is mandatory.
    repo = Path(__file__).resolve().parents[4]
    config = json.loads(args.config.read_text(encoding='utf-8-sig'), object_pairs_hook=unique)
    owner = json.loads(args.owner.read_text(encoding='utf-8-sig'), object_pairs_hook=unique)
    client = paramiko.SSHClient()
    keys = paramiko.HostKeys(config['knownHostsFile'])
    for kind, key in keys[config['hostKeyAlias']].items():
        client.get_host_keys().add(config['workerHost'], kind, key)

    def command(text, data=None, maximum=8192, deadline=45):
        stdin, out, err = client.exec_command(text, timeout=deadline)
        if data is not None:
            stdin.write(data)
        stdin.channel.shutdown_write()
        raw = out.read(maximum + 1)
        if len(raw) > maximum:
            out.channel.close()
            raise ValueError('CONTROL_RESPONSE_WORKER_REQUIRED')
        code = out.channel.recv_exit_status()
        if code:
            raise RuntimeError('GUEST_EXPORT_OPERATION_UNAVAILABLE')
        return raw

    def download(remote, destination, expected, maximum):
        size = expected['size']
        digest = expected['digest']
        if type(size) is not int or not 0 <= size <= maximum or not isinstance(digest, str) or len(digest) != 71 or not digest.startswith('sha256:'):
            raise ValueError('INVALID_CAPTURE_HANDLE')
        int(digest[7:], 16)
        if destination.exists():
            actual = hashlib.sha256()
            length = 0
            with destination.open('rb') as source:
                for chunk in iter(lambda: source.read(65536), b''):
                    length += len(chunk)
                    actual.update(chunk)
            if length == size and actual.hexdigest() == digest[7:]:
                return
            raise ValueError('EXISTING_CAPTURE_MISMATCH')
        _, out, err = client.exec_command('sudo -n cat -- ' + remote, timeout=45)
        actual = hashlib.sha256()
        length = 0
        with destination.open('xb') as target:
            while True:
                chunk = out.read(65536)
                if not chunk:
                    break
                length += len(chunk)
                if length > size:
                    out.channel.close()
                    raise ValueError('CAPTURE_CONTENT_EXCEEDS_DECLARATION')
                actual.update(chunk)
                target.write(chunk)
        if out.channel.recv_exit_status() or length != size or actual.hexdigest() != digest[7:]:
            raise ValueError('CAPTURE_CONTENT_MISMATCH')

    try:
        client.connect(config['workerHost'], username='evoctl', key_filename=config['identityFile'],
                       allow_agent=False, look_for_keys=False, timeout=5)
        source = repo / 'analyzer-execution/src/main/resources/guest/export.py'
        expected_pin = hashlib.sha256(source.read_bytes()).hexdigest()
        remote_source = '/tmp/evo-export-' + uuid.uuid4().hex + '.py'
        with client.open_sftp() as transfer:
            transfer.put(str(source), remote_source)
            transfer.chmod(remote_source, 0o600)
        command('sudo -n install -m 0700 ' + remote_source + ' /usr/local/lib/evo/export.py')
        before = command('sudo -n sha256sum /usr/local/lib/evo/export.py').decode().split()[0]
        if before != expected_pin:
            raise ValueError('COLLECTOR_PIN_MISMATCH')
        request = dict(attempt=attempt, nonce=nonce, work='/work/' + attempt, maxRowBytes=65536,
                       maxObjectBytes=134217728, maxCaptureBytes=536870912)
        with (args.root / 'request.json').open('x', encoding='utf-8') as initial:
            json.dump(dict(request=request, collectorPin='sha256:' + expected_pin), initial, sort_keys=True)
            initial.flush()
            __import__('os').fsync(initial.fileno())
        started = time.monotonic()
        raw = command('sudo -n env -i PATH=/usr/bin:/bin LANG=C.UTF-8 /usr/bin/python3 /usr/local/lib/evo/export.py', json.dumps(request), maximum=8192, deadline=300)
        (args.root / 'collector-result.json').write_bytes(raw)
        census = json.loads(raw, object_pairs_hook=unique)
        if census['attempt'] != attempt or census['nonce'] != nonce or census['schema'] != 'guest-output-census:m8.4-v2' or census['trust'] != 'PRODUCER_ASSERTION':
            raise ValueError('CENSUS_SCOPE_MISMATCH')
        base = '/var/lib/evo/export/' + nonce
        download(base + '/scope.json', args.root / 'scope.json', census['scope'], 65536)
        scope = json.loads((args.root / 'scope.json').read_bytes(), object_pairs_hook=unique)
        import base64
        if scope['nonce'] != nonce or base64.b64decode(scope['work']) != request['work'].encode() or scope['budget'] != {key: request[key] for key in ['maxRowBytes','maxObjectBytes','maxCaptureBytes']}:
            raise ValueError('EXPORT_SCOPE_MISMATCH')
        download(base + '/entries.ndjson', args.root / 'entries.ndjson', census['manifest'], 536870912)
        objects = args.root / 'objects'
        objects.mkdir()
        count = pending = rejected = logical_bytes = captured_bytes = 0
        with (args.root / 'entries.ndjson').open('rb') as manifest:
            while True:
                line = manifest.readline(request['maxRowBytes'] + 1)
                if not line:
                    break
                if len(line) > request['maxRowBytes'] or not line.endswith(b'\n'):
                    raise ValueError('CENSUS_ROW_WORKER_REQUIRED')
                row = json.loads(line, object_pairs_hook=unique)
                count += 1
                pending += row['disposition'] == 'PENDING'
                rejected += row['disposition'] == 'REJECTED'
                if row['kind'] in ('FILE', 'HARDLINK'):
                    logical_bytes += row['size']
                if row['content'] is not None:
                    handle = row['content']
                    digest = handle['digest'][7:]
                    if len(digest) != 64 or any(c not in '0123456789abcdef' for c in digest):
                        raise ValueError('INVALID_CAPTURE_DIGEST')
                    download(base + '/objects/' + digest, objects / digest, handle, request['maxObjectBytes'])
                    captured_bytes += handle['size']
        if (count, pending, rejected, logical_bytes, captured_bytes) != (census['entries'], census['pending'], census['rejected'], census['logicalBytes'], census['capturedBytes']):
            raise ValueError('CENSUS_DENOMINATOR_MISMATCH')
        after = command('sudo -n sha256sum /usr/local/lib/evo/export.py').decode().split()[0]
        if before != after:
            raise ValueError('COLLECTOR_CHANGED')
    except Exception as failure:
        with (args.root / 'failure.json').open('x', encoding='utf-8') as failed:
            json.dump(dict(schema='authored-export-failure:m8.4-v1', failureType=type(failure).__name__, qualified=False), failed)
        raise
    finally:
        client.close()
        script = repo / 'analyzer-execution/src/main/resources/hyperv/worker.ps1'
        pin = hashlib.sha256(script.read_bytes()).hexdigest()
        stopped = subprocess.run(['pwsh', '-NoProfile', '-File', str(script), '-Action', 'Stop', '-Workspace', owner['workspace'],
                              '-Attempt', owner['attempt'], '-LaunchDigest', owner['launch'], '-Fence', str(owner['fence'])],
                             stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, timeout=45, check=True)
        off = json.loads(stopped.stdout, object_pairs_hook=unique)
        if (off['vmId'],off['attempt'],off['launch'],off['fence']) != (owner['vmId'],owner['attempt'],owner['launch'],owner['fence']) or off['state'] != 'Off' or hashlib.sha256(script.read_bytes()).hexdigest() != pin:
            raise ValueError('AUTHORITATIVE_EXPORT_TERMINATION_UNCONFIRMED')
        (args.root / 'worker-off.json').write_bytes(stopped.stdout)
    summary = dict(schema='authored-guest-export-fixture:m8.4-v1', workAttempt=attempt, nonce=nonce,
                   collectorPin='sha256:' + expected_pin, driverPin='sha256:' + pin,
                   entries=count, logicalBytes=logical_bytes, capturedBytes=captured_bytes,
                   pending=pending, rejected=rejected, inventoryClosed=census['inventoryClosed'],
                   captureClosed=census['captureClosed'], manifest=census['manifest'], scope=census['scope'], elapsedSeconds=round(time.monotonic()-started,3), qualified=False)
    (args.root / 'summary.json').write_text(json.dumps(summary, sort_keys=True), encoding='utf-8')
    print(json.dumps(summary, sort_keys=True))


if __name__ == '__main__':
    main()
