#!/usr/bin/python3
"""Root-owned native build launcher. Policy is installed by the pinned controller before
target execution; it never receives a private transport/signing key or ambient environment."""
import hashlib
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
            raise ValueError('NATIVE_DUPLICATE_FIELD')
        result[key] = value
    return result


def main():
    policy_file = Path('/var/lib/evo/native-build-policy.json')
    status = policy_file.lstat()
    if os.geteuid() != 0 or not stat.S_ISREG(status.st_mode) or status.st_uid != 0 or status.st_mode & 0o077:
        raise ValueError('NATIVE_PROTECTED_POLICY_REQUIRED')
    policy = json.loads(policy_file.read_bytes(), object_pairs_hook=unique)
    raw = sys.stdin.buffer.read(1048577)
    if len(raw) > 1048576:
        raise ValueError('NATIVE_REQUEST_WORKER_REQUIRED')
    request = json.loads(raw, object_pairs_hook=unique)
    if set(request) != {'attempt', 'role', 'arguments', 'directory'} or request['attempt'] != policy['attempt']:
        raise ValueError('NATIVE_REQUEST_SCOPE')
    if not re.fullmatch('[a-f0-9]{32}', request['attempt']):
        raise ValueError('NATIVE_ATTEMPT_REQUIRED')
    tool = policy['tools'][request['role']]
    executable = Path(tool['executable'])
    if not executable.is_absolute() or any(part.is_symlink() for part in (executable, *executable.parents)):
        raise ValueError('NATIVE_TOOL_ALIAS')
    with executable.open('rb') as source:
        if executable.stat().st_size != tool['content']['size'] or 'sha256:' + hashlib.file_digest(source, 'sha256').hexdigest() != tool['content']['digest']:
            raise ValueError('NATIVE_TOOL_PIN_CHANGED')
    arguments = request['arguments']
    if not isinstance(arguments, list) or any(not isinstance(value, str) or '\0' in value for value in arguments):
        raise ValueError('NATIVE_ARGUMENTS_INVALID')
    root = Path('/work') / request['attempt']
    directory = Path(request['directory'])
    if not directory.is_absolute() or directory != directory.resolve(strict=True) or not directory.is_relative_to(root / 'source'):
        raise ValueError('NATIVE_WORKING_DIRECTORY')
    resources = policy['resources']
    home = root / 'home'
    if home.is_symlink() or not home.is_dir():
        raise ValueError('NATIVE_PRIVATE_HOME_REQUIRED')
    runtime = (resources['runtimeMillis'] + 999) // 1000
    command = ['/usr/bin/systemd-run', '--quiet', '--wait', '--pipe', '--collect', '--unit=evo-native-' + request['attempt'],
               '--property=User=evobuild', '--property=Group=evobuild',
               '--property=MemoryMax=' + str(resources['buildMemoryBytes']), '--property=MemorySwapMax=0',
               '--property=TasksMax=' + str(resources['tasksMax']), '--property=CPUQuota=' + str(resources['cpuPercent']) + '%',
               '--property=NoNewPrivileges=yes', '--property=PrivateTmp=yes', '--property=PrivateDevices=yes',
               '--property=RestrictNamespaces=yes', '--property=PrivateNetwork=yes', '--property=ProtectSystem=strict',
               '--property=ProtectHome=yes', '--property=InaccessiblePaths=/var/lib/evo /var/lib/cloud /etc/ssh /root',
               '--property=RestrictSUIDSGID=yes', '--property=RestrictRealtime=yes', '--property=CapabilityBoundingSet=',
               '--property=UMask=0077', '--property=KillMode=control-group', '--property=RuntimeMaxSec=' + str(runtime),
               '--property=ReadWritePaths=' + str(root), '--property=WorkingDirectory=' + str(directory),
               '--', '/usr/bin/env', '-i', 'HOME=' + str(home), 'PATH=/usr/bin:/bin', 'LANG=C.UTF-8', 'TZ=UTC',
               'GRADLE_USER_HOME=' + str(root / 'cache/gradle'), str(executable), *arguments]
    process=subprocess.Popen(command,stdin=subprocess.DEVNULL)
    code=process.wait()
    receipt=dict(schema='native-build-launcher-returned:m8.4-v1',attempt=request['attempt'],role=request['role'],
                 tool=tool['content'],exitCode=code,executionEvidence='PROTECTED_GUEST_LAUNCHER_RETURNED')
    descriptor=os.open('/var/lib/evo/native-build-finished.json',os.O_WRONLY|os.O_CREAT|os.O_EXCL|os.O_NOFOLLOW,0o600)
    with os.fdopen(descriptor,'w',encoding='utf-8') as output:
        json.dump(receipt,output,sort_keys=True);output.flush();os.fsync(output.fileno())
    return code


if __name__ == '__main__':
    try:
        sys.exit(main())
    except (OSError, ValueError, KeyError, TypeError):
        print('NATIVE_BUILD_REQUEST_REJECTED', file=sys.stderr)
        sys.exit(64)
