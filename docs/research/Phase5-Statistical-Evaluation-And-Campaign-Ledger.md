# Phase 5 Statistical Evaluation & Campaign Ledger (`WP5.3`)

**Generated:** `2026-11-28` | **Protocol:** Paired Wilcoxon Signed-Rank ($\alpha = 0.05$), Holm–Bonferroni step-down, Cliff's $\delta$ (SSAR bands), SemRef `RDP`.

## 1. Real Artifact Paired Statistical Analysis — `17/17` `microSecEnD` (`dfd-export` vs `strict`)

- **Source CSV:** `D:\UIT\evo-benchmark-cache\results\e6-batch11\e6_per_app.csv`
- **Closed Denominator:** `17/17` paired apps | **Cold == Warm SHA-256 Parity:** `17/17`

| Metric | `strict` Median (IQR) | `dfd-export` Median (IQR) | Wilcoxon $p$ ($W$) | Holm–Bonferroni $p_{\text{adj}}$ | Cliff's $\delta$ (Band) | Median `RDP` |
|---|---:|---:|---:|---:|---|---:|
| `comp_f1` | 0.9167 (0.0777) | 1.0000 (0.0813) | 0.00165625 (0.0) |  **0.00496875** | +0.4533 (`medium`) | 0.5198 |
| `conn_f1` | 0.4571 (0.2375) | 0.9600 (0.1157) | 1.526e-05 (0.0) |  **6.104e-05** | +0.9516 (`large`) | 0.9263 |
| `cold_elapsed_ms` | 48825.0000 (72274.5000) | 47759.0000 (75021.5000) | 0.817581 (71.0) | 0.817581 | -0.0035 (`negligible`) | — |
| `warm_elapsed_ms` | 6305.0000 (8662.5000) | 5821.0000 (8682.0000) | 0.343692 (56.0) | 0.687385 | -0.0104 (`negligible`) | — |

## 2. Live Post-Phase-4 Verified Local Evidence (`CONFIRMED`)

- **Live Multi-JDK `8/11/17/21` Tier-1 Hardened Docker Matrix (`run.py --mode docker-container --m2-readonly-mount --collect-jacoco --behavioral-diff --jdk-version auto`):** `4/4` PASS (`JDK 8: 21.41 s`, `JDK 11: 24.56 s`, `JDK 17: 25.62 s`, `JDK 21: 22.89 s`; `compileExit=0`, `testExit=0`, `baseFail=1 -> candFail=0`, `CanonicalJson` parity).
- **`T4` Spring Oracles (`-Xmx2048m`):** `SpringDifferentialOracleTest` `6/6` (`23` scenarios, `309` worlds, `falseMust=0`, `falseNever=0`, `100.0%` soundness) + `SpringDdnnfOracleSliceTest` `1/1` (`falseMust=0`, `falseNever=0`).
- **Live *Shopizer* (`6a4a0a65`) `--mode=syntax-fast` & *PetClinic* (`818c413`) 3-Way Parity:** *Shopizer* `Cold 88.141 s` / `Warm NO_CHANGE 5.031 s` (`100%` SHA-256 parity); *PetClinic* `Cold 7.500 s == Warm 1.641 s`, `Warm 1-File Edit 7.484 s == Cold Edited 6.703 s` (`100%` SHA-256 parity).
- **FSE 2027 Tool Demo (`docs/fse-2027-tool-demo/verify-demo.py`):** PASS on `evo-workbench-online:latest` (`f0de059b1d1f`, `200 ok`, `repairRecipeCount=22`, `403 FORBIDDEN_ORIGIN`, `23` MCP tools, `7` `evo://` resources).

## 3. Closed-Denominator `EXP-1..EXP-7` Ledger

| ID | Scope | Status | Evidence & Open Gate |
|---|---|---|---|
| `EXP-1` | 17/17 microSecEnD (strict vs dfd-export + baseline correction) | `LOCAL_DOCKER_ARTIFACT_VERIFIED` | 17/17 paired stats computed in Phase 5; bare-metal Ubuntu 24.04 n>=5 pending |
| `EXP-2` | 10 blind Micans + Online Boutique + TeaStore + DeathStarBench | `PENDING_EXTERNAL_CORPUS` | Code WP1.1/WP1.3 complete; Zenodo 15050505 download + Linux run pending |
| `EXP-3` | Passive Fail-Close RPC Resilience (RPCShield + Micans) | `PENDING_EXTERNAL_CORPUS` | Code WP1.2 complete; manual 2-rater audit & fault injection pending |
| `EXP-4` | Modularization (ArchStudio 4, Hadoop 0.19.0, OODT 0.2) | `PENDING_EXTERNAL_CORPUS` | Code WP1.4 complete; SemRef/SSAR corpus download & run pending |
| `EXP-5` | Spring DI/AOP/Metadata & PB-d-DNNF Oracle (15-20 OSS + MeCheck) | `PARTIAL_LOCAL_ORACLE_VERIFIED` | T4 23-scenario/309-world oracle 100% sound (0 falseMust/Never); 18-OSS external clone pending |
| `EXP-6` | Enterprise Scale (Shopizer, ThingsBoard) & Comprehension (Chinthareddy/RepoProbe) | `PARTIAL_LOCAL_VERIFIED` | Live Shopizer syntax-fast (Cold 88.14s, Warm 5.03s, 100% parity) + PetClinic 3-Way verified; ThingsBoard & LLM QA pending |
| `EXP-7` | Verified Refactoring & APR (SWE-Refactor 1,099 + RefAgent 8 + GitBug-Java) | `PARTIAL_SANDBOX_VERIFIED` | Live 4-JDK 8/11/17/21 Tier-1 Docker matrix 4/4 PASS (21.41-25.62s) + 140/140 Maven + 22/22 Python; external corpora/LLM keys pending |

## 4. Closed-Denominator `AB-01..AB-10` Ablation & `M1..M4` Milestone Ledger

| ID | Ablation / Milestone | Status | Verification Scope |
|---|---|---|---|
| `AB-01` | Multi-Layer Topology Fusion & Profile Separation | `LOCAL_PAIRED_STATS_VERIFIED` | 17/17 microSecEnD Wilcoxon+Holm+Cliff+RDP verified; blind Micans pending |
| `AB-02` | AMQP Binding Wildcard & k-CFA Wrapper Folding | `CODE_AND_UNIT_VERIFIED` | Pending blind Micans external run |
| `AB-03` | Counterfactual D1.3/D2.2 & XOR Path Dedup | `CODE_AND_UNIT_VERIFIED` | Pending RPCShield/Shopizer external audit |
| `AB-04` | Utility Damping, 2x Cyclic & Lexical Leiden | `CODE_AND_UNIT_VERIFIED` | Pending ArchStudio/Hadoop/OODT corpus run |
| `AB-05` | COI Slicing + PB-CDCL + Per-Slice d-DNNF | `LOCAL_ORACLE_VERIFIED` | T4 309-world SpringDifferentialOracleTest + SpringDdnnfOracleSliceTest PASS |
| `AB-06` | Exact AsmBytecodeIndex & SAT Qualification | `CODE_AND_UNIT_VERIFIED` | Pending 70-repo MeCheck external corpus run |
| `AB-07` | InterfaceConsumerExpand & k=1 Ego-Graph Budget | `CODE_AND_UNIT_VERIFIED` | Pending live LLM 45-Q Chinthareddy & RepoProbe run |
| `AB-08` | Quarantined TacitMemoryStore & Anti-Edit-Bias Gate | `CODE_AND_UNIT_VERIFIED` | Pending live LLM RepoProbe discussion campaign |
| `AB-09` | SALT Signature(n) Compression & Impact x Richness | `CODE_AND_UNIT_VERIFIED` | Pending live LLM multi-file SWE-Refactor/GitBug-Java token campaign |
| `AB-10` | Dual-Slicing, OpenRewrite Compound, AST Purity & QMOOD Guard | `PARTIAL_SANDBOX_VERIFIED` | Live 4-JDK 8/11/17/21 Docker matrix + AST Purity + QMOOD unit gates PASS; SWE-Refactor 1,099 pending |
| `M1` | FSE 2027 Tool Demo (26/01/2027, CORE A*) | `READY_FOR_SUBMISSION_ARTIFACT` | docs/fse-2027-tool-demo/{README.md,verify-demo.py,walkthrough-script.md,paper/main.tex} + live Docker PASS |
| `M2` | Companion Paper #1 — EMSE/TOSEM/ASE (03–04/2027) | `BLOCKED_ON_EXTERNAL_CORPORA` | Needs EXP-1..EXP-4 + AB-01..AB-04 on Ubuntu 24.04 LTS n>=5 |
| `M3` | Primary Flagship — OOPSLA '27 R2 / ICSE '28 (04–07/2027) | `BLOCKED_ON_EXTERNAL_CORPORA` | Needs EXP-5 18-OSS + MeCheck 70 repos + AB-05/AB-06 on Ubuntu 24.04 LTS n>=5 |
| `M4` | Flagship/Journal Paper #2 — ICSE '28 / IEEE TSE (Q4/2027) | `BLOCKED_ON_EXTERNAL_CORPORA_AND_LLM_KEYS` | Needs EXP-6 + EXP-7 (SWE-Refactor/RefAgent/GitBug-Java) + AB-07..AB-10 |
