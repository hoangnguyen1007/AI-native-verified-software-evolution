# M7.3 — Visual Workbench Foundation, Policy-Qualified Impact, and Local Workflow Acceptance

Date: 2026-10-16. **CONFIRMED implementation and verified test execution.** Milestone M7 is complete; Gate G6 Track A Release Preparation is ready for human adjudication.

---

## State and Completed Scope

Started from M7.2 CLI implementation commit. The objective of M7.3 was to deliver the visual workbench foundation, policy-qualified structural impact analysis, multi-format interoperability exports, and complete offline local workflow acceptance.

### 1. Canonical Visual Projection & Policy Impact Domain Services (`analyzer`)
- **`com.evolution.analysis.policy.PolicyImpactRecord`**: Canonical immutable records for policy-qualified impact (`Result`, `FindingImpact`, `RuleImpactSummary`), versioned as `policy.impact:m7.3-v1` with ContentDigest SHA-256 identities.
- **`com.evolution.analysis.policy.PolicyImpactService`**: Implements direct vs. transitive impact classification by intersecting incoming reachability paths with violated rule Evidence Bundles, tracking worst severities and quantifiers (`MUST`, `MAY`, `UNKNOWN`). Verified with `PolicyImpactServiceTest`.
- **`com.evolution.analysis.query.projection.VisualGraph`**: Canonical visual graph projection representation (`VisualNode`, `VisualEdge`, metadata) including compound containment (`parent`), cycle participation (`isCycleParticipant`), and policy violation badges (`violationCount`, `worstSeverity`). Supports built-in `toCytoscape()` and `toGraphML()` adapters.
- **`com.evolution.analysis.query.projection.GraphProjectionService`**: Implements hierarchical compound projection (Module $\rightarrow$ Package $\rightarrow$ Type), focused subgraphs (ego-networks around a seed symbol within bounded depth), and violation-focused subgraphs. Verified with `GraphProjectionServiceTest`.

### 2. Transport & API Registration (`backend`)
- **`openapi.yaml`**: Registered `"visual-graph"` in `OPERATIONS`, and `"policy-impact"` and `"visual-graph"` in `REPORT_OPERATIONS`.
- **`ArchitectureQueryApi`**:
  - `/queries/visual-graph`: Supports structural hierarchy projections and focused subgraphs with depth limits.
  - `/reports/{reportId}/policy-impact`: Dispatches to `PolicyImpactService` evaluating against the specified published report.
  - `/reports/{reportId}/visual-graph`: Dispatches to `GraphProjectionService` enriched with policy violation badges and optional `violationsOnly` filtering.
  - Verified with `ArchitectureQueryApiTest`, `PublishedReportsTest`, and `ApiSpecificationTest`.

### 3. CLI Integration & Terminal Reporting (`cli`)
- **`Exports.java`**: Added `Exports.cytoscape(tree)` to produce standard Cytoscape.js format with compound elements and metadata.
- **`TerminalReports.java`**: Added `policyImpact(Map<String,Object>)` and `visualGraph(Map<String,Object>)` terminal renderers with bilingual English/Vietnamese output, badge metrics, and export hints.
- **`EvoApp.java`**:
  - Registered `visual` subcommand mapping to `visual-graph`.
  - Added support for `evo impact <symbol> --policy <file>`, routing automatically to policy-qualified impact.
  - Added `cytoscape` parameter to `evo export`, enabling `evo export cytoscape -o <file>`.
  - Updated entity resolution to support visual graph seed symbols.
- **`LocalWorkflowAcceptanceTest.java`**: Comprehensive end-to-end acceptance suite executing passive scan, show, find, incoming deps, rule checks, findings, policy impact, visual projection, focused seed visual queries, and all export formats (Cytoscape, GraphML, SARIF, JSON) without network or database servers.

---

## Executed Verification

All tests were executed in quiet mode with `-Dplatform.build.directory=target/evo-build` and `-Denforcer.skipRules=reactorModuleConvergence`.

| Module | Test Suite | Tests Run | Result | Duration |
|---|---|---:|---|---:|
| `analyzer` | `PolicyImpactServiceTest` | 4 | PASS | < 1s |
| `analyzer` | `GraphProjectionServiceTest` | 5 | PASS | < 1s |
| `backend` | `ArchitectureQueryApiTest` | 8 | PASS | < 1s |
| `backend` | `PublishedReportsTest` | 3 | PASS | < 1s |
| `backend` | `ApiSpecificationTest` | 2 | PASS | < 1s |
| `cli` | `EvoAppTest` | 17 | PASS | < 4s |
| `cli` | `TerminalReportsTest` | 8 | PASS | < 1s |
| `cli` | `LocalWorkflowAcceptanceTest` | 2 | PASS | ~19s |
| **Total** | **8 test suites** | **49 tests** | **0 failures, 0 errors** | **ALL GREEN** |

Exact Maven verification command:
```powershell
.\mvnw.cmd test -pl analyzer,backend,cli "-Dtest=PolicyImpactServiceTest,GraphProjectionServiceTest,ArchitectureQueryApiTest,PublishedReportsTest,EvoAppTest,TerminalReportsTest,LocalWorkflowAcceptanceTest" "-Dsurefire.failIfNoSpecifiedTests=false" "-Dplatform.build.directory=target/evo-build" "-Denforcer.skipRules=reactorModuleConvergence" -q
```

---

## Invariants Preserved
- **Execution Safety & Sandbox:** Zero execution of target build lifecycles, scripts, or plugins. Target repositories remain passive data.
- **Deterministic Replay & Zero Hallucination:** All identities are content-addressed SHA-256 (`ContentDigest`).
- **Closed Reporting Denominator:** Explicit `CapabilityGapRecord` for any unsupported or incomplete operations.
- **Zero Heavyweight Artifact Dumps:** No ceremonial JSON dumps or synthetic benchmark packages created.
