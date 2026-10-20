#!/usr/bin/python3
"""Trusted first-boot setup, before any target work. Only an exact NoCloud worker is eligible.
This emits operational observations, never a semantic or isolation certificate.
"""
import hashlib
import base64
import binascii
import json
import os
from pathlib import Path
import re
import stat
import subprocess
import sys


def unique(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError('DUPLICATE_BOOTSTRAP_FIELD')
        result[key] = value
    return result


def validate(request):
    if not isinstance(request, dict) or set(request) != {'schema', 'attempt', 'controllerKey', 'workBytes'}:
        raise ValueError('INVALID_WORKER_BOOTSTRAP')
    if request['schema'] != 'guest-worker-bootstrap:m8.4-v1' or not isinstance(request['attempt'], str) or not re.fullmatch('[a-f0-9]{32}', request['attempt']):
        raise ValueError('INVALID_WORKER_BOOTSTRAP')
    validate_public_key(request['controllerKey'])
    capacity = request['workBytes']
    if type(capacity) is not int or capacity < 16777216 or capacity > 9223372036854775807 or capacity % 4096:
        raise ValueError('INVALID_WORK_VOLUME_CAPACITY')
    return request


def validate_public_key(value):
    if not isinstance(value, str) or not re.fullmatch(r'ssh-ed25519 [A-Za-z0-9+/=]+(?: [^\x00-\x1f\x7f]+)?', value):
        raise ValueError('INVALID_CONTROLLER_KEY')
    try:
        wire = base64.b64decode(value.split(' ')[1], validate=True)
    except (ValueError, binascii.Error):
        raise ValueError('INVALID_CONTROLLER_KEY') from None
    # SSH strings have uint32 length prefixes: exact algorithm plus a 32-byte Ed25519 key.
    if len(wire) != 51 or wire[:19] != b'\x00\x00\x00\x0bssh-ed25519\x00\x00\x00\x20':
        raise ValueError('INVALID_CONTROLLER_KEY')


def checked(argv):
    result = subprocess.run(argv, stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL,
                            stderr=subprocess.DEVNULL, timeout=90, check=False,
                            env={'PATH': '/usr/sbin:/usr/bin:/sbin:/bin', 'LANG': 'C.UTF-8'})
    if result.returncode:
        raise RuntimeError('WORKER_BOOTSTRAP_OPERATION_FAILED')


def write_exclusive(path, content, mode=0o600):
    raw = content.encode('utf-8')
    fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, mode)
    with os.fdopen(fd, 'wb') as stream:
        os.fchmod(stream.fileno(), mode)
        stream.write(raw)
        stream.flush()
        os.fsync(stream.fileno())


def main():
    if os.geteuid() != 0:
        raise RuntimeError('BOOTSTRAP_REQUIRES_GUEST_ROOT')
    raw = sys.stdin.buffer.read(8193)
    if len(raw) > 8192:
        raise ValueError('BOOTSTRAP_REQUEST_WORKER_REQUIRED')
    request = validate(json.loads(raw, object_pairs_hook=unique))
    attempt = request['attempt']
    # Refuse the human VM and every clone bootstrapped for a different attempt.
    if Path('/var/lib/cloud/data/instance-id').read_text().strip() != 'evo-' + attempt:
        raise RuntimeError('BOOTSTRAP_INSTANCE_MISMATCH')
    if Path('/sys/class/dmi/id/product_name').read_text().strip() != 'Virtual Machine':
        raise RuntimeError('BOOTSTRAP_HYPERV_REQUIRED')
    import pwd
    account = pwd.getpwnam('evobuild')
    state = Path('/var/lib/evo')
    state.mkdir(mode=0o700, exist_ok=True)
    if state.is_symlink() or state.stat().st_uid != 0:
        raise RuntimeError('UNSAFE_BOOTSTRAP_STATE')
    os.chmod(state, 0o700)
    marker = state / 'sanitized-worker.json'
    if marker.exists():
        raise RuntimeError('WORKER_BOOTSTRAP_ALREADY_ATTEMPTED')
    # Durable intent first; a failed bootstrap may not be silently retried as a clean image.
    write_exclusive(marker, json.dumps(dict(schema='guest-worker-bootstrap-intent:m8.4-v1', request=request), sort_keys=True))
    checked(['/usr/sbin/usermod', '-L', '-G', '', 'evobuild'])
    checked(['/usr/sbin/usermod', '-L', 'nguyen'])
    # Old authored work stays present and root-only. No evidence or user source is deleted.
    work = Path('/work')
    work.mkdir(mode=0o711, exist_ok=True)
    if work.is_symlink() or work.stat().st_uid != 0:
        raise RuntimeError('UNSAFE_BOOTSTRAP_WORK')
    os.chmod(work, 0o711)
    for old in work.iterdir():
        descriptor = old.lstat()
        os.chown(old, 0, 0, follow_symlinks=False)
        if not stat.S_ISLNK(descriptor.st_mode):
            os.chmod(old, 0, follow_symlinks=False)
    root = work / attempt
    root.mkdir(mode=0o700)  # Fresh namespace only.
    volumes = state / 'volumes'
    volumes.mkdir(mode=0o700, exist_ok=True)
    if volumes.is_symlink() or volumes.stat().st_uid != 0:
        raise RuntimeError('UNSAFE_WORK_VOLUME_DIRECTORY')
    disk = volumes / (attempt + '.ext4')
    fd = os.open(disk, os.O_RDWR | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600)
    try:
        os.posix_fallocate(fd, 0, request['workBytes'])
        os.fsync(fd)
    finally:
        os.close(fd)
    checked(['/usr/sbin/mkfs.ext4', '-q', '-F', str(disk)])
    checked(['/usr/bin/mount', '-o', 'loop,nodev,nosuid,noatime', str(disk), str(root)])
    os.chown(root, account.pw_uid, account.pw_gid)
    os.chmod(root, 0o700)
    # First-match configuration precedes distro/cloud defaults. Credentials are controller-only.
    ssh_config = ('AllowUsers evoctl\nPasswordAuthentication no\nKbdInteractiveAuthentication no\n'
                  'PermitRootLogin no\nAllowAgentForwarding no\nAllowTcpForwarding no\n'
                  'X11Forwarding no\nPermitTunnel no\nAuthorizedKeysFile /etc/ssh/evo_worker_keys\n')
    write_exclusive('/etc/ssh/sshd_config.d/00-evo-worker.conf', ssh_config)
    # sshd opens AuthorizedKeysFile under the authenticating user's uid. Public keys may be
    # readable; ownership and write permission remain root-only. Host private keys stay 0600.
    write_exclusive('/etc/ssh/evo_worker_keys', request['controllerKey'] + '\n', mode=0o644)
    checked(['/usr/sbin/sshd', '-t'])
    checked(['/usr/bin/systemctl', 'reload', 'ssh'])
    observation = dict(schema='guest-worker-bootstrap-observation:m8.4-v1', attempt=attempt,
                       requestDigest='sha256:' + hashlib.sha256(json.dumps(request, sort_keys=True, separators=(',', ':')).encode()).hexdigest(),
                       work=str(root), workBytes=request['workBytes'],
                       controllerKeyDigest='sha256:' + hashlib.sha256(request['controllerKey'].encode()).hexdigest(),
                       sourceAccountsLocked=True, oldWorkProtected=True, semanticTrust='PRODUCER_ASSERTION')
    write_exclusive(state / 'bootstrap-complete.json', json.dumps(observation, sort_keys=True))
    print(json.dumps(observation, sort_keys=True))


if __name__ == '__main__':
    try:
        main()
    except Exception as failure:
        # Do not render raw requests, private key material or arbitrary exceptions.
        print(json.dumps({'schema': 'guest-worker-bootstrap-failure:m8.4-v1', 'failureType': type(failure).__name__}), file=sys.stderr)
        sys.exit(78)
