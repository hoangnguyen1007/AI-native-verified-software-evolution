"""Operator-owned host volume binding and bounded binary log capture. No target execution.
The receipt binds an already initialized volume; this module never initializes/mounts a disk.
"""
import ctypes
import errno
import hashlib
import os
from pathlib import Path, PureWindowsPath
import re
import time
import uuid


def validate_receipt(value, workspace, owner):
    fields={'schema','kind','workspace','path','driveLetter','volumeUniqueId','diskNumber',
            'partitionNumber','capacityBytes','owner','guestDiskAccess'}
    if not isinstance(value,dict) or set(value)!=fields or value['schema']!='hyperv-host-storage:m8.4-v1' or value['kind']!='HOST_STORAGE':
        raise ValueError('INVALID_HOST_STORAGE_RECEIPT')
    if value['guestDiskAccess'] is not False or value['owner']!=owner or not isinstance(owner,str) or not re.fullmatch(r'S-1-[0-9]+(?:-[0-9]+)+',owner):
        raise ValueError('HOST_STORAGE_OWNER_MISMATCH')
    for field,minimum in [('diskNumber',0),('partitionNumber',1),('capacityBytes',1)]:
        if type(value[field]) is not int or not minimum<=value[field]<=9223372036854775807:
            raise ValueError('INVALID_HOST_STORAGE_CAPACITY')
    if not isinstance(value['driveLetter'],str) or not re.fullmatch('[D-Z]',value['driveLetter']):
        raise ValueError('INVALID_HOST_STORAGE_DRIVE')
    for raw in [workspace,value['workspace'],value['path']]:
        if not isinstance(raw,str):raise ValueError('INVALID_HOST_STORAGE_PATH')
        path=PureWindowsPath(raw)
        if not re.fullmatch('[A-Za-z]:',path.drive) or not path.is_absolute() or '..' in path.parts or any(':' in part for part in path.parts[1:]):
            raise ValueError('INVALID_HOST_STORAGE_PATH')
    if PureWindowsPath(value['workspace'])!=PureWindowsPath(workspace) or PureWindowsPath(value['path'])!=PureWindowsPath(workspace)/'.evo/executor/m843-host-storage/host-storage.vhdx':
        raise ValueError('HOST_STORAGE_WORKSPACE_MISMATCH')
    identity=value['volumeUniqueId'];prefix='\\\\?\\Volume{'
    if not isinstance(identity,str) or not identity.startswith(prefix) or not identity.endswith('}\\'):
        raise ValueError('INVALID_HOST_STORAGE_VOLUME_ID')
    if str(uuid.UUID(identity[len(prefix):-2]))!=identity[len(prefix):-2].lower():
        raise ValueError('INVALID_HOST_STORAGE_VOLUME_ID')
    return value


def validate_volume(receipt, actual):
    if (actual['uniqueId'].lower()!=receipt['volumeUniqueId'].lower() or actual['filesystem']!='NTFS'
            or actual['label']!='EVO-M843-HOST' or type(actual['totalBytes']) is not int
            or not 0<actual['totalBytes']<=receipt['capacityBytes']
            or receipt['capacityBytes']-actual['totalBytes']>67108864
            or type(actual['availableBytes']) is not int or not 0<=actual['availableBytes']<=actual['totalBytes']):
        raise RuntimeError('HOST_STORAGE_BINDING_MISMATCH')
    return actual


def windows_volume(root):
    if os.name!='nt':raise RuntimeError('WINDOWS_HOST_STORAGE_REQUIRED')
    from ctypes import wintypes as w
    kernel=ctypes.WinDLL('kernel32',use_last_error=True)
    kernel.GetVolumeNameForVolumeMountPointW.argtypes=[w.LPCWSTR,w.LPWSTR,w.DWORD]
    kernel.GetVolumeNameForVolumeMountPointW.restype=w.BOOL
    kernel.GetVolumeInformationW.argtypes=[w.LPCWSTR,w.LPWSTR,w.DWORD,ctypes.POINTER(w.DWORD),ctypes.POINTER(w.DWORD),ctypes.POINTER(w.DWORD),w.LPWSTR,w.DWORD]
    kernel.GetVolumeInformationW.restype=w.BOOL
    kernel.GetDiskFreeSpaceExW.argtypes=[w.LPCWSTR,*([ctypes.POINTER(ctypes.c_ulonglong)]*3)]
    kernel.GetDiskFreeSpaceExW.restype=w.BOOL
    identity=ctypes.create_unicode_buffer(64);label=ctypes.create_unicode_buffer(261);filesystem=ctypes.create_unicode_buffer(32)
    serial=w.DWORD();component=w.DWORD();flags=w.DWORD();available=ctypes.c_ulonglong();total=ctypes.c_ulonglong();free=ctypes.c_ulonglong()
    if (not kernel.GetVolumeNameForVolumeMountPointW(root,identity,len(identity))
            or not kernel.GetVolumeInformationW(root,label,len(label),ctypes.byref(serial),ctypes.byref(component),ctypes.byref(flags),filesystem,len(filesystem))
            or not kernel.GetDiskFreeSpaceExW(root,ctypes.byref(available),ctypes.byref(total),ctypes.byref(free))):
        raise RuntimeError('HOST_STORAGE_QUERY_UNAVAILABLE')
    return dict(uniqueId=identity.value,label=label.value,filesystem=filesystem.value,totalBytes=total.value,availableBytes=available.value)


class PinnedVolume:
    def __init__(self,receipt,workspace,owner):
        self.receipt=validate_receipt(receipt,workspace,owner)
        self.root=Path(receipt['driveLetter']+':\\')
        self.check()

    def check(self):
        return validate_volume(self.receipt,windows_volume(str(self.root)))

    def contained(self,path):
        path=Path(path).absolute()
        path.relative_to(self.root)
        if '..' in path.parts or any(':' in part for part in path.parts[1:]):raise ValueError('HOST_STORAGE_PATH_ESCAPE')
        for part in (path,*path.parents):
            if part.is_symlink() or (hasattr(part,'is_junction') and part.is_junction()):raise ValueError('HOST_STORAGE_PATH_ALIAS')
        self.check()
        return path


def capture_log(source,sink,limit,check=lambda:None):
    if type(limit) is not int or not 0<=limit<=9223372036854775807:raise ValueError('INVALID_LOG_BUDGET')
    captured=0;observed=0;digest=hashlib.sha256();reason='';secondary=[]
    def report():
        return dict(schema='execution-log-capture:m8.4-v1',capturedBytes=captured,observedAtLeast=observed,
                    digest='sha256:'+digest.hexdigest(),complete=not reason,reason=reason,secondaryReasons=secondary)
    def write_failure(failure):
        return 'HOST_STORAGE_EXHAUSTED' if failure.errno in (errno.ENOSPC,errno.EDQUOT) or getattr(failure,'winerror',None) in (39,112) else 'LOG_WRITE_UNAVAILABLE'
    while True:
        try:check()
        except (ValueError,RuntimeError,OSError):reason='HOST_STORAGE_BINDING_LOST';break
        count=min(65536,limit-captured+1)
        try:raw=source.read(count)
        except OSError as failure:reason='LOG_CAPTURE_DEADLINE' if isinstance(failure,TimeoutError) else 'LOG_SOURCE_UNAVAILABLE';break
        if not isinstance(raw,bytes) or len(raw)>count:reason='LOG_SOURCE_PROTOCOL_VIOLATION';break
        if not raw:break
        if observed>9223372036854775807-len(raw):observed=9223372036854775807;reason='LOG_COUNTER_OVERFLOW';break
        observed+=len(raw)
        wanted=raw[:limit-captured];offset=0
        while offset<len(wanted):
            try:
                check()
            except (ValueError,RuntimeError,OSError):reason='HOST_STORAGE_BINDING_LOST';break
            try:written=sink.write(wanted[offset:])
            except OSError as failure:reason=write_failure(failure);break
            if type(written) is not int or not 0<written<=len(wanted)-offset:reason='LOG_WRITE_PROTOCOL_VIOLATION';break
            digest.update(wanted[offset:offset+written]);captured+=written;offset+=written
        if reason:break
        if len(wanted)!=len(raw):reason='LOG_BUDGET_EXHAUSTED';break
    try:sink.flush()
    except OSError as failure:
        flush_reason=write_failure(failure)
        if reason and reason!=flush_reason:secondary.append(flush_reason)
        else:reason=flush_reason
    return report()


class ChannelLogSource:
    """Drain both SSH streams without accumulating target output in memory."""
    def __init__(self,channel,deadline,clock=time.monotonic,pause=time.sleep):
        self.channel=channel;self.deadline=deadline;self.clock=clock;self.pause=pause;self.stderr_first=False

    def read(self,count):
        while True:
            if self.clock()>=self.deadline:raise TimeoutError('LOG_CAPTURE_DEADLINE')
            streams=[(self.channel.recv_ready,self.channel.recv),(self.channel.recv_stderr_ready,self.channel.recv_stderr)]
            if self.stderr_first:streams.reverse()
            for ready,receive in streams:
                if ready():
                    self.stderr_first=not self.stderr_first
                    return receive(count)
            if self.channel.exit_status_ready():return b''
            self.pause(.01)


def capture_channel_log(channel,path,limit,deadline,volume=None):
    path=volume.contained(path) if volume is not None else Path(path)
    with path.open('xb',buffering=0) as sink:
        report=capture_log(ChannelLogSource(channel,deadline),sink,limit,check=volume.check if volume else lambda:None)
        try:os.fsync(sink.fileno())
        except OSError as failure:
            report['complete']=False
            reason='HOST_STORAGE_EXHAUSTED' if failure.errno in (errno.ENOSPC,errno.EDQUOT) or getattr(failure,'winerror',None) in (39,112) else 'LOG_SYNC_UNAVAILABLE'
            if report['reason'] and report['reason']!=reason:report['secondaryReasons'].append(reason)
            else:report['reason']=reason
        return report
