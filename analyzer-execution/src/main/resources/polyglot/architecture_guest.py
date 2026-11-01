#!/usr/bin/python3
"""Root-only preparation of the pinned read-only architecture SDK; no package hooks."""
from pathlib import Path
import tarfile
import shutil


def scratch_budget(expanded,compressed,source):
    """Registered 1-GiB scratch class: two transport copies, source staging and reserved OS/output space."""
    if any(type(n) is not int or n<0 for n in (expanded,compressed,source)):raise ValueError('ARCHITECTURE_SDK_RESOURCE_INPUT')
    planned=expanded+2*compressed+3*source+268435456
    if planned>1073741824:raise ValueError('ARCHITECTURE_SDK_RESOURCE_PROFILE_REQUIRED')
    return planned

def members(source,profile):
    limits={'base':268435456,'nuxt-config-v1':335544320}
    if profile not in limits:raise ValueError('ARCHITECTURE_SDK_PROFILE_UNREGISTERED')
    maximum=limits[profile]
    seen=set();files=set();total=0
    for member in source:
        parts=member.name.split('/')
        if (not parts or parts[0]!='package' or any(p in ('','.','..') for p in parts)
                or '\\' in member.name or ':' in member.name or '\0' in member.name or member.name in seen):raise ValueError('ARCHITECTURE_SDK_ARCHIVE_PATH')
        seen.add(member.name)
        if not member.isdir():
            if not member.isfile() or member.size<0 or member.size>maximum-total:raise ValueError('ARCHITECTURE_SDK_ARCHIVE_WORKER_REQUIRED')
            total+=member.size;files.add(member.name)
        yield member,parts[1:]
    if not {'package/package-lock.json','package/package.json'}<=files:raise ValueError('ARCHITECTURE_SDK_LOCK_REQUIRED')


def inspect(archive,profile='base'):
    """Data-only preflight: stream headers without extracting SDK bytes or creating a worker."""
    entries=total=0
    with tarfile.open(archive,'r:gz') as source:
        for member,_ in members(source,profile):
            if member.isfile():entries+=1;total+=member.size
    return dict(entries=entries,logicalBytes=total)


def unpack(archive,root,profile='base'):
    root=Path(root);root.mkdir()
    with tarfile.open(archive,'r:gz') as source:
        for member,parts in members(source,profile):
            path=root.joinpath(*parts)
            if member.isdir():path.mkdir(parents=True,exist_ok=True);continue
            path.parent.mkdir(parents=True,exist_ok=True)
            with source.extractfile(member) as data,path.open('xb') as target:shutil.copyfileobj(data,target,65536)
            if path.stat().st_size!=member.size:raise ValueError('ARCHITECTURE_SDK_ARCHIVE_TRUNCATED')
            path.chmod(0o444)
    if not (root/'package-lock.json').is_file() or not (root/'package.json').is_file():raise ValueError('ARCHITECTURE_SDK_LOCK_REQUIRED')
    for directory in sorted((p for p in root.rglob('*') if p.is_dir()),key=lambda p:len(p.parts),reverse=True):directory.chmod(0o555)
    root.chmod(0o555);return root
