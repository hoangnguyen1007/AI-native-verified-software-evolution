# Current State

**Reconciled:** `2026-11-28` | **Baseline:** `main` Phase 0 integrated (`WP0.1–WP0.6` on top of `6331202`; clean per-WP commits `2b5e013`, `24dad45`, `59f3ac3`, `e0845c5`, `1d26982` + WP0.6 freeze).  
**Archive (Zero Loss):** Verbatim M-1 through Batch 11 log (`516 KB`, `1,708` lines) preserved in [current-state-history-2026-11.md](archive/current-state-history-2026-11.md).

## Active Dual-Track Snapshot (2026-11-28)

- **Track A — Product (Online Web App + CLI + MCP):** **CONFIRMED override (2026-11-27):** primary product is the **Online Web App Workbench** (`backend/target/evo-workbench.jar` `72,452,549 B`, Vanilla JS + Cytoscape + Lucian UI in `backend/src/main/resources/web/`, Streamable HTTP MCP `2025-11-25` `21` tools / `6` `evo://` resources, `403 FORBIDDEN_ORIGIN`, SKG-RTS Repair Studio). Completed [full-pipeline demo](roadmap.md#full-pipeline-demo) stays preserved; `cli/target/evo.jar` (`73,599,075 B`) is synchronized with `evo-workbench.jar` for `E0` parity (frozen for new features).
- **Track B — CORE A*/Q1 Research:** Completed 19-paper curated SOTA library ([docs/paper/README.md](paper/README.md), [catalog.json](paper/catalog.json); raw PDFs `.gitignore`d locally), [19-Paper SOTA Deep Synthesis](research/sota-19-paper-deep-synthesis.md), [Comparative Evaluation Report v7](evo-comparative-evaluation-report.md), [Batch 11 E8/E5v2/E9 Report](research/E8-Batch11-Flagship-Polyglot-3Way-E5v2-E9-Report.md), and [SuperPlan v2](evo-sota-superplan-v2.md).

## Phase 0 (`WP0.1–WP0.6`) Verified Metrics (`CONFIRMED`, `2026-11-28`)

| Area | Verified Integrated Metric (`main` WP0.6) | Honest Scope & Remaining Boundary |
|---|---|---|
| Spring DI / COI / CDCL (`WP0.1`) | `IncrementalCdclReasoner` (`cdcl-2wl-1uip-v2`, indexed VSIDS max-heap, Luby `base=100`, LBD `<=2` glue retention, `0` `dpll-m4u.2-v1` in `src/`); `ConeOfInfluenceSlicer` (`935` LOC) on Boot `3.5.14`: `targets=542`, `globalVars=655`, `undirectedMax=415`, **`directedMax=251`**, **`directedMedian=17.5`**, **`exceeding64=145`**; `E5-v3` Real `ApplicationContextRunner` (`3.4.0`): `16` real `@AutoConfiguration`, `59` worlds, `57` beans -> `nonOpaqueExact=50/50 (100%)`, **`falseMust=0`**, **`falseNever=0`**, `nonTautology=PROVED` (`6/6` PASS) | Multi-repo `15–20` real OSS Boot oracle expansion planned in `WP2.5` |
| Storage & Incremental (`WP0.2`) | Chunked manifest + B-tree `rollUpPages` across `6` evidence families; *Shopizer* (`--build=off --offline -Xmx4g`): **`0` `STORAGE_IO_FAILED`**, max row `1.07 MB < 4 MB`, max manifest `44.9 KB < 64 KB`, honest `I3` `rc=3` `PARTIAL`; fresh `cli/target/evo.jar` *PetClinic* (`--build=off --spring-sat --offline`): `rc=0` `COMPLETE`, **`100%` SHA-256** `Cold==Warm NC` (`sha256:f007675f...`) & `Warm 1-File Edit==Cold Edited` (`sha256:4df2e232...`) across all `3` contexts incl. `spring-context` | *ThingsBoard* `1.95M` LOC `-Xmx6g` scaling remains in `WP3.1` (`EVOGBIN2` / syntax-fast) |
| Polyglot Dedup & Perf (`WP0.3+0.4`) | `"redis-cart"` removed; `I5` audit **`0` leaks** (`768` files, `82+9` rules, `56` GT terms); *Online Boutique*: `cartservice` `3->1`, core-12 Service Recall `1.0000`, core+opt-14 F1 `1.0000`, strict Edge F1 `0.9697`; *TeaStore*: `28->7` (`6` WAR + `1` DB, `48/48` endpoints); `apssouza22` Cold `40.5->24.6 s` (`1.65x`, Maven cache-only `3.63x`), `cold==warm1` SHA-256 `100%` | Blind `10`-repo `Micans` holdout (`B4`) deferred to `WP1.1`; full `17/17` Docker Cold median waits on Docker/Linux host |
| Harness & Baselines (`WP0.5`) | `parse_emse_baselines()` matches `complete_results.xlsx`: `attack-graph-generator` `123/41/21` (`uF1=0.7987`) / `279/432/45` (`uF1=0.5391`), `code2DFD` `178/5/4` / `320/18/65` / `86/13/74`; Raw (`182/385/160`) + Collapsed (`179/383/160`) GT + `17`-app endpoint + `SERVICE_COMPONENT` E6 wiring; CausalRepair DOI `10.1145/3832225` | Fresh `17/17` online `E6` run pending Docker image rebuild + Ubuntu LTS host |
| Integrated Gate-0 Checks (`WP0.6`) | `9/9` reactor modules built (`install -DskipTests -Dmaven.compiler.useIncrementalCompilation=false`); **`217/217`** 6-module Maven tests (`analyzer:96`, `analyzer-maven:39`, `analyzer-filesystem:21`, `analyzer-javaparser:9`, `backend:42`, `cli:10`) + **`6/6`** `SpringDifferentialOracleTest` + **`49/49`** Web UI Node + **`25/25`** Python E0 + **`0`** broken links | `mvn clean` blocked on Windows by pre-existing Sept `2026` elevated-Admin ACLs on `*/target/classes` (`0` deleted/renamed Java files in Phase 0); Docker daemon stopped (`docker info rc=1`) |

## Phase 0 Blockers (`B1–B6`) Closure & External Boundaries

| ID | Sev | Status (`2026-11-28`) | Verified Evidence / Open Boundary |
|---|---|---|---|
| **`B1`** | **P0** | **CLOSED (local jars + git); Docker rebuild pending daemon** | `evo-workbench.jar` (`72.45 MB`) & `cli/target/evo.jar` (`73.60 MB`) synchronized on clean `main` (`evo --help rc=0`). Docker Desktop daemon stopped on Windows host (`docker build` pending). |
| **`B2`** | **P0 (`I2`)** | **CLOSED** | *PetClinic* `Warm 1-File Edit == Cold Edited` `100%` SHA-256 on all `3` contexts (`PetClinicIncrementalSpringDeterminismTest` 4/4 + live fresh `evo.jar` `rc=0`). |
| **`B3`** | **P0** | **CLOSED (Shopizer CLI); ThingsBoard in `WP3.1`** | Chunked manifest+pages persistence (`<=1.07 MB` row, `<=44.9 KB` manifest); `0` `STORAGE_IO_FAILED` on *Shopizer* (`-Xmx4g`). *ThingsBoard* `1.95M` LOC tracked in `WP3.1`. |
| **`B4`** | **CRIT (`I5`)** | **CLOSED (I5 code hygiene); blind holdout in `WP1.1`** | `"redis-cart"` purged; hardened `I5` audit `0` leaks (`768` files + `56` GT terms). Blind `10`-repo `Micans` holdout scheduled in `WP1.1`. |
| **`B5`** | **CRIT** | **CLOSED** | `IncrementalCdclReasoner` (`cdcl-2wl-1uip-v2`), `ConeOfInfluenceSlicer` (`935` LOC), and `E5-v3` (`6/6`, `falseMust=0`, `falseNever=0`, `nonTautology=PROVED`) integrated & verified. |
| **`B6`** | **HIGH** | **CLOSED (harness/DOI/dedup/perf); env & real `E9` open** | EMSE parser, Raw+Collapsed GT, DOI `10.1145/3832225`, Online Boutique/TeaStore dedup, Cold perf optimizations landed. **External env open:** Docker rebuild + native **Ubuntu 24.04 LTS `n >= 5`** `E0` v8 campaign; real `E9` in `WP3.4`/`WP4.1`. |

## REPOSITORY REALITY & QUALITY GATES

- **Reactor (`8` modules, JDK `21`, Maven `3.9.16`):** `analyzer`, `analyzer-maven`, `analyzer-filesystem`, `analyzer-execution`, `analyzer-javaparser`, `analyzer-treesitter` (`16` grammars), `backend` (OpenRewrite `8.87.7`, MCP `2025-11-25`, `evo-workbench.jar`), `cli` (`evo.jar`, frozen for new features).
- **Web UI (`backend/src/main/resources/web/`):** Vanilla HTML/CSS/JS + Cytoscape + Lucian (`49/49` Node tests PASS; `0` React/Astryx). Fast `T0–T4` protocol: [docs/testing.md](testing.md).
- **Historical Gates:** `G-1/G0/G1` PASSED · [G2](reproducibility/g2-semantic-adjudication-record-2026-09-10.md) / [G3](reproducibility/g3-spring-adjudication-record-2026-10-14.md) / [G4](reproducibility/g4-adjudication-record-2026-10-15.md) / [G5](reproducibility/g5-adjudication-record-2026-10-15.md) PASSED · `G6/M8.4/M9/M10` PASSED/ACCEPTED · `G7/M11` CLOSED BY HUMAN DECISION ([ADR-011](decisions/ADR-011-full-pipeline-demo.md)).
- **SuperPlan v2 `Gate-0`:** **CODE & LOCAL VERIFICATION PASSED (`WP0.1–WP0.6` landed on `main`); EXTERNAL HOST ENV PENDING** (Docker `evo-workbench-online:latest` rebuild when daemon starts + physical Ubuntu 24.04 LTS `n >= 5` `E0` v8 baseline).

## EXACT NEXT TASK

1. Start Docker Desktop on host (or Linux CI) and run `docker build -t evo-workbench-online:latest .` from the frozen `WP0.6` `main` SHA.
2. Execute the physical **Ubuntu 24.04 LTS `n >= 5`** `E0` v8 campaign (`microSecEnD` `17/17` + flagship polyglot 3-way timing) to close the `B6-env` hardware boundary.
3. Proceed to **FSE 2027 Tool Demo** packaging and **Phase 1 (`WP1.1`)** of [docs/evo-sota-superplan-v2.md](evo-sota-superplan-v2.md).
