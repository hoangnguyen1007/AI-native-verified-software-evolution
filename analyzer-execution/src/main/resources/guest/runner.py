#!/usr/bin/python3
"""Root-owned offline candidate launcher. Commands come only from the authenticated controller.
Target output is producer data; this process never holds an attestation signing key.
"""
import json
import os
from pathlib import Path
import re
import subprocess
import sys

MAX_REQUEST_BYTES = 1048576
ALLOWED_TOOLS = frozenset({'/usr/bin/javac', '/usr/bin/java', '/usr/bin/mvn', '/usr/bin/python3', '/opt/evo-tools/gradle-8.14.6/bin/gradle'})


def decode_request(raw):
    if len(raw) > MAX_REQUEST_BYTES:
        raise ValueError('EXECUTION_REQUEST_WORKER_REQUIRED')
    def unique(pairs):
        value = {}
        for key, item in pairs:
            if key in value:
                raise ValueError('DUPLICATE_EXECUTION_FIELD')
            value[key] = item
        return value
    return json.loads(raw, object_pairs_hook=unique)


def validate_request(request, workspace_base=Path('/work')):
    if not isinstance(request, dict) or set(request) != {'attempt', 'argv', 'memoryBytes', 'tasksMax', 'cpuPercent', 'work'}:
        raise ValueError('INVALID_EXECUTION_REQUEST')
    attempt = request['attempt']
    if not isinstance(attempt, str) or not re.fullmatch('[a-f0-9]{32}', attempt):
        raise ValueError('INVALID_ATTEMPT')
    argv = request['argv']
    if not isinstance(argv, list) or not argv or argv[0] not in ALLOWED_TOOLS or any(not isinstance(a, str) or '\x00' in a for a in argv):
        raise ValueError('UNREGISTERED_NATIVE_COMMAND')
    memory, tasks, cpu = (request[key] for key in ('memoryBytes', 'tasksMax', 'cpuPercent'))
    if any(type(v) is not int for v in (memory, tasks, cpu)) or not (67108864 <= memory <= 1073741824 and 1 <= tasks <= 256 and 1 <= cpu <= 100):
        raise ValueError('INVALID_EXECUTION_RESOURCES')
    if not isinstance(request['work'], str):
        raise ValueError('INVALID_EXECUTION_WORKSPACE')
    root = workspace_base / attempt
    if Path(request['work']) != root or not root.is_dir():
        raise ValueError('INVALID_EXECUTION_WORKSPACE')
    # Reject aliases through any ancestor, and a pre-existing target-controlled HOME.
    if any(p.is_symlink() for p in (root, *root.parents)) or (root / 'home').is_symlink():
        raise ValueError('INVALID_EXECUTION_WORKSPACE')
    return root


def build_command(request, root):
    home = root / 'home'
    command = ['/usr/bin/systemd-run', '--quiet', '--wait', '--pipe', '--collect',
               '--unit=evo-' + request['attempt'],
               '--property=User=evobuild', '--property=Group=evobuild',
               '--property=MemoryMax=' + str(request['memoryBytes']), '--property=MemorySwapMax=0',
               '--property=TasksMax=' + str(request['tasksMax']), '--property=CPUQuota=' + str(request['cpuPercent']) + '%',
               '--property=NoNewPrivileges=yes', '--property=PrivateTmp=yes',
               '--property=PrivateDevices=yes', '--property=RestrictNamespaces=yes',
               '--property=PrivateNetwork=yes',
               '--property=TemporaryFileSystem=/tmp:rw,size=64M,mode=1777 /var/tmp:rw,size=64M,mode=1777',
               '--property=InaccessiblePaths=/var/lib/evo /var/lib/cloud /etc/ssh',
               '--property=ProtectSystem=strict', '--property=ProtectHome=yes',
               '--property=RestrictSUIDSGID=yes', '--property=RestrictRealtime=yes',
               '--property=CapabilityBoundingSet=', '--property=UMask=0077',
               '--property=KillMode=control-group', '--property=RuntimeMaxSec=3600',
               '--property=ReadWritePaths=' + str(root), '--property=WorkingDirectory=' + str(root),
               '--', '/usr/bin/env', '-i', 'HOME=' + str(home), 'PATH=/usr/bin:/bin',
               'LANG=C.UTF-8', 'TZ=UTC'] + request['argv']
    return command



def main():
    try:
        request = decode_request(sys.stdin.buffer.read(MAX_REQUEST_BYTES + 1))
        root = validate_request(request)
    except (ValueError, TypeError, UnicodeError):
        print('INVALID_EXECUTION_REQUEST', file=sys.stderr)
        return 64
    import pwd
    account = pwd.getpwnam('evobuild')
    home = root / 'home'
    # Ownership changes must act on the opened directory, never a target-replaceable pathname.
    try:
        home.mkdir(mode=0o700, exist_ok=True)
        descriptor = os.open(home, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW)
        try:
            os.fchown(descriptor, account.pw_uid, account.pw_gid)
        finally:
            os.close(descriptor)
    except OSError:
        print('INVALID_EXECUTION_HOME', file=sys.stderr)
        return 64
    command = build_command(request, root)
    # Controller must independently enforce log/disk budgets, lease, and whole-VM termination.
    return subprocess.run(command, check=False).returncode


if __name__ == '__main__':
    sys.exit(main())
