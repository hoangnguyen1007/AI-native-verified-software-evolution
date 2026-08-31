# Research & Architecture Decision Protocol

## Purpose

This rule governs consequential technical and research decisions.

Use it when:

- choosing technologies
- selecting libraries/frameworks
- designing system architecture
- designing module boundaries
- selecting parsers
- designing semantic models
- designing graph schemas
- selecting databases
- designing APIs
- changing architecture
- evaluating performance/scalability approaches
- designing experiments
- defining benchmarks
- interpreting technical evidence
- making decisions that are costly or difficult to reverse

The objective is not to make the system more sophisticated.

The objective is to make the best defensible decision for the current project phase while preserving future options.

---

# 1. Decision First

Before investigating technologies, explicitly define:

- the decision that must be made
- why the decision matters
- current project phase
- constraints
- required capabilities
- success criteria
- reversibility
- consequences of choosing incorrectly

Do not research without a decision target.

Avoid research that produces information without reducing an actual project uncertainty.

---

# 2. Understand the Current System First

Before making a consequential decision:

1. Read AGENTS.md.
2. Read relevant project documentation.
3. Inspect the current repository structure.
4. Inspect existing implementations.
5. Inspect tests.
6. Inspect existing dependencies.
7. Inspect relevant ADRs.
8. Identify existing architectural constraints.

Do not recommend replacing or redesigning something before understanding why it exists.

Repository evidence takes precedence over generic assumptions.

---

# 3. Respect the Current Project Phase

Current phase:

SE121 – Software Architecture Intelligence Platform.

Current scope:

- semantic source analysis
- Software Knowledge Graph
- dependency modeling
- architecture rules
- architecture violation detection
- evidence/provenance
- basic impact analysis
- visualization
- benchmarking

Do not silently pull later-phase functionality into current implementation.

Later phases may include:

- AI diagnosis
- graph-grounded RAG
- automated refactoring
- patch generation
- transformation engines
- sandbox verification
- differential testing
- mutation testing
- CI/CD verification
- Verified Pull Requests

When later-phase requirements influence a current architectural decision:

- account for them as future constraints
- preserve clean extension points
- do not implement the later feature prematurely

---

# 4. Separate Facts From Judgment

For consequential decisions, classify information as:

## FACT

Directly supported by:
- official documentation
- source code
- reproducible experiment
- repository evidence
- benchmark results

## OBSERVATION

Something directly observed during:
- execution
- profiling
- testing
- experiment
- inspection

## INFERENCE

A reasoned conclusion derived from evidence.

## HYPOTHESIS

A claim that still requires validation.

Never present an inference or hypothesis as established fact.

---

# 5. Source Hierarchy

When external technical information is required, prefer sources in this order:

1. Official project documentation
2. Official specification
3. Official source repository
4. Maintainer documentation
5. Reproducible technical experiments
6. High-quality technical publications
7. Community discussion

Do not use popularity as evidence of suitability.

For rapidly changing technologies, verify current information rather than relying on remembered knowledge.

Record important external sources for consequential decisions.

---

# 6. Compare Alternatives

For meaningful architectural decisions, identify viable alternatives.

Do not compare ten options merely to appear thorough.

Normally evaluate:

- preferred option
- strongest alternative
- current/simple baseline

Compare them against criteria relevant to this project.

Possible criteria:

- semantic fidelity
- correctness
- Java/Spring compatibility
- performance
- memory usage
- scalability
- implementation complexity
- ecosystem maturity
- library stability
- debugging experience
- testability
- observability
- maintainability
- research reproducibility
- benchmarkability
- future-phase compatibility
- reversibility
- operational complexity

The criteria must come from the actual decision.

---

# 7. Use a Decision Matrix When Useful

For consequential choices, create an explicit comparison.

Example:

| Criterion | Option A | Option B | Baseline |
|---|---:|---:|---:|
| Semantic fidelity | | | |
| Performance | | | |
| Complexity | | | |
| Testability | | | |
| Research value | | | |
| Future compatibility | | | |

Do not invent numeric scores without a defensible basis.

When scoring is subjective, say so.

---

# 8. Prefer Experiments Over Speculation

When documentation and reasoning are insufficient:

1. Define the uncertainty.
2. Formulate a hypothesis.
3. Define the minimum experiment needed.
4. Keep inputs controlled.
5. Measure relevant outputs.
6. Record the result.
7. Re-evaluate the decision.

Examples:

- parser accuracy comparison
- AST extraction performance
- graph query performance
- repository indexing time
- memory consumption
- benchmark reproducibility
- false-positive rate of an architecture rule

Prefer a focused proof-of-concept over a large speculative implementation.

---

# 9. Architecture Before Implementation

For substantial architecture changes, produce:

## Context

What problem are we solving?

## Constraints

What must remain true?

## Options

What viable designs exist?

## Decision

What are we selecting?

## Rationale

Why is it the best choice?

## Trade-offs

What are we giving up?

## Consequences

What changes operationally or structurally?

## Reversal Conditions

Under what future evidence should this decision be reconsidered?

---

# 10. Preserve Reversibility

Prefer decisions that are:

- modular
- replaceable
- isolated behind interfaces
- testable independently
- reversible without repository-wide rewrites

Especially avoid early hard coupling to:

- one parser
- one graph storage implementation
- one LLM provider
- one UI implementation
- one deployment platform

Use abstraction only when it protects a real expected change.

Do not create abstractions solely for theoretical flexibility.

---

# 11. Avoid Premature Distributed Architecture

Do not introduce:

- microservices
- service meshes
- message brokers
- distributed queues
- Kubernetes
- event-driven architecture

unless a concrete requirement demonstrates their necessity.

Prefer a well-structured modular architecture first.

Complexity must earn its existence.

---

# 12. Analyze Architectural Boundaries

For every major component, determine:

- responsibility
- inputs
- outputs
- dependencies
- owned data
- invariants
- error boundaries
- testing boundary
- extension points

Prefer dependency direction that keeps the core analysis system independent from presentation and infrastructure when practical.

---

# 13. Semantic Analysis Decisions

For source-analysis technology, prioritize semantic fidelity over convenience.

Evaluate whether the approach can correctly represent:

- packages
- classes
- interfaces
- methods
- fields
- imports
- annotations
- inheritance
- implementations
- method calls
- field dependencies
- framework-specific semantics
- configuration relationships
- persistence relationships

A parser that is easy to integrate but loses important semantic information is not automatically a good choice.

---

# 14. Knowledge Graph Decisions

When designing the Software Knowledge Graph, explicitly define:

- node identity
- relationship identity
- relationship semantics
- source provenance
- source location
- direct vs derived relationships
- unresolved relationships
- schema evolution strategy

Never optimize graph elegance at the expense of traceability.

The graph must support answering:

"Why does this relationship exist?"

---

# 15. Architecture Rule Decisions

Every important architecture rule should define:

- rule identifier
- intended invariant
- detection scope
- required evidence
- detection algorithm
- false-positive risks
- false-negative risks
- severity semantics
- positive examples
- negative examples
- regression tests

A rule should be explainable independently from an LLM response.

---

# 16. Research Validity

When the work contributes to the research component of the project:

- define the measurable question
- define the baseline
- define variables
- define metrics
- control relevant confounders
- record methodology
- preserve raw results
- distinguish exploratory results from final claims

Do not optimize an experiment after seeing the result without recording the change.

Do not choose a metric merely because it produces a favorable number.

---

# 17. Benchmark Design

Benchmarks should preserve enough information to reproduce the result.

Record, where relevant:

- dataset
- repository
- commit/version
- analyzer version
- rule version
- parser version
- configuration
- environment
- runtime
- hardware
- parameters
- result
- failure
- uncertainty

Never manually alter generated benchmark output.

---

# 18. Cost of Complexity

Before adding a new component, ask:

1. What problem does it solve?
2. What simpler alternative exists?
3. What complexity does it introduce?
4. How will it be tested?
5. How will it be debugged?
6. How will it affect future research?
7. Can it later be removed?

Reject complexity that has no measurable benefit.

---

# 19. Decision Confidence

Use explicit confidence:

### HIGH

Supported by strong evidence or reproducible experiments.

### MEDIUM

Reasonable evidence exists but some uncertainty remains.

### LOW

Primarily based on assumptions or limited experiments.

Low-confidence architectural decisions should prefer reversible implementation.

---

# 20. Architecture Decision Records

When a decision materially affects project architecture or research methodology:

Create an ADR under:

docs/decisions/

Use:

ADR-NNN-title.md

Include:

- Status
- Context
- Problem
- Constraints
- Options
- Decision
- Rationale
- Trade-offs
- Consequences
- Evidence
- Reversal conditions

Do not bury major architectural decisions inside chat history.

---

# 21. Decision Gate

Before implementing a consequential architectural decision, verify:

- decision target is clear
- relevant repository context was inspected
- important alternatives were considered
- evidence is sufficient
- trade-offs are understood
- reversibility is understood
- project-phase boundaries are respected
- ADR is recorded when required

Only then proceed to implementation.

---

# 22. Avoid Bikeshedding

Do not spend large amounts of time debating low-impact decisions.

For low-risk, reversible choices:

- choose a sensible default
- document only if necessary
- continue implementation

Spend research effort where incorrect decisions would be expensive.

---

# 23. Escalation Rule

Continue autonomously when the uncertainty is:

- local
- reversible
- low-risk
- unlikely to affect research validity

Escalate when uncertainty materially affects:

- core architecture
- persistent data model
- benchmark validity
- security
- expensive dependencies
- public API contracts
- irreversible migration
- project scope

When escalating, present the smallest decision set necessary.

---

# 24. Decision Output

For a consequential decision, produce:

## Decision
...

## Evidence
...

## Alternatives
...

## Trade-offs
...

## Confidence
HIGH / MEDIUM / LOW

## Recommendation
...

## ADR
...

Do not provide a long explanation when a compact evidence-backed decision is sufficient.

---

# 25. Final Principle

The best architecture is not the architecture with the most technologies.

The best architecture is the simplest architecture that:

- solves the current problem correctly
- preserves important future options
- can be tested
- can be explained
- can be measured
- can be reproduced
- can be evolved