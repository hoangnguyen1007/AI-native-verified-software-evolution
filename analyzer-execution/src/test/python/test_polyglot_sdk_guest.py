import importlib.util
from pathlib import Path
import sys
import unittest

ROOT=Path(__file__).resolve().parents[2]/'main/resources/polyglot'
sys.path.insert(0,str(ROOT))
spec=importlib.util.spec_from_file_location('sdk_guest_controls',ROOT/'sdk_guest.py')
guest=importlib.util.module_from_spec(spec);spec.loader.exec_module(guest)


class SdkGuestProtocol(unittest.TestCase):
    def test_explicit_native_scope_probes_exact_required_tools_without_claiming_full_sdk(self):
        registration=dict(sdkRoot='/opt/evo-toolset',runtime={'java':{'path':'/image/java'},'python':{'path':'/image/python'}},probeScope='EXACT_NATIVE_CONTEXTS',probeNames=['swift','swift-parse-shape','swift-typed-shape'],nativeCampaign={'contexts':{'case':{'language':'swift'}}});self.assertEqual(set(registration['probeNames']),set(guest.commands(registration)))
        for bad in (dict(registration,probeNames=['swift']),dict(registration,probeNames=registration['probeNames']+['node']),dict(registration,probeScope='anything'),dict(registration,nativeCampaign={'contexts':{}})):
            with self.assertRaises(ValueError):guest.commands(bad)
    def test_swift_only_scope_does_not_require_an_unrelated_image_java_runtime(self):
        registration=dict(sdkRoot='/opt/evo-toolset',runtime={'python':{'path':'/image/python'}},probeScope='EXACT_NATIVE_CONTEXTS',probeNames=['swift','swift-parse-shape','swift-typed-shape'],nativeCampaign={'contexts':{'case':{'language':'swift'}}});self.assertEqual(set(registration['probeNames']),set(guest.commands(registration)))
    def test_exact_recipe_and_attempt_are_required_with_no_caller_argv(self):
        registration=dict(attempt='a'*32);request=dict(schema='sdk-worker-control-request:m8.4-v1',attempt='a'*32,recipe='probe-versions')
        guest.validate(request,registration)
        for bad in (dict(request,argv=['/bin/sh']),dict(request,recipe='anything'),dict(request,attempt='b'*32)):
            with self.assertRaises(ValueError):guest.validate(bad,registration)

    def test_all_nine_probes_use_fixed_toolsets_and_keep_jvm_classpaths_separate(self):
        registration=dict(sdkRoot='/opt/evo-toolset',runtime={'java':{'path':'/image/java'},'python':{'path':'/image/python'}})
        commands=guest.commands(registration);self.assertEqual(set(('node','go','dotnet','dart','ruby','clang','kotlin','scala','groovy')),set(commands))
        self.assertEqual(['/opt/evo-toolset/go/bin/go','version'],commands['go'])
        self.assertTrue(all('scala-' not in part and 'groovy-' not in part for part in commands['kotlin']))
        self.assertTrue(all('kotlin-' not in part and 'groovy-' not in part for part in commands['scala']))
        self.assertTrue(all('kotlin-' not in part and 'scala-' not in part for part in commands['groovy']))


if __name__=='__main__':unittest.main()
