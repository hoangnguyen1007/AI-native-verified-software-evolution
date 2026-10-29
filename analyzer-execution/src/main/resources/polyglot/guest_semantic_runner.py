#!/usr/bin/python3
"""Fixed registered TS/JS recipe inside a fresh VM. No target argv, plugins or host execution."""
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import re
import subprocess
import sys


def unique(pairs):
    value = {}
    for key, item in pairs:
        if key in value:
            raise ValueError('DUPLICATE_SEMANTIC_FIELD')
        value[key] = item
    return value


def digest(path):
    hashed = hashlib.sha256()
    with Path(path).open('rb') as stream:
        while block := stream.read(65536):
            hashed.update(block)
    return 'sha256:' + hashed.hexdigest()


def validate_request(value, registration):
    if (not isinstance(value, dict) or set(value) != {'schema', 'attempt', 'context'}
            or value['schema'] != 'polyglot-guest-request:m8.4-v1'
            or value['attempt'] != registration['attempt']
            or not re.fullmatch('[a-f0-9]{32}', value['attempt'])
            or not isinstance(value['context'], str)
            or not re.fullmatch('[a-z][a-z0-9-]{0,63}', value['context'])
            or value['context'] not in registration['contexts']):
        raise ValueError('UNREGISTERED_SEMANTIC_RECIPE')
    return registration['contexts'][value['context']]


def require_file(path, expected):
    path = Path(path)
    if (not path.is_file() or any(p.is_symlink() for p in (path, *path.parents))
            or path.stat().st_size != expected['size'] or digest(path) != expected['digest']):
        raise ValueError('SEMANTIC_TOOL_OR_INPUT_CHANGED')
    return path


def require_sdk_file(path,expected):
    """Permit only manifest-captured aliases inside the read-only tools disc.

    Swift's driver selects its mode from the entry name, so replacing swiftc by
    its resolved executable is not equivalent. Both entry and target are pinned.
    This does not relax captured source/control-file alias rejection.
    """
    path=Path(path);root=Path('/opt/evo-toolset')
    if 'resolvedPath' not in expected:return require_file(path,expected)
    resolved=path.resolve(strict=True)
    if (str(path)!=expected['path'] or str(resolved)!=expected['resolvedPath'] or root not in path.parents or root not in resolved.parents
            or not os.statvfs(root).f_flag&os.ST_RDONLY or not resolved.is_file()
            or resolved.stat().st_size!=expected['size'] or digest(resolved)!=expected['digest']):raise ValueError('SEMANTIC_SDK_ENTRY_OR_RESOLVED_TOOL_CHANGED')
    return path


def main():
    base = Path(__file__).resolve().parent
    registry = json.loads((base / 'registration.json').read_bytes(), object_pairs_hook=unique)
    raw = sys.stdin.buffer.read(16385)
    if len(raw) > 16384:
        raise ValueError('SEMANTIC_REQUEST_WORKER_REQUIRED')
    request = json.loads(raw, object_pairs_hook=unique)
    context = validate_request(request, registry)
    root = Path('/work') / request['attempt']
    if str(root) != registry['work'] or not root.is_dir() or any(p.is_symlink() for p in (root, *root.parents)):
        raise ValueError('SEMANTIC_WORKSPACE_MISMATCH')
    tools = registry['tools']
    for name in ('node', 'worker', 'compiler', 'baseRunner'):
        require_file(tools[name]['path'], tools[name])
    require_file(context['input']['path'], context['input'])
    import pwd
    user = pwd.getpwnam('evobuild')
    home = root / 'home'
    home.mkdir(mode=0o700, exist_ok=True)
    descriptor = os.open(home, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW)
    try:
        os.fchown(descriptor, user.pw_uid, user.pw_gid)
    finally:
        os.close(descriptor)
    spec = importlib.util.spec_from_file_location('protected_semantic_systemd', tools['baseRunner']['path'])
    runner = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(runner)
    # A new registered recipe validates its own fixed toolset. The old Core MVP allowlist is unchanged.
    native = dict(attempt=request['attempt'], work=str(root), memoryBytes=1073741824, tasksMax=128, cpuPercent=100,
                  argv=[tools['node']['path'], tools['worker']['path'], tools['compiler']['path']])
    command = runner.build_command(native, root)
    output = root / 'results' / (request['context'] + '.ndjson')
    errors = root / 'results' / (request['context'] + '.stderr')
    with Path(context['input']['path']).open('rb') as source, output.open('xb') as out, errors.open('xb') as err:
        result = subprocess.run(command, stdin=source, stdout=out, stderr=err, check=False)
    require_file(context['input']['path'], context['input'])
    for name in tools:
        require_file(tools[name]['path'], tools[name])
    print(json.dumps(dict(schema='polyglot-guest-result:m8.4-v1', context=request['context'], exit=result.returncode,
                          output=dict(path=str(output), size=output.stat().st_size, digest=digest(output)),
                          trust='PRODUCER_ASSERTION', qualified=False), sort_keys=True))
    return result.returncode


if __name__ == '__main__':
    try:
        sys.exit(main())
    except (ValueError, OSError, KeyError, TypeError):
        print('SEMANTIC_REGISTERED_RECIPE_FAILED', file=sys.stderr)
        sys.exit(64)
