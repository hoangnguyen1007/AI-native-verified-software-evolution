# AI-Native Verified Software Evolution Platform

## Project Mission

Build a deterministic, evidence-first software architecture intelligence platform that understands Java and Spring Boot repositories, reconstructs architecture-relevant semantics, detects architecture violations, explains their evidence, and supports later verified software evolution without redesigning the core.

## Current Phase and North Star

Current phase: **SE121 - Software Architecture Intelligence Platform**.

The approved SE121 North Star is **Track A + Track B**:

- **Track A:** protected correctness foundation and complete architecture-intelligence product.
- **Track B:** architecture evolution across compatible repository snapshots, after Track A correctness gates pass.
- **Track C:** optional moonshot work; it must never weaken or delay a credible Track A + B result.

Current operating priority:

1. Technical depth
2. System correctness
3. Architecture quality
4. Engineering quality
5. Product quality
6. Empirical validation
7. Publication readiness later

## SE121 Scope

- Java semantic source analysis
- Multi-module workspace and safe build-model intelligence
- Spring semantic intelligence
- Software Knowledge Graph
- Dependency and architecture-policy modeling
- Evidence-backed architecture violation detection
- Basic, explicitly bounded impact analysis
- Architecture visualization and query services
- Reproducible benchmarking and evaluation
- Track B snapshot comparison and architecture evolution after prerequisite gates

## Explicit Non-Goals

Do not implement these unless the human explicitly changes phase scope:

- AI diagnosis or graph-guided RAG
- Automated refactoring or patch generation
- OpenRewrite transformation pipelines
- Sandbox verification of generated changes
- Differential or mutation testing of generated patches
- CI/CD verification or Verified Pull Requests
- A second programming language

Architecture-mutation fixtures used to evaluate SE121 rule detection are in scope; automated mutation testing of patches is not.

## Source Authority and Epistemic Status

Use this authority order:

1. Explicit human decisions
2. Official project or academic documents supplied by the human
3. Verified repository evidence and reproducible experiments
4. Official technical documentation and specifications
5. Peer-reviewed research
6. AI-generated proposals and assumptions

Classify consequential claims as:

- **CONFIRMED:** human-approved or directly verified fact
- **PROVISIONAL:** adopted working decision with remaining validation gates
- **HYPOTHESIS:** testable claim awaiting evidence
- **ASSUMPTION:** temporary default
- **OPEN QUESTION:** unresolved decision

Never promote a lower-authority claim without evidence or human approval.

## Mandatory Session Bootstrap

Every non-trivial new work session must reconstruct project state. Chat history is not authoritative.

### Tier 0 - always read fully

- `AGENTS.md`
- `docs/project-context.md`
- `docs/current-state.md`
- `docs/roadmap.md`
- `git status`, `git diff`, and recent `git log`

Identify the current milestone, active task, repository state, approved and provisional decisions, blockers, and next expected task.

### Tier 1 - milestone context

Read relevant architecture documents, ADRs, research evidence, and milestone specifications in full.

### Tier 2 - task context

Inspect all relevant production source, tests, schemas, fixtures, benchmark cases, and configuration before editing.

### Tier 3 - extended context

Load external research, historical experiments, and unrelated project areas only when the task needs them.

Before substantial modification, state concisely:

- current milestone
- current task
- relevant contracts
- known risks
- planned change
- verification plan

## Engineering and Architecture Principles

1. Correctness and evidence take precedence over feature count.
2. Preserve deterministic behavior where practical.
3. Every important relationship must expose its origin, semantic status, evidence, and provenance.
4. Never replace uncertainty with invented certainty.
5. Separate repository acquisition, build modeling, semantic extraction, Spring enrichment, graph construction, rule evaluation, storage, query services, and presentation.
6. Do not leak parser AST objects or storage-specific queries across domain boundaries.
7. Represent Maven modules, source roots, module dependencies, dependency management, BOMs, and scopes explicitly where architecture analysis needs them.
8. Do not execute arbitrary untrusted Maven or Gradle lifecycle code.
9. Analysis identity must be content-addressed from stable inputs: repository snapshot, source hashes, classpath manifest, configuration, rules, graph schema, and analyzer version.
10. Backend and frontend domain behavior must use stable architecture query services, not arbitrary graph-store queries.
11. Keep bytecode validation on ASSESS/HOLD until source-semantic evidence demonstrates a concrete need.
12. Prefer a modular monolith and reversible adapters over premature distributed infrastructure.
13. Write tests for semantic behavior, graph invariants, rules, evidence, and regressions.
14. Preserve reproducibility and raw benchmark evidence.

## Standard Task Lifecycle

For meaningful work:

1. Bootstrap context.
2. Define scope, contracts, exit criteria, and non-goals.
3. Complete a research gate when evidence is missing.
4. Complete an architecture gate for consequential design choices.
5. Implement the smallest coherent approved change.
6. Verify with task-specific and proportional broader checks.
7. Perform independent/adversarial review when risk warrants it.
8. Update durable project state.
9. Produce the mandatory handoff.
10. Leave commit and push to the human unless explicitly requested.

## Evidence-First Contract

Architecture facts and violations should preserve, as applicable:

- repository and snapshot identity
- analysis configuration and analyzer version
- relationship category and semantic status
- caller/source and target/candidate identities
- source file and complete source span
- derivation and uncertainty
- dependency and graph path
- rule ID/version and rule provenance
- diagnostics and unresolved/error evidence

A result without sufficient traceability must not be presented as verified.

## Durable Project State

- `docs/project-context.md`: durable identity, scope, authority, and constraints
- `docs/roadmap.md`: future direction, milestone DAG, gates, and Track A/B/C status
- `docs/current-state.md`: concise operational truth now
- `docs/decisions/`: why consequential decisions were made
- `docs/architecture/`: current contracts, boundaries, semantics, schemas, and invariants
- `docs/research/`: methods and evidence, not operational progress

Do not duplicate current status across documents.

At the end of a meaningful task:

- Always update `docs/current-state.md` if project state changed.
- Update `docs/roadmap.md` only when milestone status, sequence, scope, gate state, track assignment, or evidence-backed direction changes.
- Update architecture documents only when contracts, boundaries, schemas, semantics, or invariants change.
- Create or update an ADR only for a significant approved decision with real alternatives.
- Update research documents when experimental evidence changes project knowledge.

## Mandatory Completion and Handoff

No meaningful task is complete immediately after generation or editing. Before completion:

1. Inspect the resulting diff.
2. Run task-specific tests or validation.
3. Run relevant integration/contract checks.
4. Run a broader build/check proportional to impact.
5. Check project and architecture rules.
6. Compare work with exit criteria.
7. Record limitations, open issues, and blockers.
8. Update durable state.

Every meaningful handoff must report:

- STATE BEFORE
- WORK COMPLETED
- FILES CHANGED
- TESTS / COMMANDS ACTUALLY RUN
- RESULTS
- NEW EVIDENCE
- DECISIONS MADE
- DECISIONS STILL REQUIRING HUMAN APPROVAL
- LIMITATIONS
- BLOCKERS
- DURABLE STATE FILES UPDATED
- EXACT NEXT RECOMMENDED TASK

## Agent System

The permanent roles are limited to:

- lead-architect
- researcher
- semantic-analyst
- graph-architect
- implementer
- verifier
- red-team-reviewer
- benchmark-engineer

Use the smallest number of roles that materially improves the work. Independent agreement is not evidence; important decisions require repository evidence, experiments, or human approval.

## Repository and Git Safety

- Inspect and preserve existing user changes.
- Never expose secrets or commit credentials.
- Do not rewrite history, force-push, or perform destructive cleanup without explicit approval.
- Do not edit generated evidence without understanding its generator.
- Do not manually improve benchmark results.
- Do not commit or push unless explicitly requested.
