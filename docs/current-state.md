# Current State

**Reconciled:** `2026-11-28` | **Baseline:** `main` **Toàn bộ `Phases 0–4` (`WP0.1–WP4.4`, `25/25` WPs) `CLOSED / COMPLETE`** (`140/140` 9-module Maven + `29/29` `T4` Oracles + `22/22` `analyzer-execution` Python + `47/47` `e0-publication-suite` Python + `49/49` Web UI Node + `6/6` `I5` `0` leaks + **4/4 Live Multi-JDK `8/11/17/21` Docker matrix** + live *Shopizer* / *PetClinic* `100%` SHA-256 parity).  
**Archive:** M-1..Batch 11 verbatim log (`516 KB`, `1,708` lines) in [current-state-history-2026-11.md](archive/current-state-history-2026-11.md).

## Active Dual-Track Snapshot (2026-11-28)

- **Track A — Product:** **CONFIRMED override (2026-11-27):** primary product is the **Online Web App Workbench** (`backend/src/main/resources/web/`, Streamable HTTP MCP `2025-11-25` **`23` tools / `7` `evo://` resources**, `403 FORBIDDEN_ORIGIN`, SKG-RTS Repair Studio **`22`** recipes; rebuilt `evo-workbench.jar` `73.96 MB`, `evo.jar` `75.11 MB`, `evo-workbench-online:latest` `f0de059b1d1f`; [FSE 2027 Tool Demo](fse-2027-tool-demo/README.md) `verify-demo.py` PASS). [Full-pipeline demo](roadmap.md#full-pipeline-demo) preserved; `cli/target/evo.jar` frozen for new features.
- **Track B — CORE A*/Q1 Research:** 19-paper SOTA library ([docs/paper/README.md](paper/README.md), [catalog.json](paper/catalog.json)), [19-Paper Synthesis](research/sota-19-paper-deep-synthesis.md), [Comparative Report v7](evo-comparative-evaluation-report.md), [SuperPlan v2 Statistical Ledger](research/Phase5-Statistical-Evaluation-And-Campaign-Ledger.md), [SuperPlan v2](evo-sota-superplan-v2.md) (**Phases 0–4 `25/25` WPs `CLOSED / COMPLETE`**).

## Phase 4 (`WP4.1–WP4.4`) — `CLOSED / COMPLETE` (`2026-11-28`, `25/25` Total WPs Done)

| Work Package | Landed Production Code (`2263fcb..fffbfc0`) | Verified Acceptance Evidence |
|---|---|---|
| `WP4.1` | `JavaMethodSlicer.sliceFailingTest` (SDG + `CollectionContainerSummary` + SKG helpers `depth<=3`), `RepairContextPlanner` (AST Skeleton + Javadoc + `DYNAMIC_SLICE_TIMEOUT_OR_UNAVAILABLE`), `AffectedTestSelector` (DI/AOP + `calibrateAgainstExecutionTrace`) | `DualSlicingAndSkeletonTest` (`6/6`), `JavaMethodSlicerTest` (`6/6`), `CapturedRepairV2Test` (`9/9`): `missedTestRate == 0.0` |
| `WP4.2` | `QmoodQualityModel` (11 OO metrics, Understandability `-2.0%` + SCC guard, zero-smell `MetricsOnlyBrief`), `ClosedLoopRepairOrchestrator` (`APR m<=5x3`, `Refactoring <=12`, Oscillation Guard, rollback + `REPAIR_BUDGET_EXHAUSTED`, Patch Augmentation `<=25`, 4-Gate ranking) | `QmoodQualityModelTest` (`7/7`), `ArchitectureAssessmentV2ServiceTest` (`5/5`), `CapturedRepairV2Test` (`9/9`) |
| `WP4.3` | Dual-Gate `AstRefactoringPurityVerifier` (Structural Intent + Non-Target Purity), `OpenRewriteCompoundRecipes` (`6` recipes -> `22` total in `JavaRepairRecipes`), quarantined `RefactoringExemplarStore` (`canonicalLeakage = 0`) | `AstRefactoringPurityVerifierTest` (`6/6`, `100%` hollow-stub catch), `CapturedRepairTest` (`43/43`) |
| `WP4.4` | Java 8–21 `ClassDependencyCensus`, pinned `JDK_IMAGE_MATRIX` (`8/11/17/21` `eclipse-temurin` SHA-256 digests), `--m2-readonly-mount`, XXE-hardened JaCoCo (`UNKNOWN_UNTESTED`), 3-Layer Certification (`Compile&Test+Coverage` $\land$ `AST Purity` $\land$ `Negative Control`) | **Live 4/4 Multi-JDK `8/11/17/21` Docker matrix PASS** (`8: 21.41 s`, `11: 24.56 s`, `17: 25.62 s`, `21: 22.89 s`; `baseFail 1->0`, `CanonicalJson` parity) + `22/22` Python |
| **Phase 4 Gate** | Rebuilt `evo-workbench.jar` + `evo.jar` + `evo-workbench-online:latest`; [FSE 2027 Tool Demo](fse-2027-tool-demo/README.md) (`verify-demo.py`, `paper/main.tex`); `T4` Oracles (`29/29`, `309` worlds, `falseMust=0`, `falseNever=0`); live *Shopizer* (`Cold 88.14 s`, `Warm 5.03 s`) & *PetClinic* 3-Way (`100%` SHA-256) | **CLOSED (`25/25` WPs `WP0.1–WP4.4` complete)** |

## Phases 0–3 (`WP0.1–WP3.5`) — `CLOSED / COMPLETE`

- **Phase 3 (`WP3.1–WP3.5`, `47a16a8` `CLOSED`, `180/180` Maven + `42/42` Python):** `--mode=syntax-fast` + `InterfaceConsumerExpand` + `k=1` Ego (`04f6cae`); `GraphComposeQuery` + MCP #23/`evo://` #7 (`94103a4`); quarantined `TacitMemoryStore` (`e6fcce2`); `PreEditConfigGate` + `BENCHMARK_QA` + `evo ask` (`4356abc`); `EVOGBIN2` (`45.27x` vs JSON, `27fd839`).
- **Phase 2 (`WP2.1–WP2.5`, `ae6a474` `CLOSED`, `164/164` Maven + `37/37` Python):** XML/`getBean()`/`@Aspect`/`@Scope` (`e7ebcbb`, `b71a061`); `MetadataConsistencyRulePack` + `ClasspathTypeLookup` (`0154994`, `f5ae595`); PB-CDCL + pure-Java d-DNNF (`542/542`, `376.4 ms`, Thm-1 `1,000/1,000`, `718ffb8`, `aa94a63`, `b44a7e5`); SALT `88.80%` compression (`0d84627`, `0d833c3`).
- **Phase 1 (`WP1.1–WP1.5`, `3daf3e7` `CLOSED`, `169/169` Maven + `25/25` Python):** AMQP + `k=2` folding + Gateway DSL + blind `Micans`/`DeathStarBench` manifests (`70fd14b`); `FailCloseResilienceAnalyzer` (`87279f1`); 3-tier concordance (`b2facef`); Leiden + SemRef (`06398c4`); `PolyglotScopeGraphResolver` (`93cb53b`).
- **Phase 0 (`WP0.1–WP0.6`, `bdc838d` `CLOSED`, `217/217` Maven):** `cdcl-2wl-1uip-v2`, `ConeOfInfluenceSlicer`, `E5-v3`, chunked *Shopizer*, `cartservice 3->1`, `TeaStore 28->7`, EMSE Raw/Collapsed GT (`B1–B6` code-closed).

## SuperPlan v2 Phần 4–5 — Statistical Engine & Campaign Ledger (`EXP-1..7`, `AB-01..10`, `M1..M4`)

- **CORE A\*/Q1 Statistical Evaluator (`§4.1`):** `benchmarks/e0-publication-suite/stats_publication_evaluator.py` (`47/47` suite tests PASS: Wilcoxon exact DP + tie-corrected, Holm–Bonferroni, Cliff's $\delta$ SSAR bands, SemRef `RDP`, Spearman $\rho$, Judge `StdDev`/`Range`).
- **Real-Artifact Paired Audit & Closed-Denominator Ledger:** [Phase5-Statistical-Evaluation-And-Campaign-Ledger.md](research/Phase5-Statistical-Evaluation-And-Campaign-Ledger.md) (`17/17` `microSecEnD` `dfd-export` vs `strict`: `conn_f1` Holm $p_{\text{adj}} = 6.104 \times 10^{-5}$, Cliff's $\delta = +0.9516$ `large`; `comp_f1` $p_{\text{adj}} = 0.004969$, $\delta = +0.4533$ `medium`; `17/17` SHA-256 parity).

## REPOSITORY REALITY & EXACT NEXT TASK

- **Reactor (`8` modules, JDK `21`, Maven `3.9.16`):** `analyzer`, `analyzer-maven`, `analyzer-filesystem`, `analyzer-execution`, `analyzer-javaparser`, `analyzer-treesitter` (`16` langs), `backend` (OpenRewrite `8.87.7`, MCP `2025-11-25` **`23/7`**, Vanilla Web UI `49/49` Node), `cli` (frozen). Fast `T0–T4`: [docs/testing.md](testing.md). Historical `G-1..G7/M8.4..M11` CLOSED ([ADR-011](decisions/ADR-011-full-pipeline-demo.md)).
- **Next Task (SuperPlan v2 Phần 4–5 External Campaigns `EXP-1..EXP-7` / `AB-01..AB-10` / `M1–M4`):** All `Phases 0–4` (`WP0.1–WP4.4`, `25/25` WPs) are **CLOSED / COMPLETE**. Remaining publication campaigns require external benchmark corpora, bare-metal Ubuntu `24.04` LTS (`n >= 5`) timing runs, and LLM API keys for `EXP-6`/`EXP-7`.
