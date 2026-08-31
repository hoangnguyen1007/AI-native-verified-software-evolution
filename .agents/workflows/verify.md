# Verify

Description: Independently verify implementation correctness through reproducible checks, failure classification, and evidence-based iteration.

---

# 1. Establish Baseline

Inspect:

- Git diff
- changed files
- task requirements
- relevant architecture
- tests
- build configuration

Do not assume the implementation is correct.

---

# 2. Verification Ladder

Run checks from cheapest/highest-signal to broader checks:

1. syntax/compile
2. targeted unit tests
3. affected integration tests
4. broader test suite
5. static analysis
6. build/package
7. behavioral/manual verification when required

Do not run expensive checks blindly when a cheaper check already proves the failure.

---

# 3. Failure Classification

Every failure should be classified as:

- implementation defect
- test defect
- environment failure
- dependency failure
- flaky behavior
- specification ambiguity
- unrelated pre-existing failure

Do not automatically blame the latest code change.

---

# 4. Root-Cause Loop

For implementation failures:

1. reproduce
2. inspect evidence
3. identify root cause
4. make smallest correction
5. rerun the failing check
6. rerun affected checks

For ambiguous failures:

stop and report the uncertainty.

---

# 5. Adversarial Cases

Where relevant, test:

- empty input
- malformed input
- unusual repository structure
- nested Java constructs
- inheritance
- interfaces
- generics
- annotations
- unresolved symbols
- circular dependencies
- large inputs

Tests should target actual project risks.

---

# 6. Research Integrity

For analysis/benchmark code, verify:

- determinism where expected
- source provenance
- reproducibility
- correct metrics
- no hardcoded favorable outcomes
- no accidental dataset leakage

---

# 7. Completion Standard

Do not declare success because:

- code compiles
- one test passes
- the output "looks right"

Declare success only when the relevant acceptance conditions are actually verified.

---

# 8. Verification Report

## Checks Run
...

## Passed
...

## Failed
...

## Root Causes
...

## Fixes
...

## Remaining Gaps
...

## Confidence
HIGH / MEDIUM / LOW