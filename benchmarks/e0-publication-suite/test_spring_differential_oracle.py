#!/usr/bin/env python3
"""Unit tests for WP2.5 hardened Spring ApplicationContextRunner differential oracle,
spring_sat_20_oss manifest cohort, closed-denominator soundness verifier, and G1-G3 evaluator.
"""

from __future__ import annotations

import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path
from unittest import mock

SUITE_DIR = Path(__file__).resolve().parent
if str(SUITE_DIR) not in sys.path:
    sys.path.insert(0, str(SUITE_DIR))

import run_spring_differential_oracle as oracle  # noqa: E402


class TestSpringSat20OssManifest(unittest.TestCase):
    def test_manifest_spring_sat_20_oss_cohort_schema_and_invariants(self) -> None:
        manifest = json.loads((SUITE_DIR / "corpus_manifest.json").read_text(encoding="utf-8"))
        entries = oracle.validate_spring_sat_manifest(manifest)
        self.assertEqual(18, len(entries))

        repos = {item["repo"] for item in entries}
        self.assertEqual(18, len(repos))
        for expected_repo in (
            "spring-cloud/spring-cloud-gateway",
            "spring-projects/spring-authorization-server",
            "codecentric/spring-boot-admin",
            "halo-dev/halo",
            "flowable/flowable-engine",
            "apache/shenyu",
            "jhipster/jhipster-sample-app",
            "dromara/RuoYi-Cloud",
            "macrozheng/mall",
            "jeecgboot/jeecg-boot",
            "tomoya92/pybbs",
            "shopizer-ecommerce/shopizer",
            "spring-projects/spring-petclinic",
            "spring-petclinic/spring-petclinic-microservices",
            "apssouza22/java-microservice",
            "sqshq/piggymetrics",
            "elunez/eladmin",
            "thingsboard/thingsboard",
        ):
            self.assertIn(expected_repo, repos)

        core_g1 = [item for item in entries if item.get("core_g1_repo")]
        self.assertEqual(5, len(core_g1))
        for item in entries:
            self.assertTrue(item["blind_oracle"])
            self.assertTrue(item["offline_sandbox_required"])
            self.assertIn(item["boot_generation"], {"boot2", "boot3", "boot4"})
            self.assertTrue(item["category"])

    def test_manifest_validator_rejects_missing_sandbox_or_blind_flag(self) -> None:
        manifest = json.loads((SUITE_DIR / "corpus_manifest.json").read_text(encoding="utf-8"))
        manifest["suites"]["spring_sat_20_oss"][0]["offline_sandbox_required"] = False
        with self.assertRaises(ValueError):
            oracle.validate_spring_sat_manifest(manifest)

        manifest = json.loads((SUITE_DIR / "corpus_manifest.json").read_text(encoding="utf-8"))
        manifest["suites"]["spring_sat_20_oss"][0]["blind_oracle"] = False
        with self.assertRaises(ValueError):
            oracle.validate_spring_sat_manifest(manifest)


class TestSliceSynthesizer(unittest.TestCase):
    def test_deterministic_may_symmetric_pair_synthesis_and_property_escaping(self) -> None:
        raw_a = {
            "sliceId": "gateway_redis_cache",
            "repoId": "spring-cloud/spring-cloud-gateway",
            "targetBean": "org.example.gateway.RateLimiterClient",
            "autoConfigClasses": [
                "org.springframework.boot.autoconfigure.web.client.RestClientAutoConfiguration",
                "org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration",
            ],
            "userConfigClasses": [
                "org.example.gateway.GatewayUserConfig",
                "org.example.gateway.AuditUserConfig",
            ],
            "predictedTruth": "MAY",
            "mayExposureRatio": 0.5,
            "socketFreeExcludes": [
                "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration",
            ],
            "witnesses": [
                {
                    "worldId": "w_neg",
                    "expectedPresent": False,
                    "properties": {
                        "app.banner": 'hello "world" \\ path\nline2',
                        "app.cache.enabled": "false",
                    },
                    "activeProfiles": ["test", "offline"],
                },
                {
                    "worldId": "w_pos",
                    "expectedPresent": True,
                    "properties": {
                        "app.cache.enabled": "true",
                        "app.mode": "strict",
                    },
                    "activeProfiles": ["prod"],
                },
            ],
        }
        # Permuted order of lists & keys must yield bit-identical Java source and SHA-256 (I2).
        raw_b = {
            "sliceId": "gateway_redis_cache",
            "repoId": "spring-cloud/spring-cloud-gateway",
            "targetBean": "org.example.gateway.RateLimiterClient",
            "autoConfigClasses": list(reversed(raw_a["autoConfigClasses"])),
            "userConfigClasses": list(reversed(raw_a["userConfigClasses"])),
            "predictedTruth": "MAY",
            "mayExposureRatio": 0.5,
            "socketFreeExcludes": list(raw_a["socketFreeExcludes"]),
            "witnesses": list(reversed(raw_a["witnesses"])),
        }

        syn_a = oracle.synthesize_application_context_runner_test(oracle.SliceSpec.from_dict(raw_a))
        syn_b = oracle.synthesize_application_context_runner_test(oracle.SliceSpec.from_dict(raw_b))

        self.assertEqual(syn_a.sha256, syn_b.sha256)
        self.assertEqual(syn_a.java_source, syn_b.java_source)
        self.assertTrue(syn_a.has_symmetric_may_pair)
        self.assertEqual(2, syn_a.world_count)

        src = syn_a.java_source
        self.assertIn(
            "AutoConfigurations.of("
            "org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration.class, "
            "org.springframework.boot.autoconfigure.web.client.RestClientAutoConfiguration.class)",
            src,
        )
        self.assertIn(
            ".withUserConfiguration(org.example.gateway.AuditUserConfig.class, org.example.gateway.GatewayUserConfig.class)",
            src,
        )
        self.assertIn(
            'spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration',
            src,
        )
        # Verify property escaping & sorted active profiles
        self.assertIn(r'app.banner=hello \"world\" \\ path\nline2', src)
        self.assertIn('"spring.profiles.active=offline,test"', src)
        self.assertIn('"spring.profiles.active=prod"', src)
        # Verify deterministic world ordering (w_neg before w_pos)
        self.assertLess(src.index("void witness_001_w_neg()"), src.index("void witness_002_w_pos()"))

    def test_may_slice_without_symmetric_pair_fails_closed(self) -> None:
        raw = {
            "sliceId": "broken_may",
            "repoId": "halo-dev/halo",
            "targetBean": "org.example.PluginRegistry",
            "autoConfigClasses": ["org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration"],
            "userConfigClasses": [],
            "predictedTruth": "MAY",
            "mayExposureRatio": 0.75,
            "witnesses": [
                {"worldId": "w1", "expectedPresent": True, "properties": {}, "activeProfiles": []},
                {"worldId": "w2", "expectedPresent": True, "properties": {"x": "1"}, "activeProfiles": []},
            ],
        }
        with self.assertRaises(ValueError):
            oracle.synthesize_application_context_runner_test(oracle.SliceSpec.from_dict(raw))

    def test_must_and_never_slices_synthesize_cleanly(self) -> None:
        for truth, expected in (("MUST", True), ("NEVER", False)):
            spec = oracle.SliceSpec.from_dict(
                {
                    "sliceId": f"case_{truth.lower()}",
                    "repoId": "flowable/flowable-engine",
                    "targetBean": "org.example.ProcessEngineBean",
                    "autoConfigClasses": ["org.springframework.boot.autoconfigure.aop.AopAutoConfiguration"],
                    "userConfigClasses": [],
                    "predictedTruth": truth,
                    "mayExposureRatio": 1.0 if expected else 0.0,
                    "witnesses": [
                        {"worldId": "sample_1", "expectedPresent": expected, "properties": {"a": "1"}, "activeProfiles": []},
                    ],
                }
            )
            syn = oracle.synthesize_application_context_runner_test(spec)
            self.assertIn(f'PREDICTED_TRUTH = "{truth}"', syn.java_source)
            self.assertIn(f"assertEquals({'true' if expected else 'false'}, actualPresent", syn.java_source)

    def test_socket_free_exclude_overlapping_condition_semantics_is_rejected(self) -> None:
        with self.assertRaises(ValueError):
            oracle.SliceSpec.from_dict(
                {
                    "sliceId": "semantic_violation",
                    "repoId": "apache/shenyu",
                    "targetBean": "org.example.Router",
                    "autoConfigClasses": ["org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration"],
                    "userConfigClasses": [],
                    "predictedTruth": "MUST",
                    "mayExposureRatio": 1.0,
                    "socketFreeExcludes": ["org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration"],
                    "witnesses": [{"worldId": "w1", "expectedPresent": True, "properties": {}, "activeProfiles": []}],
                }
            )

        with self.assertRaises(ValueError):
            oracle.SliceSpec.from_dict(
                {
                    "sliceId": "condition_ref_violation",
                    "repoId": "apache/shenyu",
                    "targetBean": "org.example.Router",
                    "autoConfigClasses": ["org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration"],
                    "userConfigClasses": [],
                    "conditionReferencedClasses": ["org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration"],
                    "socketFreeExcludes": ["org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration"],
                    "predictedTruth": "MUST",
                    "mayExposureRatio": 1.0,
                    "witnesses": [{"worldId": "w1", "expectedPresent": True, "properties": {}, "activeProfiles": []}],
                }
            )


class TestHardenedDockerSandbox(unittest.TestCase):
    def test_docker_command_enforces_i1_hardening_and_zero_ambient_env_leaks(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            in_dir = root / "in"
            out_dir = root / "out"
            m2_dir = root / "m2"
            in_dir.mkdir()
            out_dir.mkdir()
            m2_dir.mkdir()

            cmd = oracle.build_hardened_docker_command(
                container_name="evo-oracle-test-123",
                slice_input_dir=in_dir,
                output_dir=out_dir,
                offline_m2_dir=m2_dir,
                policy=oracle.SandboxPolicy(
                    image="evo-spring-oracle-sandbox:test",
                    memory_limit="1536m",
                    pids_limit=128,
                    cpus_limit="1.5",
                ),
                extra_env={"EVO_ORACLE_SEED": "42", "EVO_SLICE_ID": "s1"},
            )

            self.assertEqual(["docker", "run", "--rm"], cmd[:3])
            self.assertIn("--network=none", cmd)
            self.assertIn("--read-only", cmd)
            self.assertIn("--cap-drop=ALL", cmd)
            sec_idx = cmd.index("--security-opt")
            self.assertIn("no-new-privileges", cmd[sec_idx + 1])
            self.assertEqual("128", cmd[cmd.index("--pids-limit") + 1])
            self.assertEqual("1536m", cmd[cmd.index("--memory") + 1])
            self.assertEqual("1536m", cmd[cmd.index("--memory-swap") + 1])

            # Input & m2 cache must be mounted read-only (:ro)
            volumes = [cmd[i + 1] for i, tok in enumerate(cmd) if tok == "-v"]
            self.assertTrue(any(v.endswith(":/input:ro") for v in volumes))
            self.assertTrue(any(v.endswith(":/m2-cache:ro") for v in volumes))
            self.assertTrue(any(v.endswith(":/output:rw") for v in volumes))

            # Reject arbitrary ambient or secret env vars into container
            with self.assertRaises(ValueError):
                oracle.build_hardened_docker_command(
                    container_name="evo-oracle-test-124",
                    slice_input_dir=in_dir,
                    output_dir=out_dir,
                    extra_env={"AWS_SECRET_ACCESS_KEY": "placeholder-value"},
                )

            # Host subprocess env sanitizer strips ambient credential variables
            sanitized = oracle.build_sanitized_host_env(
                {
                    "PATH": "/usr/bin",
                    "AWS_SECRET_ACCESS_KEY": "placeholder-1",
                    "GITHUB_TOKEN": "placeholder-2",
                    "OPENAI_API_KEY": "placeholder-3",
                    "MAVEN_OPTS": "-DproxyPassword=placeholder-4",
                }
            )
            self.assertEqual({"PATH": "/usr/bin"}, sanitized)

    def test_sandbox_fails_closed_when_docker_unavailable_and_cleans_up_on_timeout(self) -> None:
        spec = oracle.SliceSpec.from_dict(
            {
                "sliceId": "s_fail_closed",
                "repoId": "spring-cloud/spring-cloud-gateway",
                "targetBean": "org.example.Client",
                "autoConfigClasses": ["org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration"],
                "userConfigClasses": [],
                "predictedTruth": "MUST",
                "mayExposureRatio": 1.0,
                "witnesses": [{"worldId": "w1", "expectedPresent": True, "properties": {}, "activeProfiles": []}],
            }
        )

        # Case 1: Docker CLI missing -> fails closed, zero host fallback
        def missing_docker(*_args, **_kwargs):
            raise FileNotFoundError("docker not found")

        res_unavail = oracle.execute_slice_in_sandbox(spec, runner=missing_docker)
        self.assertEqual("RUNTIME_INIT_FAILED", res_unavail["status"])
        self.assertEqual("DOCKER_DAEMON_UNAVAILABLE", res_unavail["failureReason"])

        # Case 2: Docker available, container times out -> try/finally runs `docker rm -f`
        calls: list[list[str]] = []

        def timeout_runner(cmd, **_kwargs):
            calls.append(list(cmd))
            if cmd[:2] == ["docker", "info"]:
                return subprocess.CompletedProcess(cmd, 0, stdout="27.5.0\n", stderr="")
            if cmd[:2] == ["docker", "run"]:
                raise subprocess.TimeoutExpired(cmd=cmd, timeout=5)
            return subprocess.CompletedProcess(cmd, 0, stdout="", stderr="")

        res_timeout = oracle.execute_slice_in_sandbox(
            spec,
            policy=oracle.SandboxPolicy(timeout_seconds=5),
            runner=timeout_runner,
        )
        self.assertEqual("RUNTIME_INIT_FAILED", res_timeout["status"])
        self.assertEqual("SANDBOX_TIMEOUT", res_timeout["failureReason"])
        self.assertTrue(any(c[:3] == ["docker", "rm", "-f"] for c in calls))


class TestClosedDenominatorAndGoNoGoGates(unittest.TestCase):
    def _make_passing_core_repo_metrics(self) -> list[dict]:
        return [
            {
                "repoId": "spring-sat-oss/spring-cloud-gateway",
                "repo": "spring-cloud/spring-cloud-gateway",
                "coreG1Repo": True,
                "userStarterInteractions": 14,
                "junitConfirmedBugs": 2,
                "singleConfigFpsEliminated": 6,
                "analysisSeconds": 4.2,
                "solverQueries": 200,
                "solverLimitHits": 2,
            },
            {
                "repoId": "spring-sat-oss/spring-authorization-server",
                "repo": "spring-projects/spring-authorization-server",
                "coreG1Repo": True,
                "userStarterInteractions": 11,
                "junitConfirmedBugs": 1,
                "singleConfigFpsEliminated": 5,
                "analysisSeconds": 5.5,
                "solverQueries": 150,
                "solverLimitHits": 3,
            },
            {
                "repoId": "spring-sat-oss/halo",
                "repo": "halo-dev/halo",
                "coreG1Repo": True,
                "userStarterInteractions": 10,
                "junitConfirmedBugs": 2,
                "singleConfigFpsEliminated": 4,
                "analysisSeconds": 8.1,
                "solverQueries": 300,
                "solverLimitHits": 5,
            },
            {
                "repoId": "spring-sat-oss/flowable-engine",
                "repo": "flowable/flowable-engine",
                "coreG1Repo": True,
                "userStarterInteractions": 7,
                "junitConfirmedBugs": 0,
                "singleConfigFpsEliminated": 3,
                "analysisSeconds": 9.8,
                "solverQueries": 250,
                "solverLimitHits": 4,
            },
            {
                "repoId": "spring-sat-oss/shopizer",
                "repo": "shopizer-ecommerce/shopizer",
                "coreG1Repo": True,
                "userStarterInteractions": 6,
                "junitConfirmedBugs": 0,
                "singleConfigFpsEliminated": 1,
                "analysisSeconds": 9.2,
                "solverQueries": 100,
                "solverLimitHits": 1,
            },
        ]

    def test_closed_denominator_preserves_runtime_failures_and_catches_false_must_false_never(self) -> None:
        slice_results = [
            # Slice 1: MUST exact match (2 worlds)
            {
                "sliceId": "s1",
                "repoId": "r1",
                "predictedTruth": "MUST",
                "status": "EXECUTED",
                "compiled": True,
                "worlds": [
                    {"worldId": "w1", "expectedPresent": True, "actualPresent": True, "status": "RESOLVED"},
                    {"worldId": "w2", "expectedPresent": True, "actualPresent": True, "status": "RESOLVED"},
                ],
            },
            # Slice 2: MAY symmetric pair, 1 exact + 1 overApproxMay
            {
                "sliceId": "s2",
                "repoId": "r1",
                "predictedTruth": "MAY",
                "status": "EXECUTED",
                "compiled": True,
                "worlds": [
                    {"worldId": "w_pos", "expectedPresent": True, "actualPresent": True, "status": "RESOLVED"},
                    {"worldId": "w_neg", "expectedPresent": False, "actualPresent": True, "status": "RESOLVED"},
                ],
            },
            # Slice 3: UNKNOWN safe conservative abstention
            {
                "sliceId": "s3",
                "repoId": "r2",
                "predictedTruth": "UNKNOWN",
                "status": "EXECUTED",
                "compiled": True,
                "worlds": [
                    {"worldId": "w1", "expectedPresent": True, "actualPresent": False, "status": "RESOLVED"},
                ],
            },
            # Slice 4: Runtime init failed (JNI) -> MUST stay in closed denominator with typed reason!
            {
                "sliceId": "s4",
                "repoId": "r3",
                "predictedTruth": "MUST",
                "status": "RUNTIME_INIT_FAILED",
                "compiled": True,
                "failureReason": "java.lang.UnsatisfiedLinkError: native library missing",
                "worlds": [],
            },
            # Slice 5: Soundness breach: 1 falseMust + 1 falseNever (simulated across slices 5 & 6)
            {
                "sliceId": "s5",
                "repoId": "r4",
                "predictedTruth": "MUST",
                "status": "EXECUTED",
                "compiled": True,
                "worlds": [
                    {"worldId": "w_breach", "expectedPresent": True, "actualPresent": False, "status": "RESOLVED"},
                ],
            },
            {
                "sliceId": "s6",
                "repoId": "r5",
                "predictedTruth": "NEVER",
                "status": "EXECUTED",
                "compiled": True,
                "worlds": [
                    {"worldId": "w_breach2", "expectedPresent": False, "actualPresent": True, "status": "RESOLVED"},
                ],
            },
        ]

        summary = oracle.verify_closed_denominator_results(slice_results)
        self.assertEqual(6, summary["totalSlices"])
        self.assertEqual(6, summary["compiledSlices"])
        self.assertEqual(5, summary["successfulSlices"])
        self.assertEqual(1, summary["runtimeInitFailedSlices"])
        self.assertEqual({"JNI_OR_NATIVE_LIB_UNAVAILABLE": 1}, summary["runtimeInitFailureReasons"])
        self.assertEqual(7, summary["executedWorlds"])
        self.assertEqual(7, summary["resolvedWorlds"])
        self.assertEqual(3, summary["exactMatches"])
        self.assertEqual(1, summary["safeUnknown"])
        self.assertEqual(1, summary["overApproxMay"])
        self.assertEqual(1, summary["falseMust"])
        self.assertEqual(1, summary["falseNever"])
        self.assertAlmostEqual(500.0 / 7.0, summary["soundnessPct"], places=2)
        self.assertEqual(2, len(summary["unsoundWitnessFindings"]))

        # Soundness gate MUST fail closed on falseMust/falseNever > 0
        gates = oracle.evaluate_go_no_go_gates(
            oracle_summary=summary,
            repo_metrics=self._make_passing_core_repo_metrics(),
        )
        self.assertFalse(gates["soundness"]["passed"])
        self.assertEqual("NO_GO", gates["decision"])

    def test_go_no_go_gates_pass_and_strict_boundary_failures(self) -> None:
        # 5 slices: 4 successful (80.0% exact boundary) + 1 runtime init failed; 50 worlds: 49 exact + 1 overApproxMay (98.0% exact boundary)
        good_slices = [
            {
                "sliceId": f"ok_{i}",
                "repoId": "r1",
                "predictedTruth": "MUST",
                "status": "EXECUTED",
                "compiled": True,
                "worlds": [
                    {"worldId": f"w_{j}", "expectedPresent": True, "actualPresent": True, "status": "RESOLVED"}
                    for j in range(12)
                ],
            }
            for i in range(3)
        ]
        good_slices.append(
            {
                "sliceId": "ok_may",
                "repoId": "r2",
                "predictedTruth": "MAY",
                "status": "EXECUTED",
                "compiled": True,
                "worlds": [
                    {"worldId": f"w_{j}", "expectedPresent": (j % 2 == 0), "actualPresent": (j % 2 == 0), "status": "RESOLVED"}
                    for j in range(13)
                ]
                + [{"worldId": "w_over", "expectedPresent": False, "actualPresent": True, "status": "RESOLVED"}],
            }
        )
        good_slices.append(
            {
                "sliceId": "init_fail",
                "repoId": "r3",
                "predictedTruth": "MUST",
                "status": "RUNTIME_INIT_FAILED",
                "compiled": True,
                "failureReason": "DATASOURCE_OR_DB_INIT_FAILED",
                "worlds": [],
            }
        )

        summary = oracle.verify_closed_denominator_results(good_slices)
        self.assertEqual(0.8, summary["sliceRunSuccessRate"])
        self.assertEqual(0.98, summary["exactMatchRate"])
        self.assertEqual(100.0, summary["soundnessPct"])

        # All gates PASS -> GO
        metrics = self._make_passing_core_repo_metrics()
        gates_go = oracle.evaluate_go_no_go_gates(oracle_summary=summary, repo_metrics=metrics)
        self.assertTrue(gates_go["soundness"]["passed"])
        self.assertTrue(gates_go["G1"]["passed"])
        self.assertTrue(gates_go["G2"]["passed"])
        self.assertTrue(gates_go["G3"]["passed"])
        self.assertTrue(gates_go["overallPassed"])
        self.assertEqual("GO", gates_go["decision"])

        # G1 fail boundary: drop 3rd core repo from 10 -> 9 interactions (only 2/5 pass)
        m_g1_fail = [dict(r) for r in metrics]
        m_g1_fail[2]["userStarterInteractions"] = 9
        gates_g1 = oracle.evaluate_go_no_go_gates(oracle_summary=summary, repo_metrics=m_g1_fail)
        self.assertFalse(gates_g1["G1"]["passed"])
        self.assertEqual("NO_GO", gates_g1["decision"])

        # G2 OR-branch: 4 bugs (<5) BUT 20 FPs eliminated (>=20) -> PASS; 4 bugs + 19 FPs -> FAIL
        m_g2_fp_pass = [dict(r) for r in metrics]
        m_g2_fp_pass[0]["junitConfirmedBugs"] = 1  # total bugs = 4
        m_g2_fp_pass[0]["singleConfigFpsEliminated"] = 7  # total FPs = 20
        self.assertTrue(oracle.evaluate_go_no_go_gates(oracle_summary=summary, repo_metrics=m_g2_fp_pass)["G2"]["passed"])

        m_g2_fail = [dict(r) for r in m_g2_fp_pass]
        m_g2_fail[0]["singleConfigFpsEliminated"] = 6  # total FPs = 19, total bugs = 4
        self.assertFalse(oracle.evaluate_go_no_go_gates(oracle_summary=summary, repo_metrics=m_g2_fail)["G2"]["passed"])

        # G3 strict '<' boundaries: median == 10.0 FAILS, solverLimitRate == 0.05 FAILS
        m_g3_time_fail = [dict(r, analysisSeconds=10.0) for r in metrics]
        g3_time = oracle.evaluate_go_no_go_gates(oracle_summary=summary, repo_metrics=m_g3_time_fail)["G3"]
        self.assertFalse(g3_time["passed"])
        self.assertFalse(g3_time["criteria"]["medianAnalysisUnderLimit"])

        m_g3_solver_fail = [dict(r, solverQueries=100, solverLimitHits=5) for r in metrics]  # exact 0.05
        g3_solver = oracle.evaluate_go_no_go_gates(oracle_summary=summary, repo_metrics=m_g3_solver_fail)["G3"]
        self.assertFalse(g3_solver["passed"])
        self.assertFalse(g3_solver["criteria"]["solverLimitRateUnderLimit"])

        # Soundness sliceSuccess < 0.80 boundary FAILS
        bad_slice_rate = dict(summary, sliceRunSuccessRate=0.7999)
        self.assertFalse(
            oracle.evaluate_go_no_go_gates(oracle_summary=bad_slice_rate, repo_metrics=metrics)["soundness"]["passed"]
        )


class TestDryRunEndToEndOrchestration(unittest.TestCase):
    def test_dry_run_emits_deterministic_json_md_and_synthesized_java_without_docker(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            slices_file = root / "slices.json"
            out_dir = root / "out"
            slices_file.write_text(
                json.dumps(
                    {
                        "slices": [
                            {
                                "sliceId": "petclinic_cache",
                                "repoId": "spring-projects/spring-petclinic",
                                "targetBean": "org.springframework.cache.CacheManager",
                                "autoConfigClasses": [
                                    "org.springframework.boot.autoconfigure.cache.CacheAutoConfiguration",
                                ],
                                "userConfigClasses": [],
                                "predictedTruth": "MAY",
                                "mayExposureRatio": 0.5,
                                "witnesses": [
                                    {"worldId": "pos", "expectedPresent": True, "properties": {"spring.cache.type": "simple"}, "activeProfiles": []},
                                    {"worldId": "neg", "expectedPresent": False, "properties": {"spring.cache.type": "none"}, "activeProfiles": []},
                                ],
                            }
                        ]
                    },
                    indent=2,
                ),
                encoding="utf-8",
            )

            with mock.patch("subprocess.run", side_effect=AssertionError("Dry-run must NEVER call subprocess/Docker!")):
                rc = oracle.main(
                    [
                        "--manifest",
                        str(SUITE_DIR / "corpus_manifest.json"),
                        "--slices-json",
                        str(slices_file),
                        "--output-dir",
                        str(out_dir),
                        "--dry-run",
                        "--skip-env-probe",
                    ]
                )

            self.assertEqual(0, rc)
            json_report_path = out_dir / "e5_multi_repo_oracle_report.json"
            md_report_path = out_dir / "e5_multi_repo_oracle_report.md"
            self.assertTrue(json_report_path.is_file())
            self.assertTrue(md_report_path.is_file())

            report = json.loads(json_report_path.read_text(encoding="utf-8"))
            self.assertEqual("PLAN_ONLY_DRY_RUN", report["mode"])
            self.assertEqual("PLAN_ONLY_DRY_RUN", report["decision"])
            self.assertEqual(18, report["cohortCount"])
            self.assertTrue(report["sandboxPolicy"]["hostExecutionForbidden"])
            self.assertEqual("none", report["sandboxPolicy"]["network"])
            self.assertEqual(1, len(report["slices"]))
            self.assertIn("--network=none", report["slices"][0]["dockerCommand"])

            java_files = list((out_dir / "synthesized_slices").glob("*.java"))
            self.assertEqual(1, len(java_files))
            self.assertIn("ApplicationContextRunner", java_files[0].read_text(encoding="utf-8"))

            md = md_report_path.read_text(encoding="utf-8")
            self.assertIn("Closed-Denominator Soundness Summary", md)
            self.assertIn("Go/No-Go Gate Evaluation", md)
            self.assertIn("spring_sat_20_oss", md)

    def test_mixed_world_slice_and_offline_results_cli_go_vs_no_go(self) -> None:
        # Mixed slice: 1 resolved world with FALSE_MUST + 1 sibling world with RUNTIME_INIT_FAILED
        mixed = [
            {
                "sliceId": "mixed_slice",
                "repoId": "spring-cloud/spring-cloud-gateway",
                "predictedTruth": "MUST",
                "status": "EXECUTED",
                "compiled": True,
                "worlds": [
                    {"worldId": "w1", "expectedPresent": True, "actualPresent": False, "status": "RESOLVED"},
                    {"worldId": "w2", "expectedPresent": True, "actualPresent": None, "status": "RUNTIME_INIT_FAILED", "reason": "java.net.ConnectException: network blocked"},
                ],
            }
        ]
        summary = oracle.verify_closed_denominator_results(mixed)
        self.assertEqual(1, summary["totalSlices"])
        self.assertEqual(0, summary["successfulSlices"])
        self.assertEqual(1, summary["runtimeInitFailedSlices"])
        self.assertEqual({"NETWORK_OR_SOCKET_BLOCKED_IN_SANDBOX": 1}, summary["runtimeInitFailureReasons"])
        self.assertEqual(2, summary["executedWorlds"])
        self.assertEqual(1, summary["resolvedWorlds"])
        self.assertEqual(1, summary["falseMust"])

        # Parse stdout marker fallback
        parsed = oracle.parse_world_results_from_output(
            'some log\nEVO_ORACLE_WORLD_RESULT={"sliceId":"s1","worldId":"w1","expectedPresent":true,"actualPresent":true,"status":"RESOLVED","reason":null}\n'
        )
        self.assertEqual(1, len(parsed))
        self.assertEqual("w1", parsed[0]["worldId"])

        # Offline CLI verification returns 0 on GO and 1 on NO_GO
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            res_path = root / "results.json"
            out_go = root / "out_go"
            out_nogo = root / "out_nogo"
            good_payload = {
                "slices": [
                    {
                        "sliceId": "s1",
                        "repoId": "spring-cloud/spring-cloud-gateway",
                        "predictedTruth": "MUST",
                        "status": "EXECUTED",
                        "compiled": True,
                        "worlds": [
                            {"worldId": "w1", "expectedPresent": True, "actualPresent": True, "status": "RESOLVED"},
                        ],
                    }
                ],
                "repoMetrics": [
                    {"repoId": "spring-sat-oss/spring-cloud-gateway", "userStarterInteractions": 12, "junitConfirmedBugs": 2, "singleConfigFpsEliminated": 8, "analysisSeconds": 3.5, "solverQueries": 100, "solverLimitHits": 1},
                    {"repoId": "spring-sat-oss/spring-authorization-server", "userStarterInteractions": 11, "junitConfirmedBugs": 2, "singleConfigFpsEliminated": 7, "analysisSeconds": 4.5, "solverQueries": 100, "solverLimitHits": 1},
                    {"repoId": "spring-sat-oss/halo", "userStarterInteractions": 15, "junitConfirmedBugs": 1, "singleConfigFpsEliminated": 6, "analysisSeconds": 6.0, "solverQueries": 100, "solverLimitHits": 1},
                ],
            }
            res_path.write_text(json.dumps(good_payload), encoding="utf-8")
            rc_go = oracle.main(
                [
                    "--manifest", str(SUITE_DIR / "corpus_manifest.json"),
                    "--results-json", str(res_path),
                    "--output-dir", str(out_go),
                    "--skip-env-probe",
                ]
            )
            self.assertEqual(0, rc_go)
            go_rep = json.loads((out_go / "e5_multi_repo_oracle_report.json").read_text(encoding="utf-8"))
            self.assertEqual("GO", go_rep["decision"])

            # Flip 1 world to FALSE_MUST -> exit code 1 (NO_GO)
            good_payload["slices"][0]["worlds"][0]["actualPresent"] = False
            res_path.write_text(json.dumps(good_payload), encoding="utf-8")
            rc_nogo = oracle.main(
                [
                    "--manifest", str(SUITE_DIR / "corpus_manifest.json"),
                    "--results-json", str(res_path),
                    "--output-dir", str(out_nogo),
                    "--skip-env-probe",
                ]
            )
            self.assertEqual(1, rc_nogo)
            nogo_rep = json.loads((out_nogo / "e5_multi_repo_oracle_report.json").read_text(encoding="utf-8"))
            self.assertEqual("NO_GO", nogo_rep["decision"])


if __name__ == "__main__":
    unittest.main()
