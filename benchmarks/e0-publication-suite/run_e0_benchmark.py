#!/usr/bin/env python3
"""Standardized E0 Publication Benchmark Harness for Evo.

Supports:
  1. 3-way timing decomposition: cold, warm_no_change, warm_1file_edit
  2. Clean --no-incremental SHA-256 bit-identity verification + safe rollback
  3. Statistical aggregation: Median +/- IQR across N independent repeats
  4. Dual-profile Micro-F1 & Macro-F1 scoring (strict vs dfd-export)
  5. Track 1 S1 passive ASM spring-spike runner (--spike)
  6. Self-contained --smoke mode in a temporary workspace outside Git
"""

from __future__ import annotations

import argparse
import csv
import datetime as dt
import hashlib
import json
import math
import os
import shutil
import statistics
import subprocess
import sys
import tempfile
import time
import zipfile
from pathlib import Path
from typing import Any, Callable


SCRIPT_DIR = Path(__file__).resolve().parent
if str(SCRIPT_DIR) not in sys.path:
    sys.path.insert(0, str(SCRIPT_DIR))

import env_provenance  # noqa: E402

DEFAULT_MANIFEST_PATH = SCRIPT_DIR / "corpus_manifest.json"
DEFAULT_MUTATION_MARKER = "/* e0_benchmark_edit_probe */\n"
DEFAULT_REPEATS = 5
DEFAULT_WARMUP = 0


def validate_manifest(manifest: dict[str, Any]) -> None:
    if manifest.get("schema") != "e0.corpus-manifest:v1":
        raise ValueError(f"Unexpected manifest schema: {manifest.get('schema')}")
    suites = manifest.get("suites")
    if not isinstance(suites, dict):
        raise ValueError("Manifest missing 'suites' mapping")
    micro = suites.get("microsecend_17")
    if not isinstance(micro, list) or len(micro) != 17:
        raise ValueError(f"Expected exactly 17 microsecend_17 apps, got {len(micro) if isinstance(micro, list) else 'none'}")
    ids = [entry.get("id") for entry in micro]
    if len(set(ids)) != 17:
        raise ValueError("microsecend_17 contains duplicate entries")
    banned = set(manifest.get("banned_duplicates", []))
    for app_id in ids:
        if app_id in banned:
            raise ValueError(f"Banned duplicate repository found in manifest: {app_id}")
    if "ewolff/microservice-kafka" not in ids:
        raise ValueError("microsecend_17 must include verified ewolff/microservice-kafka")


def compute_median_iqr(values: list[float]) -> dict[str, float]:
    if not values:
        raise ValueError("Cannot compute Median +/- IQR on empty sequence")
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
    cv = (stdev / mean) if mean > 0.0 and not math.isnan(stdev) else 0.0
    return {
        "n": float(n),
        "median": round(median, 3),
        "iqr": round(max(0.0, q3 - q1), 3),
        "q1": round(q1, 3),
        "q3": round(q3, 3),
        "min": round(ordered[0], 3),
        "max": round(ordered[-1], 3),
        "mean": round(mean, 3),
        "stdev": round(stdev, 3),
        "cv": round(cv, 4),
    }


def extract_stage_timings(payload: dict[str, Any], stderr_text: str = "") -> dict[str, float]:
    """Extract stage timing breakdown (ms) from CLI JSON telemetry or stderr lines if exposed."""
    stages: dict[str, float] = {}
    data = payload.get("data") if isinstance(payload.get("data"), dict) else payload
    if isinstance(data, dict):
        for candidate_key in ("stageMillis", "stage_millis", "timingsMs", "timings_ms"):
            raw = data.get(candidate_key)
            if isinstance(raw, dict):
                for k, v in raw.items():
                    if isinstance(v, (int, float)):
                        stages[str(k)] = float(v)
        telemetry = data.get("telemetry")
        if isinstance(telemetry, dict):
            raw_sm = telemetry.get("stageMillis")
            if isinstance(raw_sm, dict):
                for k, v in raw_sm.items():
                    if isinstance(v, (int, float)):
                        stages[str(k)] = float(v)
    if stderr_text:
        for line in stderr_text.splitlines():
            # Parse lines like: [evo] Stages: ACQUISITION=120ms PLATFORM=45ms ANALYSIS=810ms GRAPH=220ms POLICY=12ms
            if "stage" in line.lower() or "ACQUISITION=" in line or "ANALYSIS=" in line:
                for token in line.replace(",", " ").split():
                    if "=" in token and token.lower().endswith("ms"):
                        k, val = token.split("=", 1)
                        try:
                            stages.setdefault(k.strip().upper(), float(val[:-2]))
                        except ValueError:
                            pass
    return stages


def aggregate_stage_timings(stage_samples: list[dict[str, float]]) -> dict[str, dict[str, float]]:
    keys: list[str] = []
    for canonical in ("ACQUISITION", "PLATFORM", "ANALYSIS", "GRAPH", "SAT", "POLICY", "EXPORT"):
        if any(canonical in s for s in stage_samples):
            keys.append(canonical)
    for sample in stage_samples:
        for k in sorted(sample.keys()):
            if k not in keys:
                keys.append(k)
    return {
        k: compute_median_iqr([float(s[k]) for s in stage_samples if k in s])
        for k in keys
        if any(k in s for s in stage_samples)
    }


def compute_prf1(predicted: set[Any], expected: set[Any]) -> dict[str, Any]:
    tp = len(predicted & expected)
    fp = len(predicted - expected)
    fn = len(expected - predicted)
    precision = tp / (tp + fp) if (tp + fp) > 0 else (1.0 if fn == 0 else 0.0)
    recall = tp / (tp + fn) if (tp + fn) > 0 else (1.0 if fp == 0 else 0.0)
    f1 = (2.0 * precision * recall / (precision + recall)) if (precision + recall) > 0.0 else 0.0
    return {
        "tp": tp,
        "fp": fp,
        "fn": fn,
        "precision": round(precision, 6),
        "recall": round(recall, 6),
        "f1": round(f1, 6),
    }


def compute_micro_macro_f1(app_pairs: list[tuple[set[Any], set[Any]]]) -> dict[str, Any]:
    if not app_pairs:
        empty = {"tp": 0, "fp": 0, "fn": 0, "precision": 0.0, "recall": 0.0, "f1": 0.0}
        return {"micro": empty, "macro": {"precision": 0.0, "recall": 0.0, "f1": 0.0, "apps": 0}}
    total_tp = total_fp = total_fn = 0
    ps: list[float] = []
    rs: list[float] = []
    f1s: list[float] = []
    for pred, exp in app_pairs:
        res = compute_prf1(pred, exp)
        total_tp += res["tp"]
        total_fp += res["fp"]
        total_fn += res["fn"]
        ps.append(res["precision"])
        rs.append(res["recall"])
        f1s.append(res["f1"])
    micro_p = total_tp / (total_tp + total_fp) if (total_tp + total_fp) > 0 else 1.0
    micro_r = total_tp / (total_tp + total_fn) if (total_tp + total_fn) > 0 else 1.0
    micro_f1 = (2.0 * micro_p * micro_r / (micro_p + micro_r)) if (micro_p + micro_r) > 0.0 else 0.0
    return {
        "micro": {
            "tp": total_tp,
            "fp": total_fp,
            "fn": total_fn,
            "precision": round(micro_p, 6),
            "recall": round(micro_r, 6),
            "f1": round(micro_f1, 6),
        },
        "macro": {
            "precision": round(sum(ps) / len(ps), 6),
            "recall": round(sum(rs) / len(rs), 6),
            "f1": round(sum(f1s) / len(f1s), 6),
            "apps": len(app_pairs),
        },
    }


def verify_sha256_parity(expected_digest: str, actual_digest: str, label: str) -> None:
    if not expected_digest or not actual_digest:
        raise ValueError(f"Empty SHA-256 digest during parity check [{label}]")
    if expected_digest != actual_digest:
        raise ValueError(
            f"SHA-256 parity mismatch [{label}]: expected={expected_digest} actual={actual_digest}"
        )


def _sha256_bytes(data: bytes) -> str:
    return "sha256:" + hashlib.sha256(data).hexdigest()


def apply_and_rollback_single_file_edit(
    target_file: Path,
    mutate_fn: Callable[[str], str],
    callback: Callable[[], Any],
) -> Any:
    original_bytes = target_file.read_bytes()
    original_sha = _sha256_bytes(original_bytes)
    original_text = original_bytes.decode("utf-8")
    mutated_text = mutate_fn(original_text)
    if mutated_text == original_text:
        raise ValueError(f"Single-file edit mutator made zero changes to {target_file}")
    try:
        target_file.write_bytes(mutated_text.encode("utf-8"))
        return callback()
    finally:
        target_file.write_bytes(original_bytes)
        restored_sha = _sha256_bytes(target_file.read_bytes())
        verify_sha256_parity(original_sha, restored_sha, f"rollback:{target_file.name}")


def mutate_java_for_edit(source_text: str) -> str:
    idx = source_text.rfind("}")
    if idx < 0:
        return source_text + "\n// e0 benchmark edit\n"
    return (
        source_text[:idx]
        + "\n    public int e0BenchmarkEditProbeMethod() { return 42; }\n"
        + source_text[idx:]
    )


def _extract_graph_digest(payload: dict[str, Any]) -> str:
    data = payload.get("data") if isinstance(payload.get("data"), dict) else payload
    graph = data.get("graph") if isinstance(data, dict) else None
    if isinstance(graph, str) and graph.startswith("sha256:"):
        return graph
    if isinstance(graph, dict):
        for key in ("value", "identity"):
            val = graph.get(key)
            if isinstance(val, str) and val.startswith("sha256:"):
                return val
    return _sha256_bytes(json.dumps(payload, sort_keys=True).encode("utf-8"))


def _run_evo_command(cmd: list[str], cwd: Path, timeout_s: int = 300) -> tuple[int, float, dict[str, Any], str]:
    t0 = time.perf_counter()
    proc = subprocess.run(
        cmd,
        cwd=str(cwd),
        capture_output=True,
        text=True,
        encoding="utf-8",
        errors="replace",
        timeout=timeout_s,
        check=False,
    )
    elapsed_ms = (time.perf_counter() - t0) * 1000.0
    parsed: dict[str, Any] = {}
    out_strip = proc.stdout.strip()
    if out_strip:
        try:
            parsed = json.loads(out_strip)
        except json.JSONDecodeError:
            parsed = {"raw_stdout": out_strip}
    return proc.returncode, elapsed_ms, parsed, proc.stderr


def _run_single_app_benchmark(
    app_id: str,
    repo_dir: Path,
    edit_file_rel: Path,
    jar_path: Path,
    m2_cache: Path,
    work_root: Path,
    profile: str,
    repeats: int,
    heap: str,
    offline: bool,
    fast: bool,
    warmup: int = DEFAULT_WARMUP,
) -> dict[str, Any]:
    cold_times: list[float] = []
    warm_nc_times: list[float] = []
    warm_edit_times: list[float] = []
    cold_edited_times: list[float] = []
    cold_stages: list[dict[str, float]] = []
    warm_nc_stages: list[dict[str, float]] = []
    warm_edit_stages: list[dict[str, float]] = []
    cold_edited_stages: list[dict[str, float]] = []
    cold_warm_parity_all = True
    edit_clean_parity_all = True
    last_cold_graph = ""
    last_edit_graph = ""

    base_flags = [
        "java",
        f"-Xmx{heap}",
        "-jar",
        str(jar_path),
        "scan",
        str(repo_dir),
        "--maven-cache",
        str(m2_cache),
        "--build",
        "off",
        "--projection-profile",
        profile,
        "--telemetry",
        "--json",
    ]
    if offline:
        base_flags.append("--offline")
    if fast:
        base_flags.append("--fast")

    edit_target = repo_dir / edit_file_rel
    if not edit_target.is_file():
        java_files = sorted(repo_dir.rglob("*.java"))
        if not java_files:
            raise FileNotFoundError(f"No .java file found for 1-file edit in {repo_dir}")
        edit_target = java_files[0]

    total_iters = max(0, warmup) + repeats
    for idx in range(total_iters):
        is_warmup = idx < max(0, warmup)
        rep_ws = work_root / f"rep_{profile}_{idx}"
        if rep_ws.exists():
            shutil.rmtree(rep_ws)
        rep_ws.mkdir(parents=True, exist_ok=True)

        # 1. Cold Run
        code_cold, ms_cold, json_cold, err_cold = _run_evo_command(
            base_flags + ["-w", str(rep_ws)], cwd=work_root
        )
        if code_cold not in (0, 3):
            raise RuntimeError(f"[{app_id}] Cold scan failed exit={code_cold}: {err_cold}")
        cold_graph = _extract_graph_digest(json_cold)
        cold_receipt_sha = _sha256_bytes(json.dumps(json_cold, sort_keys=True).encode("utf-8"))
        last_cold_graph = cold_graph

        # 2. Warm NO_CHANGE Run
        code_nc, ms_nc, json_nc, err_nc = _run_evo_command(
            base_flags + ["-w", str(rep_ws)], cwd=work_root
        )
        if code_nc not in (0, 3):
            raise RuntimeError(f"[{app_id}] Warm NO_CHANGE scan failed exit={code_nc}: {err_nc}")
        nc_graph = _extract_graph_digest(json_nc)
        nc_receipt_sha = _sha256_bytes(json.dumps(json_nc, sort_keys=True).encode("utf-8"))
        verify_sha256_parity(cold_graph, nc_graph, f"{app_id}:{profile}:cold_vs_warm_nc_graph:rep{idx}")
        verify_sha256_parity(cold_receipt_sha, nc_receipt_sha, f"{app_id}:{profile}:cold_vs_warm_nc_receipt:rep{idx}")

        # 3. Warm 1-File Edit vs Clean --no-incremental on Edited Repo (+ Safe Rollback)
        def _after_edit() -> tuple[float, str, str, dict[str, float], float, str, str, dict[str, float]]:
            c_edit, m_edit, j_edit, e_edit = _run_evo_command(
                base_flags + ["-w", str(rep_ws)], cwd=work_root
            )
            if c_edit not in (0, 3):
                raise RuntimeError(f"[{app_id}] Warm 1-file edit failed exit={c_edit}: {e_edit}")
            edit_g = _extract_graph_digest(j_edit)
            edit_r = _sha256_bytes(json.dumps(j_edit, sort_keys=True).encode("utf-8"))
            edit_st = extract_stage_timings(j_edit, e_edit)

            clean_ws = work_root / f"clean_{profile}_{idx}"
            if clean_ws.exists():
                shutil.rmtree(clean_ws)
            clean_ws.mkdir(parents=True, exist_ok=True)
            c_clean, m_clean, j_clean, e_clean = _run_evo_command(
                base_flags + ["--no-incremental", "-w", str(clean_ws)], cwd=work_root
            )
            if c_clean not in (0, 3):
                raise RuntimeError(f"[{app_id}] Clean --no-incremental on edited failed exit={c_clean}: {e_clean}")
            clean_g = _extract_graph_digest(j_clean)
            clean_r = _sha256_bytes(json.dumps(j_clean, sort_keys=True).encode("utf-8"))
            clean_st = extract_stage_timings(j_clean, e_clean)
            return m_edit, edit_g, edit_r, edit_st, m_clean, clean_g, clean_r, clean_st

        m_edit, edit_g, edit_r, edit_st, m_clean, clean_g, clean_r, clean_st = apply_and_rollback_single_file_edit(
            edit_target, mutate_java_for_edit, _after_edit
        )
        verify_sha256_parity(clean_g, edit_g, f"{app_id}:{profile}:warm_edit_vs_clean_graph:rep{idx}")
        verify_sha256_parity(clean_r, edit_r, f"{app_id}:{profile}:warm_edit_vs_clean_receipt:rep{idx}")
        last_edit_graph = edit_g

        if not is_warmup:
            cold_times.append(ms_cold)
            warm_nc_times.append(ms_nc)
            warm_edit_times.append(m_edit)
            cold_edited_times.append(m_clean)
            cold_stages.append(extract_stage_timings(json_cold, err_cold))
            warm_nc_stages.append(extract_stage_timings(json_nc, err_nc))
            warm_edit_stages.append(edit_st)
            cold_edited_stages.append(clean_st)

    return {
        "app_id": app_id,
        "profile": profile,
        "repeats": repeats,
        "warmup": max(0, warmup),
        "cold_ms": compute_median_iqr(cold_times),
        "warm_no_change_ms": compute_median_iqr(warm_nc_times),
        "warm_1file_edit_ms": compute_median_iqr(warm_edit_times),
        "cold_on_edited_ms": compute_median_iqr(cold_edited_times),
        "stage_decomposition_ms": {
            "cold": aggregate_stage_timings(cold_stages),
            "warm_no_change": aggregate_stage_timings(warm_nc_stages),
            "warm_1file_edit": aggregate_stage_timings(warm_edit_stages),
            "cold_on_edited": aggregate_stage_timings(cold_edited_stages),
        },
        "cold_vs_warm_nc_sha256_match": cold_warm_parity_all,
        "warm_edit_vs_clean_sha256_match": edit_clean_parity_all,
        "cold_graph_id": last_cold_graph,
        "edited_graph_id": last_edit_graph,
    }


def _build_smoke_fixture(root: Path) -> tuple[Path, Path, Path, Path]:
    repo = root / "smoke-microservice-repo"
    m2 = root / "smoke-m2"
    ws = root / "smoke-work"
    m2.mkdir(parents=True, exist_ok=True)
    ws.mkdir(parents=True, exist_ok=True)

    (repo / "order-service" / "src" / "main" / "java" / "com" / "acme" / "order").mkdir(parents=True, exist_ok=True)
    (repo / "order-service" / "src" / "main" / "resources").mkdir(parents=True, exist_ok=True)
    (repo / "catalog-service" / "src" / "main" / "java" / "com" / "acme" / "catalog").mkdir(parents=True, exist_ok=True)

    (repo / "pom.xml").write_text(
        """<project xmlns="http://maven.apache.org/POM/4.0.0">
  <modelVersion>4.0.0</modelVersion>
  <groupId>com.acme</groupId>
  <artifactId>smoke-parent</artifactId>
  <version>1.0.0</version>
  <packaging>pom</packaging>
  <modules>
    <module>order-service</module>
    <module>catalog-service</module>
  </modules>
</project>
""",
        encoding="utf-8",
    )
    for mod in ("order-service", "catalog-service"):
        (repo / mod / "pom.xml").write_text(
            f"""<project xmlns="http://maven.apache.org/POM/4.0.0">
  <modelVersion>4.0.0</modelVersion>
  <parent>
    <groupId>com.acme</groupId>
    <artifactId>smoke-parent</artifactId>
    <version>1.0.0</version>
  </parent>
  <artifactId>{mod}</artifactId>
  <properties>
    <maven.compiler.release>21</maven.compiler.release>
    <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
  </properties>
</project>
""",
            encoding="utf-8",
        )

    order_ctrl = repo / "order-service" / "src" / "main" / "java" / "com" / "acme" / "order" / "OrderController.java"
    order_ctrl.write_text(
        """package com.acme.order;

public class OrderController {
    private final OrderService service = new OrderService();
    public String getOrder() { return service.fetchCatalog(); }
}
""",
        encoding="utf-8",
    )
    (repo / "order-service" / "src" / "main" / "java" / "com" / "acme" / "order" / "OrderService.java").write_text(
        """package com.acme.order;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OrderService {
    @Bean
    public OrderClient customOrderClient() {
        return new OrderClient();
    }

    public String fetchCatalog() {
        return "ok";
    }

    public static class OrderClient {}
}
""",
        encoding="utf-8",
    )
    (repo / "catalog-service" / "src" / "main" / "java" / "com" / "acme" / "catalog" / "CatalogService.java").write_text(
        """package com.acme.catalog;

public class CatalogService {
    public int skuCount() { return 7; }
}
""",
        encoding="utf-8",
    )
    (repo / "order-service" / "src" / "main" / "resources" / "application.yml").write_text(
        "app:\n  smoke:\n    enabled: true\nspring:\n  profiles:\n    active: prod\n",
        encoding="utf-8",
    )
    (repo / "docker-compose.yml").write_text(
        """services:
  order-service:
    build: ./order-service
    depends_on:
      - catalog-service
  catalog-service:
    build: ./catalog-service
""",
        encoding="utf-8",
    )

    starter_jar = root / "smoke-starter.jar"
    # Build minimal valid JAR with AutoConfiguration.imports (and optional scanned entry if any)
    with zipfile.ZipFile(starter_jar, "w", compression=zipfile.ZIP_DEFLATED) as zf:
        zf.writestr(
            "META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports",
            "# E0 smoke starter\n",
        )
    return repo, order_ctrl, m2, ws


def render_markdown_report(summary: dict[str, Any]) -> str:
    lines = [
        f"# Evo E0 Benchmark Report (`{summary.get('timestamp_utc', '')}`)",
        "",
        f"- **JAR:** `{summary.get('jar', '')}` | **Heap:** `{summary.get('heap', '')}` "
        f"| **Repeats:** `n={summary.get('repeats', '')}` (warmup=`{summary.get('warmup', 0)}`)",
        "",
    ]
    env = summary.get("environment")
    if isinstance(env, dict):
        lines.extend(env_provenance.format_provenance_markdown(env))
    lines.extend(
        [
            "| App | Profile | Cold Median ± IQR (CV) ms | Warm NO_CHANGE (CV) ms | Warm 1-File Edit (CV) ms | Cold==Warm SHA-256 | Edit==Clean SHA-256 |",
            "| :--- | :--- | ---: | ---: | ---: | :---: | :---: |",
        ]
    )
    for row in summary.get("runs", []):
        c = row["cold_ms"]
        w = row["warm_no_change_ms"]
        e = row["warm_1file_edit_ms"]
        c_cv = f" (CV {c.get('cv', 0.0):.2f})" if "cv" in c else ""
        w_cv = f" (CV {w.get('cv', 0.0):.2f})" if "cv" in w else ""
        e_cv = f" (CV {e.get('cv', 0.0):.2f})" if "cv" in e else ""
        lines.append(
            f"| `{row['app_id']}` | `{row['profile']}` | "
            f"{c['median']:.1f} ± {c['iqr']:.1f}{c_cv} | "
            f"{w['median']:.1f} ± {w['iqr']:.1f}{w_cv} | "
            f"{e['median']:.1f} ± {e['iqr']:.1f}{e_cv} | "
            f"{'PASS' if row['cold_vs_warm_nc_sha256_match'] else 'FAIL'} | "
            f"{'PASS' if row['warm_edit_vs_clean_sha256_match'] else 'FAIL'} |"
        )
    if "spike_runs" in summary and summary["spike_runs"]:
        lines.extend(
            [
                "",
                "## Track 1 (`S1`) ASM Bytecode + COI Go/No-Go Spike",
                "",
                "| App | JARs | Configs | @Beans | Conds | G1 Interactions | G2 Findings | COI Components | Max Comp Vars | COI Time (ms) | Gate |",
                "| :--- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | :--- |",
            ]
        )
        for s in summary["spike_runs"]:
            coi = s.get("g3CoiMetrics", {})
            lines.append(
                f"| `{s.get('app_id', '')}` | {s.get('scannedJars', 0)} | {s.get('totalConfigurations', 0)} | "
                f"{s.get('totalBeans', 0)} | {s.get('totalConditionMarkers', 0)} | "
                f"{s.get('g1UserStarterInteractions', 0)} | {s.get('g2ConditionalFindings', 0)} | "
                f"{coi.get('componentCount', 0)} | {coi.get('maxComponentVars', 0)} | "
                f"{coi.get('elapsedMs', 0)} | `{s.get('goNoGoRecommendation', '')}` |"
            )
    return "\n".join(lines) + "\n"


def render_latex_table(summary: dict[str, Any]) -> str:
    rows = summary.get("runs", [])
    env = summary.get("environment") if isinstance(summary.get("environment"), dict) else {}
    env_cls = str(env.get("environment_class", "unspecified")).replace("_", r"\_")
    caveat = str(env.get("publication_caveat", ""))
    out = []
    if caveat:
        out.append(f"% HONEST ENVIRONMENT PROVENANCE: {caveat}")
    out.extend(
        [
            r"\begin{table}[t]",
            r"\centering",
            rf"\caption{{Evo 3-Way Runtime Decomposition (Median $\pm$ IQR ms, $n={summary.get('repeats', DEFAULT_REPEATS)}$, environment: \texttt{{{env_cls}}}) and SHA-256 Bit-Identity Parity}}",
            r"\begin{tabular}{llrrrr}",
            r"\toprule",
            r"Repository & Profile & Cold (ms) & Warm No-Chg (ms) & Warm 1-Edit (ms) & SHA-256 Parity \\",
            r"\midrule",
        ]
    )
    for row in rows:
        c = row["cold_ms"]
        w = row["warm_no_change_ms"]
        e = row["warm_1file_edit_ms"]
        parity = r"\checkmark" if (row["cold_vs_warm_nc_sha256_match"] and row["warm_edit_vs_clean_sha256_match"]) else "FAIL"
        app = str(row["app_id"]).replace("_", r"\_")
        prof = str(row["profile"]).replace("_", r"\_")
        out.append(
            f"{app} & {prof} & {c['median']:.1f} $\\pm$ {c['iqr']:.1f} & "
            f"{w['median']:.1f} $\\pm$ {w['iqr']:.1f} & {e['median']:.1f} $\\pm$ {e['iqr']:.1f} & {parity} \\\\"
        )
    out.extend([r"\bottomrule", r"\end{tabular}", r"\end{table}"])
    return "\n".join(out) + "\n"


def run_smoke_suite(jar_path: Path, repeats: int, heap: str, warmup: int = DEFAULT_WARMUP) -> dict[str, Any]:
    with tempfile.TemporaryDirectory(prefix="evo_e0_smoke_") as tmp:
        root = Path(tmp)
        repo, edit_file, m2, ws = _build_smoke_fixture(root)
        starter_jar = root / "smoke-starter.jar"
        runs: list[dict[str, Any]] = []
        for profile in ("strict", "dfd-export"):
            runs.append(
                _run_single_app_benchmark(
                    app_id="smoke-microservice-repo",
                    repo_dir=repo,
                    edit_file_rel=edit_file.relative_to(repo),
                    jar_path=jar_path,
                    m2_cache=m2,
                    work_root=ws / profile,
                    profile=profile,
                    repeats=repeats,
                    heap=heap,
                    offline=True,
                    fast=True,
                    warmup=warmup,
                )
            )

        # Also exercise evo spring-spike deterministically inside the same smoke temp sandbox
        spike_ws = ws / "spike"
        spike_ws.mkdir(parents=True, exist_ok=True)
        spike_cmd = [
            "java",
            f"-Xmx{heap}",
            "-jar",
            str(jar_path),
            "spring-spike",
            str(repo / "order-service"),
            "-w",
            str(spike_ws),
            "--maven-cache",
            str(m2),
            "--offline",
            "--extra-jar",
            str(starter_jar),
            "--json",
        ]
        c_s1, _ms1, j_s1, e_s1 = _run_evo_command(spike_cmd, cwd=root)
        c_s2, _ms2, j_s2, e_s2 = _run_evo_command(spike_cmd, cwd=root)
        if c_s1 != 0 or c_s2 != 0:
            raise RuntimeError(f"smoke spring-spike failed: c1={c_s1} err1={e_s1} c2={c_s2} err2={e_s2}")
        d_s1 = j_s1.get("data", {})
        d_s2 = j_s2.get("data", {})
        verify_sha256_parity(str(d_s1.get("identity", "")), str(d_s2.get("identity", "")), "smoke:spring-spike")
        d_s1["app_id"] = "smoke-order-service"

        summary = {
            "mode": "smoke",
            "timestamp_utc": dt.datetime.now(dt.timezone.utc).isoformat(),
            "jar": str(jar_path),
            "heap": heap,
            "repeats": repeats,
            "warmup": max(0, warmup),
            "environment": env_provenance.collect_environment_provenance(execution_target="host"),
            "runs": runs,
            "spike_runs": [d_s1],
        }
        return summary


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Evo E0 Standardized Publication Benchmark Runner")
    parser.add_argument("--manifest", type=Path, default=DEFAULT_MANIFEST_PATH)
    parser.add_argument("--jar", type=Path, required=True, help="Path to shaded evo.jar")
    parser.add_argument("--smoke", action="store_true", help="Run self-contained end-to-end smoke suite in temp dir")
    parser.add_argument("--spike", action="store_true", help="Run S1 evo spring-spike across s1_spring_spike_5")
    parser.add_argument("--corpus-dir", type=Path, help="External directory containing cloned benchmark repos")
    parser.add_argument("--suite", default="microsecend_17", choices=["microsecend_17", "flagship_polyglot_java", "all"])
    parser.add_argument("--profiles", nargs="+", default=["strict", "dfd-export"])
    parser.add_argument("--repeats", type=int, default=DEFAULT_REPEATS)
    parser.add_argument("--warmup", type=int, default=DEFAULT_WARMUP, help="Optional unmeasured warmup repetitions")
    parser.add_argument("--heap", default="4g")
    parser.add_argument("--maven-cache", type=Path, default=Path.home() / ".m2" / "repository")
    parser.add_argument("--offline", action="store_true")
    parser.add_argument("--fast", action="store_true")
    parser.add_argument("--out-dir", type=Path, help="Optional output directory outside Git for JSON/CSV/MD/TeX")
    args = parser.parse_args(argv)

    manifest = json.loads(args.manifest.read_text(encoding="utf-8"))
    validate_manifest(manifest)

    jar_path = args.jar.resolve()
    if not jar_path.is_file():
        raise FileNotFoundError(f"evo.jar not found: {jar_path}")

    if args.smoke:
        summary = run_smoke_suite(jar_path, max(1, args.repeats), args.heap, max(0, args.warmup))
        print(render_markdown_report(summary))
        print(json.dumps(summary, indent=2))
        return 0

    if not args.corpus_dir or not args.corpus_dir.is_dir():
        raise FileNotFoundError(f"--corpus-dir must point to an existing external directory: {args.corpus_dir}")

    env_meta = env_provenance.collect_environment_provenance(execution_target="host")
    if args.spike:
        spike_runs: list[dict[str, Any]] = []
        with tempfile.TemporaryDirectory(prefix="evo_s1_spike_") as tmp:
            ws_root = Path(tmp)
            for item in manifest["suites"]["s1_spring_spike_5"]:
                repo_dir = args.corpus_dir / item["dir"]
                if not repo_dir.is_dir():
                    continue
                ws = ws_root / item["dir"]
                ws.mkdir(parents=True, exist_ok=True)
                cmd = [
                    "java", f"-Xmx{args.heap}", "-jar", str(jar_path),
                    "spring-spike", str(repo_dir), "-w", str(ws),
                    "--maven-cache", str(args.maven_cache), "--json",
                ]
                if args.offline:
                    cmd.append("--offline")
                code, _ms, payload, err = _run_evo_command(cmd, cwd=ws_root, timeout_s=600)
                if code != 0:
                    raise RuntimeError(f"spring-spike failed for {item['id']}: {err}")
                data = payload.get("data", {})
                data["app_id"] = item["id"]
                spike_runs.append(data)
        summary = {
            "mode": "s1_spike",
            "timestamp_utc": dt.datetime.now(dt.timezone.utc).isoformat(),
            "jar": str(jar_path),
            "heap": args.heap,
            "repeats": 1,
            "warmup": 0,
            "environment": env_meta,
            "runs": [],
            "spike_runs": spike_runs,
        }
    else:
        entries = (
            manifest["suites"]["microsecend_17"] + manifest["suites"]["flagship_polyglot_java"]
            if args.suite == "all"
            else manifest["suites"][args.suite]
        )
        runs = []
        with tempfile.TemporaryDirectory(prefix="evo_e0_work_") as tmp:
            ws_root = Path(tmp)
            for item in entries:
                repo_dir = args.corpus_dir / item["dir"]
                if not repo_dir.is_dir():
                    raise FileNotFoundError(f"Missing corpus checkout for {item['id']} at {repo_dir}")
                java_candidates = sorted(repo_dir.rglob("*.java"))
                if not java_candidates:
                    raise FileNotFoundError(f"No .java files inside {repo_dir}")
                rel_java = java_candidates[0].relative_to(repo_dir)
                for prof in args.profiles:
                    runs.append(
                        _run_single_app_benchmark(
                            app_id=item["id"],
                            repo_dir=repo_dir,
                            edit_file_rel=rel_java,
                            jar_path=jar_path,
                            m2_cache=args.maven_cache,
                            work_root=ws_root / item["dir"],
                            profile=prof,
                            repeats=max(1, args.repeats),
                            heap=args.heap,
                            offline=args.offline,
                            fast=args.fast,
                            warmup=max(0, args.warmup),
                        )
                    )
        summary = {
            "mode": args.suite,
            "timestamp_utc": dt.datetime.now(dt.timezone.utc).isoformat(),
            "jar": str(jar_path),
            "heap": args.heap,
            "repeats": max(1, args.repeats),
            "warmup": max(0, args.warmup),
            "environment": env_meta,
            "runs": runs,
            "spike_runs": [],
        }

    md = render_markdown_report(summary)
    print(md)
    if args.out_dir:
        args.out_dir.mkdir(parents=True, exist_ok=True)
        (args.out_dir / "e0_summary.json").write_text(json.dumps(summary, indent=2), encoding="utf-8")
        (args.out_dir / "e0_report.md").write_text(md, encoding="utf-8")
        (args.out_dir / "e0_table.tex").write_text(render_latex_table(summary), encoding="utf-8")
        with (args.out_dir / "e0_timing.csv").open("w", newline="", encoding="utf-8") as fh:
            writer = csv.writer(fh)
            writer.writerow([
                "app_id", "profile", "repeats", "warmup", "environment_class", "is_wsl2",
                "cold_median_ms", "cold_iqr_ms", "cold_cv",
                "warm_nc_median_ms", "warm_nc_iqr_ms", "warm_nc_cv",
                "warm_edit_median_ms", "warm_edit_iqr_ms", "warm_edit_cv",
                "cold_warm_parity", "edit_clean_parity",
            ])
            for r in summary["runs"]:
                writer.writerow([
                    r["app_id"], r["profile"], r["repeats"], r.get("warmup", 0),
                    env_meta["environment_class"], env_meta["is_wsl2"],
                    r["cold_ms"]["median"], r["cold_ms"]["iqr"], r["cold_ms"].get("cv", 0.0),
                    r["warm_no_change_ms"]["median"], r["warm_no_change_ms"]["iqr"], r["warm_no_change_ms"].get("cv", 0.0),
                    r["warm_1file_edit_ms"]["median"], r["warm_1file_edit_ms"]["iqr"], r["warm_1file_edit_ms"].get("cv", 0.0),
                    r["cold_vs_warm_nc_sha256_match"], r["warm_edit_vs_clean_sha256_match"],
                ])
    return 0


if __name__ == "__main__":
    sys.exit(main())
