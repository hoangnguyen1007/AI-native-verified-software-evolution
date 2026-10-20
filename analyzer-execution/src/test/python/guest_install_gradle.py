#!/usr/bin/python3
"""Install one publisher-pinned Gradle tool inside an owned candidate guest. Never run on controller."""
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import re
import shutil
import stat
import sys
import tempfile
import zipfile

archive=Path(sys.argv[1])
if os.geteuid()!=0 or not re.fullmatch(r'/tmp/evo-gradle-[a-f0-9]{32}\.zip',str(archive)):
    raise SystemExit('INVALID_GUEST_TOOL_INSTALL')
expected='7988ed071b2a07900e2ec715fca15c6ab72bce6db433af301fa7fa7e9407bd24'
digest=hashlib.sha256()
with archive.open('rb') as source:
    for block in iter(lambda:source.read(65536),b''):digest.update(block)
if digest.hexdigest()!=expected:raise SystemExit('TOOL_DIGEST_MISMATCH')
base=Path('/opt/evo-tools')
base.mkdir(mode=0o755,exist_ok=True)
if any(p.is_symlink() for p in [base,*base.parents]):raise SystemExit('UNSAFE_TOOL_ROOT')
destination=base/'gradle-8.14.6'
if destination.exists():raise SystemExit('TOOL_ALREADY_EXISTS')
with tempfile.TemporaryDirectory(prefix='.gradle-stage-',dir=base) as staging:
    with zipfile.ZipFile(archive) as entries:
        total=0
        for entry in entries.infolist():
            parts=PurePosixPath(entry.filename).parts
            mode=stat.S_IFMT(entry.external_attr>>16)
            if not parts or parts[0]!='gradle-8.14.6' or any(p in ('.','..') for p in parts) or '\\' in entry.filename or mode not in (0,stat.S_IFDIR,stat.S_IFREG):
                raise SystemExit('UNSAFE_TOOL_ARCHIVE')
            total+=entry.file_size
            if total>536870912:raise SystemExit('TOOL_EXTRACTION_WORKER_REQUIRED')
            path=Path(staging).joinpath(*parts)
            if entry.is_dir():path.mkdir(parents=True,exist_ok=True)
            else:
                path.parent.mkdir(parents=True,exist_ok=True)
                with entries.open(entry) as source,path.open('xb') as output:shutil.copyfileobj(source,output,65536)
                path.chmod(0o444)
        extracted=Path(staging)/'gradle-8.14.6'
        (extracted/'bin/gradle').chmod(0o555)
        for directory in sorted((p for p in extracted.rglob('*') if p.is_dir()),key=lambda p:len(p.parts),reverse=True):directory.chmod(0o555)
        extracted.chmod(0o555)
        extracted.rename(destination)
print(json.dumps(dict(schema='authored-gradle-tool-install:m8.4-v1',version='8.14.6',sha256=expected,expandedBytes=total)))
