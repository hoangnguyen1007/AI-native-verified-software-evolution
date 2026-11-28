# Current State

**Reconciled:** `2026-11-28` | **Baseline:** `HEAD a661db3f4d3756a5ca18fef8897e93912ee9b7e6` (`update UI`) + live uncommitted working tree (Batches 3–11, partial `B5` live-disk patches, governance/build-hygiene updates).  
**Archive (Zero Loss):** Verbatim M-1 through Batch 11 log (`516 KB`, `1,708` lines) preserved in [current-state-history-2026-11.md](archive/current-state-history-2026-11.md).

## Active Dual-Track Snapshot (2026-11-28)

- **Track A — Product (Online Web App + CLI + MCP):** **CONFIRMED override (2026-11-27):** primary product is the **Online Web App Workbench** (`backend/target/evo-workbench.jar`, Docker `evo-workbench-online:latest` `5cdb07ad0f6c`, Vanilla JS + Cytoscape + Lucian UI in `backend/src/main/resources/web/`, Streamable HTTP MCP `2025-11-25` `21` tools / `6` `evo://` resources, `403 FORBIDDEN_ORIGIN`, `0` JS/5xx errors in Headless Edge E2E, `strict`/`dfd-export` selector, SKG-RTS Repair Studio). Completed [full-pipeline demo](roadmap.md#full-pipeline-demo) stays preserved; `cli` is frozen for new features (rebuilt only for `E0` parity in `WP0.6`).
- **Track B — CORE A*/Q1 Research:** Completed 19-paper curated SOTA library ([docs/paper/README.md](paper/README.md), [catalog.json](paper/catalog.json); raw PDFs `.gitignore`d locally), [19-Paper SOTA Deep Synthesis](research/sota-19-paper-deep-synthesis.md), [Comparative Evaluation Report v7](evo-comparative-evaluation-report.md), [Batch 11 E8/E5v2/E9 Report](research/E8-Batch11-Flagship-Polyglot-3Way-E5v2-E9-Report.md), and [SuperPlan v2](evo-sota-superplan-v2.md).

## Verified Live Metrics (v7 / Batch 11, `CONFIRMED`)

| Area | Verified Metric (`a661db3` + working tree) | Honest Scope & Limits |
|---|---|---|
| `microSecEnD` (`17/17`, Docker `--build=off`) | `Evo[dfd-export]`: COMP `uF1=0.9632` / CONN `uF1=0.9266`; `Evo[strict]`: COMP `uF1=0.9204` / CONN `uF1=0.4733`; `17/17` `cold==warm1==steady` SHA-256 | `dfd-export` tuned in-sample on all 17 apps (`B4`, no blind holdout yet); Cold regressed `2.50x` (`19.51->48.83 s`, `B6`) |
| Flagship Polyglot (`strict`, Win CLI & Docker) | *TeaStore*: `48` endpoints (`28` JAX-RS + `20` Servlet), Cold `19.86 s` / Warm NC `2.02 s` (`9.85x`) / 1-Edit `14.67 s` (`1.35x`, SHA-256 PASS); *Online Boutique*: `14/16` RPC (`F1=0.9333`), combined `F1=0.9091`; *ewolff* (`n=3` Docker): Cold `29.20 s` / Warm NC `10.16 s` (`2.88x`) / 1-Edit `21.10 s` (`1.38x`, `3/3` SHA-256 PASS) | *Online Boutique* duplicates `cartservice` `3x`; *TeaStore* emits `28` comps for `7` services (`B6`); Ubuntu LTS `n >= 5` pending |
| Spring DI (`ASM` + `COI` + `CDCL`) | Passive ASM `spring-boot-autoconfigure-3.5.14.jar` (`452` configs / `574` beans / `1,140` conds) in `143 ms`; CDCL `28.0x` vs DPLL on `PHP(5,4)`; Kleene `65,536->2` traces (`32,768x`); `2` real `False-MUST` fixed; `E5-v2` `345` worlds `0` False-MUST/NEVER | Live-disk `ConeOfInfluenceSlicer` (`920` LOC) & `E5-v3` (`3,687` LOC) awaiting acceptance; `IncrementalCdclReasoner` bugs open (`B5`) |
| SKG, SCIP, Quarantine & Sandbox | `ScipProtobufReader` `81,011 occ/s`; `AdvisoryOverlayService` `canonicalLeakage=0`; `llm-redact:v2` `15/15` secrets redacted; `QEMU` `9/9` negative control (`RESOLVED` vs `PERSISTED`) | `E9` (`SKG-RTS`, RRF, Quarantine) synthetic-only (`B6`); real-repo `E9` planned in Phases 3–4 |

## Active Blockers (`B1–B6`, Live-Disk `2026-11-28`)

| ID | Sev | Live Disk State (`CONFIRMED`) | WP |
|---|---|---|---|
| **`B1`** | **P0** | **Unpatched:** `cli/target/evo.jar` lags `evo-workbench.jar` by `42` Java files; Batches 3–11 uncommitted. | `WP0.6` |
| **`B2`** | **P0 (`I2`)** | **Unpatched:** *PetClinic* (`500158f`) Warm `1-File Edit` (`OwnerController.java`) drifts `spring-context` SHA-256 (`+6` nodes / `+4` edges). | `WP0.2` |
| **`B3`** | **P0** | **Unpatched:** *Shopizer* (`115k` LOC) crashes CLI (`SqliteGraphProjection.java:510` `64 MB` row) & OOMs `3.5 GB` Docker; *ThingsBoard* (`1.95M` LOC) hits `MEMORY_LIMIT` at `-Xmx8g`. | `WP0.2`, `WP3.1` |
| **`B4`** | **CRIT (`I5`)** | **Unpatched:** Hardcoded `"redis-cart"` in `DeclarativeTopologyProjection.java:30,46,64` + in-sample `DFD_EXPORT` rules without blind holdout. | `WP0.3`, `WP1.1` |
| **`B5`** | **CRIT** | **Partially patched on live disk (uncommitted/unaccepted):** `ConeOfInfluenceSlicer.java` (`920` LOC) & `SpringDifferentialOracleTest.java` (`3,687` LOC `E5-v3`, real `ApplicationContextRunner`, `backend/pom.xml` adds `spring-boot-autoconfigure:3.4.0` / `spring-boot-test:3.4.1`). **Still unpatched:** `IncrementalCdclReasoner.java:334–348,571–585` & `SatConfigurationReasoner.java:14` (`"dpll-m4u.2-v1"`). | `WP0.1` |
| **`B6`** | **HIGH** | **Unpatched:** `parse_emse_baselines()` drops `FP:`/`TP(k)`; Cold regressed `2.50x`; *Online Boutique* `cartservice` `3x` & *TeaStore* `28->7` dedup; synthetic `E9`; `n >= 5` Ubuntu LTS pending; DOI typo `10.1145/3832252 -> 10.1145/3832225` in report `[31]`. | `WP0.3`–`WP0.6` |

## REPOSITORY REALITY

- **Reactor (`8` modules, JDK `21`, Maven Wrapper `3.9.16`):** `analyzer`, `analyzer-maven`, `analyzer-filesystem`, `analyzer-execution`, `analyzer-javaparser`, `analyzer-treesitter` (`16` grammars), `backend` (OpenRewrite `8.87.7`, JDK HTTP `WorkbenchApiMain`, MCP `2025-11-25`, `evo-workbench.jar`), `cli` (frozen for new features).
- **Web UI (`backend/src/main/resources/web/`):** `47` Vanilla HTML/CSS/JS files + Cytoscape + Lucian (`0` React/Astryx; no `frontend/`). Validated via `node --check` + `node --test` (`~1.5 s`).
- **Tests:** `423` JUnit classes (`2,610` `@Test`s), `3` Web UI CJS suites (`49` tests), `6` polyglot CJS suites, `53` Python suites. Fast `T0–T4` protocol: [docs/testing.md](testing.md).

## QUALITY GATES

| Gate | State | Evidence / Remaining Boundary |
|---|---|---|
| G-1 / G0 / G1 | PASSED (historical) | Governance baseline, JDK 21 / Maven 3.9.16 wrapper, M1 contracts |
| G2 / M4-R0 / G3 | PASSED | [G2 (2026-09-10)](reproducibility/g2-semantic-adjudication-record-2026-09-10.md) · [G3 (2026-10-14)](reproducibility/g3-spring-adjudication-record-2026-10-14.md) (`37/37` obligations, `0` false certainty) |
| G4 / G5 | PASSED | [G4](reproducibility/g4-adjudication-record-2026-10-15.md) (`165` tests) · [G5](reproducibility/g5-adjudication-record-2026-10-15.md) (`250` tests, ADR-002 score frozen) |
| G6 / M8.4 / M9 / M10 | PASSED / HUMAN ACCEPTED | M8.4 closed (`2026-11-10`); M9 ([M9.5](tasks/m9/05-execution.md)); M10 ([M10 Sign-Off](tasks/m10/01-release-sign-off.md)) |
| G7 Track B / M11 | CLOSED BY HUMAN DECISION / NOT CERTIFIED PASSED | [ADR-011](decisions/ADR-011-full-pipeline-demo.md); [M11.6](tasks/m11/06-execution.md) retains open assertions |
| SuperPlan v2 `Gate-0` | IN PROGRESS (`B1–B6` open) | Requires closing `WP0.1–WP0.6` before FSE Tool Demo & Phases 1–4 |

## EXACT NEXT TASK

Execute **Phase 0 (`WP0.1–WP0.6`)** of [docs/evo-sota-superplan-v2.md](evo-sota-superplan-v2.md) after live `git diff` reconciliation:
1. **`WP0.1` (`B5`):** Verify live-disk `ConeOfInfluenceSlicer` (`920` LOC) + `SpringDifferentialOracleTest` (`E5-v3`, `'-DargLine=-Xmx2048m'`); patch `IncrementalCdclReasoner.java:334–348,571–585` & `SatConfigurationReasoner.java:14`.
2. **`WP0.2` (`B2`, `B3`):** Chunk `spring.symbolic-result` storage (unblocking *Shopizer* CLI) and fix *PetClinic* `spring-context` `Warm 1-File Edit` SHA-256 drift.
3. **`WP0.3–WP0.5` (`B4`, `B6`):** Remove `"redis-cart"` (`I5`), deduplicate *Online Boutique* (`3->1`) & *TeaStore* (`28->7`), recover Cold `2.50x` latency, fix `parse_emse_baselines()` & DOI `[31]`.
4. **`WP0.6` (`B1`):** Freeze clean Git SHA, sync `evo-workbench.jar` + `cli/target/evo.jar` + Docker image, and record Ubuntu LTS `E0` (`n >= 5`) baseline to unlock FSE Tool Demo & Phases 1–4.
