# AI-Native Verified Software Evolution Platform

## Project Mission

Build an AI-native software evolution platform that can understand Java/Spring Boot repositories, model software architecture, detect architecture violations, provide evidence-backed analysis, and support future verified software evolution.

## Current Phase

Current phase: SE121 – Đồ án 1

Current scope is limited to:

- Semantic source analysis
- Software Knowledge Graph
- Dependency modeling
- Architecture rule modeling
- Architecture violation detection
- Evidence and provenance
- Basic impact analysis
- Architecture visualization
- Benchmarking and evaluation

## Explicit Non-Goals for Current Phase

Do NOT implement the following unless explicitly requested:

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

Those belong to later project phases.

## Target Ecosystem

Primary target:

- Java
- Spring Boot

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

## AI Agent Behavior

Before making architectural changes:

1. Inspect the repository.
2. Read relevant documentation.
3. Identify affected modules.
4. State assumptions.
5. Propose a plan.
6. Identify risks and alternatives.

Do not silently make major architectural decisions.

For implementation tasks:

1. Understand requirements.
2. Inspect existing code and tests.
3. Make the smallest coherent change.
4. Add or update tests.
5. Run relevant verification.
6. Update documentation when behavior or architecture changes.
7. Report changed files, verification results, and remaining risks.

## Evidence First

Architecture analysis should preserve:

- source file
- source span
- symbol
- dependency
- graph path
- violated rule
- rule provenance
- analysis evidence

A result without traceable evidence should not be treated as a verified architecture violation.

## Repository Discipline

- Never commit secrets.
- Never commit API keys.
- Never modify generated files without understanding their source.
- Never rewrite git history unless explicitly requested.
- Never force-push without explicit approval.
- Never change public APIs unnecessarily.
- Never introduce a new framework merely because an agent prefers it.

## Documentation

Important project knowledge must be persisted in repository documentation rather than only in conversation history.

Primary documents:

- docs/project-context.md
- docs/architecture/architecture.md
- docs/architecture/knowledge-graph.md
- docs/decisions/
- docs/current-state.md
- docs/roadmap.md

## Multi-Agent Rule

Agents must treat Git, repository documentation, tests, and source code as the durable project state.

Conversation history is temporary context and must not be relied upon as the only source of project knowledge.

## Completion Standard

Never claim a task is complete without reporting:

- what changed
- which tests were executed
- which verification passed
- which verification was not available
- known limitations
- remaining follow-up work