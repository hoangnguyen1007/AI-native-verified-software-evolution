import importlib.util
from pathlib import Path
import sys
import unittest

SOURCE=Path(__file__).resolve().parents[2]/'main/resources/polyglot'
sys.path.insert(0,str(SOURCE))
spec=importlib.util.spec_from_file_location('jvm_guest_controls',SOURCE/'jvm_guest.py')
guest=importlib.util.module_from_spec(spec);spec.loader.exec_module(guest)


class RegisteredJvmControls(unittest.TestCase):
    def test_controller_never_uses_host_windows_path_separator_in_guest_commands(self):
        spec=importlib.util.spec_from_file_location('sdk_worker_path_control',SOURCE/'sdk_worker.py')
        worker=importlib.util.module_from_spec(spec);spec.loader.exec_module(worker)
        self.assertEqual('/work/'+'a'*32+'/controls/platform',worker.remote_staging('a'*32,'platform'))
        with self.assertRaises(ValueError):worker.remote_staging('a'*32,'x;whoami')
    def test_only_fixed_entrypoints_and_separate_native_classpaths_are_selected(self):
        registration={'sdkRoot':'/opt/evo-toolset','runtime':{'java':{'path':'/usr/lib/jvm/pinned/bin/java'}}}
        campaign={'platform':{'path':'/opt/control/platform.jar'},'adapters':{'path':'/opt/control/adapters.jar'}}
        kotlin=guest.command(registration,campaign,'kotlin','/opt/input/request.json')
        scala=guest.command(registration,campaign,'scala','/opt/input/request.json')
        self.assertEqual('evo.polyglot.KotlinEngine',kotlin[-2]);self.assertNotIn('scala-library',kotlin[-3]);self.assertNotIn('kotlin-stdlib',scala[-3])
        self.assertEqual('evo.polyglot.ScalaEngine',scala[-2])
        with self.assertRaises(KeyError):guest.command(registration,campaign,'arbitrary-main','/input')

    def test_request_handles_and_closed_context_denominator_are_mandatory(self):
        value={'schema':'registered-jvm-campaign:m8.4-v1','platform':{},'adapters':{},'contexts':{'good':{'language':'scala','request':{'path':'/input','size':1,'digest':'sha256:'+'a'*64}}}}
        guest.validate(value)
        for name in ('../escape','good;whoami'):
            with self.assertRaises(ValueError):guest.validate(dict(value,contexts={name:value['contexts']['good']}))
        with self.assertRaises(ValueError):guest.validate(dict(value,contexts={}))
        with self.assertRaises(ValueError):guest.validate(dict(value,argv=['/bin/sh']))
