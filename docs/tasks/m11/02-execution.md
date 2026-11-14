# M11.2 — Authenticated snapshot alignment and structural delta

2026-11-14. [M11.1](01-execution.md) supplied the approved scope and provisional contract. The workspace was dirty at entry on `main` / `c5517c30e22f9e192f181c0135207828188d7259`; existing graph/storage/Workbench changes were preserved. No staging, commit or push.

## Implemented boundary

- `analyzer`: `SnapshotComparison`, authenticated snapshot and ordered scratch ports, context/source/coverage reconciliation, scoped Java correspondence, structural and semantic relationship deltas, support/location/multiplicity changes, typed residuals, deterministic event identities and paged results.
- `analyzer-filesystem`: authenticate the existing partition root and read direct context/node/edge/evidence pages; compare through a private SQLite session. Scratch keys are ordered independently of traversal or transport pages. The session closes on cancellation or failure and exposes a result only after the final digest is computed. A caller cannot reuse a scratch index owned by another attempt.
- `backend`: reuse published report content storage and verification to authenticate optional graph/context-bound report inputs. The adapter does not calculate a policy delta, and no HTTP, CLI or Workbench surface was added.
- Source edits, tool/classpath drift and lost evidence are distinct. Missing or corrupt operands do not become absence. Exact M1/M5/M6 IDs and existing graph/report formats remain unchanged. The [architecture contract](../../architecture/m11-compatible-snapshot-evolution.md#6-implemented-m112-boundary) defines the boundary.

## Verification

The first test-first invocation reported missing comparison API types; that run also exposed and corrected a fixture constructor mismatch. The implemented source-edit control subsequently passed in 3.941 s wall time. The final selected reactor closure is `backend -am`, excluding CLI and restricting execution to named suites. It exercises the actual cold partition store, content tampering, missing publication, cancellation and typed-reader parity; analyzer controls exercise both comparison directions, self/revision-only, ambiguous and unselected scope, pending module, tool/classpath drift, absent report, payload/support separation and bad edge provenance. Existing graph, query, report and provider checks run in the same selected closure.

```powershell
.\mvnw.cmd -B -ntp -q -pl backend -am '-Dtest=EvolutionCompatibilityTest,FileSnapshotComparisonTest,PublishedReportsTest,CanonicalGraphContractTest,CanonicalGraphBuilderTest,PartitionedGraphStoreTest,FileGraphStoreTest,UniversalMavenIngestionTest,CanonicalGraphPipelineIntegrationTest,TreeSitterSourceFrontendTest,AnalysisJobApiTest,ArchitectureQueryApiTest,OutputCensusTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
```

**CONFIRMED / PASS:** exit `0` in **31.257 s**, 117 tests in 13 suites across the seven modules in the selected closure, 0 failures/errors/skips. The check covers presence/absence and direction, two authenticated input artifacts, scope/page invariance, no inferred removal when a raw relation survives but its projected edge is missing, and report binding and tampering. After adding the module and overload isolation control, the analyzer-only `EvolutionCompatibilityTest` rerun passed all 13 tests in **4.296 s**. No production code changed between these two checks. These are authored contract and integration checks. No target build/indexer, external historical-pair evaluation, large-repository comparison benchmark, or independent review was run.

## Remaining work

Strict comparison requires a supported, pinned Java profile and positive evidence that the selected operand population was processed. Legacy placeholder analyzer/rule digests, unresolved generated lineage, unqualified polyglot/bridge data, or absent readiness census remain visible as bounded or unknown. Non-Java symbol and bridge lifecycle events are not established here. The private disk session supports canonical paging after a completed comparison; durable restart/resume and product publication are M11.5. Historical paired analysis and independent review remain G7 work. No new public schema or release claim is made.

**Exact next task:** M11.3, prove common-domain correspondence and add a finite-world oracle for `F→T`, `T→F`, `T→T` and unknown residuals, then integrate the existing symbolic reasoner and side-specific binding/witness/evidence replay. Keep the structural absence guards and original gap provenance intact.
