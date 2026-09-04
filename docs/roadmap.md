# SE121 Technical Roadmap

## Status and Intent

This is the canonical future-direction document. Operational status belongs in `docs/current-state.md`.

The approved SE121 North Star is **Track A + Track B**:

- **Track A:** complete correctness foundation and usable architecture-intelligence product.
- **Track B:** architecture evolution across compatible snapshots after Track A gates.
- **Track C:** moonshot research; never required to make Track A + B credible.

The roadmap is tech-first. Ground truth, benchmarks, evidence, and reproducibility remain mandatory engineering verification. Publication and extensive defense packaging are later/optional activities.

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
- Bounded Spring candidate/injection intelligence
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

Deliver the JavaParser adapter and architecture-relevant relationship set: declarations, inheritance, implementations, permits, type uses, calls, constructor calls, field access, method references, parameters, returns, fields, throws, annotations, generics, and relevant modern Java constructs.

Ground truth distinguishes attempted, correct, incorrect, unresolved, ambiguous, omitted, unsupported, and error outcomes. JavaParser is the human-approved primary frontend (ADR-001); this choice does not pass G2 or remove the validation/replacement gates.

### M3 - Multi-Module Workspace and Build-Model Intelligence

Deliver safe understanding of Maven parent POMs, modules, source/generated-source roots where safely discoverable, dependency management, BOMs, dependency scopes, module dependencies, and exact classpath manifests. Missing generated sources or effective-model inputs remain explicit acquisition gaps rather than disappearing from coverage.

M3 also introduces the first normalized capability-gap/acquisition contract over M2 observations and build-model coverage, following the provisional [Progressive Evidence Acquisition and Capability-Gap Contract](architecture/evidence-acquisition.md). This includes provider identity, stable reason/mechanism catalogs, typed evidence requirements, attempt provenance and explicit provider conflicts; implementation must remain versioned and tested.

Decouple analyzer execution from analyzed repository targets (**analyzer-runtime != analyzed-platform**):
- **Separate source language level from platform symbol view:** Discover source language level (syntax support) and target platform release (standard library APIs) independently from `maven.compiler.source`, `maven.compiler.target`, `maven.compiler.release`, `<java.version>`, or toolchains. Supporting a target platform symbol view for Java N does not imply parser syntax support for all Java N language features; syntax beyond verified parser capabilities and preview features remain explicit unsupported/degraded outcomes.
- **Toolchain / `JAVA_HOME` / Platform symbol acquisition:** Support configured target JDKs via `JAVA_HOME`, toolchains, or platform symbol views (`rt.jar` for Java 8, `jmods`/`ct.sym` for Java 9+) rather than restricting resolution exclusively to the analyzer's host JDK 21 image.
- **Platform provenance:** Record the exact analyzed platform version, vendor, source release, and symbol-view hash in the analysis manifest and provenance.
- **Source-encoding evidence:** Resolve charset per module/source set from ordered authoritative evidence such as a supported BOM, build declaration (for example `<project.build.sourceEncoding>`), supported repository configuration, or explicit analysis configuration. If none exists, use only a declared analysis policy such as assumed UTF-8 or withhold/degrade the input; never inherit an ambient host default or present the assumption as repository fact. UTF-8 with BOM must be handled deterministically without corrupting source coordinates; byte sequences invalid under the selected charset or declared/byte mismatches yield explicit input-error/degraded outcomes rather than speculative charset guessing; original raw bytes and SHA-256 digests are strictly preserved alongside charset provenance.

Do not execute arbitrary target lifecycle plugins. M3's initial Gradle path accepts an explicit classpath unless a separately approved safe approach exists; this is an M3 delivery boundary, not a prohibition on future declarative, tool-model, or explicitly authorized isolated build/sandbox providers.

Exit gate G2: pinned multi-module fixtures and a real repository reproduce module/source/classpath models without hidden dependency supersets, decouple the analyzer runtime from analyzed repository target platforms, account for registered input/acquisition gaps and provider failures, and pass an early representative real-repository semantic coverage checkpoint (measuring attempted, resolved, unresolved, ambiguous, unsupported, omitted/unmapped, error, and adjudicated incorrect outcomes across registered categories, with reason-level breakdowns for degraded facts) before treating the frontend foundation as mature.

### M4 - Spring Semantic Intelligence

Deliver direct/composed stereotypes, `@Bean` producer candidates, injection points, constructor rules, assignable candidate sets, qualifiers, primary/fallback, collection injection, and explicit conditional/profile states.

M4 must systematically enumerate and classify all relevant Spring dependency/wiring mechanisms—including annotation-based, constructor, field, setter/method, @Bean parameter, JSR-330/@Resource, collection/provider, qualifier/primary/fallback, conditional/profile, lookup, programmatic registration, factory/auto-configuration, XML/legacy, and runtime-dynamic mechanisms—into SUPPORTED, CONDITIONAL, DYNAMIC, UNSUPPORTED, or OUT_OF_SCOPE; no registered mechanism may be silently omitted, and mechanisms that cannot be statically resolved must still be detected and accounted for with explicit evidence, uncertainty, and limitations.

Static non-resolution is not a permanent verdict. Preserve the evidence need so later configuration, generated-source, bytecode, sandbox or runtime providers can enrich the same canonical model without erasing the original status or provenance.

`OUT_OF_SCOPE` means outside the registered M4 provider/catalog boundary; it does not mean permanently excluded from the platform.

The provisional storage-neutral concepts, versioned closed denominator and initial mechanism matrix are defined in [M4 Spring Intelligence and Closed Mechanism Taxonomy](architecture/m4-spring-intelligence.md). They narrow OQ-3 but remain subject to M4 fixtures, identity decisions and G3 review.

Exit gate G3: pre-registered ground truth across the systematic Spring wiring taxonomy demonstrates bounded correctness without silent omissions. Do not claim Spring runtime-container equivalence.

### M5 - Canonical Graph, Metrics, and Architecture Query Layer

Deliver deterministic graph construction, stable entity/occurrence/relationship identities, graph invariants, storage port, structural metric computation, focused graph projections, and query services for summaries, inventory, metrics, symbol lookup, dependencies, dependents, paths, cycles, evidence, impact, and snapshot comparison preparation.

Every metric exposes a stable ID/version, scope, value/unit, formula semantics, inputs, analysis/configuration identity, status, denominator where applicable, and uncertainty. Hand-computed micrographs and golden fixtures verify structural metrics.

Neo4j is evaluated only as an adapter.

Exit gate G4: idempotency, uniqueness, provenance, persistence round-trip, metric correctness, bounded projection, and query contract tests pass.

### M6 - Policy, Evidence, and Explainable Architecture Assessment

Deliver schema-validated external policy representation compiled to a typed internal model, initially covering forbidden dependency, layer/module/package boundary, and cycle rules.

Each violation carries rule/version, source/target identities, supporting relationships, source spans, semantic status, graph path, configuration, and limitations.

Deliver policy/violation metrics and a versioned explainable architecture health score with dimension breakdown, raw inputs, contributions, penalties, caps, and withheld reasons. Architecture health remains separate from analysis confidence. Exact weights and thresholds require labeled examples, sensitivity analysis, and human approval before being treated as confirmed.

Exit gate G5: positive, negative, ambiguous, and controlled architecture-mutation cases pass; score golden cases, expected monotonicity properties, missing-evidence behavior, sensitivity analysis, and formula-version tests pass without hidden false certainty.

### M7 - Impact, CLI, and Interoperability

Deliver bounded direct/transitive/policy impact queries, a complete CLI workflow, and canonical exports for inventory, metrics, score explanations, violations, provenance, and limitations through JSON, SARIF, and GraphML where applicable. Impact remains potential structural impact, not guaranteed runtime behavior.

Exit gate: the entire Track A analysis/evidence workflow runs locally without backend or Neo4j.

### M8 - Backend API and Complete Architecture Workbench

Deliver asynchronous cancellable analysis jobs and versioned query APIs plus the required workbench:

- analysis setup/history and provenance;
- overview dashboard with repository inventory, semantic coverage, confidence, violations, metrics, score, hotspots, duration, and limitations;
- repository/module/package/type/member structure explorer;
- focused interactive architecture graph with search, filters, grouping, expansion, path highlighting, legends, limits, and export;
- metrics catalog, distributions, drill-down, score breakdown, and contribution/penalty evidence;
- violation explorer with rule, severity, status, uncertainty, graph path, and exact source evidence;
- Spring component, endpoint, injection-candidate, and conditional/ambiguous views;
- bounded impact views; and
- complete loading, empty, partial, error, canceled, and oversized-result states.

The UI must use stable query services, bounded payloads, pagination/cancellation where applicable, progressive graph expansion, accessible status cues, and registered reference-environment performance budgets. It must not recompute canonical metrics or issue arbitrary graph-store queries.

Exit gate: a user can complete the registered end-to-end product journey on a pinned external repository, drill from dashboard/score/violation/graph to exact evidence, and observe honest degraded behavior on a partial-evidence scenario. CLI, API, exports, and UI agree on canonical values.

### M9 - Multi-Repository Evaluation and Hardening

Deliver curated microfixtures, PetClinic, medium, multi-module, and larger repositories; controlled partial-classpath experiments; architecture mutations; metric golden cases; score sensitivity and missing-evidence experiments; graph/query/UI scale scenarios; accessibility/usability review of primary workflows; robustness matrix; repeated determinism/performance runs; and immutable raw results.

Exit gate G6: independent review supports the exact bounded Track A claims, metric and score correctness boundaries, registered product performance/usability criteria, and a clean reproducible product workflow.

### M10 - Track A Release Gate

Freeze Track A contracts and claim language. Resolve all blocking semantic, Spring, graph, metric, score, policy, evidence, robustness, accessibility, and product defects before Track B. Track A cannot pass with an analyzer/CLI-only result.

Human approval is mandatory to continue.

### M11 - Track B Architecture Evolution

Deliver compatible snapshot comparison, semantic/graph/policy/metric/score diffs, introduced/resolved/persisted/reintroduced violations, evidence-backed comparison queries and UI, and one labeled historical corpus. Reject or visibly qualify comparisons across incompatible analyzer, configuration, policy, metric, or score versions.

Exit gate G7: known evolution events are reproduced and analyzer/configuration drift is distinguished from repository change.

### M12 - Technical Integration and Final Reproducibility

Deliver the Track A + B integrated visual product, clean-clone reproduction, current architecture documentation, benchmark evidence, known limitations, and a reliable local demonstration covering dashboard, metrics, score, graph, violations, evidence, impact, provenance, and compatible snapshot comparison. Publication packaging remains optional future work.

## Milestone DAG

```text
M-1 Human approval [COMPLETE: 86c4ca2]
  -> M0 Reproducible foundation
  -> M1 Semantic/identity/provenance contracts
       -> M2 Semantic frontend + ground truth
       -> M3 Workspace/build model
       -> M5 Canonical graph/metrics/query foundations
  M2 + M3 -> M4 Spring intelligence
  M4 + M5 -> M6 Policy/evidence/assessment
  M6 -> M7 Impact/CLI/export
  M7 -> M8 Backend/complete workbench
  M2 + M3 + M4 + M6 -> M9 External evaluation
  M8 + M9 -> M10 Track A gate
  M10 human approval -> M11 Track B evolution
  M11 -> M12 Technical integration
  M12 -> optional Track C
```

## Approximate 24-Week Calendar

| Weeks | Focus |
|---|---|
| 1 | M-1 governance approval and handoff |
| 2-3 | M0 reproducible foundation |
| 3-5 | M1 contracts and invariants |
| 5-8 | M2 semantic frontend/ground truth and M3 build model |
| 8-11 | M4 Spring intelligence and M5 graph/metrics/query layer |
| 11-14 | M6 policy, Evidence Bundles, and explainable assessment |
| 14-16 | M7 impact, CLI, and exports |
| 16-19 | M8 complete workbench and continuous M9 evaluation |
| 19-20 | Track A hardening and G6/M10 review |
| 20-23 | M11 Track B evolution after approval |
| 23-24 | M12 integration and reproducibility |

Dates are guidance. Gates, not calendar pressure, authorize progression.

## Parallel Workstreams

| Workstream | Starts | Primary outputs |
|---|---|---|
| Infrastructure/reproducibility | M0 | Toolchain, clean builds, manifests |
| Semantic intelligence | M1 | Contracts, frontend, diagnostics |
| Workspace/build intelligence | M1/M3 | Modules, roots, dependencies/classpaths |
| Benchmark/ground truth | M1 | Fixtures, labels, protocols, raw results |
| Spring intelligence | M4 | Bean/injection candidates and uncertainty |
| Graph/metrics/query | M5 | Canonical graph, invariants, structural metrics, projections, services |
| Policy/evidence/assessment | M6 | Rules, violations, Evidence Bundles, explainable score |
| Product | M7 | CLI, API, complete workbench, exports |
| Evolution | M11 | Snapshot diffs and events |
| Documentation | Continuous | Current contracts, decisions, state |

## Stage Gates

| Gate | Decision |
|---|---|
| G-1 — PASSED | M-1 operating system approved and committed; M0 authorized |
| G0 — PASSED | Foundation builds reproducibly in the documented Windows/Oracle and Docker Linux/Temurin environments |
| G1 — PASSED | Parser-neutral semantic/identity/metric/assessment contracts are stable and deterministic |
| G2 | Frontend, multi-module build model and acquisition-gap accounting meet ground truth |
| G3 | Bounded Spring inference meets approved evidence criteria and classified wiring taxonomy |
| G4 | Canonical graph/metric/query invariants pass |
| G5 | Policy/evidence/score correctness passes mutations, negatives, and sensitivity checks |
| G6 | Complete Track A visual product and multi-repository evidence are sufficient |
| G7 | Track B evolution events are correct and reproducible |
| G8 | Final Track A + B integration is reproducible |

## Continuous Evaluation

- Exhaustive microfixtures and contract tests on semantic changes
- Parser/Spring ground truth at their gates
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
3. SE121 bytecode-provider implementation unless a registered correctness gap makes it gate-critical; preserve the provider boundary and capability-gap record
4. Advanced comparison visualizations beyond the required Track B comparison flow
5. Neo4j persistence, retaining canonical graph/file output
6. Nonessential backend/workbench extras, retaining the required Track A dashboard, metrics, score, graph, violation, evidence, and provenance workflow

Never cut semantic ground truth, uncertainty, provenance, graph/metric invariants, policy evidence, explainable score safeguards, the required Track A workbench, deterministic output, reproducibility, or honest limitations.

## Publication and Defense

Paper readiness, venue selection, extensive artifact badging, and elaborate defense scripting are future/optional. Preserve strong raw evidence and methodology now so those paths remain available without steering current engineering priorities.

## Immediate Sequence

1. ~~Human reviews and approves M-1.~~ Complete.
2. ~~Human commits the approved M-1 baseline.~~ Complete at `86c4ca29fb747797df3e489d978804644a34f1ce`.
3. ~~Complete the bounded M0 reproducible Maven/Java foundation task.~~ Complete; G0 passed on 2026-09-02.
4. ~~Complete M1 semantic, identity, uncertainty, provenance, metric-envelope, and assessment-status contracts.~~ Complete; G1 passed on 2026-09-02.
5. M1 is committed; the human-approved primary frontend choice is recorded in ADR-001. Comparative evidence remains bounded and does not pass G2.
6. Begin M2 contract/ground-truth design before production JavaParser extraction; preserve the replaceable SemanticFrontend boundary and empirical G2 criteria.
