# Current State

**Reconciled:** `2026-11-28` | **Baseline:** `main` **Phases 0–4 CLOSED (`25/25` engineering WPs `WP0.1–WP4.4`, `fffbfc0` on `origin/main`)** + **Phase 5 ACTIVE (`WP5.1–WP5.3` landed; `WP5.4` external campaigns next)**.  
**Archive:** M-1..Batch 11 verbatim log (`516 KB`, `1,708` lines) in [current-state-history-2026-11.md](archive/current-state-history-2026-11.md).

## Active Dual-Track Snapshot (2026-11-28)

- **Track A — Product:** **CONFIRMED override (2026-11-27):** primary product is the **Online Web App Workbench** (`backend/src/main/resources/web/`, Streamable HTTP MCP `2025-11-25` **`23` tools / `7` `evo://` resources**, `403 FORBIDDEN_ORIGIN`, SKG-RTS Repair Studio **`22`** recipes; rebuilt `evo-workbench.jar` `73.96 MB`, `evo.jar` `75.11 MB`, `evo-workbench-online:latest` `f0de059b1d1f`; [FSE 2027 Tool Demo](fse-2027-tool-demo/README.md) `verify-demo.py` PASS). [Full-pipeline demo](roadmap.md#full-pipeline-demo) preserved; `cli/target/evo.jar` frozen for new features.
- **Track B — CORE A*/Q1 Research:** 19-paper SOTA library ([docs/paper/README.md](paper/README.md), [catalog.json](paper/catalog.json)), [19-Paper Synthesis](research/sota-19-paper-deep-synthesis.md), [Comparative Report v7](evo-comparative-evaluation-report.md), [Phase 5 Statistical Ledger](research/Phase5-Statistical-Evaluation-And-Campaign-Ledger.md), [SuperPlan v2](evo-sota-superplan-v2.md).

## Phase 5 — Publication Execution & Statistical Rigor (`ACTIVE`, `2026-11-28`)

| Work Package | Landed / Active Scope | Verified Metrics & Open Gate |
|---|---|---|
| `WP5.1` (`M1` Demo) | [FSE 2027 Tool Demo](fse-2027-tool-demo/README.md): ACM `sigconf` `4+1`-p. draft ([main.tex](fse-2027-tool-demo/paper/main.tex) + [references.bib](fse-2027-tool-demo/paper/references.bib)), 4-min [walkthrough-script.md](fse-2027-tool-demo/walkthrough-script.md), live [verify-demo.py](fse-2027-tool-demo/verify-demo.py) | **DONE (Artifact Ready):** `verify-demo.py` PASS on `evo-workbench-online:latest` (`f0de059b1d1f`, `22` recipes, `403 FORBIDDEN_ORIGIN`, `23/7` MCP); `pdflatex` & screencast capture next |
| `WP5.2` (Stats Engine) | `benchmarks/e0-publication-suite/stats_publication_evaluator.py`: exact DP + tie-corrected Wilcoxon, Holm–Bonferroni step-down, Cliff's $\delta$ (SSAR bands), SemRef `RDP`, Spearman $\rho$, Judge `StdDev`/`Range` | **DONE:** zero-dependency deterministic stdlib implementation + unit suite (`test_stats_publication_evaluator.py`) PASS |
| `WP5.3` (Campaign Ledger) | `run_phase5_statistical_ledger.py` + [Phase5-Statistical-Evaluation-And-Campaign-Ledger.md](research/Phase5-Statistical-Evaluation-And-Campaign-Ledger.md) (`EXP-1..7`, `AB-01..10`, `M1..M4`) | **LOCAL COMPLETE:** `17/17` `microSecEnD` paired stats (`conn_f1` Holm $p=6.104\times 10^{-5}$, $\delta=+0.9516$ `large`; `comp_f1` $p=0.00497$, $\delta=+0.4533$ `medium`; `17/17` SHA-256) |
| `WP5.4` (External Runs) | Physical Ubuntu `24.04` LTS `n>=5` `E0`, *ThingsBoard* `-Xmx6g`, blind `10`-repo `Micans`, `18`-OSS `spring_sat_20_oss` oracle, `EXP-7` (`SWE-Refactor`/`RefAgent`/`GitBug-Java`) & submissions `M1–M4` | **NEXT:** requires external corpora, bare-metal Ubuntu `24.04` LTS host & LLM API keys |

## Closed Engineering Phases (`Phase 0–4`, `25/25` WPs `CLOSED`)

- **Phase 4 (`WP4.1–WP4.4`, `2263fcb..fffbfc0` `CLOSED`):** `140/140` 9-module Maven + `29/29` `T4` Oracles (`309` worlds, `falseMust=0`, `falseNever=0`, `100%` soundness) + `22/22` Python + `6/6` `I5` + `49/49` Node + Java 8–21 `ClassDependencyCensus` & live **4/4 Multi-JDK `8/11/17/21`** Tier-1 Docker matrix (`21.41–25.62 s`, `baseFail 1->0`, `CanonicalJson` parity) + live *Shopizer* `syntax-fast` (`Cold 88.14 s`, `Warm 5.03 s`, `100%` SHA-256) + *PetClinic* 3-Way (`100%` SHA-256).
- **Phase 3 (`WP3.1–WP3.5`, `47a16a8` `CLOSED`, `180/180` Maven + `42/42` Python):** `--mode=syntax-fast` + `InterfaceConsumerExpand` + `k=1` Ego (`04f6cae`); `GraphComposeQuery` + MCP #23/`evo://` #7 (`94103a4`); quarantined `TacitMemoryStore` (`e6fcce2`); `PreEditConfigGate` + `BENCHMARK_QA` + `evo ask` (`4356abc`); `EVOGBIN2` (`45.27x` vs JSON, `27fd839`).
- **Phase 2 (`WP2.1–WP2.5`, `ae6a474` `CLOSED`, `164/164` Maven + `37/37` Python):** XML/`getBean()`/`@Aspect`/`@Scope` (`e7ebcbb`, `b71a061`); `MetadataConsistencyRulePack` + `ClasspathTypeLookup` (`0154994`, `f5ae595`); PB-CDCL + pure-Java d-DNNF (`542/542`, `376.4 ms`, Thm-1 `1,000/1,000`, `718ffb8`, `aa94a63`, `b44a7e5`); SALT `88.80%` compression (`0d84627`, `0d833c3`).
- **Phase 1 (`WP1.1–WP1.5`, `3daf3e7` `CLOSED`, `169/169` Maven + `25/25` Python):** AMQP + `k=2` folding + Gateway DSL + blind `Micans`/`DeathStarBench` manifests (`70fd14b`); `FailCloseResilienceAnalyzer` (`87279f1`); 3-tier concordance (`b2facef`); Leiden + SemRef (`06398c4`); `PolyglotScopeGraphResolver` (`93cb53b`).
- **Phase 0 (`WP0.1–WP0.6`, `bdc838d` `CLOSED`, `217/217` Maven):** `cdcl-2wl-1uip-v2`, `ConeOfInfluenceSlicer`, `E5-v3`, chunked *Shopizer*, `cartservice 3->1`, `TeaStore 28->7`, EMSE Raw/Collapsed GT (`B1–B6` code-closed).

## REPOSITORY REALITY & EXACT NEXT TASK

- **Reactor (`8` modules, JDK `21`, Maven `3.9.16`):** `analyzer`, `analyzer-maven`, `analyzer-filesystem`, `analyzer-execution`, `analyzer-javaparser`, `analyzer-treesitter` (`16` langs), `backend` (OpenRewrite `8.87.7`, MCP `2025-11-25` **`23/7`**, Vanilla Web UI `49/49` Node), `cli` (frozen). Fast `T0–T4`: [docs/testing.md](testing.md). Historical `G-1..G7/M8.4..M11` CLOSED ([ADR-011](decisions/ADR-011-full-pipeline-demo.md)).
- **Next Task (`Phase 5 / WP5.4`):** Compile FSE '27 Demo PDF (`main.tex`), record 4-min screencast (`walkthrough-script.md`), and execute external `EXP-1..EXP-7` / `AB-01..AB-10` campaigns on bare-metal Ubuntu `24.04` LTS (`n>=5`) + LLM API keys.
