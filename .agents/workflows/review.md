# Review — Read-Only Adversarial Assessment

After AGENTS.md bootstrap, inspect requirements, actual diff/artifacts, relevant contracts, tests/fixtures and raw evidence. Do not audit only another agent's summary.

Attack:
- assumptions, edge cases, false certainty and unsupported conclusions;
- dependency boundaries, identity, status, provenance and deterministic behavior;
- untrusted input, paths, execution, secrets and scope;
- test sensitivity, negative cases, independent oracles and hidden denominators.

Rank actionable defects P0-P3, citing location, violated requirement, consequence and reproduction/reasoning. Separate preferences and open questions from confirmed defects.

Run permitted proportional checks. Preserve reviewed source and raw evidence; report state corrections needed. A follow-up request to implement findings changes the mode and authorizes relevant repairs.

Return blocking findings first, strengths, verification gaps, verdict (APPROVE / CHANGES REQUIRED / BLOCKED) and [handoff](handoff.md). State whether review was independent or self-review.
