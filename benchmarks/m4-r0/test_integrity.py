"""Regression tests for the self-contained M4-R0 integrity boundary."""

import importlib.util
import tempfile
import unittest
from pathlib import Path

import verify_package


ROOT = Path(__file__).resolve().parent
REPO = ROOT.parents[1]


def load_manifest_verifier():
    path = REPO / "docs/reproducibility/m4-r0-2026-09-11/verify_manifest.py"
    spec = importlib.util.spec_from_file_location("m4_r0_verify_manifest", path)
    module = importlib.util.module_from_spec(spec)
    assert spec.loader is not None
    spec.loader.exec_module(module)
    return module


class M4R0IntegrityTest(unittest.TestCase):
    def test_checked_in_package_verifies_without_external_cache(self):
        checks = verify_package.verify_checked_in()

        self.assertEqual(26, checks["artifactLockEntries"])
        self.assertEqual(34, checks["acquiredSourceLockEntries"])
        self.assertEqual(29, checks["catalogRows"])
        self.assertEqual(452, checks["capabilityGapRecords"])

    def test_required_external_cache_reports_every_missing_input(self):
        artifacts, sources = verify_package.load_external_locks()
        with tempfile.TemporaryDirectory() as directory:
            result = verify_package.verify_external_cache(Path(directory), artifacts, sources)

        self.assertEqual("UNAVAILABLE", result["status"])
        self.assertEqual(60, result["expectedInputs"])
        self.assertEqual(60, result["missingCount"])
        self.assertEqual([], result["mismatches"])

    def test_manifest_uses_explicit_cross_platform_text_identity(self):
        verifier = load_manifest_verifier()

        self.assertEqual(
            verifier.normalized_content(b"alpha\r\nbeta\r", "utf8-lf-v1"),
            verifier.normalized_content(b"alpha\nbeta\n", "utf8-lf-v1"),
        )

    def test_manifest_closes_the_checked_in_package_scope(self):
        verifier = load_manifest_verifier()

        result = verifier.verify()

        self.assertGreater(result["filesVerified"], 0)
        self.assertEqual(8, result["historicalExternalReferences"])
        self.assertEqual("m4-r0-deliverable-manifest-v2", result["schema"])


if __name__ == "__main__":
    unittest.main()
