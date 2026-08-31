---
name: se-project-engineering
description: Provides the engineering operating procedure for the AI-Native Verified Software Evolution Platform. Use when planning, designing, implementing, reviewing, debugging, testing, researching, or making architectural decisions in this project. Enforces evidence-first reasoning, phase boundaries, explicit plans, reproducibility, minimal coherent changes, verification gates, documentation updates, and multi-agent handoff discipline.
---

# SE Project Engineering

## Purpose

This skill defines how an AI agent should perform engineering work in the
AI-Native Verified Software Evolution Platform.

It is the project's engineering methodology.

It does NOT replace:
- AGENTS.md
- project rules
- architecture documentation
- task-specific requirements

Those sources remain authoritative.

---

# 1. Operating Principles

Always optimize for:

1. Correctness
2. Architectural integrity
3. Evidence and traceability
4. Reproducibility
5. Maintainability
6. Small coherent changes
7. Testability
8. Research validity
9. Clear human oversight

Do not optimize for:
- maximum amount of code
- maximum number of abstractions
- premature generalization
- cleverness
- framework quantity
- agent autonomy for its own sake

Prefer the simplest design that satisfies the current research and
engineering requirements.

---

# 2. Phase Awareness

The current project phase is:

SE121 – Software Architecture Intelligence Platform.

Current scope:

- semantic source analysis
- Software Knowledge Graph
- dependency modeling
- architecture rules
- architecture violation detection
- evidence and provenance
- basic impact analysis
- visualization
- benchmarking

Do NOT silently introduce functionality belonging to later phases:

- AI diagnosis
- Graph-grounded RAG
- automated refactoring
- patch generation
- OpenRewrite transformation
- sandbox execution
- differential testing
- mutation testing
- CI/CD verification
- Verified Pull Request generation

If a task appears to require a later-phase capability:

1. Identify it.
2. Explain the dependency.
3. Determine whether a minimal prerequisite is needed now.
4. Do not implement the later-phase feature unless explicitly requested.

---

# 3. Task Classification

Before acting, classify the task as one of:

- Research
- Architecture
- Design
- Implementation
- Refactoring
- Debugging
- Testing
- Review
- Benchmarking
- Documentation
- Infrastructure

The classification determines the appropriate process.

---

# 4. Research Tasks

For research tasks:

1. Define the question.
2. Identify assumptions.
3. Identify candidate approaches.
4. Compare alternatives using explicit criteria.
5. Separate facts from hypotheses.
6. Record important findings.
7. Recommend a decision only when evidence is sufficient.

Do not choose a technology merely because it is popular.

For technology selection, consider:

- semantic fidelity
- ecosystem maturity
- Java/Spring compatibility
- performance
- implementation complexity
- testability
- observability
- maintainability
- research reproducibility
- future compatibility with later project phases

---

# 5. Architecture Tasks

Before changing architecture:

1. Read relevant architecture documentation.
2. Inspect affected modules.
3. Identify dependencies.
4. Identify architectural invariants.
5. Consider at least two viable alternatives when the decision is significant.
6. State trade-offs.
7. Record the final decision as an ADR.

Do not introduce microservices unless there is a demonstrated reason.

Prefer modular boundaries and explicit interfaces.

Architecture changes must not be hidden inside implementation tasks.

---

# 6. Implementation Tasks

Before writing code:

1. Read the task.
2. Read AGENTS.md.
3. Read relevant project documentation.
4. Inspect the current implementation.
5. Inspect existing tests.
6. Identify the smallest coherent change.
7. Produce a concise implementation plan.

Then implement.

Do not rewrite unrelated code.

Do not perform opportunistic cleanup unless it is required for correctness,
security, maintainability, or the current task.

---

# 7. Verification Gate

A task is not complete merely because code was written.

After implementation:

1. Run the most relevant tests.
2. Run the relevant build.
3. Run static analysis when available.
4. Verify changed behavior.
5. Inspect the final diff.
6. Check for accidental changes.
7. Update documentation when required.

Report:

- what changed
- tests executed
- verification results
- failures
- limitations
- remaining work

Never claim verification that was not actually performed.

---

# 8. Evidence-First Architecture Analysis

Architecture analysis is evidence-driven.

Every architecture violation should ideally preserve:

- rule identifier
- severity
- source file
- source span
- symbol
- graph path
- dependency
- evidence
- provenance

Do not present an architecture conclusion as a fact when the underlying
evidence is unavailable or ambiguous.

Prefer:

"EVIDENCE CONFIRMED"

over:

"MODEL BELIEVES"

---

# 9. Semantic Analysis Discipline

When analyzing source code:

1. Prefer structured semantic information over textual heuristics.
2. Preserve source locations.
3. Preserve symbol identity.
4. Distinguish declarations from references.
5. Distinguish direct dependencies from inferred dependencies.
6. Preserve uncertainty where resolution is incomplete.
7. Never silently invent graph relationships.

When a relationship cannot be resolved confidently, represent the
uncertainty explicitly rather than fabricating a relationship.

---

# 10. Knowledge Graph Discipline

Every graph entity should have a stable conceptual identity.

Every relationship should have:

- type
- source
- target
- evidence
- provenance when applicable

Graph construction should be deterministic whenever practical.

Graph schema changes require:

1. schema impact analysis
2. migration/update strategy
3. test coverage
4. documentation update

---

# 11. Architecture Rule Discipline

A rule should be defined explicitly.

A rule should specify:

- rule ID
- intent
- scope
- required conditions
- forbidden conditions
- detection strategy
- evidence requirements
- severity semantics
- known limitations
- test cases

A rule that cannot explain why it triggered should be considered incomplete.

---

# 12. Testing Philosophy

Tests should verify behavior, not implementation details.

For analysis components, prefer tests covering:

- normal cases
- nested structures
- inheritance
- interfaces
- annotations
- generics
- ambiguous cases
- malformed input
- unresolved symbols
- large repositories

For architecture rules, each rule should have:

- positive examples
- negative examples
- edge cases
- regression tests

---

# 13. Research Reproducibility

Benchmark results must be reproducible.

Record:

- dataset
- repository/version or commit
- configuration
- analyzer version
- rule version
- environment
- execution parameters
- result
- failures

Do not manually edit benchmark results.

Prefer generated reports.

---

# 14. Documentation Memory

Project knowledge must not remain only inside conversation history.

Persist important knowledge in:

- docs/project-context.md
- docs/current-state.md
- docs/roadmap.md
- docs/decisions/
- docs/architecture/

When a decision materially changes project behavior,
update the relevant documentation.

---

# 15. Multi-Agent Protocol

Treat other agents as specialized collaborators.

Good delegation:

- research comparison
- independent code review
- test generation
- benchmark analysis
- architecture critique
- security review

Bad delegation:

- "build the whole project"
- overlapping agents editing the same files
- multiple agents making incompatible architecture decisions

Before delegating:

1. Define the objective.
2. Define the allowed scope.
3. Identify relevant files.
4. Define expected output.
5. Define what the agent must NOT modify.

After delegation:

1. Inspect the result.
2. Verify claims.
3. Integrate selectively.
4. Record important conclusions.

---

# 16. Handoff Protocol

Whenever work must continue in another agent, machine,
conversation, or coding tool, produce a durable handoff.

The handoff should contain:

## Context
What is the project/task?

## Current State
What is already complete?

## Decisions
What has been decided and why?

## Files
Which files are relevant?

## Unfinished Work
What remains?

## Risks
What could go wrong?

## Next Action
What should the next agent do first?

Persist important handoff information into project documentation
rather than relying only on conversation history.

---

# 17. Change Safety

Before destructive actions:

- verify the target
- inspect git state
- preserve recoverability

Never:

- delete large portions of the project without explicit justification
- force-push
- rewrite history
- expose secrets
- commit credentials
- silently modify unrelated modules

---

# 18. Conflict Resolution

When instructions conflict, prioritize:

1. explicit human instruction
2. AGENTS.md
3. project architecture/decision records
4. project rules
5. this skill
6. task-specific preferences
7. agent assumptions

When uncertainty materially affects the result,
stop and surface the uncertainty rather than inventing a decision.

---

# 19. Completion Contract

Before declaring a task complete, verify:

- Requirements satisfied
- Scope respected
- Relevant tests passed
- Build passed when applicable
- Diff inspected
- Documentation updated when required
- Known limitations reported

Final response should be concise and structured:

## Implemented
...

## Verification
...

## Evidence
...

## Remaining
...

## Risks
...