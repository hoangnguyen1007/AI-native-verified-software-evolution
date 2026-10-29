"""Scoped host-storage observations; these controls never create or change a VM."""
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

SOURCE=Path(__file__).resolve().parents[2]/'main/resources/polyglot/worker-sdk.ps1'
SCRIPT=r'''
param([string]$Source,[string]$Mode,[string]$Missing)
$ErrorActionPreference='Stop'
$tokens=$null;$errors=$null
$ast=[Management.Automation.Language.Parser]::ParseFile($Source,[ref]$tokens,[ref]$errors)
if($errors.Count){throw 'SDK_DRIVER_PARSE_FAILED'}
$definition=$ast.Find({param($node) $node -is [Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -eq 'Get-ObservedDiskIdentity'},$true)
if($null -eq $definition){throw 'SDK_STORAGE_OBSERVER_REQUIRED'}
. ([ScriptBlock]::Create($definition.Extent.Text))
function Get-LocalFileIdentity([string]$Path){
 if($Mode -eq 'present'){return 'ACTUAL_NATIVE_ID'}
 if($Mode -eq 'missing'){[IO.File]::OpenRead($Missing) | Out-Null}
 if($Mode -eq 'permission'){throw [UnauthorizedAccessException]::new('AUTHORED_PERMISSION')}
 if($Mode -eq 'sharing'){throw [IO.IOException]::new('AUTHORED_SHARING',[int]-2147024864)}
 throw 'AUTHORED_UNKNOWN'
}
Get-ObservedDiskIdentity 'C:/registered/unrelated.vhdx' | ConvertTo-Json -Compress
'''


class SdkStorageControls(unittest.TestCase):
    def invoke(self,mode):
        powershell=os.environ.get('POLYGLOT_PWSH') or shutil.which('pwsh')
        if not powershell:raise RuntimeError('Pinned PowerShell runtime required')
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory);script=root/'probe.ps1';script.write_text(SCRIPT,encoding='utf8')
            return subprocess.run([powershell,'-NoProfile','-NonInteractive','-File',str(script),'-Source',str(SOURCE),'-Mode',mode,'-Missing',str(root/'missing.vhdx')],stdout=subprocess.PIPE,stderr=subprocess.PIPE,timeout=30)
    def test_present_native_identity_and_proven_file_absence_remain_distinct(self):
        present=self.invoke('present');self.assertEqual(0,present.returncode,present.stderr.decode(errors='replace'));self.assertEqual('ACTUAL_NATIVE_ID',json.loads(present.stdout)['identity'])
        missing=self.invoke('missing');self.assertEqual(0,missing.returncode,missing.stderr.decode(errors='replace'));row=json.loads(missing.stdout);self.assertEqual('MISSING',row['state']);self.assertEqual('FILE_NOT_FOUND',row['reason']);self.assertIsNone(row['identity'])
    def test_permissions_sharing_and_unknown_failures_cannot_be_treated_as_missing(self):
        for mode in ('permission','sharing','unknown'):
            result=self.invoke(mode);self.assertNotEqual(0,result.returncode);self.assertIn(('AUTHORED_'+mode.upper()).encode(),result.stderr)


if __name__=='__main__':unittest.main()
