#!/usr/bin/env python3
"""Unit tests for WP5.2 CORE A* / Q1 Statistical Evaluation Engine."""

from __future__ import annotations

import sys
import unittest
from pathlib import Path

SUITE_DIR = Path(__file__).resolve().parent
if str(SUITE_DIR) not in sys.path:
    sys.path.insert(0, str(SUITE_DIR))

import stats_publication_evaluator as stats_eval  # noqa: E402


class StatsPublicationEvaluatorTest(unittest.TestCase):

    def test_wilcoxon_exact_dp_and_ties(self):
        # n=6 strictly positive untied differences (0.1, 0.2, 0.4, 0.8, 1.5, 2.0) -> W- = 0, exact two-sided p = 2 / 2^6 = 0.03125
        res = stats_eval.wilcoxon_signed_rank(
            [10.1, 10.2, 10.4, 10.8, 11.5, 12.0],
            [10.0, 10.0, 10.0, 10.0, 10.0, 10.0],
            alpha=0.05,
        )
        self.assertEqual("exact_dp", res["method"])
        self.assertEqual(6, res["n_nonzero"])
        self.assertEqual(0.0, res["statistic"])
        self.assertAlmostEqual(0.03125, res["p_value"], places=6)
        self.assertTrue(res["significant"])

        # Identical distributions -> n_nonzero=0, p=1.0
        same = stats_eval.wilcoxon_signed_rank([1.0, 2.0, 3.0], [1.0, 2.0, 3.0])
        self.assertEqual(0, same["n_nonzero"])
        self.assertEqual(1.0, same["p_value"])
        self.assertFalse(same["significant"])

        # Ties + zeros use tie-corrected normal approximation cleanly
        tied = stats_eval.wilcoxon_signed_rank(
            [1.0, 2.0, 3.0, 4.0, 5.0, 6.0, 7.0, 8.0],
            [0.5, 1.5, 2.5, 3.5, 4.5, 5.5, 7.0, 7.5],
        )
        self.assertEqual("normal_approx_tie_corrected", tied["method"])
        self.assertEqual(7, tied["n_nonzero"])
        self.assertTrue(tied["significant"])

    def test_holm_bonferroni_step_down_monotonicity(self):
        hb = stats_eval.holm_bonferroni([0.04, 0.008, 0.03, 0.25], alpha=0.05)
        adj = hb["adjusted_p_values"]
        # Sorted raw: 0.008 (x4=0.032), 0.03 (x3=0.09), 0.04 (x2=0.08 -> monotonic max=0.09), 0.25 (x1=0.25)
        self.assertAlmostEqual(0.032, adj[1], places=6)
        self.assertAlmostEqual(0.09, adj[2], places=6)
        self.assertAlmostEqual(0.09, adj[0], places=6)
        self.assertAlmostEqual(0.25, adj[3], places=6)
        self.assertEqual([False, True, False, False], hb["significant"])

    def test_cliffs_delta_ssar_bands(self):
        self.assertEqual("negligible", stats_eval.cliffs_delta([1, 2, 3, 4], [1, 2, 3, 4])["magnitude"])
        self.assertEqual("large", stats_eval.cliffs_delta([10, 11, 12, 13], [1, 2, 3, 4])["magnitude"])
        self.assertEqual(1.0, stats_eval.cliffs_delta([10, 11], [1, 2])["delta"])
        self.assertEqual(-1.0, stats_eval.cliffs_delta([1, 2], [10, 11])["delta"])

    def test_normalized_gain_rdp_spearman_and_judge_stability(self):
        self.assertAlmostEqual(0.5, stats_eval.normalized_gain_rdp(0.9, 0.8), places=6)
        self.assertAlmostEqual(-0.25, stats_eval.normalized_gain_rdp(0.6, 0.8), places=6)
        self.assertEqual(0.0, stats_eval.normalized_gain_rdp(1.0, 1.0))

        self.assertAlmostEqual(1.0, stats_eval.spearman_rho([10, 20, 30, 40], [1, 2, 3, 4]), places=6)
        self.assertAlmostEqual(-1.0, stats_eval.spearman_rho([10, 20, 30, 40], [4, 3, 2, 1]), places=6)
        self.assertEqual(0.0, stats_eval.spearman_rho([5, 5, 5], [1, 2, 3]))

        stable = stats_eval.judge_stability([84.0, 85.2, 84.6], max_stdev=2.4)
        self.assertTrue(stable["within_threshold"])
        self.assertEqual(3, stable["n"])


if __name__ == "__main__":
    unittest.main()
