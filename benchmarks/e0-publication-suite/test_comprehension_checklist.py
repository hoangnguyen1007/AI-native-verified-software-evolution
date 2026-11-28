import json
import sys
import tempfile
import unittest
from pathlib import Path

SUITE_DIR = Path(__file__).resolve().parent
if str(SUITE_DIR) not in sys.path:
    sys.path.insert(0, str(SUITE_DIR))

import run_comprehension_checklist_benchmark as bench  # noqa: E402


def _make_sample_question(
    items: tuple[bench.ChecklistItem, ...],
    config_only: bool = False,
    config_key: str = "",
) -> bench.ComprehensionQuestion:
    return bench.ComprehensionQuestion(
        question_id="TEST-Q1",
        tier="tier2_repoprobe_polyglot",
        repo="sample-repo",
        language="java-spring",
        category="config_resolution" if config_only else "data_flow",
        question="How does caching get enabled?",
        checklist_items=items,
        config_only_expected=config_only,
        expected_config_key=config_key,
        expected_citations=("src/main/resources/application.yml:4",),
        split="holdout",
    )


class TestComprehensionChecklistBenchmark(unittest.TestCase):
    def test_checklist_nine_plus_one_invariant_accepts_valid_and_rejects_violations(self) -> None:
        valid_items = (
            bench.ChecklistItem("k1", "Identifies config key", 4, (0, 2, 4)),
            bench.ChecklistItem("k2", "Explains runtime effect", 3, (0, 1, 3)),
            bench.ChecklistItem("k3", "Provides exact span", 2, (0, 1, 2)),
        )
        q = _make_sample_question(valid_items, config_only=True, config_key="app.cache.enabled")
        bench.validate_checklist(q)

        # k = 1 summing to 9 is valid
        bench.validate_checklist(
            _make_sample_question((bench.ChecklistItem("k1", "Single item", 9, (0, 3, 6, 9)),))
        )

        # k = 4 summing to 9 is valid (3+2+2+2)
        bench.validate_checklist(
            _make_sample_question(
                (
                    bench.ChecklistItem("k1", "Item 1", 3, (0, 1, 3)),
                    bench.ChecklistItem("k2", "Item 2", 2, (0, 1, 2)),
                    bench.ChecklistItem("k3", "Item 3", 2, (0, 1, 2)),
                    bench.ChecklistItem("k4", "Item 4", 2, (0, 1, 2)),
                )
            )
        )

        # k = 0 rejected
        with self.assertRaises(ValueError):
            bench.validate_checklist(_make_sample_question(()))

        # k = 5 rejected
        with self.assertRaises(ValueError):
            bench.validate_checklist(
                _make_sample_question(
                    tuple(bench.ChecklistItem(f"k{i}", f"Item {i}", 1, (0, 1)) for i in range(1, 6))
                )
            )

        # Sum != 9 rejected (sum = 8)
        with self.assertRaises(ValueError):
            bench.validate_checklist(
                _make_sample_question(
                    (
                        bench.ChecklistItem("k1", "Item 1", 5, (0, 5)),
                        bench.ChecklistItem("k2", "Item 2", 3, (0, 3)),
                    )
                )
            )

        # allowed_scores missing 0 or max_points rejected
        with self.assertRaises(ValueError):
            bench.validate_checklist(
                _make_sample_question((bench.ChecklistItem("k1", "Bad rubric", 9, (1, 9)),))
            )

        # Duplicate item IDs rejected
        with self.assertRaises(ValueError):
            bench.validate_checklist(
                _make_sample_question(
                    (
                        bench.ChecklistItem("dup", "A", 5, (0, 5)),
                        bench.ChecklistItem("dup", "B", 4, (0, 4)),
                    )
                )
            )

    def test_rationale_first_prompt_and_strict_validator_enforcement(self) -> None:
        q = _make_sample_question(
            (
                bench.ChecklistItem("k1", "Identifies app.cache.enabled", 4, (0, 2, 4)),
                bench.ChecklistItem("k2", "Recommends CONFIG_ONLY_RESOLUTION", 3, (0, 1, 3)),
                bench.ChecklistItem("k3", "Cites application.yml:4", 2, (0, 1, 2)),
            ),
            config_only=True,
            config_key="app.cache.enabled",
        )
        prompt = bench.build_rationale_first_judge_prompt(
            q, "CONFIG_ONLY_RESOLUTION: Set app.cache.enabled=true at application.yml:4."
        )
        self.assertIn("RATIONALE-FIRST", prompt)
        self.assertIn("allowed_scores=[0, 2, 4]", prompt)
        self.assertIn("EDIT_BIAS", prompt)

        valid_raw = json.dumps(
            {
                "items": [
                    {"item_id": "k1", "rationale": "Explicitly names app.cache.enabled.", "score": 4},
                    {"item_id": "k2", "rationale": "Recommends config switch without code edits.", "score": 3},
                    {"item_id": "k3", "rationale": "Cites application.yml line 4 accurately.", "score": 2},
                ],
                "clarity_rationale": "Concise and clearly structured response.",
                "clarity_score": 1,
                "error_tags": [],
                "total_score": 10,
            }
        )
        res = bench.validate_judge_response(q, valid_raw)
        self.assertEqual(10, res["total_score"])
        self.assertEqual(100.0, res["pct_score"])

        # Reject score-before-rationale ordering in raw JSON
        bad_order_raw = (
            '{"items":['
            '{"item_id":"k1","score":4,"rationale":"Score appeared before rationale."},'
            '{"item_id":"k2","rationale":"Valid order here.","score":3},'
            '{"item_id":"k3","rationale":"Valid order here.","score":2}'
            '],"clarity_rationale":"Clear enough answer.","clarity_score":1,"total_score":10}'
        )
        with self.assertRaises(ValueError):
            bench.validate_judge_response(q, bad_order_raw)

        # Reject clarity_score before clarity_rationale
        bad_clarity_order = (
            '{"items":['
            '{"item_id":"k1","rationale":"Valid rationale 1.","score":4},'
            '{"item_id":"k2","rationale":"Valid rationale 2.","score":3},'
            '{"item_id":"k3","rationale":"Valid rationale 3.","score":2}'
            '],"clarity_score":1,"clarity_rationale":"Clarity score came first.","total_score":10}'
        )
        with self.assertRaises(ValueError):
            bench.validate_judge_response(q, bad_clarity_order)

        # Reject out-of-rubric discrete score (k1 only allows 0, 2, 4 -> reject 3)
        out_of_rubric = json.dumps(
            {
                "items": [
                    {"item_id": "k1", "rationale": "Partially right answer here.", "score": 3},
                    {"item_id": "k2", "rationale": "Valid rationale for item two.", "score": 3},
                    {"item_id": "k3", "rationale": "Valid rationale for item three.", "score": 2},
                ],
                "clarity_rationale": "Concise explanation overall.",
                "clarity_score": 1,
                "total_score": 9,
            }
        )
        with self.assertRaises(ValueError):
            bench.validate_judge_response(q, out_of_rubric)

        # Reject empty/short rationale
        short_rationale = json.dumps(
            {
                "items": [
                    {"item_id": "k1", "rationale": "ok", "score": 4},
                    {"item_id": "k2", "rationale": "Valid rationale for item two.", "score": 3},
                    {"item_id": "k3", "rationale": "Valid rationale for item three.", "score": 2},
                ],
                "clarity_rationale": "Concise explanation overall.",
                "clarity_score": 1,
                "total_score": 10,
            }
        )
        with self.assertRaises(ValueError):
            bench.validate_judge_response(q, short_rationale)

    def test_cross_family_multi_judge_aggregation_and_stddev_target(self) -> None:
        # Low variance across 3 model families (88%, 90%, 92% -> stdev = 2.0% <= 2.4%)
        evals = [
            bench.JudgeEvaluation("gpt-judge", "openai", "Q1", "evo_mcp", {"pct_score": 88.0, "error_tags": []}),
            bench.JudgeEvaluation("gemini-judge", "google", "Q1", "evo_mcp", {"pct_score": 90.0, "error_tags": []}),
            bench.JudgeEvaluation("claude-judge", "anthropic", "Q1", "evo_mcp", {"pct_score": 92.0, "error_tags": []}),
        ]
        agg = bench.aggregate_cross_family_judges(evals, strict_cross_family=True)
        self.assertEqual(90.0, agg["overall_pct"])
        self.assertEqual(2.0, agg["judge_stddev_pct"])
        self.assertTrue(agg["meets_stddev_target"])
        self.assertEqual(["anthropic", "google", "openai"], agg["model_families"])

        # High variance (> 2.4%) sets meets_stddev_target=False
        noisy = [
            bench.JudgeEvaluation("gpt-judge", "openai", "Q1", "raw_grep", {"pct_score": 60.0, "error_tags": ["EDIT_BIAS"]}),
            bench.JudgeEvaluation("gemini-judge", "google", "Q1", "raw_grep", {"pct_score": 70.0, "error_tags": []}),
            bench.JudgeEvaluation("claude-judge", "anthropic", "Q1", "raw_grep", {"pct_score": 80.0, "error_tags": []}),
        ]
        noisy_agg = bench.aggregate_cross_family_judges(noisy, strict_cross_family=True)
        self.assertEqual(10.0, noisy_agg["judge_stddev_pct"])
        self.assertFalse(noisy_agg["meets_stddev_target"])
        self.assertGreater(noisy_agg["error_taxonomy_rates_pct"]["EDIT_BIAS"], 0.0)

        # Single-family panel rejected under strict_cross_family=True
        single_family = [
            bench.JudgeEvaluation("gpt-a", "openai", "Q1", "evo_mcp", {"pct_score": 90.0, "error_tags": []}),
            bench.JudgeEvaluation("gpt-b", "openai", "Q1", "evo_mcp", {"pct_score": 90.0, "error_tags": []}),
            bench.JudgeEvaluation("gpt-c", "openai", "Q1", "evo_mcp", {"pct_score": 90.0, "error_tags": []}),
        ]
        with self.assertRaises(ValueError):
            bench.aggregate_cross_family_judges(single_family, strict_cross_family=True)

    def test_edit_bias_detector_and_skg_citation_verifier(self) -> None:
        # Invasive Java patch on config-only question -> Edit Bias detected
        patch_ans = (
            "Modify the class and add a new method:\n"
            "```java\n"
            "public class CacheOverride {\n"
            "    public void enable() {\n"
            "        init();\n"
            "        start();\n"
            "    }\n"
            "}\n"
            "```"
        )
        self.assertTrue(bench.detect_edit_bias(patch_ans, config_only_expected=True)["edit_bias"])

        # Clean CONFIG_ONLY_RESOLUTION answer -> NO Edit Bias
        cfg_ans = (
            "CONFIG_ONLY_RESOLUTION: Set `app.cache.enabled: true` in src/main/resources/application.yml:4 [E1].\n"
            "```yaml\napp:\n  cache:\n    enabled: true\n```"
        )
        self.assertFalse(
            bench.detect_edit_bias(cfg_ans, config_only_expected=True, pre_edit_gate_decision="CONFIG_ONLY_RESOLUTION")[
                "edit_bias"
            ]
        )

        # SKG path:line verification catches hallucinated path and out-of-bounds line while ignoring URLs
        manifest = {
            "src/main/resources/application.yml": 10,
            "src/main/java/com/acme/OrderService.java": 40,
        }
        mixed_text = (
            "Verified at src/main/resources/application.yml:4 and OrderService.java:10-25. "
            "Broken refs: src/main/resources/application.yml:99 and src/fake/Ghost.java:5. "
            "URL ignored: https://example.test:8443/api."
        )
        cit = bench.verify_answer_citations(mixed_text, skg_file_lines=manifest)
        self.assertEqual(4, cit["total_citations"])
        self.assertEqual(2, cit["valid_citations"])
        self.assertEqual(2, cit["invalid_citations"])
        self.assertEqual(50.0, cit["validity_pct"])
        reasons = {u["reason"] for u in cit["unverified"]}
        self.assertEqual({"HALLUCINATED_PATH", "LINE_OUT_OF_BOUNDS"}, reasons)

        # Local read-only repo_root verification works without skg_manifest
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            p = root / "src" / "App.java"
            p.parent.mkdir(parents=True, exist_ok=True)
            p.write_text("line1\nline2\nline3\n", encoding="utf-8")
            local_cit = bench.verify_answer_citations("See src/App.java:2 and src/App.java:9", repo_root=root)
            self.assertEqual(1, local_cit["valid_citations"])
            self.assertEqual(1, local_cit["invalid_citations"])

    def test_tier1_45_questions_and_tier2_paired_dry_run_cli(self) -> None:
        t1 = bench.build_tier1_chinthareddy_45_suite()
        self.assertEqual(45, len(t1))
        repos = {}
        for q in t1:
            bench.validate_checklist(q)
            repos[q.repo] = repos.get(q.repo, 0) + 1
        self.assertEqual({"shopizer-6a4a0a65": 15, "thingsboard": 15, "openmrs-core": 15}, repos)

        t2 = bench.build_tier2_repoprobe_polyglot_suite()
        self.assertEqual(20, len(t2))
        langs = {q.language for q in t2}
        self.assertEqual({"java-spring", "python", "typescript", "go", "rust"}, langs)
        self.assertTrue(any(q.config_only_expected for q in t2))
        self.assertTrue(any(q.split == "holdout" for q in t2))

        with tempfile.TemporaryDirectory() as tmp:
            out_dir = Path(tmp) / "reports"
            q_export = Path(tmp) / "questions.jsonl"
            rc = bench.main(["--dry-run", "--export-questions", str(q_export), "--out-dir", str(out_dir)])
            self.assertEqual(0, rc)
            self.assertEqual(65, len(q_export.read_text(encoding="utf-8").splitlines()))

            json_path = out_dir / "comprehension_checklist_report.json"
            md_path = out_dir / "comprehension_checklist_report.md"
            self.assertTrue(json_path.is_file())
            self.assertTrue(md_path.is_file())

            report = json.loads(json_path.read_text(encoding="utf-8"))
            self.assertEqual("DRY_RUN_SYNTHETIC", report["execution_mode"])
            t1_rep = report["tier1_chinthareddy_45"]
            self.assertEqual(45, t1_rep["question_count"])
            self.assertGreaterEqual(t1_rep["passing_questions_ge_80pct"], 43)
            self.assertEqual(100.0, t1_rep["citation_validity_pct"])
            self.assertTrue(t1_rep["meets_stddev_target"])

            t2_rep = report["tier2_repoprobe_polyglot"]
            self.assertGreater(t2_rep["paired_delta"]["overall_delta_pp"], 0.0)
            self.assertGreaterEqual(t2_rep["paired_delta"]["edit_bias_relative_reduction_pct"], 50.0)
            self.assertTrue(t2_rep["paired_delta"]["evo_meets_stddev_target"])
            self.assertEqual(5, len(t2_rep["by_language"]))
            self.assertGreater(t2_rep["holdout_only"]["question_count"], 0)


if __name__ == "__main__":
    unittest.main()
