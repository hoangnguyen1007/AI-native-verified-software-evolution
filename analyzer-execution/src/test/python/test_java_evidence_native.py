"""Transport/input controls only. Dummy bytes never stand in for native acceptance."""
import importlib.util
import json
from pathlib import Path
import shutil
import sys
import tempfile
import unittest
from unittest.mock import patch

sys.dont_write_bytecode = True
spec = importlib.util.spec_from_file_location('native_java_fixture', Path(__file__).with_name('hyperv_java_evidence_fixture.py'))
fixture = importlib.util.module_from_spec(spec)
spec.loader.exec_module(fixture)
recipe = fixture.load_module('native_java_recipe_test', fixture.FIXTURE / 'recipe.py')


class NativeInputBoundaryTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.repo = Path(self.temporary.name)
        self.materials = self.repo / '.evo/executor/m844-native-materials-12345678'
        self.materials.mkdir(parents=True)
        self.sources = self.repo / 'authored-source'
        self.sources.mkdir()
        shutil.copyfile(fixture.FIXTURE / 'recipe.py', self.sources / 'recipe.py')
        (self.sources / 'Input.java').write_text('class Input {}\n')
        self.rows = []
        for name in sorted(recipe.MATERIALS):
            path = self.materials / name
            path.write_bytes(b'unit-contract-data:' + name.encode())
            self.rows.append(dict(path=name, size=path.stat().st_size, digest=fixture.digest(path),
                                  origin='https://repo.maven.apache.org/maven2/authored-contract-data',
                                  publisherSha1='0' * 40, trust='PRODUCER_ASSERTION'))
        self.write_manifest()

    def write_manifest(self):
        (self.materials / 'materials.json').write_text(json.dumps(dict(schema='m844-native-materials:v1', files=self.rows)))

    def plan(self):
        with patch.object(fixture, 'REPO', self.repo), patch.object(fixture, 'FIXTURE', self.sources):
            return fixture.input_plan(self.materials)

    def test_changed_tool_bytes_are_rejected_before_transport(self):
        plan = self.plan()
        self.assertEqual(16, len(plan))  # 13 materials + material manifest + two source files.
        tool = self.materials / 'protoc'
        tool.write_bytes(tool.read_bytes().replace(b'unit', b'fake'))
        with self.assertRaisesRegex(ValueError, 'NATIVE_MATERIAL_CONTENT'):
            self.plan()

    def test_guest_hashing_supports_ubuntu_python_310_without_file_digest(self):
        with patch.object(recipe.hashlib, 'file_digest', side_effect=AssertionError('Python 3.10 has no file_digest')):
            observed = recipe.verify_materials(self.materials)
        self.assertEqual(13, len(observed['files']))

    def test_indexer_is_an_explicit_registered_input_and_closes_context_denominator(self):
        with patch.object(fixture, 'REPO', self.repo), patch.object(fixture, 'FIXTURE', self.sources):
            plan = fixture.input_plan(self.materials, indexer=True)
        tooling = {row['path'] for _, row in plan if row['path'].startswith('tooling/')}
        self.assertEqual({'tooling/CapturedJavacIndex.java', 'tooling/native_index_control.py'}, tooling)
        control = fixture.load_module('native_index_control_test', fixture.INDEX_CONTROL)
        contexts = control.contexts('/work/' + 'a' * 32, '/opt/evo-inputs/' + 'a' * 32)
        self.assertEqual(11, len(contexts))
        self.assertEqual(11, len({row['name'] for row in contexts}))
        self.assertEqual({'custom-broken'}, {row['name'] for row in contexts if row['expectedState'] == 'ERROR'})
        lombok = next(row for row in contexts if row['name'] == 'lombok-client')
        self.assertTrue(lombok['classpath'][0].endswith('/lombok/model/classes'))
        self.assertFalse(any('dependent' in row['name'] for row in contexts))

    def test_material_denominator_cannot_omit_duplicate_or_escape_a_tool(self):
        original = list(self.rows)
        cases = [original[:-1], original + [original[0]],
                 [dict(original[0], path='../controller-ed25519')] + original[1:]]
        for rows in cases:
            with self.subTest(paths=[r['path'] for r in rows]):
                self.rows = rows
                self.write_manifest()
                with self.assertRaisesRegex(ValueError, 'NATIVE_MATERIAL_DENOMINATOR'):
                    self.plan()

    def test_spring_materials_are_an_explicit_all_or_none_native_variant(self):
        for name in ['spring-aop.jar', 'spring-context.jar', 'spring-beans.jar', 'spring-core.jar', 'spring-expression.jar', 'spring-jcl.jar']:
            path = self.materials / name
            path.write_bytes(b'authored-transport-control:' + name.encode())
            self.rows.append(dict(path=name, size=path.stat().st_size, digest=fixture.digest(path),
                                  origin='https://repo.maven.apache.org/maven2/authored-contract-data',
                                  publisherSha1='0' * 40, trust='PRODUCER_ASSERTION'))
        self.write_manifest()
        self.assertEqual(22, len(self.plan()))
        control = fixture.load_module('native_spring_context_control', fixture.INDEX_CONTROL)
        contexts = control.contexts('/work/' + 'a' * 32, '/opt/evo-inputs/' + 'a' * 32, spring=True)
        mapper = next(row for row in contexts if row['name'] == 'mapstruct-mapper')
        producer = next(row for row in contexts if row['name'] == 'custom-producer')
        self.assertTrue(any('/springmapper/' in path for path in mapper['sources']))
        self.assertEqual(4, len(producer['sources']))
        self.rows.pop()
        self.write_manifest()
        with self.assertRaisesRegex(ValueError, 'NATIVE_MATERIAL_DENOMINATOR'):
            self.plan()

    def test_transport_does_not_upload_extra_private_materials_or_unregistered_files(self):
        (self.materials / 'controller-ed25519').write_text('not-a-key; forbidden transport input')
        with self.assertRaisesRegex(ValueError, 'FIXTURE_MATERIAL_DENOMINATOR'):
            self.plan()
        # A different controlled root, rather than deleting or normalizing a failed input set.
        other = self.repo / '.evo/executor/m844-native-materials-87654321'
        other.mkdir()
        for name in recipe.MATERIALS | {'materials.json'}:
            shutil.copyfile(self.materials / name, other / name)
        (self.sources / 'unexpected.bin').write_bytes(b'unregistered')
        with patch.object(fixture, 'REPO', self.repo), patch.object(fixture, 'FIXTURE', self.sources):
            with self.assertRaisesRegex(ValueError, 'FIXTURE_UNREGISTERED_SOURCE'):
                fixture.input_plan(other)


if __name__ == '__main__':
    unittest.main()
