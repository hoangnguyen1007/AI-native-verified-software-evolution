import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

SOURCE=Path(__file__).resolve().parents[2]/'main/resources/polyglot/provision-sdk.ps1'


class SdkProvisionControls(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.powershell=os.environ.get('POLYGLOT_PWSH') or shutil.which('pwsh')
        if not cls.powershell:raise RuntimeError('Explicit PowerShell runtime required for SDK provision controls')

    def request(self,root,readonly=True):
        media=root/'tools.iso';raw=bytearray(34816);raw[32768:32775]=b'\x01CD001\x01';media.write_bytes(raw)
        if readonly:media.chmod(0o444)
        value=dict(schema='hyperv-polyglot-provision-request:m8.4-v1',attempt='01234567-0123-4123-8123-0123456789ab',workspace=str(root),launch='sha256:'+'a'*64,fence=1,driverPin='sha256:'+'b'*64,baseRoot='',disk='',images=[],seed={},network={},resources={},tools=dict(root=str(root),path=str(media),size=len(raw),digest='sha256:'+hashlib.sha256(raw).hexdigest()))
        return media,value

    def invoke(self,root,value,action):
        request=root/'request.json';request.write_text(json.dumps(value),encoding='utf8');pin='sha256:'+hashlib.sha256(request.read_bytes()).hexdigest()
        return subprocess.run([self.powershell,'-NoProfile','-NonInteractive','-File',str(SOURCE),'-Action',action,'-Request',str(request),'-RequestPin',pin],stdout=subprocess.PIPE,stderr=subprocess.PIPE,timeout=30)

    def test_media_hash_is_checked_before_any_hyperv_mutation(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory);media,value=self.request(root);value['tools']['digest']='sha256:'+'0'*64
            try:
                result=self.invoke(root,value,'Prepare');self.assertNotEqual(0,result.returncode);self.assertIn(b'PROVISION_PIN_MISMATCH',result.stderr);self.assertFalse(list(root.glob('owner-*')))
            finally:media.chmod(0o666)

    def test_bytes_inspection_is_readonly_and_does_not_claim_execution_qualification(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory);media,value=self.request(root)
            try:
                result=self.invoke(root,value,'ValidateMedia');self.assertEqual(0,result.returncode,result.stderr.decode(errors='replace'));out=json.loads(result.stdout);self.assertFalse(out['qualified']);self.assertEqual('PINNED_TOOL_BYTES_ONLY',out['trust']);self.assertFalse(list(root.glob('owner-*')))
            finally:media.chmod(0o666)

    def test_writable_media_or_old_request_schema_is_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory);media,value=self.request(root,False);result=self.invoke(root,value,'ValidateMedia');self.assertNotEqual(0,result.returncode);self.assertIn(b'SDK_MEDIA_NOT_READONLY',result.stderr)
            value['schema']='hyperv-provision-request:m8.4-v1';result=self.invoke(root,value,'ValidateMedia');self.assertNotEqual(0,result.returncode);self.assertIn(b'INVALID_PROVISION_SCOPE',result.stderr)


if __name__=='__main__':unittest.main()
