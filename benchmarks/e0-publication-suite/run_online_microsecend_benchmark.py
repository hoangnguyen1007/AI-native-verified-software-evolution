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
import urllib.parse
import urllib.request
from pathlib import Path
from typing import Any, Callable


EDGE_KINDS = ["CALLS_SERVICE", "INVOKES_RPC", "PUBLISHES_TO", "CONSUMES_FROM", "PUBLISHES_EVENT"]
GENERIC_ROLE_SUFFIXES = ("-microservice", "-service", "-server", "-ms", "-app")
AGGREGATOR_ID_RE = re.compile(r"^[A-Za-z0-9_-]+:\.(?:->\.)?$")
EMSE_COUNT_TAG_RE = re.compile(r"^(TP|FP|FN)\((\d+)\)(?:\s|$)")
EMSE_EDGE_SEP_RE = re.compile(r"->|\s-\s|\S-\s|\s-\S")
HTTP_METHOD_PREFIX_RE = re.compile(
    r"^(?:GET|POST|PUT|DELETE|PATCH|HEAD|OPTIONS|TRACE|ANY)"
    r"(?:,(?:GET|POST|PUT|DELETE|PATCH|HEAD|OPTIONS|TRACE|ANY))*\s+(.+)$"
)
PATH_VAR_BRACE_RE = re.compile(r"\{[^}/]+\}")
PATH_VAR_COLON_RE = re.compile(r"(?<=/)[:][A-Za-z_][A-Za-z0-9_]*")
LANG_TAG_SUFFIX_RE = re.compile(r"\s+\[[A-Za-z0-9_+-]+\]$")
TEST_PATH_RE = re.compile(r"(?:^|/)src/test(?:/|$)|(?:^|/)tests?(?:/|$)|(?:Test|Tests|IT)\.java$", re.IGNORECASE)


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


def compute_prf_counts(tp: int, fp: int, fn: int) -> dict[str, Any]:
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


def compute_prf(pred: set[Any], exp: set[Any]) -> dict[str, Any]:
    tp = len(pred & exp)
    fp = len(pred - exp)
    fn = len(exp - pred)
    return compute_prf_counts(tp, fp, fn)


def compute_prf_raw_gt(
    pred_strict: set[Any],
    gt_raw: set[Any],
    strict_key_fn: Callable[[Any], Any],
    collapsed_key_fn: Callable[[Any], Any],
) -> dict[str, Any]:
    """Two-pass 1-to-1 matching preserving the exact raw GT denominator (zero silent GT collapse).

    Pass 1 matches predictions against raw GT items by exact strict key (1-to-1).
    Pass 2 matches remaining unmatched predictions against remaining unmatched raw GT items
    by collapsed key (1-to-1), so colliding GT items (e.g., 'user' + 'user_service' -> 'user')
    retain distinct slots and tp + fn == len(gt_entries) always holds.
    """
    gt_entries: list[tuple[Any, Any, Any]] = []
    for item in sorted(gt_raw):
        sk = strict_key_fn(item)
        if not sk:
            continue
        ck = collapsed_key_fn(item)
        gt_entries.append((item, sk, ck))

    pred_pool = {p for p in pred_strict if p}
    unmatched_gt: list[tuple[Any, Any, Any]] = []
    tp = 0
    for item, sk, ck in gt_entries:
        if sk in pred_pool:
            tp += 1
            pred_pool.remove(sk)
        else:
            unmatched_gt.append((item, sk, ck))

    gt_buckets: dict[Any, int] = {}
    for _, _, ck in unmatched_gt:
        gt_buckets[ck] = gt_buckets.get(ck, 0) + 1

    pred_buckets: dict[Any, int] = {}
    for pk in pred_pool:
        ck = collapsed_key_fn(pk)
        pred_buckets[ck] = pred_buckets.get(ck, 0) + 1

    fn = 0
    fp = 0
    for ck in set(gt_buckets) | set(pred_buckets):
        g_cnt = gt_buckets.get(ck, 0)
        p_cnt = pred_buckets.get(ck, 0)
        matched = min(g_cnt, p_cnt)
        tp += matched
        fn += g_cnt - matched
        fp += p_cnt - matched

    return compute_prf_counts(tp, fp, fn)


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
    valid_rows = [r for r in rows if family_key in r and target_key in r[family_key]]
    if not valid_rows:
        empty = {"tp": 0, "fp": 0, "fn": 0, "precision": 0.0, "recall": 0.0, "f1": 0.0}
        return {"micro": empty, "macro": {"precision": 0.0, "recall": 0.0, "f1": 0.0, "apps": 0}}
    tp = fp = fn = 0
    ps: list[float] = []
    rs: list[float] = []
    f1s: list[float] = []
    for r in valid_rows:
        m = r[family_key][target_key]
        tp += int(m["tp"])
        fp += int(m["fp"])
        fn += int(m["fn"])
        ps.append(float(m["precision"]))
        rs.append(float(m["recall"]))
        f1s.append(float(m["f1"]))
    mp = tp / (tp + fp) if (tp + fp) > 0 else (1.0 if fn == 0 else 0.0)
    mr = tp / (tp + fn) if (tp + fn) > 0 else (1.0 if fp == 0 else 0.0)
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
            "apps": len(valid_rows),
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


def norm_endpoint_path(raw: str, canonicalize_params: bool = False, lowercase: bool = False) -> str:
    """Normalize an HTTP/REST endpoint path while preserving URI structure.

    Strips optional HTTP method prefixes (e.g. 'GET,POST /accounts'), language tags
    (e.g. ' [java]'), and absolute http(s)/lb authorities; ensures a leading '/';
    collapses duplicate slashes; strips trailing '/' except for root '/'.
    When canonicalize_params=True, normalizes path template variables ({id}, :id) to '{}'.
    """
    if not raw:
        return ""
    s = raw.strip().strip('"').strip("'").strip("`").strip()
    if not s:
        return ""
    s = LANG_TAG_SUFFIX_RE.sub("", s).strip()
    m = HTTP_METHOD_PREFIX_RE.match(s)
    if m:
        s = m.group(1).strip()
    if not s or "sha256:" in s or s.startswith("java:v1:"):
        return ""
    low = s.lower()
    if low.startswith(("http://", "https://", "lb://")):
        parsed = urllib.parse.urlsplit(s)
        s = parsed.path or "/"
        if parsed.query:
            s = f"{s}?{parsed.query}"
    if not s.startswith("/"):
        s = "/" + s
    if "?" in s:
        path_part, query_part = s.split("?", 1)
        path_part = re.sub(r"/{2,}", "/", path_part)
        if len(path_part) > 1 and path_part.endswith("/"):
            path_part = path_part.rstrip("/")
        s = f"{path_part}?{query_part}"
    else:
        s = re.sub(r"/{2,}", "/", s)
        if len(s) > 1 and s.endswith("/"):
            s = s.rstrip("/")
    if canonicalize_params:
        s = PATH_VAR_BRACE_RE.sub("{}", s)
        s = PATH_VAR_COLON_RE.sub("{}", s)
    if lowercase:
        s = s.lower()
    return s


def parse_groundtruth_txt(path: Path) -> tuple[set[str], set[tuple[str, str]], set[tuple[str, str]]]:
    comps: set[str] = set()
    conns: set[tuple[str, str]] = set()
    endpoints: set[tuple[str, str]] = set()
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
            sec = "ENDP"
            continue
        if sec == "COMP":
            comps.add(line)
        elif sec == "CONN" and "->" in line:
            left, right = line.split("->", 1)
            src = left.strip()
            dst = right.strip()
            if src and dst:
                conns.add((src, dst))
        elif sec == "ENDP" and ":" in line:
            svc_part, rest = line.split(":", 1)
            svc = svc_part.strip()
            if not svc:
                continue
            quoted_paths = re.findall(r'"([^"]*)"', rest)
            if quoted_paths:
                for ep_path in quoted_paths:
                    ep_clean = ep_path.strip()
                    if ep_clean:
                        endpoints.add((svc, ep_clean))
            else:
                for token in rest.split(","):
                    ep_clean = token.strip().strip('"').strip("'")
                    if ep_clean:
                        endpoints.add((svc, ep_clean))
    return comps, conns, endpoints


def detect_collapsed_collisions(
    gt_comps_raw: set[str],
    gt_conns_raw: set[tuple[str, str]],
    gt_endpoints_raw: set[tuple[str, str]],
    app_dir: str = "",
    shared_prefix: str = "",
) -> dict[str, Any]:
    """Compute side-by-side raw/strict/canonical/collapsed GT counts and explicit collision maps."""
    gt_comps_strict = {norm_strict(c) for c in gt_comps_raw if norm_strict(c)}
    gt_conns_strict = {
        (norm_strict(a), norm_strict(b))
        for a, b in gt_conns_raw
        if norm_strict(a) and norm_strict(b) and norm_strict(a) != norm_strict(b)
    }
    gt_eps_strict = {
        (norm_strict(sv), norm_endpoint_path(p, canonicalize_params=False, lowercase=False))
        for sv, p in gt_endpoints_raw
        if norm_strict(sv) and norm_endpoint_path(p, canonicalize_params=False, lowercase=False)
    }

    gt_comps_canon = {
        norm_canonical(c, app_dir, shared_prefix)
        for c in gt_comps_raw
        if norm_canonical(c, app_dir, shared_prefix)
    }
    gt_conns_canon = {
        (norm_canonical(a, app_dir, shared_prefix), norm_canonical(b, app_dir, shared_prefix))
        for a, b in gt_conns_raw
        if norm_canonical(a, app_dir, shared_prefix)
        and norm_canonical(b, app_dir, shared_prefix)
        and norm_canonical(a, app_dir, shared_prefix) != norm_canonical(b, app_dir, shared_prefix)
    }
    gt_eps_canon = {
        (norm_canonical(sv, app_dir, shared_prefix), norm_endpoint_path(p, canonicalize_params=True, lowercase=False))
        for sv, p in gt_endpoints_raw
        if norm_canonical(sv, app_dir, shared_prefix)
        and norm_endpoint_path(p, canonicalize_params=True, lowercase=False)
    }

    comp_groups: dict[str, list[str]] = {}
    for c in sorted(gt_comps_raw):
        ck = norm_collapsed(c, app_dir, shared_prefix)
        if ck:
            comp_groups.setdefault(ck, []).append(c)

    conn_groups: dict[tuple[str, str], list[str]] = {}
    conn_self_loops: list[str] = []
    for a, b in sorted(gt_conns_raw):
        ka = norm_collapsed(a, app_dir, shared_prefix)
        kb = norm_collapsed(b, app_dir, shared_prefix)
        if not ka or not kb:
            continue
        if ka == kb:
            conn_self_loops.append(f"{a} -> {b}")
        else:
            conn_groups.setdefault((ka, kb), []).append(f"{a} -> {b}")

    ep_groups: dict[tuple[str, str], list[str]] = {}
    for sv, p in sorted(gt_endpoints_raw):
        ks = norm_collapsed(sv, app_dir, shared_prefix)
        kp = norm_endpoint_path(p, canonicalize_params=True, lowercase=True)
        if ks and kp:
            ep_groups.setdefault((ks, kp), []).append(f"{sv}:{p}")

    comp_collisions = {k: vals for k, vals in sorted(comp_groups.items()) if len(vals) > 1}
    conn_collisions = {f"{k[0]} -> {k[1]}": vals for k, vals in sorted(conn_groups.items()) if len(vals) > 1}
    if conn_self_loops:
        conn_collisions["<self-loop>"] = conn_self_loops
    ep_collisions = {f"{k[0]}:{k[1]}": vals for k, vals in sorted(ep_groups.items()) if len(vals) > 1}

    return {
        "raw": {
            "components": len(gt_comps_raw),
            "connections": len(gt_conns_raw),
            "endpoints": len(gt_endpoints_raw),
        },
        "strict": {
            "components": len(gt_comps_strict),
            "connections": len(gt_conns_strict),
            "endpoints": len(gt_eps_strict),
        },
        "canonical": {
            "components": len(gt_comps_canon),
            "connections": len(gt_conns_canon),
            "endpoints": len(gt_eps_canon),
        },
        "collapsed": {
            "components": len(comp_groups),
            "connections": len(conn_groups),
            "endpoints": len(ep_groups),
        },
        "collapsed_collisions": {
            "components": comp_collisions,
            "connections": conn_collisions,
            "endpoints": ep_collisions,
        },
    }


def summarize_groundtruth_corpus(gt_dir: Path, manifest_path: Path | None = None) -> dict[str, Any]:
    """Audit raw vs collapsed GT denominators across all groundtruth_textual files."""
    if manifest_path is not None and manifest_path.is_file():
        manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
        entries = [(a["id"], a["dir"]) for a in manifest["suites"]["microsecend_17"]]
    else:
        entries = [(p.stem, p.stem) for p in sorted(gt_dir.glob("*.txt"))]

    totals = {
        tier: {"components": 0, "connections": 0, "endpoints": 0}
        for tier in ("raw", "strict", "canonical", "collapsed")
    }
    apps_with_collisions: list[str] = []
    per_app: list[dict[str, Any]] = []
    gt_by_lower = {p.stem.lower(): p for p in gt_dir.glob("*.txt")}

    for app_id, app_dir in entries:
        gt_path = gt_by_lower.get(app_dir.lower())
        if gt_path is None:
            raise FileNotFoundError(f"Missing groundtruth file for {app_dir} under {gt_dir}")
        gt_comps, gt_conns, gt_eps = parse_groundtruth_txt(gt_path)
        den = detect_collapsed_collisions(gt_comps, gt_conns, gt_eps, app_dir=app_dir, shared_prefix="")
        for tier in ("raw", "strict", "canonical", "collapsed"):
            for kind in ("components", "connections", "endpoints"):
                totals[tier][kind] += den[tier][kind]
        has_col = any(bool(den["collapsed_collisions"][k]) for k in ("components", "connections", "endpoints"))
        if has_col:
            apps_with_collisions.append(app_id)
        per_app.append({"app_id": app_id, "dir": app_dir, **den})

    return {
        "apps": len(per_app),
        **totals,
        "dropped_by_collapse": {
            kind: totals["raw"][kind] - totals["collapsed"][kind]
            for kind in ("components", "connections", "endpoints")
        },
        "apps_with_collisions": apps_with_collisions,
        "per_app": per_app,
    }


def score_app_predictions(
    gt_comps_raw: set[str],
    gt_conns_raw: set[tuple[str, str]],
    gt_endpoints_raw: set[tuple[str, str]],
    pred_names: list[str],
    pred_edges: list[tuple[str, str]],
    pred_endpoints: list[tuple[str, str]],
    app_dir: str = "",
    shared_prefix: str = "",
) -> dict[str, Any]:
    """Score predictions against GT across Raw-GT (collision-safe), Strict, Canonical, and Collapsed tiers."""
    gt_comps_strict = {norm_strict(c) for c in gt_comps_raw if norm_strict(c)}
    gt_conns_strict = {
        (norm_strict(a), norm_strict(b))
        for a, b in gt_conns_raw
        if norm_strict(a) and norm_strict(b) and norm_strict(a) != norm_strict(b)
    }
    gt_eps_strict = {
        (norm_strict(sv), norm_endpoint_path(p, canonicalize_params=False, lowercase=False))
        for sv, p in gt_endpoints_raw
        if norm_strict(sv) and norm_endpoint_path(p, canonicalize_params=False, lowercase=False)
    }

    gt_comps_canon = {
        norm_canonical(c, app_dir, shared_prefix)
        for c in gt_comps_raw
        if norm_canonical(c, app_dir, shared_prefix)
    }
    gt_conns_canon = {
        (norm_canonical(a, app_dir, shared_prefix), norm_canonical(b, app_dir, shared_prefix))
        for a, b in gt_conns_raw
        if norm_canonical(a, app_dir, shared_prefix)
        and norm_canonical(b, app_dir, shared_prefix)
        and norm_canonical(a, app_dir, shared_prefix) != norm_canonical(b, app_dir, shared_prefix)
    }
    gt_eps_canon = {
        (norm_canonical(sv, app_dir, shared_prefix), norm_endpoint_path(p, canonicalize_params=True, lowercase=False))
        for sv, p in gt_endpoints_raw
        if norm_canonical(sv, app_dir, shared_prefix)
        and norm_endpoint_path(p, canonicalize_params=True, lowercase=False)
    }

    gt_comps_col = {
        norm_collapsed(c, app_dir, shared_prefix)
        for c in gt_comps_raw
        if norm_collapsed(c, app_dir, shared_prefix)
    }
    gt_conns_col = {
        (norm_collapsed(a, app_dir, shared_prefix), norm_collapsed(b, app_dir, shared_prefix))
        for a, b in gt_conns_raw
        if norm_collapsed(a, app_dir, shared_prefix)
        and norm_collapsed(b, app_dir, shared_prefix)
        and norm_collapsed(a, app_dir, shared_prefix) != norm_collapsed(b, app_dir, shared_prefix)
    }
    gt_eps_col = {
        (norm_collapsed(sv, app_dir, shared_prefix), norm_endpoint_path(p, canonicalize_params=True, lowercase=True))
        for sv, p in gt_endpoints_raw
        if norm_collapsed(sv, app_dir, shared_prefix)
        and norm_endpoint_path(p, canonicalize_params=True, lowercase=True)
    }

    pred_c_strict = {norm_strict(x) for x in pred_names if norm_strict(x)}
    pred_e_strict = {
        (norm_strict(a), norm_strict(b))
        for a, b in pred_edges
        if norm_strict(a) and norm_strict(b) and norm_strict(a) != norm_strict(b)
    }
    pred_ep_strict = {
        (norm_strict(sv), norm_endpoint_path(p, canonicalize_params=False, lowercase=False))
        for sv, p in pred_endpoints
        if norm_strict(sv) and norm_endpoint_path(p, canonicalize_params=False, lowercase=False)
    }

    pred_c_canon = {
        norm_canonical(x, app_dir, shared_prefix)
        for x in pred_names
        if norm_canonical(x, app_dir, shared_prefix)
    }
    pred_e_canon = {
        (norm_canonical(a, app_dir, shared_prefix), norm_canonical(b, app_dir, shared_prefix))
        for a, b in pred_edges
        if norm_canonical(a, app_dir, shared_prefix)
        and norm_canonical(b, app_dir, shared_prefix)
        and norm_canonical(a, app_dir, shared_prefix) != norm_canonical(b, app_dir, shared_prefix)
    }
    pred_ep_canon = {
        (norm_canonical(sv, app_dir, shared_prefix), norm_endpoint_path(p, canonicalize_params=True, lowercase=False))
        for sv, p in pred_endpoints
        if norm_canonical(sv, app_dir, shared_prefix)
        and norm_endpoint_path(p, canonicalize_params=True, lowercase=False)
    }

    pred_c_col = {
        norm_collapsed(x, app_dir, shared_prefix)
        for x in pred_names
        if norm_collapsed(x, app_dir, shared_prefix)
    }
    pred_e_col = {
        (norm_collapsed(a, app_dir, shared_prefix), norm_collapsed(b, app_dir, shared_prefix))
        for a, b in pred_edges
        if norm_collapsed(a, app_dir, shared_prefix)
        and norm_collapsed(b, app_dir, shared_prefix)
        and norm_collapsed(a, app_dir, shared_prefix) != norm_collapsed(b, app_dir, shared_prefix)
    }
    pred_ep_col = {
        (norm_collapsed(sv, app_dir, shared_prefix), norm_endpoint_path(p, canonicalize_params=True, lowercase=True))
        for sv, p in pred_endpoints
        if norm_collapsed(sv, app_dir, shared_prefix)
        and norm_endpoint_path(p, canonicalize_params=True, lowercase=True)
    }

    raw_gt_metrics = {
        "components": compute_prf_raw_gt(
            pred_c_strict,
            gt_comps_raw,
            strict_key_fn=norm_strict,
            collapsed_key_fn=lambda x: norm_collapsed(x, app_dir, shared_prefix),
        ),
        "connections": compute_prf_raw_gt(
            pred_e_strict,
            gt_conns_raw,
            strict_key_fn=lambda e: (norm_strict(e[0]), norm_strict(e[1]))
            if norm_strict(e[0]) and norm_strict(e[1]) and norm_strict(e[0]) != norm_strict(e[1])
            else None,
            collapsed_key_fn=lambda e: (
                norm_collapsed(e[0], app_dir, shared_prefix),
                norm_collapsed(e[1], app_dir, shared_prefix),
            ),
        ),
        "endpoints": compute_prf_raw_gt(
            pred_ep_strict,
            gt_endpoints_raw,
            strict_key_fn=lambda ep: (
                norm_strict(ep[0]),
                norm_endpoint_path(ep[1], canonicalize_params=False, lowercase=False),
            )
            if norm_strict(ep[0]) and norm_endpoint_path(ep[1], canonicalize_params=False, lowercase=False)
            else None,
            collapsed_key_fn=lambda ep: (
                norm_collapsed(ep[0], app_dir, shared_prefix),
                norm_endpoint_path(ep[1], canonicalize_params=True, lowercase=True),
            ),
        ),
    }

    gt_denominators = detect_collapsed_collisions(
        gt_comps_raw, gt_conns_raw, gt_endpoints_raw, app_dir=app_dir, shared_prefix=shared_prefix
    )

    return {
        "gt_denominators": gt_denominators,
        "pred_components_raw": sorted(pred_c_strict),
        "pred_components_canon": sorted(pred_c_canon),
        "pred_connections_canon": sorted(f"{a} -> {b}" for a, b in pred_e_canon),
        "pred_endpoints_raw": sorted(f"{sv}:{p}" for sv, p in pred_ep_strict),
        "pred_endpoints_canon": sorted(f"{sv}:{p}" for sv, p in pred_ep_canon),
        "raw_gt_metrics": raw_gt_metrics,
        "strict_metrics": {
            "components": compute_prf(pred_c_strict, gt_comps_strict),
            "connections": compute_prf(pred_e_strict, gt_conns_strict),
            "endpoints": compute_prf(pred_ep_strict, gt_eps_strict),
        },
        "canon_metrics": {
            "components": compute_prf(pred_c_canon, gt_comps_canon),
            "connections": compute_prf(pred_e_canon, gt_conns_canon),
            "endpoints": compute_prf(pred_ep_canon, gt_eps_canon),
        },
        "collapsed_metrics": {
            "components": compute_prf(pred_c_col, gt_comps_col),
            "connections": compute_prf(pred_e_col, gt_conns_col),
            "endpoints": compute_prf(pred_ep_col, gt_eps_col),
        },
        "_sets": {
            "gt_c_strict": gt_comps_strict, "pr_c_strict": pred_c_strict,
            "gt_e_strict": gt_conns_strict, "pr_e_strict": pred_e_strict,
            "gt_ep_strict": gt_eps_strict, "pr_ep_strict": pred_ep_strict,
            "gt_c_canon": gt_comps_canon, "pr_c_canon": pred_c_canon,
            "gt_e_canon": gt_conns_canon, "pr_e_canon": pred_e_canon,
            "gt_ep_canon": gt_eps_canon, "pr_ep_canon": pred_ep_canon,
            "gt_c_col": gt_comps_col, "pr_c_col": pred_c_col,
            "gt_e_col": gt_conns_col, "pr_e_col": pred_e_col,
            "gt_ep_col": gt_eps_col, "pr_ep_col": pred_ep_col,
        },
    }


def _is_emse_edge_line(line: str) -> bool:
    """Detect connection lines in EMSE2025SAR processed_results (e.g. 'a -> b', 'a - b', 'consul- consul')."""
    return bool(EMSE_EDGE_SEP_RE.search(line))


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
        endp_all = {"TP": 0, "FP": 0, "FN": 0}
        comp_macro: list[tuple[float, float, float]] = []
        conn_macro: list[tuple[float, float, float]] = []
        endp_macro: list[tuple[float, float, float]] = []
        files = sorted(tool_dir.glob("*.txt"))
        for f in files:
            c1 = {"TP": 0, "FP": 0, "FN": 0}
            c2 = {"TP": 0, "FP": 0, "FN": 0}
            c3 = {"TP": 0, "FP": 0, "FN": 0}
            sec: str | None = None
            in_fp_block = False
            for raw in f.read_text(encoding="utf-8", errors="replace").splitlines():
                s = raw.strip()
                if not s:
                    continue
                if s == "Components:":
                    sec = "COMP"
                    in_fp_block = False
                    continue
                if s == "Connections:":
                    sec = "CONN"
                    in_fp_block = False
                    continue
                if s == "Endpoints:":
                    sec = "ENDP"
                    in_fp_block = False
                    continue
                if s in ("FP:", "FP"):
                    in_fp_block = True
                    continue
                if in_fp_block:
                    m_ep = EMSE_COUNT_TAG_RE.match(s)
                    if m_ep:
                        tag, count = m_ep.group(1), int(m_ep.group(2))
                        c3[tag] += count
                        endp_all[tag] += count
                        continue
                    entry = s[3:].strip() if s.startswith("FP ") else s
                    if _is_emse_edge_line(entry):
                        c2["FP"] += 1
                        conn_all["FP"] += 1
                    else:
                        c1["FP"] += 1
                        comp_all["FP"] += 1
                    continue
                if sec == "ENDP":
                    m_ep = EMSE_COUNT_TAG_RE.match(s)
                    if m_ep:
                        tag, count = m_ep.group(1), int(m_ep.group(2))
                        c3[tag] += count
                        endp_all[tag] += count
                        continue
                    parts = s.split(" ", 1)
                    tag = parts[0]
                    rest = parts[1].strip() if len(parts) > 1 else ""
                    if tag in ("TP", "FP", "FN"):
                        if rest and _is_emse_edge_line(rest) and ":" not in rest:
                            c2[tag] += 1
                            conn_all[tag] += 1
                        else:
                            quoted = re.findall(r'"([^"]*)"', rest)
                            count = len(quoted) if quoted else 1
                            c3[tag] += count
                            endp_all[tag] += count
                    continue
                if sec is None:
                    continue
                tag = s.split(" ", 1)[0]
                if tag in ("TP", "FP", "FN"):
                    if sec == "COMP":
                        c1[tag] += 1
                        comp_all[tag] += 1
                    elif sec == "CONN":
                        c2[tag] += 1
                        conn_all[tag] += 1
            for bucket, macro_list in ((c1, comp_macro), (c2, conn_macro), (c3, endp_macro)):
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
            "endpoints": summarize(endp_all, endp_macro),
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


def _is_test_source_path(rel_path: str) -> bool:
    norm = rel_path.replace("\\", "/").lstrip("./")
    return bool(norm and TEST_PATH_RE.search(norm))


def extract_endpoints_from_items(
    endpoint_records: list[tuple[dict[str, Any], dict[str, Any]]],
    resolve_path_fn: Callable[[str], str],
    module_id_to_path: dict[str, str],
    single_component_fallback: str = "",
    evidence_fetcher: Callable[[str], dict[str, Any]] | None = None,
) -> list[dict[str, Any]]:
    """Extract normalized (service, path) endpoint descriptors from Evo Graph Query API items.

    Supports both Java semantic ENDPOINT nodes (spring.endpoint-declaration,
    jaxrs.endpoint-declaration, servlet.endpoint-declaration) across analysis contexts
    and polyglot ROUTE_CANDIDATE nodes in the repository snapshot context.
    Filters out TEST/GENERATED_TEST source sets, DEPENDENCY origins, and test file paths.
    """
    seen: set[tuple[str, str]] = set()
    extracted: list[dict[str, Any]] = []

    for ent, ctx_meta in endpoint_records:
        source_set = str(ent.get("sourceSet") or ctx_meta.get("sourceSet") or "").upper()
        if source_set in ("TEST", "GENERATED_TEST"):
            continue
        origin = str(ent.get("origin") or "").upper()
        if origin == "DEPENDENCY":
            continue

        kind = str(ent.get("kind") or "")
        cname = str(ent.get("canonicalName") or "")
        spelling = str(ent.get("spelling") or "")
        mod = str(ent.get("module") or "")
        ctx_module_path = str(ctx_meta.get("modulePath") or "")

        source_file = ""
        route_raw = ""
        methods_raw = ""
        ev_family = ""

        # Handle ROUTE_CANDIDATE "<file>#<METHOD> <path>" format first
        if kind == "ROUTE_CANDIDATE" and "#" in cname:
            left, right = cname.split("#", 1)
            source_file = left.strip()
            route_raw = right.strip()
        elif cname and not cname.startswith(("endpoint-declaration:", "java:v1:")):
            route_raw = cname.strip()
        elif spelling and not spelling.startswith(("endpoint-declaration:", "java:v1:")):
            route_raw = spelling.strip()

        if source_file and _is_test_source_path(source_file):
            continue

        # Fallback to evidence metadata when canonicalName is unavailable (e.g. JAX-RS/Servlet)
        # or when module/route info must be extracted from spring/jaxrs/servlet.endpoint-declaration.
        need_evidence = (not route_raw) or (
            kind == "ENDPOINT" and not mod and not ctx_module_path
        )
        if need_evidence:
            inline_evs = ent.get("evidencePayloads")
            ev_candidates: list[dict[str, Any]] = list(inline_evs) if isinstance(inline_evs, list) else []
            if not ev_candidates and evidence_fetcher is not None:
                for ref in ent.get("evidence", []):
                    ev = evidence_fetcher(str(ref))
                    if ev:
                        ev_candidates.append(ev)
            for ev in ev_candidates:
                fam = str(ev.get("family") or "")
                meta = ev.get("metadata") if isinstance(ev.get("metadata"), dict) else {}
                if fam in ("spring.endpoint-declaration", "jaxrs.endpoint-declaration", "servlet.endpoint-declaration"):
                    ev_family = fam
                    ev_ss = str(meta.get("sourceSet") or "").upper()
                    if ev_ss in ("TEST", "GENERATED_TEST"):
                        route_raw = ""
                        source_set = ev_ss
                        break
                    if not mod and meta.get("module"):
                        mod = str(meta["module"])
                    route_obj = meta.get("route") if isinstance(meta.get("route"), dict) else {}
                    if route_obj.get("path"):
                        route_raw = str(route_obj["path"])
                    if isinstance(route_obj.get("methods"), list):
                        methods_raw = ",".join(str(x) for x in route_obj["methods"] if x)
                    break
            if source_set in ("TEST", "GENERATED_TEST"):
                continue

        if not route_raw:
            continue

        # Extract HTTP methods if present in label before normalizing path
        m_pref = HTTP_METHOD_PREFIX_RE.match(LANG_TAG_SUFFIX_RE.sub("", route_raw).strip())
        if m_pref and not methods_raw:
            methods_raw = route_raw.strip().split(None, 1)[0]

        # Skip non-HTTP-path ROUTE_CANDIDATE artifacts (e.g. bare GraphQL operation names)
        raw_after_method = m_pref.group(1).strip() if m_pref else LANG_TAG_SUFFIX_RE.sub("", route_raw).strip()
        raw_after_method = raw_after_method.strip('"').strip("'").strip("`").strip()
        if kind == "ROUTE_CANDIDATE" and not raw_after_method.startswith(("/", "http://", "https://", "lb://")):
            continue

        norm_path = norm_endpoint_path(route_raw, canonicalize_params=False, lowercase=False)
        if not norm_path:
            continue

        # Resolve owning service/component name
        service_name = ""
        if source_file:
            service_name = resolve_path_fn(source_file)
        if not service_name and mod:
            if mod in module_id_to_path:
                service_name = resolve_path_fn(module_id_to_path[mod])
            elif not mod.startswith("module:sha256:"):
                service_name = resolve_path_fn(mod)
        if not service_name and ctx_module_path:
            service_name = resolve_path_fn(ctx_module_path)
        if not service_name and single_component_fallback:
            service_name = single_component_fallback

        if not service_name or is_aggregator_descriptor(service_name):
            continue

        dedup_key = (norm_strict(service_name), norm_path)
        if dedup_key in seen:
            continue
        seen.add(dedup_key)
        extracted.append({
            "service": service_name,
            "path": norm_path,
            "kind": kind,
            "methods": methods_raw,
            "evidence_family": ev_family,
        })

    extracted.sort(key=lambda item: (item["service"], item["path"]))
    return extracted


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
        "population": {"nodeKinds": ["BUILD_COMPONENT", "SERVICE_COMPONENT", "INFRA_COMPONENT", "EXTERNAL_ACTOR"]},
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
        bdir = cmod if (kind in ("BUILD_COMPONENT", "SERVICE_COMPONENT") and cmod) else cname.replace("\\", "/").strip("/")
        raw_components.append({"id": cid, "kind": kind, "name": cname, "dir": bdir})
        entity_label_by_id[cid] = cname
        if kind in ("BUILD_COMPONENT", "SERVICE_COMPONENT"):
            build_dirs.append(bdir)
            if bdir and bdir != ".":
                build_dir_to_name.setdefault(bdir, cname)

    sorted_build_dirs = sorted(
        [d for d in build_dirs if d and d != "."],
        key=lambda d: len(d),
        reverse=True,
    )

    aggregator_dirs = {
        d for d in build_dirs
        if d == "." or any(other != d and other.startswith(d + "/") for other in build_dirs)
    }

    leaf_components = [
        c for c in raw_components
        if c["kind"] != "BUILD_COMPONENT"
        or c["dir"] not in aggregator_dirs
        or len(build_dirs) <= 1
    ]
    leaf_build_names = [
        c["name"] for c in leaf_components if c["kind"] in ("BUILD_COMPONENT", "SERVICE_COMPONENT")
    ]
    single_component_fallback = leaf_build_names[0] if len(leaf_build_names) == 1 else ""

    def resolve_path_to_component(rel_path: str) -> str:
        norm = rel_path.replace("\\", "/").lstrip("./")
        if not norm:
            return build_dir_to_name.get(".", single_component_fallback)
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
        if kind in ("BUILD_COMPONENT", "SERVICE_COMPONENT", "INFRA_COMPONENT", "EXTERNAL_ACTOR") and not is_aggregator_descriptor(cname):
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

    edge_items = fetch_paged(base_url, graph_id, "edges", base_req)
    entity_cache: dict[str, dict[str, Any]] = {}
    evidence_cache: dict[str, dict[str, Any]] = {}
    raw_edges: list[dict[str, str]] = []

    def fetch_evidence_cached(ref: str, ctx_id: str = snapshot_id) -> dict[str, Any]:
        ev = evidence_cache.get(ref)
        if ev is not None:
            return ev
        req = {**base_req, "context": ctx_id}
        ev_res = http_json(base_url, "POST", f"/api/v1/graphs/{graph_id}/queries/evidence", {
            "request": req,
            "arguments": {"reference": ref},
        })["data"]
        ev_items = ev_res.get("items", [])
        ev = ev_items[0] if ev_items else {}
        evidence_cache[ref] = ev
        return ev

    for ed in edge_items:
        src_name = ""
        dst_name = ""
        mechanism = ""
        for ref in ed.get("evidence", []):
            ev = fetch_evidence_cached(str(ref), snapshot_id)
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

    # Query endpoints across all non-test Java analysis contexts (ENDPOINT) and root snapshot (ENDPOINT, ROUTE_CANDIDATE)
    endpoint_records: list[tuple[dict[str, Any], dict[str, Any]]] = []
    ctx_list: list[dict[str, Any]] = []
    try:
        g_meta = http_json(base_url, "GET", f"/api/v1/graphs/{graph_id}", timeout_s=30).get("data", {})
        if isinstance(g_meta.get("contexts"), list):
            ctx_list = [c for c in g_meta["contexts"] if isinstance(c, dict)]
    except Exception:
        ctx_list = []

    ctx_id_by_ref_ctx: dict[str, str] = {}
    for ctx in ctx_list:
        cid = str(ctx.get("identity") or "")
        if not cid or cid == snapshot_id:
            continue
        fam = str(ctx.get("family") or "")
        ckind = str(ctx.get("kind") or "")
        ss = str(ctx.get("sourceSet") or "").upper()
        if ss in ("TEST", "GENERATED_TEST"):
            continue
        if fam == "analysis.manifest" or ckind == "java":
            try:
                ep_items = fetch_paged(base_url, graph_id, "entities", {
                    **base_req,
                    "context": cid,
                    "population": {"nodeKinds": ["ENDPOINT"]},
                })
                for item in ep_items:
                    endpoint_records.append((item, ctx))
                    for ref in item.get("evidence", []):
                        ctx_id_by_ref_ctx.setdefault(str(ref), cid)
            except Exception:
                continue

    try:
        root_ep_items = fetch_paged(base_url, graph_id, "entities", {
            **base_req,
            "context": snapshot_id,
            "population": {"nodeKinds": ["ENDPOINT", "ROUTE_CANDIDATE"]},
        })
        root_ctx_meta = {"identity": snapshot_id, "kind": "repository"}
        for item in root_ep_items:
            endpoint_records.append((item, root_ctx_meta))
            for ref in item.get("evidence", []):
                ctx_id_by_ref_ctx.setdefault(str(ref), snapshot_id)
    except Exception:
        pass

    raw_endpoints = extract_endpoints_from_items(
        endpoint_records=endpoint_records,
        resolve_path_fn=resolve_path_to_component,
        module_id_to_path=module_id_to_path,
        single_component_fallback=single_component_fallback,
        evidence_fetcher=lambda ref: fetch_evidence_cached(ref, ctx_id_by_ref_ctx.get(ref, snapshot_id)),
    )

    shared_prefix = compute_shared_module_prefix([
        norm_strict(c["name"]) for c in leaf_components if c["kind"] in ("BUILD_COMPONENT", "SERVICE_COMPONENT")
    ])
    return {
        "raw_components": raw_components,
        "leaf_components": leaf_components,
        "raw_edges": raw_edges,
        "raw_endpoints": raw_endpoints,
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
    gt_comps_raw, gt_conns_raw, gt_endpoints_raw = parse_groundtruth_txt(gt_matches[0])

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
        "gt_endpoints": len(gt_endpoints_raw),
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

            pred_names = [c["name"] for c in topo["leaf_components"]]
            pred_edges = [(e["source"], e["target"]) for e in topo["raw_edges"]]
            pred_endpoints = [(ep["service"], ep["path"]) for ep in topo.get("raw_endpoints", [])]

            scored = score_app_predictions(
                gt_comps_raw=gt_comps_raw,
                gt_conns_raw=gt_conns_raw,
                gt_endpoints_raw=gt_endpoints_raw,
                pred_names=pred_names,
                pred_edges=pred_edges,
                pred_endpoints=pred_endpoints,
                app_dir=app_dir,
                shared_prefix=shared_prefix,
            )
            row.update(scored)
            return row
    except Exception as ex:
        row["status"] = "ERROR"
        row["error"] = str(ex)[:400]

    scored_empty = score_app_predictions(
        gt_comps_raw=gt_comps_raw,
        gt_conns_raw=gt_conns_raw,
        gt_endpoints_raw=gt_endpoints_raw,
        pred_names=[],
        pred_edges=[],
        pred_endpoints=[],
        app_dir=app_dir,
        shared_prefix="",
    )
    row.update(scored_empty)
    return row


def serialize_sets(sets_dict: dict[str, set[Any]]) -> dict[str, list[Any]]:
    out: dict[str, list[Any]] = {}
    for k, v in sets_dict.items():
        if "_e_" in k or "_ep_" in k:
            out[k] = sorted([list(p) for p in v])
        else:
            out[k] = sorted(v)
    return out


def deserialize_sets(sets_dict: dict[str, list[Any]]) -> dict[str, set[Any]]:
    out: dict[str, set[Any]] = {}
    for k, v in sets_dict.items():
        if "_e_" in k or "_ep_" in k:
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


def aggregate_gt_denominators(rows: list[dict[str, Any]]) -> dict[str, Any]:
    """Aggregate Raw vs Collapsed GT denominators and collision diagnostics across rows."""
    totals = {
        tier: {"components": 0, "connections": 0, "endpoints": 0}
        for tier in ("raw", "strict", "canonical", "collapsed")
    }
    apps_with_collisions: list[str] = []
    for r in rows:
        den = r.get("gt_denominators")
        if isinstance(den, dict):
            for tier in ("raw", "strict", "canonical", "collapsed"):
                for kind in ("components", "connections", "endpoints"):
                    totals[tier][kind] += int(den.get(tier, {}).get(kind, 0))
            cols = den.get("collapsed_collisions", {})
            if any(bool(cols.get(k)) for k in ("components", "connections", "endpoints")):
                apps_with_collisions.append(str(r.get("app_id", "")))
        else:
            totals["raw"]["components"] += int(r.get("gt_components", 0))
            totals["raw"]["connections"] += int(r.get("gt_connections", 0))
            totals["raw"]["endpoints"] += int(r.get("gt_endpoints", 0))
    return {
        **totals,
        "dropped_by_collapse": {
            kind: totals["raw"][kind] - totals["collapsed"][kind]
            for kind in ("components", "connections", "endpoints")
        },
        "apps_with_collisions": apps_with_collisions,
    }


def render_markdown_report(summary: dict[str, Any]) -> str:
    """Render side-by-side Raw vs Collapsed GT denominator + Endpoint + EMSE baseline Markdown report."""
    lines: list[str] = [
        "# E6 17-App microSecEnD Online Benchmark Summary",
        "",
        f"- **Timestamp (UTC):** `{summary.get('timestamp_utc', '')}`",
        f"- **Applications Evaluated:** `{summary.get('apps_count', 0)}`",
    ]
    gt_den = summary.get("gt_denominators") or {}
    if gt_den:
        raw_d = gt_den.get("raw", {})
        col_d = gt_den.get("collapsed", {})
        drop_d = gt_den.get("dropped_by_collapse", {})
        coll_apps = gt_den.get("apps_with_collisions", [])
        lines.extend([
            f"- **Ground-Truth Denominators (Raw vs Collapsed):** "
            f"Raw `{raw_d.get('components', 0)} / {raw_d.get('connections', 0)} / {raw_d.get('endpoints', 0)}` "
            f"(COMP / CONN / ENDP) vs Collapsed `{col_d.get('components', 0)} / {col_d.get('connections', 0)} / {col_d.get('endpoints', 0)}` "
            f"(dropped by `norm_collapsed`: `{drop_d.get('components', 0)}` COMP, `{drop_d.get('connections', 0)}` CONN, `{drop_d.get('endpoints', 0)}` ENDP)",
            f"- **Apps with Collapsed GT Collisions:** `{', '.join(coll_apps) if coll_apps else 'none'}`",
        ])

    lines.extend([
        "",
        "## Aggregate Evo Profiles (Raw GT Denominator vs Collapsed GT Denominator)",
        "",
        "| Profile | Tier (GT Denom.) | COMP TP/FP/FN (uF1 / mF1) | CONN TP/FP/FN (uF1 / mF1) | ENDP TP/FP/FN (uF1 / mF1) |",
        "| :--- | :--- | ---: | ---: | ---: |",
    ])
    for p_name, s in (summary.get("profiles") or {}).items():
        for label, key in (
            ("Raw GT (collision-safe)", "raw_gt_denominator"),
            ("Strict (exact)", "strict_normalized"),
            ("Canonical", "canonical_normalized"),
            ("Collapsed (`norm_collapsed`)", "collapsed_canonical"),
        ):
            block = s.get(key)
            if not isinstance(block, dict):
                continue
            cells = []
            for kind in ("components", "connections", "endpoints"):
                m = block.get(kind, {})
                mi = m.get("micro", {})
                ma = m.get("macro", {})
                cells.append(
                    f"`{mi.get('tp', 0)}/{mi.get('fp', 0)}/{mi.get('fn', 0)}` "
                    f"({mi.get('f1', 0.0):.4f} / {ma.get('f1', 0.0):.4f})"
                )
            lines.append(f"| `Evo[{p_name}]` | {label} | {cells[0]} | {cells[1]} | {cells[2]} |")

    baselines = summary.get("emse2025sar_baselines") or []
    if baselines:
        lines.extend([
            "",
            "## EMSE2025SAR Replication Baselines (Verified `FP:`/`FP` & `Endpoints:` Parser)",
            "",
            "| Tool | Apps | COMP TP/FP/FN (uF1) | CONN TP/FP/FN (uF1) | ENDP TP/FP/FN (uF1) |",
            "| :--- | ---: | ---: | ---: | ---: |",
        ])
        for b in baselines:
            c = b.get("components", {})
            e = b.get("connections", {})
            ep = b.get("endpoints", {})
            c_str = f"`{c.get('tp', 0)}/{c.get('fp', 0)}/{c.get('fn', 0)}` ({c.get('micro_f1', 0.0):.4f})" if (c.get("tp", 0) + c.get("fp", 0) + c.get("fn", 0)) > 0 else "—"
            e_str = f"`{e.get('tp', 0)}/{e.get('fp', 0)}/{e.get('fn', 0)}` ({e.get('micro_f1', 0.0):.4f})" if (e.get("tp", 0) + e.get("fp", 0) + e.get("fn", 0)) > 0 else "—"
            ep_str = f"`{ep.get('tp', 0)}/{ep.get('fp', 0)}/{ep.get('fn', 0)}` ({ep.get('micro_f1', 0.0):.4f})" if (ep.get("tp", 0) + ep.get("fp", 0) + ep.get("fn", 0)) > 0 else "—"
            lines.append(f"| `{b.get('tool', '')}` | {b.get('apps_completed', 0)}/17 | {c_str} | {e_str} | {ep_str} |")

    for p_name, rows in (summary.get("runs") or {}).items():
        if not rows:
            continue
        lines.extend([
            "",
            f"## Per-App Breakdown: `Evo[{p_name}]` (Raw GT vs Collapsed GT + Endpoints)",
            "",
            "| # | App | GT Raw (C/E/EP) | GT Col (C/E/EP) | Raw COMP F1 | Col COMP F1 | Raw CONN F1 | Col CONN F1 | Raw ENDP F1 | Col ENDP F1 |",
            "| ---: | :--- | :---: | :---: | ---: | ---: | ---: | ---: | ---: | ---: |",
        ])
        for idx, r in enumerate(rows, 1):
            den = r.get("gt_denominators") or {}
            rd = den.get("raw", {"components": r.get("gt_components", 0), "connections": r.get("gt_connections", 0), "endpoints": r.get("gt_endpoints", 0)})
            cd = den.get("collapsed", rd)
            rm = r.get("raw_gt_metrics") or r.get("strict_metrics") or {}
            cm = r.get("collapsed_metrics") or {}
            lines.append(
                f"| {idx} | `{r.get('app_id', '')}` | "
                f"`{rd.get('components', 0)}/{rd.get('connections', 0)}/{rd.get('endpoints', 0)}` | "
                f"`{cd.get('components', 0)}/{cd.get('connections', 0)}/{cd.get('endpoints', 0)}` | "
                f"{rm.get('components', {}).get('f1', 0.0):.4f} | {cm.get('components', {}).get('f1', 0.0):.4f} | "
                f"{rm.get('connections', {}).get('f1', 0.0):.4f} | {cm.get('connections', {}).get('f1', 0.0):.4f} | "
                f"{rm.get('endpoints', {}).get('f1', 0.0):.4f} | {cm.get('endpoints', {}).get('f1', 0.0):.4f} |"
            )

    return "\n".join(lines) + "\n"


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
            ep_m = cm.get("endpoints", {"tp": 0, "fp": 0, "fn": 0, "f1": 0.0})
            print(
                f"[{profile_label}] ({idx:2d}/{total}) {app_id:48s} CACHED status={row['status']} "
                f"cold={row['cold_elapsed_ms']:.0f}ms warm={row['warm_elapsed_ms']:.0f}ms steady={row['steady_state_sha256_match']} "
                f"| COMP(TP={cm['components']['tp']},FP={cm['components']['fp']},FN={cm['components']['fn']},F1={cm['components']['f1']:.3f}) "
                f"| CONN(TP={cm['connections']['tp']},FP={cm['connections']['fp']},FN={cm['connections']['fn']},F1={cm['connections']['f1']:.3f}) "
                f"| ENDP(TP={ep_m['tp']},FP={ep_m['fp']},FN={ep_m['fn']},F1={ep_m['f1']:.3f})",
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
        rm = row.get("raw_gt_metrics", row["collapsed_metrics"])
        cm = row["collapsed_metrics"]
        ep_r = rm.get("endpoints", {"tp": 0, "fp": 0, "fn": 0, "f1": 0.0})
        err_suffix = f" err={row['error'][:80]}" if row.get("error") else ""
        print(
            f"[{profile_label}] ({idx:2d}/{total}) {app_id:48s} status={row['status']} "
            f"cold={row['cold_elapsed_ms']:.0f}ms warm={row['warm_elapsed_ms']:.0f}ms "
            f"cold==w1={row['cold_vs_warm1_sha256_match']} steady={row['steady_state_sha256_match']} pref='{row['shared_prefix']}' "
            f"| COMP[raw/col]={rm['components']['f1']:.3f}/{cm['components']['f1']:.3f} "
            f"| CONN[raw/col]={rm['connections']['f1']:.3f}/{cm['connections']['f1']:.3f} "
            f"| ENDP(TP={ep_r['tp']},FP={ep_r['fp']},FN={ep_r['fn']},F1={ep_r['f1']:.3f}){err_suffix}",
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
    top_gt_denominators: dict[str, Any] = {}
    for profile_label, rows in results_by_profile.items():
        if not rows:
            continue
        c_raw = aggregate_rows(rows, "raw_gt_metrics", "components")
        e_raw = aggregate_rows(rows, "raw_gt_metrics", "connections")
        ep_raw = aggregate_rows(rows, "raw_gt_metrics", "endpoints")
        c_strict = aggregate_rows(rows, "strict_metrics", "components")
        e_strict = aggregate_rows(rows, "strict_metrics", "connections")
        ep_strict = aggregate_rows(rows, "strict_metrics", "endpoints")
        c_canon = aggregate_rows(rows, "canon_metrics", "components")
        e_canon = aggregate_rows(rows, "canon_metrics", "connections")
        ep_canon = aggregate_rows(rows, "canon_metrics", "endpoints")
        c_col = aggregate_rows(rows, "collapsed_metrics", "components")
        e_col = aggregate_rows(rows, "collapsed_metrics", "connections")
        ep_col = aggregate_rows(rows, "collapsed_metrics", "endpoints")
        prof_gt_den = aggregate_gt_denominators(rows)
        if not top_gt_denominators:
            top_gt_denominators = prof_gt_den
        summary_profiles[profile_label] = {
            "apps_total": len(rows),
            "apps_analyzed_with_graph": sum(1 for r in rows if r.get("cold_graph")),
            "cold_vs_warm1_sha256_passes": sum(1 for r in rows if r.get("cold_vs_warm1_sha256_match")),
            "steady_state_sha256_passes": sum(1 for r in rows if r.get("steady_state_sha256_match")),
            "cold_elapsed_ms": compute_median_iqr([r["cold_elapsed_ms"] for r in rows if r["cold_elapsed_ms"] > 0]),
            "warm_elapsed_ms": compute_median_iqr([r["warm_elapsed_ms"] for r in rows if r["warm_elapsed_ms"] > 0]),
            "gt_denominators": prof_gt_den,
            "raw_gt_denominator": {"components": c_raw, "connections": e_raw, "endpoints": ep_raw},
            "strict_normalized": {"components": c_strict, "connections": e_strict, "endpoints": ep_strict},
            "canonical_normalized": {"components": c_canon, "connections": e_canon, "endpoints": ep_canon},
            "collapsed_canonical": {"components": c_col, "connections": e_col, "endpoints": ep_col},
        }
        for r in rows:
            r.pop("_sets", None)

    summary = {
        "timestamp_utc": dt.datetime.now(dt.timezone.utc).isoformat(),
        "apps_count": max((len(v) for v in results_by_profile.values()), default=0),
        "gt_denominators": top_gt_denominators,
        "profiles": summary_profiles,
        "emse2025sar_baselines": baselines,
        "runs": results_by_profile,
    }
    (args.out_dir / "e6_summary.json").write_text(json.dumps(summary, indent=2), encoding="utf-8")
    (args.out_dir / "e6_summary.md").write_text(render_markdown_report(summary), encoding="utf-8")

    with (args.out_dir / "e6_per_app.csv").open("w", newline="", encoding="utf-8") as fh:
        w = csv.writer(fh)
        w.writerow([
            "profile", "app_id", "status", "completion", "cold_elapsed_ms", "warm_elapsed_ms",
            "cold_vs_warm1_sha256", "steady_state_sha256", "gap_count", "warm1_gap_count",
            "gt_comp_raw", "gt_comp_col", "gt_conn_raw", "gt_conn_col", "gt_endp_raw", "gt_endp_col",
            "comp_tp", "comp_fp", "comp_fn", "comp_p", "comp_r", "comp_f1",
            "conn_tp", "conn_fp", "conn_fn", "conn_p", "conn_r", "conn_f1",
            "endp_tp", "endp_fp", "endp_fn", "endp_p", "endp_r", "endp_f1",
            "raw_comp_tp", "raw_comp_fp", "raw_comp_fn", "raw_comp_f1",
            "raw_conn_tp", "raw_conn_fp", "raw_conn_fn", "raw_conn_f1",
            "raw_endp_tp", "raw_endp_fp", "raw_endp_fn", "raw_endp_f1",
        ])
        for profile_label, rows in results_by_profile.items():
            for r in rows:
                den = r.get("gt_denominators") or {}
                rd = den.get("raw", {"components": r.get("gt_components", 0), "connections": r.get("gt_connections", 0), "endpoints": r.get("gt_endpoints", 0)})
                cd = den.get("collapsed", rd)
                mc = r["canon_metrics"]["components"]
                me = r["canon_metrics"]["connections"]
                mep = r["canon_metrics"].get("endpoints", {"tp": 0, "fp": 0, "fn": 0, "precision": 0.0, "recall": 0.0, "f1": 0.0})
                rm = r.get("raw_gt_metrics") or r["strict_metrics"]
                rc = rm["components"]
                re_m = rm["connections"]
                rep_m = rm.get("endpoints", {"tp": 0, "fp": 0, "fn": 0, "f1": 0.0})
                w.writerow([
                    profile_label, r["app_id"], r["status"], r.get("completion", ""),
                    r["cold_elapsed_ms"], r["warm_elapsed_ms"],
                    r["cold_vs_warm1_sha256_match"], r["steady_state_sha256_match"],
                    r["gap_count"], r["warm1_gap_count"],
                    rd.get("components", 0), cd.get("components", 0),
                    rd.get("connections", 0), cd.get("connections", 0),
                    rd.get("endpoints", 0), cd.get("endpoints", 0),
                    mc["tp"], mc["fp"], mc["fn"], mc["precision"], mc["recall"], mc["f1"],
                    me["tp"], me["fp"], me["fn"], me["precision"], me["recall"], me["f1"],
                    mep["tp"], mep["fp"], mep["fn"], mep["precision"], mep["recall"], mep["f1"],
                    rc["tp"], rc["fp"], rc["fn"], rc["f1"],
                    re_m["tp"], re_m["fp"], re_m["fn"], re_m["f1"],
                    rep_m["tp"], rep_m["fp"], rep_m["fn"], rep_m["f1"],
                ])

    print("\n=== AGGREGATE SUMMARY (Raw GT Denominator vs Delimiter-Collapsed Canonical) ===")
    for p_name, s in summary_profiles.items():
        rc = s["raw_gt_denominator"]["components"]
        re_m = s["raw_gt_denominator"]["connections"]
        rep_m = s["raw_gt_denominator"]["endpoints"]
        cc = s["canonical_normalized"]["components"]
        ce = s["canonical_normalized"]["connections"]
        cep = s["canonical_normalized"]["endpoints"]
        lc = s["collapsed_canonical"]["components"]
        le = s["collapsed_canonical"]["connections"]
        lep = s["collapsed_canonical"]["endpoints"]
        print(
            f"Evo[{p_name}] apps={s['apps_analyzed_with_graph']}/{s['apps_total']} "
            f"cold==w1={s['cold_vs_warm1_sha256_passes']}/{s['apps_total']} steady={s['steady_state_sha256_passes']}/{s['apps_total']} "
            f"coldMed={s['cold_elapsed_ms']['median']}±{s['cold_elapsed_ms']['iqr']}ms "
            f"warmMed={s['warm_elapsed_ms']['median']}±{s['warm_elapsed_ms']['iqr']}ms\n"
            f"  [Raw-GT   ] COMP uF1={rc['micro']['f1']:.4f} mF1={rc['macro']['f1']:.4f} (TP={rc['micro']['tp']},FP={rc['micro']['fp']},FN={rc['micro']['fn']}) | "
            f"CONN uF1={re_m['micro']['f1']:.4f} mF1={re_m['macro']['f1']:.4f} (TP={re_m['micro']['tp']},FP={re_m['micro']['fp']},FN={re_m['micro']['fn']}) | "
            f"ENDP uF1={rep_m['micro']['f1']:.4f} mF1={rep_m['macro']['f1']:.4f} (TP={rep_m['micro']['tp']},FP={rep_m['micro']['fp']},FN={rep_m['micro']['fn']})\n"
            f"  [Canonical] COMP uF1={cc['micro']['f1']:.4f} mF1={cc['macro']['f1']:.4f} (TP={cc['micro']['tp']},FP={cc['micro']['fp']},FN={cc['micro']['fn']}) | "
            f"CONN uF1={ce['micro']['f1']:.4f} mF1={ce['macro']['f1']:.4f} (TP={ce['micro']['tp']},FP={ce['micro']['fp']},FN={ce['micro']['fn']}) | "
            f"ENDP uF1={cep['micro']['f1']:.4f} mF1={cep['macro']['f1']:.4f} (TP={cep['micro']['tp']},FP={cep['micro']['fp']},FN={cep['micro']['fn']})\n"
            f"  [Collapsed] COMP uF1={lc['micro']['f1']:.4f} mF1={lc['macro']['f1']:.4f} (TP={lc['micro']['tp']},FP={lc['micro']['fp']},FN={lc['micro']['fn']}) | "
            f"CONN uF1={le['micro']['f1']:.4f} mF1={le['macro']['f1']:.4f} (TP={le['micro']['tp']},FP={le['micro']['fp']},FN={le['micro']['fn']}) | "
            f"ENDP uF1={lep['micro']['f1']:.4f} mF1={lep['macro']['f1']:.4f} (TP={lep['micro']['tp']},FP={lep['micro']['fp']},FN={lep['micro']['fn']})"
        )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
