---
name: fast-verification
description: >-
  Select the minimal sufficient verification tier (T0-T4), PowerShell 5.1 Maven
  commands, Vanilla Web UI Node checks, fast git-grep anti-overfitting gate,
  and Pareto heavy-test guardrails across Evo's 8 Maven modules and web assets.
  Use before running tests, choosing Maven flags, or verifying code/docs changes.
---

# Fast Verification & Agent Speed Cheatsheet

Apply alongside [00-agent-core.md](../../rules/00-agent-core.md), [verification-before-completion](../verification-before-completion/SKILL.md), and [docs/testing.md](../../../docs/testing.md).

## 1. Dual-Track Cadence (Track A vs. Track B)

Both tracks enforce invariants `I1-I6`, but use distinct verification depths:
- **Track A (Product SWE — Online Web App & Backend):** run `T0 -> T1 -> T2` by default; escalate to `T3` only when a shared module contract, HTTP schema, or Docker/JAR boundary changes. Do **not** run `T4` or `cli` unless explicitly touched/requested (2026-11-27 Online Web App override).
- **Track B (Publication Rigor — CORE A* / Q1 Research & SuperPlan v2):** run `T0 -> T2` + `git grep` anti-overfitting (`0.20 s`) + `T3` determinism/quarantine checks during iteration, and escalate to **mandatory `T4`** (per [publication-research-rigor](../publication-research-rigor/SKILL.md)) before closing masterplan WPs or citing paper tables.

## 2. Five-Tier Verification Ladder (`T0-T4`, Measured Windows PS 5.1)

| Tier | Scope | Wall Budget | When to Run |
|---|---|---:|---|
| **`T0`** | Docs, `.agents/`, governance, `catalog.json` | `< 2 s` | Markdown/rule/index-only edits (`0` Maven/Node runs). |
| **`T1`** | Inner-loop unit / single `#method` / Vanilla JS | `0.4 - 12 s` | Every edit iteration during development. |
| **`T2`** | Batched subsystem contract pack (`-Dtest=A,B,C`) | `10 - 35 s` | Required before handoff for any behavior change. |
| **`T3`** | Cross-module consumer / Web API / determinism | `25 - 90 s` | Shared `analyzer*` DTO/API change, schema change, or caching edit. |
| **`T4`** | Heavy oracles, scale suites, Docker `E6`, Linux 5x | `3 - 20+ min` | Opt-in only: oracle/scale edits, release tags, or Track B paper claims. |

## 3. Core Speed Rules & Guardrails (Empirically Verified)

1. **Always use `.\mvnw.cmd -o -B -ntp` on Windows PS 5.1:** host `mvn 3.9.15` fails Enforcer `[3.9.16]`; `-o` offline saves `+1.05 s` (`+21%`); keep Enforcer ON (skipping saves `<0.3 s`). Chain commands with `;` (never `&&`) and quote every `-D` flag.
2. **Batch related tests in ONE Maven call (`1.92x` faster):** each `.\mvnw.cmd` invocation pays `~4.7-5.8 s` startup overhead. Combine related tests via `-Dtest=A,B,C,D` (`15.1 s` vs `29.0 s` for 4 separate calls) or package globs (`com.evolution.analysis.spring.condition.*Test` runs `262` tests in `10.6 s`).
3. **Selective multi-module runs + zero-test guardrail:** include `.` in `-pl` and pass **both** `'-Dtest.failIfNoTests=false'` and `'-Dtest.failIfNoSpecifiedTests=false'`. Always verify total `Tests run: N > 0` (`N == 0` is a **HARD FAIL**, catching `-Dtest=` typos).
4. **Check `git diff --name-only` (`0.08 s`) before skipping `-am`:** `-am` adds `+8.6-14.6 s` (`+124%`) even when upstream modules are warm. Use `-pl backend '-Denforcer.skipRules=reactorModuleConvergence'` **only** when `0` upstream `analyzer*` files changed (avoids stale `~/.m2` SNAPSHOT JARs). If `analyzer-<X>` changed, run ONE reactor call: `-pl .,analyzer-<X>,backend`.
5. **Reuse default `target/` (`5.41x` faster):** novel `-Dplatform.build.directory=target/<random>` forces full cold `javac` (`25.4 s` vs `4.7 s`) and bloats disk. Use a single stable worker dir only when two processes build the same module concurrently.
6. **Vanilla Web UI (`backend/src/main/resources/web/`) checks (`< 1.6 s`):** no React/Vite build exists. Run `node --check` (`~0.05 s/file`, `1.18 s` all 23 JS files) and `node --test backend/src/test/js/*.test.cjs` (`0.36 s`, `49` tests). Invoke npm/npx via `npm.cmd`/`npx.cmd` on PS 5.1.
7. **Fast Anti-Overfitting Gate (`0.20 s` vs `4.89 s` PS `Select-String`):** run `git grep -n -E 'ServiceLoadBalancer|teastore|logstash\.conf|blog-microservices|piggymetrics|petclinic' -- 'analyzer*/src/main/**/*.java' 'backend/src/main/**/*.java' 'cli/src/main/**/*.java' 'analyzer*/src/main/**/*.py'` AND check untracked files via `git ls-files --others --exclude-standard -- '*/src/main/*'`.

## 4. Change-Path -> Minimal Sufficient Command Matrix (PowerShell 5.1)

| Changed Path Prefix | Tier | Minimal Sufficient Command | Expected Wall |
|---|---|---|---:|
| `docs/**`, `.agents/**`, `AGENTS.md` | `T0` | `git diff --stat`; `python .agents/evals/check-structure.py` *(for `.agents/`)*; `python -c "import json; json.load(open('docs/paper/catalog.json',encoding='utf-8'))"` *(for `docs/paper/`)* | `0.2 - 1.5 s` |
| `backend/src/main/resources/web/**`, `backend/src/test/js/**` | `T1-T2` | `node --check backend/src/main/resources/web/<file>.js`; `node --test backend/src/test/js/workbench-model.test.cjs backend/src/test/js/bridge-model.test.cjs backend/src/test/js/m9-consumer-model.test.cjs` | `0.5 - 1.6 s` |
| `analyzer/.../spring/**` | `T1-T2` | `.\mvnw.cmd -o -B -ntp -pl analyzer '-Denforcer.skipRules=reactorModuleConvergence' '-Dtest=com.evolution.analysis.spring.condition.*Test,com.evolution.analysis.spring.universal.*Test' test` | `11 - 15 s` |
| `analyzer/.../{graph,query,evolution,metrics,policy,assessment,polyglot}/**` | `T1-T2` | `.\mvnw.cmd -o -B -ntp -pl analyzer '-Denforcer.skipRules=reactorModuleConvergence' '-Dtest=com.evolution.analysis.<pkg>.*Test' test` *(add `git grep` gate for `polyglot`)* | `6 - 18 s` |
| `analyzer-maven/**` / `analyzer-treesitter/**` | `T1-T2` | `.\mvnw.cmd -o -B -ntp -pl analyzer-maven '-Denforcer.skipRules=reactorModuleConvergence' test` *(or `-pl analyzer-treesitter`, `~6 s`)* | `6 - 13 s` |
| `analyzer-javaparser/**` | `T1-T2` | `.\mvnw.cmd -o -B -ntp -pl analyzer-javaparser '-Denforcer.skipRules=reactorModuleConvergence' '-Dtest=<ChangedTest>#<method>' test` *(T2: batch `HttpClientRecoveryTest,SourceRecoveryAttributionTest`)* | `11 - 25 s` |
| `analyzer-filesystem/**` | `T1-T2` | `.\mvnw.cmd -o -B -ntp -pl analyzer-filesystem '-Denforcer.skipRules=reactorModuleConvergence' '-Dtest=<ChangedTest>,FileAnalysisWorkspaceTest,GraphProjectionPayloadsTest' test` | `8 - 22 s` |
| `analyzer-execution/**` | `T1-T2` | Java: `-pl analyzer-execution '-Denforcer.skipRules=reactorModuleConvergence' '-Dtest=<ChangedTest>' test`; Python: `python -m unittest discover -s analyzer-execution/src/test/python -p "test_<target>.py" -q` | `1 - 14 s` |
| `backend/.../analysis/**` | `T1-T2` | `.\mvnw.cmd -o -B -ntp -pl backend '-Denforcer.skipRules=reactorModuleConvergence' '-Dtest=DeclarativeTopologyParsersTest,PolyglotScanTest,IncrementalAnalysisTest' test` *(add `git grep` gate)* | `10 - 30 s` |
| `backend/.../{query,auth,github}/**` | `T1-T2` | `.\mvnw.cmd -o -B -ntp -pl backend '-Denforcer.skipRules=reactorModuleConvergence' '-Dtest=ApiSpecificationTest,ArchitectureQueryApiTest,AdvisoryOverlayQuarantineTest' test` *(swap pack for Repair/MCP/Auth)* | `6 - 22 s` |
| Multi-module boundary (`analyzer*` + `backend`) | `T3` | `.\mvnw.cmd -o -B -ntp -pl .,analyzer,backend '-Dtest=<AnalyzerTest>,ApiSpecificationTest,PublishedReportsTest' '-Dtest.failIfNoTests=false' '-Dtest.failIfNoSpecifiedTests=false' test` | `15 - 35 s` |
| `cli/**` *(only if requested; or public signature check)* | `T1-T2` | Signature compile check: `-pl .,cli test-compile` (`~6 s`); targeted: `-pl cli '-Denforcer.skipRules=reactorModuleConvergence' '-Dtest=<ChangedTest>,TerminalReportsTest' test` | `6 - 16 s` |
| `benchmarks/**` | `T2/T4` | `T2`: `python -m unittest discover -s benchmarks/e0-publication-suite -p "test_*.py"` (`~10 s`); `T4`: isolated Docker `evo-workbench-online:latest` (no host `-v` mounts) | `10 s / 5-20m` |

## 5. Pareto Top-20 Heavy JUnit Tests Watchlist (`4.8%` Suites = `63.8%` Runtime)

Avoid bare module `test` on `analyzer-filesystem`, `analyzer-javaparser`, `backend`, or `cli` in inner loops—the 20 classes below consume **`575.3 s` / `901.2 s`** of recorded runtime (plus live `E5-v3` & `JarTypeSolver` spikes):

| Tier | Module | Heavy Test Classes (Measured Time) | Safe `T1/T2` Alternative |
|---|---|---|---|
| **`T4` (`>60 s`)** | `backend` | `SpringDifferentialOracleTest` (`235.0 s` live `E5-v3` — **pass `'-DargLine=-Xmx2048m'`** to avoid Windows pagefile OOM), `SpringScaleAndAi4seBenchmarkTest` (`138.4 s`) | `WorkbenchSpringSatIntegrationTest` + `analyzer` `spring.condition.*Test` (`~15 s`) |
| **`T4` (`>60 s`)** | `analyzer-javaparser` | `SourceToSpringPlanTest` (`122.4 s` wall / `44.5 s` body; `~70 s` `JarTypeSolver` teardown gap) | `SourceToSpringPlanTest#<singleMethod>` (`11.5 s` warm) during `T1` |
| **`T4` (`>60 s`)** | `analyzer-filesystem` | `StreamingRepositoryAcquirerTest` (`116.0 s`), `StreamingScaleTest` (`75.9 s`) | `FileAnalysisWorkspaceTest,GraphProjectionPayloadsTest` (`~10 s`) |
| **`T3` (`20-45 s`)** | `backend` | `WorkbenchApiMainTest` (`40.1 s`), `CapturedRepairVerificationTest` (`31.9 s`), `ColdWarmDeterminismTest` (`23.0 s`) | `ApiSpecificationTest`, `SafePatchApplierTest`, `IncrementalAnalysisTest` (`5-12 s`) |
| **`T3` (`20-45 s`)** | `analyzer-filesystem` | `PartitionedGraphStoreTest` (`35.1 s`), `IncrementalAcquisitionDeltaTest` (`32.2 s`), `SmallBlobPackTest` (`27.9 s`) | Run only when SQLite/CAS/partition storage changes directly |
| **`T3` (`20-45 s`)** | `analyzer-execution` / `cli` | `ExecutionControllerTest` (`26.6 s`), `EvoScoreV2AndIncrementalCliTest` (`37.2 s`), `EvoAppTest` (`28.6 s`) | Targeted unit test or `-pl .,cli test-compile` (`~6 s`) |
| **`T2/T3` (`15-20 s`)** | `analyzer-filesystem` / `backend` | `SemanticEmbeddedPublicationTest` (`19.6 s`), `Stage0HardeningAndUnsiloTest` (`19.5 s`), `ComprehensiveSelectionTest` (`17.4 s`), `SemanticRepositoryCensusTest` (`17.2 s`), `LocalAnalysisRunnerTest` (`15.6 s`), `Moonshot1ClosedLoopRepairTest` (`15.4 s`) | Batch selectively only when modifying their owning subsystem |
