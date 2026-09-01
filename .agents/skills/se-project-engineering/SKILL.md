---
name: se-project-engineering
description: Engineering operating procedure for planning, implementing, reviewing, testing, researching, benchmarking, documenting, and making architecture decisions in this project.
---

# SE Project Engineering

`AGENTS.md` is the authoritative operating contract. Read it fully before applying this skill.

## Purpose

Deliver the smallest coherent, evidence-backed project change while preserving SE121 phase boundaries, architecture quality, reproducibility, and human oversight.

## Required Procedure

1. Execute the Tier 0 bootstrap from `AGENTS.md`.
2. Classify the task: research, architecture, design, implementation, refactoring, debugging, testing, review, benchmark, documentation, or infrastructure.
3. Load milestone and task context progressively.
4. Define goal, non-goals, contracts, risks, files, and exit criteria.
5. Use a research or architecture gate when the task contains consequential uncertainty.
6. For behavior changes, specify or add tests before implementation where practical.
7. Implement only the approved scope.
8. Run task-specific and proportional broader verification.
9. Inspect the full diff and perform adversarial review when risk warrants it.
10. Update durable state according to `AGENTS.md`.
11. Return the complete mandatory handoff.

## Evidence Discipline

Architecture relationships and violations must preserve identity, source evidence, semantic status, derivation, provenance, configuration, and diagnostics. Unknown or ambiguous information stays unknown or ambiguous.

## Phase Discipline

Do not implement SE122/KLTN AI diagnosis, RAG, transformation, patching, sandbox verification, or Verified PR capabilities. Architecture-mutation fixtures for evaluating SE121 rule detection are allowed.

## Completion

Never claim completion because content was generated or code compiled. Completion requires verified exit criteria, an inspected diff, updated durable state, explicit limitations, and an exact next task.
