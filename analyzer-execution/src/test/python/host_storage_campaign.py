"""Trusted platform I/O fixture on an explicitly initialized private host-only volume.
The saturation payload is a newly created OS delete-on-close temporary file, never existing data.
No guest filesystem is mounted or read, and no target script runs on the host.
"""
import argparse
import errno
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import tempfile
import time
import types
import uuid


def main():
    parser=argparse.ArgumentParser()
    parser.add_argument('--receipt',type=Path,required=True)
    parser.add_argument('--workspace',required=True)
    parser.add_argument('--owner',required=True)
    parser.add_argument('--result-root',type=Path,required=True)
    parser.add_argument('--retained-probe-root',type=Path,action='append',default=[])
    args=parser.parse_args()
    library=Path(__file__).resolve().parents[2]/'main/resources/hyperv/host_storage.py'
    library_source=library.read_bytes()
    m=types.ModuleType('host_storage_campaign_library')
    exec(compile(library_source,str(library),'exec'),m.__dict__)
    raw=args.receipt.read_bytes()
    def unique(pairs):
        value={}
        for key,item in pairs:
            if key in value:raise ValueError('DUPLICATE_HOST_STORAGE_FIELD')
            value[key]=item
        return value
    if len(raw)>16384:raise ValueError('HOST_STORAGE_RECEIPT_WORKER_REQUIRED')
    receipt=json.loads(raw,object_pairs_hook=unique)
    volume=m.PinnedVolume(receipt,args.workspace,args.owner)
    destination=args.result_root.resolve()
    if not destination.is_dir() or destination.drive.lower()==volume.root.drive.lower():raise ValueError('EXTERNAL_CONTROL_JOURNAL_REQUIRED')
    before=volume.check()
    # Do not consume a shared/in-use volume or saturate beneath any active worker namespace.
    retained=set()
    for prior in args.retained_probe_root:
        prior=volume.contained(prior)
        if prior.parent!=volume.root or not prior.name.startswith('storage-probe-'):raise ValueError('INVALID_RETAINED_PROBE_ROOT')
        for entry in prior.iterdir():
            volume.contained(entry)
            if not entry.is_file() or entry.name not in {'bounded.log','full-volume.log','resident-volume.log'} or entry.stat().st_size>65536:
                raise ValueError('RETAINED_PROBE_CONTENT_UNVERIFIED')
        retained.add(prior.name)
    existing=[path.name for path in volume.root.iterdir() if path.name not in {'System Volume Information','$RECYCLE.BIN'}|retained]
    if existing:raise RuntimeError('EXCLUSIVE_EMPTY_HOST_VOLUME_REQUIRED')
    root=volume.root/('storage-probe-'+uuid.uuid4().hex)
    volume.contained(root);root.mkdir(mode=0o700)
    def record(name,value):
        with (destination/name).open('x',encoding='utf-8') as out:
            json.dump(value,out,sort_keys=True);out.flush();os.fsync(out.fileno())
    with (destination/'library-source.py').open('xb') as source:source.write(library_source)
    with (destination/'campaign-source.py').open('xb') as source:source.write(Path(__file__).read_bytes())
    record('intent.json',dict(schema='authored-host-storage-campaign:m8.4-v1',receiptDigest='sha256:'+hashlib.sha256(raw).hexdigest(),
                              libraryDigest='sha256:'+hashlib.sha256(library_source).hexdigest(),
                              volumeId=receipt['volumeUniqueId'],root=str(root),before=before,qualified=False))
    class Repeating:
        def read(self,count):return b'x'*count
    with volume.contained(root/'bounded.log').open('xb',buffering=0) as out:
        bounded=m.capture_log(Repeating(),out,65536,check=volume.check);os.fsync(out.fileno())
    if bounded['complete'] or bounded['reason']!='LOG_BUDGET_EXHAUSTED' or bounded['capturedBytes']!=65536 or bounded['observedAtLeast']!=65537:
        raise RuntimeError('LOG_LIMIT_NOT_ENFORCED')
    if (root/'bounded.log').read_bytes()!=b'x'*65536:raise RuntimeError('LOG_PREFIX_ORACLE_MISMATCH')
    record('bounded-log.json',bounded)
    started=time.monotonic();written=0;error=None;full_capture=None
    def full(failure):return failure.errno in (errno.ENOSPC,errno.EDQUOT) or getattr(failure,'winerror',None) in (39,112)
    with volume.contained(root/'full-volume.log').open('xb',buffering=0) as sink, volume.contained(root/'resident-volume.log').open('xb',buffering=0) as resident:
        with tempfile.TemporaryFile(dir=volume.contained(root),prefix='owned-delete-on-close-',buffering=0) as scratch:
            attributes=os.fstat(scratch.fileno()).st_file_attributes
            if attributes & (0x200|0x800):raise RuntimeError('NONSPARSE_UNCOMPRESSED_PROBE_REQUIRED')
            quantum=b'\xa5'*8388608
            for size in (8388608,4096):
                while True:
                    if time.monotonic()-started>360 or written>receipt['capacityBytes']:
                        raise RuntimeError('HOST_STORAGE_SATURATION_POLICY_EXCEEDED')
                    volume.check()
                    try:
                        count=scratch.write(quantum[:size])
                        if type(count) is not int or not 0<count<=size:raise RuntimeError('HOST_STORAGE_PROBE_SHORT_WRITE')
                        written+=count
                    except OSError as failure:
                        if not full(failure):raise
                        error=dict(errno=failure.errno,winerror=getattr(failure,'winerror',None))
                        break
            try:os.fsync(scratch.fileno())
            except OSError as failure:
                if not full(failure):raise
            saturated=volume.check()
            import io
            small_capture=m.capture_log(io.BytesIO(b'capture-after-full'),resident,1024,check=volume.check)
            full_capture=m.capture_log(io.BytesIO(b'x'*65536),sink,65536,check=volume.check)
            record('full-volume-observation.json',dict(writtenBytes=written,error=error,volume=saturated,residentLogCapture=small_capture,logCapture=full_capture,qualified=False))
            if error is None or written<before['availableBytes']-33554432 or full_capture['reason']!='HOST_STORAGE_EXHAUSTED':
                raise RuntimeError('PHYSICAL_VOLUME_EXHAUSTION_NOT_ESTABLISHED')
    # Only the OS-owned new scratch file is automatically released. Retained probe logs/results stay.
    deadline=time.monotonic()+10
    while True:
        after=volume.check()
        if after['availableBytes']>=before['availableBytes']-1048576:break
        if time.monotonic()>=deadline:raise RuntimeError('TEMPORARY_PROBE_SPACE_NOT_RESTORED')
        time.sleep(.1)
    result=dict(schema='authored-host-storage-result:m8.4-v1',volumeId=receipt['volumeUniqueId'],
                boundedLogPassed=True,physicalExhaustionPassed=True,fullVolumeCaptureRejected=True,
                temporarySpaceRestored=True,writtenBytes=written,before=before,after=after,
                elapsedMillis=int((time.monotonic()-started)*1000),qualified=False)
    record('result.json',result)
    print(json.dumps(result,sort_keys=True))


if __name__=='__main__':main()
