"""Guest-cache recovery protocol controls; never run mounts or target builds on the host."""
import importlib.util
from pathlib import Path
import sys
import unittest
import tempfile
import hashlib
import json
import io
from unittest.mock import patch

resources = Path(__file__).resolve().parents[2] / 'main/resources/mvp'
sys.path.insert(0, str(resources))
spec = importlib.util.spec_from_file_location('fixed_mvp_native_build', resources / 'native_build.py')
native = importlib.util.module_from_spec(spec)
spec.loader.exec_module(native)


class CapturedCacheTest(unittest.TestCase):
    attempt = 'a' * 32

    def test_visible_cache_does_not_mount_anything(self):
        calls = []
        origin, access = native.copy_native_cache(calls.append, lambda command: 0, '/work/' + self.attempt, self.attempt)
        self.assertEqual('DIRECT_IMAGE_PATH', access)
        self.assertTrue(origin.endswith('/m2'))
        self.assertEqual(2, len(calls))
        self.assertFalse(any('mount' in command for command in calls))

    def test_hidden_image_cache_is_read_only_private_and_unmounted_before_return(self):
        calls = []
        _, access = native.copy_native_cache(calls.append, lambda command: 1, '/work/' + self.attempt, self.attempt)
        self.assertEqual('IMAGE_ROOT_READONLY_BIND', access)
        self.assertIn('install -d -m 0700', calls[0])
        self.assertIn('remount,bind,ro', calls[2])
        self.assertIn('/image/work/6c3f72f1f2ef4561adfdab60f493549d/m2', calls[-2])
        self.assertIn('umount ', calls[-1])

    def test_failed_copy_still_unmounts_and_cannot_report_success(self):
        calls = []
        def checked(command):
            calls.append(command)
            if 'cp -a' in command:
                raise OSError('guest disk full')
        with self.assertRaises(OSError):
            native.copy_native_cache(checked, lambda command: 1, '/work/' + self.attempt, self.attempt)
        self.assertIn('umount ', calls[-1])

    def test_transport_failure_and_foreign_work_never_trigger_mount_or_copy(self):
        calls = []
        with self.assertRaises(RuntimeError):
            native.copy_native_cache(calls.append, lambda command: 255, '/work/' + self.attempt, self.attempt)
        with self.assertRaises(ValueError):
            native.copy_native_cache(calls.append, lambda command: 0, '/work/foreign;unsafe', self.attempt)
        self.assertEqual([], calls)

    def bundle(self, workspace):
        root = Path(workspace) / '.evo/executor/m844-core-mvp-cache-abcdef12'
        repository = root / 'repository'
        source = repository / 'org/example/library/1/library-1.jar'
        source.parent.mkdir(parents=True)
        source.write_bytes(b'opaque authored archive bytes')
        row = dict(path=source.relative_to(repository).as_posix(), size=source.stat().st_size, digest='sha256:' + hashlib.sha256(source.read_bytes()).hexdigest())
        value = dict(schema='core-mvp-public-cache:m8.4-v1', repository='https://repo.maven.apache.org/maven2/', files=[row])
        return root, source, value

    def specification(self, root, value):
        raw = json.dumps(value, sort_keys=True).encode()
        (root / 'materials.json').write_bytes(raw)
        return dict(schema='core-mvp-cache-inputs:m8.4-v1', root=str(root), manifest=dict(size=len(raw), digest='sha256:' + hashlib.sha256(raw).hexdigest()))

    def test_registered_materials_require_all_actual_bytes_and_a_closed_artifact_denominator(self):
        with tempfile.TemporaryDirectory() as workspace:
            root, source, value = self.bundle(workspace)
            spec = self.specification(root, value)
            rows = native.material_cache(spec, workspace)
            self.assertEqual([(source, value['files'][0])], rows)
            source.with_suffix('.pom').write_bytes(b'<project/>')
            with self.assertRaisesRegex(ValueError, 'DENOMINATOR'):
                native.material_cache(spec, workspace)

    def test_changed_manifest_or_material_cannot_borrow_the_original_hash(self):
        with tempfile.TemporaryDirectory() as workspace:
            root, source, value = self.bundle(workspace)
            spec = self.specification(root, value)
            source.write_bytes(b'changed')
            with self.assertRaisesRegex(ValueError, 'BYTES'):
                native.material_cache(spec, workspace)
            (root / 'materials.json').write_bytes(b'{}')
            with self.assertRaisesRegex(ValueError, 'MANIFEST'):
                native.material_cache(spec, workspace)

    def test_traversal_duplicate_rows_and_aliases_are_rejected_before_upload(self):
        with tempfile.TemporaryDirectory() as workspace:
            root, source, value = self.bundle(workspace)
            original = dict(value['files'][0])
            for path in ('../secret.jar', '/absolute.jar', 'a//bad.jar', 'a/./bad.jar'):
                value['files'] = [dict(original, path=path)]
                with self.assertRaisesRegex(ValueError, 'ROW'):
                    native.material_cache(self.specification(root, value), workspace)
            value['files'] = [original, original]
            with self.assertRaisesRegex(ValueError, 'ROW'):
                native.material_cache(self.specification(root, value), workspace)
            value['files'] = [original]
            spec = self.specification(root, value)
            with patch.object(native.Path, 'is_symlink', lambda path: path.name == source.name):
                with self.assertRaisesRegex(ValueError, 'ALIAS'):
                    native.material_cache(spec, workspace)

    def test_upload_rechecks_material_changed_after_the_manifest_was_validated(self):
        opened = []
        class Sftp:
            def __enter__(self): return self
            def __exit__(self, *args): pass
            def mkdir(self, path): pass
            def open(self, path, mode):
                opened.append(path)
                return io.BytesIO()
        class Client:
            def open_sftp(self): return Sftp()
        with tempfile.TemporaryDirectory() as workspace:
            root, source, value = self.bundle(workspace)
            rows = native.material_cache(self.specification(root, value), workspace)
            source.write_bytes(b'changed after validation')
            inputs = {}
            with self.assertRaisesRegex(ValueError, 'CHANGED'):
                native.upload_material_cache(Client(), rows, '/work/' + self.attempt, inputs)
            self.assertEqual({}, inputs)
            self.assertEqual([], opened, 'Changed local bytes must be rejected before any remote file is opened')

    def test_batched_guest_input_checks_keep_all_files_and_reject_a_wrong_or_missing_answer(self):
        inputs = {'artifacts/library-' + str(i) + '.jar': hashlib.sha256(str(i).encode()).hexdigest() for i in range(65)}
        work = '/work/' + self.attempt
        calls = []
        def checked(command):
            names = command.split(' -- ', 1)[1].split()
            calls.append(names)
            return '\n'.join(inputs[name[len(work) + 1:]] + '  ' + name for name in names).encode()
        native.verify_inputs(checked, work, inputs)
        self.assertEqual([32, 32, 1], list(map(len, calls)))
        self.assertEqual(65, len({name for batch in calls for name in batch}))
        with self.assertRaisesRegex(ValueError, 'CHANGED'):
            native.verify_inputs(lambda command: b'', work, inputs)
        with self.assertRaisesRegex(ValueError, 'CHANGED'):
            native.verify_inputs(lambda command: checked(command).replace(next(iter(inputs.values())).encode(), b'0' * 64), work, inputs)


if __name__ == '__main__':
    unittest.main()
