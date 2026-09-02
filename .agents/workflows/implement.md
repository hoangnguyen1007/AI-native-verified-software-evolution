# Implement — Authorized Bounded Change

After AGENTS.md bootstrap, inspect affected source/callers, contracts, tests, fixtures and build configuration. Define files, behavior, non-goals, risks and observable exit criteria.

1. For behavior changes, apply [TDD](../skills/test-driven-development/SKILL.md). For failures, first use [systematic debugging](../skills/systematic-debugging/SKILL.md).
2. Implement the smallest coherent change preserving uncertainty, diagnostics, provenance and deterministic behavior.
3. Run focused checks while iterating; fix supported in-scope defects. Avoid unrelated cleanup.
4. Apply [verification](verify.md) to affected integrations/contracts and proportionate broader checks; inspect the final diff.
5. Update durable state as required and [hand off](handoff.md).

Review-only restrictions do not prevent an implementer from repairing their own authorized change. Seek a decision only if the remedy would change consequential unapproved scope or contracts.
