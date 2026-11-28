# Technical Roadmap

## Status and Intent

The [full-pipeline demo](#full-pipeline-demo) is complete in its pinned scope and preserved as a reproducible compatibility check. Evo operates on an explicit **Dual-Track Direction (2026-11-28)** built on the protected Track A analysis and Track B snapshot-evolution foundations:

- **Product Track (Online Web App + CLI + MCP):** **CONFIRMED human direction (2026-11-16 & 2026-11-27):** prioritize the **Online Web App Workbench** (`backend/target/evo-workbench.jar`, Docker `evo-workbench-online:latest`, Vanilla HTML/CSS/JS + Cytoscape + Lucian UI in `backend/src/main/resources/web/`, Streamable HTTP MCP `2025-11-25`) and extend [captured-repository repair](architecture/captured-repository-repair.md) with usable recipes, optional model-authored edits, compiler syntax checks, and isolated sandbox verification. Keep `cli` preserved for compatibility and `E0` benchmark artifact parity without adding new CLI features unless explicitly requested.
- **Scientific Research Track (CORE A*/Q1):** Governed by [SuperPlan v2](evo-sota-superplan-v2.md) (`Phase 0` `WP0.1–WP0.6` -> `Phases 1–4` `WP1.1–WP4.4`), backed by the curated 19-paper [SOTA Paper Library](paper/README.md) ([catalog.json](paper/catalog.json)), [19-Paper SOTA Deep Synthesis](research/sota-19-paper-deep-synthesis.md), and [Comparative Evaluation Report v7](evo-comparative-evaluation-report.md).

[Current state](current-state.md) records live verified metrics, active blockers (`B1–B6`), and the exact next task. Support for additional languages and isolated build, test, and finding verification must be established separately for their actual toolchains and contexts; preserve explicit capability gaps rather than inflating demonstrated claims.

## Full-Pipeline Demo

Use one pinned, safe local Java/Spring fixture with compatible snapshots, a traceable architecture finding, and a small repair. Reuse the existing engine and Workbench surfaces. Deliver these connected slices in the same demo:

1. **Analyze and compare.** Ingest the fixture through the real acquisition, build-context, semantic, Spring, graph, metric and policy pipeline. In Workbench, navigate from inventory, score and snapshot comparison to one conditional finding, witness, impact and source evidence. Keep gaps visible and coverage separate from the score.
2. **Diagnose and propose.** From that finding, retrieve bounded, snapshot-scoped graph and evidence context for AI diagnosis (RAG). Show a cited explanation, affected scope, and a reviewable repair plan and patch. Support an applicable OpenRewrite recipe for the demo refactoring. Keep a replaceable model adapter and a labeled replay mode for repeatable local checks; prove the live AI path separately before claiming it. Missing or mismatched evidence must withhold a supported diagnosis.
3. **Verify and hand off.** Stage the proposal in an isolated disposable workspace and run the smallest relevant baseline/candidate build and tests. Reanalyze the candidate, compare compatible results against the baseline, and confirm the intended finding transition. Include one targeted negative or mutation control that shows verification can reject a bad candidate. Present the patch, affected region and actual verdict in Workbench and a local Verified PR preview. Expose the same verifier through a runnable CI entry point with an exit status and a machine-readable check result. Missing evidence or execution is explicitly unverified. Remote publication and automatic merge are outside the local demo.

**Done:** One documented local run uses the real analyzer, an authorized live model on the demo fixture and the isolated verifier. It links the starting snapshot and finding to the exact patch, candidate snapshot, checks and PR/CI output. A reviewer can repeat the journey on the pinned input; a local replay is useful for iteration and is labeled separately from live model output, unknown or partial outcomes, and unrun checks. The [comprehensive coverage mandate](architecture/comprehensive-repository-coverage-problems.md) still applies to the platform. See [lean verification](testing.md#lean-verification-for-the-demo).

## Protected Principles

1. Do not weaken Track A to reach Track B.
2. Do not begin Track B until snapshot identity, semantic correctness, graph invariants, and policy evidence pass.
3. Coverage and semantic correctness are separate metrics.
4. Unknown/ambiguous/conditional semantics remain explicit.
5. Canonical semantic and query contracts remain independent of parser and storage adapters.
6. Multi-module workspace/build-model intelligence is a core capability.
7. Analysis identity is content-addressed. M8.4 requires CAS/checkpoint/evidence caching to remove demonstrated scale constraints; broader temporal incremental analysis stays gated.
8. Source-only analysis is a baseline, not a ceiling. Unresolved/unsupported cases are registered by reason and may trigger progressively stronger evidence providers; provider implementation remains gate- and evidence-driven.
9. Evaluation runs throughout implementation rather than at the end.
10. AI proposals remain separate from deterministic evidence, verdicts and execution authority.
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
- Online Web App architecture workbench (`backend/src/main/resources/web/`), HTTP/MCP APIs, and preserved CLI/export workflow
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

### Broader Capability Horizons

These capabilities can grow beyond the demonstrated scope as evidence and practical needs justify them.

- **Horizon D — Context federation:** Maven/Gradle/POM-less build-context families, generated/deployment/AOT/runtime evidence and configuration descriptors from container/orchestration systems.
- **Horizon E — System-of-systems assurance:** cross-repository API, event, schema, database-migration and ownership architecture with enterprise CI/PR/IDE integration.
- **Horizon F — Production verified evolution:** broader repair coverage, authenticated provider integrations and production change assurance beyond the local demo.

## Milestones

Historical delivery roadmap through M11. Its gate and campaign requirements describe the original scope and do not add work to the active demo.

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

**Status: COMPLETE (Frontend Implementation Verified).** Implemented, verified with 98 root tests at its checkpoint, and confirmed on whole-project multi-file analysis (43 files, 1,488 occurrences). All 18 relationship families are implemented. The combined frontend/build-model Gate G2 is subsequently recorded PASSED in current state and the 2026-09-10 adjudication record; historical checkpoints retain their original scope.

Deliver the JavaParser adapter and architecture-relevant relationship set: declarations, inheritance, implementations, permits, type uses, calls, constructor calls, field access, method references, parameters, returns, fields, throws, annotations, generics, and relevant modern Java constructs.

Ground truth distinguishes attempted, correct, incorrect, unresolved, ambiguous, omitted, unsupported, and error outcomes. JavaParser is the human-approved primary frontend (ADR-001); this choice does not pass G2 or remove the validation/replacement gates.

### M3 - Multi-Module Workspace and Build-Model Intelligence

**Status: M3.1–M3.8 DELIVERED; G2 RECORDED PASSED.** See the [recorded adjudication](reproducibility/g2-semantic-adjudication-record-2026-09-10.md). The historical full reactor checkpoint passes 221 tests. M3.8 adds neutral acquisition contracts, `dependency.artifact-cache:m3.8`, and the bounded `dependency.maven-fixpoint:3.9.16-m3.8` resolver. The official Spring PetClinic checkpoint ran twice from separate empty caches: each run accounted for 629 requested files, including 266 acquired JARs and 363 acquired/verified POM descriptors recorded as `SKIPPED_POM` because they are not frontend binaries; consumed 171,800,181 bytes; assembled both source sets; analyzed all 50 Java files; and emitted 4,250 frontend observations. The byte-identical canonical outputs have digest `sha256:8806702323d9bc307aef7795bee0b10bdf15e43da65353f75015517b46eb2796`. Those execution results closed the M3 implementation slice; the later G2 decision is recorded separately and is not inferred merely from deterministic output.

Deliver safe understanding of Maven parent POMs, modules, source/generated-source roots where safely discoverable, dependency management, BOMs, dependency scopes, module dependencies, and exact classpath manifests. Build discovery is a provider family rather than a requirement that repositories conform to Maven: POM-less, Gradle and later build shapes use neutral source-plan/build adapters as evidence justifies them. The formal specification is maintained in the provisional [M3 Workspace and Build-Model Intelligence Contract](architecture/m3-workspace-build-model.md). Missing generated sources or effective-model inputs remain explicit acquisition gaps rather than disappearing from coverage.

M3 also introduces the first normalized capability-gap/acquisition contract over M2 observations and build-model coverage, following the provisional [Progressive Evidence Acquisition and Capability-Gap Contract](architecture/evidence-acquisition.md). This includes provider identity, stable reason/mechanism catalogs, typed evidence requirements, attempt provenance and explicit provider conflicts; implementation must remain versioned and tested.

Decouple analyzer execution from analyzed repository targets (**analyzer-runtime != analyzed-platform**):
- **Separate source language level from platform symbol view:** Discover source language level (syntax support) and target platform release (standard library APIs) independently from `maven.compiler.source`, `maven.compiler.target`, `maven.compiler.release`, `<java.version>`, or toolchains. Supporting a target platform symbol view for Java N does not imply parser syntax support for all Java N language features; syntax beyond verified parser capabilities and preview features remain explicit unsupported/degraded outcomes.
- **Toolchain / `JAVA_HOME` / Platform symbol acquisition:** Support configured target JDKs via `JAVA_HOME`, toolchains, or platform symbol views (`rt.jar` for Java 8, `jmods`/`ct.sym` for Java 9+) rather than restricting resolution exclusively to the analyzer's host JDK 21 image.
- **Platform provenance:** Record the exact analyzed platform version, vendor, source release, and symbol-view hash in the analysis manifest and provenance.
- **Source-encoding evidence:** Resolve charset per module/source set from ordered authoritative evidence such as a supported BOM, build declaration (for example `<project.build.sourceEncoding>`), supported repository configuration, or explicit analysis configuration. If none exists, use only a declared analysis policy such as assumed UTF-8 or withhold/degrade the input; never inherit an ambient host default or present the assumption as repository fact. UTF-8 with BOM must be handled deterministically without corrupting source coordinates; byte sequences invalid under the selected charset or declared/byte mismatches yield explicit input-error/degraded outcomes rather than speculative charset guessing; original raw bytes and SHA-256 digests are strictly preserved alongside charset provenance.

M3's delivered providers do not execute target lifecycle plugins; its initial Gradle path accepts an explicit classpath. M8.4 now authorizes a separate isolated build/model provider under ADR-007. Direct host execution remains prohibited; no M3 evidence is retroactively reclassified.

Exit gate G2: pinned multi-module fixtures and a real repository reproduce module/source/classpath models without hidden dependency supersets, decouple the analyzer runtime from analyzed repository target platforms, account for registered input/acquisition gaps and provider failures, and pass an early representative real-repository semantic coverage checkpoint (measuring attempted, resolved, unresolved, ambiguous, unsupported, omitted/unmapped, error, and adjudicated incorrect outcomes across registered categories, with reason-level breakdowns for degraded facts) before treating the frontend foundation as mature.

### M4 - Spring Semantic Intelligence

M4 changes from single-context candidate enrichment to **bounded conditional architecture semantics** inside one exact M3 build context. [ADR-004](decisions/ADR-004-staged-conditional-architecture-semantics.md) and the [canonical semantics contract](architecture/conditional-architecture-semantics.md) govern the direction. M4A.1–M4A.2 provide the production evidence-only mechanism inventory, declaration graph and gap-normalization foundation; M4B–M4C provide bounded per-assignment inference; and M4D now provides finite-space truth regions and revalidated witnesses. M4E validation and G3 remain pending.

#### M4-R0 — Research and Semantics Gate

**2026-09-11: PASSED (Human-Accepted).** The human supervisor reviewed and approved the R0 contract candidate ([architecture/m4-r0-semantics-gate.md](architecture/m4-r0-semantics-gate.md)), research matrix ([research/2026-09-11-m4-r0-spring-semantics.md](research/2026-09-11-m4-r0-spring-semantics.md)), 29-family catalog (`spring-mechanisms:v2`), bounded runtime/formal oracle evidence and preregistered protocol. Production implementation is authorized starting with slice M4A.1.

Accepted M4-R0 prerequisites (historical checklist):

- approve producer/candidate/injection/condition/configuration-space identities;
- freeze `spring-mechanisms:v2` and the first supported Spring Boot/Framework version matrix;
- define repository and user-supplied configuration envelopes, precedence and finite-domain abstraction;
- adjudicate profile/property/classpath and ordered `OnBean`/`OnMissingBean`/single-candidate fixtures against official behavior;
- define UNKNOWN, branching and resource-limit behavior;
- implement an exhaustive small-space oracle specification and preregister solver/baseline/corpus protocols.

#### M4A — Mechanism Ground Truth

**2026-09-13: M4A.1–M4A.2 DELIVERED; M4B NEXT.** The two slices passively inventory the accepted 29-family catalog from exact frontend/classpath/resource evidence, close every raw row as a marker or typed semantic obligation, retain exact annotation-declaration/meta-edge/cycle evidence, and classify remaining callable/type/declaration mechanism candidates. They deliberately perform no activation, registration-effect, lifecycle or binding inference.

Detect and account for direct/composed stereotypes, component scanning, `@Bean`, constructor/field/method/`@Bean` parameter injection, JSR-330/`@Resource`, collection/provider forms, qualifiers, primary/fallback, framework entry points, auto-configuration, factories, Spring Data, generated members, XML, programmatic registration, lookup, SpEL and runtime-dynamic mechanisms. Every registered row is `SUPPORTED`, `CONDITIONAL`, `DYNAMIC`, `UNSUPPORTED` or `OUT_OF_SCOPE` with a closed denominator and evidence need.

Lombok and Spring Data are first-class coverage obligations. Universal v1 supplies bounded generated-member and repository-candidate evidence; Universal v2 closes symbol/descriptor/registration integration. Synthesis requires exact version/configuration/eligibility evidence. An interface or generated constructor alone does not prove an active bean or selected binding.

#### M4B — Configuration Space and Condition IR

Deliver storage- and solver-neutral condition expressions; separate build constants, exogenous profile/property inputs, endogenous bean-state predicates and opaque/dynamic conditions; distinguish one existing realized `ConfigurationIdentity` from a new content-addressed `ConfigurationSpaceIdentity`.

#### M4C — Phase/Order-Aware Spring Resolution

Deliver versioned configuration-parse and bean-registration transitions, ordered/partially ordered auto-configuration handling, candidate activation, type/generic matching and qualifier/priority binding. Missing order or opaque behavior branches only within explicit limits and otherwise remains `UNKNOWN`. Do not use an unordered generic fixpoint as Spring truth.

#### M4D — Truth Regions and Witnesses

**Status: DELIVERED for the normalized M4C fragment (2026-09-18); M4E/G3 evidence pending.** Classify facts and bindings over the feasible modeled space as `MUST`, `MAY`, `NEVER` or `UNKNOWN`; produce deterministic, minimized and revalidated witnesses/counter-witnesses; preserve unresolved regions and operational failures separately. See the [M4D contract](architecture/m4d-truth-regions-witnesses.md).

#### M4-UNIVERSAL v1 — delivered provider foundation

The human's 2026-09-18 direction established broad Java/Spring coverage through two implementation slices: [passive source/build/config ingestion](architecture/m4u1-passive-ingestion.md) and [Spring evidence plus symbolic SAT](architecture/m4u2-universal-spring.md). Their implementation contracts own actual support and remaining obligations. Historical 98–99% coverage ambitions are targets, not measured worldwide coverage. Finite SAT does not promise arbitrary infinite-domain or constant-time solving.

#### M4-UNIVERSAL-V2 — Adaptive Repository Understanding

**CONFIRMED owner request (2026-09-23, compact plan finalized 2026-09-26):** add one new M4 Universal v2 umbrella task before M4E, with deep Java/Spring semantics, polyglot inventory/boundaries, and fewer execution tasks. [ADR-005](decisions/ADR-005-m4-universal-v2.md) records sequencing; the [architecture contract](architecture/m4-universal-v2.md), [coverage catalog](architecture/m4-universal-v2-coverage.md) and [evaluation protocol](research/m4-universal-v2-evaluation.md) define the provisional implementation plan.

Exactly three task files:

1. [V2.1 — Adaptive intake and evidence closure](tasks/m4-universal-v2/01-adaptive-intake-and-evidence.md): resilient inventory, scoped build/source plans, exact dependencies/platforms, generated/binary/library evidence imports and an active permitted-evidence coordinator.
2. [V2.2 — Java/Spring semantic closure](tasks/m4-universal-v2/02-java-spring-semantic-closure.md): Java/generated symbol resolution, config closure, automatic source-to-M4C descriptors, version/mechanism packs and dependency-local conditional reasoning.
3. [V2.3 — Integrated architecture and acceptance](tasks/m4-universal-v2/03-integration-and-acceptance.md): input-to-report integration, root-cause uncertainty explanations, recovery/replay controls and one registered acceptance evidence campaign shared with M4E.

The first two are implementation tasks; the third integrates and verifies. Internal checklists do not create additional tasks. Each supported capability needs positive and negative end-to-end evidence; returning a gap for every input satisfies accounting only. Architecture usefulness, correct resolution, false certainty, setup burden and residual uncertainty are measured separately. This historical M4 scope covered Java/Spring without extra-language engines or target execution. M8.4 explicitly expands both under ADR-007; all-context universal semantics still requires evidence rather than inference from one build.

Exit: integrated evidence closure and semantic journeys pass their controls; all registered categories/failures remain in denominators; applicable permitted providers have been attempted; residuals have exact causes/affected outputs; corpus claims use preregistered inputs/modes/labels and honest comparison. The 98–99% aspiration is evaluated on declared cohorts. V2 is not implemented by this plan and does not pass G3; M4E adjudicates its evidence.
#### M4E — Validation and G3

Run adjudicated microfixtures, interaction fixtures and representative repositories across pinned framework versions. Compare against Java-static, realized-current-config, flat-condition and applicable ArchUnit/Spring Modulith/Jasmine baselines. Include failures in the denominator and report false-certainty plus false-unconditional-warning rates.

Static non-resolution is not a permanent verdict. Later configuration, generated-source, bytecode, isolated build or runtime providers may add evidence without erasing the original observation. `OUT_OF_SCOPE` is a catalog boundary, not a permanent platform prohibition.

Exit gate G3: `spring-mechanisms:v2` is fully accounted for; the approved semantic fragment has reviewed truth-region/binding evidence, valid deterministic witnesses, no silent universal promotion, explicit resource/version boundaries and bounded real-repository results. Do not claim complete Spring runtime-container equivalence.

### M5 - Canonical Graph, Metrics, and Architecture Query Layer

**Status: DELIVERED; Gate G4 PASSED (2026-10-15).** See the [Gate G4 Adjudication Record](reproducibility/g4-adjudication-record-2026-10-15.md). Bounded implementation passes 165 tests across 22 suites with zero failures/errors/skips. The 19-metric baseline in `MetricCatalog` and product performance budgets are approved and frozen under ADR-002.

Deliver deterministic graph construction as a projection of canonical Java and conditional Spring facts, stable entity/occurrence/relationship/region identities, graph invariants, storage port, structural metric computation, focused configuration-aware projections, and query services for summaries, inventory, metrics, symbol lookup, dependencies, dependents, paths, cycles, evidence, impact, activation explanation and snapshot comparison preparation.

Required projections include one realized configuration, `MUST` facts, condition-labeled `MAY` facts, bounded exploratory union and unknown/gap overlays. A union projection cannot be treated as one runtime application world. Graph storage does not own condition solving or policy quantification.

Every metric exposes a stable ID/version, scope, value/unit, formula semantics, inputs, analysis/configuration identity, status, denominator where applicable, and uncertainty. Hand-computed micrographs and golden fixtures verify structural metrics.

Neo4j is evaluated only as an adapter.

Exit gate G4: idempotency, uniqueness, provenance, persistence round-trip, metric correctness, bounded projection and query contract tests pass across realized, `MUST`, `MAY` and unknown projections, including controls that reject infeasible cross-configuration paths.

### M6 - Policy, Evidence, and Explainable Architecture Assessment

**Status: DELIVERED; Gate G5 PASSED (2026-10-15).** See the [Gate G5 Adjudication Record](reproducibility/g5-adjudication-record-2026-10-15.md). Bounded implementation passes 250 tests across 30 suites with zero failures/errors/skips. The baseline score model parameters (5 dimensions, weights 25/20/20/25/10, caps 100, severity penalties 2/10/25, structural thresholds/saturation 0/1) are approved and frozen under ADR-002.

Each policy result composes rule predicates with fact truth regions. Findings distinguish `MUST`, `MAY` and `UNKNOWN`; `NEVER` is a clean result within the modeled space rather than a warning. Each finding carries rule/version, source/target identities, supporting relationships, source spans, semantic status, graph path, configuration-space/affected-region identity, witness/counter-witness where applicable, evidence gaps and limitations. Severity and configuration quantifier remain orthogonal.

Deliver policy/violation metrics and a versioned explainable architecture health score with dimension breakdown, raw inputs, contributions, penalties, caps and withheld reasons. Scoring must define how conditional regions contribute and must not treat unknown configurations as healthy. Architecture health remains separate from analysis confidence. Exact weights and thresholds require labeled examples, sensitivity analysis and human approval before being treated as confirmed.

Exit gate G5: positive, negative, ambiguous, conditional and controlled architecture-mutation cases pass; witness replay, infeasible-path rejection, score golden cases, expected monotonicity properties, missing-evidence behavior, sensitivity analysis and formula-version tests pass without hidden false certainty.

### M7 - Impact, CLI, and Interoperability

Deliver bounded direct/transitive/policy impact queries, configuration-space selection, witness replay inputs, a complete CLI workflow and canonical exports for inventory, metrics, score explanations, conditional findings, provenance and limitations through JSON, SARIF and GraphML where applicable. Impact remains potential structural/configuration-qualified impact, not guaranteed runtime behavior.

Exit gate: the entire Track A analysis/evidence workflow runs locally without backend or Neo4j.

### M8 - Backend API and Complete Architecture Workbench

**Status: CLOSED / FINAL ARCHITECTURAL RECOVERY BASELINE (Human-Accepted 2026-11-10).** M8.1–M8.4 and the Master Upgrade Plan are complete within their recorded bounds; the [closure report](research/M8.4-Step6-Real-Repository-Scale-Evaluation-Report.md) preserves historical evidence. The current passive completion policy is the human-approved 95% with guards and truthful evidence tiers; it does not recalculate historical runs. The product acceptance criteria below informed the M9 evaluation and M10 human release review.

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

### M8.4 — Comprehensive Repository Readiness Upgrade (Mandatory Pre-M9 Task)

**CONFIRMED human delivery adjustment (2026-10-29):** Step 5 / Bước 1 is **CLOSED / CORE MVP** for the demonstrated 18-adapter/native-capture, explicit-gap, replay, embedded/cross-provider and authentication paths. Full A01–A12 qualification and production-method registration are withdrawn from that task's exit, without being certified or turned into replacement tasks. ADR-007 owns the acceptance amendment. Step 5 continues with its existing Bước 2–3; M8.4 and later gates are unchanged.

**Status: CLOSED / FINAL ARCHITECTURAL RECOVERY BASELINE (Human-Accepted 2026-11-10).** The post-M8 Master Upgrade Plan is complete; [current state](current-state.md) owns its bounded evidence and remaining capability limits. M9 does not reopen withdrawn Step-3/Step-5 qualification campaigns.

This task is established pursuant to the binding **Human Mandate on Comprehensive Coverage** ([docs/architecture/comprehensive-repository-coverage-problems.md](architecture/comprehensive-repository-coverage-problems.md)).

- **Requirement:** Every repository, regardless of scale, complexity, or multi-module microservice architecture, must be fully analyzable and measurable without omitting any component, with hard ceilings and acquisition timeouts addressed, generated code/stubs (such as gRPC, Protobuf, and compiler-generated artifacts) accounted for, and `FAILED` and `PARTIAL` outcomes minimized to the utmost extent.
- **Sequencing:** This task is a mandatory prerequisite that must be executed and verified immediately following M8.3, before Milestone M9 begins.
- **Confirmed expansion:** target builds run in validated isolated executors, never directly on host; non-Java semantic engines are M8.4 deliverables, superseding inventory-only scope.
- **Detailed execution:** [six-step plan](tasks/m8/04-comprehensive-repository-readiness.md): (1) streaming full acquisition/CAS/journal, (2) multi-root/module/context readiness, (3) isolated native build/index execution, (4) generated-code and Java/Spring evidence closure, (5) language semantic engines and interop, (6) partitioned graph/product integration and registered acceptance. [Contract](architecture/m8-repository-readiness.md) and [research](research/2026-10-17-m8-readiness-design.md) define choices and exit controls.
- **Claims:** complete inventory is distinct from selected-context semantic coverage and correctness. Physical resources stay finite; waits/checkpoints replace global repo rejection. The [current state](current-state.md#exact-next-task) identifies the next authorized slice.

**CONFIRMED human delivery adjustment (2026-10-20):** M8.4 Step 3 closes at Core MVP scope: a registered Hyper-V launcher feeds the offline Maven/generated-code build/export/CAS pipeline with durable replay. Advanced security campaigns are removed from that step’s exit criteria and are not new tasks. This does not certify unrestricted sandbox execution or generated semantic correctness. Existing Steps 4–6, the broader coverage mission and M9/M10 gates remain unchanged. ADR-007 and current state own the exact decision/evidence.

### M9 - Multi-Repository Evaluation and Hardening

Deliver curated condition/registration/binding microfixtures, interaction fixtures, PetClinic, medium, multi-module and larger repositories; controlled partial-classpath/configuration experiments; architecture mutations with expected affected regions; metric golden cases; score sensitivity and missing-evidence experiments; graph/query/UI scale scenarios; accessibility/usability review of primary workflows; robustness matrix; repeated determinism/performance runs; and immutable raw results. Corpus inclusion, labels, baselines and primary metrics are registered before result interpretation.

M9 Track A evaluation acceptance: independent review supports the exact bounded Track A claims, metric and score correctness boundaries, registered product performance/usability criteria, and a clean reproducible product workflow. The historical G6 M7 release-preparation adjudication stays PASSED within its original scope; this M9 acceptance record supplements it without renumbering gates or granting M10 approval. See the [M9 protocol](tasks/m9/PROTOCOL.md).

### M10 - Track A Release Gate

**Status: PASSED / CLOSED BY HUMAN APPROVAL (2026-11-14).** Human supervisor signed off on Track A product release based on the M9.5 dossier ([01-release-sign-off.md](tasks/m10/01-release-sign-off.md)), ratifying the frozen candidate (`evo-candidate.jar`), 17-claim evaluation baseline, and 4 mandatory release reservations. Track A is formally released. Milestone M11 (Track B) is authorized to begin.

Freeze Track A contracts and claim language. Resolve all blocking semantic, Spring, graph, metric, score, policy, evidence, robustness, accessibility, and product defects before Track B. Track A cannot pass with an analyzer/CLI-only result.

Human approval is mandatory to continue.

### M11 - Track B Architecture Evolution

**CLOSED / HUMAN ACCEPTED (2026-11-15).** The [comparison contract](architecture/m11-compatible-snapshot-evolution.md) defines the delivered boundaries. The [six-task plan](tasks/m11/README.md) and [registered protocol](tasks/m11/PROTOCOL.md) are historical. G7 is closed by human decision and is not certified passed; the review and failures remain in [M11.6](tasks/m11/06-execution.md).

### M12 - Technical Integration and Final Reproducibility

Local integration and reproduction are part of the [full-pipeline demo](#full-pipeline-demo). The separate M12 closeout and G8 sign-off are retired under [ADR-011](decisions/ADR-011-full-pipeline-demo.md).

## Historical Milestone DAG

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
        -> Universal v1 (two delivered slices)
        -> Universal v2: V2.1 -> V2.2 -> V2.3
        -> M4E shared-evidence adjudication -> G3
  M4 + M5 -> M6 conditional policy/evidence/assessment
  M6 -> M7 Impact/CLI/export
  M7 -> M8 Backend/complete workbench (M8.1-M8.3)
  M8.3 -> M8.4 Comprehensive repository readiness upgrade
  M8.4 + M2 + M3 + M4 + M6 -> M9 External evaluation
  M9 -> M10 Track A gate
  M10 human approval -> M11 Conditional architecture evolution
  M11 human closure -> Full-Pipeline Demo (includes M12 integration)
```

## Historical Workstreams

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

## Historical Stage Gates

| Gate | Decision |
|---|---|
| G-1 — PASSED | M-1 operating system approved and committed; M0 authorized |
| G0 — PASSED | Foundation builds reproducibly in the documented Windows/Oracle and Docker Linux/Temurin environments |
| G1 — PASSED | Parser-neutral semantic/identity/metric/assessment contracts are stable and deterministic |
| G2 — RECORDED PASSED | Historical frontend/build acceptance is recorded in current state and the 2026-09-10 adjudication record; R0 does not re-certify it |
| M4-R0 — PASSED | Historical matrix, identities/catalog/fragment, bounded oracle results and protocol approved by human supervisor on 2026-09-11 |
| G3 — PASSED | Bounded phase/order-aware Spring inference meets truth-region, witness, version and closed-taxonomy evidence criteria (`37/37` obligations, `0` false certainty; certified 2026-10-14 in [Gate G3 Adjudication Record](reproducibility/g3-spring-adjudication-record-2026-10-14.md)) |
| G4 — PASSED | Canonical conditional graph/metric/query invariants pass, including infeasible-path rejection (`165` tests / `22` suites; certified 2026-10-15 in [Gate G4 Adjudication Record](reproducibility/g4-adjudication-record-2026-10-15.md)) |
| G5 — PASSED | Conditional policy/evidence/score correctness passes region mutations, negatives, witness replay and sensitivity checks |
| G6 — PASSED | Track A release preparation complete: Query HTTP API, evo CLI, visual workbench projections, policy-qualified impact, multi-format exports, and offline local acceptance pass without network/database |
| G7 — CLOSED BY HUMAN DECISION / NOT CERTIFIED PASSED | See [M11.6](tasks/m11/06-execution.md) for scoped results and open assertions |
| G8 — RETIRED AS SEPARATE SIGN-OFF | Local integration and reproduction are checked in the demo |

## Verification and scope tradeoffs

Use [lean verification & 5-Tier `T0–T4` protocol](testing.md#lean-verification-for-the-demo). Preserve evidence, typed gaps, isolated execution, canonical semantics, `I5` anti-overfitting, and honest verdicts. Keep routine Product SWE inner loops in `T0–T3` (`0.5–90 s`) and reserve `T4` for explicit publication/scale/oracle gates.

## Scientific Research & Publication Track (CORE A* / Q1)

Scientific publication is an active, disciplined parallel track alongside the Online Web App product, governed by [SuperPlan v2](evo-sota-superplan-v2.md) and the 19-paper [SOTA Paper Library](paper/README.md):

- **Phase 0 (`WP0.1–WP0.6`, Gate-0):** Close all six live execution blockers (`B1–B6` — CDCL/COI acceptance, `PetClinic` incremental `spring-context` determinism, `Shopizer` 64 MB chunking, `"redis-cart"` `I5` removal & service dedup, Cold regression profiling, harness/DOI fixes, clean Git baseline + Linux `n >= 5` `E0` freeze).
- **Phases 1–4 (`WP1.1–WP4.4`):** Integrate formal SOTA contributions across Pillar 1 (blind `Micans` AMQP/RPC + passive Fail-Close resilience), Pillar 2 (Spring AOP/XML/MeCheck + COI-sliced PB-d-DNNF `#SAT` + real multi-repo `ApplicationContextRunner` oracle), Pillar 3 (fast structural SKG + quarantined `TacitMemoryStore`), and Pillar 4 (dual-sliced, AST-purity & QMOOD-guarded sandboxed evolution).
- **Target Venues:** FSE Tool Demo (launchpad after Gate-0), followed by CORE A* Research (ICSE / OOPSLA / ASE) and Q1 Journal tracks (IEEE TSE / ACM TOSEM / EMSE), enforcing strict zero-overlap claims, blind holdouts, and 1-command Zenodo artifact reproducibility.

## Immediate Sequence

The [current state](current-state.md#exact-next-task) owns the active task sequence (`Phase 0` `WP0.1–WP0.6` in [SuperPlan v2](evo-sota-superplan-v2.md)). The [Step 5 run](architecture/demo-finding-transition.md#closure-run) preserves the completed local demo compatibility journey.
