import hashlib
import importlib.util
from pathlib import Path
import sys
import unittest

BASE=Path(__file__).resolve().parents[2]/'main/resources/polyglot';sys.path.insert(0,str(BASE))
spec=importlib.util.spec_from_file_location('native_guest_controls',BASE/'native_guest.py');native=importlib.util.module_from_spec(spec);spec.loader.exec_module(native)


class NativeGuestControls(unittest.TestCase):
    def campaign(self):
        return dict(schema='authored-native-campaign:m8.4-v1',assets={},contexts={'c-positive':dict(language='c',request=dict(schema='native-clang-request:m8.4-v1',language='c',dialect='c17',files=[dict(path='main.c',text='int x;',sha256='sha256:'+hashlib.sha256(b'int x;').hexdigest())]))})
    def test_registered_source_membership_and_actual_asset_roles_are_closed(self):
        value=self.campaign();native.validate(value)
        with self.assertRaises(ValueError):native.validate(dict(value,assets={'arbitrary-command':{}}))
        value['contexts']['c-positive']['language']='python';value['contexts']['c-positive']['request'].update(language='python',dialect='3.10',schema='native-python-request:m8.4-v1')
        with self.assertRaises(ValueError):native.validate(value)
        value['assets']={'pyright':{}};native.validate(value)
    def test_source_alias_escape_and_changed_bytes_are_rejected(self):
        for path in ('../escape','C:/host','a\\b','/root','a//b'):
            value=self.campaign();value['contexts']['c-positive']['request']['files'][0]['path']=path
            with self.assertRaises(ValueError):native.validate(value)
        value=self.campaign();value['contexts']['c-positive']['request']['files'][0]['text']='changed'
        with self.assertRaises(ValueError):native.validate(value)
    def test_fixed_argv_selects_no_target_entrypoint(self):
        registration=dict(sdkRoot='/opt/evo-toolset',runtime={'python':{'path':'/usr/bin/python3'}},preparedNative={'contexts':{'c-positive':{'language':'c','request':{'path':'/opt/control/request.json'}}},'extras':{},'pythonWrapper':{}},nativeCampaign={'assets':{}})
        argv=native.command(registration,'/opt/control','c-positive');self.assertEqual('/usr/bin/python3',argv[0]);self.assertIn('/opt/control/clang_engine.py',argv);self.assertIn('-I',argv)
        registration['preparedNative']['contexts']['c-positive']['language']='shell'
        with self.assertRaises(ValueError):native.command(registration,'/opt/control','c-positive')
    def test_physical_source_layout_is_stable_across_attempts_and_binds_source_scope(self):
        request=dict(scope='sha256:'+'1'*64,files=[dict(path='lib.rs',text='pub fn path()->&\'static str { file!() }',sha256='sha256:'+'2'*64)])
        first=native.source_location(request);self.assertEqual(first,native.source_location(dict(request)))
        self.assertEqual('/opt/evo-native-inputs',first.parent.parent.as_posix())
        changed=dict(request,scope='sha256:'+'3'*64);self.assertNotEqual(first,native.source_location(changed))
        changed=dict(request,files=[dict(request['files'][0],text='changed')]);self.assertNotEqual(first,native.source_location(changed))
