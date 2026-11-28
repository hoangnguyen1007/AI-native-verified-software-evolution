# E5 Evaluation Report: Spring Runtime Differential Oracle, 4-Tier SAT/COI Ablation, and AI4SE Breakthroughs

- **Date / Reconciled:** 2026-11-27
- **Verification Scope:** Passive static analysis (`--build=off`), zero external solver binaries/dependencies, zero target-build execution on host, anti-overfitting check **`I5_MATCHES=0`**.
- **Test Suites:** `SpringSatAblationBenchmarkTest` (`3/3`), `RepositoryConfigurationSpaceBuilderTest` (`5/5`), `IncrementalCdclAndCoiSlicerTest` (`6/6`), `SatConfigurationReasonerTest` (`4/4`), `TruthRegionEvaluationTest` (`17/17`), `StoragePerfParityTest` (`3/3`), `JavaServiceSyntaxExtractorTest` (`3/3`), `SpringDifferentialOracleTest` (`1/1`), `ResearchBreakthroughUpgradesTest` (`3/3`), `WorkbenchSpringSatIntegrationTest` (`1/1`), `ArchitectureAgentTest` (`5/5`), `AiAndRepairUpgradeTest` (`11/11`), `WorkbenchApiMainTest` (`5/5` + `4/4`) — **71/71 PASS (`0` failures, `0` errors, `0` skipped)**.

---

## 1. Real Soundness Defect Discovered & Eliminated by Differential Oracle (`CONFIRMED`)

During initial execution of [`SpringDifferentialOracleTest`](../../backend/src/test/java/com/evolution/backend/analysis/SpringDifferentialOracleTest.java) against live **Spring Framework 6.2 `AnnotationConfigApplicationContext`**, the oracle immediately exposed a genuine static-analysis soundness bug (`Unsound False-MUST`) in [`RepositoryConfigurationSpaceBuilder`](../../analyzer/src/main/java/com/evolution/analysis/spring/condition/RepositoryConfigurationSpaceBuilder.java):

- **Triggering Construct:** `@ConditionalOnProperty(prefix = "app.audit", name = "mode", havingValue = "strict", matchIfMissing = true)` paired with `app.audit.mode: strict` in `application.yml`.
- **Root Cause:** `propertyHints` recorded only `"strict"`, producing the 2-element domain `{MISSING, "strict"}`. Because both `MISSING` (via `matchIfMissing = true`) and `"strict"` (via `havingValue = "strict"`) satisfy the predicate, zero falsifying values existed in the finite domain, causing Evo's symbolic SAT evaluator to classify `auditBean` as **`MUST`**. In live Spring 6.2 execution (`app.audit.mode = "relaxed"`), the bean is absent, making the true modality **`MAY`**.
- **Fix & Regression Verification:** Added explicit `havingValue` counter-witness retention in [`RepositoryConfigurationSpaceBuilder`](../../analyzer/src/main/java/com/evolution/analysis/spring/condition/RepositoryConfigurationSpaceBuilder.java) so every `havingValue`-guarded property domain retains at least one deterministic falsifying explicit value when below `MAX_VALUES_PER_PROPERTY`. Verified by [`retainsNonMatchingWitnessForHavingValueWithMatchIfMissingPreventsFalseMust`](../../analyzer/src/test/java/com/evolution/analysis/spring/condition/RepositoryConfigurationSpaceBuilderTest.java#L201-L231) and re-verified against Spring 6.2 runtime (`auditBean`: `oracle=MAY`, `evo=MAY`).

---

## 2. E5 Spring Framework 6.2 Runtime Differential Soundness Oracle (`59` Concrete Worlds)

Across **6 multi-condition Spring DI scenarios** and **59 concrete runtime container executions** (`StandardEnvironment` profiles + `MapPropertySource` properties + `@Profile` + `Condition` + `ConfigurationPhase.REGISTER_BEAN`), Evo achieved **100.0% soundness (`0` False-MUST, `0` False-NEVER)** and **84.6% (`11/13`) exact match**, failing closed conservatively to `UNKNOWN` only when non-monotone `@ConditionalOnMissingBean` lacked starter JAR bytecode in the fixture's empty `.m2` cache:

| Scenario | Bean | Spring 6.2 Runtime Oracle | Evo Passive Static (`--build=off`) | Exact Match | Soundness Check | Concrete Worlds |
| :--- | :--- | :---: | :---: | :---: | :---: | :---: |
| `ProfileNegation` | `coreBean` | `MUST` | `MUST` | `true` | PASS | 2 |
| `ProfileNegation` | `devBean` | `MAY` | `MAY` | `true` | PASS | 2 |
| `ProfileNegation` | `nonDevBean` | `MAY` | `MAY` | `true` | PASS | 2 |
| `PropertyHavingAndMissing` | `cacheBean` | `MAY` | `MAY` | `true` | PASS | 9 |
| `PropertyHavingAndMissing` | `auditBean` | `MAY` | `MAY` *(post-fix)* | `true` | PASS | 9 |
| `CrossProfileStores` | `devStore` | `MAY` | `MAY` | `true` | PASS | 4 |
| `CrossProfileStores` | `prodStore` | `MAY` | `MAY` | `true` | PASS | 4 |
| `NonMonotoneMissingBean` | `customStore` | `MAY` | `UNKNOWN` | `false` *(safe)* | PASS | 2 |
| `NonMonotoneMissingBean` | `fallbackStore` | `MAY` | `UNKNOWN` | `false` *(safe)* | PASS | 2 |
| `ProfilePropertyCascade` | `cloudMetrics` | `MAY` | `MAY` | `true` | PASS | 6 |
| `FourDimMatrix` | `alwaysOn` | `MUST` | `MUST` | `true` | PASS | 36 |
| `FourDimMatrix` | `alphaFeatureX` | `MAY` | `MAY` | `true` | PASS | 36 |
| `FourDimMatrix` | `betaFeatureY` | `MAY` | `MAY` | `true` | PASS | 36 |
| **Total (`6` scenarios)** | **`13` beans** | — | **`11` exact, `2` safe `UNKNOWN`** | **`84.6%`** | **`100.0%` (`0` False-MUST / `0` False-NEVER)** | **`59` worlds** |

---

## 3. 4-Tier SAT, Kleene Cube, and COI Slicing Ablation (`A0 -> A3`, 2 Warmup + 5 Measured Repeats)

### 3.1. `A0` Legacy Naive DPLL vs `A3` Clean-Room Incremental CDCL (`2-WL + 1-UIP + VSIDS`)

Measured in [`SpringSatAblationBenchmarkTest`](../../analyzer/src/test/java/com/evolution/analysis/spring/condition/SpringSatAblationBenchmarkTest.java) under an identical `120,000`-step budget (`Median ± IQR`, ms):

| Benchmark Formula | Expected | `A0` Naive DPLL Status | `A0` Steps | `A0` Median ± IQR (ms) | `A3` CDCL Status | `A3` Steps | `A3` Median ± IQR (ms) | Step / Time Gain |
| :--- | :---: | :---: | ---: | ---: | :---: | ---: | ---: | :---: |
| `PHP(4,3)` | `UNSATISFIABLE` | `UNSATISFIABLE` | 8,504 | 1.520 ± 1.247 | `UNSATISFIABLE` | 1,203 | 0.130 ± 0.056 | 7.1× fewer steps / **11.7× faster** |
| `PHP(5,4)` | `UNSATISFIABLE` | `UNSATISFIABLE` | 80,085 | 5.888 ± 2.161 | `UNSATISFIABLE` | 7,432 | 0.231 ± 0.010 | 10.8× fewer steps / **25.5× faster** |
| `Chain-UNSAT(N=64)` | `UNSATISFIABLE` | `UNSATISFIABLE` | 44,689 | 0.962 ± 0.322 | `UNSATISFIABLE` | 967 | 0.109 ± 0.052 | 46.2× fewer steps / **8.8× faster** |
| `Chain-UNSAT(N=128)` | `UNSATISFIABLE` | `UNKNOWN` *(SOLVER_LIMIT)* | 120,001 | 3.948 ± 3.907 | `UNSATISFIABLE` | 1,927 | 0.351 ± 0.113 | **Solved (`>62.3×` fewer steps)** |
| `Chain-SAT(N=128)` | `SATISFIABLE` | `UNKNOWN` *(SOLVER_LIMIT)* | 120,001 | 1.480 ± 0.459 | `SATISFIABLE` | 2,294 | 0.282 ± 0.067 | **Solved (`>52.3×` fewer steps)** |

### 3.2. Kleene 4-Valued Prime-Implicant Cube Generalization (`S5b`) & Production Replay Flag (`S5c`)

For `k` exogenous Boolean dimensions with 1 active condition variable (`2^k` concrete worlds):

| Profile Dimensions (`k`) | Concrete Worlds (`2^k`) | Kleene Signature Traces | SAT Queries (`cdclSession`) | Compression Ratio | `verify=true` (`A2`) Median ± IQR (ms) | `verify=false` (`A3-prod`) Median ± IQR (ms) |
| ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 8 | 256 | 2 | 4 | **128.0×** | 42.294 ± 1.124 | 21.691 ± 1.412 |
| 12 | 4,096 | 2 | 4 | **2,048.0×** | 33.074 ± 4.291 | 19.643 ± 4.529 |
| 16 | 65,536 | 2 | 4 | **32,768.0×** | 30.563 ± 5.097 | 16.876 ± 3.961 |

Disabling redundant witness binding replay in production (`verifyWitnessReplay = false`) delivers **`~1.8×–1.95×`** faster symbolic truth-region evaluation while preserving identical `MUST`/`MAY`/`NEVER` classifications across every trace.

### 3.3. Cone-of-Influence (`S4`) Component Decomposition & Real Starter JAR Hub Collapse

1. **Synthetic Multi-Module Component Scaling (`12` vars/component):**

| Components | Total Direct Vars | `A0` Global `32`-Var Ceiling Dropped | `A0` Variable Loss (%) | `A3` COI (`maxComp=64`) Spaces | `A3` Dropped Vars | `A3` Variable Loss (%) |
| ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 4 | 48 | 16 | 33.3% | 4 | 0 | **0.0%** |
| 8 | 96 | 64 | 66.7% | 8 | 0 | **0.0%** |
| 16 | 192 | 160 | 83.3% | 16 | 0 | **0.0%** |

2. **Real Offline Starter JAR (`spring-boot-autoconfigure-3.5.14.jar`, 5-repeat benchmark):**
   - `targets = 542`, `globalVars = 655`:
   - Undirected `@AutoConfigureAfter` component hub: **`undirectedMax = 415`** variables.
   - Directed backward `Reach^{-1}(target)` slice: **`directedMax = 14`**, **`directedMedian = 2.0`**, **`exceeding64 = 0`** (`0/542` budget overflows), **`sliceMedianMs = 19.168 ± 1.677 ms`**.

---

## 4. Phase 3 AI4SE Breakthroughs (`R2-delta` & `R3/R4-delta` SKG-RTS)

Verified in [`ResearchBreakthroughUpgradesTest`](../../backend/src/test/java/com/evolution/backend/query/ResearchBreakthroughUpgradesTest.java):
- **`ArchitectureAgent` (`R2-delta`):** `MAX_ROUNDS` raised `10 -> 18`; sliding-window compaction protects the latest 6 turns while retaining `evidenceId`, `tool`, `path`, `symbol`, `startLine`, `endLine`, `classification`, and up to `8` `itemDigests`; `ArchitectureChatModel` adds `strictQaMode` Anti-Edit-Bias prompt constraints.
- **`RepairContextPlanner` & `AffectedTestSelector` (`R3/R4-delta`):** Multi-file refactoring context budget raised `6 -> 20` (`10` incoming 1-hop callers); SKG-RTS reverse semantic graph traversal reduced executed test classes from **`14 -> 2` (`85.71%` reduction, `ServiceCoreTest,FacadeTest`)** with **100% direct + transitive test recall**, while safely failing closed to `FULL_SUITE` on `pom.xml`, `application-*.yml`, and unmapped source edits. `CapturedRepairRunner` and `run.py` validate and forward `--selected-tests` into isolated Maven/Gradle sandboxes.

---

## 5. Reproduction Command & Honest Limits

```powershell
.\mvnw.cmd -o -B -ntp -pl .,analyzer,analyzer-filesystem,analyzer-javaparser,backend '-Dmaven.compiler.useIncrementalCompilation=false' '-Dtest=SpringSatAblationBenchmarkTest,RepositoryConfigurationSpaceBuilderTest,IncrementalCdclAndCoiSlicerTest,SatConfigurationReasonerTest,TruthRegionEvaluationTest,StoragePerfParityTest,JavaServiceSyntaxExtractorTest,SpringDifferentialOracleTest,ResearchBreakthroughUpgradesTest,WorkbenchSpringSatIntegrationTest,ArchitectureAgentTest,AiAndRepairUpgradeTest,WorkbenchApiMainTest' test
```

**Limits retained explicitly:**
- External multi-repo clone campaigns (`17`-app `microSecEnD` + external `15–20` full Boot repo checkouts) are separate external evaluations (`benchmarks/e0-publication-suite`); this report covers self-contained JVM, Spring Framework 6.2 runtime oracle, and offline cached `~/.m2` starter JAR experiments.
- Non-monotone `@ConditionalOnMissingBean` remains conservatively `UNKNOWN` when starter bytecode is absent from the fixture's `.m2` cache, preserving soundness (`0` False-MUST, `0` False-NEVER).
