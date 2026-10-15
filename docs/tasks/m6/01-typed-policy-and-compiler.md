# M6.1 — Typed architecture policy, rule compiler and AST mapping

Entry: SE121 Track A, G4 human-adjudicated PASSED, M5 complete, clean Git at
`a7db34d`. This task implements the exact next slice recorded in current state.
Contract: [M6 typed policy](../../architecture/m6-typed-policy.md), existing M4
conditional semantics, M5 graph/query ownership and evidence-first safeguards.

## Delivered

- Immutable typed forbidden-dependency, directional boundary, layer and acyclic AST.
- Strict external UTF-8/YAML validation, JSON-tree schema, executable example,
  raw/semantic identity separation, coordinates and typed compilation failures.
- Context-qualified graph mapping, canonical module/package ownership, explicit
  empty/unknown/overlapping selectors and closed per-rule resource accounting.
- Additive query predicate composition with true/counter-witnesses and conservative
  truth-region classification, preserving source evidence and uncertainty.
- Source-to-policy, mutation, explicit/symbolic Spring and file persistence controls.

No finding/score engine, M7 work, target lifecycle, arbitrary policy execution,
new dependency or gate promotion is part of this slice. External format remains
PROVISIONAL pending contract acceptance at G5; no scoring weights were invented.

## Verification record

Toolchain: Maven Wrapper 3.9.16, Oracle JDK 21.0.12.1. Commands run at repository
root in PowerShell; only `reactorModuleConvergence` is skipped for standalone
module verification. All other Enforcer rules remain enabled.

Initial compiler control failed at COMPLETE versus WITHHELD, then passed. The
initial conjunction control failed at NEVER versus UNKNOWN, then passed. Exact
identity-selector support failed before implementation and passed afterward.
Default-package support initially raised a constructor error, then passed. A
regression reproduced aggregate source-set membership incorrectly returning YES;
it now returns UNKNOWN. One authored test import was ambiguous and corrected before
behavioral execution. Mapper's initial broad controls were added after its first
implementation; no red phase is claimed for those controls.
The final API guard also failed its control before being added: an UNKNOWN selector
candidate can no longer expose an unqualified executable edge predicate.

Inner loops used one method or small class. Maven recompilation/startup caused
multiple commands to exceed the five-second target (initial calls yielded after
10 seconds); Surefire method time alone is not total command time. No sub-five-second
command claim is made. No compiler-stub workaround was required in this task.

Core packaging/install with `-DskipTests` refreshes the adapter's
trusted local dependency and is not test evidence. JSON Schema validation uses
the local `jsonschema.Draft202012Validator` against the shipped YAML example and
four invalid kind/scope/severity/version controls; all passed without network I/O.

Bounded regression commands actually run:

```powershell
.\mvnw.cmd test -pl analyzer "-Dtest=PolicyCompilerTest,PolicySelectorTest,PolicyGraphMapperTest,ArchitectureConditionTest,ArchitectureProjectionTest,ConditionalPathCycleTest,ArchitectureQueryContractTest,CanonicalGraphContractTest,CanonicalGraphBuilderTest,InMemoryGraphStoreTest,StructuralMetricsTest,InventoryQualityMetricsTest,ConditionalMetricContractTest,CanonicalSerializationTest,FrontendContractTest,TruthRegionEvaluationTest,SatConfigurationReasonerTest" "-Denforcer.skipRules=reactorModuleConvergence" -q
.\mvnw.cmd test -pl analyzer-javaparser "-Dtest=M6PolicyIntegrationTest,ArchitectureQueryPipelineIntegrationTest,M5ArchitectureIntegrationTest,CanonicalGraphPipelineIntegrationTest,UniversalArchitecturePipelineIntegrationTest" "-Denforcer.skipRules=reactorModuleConvergence" -q
.\mvnw.cmd test -pl analyzer-filesystem "-Dtest=FileGraphStoreTest" "-Denforcer.skipRules=reactorModuleConvergence" -q
```

The broad core command passed 142 cases; the final extra incomplete/incompatible
universe control raised the selected core set to **143 cases / 17 suites**. After
the final candidate API guard, the four new core suites were rerun (**43 cases**)
and the direct M6 adapter integration was rerun (**2 cases**). Unchanged selected
regressions retain their passing evidence. The adapter bundle passed **27 cases /
5 suites**, filesystem **6 / 1**. Final relevant total: **176 cases / 23 suites**,
zero failures/errors/skips. New M6 coverage is **45 cases / 5 suites**. This is a
bounded selected set, not a full-module/reactor result.

Final affected-state commands:

```powershell
.\mvnw.cmd test -pl analyzer "-Dtest=PolicyCompilerTest,PolicySelectorTest,PolicyGraphMapperTest,ArchitectureConditionTest" "-Denforcer.skipRules=reactorModuleConvergence" -q
.\mvnw.cmd install -pl analyzer "-DskipTests" "-Denforcer.skipRules=reactorModuleConvergence" -q
.\mvnw.cmd test -pl analyzer-javaparser "-Dtest=M6PolicyIntegrationTest" "-Denforcer.skipRules=reactorModuleConvergence" -q
```

All three exited zero: measured wall times **12.791 s / 11.392 s / 15.271 s**.
The earlier broad adapter bundle accumulated **66.64 s** of Surefire suite time;
filesystem **1.031 s**, excluding Maven startup. One yielded core session's exit
could not be retrieved, so its affected tests were explicitly rerun with a captured
zero exit. No success claim depends on the missing session result.

`git diff --check` and **105 local Markdown links** passed. The JSON-tree schema
was checked with `Draft202012Validator.check_schema`, its shipped example validated,
and all four mutated controls rejected. Runtime assertions verify SnakeYAML **2.6**
from its packaged Maven metadata, plus the policy/compiler contract versions.

## Completion and continuation

Exit criteria are typed compilation for all four kinds, closed invalid-input
handling, truthful selector/query mapping, safe conditional composition,
deterministic identity/replay and source-evidence continuity. This slice is
implementation self-review only; no independent review, full reactor, external
benchmark, reproducibility package, commit or push is claimed.

Exact next slice: **M6.2 — configuration-qualified policy evaluation and Evidence
Bundles**. Consume the compiled plans; preserve unknown eligibility and incomplete
query denominators; evaluate direct boundaries and induced-scope bounded cycles;
aggregate violating regions with sound OR/AND semantics; retain clean NEVER
results separately from findings; identify rules/versions, graph paths, full source
evidence, witnesses/counter-witnesses and gaps. Score implementation and G5 remain
subsequent M6 obligations.
