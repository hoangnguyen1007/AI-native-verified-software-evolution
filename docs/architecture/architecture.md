# Architecture Overview

## Status

This document describes the approved architecture direction and the boundaries that future milestone contracts must refine.

- Modular-monolith direction and separation principles: **CONFIRMED**.
- Complete visual architecture-intelligence product outcome: **CONFIRMED**.
- M1 contract placement and package boundaries in the existing `analyzer` module: **CONFIRMED by implementation and contract tests**.
- Later Maven modules, adapter package layouts, frameworks, storage adapter, metric formulas, score weights, and UI performance budgets: **PROVISIONAL** until their gates.

## System Purpose

Analyze Java/Spring Boot repositories to produce deterministic, evidence-backed architecture intelligence: semantic facts, Software Knowledge Graph projections, metrics, explainable architecture assessment, policy violations, bounded impact, visual exploration, and compatible snapshot evolution.

The platform is not complete as an analyzer alone. Track A includes a usable dashboard and architecture workbench.

Milestone boundaries constrain current delivery and claims, not the platform's eventual evidence sources. The durable restriction is not “source only” or “static only”; it is that every observation must remain safe to acquire, explicit about uncertainty, attributable to versioned inputs, and impossible to silently omit or promote beyond its evidence.

## Architecture Style

**Modular monolith.**

- one repository and reproducible build;
- explicit module/package boundaries;
- core domain independent of parser, graph database, HTTP, and frontend frameworks;
- adapters replaceable without redesigning semantic or product contracts;
- no microservices without measured requirements and human approval.

## Logical Pipeline

```text
Repository/Snapshot Acquisition
  -> Safe Workspace and Build Model
  -> Java Semantic Frontend Adapter
  -> Spring Semantic Enrichment
  -> Canonical Facts and Graph Construction
  -> Architecture Query and Metric Services
  -> Policy, Evidence, and Assessment
  -> CLI / Export / Versioned API
  -> Architecture Workbench

Compatible snapshots
  -> Comparison Compatibility Check
  -> Entity / Relationship / Metric / Score / Violation Deltas
  -> Evolution Queries and UI
```

Evidence acquisition is a feedback loop around the pipeline, not a one-time source-loading step. A provider may report a fact, a candidate set, a conflict, or an unresolved capability gap. Registered gaps can request the least invasive useful next provider—declarative build/configuration metadata, dependencies or generated sources, bytecode, controlled sandbox/build output, runtime observation, or a future source frontend—subject to phase scope, authorization and safety. Each pass produces new provenance; it never mutates an earlier observation into false certainty.

## Architectural Components

| Component | Responsibility | Must not own |
|---|---|---|
| Repository acquisition | Snapshot identity, safe file inventory, content hashes | Semantic interpretation |
| Evidence acquisition coordinator | Provider selection, trust/permission policy, gap escalation, evidence reconciliation and attempt provenance | Fabricating facts or treating unavailable evidence as absence |
| Evidence providers | Versioned source, build, generated-source, bytecode, configuration, sandbox/build or runtime observations | Canonical truth, policy meaning or silent conflict resolution |
| Workspace/build model | Modules, roots, dependencies, scopes, exact classpath manifest | Arbitrary target lifecycle execution |
| Semantic frontend port | Parser-neutral Java facts, diagnostics, evidence | Graph storage or policy meaning |
| JavaParser adapter | Parsing and symbol-resolution implementation | Domain types outside its adapter boundary |
| Spring intelligence | Producers, bean-definition candidates, exact injection points, conditions, binding candidates, endpoints and a versioned mechanism taxonomy | Runtime-container equivalence, flat certain `INJECTS` edges or silent omission of unresolvable wiring |
| Canonical graph builder | Deterministic nodes, occurrences, relationships, provenance | UI-specific graph shapes |
| Graph storage adapter | Persist/load canonical graph representations | Canonical domain semantics |
| Architecture query services | Search, dependencies, paths, cycles, projections, evidence, comparison | Presentation rendering |
| Metric engine | Versioned deterministic inventory/structural/quality metrics | Opaque or UI-only calculations |
| Policy engine | Typed rules and evidence-first findings | AI diagnosis or automatic remediation |
| Assessment engine | Explainable health dimensions and score status | Analyzer-confidence calculation or hidden weights |
| Analysis-confidence service | Evidence completeness and qualification/withholding decisions | Repository health judgment |
| Application services | Job orchestration, cancellation, limits, analysis lifecycle | Core semantic algorithms |
| CLI/export adapters | Scriptable workflow and canonical result formats | Independent metric semantics |
| Versioned API | Bounded access to application/query services | Arbitrary graph-store queries |
| Architecture workbench | Dashboard, explorer, graph, metrics, score, violations, Spring, impact, provenance | Recomputing canonical facts, metrics, or scores |
| Evolution service | Compatible snapshot deltas and event classification | Comparing incompatible identities silently |

## Dependency Direction

```text
presentation adapters (CLI, API, workbench)
                 |
                 v
application orchestration and query ports
                 |
                 v
semantic / graph / metric / policy / assessment domain
                 ^
                 |
infrastructure adapters (JavaParser, Maven model, storage, Git)
```

Dependencies point toward stable contracts. Core domain code must not import:

- JavaParser AST or resolution types;
- Neo4j driver/query types;
- Spring Web/controller types;
- frontend framework types;
- process- or environment-specific infrastructure.

Later Maven module boundaries remain evidence-driven. The logical boundaries above are invariant even if several components initially share a module.

M1 resolves the first placement decision: parser- and storage-neutral contracts live under `com.evolution.analysis.contract` in the existing `analyzer` module. Adapter/module extraction remains a later evidence-driven choice. See [M1 Contracts](m1-contracts.md).

## Canonical Data Flow

### Analysis inputs

- repository and immutable snapshot identity;
- source hashes and workspace model;
- exact classpath/dependency manifest;
- analyzer and schema versions;
- semantic, Spring, metric, score, policy, and limit configuration.
- enabled evidence-provider identities, trust/permission policy, acquired artifact identities and prior acquisition-gap records.

### Canonical outputs

- entity and occurrence identities;
- relationships and candidate relationships;
- complete source evidence spans;
- diagnostics, unresolved/ambiguous/unsupported/error outcomes;
- graph identities and projections;
- metric envelopes;
- analysis-confidence result;
- policy findings and Evidence Bundles;
- architecture score and dimension explanations when evidence permits;
- provenance/reproducibility manifest.

## Metric and Assessment Boundary

Metrics are computed from canonical facts, graph/query outputs, and policy findings. They are not stored only as UI counters and are not tied to a graph-database query language.

Every metric has a stable ID/version, scope, typed value/unit, status, inputs, provenance, denominator where applicable, and limitations.

Architecture assessment consumes versioned metrics and findings. It emits:

- score status: `COMPLETE`, `PARTIAL`, `WITHHELD`, or `NOT_APPLICABLE`;
- overall and dimension values where permitted;
- raw inputs, contributions, penalties, caps, and withheld reasons;
- formula/policy/configuration identities.

Analysis confidence is computed separately. Missing evidence can qualify or withhold assessment; it cannot make architecture look healthier.

## Product Query Boundary

All product surfaces use the same versioned query services for:

- analysis summary, inventory, provenance, and limitations;
- metric catalog/results and score explanations;
- entity search/detail;
- focused subgraphs and bounded expansion;
- dependencies, dependents, paths, and cycles;
- violation aggregation/detail/evidence;
- Spring components, endpoints, injections, and ambiguity;
- bounded impact;
- compatible snapshot comparison.

The workbench may compose responses for presentation, but it must not issue arbitrary storage queries or implement independent formulas.

## Workbench Views

The required Track A workbench contains:

1. analysis setup/history;
2. overview dashboard;
3. structure explorer;
4. focused interactive graph;
5. metrics and explainable score;
6. violations and Evidence Bundles;
7. Spring and bounded-impact views;
8. provenance, limitations, and export access.

Track B adds compatible snapshot comparison and evolution views.

The detailed contract and acceptance criteria are defined in [Product Outcome, Metrics, Scoring, and Workbench Contract](product-outcome.md).

## Evidence Model

Every important relationship, metric input, violation, and score penalty preserves as applicable:

| Evidence element | Requirement |
|---|---|
| Repository/snapshot | Exact identity |
| Analysis/configuration | Exact versioned identity |
| Source and target | Stable identities |
| Source location | File plus begin/end line/column |
| Semantic status | Direct, derived, inferred, unresolved, ambiguous, conditional, unsupported, or error as defined by contract |
| Derivation | Inputs and deterministic rule/formula |
| Graph evidence | Supporting relationship/path identities |
| Policy evidence | Rule ID/version and configuration |
| Assessment evidence | Metric/score formula versions, contributions, penalties, and qualification |
| Limitations | Missing inputs and bounded-claim language |
| Acquisition | Provider/method/version, trust and permission context, inputs, attempts, failures, conflicts and resulting capability gaps |

## Safety and Scale Boundaries

- Do not execute arbitrary untrusted Maven/Gradle lifecycle code during normal analysis. Any future execution-backed provider is opt-in, explicitly authorized, isolated, resource-bounded, network/filesystem constrained as appropriate, and records inputs, outputs and side effects.
- Bound repository size, files, graph projections, traversal depth, result count, and job duration.
- Use pagination, cancellation, aggregation, and progressive graph expansion.
- Never render the complete raw graph by default.
- Do not expose secrets, raw internal stack traces, or unsafe local paths through product surfaces.
- Preserve deterministic output ordering where applicable.

## Verification Boundaries

- semantic ground truth verifies frontend facts;
- hand-computed graphs and invariants verify graph/structural metrics;
- mutation and negative fixtures verify policies;
- golden and sensitivity cases verify assessment behavior;
- adapter contracts verify storage independence;
- API/UI end-to-end tests verify value agreement and evidence navigation;
- registered corpus and reference environments verify scale and interaction budgets;
- clean-clone runs verify reproducibility.

## Explicit Future Extension Points

The architecture preserves boundaries for later phases even where SE121 does not implement the capability. These are scheduled omissions, not permanent exclusions:

- additional source/build-system frontends and declarative build-model providers;
- generated-source and dependency-artifact acquisition;
- selective bytecode and framework/configuration evidence;
- explicitly authorized controlled build/sandbox and runtime-observation providers;

- graph-guided RAG or AI diagnosis;
- automated repair/refactoring;
- transformation/sandbox verification;
- verified pull requests or CI/CD product integration;
- a second programming language.

## Related Documents

- [Project Context](../project-context.md)
- [Technical Roadmap](../roadmap.md)
- [Product Outcome Contract](product-outcome.md)
- [Knowledge Graph](knowledge-graph.md)
- [M1 Semantic, Identity, Uncertainty, and Provenance Contracts](m1-contracts.md)
- [Progressive Evidence Acquisition and Capability-Gap Contract](evidence-acquisition.md)
- [M4 Spring Intelligence and Closed Mechanism Taxonomy](m4-spring-intelligence.md)
- [ADR-002](../decisions/ADR-002-product-outcome-and-explainable-assessment.md)
- [ADR-003: Progressive Evidence Acquisition](../decisions/ADR-003-progressive-evidence-acquisition.md)
- [Current State](../current-state.md)
