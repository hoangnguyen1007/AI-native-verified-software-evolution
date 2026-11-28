#!/usr/bin/env python3
"""CORE A* / Q1 Statistical Evaluation Engine for Evo Publication Campaigns (SuperPlan v2 Section 4.1).

Implements pure-Python, zero-dependency, deterministic statistical checks required by
SuperPlan v2 Section 4.1:
  - Median +/- IQR & distribution summaries
  - Paired Wilcoxon Signed-Rank Test (exact DP for n <= 20 without ties; tie-corrected
    continuity-corrected normal approximation otherwise)
  - Holm-Bonferroni step-down multiple-testing correction
  - Cliff's delta effect size with SSAR Table 4 magnitude bands (0.147 / 0.33 / 0.474)
  - Normalized gain RDP (SemRef Eqs. 4-5)
  - Spearman rank correlation rho (PRAXIS protocol)
  - Judge stability (StdDev <= 2.4% & Range, RepoProbe protocol)
"""

from __future__ import annotations

import math
import statistics
from typing import Any, Sequence


CLIFFS_NEGLIGIBLE_MAX = 0.147
CLIFFS_SMALL_MAX = 0.33
CLIFFS_MEDIUM_MAX = 0.474


def summarize_distribution(values: Sequence[float]) -> dict[str, float]:
    """Computes Median, IQR, Q1, Q3, Min, Max, Mean, and sample StDev."""
    if not values:
        raise ValueError("Cannot summarize an empty sequence")
    ordered = sorted(float(v) for v in values)
    n = len(ordered)
    median = float(statistics.median(ordered))
    if n == 1:
        q1 = q3 = median
        stdev = 0.0
    elif n % 2 == 0:
        q1 = float(statistics.median(ordered[: n // 2]))
        q3 = float(statistics.median(ordered[n // 2 :]))
        stdev = float(statistics.stdev(ordered))
    else:
        q1 = float(statistics.median(ordered[: n // 2]))
        q3 = float(statistics.median(ordered[n // 2 + 1 :]))
        stdev = float(statistics.stdev(ordered))
    mean = float(statistics.fmean(ordered))
    return {
        "n": float(n),
        "median": round(median, 6),
        "iqr": round(max(0.0, q3 - q1), 6),
        "q1": round(q1, 6),
        "q3": round(q3, 6),
        "min": round(ordered[0], 6),
        "max": round(ordered[-1], 6),
        "mean": round(mean, 6),
        "stdev": round(stdev, 6),
    }


def _average_ranks(values: Sequence[float]) -> tuple[list[float], list[int]]:
    """Assigns 1-based average midranks to values and returns (ranks, tie_group_sizes)."""
    indexed = sorted(enumerate(values), key=lambda item: item[1])
    ranks = [0.0] * len(values)
    tie_groups: list[int] = []
    i = 0
    while i < len(indexed):
        j = i + 1
        while j < len(indexed) and indexed[j][1] == indexed[i][1]:
            j += 1
        count = j - i
        if count > 1:
            tie_groups.append(count)
        avg_rank = (i + 1 + j) / 2.0
        for k in range(i, j):
            ranks[indexed[k][0]] = avg_rank
        i = j
    return ranks, tie_groups


def _exact_wilcoxon_two_sided_p(n: int, w_stat_int: int) -> float:
    """Computes exact two-sided p-value for Wilcoxon W <= w_stat_int over 2^n equiprobable sign vectors."""
    max_sum = n * (n + 1) // 2
    dp = [0] * (max_sum + 1)
    dp[0] = 1
    for rank in range(1, n + 1):
        for s in range(max_sum, rank - 1, -1):
            dp[s] += dp[s - rank]
    tail = sum(dp[: min(w_stat_int, max_sum) + 1])
    p_val = (2.0 * tail) / float(1 << n)
    return min(1.0, max(0.0, p_val))


def wilcoxon_signed_rank(
    x: Sequence[float],
    y: Sequence[float],
    alpha: float = 0.05,
) -> dict[str, Any]:
    """Paired two-sided Wilcoxon Signed-Rank Test with exact DP (n<=20, no ties) or tie-corrected normal approx."""
    if len(x) != len(y):
        raise ValueError(f"Paired samples must have equal length: {len(x)} != {len(y)}")
    if not x:
        raise ValueError("Cannot run Wilcoxon Signed-Rank Test on empty samples")

    diffs = [round(float(a) - float(b), 12) for a, b in zip(x, y) if round(float(a) - float(b), 12) != 0.0]
    n = len(diffs)
    if n == 0:
        return {
            "n_total": len(x),
            "n_nonzero": 0,
            "w_plus": 0.0,
            "w_minus": 0.0,
            "statistic": 0.0,
            "p_value": 1.0,
            "alpha": alpha,
            "significant": False,
            "method": "all_zero_differences",
        }

    abs_diffs = [round(abs(d), 12) for d in diffs]
    ranks, tie_groups = _average_ranks(abs_diffs)
    w_plus = sum(r for d, r in zip(diffs, ranks) if d > 0.0)
    w_minus = sum(r for d, r in zip(diffs, ranks) if d < 0.0)
    w_stat = min(w_plus, w_minus)

    if n <= 20 and not tie_groups:
        p_val = _exact_wilcoxon_two_sided_p(n, int(round(w_stat)))
        method = "exact_dp"
    else:
        mean_w = n * (n + 1) / 4.0
        tie_term = sum(t * t * t - t for t in tie_groups) / 48.0
        var_w = (n * (n + 1) * (2 * n + 1)) / 24.0 - tie_term
        if var_w <= 0.0:
            p_val = 1.0
        else:
            sd_w = math.sqrt(var_w)
            diff_mean = abs(w_stat - mean_w)
            z = max(0.0, diff_mean - 0.5) / sd_w
            p_val = min(1.0, max(0.0, math.erfc(z / math.sqrt(2.0))))
        method = "normal_approx_tie_corrected"

    return {
        "n_total": len(x),
        "n_nonzero": n,
        "w_plus": round(w_plus, 6),
        "w_minus": round(w_minus, 6),
        "statistic": round(w_stat, 6),
        "p_value": round(p_val, 8),
        "alpha": alpha,
        "significant": p_val < alpha,
        "method": method,
    }


def holm_bonferroni(p_values: Sequence[float], alpha: float = 0.05) -> dict[str, Any]:
    """Applies step-down Holm-Bonferroni family-wise error adjustment."""
    m = len(p_values)
    if m == 0:
        return {"alpha": alpha, "adjusted_p_values": [], "significant": []}
    indexed = sorted(enumerate(float(p) for p in p_values), key=lambda item: item[1])
    adjusted = [1.0] * m
    running_max = 0.0
    for rank_idx, (orig_idx, raw_p) in enumerate(indexed):
        factor = m - rank_idx
        adj = min(1.0, max(running_max, raw_p * factor))
        running_max = adj
        adjusted[orig_idx] = round(adj, 8)
    return {
        "alpha": alpha,
        "adjusted_p_values": adjusted,
        "significant": [p < alpha for p in adjusted],
    }


def cliffs_delta(x: Sequence[float], y: Sequence[float]) -> dict[str, Any]:
    """Computes Cliff's delta dominance and SSAR Table 4 effect-size band."""
    if not x or not y:
        raise ValueError("Cannot compute Cliff's delta on empty sequences")
    gt = 0
    lt = 0
    for a in x:
        fa = float(a)
        for b in y:
            fb = float(b)
            if fa > fb:
                gt += 1
            elif fa < fb:
                lt += 1
    delta = (gt - lt) / float(len(x) * len(y))
    abs_d = abs(delta)
    if abs_d < CLIFFS_NEGLIGIBLE_MAX:
        magnitude = "negligible"
    elif abs_d < CLIFFS_SMALL_MAX:
        magnitude = "small"
    elif abs_d < CLIFFS_MEDIUM_MAX:
        magnitude = "medium"
    else:
        magnitude = "large"
    return {
        "delta": round(delta, 6),
        "abs_delta": round(abs_d, 6),
        "magnitude": magnitude,
    }


def normalized_gain_rdp(score: float, baseline: float) -> float:
    """Computes SemRef (ICSE '26 Eqs. 4-5) ceiling-normalized relative improvement RDP."""
    s = float(score)
    b = float(baseline)
    if s >= b:
        if b >= 1.0:
            return 0.0
        return round((s - b) / (1.0 - b), 6)
    if b <= 0.0:
        return 0.0
    return round((s - b) / b, 6)


def spearman_rho(x: Sequence[float], y: Sequence[float]) -> float:
    """Computes tie-aware Spearman rank correlation coefficient rho."""
    if len(x) != len(y):
        raise ValueError(f"Sequences must have equal length: {len(x)} != {len(y)}")
    n = len(x)
    if n < 2:
        return 0.0
    rx, _ = _average_ranks([float(v) for v in x])
    ry, _ = _average_ranks([float(v) for v in y])
    mean_x = sum(rx) / n
    mean_y = sum(ry) / n
    num = sum((a - mean_x) * (b - mean_y) for a, b in zip(rx, ry))
    den_x = math.sqrt(sum((a - mean_x) ** 2 for a in rx))
    den_y = math.sqrt(sum((b - mean_y) ** 2 for b in ry))
    if den_x == 0.0 or den_y == 0.0:
        return 0.0
    return round(num / (den_x * den_y), 6)


def judge_stability(scores: Sequence[float], max_stdev: float = 2.4) -> dict[str, Any]:
    """Computes RepoProbe judge stability (n>=3 runs, StdDev <= 2.4% & Range)."""
    if not scores:
        raise ValueError("Cannot compute judge stability on empty scores")
    vals = [float(s) for s in scores]
    mean = statistics.fmean(vals)
    stdev = statistics.stdev(vals) if len(vals) >= 2 else 0.0
    rng = max(vals) - min(vals)
    return {
        "n": len(vals),
        "mean": round(mean, 6),
        "stdev": round(stdev, 6),
        "range": round(rng, 6),
        "max_stdev_threshold": max_stdev,
        "within_threshold": stdev <= max_stdev,
    }


def paired_comparison_summary(
    metric_name: str,
    treatment: Sequence[float],
    baseline: Sequence[float],
    alpha: float = 0.05,
    include_rdp: bool = False,
) -> dict[str, Any]:
    """Computes full CORE A* paired statistical summary for one metric."""
    summary: dict[str, Any] = {
        "metric": metric_name,
        "treatment": summarize_distribution(treatment),
        "baseline": summarize_distribution(baseline),
        "wilcoxon": wilcoxon_signed_rank(treatment, baseline, alpha=alpha),
        "cliffs_delta": cliffs_delta(treatment, baseline),
    }
    if include_rdp:
        rdps = [normalized_gain_rdp(t, b) for t, b in zip(treatment, baseline)]
        summary["rdp"] = summarize_distribution(rdps)
    return summary
