#!/usr/bin/env python3
"""Unit tests for WP5.3 Phase 5 real-artifact statistical pairing & ledger builder."""

from __future__ import annotations

import csv
import sys
import tempfile
import unittest
from pathlib import Path

SUITE_DIR = Path(__file__).resolve().parent
if str(SUITE_DIR) not in sys.path:
    sys.path.insert(0, str(SUITE_DIR))

import run_phase5_statistical_ledger as p5_ledger  # noqa: E402


class Phase5StatisticalLedgerTest(unittest.TestCase):

    def test_analyze_e6_paired_and_render_closed_denominator_ledger(self):
        with tempfile.TemporaryDirectory() as tmp:
            csv_path = Path(tmp) / "e6_per_app.csv"
            out_md = Path(tmp) / "ledger.md"
            with csv_path.open("w", encoding="utf-8", newline="") as fh:
                writer = csv.writer(fh)
                writer.writerow([
                    "profile", "app_id", "status", "completion",
                    "cold_elapsed_ms", "warm_elapsed_ms",
                    "cold_vs_warm1_sha256", "steady_state_sha256",
                    "gap_count", "warm1_gap_count",
                    "comp_tp", "comp_fp", "comp_fn", "comp_p", "comp_r", "comp_f1",
                    "conn_tp", "conn_fp", "conn_fn", "conn_p", "conn_r", "conn_f1",
                ])
                for i in range(17):
                    app = f"org/app-{i:02d}"
                    writer.writerow([
                        "strict", app, "PARTIAL", "PARTIAL",
                        10000 + i * 500, 2000 + i * 100, "True", "True",
                        100, 100, 8, 1, 1, 0.88, 0.88, 0.85, 5, 3, 5, 0.625, 0.5, 0.50 + i * 0.01,
                    ])
                    writer.writerow([
                        "dfd-export", app, "PARTIAL", "PARTIAL",
                        9900 + i * 500, 1980 + i * 100, "True", "True",
                        100, 100, 9, 1, 0, 0.90, 1.0, 0.94, 9, 1, 0, 0.90, 1.0, 0.85 + i * 0.005,
                    ])

            stats = p5_ledger.analyze_e6_paired(csv_path)
            self.assertEqual(17, stats["paired_apps"])
            self.assertEqual(17, stats["sha256_parity_apps"])
            by_metric = {c["metric"]: c for c in stats["comparisons"]}
            self.assertTrue(by_metric["conn_f1"]["holm_bonferroni"]["significant"])
            self.assertEqual("large", by_metric["conn_f1"]["cliffs_delta"]["magnitude"])

            ledger = p5_ledger.build_closed_denominator_ledger()
            self.assertEqual(7, len(ledger["experiments"]))
            self.assertEqual(10, len(ledger["ablations"]))
            self.assertEqual(4, len(ledger["milestones"]))

            md = p5_ledger.render_markdown_report(stats, ledger)
            out_md.write_text(md, encoding="utf-8")
            self.assertIn("`17/17` paired apps", md)
            self.assertIn("`EXP-7`", md)
            self.assertIn("`AB-10`", md)
            self.assertIn("`M1`", md)


if __name__ == "__main__":
    unittest.main()
