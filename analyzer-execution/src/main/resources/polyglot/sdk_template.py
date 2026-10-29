#!/usr/bin/env python3
"""Register an explicitly approved, immutable native SDK worker image template.

This is not a Core MVP/Maven acceptance, generic executor or semantic certificate.
The registered template has never booted; each launch gets separate worker state.
"""
import argparse
import json
import sys
import uuid
from pathlib import Path
import authored_worker as base

SCOPE='AUTHORED_NATIVE_SDK_TEMPLATE'


def validate(root,catalog=None):
    root=base.unaliased(root).resolve();setup=base.read(root/'setup.json');attempt=str(uuid.UUID(setup['attempt']));catalog=Path.home()/'.evo/polyglot-sdk-workers/templates' if catalog is None else Path(catalog);record=base.read(catalog/(attempt+'.json'))
    if set(record)!={'schema','root','attempt','scope','files'} or record['schema']!='polyglot-sdk-template-registration:m8.4-v1' or record['scope']!=SCOPE or record['attempt']!=attempt or Path(record['root']).resolve()!=root or set(record['files'])!=set(base.TEMPLATE_FILES) or any(base.digest(root/name)!=pin for name,pin in record['files'].items()):raise ValueError('PINNED_NATIVE_SDK_TEMPLATE_REQUIRED')
    profile=base.read(root/'profile.json')
    if setup.get('acceptanceScope')!=SCOPE or profile.get('acceptanceScope')!=SCOPE or profile.get('qualified') is not False:raise ValueError('NATIVE_TEMPLATE_SCOPE_MISMATCH')
    return setup


def register(reference,root,image,source,workspace,storage_receipt):
    reference=base.unaliased(reference).resolve();root=base.unaliased(root).absolute();image=base.unaliased(image).resolve(strict=True);source=base.unaliased(source).resolve(strict=True)
    if root.exists():raise ValueError('NATIVE_TEMPLATE_ALREADY_EXISTS')
    setup=base.validate_template(reference);profile=base.read(reference/'profile.json');request=base.read(reference/'provision-request.json');receipt=base.read(storage_receipt)
    import importlib.util
    path=Path(__file__).resolve().parents[1]/'hyperv/host_storage.py';spec=importlib.util.spec_from_file_location('native_template_storage',path);storage=importlib.util.module_from_spec(spec);spec.loader.exec_module(storage);storage.PinnedVolume(receipt,str(workspace.resolve()),receipt['owner']).check()
    attempt=uuid.uuid4();root.mkdir();pins=[dict(path=str(image),**base.handle(image))];profile={key:value for key,value in profile.items() if key not in ('approvedBy','pipeline','cacheInputs')}
    profile.update(schema='polyglot-sdk-operator-template:m8.4-v1',acceptanceScope=SCOPE,authorization='HUMAN_TASK_M845_NEW_UBUNTU_IMAGE_20261029',qualified=False,image=dict(disk=str(image),images=pins,source=dict(path=str(source),**base.handle(source)),capture='OFFICIAL_IMAGE_DERIVATION',preparation='Dynamic VHDX; no source VM or existing worker state reused'))
    resources=Path(__file__).resolve().parents[1]
    for name in profile['enforcement']:profile['enforcement'][name]=base.digest(resources/name)
    profile['hostStorage']=dict(receipt=str(storage_receipt.resolve()),receiptDigest=base.digest(storage_receipt),owner=receipt['owner'],volumeId=receipt['volumeUniqueId'],capacityBytes=receipt['capacityBytes']);profile.pop('seed',None);base.write(root/'profile.json',profile)
    request.update(attempt=str(attempt),workspace=str(root),launch=base.digest(root/'profile.json'),baseRoot=str(image.parent),disk=str(image),images=pins);base.write(root/'provision-request.json',request)
    setup.update(attempt=str(attempt),root=str(root),vmName='evo-worker-'+attempt.hex,acceptanceScope=SCOPE,workspace=str(workspace.resolve()),hostStorage=profile['hostStorage'],python=sys.executable,pythonPin=base.digest(sys.executable),identityFile=str(root/'not-a-launched-worker-controller'));setup.pop('approvedBy',None);base.write(root/'setup.json',setup)
    base.write(root/'controller-config.json',dict(workerHost=setup['guestAddress'],hostKeyAlias=setup['guestAddress'],identityFile=setup['identityFile'],knownHostsFile=str(root/'known_hosts')))
    with (root/'known_hosts').open('xb'):pass
    catalog=Path.home()/'.evo/polyglot-sdk-workers/templates';catalog.mkdir(parents=True,exist_ok=True);base.write(catalog/(str(attempt)+'.json'),dict(schema='polyglot-sdk-template-registration:m8.4-v1',root=str(root),attempt=str(attempt),scope=SCOPE,files={name:base.digest(root/name) for name in base.TEMPLATE_FILES}));validate(root)
    return dict(root=str(root),scope=SCOPE,image=pins[0],qualified=False,launched=False)


if __name__=='__main__':
    parser=argparse.ArgumentParser()
    for name in ('reference','root','image','source','workspace','storage-receipt'):parser.add_argument('--'+name,type=Path,required=True)
    parser.add_argument('--approve-native-template',action='store_true',required=True);args=parser.parse_args();print(json.dumps(register(args.reference,args.root,args.image,args.source,args.workspace,args.storage_receipt),sort_keys=True))
