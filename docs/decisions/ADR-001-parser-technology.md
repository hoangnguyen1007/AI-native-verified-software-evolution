# ADR-001: Primary Java Semantic Frontend

**Status:** ACCEPTED — human-approved implementation choice; empirical G2 acceptance pending.
**Originally provisional:** 2026-09-01. **Human approval recorded:** 2026-09-02.
**Scope:** SE121/M2 Java source analysis.

## Decision

Use JavaParser + JavaSymbolSolver as the primary semantic frontend behind a replaceable, parser-neutral `SemanticFrontend` port. The human explicitly confirmed this choice on 2026-09-02 after the comparative investigation.

The approval selects a direction. It does not establish universal accuracy, superiority over OpenRewrite, resource advantages, or a passed M2/M3 gate.

OpenRewrite remains an independent comparator and a possible future transformation technology. No transformation/patch pipeline enters SE121.

## Drivers and evidence

Required capabilities: architecture-relevant Java relationships, exact source provenance, explicit incomplete/ambiguous/error outcomes, modern Java support, safe multi-module classpaths and stable canonical mapping.

- [R1 evaluation](../research/parser-evaluation.md) demonstrated bounded viability on the pinned PetClinic corpus and 14 labeled cases. Resolution coverage is distinct from correctness.
- [Comparative evaluation](../research/semantic-frontend-comparison.md) includes controlled and PetClinic CALLS-only evidence. It does not establish a general ranking; the experimental provenance/origin model has known defects recorded in current state.
- M1 provides parser-neutral identity, evidence and status contracts. Preserve them rather than accepting parser-specific objects into the domain.

## Alternatives and trade-offs

JavaParser provides direct node ranges and follows the established source-analysis direction. Its generic/lambda/modern-Java and scale behavior still needs expanded evidence.

OpenRewrite provides an independent attribution comparison. The existing adapter's span reconstruction and canonical mapping remain incomplete; those are adapter limitations, not proof of an intrinsic technology defect.

Eclipse JDT or Spoon remain possible alternatives if a replacement trigger requires a focused evaluation. No ranking or migration to these alternatives has been approved.

## Boundaries and validation

- All AST/resolution objects remain inside the frontend adapter. Canonical output uses the approved M1 contracts.
- Exact Java names/signatures, category coverage, port shape and ground truth are M2 design responsibilities.
- Safe source/module/classpath acquisition is part of M3/G2. Do not use hidden dependency supersets or execute arbitrary target lifecycles.
- Keep JavaParser interactions in an isolated implementation module, preserving the original ADR's boundary. Choose its exact name and dependency layout during M2 design; do not expose parser types through the port.
- Register representative positive/negative/degraded/error cases and meaningful performance budgets before evaluation. Do not invent numeric thresholds in this ADR.

## Replacement triggers

Reassess the approved choice when reproducible evidence shows:
1. Required semantic cases cannot meet registered acceptance criteria after bounded adapter investigation.
2. Required provenance, uncertainty or deterministic identity cannot be preserved without violating canonical contracts.
3. Representative multi-module/scale cases exceed approved resource budgets or repeatedly fail robustly bounded analysis.
4. A supported alternative materially addresses the demonstrated gap with a credible migration cost and verified canonical mapping.

A single preliminary difference starts investigation, not automatic replacement. Preserve raw evidence, distinguish adapter bugs from frontend limits, and obtain human approval before switching the primary technology.
