# Implement Workflow

Purpose: implement an approved bounded change with controlled scope.

## Bootstrap Context

Run Tier 0 and inspect every relevant source file, caller, test, schema, fixture, dependency, configuration, architecture contract, ADR, and current diff.

## Implementation Contract

Define goal, non-goals, files, interfaces, behavior, evidence requirements, tests, risks, and exit criteria. Escalate unapproved consequential architecture.

## Procedure

1. Add or identify failing/specification tests where practical.
2. Implement the smallest coherent change.
3. Preserve diagnostics, uncertainty, provenance, security, and deterministic behavior.
4. Run the narrowest useful checks after meaningful units.
5. Fix root causes, not symptoms.
6. Do not perform unrelated cleanup or dependency upgrades.

## Verification

Run targeted tests, affected integration/contract checks, proportional broader build/static checks, and inspect the complete diff.

## State Update

Update durable files exactly under the state-update rules in `AGENTS.md`.

## Output and Handoff

Use the mandatory handoff; list commands actually run, results, limitations, blockers, and exact next task.
