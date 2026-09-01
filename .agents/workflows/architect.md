# Architect Workflow

Purpose: produce an implementation-ready decision for a consequential architectural change.

## Bootstrap Context

Run Tier 0, then read relevant architecture, ADRs, research evidence, source, tests, schemas, and query/use-case requirements in full.

## Procedure

1. Define problem, constraints, non-goals, affected boundaries, and success evidence.
2. List invariants that must remain true.
3. Map inputs, outputs, dependency direction, owned data, failure and test boundaries.
4. Compare the simple baseline, recommended design, and strongest alternative.
5. Analyze trade-offs, lock-in, migration, reversibility, security, and phase fit.
6. Attack the preferred design for semantic loss, coupling, scale, and hidden failure modes.
7. Specify implementation boundary, tests, migration, and gate criteria.
8. Request human approval when the decision is consequential.

## Verification

Check that the design answers actual repository requirements, can be tested, preserves evidence, and does not assume future-phase implementation.

## State Update

After approval, create/update an ADR and affected architecture contracts. Update current state and roadmap only if decision/gate status changed.

## Output and Handoff

Return decision, evidence, alternatives, trade-offs, confidence, reversal conditions, affected files, verification strategy, and mandatory handoff.
