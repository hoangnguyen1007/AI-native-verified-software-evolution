import os
from pathlib import Path
import unittest
import zipfile


class PackagedResourcesTest(unittest.TestCase):
    @unittest.skipUnless(os.environ.get('EVO_EXECUTION_JAR'), 'Set EVO_EXECUTION_JAR after the module package build')
    def test_incremental_jar_contains_exact_tools_without_host_python_bytecode(self):
        resources = Path(__file__).resolve().parents[2] / 'main/resources'
        with zipfile.ZipFile(os.environ['EVO_EXECUTION_JAR']) as archive:
            unexpected = [name for name in archive.namelist()
                          if '__pycache__/' in name or name.endswith(('.pyc', '.pyo'))]
            self.assertEqual([], unexpected)
            sources = sorted(path for path in resources.rglob('*') if path.is_file() and path.suffix in {'.py', '.ps1'})
            self.assertTrue(sources)
            for source in sources:
                with self.subTest(tool=source.name):
                    self.assertEqual(source.read_bytes(), archive.read(source.relative_to(resources).as_posix()))


if __name__ == '__main__':
    unittest.main()
