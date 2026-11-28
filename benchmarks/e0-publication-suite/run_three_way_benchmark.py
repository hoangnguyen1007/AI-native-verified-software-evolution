#!/usr/bin/env python3
"""Standardized 3-Way Linux Docker Timing Decomposition Runner (default n=5).

Measures Evo's 3-way timing decomposition + per-stage telemetry inside isolated
Linux Docker containers (zero host -v bind mounts; container-local overlayfs):
  1. Cold (Commit A, fresh container)
  2. Warm NO_CHANGE (Commit A, force=true, same container)
  3. Warm 1-File Edit (Commit B, force=true, same container)
  4. Cold on Edited (Commit B, separate fresh container)

For every repetition, captures:
  - Total elapsed & wall-clock milliseconds
  - Full per-stage breakdown from GET /api/v1/analysis/jobs/{id}/telemetry
    (ACQUISITION, PLATFORM, ANALYSIS, GRAPH, POLICY, plus cache/incremental/SAT counters)
  - Strict SHA-256 bit-identity gates:
      (a) Cold (Commit A) == Warm NO_CHANGE (Commit A)
      (b) Warm 1-File Edit (Commit B) != Cold (Commit A)
      (c) Warm 1-File Edit (Commit B) == Cold on Edited (Commit B)
  - Full distribution statistics across n measured runs (default n=5):
    median, IQR, Q1, Q3, min, max, mean, stdev, CV
  - Exact execution environment provenance (uname -a, WSL2 vs native Ubuntu LTS detection,
    CPU/RAM, Docker Engine & container JVM) so WSL2 runs are NEVER mislabeled as native Ubuntu LTS.
"""

from __future__ import annotations

import argparse
import csv
import datetime as dt
import json
import math
import statistics
import subprocess
import sys
import time
import urllib.error
import urllib.request
import uuid
from pathlib import Path
from typing import Any

SUITE_DIR = Path(__file__).resolve().parent
if str(SUITE_DIR) not in sys.path:
    sys.path.insert(0, str(SUITE_DIR))

import env_provenance  # noqa: E402


DEFAULT_REPEATS = 5
DEFAULT_WARMUP = 0
DEFAULT_IMAGE = "evo-workbench-online:latest"
DEFAULT_MEMORY = "2200m"
DEFAULT_JAVA_OPTS = "-Xms256m -Xmx1536m -XX:+UseG1GC -XX:SoftRefLRUPolicyMSPerMB=1"
DEFAULT_EDIT_MARKER = "private int evoBenchEditMarker() { return 1; }"
KNOWN_STAGES = ("ACQUISITION", "PLATFORM", "ANALYSIS", "GRAPH", "POLICY")


def compute_distribution_stats(values: list[float]) -> dict[str, float]:
    """Compute publication-grade summary stats: n, median, IQR, Q1, Q3, min, max, mean, stdev, CV."""
    if not values:
        return {
            "n": 0.0,
            "median": 0.0,
            "iqr": 0.0,
            "q1": 0.0,
            "q3": 0.0,
            "min": 0.0,
            "max": 0.0,
            "mean": 0.0,
            "stdev": 0.0,
            "cv": 0.0,
        }
    ordered = sorted(float(v) for v in values)
    n = len(ordered)
    med = float(statistics.median(ordered))
    if n == 1:
        q1 = q3 = med
        stdev = 0.0
    else:
        if n % 2 == 0:
            q1 = float(statistics.median(ordered[: n // 2]))
            q3 = float(statistics.median(ordered[n // 2 :]))
        else:
            q1 = float(statistics.median(ordered[: n // 2]))
            q3 = float(statistics.median(ordered[n // 2 + 1 :]))
        stdev = float(statistics.stdev(ordered))
    mean = float(statistics.fmean(ordered))
    cv = (stdev / mean) if mean > 0.0 and not math.isnan(stdev) else 0.0
    return {
        "n": float(n),
        "median": round(med, 3),
        "iqr": round(max(0.0, q3 - q1), 3),
        "q1": round(q1, 3),
        "q3": round(q3, 3),
        "min": round(ordered[0], 3),
        "max": round(ordered[-1], 3),
        "mean": round(mean, 3),
        "stdev": round(stdev, 3),
        "cv": round(cv, 4),
    }


def aggregate_stage_timings(stage_maps: list[dict[str, float]]) -> dict[str, dict[str, float]]:
    """Aggregate per-stage milliseconds across repetitions (preserving canonical stage order)."""
    all_keys: list[str] = []
    for k in KNOWN_STAGES:
        if any(k in m for m in stage_maps):
            all_keys.append(k)
    for m in stage_maps:
        for k in sorted(m.keys()):
            if k not in all_keys:
                all_keys.append(k)
    out: dict[str, dict[str, float]] = {}
    for k in all_keys:
        vals = [float(m[k]) for m in stage_maps if k in m and m[k] is not None]
        if vals:
            out[k] = compute_distribution_stats(vals)
    return out


def http_json(
    base_url: str,
    method: str,
    path: str,
    body: dict[str, Any] | None = None,
    timeout_s: int = 90,
) -> dict[str, Any]:
    url = base_url.rstrip("/") + path
    data = json.dumps(body).encode("utf-8") if body is not None else None
    headers = {"Content-Type": "application/json"} if data is not None else {}
    last_err: Exception | None = None
    for attempt in range(3):
        req = urllib.request.Request(url, data=data, headers=headers, method=method)
        try:
            with urllib.request.urlopen(req, timeout=timeout_s) as resp:
                return json.loads(resp.read().decode("utf-8"))
        except urllib.error.HTTPError as exc:
            err_body = exc.read().decode("utf-8", errors="replace")
            if exc.code in (502, 503, 504) and attempt < 2:
                time.sleep(1.0 + attempt)
                last_err = RuntimeError(f"HTTP {exc.code} {path}: {err_body}")
                continue
            raise RuntimeError(f"HTTP {exc.code} {path}: {err_body}") from exc
        except (urllib.error.URLError, ConnectionError, TimeoutError, OSError) as exc:
            if attempt < 2:
                time.sleep(1.0 + attempt)
                last_err = exc
                continue
            raise RuntimeError(f"TransportError {path}: {exc}") from exc
    raise RuntimeError(f"Request failed {path}: {last_err}")


def wait_health(base_url: str, timeout_s: int = 45) -> dict[str, Any]:
    deadline = time.monotonic() + timeout_s
    while time.monotonic() < deadline:
        try:
            res = http_json(base_url, "GET", "/api/v1/health", timeout_s=5)
            return res.get("data", res)
        except Exception:
            time.sleep(0.5)
    raise RuntimeError(f"Evo container at {base_url} did not become healthy within {timeout_s}s")


def _docker(args: list[str], timeout_s: int = 120) -> str:
    proc = subprocess.run(
        ["docker", *args],
        capture_output=True,
        text=True,
        encoding="utf-8",
        errors="replace",
        timeout=timeout_s,
        check=False,
    )
    if proc.returncode != 0:
        raise RuntimeError(f"docker {' '.join(args)} failed (rc={proc.returncode}): {proc.stderr.strip()}")
    return proc.stdout.strip()


def _start_local_overlay_container(
    name: str,
    image: str,
    port: int,
    memory: str,
    java_opts: str,
    profile: str,
    offline_deps: bool,
) -> str:
    subprocess.run(["docker", "rm", "-f", name], capture_output=True, check=False)
    extra_java = f"{java_opts} -Devo.workbench.offline=true" if offline_deps else java_opts
    cmd = [
        "run", "-d",
        "--name", name,
        f"--memory={memory}",
        "-p", f"127.0.0.1:{port}:8080",
        "-e", "EVO_ONLINE_MODE=false",
        "-e", f"EVO_PROJECTION_PROFILE={profile}",
        "-e", f"JAVA_OPTS={extra_java}",
        "--entrypoint", "sh",
        image,
        "-c",
        'mkdir -p /data/evo-store/sqlite-tmp /data/evo-store/m2-cache /tmp/bench-repo && '
        'exec java $JAVA_OPTS -Dfile.encoding=UTF-8 '
        '-Dorg.sqlite.tmpdir=/data/evo-store/sqlite-tmp '
        '-jar /app/evo-workbench.jar '
        '--host 0.0.0.0 --port 8080 '
        '/tmp/bench-repo urn:evo:three-way-bench "$JAVA_HOME" 21 '
        '/data/evo-store/m2-cache /data/evo-store',
    ]
    return _docker(cmd, timeout_s=60)


def build_job_submission_payload(
    setup_id: str,
    profile: str,
    force: bool,
    idempotency_key: str,
) -> dict[str, Any]:
    """Build POST /api/v1/analysis/jobs request body matching AnalysisJobApi strict key allowlist."""
    return {
        "setup": setup_id,
        "force": force,
        "idempotencyKey": idempotency_key,
        "projectionProfile": profile,
    }


def _submit_and_collect_job(
    base_url: str,
    setup_id: str,
    profile: str,
    force: bool,
    timeout_s: int,
) -> dict[str, Any]:
    idem = f"tw-{uuid.uuid4().hex[:24]}"
    t0 = time.perf_counter()
    submit = http_json(
        base_url,
        "POST",
        "/api/v1/analysis/jobs",
        body=build_job_submission_payload(setup_id, profile, force, idem),
        timeout_s=60,
    )
    job = submit.get("data", submit)
    job_id = str(job["id"])

    deadline = time.monotonic() + timeout_s
    while True:
        poll = http_json(base_url, "GET", f"/api/v1/analysis/jobs/{job_id}", timeout_s=30)
        view = poll.get("data", poll)
        state = str(view.get("state", ""))
        if state in ("SUCCEEDED", "PARTIAL", "FAILED", "CANCELLED"):
            wall_ms = round((time.perf_counter() - t0) * 1000.0, 2)
            break
        if time.monotonic() > deadline:
            raise TimeoutError(f"Job {job_id} timed out after {timeout_s}s (state={state})")
        time.sleep(0.25)

    tel_resp = http_json(base_url, "GET", f"/api/v1/analysis/jobs/{job_id}/telemetry", timeout_s=30)
    tel = tel_resp.get("data", tel_resp)
    res_obj = view.get("result") or {}
    raw_stage = tel.get("stageMillis") or {}
    stage_ms = {str(k): float(v) for k, v in raw_stage.items() if isinstance(v, (int, float))}
    return {
        "job_id": job_id,
        "state": state,
        "elapsed_ms": float(view.get("elapsedMillis", tel.get("totalElapsedMillis", 0))),
        "wall_ms": wall_ms,
        "graph_id": str(res_obj.get("graph", "")),
        "gap_count": int(res_obj.get("gapCount", view.get("gapCount", 0)) or 0),
        "stage_millis": stage_ms,
        "cache_metrics": tel.get("cacheMetrics") or {},
        "incremental": tel.get("incremental"),
        "acquisition_summary": tel.get("acquisitionSummary"),
    }


def _apply_deterministic_java_edit_in_container(
    container_name: str,
    repo_dir_in_container: str,
    rel_edit_file: str,
    marker: str = DEFAULT_EDIT_MARKER,
) -> None:
    target = f"{repo_dir_in_container.rstrip('/')}/{rel_edit_file.lstrip('/')}"
    # Append a deterministic private helper method before the final closing brace.
    py_or_awk = (
        f'file="{target}"; '
        'last=$(grep -n "}" "$file" | tail -n 1 | cut -d: -f1); '
        'if [ -z "$last" ]; then echo "NO_CLOSING_BRACE" >&2; exit 2; fi; '
        f'awk -v ln="$last" -v ins="    {marker}" \'NR==ln {{print ins}} {{print}}\' "$file" > "$file.tmp" '
        '&& mv "$file.tmp" "$file"'
    )
    _docker(["exec", container_name, "sh", "-c", py_or_awk], timeout_s=30)


def build_prepare_repo_commands(
    container_name: str,
    repo_source: Path,
    dest_dir: str = "/tmp/bench-repo",
) -> list[tuple[list[str], int]]:
    """Build docker commands to stage a clean copy of repo_source owned by the container's runtime UID."""
    cleaned_dest = dest_dir.rstrip("/")
    if not cleaned_dest or cleaned_dest == "/":
        raise ValueError(f"Refusing to wipe unsafe dest_dir: {dest_dir!r}")
    clean_cmd = [
        "exec", "-u", "0", container_name, "sh", "-c",
        f"rm -rf '{cleaned_dest}' && mkdir -p '{cleaned_dest}'",
    ]
    copy_cmd = ["cp", f"{repo_source}/.", f"{container_name}:{cleaned_dest}"]
    chown_cmd = [
        "exec", "-u", "0", container_name, "sh", "-c",
        f"rm -rf '{cleaned_dest}/.git' "
        f"&& owner=$(stat -c '%u:%g' /data/evo-store 2>/dev/null || echo 10001:10001) "
        f"&& chown -R \"$owner\" '{cleaned_dest}'",
    ]
    return [(clean_cmd, 60), (copy_cmd, 120), (chown_cmd, 60)]


def _prepare_repo_in_container(
    container_name: str,
    repo_source: Path,
    dest_dir: str = "/tmp/bench-repo",
) -> None:
    for cmd, timeout_s in build_prepare_repo_commands(container_name, repo_source, dest_dir):
        _docker(cmd, timeout_s=timeout_s)


def run_three_way_benchmark(
    *,
    repo_dir: Path,
    edit_file: str,
    image: str = DEFAULT_IMAGE,
    profile: str = "strict",
    repeats: int = DEFAULT_REPEATS,
    warmup: int = DEFAULT_WARMUP,
    memory: str = DEFAULT_MEMORY,
    java_opts: str = DEFAULT_JAVA_OPTS,
    base_port: int = 18091,
    timeout_s: int = 300,
    offline_deps: bool = False,
    repo_label: str = "",
) -> dict[str, Any]:
    if not repo_dir.is_dir():
        raise FileNotFoundError(f"Repository directory does not exist: {repo_dir}")
    edit_full = repo_dir / edit_file
    if not edit_full.is_file():
        raise FileNotFoundError(f"Edit target file not found in repository: {edit_full}")

    measured_runs: list[dict[str, Any]] = []
    warmup_runs: list[dict[str, Any]] = []
    env_info: dict[str, Any] | None = None
    total_iters = warmup + repeats

    for idx in range(total_iters):
        is_warmup = idx < warmup
        label = f"warmup-{idx + 1}" if is_warmup else f"rep-{idx - warmup + 1}"
        c_primary = f"evo-3way-a-{idx}-{uuid.uuid4().hex[:6]}"
        c_clean_edit = f"evo-3way-b-{idx}-{uuid.uuid4().hex[:6]}"
        url_primary = f"http://127.0.0.1:{base_port}"
        url_clean = f"http://127.0.0.1:{base_port + 1}"
        try:
            _start_local_overlay_container(c_primary, image, base_port, memory, java_opts, profile, offline_deps)
            wait_health(url_primary, timeout_s=60)
            if env_info is None:
                env_info = env_provenance.collect_environment_provenance(
                    execution_target="docker",
                    include_docker=True,
                    docker_image=image,
                    container_id=c_primary,
                )
            _prepare_repo_in_container(c_primary, repo_dir)

            cold = _submit_and_collect_job(url_primary, "local", profile, force=True, timeout_s=timeout_s)
            warm_nc = _submit_and_collect_job(url_primary, "local", profile, force=True, timeout_s=timeout_s)

            _apply_deterministic_java_edit_in_container(c_primary, "/tmp/bench-repo", edit_file)
            warm_edit = _submit_and_collect_job(url_primary, "local", profile, force=True, timeout_s=timeout_s)
        finally:
            subprocess.run(["docker", "rm", "-f", c_primary], capture_output=True, check=False)

        try:
            _start_local_overlay_container(c_clean_edit, image, base_port + 1, memory, java_opts, profile, offline_deps)
            wait_health(url_clean, timeout_s=60)
            _prepare_repo_in_container(c_clean_edit, repo_dir)
            _apply_deterministic_java_edit_in_container(c_clean_edit, "/tmp/bench-repo", edit_file)
            cold_edited = _submit_and_collect_job(url_clean, "local", profile, force=True, timeout_s=timeout_s)
        finally:
            subprocess.run(["docker", "rm", "-f", c_clean_edit], capture_output=True, check=False)

        warm_nc_match = bool(cold["graph_id"]) and (cold["graph_id"] == warm_nc["graph_id"])
        edit_changed = bool(warm_edit["graph_id"]) and (warm_edit["graph_id"] != cold["graph_id"])
        edit_parity = bool(warm_edit["graph_id"]) and (warm_edit["graph_id"] == cold_edited["graph_id"])

        record = {
            "iteration": label,
            "is_warmup": is_warmup,
            "cold": cold,
            "warm_no_change": warm_nc,
            "warm_1file_edit": warm_edit,
            "cold_on_edited": cold_edited,
            "warm_no_change_matches_cold": warm_nc_match,
            "edit_changed_graph": edit_changed,
            "warm_edit_matches_cold_edited": edit_parity,
        }
        if is_warmup:
            warmup_runs.append(record)
        else:
            measured_runs.append(record)

    if env_info is None:
        env_info = env_provenance.collect_environment_provenance(
            execution_target="docker", include_docker=True, docker_image=image
        )

    return summarize_three_way_runs(
        repo_label=repo_label or repo_dir.name,
        repo_path=str(repo_dir),
        edit_file=edit_file,
        profile=profile,
        repeats=repeats,
        warmup=warmup,
        memory=memory,
        java_opts=java_opts,
        offline_deps=offline_deps,
        environment=env_info,
        measured_runs=measured_runs,
        warmup_runs=warmup_runs,
    )


def summarize_three_way_runs(
    *,
    repo_label: str,
    repo_path: str,
    edit_file: str,
    profile: str,
    repeats: int,
    warmup: int,
    memory: str,
    java_opts: str,
    offline_deps: bool,
    environment: dict[str, Any],
    measured_runs: list[dict[str, Any]],
    warmup_runs: list[dict[str, Any]] | None = None,
) -> dict[str, Any]:
    cold_stats = compute_distribution_stats([r["cold"]["elapsed_ms"] for r in measured_runs])
    warm_nc_stats = compute_distribution_stats([r["warm_no_change"]["elapsed_ms"] for r in measured_runs])
    warm_edit_stats = compute_distribution_stats([r["warm_1file_edit"]["elapsed_ms"] for r in measured_runs])
    cold_edit_stats = compute_distribution_stats([r["cold_on_edited"]["elapsed_ms"] for r in measured_runs])

    speedup_nc = round(cold_stats["median"] / warm_nc_stats["median"], 2) if warm_nc_stats["median"] > 0 else 0.0
    speedup_edit = round(cold_stats["median"] / warm_edit_stats["median"], 2) if warm_edit_stats["median"] > 0 else 0.0

    return {
        "schema": "evo.three-way-timing:v2",
        "timestamp_utc": dt.datetime.now(dt.timezone.utc).isoformat(),
        "repository": repo_label,
        "repository_path": repo_path,
        "edited_file": edit_file,
        "profile": profile,
        "measured_repeats": repeats,
        "warmup_repeats": warmup,
        "container_memory": memory,
        "java_opts": java_opts,
        "offline_deps": offline_deps,
        "environment": environment,
        "summary": {
            "cold_elapsed_ms": cold_stats,
            "warm_no_change_elapsed_ms": warm_nc_stats,
            "warm_1file_edit_elapsed_ms": warm_edit_stats,
            "cold_on_edited_elapsed_ms": cold_edit_stats,
            "speedup_warm_no_change_vs_cold": speedup_nc,
            "speedup_warm_1file_edit_vs_cold": speedup_edit,
            "stage_decomposition_ms": {
                "cold": aggregate_stage_timings([r["cold"].get("stage_millis") or {} for r in measured_runs]),
                "warm_no_change": aggregate_stage_timings([r["warm_no_change"].get("stage_millis") or {} for r in measured_runs]),
                "warm_1file_edit": aggregate_stage_timings([r["warm_1file_edit"].get("stage_millis") or {} for r in measured_runs]),
                "cold_on_edited": aggregate_stage_timings([r["cold_on_edited"].get("stage_millis") or {} for r in measured_runs]),
            },
            "warm_no_change_sha256_passes": sum(1 for r in measured_runs if r["warm_no_change_matches_cold"]),
            "edit_changed_graph_passes": sum(1 for r in measured_runs if r["edit_changed_graph"]),
            "warm_edit_vs_cold_edited_sha256_passes": sum(1 for r in measured_runs if r["warm_edit_matches_cold_edited"]),
        },
        "measured_runs": measured_runs,
        "warmup_runs": warmup_runs or [],
    }


def render_three_way_markdown(payload: dict[str, Any]) -> str:
    s = payload["summary"]
    n = payload["measured_repeats"]
    lines = [
        f"# Evo 3-Way Timing Decomposition Report (`{payload['repository']}`, `n={n}`)",
        "",
        f"- **Timestamp (UTC):** `{payload['timestamp_utc']}` | **Profile:** `{payload['profile']}` "
        f"| **Measured Repeats:** `n={n}` (warmup=`{payload['warmup_repeats']}`)",
        f"- **Edited File:** `{payload['edited_file']}` | **Container:** `--memory={payload['container_memory']}` "
        f"| **Offline Deps:** `{payload['offline_deps']}`",
        "",
    ]
    lines.extend(env_provenance.format_provenance_markdown(payload["environment"]))
    lines.extend(
        [
            "## 3-Way Timing Summary (ms)",
            "",
            "| Phase | n | Median ± IQR (ms) | Min .. Max (ms) | Mean ± Stdev (ms) | CV | Speedup vs Cold | SHA-256 Parity |",
            "| :--- | ---: | ---: | ---: | ---: | ---: | :---: | :---: |",
        ]
    )
    rows = [
        ("1. Cold (Commit A)", s["cold_elapsed_ms"], "1.00x", "Baseline A"),
        (
            "2. Warm NO_CHANGE (Commit A)",
            s["warm_no_change_elapsed_ms"],
            f"{s['speedup_warm_no_change_vs_cold']:.2f}x",
            f"{s['warm_no_change_sha256_passes']}/{n} (`c==w`)",
        ),
        (
            "3. Warm 1-File Edit (Commit B)",
            s["warm_1file_edit_elapsed_ms"],
            f"{s['speedup_warm_1file_edit_vs_cold']:.2f}x",
            f"{s['edit_changed_graph_passes']}/{n} (`B!=A`)",
        ),
        (
            "4. Cold on Edited (Commit B)",
            s["cold_on_edited_elapsed_ms"],
            f"{round(s['cold_elapsed_ms']['median'] / s['cold_on_edited_elapsed_ms']['median'], 2) if s['cold_on_edited_elapsed_ms']['median'] > 0 else 0.0:.2f}x",
            f"{s['warm_edit_vs_cold_edited_sha256_passes']}/{n} (`wB==cB`)",
        ),
    ]
    for title, st, spd, parity in rows:
        lines.append(
            f"| {title} | {int(st['n'])} | {st['median']:.1f} ± {st['iqr']:.1f} | "
            f"{st['min']:.1f} .. {st['max']:.1f} | {st['mean']:.1f} ± {st['stdev']:.1f} | "
            f"{st['cv']:.4f} | {spd} | {parity} |"
        )

    stage_decomp = s.get("stage_decomposition_ms") or {}
    all_stages: list[str] = []
    for phase_map in stage_decomp.values():
        for k in phase_map:
            if k not in all_stages:
                all_stages.append(k)
    if all_stages:
        lines.extend(
            [
                "",
                "## Per-Stage Timing Decomposition (Median ± IQR ms, CV)",
                "",
                "| Stage | Cold (ms) | Warm NO_CHANGE (ms) | Warm 1-File Edit (ms) | Cold on Edited (ms) |",
                "| :--- | ---: | ---: | ---: | ---: |",
            ]
        )
        for stg in all_stages:
            cells = []
            for phase in ("cold", "warm_no_change", "warm_1file_edit", "cold_on_edited"):
                st = (stage_decomp.get(phase) or {}).get(stg)
                if st:
                    cells.append(f"{st['median']:.1f} ± {st['iqr']:.1f} (CV {st['cv']:.2f})")
                else:
                    cells.append("—")
            lines.append(f"| `{stg}` | " + " | ".join(cells) + " |")
    return "\n".join(lines) + "\n"


def render_three_way_latex(payload: dict[str, Any]) -> str:
    s = payload["summary"]
    env = payload["environment"]
    n = payload["measured_repeats"]
    env_cls = str(env.get("environment_class", "unknown")).replace("_", r"\_")
    caveat_comment = (
        f"% HONEST ENVIRONMENT PROVENANCE: {env.get('publication_caveat')}\n"
        if env.get("publication_caveat")
        else "% Native Ubuntu LTS execution verified.\n"
    )
    lines = [
        caveat_comment.rstrip("\n"),
        r"\begin{table}[t]",
        r"\centering",
        rf"\caption{{3-Way Timing Decomposition on {payload['repository'].replace('_', r'\_')} ($n={n}$, environment: \texttt{{{env_cls}}})}}",
        r"\begin{tabular}{lrrrrr}",
        r"\toprule",
        r"Phase & Median $\pm$ IQR (ms) & Min..Max (ms) & CV & Speedup & Parity \\",
        r"\midrule",
    ]
    for title, key, spd, parity in (
        ("Cold (Commit A)", "cold_elapsed_ms", "1.00$\\times$", "Baseline"),
        ("Warm No-Change", "warm_no_change_elapsed_ms", f"{s['speedup_warm_no_change_vs_cold']:.2f}$\\times$", f"{s['warm_no_change_sha256_passes']}/{n}"),
        ("Warm 1-File Edit", "warm_1file_edit_elapsed_ms", f"{s['speedup_warm_1file_edit_vs_cold']:.2f}$\\times$", f"{s['edit_changed_graph_passes']}/{n}"),
        ("Cold on Edited", "cold_on_edited_elapsed_ms", "—", f"{s['warm_edit_vs_cold_edited_sha256_passes']}/{n}"),
    ):
        st = s[key]
        lines.append(
            f"{title} & {st['median']:.1f} $\\pm$ {st['iqr']:.1f} & "
            f"{st['min']:.1f}..{st['max']:.1f} & {st['cv']:.3f} & {spd} & {parity} \\\\"
        )
    lines.extend([r"\bottomrule", r"\end{tabular}", r"\end{table}"])
    return "\n".join(lines) + "\n"


def write_outputs(out_dir: Path, payload: dict[str, Any]) -> None:
    out_dir.mkdir(parents=True, exist_ok=True)
    (out_dir / "three_way_summary.json").write_text(json.dumps(payload, indent=2), encoding="utf-8")
    (out_dir / "three_way_report.md").write_text(render_three_way_markdown(payload), encoding="utf-8")
    (out_dir / "three_way_table.tex").write_text(render_three_way_latex(payload), encoding="utf-8")
    with (out_dir / "three_way_runs.csv").open("w", newline="", encoding="utf-8") as fh:
        w = csv.writer(fh)
        w.writerow([
            "iteration", "phase", "elapsed_ms", "wall_ms", "state", "gap_count", "graph_id",
            "ACQUISITION_ms", "PLATFORM_ms", "ANALYSIS_ms", "GRAPH_ms", "POLICY_ms",
        ])
        for rec in payload["measured_runs"]:
            for phase in ("cold", "warm_no_change", "warm_1file_edit", "cold_on_edited"):
                p = rec[phase]
                sm = p.get("stage_millis") or {}
                w.writerow([
                    rec["iteration"], phase, p["elapsed_ms"], p["wall_ms"], p["state"],
                    p["gap_count"], p["graph_id"],
                    sm.get("ACQUISITION", ""), sm.get("PLATFORM", ""),
                    sm.get("ANALYSIS", ""), sm.get("GRAPH", ""), sm.get("POLICY", ""),
                ])


def build_arg_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description="Evo Standardized 3-Way Linux Docker Timing Decomposition Runner (default n=5)"
    )
    parser.add_argument("--repo-dir", type=Path, required=True, help="Local clean clone of target repository")
    parser.add_argument("--edit-file", required=True, help="Relative path of Java file for deterministic 1-file edit")
    parser.add_argument("--repo-label", default="", help="Display label for repository (e.g. ewolff/microservice)")
    parser.add_argument("--image", default=DEFAULT_IMAGE, help="Docker image name")
    parser.add_argument("--profile", default="strict", choices=["strict", "dfd-export"])
    parser.add_argument("--repeats", type=int, default=DEFAULT_REPEATS, help="Measured repetitions (default: 5)")
    parser.add_argument("--warmup", type=int, default=DEFAULT_WARMUP, help="Optional unmeasured warmup repetitions (default: 0)")
    parser.add_argument("--memory", default=DEFAULT_MEMORY)
    parser.add_argument("--java-opts", default=DEFAULT_JAVA_OPTS)
    parser.add_argument("--base-port", type=int, default=18091)
    parser.add_argument("--timeout-s", type=int, default=300)
    parser.add_argument(
        "--offline-deps",
        action="store_true",
        help="Pass -Devo.workbench.offline=true for zero-network timing (fresh containers have an empty /data/evo-store/m2-cache unless pre-seeded in the image)",
    )
    parser.add_argument("--out-dir", type=Path, help="Output directory outside Git for JSON/MD/TeX/CSV")
    return parser


def main(argv: list[str] | None = None) -> int:
    args = build_arg_parser().parse_args(argv)
    payload = run_three_way_benchmark(
        repo_dir=args.repo_dir,
        edit_file=args.edit_file,
        image=args.image,
        profile=args.profile,
        repeats=max(1, args.repeats),
        warmup=max(0, args.warmup),
        memory=args.memory,
        java_opts=args.java_opts,
        base_port=args.base_port,
        timeout_s=args.timeout_s,
        offline_deps=args.offline_deps,
        repo_label=args.repo_label,
    )
    md = render_three_way_markdown(payload)
    print(md)
    if args.out_dir:
        write_outputs(args.out_dir, payload)
    return 0


if __name__ == "__main__":
    sys.exit(main())
