---
name: test-driven-development
description: Implement or fix observable behavior with a failing regression/specification test and focused verification. Use for production behavior changes; existing-code characterization and behavior-preserving refactors use baseline tests. Skip prose-only edits.
---

# Test-Driven Development

Use [AGENTS.md](../../../AGENTS.md) for scope, safety and authority. Test observable contracts, not implementation structure.

## Behavior changes

1. Read the relevant contract and consumers. Name the defect or missing behavior and the independent expected result.
2. Add the smallest useful test. Run it against the baseline and inspect the failure: the intended assertion/contract must fail, not environment setup or a typo.
3. Implement the smallest coherent fix. Run the failing case, then affected integration/contract tests.
4. Refactor only while relevant checks stay green. Use [verification-before-completion](../verification-before-completion/SKILL.md) for final claims.

If a test passes immediately, determine whether it characterizes existing behavior or fails to exercise the new requirement. Do not manufacture a failure or change a correct expectation merely to obtain red.

## Existing work and exceptions

- Never delete user/pre-existing code, discard a patch, or restart implementation to satisfy a test-order ritual.
- For an already-written change, preserve it. Demonstrate regression sensitivity against the baseline in an isolated scratch copy when useful; report if red was not observed.
- For behavior-preserving refactors, run existing tests before and after; add characterization coverage where the contract is unprotected.
- For exploratory PoCs, generated outputs, config and documentation, choose appropriate experiments, generator checks or validation. Record the reason; routine low-risk choices within scope need no extra approval.
- Test a private helper only when its behavior has a meaningful invariant not adequately covered through a consumer. No quota of one test per method.

## Test quality

Read [writing-good-tests.md](writing-good-tests.md) when designing tests. In this project, semantic tests must distinguish correct, incorrect, unresolved, ambiguous, omitted, unsupported, error and provenance failures where applicable. A parser's successful resolution is not its own correctness oracle.

Keep literal/golden expectations tied to approved versioned contracts. Identity/serialization golden tests are valuable even when an intentional contract change requires updating them.
