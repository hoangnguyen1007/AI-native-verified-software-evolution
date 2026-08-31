# Project Context

## Mission

Build an AI-native software evolution platform that can understand Java/Spring Boot
repositories, model software architecture, detect architecture violations, provide
evidence-backed analysis, and support future verified software evolution.

**[CONFIRMED]** — from AGENTS.md.

## Academic Context

- Course: SE121 – Đồ án 1 (UIT)
- Current phase: SE121 – Software Architecture Intelligence Platform
- Future phases: SE122 (Đồ án 2), KLTN (Thesis)
- Academic evaluation rubric: **[OPEN QUESTION]** — not yet available

**[CONFIRMED]** — from AGENTS.md and human decisions.

## Target Ecosystem

- Primary language: **Java**
- Primary framework: **Spring Boot**

**[CONFIRMED]** — from AGENTS.md.

## SE121 Capabilities (In-Scope)

1. Semantic source analysis
2. Software Knowledge Graph
3. Dependency modeling
4. Architecture rule modeling
5. Architecture violation detection
6. Evidence and provenance
7. Basic impact analysis
8. Architecture visualization (deferred until core pipeline works)
9. Benchmarking and evaluation

**[CONFIRMED]** — from AGENTS.md + human decisions.

## Explicit Non-Goals (SE121)

Do NOT implement unless explicitly requested:

- Automated refactoring
- Patch generation
- OpenRewrite transformation pipeline
- AI diagnosis
- Graph-guided RAG
- Sandbox verification
- Differential testing
- Mutation testing pipeline
- CI/CD verification
- Verified Pull Request generation

**[CONFIRMED]** — from AGENTS.md. These belong to SE122/KLTN.

## Engineering Principles

1. Prefer deterministic analysis where possible.
2. Every architecture violation must be backed by inspectable evidence.
3. Every graph relationship should be traceable to source code.
4. Separate parsing, semantic modeling, graph construction, rule evaluation, and presentation.
5. Avoid premature microservices.
6. Prefer a modular architecture with strong boundaries.
7. Minimize unnecessary dependencies.
8. Favor explicit domain models over implicit conventions.
9. Write tests for core analysis behavior.
10. Preserve reproducibility for all benchmark results.

**[CONFIRMED]** — from AGENTS.md.

## Technology Decisions

| Decision | Value | Epistemic Status | Source |
|---|---|---|---|
| Java version | Java 21 | **CONFIRMED** | Human decision |
| Build system | Maven | **CONFIRMED** | Human decision |
| Repository structure | Single monorepo | **CONFIRMED** | Human decision |
| Java parser | JavaParser + SymbolSolver | **HYPOTHESIS** — requires R1 PoC | Proposed candidate |
| Graph database | Neo4j Community | **PROVISIONAL** — requires R2 validation | Proposed candidate |
| Backend framework | Spring Boot 3.x | **PROVISIONAL** | Reasonable default |
| Architecture rule format | TBD | **HYPOTHESIS** | Not yet designed |
| Frontend framework | Deferred | **CONFIRMED** (deferral) | Human decision |

## Test Targets

| Target | Purpose | Status |
|---|---|---|
| Spring PetClinic | First PoC target for parser evaluation | **CONFIRMED** |
| 2–4 additional repos | Validation against varied architectures | **CONFIRMED** (scope); **OPEN QUESTION** (which repos) |

## Repository Structure

```
AI-native-verified-software-evolution/
├── AGENTS.md                  # Root project contract
├── CLAUDE.md                  # Portable AI adapter (Claude)
├── GEMINI.md                  # Portable AI adapter (Gemini)
├── .agents/                   # Portable AI agent configuration
│   ├── agents/                # Agent definitions
│   ├── rules/                 # Engineering rules
│   ├── skills/                # Project-specific skills
│   └── workflows/             # Workflow definitions
├── analyzer/                  # Java semantic analysis engine (empty)
├── backend/                   # API / orchestration server (empty)
├── frontend/                  # Visualization UI (empty, deferred)
├── benchmarks/                # Evaluation scripts & data (empty)
├── tests/                     # Cross-cutting test fixtures (empty)
└── docs/
    ├── project-context.md     # This document
    ├── current-state.md       # Living status
    ├── roadmap.md             # Phased roadmap
    ├── architecture/          # Architecture documentation
    ├── decisions/             # Architecture Decision Records
    ├── research/              # Research notes and candidate ideas
    └── benchmarks/            # Benchmark methodology
```

## Source Hierarchy

Information entering the project is classified by authority:

1. Explicit human decisions
2. Official project/academic documents provided by the human
3. Verified official technical documentation and reproducible evidence
4. Research literature
5. AI-generated design proposals

Nothing from a lower-priority source may be promoted to CONFIRMED
without evidence from a higher-priority source or human approval.

## Epistemic Classification

| Status | Meaning |
|---|---|
| **CONFIRMED** | Verified fact or explicitly approved decision |
| **PROVISIONAL** | Working decision, adopted pending validation |
| **HYPOTHESIS** | Testable claim requiring experiment |
| **ASSUMPTION** | Logical default, not yet validated |
| **OPEN QUESTION** | Identified uncertainty requiring investigation |
| **CANDIDATE IDEA** | Unvetted suggestion from any source |

## Phase Boundaries

| Phase | Focus | Status |
|---|---|---|
| SE121 | Architecture intelligence platform | **Current** |
| SE122 | AI diagnosis, graph-guided RAG, transformation | Future |
| KLTN | Verified evolution, sandbox, CI/CD integration | Future |

## Related Documents

- [Architecture Overview](architecture/architecture.md)
- [Knowledge Graph Schema](architecture/knowledge-graph.md)
- [Current State](current-state.md)
- [Roadmap](roadmap.md)
- [Research Questions](research/research-questions.md)
- [Architecture Decision Records](decisions/)
