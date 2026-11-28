# E6 Evaluation Report: Online Web App 17-App `microSecEnD` Linux Docker Benchmark

- **Date / Reconciled:** 2026-11-27 (`e6_summary.json` UTC: `2026-11-27T15:27:10.485726+00:00`)
- **Base Repository Commit (`HEAD`):** `a661db3` (`update UI`, unstaged working tree `81 files changed, 9982 insertions(+), 2613 deletions(-)` plus `30` untracked paths; zero external benchmark repositories, CSVs, or JSONs committed to Git)
- **Target Product Artifact (Online Web App):**
  - Standalone JAR: `backend/target/evo-workbench.jar` — **`72,160,402` bytes**, `LastWriteTimeUtc`: `2026-11-27T13:54:54.1865630Z`, SHA-256: `C88CE3DB63176AFA11CB93794D3FB71376250B6BB55379B93D5B5F6BF9C57944` (byte-for-byte verified identical against compiled `target/classes` and `run.py` for all 6 latest-timestamp Batch 9 files).
  - Linux Container Image: `evo-workbench-online:latest` (built from [`Dockerfile`](../../Dockerfile), `eclipse-temurin:21-jdk-alpine`, non-root `uid=10001`, `--online` entrypoint) — ID: `sha256:905b9e9c4b0f132909c7f5ed40887542c5e4af7d102eab4eefc59b0406eb760d` (`905b9e9c4b0f`), Size: `706,986,207` bytes (`707 MB` disk / `256 MB` content), Created: `2026-11-27T13:55:06.66895835Z`.
- **Analysis Mode & Invariants:** Passive static analysis (`--build=off`), zero target-build execution on host, closed `17/17` reporting denominator (`0` omitted apps), anti-overfitting gate **`I5_MATCHES=0`** across **`800`** scanned `src/main/` `*.java`/`*.py` files (`analyzer*,backend,cli`).
- **Pinned External Corpora (Stored Outside Git under `D:\UIT\evo-benchmark-cache\`):**
  - `microSecEnD` commit SHA: `63653e09177b6d757eada4521f8bace8a8c20766` (17 Spring microservice repositories in [`corpus_manifest.json`](../../benchmarks/e0-publication-suite/corpus_manifest.json) `suites.microsecend_17`: `14` Maven + `3` Gradle; `182` raw ground-truth components, `385` raw ground-truth inter-service connections).
  - `EMSE2025SAR-Replication` commit SHA: `1b8994471e9df0d32e22b31415eead8511078125` (textual ground truth and verbatim processed outputs for 7 SAR baseline tools).
- **Pre-Benchmark Selective Verification (`CONFIRMED`):**
  - Selective 5-module Maven test suite (`.,analyzer,analyzer-filesystem,analyzer-javaparser,backend`; Enforcer rules 0–9 passed): **`80/80` PASS (`0` failures, `0` errors, `0` skipped)** across **14 test classes**:
    - `analyzer` (`19`): `ArchitectureQueryContractTest` (`13`), `PolyglotQueryServiceTest` (`3`), `SpringSatAblationBenchmarkTest` (`3`).
    - `analyzer-filesystem` (`14`): `StoragePerfParityTest` (`3`), `StreamingRepositoryAcquirerTest` (`11`).
    - `analyzer-javaparser` (`3`): `JavaServiceSyntaxExtractorTest` (`3`).
    - `backend` (`44`): `RepositoryConnectionsTest` (`8`), `SpringDifferentialOracleTest` (`1` — 6 scenarios, 59 concrete Spring 6.2 worlds, 13 beans, 11 exact [`84.6%`], 2 safe `UNKNOWN`, `100.0%` soundness; see [`E5-Spring-SAT-Oracle-And-Ablation-Report.md`](./E5-Spring-SAT-Oracle-And-Ablation-Report.md)), `backend.analysis.WorkbenchApiMainTest` (`5`), `AiAndRepairUpgradeTest` (`11`), `AnalysisJobApiTest` (`5`), `ArchitectureAgentTest` (`5`), `ResearchBreakthroughUpgradesTest` (`5` — `[R3-RTS] selected=2/14`, `85.71%` reduction), `backend.query.WorkbenchApiMainTest` (`4`).
- **Working-Tree Honesty Note on `cli/` (`CONFIRMED`):** Batch 9 performed **`0` edits to `cli/`**, targeting exclusively the Online Web App JAR and Linux Docker image. Against `HEAD a661db3`, `git diff --stat -- cli/` still records **3 pre-existing uncommitted tracked modifications** from earlier batches (UTC mtimes `2026-11-27T11:00:01Z`–`11:13:28Z`, predating Batch 9's `12:43Z`–`13:55Z` Online Web App edits): `cli/src/main/java/com/evolution/cli/EvoApp.java` (`4956 +-`), `cli/src/main/java/com/evolution/cli/Workspace.java` (`10 +-`), and `cli/src/test/java/com/evolution/cli/EvoScoreV2AndIncrementalCliTest.java` (`303 ++`; subtotal `3 files changed, 3974 insertions(+), 1295 deletions(-)`).

---

## 1. Batch 9 Online Web App Engineering Fixes Evaluated in E6

Before executing E6, three general-purpose engineering fixes (`I5_MATCHES=0`) were landed in the Online Web App pipeline and packaged into `evo-workbench-online:latest` (`905b9e9c4b0f`):

1. **Online `SKG-RTS`, `strictQaMode`, and `EVO_PROJECTION_PROFILE` Wiring:**
   - [`LocalAnalysisRunner.java`](../../backend/src/main/java/com/evolution/backend/analysis/LocalAnalysisRunner.java) exposes `configuredProjectionProfile()` reading `-Devo.projection.profile` or `EVO_PROJECTION_PROFILE` (`strict` default vs `dfd-export` / `DFD_EXPORT`), forwards the profile into `PolyglotScan.project(...)` for both disk-spill (`PartitionedGraphStore`) and in-memory (`CanonicalGraphBuilder`) graph builds, and persists `IncrementalGraphProjector` templates under `incremental-cache/graph-templates`.
   - [`ArchitectureQueryApi.java`](../../backend/src/main/java/com/evolution/backend/query/ArchitectureQueryApi.java), [`ArchitectureAgent.java`](../../backend/src/main/java/com/evolution/backend/query/ArchitectureAgent.java), [`CapturedRepairRunner.java`](../../backend/src/main/java/com/evolution/backend/query/CapturedRepairRunner.java), [`RepairContextPlanner.java`](../../backend/src/main/java/com/evolution/backend/query/repair/RepairContextPlanner.java), and [`captured-repair/run.py`](../../analyzer-execution/src/main/resources/captured-repair/run.py) wire `strictQaMode` and SKG-RTS `--selected-tests` into the Online Web App endpoints (verified in [`ResearchBreakthroughUpgradesTest.java`](../../backend/src/test/java/com/evolution/backend/query/ResearchBreakthroughUpgradesTest.java)).
2. **Service-Topology Evidence Whitelisting in Query API:**
   - [`ArchitectureQueryService.java`](../../analyzer/src/main/java/com/evolution/analysis/query/ArchitectureQueryService.java) explicitly whitelists field-level `select(...)` projections for 7 service-topology evidence families (`polyglot.build-component`, `architecture.infra-component`, `architecture.external-actor`, `architecture.declarative-topology`, `architecture.service-call`, `polyglot.event-broker-link`, `polyglot.event-topic-match`) and populates safe labels/origins (`PROJECT`, `INFRA`, `EXTERNAL`) during `describe(...)` while keeping untrusted redaction strictly enforced for all non-whitelisted families (verified in [`PolyglotQueryServiceTest.java`](../../analyzer/src/test/java/com/evolution/analysis/query/PolyglotQueryServiceTest.java)).
3. **Uninitialized Git Submodule (`GITLINK`) Inventory Sealing:**
   - [`SqliteInventoryStore.java`](../../analyzer-filesystem/src/main/java/com/evolution/analysis/filesystem/SqliteInventoryStore.java) permits `InventoryEntry.Kind.GITLINK` entries marked `Disposition.GAP` with `SUBMODULE_COMMIT_REQUIRED` to seal the repository inventory with `compatible=false` while preserving typed `GAP` provenance—eliminating fatal `INVENTORY_FRONTIER_OPEN` aborts on repositories containing uninitialized Git submodules (`#6 fernandoabcampos/spring-netflix-oss-microservices` and `#8 jferrater/tap-and-eat-microservices`), while keeping `LFS_OBJECT_REQUIRED` and all other open `PENDING`/`GAP` frontiers fail-closed (verified in [`StreamingRepositoryAcquirerTest.java`](../../analyzer-filesystem/src/test/java/com/evolution/analysis/filesystem/StreamingRepositoryAcquirerTest.java)).

---

## 2. Benchmark Protocol, Epistemic Tiering & Container Hygiene

All 17 repositories in [`corpus_manifest.json`](../../benchmarks/e0-publication-suite/corpus_manifest.json) (`suites.microsecend_17`) were executed end-to-end through [`run_online_microsecend_benchmark.py`](../../benchmarks/e0-publication-suite/run_online_microsecend_benchmark.py) over HTTP against live Linux Docker containers running `evo-workbench-online:latest` (`--online`, passive `--build=off`).

### 2.1. Epistemic Projection Profiles (`EVO_PROJECTION_PROFILE`)

- **`Evo[strict]` (`EVO_PROJECTION_PROFILE=strict`):** Default evidence-strict mode. Projects `BUILD_COMPONENT`, `INFRA_COMPONENT`, and directly evidenced inter-service edges (`CALLS_SERVICE`, `INVOKES_RPC`, `PUBLISHES_TO`, `CONSUMES_FROM`, `PUBLISHES_EVENT`) without synthesizing inferred DFD external user actors or heuristic hub edges.
- **`Evo[dfd-export]` (`EVO_PROJECTION_PROFILE=dfd-export`):** Opt-in DFD-export projection profile. Adds DFD-aligned `EXTERNAL_ACTOR` (`user`) and inferred public entrypoint / configuration-hub topology projections as an explicitly labeled epistemic tier so recall gains and precision tradeoffs are measured transparently against `STRICT`.

### 2.2. Three-Tier Label Normalization (Generic, Zero Repo-Specific Overrides)

To separate true architectural recovery from naming-convention differences between build directories, Docker/K8s manifests, and `EMSE2025SAR-Replication` textual ground truth, the harness computes three deterministic normalization tiers using **zero repository-specific mappings** (`I5_MATCHES=0`):

1. **`strict_normalized` (`182` GT components, `385` GT connections):** Strips `#` document qualifiers and parent directory prefixes, lowercases, replaces `[_\s.]+` with `-`, and collapses repeated `-`.
2. **`canonical_normalized` (`179` GT components, `383` GT connections after post-normalization deduplication):** Applies data-driven `compute_shared_module_prefix(...)` (stripping any hyphen prefix shared by `>= 3` sibling `BUILD_COMPONENT` modules—triggered automatically on 3 repos: `microservice-demo-`, `microservice-kafka-`, and `spring-petclinic-`—or else a matching `<repo-dir>-` prefix) and strips one generic role suffix from `("-microservice", "-service", "-server", "-ms", "-app")`.
3. **`collapsed_canonical` (`179` GT components, `383` GT connections):** Removes `-` delimiters from `canonical_normalized` and strips a trailing concatenated role suffix from `("microservice", "service", "server", "app")` when `len(token) > len(suffix) + 2`. This generically aligns concatenated Gradle/Maven names with underscore-delimited ground truth (e.g., `accountservice` vs `account_service` in `#8 jferrater`, `authserver` vs `auth_server` in `#1 anilallewar`).

### 2.3. Determinism Protocol (`Cold -> Warm1 -> Warm2`) & Linux Docker Execution Hygiene

- **Replay Verification:** For each `(repo, profile)`, the harness registers the remote HTTPS repository (`POST /api/v1/analysis/connections`), runs a Cold `FULL` scan (`force=false`), runs a Warm1 `NO_CHANGE` re-scan (`force=true`), and—whenever `cold_graph != warm1_graph`—automatically executes a Warm2 `NO_CHANGE` re-scan (`force=true`) to verify steady-state SHA-256 parity (`warm1_graph == warm2_graph`) and record `cold_vs_warm1_observed` diagnostics.
- **Zero Windows Bind Mounts (OverlayFS Isolation):** All containers ran exclusively on Linux `overlayfs` (`/data/evo-store` inside the container, zero `-v` Windows host bind mounts) to prevent Windows Docker Desktop VirtioFS concurrent SQLite/clone lock deadlocks.
- **WSL2 RAM Budgeting & Sharding:** Docker Desktop WSL2 provides `15.5 GiB` total RAM (`16,657,866,752` bytes), shared with 7 pre-existing non-Evo containers. An initial attempt with 6 concurrent `-Xmx3g` containers triggered a WSL2 OOM-kill (`Exit 137` on `evo-e6-dfd`). The suite was cleanly partitioned into 3 bounded-memory Linux container pairs (`--memory=2200m` with `-Xms256m -Xmx1536m` for Shard A apps `1..9` and Shard B apps `10..14, 17`; `--memory=2800m` with `-Xms256m -Xmx2g` for Shard C apps `15..16`), atomically checkpointed per app (`--resume`), merged strictly in `corpus_manifest.json` order, and verified to leave **zero** `evo-e6-*` containers behind (`docker ps -a`).

---

## 3. Head-to-Head Benchmark Comparison vs `EMSE2025SAR-Replication` (`17/17` Closed Denominator)

All 7 baseline rows are parsed verbatim from `EMSE2025SAR-Replication` (`1b8994471e9df0d32e22b31415eead8511078125`) `tool_comparison/results/recovery_correctness/processed_results/*/*.txt`. Baseline metrics are averaged only over the subset of applications each tool completed (`apps_completed`), whereas **Evo reports the full `17/17` (`100%`) closed denominator**.

### 3.1. Components & Inter-Service Connections (`Micro` and `Macro` Precision / Recall / F1)

| Tool / Configuration | Apps Done | COMP TP / FP / FN | COMP uP | COMP uR | **COMP uF1** | COMP mP | COMP mR | **COMP mF1** | CONN TP / FP / FN | CONN uP | CONN uR | **CONN uF1** | CONN mP | CONN mR | **CONN mF1** |
| :--- | :---: | :---: | ---: | ---: | ---: | ---: | ---: | ---: | :---: | ---: | ---: | ---: | ---: | ---: | ---: |
| **`Evo[strict]` (Collapsed Canonical)** | **17/17** | 142 / 32 / 37 | 0.8161 | 0.7933 | **0.8045** | 0.8823 | 0.7740 | **0.8086** | 64 / 128 / 319 | 0.3333 | 0.1671 | **0.2226** | 0.3550 | 0.1355 | **0.1792** |
| **`Evo[dfd-export]` (Collapsed Canonical)** | **17/17** | 152 / 32 / 27 | 0.8261 | 0.8492 | **0.8375** | 0.8852 | 0.8294 | **0.8412** | 91 / 207 / 292 | 0.3054 | 0.2376 | **0.2673** | 0.3525 | 0.1960 | **0.2217** |
| `Evo[strict]` (Canonical Normalized) | 17/17 | 131 / 51 / 48 | 0.7198 | 0.7318 | 0.7258 | 0.8128 | 0.7066 | 0.7388 | 62 / 131 / 321 | 0.3212 | 0.1619 | 0.2153 | 0.2960 | 0.1282 | 0.1661 |
| `Evo[dfd-export]` (Canonical Normalized) | 17/17 | 142 / 51 / 37 | 0.7358 | 0.7933 | 0.7634 | 0.8179 | 0.7669 | 0.7738 | 89 / 213 / 294 | 0.2947 | 0.2324 | 0.2599 | 0.2928 | 0.1886 | 0.2082 |
| `Evo[strict]` (Strict Normalized) | 17/17 | 108 / 85 / 74 | 0.5596 | 0.5934 | 0.5760 | 0.6030 | 0.5509 | 0.5581 | 41 / 162 / 344 | 0.2020 | 0.1065 | 0.1395 | 0.1764 | 0.0804 | 0.1044 |
| `Evo[dfd-export]` (Strict Normalized) | 17/17 | 122 / 85 / 60 | 0.5894 | 0.6703 | 0.6272 | 0.6388 | 0.6271 | 0.6148 | 66 / 251 / 319 | 0.2082 | 0.1714 | 0.1880 | 0.1922 | 0.1341 | 0.1449 |
| `code2DFD` *(EMSE2025SAR)* | 17/17 | 178 / 0 / 4 | 1.0000 | 0.9780 | 0.9889 | 1.0000 | 0.9755 | 0.9869 | 320 / 0 / 65 | 1.0000 | 0.8312 | 0.9078 | 1.0000 | 0.8554 | 0.9151 |
| `attack-graph-generator` *(EMSE2025SAR)* | 12/17 | 123 / 0 / 21 | 1.0000 | 0.8542 | 0.9213 | 1.0000 | 0.8643 | 0.9219 | 279 / 1 / 45 | 0.9964 | 0.8611 | 0.9238 | 0.9833 | 0.8550 | 0.9031 |
| `MicroDepGraph` *(EMSE2025SAR)* | 10/17 | 84 / 0 / 26 | 1.0000 | 0.7636 | 0.8660 | 1.0000 | 0.7683 | 0.8641 | 124 / 0 / 121 | 1.0000 | 0.5061 | 0.6721 | 1.0000 | 0.5139 | 0.6535 |
| `microMiner` *(EMSE2025SAR)* | 5/17 | 32 / 0 / 25 | 1.0000 | 0.5614 | 0.7191 | 0.8000 | 0.5695 | 0.6586 | 0 / 0 / 0 *(n=0)* | 0.0000 | 0.0000 | 0.0000 | 0.0000 | 0.0000 | 0.0000 |
| `Prophet` *(EMSE2025SAR)* | 17/17 | 27 / 0 / 155 | 1.0000 | 0.1484 | 0.2584 | 0.5294 | 0.1608 | 0.2372 | 4 / 0 / 381 | 1.0000 | 0.0104 | 0.0206 | 0.1765 | 0.0261 | 0.0418 |
| `RAD` *(EMSE2025SAR)* | 13/17 | 0 / 0 / 0 *(n=0)* | 0.0000 | 0.0000 | 0.0000 | 0.0000 | 0.0000 | 0.0000 | 0 / 0 / 259 | 0.0000 | 0.0000 | 0.0000 | 0.0000 | 0.0000 | 0.0000 |
| `RAD-source` *(EMSE2025SAR)* | 17/17 | 0 / 0 / 0 *(n=0)* | 0.0000 | 0.0000 | 0.0000 | 0.0000 | 0.0000 | 0.0000 | 7 / 0 / 378 | 1.0000 | 0.0182 | 0.0357 | 0.2353 | 0.0341 | 0.0551 |

### 3.2. `DFD_EXPORT` vs `STRICT` Ablation & Runtime Summary (`17/17` Apps)

| Metric | `Evo[strict]` | `Evo[dfd-export]` | Delta (`dfd-export - strict`) |
| :--- | ---: | ---: | ---: |
| Apps Analyzed with Graph | `17 / 17` (`100%`) | `17 / 17` (`100%`) | `0` |
| Cold == Warm1 SHA-256 Parity | `12 / 17` (`70.6%`) | `13 / 17` (`76.5%`) | `+1` |
| **Steady-State (`Warm1 == Warm2`) SHA-256 Parity** | **`17 / 17` (`100.0%`)** | **`17 / 17` (`100.0%`)** | **`0`** |
| Cold Elapsed Time — Median ± IQR (min..max, ms) | `37,603.0 ± 132,391.0` (`7,755..241,401`) | `37,317.0 ± 55,431.5` (`8,068..255,160`) | `-286.0` ms median |
| Warm1 Elapsed Time — Median ± IQR (min..max, ms) | `4,474.0 ± 20,588.0` (`1,743..40,573`) | `4,216.0 ± 17,995.0` (`1,782..106,429`) | `-258.0` ms median (`8.4×`/`8.9×` warm speedup) |
| **Collapsed COMP Micro F1 (TP / FP / FN)** | **`0.8045`** (`142 / 32 / 37`) | **`0.8375`** (`152 / 32 / 27`) | **`+0.0330`** (`+10` TP, **`0` extra FP**) |
| **Collapsed COMP Macro F1** | **`0.8086`** | **`0.8412`** | **`+0.0326`** |
| **Collapsed CONN Micro F1 (TP / FP / FN)** | **`0.2226`** (`64 / 128 / 319`) | **`0.2673`** (`91 / 207 / 292`) | **`+0.0447`** (`+27` TP, `+79` FP) |
| **Collapsed CONN Macro F1** | **`0.1792`** | **`0.2217`** | **`+0.0425`** |
| Canonical COMP Micro F1 (TP / FP / FN) | `0.7258` (`131 / 51 / 48`) | `0.7634` (`142 / 51 / 37`) | `+0.0376` (`+11` TP, `0` extra FP) |
| Canonical CONN Micro F1 (TP / FP / FN) | `0.2153` (`62 / 131 / 321`) | `0.2599` (`89 / 213 / 294`) | `+0.0446` (`+27` TP, `+82` FP) |
| Strict-Norm COMP Micro F1 (TP / FP / FN) | `0.5760` (`108 / 85 / 74`) | `0.6272` (`122 / 85 / 60`) | `+0.0512` (`+14` TP, `0` extra FP) |
| Strict-Norm CONN Micro F1 (TP / FP / FN) | `0.1395` (`41 / 162 / 344`) | `0.1880` (`66 / 251 / 319`) | `+0.0485` (`+25` TP, `+89` FP) |

**Key takeaway:** Enabling `EVO_PROJECTION_PROFILE=dfd-export` improves Collapsed Component uF1 by **`+0.0330`** (`0.8045 -> 0.8375`) with **zero extra component false positives** (`32 -> 32`, across all three normalization tiers `FP` is strictly unchanged: `85 -> 85`, `51 -> 51`, `32 -> 32`) and improves Collapsed Connection uF1 by **`+0.0447`** (`0.2226 -> 0.2673`, `+27` TPs at a precision tradeoff of `-0.0279` uP from `+79` FPs). Among tools completing all `17/17` applications, `Evo[dfd-export]` ranks **2nd overall** behind `code2DFD` on both Components (`0.8375` vs `Prophet` `0.2584` and `RAD-source` `0.0000`) and Connections (`0.2673` vs `RAD-source` `0.0357` and `Prophet` `0.0206`).

---

## 4. Complete 17/17 Per-Application Breakdown (Verbatim from `e6_summary.json`)

Every number below is extracted verbatim from `D:\UIT\evo-benchmark-cache\results\e6-online-microsecend\e6_summary.json`. All 17 repositories completed with `status=PARTIAL` and `steady_state_sha256_match=True` (`0` `ERROR` rows) under both profiles.

### 4.1. `Evo[strict]` (`EVO_PROJECTION_PROFILE=strict`) — All 17 Applications

| # | Repository (`app_id`) | Build | Status | Cold (ms) | Warm (ms) | `cold==w1` | `steady` | Gaps (`c/w1`) | Canon COMP (`TP/FP/FN`, F1) | Collapsed COMP (`TP/FP/FN`, F1) | Canon CONN (`TP/FP/FN`, F1) | Collapsed CONN (`TP/FP/FN`, F1) |
| ---: | :--- | :---: | :---: | ---: | ---: | :---: | :---: | :---: | :---: | :---: | :---: | :---: |
| 1 | `anilallewar/microservices-basics-spring-boot` | gradle | `PARTIAL` | 37,603 | 5,073 | `True` | `True` | 1257 / 1257 | `8/24/4` (0.3636) | `10/14/2` (0.5556) | `1/18/28` (0.0417) | `1/17/28` (0.0426) |
| 2 | `apssouza22/java-microservice` | maven | `PARTIAL` | 139,069 | 22,947 | `False` | `True` | 1947 / 1947 | `11/3/3` (0.7857) | `11/3/3` (0.7857) | `5/19/29` (0.1724) | `5/19/29` (0.1724) |
| 3 | `callistaenterprise/blog-microservices` | gradle | `PARTIAL` | 43,692 | 4,474 | `True` | `True` | 1152 / 1152 | `15/7/2` (0.7692) | `15/7/2` (0.7692) | `7/9/35` (0.2414) | `7/9/35` (0.2414) |
| 4 | `ewolff/microservice` | maven | `PARTIAL` | 65,039 | 20,640 | `True` | `True` | 1083 / 1083 | `6/0/1` (0.9231) | `6/0/1` (0.9231) | `0/2/13` (0.0000) | `0/2/13` (0.0000) |
| 5 | `ewolff/microservice-kafka` | maven | `PARTIAL` | 193,210 | 40,573 | `False` | `True` | 856 / 856 | `7/0/1` (0.9333) | `7/0/1` (0.9333) | `1/18/11` (0.0645) | `1/18/11` (0.0645) |
| 6 | `fernandoabcampos/spring-netflix-oss-microservices` | maven | `PARTIAL` | 28,382 | 3,646 | `True` | `True` | 714 / 714 | `9/3/2` (0.7826) | `9/3/2` (0.7826) | `5/16/19` (0.2222) | `5/16/19` (0.2222) |
| 7 | `georgwittberger/apache-spring-boot-microservice-example` | maven | `PARTIAL` | 14,912 | 2,501 | `True` | `True` | 419 / 419 | `3/0/2` (0.7500) | `3/0/2` (0.7500) | `0/0/6` (0.0000) | `0/0/6` (0.0000) |
| 8 | `jferrater/tap-and-eat-microservices` | gradle | `PARTIAL` | 162,356 | 23,355 | `False` | `True` | 1043 / 1043 | `0/9/9` (0.0000) | `8/1/1` (0.8889) | `0/2/16` (0.0000) | `2/0/14` (0.2222) |
| 9 | `koushikkothagal/spring-boot-microservices-workshop` | maven | `PARTIAL` | 11,185 | 2,422 | `True` | `True` | 358 / 358 | `4/0/1` (0.8889) | `4/0/1` (0.8889) | `0/0/6` (0.0000) | `0/0/6` (0.0000) |
| 10 | `mdeket/spring-cloud-movie-recommendation` | maven | `PARTIAL` | 154,180 | 32,343 | `False` | `True` | 830 / 830 | `6/0/4` (0.7500) | `6/0/4` (0.7500) | `3/0/14` (0.3000) | `3/0/14` (0.3000) |
| 11 | `mudigal-technologies/microservices-sample` | maven | `PARTIAL` | 13,555 | 4,222 | `True` | `True` | 416 / 416 | `14/0/1` (0.9655) | `14/0/1` (0.9655) | `10/7/24` (0.3922) | `10/7/24` (0.3922) |
| 12 | `piomin/sample-spring-oauth2-microservices` | maven | `PARTIAL` | 7,755 | 1,743 | `True` | `True` | 379 / 379 | `4/0/4` (0.6667) | `4/0/4` (0.6667) | `2/0/11` (0.2667) | `2/0/11` (0.2667) |
| 13 | `rohitghatol/spring-boot-microservices` | maven | `PARTIAL` | 24,705 | 4,233 | `True` | `True` | 1146 / 1146 | `7/1/4` (0.7368) | `8/0/3` (0.8421) | `0/7/26` (0.0000) | `0/7/26` (0.0000) |
| 14 | `shabbirdwd53/springboot-microservice` | maven | `PARTIAL` | 10,857 | 2,625 | `True` | `True` | 711 / 711 | `4/2/4` (0.5714) | `4/2/4` (0.5714) | `0/2/17` (0.0000) | `0/2/17` (0.0000) |
| 15 | `spring-petclinic/spring-petclinic-microservices` | maven | `PARTIAL` | 241,401 | 29,015 | `False` | `True` | 1585 / 1584 | `10/1/2` (0.8696) | `10/1/2` (0.8696) | `7/12/21` (0.2979) | `7/12/21` (0.2979) |
| 16 | `sqshq/piggymetrics` | maven | `PARTIAL` | 63,001 | 5,880 | `True` | `True` | 1797 / 1797 | `14/0/3` (0.9032) | `14/0/3` (0.9032) | `8/14/29` (0.2712) | `8/14/29` (0.2712) |
| 17 | `yidongnan/spring-cloud-netflix-example` | maven | `PARTIAL` | 15,887 | 2,481 | `True` | `True` | 754 / 754 | `9/1/1` (0.9000) | `9/1/1` (0.9000) | `13/5/16` (0.5532) | `13/5/16` (0.5532) |
| **Agg** | **`17/17` Micro / Macro (Median Cold / Warm)** | **14M/3G** | **17/17** | **37,603** | **4,474** | **12/17** | **17/17** | — | **`131/51/48` (u0.7258 / m0.7388)** | **`142/32/37` (u0.8045 / m0.8086)** | **`62/131/321` (u0.2153 / m0.1661)** | **`64/128/319` (u0.2226 / m0.1792)** |

### 4.2. `Evo[dfd-export]` (`EVO_PROJECTION_PROFILE=dfd-export`) — All 17 Applications

| # | Repository (`app_id`) | Build | Status | Cold (ms) | Warm (ms) | `cold==w1` | `steady` | Gaps (`c/w1`) | Canon COMP (`TP/FP/FN`, F1) | Collapsed COMP (`TP/FP/FN`, F1) | Canon CONN (`TP/FP/FN`, F1) | Collapsed CONN (`TP/FP/FN`, F1) |
| ---: | :--- | :---: | :---: | ---: | ---: | :---: | :---: | :---: | :---: | :---: | :---: | :---: |
| 1 | `anilallewar/microservices-basics-spring-boot` | gradle | `PARTIAL` | 37,317 | 4,104 | `True` | `True` | 1257 / 1257 | `9/24/3` (0.4000) | `10/14/2` (0.5556) | `4/32/25` (0.1231) | `4/28/25` (0.1311) |
| 2 | `apssouza22/java-microservice` | maven | `PARTIAL` | 139,762 | 21,185 | `False` | `True` | 1947 / 1947 | `11/3/3` (0.7857) | `11/3/3` (0.7857) | `6/25/28` (0.1846) | `6/25/28` (0.1846) |
| 3 | `callistaenterprise/blog-microservices` | gradle | `PARTIAL` | 47,497 | 5,794 | `True` | `True` | 1152 / 1152 | `16/7/1` (0.8000) | `16/7/1` (0.8000) | `9/19/33` (0.2571) | `9/19/33` (0.2571) |
| 4 | `ewolff/microservice` | maven | `PARTIAL` | 64,497 | 20,251 | `True` | `True` | 1083 / 1083 | `7/0/0` (1.0000) | `7/0/0` (1.0000) | `0/3/13` (0.0000) | `0/3/13` (0.0000) |
| 5 | `ewolff/microservice-kafka` | maven | `PARTIAL` | 39,693 | 5,285 | `True` | `True` | 1003 / 1003 | `8/0/0` (1.0000) | `8/0/0` (1.0000) | `2/19/10` (0.1212) | `2/19/10` (0.1212) |
| 6 | `fernandoabcampos/spring-netflix-oss-microservices` | maven | `PARTIAL` | 15,650 | 2,778 | `True` | `True` | 714 / 714 | `10/3/1` (0.8333) | `10/3/1` (0.8333) | `9/26/15` (0.3051) | `9/26/15` (0.3051) |
| 7 | `georgwittberger/apache-spring-boot-microservice-example` | maven | `PARTIAL` | 10,812 | 3,354 | `True` | `True` | 419 / 419 | `3/0/2` (0.7500) | `3/0/2` (0.7500) | `0/0/6` (0.0000) | `0/0/6` (0.0000) |
| 8 | `jferrater/tap-and-eat-microservices` | gradle | `PARTIAL` | 70,769 | 106,429 | `False` | `True` | 1043 / 1043 | `0/9/9` (0.0000) | `8/1/1` (0.8889) | `0/2/16` (0.0000) | `2/0/14` (0.2222) |
| 9 | `koushikkothagal/spring-boot-microservices-workshop` | maven | `PARTIAL` | 8,549 | 2,530 | `True` | `True` | 358 / 358 | `4/0/1` (0.8889) | `4/0/1` (0.8889) | `0/0/6` (0.0000) | `0/0/6` (0.0000) |
| 10 | `mdeket/spring-cloud-movie-recommendation` | maven | `PARTIAL` | 140,549 | 29,398 | `False` | `True` | 830 / 830 | `6/0/4` (0.7500) | `6/0/4` (0.7500) | `4/0/13` (0.3810) | `4/0/13` (0.3810) |
| 11 | `mudigal-technologies/microservices-sample` | maven | `PARTIAL` | 13,591 | 3,292 | `True` | `True` | 416 / 416 | `15/0/0` (1.0000) | `15/0/0` (1.0000) | `11/18/23` (0.3492) | `11/18/23` (0.3492) |
| 12 | `piomin/sample-spring-oauth2-microservices` | maven | `PARTIAL` | 8,068 | 1,782 | `True` | `True` | 379 / 379 | `5/0/3` (0.7692) | `5/0/3` (0.7692) | `3/0/10` (0.3750) | `3/0/10` (0.3750) |
| 13 | `rohitghatol/spring-boot-microservices` | maven | `PARTIAL` | 24,507 | 4,216 | `True` | `True` | 1146 / 1146 | `8/1/3` (0.8000) | `9/0/2` (0.9000) | `5/8/21` (0.2564) | `5/8/21` (0.2564) |
| 14 | `shabbirdwd53/springboot-microservice` | maven | `PARTIAL` | 10,526 | 2,633 | `True` | `True` | 711 / 711 | `4/2/4` (0.5714) | `4/2/4` (0.5714) | `0/3/17` (0.0000) | `0/3/17` (0.0000) |
| 15 | `spring-petclinic/spring-petclinic-microservices` | maven | `PARTIAL` | 255,160 | 25,320 | `False` | `True` | 1585 / 1584 | `11/1/1` (0.9167) | `11/1/1` (0.9167) | `8/22/20` (0.2759) | `8/22/20` (0.2759) |
| 16 | `sqshq/piggymetrics` | maven | `PARTIAL` | 60,670 | 5,695 | `True` | `True` | 1797 / 1797 | `15/0/2` (0.9375) | `15/0/2` (0.9375) | `13/28/24` (0.3333) | `13/28/24` (0.3333) |
| 17 | `yidongnan/spring-cloud-netflix-example` | maven | `PARTIAL` | 15,870 | 2,668 | `True` | `True` | 754 / 754 | `10/1/0` (0.9524) | `10/1/0` (0.9524) | `15/8/14` (0.5769) | `15/8/14` (0.5769) |
| **Agg** | **`17/17` Micro / Macro (Median Cold / Warm)** | **14M/3G** | **17/17** | **37,317** | **4,216** | **13/17** | **17/17** | — | **`142/51/37` (u0.7634 / m0.7738)** | **`152/32/27` (u0.8375 / m0.8412)** | **`89/213/294` (u0.2599 / m0.2082)** | **`91/207/292` (u0.2673 / m0.2217)** |

*(Auto-detected shared build prefixes across the 17 apps: `microservice-demo-` on `#4 ewolff/microservice`, `microservice-kafka-` on `#5 ewolff/microservice-kafka`, and `spring-petclinic-` on `#15 spring-petclinic/spring-petclinic-microservices`; empty `""` on all other 14 apps.)*

---

## 5. Honest Scientific Analysis, Failure Modes & Limitations

### 5.1. Why Evo Achieves `17/17` (`100%`) Coverage and `17/17` (`100%`) Steady-State Determinism (`CONFIRMED`)

- **Closed-Denominator Robustness Without Host Build Execution:** Unlike 4 of the 7 `EMSE2025SAR-Replication` baselines that fail or skip 4–12 repositories (`attack-graph-generator`: `12/17`, `RAD`: `13/17`, `MicroDepGraph`: `10/17`, `microMiner`: `5/17`), Evo analyzes all `17/17` repositories (`14` Maven + `3` Gradle) in passive `--build=off` mode without ever executing untrusted target lifecycles or build scripts.
- **Typed Capability Gaps Instead of Silent Failure or Hard Aborts:** All 17 repositories truthfully report `PARTIAL` completion with explicit `CapabilityGapRecord` counts (`358`–`1,947` per repository, reflecting passive unresolved classpath/bytecode or external submodule gaps). Batch 9's `GITLINK` sealing fix in [`SqliteInventoryStore.java`](../../analyzer-filesystem/src/main/java/com/evolution/analysis/filesystem/SqliteInventoryStore.java) specifically unblocked `#6 fernandoabcampos/spring-netflix-oss-microservices` and `#8 jferrater/tap-and-eat-microservices` while retaining typed `SUBMODULE_COMMIT_REQUIRED` gaps (`compatible=false`).
- **Component Recovery Strength:** Under `Evo[dfd-export]` (Collapsed Canonical), Evo achieves **`1.0000` F1** on 3 repositories (`#4 ewolff/microservice`, `#5 ewolff/microservice-kafka`, `#11 mudigal-technologies/microservices-sample`), **`>= 0.9000` F1** on 7 repositories (adding `#13 rohitghatol` `0.9000`, `#15 spring-petclinic` `0.9167`, `#16 piggymetrics` `0.9375`, `#17 yidongnan` `0.9524`), and **`0` component FPs** on `9/17` repositories.

### 5.2. Exact Observed Root Cause of `cold != warm1` vs `17/17` Steady-State Parity (`CONFIRMED`)

Across the 17 apps, `cold_graph == warm1_graph` matched on **`12/17`** (`strict`) and **`13/17`** (`dfd-export`), while `warm1_graph == warm2_graph` converged bit-identically on **`17/17` (`100.0%`)** for both profiles.

Inspecting `cold_vs_warm1_observed` in `e6_summary.json` and performing a direct live API diff on `evo-e6-strict` (`:18081`) revealed the exact cause across all mismatched repositories (`#2 apssouza22`, `#5 ewolff/microservice-kafka` [`strict`], `#8 jferrater`, `#10 mdeket`, `#15 spring-petclinic-microservices`):

- **Input/Snapshot Invariance:** Across all 5 `strict` and 4 `dfd-export` mismatched cases, `snapshot`, `acquisition`, and `sourceReport` SHA-256 digests were **100% identical** across `cold`, `warm1`, and `warm2`.
- **Direct Live API Diff on `#5 ewolff/microservice-kafka` (`strict`, `gaps: 856 -> 856`):**
  - 100% of per-module Java analysis contexts, all 18 non-acquisition entity kinds (`BUILD_COMPONENT=4`, `INFRA_COMPONENT=4`, `MODULE=4`, `CAPABILITY_GAP=833`, `ACQUISITION_ISSUE=16`, `INVENTORY_ENTRY=164`, and all `SOURCE_*` kinds), and all **`31` inter-service edges** (`CALLS_SERVICE=4`, `CONSUMES_FROM=9`, `PUBLISHES_EVENT=9`, `PUBLISHES_TO=9`) were **100% bit-identical** between `cold` and `warm1`.
  - The **sole observed difference** in the `Whole repository` graph (`3,359 -> 2,045` nodes, delta `-1,314`) was operational **`ACQUISITION_ATTEMPT` telemetry nodes dropping from `2,214` (cold) to `900` (warm1)** (`2,214 - 900 = 1,314`) once the container's `/data/evo-store/m2-cache` populated during the cold scan. After that single cache-warming transition, `warm1 == warm2` converged bit-identically (`sha256:63ad2b5c...`).
- **Direct Live API Diff on `#2 apssouza22/java-microservice` (`strict`, `gaps: 1,947 -> 1,947`):** All `20` Java module contexts had identical `analysis` hashes, `evidence` hashes, and `nodeCount`s; only `Whole repository` dropped from `10,545 -> 7,879` nodes (`-2,666` operational acquisition telemetry nodes), then converged bit-identically (`warm1 == warm2 = sha256:b5625537...`).
- **Single Cache-Resolved Gap on `#15 spring-petclinic-microservices` (`gaps: 1,585 -> 1,584`):** One Maven artifact cached during the cold run resolved 1 capability gap on Warm1, after which `warm1 == warm2` converged bit-identically.
- **Architectural Implication (`OPEN` Improvement Opportunity):** Operational `ACQUISITION_ATTEMPT` retry/download telemetry is currently hashed inside the canonical `Whole repository` graph digest. Separating operational acquisition-attempt telemetry into an auxiliary ledger outside the canonical semantic graph digest would eliminate cold-vs-warm1 digest drift without sacrificing acquisition auditability.

### 5.3. Candid Diagnosis of Remaining `CONN` and `COMP` Accuracy Gaps

Evo's inter-service connection F1 (`0.2226` `strict` / `0.2673` `dfd-export` Collapsed uF1) trails specialized Spring DFD extractors (`code2DFD` `0.9078`, `attack-graph-generator` `0.9238` on `12/17`, `MicroDepGraph` `0.6721` on `10/17`). Four concrete, verified failure modes explain the gap:

1. **Unresolved Runtime/Programmatic HTTP & RPC Call Targets under Passive `--build=off` (`FN` Driver):**
   - Without compiled module bytecode or runtime/inter-procedural Spring Cloud route resolution, programmatic calls executed via `RestTemplate`, `WebClient`, `@FeignClient` name/URL properties, Ribbon load-balanced logical service IDs, and Zuul/Spring Cloud Gateway route tables (`zuul.routes.*`) frequently remain unresolved statically.
   - Consequently, 5 smaller/workshop repositories (`#4 ewolff/microservice`, `#7 georgwittberger`, `#9 koushikkothagal`, `#14 shabbirdwd53`, plus `#13 rohitghatol` under `strict`) yield `0` collapsed connection TPs, and corpus-wide Collapsed `CONN` recall reaches `0.1671` (`strict`) / `0.2376` (`dfd-export`).
2. **Implicit Spring Cloud Config/Eureka Hub & Edge-Direction Mismatch (`FP` & `FN` Driver):**
   - `EMSE2025SAR-Replication` textual DFD ground truth encodes data-flow edges from configuration/registry hubs or datastores to services (e.g., `config_server -> service`, `account_mongodb -> account_service`, `service -> user`), whereas Evo's static topology records declarative client dependency directions (`service -> config`, `service -> admin`, `service -> eureka`).
   - Under `DFD_EXPORT`, adding inferred hub/user edges recovers **`+27`** Collapsed connection TPs (`64 -> 91`), notably boosting `#13 rohitghatol` (`0 -> 5` TP, F1 `0.0000 -> 0.2564`), `#16 piggymetrics` (`8 -> 13` TP, F1 `0.2712 -> 0.3333`), `#6 fernandoabcampos` (`5 -> 9` TP, F1 `0.2222 -> 0.3051`), and `#17 yidongnan` (`13 -> 15` TP, F1 `0.5532 -> 0.5769`), while introducing **`+79`** unmatched hub/direction FPs (`128 -> 207`).
3. **`#1 anilallewar/microservices-basics-spring-boot` Duplicate Manifest & Helm Template FPs (`43.8%` of All Collapsed Component FPs):**
   - `#1 anilallewar` alone accounts for **`14 / 32` (`43.8%`)** of Evo's total Collapsed component FPs (and `24 / 51` Canonical FPs). Inspection of `pred_components_raw` in `e6_summary.json` shows two concrete causes:
     - **Unrendered Helm Go-template names (`9` FPs):** `{{-release-name-}}-api-gateway`, `{{-release-name-}}-auth-server`, `{{-release-name-}}-comment-service`, `{{-release-name-}}-config-server`, `{{-release-name-}}-eurekaregistry`, `{{-release-name-}}-mysql`, `{{-release-name-}}-task-service`, `{{-release-name-}}-user-service`, `{{-release-name-}}-zipkin-server` extracted literally from Kubernetes Helm chart YAML templates.
     - **Cross-manifest alias proliferation (`5` Collapsed FPs):** distinct non-unified identities emitted for the same logical service across Gradle subprojects, Docker Compose, and Kubernetes manifests (e.g., `comments-webservice` vs `commentservice`, `webservice-registry` vs `eurekaregistry`, `mysql` vs `mysqldb`, `config-server` vs `config-server-service`).
   - Secondary minor component FP sources across the remaining 16 apps are non-service/helper modules (`#3 callistaenterprise`: `7` FPs; `#2 apssouza22`: `3` FPs; `#6 fernandoabcampos`: `3` FPs; `#14 shabbirdwd53`: `2` FPs) and 2 raw YAML/build token artifacts (`ide:` in `#8 jferrater`, `gradle:` in `#17 yidongnan`, `1` FP each).
4. **Comparison Context vs `code2DFD` and Other EMSE Baselines:**
   - `code2DFD` (`0.9889` COMP uF1, `0.9078` CONN uF1) is a DFD-specialized Spring extractor co-evaluated with the `microSecEnD` benchmark and reports `0` FPs across all 17 apps in the replication dataset's processed results. By contrast, Evo is a general-purpose, zero-repo-tuned (`I5_MATCHES=0`), multi-language evidence-first architecture platform with cryptographic content-addressing, Spring SAT/COI conditional reasoning, and explicit `CapabilityGapRecord` disclosure; closing the remaining `CONN` recall gap requires deeper static Feign/RestTemplate/Zuul/Gateway target resolution and Helm/K8s template normalization—not repository-specific rules.

---

## 6. Reproducibility & Artifact Locations

### 6.1. Repository Harness & Manifest

- Benchmark runner: [`benchmarks/e0-publication-suite/run_online_microsecend_benchmark.py`](../../benchmarks/e0-publication-suite/run_online_microsecend_benchmark.py) (`python -m py_compile` verified `EXIT_CODE=0`)
- Corpus manifest: [`benchmarks/e0-publication-suite/corpus_manifest.json`](../../benchmarks/e0-publication-suite/corpus_manifest.json) (`suites.microsecend_17`, 17 apps)
- Container build definition: [`Dockerfile`](../../Dockerfile) (`evo-workbench-online:latest`)

### 6.2. External Immutable Benchmark Outputs (Outside Git)

- Authoritative 17/17 JSON summary (`150,763` bytes, `apps_count=17`, `0` `_sets` keys, `7` `emse2025sar_baselines`):
  - `D:\UIT\evo-benchmark-cache\results\e6-online-microsecend\e6_summary.json`
- Per-app CSV export (`5,236` bytes, `35` lines = 1 header + 17 `strict` + 17 `dfd-export` rows):
  - `D:\UIT\evo-benchmark-cache\results\e6-online-microsecend\e6_per_app.csv`
- Merged per-app checkpoint (`306,272` bytes):
  - `D:\UIT\evo-benchmark-cache\results\e6-online-microsecend\e6_checkpoint.json`

### 6.3. Reproduction Commands (Windows PowerShell 5.1)

**1. Pre-benchmark selective 5-module Maven verification (`80/80` PASS):**

```powershell
.\mvnw.cmd -o -B -ntp -pl .,analyzer,analyzer-filesystem,analyzer-javaparser,backend '-Dmaven.compiler.useIncrementalCompilation=false' '-Dtest=ArchitectureQueryContractTest,PolyglotQueryServiceTest,StoragePerfParityTest,StreamingRepositoryAcquirerTest,JavaServiceSyntaxExtractorTest,RepositoryConnectionsTest,SpringDifferentialOracleTest,SpringSatAblationBenchmarkTest,WorkbenchApiMainTest,AiAndRepairUpgradeTest,AnalysisJobApiTest,ArchitectureAgentTest,ResearchBreakthroughUpgradesTest' '-DfailIfNoTests=false' test
```

**2. Isolated Linux Docker `STRICT` + `DFD_EXPORT` benchmark run (zero host `-v` bind mounts, bounded RAM, guaranteed cleanup):**

```powershell
docker rm -f evo-e6-strict evo-e6-dfd 2>$null | Out-Null
docker run -d --name evo-e6-strict --memory=2200m -p 127.0.0.1:18081:8080 -e EVO_PROJECTION_PROFILE=strict -e JAVA_OPTS="-Xms256m -Xmx1536m -XX:+UseG1GC -XX:SoftRefLRUPolicyMSPerMB=1" evo-workbench-online:latest
docker run -d --name evo-e6-dfd --memory=2200m -p 127.0.0.1:18082:8080 -e EVO_PROJECTION_PROFILE=dfd-export -e JAVA_OPTS="-Xms256m -Xmx1536m -XX:+UseG1GC -XX:SoftRefLRUPolicyMSPerMB=1" evo-workbench-online:latest
try {
    python benchmarks\e0-publication-suite\run_online_microsecend_benchmark.py `
        --manifest benchmarks\e0-publication-suite\corpus_manifest.json `
        --ground-truth-dir D:\UIT\evo-benchmark-cache\EMSE2025SAR-Replication\tool_comparison\groundtruth_textual `
        --replication-dir D:\UIT\evo-benchmark-cache\EMSE2025SAR-Replication `
        --strict-url http://127.0.0.1:18081 `
        --dfd-url http://127.0.0.1:18082 `
        --out-dir D:\UIT\evo-benchmark-cache\results\e6-online-microsecend `
        --timeout-s 300 --resume
} finally {
    docker rm -f evo-e6-strict evo-e6-dfd 2>$null | Out-Null
}
```
