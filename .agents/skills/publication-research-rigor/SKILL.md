---
name: publication-research-rigor
description: >-
  Enforce CORE A* / Q1 publication rigor, anti-overfitting checks, 3-way
  benchmark timing decomposition, Windows Docker Desktop online benchmark
  hygiene, live-HEAD reconciliation, and Windows PowerShell 5.1 multi-module
  Maven test execution. Use when executing masterplan work packages, running
  benchmarks or ablations, preparing conference/journal artifacts, or updating
  scientific evaluation reports.
---

# Publication & Research Rigor

Use this workflow alongside `AGENTS.md`, [20-evidence-first.md](../../rules/20-evidence-first.md), and [fast-verification](../fast-verification/SKILL.md) whenever working on **Track B (Publication Rigor)** tasks: benchmarks, ablations, masterplan packages (`WP*`, `P2-*`, `F*`, `S*`, `R*`), or paper drafts/artifacts.

> **Track Boundary:** Routine **Track A (Product SWE — Online Web App & Backend)** tasks use `T0-T3` in [fast-verification](../fast-verification/SKILL.md) and must **not** trigger `T4` publication campaigns unless explicitly requested.

## 1. Pre-Flight Live-HEAD Reconciliation

Before starting any roadmap or masterplan work package, verify that cited anchors and gaps still match `HEAD`:

1. Inspect `git status -sb` and recent commits (`git log -n 10 --oneline`).
2. Confirm exact file paths, line numbers, and unfixed behavior on disk before citing baseline metrics or assuming a prerequisite is missing (or still unimplemented).
3. Verify any external conference deadlines or SOTA claims via live web search rather than relying on static dates in older planning docs.

## 2. Anti-Overfitting Gate (Production Code Check)

Never add benchmark-specific class names, enums, repo identifiers, or bespoke non-standard config DSL parsers inside `src/main/`. Only implement open industrial standards (e.g., Docker Compose, Kubernetes YAML, Spring Boot/Cloud YAML, Prometheus, Jakarta/Javax JAX-RS & Servlet, gRPC/Protobuf, AMQP/Kafka).

Run this fast `git grep` gate (`~0.20 s`, `24x` faster than PowerShell `Select-String`) plus the untracked-file guardrail across all `src/main/` directories before completing any extractor change:

```powershell
git grep -n -E 'ServiceLoadBalancer|teastore|logstash\.conf|blog-microservices|piggymetrics|petclinic' -- 'analyzer*/src/main/**/*.java' 'backend/src/main/**/*.java' 'cli/src/main/**/*.java' 'analyzer*/src/main/**/*.py';
git ls-files --others --exclude-standard -- '*/src/main/*'
```

Expected output: **0 `git grep` matches** (and inspect any newly created untracked `src/main/` files listed by the second command).

## 3. Epistemic Tiering & Quarantine Invariance

Keep all epistemic tiers mathematically separate in outputs, tables, and tests:
- **Strict Canonical (`--projection-profile=strict`)** vs **DFD Compatibility (`--projection-profile=dfd-export`)**.
- **`COMPILER` / `SCIP`** vs **`SOURCE_SYNTAX` / `SOURCE_SCOPED`** vs **`DECLARATIVE_CONFIG`** vs **`ADVISORY_UNVERIFIED`**.
- Verify **Quarantine Invariance**: mutating non-code files (`.sql`, `.tf`, `.md`, `.mcp.json`) must yield `0` changes to canonical `KnowledgeGraph` digests and canonical Leiden communities.

## 4. Benchmark & Timing Protocol (`E0`)

- **3-Way Runtime Decomposition:** Never present Warm `NO_CHANGE` memoization as incremental analysis. Report three distinct columns:
  1. `Cold Run` (clean `.evo` workspace),
  2. `Warm NO_CHANGE` (verified bit-identical `SHA-256` vs `Cold`),
  3. `Warm 1-File Edit` (real AST edit in one module, verified bit-identical `SHA-256` against `Cold Run` on the edited repo).
- **Publication Hardware & Statistics:** Official paper timing tables must run on fixed **Linux Ubuntu LTS** (no Windows Defender I/O noise), with **5 independent repeats** reported as `Median ± IQR` (keep Windows measurements in an appendix if included).
- **No Subset Extrapolation:** Run the full benchmark corpus (e.g., all `17/17` `microSecEnD` apps via pinned `EMSE2025SAR-Replication` scripts) and report both **Micro-Average** and **Macro-Average F1** before citing full-benchmark numbers.

### 4.1 Windows Docker Desktop Online Benchmark Hygiene (`E6` / Multi-Repo Campaigns)

- **Isolated Container OverlayFS Only:** Never mount a shared Windows host directory (`-v D:\...`) concurrently into multiple Linux Docker containers during benchmarks (causes VirtioFS/9P deadlock). Run isolated containers against container-local overlayfs (e.g., `evo-workbench-online:latest` with `-e EVO_PROJECTION_PROFILE=strict|dfd-export` and zero `-v` host mounts).
- **Short `idempotencyKey` (`<= 64` chars):** `POST /api/v1/analysis/jobs` enforces `[A-Za-z0-9][A-Za-z0-9._-]{0,63}` in `AnalysisJobs.identifier(...)`; keep generated keys `<= 64` characters.
- **Resumable Checkpointing & Guaranteed Cleanup:** Persist atomic per-app JSON checkpoints (`*.tmp` replace) supporting `--resume` without clobbering already-completed steady apps, and wrap long campaigns in `try { ... } finally { docker rm -f ... }` followed by `docker ps -a` confirmation that zero benchmark containers leaked:

```powershell
try {
  docker run -d --name evo-e6-strict -p 127.0.0.1:18081:8080 -e EVO_PROJECTION_PROFILE=strict evo-workbench-online:latest
  docker run -d --name evo-e6-dfd -p 127.0.0.1:18082:8080 -e EVO_PROJECTION_PROFILE=dfd-export evo-workbench-online:latest
  python benchmarks\e0-publication-suite\run_online_microsecend_benchmark.py --resume --out-dir D:\UIT\evo-benchmark-cache\results\e6-online-microsecend
} finally {
  docker rm -f evo-e6-strict evo-e6-dfd 2>$null | Out-Null
}
```

## 5. Multi-Track Venue & Go/No-Go Discipline

- **Zero-Overlap Boundary:** Keep `>75%` of algorithmic design and full ablation/empirical data out of Tool Demo submissions (`4+1` pages) so Research Track papers remain wholly novel.
- **Falsifiable Go/No-Go Spikes:** Before committing multi-month effort to high-risk tracks (e.g., Spring SAT), pass pre-registered non-tautological gates (`G1` user-starter interaction density, `G2` differential runtime oracle findings, `G3` solver latency/limit budget).

## 6. Windows PowerShell 5.1 + Maven Surefire Cheatsheet

Windows PowerShell 5.1 does not support `&&` — chain commands with `;` (never `&&`, checking `$LASTEXITCODE` when fail-fast is needed) and quote every `-D` flag. Root `pom.xml` binds Surefire `<failIfNoTests>${test.failIfNoTests}</failIfNoTests>` and `<failIfNoSpecifiedTests>${test.failIfNoSpecifiedTests}</failIfNoSpecifiedTests>` (default `true`, fail-closed) and enforces `ReactorModuleConvergence` via `-pl .,...`. Always include `.` in `-pl`, pass **both** override flags on selective multi-module runs, and verify total `Tests run: N > 0` (`0` tests executed is a hard fail). For full `T0-T4` routing and Pareto heavy tests (including `'-DargLine=-Xmx2048m'` for `SpringDifferentialOracleTest`), consult [fast-verification](../fast-verification/SKILL.md):

```powershell
.\mvnw.cmd -o -B -ntp -pl .,analyzer,backend '-Dtest=ChangedTest,ContractTest' '-Dtest.failIfNoTests=false' '-Dtest.failIfNoSpecifiedTests=false' test
```
