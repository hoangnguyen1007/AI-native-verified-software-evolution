#!/usr/bin/env python3
"""Stratified Dev-vs-Holdout Generalization & Online Boutique Service F1 Evaluator.

Reads:
  1. An E6 microSecEnD e6_summary.json and benchmarks/e0-publication-suite/blind_holdout_split.json
     -> computes Dev (10 apps) vs Holdout (7 apps) vs Full (17 apps) Micro & Macro P/R/F1
     -> computes Generalization Gap (Dev F1 - Holdout F1) for STRICT and DFD_EXPORT
     -> flags warnings if generalization gap exceeds max_allowed_generalization_gap_f1 (0.05)
        or if the evaluation is a retrospective split over in-sample contaminated runs.
  2. Optionally scores Online Boutique component lists (BUILD_COMPONENT + INFRA_COMPONENT)
     against the 12 core K8s services (strict leaf & deduplicated deployable components).
"""

from __future__ import annotations

import argparse
import json
import re
from pathlib import Path
from typing import Any


def compute_prf(tp: int, fp: int, fn: int) -> dict[str, Any]:
    p = tp / (tp + fp) if (tp + fp) > 0 else (1.0 if fn == 0 else 0.0)
    r = tp / (tp + fn) if (tp + fn) > 0 else (1.0 if fp == 0 else 0.0)
    f1 = (2.0 * p * r / (p + r)) if (p + r) > 0 else 0.0
    return {
        "tp": tp,
        "fp": fp,
        "fn": fn,
        "precision": round(p, 4),
        "recall": round(r, 4),
        "f1": round(f1, 4),
    }


def aggregate_rows(rows: list[dict[str, Any]], metric_family: str, target: str) -> dict[str, Any]:
    if not rows:
        empty = {"tp": 0, "fp": 0, "fn": 0, "precision": 0.0, "recall": 0.0, "f1": 0.0}
        return {"micro": empty, "macro": {"precision": 0.0, "recall": 0.0, "f1": 0.0, "apps": 0}}
    tp = fp = fn = 0
    ps: list[float] = []
    rs: list[float] = []
    f1s: list[float] = []
    for row in rows:
        m = row[metric_family][target]
        tp += int(m["tp"])
        fp += int(m["fp"])
        fn += int(m["fn"])
        ps.append(float(m["precision"]))
        rs.append(float(m["recall"]))
        f1s.append(float(m["f1"]))
    return {
        "micro": compute_prf(tp, fp, fn),
        "macro": {
            "precision": round(sum(ps) / len(ps), 4),
            "recall": round(sum(rs) / len(rs), 4),
            "f1": round(sum(f1s) / len(f1s), 4),
            "apps": len(rows),
        },
    }


def validate_split_definition(split_doc: dict[str, Any], manifest_doc: dict[str, Any] | None = None) -> None:
    split = split_doc["microsecend_17_stratified_split"]
    dev = split["dev_10"]
    holdout = split["holdout_7"]
    dev_ids = {x["id"] for x in dev}
    holdout_ids = {x["id"] for x in holdout}
    overlap = dev_ids & holdout_ids
    if overlap:
        raise ValueError(f"Dev and holdout overlap: {sorted(overlap)}")
    if len(dev_ids) != 10 or len(holdout_ids) != 7:
        raise ValueError(f"Expected 10 dev / 7 holdout, got {len(dev_ids)} / {len(holdout_ids)}")
    if any(x.get("in_5repo_baseline") for x in holdout):
        raise ValueError("Original 5-repo seed apps must never appear in holdout_7")
    if manifest_doc is not None:
        manifest_ids = {x["id"] for x in manifest_doc["suites"]["microsecend_17"]}
        if (dev_ids | holdout_ids) != manifest_ids:
            raise ValueError("Split union does not equal corpus_manifest microsecend_17")


def evaluate_e6_split(
    e6_summary: dict[str, Any],
    split_doc: dict[str, Any],
    retrospective: bool = True,
) -> dict[str, Any]:
    validate_split_definition(split_doc)
    split = split_doc["microsecend_17_stratified_split"]
    dev_ids = {x["id"] for x in split["dev_10"]}
    holdout_ids = {x["id"] for x in split["holdout_7"]}
    max_gap = float(split_doc["governance_invariants"]["max_allowed_generalization_gap_f1"])

    report: dict[str, Any] = {
        "schema": "e0.blind-holdout-evaluation:v1",
        "epistemic_label": (
            "RETROSPECTIVE_SPLIT_OVER_IN_SAMPLE_CORPUS"
            if retrospective
            else "FROZEN_COMMIT_BLIND_EVALUATION"
        ),
        "max_allowed_generalization_gap_f1": max_gap,
        "profiles": {},
        "warnings": [],
    }
    if retrospective:
        report["warnings"].append(
            "Retrospective split over microSecEnD (prior Batch 10/11 tuning saw all 17 apps); "
            "report generalization gap transparently and pair with unseen OOD / zero-touch repos."
        )

    for profile_name, rows in e6_summary.get("runs", {}).items():
        dev_rows = [r for r in rows if r.get("app_id") in dev_ids]
        holdout_rows = [r for r in rows if r.get("app_id") in holdout_ids]
        profile_out: dict[str, Any] = {
            "dev_count": len(dev_rows),
            "holdout_count": len(holdout_rows),
            "all_count": len(rows),
            "metrics": {},
        }
        for family_key in ("canon_metrics", "collapsed_metrics"):
            if not all(family_key in r for r in rows):
                continue
            fam_out: dict[str, Any] = {}
            for target in ("components", "connections"):
                dev_m = aggregate_rows(dev_rows, family_key, target)
                hold_m = aggregate_rows(holdout_rows, family_key, target)
                all_m = aggregate_rows(rows, family_key, target)
                micro_gap = round(dev_m["micro"]["f1"] - hold_m["micro"]["f1"], 4)
                macro_gap = round(dev_m["macro"]["f1"] - hold_m["macro"]["f1"], 4)
                exceeds = abs(micro_gap) > max_gap or abs(macro_gap) > max_gap
                if exceeds:
                    report["warnings"].append(
                        f"[{profile_name}/{family_key}/{target}] generalization gap exceeds {max_gap:.2f}: "
                        f"micro_gap={micro_gap:+.4f}, macro_gap={macro_gap:+.4f}"
                    )
                fam_out[target] = {
                    "dev_10": dev_m,
                    "holdout_7": hold_m,
                    "all_17": all_m,
                    "generalization_gap": {
                        "micro_f1_dev_minus_holdout": micro_gap,
                        "macro_f1_dev_minus_holdout": macro_gap,
                        "exceeds_threshold": exceeds,
                    },
                }
            profile_out["metrics"][family_key] = fam_out
        report["profiles"][profile_name] = profile_out
    return report


def _norm_component_name(raw: str) -> str:
    s = raw.strip().replace("\\", "/")
    if "#" in s:
        s = s.rsplit("#", 1)[-1]
    if ":" in s and not s.startswith("/"):
        prefix, rest = s.split(":", 1)
        if prefix.isupper() and rest in (".", ".-."):
            return ""
        s = rest
    if "/" in s:
        s = s.rstrip("/").rsplit("/", 1)[-1]
    return re.sub(r"[_\s.]+", "-", s.lower()).strip("-")


def score_online_boutique_components(
    build_components: list[str],
    infra_components: list[str],
    split_doc: dict[str, Any],
) -> dict[str, Any]:
    ob = next(x for x in split_doc["external_ood_polyglot_suite"] if x["id"] == "GoogleCloudPlatform/microservices-demo")
    gt_core = set(ob["ground_truth_core_services"])
    optional_at_sha = set(ob.get("optional_repo_components_at_sha", []))

    # Exclude root aggregators and parent dirs that have non-test build subdirs
    clean_builds = [b.replace("\\", "/").strip("/") for b in build_components if b and not re.match(r"^[A-Z0-9_-]+:\.(?:->\.)?$", b.strip())]
    agg_dirs = {
        d for d in clean_builds
        if any(other != d and other.startswith(d + "/") for other in clean_builds)
    }
    leaf_builds = [d for d in clean_builds if d not in agg_dirs]

    strict_leaf_list = [n for n in (_norm_component_name(x) for x in leaf_builds + infra_components) if n]
    # Keep duplicates in strict count to penalize multi-counting (e.g. cartservice counted 3x)
    seen_tp: set[str] = set()
    strict_tp = 0
    strict_fp = 0
    for item in strict_leaf_list:
        if item in gt_core and item not in seen_tp:
            seen_tp.add(item)
            strict_tp += 1
        else:
            strict_fp += 1
    strict_fn = len(gt_core - seen_tp)

    # Set-deduplicated score (unique canonical names across deployable components)
    unique_pred = set(strict_leaf_list)
    dedup_tp = len(unique_pred & gt_core)
    dedup_fp = len(unique_pred - gt_core)
    dedup_fn = len(gt_core - unique_pred)

    # Core + optional-at-SHA score (accounting for real optional services present at c7cb6d5)
    gt_with_optional = gt_core | optional_at_sha
    opt_tp = len(unique_pred & gt_with_optional)
    opt_fp = len(unique_pred - gt_with_optional)
    opt_fn = len(gt_with_optional - unique_pred)

    return {
        "repository": ob["id"],
        "pinned_sha": ob["pinned_sha"],
        "leaf_build_components": sorted(leaf_builds),
        "infra_components": sorted(infra_components),
        "predicted_strict_multiset": sorted(strict_leaf_list),
        "predicted_unique": sorted(unique_pred),
        "missing_core_services": sorted(gt_core - unique_pred),
        "extra_vs_core_12": sorted(unique_pred - gt_core),
        "strict_leaf_metrics_vs_core_12": compute_prf(strict_tp, strict_fp, strict_fn),
        "unique_set_metrics_vs_core_12": compute_prf(dedup_tp, dedup_fp, dedup_fn),
        "unique_set_metrics_vs_core_plus_optional_14": compute_prf(opt_tp, opt_fp, opt_fn),
    }


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Evaluate Dev-vs-Holdout generalization and Online Boutique Service F1.")
    parser.add_argument("--split-file", type=Path, default=Path("benchmarks/e0-publication-suite/blind_holdout_split.json"))
    parser.add_argument("--e6-summary", type=Path, default=None)
    parser.add_argument("--e8-summary", type=Path, default=None)
    parser.add_argument("--frozen-blind", action="store_true", help="Mark run as frozen-commit blind evaluation.")
    parser.add_argument("--json", dest="json_out", type=Path, default=None)
    args = parser.parse_args(argv)

    split_doc = json.loads(args.split_file.read_text(encoding="utf-8"))
    out: dict[str, Any] = {}

    if args.e6_summary:
        e6_doc = json.loads(args.e6_summary.read_text(encoding="utf-8"))
        out["microsecend_split_evaluation"] = evaluate_e6_split(e6_doc, split_doc, retrospective=not args.frozen_blind)
        for p_name, p_data in out["microsecend_split_evaluation"]["profiles"].items():
            cc = p_data["metrics"].get("collapsed_metrics", {})
            if cc:
                comp = cc["components"]
                conn = cc["connections"]
                print(
                    f"[holdout-eval:{p_name}] COMP dev={comp['dev_10']['micro']['f1']:.4f} "
                    f"holdout={comp['holdout_7']['micro']['f1']:.4f} "
                    f"(gap={comp['generalization_gap']['micro_f1_dev_minus_holdout']:+.4f}) | "
                    f"CONN dev={conn['dev_10']['micro']['f1']:.4f} "
                    f"holdout={conn['holdout_7']['micro']['f1']:.4f} "
                    f"(gap={conn['generalization_gap']['micro_f1_dev_minus_holdout']:+.4f})"
                )

    if args.e8_summary:
        e8_doc = json.loads(args.e8_summary.read_text(encoding="utf-8"))
        for r in e8_doc.get("part1_flagship_repositories", []):
            if r.get("id") == "GoogleCloudPlatform/microservices-demo":
                stats = r.get("strict_graph_stats", {})
                ob_score = score_online_boutique_components(
                    stats.get("build_components", []),
                    stats.get("infra_components", []),
                    split_doc,
                )
                out["online_boutique_service_score"] = ob_score
                sl = ob_score["strict_leaf_metrics_vs_core_12"]
                un = ob_score["unique_set_metrics_vs_core_12"]
                print(
                    f"[online-boutique] strict_leaf_F1={sl['f1']:.4f} (TP={sl['tp']},FP={sl['fp']},FN={sl['fn']}) | "
                    f"unique_core12_F1={un['f1']:.4f} (TP={un['tp']},FP={un['fp']},FN={un['fn']}) | "
                    f"missing={ob_score['missing_core_services']}"
                )

    if args.json_out:
        args.json_out.parent.mkdir(parents=True, exist_ok=True)
        args.json_out.write_text(json.dumps(out, indent=2), encoding="utf-8")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
