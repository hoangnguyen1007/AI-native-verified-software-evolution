#!/usr/bin/env python3
"""Pinned, fresh-identity NoCloud seed. Output contains a private guest SSH host key.
Keep it in the operator-private transport directory, never in a public artifact/CAS endpoint.
"""
import argparse
import base64
import hashlib
import io
import ipaddress
import json
from pathlib import Path
import re
from bootstrap import validate, validate_public_key


def configuration(attempt, mac, address, public_key, host_private, host_public, work_bytes):
    if not isinstance(mac, str) or not re.fullmatch(r'(?:[0-9a-f]{2}:){5}[0-9a-f]{2}', mac):
        raise ValueError('INVALID_WORKER_MAC')
    address = str(ipaddress.IPv4Interface(address))
    request = validate(dict(schema='guest-worker-bootstrap:m8.4-v1', attempt=attempt,
                            controllerKey=public_key.strip(), workBytes=work_bytes))
    if not host_private.startswith('-----BEGIN OPENSSH PRIVATE KEY-----') or not host_public.startswith('ssh-ed25519 '):
        raise ValueError('INVALID_WORKER_HOST_KEYS')
    validate_public_key(host_public)
    sources = Path(__file__).parent
    write_files = []
    for name in ('runner.py', 'export.py', 'bootstrap.py'):
        write_files.append(dict(path='/usr/local/lib/evo/' + name, owner='root:root', permissions='0700',
                                encoding='b64', content=base64.b64encode((sources / name).read_bytes()).decode('ascii')))
    write_files.append(dict(path='/root/evo-worker-request.json', owner='root:root', permissions='0400',
                            content=json.dumps(request, sort_keys=True)))
    config = dict(users=[dict(name='evoctl', groups=['sudo'], sudo=['ALL=(ALL) NOPASSWD:ALL'], shell='/bin/bash', lock_passwd=True,
                              ssh_authorized_keys=[request['controllerKey']]),
                         dict(name='evobuild', groups='', shell='/usr/sbin/nologin', lock_passwd=True)],
                  ssh_pwauth=False, ssh_deletekeys=True, disable_root=True,
                  ssh_keys=dict(ed25519_private=host_private, ed25519_public=host_public), write_files=write_files,
                  runcmd=[['/bin/sh', '-c', '/usr/bin/python3 /usr/local/lib/evo/bootstrap.py < /root/evo-worker-request.json > /root/evo-worker-bootstrap-result.json']])
    metadata = {'instance-id': 'evo-' + attempt, 'local-hostname': 'evo-worker-' + attempt}
    network = dict(version=2, ethernets=dict(evo=dict(match={'macaddress': mac}, **{'set-name': 'evo0'},
                                                    addresses=[address], dhcp4=False, dhcp6=False, optional=True)))
    return config, metadata, network


def main():
    parser = argparse.ArgumentParser()
    for name in ('attempt', 'mac', 'address', 'public-key', 'host-private-key', 'host-public-key', 'output'):
        parser.add_argument('--' + name, required=True)
    parser.add_argument('--work-bytes', type=int, required=True)
    args = parser.parse_args()
    private = Path(args.host_private_key).read_bytes()
    public = Path(args.host_public_key).read_bytes().strip()
    # Reject a mismatched key pair before a VM or mutable transport artifact is created.
    from cryptography.hazmat.primitives import serialization
    from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey
    key = serialization.load_ssh_private_key(private, password=None)
    if not isinstance(key, Ed25519PrivateKey) or key.public_key().public_bytes(serialization.Encoding.OpenSSH, serialization.PublicFormat.OpenSSH) != b' '.join(public.split()[:2]):
        raise ValueError('WORKER_HOST_KEY_PAIR_MISMATCH')
    values = configuration(args.attempt, args.mac, args.address, Path(args.public_key).read_text(encoding='utf-8'),
                           private.decode('ascii'), public.decode('ascii'), args.work_bytes)
    output = Path(args.output)
    if output.exists():
        raise ValueError('WORKER_SEED_ALREADY_EXISTS')
    import pycdlib
    iso = pycdlib.PyCdlib()
    iso.new(interchange_level=3, joliet=3, rock_ridge='1.09', vol_ident='cidata')
    try:
        for name, iso_name, value in zip(('user-data', 'meta-data', 'network-config'), ('USERDATA', 'METADATA', 'NETWORK'), values):
            raw = (('#cloud-config\n' if name == 'user-data' else '') + json.dumps(value) + '\n').encode('utf-8')
            iso.add_fp(io.BytesIO(raw), len(raw), iso_path='/' + iso_name + '.;1', rr_name=name, joliet_path='/' + name)
        with output.open('xb') as stream:
            iso.write_fp(stream)
            stream.flush()
            __import__('os').fsync(stream.fileno())
    finally:
        iso.close()
    print(json.dumps(dict(schema='hyperv-private-worker-seed:m8.4-v1', attempt=args.attempt,
                         digest='sha256:' + hashlib.sha256(output.read_bytes()).hexdigest(), bytes=output.stat().st_size,
                         hostKeyDigest='sha256:' + hashlib.sha256(b' '.join(public.split()[:2])).hexdigest(), qualified=False)))


if __name__ == '__main__':
    main()
