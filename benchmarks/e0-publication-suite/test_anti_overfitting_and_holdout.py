#!/usr/bin/env python3
"""Unit tests for anti_overfitting_audit.py and blind_holdout_split.json / evaluate_blind_holdout.py."""

from __future__ import annotations

import json
import sys
import tempfile
import unittest
from pathlib import Path

SUITE_DIR = Path(__file__).resolve().parent
REPO_ROOT = SUITE_DIR.parent.parent
sys.path.insert(0, str(SUITE_DIR))

import anti_overfitting_audit  # noqa: E402
import evaluate_blind_holdout  # noqa: E402


class AntiOverfittingAuditTest(unittest.TestCase):
    def test_live_production_codebase_has_zero_benchmark_leaks(self) -> None:
        manifest = SUITE_DIR / "corpus_manifest.json"
        gt_dir = Path(r"D:\UIT\evo-benchmark-cache\EMSE2025SAR-Replication\tool_comparison\groundtruth_textual")
        report = anti_overfitting_audit.scan_repository(
            REPO_ROOT,
            manifest_path=manifest,
            gt_dir=gt_dir if gt_dir.is_dir() else None,
        )
        self.assertGreater(report["files_scanned"], 500)
        self.assertEqual(
            0,
            report["leaks_count"],
            msg=f"Production codebase must have zero benchmark leaks, found: {report['findings']}",
        )
        self.assertEqual("PASS", report["status"])

    def test_audit_catches_injected_service_name_dataset_hack_and_regex_proxy_leaks(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            src = root / "analyzer" / "src" / "main" / "java" / "com" / "example"
            src.mkdir(parents=True)
            (src / "Leaky.java").write_text(
                "\n".join([
                    'class Leaky {',
                    '  String a = "redis-cart";',
                    '  String b = "external-website";',
                    '  String c = "mail-server";',
                    '  String d = "zipkin-tracing";',
                    '  String e = "web-application";',
                    '  String f = "piggymetrics";',
                    '  String g = "redis.*cart";',
                    '  String h = "thingsboard";',
                    '  String i = "openmrs";',
                    '  String j = "microsecend";',
                    '}',
                ]),
                encoding="utf-8",
            )
            report = anti_overfitting_audit.scan_repository(root, manifest_path=SUITE_DIR / "corpus_manifest.json")
            self.assertEqual("FAIL", report["status"])
            rules = {f["rule"] for f in report["findings"]}
            for expected in (
                "redis-cart", "external-website", "mail-server", "zipkin-tracing",
                "web-application", "piggymetrics", "proxy:redis-cart",
                "thingsboard", "openmrs", "microsecend",
            ):
                self.assertIn(expected, rules, f"Expected audit to catch {expected}, got rules={rules}")
            exit_code = anti_overfitting_audit.main(["--repo-root", str(root)])
            self.assertEqual(1, exit_code)

    def test_audit_ignores_test_directories(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            test_src = root / "analyzer" / "src" / "test" / "java" / "com" / "example"
            test_src.mkdir(parents=True)
            (test_src / "FixtureTest.java").write_text('class FixtureTest { String x = "redis-cart"; }\n', encoding="utf-8")
            report = anti_overfitting_audit.scan_repository(root)
            self.assertEqual(0, report["files_scanned"])
            self.assertEqual(0, report["leaks_count"])


class BlindHoldoutProtocolTest(unittest.TestCase):
    def test_split_definition_is_disjoint_complete_and_stratified(self) -> None:
        split_doc = json.loads((SUITE_DIR / "blind_holdout_split.json").read_text(encoding="utf-8"))
        manifest_doc = json.loads((SUITE_DIR / "corpus_manifest.json").read_text(encoding="utf-8"))
        evaluate_blind_holdout.validate_split_definition(split_doc, manifest_doc)

        split = split_doc["microsecend_17_stratified_split"]
        dev_builds = [x["build"] for x in split["dev_10"]]
        hold_builds = [x["build"] for x in split["holdout_7"]]
        self.assertEqual(2, dev_builds.count("gradle"))
        self.assertEqual(8, dev_builds.count("maven"))
        self.assertEqual(1, hold_builds.count("gradle"))
        self.assertEqual(6, hold_builds.count("maven"))
        self.assertTrue(split_doc["historical_disclosure"]["microsecend_17_in_sample_dev_contaminated"])

    def test_evaluate_e6_split_computes_generalization_gap_and_warns_when_large(self) -> None:
        split_doc = json.loads((SUITE_DIR / "blind_holdout_split.json").read_text(encoding="utf-8"))
        dev_ids = [x["id"] for x in split_doc["microsecend_17_stratified_split"]["dev_10"]]
        hold_ids = [x["id"] for x in split_doc["microsecend_17_stratified_split"]["holdout_7"]]

        def make_row(app_id: str, tp: int, fp: int, fn: int) -> dict:
            m = evaluate_blind_holdout.compute_prf(tp, fp, fn)
            return {
                "app_id": app_id,
                "canon_metrics": {"components": m, "connections": m},
                "collapsed_metrics": {"components": m, "connections": m},
            }

        synthetic_e6 = {
            "runs": {
                "strict": [make_row(a, 10, 0, 0) for a in dev_ids] + [make_row(a, 10, 0, 0) for a in hold_ids],
                "dfd-export": [make_row(a, 10, 0, 0) for a in dev_ids] + [make_row(a, 6, 4, 4) for a in hold_ids],
            }
        }
        ev = evaluate_blind_holdout.evaluate_e6_split(synthetic_e6, split_doc, retrospective=True)
        self.assertEqual("RETROSPECTIVE_SPLIT_OVER_IN_SAMPLE_CORPUS", ev["epistemic_label"])

        strict_gap = ev["profiles"]["strict"]["metrics"]["collapsed_metrics"]["connections"]["generalization_gap"]
        self.assertEqual(0.0, strict_gap["micro_f1_dev_minus_holdout"])
        self.assertFalse(strict_gap["exceeds_threshold"])

        dfd_gap = ev["profiles"]["dfd-export"]["metrics"]["collapsed_metrics"]["connections"]["generalization_gap"]
        self.assertAlmostEqual(0.4, dfd_gap["micro_f1_dev_minus_holdout"], places=4)
        self.assertTrue(dfd_gap["exceeds_threshold"])
        self.assertTrue(any("dfd-export" in w for w in ev["warnings"]))

    def test_online_boutique_scoring_penalizes_cartservice_triple_count_and_rewards_clean_detection(self) -> None:
        split_doc = json.loads((SUITE_DIR / "blind_holdout_split.json").read_text(encoding="utf-8"))
        baseline = evaluate_blind_holdout.score_online_boutique_components(
            build_components=[
                "PLAIN:.", "src/adservice", "src/cartservice", "src/cartservice/src", "src/cartservice/tests",
                "src/checkoutservice", "src/currencyservice", "src/frontend", "src/paymentservice",
                "src/productcatalogservice", "src/shippingservice",
            ],
            infra_components=[
                "kustomize/base/cartservice.yaml#cartservice",
                "kustomize/components/google-cloud-operations/otel-collector.yaml#opentelemetrycollector",
                "release/kubernetes-manifests.yaml#frontend-external",
                "release/kubernetes-manifests.yaml#recommendationservice",
            ],
            split_doc=split_doc,
        )
        self.assertEqual(["emailservice", "loadgenerator", "redis-cart"], baseline["missing_core_services"])
        self.assertLess(baseline["strict_leaf_metrics_vs_core_12"]["f1"], 0.76)

        improved = evaluate_blind_holdout.score_online_boutique_components(
            build_components=[
                "src/adservice", "src/cartservice", "src/checkoutservice", "src/currencyservice",
                "src/emailservice", "src/frontend", "src/loadgenerator", "src/paymentservice",
                "src/productcatalogservice", "src/recommendationservice", "src/shippingservice",
                "src/shoppingassistantservice",
            ],
            infra_components=["redis-cart", "opentelemetrycollector"],
            split_doc=split_doc,
        )
        self.assertEqual([], improved["missing_core_services"])
        self.assertEqual(1.0, improved["strict_leaf_metrics_vs_core_12"]["recall"])
        self.assertGreater(improved["strict_leaf_metrics_vs_core_12"]["f1"], 0.92)
        self.assertEqual(1.0, improved["unique_set_metrics_vs_core_plus_optional_14"]["f1"])


if __name__ == "__main__":
    unittest.main()
