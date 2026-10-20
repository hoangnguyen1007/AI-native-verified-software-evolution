#!/usr/bin/env python3
"""Independent controller-side worker supervisor. No target command or host fallback.
The operator creates a private immutable config before starting the target controller.
"""
import argparse
import base64
import ctypes
import hashlib
import json
import os
from pathlib import Path
import subprocess
import time
import uuid


def unique(pairs):
    value = {}
    for key, item in pairs:
        if key in value:
            raise ValueError('DUPLICATE_SUPERVISOR_FIELD')
        value[key] = item
    return value


def read(path, maximum=16384):
    with Path(path).open('rb') as stream:
        raw = stream.read(maximum + 1)
    if len(raw) > maximum:
        raise ValueError('SUPERVISOR_RECORD_WORKER_REQUIRED')
    return json.loads(raw, object_pairs_hook=unique)


def safe(path, root=None):
    path = Path(path).absolute()
    if '..' in path.parts or str(path.anchor).startswith('\\\\') or any(':' in part for part in path.parts[1:]):
        raise ValueError('SUPERVISOR_PATH_ESCAPE')
    if root is not None:
        path.relative_to(Path(root).absolute())
    for part in (path, *path.parents):
        if part.is_symlink() or (hasattr(part, 'is_junction') and part.is_junction()):
            raise ValueError('SUPERVISOR_PATH_ALIAS')
    return path


def validate(config):
    fields = {'schema', 'root', 'owner', 'powershell', 'powershellPin', 'driver', 'driverPin',
              'controlPid', 'runtimeMillis', 'overlayBytes', 'logBytes', 'logs', 'pollMillis'}
    if not isinstance(config, dict) or set(config) != fields or config['schema'] != 'hyperv-supervisor-config:m8.4-v1':
        raise ValueError('INVALID_SUPERVISOR_CONFIG')
    for key in ('controlPid', 'runtimeMillis', 'overlayBytes', 'logBytes', 'pollMillis'):
        value = config[key]
        if type(value) is not int or value < 0 or value > 9223372036854775807:
            raise ValueError('INVALID_SUPERVISOR_BUDGET')
    if not 1 <= config['controlPid'] <= 4294967295 or not config['runtimeMillis'] or not config['overlayBytes'] or not 10 <= config['pollMillis'] <= 1000:
        raise ValueError('INVALID_SUPERVISOR_BUDGET')
    root = safe(config['root'])
    safe(config['owner'], root)
    for key in ('driver', 'powershell'):
        safe(config[key])
        pin = config[key + 'Pin']
        if not isinstance(pin, str) or len(pin) != 71 or not pin.startswith('sha256:') or any(c not in '0123456789abcdef' for c in pin[7:]):
            raise ValueError('INVALID_SUPERVISOR_PIN')
    if not isinstance(config['logs'], list) or not all(isinstance(path, str) for path in config['logs']) or len(config['logs']) > 32 or len(set(config['logs'])) != len(config['logs']):
        raise ValueError('INVALID_SUPERVISOR_LOGS')
    for path in config['logs']:
        safe(path, root)
    return config


def boundary_reason(elapsed_millis, runtime_millis, overlay, overlay_limit, logs, log_limit, alive, completed):
    if not alive:
        return 'CONTROL_PROCESS_LOST'
    if elapsed_millis >= runtime_millis:
        return 'RUNTIME_BUDGET_EXHAUSTED'
    if overlay > overlay_limit:
        return 'OVERLAY_BUDGET_EXHAUSTED'
    if logs > log_limit:
        return 'LOG_BUDGET_EXHAUSTED'
    return 'CONTROLLER_PROTOCOL_COMPLETE' if completed else ''


class WindowsProcess:
    """Hold the original kernel process object, so PID reuse cannot extend a lease."""
    def __init__(self, pid):
        if os.name != 'nt':
            raise RuntimeError('WINDOWS_HYPERV_SUPERVISOR_REQUIRED')
        from ctypes import wintypes
        self.kernel = ctypes.WinDLL('kernel32', use_last_error=True)
        self.kernel.OpenProcess.argtypes = [wintypes.DWORD, wintypes.BOOL, wintypes.DWORD]
        self.kernel.OpenProcess.restype = wintypes.HANDLE
        self.kernel.WaitForSingleObject.argtypes = [wintypes.HANDLE, wintypes.DWORD]
        self.kernel.WaitForSingleObject.restype = wintypes.DWORD
        self.kernel.CloseHandle.argtypes = [wintypes.HANDLE]
        self.kernel.CloseHandle.restype = wintypes.BOOL
        self.handle = self.kernel.OpenProcess(0x00100000, False, pid)  # SYNCHRONIZE only.
        if not self.handle:
            raise RuntimeError('SUPERVISOR_CONTROL_PROCESS_UNAVAILABLE')

    def alive(self):
        result = self.kernel.WaitForSingleObject(self.handle, 0)
        if result not in (0, 258):
            raise RuntimeError('SUPERVISOR_CONTROL_PROCESS_UNKNOWN')
        return result == 258

    def close(self):
        self.kernel.CloseHandle(self.handle)


def write(path, value):
    with Path(path).open('x', encoding='utf-8') as stream:
        json.dump(value, stream, sort_keys=True)
        stream.flush()
        os.fsync(stream.fileno())


def control(config, owner, action):
    if action not in {'Find', 'Stop'}:
        raise ValueError('UNREGISTERED_SUPERVISOR_CONTROL')
    driver = safe(config['driver']).read_bytes()
    executable = safe(config['powershell'])
    with executable.open('rb') as stream:
        executable_digest = hashlib.file_digest(stream, 'sha256').hexdigest()
    if 'sha256:' + hashlib.sha256(driver).hexdigest() != config['driverPin'] or 'sha256:' + executable_digest != config['powershellPin']:
        raise RuntimeError('SUPERVISOR_CONTROL_PIN_MISMATCH')
    attempt = str(uuid.UUID(owner['attempt']))
    if attempt != owner['attempt'] or type(owner['fence']) is not int or not 1 <= owner['fence'] <= 9223372036854775807:
        raise ValueError('INVALID_SUPERVISOR_OWNER')
    launch = owner['launch']
    if not isinstance(launch, str) or len(launch) != 71 or not launch.startswith('sha256:') or any(c not in '0123456789abcdef' for c in launch[7:]):
        raise ValueError('INVALID_SUPERVISOR_OWNER')
    workspace = base64.b64encode(str(safe(owner['workspace'])).encode()).decode()
    source = ('& {\n' + driver.decode('utf-8') + "\n} -Action '" + action + "' -Workspace ([Text.Encoding]::UTF8.GetString([Convert]::FromBase64String('" + workspace + "'))) -Attempt ([Guid]'" + attempt + "') -LaunchDigest '" + launch + "' -Fence " + str(owner['fence']))
    wrapper = '[Console]::InputEncoding=[Text.UTF8Encoding]::new($false); [Console]::OutputEncoding=[Text.UTF8Encoding]::new($false); & ([scriptblock]::Create([Console]::In.ReadToEnd()))'
    encoded = base64.b64encode(wrapper.encode('utf-16-le')).decode()
    result = subprocess.run([str(executable), '-NoLogo', '-NoProfile', '-NonInteractive', '-EncodedCommand', encoded],
                            input=source.encode(), stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, timeout=35, check=True)
    if len(result.stdout) > 8192:
        raise RuntimeError('SUPERVISOR_CONTROL_RESPONSE_OVERSIZE')
    state = json.loads(result.stdout, object_pairs_hook=unique)
    if (state['attempt'], state['launch'], state['fence'], state['vmId']) != (attempt, launch, owner['fence'], owner['vmId']):
        raise RuntimeError('SUPERVISOR_VM_SCOPE_MISMATCH')
    return state


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--config', required=True)
    args = parser.parse_args()
    config = validate(read(safe(args.config)))
    root = safe(config['root'])
    owner = read(safe(config['owner'], root))
    if owner['schema'] != 'hyperv-worker-owner:m8.4-v1' or Path(owner['workspace']).absolute() != root or owner['phase'] != 'CREATED':
        raise ValueError('SUPERVISOR_OWNER_REQUIRED')
    disk = safe(owner['disk'], root)
    process = None
    started = time.monotonic_ns()
    reason = 'SUPERVISOR_FAILURE'
    high_water = 0
    try:
        process = WindowsProcess(config['controlPid'])
        # An independently validated exact worker must exist before the producer gets ready.
        state = control(config, owner, 'Find')
        write(root / 'supervisor-ready.json', dict(schema='hyperv-supervisor-ready:m8.4-v1', attempt=owner['attempt'],
              fence=owner['fence'], vmId=state['vmId'], controlPid=config['controlPid'], driverPin=config['driverPin']))
        while True:
            log_bytes = sum(safe(path, root).stat().st_size if Path(path).exists() else 0 for path in config['logs'])
            if log_bytes < high_water:
                reason = 'LOG_COUNTER_REGRESSION'
                break
            high_water = log_bytes
            complete = root / 'controller-protocol-complete.json'
            done = False
            if complete.exists():
                value = read(safe(complete, root))
                if value != dict(attempt=owner['attempt'], fence=owner['fence'], launch=owner['launch']):
                    raise ValueError('SUPERVISOR_COMPLETION_SCOPE_MISMATCH')
                done = True
            reason = boundary_reason((time.monotonic_ns() - started) // 1000000, config['runtimeMillis'],
                                     safe(disk, root).stat().st_size, config['overlayBytes'], log_bytes, config['logBytes'], process.alive(), done)
            if reason:
                break
            time.sleep(config['pollMillis'] / 1000)
    finally:
        if process is not None:
            process.close()
        # An unknown disposition is recorded and never misreported as quiescence.
        try:
            stopped = control(config, owner, 'Stop')
            if stopped['state'] != 'Off':
                raise RuntimeError('SUPERVISOR_TERMINATION_UNKNOWN')
            write(root / 'supervisor-result.json', dict(schema='hyperv-supervisor-result:m8.4-v1', reason=reason,
                  state=stopped, logHighWater=high_water, elapsedMillis=(time.monotonic_ns() - started) // 1000000, qualified=False))
        except Exception as failure:
            write(root / 'supervisor-unknown.json', dict(schema='hyperv-supervisor-unknown:m8.4-v1',
                                                       failureType=type(failure).__name__, qualified=False))
            raise


if __name__ == '__main__':
    main()
