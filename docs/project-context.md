# Project Context

## Mission

Build a deterministic, evidence-first platform that understands repositories through a protected Java/Spring Boot foundation and language-specific semantic engines, reconstructs architecture-relevant semantics across declared configuration spaces, measures architecture structure and health, detects configuration-qualified architecture violations, explains every assessment through evidence and counterexample configurations, presents the results through a visual workbench, and uses that evidence to diagnose architecture problems, propose repairs and verify changes.

## Product Capability Philosophy

**CONFIRMED by the human owner on 2026-09-04 and re-affirmed with full universal scope expansion on 2026-09-18:** the platform adapts to understand each repository as deeply as safely and truthfully possible; it does not require repositories to conform to one analyzer assumption. There are **no artificial restrictions or "forbidden zones"**; the platform must not be shackled by narrow academic assumptions or artificial boundaries that declare common real-world code "unsupported". Prefer maximum capability, minimum assumptions, progressive evidence acquisition, explicit uncertainty, and zero silent omission.

The platform targets student/plain Java, legacy and modern apps, multi-build reactors, generated-code-heavy systems, enterprise monorepos, polyglot workspaces, binary/resource inputs and incomplete environments. **CONFIRMED human scope change (2026-10-17):** M8.4 includes actual semantic engines for individual non-Java languages, isolated target builds and comprehensive acquisition without fixed total-repository ceilings. This supersedes the inventory-only language clarification of 2026-09-23. [ADR-007](decisions/ADR-007-isolated-build-and-polyglot-readiness.md) and the [M8.4 contract](architecture/m8-repository-readiness.md) govern the expansion; implementation and empirical acceptance remain pending. The existing 98–99% aspiration is an empirical target on preregistered cohorts, not a worldwide measured fact. Syntax, platform API, framework version and mechanism support are verified separately; finite SAT cannot guarantee infinite-domain or millisecond worst-case solving.

**Autonomous Reasoning & Capability-Driven Specification Mandate:** Documentation declares required capabilities, coverage outcomes, boundaries and verifiable invariants. Implementers choose internal algorithms and structures within those contracts. Historical Universal v1 had two slices and [Universal v2](tasks/m4-universal-v2/README.md) had three tasks. The current [M8.4 plan](tasks/m8/04-comprehensive-repository-readiness.md) has exactly six steps with detailed internal checklists. A checklist is not an additional task; smaller task count cannot remove coverage or correctness controls.

Milestone scope sequences implementation delivery, but never serves as a ceiling or artificial constraint on platform capabilities. When justified and authorized, providers synthesize and acquire build-model, dependency, generated-source, bytecode, configuration, controlled sandbox/build, runtime, or other evidence. All such evidence remains versioned, attributable, bounded by permissions and safety, and reconciled without fabrication or silent replacement of conflicting observations.

**CONFIRMED bounded delivery acceptance (2026-10-20):** M8.4 Step 3 is accepted as Core MVP—offline Hyper-V build, wired launcher/pipeline, generated-source/artifact export and verified durable publication/replay. The owner removes advanced security campaigns from that step’s closure criteria. This does not narrow the platform’s comprehensive long-term capability mission, fabricate an unrestricted isolation certificate, or change the existing Steps 4–6. Host fallback and false semantic promotion remain prohibited.

**CONFIRMED bounded delivery acceptance (2026-10-29): M8.4 Step 5 / Bước 1 is CLOSED / CORE MVP**, accepted by the human for the delivered 18 adapters and scoped native journeys, explicit unsupported outcomes, replay, embedded/cross-provider paths and authentication mechanisms. Full A01–A12 qualification and production-method registration are withdrawn from this task's closure criteria, without treating them as passed or creating replacement tasks. Existing semantic gaps and unqualified trust markers remain truthful. This is delivery acceptance, not universal language/repository completeness or Step-5/milestone promotion. See the amendment to [ADR-007](decisions/ADR-007-isolated-build-and-polyglot-readiness.md).

## Delivery Context

**CONFIRMED human decision (2026-11-15):** deliver one [full-pipeline demo](roadmap.md#full-pipeline-demo) across the former SE121, SE122 and KLTN boundaries. Track A and Track B are the existing analysis, product and evolution foundations. The same demo adds graph-guided AI diagnosis, repair planning and patch/refactoring, isolated differential and targeted mutation verification, and local Verified PR/CI output. See [ADR-011](decisions/ADR-011-full-pipeline-demo.md) for the G7/M11 closure and the verification policy.

Prioritize finishing the integrated journey with a small, pinned setup. Retain the comprehensive-coverage mandate and all evidence, safety and correctness invariants. Verification is proportional to the behavior changed and the claim made; campaigns, publication packaging and enterprise qualification are separate work only when needed.

## Durable Architectural Constraints

1. Parser-specific objects do not cross the semantic frontend boundary.
2. Graph storage does not define the canonical semantic domain.
3. Backend and frontend use stable architecture query services rather than arbitrary storage queries.
4. Every important semantic relationship and violation preserves inspectable evidence and provenance.
5. Unresolved, ambiguous, partial, inferred, conditional, unsupported, and error outcomes remain explicit.
6. Maven modules, source roots, dependency scopes, parent POMs, dependency management, and BOMs are represented where analysis needs them.
7. Untrusted target lifecycles, scripts, plugins and executable model/index requests never run directly on the host. M8.4 authorizes isolated execution under validated sandbox, resource/egress and provenance contracts; static inspection and build success are not safety or semantic certificates.
8. Analysis identity is content-addressed from captured stable inputs. M8.4 requires CAS/checkpoints and evidence-cache reuse for scalable readiness; temporal incremental architecture evolution remains separately gated. Build reproducibility is measured separately from deterministic analysis replay.
9. Evidence acquisition is progressive and replaceable. Source analysis is the current baseline; unresolved or unsupported cases remain registered capability gaps that can justify additional build, generated-source, bytecode, configuration, sandbox or runtime providers at an approved gate.
10. The system remains a modular monolith unless measured requirements justify otherwise.
11. Architecture health and analyzer confidence are separate outputs; incomplete evidence can qualify or withhold an assessment but cannot improve it.
12. Metrics and scores are versioned, deterministic, scope-aware, and traceable to canonical inputs and limitations.
13. The UI consumes bounded query projections and never depends on rendering the complete raw graph by default.
14. Track A is not complete with a CLI or analyzer alone; the confirmed product outcome includes a usable visual workbench.
15. M3 models one exact build/classpath/platform context. M4+ may model a bounded Spring configuration space inside it; one realized configuration never becomes a universal claim without proof over the modeled feasible space.
16. Spring bean conditions that depend on definitions processed so far use versioned phase/order-aware transition semantics. Missing order, external configuration or dynamic behavior remains `UNKNOWN` or a typed capability gap.
17. Canonical conditional facts own truth regions and witnesses. Graph, policy, API and UI are projections/consumers and must not flatten mutually infeasible facts into one application world.
18. Total repository size/count/time are not comprehensive-mode admission ceilings. Bound worker/IPC/API resources and retain durable continuation when allocation is insufficient. Physical resources and inaccessible inputs remain explicit constraints, never silently omitted coverage.
19. Inventory, selected build-context coverage, semantic correctness and operational completion are separate. Active unmet obligations determine residual coverage while resolved gaps remain auditable. Language engines need independent obligation censuses, stable logical symbol identity and trust-qualified evidence.

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
| Single full-pipeline demo on Track A + Track B | CONFIRMED (2026-11-15); see ADR-011 |
| JavaParser + SymbolSolver behind SemanticFrontend | CONFIRMED human choice (2026-09-02); G2 subsequently recorded PASSED in current state; see ADR-001 |
| OpenRewrite | Existing independent comparator; a bounded transformation route is in demo scope, implementation pending |
| Storage-neutral canonical graph | CONFIRMED direction; detailed schema pending |
| Neo4j Community adapter | PROVISIONAL/experimental |
| Typed external policy representation | Approved direction; exact format pending |
| CLI-first product path | Approved direction |
| Spring Boot backend | PROVISIONAL |
| Cytoscape.js workbench visualization | PROVISIONAL |
| Complete visual architecture-intelligence workbench | CONFIRMED product outcome |
| Explainable architecture score plus separate analysis confidence | CONFIRMED direction; exact formula PROVISIONAL |
| Progressive evidence providers | Passive baseline and Universal v2 imports delivered within recorded scope; M8.4 isolated target execution authorized, design PROVISIONAL and implementation pending |
| Polyglot semantic engines and scalable readiness | CONFIRMED M8.4 scope expansion; native engine/CAS/VM adapter choices PROVISIONAL under ADR-007 |
| Staged conditional architecture semantics for M4+ | ACCEPTED direction and bounded M4B–D implementation; empirical G3 acceptance pending; see ADR-004/current state |
| Solver backend for configuration reasoning | Pure-Java DPLL and exhaustive backends implemented within recorded fragment; broader performance/coverage unmeasured |
| Deployment federation, cross-system assurance, and production verified evolution | Broader horizons beyond the local demo |

## Document Responsibilities

- `AGENTS.md`: canonical project operating contract
- `docs/project-context.md`: durable identity, scope, authority, and constraints
- `docs/roadmap.md`: future milestones, gates, tracks, sequencing, and fallback
- `docs/current-state.md`: concise operational truth now
- `docs/decisions/`: rationale for consequential approved decisions
- `docs/architecture/`: current contracts, semantics, boundaries, schemas, and invariants
- `docs/architecture/m3-workspace-build-model.md`: provisional M3 workspace, safe Maven model, exact classpath, and platform decoupling contract
- `docs/architecture/evidence-acquisition.md`: provisional M3+ capability-gap, acquisition-attempt, and provider-conflict contract
- `docs/architecture/m8-repository-readiness.md`: M8.4 acquisition, isolated execution, polyglot semantics, resource and completion contracts
- `docs/tasks/m8/04-comprehensive-repository-readiness.md`: six-step M8.4 implementation plan and exit criteria
- `docs/architecture/m4-spring-intelligence.md`: provisional M4 Spring domain model and versioned closed mechanism denominator
- `docs/architecture/m4-universal-v2.md`: planned adaptive intake/evidence/semantic integration contract; coverage catalog is the required-case companion
- `docs/tasks/m4-universal-v2/`: historical three-task execution plan and shared index
- `docs/architecture/m4-r0-semantics-gate.md`: reviewable R0 identity/fragment/version/order contract candidate spanning Spring 1.x XML through Boot 3.4.x
- `docs/architecture/conditional-architecture-semantics.md`: canonical M4+ configuration-space, staged registration, truth-region, witness, policy and evolution semantics
- `docs/architecture/product-outcome.md`: canonical product, metric, scoring, visualization, and acceptance contract
- `docs/research/`: methods and evidence, not project progress
- Former `roadmap_for_user.md` and `roadmap_for_user_vi.md` review snapshots are absent from the current checkout. Use `docs/roadmap.md` for direction and `docs/current-state.md` for operational status.

## Related Documents

- [Current State](current-state.md)
- [Roadmap](roadmap.md)
- [Architecture Overview](architecture/architecture.md)
- [Knowledge Graph](architecture/knowledge-graph.md)
- [M1 Contracts](architecture/m1-contracts.md)
- [M3 Workspace and Build-Model Contract](architecture/m3-workspace-build-model.md)
- [Progressive Evidence Acquisition Contract](architecture/evidence-acquisition.md)
- [M4 Spring Intelligence and Closed Mechanism Taxonomy](architecture/m4-spring-intelligence.md)
- [Conditional Architecture Semantics](architecture/conditional-architecture-semantics.md)
- [Product Outcome and Workbench Contract](architecture/product-outcome.md)
- [ADR-002: Complete Visual Product and Explainable Assessment](decisions/ADR-002-product-outcome-and-explainable-assessment.md)
- [ADR-003: Progressive Evidence Acquisition](decisions/ADR-003-progressive-evidence-acquisition.md)
- [ADR-004: Staged Conditional Architecture Semantics](decisions/ADR-004-staged-conditional-architecture-semantics.md)
- [ADR-005: M4 Universal v2](decisions/ADR-005-m4-universal-v2.md)
- [M4 Universal v2 task plan](tasks/m4-universal-v2/README.md)
- [Conditional Architecture Redirection Review](research/2026-09-09-conditional-architecture-redirection-review.md)
- [Research Questions](research/research-questions.md)
- [Architecture Decisions](decisions/)
