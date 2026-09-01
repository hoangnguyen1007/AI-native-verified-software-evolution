# Execute Workflow

Purpose: orchestrate every meaningful task through one consistent lifecycle.

## 1. Bootstrap Context

Run the Tier 0 protocol from `AGENTS.md`, then load milestone and task context progressively. Inspect existing changes before planning.

State: current milestone, task, relevant contracts, risks, planned change, and verification plan.

## 2. Scope and Contract

Classify the task as trivial, bounded, feature, or architecture/research. Define goal, non-goals, affected boundaries/files, acceptance criteria, authority, and phase fit.

Stop if the task crosses SE121 boundaries or needs a consequential human decision.

## 3. Research Gate

Use `/research` only when missing evidence can materially change the decision. Require a decision question, alternatives, evidence, experiment if needed, confidence, and unknowns.

## 4. Architecture Gate

Use `/architect` for consequential boundaries, identities, schemas, semantics, dependencies, storage, or public contracts. Record approved durable decisions as ADRs.

## 5. Implement

Use `/implement` for the smallest coherent approved change. Inspect source/tests first, preserve user work, and verify incrementally.

## 6. Verify

Use `/verify` for task-specific tests, contract/integration checks, proportional broader build checks, manual validation where relevant, and full diff inspection. Classify failures and repair root causes.

## 7. Adversarial Review

Use `/review` when semantic correctness, architecture, security, benchmark validity, or milestone acceptance warrants independent attack. Resolve blocking findings and re-verify.

## 8. Update Durable State

Always update `docs/current-state.md` when project state changed. Update roadmap, architecture, ADR, or research documents only under the rules in `AGENTS.md`.

## 9. Handoff

Use `/handoff`. Include all mandatory fields and the exact next recommended task. Leave commit/push to the human unless explicitly requested.

## Stop Conditions

Stop on completed exit criteria, a consequential missing human decision, repeated non-progress, or an external blocker. Never continue automatically into the next milestone.
