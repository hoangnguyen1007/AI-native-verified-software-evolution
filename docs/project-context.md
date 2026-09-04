# Project Context

## Mission

Build a deterministic, evidence-first platform that understands Java and Spring Boot repositories, reconstructs architecture-relevant semantics, measures architecture structure and health, detects architecture violations, explains every assessment through evidence, and presents the results through a complete visual workbench with a stable foundation for later verified software evolution.

## Product Capability Philosophy

**CONFIRMED by the human owner on 2026-09-04:** the platform adapts to understand each repository as deeply as safely and truthfully possible; it does not require repositories to conform to one analyzer assumption. Prefer maximum capability, minimum assumptions, progressive evidence acquisition, explicit uncertainty, and zero silent omission.

Milestone scope limits current implementation and claims, not the architecture's ultimate evidence envelope. A source-only or current-adapter failure is an explicit capability gap. When justified and authorized, later providers may add build-model, dependency, generated-source, bytecode, configuration, controlled sandbox/build, runtime, or other evidence. All such evidence remains versioned, attributable, bounded by permissions and safety, and reconciled without fabrication or silent replacement of conflicting observations.

## Academic Context

- Course phase: SE121 - Software Architecture Intelligence Platform
- Intended SE121 North Star: Track A correctness foundation plus Track B architecture evolution
- Future phases: SE122 and KLTN
- Primary ecosystem: Java and Spring Boot

## Human-Approved Priorities

1. Technical depth
2. System correctness
3. Architecture quality
4. Engineering quality
5. Product quality
6. Empirical validation
7. Paper/publication readiness later

Research discipline remains required for ground truth, benchmarks, evidence, reproducibility, and honest claims. Immediate work is not optimized around paper writing, venue selection, extensive publication packaging, defense scripting, or unnecessary statistical ceremony.

## SE121 Scope

- Semantic Java source analysis
- Multi-module workspace intelligence
- Safe Maven/build-model understanding
- Spring semantic intelligence
- Software Knowledge Graph construction
- Dependency and architecture-policy modeling
- Evidence-backed architecture violation detection
- Basic, bounded impact analysis
- Stable architecture query services
- Detailed repository, semantic-quality, Spring, policy, and operational metrics
- Versioned and explainable architecture health scoring, separate from analysis confidence
- Focused interactive architecture visualization and evidence navigation
- A usable dashboard/workbench with inventory, metrics, score, graph, violations, Spring, impact, and provenance views
- Reproducible benchmark and evaluation infrastructure
- Track B comparison of compatible repository snapshots and architecture evolution

Architecture-mutation fixtures used to evaluate rule detection are part of SE121 benchmarking.

## SE121 Delivery Non-Goals

- AI diagnosis and graph-guided RAG
- Automated refactoring, patch generation, or OpenRewrite transformation execution
- Sandbox verification of generated changes
- Differential/mutation testing of generated patches
- CI/CD verification and Verified Pull Requests
- Another programming language

These remain SE122/KLTN concerns unless a later explicit human decision changes scope. This scheduling does not make them forbidden platform capabilities or prevent preserving clean extension points during SE121.

## Durable Architectural Constraints

1. Parser-specific objects do not cross the semantic frontend boundary.
2. Graph storage does not define the canonical semantic domain.
3. Backend and frontend use stable architecture query services rather than arbitrary storage queries.
4. Every important semantic relationship and violation preserves inspectable evidence and provenance.
5. Unresolved, ambiguous, partial, inferred, conditional, unsupported, and error outcomes remain explicit.
6. Maven modules, source roots, dependency scopes, parent POMs, dependency management, and BOMs are represented where analysis needs them.
7. Arbitrary untrusted Maven/Gradle lifecycle code is not executed during normal analysis. A future opt-in execution provider must be explicitly authorized, isolated, resource-bounded and fully provenance-recorded.
8. Analysis identity is content-addressed from stable inputs, enabling determinism, compatible evolution, and future caching/incrementality without implementing a cache prematurely.
9. Evidence acquisition is progressive and replaceable. Source analysis is the current baseline; unresolved or unsupported cases remain registered capability gaps that can justify additional build, generated-source, bytecode, configuration, sandbox or runtime providers at an approved gate.
10. The system remains a modular monolith unless measured requirements justify otherwise.
11. Architecture health and analyzer confidence are separate outputs; incomplete evidence can qualify or withhold an assessment but cannot improve it.
12. Metrics and scores are versioned, deterministic, scope-aware, and traceable to canonical inputs and limitations.
13. The UI consumes bounded query projections and never depends on rendering the complete raw graph by default.
14. Track A is not complete with a CLI or analyzer alone; the confirmed product outcome includes a usable visual workbench.

## Source Authority

1. Explicit human decisions
2. Official project/academic documents supplied by the human
3. Verified repository evidence and reproducible experiments
4. Official technical documentation/specifications
5. Peer-reviewed research
6. AI-generated proposals and assumptions

Lower-authority claims cannot become confirmed without higher-authority evidence or human approval.

## Epistemic Classification

| Status | Meaning |
|---|---|
| CONFIRMED | Human-approved decision or directly verified fact |
| PROVISIONAL | Adopted working decision with remaining validation gates |
| HYPOTHESIS | Testable claim requiring evidence |
| ASSUMPTION | Temporary default that has not been validated |
| OPEN QUESTION | Explicit unresolved issue |
| CANDIDATE IDEA | Unadopted option; not a task or approved commitment |

## Durable Technology Direction

| Decision | Status |
|---|---|
| Java 21 compile baseline | CONFIRMED |
| Maven and monorepo | CONFIRMED |
| Track A + Track B SE121 target | CONFIRMED |
| JavaParser + SymbolSolver behind SemanticFrontend | CONFIRMED human choice (2026-09-02); empirical G2 acceptance pending; see ADR-001 |
| OpenRewrite | Independent comparator; possible future transformation technology outside SE121 |
| Storage-neutral canonical graph | CONFIRMED direction; detailed schema pending |
| Neo4j Community adapter | PROVISIONAL/experimental |
| Typed external policy representation | Approved direction; exact format pending |
| CLI-first product path | Approved direction |
| Spring Boot backend | PROVISIONAL |
| Cytoscape.js workbench visualization | PROVISIONAL |
| Complete visual architecture-intelligence workbench | CONFIRMED product outcome |
| Explainable architecture score plus separate analysis confidence | CONFIRMED direction; exact formula PROVISIONAL |
| Progressive evidence providers (including selective bytecode) | ASSESS/HOLD for SE121 implementation; extension boundary CONFIRMED |

## Document Responsibilities

- `AGENTS.md`: canonical project operating contract
- `docs/project-context.md`: durable identity, scope, authority, and constraints
- `docs/roadmap.md`: future milestones, gates, tracks, sequencing, and fallback
- `docs/current-state.md`: concise operational truth now
- `docs/decisions/`: rationale for consequential approved decisions
- `docs/architecture/`: current contracts, semantics, boundaries, schemas, and invariants
- `docs/architecture/evidence-acquisition.md`: provisional M3+ capability-gap, acquisition-attempt, and provider-conflict contract
- `docs/architecture/m4-spring-intelligence.md`: provisional M4 Spring domain model and versioned closed mechanism denominator
- `docs/architecture/product-outcome.md`: canonical product, metric, scoring, visualization, and acceptance contract
- `docs/research/`: methods and evidence, not project progress
- `roadmap_for_user.md` and `roadmap_for_user_vi.md`: explanatory human review companions; not the source of current status

## Related Documents

- [Current State](current-state.md)
- [Roadmap](roadmap.md)
- [Human Review Roadmap](../roadmap_for_user.md)
- [Architecture Overview](architecture/architecture.md)
- [Knowledge Graph](architecture/knowledge-graph.md)
- [M1 Contracts](architecture/m1-contracts.md)
- [Progressive Evidence Acquisition Contract](architecture/evidence-acquisition.md)
- [M4 Spring Intelligence and Closed Mechanism Taxonomy](architecture/m4-spring-intelligence.md)
- [Product Outcome and Workbench Contract](architecture/product-outcome.md)
- [ADR-002: Complete Visual Product and Explainable Assessment](decisions/ADR-002-product-outcome-and-explainable-assessment.md)
- [ADR-003: Progressive Evidence Acquisition](decisions/ADR-003-progressive-evidence-acquisition.md)
- [Research Questions](research/research-questions.md)
- [Architecture Decisions](decisions/)
