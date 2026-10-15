# M6.2 — Configuration-qualified policy evaluation and Evidence Bundles

Entry: SE121 Track A, M6 active, G3/G4 PASSED, M6.1 committed at `87572e5`,
workspace clean. Scope is the exact next slice in current-state and the M6.1 task
record. Contract: [M6.2 policy evaluation](../../architecture/m6-policy-evaluation.md).

## Delivered

- All four compiled rule kinds evaluated through stable conditional graph queries.
- Bounded cycles restricted to the selected induced population; unknown cycles
  retained and jointly infeasible cycles rejected.
- Route-level conjunction, whole-rule disjunction, clean NEVER results, explicit
  UNKNOWN/withheld denominators and severity independent of quantification.
- Content-addressed findings and Evidence Bundles with source hashes/full spans,
  rule/version, raw and logical graph supports, region and provenance, replayable
  predicates, witnesses/counter-witnesses and original upstream gap pointers.
- Global candidate/evidence/input/character/visit limits and typed failure records.
- Passive Java/Spring integration, source mutation, explicit/symbolic and graph
  file/codec round-trip controls.

Routine reversible choices: additive evaluator/query/evidence contracts and a
logical finding key distinct from its complete content identity. No new external
format, dependency, score model, target execution or architecture gate decision.

## Verification record

Toolchain verified with `.\mvnw.cmd --version`: Maven Wrapper 3.9.16 and Oracle
JDK 21.0.12.1. Commands run in PowerShell at repository root; only the standalone
module `reactorModuleConvergence` Enforcer rule is skipped.

TDD observed two behavioral failures: empty closed structural rule expected NEVER
but returned UNKNOWN, and the evaluator expected a retained rule row but returned
none. Both passed after implementation. An ambiguous Java wildcard import was a
compilation mistake, corrected before behavioral execution; it is not red-phase
evidence. Initial Maven compilation yielded after ten seconds and exceeded the
five-second inner-loop target; no sub-five-second command claim is made.

Initial actual-pipeline controls exposed a wrong test expectation (the source
negative has upstream gaps and therefore must be UNKNOWN) and missing indirect
Spring source provenance. Inspection traced constructor evidence to exact Java
declaration references. The collector now follows those references only within the
same analysis, without guessed source positions or invented facts. The corrected
two-case integration passed before final regression verification. Source corruption,
budget closure, complement witnesses and a separately enumerated two-variable
Boolean oracle provide additional controls.

Final self-review also added two encoding controls. When both explicit and symbolic
representations exist, the rule universe follows the existing preferred-encoding
policy. A contradictory-symbolic-universe regression failed as intended (UNKNOWN
expected, MUST observed; **5.396 s** command), then passed after bounded SAT
equivalence checks prevented silent universe narrowing. This does not change the
original M5 condition-composition contract. The six affected core suites were
rerun after the final repair (**12.994 s** command).

Commands actually run (PowerShell; all final affected-state commands exited zero):

```powershell
.\mvnw.cmd test -pl analyzer "-Dtest=PolicyCompilerTest,PolicySelectorTest,PolicyGraphMapperTest,PolicyEvaluatorTest,PolicyConditionalEvaluationTest,ArchitectureConditionTest,ArchitectureProjectionTest,ConditionalPathCycleTest,ArchitectureQueryContractTest,CanonicalGraphContractTest,CanonicalGraphBuilderTest,InMemoryGraphStoreTest,StructuralMetricsTest,InventoryQualityMetricsTest,ConditionalMetricContractTest,CanonicalSerializationTest,FrontendContractTest,TruthRegionEvaluationTest,SatConfigurationReasonerTest" "-Denforcer.skipRules=reactorModuleConvergence" -q
.\mvnw.cmd test -pl analyzer "-Dtest=PolicyEvaluatorTest" "-Denforcer.skipRules=reactorModuleConvergence" -q
.\mvnw.cmd test -pl analyzer "-Dtest=PolicyEvaluatorTest,PolicyConditionalEvaluationTest,ArchitectureConditionTest,ArchitectureProjectionTest,ConditionalPathCycleTest,ConditionalMetricContractTest" "-Denforcer.skipRules=reactorModuleConvergence" -q
.\mvnw.cmd install -pl analyzer "-DskipTests" "-Denforcer.skipRules=reactorModuleConvergence" -q
.\mvnw.cmd test -pl analyzer-javaparser "-Dtest=M6PolicyIntegrationTest,ArchitectureQueryPipelineIntegrationTest,M5ArchitectureIntegrationTest,CanonicalGraphPipelineIntegrationTest,UniversalArchitecturePipelineIntegrationTest" "-Denforcer.skipRules=reactorModuleConvergence" -q
.\mvnw.cmd test -pl analyzer-javaparser "-Dtest=M6PolicyIntegrationTest,ArchitectureQueryPipelineIntegrationTest,M5ArchitectureIntegrationTest" "-Denforcer.skipRules=reactorModuleConvergence" -q
.\mvnw.cmd test -pl analyzer-javaparser "-Dtest=M6PolicyIntegrationTest" "-Denforcer.skipRules=reactorModuleConvergence" -q
.\mvnw.cmd test -pl analyzer-filesystem "-Dtest=FileGraphStoreTest" "-Denforcer.skipRules=reactorModuleConvergence" -q
```

The first core bundle passed **162 cases / 19 suites**. Four additional evaluator
controls and two additional region controls raised the final selected set to
**168 / 19**; the six affected suites were rerun on the final implementation
(**62 cases**). Unchanged suites retain their earlier passing checks. The adapter
bundle passed **27 / 5**; three direct consumer suites were rerun (**28.488 s**
command), followed by the two-case M6 integration after the final universe repair
(**9.266 s** command).
Filesystem passed **6 / 1**. Final relevant total: **201 tests / 25 suites**, zero
failures/errors/skips. M6.2 adds **25 core cases** and strengthens two existing
source/Spring integration cases; the Boolean oracle internally checks 16 formula
pairs and all four assignments for both cycles and whole-rule unions.

Installation with skipped tests only refreshes the trusted analyzer dependency;
it is not test evidence. Provider/version and M1 serialization controls are included
in the selected suites. All checks used standard Maven execution; no IDE stub
workaround was needed. `git diff --check` and **110 local Markdown links** pass.
Suite runtimes exclude Maven startup and are not latency guarantees.

## Completion and continuation

Exit criteria: all four rule kinds, configuration-qualified findings and aggregate
verdicts, induced bounded cycles, clean/unknown/withheld denominator closure,
deterministic identities, exact source/provenance retention and replay/persistence
integration. Self-review only; no independent review, full reactor, real-repository
benchmark, formal reproducibility package, commit or push.

Exact next slice: **M6.3 — policy/violation metrics and explainable architecture
assessment**. Consume M6.2 outcomes; define versioned conditional contributions,
missing-evidence safeguards, dimension breakdowns, raw operands/penalties/caps and
golden/sensitivity checks. Labeled weights/thresholds and G5 acceptance remain
subject to the existing human-approval contract; neither is silently promoted by
M6.2.
