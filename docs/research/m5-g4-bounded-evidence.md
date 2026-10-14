# M5.3 bounded evidence and G4 review package

2026-10-14. **CONFIRMED — repository/test evidence:** implementation began at clean HEAD `a7f6e1c` (M5.2). The current uncommitted change delivers M5.3 metrics and bounded integration. **G4 remains IN PROGRESS**, not PASSED. This is an implementation self-review package, not independent adjudication, an external accuracy/performance campaign or a formal reproducibility archive.

Review [metric contract/catalog baseline](../architecture/m5-architecture-metrics.md), [graph contract](../architecture/m5-canonical-graph.md), [query contract](../architecture/m5-architecture-queries.md), and the executable [MetricCatalog](../../analyzer/src/main/java/com/evolution/analysis/metrics/MetricCatalog.java). Existing G2/G3 records are preserved and are not reused as M5 accuracy evidence.

## Commands actually run on the final implementation

From repository root, PowerShell, JDK 21.0.12.1. Only the single-module reactor-convergence rule is skipped; no other Enforcer bypass, `-am`, full reactor or target-repository lifecycle execution.

```powershell
.\mvnw.cmd test -pl analyzer "-Dtest=StructuralMetricsTest,InventoryQualityMetricsTest,ConditionalMetricContractTest,AnalysisAndAssessmentContractTest,IdentityAndSourceContractTest,SemanticContractTest,FrontendContractTest,CanonicalGraphContractTest,CanonicalGraphBuilderTest,InMemoryGraphStoreTest,ArchitectureProjectionTest,ArchitectureQueryContractTest,ConditionalPathCycleTest,CapabilityGapContractTest,SatConfigurationReasonerTest,TruthRegionEvaluationTest" "-Denforcer.skipRules=reactorModuleConvergence" -q
.\mvnw.cmd package install -pl analyzer "-DskipTests" "-Denforcer.skipRules=reactorModuleConvergence" -q
.\mvnw.cmd test -pl analyzer-filesystem "-Dtest=FileGraphStoreTest" "-Denforcer.skipRules=reactorModuleConvergence" -q
.\mvnw.cmd test -pl analyzer-javaparser "-Dtest=M5ArchitectureIntegrationTest,CanonicalGraphPipelineIntegrationTest,ArchitectureQueryPipelineIntegrationTest,UniversalArchitecturePipelineIntegrationTest,UniversalArchitectureM4EAdjudicationTest" "-Denforcer.skipRules=reactorModuleConvergence" -q
```

The unchanged filesystem production adapter was packaged/installed separately before its new test-scoped JavaParser consumer was compiled; packaging is not test evidence. Surefire XML under each module's `target/surefire-reports` was read for the explicitly selected suites, not all historical reports.

| Final bounded set | Suites | Tests | Failures/errors/skips | Timing evidence |
|---|---:|---:|---|---|
| analyzer: three metric suites + M1/frontend/graph/query/gap/SAT/truth consumers | 16 | 129 | 0 / 0 / 0 | Final Surefire suite execution sum 6.606 s |
| analyzer-filesystem: FileGraphStoreTest | 1 | 6 | 0 / 0 / 0 | Final invocation 5.027 s; suite execution 0.443 s |
| analyzer-javaparser: five named pipeline/integration suites | 5 | 30 | 0 / 0 / 0 | Final Surefire suite execution sum 36.625 s |
| Total | 22 | 165 | 0 / 0 / 0 | Bounded pre-handoff set after feedback hardening; supersedes the initial 159-test checkpoint |

Targeted TDD examples: unmapped observation initially returned COMPLETE, then WITHHELD after repair; stale self-consistent bytes initially returned COMPLETE, then WITHHELD after binding to selected document digest; module coupling initially qualified aggregate metadata, then completed after population induction; actual frontend category accounting initially collapsed 18 categories to one and now asserts all 18 separately. Final focused stale-bytes invocation took 3.587 s; some focused compiler/integration invocations exceeded 5 s, including the 8.678 s multi-module regression. The inner-loop target is not claimed universally passed.

During the initial M5.3 delivery, an IDE-generated ECJ unresolved-compilation stub was observed in the new integration test class. After correcting source errors, the selected test was explicitly recompiled with JDK 21 `javac -encoding UTF-8 -proc:none --release 21`, using the prior Surefire test classpath; standard Maven tests above then passed. The feedback-hardening run needed no such workaround. No clean/reactor claim or blanket compiler bypass follows. Tests were rerun after refreshing the changed core SNAPSHOT.

## Feedback dispositions

**CONFIRMED — false owner gap:** a valid SPRING_COMPONENT request over a context also containing production Java declarations reproduced WITHHELD for concentration due to COMPONENT_OWNER_UNAVAILABLE (focused red run 3.737 s). The type map now serves only Martin metrics; Spring population derives from definition activation. Non-Martin Java metrics still validate production evidence, avoiding a new silent-empty-population regression. The 13-case StructuralMetricsTest passed in a 3.860 s focused Maven invocation. Exact goldens for the complete two-bean, one-binding graph are density=0.5, SCC=2, feasible cycles=0, hotspots pair count=1 and concentration=1, all COMPLETE with no gaps. Negative controls preserve UNKNOWN activation and missing Java source-population gaps.

**CONFIRMED — missing direct invocations:** fan-in, fan-out and reachability had no direct tests in the initial metric delivery. Added component/context fan goldens with duplicate/internal-edge controls; incoming reachable endpoint and shortest witness distance goldens; zero-dependent and inapplicable context-subject checks; and an output-quota partial count/distance/frontier check. These characterize existing formulas; no new metric formula or version was introduced.

**Qualified finding about the original integration assertion:** the canonical builder separates Java analysis and Spring contexts, so the false-owner branch is not inevitably executed by that integration fixture. The stricter COMPLETE assertion instead exposed genuine acquisition gaps CONSTRUCTOR_SET_INCOMPLETE and CATEGORY_PARTIAL. The final test explicitly asserts those root causes, WITHHELD, absent scalar, zero returned witnesses, exact-cycle-total unavailability and preservation of original gap evidence in both explicit and symbolic encodings. It no longer accepts any result merely because some gap exists. The focused integration invocation passed in 7.422 s, exceeding the 5 s inner-loop target; the final five-suite consumer set also passed. No upstream gap was removed to force a COMPLETE result.

## Assertion-to-evidence matrix

| Obligation | Executed evidence | Result / practical boundary |
|---|---|---|
| Identity, uniqueness, idempotency | CanonicalGraphContractTest, CanonicalGraphBuilderTest, InMemoryGraphStoreTest; IdentityAndSourceContractTest, SemanticContractTest; metric ordering/codec/locale/timezone/telemetry tests | Stable complete-input replay; genuine context/manifests retained. New query identities invalidate former cursors; M1 preimages unchanged. |
| Provenance and closed denominator | CapabilityGapContractTest, FrontendContractTest, AnalysisAndAssessmentContractTest; unmapped-observation, missing-shape, malformed-file, missing/stale source-byte controls | Missing evidence produces typed gaps; observed counts retained, uncertain ratios withheld. Ground-truth accuracy remains unmeasured. |
| Persistence integrity and round-trip | FileGraphStoreTest (fresh instance, canonical payload equality, corruption/cancellation/bounds); M5ArchitectureIntegrationTest file reload and metric/query parity | No external DB. Existing adapter filesystem/hard-link requirements remain. Binary dependencies are identified by manifests, not archived here. |
| Conditional projections | ArchitectureProjectionTest, ConditionalPathCycleTest, ArchitectureQueryPipelineIntegrationTest; explicit/symbolic M5 integration and conditional metric regression | Realized/MUST/MAY/union/unknown remain distinct; union density is exploratory, not a runtime-world metric. Default pipeline registration-order gaps remain visible. |
| Infeasible path/cycle rejection | ConditionalPathCycleTest joint/pairwise counterexamples and Boolean oracle; conditional metric union-cycle control | Mutually contradictory prefixes pruned; union SCC may be cyclic while exact feasible cycle count is zero. Profile names alone do not imply exclusivity. |
| Resource bounds/pagination/cancellation | ArchitectureQueryContractTest, ConditionalPathCycleTest; metric fact/source-byte/cancellation/cursor/page-invariance and 50-witness controls | Truncation/frontier/actual execution bounds exposed. No latency/heap SLA, wall-time determinism or large-repository performance claim. |
| Metric correctness/versioning | StructuralMetricsTest (13), InventoryQualityMetricsTest (8), ConditionalMetricContractTest (9) | Hand-computed Martin/density/concentration/fan/reachability goldens, complete Spring ownership and degraded controls, kinds/populations/zero denominators, self-loop SCC, LOC, operation provenance, unknown/bounded outcomes and malformed-document metadata without crash. Catalog approval remains separate. |
| Product traceability/integration | M5ArchitectureIntegrationTest (4), CanonicalGraphPipelineIntegrationTest (7), ArchitectureQueryPipelineIntegrationTest (4); UniversalArchitecturePipelineIntegrationTest (10), UniversalArchitectureM4EAdjudicationTest (5) | Actual compact source → multi-module build model/frontend/Spring → graph → fresh file reload → summary/search/path/evidence/metrics. Useful partial sibling survives. Known-order explicit/symbolic fixture demonstrates activation; default pipeline uncertainty is preserved. These are regressions, not a new G3 campaign. |

M01–M03: StructuralMetricsTest Martin, density and production/zero-denominator cases. M04: missing evidence, truncated inputs and malformed-sibling tests. M05: inventory role/metadata golden plus actual nested/local/anonymous source. M06: LOC terminator/BOM/empty/final-line, corruption/staleness/byte-budget controls. M07: conditional metric and explicit/symbolic activation tests. M08: cycle quota, self-loop/SCC and ranking cases. M09: canonical ordering, codec, locale/timezone, result-affecting limits and telemetry identity. M10–M11: multi-module/source/store/Spring integration and partial counterpart. M12: full-scope computation unaffected by UI pages. Duplicate support changes graph provenance identity; coupling values remain invariant. It is not claimed that a changed graph digest leaves the entire metric identity unchanged.

## Concrete review decisions and handoff

Files changed: six new production classes in `analyzer/.../metrics` (`ArchitectureMetric`, `MetricCatalog`, `MetricEvidence`, `StructuralMetrics`, `InventoryMetrics`, `ArchitectureMetricsService`); additive query DTO/service and version assertion; three new metric test suites. Consumer changes: FileGraphStoreTest parity, JavaParser test-scoped filesystem dependency and M5ArchitectureIntegrationTest. Documentation: current-state, knowledge-graph, graph-query/metric contracts, M5 README/task M5.3, and this assertion matrix.

Routine reversible decisions: additive M5 result wrapper; 19 versioned metric definitions; scale-12 HALF_EVEN with exact operands; production population; separate full-scope projection and bounded details; test-only filesystem dependency. No M1 or graph snapshot migration. No commit/push, corpus run, raw-evidence edits or new reproducibility directory.

**Human approval still required by ADR-002:** catalog baseline and registered product query/UI performance budgets. Review the definitions and population/status choices in the linked contract and MetricCatalog. These defensive limits have not been validated as product budgets. Scores, thresholds and policy semantics remain M6 work.

**Limitations:** no independent review was authorized/performed; no formal G4 reproducibility package, external corpus benchmark or full reactor was authorized/run. Operational timing requires supplied verified observations; endpoint coverage absent upstream remains a gap. Partial/unsupported Spring mechanisms retain their upstream gaps; this slice does not emulate missing runtime behavior. No implementation blocker remains in the bounded delivery.

Durable state is updated in current-state, graph/query/metric contracts and the M5 task plan. **Exact next task:** human adjudication of this catalog/G4 package, registration and authorization of any remaining formal G4 evidence campaign; freeze the approved catalog/budgets only after that review. Start M6 only after G4 passes and the appropriate authorization exists.
