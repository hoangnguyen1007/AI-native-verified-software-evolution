# M11.4 — Finding lifecycle, bounded history and assessment delta

2026-11-14. M10 had authorized Track B. Main was at `4b52040` with M11.3 already present as working-tree changes. G7 remained undecided.

**CONFIRMED / internal implementation and bounded verification.** The [analyzer evolution package](../../../analyzer/src/main/java/com/evolution/analysis/evolution) compares authenticated M6 finding, history, metric and score operands. [Architecture query replay](../../../analyzer/src/main/java/com/evolution/analysis/query/ArchitectureQueryService.java) supplies the original rule truth operands. [FileSnapshotComparison](../../../analyzer-filesystem/src/main/java/com/evolution/analysis/filesystem/FileSnapshotComparison.java) uses a request-private external index. [PublishedReports](../../../backend/src/main/java/com/evolution/backend/query/PublishedReports.java) authenticates the existing M6 report before handing its contents to the analyzer.

| Registered control | Verified outcome |
|---|---|
| E11 regional and policy | One Spring finding has persisted, introduced and resolved subregions on three independently specified property assignments. Explicit, mixed explicit/symbolic and partition archive replays agree on the applicable assertions. Missing publication, changed rule, a truncated predicate and a missing derived edge with intact raw evidence cannot prove resolution. A severity-only change with exact compilation preserves logical finding correspondence. |
| E12 return and gap | Authenticated linear history proves reintroduction through a clean middle report. The explicit-sequence control also reintroduces a Spring finding on the intersected symbolic subregion. Missing middle evidence and a merge-parent interval cannot establish reintroduction. Earliest observed is scoped to the declared history. |
| E13 exact arithmetic and drift | Density moves from 0/2 to 1/2. The exact delta is 1/2 and the relative percentage is absent. A changed definition retains the original operands and withholds the delta. |
| E14 score and model | The M6 golden is 67.5 from density 1/2 and concentration 1/1, with PROVISIONAL calibration. A duplicate observation gives a zero score delta. Inapplicable or missing required evidence and changed metric or model identity withhold the health delta. |

Verification used JDK 21.0.12.1 and Maven 3.9.16. The selected command exited 0 in 37.547 s. Surefire recorded 188 tests in 19 suites, with zero failures, errors or skips.

```powershell
.\mvnw.cmd -B -ntp -q -pl backend -am '-Dtest=FindingEvolutionTest,EvolutionCompatibilityTest,ConditionalRegionDeltaTest,PolicyEvaluatorTest,PolicyGraphMapperTest,PolicyMetricsTest,ArchitectureAssessmentTest,AssessmentMetricsIntegrationTest,StructuralMetricsTest,ArchitectureQueryContractTest,PolicyConditionalEvaluationTest,ArchitectureConditionTest,FileSnapshotComparisonTest,UniversalMavenIngestionTest,OutputCensusTest,CanonicalGraphPipelineIntegrationTest,PublishedReportsTest,ArchitectureQueryApiTest,TreeSitterSourceFrontendTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
```

The inner loop initially needed the parent reactor to satisfy Maven Enforcer and reported compilation failure for the absent M11.4 interfaces. The regional test then exposed an open aggregate Spring rule with a definite local route. The final implementation preserves the aggregate qualification while emitting only subregions supported by authenticated route truth. Filesystem parity checked equal summary and finding rows at different input page sizes. The persistent report control checked the pinned body and rejected altered CAS bytes.

Internal limits are versioned with the comparison engine. Report replay materializes only the selected partition and applicable ancestor evidence under a working-set budget. Published legacy reports use the exact policy digest when the policy namespace or mapping is unavailable. Cross-capture symbolic numeric populations require a stronger equivalence proof before a health delta. History consumes authenticated interval proofs through its reader port and uses authored proofs in the controls. Natural VCS ancestry and the public history ingestion path remain for product integration and evaluation. No target build, native campaign or independent gate review was performed.

No further human decision was needed for this internal slice. [Current state](../../current-state.md) and the [M11 contract](../../architecture/m11-compatible-snapshot-evolution.md#8-implemented-m114-boundary) record the boundary. **Exact next task is M11.5.** Implement durable publication and restart verification for immutable comparison roots, followed by stable product consumers. G7 has no verdict.
