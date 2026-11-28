# Project Context

## Mission

Build a deterministic, evidence-first platform that understands repositories through a protected Java/Spring Boot foundation and language-specific semantic engines, reconstructs architecture-relevant semantics across declared configuration spaces, measures architecture structure and health, detects configuration-qualified architecture violations, explains every assessment through evidence and counterexample configurations, presents the results through a visual workbench, and uses that evidence to diagnose architecture problems, propose repairs and verify changes.

## Product Capability Philosophy

**CONFIRMED by the human owner on 2026-09-04 and re-affirmed with full universal scope expansion on 2026-09-18:** the platform adapts to understand each repository as deeply as safely and truthfully possible; it does not require repositories to conform to one analyzer assumption. There are **no artificial restrictions or "forbidden zones"**; the platform must not be shackled by narrow academic assumptions or artificial boundaries that declare common real-world code "unsupported". Prefer maximum capability, minimum assumptions, progressive evidence acquisition, explicit uncertainty, and zero silent omission.

The platform targets student/plain Java, legacy and modern apps, multi-build reactors, generated-code-heavy systems, enterprise monorepos, polyglot workspaces, binary/resource inputs and incomplete environments. **CONFIRMED human scope change (2026-10-17; M8.4 closed 2026-11-10):** M8.4 added semantic engines for individual non-Java languages, isolated target builds and comprehensive acquisition without fixed total-repository ceilings, superseding the earlier 2026-09-23 inventory-only boundary. [ADR-007](decisions/ADR-007-isolated-build-and-polyglot-readiness.md) and the [M8.4 contract](architecture/m8-repository-readiness.md) govern that foundation; M8.4 closed at the Final Architectural Recovery Baseline within recorded bounds, while Batches 3–11 and [SuperPlan v2](evo-sota-superplan-v2.md) continue closing remaining scale, soundness, and blind-generalization frontiers. The existing 98–99% aspiration is an empirical target on preregistered cohorts, not a worldwide measured fact. Syntax, platform API, framework version and mechanism support are verified separately; finite SAT cannot guarantee infinite-domain or millisecond worst-case solving.

**Autonomous Reasoning & Capability-Driven Specification Mandate:** Documentation declares required capabilities, coverage outcomes, boundaries and verifiable invariants. Implementers choose internal algorithms and structures within those contracts. Historical Universal v1 had two slices and [Universal v2](tasks/m4-universal-v2/README.md) had three tasks. The current [M8.4 plan](tasks/m8/04-comprehensive-repository-readiness.md) has exactly six steps with detailed internal checklists. A checklist is not an additional task; smaller task count cannot remove coverage or correctness controls.

Milestone scope sequences implementation delivery, but never serves as a ceiling or artificial constraint on platform capabilities. When justified and authorized, providers synthesize and acquire build-model, dependency, generated-source, bytecode, configuration, controlled sandbox/build, runtime, or other evidence. All such evidence remains versioned, attributable, bounded by permissions and safety, and reconciled without fabrication or silent replacement of conflicting observations.

**CONFIRMED bounded delivery acceptance (2026-10-20):** M8.4 Step 3 is accepted as Core MVP—offline Hyper-V build, wired launcher/pipeline, generated-source/artifact export and verified durable publication/replay. The owner removes advanced security campaigns from that step’s closure criteria. This does not narrow the platform’s comprehensive long-term capability mission, fabricate an unrestricted isolation certificate, or change the existing Steps 4–6. Host fallback and false semantic promotion remain prohibited.

**CONFIRMED bounded delivery acceptance (2026-10-29): M8.4 Step 5 / Bước 1 is CLOSED / CORE MVP**, accepted by the human for the delivered 18 adapters and scoped native journeys, explicit unsupported outcomes, replay, embedded/cross-provider paths and authentication mechanisms. Full A01–A12 qualification and production-method registration are withdrawn from this task's closure criteria, without treating them as passed or creating replacement tasks. Existing semantic gaps and unqualified trust markers remain truthful. This is delivery acceptance, not universal language/repository completeness or Step-5/milestone promotion. See the amendment to [ADR-007](decisions/ADR-007-isolated-build-and-polyglot-readiness.md).

## Delivery Context

**CONFIRMED Dual-Track Direction (2026-11-15 through 2026-11-28):** Evo serves two aligned goals on one deterministic Software Knowledge Graph core:

1. **Product Track (Online Web App + CLI + MCP):** Building on the completed [full-pipeline demo](roadmap.md#full-pipeline-demo) ([ADR-011](decisions/ADR-011-full-pipeline-demo.md)) and the 2026-11-16 [captured-repository repair](architecture/captured-repository-repair.md) direction, the **confirmed 2026-11-27 human override** focuses active product delivery on the **Online Web App Workbench** (`backend/target/evo-workbench.jar`, Docker `evo-workbench-online:latest`, Vanilla HTML/CSS/JS + Cytoscape + Lucian UI in `backend/src/main/resources/web/`, Streamable HTTP MCP `2025-11-25`). The `cli` module stays preserved for compatibility and `E0` benchmark parity without new CLI feature scope unless explicitly requested.
2. **Scientific Research Track (CORE A* / Q1):** Governed by [SuperPlan v2](evo-sota-superplan-v2.md), the curated 19-paper [SOTA Paper Library](paper/README.md) ([catalog.json](paper/catalog.json)), [19-Paper SOTA Deep Synthesis](research/sota-19-paper-deep-synthesis.md), and [Comparative Evaluation Report v7](evo-comparative-evaluation-report.md). Enforces strict anti-overfitting (`I5`), blind holdouts, 3-way timing decomposition, and isolated execution (`I1`) while keeping routine Product SWE verification fast (`T0–T3` in [docs/testing.md](testing.md)).

The comprehensive-coverage mandate and evidence, safety, quarantine (`canonicalLeakage = 0`), and correctness invariants remain in force across both tracks.

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
| Java 21 compile baseline & Maven 3.9.16 monorepo (8 reactor modules) | CONFIRMED |
| Dual-track Online Web App product + CORE A*/Q1 research roadmap | CONFIRMED (2026-11-27 / 2026-11-28); see [SuperPlan v2](evo-sota-superplan-v2.md) & [ADR-011](decisions/ADR-011-full-pipeline-demo.md) |
| JavaParser + SymbolSolver (`3.28.2`) behind `SemanticFrontend` | CONFIRMED human choice; `G2 PASSED` (`2026-09-10`); see [ADR-001](decisions/ADR-001-parser-technology.md) |
| OpenRewrite (`rewrite-java:8.87.7` in `backend/pom.xml`) | CONFIRMED; deterministic repair recipes implemented; compound refactoring expansion in `WP4.3` |
| Storage-neutral canonical SKG + SQLite WAL / CAS / partitioned persistence | CONFIRMED; `G4 PASSED` (`2026-10-15`); chunked symbolic artifact storage in `WP0.2` (`B3`) |
| Neo4j Community adapter | PROVISIONAL / optional adapter |
| Typed policy compilation, Evidence Bundles & explainable Score v2 | CONFIRMED; `G5 PASSED` (`2026-10-15`); score parameters frozen under [ADR-002](decisions/ADR-002-product-outcome-and-explainable-assessment.md) |
| Online Web App primary product + preserved CLI compatibility | CONFIRMED (`2026-11-27` override; supersedes earlier CLI-first phrasing): standalone `evo-workbench.jar`, Docker `evo-workbench-online:latest`, Streamable HTTP MCP `2025-11-25` |
| Backend HTTP & Web Workbench UI stack | CONFIRMED lightweight JDK HTTP server (`WorkbenchApiMain`) + Vanilla HTML/CSS/JS + Cytoscape.js + Lucian (`backend/src/main/resources/web/`); no React/Astryx/Spring Boot web server currently used |
| Progressive evidence providers & isolated target execution | M8.4 Core MVP isolated workers (`Docker`/`QEMU`/`Hyper-V`) delivered within recorded bounds under [ADR-007](decisions/ADR-007-isolated-build-and-polyglot-readiness.md); unrestricted executor certification remains distinct |
| Polyglot semantic engines & SCIP interoperability | Tree-sitter `0.26.6` (`16` native grammars), JAX-RS/Servlet, SCIP protobuf (`81k occ/s`), and passive cloud-native topology implemented; large-scale streaming/blind holdout in `WP0.2`–`WP1.1` |
| Staged conditional Spring semantics & solver backend | `G3 PASSED` (`2026-10-14`); passive ASM starter scanner (`BytecodeAutoConfigScanner`), directed COI slicer, pure-Java CDCL (`IncrementalCdclReasoner`), and 4-valued Kleene cubes implemented; `WP0.1` closes remaining `B5` fixes & real `ApplicationContextRunner` oracle (`E5-v3`) |
| Quarantined AI/advisory overlays (`AdvisoryOverlayService`) | CONFIRMED (`canonicalLeakage = 0`, `llm-redact:v2` `15/15` secrets redacted); graph-grounded `TacitMemoryStore` planned in Phase 3 |
| Deployment federation & cross-system production assurance | Broader horizons beyond current demonstrated scope |

## Document Responsibilities

- `AGENTS.md`: lean canonical project operating contract (`< 12 KB`, zero truncation)
- `docs/project-context.md`: durable identity, scope, authority, and architectural constraints
- `docs/roadmap.md`: dual-track direction, historical milestones/gates, and publication trajectory
- `docs/current-state.md`: concise live operational truth (`<= 8 KB`); full historical log archived at `docs/archive/current-state-history-2026-11.md`
- `docs/testing.md`: empirically measured `T0–T4` verification matrix, PowerShell 5.1 Maven/Node discipline, and native campaign runbooks
- `docs/evo-sota-superplan-v2.md`: canonical CORE A*/Q1 scientific research & implementation roadmap (`WP0.1–WP4.4`)
- `docs/evo-comparative-evaluation-report.md`: empirical comparative benchmark report (`v7`)
- `docs/paper/README.md` & `docs/paper/catalog.json`: Git-tracked index for the 19-paper SOTA library (raw PDFs are local/gitignored)
- `docs/decisions/`: rationale for consequential approved decisions
- `docs/architecture/`: current contracts, semantics, boundaries, schemas, and invariants
- `docs/research/`: empirical evaluation reports, SOTA synthesis, and methodology (no progress diary)

## Related Documents

- [Current State](current-state.md) & [Historical State Archive (2026-11)](archive/current-state-history-2026-11.md)
- [Roadmap](roadmap.md) & [Testing & Fast Verification Protocol](testing.md)
- [SuperPlan v2 (CORE A*/Q1)](evo-sota-superplan-v2.md) & [19-Paper SOTA Deep Synthesis](research/sota-19-paper-deep-synthesis.md)
- [SOTA Paper Library](paper/README.md) & [Comparative Evaluation Report v7](evo-comparative-evaluation-report.md)
- [Architecture Overview](architecture/architecture.md) & [Knowledge Graph](architecture/knowledge-graph.md)
- [M1 Contracts](architecture/m1-contracts.md), [M3 Build Model](architecture/m3-workspace-build-model.md) & [Evidence Acquisition](architecture/evidence-acquisition.md)
- [Conditional Architecture Semantics](architecture/conditional-architecture-semantics.md) & [M8.4 Readiness](architecture/m8-repository-readiness.md)
- [Captured Repository Repair](architecture/captured-repository-repair.md) & [Product Outcome](architecture/product-outcome.md)
- [ADR-001](decisions/ADR-001-parser-technology.md) · [ADR-002](decisions/ADR-002-product-outcome-and-explainable-assessment.md) · [ADR-003](decisions/ADR-003-progressive-evidence-acquisition.md) · [ADR-004](decisions/ADR-004-staged-conditional-architecture-semantics.md) · [ADR-005](decisions/ADR-005-m4-universal-v2.md) · [ADR-007](decisions/ADR-007-isolated-build-and-polyglot-readiness.md) · [ADR-011](decisions/ADR-011-full-pipeline-demo.md)
