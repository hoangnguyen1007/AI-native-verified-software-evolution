#!/usr/bin/env python3
"""Authored candidate fixture: observe a bounded parent/child surviving SSH loss before VM recovery.
No arbitrary target command is accepted. Use only the explicitly configured owned candidate.
"""
import argparse
import hashlib
import json
from pathlib import Path
import re
import time
import uuid
import paramiko


def connect(config):
    client = paramiko.SSHClient()
    keys = paramiko.HostKeys(config['knownHostsFile'])
    for kind, key in keys[config['hostKeyAlias']].items():
        client.get_host_keys().add(config['workerHost'], kind, key)
    client.connect(config['workerHost'], username='evoctl', key_filename=config['identityFile'],
                   allow_agent=False, look_for_keys=False, timeout=3, banner_timeout=3, auth_timeout=3)
    return client


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--config', type=Path, required=True)
    parser.add_argument('--root', type=Path, required=True)
    args = parser.parse_args()
    config = json.loads(args.config.read_text(encoding='utf-8-sig'))
    client = connect(config)
    attempt = uuid.uuid4().hex
    work = '/work/' + attempt
    try:
        _, out, err = client.exec_command('sudo -n install -d -m 0750 -o evobuild -g evobuild ' + work, timeout=10)
        if out.channel.recv_exit_status():
            raise RuntimeError('AUTHORED_FIXTURE_SETUP_FAILED')
        request = dict(attempt=attempt, work=work, memoryBytes=134217728, tasksMax=16, cpuPercent=50,
                       argv=['/usr/bin/python3', '-c', "import os,time; child=os.fork(); print('PARENT='+str(os.getpid())+' CHILD='+str(child),flush=True) if child else None; time.sleep(180)"])
        stdin, out, err = client.exec_command('sudo -n /usr/bin/python3 /usr/local/lib/evo/runner.py', timeout=15)
        stdin.write(json.dumps(request))
        stdin.channel.shutdown_write()
        marker = out.readline(256).strip()
        if not re.fullmatch(r'PARENT=[1-9][0-9]* CHILD=[1-9][0-9]*', marker):
            raise RuntimeError('PARENT_CHILD_MARKER_MISSING')
    finally:
        client.close()
    # A new authenticated connection supplies independent operational observation after the loss.
    client = connect(config)
    try:
        _, out, err = client.exec_command('sudo -n systemctl show evo-' + attempt + ' -p ActiveState -p TasksCurrent', timeout=10)
        raw = out.read(1024)
        if out.channel.recv_exit_status():
            raise RuntimeError('POST_DISCONNECT_OBSERVATION_FAILED')
        observed = dict(line.split('=', 1) for line in raw.decode('utf-8').strip().splitlines())
        if observed.get('ActiveState') != 'active' or int(observed.get('TasksCurrent', '0')) < 2:
            raise RuntimeError('SURVIVING_CHILD_NOT_OBSERVED')
        result = dict(schema='authored-controller-loss-probe:m8.4-v1', unit='evo-' + attempt,
                      marker=marker, observation=observed, observationSha256=hashlib.sha256(raw).hexdigest(), qualified=False)
        with (args.root / 'disconnect-observation.json').open('x', encoding='utf-8') as target:
            json.dump(result, target, sort_keys=True)
        print(json.dumps(result, sort_keys=True))
    finally:
        client.close()


if __name__ == '__main__':
    main()
