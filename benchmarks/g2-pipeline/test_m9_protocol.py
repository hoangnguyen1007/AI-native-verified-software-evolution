"""Data-only preflight contracts; no repository builds or analyzer execution."""
import importlib.util
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
from contextlib import redirect_stdout
import io
import json
from types import SimpleNamespace

spec = importlib.util.spec_from_file_location("m9", Path(__file__).with_name("m9_protocol.py"))
m9 = importlib.util.module_from_spec(spec)
spec.loader.exec_module(m9)


class ProtocolTest(unittest.TestCase):
    def test_census_accounts_for_unknown_binary_generated_and_context_ownership(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            for name, data in {"pom.xml": b"<project/>", "a/pom.xml": b"<project/>",
                               "a/src/main/java/A.java": b"class A {}", "a/src/test/java/T.java": b"class T {}",
                               "a/target/generated-sources/g/G.java": b"class G {}",
                               "odd/A.xyz": b"\x00\xff", "vendor/V.go": b"package v"}.items():
                p = root / name
                p.parent.mkdir(parents=True, exist_ok=True)
                p.write_bytes(data)
            rows, contexts = m9.census(root)
            self.assertEqual(7, len(rows))
            by_path = {r["path"]: r for r in rows}
            self.assertEqual("MAIN", by_path["a/src/main/java/A.java"]["sourceSet"])
            self.assertEqual("TEST", by_path["a/src/test/java/T.java"]["sourceSet"])
            self.assertEqual("GENERATED", by_path["a/target/generated-sources/g/G.java"]["sourceSet"])
            self.assertEqual("VENDOR", by_path["vendor/V.go"]["sourceSet"])
            self.assertEqual("UNCLASSIFIED", by_path["odd/A.xyz"]["sourceSet"])
            self.assertEqual("a", by_path["a/src/main/java/A.java"]["owner"])
            self.assertEqual(2, len(contexts))
            self.assertEqual(rows, m9.census(root)[0])

    def test_same_size_changed_bytes_invalidate_inventory(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "a").write_bytes(b"old")
            before = m9.digest(m9.census(root))
            (root / "a").write_bytes(b"new")
            self.assertNotEqual(before, m9.digest(m9.census(root)))

    def test_missing_case_or_oracle_and_duplicate_claim_are_rejected(self):
        claim = {"id": "S", "oracle": "independent source", "denominator": "all labels",
                 "cases": ["fixture"], "criterion": "zero mismatch", "owner": "M9.2"}
        self.assertEqual([], m9.claim_errors([claim]))
        self.assertTrue(m9.claim_errors([claim, claim]))
        self.assertTrue(m9.claim_errors([{**claim, "oracle": ""}]))
        self.assertTrue(m9.claim_errors([{**claim, "cases": []}]))

    def test_changed_raw_capture_cannot_be_reused(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "capture"
            path.write_bytes(b"old")
            pin = {"path": str(path), "sha256": m9.file_sha(path)}
            self.assertTrue(m9.matches(pin))
            path.write_bytes(b"new")
            self.assertFalse(m9.matches(pin))

    def test_preflight_rejects_changed_candidate_or_missing_census_and_keeps_denominator(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            content = root / "bytes"
            content.write_bytes(b"pinned")
            pin = {"path": str(content), "sha256": m9.file_sha(content)}
            identity = {"commit": "a" * 40, "runtimeSourceSha256": "source"}
            registry = {"schema": "m9-evaluation-protocol-v1", "frozen": True, "policy": {"passiveThresholdPerMille": 950},
                        "candidate": {**identity, "jar": pin},
                        "claims": [{"id": "I", "oracle": "source", "denominator": "all", "cases": ["positive", "negative"], "criterion": "exact", "owner": "M9.2"}],
                        "corpus": [{"id": "one", "commit": "a" * 40, "inventory": pin, "contexts": pin}]}
            path = root / "registry.json"
            path.write_text(json.dumps(registry))
            args = SimpleNamespace(registry=path, verify_sources=False)
            with patch.object(m9, "platform_identity", return_value=identity), redirect_stdout(io.StringIO()):
                self.assertEqual(0, m9.preflight(args))
                content.write_bytes(b"edited")
                self.assertEqual(1, m9.preflight(args))
                content.unlink()  # Test-owned temporary file only.
                self.assertEqual(1, m9.preflight(args))

    def test_orphan_files_get_their_own_open_context_disposition(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "module").mkdir()
            (root / "module/pom.xml").write_text("<project/>")
            (root / "custom.xyz").write_bytes(b"input")
            rows, contexts = m9.census(root)
            self.assertEqual({"module", "UNASSIGNED"}, {c["owner"] for c in contexts})
            self.assertEqual(2, len(rows))

    def test_freeze_refuses_an_existing_destination(self):
        with tempfile.TemporaryDirectory() as directory:
            with self.assertRaisesRegex(ValueError, "already exists"):
                m9.freeze(SimpleNamespace(output=directory))

    def test_portable_registry_relocates_inputs_but_rejects_wrong_bytes(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            repo, evidence = root / "relocated checkout", root / "relocated evidence"
            repo.mkdir(); evidence.mkdir()
            sources = root / "relocated sources"
            source = sources / "one"
            source.mkdir(parents=True)
            (source / "main.go").write_bytes(b"package one")
            (repo / "label").write_bytes(b"expected")
            (evidence / "jar").write_bytes(b"candidate")
            roots = root / "roots.json"
            roots.write_text(json.dumps({"evidence": str(evidence), "corpus": str(sources)}), encoding="utf-8")
            pin = {"path": "evidence:jar", "sha256": m9.file_sha(evidence / "jar")}
            identity = {"commit": "a" * 40, "runtimeSourceSha256": "source"}
            registry = {"schema": "m9-evaluation-protocol-v2", "frozen": True,
                        "pathRoots": {"evidence": {"recordedPath": "Z:/absent/original"}, "corpus": {"recordedPath": "Z:/absent/source"}},
                        "policy": {"passiveThresholdPerMille": 950}, "candidate": {**identity, "jar": pin},
                        "claims": [{"id": "I", "oracle": "source", "denominator": "all", "cases": ["control"], "criterion": "exact", "owner": "M9.2"}],
                        "corpus": [{"id": "one", "commit": "a" * 40, "inventory": pin, "contexts": pin,
                                    "root": "corpus:one", "sourceManifestSha256": m9.digest(m9.census(source)[0])}],
                        "pinnedFiles": [{"path": "repo:label", "sha256": m9.file_sha(repo / "label")}]}
            path = root / "registry.json"
            path.write_text(json.dumps(registry), encoding="utf-8")
            args = SimpleNamespace(registry=path, verify_sources=True, roots_file=roots)
            with patch.object(m9, "ROOT", repo), patch.object(m9, "platform_identity", return_value=identity), patch.object(m9, "git", return_value=("a" * 40).encode()), redirect_stdout(io.StringIO()):
                self.assertEqual(0, m9.preflight(args))
                (source / "main.go").write_bytes(b"package two")
                self.assertEqual(1, m9.preflight(args))
                (source / "main.go").write_bytes(b"package one")
                (evidence / "jar").write_bytes(b"wrong!!!")
                self.assertEqual(1, m9.preflight(args))

    def test_portable_locators_reject_traversal_unbound_roots_and_repository_override(self):
        with tempfile.TemporaryDirectory() as directory:
            registry = {"schema": m9.PORTABLE_SCHEMA, "pathRoots": {"evidence": {"recordedPath": directory}}}
            paths = m9.InputPaths(registry)
            self.assertEqual(Path(directory).resolve() / "space name", paths("evidence:space name"))
            for invalid in ["evidence:../secret", "repo:/absolute", "repo:C:/absolute", "repo:\\escape", "missing:file", "relative-only", "repo:"]:
                with self.subTest(locator=invalid), self.assertRaises(m9.InputLocationError):
                    paths(invalid)
            config = Path(directory) / "roots.json"
            for override in [{"repo": directory}, {"not-registered": directory}]:
                config.write_text(json.dumps(override), encoding="utf-8")
                with self.assertRaises(m9.InputLocationError):
                    m9.InputPaths(registry, config)

    def test_portable_locations_check_resolved_containment(self):
        # Model the resolved target of a symlink/junction without requiring OS link privileges.
        with tempfile.TemporaryDirectory() as directory:
            base = Path(directory).resolve()
            resolver = m9.InputPaths({"schema": m9.PORTABLE_SCHEMA})
            with patch.object(m9, "ROOT", base), patch.object(Path, "resolve", side_effect=[base, base.parent / "outside"]):
                with self.assertRaises(m9.InputLocationError):
                    resolver("repo:link/secret")


if __name__ == "__main__":
    unittest.main()
