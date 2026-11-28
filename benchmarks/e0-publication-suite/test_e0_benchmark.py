import json
import sys
import tempfile
import unittest
from pathlib import Path

SUITE_DIR = Path(__file__).resolve().parent
if str(SUITE_DIR) not in sys.path:
    sys.path.insert(0, str(SUITE_DIR))

import env_provenance as envp  # noqa: E402
import run_e0_benchmark as e0  # noqa: E402
import run_online_microsecend_benchmark as micro  # noqa: E402
import run_three_way_benchmark as tw  # noqa: E402

REPO_ROOT = SUITE_DIR.parent.parent
EMSE_REPLICATION_DIR = Path(r"D:\UIT\evo-benchmark-cache\EMSE2025SAR-Replication")


class TestE0BenchmarkHarness(unittest.TestCase):
    def test_manifest_contains_17_verified_microsecend_apps_and_no_banned_duplicates(self) -> None:
        manifest = json.loads((SUITE_DIR / "corpus_manifest.json").read_text(encoding="utf-8"))
        e0.validate_manifest(manifest)
        micro_ids = {item["id"] for item in manifest["suites"]["microsecend_17"]}
        self.assertEqual(17, len(micro_ids))
        self.assertIn("ewolff/microservice-kafka", micro_ids)
        self.assertNotIn("oversengineer/microservices-kafka", micro_ids)
        self.assertEqual(4, len(manifest["suites"]["flagship_polyglot_java"]))
        self.assertEqual(5, len(manifest["suites"]["s1_spring_spike_5"]))

    def test_validate_manifest_rejects_duplicate_or_banned_entry(self) -> None:
        manifest = json.loads((SUITE_DIR / "corpus_manifest.json").read_text(encoding="utf-8"))
        manifest["suites"]["microsecend_17"][4]["id"] = "oversengineer/microservices-kafka"
        with self.assertRaises(ValueError):
            e0.validate_manifest(manifest)

    def test_median_iqr_and_cv_stats(self) -> None:
        stats = e0.compute_median_iqr([10.0, 20.0, 30.0, 40.0, 100.0])
        self.assertEqual(30.0, stats["median"])
        self.assertEqual(55.0, stats["iqr"])
        self.assertEqual(10.0, stats["min"])
        self.assertEqual(100.0, stats["max"])
        self.assertEqual(5.0, stats["n"])
        self.assertEqual(40.0, stats["mean"])
        self.assertGreater(stats["stdev"], 35.0)
        self.assertGreater(stats["cv"], 0.8)

        single = e0.compute_median_iqr([12.5])
        self.assertEqual(12.5, single["median"])
        self.assertEqual(0.0, single["iqr"])
        self.assertEqual(0.0, single["cv"])

    def test_three_way_defaults_to_n5_and_aggregates_stage_decomposition(self) -> None:
        args = tw.build_arg_parser().parse_args(
            ["--repo-dir", "/tmp/repo", "--edit-file", "src/A.java"]
        )
        self.assertEqual(5, args.repeats)
        self.assertEqual(0, args.warmup)
        self.assertEqual(5, e0.DEFAULT_REPEATS)

        runs = []
        for i, (c, w, e, ce) in enumerate(
            [
                (1000.0, 200.0, 400.0, 1010.0),
                (1020.0, 210.0, 420.0, 1005.0),
                (980.0, 195.0, 410.0, 995.0),
                (1010.0, 205.0, 405.0, 1000.0),
                (990.0, 190.0, 395.0, 1020.0),
            ],
            start=1,
        ):
            runs.append(
                {
                    "iteration": f"rep-{i}",
                    "is_warmup": False,
                    "cold": {"elapsed_ms": c, "wall_ms": c + 5, "state": "PARTIAL", "gap_count": 10, "graph_id": "sha256:a", "stage_millis": {"ACQUISITION": c * 0.6, "ANALYSIS": c * 0.3, "GRAPH": c * 0.1}},
                    "warm_no_change": {"elapsed_ms": w, "wall_ms": w + 5, "state": "PARTIAL", "gap_count": 10, "graph_id": "sha256:a", "stage_millis": {"ACQUISITION": w * 0.2, "ANALYSIS": w * 0.5, "GRAPH": w * 0.3}},
                    "warm_1file_edit": {"elapsed_ms": e, "wall_ms": e + 5, "state": "PARTIAL", "gap_count": 10, "graph_id": "sha256:b", "stage_millis": {"ACQUISITION": e * 0.2, "ANALYSIS": e * 0.6, "GRAPH": e * 0.2}},
                    "cold_on_edited": {"elapsed_ms": ce, "wall_ms": ce + 5, "state": "PARTIAL", "gap_count": 10, "graph_id": "sha256:b", "stage_millis": {"ACQUISITION": ce * 0.6, "ANALYSIS": ce * 0.3, "GRAPH": ce * 0.1}},
                    "warm_no_change_matches_cold": True,
                    "edit_changed_graph": True,
                    "warm_edit_matches_cold_edited": True,
                }
            )

        wsl_env = {
            "schema": "evo.env-provenance:v1",
            **envp.classify_environment(
                host_system="Windows",
                execution_target="docker",
                docker_os="Docker Desktop",
                docker_kernel="6.6.87.2-microsoft-standard-WSL2",
                container_uname="Linux abc 6.6.87.2-microsoft-standard-WSL2 #1 SMP x86_64 Linux",
            ),
            "host": {"uname": "Windows 11 AMD64", "cpu_model": "TestCPU", "cpu_logical_cores": 16, "ram_total_gib": 32.0},
            "docker": {"server_version": "29.9.3", "operating_system": "Docker Desktop", "kernel_version": "6.6.87.2-microsoft-standard-WSL2"},
        }
        payload = tw.summarize_three_way_runs(
            repo_label="ewolff/microservice",
            repo_path="/tmp/ewolff",
            edit_file="OrderController.java",
            profile="strict",
            repeats=5,
            warmup=0,
            memory="2200m",
            java_opts="-Xmx1536m",
            offline_deps=False,
            environment=wsl_env,
            measured_runs=runs,
        )
        self.assertEqual(5.0, payload["summary"]["cold_elapsed_ms"]["n"])
        self.assertEqual(1000.0, payload["summary"]["cold_elapsed_ms"]["median"])
        self.assertGreater(payload["summary"]["cold_elapsed_ms"]["cv"], 0.0)
        self.assertEqual(5.0, payload["summary"]["speedup_warm_no_change_vs_cold"])
        self.assertIn("ACQUISITION", payload["summary"]["stage_decomposition_ms"]["cold"])
        self.assertEqual(5, payload["summary"]["warm_no_change_sha256_passes"])
        self.assertEqual(5, payload["summary"]["warm_edit_vs_cold_edited_sha256_passes"])

        md = tw.render_three_way_markdown(payload)
        tex = tw.render_three_way_latex(payload)
        self.assertIn("windows-host-docker-wsl2", md)
        self.assertIn("NON-NATIVE-UBUNTU-LTS", md)
        self.assertIn("ACQUISITION", md)
        self.assertIn("NON-NATIVE-UBUNTU-LTS", tex)
        self.assertIn("n=5", md)

    def test_environment_classification_never_mislabels_wsl2_as_native_ubuntu_lts(self) -> None:
        wsl_docker = envp.classify_environment(
            host_system="Windows",
            execution_target="docker",
            docker_os="Docker Desktop",
            docker_kernel="6.6.87.2-microsoft-standard-WSL2",
        )
        self.assertEqual("windows-host-docker-wsl2", wsl_docker["environment_class"])
        self.assertTrue(wsl_docker["is_wsl2"])
        self.assertFalse(wsl_docker["is_native_ubuntu_lts"])
        self.assertIn("NON-NATIVE-UBUNTU-LTS", wsl_docker["publication_caveat"])

        wsl_linux = envp.classify_environment(
            host_system="Linux",
            host_proc_version="Linux version 5.15.153.1-microsoft-standard-WSL2 (root@build)",
            host_os_release={"ID": "ubuntu", "VERSION": "24.04.1 LTS (Noble Numbat)", "PRETTY_NAME": "Ubuntu 24.04.1 LTS"},
            execution_target="host",
        )
        self.assertEqual("wsl2-linux", wsl_linux["environment_class"])
        self.assertTrue(wsl_linux["is_wsl2"])
        self.assertFalse(wsl_linux["is_native_ubuntu_lts"])
        self.assertIn("NON-NATIVE-UBUNTU-LTS", wsl_linux["publication_caveat"])

        native_ubuntu = envp.classify_environment(
            host_system="Linux",
            host_proc_version="Linux version 6.8.0-49-generic (buildd@lcy02-amd64)",
            host_os_release={"ID": "ubuntu", "VERSION": "24.04.1 LTS (Noble Numbat)", "PRETTY_NAME": "Ubuntu 24.04.1 LTS"},
            execution_target="docker",
            docker_os="Ubuntu 24.04.1 LTS",
            docker_kernel="6.8.0-49-generic",
        )
        self.assertEqual("native-linux-ubuntu-lts-docker", native_ubuntu["environment_class"])
        self.assertFalse(native_ubuntu["is_wsl2"])
        self.assertTrue(native_ubuntu["is_native_ubuntu_lts"])
        self.assertEqual("", native_ubuntu["publication_caveat"])

    def test_micro_and_macro_f1_scoring(self) -> None:
        app1 = ({"a", "b", "c"}, {"a", "b", "d"})  # tp=2, fp=1, fn=1 -> P=2/3, R=2/3, F1=2/3
        app2 = ({"x"}, {"x"})  # tp=1, fp=0, fn=0 -> P=1, R=1, F1=1
        res = e0.compute_micro_macro_f1([app1, app2])
        self.assertEqual(3, res["micro"]["tp"])
        self.assertEqual(1, res["micro"]["fp"])
        self.assertEqual(1, res["micro"]["fn"])
        self.assertAlmostEqual(0.75, res["micro"]["f1"], places=5)
        self.assertAlmostEqual((2.0 / 3.0 + 1.0) / 2.0, res["macro"]["f1"], places=5)

    def test_sha256_parity_fails_closed_on_mismatch(self) -> None:
        e0.verify_sha256_parity("sha256:" + "a" * 64, "sha256:" + "a" * 64, "ok")
        with self.assertRaises(ValueError):
            e0.verify_sha256_parity("sha256:" + "a" * 64, "sha256:" + "b" * 64, "drift")

    def test_apply_and_rollback_single_file_edit_restores_bytes_on_exception(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            java_file = Path(tmp) / "Demo.java"
            original = "package demo;\npublic class Demo {\n}\n"
            java_file.write_text(original, encoding="utf-8")

            def _failing_cb() -> None:
                self.assertIn("e0BenchmarkEditProbeMethod", java_file.read_text(encoding="utf-8"))
                raise RuntimeError("boom")

            with self.assertRaises(RuntimeError):
                e0.apply_and_rollback_single_file_edit(java_file, e0.mutate_java_for_edit, _failing_cb)
            self.assertEqual(original, java_file.read_text(encoding="utf-8"))

    def test_render_markdown_and_latex_tables_with_environment_provenance(self) -> None:
        sample = {
            "timestamp_utc": "2026-11-27T00:00:00Z",
            "jar": "evo.jar",
            "heap": "4g",
            "repeats": 5,
            "warmup": 1,
            "environment": {
                "environment_class": "windows-host-docker-wsl2",
                "is_wsl2": True,
                "is_native_ubuntu_lts": False,
                "publication_caveat": "NON-NATIVE-UBUNTU-LTS: WSL2 Docker Desktop",
                "host": {"uname": "Windows 11", "cpu_model": "CPU", "cpu_logical_cores": 8, "ram_total_gib": 32.0},
            },
            "runs": [
                {
                    "app_id": "ewolff/microservice-kafka",
                    "profile": "strict",
                    "repeats": 5,
                    "cold_ms": {"median": 1200.0, "iqr": 50.0, "cv": 0.04},
                    "warm_no_change_ms": {"median": 210.0, "iqr": 12.0, "cv": 0.03},
                    "warm_1file_edit_ms": {"median": 450.0, "iqr": 25.0, "cv": 0.05},
                    "cold_vs_warm_nc_sha256_match": True,
                    "warm_edit_vs_clean_sha256_match": True,
                }
            ],
            "spike_runs": [],
        }
        md = e0.render_markdown_report(sample)
        tex = e0.render_latex_table(sample)
        self.assertIn("ewolff/microservice-kafka", md)
        self.assertIn("PASS", md)
        self.assertIn("windows-host-docker-wsl2", md)
        self.assertIn("NON-NATIVE-UBUNTU-LTS", md)
        self.assertIn(r"\begin{table}", tex)
        self.assertIn(r"\checkmark", tex)
        self.assertIn("NON-NATIVE-UBUNTU-LTS", tex)

    def test_three_way_job_payload_matches_analysis_job_api_and_marker_is_quote_free(self) -> None:
        payload = tw.build_job_submission_payload("local", "strict", True, "idem-1")
        self.assertEqual("local", payload["setup"])
        self.assertNotIn("setupId", payload)
        self.assertEqual(
            {"setup", "force", "idempotencyKey", "projectionProfile"},
            set(payload.keys()),
        )
        self.assertNotIn('"', tw.DEFAULT_EDIT_MARKER)
        self.assertNotIn("'", tw.DEFAULT_EDIT_MARKER)
        help_text = tw.build_arg_parser().format_help()
        self.assertIn("-Devo.workbench.offline=true", help_text)
        self.assertNotIn("-Devo.offline=true", help_text)

    def test_prepare_repo_commands_use_root_exec_to_strip_git_and_chown_for_nonroot_user(self) -> None:
        cmds = tw.build_prepare_repo_commands("evo-c1", Path("/tmp/src"), "/tmp/bench-repo")
        self.assertEqual(3, len(cmds))
        clean_cmd, _ = cmds[0]
        copy_cmd, _ = cmds[1]
        chown_cmd, _ = cmds[2]
        self.assertEqual(["exec", "-u", "0", "evo-c1", "sh", "-c"], clean_cmd[:6])
        self.assertEqual("cp", copy_cmd[0])
        self.assertEqual(["exec", "-u", "0", "evo-c1", "sh", "-c"], chown_cmd[:6])
        self.assertIn("rm -rf '/tmp/bench-repo/.git'", chown_cmd[6])
        self.assertIn("chown -R", chown_cmd[6])
        self.assertNotIn("|| true", chown_cmd[6])


class TestOnlineMicroSecEnDHarnessWP05(unittest.TestCase):
    def test_parse_emse_baselines_synthetic_fp_blocks_and_weighted_endpoints(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            rep = Path(tmp)
            pr = rep / "tool_comparison" / "results" / "recovery_correctness" / "processed_results"

            # Tool 1: mixed FP: block (component + '->' + ' - ' + malformed 'consul- consul') and bare FP block
            agg_dir = pr / "synthetic-agg"
            agg_dir.mkdir(parents=True)
            (agg_dir / "app1.txt").write_text(
                "\n".join([
                    "Components:",
                    "TP svc_a (a)",
                    "FN svc_b",
                    "",
                    "Connections:",
                    "TP svc_a -> svc_b",
                    "FN svc_b -> svc_a",
                    "",
                    "FP:",
                    "docker host",
                    "redis:latest",
                    "outside - svc_a",
                    "consul- consul",
                ]),
                encoding="utf-8",
            )
            (agg_dir / "app2.txt").write_text(
                "\n".join([
                    "Components:",
                    "TP svc_c",
                    "",
                    "Connections:",
                    "TP svc_c -> svc_d",
                    "",
                    "FP",
                    "outside",
                    "outside - svc_c",
                    "svc_c -> svc_c",
                ]),
                encoding="utf-8",
            )

            # Tool 2: weighted Endpoints TP(k)/FP(k)/FN(k) + trailing inline connection FP after Endpoints
            ep_dir = pr / "synthetic-ep"
            ep_dir.mkdir(parents=True)
            (ep_dir / "app1.txt").write_text(
                "\n".join([
                    "Components:",
                    "/ svc_a",
                    "",
                    "Connections:",
                    "TP svc_a -> svc_b",
                    "FN svc_b -> svc_a",
                    "",
                    "Endpoints:",
                    'TP(3) svc_a: "/", "/items", "/items/{id}"',
                    'FP(2) svc_a: "/legacy", "/debug"',
                    'FN(1) svc_b: "/health"',
                    "",
                    "FP svc_a -> svc_c",
                ]),
                encoding="utf-8",
            )

            parsed = {row["tool"]: row for row in micro.parse_emse_baselines(rep)}
            self.assertEqual({"synthetic-agg", "synthetic-ep"}, set(parsed.keys()))

            agg = parsed["synthetic-agg"]
            self.assertEqual(2, agg["apps_completed"])
            self.assertEqual((2, 3, 1), (agg["components"]["tp"], agg["components"]["fp"], agg["components"]["fn"]))
            self.assertEqual((2, 4, 1), (agg["connections"]["tp"], agg["connections"]["fp"], agg["connections"]["fn"]))
            self.assertEqual((0, 0, 0), (agg["endpoints"]["tp"], agg["endpoints"]["fp"], agg["endpoints"]["fn"]))

            ep = parsed["synthetic-ep"]
            self.assertEqual(1, ep["apps_completed"])
            self.assertEqual((0, 0, 0), (ep["components"]["tp"], ep["components"]["fp"], ep["components"]["fn"]))
            self.assertEqual((1, 1, 1), (ep["connections"]["tp"], ep["connections"]["fp"], ep["connections"]["fn"]))
            self.assertEqual((3, 2, 1), (ep["endpoints"]["tp"], ep["endpoints"]["fp"], ep["endpoints"]["fn"]))
            self.assertAlmostEqual(0.6667, ep["endpoints"]["micro_f1"], places=4)

    def test_parse_emse_baselines_real_replication_exact_counts(self) -> None:
        if not (EMSE_REPLICATION_DIR / "tool_comparison" / "results" / "recovery_correctness" / "processed_results").is_dir():
            self.skipTest(f"EMSE2025SAR-Replication not present at {EMSE_REPLICATION_DIR}")

        by_tool = {row["tool"]: row for row in micro.parse_emse_baselines(EMSE_REPLICATION_DIR)}
        self.assertEqual(
            {"attack-graph-generator", "code2DFD", "MicroDepGraph", "microMiner", "Prophet", "RAD", "RAD-source"},
            set(by_tool.keys()),
        )

        # code2DFD exact parity with processed_results & complete_results.xlsx
        c2d = by_tool["code2DFD"]
        self.assertEqual(17, c2d["apps_completed"])
        self.assertEqual((178, 5, 4), (c2d["components"]["tp"], c2d["components"]["fp"], c2d["components"]["fn"]))
        self.assertEqual(0.9753, c2d["components"]["micro_f1"])
        self.assertEqual((320, 18, 65), (c2d["connections"]["tp"], c2d["connections"]["fp"], c2d["connections"]["fn"]))
        self.assertEqual(0.8852, c2d["connections"]["micro_f1"])
        self.assertEqual((86, 13, 74), (c2d["endpoints"]["tp"], c2d["endpoints"]["fp"], c2d["endpoints"]["fn"]))
        self.assertEqual(0.6641, c2d["endpoints"]["micro_f1"])

        # attack-graph-generator exact parity with official complete_results.xlsx & EMSE 2025 Table 5
        # (includes Tap-And-Eat bare 'FP' header + microservices-sample 'consul- consul' edge)
        agg = by_tool["attack-graph-generator"]
        self.assertEqual(12, agg["apps_completed"])
        self.assertEqual((123, 41, 21), (agg["components"]["tp"], agg["components"]["fp"], agg["components"]["fn"]))
        self.assertEqual(0.7987, agg["components"]["micro_f1"])
        self.assertEqual((279, 432, 45), (agg["connections"]["tp"], agg["connections"]["fp"], agg["connections"]["fn"]))
        self.assertEqual(0.5391, agg["connections"]["micro_f1"])

        # Remaining tools in processed_results
        mdg = by_tool["MicroDepGraph"]
        self.assertEqual((84, 0, 26), (mdg["components"]["tp"], mdg["components"]["fp"], mdg["components"]["fn"]))
        self.assertEqual((124, 2, 121), (mdg["connections"]["tp"], mdg["connections"]["fp"], mdg["connections"]["fn"]))

        mmi = by_tool["microMiner"]
        self.assertEqual((32, 1, 25), (mmi["components"]["tp"], mmi["components"]["fp"], mmi["components"]["fn"]))

        pro = by_tool["Prophet"]
        self.assertEqual((27, 21, 155), (pro["components"]["tp"], pro["components"]["fp"], pro["components"]["fn"]))
        self.assertEqual((4, 1, 381), (pro["connections"]["tp"], pro["connections"]["fp"], pro["connections"]["fn"]))

        rad = by_tool["RAD"]
        self.assertEqual((90, 6, 42), (rad["endpoints"]["tp"], rad["endpoints"]["fp"], rad["endpoints"]["fn"]))
        self.assertEqual(0.7895, rad["endpoints"]["micro_f1"])

        ras = by_tool["RAD-source"]
        self.assertEqual((7, 2, 378), (ras["connections"]["tp"], ras["connections"]["fp"], ras["connections"]["fn"]))
        self.assertEqual((86, 9, 74), (ras["endpoints"]["tp"], ras["endpoints"]["fp"], ras["endpoints"]["fn"]))

    def test_parse_groundtruth_txt_and_dual_raw_vs_collapsed_collision_preservation(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            gt_file = Path(tmp) / "demo-app.txt"
            gt_file.write_text(
                "\n".join([
                    "Components:",
                    "api_gateway",
                    "user",
                    "user_service",
                    "",
                    "Connections:",
                    "api_gateway -> user",
                    "api_gateway -> user_service",
                    "",
                    "Endpoints:",
                    'user_service: "/users", "api/users/{userId}"',
                    'api_gateway: "/"',
                ]),
                encoding="utf-8",
            )
            comps, conns, endpoints = micro.parse_groundtruth_txt(gt_file)
            self.assertEqual({"api_gateway", "user", "user_service"}, comps)
            self.assertEqual({("api_gateway", "user"), ("api_gateway", "user_service")}, conns)
            self.assertEqual(
                {("user_service", "/users"), ("user_service", "api/users/{userId}"), ("api_gateway", "/")},
                endpoints,
            )

            # Case 1: Evo misses external actor 'user' and edge 'api_gateway -> user'
            scored_partial = micro.score_app_predictions(
                gt_comps_raw=comps,
                gt_conns_raw=conns,
                gt_endpoints_raw=endpoints,
                pred_names=["api-gateway", "user-service"],
                pred_edges=[("api-gateway", "user-service")],
                pred_endpoints=[("user-service", "/users"), ("user-service", "/api/users/{id}"), ("api-gateway", "/")],
                app_dir="demo-app",
            )
            den = scored_partial["gt_denominators"]
            self.assertEqual({"components": 3, "connections": 2, "endpoints": 3}, den["raw"])
            self.assertEqual({"components": 2, "connections": 1, "endpoints": 3}, den["collapsed"])
            self.assertEqual({"user": ["user", "user_service"]}, den["collapsed_collisions"]["components"])
            self.assertIn("apigateway -> user", den["collapsed_collisions"]["connections"])

            # Collapsed hides the missed 'user' component and edge (denominator 2 & 1 -> F1 1.0)
            self.assertEqual(1.0, scored_partial["collapsed_metrics"]["components"]["f1"])
            self.assertEqual(1.0, scored_partial["collapsed_metrics"]["connections"]["f1"])
            # Raw-GT preserves exact denominator 3 & 2 -> honest FN=1 on both COMP and CONN
            raw_c = scored_partial["raw_gt_metrics"]["components"]
            raw_e = scored_partial["raw_gt_metrics"]["connections"]
            self.assertEqual((2, 0, 1), (raw_c["tp"], raw_c["fp"], raw_c["fn"]))
            self.assertEqual(3, raw_c["tp"] + raw_c["fn"])
            self.assertEqual((1, 0, 1), (raw_e["tp"], raw_e["fp"], raw_e["fn"]))
            self.assertEqual(2, raw_e["tp"] + raw_e["fn"])

            # Endpoint path variable canonicalization: {id} matches {userId} in raw_gt / canon / collapsed, not strict
            self.assertEqual((2, 1, 1), (
                scored_partial["strict_metrics"]["endpoints"]["tp"],
                scored_partial["strict_metrics"]["endpoints"]["fp"],
                scored_partial["strict_metrics"]["endpoints"]["fn"],
            ))
            self.assertEqual((3, 0, 0), (
                scored_partial["raw_gt_metrics"]["endpoints"]["tp"],
                scored_partial["raw_gt_metrics"]["endpoints"]["fp"],
                scored_partial["raw_gt_metrics"]["endpoints"]["fn"],
            ))

            # Case 2: Evo recovers BOTH 'user' and 'user-service' -> 100% on Raw-GT
            scored_full = micro.score_app_predictions(
                gt_comps_raw=comps,
                gt_conns_raw=conns,
                gt_endpoints_raw=endpoints,
                pred_names=["api-gateway", "user", "user-service"],
                pred_edges=[("api-gateway", "user"), ("api-gateway", "user-service")],
                pred_endpoints=[("user-service", "/users"), ("user-service", "/api/users/{userId}"), ("api-gateway", "/")],
                app_dir="demo-app",
            )
            self.assertEqual((3, 0, 0), (
                scored_full["raw_gt_metrics"]["components"]["tp"],
                scored_full["raw_gt_metrics"]["components"]["fp"],
                scored_full["raw_gt_metrics"]["components"]["fn"],
            ))
            self.assertEqual((2, 0, 0), (
                scored_full["raw_gt_metrics"]["connections"]["tp"],
                scored_full["raw_gt_metrics"]["connections"]["fp"],
                scored_full["raw_gt_metrics"]["connections"]["fn"],
            ))

    def test_groundtruth_corpus_real_totals_raw_182_385_160_vs_collapsed_179_383(self) -> None:
        gt_dir = EMSE_REPLICATION_DIR / "tool_comparison" / "groundtruth_textual"
        if not gt_dir.is_dir():
            self.skipTest(f"groundtruth_textual not present at {gt_dir}")

        corpus = micro.summarize_groundtruth_corpus(gt_dir, manifest_path=SUITE_DIR / "corpus_manifest.json")
        self.assertEqual(17, corpus["apps"])
        self.assertEqual({"components": 182, "connections": 385, "endpoints": 160}, corpus["raw"])
        self.assertEqual({"components": 182, "connections": 385, "endpoints": 160}, corpus["strict"])
        self.assertEqual({"components": 179, "connections": 383, "endpoints": 160}, corpus["canonical"])
        self.assertEqual({"components": 179, "connections": 383, "endpoints": 160}, corpus["collapsed"])
        self.assertEqual({"components": 3, "connections": 2, "endpoints": 0}, corpus["dropped_by_collapse"])
        self.assertEqual(
            [
                "apssouza22/java-microservice",
                "mdeket/spring-cloud-movie-recommendation",
                "shabbirdwd53/springboot-microservice",
            ],
            corpus["apps_with_collisions"],
        )

    def test_endpoint_extraction_and_scoring_on_sample_evo_api_payloads(self) -> None:
        build_dir_to_name = {
            "services/cart-service": "cart-service",
            "services/product-service": "product-service",
            "services/content-service": "content-service",
        }
        sorted_dirs = sorted(build_dir_to_name.keys(), key=len, reverse=True)

        def resolve_path(rel_path: str) -> str:
            norm = rel_path.replace("\\", "/").lstrip("./")
            for bdir in sorted_dirs:
                if norm == bdir or norm.startswith(bdir + "/"):
                    return build_dir_to_name[bdir]
            return norm.split("/", 1)[0] if "/" in norm else norm

        module_id_to_path = {
            "module:sha256:cart": "services/cart-service",
            "module:sha256:prod": "services/product-service",
        }
        evidence_store = {
            "sha256:jaxrs-ev": {
                "family": "jaxrs.endpoint-declaration",
                "metadata": {
                    "module": "services/content-service",
                    "sourceSet": "MAIN",
                    "route": {"path": "/", "methods": ["GET"]},
                },
            },
            "sha256:test-ev": {
                "family": "spring.endpoint-declaration",
                "metadata": {
                    "module": "services/cart-service",
                    "sourceSet": "TEST",
                    "route": {"path": "/test-only", "methods": ["GET"]},
                },
            },
        }

        sample_records = [
            # Spring ENDPOINT with canonicalName + module digest
            (
                {
                    "identity": "endpoint-declaration:sha256:1",
                    "kind": "ENDPOINT",
                    "canonicalName": "GET /",
                    "module": "module:sha256:cart",
                    "origin": "PROJECT",
                    "sourceSet": "MAIN",
                    "evidence": ["sha256:spring-1"],
                },
                {"identity": "analysis:1", "kind": "java", "modulePath": "services/cart-service"},
            ),
            # Spring ENDPOINT with multi-method prefix resolved via ctx modulePath
            (
                {
                    "identity": "endpoint-declaration:sha256:2",
                    "kind": "ENDPOINT",
                    "canonicalName": "GET,POST /count/",
                    "module": "",
                    "origin": "PROJECT",
                    "sourceSet": "MAIN",
                    "evidence": ["sha256:spring-2"],
                },
                {"identity": "analysis:1", "kind": "java", "modulePath": "services/cart-service"},
            ),
            # Polyglot ROUTE_CANDIDATE "<file>#<METHOD> <path>"
            (
                {
                    "identity": "route:sha256:3",
                    "kind": "ROUTE_CANDIDATE",
                    "canonicalName": "services/cart-service/src/main/java/CartController.java#POST /add/{seoName}",
                    "spelling": "POST /add/{seoName} [java]",
                    "origin": "PROJECT",
                    "evidence": ["sha256:poly-3"],
                },
                {"identity": "snapshot:1", "kind": "repository"},
            ),
            # JAX-RS ENDPOINT where canonicalName is digest-only -> resolved via evidence_fetcher
            (
                {
                    "identity": "endpoint-declaration:sha256:4",
                    "kind": "ENDPOINT",
                    "canonicalName": "endpoint-declaration:sha256:4",
                    "origin": "PROJECT",
                    "evidence": ["sha256:jaxrs-ev"],
                },
                {"identity": "analysis:2", "kind": "java"},
            ),
            # Spring ENDPOINT on product-service
            (
                {
                    "identity": "endpoint-declaration:sha256:5",
                    "kind": "ENDPOINT",
                    "canonicalName": "GET /{productSlug}",
                    "module": "module:sha256:prod",
                    "origin": "PROJECT",
                    "sourceSet": "MAIN",
                    "evidence": ["sha256:spring-5"],
                },
                {"identity": "analysis:3", "kind": "java", "modulePath": "services/product-service"},
            ),
            # Filtered out: TEST sourceSet on entity
            (
                {
                    "identity": "endpoint-declaration:sha256:6",
                    "kind": "ENDPOINT",
                    "canonicalName": "GET /mock",
                    "module": "module:sha256:cart",
                    "origin": "PROJECT",
                    "sourceSet": "TEST",
                },
                {"identity": "analysis:1", "kind": "java"},
            ),
            # Filtered out: TEST sourceSet via evidence metadata
            (
                {
                    "identity": "endpoint-declaration:sha256:7",
                    "kind": "ENDPOINT",
                    "canonicalName": "endpoint-declaration:sha256:7",
                    "origin": "PROJECT",
                    "evidence": ["sha256:test-ev"],
                },
                {"identity": "analysis:1", "kind": "java"},
            ),
            # Filtered out: DEPENDENCY origin
            (
                {
                    "identity": "endpoint-declaration:sha256:8",
                    "kind": "ENDPOINT",
                    "canonicalName": "GET /actuator/health",
                    "module": "module:sha256:cart",
                    "origin": "DEPENDENCY",
                    "sourceSet": "MAIN",
                },
                {"identity": "analysis:1", "kind": "java"},
            ),
            # Filtered out: test file path in ROUTE_CANDIDATE
            (
                {
                    "identity": "route:sha256:9",
                    "kind": "ROUTE_CANDIDATE",
                    "canonicalName": "services/cart-service/src/test/java/CartControllerTest.java#GET /fake",
                    "origin": "PROJECT",
                },
                {"identity": "snapshot:1", "kind": "repository"},
            ),
            # Filtered out: non-path GraphQL ROUTE_CANDIDATE
            (
                {
                    "identity": "route:sha256:10",
                    "kind": "ROUTE_CANDIDATE",
                    "canonicalName": "services/cart-service/schema.graphql#getCart",
                    "origin": "PROJECT",
                },
                {"identity": "snapshot:1", "kind": "repository"},
            ),
        ]

        extracted = micro.extract_endpoints_from_items(
            endpoint_records=sample_records,
            resolve_path_fn=resolve_path,
            module_id_to_path=module_id_to_path,
            evidence_fetcher=lambda ref: evidence_store.get(ref, {}),
        )
        extracted_pairs = [(item["service"], item["path"]) for item in extracted]
        self.assertEqual(
            [
                ("cart-service", "/"),
                ("cart-service", "/add/{seoName}"),
                ("cart-service", "/count"),
                ("content-service", "/"),
                ("product-service", "/{productSlug}"),
            ],
            extracted_pairs,
        )

        # Score against apache-spring-boot-microservice-example style GT endpoints (6 total)
        gt_eps = {
            ("cart_service", "/"),
            ("cart_service", "/count"),
            ("cart_service", "/add/{seoName}"),
            ("content_service", "/"),
            ("product_service", "/"),
            ("product_service", "/{seoName}"),
        }
        scored = micro.score_app_predictions(
            gt_comps_raw={"cart_service", "content_service", "product_service"},
            gt_conns_raw=set(),
            gt_endpoints_raw=gt_eps,
            pred_names=["cart-service", "content-service", "product-service"],
            pred_edges=[],
            pred_endpoints=extracted_pairs,
            app_dir="apache-spring-boot-microservice-example",
        )
        # Strict: /{productSlug} != /{seoName} -> TP=4, FP=1, FN=2
        self.assertEqual((4, 1, 2), (
            scored["strict_metrics"]["endpoints"]["tp"],
            scored["strict_metrics"]["endpoints"]["fp"],
            scored["strict_metrics"]["endpoints"]["fn"],
        ))
        # Raw-GT & Collapsed canonicalize {productSlug} == {seoName} -> TP=5, FP=0, FN=1 (missing product_service '/')
        self.assertEqual((5, 0, 1), (
            scored["raw_gt_metrics"]["endpoints"]["tp"],
            scored["raw_gt_metrics"]["endpoints"]["fp"],
            scored["raw_gt_metrics"]["endpoints"]["fn"],
        ))
        self.assertEqual((5, 0, 1), (
            scored["collapsed_metrics"]["endpoints"]["tp"],
            scored["collapsed_metrics"]["endpoints"]["fp"],
            scored["collapsed_metrics"]["endpoints"]["fn"],
        ))

        # Verify _ep_ sets round-trip through serialize_sets / deserialize_sets
        ser = micro.serialize_sets(scored["_sets"])
        deser = micro.deserialize_sets(ser)
        self.assertEqual(scored["_sets"]["gt_ep_strict"], deser["gt_ep_strict"])
        self.assertEqual(scored["_sets"]["pr_ep_col"], deser["pr_ep_col"])

    def test_render_markdown_report_emits_raw_and_collapsed_gt_and_endpoints(self) -> None:
        row = {
            "app_id": "apssouza22/java-microservice",
            "status": "PARTIAL",
            "gt_components": 15,
            "gt_connections": 34,
            "gt_endpoints": 17,
            "gt_denominators": {
                "raw": {"components": 15, "connections": 34, "endpoints": 17},
                "strict": {"components": 15, "connections": 34, "endpoints": 17},
                "canonical": {"components": 14, "connections": 34, "endpoints": 17},
                "collapsed": {"components": 14, "connections": 34, "endpoints": 17},
                "collapsed_collisions": {"components": {"user": ["user", "user_service"]}, "connections": {}, "endpoints": {}},
            },
            "raw_gt_metrics": {
                "components": micro.compute_prf_counts(14, 0, 1),
                "connections": micro.compute_prf_counts(30, 2, 4),
                "endpoints": micro.compute_prf_counts(15, 1, 2),
            },
            "strict_metrics": {
                "components": micro.compute_prf_counts(14, 0, 1),
                "connections": micro.compute_prf_counts(30, 2, 4),
                "endpoints": micro.compute_prf_counts(15, 1, 2),
            },
            "canon_metrics": {
                "components": micro.compute_prf_counts(14, 0, 0),
                "connections": micro.compute_prf_counts(30, 2, 4),
                "endpoints": micro.compute_prf_counts(15, 1, 2),
            },
            "collapsed_metrics": {
                "components": micro.compute_prf_counts(14, 0, 0),
                "connections": micro.compute_prf_counts(30, 2, 4),
                "endpoints": micro.compute_prf_counts(15, 1, 2),
            },
        }
        gt_den = micro.aggregate_gt_denominators([row])
        summary = {
            "timestamp_utc": "2026-11-28T00:00:00Z",
            "apps_count": 1,
            "gt_denominators": gt_den,
            "profiles": {
                "strict": {
                    "gt_denominators": gt_den,
                    "raw_gt_denominator": {
                        k: micro.aggregate_rows([row], "raw_gt_metrics", k)
                        for k in ("components", "connections", "endpoints")
                    },
                    "collapsed_canonical": {
                        k: micro.aggregate_rows([row], "collapsed_metrics", k)
                        for k in ("components", "connections", "endpoints")
                    },
                }
            },
            "emse2025sar_baselines": [
                {
                    "tool": "code2DFD",
                    "apps_completed": 17,
                    "components": {"tp": 178, "fp": 5, "fn": 4, "micro_f1": 0.9753},
                    "connections": {"tp": 320, "fp": 18, "fn": 65, "micro_f1": 0.8852},
                    "endpoints": {"tp": 86, "fp": 13, "fn": 74, "micro_f1": 0.6641},
                }
            ],
            "runs": {"strict": [row]},
        }
        md = micro.render_markdown_report(summary)
        self.assertIn("Raw `15 / 34 / 17`", md)
        self.assertIn("Collapsed `14 / 34 / 17`", md)
        self.assertIn("apssouza22/java-microservice", md)
        self.assertIn("Raw GT (collision-safe)", md)
        self.assertIn("`86/13/74` (0.6641)", md)

    def test_causalrepair_doi_fixed_in_reports(self) -> None:
        report = (REPO_ROOT / "docs" / "evo-comparative-evaluation-report.md").read_text(encoding="utf-8")
        masterplan = (REPO_ROOT / "docs" / "evo-improvement-and-publication-masterplan.md").read_text(encoding="utf-8")
        self.assertIn("10.1145/3832225", report)
        self.assertNotIn("10.1145/3832252", report)
        self.assertIn("10.1145/3832225", masterplan)
        self.assertNotIn("10.1145/3832252", masterplan)


if __name__ == "__main__":
    unittest.main()
