# SE121 Technical Roadmap

## Status and Intent

This is the canonical future-direction document. Operational status belongs in `docs/current-state.md`.

The approved SE121 North Star is **Track A + Track B**:

- **Track A:** complete correctness foundation and usable architecture-intelligence product.
- **Track B:** architecture evolution across compatible snapshots after Track A gates.
- **Track C:** moonshot research; never required to make Track A + B credible.

The roadmap is tech-first. Ground truth, benchmarks, evidence, and reproducibility remain mandatory engineering verification. Publication and extensive defense packaging are later/optional activities.

On 2026-09-09, [ADR-004](decisions/ADR-004-staged-conditional-architecture-semantics.md) accepted bounded phase/order-aware conditional architecture semantics as the M4+ direction. The decision strengthens Track A+B but does not change the active M3.8 task or advance G2/G3.

Tracks and milestones define delivery order and acceptance claims, not permanent capability ceilings. Architecture work must preserve safe extension paths for deeper repository evidence even when the corresponding provider is not an SE121 deliverable.

## Protected Principles

1. Do not weaken Track A to reach Track B.
2. Do not begin Track B until snapshot identity, semantic correctness, graph invariants, and policy evidence pass.
3. Coverage and semantic correctness are separate metrics.
4. Unknown/ambiguous/conditional semantics remain explicit.
5. Canonical semantic and query contracts remain independent of parser and storage adapters.
6. Multi-module workspace/build-model intelligence is a core capability.
7. Analysis identity is content-addressed, but caching is deferred until a measured need.
8. Source-only analysis is a baseline, not a ceiling. Unresolved/unsupported cases are registered by reason and may trigger progressively stronger evidence providers; provider implementation remains gate- and evidence-driven.
9. Evaluation runs throughout implementation rather than at the end.
10. No SE122/KLTN production features enter SE121 silently.
11. Architecture health, analysis confidence, and semantic coverage remain separate outputs.
12. Metrics and scores are versioned, deterministic, explainable, and withheld when required evidence is insufficient.
13. Track A must deliver the required visual product workflow; an analyzer or CLI alone is not product completion.
14. Repository facts and inputs are never silently omitted because a current provider cannot interpret them; acquisition attempts, gaps, exclusions and reasons remain visible.
15. One realized Spring configuration is not universal architecture truth. M4+ must preserve configuration-space identity, phase/order semantics, truth regions, witnesses and `UNKNOWN` outcomes.
16. Graph, policy, metrics and UI consume conditional facts through stable contracts; they must not flatten mutually infeasible facts into one executable world.

## Capability Growth and Progressive Evidence

Evidence may deepen from immutable source and declarative build/configuration metadata to resolved dependencies and generated sources, bytecode, framework metadata, explicitly authorized controlled build/sandbox outputs, and runtime observations. This is an extension ladder, not a mandatory universal sequence: use the least invasive provider that can answer a registered question, then stop when evidence is sufficient or the next step is unsafe, unauthorized, unavailable, or not justified.

Every provider must declare its inputs, trust boundary, versions, configuration, permissions, resource limits, outputs, failures and provenance. Later evidence may corroborate, qualify or contradict earlier evidence; it must not silently rewrite history or turn an inference into a direct fact. When escalation is not available, the platform retains the unresolved capability gap and its effect on coverage, confidence and assessment.

See [ADR-003: Progressive Evidence Acquisition and Capability Boundaries](decisions/ADR-003-progressive-evidence-acquisition.md).

## Tracks

### Track A - Correctness Foundation and Complete Product

- Reproducible Java/Maven foundation
- Parser-neutral semantic/identity/provenance contracts
- Safe multi-module workspace and build-model intelligence
- Expanded Java semantic frontend behind an adapter
- Explicit uncertainty and diagnostics
- Bounded, framework-versioned Spring producer/injection intelligence
- Finite configuration-space modeling inside one exact build context
- Phase/order-aware bean-registration and binding semantics
- `MUST`/`MAY`/`NEVER`/`UNKNOWN` facts with deterministic witnesses where applicable
- Deterministic canonical Software Knowledge Graph
- Storage-neutral architecture query layer
- Typed architecture-policy engine
- Evidence-first violations
- Detailed inventory, semantic-quality, structural, policy, Spring, and operational metrics
- Explainable architecture health score with separate analysis confidence
- Bounded impact analysis
- CLI-first workflow, API, and a complete evidence-oriented architecture workbench
- Dashboard, structure explorer, focused graph, metrics/score, violations, Spring, impact, and provenance views
- Multi-repository correctness, robustness, determinism, and performance evaluation

### Track B - Architecture Evolution Target

- Compatible content-addressed snapshot identities
- Entity, relationship, Spring-binding, policy, and metric diffs
- Introduced, resolved, persisted, and reintroduced violations
- Source-evidenced comparison queries and UI
- Conditional fact/finding region and witness deltas
- Evidence/configuration-space drift separated from repository change
- Labeled historical/two-snapshot evaluation
- Basic earliest-observed violation analysis only where history semantics are explicit

### Track C - Moonshot

- Correctness-preserving incremental analysis
- Selective graph reconstruction
- Selective rule re-evaluation
- Architecture blame beyond basic Track B comparison
- Large temporal histories
- Advanced hotspot analysis
- Advanced evidence-provider research beyond needs already justified by Track A/B gaps, including selective bytecode/runtime correlation
- AOT/JVM architecture comparison and multi-build-context federation

### Post-M12 Capability Horizons

These horizons preserve a coherent path toward a broader real-world product; they are not SE121 commitments and cannot bypass Track A+B gates.

- **Horizon D — Context federation:** Maven/Gradle/POM-less build-context families, generated/deployment/AOT/runtime evidence and configuration descriptors from container/orchestration systems.
- **Horizon E — System-of-systems assurance:** cross-repository API, event, schema, database-migration and ownership architecture with enterprise CI/PR/IDE integration.
- **Horizon F — Verified AI evolution:** evidence-grounded explanation, gap prioritization, policy drafting, change planning and transformation proposals; deterministic analysis and isolated verification remain authoritative.

## Milestones

### M-1 - Project Operating System Hardening

**Status: COMPLETE.** Human-approved and committed at `86c4ca29fb747797df3e489d978804644a34f1ce` on 2026-09-01. Gate G-1 passed.

Purpose: establish one canonical session bootstrap, completion/handoff contract, normalized eight-role agent system, concise durable state, and tech-first roadmap.

Exit gate: human approves governance diff and authorizes M0. No production code begins inside M-1.

### M0 - Reproducible Foundation

**Status: COMPLETE.** Human-accepted and committed at `375702f9b871dd78fbad99f8bc5994b7b2c499fb` on 2026-09-02. Gate G0 passed with clean Windows/Oracle JDK and Docker Linux/Temurin JDK environments.

Deliver:

- Maven Wrapper with pinned distribution/checksum
- Java/Maven enforcement and `maven.compiler.release`
- repeatable clean build instructions
- deterministic serialization/build foundations
- root README and documented environment

Exit gate G0: clean build from at least two documented environments with exact tool versions and no unexplained artifacts.

### M1 - Semantic, Identity, Uncertainty, and Provenance Contracts

**Status: COMPLETE.** Implemented and verified on 2026-09-02; committed at `b04220e722cc4bc772cbb3ad8531d4dc1ea1a058`. Gate G1 passed.

Deliver parser-neutral immutable contracts for repository snapshots, modules, source documents/spans, entities, relationships, diagnostics, uncertainty, derivation, analysis manifests, content-addressed analysis identity, metric envelopes, metric/score versions, score status, and analysis confidence.

Exit gate G1: contract/invariant/golden tests define deterministic identity and serialization, including `COMPLETE`, `PARTIAL`, `WITHHELD`, and `NOT_APPLICABLE` assessment states; no JavaParser or graph-store type leaks.

### M2 - Semantic Frontend and Ground-Truth Expansion

**Status: COMPLETE (Frontend Implementation Verified).** Implemented, verified with 98 root tests, and confirmed on whole-project multi-file analysis (43 files, 1,488 occurrences). All 18 relationship families are implemented. Gate G2 boundary remains open for M3 build-model intelligence.

Deliver the JavaParser adapter and architecture-relevant relationship set: declarations, inheritance, implementations, permits, type uses, calls, constructor calls, field access, method references, parameters, returns, fields, throws, annotations, generics, and relevant modern Java constructs.

Ground truth distinguishes attempted, correct, incorrect, unresolved, ambiguous, omitted, unsupported, and error outcomes. JavaParser is the human-approved primary frontend (ADR-001); this choice does not pass G2 or remove the validation/replacement gates.

### M3 - Multi-Module Workspace and Build-Model Intelligence

**Status: M3.1–M3.7 DELIVERED; M3.8 COMPLETION SLICE ACTIVE; G2 WITHHELD PENDING DEPENDENCY ARTIFACTS.** Progressive external parent/BOM POM acquisition, qualified inactive profile baselines and cross-release `ct.sym` are implemented and verified (197 root tests pass). Spring PetClinic execution successfully resolves external Spring Boot parent and imported BOMs, and recovers complete source ownership/decoding for all 50 Java files. M3.8 completes Milestone M3 by providing bounded dependency artifact (JAR) acquisition into an isolated or selected local cache (`dependency.artifact-cache:m3.8`), admitting full compile classpaths so source sets reach the JavaParser frontend and produce real semantic metrics to advance Gate G2.

Deliver safe understanding of Maven parent POMs, modules, source/generated-source roots where safely discoverable, dependency management, BOMs, dependency scopes, module dependencies, and exact classpath manifests. Build discovery is a provider family rather than a requirement that repositories conform to Maven: POM-less, Gradle and later build shapes use neutral source-plan/build adapters as evidence justifies them. The formal specification is maintained in the provisional [M3 Workspace and Build-Model Intelligence Contract](architecture/m3-workspace-build-model.md). Missing generated sources or effective-model inputs remain explicit acquisition gaps rather than disappearing from coverage.

M3 also introduces the first normalized capability-gap/acquisition contract over M2 observations and build-model coverage, following the provisional [Progressive Evidence Acquisition and Capability-Gap Contract](architecture/evidence-acquisition.md). This includes provider identity, stable reason/mechanism catalogs, typed evidence requirements, attempt provenance and explicit provider conflicts; implementation must remain versioned and tested.

Decouple analyzer execution from analyzed repository targets (**analyzer-runtime != analyzed-platform**):
- **Separate source language level from platform symbol view:** Discover source language level (syntax support) and target platform release (standard library APIs) independently from `maven.compiler.source`, `maven.compiler.target`, `maven.compiler.release`, `<java.version>`, or toolchains. Supporting a target platform symbol view for Java N does not imply parser syntax support for all Java N language features; syntax beyond verified parser capabilities and preview features remain explicit unsupported/degraded outcomes.
- **Toolchain / `JAVA_HOME` / Platform symbol acquisition:** Support configured target JDKs via `JAVA_HOME`, toolchains, or platform symbol views (`rt.jar` for Java 8, `jmods`/`ct.sym` for Java 9+) rather than restricting resolution exclusively to the analyzer's host JDK 21 image.
- **Platform provenance:** Record the exact analyzed platform version, vendor, source release, and symbol-view hash in the analysis manifest and provenance.
- **Source-encoding evidence:** Resolve charset per module/source set from ordered authoritative evidence such as a supported BOM, build declaration (for example `<project.build.sourceEncoding>`), supported repository configuration, or explicit analysis configuration. If none exists, use only a declared analysis policy such as assumed UTF-8 or withhold/degrade the input; never inherit an ambient host default or present the assumption as repository fact. UTF-8 with BOM must be handled deterministically without corrupting source coordinates; byte sequences invalid under the selected charset or declared/byte mismatches yield explicit input-error/degraded outcomes rather than speculative charset guessing; original raw bytes and SHA-256 digests are strictly preserved alongside charset provenance.

Do not execute arbitrary target lifecycle plugins. M3's initial Gradle path accepts an explicit classpath unless a separately approved safe approach exists; this is an M3 delivery boundary, not a prohibition on future declarative, tool-model, or explicitly authorized isolated build/sandbox providers.

Exit gate G2: pinned multi-module fixtures and a real repository reproduce module/source/classpath models without hidden dependency supersets, decouple the analyzer runtime from analyzed repository target platforms, account for registered input/acquisition gaps and provider failures, and pass an early representative real-repository semantic coverage checkpoint (measuring attempted, resolved, unresolved, ambiguous, unsupported, omitted/unmapped, error, and adjudicated incorrect outcomes across registered categories, with reason-level breakdowns for degraded facts) before treating the frontend foundation as mature.

### M4 - Spring Semantic Intelligence

M4 changes from single-context candidate enrichment to **bounded conditional architecture semantics** inside one exact M3 build context. [ADR-004](decisions/ADR-004-staged-conditional-architecture-semantics.md) and the [canonical semantics contract](architecture/conditional-architecture-semantics.md) govern the direction; no M4 production implementation exists yet.

#### M4-R0 — Research and Semantics Gate

Before production Spring semantics:

- approve producer/candidate/injection/condition/configuration-space identities;
- freeze `spring-mechanisms:v2` and the first supported Spring Boot/Framework version matrix;
- define repository and user-supplied configuration envelopes, precedence and finite-domain abstraction;
- adjudicate profile/property/classpath and ordered `OnBean`/`OnMissingBean`/single-candidate fixtures against official behavior;
- define UNKNOWN, branching and resource-limit behavior;
- implement an exhaustive small-space oracle specification and preregister solver/baseline/corpus protocols.

#### M4A — Mechanism Ground Truth

Detect and account for direct/composed stereotypes, component scanning, `@Bean`, constructor/field/method/`@Bean` parameter injection, JSR-330/`@Resource`, collection/provider forms, qualifiers, primary/fallback, framework entry points, auto-configuration, factories, Spring Data, generated members, XML, programmatic registration, lookup, SpEL and runtime-dynamic mechanisms. Every registered row is `SUPPORTED`, `CONDITIONAL`, `DYNAMIC`, `UNSUPPORTED` or `OUT_OF_SCOPE` with a closed denominator and evidence need.

Lombok and Spring Data are evidence-gated mechanism families. Do not synthesize members or repository beans from a broad annotation/interface heuristic. Exact generator/framework version, configuration, registration/scanning scope and supporting evidence are required; otherwise retain candidates and capability gaps.

#### M4B — Configuration Space and Condition IR

Deliver storage- and solver-neutral condition expressions; separate build constants, exogenous profile/property inputs, endogenous bean-state predicates and opaque/dynamic conditions; distinguish one existing realized `ConfigurationIdentity` from a new content-addressed `ConfigurationSpaceIdentity`.

#### M4C — Phase/Order-Aware Spring Resolution

Deliver versioned configuration-parse and bean-registration transitions, ordered/partially ordered auto-configuration handling, candidate activation, type/generic matching and qualifier/priority binding. Missing order or opaque behavior branches only within explicit limits and otherwise remains `UNKNOWN`. Do not use an unordered generic fixpoint as Spring truth.

#### M4D — Truth Regions and Witnesses

Classify facts and bindings over the feasible modeled space as `MUST`, `MAY`, `NEVER` or `UNKNOWN`; produce deterministic, minimized and revalidated witnesses/counter-witnesses; preserve unresolved regions and operational failures separately.

#### M4E — Validation and G3

Run adjudicated microfixtures, interaction fixtures and representative repositories across pinned framework versions. Compare against Java-static, realized-current-config, flat-condition and applicable ArchUnit/Spring Modulith/Jasmine baselines. Include failures in the denominator and report false-certainty plus false-unconditional-warning rates.

Static non-resolution is not a permanent verdict. Later configuration, generated-source, bytecode, isolated build or runtime providers may add evidence without erasing the original observation. `OUT_OF_SCOPE` is a catalog boundary, not a permanent platform prohibition.

Exit gate G3: `spring-mechanisms:v2` is fully accounted for; the approved semantic fragment has reviewed truth-region/binding evidence, valid deterministic witnesses, no silent universal promotion, explicit resource/version boundaries and bounded real-repository results. Do not claim complete Spring runtime-container equivalence.

### M5 - Canonical Graph, Metrics, and Architecture Query Layer

Deliver deterministic graph construction as a projection of canonical Java and conditional Spring facts, stable entity/occurrence/relationship/region identities, graph invariants, storage port, structural metric computation, focused configuration-aware projections, and query services for summaries, inventory, metrics, symbol lookup, dependencies, dependents, paths, cycles, evidence, impact, activation explanation and snapshot comparison preparation.

Required projections include one realized configuration, `MUST` facts, condition-labeled `MAY` facts, bounded exploratory union and unknown/gap overlays. A union projection cannot be treated as one runtime application world. Graph storage does not own condition solving or policy quantification.

Every metric exposes a stable ID/version, scope, value/unit, formula semantics, inputs, analysis/configuration identity, status, denominator where applicable, and uncertainty. Hand-computed micrographs and golden fixtures verify structural metrics.

Neo4j is evaluated only as an adapter.

Exit gate G4: idempotency, uniqueness, provenance, persistence round-trip, metric correctness, bounded projection and query contract tests pass across realized, `MUST`, `MAY` and unknown projections, including controls that reject infeasible cross-configuration paths.

### M6 - Policy, Evidence, and Explainable Architecture Assessment

Deliver schema-validated external policy representation compiled to a typed internal model, initially covering forbidden dependency, layer/module/package boundary, and cycle rules.

Each policy result composes rule predicates with fact truth regions. Findings distinguish `MUST`, `MAY` and `UNKNOWN`; `NEVER` is a clean result within the modeled space rather than a warning. Each finding carries rule/version, source/target identities, supporting relationships, source spans, semantic status, graph path, configuration-space/affected-region identity, witness/counter-witness where applicable, evidence gaps and limitations. Severity and configuration quantifier remain orthogonal.

Deliver policy/violation metrics and a versioned explainable architecture health score with dimension breakdown, raw inputs, contributions, penalties, caps and withheld reasons. Scoring must define how conditional regions contribute and must not treat unknown configurations as healthy. Architecture health remains separate from analysis confidence. Exact weights and thresholds require labeled examples, sensitivity analysis and human approval before being treated as confirmed.

Exit gate G5: positive, negative, ambiguous, conditional and controlled architecture-mutation cases pass; witness replay, infeasible-path rejection, score golden cases, expected monotonicity properties, missing-evidence behavior, sensitivity analysis and formula-version tests pass without hidden false certainty.

### M7 - Impact, CLI, and Interoperability

Deliver bounded direct/transitive/policy impact queries, configuration-space selection, witness replay inputs, a complete CLI workflow and canonical exports for inventory, metrics, score explanations, conditional findings, provenance and limitations through JSON, SARIF and GraphML where applicable. Impact remains potential structural/configuration-qualified impact, not guaranteed runtime behavior.

Exit gate: the entire Track A analysis/evidence workflow runs locally without backend or Neo4j.

### M8 - Backend API and Complete Architecture Workbench

Deliver asynchronous cancellable analysis jobs and versioned query APIs plus the required workbench:

- analysis setup/history and provenance;
- overview dashboard with repository inventory, semantic coverage, confidence, violations, metrics, score, hotspots, duration, and limitations;
- repository/module/package/type/member structure explorer;
- focused interactive architecture graph with search, filters, grouping, expansion, path highlighting, legends, limits, and export;
- metrics catalog, distributions, drill-down, score breakdown, and contribution/penalty evidence;
- violation explorer with rule, severity, status, uncertainty, graph path, and exact source evidence;
- Spring component, endpoint, injection-candidate, condition/registration explanation and conditional/ambiguous views;
- configuration-space summary, realized-configuration selector, `MUST`/`MAY`/`UNKNOWN` filters, affected-region and witness views;
- bounded impact views; and
- complete loading, empty, partial, error, canceled, and oversized-result states.

The UI must use stable query services, bounded payloads, pagination/cancellation where applicable, progressive graph expansion, accessible status cues, and registered reference-environment performance budgets. It must not recompute canonical metrics or issue arbitrary graph-store queries.

Exit gate: a user can complete the registered end-to-end product journey on a pinned external repository, compare a realized view with conditional regions, replay a finding witness, drill from dashboard/score/violation/graph to exact evidence, and observe honest degraded behavior on a partial-evidence scenario. CLI, API, exports and UI agree on canonical values.

### M9 - Multi-Repository Evaluation and Hardening

Deliver curated condition/registration/binding microfixtures, interaction fixtures, PetClinic, medium, multi-module and larger repositories; controlled partial-classpath/configuration experiments; architecture mutations with expected affected regions; metric golden cases; score sensitivity and missing-evidence experiments; graph/query/UI scale scenarios; accessibility/usability review of primary workflows; robustness matrix; repeated determinism/performance runs; and immutable raw results. Corpus inclusion, labels, baselines and primary metrics are registered before result interpretation.

Exit gate G6: independent review supports the exact bounded Track A claims, metric and score correctness boundaries, registered product performance/usability criteria, and a clean reproducible product workflow.

### M10 - Track A Release Gate

Freeze Track A contracts and claim language. Resolve all blocking semantic, Spring, graph, metric, score, policy, evidence, robustness, accessibility, and product defects before Track B. Track A cannot pass with an analyzer/CLI-only result.

Human approval is mandatory to continue.

### M11 - Track B Architecture Evolution

Deliver compatible snapshot comparison for semantic facts, conditional regions, graph/policy/metric/score results, bindings, witnesses and evidence; classify introduced/resolved/persisted/reintroduced findings plus region expanded/shrunk and `MUST`/`MAY`/`NEVER`/`UNKNOWN` transitions; provide evidence-backed comparison queries/UI and one labeled historical corpus. Reject or visibly qualify comparisons across incompatible analyzer, build-context, configuration-space, framework/registration, policy, metric, score or result-affecting limit versions.

Exit gate G7: known configuration-hidden evolution events and affected regions are reproduced; analyzer/build/configuration/evidence drift is distinguished from repository change; evidence loss never becomes a resolved violation.

### M12 - Technical Integration and Final Reproducibility

Deliver the Track A + B integrated visual product, clean-clone reproduction, current architecture documentation, benchmark evidence, known limitations and a reliable local demonstration covering dashboard, metrics, score, conditional graph/findings, witness, evidence, impact, provenance and compatible conditional snapshot comparison. Publication packaging and post-M12 context/AI horizons remain optional future work.

## Milestone DAG

```text
M-1 Human approval [COMPLETE: 86c4ca2]
  -> M0 Reproducible foundation
  -> M1 Semantic/identity/provenance contracts
       -> M2 Semantic frontend + ground truth
       -> M3 Workspace/build model
       -> M5 base canonical graph/metrics/query foundations
  G2 + M2 + M3 -> M4-R0 semantics gate
  M4-R0 -> M4A mechanism ground truth
        -> M4B condition/configuration IR
        -> M4C staged registration/binding
        -> M4D truth regions/witnesses
        -> M4E validation -> G3
  M4 + M5 -> M6 conditional policy/evidence/assessment
  M6 -> M7 Impact/CLI/export
  M7 -> M8 Backend/complete workbench
  M2 + M3 + M4 + M6 -> M9 External evaluation
  M8 + M9 -> M10 Track A gate
  M10 human approval -> M11 Conditional architecture evolution
  M11 -> M12 Technical integration
  M12 -> optional Track C -> post-M12 horizons D/E/F
```

## Gate-Driven Capability Waves

The former approximate 24-week calendar is retired as an authority. The owner prioritizes technical depth and has not imposed a delivery deadline; forcing calendar estimates would encourage shallow scope cuts and make research uncertainty look scheduled. Progress is governed by evidence-bearing gates:

| Wave | Capability boundary | Exit evidence |
|---|---|---|
| Foundation | M0–M3/G2 | Reproducible build context and reviewed semantic denominator |
| Conditional semantics | M4-R0–M4E/G3 | Versioned Spring fragment, closed mechanism denominator, truth-region/witness correctness |
| Architecture assurance | M5–M6/G4–G5 | Conditional graph/query/policy/metric/assessment invariants |
| Product hardening | M7–M10/G6 | Complete evidence-oriented workbench and multi-repository acceptance |
| Evolution | M11/G7 | Reproduced conditional/evidence deltas on historical cases |
| Integration | M12/G8 | Clean reproducible Track A+B demonstration |
| Optional expansion | Track C and horizons D/E/F | Separate human-approved gates and evidence |

Estimates may be introduced locally only after a wave has an approved denominator and measured throughput. They never authorize skipping a gate.

## Parallel Workstreams

| Workstream | Starts | Primary outputs |
|---|---|---|
| Infrastructure/reproducibility | M0 | Toolchain, clean builds, manifests |
| Semantic intelligence | M1 | Contracts, frontend, diagnostics |
| Workspace/build intelligence | M1/M3 | Modules, roots, dependencies/classpaths |
| Benchmark/ground truth | M1 | Fixtures, labels, protocols, raw results |
| Spring intelligence | M4-R0 | Mechanism ground truth, condition IR, registration transitions, bindings, truth regions and witnesses |
| Graph/metrics/query | M5 | Canonical conditional projections, invariants, structural metrics and services |
| Policy/evidence/assessment | M6 | Configuration-qualified rules/findings, Evidence Bundles and explainable score |
| Product | M7/M8 | CLI, API, configuration/witness workbench and exports |
| Evolution | M11 | Conditional fact/finding/evidence deltas and affected regions |
| Documentation | Continuous | Current contracts, decisions, state |

## Stage Gates

| Gate | Decision |
|---|---|
| G-1 — PASSED | M-1 operating system approved and committed; M0 authorized |
| G0 — PASSED | Foundation builds reproducibly in the documented Windows/Oracle and Docker Linux/Temurin environments |
| G1 — PASSED | Parser-neutral semantic/identity/metric/assessment contracts are stable and deterministic |
| G2 | Frontend, multi-module build model and acquisition-gap accounting meet ground truth |
| M4-R0 | Condition/configuration identities, v1 semantics fragment, oracle, baselines, corpus protocol and limits are approved before production M4 semantics |
| G3 | Bounded phase/order-aware Spring inference meets truth-region, witness, version and closed-taxonomy evidence criteria |
| G4 | Canonical conditional graph/metric/query invariants pass, including infeasible-path rejection |
| G5 | Conditional policy/evidence/score correctness passes region mutations, negatives, witness replay and sensitivity checks |
| G6 | Complete Track A visual product and multi-repository evidence are sufficient |
| G7 | Track B conditional/evidence evolution events and affected regions are correct and reproducible |
| G8 | Final Track A + B integration is reproducible |

## Continuous Evaluation

- Exhaustive microfixtures and contract tests on semantic changes
- Parser/Spring ground truth at their gates
- Exhaustive-oracle conformance for the supported condition/registration fragment
- False-certainty and false-unconditional-warning measurements
- Witness validity/minimality and affected-region precision/recall
- Early representative real-repository coverage checkpoints across registered categories with reason-level breakdowns before late-stage M9 hardening
- Rule mutation and negative-control tests on policy changes
- Full corpus runs at major gates
- Immutable versioned raw results
- Repeated deterministic output comparison
- End-to-end phase time and memory measurement
- Golden inventory/metric counts and hand-computed micrograph checks
- Score missing-evidence, sensitivity, versioning, and explanation checks
- Registered graph/query/UI scale and primary-workflow accessibility checks
- Traceability from dashboard and score values to canonical inputs/evidence
- Independent review for parser promotion, Spring claims, Track A, and Track B

## Fallback and Scope Cuts

Cut in this order if time/evidence requires:

1. Track C incremental analysis and large history
2. Advanced architecture blame/hotspot analysis
3. Selective runtime/AOT evidence unless a registered correctness gap makes it gate-critical; preserve provider boundaries and capability-gap records
4. SAT/BDD optimization if the exhaustive oracle and bounded enumeration satisfy the approved fragment; preserve the reasoner port
5. Advanced comparison visualizations beyond the required conditional Track B flow
6. Neo4j persistence, retaining canonical graph/file output
7. Nonessential backend/workbench extras, retaining the required Track A dashboard, metrics, score, conditional graph/finding, witness, evidence and provenance workflow

Never cut semantic ground truth, uncertainty, provenance, configuration-space identity, no-false-universal semantics, graph/metric invariants, policy evidence, witness correctness, explainable score safeguards, the required Track A workbench, deterministic output, reproducibility or honest limitations.

## Publication and Defense

Paper readiness, venue selection, extensive artifact badging, and elaborate defense scripting are future/optional. Preserve strong raw evidence and methodology now so those paths remain available without steering current engineering priorities.

## Immediate Sequence

1. Preserve completed M0–M2 and delivered M3.1–M3.7 contracts/evidence.
2. Complete the active M3.8 bounded dependency artifact acquisition slice and the full G2 review; the research redirection does not bypass this gate.
3. After G2, execute M4-R0 as a research/architecture gate before writing production Spring semantics.
4. Implement the smallest approved conditional fragment through M4A–M4D with exhaustive oracle fixtures and closed denominators.
5. Run M4E baselines/real-repository evaluation and obtain G3 acceptance before treating conditional Spring facts as stable M5/M6 inputs.
6. Continue M5–M10 Track A product work, obtain human approval, then execute M11 conditional architecture evolution and M12 integration.
