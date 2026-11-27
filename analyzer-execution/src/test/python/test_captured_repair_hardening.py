#!/usr/bin/env python3
"""
Adversarial regression suite for captured-repair/run.py, guest-init.sh, and Dockerfile hardening.
Covers Moonshot #1 Round 2 security & correctness findings:
  - P0-1: stage_m2_subset Maven GAV traversal, relativePath escape, BOM traversal, symlink skipping
  - P1-2: Cryptographic nonce framing, raw tail removal, unauthenticated stdout injection & duplicate rejection
  - P1-3: stage_repo_tree host symlink dereference defense
  - P1-6: Go census.json encoding/json helper (no shell string concatenation injection)
  - P1-7: Windows MAX_PATH (>260 char) + read-only safe_rmtree & ephemeral Docker image cleanup
  - P2-10: Windows drive-letter & ADS traversal in resolve_safe_relative_path (probes & edits)
  - P2-15: Container hardening (--security-opt=no-new-privileges:true, --user 65532:65532, USER root, --network=none build)
  - P2-18: Windows named-pipe Docker daemon probe generality
"""

import base64
import importlib.util
import json
import os
import secrets
import shutil
import stat
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path
from unittest import mock

REPO_ROOT = Path(__file__).resolve().parents[4]
CAPTURED_REPAIR_DIR = REPO_ROOT / "analyzer-execution" / "src" / "main" / "resources" / "captured-repair"
RUN_PY = CAPTURED_REPAIR_DIR / "run.py"

spec = importlib.util.spec_from_file_location("captured_repair_run", RUN_PY)
assert spec is not None and spec.loader is not None
run_mod = importlib.util.module_from_spec(spec)
spec.loader.exec_module(run_mod)


class CapturedRepairHardeningTest(unittest.TestCase):

    # ── P0-1: stage_m2_subset path traversal, relativePath escape & symlinks ─

    def test_p0_1_maven_coord_validation_and_stage_m2_subset_blocks_traversal_and_outside_parent(self):
        # Unit check on strict Maven GAV regex & segment rules
        self.assertTrue(run_mod.is_valid_maven_coord("com.acme.demo", "my-lib_1", "1.2.3-SNAPSHOT"))
        for bad_g, bad_a, bad_v in [
            ("com..acme", "lib", "1.0"),
            (".com.acme", "lib", "1.0"),
            ("com.acme.", "lib", "1.0"),
            ("../../../etc", "lib", "1.0"),
            ("com.acme", "..", "1.0"),
            ("com.acme", ".", "1.0"),
            ("com.acme", "lib", ".."),
            ("com.acme", "a/b", "1.0"),
            ("com.acme", "a\\b", "1.0"),
            ("C:evil", "lib", "1.0"),
            ("com.acme", "lib:ads", "1.0"),
            ("com.acme", "lib\x00", "1.0"),
            ("com.acme", "lib", "LATEST"),
            ("com.acme", "lib", "RELEASE"),
            ("com.acme", "${x}", "1.0"),
        ]:
            self.assertFalse(
                run_mod.is_valid_maven_coord(bad_g, bad_a, bad_v),
                f"Expected invalid Maven coord: {(bad_g, bad_a, bad_v)}",
            )

        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            outside_secret = root / "outside-secret"
            outside_secret.mkdir()
            (outside_secret / "secret.txt").write_text("TOP_SECRET_HOST_FILE", encoding="utf-8")
            (outside_secret / "pom.xml").write_text(
                """<project>
                  <groupId>com.escaped</groupId><artifactId>escaped</artifactId><version>1.0</version>
                  <dependencies>
                    <dependency><groupId>com.secretdep</groupId><artifactId>only-via-escape</artifactId><version>1.0</version></dependency>
                  </dependencies>
                </project>""",
                encoding="utf-8",
            )

            m2_root = root / "m2"
            # Legitimate artifact
            legit_dir = m2_root / "com" / "acme" / "legit" / "1.0"
            legit_dir.mkdir(parents=True)
            (legit_dir / "legit-1.0.jar").write_bytes(b"LEGIT_JAR")
            (legit_dir / "legit-1.0.pom").write_text("<project/>", encoding="utf-8")
            # Artifact that should only be reachable if outside-secret/pom.xml was parsed
            secret_dep = m2_root / "com" / "secretdep" / "only-via-escape" / "1.0"
            secret_dep.mkdir(parents=True)
            (secret_dep / "only-via-escape-1.0.jar").write_bytes(b"SHOULD_NOT_STAGE")

            # Core POM containing adversarial BOM import traversal
            core_dir = m2_root / "org" / "apache" / "maven" / "core-test" / "1.0"
            core_dir.mkdir(parents=True)
            (core_dir / "core-test-1.0.pom").write_text(
                """<project>
                  <dependencyManagement><dependencies>
                    <dependency>
                      <groupId>../../../../..</groupId><artifactId>outside-secret</artifactId><version>.</version>
                      <type>pom</type><scope>import</scope>
                    </dependency>
                  </dependencies></dependencyManagement>
                </project>""",
                encoding="utf-8",
            )

            repo_dir = root / "repo"
            repo_dir.mkdir()
            (repo_dir / "pom.xml").write_text(
                """<project>
                  <modelVersion>4.0.0</modelVersion>
                  <parent>
                    <groupId>com.escaped</groupId>
                    <artifactId>escaped</artifactId>
                    <version>1.0</version>
                    <relativePath>../outside-secret/pom.xml</relativePath>
                  </parent>
                  <groupId>com.acme</groupId><artifactId>app</artifactId><version>1.0</version>
                  <dependencies>
                    <dependency><groupId>com.acme</groupId><artifactId>legit</artifactId><version>1.0</version></dependency>
                    <dependency><groupId>../outside-secret</groupId><artifactId>.</artifactId><version>.</version></dependency>
                    <dependency><groupId>com.acme</groupId><artifactId>../../../escape</artifactId><version>1.0</version></dependency>
                    <dependency><groupId>C:evil</groupId><artifactId>bad</artifactId><version>1.0</version></dependency>
                  </dependencies>
                </project>""",
                encoding="utf-8",
            )

            m2_stage = root / "stage-m2"
            m2_stage.mkdir()
            run_mod.stage_m2_subset(repo_dir, m2_root, m2_stage)

            # Legitimate artifact staged
            self.assertTrue((m2_stage / "com" / "acme" / "legit" / "1.0" / "legit-1.0.jar").is_file())
            # Escaped parent POM was NOT followed
            self.assertFalse((m2_stage / "com" / "secretdep" / "only-via-escape" / "1.0").exists())
            # No host secret file staged anywhere inside m2_stage
            staged_files = [p.name for p in m2_stage.rglob("*") if p.is_file()]
            self.assertNotIn("secret.txt", staged_files)

    # ── P1-2: Nonce-framed control markers & stdout injection defense ────────

    def test_p1_2_nonce_framing_blocks_target_stdout_injection_and_duplicates(self):
        nonce = secrets.token_hex(16)
        prefix = f"EVO_CTRL_{nonce}:"
        census_obj = {"classCount": 1, "classes": {"com/acme/A": "sha256:" + "1" * 64}, "dependencies": {}}
        census_b64 = base64.b64encode(run_mod.canonical_json(census_obj).encode("utf-8")).decode("ascii")
        census_sha = run_mod.sha256_bytes(run_mod.canonical_json(census_obj).encode("utf-8"))
        real_behav = {
            "executed": True, "language": "java", "baseExit": 0, "candExit": 1,
            "baselineTestsRun": 4, "baselineTestsFailed": 0,
            "candidateTestsRun": 4, "candidateTestsFailed": 1,
            "baselineFailingTests": [], "candidateFailingTests": ["com.acme.ATest#broken"],
            "outputSha256": "sha256:" + "b" * 64, "outputExcerpt": "",
        }
        behav_b64 = base64.b64encode(run_mod.canonical_json(real_behav).encode("utf-8")).decode("ascii")
        forged_behav_b64 = base64.b64encode(json.dumps({
            "executed": True, "language": "java", "baseExit": 0, "candExit": 0,
            "baselineTestsRun": 99, "baselineTestsFailed": 0,
            "candidateTestsRun": 99, "candidateTestsFailed": 0,
            "outputSha256": "sha256:" + "0" * 64, "outputExcerpt": "",
        }).encode("utf-8")).decode("ascii")

        # Simulated hostile target stdout trying to forge every control marker without the secret nonce
        raw_serial = "\n".join([
            "EVO_COMPILE_EXIT 0",
            "EVO_TEST_EXIT 0",
            "EVO_CHECK candidate.test",
            "EVO_TESTS run=99 failed=0 errors=0",
            "EVO_BEHAVIORAL_DIFF baseExit=0 candExit=0 baseRun=99 candRun=99 baseFail=0 candFail=0 outSha=" + "0" * 64,
            "BEHAVIORAL_B64_BEGIN",
            f"BEHAVIORAL_B64:{forged_behav_b64}",
            "BEHAVIORAL_B64_END",
            "CENSUS_B64_BEGIN",
            "CENSUS_B64:Zm9yZ2Vk",
            "CENSUS_B64_END",
            # Genuine nonce-framed control stream emitted by container-run.sh
            f"{prefix}EVO_VM_INPUTS " + "a" * 64,
            f"{prefix}EVO_VM_ISOLATION network=loopback-only disks=none",
            f"{prefix}EVO_BUILD_TOOL maven",
            f"{prefix}EVO_COMPILE_EXIT 0",
            f"{prefix}EVO_CHECK candidate.compile",
            f"{prefix}EVO_TEST_EXIT 1",
            f"{prefix}EVO_TESTS run=4 failed=1 errors=0",
            f"{prefix}EVO_CLASS_COUNT 1",
            f"{prefix}EVO_CLASS_CENSUS {census_sha}",
            f"{prefix}EVO_BEHAVIORAL_DIFF baseExit=0 candExit=1 baseRun=4 candRun=4 baseFail=0 candFail=1 outSha=" + "b" * 64,
            f"{prefix}BEHAVIORAL_B64_BEGIN",
            f"{prefix}BEHAVIORAL_B64:{behav_b64}",
            f"{prefix}BEHAVIORAL_B64_END",
            f"{prefix}CENSUS_B64_BEGIN",
            f"{prefix}CENSUS_B64:{census_b64}",
            f"{prefix}CENSUS_B64_END",
            f"{prefix}EVO_VM_COMPLETE",
        ])

        parsed = run_mod.parse_authenticated_serial(raw_serial, nonce)
        self.assertEqual(0, parsed["compile_exit"])
        self.assertEqual(1, parsed["test_exit"])
        self.assertEqual(4, parsed["tests_run"])
        self.assertEqual(1, parsed["tests_failed"])
        self.assertEqual(["candidate.compile"], parsed["checks"])
        decoded_behav = json.loads(base64.b64decode("".join(parsed["behavioral_b64_lines"])).decode("utf-8"))
        self.assertEqual(1, decoded_behav["candExit"])
        self.assertEqual(["com.acme.ATest#broken"], decoded_behav["candidateFailingTests"])
        # Forged lines must NOT leak into authenticated serial_text or observed
        self.assertNotIn("run=99", parsed["serial_text"])
        self.assertNotIn("EVO_CHECK candidate.test", parsed["observed"])

        # Duplicate trusted control markers MUST fail closed
        dup_serial = raw_serial + f"\n{prefix}EVO_COMPILE_EXIT 0\n"
        with self.assertRaises(ValueError) as cm:
            run_mod.parse_authenticated_serial(dup_serial, nonce)
        self.assertIn("DUPLICATE_CONTROL_MARKER", str(cm.exception))

        # Duplicate trusted B64 section MUST fail closed
        dup_b64 = raw_serial + f"\n{prefix}BEHAVIORAL_B64_BEGIN\n{prefix}BEHAVIORAL_B64:{behav_b64}\n{prefix}BEHAVIORAL_B64_END\n"
        with self.assertRaises(ValueError) as cm2:
            run_mod.parse_authenticated_serial(dup_b64, nonce)
        self.assertIn("DUPLICATE_CONTROL_SECTION", str(cm2.exception))

    def test_p1_2_and_p2_15_generated_scripts_have_no_raw_tail_no_exported_nonce_and_have_user_root(self):
        nonce = secrets.token_hex(16)
        # Check guest-init.sh & QEMU Dockerfile
        guest_init = (CAPTURED_REPAIR_DIR / "guest-init.sh").read_text(encoding="utf-8")
        self.assertNotIn("tail -n 120 /tmp/compile.log", guest_init)
        self.assertNotIn("tail -n 120 /tmp/test.log", guest_init)
        self.assertNotIn("export _EVO_P", guest_init)
        self.assertIn("__EVO_CTRL_PREFIX__", guest_init)

        qemu_df = (CAPTURED_REPAIR_DIR / "Dockerfile").read_text(encoding="utf-8")
        self.assertIn("USER root", qemu_df)

        for tool in ("maven", "node", "python", "go"):
            with tempfile.TemporaryDirectory() as tmp:
                ctx = Path(tmp)
                run_mod.write_container_runner_files(ctx, tool, nonce)
                df = (ctx / "Dockerfile").read_text(encoding="utf-8")
                script = (ctx / "container-run.sh").read_text(encoding="utf-8")
                self.assertIn("USER root", df, f"Missing USER root in {tool} Dockerfile")
                self.assertIn("chmod -R a+rX /opt /inputs", df, f"Missing non-root readable permissions in {tool} Dockerfile")
                self.assertNotIn("tail -n 120 /tmp/compile.log", script, f"Raw compile tail found in {tool} script")
                self.assertNotIn("tail -n 120 /tmp/test.log", script, f"Raw test tail found in {tool} script")
                self.assertNotIn("export _EVO_P", script, f"_EVO_P must never be exported in {tool} script")
                self.assertNotIn("cp -a ", script, f"Non-root container script for {tool} should use cp -R instead of cp -a")
                self.assertIn(f'_EVO_P="EVO_CTRL_{nonce}:"', script)
                if tool == "go":
                    # P1-6: Go uses compiled encoding/json helper, zero raw shell CLASSES_JSON concatenation
                    self.assertTrue((ctx / "go_census.go").is_file())
                    self.assertNotIn('CLASSES_JSON=', script)
                    self.assertIn("/opt/go-census", script)
                    go_src = (ctx / "go_census.go").read_text(encoding="utf-8")
                    self.assertIn('"encoding/json"', go_src)
                    self.assertIn('"crypto/sha256"', go_src)

    # ── P1-3: stage_repo_tree host symlink dereference defense ───────────────

    def test_p1_3_stage_repo_tree_skips_symlinks_and_outside_targets(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            secret = root / "host-secret.txt"
            secret.write_text("DO_NOT_EXFILTRATE", encoding="utf-8")
            repo = root / "repo"
            repo.mkdir()
            (repo / "Good.java").write_text("class Good {}", encoding="utf-8")
            (repo / "link-placeholder.txt").write_text("placeholder", encoding="utf-8")
            cand = root / "cand"
            cand.mkdir()

            # Test real symlink if OS grants symlink privilege
            real_link = repo / "real-secret-link.txt"
            symlink_created = False
            try:
                os.symlink(secret, real_link)
                symlink_created = True
            except OSError:
                pass

            # Always test via Path.is_symlink guard so Windows non-elevated runs still exercise the code path
            orig_is_symlink = Path.is_symlink
            def fake_is_symlink(self_path: Path) -> bool:
                if self_path.name == "link-placeholder.txt":
                    return True
                return orig_is_symlink(self_path)

            with mock.patch.object(Path, "is_symlink", fake_is_symlink):
                run_mod.stage_repo_tree(repo, cand)

            self.assertTrue((cand / "Good.java").is_file())
            self.assertFalse((cand / "link-placeholder.txt").exists())
            if symlink_created:
                self.assertFalse((cand / "real-secret-link.txt").exists())

    # ── P1-7: Windows MAX_PATH (>260 char) & read-only safe_rmtree ───────────

    def test_p1_7_safe_rmtree_handles_long_paths_over_260_chars_and_readonly_files(self):
        with tempfile.TemporaryDirectory() as tmp:
            base = Path(tmp) / "ctx"
            deep = base
            # Build path well over Windows 260-char MAX_PATH
            for i in range(12):
                deep = deep / f"org_sonatype_plexus_segment_{i:02d}_abcdefghijklmnop"
            long_parent = run_mod._win_long_path(deep) if os.name == "nt" else str(deep)
            os.makedirs(long_parent, exist_ok=True)
            ro_file = os.path.join(long_parent, "plexus-sec-dispatcher-2.0.jar")
            with open(ro_file, "wb") as f:
                f.write(b"jar-bytes")
            os.chmod(ro_file, stat.S_IREAD)
            self.assertGreater(len(str(deep / "plexus-sec-dispatcher-2.0.jar")), 260)

            run_mod.safe_rmtree(base)
            self.assertFalse(base.exists())

    # ── P2-10: Windows drive-letter & ADS path traversal ─────────────────────

    def test_p2_10_resolve_safe_relative_path_rejects_drive_letters_ads_and_traversals(self):
        with tempfile.TemporaryDirectory() as tmp:
            base = Path(tmp) / "base"
            base.mkdir()
            valid = run_mod.resolve_safe_relative_path(base, "src/test/java/AcmeTest.java", "UNSAFE_PROBE_PATH")
            self.assertEqual(base / "src" / "test" / "java" / "AcmeTest.java", valid)

            for evil in [
                "C:evil.java",
                "C:/evil.java",
                "D:src/Main.java",
                "src/Main.java:stream",
                "../escape.java",
                "src/../../escape.java",
                "/etc/passwd",
                "src\\Main.java",
                "src/./Main.java",
                "src//Main.java",
                "src/Main\x00.java",
                "",
            ]:
                with self.assertRaises(ValueError, msg=f"Must reject unsafe path: {evil!r}"):
                    run_mod.resolve_safe_relative_path(base, evil, "UNSAFE_EDIT_PATH")

    # ── P2-18: Windows named-pipe daemon probe generality ────────────────────

    def test_p2_18_windows_named_pipe_probe_accepts_general_local_contexts_and_rejects_remote(self):
        self.assertTrue(run_mod.is_local_windows_named_pipe("npipe:////./pipe/dockerDesktopLinuxEngine"))
        self.assertTrue(run_mod.is_local_windows_named_pipe("npipe:////./pipe/docker_engine"))
        self.assertTrue(run_mod.is_local_windows_named_pipe("npipe:////./pipe/rancher_desktop"))
        for bad in ["tcp://192.168.1.5:2375", "ssh://root@remote", "https://docker.evil", "unix:///var/run/docker.sock", ""]:
            self.assertFalse(run_mod.is_local_windows_named_pipe(bad), f"Must reject non-named-pipe endpoint: {bad}")

        with mock.patch.object(run_mod.os, "name", "nt"):
            def fake_docker_cmd(_bin, *args, **_kw):
                if args[:2] == ("context", "show"):
                    return "rancher-desktop"
                if args[:3] == ("context", "inspect", "desktop-linux"):
                    raise subprocess.CalledProcessError(1, "docker")
                if args[:3] == ("context", "inspect", "rancher-desktop"):
                    return json.dumps([{"Endpoints": {"docker": {"Host": "npipe:////./pipe/rancher_desktop"}}}])
                raise AssertionError(f"Unexpected args: {args}")

            with mock.patch.object(run_mod, "docker_cmd", side_effect=fake_docker_cmd):
                resolved = run_mod.resolve_windows_docker_bin("desktop-linux", context_explicit=False)
                self.assertEqual(["docker", "--context", "rancher-desktop"], resolved)

            def fake_remote_cmd(_bin, *args, **_kw):
                return json.dumps([{"Endpoints": {"docker": {"Host": "tcp://10.0.0.5:2375"}}}])

            with mock.patch.object(run_mod, "docker_cmd", side_effect=fake_remote_cmd):
                with self.assertRaises(RuntimeError) as cm:
                    run_mod.resolve_windows_docker_bin("remote-ctx", context_explicit=True)
                self.assertIn("LOCAL_DOCKER_DAEMON_REQUIRED", str(cm.exception))

    # ── P1-2: guest-init.sh nonce instantiation MUST preserve _EVO_P at sh runtime ─

    def test_p1_2_guest_init_instantiation_preserves_nonce_at_sh_runtime(self):
        nonce = secrets.token_hex(16)
        raw_gi = (CAPTURED_REPAIR_DIR / "guest-init.sh").read_text(encoding="utf-8")
        instantiated = run_mod.instantiate_guest_init(raw_gi, nonce)
        self.assertIn(f'_EVO_P="EVO_CTRL_{nonce}:"', instantiated)
        self.assertNotIn("__EVO_CTRL_PREFIX__", instantiated)
        # Ensure the fallback guard line was NOT rewritten to compare against the instantiated nonce
        self.assertNotIn(f'= "EVO_CTRL_{nonce}:"', instantiated)

        # Execute the header (through emit/emit_b64 definitions) with a real POSIX sh if present
        sh_exe = shutil.which("sh") or (r"C:\Program Files\Git\bin\sh.exe" if Path(r"C:\Program Files\Git\bin\sh.exe").is_file() else None)
        if sh_exe:
            header_lines = []
            for line in instantiated.splitlines():
                if line.startswith("# Networkless initramfs guest"):
                    break
                header_lines.append(line)
            header_lines.append('emit "EVO_TEST_EXIT 0"')
            proc = subprocess.run(
                [sh_exe, "-c", "\n".join(header_lines)],
                capture_output=True, text=True, check=True, timeout=10,
            )
            self.assertEqual(f"EVO_CTRL_{nonce}:EVO_TEST_EXIT 0\n", proc.stdout)

    # ── P1-3: repo/node_modules symlink exfiltration defense ─────────────────

    def test_p1_3_stage_node_repo_modules_blocks_symlinked_node_modules_exfiltration(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            secret_dir = root / "host-secrets"
            secret_dir.mkdir()
            (secret_dir / "id_rsa").write_text("PRIVATE_KEY_MATERIAL", encoding="utf-8")
            repo = root / "node-repo"
            repo.mkdir()
            nm = repo / "node_modules"
            dst = root / "staged-nm"

            # Attempt real symlink; fallback to mocked is_symlink if Windows lacks symlink privilege
            real_link = False
            try:
                os.symlink(secret_dir, nm, target_is_directory=True)
                real_link = True
            except OSError:
                nm.mkdir()
                (nm / "pkg.js").write_text("module.exports={}", encoding="utf-8")

            if real_link:
                staged = run_mod.stage_node_repo_modules(repo, dst)
                self.assertFalse(staged)
                self.assertFalse(dst.exists())
            else:
                orig_is_symlink = Path.is_symlink
                def fake_symlink(p: Path) -> bool:
                    return True if p == nm else orig_is_symlink(p)
                with mock.patch.object(Path, "is_symlink", fake_symlink):
                    staged = run_mod.stage_node_repo_modules(repo, dst)
                self.assertFalse(staged)
                self.assertFalse(dst.exists())

    # ── P1-4: Python unittest summary line must not spoof failing test IDs ───

    def test_p1_4_python_unittest_summary_line_not_treated_as_fail_id(self):
        nonce = secrets.token_hex(16)
        with tempfile.TemporaryDirectory() as tmp:
            ctx = Path(tmp)
            run_mod.write_container_runner_files(ctx, "python", nonce)
            script = (ctx / "container-run.sh").read_text(encoding="utf-8")
            start = script.index("def extract_py_fail_ids")
            end = script.index("\nblog = ", start)
            ns: dict = {"re": __import__("re")}
            exec(script[start:end], ns)
            extract = ns["extract_py_fail_ids"]

            # Import error with only unittest summary line -> MUST return [] (not ['(errors=1)'])
            self.assertEqual([], extract("Ran 0 tests in 0.000s\n\nFAILED (errors=1)\n"))
            # Real unittest failure -> MUST return only the test identity, excluding '(failures=1)'
            self.assertEqual(
                ["test_bad (test_calc.CalcTest.test_bad)"],
                extract("FAIL: test_bad (test_calc.CalcTest.test_bad)\nRan 2 tests in 0.001s\n\nFAILED (failures=1)\n"),
            )
            # Pytest failure -> MUST extract pytest node ID
            self.assertEqual(
                ["tests/test_app.py::test_alpha"],
                extract("FAILED tests/test_app.py::test_alpha - AssertionError\n1 failed in 0.02s\n"),
            )

    # ── P0-1 & P2-10: Maven -pl module path validation before filesystem probe ─

    def test_p0_1_and_p2_10_is_safe_mvn_module_path_rejects_traversal_and_drive_letters(self):
        self.assertTrue(run_mod.is_safe_mvn_module_path("backend"))
        self.assertTrue(run_mod.is_safe_mvn_module_path("services/orders-api"))
        for bad in ["../../../../etc", "../sibling", "C:evil", "C:/evil", "/abs", "-X", "mod\\sub", "mod/./a", "mod//a", "mod\x00a", ""]:
            self.assertFalse(run_mod.is_safe_mvn_module_path(bad), f"Must reject unsafe module path: {bad!r}")

    # ── Round 3 (I3): skipTests=true MUST guard baseline behavioral diff across all 5 tools + guest-init.sh ─

    def test_round3_skip_tests_guards_behavioral_diff_across_all_tools_guest_init_and_cli(self):
        expected_guard = 'if [ "${EVO_SKIP_TESTS:-false}" != "true" ] && [ "${EVO_BEHAVIORAL_DIFF:-false}" = "true" ] && [ -d /inputs/baseline ]; then'
        unguarded = 'if [ "${EVO_BEHAVIORAL_DIFF:-false}" = "true" ] && [ -d /inputs/baseline ]; then'
        sh_exe = shutil.which("sh") or (r"C:\Program Files\Git\bin\sh.exe" if Path(r"C:\Program Files\Git\bin\sh.exe").is_file() else None)

        # 1. guest-init.sh guards baseline diff on EVO_SKIP_TESTS != "true" and passes sh -n
        guest_init_path = CAPTURED_REPAIR_DIR / "guest-init.sh"
        guest_init = guest_init_path.read_text(encoding="utf-8")
        self.assertIn(expected_guard, guest_init)
        self.assertNotIn(unguarded, guest_init)
        if sh_exe:
            subprocess.run([sh_exe, "-n", str(guest_init_path)], check=True, capture_output=True, text=True, timeout=10)

        # 2. All 5 build tools (maven, gradle, node, python, go) guard baseline diff and pass sh -n
        nonce = secrets.token_hex(16)
        for tool in ("maven", "gradle", "node", "python", "go"):
            with tempfile.TemporaryDirectory() as tmp:
                ctx = Path(tmp)
                run_mod.write_container_runner_files(ctx, tool, nonce)
                script_path = ctx / "container-run.sh"
                script = script_path.read_text(encoding="utf-8")
                self.assertIn(expected_guard, script, f"Missing EVO_SKIP_TESTS guard for {tool}")
                self.assertNotIn(unguarded, script, f"Unguarded EVO_BEHAVIORAL_DIFF block found for {tool}")
                if sh_exe:
                    subprocess.run([sh_exe, "-n", str(script_path)], check=True, capture_output=True, text=True, timeout=10)

        # 3. Host helper disables behavioral diff whenever skip_tests=True
        self.assertFalse(run_mod.effective_behavioral_diff_enabled(True, True))
        self.assertTrue(run_mod.effective_behavioral_diff_enabled(True, False))
        self.assertFalse(run_mod.effective_behavioral_diff_enabled(False, True))
        self.assertFalse(run_mod.effective_behavioral_diff_enabled(False, False))

        spec_skip = run_mod.build_spec_env("maven", ["backend"], skip_tests=True, behavioral_diff=True)
        self.assertIn("EVO_SKIP_TESTS=true\n", spec_skip)
        self.assertIn("EVO_BEHAVIORAL_DIFF=false\n", spec_skip)
        spec_run = run_mod.build_spec_env("maven", ["backend"], skip_tests=False, behavioral_diff=True)
        self.assertIn("EVO_SKIP_TESTS=false\n", spec_run)
        self.assertIn("EVO_BEHAVIORAL_DIFF=true\n", spec_run)

        # 4. End-to-end CLI invocation with --skip-tests --behavioral-diff --behavioral-probes
        #    MUST NOT stage inputs/baseline or inputs/probes, MUST set EVO_BEHAVIORAL_DIFF=false,
        #    and MUST emit capture.json with testsSkipped=True and NO behavioralDiff key.
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            repo = root / "py-repo"
            repo.mkdir()
            orig_src = "def add(a, b):\n    return a - b\n"
            new_src = "def add(a, b):\n    return a + b\n"
            (repo / "pyproject.toml").write_bytes(b"[project]\nname='demo'\n")
            (repo / "calc.py").write_bytes(orig_src.encode("utf-8"))

            before_sha = run_mod.sha256_bytes(orig_src.encode("utf-8"))
            after_sha = run_mod.sha256_bytes(new_src.encode("utf-8"))
            patch_text = (
                "--- a/calc.py\n"
                "+++ b/calc.py\n"
                "@@ -1,2 +1,2 @@\n"
                " def add(a, b):\n"
                "-    return a - b\n"
                "+    return a + b\n"
            )
            prop_id = "sha256:" + "1" * 64
            plan_id = "sha256:" + "2" * 64
            proposal_file = root / "proposal.json"
            proposal_file.write_text(json.dumps({
                "identity": prop_id,
                "candidate": {
                    "patch": patch_text,
                    "edits": [{"path": "calc.py", "before": before_sha, "after": after_sha}],
                },
            }), encoding="utf-8")
            ver_file = root / "verification.json"
            ver_file.write_text(json.dumps({
                "planIdentity": plan_id,
                "plan": {"proposal": prop_id, "affectedModules": ["."]},
            }), encoding="utf-8")
            probes_file = root / "probes.json"
            probes_file.write_text(json.dumps([
                {"path": "tests/test_probe.py", "language": "python", "content": "def test_p(): assert True\n"}
            ]), encoding="utf-8")
            out_dir = root / "out"
            pinned_py = "python@sha256:" + "a" * 64
            fake_img = "sha256:" + "b" * 64
            fake_container = "c" * 64
            captured_state: dict = {}

            def fake_subprocess_run(cmd, *a, **kw):
                if "build" in cmd:
                    ctx_path = Path(cmd[-1])
                    inp = ctx_path / "inputs"
                    captured_state["build_spec"] = (inp / "build-spec.env").read_text(encoding="utf-8")
                    captured_state["baseline_exists"] = (inp / "baseline").exists()
                    captured_state["probes_exists"] = (inp / "probes").exists()
                    captured_state["manifest_sha"] = run_mod.sha256_file(inp / "manifest.sha256")[7:]
                    script_txt = (ctx_path / "container-run.sh").read_text(encoding="utf-8")
                    m = run_mod.re.search(r'_EVO_P="EVO_CTRL_([a-f0-9]{32}):"', script_txt)
                    assert m is not None
                    captured_state["nonce"] = m.group(1)
                    iid_idx = cmd.index("--iidfile") + 1
                    Path(cmd[iid_idx]).write_text(fake_img + "\n", encoding="utf-8")
                return subprocess.CompletedProcess(cmd, 0, stdout="", stderr="")

            def fake_run_isolated(_docker_bin, _image, _mode):
                p = f"EVO_CTRL_{captured_state['nonce']}:"
                census_obj = {"classCount": 1, "classes": {"calc.py": after_sha}, "dependencies": {}}
                census_raw = run_mod.canonical_json(census_obj).encode("utf-8")
                census_sha = run_mod.sha256_bytes(census_raw)
                census_b64 = base64.b64encode(census_raw).decode("ascii")
                raw_serial = "\n".join([
                    f"{p}EVO_VM_INPUTS {captured_state['manifest_sha']}",
                    f"{p}EVO_VM_ISOLATION network=loopback-only disks=none",
                    f"{p}EVO_BUILD_TOOL python",
                    f"{p}EVO_COMPILE_EXIT 0",
                    f"{p}EVO_CHECK candidate.compile",
                    f"{p}EVO_TESTS_SKIPPED true",
                    f"{p}EVO_TEST_EXIT 0",
                    f"{p}EVO_TESTS run=0 failed=0 errors=0",
                    f"{p}EVO_CLASS_COUNT 1",
                    f"{p}EVO_CLASS_CENSUS {census_sha}",
                    f"{p}CENSUS_B64_BEGIN",
                    f"{p}CENSUS_B64:{census_b64}",
                    f"{p}CENSUS_B64_END",
                    f"{p}EVO_VM_COMPLETE",
                ])
                return fake_container, raw_serial

            argv = [
                str(RUN_PY),
                "--repo", str(repo),
                "--proposal", str(proposal_file),
                "--verification", str(ver_file),
                "--build-tool", "python",
                "--mode", "docker-container",
                "--base", pinned_py,
                "--skip-tests",
                "--behavioral-diff",
                "--behavioral-probes", str(probes_file),
                "--out", str(out_dir),
            ]
            with mock.patch.object(sys, "argv", argv), \
                 mock.patch.object(run_mod, "resolve_windows_docker_bin", return_value=["docker"]), \
                 mock.patch.object(run_mod, "docker_cmd", return_value=fake_img), \
                 mock.patch.object(run_mod.subprocess, "run", side_effect=fake_subprocess_run), \
                 mock.patch.object(run_mod, "run_isolated_container", side_effect=fake_run_isolated):
                run_mod.main()

            self.assertIn("EVO_SKIP_TESTS=true\n", captured_state["build_spec"])
            self.assertIn("EVO_BEHAVIORAL_DIFF=false\n", captured_state["build_spec"])
            self.assertFalse(captured_state["baseline_exists"], "--skip-tests must NOT stage inputs/baseline")
            self.assertFalse(captured_state["probes_exists"], "--skip-tests must NOT stage inputs/probes")

            cap_doc = json.loads((out_dir / "capture.json").read_text(encoding="utf-8"))
            self.assertTrue(cap_doc.get("testsSkipped"))
            self.assertNotIn("behavioralDiff", cap_doc)
            self.assertEqual(["candidate.compile"], cap_doc["checks"])
            self.assertEqual(0, cap_doc["testsRun"])
            serial_txt = (out_dir / "serial.txt").read_text(encoding="utf-8")
            self.assertIn("EVO_TESTS_SKIPPED true", serial_txt)
            self.assertNotIn("EVO_BEHAVIORAL_DIFF", serial_txt)

            # 5. Compile-failure + --skip-tests --behavioral-diff MUST still set testsSkipped=True & omit behavioralDiff
            out_fail = root / "out-compile-fail"
            def fake_run_isolated_compile_fail(_docker_bin, _image, _mode):
                p = f"EVO_CTRL_{captured_state['nonce']}:"
                census_obj = {"classCount": 1, "classes": {"calc.py": after_sha}, "dependencies": {}}
                census_raw = run_mod.canonical_json(census_obj).encode("utf-8")
                census_sha = run_mod.sha256_bytes(census_raw)
                census_b64 = base64.b64encode(census_raw).decode("ascii")
                raw_serial = "\n".join([
                    f"{p}EVO_VM_INPUTS {captured_state['manifest_sha']}",
                    f"{p}EVO_VM_ISOLATION network=loopback-only disks=none",
                    f"{p}EVO_BUILD_TOOL python",
                    f"{p}EVO_COMPILE_EXIT 1",
                    f"{p}EVO_TEST_EXIT 1",
                    f"{p}EVO_TESTS run=0 failed=0 errors=0",
                    f"{p}EVO_CLASS_COUNT 1",
                    f"{p}EVO_CLASS_CENSUS {census_sha}",
                    f"{p}CENSUS_B64_BEGIN",
                    f"{p}CENSUS_B64:{census_b64}",
                    f"{p}CENSUS_B64_END",
                    f"{p}EVO_VM_COMPLETE",
                ])
                return fake_container, raw_serial

            argv_fail = [*argv[:-1], str(out_fail)]
            with mock.patch.object(sys, "argv", argv_fail), \
                 mock.patch.object(run_mod, "resolve_windows_docker_bin", return_value=["docker"]), \
                 mock.patch.object(run_mod, "docker_cmd", return_value=fake_img), \
                 mock.patch.object(run_mod.subprocess, "run", side_effect=fake_subprocess_run), \
                 mock.patch.object(run_mod, "run_isolated_container", side_effect=fake_run_isolated_compile_fail):
                run_mod.main()

            fail_cap = json.loads((out_fail / "capture.json").read_text(encoding="utf-8"))
            self.assertEqual(1, fail_cap["compileExit"])
            self.assertTrue(fail_cap.get("testsSkipped"))
            self.assertNotIn("behavioralDiff", fail_cap)
            self.assertNotIn("EVO_BEHAVIORAL_DIFF", (out_fail / "serial.txt").read_text(encoding="utf-8"))

            # 6. Real POSIX sh behavioral evaluation of the guard condition even when baseline dir exists & EVO_BEHAVIORAL_DIFF=true
            if sh_exe:
                fake_base = root / "baseline-dir"
                fake_base.mkdir()
                guard_body = expected_guard.replace("/inputs/baseline", str(fake_base.as_posix())) + " echo RAN_BASELINE; else echo SKIPPED_BASELINE; fi"
                res_skip = subprocess.run(
                    [sh_exe, "-c", f"EVO_SKIP_TESTS=true; EVO_BEHAVIORAL_DIFF=true; {guard_body}"],
                    capture_output=True, text=True, check=True, timeout=10,
                )
                self.assertEqual("SKIPPED_BASELINE", res_skip.stdout.strip())
                res_run = subprocess.run(
                    [sh_exe, "-c", f"EVO_SKIP_TESTS=false; EVO_BEHAVIORAL_DIFF=true; {guard_body}"],
                    capture_output=True, text=True, check=True, timeout=10,
                )
                self.assertEqual("RAN_BASELINE", res_run.stdout.strip())


if __name__ == "__main__":
    unittest.main()
