# Architect

Description: Design, challenge, and document architecture for a consequential project change.

---

# 1. Load Reality

Read:

- AGENTS.md
- current-state
- roadmap
- architecture docs
- relevant ADRs

Inspect the actual repository.

Never design against an imagined system.

---

# 2. Define the Problem

State:

- current state
- desired state
- problem
- constraints
- non-goals
- affected boundaries
- success criteria

---

# 3. Identify Architectural Invariants

List what must remain true.

Examples:

- analyzer independent from UI
- source evidence preserved
- graph relationships traceable
- deterministic analysis remains testable
- benchmark results reproducible

Any proposed architecture violating an invariant must explicitly justify it.

---

# 4. Map Dependencies

Identify:

- components
- dependency direction
- data flow
- control flow
- public interfaces
- persistence
- external systems
- test boundaries

---

# 5. Generate Options

Produce:

A. simplest viable design
B. recommended design
C. strongest alternative

For each:

- complexity
- benefits
- risks
- migration cost
- future flexibility

---

# 6. Challenge the Recommendation

Actively search for reasons the preferred architecture could fail.

Ask:

- What becomes tightly coupled?
- What becomes hard to test?
- What becomes expensive to replace?
- Where can semantic information be lost?
- What happens at repository scale?
- What becomes difficult in later project phases?
- What failure modes become invisible?

Do not perform confirmation-only reasoning.

---

# 7. Architecture Fitness

Define measurable or testable properties where practical.

Examples:

- dependency boundaries
- parser output completeness
- graph consistency
- evidence preservation
- query latency
- memory behavior
- benchmark reproducibility

An architecture should have observable qualities, not only diagrams.

---

# 8. Reversibility

Identify:

- easy-to-reverse choices
- expensive-to-reverse choices
- irreversible choices

For expensive choices, increase evidence requirements before implementation.

---

# 9. Implementation Boundary

Specify exactly:

## Change

...

## Do Not Change

...

## Interfaces

...

## Data Model

...

## Tests

...

## Migration

...

## Risks

...

---

# 10. Decision Record

Create/update ADR when the decision is consequential.

Include:

- context
- problem
- alternatives
- decision
- rationale
- trade-offs
- consequences
- evidence
- reversal conditions

---

# 11. Final Architecture Review

Before implementation, ask:

- Is the design simpler than necessary?
- Is any component premature?
- Is any coupling unnecessary?
- Can it be tested?
- Can it be benchmarked?
- Is it compatible with the current project phase?
- Does it preserve future options?

Return an implementation-ready architecture decision.