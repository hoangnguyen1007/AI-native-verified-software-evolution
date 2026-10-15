# M7.1 — Query REST/HTTP API and canonical JSON

Entry: clean Git working tree at `1f423b0`; G3/G4/G5 recorded PASSED. The final next-task section and G5 adjudication authorize M7.1 despite stale M6-open language at the top of current state. No pre-existing edits were overwritten. Selected route: lead/graph architect for the reversible transport contract, implementer/TDD, then implementer self-verification. No independent agent review was requested or performed.

Contract: [M7.1 HTTP/JSON](../../architecture/m7-query-http-api.md). External wire v1 remains PROVISIONAL. JDK HTTP is a replaceable local adapter; Spring Boot choice, M1/graph/query identities and all formulas are preserved. There is no new production dependency, arbitrary target execution, network acquisition, full reactor, external benchmark, reproducibility package, gate promotion, commit or push.

| Exit criterion | Evidence |
|---|---|
| Stable service boundary, all 22 public presentation operations | `ArchitectureQueryApiTest.everyPublicQueryDelegatesWithoutChangingCanonicalValues` |
| Canonical JSON, exact IDs, defaults/types/caps | `QueryJsonTest`, `BoundedJsonTest` |
| Pagination/filter binding and file-codec parity | `ArchitectureQueryApiTest.paginationKeepsTotalsAndBindsCursorToFiltersAcrossSnapshotReload` |
| No infeasible conditional path or invented runtime impact | explicit-world HTTP test; core conditional/path/cycle contracts |
| Gaps, cancellation, limits, redaction and sanitized errors | API unit/contract tests and real HTTP tests |
| M6 finding/bundle/metrics/assessment parity; clean/unknown denominators | `PublishedReportsTest` using real compiler/mapper/evaluator/metrics/assessment |
| Local HTTP lifecycle and stalled client bounds | `ArchitectureHttpServerTest` |
| Consumable specification matches implementation | packaged OpenAPI; `ApiSpecificationTest` operation/reference/budget checks |

TDD: canonical request round-trip initially failed at the explicit unimplemented decoder (one error; command 6.499 s); implementation passed (4.796 s). The real HTTP Accept regression failed with expected 406 versus actual 200 (5.361 s), identifying wildcard precedence; the repaired HTTP class passed (4.746 s). Inner-loop commands exceeding five seconds are recorded as overruns, not successful timing-budget claims. New tests otherwise include post-implementation contract coverage; no claim that every test was observed red.

## Bounded handoff verification

**CONFIRMED final result: 103 tests / 17 suites, zero failures/errors/skips.** Backend contains 41 new controls plus the existing build-boundary test. Only the partial-reactor `reactorModuleConvergence` rule is skipped for single-module commands; all other enforcement remains enabled. Standard Surefire reports supply these counts; no custom result/hash package was generated.

```powershell
.\mvnw.cmd test -pl analyzer "-Dtest=FrontendContractTest,ArchitectureQueryContractTest,ArchitectureProjectionTest,ConditionalPathCycleTest,InventoryQualityMetricsTest,PolicyConditionalEvaluationTest,AssessmentMetricsIntegrationTest" "-Denforcer.skipRules=reactorModuleConvergence" -q
.\mvnw.cmd jar:jar install:install -pl analyzer "-Denforcer.skipRules=reactorModuleConvergence" -q
.\mvnw.cmd package -pl backend "-Denforcer.skipRules=reactorModuleConvergence" -q
.\mvnw.cmd test -pl analyzer-filesystem "-Dtest=FileGraphStoreTest" "-Denforcer.skipRules=reactorModuleConvergence" -q
.\mvnw.cmd test -pl analyzer-javaparser "-Dtest=M6PolicyIntegrationTest" "-Denforcer.skipRules=reactorModuleConvergence" -q
```

| Check | Tests / suites | Result and timing |
|---|---:|---|
| Analyzer selected contracts | 53 / 7 | Exit 0; command 8.015 s |
| Backend final package including all tests | 42 / 8 | Exit 0; final command 7.293 s |
| Filesystem persistence consumer | 6 / 1 | Exit 0; command 5.526 s, suite 0.482 s |
| Actual-source/Spring policy/assessment consumer | 2 / 1 | Exit 0; suite 5.510 s; full asynchronous command duration was not captured |

The artifact refresh exited 0 in 3.266 s and ran no tests. The final backend package follows the last page-null/schema-alignment repair; unchanged analyzer/filesystem/JavaParser evidence remains applicable. Verification ran on Maven 3.9.16 / Oracle JDK 21.0.12.1 / Windows amd64. Bounded handoff commands are outside the five-second inner-loop target. No clean-build claim follows.

Self-review additionally hardened Accept precedence (specific JSON exclusion beats wildcard), input media/duplicate fields, report provenance, serialization allocation/UTF-8 expansion, all eight canonical hard ceilings, stalled-body deadlines, launcher nonzero exits and OpenAPI references/operation coverage. `git diff --check` and local documentation-link checks pass. All resulting files remain unstaged/uncommitted; no commit or push was performed.

Remaining limits: local trusted embedding/loopback only; no authenticated deployment, asynchronous jobs, CLI/export/UI or corpus latency claim. The API returns emitted M6 calibration/status unchanged, including the existing PROVISIONAL code marker despite approved baseline parameters. Graph-only startup has no precomputed M6 report unless explicitly supplied by an embedding caller. These do not authorize silently recomputing a score or inventing an absent report.

Exact next slice: **M7.2 — Architecture Intelligence CLI tooling**, consuming stable query services and the M7.1 canonical JSON contract, with M7's bounded impact/evidence/export obligations. M8's asynchronous backend and complete workbench remain later work.
