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
import run_three_way_benchmark as tw  # noqa: E402


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


if __name__ == "__main__":
    unittest.main()
