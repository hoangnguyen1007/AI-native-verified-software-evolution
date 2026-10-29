#!/usr/bin/env python3
"""Build read-only tool-only ISO data, preserving Linux names on a Windows controller.

No SDK/application code, installers or archive hooks are executed. Every archive is
privately frozen and hash-verified before parsing. Archive names never become host paths.
The media contains public tools only; NoCloud seeds/SSH keys belong to a different disc.
"""
import argparse
from contextlib import contextmanager
import hashlib
import gzip
import io
import json
from pathlib import Path, PurePosixPath
import re
import shutil
import stat
import tarfile
import tempfile
import zipfile


def logical(value):
    if (not isinstance(value,str) or not value or '\\' in value or ':' in value or '\x00' in value
            or value.startswith('/') or any(part in ('','.','..') for part in value.split('/'))):
        raise ValueError('SDK_MEDIA_PATH_ESCAPE')
    value.encode('utf-8',errors='strict')
    return value


def link_target(name,target):
    logical(name)
    if not isinstance(target,str) or not target or target.startswith('/') or '\\' in target or ':' in target or '\x00' in target:
        raise ValueError('SDK_MEDIA_LINK_ESCAPE')
    parts=name.split('/')[:-1]
    for part in target.split('/'):
        if part in ('','.'):continue
        if part=='..':
            if not parts:raise ValueError('SDK_MEDIA_LINK_ESCAPE')
            parts.pop()
        else:parts.append(part)
    if not parts:raise ValueError('SDK_MEDIA_LINK_ESCAPE')
    return logical('/'.join(parts))


def digest(path):
    with Path(path).open('rb') as source:return 'sha256:'+hashlib.file_digest(source,'sha256').hexdigest()


def handle(path):return dict(size=Path(path).stat().st_size,digest=digest(path))


def unique(pairs):
    value={}
    for name,item in pairs:
        if name in value:raise ValueError('DUPLICATE_SDK_MEDIA_FIELD')
        value[name]=item
    return value


@contextmanager
def frozen(root,item):
    path=root/logical(item['source'])
    if (any(p.is_symlink() or (hasattr(p,'is_junction') and p.is_junction()) for p in (path,*path.parents))
            or type(item['size']) is not int or item['size']<0 or not re.fullmatch('sha256:[a-f0-9]{64}',item['digest'])):
        raise ValueError('SDK_MEDIA_INPUT_INVALID')
    with tempfile.TemporaryFile('w+b',dir=root) as private:
        size=0;hashed=hashlib.sha256()
        with path.open('rb') as source:
            while block:=source.read(1048576):
                size+=len(block)
                if size>item['size']:raise ValueError('SDK_MEDIA_INPUT_CHANGED')
                hashed.update(block);private.write(block)
        if size!=item['size'] or 'sha256:'+hashed.hexdigest()!=item['digest']:raise ValueError('SDK_MEDIA_INPUT_CHANGED')
        private.seek(0);yield private


def member_path(name,prefix,strip):
    while name.startswith('./'):name=name[2:]
    name=name.rstrip('/')
    if not name:return None
    logical(name);parts=name.split('/')
    if len(parts)<=strip:return None
    return logical(prefix+'/'+('/'.join(parts[strip:])))


def build(root,artifacts,output,max_bytes=17179869184):
    """A toolset worker budget, never a repository admission ceiling."""
    import pycdlib
    root=Path(root).absolute();output=Path(output).absolute()
    if output.exists():raise ValueError('SDK_MEDIA_OUTPUT_ALREADY_EXISTS')
    if type(max_bytes) is not int or max_bytes<0:raise ValueError('SDK_MEDIA_WORKER_BUDGET')
    if not artifacts:raise ValueError('SDK_MEDIA_INPUTS_REQUIRED')
    entries={};directories=set();size=0;seen_artifacts=set()
    with tempfile.TemporaryDirectory(prefix='sdk-media-staging-',dir=root) as staging:
        staging=Path(staging)
        def parents(name):
            parts=name.split('/')
            for end in range(1,len(parts)):
                parent='/'.join(parts[:end])
                if parent in entries:raise ValueError('SDK_MEDIA_NAMESPACE_CONFLICT')
                directories.add(parent)
        def add_file(name,stream,expected_size,mode):
            nonlocal size
            logical(name)
            if name in entries or name in directories:raise ValueError('SDK_MEDIA_DUPLICATE_ENTRY')
            if type(expected_size) is not int or not 0<=expected_size<=max_bytes-size:raise ValueError('SDK_MEDIA_WORKER_REQUIRED')
            parents(name);path=staging/('F'+str(len(entries)));written=0
            with path.open('xb') as target:
                while block:=stream.read(1048576):
                    written+=len(block)
                    if written>expected_size:raise ValueError('SDK_MEDIA_MEMBER_SIZE_CHANGED')
                    target.write(block)
            if written!=expected_size:raise ValueError('SDK_MEDIA_MEMBER_SIZE_CHANGED')
            entries[name]=dict(kind='file',path=path,mode=0o100555 if mode&0o111 else 0o100444);size+=written
        def add_link(name,target):
            logical(name);resolved=link_target(name,target)
            if name in entries or name in directories:raise ValueError('SDK_MEDIA_DUPLICATE_ENTRY')
            parents(name);entries[name]=dict(kind='link',target=target,resolved=resolved)
        def tar_members(archive,item):
            prefix=logical(item['prefix']);strip=item.get('strip',0)
            subtree=logical(item['subtree']) if 'subtree' in item else None
            skip={logical(value) for value in item.get('skipMembers',[])}
            for member in archive:
                if member.isdir() and member.name in ('.','./'):continue
                original=member.name.removeprefix('./').rstrip('/')
                if subtree:
                    if original!=subtree and not original.startswith(subtree+'/'):continue
                    if original.removeprefix(subtree+'/') in skip:continue
                elif original in skip:continue
                name=member_path(member.name,prefix,strip)
                if name is None:continue
                if member.isdir():
                    if name in entries:raise ValueError('SDK_MEDIA_DUPLICATE_ENTRY')
                    parents(name);directories.add(name)
                elif member.issym():add_link(name,member.linkname)
                elif member.isfile() or member.islnk():
                    if member.islnk():
                        target=member_path(member.linkname,prefix,strip)
                        if target is None:raise ValueError('SDK_MEDIA_HARDLINK_ESCAPE')
                    with archive.extractfile(member) as source:add_file(name,source,member.size if member.isfile() else archive.getmember(member.linkname).size,member.mode)
                else:raise ValueError('SDK_MEDIA_SPECIAL_ENTRY')
        for item in artifacts:
            if item['source'] in seen_artifacts:raise ValueError('SDK_MEDIA_DUPLICATE_ARTIFACT')
            seen_artifacts.add(item['source'])
            if type(item.get('strip',0)) is not int or item.get('strip',0)<0:raise ValueError('SDK_MEDIA_STRIP_INVALID')
            prefix=logical(item['prefix'])
            if prefix.split('/')[0]=='TOOLSET.JSON':raise ValueError('SDK_MEDIA_RESERVED_NAME')
            with frozen(root,item) as captured:
                if item['format']=='file':add_file(prefix,captured,item['size'],item.get('mode',0o444))
                elif item['format']=='tar':
                    with tarfile.open(fileobj=captured,mode='r:*') as archive:tar_members(archive,item)
                elif item['format']=='gem':
                    with tarfile.open(fileobj=captured,mode='r:*') as outer:
                        payload=outer.getmember('data.tar.gz')
                        if not payload.isfile():raise ValueError('SDK_MEDIA_GEM_PAYLOAD_INVALID')
                        with outer.extractfile(payload) as stream,tarfile.open(fileobj=stream,mode='r:gz') as inner:tar_members(inner,item)
                elif item['format']=='gzip-file':
                    if type(item.get('outputBytes')) is not int:raise ValueError('SDK_MEDIA_GZIP_SIZE_REQUIRED')
                    with gzip.GzipFile(fileobj=captured,mode='rb') as stream:add_file(prefix,stream,item['outputBytes'],item.get('mode',0o555))
                elif item['format']=='deb':
                    if captured.read(8)!=b'!<arch>\n':raise ValueError('SDK_DEB_AR_HEADER')
                    payload=None
                    while header:=captured.read(60):
                        if len(header)!=60 or header[-2:]!=b'`\n':raise ValueError('SDK_DEB_AR_MEMBER')
                        name=header[:16].decode('ascii').strip().removesuffix('/');length=header[48:58].decode('ascii').strip()
                        if not length.isdigit() or int(length)>268435456:raise ValueError('SDK_DEB_MEMBER_WORKER_REQUIRED')
                        size_bytes=int(length);data=captured.read(size_bytes)
                        if len(data)!=size_bytes or size_bytes%2 and len(captured.read(1))!=1:raise ValueError('SDK_DEB_AR_TRUNCATED')
                        if name.startswith('data.tar'):
                            if payload is not None:raise ValueError('SDK_DEB_DUPLICATE_DATA')
                            payload=data
                    if payload is None:raise ValueError('SDK_DEB_DATA_REQUIRED')
                    if payload.startswith(b'\x28\xb5\x2f\xfd'):
                        import zstandard
                        if zstandard.__version__!='0.23.0':raise ValueError('SDK_DEB_ZSTD_ASSEMBLER_VERSION')
                        with tempfile.TemporaryFile('w+b',dir=root) as expanded:
                            written=0
                            with zstandard.ZstdDecompressor().stream_reader(io.BytesIO(payload),read_across_frames=True) as source:
                                while block:=source.read(1048576):
                                    written+=len(block)
                                    if written>max_bytes-size:raise ValueError('SDK_DEB_EXPANDED_WORKER_REQUIRED')
                                    expanded.write(block)
                            expanded.seek(0)
                            with tarfile.open(fileobj=expanded,mode='r:*') as archive:tar_members(archive,item)
                    else:
                        with tarfile.open(fileobj=io.BytesIO(payload),mode='r:*') as archive:tar_members(archive,item)
                elif item['format']=='zip':
                    with zipfile.ZipFile(captured) as archive:
                        for member in archive.infolist():
                            name=member_path(member.filename,prefix,item.get('strip',0))
                            if name is None:continue
                            mode=member.external_attr>>16
                            if member.is_dir():
                                if name in entries:raise ValueError('SDK_MEDIA_DUPLICATE_ENTRY')
                                parents(name);directories.add(name)
                            elif stat.S_ISLNK(mode):
                                if member.file_size>65536:raise ValueError('SDK_MEDIA_LINK_WORKER_REQUIRED')
                                add_link(name,archive.read(member).decode('utf-8',errors='strict'))
                            else:
                                with archive.open(member) as source:add_file(name,source,member.file_size,mode)
                else:raise ValueError('SDK_MEDIA_FORMAT_UNSUPPORTED')
        for name,entry in entries.items():
            if entry['kind']=='link':
                # Links may point to a native-platform dependency or be intentionally dangling, but
                # cannot escape their registered SDK subtree to the seed/control directories.
                owner=name.split('/')[0]
                if entry['resolved'].split('/')[0]!=owner:raise ValueError('SDK_MEDIA_LINK_SCOPE_ESCAPE')
        metadata=dict(schema='polyglot-sdk-media:m8.4-v1',entries=len(entries),logicalBytes=size,
                      artifacts=artifacts,trust='PINNED_TOOL_BYTES_ONLY',containsSeedOrTransportKeys=False)
        raw=json.dumps(metadata,sort_keys=True,separators=(',',':')).encode();metadata_path=staging/'TOOLSET.JSON';metadata_path.write_bytes(raw)
        iso=pycdlib.PyCdlib();iso.new(interchange_level=3,rock_ridge='1.09',sys_ident='LINUX',vol_ident='EVO_TOOLSET')
        paths={'':'/'}
        try:
            for index,name in enumerate(sorted(directories,key=lambda n:(n.count('/'),n))):
                parent,_,leaf=name.rpartition('/');path=paths[parent].rstrip('/')+'/D'+str(index).zfill(8)
                iso.add_directory(iso_path=path,rr_name=leaf,file_mode=0o40555);paths[name]=path
            for index,(name,entry) in enumerate(sorted(entries.items())):
                parent,_,leaf=name.rpartition('/');path=paths[parent].rstrip('/')+'/F'+str(index).zfill(8)
                if entry['kind']=='link':iso.add_symlink(symlink_path=path,rr_symlink_name=leaf,rr_path=entry['target'])
                else:iso.add_file(str(entry['path']),iso_path=path+';1',rr_name=leaf,file_mode=entry['mode'])
            iso.add_file(str(metadata_path),iso_path='/TOOLSET.JSON;1',rr_name='TOOLSET.JSON',file_mode=0o100444)
            # Create-new reservation prevents an existing SDK revision from being overwritten.
            with output.open('xb') as target:iso.write_fp(target)
        finally:iso.close()
        return dict(schema='polyglot-sdk-media-result:m8.4-v1',entries=len(entries),logicalBytes=size,
                    media=handle(output),manifest=dict(size=len(raw),digest='sha256:'+hashlib.sha256(raw).hexdigest()),qualified=False)


def main():
    parser=argparse.ArgumentParser();parser.add_argument('--inputs',type=Path,required=True);parser.add_argument('--output',type=Path,required=True)
    args=parser.parse_args()
    value=json.loads(args.inputs.read_bytes(),object_pairs_hook=unique)
    if set(value)!={'schema','root','artifacts','maxBytes'} or value['schema']!='polyglot-sdk-media-inputs:m8.4-v1':raise ValueError('SDK_MEDIA_INPUT_SCHEMA')
    result=build(Path(value['root']),value['artifacts'],args.output,value['maxBytes']);print(json.dumps(result,sort_keys=True))


if __name__=='__main__':main()
