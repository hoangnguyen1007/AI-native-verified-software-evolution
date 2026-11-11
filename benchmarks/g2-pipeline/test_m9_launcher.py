"""Exercise the real Windows PowerShell -File boundary with a data-only Python spy."""
import json
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest

SCRIPT = Path(__file__).resolve().parents[1] / "run-g2-benchmark.ps1"


@unittest.skipUnless(sys.platform == "win32" and shutil.which("powershell"),
                     "requires Windows PowerShell 5.1; Python path contracts run independently")
class LauncherTest(unittest.TestCase):
    def invoke(self, root, *arguments):
        script = root / "checkout with spaces/benchmarks/run-g2-benchmark.ps1"
        script.parent.mkdir(parents=True)
        shutil.copyfile(SCRIPT, script)
        helper = script.parent / "g2-pipeline/m9_protocol.py"
        helper.parent.mkdir()
        helper.write_text(
            "import json,pathlib,sys\n"
            "a=sys.argv; p=pathlib.Path(a[a.index('--registry')+1])\n"
            "print(json.dumps({'registry':str(p.resolve()),'exists':p.is_file(),'args':a[1:]}))\n"
            "sys.exit(0 if p.is_file() else 17)\n", encoding="utf-8")
        default = script.parent.parent / "docs/tasks/m9/registry.json"
        default.parent.mkdir(parents=True)
        default.write_text("{}", encoding="utf-8")
        completed = subprocess.run(["powershell", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File",
                                    str(script), "-M9Preflight", "-Python", sys.executable, *arguments],
                                   cwd=root, capture_output=True, text=True, timeout=15)
        return completed, default

    def test_file_invocation_resolves_default_registry_from_script_not_cwd(self):
        with tempfile.TemporaryDirectory() as directory:
            result, expected = self.invoke(Path(directory))
            self.assertEqual(0, result.returncode, result.stdout + result.stderr)
            self.assertEqual(expected.resolve(), Path(json.loads(result.stdout)["registry"]))

    def test_file_invocation_preserves_explicit_registry_and_propagates_failure(self):
        with tempfile.TemporaryDirectory() as directory:
            result, _ = self.invoke(Path(directory), "-M9Registry", "missing registry.json")
            self.assertNotEqual(0, result.returncode)
            self.assertFalse(json.loads(result.stdout)["exists"])

    def test_file_invocation_forwards_explicit_registry_and_root_bindings_with_spaces(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            explicit = root / "explicit registry.json"
            explicit.write_text("{}", encoding="utf-8")
            bindings = root / "local roots.json"
            bindings.write_text("{}", encoding="utf-8")
            result, _ = self.invoke(root, "-M9Registry", str(explicit), "-M9RootsFile", str(bindings))
            self.assertEqual(0, result.returncode, result.stdout + result.stderr)
            data = json.loads(result.stdout)
            self.assertEqual(str(explicit.resolve()), data["registry"])
            self.assertEqual(str(bindings), data["args"][data["args"].index("--roots-file") + 1])


if __name__ == "__main__":
    unittest.main()
