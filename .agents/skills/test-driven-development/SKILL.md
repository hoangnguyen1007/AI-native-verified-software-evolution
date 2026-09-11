---
name: test-driven-development
description: Implement or fix observable behavior with a failing regression/specification test and focused verification. Use for production behavior changes; existing-code characterization and behavior-preserving refactors use baseline tests. Skip prose-only edits.
---

# Test-Driven Development

Use [AGENTS.md](../../../AGENTS.md) for scope, safety and authority. Test observable contracts, not implementation structure.

## Behavior changes

1. Read the relevant contract and consumers (using 20–50 line slices). Name the defect or missing behavior and the independent expected result.
2. Add the smallest useful test with a compact fixture (5–15 line Java sample). Run it strictly in isolated mode (`mvn test -pl <module> -Dtest=<TargetTest> -q`) and inspect the failure: the intended assertion/contract must fail, not environment setup or a typo.
3. Implement the smallest coherent fix. Re-run only the failing test class in isolated mode to confirm green. Never run multi-module reactor builds or full benchmarks during this inner cycle.
4. Refactor only while relevant checks stay green. Run broader integration checks or reactor verification only once at slice completion. Use [verification-before-completion](../verification-before-completion/SKILL.md) for final claims.

If a test passes immediately, determine whether it characterizes existing behavior or fails to exercise the new requirement. Do not manufacture a failure or change a correct expectation merely to obtain red.

### Lean Execution Discipline
- **Inner Loop Isolation:** Inner TDD cycles must execute in <5 seconds and output <20 lines. Avoid broad builds until the vertical slice behavior is proven locally.
- **Error Diagnosis:** When a test fails, inspect only the targeted stack trace or failure diff; never ingest full reactor logs or unrelated compiler warnings.

## Existing work and exceptions

- Never delete user/pre-existing code, discard a patch, or restart implementation to satisfy a test-order ritual.
- For an already-written change, preserve it. Demonstrate regression sensitivity against the baseline in an isolated scratch copy when useful; report if red was not observed.
- For behavior-preserving refactors, run existing tests before and after; add characterization coverage where the contract is unprotected.
- For exploratory PoCs, generated outputs, config and documentation, choose appropriate experiments, generator checks or validation. Record the reason; routine low-risk choices within scope need no extra approval.
- Test a private helper only when its behavior has a meaningful invariant not adequately covered through a consumer. No quota of one test per method.

## Test quality

Read [writing-good-tests.md](writing-good-tests.md) when designing tests. In this project, semantic tests must distinguish correct, incorrect, unresolved, ambiguous, omitted, unsupported, error and provenance failures where applicable. A parser's successful resolution is not its own correctness oracle.

Keep literal/golden expectations tied to approved versioned contracts. Identity/serialization golden tests are valuable even when an intentional contract change requires updating them.
