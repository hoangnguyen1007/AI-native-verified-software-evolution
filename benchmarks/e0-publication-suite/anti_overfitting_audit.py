#!/usr/bin/env python3
"""Automated Anti-Overfitting & Benchmark-Leak Audit for Evo Production Code.

Scans all production source files under src/main/ (Java, Python, Kotlin, TypeScript, JS)
and fails with exit code 1 if ANY benchmark repository name, benchmark-specific service
name, dataset-specific DFD actor label, or regex proxy for a benchmark component appears
in production code.

Run:
  python benchmarks/e0-publication-suite/anti_overfitting_audit.py
  python benchmarks/e0-publication-suite/anti_overfitting_audit.py \
      --ground-truth-dir D:/UIT/evo-benchmark-cache/EMSE2025SAR-Replication/tool_comparison/groundtruth_textual \
      --json audit_report.json
"""

from __future__ import annotations

import argparse
import json
import os
import re
import sys
from pathlib import Path
from typing import Any

PROD_EXTENSIONS = {".java", ".py", ".kt", ".ts", ".tsx", ".js", ".jsx"}
PRUNE_DIRS = {".git", "node_modules", "target", "build", "dist", ".idea", ".gradle", "__pycache__", "graphify-out", ".venv", "venv"}

# 1. Benchmark repository & author identifiers (from microSecEnD 17 + flagship benchmarks)
BANNED_BENCHMARK_REPOS = [
    "microservices-basics-spring-boot",
    "blog-microservices",
    "spring-netflix-oss-microservices",
    "apache-spring-boot-microservice-example",
    "tap-and-eat-microservices",
    "spring-boot-microservices-workshop",
    "spring-cloud-movie-recommendation",
    "sample-spring-oauth2-microservices",
    "spring-petclinic-microservices",
    "spring-cloud-netflix-example",
    "microservices-demo",
    "online-boutique",
    "onlineboutique",
    "piggymetrics",
    "teastore",
    "descartesresearch",
    "shopizer",
    "petclinic",
    "ewolff",
    "anilallewar",
    "callistaenterprise",
    "mudigal",
    "koushikkothagal",
    "yidongnan",
    "fernandoabcampos",
    "georgwittberger",
    "jferrater",
    "mdeket",
    "piomin",
    "rohitghatol",
    "shabbirdwd53",
    "sqshq",
]

# 2. Online Boutique, TeaStore, PetClinic & flagship service names
BANNED_SERVICE_NAMES = [
    "redis-cart",
    "cartservice",
    "adservice",
    "checkoutservice",
    "currencyservice",
    "emailservice",
    "loadgenerator",
    "paymentservice",
    "productcatalogservice",
    "recommendationservice",
    "shippingservice",
    "shoppingassistantservice",
    "frontend-external",
    "teastore-auth",
    "teastore-webui",
    "teastore-persistence",
    "teastore-recommender",
    "teastore-registry",
    "teastore-image",
    "teastore-db",
    "teastore-kieker",
    "vets-service",
    "visits-service",
    "customers-service",
]

# 3. Dataset-specific microSecEnD DFD/component hacks & bespoke parser traps
BANNED_DATASET_HACKS = [
    "zipkin-tracing",
    "mail-server",
    "external-website",
    "web-application",
    "ServiceLoadBalancer",
    "logstash.conf",
    "movie-catalog-service",
    "movie-info-service",
    "ratings-data-service",
    "statistics-service",
    "product-composite",
]

# 4. Sneaky regex / substring proxy patterns (catches e.g. "redis.*cart", "mail.*server")
BANNED_PROXY_REGEXES = [
    ("proxy:redis-cart", re.compile(r"redis.{0,8}cart", re.IGNORECASE)),
    ("proxy:mail-server", re.compile(r"mail.{0,4}server", re.IGNORECASE)),
    ("proxy:zipkin-tracing", re.compile(r"(?<!management\.)(?<!STARTER_)zipkin(?:[-_]|(?:\.\*)|(?:\.\+))tracing", re.IGNORECASE)),
    ("proxy:external-website", re.compile(r"external.{0,6}website", re.IGNORECASE)),
    ("proxy:web-application", re.compile(r"[\"']web-application[\"']", re.IGNORECASE)),
    ("proxy:piggymetrics", re.compile(r"piggy.{0,4}metrics", re.IGNORECASE)),
    ("proxy:teastore", re.compile(r"tea.{0,4}store", re.IGNORECASE)),
    ("proxy:petclinic", re.compile(r"pet.{0,4}clinic", re.IGNORECASE)),
    ("proxy:shopizer", re.compile(r"shopizer", re.IGNORECASE)),
]

# Standard domain/protocol/architectural/programming terms legitimately used by generic analyzers
GENERIC_ARCHITECTURE_ALLOWLIST = {
    "mysql", "postgres", "postgresql", "mariadb", "mongodb", "mongo", "h2", "hsqldb", "sqlite",
    "redis", "valkey", "memcached", "cassandra", "couchdb", "neo4j", "clickhouse", "influxdb",
    "opensearch", "elasticsearch", "kibana", "logstash", "elk", "filebeat", "fluentd",
    "rabbitmq", "rabbit", "kafka", "nats", "activemq", "zookeeper",
    "zipkin", "jaeger", "prometheus", "grafana", "otel", "opentelemetry",
    "smtp", "mail", "mailhog", "maildev", "mailpit", "postfix",
    "git", "github", "gitlab", "github-repository", "gitlab-repository", "git-repository",
    "config", "config-server", "config-service", "registry", "discovery", "discovery-server",
    "discovery-service", "eureka", "eureka-server", "consul", "gateway", "api-gateway", "zuul",
    "admin", "admin-server", "spring-boot-admin", "auth", "auth-server", "auth-service", "uaa",
    "oauth", "oauth2", "authorization-server", "hystrix", "hystrix-dashboard", "turbine",
    "turbine-server", "turbine-stream", "monitor", "monitoring", "edge", "proxy",
    "apache", "httpd", "nginx", "envoy", "traefik", "haproxy",
    "user", "frontend", "ui", "web", "webapp", "webui", "portal",
    "db", "database", "cache", "broker", "queue", "server", "service", "client", "app", "api",
    "scope", "order", "orders", "product", "products", "catalog", "payment", "shipping", "inventory",
    "customer", "customers", "account", "billing", "notification", "review", "rating", "search",
    "item", "items", "price", "store", "basket", "cart", "recommendation", "comment", "user-service",
}

STRING_LITERAL_RE = re.compile(r'"([^"\\]*(?:\\.[^"\\]*)*)"|\'([^\'\\]*(?:\\.[^\'\\]*)*)\'')


def is_prod_source(path: Path, repo_root: Path) -> bool:
    if path.suffix.lower() not in PROD_EXTENSIONS:
        return False
    rel = "/" + path.relative_to(repo_root).as_posix().lower()
    if rel.startswith("/benchmarks/"):
        return False
    if "/src/main/" not in rel:
        return False
    if any(seg in rel for seg in ("/target/", "/build/", "/node_modules/", "/.git/", "/dist/")):
        return False
    return True


def load_manifest_banned_tokens(manifest_path: Path | None) -> list[str]:
    if not manifest_path or not manifest_path.is_file():
        return []
    data = json.loads(manifest_path.read_text(encoding="utf-8"))
    tokens: list[str] = []
    # Generic organization or standard JDK/RxJava library names excluded from owner-token bans
    generic_owners = {"spring-cloud", "spring-projects", "googlecloudplatform", "flowable"}
    for suite_apps in data.get("suites", {}).values():
        if not isinstance(suite_apps, list):
            continue
        for entry in suite_apps:
            if not isinstance(entry, dict):
                continue
            for key in ("id", "dir"):
                val = str(entry.get(key, "")).strip()
                if not val:
                    continue
                if "/" in val:
                    owner, repo = val.split("/", 1)
                    if len(owner) >= 5 and owner.lower() not in generic_owners:
                        tokens.append(owner)
                    if len(repo) >= 8 and repo.lower() not in {"microservice", "microservices", "spring-cloud-gateway"}:
                        tokens.append(repo)
                elif len(val) >= 8 and val.lower() not in {"microservice", "microservices", "java-microservice", "spring-cloud-gateway"}:
                    tokens.append(val)
    return sorted(set(tokens))


def load_single_app_groundtruth_tokens(gt_dir: Path | None) -> dict[str, str]:
    """Return distinctive GT component names occurring in exactly one microSecEnD app."""
    if not gt_dir or not gt_dir.is_dir():
        return {}
    token_apps: dict[str, set[str]] = {}
    for txt_file in sorted(gt_dir.glob("*.txt")):
        sec = None
        for raw in txt_file.read_text(encoding="utf-8", errors="replace").splitlines():
            line = raw.strip()
            if line == "Components:":
                sec = "COMP"
                continue
            if line in ("Connections:", "Endpoints:"):
                sec = None
                continue
            if sec == "COMP" and line:
                norm = re.sub(r"[_\s.]+", "-", line.lower()).strip("-")
                if len(norm) >= 4 and norm not in GENERIC_ARCHITECTURE_ALLOWLIST:
                    token_apps.setdefault(norm, set()).add(txt_file.stem)
    return {
        tok: next(iter(apps))
        for tok, apps in token_apps.items()
        if len(apps) == 1
    }


def iter_prod_sources(repo_root: Path) -> list[Path]:
    found: list[Path] = []
    for dirpath, dirnames, filenames in os.walk(repo_root):
        dirnames[:] = sorted(d for d in dirnames if d not in PRUNE_DIRS)
        cur = Path(dirpath)
        for fname in sorted(filenames):
            fp = cur / fname
            if is_prod_source(fp, repo_root):
                found.append(fp)
    return found


def scan_repository(
    repo_root: Path,
    manifest_path: Path | None = None,
    gt_dir: Path | None = None,
) -> dict[str, Any]:
    files = iter_prod_sources(repo_root)

    exact_by_lower: dict[str, tuple[str, str]] = {}

    def register_exact(category: str, term: str) -> None:
        key = term.lower()
        if key in exact_by_lower:
            return
        exact_by_lower[key] = (category, term)

    for t in BANNED_BENCHMARK_REPOS:
        register_exact("benchmark-repo", t)
    for t in load_manifest_banned_tokens(manifest_path):
        register_exact("manifest-repo", t)
    for t in BANNED_SERVICE_NAMES:
        register_exact("benchmark-service", t)
    for t in BANNED_DATASET_HACKS:
        register_exact("dataset-specific-hack", t)

    sorted_terms = sorted(exact_by_lower.keys(), key=len, reverse=True)
    combined_exact = (
        re.compile(r"(?<![A-Za-z0-9])(?:" + "|".join(re.escape(t) for t in sorted_terms) + r")(?![A-Za-z0-9])", re.IGNORECASE)
        if sorted_terms
        else None
    )
    combined_proxy = re.compile(
        "|".join(f"(?P<p{i}>{pat.pattern})" for i, (_, pat) in enumerate(BANNED_PROXY_REGEXES)),
        re.IGNORECASE,
    )
    proxy_names = {f"p{i}": name for i, (name, _) in enumerate(BANNED_PROXY_REGEXES)}

    gt_distinctive = load_single_app_groundtruth_tokens(gt_dir)

    findings: list[dict[str, Any]] = []
    for fp in files:
        rel = fp.relative_to(repo_root).as_posix()
        text = fp.read_text(encoding="utf-8", errors="replace")
        has_exact_hit = combined_exact is not None and combined_exact.search(text) is not None
        has_proxy_hit = combined_proxy.search(text) is not None
        if not has_exact_hit and not has_proxy_hit and not gt_distinctive:
            continue
        lines = text.splitlines()
        for line_no, line in enumerate(lines, 1):
            if has_exact_hit and combined_exact is not None:
                for m in combined_exact.finditer(line):
                    matched_lower = m.group(0).lower()
                    category, term = exact_by_lower[matched_lower]
                    findings.append({
                        "file": rel,
                        "line": line_no,
                        "category": category,
                        "rule": term,
                        "snippet": line.strip()[:160],
                    })
            if has_proxy_hit:
                for m in combined_proxy.finditer(line):
                    if m.lastgroup:
                        findings.append({
                            "file": rel,
                            "line": line_no,
                            "category": "regex-proxy",
                            "rule": proxy_names[m.lastgroup],
                            "snippet": line.strip()[:160],
                        })
            if gt_distinctive and ('"' in line or "'" in line):
                for m in STRING_LITERAL_RE.finditer(line):
                    lit = (m.group(1) if m.group(1) is not None else m.group(2) or "").strip()
                    norm_lit = re.sub(r"[_\s.]+", "-", lit.lower()).strip("-")
                    if norm_lit in gt_distinctive:
                        findings.append({
                            "file": rel,
                            "line": line_no,
                            "category": "groundtruth-single-app-literal",
                            "rule": f"{norm_lit} (only in {gt_distinctive[norm_lit]})",
                            "snippet": line.strip()[:160],
                        })

    # Deduplicate findings on (file, line, rule)
    deduped: list[dict[str, Any]] = []
    seen_keys: set[tuple[str, int, str]] = set()
    for f in findings:
        k = (f["file"], f["line"], f["rule"])
        if k not in seen_keys:
            seen_keys.add(k)
            deduped.append(f)

    return {
        "schema": "e0.anti-overfitting-audit:v2",
        "repo_root": str(repo_root),
        "files_scanned": len(files),
        "exact_rules_count": len(exact_by_lower),
        "proxy_rules_count": len(BANNED_PROXY_REGEXES),
        "groundtruth_single_app_terms_checked": len(gt_distinctive),
        "leaks_count": len(deduped),
        "status": "PASS" if not deduped else "FAIL",
        "findings": deduped,
    }


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Anti-overfitting & benchmark-leak audit over src/main/.")
    parser.add_argument("--repo-root", type=Path, default=Path("."))
    parser.add_argument("--manifest", type=Path, default=Path("benchmarks/e0-publication-suite/corpus_manifest.json"))
    parser.add_argument("--ground-truth-dir", type=Path, default=None)
    parser.add_argument("--json", dest="json_out", type=Path, default=None)
    args = parser.parse_args(argv)

    manifest = args.manifest if args.manifest.is_file() else None
    report = scan_repository(args.repo_root.resolve(), manifest, args.ground_truth_dir)

    if args.json_out:
        args.json_out.parent.mkdir(parents=True, exist_ok=True)
        args.json_out.write_text(json.dumps(report, indent=2), encoding="utf-8")

    print(
        f"[anti-overfitting-audit] status={report['status']} "
        f"files_scanned={report['files_scanned']} "
        f"rules={report['exact_rules_count']}+{report['proxy_rules_count']} "
        f"gt_terms={report['groundtruth_single_app_terms_checked']} "
        f"leaks={report['leaks_count']}"
    )
    for finding in report["findings"]:
        print(
            f"  LEAK [{finding['category']}] {finding['file']}:{finding['line']} "
            f"rule={finding['rule']} :: {finding['snippet']}"
        )
    return 0 if report["leaks_count"] == 0 else 1


if __name__ == "__main__":
    raise SystemExit(main())
