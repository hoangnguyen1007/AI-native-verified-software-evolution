import errno
import hashlib
import importlib.util
import io
from pathlib import Path
import unittest
import tempfile


def load():
    path=Path(__file__).resolve().parents[2]/'main/resources/hyperv/host_storage.py'
    spec=importlib.util.spec_from_file_location('host_storage_test',path)
    module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module)
    return module


def receipt():
    return dict(schema='hyperv-host-storage:m8.4-v1',kind='HOST_STORAGE',workspace=r'D:\platform',
                path=r'D:\platform\.evo\executor\m843-host-storage\host-storage.vhdx',driveLetter='Z',
                volumeUniqueId='\\\\?\\Volume{07d9702a-fc79-4b44-ac05-d0b62c436064}\\',diskNumber=2,
                partitionNumber=2,capacityBytes=8589934592,owner='S-1-5-21-1-2-3-1002',guestDiskAccess=False)


class HostStorageTest(unittest.TestCase):
    def test_flush_failure_cannot_erase_an_already_observed_log_overflow(self):
        m=load()
        class Sink(io.BytesIO):
            def flush(self):raise OSError(errno.ENOSPC,'authored flush failure')
        result=m.capture_log(io.BytesIO(b'abcde'),Sink(),4)
        self.assertEqual('LOG_BUDGET_EXHAUSTED',result['reason'])
        self.assertEqual(['HOST_STORAGE_EXHAUSTED'],result['secondaryReasons'])

    def test_channel_capture_drains_both_streams_and_never_overwrites_an_existing_log(self):
        m=load()
        class Channel:
            def __init__(self):self.out=bytearray(b'abc');self.err=bytearray(b'XYZ')
            def recv_ready(self):return bool(self.out)
            def recv_stderr_ready(self):return bool(self.err)
            def recv(self,count):raw=bytes(self.out[:count]);del self.out[:count];return raw
            def recv_stderr(self,count):raw=bytes(self.err[:count]);del self.err[:count];return raw
            def exit_status_ready(self):return True
        with tempfile.TemporaryDirectory() as temp:
            path=Path(temp)/'native.log'
            result=m.capture_channel_log(Channel(),path,4,__import__('time').monotonic()+5)
            self.assertEqual(b'abcX',path.read_bytes());self.assertEqual('LOG_BUDGET_EXHAUSTED',result['reason'])
            self.assertEqual(5,result['observedAtLeast'])
            with self.assertRaises(FileExistsError):m.capture_channel_log(Channel(),path,100,__import__('time').monotonic()+5)
            self.assertEqual(b'abcX',path.read_bytes())

    def test_channel_deadline_cannot_be_renewed_by_output(self):
        m=load()
        class Channel:
            def recv_ready(self):return True
            def recv_stderr_ready(self):return False
            def recv(self,count):return b'x'
            def exit_status_ready(self):return False
        source=m.ChannelLogSource(Channel(),1,clock=lambda:1,pause=lambda seconds:self.fail('expired capture must not wait'))
        result=m.capture_log(source,io.BytesIO(),100)
        self.assertFalse(result['complete']);self.assertEqual(0,result['capturedBytes'])
        self.assertEqual('LOG_CAPTURE_DEADLINE',result['reason'])

    def test_receipt_rejects_foreign_storage_coercion_aliases_and_unknown_fields(self):
        m=load();value=receipt()
        self.assertEqual(value,m.validate_receipt(value,r'D:\platform',value['owner']))
        for change in [dict(guestDiskAccess=True),dict(capacityBytes=True),dict(capacityBytes=0),
                       dict(diskNumber=-1),dict(partitionNumber=0),dict(driveLetter='C'),
                       dict(volumeUniqueId='Z:\\'),dict(owner='S-1-5-18'),dict(extra='ignored'),
                       dict(path=r'D:\platform\..\foreign.vhdx'),dict(workspace=r'D:\other')]:
            with self.subTest(change=change),self.assertRaises(ValueError):
                m.validate_receipt(value|change,r'D:\platform',value['owner'])

    def test_volume_binding_rejects_drive_reuse_wrong_filesystem_or_geometry(self):
        m=load();value=receipt();actual=dict(uniqueId=value['volumeUniqueId'],label='EVO-M843-HOST',filesystem='NTFS',
                                          totalBytes=8572104704,availableBytes=8538030080)
        m.validate_volume(value,actual)
        for change in [dict(uniqueId='foreign'),dict(filesystem='FAT32'),dict(label='USER-DATA'),
                       dict(totalBytes=17179869184),dict(totalBytes=1),dict(availableBytes=-1)]:
            with self.subTest(change=change),self.assertRaises(RuntimeError):m.validate_volume(value,actual|change)

    def test_log_limit_preserves_exact_prefix_and_distinguishes_eof_at_boundary(self):
        m=load()
        for raw,limit,complete in [(b'',0,True),(b'abcd',4,True),(b'abcde',4,False),(b'a',0,False)]:
            with self.subTest(raw=raw,limit=limit):
                source=io.BytesIO(raw);sink=io.BytesIO();result=m.capture_log(source,sink,limit)
                self.assertEqual(raw[:limit],sink.getvalue());self.assertEqual(complete,result['complete'])
                self.assertEqual('sha256:'+hashlib.sha256(raw[:limit]).hexdigest(),result['digest'])
                self.assertEqual(min(len(raw),limit+1),result['observedAtLeast'])
                self.assertEqual('' if complete else 'LOG_BUDGET_EXHAUSTED',result['reason'])
        for limit in [True,-1,9223372036854775808]:
            with self.assertRaises(ValueError):m.capture_log(io.BytesIO(),io.BytesIO(),limit)

    def test_short_writes_and_disk_full_never_become_complete_capture(self):
        m=load()
        class Sink(io.BytesIO):
            def write(self,raw):
                if self.tell()==3:raise OSError(errno.ENOSPC,'authored full volume')
                return super().write(raw[:min(2,3-self.tell())])
        sink=Sink();result=m.capture_log(io.BytesIO(b'abcdef'),sink,100)
        self.assertEqual(b'abc',sink.getvalue());self.assertEqual(3,result['capturedBytes'])
        self.assertFalse(result['complete']);self.assertEqual('HOST_STORAGE_EXHAUSTED',result['reason'])
        self.assertEqual('sha256:'+hashlib.sha256(b'abc').hexdigest(),result['digest'])

    def test_binding_loss_and_source_errors_preserve_partial_evidence(self):
        m=load()
        class Source:
            def __init__(self):self.calls=0
            def read(self,count):
                self.calls+=1
                if self.calls==1:return b'ab'
                raise OSError('authored transport loss')
        result=m.capture_log(Source(),io.BytesIO(),100)
        self.assertFalse(result['complete']);self.assertEqual('LOG_SOURCE_UNAVAILABLE',result['reason'])
        self.assertEqual(2,result['capturedBytes'])
        def lost():raise RuntimeError('authored drive replacement')
        sink=io.BytesIO();result=m.capture_log(io.BytesIO(b'abc'),sink,100,check=lost)
        self.assertEqual(b'',sink.getvalue());self.assertEqual('HOST_STORAGE_BINDING_LOST',result['reason'])


if __name__=='__main__':unittest.main()
