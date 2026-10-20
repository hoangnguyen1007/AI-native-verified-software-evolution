import importlib.util
from pathlib import Path
import tempfile
import unittest

RUNNER = Path(__import__('os').environ['EVO_GUEST_RUNNER_TEST_PATH']) if 'EVO_GUEST_RUNNER_TEST_PATH' in __import__('os').environ else Path(__file__).resolve().parents[2] / 'main/resources/guest/runner.py'

class RunnerValidationTest(unittest.TestCase):
    def test_import_is_inert(self):
        spec = importlib.util.spec_from_file_location('guest_runner', RUNNER)
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        self.assertTrue(callable(module.validate_request))

    def test_duplicate_json_fields_are_rejected(self):
        module = self.load()
        with self.assertRaisesRegex(ValueError, 'DUPLICATE_EXECUTION_FIELD'):
            module.decode_request(b'{"attempt":"a","attempt":"b"}')

    def load(self):
        spec = importlib.util.spec_from_file_location('guest_runner', RUNNER)
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        return module

    def test_registered_command_and_exact_private_workspace(self):
        module = self.load()
        with tempfile.TemporaryDirectory() as tmp:
            base = Path(tmp).resolve()
            root = base / ('a' * 32)
            root.mkdir()
            request = dict(attempt='a' * 32, argv=['/usr/bin/javac','A.java'],
                           memoryBytes=268435456,tasksMax=32,cpuPercent=25,work=str(root))
            self.assertEqual(root, module.validate_request(request, base))
            for change in [dict(work=str(base)),dict(argv=['/bin/sh','-c','id']),
                           dict(memoryBytes=True),dict(tasksMax=0),dict(cpuPercent=101),
                           dict(attempt='../escape'),dict(argv=['/usr/bin/java','x\x00'])]:
                with self.subTest(change=change), self.assertRaises(ValueError):
                    module.validate_request(request | change, base)

    def test_target_environment_is_explicitly_cleared_before_native_tool(self):
        module=self.load()
        command=module.build_command(dict(attempt='a'*32,argv=['/usr/bin/java','-version'],memoryBytes=268435456,tasksMax=32,cpuPercent=25),__import__('pathlib').PurePosixPath('/work')/('a'*32))
        marker=command.index('--')
        target=command[marker+1:]
        self.assertEqual(['/usr/bin/env','-i'],target[:2])
        self.assertEqual(['/usr/bin/java','-version'],target[-2:])
        self.assertTrue(any(value.startswith('HOME=/work/') for value in target))
        self.assertNotIn('JAVA_TOOL_OPTIONS',str(target))

    @unittest.skipIf(__import__('os').name == 'nt', 'POSIX guest symlink conformance')
    def test_symlink_workspace_and_home_are_rejected(self):
        module = self.load()
        with tempfile.TemporaryDirectory() as tmp:
            base=Path(tmp).resolve(); outside=base/'outside'; outside.mkdir()
            root=base/('a'*32); root.symlink_to(outside, target_is_directory=True)
            request=dict(attempt='a'*32,argv=['/usr/bin/java','-version'],memoryBytes=268435456,tasksMax=32,cpuPercent=25,work=str(root))
            with self.assertRaises(ValueError): module.validate_request(request,base)
            root.unlink(); root.mkdir(); (root/'home').symlink_to(outside,target_is_directory=True)
            with self.assertRaises(ValueError): module.validate_request(request,base)

if __name__ == '__main__': unittest.main()
