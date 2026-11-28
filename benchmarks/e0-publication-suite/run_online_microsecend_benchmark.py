#!/usr/bin/env python3
"""E6 Publication Benchmark: 17-App microSecEnD Linux Docker Online Web App Runner.

Runs all 17 repositories in corpus_manifest.json (suites.microsecend_17) against two live
Linux Docker instances of Evo Online Web App concurrently:
  1. STRICT projection profile (default --strict-url http://127.0.0.1:18081)
  2. DFD_EXPORT projection profile (default --dfd-url http://127.0.0.1:18082)

For each repository and epistemic tier, executes:
  - Remote HTTPS repository connection (POST /api/v1/analysis/connections)
  - Cold FULL scan (--build=off passive analysis)
  - Warm NO_CHANGE re-scan (force=true), plus Warm2 steady-state replay check if Cold!=Warm1
    (e.g., when online Maven dependency resolution populated cache during Cold/Warm1)
  - HTTP graph query extraction (GET /api/v1/graphs/{id}, POST /queries/entities, /edges, /evidence)
  - Ground-truth Component & Inter-Service Connection F1 scoring against
    M3SOulu/EMSE2025SAR-Replication/tool_comparison/groundtruth_textual/*.txt

Reports all 17/17 apps (closed denominator, zero subset cherry-picking) with both
Micro-Average and Macro-Average Precision / Recall / F1, plus verbatim baseline comparison.
"""

from __future__ import annotations

import argparse
from concurrent.futures import ThreadPoolExecutor
import csv
import datetime as dt
import json
import re
import statistics
import time
import urllib.error
import urllib.request
from pathlib import Path
from typing import Any


EDGE_KINDS = ["CALLS_SERVICE", "INVOKES_RPC", "PUBLISHES_TO", "CONSUMES_FROM", "PUBLISHES_EVENT"]
GENERIC_ROLE_SUFFIXES = ("-microservice", "-service", "-server", "-ms", "-app")
AGGREGATOR_ID_RE = re.compile(r"^[A-Za-z0-9_-]+:\.(?:->\.)?$")


def is_aggregator_descriptor(name: str) -> bool:
    """Filter root-level build aggregators (e.g. MAVEN:.) and ReadinessPlan.Family.IDE descriptors (IDE:*)."""
    s = name.strip()
    return not s or bool(AGGREGATOR_ID_RE.match(s)) or s.upper().startswith("IDE:")


def http_json(base_url: str, method: str, path: str, body: dict[str, Any] | None = None, timeout_s: int = 90) -> dict[str, Any]:
    url = base_url.rstrip("/") + path
    data = json.dumps(body).encode("utf-8") if body is not None else None
    headers = {"Content-Type": "application/json"} if data is not None else {}
    last_err: Exception | None = None
    for attempt in range(3):
        req = urllib.request.Request(url, data=data, headers=headers, method=method)
        try:
            with urllib.request.urlopen(req, timeout=timeout_s) as resp:
                return json.loads(resp.read().decode("utf-8"))
        except urllib.error.HTTPError as e:
            err_body = e.read().decode("utf-8", errors="replace")
            if e.code in (502, 503, 504) and attempt < 2:
                time.sleep(1.0 + attempt)
                last_err = RuntimeError(f"HTTP {e.code} {path}: {err_body}")
                continue
            raise RuntimeError(f"HTTP {e.code} {path}: {err_body}") from e
        except (urllib.error.URLError, ConnectionError, TimeoutError, OSError) as e:
            if attempt < 2:
                time.sleep(1.5 + attempt)
                last_err = e
                continue
            raise RuntimeError(f"TransportError {path}: {e}") from e
    raise RuntimeError(f"Request failed after retries {path}: {last_err}")


def wait_health(base_url: str, timeout_s: int = 30) -> dict[str, Any]:
    deadline = time.monotonic() + timeout_s
    while time.monotonic() < deadline:
        try:
            res = http_json(base_url, "GET", "/api/v1/health", timeout_s=5)
            http_json(base_url, "GET", "/api/v1/analysis/connections", timeout_s=5)
            time.sleep(1.0)
            return res.get("data", res)
        except Exception:
            time.sleep(0.5)
    raise RuntimeError(f"Container at {base_url} did not become healthy within {timeout_s}s")


def compute_median_iqr(values: list[float]) -> dict[str, float]:
    if not values:
        return {"n": 0.0, "median": 0.0, "iqr": 0.0, "min": 0.0, "max": 0.0}
    ordered = sorted(float(v) for v in values)
    n = len(ordered)
    med = float(statistics.median(ordered))
    if n == 1:
        q1 = q3 = med
    elif n % 2 == 0:
        q1 = float(statistics.median(ordered[: n // 2]))
        q3 = float(statistics.median(ordered[n // 2 :]))
    else:
        q1 = float(statistics.median(ordered[: n // 2]))
        q3 = float(statistics.median(ordered[n // 2 + 1 :]))
    return {
        "n": float(n),
        "median": round(med, 2),
        "iqr": round(max(0.0, q3 - q1), 2),
        "min": round(ordered[0], 2),
        "max": round(ordered[-1], 2),
    }


def compute_prf(pred: set[Any], exp: set[Any]) -> dict[str, Any]:
    tp = len(pred & exp)
    fp = len(pred - exp)
    fn = len(exp - pred)
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


def compute_micro_macro(pairs: list[tuple[set[Any], set[Any]]]) -> dict[str, Any]:
    if not pairs:
        empty = {"tp": 0, "fp": 0, "fn": 0, "precision": 0.0, "recall": 0.0, "f1": 0.0}
        return {"micro": empty, "macro": {"precision": 0.0, "recall": 0.0, "f1": 0.0, "apps": 0}}
    tp = fp = fn = 0
    ps: list[float] = []
    rs: list[float] = []
    f1s: list[float] = []
    for pr, ex in pairs:
        m = compute_prf(pr, ex)
        tp += m["tp"]
        fp += m["fp"]
        fn += m["fn"]
        ps.append(m["precision"])
        rs.append(m["recall"])
        f1s.append(m["f1"])
    mp = tp / (tp + fp) if (tp + fp) > 0 else 1.0
    mr = tp / (tp + fn) if (tp + fn) > 0 else 1.0
    mf1 = (2.0 * mp * mr / (mp + mr)) if (mp + mr) > 0 else 0.0
    return {
        "micro": {
            "tp": tp,
            "fp": fp,
            "fn": fn,
            "precision": round(mp, 4),
            "recall": round(mr, 4),
            "f1": round(mf1, 4),
        },
        "macro": {
            "precision": round(sum(ps) / len(ps), 4),
            "recall": round(sum(rs) / len(rs), 4),
            "f1": round(sum(f1s) / len(f1s), 4),
            "apps": len(pairs),
        },
    }


def aggregate_rows(rows: list[dict[str, Any]], family_key: str, target_key: str) -> dict[str, Any]:
    if not rows:
        empty = {"tp": 0, "fp": 0, "fn": 0, "precision": 0.0, "recall": 0.0, "f1": 0.0}
        return {"micro": empty, "macro": {"precision": 0.0, "recall": 0.0, "f1": 0.0, "apps": 0}}
    tp = fp = fn = 0
    ps: list[float] = []
    rs: list[float] = []
    f1s: list[float] = []
    for r in rows:
        m = r[family_key][target_key]
        tp += int(m["tp"])
        fp += int(m["fp"])
        fn += int(m["fn"])
        ps.append(float(m["precision"]))
        rs.append(float(m["recall"]))
        f1s.append(float(m["f1"]))
    mp = tp / (tp + fp) if (tp + fp) > 0 else 1.0
    mr = tp / (tp + fn) if (tp + fn) > 0 else 1.0
    mf1 = (2.0 * mp * mr / (mp + mr)) if (mp + mr) > 0 else 0.0
    return {
        "micro": {
            "tp": tp,
            "fp": fp,
            "fn": fn,
            "precision": round(mp, 4),
            "recall": round(mr, 4),
            "f1": round(mf1, 4),
        },
        "macro": {
            "precision": round(sum(ps) / len(ps), 4),
            "recall": round(sum(rs) / len(rs), 4),
            "f1": round(sum(f1s) / len(f1s), 4),
            "apps": len(rows),
        },
    }


def norm_strict(raw: str) -> str:
    if not raw:
        return ""
    s = raw.strip().replace("\\", "/")
    # Strip document qualifier such as 'docker-compose.yml#prometheus-server' -> 'prometheus-server'
    if "#" in s:
        s = s.rsplit("#", 1)[-1]
    if "/" in s:
        s = s.rstrip("/").rsplit("/", 1)[-1]
    s = re.sub(r"[_\s.]+", "-", s.lower())
    s = re.sub(r"-{2,}", "-", s).strip("-")
    return s


def compute_shared_module_prefix(strict_build_names: list[str]) -> str:
    """Data-driven detection of a multi-module project's common hyphen prefix (>= 3 sibling modules)."""
    counts: dict[str, int] = {}
    unique_names = sorted({n for n in strict_build_names if "-" in n})
    for name in unique_names:
        parts = [p for p in name.split("-") if p]
        for k in range(1, len(parts)):
            prefix = "-".join(parts[:k]) + "-"
            if len(name) > len(prefix):
                counts[prefix] = counts.get(prefix, 0) + 1
    eligible = [(cnt, len(pref), pref) for pref, cnt in counts.items() if cnt >= 3]
    if not eligible:
        return ""
    eligible.sort(reverse=True)
    return eligible[0][2]


def norm_canonical(raw: str, repo_dir: str = "", shared_prefix: str = "") -> str:
    s = norm_strict(raw)
    if not s:
        return ""
    if shared_prefix and s.startswith(shared_prefix) and len(s) > len(shared_prefix):
        s = s[len(shared_prefix) :]
    else:
        repo_tok = norm_strict(repo_dir)
        if repo_tok and s.startswith(repo_tok + "-") and len(s) > len(repo_tok) + 1:
            s = s[len(repo_tok) + 1 :]
    for suf in GENERIC_ROLE_SUFFIXES:
        if s.endswith(suf) and len(s) > len(suf) + 1:
            s = s[: -len(suf)]
            break
    return s.strip("-")


def norm_collapsed(raw: str, repo_dir: str = "", shared_prefix: str = "") -> str:
    s = norm_canonical(raw, repo_dir, shared_prefix).replace("-", "")
    for suf in ("microservice", "service", "server", "app"):
        if s.endswith(suf) and len(s) > len(suf) + 2:
            s = s[: -len(suf)]
            break
    return s


def parse_groundtruth_txt(path: Path) -> tuple[set[str], set[tuple[str, str]]]:
    comps: set[str] = set()
    conns: set[tuple[str, str]] = set()
    sec: str | None = None
    for raw_line in path.read_text(encoding="utf-8", errors="replace").splitlines():
        line = raw_line.strip()
        if not line:
            continue
        if line == "Components:":
            sec = "COMP"
            continue
        if line == "Connections:":
            sec = "CONN"
            continue
        if line == "Endpoints:":
            sec = None
            continue
        if sec == "COMP":
            comps.add(line)
        elif sec == "CONN" and "->" in line:
            left, right = line.split("->", 1)
            src = left.strip()
            dst = right.strip()
            if src and dst:
                conns.add((src, dst))
    return comps, conns


def parse_emse_baselines(rep_dir: Path) -> list[dict[str, Any]]:
    base = rep_dir / "tool_comparison" / "results" / "recovery_correctness" / "processed_results"
    if not base.is_dir():
        return []
    out: list[dict[str, Any]] = []
    for tool_dir in sorted(base.iterdir()):
        if not tool_dir.is_dir():
            continue
        comp_all = {"TP": 0, "FP": 0, "FN": 0}
        conn_all = {"TP": 0, "FP": 0, "FN": 0}
        comp_macro: list[tuple[float, float, float]] = []
        conn_macro: list[tuple[float, float, float]] = []
        files = sorted(tool_dir.glob("*.txt"))
        for f in files:
            c1 = {"TP": 0, "FP": 0, "FN": 0}
            c2 = {"TP": 0, "FP": 0, "FN": 0}
            sec = None
            for raw in f.read_text(encoding="utf-8", errors="replace").splitlines():
                s = raw.strip()
                if s == "Components:":
                    sec = (c1, comp_all)
                    continue
                if s == "Connections:":
                    sec = (c2, conn_all)
                    continue
                if s == "Endpoints:":
                    sec = None
                    continue
                if not s or sec is None:
                    continue
                tag = s.split(" ", 1)[0]
                if tag in ("TP", "FP", "FN"):
                    sec[0][tag] += 1
                    sec[1][tag] += 1
            for bucket, macro_list in ((c1, comp_macro), (c2, conn_macro)):
                if sum(bucket.values()) > 0:
                    tp, fp, fn = bucket["TP"], bucket["FP"], bucket["FN"]
                    p = tp / (tp + fp) if (tp + fp) > 0 else (1.0 if fn == 0 else 0.0)
                    r = tp / (tp + fn) if (tp + fn) > 0 else (1.0 if fp == 0 else 0.0)
                    f1 = (2.0 * p * r / (p + r)) if (p + r) > 0 else 0.0
                    macro_list.append((p, r, f1))

        def summarize(micro: dict[str, int], macro: list[tuple[float, float, float]]) -> dict[str, Any]:
            tp, fp, fn = micro["TP"], micro["FP"], micro["FN"]
            up = tp / (tp + fp) if (tp + fp) > 0 else 0.0
            ur = tp / (tp + fn) if (tp + fn) > 0 else 0.0
            uf1 = (2.0 * up * ur / (up + ur)) if (up + ur) > 0 else 0.0
            mp = sum(x[0] for x in macro) / len(macro) if macro else 0.0
            mr = sum(x[1] for x in macro) / len(macro) if macro else 0.0
            mf1 = sum(x[2] for x in macro) / len(macro) if macro else 0.0
            return {
                "tp": tp, "fp": fp, "fn": fn,
                "micro_p": round(up, 4), "micro_r": round(ur, 4), "micro_f1": round(uf1, 4),
                "macro_n": len(macro), "macro_p": round(mp, 4), "macro_r": round(mr, 4), "macro_f1": round(mf1, 4),
            }

        out.append({
            "tool": tool_dir.name,
            "apps_completed": len(files),
            "components": summarize(comp_all, comp_macro),
            "connections": summarize(conn_all, conn_macro),
        })
    return out


def fetch_paged(base_url: str, graph_id: str, op: str, request_obj: dict[str, Any]) -> list[dict[str, Any]]:
    items: list[dict[str, Any]] = []
    cursor: str | None = None
    while True:
        page: dict[str, Any] = {"size": 50}
        if cursor:
            page["cursor"] = cursor
        data: dict[str, Any] = {}
        for attempt in range(4):
            res = http_json(base_url, "POST", f"/api/v1/graphs/{graph_id}/queries/{op}", {
                "request": request_obj,
                "page": page,
            })
            data = res.get("data", {})
            bounds = data.get("bounds") or {}
            stop_reason = bounds.get("stopReason", "NONE")
            if data.get("operation") == "RESOURCE_LIMIT" or stop_reason in ("MEMORY", "TIME"):
                if attempt < 3:
                    time.sleep(1.5 + attempt)
                    continue
                raise RuntimeError(
                    f"Query {op} truncated by {data.get('operation')} (stopReason={stop_reason}) on graph {graph_id}"
                )
            break
        items.extend(data.get("items", []))
        cursor = data.get("continuation")
        if not cursor or len(items) >= 5000:
            break
    return items


def submit_and_wait(base_url: str, setup: str, key: str, force: bool, timeout_s: int) -> tuple[float, dict[str, Any]]:
    t0 = time.perf_counter()
    job = http_json(base_url, "POST", "/api/v1/analysis/jobs", {
        "setup": setup,
        "idempotencyKey": key,
        "mode": "FULL",
        "force": force,
    })["data"]
    job_id = job["id"]
    deadline = time.monotonic() + timeout_s
    while True:
        st = http_json(base_url, "GET", f"/api/v1/analysis/jobs/{job_id}")["data"]
        if st["state"] in ("SUCCEEDED", "PARTIAL", "EMPTY", "FAILED", "CANCELLED"):
            wall_ms = (time.perf_counter() - t0) * 1000.0
            return wall_ms, st
        if time.monotonic() >= deadline:
            try:
                http_json(base_url, "POST", f"/api/v1/analysis/jobs/{job_id}/cancel", {})
            except Exception:
                pass
            raise TimeoutError(f"Job {job_id} timed out after {timeout_s}s (stage={st.get('stage')})")
        time.sleep(0.5)


def extract_topology_from_graph(base_url: str, graph_id: str, snapshot_id: str) -> dict[str, Any]:
    base_req = {
        "context": snapshot_id,
        "scope": "ENTITY",
        "projection": "BOUNDED_UNION",
        "direction": "OUTGOING",
        "relationshipKinds": EDGE_KINDS,
        "limits": {
            "maxDepth": 20,
            "maxSteps": 100000,
            "maxResults": 50,
            "maxVertices": 100000,
            "maxEdges": 500000,
            "maxSolverSteps": 10000000,
            "maxFormulaNodes": 100000,
            "maxAuxiliaryStates": 500000,
        },
    }

    mod_items = fetch_paged(base_url, graph_id, "entities", {
        **base_req,
        "population": {"nodeKinds": ["MODULE"]},
    })
    module_id_to_path: dict[str, str] = {}
    for m in mod_items:
        mid = m.get("identity", "")
        mpath = m.get("canonicalName") or m.get("spelling") or ""
        if mid and mpath:
            module_id_to_path[mid] = mpath

    comp_items = fetch_paged(base_url, graph_id, "entities", {
        **base_req,
        "population": {"nodeKinds": ["BUILD_COMPONENT", "INFRA_COMPONENT", "EXTERNAL_ACTOR"]},
    })

    raw_components: list[dict[str, str]] = []
    build_dirs: list[str] = []
    build_dir_to_name: dict[str, str] = {}
    entity_label_by_id: dict[str, str] = {}

    for c in comp_items:
        kind = c.get("kind", "")
        cid = c.get("identity", "")
        cname = c.get("canonicalName") or c.get("spelling") or ""
        cmod = (c.get("module") or "").replace("\\", "/").strip("/")
        if is_aggregator_descriptor(cname) or (cmod and is_aggregator_descriptor(cmod)):
            continue
        bdir = cmod if (kind == "BUILD_COMPONENT" and cmod) else cname.replace("\\", "/").strip("/")
        raw_components.append({"id": cid, "kind": kind, "name": cname, "dir": bdir})
        entity_label_by_id[cid] = cname
        if kind == "BUILD_COMPONENT":
            build_dirs.append(bdir)
            if bdir and bdir != ".":
                build_dir_to_name.setdefault(bdir, cname)

    sorted_build_dirs = sorted(
        [d for d in build_dirs if d and d != "."],
        key=lambda d: len(d),
        reverse=True,
    )

    def resolve_path_to_component(rel_path: str) -> str:
        norm = rel_path.replace("\\", "/").lstrip("./")
        for bdir in sorted_build_dirs:
            if norm == bdir or norm.startswith(bdir + "/"):
                return build_dir_to_name.get(bdir, bdir)
        if norm.startswith("src/"):
            first_dir = build_dirs[0] if build_dirs else norm.split("/", 1)[0]
            return build_dir_to_name.get(first_dir, first_dir)
        return norm.split("/", 1)[0] if "/" in norm else norm

    def resolve_entity_name(eid: str, entity_cache: dict[str, dict[str, Any]]) -> str:
        if eid in entity_label_by_id:
            return entity_label_by_id[eid]
        ent = entity_cache.get(eid)
        if ent is None:
            res = http_json(base_url, "POST", f"/api/v1/graphs/{graph_id}/queries/entity", {
                "request": base_req,
                "arguments": {"identity": eid},
            })["data"]
            items = res.get("items", [])
            ent = items[0] if items else {}
            entity_cache[eid] = ent
        kind = ent.get("kind", "")
        cname = ent.get("canonicalName") or ent.get("spelling") or ""
        mod = ent.get("module") or ""
        if kind in ("BUILD_COMPONENT", "INFRA_COMPONENT", "EXTERNAL_ACTOR") and not is_aggregator_descriptor(cname):
            entity_label_by_id[eid] = cname
            return cname
        if mod and mod in module_id_to_path:
            mp = module_id_to_path[mod]
            resolved = resolve_path_to_component(mp)
            if resolved:
                entity_label_by_id[eid] = resolved
                return resolved
        if cname and not cname.startswith("java:v1:") and not is_aggregator_descriptor(cname):
            resolved = resolve_path_to_component(cname)
            entity_label_by_id[eid] = resolved
            return resolved
        if mod and not mod.startswith("module:sha256:"):
            resolved = resolve_path_to_component(mod)
            entity_label_by_id[eid] = resolved
            return resolved
        return ""

    aggregator_dirs = {
        d for d in build_dirs
        if d == "." or any(other != d and other.startswith(d + "/") for other in build_dirs)
    }

    edge_items = fetch_paged(base_url, graph_id, "edges", base_req)
    entity_cache: dict[str, dict[str, Any]] = {}
    evidence_cache: dict[str, dict[str, Any]] = {}
    raw_edges: list[dict[str, str]] = []

    for ed in edge_items:
        src_name = ""
        dst_name = ""
        mechanism = ""
        for ref in ed.get("evidence", []):
            ev = evidence_cache.get(ref)
            if ev is None:
                ev_res = http_json(base_url, "POST", f"/api/v1/graphs/{graph_id}/queries/evidence", {
                    "request": base_req,
                    "arguments": {"reference": ref},
                })["data"]
                ev_items = ev_res.get("items", [])
                ev = ev_items[0] if ev_items else {}
                evidence_cache[ref] = ev
            if ev.get("family") == "architecture.declarative-topology":
                meta = ev.get("metadata", {})
                if meta.get("source") and meta.get("target"):
                    src_name = str(meta["source"])
                    dst_name = str(meta["target"])
                    mechanism = str(meta.get("mechanism", ""))
                    break
        if not src_name:
            src_name = resolve_entity_name(ed.get("source", ""), entity_cache)
        if not dst_name:
            dst_name = resolve_entity_name(ed.get("target", ""), entity_cache)
        if src_name and dst_name:
            raw_edges.append({
                "kinds": ",".join(ed.get("kinds", [])),
                "source": src_name,
                "target": dst_name,
                "mechanism": mechanism,
            })

    leaf_components = [
        c for c in raw_components
        if c["kind"] != "BUILD_COMPONENT"
        or c["dir"] not in aggregator_dirs
        or len(build_dirs) <= 1
    ]
    shared_prefix = compute_shared_module_prefix([
        norm_strict(c["name"]) for c in leaf_components if c["kind"] == "BUILD_COMPONENT"
    ])
    return {
        "raw_components": raw_components,
        "leaf_components": leaf_components,
        "raw_edges": raw_edges,
        "aggregator_dirs": sorted(aggregator_dirs),
        "shared_prefix": shared_prefix,
    }


def evaluate_app(
    idx: int,
    app: dict[str, Any],
    base_url: str,
    profile_label: str,
    gt_dir: Path,
    timeout_s: int,
    run_tag: str,
) -> dict[str, Any]:
    app_id = app["id"]
    app_dir = app["dir"]
    p_code = "s" if profile_label == "strict" else "d"
    gt_matches = [p for p in gt_dir.glob("*.txt") if p.stem.lower() == app_dir.lower()]
    if not gt_matches:
        raise FileNotFoundError(f"Missing groundtruth file for {app_dir} under {gt_dir}")
    gt_comps_raw, gt_conns_raw = parse_groundtruth_txt(gt_matches[0])

    gt_comps_strict = {norm_strict(c) for c in gt_comps_raw if norm_strict(c)}
    gt_conns_strict = {
        (norm_strict(a), norm_strict(b))
        for a, b in gt_conns_raw
        if norm_strict(a) and norm_strict(b) and norm_strict(a) != norm_strict(b)
    }

    row: dict[str, Any] = {
        "app_id": app_id,
        "dir": app_dir,
        "url": app["url"],
        "build": app.get("build", ""),
        "profile": profile_label,
        "status": "ERROR",
        "error": "",
        "cold_wall_ms": 0.0,
        "cold_elapsed_ms": 0.0,
        "warm_wall_ms": 0.0,
        "warm_elapsed_ms": 0.0,
        "cold_vs_warm1_sha256_match": False,
        "steady_state_sha256_match": False,
        "gap_count": 0,
        "warm1_gap_count": 0,
        "shared_prefix": "",
        "gt_components": len(gt_comps_raw),
        "gt_connections": len(gt_conns_raw),
    }

    try:
        setup = None
        cold_wall, cold_st = 0.0, {}
        for attempt_idx, tag_suffix in enumerate(("0", "1")):
            try:
                conn = http_json(base_url, "POST", "/api/v1/analysis/connections", {"source": app["url"]}, timeout_s=timeout_s)["data"]
                setup = conn["setup"]
                cold_wall, cold_st = submit_and_wait(base_url, setup, f"e6-{p_code}c-{idx}-{run_tag}-{tag_suffix}", False, timeout_s)
                break
            except Exception:
                if attempt_idx == 1:
                    raise
                time.sleep(2.0)
        row["cold_wall_ms"] = round(cold_wall, 1)
        row["cold_elapsed_ms"] = float(cold_st.get("elapsedMillis") or 0)
        row["status"] = cold_st.get("state", "UNKNOWN")
        if cold_st.get("error"):
            row["error"] = str(cold_st["error"])

        cold_res = cold_st.get("result") or {}
        cold_graph = cold_res.get("graph")
        cold_snap = cold_res.get("snapshot")
        row["completion"] = cold_res.get("completion", "")
        row["gap_count"] = int(cold_res.get("gapCount") or 0)
        row["cold_graph"] = cold_graph

        warm1_wall, warm1_st = submit_and_wait(base_url, setup, f"e6-{p_code}w1-{idx}-{run_tag}", True, timeout_s)
        row["warm_wall_ms"] = round(warm1_wall, 1)
        row["warm_elapsed_ms"] = float(warm1_st.get("elapsedMillis") or 0)
        warm1_res = warm1_st.get("result") or {}
        warm1_graph = warm1_res.get("graph")
        row["warm1_graph"] = warm1_graph
        row["warm1_gap_count"] = int(warm1_res.get("gapCount") or 0)
        row["cold_vs_warm1_sha256_match"] = bool(cold_graph and warm1_graph and cold_graph == warm1_graph)

        if row["cold_vs_warm1_sha256_match"]:
            row["steady_state_sha256_match"] = True
        elif warm1_graph:
            _, warm2_st = submit_and_wait(base_url, setup, f"e6-{p_code}w2-{idx}-{run_tag}", True, timeout_s)
            warm2_res = warm2_st.get("result") or {}
            warm2_graph = warm2_res.get("graph")
            row["warm2_graph"] = warm2_graph
            row["steady_state_sha256_match"] = bool(warm1_graph == warm2_graph)
            obs: dict[str, Any] = {
                "cold_snapshot": cold_snap,
                "warm1_snapshot": warm1_res.get("snapshot"),
                "warm2_snapshot": warm2_res.get("snapshot"),
                "cold_result_fields": {
                    k: v for k, v in cold_res.items()
                    if isinstance(v, (str, int, float, bool)) or v is None
                },
                "warm1_result_fields": {
                    k: v for k, v in warm1_res.items()
                    if isinstance(v, (str, int, float, bool)) or v is None
                },
                "warm2_result_fields": {
                    k: v for k, v in warm2_res.items()
                    if isinstance(v, (str, int, float, bool)) or v is None
                },
            }
            try:
                g_cold = http_json(base_url, "GET", f"/api/v1/graphs/{cold_graph}", timeout_s=30).get("data", {})
                g_warm1 = http_json(base_url, "GET", f"/api/v1/graphs/{warm1_graph}", timeout_s=30).get("data", {})
                obs["cold_graph_summary"] = {
                    k: v for k, v in g_cold.items()
                    if isinstance(v, (str, int, float, bool)) or k in ("counts", "statistics", "manifest", "projection")
                }
                obs["warm1_graph_summary"] = {
                    k: v for k, v in g_warm1.items()
                    if isinstance(v, (str, int, float, bool)) or k in ("counts", "statistics", "manifest", "projection")
                }
            except Exception as meta_ex:
                obs["graph_meta_error"] = str(meta_ex)[:200]
            row["cold_vs_warm1_observed"] = obs

        target_graph = cold_graph or warm1_graph
        target_snap = cold_snap or warm1_res.get("snapshot")
        if target_graph and target_snap:
            topo = extract_topology_from_graph(base_url, target_graph, target_snap)
            if not topo["leaf_components"] and warm1_graph and warm1_graph != target_graph:
                topo = extract_topology_from_graph(base_url, warm1_graph, warm1_res.get("snapshot") or target_snap)
            shared_prefix = topo["shared_prefix"]
            row["shared_prefix"] = shared_prefix

            gt_comps_canon = {norm_canonical(c, app_dir, shared_prefix) for c in gt_comps_raw if norm_canonical(c, app_dir, shared_prefix)}
            gt_conns_canon = {
                (norm_canonical(a, app_dir, shared_prefix), norm_canonical(b, app_dir, shared_prefix))
                for a, b in gt_conns_raw
                if norm_canonical(a, app_dir, shared_prefix) and norm_canonical(b, app_dir, shared_prefix)
                and norm_canonical(a, app_dir, shared_prefix) != norm_canonical(b, app_dir, shared_prefix)
            }
            gt_comps_col = {norm_collapsed(c, app_dir, shared_prefix) for c in gt_comps_raw if norm_collapsed(c, app_dir, shared_prefix)}
            gt_conns_col = {
                (norm_collapsed(a, app_dir, shared_prefix), norm_collapsed(b, app_dir, shared_prefix))
                for a, b in gt_conns_raw
                if norm_collapsed(a, app_dir, shared_prefix) and norm_collapsed(b, app_dir, shared_prefix)
                and norm_collapsed(a, app_dir, shared_prefix) != norm_collapsed(b, app_dir, shared_prefix)
            }

            pred_names = [c["name"] for c in topo["leaf_components"]]
            pred_edges = [(e["source"], e["target"]) for e in topo["raw_edges"]]

            pred_c_strict = {norm_strict(x) for x in pred_names if norm_strict(x)}
            pred_e_strict = {
                (norm_strict(a), norm_strict(b))
                for a, b in pred_edges
                if norm_strict(a) and norm_strict(b) and norm_strict(a) != norm_strict(b)
            }
            pred_c_canon = {norm_canonical(x, app_dir, shared_prefix) for x in pred_names if norm_canonical(x, app_dir, shared_prefix)}
            pred_e_canon = {
                (norm_canonical(a, app_dir, shared_prefix), norm_canonical(b, app_dir, shared_prefix))
                for a, b in pred_edges
                if norm_canonical(a, app_dir, shared_prefix) and norm_canonical(b, app_dir, shared_prefix)
                and norm_canonical(a, app_dir, shared_prefix) != norm_canonical(b, app_dir, shared_prefix)
            }
            pred_c_col = {norm_collapsed(x, app_dir, shared_prefix) for x in pred_names if norm_collapsed(x, app_dir, shared_prefix)}
            pred_e_col = {
                (norm_collapsed(a, app_dir, shared_prefix), norm_collapsed(b, app_dir, shared_prefix))
                for a, b in pred_edges
                if norm_collapsed(a, app_dir, shared_prefix) and norm_collapsed(b, app_dir, shared_prefix)
                and norm_collapsed(a, app_dir, shared_prefix) != norm_collapsed(b, app_dir, shared_prefix)
            }

            row["pred_components_raw"] = sorted(pred_c_strict)
            row["pred_components_canon"] = sorted(pred_c_canon)
            row["pred_connections_canon"] = sorted([f"{a} -> {b}" for a, b in pred_e_canon])
            row["strict_metrics"] = {
                "components": compute_prf(pred_c_strict, gt_comps_strict),
                "connections": compute_prf(pred_e_strict, gt_conns_strict),
            }
            row["canon_metrics"] = {
                "components": compute_prf(pred_c_canon, gt_comps_canon),
                "connections": compute_prf(pred_e_canon, gt_conns_canon),
            }
            row["collapsed_metrics"] = {
                "components": compute_prf(pred_c_col, gt_comps_col),
                "connections": compute_prf(pred_e_col, gt_conns_col),
            }
            row["_sets"] = {
                "gt_c_strict": gt_comps_strict, "pr_c_strict": pred_c_strict,
                "gt_e_strict": gt_conns_strict, "pr_e_strict": pred_e_strict,
                "gt_c_canon": gt_comps_canon, "pr_c_canon": pred_c_canon,
                "gt_e_canon": gt_conns_canon, "pr_e_canon": pred_e_canon,
                "gt_c_col": gt_comps_col, "pr_c_col": pred_c_col,
                "gt_e_col": gt_conns_col, "pr_e_col": pred_e_col,
            }
            return row
    except Exception as ex:
        row["status"] = "ERROR"
        row["error"] = str(ex)[:400]

    gt_comps_canon = {norm_canonical(c, app_dir, "") for c in gt_comps_raw if norm_canonical(c, app_dir, "")}
    gt_conns_canon = {
        (norm_canonical(a, app_dir, ""), norm_canonical(b, app_dir, ""))
        for a, b in gt_conns_raw
        if norm_canonical(a, app_dir, "") and norm_canonical(b, app_dir, "")
        and norm_canonical(a, app_dir, "") != norm_canonical(b, app_dir, "")
    }
    gt_comps_col = {norm_collapsed(c, app_dir, "") for c in gt_comps_raw if norm_collapsed(c, app_dir, "")}
    gt_conns_col = {
        (norm_collapsed(a, app_dir, ""), norm_collapsed(b, app_dir, ""))
        for a, b in gt_conns_raw
        if norm_collapsed(a, app_dir, "") and norm_collapsed(b, app_dir, "")
        and norm_collapsed(a, app_dir, "") != norm_collapsed(b, app_dir, "")
    }
    row["strict_metrics"] = {
        "components": compute_prf(set(), gt_comps_strict),
        "connections": compute_prf(set(), gt_conns_strict),
    }
    row["canon_metrics"] = {
        "components": compute_prf(set(), gt_comps_canon),
        "connections": compute_prf(set(), gt_conns_canon),
    }
    row["collapsed_metrics"] = {
        "components": compute_prf(set(), gt_comps_col),
        "connections": compute_prf(set(), gt_conns_col),
    }
    row["_sets"] = {
        "gt_c_strict": gt_comps_strict, "pr_c_strict": set(),
        "gt_e_strict": gt_conns_strict, "pr_e_strict": set(),
        "gt_c_canon": gt_comps_canon, "pr_c_canon": set(),
        "gt_e_canon": gt_conns_canon, "pr_e_canon": set(),
        "gt_c_col": gt_comps_col, "pr_c_col": set(),
        "gt_e_col": gt_conns_col, "pr_e_col": set(),
    }
    return row


def serialize_sets(sets_dict: dict[str, set[Any]]) -> dict[str, list[Any]]:
    out: dict[str, list[Any]] = {}
    for k, v in sets_dict.items():
        if "_e_" in k:
            out[k] = sorted([list(p) for p in v])
        else:
            out[k] = sorted(v)
    return out


def deserialize_sets(sets_dict: dict[str, list[Any]]) -> dict[str, set[Any]]:
    out: dict[str, set[Any]] = {}
    for k, v in sets_dict.items():
        if "_e_" in k:
            out[k] = {tuple(p) for p in v}
        else:
            out[k] = set(v)
    return out


def summarize_metrics_from_rows(rows: list[dict[str, Any]], metric_key: str, kind: str) -> dict[str, Any]:
    tp = sum(int(r[metric_key][kind]["tp"]) for r in rows)
    fp = sum(int(r[metric_key][kind]["fp"]) for r in rows)
    fn = sum(int(r[metric_key][kind]["fn"]) for r in rows)
    up = tp / (tp + fp) if (tp + fp) > 0 else (1.0 if fn == 0 else 0.0)
    ur = tp / (tp + fn) if (tp + fn) > 0 else (1.0 if fp == 0 else 0.0)
    uf1 = (2.0 * up * ur / (up + ur)) if (up + ur) > 0 else 0.0
    n = len(rows)
    mp = sum(float(r[metric_key][kind]["precision"]) for r in rows) / n if n else 0.0
    mr = sum(float(r[metric_key][kind]["recall"]) for r in rows) / n if n else 0.0
    mf1 = sum(float(r[metric_key][kind]["f1"]) for r in rows) / n if n else 0.0
    return {
        "micro": {"tp": tp, "fp": fp, "fn": fn, "precision": round(up, 4), "recall": round(ur, 4), "f1": round(uf1, 4)},
        "macro": {"precision": round(mp, 4), "recall": round(mr, 4), "f1": round(mf1, 4), "apps": n},
    }


def is_complete_steady_row(row: dict[str, Any] | None) -> bool:
    if not row:
        return False
    return (
        row.get("status") not in (None, "", "ERROR", "FAILED", "CANCELLED")
        and bool(row.get("cold_graph"))
        and bool(row.get("steady_state_sha256_match"))
    )


def run_profile_suite(
    profile_label: str,
    base_url: str,
    apps: list[dict[str, Any]],
    gt_dir: Path,
    timeout_s: int,
    run_tag: str,
    checkpoint_path: Path | None = None,
    checkpoint_lock: Any = None,
    existing_by_app: dict[str, dict[str, Any]] | None = None,
    resume: bool = False,
    start_idx: int = 1,
    total_apps: int | None = None,
) -> dict[str, dict[str, Any]]:
    by_app: dict[str, dict[str, Any]] = dict(existing_by_app or {})
    total = total_apps if total_apps is not None else len(apps)
    for offset, app in enumerate(apps):
        idx = start_idx + offset
        app_id = app["id"]
        if resume and is_complete_steady_row(by_app.get(app_id)):
            row = by_app[app_id]
            cm = row["collapsed_metrics"]
            print(
                f"[{profile_label}] ({idx:2d}/{total}) {app_id:48s} CACHED status={row['status']} "
                f"cold={row['cold_elapsed_ms']:.0f}ms warm={row['warm_elapsed_ms']:.0f}ms steady={row['steady_state_sha256_match']} "
                f"| COMP(TP={cm['components']['tp']},FP={cm['components']['fp']},FN={cm['components']['fn']},F1={cm['components']['f1']:.3f}) "
                f"| CONN(TP={cm['connections']['tp']},FP={cm['connections']['fp']},FN={cm['connections']['fn']},F1={cm['connections']['f1']:.3f})",
                flush=True,
            )
            continue
        row = evaluate_app(idx, app, base_url, profile_label, gt_dir, timeout_s, run_tag)
        if not (resume and not is_complete_steady_row(row) and is_complete_steady_row(by_app.get(app_id))):
            by_app[app_id] = row
        if checkpoint_path is not None and checkpoint_lock is not None:
            with checkpoint_lock:
                ckpt: dict[str, dict[str, Any]] = {}
                if checkpoint_path.exists():
                    ckpt = json.loads(checkpoint_path.read_text(encoding="utf-8"))
                p_map = ckpt.setdefault(profile_label, {})
                prev_ckpt_row = p_map.get(app_id)
                if not (resume and not is_complete_steady_row(row) and is_complete_steady_row(prev_ckpt_row)):
                    row_copy = dict(row)
                    if "_sets" in row_copy:
                        row_copy["_sets"] = serialize_sets(row_copy["_sets"])
                    p_map[app_id] = row_copy
                tmp = checkpoint_path.with_suffix(".tmp")
                tmp.write_text(json.dumps(ckpt, indent=2), encoding="utf-8")
                tmp.replace(checkpoint_path)
        cm = row["collapsed_metrics"]
        err_suffix = f" err={row['error'][:80]}" if row.get("error") else ""
        print(
            f"[{profile_label}] ({idx:2d}/{total}) {app_id:48s} status={row['status']} "
            f"cold={row['cold_elapsed_ms']:.0f}ms warm={row['warm_elapsed_ms']:.0f}ms "
            f"cold==w1={row['cold_vs_warm1_sha256_match']} steady={row['steady_state_sha256_match']} pref='{row['shared_prefix']}' "
            f"| COMP(TP={cm['components']['tp']},FP={cm['components']['fp']},FN={cm['components']['fn']},F1={cm['components']['f1']:.3f}) "
            f"| CONN(TP={cm['connections']['tp']},FP={cm['connections']['fp']},FN={cm['connections']['fn']},F1={cm['connections']['f1']:.3f}){err_suffix}",
            flush=True,
        )
    return by_app


def main() -> int:
    import threading

    parser = argparse.ArgumentParser(description="E6 17-App microSecEnD Linux Docker Online Benchmark")
    parser.add_argument("--manifest", type=Path, default=Path("benchmarks/e0-publication-suite/corpus_manifest.json"))
    parser.add_argument("--ground-truth-dir", type=Path, required=True)
    parser.add_argument("--replication-dir", type=Path)
    parser.add_argument("--strict-url", default="http://127.0.0.1:18081")
    parser.add_argument("--dfd-url", default="http://127.0.0.1:18082")
    parser.add_argument("--out-dir", type=Path, required=True)
    parser.add_argument("--timeout-s", type=int, default=240)
    parser.add_argument("--start", type=int, default=1)
    parser.add_argument("--end", type=int, default=0)
    parser.add_argument("--limit", type=int, default=0)
    parser.add_argument("--split", choices=["all", "dev", "holdout"], default="all",
                        help="Run full 17-app corpus or stratified dev_10 / holdout_7 split from --split-file.")
    parser.add_argument("--split-file", type=Path, default=Path("benchmarks/e0-publication-suite/blind_holdout_split.json"))
    parser.add_argument("--resume", action="store_true")
    args = parser.parse_args()

    manifest = json.loads(args.manifest.read_text(encoding="utf-8"))
    all_apps = manifest["suites"]["microsecend_17"]
    if args.split != "all":
        split_doc = json.loads(args.split_file.read_text(encoding="utf-8"))
        split_key = "dev_10" if args.split == "dev" else "holdout_7"
        allowed_ids = {x["id"] for x in split_doc["microsecend_17_stratified_split"][split_key]}
        all_apps = [a for a in all_apps if a["id"] in allowed_ids]
    if args.limit > 0:
        all_apps = all_apps[: args.limit]
    start_idx = max(1, args.start)
    end_idx = len(all_apps) if args.end <= 0 else min(len(all_apps), args.end)
    apps = all_apps[start_idx - 1 : end_idx]

    h_strict = wait_health(args.strict_url)
    h_dfd = wait_health(args.dfd_url)
    if not (h_strict.get("onlineMode") and h_dfd.get("onlineMode")):
        raise RuntimeError("Containers must run in --online mode")

    baselines = parse_emse_baselines(args.replication_dir) if args.replication_dir else []
    run_tag = dt.datetime.now(dt.timezone.utc).strftime("%Y%m%d%H%M%S")
    args.out_dir.mkdir(parents=True, exist_ok=True)
    checkpoint_path = args.out_dir / "e6_checkpoint.json"
    ckpt_lock = threading.Lock()
    ckpt_existing: dict[str, dict[str, Any]] = {}
    summary_path = args.out_dir / "e6_summary.json"
    if args.resume and summary_path.exists():
        prev_sum = json.loads(summary_path.read_text(encoding="utf-8"))
        for p_label, rows in prev_sum.get("runs", {}).items():
            ckpt_existing[p_label] = {r["app_id"]: dict(r) for r in rows if r.get("app_id")}
    if args.resume and checkpoint_path.exists():
        raw_ckpt = json.loads(checkpoint_path.read_text(encoding="utf-8"))
        for p_label, p_map in raw_ckpt.items():
            dest = ckpt_existing.setdefault(p_label, {})
            for a_id, row in p_map.items():
                r_copy = dict(row)
                if "_sets" in r_copy:
                    r_copy["_sets"] = deserialize_sets(r_copy["_sets"])
                prev = dest.get(a_id)
                if prev is None or is_complete_steady_row(r_copy) or not is_complete_steady_row(prev):
                    dest[a_id] = r_copy

    print(f"=== Running apps {start_idx}..{end_idx} ({len(apps)}/{len(all_apps)}) concurrently on strict ({args.strict_url}) & dfd-export ({args.dfd_url}) ===", flush=True)
    with ThreadPoolExecutor(max_workers=2) as pool:
        f_strict = pool.submit(
            run_profile_suite, "strict", args.strict_url, apps, args.ground_truth_dir,
            args.timeout_s, run_tag, checkpoint_path, ckpt_lock, ckpt_existing.get("strict"), args.resume,
            start_idx, len(all_apps),
        )
        f_dfd = pool.submit(
            run_profile_suite, "dfd-export", args.dfd_url, apps, args.ground_truth_dir,
            args.timeout_s, run_tag, checkpoint_path, ckpt_lock, ckpt_existing.get("dfd-export"), args.resume,
            start_idx, len(all_apps),
        )
        merged_maps = {
            "strict": f_strict.result(),
            "dfd-export": f_dfd.result(),
        }

    # Aggregate over all available apps in manifest order (all completed slices so far)
    results_by_profile: dict[str, list[dict[str, Any]]] = {}
    for p_label in ("strict", "dfd-export"):
        p_map = merged_maps[p_label]
        results_by_profile[p_label] = [p_map[a["id"]] for a in all_apps if a["id"] in p_map]

    summary_profiles: dict[str, Any] = {}
    for profile_label, rows in results_by_profile.items():
        if not rows:
            continue
        c_strict = aggregate_rows(rows, "strict_metrics", "components")
        e_strict = aggregate_rows(rows, "strict_metrics", "connections")
        c_canon = aggregate_rows(rows, "canon_metrics", "components")
        e_canon = aggregate_rows(rows, "canon_metrics", "connections")
        c_col = aggregate_rows(rows, "collapsed_metrics", "components")
        e_col = aggregate_rows(rows, "collapsed_metrics", "connections")
        summary_profiles[profile_label] = {
            "apps_total": len(rows),
            "apps_analyzed_with_graph": sum(1 for r in rows if r.get("cold_graph")),
            "cold_vs_warm1_sha256_passes": sum(1 for r in rows if r.get("cold_vs_warm1_sha256_match")),
            "steady_state_sha256_passes": sum(1 for r in rows if r.get("steady_state_sha256_match")),
            "cold_elapsed_ms": compute_median_iqr([r["cold_elapsed_ms"] for r in rows if r["cold_elapsed_ms"] > 0]),
            "warm_elapsed_ms": compute_median_iqr([r["warm_elapsed_ms"] for r in rows if r["warm_elapsed_ms"] > 0]),
            "strict_normalized": {"components": c_strict, "connections": e_strict},
            "canonical_normalized": {"components": c_canon, "connections": e_canon},
            "collapsed_canonical": {"components": c_col, "connections": e_col},
        }
        for r in rows:
            r.pop("_sets", None)

    summary = {
        "timestamp_utc": dt.datetime.now(dt.timezone.utc).isoformat(),
        "apps_count": max((len(v) for v in results_by_profile.values()), default=0),
        "profiles": summary_profiles,
        "emse2025sar_baselines": baselines,
        "runs": results_by_profile,
    }
    (args.out_dir / "e6_summary.json").write_text(json.dumps(summary, indent=2), encoding="utf-8")

    with (args.out_dir / "e6_per_app.csv").open("w", newline="", encoding="utf-8") as fh:
        w = csv.writer(fh)
        w.writerow([
            "profile", "app_id", "status", "completion", "cold_elapsed_ms", "warm_elapsed_ms",
            "cold_vs_warm1_sha256", "steady_state_sha256", "gap_count", "warm1_gap_count",
            "comp_tp", "comp_fp", "comp_fn", "comp_p", "comp_r", "comp_f1",
            "conn_tp", "conn_fp", "conn_fn", "conn_p", "conn_r", "conn_f1",
        ])
        for profile_label, rows in results_by_profile.items():
            for r in rows:
                mc = r["canon_metrics"]["components"]
                me = r["canon_metrics"]["connections"]
                w.writerow([
                    profile_label, r["app_id"], r["status"], r.get("completion", ""),
                    r["cold_elapsed_ms"], r["warm_elapsed_ms"],
                    r["cold_vs_warm1_sha256_match"], r["steady_state_sha256_match"],
                    r["gap_count"], r["warm1_gap_count"],
                    mc["tp"], mc["fp"], mc["fn"], mc["precision"], mc["recall"], mc["f1"],
                    me["tp"], me["fp"], me["fn"], me["precision"], me["recall"], me["f1"],
                ])

    print("\n=== AGGREGATE SUMMARY (Canonical vs Delimiter-Collapsed Canonical) ===")
    for p_name, s in summary_profiles.items():
        cc = s["canonical_normalized"]["components"]
        ce = s["canonical_normalized"]["connections"]
        lc = s["collapsed_canonical"]["components"]
        le = s["collapsed_canonical"]["connections"]
        print(
            f"Evo[{p_name}] apps={s['apps_analyzed_with_graph']}/{s['apps_total']} "
            f"cold==w1={s['cold_vs_warm1_sha256_passes']}/{s['apps_total']} steady={s['steady_state_sha256_passes']}/{s['apps_total']} "
            f"coldMed={s['cold_elapsed_ms']['median']}±{s['cold_elapsed_ms']['iqr']}ms "
            f"warmMed={s['warm_elapsed_ms']['median']}±{s['warm_elapsed_ms']['iqr']}ms\n"
            f"  [Canonical] COMP uF1={cc['micro']['f1']:.4f} mF1={cc['macro']['f1']:.4f} (TP={cc['micro']['tp']},FP={cc['micro']['fp']},FN={cc['micro']['fn']}) | "
            f"CONN uF1={ce['micro']['f1']:.4f} mF1={ce['macro']['f1']:.4f} (TP={ce['micro']['tp']},FP={ce['micro']['fp']},FN={ce['micro']['fn']})\n"
            f"  [Collapsed] COMP uF1={lc['micro']['f1']:.4f} mF1={lc['macro']['f1']:.4f} (TP={lc['micro']['tp']},FP={lc['micro']['fp']},FN={lc['micro']['fn']}) | "
            f"CONN uF1={le['micro']['f1']:.4f} mF1={le['macro']['f1']:.4f} (TP={le['micro']['tp']},FP={le['micro']['fp']},FN={le['micro']['fn']})"
        )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
