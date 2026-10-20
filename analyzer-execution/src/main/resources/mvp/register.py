#!/usr/bin/env python3
"""Create a fresh private Core MVP registration from an operator's pinned Hyper-V template."""
import argparse
import hashlib
import importlib.util
import json
import subprocess
import sys
import uuid
from pathlib import Path
from capture import unique


def digest(path):
    with Path(path).open('rb') as source:return 'sha256:'+hashlib.file_digest(source,'sha256').hexdigest()


def register_catalog(root):
    """Operator-only publication outside source snapshots and guest-accessible filesystems."""
    root=Path(root).resolve();setup=json.loads((root/'setup.json').read_bytes(),object_pairs_hook=unique)
    attempt=str(uuid.UUID(setup['attempt']));catalog=Path.home()/'.evo/core-mvp/registrations';catalog.mkdir(parents=True,exist_ok=True)
    value=dict(schema='core-mvp-registration:m8.4-v1',root=str(root),attempt=attempt,
               files={name:digest(root/name) for name in ('setup.json','provision-request.json','profile.json','controller-config.json','known_hosts')})
    with (catalog/(attempt+'.json')).open('x',encoding='utf-8') as out:
        json.dump(value,out,sort_keys=True);out.flush();__import__('os').fsync(out.fileno())


def main():
    parser=argparse.ArgumentParser()
    for name in ('template','storage-receipt','identity','python-modules','powershell','workspace'):
        parser.add_argument('--'+name,type=Path,required=True)
    parser.add_argument('--approve-core-mvp',action='store_true',required=True)
    args=parser.parse_args();resources=Path(__file__).resolve().parents[1]
    read=lambda path:json.loads(Path(path).read_bytes(),object_pairs_hook=unique)
    receipt=read(args.storage_receipt)
    spec=importlib.util.spec_from_file_location('registration_storage',resources/'hyperv/host_storage.py')
    storage=importlib.util.module_from_spec(spec);spec.loader.exec_module(storage)
    volume=storage.PinnedVolume(receipt,str(args.workspace.resolve()),receipt['owner']);volume.check()
    # Local VM identities are unique; never reuse a terminal attempt to obtain a fresh launch.
    attempt=uuid.uuid4();root=Path(receipt['driveLetter']+':/mvp-'+attempt.hex);root.mkdir();volume.contained(root)
    subprocess.run(['icacls',str(root),'/inheritance:r','/grant:r','*'+receipt['owner']+':(OI)(CI)F','*S-1-5-18:(OI)(CI)F'],check=True,stdout=subprocess.DEVNULL)
    from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey
    from cryptography.hazmat.primitives import serialization
    key=Ed25519PrivateKey.generate();private=root/'guest-host-ed25519';public=root/'guest-host-ed25519.pub'
    private.write_bytes(key.private_bytes(serialization.Encoding.PEM,serialization.PrivateFormat.OpenSSH,serialization.NoEncryption()))
    public.write_bytes(key.public_key().public_bytes(serialization.Encoding.OpenSSH,serialization.PublicFormat.OpenSSH)+b'\n')
    setup=read(args.template/'setup.json');address=setup['guestAddress'];(root/'known_hosts').write_bytes(address.encode()+b' '+public.read_bytes())
    profile=read(args.template/'profile.json');request=read(args.template/'provision-request.json');seed=root/'worker-seed.iso'
    mac=profile['network']['mac'].lower();mac=':'.join(mac[i:i+2] for i in range(0,12,2))
    subprocess.run([sys.executable,str(resources/'guest/make_worker_seed.py'),'--attempt',attempt.hex,'--mac',mac,'--address',address+'/24',
        '--public-key',str(args.identity.resolve())+'.pub','--host-private-key',str(private),'--host-public-key',str(public),'--output',str(seed),'--work-bytes','1073741824'],check=True,stdout=subprocess.DEVNULL)
    profile.update(acceptanceScope='CORE_MVP',approvedBy='HUMAN',seed=digest(seed))
    for name in profile['enforcement']:profile['enforcement'][name]=digest(resources/name)
    profile['hostStorage']=dict(receipt=str(args.storage_receipt.resolve()),receiptDigest=digest(args.storage_receipt),owner=receipt['owner'],volumeId=receipt['volumeUniqueId'],capacityBytes=receipt['capacityBytes'])
    profile['pipeline']={name:digest(resources/('mvp/'+name)) for name in ('launch.py','native_build.py','capture.py','register.py')}
    raw=json.dumps(profile,sort_keys=True,separators=(',',':')).encode();(root/'profile.json').write_bytes(raw)
    request.update(attempt=str(attempt),workspace=str(root),launch='sha256:'+hashlib.sha256(raw).hexdigest(),driverPin=digest(resources/'hyperv/worker.ps1'),seed=dict(path=str(seed),size=seed.stat().st_size,digest=digest(seed)))
    (root/'provision-request.json').write_text(json.dumps(request,sort_keys=True,separators=(',',':')),encoding='utf-8')
    setup.update(attempt=str(attempt),root=str(root),vmName='evo-worker-'+attempt.hex,acceptanceScope='CORE_MVP',approvedBy='HUMAN',
        workspace=str(args.workspace.resolve()),hostStorage=profile['hostStorage'],identityFile=str(args.identity.resolve()),python=sys.executable,
        powershell=str(args.powershell.resolve()),pythonModules=str(args.python_modules.resolve()))
    setup['pythonPin']=digest(sys.executable);setup['powershellPin']=digest(args.powershell)
    (root/'setup.json').write_text(json.dumps(setup,sort_keys=True),encoding='utf-8')
    (root/'controller-config.json').write_text(json.dumps(dict(workerHost=address,hostKeyAlias=address,identityFile=str(args.identity.resolve()),knownHostsFile=str(root/'known_hosts')),sort_keys=True),encoding='utf-8')
    register_catalog(root)
    print(json.dumps(dict(root=str(root),attempt=str(attempt),scope='CORE_MVP',qualified=False)))


if __name__=='__main__':main()
