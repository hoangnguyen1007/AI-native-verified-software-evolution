# Platform Test Selection & Fast Verification Protocol

The Maven Wrapper pins Maven `3.9.16`; use JDK `21`. These commands build and test trusted platform code. Native tests replay captured evidence; they never run an untrusted target lifecycle on the host or launch a worker to repair missing fixtures.

## Lean verification for the demo

- **Docs only (`T0`):** inspect `git diff`, check changed links, UTF-8 integrity, and status consistency. Zero Maven or Node builds.
- **Behavior change (`T1–T2`):** run the smallest regression or batched contract check that differentiates success from failure. Reuse the result at handoff if the relevant inputs are unchanged. Add a direct consumer check (`T3`) only when a shared boundary/contract changed or the focused test leaves a material risk uncovered.
- **Demo / Online Web App integration (`T3`):** run the affected API/UI/workbench checks (or the pinned local journey once after meaningful integration changes). For the repair verifier, cover the intended baseline/candidate delta and one decisive rejection case. Replayed model text and actual sandbox execution retain separate statuses.
- **Broader / publication scope (`T4`):** full reactor, Pareto heavy/oracle/scale suites, native VM campaigns, and Linux multi-repeat benchmarks are **not** routine prerequisites. Run `T4` only for a concrete regression risk or a publication/milestone claim that `T1–T3` cannot support. Always record exact executed scope, failures, and unrun checks.

The commands and campaign denominators below describe available checks and historical evidence. Selection depends on the actual change, not the number of modules touched.

## 5-Tier Fast Verification & Agent Optimization Protocol (`T0–T4`)

### Track A (Product SWE) vs. Track B (Publication Rigor) Separation

Both tracks strictly uphold invariants `I1–I6` (Zero-Host Execution, SHA-256 Evidence, Closed Denominator, Trust-Tier Quarantine, Anti-Overfitting, Storage Hygiene), **but use separate verification cadences** so routine Online Web App & backend work stays fast (`0.5–35 s`) while publication claims enforce full scientific rigor (`T4`):

| Tier | Name | Wall Budget | Track A — Product SWE (Online Web App / Backend) | Track B — Scientific Publication (CORE A*/Q1 SuperPlan v2) |
|---|---|---:|---|---|
| **`T0`** | **Docs / Governance / Config** | **`< 2 s`** | Markdown/rules/config-only edits: `git diff`, link & UTF-8 checks, `python .agents/evals/check-structure.py`. Zero Maven/Node. | Same + validate `docs/paper/catalog.json` JSON/citation keys when touching `docs/paper/`. |
| **`T1`** | **Inner-Loop Fast Check** | **`0.4 – 12 s`** | Every code iteration: `node --check` + `node --test` (Web UI) OR single offline `.\mvnw.cmd -o -B -ntp -pl <mod> '-Denforcer.skipRules=reactorModuleConvergence' '-Dtest=Class#method' test`. | Same during algorithm/solver prototyping (e.g., `IncrementalCdclReasonerTest`, `ConeOfInfluenceSlicerTest`). |
| **`T2`** | **Subsystem Contract Pack (Batched)** | **`10 – 35 s`** | **Mandatory before handoff** for any behavior change: ONE comma-batched `-Dtest=A,B,C` (or package glob) covering changed class + contract + negative control. | Same + `git grep` `I5` anti-overfitting gate (`0.20 s`) + `AdvisoryOverlayQuarantineTest` on extractor/graph changes. |
| **`T3`** | **Cross-Module / Consumer / Web-API** | **`25 – 90 s`** | Only when a shared `analyzer*` boundary, HTTP/MCP API schema, or Docker/workbench artifact changes (`-pl .,modA,modB` batched; exclude `cli` unless signature affects it or explicitly requested). | Same + `ColdWarmDeterminismTest` (`~23 s`) / `test_anti_overfitting_and_holdout.py` (`~9.5 s`) when touching caching, hashing, or topology extractors. |
| **`T4`** | **Heavy Oracle / Scale / Sandbox / Publication Gate** | **`3 – 20+ min`** | **NEVER run blindly in routine SWE loops.** Run only when directly editing heavy oracles/scale stores, native workers, or release tags. | **Mandatory before closing `WP*` or claiming paper numbers:** `SpringDifferentialOracleTest` (`-DargLine=-Xmx2048m`), `SpringScaleAndAi4seBenchmarkTest`, isolated Docker `E6` (`17/17` `microSecEnD`), blind `Micans` holdout, Linux `n >= 5` `Median ± IQR` 3-way timing (`Cold` / `Warm NO_CHANGE` / `Warm 1-File Edit`). |

### Empirically Measured Windows PowerShell 5.1 Baselines

Measured sequentially on the reference Windows host (Intel Core i5-14400F `10C/16T`, `31.8 GB` RAM, Windows PowerShell `5.1`, Oracle JDK `21`, Maven Wrapper `3.9.16`, Node `v26.13.0`; `2026-11-28`):

| Operation / Pattern | Measured Wall | Key Operational Lesson |
|---|---:|---|
| `docs/paper/catalog.json` Python query | `0.07 s` | Always query `catalog.json` before opening PDFs. |
| `git grep -l <Symbol>` vs CLI `graphify query` | `0.20 s` vs `5.0–10.0 s` | **`25×–51×` faster** for exact symbol/caller lookup; reserve `graphify` for multi-hop architecture/community queries. |
| `git grep -n -E` anti-overfitting gate vs PS `Select-String` | `0.20 s` vs `4.89 s` | **`24.3×` faster**; pair with `git ls-files --others --exclude-standard -- 'analyzer*/src/main/*' 'backend/src/main/*' 'cli/src/main/*'` to cover untracked files. |
| Vanilla Web UI `node --check` (`23` JS files) + `node --test` (`49` tests) | `1.18 s + 0.36 s` | **`~1.5 s` full Web UI syntax + model verification** (no React/Vite build exists). |
| Warm offline single test (`-o -pl backend '-Denforcer.skipRules=reactorModuleConvergence' '-Dtest=ApiSpecificationTest' test`) | `4.70 s` | Fastest valid Java loop (`1.80 s` wrapper+JVM + `2.62 s` Maven/Surefire + `0.29 s` test). Including `.` (`-pl .,backend`) costs only `+0.30 s`; full Enforcer costs `<0.3 s` (never pass `-Denforcer.skip=true`; skip ONLY `reactorModuleConvergence` on single-module `-pl <mod>` runs). |
| Omitting `-o` (online mode on warm cache) | `+1.05 s` (`+21%`) | Always pass `-o` during local inner loops. |
| Batching `4` tests in ONE `-Dtest=A,B,C,D` vs `4` separate `mvnw` calls | `15.14 s` vs `29.03 s` | **`1.92×` faster (`-47.8%`)** — always batch same-module tests into one command. |
| Unnecessary `-am` on `backend` (`test-compile`, warm / 1st dirty) | `+8.56 s` / `+14.56 s` | Avoid `-am` when upstream `analyzer*` modules are unchanged (verify via `git diff --name-only`). |
| Novel `-Dplatform.build.directory=target/<new>` cold compile | `25.42 s` (`5.41×` slower) | Reuse default `target/` in sequential runs; avoid creating stray build dirs. |
| `analyzer` package glob (`com.evolution.analysis.spring.condition.*Test`, `262` tests) | `10.63 s` | Domain package globs in `analyzer` are exceptionally fast for `T2`. |
| `SourceToSpringPlanTest#singleMethod` (warm) vs full class (`81` tests) | `11.49 s` vs `122.42 s` | **`10.65×` faster** — use `#method` in `T1` (`~70 s` full-class teardown is Windows Defender + `deleteOnExit` ZIPs). |
| `SpringDifferentialOracleTest` (`E5-v3`, `6` tests) | `234.96 s` (`3m 55s`) | Strictly **`T4`**; cap heap with `'-DargLine=-Xmx2048m'` to avoid Windows pagefile pressure. |

### Windows PowerShell 5.1 & Multi-Module Maven Rules

1. **Wrapper & offline flags:** Always invoke `.\mvnw.cmd -o -B -ntp` (host `mvn 3.9.15` fails Maven Enforcer `[3.9.16]`). Never pass `-Denforcer.skip=true` (`<0.3 s` cost); skip ONLY `reactorModuleConvergence` (`'-Denforcer.skipRules=reactorModuleConvergence'`) on intentional single-module `-pl <mod>` runs without parent `.`.
2. **PowerShell 5.1 syntax:** Chain commands with `;` (never `&&` in PS 5.1) and single-quote every `-D...` property (`'-Dtest=...'`). For Node tooling if ever invoked, use `node`, `npm.cmd`, or `npx.cmd` (unsigned `.ps1` shims are blocked by ExecutionPolicy).
3. **Selective multi-module test overrides:** Root `pom.xml` parameterizes `<failIfNoTests>${test.failIfNoTests}</failIfNoTests>` and `<failIfNoSpecifiedTests>${test.failIfNoSpecifiedTests}</failIfNoSpecifiedTests>` (both default `true` so bare `test` stays fail-closed). For selective multi-module `-pl .,modA,modB` runs, pass **BOTH** `'-Dtest.failIfNoTests=false'` AND `'-Dtest.failIfNoSpecifiedTests=false'`, and **ALWAYS verify total `Tests run: N > 0`** (`N == 0` is a **HARD FAIL** regardless of exit code!).
4. **Comma-batching & default `target/`:** Batch all related tests in one module into ONE `-Dtest=TestA,TestB,TestC` or package glob. Reuse default `target/` for sequential runs; isolate `-Dplatform.build.directory=target/<worker>` **only** when concurrent IDE/swarm writers target the same module, and reuse that single path.
5. **Stale `.m2` SNAPSHOT guardrail (before skipping `-am`):** Check `git diff --name-only` (`~0.08 s`) first. Use single-module `-pl backend '-Denforcer.skipRules=reactorModuleConvergence'` **only** when `0` upstream `analyzer*` files changed since the last `install`. If any upstream `analyzer*` module changed, run ONE reactor command `-pl .,analyzer-<changed>,backend` (which resolves live reactor `target/classes` directly without rebuilding all 6 modules via `-am`).
6. **`cli` signature guardrail:** Per the 2026-11-27 Online Web App override, omit `cli` from routine runs. If a change alters a public signature in `backend` or `analyzer*` referenced by `cli/` (check via `git grep <symbol> -- cli/` in `0.05 s`), run ONE `.\mvnw.cmd -o -B -ntp -pl .,cli test-compile` (`~6 s`) without running `cli`'s `70 s` tests or `72 MB` shading.
7. **Avoid `package`/`verify` in inner loops:** Both `backend` and `cli` run `maven-shade-plugin` (`~72 MB` fat JARs) during `package`. Use `test` for `T1–T3`; run `package` only when building a deliverable JAR or Docker image.

### Change-Path -> Tier & Command Routing Matrix

| Changed Path / Package Prefix | Tier | Minimal-Sufficient Command (Windows PowerShell 5.1) | Budget | Escalate to `T3`/`T4` Only When... |
|---|---|---|---:|---|
| `docs/**`, `.agents/**`, `README.md`, `AGENTS.md` | **`T0`** | `git diff --stat`; `python .agents/evals/check-structure.py` *(for `.agents/`)*; JSON check on `docs/paper/catalog.json` *(for `docs/paper/`)* | `0.2–1.5 s` | Never (`0` Maven/Node runs). |
| `backend/src/main/resources/web/**`, `backend/src/test/js/**` | **`T1–T2`** | `node --check backend/src/main/resources/web/<changed>.js`; `node --test backend/src/test/js/workbench-model.test.cjs backend/src/test/js/bridge-model.test.cjs backend/src/test/js/m9-consumer-model.test.cjs` | `0.5–1.6 s` | HTTP/MCP payload contract changed -> add `ApiSpecificationTest` (`+5 s`). |
| `analyzer/.../spring/**` (`binding`, `condition`, `registration`, `truth`, `universal`) | **`T1–T2`** | **T1:** `.\mvnw.cmd -o -B -ntp -pl analyzer '-Denforcer.skipRules=reactorModuleConvergence' '-Dtest=<ChangedTest>' test`<br>**T2:** `.\mvnw.cmd -o -B -ntp -pl analyzer '-Denforcer.skipRules=reactorModuleConvergence' '-Dtest=com.evolution.analysis.spring.condition.*Test,com.evolution.analysis.spring.universal.*Test' test` | `5–15 s` | SAT/CDCL/COI/Starter semantics changed -> `T3` `WorkbenchSpringSatIntegrationTest` (`~8 s`) or `T4` `SpringDifferentialOracleTest` (`235 s`, `'-DargLine=-Xmx2048m'`). |
| `analyzer/.../{graph,query,evolution,metrics,policy,assessment,contract}/**` | **`T1–T2`** | `.\mvnw.cmd -o -B -ntp -pl analyzer '-Denforcer.skipRules=reactorModuleConvergence' '-Dtest=com.evolution.analysis.<pkg>.*Test' test` | `6–18 s` | `ArchitectureQueryService` evidence/JSON contract changed -> `T3` `-pl .,analyzer,backend '-Dtest=ArchitectureQueryServiceTest,ApiSpecificationTest,PublishedReportsTest' '-Dtest.failIfNoTests=false' '-Dtest.failIfNoSpecifiedTests=false' test`. |
| `analyzer/.../{polyglot,frontend,buildmodel,readiness,javaevidence,acquisition,ingestion}/**` | **`T1–T2`** | `.\mvnw.cmd -o -B -ntp -pl analyzer '-Denforcer.skipRules=reactorModuleConvergence' '-Dtest=com.evolution.analysis.<pkg>.*Test' test`; `git grep -n -E 'ServiceLoadBalancer\|redis-cart\|teastore\|piggymetrics\|petclinic\|shopizer' -- 'analyzer*/src/main/**/*.java' 'backend/src/main/**/*.java' 'cli/src/main/**/*.java' 'analyzer*/src/main/**/*.py'` | `6–12 s` | Shared DTO/projection changed -> add `backend` `PolyglotScanTest,DeclarativeTopologyParsersTest`. |
| `analyzer-maven/**` | **`T1–T2`** | `.\mvnw.cmd -o -B -ntp -pl analyzer-maven '-Denforcer.skipRules=reactorModuleConvergence' test` *(all 12 classes)* | `11–13 s` | Reactor resolution changed -> `T3` `MavenScanReactorTest` (`backend`). |
| `analyzer-treesitter/**` | **`T1`** | `.\mvnw.cmd -o -B -ntp -pl analyzer-treesitter '-Denforcer.skipRules=reactorModuleConvergence' test` *(1 class, 24 tests)* | `~6 s` | Bridge emission changed -> `PolyglotScanTest` (`backend`). |
| `analyzer-javaparser/**` | **`T1–T2`** | **T1:** `.\mvnw.cmd -o -B -ntp -pl analyzer-javaparser '-Denforcer.skipRules=reactorModuleConvergence' '-Dtest=<ChangedTest>#<method>' test`<br>**T2:** `'-Dtest=<ChangedTest>,HttpClientRecoveryTest,SourceRecoveryAttributionTest'` | `11–25 s` | Spring ingestion plan changed -> `SourceToSpringPlanTest#<method>` (`11.5 s`), full class (`122 s`) pre-handoff only. |
| `analyzer-filesystem/**` | **`T1–T2`** | `.\mvnw.cmd -o -B -ntp -pl analyzer-filesystem '-Denforcer.skipRules=reactorModuleConvergence' '-Dtest=<ChangedTest>,FileAnalysisWorkspaceTest,GraphProjectionPayloadsTest' test` | `8–22 s` | SQLite schema/chunking changed -> `PartitionedGraphStoreTest` (`35 s`) + `StoragePerfParityTest` (`10 s`). Scale -> `T4` `StreamingScaleTest`. |
| `analyzer-execution/**` | **`T1–T2`** | **Java:** `.\mvnw.cmd -o -B -ntp -pl analyzer-execution '-Denforcer.skipRules=reactorModuleConvergence' '-Dtest=<ChangedTest>' test`<br>**Python:** `python -m unittest discover -s analyzer-execution/src/test/python -p 'test_<target>.py' -q` | `1–14 s` | SCIP format -> `Moonshot5StreamableMcpAndScipTest`. Worker protocol -> `T4` `ExecutionControllerTest` + native capture. |
| `backend/.../analysis/**` (Topology, Incremental, Spring wiring) | **`T1–T2`** | **Topology:** `.\mvnw.cmd -o -B -ntp -pl backend '-Denforcer.skipRules=reactorModuleConvergence' '-Dtest=DeclarativeTopologyParsersTest,PolyglotScanTest,ComprehensiveSelectionTest' test` + `git grep` `I5`<br>**Incremental:** `'-Dtest=IncrementalAnalysisTest,IncrementalDeltaEngineTest,AnalysisResultReuseTest'`<br>**Spring:** `'-Dtest=WorkbenchSpringSatIntegrationTest,SpoolSpringTest'` | `10–30 s` | SHA-256 determinism -> `T3` `ColdWarmDeterminismTest` (`23 s`). SAT/microSecEnD -> `T4` oracle / Docker `E6`. |
| `backend/.../query/**` (API, Repair, MCP, Search, Advisory) | **`T1–T2`** | **API:** `'-Dtest=ApiSpecificationTest,ArchitectureQueryApiTest,PublishedReportsTest'`<br>**Repair:** `'-Dtest=SafePatchApplierTest,RepairPatchMyersTest,Moonshot1ClosedLoopRepairTest'`<br>**MCP/SCIP:** `'-Dtest=WorkbenchMcpTest,Moonshot5StreamableMcpAndScipTest,ApiSpecificationTest'`<br>**Search/Trace/Advisory:** `'-Dtest=Moonshot2SearchAndRrfTest,Moonshot3VisualDsmAndChatStoreTest,Moonshot4TraceAndSliceApiTest,AdvisoryOverlayQuarantineTest'` | `6–22 s` | Repair sandbox pipeline -> `T3` `CapturedRepairVerificationTest` (`32 s`). Server wiring -> `Stage0HardeningAndUnsiloTest` (`19.5 s`) / `WorkbenchApiMainTest` (`40 s`). |
| `backend/.../{auth,github}/**` | **`T1–T2`** | `.\mvnw.cmd -o -B -ntp -pl backend '-Denforcer.skipRules=reactorModuleConvergence' '-Dtest=com.evolution.backend.auth.*Test,com.evolution.backend.github.*Test,Wave1SecurityAndAgentTest' test` | `10–15 s` | OAuth/session filter change -> add `Stage0HardeningAndUnsiloTest`. |
| `cli/**` *(frozen unless explicitly requested)* | **`T1–T2`** | `.\mvnw.cmd -o -B -ntp -pl cli '-Denforcer.skipRules=reactorModuleConvergence' '-Dtest=<ChangedTest>,TerminalReportsTest' test` | `7–16 s` | Explicit CLI release/`E0` parity -> `EvoAppTest,ScanJourneyTest` (`39 s`). |
| `benchmarks/**` | **`T2–T4`** | **T2:** `python -m unittest discover -s benchmarks/e0-publication-suite -p 'test_*.py'`<br>**T4:** Isolated Docker `evo-workbench-online:latest` (no host `-v` mounts) + `--resume` + `try/finally docker rm -f`. | `10 s` / `5–20+ m` | Keep `T4` publication campaigns strictly out of routine Track A PRs. |

### Pareto Top-20 Heavy Suites Awareness (`T4` / Explicit Only)

Across `417` recorded JUnit suites (`901.2 s` historical sum, `>18 min` live with `E5-v3`), **`84.2%` (`351/417`) finish in `< 2.0 s`**, while the **Top `20` suites (`4.8%`) consume `63.8%` (`575.3 s`)** of total runtime. Never invoke bare module-wide `test` on `backend`, `analyzer-filesystem`, `analyzer-execution`, `analyzer-javaparser`, or `cli` during inner loops without selecting specific classes or `-Pfast-tests`:

- **Multi-minute `T4` suites (`> 60 s`):** `SpringDifferentialOracleTest` (`235 s` live; always pass `'-DargLine=-Xmx2048m'`), `SpringScaleAndAi4seBenchmarkTest` (`138 s`), `SourceToSpringPlanTest` (`122 s` full class — use `#method` in `T1`), `StreamingRepositoryAcquirerTest` (`116 s`), `StreamingScaleTest` (`76 s`).
- **Heavy `T3/T4` suites (`15–45 s`):** `WorkbenchApiMainTest` (`40 s`), `EvoScoreV2AndIncrementalCliTest` (`37 s`), `PartitionedGraphStoreTest` (`35 s`), `IncrementalAcquisitionDeltaTest` (`32 s`), `CapturedRepairVerificationTest` (`32 s`), `EvoAppTest` (`29 s`), `SmallBlobPackTest` (`28 s`), `ExecutionControllerTest` (`27 s`), `ColdWarmDeterminismTest` (`23 s`), `SemanticEmbeddedPublicationTest` (`20 s`), `Stage0HardeningAndUnsiloTest` (`20 s`), `ComprehensiveSelectionTest` (`17 s`), `SemanticRepositoryCensusTest` (`17 s`), `LocalAnalysisRunnerTest` (`16 s`), `Moonshot1ClosedLoopRepairTest` (`15 s`).

## Select the intended scope

| Command selection | Included | Excluded / not established |
|---|---|---|
| `test` or `verify`, without a test profile | All self-contained tests, including Spring integration tests and heavy `T4` suites | `native-campaign`; native acceptance is **NOT RUN** by this command |
| `-Pfast-tests` | Self-contained tests except those tagged `integration` / `native-campaign` (note: untagged Pareto heavy suites still run unless filtered by `-Dtest`) | Spring `integration`-tagged suites and `native-campaign` |
| `-Pnative-campaign` | Only tests tagged `native-campaign`, on explicitly supplied captured inputs | Other unit/integration tests |

The profiles are mutually exclusive. Missing native inputs fail in Maven `validate`, rather than producing skipped tests. The five native suites have no property-based JUnit disable conditions. A zero-test selection fails by default (`test.failIfNoTests=true`, `test.failIfNoSpecifiedTests=true`), including an explicitly selected native class without its profile. A green build proves only the selected scope; tag exclusions do not mean those tests passed. In an IDE, exclude the `native-campaign` tag for the self-contained selection; selecting native tests directly requires the same captured inputs and trusted fixture classpath.

The two `@Tag("integration")` self-contained suites, `SourceToSpringPlanTest` (81 cases) and `ResourceFieldIngestionTest` (12), still run by default when no profile is passed. Select a specific method (`ClassName#methodName`) when it covers the behavior under test; select complete classes only at pre-handoff when needed.

The JMOD adapter avoids recompressing its temporary solver JAR by using compression level `0`. Original artifact SHA-256 verification, class bytes, duplicate checks, sorted entries, and timestamps remain unchanged. The view is built in memory; JavaParser `3.28.2`'s `JarTypeSolver(InputStream)` also materializes a temporary file with `deleteOnExit`, which can consume additional memory and temporary disk until the JVM exits. The captured native campaign uses an explicit `1,536 MiB` test heap; this is not a general capacity certificate.

```powershell
# Inner loop (T1): one relevant method, including a method in an integration class.
.\mvnw.cmd -o -B -ntp -pl analyzer-javaparser '-Denforcer.skipRules=reactorModuleConvergence' '-Dtest=ClassName#methodName' test

# Broader local iteration on analyzer-javaparser (excludes @Tag("integration")).
.\mvnw.cmd -o -B -ntp -pl analyzer-javaparser '-Denforcer.skipRules=reactorModuleConvergence' -Pfast-tests test

# Pre-handoff check when a change affects both Spring ingestion contracts.
.\mvnw.cmd -o -B -ntp -pl analyzer-javaparser '-Denforcer.skipRules=reactorModuleConvergence' '-Dtest=SourceToSpringPlanTest,ResourceFieldIngestionTest' test
```

## Python worker/SDK controls

Install the host test dependencies from the checked-in [requirements file](../analyzer-execution/src/test/python/requirements.txt) into an explicit virtual environment. From the repository root in PowerShell:

```powershell
python -m venv .venv
$workerPython = (Resolve-Path '.venv/Scripts/python.exe').Path
& $workerPython -m pip install -r analyzer-execution/src/test/python/requirements.txt
& $workerPython -m unittest discover -s analyzer-execution/src/test/python -p 'test_polyglot_sdk*.py' -q
```

On POSIX use `.venv/bin/python`. This gives the tests `pycdlib`, `cryptography`, `paramiko`, and `zstandard` through the selected interpreter; no machine-specific cache path is required. For an already provisioned `pip --target` directory, set `PYTHONPATH` explicitly instead.

These controls do not provision a VM or qualify a native language pack. The separate `test_*_native.py` suites require their real pinned SDKs (e.g., `test_python_native.py` needs Node on `PATH` and Pyright `1.1.396` at `.evo/tools/m845-pyright-api-1.1.396/package`). They analyze fixed first-party fixture strings only; analyzed repository code/builds still run exclusively in the registered isolated lane.

## Frontend architecture MVP

This is the explicit M8.4 Step-5/Task-2 captured-context lane, separate from the campaign below. [The execution record](tasks/m8/04-step5-step2-execution.md) owns actual passes/remaining exits; [the contract](architecture/m8-frontend-architecture.md) describes requests and query semantics. Source → capture → `ArchitectureCapture.read` → `ArchitectureCaptureQuery.page` is usable; automatic whole-repository HTTP/UI/federation is not claimed by this lane.

Authored host controls require pinned Node and provisioned exact SDK bytes at `.evo/tools/m845-architecture`; routing controls additionally require `.evo/tools/m845-native-routing-v2`. The [base pin](../analyzer-execution/src/main/resources/polyglot/architecture/toolchain.json) and [routing pin](../analyzer-execution/src/main/resources/polyglot/architecture/routing-toolchain.json), with adjacent lockfiles, define runtime/lock/installed-tree identities. Vue SFC controls additionally use `.evo/tools/m845-vue-language-3.3.13`; native Nuxt controls use `.evo/tools/m845-nuxt-schema-v1` (adjacent `vue-toolchain.json` and `nuxt-toolchain.json`). SDKs, package caches, and raw captures stay outside Git. Missing/changed prerequisites fail explicitly with zero host target-build fallback.

```powershell
# One affected data-only/native-parser selection on fixed platform-authored inputs.
node --test "--test-name-pattern=literal template fragments|native source-map chains|Vue native SFC/script/template compilation|invalid checker context cannot|mutually exclusive browser and server" analyzer-execution/src/test/resources/polyglot/architecture-worker.test.cjs
python -m unittest discover -s analyzer-execution/src/test/python -p test_architecture_guest.py -q

# Reader unit checks. No SDK or VM is needed for these immutable compact fixtures.
.\mvnw.cmd -o -B -ntp -pl analyzer '-Denforcer.skipRules=reactorModuleConvergence' '-Dtest=ArchitectureCaptureTest,ArchitectureCaptureAuditTest' test
```

`ArchitectureNativeCaptureIT` is selected explicitly with both `architecture.captureRoot` and `architecture.labels` pointing to a completed immutable worker root and its precommitted independent labels. It verifies output census, source bytes, frozen controls, VM Off identity, CAS import, bounded navigation, and cold reopen without starting a worker or obtaining missing SDKs. Native guest Node is separately pinned `22.23.4` on Linux. `qualified=false` and method gaps remain expected.

`ArchitectureNativeReplayIT` requires `architecture.replayRoot`, `architecture.replayLabels`, and `architecture.replayedOutput`. It admits reproduced fixed-authored bytes only when SHA-256 and length equal both authenticated VM-reported native outputs, rejects changed bytes, and reports `INCOMPLETE_NATIVE_DIGEST_MATCHED_REPLAY`.

Pass `--evidence-root <existing private-cache parent>` to `sdk_worker.py register` to create an owner/System-only capture child on `D:`. `Z:` contains only necessary registered worker state; data-only replay needs neither a VM nor `Z:` writable space.

## Captured native campaign

Prepare four explicit absolute paths, using forward slashes on Windows:

| Property | Required evidence |
|---|---|
| `native.fixture.root` | Original pipeline cohort: `native-context-check-v2.json`, `native-wiring-inputs-v1.json`, complete immutable CAS |
| `native.failure.root` | Actual failed-processor capture: `native-byte-import.json`, `native-input-import.json`, complete immutable CAS |
| `native.spring.root` | Native Spring cohort: context check, input binding, and complete immutable CAS; exact framework artifacts |
| `native.coremvp.root` | Current operator-registered Core MVP publication: `setup.json`, `export-request.json`, publication CAS, and matching private registration/controller identity |

File existence is a preflight check, not authenticity; real readers verify bytes, hashes, scope, lineage, registration, and version identity. CI without provisioned matching evidence runs self-contained tests and reports native acceptance as `NOT RUN`.

```powershell
.\mvnw.cmd -o -pl analyzer-execution,analyzer-javaparser,backend -Pnative-campaign `
  "-Dnative.fixture.root=$pipeline" "-Dnative.failure.root=$failure" `
  "-Dnative.spring.root=$spring" "-Dnative.coremvp.root=$coreMvp" `
  "-Dplatform.build.directory=target/native-campaign" "-DargLine=-Xmx1536m" `
  "-Denforcer.skipRules=reactorModuleConvergence" test
```

The complete selection is **22 tests / 5 suites**: `NativePreparedPipelineIntegrationTest` (`10`), `NativeSemanticProjectionTest` (`2`), `NativeSpringContinuationTest` (`3`), `PreparedJavaAnalysisRunnerTest` (`6`), and `NativeCoreMvpEvidenceReaderTest` (`1`). Check Surefire reports in each selected module's build directory for all five suites, `22` executed, zero failures/errors/skips. Native replay under the public test policy does not establish production semantic qualification or unrestricted executor certification. See [the Step-4 Phase-2 execution record](tasks/m8/04-step4-phase2-execution.md).

## M8.4 Step 5 / Bước 3 — bounded bridge checks

The [Step-3 execution record](tasks/m8/04-step5-step3-execution.md#verification) owns the exact selected Maven commands, counts, and retained fixture paths (`BridgeAssemblyTest`, `ArchitectureBridgesTest`, `OpenApiBridgeDescriptorsTest`, `SpringBridgeImportTest`, `BridgePublicationStoreTest`, `BridgeQueryApiTest`, `BridgeCliTest`). Use changed-module checks and direct consumers, not the full reactor.

```powershell
# Vanilla Web UI bridge/workbench syntax and unit model checks (~1.5 s)
node --version
node --check backend/src/main/resources/web/bridges.js
node --test backend/src/test/js/bridge-model.test.cjs backend/src/test/js/workbench-model.test.cjs
```

`BridgeJourneyIT` (pinned Node/architecture/routing/Kotlin/Pyright/AsyncAPI/protobufjs SDKs) and `BridgeMixedReplayIT` (`-Dbridge.fixtureRoot=<existing authored fixture>`, `-Dbridge.expectedSources=33` for v3) are pre-handoff `T4` integrations (body `~115 s`), not inner-loop commands. Narrow pure tests (`BridgeTopologyTest`, `LanguageBridgeImportTest`) serve query/import iterations:

```powershell
.\mvnw.cmd -o -q -pl backend '-Dplatform.build.directory=target/step5-bridges' '-Denforcer.skipRules=reactorModuleConvergence' '-Dtest=BridgeJourneyIT' "-Dbridge.node=$bridgeNode" "-Dbridge.python=$bridgePython" "-Dbridge.tools=$bridgeTools" "-Dbridge.routing=$bridgeRouting" "-Dbridge.languages=$bridgeLanguages" "-Dbridge.native=$bridgeNative" test
.\mvnw.cmd -o -q -pl backend '-Dplatform.build.directory=target/step5-bridges' '-Denforcer.skipRules=reactorModuleConvergence' '-Dtest=BridgeMixedReplayIT' "-Dbridge.fixtureRoot=$bridgeFixture" test
node --test analyzer-execution/src/test/resources/polyglot/architecture-bridges.test.cjs
```

The [quality follow-up](tasks/m8/04-step5-step3-execution.md#quality-follow-up--2026-11-02) owns the 51-case selection, native config/SDK controls, fixture v3, and timings. `PythonBridgeNativeIT` reuses a verified SDK via process-local `EVO_PYRIGHT_SDK` (pinned Pyright `1.1.396`). Bridge evidence remains unqualified producer evidence; always report concrete bindings and evidence obligations separately.

## M9.2 explicit evidence checks

The [Task-2 controls](tasks/m9/02-cases.json) register the semantic checks; ordinary tests never recreate deleted workers or captures. `SpringBindingOracleIT` reuses an explicitly selected Maven2 cache without weakening its seven SHA-256 pins; `M9InventoryCensusIT` takes frozen registry/corpus/inventory roots and a new, nonexistent output directory:

```powershell
.\mvnw.cmd -o -q -pl analyzer '-Denforcer.skipRules=reactorModuleConvergence' '-Dtest=SpringBindingOracleIT' '-Dspring.oracle.repository=C:/Users/Admin/.m2/repository' test
.\mvnw.cmd -o -q -pl backend '-Denforcer.skipRules=reactorModuleConvergence' '-Dtest=M9InventoryCensusIT' "-Dm9.registry=<absolute-checkout>/docs/tasks/m9/registry.json" "-Dm9.corpusRoot=<explicit-corpus-root>" "-Dm9.inventoryRoot=<registered-inventory-root>" "-Dm9.censusOutput=<new-external-output-directory>" test
```

Native TypeScript fixture source/NDJSON files must retain their checked-in LF bytes (pinned by `.gitattributes`); converting them to CRLF changes source hashes and UTF offsets.

## M9.3 graph, assessment and consumer controls

The [M9.3 record](tasks/m9/03-execution.md) and [registered controls](tasks/m9/03-cases.md) define the golden/degraded inputs and packaged CLI/workbench parity checks:

```powershell
.\mvnw.cmd -o -q -pl .,analyzer,backend,cli '-Dtest.failIfNoTests=false' '-Dtest.failIfNoSpecifiedTests=false' `
  '-Dplatform.build.directory=target/m9.3' `
  '-Dtest=InMemoryGraphStoreTest,PublishedReportsTest#actualM6FindingsBundlesMetricsAndWithheldAssessmentRetainCanonicalValues,M9ConsumerConformanceTest' `
  '-Dm9.candidateJar=<absolute-evo.jar>' '-Dm9.node=<absolute-node.exe>' test
node --check backend/src/main/resources/web/app.js
node --test backend/src/test/js/workbench-model.test.cjs backend/src/test/js/bridge-model.test.cjs backend/src/test/js/m9-consumer-model.test.cjs
```

`m9.candidateJar` selects fresh packaged CLI processes and `m9.node` adds JS payload assertions over exact HTTP responses; the default JUnit path uses the in-process CLI.

## Basis

JUnit 5.11 distinguishes [tag filtering from conditional disabling](https://junit.org/junit5/docs/5.11.0/user-guide/index.html#writing-tests-tagging-and-filtering). Surefire 3.5.5 supports [JUnit tag expressions through `groups` / `excludedGroups`](https://maven.apache.org/surefire-archives/surefire-3.5.5/maven-surefire-plugin/examples/junit-platform.html#filtering-by-tags). Native preflight uses Maven Enforcer's [required-file rule](https://maven.apache.org/enforcer/enforcer-rules/requireFilesExist.html); native readers perform authenticity checks. Java 21 defines [`Deflater.NO_COMPRESSION`](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/zip/Deflater.html#NO_COMPRESSION) for the adapter's temporary ZIP representation.

The five product captures are Protobuf caller/producer, Lombok native member augmentation, native Spring MapStruct, and custom generated Java/resources; product replay excludes test-classes and tests JARs and verifies external registry/pins/signatures without issuing claims. Qualification remains `PUBLIC_TEST_POLICY_ONLY`. [Review follow-up](tasks/m8/04-step4-phase2-execution.md#review-follow-up--2026-10-26) records the final 605-case/70-suite refresh and its limits.
